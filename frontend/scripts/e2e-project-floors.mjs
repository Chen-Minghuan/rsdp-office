import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const cwd = fileURLToPath(new URL('../', import.meta.url))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = `http://127.0.0.1:${port}`
const app = spawn(process.execPath, ['.output/server/index.mjs'], {
  cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' }, stdio: 'ignore',
})
let browser
try {
  let ready = false
  for (let attempt = 0; attempt < 75; attempt++) {
    try { if ((await fetch(baseUrl)).ok) { ready = true; break } } catch {}
    if (app.exitCode !== null) throw new Error('测试预览服务启动失败，请先执行 pnpm build')
    await new Promise(resolve => setTimeout(resolve, 200))
  }
  assert.ok(ready)
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  // 本地构建默认开启鉴权（frontend/.env VITE_AUTH_ENABLED=1），需 mock 会话接口
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试经销商', role: 'DEALER' } } }))
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXioAAAAASUVORK5CYII=', 'base64')
  const projects = []
  const saves = []
  const scopedCarts = new Map()
  const pendingCartReads = new Map()
  const summaryRequests = []
  const summaryPlans = []
  function planSummary(fail = false) {
    let release
    const promise = new Promise(resolve => { release = resolve })
    const plan = { promise, release, fail }
    summaryPlans.push(plan)
    return plan
  }
  planSummary()
  await context.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const respond = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
    const cartFloorId = path.match(/^\/api\/v1\/office\/projects\/([^/]+)\/cart$/)?.[1]
    if (request.method() === 'GET' && cartFloorId && scopedCarts.has(cartFloorId)) {
      await pendingCartReads.get(cartFloorId)
      return respond(scopedCarts.get(cartFloorId))
    }
    if (path === '/api/v1/office/projects' && request.method() === 'GET') return respond(projects)
    if (path === '/api/v1/floor-plan/analyze') return respond({ analysisId: 'ANALYSIS-2F', taskId: 'TASK-2F' })
    if (path === '/api/v1/floor-plan/ANALYSIS-2F') {
      return respond({ analysisId: 'ANALYSIS-2F', projectId: 'OFLR-2', status: 'awaiting_confirm', rooms: [] })
    }
    if (path === '/api/v1/office/projects' && request.method() === 'POST') {
      const body = request.postDataJSON()
      const isSecondProject = projects.length > 0
      const projectId = isSecondProject ? 'OPRJ-TEST-2' : 'OPRJ-TEST'
      const floorIdPrefix = isSecondProject ? 'OFLR-SECOND-' : 'OFLR-'
      const project = { projectId, name: body.name, customerRequirements: body.customerRequirements, requirementsRevision: 1,
        leader: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'LEADER' }, collaborators: [], referenceImages: [],
        floors: body.floors.map((floor, index) => ({ projectId, floorId: `${floorIdPrefix}${index + 1}`, name: floor.name, sortOrder: index + 1 })) }
      projects.push(project)
      return respond(project)
    }
    if (path === '/api/v1/office/projects/OPRJ-TEST' && request.method() === 'PATCH') {
      projects[0].name = request.postDataJSON().name
      return respond(projects[0])
    }
    if (path.startsWith('/api/v1/office/projects/OPRJ-TEST/floors/') && request.method() === 'PATCH') {
      const floor = projects[0].floors.find(item => item.floorId === path.split('/').at(-1))
      floor.name = request.postDataJSON().name
      return respond(floor)
    }
    if (path === '/api/v1/office/projects/OPRJ-TEST/requirements/summary' && request.method() === 'POST') {
      const body = request.postDataJSON()
      summaryRequests.push(body)
      const plan = summaryPlans[summaryRequests.length - 1]
      if (plan) await plan.promise
      if (plan?.fail) {
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 500, message: '模型暂不可用', data: null }) })
      }
      return respond({ keyPoints: [
        '总裁办公室采用现代简约风格，整体偏深色系。',
        '保留独立会客区，配置沙发、茶几与储物柜。',
        '开放办公区计划容纳60人，需保证通道顺畅。',
        '会议室配置长桌、会议椅及饮水设备。',
      ], sourceRevision: body.requirementsRevision, cacheHit: false })
    }
    if (path === '/api/v1/office/projects/OPRJ-TEST/requirements') {
      const project = projects[0]
      project.customerRequirements = request.postDataJSON().customerRequirements
      project.requirementsRevision++
      return respond(project)
    }
    if (path === '/api/v1/office/projects/OPRJ-TEST/floors') {
      const floor = { projectId: 'OPRJ-TEST', floorId: 'OFLR-3', name: request.postDataJSON().name, sortOrder: 3 }
      projects[0].floors.push(floor)
      return respond(floor)
    }
    if (path.endsWith('/canvas') && request.method() === 'PUT') { saves.push({ path, body: request.postDataJSON() }); return respond(null) }
    return respond(path.endsWith('/canvas') ? null : [])
  })

  const workbenchPath = '/workspace/project-bench'
  await page.goto(baseUrl + workbenchPath, { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '项目工作台', exact: true }).waitFor()
  const appSidebar = page.getByTestId('app-sidebar')
  assert.equal(await appSidebar.evaluate(element => getComputedStyle(element).scrollbarWidth), 'none', '侧栏应隐藏原生纵向滚动条，但保留滚动能力')
  const sidebarResizeHandle = page.getByTestId('sidebar-resize-handle')
  assert.equal(await sidebarResizeHandle.count(), 1, '桌面端应提供侧栏宽度拖拽把手')
  assert.equal(Math.round(await appSidebar.evaluate(element => element.getBoundingClientRect().width)), 240, '侧栏默认宽度应为 240px')

  async function dragSidebarTo(offsetX) {
    const box = await sidebarResizeHandle.boundingBox()
    assert.ok(box, '侧栏拖拽把手应可定位')
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width / 2 + offsetX, box.y + box.height / 2)
    await page.mouse.up()
  }

  await dragSidebarTo(80)
  assert.equal(Math.round(await appSidebar.evaluate(element => element.getBoundingClientRect().width)), 320, '向右拖动应增大侧边栏宽度')
  await dragSidebarTo(1000)
  assert.equal(Math.round(await appSidebar.evaluate(element => element.getBoundingClientRect().width)), await page.evaluate(() => Math.floor(innerWidth / 2)), '侧栏宽度不应超过页面可视宽度的一半')
  await dragSidebarTo(-1000)
  assert.equal(Math.round(await appSidebar.evaluate(element => element.getBoundingClientRect().width)), 192, '侧栏宽度不应小于 192px')

  await page.reload({ waitUntil: 'networkidle' })
  assert.equal(Math.round(await appSidebar.evaluate(element => element.getBoundingClientRect().width)), 240, '刷新后侧栏宽度应恢复默认值')
  await page.setViewportSize({ width: 600, height: 1000 })
  assert.equal(await sidebarResizeHandle.isVisible(), false, '窄屏不应显示侧栏宽度拖拽把手')
  await page.setViewportSize({ width: 1600, height: 1000 })
  await page.getByRole('button', { name: '新建项目', exact: true }).click()
  await page.getByPlaceholder('例如：深圳 XX 集团总部办公').fill('总部办公大楼')
  await page.getByPlaceholder('记录客户偏好、工位数量、预算或其他需要持续提醒的事项').fill('保留开放办公区，控制预算')
  await page.getByPlaceholder('例如：1F, 2F, 7~9F, 11F').fill('2F')
  await page.getByRole('button', { name: '添加楼层', exact: true }).click()
  assert.deepEqual(await page.locator('form span').filter({ hasText: /^1F$|^2F$/ }).allTextContents(), ['1F', '2F'])
  await page.getByRole('button', { name: '创建项目', exact: true }).click()
  await page.getByRole('heading', { name: '1F · 上传办公图纸', exact: true }).waitFor()
  assert.equal(new URL(page.url()).pathname, workbenchPath)
  assert.match(page.url(), /floorId=OFLR-1/)

  const requirementsPanel = page.getByTestId('project-requirements-panel')
  await requirementsPanel.waitFor()
  const floorSectionLabel = page.getByText('项目与楼层', { exact: true }).first()
  const panelBox = await requirementsPanel.boundingBox()
  const floorLabelBox = await floorSectionLabel.boundingBox()
  assert.ok(panelBox && floorLabelBox && panelBox.y < floorLabelBox.y, '项目需求面板应位于项目与楼层上方')
  const userSession = appSidebar.getByTestId('user-session')
  await userSession.waitFor()
  const sessionBox = await userSession.boundingBox()
  const footerBox = await appSidebar.getByText('办公空间智能设计平台', { exact: true }).last().boundingBox()
  assert.ok(sessionBox && footerBox && sessionBox.y + sessionBox.height <= footerBox.y, '用户信息与退出登录应位于侧栏底部平台标识上方')
  assert.equal(await page.locator('header').getByRole('button', { name: '退出登录', exact: true }).count(), 0, '顶栏不再显示退出登录')
  assert.equal(await appSidebar.getByRole('button', { name: '退出登录', exact: true }).count(), 1, '退出登录应移到侧栏底部')
  const rawRequirements = requirementsPanel.getByRole('textbox', { name: '项目需求原文', exact: true })
  await rawRequirements.waitFor()
  assert.equal(await rawRequirements.inputValue(), '保留开放办公区，控制预算')
  assert.equal(await rawRequirements.evaluate(element => element.readOnly), true, '原文展示面应使用只读文本框')
  assert.equal(await requirementsPanel.getByTestId('requirements-summary-face').getAttribute('aria-hidden'), 'true', '初始应显示原文而不是演示总结')
  const editRequirementsButton = requirementsPanel.getByRole('button', { name: '编辑', exact: true })
  const summarizeButton = requirementsPanel.getByRole('switch', { name: '总结', exact: true })
  const flipCard = requirementsPanel.getByTestId('requirements-flip-card')
  const summaryThumb = summarizeButton.getByTestId('summary-switch-thumb')
  assert.equal(await summarizeButton.getAttribute('aria-checked'), 'false', '原文时滑动开关应处于关闭状态')
  const rawThumbX = (await summaryThumb.boundingBox())?.x
  const summaryLabel = summarizeButton.locator('.requirements-summary-label')
  assert.ok(rawThumbX !== undefined && (await summaryLabel.boundingBox()).x > rawThumbX, '原文时“总结”文字应在滑块右侧')
  assert.equal(await summaryThumb.evaluate(element => getComputedStyle(element).backgroundColor), 'rgb(255, 254, 251)', '滑块应为白色圆钮')
  const rawFace = requirementsPanel.getByTestId('requirements-raw-face')
  const summaryFace = requirementsPanel.getByTestId('requirements-summary-face')
  const summaryTextarea = summaryFace.locator('textarea[aria-label="AI总结关键点"]')
  assert.equal(await summaryTextarea.count(), 1, '总结面应使用独立文本框')
  assert.equal(await summaryTextarea.evaluate(element => element.readOnly), true, 'AI总结文本框应只读')
  for (const [name, locator] of [['编辑按钮', editRequirementsButton], ['总结开关', summarizeButton], ['原文卡片', rawFace], ['总结卡片', summaryFace]]) {
    assert.notEqual(await locator.evaluate(element => getComputedStyle(element).borderTopLeftRadius), '0px', `${name}应显示圆角`)
  }
  assert.equal(await summaryThumb.evaluate(element => getComputedStyle(element).borderTopLeftRadius), '50%', '滑钮应为圆形')
  assert.notEqual(await summaryFace.evaluate(element => getComputedStyle(element).scrollbarWidth), 'none', '总结面保留一条原生滚动条')
  assert.notEqual(await rawFace.evaluate(element => getComputedStyle(element).scrollbarWidth), 'none', '原文面同样保留原生滚动条')
  assert.equal(await summaryTextarea.evaluate(element => getComputedStyle(element).scrollbarWidth), 'none', '总结文本框不应出现第二条滚动条')
  assert.equal(await summaryTextarea.evaluate(element => getComputedStyle(element).overflowY), 'hidden', '总结文本框自身不滚动')
  assert.equal(await flipCard.getAttribute('data-view'), 'raw')
  const summaryRequest = page.waitForRequest(request => new URL(request.url()).pathname === '/api/v1/office/projects/OPRJ-TEST/requirements/summary')
  await summarizeButton.click()
  await summaryRequest
  await requirementsPanel.getByText('生成中…', { exact: true }).waitFor()
  assert.equal(await flipCard.getAttribute('data-view'), 'raw', 'AI返回成功前应保持原文面')
  summaryPlans[0].release()
  await page.waitForFunction(() => document.querySelector('[data-testid="requirements-flip-card"]')?.getAttribute('data-view') === 'summary')
  assert.equal(await flipCard.getAttribute('data-view'), 'summary', '第一次点击总结应在AI返回后翻到总结面')
  assert.equal(await rawFace.getAttribute('aria-hidden'), 'true')
  assert.equal(await summaryFace.getAttribute('aria-hidden'), 'false')
  assert.equal(await requirementsPanel.getByText('演示数据', { exact: true }).count(), 0, '真实接口接入后不应再显示演示标记')
  assert.deepEqual(summaryRequests, [{ requirementsRevision: 1 }], '总结请求必须携带当前原文版本')
  assert.match(await summaryTextarea.inputValue(), /^1\. 总裁办公室采用现代简约风格，整体偏深色系。[\s\S]*4\. 会议室配置长桌、会议椅及饮水设备。$/, 'AI关键点应在文本框内按编号换行')
  await page.waitForTimeout(1000)
  assert.match(await flipCard.evaluate(element => getComputedStyle(element).transform), /^matrix3d\(/, '白色原文面与灰色总结面应作为正反两面一起3D翻转')
  assert.equal(await flipCard.evaluate(element => element.querySelectorAll(':scope > .requirements-face').length), 2, '两个文本框必须同属一张翻转卡片')
  assert.equal(await rawFace.evaluate(element => getComputedStyle(element).backgroundColor), 'rgb(255, 254, 251)', '正面应为白色原文')
  assert.equal(await summaryFace.evaluate(element => getComputedStyle(element).backgroundColor), 'rgb(241, 239, 235)', '背面应为灰色AI总结')
  assert.ok(await summaryTextarea.evaluate(element => element.scrollHeight <= element.clientHeight + 1), '总结文本框应随内容撑高，滚动只发生在卡片层')
  assert.equal(await summarizeButton.getAttribute('aria-checked'), 'true', '总结面应打开滑动开关')
  const summaryThumbX = (await summaryThumb.boundingBox())?.x
  assert.ok(rawThumbX !== undefined && summaryThumbX !== undefined && summaryThumbX > rawThumbX, '总结时滑块应实际向右滑动')
  assert.ok((await summaryLabel.boundingBox()).x < summaryThumbX, '总结时“总结”文字应换到滑块左侧')
  assert.equal(await summaryThumb.evaluate(element => getComputedStyle(element).backgroundColor), 'rgb(255, 254, 251)', '总结状态滑块仍为白色')
  await summarizeButton.click()
  assert.equal(await flipCard.getAttribute('data-view'), 'raw', '再次点击总结应翻回原文')
  assert.equal(await summarizeButton.getAttribute('aria-checked'), 'false')
  await page.waitForTimeout(1000)
  assert.equal((await summaryThumb.boundingBox())?.x, rawThumbX, '返回原文时滑块应回到左侧')
  assert.equal(await flipCard.evaluate(element => getComputedStyle(element).transform), 'none', '返回原文时整卡应翻回正面')
  assert.equal(await rawFace.getAttribute('aria-hidden'), 'false')
  assert.equal(await summaryFace.getAttribute('aria-hidden'), 'true')
  await summarizeButton.click()
  assert.equal(summaryRequests.length, 1, '同一版本再次查看总结不应重复调用生成接口')
  await editRequirementsButton.click()
  await page.getByRole('heading', { name: '客户需求备注', exact: true }).waitFor()
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await rawRequirements.waitFor()
  await summarizeButton.click()
  await page.reload({ waitUntil: 'networkidle' })
  await rawRequirements.waitFor()
  assert.equal(await requirementsPanel.getByTestId('requirements-summary-face').getAttribute('aria-hidden'), 'true', '刷新后仍应默认显示原文')

  const navigation = page.getByRole('navigation', { name: '工作区导航' })
  await navigation.getByRole('link', { name: '商品库', exact: true }).click()
  await page.waitForURL(url => url.pathname === '/products')
  await page.getByTitle('单击切换楼层，双击修改楼层名称').filter({ hasText: /^1F/ }).click()
  await page.getByRole('heading', { name: '1F · 上传办公图纸', exact: true }).waitFor()
  assert.equal(new URL(page.url()).pathname, workbenchPath)
  assert.equal(new URL(page.url()).searchParams.get('floorId'), 'OFLR-1')

  await navigation.getByRole('link', { name: '商品库', exact: true }).click()
  await page.waitForURL(url => url.pathname === '/products')
  await page.getByRole('button', { name: '2F', exact: true }).click()
  await page.getByRole('heading', { name: '2F · 上传办公图纸', exact: true }).waitFor()
  assert.equal(new URL(page.url()).pathname, workbenchPath)
  assert.equal(new URL(page.url()).searchParams.get('floorId'), 'OFLR-2')

  await navigation.getByRole('link', { name: '导入记录', exact: true }).click()
  await page.waitForURL(url => url.pathname === '/products/import-records')
  await page.getByTitle('单击切换楼层，双击修改楼层名称').filter({ hasText: /^2F/ }).click()
  await page.getByRole('heading', { name: '2F · 上传办公图纸', exact: true }).waitFor()
  assert.equal(new URL(page.url()).pathname, workbenchPath)
  assert.equal(new URL(page.url()).searchParams.get('floorId'), 'OFLR-2')
  await page.getByRole('button', { name: '1F', exact: true }).click()
  await page.getByRole('heading', { name: '1F · 上传办公图纸', exact: true }).waitFor()

  await page.goto(`${baseUrl}/products?floorId=OFLR-1&mode=design&zoneId=OLD-ZONE&analysisId=OLD-ANALYSIS`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '2F', exact: true }).click()
  await page.getByRole('heading', { name: '2F · 上传办公图纸', exact: true }).waitFor()
  assert.deepEqual([...new URL(page.url()).searchParams.entries()], [['floorId', 'OFLR-2']])
  await page.getByRole('button', { name: '1F', exact: true }).click()
  await page.getByRole('heading', { name: '1F · 上传办公图纸', exact: true }).waitFor()

  await page.locator('input[type="file"]').setInputFiles({ name: 'first.png', mimeType: 'image/png', buffer: png })
  await page.getByText('first.png', { exact: true }).last().waitFor()
  await page.getByRole('button', { name: '折叠1F空间', exact: true }).click()
  assert.equal(await page.getByTestId('design-space-switcher').isVisible(), false)
  await page.goto(`${baseUrl}/canvas?floorId=OFLR-1`, { waitUntil: 'networkidle' })
  await page.waitForURL(url => url.pathname === '/canvas' && url.searchParams.get('floorId') === 'OFLR-1')
  await page.getByRole('button', { name: '2F', exact: true }).click()
  await page.waitForURL(url => url.pathname === workbenchPath && url.searchParams.get('floorId') === 'OFLR-2')
  assert.equal(new URL(page.url()).search, '?floorId=OFLR-2', '侧栏楼层切换应清除原页面查询参数')
  await page.getByRole('heading', { name: '2F · 上传办公图纸', exact: true }).waitFor()
  assert.equal(await page.getByTestId('design-space-switcher').isVisible(), true)
  assert.equal(await page.getByText('first.png', { exact: true }).count(), 0)
  await page.locator('input[type="file"]').setInputFiles({ name: 'second.png', mimeType: 'image/png', buffer: png })
  await page.getByText('second.png', { exact: true }).last().waitFor()
  const recognitionRequest = page.waitForRequest(request => new URL(request.url()).pathname === '/api/v1/floor-plan/analyze')
  await page.getByRole('button', { name: '识别图纸', exact: true }).click()
  const request = await recognitionRequest
  const form = await new Response(request.postDataBuffer(), {
    headers: { 'Content-Type': request.headers()['content-type'] },
  }).formData()
  assert.equal(form.get('projectId'), 'OFLR-2')
  await page.getByText('识别房间', { exact: true }).waitFor()
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByText('second.png', { exact: true }).last().waitFor()
  await page.getByRole('button', { name: '1F', exact: true }).click()
  await page.getByText('first.png', { exact: true }).last().waitFor()
  assert.equal(await page.getByTestId('design-space-switcher').isVisible(), false)
  await page.getByRole('button', { name: '展开1F空间', exact: true }).click()
  assert.equal(await page.getByTestId('design-space-switcher').isVisible(), true)
  const records = await page.evaluate(async () => {
    const db = await new Promise((resolve, reject) => { const req = indexedDB.open('rsdp-office-files'); req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error) })
    const tx = db.transaction('floorFiles', 'readonly')
    return await new Promise((resolve, reject) => { const req = tx.objectStore('floorFiles').getAll(); req.onsuccess = () => resolve(req.result.map(item => [item.floorId, item.name])); req.onerror = () => reject(req.error) })
  })
  assert.deepEqual(records.map(item => item[1]).sort(), ['first.png', 'second.png'])
  assert.ok(records.some(item => item[0] === 'OFLR-1') && records.some(item => item[0] === 'OFLR-2'))

  await page.getByRole('button', { name: /总部办公大楼/ }).click()
  assert.equal(await page.getByRole('button', { name: '2F', exact: true }).isVisible(), false)
  await page.getByRole('button', { name: /总部办公大楼/ }).click()
  await page.getByRole('button', { name: '需求', exact: true }).click()
  await page.locator('form textarea').fill('新增 20 个工位，保留会议室')
  await page.getByRole('button', { name: '保存备注', exact: true }).click()
  await page.getByText('已保存。既有推荐和方案请结合新需求重新复核。', { exact: true }).waitFor()
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await rawRequirements.waitFor()
  assert.equal(await rawRequirements.inputValue(), '新增 20 个工位，保留会议室')
  await rawRequirements.dblclick()
  await page.getByRole('textbox', { name: '双击填写客户需求', exact: true }).fill('双击补充需求\n第二行备注')
  await page.getByRole('textbox', { name: '双击填写客户需求', exact: true }).press('Control+Enter')
  await rawRequirements.waitFor()
  assert.equal(await rawRequirements.inputValue(), '双击补充需求\n第二行备注')
  assert.equal(await page.getByRole('textbox', { name: '双击填写客户需求', exact: true }).count(), 0)

  const oldFailure = planSummary(true)
  await summarizeButton.click()
  await page.waitForFunction(() => document.querySelector('[data-testid="project-requirements-panel"]')?.querySelector('button[aria-busy="true"]'))
  await rawRequirements.dblclick()
  await page.getByRole('textbox', { name: '双击填写客户需求', exact: true }).fill('旧请求完成后再次编辑')
  await page.getByRole('textbox', { name: '双击填写客户需求', exact: true }).press('Control+Enter')
  await rawRequirements.waitFor()
  assert.equal(await rawRequirements.inputValue(), '旧请求完成后再次编辑')
  const newSummary = planSummary(false)
  await summarizeButton.click()
  await requirementsPanel.getByText('生成中…', { exact: true }).waitFor()
  oldFailure.release()
  await page.waitForTimeout(100)
  assert.equal(await requirementsPanel.getByRole('alert').filter({ hasText: '模型暂不可用' }).count(), 0, '旧请求失败不能污染新原文')
  await requirementsPanel.getByText('生成中…', { exact: true }).waitFor()
  assert.equal(await flipCard.getAttribute('data-view'), 'raw', '旧请求失败不能清除新请求加载状态')
  newSummary.release()
  await page.waitForFunction(() => document.querySelector('[data-testid="requirements-flip-card"]')?.getAttribute('data-view') === 'summary')

  await page.getByTitle('编辑项目名称和楼层名称', { exact: true }).click()
  await page.getByRole('textbox', { name: '编辑项目名称' }).fill('总部办公项目（已编辑）')
  await page.getByRole('textbox', { name: '编辑楼层名称 1' }).fill('首层')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await page.getByRole('heading', { name: '编辑项目与楼层', exact: true }).waitFor({ state: 'hidden' })
  await page.getByRole('heading', { name: '首层 · 上传办公图纸', exact: true }).waitFor()
  assert.equal(await page.getByRole('region', { name: '项目设计流程' }).getByText('first.png', { exact: true }).count(), 0)
  assert.match(page.url(), /floorId=OFLR-1/)
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFLR-1')).files[0].name), 'first.png')

  await page.getByRole('button', { name: /首层 当前/ }).dblclick()
  await page.getByRole('textbox', { name: '双击编辑楼层名称', exact: true }).fill('一层')
  await page.getByRole('textbox', { name: '双击编辑楼层名称', exact: true }).press('Enter')
  await page.getByRole('button', { name: /一层 当前/ }).waitFor()
  await page.getByRole('button', { name: /总部办公项目（已编辑）/ }).dblclick()
  await page.getByRole('textbox', { name: '双击编辑项目名称', exact: true }).fill('总部项目双击')
  await page.getByRole('textbox', { name: '双击编辑项目名称', exact: true }).press('Enter')
  await page.getByRole('button', { name: /总部项目双击/ }).waitFor()
  const secondFloor = page.getByTitle('单击切换楼层，双击修改楼层名称').filter({ hasText: /^2F/ })
  await secondFloor.click()
  await page.getByRole('button', { name: /2F 当前/ }).waitFor()
  await secondFloor.dblclick()
  await page.getByRole('textbox', { name: '双击编辑楼层名称', exact: true }).fill('不会保存')
  await page.getByRole('textbox', { name: '双击编辑楼层名称', exact: true }).press('Escape')
  assert.equal(await page.getByRole('button', { name: /2F 当前/ }).count(), 1)
  await page.waitForURL(/floorId=OFLR-2/)
  await page.getByRole('button', { name: '一层', exact: true }).click()
  await page.waitForURL(/floorId=OFLR-1/)
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFLR-1')).files[0].name), 'first.png')

  await page.getByRole('button', { name: '＋ 添加新楼层', exact: true }).click()
  await page.getByRole('textbox', { name: '新楼层名称' }).fill('3F')
  await page.getByRole('button', { name: '添加', exact: true }).click()
  await page.getByRole('heading', { name: '3F · 上传办公图纸', exact: true }).waitFor()
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '3F · 上传办公图纸', exact: true }).waitFor()

  await page.getByRole('button', { name: '＋ 新建项目', exact: true }).click()
  await page.getByPlaceholder('例如：深圳 XX 集团总部办公').fill('滨江智能办公')
  await page.getByRole('button', { name: '创建项目', exact: true }).click()
  await page.waitForURL(/floorId=OFLR-SECOND-1/)
  await page.goto(`${baseUrl}/canvas?floorId=OFLR-SECOND-1`, { waitUntil: 'networkidle' })
  await page.waitForURL(url => url.pathname === '/canvas' && url.searchParams.get('floorId') === 'OFLR-SECOND-1')
  const projectTree = page.getByRole('region', { name: '项目与楼层', exact: true })
  assert.equal(await projectTree.getByRole('button', { name: /总部项目双击/ }).count(), 0, '侧栏只显示当前项目')
  assert.equal(await projectTree.getByRole('button', { name: /滨江智能办公/ }).count(), 1)

  const projectBenchLink = page.getByRole('link', { name: '项目工作台', exact: true })
  const navigationYBefore = (await projectBenchLink.boundingBox())?.y
  const projectListToggle = page.getByRole('button', { name: '项目列表', exact: true })
  await projectListToggle.click()
  const projectList = page.getByTestId('project-list-popover')
  await projectList.waitFor()
  assert.equal(await projectListToggle.getAttribute('aria-expanded'), 'true')
  assert.ok((await projectListToggle.getAttribute('class') || '').split(/\s+/).includes('bg-cream'), '项目列表打开时按钮应显示选中状态')
  const navigationYAfter = (await projectBenchLink.boundingBox())?.y
  assert.equal(navigationYAfter, navigationYBefore, '项目列表应覆盖侧栏内容，不能推挤导航')
  await page.waitForTimeout(5100)
  await projectList.waitFor()
  assert.equal(await projectListToggle.getAttribute('aria-expanded'), 'true')
  assert.ok((await projectListToggle.getAttribute('class') || '').split(/\s+/).includes('bg-cream'), '项目列表应保持打开，直到用户主动关闭或选择项目')
  await projectList.getByRole('button', { name: '切换到项目 总部项目双击', exact: true }).click()
  await projectList.waitFor({ state: 'hidden' })
  await page.waitForURL(url => url.pathname === workbenchPath && url.searchParams.get('floorId') === 'OFLR-1')
  assert.equal(new URL(page.url()).search, '?floorId=OFLR-1', '项目切换应清除原页面查询参数')
  await projectTree.getByRole('button', { name: /总部项目双击/ }).waitFor()
  assert.equal(await projectTree.getByRole('button', { name: /滨江智能办公/ }).count(), 0, '切换后只保留新当前项目的树')

  await page.getByRole('link', { name: '返回项目列表', exact: true }).click()
  const workbenchList = page.getByRole('region', { name: '项目列表', exact: true })
  await workbenchList.getByRole('button', { name: '进入项目 总部项目双击', exact: true }).first().click()
  await page.waitForURL(url => url.pathname === workbenchPath && url.searchParams.get('floorId') === 'OFLR-1')
  await page.getByText('first.png', { exact: true }).last().waitFor()
  await page.getByRole('button', { name: '新建项目', exact: true }).click()
  await page.getByRole('heading', { name: '创建办公项目', exact: true }).waitFor()
  await page.getByRole('button', { name: '关闭创建项目', exact: true }).click()
  // 项目切换用例已回到 1F；导航回归的空间数据必须写入后续点击的 3F。
  await page.getByTitle('单击切换楼层，双击修改楼层名称').filter({ hasText: /^3F/ }).click()
  await page.getByRole('heading', { name: '3F · 上传办公图纸', exact: true }).waitFor()
  await page.evaluate(() => {
    const office = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    office.$patch({ objects: [{
      id: 'NAVIGATION-ZONE', kind: 'zone', name: '导航会议室', zoneType: 'MEETING_ROOM',
      x: 0, y: 0, w: 4000, h: 3000, polygon: [[0, 0], [4000, 0], [4000, 3000], [0, 3000]],
    }] })
  })
  await navigation.getByRole('link', { name: '商品库', exact: true }).click()
  await page.waitForURL(url => url.pathname === '/products')
  await page.getByTitle('单击切换楼层，双击修改楼层名称').filter({ hasText: /^3F/ }).click()
  await page.waitForURL(url => url.pathname === '/canvas' && url.searchParams.get('floorId') === 'OFLR-3')
  await page.getByRole('button', { name: '一层', exact: true }).click()
  await page.getByRole('heading', { name: '一层 · 上传办公图纸', exact: true }).waitFor()
  assert.deepEqual([...new URL(page.url()).searchParams.entries()], [['floorId', 'OFLR-1']])
  await page.getByText('first.png', { exact: true }).last().waitFor()
  await page.goto(`${baseUrl}/?floorId=OFLR-1`, { waitUntil: 'networkidle' })
  await page.waitForURL(url => url.pathname === workbenchPath && url.searchParams.get('floorId') === 'OFLR-1')
  await page.getByRole('heading', { name: '一层 · 上传办公图纸', exact: true }).waitFor()
  await page.goto(`${baseUrl}${workbenchPath}/?floorId=OFLR-1`, { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '一层 · 上传办公图纸', exact: true }).waitFor()

  const first = { floorId: 'OFLR-1', project: '总部项目双击', floor: '一层', zoneId: 'PROJECT-A-ROOM', name: '总部独立会议室', qty: 2 }
  const floorTreeTarget = { floorId: 'OFLR-2', project: '总部项目双击', floor: '2F', zoneId: 'PROJECT-A-ROOM-2', name: '总部二层独立会议室', qty: 1 }
  const second = { floorId: 'OFLR-SECOND-1', project: '滨江智能办公', floor: '1F', zoneId: 'PROJECT-B-ROOM', name: '滨江独立会议室', qty: 7 }
  for (const { floorId, zoneId, name, qty } of [first, floorTreeTarget, second]) {
    scopedCarts.set(floorId, [{ lineId: `cart-${floorId}`, projectId: floorId, zoneId, zoneName: name, productId: 'SAMPLE-OF-03', qty }])
  }
  await page.evaluate(fixtures => {
    for (const { floorId, zoneId, name, qty } of fixtures) {
      const key = `rsdp-office-v01:MOCK-USER:${floorId}`
      const zone = {
        id: zoneId, kind: 'zone', name, zoneType: 'MEETING_ROOM', x: 0, y: 0, w: 4000, h: 3000,
        polygon: [[0, 0], [4000, 0], [4000, 3000], [0, 3000]], reviewUnit: 'mm', evidenceKind: 'MANUAL',
        geometrySource: 'manual_geometry', reviewConfirmation: { status: 'CONFIRMED' },
      }
      const furniture = Array.from({ length: qty }, (_, index) => ({
        id: `${floorId}-FURNITURE-${index + 1}`, kind: 'furniture', zoneId, furnitureTemplateId: 'meeting-table',
        productId: 'SAMPLE-OF-03', label: '回归测试会议桌', x: 300 + index * 1300, y: 500, w: 1200, h: 600, rotation: 0,
        layoutConfirmed: true, productSelection: { product: { id: 'SAMPLE-OF-03' }, cartLineId: `cart-${floorId}`, sourceCanvasRevision: 1 },
      }))
      localStorage.setItem(key, JSON.stringify({
        ...JSON.parse(localStorage.getItem(key) || '{}'), files: [], floorPlan: null, spaceTemplate: null,
        canvasMode: 'overview', designZoneId: null, objects: [zone, ...furniture],
        initialized: true, workspaceId: 'old-saved-workspace',
        cart: [{ id: `cart-${floorId}`, projectId: floorId, zoneId, zoneName: name, productId: 'SAMPLE-OF-03', qty }],
      }))
    }
  }, [first, floorTreeTarget, second])

  const steps = page.getByRole('navigation', { name: '项目设计步骤' })
  const overviewStep = steps.getByRole('link', { name: '2 空间总览', exact: true })
  const layoutStep = steps.getByRole('link', { name: '3 布局选品', exact: true })
  const budgetStep = steps.getByRole('link', { name: '4 预算清单', exact: true })
  const budget = page.locator('.proposal-page')
  async function verifyProjectSteps(active, other) {
    for (const step of [overviewStep, layoutStep, budgetStep]) {
      assert.equal(new URL(await step.getAttribute('href'), baseUrl).searchParams.get('floorId'), active.floorId)
    }
    await overviewStep.click()
    await page.locator('.review-rooms').getByRole('button', { name: `选择空间 ${active.name}`, exact: true }).waitFor()
    assert.equal(await page.getByText(other.name, { exact: true }).count(), 0)
    await layoutStep.click()
    await page.waitForURL(url => url.searchParams.get('mode') === 'design' && url.searchParams.get('zoneId') === active.zoneId && url.searchParams.get('floorId') === active.floorId)
    await page.getByTestId('office-design-canvas').waitFor()
    await budgetStep.click()
    await budget.getByText(`${active.project} / ${active.floor}`, { exact: true }).waitFor()
    await budget.getByRole('button', { name: new RegExp(active.name) }).first().waitFor()
    await budget.locator('.aside-summary').filter({ hasText: '商品总数' }).getByText(`${active.qty} 件`, { exact: true }).waitFor()
    assert.equal(await budget.getByText(other.name, { exact: true }).count(), 0)
  }
  await page.goto(`${baseUrl}/canvas?floorId=${first.floorId}`, { waitUntil: 'networkidle' })
  await verifyProjectSteps(first, second)

  // 楼层树在 proposal 内切换时，必须等目标楼层加载后再做门禁，不能放行旧楼层的匹配状态。
  scopedCarts.set(floorTreeTarget.floorId, [{ lineId: `cart-${floorTreeTarget.floorId}`, projectId: floorTreeTarget.floorId, zoneId: floorTreeTarget.zoneId, zoneName: floorTreeTarget.name, productId: 'SAMPLE-OF-03', qty: floorTreeTarget.qty + 1 }])
  await projectTree.getByTitle('单击切换楼层，双击修改楼层名称').filter({ hasText: /^2F/ }).click()
  await page.waitForURL(url => url.pathname === '/canvas' && url.searchParams.get('floorId') === floorTreeTarget.floorId && url.searchParams.get('mode') === 'design')
  const proposalGateDialog = page.getByRole('dialog', { name: '请先选择家具' })
  await proposalGateDialog.waitFor()
  await proposalGateDialog.getByText(floorTreeTarget.name, { exact: true }).waitFor()
  await proposalGateDialog.getByText('画布 1 件 · 购物车 2 件', { exact: true }).waitFor()
  assert.equal(await proposalGateDialog.getByText(first.name, { exact: true }).count(), 0, '门禁不能显示来源楼层的核对结果')
  await proposalGateDialog.getByRole('button', { name: '关闭缺失家具提示', exact: true }).click()
  scopedCarts.set(floorTreeTarget.floorId, [{ lineId: `cart-${floorTreeTarget.floorId}`, projectId: floorTreeTarget.floorId, zoneId: floorTreeTarget.zoneId, zoneName: floorTreeTarget.name, productId: 'SAMPLE-OF-03', qty: floorTreeTarget.qty }])

  // 已配齐来源楼层、目标楼层缺少一件选品时，直达 proposal 必须先切换并校验目标楼层。
  await page.evaluate(floorId => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$router.push({ path: '/canvas', query: { floorId } }), first.floorId)
  await page.waitForURL(url => url.pathname === '/canvas' && url.searchParams.get('floorId') === first.floorId)
  await page.waitForFunction(floorId => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').workspaceId === floorId, first.floorId)
  scopedCarts.set(second.floorId, [{ lineId: `cart-${second.floorId}`, projectId: second.floorId, zoneId: second.zoneId, zoneName: second.name, productId: 'SAMPLE-OF-03', qty: second.qty + 1 }])
  await page.evaluate(floorId => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$router.push({ path: '/proposal', query: { floorId } }), second.floorId)
  await page.waitForURL(url => url.pathname === '/canvas' && url.searchParams.get('floorId') === second.floorId && url.searchParams.get('mode') === 'design')
  await proposalGateDialog.waitFor()
  await proposalGateDialog.getByText(second.name, { exact: true }).waitFor()
  await proposalGateDialog.getByText('画布 7 件 · 购物车 8 件', { exact: true }).waitFor()
  assert.equal(await proposalGateDialog.getByText(first.name, { exact: true }).count(), 0, '门禁不能显示旧楼层的核对结果')
  await proposalGateDialog.getByRole('button', { name: '关闭缺失家具提示', exact: true }).click()

  // 目标楼层补齐同一购物车行后，应当允许进入 proposal，不能被旧楼层状态误拦截。
  scopedCarts.set(second.floorId, [{ lineId: `cart-${second.floorId}`, projectId: second.floorId, zoneId: second.zoneId, zoneName: second.name, productId: 'SAMPLE-OF-03', qty: second.qty }])
  await page.evaluate(floorId => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$router.push({ path: '/canvas', query: { floorId } }), first.floorId)
  await page.waitForURL(url => url.pathname === '/canvas' && url.searchParams.get('floorId') === first.floorId)
  await page.waitForFunction(floorId => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').workspaceId === floorId, first.floorId)
  await page.evaluate(floorId => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$router.push({ path: '/proposal', query: { floorId } }), second.floorId)
  await page.waitForURL(url => url.pathname === '/proposal' && url.searchParams.get('floorId') === second.floorId)
  await budget.getByRole('button', { name: new RegExp(second.name) }).first().waitFor()
  assert.equal(await page.getByRole('dialog', { name: '请先选择家具' }).count(), 0, '目标楼层数量一致时不应拦截 proposal')

  // 后续跨项目加载遮罩场景从第一项目开始，确保点击项目列表确实触发目标楼层读取。
  await page.evaluate(floorId => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$router.push({ path: '/canvas', query: { floorId } }), first.floorId)
  await page.waitForURL(url => url.pathname === '/canvas' && url.searchParams.get('floorId') === first.floorId)
  await page.waitForFunction(floorId => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').workspaceId === floorId, first.floorId)

  let releaseCartRead
  pendingCartReads.set(second.floorId, new Promise(resolve => { releaseCartRead = resolve }))
  const secondCartRequest = page.waitForRequest(request => new URL(request.url()).pathname === `/api/v1/office/projects/${second.floorId}/cart`)
  await projectListToggle.click()
  await projectList.getByRole('button', { name: `切换到项目 ${second.project}`, exact: true }).click()
  try {
    await secondCartRequest
    await page.getByText('正在加载当前楼层…', { exact: true }).waitFor()
    assert.equal(await budget.count(), 0)
    assert.equal(await steps.count(), 0)
    assert.equal(await projectTree.getByText(first.name, { exact: true }).count(), 0)
  } finally {
    pendingCartReads.delete(second.floorId)
    releaseCartRead()
  }
  await page.waitForURL(url => url.pathname === workbenchPath && url.searchParams.get('floorId') === second.floorId)
  await page.getByRole('heading', { name: '1F · 上传办公图纸', exact: true }).waitFor()
  await verifyProjectSteps(second, first)
  // 跨项目直达保留目标楼层的空间参数，同楼层切换入口不会沿用演示参数。
  await page.evaluate(({ floorId, zoneId }) => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$router.push({ path: '/canvas', query: { floorId, mode: 'design', zoneId } }), first)
  await page.waitForURL(url => url.searchParams.get('floorId') === first.floorId && url.searchParams.get('zoneId') === first.zoneId)
  await page.getByTestId('office-design-canvas').waitFor()
  await page.goto(`${baseUrl}/proposal?floorId=${first.floorId}&preview=1`, { waitUntil: 'networkidle' })
  await page.getByTitle('单击切换楼层，双击修改楼层名称').filter({ hasText: /^3F/ }).click()
  await page.waitForURL(url => url.pathname === '/proposal' && url.search === '?floorId=OFLR-3')
  await budget.getByText(`${first.project} / 3F`, { exact: true }).waitFor()
  assert.equal(await budget.getByText('方案演示', { exact: true }).count(), 0)

  assert.ok(saves.every(save => save.path.includes(save.body.projectId)), '画布保存必须使用原楼层隔离键')
  assert.deepEqual(errors, [])
  console.log('E2E project floors passed')
} finally {
  if (browser) await browser.close()
  app.kill('SIGTERM')
}
