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
  await page.getByRole('tab', { name: '选择模块', exact: true }).click()
  await catalog.waitFor()
  assert.equal(await catalog.locator('[data-module-id="table-conference-rect"]').getAttribute('aria-pressed'), 'true')
  const snapshot = await state()
  await catalog.locator('[data-module-id="table-conference-rect"]').click() // preview appears after an explicit module choice
  assert.ok(await page.locator('[data-preview-id]').count() > 1)
  assert.deepEqual((await state()).objects, snapshot.objects, 'preview never writes objects')
  await page.getByRole('spinbutton', { name: '目标人数', exact: true }).fill('8')
  await page.getByTestId('layout-seat-summary').getByText('已排座位 8', { exact: false }).waitFor()
  const preview = await page.locator('[data-preview-id]').evaluateAll(nodes => nodes.map(n => ({ x: n.style.left, y: n.style.top, w: n.style.width, h: n.style.height, transform: n.style.transform })))
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.$onAction(({ name, args }) => { if (name === 'applyLayoutProposal') window.appliedArguments = JSON.parse(JSON.stringify(args)) })
  })
  await page.getByTestId('apply-layout').click()
  const placed = (await state()).objects.filter(item => item.kind === 'furniture')
  assert.equal(placed.length, 9)
  assert.equal(placed.filter(item => item.layoutRole === 'seat').length, 8)
  assert.equal(await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const args = window.appliedArguments
    return store.applyLayoutProposal(...args)
  }), false, 'stale proposal cannot overwrite current saved placements')
  const actual = await page.locator('[data-testid="office-design-canvas"] .board-element[data-id]').filter({ has: page.locator('[data-furniture-template]') }).evaluateAll(nodes => nodes.map(n => ({ x: n.style.left, y: n.style.top, w: n.style.width, h: n.style.height, transform: n.style.transform })))
  assert.deepEqual(actual, preview, 'preview and applied placement use identical geometry')
  await page.getByTestId('layout-unchanged').waitFor()
  assert.ok(await page.getByTestId('apply-layout').isDisabled())
  await page.getByRole('button', { name: '确认当前排版', exact: false }).click()
  assert.ok((await state()).objects.filter(item => item.kind === 'furniture').every(item => item.layoutConfirmed))
  await page.getByRole('spinbutton', { name: '目标人数', exact: true }).fill('60')
  await page.getByTestId('layout-seat-summary').getByText('尚缺', { exact: false }).waitFor()
  assert.ok((await state()).objects.filter(item => item.kind === 'furniture').every(item => item.layoutConfirmed), 'editing draft does not unconfirm saved layout')
  await page.reload({ waitUntil: 'networkidle' })
  assert.equal(await page.getByRole('spinbutton', { name: '目标人数', exact: true }).inputValue(), '8')
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    const zone = draft.objects.find(item => item.id === 'MEETING-ROOM')
    draft.objects.push({ id: 'KEEP-CABINET', kind: 'furniture', zoneId: zone.id, productId: '', furnitureTemplateId: 'cabinet-mobile', x: zone.x + 300, y: zone.y + 300, w: 420, h: 500, rotation: 0, layoutConfirmed: false })
    draft.objects.find(item => item.layoutRole === 'primary').productId = 'SELECTED-OLD'
    draft.cart = [{ id: 'CART-OLD', productId: 'SELECTED-OLD', zoneId: zone.id, zoneName: zone.name, qty: 1 }, { id: 'CART-EXTRA', productId: 'EXTRA-MANUAL', zoneId: zone.id, zoneName: zone.name, qty: 2 }]
    localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
  })
  await page.reload({ waitUntil: 'networkidle' })
  const beforeReplan = (await state()).objects
  await page.getByRole('spinbutton', { name: '目标人数', exact: true }).fill('6')
  await page.getByTestId('apply-layout').click()
  assert.deepEqual((await state()).objects.find(item => item.id === 'KEEP-CABINET'), beforeReplan.find(item => item.id === 'KEEP-CABINET'))
  assert.equal((await state()).objects.filter(item => item.layoutGroupId).length, 7)
  assert.deepEqual((await state()).cart.map(item => item.id), ['CART-EXTRA'], 'remove group selections while retaining unrelated manual selections')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual((await state()).objects, beforeReplan, 'one undo restores group, preferences and confirmation')
  assert.equal((await state()).cart[0].id, 'CART-OLD', 'undo restores associated selections')
  await page.getByRole('tab', { name: '选择模块', exact: true }).click()
  await catalog.locator('summary').filter({ hasText: '职员桌' }).click()
  await catalog.locator('[data-module-id="desk-l"]').click()
  assert.ok(await page.getByTestId('apply-layout').isEnabled(), 'meeting room allows staff desks')
  assert.ok(await page.getByRole('button', { name: '逐件添加', exact: true }).isEnabled())
  await catalog.locator('[data-module-id="table-conference-rect"]').click()
  await page.locator('summary').filter({ hasText: '入口与禁放区' }).click()
  await page.getByRole('button', { name: '添加入口或禁放区', exact: true }).click()
  await page.getByRole('spinbutton', { name: '预留区域 w', exact: true }).fill('12000')
  await page.getByRole('spinbutton', { name: '预留区域 h', exact: true }).fill('8000')
  assert.ok(await page.getByTestId('apply-layout').isDisabled())
  await page.getByRole('button', { name: '移除预留区域', exact: true }).click()
  await page.getByRole('button', { name: '自动推荐', exact: true }).click()
  await page.screenshot({ path: (process.env.OUT_DIR || 'C:/Users/cmh/AppData/Local/Temp') + '/rsdp-layout-planning.png' })
  const savedMeeting = (await state()).objects.filter(item => item.kind === 'furniture' && item.zoneId === 'MEETING-ROOM')
  await page.getByRole('combobox', { name: '选择排版空间', exact: true }).selectOption('OPEN-ROOM')
  await page.waitForURL(url => url.searchParams.get('zoneId') === 'OPEN-ROOM')
  assert.equal(await page.getByRole('spinbutton', { name: '目标人数', exact: true }).inputValue(), '0')
  await page.getByRole('tab', { name: '选择排布', exact: true }).click()
  await page.getByRole('button', { name: '一字对坐', exact: true }).click()
  await page.getByRole('spinbutton', { name: '目标人数', exact: true }).fill('12')
  await page.getByTestId('apply-layout').click()
  assert.equal((await state()).objects.filter(item => item.layoutGroupId === 'auto:OPEN-ROOM').length, 12)
  assert.deepEqual((await state()).objects.filter(item => item.kind === 'furniture' && item.zoneId === 'MEETING-ROOM'), savedMeeting)
  await page.reload({ waitUntil: 'networkidle' })
  assert.equal(await page.getByRole('spinbutton', { name: '目标人数', exact: true }).inputValue(), '12')
  await page.getByTestId('layout-unchanged').waitFor()
  for (const width of [1440, 1100]) {
    await page.setViewportSize({ width, height: 900 })
    const box = await page.getByTestId('apply-layout').boundingBox()
    assert.ok(box.y >= 0 && box.y + box.height <= 900, 'main action visible in 900px viewport')
  }
  assert.deepEqual(errors, [])
  console.log('PASS: meeting module compatibility, real table/chair seats, preview/application equality, no draft persistence, manual retention, undo, refresh, exclusion and shortfall feedback')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
