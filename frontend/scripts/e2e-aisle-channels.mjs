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
  await page.getByText('当前家具存在越界、重叠、禁放区冲突或目标不足，请检查后确认').waitFor()
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

  assert.deepEqual(errors, [], '页面运行时不应有未捕获异常')
  console.log('e2e-aisle-channels: 全部断言通过')
} finally {
  await browser?.close()
  app.kill()
}
