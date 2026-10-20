import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { once } from 'node:events'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const cwd = fileURLToPath(new URL('../', import.meta.url))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = 'http://127.0.0.1:' + port
const app = spawn(process.execPath, ['.output/server/index.mjs'], { cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' }, stdio: 'ignore' })
const outDir = process.env.OUT_DIR || '/tmp/rsdp-single-space'
let browser, debugPage
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先 pnpm build；预览服务未就绪')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  await mkdir(outDir, { recursive: true })
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  const page = await context.newPage(), errors = []
  debugPage = page
  page.on('pageerror', error => errors.push(error.message))
  const source = {
    analysisId: 'SINGLE-SPACE', analysisRevision: 1, status: 'awaiting_confirm', sourceName: '视图隔离测试.dxf',
    inputMode: 'cad', geometrySource: 'cad_geometry', evidenceKind: 'REAL_SERVICE',
    unitResolution: { canonicalUnit: 'mm', confirmed: true },
    previewBounds: { minX: -20000, minY: -10000, maxX: 35000, maxY: 10000 },
    previewUrl: 'data:image/svg+xml;base64,' + Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="600"><text x="10" y="30">整层底图，不应进入单空间</text></svg>').toString('base64'),
    rooms: [
      { roomId: 'ROOM-L', label: '独立 L 形办公室', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[-18000,2000],[-12000,2000],[-12000,6000],[-16000,6000],[-16000,8000],[-18000,8000]] },
      { roomId: 'ROOM-B', label: '其他会议室', roomType: 'MEETING_ROOM', geometrySource: 'cad_geometry', polygon: [[28000,-6000],[34000,-6000],[34000,-2000],[28000,-2000]] },
    ],
  }
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    const data = path.endsWith('/floor-plan/SINGLE-SPACE') ? source : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  const canvas = () => page.getByTestId('office-design-canvas')
  async function enter(id) {
    await page.goto(baseUrl + '/canvas?analysisId=SINGLE-SPACE&mode=design&zoneId=' + id, { waitUntil: 'networkidle' })
    await page.getByRole('heading', { name: '无限画布 · 单空间设计', exact: true }).waitFor()
    await canvas().locator('polygon[data-id="' + id + '"]').waitFor()
    await assertFit(id)
  }
  async function assertFit(id) {
    await page.waitForFunction(id => {
      const viewport = document.querySelector('[data-testid="office-design-canvas"]')?.getBoundingClientRect()
      const room = document.querySelector('polygon[data-id="' + id + '"]')?.getBoundingClientRect()
      return viewport && room && room.width > 100 && room.height > 100
        && Math.abs(room.x + room.width / 2 - viewport.x - viewport.width / 2) < 2
        && Math.abs(room.y + room.height / 2 - viewport.y - viewport.height / 2) < 2
        && room.x >= viewport.x + 10 && room.right <= viewport.right - 10
        && room.y >= viewport.y + 10 && room.bottom <= viewport.bottom - 10
    }, id)
  }
  await page.goto(baseUrl + '/canvas?analysisId=SINGLE-SPACE', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '选择空间 独立 L 形办公室', exact: true }).waitFor()
  // Overview selection must determine the destination, even after another room was designed.
  for (const [id, name] of [['ROOM-B', '其他会议室'], ['ROOM-L', '独立 L 形办公室'], ['ROOM-B', '其他会议室']]) {
    await page.getByRole('button', { name: '选择空间 ' + name, exact: true }).click()
    await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()
    await page.getByRole('heading', { name: '无限画布 · 单空间设计', exact: true }).waitFor()
    assert.equal(new URL(page.url()).searchParams.get('zoneId'), id, '进入排版必须使用核对页当前选中的空间')
    await canvas().locator('polygon[data-id="' + id + '"]').waitFor()
    assert.equal((await state()).designZoneId, id)
    await page.reload({ waitUntil: 'networkidle' })
    await canvas().locator('polygon[data-id="' + id + '"]').waitFor()
    assert.equal(new URL(page.url()).searchParams.get('zoneId'), id, '刷新后保留选择的排版空间')
    await page.goto(baseUrl + '/canvas?analysisId=SINGLE-SPACE', { waitUntil: 'networkidle' })
    await page.getByRole('button', { name: '选择空间 ' + name, exact: true }).waitFor()
  }
  assert.deepEqual(errors, [])
  console.log('选择空间进入排版 UI mock 通过：会议室→办公区→会议室；URL、当前设计 ID、画布空间与刷新保持一致。')
} catch (failure) {
  await debugPage?.screenshot({ path: outDir + '/failure.png' }).catch(() => {})
  throw failure
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
