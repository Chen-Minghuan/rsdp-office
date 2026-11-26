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
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  const reviewMock = createReviewConfirmMock()
  await context.route('**/api/v1/**', async route => {
    if (fulfillReviewConfirm(reviewMock, route)) return
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/office/projects') {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: [{
        projectId: 'OFFICE-PROJECT-001', name: '视图隔离测试项目',
        leader: { userId: 'MOCK-USER', role: 'LEADER', permissions: ['READ', 'EDIT'] },
        floors: [{ projectId: 'OFFICE-PROJECT-001', floorId: 'OFFICE-PROJECT-001', name: '默认楼层', sortOrder: 1 }],
      }] }) })
    }
    if (path.endsWith(`/office/projects/${encodeURIComponent('OFFICE-PROJECT-001')}/module-recommendations`)) {
      const req = await route.request().postDataJSON()
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: { moduleId: req.moduleId, zoneId: req.zoneId, canvasRevision: req.canvasRevision, widthMm: 1200, depthMm: 600, targetCategory: '职员桌', sameModuleCount: 2, missingDimensions: 0, eligibleVariantCount: 2, candidates: [
        { product: { id: 'PRODUCT-A', rspuId: 'RSPU-A', rspuCode: 'A', name: '测试商品', widthMm: 1200, depthMm: 600, price: 100 }, tier: 'CLOSE', fitRatio: 1, rotationDeg: 0, remainingWidthMm: 0, remainingDepthMm: 0, relevanceScore: 95, matchReason: '同品类' },
        { product: { id: 'PRODUCT-B', rspuId: 'RSPU-B', rspuCode: 'B', name: '测试商品B', widthMm: 1100, depthMm: 550, price: 200 }, tier: 'CLOSE', fitRatio: 0.9, rotationDeg: 0, remainingWidthMm: 100, remainingDepthMm: 50, relevanceScore: 90, matchReason: '同品类' },
      ] } }) })
    }
    if (path.endsWith(`/office/projects/${encodeURIComponent('OFFICE-PROJECT-001')}/module-products`)) {
      const body = await route.request().postDataJSON()
      await page.evaluate(req => { window.lastModuleProductRequest = req }, body)
      // 回显当前画布，避免绑定动作把测试中的家具清空
      const objects = await page.evaluate(() => {
        const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
        return JSON.parse(JSON.stringify(store.objects))
      })
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: { canvas: { objects, files: [], revision: 2 }, cartItems: [], match: { product: { id: 'PRODUCT-A', name: '测试商品', widthMm: 1200, depthMm: 600, price: 100 }, tier: 'CLOSE', fitRatio: 1, rotationDeg: 0, remainingWidthMm: 0, remainingDepthMm: 0 } } }) })
    }
    const data = path.endsWith('/floor-plan/SINGLE-SPACE') ? source : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')))
  const canvas = () => page.getByTestId('office-design-canvas')
  async function enter(id) {
    await page.goto(baseUrl + '/canvas?analysisId=SINGLE-SPACE&mode=design&zoneId=' + id, { waitUntil: 'networkidle' })
    // 进入排版不再默认选中功能模块（3b42ed3），需显式点选品类后才出现排版操作
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
    const roomL = draft.objects.find(zone => zone.id === 'ROOM-L')
    const roomB = draft.objects.find(zone => zone.id === 'ROOM-B')
    draft.objects.push(
      { id: 'F-L', zoneId: 'ROOM-L', kind: 'furniture', x: roomL.x + 500, y: roomL.y + 2400, w: 1200, h: 50, rotation: 0, productId: '', label: '办公桌', furnitureTemplateId: 'desk-straight' },
      { id: 'F-B', zoneId: 'ROOM-B', kind: 'furniture', x: roomB.x + 500, y: roomB.y + 500, w: 1200, h: 600, rotation: 0, productId: '', label: '办公桌', furnitureTemplateId: 'desk-straight' },
      { id: 'F-L2', zoneId: 'ROOM-L', kind: 'furniture', x: roomL.x + 500, y: roomL.y + 3000, w: 1200, h: 50, rotation: 0, productId: '', label: '办公桌', furnitureTemplateId: 'desk-straight' },
      { id: 'F-L3', zoneId: 'ROOM-L', kind: 'furniture', x: roomL.x + 4000, y: roomL.y + 2400, w: 800, h: 50, rotation: 0, productId: '', label: '办公桌2', furnitureTemplateId: 'desk-straight' },
      { id: 'F-L4', zoneId: 'ROOM-L', kind: 'furniture', x: roomL.x + 1000, y: roomL.y + 4000, w: 2400, h: 1200, rotation: 0, productId: '', label: '会议桌', furnitureTemplateId: 'table-conference-rect' }
    )
    draft.objects.push(
      { id: 'GLOBAL-REF', kind: 'reference', x: -17500, y: -7500, caption: '整层参考' },
      { id: 'GLOBAL-ARROW', kind: 'arrow', x: -17500, y: -7500, x2: -17000, y2: -7000 })
    localStorage.setItem(key, JSON.stringify(draft))
  })
  await enter('ROOM-L')
  const spaceSelect = page.getByTestId('design-space-switcher')
  assert.equal(await spaceSelect.locator('[data-space-id="ROOM-L"]').getAttribute('aria-current'), 'page')
  assert.deepEqual(await spaceSelect.locator('[data-space-id]').evaluateAll(nodes => nodes.map(node => [node.getAttribute('data-space-id'), node.textContent.replace(/\s+/g, ' ').trim()])), [['ROOM-L', '独立 L 形办公室待确认 · 4 件'], ['ROOM-B', '其他会议室待确认 · 1 件']])
  assert.equal(await page.getByRole('button', { name: '上一个空间', exact: true }).isDisabled(), true)
  await page.getByRole('button', { name: '折叠默认楼层空间', exact: true }).click()
  assert.equal(await spaceSelect.isVisible(), false)
  await page.reload({ waitUntil: 'networkidle' })
  assert.equal(await spaceSelect.isVisible(), false)
  await page.getByRole('button', { name: '展开默认楼层空间', exact: true }).click()
  assert.equal(await spaceSelect.locator('[data-space-id="ROOM-L"]').getAttribute('aria-current'), 'page')
  const initial = await state()
  assert.equal(await canvas().locator('polygon.board-element').count(), 1)
  assert.equal((await canvas().locator('polygon.board-element').getAttribute('points')).split(' ').length, 6, 'L 形不能变成包围盒')
  assert.equal(await canvas().locator('img').count(), 1, '显示与当前空间对齐的原始图纸')
  assert.deepEqual(await canvas().locator('.board-element').evaluateAll(nodes => nodes.map(node => node.dataset.id).sort()), ['F-L','F-L2','F-L3','F-L4','ROOM-L'])
  assert.equal(await canvas().locator('line[marker-end="url(#arrowHead)"]').count(), 0)
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
  await furniture.waitFor()
  const from = await furniture.boundingBox()
  const zoom = from.width / 1200
  const canvasBox = await canvas().boundingBox()
  const dragTargetX = from.x + from.width / 2 + 25 - canvasBox.x
  const dragTargetY = from.y + from.height / 2 + 15 - canvasBox.y
  const endX = canvasBox.x + dragTargetX
  const endY = canvasBox.y + dragTargetY
  const startX = from.x + from.width / 2
  const startY = from.y + from.height / 2
  await page.evaluate(({ startX, startY, endX, endY }) => {
    const f = document.querySelector('[data-id="F-L"]')
    const canvas = document.querySelector('[data-testid="office-design-canvas"]')
    if (!f || !canvas) throw new Error('missing drag elements')
    const make = (type, x, y) => new PointerEvent(type, {
      bubbles: true, cancelable: true, clientX: x, clientY: y,
      pointerId: 42, pointerType: 'mouse', button: 0, buttons: type === 'pointerup' ? 0 : 1, isPrimary: true,
    })
    f.dispatchEvent(make('pointerdown', startX, startY))
    for (let i = 1; i <= 5; i++) {
      const t = i / 5
      canvas.dispatchEvent(make('pointermove', startX + (endX - startX) * t, startY + (endY - startY) * t))
    }
    canvas.dispatchEvent(make('pointerup', endX, endY))
  }, { startX, startY, endX, endY })
  const moved = (await state()).objects.find(object => object.id === 'F-L')
  const original = initial.objects.find(object => object.id === 'F-L')
  assert.ok(Math.abs(moved.x - original.x - 25 / zoom) < 3, JSON.stringify({ moved, original, zoom, from }))
  assert.ok(Math.abs(moved.y - original.y - 15 / zoom) < 3)
  assert.equal(moved.zoneId, 'ROOM-L')

  // 底部家具栏回归：按 label+w+h 分组，单击切换全选，取消选择不打开换品
  const strip = page.getByTestId('canvas-furniture-strip')
  await strip.waitFor()
  const groups = strip.locator('> div > div')
  assert.equal(await groups.count(), 3, '底部栏按 label+w+h 显示 3 组')

  await groups.first().click()
  const selectedIds = await canvas().locator('.board-element[class*="ring-accent"]').evaluateAll(nodes => nodes.map(node => node.dataset.id).sort())
  assert.deepEqual(selectedIds, ['F-L', 'F-L2'])
  await groups.first().click()
  const deselectedIds = await canvas().locator('.board-element[class*="ring-accent"]').evaluateAll(nodes => nodes.map(node => node.dataset.id).sort())
  assert.deepEqual(deselectedIds, [])
  assert.equal(await page.getByTestId('white-model-product-dialog').count(), 0)

  // 组内碰撞：两件同时放大到互相重叠时整批不写回
  const collision = await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const ids = ['F-L', 'F-L2']
    const before = store.objects.filter(object => ids.includes(object.id)).map(object => ({ id: object.id, w: object.w, h: object.h }))
    const result = store.resizeFurnitureBatch(ids, 2800, 900)
    const after = store.objects.filter(object => ids.includes(object.id)).map(object => ({ id: object.id, w: object.w, h: object.h }))
    return { result, before, after }
  })
  assert.ok(collision.result, '组内碰撞必须返回错误')
  assert.deepEqual(collision.after, collision.before, '失败时整批不得修改尺寸')

  // 成功软提示：改尺寸后触发座椅侧净空复核警告，弹窗关闭且几何生效；临时家具用后删除，不影响后续确认
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const room = store.objects.find(object => object.id === 'ROOM-L')
    store.addObject({ id: 'F-SOFT', zoneId: 'ROOM-L', kind: 'furniture', x: room.x + 5000, y: room.y + 5500, w: 600, h: 300, rotation: 0, productId: '', label: '软提示桌', furnitureTemplateId: 'desk-straight' })
  })
  await canvas().locator('[data-id="F-SOFT"]').waitFor()
  await page.locator('[data-id="F-SOFT"]').click({ button: 'right' })
  const sizeDialog = page.getByTestId('furniture-size-dialog')
  await sizeDialog.waitFor()
  await sizeDialog.locator('input').first().fill('600')
  await sizeDialog.locator('input').nth(1).fill('400')
  await sizeDialog.getByRole('button', { name: '应用尺寸' }).click()
  await sizeDialog.waitFor({ state: 'detached', timeout: 5000 })
  await page.locator('p[role="status"]').filter({ hasText: '已调整尺寸；请复核' }).waitFor()
  const resized = (await state()).objects.find(object => object.id === 'F-SOFT')
  assert.equal(resized.h, 400)
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.deleteObject('F-SOFT')
  })
  await canvas().locator('[data-id="F-SOFT"]').waitFor({ state: 'detached', timeout: 5000 })

  // 未修改尺寸时只提示无变化，不写回几何，也不显示更新数量
  const beforeUnchanged = (await state()).objects.find(object => object.id === 'F-L3')
  await page.locator('[data-id="F-L3"]').click({ button: 'right' })
  await sizeDialog.waitFor()
  await sizeDialog.getByRole('button', { name: '应用尺寸' }).click()
  await sizeDialog.waitFor({ state: 'detached', timeout: 5000 })
  await page.locator('p[role="status"]').filter({ hasText: '尺寸未变化，无需更新' }).waitFor()
  assert.deepEqual((await state()).objects.find(object => object.id === 'F-L3'), beforeUnchanged)
  assert.equal(await page.getByText(/已更新 \d+ 个家具尺寸/).count(), 0)

  const expandButton = page.getByRole('button', { name: '展开侧边栏', exact: true }).first()
  if (await expandButton.count() > 0) await expandButton.click()
  await page.getByTestId('auto-confirm-recommend').click()
  const confirmedFurniture = (await state()).objects.filter(object => object.kind === 'furniture' && object.zoneId === 'ROOM-L')
  assert.ok(confirmedFurniture.every(object => object.layoutConfirmed))
  assert.match(await spaceSelect.locator('[data-space-id="ROOM-L"]').textContent(), /已确认 · 4 件/)

  // 返回设计模式验证批量换品只提交目标分组 ID
  await page.getByTestId('tab-product-selection').click()
  await page.getByTestId('canvas-furniture-strip').waitFor()
  await page.getByTestId('furniture-strip-item-F-L3').dblclick()
  const productDialog = page.getByTestId('white-model-product-dialog')
  await productDialog.waitFor()
  const currentProductName = await productDialog.locator('h3').textContent()
  await productDialog.locator('h3').hover()
  await page.mouse.wheel(0, 300)
  await page.waitForTimeout(100)
  assert.equal(await productDialog.locator('h3').textContent(), currentProductName, '滚轮不得切换当前商品')
  assert.equal(await productDialog.getByText('1 / 2', { exact: true }).count(), 1)
  await productDialog.getByRole('button', { name: '批量绑定该商品' }).click()
  await page.waitForFunction(() => !!window.lastModuleProductRequest, { timeout: 5000 })
  const bulkRequest = await page.evaluate(() => window.lastModuleProductRequest)
  assert.deepEqual(bulkRequest.targetIds, ['F-L3'], '批量换品只提交选中分组的家具 ID')
  await productDialog.waitFor({ state: 'detached', timeout: 5000 })

  // 侧栏收起时右键会议自动组主家具：自动展开并进入自动布局重排，不弹尺寸窗
  await page.getByRole('button', { name: '收起侧边栏', exact: true }).first().click()
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const room = store.objects.find(object => object.id === 'ROOM-L')
    store.addObject({
      id: 'F-MEET-AUTO', zoneId: 'ROOM-L', kind: 'furniture', x: room.x + 5400, y: room.y + 3500, w: 500, h: 800, rotation: 0,
      productId: '', label: '会议桌自动组', furnitureTemplateId: 'table-conference-rect', layoutRole: 'primary', layoutGroupId: 'auto:ROOM-L',
      layoutSettings: { mode: 'auto', spacing: { wallMm: 0, gapMm: 0, aisleMm: 0 }, layoutContext: 'meeting', productStyleId: 'meeting-table', arrangementPresetId: 'meeting-center' },
    })
  })
  await canvas().locator('[data-id="F-MEET-AUTO"]').waitFor()
  await page.locator('[data-id="F-MEET-AUTO"]').click({ button: 'right' })
  await page.getByRole('button', { name: '收起侧边栏', exact: true }).waitFor()
  assert.equal(await page.getByTestId('tab-auto-layout').getAttribute('aria-selected'), 'true')
  assert.equal(await page.getByTestId('furniture-size-dialog').count(), 0)
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.deleteObject('F-MEET-AUTO')
  })
  await canvas().locator('[data-id="F-MEET-AUTO"]').waitFor({ state: 'detached', timeout: 5000 })
  const reconfirmed = await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    return store.confirmLayout('ROOM-L')
  })
  assert.equal(reconfirmed, true, '删除临时会议桌后需恢复 ROOM-L 已确认状态')

  // Cross-space switch preserves confirmed layouts and does not carry state across rooms.
  const roomLFurniture = confirmedFurniture
  await spaceSelect.locator('[data-space-id="ROOM-B"]').click()
  await page.waitForURL(url => url.searchParams.get('zoneId') === 'ROOM-B')
  await assertFit('ROOM-B')
  assert.equal(await page.getByRole('button', { name: '下一个空间', exact: true }).isDisabled(), true)
  assert.deepEqual(await canvas().locator('.board-element').evaluateAll(nodes => nodes.map(node => node.dataset.id).sort()), ['F-B','ROOM-B'])
  assert.equal(await canvas().locator('img').count(), 1, '显示与当前空间对齐的原始图纸')
  await page.getByRole('button', { name: '上一个空间', exact: true }).click()
  await page.waitForURL(url => url.searchParams.get('zoneId') === 'ROOM-L')
  await assertFit('ROOM-L')
  assert.deepEqual((await state()).objects.filter(object => object.kind === 'furniture' && object.zoneId === 'ROOM-L'), roomLFurniture)
  await page.getByRole('button', { name: '下一个空间', exact: true }).click()
  await page.waitForURL(url => url.searchParams.get('zoneId') === 'ROOM-B')
  await assertFit('ROOM-B')
  await page.getByTestId('design-space-switcher').getByText('已确认排版 1 / 2 个空间', { exact: true }).waitFor()
  await page.screenshot({ path: outDir + '/02-switched-room.png' })
  await page.reload({ waitUntil: 'networkidle' })
  await assertFit('ROOM-B')
  assert.equal(await spaceSelect.locator('[data-space-id="ROOM-B"]').getAttribute('aria-current'), 'page')
  assert.match(await spaceSelect.locator('[data-space-id="ROOM-L"]').textContent(), /已确认 · 4 件/)
  assert.deepEqual(await canvas().locator('.board-element').evaluateAll(nodes => nodes.map(node => node.dataset.id).sort()), ['F-B','ROOM-B'])
  await page.getByRole('button', { name: '返回空间总览', exact: true }).first().click()
  await page.getByRole('img', { name: '空间核对画布', exact: true }).waitFor()
  assert.equal(await page.locator('[data-layer="design"]>g').count(), 2)
  assert.equal(await page.locator('[data-layer="underlay"] image').count(), 1)
  const final = await state()
  assert.equal(final.objects.length, initial.objects.length)
  assert.deepEqual(final.objects.filter(object => object.kind === 'zone'), initial.objects.filter(object => object.kind === 'zone'))
  assert.deepEqual(final.objects.find(object => object.id === 'F-B'), initial.objects.find(object => object.id === 'F-B'))
  assert.equal(final.floorPlan.previewUrl, source.previewUrl)
  assert.deepEqual(errors, [])
  console.log('单空间 UI 通过：房间/家具隔离、负坐标 L 形/远坐标适屏、缩放/拖动/局部标注、楼层树折叠及前后空间切换、其他空间独立自动排版、确认保留、放置取消、URL/刷新/返回总览及数据保留。全部 API 模拟。')
} catch (failure) {
  await debugPage?.screenshot({ path: outDir + '/failure.png' }).catch(() => {})
  throw failure
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
