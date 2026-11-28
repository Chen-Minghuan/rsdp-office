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
const app = spawn(process.execPath, ['.output/server/index.mjs'], { cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' }, stdio: 'ignore' })
let browser
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先运行 pnpm build')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1750, height: 1150 } })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  const project = {
    projectId: 'OFFICE-PROJECT-001', name: '家具多选测试',
    leader: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'LEADER', permissions: ['EDIT'] }, collaborators: [], referenceImages: [],
    floors: [{ projectId: 'OFFICE-PROJECT-001', floorId: 'OFFICE-PROJECT-001', name: '默认楼层', sortOrder: 1 }],
  }
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    return route.fulfill({ json: { code: 200, data: path === '/api/v1/office/projects' ? [project] : null } })
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')))
  const items = async () => (await state()).objects.filter(item => item.kind === 'furniture')
  const canvas = page.getByTestId('office-design-canvas')
  const item = id => canvas.locator(`[data-id="${id}"]`)
  const dragBy = async (locator, dx, dy, button = 'left') => {
    const box = await locator.boundingBox()
    const x = box.x + box.width / 2, y = box.y + box.height / 2
    await page.mouse.move(x, y)
    await page.mouse.down({ button })
    await page.mouse.move(x + dx, y + dy, { steps: 5 })
    await page.mouse.up({ button })
  }
  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.getByRole('img', { name: '空间核对画布', exact: true }).waitFor()
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')) || {}
    const zone = (id, x) => ({ id, kind: 'zone', x, y: 0, w: 6000, h: 4000, name: id, zoneType: 'OPEN_OFFICE', seats: 0, note: '', tone: 'blue', reviewUnit: 'mm', reviewConfirmation: { status: 'CONFIRMED' }, evidenceKind: 'MANUAL', geometrySource: 'manual_geometry', polygon: [[x,0],[x+6000,0],[x+6000,4000],[x,4000]] })
    const furniture = (id, zoneId, x, y) => ({ id, kind: 'furniture', zoneId, furnitureTemplateId: 'desk-straight', productId: '', label: id, x, y, w: 800, h: 500, rotation: 0, layoutConfirmed: true })
    draft.objects = [zone('ROOM', 0), zone('OTHER', 10000), furniture('A', 'ROOM', 500, 1000), furniture('B', 'ROOM', 2000, 1000), furniture('C', 'ROOM', 4000, 1000), furniture('D', 'OTHER', 11000, 1000), furniture('E', 'ROOM', 5200, 1000)]
    draft.floorPlan = null
    draft.spaceTemplate = null
    draft.canvasMode = 'overview'
    draft.designZoneId = null
    localStorage.setItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001', JSON.stringify(draft))
  })
  await page.goto(baseUrl + '/canvas?mode=design&zoneId=ROOM', { waitUntil: 'networkidle' })
  await item('A').click()
  await item('B').click({ modifiers: ['Shift'] })
  await page.getByText('已选 2 件模块').waitFor()
  const initial = await items()
  const startRevision = (await state()).canvasRevision
  const redo = page.getByRole('button', { name: '恢复', exact: true })
  assert.ok(await redo.isDisabled())
  assert.equal(await page.getByRole('button', { name: '拖动', exact: true }).locator('small').innerText(), '空格＋左拖 · 中键')

  const assertViewShift = async (before, dx, dy) => {
    const after = await item('A').boundingBox()
    assert.ok(Math.abs(after.x - before.x - dx) < 1 && Math.abs(after.y - before.y - dy) < 1)
    assert.deepEqual(await items(), initial, '平移不改变家具的位置或角度')
    assert.equal((await state()).canvasRevision, startRevision)
    assert.ok(await page.getByText('已选 2 件模块').isVisible(), '平移保留原来的选择')
  }
  const beforeSpacePan = await item('A').boundingBox()
  await page.keyboard.down('Space')
  await dragBy(item('A'), 38, 25)
  await page.keyboard.up('Space')
  await assertViewShift(beforeSpacePan, 38, 25)
  const beforeMiddle = await item('A').boundingBox()
  await dragBy(item('A'), -18, -11, 'middle')
  await assertViewShift(beforeMiddle, -18, -11)

  const fit = page.getByRole('button', { name: '适屏', exact: true })
  await fit.hover()
  const outsideSpaceProceeds = await canvas.evaluate(el => el.dispatchEvent(new KeyboardEvent('keydown', {
    key: ' ', code: 'Space', bubbles: true, cancelable: true,
  })))
  assert.ok(outsideSpaceProceeds, '指针在画布外时不拦截空格')
  await fit.click()
  await dragBy(item('A'), 20, 0)
  const moved = await items()
  const deltaA = moved.find(f => f.id === 'A').x - initial.find(f => f.id === 'A').x
  const deltaB = moved.find(f => f.id === 'B').x - initial.find(f => f.id === 'B').x
  assert.ok(deltaA > 0 && deltaA === deltaB, '两件家具保持相同位移')
  assert.equal(moved.find(f => f.id === 'C').x, initial.find(f => f.id === 'C').x)
  assert.equal(moved.find(f => f.id === 'D').x, initial.find(f => f.id === 'D').x)
  assert.equal(moved.filter(f => f.zoneId === 'ROOM').every(f => f.layoutConfirmed === false), true)
  assert.equal(moved.find(f => f.id === 'D').layoutConfirmed, true)
  await dragBy(item('A'), 400, 0)
  assert.deepEqual(await items(), moved, '碰撞时整组不写入')
  await dragBy(item('A'), -900, 0)
  assert.deepEqual(await items(), moved, '越界时整组不写入')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual(await items(), initial, '整组移动只产生一次撤销记录')
  await redo.click()
  assert.deepEqual(await items(), moved, '恢复整组移动及其排版状态')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual(await items(), initial, '恢复后可以再次撤销')
  await page.reload({ waitUntil: 'networkidle' })
  assert.deepEqual(await items(), initial, '刷新保持撤销结果')
  const aBox = await item('A').boundingBox(), bBox = await item('B').boundingBox()
  await page.mouse.move(aBox.x - 12, aBox.y - 12)
  await page.mouse.down()
  await page.mouse.move(bBox.x + bBox.width + 12, bBox.y + bBox.height + 12, { steps: 5 })
  await page.mouse.up()
  await page.getByText('已选 2 件模块').waitFor()
  await page.keyboard.press('Escape')
  await item('A').click()
  await item('B').click({ modifiers: ['Control'] })
  await page.keyboard.press('Delete')
  assert.deepEqual((await items()).filter(f => f.zoneId === 'ROOM').map(f => f.id).sort(), ['C', 'E'])
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual(await items(), initial, '批量删除也只产生一次撤销记录')
  await item('A').click()
  await item('B').click({ modifiers: ['Shift'] })
  await dragBy(item('A'), 15, 0)
  const persistedMove = await items()
  assert.notEqual(persistedMove.find(f => f.id === 'A').x, initial.find(f => f.id === 'A').x)
  await page.reload({ waitUntil: 'networkidle' })
  assert.deepEqual(await items(), persistedMove, '整组移动刷新后保持')
  // Rotate and mirror operate on the selection: applied, undoable, rejected when invalid, reload-persistent.
  await item('C').click()
  await page.getByTestId('rotate-selection').click()
  assert.equal((await items()).find(f => f.id === 'C').rotation, 90)
  assert.equal((await items()).find(f => f.id === 'C').layoutConfirmed, false)
  await page.getByTestId('mirror-selection').click()
  assert.equal((await items()).find(f => f.id === 'C').mirrorX, true)
  await page.getByTestId('office-design-canvas').focus()
  await page.keyboard.press('Space')
  assert.equal((await items()).find(f => f.id === 'C').rotation, 180)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.equal((await items()).find(f => f.id === 'C').rotation, 90)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.equal((await items()).find(f => f.id === 'C').mirrorX, undefined)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.equal((await items()).find(f => f.id === 'C').rotation, 0)
  await item('E').click()
  await page.keyboard.press('Space')
  await page.keyboard.press('Space')
  await page.keyboard.press('Space')
  assert.equal((await items()).find(f => f.id === 'E').rotation, 270, '座椅侧净空不足已降级为提示，不再拦截旋转（2026-10-26 定稿）')
  await page.reload({ waitUntil: 'networkidle' })
  assert.equal((await items()).find(f => f.id === 'E').rotation, 270)
  // Arbitrary-angle rotation: typing only previews; Enter, switching selection or clicking canvas applies; zero angle is rejected.
  await item('C').click()
  const rotateInput = page.getByLabel('家具旋转角度（度，顺时针为正）')
  await rotateInput.fill('45')
  assert.equal((await items()).find(f => f.id === 'C').rotation, 0, '输入角度只触发预览，不落库')
  await rotateInput.press('Enter')
  assert.equal((await items()).find(f => f.id === 'C').rotation, 45, 'Enter 应用输入角度')
  assert.equal((await items()).find(f => f.id === 'C').layoutConfirmed, false)
  assert.equal(await page.getByTestId('rotate-angle-form').count(), 0, 'Enter 应用后取消选中，操作条收起')
  await item('C').click()
  await rotateInput.fill('30')
  await item('E').click()
  assert.equal((await items()).find(f => f.id === 'C').rotation, 75, '切换选中时自动应用待提交的旋转')
  await item('C').click()
  await rotateInput.fill('-75')
  await page.getByTestId('office-design-canvas').click({ position: { x: 30, y: 30 } })
  assert.equal((await items()).find(f => f.id === 'C').rotation, 0, '点击画布自动应用，累计回到 0°')
  await item('C').click()
  await rotateInput.fill('0')
  await rotateInput.press('Enter')
  assert.equal((await items()).find(f => f.id === 'C').rotation, 0, '零角度不生效')
  assert.equal(await page.getByTestId('rotate-commit-problem').innerText(), '请输入非零旋转角度', '零角度提示原因且保留选中')
  // Out-of-bounds rotation is rejected: rotation unchanged, selection kept, reason shown inline.
  await item('E').click()
  await rotateInput.fill('45')
  await rotateInput.press('Enter')
  assert.equal((await items()).find(f => f.id === 'E').rotation, 270, '越界旋转被拒绝，角度保持不变')
  assert.equal(await page.getByTestId('rotate-angle-form').count(), 1, '校验失败保留选中与操作条')
  assert.equal(await rotateInput.inputValue(), '', '拒绝旋转后清除未应用的角度')
  assert.equal(await page.getByTestId('rotate-commit-problem').innerText(), '此位置超出空间边界', '画布显示失败原因')
  await rotateInput.press('Escape')
  assert.equal(await page.getByTestId('rotate-commit-problem').count(), 0, 'Esc 清除错误提示')
  await item('E').click()
  await rotateInput.fill('45')
  await canvas.click({ position: { x: 30, y: 30 } })
  assert.equal((await items()).find(f => f.id === 'E').rotation, 270, '点击空白处提交越界旋转也不保存')
  assert.equal(await page.getByTestId('rotate-angle-form').count(), 0, '点击空白处取消选中')
  assert.equal(await page.getByTestId('rotate-commit-problem').innerText(), '此位置超出空间边界', '取消选中后仍可看到旋转失败原因')
  await item('E').click()
  assert.equal(await rotateInput.inputValue(), '', '重新选中时不显示失败的旋转角度')
  await rotateInput.press('Escape')
  // Manual arranging snaps to walls and other modules within 50mm; the toolbar toggle disables it.
  const zoom = await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').zoom)
  const beforeSnap = await items()
  const bRight = beforeSnap.find(f => f.id === 'B').x + beforeSnap.find(f => f.id === 'B').w
  await dragBy(item('C'), (bRight + 30 - 4000) * zoom, 0)
  assert.ok(Math.abs((await items()).find(f => f.id === 'C').x - bRight) < 0.01, '靠近其他模块 30mm 吸附为贴合')
  await dragBy(item('C'), 0, (30 - 1000) * zoom)
  assert.ok(Math.abs((await items()).find(f => f.id === 'C').y) < 0.01, '靠近墙面 30mm 吸附为贴墙')
  await page.getByRole('button', { name: '家具吸附', exact: true }).click()
  await dragBy(item('C'), 30 * zoom, 40 * zoom)
  const freeC = (await items()).find(f => f.id === 'C')
  assert.ok(Math.abs(freeC.x - (bRight + 30)) < 1 && Math.abs(freeC.y - 40) < 1, '关闭吸附后按原始位移移动')
  await page.getByRole('button', { name: '家具吸附', exact: true }).click()
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual(await items(), beforeSnap, '吸附移动每次各产生一次撤销记录')
  assert.deepEqual(errors, [])
  console.log('家具多选 UI 通过：Shift/Ctrl 多选、框选、整组移动、旋转 90°/水平镜像（含拒绝与撤销）、任意角度旋转（预览、Enter/切换选中/点画布自动应用、零角度拒绝）、碰撞/越界拒绝、一次撤销、刷新、批量删除与其他空间隔离；API 模拟。')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
