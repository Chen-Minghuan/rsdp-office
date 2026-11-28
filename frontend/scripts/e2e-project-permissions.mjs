import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

// 项目成员权限 UI 回归：独立端口 + 模拟 API。
// 覆盖：主号勾选权限的级联规则与 PUT 请求体（权限真实发出）、取消「项目查看」级联清空、
// 无改动不发请求、只读协作者全部入口关闭（EDIT/MANAGE_MEMBERS/QUOTE_READ/EXPORT_PROJECT/EXPORT_QUOTE）、
// 报价查看与报价导出分离、毛利字段随 pricingMode 为 null 隐藏（后端 MARGIN_READ 软校验契约）、
// 仅 MANAGE_MEMBERS 协作者可管成员但不可编辑内容、不可授权（PUT 不含 permissions）。
const cwd = fileURLToPath(new URL('../', import.meta.url))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = `http://127.0.0.1:${port}`
const app = spawn(process.execPath, ['.output/server/index.mjs'], {
  cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' },
  stdio: 'ignore',
})
let browser
try {
  let up = false
  for (let attempt = 0; attempt < 75; attempt++) {
    try { if ((await fetch(baseUrl)).ok) { up = true; break } } catch {}
    if (app.exitCode !== null) throw new Error('测试预览服务启动失败，请先执行 pnpm build')
    await new Promise(resolve => setTimeout(resolve, 200))
  }
  assert.ok(up, '测试预览服务没有就绪')
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })

  const projectId = 'OPRJ-PERM'
  const floorId = 'OFLR-PERM'
  // authEnabled 在构建产物中恒为 true；DEALER 短路全部权限检查，USER 走项目成员权限分支
  const dealer = { userId: 'BOSS-1', username: 'boss', displayName: '主号', role: 'DEALER' }
  const collaboratorAccount = { userId: 'COLLAB-1', username: 'collab', displayName: '协作甲', role: 'USER' }
  let authUser = dealer
  const directory = {
    'LEAD-1': { userId: 'LEAD-1', username: 'lead', displayName: '负责人甲' },
    'COLLAB-1': { userId: 'COLLAB-1', username: 'collab', displayName: '协作甲' },
    'USR-3': { userId: 'USR-3', username: 'other', displayName: '协作丙' },
  }
  const project = {
    projectId, name: '权限测试项目', customerRequirements: '', requirementsRevision: 1, status: 'DESIGNING',
    leader: { ...directory['LEAD-1'], role: 'LEADER', permissions: ['READ', 'EDIT', 'MANAGE_MEMBERS'] },
    collaborators: [{ ...directory['COLLAB-1'], role: 'COLLABORATOR', permissions: [] }],
    referenceImages: [],
    floors: [{ floorId, projectId, name: '1F', sortOrder: 0, referenceImages: [] }],
  }
  const putBodies = []
  // 模拟无 MARGIN_READ 时后端置 null 的软校验契约：pricingMode/pricingValue/factoryPrice 均为 null
  const quotationRecord = {
    quotationId: 'QT-1', projectId, projectName: '权限测试项目', revision: 1, status: 'CONFIRMED',
    pricingMode: null, pricingValue: null, totalAmount: 412.8, missingPriceLines: 0,
    createdBy: 'LEAD-1', createdAt: '2026-11-25T10:00:00',
    lines: [{ productName: '行政桌 1200', variant: '暖白 / 钢脚', qty: 1, factoryPrice: null, unitPrice: 412.8, subtotal: 412.8 }],
  }

  let canvasDto = null
  let cartLines = []
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: authUser } }))
  await context.route('**/api/v1/**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status, message: '成功', data }) })
    if (path === '/api/v1/office/projects' && request.method() === 'GET') return respond([project])
    if (path === '/api/v1/office/projects/budgets') return respond({})
    if (path === '/api/v1/office/users/assignable') return respond(Object.values(directory))
    if (path === `/api/v1/office/projects/${projectId}/members` && request.method() === 'PUT') {
      const body = request.postDataJSON()
      putBodies.push(body)
      // 与后端语义一致：permissions 缺省时保留原有授权
      const apply = (userId, role, previous) => ({ ...directory[userId], role, permissions: body.permissions?.[userId] ?? previous ?? [] })
      project.leader = apply(body.leaderUserId, 'LEADER', project.leader?.userId === body.leaderUserId ? project.leader.permissions : null)
      project.collaborators = (body.collaboratorUserIds || []).map(userId =>
        apply(userId, 'COLLABORATOR', project.collaborators.find(member => member.userId === userId)?.permissions))
      return respond(project)
    }
    if (path === `/api/v1/office/projects/${projectId}/quotation/latest`) return respond(quotationRecord)
    if (path === `/api/v1/office/projects/${projectId}/quotation/latest-record`) return respond(quotationRecord)
    if (path === '/api/v1/office/products') return respond([
      { id: 'P1', rspuId: 'R1', variantId: 'V1', widthMm: 1200, depthMm: 550, heightMm: 750, widthCm: 120, depthCm: 55, name: '行政桌 1200', desc: '', variant: '暖白 / 钢脚', price: 1999, kind: 'desk' },
    ])
    // 画布与购物车按楼层 ID 挂在 projects/{floorId} 路径下；只读成员不读 localStorage，数据只能来自接口
    if (path === `/api/v1/office/projects/${floorId}/canvas`) return respond(canvasDto)
    if (path === `/api/v1/office/projects/${floorId}/cart`) return respond(cartLines)
    return respond(path.endsWith('/canvas') ? null : [])
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))

  const benchUrl = `${baseUrl}/workspace/project-bench?view=overview&floorId=${floorId}`
  const tree = page.locator('section[aria-label="项目与楼层"]')
  const dialog = page.locator('form', { hasText: '编辑项目与楼层' })
  // 需求面板也有「编辑」按钮；项目设置入口按 title 区分（随权限为「编辑项目名称和楼层名称」或「管理项目成员」）
  const settingsEntry = tree.locator('button[title="编辑项目名称和楼层名称"], button[title="管理项目成员"]')
  const openSettings = async () => {
    await settingsEntry.click()
    await dialog.waitFor()
    // 等待成员候选加载完成（ProjectMemberEditor 挂载即请求 assignable 用户）
    await dialog.locator('[data-testid="collaborator-search"]').waitFor()
  }
  const save = () => dialog.getByRole('button', { name: '保存修改' }).click()

  // 场景 1（主号）：勾选「毛利查看」级联勾上「报价查看」「项目查看」；保存后 PUT 请求体含全部四项
  await page.goto(benchUrl, { waitUntil: 'networkidle' })
  await openSettings()
  await dialog.locator('table[aria-label="项目成员权限"]').waitFor()
  await page.getByLabel('协作甲的毛利查看').check()
  assert.ok(await page.getByLabel('协作甲的项目查看').isChecked(), '勾选毛利查看应级联勾上项目查看')
  assert.ok(await page.getByLabel('协作甲的报价查看').isChecked(), '勾选毛利查看应级联勾上报价查看')
  await page.getByLabel('协作甲的报价导出').check()
  const firstPut = page.waitForRequest(request => request.method() === 'PUT' && request.url().includes(`/projects/${projectId}/members`))
  await save()
  const grantBody = (await firstPut).postDataJSON()
  assert.equal(grantBody.leaderUserId, 'LEAD-1', 'PUT 应携带负责人')
  assert.deepEqual(grantBody.collaboratorUserIds, ['COLLAB-1'], 'PUT 应携带协作者集合')
  assert.deepEqual([...grantBody.permissions['COLLAB-1']].sort(), ['EXPORT_QUOTE', 'MARGIN_READ', 'QUOTE_READ', 'READ'],
    '勾选毛利查看+报价导出应发出 READ/QUOTE_READ/MARGIN_READ/EXPORT_QUOTE 四项权限')
  assert.deepEqual([...grantBody.permissions['LEAD-1']].sort(), ['EDIT', 'MANAGE_MEMBERS', 'READ'],
    '负责人未改动的权限应原样发出')
  await page.waitForLoadState('networkidle')
  await dialog.waitFor({ state: 'detached' })
  // 回读链路：刷新后重开弹窗，权限勾选态来自项目数据
  await page.reload({ waitUntil: 'networkidle' })
  await openSettings()
  assert.ok(await page.getByLabel('协作甲的毛利查看').isChecked(), '刷新后毛利查看应回读为已勾选')
  assert.ok(await page.getByLabel('协作甲的报价导出').isChecked(), '刷新后报价导出应回读为已勾选')

  // 场景 2（主号）：取消「项目查看」级联清空该成员其余权限；无任何改动时保存不发 PUT
  await page.getByLabel('协作甲的项目查看').uncheck()
  for (const label of ['项目编辑', '报价查看', '毛利查看', '方案导出', '报价导出', '成员管理']) {
    assert.ok(!(await page.getByLabel(`协作甲的${label}`).isChecked()), `取消项目查看后「${label}」应被级联清空`)
  }
  const secondPut = page.waitForRequest(request => request.method() === 'PUT' && request.url().includes(`/projects/${projectId}/members`))
  await save()
  assert.deepEqual((await secondPut).postDataJSON().permissions['COLLAB-1'], [], '取消项目查看后该成员权限应为空数组')
  await dialog.waitFor({ state: 'detached' })
  await openSettings()
  const putsBeforeNoop = putBodies.length
  await save()
  await dialog.waitFor({ state: 'detached' })
  assert.equal(putBodies.length, putsBeforeNoop, '权限与成员均无改动时不得发出 PUT members 请求')

  // 场景 3（只读协作者，仅 READ）：编辑/成员管理/报价/导出入口全部关闭
  authUser = collaboratorAccount
  project.collaborators[0].permissions = ['READ']
  await page.goto(benchUrl, { waitUntil: 'networkidle' })
  await tree.getByText('权限测试项目').waitFor()
  assert.equal(await settingsEntry.count(), 0, '无 MANAGE_MEMBERS/EDIT 时不得显示项目编辑入口')
  assert.equal(await tree.getByRole('button', { name: '＋ 添加新楼层' }).count(), 0, '无 EDIT 时不得显示添加楼层入口')
  await tree.getByRole('button', { name: /权限测试项目/ }).dblclick()
  assert.equal(await page.locator('input[aria-label="双击编辑项目名称"]').count(), 0, '无 EDIT 时双击项目名不得进入编辑')
  await page.locator('[data-testid="workspace-center-trigger"]').click()
  const panel = page.locator('[data-testid="workspace-center-panel"]')
  await panel.waitFor()
  assert.equal(await panel.getByText('商品报价').count(), 0, '无 QUOTE_READ 时用户面板不得显示商品报价入口')
  await page.keyboard.press('Escape')
  await page.goto(`${baseUrl}/workspace/quotes`, { waitUntil: 'networkidle' })
  await page.getByText('当前账号暂无报价查看权限。').waitFor()
  // 方案页：无 EXPORT_PROJECT/EDIT 时导出与确认入口关闭，只读查看仍可用。
  // 只读成员不读 localStorage（canEditFloor 为否），画布与购物车只能来自接口
  canvasDto = {
    revision: 3, mode: 'overview', designZoneId: null, blank: false, files: [],
    objects: [
      { id: 'Z1', kind: 'zone', name: '开放办公区', x: 0, y: 0, w: 6000, h: 4000, zoneType: 'OPEN_OFFICE', tone: 'blue', reviewUnit: 'mm', confirmed: true, reviewConfirmation: { status: 'CONFIRMED' },
        polygon: [[0, 0], [6000, 0], [6000, 4000], [0, 4000]], obstacles: [], facilities: [] },
      { id: 'A1', kind: 'furniture', zoneId: 'Z1', furnitureTemplateId: 'desk-straight', label: '行政桌', x: 400, y: 400, w: 1200, h: 600, rotation: 0,
        productId: 'P1', layoutConfirmed: true, productSelection: { product: null, cartLineId: 'L1', sourceCanvasRevision: 3 } },
    ],
  }
  cartLines = [{ id: 'L1', productId: 'P1', zoneId: 'Z1', zoneName: '开放办公区', qty: 1 }]
  await page.goto(`${baseUrl}/proposal?floorId=${floorId}`, { waitUntil: 'networkidle' })
  await page.locator('.proposal-aside').getByText('方案摘要').waitFor()
  assert.equal(await page.getByRole('button', { name: /导出方案清单 CSV/ }).count(), 0, '无 EXPORT_PROJECT 时不得显示导出方案清单入口')
  assert.equal(await page.getByRole('button', { name: /确认方案/ }).count(), 0, '无 EDIT 时不得显示确认方案入口')
  assert.equal(await page.getByRole('link', { name: /返回继续设计/ }).count(), 0, '无 EDIT 时不得显示返回继续设计入口')
  await page.getByRole('link', { name: /查看空间总览/ }).waitFor()

  // 场景 4（QUOTE_READ 无 EXPORT_QUOTE）：报价可见、导出隐藏；pricingMode 为 null 时毛利字段不展示
  project.collaborators[0].permissions = ['READ', 'QUOTE_READ']
  await page.goto(benchUrl, { waitUntil: 'networkidle' })
  await page.locator('[data-testid="workspace-center-trigger"]').click()
  await panel.waitFor()
  await panel.getByText('商品报价').waitFor()
  await page.keyboard.press('Escape')
  await page.goto(`${baseUrl}/workspace/quotes`, { waitUntil: 'networkidle' })
  await page.getByText('行政桌 1200').waitFor()
  await page.getByText('报价合计 ¥412.8').waitFor()
  assert.equal(await page.getByRole('columnheader', { name: '出厂价' }).count(), 0, 'pricingMode 为 null（无毛利查看）时不得显示出厂价列')
  assert.equal(await page.getByRole('columnheader', { name: '商品毛利' }).count(), 0, 'pricingMode 为 null（无毛利查看）时不得显示商品毛利列')
  assert.equal(await page.getByRole('button', { name: '导出已确认报价' }).count(), 0, '无 EXPORT_QUOTE 时不得显示导出已确认报价按钮')
  project.collaborators[0].permissions = ['READ', 'QUOTE_READ', 'EXPORT_QUOTE']
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByText('行政桌 1200').waitFor()
  await page.getByRole('button', { name: '导出已确认报价' }).waitFor()

  // 场景 5（MANAGE_MEMBERS 无 EDIT）：可管成员但不可改内容、不可授权
  project.collaborators[0].permissions = ['READ', 'MANAGE_MEMBERS']
  await page.goto(benchUrl, { waitUntil: 'networkidle' })
  const manageEntry = settingsEntry
  await manageEntry.waitFor()
  assert.equal(await manageEntry.getAttribute('title'), '管理项目成员', '仅 MANAGE_MEMBERS 时入口提示应为管理项目成员')
  await manageEntry.click()
  await dialog.waitFor()

  assert.ok(await page.getByLabel('编辑项目名称').isDisabled(), '无 EDIT 时项目名称不可编辑')
  assert.equal(await dialog.locator('table[aria-label="项目成员权限"]').count(), 0, '非主号不得渲染权限授权表格')
  await dialog.locator('[data-testid="collaborator-option"]', { hasText: '协作丙' }).click()
  await dialog.locator('[data-testid="collaborator-tag"]', { hasText: '协作丙' }).waitFor()
  const thirdPut = page.waitForRequest(request => request.method() === 'PUT' && request.url().includes(`/projects/${projectId}/members`))
  await save()
  const membersBody = (await thirdPut).postDataJSON()
  assert.ok(!('permissions' in membersBody), '非主号保存成员时 PUT 不得携带 permissions 字段')
  assert.deepEqual([...membersBody.collaboratorUserIds].sort(), ['COLLAB-1', 'USR-3'], '协作者集合应包含新增成员')
  await page.waitForLoadState('networkidle')

  assert.deepEqual(errors, [], `不应有未捕获错误：${errors.join('; ')}`)
  console.log('项目成员权限 UI 通过：勾选级联与 PUT 请求体、取消级联清空、无改动不请求、只读协作者入口全关、报价查看/导出分离与毛利列隐藏、仅成员管理协作者五场景。API 全部模拟。')
} finally {
  await browser?.close()
  app.kill()
}
