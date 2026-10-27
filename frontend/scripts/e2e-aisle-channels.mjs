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
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const source = {
    analysisId: 'CHANNEL-TEST', analysisRevision: 1, status: 'awaiting_confirm', sourceName: '通道排版测试.dxf',
    inputMode: 'cad', geometrySource: 'cad_geometry', evidenceKind: 'REAL_SERVICE',
    unitResolution: { canonicalUnit: 'mm', confirmed: true },
    rooms: [{ roomId: 'CHANNEL-ROOM', label: '培训室', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[0, 0], [16000, 0], [16000, 10000], [0, 10000]] }],
  }
  let floorPlanRequests = 0
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    const data = path.endsWith('/floor-plan/CHANNEL-TEST') ? (floorPlanRequests++ === 0 ? source : null) : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  const zoneState = async () => (await state()).objects.find(object => object.kind === 'zone' && object.id === 'CHANNEL-ROOM')
  const furnitureState = async () => (await state()).objects.filter(object => object.kind === 'furniture' && object.zoneId === 'CHANNEL-ROOM')
  const channelsState = async () => (await zoneState()).layoutPreferences?.channels || []
  const capacity = async () => Number((await page.getByTestId('module-capacity').innerText()).replace(/[^0-9]/g, ''))
  // 与 officeFurnitureFootprint 一致：旋转 90/270° 时物理占地围绕中心交换宽高。
  const footprint = (item) => {
    const radians = (item.rotation || 0) * Math.PI / 180
    const w = Math.abs(item.w * Math.cos(radians)) + Math.abs(item.h * Math.sin(radians))
    const h = Math.abs(item.w * Math.sin(radians)) + Math.abs(item.h * Math.cos(radians))
    return { x: item.x + (item.w - w) / 2, y: item.y + (item.h - h) / 2, w, h }
  }
  const intersectsBand = (item, channel) => {
    const rect = footprint(item)
    const clearance = channel.clearanceMm || 0
    return channel.orientation === 'vertical'
      ? rect.x < channel.offsetMm + channel.widthMm + clearance && rect.x + rect.w > channel.offsetMm - clearance
      : rect.y < channel.offsetMm + channel.widthMm + clearance && rect.y + rect.h > channel.offsetMm - clearance
  }

  await page.goto(`${baseUrl}/canvas?analysisId=CHANNEL-TEST`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '选择空间 培训室', exact: true }).waitFor()
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    for (const zone of draft.objects.filter(object => object.kind === 'zone')) zone.reviewUnit = 'mm'
    localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
  })
  await page.goto(`${baseUrl}/canvas?analysisId=CHANNEL-TEST`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()
  await page.getByTestId('unit-module-catalog').waitFor()

  // 主管直线桌是非职员桌/会议类模块，走课桌式网格排版（原生通道切段）。
  await page.locator('details summary', { hasText: '主管桌' }).first().click()
  await page.getByRole('button', { name: /主管直线桌/ }).first().click()
  await page.getByRole('tab', { name: '选择排布', exact: true }).click()
  await page.getByRole('button', { name: '按场地排布', exact: true }).click()
  const baseline = await capacity()
  assert.ok(baseline > 10, `基线容量异常: ${baseline}`)

  // 1. 添加纵向通道：预览容量下降，画布出现通道条带，参数落到 layoutPreferences。
  await page.getByTestId('advanced-options').locator('summary').first().click()
  await page.getByTestId('channel-panel').locator('summary').click()
  await page.getByTestId('add-channel-vertical').click()
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || []).length === 1
  })
  assert.equal((await page.getByTestId('layout-channels').locator('[data-channel-id]').count()), 1)
  const withChannel = await capacity()
  assert.ok(withChannel < baseline, `加通道后容量应下降: ${withChannel} !< ${baseline}`)

  // 2. 应用后家具不压占通道（未勾选二排起，通道全深生效）。
  await page.getByTestId('apply-layout').click()
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return draft.objects.some(object => object.kind === 'furniture' && object.zoneId === 'CHANNEL-ROOM')
  })
  let channel = (await channelsState())[0]
  let items = await furnitureState()
  assert.ok(items.length > 0)
  assert.ok(items.every(item => !intersectsBand(item, channel)), '应用后仍有家具压占纵向通道')

  // 3. 仅从二排起：首排允许跨过通道，后续排必须断开；首排深度随实例保存。
  await page.getByLabel('仅从二排起').check()
  await page.getByTestId('apply-layout').click()
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return draft.objects.some(object => object.kind === 'furniture' && object.zoneId === 'CHANNEL-ROOM' && object.layoutSettings?.firstBlockDepthMm)
  })
  channel = (await channelsState())[0]
  assert.equal(channel.fromSecondRow, true)
  items = await furnitureState()
  const firstBlockDepth = items[0].layoutSettings.firstBlockDepthMm
  assert.ok(firstBlockDepth > 0)
  // 设计副本 Y 向下且常为负坐标，首排深度是相对包围盒顶部的距离，分类前先换算。
  const zoneMinY = Math.min(...(await zoneState()).polygon.map(point => point[1]))
  const firstRow = items.filter(item => item.y - zoneMinY + item.h <= firstBlockDepth + 1)
  const laterRows = items.filter(item => item.y - zoneMinY >= firstBlockDepth - 1)
  assert.ok(firstRow.some(item => intersectsBand(item, channel)), '首排应保持完整跨过通道')
  assert.ok(laterRows.length > 0 && laterRows.every(item => !intersectsBand(item, channel)), '二排起之后仍有家具压占通道')
  const exemptCapacity = items.length
  assert.ok(exemptCapacity > withChannel, `二排起容量应高于全深通道: ${exemptCapacity} !> ${withChannel}`)

  // 4. 取消二排起，恢复全深通道。
  await page.getByLabel('仅从二排起').uncheck()
  await page.getByTestId('apply-layout').click()
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return !draft.objects.some(object => object.kind === 'furniture' && object.zoneId === 'CHANNEL-ROOM' && object.layoutSettings?.firstBlockDepthMm)
  })
  channel = (await channelsState())[0]
  items = await furnitureState()
  assert.ok(items.every(item => !intersectsBand(item, channel)), '取消二排起后仍有家具压占通道')

  // 4.5 两侧净距：禁放带按 widthMm + 2×clearanceMm 扩展；应用后家具不压占扩展带。
  // 该测试几何下原布局必有家具落在通道边缘 300mm 内，否则净距断言无效（提前失败提示调整参数）。
  assert.ok(items.some(item => intersectsBand(item, { ...channel, clearanceMm: 300 })), '测试几何下原布局应贴近通道，否则净距验证无效')
  await page.getByLabel('通道两侧净距（毫米）').fill('300')
  await page.getByLabel('通道两侧净距（毫米）').press('Enter')
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || [])[0]?.clearanceMm === 300
  })
  const withClearance = await capacity()
  assert.ok(withClearance <= withChannel, `设净距后容量不应上升: ${withClearance} > ${withChannel}`)
  await page.getByTestId('apply-layout').click()
  await page.waitForFunction(() => {
    const fp = (item) => {
      const radians = (item.rotation || 0) * Math.PI / 180
      const w = Math.abs(item.w * Math.cos(radians)) + Math.abs(item.h * Math.sin(radians))
      return { x: item.x + (item.w - w) / 2, w }
    }
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    const channels = draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || []
    const items = draft.objects.filter(object => object.kind === 'furniture' && object.zoneId === 'CHANNEL-ROOM')
    const clearance = channels[0]?.clearanceMm || 0
    return items.length > 0 && items.every(item => { const rect = fp(item); return !(rect.x < channels[0].offsetMm + channels[0].widthMm + clearance && rect.x + rect.w > channels[0].offsetMm - clearance) })
  })
  // 还原净距为 0 并重新应用，布局应恢复贴近通道（有家具重新进入 300mm 范围）。
  await page.getByLabel('通道两侧净距（毫米）').fill('0')
  await page.getByLabel('通道两侧净距（毫米）').press('Enter')
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return ((draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || [])[0]?.clearanceMm || 0) === 0
  })
  await page.getByTestId('apply-layout').click()
  await page.waitForFunction(() => {
    const fp = (item) => {
      const radians = (item.rotation || 0) * Math.PI / 180
      const w = Math.abs(item.w * Math.cos(radians)) + Math.abs(item.h * Math.sin(radians))
      return { x: item.x + (item.w - w) / 2, w }
    }
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    const channels = draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || []
    const items = draft.objects.filter(object => object.kind === 'furniture' && object.zoneId === 'CHANNEL-ROOM')
    return items.some(item => { const rect = fp(item); return rect.x < channels[0].offsetMm + channels[0].widthMm + 300 && rect.x + rect.w > channels[0].offsetMm - 300 })
  })

  // 5. 画布拖动通道：松手提交，offsetMm 变化。向左小距离拖动，保持通道仍在家具间隙内。
  const beforeDrag = (await channelsState())[0].offsetMm
  const strip = page.getByTestId('layout-channels').locator('[data-channel-id]').first()
  const box = await strip.boundingBox()
  assert.ok(box, '通道条带不可见')
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 - 2, box.y + box.height / 2, { steps: 2 })
  await page.mouse.up()
  await page.waitForFunction(previous => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || [])[0]?.offsetMm !== previous
  }, beforeDrag)
  const afterDrag = (await channelsState())[0].offsetMm
  assert.ok(afterDrag < beforeDrag, '向左拖动后通道位置应减小')

  // 5.5 通道不允许拖出空间：大幅向左拖出包围盒，松手后钳制在盒内；含净距时按扩展带钳制。
  const zoneMinX = Math.min(...(await zoneState()).polygon.map(point => point[0]))
  const dragFarLeft = async () => {
    const current = await page.getByTestId('layout-channels').locator('[data-channel-id]').first().boundingBox()
    assert.ok(current, '通道条带不可见')
    await page.mouse.move(current.x + current.width / 2, current.y + current.height / 2)
    await page.mouse.down()
    await page.mouse.move(2, current.y + current.height / 2, { steps: 4 })
    await page.mouse.up()
  }
  await dragFarLeft()
  await page.waitForFunction(previous => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || [])[0]?.offsetMm !== previous
  }, afterDrag)
  assert.equal((await channelsState())[0].offsetMm, zoneMinX, '拖出空间后应钳制到包围盒左缘')
  await page.getByLabel('通道两侧净距（毫米）').fill('300')
  await page.getByLabel('通道两侧净距（毫米）').press('Enter')
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || [])[0]?.clearanceMm === 300
  })
  await dragFarLeft()
  await page.waitForFunction(minX => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || [])[0]?.offsetMm > minX
  }, zoneMinX)
  assert.equal((await channelsState())[0].offsetMm, zoneMinX + 300, '含净距时应钳制到 左缘+净距')
  // 还原：净距归零、位置恢复到第 5 步拖动后的值，后续步骤不受影响。
  await page.getByLabel('通道两侧净距（毫米）').fill('0')
  await page.getByLabel('通道两侧净距（毫米）').press('Enter')
  await page.getByLabel('通道位置（毫米）').fill(String(Math.round(afterDrag - zoneMinX)))
  await page.getByLabel('通道位置（毫米）').press('Enter')
  await page.waitForFunction(expected => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    const channel = (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || [])[0]
    return channel && (channel.clearanceMm || 0) === 0 && channel.offsetMm === expected
  }, afterDrag)

  // 5.6 宽度阈值提示：低于 900mm 给出策略提示（不拦截），恢复后提示消失。
  await page.getByLabel('通道宽度（毫米）').fill('800')
  await page.getByLabel('通道宽度（毫米）').press('Enter')
  await page.getByTestId('channel-width-hint').waitFor()
  await page.getByLabel('通道宽度（毫米）').fill('900')
  await page.getByLabel('通道宽度（毫米）').press('Enter')
  await page.waitForFunction(() => !document.querySelector('[data-testid="channel-width-hint"]'))

  // 5.7 通道选中态：点选条带出现浮动编辑卡，卡内编辑宽度生效，Esc 取消选中。
  const stripForSelect = page.getByTestId('layout-channels').locator('[data-channel-id]').first()
  const selectBox = await stripForSelect.boundingBox()
  assert.ok(selectBox, '通道条带不可见')
  await page.mouse.click(selectBox.x + selectBox.width / 2, selectBox.y + selectBox.height / 2)
  await page.getByTestId('channel-editor').waitFor()
  // 编辑卡跟随通道条带位置：与条带相邻（一侧间隙小），且不超出画布窗口
  {
    const editorBox = await page.getByTestId('channel-editor').boundingBox()
    const canvasBox = await page.getByTestId('office-design-canvas').boundingBox()
    assert.ok(editorBox && canvasBox, '编辑卡或画布不可见')
    const gapRight = editorBox.x - (selectBox.x + selectBox.width)
    const gapLeft = selectBox.x - (editorBox.x + editorBox.width)
    const gapBelow = editorBox.y - (selectBox.y + selectBox.height)
    const gapAbove = selectBox.y - (editorBox.y + editorBox.height)
    const adjacent = (gapRight >= -2 && gapRight < 40) || (gapLeft >= -2 && gapLeft < 40) || (gapBelow >= -2 && gapBelow < 40) || (gapAbove >= -2 && gapAbove < 40)
    assert.ok(adjacent, `编辑卡应跟随条带相邻显示: gaps R${gapRight.toFixed(0)} L${gapLeft.toFixed(0)} B${gapBelow.toFixed(0)} A${gapAbove.toFixed(0)}`)
    assert.ok(editorBox.x >= canvasBox.x && editorBox.y >= canvasBox.y && editorBox.x + editorBox.width <= canvasBox.x + canvasBox.width + 1 && editorBox.y + editorBox.height <= canvasBox.y + canvasBox.height + 1, '编辑卡钳制在画布窗口内')
  }
  await page.getByLabel('通道属性宽度（毫米）').fill('1000')
  await page.getByLabel('通道属性宽度（毫米）').press('Enter')
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || [])[0]?.widthMm === 1000
  })
  await page.getByLabel('通道属性宽度（毫米）').fill('900')
  await page.getByLabel('通道属性宽度（毫米）').press('Enter')
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || [])[0]?.widthMm === 900
  })
  await page.keyboard.press('Escape')
  await page.waitForFunction(() => !document.querySelector('[data-testid="channel-editor"]'))

  // 5.8 宽度拖柄：选中后拖 max 边加宽、拖 min 边远端固定、越界钳制；完成后恢复原值。
  await page.mouse.click(selectBox.x + selectBox.width / 2, selectBox.y + selectBox.height / 2)
  await page.getByTestId('channel-editor').waitFor()
  const stripBox = await page.getByTestId('layout-channels').locator('[data-channel-id]').first().boundingBox()
  assert.ok(stripBox, '通道条带不可见')
  const zoom = stripBox.width / 900
  const zoneMaxX = Math.max(...(await zoneState()).polygon.map(point => point[0]))
  const maxHandle = page.getByTestId('channel-width-handle-max')
  const maxBox = await maxHandle.boundingBox()
  assert.ok(maxBox, '宽度拖柄不可见')
  await page.mouse.move(maxBox.x + maxBox.width / 2, maxBox.y + maxBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(maxBox.x + maxBox.width / 2 + 3, maxBox.y + maxBox.height / 2, { steps: 2 })
  await page.mouse.up()
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || [])[0]?.widthMm !== 900
  })
  const afterMaxEdge = (await channelsState())[0]
  assert.ok(Math.abs(afterMaxEdge.widthMm - (900 + 3 / zoom)) <= 2, `拖 max 边宽度异常: ${afterMaxEdge.widthMm}（zoom=${zoom}）`)
  assert.equal(afterMaxEdge.offsetMm, afterDrag, '拖 max 边不应改位置')
  const minBox = await page.getByTestId('channel-width-handle-min').boundingBox()
  assert.ok(minBox, '宽度拖柄不可见')
  await page.mouse.move(minBox.x + minBox.width / 2, minBox.y + minBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(minBox.x + minBox.width / 2 - 2, minBox.y + minBox.height / 2, { steps: 2 })
  await page.mouse.up()
  await page.waitForFunction(expected => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || [])[0]?.offsetMm !== expected
  }, afterMaxEdge.offsetMm)
  const afterMinEdge = (await channelsState())[0]
  assert.ok(Math.abs(afterMinEdge.offsetMm - (afterMaxEdge.offsetMm - 2 / zoom)) <= 2, '拖 min 边位置应随边缘移动')
  assert.ok(Math.abs((afterMinEdge.offsetMm + afterMinEdge.widthMm) - (afterMaxEdge.offsetMm + afterMaxEdge.widthMm)) <= 2, '拖 min 边远端应保持固定')
  // 越界：max 边大幅右拖，宽度钳制到 右缘-位置。
  const clampBox = await page.getByTestId('channel-width-handle-max').boundingBox()
  assert.ok(clampBox, '宽度拖柄不可见')
  await page.mouse.move(clampBox.x + clampBox.width / 2, clampBox.y + clampBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(clampBox.x + clampBox.width / 2 + 2000, clampBox.y + clampBox.height / 2, { steps: 4 })
  await page.mouse.up()
  await page.waitForFunction(width => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || [])[0]?.widthMm > width
  }, afterMinEdge.widthMm)
  const clamped = (await channelsState())[0]
  assert.ok(Math.abs(clamped.widthMm - (zoneMaxX - clamped.offsetMm)) <= 2, `越界拖宽应钳制到右缘: ${clamped.widthMm} + ${clamped.offsetMm} ≠ ${zoneMaxX}`)
  // 恢复宽度与位置，保证后续步骤不受影响。
  await page.getByLabel('通道属性宽度（毫米）').fill('900')
  await page.getByLabel('通道属性宽度（毫米）').press('Enter')
  await page.getByLabel('通道属性位置（毫米）').fill(String(Math.round(afterDrag - zoneMinX)))
  await page.getByLabel('通道属性位置（毫米）').press('Enter')
  await page.waitForFunction(expected => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    const channel = (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || [])[0]
    return channel && channel.widthMm === 900 && channel.offsetMm === expected
  }, afterDrag)
  await page.keyboard.press('Escape')
  await page.waitForFunction(() => !document.querySelector('[data-testid="channel-editor"]'))

  // 5.9 拖动吸附：拖到距左墙 40mm（50mm 阈值内）应吸附到墙缘 0，而不是停在 40。
  const snapStripBox = await page.getByTestId('layout-channels').locator('[data-channel-id]').first().boundingBox()
  assert.ok(snapStripBox, '通道条带不可见')
  const snapZoom = snapStripBox.width / 900
  await page.mouse.move(snapStripBox.x + snapStripBox.width / 2, snapStripBox.y + snapStripBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(snapStripBox.x + snapStripBox.width / 2 + (40 - afterDrag) * snapZoom, snapStripBox.y + snapStripBox.height / 2, { steps: 4 })
  await page.mouse.up()
  await page.waitForFunction(expected => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || [])[0]?.offsetMm !== expected
  }, afterDrag)
  assert.equal((await channelsState())[0].offsetMm, zoneMinX, '距墙 40mm 应吸附到墙缘（阈值 50mm）')
  // 恢复位置，后续步骤不受影响。
  await page.getByLabel('通道位置（毫米）').fill(String(Math.round(afterDrag - zoneMinX)))
  await page.getByLabel('通道位置（毫米）').press('Enter')
  await page.waitForFunction(expected => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || [])[0]?.offsetMm === expected
  }, afterDrag)
  await page.keyboard.press('Escape')
  await page.waitForFunction(() => !document.querySelector('[data-testid="channel-editor"]'))

  // 6. 合法布局可以确认；新增压占家具的通道使确认失效且不能重新确认。
  await page.getByRole('button', { name: '确认当前排版', exact: true }).click()
  await page.getByRole('button', { name: '重新调整排版', exact: true }).waitFor()
  items = await furnitureState()
  const victim = items[0]
  await page.getByTestId('add-channel-vertical').click()
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || []).length === 2
  })
  const secondPanel = page.getByTestId('channel-vertical').nth(1)
  await secondPanel.getByLabel('通道位置（毫米）').fill(String(Math.round(victim.x + victim.w / 2 - 450)))
  await secondPanel.getByLabel('通道位置（毫米）').press('Enter')
  await page.getByRole('button', { name: '确认当前排版', exact: true }).waitFor()
  assert.ok((await furnitureState()).every(item => !item.layoutConfirmed), '通道变化应使排版确认失效')
  await page.getByRole('button', { name: '确认当前排版', exact: true }).click()
  await page.locator('[role="status"]').filter({ hasText: '家具压占通道，请调整家具或通道位置' }).waitFor()
  assert.ok((await furnitureState()).every(item => !item.layoutConfirmed), '压占通道时不应确认成功')
  await secondPanel.getByRole('button', { name: '移除纵向通道', exact: true }).click()
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || []).length === 1
  })

  // 7. 横向通道分割桌排：应用后没有家具跨过横向通道带。
  await page.getByTestId('add-channel-horizontal').click()
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || []).length === 2
  })
  // 撤销恢复通道列表，再重新添加。
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || []).length === 1
  })
  await page.getByTestId('add-channel-horizontal').click()
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || []).length === 2
  })
  await page.getByTestId('apply-layout').click()
  await page.waitForFunction(() => {
    const fp = (item) => {
      const radians = (item.rotation || 0) * Math.PI / 180
      const h = Math.abs(item.w * Math.sin(radians)) + Math.abs(item.h * Math.cos(radians))
      return { y: item.y + (item.h - h) / 2, h }
    }
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    const items = draft.objects.filter(object => object.kind === 'furniture' && object.zoneId === 'CHANNEL-ROOM')
    const channels = draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || []
    const horizontal = channels.find(channel => channel.orientation === 'horizontal')
    return horizontal && items.length > 0 && items.every(item => { const rect = fp(item); return !(rect.y < horizontal.offsetMm + horizontal.widthMm && rect.y + rect.h > horizontal.offsetMm) })
  })

  // 8. 刷新后通道与画布条带保持。
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('layout-channels').locator('[data-channel-id]').first().waitFor()
  assert.equal((await page.getByTestId('layout-channels').locator('[data-channel-id]').count()), 2)
  assert.equal((await channelsState()).length, 2)

  // 9. 选中通道后 Delete 移除，撤销恢复。
  const stripFinal = page.getByTestId('layout-channels').locator('[data-channel-id]').first()
  const finalBox = await stripFinal.boundingBox()
  assert.ok(finalBox, '通道条带不可见')
  await page.mouse.click(finalBox.x + finalBox.width / 2, finalBox.y + finalBox.height / 2)
  await page.getByTestId('channel-editor').waitFor()
  await page.keyboard.press('Delete')
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || []).length === 1
  })
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || []).length === 2
  })

  // 11. 端点留断（LAYOUT-028）：纵向通道设起点后条带只覆盖后半跨；刷新保持；起点>终点无有效禁放带（条带消失）；清空恢复贯通。
  const epZone = await zoneState()
  const epMinY = Math.min(...epZone.polygon.map(point => point[1])), epMaxY = Math.max(...epZone.polygon.map(point => point[1]))
  const epSpanY = epMaxY - epMinY
  const epChannelId = (await channelsState()).find(item => item.orientation === 'vertical').id
  const epStrip = page.getByTestId('layout-channels').locator(`[data-channel-id="${epChannelId}"]`)
  const epFullBox = await epStrip.first().boundingBox()
  assert.ok(epFullBox, '通道条带不可见')
  // 避免点在纵/横条带交叠处（上层条带会抢走点击）：取 1/4 高度处。
  await page.mouse.click(epFullBox.x + epFullBox.width / 2, epFullBox.y + epFullBox.height * 0.25)
  await page.getByTestId('channel-editor').waitFor()
  await page.getByLabel('通道属性起点（毫米）').fill(String(Math.round(epSpanY / 2)))
  await page.getByLabel('通道属性起点（毫米）').press('Enter')
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return Number.isFinite((draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || []).find(item => item.orientation === 'vertical')?.startMm)
  })
  const epHalfBox = await epStrip.first().boundingBox()
  assert.ok(epHalfBox && Math.abs(epHalfBox.height - epFullBox.height / 2) < epFullBox.height * 0.06, `设起点后条带高度应约为一半: ${epHalfBox?.height} vs ${epFullBox.height}`)
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('layout-channels').locator(`[data-channel-id="${epChannelId}"]`).first().waitFor()
  const epPersisted = (await channelsState()).find(item => item.orientation === 'vertical')
  assert.ok(Number.isFinite(epPersisted.startMm), '刷新后通道起点应保持')
  const epReloadBox = await epStrip.first().boundingBox()
  assert.ok(epReloadBox && Math.abs(epReloadBox.height - epFullBox.height / 2) < epFullBox.height * 0.06, '刷新后条带应保持留断跨度')
  // 起点 > 终点：无有效禁放带，条带消失（validateChannels 同步给出问题文案，单测覆盖）。
  await page.getByTestId('advanced-options').evaluate(node => { node.open = true }); await page.getByTestId('channel-panel').evaluate(node => { node.open = true })
  await page.getByTestId('channel-vertical').getByLabel('通道终点（毫米）').fill(String(Math.round(epSpanY * 0.1)))
  await page.getByTestId('channel-vertical').getByLabel('通道终点（毫米）').press('Enter')
  await page.waitForFunction((id) => !document.querySelector(`[data-channel-id="${id}"]`), epChannelId)
  // 清空起终点恢复贯通。
  await page.getByTestId('channel-vertical').getByLabel('通道起点（毫米）').fill('')
  await page.getByTestId('channel-vertical').getByLabel('通道起点（毫米）').press('Enter')
  await page.getByTestId('channel-vertical').getByLabel('通道终点（毫米）').fill('')
  await page.getByTestId('channel-vertical').getByLabel('通道终点（毫米）').press('Enter')
  await page.waitForFunction((id) => document.querySelectorAll(`[data-channel-id="${id}"]`).length === 1, epChannelId)
  const epRestoredBox = await epStrip.first().boundingBox()
  assert.ok(epRestoredBox && Math.abs(epRestoredBox.height - epFullBox.height) < epFullBox.height * 0.03, '清空起终点后条带应恢复全跨')

  // 10. U 形空间（LAYOUT-027）：通道按多边形裁剪——缺角内的纵向通道截断到缺口下方、不画出缺角；
  // 缺口高度上的横向通道断成左右两段。
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    const zone = draft.objects.find(object => object.id === 'CHANNEL-ROOM')
    const xs = zone.polygon.map(point => point[0]), ys = zone.polygon.map(point => point[1])
    const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys)
    const x1 = minX + (maxX - minX) * 0.375, x2 = minX + (maxX - minX) * 0.625, midY = minY + (maxY - minY) * 0.4
    zone.polygon = [[minX, minY], [x1, minY], [x1, midY], [x2, midY], [x2, minY], [maxX, minY], [maxX, maxY], [minX, maxY]]
    zone.layoutPreferences.channels = []
    localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
  })
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('unit-module-catalog').waitFor()
  await page.getByTestId('advanced-options').evaluate(node => { node.open = true }); await page.getByTestId('channel-panel').evaluate(node => { node.open = true })
  // 纵向通道默认在包围盒中轴（x 落入缺口范围）：应只剩缺口下方一段，高度 ≈ 0.6 倍空间进深。
  await page.getByTestId('add-channel-vertical').click()
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || []).length === 1
  })
  const uZone = await zoneState()
  const uMinY = Math.min(...uZone.polygon.map(point => point[1])), uMaxY = Math.max(...uZone.polygon.map(point => point[1]))
  const uVerticalId = (await channelsState()).find(item => item.orientation === 'vertical').id
  const uVerticalSegs = page.getByTestId('layout-channels').locator(`[data-channel-id="${uVerticalId}"]`)
  assert.equal(await uVerticalSegs.count(), 1, '缺口内的纵向通道应裁剪为单段')
  // 横向通道默认在包围盒中轴（缺口之下，全宽一段）；改位置到缺口高度后断成左右两段。
  await page.getByTestId('add-channel-horizontal').click()
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return (draft.objects.find(object => object.id === 'CHANNEL-ROOM')?.layoutPreferences?.channels || []).length === 2
  })
  const uHorizontalId = (await channelsState()).find(item => item.orientation === 'horizontal').id
  const uHorizontalSegs = page.getByTestId('layout-channels').locator(`[data-channel-id="${uHorizontalId}"]`)
  assert.equal(await uHorizontalSegs.count(), 1, '缺口之下的横向通道应为全宽单段')
  const hFullBox = await uHorizontalSegs.first().boundingBox()
  const vSegBox = await uVerticalSegs.first().boundingBox()
  assert.ok(hFullBox && vSegBox, '通道分段不可见')
  // 同一 zoom：纵向分段高度(0.6 进深) ≈ 横向全宽分段宽度(全宽) × 0.6 × (进深/宽度)。
  const uSpanX = Math.max(...uZone.polygon.map(point => point[0])) - Math.min(...uZone.polygon.map(point => point[0]))
  const expectedRatio = 0.6 * (uMaxY - uMinY) / uSpanX
  assert.ok(Math.abs(vSegBox.height / hFullBox.width - expectedRatio) < 0.05, `纵向通道应按缺口截断: ${vSegBox.height}/${hFullBox.width} ≉ ${expectedRatio}`)
  // 把横向通道位置改到缺口高度（距上 20% 处），应断成左右两段且互不相交。
  await page.mouse.click(hFullBox.x + hFullBox.width / 2, hFullBox.y + hFullBox.height / 2)
  await page.getByTestId('channel-editor').waitFor()
  await page.getByLabel('通道属性位置（毫米）').fill(String(Math.round((uMaxY - uMinY) * 0.2)))
  await page.getByLabel('通道属性位置（毫米）').press('Enter')
  await page.waitForFunction((id) => document.querySelectorAll(`[data-channel-id="${id}"]`).length === 2, uHorizontalId)
  const segBoxes = await uHorizontalSegs.evaluateAll(nodes => nodes.map(node => { const box = node.getBoundingClientRect(); return { x: box.x, right: box.x + box.width } }))
  const leftSeg = segBoxes.reduce((a, b) => a.x < b.x ? a : b), rightSeg = segBoxes.reduce((a, b) => a.x > b.x ? a : b)
  assert.ok(leftSeg.right < rightSeg.x, '缺口高度的横向通道应在缺角处断开为两段')

  // 12. 通道-入口/疏散联动提示（LAYOUT-029，提示级不拦截）：未标注门洞净空→未核验提示；
  // 端头衔接门洞→无提示；衔接走廊被禁放区贯穿→截断提示；门远离通道→未衔接提示。
  const setupEgress = async (mode) => {
    await page.evaluate((m) => {
      const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
      const zone = draft.objects.find(object => object.id === 'CHANNEL-ROOM')
      const xs = zone.polygon.map(point => point[0]), ys = zone.polygon.map(point => point[1])
      const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys)
      zone.polygon = [[minX, minY], [maxX, minY], [maxX, maxY], [minX, maxY]]
      const cx = Math.round(minX + (maxX - minX) / 2)
      zone.layoutPreferences.channels = [{ id: 'CH-EGRESS', orientation: 'vertical', offsetMm: cx, widthMm: 900 }]
      zone.layoutPreferences.exclusions = []
      zone.obstacles = []
      if (m !== 'unmarked') {
        // 门洞净空与 obstacles.ts 的 doorClearanceObstacle 同构（存储坐标 Y 向上）。
        const hingeX = m === 'far' ? minX + 100 : cx + 400
        const hinge = [hingeX, -maxY], radius = 900
        const outer = [hinge]
        for (let index = 0; index <= 16; index++) outer.push([hinge[0] + radius * Math.cos(index * Math.PI / 32), hinge[1] + radius * Math.sin(index * Math.PI / 32)])
        zone.obstacles = [{ obstacleId: 'DOOR-EGRESS', kind: 'DOOR_CLEARANCE', spaceId: zone.id, geometry: { type: 'POLYGON', coordinateSystem: 'WORLD_MM', outer, outerVertexIds: outer.map((_, i) => 'DOOR-EGRESS-V-' + i), holes: [], holeVertexIds: [] }, excludesFloorArea: false, source: 'MANUAL' }]
        if (m === 'blocked') zone.layoutPreferences.exclusions = [{ id: 'EX-EGRESS', label: '禁放', x: cx + 500, y: maxY - 1000, w: 100, h: 1100 }]
      }
      localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
    }, mode)
    await page.reload({ waitUntil: 'networkidle' })
    await page.getByTestId('unit-module-catalog').waitFor()
    await page.getByTestId('advanced-options').evaluate(node => { node.open = true }); await page.getByTestId('channel-panel').evaluate(node => { node.open = true })
    return page.locator('[data-testid="channel-egress-hint"]')
  }
  let egressHints = await setupEgress('unmarked')
  assert.equal(await egressHints.count(), 1, '未标注门洞净空应给出未核验提示')
  assert.match(await egressHints.first().textContent(), /未核验/)
  egressHints = await setupEgress('linked')
  assert.equal(await egressHints.count(), 0, '通道端头衔接门洞后不应有提示')
  egressHints = await setupEgress('blocked')
  assert.equal(await egressHints.count(), 1, '衔接走廊被贯穿应给出截断提示')
  assert.match(await egressHints.first().textContent(), /净宽不足/)
  egressHints = await setupEgress('far')
  assert.equal(await egressHints.count(), 1, '门远离通道应给出未衔接提示')
  assert.match(await egressHints.first().textContent(), /未衔接/)

  // 12. 确认排版阻塞的具体原因：目标人数不足 / 家具压占通道分别给出明确文案，解除后可确认。
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    const zone = draft.objects.find(object => object.id === 'CHANNEL-ROOM')
    zone.layoutPreferences.channels = []
    zone.layoutPreferences.targetSeats = 0
    draft.objects = draft.objects.filter(object => object.kind !== 'furniture')
    localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
  })
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('unit-module-catalog').waitFor()
  await page.getByTestId('apply-layout').click()
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return draft.objects.some(object => object.kind === 'furniture' && object.zoneId === 'CHANNEL-ROOM')
  })
  const seatCount = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).objects.filter(object => object.kind === 'furniture' && object.zoneId === 'CHANNEL-ROOM' && (object.furnitureTemplateId || '').startsWith('desk-')).length)
  // 目标人数超出实际座位：提示具体差距，点击确认也显示同一文案且不生效
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const zone = store.zones.find(item => item.id === 'CHANNEL-ROOM')
    zone.layoutPreferences = { ...zone.layoutPreferences, targetSeats: 500 }
    store.schedulePersist()
  })
  await page.getByTestId('confirm-problem').waitFor()
  assert.match(await page.getByTestId('confirm-problem').innerText(), new RegExp(`目标 500 人，当前仅 ${seatCount} 座`), '目标人数不足给出具体座数')
  await page.getByRole('button', { name: '确认当前排版', exact: true }).click()
  assert.match(await page.getByTestId('confirm-problem').innerText(), /目标 500 人/)
  assert.ok(!(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).objects.filter(object => object.kind === 'furniture').every(object => object.layoutConfirmed))), '目标不足时不得确认')
  // 家具压占通道：通道移到已放置家具上
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const zone = store.zones.find(item => item.id === 'CHANNEL-ROOM')
    zone.layoutPreferences = { ...zone.layoutPreferences, targetSeats: 0 }
    const desk = store.objects.find(object => object.kind === 'furniture' && object.zoneId === 'CHANNEL-ROOM' && object.layoutRole !== 'seat')
    store.updateZoneChannels('CHANNEL-ROOM', [{ id: 'CH-CONFIRM', orientation: 'vertical', offsetMm: desk.x + 10, widthMm: 900, clearanceMm: 0 }])
  })
  await page.getByTestId('confirm-problem').waitFor()
  assert.match(await page.getByTestId('confirm-problem').innerText(), /压占通道/, '压占通道给出明确文案')
  // 解除通道后可正常确认
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.updateZoneChannels('CHANNEL-ROOM', [])
  })
  await page.waitForFunction(() => !document.querySelector('[data-testid="confirm-problem"]'))
  await page.getByRole('button', { name: '确认当前排版', exact: true }).click()
  await page.waitForFunction(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    return draft.objects.filter(object => object.kind === 'furniture').every(object => object.layoutConfirmed)
  })

  assert.deepEqual(errors, [], '页面运行时不应有未捕获异常')
  console.log('e2e-aisle-channels: 全部断言通过')
} finally {
  await browser?.close()
  app.kill()
}
