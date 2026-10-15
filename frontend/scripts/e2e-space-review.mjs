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
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    const data = path === '/api/v1/floor-plan/REVIEW-TEST' ? source : path === '/api/v1/floor-plan/CAD-TEST' ? cadSource : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
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
    return page.locator('[data-layer="underlay"]').evaluate((group, point) => {
      const transformed = new DOMPoint(point[0], point[1]).matrixTransform(group.getScreenCTM())
      return { x: transformed.x, y: transformed.y }
    }, point)
  }

  // A fresh direct link waits for source loading, without requiring prior room confirmations.
  await page.goto(baseUrl + '/canvas?mode=design&zoneId=ROOM-001', { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '无限画布 · 单空间设计', exact: true }).waitFor()
  await page.waitForURL(/mode=design/)
  assert.match(page.url(), /mode=design/)
  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '无限画布 · 空间核对', exact: true }).waitFor()
  assert.ok(await advance().isEnabled(), '空间无需逐个确认即可排版')
  assert.equal(await page.getByRole('button', { name: '确认当前空间', exact: true }).count(), 0)
  assert.equal(await page.locator('[data-layer="design"]>g').count(), 12)
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
  await page.screenshot({ path: outDir + '/03-image-calibration-edit.png' })
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
  assert.deepEqual(errors, [])
  console.log('空间核对 UI 回归通过：统一坐标、原图/轮廓/尺寸图层、毫米编辑与撤销、复核仅提示、直接排版与刷新恢复、比例和几何校验、图片两点校准、L 形顶点拖动、刷新恢复、移动布局。使用模拟 API。')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
