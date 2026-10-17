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
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXioAAAAASUVORK5CYII=', 'base64')
  let mode = 'image'
  let analyzeRequests = 0
  let selectedPage = null
  await context.route('**/api/v1/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const respond = data => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: '成功', data }) })
    if (url.pathname === '/api/v1/floor-plan/analyze') {
      analyzeRequests++
      const match = request.postDataBuffer().toString().match(/name="pageNumber"\r\n\r\n(\d+)/)
      selectedPage = match ? Number(match[1]) : null
      return respond({ analysisId: 'VISION-TEST', taskId: 'TASK-TEST' })
    }
    if (url.pathname === '/api/v1/floor-plan/VISION-TEST') {
      if (mode === 'error') return respond({ status: 'failed', errorMessage: '尚未配置图片/PDF 识别密钥，请填写 DASHSCOPE_API_KEY', rooms: [] })
      return respond({
        analysisId: 'VISION-TEST', status: 'awaiting_confirm', parseStatus: 'NEEDS_REVIEW', inputMode: mode,
        previewUrl: `data:image/png;base64,${png.toString('base64')}`,
        unitResolution: { canonicalUnit: 'px', confirmed: false, pageNumber: selectedPage, pageCount: mode === 'pdf' ? 3 : 1 },
        rooms: [{ roomId: 'R1', roomType: 'MEETING_ROOM', label: '测试会议室', dimensionConfidence: 'high', widthMm: null, depthMm: null, areaM2: null }],
        qualityIssues: [{ code: 'CALIBRATION_REQUIRED', message: '请校准实际长度' }],
      })
    }
    return respond(url.pathname.includes('/canvas') || url.pathname.endsWith('/OFFICE-PROJECT-001') ? null : [])
  })
  await page.goto(baseUrl, { waitUntil: 'networkidle' })
  const upload = page.locator('input[type="file"]').first()
  const waitForUpload = () => page.waitForFunction(() => !document.querySelector('input[type="file"]').disabled)
  assert.equal(await upload.getAttribute('multiple'), null)
  await upload.setInputFiles({ name: 'test-plan.png', mimeType: 'image/png', buffer: png })
  await waitForUpload()
  await page.getByRole('button', { name: '更换图纸', exact: true }).waitFor()
  // 拖入多个文件必须整体拒绝，不能悄悄采用第一张或污染已存文件。
  const multipleDrop = await page.evaluateHandle(() => {
    const data = new DataTransfer()
    data.items.add(new File(['first'], 'first.png', { type: 'image/png' }))
    data.items.add(new File(['second'], 'second.png', { type: 'image/png' }))
    return data
  })
  await page.getByText('点击或拖放以更换图纸', { exact: true }).dispatchEvent('drop', { dataTransfer: multipleDrop })
  await page.getByRole('alert').filter({ hasText: '一次只能上传一张图纸' }).waitFor()
  assert.equal(await page.getByLabel('移除 test-plan.png').count(), 1)
  assert.equal(await page.getByText('first.png', { exact: true }).count(), 0)
  await upload.setInputFiles({ name: 'invalid.txt', mimeType: 'text/plain', buffer: Buffer.from('invalid') })
  await waitForUpload()
  await page.getByRole('alert').filter({ hasText: '请选择 PDF' }).waitFor()
  assert.equal(await page.getByText('invalid.txt', { exact: true }).count(), 0)
  await page.getByRole('button', { name: '识别图纸', exact: true }).click()
  await page.getByText('实际尺寸与面积待校准', { exact: true }).waitFor()
  assert.equal(await page.getByText('待校准', { exact: true }).count(), 1)
  assert.equal(await page.getByText('测试会议室', { exact: true }).count(), 1)
  assert.equal(await page.getByText('高置信', { exact: true }).count(), 1)
  assert.ok(await page.getByAltText('图纸预览').isVisible())

  mode = 'pdf'
  await upload.setInputFiles({ name: 'two-pages.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\nUI-only-fixture') })
  await waitForUpload()
  await page.getByLabel('移除 two-pages.pdf').waitFor()
  assert.equal(await page.getByLabel('移除 test-plan.png').count(), 0)
  assert.equal(await page.getByAltText('图纸预览').count(), 0, '换图后不能残留旧识别结果')
  assert.equal(await page.getByRole('alert').count(), 0)
  const persistedNames = await page.evaluate(() => new Promise((resolve, reject) => {
    const open = indexedDB.open('rsdp-office-files', 1)
    open.onerror = () => reject(open.error)
    open.onsuccess = () => {
      const db = open.result
      const request = db.transaction('files', 'readonly').objectStore('files').getAllKeys()
      request.onsuccess = () => { resolve(request.result); db.close() }
      request.onerror = () => { reject(request.error); db.close() }
    }
  }))
  assert.deepEqual(persistedNames, ['two-pages.pdf'])
  // 刷新后仍然只有当前文件，且能从 IndexedDB 恢复二进制继续识别。
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByLabel('移除 two-pages.pdf').waitFor()
  assert.equal(await page.getByLabel('移除 test-plan.png').count(), 0)
  const pdfPage = page.getByLabel('two-pages.pdf 识别页码')
  await pdfPage.fill('2')
  const recognizePdf = pdfPage.locator('..').locator('..').getByRole('button', { name: '识别图纸', exact: true })
  await recognizePdf.click()
  await page.getByText('当前识别第 2 页，共 3 页。', { exact: true }).waitFor()
  assert.equal(selectedPage, 2)
  assert.equal(analyzeRequests, 2)

  mode = 'error'
  await recognizePdf.click()
  await page.getByText(/尚未配置图片\/PDF 识别密钥/).waitFor()
  assert.equal(await page.getByText('测试会议室', { exact: true }).count(), 0)
  assert.equal(await page.getByAltText('图纸预览').count(), 0)
  await page.getByLabel('移除 two-pages.pdf').click()
  await page.getByText('暂无图纸', { exact: false }).waitFor()
  assert.equal(await page.getByRole('button', { name: '更换图纸', exact: true }).count(), 0)
  await page.waitForFunction(() => !document.querySelector('input[type="file"]').disabled)
  const singleDrop = await page.evaluateHandle(() => {
    const data = new DataTransfer()
    data.items.add(new File(['single'], 'test-plan.png', { type: 'image/png' }))
    return data
  })
  await page.getByText('点击或拖放办公图纸到这里', { exact: true }).dispatchEvent('drop', { dataTransfer: singleDrop })
  await page.getByLabel('移除 test-plan.png').waitFor()
  await page.waitForFunction(() => !document.querySelector('input[type="file"]').disabled)
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    draft.files.push({ name: 'legacy-extra.png', size: 1, type: 'PNG', mock: false })
    localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
  })
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByLabel('移除 test-plan.png').waitFor()
  assert.equal(await page.getByLabel('移除 legacy-extra.png').count(), 0)
  assert.deepEqual(errors, [])
  console.log('图片/PDF 上传页验证通过：单图选择、批量拖放拒绝、格式校验、换图/移除/刷新、单文件持久化及识别预览/选页/失败反馈。使用模拟 API，未调用百炼。')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) {
      const exited = once(app, 'exit')
      app.kill('SIGTERM')
      await exited
    }
  }
}
