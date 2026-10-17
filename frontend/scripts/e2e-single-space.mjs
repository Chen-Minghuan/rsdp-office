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
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    for (const [id, zoneId] of [['F-L','ROOM-L'],['F-B','ROOM-B']]) {
      const zone = draft.objects.find(zone => zone.id === zoneId)
      draft.objects.push({ id, zoneId, kind: 'furniture', x: zone.x + 500, y: zone.y + 500, w: 1200, h: 600, rotation: 0, productId: '', label: id })
    }
    draft.objects.push({ id: 'GLOBAL-NOTE', kind: 'note', x: -17500, y: -7500, text: '整层便签' },
      { id: 'GLOBAL-REF', kind: 'reference', x: -17500, y: -7500, caption: '整层参考' },
      { id: 'GLOBAL-ARROW', kind: 'arrow', x: -17500, y: -7500, x2: -17000, y2: -7000 })
    localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
  })
  await enter('ROOM-L')
  const spaceSelect = page.getByRole('combobox', { name: '选择排版空间', exact: true })
  assert.equal(await spaceSelect.inputValue(), 'ROOM-L')
  assert.deepEqual(await spaceSelect.locator('option').allTextContents(), ['独立 L 形办公室 · 待确认 · 1 件', '其他会议室 · 待确认 · 1 件'])
  assert.equal(await page.getByRole('button', { name: '上一个空间', exact: true }).isDisabled(), true)
  const initial = await state()
  assert.equal(await canvas().locator('polygon.board-element').count(), 1)
  assert.equal((await canvas().locator('polygon.board-element').getAttribute('points')).split(' ').length, 6, 'L 形不能变成包围盒')
  assert.equal(await canvas().locator('img').count(), 0, '不绘制整层底图')
  assert.deepEqual(await canvas().locator('.board-element').evaluateAll(nodes => nodes.map(node => node.dataset.id).sort()), ['F-L','ROOM-L'])
  assert.equal(await canvas().locator('line').count(), 0)
  await page.getByText('尺寸（毫米）', { exact: true }).waitFor()
  await page.screenshot({ path: outDir + '/01-single-room.png' })

  const beforeZoom = await canvas().locator('polygon.board-element').boundingBox()
  await page.getByRole('button', { name: '放大画布', exact: true }).click()
  await page.waitForFunction(width => document.querySelector('polygon.board-element').getBoundingClientRect().width > width * 1.1, beforeZoom.width)
  await page.getByRole('button', { name: '缩小画布', exact: true }).click()
  await page.getByRole('button', { name: '适屏', exact: true }).click()
  await assertFit('ROOM-L')
  await page.setViewportSize({ width: 1850, height: 1100 })
  await assertFit('ROOM-L')
  await page.getByRole('button', { name: '收起侧边栏', exact: true }).first().click()
  await page.waitForFunction(() => {
    const expand = document.querySelector('button[title="展开侧边栏"]')
    return expand?.parentElement?.parentElement?.getBoundingClientRect().width < 50
  })
  await assertFit('ROOM-L')
  assert.deepEqual((await state()).objects, initial.objects, '缩放、适屏、调整视口均不改动几何')

  // Pointer coordinates must stay in world mm even with a negative world origin.
  const furniture = canvas().locator('[data-id="F-L"]')
  const from = await furniture.boundingBox()
  const zoom = from.width / 1200
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2)
  await page.mouse.down()
  await page.mouse.move(from.x + from.width / 2 + 25, from.y + from.height / 2 + 15, { steps: 5 })
  await page.mouse.up()
  const moved = (await state()).objects.find(object => object.id === 'F-L')
  const original = initial.objects.find(object => object.id === 'F-L')
  assert.ok(Math.abs(moved.x - original.x - 25 / zoom) < 3, JSON.stringify({ moved, original, zoom, from }))
  assert.ok(Math.abs(moved.y - original.y - 15 / zoom) < 3)
  assert.equal(moved.zoneId, 'ROOM-L')

  // New local annotations remain attached to this room instead of appearing in other designs.
  await page.getByRole('button', { name: '标注', exact: true }).click()
  const roomBox = await canvas().locator('polygon.board-element').boundingBox()
  await page.mouse.click(roomBox.x + roomBox.width * 0.25, roomBox.y + roomBox.height * 0.6)
  const note = (await state()).objects.find(object => object.kind === 'note' && object.zoneId === 'ROOM-L')
  assert.ok(note && note.x < 0 && note.y < 0)
  await page.getByRole('button', { name: '展开侧边栏', exact: true }).first().click()
  await page.getByRole('button', { name: '确认当前排版', exact: true }).click()
  const confirmedFurniture = (await state()).objects.filter(object => object.kind === 'furniture' && object.zoneId === 'ROOM-L')
  assert.ok(confirmedFurniture.every(object => object.layoutConfirmed))
  assert.match(await spaceSelect.locator('option[value="ROOM-L"]').textContent(), /已确认 · 1 件/)

  // Switch through the visible selector without leaving the design page.
  await spaceSelect.selectOption('ROOM-B')
  await page.waitForURL(url => url.searchParams.get('zoneId') === 'ROOM-B')
  await assertFit('ROOM-B')
  assert.equal(await page.getByRole('button', { name: '下一个空间', exact: true }).isDisabled(), true)
  assert.deepEqual(await canvas().locator('.board-element').evaluateAll(nodes => nodes.map(node => node.dataset.id).sort()), ['F-B','ROOM-B'])
  assert.equal(await canvas().locator('img').count(), 0)
  // Applying a layout in the newly selected room never replaces the previous room's furniture.
  await page.getByRole('button', { name: '一键自动排版', exact: true }).click()
  assert.ok((await state()).objects.filter(object => object.kind === 'furniture' && object.zoneId === 'ROOM-B').length > 1)
  assert.deepEqual((await state()).objects.filter(object => object.kind === 'furniture' && object.zoneId === 'ROOM-L'), confirmedFurniture)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual((await state()).objects.find(object => object.id === 'F-B'), initial.objects.find(object => object.id === 'F-B'))

  // An unfinished placement must not carry across rooms; previous/next retain saved layouts.
  await page.getByRole('button', { name: '添加到画布', exact: true }).click()
  await page.getByRole('button', { name: '结束逐件添加', exact: true }).waitFor()
  await page.getByRole('button', { name: '上一个空间', exact: true }).click()
  await page.waitForURL(url => url.searchParams.get('zoneId') === 'ROOM-L')
  await assertFit('ROOM-L')
  await page.getByRole('button', { name: '添加到画布', exact: true }).waitFor()
  assert.equal(await page.getByTestId('furniture-placement-preview').count(), 0)
  await page.getByRole('button').filter({ has: page.getByText('重新调整排版', { exact: true }) }).waitFor()
  assert.deepEqual((await state()).objects.filter(object => object.kind === 'furniture' && object.zoneId === 'ROOM-L'), confirmedFurniture)
  assert.equal(await canvas().locator('[data-id="' + note.id + '"]').count(), 1)
  await page.getByRole('button', { name: '下一个空间', exact: true }).click()
  await page.waitForURL(url => url.searchParams.get('zoneId') === 'ROOM-B')
  await assertFit('ROOM-B')
  await page.getByTestId('design-space-switcher').getByText('已确认排版 1 / 2 个空间', { exact: true }).waitFor()
  await page.screenshot({ path: outDir + '/02-switched-room.png' })
  await page.reload({ waitUntil: 'networkidle' })
  await assertFit('ROOM-B')
  assert.equal(await spaceSelect.inputValue(), 'ROOM-B')
  assert.match(await spaceSelect.locator('option[value="ROOM-L"]').textContent(), /已确认 · 1 件/)
  assert.deepEqual(await canvas().locator('.board-element').evaluateAll(nodes => nodes.map(node => node.dataset.id).sort()), ['F-B','ROOM-B'])
  await page.getByRole('button', { name: '返回空间总览', exact: true }).first().click()
  await page.getByRole('heading', { name: '无限画布 · 空间核对', exact: true }).waitFor()
  assert.equal(await page.locator('[data-layer="design"]>g').count(), 2)
  assert.equal(await page.locator('[data-layer="underlay"] image').count(), 1)
  const final = await state()
  assert.equal(final.objects.length, initial.objects.length + 1)
  assert.deepEqual(final.objects.filter(object => object.kind === 'zone'), initial.objects.filter(object => object.kind === 'zone'))
  assert.deepEqual(final.objects.find(object => object.id === 'F-B'), initial.objects.find(object => object.id === 'F-B'))
  assert.equal(final.floorPlan.previewUrl, source.previewUrl)
  assert.deepEqual(errors, [])
  console.log('单空间 UI 通过：房间/家具隔离、负坐标 L 形/远坐标适屏、缩放/拖动/局部标注、下拉及前后空间切换、其他空间独立自动排版、确认保留、放置取消、URL/刷新/返回总览及数据保留。全部 API 模拟。')
} catch (failure) {
  await debugPage?.screenshot({ path: outDir + '/failure.png' }).catch(() => {})
  throw failure
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
