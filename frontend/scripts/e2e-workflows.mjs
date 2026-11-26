import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { chromium } from 'playwright'
import { createReviewConfirmMock, fulfillReviewConfirm, confirmAllReviews } from './helpers/review-confirm-mock.mjs'

const baseUrl = process.env.BASE_URL || 'http://localhost:3001'
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
const context = await browser.newContext({ viewport: { width: 1440, height: 960 }, acceptDownloads: true })
const page = await context.newPage()
const pageErrors = []
page.on('pageerror', error => pageErrors.push(error.message))
let listMode = 'success'
let catalogEnabled = true
let catalogDealer = true
let includeLocalFixture = false
let rejectCatalogChange = false
const catalogUpdates = []
const localProduct = { id: 'LOCAL-TEA-TABLE', revision: 1, name: '本地样品茶几', categoryCode: 'CODE-1', factoryModel: 'LOCAL-001' }
const localImageId = 'LOCAL-IMAGE'
const localSideImageId = 'LOCAL-SIDE-IMAGE'
const localCollectionId = 'OFFICE-LOCAL-' + localProduct.id
const localFavoriteProduct = {
  rspuId: localCollectionId, rspuCode: localProduct.factoryModel, productName: localProduct.name,
  status: 'active', primaryImageUrl: '/api/v1/office/local-products/images/' + localImageId,
}
const savedBundles = []
let staffCanEditLocalProducts = false
let localCapacityUsed = 4999
let supplierCapacityPreview = false
const capacitySaves = []
const quotaPreviewProducts = ['LIMIT-1', 'LIMIT-2'].map(factoryModel => ({
  name: '额度验证 ' + factoryModel, categoryCode: 'CODE-1', factoryModel, images: [],
  variants: [{ name: '默认规格', widthMm: 1200, depthMm: 600, heightMm: 450 }],
}))
let catalogPageRequests = 0
let importRequests = 0
let legacyAuditDetailRequests = 0
const favoriteFolders = []
const favoriteItems = []
const favoriteSaveRequests = []
const projectFavoriteItems = []
const projectFavoriteSaveRequests = []
let failNextProjectFavoriteSave = false
let failNextFavoriteSave = false
let failNextFavoriteFolders = false
let delayNextFavoriteSave = false
let delayedFavoriteRspuId = null
let delayNextFavoriteCreate = false
let delayNextFavoriteIds = false
let delayNextProjectFavoriteIds = false
let delayNextProjectFavoriteRead = false
let personalFavoriteIdsRequests = 0
function ensureDefaultFavoriteFolder() {
  if (!favoriteFolders.some(folder => folder.defaultFolder)) {
    favoriteFolders.push({ folderId: 'F-DEFAULT', name: '默认收藏夹', defaultFolder: true })
  }
}
function favoriteFolderData(rspuId) {
  ensureDefaultFavoriteFolder()
  return favoriteFolders.map(folder => ({
    ...folder,
    itemCount: favoriteItems.filter(item => item.folderId === folder.folderId).length,
    selected: !!rspuId && favoriteItems.some(item => item.folderId === folder.folderId && item.rspuId === rspuId),
  }))
}
const products = Array.from({ length: 23 }, (_, index) => ({
  rspuId: `PRODUCT-${index + 1}`,
  rspuCode: `RSDP-${String(index + 1).padStart(3, '0')}`,
  externalCode: `EXTERNAL-${index + 1}`,
  productName: index % 2 ? `简约办公桌 ${index + 1}` : `人体工学椅 ${index + 1}`,
  categoryPath: index < 4 ? ['办公家具', index % 2 ? '办公桌' : '办公椅'] : undefined,
  colorPrimaryName: index === 2 ? '深木色' : index < 4 ? '胡桃色' : null,
  positioningLabel: '现代办公', primaryImageUrl: null, retailPrice: 980 + index * 100,
  referencePriceBand: '标准', productLevel: 'B', warrantyYears: 3,
  sixDimTags: '{"A":["A1"],"B":["B2"]}', status: index % 3 ? 'active' : 'inactive',
  createdAt: '2026-10-12T08:00:00', updatedAt: '2026-10-12T09:00:00',
}))
const batchOverview = {
  batchId: 'BATCH-MOCK', fileName: '商品清单.xlsx', status: 'done',
  totalRows: 2, successCount: 1, failedCount: 1,
}
const localImport = {
  id: '00000000-0000-4000-8000-000000000001', fileName: '本地商品导入.xlsx',
  fileBytes: 3 * 1024 * 1024, actorName: '测试用户', createdAt: '2026-11-25T06:00:00Z',
  batchCount: 2, productCount: 1,
}
let historicalImportRequests = 0
await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
const reviewMock = createReviewConfirmMock()
const defaultProject = {
  projectId: 'OFFICE-PROJECT-001', name: '默认办公项目', customerRequirements: '', requirementsRevision: 1,
  budgetTotal: 124331.15, budgetStatus: 'PENDING_PRICING',
  leader: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'LEADER', permissions: ['READ', 'EDIT', 'EXPORT_PROJECT'] }, collaborators: [], referenceImages: [],
  floors: [{ projectId: 'OFFICE-PROJECT-001', floorId: 'OFFICE-PROJECT-001', name: '默认楼层', sortOrder: 1 }],
}
const secondProject = {
  projectId: 'OFFICE-PROJECT-002', name: '第二个办公项目', customerRequirements: '', requirementsRevision: 1,
  leader: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'LEADER', permissions: ['READ', 'EDIT', 'EXPORT_PROJECT'] }, collaborators: [], referenceImages: [],
  floors: [{ projectId: 'OFFICE-PROJECT-002', floorId: 'OFFICE-PROJECT-002-FLOOR', name: '默认楼层', sortOrder: 1 }],
}
const availableProjects = [defaultProject, secondProject]
await context.route('**/api/v1/**', async route => {
  if (fulfillReviewConfirm(reviewMock, route)) return
  const request = route.request()
  const url = new URL(request.url())
  const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status, message: status === 200 ? '成功' : '服务暂不可用', data }) })
  if (url.pathname === '/api/v1/office/projects' && request.method() === 'GET') return respond(availableProjects)
  if (url.pathname === '/api/v1/office/projects/OFFICE-PROJECT-001/requirements' && request.method() === 'PATCH') {
    defaultProject.customerRequirements = request.postDataJSON().customerRequirements
    defaultProject.requirementsRevision++
    const { budgetStatus, budgetTotal, ...details } = defaultProject
    return respond(details)
  }
  if (url.pathname === '/api/v1/products/capabilities') return respond({ managed: true })
  if (url.pathname === '/api/v1/office/product-bundles' && request.method() === 'GET') return respond(savedBundles)
  if (url.pathname.startsWith('/api/v1/office/product-bundles/') && request.method() === 'PUT') {
    const { name, revision, items } = request.postDataJSON()
    const bundle = { bundleId: url.pathname.split('/').at(-1), name, revision: revision + 1,
      items: items.map(item => ({ ...item, name: localProduct.name, variant: '加长规格' })) }
    savedBundles.push(bundle)
    return respond(bundle)
  }
  if (url.pathname === '/api/v1/office/local-products/capacity') return respond({ used: localCapacityUsed, limit: 5000 })
  if (url.pathname === '/api/v1/office/local-products/excel-preview') {
    if (supplierCapacityPreview) return respond({
      products: [], errors: [], previewId: 'SUPPLIER-QUOTA-PREVIEW', imageSizes: {},
      supplier: { products: quotaPreviewProducts.map(product => ({ product, sources: [], images: [] })), images: {} },
    })
    return respond({ products: quotaPreviewProducts, errors: [], previewId: 'QUOTA-PREVIEW', imageSizes: {} })
  }
  if (url.pathname === '/api/v1/office/local-products/imports') {
    const query = url.searchParams.get('search') || ''
    return respond({ canWrite: catalogDealer || staffCanEditLocalProducts, hasMore: false,
      records: localImport.fileName.includes(query) ? [localImport] : [] })
  }
  if (url.pathname === `/api/v1/office/local-products/imports/${localImport.id}`) {
    return respond({ record: localImport, products: [{ id: localProduct.id, name: localProduct.name, revision: 1 }] })
  }
  if (url.pathname === '/api/v1/office/local-products') {
    if (request.method() === 'POST') {
      const { products } = request.postDataJSON()
      capacitySaves.push(products)
      localCapacityUsed += products.length
      return respond(products.map(product => ({ id: product.factoryModel, name: product.name, revision: 1 })))
    }
    const query = (url.searchParams.get('search') || '').toLowerCase()
    const matches = [localProduct.name, localProduct.factoryModel].some(value => value.toLowerCase().includes(query))
    return respond({ canWrite: catalogDealer || staffCanEditLocalProducts,
      products: matches ? [{ ...localProduct, primaryImageId: localImageId }] : [], hasMore: false })
  }
  if (url.pathname === `/api/v1/office/local-products/${localProduct.id}`) {
    return respond({ product: localProduct, variants: [
      { id: 'STANDARD', name: '默认规格', widthMm: 1200, depthMm: 600, heightMm: 450, color: '胡桃色', material: 'PB' },
      { id: 'LARGE', name: '加长规格', widthMm: 1400, depthMm: 600, heightMm: 450, color: '深木色', material: '碳晶板' },
    ], images: [localImageId, localSideImageId].map(id => ({ id, mediaType: 'image/png' })),
    canWrite: catalogDealer || staffCanEditLocalProducts })
  }
  if ([localImageId, localSideImageId].some(id => url.pathname === `/api/v1/office/local-products/images/${id}`)) {
    return route.fulfill({ contentType: 'image/png', body: Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9GQAAAAASUVORK5CYII=', 'base64') })
  }
  if (url.pathname.startsWith('/api/v1/dicts/')) {
    return respond([{ dictCode: 'CODE-1', dictName: '测试字典项' }])
  }
  if (url.pathname === '/api/v1/products/import-batches' && request.method() === 'GET') {
    historicalImportRequests++
    return respond([batchOverview])
  }
  if (url.pathname === '/api/v1/products/import-batches/BATCH-MOCK/status' && request.method() === 'GET') {
    return respond({ ...batchOverview, rows: [
      { excelRowNumber: 2, status: 'success', generatedRspuId: 'PRODUCT-1' },
      { excelRowNumber: 3, status: 'failed', failureReason: '商品名称不能为空' },
    ] })
  }
  if (url.pathname === '/api/v1/products/audit') legacyAuditDetailRequests += 1
  if (url.pathname.startsWith('/api/v1/products') && request.method() !== 'GET') {
    importRequests += 1
    return respond(null, 403)
  }
  if (url.pathname === '/api/v1/products/browse/facets') {
    return respond({
      localCategories: includeLocalFixture ? [localProduct.categoryCode] : [],
      sharedCategories: catalogEnabled ? [...new Set(products.map(product => product.categoryPath?.join(' / ')).filter(Boolean))] : [],
      colors: catalogEnabled ? [...new Set(products.map(product => product.colorPrimaryName).filter(Boolean))] : [],
      sharedUnavailable: false,
    })
  }
  if (url.pathname === '/api/v1/products/browse' && request.method() === 'GET') {
    if (catalogEnabled) catalogPageRequests++
    const localCatalog = includeLocalFixture ? [{ ...localProduct, primaryImageId: localImageId }] : []
    const sharedCatalog = listMode === 'success' && catalogEnabled ? products : []
    const page = Number(url.searchParams.get('page'))
    const size = Number(url.searchParams.get('size'))
    const search = (url.searchParams.get('search') || '').toLowerCase()
    const status = url.searchParams.get('status')
    const color = url.searchParams.get('color')
    const category = url.searchParams.get('category')
    const sharedMatches = sharedCatalog.filter(product =>
      (!search || [product.productName, product.rspuCode, product.externalCode, product.rspuId].some(value => value?.toLowerCase().includes(search)))
      && (!status || (status === 'active' ? product.status === 'active' : product.status !== 'active'))
      && (!category || category === 'shared:' + product.categoryPath?.join(' / '))
      && (!color || color === product.colorPrimaryName))
    const localMatches = localCatalog.filter(product =>
      (!search || [product.name, product.factoryModel].some(value => value.toLowerCase().includes(search)))
      && !status && !color && (!category || category === 'local:' + product.categoryCode))
    const matches = []
    for (let index = 0; index < Math.max(localMatches.length, sharedMatches.length); index++) {
      if (localMatches[index]) matches.push({ local: localMatches[index], shared: null })
      if (sharedMatches[index]) matches.push({ local: null, shared: sharedMatches[index] })
    }
    return respond({ rows: matches.slice((page - 1) * size, page * size), total: matches.length,
      localTotal: localCatalog.length, catalogTotal: localCatalog.length + sharedCatalog.length,
      activeTotal: sharedCatalog.filter(product => product.status === 'active').length,
      canManageLocal: catalogDealer || staffCanEditLocalProducts, sharedUnavailable: catalogEnabled && listMode === 'error' })
  }
  if (url.pathname.startsWith('/api/v1/products/PRODUCT-') && request.method() === 'GET') {
    const rspuId = url.pathname.split('/').at(-1)
    const product = products.find(item => item.rspuId === rspuId)
    return respond({ ...product, description: '测试配置说明', images: [localImageId, localSideImageId].map((id, index) => ({
      imageId: id, storageUrl: `/api/v1/office/local-products/images/${id}`, primary: index === 0,
    })), variants: [
      { variantId: 'STRUCTURED', displayName: '默认规格', dimensionsText: '1200 × 600 × 450 mm', materialCode: 'PB',
        packageCount: 2, packageVolumeCbm: 0.36, grossWeight: 18, status: 'active' },
      { variantId: 'SUPPLIER', displayName: '供应商规格', sizeText: '供应商尺寸原文', dimensionsText: '1400 × 600 mm', status: 'active' },
    ] })
  }
  const projectFavoriteIdsMatch = url.pathname.match(/^\/api\/v1\/office\/projects\/([^/]+)\/favorites\/product-ids$/)
  if (projectFavoriteIdsMatch && request.method() === 'GET') {
    const projectId = projectFavoriteIdsMatch[1]
    if (delayNextProjectFavoriteIds) {
      delayNextProjectFavoriteIds = false
      await new Promise(resolve => setTimeout(resolve, 3000))
    }
    return respond(projectFavoriteItems.filter(item => item.projectId === projectId).map(item => item.rspuId).sort())
  }
  const projectFavoriteItemsMatch = url.pathname.match(/^\/api\/v1\/office\/projects\/([^/]+)\/favorites\/items$/)
  if (projectFavoriteItemsMatch && request.method() === 'GET') {
    const projectId = projectFavoriteItemsMatch[1]
    const visible = catalogEnabled ? [...products, localFavoriteProduct] : [localFavoriteProduct]
    return respond(visible.filter(product => projectFavoriteItems.some(item => item.projectId === projectId && item.rspuId === product.rspuId)))
  }
  if (url.pathname === '/api/v1/office/favorites/items' && request.method() === 'GET') {
    const visible = catalogEnabled ? [...products, localFavoriteProduct] : [localFavoriteProduct]
    return respond(visible.filter(product => favoriteItems.some(item => item.rspuId === product.rspuId)))
  }
  const projectFavoriteMatch = url.pathname.match(/^\/api\/v1\/office\/projects\/([^/]+)\/favorites\/((?:PRODUCT-|OFFICE-LOCAL-)[^/]+)$/)
  if (projectFavoriteMatch && request.method() === 'GET') {
    const [, projectId, rspuId] = projectFavoriteMatch
    if (delayNextProjectFavoriteRead) {
      delayNextProjectFavoriteRead = false
      await new Promise(resolve => setTimeout(resolve, 500))
    }
    return respond({ selected: projectFavoriteItems.some(item => item.projectId === projectId && item.rspuId === rspuId) })
  }
  if (projectFavoriteMatch && request.method() === 'PUT') {
    const [, projectId, rspuId] = projectFavoriteMatch
    const selected = request.postDataJSON().selected
    projectFavoriteSaveRequests.push({ projectId, rspuId, selected })
    if (failNextProjectFavoriteSave) {
      failNextProjectFavoriteSave = false
      return respond(null, 503)
    }
    const index = projectFavoriteItems.findIndex(item => item.projectId === projectId && item.rspuId === rspuId)
    if (selected && index < 0) projectFavoriteItems.push({ projectId, rspuId })
    if (!selected && index >= 0) projectFavoriteItems.splice(index, 1)
    return respond({ selected })
  }
  if (url.pathname === '/api/v1/office/favorites/folders' && request.method() === 'GET') {
    if (failNextFavoriteFolders) return respond(null, 503)
    const rspuId = url.searchParams.get('rspuId')
    if (delayedFavoriteRspuId === rspuId) {
      delayedFavoriteRspuId = null
      await new Promise(resolve => setTimeout(resolve, 400))
    }
    return respond(favoriteFolderData(rspuId))
  }
  if (url.pathname === '/api/v1/office/favorites/folders' && request.method() === 'POST') {
    const name = request.postDataJSON().name.trim()
    if (favoriteFolders.some(folder => folder.name === name)) return respond(null, 400)
    const folder = { folderId: `F-CUSTOM-${favoriteFolders.length}`, name, defaultFolder: false }
    if (delayNextFavoriteCreate) {
      delayNextFavoriteCreate = false
      await new Promise(resolve => setTimeout(resolve, 400))
    }
    favoriteFolders.push(folder)
    return respond({ ...folder, itemCount: 0, selected: false })
  }
  if (url.pathname.startsWith('/api/v1/office/favorites/products/') && request.method() === 'PUT') {
    const rspuId = url.pathname.split('/').at(-1)
    const folderIds = request.postDataJSON().folderIds
    favoriteSaveRequests.push({ rspuId, folderIds })
    if (failNextFavoriteSave) {
      failNextFavoriteSave = false
      return respond(null, 503)
    }
    if (delayNextFavoriteSave) {
      delayNextFavoriteSave = false
      await new Promise(resolve => setTimeout(resolve, 400))
    }
    for (let index = favoriteItems.length - 1; index >= 0; index--) {
      if (favoriteItems[index].rspuId === rspuId) favoriteItems.splice(index, 1)
    }
    folderIds.forEach(folderId => favoriteItems.push({ folderId, rspuId }))
    return respond(null)
  }
  if (url.pathname === '/api/v1/office/favorites/product-ids' && request.method() === 'GET') {
    personalFavoriteIdsRequests += 1
    if (delayNextFavoriteIds) {
      delayNextFavoriteIds = false
      await new Promise(resolve => setTimeout(resolve, 3000))
    }
    return respond([...new Set(favoriteItems.map(item => item.rspuId))].sort())
  }
  if (url.pathname === '/api/v1/office/workspace/shared-catalog') {
    if (request.method() === 'PUT') {
      catalogUpdates.push(request.postDataJSON().enabled)
      if (!catalogDealer || rejectCatalogChange) return respond(null, 503)
      catalogEnabled = request.postDataJSON().enabled
    }
    return respond({ workspaceEnabled: catalogEnabled, dealer: catalogDealer, effectiveVisible: catalogEnabled })
  }
  return respond(url.pathname.endsWith('/OFFICE-PROJECT-001') || url.pathname.endsWith('/canvas') ? null : [])
})

async function heading(text) {
  await page.getByRole('heading', { name: text, exact: true }).waitFor()
}

const productCards = () => page.getByRole('list', { name: '商品卡片列表' }).getByRole('listitem')
const mainNav = page.getByRole('navigation', { name: '主导航' })
const productsLink = mainNav.getByRole('link', { name: '产品库', exact: true })


try {
  await page.goto(`${baseUrl}/products`, { waitUntil: 'networkidle' })
  await heading('商品库')
  assert.equal(await productsLink.getAttribute('aria-current'), 'page')
  assert.equal(await page.getByTestId('workspace-topbar-context').count(), 0)
  assert.equal(await productCards().count(), 20)
  const firstRow = await productCards().evaluateAll(cards => cards.slice(0, 5).map(card => {
    const rect = card.getBoundingClientRect()
    return { x: rect.x, y: rect.y }
  }))
  assert.equal(firstRow.length, 5)
  assert.equal(firstRow.every(rect => Math.abs(rect.y - firstRow[0].y) < 2), true, '前五件商品应位于同一行')
  assert.equal(firstRow.every((rect, index) => index === 0 || rect.x > firstRow[index - 1].x), true, '前五件商品应从左到右排列')
  const gridColumnCount = () => page.getByRole('list', { name: '商品卡片列表' }).evaluate(list => getComputedStyle(list).gridTemplateColumns.split(' ').length)
  assert.equal(await gridColumnCount(), 5, '1440px 宽屏保持五列')
  await page.setViewportSize({ width: 2306, height: 960 })
  assert.equal(await gridColumnCount(), 8, '2306px 超宽屏显示八列')
  await page.setViewportSize({ width: 1440, height: 960 })
  const categoryFilter = page.getByRole('combobox', { name: '筛选产品分类' })
  const colorFilter = page.getByRole('combobox', { name: '筛选商品颜色' })
  await categoryFilter.focus()
  await categoryFilter.getByRole('option', { name: '办公家具 / 办公椅', exact: true }).waitFor({ state: 'attached' })
  await categoryFilter.selectOption({ label: '办公家具 / 办公椅' })
  await productCards().nth(1).waitFor()
  assert.equal(await productCards().count(), 2)
  await colorFilter.selectOption('深木色')
  await productCards().getByText('人体工学椅 3', { exact: true }).waitFor()
  assert.equal(await productCards().count(), 1)
  await page.getByLabel('搜索商品名称或编码').fill('RSDP-001')
  await heading('没有找到匹配的商品')
  await page.getByRole('button', { name: '清空筛选', exact: true }).click()
  await productCards().nth(19).waitFor()
  assert.equal(await productCards().count(), 20)
  assert.equal(await categoryFilter.inputValue(), '')
  assert.equal(await colorFilter.inputValue(), '')
  const catalogFavorites = page.getByRole('complementary', { name: '产品库收藏' })
  const openCollections = catalogFavorites.getByRole('link', { name: '打开收藏中心' })
  await openCollections.waitFor()
  assert.equal(await openCollections.getAttribute('href'), '/favorites?tab=project')
  assert.equal(await catalogFavorites.getByRole('button', { name: '项目收藏', exact: true }).getAttribute('aria-pressed'), 'true')
  const firstCard = productCards().first()
  await firstCard.getByText('人体工学椅 1', { exact: true }).waitFor()
  await firstCard.getByText('RSDP-001', { exact: true }).waitFor()
  await firstCard.getByText('¥ 980.00', { exact: true }).waitFor()
  await firstCard.getByText('非在售', { exact: true }).waitFor()
  await firstCard.getByText('暂无商品图片', { exact: true }).waitFor()
  await firstCard.getByRole('link', { name: '查看人体工学椅 1详情' }).first().waitFor()

  const favoriteButton = firstCard.getByRole('button', { name: '个人收藏人体工学椅 1' })
  const projectFavoriteButton = firstCard.getByRole('button', { name: '项目收藏人体工学椅 1' })
  await firstCard.getByText('人体工学椅 1', { exact: true }).waitFor()
  assert.equal(await favoriteButton.evaluate(button => getComputedStyle(button).opacity), '0', '爱心默认隐藏')
  assert.equal(await projectFavoriteButton.evaluate(button => getComputedStyle(button).opacity), '0', '项目收藏按钮默认隐藏')
  await firstCard.getByRole('link', { name: '查看人体工学椅 1详情' }).first().hover()
  await page.waitForTimeout(250)
  assert.equal(await favoriteButton.evaluate(button => getComputedStyle(button).opacity), '1', '悬停商品图显示爱心')
  assert.equal(await projectFavoriteButton.evaluate(button => getComputedStyle(button).opacity), '1', '悬停商品图显示项目收藏按钮')
  assert.equal(await favoriteButton.getAttribute('aria-pressed'), 'false')
  assert.equal(await favoriteButton.locator('svg').count(), 1)
  await page.mouse.move(900, 150)
  await page.waitForTimeout(250)
  assert.equal(await favoriteButton.evaluate(button => getComputedStyle(button).opacity), '0', '移出商品卡后按钮重新隐藏')
  await projectFavoriteButton.focus()
  await page.waitForTimeout(250)
  assert.equal(await projectFavoriteButton.evaluate(button => getComputedStyle(button).opacity), '1', '键盘聚焦时显示收藏按钮')

  await projectFavoriteButton.click()
  const favoriteDialog = page.getByRole('dialog', { name: '收藏商品' })
  await favoriteDialog.waitFor()
  assert.equal(await favoriteDialog.getByRole('tab', { name: '项目收藏' }).getAttribute('aria-selected'), 'true')
  await favoriteDialog.getByText('默认办公项目', { exact: true }).waitFor()
  const currentProjectRadio = favoriteDialog.getByRole('radio', { name: /默认办公项目/ })
  assert.equal(await currentProjectRadio.isChecked(), true, '当前项目默认选中')
  assert.equal(await favoriteDialog.getByRole('radio').count(), 2, '显示当前用户全部可访问项目')
  assert.equal(await favoriteDialog.getByRole('radio', { name: /第二个办公项目/ }).count(), 1)
  assert.equal(await favoriteDialog.getByText('仅自己可见', { exact: true }).count(), 0, '项目行不显示仅自己可见')
  await favoriteDialog.getByRole('button', { name: /确定收藏/ }).click()
  await favoriteDialog.waitFor({ state: 'hidden' })
  assert.deepEqual(projectFavoriteSaveRequests[0], { projectId: 'OFFICE-PROJECT-001', rspuId: 'PRODUCT-1', selected: true })
  assert.equal(await projectFavoriteButton.getAttribute('aria-pressed'), 'true')
  assert.equal(await favoriteButton.getAttribute('aria-pressed'), 'false', '项目收藏不改变个人爱心状态')
  await catalogFavorites.getByRole('link', { name: '人体工学椅 1', exact: true }).waitFor()
  await catalogFavorites.getByRole('button', { name: '个人收藏', exact: true }).click()
  assert.equal(await openCollections.getAttribute('href'), '/favorites')
  await catalogFavorites.getByText('当前目录范围内暂无收藏商品。', { exact: true }).waitFor()
  assert.equal(await catalogFavorites.getByRole('link', { name: '人体工学椅 1', exact: true }).count(), 0)

  await projectFavoriteButton.click()
  const secondProjectRadio = favoriteDialog.getByRole('radio', { name: /第二个办公项目/ })
  await secondProjectRadio.waitFor()
  delayNextProjectFavoriteRead = true
  await secondProjectRadio.check()
  assert.equal(await secondProjectRadio.isChecked(), true, '目标项目立即选中')
  const projectReadingButton = favoriteDialog.getByRole('button', { name: '读取中…', exact: true })
  await projectReadingButton.waitFor()
  assert.equal(await projectReadingButton.isDisabled(), true, '读取所选项目时保存按钮显示稳定反馈')
  await favoriteDialog.getByRole('button', { name: '确定收藏', exact: true }).click()
  await favoriteDialog.waitFor({ state: 'hidden' })
  assert.deepEqual(projectFavoriteSaveRequests[1], { projectId: 'OFFICE-PROJECT-002', rspuId: 'PRODUCT-1', selected: true })
  assert.equal(await projectFavoriteButton.getAttribute('aria-pressed'), 'true', '保存到其他项目不改变当前项目按钮状态')
  await projectFavoriteButton.click()
  await secondProjectRadio.waitFor()
  await secondProjectRadio.check()
  await favoriteDialog.getByRole('button', { name: '取消收藏', exact: true }).click()
  await favoriteDialog.waitFor({ state: 'hidden' })

  failNextProjectFavoriteSave = true
  await projectFavoriteButton.click()
  await currentProjectRadio.waitFor()
  assert.equal(await currentProjectRadio.isChecked(), true, '重新打开时仍默认当前项目')
  await favoriteDialog.getByRole('button', { name: '取消收藏', exact: true }).click()
  await favoriteDialog.getByRole('alert').waitFor()
  assert.equal(await favoriteDialog.isVisible(), true, '项目收藏保存失败保留弹窗')
  assert.equal(await projectFavoriteButton.getAttribute('aria-pressed'), 'true', '项目收藏保存失败不改变旧状态')
  await favoriteDialog.getByRole('button', { name: '取消', exact: true }).click()
  await favoriteDialog.waitFor({ state: 'hidden' })

  await favoriteButton.click()
  await favoriteDialog.waitFor()
  assert.equal(await favoriteDialog.getByRole('tab', { name: '个人收藏' }).getAttribute('aria-selected'), 'true')
  await favoriteDialog.getByText('人体工学椅 1', { exact: true }).waitFor()
  await favoriteDialog.getByText('RSDP-001', { exact: true }).waitFor()
  const defaultFolder = favoriteDialog.getByRole('checkbox', { name: /默认收藏夹/ })
  await defaultFolder.waitFor()
  assert.equal(await defaultFolder.isChecked(), false)
  await favoriteDialog.getByRole('button', { name: '新建收藏夹' }).click()
  await favoriteDialog.getByLabel('新建收藏夹名称').fill('我的收藏')
  delayNextFavoriteCreate = true
  await favoriteDialog.getByRole('button', { name: '创建收藏夹' }).click()
  assert.equal(await favoriteDialog.getByRole('button', { name: /确定收藏/ }).isDisabled(), true, '新建分组进行中不能保存旧勾选')
  const customFolder = favoriteDialog.getByRole('checkbox', { name: /我的收藏/ })
  await customFolder.waitFor()
  assert.equal(await customFolder.isChecked(), true)
  await defaultFolder.check()
  await favoriteDialog.getByRole('button', { name: /确定收藏/ }).click()
  await favoriteDialog.waitFor({ state: 'hidden' })
  assert.deepEqual(favoriteSaveRequests[0], { rspuId: 'PRODUCT-1', folderIds: ['F-CUSTOM-1', 'F-DEFAULT'] })
  assert.equal(await favoriteButton.getAttribute('aria-pressed'), 'true')
  await catalogFavorites.getByRole('link', { name: '人体工学椅 1', exact: true }).waitFor()

  failNextFavoriteSave = true
  await favoriteButton.click()
  const cancelFavoriteDialog = page.getByRole('dialog', { name: '取消收藏' })
  await cancelFavoriteDialog.waitFor()
  assert.equal(await cancelFavoriteDialog.getByRole('button', { name: '调整收藏夹' }).count(), 1, '已收藏商品点爱心先进确认弹窗，提供调整收藏夹入口')
  await cancelFavoriteDialog.getByRole('button', { name: '调整收藏夹' }).click()
  await favoriteDialog.waitFor()
  await favoriteDialog.getByRole('checkbox', { name: /默认收藏夹/ }).uncheck()
  await favoriteDialog.getByRole('button', { name: /确定收藏/ }).click()
  await favoriteDialog.getByRole('alert').waitFor()
  assert.equal(await favoriteDialog.isVisible(), true)
  assert.equal(await favoriteButton.getAttribute('aria-pressed'), 'true')
  await favoriteDialog.getByRole('button', { name: '取消', exact: true }).click()
  await favoriteDialog.waitFor({ state: 'hidden' })

  const favoriteSaveCount = favoriteSaveRequests.length
  failNextFavoriteFolders = true
  const failedFoldersResponse = page.waitForResponse(response => response.url().includes('/api/v1/office/favorites/folders') && response.request().method() === 'GET' && response.status() === 503)
  await favoriteButton.click()
  await cancelFavoriteDialog.waitFor()
  await cancelFavoriteDialog.getByRole('button', { name: '调整收藏夹' }).click()
  await favoriteDialog.waitFor()
  await failedFoldersResponse
  await favoriteDialog.getByRole('alert').waitFor()
  failNextFavoriteFolders = false
  assert.equal(await favoriteDialog.getByRole('button', { name: /确定收藏/ }).isDisabled(), true, '分组加载失败时不能保存空选择')
  assert.equal(favoriteSaveRequests.length, favoriteSaveCount)
  await favoriteDialog.getByRole('button', { name: '取消', exact: true }).click()
  await favoriteDialog.waitFor({ state: 'hidden' })

  await favoriteButton.click()
  await cancelFavoriteDialog.waitFor()
  await cancelFavoriteDialog.getByRole('button', { name: '调整收藏夹' }).click()
  await favoriteDialog.getByRole('checkbox', { name: /默认收藏夹/ }).waitFor()
  await favoriteDialog.getByRole('checkbox', { name: /我的收藏/ }).uncheck()
  delayNextFavoriteSave = true
  await favoriteDialog.getByRole('button', { name: /确定收藏/ }).click()
  await page.keyboard.press('Escape')
  assert.equal(await favoriteDialog.isVisible(), true, '保存中按 Esc 不应关闭弹窗')
  await favoriteDialog.waitFor({ state: 'hidden' })

  delayedFavoriteRspuId = 'PRODUCT-1'
  await favoriteButton.click()
  await cancelFavoriteDialog.waitFor()
  await cancelFavoriteDialog.getByRole('button', { name: '调整收藏夹' }).click()
  await favoriteDialog.getByText('正在加载收藏夹…', { exact: true }).waitFor()
  await favoriteDialog.getByRole('button', { name: '取消', exact: true }).click()
  await favoriteDialog.waitFor({ state: 'hidden' })
  const secondFavoriteButton = productCards().nth(1).getByRole('button', { name: '个人收藏简约办公桌 2' })
  await secondFavoriteButton.click()
  const secondDefaultFolder = favoriteDialog.getByRole('checkbox', { name: /默认收藏夹/ })
  await secondDefaultFolder.waitFor()
  await page.waitForTimeout(500)
  await favoriteDialog.getByText('简约办公桌 2', { exact: true }).waitFor()
  assert.equal(await secondDefaultFolder.isChecked(), false, '商品 A 的迟到响应不能覆盖商品 B 的勾选')
  await favoriteDialog.getByRole('button', { name: '取消', exact: true }).click()
  await favoriteDialog.waitFor({ state: 'hidden' })

  await page.reload({ waitUntil: 'networkidle' })
  const personalRequestsBeforeProjectSwitch = personalFavoriteIdsRequests
  await page.evaluate(async () => {
    const router = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$router
    await router.push('/products?floorId=OFFICE-PROJECT-002-FLOOR')
  })
  await page.waitForURL('**/products?floorId=OFFICE-PROJECT-002-FLOOR')
  await page.waitForFunction(() => document.querySelector('[aria-label="项目收藏人体工学椅 1"]')?.getAttribute('aria-pressed') === 'false')
  await page.waitForTimeout(300)
  assert.equal(await favoriteButton.getAttribute('aria-pressed'), 'true', '原地切换到项目 B 后个人爱心仍保留')
  assert.equal(personalFavoriteIdsRequests, personalRequestsBeforeProjectSwitch, '原地项目切换不得重新请求个人收藏')
  await page.evaluate(async () => {
    const router = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$router
    await router.push('/products?floorId=OFFICE-PROJECT-001')
  })
  await page.waitForURL('**/products?floorId=OFFICE-PROJECT-001')
  await page.waitForFunction(() => document.querySelector('[aria-label="项目收藏人体工学椅 1"]')?.getAttribute('aria-pressed') === 'true')
  await page.waitForTimeout(300)
  assert.equal(await favoriteButton.getAttribute('aria-pressed'), 'true')
  assert.equal(personalFavoriteIdsRequests, personalRequestsBeforeProjectSwitch, '返回项目 A 也不得重新请求个人收藏')

  if (!favoriteItems.some(item => item.rspuId === 'PRODUCT-2')) favoriteItems.push({ folderId: 'F-DEFAULT', rspuId: 'PRODUCT-2' })
  if (!projectFavoriteItems.some(item => item.projectId === 'OFFICE-PROJECT-001' && item.rspuId === 'PRODUCT-2')) {
    projectFavoriteItems.push({ projectId: 'OFFICE-PROJECT-001', rspuId: 'PRODUCT-2' })
  }
  delayNextFavoriteIds = true
  delayNextProjectFavoriteIds = true
  await page.goto(`${baseUrl}/products?floorId=OFFICE-PROJECT-001`, { waitUntil: 'domcontentloaded' })
  await firstCard.getByText('人体工学椅 1', { exact: true }).waitFor()
  await projectFavoriteButton.click()
  await currentProjectRadio.waitFor()
  await favoriteDialog.getByRole('button', { name: '取消收藏', exact: true }).click()
  await favoriteDialog.waitFor({ state: 'hidden' })
  const secondProjectFavoriteButton = productCards().nth(1).getByRole('button', { name: '项目收藏简约办公桌 2' })
  await page.waitForFunction(() => document.querySelector('[aria-label="项目收藏简约办公桌 2"]')?.getAttribute('aria-pressed') === 'true')
  assert.equal(await secondProjectFavoriteButton.getAttribute('aria-pressed'), 'true', '项目保存后重新核验完整列表，不丢弃其他收藏')
  await page.waitForFunction(() => document.querySelector('[aria-label="个人收藏人体工学椅 1"]')?.getAttribute('aria-pressed') === 'true')
  await favoriteButton.click()
  await cancelFavoriteDialog.waitFor()
  await cancelFavoriteDialog.getByRole('button', { name: '调整收藏夹' }).click()
  await favoriteDialog.getByRole('checkbox', { name: /默认收藏夹/ }).waitFor()
  await favoriteDialog.getByRole('button', { name: /确定收藏/ }).click()
  await favoriteDialog.waitFor({ state: 'hidden' })
  await page.waitForFunction(() => document.querySelector('[aria-label="个人收藏简约办公桌 2"]')?.getAttribute('aria-pressed') === 'true')
  assert.equal(await secondFavoriteButton.getAttribute('aria-pressed'), 'true', '个人保存后重新核验完整列表，不丢弃其他收藏')

  // 实心爱心点击 → 取消收藏确认弹窗（不再直接打开收藏夹弹窗）
  const personalSaveCountBeforeConfirm = favoriteSaveRequests.length
  await secondFavoriteButton.click()
  await cancelFavoriteDialog.waitFor()
  assert.equal(await cancelFavoriteDialog.getByText('所有个人收藏夹', { exact: false }).count(), 1, '确认弹窗说明将从所有个人收藏夹移除')
  assert.equal(await cancelFavoriteDialog.getByRole('button', { name: '调整收藏夹' }).count(), 1, '确认弹窗提供调整收藏夹入口')
  await cancelFavoriteDialog.getByRole('button', { name: '取消', exact: true }).click()
  await cancelFavoriteDialog.waitFor({ state: 'hidden' })
  assert.equal(favoriteSaveRequests.length, personalSaveCountBeforeConfirm, '点取消不发送保存请求')
  assert.equal(await secondFavoriteButton.getAttribute('aria-pressed'), 'true', '取消后爱心保持实心')

  await secondFavoriteButton.click()
  await cancelFavoriteDialog.waitFor()
  await cancelFavoriteDialog.getByRole('button', { name: '确认取消收藏' }).click()
  await cancelFavoriteDialog.waitFor({ state: 'hidden' })
  assert.deepEqual(favoriteSaveRequests.at(-1), { rspuId: 'PRODUCT-2', folderIds: [] }, '确认后保存空收藏夹列表')
  await page.waitForFunction(() => document.querySelector('[aria-label="个人收藏简约办公桌 2"]')?.getAttribute('aria-pressed') === 'false')
  assert.equal(await secondFavoriteButton.getAttribute('aria-pressed'), 'false', '确认后爱心变回空心')

  favoriteItems.push({ folderId: 'F-DEFAULT', rspuId: 'PRODUCT-2' })
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForFunction(() => document.querySelector('[aria-label="个人收藏简约办公桌 2"]')?.getAttribute('aria-pressed') === 'true')
  failNextFavoriteSave = true
  await secondFavoriteButton.click()
  await cancelFavoriteDialog.waitFor()
  await cancelFavoriteDialog.getByRole('button', { name: '确认取消收藏' }).click()
  await cancelFavoriteDialog.getByRole('alert').waitFor()
  assert.equal(await cancelFavoriteDialog.isVisible(), true, '取消收藏失败保留确认弹窗')
  assert.equal(await secondFavoriteButton.getAttribute('aria-pressed'), 'true', '失败不改变爱心状态')
  await cancelFavoriteDialog.getByRole('button', { name: '取消', exact: true }).click()
  await cancelFavoriteDialog.waitFor({ state: 'hidden' })

  delayNextFavoriteSave = true
  await secondFavoriteButton.click()
  await cancelFavoriteDialog.waitFor()
  await cancelFavoriteDialog.getByRole('button', { name: '确认取消收藏' }).click()
  await page.keyboard.press('Escape')
  assert.equal(await cancelFavoriteDialog.isVisible(), true, '保存中按 Esc 不关闭确认弹窗')
  await cancelFavoriteDialog.waitFor({ state: 'hidden' })
  await page.waitForFunction(() => document.querySelector('[aria-label="个人收藏简约办公桌 2"]')?.getAttribute('aria-pressed') === 'false')

  favoriteItems.push({ folderId: 'F-DEFAULT', rspuId: 'PRODUCT-2' })
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForFunction(() => document.querySelector('[aria-label="个人收藏简约办公桌 2"]')?.getAttribute('aria-pressed') === 'true')
  await secondFavoriteButton.click()
  await cancelFavoriteDialog.waitFor()
  await cancelFavoriteDialog.getByRole('button', { name: '调整收藏夹' }).click()
  await favoriteDialog.waitFor()
  assert.equal(await favoriteDialog.getByRole('tab', { name: '个人收藏' }).getAttribute('aria-selected'), 'true', '调整收藏夹打开弹窗个人页签')
  assert.equal(await favoriteDialog.getByRole('checkbox', { name: /默认收藏夹/ }).isChecked(), true)
  await favoriteDialog.getByRole('button', { name: '取消', exact: true }).click()
  await favoriteDialog.waitFor({ state: 'hidden' })

  const catalogSwitch = page.getByRole('switch', { name: '公共库' })
  await catalogSwitch.waitFor()
  assert.equal(await catalogSwitch.getAttribute('aria-checked'), 'true')
  assert.equal(await page.getByRole('link', { name: '手工录入' }).count(), 0)
  rejectCatalogChange = true
  await catalogSwitch.click()
  await page.getByRole('alert').waitFor()
  assert.equal(await catalogSwitch.getAttribute('aria-checked'), 'true')
  assert.equal(await productCards().count(), 20)
  rejectCatalogChange = false
  includeLocalFixture = true
  const localCard = page.getByRole('link', { name: `查看${localProduct.name}详情` })
  const sharedCard = page.getByRole('link', { name: '查看人体工学椅 1详情' })
  await page.getByRole('button', { name: '刷新商品列表' }).click()
  await localCard.waitFor()
  await sharedCard.waitFor()
  assert.equal(await productCards().count(), 20)
  await Promise.all([
    page.waitForResponse(response => new URL(response.url()).searchParams.get('search') === 'local-001'),
    page.getByLabel('搜索商品名称或编码').fill('local-001'),
  ])
  await sharedCard.waitFor({ state: 'hidden' })
  await localCard.waitFor()
  assert.equal(await productCards().count(), 1)
  await page.getByRole('button', { name: '清空条件' }).click()
  await sharedCard.waitFor()

  const catalogRequestsBeforeClose = catalogPageRequests
  await catalogSwitch.click()
  await page.getByRole('switch', { name: '公共库', checked: false }).waitFor()
  await localCard.waitFor()
  assert.equal(await catalogSwitch.getAttribute('aria-checked'), 'false')
  assert.equal(await productCards().count(), 1)
  assert.equal(await sharedCard.count(), 0)
  assert.equal(catalogPageRequests, catalogRequestsBeforeClose)
  await page.getByRole('link', { name: '管理本地商品' }).waitFor()
  catalogDealer = false
  await page.reload({ waitUntil: 'networkidle' })
  await localCard.waitFor()
  assert.equal(await catalogSwitch.count(), 0)
  assert.equal(await page.getByRole('link', { name: '管理本地商品' }).count(), 0)
  const localPersonal = page.getByRole('button', { name: `个人收藏${localProduct.name}` })
  await localPersonal.click()
  await favoriteDialog.getByRole('checkbox', { name: /默认收藏夹/ }).check()
  await favoriteDialog.getByRole('button', { name: /确定收藏/ }).click()
  await favoriteDialog.waitFor({ state: 'hidden' })
  assert.deepEqual(favoriteSaveRequests.at(-1), { rspuId: localCollectionId, folderIds: ['F-DEFAULT'] })
  const localProjectFavorite = page.getByRole('button', { name: `项目收藏${localProduct.name}` })
  await localProjectFavorite.click()
  await favoriteDialog.getByRole('button', { name: '确定收藏', exact: true }).click()
  await favoriteDialog.waitFor({ state: 'hidden' })
  assert.equal(projectFavoriteSaveRequests.at(-1).rspuId, localCollectionId)
  await page.getByRole('button', { name: `加入组合 ${localProduct.name}` }).click()
  await page.getByRole('combobox', { name: '选择商品规格' }).selectOption('LARGE')
  await page.getByLabel('组合名称').fill('本地茶几组合')
  await page.getByRole('button', { name: '加入规格' }).click()
  await page.getByRole('button', { name: '保存组合' }).click()
  await page.getByText('组合已保存', { exact: true }).waitFor()
  assert.equal(savedBundles[0].items[0].rspuId, localCollectionId)
  assert.equal(savedBundles[0].items[0].variantId, 'LARGE')
  await page.reload({ waitUntil: 'networkidle' })
  assert.equal(await localPersonal.getAttribute('aria-pressed'), 'true')
  assert.equal(await localProjectFavorite.getAttribute('aria-pressed'), 'true')
  const bundlesPanel = page.getByRole('region', { name: '自选商品组合' })
  await bundlesPanel.getByRole('button', { name: /本地茶几组合/ }).click()
  assert.equal(await bundlesPanel.getByRole('link', { name: localProduct.name }).getAttribute('href'),
    `/products/local/${localProduct.id}?from=catalog`)
  await page.goto(`${baseUrl}/favorites`)
  const savedLocalCard = page.getByRole('list', { name: '收藏商品列表' }).getByRole('link', { name: `查看${localProduct.name}详情` }).first()
  await savedLocalCard.waitFor()
  assert.equal(await savedLocalCard.getAttribute('href'), `/products/local/${localProduct.id}?from=catalog`)
  await page.goto(`${baseUrl}/products`)
  await localCard.waitFor()
  await page.getByLabel('搜索商品名称或编码').fill('无匹配型号')
  await heading('没有找到匹配的商品')
  await page.getByLabel('搜索商品名称或编码').fill('local-001')
  await localCard.waitFor()
  await localCard.click()
  await heading(localProduct.name)
  const localDimensions = page.getByTestId('catalog-product-dimensions')
  assert.equal(await localDimensions.innerText(), '1200 × 600 × 450 mm')
  await page.getByRole('combobox', { name: '选择规格' }).selectOption('LARGE')
  assert.equal(await localDimensions.innerText(), '1400 × 600 × 450 mm')
  await page.getByText('碳晶板', { exact: true }).waitFor()
  const secondaryImage = page.getByRole('button', { name: '查看商品图片 2' })
  await secondaryImage.click()
  assert.equal(await secondaryImage.getAttribute('aria-pressed'), 'true')
  const enlargeImage = page.getByRole('button', { name: `放大${localProduct.name}图片` })
  await enlargeImage.waitFor()
  await enlargeImage.focus()
  await page.keyboard.press('Enter')
  const zoomDialog = page.getByRole('dialog', { name: `${localProduct.name}图片完整预览` })
  await zoomDialog.waitFor()
  await page.keyboard.press('Escape')
  await zoomDialog.waitFor({ state: 'hidden' })
  assert.equal(await enlargeImage.evaluate(element => document.activeElement === element), true)
  await page.setViewportSize({ width: 390, height: 900 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  assert.equal(await localDimensions.innerText(), '1400 × 600 × 450 mm')
  await page.setViewportSize({ width: 1440, height: 960 })
  assert.equal(await page.getByRole('link', { name: '编辑商品' }).count(), 0)
  assert.equal(await page.getByLabel('宽（mm）').count(), 0)
  await page.waitForFunction(name => Array.from(document.images).some(img => img.alt === name && img.naturalWidth > 0), localProduct.name + '图片')
  assert.equal(catalogPageRequests, catalogRequestsBeforeClose)
  await page.goto(`${baseUrl}/products/local?edit=${localProduct.id}`)
  await page.getByRole('alert').filter({ hasText: '当前账号没有商品编辑权限' }).waitFor()
  assert.equal(await page.getByRole('button', { name: '保存', exact: true }).count(), 0)
  staffCanEditLocalProducts = true
  await page.goto(`${baseUrl}/products/local/${localProduct.id}?from=catalog`)
  await page.getByRole('link', { name: '编辑商品' }).click()
  await heading('编辑商品')
  assert.equal(await page.getByLabel('宽（mm）').first().inputValue(), '1200')
  assert.equal(await page.getByLabel('宽（mm）').first().isEnabled(), true)
  await page.getByRole('button', { name: '保存', exact: true }).waitFor()
  staffCanEditLocalProducts = false
  await page.goto(`${baseUrl}/products`)
  await localCard.waitFor()
  assert.equal(await sharedCard.count(), 0)
  catalogDealer = true
  await page.reload({ waitUntil: 'networkidle' })
  await catalogSwitch.click()
  await sharedCard.waitFor()
  await localCard.waitFor()
  assert.equal(await catalogSwitch.getAttribute('aria-checked'), 'true')
  assert.deepEqual(catalogUpdates, [false, false, true])
  catalogDealer = false
  await page.reload({ waitUntil: 'networkidle' })
  assert.equal(await catalogSwitch.count(), 0)
  await localCard.waitFor()
  await sharedCard.waitFor()

  const waitForCatalogPage = (pageNumber, size, search = null) => page.waitForResponse(response => {
    const url = new URL(response.url())
    return url.pathname === '/api/v1/products/browse' && url.searchParams.get('page') === String(pageNumber) &&
      url.searchParams.get('size') === String(size) && url.searchParams.get('search') === search
  })
  await Promise.all([waitForCatalogPage(1, 10), page.getByLabel('每页显示数量').selectOption('10')])
  await productCards().nth(9).waitFor()
  await Promise.all([waitForCatalogPage(2, 10), page.getByRole('button', { name: '下一页', exact: true }).click()])
  await page.getByText('11–20', { exact: true }).waitFor()
  assert.equal(await productCards().count(), 10)
  await Promise.all([waitForCatalogPage(1, 10, 'RSDP-002'), page.getByLabel('搜索商品名称或编码').fill('RSDP-002')])
  await page.getByRole('link', { name: '查看简约办公桌 2详情' }).first().waitFor()
  assert.equal(await productCards().count(), 1)
  await page.getByRole('link', { name: '查看简约办公桌 2详情' }).first().click()
  await heading('简约办公桌 2')
  await page.getByText('在售', { exact: true }).waitFor()
  await page.getByText('¥ 1,080.00', { exact: true }).waitFor()
  const sharedSpecs = page.getByRole('region', { name: '商品规格', exact: true })
  const sharedDimensions = sharedSpecs.getByTestId('catalog-product-dimensions')
  assert.equal(await sharedDimensions.innerText(), '1200 × 600 × 450 mm')
  for (const value of ['PB', '包数', '体积（CBM）', '0.36', '重量（原表值）', '18', '有效']) {
    await sharedSpecs.getByText(value, { exact: true }).waitFor()
  }
  await sharedSpecs.getByRole('combobox', { name: '选择规格' }).selectOption('SUPPLIER')
  assert.equal(await sharedDimensions.innerText(), '供应商尺寸原文')
  assert.equal(await sharedSpecs.getByText('包数', { exact: true }).count(), 0)
  await page.getByRole('button', { name: '查看商品图片 2' }).click()
  await page.getByRole('button', { name: '放大简约办公桌 2图片' }).click()
  await page.getByRole('dialog', { name: '简约办公桌 2图片完整预览' }).waitFor()
  await page.keyboard.press('Escape')
  await page.getByText('说明与记录', { exact: true }).click()
  await page.getByText('测试配置说明', { exact: true }).waitFor()
  await page.getByText('更新时间', { exact: true }).waitFor()
  await page.setViewportSize({ width: 390, height: 900 })
  const infoBox = await page.getByRole('region', { name: '商品资料' }).boundingBox()
  const specBox = await sharedSpecs.boundingBox()
  assert.ok(specBox.y < infoBox.y, '窄屏优先展示公共商品规格')
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.setViewportSize({ width: 1440, height: 960 })
  assert.equal(await page.getByRole('link', { name: '编辑商品' }).count(), 0)
  assert.equal(await page.getByText('审核状态', { exact: true }).count(), 0)

  const detailRequests = []
  const recordDetailRequest = request => {
    if (request.method() === 'GET') detailRequests.push(new URL(request.url()).pathname)
  }
  page.on('request', recordDetailRequest)
  await page.reload({ waitUntil: 'networkidle' })
  await heading('简约办公桌 2')
  page.off('request', recordDetailRequest)
  const detailPath = `/api/v1${new URL(page.url()).pathname}`
  assert.equal(detailRequests.filter(path => path === detailPath).length, 1, '直达商品详情只加载一次')
  assert.ok(!detailRequests.some(path => path === '/api/v1/office/products' || /\/(?:canvas|cart|budgets)$/.test(path)), '商品详情无需加载楼层数据或项目预算')

  await page.getByRole('link', { name: '返回商品库' }).click()
  await page.getByLabel('搜索商品名称或编码').fill('不存在的商品')
  await heading('没有找到匹配的商品')
  await page.getByRole('button', { name: '清空筛选' }).click()
  await page.getByLabel('筛选商品状态').selectOption('active')
  await productCards().nth(14).waitFor()
  assert.equal(await productCards().count(), 15)
  await page.getByLabel('筛选商品状态').selectOption('')

  const workbenchLink = mainNav.getByRole('link', { name: '项目工作台', exact: true })
  await workbenchLink.click()
  await heading('项目工作台')
  const workbench = page.getByTestId('project-bench-overview')
  await workbench.getByRole('heading', { name: '默认办公项目', exact: true }).waitFor()
  assert.equal(await workbenchLink.getAttribute('aria-current'), 'page')
  assert.match(await workbench.getByTestId('project-reference-budget').innerText(), /124,331\.15/)
  await workbench.getByRole('button', { name: '项目需求录入与更新', exact: true }).click()
  await heading('客户需求备注')
  await page.getByPlaceholder('填写客户需求与注意事项').fill('回归测试项目需求')
  await page.getByRole('button', { name: '保存备注', exact: true }).click()
  await page.getByText('已保存。既有推荐和方案请结合新需求重新复核。', { exact: true }).waitFor()
  await page.getByRole('button', { name: '关闭客户需求', exact: true }).click()
  assert.match(await workbench.getByTestId('project-reference-budget').innerText(), /124,331\.15/)
  await page.getByRole('link', { name: '返回项目列表', exact: true }).click()
  await page.getByRole('region', { name: '项目列表', exact: true }).getByRole('button', { name: '进入项目 默认办公项目', exact: true }).click()
  const drawingLink = workbench.getByRole('link', { name: '上传或识别图纸', exact: true })
  await drawingLink.click()
  await heading('默认楼层 · 上传办公图纸')
  assert.deepEqual(await mainNav.getByRole('link').allTextContents(), ['项目工作台', '产品库', '空间设计', '产品方案', '项目清单'])
  const overviewStep = mainNav.getByRole('link', { name: '空间设计', exact: true })
  const schemeLink = mainNav.getByRole('link', { name: '产品方案', exact: true })
  await overviewStep.click()
  await page.getByRole('img', { name: '空间核对画布', exact: true }).waitFor()
  assert.equal(await overviewStep.getAttribute('aria-current'), 'page')
  const projectContext = page.getByTestId('workspace-topbar-context')
  await projectContext.getByText('默认办公项目 / 默认楼层', { exact: true }).waitFor()
  const topbar = page.getByTestId('workspace-topbar')
  for (const width of [1440, 1280, 390]) {
    await page.setViewportSize({ width, height: 960 })
    const bar = await topbar.boundingBox()
    const links = await mainNav.getByRole('link').evaluateAll(elements => elements.map(element => {
      const { left, right } = element.getBoundingClientRect()
      return { left, right }
    }))
    assert.equal(links.length, 5)
    assert.ok(links.every(link => link.left >= bar.x && link.right <= bar.x + bar.width), `navigation fits in topbar at ${width}px`)
    if (width >= 1280) {
      const canvas = await page.locator('.review-canvas-panel').boundingBox()
      const roomPanel = await page.locator('.review-rooms').boundingBox()
      assert.equal(Math.round(canvas.y), Math.round(bar.y + bar.height))
      assert.equal(Math.round(roomPanel.y), Math.round(bar.y + bar.height))
    } else {
      const sidebar = page.getByRole('button', { name: '项目侧栏', exact: true })
      assert.equal(await sidebar.getAttribute('aria-expanded'), 'false')
      await sidebar.click()
      assert.equal(await sidebar.getAttribute('aria-expanded'), 'true')
      await sidebar.click()
    }
  }
  await page.setViewportSize({ width: 1440, height: 960 })
  await workbenchLink.click()
  await heading('项目工作台')
  assert.equal(await workbenchLink.getAttribute('aria-current'), 'page')
  await drawingLink.click()
  await heading('默认楼层 · 上传办公图纸')
  await overviewStep.click()
  await page.getByRole('button', { name: '加载工装示例（可撤销）', exact: true }).click()
  const enterLayout = page.getByRole('button', { name: '进入家具排版 →', exact: true })
  await confirmAllReviews(page)
  assert.equal(await enterLayout.isEnabled(), true)
  await enterLayout.click()
  await page.waitForURL(/mode=design/)
  const designSidebar = page.getByTestId('design-workflow-sidebar')
  await designSidebar.waitFor()
  const designBar = await topbar.boundingBox()
  assert.equal(Math.round((await designSidebar.boundingBox()).y), Math.round(designBar.y + designBar.height))
  await topbar.getByRole('button', { name: /查看空间模板/ }).click()
  await page.getByRole('dialog', { name: '空间模板', exact: true }).waitFor()
  await page.getByRole('button', { name: '关闭空间模板', exact: true }).click()
  await productsLink.click()
  await heading('商品库')
  assert.equal(await projectContext.count(), 0)
  await schemeLink.click()
  await page.waitForURL(url => url.pathname === '/canvas' && url.searchParams.get('view') === 'products')
  await page.getByTestId('tab-product-selection').waitFor()
  assert.equal(await page.getByTestId('tab-product-selection').getAttribute('aria-selected'), 'true')
  assert.equal(await schemeLink.getAttribute('aria-current'), 'page')

  staffCanEditLocalProducts = true
  await productsLink.click()
  const localProductsLink = page.getByRole('link', { name: '管理本地商品', exact: true })
  await localProductsLink.waitFor()
  assert.equal(await localProductsLink.getAttribute('href'), '/products/local')
  assert.equal(await page.getByRole('link', { name: '去 RSDP 管理商品', exact: true }).count(), 0)
  assert.equal(await page.getByRole('link', { name: '手工录入' }).count(), 0)
  assert.equal(await page.getByRole('link', { name: '批量导入' }).count(), 0)

  await page.getByRole('link', { name: '导入记录', exact: true }).click()
  await heading('导入记录')
  await page.getByText(localImport.fileName, { exact: true }).waitFor()
  await page.getByText('3.00 MB', { exact: true }).waitFor()
  await page.getByRole('button', { name: '查看商品', exact: true }).click()
  assert.equal(await page.getByRole('link', { name: localProduct.name, exact: true }).getAttribute('href'), `/products/local/${localProduct.id}`)
  assert.equal(historicalImportRequests, 0, '当前 Office 记录不需要读取早期批次接口')
  await page.getByRole('searchbox', { name: '搜索 Excel 文件名' }).fill('无匹配')
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await heading('没有匹配的导入记录')
  await page.getByRole('button', { name: '清空搜索', exact: true }).click()
  await page.getByText(localImport.fileName, { exact: true }).waitFor()
  await page.getByText('早期导入记录', { exact: true }).click()
  await page.getByRole('button', { name: '查看批次' }).click()
  await page.getByText(/商品名称不能为空/).waitFor()
  assert.equal(await page.getByRole('link', { name: '去 RSDP 导入' }).count(), 0)
  await page.getByRole('link', { name: '导入 Excel', exact: true }).click()
  await heading('Excel 商品导入')
  assert.equal(new URL(page.url()).searchParams.get('view'), 'import')
  const capacityGauge = page.getByRole('progressbar', { name: '本地商品录入占用率' })
  await capacityGauge.waitFor()
  assert.equal(await capacityGauge.getAttribute('aria-valuenow'), '4999')
  assert.equal(await capacityGauge.getAttribute('aria-valuemax'), '5000')
  await page.getByLabel('选择 Excel 文件').setInputFiles({ name: '额度验证.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: Buffer.from('mock') })
  await page.getByText('超出剩余名额 1 件，请减少本次选择。', { exact: true }).waitFor()
  const confirmWorkbook = page.getByRole('button', { name: '确认导入', exact: true })
  assert.equal(await confirmWorkbook.isDisabled(), true)
  assert.equal(capacitySaves.length, 0)
  await page.getByRole('checkbox', { name: '选择 LIMIT-2' }).uncheck()
  await page.getByText('本次已选 1 件 · 导入后预计 100%', { exact: true }).waitFor()
  await confirmWorkbook.click()
  await page.getByText('已导入 1 件商品', { exact: true }).waitFor()
  await page.waitForFunction(() => document.querySelector('[aria-label="本地商品录入占用率"][role="progressbar"]')?.getAttribute('aria-valuenow') === '5000')
  assert.deepEqual(capacitySaves.map(products => products.map(product => product.factoryModel)), [['LIMIT-1']])
  await page.getByRole('checkbox', { name: '选择 LIMIT-2' }).check()
  assert.equal(await confirmWorkbook.isDisabled(), true)
  supplierCapacityPreview = true
  localCapacityUsed = 4999
  await page.getByLabel('选择 Excel 文件').setInputFiles({ name: '供应商额度验证.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: Buffer.from('mock') })
  await page.getByRole('radio', { name: '单一品类导入' }).check()
  await page.getByRole('checkbox', { name: '选择 LIMIT-1' }).check()
  await page.getByRole('checkbox', { name: '选择 LIMIT-2' }).check()
  await page.getByText('超出剩余名额 1 件，请减少本次选择。', { exact: true }).waitFor()
  assert.equal(await confirmWorkbook.isDisabled(), true)
  await page.getByRole('checkbox', { name: '选择 LIMIT-2' }).uncheck()
  await page.getByText('本次已选 1 件 · 导入后预计 100%', { exact: true }).waitFor()
  assert.equal(await confirmWorkbook.isEnabled(), true)

  for (const route of ['audit', 'manual', 'import']) {
    await page.goto(`${baseUrl}/products/${route}`, { waitUntil: 'networkidle' })
    await page.waitForURL('**/products')
    await heading('商品库')
  }
  assert.equal(legacyAuditDetailRequests, 0)
  assert.equal(importRequests, 0)

  includeLocalFixture = false
  listMode = 'error'
  await page.getByRole('button', { name: '刷新商品列表' }).click()
  await heading('商品暂时无法加载')
  assert.equal(await page.getByRole('button', { name: '导入商品', exact: true }).count(), 0)
  assert.match(await page.locator('[aria-live="polite"]').first().innerText(), /商品总数 —/)
  const details = page.locator('details').first()
  assert.equal(await details.getAttribute('open'), null)
  listMode = 'empty'
  await page.getByRole('button', { name: '重新加载' }).click()
  await heading('商品库还是空的')
  assert.match(await page.locator('[aria-live="polite"]').first().innerText(), /商品总数 0/)

  listMode = 'success'
  await page.getByRole('button', { name: '刷新商品列表' }).click()
  await page.getByRole('link', { name: '查看人体工学椅 1详情' }).first().waitFor()
  for (const width of [1024, 768, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, `Overflow at ${width}px`)
  }
  const collectionToggle = page.getByRole('button', { name: '收藏夹', exact: true })
  assert.equal(await collectionToggle.getAttribute('aria-expanded'), 'false')
  await collectionToggle.click()
  await catalogFavorites.waitFor()
  assert.equal(await collectionToggle.getAttribute('aria-expanded'), 'true')
  await page.getByRole('button', { name: '项目侧栏', exact: true }).click()
  await page.getByRole('link', { name: '导入记录', exact: true }).click()
  await heading('导入记录')
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  assert.deepEqual(pageErrors, [])
  console.log('Workflow E2E passed: navigation, context, filters, pagination, read-only details, legacy redirects, history, retry, empty/error states and responsive layouts.')
} finally {
  await browser.close()
}
