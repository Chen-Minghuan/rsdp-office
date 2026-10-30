import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'node:net'
import { once } from 'node:events'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const cwd = fileURLToPath(new URL('../', import.meta.url))
const outDir = process.env.OUT_DIR || '../output/space-obstacles'
await mkdir(outDir, { recursive: true })
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
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先执行 pnpm build；独立预览服务未就绪')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  await context.route('**/api/v1/**', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: null }) }))
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const stored = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  const zoneState = () => stored().then(state => state.objects.find(item => item.id === 'ROOM-001'))
  const editor = page.getByTestId('design-constraint-editor')
  async function dragElement(locator, dx, dy) {
    const box = await locator.boundingBox()
    assert.ok(box)
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width / 2 + dx, box.y + box.height / 2 + dy, { steps: 8 })
    await page.mouse.up()
  }
  async function dragOnZone(left, top, right, bottom, beforeUp) {
    const box = await page.locator('polygon[data-id="ROOM-001"]').boundingBox()
    assert.ok(box)
    await page.mouse.move(box.x + box.width * left, box.y + box.height * top)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width * right, box.y + box.height * bottom, { steps: 8 })
    if (beforeUp) await beforeUp()
    await page.mouse.up()
  }
  async function createConstraint(kind, left, top, right, bottom, beforeUp) {
    await page.getByTestId('toolbar-constraints').click()
    await page.getByTestId('constraint-tool-' + kind).click()
    assert.equal(await page.getByRole('img', { name: '空间核对画布', exact: true }).count(), 0, '建筑标注留在布局选品')
    await dragOnZone(left, top, right, bottom, beforeUp)
    await editor.waitFor()
  }

  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '加载工装示例（可撤销）', exact: true }).click()
  for (const label of ['标柱', '门洞净空', '窗', '禁放区']) {
    assert.equal(await page.getByRole('button', { name: label, exact: true }).count(), 0, '空间总览不再提供' + label)
  }
  await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()
  await page.getByTestId('functional-category-workstation').click()
  await page.getByTestId('apply-layout').waitFor()
  const originalRoomPolygon = (await zoneState()).polygon

  await page.getByTestId('design-underlay').waitFor()
  await page.getByTestId('design-underlay-opacity').evaluate(input => {
    input.value = '35'
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
  assert.equal(await page.getByTestId('design-underlay-opacity').inputValue(), '35')
  assert.equal(await page.getByTestId('design-underlay').evaluate(element => element.style.opacity), '0.35')
  await page.getByTestId('design-underlay-toggle').uncheck()
  assert.equal(await page.getByTestId('design-underlay').count(), 0)
  await page.getByTestId('design-underlay-toggle').check()
  await page.getByTestId('design-underlay').waitFor()
  await page.getByText('图层与对照', { exact: true }).waitFor()
  await page.getByLabel('设计空间轮廓', { exact: true }).uncheck()
  assert.equal(await page.locator('polygon.board-element').count(), 0)
  await page.getByLabel('设计空间轮廓', { exact: true }).check()
  assert.equal(await page.locator('polygon.board-element').count(), 1)
  await page.getByLabel('实际尺寸标注', { exact: true }).uncheck()
  assert.equal(await page.getByLabel('排版尺寸标注', { exact: true }).count(), 0)
  await page.getByLabel('实际尺寸标注', { exact: true }).check()
  await page.getByLabel('排版尺寸标注', { exact: true }).waitFor()
  await page.getByLabel('原始识别轮廓', { exact: true }).check()
  await page.getByTestId('original-recognition').waitFor()
  await page.getByLabel('原始识别轮廓', { exact: true }).uncheck()
  assert.equal(await page.getByTestId('original-recognition').count(), 0)

  await createConstraint('COLUMN', 0.2, 0.2, 0.3, 0.3)
  assert.equal((await zoneState()).obstacles[0].kind, 'COLUMN')
  await createConstraint('DOOR_CLEARANCE', 0.68, 0.68, 0.82, 0.78, async () => {
    const draft = page.getByTestId('door-clearance-draft')
    await draft.waitFor()
    assert.ok((await draft.locator('polygon').getAttribute('points')).split(' ').length > 4, '门洞拖动中即显示弧形而非矩形')
  })
  assert.equal((await zoneState()).obstacles[1].kind, 'DOOR_CLEARANCE')
  assert.ok((await zoneState()).obstacles[1].geometry.outer.length > 4)
  const columnId = (await zoneState()).obstacles[0].obstacleId
  const doorId = (await zoneState()).obstacles[1].obstacleId

  await editor.getByLabel('门洞半径（毫米）').fill('500')
  await editor.getByLabel('门洞半径（毫米）').press('Tab')
  assert.equal((await zoneState()).obstacles[1].geometry.outer[1][0] - (await zoneState()).obstacles[1].geometry.outer[0][0], 500)
  const beforeTurn = (await zoneState()).obstacles[1].geometry.outer
  await editor.getByRole('button', { name: '换向', exact: true }).click()
  assert.notDeepEqual((await zoneState()).obstacles[1].geometry.outer, beforeTurn)
  for (let index = 0; index < 3; index++) await editor.getByRole('button', { name: '换向', exact: true }).click()
  assert.deepEqual((await zoneState()).obstacles[1].geometry.outer, beforeTurn)

  const beforeColumnMove = (await zoneState()).obstacles[0].geometry.outer
  await dragElement(page.locator('[data-obstacle-id="' + columnId + '"]'), 24, 16)
  const afterColumnMove = (await zoneState()).obstacles[0].geometry.outer
  assert.notDeepEqual(afterColumnMove, beforeColumnMove)
  await dragElement(page.getByTestId('constraint-handle-2'), 14, 10)
  assert.notDeepEqual((await zoneState()).obstacles[0].geometry.outer, afterColumnMove)

  const beforeDoorMove = (await zoneState()).obstacles[1].geometry.outer
  await dragElement(page.locator('[data-obstacle-id="' + doorId + '"]'), 20, -14)
  const afterDoorMove = (await zoneState()).obstacles[1].geometry.outer
  assert.notDeepEqual(afterDoorMove[0], beforeDoorMove[0])
  await dragElement(page.getByTestId('constraint-handle-radius'), 12, 8)
  assert.notDeepEqual((await zoneState()).obstacles[1].geometry.outer, afterDoorMove)

  const roomBox = await page.locator('polygon[data-id="ROOM-001"]').boundingBox()
  assert.ok(roomBox)
  await page.getByTestId('toolbar-constraints').click()
  await page.getByTestId('constraint-tool-WINDOW').click()
  await page.mouse.move(roomBox.x + 2, roomBox.y + roomBox.height * 0.4)
  await page.mouse.down()
  await page.mouse.move(roomBox.x + 60, roomBox.y + roomBox.height * 0.5, { steps: 8 })
  await page.mouse.up()
  assert.equal((await zoneState()).obstacles[2].kind, 'WINDOW')
  const windowMinX = Math.min(...(await zoneState()).obstacles[2].geometry.outer.map(point => point[0]))
  assert.ok(Math.abs(windowMinX) < 0.001, '窗创建时保留贴墙吸附，实际 minX=' + windowMinX)
  await editor.getByRole('button', { name: /^删除/ }).click()
  assert.equal((await zoneState()).obstacles.length, 2)

  await createConstraint('NO_PLACEMENT', 0.4, 0.4, 0.52, 0.52)
  const constraint = (await zoneState()).obstacles[2]
  assert.equal(constraint.kind, 'NO_PLACEMENT')
  await dragElement(page.locator('[data-obstacle-id="' + constraint.obstacleId + '"]'), 16, 12)
  const movedConstraint = (await zoneState()).obstacles[2]
  assert.notDeepEqual(movedConstraint.geometry.outer, constraint.geometry.outer)
  await dragElement(page.getByTestId('constraint-handle-2'), 12, 8)
  assert.notDeepEqual((await zoneState()).obstacles[2].geometry.outer, movedConstraint.geometry.outer)
  await editor.getByRole('button', { name: /^删除/ }).click()
  assert.equal((await zoneState()).obstacles.length, 2)

  assert.deepEqual((await zoneState()).polygon, originalRoomPolygon, '建筑标注编辑不改变空间轮廓')
  const beforeReload = (await zoneState()).obstacles
  await page.screenshot({ path: outDir + '/02-layout-obstacles.png', fullPage: true })
  await page.reload({ waitUntil: 'networkidle' })
  assert.deepEqual((await zoneState()).obstacles, beforeReload)
  await page.getByTestId('design-underlay').waitFor()
  assert.deepEqual(errors, [])
  console.log('建筑标注 UI 通过：总览入口移除、布局选品原图与透明度、柱/门洞/窗/禁放区新增、移动、缩放、换向、贴墙吸附、删除及刷新恢复。使用模拟 API。')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
