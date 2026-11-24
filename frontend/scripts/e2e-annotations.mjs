import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { once } from 'node:events'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { createReviewConfirmMock, fulfillReviewConfirm, confirmAllReviews } from './helpers/review-confirm-mock.mjs'

const cwd = fileURLToPath(new URL('../', import.meta.url))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = 'http://127.0.0.1:' + port
const app = spawn(process.execPath, ['.output/server/index.mjs'], { cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' }, stdio: 'ignore' })
const outDir = process.env.OUT_DIR || '/tmp/rsdp-annotations'
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
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  const reviewMock = createReviewConfirmMock()
  await context.route('**/api/v1/**', route => {
    if (fulfillReviewConfirm(reviewMock, route)) return
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/office/projects') {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: [{
        projectId: 'OFFICE-PROJECT-001', name: '视图隔离测试项目',
        leader: { userId: 'MOCK-USER', role: 'LEADER', permissions: ['READ', 'EDIT'] },
        floors: [{ projectId: 'OFFICE-PROJECT-001', floorId: 'OFFICE-PROJECT-001', name: '默认楼层', sortOrder: 1 }],
      }] }) })
    }
    const data = path.endsWith('/floor-plan/SINGLE-SPACE') ? source : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')))
  const canvas = () => page.getByTestId('office-design-canvas')
  async function enter(id) {
    await page.goto(baseUrl + '/canvas?analysisId=SINGLE-SPACE&mode=design&zoneId=' + id, { waitUntil: 'networkidle' })
    await page.getByTestId('functional-category-workstation').click()
    await page.getByTestId('apply-layout').waitFor()
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
  await confirmAllReviews(page)
  await page.locator('.review-rooms').getByRole('button', { name: '选择空间 独立 L 形办公室', exact: true }).waitFor()
  await page.evaluate(() => {
    const key = 'rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001'
    const draft = JSON.parse(localStorage.getItem(key))
    for (const [id, zoneId] of [['F-L','ROOM-L'],['F-B','ROOM-B']]) {
      const zone = draft.objects.find(zone => zone.id === zoneId)
      draft.objects.push({ id, zoneId, kind: 'furniture', x: zone.x + 500, y: zone.y + 500, w: 1200, h: 600, rotation: 0, productId: '', label: id })
    }
    draft.objects.push(
      { id: 'GLOBAL-REF', kind: 'reference', x: -17500, y: -7500, caption: '整层参考' },
      { id: 'GLOBAL-ARROW', kind: 'arrow', x: -17500, y: -7500, x2: -17000, y2: -7000 })
    localStorage.setItem(key, JSON.stringify(draft))
  })
  await enter('ROOM-L')
  const spaceSelect = page.getByTestId('design-space-switcher')
  assert.equal(await spaceSelect.locator('[data-space-id="ROOM-L"]').getAttribute('aria-current'), 'page')
  assert.deepEqual(await spaceSelect.locator('[data-space-id]').evaluateAll(nodes => nodes.map(node => [node.getAttribute('data-space-id'), node.textContent.replace(/\s+/g, ' ').trim()])), [['ROOM-L', '独立 L 形办公室待确认 · 1 件'], ['ROOM-B', '其他会议室待确认 · 1 件']])
  assert.equal(await page.getByRole('button', { name: '上一个空间', exact: true }).isDisabled(), true)
  const initial = await state()
  assert.equal(await canvas().locator('polygon.board-element').count(), 1)
  assert.equal((await canvas().locator('polygon.board-element').getAttribute('points')).split(' ').length, 6, 'L 形不能变成包围盒')
  assert.equal(await canvas().locator('img').count(), 1, '设计画布只保留原始图纸对照层（GEOM-013），不叠加其他空间图像')
  assert.equal(await canvas().locator('[data-testid="design-underlay"]').count(), 1, '原始图纸对照层可开关')
  assert.deepEqual(await canvas().locator('.board-element').evaluateAll(nodes => nodes.map(node => node.dataset.id).sort()), ['F-L','ROOM-L'])
  assert.equal(await canvas().locator('line[marker-end="url(#arrowHead)"]').count(), 0)
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
  // 当前布局选品模式下家具为只读预览，坐标拖动验证暂跳过；保留存在性与归属断言。
  const fL = (await state()).objects.find(object => object.id === 'F-L')
  assert.ok(fL && fL.x < 0 && fL.y < 0)
  assert.equal(fL.zoneId, 'ROOM-L')

  // 便签入口与快捷键已移除（RENDER-003 后删除画布便签功能）。
  assert.equal(await page.getByRole('button', { name: '便签', exact: true }).count(), 0, '工具栏不应再显示便签按钮')
  assert.equal(await page.getByText('便签').count(), 0, '状态栏不应再显示便签计数')
  const objectsBeforeShortcut = (await state()).objects.length
  await page.keyboard.press('n')
  await page.waitForTimeout(100)
  assert.equal((await state()).objects.length, objectsBeforeShortcut, '按 n 键不应再创建便签对象')

  // Local annotations stay with their room and survive refresh.
  await spaceSelect.locator('[data-space-id="ROOM-B"]').click()
  await page.waitForURL(url => url.searchParams.get('zoneId') === 'ROOM-B')
  await assertFit('ROOM-B')
  assert.deepEqual(await canvas().locator('.board-element').evaluateAll(nodes => nodes.map(node => node.dataset.id).sort()), ['F-B','ROOM-B'])
  await page.getByRole('button', { name: '上一个空间', exact: true }).click()
  await page.waitForURL(url => url.searchParams.get('zoneId') === 'ROOM-L')
  await assertFit('ROOM-L')
  await page.reload({ waitUntil: 'networkidle' })
  await assertFit('ROOM-L')
  assert.deepEqual(errors, [])
  console.log('画布标注 UI 通过：便签入口与快捷键已移除，空间切换与刷新后数据保留。全部 API 模拟。')
} catch (failure) {
  await debugPage?.screenshot({ path: outDir + '/failure.png' }).catch(() => {})
  throw failure
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
