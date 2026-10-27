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
  const roomPath = () => page.locator('[data-space-id="ROOM-001"] > path').first()
  async function obstacleScreenPoint(x, y) {
    return roomPath().evaluate((path, point) => {
      const values = path.getAttribute('d').match(/-?\d+(?:\.\d+)?/g).map(Number)
      const first = { x: values[0], y: values[1] }, second = { x: values[2], y: values[3] }
      const scale = (second.x - first.x) / 6000
      const transformed = new DOMPoint(first.x + point.x * scale, first.y - point.y * scale).matrixTransform(path.getScreenCTM())
      return { x: transformed.x, y: transformed.y }
    }, { x, y })
  }
  async function dragFraction(left, top, right, bottom) {
    const box = await roomPath().boundingBox()
    assert.ok(box)
    await page.mouse.move(box.x + box.width * left, box.y + box.height * top)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width * right, box.y + box.height * bottom, { steps: 8 })
    await page.mouse.up()
  }
  async function dragElement(locator, dx, dy) {
    const box = await locator.boundingBox()
    assert.ok(box)
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width / 2 + dx, box.y + box.height / 2 + dy, { steps: 8 })
    await page.mouse.up()
  }
  const obstaclePath = id => page.locator(`[data-layer="obstacles"] g[data-obstacle-id="${id}"] > path`)
  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '加载工装示例（可撤销）', exact: true }).click()
  await page.getByRole('button', { name: '放大核对当前空间', exact: true }).click()
  assert.equal(await page.locator('[data-layer="design"] > g').count(), 1)
  await page.getByRole('button', { name: '标柱', exact: true }).click()
  await dragFraction(0.2, 0.2, 0.3, 0.3)
  assert.equal((await stored()).objects.find(item => item.id === 'ROOM-001').obstacles.length, 1)
  await page.getByRole('button', { name: '门洞净空', exact: true }).click()
  await dragFraction(0.68, 0.68, 0.82, 0.78)
  const zone = (await stored()).objects.find(item => item.id === 'ROOM-001')
  const columnId = zone.obstacles[0].obstacleId, doorId = zone.obstacles[1].obstacleId
  const originalRoomPolygon = zone.polygon
  assert.equal(zone.obstacles.length, 2)
  assert.equal(zone.obstacles[0].excludesFloorArea, true)
  assert.equal(zone.obstacles[1].excludesFloorArea, false)
  assert.ok(zone.obstacles[1].geometry.outer.length > 4)
  assert.equal(await page.locator('[data-layer="obstacles"] [data-obstacle-id]').count(), 2)
  await page.locator('.selected-obstacle-editor input').first().fill('500')
  await page.locator('.selected-obstacle-editor input').first().press('Tab')
  assert.equal((await stored()).objects.find(item => item.id === 'ROOM-001').obstacles[1].geometry.outer[1][0] - (await stored()).objects.find(item => item.id === 'ROOM-001').obstacles[1].geometry.outer[0][0], 500)
  const beforeTurn = (await stored()).objects.find(item => item.id === 'ROOM-001').obstacles[1].geometry.outer
  await page.getByRole('button', { name: '换向', exact: true }).click()
  assert.notDeepEqual((await stored()).objects.find(item => item.id === 'ROOM-001').obstacles[1].geometry.outer, beforeTurn)
  for (let index = 0; index < 3; index++) await page.getByRole('button', { name: '换向', exact: true }).click()
  assert.deepEqual((await stored()).objects.find(item => item.id === 'ROOM-001').obstacles[1].geometry.outer, beforeTurn)
  const beforeColumnMove = (await stored()).objects.find(item => item.id === 'ROOM-001').obstacles[0].geometry.outer
  await dragElement(obstaclePath(columnId), 28, 18)
  const afterColumnMove = (await stored()).objects.find(item => item.id === 'ROOM-001').obstacles[0].geometry.outer
  assert.notDeepEqual(afterColumnMove, beforeColumnMove)
  const columnDx = afterColumnMove[0][0] - beforeColumnMove[0][0], columnDy = afterColumnMove[0][1] - beforeColumnMove[0][1]
  afterColumnMove.forEach((point, index) => assert.deepEqual(point, [beforeColumnMove[index][0] + columnDx, beforeColumnMove[index][1] + columnDy]))
  const beforeColumnShape = (await stored()).objects.find(item => item.id === 'ROOM-001').obstacles[0].geometry.outer
  await dragElement(page.locator('[data-layer="obstacle-handles"] rect[data-obstacle-handle="2"]'), 14, 10)
  assert.notDeepEqual((await stored()).objects.find(item => item.id === 'ROOM-001').obstacles[0].geometry.outer, beforeColumnShape)
  const beforeDoorMove = (await stored()).objects.find(item => item.id === 'ROOM-001').obstacles[1].geometry.outer
  await dragElement(obstaclePath(doorId), 20, -14)
  const afterDoorMove = (await stored()).objects.find(item => item.id === 'ROOM-001').obstacles[1].geometry.outer
  assert.notDeepEqual(afterDoorMove[0], beforeDoorMove[0])
  const beforeRadius = (await stored()).objects.find(item => item.id === 'ROOM-001').obstacles[1].geometry.outer
  await dragElement(page.locator('[data-layer="obstacle-handles"] rect[data-obstacle-handle="radius"]'), 12, 8)
  assert.notDeepEqual((await stored()).objects.find(item => item.id === 'ROOM-001').obstacles[1].geometry.outer, beforeRadius)
  assert.deepEqual((await stored()).objects.find(item => item.id === 'ROOM-001').polygon, originalRoomPolygon, 'obstacle editing must not move or resize the room')
  await page.getByRole('button', { name: '标柱', exact: true }).click()
  const snapStart = await obstacleScreenPoint(10, -3000), snapEnd = await obstacleScreenPoint(510, -3400)
  await page.mouse.move(snapStart.x, snapStart.y)
  await page.mouse.down()
  await page.mouse.move(snapEnd.x, snapEnd.y, { steps: 8 })
  await page.mouse.up()
  const snappedZone = (await stored()).objects.find(item => item.id === 'ROOM-001')
  assert.equal(snappedZone.obstacles.length, 3, await page.locator('.review-toast').textContent())
  const snappedMinX = Math.min(...snappedZone.obstacles[2].geometry.outer.map(point => point[0]))
  assert.ok(Math.abs(snappedMinX) < 0.001, 'column snaps to the recognized wall; actual minX=' + snappedMinX)
  await page.screenshot({ path: outDir + '/03-wall-snap.png', fullPage: true })
  await page.locator('.selected-obstacle-editor').getByRole('button', { name: '删除', exact: true }).click()
  assert.equal((await stored()).objects.find(item => item.id === 'ROOM-001').obstacles.length, 2)
  await page.locator('.edge-snap-toggle input').uncheck()
  await page.getByRole('button', { name: '标柱', exact: true }).click()
  const freeStart = await obstacleScreenPoint(10, -4000), freeEnd = await obstacleScreenPoint(510, -4400)
  await page.mouse.move(freeStart.x, freeStart.y)
  await page.mouse.down()
  await page.mouse.move(freeEnd.x, freeEnd.y, { steps: 8 })
  await page.mouse.up()
  const freeZone = (await stored()).objects.find(item => item.id === 'ROOM-001')
  const freeMinX = Math.min(...freeZone.obstacles[2].geometry.outer.map(point => point[0]))
  assert.ok(freeMinX > 5 && freeMinX < 15, 'disabled snapping preserves the manual offset; actual minX=' + freeMinX)
  await page.locator('.selected-obstacle-editor').getByRole('button', { name: '删除', exact: true }).click()
  await page.locator('.edge-snap-toggle input').check()
  const existingColumn = (await stored()).objects.find(item => item.id === 'ROOM-001').obstacles[0]
  const columnXs = existingColumn.geometry.outer.map(point => point[0]), columnYs = existingColumn.geometry.outer.map(point => point[1])
  const columnCenter = { x: (Math.min(...columnXs) + Math.max(...columnXs)) / 2, y: (Math.min(...columnYs) + Math.max(...columnYs)) / 2 }
  const targetCenter = { x: (Math.max(...columnXs) - Math.min(...columnXs)) / 2 + 10, y: -5000 }
  const moveStart = await obstacleScreenPoint(columnCenter.x, columnCenter.y), moveEnd = await obstacleScreenPoint(targetCenter.x, targetCenter.y)
  await page.mouse.move(moveStart.x, moveStart.y)
  await page.mouse.down()
  await page.mouse.move(moveEnd.x, moveEnd.y, { steps: 8 })
  await page.mouse.up()
  const movedToWall = (await stored()).objects.find(item => item.id === 'ROOM-001').obstacles[0]
  assert.ok(Math.abs(Math.min(...movedToWall.geometry.outer.map(point => point[0]))) < 0.001, 'existing column snaps while moving')
  await page.screenshot({ path: outDir + '/01-space-obstacles.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
  await page.setViewportSize({ width: 1600, height: 1000 })
  const beforeReload = (await stored()).objects.find(item => item.id === 'ROOM-001').obstacles
  await page.reload({ waitUntil: 'networkidle' })
  assert.deepEqual((await stored()).objects.find(item => item.id === 'ROOM-001').obstacles, beforeReload)
  await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()
  await page.getByTestId('apply-layout').waitFor()
  assert.equal(await page.getByTestId('design-obstacles').locator(':scope > div').count(), 2)
  await page.screenshot({ path: outDir + '/02-layout-obstacles.png', fullPage: true })
  await page.reload({ waitUntil: 'networkidle' })
  assert.equal(await page.getByTestId('design-obstacles').locator(':scope > div').count(), 2)
  // GEOM-013：排版面板只读展示建筑约束，并跳回空间核对聚焦当前空间标注。
  await page.locator('summary').filter({ hasText: '入口与禁放区' }).click()
  const constraintsPanel = page.getByTestId('constraints-panel')
  assert.equal(await constraintsPanel.locator(':scope > div').count(), 2)
  assert.equal(await page.getByRole('button', { name: '添加入口或禁放区', exact: true }).count(), 0)
  await page.getByTestId('back-to-review-annotate').click()
  await page.getByRole('img', { name: '空间核对画布', exact: true }).waitFor()
  await page.getByRole('button', { name: '返回全图', exact: true }).waitFor()
  assert.deepEqual(errors, [])
  console.log('空间建筑约束 UI 通过：单空间放大、柱/门洞创建、拖动移动、手柄/数值编辑、方向切换、创建/移动贴墙吸附、吸附开关、刷新恢复、排版禁放显示、面板只读与跳回聚焦标注。使用模拟 API。')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
