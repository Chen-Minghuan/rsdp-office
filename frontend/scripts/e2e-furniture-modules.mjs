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
const outDir = process.env.OUT_DIR || '/tmp/rsdp-furniture-modules'
let browser, debugPage
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先 pnpm build；预览服务未就绪')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  await mkdir(outDir, { recursive: true })
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1700, height: 1100 } })
  const page = await context.newPage(), errors = []
  debugPage = page
  page.on('pageerror', error => errors.push(error.message))
  const source = {
    analysisId: 'MODULE-TEST', analysisRevision: 1, status: 'awaiting_confirm', sourceName: '家具图例测试.dxf',
    inputMode: 'cad', geometrySource: 'cad_geometry', evidenceKind: 'REAL_SERVICE',
    unitResolution: { canonicalUnit: 'mm', confirmed: true },
    rooms: [{ roomId: 'MODULE-ROOM', label: '办公家具测试空间', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[0,0],[12000,0],[12000,8000],[0,8000]] }],
  }
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    const data = path.endsWith('/floor-plan/MODULE-TEST') ? source : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  const furniture = async () => (await state()).objects.filter(object => object.kind === 'furniture')
  const layout = () => page.getByRole('button', { name: '一键自动排版', exact: true }).click()
  const catalog = () => page.getByTestId('unit-module-catalog')
  const canvas = () => page.getByTestId('office-design-canvas')
  const width = () => page.getByLabel('模块宽度（毫米）')
  const depth = () => page.getByLabel('模块进深（毫米）')
  async function choose(id) {
    const card = catalog().locator('[data-module-id="' + id + '"]')
    const section = card.locator('xpath=ancestor::details')
    if (!(await section.getAttribute('open') !== null)) await section.locator('summary').click()
    await card.click()
  }
  await page.goto(baseUrl + '/canvas?analysisId=MODULE-TEST', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '选择空间 办公家具测试空间', exact: true }).waitFor()
  await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()
  await catalog().waitFor()
  const modules = await catalog().locator('[data-module-id]').evaluateAll(cards => cards.map(card => ({
    id: card.dataset.moduleId, name: card.getAttribute('aria-label'),
    geometry: card.querySelector('.manual-furniture-glyph').innerHTML,
  })))
  assert.equal(modules.length, 28)
  assert.equal(await catalog().locator('details').count(), 9)
  assert.equal(modules.some(module => module.id === 'desk-u'), false, '与手动布局一致，不暴露隐藏目录项')
  const manual = await context.newPage()
  await manual.goto(baseUrl + '/manual-canvas', { waitUntil: 'networkidle' })
  const manualModules = await manual.locator('.furniture-catalog .furniture-template-grid button').evaluateAll(cards => cards.map(card => ({
    name: card.querySelector('.furniture-template-copy b').textContent,
    geometry: card.querySelector('.manual-furniture-glyph').innerHTML,
  })))
  assert.deepEqual(modules.map(({ id, ...module }) => module), manualModules, '目录与每个缩略线稿必须和手动布局完全一致')
  await manual.close()

  for (const module of modules) {
    await choose(module.id)
    const w = Number(await width().inputValue()), d = Number(await depth().inputValue())
    const capacity = Number(await page.getByTestId('module-capacity').innerText())
    assert.ok(capacity > 0, module.name)
    await layout()
    const placed = await furniture()
    assert.equal(placed.length, capacity)
    assert.ok(placed.every(item => item.furnitureTemplateId === module.id && item.productId === '' && item.w === w && item.h === d && item.zoneId === 'MODULE-ROOM' && item.spaceTemplateId && item.templateSpaceId === 'MODULE-ROOM'))
    assert.equal(await canvas().locator('[data-furniture-template="' + module.id + '"] .manual-furniture-glyph').count(), capacity)
  }
  await choose('desk-l-rounded')
  await page.getByLabel('模块常用规格').selectOption('1600,1600')
  assert.equal(await width().inputValue(), '1600')
  assert.equal(await depth().inputValue(), '1600')
  await width().fill('1750')
  await depth().fill('1500')
  await layout()
  assert.ok((await furniture()).every(item => item.w === 1750 && item.h === 1500))
  const rounded = await canvas().locator('.manual-furniture-glyph').first().locator('path').first().getAttribute('d')
  assert.match(rounded, /Q/, '圆角 L 桌的实际画布仍保留圆角曲线')
  const firstRounded = (await furniture())[0]
  const stroke = await canvas().locator('[data-furniture-template]').first().evaluate((svg, footprintWidth) =>
    parseFloat(getComputedStyle(svg.querySelector('.manual-furniture-glyph')).strokeWidth) * svg.getBoundingClientRect().width / footprintWidth, firstRounded.rotation % 180 ? firstRounded.h : firstRounded.w)
  assert.ok(Math.abs(stroke - 1.3) < 0.05, '画布 CSS 缩放后线稿保持可读线宽')
  assert.equal(await page.getByLabel('模块常用规格').inputValue(), '', '自定义尺寸显示对应提示')
  await page.screenshot({ path: outDir + '/01-shared-legend.png' })
  const beforeInvalid = await furniture()
  await width().fill('0')
  assert.ok(await page.getByRole('button', { name: '一键自动排版', exact: true }).isDisabled())
  assert.deepEqual(await furniture(), beforeInvalid)
  await width().fill('20000')
  assert.equal(await page.getByTestId('module-capacity').innerText(), '0')
  assert.ok(await page.getByRole('button', { name: '一键自动排版', exact: true }).isDisabled())
  assert.deepEqual(await furniture(), beforeInvalid, '无效或放不下不能清空已有布局')

  await choose('table-conference-oval')
  await layout()
  assert.ok((await furniture()).every(item => item.furnitureTemplateId === 'table-conference-oval'))
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.deepEqual(await furniture(), beforeInvalid, '撤销恢复图例、尺寸及归属')
  await page.reload({ waitUntil: 'networkidle' })
  await canvas().waitFor()
  assert.deepEqual(await furniture(), beforeInvalid, '刷新保留图例及尺寸')
  assert.equal(await canvas().locator('[data-furniture-template="desk-l-rounded"]').count(), beforeInvalid.length)

  await choose('desk-straight')
  await page.getByRole('button', { name: '沿边', exact: true }).click()
  await page.getByRole('button', { name: '应用沿边方案', exact: true }).click()
  const perimeter = await furniture()
  assert.ok(perimeter.some(item => item.rotation === 90))
  assert.ok(perimeter.every(item => item.w === 1200 && item.h === 600), '旋转占地不能改写家具实际宽深')
  await page.getByRole('button', { name: '确认当前排版', exact: true }).click()
  assert.ok((await furniture()).every(item => item.layoutConfirmed))
  await page.getByText('排版已确认', { exact: true }).waitFor()
  assert.deepEqual(errors, [])
  console.log('单位模块 UI 通过：28 个可见图例/9 类与手动布局目录和线稿一致、全部图例按毫米排版、无需绑定商品、规格/自定义尺寸、圆角 L 桌、旋转宽深、无效/超大尺寸保护及撤销/刷新/确认。全部 API 模拟。')
} catch (failure) {
  await debugPage?.screenshot({ path: outDir + '/failure.png' }).catch(() => {})
  throw failure
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
