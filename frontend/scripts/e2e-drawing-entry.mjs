import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { once } from 'node:events'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

// 独立测试端口及模拟 API，测试结束关闭测试服务，不使用用户的开发服务。
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
  let ready = false
  for (let attempt = 0; attempt < 75; attempt++) {
    try { if ((await fetch(baseUrl)).ok) { ready = true; break } } catch {}
    if (app.exitCode !== null) throw new Error('测试预览服务启动失败，请先执行 pnpm build')
    await new Promise(resolve => setTimeout(resolve, 200))
  }
  assert.ok(ready, '测试预览服务没有就绪')
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext()
  // Match public HTTP: randomUUID is unavailable, getRandomValues still works.
  await context.addInitScript(() => Object.defineProperty(Crypto.prototype, 'randomUUID', { value: undefined, configurable: true }))
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXioAAAAASUVORK5CYII=', 'base64')


  let complete = false
  let fail = false
  let posts = 0
  let blockNext = false
  let releaseQuery
  const preview = name => 'data:image/svg+xml;base64,' + Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="800" height="500"><rect width="800" height="500" fill="white"/><path d="M60 60H740V440H60Z M400 60V440" fill="none" stroke="black" stroke-width="8"/><text x="140" y="200" font-size="30">${name}</text></svg>`).toString('base64')
  const old = {
    analysisId: 'DRAWING-5', sourceDrawingId: 'UPLOAD-5', sourceName: '5.png', status: 'awaiting_confirm', inputMode: 'image',
    imageSha256: '5'.repeat(64), previewUrl: preview('OLD 5'), previewBounds: { minX: 0, minY: 0, maxX: 8000, maxY: 5000 },
    unitResolution: { canonicalUnit: 'mm', confirmed: true },
    rooms: [{ roomId: 'ROOM-5', label: '旧图5会议室', roomType: 'MEETING_ROOM', polygon: [[600,600],[4000,600],[4000,4400],[600,4400]], geometrySource: 'ai_vision' }],
  }
  await context.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    const respond = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
    if (path === '/api/v1/floor-plan/analyze') {
      posts++
      return respond({ analysisId: 'DRAWING-7-' + posts, taskId: 'TASK-' + posts })
    }
    if (path.startsWith('/api/v1/floor-plan/DRAWING-7-')) {
      if (blockNext) {
        blockNext = false
        await new Promise(resolve => { releaseQuery = resolve })
      }
      return respond({
      ...old, analysisId: path.split('/').at(-1), sourceDrawingId: undefined, sourceName: '7.png', imageSha256: '7'.repeat(64), previewUrl: preview('NEW 7'),
      status: fail ? 'failed' : complete ? 'awaiting_confirm' : 'analyzing', errorMessage: fail ? '当前7.png识别失败' : null,
      processing: { stage: fail ? 'FAILED' : complete ? 'DONE' : 'RECOGNIZING', elapsedMs: 300, timingsMs: { prepare: 100 } },
      rooms: complete && !fail ? [{ ...old.rooms[0], roomId: 'ROOM-7', label: '新图7办公区', roomType: 'OPEN_OFFICE' }] : [],
      })
    }
    if (path === '/api/v1/floor-plan/DRAWING-5') return respond(old)
    return respond(path.includes('/canvas') || path.endsWith('/OFFICE-PROJECT-001') ? null : [])
  })
  await page.goto(baseUrl, { waitUntil: 'networkidle' })
  await page.evaluate(old => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.$patch({ files: [{ drawingId: 'UPLOAD-5', analysisId: 'DRAWING-5', name: '5.png', size: 1, type: 'PNG', mock: false }] })
    store.importFloorPlanToZones(old)
    store.objects.push({ kind: 'furniture', id: 'OLD-DESK', zoneId: 'ROOM-5', productId: '', x: 1000, y: 1000, w: 1200, h: 600, rotation: 0, layoutConfirmed: true })
    store.spaceTemplate = store.captureSpaceTemplate('5.png · 空间模板')
    store.saveLocal()
  }, old)
  const upload = page.locator('input[type="file"]').first()
  const replace = async () => {
    await upload.setInputFiles({ name: '7.png', mimeType: 'image/png', buffer: png })
    await page.waitForFunction(() => !document.querySelector('input[type="file"]').disabled)
  }
  await replace()
  const firstUploadId = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).files[0].drawingId)
  assert.ok(firstUploadId)
  assert.ok(await page.getByRole('button', { name: '进入空间总览', exact: true }).isDisabled())
  // 顶部步骤/侧栏或直接地址不能绕过当前图纸的绑定，旧数据仍保留但不能冒充新图。
  await page.goto(baseUrl + '/canvas?analysisId=DRAWING-5&mode=design&zoneId=ROOM-5', { waitUntil: 'networkidle' })
  await page.getByTestId('drawing-entry').waitFor()
  await page.getByRole('heading', { name: '当前图纸：7.png', exact: true }).waitFor()
  assert.equal(await page.locator('.review-room-card').count(), 0)
  assert.equal(await page.locator('[data-layer="underlay"]').count(), 0)
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).floorPlan.analysisId), 'DRAWING-5')
  await page.getByRole('link', { name: '返回图纸识别', exact: true }).click()
  await page.getByRole('button', { name: '识别图纸', exact: true }).click()
  await page.getByText('正在识别空间', { exact: true }).waitFor()
  await page.getByRole('button', { name: '进入空间总览', exact: true }).click()
  await page.getByTestId('drawing-entry').waitFor()
  await page.getByAltText('当前图纸预览').waitFor()
  assert.equal(await page.locator('.review-room-card').count(), 0)
  assert.match(await page.getByAltText('当前图纸预览').getAttribute('src'), /^data:image\/svg/)
  await page.screenshot({ path: '/tmp/rsdp-drawing-7-processing.png' })
  complete = true
  await page.getByRole('button', { name: '选择空间 新图7办公区', exact: true }).waitFor()
  const current = () => page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    return { plan: store.floorPlan, files: store.files, objects: store.objects, template: store.spaceTemplate, undo: store.undoStack }
  })
  let state = await current()
  assert.equal(state.plan.analysisId, 'DRAWING-7-1')
  assert.equal(state.plan.sourceDrawingId, firstUploadId)
  assert.equal(state.files[0].analysisId, 'DRAWING-7-1')
  assert.equal(state.template, null)
  assert.deepEqual(state.objects.map(object => object.id), ['ROOM-7'])
  assert.ok(state.undo.some(snapshot => JSON.parse(snapshot).floorPlan?.analysisId === 'DRAWING-5'), '导入前的旧稿进入撤销快照')
  assert.equal(await page.getByText('5.png · 空间模板', { exact: true }).count(), 0)
  assert.equal(await page.locator('.review-source-file strong').innerText(), '7.png')
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.updateObject('ROOM-7', { name: '7图已编辑空间' })
  })
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '选择空间 7图已编辑空间', exact: true }).waitFor()
  assert.equal(posts, 1, '进入空间和刷新只查询同一任务，不重复识别')
  await page.screenshot({ path: '/tmp/rsdp-drawing-7-current.png' })
  // 远端/本地空稿恢复只有任务绑定时，只补来源，不重新填回用户已删除的空间。
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.setBlank()
    store.floorPlan = null
    store.saveLocal()
  })
  await page.reload({ waitUntil: 'networkidle' })
  await page.locator('.review-source-file strong').filter({ hasText: '7.png' }).waitFor()
  assert.equal(await page.locator('.review-room-card').count(), 0, '已清空的图纸刷新不得自动重填空间')
  assert.equal((await current()).plan.analysisId, 'DRAWING-7-1')
  // 同文件名、同大小再次上传仍是新上传，不能误用上一次空间。
  await page.goto(baseUrl, { waitUntil: 'networkidle' })
  await replace()
  assert.notEqual(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).files[0].drawingId), firstUploadId)
  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.getByTestId('drawing-entry').waitFor()
  assert.equal(await page.getByRole('button', { name: '选择空间 7图已编辑空间', exact: true }).count(), 0)
  await page.getByRole('link', { name: '返回图纸识别', exact: true }).click()
  fail = true
  await page.getByRole('button', { name: '识别图纸', exact: true }).click()
  await page.getByRole('alert').filter({ hasText: '当前7.png识别失败' }).waitFor()
  await page.getByRole('button', { name: '进入空间总览', exact: true }).click()
  await page.getByTestId('drawing-entry').waitFor()
  await page.getByRole('alert').filter({ hasText: '当前7.png识别失败' }).waitFor()
  assert.ok(await page.getByAltText('当前图纸预览').isVisible())
  assert.equal(await page.locator('.review-room-card').count(), 0)
  assert.equal((await current()).plan.analysisId, 'DRAWING-7-1', '失败不清空旧稿，也不能展示为当前图纸')
  // 旧任务查询在途时换图，迟到结果不能覆盖当前上传和原设计。
  fail = false
  blockNext = true
  await page.getByRole('button', { name: '重试查询当前图纸', exact: true }).click()
  const deadline = Date.now() + 5000
  while (!releaseQuery && Date.now() < deadline) await page.waitForTimeout(50)
  assert.ok(releaseQuery)
  await page.goto(baseUrl, { waitUntil: 'domcontentloaded' })
  await upload.setInputFiles({ name: '9.png', mimeType: 'image/png', buffer: png })
  await page.waitForFunction(() => !document.querySelector('input[type="file"]').disabled)
  releaseQuery()
  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '当前图纸：9.png', exact: true }).waitFor()
  assert.equal((await current()).plan.analysisId, 'DRAWING-7-1')
  assert.equal(await page.locator('.review-room-card').count(), 0)
  // 上传页“进入空间”也须检查离开/换图：延迟应用响应不能覆盖新的上传。
  await page.goto(baseUrl, { waitUntil: 'networkidle' })
  await replace()
  await page.getByRole('button', { name: '识别图纸', exact: true }).click()
  await page.getByText('新图7办公区', { exact: true }).waitFor()
  blockNext = true
  releaseQuery = undefined
  await page.getByRole('button', { name: '进入空间总览', exact: true }).click()
  const applyDeadline = Date.now() + 5000
  while (!releaseQuery && Date.now() < applyDeadline) await page.waitForTimeout(50)
  assert.ok(releaseQuery)
  await page.goto(baseUrl, { waitUntil: 'domcontentloaded' })
  await upload.setInputFiles({ name: '9.png', mimeType: 'image/png', buffer: png })
  await page.waitForFunction(() => !document.querySelector('input[type="file"]').disabled)
  releaseQuery()
  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '当前图纸：9.png', exact: true }).waitFor()
  assert.equal((await current()).plan.analysisId, 'DRAWING-7-1')
  // 已完成的结果由底部统一入口直接应用，不能再打开旧图纸。
  await page.goto(baseUrl, { waitUntil: 'networkidle' })
  await replace()
  await page.getByRole('button', { name: '识别图纸', exact: true }).click()
  await page.getByText('新图7办公区', { exact: true }).waitFor()
  await page.getByRole('button', { name: '进入空间总览', exact: true }).click()
  await page.getByRole('button', { name: '选择空间 新图7办公区', exact: true }).waitFor()
  assert.equal((await current()).plan.analysisId, 'DRAWING-7-4')
  assert.deepEqual(errors, [])
  console.log('图纸交接 UI 通过：5→7、未识别/旧链接/设计入口防串图、识别中预览、完成导入、模板/家具隔离、旧稿快照、刷新保留编辑、同名同大小换图和失败保护。API 全部模拟。')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) {
      const exited = once(app, 'exit')
      app.kill('SIGTERM')
      await exited
    }
  }
}
