import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { chromium } from 'playwright'

const baseUrl = process.env.BASE_URL || 'http://localhost:3001'
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
const context = await browser.newContext({ viewport: { width: 1440, height: 960 }, acceptDownloads: true })
const page = await context.newPage()
const pageErrors = []
page.on('pageerror', error => pageErrors.push(error.message))
let listMode = 'success'
let importMode = 'partial'
let importRequests = 0
let overwriteRequested = false
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
const partialResult = {
  totalRows: 3, successCount: 1, failedCount: 1, skippedCount: 1,
  failures: [{ rowIndex: 3, externalCode: '=INVALID', rspuId: null, reason: '商品名称不能为空' }],
  warnings: [{ rowIndex: 4, externalCode: 'EXTERNAL-1', rspuId: 'PRODUCT-1', reason: '商品已存在，已跳过' }],
  cleansingReport: { cleanedRows: 3, issuesFound: 0, issues: [] },
}
const importPreview = {
  columns: [{ key: '商品名称', label: '商品名称', type: 'text' }],
  rows: [{ rowIndex: 2, fields: { '商品名称': '办公椅' }, skipped: false, groupKey: 'G1', action: 'insert', errors: [] }],
  productCount: 1, invalidRows: 0, skippedRows: 0,
  cleansingReport: { cleanedRows: 1, issuesFound: 0, issues: [] },
}

await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
await context.route('**/api/v1/**', async route => {
  const request = route.request()
  const url = new URL(request.url())
  const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status, message: status === 200 ? '成功' : '服务暂不可用', data }) })
  if (url.pathname.startsWith('/api/v1/products/') && request.method() === 'GET') {
    const found = products.find(item => url.pathname.endsWith('/' + item.rspuId))
    if (found) return respond(found)
  }
  if (url.pathname === '/api/v1/products' && request.method() === 'GET') {
    if (listMode === 'error') return respond(null, 404)
    return respond(listMode === 'empty' ? [] : products)
  }
  if (url.pathname.startsWith('/api/v1/products/PRODUCT-') && request.method() === 'GET') {
    const product = products.find(item => item.rspuId === url.pathname.split('/').at(-1))
    return respond({ ...product, images: [], variants: [] })
  }
  if (url.pathname === '/api/v1/products/import-template') return route.fulfill({ status: 200, contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', body: Buffer.from('test-template') })
  if (url.pathname === '/api/v1/products/import-preview') {
    assert.match(request.headers()['content-type'], /multipart\/form-data/)
    return respond(importPreview)
  }
  if (url.pathname === '/api/v1/products/import-preview/validate') return respond(importPreview)
  if (url.pathname === '/api/v1/products/import-confirm') {
    importRequests += 1
    overwriteRequested = /updateIfExists[\s\S]*true/.test(request.postData() || '')
    await new Promise(resolve => setTimeout(resolve, 600))
    if (importMode === 'error') return respond(null, 503)
    if (importMode === 'skipped') return respond({ totalRows: 1, successCount: 0, failedCount: 0, skippedCount: 1, failures: [], warnings: [{ rowIndex: 2, externalCode: 'EXTERNAL-1', rspuId: null, reason: '商品已存在，已跳过' }], cleansingReport: { cleanedRows: 0, issuesFound: 0, issues: [] } })
    return respond(partialResult)
  }
  return respond(url.pathname.endsWith('/OFFICE-PROJECT-001') || url.pathname.endsWith('/canvas') ? null : [])
})

async function heading(text) {
  await page.getByRole('heading', { name: text, exact: true }).waitFor()
}

async function assertNoProjectSteps() {
  assert.equal(await page.getByRole('navigation', { name: '项目设计步骤' }).count(), 0)
}

async function chooseFile(name = '商品清单.csv', content = '商品名称\n办公椅') {
  await page.getByLabel('选择商品数据文件').setInputFiles({ name, mimeType: 'text/csv', buffer: Buffer.from(content) })
}

try {
  await page.goto(`${baseUrl}/products`, { waitUntil: 'networkidle' })
  await heading('商品库')
  await assertNoProjectSteps()
  assert.equal(await page.locator('tbody tr').count(), 20)

  await page.getByLabel('每页显示数量').selectOption('10')
  assert.equal(await page.locator('tbody tr').count(), 10)
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await page.waitForFunction(() => document.querySelectorAll('tbody tr').length === 10)
  assert.equal(await page.locator('tbody tr').count(), 10)
  await page.getByLabel('搜索商品名称或编码').fill('RSDP-002')
  assert.equal(await page.locator('tbody tr').count(), 1)
  await page.getByRole('link', { name: '查看简约办公桌 2详情' }).first().click()
  await heading('简约办公桌 2')
  await page.getByRole('link', { name: '返回商品库' }).click()
  await page.getByLabel('搜索商品名称或编码').fill('不存在的商品')
  await heading('没有找到匹配的商品')
  await page.getByRole('button', { name: '清空筛选' }).click()
  await page.getByLabel('筛选商品状态').selectOption('active')
  assert.equal(await page.locator('tbody tr').count(), 15)
  await page.getByLabel('筛选商品状态').selectOption('')

  await page.getByRole('link', { name: '空间设计', exact: true }).click()
  await heading('默认楼层 · 上传办公图纸')
  const projectFlow = page.getByRole('region', { name: '项目设计流程' })
  await projectFlow.getByText('空间设计', { exact: true }).waitFor()
  await projectFlow.getByText(/当前图纸：/).waitFor()
  const projectNav = page.getByRole('navigation', { name: '项目设计步骤' })
  assert.equal(await projectNav.getByRole('link').count(), 4)
  assert.equal(await projectNav.getByRole('button', { name: '1 项目创建', exact: true }).count(), 1)
  await projectNav.getByRole('link', { name: '3 空间总览' }).click()
  await page.getByRole('img', { name: '空间核对画布', exact: true }).waitFor()
  await page.getByRole('button', { name: '加载工装示例（可撤销）', exact: true }).click()
  const enterLayout = page.getByRole('button', { name: '进入家具排版 →', exact: true })
  assert.equal(await enterLayout.isEnabled(), true)
  await enterLayout.click()
  await page.waitForURL(/mode=design/)
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
  await page.getByRole('link', { name: '批量导入', exact: true }).click()
  await heading('批量导入商品')
  await assertNoProjectSteps()
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载 Excel 模板' }).click()
  assert.equal((await downloadPromise).suggestedFilename(), '办公产品导入模板.xlsx')
  await chooseFile('错误文件.txt')
  assert.match(await page.getByRole('alert').innerText(), /请选择/)
  await chooseFile('空文件.csv', '')
  assert.match(await page.getByRole('alert').innerText(), /文件内容为空/)
  await chooseFile()
  await heading('预览并确认导入数据')
  const dismissNavigation = page.waitForEvent('dialog').then(dialog => dialog.dismiss())
  await page.getByRole('link', { name: '返回商品库' }).click()
  await dismissNavigation
  assert.match(page.url(), /\/products\/import$/)
  assert.equal(importRequests, 0)
  await page.getByRole('radio', { name: /覆盖更新已有商品/ }).check()
  await page.getByRole('button', { name: '重新校验', exact: true }).click()
  await page.getByRole('button', { name: '确认并执行导入', exact: true }).waitFor({ state: 'visible' })
  await page.getByRole('button', { name: '确认并执行导入' }).click()
  assert.equal(await page.getByRole('button', { name: '正在导入，请稍候…' }).isDisabled(), true)
  await page.getByRole('link', { name: '返回商品库' }).click()
  await page.getByRole('alert').filter({ hasText: '正在处理导入' }).waitFor()
  await page.getByRole('heading', { name: '处理完成，部分商品未能导入' }).waitFor()
  assert.equal(await page.getByRole('alert').filter({ hasText: /失败|未完成/ }).count(), 0)
  assert.equal(importRequests, 1)
  assert.equal(overwriteRequested, true)
  const failureDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载失败明细' }).click()
  const failedRows = await failureDownload
  assert.equal(failedRows.suggestedFilename(), '商品导入失败明细.csv')
  const failedRowsStream = await failedRows.createReadStream()
  let failedRowsCsv = ''
  for await (const chunk of failedRowsStream) failedRowsCsv += chunk
  assert.match(failedRowsCsv, /'=INVALID/)
  await page.getByRole('link', { name: '查看导入记录' }).click()
  await heading('导入记录')
  await page.reload({ waitUntil: 'networkidle' })
  assert.equal(await page.locator('tbody tr').count(), 1)
  await page.getByRole('button', { name: '查看商品清单.csv导入结果' }).click()
  await page.getByRole('heading', { name: '处理完成，部分商品未能导入' }).waitFor()

  await page.getByRole('link', { name: '新建导入' }).click()
  await chooseFile('失败后重试.csv')
  importMode = 'error'
  await page.getByRole('button', { name: '确认并执行导入' }).click()
  await page.getByRole('alert').filter({ hasText: '导入请求未完成' }).waitFor()
  importMode = 'skipped'
  await page.getByRole('button', { name: '确认并执行导入' }).click()
  await page.getByRole('heading', { name: '处理完成，已跳过重复商品' }).waitFor()
  assert.equal(overwriteRequested, false)
  assert.equal(importRequests, 3)
  await page.getByRole('link', { name: '查看导入记录' }).click()
  await heading('导入记录')
  assert.equal(await page.locator('tbody tr').count(), 3)

  listMode = 'error'
  await page.getByRole('navigation', { name: '工作区导航' }).getByRole('link', { name: '商品库', exact: true }).click()
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
  await page.getByRole('link', { name: '批量导入', exact: true }).click()
  await heading('批量导入商品')
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  assert.deepEqual(pageErrors, [])
  console.log('Workflow E2E passed: navigation, context, filters, pagination, details, import, downloads, history, retry, empty/error states and responsive layouts.')
} finally {
  await browser.close()
}
