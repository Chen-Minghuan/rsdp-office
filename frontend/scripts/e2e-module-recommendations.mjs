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
  page.on('pageerror', error => errors.push(error.message))
  let saved = null, empty = false, fail = false, unavailable = false, delayA = false, noCatalog = false
  const selections = [], queries = []
  const products = [
    ['EXACT', '精确尺寸办公桌', 1200, 600, 1000], ['MEDIUM', '宽松办公桌', 1000, 500, 800],
    ['SMALL', '小尺寸边桌', 600, 300, 400], ['ROTATED', '旋转适配长桌', 550, 1150, 900],
    ['QUOTE', '待报价办公桌', 1100, 500, 0],
  ].map(([id, name, widthMm, depthMm, price]) => ({ id, rspuId: 'MASTER-' + id, variantId: id, name, widthMm, depthMm, price, widthCm: widthMm / 10, depthCm: depthMm / 10, desc: '', variant: '真实规格 API 模拟', kind: 'other', uses: ['OTHER'] }))
  await context.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    const respond = (data, status = 200, message = '成功') => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status, message, data }) })
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
        return [{ product, rotationDeg, fitRatio, remainingWidthMm: current.w - w, remainingDepthMm: current.h - d, tier: fitRatio >= .85 ? 'CLOSE' : fitRatio >= .6 ? 'RELAXED' : 'SMALL' }]
      }).sort((a, b) => b.fitRatio - a.fitRatio)
      if (path.endsWith('/module-products')) {
        selections.push(body)
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
      const response = { zoneId: body.zoneId, moduleId: body.moduleId, canvasRevision: body.canvasRevision, widthMm: current.w, depthMm: current.h, sameModuleCount: current.id === 'C' ? 1 : 2, missingDimensions: noCatalog ? 0 : 1, eligibleVariantCount: noCatalog ? 0 : products.length + 1, candidates: empty || noCatalog ? [] : candidates }
      if (delayA && body.moduleId === 'A' && !body.allowRotation) await new Promise(resolve => setTimeout(resolve, 900))
      if (fail) return respond(null, 503, '推荐服务暂不可用')
      return respond(response)
    }
    return respond(path.endsWith('/OFFICE-PROJECT-001') ? null : [])
  })
  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.evaluate(() => {
    const state = JSON.parse(localStorage.getItem('rsdp-office-v01') || '{}')
    state.objects = [
      { id: 'Z', kind: 'zone', x: 0, y: 0, w: 6000, h: 4000, name: '推荐测试空间', zoneType: 'OPEN_OFFICE', seats: 0, note: '', tone: 'blue', reviewUnit: 'mm', evidenceKind: 'MANUAL', geometrySource: 'manual_geometry', polygon: [[0,0],[6000,0],[6000,4000],[0,4000]] },
      ...['A', 'B'].map((id, index) => ({ id, kind: 'furniture', zoneId: 'Z', furnitureTemplateId: 'desk-straight', label: '职员桌 ' + id, x: 500 + index * 2000, y: 500, w: 1200, h: 600, rotation: 0, productId: '', layoutConfirmed: true })),
      { id: 'C', kind: 'furniture', zoneId: 'Z', furnitureTemplateId: 'desk-straight', label: '小桌 C', x: 500, y: 2300, w: 700, h: 400, rotation: 0, productId: '', layoutConfirmed: true },
      { id: 'OTHER', kind: 'furniture', zoneId: 'OTHER', furnitureTemplateId: 'chair-office', x: 10000, y: 0, w: 650, h: 650, rotation: 0, productId: 'KEEP', layoutConfirmed: true },
    ]
    state.canvasRevision = 9; state.canvasMode = 'design'; state.designZoneId = 'Z'; state.cart = []; state.floorPlan = null; state.spaceTemplate = null
    localStorage.setItem('rsdp-office-v01', JSON.stringify(state))
  })
  await page.goto(baseUrl + '/canvas?mode=design&zoneId=Z', { waitUntil: 'networkidle' })
  await page.getByLabel('布局房间类型').selectOption('MEETING_ROOM')
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).objects.find(item => item.id === 'Z').zoneType), 'MEETING_ROOM')
  await page.getByTestId('tab-product-selection').click()
  await page.locator('[data-furniture-id="A"]').click()
  await page.getByTestId('product-copy-furniture').click()
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).objects.filter(item => item.kind === 'furniture' && item.zoneId === 'Z').length), 4)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).objects.filter(item => item.kind === 'furniture' && item.zoneId === 'Z').length), 3)
  await page.locator('[data-furniture-id="A"]').click()
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.objects.find(item => item.id === 'A').productId = 'OLD-MODULE-PRODUCT'
    store.cart = [{ id: 'OLD-MODULE-LINE', productId: 'OLD-MODULE-PRODUCT', zoneId: 'Z', zoneName: '推荐测试空间', qty: 1 }]
    store.saveLocal()
  })
  const beforeReplace = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  const staffCatalog = page.getByTestId('unit-module-catalog').locator('details').filter({ hasText: '职员桌' })
  if (!(await staffCatalog.getAttribute('open'))) await staffCatalog.locator('summary').click()
  await page.getByTestId('unit-module-catalog').locator('[data-module-id="desk-l"]').click()
  await page.getByRole('status').filter({ hasText: '已替换为' }).waitFor()
  const replaced = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
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
  const restored = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
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
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).objects.find(item => item.id === 'A').w), 1250)
  await page.getByTestId('product-item-width').fill('1200')
  await page.getByTestId('product-item-resize').click()
  await page.screenshot({ path: outDir + '/00-product-layout-editor.png', animations: 'disabled' })
  await page.getByRole('button', { name: '确认当前排版', exact: true }).click()
  const panel = page.getByTestId('module-product-recommendations')
  const card = id => panel.locator('[data-product-id="' + id + '"]')
  await card('EXACT').waitFor()
  assert.equal(await card('ROTATED').count(), 1)
  assert.match(await card('ROTATED').innerText(), /需旋转 90°/)
  assert.equal(await card('QUOTE').getByRole('button', { name: '待补报价' }).isDisabled(), true)
  assert.equal(await panel.getByText('四人工位组合', { exact: true }).count(), 0)
  await panel.getByRole('button', { name: /^偏小/ }).click()
  assert.equal(await panel.locator('[data-product-id]').count(), 1)
  assert.equal(await card('SMALL').count(), 1)
  await panel.getByRole('button', { name: /^全部兼容/ }).click()
  await page.screenshot({ path: outDir + '/01-size-tiers.png', animations: 'disabled' })
  const before = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).objects)
  await card('EXACT').getByRole('button', { name: '应用到 2 个模块' }).click()
  await panel.getByText('已为 2 个模块匹配商品，模块占地保持不变').waitFor()
  let state = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  for (const id of ['A','B']) {
    const item = state.objects.find(item => item.id === id), old = before.find(item => item.id === id)
    for (const key of ['w','h','x','y','rotation','layoutConfirmed']) assert.deepEqual(item[key], old[key])
    assert.equal(item.productId, 'EXACT')
  }
  assert.equal(state.cart.length, 2)
  assert.equal(state.cart.every(item => item.qty === 1), true)
  assert.deepEqual(state.objects.find(item => item.id === 'OTHER'), before.find(item => item.id === 'OTHER'))
  assert.equal(selections[0].applyToSameModules, true)
  unavailable = true
  await card('MEDIUM').getByRole('button', { name: '应用到 2 个模块' }).click()
  await panel.getByRole('alert').filter({ hasText: '商品已下架' }).waitFor()
  state = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  assert.equal(state.cart.every(item => item.productId === 'EXACT'), true)
  unavailable = false
  delayA = true
  await panel.getByLabel('允许旋转 90°').uncheck()
  await page.waitForTimeout(350)
  await panel.getByLabel('匹配单位模块').selectOption('C')
  await card('SMALL').waitFor()
  await page.waitForTimeout(1000)
  assert.equal(await card('EXACT').count(), 0)
  assert.equal(await panel.getByLabel('匹配单位模块').inputValue(), 'C')
  empty = true
  await panel.getByRole('button', { name: '重新匹配' }).click()
  await panel.getByText(/暂无宽深均可容纳的在售商品/).waitFor()
  assert.equal(await panel.locator('[data-product-id]').count(), 0)
  noCatalog = true
  await panel.getByRole('button', { name: '重新匹配' }).click()
  await panel.getByText('暂无已上架、审核通过且有有效规格的商品，请先核对商品审核和规格。').waitFor()
  assert.equal(await panel.getByRole('link', { name: '前往商品审核' }).count(), 1)
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
  assert.ok(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).objects.filter(item => item.kind === 'furniture' && item.zoneId === 'Z').every(item => item.layoutConfirmed)), '座椅侧净空不足不阻止确认')
  await page.getByTestId('layout-review-notice').waitFor()
  assert.match(await page.getByTestId('layout-review-notice').innerText(), /座椅一侧离墙净空不足 560mm，请现场复核/)
  assert.deepEqual(errors, [])
  console.log('模块尺寸推荐 UI 通过：真实规格独立入口、毫米/三档/旋转、待报价、同规格批量应用及几何保留、下架拒绝、切换/旧响应、空结果和失败。全部 API 模拟。')
} catch (failure) {
  if (page) await page.screenshot({ path: outDir + '/failure.png', animations: 'disabled' }).catch(() => {})
  throw failure
} finally {
  if (browser) await browser.close()
  app.kill('SIGTERM')
  await Promise.race([once(app, 'exit').catch(() => {}), new Promise(resolve => setTimeout(resolve, 2000))])
}
