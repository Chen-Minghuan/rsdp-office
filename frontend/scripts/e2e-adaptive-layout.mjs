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
    rooms: [{ roomId: 'MEETING-ROOM', label: '测试会议室', roomType: 'MEETING_ROOM', geometrySource: 'cad_geometry', polygon: [[0, 0], [12000, 0], [12000, 8000], [0, 8000]] }, { roomId: 'OPEN-ROOM', label: '测试办公区', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[20000, 0], [26093, 0], [26093, 17810], [20000, 17810]] }],
  }
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
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
  const errors=[]
  page.on('pageerror',error=>errors.push(error.message))
  await page.locator('[data-space-id="OPEN-ROOM"]').click()
  await page.waitForURL(url=>url.searchParams.get('zoneId')==='OPEN-ROOM')
  await catalog.locator('[data-module-id="desk-l"]').click()
  await page.getByRole('tab',{name:'选择排布',exact:true}).click()
  await page.getByRole('button',{name:'L 型单排',exact:true}).click()
  assert.equal(Number((await page.getByTestId('module-capacity').innerText()).replace(/[^0-9]/g,'')),36)
  await page.getByTestId('apply-layout').click()
  await page.getByTestId('layout-unchanged').waitFor()
  const original=(await state()).objects
  assert.equal(original.filter(item=>item.layoutGroupId==='auto:OPEN-ROOM').length,36)
  await page.getByTestId('office-design-canvas').screenshot({path:'C:/Users/cmh/AppData/Local/Temp/rsdp-adaptive-room.png'})
  await page.reload({waitUntil:'networkidle'})
  await page.getByTestId('layout-unchanged').waitFor()
  assert.equal((await state()).objects.filter(item=>item.layoutGroupId==='auto:OPEN-ROOM').length,36)
  await page.getByRole('tab',{name:'调整参数',exact:true}).click()
  await page.getByRole('spinbutton',{name:'模块宽度（毫米）',exact:true}).fill('1600')
  assert.equal(Number((await page.getByTestId('module-capacity').innerText()).replace(/[^0-9]/g,'')),30)
  assert.deepEqual((await state()).objects,original,'parameter preview does not overwrite the 36-place layout')
  assert.equal(await page.getByTestId('office-design-canvas').locator('.board-element[data-id]').filter({ has: page.locator('[data-furniture-template]') }).count(), 0, '预览新方案时隐藏将被替换的自动组，不重叠')
  await page.getByTestId('apply-layout').click()
  assert.equal((await state()).objects.filter(item=>item.layoutGroupId==='auto:OPEN-ROOM').length,30)
  assert.equal(await page.getByTestId('office-design-canvas').locator('.board-element[data-id]').filter({ has: page.locator('[data-furniture-template]') }).count(), 30, '应用后实际家具正常显示')
  await page.getByRole('button',{name:'撤销',exact:true}).click()
  assert.deepEqual((await state()).objects,original)
  assert.deepEqual(errors,[])
  console.log('PASS: screenshot-sized room 36 desks, direction comparison, reload, width change to 30, preview isolation, replaced-group hiding and one-step undo')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
