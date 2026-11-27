import assert from 'node:assert/strict'
import { chromium } from 'playwright'
import { createReviewConfirmMock, fulfillReviewConfirm, confirmAllReviews } from './helpers/review-confirm-mock.mjs'
import { mkdir } from 'node:fs/promises'

const baseUrl = process.env.BASE_URL || 'http://localhost:3001'
const outDir = process.env.OUT_DIR || './screenshots'

await mkdir(outDir, { recursive: true })

const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
if (process.env.MOCK_API === '1') {
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  const reviewMock = createReviewConfirmMock()
  const defaultProject = {
    projectId: 'OFFICE-PROJECT-001', name: '默认办公项目', customerRequirements: '', requirementsRevision: 1,
    leader: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'LEADER', permissions: ['READ', 'EDIT'] }, collaborators: [], referenceImages: [],
    floors: [{ projectId: 'OFFICE-PROJECT-001', floorId: 'OFFICE-PROJECT-001', name: '默认楼层', sortOrder: 1 }],
  }
  let savedCanvas = null
  let cart = []
  const product = { id: 'SMOKE-DESK', rspuId: 'SMOKE-DESK-PRODUCT', name: '验证办公桌', variant: '标准规格', price: 300 }
  await context.route('**/api/v1/**', route => {
    if (fulfillReviewConfirm(reviewMock, route)) return
    const request = route.request()
    const { pathname } = new URL(request.url())
    const respond = data => route.fulfill({ json: { code: 200, message: '成功', data } })
    if (pathname === '/api/v1/office/projects' && request.method() === 'GET') return respond([defaultProject])
    if (pathname === '/api/v1/office/products') return respond([product])
    if (pathname.endsWith('/canvas')) {
      if (request.method() === 'PUT') savedCanvas = request.postDataJSON()
      return respond(savedCanvas)
    }
    if (pathname.endsWith('/cart')) return respond(cart)
    if (pathname.endsWith('/module-recommendations') || pathname.endsWith('/module-products')) {
      const { zoneId, moduleId, canvasRevision, applyToSameModules } = request.postDataJSON()
      const selected = savedCanvas.objects.find(item => item.id === moduleId && item.zoneId === zoneId)
      assert.ok(selected?.layoutConfirmed)
      assert.equal(canvasRevision, savedCanvas.revision)
      const sameModules = savedCanvas.objects.filter(item => item.kind === 'furniture' && item.zoneId === zoneId
        && item.furnitureTemplateId === selected.furnitureTemplateId && item.w === selected.w && item.h === selected.h)
      const match = { product: { ...product, widthMm: selected.w, depthMm: selected.h },
        rotationDeg: 0, fitRatio: 1, remainingWidthMm: 0, remainingDepthMm: 0, tier: 'CLOSE' }
      if (pathname.endsWith('/module-recommendations')) {
        return respond({ zoneId, moduleId, canvasRevision, widthMm: selected.w, depthMm: selected.h,
          targetCategory: '办公桌', sameModuleCount: sameModules.length, candidates: [match], eligibleVariantCount: 1 })
      }
      const cartItems = (applyToSameModules ? sameModules : [selected]).map(item => {
        const lineId = 'line-' + item.id
        item.productId = product.id
        item.productSelection = { ...match, cartLineId: lineId, sourceCanvasRevision: canvasRevision }
        return { lineId, projectId: 'OFFICE-PROJECT-001', zoneId, zoneName: '验证空间', productId: product.id, qty: 1 }
      })
      cart = [...cart.filter(line => !cartItems.some(item => item.lineId === line.lineId)), ...cartItems]
      savedCanvas.revision++
      return respond({ canvas: savedCanvas, cartItems, match })
    }
    return respond(pathname.endsWith('/OFFICE-PROJECT-001') ? null : [])
  })
}
const page = await context.newPage()

async function shot(name) {
  const file = `${outDir}/${name}.png`
  await page.screenshot({ path: file, fullPage: false })
  console.log(`  screenshot: ${file}`)
}

// 1. 上传页
console.log('Step 1: upload page')
await page.goto(`${baseUrl}/`, { waitUntil: 'networkidle' })
await page.getByRole('heading', { name: /上传办公图纸$/ }).waitFor({ timeout: 10000 })
await shot('01-upload')

// 2. 空间总览
console.log('Step 2: overview')
await page.goto(`${baseUrl}/canvas`, { waitUntil: 'networkidle' })
await page.getByRole('img', { name: '空间核对画布', exact: true }).waitFor({ timeout: 10000 })
await page.getByRole('button', { name: '加载工装示例（可撤销）', exact: true }).click()
await shot('02-overview')

if (process.env.MOCK_API === '1') {
  const canvas = page.getByRole('img', { name: '空间核对画布', exact: true })
  const outside = page.locator('.contour-edit-bar')
  const selectHint = page.getByRole('button', { name: '选择 / 缩放', exact: true }).locator('.review-tool-shortcut')
  const panHint = page.getByRole('button', { name: '拖动画布', exact: true }).locator('.review-tool-shortcut')
  assert.equal(await selectHint.innerText(), '左拖 · 滚轮')
  assert.match(await panHint.innerText(), /空格＋左拖 · 中键$/)
  const label = canvas.locator('.space-label-editable')
  const paths = canvas.locator('[data-layer="design"] > [data-space-id] > path')
  const storedZones = () => page.evaluate(() => {
    const key = Object.keys(localStorage).find(key => key.startsWith('rsdp-office-v01:') && key.endsWith(':OFFICE-PROJECT-001'))
      || 'rsdp-office-v01'
    return JSON.parse(localStorage.getItem(key)).objects.filter(item => item.kind === 'zone')
      .map(({ id, polygon }) => ({ id, polygon }))
  })
  const zones = await storedZones()
  const bounds = () => paths.evaluateAll(items => items.map(item => {
    const { x, y, width, height } = item.getBBox()
    return { x, y, width, height }
  }))
  const assertShift = async (before, dx, dy) => {
    const after = await bounds()
    assert.equal(after.length, before.length)
    for (const [index, a] of after.entries()) {
      const b = before[index]
      assert.ok(Math.abs(a.x - b.x - dx) < 0.1 && Math.abs(a.y - b.y - dy) < 0.1
        && Math.abs(a.width - b.width) < 0.1 && Math.abs(a.height - b.height) < 0.1,
      JSON.stringify({ a, b, dx, dy }))
    }
  }
  const rightEventsProceed = target => target.evaluate(el => ['mousedown', 'contextmenu', 'auxclick'].map(type =>
    el.dispatchEvent(new MouseEvent(type, { button: 2, bubbles: true, cancelable: true }))))
  assert.deepEqual(await rightEventsProceed(canvas), [true, true, true], '画布内保留右键事件')
  assert.deepEqual(await rightEventsProceed(outside), [true, true, true], '画布外保留右键事件')

  const labelBox = await label.boundingBox()
  const outsideBox = await outside.boundingBox()
  const start = { x: labelBox.x + labelBox.width / 2, y: labelBox.y + labelBox.height / 2 }
  const end = { x: start.x + 45, y: outsideBox.y + outsideBox.height / 2 }
  const beforeRight = await bounds()
  await page.mouse.move(start.x, start.y)
  await page.mouse.down({ button: 'right' })
  await page.mouse.move(start.x + 32, start.y + 20, { steps: 5 })
  await page.mouse.up({ button: 'right' })
  await page.keyboard.press('Escape')
  await assertShift(beforeRight, 0, 0)

  const beforeMiddle = await bounds()
  await page.mouse.move(start.x, start.y)
  await page.mouse.down({ button: 'middle' })
  await page.mouse.move(end.x, end.y, { steps: 8 })
  await page.mouse.up({ button: 'middle' })
  await assertShift(beforeMiddle, end.x - start.x, end.y - start.y)
  assert.ok((await canvas.getAttribute('class')).includes('cursor-default'), '在画布外松手也会结束临时平移')
  assert.ok(await canvas.evaluate(el => el === document.activeElement))
  const outsideKeyProceeds = await canvas.evaluate(el => el.dispatchEvent(new KeyboardEvent('keydown', {
    key: ' ', code: 'Space', bubbles: true, cancelable: true,
  })))
  assert.ok(outsideKeyProceeds, '鼠标移出画布后，画布仍有焦点也不会拦截空格')

  const fit = page.getByRole('button', { name: '适屏', exact: true })
  await fit.click()
  const canvasBox = await canvas.boundingBox()
  await page.mouse.click(canvasBox.x + 20, canvasBox.y + canvasBox.height / 2)
  assert.ok(await canvas.evaluate(el => el === document.activeElement), '点击空白画布即可聚焦')
  const beforeSpace = await bounds()
  await label.hover()
  const focusedLabel = await label.boundingBox()
  await page.keyboard.down('Space')
  await page.mouse.down()
  await page.mouse.move(focusedLabel.x + focusedLabel.width / 2 + 48,
    focusedLabel.y + focusedLabel.height / 2 + 26, { steps: 6 })
  await page.keyboard.up('Space')
  await page.mouse.up()
  await assertShift(beforeSpace, 48, 26)
  assert.equal(await page.getByTestId('label-inline').count(), 0, '从空间标签开始平移不会打开编辑')
  assert.deepEqual(await storedZones(), zones, '中键和空格平移均不写入空间几何')
  await fit.click()

  const moveFirstSpace = async (dx, dy) => {
    const room = await paths.first().boundingBox()
    const start = { x: room.x + room.width / 6, y: room.y + room.height / 5 }
    await page.mouse.move(start.x, start.y)
    await page.mouse.down()
    await page.mouse.move(start.x + dx, start.y + dy, { steps: 5 })
    await page.mouse.up()
    return storedZones()
  }
  const undo = page.getByRole('button', { name: '撤销', exact: true })
  const redo = page.getByRole('button', { name: '恢复', exact: true })
  assert.ok(await redo.isDisabled(), '撤销前没有可恢复的操作')
  const moved = await moveFirstSpace(-18, 12)
  assert.notDeepEqual(moved[0].polygon, zones[0].polygon, '松开空格后恢复左键移动空间')
  assert.deepEqual(moved.slice(1), zones.slice(1), '拖动只修改目标空间')
  const movedAgain = await moveFirstSpace(-12, -9)
  assert.notDeepEqual(movedAgain, moved)
  await undo.click()
  assert.deepEqual(await storedZones(), moved)
  await undo.click()
  assert.deepEqual(await storedZones(), zones)
  await redo.click()
  assert.deepEqual(await storedZones(), moved, '恢复最早撤销的编辑')
  await redo.click()
  assert.deepEqual(await storedZones(), movedAgain, '连续恢复按原顺序应用')
  assert.ok(await redo.isDisabled(), '恢复完成后按钮禁用')
  await undo.click()
  assert.ok(await redo.isEnabled())
  const newEdit = await moveFirstSpace(-8, 16)
  assert.notDeepEqual(newEdit, moved)
  assert.notDeepEqual(newEdit, movedAgain)
  assert.ok(await redo.isDisabled(), '撤销后的新编辑清空旧恢复记录')
  await undo.click()
  assert.deepEqual(await storedZones(), moved)
  await undo.click()
  assert.deepEqual(await storedZones(), zones)
  console.log('  canvas gestures and history: pan, right-click defaults, undo, redo and new edit')
}

// 3. 进入单空间设计（先完成全部空间复核确认）
console.log('Step 3: enter design')
await confirmAllReviews(page)
await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()
await page.getByTestId('product-scheme').waitFor()
await page.getByTestId('tab-auto-layout').click()
await page.getByTestId('functional-category-workstation').click()
await page.waitForURL(/mode=design/, { timeout: 10000 })
await page.getByTestId('apply-layout').waitFor({ timeout: 10000 })
await shot('03-design')

// 4. 选择单位模块、一键排版、确认排版并加入方案
console.log('Step 4: select module, layout and add to cart')
await page.getByLabel('自动布局家具模块').selectOption('desk-straight')
await page.waitForTimeout(200)
await page.getByTestId('apply-layout').click()
await page.waitForTimeout(500)
const furnitureGroup = page.getByTestId('canvas-furniture-strip').locator('[data-testid^="furniture-strip-item-"]').first()
await furnitureGroup.click({ button: 'right' })
const resize = page.getByRole('dialog', { name: '修改尺寸', exact: true })
await resize.getByRole('button', { name: '关闭', exact: true }).click()
await furnitureGroup.dblclick()
const bulkReplace = page.getByRole('dialog', { name: '批量替换商品', exact: true })
await bulkReplace.getByTestId('white-model-product-blocked').waitFor()
await bulkReplace.getByRole('button', { name: '关闭替换商品', exact: true }).click()
await page.getByTestId('auto-confirm-recommend').click()
await page.waitForTimeout(300)
await shot('04-design-layout-confirmed')
await page.getByRole('combobox', { name: '匹配单位模块' }).selectOption({ index: 1 })
const applyProduct = page.getByRole('button', { name: '应用到当前家具', exact: true })
await applyProduct.waitFor()
const syncProducts = page.getByRole('checkbox', { name: /同步 \d+ 个同规格模块/ })
if (await syncProducts.count()) await syncProducts.check()
const selectedProduct = page.waitForResponse(response => response.url().endsWith('/module-products'))
await page.getByRole('button', { name: /应用到(?:当前家具| \d+ 个模块)/ }).click()
assert.equal((await (await selectedProduct).json()).code, 200)
await shot('04-design-with-cart')

// 5. 整体方案
console.log('Step 5: proposal')
await page.getByRole('link', { name: '去项目清单确认', exact: true }).click()
if (process.env.MOCK_API === '1') {
  await page.waitForURL(url => url.pathname === '/proposal')
  await page.getByRole('heading', { name: '整体方案与清单' }).waitFor()
  assert.equal(await page.getByRole('dialog').count(), 0, '项目清单入口直接打开独立页面')
  // 用演示清单核对有稳定名称的房间和行内详情。
  const previewUrl = new URL('/proposal', baseUrl)
  previewUrl.searchParams.set('preview', '1')
  const floorId = new URL(page.url()).searchParams.get('floorId')
  if (floorId) previewUrl.searchParams.set('floorId', floorId)
  await page.goto(previewUrl.toString(), { waitUntil: 'networkidle' })
  await page.getByText('方案演示', { exact: true }).waitFor()
  const firstRoom = page.getByRole('button', { name: /^总裁办公室/ })
  const secondRoom = page.getByRole('button', { name: /^开放办公区/ })
  const firstDetail = page.getByRole('region', { name: '总裁办公室详情' })
  const secondDetail = page.getByRole('region', { name: '开放办公区详情' })
  await firstDetail.waitFor()
  if (!await firstRoom.evaluate(row => row.nextElementSibling?.id === row.getAttribute('aria-controls'))) {
    throw new Error('首个房间的详情必须紧跟房间行')
  }
  await secondRoom.click()
  await secondDetail.waitFor()
  await firstDetail.waitFor({ state: 'hidden' })
  if (!await secondRoom.evaluate(row => row.nextElementSibling?.id === row.getAttribute('aria-controls'))) {
    throw new Error('切换后的详情必须紧跟新的房间行')
  }
  await secondRoom.click()
  await secondDetail.waitFor({ state: 'hidden' })
  console.log('  proposal opens directly; room details expand in place')
}
await page.waitForURL(url => url.pathname === '/proposal', { timeout: 10000 })
await page.waitForSelector('h1:has-text("整体方案与清单")', { timeout: 10000 })
await shot('05-proposal')

// 6. 调整数量
console.log('Step 6: change qty')
const plus = page.locator('table tbody tr').first().locator('button').nth(1)
if (await plus.count()) {
  await plus.click()
  await page.waitForTimeout(200)
  await shot('06-proposal-qty')
}

await browser.close()
console.log('E2E smoke passed')
