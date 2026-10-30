import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

// 组合预览未应用即可拖动：拖动只改预览显示与预览模型，应用时按调整后的位置生成家具。
const cwd = fileURLToPath(new URL('../', import.meta.url))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = `http://127.0.0.1:${port}`
const app = spawn(process.execPath, ['.output/server/index.mjs'], { cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' }, stdio: 'ignore' })
let browser
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先 pnpm build；预览服务未就绪')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1700, height: 1100 } })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const source = {
    analysisId: 'PREVIEW-DRAG', analysisRevision: 1, status: 'awaiting_confirm', sourceName: '预览拖动测试.dxf',
    inputMode: 'cad', geometrySource: 'cad_geometry', evidenceKind: 'REAL_SERVICE',
    unitResolution: { canonicalUnit: 'mm', confirmed: true },
    rooms: [{ roomId: 'DRAG-ROOM', label: '开放办公区', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[0, 0], [16000, 0], [16000, 10000], [0, 10000]] }],
  }
  let floorPlanRequests = 0
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    const data = path.endsWith('/floor-plan/PREVIEW-DRAG') ? (floorPlanRequests++ === 0 ? source : null) : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  await page.goto(`${baseUrl}/canvas?analysisId=PREVIEW-DRAG`, { waitUntil: 'networkidle' })
  await page.locator('.review-rooms').getByRole('button', { name: '选择空间 开放办公区', exact: true }).waitFor()
  // 与 e2e-auto-layout 相同：把识别副本转成已校准设计副本，直接进入排版阶段。
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    for (const zone of draft.objects.filter(object => object.kind === 'zone')) zone.reviewUnit = 'mm'
    localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
  })
  await page.goto(`${baseUrl}/canvas?analysisId=PREVIEW-DRAG`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()

  // 默认不选品类；选两个品类切换到组合面板。
  await page.getByTestId('functional-category-workstation').click()
  await page.getByTestId('functional-category-sofa').click()
  await page.getByTestId('mixed-layout-generate').click()
  const previewItems = page.locator('[data-preview-id]')
  await previewItems.first().waitFor()
  const count = await previewItems.count()
  assert.ok(count >= 2, `组合预览至少 2 件家具，实际 ${count}`)

  // 关闭吸附，保证拖动位移可精确断言。
  await page.getByRole('button', { name: '家具吸附', exact: true }).click()
  const zoom = await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').zoom)
  const item = previewItems.first()
  const before = await item.evaluate(el => ({ left: parseFloat(el.style.left), top: parseFloat(el.style.top) }))
  const box = await item.boundingBox()
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 + 60 * zoom, box.y + box.height / 2 + 30 * zoom, { steps: 6 })
  await page.mouse.up()
  const after = await item.evaluate(el => ({ left: parseFloat(el.style.left), top: parseFloat(el.style.top) }))
  assert.ok(Math.abs(after.left - before.left - 60) <= 2, `预览横向拖动 60mm，实际 ${after.left - before.left}`)
  assert.ok(Math.abs(after.top - before.top - 30) <= 2, `预览纵向拖动 30mm，实际 ${after.top - before.top}`)

  // 未应用前不写入已排版家具。
  assert.equal((await state()).objects.filter(object => object.kind === 'furniture').length, 0, '拖动预览不写入已排版家具')

  // 应用：已排版家具按拖动后的预览位置生成。
  await page.getByTestId('mixed-layout-apply').click()
  await page.waitForFunction(() => (JSON.parse(localStorage.getItem('rsdp-office-v01')).objects || []).some(object => object.kind === 'furniture'))
  const placed = (await state()).objects.filter(object => object.kind === 'furniture')
  assert.equal(placed.length, count, '应用后家具件数与预览一致')
  assert.ok(placed.some(object => Math.abs(object.x - after.left) <= 2 && Math.abs(object.y - after.top) <= 2), `应用位置与拖动后的预览位置一致（期望 x≈${after.left} y≈${after.top}）`)
  assert.equal(await previewItems.count(), 0, '应用后预览清空')

  assert.deepEqual(errors, [], '页面无脚本错误')
  console.log('预览拖动 E2E 通过：组合预览拖动调整、未应用不落盘、应用按调整后位置生成。')
} finally {
  if (browser) await browser.close()
  app.kill()
}
