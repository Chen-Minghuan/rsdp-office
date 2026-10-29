import { chromium } from 'playwright'
import { mkdir } from 'node:fs/promises'

const baseUrl = process.env.BASE_URL || 'http://localhost:3001'
const outDir = process.env.OUT_DIR || './screenshots'

await mkdir(outDir, { recursive: true })

const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
if (process.env.MOCK_API === '1') {
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  await context.route('**/api/v1/**', route => {
    const url = new URL(route.request().url())
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: '成功', data: url.pathname.endsWith('/OFFICE-PROJECT-001') || url.pathname.endsWith('/canvas') ? null : [] }),
    })
  })
}
const page = await context.newPage()

async function shot(name) {
  const file = `${outDir}/${name}.png`
  await page.screenshot({ path: file, fullPage: false })
  console.log(`  screenshot: ${file}`)
}

// 1. 上传页
console.log('Step 1: upload page')
await page.goto(`${baseUrl}/`, { waitUntil: 'networkidle' })
await page.waitForSelector('h1:has-text("上传办公图纸")', { timeout: 10000 })
await shot('01-upload')

// 2. 空间总览
console.log('Step 2: overview')
await page.goto(`${baseUrl}/canvas`, { waitUntil: 'networkidle' })
await page.getByRole('img', { name: '空间核对画布', exact: true }).waitFor({ timeout: 10000 })
await page.getByRole('button', { name: '加载工装示例（可撤销）', exact: true }).click()
await shot('02-overview')

// 3. 进入单空间设计
console.log('Step 3: enter design')
await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()
await page.waitForURL(/mode=design/, { timeout: 10000 })
await page.getByTestId('apply-layout').waitFor({ timeout: 10000 })
await shot('03-design')

// 4. 选择单位模块、一键排版、确认排版并加入方案
console.log('Step 4: select module, layout and add to cart')
await page.getByTestId('tab-product-selection').click()
await page.locator('[data-auto-module-id="desk-straight"]').click()
await page.waitForTimeout(200)
await page.getByTestId('apply-layout').click()
await page.waitForTimeout(500)
await page.getByTestId('auto-confirm-recommend').click()
await page.waitForTimeout(300)
await shot('04-design-layout-confirmed')
const addButtons = await page.locator('button:has-text("加入方案")').all()
console.log(`  found ${addButtons.length} products`)
for (let i = 0; i < Math.min(3, addButtons.length); i++) {
  await addButtons[i].click()
  await page.waitForTimeout(200)
}
await shot('04-design-with-cart')

// 5. 整体方案
console.log('Step 5: proposal')
await page.click('text=生成整体方案')
await page.waitForURL('**/proposal', { timeout: 10000 })
await page.waitForSelector('h1:has-text("整体方案与清单")', { timeout: 10000 })
await shot('05-proposal')

// 6. 调整数量
console.log('Step 6: change qty')
const plus = page.locator('table tbody tr').first().locator('button').nth(1)
if (await plus.count()) {
  await plus.click()
  await page.waitForTimeout(200)
  await shot('06-proposal-qty')
}

await browser.close()
console.log('E2E smoke passed')
