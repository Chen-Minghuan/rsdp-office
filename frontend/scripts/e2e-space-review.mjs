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
const baseUrl = 'http://127.0.0.1:' + port
const app = spawn(process.execPath, ['.output/server/index.mjs'], {
  cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' }, stdio: 'ignore',
})
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
  const page = await context.newPage()
  const errors = []
  const dialogs = []
  const profileRequests = []
  context.on('request', request => {
    if (/^\/api\/v1\/office\/projects(?:\/[^/]+)?$/.test(new URL(request.url()).pathname)) profileRequests.push(request.url())
  })
  page.on('pageerror', error => errors.push(error.message))
  page.on('dialog', dialog => { dialogs.push(dialog.message()); void dialog.dismiss() })
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
      { name: '测试会议室', polygon: [[110,250],[690,250],[690,490],[110,490]], sourceAnalysisId: 'PREVIOUS-ANALYSIS', sourceAnalysisRevision: 1, canvasRevision: 20 },
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
  await page.getByRole('img', { name: '空间核对画布', exact: true }).waitFor()
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
  await page.getByTestId('apply-layout').waitFor()
  await page.waitForURL(/mode=design/)
  assert.equal(Object.hasOwn(await stored(), 'project'), false, '旧项目资料从本地草稿移除')
  assert.equal(Object.hasOwn((await stored()).submitted, 'projectContext'), false, '旧需求快照不再含项目资料')
  assert.match(page.url(), /mode=design/)
  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.getByRole('img', { name: '空间核对画布', exact: true }).waitFor()
  assert.ok(await advance().isEnabled(), '空间无需逐个确认即可排版')
  assert.equal(await page.getByRole('button', { name: '确认当前空间', exact: true }).count(), 0)
  assert.equal(await page.locator('[data-layer="design"]>g').count(), 12)
  await page.getByRole('button', { name: '编辑轮廓', exact: true }).click()
  assert.equal(await page.locator('[data-vertex-id]').count(), 4, '只有选中空间展示顶点')
  assert.equal(await page.getByTestId('selected-area').innerText(), '43.20 m²')
  await assertAlignment([1000, 1000])
  await page.setViewportSize({ width: 1952, height: 1278 })
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
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.ok(await advance().isEnabled())
  const beforeLayout = (await stored()).objects.filter(object => object.kind === 'zone')
  await advance().click()
  await page.getByTestId('apply-layout').waitFor()
  await page.waitForURL(/mode=design/)
  assert.match(page.url(), /mode=design/)
  assert.deepEqual((await stored()).objects.filter(object => object.kind === 'zone').map(({ spaceTemplateId, templateSpaceId, ...zone }) => zone), beforeLayout.map(({ spaceTemplateId, templateSpaceId, ...zone }) => zone), '进入排版只绑定模板，不改写人工确认和几何')
  const designUrl = page.url()
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('apply-layout').waitFor()
  assert.equal(page.url(), designUrl, '未逐个确认的有效空间可刷新恢复排版')


  // Import an unknown-scale image through the actual upload target route.
  await page.goto(baseUrl + '/canvas?analysisId=REVIEW-TEST', { waitUntil: 'networkidle' })
  // A first fit on a tiny viewport must not stick: growing the window refits until the user pans or zooms.
  const designWidth = () => page.locator('[data-layer="design"]>g').first().evaluate(el => el.getBoundingClientRect().width)
  await page.setViewportSize({ width: 700, height: 500 })
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('img', { name: '空间核对画布', exact: true }).waitFor()
  await page.waitForTimeout(500)
  const narrowWidth = await designWidth()
  await page.setViewportSize({ width: 1600, height: 1000 })
  await page.waitForTimeout(500)
  assert.ok((await designWidth()) > narrowWidth * 1.5, '小窗口初次适屏后放大窗口会自动重新适屏')
  await page.getByRole('button', { name: '选择空间 测试会议室', exact: true }).waitFor()
  assert.ok(await page.getByLabel('空间宽度（毫米）').isDisabled())
  assert.ok(await advance().isDisabled())
  await page.goto(baseUrl + '/canvas?analysisId=REVIEW-TEST&mode=design&zoneId=TEST-ROOM', { waitUntil: 'networkidle' })
  await page.getByRole('img', { name: '空间核对画布', exact: true }).waitFor()
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
  // 未校准：生成模板/进入排版灰化，点击弹出提示引导两点校准且不跳转
  {
    const advanceBtn = page.getByTestId('advance-layout')
    const templateBtn = page.getByTestId('template-workbench')
    assert.equal(await advanceBtn.getAttribute('aria-disabled'), 'true', '未校准进入排版灰化')
    assert.equal(await templateBtn.getAttribute('aria-disabled'), 'true', '未校准生成模板灰化')
    await advanceBtn.dispatchEvent('click')
    const toast = page.locator('.review-toast')
    await toast.waitFor()
    const text = await toast.innerText()
    assert.ok(text.includes('待校准') && text.includes('两点校准'), '弹出提示说明需先两点校准：' + text)
    await page.getByRole('img', { name: '空间核对画布', exact: true }).waitFor()
    assert.equal(await page.locator('[data-testid="unit-module-catalog"]').count(), 0, '弹出提示后不进入排版')
    await templateBtn.dispatchEvent('click')
    await page.locator('.review-toast').waitFor()
    assert.equal(await page.locator('.space-template-dialog, [role="dialog"]').count(), 0, '弹出提示后不打开模板工作台')
  }
  await page.getByRole('button', { name: /两点校准图纸比例/ }).click()
  for (const point of [[100, 100], [700, 100]]) {
    const screen = await sourceScreenPoint(point)
    await page.mouse.click(screen.x, screen.y)
  }
  await page.locator('.calibration-inline').waitFor()
  assert.equal(await page.locator('.review-inspector').getByLabel('校准参考长度（毫米）').count(), 0, '两点校准在画布两点旁输入，右侧面板不再重复输入框')
  await page.getByLabel('校准参考长度（毫米）').fill('6000')
  await page.getByRole('button', { name: '应用校准', exact: true }).click()
  assert.equal(await page.getByTestId('selected-area').innerText(), '24.00 m²')
  await assertAlignment([100, 100])
  const originalPolygon = (await stored()).objects.find(object => object.id === 'TEST-ROOM').sourcePolygon
  await page.getByLabel('空间宽度（毫米）').fill('5000')
  await page.getByRole('button', { name: '应用尺寸', exact: true }).click()
  assert.equal(await page.getByTestId('selected-area').innerText(), '20.00 m²')
  assert.deepEqual((await stored()).objects.find(object => object.id === 'TEST-ROOM').sourcePolygon, originalPolygon)
  const beforeRestore = (await stored()).objects
  await page.getByRole('button', { name: '恢复本次识别轮廓', exact: true }).click()
  assert.equal(await page.getByTestId('selected-area').innerText(), '24.00 m²')
  assert.deepEqual((await stored()).objects.find(o => o.id === 'TEST-ROOM').sourcePolygon, originalPolygon)
  assert.deepEqual((await stored()).objects.find(o => o.id === 'TEST-L'), beforeRestore.find(o => o.id === 'TEST-L'))
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual((await stored()).objects, beforeRestore, '毫米恢复可完整撤销')
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
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
  await page.setViewportSize({ width: 1600, height: 1000 })
  await page.goto(baseUrl + '/canvas?analysisId=CAD-TEST', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '选择空间 CAD 会议室', exact: true }).waitFor()
  assert.equal(await page.getByTestId('selected-area').innerText(), '24.00 m²')
  await assertAlignment([1000, 1000])
  await page.getByRole('button', { name: '放大画布' }).click()
  await assertAlignment([1000, 1000])
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
    await choose('测试会议室')
    await page.getByRole('status').filter({ hasText: '历史编辑与本次墙线轮廓不一致' }).waitFor()
    await choose('L 形办公区')
    const beforeRestore = (await stored()).objects
    await page.getByRole('button', { name: '恢复本次识别轮廓', exact: true }).click()
    assert.deepEqual((await stored()).objects.find(o => o.id === imported.id).polygon, source.rooms[1].polygon, '未校准恢复沿用来源像素')
    await page.getByRole('button', { name: '撤销', exact: true }).click()
    assert.deepEqual((await stored()).objects, beforeRestore)
    await page.reload({ waitUntil: 'networkidle' })
    assert.deepEqual((await stored()).objects.find(o => o.id === imported.id).polygon, correctedOutline)
  }
  // Removing an unused space excludes it from the current layout/template and remains undoable.
  await choose('L 形办公区')
  await page.getByRole('button', { name: '删除空间 L 形办公区', exact: true }).click()
  assert.equal((await stored()).objects.some(object => object.id === 'CORRECTION-TEST-2-TEST-L'), false, '空间列表删除按钮同步移除空间')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.equal((await stored()).objects.some(object => object.id === 'CORRECTION-TEST-2-TEST-L'), true, '删除空间可撤销恢复')
  // The inspector no longer hosts a delete button; the Delete key removes the selected space and stays undoable.
  assert.equal(await page.locator('.review-inspector').getByRole('button', { name: '删除空间', exact: true }).count(), 0, '右侧属性面板不再提供删除按钮')
  await choose('L 形办公区')
  await page.keyboard.press('Delete')
  assert.equal((await stored()).objects.some(object => object.id === 'CORRECTION-TEST-2-TEST-L'), false, 'Delete 键删除当前选中空间')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.equal((await stored()).objects.some(object => object.id === 'CORRECTION-TEST-2-TEST-L'), true, 'Delete 键删除可撤销恢复')
  // Deleting the last remaining space keeps the canvas intentionally empty
  // even when the route reloads and tries to auto-import the source analysis.
  await page.getByRole('button', { name: '选择空间 L 形办公区', exact: true }).click()
  await page.getByRole('button', { name: '删除空间 L 形办公区', exact: true }).click()
  await page.getByRole('button', { name: '选择空间 测试会议室', exact: true }).click()
  await page.getByRole('button', { name: '删除空间 测试会议室', exact: true }).click()
  assert.equal(await page.locator('.review-room-card').count(), 0, '删除最后空间后列表为空')
  assert.equal((await stored()).blank, true, '最后空间删除后保存空画布标记')
  assert.deepEqual(dialogs, [], '删除空间不再弹出确认框')
  await page.evaluate(() => {
    const state = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    state.floorPlan = null
    localStorage.setItem('rsdp-office-v01', JSON.stringify(state))
  })
  await page.reload({ waitUntil: 'networkidle' })
  assert.equal(await page.locator('.review-room-card').count(), 0, '刷新不会自动恢复已删除空间')
  // 空白画布（无图纸）手动画区：形状模块拖出毫米空间
  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.evaluate(() => {
    const state = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    state.floorPlan = null
    localStorage.setItem('rsdp-office-v01', JSON.stringify(state))
  })
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('img', { name: '空间核对画布', exact: true }).waitFor()
  await page.getByRole('button', { name: '开始画空间', exact: true }).click()
  const sketchRect = async (left, top, right, bottom) => {
    const box = await page.getByRole('img', { name: '空间核对画布', exact: true }).boundingBox()
    const pts = [[left, top], [right, top], [right, bottom], [left, bottom]]
    for (const [fx, fy] of pts) await page.mouse.click(box.x + box.width * fx, box.y + box.height * fy)
    return pts.map(([fx, fy]) => [box.x + box.width * fx, box.y + box.height * fy])
  }
  const sketchDragBox = async (left, top, right, bottom) => {
    const box = await page.getByRole('img', { name: '空间核对画布', exact: true }).boundingBox()
    await page.mouse.move(box.x + box.width * left, box.y + box.height * top)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width * right, box.y + box.height * bottom, { steps: 5 })
    await page.mouse.up()
  }
  // 默认矩形模块：拖出生成
  await sketchDragBox(0.3, 0.25, 0.55, 0.5)
  let drawn = (await stored()).objects.filter(object => object.kind === 'zone' && object.geometrySource === 'manual_geometry')
  assert.equal(drawn.length, 1, '矩形模块拖出后保存为一个手动空间')
  assert.equal(drawn[0].polygon.length, 4, '矩形模块生成四顶点轮廓')
  assert.equal(drawn[0].reviewUnit, 'mm', '空白画布画区直接为毫米单位')
  assert.equal(drawn[0].evidenceKind, 'MANUAL', '手画空间标记人工来源')
  assert.ok(drawn[0].actualWidthMm % 100 === 0 && drawn[0].actualWidthMm > 1000, '毫米场景画区取整到 100mm')
  await page.getByLabel('空间名称').fill('手画开放办公区')
  await page.locator('.selected-title h3').click()
  await page.getByText('手画开放办公区', { exact: true }).first().waitFor()
  // L 形模块：拖包围盒生成六顶点轮廓
  await page.getByRole('button', { name: '画新空间', exact: true }).click()
  await page.locator('[data-testid="sketch-shape-L"]').click()
  await sketchDragBox(0.62, 0.55, 0.8, 0.75)
  drawn = (await stored()).objects.filter(object => object.kind === 'zone' && object.geometrySource === 'manual_geometry')
  assert.equal(drawn.length, 2, 'L 形模块保存第二个空间')
  assert.equal(drawn[1].polygon.length, 6, 'L 形模块生成六顶点轮廓')
  // 旋转模块：90° 旋转后拖出的矩形宽深互换（用同相机的未旋转 L 形推导像素比例，容差含 100mm 取整）
  await page.getByRole('button', { name: '画新空间', exact: true }).click()
  await page.getByLabel('画区旋转角度（度）').fill('90')
  {
    const box = await page.getByRole('img', { name: '空间核对画布', exact: true }).boundingBox()
    await sketchDragBox(0.62, 0.1, 0.8, 0.3)
    drawn = (await stored()).objects.filter(object => object.kind === 'zone' && object.geometrySource === 'manual_geometry')
    assert.equal(drawn.length, 3, '旋转矩形保存为第三个空间')
    const l2 = drawn[1].polygon.map(p => p[0])
    const w2 = Math.max(...l2) - Math.min(...l2)
    const pxPerMm = (0.18 * box.width) / w2
    const expW = (0.3 - 0.1) * box.height / pxPerMm
    const expH = (0.8 - 0.62) * box.width / pxPerMm
    assert.ok(Math.abs(drawn[2].actualWidthMm - expW) <= 100 && Math.abs(drawn[2].actualDepthMm - expH) <= 100,
      '旋转 90° 后宽深互换：' + drawn[2].actualWidthMm + 'x' + drawn[2].actualDepthMm + ' ≈ ' + Math.round(expW) + 'x' + Math.round(expH))
  }
  // 逐点描：毫米场景逐顶点核对存盘几何与点击位置（Y 不翻转）
  await page.getByRole('button', { name: '画新空间', exact: true }).click()
  await page.locator('[data-testid="sketch-shape-poly"]').click()
  const lFractions = [[0.1, 0.55], [0.25, 0.55], [0.25, 0.65], [0.2, 0.65], [0.2, 0.75], [0.1, 0.75]]
  {
    const box = await page.getByRole('img', { name: '空间核对画布', exact: true }).boundingBox()
    for (const [fx, fy] of lFractions) await page.mouse.click(box.x + box.width * fx, box.y + box.height * fy)
  }
  await page.locator('[data-testid="sketch-save"]').click()
  drawn = (await stored()).objects.filter(object => object.kind === 'zone' && object.geometrySource === 'manual_geometry')
  assert.equal(drawn.length, 4, '逐点描保存第四个空间')
  const ring = drawn[3].polygon
  assert.equal(ring.length, 6, '逐点描 L 形保留全部顶点')
  const xs = ring.map(p => p[0]), ys = ring.map(p => p[1])
  const rx = Math.max(...xs) - Math.min(...xs), ry = Math.max(...ys) - Math.min(...ys)
  const fxSpan = 0.25 - 0.1, fySpan = 0.75 - 0.55
  ring.forEach(([px, py], i) => {
    const expectX = Math.min(...xs) + (lFractions[i][0] - 0.1) / fxSpan * rx
    const expectY = Math.min(...ys) + (lFractions[i][1] - 0.55) / fySpan * ry
    assert.ok(Math.abs(px - expectX) < rx * 0.03 && Math.abs(py - expectY) < ry * 0.03, '顶点 ' + i + ' 与点击位置一致（坐标未翻转）')
  })
  assert.ok(await advance().isEnabled(), '手画毫米空间可直接进入家具排版')
  // Esc 取消不留下空间
  await page.getByRole('button', { name: '画新空间', exact: true }).click()
  await sketchRect(0.1, 0.1, 0.2, 0.2)
  await page.keyboard.press('Escape')
  await page.mouse.click(20, 20)
  drawn = (await stored()).objects.filter(object => object.kind === 'zone' && object.geometrySource === 'manual_geometry')
  assert.equal(drawn.length, 4, 'Esc 取消画区不产生空间')
  // 逐点描 Enter 快捷保存（输入框聚焦时不拦截）
  await page.getByRole('button', { name: '画新空间', exact: true }).click()
  await page.locator('[data-testid="sketch-shape-poly"]').click()
  {
    const box = await page.locator('.space-review svg[aria-label="空间核对画布"]').first().boundingBox()
    for (const [fx, fy] of [[0.6, 0.1], [0.75, 0.1], [0.75, 0.25]]) await page.mouse.click(box.x + box.width * fx, box.y + box.height * fy)
  }
  await page.getByText('Enter 保存', { exact: false }).waitFor()
  await page.keyboard.press('Enter')
  await page.waitForTimeout(200)
  drawn = (await stored()).objects.filter(object => object.kind === 'zone' && object.geometrySource === 'manual_geometry')
  assert.equal(drawn.length, 5, 'Enter 保存逐点描空间')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await page.waitForTimeout(200)
  drawn = (await stored()).objects.filter(object => object.kind === 'zone' && object.geometrySource === 'manual_geometry')
  assert.equal(drawn.length, 4, '撤销移除 Enter 保存的空间')
  // 已画空间旋转：绕质心、屏幕顺时针为正（存储环 Y 向下，正角即屏幕顺时针），逐顶点核对
  {
    const centroid = ring => {
      let twiceArea = 0, cx = 0, cy = 0
      for (let i = 0; i < ring.length; i++) {
        const [x0, y0] = ring[i], [x1, y1] = ring[(i + 1) % ring.length]
        const cross = x0 * y1 - x1 * y0
        twiceArea += cross; cx += (x0 + x1) * cross; cy += (y0 + y1) * cross
      }
      return { x: cx / (3 * twiceArea), y: cy / (3 * twiceArea) }
    }
    const rotate = (ring, angleDeg) => {
      const c = centroid(ring), rad = angleDeg * Math.PI / 180, cos = Math.cos(rad), sin = Math.sin(rad)
      return ring.map(([x, y]) => [c.x + (x - c.x) * cos - (y - c.y) * sin, c.y + (x - c.x) * sin + (y - c.y) * cos])
    }
    await page.getByRole('button', { name: '选择空间 手动空间 4', exact: true }).click()
    await page.locator('[data-testid="rotate-form"]').waitFor()
    const before = drawn[3].polygon
    assert.equal(before.length, 6, '以六顶点 L 形验证任意多边形旋转')
    await page.getByLabel('空间旋转角度（度，顺时针为正）').fill('30')
    // 调整角度只预览不落库
    {
      const during = (await stored()).objects.find(object => object.id === drawn[3].id)
      during.polygon.forEach(([px, py], i) => {
        assert.ok(Math.abs(px - before[i][0]) < 1e-9 && Math.abs(py - before[i][1]) < 1e-9, '输入角度未应用前存盘几何不变')
      })
    }
    await page.locator('[data-testid="apply-rotation"]').click()
    await page.waitForTimeout(200)
    let rotated = (await stored()).objects.find(object => object.id === drawn[3].id)
    const expected = rotate(before, 30)
    rotated.polygon.forEach(([px, py], i) => {
      assert.ok(Math.abs(px - expected[i][0]) < 1e-6 && Math.abs(py - expected[i][1]) < 1e-6, '旋转后顶点 ' + i + ' 与质心旋转 30° 一致')
    })
    assert.ok(rotated.actualWidthMm > 0 && rotated.actualDepthMm > 0, '旋转后尺寸派生自新轮廓')
    // 再转 -30° 回到原轮廓（质心不动点，连续旋转不漂移）；Enter 热键应用
    await page.getByLabel('空间旋转角度（度，顺时针为正）').fill('-30')
    await page.getByLabel('空间旋转角度（度，顺时针为正）').press('Enter')
    await page.waitForTimeout(200)
    rotated = (await stored()).objects.find(object => object.id === drawn[3].id)
    rotated.polygon.forEach(([px, py], i) => {
      assert.ok(Math.abs(px - before[i][0]) < 1e-6 && Math.abs(py - before[i][1]) < 1e-6, '反向旋转 30° 恢复原轮廓')
    })
    // 撤销恢复旋转前状态
    await page.getByRole('button', { name: '撤销', exact: true }).click()
    await page.getByRole('button', { name: '撤销', exact: true }).click()
    await page.waitForTimeout(200)
    rotated = (await stored()).objects.find(object => object.id === drawn[3].id)
    rotated.polygon.forEach(([px, py], i) => {
      assert.ok(Math.abs(px - before[i][0]) < 1e-6 && Math.abs(py - before[i][1]) < 1e-6, '撤销恢复旋转前轮廓')
    })
    // 点击空白处只是放弃预览，不会应用
    await page.getByLabel('空间旋转角度（度，顺时针为正）').fill('15')
    {
      const box = await page.getByRole('img', { name: '空间核对画布', exact: true }).boundingBox()
      await page.mouse.click(box.x + box.width * 0.05, box.y + box.height * 0.95)
    }
    await page.waitForTimeout(200)
    rotated = (await stored()).objects.find(object => object.id === drawn[3].id)
    rotated.polygon.forEach(([px, py], i) => {
      assert.ok(Math.abs(px - before[i][0]) < 1e-9 && Math.abs(py - before[i][1]) < 1e-9, '点击空白处不应用旋转')
    })
    // 预览放弃后「应用」仍按当前角度提交
    await page.getByRole('button', { name: '选择空间 手动空间 4', exact: true }).click()
    await page.locator('[data-testid="apply-rotation"]').click()
    await page.waitForTimeout(200)
    rotated = (await stored()).objects.find(object => object.id === drawn[3].id)
    const expected15 = rotate(before, 15)
    rotated.polygon.forEach(([px, py], i) => {
      assert.ok(Math.abs(px - expected15[i][0]) < 1e-6 && Math.abs(py - expected15[i][1]) < 1e-6, '点「应用」提交 15° 旋转')
    })
    // Esc 取消预览：之后点击画布同样不应用
    await page.getByRole('button', { name: '选择空间 手动空间 4', exact: true }).click()
    await page.getByLabel('空间旋转角度（度，顺时针为正）').fill('10')
    await page.getByLabel('空间旋转角度（度，顺时针为正）').press('Escape')
    {
      const box = await page.getByRole('img', { name: '空间核对画布', exact: true }).boundingBox()
      await page.mouse.click(box.x + box.width * 0.05, box.y + box.height * 0.95)
    }
    await page.waitForTimeout(200)
    rotated = (await stored()).objects.find(object => object.id === drawn[3].id)
    rotated.polygon.forEach(([px, py], i) => {
      assert.ok(Math.abs(px - expected15[i][0]) < 1e-6 && Math.abs(py - expected15[i][1]) < 1e-6, 'Esc 取消后点击画布不应用')
    })
    await page.getByRole('button', { name: '撤销', exact: true }).click()
    await page.waitForTimeout(200)
    rotated = (await stored()).objects.find(object => object.id === drawn[3].id)
    rotated.polygon.forEach(([px, py], i) => {
      assert.ok(Math.abs(px - before[i][0]) < 1e-6 && Math.abs(py - before[i][1]) < 1e-6, '撤销已应用的旋转')
    })
    // 一键旋转 90°：点击即应用，无需输入角度
    await page.getByRole('button', { name: '选择空间 手动空间 4', exact: true }).click()
    await page.locator('[data-testid="rotate-90"]').click()
    await page.waitForTimeout(200)
    rotated = (await stored()).objects.find(object => object.id === drawn[3].id)
    const expected90 = rotate(before, 90)
    rotated.polygon.forEach(([px, py], i) => {
      assert.ok(Math.abs(px - expected90[i][0]) < 1e-6 && Math.abs(py - expected90[i][1]) < 1e-6, '一键旋转 90° 逐顶点一致')
    })
    await page.getByRole('button', { name: '撤销', exact: true }).click()
    await page.waitForTimeout(200)
    rotated = (await stored()).objects.find(object => object.id === drawn[3].id)
    rotated.polygon.forEach(([px, py], i) => {
      assert.ok(Math.abs(px - before[i][0]) < 1e-6 && Math.abs(py - before[i][1]) < 1e-6, '撤销一键 90° 旋转')
    })
  }
  // 合并/剔除（GEOM-014）：两个相邻矩形合并为大矩形；重叠剔除收窄轮廓；拒绝分支与撤销
  {
    const zoneCount = async () => (await stored()).objects.filter(object => object.kind === 'zone').length
    const zoneById = async (id) => (await stored()).objects.find(object => object.id === id)
    const ringBounds = ring => ({ minX: Math.min(...ring.map(p => p[0])), maxX: Math.max(...ring.map(p => p[0])), minY: Math.min(...ring.map(p => p[1])), maxY: Math.max(...ring.map(p => p[1])) })
    // 存储坐标（环 Y 向下）→ 客户端坐标：每次从任一多边形空间的路径重建映射，免疫相机平移缩放
    const worldToClient = async (sx, sy) => {
      const zone = (await stored()).objects.find(object => object.kind === 'zone' && object.polygon?.length >= 3)
      const d = await page.locator(`svg[aria-label="空间核对画布"] [data-space-id="${zone.id}"] path`).first().getAttribute('d')
      const svgBox = await page.locator('.space-review svg[aria-label="空间核对画布"]').first().boundingBox()
      const pts = [...d.matchAll(/(-?[\d.]+),(-?[\d.]+)/g)].map(m => [Number(m[1]), Number(m[2])])
      const world = zone.polygon.map(([x, y]) => [x, -y])
      const sMinX = Math.min(...pts.map(p => p[0])), sMaxX = Math.max(...pts.map(p => p[0]))
      const sMinY = Math.min(...pts.map(p => p[1])), sMaxY = Math.max(...pts.map(p => p[1]))
      const wMinX = Math.min(...world.map(p => p[0])), wMaxX = Math.max(...world.map(p => p[0]))
      const wMinY = Math.min(...world.map(p => p[1])), wMaxY = Math.max(...world.map(p => p[1]))
      const kx = (sMaxX - sMinX) / (wMaxX - wMinX), ky = (sMaxY - sMinY) / (wMaxY - wMinY)
      return { x: svgBox.x + sMinX + (sx - wMinX) * kx, y: svgBox.y + sMaxY - (-sy - wMinY) * ky }
    }
    const worldClick = async (sx, sy) => { const p = await worldToClient(sx, sy); await page.mouse.click(p.x, p.y) }
    const worldDrag = async (x1, y1, x2, y2) => {
      const a = await worldToClient(x1, y1), b = await worldToClient(x2, y2)
      await page.mouse.move(a.x, a.y)
      await page.mouse.down()
      await page.mouse.move(b.x, b.y, { steps: 5 })
      await page.mouse.up()
    }
    const before = await zoneCount()
    await page.getByRole('button', { name: '画新空间', exact: true }).click()
    await page.locator('[data-testid="sketch-shape-rect"]').click()
    await page.getByLabel('画区旋转角度（度）').fill('0')
    await sketchDragBox(0.35, 0.58, 0.5, 0.75)
    let zonesNow = (await stored()).objects.filter(object => object.kind === 'zone')
    const mergeA = zonesNow[zonesNow.length - 1]
    const boundsA = ringBounds(mergeA.polygon)
    // 邻接空间 B：贴着 A 右边画出（共享边；世界坐标驱动，免疫相机漂移）
    await page.getByRole('button', { name: '画新空间', exact: true }).click()
    await page.locator('[data-testid="sketch-shape-rect"]').click()
    await worldDrag(boundsA.maxX, boundsA.minY, boundsA.maxX + 600, boundsA.maxY)
    zonesNow = (await stored()).objects.filter(object => object.kind === 'zone')
    const mergeB = zonesNow[zonesNow.length - 1]
    const boundsB = ringBounds(mergeB.polygon)
    assert.equal(await zoneCount(), before + 2, '合并前画好两个相邻空间')
    assert.equal(boundsB.minX, boundsA.maxX, 'B 与 A 共享边')
    const disjointBounds = ringBounds(zonesNow[2].polygon)
    const midAY = (boundsA.minY + boundsA.maxY) / 2
    // 拒绝：合并两个不相交空间
    await page.locator('[data-testid="tool-merge"]').click()
    await page.locator('[data-testid="boolean-bar"]').waitFor()
    await worldClick((boundsA.minX + boundsA.maxX) / 2, midAY)
    await worldClick((disjointBounds.minX + disjointBounds.maxX) / 2, (disjointBounds.minY + disjointBounds.maxY) / 2)
    await page.locator('.review-toast').getByText('不相连', { exact: false }).waitFor()
    assert.equal(await zoneCount(), before + 2, '不相连合并不改动空间')
    assert.deepEqual((await zoneById(mergeA.id)).polygon, mergeA.polygon, '不相连合并不改动轮廓')
    await page.locator('[data-testid="boolean-cancel"]').click()
    // Esc 取消第一次点选
    await page.locator('[data-testid="tool-merge"]').click()
    await worldClick((boundsA.minX + boundsA.maxX) / 2, midAY)
    await page.getByText('再点击要并入的空间', { exact: false }).waitFor()
    await page.keyboard.press('Escape')
    await page.getByText('点击要保留的空间', { exact: false }).waitFor()
    await page.locator('[data-testid="boolean-cancel"]').click()
    // 成功合并：A 保留，B 并入并删除
    await page.locator('[data-testid="tool-merge"]').click()
    await worldClick((boundsA.minX + boundsA.maxX) / 2, midAY)
    await worldClick((boundsB.minX + boundsB.maxX) / 2, (boundsB.minY + boundsB.maxY) / 2)
    await page.locator('.review-toast').getByText('并入', { exact: false }).waitFor()
    assert.equal(await zoneCount(), before + 1, '合并后只剩一个空间')
    const merged = await zoneById(mergeA.id)
    assert.equal(merged.polygon.length, 4, '相邻矩形合并为大矩形')
    assert.equal(Math.round(merged.actualWidthMm), Math.round(mergeA.actualWidthMm + mergeB.actualWidthMm), '合并宽度为两空间之和')
    assert.ok(!(await stored()).objects.some(object => object.id === mergeB.id), '被并入空间已删除')
    // 撤销一次完整恢复两个空间
    await page.getByRole('button', { name: '撤销', exact: true }).click()
    await page.waitForTimeout(200)
    assert.equal(await zoneCount(), before + 2, '撤销恢复两个空间')
    assert.deepEqual((await zoneById(mergeA.id)).polygon, mergeA.polygon, '撤销恢复保留空间轮廓')
    assert.ok(await zoneById(mergeB.id), '撤销恢复被并入空间')
    // 拒绝：剔除不相交空间
    await page.locator('[data-testid="tool-subtract"]').click()
    await worldClick((boundsA.minX + boundsA.maxX) / 2, midAY)
    await worldClick((disjointBounds.minX + disjointBounds.maxX) / 2, (disjointBounds.minY + disjointBounds.maxY) / 2)
    await page.locator('.review-toast').getByText('没有重叠区域', { exact: false }).waitFor()
    assert.deepEqual((await zoneById(mergeA.id)).polygon, mergeA.polygon, '不相交剔除不改动轮廓')
    await page.locator('[data-testid="boolean-cancel"]').click()
    // 成功剔除：刀具覆盖 A 右半并超出上下边，A 收窄，刀具保持不动
    await page.getByRole('button', { name: '画新空间', exact: true }).click()
    await page.locator('[data-testid="sketch-shape-rect"]').click()
    await worldDrag(boundsA.minX + mergeA.actualWidthMm / 2, boundsA.minY - 400, boundsA.maxX + 600, boundsA.maxY + 400)
    zonesNow = (await stored()).objects.filter(object => object.kind === 'zone')
    const cutter = zonesNow[zonesNow.length - 1]
    const cutterLeft = ringBounds(cutter.polygon).minX
    await page.locator('[data-testid="tool-subtract"]').click()
    await worldClick(boundsA.minX + 150, midAY)
    await worldClick(boundsA.maxX + 300, midAY)
    await page.locator('.review-toast').getByText('剔除与', { exact: false }).waitFor()
    assert.equal(await zoneCount(), before + 3, '剔除不删除刀具空间')
    const trimmed = await zoneById(mergeA.id)
    assert.equal(trimmed.polygon.length, 4, '剔除后仍为矩形')
    assert.equal(ringBounds(trimmed.polygon).minX, boundsA.minX, '剔除保留左缘不动')
    assert.ok(Math.abs(trimmed.actualWidthMm - (cutterLeft - boundsA.minX)) <= 1, '剔除后宽度收窄到刀具左缘')
    assert.deepEqual((await zoneById(cutter.id)).polygon, cutter.polygon, '刀具空间保持不动')
    // 撤销恢复剔除前轮廓
    await page.getByRole('button', { name: '撤销', exact: true }).click()
    await page.waitForTimeout(200)
    assert.deepEqual((await zoneById(mergeA.id)).polygon, mergeA.polygon, '撤销恢复剔除前轮廓')
    // 清理刀具空间，避免与后续段落的空间重叠提示叠加
    await worldClick(boundsA.maxX + 300, midAY)
    await page.keyboard.press('Delete')
    await page.waitForTimeout(200)
    assert.equal(await zoneCount(), before + 2, '刀具空间已删除')
    // 恢复后续段落依赖的选中空间
    await page.getByRole('button', { name: '选择空间 手动空间 4', exact: true }).click()
  }
  // 窗标注（GEOM-015）：单空间焦点内沿墙拖出窗矩形；仅标注——不扣面积、不改轮廓、可撤销
  {
    const zoneById = async (id) => (await stored()).objects.find(object => object.id === id)
    const ringBounds = ring => ({ minX: Math.min(...ring.map(p => p[0])), maxX: Math.max(...ring.map(p => p[0])), minY: Math.min(...ring.map(p => p[1])), maxY: Math.max(...ring.map(p => p[1])) })
    const zone4 = (await stored()).objects.find(object => object.kind === 'zone' && object.name === '手动空间 4')
    const zb = ringBounds(zone4.polygon)
    const polygonBefore = JSON.stringify(zone4.polygon)
    // 焦点模式只渲染当前空间，映射固定从手动空间 4 的路径重建
    const worldToClient = async (sx, sy) => {
      const d = await page.locator(`svg[aria-label="空间核对画布"] [data-space-id="${zone4.id}"] path`).first().getAttribute('d')
      const svgBox = await page.locator('.space-review svg[aria-label="空间核对画布"]').first().boundingBox()
      const pts = [...d.matchAll(/(-?[\d.]+),(-?[\d.]+)/g)].map(m => [Number(m[1]), Number(m[2])])
      const world = zone4.polygon.map(([x, y]) => [x, -y])
      const sMinX = Math.min(...pts.map(p => p[0])), sMaxX = Math.max(...pts.map(p => p[0]))
      const sMinY = Math.min(...pts.map(p => p[1])), sMaxY = Math.max(...pts.map(p => p[1]))
      const wMinX = Math.min(...world.map(p => p[0])), wMaxX = Math.max(...world.map(p => p[0]))
      const wMinY = Math.min(...world.map(p => p[1])), wMaxY = Math.max(...world.map(p => p[1]))
      const kx = (sMaxX - sMinX) / (wMaxX - wMinX), ky = (sMaxY - sMinY) / (wMaxY - wMinY)
      return { x: svgBox.x + sMinX + (sx - wMinX) * kx, y: svgBox.y + sMaxY - (-sy - wMinY) * ky }
    }
    const worldDrag = async (x1, y1, x2, y2) => {
      const a = await worldToClient(x1, y1), b = await worldToClient(x2, y2)
      await page.mouse.move(a.x, a.y)
      await page.mouse.down()
      await page.mouse.move(b.x, b.y, { steps: 5 })
      await page.mouse.up()
    }
    await page.getByRole('button', { name: '放大核对当前空间', exact: true }).click()
    await page.locator('[data-testid="tool-obstacle-window"]').waitFor()
    await page.locator('[data-testid="tool-obstacle-window"]').click()
    await worldDrag(zb.minX + 400, zb.minY + 150, zb.minX + 1600, zb.minY + 300)
    await page.locator('.review-toast').getByText('仅标注', { exact: false }).waitFor()
    const withWindow = await zoneById(zone4.id)
    assert.equal(withWindow.obstacles?.length, 1, '窗障碍已保存到当前空间')
    assert.equal(withWindow.obstacles[0].kind, 'WINDOW', '障碍类型为窗')
    assert.equal(withWindow.obstacles[0].excludesFloorArea, false, '窗不扣面积')
    assert.equal(JSON.stringify(withWindow.polygon), polygonBefore, '窗不改变空间轮廓')
    await page.getByRole('button', { name: '撤销', exact: true }).click()
    await page.waitForTimeout(200)
    assert.equal((await zoneById(zone4.id)).obstacles?.length || 0, 0, '撤销移除窗标注')
    await page.getByRole('button', { name: '返回全图', exact: true }).click()
    await page.getByRole('button', { name: '选择空间 手动空间 4', exact: true }).click()
  }
  // 空间列表直接改名
  await page.getByRole('button', { name: '重命名空间 手画开放办公区', exact: true }).click()
  const renameInput = page.getByRole('textbox', { name: '重命名空间 手画开放办公区', exact: true })
  await renameInput.fill('开放办公区 A')
  await renameInput.press('Enter')
  await page.getByText('开放办公区 A', { exact: true }).first().waitFor()
  assert.ok((await stored()).objects.some(object => object.kind === 'zone' && object.name === '开放办公区 A'), '列表改名已保存')
  // 家具排版侧栏同样可改名
  await advance().click()
  await page.getByTestId('apply-layout').waitFor()
  await page.getByRole('button', { name: '手动空间 4', exact: true }).click()
  await page.getByLabel('排版空间名称').fill('设计侧改名区')
  await page.getByLabel('排版空间名称').press('Enter')
  assert.ok((await stored()).objects.some(object => object.kind === 'zone' && object.name === '设计侧改名区'), '排版侧栏改名已保存')
  await page.getByRole('button', { name: '返回空间总览', exact: true }).first().click()
  await page.getByText('设计侧改名区', { exact: true }).first().waitFor()
  // 画布上点选中空间的名称标签：就地改名称/用途/矩形尺寸
  await page.getByRole('button', { name: '选择空间 开放办公区 A', exact: true }).click()
  await page.locator('.space-label-editable').click()
  const inline = page.locator('[data-testid="label-inline"]')
  await inline.waitFor()
  await inline.getByLabel('画布空间名称').fill('画布改名区')
  await inline.getByLabel('画布空间用途').selectOption('MEETING_ROOM')
  await inline.getByLabel('画布空间宽度（毫米）').fill('5200')
  await inline.getByRole('button', { name: '保存', exact: true }).click()
  const edited = (await stored()).objects.find(object => object.kind === 'zone' && object.name === '画布改名区')
  assert.ok(edited, '画布标签改名已保存')
  assert.equal(edited.zoneType, 'MEETING_ROOM', '画布标签改用途已保存')
  assert.equal(edited.actualWidthMm, 5200, '画布标签改宽度已保存并联动轮廓')
  assert.ok(profileRequests.every(url => new URL(url).pathname === '/api/v1/office/projects'), '只调用新项目树接口，不调用已删除的项目资料详情接口')
  assert.deepEqual(errors, [])
  console.log('空间核对 UI 回归通过：统一坐标、原图/轮廓/尺寸图层、毫米编辑与撤销、复核仅提示、直接排版与刷新恢复、比例和几何校验、图片两点校准、L 形顶点拖动、凹角重描、八点自由变换/等比缩放/平移/整边推拉、缩放后坐标、取消与撤销、刷新恢复、移动布局、新识别沿用同图人工轮廓、来源与比例保留、旧错位框保护、手动画区（矩形/L 形模块拖拽与逐点描、毫米取整、90° 旋转宽深互换、逐顶点坐标、已画空间质心旋转（输入预览不落库、仅 Enter/应用提交、点空白或 Esc 放弃）与撤销、总览/列表/排版三处改名、画布名称标签快捷编辑属性、Esc 取消、逐点描 Enter 快捷保存与撤销、空间合并/剔除（相邻合并、重叠剔除、不相连/无重叠拒绝、撤销恢复）、单空间窗标注（不扣面积、不改轮廓、撤销移除））。使用模拟 API。')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
