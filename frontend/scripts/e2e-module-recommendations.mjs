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
const app = spawn(process.execPath, ['.output/server/index.mjs'], { cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1' }, stdio: 'ignore' })
const outDir = process.env.OUT_DIR || '/tmp/rsdp-module-recommendations'
let browser, page
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先构建，预览服务未就绪')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  await mkdir(outDir, { recursive: true })
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1800, height: 1150 } })
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  page = await context.newPage()
  const errors = []
  const runtimeErrors = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => { if (message.type() === 'error') runtimeErrors.push(message.text()) })
  let saved = null, empty = false, fail = false, unavailable = false, delayA = false, noCatalog = false
  let waitForSelection
  const selections = [], queries = []
  const products = [
    ['EXACT', '精确尺寸办公桌', 1200, 600, 1000, '暮山黑橡+深灰银钢脚+灯带'],
    ['MEDIUM', '宽松办公桌', 1000, 500, 800, '蜜芽胡桃+雅士白'],
    ['SMALL', '小尺寸边桌', 600, 300, 400, '月影胡桃+海鸥灰'],
    ['ROTATED', '旋转适配长桌', 550, 1150, 900, '雾山核桃+液态银'],
    ['QUOTE', '待报价办公桌', 1100, 500, 0, '雅士白+爱马仕橙'],
  ].map(([id, name, widthMm, depthMm, price, colorPrimaryName]) => ({ id, rspuId: 'MASTER-' + id, variantId: id, name, colorPrimaryName, widthMm, depthMm, price, widthCm: widthMm / 10, depthCm: depthMm / 10, desc: '', variant: '真实规格 API 模拟', kind: 'other', uses: ['OTHER'] }))
  products.push(...Array.from({ length: 30 }, (_, index) => ({
    id: `FILL-${index}`, rspuId: `MASTER-FILL-${index}`, variantId: `FILL-${index}`, name: `滚动候选 ${index + 1}`,
    widthMm: 1190, depthMm: 590, price: 100 + index, widthCm: 119, depthCm: 59, desc: '', variant: '滚动回归模拟', kind: 'other', uses: ['OTHER'],
  })))
  await context.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    const respond = (data, status = 200, message = '成功') => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status, message, data }) })
    if (path === '/api/v1/office/projects' && route.request().method() === 'GET') return respond([{
      projectId: 'OFFICE-PROJECT-001', name: '推荐测试项目', customerRequirements: '', requirementsRevision: 1,
      leader: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'LEADER' }, collaborators: [], referenceImages: [],
      floors: [{ projectId: 'OFFICE-PROJECT-001', floorId: 'OFFICE-PROJECT-001', name: '默认楼层', sortOrder: 1 }],
    }])
    if (path.endsWith('/canvas')) {
      if (route.request().method() === 'PUT') { saved = route.request().postDataJSON(); return respond(saved) }
      return respond(saved)
    }
    if (path.endsWith('/module-recommendations') || path.endsWith('/module-products')) {
      const body = route.request().postDataJSON()
      const current = saved.objects.find(item => item.id === body.moduleId)
      assert.equal(body.canvasRevision, saved.revision)
      assert.ok(current)
      const candidates = products.flatMap(product => {
        const options = [[product.widthMm, product.depthMm, 0], ...(body.allowRotation ? [[product.depthMm, product.widthMm, 90]] : [])]
          .filter(([w, d]) => w <= current.w && d <= current.h)
        if (!options.length) return []
        options.sort((a, b) => Math.min(b[0] / current.w, b[1] / current.h) - Math.min(a[0] / current.w, a[1] / current.h))
        const [w, d, rotationDeg] = options[0], fitRatio = Math.min(w / current.w, d / current.h)
        const tagOnly = product.id === 'ROTATED'
        return [{ product, rotationDeg, fitRatio, relevanceScore: (tagOnly ? 32 : 80) + fitRatio * 15,
          matchReason: tagOnly ? '六维标签相关 · 桌类' : '同品类 · 商品名称或规格名',
          remainingWidthMm: current.w - w, remainingDepthMm: current.h - d, tier: fitRatio >= .85 ? 'CLOSE' : fitRatio >= .6 ? 'RELAXED' : 'SMALL' }]
      }).sort((a, b) => b.relevanceScore - a.relevanceScore)
      if (path.endsWith('/module-products')) {
        selections.push(body)
        if (waitForSelection) await waitForSelection
        if (unavailable) return respond(null, 400, '商品已下架，请重新选择')
        const match = candidates.find(item => item.product.id === body.productId)
        assert.ok(match)
        const targets = saved.objects.filter(item => item.kind === 'furniture' && item.zoneId === body.zoneId && (item.id === body.moduleId || body.applyToSameModules && item.w === current.w && item.h === current.h && item.furnitureTemplateId === current.furnitureTemplateId))
        const cartItems = targets.map(item => {
          const lineId = 'module-' + item.id
          item.productId = body.productId
          item.productSelection = { ...match, cartLineId: lineId, sourceCanvasRevision: saved.revision }
          return { lineId, projectId: 'OFFICE-PROJECT-001', zoneId: body.zoneId, zoneName: '推荐测试空间', productId: body.productId, qty: 1 }
        })
        saved.revision++
        return respond({ canvas: saved, cartItems, match })
      }
      queries.push(body)
      const response = { zoneId: body.zoneId, moduleId: body.moduleId, canvasRevision: body.canvasRevision, widthMm: current.w, depthMm: current.h, targetCategory: '办公桌', sameModuleCount: current.id === 'C' ? 1 : 2, missingDimensions: noCatalog ? 0 : 1, eligibleVariantCount: noCatalog ? 0 : products.length + 1, candidates: empty || noCatalog ? [] : candidates }
      if (delayA && body.moduleId === 'A' && !body.allowRotation) await new Promise(resolve => setTimeout(resolve, 900))
      if (fail) return respond(null, 503, '推荐服务暂不可用')
      return respond(response)
    }
    return respond(path.endsWith('/OFFICE-PROJECT-001') ? null : [])
  })
  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.evaluate(() => {
    const state = JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001') || '{}')
    state.objects = [
      { id: 'Z', kind: 'zone', x: 0, y: 0, w: 6000, h: 4000, name: '推荐测试空间', zoneType: 'OPEN_OFFICE', seats: 0, note: '', tone: 'blue', reviewUnit: 'mm', reviewConfirmation: { status: 'CONFIRMED' }, evidenceKind: 'MANUAL', geometrySource: 'manual_geometry', polygon: [[0,0],[6000,0],[6000,4000],[0,4000]] },
      ...['A', 'B'].map((id, index) => ({ id, kind: 'furniture', zoneId: 'Z', furnitureTemplateId: 'desk-straight', label: '共享职员桌', x: 500 + index * 2000, y: 500, w: 1200, h: 600, rotation: 0, productId: '', layoutConfirmed: true })),
      { id: 'C', kind: 'furniture', zoneId: 'Z', furnitureTemplateId: 'desk-straight', label: '小桌 C', x: 500, y: 2300, w: 700, h: 400, rotation: 0, productId: '', layoutConfirmed: true },
      { id: 'OTHER', kind: 'furniture', zoneId: 'OTHER', furnitureTemplateId: 'chair-office', x: 10000, y: 0, w: 650, h: 650, rotation: 0, productId: 'KEEP', layoutConfirmed: true },
    ]
    state.canvasRevision = 9; state.canvasMode = 'design'; state.designZoneId = 'Z'; state.cart = []; state.floorPlan = null; state.spaceTemplate = null
    localStorage.setItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001', JSON.stringify(state))
  })
  await page.goto(baseUrl + '/canvas?mode=design&zoneId=Z', { waitUntil: 'networkidle' })
  await page.getByLabel('布局房间类型').selectOption('MEETING_ROOM')
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects.find(item => item.id === 'Z').zoneType), 'MEETING_ROOM')
  await page.getByTestId('tab-product-selection').click()
  const furniture = id => page.getByTestId('office-design-canvas').locator(`[data-id="${id}"]`)
  assert.equal(await page.locator('[data-furniture-id]').count(), 2, '名称与尺寸完全相同的 A/B 在已排家具列表中只显示一个代表项')
  assert.equal(await page.locator('[data-furniture-id="A"]').count(), 1)
  assert.equal(await page.locator('[data-furniture-id="B"]').count(), 0)
  await furniture('B').click()
  await page.getByTestId('product-item-editor').waitFor()
  assert.equal(await page.locator('[data-furniture-id="A"]').getAttribute('aria-pressed'), 'true', '选中隐藏的同组实例时代表项保持高亮')
  await page.getByTestId('product-copy-furniture').click()
  const copied = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects.find(item => item.kind === 'furniture' && item.zoneId === 'Z' && !['A', 'B', 'C'].includes(item.id)))
  assert.deepEqual([copied.x, copied.y], [2700, 700], '合并列表后编辑器仍作用于画布实际选中的 B 实例')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await page.locator('[data-furniture-id="A"]').click()
  await page.getByTestId('product-copy-furniture').click()
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects.filter(item => item.kind === 'furniture' && item.zoneId === 'Z').length), 4)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects.filter(item => item.kind === 'furniture' && item.zoneId === 'Z').length), 3)
  await page.locator('[data-furniture-id="A"]').click()
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.objects.find(item => item.id === 'A').productId = 'OLD-MODULE-PRODUCT'
    store.cart = [{ id: 'OLD-MODULE-LINE', productId: 'OLD-MODULE-PRODUCT', zoneId: 'Z', zoneName: '推荐测试空间', qty: 1 }]
    store.saveLocal()
  })
  const beforeReplace = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')))
  const staffCatalog = page.getByTestId('unit-module-catalog').locator('details').filter({ hasText: '职员桌' })
  if (!(await staffCatalog.getAttribute('open'))) await staffCatalog.locator('summary').click()
  await page.getByTestId('unit-module-catalog').locator('[data-module-id="desk-l"]').click()
  await page.getByRole('status').filter({ hasText: '已替换为' }).waitFor()
  const replaced = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')))
  const oldA = beforeReplace.objects.find(item => item.id === 'A'), newA = replaced.objects.find(item => item.id === 'A')
  assert.equal(newA.furnitureTemplateId, 'desk-l')
  assert.equal(newA.x + newA.w / 2, oldA.x + oldA.w / 2, 'replacement preserves the furniture center')
  assert.equal(newA.y + newA.h / 2, oldA.y + oldA.h / 2)
  assert.equal(newA.rotation, oldA.rotation)
  assert.equal(newA.productId, '')
  assert.ok(replaced.objects.filter(item => item.kind === 'furniture' && item.zoneId === 'Z').every(item => !item.layoutConfirmed))
  assert.equal(replaced.cart.length, 0, 'replacement removes the old product selection from the cart')
  assert.equal(await page.getByTestId('tab-product-selection').getAttribute('aria-selected'), 'true', 'manual replacement stays in product selection')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  const restored = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')))
  assert.deepEqual(restored.objects, beforeReplace.objects, 'one undo restores module, geometry, confirmation and old product')
  assert.deepEqual(restored.cart, beforeReplace.cart)
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.objects.find(item => item.id === 'A').productId = ''
    store.cart = []
    store.saveLocal()
  })
  await page.locator('[data-furniture-id="A"]').click()
  await page.getByTestId('product-item-width').fill('1250')
  await page.getByTestId('product-item-resize').click()
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects.find(item => item.id === 'A').w), 1250)
  await page.getByTestId('product-item-width').fill('1200')
  await page.getByTestId('product-item-resize').click()
  await page.screenshot({ path: outDir + '/00-product-layout-editor.png', animations: 'disabled' })
  await page.getByRole('button', { name: '确认当前排版', exact: true }).click()
  const panel = page.getByTestId('module-product-recommendations')
  const card = id => panel.locator('[data-product-id="' + id + '"]')
  const matchingModule = panel.getByLabel('匹配单位模块')
  await card('EXACT').waitFor()
  assert.equal(await matchingModule.inputValue(), 'A', '进入推荐时使用画布已选的家具')
  await page.keyboard.press('Escape')
  await panel.getByText('点击画布上的家具，或从上方列表选择。').waitFor()
  assert.equal(await card('EXACT').count(), 0, '清除画布选择时不保留旧候选')
  assert.equal(await panel.getByTestId('color-search').isDisabled(), true)
  assert.equal(await panel.getByTestId('color-filter').isDisabled(), true)
  await furniture('A').click()
  await furniture('B').click({ modifiers: ['Shift'] })
  await furniture('B').click()
  await card('MEDIUM').waitFor()
  assert.equal(await page.getByTestId('tab-style-recommendation').getAttribute('aria-selected'), 'true', '点击画布家具时留在款式推荐')
  assert.equal(await matchingModule.inputValue(), 'B', '规格相同也要定位到实际点中的实例')
  assert.equal(queries.at(-1).moduleId, 'B')
  assert.equal(await panel.getByLabel('同步 2 个同规格模块').isChecked(), false)
  let releaseSelection
  waitForSelection = new Promise(resolve => { releaseSelection = resolve })
  const saving = page.waitForRequest(request => request.url().endsWith('/module-products'))
  await card('MEDIUM').getByRole('button', { name: '应用到当前家具' }).click()
  await saving
  await furniture('C').click()
  await panel.getByText('正在保存刚才的商品选择…').waitFor()
  assert.equal(await matchingModule.inputValue(), 'C')
  assert.equal(await card('MEDIUM').count(), 0, '切换时不展示上一件家具的旧候选')
  releaseSelection()
  waitForSelection = undefined
  await card('SMALL').waitFor()
  assert.equal(queries.at(-1).moduleId, 'C')
  assert.equal(selections[0].moduleId, 'B')
  assert.equal(selections[0].applyToSameModules, false)
  const single = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')))
  assert.equal(single.objects.find(item => item.id === 'A').productId, '')
  assert.equal(single.objects.find(item => item.id === 'B').productId, 'MEDIUM')
  assert.equal(single.cart.length, 1)
  const selectedOptions = await matchingModule.locator('option').allTextContents()
  assert.ok(selectedOptions.some(text => text.includes('共享职员桌') && text.includes('1,200 × 600 mm') && text.includes('已选 1/2') && !text.includes('已选择')), '同规格模块只选一件时，下拉项显示已选数量')
  assert.ok(selectedOptions.some(text => text.includes('小桌 C') && !text.includes('已选择')), '不同名称或尺寸的下拉项不应标记为已选择')
  const legacyBefore = await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const item = store.objects.find(item => item.id === 'A')
    return { productId: item.productId, productSelection: item.productSelection ? JSON.parse(JSON.stringify(item.productSelection)) : null, cart: JSON.parse(JSON.stringify(store.cart)) }
  })
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const legacy = store.objects.find(item => item.id === 'A')
    legacy.productId = 'MEDIUM'
    legacy.productSelection = { product: null, cartLineId: 'LEGACY-LINE', sourceCanvasRevision: store.canvasRevision }
    store.cart = [{ id: 'LEGACY-LINE', productId: 'MEDIUM', zoneId: 'Z', zoneName: '推荐测试空间', qty: 1 }]
    store.saveLocal()
  })
  await matchingModule.selectOption('A')
  await card('EXACT').waitFor()
  const legacyOption = await matchingModule.locator('option:checked').textContent()
  assert.match(legacyOption || '', /已选 1\/2/, '旧快照商品详情为空时，有效购物车行仍应计入已选数量')
  assert.deepEqual(errors, [], `旧快照 product=null 不应触发页面异常：${errors.join('; ')}`)
  assert.ok(!runtimeErrors.some(error => error.includes("Cannot read properties of null")), `旧快照 product=null 不应读取商品详情：${runtimeErrors.join('; ')}`)
  await page.evaluate(previous => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const item = store.objects.find(item => item.id === 'A')
    item.productId = previous.productId
    item.productSelection = previous.productSelection
    store.cart = previous.cart
    store.saveLocal()
  }, legacyBefore)
  await matchingModule.selectOption('A')
  await card('EXACT').waitFor()
  assert.equal(await furniture('A').locator('.manual-furniture-glyph.selected').count(), 1)
  await page.setViewportSize({ width: 1600, height: 900 })
  const recommendationList = panel.locator('div.flex-1.overflow-auto')
  await page.waitForTimeout(100)
  const scrollMetrics = await recommendationList.evaluate(element => ({ clientHeight: element.clientHeight, scrollHeight: element.scrollHeight }))
  assert.ok(scrollMetrics.clientHeight > 0 && scrollMetrics.clientHeight < 900, '推荐列表必须受侧栏剩余高度约束')
  assert.ok(scrollMetrics.scrollHeight > scrollMetrics.clientHeight, '多候选时应形成列表内部滚动区域')
  await recommendationList.hover()
  await page.mouse.wheel(0, 600)
  await page.waitForTimeout(200)
  assert.ok(await recommendationList.evaluate(element => element.scrollTop > 0), '滚轮必须实际滚动推荐列表')
  await recommendationList.evaluate(element => { element.scrollTop = 0 })

  // LAYOUT-044：矮视口（等价于浏览器高倍缩放）下的面板滚动兜底
  // 应用外壳约定最小有效高度 600px（app.vue min-h-[600px]），此处用 620px 验证降级行为
  const tierTops = await page.getByTestId('tier-row').locator('button').evaluateAll(buttons => buttons.map(button => button.offsetTop))
  assert.equal(new Set(tierTops).size, 1, '四个尺寸档位按钮必须位于同一行')
  await page.setViewportSize({ width: 1280, height: 620 })
  await page.waitForTimeout(200)
  assert.ok(await recommendationList.evaluate(element => element.clientHeight) >= 160, '矮视口下推荐列表必须保留至少 160px 保底高度')
  const proposalBox = await page.getByRole('link', { name: /生成整体方案/ }).boundingBox()
  assert.ok(proposalBox && proposalBox.y >= 0 && proposalBox.y + proposalBox.height <= 621, '结算条必须在矮视口内完整可见（钉底）')
  const fallback = page.getByTestId('sidebar-fallback-scroll')
  const fallbackMetrics = await fallback.evaluate(element => ({ clientHeight: element.clientHeight, scrollHeight: element.scrollHeight }))
  assert.ok(fallbackMetrics.scrollHeight > fallbackMetrics.clientHeight, '矮视口下兜底滚动层必须可滚动以触达被挤出的筛选区')
  await fallback.evaluate(element => { element.scrollTop = 120 })
  assert.ok(await fallback.evaluate(element => element.scrollTop > 0), '兜底滚动层必须实际可滚动')
  await page.screenshot({ path: outDir + '/03-narrow-viewport-fallback.png', animations: 'disabled' })
  await page.setViewportSize({ width: 1600, height: 900 })
  await page.waitForTimeout(200)
  assert.equal(await card('ROTATED').count(), 1)
  assert.match(await card('ROTATED').innerText(), /需旋转 90°/)
  await panel.getByText('推荐品类：办公桌').waitFor()
  assert.match(await card('ROTATED').getByTestId('product-relevance').innerText(), /适配度 .*六维标签相关/)
  const rankedIds = await panel.locator('[data-product-id]').evaluateAll(cards => cards.map(item => item.dataset.productId))
  assert.ok(rankedIds.indexOf('SMALL') < rankedIds.indexOf('ROTATED'), '界面保留服务端的综合分顺序')
  assert.equal(await card('QUOTE').getByRole('button', { name: '待补报价' }).isDisabled(), true)
  assert.equal(await panel.getByText('四人工位组合', { exact: true }).count(), 0)

  const colorSearch = panel.getByTestId('color-search')
  const colorFilter = panel.getByTestId('color-filter')
  const visibleIds = () => panel.locator('[data-product-id]').evaluateAll(cards => cards.map(item => item.dataset.productId))
  const queryCount = queries.length
  await colorFilter.selectOption('TONE:DARK')
  assert.deepEqual(new Set(await visibleIds()), new Set(['EXACT', 'SMALL', 'ROTATED']))
  assert.equal(await card('MEDIUM').count(), 0)
  assert.match(await card('EXACT').getByTestId('product-color').innerText(), /深色/)
  await page.screenshot({ path: outDir + '/01-color-dark-filter.png', animations: 'disabled' })
  await colorFilter.selectOption('TONE:LIGHT')
  assert.deepEqual(new Set(await visibleIds()), new Set(['MEDIUM', 'QUOTE']))
  await colorFilter.selectOption('PRIMARY:月影胡桃')
  assert.deepEqual(await visibleIds(), ['SMALL'])
  await colorFilter.selectOption('ALL')
  await colorSearch.fill('胡桃')
  assert.deepEqual(new Set(await visibleIds()), new Set(['MEDIUM', 'SMALL', 'ROTATED']))
  await colorSearch.fill(' 胡　桃 ')
  assert.deepEqual(new Set(await visibleIds()), new Set(['MEDIUM', 'SMALL', 'ROTATED']), '搜索忽略首尾、半角和全角空格')
  await colorSearch.fill('api')
  assert.deepEqual(new Set(await visibleIds()), new Set(['EXACT', 'MEDIUM', 'SMALL', 'ROTATED', 'QUOTE']))
  await colorSearch.fill('ＡＰＩ')
  assert.deepEqual(new Set(await visibleIds()), new Set(['EXACT', 'MEDIUM', 'SMALL', 'ROTATED', 'QUOTE']), '搜索忽略大小写和全半角')
  await colorSearch.fill('核桃')
  assert.deepEqual(await visibleIds(), ['ROTATED'])
  await colorSearch.fill('白色')
  assert.deepEqual(new Set(await visibleIds()), new Set(['MEDIUM', 'QUOTE']))
  await colorSearch.fill('爱马仕橙')
  assert.deepEqual(await visibleIds(), ['QUOTE'])
  await colorSearch.fill('胡桃')
  await colorFilter.selectOption('TONE:LIGHT')
  assert.deepEqual(await visibleIds(), ['MEDIUM'])
  await panel.getByRole('button', { name: /^≥85%/ }).click()
  await panel.getByText('当前颜色或搜索条件下无商品。').waitFor()
  await panel.getByRole('button', { name: '清空颜色与搜索' }).click()
  assert.equal(await colorSearch.inputValue(), '')
  assert.equal(await colorFilter.inputValue(), 'ALL')
  await panel.getByRole('button', { name: /^全部 \s*\(/ }).click()
  await colorFilter.selectOption('TONE:UNKNOWN')
  assert.equal(await panel.locator('[data-product-id]').count(), 30, '无颜色候选进入未分类')
  await colorFilter.selectOption('ALL')
  assert.equal(queries.length, queryCount, '颜色筛选和搜索不得重新请求推荐接口')
  const uniqueIds = await visibleIds()
  assert.equal(new Set(uniqueIds).size, uniqueIds.length, '组合色商品不得重复展示')

  await panel.getByRole('button', { name: /^<60%/ }).click()
  assert.equal(await panel.locator('[data-product-id]').count(), 1)
  assert.equal(await card('SMALL').count(), 1)
  assert.deepEqual(await colorFilter.locator('optgroup option').allTextContents(), ['月影胡桃 (1)'])
  await panel.getByRole('button', { name: /^全部 \s*\(/ }).click()
  await page.screenshot({ path: outDir + '/01-size-tiers.png', animations: 'disabled' })
  const before = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects)
  await panel.getByLabel('同步 2 个同规格模块').check()
  await card('EXACT').getByRole('button', { name: '应用到 2 个模块' }).click()
  await panel.getByText('已为 2 个模块匹配商品，模块占地保持不变').waitFor()
  let state = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')))
  for (const id of ['A','B']) {
    const item = state.objects.find(item => item.id === id), old = before.find(item => item.id === id)
    for (const key of ['w','h','x','y','rotation','layoutConfirmed']) assert.deepEqual(item[key], old[key])
    assert.equal(item.productId, 'EXACT')
  }
  assert.equal(state.cart.length, 2)
  assert.equal(state.cart.every(item => item.qty === 1), true)
  const fullySelectedOptions = await matchingModule.locator('option').allTextContents()
  assert.ok(fullySelectedOptions.some(text => text.includes('共享职员桌') && text.includes('1,200 × 600 mm') && text.includes('已选 2/2')), '同规格模块全部选中时，下拉项显示完整已选数量')
  assert.deepEqual(state.objects.find(item => item.id === 'OTHER'), before.find(item => item.id === 'OTHER'))
  assert.equal(selections[1].applyToSameModules, true)
  unavailable = true
  await card('MEDIUM').getByRole('button', { name: '应用到 2 个模块' }).click()
  await panel.getByRole('alert').filter({ hasText: '商品已下架' }).waitFor()
  state = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')))
  assert.equal(state.cart.every(item => item.productId === 'EXACT'), true)
  unavailable = false
  delayA = true
  await panel.getByLabel('允许旋转 90°').uncheck()
  await page.waitForTimeout(350)
  await furniture('C').click()
  await card('SMALL').waitFor()
  await page.waitForTimeout(1000)
  assert.equal(await card('EXACT').count(), 0)
  assert.equal(await matchingModule.inputValue(), 'C')
  empty = true
  await panel.getByRole('button', { name: '重新匹配' }).click()
  await panel.getByText(/暂无品类相关且尺寸可容纳的在售商品/).waitFor()
  assert.equal(await panel.locator('[data-product-id]').count(), 0)
  noCatalog = true
  await panel.getByRole('button', { name: '重新匹配' }).click()
  await panel.getByText('暂无符合推荐条件的在售规格，请核对商品和规格的在售状态。').waitFor()
  assert.equal(await panel.getByRole('link', { name: '查看商品库' }).getAttribute('href'), '/products')
  fail = true
  await panel.getByRole('button', { name: '重新匹配' }).click()
  await panel.getByRole('alert').filter({ hasText: '推荐服务暂不可用' }).waitFor()
  await page.screenshot({ path: outDir + '/02-empty-error.png', animations: 'disabled' })
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.objects.find(item => item.id === 'A').y = 3400
    store.unconfirmLayout('Z')
    store.saveLocal()
  })
  await page.getByTestId('tab-product-selection').click()
  await page.locator('[data-furniture-id="A"]').click()
  await page.getByRole('button', { name: '确认当前排版', exact: true }).click()
  assert.ok(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects.filter(item => item.kind === 'furniture' && item.zoneId === 'Z').every(item => item.layoutConfirmed)), '座椅侧净空不足不阻止确认')
  await page.getByTestId('layout-review-notice').waitFor()
  assert.match(await page.getByTestId('layout-review-notice').innerText(), /座椅一侧离墙净空不足 560mm，请现场复核/)
  assert.deepEqual(errors, [])
  console.log('模块尺寸推荐 UI 通过：画布与下拉双向联动、同规格单件及可选批量应用、保存中切换、旧响应拦截、几何保留、尺寸分档和旋转、颜色搜索、深浅/主色筛选、组合色搜索、筛选叠加、未分类、待报价、下架拒绝、空结果和失败。全部 API 模拟。')
} catch (failure) {
  if (page) await page.screenshot({ path: outDir + '/failure.png', animations: 'disabled' }).catch(() => {})
  throw failure
} finally {
  if (browser) await browser.close()
  app.kill('SIGTERM')
  await Promise.race([once(app, 'exit').catch(() => {}), new Promise(resolve => setTimeout(resolve, 2000))])
}
