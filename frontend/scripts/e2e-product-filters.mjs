import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const baseUrl = process.env.BASE_URL || 'http://localhost:3001'
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
const page = await context.newPage()
const pageErrors = []
page.on('pageerror', error => pageErrors.push(error.message))

const SPACE_RULES = [['职员台', 'STAFF'], ['主管台', 'MANAGER'], ['高管台', 'SENIOR_MANAGER'], ['总裁台', 'PRESIDENT'],
  ['会议台', 'MEETING'], ['洽谈台', 'NEGOTIATION'], ['前台', 'RECEPTION'], ['茶台', 'PANTRY'], ['茶水柜', 'PANTRY']]
const STORAGE_RULES = [['活动小柜', 'MOBILE_SMALL_CABINET'], ['长副柜', 'LONG_SIDE_CABINET'], ['高柜', 'HIGH_CABINET'],
  ['背柜', 'BACK_CABINET'], ['支撑柜', 'SUPPORT_CABINET'], ['副柜', 'SIDE_CABINET'], ['抽屉', 'DRAWER']]
const ALLOWED_COLORS = ['雅士白', '蜜芽胡桃', '液态银', '纳柯']

function spaceTypeOf(name) {
  for (const [word, code] of SPACE_RULES) if (name.includes(word)) return code
  return 'UNSPECIFIED'
}
function productTypeOf(name) {
  if (name.includes('沙发')) return 'SOFA'
  if (name.includes('椅')) return 'CHAIR'
  if (name.includes('台') || name.includes('桌') || name.includes('茶几')) return 'DESK'
  if (name.includes('柜')) return 'CABINET'
  return 'UNCLASSIFIED'
}
function colorsOf(product) {
  return (product.colorPrimaryName || '').split('+').map(part => part.trim()).filter(part => ALLOWED_COLORS.includes(part))
}
function legTypesOf(product) {
  const text = `${product.productName} ${product.description || ''}`
  const legs = []
  if (text.includes('钢脚')) legs.push('STEEL')
  if (text.includes('支撑柜')) legs.push('SUPPORT_CABINET')
  return legs.length ? legs : ['UNSPECIFIED']
}
function storageTypeOf(product) {
  for (const source of [product.productName, product.description || '']) {
    for (const [word, code] of STORAGE_RULES) if (source.includes(word)) return code
  }
  return 'UNSPECIFIED'
}

const products = []
let seq = 0
function add(name, color, description, material, width, status) {
  seq += 1
  products.push({
    rspuId: `P-${seq}`, rspuCode: `C-${seq}`, externalCode: `E-${seq}`, productName: name,
    productLevel: 'B', colorPrimaryName: color, primaryImageUrl: null, retailPrice: 1000 + seq,
    status, description, material, width,
    createdAt: '2026-11-01T08:00:00', updatedAt: '2026-11-01T08:00:00',
  })
}
for (let k = 1; k <= 60; k++) add(`1.4米职员台 ${k}`, '蜜芽胡桃+雅士白', '钢脚', 'PB', 1400, 'active')
for (let k = 1; k <= 20; k++) add(`1.6米主管台 ${k}`, '纳柯+液态银', '支撑柜带抽屉，钢脚', 'PB', 1600, 'active')
for (let k = 1; k <= 5; k++) add(`2米文件柜 ${k}`, '雅士白', '高柜', 'PB', 1200, 'active')
for (let k = 1; k <= 5; k++) add(`办公椅 ${k}`, '液态银', '', 'WO', 600, 'active')
for (let k = 1; k <= 10; k++) add(`1.2米双人位 ${k}`, '雅士白', '', 'WO', 1200, 'inactive')

function widthRangeOf(width) {
  if (width == null) return 'UNSPECIFIED'
  if (width <= 1000) return 'LE_1000'
  if (width <= 1200) return 'R1000_1200'
  if (width <= 1600) return 'R1200_1600'
  if (width <= 2000) return 'R1600_2000'
  if (width <= 2400) return 'R2000_2400'
  if (width <= 2800) return 'R2400_2800'
  return 'GT_2800'
}

function buildFacets(base) {
  const tally = values => values.reduce((map, value) => map.set(value, (map.get(value) || 0) + 1), new Map())
  const widthRangeCounts = tally(base.map(product => widthRangeOf(product.width)))
  const levelCounts = tally(base.map(product => product.productLevel))
  const spaceCounts = tally(base.map(product => spaceTypeOf(product.productName)))
  const typeCounts = tally(base.map(product => productTypeOf(product.productName)))
  const storageCounts = tally(base.map(product => storageTypeOf(product)))
  const legCounts = tally(base.flatMap(product => legTypesOf(product)))
  const colorCounts = tally(base.flatMap(product => colorsOf(product)))
  const materialCounts = tally(base.map(product => product.material))
  const option = (code, label, count) => ({ code, label, count, disabled: count === 0 })
  const group = (order, labels, counts) => order.map(code => option(code, labels[code] ?? code, counts.get(code) || 0))
  return {
    groups: {
      levels: group(['S', 'A', 'B', 'C'], { S: 'S级', A: 'A级', B: 'B级', C: 'C级' }, levelCounts),
      spaceTypes: group(['STAFF', 'MANAGER', 'SENIOR_MANAGER', 'PRESIDENT', 'MEETING', 'NEGOTIATION', 'RECEPTION', 'PANTRY'],
        { STAFF: '职员空间', MANAGER: '主管空间', SENIOR_MANAGER: '高管空间', PRESIDENT: '总裁空间', MEETING: '会议空间', NEGOTIATION: '洽谈空间', RECEPTION: '接待空间', PANTRY: '茶水空间' }, spaceCounts),
      productTypes: group(['DESK', 'CABINET', 'CHAIR', 'SOFA'],
        { DESK: '桌类', CABINET: '柜类', CHAIR: '椅类', SOFA: '沙发类' }, typeCounts),
      materials: [...materialCounts.keys()].sort().map(code => option(code, code, materialCounts.get(code))),
      colors: [...colorCounts.keys()].map(code => option(code, code, colorCounts.get(code))),
      widthRanges: [
        ['LE_1000', '1m及以下'], ['R1000_1200', '1–1.2m'], ['R1200_1600', '1.2–1.6m'],
        ['R1600_2000', '1.6–2m'], ['R2000_2400', '2–2.4m'], ['R2400_2800', '2.4–2.8m'],
        ['GT_2800', '2.8m以上'],
      ].map(([code, label]) => option(code, label, widthRangeCounts.get(code) || 0)),
      legTypes: group(['STEEL', 'SUPPORT_CABINET'], { STEEL: '钢脚', SUPPORT_CABINET: '支撑柜' }, legCounts),
      storageTypes: group(['MOBILE_SMALL_CABINET', 'LONG_SIDE_CABINET', 'HIGH_CABINET', 'BACK_CABINET', 'SUPPORT_CABINET', 'SIDE_CABINET', 'DRAWER'],
        { MOBILE_SMALL_CABINET: '活动小柜', LONG_SIDE_CABINET: '长副柜', HIGH_CABINET: '高柜', BACK_CABINET: '背柜', SUPPORT_CABINET: '支撑柜', SIDE_CABINET: '副柜', DRAWER: '抽屉' }, storageCounts),
    },
  }
}

let failNextPage = false
let delayNextPageMs = 0
const pageRequests = []

await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
await context.route('**/api/v1/**', async route => {
  const request = route.request()
  const url = new URL(request.url())
  const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status, message: status === 200 ? '成功' : '服务暂不可用', data }) })
  if (url.pathname === '/api/v1/products/capabilities') return respond({ managed: false })
  if (url.pathname === '/api/v1/office/workspace/shared-catalog') return respond({ workspaceEnabled: true, dealer: false, effectiveVisible: true })
  if (url.pathname === '/api/v1/products/page' && request.method() === 'GET') {
    const params = {
      page: Number(url.searchParams.get('page') || 1),
      size: Number(url.searchParams.get('size') || 20),
      search: (url.searchParams.get('search') || '').toLowerCase(),
      status: url.searchParams.get('status') || '',
      levels: url.searchParams.getAll('levels'),
      spaceTypes: url.searchParams.getAll('spaceTypes'),
      productTypes: url.searchParams.getAll('productTypes'),
      materials: url.searchParams.getAll('materials'),
      colors: url.searchParams.getAll('colors'),
      widthRanges: url.searchParams.getAll('widthRanges'),
      legTypes: url.searchParams.getAll('legTypes'),
      storageTypes: url.searchParams.getAll('storageTypes'),
      refresh: url.searchParams.get('refresh'),
    }
    pageRequests.push(params)
    if (failNextPage) return respond(null, 500)
    if (delayNextPageMs) { const delay = delayNextPageMs; delayNextPageMs = 0; await new Promise(resolve => setTimeout(resolve, delay)) }
    const base = products.filter(product => (
      !params.search || [product.productName, product.rspuCode, product.externalCode, product.rspuId].some(value => value?.toLowerCase().includes(params.search))
    ) && (!params.status || (params.status === 'active' ? product.status === 'active' : product.status !== 'active')))
    const matching = base.filter(product => (
      (!params.levels.length || params.levels.includes(product.productLevel))
      && (!params.spaceTypes.length || params.spaceTypes.includes(spaceTypeOf(product.productName)))
      && (!params.productTypes.length || params.productTypes.includes(productTypeOf(product.productName)))
      && (!params.materials.length || params.materials.includes(product.material))
      && (!params.colors.length || colorsOf(product).some(color => params.colors.includes(color)))
      && (!params.widthRanges.length || params.widthRanges.includes(widthRangeOf(product.width)))
      && (!params.legTypes.length || legTypesOf(product).some(leg => params.legTypes.includes(leg)))
      && (!params.storageTypes.length || params.storageTypes.includes(storageTypeOf(product)))
    ))
    return respond({
      page: params.page, size: params.size,
      rows: matching.slice((params.page - 1) * params.size, params.page * params.size)
        .map(({ description, material, width, ...summary }) => summary),
      total: matching.length, catalogTotal: products.length,
      activeTotal: products.filter(product => product.status === 'active').length,
      facets: buildFacets(base),
    })
  }
  if (/^\/api\/v1\/products\/P-\d+$/.test(url.pathname) && request.method() === 'GET') {
    const product = products.find(item => item.rspuId === url.pathname.split('/').pop())
    return respond({
      ...product, detailImageUrls: null, deletedAt: null,
      variants: [{ variantId: product.rspuId + '-V1', displayName: product.productName, sizeCode: null, sizeText: null,
        dimensions: null, widthMm: product.width, productLevel: 'B', packageCount: null, packageVolumeCbm: null,
        grossWeight: null, colorCode: null, colorText: null, materialCode: null, materialText: product.material,
        status: product.status, createdAt: product.createdAt, updatedAt: product.updatedAt }],
      images: [],
    })
  }
  if (url.pathname === '/api/v1/products' && request.method() === 'GET') return respond(products.map(({ description, material, width, ...summary }) => summary))
  if (url.pathname === '/api/v1/office/favorites/product-ids') return respond([])
  if (url.pathname === '/api/v1/office/favorites/items') return respond([])
  if (url.pathname === '/api/v1/office/favorites/folders') return respond([])
  if (url.pathname === '/api/v1/office/projects' && request.method() === 'GET') return respond([])
  if (/^\/api\/v1\/office\/projects\/[^/]+\/favorites\/(product-ids|items)$/.test(url.pathname)) return respond([])
  return respond([])
})

const dialog = page.getByRole('dialog', { name: '全部筛选' })
const cards = page.locator('[aria-label="商品卡片列表"] > *')
const chips = page.locator('.active-filter-chip')
const totalText = () => page.getByText(/共 \d+ 件商品/).first().innerText()

async function openDialog() {
  await page.getByRole('button', { name: /^全部筛选/ }).click()
  await dialog.waitFor()
}
async function pickGroup(group, optionName) {
  await dialog.locator(`section[aria-label="${group}"]`).getByRole('button', { name: optionName, exact: true }).click()
}

try {
  await page.goto(`${baseUrl}/products`, { waitUntil: 'networkidle' })
  await cards.first().waitFor()
  assert.equal(await cards.count(), 20)
  assert.match(await totalText(), /共 100 件商品/)
  assert.equal(await chips.count(), 0)

  // 点选立即筛选，弹层保持打开
  await openDialog()
  const gridBox = await page.locator('[aria-label="商品卡片列表"]').boundingBox()
  const dialogBox = await dialog.boundingBox()
  assert.ok(dialogBox.y > 100, '抽屉从工具栏下方开始，不覆盖页面头部')
  assert.ok(gridBox.x + gridBox.width <= dialogBox.x + 1, '商品区左移不被抽屉遮挡')

  // 再次点击全部筛选按钮收起，再打开
  await page.getByRole('button', { name: /^全部筛选/ }).click()
  await dialog.waitFor({ state: 'hidden' })
  await openDialog()
  await pickGroup('空间类型', '职员空间')
  await page.waitForFunction(() => document.body.innerText.includes('共 60 件商品'))
  assert.equal(await chips.count(), 1)
  assert.equal(await dialog.isVisible(), true, '点选后弹层保持打开可继续多选')
  assert.equal(pageRequests.at(-1).page, 1)
  assert.deepEqual(pageRequests.at(-1).spaceTypes, ['STAFF'])

  // 再次点击取消选中，立即恢复
  await pickGroup('空间类型', '职员空间')
  await page.waitForFunction(() => document.body.innerText.includes('共 100 件商品'))
  assert.equal(await chips.count(), 0)

  // 完成关闭，已应用条件保留
  await dialog.getByRole('button', { name: '完成', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  assert.equal(await chips.count(), 0)

  // 弹层回显 + Esc 关闭不改变已应用条件
  await openDialog()
  await pickGroup('空间类型', '职员空间')
  await page.waitForFunction(() => document.body.innerText.includes('共 60 件商品'))
  await dialog.getByRole('button', { name: '完成', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  await openDialog()
  assert.equal(await dialog.locator('section[aria-label="空间类型"]').getByRole('button', { name: '职员空间', exact: true }).getAttribute('aria-pressed'), 'true')
  await page.keyboard.press('Escape')
  await dialog.waitFor({ state: 'hidden' })
  assert.equal(await chips.count(), 1)
  assert.match(await totalText(), /共 60 件商品/)

  // 清空立即生效
  await openDialog()
  await dialog.getByRole('button', { name: '清空', exact: true }).click()
  await page.waitForFunction(() => document.body.innerText.includes('共 100 件商品'))
  assert.equal(await chips.count(), 0)
  await dialog.getByRole('button', { name: '完成', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })

  // 滚动时页签追踪分组
  await openDialog()
  await page.locator('.af-body').evaluate(el => { el.scrollTop = el.scrollHeight })
  await page.waitForTimeout(250)
  assert.equal((await dialog.locator('.af-tab.is-active').innerText()).trim(), '收纳分类', '滚动到底部页签追踪到收纳分类')
  await page.locator('.af-body').evaluate(el => { el.scrollTop = 0 })
  await page.waitForTimeout(250)
  assert.equal((await dialog.locator('.af-tab.is-active').innerText()).trim(), '产品等级', '滚回顶部页签追踪到产品等级')
  await dialog.getByRole('button', { name: '完成', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })

  // 尺寸区间筛选
  await openDialog()
  await pickGroup('尺寸分类', '1.2–1.6m')
  await page.waitForFunction(() => document.body.innerText.includes('共 80 件商品'))
  assert.deepEqual(pageRequests.at(-1).widthRanges, ['R1200_1600'])
  await dialog.getByRole('button', { name: '完成', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  await chips.filter({ hasText: '1.2–1.6m' }).waitFor()

  // 单删标签
  await chips.first().click()
  await page.waitForFunction(() => document.body.innerText.includes('共 100 件商品'))
  assert.equal(await chips.count(), 0)

  // 快捷下拉与弹层同步
  await page.getByRole('combobox', { name: '筛选产品分类' }).selectOption('CHAIR')
  await page.waitForFunction(() => document.body.innerText.includes('共 5 件商品'))
  assert.equal(await chips.count(), 1)
  await openDialog()
  assert.equal(await dialog.locator('section[aria-label="产品分类"]').getByRole('button', { name: '椅类', exact: true }).getAttribute('aria-pressed'), 'true')
  await dialog.getByRole('button', { name: '完成', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })

  // 应用失败保留旧列表并提示，重试成功
  failNextPage = true
  await openDialog()
  await pickGroup('收纳分类', '高柜')
  await dialog.getByRole('alert').waitFor()
  assert.ok((await dialog.getByRole('alert').innerText()).length > 0)
  assert.match(await totalText(), /共 5 件商品/)
  assert.equal(await chips.count(), 1)
  assert.equal(await dialog.locator('section[aria-label="收纳分类"]').getByRole('button', { name: '高柜', exact: true }).getAttribute('aria-pressed'), 'false')
  failNextPage = false
  await pickGroup('收纳分类', '高柜')
  await page.getByText('没有找到匹配的商品').waitFor()
  assert.equal(await chips.count(), 2)
  await dialog.getByRole('button', { name: '完成', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })

  // 清空全部标签
  await page.getByRole('button', { name: '清空全部', exact: true }).click()
  await page.waitForFunction(() => document.body.innerText.includes('共 100 件商品'))
  assert.equal(await chips.count(), 0)

  // 筛选后回到第一页
  await page.getByRole('combobox', { name: '筛选商品主色' }).selectOption('蜜芽胡桃')
  await page.waitForFunction(() => document.body.innerText.includes('共 60 件商品'))
  await page.getByRole('button', { name: '下一页' }).click()
  await page.waitForFunction(() => document.body.innerText.includes('正在显示 21–40'))
  await openDialog()
  await pickGroup('产品等级', 'B级')
  assert.equal(pageRequests.at(-1).page, 1)
  await page.waitForFunction(() => document.body.innerText.includes('正在显示 1–20'))
  await dialog.getByRole('button', { name: '完成', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })

  // 过期响应不覆盖新条件
  await page.getByRole('button', { name: '清空全部', exact: true }).click()
  await page.waitForFunction(() => document.body.innerText.includes('共 100 件商品'))
  delayNextPageMs = 900
  void page.getByRole('combobox', { name: '筛选产品分类' }).selectOption('DESK')
  await page.waitForTimeout(80)
  await page.getByRole('combobox', { name: '筛选产品分类' }).selectOption('CHAIR')
  await page.waitForFunction(() => document.body.innerText.includes('共 5 件商品'), null, { timeout: 5000 })
  await page.waitForTimeout(1400)
  assert.match(await totalText(), /共 5 件商品/)
  assert.equal(await chips.count(), 1)
  assert.equal(await page.getByRole('combobox', { name: '筛选产品分类' }).inputValue(), 'CHAIR')

  // 刷新携带 refresh=true 并保留已应用筛选
  await page.getByRole('button', { name: '刷新商品列表' }).click()
  await page.waitForFunction(() => document.body.innerText.includes('共 5 件商品'))
  await page.waitForTimeout(300)
  assert.equal(pageRequests.at(-1).refresh, 'true')
  assert.deepEqual(pageRequests.at(-1).productTypes, ['CHAIR'])

  // 抽屉打开时可直接点击商品进入详情（非模态）
  await openDialog()
  await page.locator('[aria-label="商品卡片列表"] a').first().click()
  await page.waitForURL(/\/products\/P-\d+/)
  await page.goto(`${baseUrl}/products`, { waitUntil: 'networkidle' })

  // 手机 390×844 全屏弹层
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: /^全部筛选/ }).click()
  await dialog.waitFor()
  const footer = await page.locator('[data-testid="advanced-filter-footer"]').boundingBox()
  assert.ok(footer, '底部按钮区域应可见')
  assert.ok(footer.y + footer.height <= 844, '底部按钮应完整位于视口内')
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true, '弹层不得产生横向溢出')
  await dialog.getByRole('button', { name: '完成', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })

  assert.deepEqual(pageErrors, [], `页面脚本错误: ${pageErrors.join('; ')}`)
  console.log('e2e-product-filters: all assertions passed')
} catch (error) {
  console.error('PAGE_REQUESTS:', JSON.stringify(pageRequests.map(params => ({
    page: params.page, status: params.status, search: params.search, levels: params.levels,
    spaceTypes: params.spaceTypes, productTypes: params.productTypes, materials: params.materials,
    colors: params.colors, widthRanges: params.widthRanges, legTypes: params.legTypes,
    storageTypes: params.storageTypes, refresh: params.refresh,
  })), null, 1))
  throw error
} finally {
  await browser.close()
}
