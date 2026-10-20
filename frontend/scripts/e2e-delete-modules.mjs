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
const outDir = process.env.OUT_DIR || '/tmp/rsdp-delete-modules'
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
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : [] }) })
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  await page.goto(baseUrl + '/canvas', { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '无限画布 · 空间核对', exact: true }).waitFor()
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01')) || {}
    draft.objects = [
      { id: 'DELETE-ROOM', kind: 'zone', x: -10000, y: -6000, w: 6000, h: 4000, name: '删除测试空间', zoneType: 'OPEN_OFFICE', seats: 0, note: '', tone: 'blue', reviewUnit: 'mm', evidenceKind: 'MANUAL', geometrySource: 'manual_geometry', polygon: [[-10000,-6000],[-4000,-6000],[-4000,-2000],[-10000,-2000]] },
      { id: 'OTHER-ROOM', kind: 'zone', x: 10000, y: 0, w: 6000, h: 4000, name: '其他空间', zoneType: 'MEETING_ROOM', seats: 0, note: '', tone: 'blue', reviewUnit: 'mm', evidenceKind: 'MANUAL', geometrySource: 'manual_geometry', polygon: [[10000,0],[16000,0],[16000,4000],[10000,4000]] },
      ...['DELETE-A','DELETE-B'].map((id,index) => ({ id, kind: 'furniture', zoneId: 'DELETE-ROOM', furnitureTemplateId: 'desk-straight', productId: '', label: id, x: -9400 + index * 2500, y: -5000, w: 1200, h: 600, rotation: 0, layoutConfirmed: true })),
      { id: 'KEEP-OTHER', kind: 'furniture', zoneId: 'OTHER-ROOM', furnitureTemplateId: 'chair-office', productId: '', x: 11000, y: 1000, w: 650, h: 650, rotation: 0, layoutConfirmed: true },
    ]
    draft.floorPlan = null
    draft.spaceTemplate = null
    draft.canvasMode = 'overview'
    draft.designZoneId = null
    localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
  })
  await page.goto(baseUrl + '/canvas?mode=design&zoneId=DELETE-ROOM', { waitUntil: 'networkidle' })
  await page.getByTestId('office-design-canvas').waitFor()
  const initial = await state()
  const furniture = async () => (await state()).objects.filter(item => item.kind === 'furniture')
  const choose = id => page.getByTestId('office-design-canvas').locator('[data-id="' + id + '"]').click()
  await choose('DELETE-A')
  await page.getByRole('button', { name: '删除选中模块', exact: true }).waitFor()
  await page.screenshot({ path: outDir + '/01-delete-selected.png', animations: 'disabled' })
  await page.getByRole('button', { name: '删除选中模块', exact: true }).click()
  const removed = await furniture()
  assert.equal(removed.some(item => item.id === 'DELETE-A'), false)
  assert.equal(removed.find(item => item.id === 'DELETE-B').layoutConfirmed, false)
  assert.deepEqual(removed.find(item => item.id === 'KEEP-OTHER'), initial.objects.find(item => item.id === 'KEEP-OTHER'))
  assert.equal(await page.getByTestId('current-furniture-count').innerText(), '1 件')
  assert.equal(await page.getByRole('button', { name: '删除选中模块', exact: true }).count(), 0)
  await page.getByRole('status').filter({ hasText: '已删除模块' }).waitFor()
  await page.getByRole('button', { name: '确认当前排版', exact: true }).waitFor()
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual(await furniture(), initial.objects.filter(item => item.kind === 'furniture'))
  assert.equal(await page.getByRole('status').filter({ hasText: '已删除模块' }).count(), 0)
  await choose('DELETE-A')
  await page.keyboard.press('Delete')
  assert.equal((await furniture()).some(item => item.id === 'DELETE-A'), false)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await choose('DELETE-A')
  // Focused fields and dialog controls must never send deletion through to the canvas.
  await page.getByRole('tab', { name: '调整参数', exact: true }).click()
  await page.getByLabel('模块宽度（毫米）').focus()
  await page.keyboard.press('Backspace')
  assert.equal((await furniture()).length, 3)
  await page.getByRole('tab', { name: '选择模块', exact: true }).click()
  await page.getByRole('button', { name: /新增单位模块/ }).click()
  const dialog = page.getByRole('dialog', { name: '新增单位模块', exact: true })
  await dialog.getByRole('button', { name: '保存模块', exact: true }).focus()
  await page.keyboard.press('Delete')
  assert.equal((await furniture()).length, 3)
  await dialog.getByLabel('自定义模块名称').fill('待删除模块')
  await dialog.getByLabel('自定义模块宽度（毫米）').fill('1200')
  await dialog.getByLabel('自定义模块进深（毫米）').fill('600')
  await dialog.getByRole('button', { name: '保存模块', exact: true }).click()
  const custom = (await state()).customModules[0]
  assert.ok(custom)
  await page.getByRole('button', { name: '删除目录模块 待删除模块', exact: true }).click()
  assert.equal((await state()).customModules.length, 0)
  assert.equal((await furniture()).length, 3, '删除目录不能删除已放置实例')
  await page.getByRole('button', { name: '撤销删除目录模块', exact: true }).click()
  assert.deepEqual((await state()).customModules, [custom])
  await choose('DELETE-A')
  await page.keyboard.press('Backspace')
  assert.equal((await furniture()).some(item => item.id === 'DELETE-A'), false)
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('office-design-canvas').waitFor()
  assert.equal((await furniture()).some(item => item.id === 'DELETE-A'), false, '刷新后删除结果保持')
  assert.deepEqual((await state()).customModules, [custom])
  assert.equal((await furniture()).find(item => item.id === 'KEEP-OTHER').layoutConfirmed, true)
  await choose('DELETE-B')
  await page.getByRole('button', { name: '删除选中模块', exact: true }).click()
  assert.equal(await page.getByTestId('current-furniture-count').innerText(), '0 件')
  assert.equal(await page.getByRole('button', { name: '确认当前排版', exact: true }).count(), 0)
  assert.equal((await state()).canvasMode, 'design')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.equal((await furniture()).filter(item => item.zoneId === 'DELETE-ROOM').length, 1)
  assert.deepEqual((await state()).objects.filter(item => item.kind === 'zone'), initial.objects.filter(item => item.kind === 'zone'))
  assert.deepEqual(errors, [])
  console.log('删除模块 UI 通过：选中按钮/Delete/Backspace、当前空间确认失效、其他空间/目录保留、输入和弹窗防误删、撤销/刷新/最后一件及目录删除/独立撤销。全部 API 模拟。')
} catch (failure) {
  await debugPage?.screenshot({ path: outDir + '/failure.png' }).catch(() => {})
  throw failure
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
