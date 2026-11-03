import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

// 3D 白模预览 UI 回归：独立端口 + 模拟 API，测试结束关闭测试服务。
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
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status, message: '成功', data }) })
    if (path === '/api/v1/office/products') {
      return respond([
        { id: 'P1', rspuId: 'R1', variantId: 'V1', widthMm: 1400, depthMm: 700, widthCm: 140, depthCm: 70, heightMm: 750, name: '行政桌 1400', desc: '', variant: '', price: 1999, kind: 'desk' },
      ])
    }
    return respond(path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : [])
  })
  const page = await context.newPage()
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
    ]
    state.canvasRevision = 3; state.canvasMode = 'design'; state.designZoneId = 'Z1'; state.cart = []; state.floorPlan = null; state.spaceTemplate = null
    localStorage.setItem('rsdp-office-v01', JSON.stringify(state))
  })
  await page.goto(baseUrl + '/canvas?mode=design&zoneId=Z1', { waitUntil: 'networkidle' })

  await page.getByRole('button', { name: '3D 白模', exact: true }).click()
  const dialog = page.getByTestId('white-model-preview')
  await dialog.waitFor()
  await page.waitForFunction(() => document.querySelector('[data-testid="white-model-preview"]')?.dataset.ready === 'true', undefined, { timeout: 15000 })
  assert.equal(await dialog.getAttribute('data-furniture'), '2')
  assert.equal(await dialog.getAttribute('data-obstacles'), '1')
  assert.equal(await dialog.getAttribute('data-facilities'), '1')
  assert.equal(await dialog.getAttribute('data-walls'), '4')

  const readObject = id => page.evaluate(oid => JSON.parse(localStorage.getItem('rsdp-office-v01')).objects.find(o => o.id === oid), id)

  // 面板选择 + 旋转写回同一份设计几何
  await page.getByTestId('white-model-select-A').click()
  await page.getByTestId('white-model-rotate').click()
  assert.equal((await readObject('A')).rotation, 90, '旋转应写回设计几何')

  // 替换模块：换模板并同步尺寸
  await page.getByTestId('white-model-template').selectOption('desk-manager')
  assert.deepEqual([(await readObject('A')).w, (await readObject('A')).h], [1400, 600], '替换模块应同步宽深')
  assert.equal((await readObject('A')).furnitureTemplateId, 'desk-manager')

  // 绑定商品：productId 与尺寸同步
  await page.getByTestId('white-model-product').selectOption('P1')
  assert.equal((await readObject('A')).productId, 'P1')
  assert.deepEqual([(await readObject('A')).w, (await readObject('A')).h], [1400, 700])

  // 拖动：沿地面移动并写回
  const before = await readObject('A')
  const center = await page.evaluate(() => window.__whiteModelProject('A'))
  assert.ok(center, '投影钩子应返回屏幕坐标')
  await page.mouse.move(center.x, center.y)
  await page.mouse.down()
  await page.mouse.move(center.x + 120, center.y + 60, { steps: 12 })
  await page.mouse.up()
  const after = await readObject('A')
  assert.notDeepEqual([after.x, after.y], [before.x, before.y], '拖动应更新家具位置')
  await page.getByTestId('white-model-notice').waitFor()

  // AI 入口保留可达
  await page.getByRole('button', { name: 'AI 概念效果图' }).click()
  await page.getByRole('dialog').waitFor()
  await page.keyboard.press('Escape')

  assert.deepEqual(errors, [], `不应有未捕获错误：${errors.join('; ')}`)
  console.log('3D 白模 UI 通过：场景统计、面板选择、旋转/替换模块/绑定商品写回、拖动同步 2D、AI 入口保留。API 全部模拟。')
} finally {
  await browser?.close()
  app.kill()
}
