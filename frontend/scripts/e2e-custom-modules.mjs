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
const outDir = process.env.OUT_DIR || '/tmp/rsdp-custom-modules'
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
  let remoteCanvas = null
  const source = {
    analysisId: 'CUSTOM-TEST', analysisRevision: 1, status: 'awaiting_confirm', sourceName: '自定义单位模块测试.dxf',
    inputMode: 'cad', geometrySource: 'cad_geometry', evidenceKind: 'REAL_SERVICE',
    unitResolution: { canonicalUnit: 'mm', confirmed: true },
    rooms: [{ roomId: 'CUSTOM-ROOM', label: '模块测试空间', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[10000,2000],[17000,2000],[17000,8000],[10000,8000]] }],
  }
  async function api(route) {
    const path = new URL(route.request().url()).pathname
    const respond = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
    if (path.endsWith('/floor-plan/CUSTOM-TEST')) return respond(source)
    if (path.endsWith('/canvas')) {
      if (route.request().method() === 'PUT') { remoteCanvas = route.request().postDataJSON(); return respond(remoteCanvas) }
      return respond(remoteCanvas)
    }
    return respond(path.endsWith('/OFFICE-PROJECT-001') ? { projectName: '自定义模块测试', area: 42 } : [])
  }
  await context.route('**/api/v1/**', api)
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  const placed = async () => (await state()).objects.filter(item => item.kind === 'furniture')
  const dialog = () => page.getByRole('dialog', { name: /单位模块$/ })
  const save = () => dialog().getByRole('button', { name: '保存模块', exact: true }).click()
  async function clickWorld(x, y, moveOnly = false) {
    const point = await page.locator('polygon[data-id="CUSTOM-ROOM"]').evaluate((polygon, point) => {
      const transformed = new DOMPoint(point.x, point.y).matrixTransform(polygon.getScreenCTM())
      return { x: transformed.x, y: transformed.y }
    }, { x, y })
    await page.mouse.move(point.x, point.y)
    if (!moveOnly) await page.mouse.click(point.x, point.y)
  }
  await page.goto(baseUrl + '/canvas?analysisId=CUSTOM-TEST', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '选择空间 模块测试空间', exact: true }).waitFor()
  await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()
  await page.getByRole('button', { name: /新增单位模块/ }).click()
  await save()
  await dialog().getByRole('alert').filter({ hasText: '模块名称' }).waitFor()
  assert.equal((await state()).customModules.length, 0)
  await dialog().getByLabel('自定义模块名称').fill('我的长 L 桌')
  await dialog().getByLabel('自定义模块图例').selectOption('desk-l')
  await dialog().getByLabel('自定义模块宽度（毫米）').fill('0')
  await save()
  await dialog().getByRole('alert').filter({ hasText: '10 毫米' }).waitFor()
  await dialog().getByLabel('自定义模块宽度（毫米）').fill('1800')
  await dialog().getByLabel('自定义模块进深（毫米）').fill('1400')
  await dialog().getByLabel('自定义模块高度（毫米）').fill('750')
  await page.screenshot({ path: outDir + '/01-custom-module-dialog.png' })
  await save()
  await dialog().waitFor({ state: 'hidden' })
  const module = (await state()).customModules[0]
  assert.match(module.id, /^UMOD-/)
  assert.equal(module.glyphTemplateId, 'desk-l')
  assert.equal(await page.getByRole('button', { name: '我的长 L 桌', exact: true }).getAttribute('aria-pressed'), 'true')
  assert.equal(await page.getByLabel('模块宽度（毫米）').inputValue(), '1800')
  assert.equal((await placed()).length, 0, '保存目录不能自动改动画布')
  await page.getByRole('button', { name: /新增单位模块/ }).click()
  await dialog().getByLabel('自定义模块名称').fill('我的长 L 桌')
  await save()
  await dialog().getByRole('alert').filter({ hasText: '同名' }).waitFor()
  assert.equal((await state()).customModules.length, 1)
  await dialog().getByRole('button', { name: '取消', exact: true }).click()
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '我的长 L 桌', exact: true }).click()
  assert.equal((await state()).customModules[0].id, module.id)
  const room = (await state()).objects.find(item => item.id === 'CUSTOM-ROOM')
  const geometry = structuredClone(room)
  await page.getByRole('button', { name: '添加到画布', exact: true }).click()
  await clickWorld(room.x + 1500, room.y + 1500, true)
  assert.equal(await page.getByTestId('furniture-placement-preview').getAttribute('data-valid'), 'true')
  await clickWorld(room.x + 1500, room.y + 1500)
  assert.equal((await placed()).length, 1)
  const first = (await placed())[0]
  assert.equal(first.furnitureTemplateId, module.id)
  assert.equal(first.moduleSnapshot.name, '我的长 L 桌')
  assert.equal(first.moduleSnapshot.heightMm, 750)
  assert.equal(first.w, 1800)
  assert.equal(first.h, 1400)
  assert.ok(first.spaceTemplateId && first.templateSpaceId === room.id)
  await clickWorld(room.x + 1500, room.y + 1500)
  assert.equal((await placed()).length, 1, '重叠不得新增或替换旧家具')
  await page.getByRole('status').filter({ hasText: '重叠' }).waitFor()
  await clickWorld(room.x + 100, room.y + 100)
  assert.equal((await placed()).length, 1, '越界和离墙距离不足不得放置')
  await page.getByRole('status').filter({ hasText: '离墙' }).waitFor()
  await page.keyboard.press('r')
  await clickWorld(room.x + 4500, room.y + 1500)
  const two = await placed()
  assert.equal(two.length, 2)
  assert.deepEqual(two[0], first)
  assert.equal(two[1].rotation, 90)
  assert.equal(two[1].w, 1800)
  assert.equal(two[1].h, 1400)
  await page.keyboard.press('Escape')
  assert.equal(await page.getByTestId('furniture-placement-preview').count(), 0)
  await page.getByRole('button', { name: '添加到画布', exact: true }).waitFor()
  await page.screenshot({ path: outDir + '/02-manual-custom-placement.png', animations: 'disabled' })
  await page.getByRole('button', { name: '确认当前排版', exact: true }).click()
  assert.ok((await placed()).every(item => item.layoutConfirmed))
  // Add a built-in chair without replacing either custom desk, then undo.
  await page.getByTestId('unit-module-catalog').locator('summary').filter({ hasText: /^座椅/ }).click()
  await page.getByRole('button', { name: '办公转椅', exact: true }).click()
  await page.getByRole('button', { name: '添加到画布', exact: true }).click()
  await clickWorld(room.x + 4500, room.y + 4000)
  assert.equal((await placed()).length, 3)
  assert.ok((await placed()).every(item => !item.layoutConfirmed))
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.equal((await placed()).length, 2)
  assert.ok((await placed()).every(item => item.layoutConfirmed))
  const beforeEdit = await placed()
  await page.getByRole('button', { name: '编辑模块 我的长 L 桌', exact: true }).click()
  await dialog().getByLabel('自定义模块名称').fill('我的圆桌')
  await dialog().getByLabel('自定义模块图例').selectOption('table-meeting-round')
  await save()
  assert.deepEqual(await placed(), beforeEdit, '编辑目录不改写已摆实例的几何和图例快照')
  assert.equal((await state()).customModules[0].glyphTemplateId, 'table-meeting-round')
  assert.equal(await page.getByTestId('office-design-canvas').locator('[data-furniture-template="desk-l"]').count(), 2)
  await page.getByRole('button', { name: '一键自动排版', exact: true }).click()
  const auto = await placed()
  assert.ok(auto.length > 2 && auto.every(item => item.furnitureTemplateId === module.id && item.moduleSnapshot.glyphTemplateId === 'table-meeting-round' && item.productId === ''))
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual(await placed(), beforeEdit)
  await page.getByRole('button', { name: '删除目录模块 我的圆桌', exact: true }).click()
  assert.equal((await state()).customModules.length, 0)
  assert.deepEqual(await placed(), beforeEdit)
  // Wait for the existing 800ms debounced canvas save before leaving the page.
  const expectedRevision = (await state()).canvasRevision
  for (let attempt = 0; !remoteCanvas || remoteCanvas.revision < expectedRevision; attempt++) {
    if (attempt > 40) throw new Error('模拟画布保存未完成')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  assert.deepEqual(remoteCanvas.objects.filter(item => item.kind === 'furniture'), beforeEdit)
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('office-design-canvas').waitFor()
  assert.deepEqual(await placed(), beforeEdit)
  assert.equal(await page.getByTestId('office-design-canvas').locator('[data-furniture-template="desk-l"]').count(), 2)
  assert.deepEqual((await state()).objects.find(item => item.id === room.id), geometry)
  await page.waitForFunction(() => !JSON.parse(localStorage.getItem('rsdp-office-v01')).furniturePlacement)
  const freshContext = await browser.newContext({ viewport: { width: 1750, height: 1150 } })
  await freshContext.route('**/api/v1/**', api)
  const fresh = await freshContext.newPage()
  fresh.on('pageerror', error => errors.push(error.message))
  await fresh.goto(baseUrl + '/canvas?mode=design&zoneId=CUSTOM-ROOM', { waitUntil: 'networkidle' })
  await fresh.getByTestId('office-design-canvas').waitFor()
  assert.equal(await fresh.getByTestId('office-design-canvas').locator('[data-furniture-template="desk-l"]').count(), 2)
  await freshContext.close()
  assert.deepEqual(errors, [])
  console.log('自定义/逐件添加 UI 通过：名称尺寸校验、本机目录/刷新、毫米快照、点击/连续放置、旋转、越界/重叠拒绝、保留旧家具/确认失效/撤销、目录编辑移除不改实例、自动排版及无本机目录时从保存快照恢复线稿。全部 API 模拟。')
} catch (failure) {
  await debugPage?.screenshot({ path: outDir + '/failure.png' }).catch(() => {})
  if (debugPage) console.error(await debugPage.evaluate(() => ({ text: document.body.innerText.slice(-1600), draft: JSON.parse(localStorage.getItem('rsdp-office-v01'))?.customModules })).catch(() => null))
  throw failure
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
