import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const baseUrl = process.env.BASE_URL || 'http://localhost:3001'
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })

const products = Object.fromEntries(Array.from({ length: 6 }, (_, index) => {
  const id = `P${index + 1}`
  return [id, {
    rspuId: id,
    rspuCode: `RSPU-${index + 1}`,
    productName: `测试商品${index + 1}号`,
    primaryImageUrl: null,
    retailPrice: 100 * (index + 1),
    status: 'active',
    updatedAt: '2026-11-20T10:00:00',
  }]
}))
const dealerUser = { userId: 'DEALER-1', username: 'dealer', displayName: '开发经销商主号', role: 'DEALER', workspaceType: 'DEALER' }

function bundleDto(combo) {
  return {
    bundleId: combo.bundleId,
    name: combo.name,
    revision: combo.revision,
    items: combo.items.map(item => ({
      ...item,
      name: products[item.rspuId]?.productName ?? null,
      variant: '默认规格',
      imageUrl: null,
    })),
  }
}

async function openPage(initialCombos = []) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  const state = { combos: initialCombos, saves: [], deleted: [], listReads: 0 }
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: dealerUser } }))
  await context.route('**/api/v1/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const respond = data => route.fulfill({ json: { code: 200, message: '成功', data } })
    const reject = message => route.fulfill({ json: { code: 400, message, data: null } })
    if (path === '/api/v1/office/product-bundles' && request.method() === 'GET') {
      state.listReads += 1
      return respond(state.combos.map(bundleDto))
    }
    const bundleMatch = path.match(/^\/api\/v1\/office\/product-bundles\/([^/]+)$/)
    if (bundleMatch && request.method() === 'PUT') {
      const body = request.postDataJSON()
      const existing = state.combos.find(combo => combo.bundleId === bundleMatch[1])
      if ((body.revision || 0) !== (existing?.revision || 0)) return reject('商品组合已变化，请重新读取后编辑')
      const combo = existing || { bundleId: bundleMatch[1], name: body.name, revision: 0, items: [] }
      combo.name = body.name
      combo.items = body.items
      combo.revision = (existing?.revision || 0) + 1
      if (!existing) state.combos.push(combo)
      state.saves.push({ bundleId: combo.bundleId, name: combo.name, revision: combo.revision, items: combo.items })
      return respond(bundleDto(combo))
    }
    if (bundleMatch && request.method() === 'DELETE') {
      const combo = state.combos.find(item => item.bundleId === bundleMatch[1])
      if (!combo) return reject('组合不存在')
      if (Number(url.searchParams.get('revision')) !== combo.revision) return reject('商品组合已变化或不存在')
      state.deleted.push(combo.bundleId)
      state.combos = state.combos.filter(item => item.bundleId !== combo.bundleId)
      return respond(null)
    }
    const productMatch = path.match(/^\/api\/v1\/products\/([^/]+)$/)
    if (productMatch && products[productMatch[1]]) {
      return respond({ variants: [{ variantId: 'V1', displayName: '默认规格', status: 'active' }] })
    }
    if (path === '/api/v1/products/browse') {
      const rows = Object.values(products).map(product => ({ local: null, shared: product }))
      return respond({
        rows, total: rows.length, catalogTotal: rows.length, activeTotal: rows.length,
        localTotal: 0, canManageLocal: false, sharedUnavailable: false,
      })
    }
    if (path === '/api/v1/products/page') {
      const term = (url.searchParams.get('search') || '').toLowerCase()
      const rows = Object.values(products).filter(product => !term
        || [product.productName, product.rspuCode, product.rspuId].some(value => value?.toLowerCase().includes(term)))
      return respond({ rows, total: rows.length, catalogTotal: rows.length, activeTotal: rows.length })
    }
    if (path === '/api/v1/products/capabilities') return respond({ managed: false })
    if (path === '/api/v1/office/workspace/shared-catalog') return respond({ workspaceEnabled: true, dealer: true, effectiveVisible: true })
    if (path === '/api/v1/office/projects') return respond([])
    return respond(path.endsWith('/workspace/me') ? null : [])
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${baseUrl}/products`, { waitUntil: 'networkidle' })
  state.listReads = 0
  return { context, page, errors, state }
}

try {
  const { context, page, errors, state } = await openPage()

  const panel = page.getByTestId('combo-panel')
  await panel.waitFor({ state: 'visible' })
  assert.equal(await panel.getByTestId('combo-card').count(), 0, '初始没有组合卡')

  // 创建：命名弹窗确认后才出现组合卡
  await panel.getByRole('button', { name: '创建新的组合' }).click()
  const nameDialog = page.getByRole('dialog', { name: '命名组合' })
  await nameDialog.getByRole('textbox', { name: '组合名称' }).fill('会议洽谈组合')
  await nameDialog.getByRole('button', { name: '确认', exact: true }).click()
  await nameDialog.waitFor({ state: 'hidden' })
  const card = panel.getByTestId('combo-card').first()
  await card.waitFor({ state: 'visible' })
  assert.match(await card.innerText(), /会议洽谈组合/)
  assert.match(await card.innerText(), /0\/5/)
  assert.equal(state.saves.at(-1).revision, 1, '创建后 revision 从 0 变为 1')

  // 空位＋ → 选品弹窗勾选 3 个商品（默认规格）
  await card.getByRole('button', { name: '添加商品', exact: true }).click()
  const picker = page.getByRole('dialog', { name: '选择商品' })
  await picker.getByRole('searchbox', { name: '搜索商品' }).fill('测试商品')
  for (const id of ['P1', 'P2', 'P3']) {
    await picker.getByRole('checkbox', { name: products[id].productName, exact: true }).check()
  }
  await picker.getByRole('button', { name: '确认加入', exact: true }).click()
  await picker.waitFor({ state: 'hidden' })
  assert.match(await card.innerText(), /3\/5/)
  assert.deepEqual(state.saves.at(-1).items.map(item => item.rspuId), ['P1', 'P2', 'P3'])
  assert.equal(state.saves.at(-1).revision, 2)

  // 展开 / 收起明细
  await card.getByRole('button', { name: '展开商品' }).click()
  const itemList = card.getByTestId('combo-item-list')
  await itemList.waitFor({ state: 'visible' })
  for (const id of ['P1', 'P2', 'P3']) {
    assert.match(await itemList.innerText(), new RegExp(products[id].productName))
  }
  await card.getByRole('button', { name: '收起商品' }).click()
  await itemList.waitFor({ state: 'hidden' })

  // 连续拖入两件商品：本地用保存结果更新，两件都保留
  await page.getByTestId('product-card-P4').dragTo(card)
  await page.waitForFunction(() => document.querySelector('[data-testid="combo-card"]')?.textContent?.includes('4/5'))
  await page.getByTestId('product-card-P5').dragTo(card)
  await page.waitForFunction(() => document.querySelector('[data-testid="combo-card"]')?.textContent?.includes('5/5'))
  assert.deepEqual(state.saves.at(-1).items.map(item => item.rspuId), ['P1', 'P2', 'P3', 'P4', 'P5'])
  assert.equal(state.listReads, 0, '增删改名不触发整表回拉，无旧列表覆盖问题')

  // 刷新后组合仍在（后端持久化）
  await page.reload({ waitUntil: 'networkidle' })
  const reloadedCard = page.getByTestId('combo-card').first()
  await reloadedCard.waitFor({ state: 'visible' })
  assert.match(await reloadedCard.innerText(), /5\/5/)

  // 超过 5 个：拦截并提示，组合内容不变
  await page.getByTestId('product-card-P6').dragTo(reloadedCard)
  await page.getByText('每个组合最多5个商品', { exact: false }).first().waitFor({ state: 'visible' })
  assert.match(await reloadedCard.innerText(), /5\/5/)
  assert.equal(state.saves.filter(save => save.items.some(item => item.rspuId === 'P6')).length, 0)

  // 重命名：弹窗预填当前名，保存后 revision 递增、商品不变
  await reloadedCard.getByRole('button', { name: '重命名', exact: true }).click()
  const renameDialog = page.getByRole('dialog', { name: '命名组合' })
  const renameInput = renameDialog.getByRole('textbox', { name: '组合名称' })
  assert.equal(await renameInput.inputValue(), '会议洽谈组合')
  await renameInput.fill('洽谈区组合')
  await renameDialog.getByRole('button', { name: '确认', exact: true }).click()
  await renameDialog.waitFor({ state: 'hidden' })
  assert.match(await reloadedCard.innerText(), /洽谈区组合/)
  assert.equal(state.saves.at(-1).name, '洽谈区组合')
  assert.equal(state.saves.at(-1).items.length, 5, '重命名不覆盖商品')

  // 删除需确认
  await reloadedCard.getByRole('button', { name: '删除', exact: true }).click()
  const deleteDialog = page.getByRole('dialog', { name: '删除组合' })
  await deleteDialog.getByRole('button', { name: '确认删除', exact: true }).click()
  await page.getByTestId('combo-card').waitFor({ state: 'detached' })
  assert.deepEqual(state.deleted.length, 1)

  assert.deepEqual(errors, [], '页面不能有未捕获异常')
  await context.close()

  // 组内含失效商品：标注已失效，仍可添加其他商品，也可移除失效条目
  const invalid = await openPage([{
    bundleId: 'CB-1', name: '含失效商品组合', revision: 1,
    items: [{ rspuId: 'P-GONE', variantId: 'V1', quantity: 1 }],
  }])
  const invCard = invalid.page.getByTestId('combo-card').first()
  await invCard.waitFor({ state: 'visible' })
  assert.match(await invCard.innerText(), /已失效/)
  await invCard.getByRole('button', { name: '添加商品', exact: true }).click()
  const invPicker = invalid.page.getByRole('dialog', { name: '选择商品' })
  await invPicker.getByRole('checkbox', { name: products.P1.productName, exact: true }).check()
  await invPicker.getByRole('button', { name: '确认加入', exact: true }).click()
  await invPicker.waitFor({ state: 'hidden' })
  assert.match(await invCard.innerText(), /2\/5/)
  await invCard.getByRole('button', { name: '展开商品' }).click()
  await invCard.getByRole('button', { name: '移除P-GONE', exact: true }).click()
  await invalid.page.waitForFunction(() => document.querySelector('[data-testid="combo-card"]')?.textContent?.includes('1/5'))
  assert.deepEqual(invalid.state.saves.at(-1).items.map(item => item.rspuId), ['P1'])
  assert.deepEqual(invalid.errors, [])
  await invalid.context.close()

  // 每账号最多 10 个组合：达到上限后创建入口禁用
  const full = await openPage(Array.from({ length: 10 }, (_, index) => ({
    bundleId: `CB-${index + 1}`, name: `组合${index + 1}`, revision: 1, items: [],
  })))
  const fullPanel = full.page.getByTestId('combo-panel')
  await fullPanel.getByTestId('combo-card').first().waitFor({ state: 'visible' })
  assert.equal(await fullPanel.getByTestId('combo-card').count(), 10)
  assert.equal(await fullPanel.getByRole('button', { name: '创建新的组合' }).isDisabled(), true)
  assert.deepEqual(full.errors, [])
  await full.context.close()

  console.log('E2E product combos passed')
} finally {
  await browser.close()
}
