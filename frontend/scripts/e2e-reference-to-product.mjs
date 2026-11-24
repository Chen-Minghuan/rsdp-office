import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { once } from 'node:events'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { createReviewConfirmMock, fulfillReviewConfirm, confirmAllReviews } from './helpers/review-confirm-mock.mjs'

const cwd = fileURLToPath(new URL('../', import.meta.url))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = process.env.BASE_URL || `http://127.0.0.1:${port}`
const app = process.env.BASE_URL ? null : spawn(process.execPath, ['.output/server/index.mjs'], {
  cwd,
  env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' },
  stdio: 'ignore',
})

let browser
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || (app && app.exitCode !== null)) throw new Error('请先 pnpm build；预览服务未就绪')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1700, height: 1100 } })
  const page = await context.newPage()
  const source = {
    analysisId: 'MEETING-TEST', analysisRevision: 1, status: 'awaiting_confirm', sourceName: '会议室测试.dxf',
    inputMode: 'cad', geometrySource: 'cad_geometry', evidenceKind: 'REAL_SERVICE',
    unitResolution: { canonicalUnit: 'mm', confirmed: true },
    rooms: [{ roomId: 'OPEN-ROOM', label: '测试办公区', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[0, 0], [12000, 0], [12000, 8000], [0, 8000]] }],
  }
  const png1x1 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64')
  // 首次识别请求挂起，模拟视觉模型耗时，验证跨页面跳转时任务不中断。
  let recognizeCalls = 0
  let releaseRecognize
  const recognizeGate = new Promise(resolve => { releaseRecognize = resolve })
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  const reviewMock = createReviewConfirmMock()
  await context.route('**/api/v1/**', async route => {
    if (fulfillReviewConfirm(reviewMock, route)) return
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/office/projects' && route.request().method() === 'GET') {
      const project = { projectId: 'OFFICE-PROJECT-001', name: '参考摆位回归测试', customerRequirements: '', requirementsRevision: 1, leader: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'LEADER' }, collaborators: [], referenceImages: [], floors: [{ projectId: 'OFFICE-PROJECT-001', floorId: 'OFFICE-PROJECT-001', name: '默认楼层', sortOrder: 1 }] }
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: [project] }) })
    }
    if (path.endsWith('/layout-reference/source')) return route.fulfill({ contentType: 'image/png', body: png1x1 })
    if (path.endsWith('/layout-reference/recognize')) {
      recognizeCalls += 1
      if (recognizeCalls === 1) await recognizeGate
      return route.fulfill({ json: { code: 200, data: [{ templateId: 'desk-straight', categoryId: 'workstation', group: 'g1', x: 0.4, y: 0.4, width: 0.1, depth: 0.1, rotation: 0, confidence: 'high' }] } })
    }
    const data = path.endsWith('/floor-plan/MEETING-TEST') ? source : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const storageKey = 'rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001'
  const state = () => page.evaluate(key => JSON.parse(localStorage.getItem(key)), storageKey)
  const catalog = page.getByTestId('unit-module-catalog')
  await page.goto(`${baseUrl}/canvas?analysisId=MEETING-TEST`, { waitUntil: 'networkidle' })
  await page.locator('.review-rooms').getByRole('button', { name: '选择空间 测试办公区', exact: true }).waitFor()
  await confirmAllReviews(page)
  // 将空间标记为已校准（mm），使「参考原图摆位」可用
  await page.evaluate(key => {
    const draft = JSON.parse(localStorage.getItem(key))
    for (const zone of draft.objects.filter(object => object.kind === 'zone')) {
      zone.reviewUnit = 'mm'
      zone.mmPerSourceUnit = 1
    }
    localStorage.setItem(key, JSON.stringify(draft))
  }, storageKey)
  await page.goto(`${baseUrl}/canvas?analysisId=MEETING-TEST`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()
  await page.waitForURL(url => url.searchParams.get('zoneId') === 'OPEN-ROOM')

  // 打开参考原图摆位并发起识别（请求被挂起，模拟模型耗时）
  await page.getByTestId('mixed-layout-reference').click()
  await page.getByTestId('reference-recognize').waitFor()
  const recognizeRequest = page.waitForRequest('**/layout-reference/recognize')
  await page.getByTestId('reference-recognize').click()
  await recognizeRequest
  assert.equal(recognizeCalls, 1, '识别请求已发出')
  const recognizeButton = page.getByTestId('reference-recognize')
  assert.equal(await recognizeButton.innerText(), '正在识别…', '识别中状态可见')
  assert.ok(await recognizeButton.isDisabled(), '识别中按钮禁用')

  // 识别中跳转到商品库：任务在 store 中继续结算，不被页面卸载中断
  await page.getByRole('link', { name: '商品库', exact: true }).click()
  await page.waitForURL('**/products')
  releaseRecognize()
  await page.waitForFunction(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    return Object.values(store.referenceRecognitions).some(task => task.status === 'success')
  }, null, { timeout: 10000 })

  // 回到画布：面板自动重开并展示识别结果
  await page.goBack()
  await page.waitForURL(url => url.searchParams.get('zoneId') === 'OPEN-ROOM')
  await page.locator('[data-testid="layout-reference-panel"] >> text=识别估计').waitFor()
  await page.getByTestId('reference-apply').click()
  assert.ok((await state()).objects.some(item => item.layoutReference?.analysisId === 'MEETING-TEST'), '参考原图家具已应用')

  // 画布已有家具图例时：再次点击识别被拦截并弹窗提醒，不再发起请求
  await page.getByTestId('mixed-layout-reference').click()
  await page.getByTestId('reference-recognize').waitFor()
  await page.getByTestId('reference-recognize').click()
  const blockedDialog = page.getByTestId('reference-recognize-blocked')
  await blockedDialog.waitFor()
  assert.ok((await blockedDialog.innerText()).includes('当前画布上已经有家具图例'), '弹窗提醒已有家具图例')
  await page.getByTestId('reference-recognize-blocked-ok').click()
  await blockedDialog.waitFor({ state: 'detached' })
  assert.equal(recognizeCalls, 1, '已有家具时不再发起识别请求')

  // 切到产品选型，点击图例并在画布上放置
  await page.getByTestId('tab-product-selection').click()
  await catalog.locator('[data-module-id="desk-straight"]').click()
  const target = await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const rect = document.querySelector('[data-testid="office-design-canvas"]').getBoundingClientRect()
    return { screenX: rect.left + rect.width / 2 + store.panX, screenY: rect.top + rect.height / 2 + store.panY + 6000 * store.zoom }
  })
  await page.mouse.move(target.screenX, target.screenY)
  await page.mouse.click(target.screenX, target.screenY)
  const manual = (await state()).objects.find(item => item.kind === 'furniture' && item.zoneId === 'OPEN-ROOM' && !item.layoutGroupId && !item.layoutReference)
  assert.ok(manual, '参考摆位后仍可手动添加模块')

  console.log('PASS: recognition survives page navigation, blocked when furniture exists, reference layout applied, then product module placement works')
} finally {
  try { await browser?.close() } finally {
    if (app && app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
