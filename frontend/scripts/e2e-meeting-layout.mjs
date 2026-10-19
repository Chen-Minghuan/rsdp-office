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
    rooms: [{ roomId: 'MEETING-ROOM', label: '测试会议室', roomType: 'MEETING_ROOM', geometrySource: 'cad_geometry', polygon: [[0, 0], [12000, 0], [12000, 8000], [0, 8000]] }],
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
  await catalog.waitFor()
  assert.equal(await catalog.locator('[data-module-id="table-conference-rect"]').getAttribute('aria-pressed'), 'true')
  assert.equal(await page.getByRole('button', { name: '会议桌', exact: true }).getAttribute('aria-pressed'), 'true')
  assert.equal(await page.getByTestId('module-capacity').innerText(), '1')
  assert.equal(await page.getByRole('button', { name: '应用单桌居中', exact: true }).count(), 1)
  assert.equal(await page.getByRole('button', { name: '一键自动排版', exact: true }).count(), 0)
  await page.getByRole('button', { name: '应用单桌居中', exact: true }).click()
  const furniture = (await state()).objects.filter(object => object.kind === 'furniture' && object.zoneId === 'MEETING-ROOM')
  assert.equal(furniture.length, 1)
  assert.equal(furniture[0].furnitureTemplateId, 'table-conference-rect')
  const room = (await state()).objects.find(object => object.kind === 'zone' && object.id === 'MEETING-ROOM')
  assert.equal(furniture[0].x, room.x + (room.w - furniture[0].w) / 2)
  assert.equal(furniture[0].y, room.y + (room.h - furniture[0].h) / 2)
  assert.equal(furniture[0].layoutSettings.layoutContext, 'meeting')
  console.log('会议空间排版 UI 通过：默认会议桌、单桌居中、1200mm 外围预留及 meeting 上下文保存。')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
