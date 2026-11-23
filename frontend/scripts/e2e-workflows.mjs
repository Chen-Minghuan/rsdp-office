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
let catalogManaged = false
let rejectCatalogChange = false
const catalogUpdates = []
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
  positioningLabel: '现代办公', primaryImageUrl: null, retailPrice: 980 + index * 100,
  referencePriceBand: '标准', productLevel: 'L2', warrantyYears: 3,
  sixDimTags: '{"A":["A1"],"B":["B2"]}', status: index % 3 ? 'active' : 'inactive',
  createdAt: '2026-10-12T08:00:00', updatedAt: '2026-10-12T09:00:00',
}))
const batchOverview = {
  batchId: 'BATCH-MOCK', fileName: '商品清单.xlsx', status: 'done',
  totalRows: 2, successCount: 1, failedCount: 1,
}
await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
const reviewMock = createReviewConfirmMock()
const defaultProject = {
  projectId: 'OFFICE-PROJECT-001', name: '默认办公项目', customerRequirements: '', requirementsRevision: 1,
  leader: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'LEADER' }, collaborators: [], referenceImages: [],
  floors: [{ projectId: 'OFFICE-PROJECT-001', floorId: 'OFFICE-PROJECT-001', name: '默认楼层', sortOrder: 1 }],
}
const secondProject = {
  projectId: 'OFFICE-PROJECT-002', name: '第二个办公项目', customerRequirements: '', requirementsRevision: 1,
  leader: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'LEADER' }, collaborators: [], referenceImages: [],
  floors: [{ projectId: 'OFFICE-PROJECT-002', floorId: 'OFFICE-PROJECT-002-FLOOR', name: '默认楼层', sortOrder: 1 }],
}
const availableProjects = [defaultProject, secondProject]
await context.route('**/api/v1/**', async route => {
  if (fulfillReviewConfirm(reviewMock, route)) return
  const request = route.request()
  const url = new URL(request.url())
  const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status, message: status === 200 ? '成功' : '服务暂不可用', data }) })
  if (url.pathname === '/api/v1/office/projects' && request.method() === 'GET') return respond(availableProjects)
  if (url.pathname === '/api/v1/products/capabilities') return respond({ managed: catalogManaged })
  if (url.pathname.startsWith('/api/v1/dicts/')) {
    return respond([{ dictCode: 'CODE-1', dictName: '测试字典项' }])
  }
  if (url.pathname === '/api/v1/products/import-batches' && request.method() === 'GET') return respond([batchOverview])
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
  if (url.pathname === '/api/v1/products' && request.method() === 'GET') {
    if (listMode === 'error') return respond(null, 404)
    return respond(listMode === 'empty' ? [] : catalogEnabled ? products : [])
  }
  if (url.pathname.startsWith('/api/v1/products/PRODUCT-') && request.method() === 'GET') {
    const rspuId = url.pathname.split('/').at(-1)
    const product = products.find(item => item.rspuId === rspuId)
    return respond({ ...product, images: [], variants: [
      { variantId: 'STRUCTURED', displayName: '默认规格', dimensionsText: '1200 × 600 × 450 mm', status: 'active' },
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
  const projectFavoriteMatch = url.pathname.match(/^\/api\/v1\/office\/projects\/([^/]+)\/favorites\/(PRODUCT-[^/]+)$/)
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
  if (url.pathname.startsWith('/api/v1/office/favorites/products/PRODUCT-') && request.method() === 'PUT') {
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

async function assertNoProjectSteps() {
  assert.equal(await page.getByRole('navigation', { name: '项目设计步骤' }).count(), 0)
}

const productCards = () => page.getByRole('list', { name: '商品卡片列表' }).getByRole('listitem')


try {
  await page.goto(`${baseUrl}/products`, { waitUntil: 'networkidle' })
  await heading('商品库')
  await assertNoProjectSteps()
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
  const firstCard = productCards().first()
  await firstCard.getByText('人体工学椅 1', { exact: true }).waitFor()
  await firstCard.getByText('RSDP-001', { exact: true }).waitFor()
  await firstCard.getByText('¥ 980.00', { exact: true }).waitFor()
  await firstCard.getByText('非在售', { exact: true }).waitFor()
  await firstCard.getByText(/2026[/-]10[/-]12/).waitFor()
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
  await catalogSwitch.click()
  await heading('商品库还是空的')
  assert.equal(await catalogSwitch.getAttribute('aria-checked'), 'false')
  await catalogSwitch.click()
  await page.getByRole('link', { name: '查看人体工学椅 1详情' }).first().waitFor()
  assert.equal(await catalogSwitch.getAttribute('aria-checked'), 'true')
  assert.deepEqual(catalogUpdates, [false, false, true])
  catalogDealer = false
  catalogManaged = true
  await page.reload({ waitUntil: 'networkidle' })
  assert.equal(await catalogSwitch.count(), 0)

  await page.getByLabel('每页显示数量').selectOption('10')
  assert.equal(await productCards().count(), 10)
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await page.waitForFunction(() => document.querySelectorAll('[aria-label="商品卡片列表"] > li').length === 10)
  assert.equal(await productCards().count(), 10)
  await page.getByLabel('搜索商品名称或编码').fill('RSDP-002')
  assert.equal(await productCards().count(), 1)
  await page.getByRole('link', { name: '查看简约办公桌 2详情' }).first().click()
  await heading('简约办公桌 2')
  await page.getByText('在售状态', { exact: true }).waitFor()
  await page.getByRole('cell', { name: '1200 × 600 × 450 mm', exact: true }).waitFor()
  await page.getByRole('cell', { name: '供应商尺寸原文', exact: true }).waitFor()
  assert.equal(await page.getByRole('cell', { name: '1400 × 600 mm', exact: true }).count(), 0)
  assert.equal(await page.getByText('审核状态', { exact: true }).count(), 0)
  await page.getByRole('link', { name: '返回商品库' }).click()
  await page.getByLabel('搜索商品名称或编码').fill('不存在的商品')
  await heading('没有找到匹配的商品')
  await page.getByRole('button', { name: '清空筛选' }).click()
  await page.getByLabel('筛选商品状态').selectOption('active')
  assert.equal(await productCards().count(), 15)
  await page.getByLabel('筛选商品状态').selectOption('')

  const workspaceNavigation = page.getByRole('navigation', { name: '工作区导航' })
  const sidebarWorkbench = workspaceNavigation.getByRole('link', { name: '项目工作台', exact: true })
  await sidebarWorkbench.click()
  await heading('项目工作台')
  await page.getByRole('region', { name: '项目列表', exact: true }).getByRole('button', { name: '进入项目 默认办公项目', exact: true }).first().click()
  await heading('默认楼层 · 上传办公图纸')
  assert.equal(await sidebarWorkbench.getAttribute('aria-current'), 'page')
  const projectFlow = page.getByRole('region', { name: '项目设计流程' })
  await projectFlow.getByText('空间设计', { exact: true }).waitFor()
  await projectFlow.getByText(/当前图纸：/).waitFor()
  const projectNav = page.getByRole('navigation', { name: '项目设计步骤' })
  assert.deepEqual(await projectNav.getByRole('link').evaluateAll(links => links.map(link => link.lastElementChild.textContent)), ['项目工作台', '空间总览', '布局选品', '预算清单'])
  const workbenchStep = projectNav.getByRole('link', { name: '1 项目工作台', exact: true })
  assert.equal(await workbenchStep.getAttribute('href'), await sidebarWorkbench.getAttribute('href'))
  assert.equal(await workbenchStep.getAttribute('aria-current'), 'step')
  const overviewStep = projectNav.getByRole('link', { name: '2 空间总览', exact: true })
  await overviewStep.click()
  await page.getByRole('img', { name: '空间核对画布', exact: true }).waitFor()
  assert.equal(await overviewStep.getAttribute('aria-current'), 'step')
  const topbar = page.getByTestId('workspace-topbar')
  assert.equal(await topbar.getByRole('navigation', { name: '项目设计步骤' }).count(), 1)
  for (const width of [1440, 1280, 390]) {
    await page.setViewportSize({ width, height: 960 })
    const bar = await topbar.boundingBox()
    const links = await projectNav.getByRole('link').evaluateAll(elements => elements.map(element => {
      const { left, right } = element.getBoundingClientRect()
      return { left, right }
    }))
    assert.equal(links.length, 4)
    assert.ok(links.every(link => link.left >= bar.x && link.right <= bar.x + bar.width), `steps fit in topbar at ${width}px`)
    if (width >= 1280) {
      const canvas = await page.locator('.review-canvas-panel').boundingBox()
      const roomPanel = await page.locator('.review-rooms').boundingBox()
      assert.ok(bar.height <= 65, `project steps share a single top row at ${width}px`)
      assert.equal(Math.round(canvas.y), Math.round(bar.y + bar.height))
      assert.equal(Math.round(roomPanel.y), Math.round(bar.y + bar.height))
    }
  }
  await page.setViewportSize({ width: 1440, height: 960 })
  await workbenchStep.click()
  await heading('项目工作台')
  assert.equal(await workbenchStep.getAttribute('aria-current'), 'step')
  await page.getByRole('link', { name: '默认楼层 · 上传识别图纸', exact: true }).click()
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
  await projectFlow.getByRole('button', { name: /查看当前空间模板/ }).click()
  await page.getByRole('dialog', { name: '空间模板', exact: true }).waitFor()
  await page.getByRole('button', { name: '关闭空间模板', exact: true }).click()
  const designUrl = page.url()
  await page.getByRole('navigation', { name: '工作区导航' }).getByRole('link', { name: '商品库', exact: true }).click()
  await heading('商品库')
  await assertNoProjectSteps()
  await page.getByRole('link', { name: '空间设计', exact: true }).click()
  await page.waitForURL(designUrl)
  assert.equal(page.url(), designUrl)

  await page.getByRole('navigation', { name: '工作区导航' }).getByRole('link', { name: '商品库', exact: true }).click()
  await page.getByRole('link', { name: '去 RSDP 管理商品' }).waitFor()
  assert.equal(await page.getByRole('link', { name: '手工录入' }).count(), 0)
  assert.equal(await page.getByRole('link', { name: '批量导入' }).count(), 0)

  await page.getByRole('link', { name: '导入记录', exact: true }).click()
  await heading('Office 历史导入记录')
  await page.getByRole('button', { name: '查看批次' }).click()
  await page.getByText(/商品名称不能为空/).waitFor()
  assert.equal(await page.getByRole('link', { name: '去 RSDP 导入' }).count(), 1)

  for (const route of ['audit', 'manual', 'import']) {
    await page.goto(`${baseUrl}/products/${route}`, { waitUntil: 'networkidle' })
    await page.waitForURL('**/products')
    await heading('商品库')
  }
  assert.equal(legacyAuditDetailRequests, 0)
  assert.equal(importRequests, 0)

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
  await page.getByRole('link', { name: '导入记录', exact: true }).click()
  await heading('Office 历史导入记录')
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  assert.deepEqual(pageErrors, [])
  console.log('Workflow E2E passed: navigation, context, filters, pagination, read-only details, legacy redirects, history, retry, empty/error states and responsive layouts.')
} finally {
  await browser.close()
}
