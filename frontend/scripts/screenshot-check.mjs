import { chromium } from 'playwright'
import { mkdir } from 'node:fs/promises'

const baseUrl = process.env.BASE_URL || 'http://localhost:3001'
const outDir = process.env.OUT_DIR || './screenshots'

await mkdir(outDir, { recursive: true })

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()

const routes = [
  { path: '/', waitFor: 'text=项目资料' },
  { path: '/canvas', waitFor: 'text=无限画布' },
  { path: '/agent', waitFor: 'text=办公智能选品' },
  { path: '/proposal', waitFor: 'text=方案报价' },
]

for (const { path, waitFor } of routes) {
  const url = `${baseUrl}${path}`
  console.log(`Navigating to ${url}`)
  await page.goto(url, { waitUntil: 'networkidle' })
  try {
    await page.waitForSelector(waitFor, { timeout: 10000 })
    console.log(`  ✓ found ${waitFor}`)
  } catch (e) {
    console.log(`  ✗ missing ${waitFor}: ${e.message}`)
  }
  const file = `${outDir}${path.replace(/\/$/, '/index')}.png`
  await page.screenshot({ path: file, fullPage: false })
  console.log(`  screenshot saved to ${file}`)
}

await browser.close()
console.log('Done')
