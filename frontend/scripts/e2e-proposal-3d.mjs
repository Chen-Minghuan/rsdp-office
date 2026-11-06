import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

// 方案页 3D 交互接入 UI 回归（RENDER-004）：独立端口 + 模拟 API。
// 覆盖：效果图详情三页签（3D 交互 / AI 概念图 / 2D 布局）、3D 只读限制、属性展示、定位到 2D 跳转。
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
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status, message: '成功', data }) })
    if (path === '/api/v1/office/products') return respond([P1])
    return respond(path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : [])
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))

  await page.goto(baseUrl + '/proposal', { waitUntil: 'networkidle' })
  await page.evaluate(() => {
    const state = JSON.parse(localStorage.getItem('rsdp-office-v01') || '{}')
    state.objects = [
      { id: 'Z1', kind: 'zone', name: '开放办公区', x: 0, y: 0, w: 6000, h: 4000, zoneType: 'OPEN_OFFICE', tone: 'blue', reviewUnit: 'mm', confirmed: true, reviewConfirmation: { status: 'CONFIRMED' },
        polygon: [[0, 0], [6000, 0], [6000, 4000], [0, 4000]], obstacles: [], facilities: [] },
      { id: 'A', kind: 'furniture', zoneId: 'Z1', furnitureTemplateId: 'desk-straight', label: '职员桌 A', x: 400, y: 400, w: 1200, h: 600, rotation: 0,
        productId: 'P1', layoutConfirmed: true,
        productSelection: { product: null, cartLineId: 'L1', sourceCanvasRevision: 3 } },
    ]
    // productSelection.product 由服务端写入；此处 null 模拟旧数据，面板应回退到在售列表实时数据
    state.cart = [{ id: 'L1', productId: 'P1', zoneId: 'Z1', zoneName: '开放办公区', qty: 1 }]
    state.canvasRevision = 3; state.canvasMode = 'overview'; state.designZoneId = null
    state.floorPlan = null; state.spaceTemplate = null
    localStorage.setItem('rsdp-office-v01', JSON.stringify(state))
  })
  await page.goto(baseUrl + '/proposal', { waitUntil: 'networkidle' })

  // 打开 3D 交互效果（真实空间，无需已有 AI 任务）
  await page.getByTestId('open-3d-view').click()
  const drawer = page.getByRole('dialog', { name: '效果图预览' })
  await drawer.waitFor()
  await page.getByTestId('proposal-render-tabs').waitFor()
  const view = page.getByTestId('proposal-3d-view')
  await view.waitFor()
  const preview = view.getByTestId('white-model-preview')
  await page.waitForFunction(() => document.querySelector('[data-testid="proposal-3d-view"] [data-testid="white-model-preview"]')?.dataset.ready === 'true', undefined, { timeout: 15000 })
  assert.equal(await preview.getAttribute('data-readonly'), 'true', '方案页 3D 应为只读')
  assert.equal(await preview.getAttribute('data-furniture'), '1')
  assert.equal(await preview.getAttribute('data-default-seats'), '1', '只读方案同样显示默认配套办公椅')

  // 只读限制：无旋转/替换按钮；选中可查看真实商品属性
  await preview.getByTestId('white-model-select-A').click()
  await page.getByTestId('white-model-panel').waitFor()
  const readonlySnapshot = await page.evaluate(() => JSON.stringify(document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').objects))
  for (const key of ['ArrowRight', 'Shift+ArrowUp', 'Alt+ArrowDown', 'Control+z']) await page.keyboard.press(key)
  assert.equal(await page.evaluate(() => JSON.stringify(document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').objects)), readonlySnapshot, '只读方案不响应键盘移动或撤销')
  assert.equal(await preview.getByTestId('white-model-keyboard-help').count(), 0)
  assert.equal(await preview.getByTestId('white-model-rotate').count(), 0, '只读模式不得提供旋转')
  assert.equal(await preview.getByTestId('white-model-template').count(), 0, '只读模式不得提供模块替换')
  assert.equal(await preview.getByTestId('white-model-product-change').count(), 0, '只读模式不得提供商品替换')
  assert.equal(await page.getByTestId('white-model-product-name').textContent(), '行政桌 1200', '应展示在售列表中的真实商品数据')
  assert.match(await page.getByTestId('white-model-panel').textContent(), /R1/, '应展示 RSPU')
  assert.match(await page.getByTestId('white-model-size').textContent(), /1200×600mm/)

  // AI 概念图页签：无任务时明确提示，不伪装成 3D
  await page.getByTestId('proposal-render-tab-ai').click()
  assert.match(await drawer.textContent(), /尚未生成效果图/)

  // 2D 布局页签
  await page.getByTestId('proposal-render-tab-layout').click()
  await drawer.locator('.layout-plan-detail').waitFor()

  // 回到 3D 并「前往设计模式编辑」→ 跳转 2D 设计画布
  await page.getByTestId('proposal-render-tab-model').click()
  await page.waitForFunction(() => document.querySelector('[data-testid="proposal-3d-view"] [data-testid="white-model-preview"]')?.dataset.ready === 'true', undefined, { timeout: 15000 })
  await page.getByTestId('white-model-select-A').click()
  await page.getByTestId('white-model-locate').click()
  await page.waitForURL(/\/canvas\?.*mode=design.*zoneId=Z1/)
  assert.ok(page.url().includes('zoneId=Z1'), '应跳转到对应空间的设计模式')

  assert.deepEqual(errors, [], `不应有未捕获错误：${errors.join('; ')}`)
  console.log('方案页 3D 交互 UI 通过：三页签、只读限制（无旋转/替换入口）、真实商品属性、AI/2D 页签、定位到 2D 跳转。API 全部模拟。')
} finally {
  await browser?.close()
  app.kill()
}
