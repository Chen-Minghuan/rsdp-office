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

  let stage = 'PREPARING'
  let status = 'analyzing'
  let preview = false
  let offline = false
  let posts = 0
  let gets = 0
  let currentId = 'PROGRESS-1'
  let lateRelease
  const startedAt = new Date().toISOString()
  await context.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const respond = data => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: '成功', data }) })
    if (path === '/api/v1/floor-plan/analyze') {
      posts++
      const body = request.postDataBuffer().toString()
      assert.match(body, /name="background"\r\n\r\ntrue/)
      assert.match(body, /name="pageNumber"\r\n\r\n2/)
      return respond({ analysisId: currentId, taskId: 'TASK' })
    }
    if (path.startsWith('/api/v1/floor-plan/PROGRESS-')) {
      gets++
      if (offline) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 503, message: '临时查询失败', data: null }) })
      if (lateRelease) await new Promise(resolve => { lateRelease.resolve = resolve })
      return respond({
        analysisId: path.split('/').at(-1), status, inputMode: 'pdf', parseStatus: 'NEEDS_REVIEW',
        errorMessage: status === 'failed' ? '模型超时，请重试' : null,
        previewUrl: preview ? `data:image/png;base64,${png.toString('base64')}` : null,
        unitResolution: { canonicalUnit: 'px', confirmed: false, pageNumber: 2, pageCount: 3 },
        processing: { stage, startedAt, elapsedMs: 6500, cacheHit: preview,
          timingsMs: { ...(preview ? { prepare: 125 } : {}), ...(['REFINING', 'SAVING', 'DONE'].includes(stage) ? { spaces: 5000, labels: 4000 } : {}), ...(stage === 'DONE' ? { contours: 200, save: 30 } : {}) } },
        rooms: status === 'awaiting_confirm' ? [{ roomId: 'R1', label: '最终会议室', roomType: 'MEETING_ROOM', dimensionConfidence: 'high' }] : [],
      })
    }
    return respond(path.includes('/canvas') || path.endsWith('/OFFICE-PROJECT-001') ? null : [])
  })
  await page.goto(baseUrl, { waitUntil: 'networkidle' })
  await page.locator('input[type="file"]').first().setInputFiles({ name: 'progress.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\nUI-fixture') })
  await page.waitForFunction(() => !document.querySelector('input[type="file"]').disabled)
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.$patch({ objects: [{ kind: 'note', id: 'KEEP-EDIT', text: '保留用户编辑', x: 5, y: 6, w: 100, h: 100 }] })
    store.saveLocal()
  })
  await page.getByLabel('progress.pdf 识别页码').fill('2')
  await page.getByRole('button', { name: '识别图纸', exact: true }).click()
  await page.getByText('正在处理图片', { exact: true }).waitFor()
  assert.equal(await page.getByAltText('图纸预览').count(), 0)
  preview = true
  stage = 'RECOGNIZING'
  await page.getByText('正在识别空间', { exact: true }).waitFor()
  assert.ok(await page.getByAltText('图纸预览').isVisible())
  assert.equal(await page.getByRole('button', { name: /应用.*画布/ }).count(), 0)
  await page.getByText('处理图片 ✓ 0.1 秒', { exact: true }).waitFor()
  await page.getByText('已复用相同图纸、页码和处理参数的标准底图', { exact: true }).waitFor()
  await page.screenshot({ path: '/tmp/rsdp-recognition-running.png' })
  // 兼容更新前只在 sessionStorage 存任务、文件尚未存分析绑定的页面。
  await page.evaluate(() => {
    const task = JSON.parse(sessionStorage.getItem('rsdp-office-recognition-task-v1'))
    delete task.drawingId
    sessionStorage.setItem('rsdp-office-recognition-task-v1', JSON.stringify(task))
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    delete draft.files[0].analysisId
    localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
  })
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.getByText('正在识别空间', { exact: true }).waitFor()
  assert.equal(await page.getByLabel('progress.pdf 识别页码').inputValue(), '2')
  assert.equal(posts, 1, '刷新只恢复查询，不能重复调用模型')
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).files[0].analysisId), 'PROGRESS-1')
  offline = true
  await page.getByRole('button', { name: '继续查询识别进度', exact: true }).waitFor()
  assert.ok(await page.getByAltText('图纸预览').isVisible())
  offline = false
  stage = 'REFINING'
  await page.getByRole('button', { name: '继续查询识别进度', exact: true }).click()
  await page.getByText('正在校正轮廓', { exact: true }).waitFor()
  await page.getByText('识别空间 ✓ 5.0 秒', { exact: true }).waitFor()
  assert.equal(posts, 1)
  status = 'awaiting_confirm'
  stage = 'SAVING'
  await page.getByText('正在保存结果', { exact: true }).waitFor()
  assert.equal(await page.getByText('最终会议室', { exact: true }).count(), 0)
  stage = 'DONE'
  await page.getByText('识别完成', { exact: true }).waitFor()
  await page.getByText('最终会议室', { exact: true }).waitFor()
  await page.getByText('校正轮廓 ✓ 0.2 秒', { exact: true }).waitFor()
  assert.equal(await page.evaluate(() => sessionStorage.getItem('rsdp-office-recognition-task-v1')), null)
  const settledGets = gets
  await page.waitForTimeout(1300)
  assert.equal(gets, settledGets, '完成后停止轮询')
  // 新任务失败后仍保留已经生成的底图，空间不会自动写入画布。
  currentId = 'PROGRESS-2'
  stage = 'RECOGNIZING'
  status = 'analyzing'
  await page.getByRole('button', { name: '识别图纸', exact: true }).click()
  await page.getByText('正在识别空间', { exact: true }).waitFor()
  status = 'failed'
  stage = 'FAILED'
  await page.getByRole('alert').filter({ hasText: '模型超时' }).waitFor()
  assert.ok(await page.getByAltText('图纸预览').isVisible())
  assert.equal(await page.getByText('最终会议室', { exact: true }).count(), 0)
  assert.equal(await page.getByRole('button', { name: /应用.*画布/ }).count(), 0)
  // 首次详情查询失败时也能重试同一任务。
  currentId = 'PROGRESS-3'
  offline = true
  status = 'analyzing'
  stage = 'RECOGNIZING'
  await page.getByRole('button', { name: '识别图纸', exact: true }).click()
  await page.getByRole('button', { name: '继续查询识别进度', exact: true }).waitFor()
  offline = false
  await page.getByRole('button', { name: '继续查询识别进度', exact: true }).click()
  await page.getByText('正在识别空间', { exact: true }).waitFor()
  // 在途查询返回前离开，旧页面不应继续安排查询；回来恢复同一任务。
  lateRelease = {}
  const deadline = Date.now() + 5000
  while (!lateRelease.resolve && Date.now() < deadline) await page.waitForTimeout(50)
  assert.ok(lateRelease.resolve)
  // 离开页面后释放旧查询，检查卸载保护与任务续查。
  await page.goto(baseUrl + '/products', { waitUntil: 'domcontentloaded' })
  lateRelease.resolve()
  lateRelease = null
  await page.goto(baseUrl, { waitUntil: 'domcontentloaded' })
  await page.getByText('正在识别空间', { exact: true }).waitFor()
  status = 'awaiting_confirm'
  stage = 'DONE'
  await page.getByText('最终会议室', { exact: true }).waitFor()
  assert.equal(posts, 3)
  assert.deepEqual(await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    return store.objects.map(object => ({ id: object.id, x: object.x, y: object.y }))
  }), [{ id: 'KEEP-EDIT', x: 5, y: 6 }], '后台识别、刷新和失败不能修改已有设计稿')
  assert.deepEqual(errors, [])
  await page.screenshot({ path: '/tmp/rsdp-progressive-recognition.png', fullPage: true })
  console.log('渐进识别模拟 UI 通过：底图先到、真实阶段/耗时/缓存提示、刷新与页面返回续查、首轮/后续查询失败重试、完成停查及失败保留底图。未调用真实 AI。')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) {
      const exited = once(app, 'exit')
      app.kill('SIGTERM')
      await exited
    }
  }
}
