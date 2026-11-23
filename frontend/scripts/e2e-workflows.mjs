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
await context.route('**/api/v1/**', async route => {
  if (fulfillReviewConfirm(reviewMock, route)) return
  const request = route.request()
  const url = new URL(request.url())
  const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status, message: status === 200 ? '成功' : '服务暂不可用', data }) })
  if (url.pathname === '/api/v1/office/projects' && request.method() === 'GET') return respond([defaultProject])
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


try {
  await page.goto(`${baseUrl}/products`, { waitUntil: 'networkidle' })
  await heading('商品库')
  await assertNoProjectSteps()
  assert.equal(await page.locator('tbody tr').count(), 20)

  const catalogSwitch = page.getByRole('switch', { name: '公共库' })
  await catalogSwitch.waitFor()
  assert.equal(await catalogSwitch.getAttribute('aria-checked'), 'true')
  assert.equal(await page.getByRole('link', { name: '手工录入' }).count(), 0)
  rejectCatalogChange = true
  await catalogSwitch.click()
  await page.getByRole('alert').waitFor()
  assert.equal(await catalogSwitch.getAttribute('aria-checked'), 'true')
  assert.equal(await page.locator('tbody tr').count(), 20)
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
  assert.equal(await page.locator('tbody tr').count(), 10)
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await page.waitForFunction(() => document.querySelectorAll('tbody tr').length === 10)
  assert.equal(await page.locator('tbody tr').count(), 10)
  await page.getByLabel('搜索商品名称或编码').fill('RSDP-002')
  assert.equal(await page.locator('tbody tr').count(), 1)
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
  assert.equal(await page.locator('tbody tr').count(), 15)
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
