import assert from 'node:assert/strict'
import { chromium } from 'playwright'

// DEPLOY-004 域名子路径复测：DNS 未切换前用 host-resolver-rules 强制
// rooom.vip 指向本实例，验证 http://rooom.vip/office/ 的登录、文件选择、
// 画布直达与退出；同时确认 80 端口原站点未被影响。不触发识别/生图，
// 不保存云端设计。
const baseUrl = process.env.RSDP_OFFICE_URL || 'http://rooom.vip/office/'
const username = process.env.RSDP_ADMIN_USERNAME || 'admin'
const password = process.env.RSDP_ADMIN_PASSWORD
assert.ok(password, '需要 RSDP_ADMIN_PASSWORD 环境变量（不写入仓库与文档）')

// DNS 未切换期间用 RSDP_FORCE_RESOLVE=1 强制解析；默认使用真实 DNS。
const forceResolve = process.env.RSDP_FORCE_RESOLVE === '1'
const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined,
  args: forceResolve ? ['--host-resolver-rules=MAP rooom.vip 101.200.81.123, MAP www.rooom.vip 101.200.81.123'] : [],
})
try {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  let analyzeCalls = 0
  await context.route('**/api/v1/floor-plan/analyze', route => {
    analyzeCalls++
    return route.fulfill({ status: 599, json: { code: 599, message: 'blocked by subpath retest', data: null } })
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))

  // 匿名访问子路径被守卫引导到子路径登录页（而不是根路径或旧站点）。
  await page.goto(baseUrl, { waitUntil: 'networkidle' })
  assert.ok(new URL(page.url()).pathname.startsWith('/office/login'), `匿名应落到子路径登录页：${page.url()}`)

  await page.locator('#username').fill(username)
  await page.locator('#password').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForURL(url => url.pathname === '/office/' || url.pathname === '/office', { timeout: 15000 })
  const me = await page.evaluate(async () => (await fetch('api/auth/me', { credentials: 'same-origin' }).catch(() => fetch('/office/api/auth/me'))).status)
  assert.equal(me, 200, '登录后 /office/api/auth/me 应为 200')

  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXioAAAAASUVORK5CYII=', 'base64')
  await page.locator('input[type="file"]').first().setInputFiles({ name: 'office-subpath.png', mimeType: 'image/png', buffer: png })
  await page.waitForFunction(() => {
    const raw = localStorage.getItem('rsdp-office-v01')
    return raw && JSON.parse(raw).files?.some(file => file.name === 'office-subpath.png' && file.drawingId)
  }, undefined, { timeout: 10000 })

  // 直达子路径画布路由（经 301 /office/canvas/ 与 SPA 路由恢复）。云端已有
  // 工作区时进入空间核对，空工作区显示图纸进入守卫，两者均为有效落地。
  await page.goto(baseUrl + 'canvas', { waitUntil: 'networkidle' })
  assert.match(new URL(page.url()).pathname, /^\/office\/canvas/)
  await page.getByTestId('drawing-entry').or(page.getByRole('heading', { name: '无限画布 · 空间核对', exact: true })).first().waitFor()
  // 云端场景经 /office/api 加载成功即证明子路径 API 前缀生效；底图链接须落在子路径。
  // 底图来自识别时后端按请求地址写入的绝对 URL：旧数据指向 8081 入口，
  // 经域名新识别的数据会因 X-Forwarded-Prefix 落在 /office/ 下；两者都应可取。
  const underlay = await page.locator('[data-layer="underlay"] image').first().getAttribute('href').catch(() => null)
  if (underlay) {
    // 存储地址可能指向另一入口（跨源），用 Node 侧请求核验可达性，绕开页面 CORS。
    const asset = await context.request.get(new URL(underlay, baseUrl).href)
    assert.equal(asset.status(), 200, `底图资源应可访问：${underlay}`)
  }

  // 80 端口原站点根路径不受影响，仍是原静态站而非本应用。
  const site = await page.evaluate(async () => {
    const res = await fetch('/', { credentials: 'omit' })
    return { status: res.status, html: (await res.text()).slice(0, 2000) }
  })
  assert.equal(site.status, 200)
  assert.ok(!site.html.includes('__nuxt'), '域名根路径应仍是原站点页面')

  await page.evaluate(() => { localStorage.removeItem('rsdp-office-v01'); indexedDB.deleteDatabase('rsdp-office-files') })
  assert.equal(analyzeCalls, 0, '不应触发识别接口')
  assert.deepEqual(errors, [], `页面不应有未捕获错误：${errors.join('; ')}`)

  await page.evaluate(async () => { await fetch('/office/api/auth/logout', { method: 'POST' }) })
  const after = await page.evaluate(async () => (await fetch('/office/api/auth/me')).status)
  assert.equal(after, 401, '退出后应为 401')
  console.log('域名子路径复测通过：/office/ 守卫登录、子路径 API、文件选择、画布直达、原站点不受影响、退出失效')
} finally {
  await browser.close()
}
