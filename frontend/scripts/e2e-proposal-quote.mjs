import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

// 历史楼层商品小计 UI 回归：自起服务，API 全部模拟。
// 覆盖缺价、无记录、商品变化、换房但商品数量不变、报价版本/时间与演示模式。
const cwd = fileURLToPath(new URL('../', import.meta.url))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = `http://127.0.0.1:${port}`
const app = spawn(process.execPath, ['.output/server/index.mjs'], {
  cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' },
  stdio: 'ignore',
})
let browser
try {
  let up = false
  for (let attempt = 0; attempt < 75; attempt++) {
    try { if ((await fetch(baseUrl)).ok) { up = true; break } } catch {}
    if (app.exitCode !== null) throw new Error('测试预览服务启动失败，请先执行 pnpm build')
    await new Promise(resolve => setTimeout(resolve, 200))
  }
  assert.ok(up, '测试预览服务没有就绪')
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  const projectId = 'OPRJ-QUOTE'
  const floorId = 'OFLR-QUOTE'
  const storageKey = 'rsdp-office-v01:MOCK-USER:' + floorId
  const P1 = { id: 'P1', rspuId: 'R1', variantId: 'V1', widthMm: 1200, depthMm: 550, heightMm: 750, widthCm: 120, depthCm: 55, name: '行政桌 1200', desc: '', variant: '暖白 / 钢脚', price: 1999, kind: 'desk' }
  const P2 = { ...P1, id: 'P2', rspuId: 'R2', variantId: 'V2', name: '会议椅', price: 399, kind: 'chair' }
  const P3 = { ...P1, id: 'P3', rspuId: 'R3', variantId: 'V3', name: '商品不可用', price: 0, kind: 'cabinet' }
  const baseLine = { floorId, floorName: '1F', zoneId: 'Z1', zoneName: '开放办公区', productId: 'P1', rspuId: 'R1', productName: '行政桌 1200', variant: '暖白 / 钢脚', imageUrl: null, qty: 1, factoryPrice: 344, unitPrice: 412.8 }
  const baseRecord = {
    quotationId: 'QT-1', projectId, projectName: '报价展示项目', revision: 1, status: 'CONFIRMED',
    pricingMode: 'COEFFICIENT', pricingValue: 1.2, goodsTotal: 512.8, serviceFee: 0, logisticsFee: 0,
    fees: [], totalAmount: 512.8, lineCount: 2, missingPriceLines: 0, createdBy: 'MOCK-USER', createdAt: '2026-11-25T10:00:00',
  }
  // 场景通过替换 latestRecord 切换；页面每次 reload 重新拉取
  let latestRecord = null
  let latestAnyRecord = null
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status, message: '成功', data }) })
    if (path === '/api/v1/office/projects') return respond([{ projectId, name: '报价展示项目', leader: {
      userId: 'MOCK-USER', role: 'LEADER', permissions: ['READ', 'EDIT'],
    }, floors: [{ floorId, projectId, name: '1F' }] }])
    if (path === '/api/v1/office/products') return respond([P1, P2, P3])
    if (path === `/api/v1/office/projects/${projectId}/quotation/latest`) return respond(latestRecord)
    if (path === `/api/v1/office/projects/${projectId}/quotation/latest-record`) return respond(latestAnyRecord || latestRecord)
    return respond(path.endsWith('/canvas') ? null : [])
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))

  const url = baseUrl + '/proposal?floorId=' + floorId
  await page.goto(url, { waitUntil: 'networkidle' })
  // 页面商品必须与报价行（productId + 数量）严格一致才显示历史楼层小计，因此按场景重写页面商品集合
  const writePageState = productIds => page.evaluate(({ key, ids }) => {
    const state = JSON.parse(localStorage.getItem(key) || '{}')
    state.objects = [
      { id: 'Z1', kind: 'zone', name: '开放办公区', x: 0, y: 0, w: 6000, h: 4000, zoneType: 'OPEN_OFFICE', tone: 'blue', reviewUnit: 'mm', confirmed: true, reviewConfirmation: { status: 'CONFIRMED' },
        polygon: [[0, 0], [6000, 0], [6000, 4000], [0, 4000]], obstacles: [], facilities: [] },
      ...ids.map((productId, index) => ({ id: 'A' + index, kind: 'furniture', zoneId: 'Z1', furnitureTemplateId: 'desk-straight', label: '家具 ' + productId, x: 400 + index * 1400, y: 400, w: 1200, h: 600, rotation: 0,
        productId, layoutConfirmed: true,
        productSelection: { product: null, cartLineId: 'L' + index, sourceCanvasRevision: 3 } })),
    ]
    state.cart = ids.map((productId, index) => ({ id: 'L' + index, productId, zoneId: 'Z1', zoneName: '开放办公区', qty: 1 }))
    state.canvasRevision = 3; state.canvasMode = 'overview'; state.designZoneId = null
    state.floorPlan = null; state.spaceTemplate = null
    localStorage.setItem(key, JSON.stringify(state))
  }, { key: storageKey, ids: productIds })

  const aside = page.locator('.proposal-aside')

  // 场景 1：完整报价且与页面商品一致 → 显示「历史楼层小计」，金额为全部行小计合计
  latestRecord = { ...baseRecord, lines: [
    { ...baseLine, subtotal: 412.8, issue: null },
    { ...baseLine, productId: 'P2', productName: '会议椅', subtotal: 100, issue: null },
  ] }
  await writePageState(['P1', 'P2'])
  await page.goto(url, { waitUntil: 'networkidle' })
  await aside.getByText('最近确认的楼层商品小计', { exact: true }).waitFor()
  assert.match(await aside.textContent(), /¥512\.80/, '完整报价应显示全部行合计')
  assert.match(await aside.textContent(), /历史报价快照/, '完整报价应保留数据口径说明')
  assert.equal(await aside.getByText('最近确认的已报价商品小计', { exact: true }).count(), 0, '完整报价不得显示缺价小计')
  assert.equal(await aside.locator('.aside-quote-hint.incomplete').count(), 0, '完整报价不得有缺价提示')
  assert.match(await aside.getByTestId('quotation-revision').textContent(), /报价版本号1/)
  assert.match(await aside.getByTestId('quotation-time').textContent(), /确认时间：2026-11-25 10:00:00/)
  assert.equal(await aside.getByText('最终报价', { exact: true }).count(), 0)
  assert.equal(await aside.getByText('参考金额', { exact: true }).locator('..').locator('strong').evaluate(el => getComputedStyle(el).textDecorationLine), 'none')

  // 同楼层换房、商品与总数量不变：仍可看历史小计，但必须保留历史标识、版本和确认时间。
  await page.evaluate(key => {
    const state = JSON.parse(localStorage.getItem(key))
    for (const item of state.objects) {
      if (item.kind === 'zone') { item.id = 'Z2'; item.name = '新会议室' }
      else if (item.kind === 'furniture') item.zoneId = 'Z2'
    }
    for (const item of state.cart) { item.zoneId = 'Z2'; item.zoneName = '新会议室' }
    state.canvasRevision += 1
    localStorage.setItem(key, JSON.stringify(state))
  }, storageKey)
  await page.reload({ waitUntil: 'networkidle' })
  await aside.getByText('最近确认的楼层商品小计', { exact: true }).waitFor()
  assert.match(await aside.getByTestId('floor-quote-total').textContent(), /¥512\.80/)
  assert.equal(await aside.getByText('最终报价', { exact: true }).count(), 0)
  assert.match(await aside.getByTestId('quotation-revision').textContent(), /报价版本号1/)
  assert.match(await aside.getByTestId('quotation-time').textContent(), /确认时间：2026-11-25 10:00:00/)


  // 场景 2：部分缺价 → 改显示「已报价商品小计」，缺价行不计入并红色提示
  latestRecord = { ...baseRecord, lineCount: 3, missingPriceLines: 1, lines: [
    { ...baseLine, subtotal: 412.8, issue: null },
    { ...baseLine, productId: 'P2', productName: '会议椅', subtotal: 100, issue: null },
    { ...baseLine, productId: 'P3', productName: '商品不可用', factoryPrice: null, unitPrice: null, subtotal: null, issue: '缺出厂价，无法试算' },
  ] }
  await writePageState(['P1', 'P2', 'P3'])
  await page.reload({ waitUntil: 'networkidle' })
  await aside.getByText('最近确认的已报价商品小计', { exact: true }).waitFor()
  assert.match(await aside.textContent(), /¥512\.80/, '缺价行不得计入合计，金额仍为已定价行小计')
  assert.equal(await aside.getByText('最近确认的楼层商品小计', { exact: true }).count(), 0, '缺价时不得显示历史楼层小计')
  const hint = aside.locator('.aside-quote-hint.incomplete')
  await hint.waitFor()
  assert.match(await hint.textContent(), /含缺价 1 行（未计入），报价不完整/)
  assert.equal(await hint.evaluate(el => getComputedStyle(el).color), 'rgb(185, 28, 28)', '缺价提示应为红色')

  // 场景 3：全部缺价 → 已报价商品小计为 ¥0.00，提示行数为总行数
  latestRecord = { ...baseRecord, lineCount: 2, missingPriceLines: 2, lines: [
    { ...baseLine, factoryPrice: null, unitPrice: null, subtotal: null, issue: '缺出厂价，无法试算' },
    { ...baseLine, productId: 'P2', productName: '商品不可用', factoryPrice: null, unitPrice: null, subtotal: null, issue: '商品不可用' },
  ] }
  await writePageState(['P1', 'P2'])
  await page.reload({ waitUntil: 'networkidle' })
  await aside.getByText('最近确认的已报价商品小计', { exact: true }).waitFor()
  assert.match(await aside.textContent(), /¥0\.00/, '全部缺价时合计为 0')
  assert.match(await aside.locator('.aside-quote-hint.incomplete').textContent(), /含缺价 2 行（未计入），报价不完整/)

  // 场景 4：无确认报价 → 不显示历史楼层小计行
  latestRecord = null
  await page.reload({ waitUntil: 'networkidle' })
  await aside.getByText('方案摘要').waitFor()
  await page.waitForLoadState('networkidle')
  assert.equal(await aside.getByText('最近确认的楼层商品小计', { exact: true }).count(), 0, '无确认报价不显示历史楼层小计')
  assert.equal(await aside.getByText('最近确认的已报价商品小计', { exact: true }).count(), 0, '无确认报价不显示已报价商品小计')

  // 场景 5：报价快照与页面商品不一致（报价后页面改商品）→ 不显示历史楼层小计，参考金额不加删除线
  latestRecord = { ...baseRecord, lines: [
    { ...baseLine, subtotal: 412.8, issue: null },
    { ...baseLine, productId: 'P2', productName: '会议椅', subtotal: 100, issue: null },
  ] }
  await writePageState(['P1'])
  await page.reload({ waitUntil: 'networkidle' })
  await aside.getByText('方案摘要').waitFor()
  await page.waitForLoadState('networkidle')
  assert.equal(await aside.getByText('最近确认的楼层商品小计', { exact: true }).count(), 0, '报价与页面商品不一致时不显示历史楼层小计')
  assert.equal(await aside.getByText('最近确认的已报价商品小计', { exact: true }).count(), 0, '报价与页面商品不一致时不显示已报价商品小计')
  assert.equal(await aside.getByTestId('floor-quote-total').count(), 0)
  assert.match(await aside.getByTestId('quotation-revision').textContent(), /上一次报价版本号1/)
  assert.match(await aside.getByTestId('quotation-time').textContent(), /2026-11-25 10:00:00/)

  // 无已确认报价时只展示最近记录的信息，不展示该记录的金额。
  latestRecord = null
  latestAnyRecord = { quotationId: 'QT-2', revision: 2, status: 'STALE', createdAt: '2026-11-26T09:30:00' }
  await page.reload({ waitUntil: 'networkidle' })
  await aside.getByTestId('quotation-revision').waitFor()
  assert.match(await aside.getByTestId('quotation-revision').textContent(), /上一次报价版本号2/)
  assert.match(await aside.getByTestId('quotation-time').textContent(), /报价记录时间：2026-11-26 09:30:00/)
  assert.equal(await aside.getByTestId('floor-quote-total').count(), 0)

  // 演示数据不应产生虚构的确认报价或报价版本。
  await page.goto(url + '&preview=1', { waitUntil: 'networkidle' })
  await aside.getByText('方案摘要', { exact: true }).waitFor()
  assert.equal(await aside.getByTestId('floor-quote-total').count(), 0)
  assert.equal(await aside.getByTestId('quotation-revision').count(), 0)
  assert.equal(await aside.getByText('最终报价', { exact: true }).count(), 0)

  assert.deepEqual(errors, [], `不应有未捕获错误：${errors.join('; ')}`)
  console.log('历史楼层小计 UI 通过：完整/部分/全部缺价、无记录、商品变化、换房、版本和时间、过期记录、演示模式。API 全部模拟。')
} finally {
  await browser?.close()
  app.kill()
}
