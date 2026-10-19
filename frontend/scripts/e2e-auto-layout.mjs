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
    analysisId: 'STRICT-TEST', analysisRevision: 1, status: 'awaiting_confirm', sourceName: '严格排布测试.dxf',
    inputMode: 'cad', geometrySource: 'cad_geometry', evidenceKind: 'REAL_SERVICE',
    unitResolution: { canonicalUnit: 'mm', confirmed: true },
    rooms: [{ roomId: 'STRICT-ROOM', label: '开放办公区', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[0,0],[16000,0],[16000,10000],[0,10000]] }],
  }
  let floorPlanRequests = 0
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    const data = path.endsWith('/floor-plan/STRICT-TEST') ? (floorPlanRequests++ === 0 ? source : null) : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  await page.goto(`${baseUrl}/canvas?analysisId=STRICT-TEST`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '选择空间 开放办公区', exact: true }).waitFor()
  // The mock source is intentionally converted to a calibrated design copy so
  // this UI test exercises the layout stage rather than the review gate.
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    for (const zone of draft.objects.filter(object => object.kind === 'zone')) zone.reviewUnit = 'mm'
    localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
  })
  await page.goto(`${baseUrl}/canvas?analysisId=STRICT-TEST`, { waitUntil: 'networkidle' })
  const enter = page.getByRole('button', { name: '进入家具排版 →', exact: true })
  await enter.click()
  await page.getByTestId('unit-module-catalog').waitFor()

  assert.equal(await page.getByRole('button', { name: '自动优化', exact: true }).count(), 0)
  assert.equal(await page.getByRole('button', { name: '矩阵', exact: true }).count(), 0)
  assert.equal(await page.getByRole('button', { name: '一字单排', exact: true }).count(), 1)
  assert.equal(await page.getByRole('button', { name: '一字对坐', exact: true }).count(), 1)
  assert.equal(await page.getByLabel('对排中缝（毫米）').inputValue(), '1000')
  const initialCapacity = Number(await page.getByTestId('module-capacity').innerText())
  assert.ok(initialCapacity > 0)
  await page.getByTestId('apply-layout').click()
  let furniture = (await state()).objects.filter(object => object.kind === 'furniture' && object.zoneId === 'STRICT-ROOM')
  assert.equal(furniture.length, initialCapacity)
  assert.ok(furniture.every(item => item.layoutSettings.arrangementPresetId === 'straight-desk-row' && item.layoutSettings.arrangementRuleVersion === '2026-10-style-v3'))

  await page.getByRole('button', { name: '一字对坐', exact: true }).click()
  assert.equal(await page.getByLabel('对排中缝（毫米）').inputValue(), '1600')
  await page.getByTestId('apply-layout').click()
  furniture = (await state()).objects.filter(object => object.kind === 'furniture' && object.zoneId === 'STRICT-ROOM')
  assert.ok(furniture.some(item => item.rotation === 180))

  await page.getByRole('button', { name: 'L 型屏风卡位', exact: true }).click()
  for (const label of ['F 型单排', 'T 型对坐', 'C 型对坐', '十字型对坐']) assert.equal(await page.getByRole('button', { name: label, exact: true }).count(), 1)
  assert.equal(await page.getByRole('button', { name: '背靠背', exact: true }).count(), 0)
  assert.equal(await page.getByRole('button', { name: '中间通道', exact: true }).count(), 0)
  await page.getByRole('button', { name: '十字型对坐', exact: true }).click()
  assert.equal(await page.getByLabel('排间净距（毫米）').inputValue(), '900')
  assert.equal(await page.getByLabel('对排中缝（毫米）').count(), 0)
  assert.ok(Number(await page.getByTestId('module-capacity').innerText()) > 0)
  assert.deepEqual(errors, [])
  console.log('严格产品样式 UI 通过：样式兼容排布过滤、1000/1600/900mm 默认间距、四种 L 型屏风拓扑和布局快照版本均通过。')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
