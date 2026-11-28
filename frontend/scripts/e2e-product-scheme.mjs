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
const baseUrl = `http://localhost:${port}`
const app = spawn(process.execPath, ['.output/server/index.mjs'], {
  cwd, env: { ...process.env, NITRO_PORT: String(port), NITRO_HOST: '127.0.0.1' }, stdio: 'ignore',
})

const floorId = 'OFLR-SCHEME'
const projectId = 'OPRJ-SCHEME'
const zone = {
  id: 'ROOM', kind: 'zone', x: 0, y: 0, w: 6000, h: 4000, name: '副总办公室', zoneType: 'EXECUTIVE_OFFICE',
  reviewUnit: 'mm', reviewConfirmation: { status: 'CONFIRMED' }, evidenceKind: 'MANUAL',
  polygon: [[0, 0], [6000, 0], [6000, 4000], [0, 4000]],
  spaceTemplateId: 'LSTPL-EXISTING', templateSpaceId: 'ROOM',
}
const furniture = {
  id: 'DESK', zoneId: zone.id, kind: 'furniture', x: 900, y: 700, w: 1500, h: 800, rotation: 0,
  furnitureTemplateId: 'desk-straight', layoutConfirmed: true, label: '办公桌',
}
const product = { id: 'SKU', variantId: 'SKU', rspuId: 'RSPU', name: '胡桃木办公桌', variant: '1400×700', price: 1250, widthMm: 1400, depthMm: 700 }
const catalog = [{ rspuId: 'RSPU', productName: product.name, retailPrice: 1250, status: 'active', categoryPath: ['办公家具', '办公桌'] }]
const selected = { ...furniture, productId: product.id, productSelection: { cartLineId: 'LINE', product } }
const cartLine = { lineId: 'LINE', zoneId: zone.id, zoneName: zone.name, projectId: floorId, productId: product.id, qty: 1 }
let canvas = { projectId: floorId, revision: 5, mode: 'design', designZoneId: zone.id, objects: [zone, { ...selected, layoutConfirmed: false }], files: [], blank: false }
let cart = [cartLine]
let slot = 'A', otherSlot = null, bundle = null, confirmation = null, submitted = null
let confirmedSlot = null
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
    if (path === '/api/v1/office/workspace/shared-catalog') return ok({ workspaceEnabled: true, effectiveVisible: true })
    if (path === '/api/v1/products/browse') return ok({ rows: catalog.map(shared => ({ local: null, shared })),
      total: catalog.length, catalogTotal: catalog.length, activeTotal: catalog.length, localTotal: 0 })
    if (path.endsWith('/products/RSPU')) return ok({ variants: [{ variantId: 'SKU', displayName: product.variant, status: 'active' }] })
    if (path === '/api/v1/office/favorites/folders') return ok([{ folderId: 'DEFAULT', name: '默认收藏夹', itemCount: 1, defaultFolder: true, selected: true }])
    if (path === '/api/v1/office/favorites/product-ids') return ok(['RSPU'])
    if (path === '/api/v1/office/favorites/items') return ok(catalog)
    if (path === `/api/v1/office/projects/${projectId}/favorites/product-ids`) return ok([])
    if (path === `/api/v1/office/projects/${projectId}/favorites/items`) return ok([])
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
    if (path.endsWith('/room-scheme')) return ok({ activeSlot: slot, alternativeSaved: !!otherSlot, confirmedSlot })
    if (path.endsWith('/room-scheme/slots')) {
      const summarize = item => ({ id: item.id, label: item.label ?? null, productName: item.productSelection?.product?.name ?? null, w: item.w, h: item.h })
      const inRoom = item => item.kind === 'furniture' && item.zoneId === zone.id
      return ok({ activeSlot: slot, active: canvas.objects.filter(inRoom).map(summarize), inactive: (otherSlot?.objects || []).filter(inRoom).map(summarize) })
    }
    if (path.includes('/room-scheme/')) {
      const action = path.split('/').at(-1)
      const body = request.postDataJSON()
      assert.equal(body.zoneId, zone.id)
      if (action === 'confirm-choice' || action === 'cancel-choice') {
        assert.equal(body.revision, canvas.revision)
        assert.equal(body.schemeToken ?? undefined, canvas.schemeToken)
        assert.equal(body.targetSlot, slot, '确认/取消必须携带用户所见目标方案')
        confirmation = null
      }
      if (action === 'confirm-choice') {
        assert.ok(otherSlot, '没有 Plan B 时不能确认方案选择')
        confirmedSlot = slot
        return ok({ activeSlot: slot, alternativeSaved: true, confirmedSlot })
      }
      if (action === 'cancel-choice') {
        confirmedSlot = null
        return ok({ activeSlot: slot, alternativeSaved: true, confirmedSlot })
      }
      assert.equal(body.revision, canvas.revision)
      assert.equal(body.schemeToken ?? undefined, canvas.schemeToken)
      let objects = canvas.objects
      if (action === 'activate') {
        assert.equal(confirmedSlot, null, '已确认方案选择的空间不能切换')
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
      return ok({ canvas, cart, scheme: { activeSlot: slot, alternativeSaved: !!otherSlot, confirmedSlot } })
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
  const backgroundOf = locator => locator.evaluate(element => getComputedStyle(element).backgroundColor)
  await page.goto(baseUrl + '/canvas?floorId=' + floorId)
  const spaceActive = page.locator('.review-toolbar button.active').first()
  await spaceActive.waitFor()
  const spaceAccent = await backgroundOf(spaceActive)
  if (process.env.SCHEME_SCREENSHOT_DIR) await page.screenshot({ path: process.env.SCHEME_SCREENSHOT_DIR + '/space-design.png' })
  const schemeUrl = baseUrl + '/canvas?mode=design&view=products&zoneId=ROOM&floorId=' + floorId
  await page.goto(schemeUrl)
  await page.getByTestId('product-scheme').waitFor()
  const templateIdAfterReentry = await page.evaluate(() => {
    const app = document.querySelector('#__nuxt').__vue_app__
    const store = app.config.globalProperties.$pinia._s.get('office')
    store.spaceTemplate = null
    store.enterDesign('ROOM')
    return store.zones[0]?.spaceTemplateId
  })
  assert.equal(templateIdAfterReentry, 'LSTPL-EXISTING', '空间内容未变、仅内存模板缺失时，进入排版不得轮换 spaceTemplateId，避免方案确认指纹无故失效')
  await page.waitForFunction(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    return store.syncState === 'saved'
  })
  assert.equal(await page.getByTestId('tab-style-recommendation').count(), 0, '产品方案不再显示顶级款式推荐页签')
  assert.equal((await page.getByTestId('tab-product-selection').innerText()).trim(), '手动布局', '产品选型页签改名为手动布局')
  assert.equal(await page.getByRole('button', { name: '自动选品', exact: true }).count(), 0, '自动选品按钮由排版步骤取代')
  const stepLayout = page.getByTestId('scheme-step-layout')
  const stepSelect = page.getByTestId('scheme-step-select')
  await stepLayout.waitFor()
  assert.equal(await stepSelect.getAttribute('aria-disabled'), 'true', '未确认排版时选品锁定')
  await stepSelect.click({ force: true })
  await page.getByText('请先完成第 1 步排版', { exact: false }).waitFor()
  assert.equal(await page.getByRole('button', { name: '项目选品库', exact: true }).count(), 0, '排版步骤不显示选品来源')
  assert.equal(await page.getByTestId('design-workflow-sidebar').getByRole('region', { name: '自选商品组合' }).count(), 0, '排版步骤不显示组合面板')
  await page.getByRole('button', { name: '确认当前排版', exact: true }).click()
  await page.getByRole('button', { name: '项目选品库', exact: true }).waitFor()
  assert.equal(await stepSelect.getAttribute('aria-disabled'), 'false', '确认排版后选品解锁')
  const schemeAccent = await backgroundOf(page.getByRole('button', { name: '选择', exact: true }))
  assert.equal(schemeAccent, spaceAccent, '两个设计页的选中工具应使用同一颜色')
  const currentNav = page.getByRole('link', { name: '产品方案', exact: true })
  await page.getByRole('button', { name: '项目选品库', exact: true }).click()
  assert.equal(await backgroundOf(page.getByRole('button', { name: '项目选品库', exact: true })), await backgroundOf(currentNav))
  await page.getByTestId('tab-product-selection').click()
  assert.equal(await page.getByTestId('tab-product-selection').getAttribute('aria-selected'), 'true', '来源内容显示时必须选中产品选型')
  const schemeBundles = page.getByTestId('design-workflow-sidebar').getByRole('region', { name: '自选商品组合' })
  await schemeBundles.waitFor()
  const bundleError = schemeBundles.getByRole('alert')
  await bundleError.getByText('当前服务暂未提供商品组合，更新服务后可重试。', { exact: false }).waitFor()
  assert.doesNotMatch(await bundleError.innerText(), /\/api\/|https?:|404/)
  assert.equal(await schemeBundles.getByRole('button', { name: '＋ 新建组合' }).isDisabled(), true)
  assert.equal(await schemeBundles.evaluate(panel => panel.scrollWidth > panel.clientWidth), false)
  const newButton = await schemeBundles.getByRole('button', { name: '＋ 新建组合' }).boundingBox()
  const bounds = await schemeBundles.boundingBox()
  assert.ok(newButton.x + newButton.width <= bounds.x + bounds.width)
  if (process.env.SCHEME_SCREENSHOT_DIR) await page.screenshot({ path: process.env.SCHEME_SCREENSHOT_DIR + '/scheme-legacy-server.png' })
  bundleApiMissing = false
  await bundleError.getByRole('button', { name: '重试' }).click()
  await schemeBundles.getByText('将常用商品保存为组合', { exact: false }).waitFor()
  // 商品库页的组合面板已由上游 ProductComboPanel 接管；这里通过页面内请求预置组合（走 mock 路由），再切来源重载面板
  const seeded = await page.evaluate(async () => {
    const response = await fetch('/api/v1/office/product-bundles/BUNDLE-1', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: '主管办公室组合', revision: 0, items: [{ rspuId: 'RSPU', variantId: 'SKU', quantity: 1 }] }),
    })
    return response.status
  })
  assert.equal(seeded, 200, '预置组合应成功')
  await page.getByRole('button', { name: '收藏', exact: true }).click()
  await page.getByTestId('scheme-favorites-panel').waitFor()
  await page.getByRole('button', { name: '项目选品库', exact: true }).click()
  await schemeBundles.waitFor()
  const bundlePanel = schemeBundles
  await bundlePanel.getByTestId('bundle-card').getByRole('button', { name: '展开组合', exact: true }).click()
  await bundlePanel.locator('form.bundle-editor').waitFor({ state: 'visible' })
  const activeBundleCard = bundlePanel.locator('[data-testid="bundle-card"].is-active')
  assert.equal(await activeBundleCard.count(), 1, '展开组合只显示一张卡片')
  await activeBundleCard.getByRole('button', { name: '重命名组合', exact: true }).waitFor()
  await activeBundleCard.getByRole('button', { name: '删除', exact: true }).waitFor()
  const nameBox = await activeBundleCard.locator('.bundle-select').boundingBox()
  const renameBox = await activeBundleCard.getByRole('button', { name: '重命名组合', exact: true }).boundingBox()
  const toggleBox = await activeBundleCard.getByRole('button', { name: '收起组合', exact: true }).boundingBox()
  assert.ok(Math.abs(renameBox.y - nameBox.y) < 10, '重命名/删除应与组合名称同一行')
  assert.ok(renameBox.x >= nameBox.x + nameBox.width && renameBox.x + renameBox.width <= toggleBox.x, '重命名/删除应位于名称与展开箭头之间')
  assert.equal(await page.getByLabel('组合名称').count(), 0, '非重命名状态不重复显示组合名称输入框')
  await activeBundleCard.getByRole('button', { name: '重命名组合', exact: true }).click()
  assert.equal(await page.getByLabel('组合名称').evaluate(element => document.activeElement === element), true, '重命名按钮聚焦组合名称')
  await page.getByLabel('组合名称').fill('主管办公室组合')
  await page.getByText('组合已保存', { exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '保存组合', exact: true }).count(), 0, '自动保存后移除保存按钮')
  assert.equal(await page.getByRole('button', { name: '删除组合', exact: true }).count(), 0, '底部删除按钮移除')
  assert.equal(await page.getByRole('button', { name: '关闭编辑', exact: true }).count(), 0, '关闭编辑按钮移除')
  assert.equal(await page.getByRole('button', { name: '加入规格', exact: true }).count(), 0, '不再显示加入规格')
  assert.equal(await page.getByRole('button', { name: '取消选规格', exact: true }).count(), 0, '不再显示取消选规格')
  await activeBundleCard.getByRole('button', { name: '收起组合', exact: true }).click()
  await bundlePanel.locator('form.bundle-editor').waitFor({ state: 'hidden' })
  await bundlePanel.getByTestId('bundle-card').getByRole('button', { name: '展开组合', exact: true }).click()
  await bundlePanel.locator('form.bundle-editor').waitFor({ state: 'visible' })
  await bundlePanel.getByTestId('bundle-add-slot').waitFor()
  await bundlePanel.getByTestId('bundle-add-slot').click()
  await bundlePanel.getByText('请先在商品库选择商品加入组合', { exact: false }).waitFor()
  await bundlePanel.getByTestId('bundle-add-slot').click()
  await bundlePanel.locator('.bundle-product-options').waitFor({ state: 'hidden' })
  await bundlePanel.getByRole('button', { name: '＋ 创建新的组合', exact: true }).waitFor()
  await bundlePanel.locator('.bundle-item-image').waitFor()
  assert.equal(await bundlePanel.locator('.bundle-item-image').count(), 1, '组合卡片展示商品图片')
  await bundlePanel.getByRole('button', { name: '收起商品', exact: true }).click()
  await bundlePanel.locator('.bundle-items').waitFor({ state: 'hidden' })
  assert.equal(await bundlePanel.locator('.bundle-item-image').isVisible(), true, '收起商品明细时图片区域保持显示')
  await bundlePanel.getByRole('button', { name: '展开商品', exact: true }).click()
  await bundlePanel.locator('.bundle-items').waitFor({ state: 'visible' })
  if (process.env.SCHEME_SCREENSHOT_DIR) await page.screenshot({ path: process.env.SCHEME_SCREENSHOT_DIR + '/bundle-card.png' })
  const schemeSourceAccent = await backgroundOf(currentNav)
  await page.waitForFunction(expected => getComputedStyle(document.querySelector('[data-testid="tab-product-selection"]')).backgroundColor === expected, schemeSourceAccent)
  if (process.env.SCHEME_SCREENSHOT_DIR) await page.screenshot({ path: process.env.SCHEME_SCREENSHOT_DIR + '/scheme-bundles.png' })
  assert.equal(await page.getByText('产品选型 · 手动排版', { exact: false }).count(), 0, '项目选品库不再显示家具手动排版')
  assert.equal(await page.getByRole('button', { name: '收藏', exact: true }).count(), 1, '产品选型提供统一收藏入口')
  await page.getByRole('button', { name: '收藏', exact: true }).click()
  await page.getByTestId('tab-product-selection').click()
  assert.equal(await page.getByTestId('tab-product-selection').getAttribute('aria-selected'), 'true', '来源内容显示时必须选中产品选型')
  const schemeFavorites = page.getByTestId('scheme-favorites-panel')
  await schemeFavorites.waitFor()
  const projectFavoriteTab = schemeFavorites.getByRole('tab', { name: '项目收藏夹', exact: true })
  const personalFavoriteTab = schemeFavorites.getByRole('tab', { name: '个人收藏夹', exact: true })
  assert.equal(await projectFavoriteTab.getAttribute('aria-selected'), 'true', '收藏默认打开项目收藏夹')
  await schemeFavorites.getByText('该项目还没有收藏的商品', { exact: false }).waitFor()
  if (process.env.SCHEME_SCREENSHOT_DIR) await page.screenshot({ path: process.env.SCHEME_SCREENSHOT_DIR + '/scheme-project-favorites.png' })
  await personalFavoriteTab.click()
  await schemeFavorites.getByText('胡桃木办公桌', { exact: true }).waitFor()
  if (process.env.SCHEME_SCREENSHOT_DIR) await page.screenshot({ path: process.env.SCHEME_SCREENSHOT_DIR + '/scheme-personal-favorites.png' })
  assert.equal(await projectFavoriteTab.getAttribute('aria-selected'), 'false')
  assert.equal(await personalFavoriteTab.getAttribute('aria-selected'), 'true')
  await projectFavoriteTab.click()
  await schemeFavorites.getByText('该项目还没有收藏的商品', { exact: false }).waitFor()
  await page.getByRole('button', { name: '款式推荐', exact: true }).click()
  assert.equal(await schemeBundles.count(), 0, '款式推荐隐藏组合面板')
  assert.equal(await schemeFavorites.count(), 0, '款式推荐隐藏收藏面板')
  await stepLayout.click()
  await page.getByText('产品选型 · 手动排版', { exact: false }).waitFor()
  assert.equal(await page.getByRole('button', { name: '项目选品库', exact: true }).count(), 0, '排版步骤隐藏选品来源')
  assert.equal(await schemeBundles.count(), 0, '返回排版后隐藏组合面板')
  assert.equal(await schemeFavorites.count(), 0, '返回排版后隐藏收藏面板')
  await stepSelect.click()
  await page.getByRole('button', { name: '项目选品库', exact: true }).click()
  const plan = name => page.getByRole('button', { name: `Plan ${name}`, exact: true })
  await page.locator('button[aria-pressed=true]').filter({ hasText: 'Plan A' }).waitFor()
  // Plan B 按需创建：初始只有 Plan A 和「+」按钮
  assert.equal(await plan('B').count(), 0)
  await page.getByRole('button', { name: '新建 Plan B' }).click()
  await page.getByText('已创建 Plan B', { exact: false }).waitFor()
  await plan('B').waitFor()
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
  // 有 Plan B 但未确认方案选择：横幅展示两个方案的家具对比，确认方案被预检查拦截
  const choiceBanner = page.getByTestId('proposal-scheme-choice-issues')
  await choiceBanner.getByText('1 个空间待确认方案', { exact: true }).waitFor()
  await choiceBanner.locator('summary').click()
  await choiceBanner.getByText('副总办公室', { exact: true }).waitFor()
  await choiceBanner.getByText('1 件家具', { exact: true }).first().waitFor()
  assert.equal(await choiceBanner.getByText('1 件家具', { exact: true }).count(), 2, 'Plan A/B 应各显示家具件数')
  assert.ok((await choiceBanner.getByText('胡桃木办公桌 × 1', { exact: true }).count()) >= 1)
  await page.getByRole('button', { name: '确认方案', exact: true }).click()
  const precheckModal = page.getByTestId('proposal-precheck-modal')
  await precheckModal.waitFor()
  await precheckModal.getByText('方案选择未确认', { exact: true }).waitFor()
  await precheckModal.getByText('副总办公室', { exact: true }).click()
  await precheckModal.waitFor({ state: 'hidden' })
  // 确认使用当前 Plan B：按钮锁定，选中的 Plan B 保持选中样式，Plan A 不可点击
  await page.getByTestId('scheme-choice-confirm').click()
  await page.getByText('副总办公室 已确认使用 Plan B', { exact: true }).waitFor()
  await choiceBanner.waitFor({ state: 'hidden' })
  assert.equal(await plan('A').isDisabled(), true, '确认后 Plan A 不可点击')
  assert.equal(await plan('B').getAttribute('aria-pressed'), 'true', '确认后 Plan B 保持选中')
  assert.equal(await plan('B').isDisabled(), true, '确认后 Plan B 也不可再切换')
  // 取消确认恢复切换，横幅重新出现
  await page.getByTestId('scheme-choice-cancel').click()
  await page.getByText('副总办公室 已取消方案确认，可重新切换', { exact: true }).waitFor()
  await choiceBanner.waitFor({ state: 'visible' })
  assert.equal(await plan('A').isDisabled(), false, '取消后恢复切换')
  await page.getByTestId('scheme-choice-confirm').click()
  await page.getByText('副总办公室 已确认使用 Plan B', { exact: true }).waitFor()
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
  console.log('Product scheme mock UI passed: bundle, Plan A/B create, choice confirm/cancel lock, clear, confirmation after reload, formal render, narrow layout')
} finally {
  await browser?.close()
  app.kill('SIGTERM')
}
