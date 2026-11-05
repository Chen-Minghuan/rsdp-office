import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

// 3D 白模交互 UI 回归（RENDER-004）：独立端口 + 模拟 API，测试结束关闭测试服务。
// 商品绑定走真实服务端链路形状（module-recommendations / module-products mock），不再直接改 productId。
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

  const P1 = { id: 'P1', rspuId: 'R1', variantId: 'V1', widthMm: 1200, depthMm: 550, heightMm: 750, widthCm: 120, depthCm: 55, name: '行政桌 1200', desc: '', variant: '暖白 / 钢脚', price: 1999, kind: 'desk' }
  const candidate = { product: P1, tier: 'CLOSE', fitRatio: 0.96, rotationDeg: 0, remainingWidthMm: 0, remainingDepthMm: 50 }

  let page
  const renderSubmissions = []
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  await context.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status, message: '成功', data }) })
    if (path === '/api/v1/office/products') return respond([P1])
    if (path.endsWith('/render-previews') && request.method() === 'POST') {
      const body = request.postDataJSON()
      renderSubmissions.push(body)
      return respond({ previewId: 'RPRE-WM-' + renderSubmissions.length, zoneId: body.zoneId, zoneName: '白模测试区', canvasRevision: body.canvasRevision,
        purpose: 'CONCEPT_PREVIEW', model: 'qwen-image-3.0', style: body.style, view: body.view, instructions: body.instructions,
        snapshot: body.expectedSnapshot, status: 'PENDING', stale: false, sourceType: body.viewImage ? 'whiteModelView' : 'spaceGuide',
        createdAt: new Date().toISOString() })
    }
    if (path.includes('/render-previews/RPRE-WM-')) {
      const id = path.split('/').pop()
      const index = Number(id.replace('RPRE-WM-', '')) - 1
      return respond(renderSubmissions[index] ? { previewId: id, zoneId: 'Z1', zoneName: '白模测试区', canvasRevision: 3, purpose: 'CONCEPT_PREVIEW', model: 'qwen-image-3.0',
        style: renderSubmissions[index].style, view: renderSubmissions[index].view, instructions: '', snapshot: renderSubmissions[index].expectedSnapshot,
        status: 'PENDING', stale: false, sourceType: renderSubmissions[index].viewImage ? 'whiteModelView' : 'spaceGuide', createdAt: new Date().toISOString() } : null)
    }
    if (path.endsWith('/module-recommendations') && request.method() === 'POST') {
      const body = request.postDataJSON()
      return respond({ moduleId: body.moduleId, zoneId: body.zoneId, canvasRevision: body.canvasRevision,
        widthMm: 1200, depthMm: 600, sameModuleCount: 1, missingDimensions: 0, eligibleVariantCount: 1, candidates: [candidate] })
    }
    if (path.endsWith('/module-products') && request.method() === 'POST') {
      const body = request.postDataJSON()
      // 模拟服务端：基于当前已保存画布写回 productId + productSelection，并同步清单
      const state = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01') || '{}'))
      const objects = (state.objects || []).map(o => o.id === body.moduleId
        ? { ...o, productId: body.productId, productSelection: { product: P1, tier: 'CLOSE', fitRatio: 0.96, rotationDeg: 0, remainingWidthMm: 0, remainingDepthMm: 50, cartLineId: 'L1', sourceCanvasRevision: body.canvasRevision } }
        : o)
      return respond({ canvas: { objects, files: state.files || [], revision: (body.canvasRevision || 0) + 1 },
        cartItems: [{ lineId: 'L1', projectId: 'OFFICE-PROJECT-001', zoneId: body.zoneId, zoneName: '白模测试区', productId: body.productId, qty: 1 }],
        match: candidate })
    }
    return respond(path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : [])
  })
  page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))

  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.evaluate(() => {
    const state = JSON.parse(localStorage.getItem('rsdp-office-v01') || '{}')
    state.objects = [
      { id: 'Z1', kind: 'zone', name: '白模测试区', x: 0, y: 0, w: 6000, h: 4000, zoneType: 'OPEN_OFFICE', tone: 'blue', reviewUnit: 'mm',
        polygon: [[0, 0], [6000, 0], [6000, 4000], [0, 4000]],
        obstacles: [{ obstacleId: 'OB1', kind: 'COLUMN', spaceId: 'Z1', geometry: { type: 'POLYGON', coordinateSystem: 'WORLD_MM', outer: [[4500, -100], [4900, -100], [4900, -500], [4500, -500]], holes: [] }, excludesFloorArea: true }],
        facilities: [{ facilityId: 'F1', kind: 'POWER_OUTLET', spaceId: 'Z1', shape: 'point', pointsMm: [[500, -3500]], visible: true }] },
      { id: 'A', kind: 'furniture', zoneId: 'Z1', furnitureTemplateId: 'desk-straight', label: '职员桌 A', x: 400, y: 400, w: 1200, h: 600, rotation: 0, productId: '', layoutConfirmed: true },
      { id: 'B', kind: 'furniture', zoneId: 'Z1', furnitureTemplateId: 'chair-office', label: '椅子 B', x: 2600, y: 400, w: 650, h: 650, rotation: 0, productId: '', layoutConfirmed: true },
      // L 型屏风卡位 90°：回归 3D 朝向与 2D 一致（rotation.y 符号错误曾使 90/270° 家具镜像反转）
      { id: 'L90', kind: 'furniture', zoneId: 'Z1', furnitureTemplateId: 'desk-screen-l', label: '卡位 L90', x: 4200, y: 2200, w: 1400, h: 1400, rotation: 90, productId: '', layoutConfirmed: true },
    ]
    state.canvasRevision = 3; state.canvasMode = 'design'; state.designZoneId = 'Z1'; state.cart = []; state.floorPlan = null; state.spaceTemplate = null
    localStorage.setItem('rsdp-office-v01', JSON.stringify(state))
  })
  await page.goto(baseUrl + '/canvas?mode=design&zoneId=Z1', { waitUntil: 'networkidle' })

  await page.getByRole('button', { name: '3D 白模', exact: true }).click()
  const dialog = page.getByTestId('white-model-preview')
  await dialog.waitFor()
  await page.waitForFunction(() => document.querySelector('[data-testid="white-model-preview"]')?.dataset.ready === 'true', undefined, { timeout: 15000 })
  assert.equal(await dialog.getAttribute('data-furniture'), '3')
  assert.equal(await dialog.getAttribute('data-obstacles'), '1')
  assert.equal(await dialog.getAttribute('data-facilities'), '1')
  assert.equal(await dialog.getAttribute('data-walls'), '4')
  assert.equal(await dialog.getAttribute('data-default-seats'), '2', '工位默认带一把配套办公椅')


  const readObject = id => page.evaluate(oid => JSON.parse(localStorage.getItem('rsdp-office-v01')).objects.find(o => o.id === oid), id)
  const project = target => page.evaluate(t => window.__whiteModelProject(t), target)

  // 朝向回归：90° L 卡位的 3D 配椅投影应与 2D 约定的座位世界坐标一致（仅差 450mm 抬升投影位移）
  const seatCenter = await project('seat:L90')
  assert.ok(seatCenter, 'L90 的配椅已渲染')
  // rotation=90：offset = R(90)·(357-700, 1025-700) = (-325, -343) → 座位世界 = 占地中心 + (-325, -343)
  const seatExpected2 = await project('point:' + (4200 + 700 - 325) + ',' + (2200 + 700 - 343))
  assert.ok(seatExpected2, '2D 约定座位点可投影')
  const seatDrift = Math.hypot(seatCenter.x - seatExpected2.x, seatCenter.y - seatExpected2.y)
  // 反假设：rotation.y 取反时座位应在中心 + (325, 343)；两种假设相距 ≈945mm，远大于抬升位移
  const seatNegated = await project('point:' + (4200 + 700 + 325) + ',' + (2200 + 700 + 343))
  const seatDriftNegated = Math.hypot(seatCenter.x - seatNegated.x, seatCenter.y - seatNegated.y)
  assert.ok(seatDrift < 40 && seatDrift * 2 < seatDriftNegated,
    `90° L 卡位配椅应与 2D 座位位置一致（对 2D 偏差 ${seatDrift.toFixed(1)}px / 对反向假设偏差 ${seatDriftNegated.toFixed(1)}px）`)

  // 3D 场景点选：命中家具实例；点空白取消选中
  const centerA = await project('A')
  assert.ok(centerA, '投影钩子应返回屏幕坐标')
  await page.mouse.click(centerA.x, centerA.y)
  assert.equal(await dialog.getAttribute('data-selected'), 'A', '点击家具应选中实例')
  const pairedChair = await project('seat:A')
  assert.ok(pairedChair, '默认配椅已在 3D 中渲染')
  await page.mouse.click(pairedChair.x, pairedChair.y)
  assert.equal(await dialog.getAttribute('data-selected'), 'A', '点击配椅仍选中同一工位')
  await page.getByTestId('white-model-paired-seat').waitFor()
  await page.getByTestId('white-model-unbound').waitFor()
  const emptySpot = await project('point:5900,2500')
  await page.mouse.click(emptySpot.x, emptySpot.y)
  assert.equal(await dialog.getAttribute('data-selected'), null, '点击空白应取消选中')
  const unselectedA = await readObject('A')
  await page.keyboard.press('ArrowRight')
  assert.deepEqual(await readObject('A'), unselectedA, '未选家具时方向键不修改数据')

  // 列表选择 + 替换商品（真实服务端链路 mock）：布局几何不变，写回 productId + productSelection
  await page.getByTestId('white-model-select-A').click()
  await page.getByTestId('white-model-product-change').click()
  const productDialog = page.getByTestId('white-model-product-dialog')
  await productDialog.waitFor()
  await page.getByTestId('white-model-product-option-P1').getByRole('button', { name: '绑定该商品' }).click()
  await page.getByTestId('white-model-notice').waitFor()
  const boundA = await readObject('A')
  assert.equal(boundA.productId, 'P1', '应写回真实商品绑定')
  assert.equal(boundA.productSelection?.cartLineId, 'L1', '应写入服务端选品快照')
  assert.deepEqual([boundA.w, boundA.h], [1200, 600], '绑定商品不得改变布局占地')
  assert.equal(await page.getByTestId('white-model-product-name').textContent(), '行政桌 1200', '属性面板应展示真实商品名')

  // 旋转写回同一份设计几何（带校验的 rotateFurnitureGroup），确认状态失效
  const chairBeforeRotate = await project('seat:A')
  await page.getByTestId('white-model-rotate').click()
  assert.equal((await readObject('A')).rotation, 90, '旋转应写回设计几何')
  assert.equal((await readObject('A')).layoutConfirmed, false, '旋转后确认状态应失效')
  assert.notDeepEqual(await project('seat:A'), chairBeforeRotate, '配椅随同一工位旋转')

  // 替换模块：复用 store.replaceFurnitureModule（保留 ID/中心/旋转，清除旧选品）
  await page.getByTestId('white-model-template').selectOption('desk-manager')
  const replacedA = await readObject('A')
  assert.deepEqual([replacedA.w, replacedA.h], [1400, 600], '替换模块应同步宽深')
  assert.equal(replacedA.furnitureTemplateId, 'desk-manager')
  assert.equal(replacedA.productId, '', '替换模块应清除旧商品绑定')

  // 拖动到与其他家具重叠的位置：硬校验拒绝并回退
  const badDragStart = await project('A')
  const ontoB = await project('point:2925,725')
  await page.mouse.move(badDragStart.x, badDragStart.y)
  await page.mouse.down()
  await page.mouse.move(ontoB.x, ontoB.y, { steps: 12 })
  await page.mouse.up()
  await page.waitForFunction(() => document.querySelector('[data-testid="white-model-notice"]')?.textContent?.includes('未移动'))
  assert.deepEqual([(await readObject('A')).x, (await readObject('A')).y], [300, 400], '拒绝后应保持原位置')

  // 拖动到合法位置：写回并同步 2D
  const goodTarget = await project('point:2000,1500')
  const dragStart = await project('A')
  await page.mouse.move(dragStart.x, dragStart.y)
  await page.mouse.down()
  await page.mouse.move(goodTarget.x, goodTarget.y, { steps: 12 })
  await page.mouse.up()
  await page.waitForFunction(() => document.querySelector('[data-testid="white-model-notice"]')?.textContent?.includes('已移动'))
  const movedA = await readObject('A')
  // 拖动越过阈值前 OrbitControls 会响应首个位移事件，相机轻微旋转导致落点偏移；容差 300mm
  assert.ok(Math.abs(movedA.x - 1300) <= 300 && Math.abs(movedA.y - 1200) <= 300, `拖动落点应接近 (1300,1200)，实际 (${movedA.x},${movedA.y})`)
  assert.equal(await dialog.getAttribute('data-selected'), 'A', '拖动后应保持选中')

  // 键盘微调：视角方向、三档毫米步长、连续按键、撤销、碰撞与控件隔离。
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.furnitureForZone('Z1').forEach(item => { item.layoutConfirmed = true })
    store.saveLocal()
  })
  await page.getByTestId('white-model-select-A').click()
  await page.getByTestId('white-model-keyboard-help').waitFor()
  const keyboardOrigin = await readObject('A')
  const distance = (a, b) => Math.hypot(a.x - b.x, a.y - b.y)
  const beforeRight = await project('A')
  const chairBeforeRight = await project('seat:A')
  await page.keyboard.press('ArrowRight')
  const nudged = await readObject('A'), afterRight = await project('A')
  assert.ok(Math.abs(distance(nudged, keyboardOrigin) - 10) < 0.02, '普通方向键移动 10mm')
  assert.ok(afterRight.x > beforeRight.x, '向右键对应当前画面向右')
  assert.ok((await project('seat:A')).x > chairBeforeRight.x, '键盘移动时配椅一起移动')
  assert.equal(nudged.layoutConfirmed, false, '键盘移动使当前空间排版确认失效')
  assert.equal((await readObject('B')).layoutConfirmed, false)
  await page.keyboard.press('Control+z')
  assert.deepEqual(await readObject('A'), keyboardOrigin, 'Ctrl+Z 恢复几何及确认状态')
  for (const [key, step] of [['Shift+ArrowUp', 100], ['Alt+ArrowLeft', 1]]) {
    await page.keyboard.press(key)
    assert.ok(Math.abs(distance(await readObject('A'), keyboardOrigin) - step) < 0.02, `${key} 步长 ${step}mm`)
    await page.keyboard.press('Meta+z')
    assert.deepEqual(await readObject('A'), keyboardOrigin, '⌘+Z 撤销并同步保存')
  }
  await page.keyboard.down('ArrowRight')
  await page.keyboard.down('ArrowRight')
  await page.keyboard.down('ArrowRight')
  await page.keyboard.up('ArrowRight')
  assert.ok(Math.abs(distance(await readObject('A'), keyboardOrigin) - 30) < 0.03, '长按重复按键连续移动')
  for (let i = 0; i < 3; i++) await page.keyboard.press('Control+z')
  assert.deepEqual(await readObject('A'), keyboardOrigin)
  await page.getByTestId('white-model-template').dispatchEvent('keydown', { key: 'ArrowRight', bubbles: true })
  assert.deepEqual(await readObject('A'), keyboardOrigin, '输入控件内方向键不移动家具')
  await page.getByTestId('white-model-product-change').click()
  await productDialog.waitFor()
  await page.keyboard.press('ArrowRight')
  assert.deepEqual(await readObject('A'), keyboardOrigin, '商品弹窗打开时不移动家具')
  await page.getByRole('button', { name: '关闭替换商品', exact: true }).click()
  await page.getByTestId('white-model-select-A').click()
  let keyboardBlocked = false
  for (let i = 0; i < 40; i++) {
    const before = await readObject('A')
    await page.keyboard.press('Shift+ArrowLeft')
    const after = await readObject('A')
    if (after.x === before.x && after.y === before.y) {
      assert.match(await page.getByTestId('white-model-notice').textContent(), /未移动.*(超出|重叠|占用)/)
      keyboardBlocked = true
      break
    }
  }
  assert.ok(keyboardBlocked, '键盘无法越界或穿过已有家具/禁放区')
  assert.equal(await dialog.getAttribute('data-selected'), 'A', '连续移动保持实例选中')

  // 统一排版的 16 张同规格职员桌只显示一行，场景实例和逐件属性仍保留。
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const door = (id, x, y, signX, signY) => ({ obstacleId: id, kind: 'DOOR_CLEARANCE', spaceId: 'GROUP',
      geometry: { type: 'POLYGON', coordinateSystem: 'WORLD_MM', holes: [], outer: [[x, y], ...Array.from({ length: 17 }, (_, i) => [x + signX * 1000 * Math.cos(i * Math.PI / 32), y + signY * 1000 * Math.sin(i * Math.PI / 32)])] }, excludesFloorArea: false, source: 'MANUAL' })
    store.objects.push({ id: 'GROUP', kind: 'zone', name: '统一工位测试区', x: 8000, y: 0, w: 12000, h: 8000, zoneType: 'OPEN_OFFICE', tone: 'blue', reviewUnit: 'mm', polygon: [[8000, 0], [20000, 0], [20000, 8000], [8000, 8000]], obstacles: [
      { obstacleId: 'GW1', kind: 'WINDOW', spaceId: 'GROUP', geometry: { type: 'POLYGON', coordinateSystem: 'WORLD_MM', outer: [[10000, 0.5], [12800, 0.5], [12800, -0.5], [10000, -0.5]], holes: [] }, excludesFloorArea: false },
      { obstacleId: 'GW2', kind: 'WINDOW', spaceId: 'GROUP', geometry: { type: 'POLYGON', coordinateSystem: 'WORLD_MM', outer: [[7999.5, -1500], [7999.5, -4300], [8000.5, -4300], [8000.5, -1500]], holes: [] }, excludesFloorArea: false },
      door('GD1', 16800, 0, 1, -1), door('GD2', 20000, -5000, -1, -1),
    ], facilities: [] })
    for (let i = 0; i < 16; i++) store.objects.push({ id: `G${i}`, kind: 'furniture', zoneId: 'GROUP', furnitureTemplateId: 'desk-straight', label: '职员直线桌', x: 8500 + i % 4 * 2000, y: 700 + Math.floor(i / 4) * 1700, w: 1200, h: 600, rotation: i % 2 * 180, productId: '', layoutGroupId: 'auto:GROUP', layoutRole: 'primary' })
    store.enterDesign('GROUP')
  })
  await page.waitForURL(/zoneId=GROUP/)
  await page.getByRole('button', { name: '3D 白模', exact: true }).click()
  await page.waitForFunction(() => document.querySelector('[data-testid="white-model-preview"]')?.dataset.ready === 'true', undefined, { timeout: 15000 })
  await page.waitForFunction(() => document.querySelector('[data-testid="white-model-preview"]')?.dataset.furniture === '16')
  assert.equal(await dialog.getAttribute('data-default-seats'), '16', '16 个工位默认都有办公椅')
  assert.equal(await dialog.getAttribute('data-windows'), '2', '横竖墙窗均生成立体窗')
  assert.equal(await dialog.getAttribute('data-doors'), '2', '横竖墙的门洞生成门框与开启门扇')
  assert.equal(await dialog.getAttribute('data-openings'), '4', '两个窗洞与两个落地门洞')
  const windowSnapshot = await page.evaluate(() => JSON.stringify(document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').zones.find(z => z.id === 'GROUP').obstacles))
  const groupRow = page.getByTestId('white-model-furniture-row')
  assert.equal(await groupRow.count(), 1, '16 张职员桌只显示一行')
  assert.match(await groupRow.innerText(), /职员直线桌.*1200×600mm.*16 件/)
  const groupSnapshot = await page.evaluate(() => JSON.stringify(document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').furnitureForZone('GROUP')))
  await page.getByTestId('white-model-select-G0').click()
  assert.equal(await dialog.getAttribute('data-selected'), 'G0')
  const lastDesk = await project('G15')
  await page.mouse.click(lastDesk.x, lastDesk.y)
  assert.equal(await dialog.getAttribute('data-selected'), 'G15', '场景中仍可选中组内其他实例')
  assert.equal(await groupRow.getByRole('button').getAttribute('aria-pressed'), 'true', '组内实例点选映射到同一列表行')
  await groupRow.getByRole('button').click()
  assert.equal(await dialog.getAttribute('data-selected'), 'G15', '点击已选行保持实际选中实例')
  assert.equal(await page.evaluate(() => JSON.stringify(document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').furnitureForZone('GROUP'))), groupSnapshot, '分组合并展示与选择不修改家具数据')
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const last = store.objects.find(item => item.id === 'G15')
    last.productId = 'P1'
    last.layoutGroupId = 'another-layout'
  })
  assert.equal(await groupRow.count(), 1, '商品绑定和排版来源不同不应拆分同规格职员桌')
  assert.match(await groupRow.innerText(), /16 件/)
  assert.equal(await dialog.getAttribute('data-selected'), 'G15')
  assert.equal(await page.getByTestId('white-model-product-name').textContent(), '行政桌 1200', '合并后属性继续对应实际选中实例的商品')
  await page.screenshot({ path: '/tmp/rsdp-grouped-staff-desks.png', animations: 'disabled' })
  await page.screenshot({ path: '/tmp/rsdp-real-window-model.png', animations: 'disabled' })
  await page.screenshot({ path: '/tmp/rsdp-real-door-model.png', animations: 'disabled' })
  await page.screenshot({ path: '/tmp/rsdp-workstation-table-chairs.png', animations: 'disabled' })
  assert.equal(await page.evaluate(() => JSON.stringify(document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').zones.find(z => z.id === 'GROUP').obstacles)), windowSnapshot, '渲染窗洞不改 2D 标注数据')
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.objects.find(item => item.id === 'G1').w = 1400
    store.objects.find(item => item.id === 'G2').productId = 'P1'
    store.objects.push({ id: 'GC1', kind: 'furniture', zoneId: 'GROUP', furnitureTemplateId: 'chair-office', label: '独立椅子', x: 18000, y: 500, w: 650, h: 650, rotation: 0, productId: '' }, { id: 'GC2', kind: 'furniture', zoneId: 'GROUP', furnitureTemplateId: 'chair-office', label: '独立椅子', x: 18000, y: 2000, w: 650, h: 650, rotation: 0, productId: '' })
  })
  assert.equal(await groupRow.count(), 4, '只按不同模块规格拆分职员桌，普通家具逐件保留')
  assert.equal(await groupRow.filter({ hasText: '15 件' }).count(), 1)
  assert.equal(await dialog.getAttribute('data-furniture'), '18', '聚合不减少场景实例数')
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.objects.push({ id: 'PAIRED-EXISTING', kind: 'furniture', zoneId: 'GROUP', furnitureTemplateId: 'chair-office', label: '已有配套办公椅', x: 8643, y: 1325, w: 650, h: 650, rotation: 0 })
  })
  await page.waitForFunction(() => document.querySelector('[data-testid="white-model-preview"]')?.dataset.defaultSeats === '15')
  assert.equal(await project('seat:G0'), null, '已有独立配椅时不叠加默认配椅')
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.objects = store.objects.filter(item => item.id !== 'PAIRED-EXISTING')
  })

  // 从低视角检查整圈墙顶与窗洞，避免俯视图掩盖高低墙接缝。
  await page.mouse.move(1100, 250)
  await page.mouse.down()
  await page.mouse.move(1100, 145, { steps: 12 })
  await page.mouse.up()
  await page.mouse.move(1200, 100)
  await page.waitForTimeout(800) // OrbitControls 阻尼结束后留图
  await page.screenshot({ path: '/tmp/rsdp-uniform-wall-height.png', animations: 'disabled' })
  await page.screenshot({ path: '/tmp/rsdp-door-low-angle.png', animations: 'disabled' })

  // AI 入口：携带白模当前视角截图作为结构参考（RENDER-006），不再选择视角
  await page.getByRole('button', { name: 'AI 概念效果图' }).click()
  const renderDialog = page.getByRole('dialog', { name: '单空间效果预览' })
  await renderDialog.waitFor()
  await page.getByTestId('render-view-image').waitFor()
  assert.equal(await page.getByLabel('效果图视角').count(), 0, '白模截图引导时不再选择观察视角')
  await page.getByRole('button', { name: '生成效果预览', exact: true }).click()
  await page.getByTestId('render-source-type').waitFor()
  assert.equal(renderSubmissions.length, 1)
  assert.ok(renderSubmissions[0].viewImage?.startsWith('data:image/png;base64,'), '应携带白模当前视角截图作为 viewImage')
  assert.ok(renderSubmissions[0].viewImage.length > 5000, '截图应为真实 PNG 内容')
  assert.equal(renderSubmissions[0].zoneId, 'GROUP')
  await page.keyboard.press('Escape')
  await renderDialog.waitFor({ state: 'hidden' })

  await page.getByRole('button', { name: '关闭 3D 预览', exact: true }).click()
  const closedObjects = await page.evaluate(() => JSON.stringify(document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').objects))
  await page.keyboard.press('ArrowRight')
  assert.equal(await page.evaluate(() => JSON.stringify(document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').objects)), closedObjects, '关闭 3D 后快捷键不残留')

  assert.deepEqual(errors, [], `不应有未捕获错误：${errors.join('; ')}`)
  console.log('3D 白模 UI 通过：场景统计、3D 点选/取消、真实链路绑定商品（几何不变）、旋转/替换模块写回、重叠拖动拒绝回退、合法拖动同步 2D、AI 入口保留。API 全部模拟。')
} finally {
  await browser?.close()
  app.kill()
}
