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
  await context.route('**/api/v1/**', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: null }) }))
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  const items = async () => (await state()).objects.filter(item => item.kind === 'furniture')
  const item = id => page.getByTestId('office-design-canvas').locator(`[data-id="${id}"]`)
  const dragBy = async (locator, dx, dy) => {
    const box = await locator.boundingBox()
    const x = box.x + box.width / 2, y = box.y + box.height / 2
    await page.mouse.move(x, y)
    await page.mouse.down()
    await page.mouse.move(x + dx, y + dy, { steps: 5 })
    await page.mouse.up()
  }
  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '无限画布 · 空间核对', exact: true }).waitFor()
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01')) || {}
    const zone = (id, x) => ({ id, kind: 'zone', x, y: 0, w: 6000, h: 4000, name: id, zoneType: 'OPEN_OFFICE', seats: 0, note: '', tone: 'blue', reviewUnit: 'mm', evidenceKind: 'MANUAL', geometrySource: 'manual_geometry', polygon: [[x,0],[x+6000,0],[x+6000,4000],[x,4000]] })
    const furniture = (id, zoneId, x, y) => ({ id, kind: 'furniture', zoneId, furnitureTemplateId: 'desk-straight', productId: '', label: id, x, y, w: 800, h: 500, rotation: 0, layoutConfirmed: true })
    draft.objects = [zone('ROOM', 0), zone('OTHER', 10000), furniture('A', 'ROOM', 500, 1000), furniture('B', 'ROOM', 2000, 1000), furniture('C', 'ROOM', 4000, 1000), furniture('D', 'OTHER', 11000, 1000)]
    draft.floorPlan = null
    draft.spaceTemplate = null
    draft.canvasMode = 'overview'
    draft.designZoneId = null
    localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
  })
  await page.goto(baseUrl + '/canvas?mode=design&zoneId=ROOM', { waitUntil: 'networkidle' })
  await item('A').click()
  await item('B').click({ modifiers: ['Shift'] })
  await page.getByText('已选 2 件模块').waitFor()
  const initial = await items()
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
  assert.equal((await items()).filter(f => f.zoneId === 'ROOM').length, 1)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual(await items(), initial, '批量删除也只产生一次撤销记录')
  await item('A').click()
  await item('B').click({ modifiers: ['Shift'] })
  await dragBy(item('A'), 15, 0)
  const persistedMove = await items()
  assert.notEqual(persistedMove.find(f => f.id === 'A').x, initial.find(f => f.id === 'A').x)
  await page.reload({ waitUntil: 'networkidle' })
  assert.deepEqual(await items(), persistedMove, '整组移动刷新后保持')
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
  console.log('家具多选 UI 通过：Shift/Ctrl 多选、框选、整组移动、碰撞/越界拒绝、一次撤销、刷新、批量删除与其他空间隔离；API 模拟。')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
