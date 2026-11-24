import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { createReviewConfirmMock, fulfillReviewConfirm, confirmAllReviews } from './helpers/review-confirm-mock.mjs'

// 单模块与组合预览未应用即可拖动：应用直接提交当前预览，不重新求解。
const cwd = fileURLToPath(new URL('../', import.meta.url))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = `http://127.0.0.1:${port}`
const app = spawn(process.execPath, ['.output/server/index.mjs'], { cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' }, stdio: 'ignore' })
let browser
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先 pnpm build；预览服务未就绪')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1700, height: 1100 } })
  const page = await context.newPage()
  page.on('console', message => { if (message.text().includes('recognition-debug')) console.log('B:', message.text().slice(0, 300)) })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const source = {
    analysisId: 'PREVIEW-DRAG', analysisRevision: 1, status: 'awaiting_confirm', sourceName: '预览拖动测试.dxf',
    inputMode: 'cad', geometrySource: 'cad_geometry', evidenceKind: 'REAL_SERVICE',
    unitResolution: { canonicalUnit: 'mm', confirmed: true },
    previewUrl: 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="800" height="500"><rect width="800" height="500" fill="white"/><rect x="96" y="110" width="128" height="55" fill="silver"/><rect x="504" y="260" width="64" height="40" fill="silver"/></svg>'),
    drawingBounds: { minX: 0, minY: 0, maxX: 16000, maxY: 10000 },
    rooms: [{ roomId: 'DRAG-ROOM', label: '开放办公区', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[0, 0], [16000, 0], [16000, 10000], [0, 10000]] }],
  }
  let floorPlanRequests = 0
  let releaseRecognition
  let recognitionStartedResolve
  const recognitionStarted = new Promise(resolve => { recognitionStartedResolve = resolve })
  const recognitionReleased = new Promise(resolve => { releaseRecognition = resolve })
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  const reviewMock = createReviewConfirmMock()
  await context.route('**/api/v1/**', async route => {
    if (fulfillReviewConfirm(reviewMock, route)) return
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/layout-reference/source')) return route.fulfill({ contentType: 'image/svg+xml', body: decodeURIComponent(source.previewUrl.split(',')[1]) })
    if (path.endsWith('/layout-reference/recognize')) {
      assert.equal(route.request().method(), 'POST')
      recognitionStartedResolve()
      await recognitionReleased
      return route.fulfill({ json: { code: 200, data: [
        { templateId: 'desk-straight', categoryId: 'workstation', group: 'office', x: 0.12, y: 0.22, width: 0.16, depth: 0.11, rotation: 0, confidence: 'high' },
        { templateId: 'chair-office', categoryId: 'workstation', group: 'office', x: 0.165, y: 0.35, width: 0.04, depth: 0.05, rotation: 0, confidence: 'low' },
        { templateId: 'coffee-round', categoryId: 'sofa', group: 'guest', x: 0.63, y: 0.52, width: 0.08, depth: 0.08, rotation: 0, confidence: 'high' },
      ] } })
    }
    if (path === '/api/v1/office/projects' && route.request().method() === 'GET') {
      const project = { projectId: 'OFFICE-PROJECT-001', name: '预览拖动测试项目', customerRequirements: '', requirementsRevision: 1, leader: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'LEADER' }, collaborators: [], referenceImages: [], floors: [{ projectId: 'OFFICE-PROJECT-001', floorId: 'OFFICE-PROJECT-001', name: '默认楼层', sortOrder: 1 }] }
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: [project] }) })
    }
    const data = path.endsWith('/floor-plan/PREVIEW-DRAG') ? (floorPlanRequests++ === 0 ? source : null) : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')))
  await page.goto(`${baseUrl}/canvas?analysisId=PREVIEW-DRAG`, { waitUntil: 'networkidle' })
  await page.locator('.review-rooms').getByRole('button', { name: '选择空间 开放办公区', exact: true }).waitFor()
  // 与 e2e-auto-layout 相同：把识别副本转成已校准设计副本，直接进入排版阶段。
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001'))
    for (const zone of draft.objects.filter(object => object.kind === 'zone')) zone.reviewUnit = 'mm'
    localStorage.setItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001', JSON.stringify(draft))
  })
  await page.goto(`${baseUrl}/canvas?analysisId=PREVIEW-DRAG`, { waitUntil: 'networkidle' })
  await confirmAllReviews(page)
  await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()

  const previewItems = page.locator('[data-preview-id]')
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    window.previewApplyResults = []
    store.$onAction(({ name, args, after }) => {
      if (name !== 'applyLayoutProposal') return
      const [zoneId, proposal] = args
      const zone = store.zones.find(item => item.id === zoneId)
      const existing = store.furnitureForZone(zoneId)
      window.previewApplyDebug = { sourceMatches: proposal.sourceKey === JSON.stringify([zone, existing]), problem: proposal.problem, count: proposal.furniture.length, unique: new Set(proposal.furniture.map(item => item.id)).size === proposal.furniture.length }
      after(result => window.previewApplyResults.push(result))
    })
  })
  // 关闭吸附，保证拖动位移可精确断言。
  await page.getByRole('button', { name: '家具吸附', exact: true }).click()
  const zoom = await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').zoom)
  async function dragFirstPreview(dx, dy) {
    const item = previewItems.first()
    const before = await item.evaluate(el => ({ left: parseFloat(el.style.left), top: parseFloat(el.style.top) }))
    const box = await item.boundingBox()
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width / 2 + dx * zoom, box.y + box.height / 2 + dy * zoom, { steps: 6 })
    await page.mouse.up()
    const after = await item.evaluate(el => ({ left: parseFloat(el.style.left), top: parseFloat(el.style.top) }))
    assert.ok(Math.abs(after.left - before.left - dx) <= 2, `预览横向拖动 ${dx}mm，实际 ${after.left - before.left}`)
    assert.ok(Math.abs(after.top - before.top - dy) <= 2, `预览纵向拖动 ${dy}mm，实际 ${after.top - before.top}`)
    return { after, count: await previewItems.count() }
  }
  async function assertAppliedAtPreview(after, count) {
    const placed = (await state()).objects.filter(object => object.kind === 'furniture')
    assert.equal(placed.length, count, '应用后家具件数与预览一致')
    assert.ok(placed.some(object => Math.abs(object.x - after.left) <= 2 && Math.abs(object.y - after.top) <= 2), `应用位置与拖动后的预览位置一致（期望 x≈${after.left} y≈${after.top}）`)
    assert.equal(await previewItems.count(), 0, '应用后预览清空')
  }

  // 单模块：空空间拖动预览，应用必须采用当前预览快照。
  await page.getByTestId('functional-category-workstation').click()
  await page.getByRole('spinbutton', { name: '目标人数', exact: true }).fill('4')
  await page.waitForFunction(() => document.querySelector('[data-testid="layout-seat-summary"]')?.textContent?.includes('工位 4 · 目标 4'))
  assert.equal(await previewItems.count(), 4, '目标 4 人生成 4 个单模块预览')
  const single = await dragFirstPreview(0, 30)
  await page.getByTestId('preview-manually-adjusted').waitFor()
  assert.equal((await state()).objects.filter(object => object.kind === 'furniture').length, 0, '单模块拖动预览不写入已排版家具')
  await page.getByTestId('apply-layout').click()
  await page.waitForFunction(() => window.previewApplyResults.length === 1)
  assert.equal(await page.evaluate(() => window.previewApplyResults[0]), true, JSON.stringify(await page.evaluate(() => window.previewApplyDebug)))
  await page.waitForFunction(() => (JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects || []).filter(object => object.kind === 'furniture').length === 4)
  await assertAppliedAtPreview(single.after, single.count)

  // 已有 4 件再生成 5 件预览：覆盖用户反馈的“旧排版 + 手动预览 + 应用”场景。
  await page.getByRole('spinbutton', { name: '目标人数', exact: true }).fill('5')
  await page.getByRole('checkbox', { name: '显示排版预览' }).check()
  await page.waitForFunction(() => document.querySelectorAll('[data-preview-id]').length === 5)
  const replanned = await dragFirstPreview(0, -25)
  await page.getByTestId('auto-confirm-recommend').click()
  await page.getByText('当前预览尚未应用，请先应用当前预览，再确认排版', { exact: true }).waitFor()
  await page.getByTestId('apply-layout').click()
  await page.waitForFunction(() => (JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects || []).filter(object => object.kind === 'furniture').length === 5)
  await assertAppliedAtPreview(replanned.after, replanned.count)
  const beforeReload = (await state()).objects.filter(object => object.kind === 'furniture').map(item => [item.x, item.y, item.w, item.h, item.rotation]).sort()
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('design-workflow-sidebar').waitFor()
  const afterReload = (await state()).objects.filter(object => object.kind === 'furniture').map(item => [item.x, item.y, item.w, item.h, item.rotation]).sort()
  assert.deepEqual(afterReload, beforeReload, '刷新后保留手动调整后的排版几何')
  await page.getByTestId('auto-clear-layout').click()
  await page.waitForFunction(() => !(JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects || []).some(object => object.kind === 'furniture'))

  // 组合布局沿用同一提交语义。
  await page.getByTestId('functional-category-workstation').click()
  await page.getByTestId('functional-category-sofa').click()
  await page.getByTestId('mixed-layout-generate').click()
  await previewItems.first().waitFor()
  // 刷新会恢复默认吸附；组合拖动仍使用无吸附的精确位移。
  await page.getByRole('button', { name: '家具吸附', exact: true }).click()
  await dragFirstPreview(60, 30)
  await page.getByTestId('mixed-preview-manually-adjusted').waitFor()
  assert.equal(await page.getByTestId('mixed-layout-apply').isDisabled(), true, '越界的组合预览不能提交')
  const cards = page.getByTestId('mixed-layout-candidate')
  await cards.nth(1).click()
  await cards.first().click()
  const mixed = await dragFirstPreview(60, -30)

  // 未应用前不写入已排版家具。
  assert.equal((await state()).objects.filter(object => object.kind === 'furniture').length, 0, '拖动预览不写入已排版家具')

  // 应用：已排版家具按拖动后的预览位置生成。
  await page.getByTestId('mixed-layout-apply').click()
  await page.waitForFunction(() => (JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects || []).some(object => object.kind === 'furniture'))
  await assertAppliedAtPreview(mixed.after, mixed.count)

  await page.getByTestId('mixed-layout-reference').click()
  await page.getByTestId('layout-reference-panel').waitFor()
  assert.equal(await page.getByRole('dialog', { name: '参考原图摆位' }).count(), 0)
  const referenceLayer = page.getByTestId('reference-canvas-layer')
  await referenceLayer.waitFor()
  await page.getByRole('combobox', { name: '标注功能' }).selectOption('entry')
  await page.getByTestId('reference-add').click()
  const canvasBox = await page.getByTestId('office-design-canvas').boundingBox()
  await page.mouse.click(canvasBox.x + canvasBox.width * 0.25, canvasBox.y + canvasBox.height * 0.25)
  const pendingManual = referenceLayer.locator('[data-reference-confidence="manual"]')
  await pendingManual.waitFor()
  await page.getByTestId('reference-recognize').click()
  await recognitionStarted
  // 识别进行中跳转到站内其他页面，任务不中断；回到画布时面板自动恢复并显示识别中状态。
  await page.getByRole('link', { name: '商品库', exact: true }).click()
  await page.waitForURL('**/products')
  await page.waitForTimeout(300)
  console.log('TASKS-AT-PRODUCTS', await page.evaluate(() => {
    const store = document.querySelector('#__nuxt')?.__vue_app__?.config.globalProperties.$pinia._s.get('office')
    return JSON.stringify(store?.referenceRecognitions)
  }))
  await page.goto(`${baseUrl}/canvas?analysisId=PREVIEW-DRAG&mode=design&zoneId=DRAG-ROOM`, { waitUntil: 'networkidle' })
  console.log('TASKS-AFTER-GOTO', await page.evaluate(() => {
    const store = document.querySelector('#__nuxt')?.__vue_app__?.config.globalProperties.$pinia._s.get('office')
    return JSON.stringify(store?.referenceRecognitions)
  }))
  await page.getByTestId('layout-reference-panel').waitFor()
  await page.getByText('正在识别裁图中的家具…', { exact: true }).waitFor()
  releaseRecognition()
  await referenceLayer.locator('[data-reference-confidence="high"]').first().waitFor()
  const restored = referenceLayer.locator('[data-reference-index]')
  assert.equal(await restored.count(), 4, '手动标记保留，识别结果跨页面返回后合并')
  assert.equal(await restored.locator('[data-reference-confidence="manual"]').count(), 1, '已有手动标记未被识别结果覆盖')
  // 手动关闭面板后再次识别仍在进行中时，回到画布自动重开面板。
  await page.getByRole('button', { name: '返回组合布局', exact: true }).click()
  await page.getByTestId('layout-reference-panel').waitFor({ state: 'detached' })
  await page.getByTestId('mixed-layout-reference').click()
  await page.getByTestId('layout-reference-panel').waitFor()
  await pendingManual.click()
  await page.keyboard.press('Delete')
  await pendingManual.waitFor({ state: 'detached' })
  const suggestedDesk = referenceLayer.locator('[data-reference-index="0"]')
  await suggestedDesk.locator('svg[data-furniture-template="desk-straight"]').waitFor()
  const deskBox = await suggestedDesk.boundingBox()
  const beforeDeskX = await suggestedDesk.evaluate(el => parseFloat(el.style.left))
  const referenceZoom = await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').zoom)
  await page.mouse.move(deskBox.x + deskBox.width / 2, deskBox.y + deskBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(deskBox.x + deskBox.width / 2 + 100 * referenceZoom, deskBox.y + deskBox.height / 2, { steps: 5 })
  await page.mouse.up()
  assert.ok(Math.abs(await suggestedDesk.evaluate(el => parseFloat(el.style.left)) - beforeDeskX - 100) <= 2, '参考图例使用画布的毫米坐标拖动')
  assert.equal(await suggestedDesk.getAttribute('data-reference-confidence'), 'manual')
  assert.equal((await state()).objects.filter(item => item.layoutReference).length, 0, '参考草稿独立于已应用排版')
  await page.getByRole('button', { name: '家具吸附', exact: true }).click()
  const movingDesk = await suggestedDesk.boundingBox()
  const movingDeskX = await suggestedDesk.evaluate(el => parseFloat(el.style.left))
  await page.mouse.move(movingDesk.x + movingDesk.width / 2, movingDesk.y + movingDesk.height / 2)
  await page.mouse.down()
  await page.mouse.move(movingDesk.x + movingDesk.width / 2 + (30 - movingDeskX) * referenceZoom, movingDesk.y + movingDesk.height / 2, { steps: 5 })
  assert.equal(await page.getByTestId('snap-guide-x').evaluate(el => parseFloat(el.style.left)), 0, '参考拖动吸附左墙并显示引导线')
  await page.mouse.up()
  assert.ok(Math.abs(await suggestedDesk.evaluate(el => parseFloat(el.style.left))) < 0.01, '落点与吸附预览一致')
  await page.keyboard.press('Space')
  assert.equal(await suggestedDesk.evaluate(el => el.style.transform), 'rotate(90deg)')
  await page.getByTestId('rotate-selection').click()
  assert.equal(await suggestedDesk.evaluate(el => el.style.transform), 'rotate(180deg)', '参考家具复用画布旋转按钮')
  for (let i = 0; i < 2; i++) await page.keyboard.press('Space')
  assert.equal(await suggestedDesk.evaluate(el => el.style.transform), '')
  await page.getByRole('button', { name: '应用当前方案', exact: true }).click()
  await page.getByText('还有 1 件家具待确认，请确认或删除后再应用', { exact: true }).waitFor()
  assert.equal(await referenceLayer.locator('[data-reference-index]').count(), 3, '校验失败保留全部编辑家具')
  await referenceLayer.locator('[data-reference-confidence="low"]').click()
  await page.getByRole('button', { name: '确认', exact: true }).click()
  await page.getByRole('combobox', { name: '标注功能' }).selectOption('entry')
  await page.getByLabel('纳入原图中其他功能的家具').check()
  const coffeeBox = await referenceLayer.locator('[data-reference-index="2"]').boundingBox()
  await page.getByTestId('reference-add').click()
  await page.mouse.click(coffeeBox.x + coffeeBox.width / 2, coffeeBox.y + coffeeBox.height / 2)
  await page.getByTestId('reference-apply').click()
  await page.getByText('此位置与已有家具重叠或间距不足', { exact: true }).waitFor()
  assert.equal(await referenceLayer.locator('[data-reference-index]').count(), 4, '重叠校验不会挪动或删掉家具')
  assert.equal((await state()).objects.filter(item => item.layoutReference).length, 0)
  await page.keyboard.press('Delete')
  await page.getByTestId('reference-add').click()
  await page.keyboard.press('Space')
  const cabinetClick = { x: coffeeBox.x + coffeeBox.width / 2 + 5080 * referenceZoom, y: coffeeBox.y + coffeeBox.height / 2 + 1600 * referenceZoom }
  await page.mouse.move(cabinetClick.x, cabinetClick.y)
  assert.equal(await page.getByTestId('reference-placement-preview').evaluate(el => el.style.transform), 'rotate(90deg)')
  assert.equal(await page.getByTestId('snap-guide-x').evaluate(el => parseFloat(el.style.left)), 16000, '参考点放吸附右墙')
  await page.mouse.click(cabinetClick.x, cabinetClick.y)
  const addedCabinet = referenceLayer.getByRole('button', { name: '玄关柜' })
  await addedCabinet.waitFor()
  await page.keyboard.press('Delete')
  await addedCabinet.waitFor({ state: 'detached' })
  await page.getByTestId('reference-add').click()
  await page.keyboard.press('Space')
  await page.mouse.click(cabinetClick.x, cabinetClick.y)
  await addedCabinet.waitFor()
  await page.getByRole('button', { name: /玄关柜.*人工确认/ }).waitFor()
  const readGeometry = el => [parseFloat(el.style.left), parseFloat(el.style.top), parseFloat(el.style.width), parseFloat(el.style.height), Number(el.style.transform.match(/rotate\((\d+)deg\)/)?.[1] || 0)]
  const deskEdited = await suggestedDesk.evaluate(readGeometry)
  const cabinetEdited = await addedCabinet.evaluate(readGeometry)
  await page.getByTestId('reference-apply').click()
  await page.getByText('已应用当前方案，共 4 件参考家具', { exact: true }).waitFor()
  assert.equal(await page.getByTestId('mixed-layout-candidates').count(), 0, '原图编辑直接应用，不跳到组合候选')
  assert.equal(await previewItems.count(), 0)
  await page.waitForFunction(() => (JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects || [])
    .some(object => object.layoutReference?.evidence === 'MANUAL'))
  const referenceFurniture = (await state()).objects.filter(object => object.kind === 'furniture')
  assert.ok(referenceFurniture.some(item => item.layoutReference?.evidence === 'ESTIMATED'))
  assert.equal(referenceFurniture.filter(item => item.layoutReference).length, 4)
  for (const [templateId, expected] of [['desk-straight', deskEdited], ['cabinet-entry', cabinetEdited]]) {
    const applied = referenceFurniture.find(item => item.furnitureTemplateId === templateId)
    assert.ok([applied.x, applied.y, applied.w, applied.h, applied.rotation].every((value, i) => Math.abs(value - expected[i]) < 0.01), '位置、规格和朝向原样写入')
  }

  // A 90° cabinet starts flush against the left wall. Shrinking along its local width must not move the footprint outside.
  const cabinetId = referenceFurniture.find(item => item.furnitureTemplateId === 'cabinet-entry').id
  await page.evaluate(id => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const cabinet = store.objects.find(item => item.id === id)
    const room = store.zones.find(item => item.id === cabinet.zoneId)
    const minX = Math.min(...room.polygon.map(([x]) => x))
    const maxY = Math.max(...room.polygon.map(([, y]) => y))
    store.moveFurnitureGroup([id], room.id, minX + (cabinet.h - cabinet.w) / 2 - cabinet.x, maxY - 3000 + (cabinet.w - cabinet.h) / 2 - cabinet.y)
  }, cabinetId)
  await page.getByTestId('auto-confirm-recommend').click()
  await page.getByTestId('tab-style-recommendation').waitFor()
  assert.equal(await page.getByTestId('tab-style-recommendation').getAttribute('aria-selected'), 'true')
  await page.locator('[data-id="' + cabinetId + '"]').click()
  assert.equal(await page.getByTestId('tab-style-recommendation').getAttribute('aria-selected'), 'true')
  assert.equal(await page.getByLabel('匹配单位模块').inputValue(), cabinetId)
  await page.getByTestId('tab-product-selection').click()
  assert.equal(await page.getByTestId('tab-product-selection').getAttribute('aria-selected'), 'true')
  await page.getByTestId('product-item-editor').getByText('玄关柜', { exact: true }).waitFor()
  assert.equal(await page.getByTestId('product-item-width').inputValue(), '1200')
  await page.getByTestId('product-item-width').fill('30000')
  await page.getByTestId('product-item-resize').click()
  await page.getByText('此位置超出空间边界', { exact: true }).waitFor()
  assert.equal((await state()).objects.find(item => item.id === cabinetId).w, 1200)
  assert.equal((await state()).objects.find(item => item.id === cabinetId).layoutConfirmed, true)
  await page.getByTestId('product-item-width').fill('1000')
  await page.getByTestId('product-item-resize').click()
  const resizedCabinet = (await state()).objects.find(item => item.id === cabinetId)
  const cabinetRoom = (await state()).objects.find(item => item.id === resizedCabinet.zoneId)
  assert.equal(resizedCabinet.w, 1000)
  assert.equal(resizedCabinet.h, 350)
  assert.equal(resizedCabinet.rotation, 90)
  assert.ok(Math.abs(resizedCabinet.x + (resizedCabinet.w - resizedCabinet.h) / 2 - Math.min(...cabinetRoom.polygon.map(([x]) => x))) < 0.01)
  assert.ok((await state()).objects.filter(item => item.kind === 'furniture' && item.zoneId === cabinetRoom.id).every(item => !item.layoutConfirmed))
  assert.equal(await page.getByTestId('tab-product-selection').getAttribute('aria-selected'), 'true')

  assert.deepEqual(errors, [], '页面无脚本错误')
  console.log('预览拖动 E2E 通过：单模块和组合快照应用、参考家具吸附、旋转和删除快捷键、点放补录及来源写入。')
} finally {
  if (browser) await browser.close()
  app.kill()
}
