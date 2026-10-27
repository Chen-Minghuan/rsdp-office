import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { chromium } from 'playwright'

const app = spawn(process.execPath, ['.output/server/index.mjs'], { env: { ...process.env, PORT: '3197', HOST: '127.0.0.1' }, stdio: 'ignore' })
let browser
try {
  for (let n = 0; ; n++) {
    try { if ((await fetch('http://127.0.0.1:3197')).ok) break } catch {}
    if (n > 100) throw new Error('preview server unavailable')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const zone = { id: 'PLAN-L', kind: 'zone', name: '测试办公室', zoneType: 'OPEN_OFFICE', x: -6000, y: -4000, w: 8000, h: 6000, reviewUnit: 'mm', polygon: [[-6000,-4000],[2000,-4000],[2000,-1000],[-1000,-1000],[-1000,2000],[-6000,2000]] }
  const objects = [zone,
    { id: 'DESK-A', kind: 'furniture', zoneId: zone.id, productId: '', furnitureTemplateId: 'desk-l', x: -5000, y: -3000, w: 1400, h: 1400, rotation: 90, mirrorX: true },
    { id: 'DESK-B', kind: 'furniture', zoneId: zone.id, productId: '', furnitureTemplateId: 'desk-straight', x: -3000, y: -3000, w: 1200, h: 600, rotation: 0 },
  ]
  await page.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  await page.route('**/api/v1/**', route => route.fulfill({ json: { code: 200, data: route.request().url().endsWith('/canvas') ? null : [] } }))
  await page.addInitScript(objects => localStorage.setItem('rsdp-office-v01', JSON.stringify({ objects, files: [], cart: [], canvasRevision: 1 })), objects)
  await page.goto('http://127.0.0.1:3197/proposal', { waitUntil: 'networkidle' })
  const thumb = page.locator('.preset-layout-thumb .layout-plan-preview').first()
  await thumb.waitFor()
  assert.equal(await thumb.locator('[data-furniture-template]').count(), 2)
  assert.equal((await thumb.locator('polygon').getAttribute('points')).split(' ').length, 6)
  await page.locator('.layout-panel').first().click()
  const detail = page.locator('.layout-plan-detail .layout-plan-preview')
  await detail.waitFor()
  assert.equal(await detail.locator('[data-placement-id]').count(), 2)
  assert.equal(await detail.locator('[data-furniture-template="desk-l"]').count(), 1)
  assert.match(await detail.locator('[data-placement-id="DESK-A"]').getAttribute('transform'), /rotate\(90\) scale\(-1 1\)/)
  assert.equal(await thumb.getAttribute('viewBox'), await detail.getAttribute('viewBox'))
  for (const width of [1600, 720, 390]) {
    await page.setViewportSize({ width, height: 900 })
    const bounds = await detail.boundingBox()
    const glyph = await detail.locator('[data-furniture-template="desk-straight"]').boundingBox()
    assert.ok(glyph.width > 0 && glyph.x >= bounds.x && glyph.x + glyph.width <= bounds.x + bounds.width)
  }
  assert.deepEqual(errors, [])
  assert.deepEqual(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).objects), objects)
  console.log('PASS: actual glyphs, L contour, rotation/mirror, shared coordinates, responsive preview, unchanged geometry')
} finally {
  await browser?.close()
  app.kill()
}
