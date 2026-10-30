import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'node:net'
import { once } from 'node:events'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

// GEOM-016 布置区 + GEOM-018 设施标注 UI 回归（模拟 API）：
// 布置区矩形/多边形绘制、设主、改名、启用/显隐、量测读数、移动/顶点编辑、删主升主、撤销、刷新持久化；
// 设施点位/踢脚线/占用型标注、净空人工标注、显隐、移动、删除；净面积与空间轮廓不受影响。
const cwd = fileURLToPath(new URL('../', import.meta.url))
const outDir = process.env.OUT_DIR || '../output/layout-regions'
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
  const regions = () => zoneState().then(zone => zone.layoutRegions || [])
  const facilities = () => zoneState().then(zone => zone.facilities || [])
  const regionRow = id => page.locator('[data-testid="layout-region-panel"] [data-region-id="' + id + '"]')
  async function zoneBox() {
    const box = await page.locator('polygon[data-id="ROOM-001"]').boundingBox()
    assert.ok(box, '空间轮廓应渲染')
    return box
  }
  async function clickOnZone(fx, fy) {
    const box = await zoneBox()
    await page.mouse.click(box.x + box.width * fx, box.y + box.height * fy)
  }
  async function dragOnZone(left, top, right, bottom) {
    const box = await zoneBox()
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
  /** 布置区多边形可能被家具覆盖：沿边缘探测一个未被遮挡的屏幕点用于拖动（必须命中目标区域自身）。 */
  async function polygonFreePoint(locator, regionId) {
    const box = await locator.boundingBox()
    assert.ok(box)
    const probes = [[0.02, 0.5], [0.5, 0.02], [0.98, 0.5], [0.5, 0.98], [0.02, 0.06], [0.98, 0.06], [0.06, 0.94], [0.94, 0.94]]
    for (const [fx, fy] of probes) {
      const x = box.x + box.width * fx
      const y = box.y + box.height * fy
      const hit = await page.evaluate(([px, py]) => document.elementFromPoint(px, py)?.getAttribute('data-region-id') || '', [x, y])
      if (hit === regionId) return { x, y }
    }
    return null
  }
  /** 家具未覆盖的相对坐标（避开自动排版占位的区域，防止标注落点被家具拦截）。 */
  async function freeFraction(candidates) {
    const state = await stored()
    const zone = state.objects.find(item => item.id === 'ROOM-001')
    const xs = zone.polygon.map(point => point[0])
    const ys = zone.polygon.map(point => point[1])
    const bounds = { minX: Math.min(...xs), maxX: Math.max(...xs), minY: Math.min(...ys), maxY: Math.max(...ys) }
    const items = state.objects.filter(item => item.kind === 'furniture')
    const free = (fx, fy) => {
      const x = bounds.minX + (bounds.maxX - bounds.minX) * fx
      const y = bounds.minY + (bounds.maxY - bounds.minY) * fy
      return !items.some(item => x > item.x - 100 && x < item.x + item.w + 100 && y > item.y - 100 && y < item.y + item.h + 100)
    }
    for (const [fx, fy] of candidates) if (free(fx, fy)) return [fx, fy]
    return candidates[0]
  }
  /** 两个相邻空闲点（踢脚线两端）。 */
  async function freePair() {
    for (const [fx, fy] of [[0.45, 0.92], [0.5, 0.06], [0.4, 0.52], [0.62, 0.3]]) {
      const pair = await freeFraction([[fx, fy]])
      const next = await freeFraction([[pair[0] + 0.12, pair[1]], [pair[0] - 0.12, pair[1]]])
      if (Math.abs(next[0] - pair[0]) > 0.05) return [pair, next]
    }
    return [[0.45, 0.92], [0.57, 0.92]]
  }

  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '加载工装示例（可撤销）', exact: true }).click()
  await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()
  await page.getByTestId('functional-category-workstation').click()
  await page.getByTestId('apply-layout').waitFor()
  const originalPolygon = (await zoneState()).polygon
  assert.equal((await zoneState()).layoutRegions, undefined, '旧画布没有布置区字段，不得自动创建')
  assert.equal((await zoneState()).facilities, undefined, '旧画布没有设施字段，不得自动创建')

  // 空状态：面板默认收起为小胶囊，不占画布；点击展开/收起
  const panelToggle = page.getByTestId('layout-region-panel-toggle')
  await panelToggle.waitFor()
  assert.equal(await page.getByTestId('layout-region-panel').count(), 0, '空状态面板默认收起为胶囊')
  await panelToggle.click()
  const panel = page.getByTestId('layout-region-panel')
  await panel.waitFor()
  await panel.getByText('暂无布置区', { exact: false }).waitFor()
  await page.getByTestId('layout-region-panel-collapse').click()
  await panelToggle.waitFor()

  // 1. 矩形布置区：拖绘 → 首个区域自动设主、默认启用
  await page.getByTestId('toolbar-regions').click()
  await page.getByTestId('region-tool-rect').click()
  await dragOnZone(0.35, 0.15, 0.6, 0.5)
  await page.locator('svg[data-testid="layout-regions"] polygon[data-region-id]').waitFor()
  assert.equal((await regions()).length, 1)
  await panel.waitFor() // 首个布置区创建后面板自动展开
  let region = (await regions())[0]
  assert.equal(region.isPrimary, true, '首个布置区自动设主')
  assert.equal(region.enabled, true)
  assert.equal(region.visible, true)
  assert.equal(region.name, '布置区 1')
  assert.equal(region.polygonMm.length, 4)
  assert.ok(region.wallOffsetsMm?.length === 4, '保存时刷新每条边距墙量测')
  await panel.getByTestId('region-primary-badge').waitFor()
  await page.getByTestId('region-edge-measurements').waitFor()
  assert.equal(await page.locator('[data-testid="region-edge-measurements"] li').count(), 4, '矩形布置区四条边量测')

  // 2. 多边形布置区：逐点绘制 + Enter 结束
  await page.getByTestId('toolbar-regions').click()
  await page.getByTestId('region-tool-poly').click()
  await clickOnZone(0.68, 0.55)
  await page.getByTestId('sketch-preview').waitFor()
  await clickOnZone(0.88, 0.55)
  await clickOnZone(0.88, 0.78)
  await clickOnZone(0.68, 0.78)
  await page.keyboard.press('Enter')
  assert.equal((await regions()).length, 2)
  const second = (await regions())[1]
  assert.equal(second.isPrimary, false, '后续区域不自动设主')
  assert.equal(second.polygonMm.length, 4)
  assert.equal(second.name, '布置区 2')

  // 3. 第三个矩形布置区（左侧）
  await page.getByTestId('toolbar-regions').click()
  await page.getByTestId('region-tool-rect').click()
  await dragOnZone(0.08, 0.55, 0.25, 0.85)
  assert.equal((await regions()).length, 3)

  // 4. 设主：区域 2 升主，区域 1 降主（同空间最多一个主布置区）
  await regionRow(second.regionId).getByTestId('region-set-primary').click()
  assert.equal((await regions())[0].isPrimary, false)
  assert.equal((await regions())[1].isPrimary, true)
  assert.equal(await panel.getByTestId('region-primary-badge').count(), 1)

  // 5. 改名 / 停用 / 隐藏
  const third = (await regions())[2]
  await regionRow(third.regionId).getByTestId('region-name-input').fill('西侧区')
  await page.keyboard.press('Tab')
  assert.equal((await regions())[2].name, '西侧区')
  await regionRow(third.regionId).getByTestId('region-toggle-enabled').uncheck()
  assert.equal((await regions())[2].enabled, false)
  await regionRow(third.regionId).getByTestId('region-toggle-visible').uncheck()
  assert.equal((await regions())[2].visible, false)
  assert.equal(await page.locator('svg[data-testid="layout-regions"] polygon[data-region-id="' + third.regionId + '"]').count(), 0, '隐藏只影响渲染')
  await regionRow(third.regionId).getByTestId('region-toggle-visible').check()
  assert.equal((await regions())[2].visible, true)
  assert.deepEqual((await regions())[2].polygonMm, third.polygonMm, '显隐不改变几何')
  await regionRow(third.regionId).getByTestId('region-toggle-enabled').check()
  assert.equal((await regions())[2].enabled, true)

  // 6. 顶点编辑：选中区域 1 后拖动顶点手柄（手柄可能被家具遮挡，探测可用的顶点）
  await regionRow((await regions())[0].regionId).click()
  await page.getByTestId('region-edge-measurements').waitFor()
  const beforeVertex = (await regions())[0].polygonMm
  let vertexMoved = false
  for (let index = 0; index < beforeVertex.length && !vertexMoved; index++) {
    const handle = page.getByTestId('region-vertex-' + index)
    const box = await handle.boundingBox()
    if (!box) continue
    const cx = box.x + box.width / 2
    const cy = box.y + box.height / 2
    const hit = await page.evaluate(([px, py]) => document.elementFromPoint(px, py)?.getAttribute('data-testid') || '', [cx, cy])
    if (hit !== 'region-vertex-' + index) continue
    await page.mouse.move(cx, cy)
    await page.mouse.down()
    await page.mouse.move(cx + 14, cy + 10, { steps: 6 })
    await page.mouse.up()
    await page.waitForTimeout(200)
    vertexMoved = JSON.stringify((await regions())[0].polygonMm) !== JSON.stringify(beforeVertex)
  }
  assert.ok(vertexMoved, '顶点拖动更新布置区几何')

  // 7. 整体移动：探测未被家具遮挡的边界点后拖动
  const regionId0 = (await regions())[0].regionId
  const polygon0 = page.locator('svg[data-testid="layout-regions"] polygon[data-region-id="' + regionId0 + '"]')
  const freePoint = await polygonFreePoint(polygon0, regionId0)
  assert.ok(freePoint, '应能找到未被遮挡的布置区边界点')
  const beforeMove = (await regions())[0].polygonMm
  await page.mouse.move(freePoint.x, freePoint.y)
  await page.mouse.down()
  await page.mouse.move(freePoint.x + 18, freePoint.y + 12, { steps: 6 })
  await page.mouse.up()
  assert.notDeepEqual((await regions())[0].polygonMm, beforeMove, '整体拖动更新布置区几何')

  // 8. 删除主布置区：首个剩余区域自动升主
  await regionRow(second.regionId).getByTestId('region-delete').click()
  assert.equal((await regions()).length, 2)
  assert.equal((await regions())[0].isPrimary, true, '删除主布置区后首个剩余区域升主')
  assert.equal((await regions())[1].isPrimary, false)

  // 9. 撤销恢复三个区域
  await page.locator('button[title="撤销"]').click()
  assert.equal((await regions()).length, 3)
  assert.equal((await regions())[1].isPrimary, true, '撤销恢复删除前的主布置区标记')

  // 10. GEOM-018 电源点位：单击放置（选空闲位置避免落点被家具/布置区拦截）
  const outletSpot = await freeFraction([[0.85, 0.2], [0.5, 0.06], [0.94, 0.4], [0.06, 0.35], [0.5, 0.94]])
  await page.getByTestId('toolbar-facilities').click()
  await page.getByTestId('facility-tool-POWER_OUTLET').click()
  await clickOnZone(outletSpot[0], outletSpot[1])
  await page.locator('svg[data-testid="spatial-facilities"] [data-facility-id]').waitFor()
  assert.equal((await facilities()).length, 1)
  const outlet = (await facilities())[0]
  assert.equal(outlet.kind, 'POWER_OUTLET')
  assert.equal(outlet.shape, 'point')
  assert.equal(outlet.pointsMm.length, 1)
  await page.getByTestId('facility-editor').waitFor()

  // 11. 踢脚线：逐点绘制 + Enter（线型，不扣面积）
  const [skirtA, skirtB] = await freePair()
  await page.getByTestId('toolbar-facilities').click()
  await page.getByTestId('facility-tool-SKIRTING').click()
  await clickOnZone(skirtA[0], skirtA[1])
  await clickOnZone(skirtB[0], skirtB[1])
  await page.keyboard.press('Enter')
  assert.equal((await facilities()).length, 2)
  const skirting = (await facilities())[1]
  assert.equal(skirting.kind, 'SKIRTING')
  assert.equal(skirting.shape, 'polyline')
  assert.equal(skirting.pointsMm.length, 2)

  // 12. 消防设施：拖绘占地矩形 + 人工标注操作净空/名称/备注
  await page.getByTestId('toolbar-facilities').click()
  await page.getByTestId('facility-tool-FIRE_FACILITY').click()
  await dragOnZone(0.08, 0.08, 0.2, 0.22)
  assert.equal((await facilities()).length, 3)
  const fire = (await facilities())[2]
  assert.equal(fire.kind, 'FIRE_FACILITY')
  assert.equal(fire.shape, 'polygon')
  assert.equal(fire.pointsMm.length, 4)
  const editor = page.getByTestId('facility-editor')
  await editor.waitFor()
  await editor.getByTestId('facility-clearance').fill('900')
  await page.keyboard.press('Tab')
  assert.equal((await facilities())[2].clearanceMm, 900, '操作净空来自人工标注')
  await editor.getByTestId('facility-name').fill('灭火器')
  await page.keyboard.press('Tab')
  assert.equal((await facilities())[2].name, '灭火器')
  await editor.getByTestId('facility-note').fill('靠墙放置')
  await page.keyboard.press('Tab')
  assert.equal((await facilities())[2].note, '靠墙放置')

  // 13. 设施显隐（编辑卡在保存后已打开）与移动
  await editor.getByTestId('facility-toggle-visible').uncheck()
  assert.equal((await facilities())[2].visible, false)
  assert.equal(await page.locator('svg[data-testid="spatial-facilities"] [data-facility-id="' + fire.facilityId + '"]').count(), 0, '隐藏只影响渲染')
  await editor.getByTestId('facility-toggle-visible').check()
  assert.equal((await facilities())[2].visible, true)
  const beforeOutletMove = (await facilities())[0].pointsMm
  await dragElement(page.locator('svg[data-testid="spatial-facilities"] [data-facility-id="' + outlet.facilityId + '"]'), 20, 14)
  assert.notDeepEqual((await facilities())[0].pointsMm, beforeOutletMove, '点位设施可拖动')

  // 14. 删除踢脚线（折线穿过家具时探测未遮挡的线段点点击选中）
  const skirtingLine = page.locator('svg[data-testid="spatial-facilities"] [data-facility-id="' + skirting.facilityId + '"]').first()
  const lineBox = await skirtingLine.boundingBox()
  assert.ok(lineBox)
  let skirtingSelected = false
  for (const fx of [0.5, 0.3, 0.7, 0.12, 0.88]) {
    const x = lineBox.x + lineBox.width * fx
    const y = lineBox.y + lineBox.height / 2
    const hit = await page.evaluate(([px, py]) => document.elementFromPoint(px, py)?.tagName?.toLowerCase() || '', [x, y])
    if (hit !== 'polyline') continue
    await page.mouse.click(x, y)
    skirtingSelected = true
    break
  }
  assert.ok(skirtingSelected, '踢脚线可点击选中')
  await editor.waitFor()
  await editor.getByTestId('facility-delete').click()
  assert.equal((await facilities()).length, 2)

  // 15. 净面积与空间轮廓不受影响：障碍/轮廓保持原样，设施不进入 obstacles
  assert.deepEqual((await zoneState()).polygon, originalPolygon, '布置区与设施编辑不改变空间轮廓')
  assert.equal(((await zoneState()).obstacles || []).length, 0, '设施不进入建筑约束（不扣净面积）')

  // 16. 刷新持久化
  await page.screenshot({ path: outDir + '/01-layout-regions-facilities.png', fullPage: true })
  const beforeReload = { regions: await regions(), facilities: await facilities() }
  await page.reload({ waitUntil: 'networkidle' })
  assert.deepEqual(await regions(), beforeReload.regions, '布置区刷新后保留')
  assert.deepEqual(await facilities(), beforeReload.facilities, '设施刷新后保留')
  await page.getByTestId('layout-region-panel').waitFor()
  await page.locator('svg[data-testid="layout-regions"] polygon[data-region-id]').first().waitFor()
  await page.locator('svg[data-testid="spatial-facilities"] [data-facility-id]').first().waitFor()
  assert.deepEqual(errors, [])
  console.log('布置区/设施标注 UI 通过：矩形与多边形绘制、设主、改名、启停、显隐、量测读数、移动、顶点编辑、删主升主、撤销、点位/踢脚线/消防设施标注、人工净空、删除、净面积不受影响与刷新恢复。使用模拟 API。')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
