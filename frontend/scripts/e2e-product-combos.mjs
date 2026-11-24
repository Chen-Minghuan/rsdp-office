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

function comboDto(combo) {
  return {
    comboId: combo.comboId,
    name: combo.name,
    items: combo.rspuIds.map(rspuId => ({
      rspuId,
      productName: products[rspuId]?.productName ?? null,
      primaryImageUrl: null,
      retailPrice: products[rspuId]?.retailPrice ?? null,
      available: Boolean(products[rspuId]),
    })),
  }
}

async function openPage(initialCombos = []) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  const state = { combos: initialCombos, saves: [], removes: [], renames: [], deleted: [] }
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: dealerUser } }))
  await context.route('**/api/v1/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const respond = data => route.fulfill({ json: { code: 200, message: '成功', data } })
    const reject = message => route.fulfill({ json: { code: 400, message, data: null } })
    if (path === '/api/v1/office/combos' && request.method() === 'GET') {
      const snapshot = state.combos.map(combo => comboDto(combo))
      const delay = state.comboGetDelays?.length ? state.comboGetDelays.shift() : 0
      if (delay) await new Promise(resolve => setTimeout(resolve, delay))
      return respond(snapshot)
    }
    if (path === '/api/v1/office/combos' && request.method() === 'POST') {
      const name = (request.postDataJSON().name || '').trim()
      if (!name) return reject('组合名称不能为空')
      if (state.combos.length >= 10) return reject('最多创建10个组合')
      if (state.combos.some(combo => combo.name === name)) return reject('组合名称已存在')
      const combo = { comboId: `CB-${state.combos.length + 1}`, name, rspuIds: [] }
      state.combos.push(combo)
      return respond(comboDto(combo))
    }
    const comboMatch = path.match(/^\/api\/v1\/office\/combos\/([^/]+)$/)
    const itemsMatch = path.match(/^\/api\/v1\/office\/combos\/([^/]+)\/items$/)
    const itemMatch = path.match(/^\/api\/v1\/office\/combos\/([^/]+)\/items\/([^/]+)$/)
    if (itemsMatch && request.method() === 'POST') {
      const combo = state.combos.find(item => item.comboId === itemsMatch[1])
      if (!combo) return reject('组合不存在')
      const additions = (request.postDataJSON().rspuIds || []).filter(id => !combo.rspuIds.includes(id))
      if (combo.rspuIds.length + additions.length > 5) return reject('每个组合最多5个商品')
      state.saves.push({ comboId: combo.comboId, rspuIds: additions })
      combo.rspuIds = [...combo.rspuIds, ...additions]
      return respond(null)
    }
    if (itemMatch && request.method() === 'DELETE') {
      const combo = state.combos.find(item => item.comboId === itemMatch[1])
      if (!combo) return reject('组合不存在')
      state.removes.push({ comboId: combo.comboId, rspuId: itemMatch[2] })
      combo.rspuIds = combo.rspuIds.filter(id => id !== itemMatch[2])
      return respond(null)
    }
    if (comboMatch) {
      const combo = state.combos.find(item => item.comboId === comboMatch[1])
      if (!combo) return reject('组合不存在')
      if (request.method() === 'PATCH') {
        const name = (request.postDataJSON().name || '').trim()
        if (!name) return reject('组合名称不能为空')
        state.renames.push({ comboId: combo.comboId, name })
        combo.name = name
        return respond(null)
      }
      if (request.method() === 'DELETE') {
        state.deleted.push(combo.comboId)
        state.combos = state.combos.filter(item => item.comboId !== combo.comboId)
        return respond(null)
      }
    }
    if (path === '/api/v1/products') return respond(Object.values(products))
    if (path === '/api/v1/products/capabilities') return respond({ managed: false })
    if (path === '/api/v1/office/workspace/shared-catalog') return respond({ workspaceEnabled: true, dealer: true, effectiveVisible: true })
    if (path === '/api/v1/office/projects') return respond([])
    return respond(path.endsWith('/workspace/me') ? null : [])
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${baseUrl}/products`, { waitUntil: 'networkidle' })
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

  // 空位＋ → 选品弹窗勾选 3 个商品
  await card.getByRole('button', { name: '添加商品', exact: true }).click()
  const picker = page.getByRole('dialog', { name: '选择商品' })
  await picker.getByRole('searchbox', { name: '搜索商品' }).fill('测试商品')
  for (const id of ['P1', 'P2', 'P3']) {
    await picker.getByRole('checkbox', { name: products[id].productName, exact: true }).check()
  }
  await picker.getByRole('button', { name: '确认加入', exact: true }).click()
  await picker.waitFor({ state: 'hidden' })
  assert.match(await card.innerText(), /3\/5/)
  assert.deepEqual(state.saves.at(-1), { comboId: 'CB-1', rspuIds: ['P1', 'P2', 'P3'] })

  // 展开 / 收起明细
  await card.getByRole('button', { name: '展开商品' }).click()
  const itemList = card.getByTestId('combo-item-list')
  await itemList.waitFor({ state: 'visible' })
  for (const id of ['P1', 'P2', 'P3']) {
    assert.match(await itemList.innerText(), new RegExp(products[id].productName))
  }
  await card.getByRole('button', { name: '收起商品' }).click()
  await itemList.waitFor({ state: 'hidden' })

  // 连续拖入两件商品：增量保存，两件都保留
  await page.getByTestId('product-card-P4').dragTo(card)
  await page.waitForFunction(() => document.querySelector('[data-testid="combo-card"]')?.textContent?.includes('4/5'))
  await page.getByTestId('product-card-P5').dragTo(card)
  await page.waitForFunction(() => document.querySelector('[data-testid="combo-card"]')?.textContent?.includes('5/5'))
  assert.deepEqual(state.saves.at(-2), { comboId: 'CB-1', rspuIds: ['P4'] })
  assert.deepEqual(state.saves.at(-1), { comboId: 'CB-1', rspuIds: ['P5'] })

  // 刷新后组合仍在（后端持久化）
  await page.reload({ waitUntil: 'networkidle' })
  const reloadedCard = page.getByTestId('combo-card').first()
  await reloadedCard.waitFor({ state: 'visible' })
  assert.match(await reloadedCard.innerText(), /5\/5/)

  // 超过 5 个：拦截并提示，组合内容不变（后端上限由 Java 用例覆盖）
  await page.getByTestId('product-card-P6').dragTo(reloadedCard)
  await page.getByText('每个组合最多5个商品', { exact: false }).first().waitFor({ state: 'visible' })
  assert.match(await reloadedCard.innerText(), /5\/5/, '超限后组合内容不变')
  assert.equal(state.saves.filter(save => save.rspuIds.includes('P6')).length, 0, '超限商品不会提交保存')

  // 改名
  await reloadedCard.getByRole('button', { name: '重命名', exact: true }).click()
  const renameDialog = page.getByRole('dialog', { name: '命名组合' })
  const renameInput = renameDialog.getByRole('textbox', { name: '组合名称' })
  assert.equal(await renameInput.inputValue(), '会议洽谈组合', '改名弹窗预填当前名称')
  await renameInput.fill('洽谈区组合')
  await renameDialog.getByRole('button', { name: '确认', exact: true }).click()
  await renameDialog.waitFor({ state: 'hidden' })
  assert.match(await reloadedCard.innerText(), /洽谈区组合/)
  assert.deepEqual(state.renames, [{ comboId: 'CB-1', name: '洽谈区组合' }])

  // 删除需确认
  await reloadedCard.getByRole('button', { name: '删除', exact: true }).click()
  const deleteDialog = page.getByRole('dialog', { name: '删除组合' })
  await deleteDialog.getByRole('button', { name: '确认删除', exact: true }).click()
  await page.getByTestId('combo-card').waitFor({ state: 'detached' })
  assert.deepEqual(state.deleted, ['CB-1'])

  assert.deepEqual(errors, [], '页面不能有未捕获异常')
  await context.close()

  // 组内含失效商品：仍可添加其他商品，也可移除失效条目
  const invalid = await openPage([{ comboId: 'CB-1', name: '含失效商品组合', rspuIds: ['P-GONE'] }])
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
  assert.deepEqual(invalid.state.removes, [{ comboId: 'CB-1', rspuId: 'P-GONE' }])
  assert.deepEqual(invalid.errors, [])
  await invalid.context.close()

  // 连续拖入时先发出的回拉最后返回：界面不能被旧列表覆盖
  const race = await openPage([{ comboId: 'CB-1', name: '连续组合', rspuIds: ['P1', 'P2', 'P3'] }])
  const raceCard = race.page.getByTestId('combo-card').first()
  await raceCard.waitFor({ state: 'visible' })
  race.state.comboGetDelays = [700, 0]
  await race.page.getByTestId('product-card-P4').dragTo(raceCard)
  await race.page.getByTestId('product-card-P5').dragTo(raceCard)
  await race.page.waitForFunction(() => document.querySelector('[data-testid="combo-card"]')?.textContent?.includes('5/5'))
  await race.page.waitForTimeout(900)
  assert.match(await raceCard.innerText(), /5\/5/, '迟到的旧回拉不得把界面写回 4 件')
  assert.deepEqual(race.errors, [])
  await race.context.close()

  // 每账号最多 10 个组合：达到上限后创建入口禁用
  const full = await openPage(Array.from({ length: 10 }, (_, index) => ({
    comboId: `CB-${index + 1}`, name: `组合${index + 1}`, rspuIds: [],
  })))
  const fullPanel = full.page.getByTestId('combo-panel')
  await fullPanel.getByTestId('combo-card').first().waitFor({ state: 'visible' })
  assert.equal(await fullPanel.getByTestId('combo-card').count(), 10)
  assert.equal(await fullPanel.getByRole('button', { name: '创建新的组合' }).isDisabled(), true, '10 个组合后创建按钮禁用')
  assert.deepEqual(full.errors, [])
  await full.context.close()

  console.log('E2E product combos passed')
} finally {
  await browser.close()
}
