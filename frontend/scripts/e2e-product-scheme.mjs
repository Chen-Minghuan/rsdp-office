import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const cwd = fileURLToPath(new URL('../', import.meta.url))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = `http://127.0.0.1:${port}`
const app = spawn(process.execPath, ['.output/server/index.mjs'], {
  cwd, env: { ...process.env, NITRO_PORT: String(port), NITRO_HOST: '127.0.0.1' }, stdio: 'ignore',
})

const floorId = 'OFLR-SCHEME'
const projectId = 'OPRJ-SCHEME'
const zone = {
  id: 'ROOM', kind: 'zone', x: 0, y: 0, w: 6000, h: 4000, name: '副总办公室', zoneType: 'EXECUTIVE_OFFICE',
  reviewUnit: 'mm', reviewConfirmation: { status: 'CONFIRMED' }, evidenceKind: 'MANUAL',
  polygon: [[0, 0], [6000, 0], [6000, 4000], [0, 4000]],
}
const furniture = {
  id: 'DESK', zoneId: zone.id, kind: 'furniture', x: 900, y: 700, w: 1500, h: 800, rotation: 0,
  furnitureTemplateId: 'desk-straight', layoutConfirmed: true, label: '办公桌',
}
const product = { id: 'SKU', variantId: 'SKU', rspuId: 'RSPU', name: '胡桃木办公桌', variant: '1400×700', price: 1250, widthMm: 1400, depthMm: 700 }
const catalog = [{ rspuId: 'RSPU', productName: product.name, retailPrice: 1250, status: 'active', categoryPath: ['办公家具', '办公桌'] }]
const selected = { ...furniture, productId: product.id, productSelection: { cartLineId: 'LINE', product } }
const cartLine = { lineId: 'LINE', zoneId: zone.id, zoneName: zone.name, projectId: floorId, productId: product.id, qty: 1 }
let canvas = { projectId: floorId, revision: 5, mode: 'design', designZoneId: zone.id, objects: [zone, selected], files: [], blank: false }
let cart = [cartLine]
let slot = 'A', otherSlot = null, bundle = null, confirmation = null, submitted = null
let bundleApiMissing = true
let browser
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先执行 pnpm build')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1536, height: 1000 } })
  await context.route('**/api/auth/me', route => route.fulfill({ json: {
    code: 200, data: { userId: 'TEST-USER', username: 'test', displayName: 'Alice', role: 'DEALER' },
  } }))
  await context.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    const ok = data => route.fulfill({ json: { code: 200, data } })
    if (path === '/api/v1/office/projects') {
      return ok([{ projectId, name: '办公总部', customerRequirements: '深色木纹', floors: [{ projectId, floorId, name: '11F' }] }])
    }
    if (path === '/api/v1/products') return ok(catalog)
    if (path === '/api/v1/products/page') return ok({ rows: catalog, total: catalog.length, catalogTotal: catalog.length, activeTotal: catalog.length, page: 1, size: 20 })
    if (path.endsWith('/products/RSPU')) return ok({ variants: [{ variantId: 'SKU', displayName: product.variant, status: 'active' }] })
    if (path.endsWith('/product-bundles')) {
      if (bundleApiMissing) return route.fulfill({ status: 404, json: { message: 'No static resource api/v1/office/product-bundles' } })
      return ok(bundle ? [bundle] : [])
    }
    if (path.includes('/product-bundles/') && method === 'PUT') {
      const body = request.postDataJSON()
      assert.deepEqual(body.items, [{ rspuId: 'RSPU', variantId: 'SKU', quantity: 1 }])
      bundle = { bundleId: path.split('/').at(-1), name: body.name, revision: body.revision + 1, items: [{ ...body.items[0], name: product.name, variant: product.variant }] }
      return ok(bundle)
    }
    if (path.endsWith('/canvas')) {
      if (method === 'PUT') {
        const draft = request.postDataJSON()
        assert.equal(draft.schemeToken, canvas.schemeToken)
        canvas = draft
      }
      return ok(canvas)
    }
    if (path.endsWith('/cart')) return ok(cart)
    if (path.endsWith('/room-scheme')) return ok({ activeSlot: slot, alternativeSaved: !!otherSlot })
    if (path.includes('/room-scheme/')) {
      const action = path.split('/').at(-1)
      const body = request.postDataJSON()
      assert.equal(body.zoneId, zone.id)
      assert.equal(body.revision, canvas.revision)
      assert.equal(body.schemeToken ?? undefined, canvas.schemeToken)
      let objects = canvas.objects
      if (action === 'activate') {
        const old = { objects, cart }
        if (otherSlot) ({ objects, cart } = otherSlot)
        otherSlot = old
        slot = body.targetSlot
      } else if (action === 'clear-selection') {
        objects = [zone, furniture]
        cart = []
      } else {
        assert.equal(slot, 'B')
        assert.deepEqual(body.items.map(({ rspuId, variantId, quantity }) => ({ rspuId, variantId, quantity })), [{ rspuId: 'RSPU', variantId: 'SKU', quantity: 1 }])
        objects = [zone, selected]
        cart = [cartLine]
      }
      canvas = { ...canvas, objects, revision: canvas.revision + 1, schemeToken: 'TOKEN-' + (canvas.revision + 1) }
      confirmation = null
      return ok({ canvas, cart, scheme: { activeSlot: slot, alternativeSaved: !!otherSlot } })
    }
    if (path.endsWith('/proposal-confirmation')) {
      if (method === 'POST') {
        assert.deepEqual(request.postDataJSON(), { canvasRevision: canvas.revision, schemeToken: canvas.schemeToken })
        confirmation = { confirmationId: 'PROP-TEST', canvasRevision: canvas.revision, confirmedAt: '2026-11-24T12:00:00', current: true }
      }
      return ok(confirmation)
    }
    if (path.endsWith('/render-previews')) {
      if (method === 'GET') return ok([])
      submitted = request.postDataJSON()
      assert.equal(submitted.confirmationId, 'PROP-TEST')
      assert.equal(submitted.expectedSnapshot.furniture[0].productId, product.id)
      return ok({ ...submitted, previewId: 'RPRE-TEST', purpose: 'CONFIRMED_SCHEME', stale: false,
        snapshot: submitted.expectedSnapshot, status: 'FAILED', errorMessage: '测试供应商响应', createdAt: new Date().toISOString() })
    }
    return ok([])
  })

  const page = await context.newPage()
  const pageErrors = []
  page.on('pageerror', error => pageErrors.push(error.message))
  await page.goto(baseUrl + '/products')
  const bundlePanel = page.getByRole('region', { name: '自选商品组合' })
  const bundleError = bundlePanel.getByRole('alert')
  await bundleError.getByText('当前服务暂未提供商品组合，更新服务后可重试。', { exact: false }).waitFor()
  assert.doesNotMatch(await bundleError.innerText(), /\/api\/|https?:|404/)
  assert.equal(await bundlePanel.getByRole('button', { name: '＋ 新建组合' }).isDisabled(), true)
  assert.equal(await bundlePanel.evaluate(panel => panel.scrollWidth > panel.clientWidth), false)
  const newButton = await bundlePanel.getByRole('button', { name: '＋ 新建组合' }).boundingBox()
  const bounds = await bundlePanel.boundingBox()
  assert.ok(newButton.x + newButton.width <= bounds.x + bounds.width)
  if (process.env.SCHEME_SCREENSHOT_DIR) await page.screenshot({ path: process.env.SCHEME_SCREENSHOT_DIR + '/catalog-legacy-server.png' })
  bundleApiMissing = false
  await bundleError.getByRole('button', { name: '重试' }).click()
  await page.getByRole('button', { name: '加入组合 胡桃木办公桌' }).click()
  await page.getByLabel('组合名称').fill('主管办公室组合')
  await page.getByRole('button', { name: '加入规格' }).click()
  await page.getByRole('button', { name: '保存组合' }).click()
  await page.getByText('组合已保存', { exact: true }).waitFor()
  const backgroundOf = locator => locator.evaluate(element => getComputedStyle(element).backgroundColor)
  await page.goto(baseUrl + '/canvas?floorId=' + floorId)
  const spaceActive = page.locator('.review-toolbar button.active').first()
  await spaceActive.waitFor()
  const spaceAccent = await backgroundOf(spaceActive)
  if (process.env.SCHEME_SCREENSHOT_DIR) await page.screenshot({ path: process.env.SCHEME_SCREENSHOT_DIR + '/space-design.png' })
  const schemeUrl = baseUrl + '/canvas?mode=design&view=products&zoneId=ROOM&floorId=' + floorId
  await page.goto(schemeUrl)
  await page.getByTestId('product-scheme').waitFor()
  const schemeAccent = await backgroundOf(page.getByRole('button', { name: '选择', exact: true }))
  assert.equal(schemeAccent, spaceAccent, '两个设计页的选中工具应使用同一颜色')
  const currentNav = page.getByRole('link', { name: '产品方案', exact: true })
  assert.equal(await backgroundOf(page.getByRole('button', { name: '自动选品', exact: true })), await backgroundOf(currentNav))
  const plan = name => page.getByRole('button', { name: `Plan ${name}`, exact: true })
  await page.locator('button[aria-pressed=true]').filter({ hasText: 'Plan A' }).waitFor()
  await plan('B').click()
  await page.getByText('已切换至 Plan B', { exact: true }).waitFor()
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '清空选品' }).click()
  await page.getByText('已清空当前房间选品').waitFor()
  await plan('A').click()
  await page.getByText('已切换至 Plan A', { exact: true }).waitFor()
  assert.equal(cart.length, 1)
  await plan('B').click()
  await page.getByText('已切换至 Plan B', { exact: true }).waitFor()
  assert.equal(cart.length, 0)
  await page.getByLabel('选择商品组合').selectOption(bundle.bundleId)
  await page.getByRole('button', { name: '应用组合' }).click()
  await page.getByText('已更新当前房间选品，剩余 0 件家具待选').waitFor()
  await page.getByRole('link', { name: '去项目清单确认' }).click()
  await page.getByRole('button', { name: '确认方案', exact: true }).click()
  await page.getByText('方案已确认', { exact: true }).waitFor()
  await page.reload()
  await page.getByText('方案已确认', { exact: true }).waitFor()
  await page.goto(schemeUrl)
  await page.getByRole('button', { name: '暖木自然', exact: true }).click()
  await page.getByRole('button', { name: '生成方案效果图', exact: true }).click()
  const render = page.getByRole('dialog', { name: '方案效果图' })
  assert.equal(await render.getByLabel('效果图风格').inputValue(), '暖木自然')
  await render.getByRole('button', { name: '生成方案效果图', exact: true }).click()
  await render.getByText('测试供应商响应', { exact: true }).waitFor()
  assert.equal(submitted.confirmationId, confirmation.confirmationId)
  await render.getByRole('button', { name: '关闭效果预览' }).click()
  if (process.env.SCHEME_SCREENSHOT_DIR) await page.screenshot({ path: process.env.SCHEME_SCREENSHOT_DIR + '/scheme-desktop.png' })
  await page.setViewportSize({ width: 600, height: 860 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
  if (process.env.SCHEME_SCREENSHOT_DIR) await page.screenshot({ path: process.env.SCHEME_SCREENSHOT_DIR + '/scheme-narrow.png' })
  assert.deepEqual(pageErrors, [])
  console.log('Product scheme mock UI passed: bundle, Plan A/B, clear, confirmation after reload, formal render, narrow layout')
} finally {
  await browser?.close()
  app.kill('SIGTERM')
}
