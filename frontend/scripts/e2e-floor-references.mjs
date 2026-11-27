import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

// 楼层参考图上传交互 UI 回归：独立端口 + 模拟 API。
// 覆盖：上传→保存→刷新恢复；上传及保存期间禁止交错编辑；连续编辑后重读保留最后一次结果；
// 保存失败显示 role=alert 且恢复后消失；只读成员不显示上传入口；画布工具栏参考图弹层。
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
  const projectId = 'OPRJ-REF'
  const floorId = 'OFLR-REF'
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXioAAAAASUVORK5CYII=', 'base64')
  // authEnabled 在构建产物中恒为 true，USER 角色走项目成员权限分支（DEALER 会短路）
  const authUser = { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' }
  const project = { projectId, name: '参考图项目', customerRequirements: '', requirementsRevision: 1, status: 'DESIGNING',
    leader: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'LEADER', permissions: ['READ', 'EDIT'] },
    collaborators: [], referenceImages: [],
    floors: [{ floorId, projectId, name: '1F', sortOrder: 0, referenceImages: [] }] }
  let uploadCount = 0
  let referencesPutCount = 0
  let uploadGate = null
  let putFail = false
  let putGate = null
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: authUser } }))
  await context.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const respond = (data, code = 200, message = '成功') => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code, message, data }) })
    if (path === '/api/v1/office/projects') return respond([project])
    if (path === '/api/v1/office/projects/budgets') return respond({})
    if (path === '/api/v1/images/upload' && request.method() === 'POST') {
      uploadCount += 1
      if (uploadGate) { const gate = uploadGate; uploadGate = null; await gate }
      return respond([`/uploads/images/e2e-${uploadCount}.png`])
    }
    if (path === `/api/v1/office/projects/${projectId}/floors/${floorId}/references` && request.method() === 'PUT') {
      referencesPutCount += 1
      const body = request.postDataJSON()
      if (putGate) { const gate = putGate; putGate = null; await gate }
      if (putFail) return respond(null, 500, '保存失败')
      project.floors[0].referenceImages = body.referenceImages
      return respond({ ...project.floors[0] })
    }
    return respond(path.endsWith('/canvas') ? null : [])
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))

  const benchUrl = `${baseUrl}/workspace/project-bench?view=overview&floorId=${floorId}`
  await page.goto(benchUrl, { waitUntil: 'networkidle' })
  // 组件根 testid 可能被父级 fallthrough 覆盖，两种取值都接受，再按标题过滤出楼层编辑器
  const editor = page.locator('section[data-testid="reference-images-editor"], section[data-testid="floor-reference-editor"]')
    .filter({ hasText: '楼层参考图' })
  await editor.waitFor()
  const names = () => editor.locator('[data-testid="reference-image-names"]')
  const uploadInput = editor.locator('input[type="file"]')
  const isReferencesPut = request => request.method() === 'PUT' && request.url().includes('/references')

  // 场景 1：上传 → PUT 保存（url 用上传返回的相对路径，name 取去扩展名的文件名）→ 刷新后从接口恢复
  const firstPut = page.waitForRequest(isReferencesPut)
  await uploadInput.setInputFiles({ name: '风格.png', mimeType: 'image/png', buffer: png })
  const putBody = (await firstPut).postDataJSON()
  assert.equal(putBody.referenceImages.length, 1, '首次保存只含新上传的一张')
  assert.equal(putBody.referenceImages[0].url, '/uploads/images/e2e-1.png', '保存应使用上传接口返回的地址')
  assert.equal(putBody.referenceImages[0].name, '风格', '名称应取去扩展名的文件名')
  await names().getByText('风格', { exact: true }).waitFor()
  await page.reload({ waitUntil: 'networkidle' })
  await names().getByText('风格', { exact: true }).waitFor()

  // 场景 2：上传 B 期间不能删除 A；保存 B 期间也不能再次编辑，刷新后 A/B 都保留。
  let releaseUpload
  uploadGate = new Promise(resolve => { releaseUpload = resolve })
  let releaseHeldPut
  putGate = new Promise(resolve => { releaseHeldPut = resolve })
  const putCountBeforeUpload = referencesPutCount
  const uploadStarted = page.waitForRequest(request => request.url().includes('/images/upload'))
  await uploadInput.setInputFiles({ name: '新增图B.png', mimeType: 'image/png', buffer: png })
  await uploadStarted
  const removeButton = editor.getByTestId('remove-reference-image').first()
  assert.equal(await removeButton.isDisabled(), true, '上传期间不得删除已有图片')
  assert.equal(await editor.getByTestId('upload-reference-image').isDisabled(), true, '上传期间不得再次上传')
  // 原生 click 不触发 disabled 按钮，不能在上传结果回来前提交删除保存。
  await removeButton.evaluate(button => button.click())
  assert.equal(referencesPutCount, putCountBeforeUpload)
  const uploadFinished = page.waitForResponse(response => response.url().includes('/images/upload'))
  const heldPut = page.waitForRequest(isReferencesPut)
  releaseUpload()
  await uploadFinished
  const pendingBody = (await heldPut).postDataJSON()
  assert.deepEqual(pendingBody.referenceImages.map(image => image.name), ['风格', '新增图B'])
  await page.getByText('正在保存…', { exact: true }).waitFor()
  assert.equal(await editor.getByTestId('upload-reference-image').count(), 0, '保存期间隐藏上传入口')
  assert.equal(await editor.getByTestId('remove-reference-image').count(), 0, '保存期间隐藏删除入口')
  const savedPut = page.waitForResponse(response => isReferencesPut(response.request()))
  releaseHeldPut()
  await savedPut
  await names().getByText('新增图B', { exact: true }).waitFor()
  await uploadInput.setInputFiles({ name: '后续图C.png', mimeType: 'image/png', buffer: png })
  await names().getByText('后续图C', { exact: true }).waitFor()
  await page.reload({ waitUntil: 'networkidle' })
  assert.deepEqual(await names().locator('li > span').allTextContents(), ['风格', '新增图B', '后续图C'])
  // 上传结束后删除恢复可用，删除保存也必须能刷新回读。
  await editor.getByTestId('remove-reference-image').first().click()
  await names().getByText('风格', { exact: true }).waitFor({ state: 'detached' })
  await page.reload({ waitUntil: 'networkidle' })
  assert.deepEqual(await names().locator('li > span').allTextContents(), ['新增图B', '后续图C'])

  // 场景 3：保存失败显示 role=alert，恢复后再次保存成功且提示消失
  putFail = true
  await uploadInput.setInputFiles({ name: '失败.png', mimeType: 'image/png', buffer: png })
  const alert = page.locator('p[role="alert"]', { hasText: '保存失败' })
  await alert.waitFor()
  putFail = false
  await uploadInput.setInputFiles({ name: '恢复.png', mimeType: 'image/png', buffer: png })
  await names().getByText('恢复', { exact: true }).waitFor()
  assert.equal(await alert.count(), 0, '保存成功后错误提示应消失')

  // 场景 4：只读成员（协作者仅 READ）不显示上传入口，列表仍可见；恢复 EDIT 后入口回来
  project.leader = { userId: 'OTHER-USER', username: 'leader', displayName: '负责人', role: 'LEADER', permissions: ['READ', 'EDIT'] }
  project.collaborators = [{ userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'COLLABORATOR', permissions: ['READ'] }]
  await page.reload({ waitUntil: 'networkidle' })
  await names().getByText('恢复', { exact: true }).waitFor()
  assert.equal(await editor.locator('[data-testid="upload-reference-image"]').count(), 0, '只读成员不得显示上传入口')
  project.leader = { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'LEADER', permissions: ['READ', 'EDIT'] }
  project.collaborators = []
  await page.reload({ waitUntil: 'networkidle' })
  await editor.locator('[data-testid="upload-reference-image"]').waitFor()

  // 场景 5：画布工具栏「参考图」弹层展示当前楼层参考图（工具栏仅在设计模式渲染，需预置已确认空间）
  project.floors[0].referenceImages = [
    { url: '/uploads/images/pop-1.png', name: '外立面' },
    { url: '/uploads/images/pop-2.png', name: '大堂' },
  ]
  await page.evaluate(key => {
    const state = JSON.parse(localStorage.getItem(key) || '{}')
    state.objects = [
      { id: 'Z1', kind: 'zone', name: '开放办公区', x: 0, y: 0, w: 6000, h: 4000, zoneType: 'OPEN_OFFICE', tone: 'blue', reviewUnit: 'mm', confirmed: true, reviewConfirmation: { status: 'CONFIRMED' },
        polygon: [[0, 0], [6000, 0], [6000, 4000], [0, 4000]], obstacles: [], facilities: [] },
    ]
    state.cart = []
    state.canvasRevision = 1; state.canvasMode = 'overview'; state.designZoneId = null
    state.floorPlan = null; state.spaceTemplate = null
    localStorage.setItem(key, JSON.stringify(state))
  }, `rsdp-office-v01:${authUser.userId}:${floorId}`)
  await page.goto(`${baseUrl}/canvas?floorId=${floorId}&mode=design&zoneId=Z1`, { waitUntil: 'networkidle' })
  await page.locator('[data-testid="toolbar-floor-references"]').click()
  const popover = page.locator('[data-testid="floor-reference-popover"]')
  await popover.waitFor()
  assert.match(await popover.textContent(), /楼层参考图 · 2/, '弹层标题应显示参考图数量')
  assert.equal(await popover.locator('img').count(), 2, '弹层应列出当前楼层全部参考图')

  assert.deepEqual(errors, [], `不应有未捕获错误：${errors.join('; ')}`)
  console.log('楼层参考图 UI 通过：上传/保存期间禁用编辑、连续新增及删除后刷新恢复、失败重试、只读权限、工具栏弹层。API 全部模拟。')
} finally {
  await browser?.close()
  app.kill()
}
