import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { once } from 'node:events'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const cwd = fileURLToPath(new URL('../', import.meta.url))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = `http://127.0.0.1:${port}`
const app = spawn(process.execPath, ['.output/server/index.mjs'], {
  cwd,
  env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' },
  stdio: 'ignore',
})

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
  const source = {
    analysisId: 'MEETING-TEST', analysisRevision: 1, status: 'awaiting_confirm', sourceName: '会议室测试.dxf',
    inputMode: 'cad', geometrySource: 'cad_geometry', evidenceKind: 'REAL_SERVICE',
    unitResolution: { canonicalUnit: 'mm', confirmed: true },
    rooms: [{ roomId: 'MEETING-ROOM', label: '测试会议室', roomType: 'MEETING_ROOM', geometrySource: 'cad_geometry', polygon: [[0, 0], [12000, 0], [12000, 8000], [0, 8000]] }, { roomId: 'OPEN-ROOM', label: '测试办公区', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[20000, 0], [32000, 0], [32000, 8000], [20000, 8000]] }, { roomId: 'NARROW-ROOM', label: '窄工作间', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[40000, 0], [47524, 0], [47524, 2805], [40000, 2805]] }],
  }
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    const data = path.endsWith('/floor-plan/MEETING-TEST') ? source : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  const catalog = page.getByTestId('unit-module-catalog')
  await page.goto(`${baseUrl}/canvas?analysisId=MEETING-TEST`, { waitUntil: 'networkidle' })
  await page.locator('.review-rooms').getByRole('button', { name: '选择空间 测试会议室', exact: true }).waitFor()
  await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.getByTestId('tab-product-selection').click()
  await catalog.waitFor()
  assert.equal(await catalog.locator('[data-module-id="table-conference-rect"]').getAttribute('aria-pressed'), 'false', '进入排版不再默认选中模块')
  const snapshot = await state()
  await catalog.locator('[data-auto-module-id="table-conference-rect"]').click() // preview appears after an explicit module choice
  assert.ok(await page.locator('[data-preview-id]').count() > 1)
  assert.deepEqual((await state()).objects, snapshot.objects, 'preview never writes objects')
  await page.getByRole('spinbutton', { name: '目标人数', exact: true }).fill('8')
  await page.getByTestId('layout-seat-summary').getByText('已排座位 8', { exact: false }).waitFor()
  const preview = await page.locator('[data-preview-id]').evaluateAll(nodes => nodes.map(n => ({ x: n.style.left, y: n.style.top, w: n.style.width, h: n.style.height, transform: n.style.transform })))
  // LAYOUT-034：候选方案卡片——出现在画布右下角浮层、展示指标、切换预览且不落库、切回默认还原。
  await page.getByTestId('layout-candidates').waitFor()
  assert.equal(await page.locator('[data-testid="layout-candidate-dock"] [data-testid="layout-candidates"]').count(), 1, '候选卡位于画布右下角浮层')
  const candidateCards = page.getByTestId('layout-candidate-card')
  const candidateCount = await candidateCards.count()
  assert.ok(candidateCount >= 2 && candidateCount <= 3, '会议排版给出 2~3 个候选方案（标准=当前间距 + 紧凑/舒适，同几何去重）')
  assert.match(await candidateCards.nth(0).innerText(), /利用率 \d+%/)
  // 三档间距候选 v2：每张卡标注档位，标准档固定首位默认，无自定义卡
  assert.equal(await candidateCards.nth(0).getAttribute('aria-pressed'), 'true', '默认选中标准方案')
  const cardTexts = await candidateCards.allInnerTexts()
  for (const text of cardTexts) assert.doesNotMatch(text, /评分|人均余量|目标达成|扣分/, '单模块卡只显示基础信息')
  for (const text of cardTexts) assert.match(text, /^(标准|紧凑|舒适)\n/, '候选卡标注间距档位且无自定义')
  assert.ok(cardTexts[0].startsWith('标准\n'), '标准档固定首位作为默认方案')
  assert.ok(['紧凑', '舒适'].filter(tier => cardTexts.some(text => text.startsWith(tier + '\n'))).length >= 1, '紧凑/舒适至少一档产出对比候选')
  await candidateCards.nth(1).click()
  const previewSnapshot = () => page.locator('[data-preview-id]').evaluateAll(nodes => nodes.map(n => ({ x: n.style.left, y: n.style.top, w: n.style.width, h: n.style.height, transform: n.style.transform })))
  const altPreview = await previewSnapshot()
  assert.notDeepEqual(altPreview, preview, '切换候选后预览随之更新')
  assert.deepEqual((await state()).objects, snapshot.objects, '切换候选仍为纯预览，不落库')
  await candidateCards.nth(0).click()
  assert.deepEqual(await previewSnapshot(), preview, '切回默认候选恢复原预览')
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.$onAction(({ name, args }) => { if (name === 'applyLayoutProposal') window.appliedArguments = JSON.parse(JSON.stringify(args)) })
  })
  await page.getByTestId('apply-layout').click()
  const placed = (await state()).objects.filter(item => item.kind === 'furniture')
  assert.equal(placed.length, 9)
  assert.equal(placed.filter(item => item.layoutRole === 'seat').length, 8)
  assert.equal(await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const args = window.appliedArguments
    return store.applyLayoutProposal(...args)
  }), false, 'stale proposal cannot overwrite current saved placements')
  const actual = await page.locator('[data-testid="office-design-canvas"] .board-element[data-id]').filter({ has: page.locator('[data-furniture-template]') }).evaluateAll(nodes => nodes.map(n => ({ x: n.style.left, y: n.style.top, w: n.style.width, h: n.style.height, transform: n.style.transform })))
  assert.deepEqual(actual, preview, 'preview and applied placement use identical geometry')
  await page.getByTestId('layout-unchanged').waitFor()
  assert.ok(await page.getByTestId('apply-layout').isDisabled())
  await page.getByRole('button', { name: '确认当前排版', exact: false }).click()
  assert.ok((await state()).objects.filter(item => item.kind === 'furniture').every(item => item.layoutConfirmed))
  await page.getByRole('spinbutton', { name: '目标人数', exact: true }).fill('60')
  // LAYOUT-035：座位不足迁入统一预警块（红级+修改建议）。人均面积阈值触发由单测覆盖（需要密集小空间场景）。
  const shortfallWarning = page.locator('[data-warning-id="shortfall"]')
  await shortfallWarning.waitFor()
  assert.equal(await shortfallWarning.getAttribute('data-level'), 'error')
  assert.match(await shortfallWarning.innerText(), /座位不足/)
  assert.match(await shortfallWarning.innerText(), /建议：/)
  assert.ok((await state()).objects.filter(item => item.kind === 'furniture').every(item => item.layoutConfirmed), 'editing draft does not unconfirm saved layout')
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('functional-category-meeting').click()
  assert.equal(await page.getByRole('spinbutton', { name: '目标人数', exact: true }).inputValue(), '8')
  // LAYOUT-036：求解目标三选（缺省目标人数；切换不丢已填数值）
  await page.getByTestId('objective-fixed-quantity').click()
  await page.getByRole('spinbutton', { name: '件数目标', exact: true }).waitFor()
  await page.getByTestId('objective-max-capacity').click()
  assert.equal(await page.getByRole('spinbutton', { name: '目标人数', exact: true }).count(), 0, '最大容量无目标人数输入')
  await page.getByTestId('objective-target-seats').click()
  assert.equal(await page.getByRole('spinbutton', { name: '目标人数', exact: true }).inputValue(), '8', '切回目标人数后已填数值保留')
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    const zone = draft.objects.find(item => item.id === 'MEETING-ROOM')
    draft.objects.push({ id: 'KEEP-CABINET', kind: 'furniture', zoneId: zone.id, productId: '', furnitureTemplateId: 'cabinet-mobile', x: zone.x + 300, y: zone.y + 300, w: 420, h: 500, rotation: 0, layoutConfirmed: false })
    draft.objects.find(item => item.layoutRole === 'primary').productId = 'SELECTED-OLD'
    draft.cart = [{ id: 'CART-OLD', productId: 'SELECTED-OLD', zoneId: zone.id, zoneName: zone.name, qty: 1 }, { id: 'CART-EXTRA', productId: 'EXTRA-MANUAL', zoneId: zone.id, zoneName: zone.name, qty: 2 }]
    localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
  })
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('functional-category-meeting').click()
  const beforeReplan = (await state()).objects
  await page.getByRole('spinbutton', { name: '目标人数', exact: true }).fill('6')
  await page.getByTestId('apply-layout').click()
  assert.deepEqual((await state()).objects.find(item => item.id === 'KEEP-CABINET'), beforeReplan.find(item => item.id === 'KEEP-CABINET'))
  assert.equal((await state()).objects.filter(item => item.layoutGroupId).length, 7)
  assert.deepEqual((await state()).cart.map(item => item.id), ['CART-EXTRA'], 'remove group selections while retaining unrelated manual selections')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual((await state()).objects, beforeReplan, 'one undo restores group, preferences and confirmation')
  assert.equal((await state()).cart[0].id, 'CART-OLD', 'undo restores associated selections')
  // LAYOUT-034：应用选中的非默认候选——携带候选索引、预览与应用几何一致，撤销后恢复。
  await page.getByTestId('layout-candidates').waitFor()
  await candidateCards.nth(1).click()
  await page.getByTestId('apply-layout').waitFor({ state: 'visible' })
  await page.waitForFunction(() => !document.querySelector('[data-testid="apply-layout"]')?.disabled)
  const candidatePreview = await previewSnapshot()
  // 前面的刷新已重置页面上下文，这里重新挂接动作捕获。
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.$onAction(({ name, args }) => { if (name === 'applyLayoutProposal') window.candidateApplyArgs = JSON.parse(JSON.stringify(args)) })
  })
  await page.getByTestId('apply-layout').click()
  assert.equal(await page.evaluate(() => window.candidateApplyArgs?.[3]), 1, '应用携带候选索引')
  await page.waitForFunction(n => [...document.querySelectorAll('[data-testid="office-design-canvas"] .board-element[data-id]')].filter(el => el.getAttribute('data-id') !== 'KEEP-CABINET' && el.querySelector('[data-furniture-template]')).length === n, candidatePreview.length)
  const placedCandidate = await page.locator('[data-testid="office-design-canvas"] .board-element[data-id]:not([data-id="KEEP-CABINET"])').filter({ has: page.locator('[data-furniture-template]') }).evaluateAll(nodes => nodes.map(n => ({ x: n.style.left, y: n.style.top, w: n.style.width, h: n.style.height, transform: n.style.transform })))
  assert.deepEqual(placedCandidate, candidatePreview, '选中候选的预览与应用几何一致')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual((await state()).objects, beforeReplan, 'undo after candidate apply restores prior layout')
  await page.getByTestId('tab-product-selection').click()
  await catalog.locator('summary').filter({ hasText: '职员桌' }).click()
  await catalog.locator('[data-auto-module-id="desk-l"]').click()
  assert.ok(await page.getByTestId('apply-layout').isEnabled(), 'meeting room allows staff desks')
  assert.ok(await page.getByTestId('auto-manual-layout').isEnabled(), 'automatic layout can enter the manual editor')
  await page.getByTestId('tab-product-selection').click()
  await catalog.locator('[data-auto-module-id="table-conference-rect"]').click()
  // GEOM-013：面板只读；空间核对标注的禁放区（建筑约束）常显并阻止应用，移除后恢复。
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    const room = draft.objects.find(item => item.id === 'MEETING-ROOM')
    const xs = room.polygon.map(point => point[0]), ys = room.polygon.map(point => point[1])
    const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys)
    // obstacle 几何存储 y 取反（毫米空间），覆盖整个房间画布范围。
    room.obstacles = [{ obstacleId: 'OBS-ALL', kind: 'NO_PLACEMENT', spaceId: 'MEETING-ROOM', label: '整间禁放', excludesFloorArea: false, source: 'MANUAL', geometry: { type: 'POLYGON', coordinateSystem: 'WORLD_MM', outer: [[minX, -minY], [maxX, -minY], [maxX, -maxY], [minX, -maxY]], outerVertexIds: [], holes: [], holeVertexIds: [] } }]
    localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
  })
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('functional-category-meeting').click()
  assert.equal(await page.getByTestId('constraints-panel').count(), 0, '排版参数区不再重复设置禁放区')
  assert.ok(await page.getByTestId('toolbar-constraints').isVisible())
  assert.ok(await page.getByTestId('apply-layout').isDisabled())
  assert.equal(await page.getByRole('button', { name: '添加入口或禁放区', exact: true }).count(), 0)
  assert.equal(await page.getByTestId('design-obstacles').locator(':scope > div').count(), 1)
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    draft.objects.find(item => item.id === 'MEETING-ROOM').obstacles = []
    localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
  })
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('functional-category-meeting').click()
  await page.getByRole('button', { name: '自动推荐', exact: true }).click()
  await page.screenshot({ path: (process.env.OUT_DIR || 'C:/Users/cmh/AppData/Local/Temp') + '/rsdp-layout-planning.png' })
  const savedMeeting = (await state()).objects.filter(item => item.kind === 'furniture' && item.zoneId === 'MEETING-ROOM')
  await page.locator('[data-space-id="OPEN-ROOM"]').click()
  await page.waitForURL(url => url.searchParams.get('zoneId') === 'OPEN-ROOM')
  await page.getByTestId('functional-category-workstation').click()
  assert.equal(await page.getByRole('spinbutton', { name: '目标人数', exact: true }).inputValue(), '0')
  await page.getByRole('tab', { name: '选择排布', exact: true }).click()
  await page.getByRole('button', { name: '一字对坐', exact: true }).click()
  // 非阻塞的椅背净距策略提示不再占用操作区；目标不足等红色错误仍保留。
  await page.getByRole('tab', { name: '调整参数', exact: true }).click()
  await page.getByTestId('advanced-spacing').evaluate(node => { node.open = true })
  // 间距预设档：一键写入全部间距字段（策略初始值），之后仍可逐项微调
  await page.getByTestId('spacing-preset-comfort').click()
  assert.equal(await page.getByRole('spinbutton', { name: '离墙距离（毫米）', exact: true }).inputValue(), '600', '舒适档离墙 600')
  assert.equal(await page.getByRole('spinbutton', { name: '图例组间净距（毫米）', exact: true }).inputValue(), '1200', '舒适档组间净距 1200')
  await page.getByTestId('spacing-preset-compact').click()
  assert.equal(await page.getByRole('spinbutton', { name: '离墙距离（毫米）', exact: true }).inputValue(), '0', '紧凑档离墙 0')
  assert.equal(await page.getByRole('spinbutton', { name: '图例组间净距（毫米）', exact: true }).inputValue(), '800', '紧凑档组间净距 800')
  await page.getByRole('spinbutton', { name: '图例组间净距（毫米）', exact: true }).fill('800')
  const chairBackWarning = page.locator('[data-warning-id="chair-back-gap"]')
  assert.equal(await chairBackWarning.count(), 0, '非阻塞策略提示不再占用操作区')
  await page.getByRole('spinbutton', { name: '图例组间净距（毫米）', exact: true }).fill('1600')
  await page.waitForFunction(() => !document.querySelector('[data-warning-id="chair-back-gap"]'))
  await page.getByRole('tab', { name: '选择排布', exact: true }).click()
  await page.getByRole('spinbutton', { name: '目标人数', exact: true }).fill('12')
  await page.getByTestId('apply-layout').click()
  assert.equal((await state()).objects.filter(item => item.layoutGroupId === 'auto:OPEN-ROOM').length, 12)
  assert.deepEqual((await state()).objects.filter(item => item.kind === 'furniture' && item.zoneId === 'MEETING-ROOM'), savedMeeting)
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('functional-category-workstation').click()
  assert.equal(await page.getByRole('spinbutton', { name: '目标人数', exact: true }).inputValue(), '12')
  await page.getByTestId('layout-unchanged').waitFor()
  for (const width of [1440, 1100]) {
    await page.setViewportSize({ width, height: 900 })
    const box = await page.getByTestId('apply-layout').boundingBox()
    assert.ok(box.y >= 0 && box.y + box.height <= 900, 'main action visible in 900px viewport')
  }
  const beforeMixed = (await state()).objects
  await page.getByTestId('functional-category-sofa').click()
  await page.getByTestId('mixed-auto-layout-panel').waitFor()
  // 间距预设档（组合面板）：一键写入全部间距字段（策略初始值），之后恢复默认继续既有流程
  const mixedPanel = page.getByTestId('mixed-auto-layout-panel')
  await page.getByTestId('mixed-spacing-preset-comfort').click()
  assert.equal(await mixedPanel.locator('label:has-text("离墙距离") input').inputValue(), '600', '组合面板舒适档离墙 600')
  assert.equal(await mixedPanel.locator('label:has-text("组合净距") input').inputValue(), '1200', '组合面板舒适档组合净距 1200')
  await mixedPanel.locator('label:has-text("离墙距离") input').fill('0')
  await mixedPanel.locator('label:has-text("组合净距") input').fill('900')
  await mixedPanel.locator('label:has-text("模块间距") input').fill('0')
  await page.getByTestId('mixed-target-seats').fill('0')
  assert.equal(await page.getByTestId('mixed-layout-count').innerText(), '4 件', '办公桌椅与沙发茶几各一套，不按空间尽量排满')
  assert.equal(await page.locator('[data-preview-id]').count(), 0, '选择功能模块不会立即出图')
  await page.getByTestId('mixed-layout-generate').click()
  await page.getByTestId('mixed-layout-candidates').waitFor()
  assert.ok(await page.locator('[data-preview-id]').count() > 0, '点击生成后才显示组合预览')
  const mixedCandidateCards = page.getByTestId('mixed-layout-candidate')
  assert.ok(await mixedCandidateCards.count() >= 2, '提供多个整体组合候选')
  assert.doesNotMatch(await mixedCandidateCards.nth(0).innerText(), /评分|人均余量|目标达成|扣分/, '组合卡只显示基础信息')
  const mixedCardTexts = await mixedCandidateCards.allInnerTexts()
  for (const text of mixedCardTexts) assert.match(text, /^(标准|紧凑|舒适)\n/, '组合候选卡标注间距档位且无自定义')
  assert.ok(mixedCardTexts[0].startsWith('标准\n'), '组合候选标准档固定首位默认')
  assert.equal(await page.locator('[data-testid="layout-candidate-dock"] [data-testid="mixed-layout-candidates"]').count(), 1, '组合候选卡位于画布右下角浮层')
  assert.equal(await mixedCandidateCards.nth(0).getAttribute('aria-pressed'), 'true', '组合默认选中标准方案')
  const firstMixedPreview = await page.locator('[data-preview-id]').evaluateAll(nodes => nodes.map(node => [node.style.left, node.style.top, node.style.width, node.style.height]))
  await mixedCandidateCards.nth(1).click()
  const selectedMixedPreview = await page.locator('[data-preview-id]').evaluateAll(nodes => nodes.map(node => [node.style.left, node.style.top, node.style.width, node.style.height]))
  assert.notDeepEqual(selectedMixedPreview, firstMixedPreview, '切换候选改变整体排布预览')
  assert.deepEqual((await state()).objects, beforeMixed, '候选预览与切换不写入正式家具')
  await page.screenshot({ path: (process.env.OUT_DIR || 'C:/Users/cmh/AppData/Local/Temp') + '/rsdp-mixed-auto-layout.png', animations: 'disabled' })
  await page.getByTestId('mixed-layout-apply').click()
  const mixedAuto = (await state()).objects.filter(item => item.layoutGroupId === 'auto:OPEN-ROOM')
  assert.equal(mixedAuto.length, 4)
  assert.ok(mixedAuto.some(item => item.furnitureTemplateId === 'sofa-double') && mixedAuto.some(item => item.furnitureTemplateId === 'coffee-round'))
  const appliedMixedPreview = await page.locator('[data-testid="office-design-canvas"] .board-element[data-id]').filter({ has: page.locator('[data-furniture-template]') }).evaluateAll(nodes => nodes.map(node => [node.style.left, node.style.top, node.style.width, node.style.height]))
  assert.deepEqual(appliedMixedPreview, selectedMixedPreview, '应用与所选候选使用同一几何')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual((await state()).objects, beforeMixed, '组合应用可一步撤销')
  // LAYOUT-036：组合求解目标三入口（固定套数/最大容量/切回目标人数）
  await page.getByTestId('mixed-objective-fixed-quantity').click()
  await page.getByTestId('mixed-fixed-quantity').fill('2')
  assert.equal(await page.getByTestId('mixed-layout-count').innerText(), '6 件', '固定套数：2 套工位 + 沙发茶几一套')
  await page.getByTestId('mixed-objective-max-capacity').click()
  const capCount = Number((await page.getByTestId('mixed-layout-count').innerText()).match(/\d+/)[0])
  assert.ok(capCount > 6, '最大容量排满更多工位')
  await page.getByTestId('mixed-objective-target-seats').click()
  await page.getByTestId('mixed-target-seats').fill('0')
  assert.equal(await page.getByTestId('mixed-layout-count').innerText(), '4 件', '切回目标人数恢复默认一套')
  await page.getByTestId('mixed-layout-generate').click()
  await page.getByTestId('mixed-layout-apply').click()
  assert.equal(await page.getByTestId('functional-categories').locator('button[aria-pressed="true"]').count(), 2)
  await page.getByTestId('auto-clear-layout').click()
  assert.equal((await state()).objects.filter(item => item.kind === 'furniture' && item.zoneId === 'OPEN-ROOM').length, 0, '自动布局清空移除当前空间家具')
  assert.equal(await page.getByTestId('functional-categories').locator('button[aria-pressed="true"]').count(), 0, '清空排版同时取消全部功能模块选择')
  assert.equal(await page.getByTestId('mixed-layout-count').innerText(), '0 件')
  assert.equal(await page.getByTestId('auto-layout-next-actions').count(), 0)
  assert.deepEqual(errors, [])
  // 用户截图回归：同样 12 工位/41% 利用率，间距不同；预览关闭后点选卡片也必须更新画布。
  await page.getByTestId('functional-category-workstation').click()
  await page.locator('[data-space-id="NARROW-ROOM"]').click()
  await page.waitForURL(url => url.searchParams.get('zoneId') === 'NARROW-ROOM')
  await page.getByTestId('functional-category-workstation').click()
  await page.getByRole('tab', { name: '选择排布', exact: true }).click()
  await page.getByRole('button', { name: '一字单排', exact: true }).click()
  await page.getByRole('tab', { name: '调整参数', exact: true }).click()
  await page.getByTestId('advanced-spacing').evaluate(node => { node.open = true })
  await page.getByRole('spinbutton', { name: '图例组间净距（毫米）', exact: true }).fill('1000')
  const narrowCards = page.getByTestId('layout-candidate-card')
  assert.equal(await narrowCards.count(), 3)
  const narrowCardTexts = await narrowCards.allInnerTexts()
  for (const text of narrowCardTexts) assert.match(text, /工位 12 · 最小组间净距 [\d,]+ mm/)
  assert.equal(new Set(narrowCardTexts.map(text => text.split('净距 ')[1])).size, 3, '卡片显示三档实际净距差异')
  const narrowStandard = await previewSnapshot()
  assert.equal(narrowStandard.length, 12)
  await page.getByTestId('apply-layout').click()
  await page.getByTestId('layout-unchanged').waitFor()
  const narrowSaved = (await state()).objects
  await page.getByRole('checkbox', { name: '显示排版预览', exact: true }).uncheck()
  assert.equal(await page.locator('[data-preview-id]').count(), 0)
  await narrowCards.nth(1).click()
  await page.waitForFunction(() => document.querySelectorAll('[data-preview-id]').length === 12, null, { timeout: 5000 })
  const narrowCompact = await previewSnapshot()
  assert.notDeepEqual(narrowCompact, narrowStandard, '预览关闭后点击紧凑卡必须显示不同坐标')
  await narrowCards.nth(2).click()
  const narrowComfort = await previewSnapshot()
  assert.notDeepEqual(narrowComfort, narrowCompact, '点击舒适卡必须更新预览')
  assert.notDeepEqual(narrowComfort, narrowStandard)
  assert.deepEqual((await state()).objects, narrowSaved, '比较三档不写入家具')
  assert.deepEqual(errors, [])
  await page.screenshot({ path: (process.env.OUT_DIR || '/tmp') + '/rsdp-narrow-tier-preview.png', animations: 'disabled' })
  await page.getByTestId('apply-layout').click()
  const narrowApplied = await page.locator('[data-testid="office-design-canvas"] .board-element[data-id]').filter({ has: page.locator('[data-furniture-template]') }).evaluateAll(nodes => nodes.map(n => ({ x: n.style.left, y: n.style.top, w: n.style.width, h: n.style.height, transform: n.style.transform })))
  assert.deepEqual(narrowApplied, narrowComfort, '窄空间所选舒适方案应用与预览一致')
  console.log('PASS: module compatibility, preview/application equality, candidates, manual retention, undo, blocking errors, hidden non-blocking hints, mixed layout and automatic clear')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
