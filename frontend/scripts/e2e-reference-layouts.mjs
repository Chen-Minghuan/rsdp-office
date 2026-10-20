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
const app = spawn(process.execPath, ['.output/server/index.mjs'], {
  cwd,
  env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' },
  stdio: 'ignore',
})

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
  const source = {
    analysisId: 'MEETING-TEST', analysisRevision: 1, status: 'awaiting_confirm', sourceName: '会议室测试.dxf',
    inputMode: 'cad', geometrySource: 'cad_geometry', evidenceKind: 'REAL_SERVICE',
    unitResolution: { canonicalUnit: 'mm', confirmed: true },
    rooms: [{ roomId: 'MEETING-ROOM', label: '测试会议室', roomType: 'MEETING_ROOM', geometrySource: 'cad_geometry', polygon: [[0, 0], [12000, 0], [12000, 8000], [0, 8000]] }, { roomId: 'OPEN-ROOM', label: '测试办公区', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[20000, 0], [32000, 0], [32000, 8000], [20000, 8000]] }],
  }
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    const data = path.endsWith('/floor-plan/MEETING-TEST') ? source : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  const catalog = page.getByTestId('unit-module-catalog')
  await page.goto(`${baseUrl}/canvas?analysisId=MEETING-TEST`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '选择空间 测试会议室', exact: true }).waitFor()
  await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.getByRole('combobox', { name: '选择排版空间', exact: true }).selectOption('OPEN-ROOM')
  await page.waitForURL(url => url.searchParams.get('zoneId') === 'OPEN-ROOM')
  const cases = [
    ['一字职员台', '一字单排', 6, 'straight-desk-row'],
    ['一字职员台', '一字对坐', 12, 'straight-desk-face-to-face'],
    ['一字屏风卡位', '一字屏风卡位·单排', 6, 'straight-screen-row'],
    ['一字屏风卡位', '一字屏风卡位·对坐', 12, 'straight-screen-face-to-face'],
    ['L 型职员台', 'L 型单排', 6, 'l-desk-row'],
    ['L 型职员台', 'L 型对坐', 12, 'l-desk-face-to-face'],
    ['L 型屏风卡位', 'F 型单排', 8, 'screen-f-row'],
    ['L 型屏风卡位', 'T 型对坐', 12, 'screen-t-face-to-face'],
    ['L 型屏风卡位', 'C 型对坐', 8, 'screen-c-face-to-face'],
    ['L 型屏风卡位', '十字型对坐', 16, 'screen-cross-face-to-face'],
  ]
  for (const [style, label, sampleCount, preset] of cases) {
    const w=style.startsWith('一字')?1200:1400, d=style.startsWith('一字')?600:1400
    const gap=style==='L 型职员台'?600:style==='L 型屏风卡位'?900:preset.endsWith('row')?1000:1600
    const cw=preset.startsWith('screen-')&&preset!=='screen-f-row'?w*2:w
    const ch=preset==='screen-c-face-to-face'||preset==='screen-cross-face-to-face'||(preset.endsWith('face-to-face')&&!preset.startsWith('screen-t'))?d*2:d
    const gx=preset==='screen-t-face-to-face'||preset==='screen-c-face-to-face'?gap:0
    const gy=preset==='screen-t-face-to-face'?0:gap
    const members=cw/w*ch/d
    const fit=(a,b)=>Math.floor((a+(b==='x'?gx:gy))/(b==='x'?cw+gx:ch+gy))
    const count=Math.max(fit(11400,'x')*fit(7400,'y'),fit(7400,'x')*fit(11400,'y'))*members
    await page.getByRole('tab', { name: '选择模块', exact: true }).click()
    const moduleId = {'一字职员台':'desk-straight', 'L 型职员台':'desk-l', '一字屏风卡位':'desk-screen-straight', 'L 型屏风卡位':'desk-screen-l'}[style]
    await page.locator('[data-module-id="' + moduleId + '"]').click()
    await page.getByRole('tab', { name: '选择排布', exact: true }).click()
    await page.getByRole('button', { name: label, exact: true }).click()
    assert.equal(Number((await page.getByTestId('module-capacity').innerText()).replace(/[^0-9]/g,'')), count)
    const pending = await page.locator('[data-preview-id]').evaluateAll(nodes => nodes.map(node => [node.style.left, node.style.top, node.style.width, node.style.height, node.style.transform]))
    await page.getByTestId('apply-layout').click()
    await page.getByTestId('layout-unchanged').waitFor()
    const placed = (await state()).objects.filter(item => item.layoutGroupId === 'auto:OPEN-ROOM')
    assert.equal(placed.length, count, label)
    const applied = await page.getByTestId('office-design-canvas').locator('.board-element[data-id]').filter({ has: page.locator('[data-furniture-template]') }).evaluateAll(nodes => nodes.map(node => [node.style.left, node.style.top, node.style.width, node.style.height, node.style.transform]))
    assert.deepEqual(applied, pending, 'each preset uses identical preview/application transforms')
    assert.ok(placed.every(item => item.layoutSettings.arrangementPresetId === preset && item.layoutSettings.arrangementRuleVersion === '2026-10-module-selection-v6'))
    if (preset.startsWith('screen-')) assert.equal(await page.getByTestId('office-design-canvas').locator('[data-furniture-template="desk-screen-l"]').count(), count)
    const seatGlyphs = await page.getByTestId('office-design-canvas').locator('.layout-seat').count()
    assert.equal(seatGlyphs, preset.includes('straight-') || preset === 'l-desk-face-to-face' ? count : 0, 'reference seat illustrations')
    await page.getByTestId('office-design-canvas').screenshot({ path: 'C:/Users/cmh/AppData/Local/Temp/rsdp-reference-' + preset + '.png' })
  }
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('layout-unchanged').waitFor()
  assert.equal((await state()).objects.filter(item => item.layoutGroupId === 'auto:OPEN-ROOM').length, 32)
  await page.getByRole('spinbutton', { name: '目标人数', exact: true }).fill('3')
  assert.equal(Number((await page.getByTestId('module-capacity').innerText()).replace(/[^0-9]/g,'')), 4, 'target preserves a complete cross cell')
  assert.deepEqual(errors, [])
  console.log('PASS: all 10 reference presets through actual UI; room-sized counts, preset/version persistence, rounded screen glyphs, seat orientation, preview/application equality and reload')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
