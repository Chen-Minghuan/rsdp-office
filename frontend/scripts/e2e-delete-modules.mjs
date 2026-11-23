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
const app = spawn(process.execPath, ['.output/server/index.mjs'], { cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' }, stdio: 'ignore' })
const outDir = process.env.OUT_DIR || './.tmp/rsdp-delete-modules'
let browser, debugPage
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先 pnpm build；预览服务未就绪')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  await mkdir(outDir, { recursive: true })
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1750, height: 1150 } })
  const page = await context.newPage(), errors = []
  debugPage = page
  page.on('pageerror', error => errors.push(error.message))
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  const deletedCartLineIds = []
  const cartMutations = []
  const serverCartQuantities = new Map()
  let holdSharedUpdate = null
  await context.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const floorId = path.match(/^\/api\/v1\/office\/projects\/([^/]+)\/cart/)?.[1] || null
    if (floorId && ['PUT', 'DELETE'].includes(route.request().method())) {
      cartMutations.push({ floorId, method: route.request().method(), lineId: path.split('/').pop(), qty: url.searchParams.get('qty') })
    }
    if (path === '/api/v1/office/projects' && route.request().method() === 'GET') {
      const project = {
        projectId: 'OFFICE-PROJECT-001', name: '删除测试项目', customerRequirements: '', requirementsRevision: 1,
        leader: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'LEADER' }, collaborators: [], referenceImages: [],
        floors: [
          { projectId: 'OFFICE-PROJECT-001', floorId: 'OFFICE-PROJECT-001', name: '默认楼层', sortOrder: 1 },
          { projectId: 'OFFICE-PROJECT-001', floorId: 'OFFICE-FLOOR-002', name: '二层', sortOrder: 2 },
        ],
      }
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: [project] }) })
    }
    if (path.includes('/cart/') && route.request().method() === 'PUT') {
      const lineId = path.split('/').pop()
      const qty = Number(url.searchParams.get('qty'))
      if (lineId === 'CART-SHARED' && holdSharedUpdate) await holdSharedUpdate
      serverCartQuantities.set(lineId, qty)
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: { lineId: path.split('/').pop(), projectId: 'OFFICE-PROJECT-001', zoneId: 'DELETE-ROOM', zoneName: '删除测试空间', productId: 'SHARED-PRODUCT', qty: Number(url.searchParams.get('qty')) } }) })
    }
    if (path.endsWith('/cart') && route.request().method() === 'POST') {
      const body = JSON.parse(route.request().postData() || '{}')
      const lineId = 'RESTORED-' + body.lineId
      serverCartQuantities.set(lineId, body.qty)
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: { ...body, lineId } }) })
    }
    if (path.includes('/cart/') && route.request().method() === 'DELETE') {
      deletedCartLineIds.push(path.split('/').pop())
      serverCartQuantities.delete(path.split('/').pop())
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: null }) })
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : [] }) })
  })
  const storageKey = 'rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001'
  const state = () => page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    return JSON.parse(JSON.stringify(store.$state))
  })
  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.getByRole('img', { name: '空间核对画布', exact: true }).waitFor()
  await page.evaluate(key => {
    const draft = JSON.parse(localStorage.getItem(key)) || {}
    draft.objects = [
      { id: 'DELETE-ROOM', kind: 'zone', x: -10000, y: -6000, w: 12000, h: 8000, name: '删除测试空间', zoneType: 'OPEN_OFFICE', seats: 0, note: '', tone: 'blue', reviewUnit: 'mm', reviewConfirmation: { status: 'CONFIRMED' }, evidenceKind: 'MANUAL', geometrySource: 'manual_geometry', polygon: [[-10000,-6000],[2000,-6000],[2000,2000],[-10000,2000]] },
      { id: 'OTHER-ROOM', kind: 'zone', x: 10000, y: 0, w: 6000, h: 4000, name: '其他空间', zoneType: 'MEETING_ROOM', seats: 0, note: '', tone: 'blue', reviewUnit: 'mm', reviewConfirmation: { status: 'CONFIRMED' }, evidenceKind: 'MANUAL', geometrySource: 'manual_geometry', polygon: [[10000,0],[16000,0],[16000,4000],[10000,4000]] },
      ...['DELETE-A','DELETE-B'].map((id,index) => ({ id, kind: 'furniture', zoneId: 'DELETE-ROOM', furnitureTemplateId: 'desk-straight', productId: '', label: id, x: -9400 + index * 2500, y: -5000, w: 1200, h: 600, rotation: 0, layoutConfirmed: true })),
      { id: 'KEEP-OTHER', kind: 'furniture', zoneId: 'OTHER-ROOM', furnitureTemplateId: 'chair-office', productId: '', x: 11000, y: 1000, w: 650, h: 650, rotation: 0, layoutConfirmed: true },
    ]
    draft.objects.find(item => item.id === 'DELETE-A').productId = 'SELECTED-PRODUCT'
    draft.objects.find(item => item.id === 'DELETE-A').productSelection = { product: { id: 'SELECTED-PRODUCT' }, cartLineId: 'CART-DELETE-A', sourceCanvasRevision: draft.canvasRevision || 1 }
    draft.objects.find(item => item.id === 'DELETE-B').productId = 'SELECTED-PRODUCT'
    draft.objects.find(item => item.id === 'DELETE-B').productSelection = { product: { id: 'SELECTED-PRODUCT' }, cartLineId: 'CART-DELETE-B', sourceCanvasRevision: draft.canvasRevision || 1 }
    draft.cart = [
      { id: 'CART-DELETE-A', projectId: 'OFFICE-PROJECT-001', productId: 'SELECTED-PRODUCT', zoneId: 'DELETE-ROOM', zoneName: '删除测试空间', qty: 1 },
      { id: 'CART-DELETE-B', projectId: 'OFFICE-PROJECT-001', productId: 'SELECTED-PRODUCT', zoneId: 'DELETE-ROOM', zoneName: '删除测试空间', qty: 1 },
      { id: 'CART-EXTRA', projectId: 'OFFICE-PROJECT-001', productId: 'SELECTED-PRODUCT', zoneId: 'DELETE-ROOM', zoneName: '删除测试空间', qty: 1 },
    ]
    draft.floorPlan = null
    draft.spaceTemplate = null
    draft.canvasMode = 'overview'
    draft.designZoneId = null
    localStorage.setItem(key, JSON.stringify(draft))
  }, storageKey)
  await page.goto(baseUrl + '/canvas?mode=design&zoneId=DELETE-ROOM', { waitUntil: 'networkidle' })
  await page.getByTestId('office-design-canvas').waitFor()
  await page.getByTestId('functional-category-workstation').click()
  const initial = await state()
  assert.equal(initial.objects.find(item => item.id === 'DELETE-A').productSelection?.cartLineId, 'CART-DELETE-A')
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.$onAction(({ name, args }) => { if (name === 'deleteFurnitureGroup') window.deleteFurnitureArgs = JSON.parse(JSON.stringify(args)) })
  })
  const furniture = async () => (await state()).objects.filter(item => item.kind === 'furniture')
  const choose = id => page.getByTestId('office-design-canvas').locator('[data-id="' + id + '"]').click()
  await choose('DELETE-A')
  await page.getByRole('button', { name: '删除选中模块', exact: true }).waitFor()
  await page.screenshot({ path: outDir + '/01-delete-selected.png', animations: 'disabled' })
  const cartDeleteResponse = page.waitForResponse(response => response.request().method() === 'DELETE' && new URL(response.url()).pathname.endsWith('/cart/CART-DELETE-A'))
  await page.getByRole('button', { name: '删除选中模块', exact: true }).click()
  await cartDeleteResponse
  const removed = await furniture()
  assert.deepEqual(await page.evaluate(() => window.deleteFurnitureArgs), [['DELETE-A'], 'DELETE-ROOM'])
  assert.equal(removed.some(item => item.id === 'DELETE-A'), false)
  assert.deepEqual((await state()).cart.map(item => item.id), ['CART-DELETE-B', 'CART-EXTRA'])
  assert.deepEqual(deletedCartLineIds, ['CART-DELETE-A'])
  assert.equal(removed.find(item => item.id === 'DELETE-B').layoutConfirmed, false)
  assert.deepEqual(removed.find(item => item.id === 'KEEP-OTHER'), initial.objects.find(item => item.id === 'KEEP-OTHER'))
  assert.equal(await page.getByTestId('current-furniture-count').first().innerText(), '1')
  assert.equal(await page.getByRole('button', { name: '删除选中模块', exact: true }).count(), 0)
  await page.getByRole('status').filter({ hasText: '已删除模块' }).waitFor()
  await page.getByRole('button', { name: '确认当前排版', exact: true }).waitFor()
  const cartRestoreResponse = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith('/cart'))
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await cartRestoreResponse
  assert.deepEqual((await furniture()).map(item => item.id), initial.objects.filter(item => item.kind === 'furniture').map(item => item.id))
  const restoredA = (await state()).objects.find(item => item.id === 'DELETE-A')
  assert.equal(restoredA.productSelection.cartLineId, 'RESTORED-CART-DELETE-A')
  assert.deepEqual((await state()).cart.map(item => item.id), ['RESTORED-CART-DELETE-A', 'CART-DELETE-B', 'CART-EXTRA'])
  assert.equal(await page.getByRole('status').filter({ hasText: '已删除模块' }).count(), 0)
  const keyboardDeleteResponse = page.waitForResponse(response => response.request().method() === 'DELETE' && new URL(response.url()).pathname.endsWith('/cart/RESTORED-CART-DELETE-A'))
  await choose('DELETE-A')
  await page.keyboard.press('Delete')
  await keyboardDeleteResponse
  assert.equal((await furniture()).some(item => item.id === 'DELETE-A'), false)
  assert.deepEqual((await state()).cart.map(item => item.id), ['CART-DELETE-B', 'CART-EXTRA'])
  const keyboardRestoreResponse = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith('/cart'))
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await keyboardRestoreResponse
  const keyboardRestoredA = (await state()).objects.find(item => item.id === 'DELETE-A')
  assert.equal(keyboardRestoredA.productSelection.cartLineId, 'RESTORED-RESTORED-CART-DELETE-A')
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const item = store.objects.find(object => object.kind === 'furniture' && object.id === 'DELETE-B')
    item.productId = ''
    item.productSelection = undefined
  })
  await choose('DELETE-B')
  await page.getByRole('button', { name: '删除选中模块', exact: true }).click()
  assert.equal((await furniture()).some(item => item.id === 'DELETE-B'), false)
  assert.deepEqual((await state()).cart.map(item => item.id), ['RESTORED-RESTORED-CART-DELETE-A', 'CART-DELETE-B', 'CART-EXTRA'], '未关联商品的模块删除不改购物车')
  assert.equal(deletedCartLineIds.includes('CART-DELETE-B'), false)
  assert.equal((await furniture()).find(item => item.id === 'KEEP-OTHER').layoutConfirmed, true)
  // 共享购物车行（qty=2 被两件家具引用）：删除其中一件只扣减数量，不整行删除
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.objects.push(
      { id: 'SHARED-A', kind: 'furniture', zoneId: 'DELETE-ROOM', furnitureTemplateId: 'desk-straight', productId: 'SHARED-PRODUCT', label: 'SHARED-A', x: -9400, y: -1500, w: 1200, h: 600, rotation: 0, layoutConfirmed: true, productSelection: { product: { id: 'SHARED-PRODUCT' }, cartLineId: 'CART-SHARED', sourceCanvasRevision: 1 } },
      { id: 'SHARED-B', kind: 'furniture', zoneId: 'DELETE-ROOM', furnitureTemplateId: 'desk-straight', productId: 'SHARED-PRODUCT', label: 'SHARED-B', x: -6900, y: -1500, w: 1200, h: 600, rotation: 0, layoutConfirmed: true, productSelection: { product: { id: 'SHARED-PRODUCT' }, cartLineId: 'CART-SHARED', sourceCanvasRevision: 1 } },
    )
    store.cart.push({ id: 'CART-SHARED', projectId: 'OFFICE-PROJECT-001', productId: 'SHARED-PRODUCT', zoneId: 'DELETE-ROOM', zoneName: '删除测试空间', qty: 2 })
  })
  const sharedUpdateResponse = page.waitForResponse(response => response.request().method() === 'PUT' && new URL(response.url()).pathname.endsWith('/cart/CART-SHARED'), { timeout: 90000 })
  await choose('SHARED-A')
  await page.getByRole('button', { name: '删除选中模块', exact: true }).click()
  await sharedUpdateResponse
  assert.equal((await furniture()).some(item => item.id === 'SHARED-A'), false)
  const sharedCart = (await state()).cart.find(item => item.id === 'CART-SHARED')
  assert.equal(sharedCart.qty, 1, '共享行删除一件家具只扣减到 1，不整行删除')
  assert.equal(serverCartQuantities.get('CART-SHARED'), 1)
  assert.equal(deletedCartLineIds.includes('CART-SHARED'), false)
  const sharedQtyResponse = qty => page.waitForResponse(response => response.request().method() === 'PUT'
    && new URL(response.url()).pathname.endsWith('/cart/CART-SHARED')
    && new URL(response.url()).searchParams.get('qty') === String(qty))
  const sharedUndoResponse = sharedQtyResponse(2)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await sharedUndoResponse
  assert.equal((await furniture()).some(item => item.id === 'SHARED-A'), true)
  assert.equal((await state()).cart.find(item => item.id === 'CART-SHARED').qty, 2)
  assert.equal(serverCartQuantities.get('CART-SHARED'), 2, '撤销共享行扣减必须恢复服务端数量')
  const sharedRedoResponse = sharedQtyResponse(1)
  await page.getByRole('button', { name: '恢复', exact: true }).click()
  await sharedRedoResponse
  assert.equal((await furniture()).some(item => item.id === 'SHARED-A'), false)
  assert.equal((await state()).cart.find(item => item.id === 'CART-SHARED').qty, 1)
  assert.equal(serverCartQuantities.get('CART-SHARED'), 1, '重做共享行扣减必须同步服务端数量')
  // 较早请求返回时，不能把连续撤销/重做后的最新本地数量覆盖掉。
  let releaseSharedUpdate
  holdSharedUpdate = new Promise(resolve => { releaseSharedUpdate = resolve })
  const queuedUndoRequest = page.waitForRequest(request => request.method() === 'PUT'
    && new URL(request.url()).pathname.endsWith('/cart/CART-SHARED')
    && new URL(request.url()).searchParams.get('qty') === '2')
  await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').undo())
  await queuedUndoRequest
  await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').redo())
  const queuedRedoResponse = sharedQtyResponse(1)
  holdSharedUpdate = null
  releaseSharedUpdate()
  await queuedRedoResponse
  assert.equal((await state()).cart.find(item => item.id === 'CART-SHARED').qty, 1)
  assert.equal(serverCartQuantities.get('CART-SHARED'), 1, '排队请求按顺序落库并保留最新数量')
  // 跨楼层删除：入队后切到二层，请求仍发往原楼层
  const floorTwoKey = 'rsdp-office-v01:MOCK-USER:OFFICE-FLOOR-002'
  await page.evaluate(key => {
    localStorage.setItem(key, JSON.stringify({ objects: [], cart: [], canvasMode: 'overview', blank: true }))
  }, floorTwoKey)
  const crossFloorDelete = page.waitForResponse(response =>
    response.request().method() === 'DELETE' &&
    new URL(response.url()).pathname === '/api/v1/office/projects/OFFICE-PROJECT-001/cart/CART-SHARED', { timeout: 90000 })
  await choose('SHARED-B')
  await page.getByRole('button', { name: '删除选中模块', exact: true }).click()
  await page.evaluate(() =>
    document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('workspace').activateFloor('OFFICE-FLOOR-002'))
  await crossFloorDelete
  const crossFloorMutation = cartMutations.filter(item => item.lineId === 'CART-SHARED')
  assert.ok(crossFloorMutation.every(item => item.floorId === 'OFFICE-PROJECT-001'), '切楼层后删除请求仍发往原楼层')
  try {
    await page.waitForFunction(() => {
      const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
      return store.workspaceId === 'OFFICE-FLOOR-002' && store.cart.length === 0
    }, null, { timeout: 8000 })
  } catch (e) {
    console.log('DEBUG floor', JSON.stringify(await page.evaluate(() => {
      const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
      return { workspaceId: store.workspaceId, initialized: store.initialized, loading: store.loading, cartLen: store.cart.length, lsKeys: Object.keys(localStorage) }
    })))
    throw e
  }
  // 清空排版：同步清理家具有效关联行，未关联行保留
  await page.evaluate(() =>
    document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('workspace').activateFloor('OFFICE-PROJECT-001'))
  await page.getByTestId('office-design-canvas').waitFor()
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.objects.push(
      { id: 'CLEAR-A', kind: 'furniture', zoneId: 'OTHER-ROOM', furnitureTemplateId: 'desk-straight', productId: 'CLEAR-PRODUCT', label: 'CLEAR-A', x: 11000, y: 500, w: 1200, h: 600, rotation: 0, layoutConfirmed: true, productSelection: { product: { id: 'CLEAR-PRODUCT' }, cartLineId: 'CART-CLEAR', sourceCanvasRevision: 1 } },
    )
    store.cart.push({ id: 'CART-CLEAR', projectId: 'OFFICE-PROJECT-001', productId: 'CLEAR-PRODUCT', zoneId: 'OTHER-ROOM', zoneName: '其他空间', qty: 1 })
  })
  const clearDeleteResponse = page.waitForResponse(response => response.request().method() === 'DELETE' && new URL(response.url()).pathname.endsWith('/cart/CART-CLEAR'), { timeout: 90000 })
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.clearFurniture('OTHER-ROOM')
  })
  await clearDeleteResponse
  const afterClear = await state()
  assert.equal(afterClear.objects.some(item => item.id === 'CLEAR-A'), false)
  assert.equal(afterClear.cart.some(item => item.id === 'CART-CLEAR'), false, '清空排版同步删除关联购物车行')
  assert.deepEqual(afterClear.cart.map(item => item.id), ['RESTORED-RESTORED-CART-DELETE-A', 'CART-DELETE-B', 'CART-EXTRA'], '清空排版只移除关联行，其余保留')
  // 整行恢复生成新 ID 后，多次撤销/重做仍需引用同一条有效的服务端行。
  serverCartQuantities.set('HISTORY-LINE', 1)
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.objects.push({ id: 'HISTORY-A', kind: 'furniture', zoneId: 'OTHER-ROOM', productId: 'P', x: 11000, y: 500, w: 1200, h: 600, productSelection: { cartLineId: 'HISTORY-LINE' } })
    store.cart.push({ id: 'HISTORY-LINE', projectId: store.workspaceId, productId: 'P', zoneId: 'OTHER-ROOM', qty: 1 })
  })
  const historyResponse = method => page.waitForResponse(response => response.request().method() === method
    && (method === 'POST'
      ? new URL(response.url()).pathname.endsWith('/cart') && response.request().postDataJSON()?.lineId?.includes('HISTORY-LINE')
      : new URL(response.url()).pathname.includes('/cart/') && new URL(response.url()).pathname.includes('HISTORY-LINE')))
  const historyDelete = historyResponse('DELETE')
  await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').deleteObject('HISTORY-A'))
  await historyDelete
  for (let round = 0; round < 3; round++) {
    const restoredResponse = historyResponse('POST')
    await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').undo())
    await restoredResponse
    const restoredState = await state()
    const restoredId = restoredState.objects.find(item => item.id === 'HISTORY-A').productSelection.cartLineId
    assert.equal(restoredState.cart.find(item => item.id === restoredId)?.qty, 1)
    assert.deepEqual([...serverCartQuantities].filter(([id]) => id.includes('HISTORY-LINE')), [[restoredId, 1]], '重复撤销不能遗留旧行或丢失新行关联')
    if (round === 2) break
    const removedResponse = historyResponse('DELETE')
    await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').redo())
    await removedResponse
    assert.equal([...serverCartQuantities.keys()].some(id => id.includes('HISTORY-LINE')), false)
  }
  assert.deepEqual(errors, [])
  console.log('删除模块 UI 通过：按 cartLineId 删除对应行并保留额外行；共享行扣减、撤销、重做及延迟请求保持服务端数量一致；切楼层后请求仍发往原楼层；清空排版同步清理关联行；撤销恢复服务端行并更新关联 ID。全部 API 模拟。')
} catch (failure) {
  await debugPage?.screenshot({ path: outDir + '/failure.png' }).catch(() => {})
  throw failure
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
