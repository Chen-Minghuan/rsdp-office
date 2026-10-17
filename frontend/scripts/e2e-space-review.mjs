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
const app = spawn(process.execPath, ['.output/server/index.mjs'], {
  cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' }, stdio: 'ignore',
})
const outDir = process.env.OUT_DIR || './screenshots/space-review'
let browser
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先执行 pnpm build；独立预览服务未就绪')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  await mkdir(outDir, { recursive: true })
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  const page = await context.newPage()
  const errors = []
  const profileRequests = []
  context.on('request', request => {
    if (/^\/api\/v1\/office\/projects(?:\/[^/]+)?$/.test(new URL(request.url()).pathname)) profileRequests.push(request.url())
  })
  page.on('pageerror', error => errors.push(error.message))
  const sourceSvg = '<svg xmlns="http://www.w3.org/2000/svg" width="1100" height="600"><rect width="1100" height="600" fill="white"/><path d="M100 100H700V500H100Z M750 100H1000V200H850V500H750Z" fill="none" stroke="black" stroke-width="4"/></svg>'
  const source = {
    analysisId: 'REVIEW-TEST', status: 'awaiting_confirm', sourceName: '平面图测试.png', inputMode: 'image',
    previewUrl: 'data:image/svg+xml;base64,' + Buffer.from(sourceSvg).toString('base64'),
    previewBounds: { minX: 0, minY: 0, maxX: 1100, maxY: 600 },
    unitResolution: { canonicalUnit: 'px', confirmed: false },
    rooms: [
      { roomId: 'TEST-ROOM', label: '测试会议室', roomType: 'MEETING_ROOM', polygon: [[100, 100], [700, 100], [700, 500], [100, 500]], geometrySource: 'ai_vision' },
      { roomId: 'TEST-L', label: 'L 形办公区', roomType: 'OPEN_OFFICE', polygon: [[750, 100], [1000, 100], [1000, 200], [850, 200], [850, 500], [750, 500]], geometrySource: 'ai_vision' },
    ],
  }
  const cadSource = {
    analysisId: 'CAD-TEST', status: 'awaiting_confirm', inputMode: 'cad', geometrySource: 'cad_geometry', sourceName: '坐标方向测试.dxf',
    unitResolution: { canonicalUnit: 'mm', confirmed: true },
    previewBounds: { minX: 10000, minY: -5000, maxX: 20000, maxY: 5000 },
    previewUrl: 'data:image/svg+xml;base64,' + Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000" viewBox="0 0 10000 10000"><rect width="10000" height="10000" fill="white"/><rect x="1000" y="1000" width="6000" height="4000" fill="none" stroke="black" stroke-width="40"/></svg>').toString('base64'),
    rooms: [{ roomId: 'CAD-ROOM', label: 'CAD 会议室', roomType: 'MEETING_ROOM', geometrySource: 'cad_geometry', polygon: [[11000, 4000], [17000, 4000], [17000, 0], [11000, 0]] }],
  }
  const correctedOutline = [[750, 100], [1000, 100], [1000, 250], [900, 250], [900, 500], [750, 500]]
  const correctionSource = id => ({
    ...source, analysisId: id, imageSha256: 'a'.repeat(64),
    rooms: source.rooms.map((room, index) => ({ ...room, roomId: id + '-' + room.roomId, contourGrounded: index === 0 })),
    designCorrections: [
      { name: 'L 形办公区', polygon: correctedOutline, sourceAnalysisId: 'PREVIOUS-ANALYSIS', sourceAnalysisRevision: 1, canvasRevision: 20 },
      { name: '测试会议室', polygon: [[800,100],[1000,100],[1000,400],[800,400]], sourceAnalysisId: 'PREVIOUS-ANALYSIS', sourceAnalysisRevision: 1, canvasRevision: 20 },
    ],
  })
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    const data = path.includes('/floor-plan/CORRECTION-TEST') ? correctionSource(path.split('/').at(-1)) : path === '/api/v1/floor-plan/REVIEW-TEST' ? source : path === '/api/v1/floor-plan/CAD-TEST' ? cadSource : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const advance = () => page.getByRole('button', { name: '进入家具排版 →', exact: true })
  const choose = name => page.getByRole('button', { name: '选择空间 ' + name, exact: true }).click()
  const stored = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  async function assertAlignment(sourcePoint) {
    const coordinates = await page.locator('[data-layer="underlay"]').evaluate((group, sourcePoint) => {
      const point = new DOMPoint(sourcePoint[0], sourcePoint[1]).matrixTransform(group.getCTM())
      const path = document.querySelector('[data-layer="design"]>g:first-child>path').getAttribute('d')
      const first = path.match(/^M([^,]+),([^LZ ]+)/)
      return { image: [point.x, point.y], polygon: [Number(first[1]), Number(first[2])] }
    }, sourcePoint)
    assert.ok(Math.abs(coordinates.image[0] - coordinates.polygon[0]) < 0.05, JSON.stringify(coordinates))
    assert.ok(Math.abs(coordinates.image[1] - coordinates.polygon[1]) < 0.05, JSON.stringify(coordinates))
  }
  async function sourceScreenPoint(point) {
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
    return page.locator('[data-layer="underlay"]').evaluate((group, point) => {
      const transformed = new DOMPoint(point[0], point[1]).matrixTransform(group.getScreenCTM())
      return { x: transformed.x, y: transformed.y }
    }, point)
  }

  // No profile is needed, even when the backend returns a null canvas for a new workspace.
  await page.goto(baseUrl + '/', { waitUntil: 'networkidle' })
  assert.equal(await page.getByText('项目信息', { exact: true }).count(), 0)
  assert.equal(await page.getByText('建筑面积', { exact: true }).count(), 0)
  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '无限画布 · 空间核对', exact: true }).waitFor()
  assert.equal(await page.locator('.review-room-card').count(), 0, '新工作区不自动填充演示空间')
  await page.getByRole('button', { name: '加载工装示例（可撤销）', exact: true }).click()
  await page.evaluate(() => {
    const state = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    state.project = { name: '旧项目资料', area: 800, headcount: 60, budget: 300000, style: '旧风格', description: '旧说明' }
    state.submitted = { projectContext: state.project, zones: [] }
    localStorage.setItem('rsdp-office-v01', JSON.stringify(state))
  })
  // A fresh direct link waits for source loading, without requiring prior room confirmations.
  await page.goto(baseUrl + '/canvas?mode=design&zoneId=ROOM-001', { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '无限画布 · 单空间设计', exact: true }).waitFor()
  await page.waitForURL(/mode=design/)
  assert.equal(Object.hasOwn(await stored(), 'project'), false, '旧项目资料从本地草稿移除')
  assert.equal(Object.hasOwn((await stored()).submitted, 'projectContext'), false, '旧需求快照不再含项目资料')
  assert.match(page.url(), /mode=design/)
  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '无限画布 · 空间核对', exact: true }).waitFor()
  assert.ok(await advance().isEnabled(), '空间无需逐个确认即可排版')
  assert.equal(await page.getByRole('button', { name: '确认当前空间', exact: true }).count(), 0)
  assert.equal(await page.locator('[data-layer="design"]>g').count(), 12)
  await page.getByRole('button', { name: '编辑轮廓', exact: true }).click()
  assert.equal(await page.locator('[data-vertex-id]').count(), 4, '只有选中空间展示顶点')
  assert.equal(await page.getByTestId('selected-area').innerText(), '43.20 m²')
  await assertAlignment([1000, 1000])
  await page.setViewportSize({ width: 1952, height: 1278 })
  await page.screenshot({ path: outDir + '/01-review-desktop.png' })
  await page.setViewportSize({ width: 1600, height: 1000 })
  const beforeZoom = (await stored()).objects.filter(object => object.kind === 'zone')
  await page.getByRole('button', { name: '放大画布' }).click()
  await assertAlignment([1000, 1000])
  assert.deepEqual((await stored()).objects.filter(object => object.kind === 'zone'), beforeZoom)
  await page.getByRole('button', { name: '适屏', exact: true }).click()
  await page.getByRole('switch', { name: '原始图纸', exact: true }).uncheck()
  assert.equal(await page.locator('[data-layer="underlay"]').count(), 0)
  await page.getByRole('switch', { name: '原始图纸', exact: true }).check()
  await page.getByRole('switch', { name: '实际尺寸标注', exact: true }).uncheck()
  assert.equal(await page.locator('[data-layer="dimensions"]').count(), 0)
  await page.getByRole('switch', { name: '实际尺寸标注', exact: true }).check()
  await page.getByLabel('空间宽度（毫米）').fill('5000')
  await page.getByRole('button', { name: '应用尺寸', exact: true }).click()
  assert.equal(await page.getByTestId('selected-area').innerText(), '36.00 m²')
  assert.equal((await stored()).objects.find(object => object.id === 'ROOM-001').confirmed, false)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.equal(await page.getByTestId('selected-area').innerText(), '43.20 m²')
  await page.getByLabel('空间宽度（毫米）').fill('8000')
  await page.getByRole('button', { name: '应用尺寸', exact: true }).click()
  assert.match(await page.getByRole('status').filter({ hasText: '边界重叠' }).innerText(), /重叠/)
  assert.ok(await advance().isEnabled(), '边界重叠只提示复核，不强制处理')
  await page.screenshot({ path: outDir + '/06-advisory-overlap.png' })
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.ok(await advance().isEnabled())
  const beforeLayout = (await stored()).objects.filter(object => object.kind === 'zone')
  await advance().click()
  await page.getByRole('heading', { name: '无限画布 · 单空间设计', exact: true }).waitFor()
  await page.waitForURL(/mode=design/)
  assert.match(page.url(), /mode=design/)
  await page.screenshot({ path: outDir + '/02-layout-gate-open.png' })
  assert.deepEqual((await stored()).objects.filter(object => object.kind === 'zone').map(({ spaceTemplateId, templateSpaceId, ...zone }) => zone), beforeLayout.map(({ spaceTemplateId, templateSpaceId, ...zone }) => zone), '进入排版只绑定模板，不改写人工确认和几何')
  const designUrl = page.url()
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '无限画布 · 单空间设计', exact: true }).waitFor()
  assert.equal(page.url(), designUrl, '未逐个确认的有效空间可刷新恢复排版')


  // Import an unknown-scale image through the actual upload target route.
  await page.goto(baseUrl + '/canvas?analysisId=REVIEW-TEST', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '选择空间 测试会议室', exact: true }).waitFor()
  assert.ok(await page.getByLabel('空间宽度（毫米）').isDisabled())
  assert.ok(await advance().isDisabled())
  await page.goto(baseUrl + '/canvas?analysisId=REVIEW-TEST&mode=design&zoneId=TEST-ROOM', { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '无限画布 · 空间核对', exact: true }).waitFor()
  assert.doesNotMatch(page.url(), /mode=design/, '未知比例不能绕过实际尺寸要求')
  await assertAlignment([100, 100])
  // Redraw a truncated room with an entrance recess before calibration.
  await page.getByRole('button', { name: '编辑轮廓', exact: true }).click()
  const beforeTrace = (await stored()).objects
  await page.getByRole('button', { name: '沿墙重描轮廓', exact: true }).click()
  const outline = [[100, 100], [700, 100], [700, 500], [500, 500], [500, 550], [100, 550]]
  for (const point of outline) {
    const screen = await sourceScreenPoint(point)
    await page.mouse.click(screen.x, screen.y)
  }
  assert.deepEqual((await stored()).objects, beforeTrace, '描点期间不改旧几何')
  await page.getByRole('button', { name: '保存轮廓', exact: true }).click()
  let traced = (await stored()).objects.find(o => o.id === 'TEST-ROOM')
  assert.equal(traced.polygon.length, 6)
  assert.equal(traced.geometrySource, 'manual_geometry')
  assert.deepEqual(traced.sourcePolygon, beforeTrace.find(o => o.id === 'TEST-ROOM').sourcePolygon)
  assert.equal(traced.reviewUnit, beforeTrace.find(o => o.id === 'TEST-ROOM').reviewUnit, '重描不猜测比例')
  for (let i = 0; i < outline.length; i++) for (let axis = 0; axis < 2; axis++) assert.ok(Math.abs(traced.polygon[i][axis] - outline[i][axis]) < 0.01)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual((await stored()).objects, beforeTrace)
  await page.getByRole('button', { name: /两点校准图纸比例/ }).click()
  for (const point of [[100, 100], [700, 100]]) {
    const screen = await sourceScreenPoint(point)
    await page.mouse.click(screen.x, screen.y)
  }
  await page.getByLabel('校准参考长度（毫米）').fill('6000')
  await page.getByRole('button', { name: '应用校准', exact: true }).click()
  assert.equal(await page.getByTestId('selected-area').innerText(), '24.00 m²')
  await assertAlignment([100, 100])
  const originalPolygon = (await stored()).objects.find(object => object.id === 'TEST-ROOM').sourcePolygon
  await page.getByLabel('空间宽度（毫米）').fill('5000')
  await page.getByRole('button', { name: '应用尺寸', exact: true }).click()
  assert.equal(await page.getByTestId('selected-area').innerText(), '20.00 m²')
  assert.deepEqual((await stored()).objects.find(object => object.id === 'TEST-ROOM').sourcePolygon, originalPolygon)
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '选择空间 测试会议室', exact: true }).waitFor()
  assert.equal(await page.getByTestId('selected-area').innerText(), '20.00 m²', '刷新不得重导入而覆盖设计副本')
  await choose('L 形办公区')
  await page.getByRole('button', { name: '编辑轮廓', exact: true }).click()
  assert.equal(await page.locator('[data-vertex-id]').count(), 6)
  assert.equal(await page.getByTestId('selected-area').innerText(), '5.50 m²')
  const vertex = page.locator('[data-vertex-id="TEST-L-V-2"]')
  const beforeDrag = (await stored()).objects.find(object => object.id === 'TEST-L').polygon
  const vertexBox = await vertex.boundingBox()
  await page.mouse.move(vertexBox.x + 4, vertexBox.y + 4)
  await page.mouse.down()
  await page.mouse.move(vertexBox.x + 20, vertexBox.y + 4, { steps: 5 })
  await page.mouse.up()
  const moved = (await stored()).objects.find(object => object.id === 'TEST-L')
  assert.equal(moved.polygon.length, 6, '顶点拖动保持异形轮廓')
  assert.notDeepEqual(moved.polygon, beforeDrag)
  await page.getByRole('button', { name: '编辑轮廓', exact: true }).click()
  await page.locator('[data-edge-index="0"]').click()
  assert.equal((await stored()).objects.find(o => o.id === 'TEST-L').polygon.length, 7)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual((await stored()).objects.find(o => o.id === 'TEST-L').polygon, moved.polygon)
  await page.getByRole('button', { name: '沿墙重描轮廓', exact: true }).click()
  for (const point of [[750, 100], [1000, 500], [1000, 100], [750, 500]]) {
    const screen = await sourceScreenPoint(point)
    await page.mouse.click(screen.x, screen.y)
  }
  assert.ok(await advance().isDisabled())
  await page.getByRole('button', { name: '保存轮廓', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '轮廓存在自交' }).waitFor()
  assert.deepEqual((await stored()).objects.find(o => o.id === 'TEST-L').polygon, moved.polygon)
  await page.keyboard.press('Escape')
  assert.equal(await page.getByRole('button', { name: '取消重描', exact: true }).count(), 0)
  await page.getByRole('button', { name: '沿墙重描轮廓', exact: true }).click()
  const millimetreOutline = [[750, 100], [1000, 100], [1000, 250], [900, 250], [900, 500], [750, 500]]
  for (const point of millimetreOutline) {
    const screen = await sourceScreenPoint(point)
    await page.mouse.click(screen.x, screen.y)
  }
  await page.getByRole('button', { name: '退回一点', exact: true }).click()
  const lastScreen = await sourceScreenPoint(millimetreOutline.at(-1))
  await page.mouse.click(lastScreen.x, lastScreen.y)
  await page.getByRole('button', { name: '保存轮廓', exact: true }).click()
  traced = (await stored()).objects.find(o => o.id === 'TEST-L')
  assert.equal(traced.polygon.length, millimetreOutline.length, JSON.stringify(traced.polygon))
  for (let i = 0; i < millimetreOutline.length; i++) for (let axis = 0; axis < 2; axis++) assert.ok(Math.abs(traced.polygon[i][axis] - millimetreOutline[i][axis] * 10) < 0.1)
  assert.equal(await page.getByTestId('selected-area').innerText(), '7.50 m²')
  await page.reload({ waitUntil: 'networkidle' })
  await choose('L 形办公区')
  assert.deepEqual((await stored()).objects.find(o => o.id === 'TEST-L').polygon, traced.polygon)
  await page.getByRole('button', { name: '编辑轮廓', exact: true }).click()
  await page.screenshot({ path: outDir + '/03-image-calibration-edit.png' })
  // Direct transform uses design geometry, preserving concavity, source and neighbours.
  await page.getByRole('button', { name: '选择 / 缩放', exact: true }).click()
  assert.equal(await page.locator('[data-resize-handle]').count(), 8)
  const originalTransformObjects = (await stored()).objects
  const baseRing = originalTransformObjects.find(o => o.id === 'TEST-L').polygon
  const near = (a, b) => assert.ok(Math.abs(a - b) < 0.1, `${a} != ${b}`)
  async function handleDrag(name, dx, dy, cancel = false) {
    const box = await page.locator(`[data-resize-handle="${name}"]`).boundingBox()
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width / 2 + dx, box.y + box.height / 2 + dy, { steps: 5 })
    if (cancel) await page.keyboard.press('Escape')
    await page.mouse.up()
  }
  const originScreen = await sourceScreenPoint([0, 0]), unitScreen = await sourceScreenPoint([100, 100])
  const mmPerScreen = 1000 / (unitScreen.x - originScreen.x)
  await handleDrag('e', 40, 0)
  let transformed = (await stored()).objects.find(o => o.id === 'TEST-L')
  assert.equal(transformed.polygon.length, baseRing.length)
  near(transformed.polygon[0][0], baseRing[0][0])
  near(transformed.polygon[1][0], baseRing[1][0] + 40 * mmPerScreen)
  near(transformed.polygon[1][1], baseRing[1][1])
  assert.deepEqual(transformed.sourcePolygon, traced.sourcePolygon)
  assert.deepEqual((await stored()).objects.find(o => o.id === 'TEST-ROOM'), originalTransformObjects.find(o => o.id === 'TEST-ROOM'))
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await page.getByRole('checkbox', { name: '锁定比例（角点 / Shift）' }).check()
  await handleDrag('se', 40, 10)
  transformed = (await stored()).objects.find(o => o.id === 'TEST-L')
  const scaleX = (transformed.polygon[1][0] - transformed.polygon[0][0]) / (baseRing[1][0] - baseRing[0][0])
  const scaleY = (transformed.polygon[5][1] - transformed.polygon[0][1]) / (baseRing[5][1] - baseRing[0][1])
  near(scaleX, scaleY)
  assert.ok(scaleX > 1)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await handleDrag('nw', 30, 30, true)
  assert.deepEqual((await stored()).objects.find(o => o.id === 'TEST-L').polygon, baseRing, 'Escape cancels gesture')
  const moveStart = await sourceScreenPoint([800, 350])
  await page.mouse.move(moveStart.x, moveStart.y)
  await page.mouse.down()
  await page.mouse.move(moveStart.x + 20, moveStart.y + 15, { steps: 4 })
  await page.mouse.up()
  transformed = (await stored()).objects.find(o => o.id === 'TEST-L')
  baseRing.forEach(([x, y], i) => { near(transformed.polygon[i][0], x + 20 * mmPerScreen); near(transformed.polygon[i][1], y + 15 * mmPerScreen) })
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  // A local edge pushes its two endpoints without moving unrelated corners.
  await page.getByRole('button', { name: '编辑轮廓', exact: true }).click()
  const edgeStart = await sourceScreenPoint([800, 100])
  await page.mouse.move(edgeStart.x, edgeStart.y)
  await page.mouse.down()
  await page.mouse.move(edgeStart.x, edgeStart.y + 20, { steps: 4 })
  await page.mouse.up()
  transformed = (await stored()).objects.find(o => o.id === 'TEST-L')
  near(transformed.polygon[0][1], baseRing[0][1] + 20 * mmPerScreen)
  near(transformed.polygon[1][1], baseRing[1][1] + 20 * mmPerScreen)
  assert.deepEqual(transformed.polygon.slice(2), baseRing.slice(2))
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await page.getByRole('button', { name: '选择 / 缩放', exact: true }).click()
  await page.getByRole('button', { name: '放大画布', exact: true }).click()
  assert.deepEqual((await stored()).objects.find(o => o.id === 'TEST-L').polygon, baseRing)
  const zoomOrigin = await sourceScreenPoint([0, 0]), zoomUnit = await sourceScreenPoint([100, 100])
  await handleDrag('n', 0, 10)
  transformed = (await stored()).objects.find(o => o.id === 'TEST-L')
  near(transformed.polygon[0][1], baseRing[0][1] + 10000 / (zoomUnit.x - zoomOrigin.x))
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  const cancelHandle = await page.locator('[data-resize-handle="e"]').boundingBox()
  await page.mouse.move(cancelHandle.x + 5, cancelHandle.y + 5)
  await page.mouse.down()
  await page.mouse.move(cancelHandle.x + 35, cancelHandle.y + 5)
  await page.locator('svg[aria-label="空间核对画布"]').dispatchEvent('pointercancel', { pointerId: 1 })
  await page.mouse.up()
  assert.deepEqual((await stored()).objects.find(o => o.id === 'TEST-L').polygon, baseRing, 'pointercancel restores geometry')
  await page.screenshot({ path: outDir + '/07-free-transform.png' })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: outDir + '/04-review-mobile.png', fullPage: true })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
  await page.setViewportSize({ width: 1600, height: 1000 })
  await page.goto(baseUrl + '/canvas?analysisId=CAD-TEST', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '选择空间 CAD 会议室', exact: true }).waitFor()
  assert.equal(await page.getByTestId('selected-area').innerText(), '24.00 m²')
  await assertAlignment([1000, 1000])
  await page.getByRole('button', { name: '放大画布' }).click()
  await assertAlignment([1000, 1000])
  await page.screenshot({ path: outDir + '/05-cad-axis-mock.png' })
  // A fresh analysis gets a new design overlay; its new raw recognition polygon remains intact.
  for (const id of ['CORRECTION-TEST', 'CORRECTION-TEST-2']) {
    await page.goto(baseUrl + '/canvas?analysisId=' + id, { waitUntil: 'networkidle' })
    await choose('L 形办公区')
    const imported = (await stored()).objects.find(o => o.id === id + '-TEST-L')
    assert.deepEqual(imported.polygon, correctedOutline)
    assert.deepEqual(imported.sourcePolygon, source.rooms[1].polygon)
    assert.equal(imported.geometrySource, 'manual_geometry')
    assert.equal(imported.evidenceKind, 'MANUAL')
    assert.equal(imported.confirmed, false)
    assert.equal(imported.reviewUnit, 'source', '继承轮廓不代替比例确认')
    assert.equal(imported.contourCorrection.sourceAnalysisId, 'PREVIOUS-ANALYSIS')
    assert.deepEqual((await stored()).objects.find(o => o.id === id + '-TEST-ROOM').polygon, source.rooms[0].polygon, '旧错位框不覆盖新定位的封闭房间')
    await page.reload({ waitUntil: 'networkidle' })
    assert.deepEqual((await stored()).objects.find(o => o.id === imported.id).polygon, correctedOutline)
  }
  // Removing an unused space excludes it from the current layout/template and remains undoable.
  await choose('L 形办公区')
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '删除空间', exact: true }).click()
  assert.equal((await stored()).objects.some(object => object.id === 'CORRECTION-TEST-2-TEST-L'), false, '删除空间同步移除空间')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.equal((await stored()).objects.some(object => object.id === 'CORRECTION-TEST-2-TEST-L'), true, '删除空间可撤销恢复')
  // Deleting the last remaining space keeps the canvas intentionally empty
  // even when the route reloads and tries to auto-import the source analysis.
  await page.getByRole('button', { name: '选择空间 L 形办公区', exact: true }).click()
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '删除空间', exact: true }).click()
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '选择空间 测试会议室', exact: true }).click()
  await page.getByRole('button', { name: '删除空间', exact: true }).click()
  assert.equal(await page.locator('.review-room-card').count(), 0, '删除最后空间后列表为空')
  assert.equal((await stored()).blank, true, '最后空间删除后保存空画布标记')
  await page.evaluate(() => {
    const state = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    state.floorPlan = null
    localStorage.setItem('rsdp-office-v01', JSON.stringify(state))
  })
  await page.reload({ waitUntil: 'networkidle' })
  assert.equal(await page.locator('.review-room-card').count(), 0, '刷新不会自动恢复已删除空间')
  await page.screenshot({ path: outDir + '/08-reused-contour.png' })
  assert.deepEqual(profileRequests, [], '不调用已删除的项目资料接口')
  assert.deepEqual(errors, [])
  console.log('空间核对 UI 回归通过：统一坐标、原图/轮廓/尺寸图层、毫米编辑与撤销、复核仅提示、直接排版与刷新恢复、比例和几何校验、图片两点校准、L 形顶点拖动、凹角重描、八点自由变换/等比缩放/平移/整边推拉、缩放后坐标、取消与撤销、刷新恢复、移动布局、新识别沿用同图人工轮廓、来源与比例保留、旧错位框保护。使用模拟 API。')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
