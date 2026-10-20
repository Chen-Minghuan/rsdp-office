import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { chromium } from 'playwright'

// DEPLOY-003 公网复测：真实 HTTP 入口、真实登录，验证文件选择在缺少
// crypto.randomUUID 的公网环境下恢复。只在文件选择后停止，不触发识别，
// 不修改用户云端设计；识别接口若被调用则立即失败并计数断言。
const baseUrl = process.env.RSDP_PUBLIC_URL || 'http://101.200.81.123:8081/'
const username = process.env.RSDP_ADMIN_USERNAME || 'admin'
const password = process.env.RSDP_ADMIN_PASSWORD
assert.ok(password, '需要 RSDP_ADMIN_PASSWORD 环境变量（不写入仓库与文档）')

const outDir = new URL('../../output/deploy/', import.meta.url).pathname
await mkdir(outDir, { recursive: true })
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
try {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  let analyzeCalls = 0
  await context.route('**/api/v1/floor-plan/analyze', route => {
    analyzeCalls++
    return route.fulfill({ status: 599, contentType: 'application/json', body: JSON.stringify({ code: 599, message: 'blocked by uuid retest', data: null }) })
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))

  await page.goto(baseUrl + 'login', { waitUntil: 'networkidle' })
  const env = await page.evaluate(() => ({
    secure: window.isSecureContext,
    hasRandomUUID: typeof crypto.randomUUID === 'function',
    hasGetRandomValues: typeof crypto.getRandomValues === 'function',
  }))
  assert.equal(env.secure, false, '公网入口当前应处于 HTTP 非安全上下文')
  assert.equal(env.hasRandomUUID, false, '公网 HTTP 下 randomUUID 应缺失（修复针对的环境）')
  assert.equal(env.hasGetRandomValues, true, '公网 HTTP 下 getRandomValues 应可用')

  await page.locator('#username').fill(username)
  await page.locator('#password').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForURL(url => !url.pathname.startsWith('/login'), { timeout: 15000 })
  const me = await page.evaluate(async () => (await fetch('/api/auth/me')).status)
  assert.equal(me, 200, '登录后 /api/auth/me 应为 200')

  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXioAAAAASUVORK5CYII=', 'base64')
  await page.locator('input[type="file"]').first().setInputFiles({ name: 'uuid-retest.png', mimeType: 'image/png', buffer: png })
  await page.waitForFunction(() => {
    const raw = localStorage.getItem('rsdp-office-v01')
    return raw && JSON.parse(raw).files?.some(file => file.name === 'uuid-retest.png' && file.drawingId)
  }, undefined, { timeout: 10000 })
  const drawingId = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).files.find(file => file.name === 'uuid-retest.png').drawingId)
  assert.match(drawingId, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/, 'HTTP 回退应生成标准 v4 UUID')
  assert.equal(await page.locator('text=上传图纸失败').count(), 0, '不应出现上传失败提示')

  // 只验证文件选择恢复；清理本次本地图纸记录并退出，不进入识别、不保存设计。
  await page.evaluate(() => { localStorage.removeItem('rsdp-office-v01'); indexedDB.deleteDatabase('rsdp-office-files') })
  assert.equal(analyzeCalls, 0, '文件选择不应触发识别接口')
  const uuidErrors = errors.filter(message => /randomUUID|唯一标识/.test(message))
  assert.deepEqual(errors, [], `页面不应有未捕获错误：${errors.join('; ')}`)
  assert.equal(uuidErrors.length, 0)
  await page.screenshot({ path: outDir + 'public-uuid-retest.png' })

  await page.evaluate(async () => { await fetch('/api/auth/logout', { method: 'POST' }) })
  const after = await page.evaluate(async () => (await fetch('/api/auth/me')).status)
  assert.equal(after, 401, '退出后 /api/auth/me 应为 401')
  console.log('公网复测通过：HTTP 环境确认 randomUUID 缺失，真实登录后文件选择生成 v4 UUID，无页面错误，未触发识别，退出失效。截图：output/deploy/public-uuid-retest.png')
} finally {
  await browser.close()
}
