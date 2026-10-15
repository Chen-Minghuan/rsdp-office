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
const outDir = process.env.OUT_DIR || '/tmp/rsdp-auto-layout'
let browser, debugPage
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先 pnpm build；预览服务未就绪')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  await mkdir(outDir, { recursive: true })
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1800, height: 1200 } })
  const page = await context.newPage(), errors = []
  debugPage = page
  page.on('pageerror', error => errors.push(error.message))
  const source = {
    analysisId: 'AUTO-TEST', analysisRevision: 1, status: 'awaiting_confirm', sourceName: '自动多排测试.dxf',
    inputMode: 'cad', geometrySource: 'cad_geometry', evidenceKind: 'REAL_SERVICE',
    unitResolution: { canonicalUnit: 'mm', confirmed: true },
    rooms: [
      { roomId: 'AUTO-ROOM', label: '开放办公区', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[0,0],[4358,0],[4358,4376],[0,4376]] },
      { roomId: 'OTHER-ROOM', label: '其他空间', roomType: 'MEETING_ROOM', geometrySource: 'cad_geometry', polygon: [[10000,0],[16000,0],[16000,4000],[10000,4000]] },
    ],
  }
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    const data = path.endsWith('/floor-plan/AUTO-TEST') ? source : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  const furniture = async () => (await state()).objects.filter(object => object.kind === 'furniture' && object.zoneId === 'AUTO-ROOM')
  const apply = () => page.getByRole('button', { name: '一键自动排版', exact: true }).click()
  const capacity = () => page.getByTestId('module-capacity').innerText()
  await page.goto(baseUrl + '/canvas?analysisId=AUTO-TEST', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '选择空间 开放办公区', exact: true }).waitFor()
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01'))
    const room = draft.objects.find(object => object.id === 'AUTO-ROOM')
    for (let i = 0; i < 3; i++) draft.objects.push({ id: 'OLD-' + i, kind: 'furniture', furnitureTemplateId: 'desk-straight', productId: '', zoneId: room.id, x: room.x + 20 + i * 1210, y: room.y + 20, w: 1200, h: 600, rotation: 0, layoutConfirmed: true, layoutSettings: { mode: 'row', spacing: { wallMm: 300, aisleMm: 900, gapMm: 100 } } })
    draft.objects.push({ id: 'KEEP-OTHER', kind: 'furniture', furnitureTemplateId: 'chair-office', productId: '', zoneId: 'OTHER-ROOM', x: 11000, y: -3000, w: 650, h: 650, rotation: 0 })
    localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
  })
  await page.goto(baseUrl + '/canvas?analysisId=AUTO-TEST&mode=design&zoneId=AUTO-ROOM', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '自动优化', exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '单排', exact: true }).getAttribute('aria-pressed'), 'true')
  assert.equal(await capacity(), '2')
  assert.equal(await page.getByTestId('current-furniture-count').innerText(), '3 件')
  assert.equal(await page.getByTestId('auto-layout-capacity').innerText(), '6 件 · 3 排')
  await page.getByTestId('layout-pending').waitFor()
  await page.screenshot({ path: outDir + '/00-restored-single-row.png' })
  assert.equal((await furniture()).length, 3, '预览不能自动替换原有布局')
  const geometry = (await state()).objects.filter(object => object.kind === 'zone')
  await page.setViewportSize({ width: 1440, height: 900 })
  const primaryBox = await page.getByRole('button', { name: '一键自动排版', exact: true }).boundingBox()
  assert.ok(primaryBox.y >= 0 && primaryBox.y + primaryBox.height <= 900, '常见视口下自动排版主按钮始终可见')
  await page.setViewportSize({ width: 1800, height: 1200 })
  await apply()
  const generated = await furniture()
  assert.equal(generated.length, 6)
  assert.equal(await page.getByRole('button', { name: '自动优化', exact: true }).getAttribute('aria-pressed'), 'true')
  assert.equal(await page.getByRole('button', { name: '单排', exact: true }).getAttribute('aria-pressed'), 'false')
  await page.getByRole('status').filter({ hasText: '已应用自动多排：6 件' }).waitFor()
  assert.equal(await page.getByTestId('layout-pending').count(), 0)
  assert.equal(new Set(generated.map(item => item.y)).size, 3)
  const room = geometry.find(object => object.id === 'AUTO-ROOM')
  assert.ok(Math.max(...generated.map(item => item.y + item.h)) > room.y + room.h * 0.8)
  assert.ok(generated.every(item => item.w === 1200 && item.h === 600 && item.layoutConfirmed === false && item.layoutSettings.mode === 'auto'))
  await page.getByTestId('layout-row-summary').filter({ hasText: '3 排' }).waitFor()
  assert.equal(await page.getByTestId('office-design-canvas').locator('polygon.board-element').count(), 1)
  await page.screenshot({ path: outDir + '/01-auto-multi-row.png', animations: 'disabled' })
  const first = await page.getByTestId('office-design-canvas').locator('[data-id="' + generated[0].id + '"]').boundingBox()
  await page.mouse.move(first.x + first.width / 2, first.y + first.height / 2)
  await page.mouse.down()
  await page.mouse.move(first.x + first.width / 2 + 12, first.y + first.height / 2 + 12, { steps: 4 })
  await page.mouse.up()
  assert.equal((await furniture()).length, 6)
  await page.getByTestId('layout-pending').waitFor()
  assert.equal(await page.getByRole('status').filter({ hasText: '已应用自动多排' }).count(), 0, '家具位置改变后旧成功提示失效')
  await apply()
  assert.equal(await page.getByTestId('layout-pending').count(), 0)
  await page.getByRole('button', { name: '确认当前排版', exact: true }).click()
  await page.getByText('排版已确认', { exact: true }).waitFor()
  await page.getByText('间距设置（毫米）', { exact: true }).click()
  await page.getByLabel('排间净距（毫米）').fill('1600')
  assert.equal(await capacity(), '4')
  assert.equal((await furniture()).length, 6, '更改参数只更新预估，点击后才替换家具')
  await apply()
  const spaced = await furniture()
  assert.equal(spaced.length, 4)
  assert.ok(spaced.every(item => !item.layoutConfirmed && item.layoutSettings.spacing.aisleMm === 1600))
  const ys = [...new Set(spaced.map(item => item.y))].sort((a,b) => a-b)
  assert.ok(ys[1] - ys[0] - 600 >= 1600)
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('office-design-canvas').waitFor()
  assert.deepEqual(await furniture(), spaced)
  assert.equal(await capacity(), '4')
  await page.getByText('间距设置（毫米）', { exact: true }).click()
  assert.equal(await page.getByLabel('排间净距（毫米）').inputValue(), '1600')
  await page.getByLabel('排间净距（毫米）').fill('-1')
  assert.ok(await page.getByRole('button', { name: '一键自动排版', exact: true }).isDisabled())
  assert.deepEqual(await furniture(), spaced)
  await page.getByLabel('排间净距（毫米）').fill('900')
  await apply()
  assert.equal((await furniture()).length, 6)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual(await furniture(), spaced)
  assert.equal(await page.getByLabel('排间净距（毫米）').inputValue(), '1600', '撤销同时恢复参数')
  await page.getByRole('button', { name: '单排', exact: true }).click()
  await page.getByRole('button', { name: '应用单排方案', exact: true }).click()
  assert.equal((await furniture()).length, 2)
  assert.equal(new Set((await furniture()).map(item => item.y)).size, 1)
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('office-design-canvas').waitFor()
  assert.equal(await page.getByRole('button', { name: '单排', exact: true }).getAttribute('aria-pressed'), 'true')
  await apply()
  assert.equal((await furniture()).length, 4, '恢复单排后主入口直接按自动多排执行，保留 1600mm 净距')
  await page.getByText('间距设置（毫米）', { exact: true }).click()
  await page.getByLabel('排间净距（毫米）').fill('900')
  await page.getByLabel('离墙距离（毫米）').fill('1000')
  assert.ok(Number(await capacity()) < 6)
  await page.getByLabel('离墙距离（毫米）').fill('300')
  await page.getByLabel('同排间距（毫米）').fill('2000')
  assert.ok(Number(await capacity()) < 6)
  await page.getByLabel('同排间距（毫米）').fill('100')
  await apply()
  await page.screenshot({ path: outDir + '/02-spacing-settings.png' })
  assert.deepEqual((await state()).objects.filter(object => object.kind === 'zone'), geometry)
  assert.equal((await state()).objects.find(object => object.id === 'KEEP-OTHER').zoneId, 'OTHER-ROOM')
  assert.deepEqual(errors, [])
  console.log('自动排版入口 UI 通过：恢复旧单排/3件后主按钮直接自动6件3排、当前/预估/未应用提示、同数量拖动识别、成功反馈失效、手动方案独立应用、刷新/撤销/间距及900高视口主按钮可见。全部 API 模拟。')
} catch (failure) {
  await debugPage?.screenshot({ path: outDir + '/failure.png' }).catch(() => {})
  throw failure
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
