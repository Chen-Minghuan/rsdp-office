import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { once } from 'node:events'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const cwd = fileURLToPath(new URL('../', import.meta.url))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = 'http://127.0.0.1:' + port
const app = spawn(process.execPath, ['.output/server/index.mjs'], { cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' }, stdio: 'ignore' })
let browser
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先 pnpm build；预览服务未就绪')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1600, height: 1100 } })
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  const page = await context.newPage(), errors = []
  page.on('pageerror', error => errors.push(error.message))
  let sourceRevision = 1
  let mode = 'READY', reportNumber = 1, confirms = [], validations = [], pendingRead = null, pendingConfirm = null
  let holdRead = false, holdConfirm = false
  const report = (unit = 'mm') => {
    const factor = unit === 'cm' ? 100 : unit === 'm' ? 10000 : 10
    return {
      reportId: 'REPORT-' + reportNumber, policyVersion: 'DIM-SCALE-1', analysisId: 'AUTO-TEST', analysisRevision: 1,
      imageSha256: 'MOCK-HASH', imageWidth: 1000, imageHeight: 600, assumedUnit: unit,
      status: mode, mmPerPixel: mode === 'READY' ? factor : null, maxDeviationPercent: mode === 'READY' ? 0 : null,
      acceptedCount: mode === 'READY' ? 2 : 0, validCount: mode === 'INSUFFICIENT' ? 1 : 2, unitAssumed: true,
      warnings: mode === 'CONFLICT' ? ['横向与纵向比例不一致，请人工校准'] : mode === 'INSUFFICIENT' ? ['仅有一条独立参考，请手工校准'] : ['尺寸未注明单位，按 ' + unit + ' 形成候选，请核对'],
      references: [
        { referenceId: 'DIM-1', text: '6000', unit, unitAssumed: true, confidence: 'high', start: [100, 70], end: [700, 70], lengthMm: 600 * factor, pixelLength: 600, mmPerPixel: factor, axis: 'HORIZONTAL', status: mode === 'READY' ? 'ACCEPTED' : 'VALID', reason: null },
        { referenceId: 'DIM-2', text: '4000', unit, unitAssumed: true, confidence: 'high', start: [70, 100], end: [70, 500], lengthMm: 400 * factor, pixelLength: 400, mmPerPixel: factor, axis: 'VERTICAL', status: mode === 'READY' ? 'ACCEPTED' : 'VALID', reason: null },
      ].slice(0, mode === 'INSUFFICIENT' ? 1 : 2),
      confirmationId: null, confirmedAt: null,
    }
  }
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="600"><rect width="1000" height="600" fill="white"/><rect x="100" y="100" width="600" height="400" fill="none" stroke="#555" stroke-width="3"/><path d="M100 70H700M70 100V500" fill="none" stroke="#888"/><text x="380" y="60" font-size="20">6000</text><text x="10" y="300" font-size="20">4000</text></svg>'
  const plan = () => ({
    analysisId: 'AUTO-TEST', analysisRevision: sourceRevision, status: 'awaiting_confirm', sourceName: '自动尺寸测试.png', inputMode: 'image',
    previewUrl: 'data:image/svg+xml;base64,' + Buffer.from(svg).toString('base64'), previewBounds: { minX: 0, minY: 0, maxX: 1000, maxY: 600 },
    unitResolution: { canonicalUnit: 'px', confirmed: false }, dimensionCalibration: report(),
    rooms: [{ roomId: 'AUTO-ROOM', label: '测试会议室', roomType: 'MEETING_ROOM', geometrySource: 'ai_vision', polygon: [[100, 100], [700, 100], [700, 500], [100, 500]] }],
  })
  await context.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    const respond = (data, code = 200, message = '成功') => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code, message, data }) })
    if (path.endsWith('/dimension-calibration/recognize')) {
      if (mode === 'ERROR') return respond(null, 500, '尺寸读取失败，请重试或手工校准')
      reportNumber++
      const value = report()
      if (holdRead) { pendingRead = () => respond(value); return }
      return respond(value)
    }
    if (path.endsWith('/dimension-calibration/confirm')) {
      const body = request.postDataJSON(); confirms.push(body)
      assert.deepEqual(Object.keys(body).sort(), ['analysisRevision', 'assumedUnit', 'reportId'])
      const value = { ...report(body.assumedUnit), confirmationId: 'CONF-' + reportNumber, confirmedAt: '2026-10-14T10:00:00Z' }
      if (holdConfirm) { pendingConfirm = () => respond(value); return }
      return respond(value)
    }
    if (path.endsWith('/dimension-calibration')) {
      validations.push(url.searchParams.get('assumedUnit'))
      return respond(report(url.searchParams.get('assumedUnit')))
    }
    if (path === '/api/v1/floor-plan/AUTO-TEST') return respond(plan())
    return respond(path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : [])
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  const room = async () => (await state()).objects.find(object => object.id === 'AUTO-ROOM')
  const confirm = () => page.getByRole('button', { name: '确认比例并应用到全部空间', exact: true })
  const readAgain = () => page.getByRole('button', { name: '重新读取', exact: true })
  const panel = () => page.getByLabel('自动读取尺寸与比例')
  async function ready() { await page.getByText('比例一致 · 待确认', { exact: true }).waitFor() }

  await page.goto(baseUrl + '/canvas?analysisId=AUTO-TEST', { waitUntil: 'networkidle' })
  await ready()
  assert.equal((await room()).reviewUnit, 'source')
  assert.ok(await page.getByLabel('空间宽度（毫米）').isDisabled())
  assert.equal((await room()).polygon[1][0], 700, '读取候选不能悄悄应用比例')
  assert.equal(await page.locator('[data-layer="dimension-reference"]').count(), 1)
  await page.getByRole('button', { name: '对照尺寸 4000', exact: true }).click()
  assert.equal(await page.locator('[data-layer="dimension-reference"] text').textContent(), '4000')
  await page.getByLabel('尺寸标注默认单位').selectOption('cm')
  await page.waitForFunction(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).floorPlan.dimensionCalibration.assumedUnit === 'cm')
  assert.equal((await room()).reviewUnit, 'source')
  await page.getByLabel('尺寸标注默认单位').selectOption('mm')
  await page.waitForFunction(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).floorPlan.dimensionCalibration.assumedUnit === 'mm')
  assert.deepEqual(validations, ['cm', 'mm'])
  await panel().scrollIntoViewIfNeeded()
  await confirm().click()
  await page.getByRole('button', { name: '✓ 自动比例已应用', exact: true }).waitFor()
  assert.equal((await room()).reviewUnit, 'mm')
  assert.equal((await room()).mmPerSourceUnit, 10)
  assert.equal((await room()).confirmed, false, '应用比例不能伪造人工核对记录')
  assert.equal(await page.getByTestId('selected-area').innerText(), '24.00 m²')
  assert.ok(await page.getByRole('button', { name: '进入家具排版 →', exact: true }).isEnabled())
  const sourcePolygon = (await room()).sourcePolygon
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.equal((await room()).reviewUnit, 'source')
  await confirm().click()
  await page.getByRole('button', { name: '✓ 自动比例已应用', exact: true }).waitFor()
  assert.equal(await page.getByTestId('selected-area').innerText(), '24.00 m²', '再次确认按来源绝对比例应用，不能重复乘十')
  await page.getByLabel('空间宽度（毫米）').fill('5000')
  await page.getByRole('button', { name: '应用尺寸', exact: true }).click()
  const edited = (await room()).polygon
  await readAgain().click()
  await ready()
  assert.deepEqual((await room()).polygon, edited)
  await confirm().click()
  await page.getByRole('button', { name: '✓ 自动比例已应用', exact: true }).waitFor()
  assert.equal(await page.getByTestId('selected-area').innerText(), '20.00 m²', '重新读尺寸保留已编辑设计副本')
  assert.deepEqual((await room()).sourcePolygon, sourcePolygon)
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '✓ 自动比例已应用', exact: true }).waitFor()
  assert.equal(await page.getByTestId('selected-area').innerText(), '20.00 m²')
  await panel().scrollIntoViewIfNeeded()

  mode = 'CONFLICT'
  await readAgain().click()
  await page.getByText('比例存在冲突', { exact: true }).waitFor()
  assert.equal(await confirm().count(), 0)
  assert.equal((await room()).mmPerSourceUnit, 10)
  assert.deepEqual((await room()).polygon, edited)
  await panel().scrollIntoViewIfNeeded()
  mode = 'INSUFFICIENT'
  await readAgain().click()
  await page.getByText('参考数量不足', { exact: true }).waitFor()
  assert.equal(await confirm().count(), 0)
  mode = 'ERROR'
  await readAgain().click()
  await page.getByRole('alert').filter({ hasText: '尺寸读取失败' }).waitFor()
  assert.ok(await page.getByRole('button', { name: /两点校准图纸比例/ }).isEnabled())

  // Keep the response pending, then edit geometry. The async confirmation must not apply.
  mode = 'READY'
  await readAgain().click()
  await ready()
  holdConfirm = true
  await confirm().click()
  await page.waitForFunction(() => document.querySelector('.dimension-confirm-button')?.textContent.includes('确认中'))
  await page.getByRole('button', { name: '编辑轮廓', exact: true }).click()
  const vertex = page.locator('[data-vertex-id="AUTO-ROOM-V-2"]')
  const box = await vertex.boundingBox()
  await page.mouse.move(box.x + 4, box.y + 4); await page.mouse.down(); await page.mouse.move(box.x - 18, box.y + 4, { steps: 4 }); await page.mouse.up()
  const changedDuringConfirm = (await room()).polygon
  assert.notDeepEqual(changedDuringConfirm, edited)
  while (!pendingConfirm) await new Promise(resolve => setTimeout(resolve, 10))
  await pendingConfirm()
  await page.getByRole('alert').filter({ hasText: '草稿已变化' }).waitFor()
  assert.deepEqual((await room()).polygon, changedDuringConfirm)
  holdConfirm = false

  // Saved canvas can outlive its local source metadata. Reattach the same revision without reimporting edits.
  const recovery = await state()
  recovery.floorPlan = null
  await page.evaluate(value => localStorage.setItem('rsdp-office-v01', JSON.stringify(value)), recovery)
  await page.reload({ waitUntil: 'networkidle' })
  await ready()
  assert.deepEqual((await room()).polygon, changedDuringConfirm)
  assert.equal((await state()).floorPlan.analysisId, 'AUTO-TEST')
  assert.equal(await page.locator('[data-layer="underlay"]').count(), 1)

  // A late read result must not be attached to another source or the example scene.
  holdRead = true
  await readAgain().click()
  while (!pendingRead) await new Promise(resolve => setTimeout(resolve, 10))
  await page.getByRole('button', { name: '加载工装示例（可撤销）', exact: true }).click()
  await pendingRead()
  await page.getByRole('button', { name: '选择空间 总经办一', exact: true }).waitFor()
  assert.equal((await state()).floorPlan.analysisId, 'OFFICE-REVIEW-EXAMPLE')
  assert.equal((await state()).floorPlan.dimensionCalibration, undefined)
  // A different analysis revision must not be silently attached to the preserved design copy.
  sourceRevision = 2
  await page.evaluate(value => localStorage.setItem('rsdp-office-v01', JSON.stringify(value)), recovery)
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('alert').filter({ hasText: '来源识别版本已变化' }).waitFor()
  assert.deepEqual((await room()).polygon, changedDuringConfirm)
  assert.equal((await state()).floorPlan, null)

  assert.deepEqual(errors, [])
  console.log('自动尺寸校准 UI 通过：候选不自动应用、参考线对照、单位重验、显式确认/撤销、全图尺寸和面积、编辑保留/刷新恢复、冲突/单条/失败回退、过期读取与确认保护、同版本来源恢复及异版本拒绝。全部接口模拟。')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
