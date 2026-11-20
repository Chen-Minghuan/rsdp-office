import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const cwd = fileURLToPath(new URL('../', import.meta.url))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = `http://127.0.0.1:${port}`
const app = spawn(process.execPath, ['.output/server/index.mjs'], {
  cwd,
  env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' },
  stdio: 'ignore',
})
let browser

try {
  let ready = false
  for (let attempt = 0; attempt < 75; attempt++) {
    try { if ((await fetch(baseUrl)).ok) { ready = true; break } } catch {}
    if (app.exitCode !== null) throw new Error('测试预览服务启动失败，请先执行 pnpm build')
    await new Promise(resolve => setTimeout(resolve, 200))
  }
  assert.ok(ready)
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })

  const project = {
    projectId: 'PROJ-PARENT', name: '方案确认测试项目', customerRequirements: '', requirementsRevision: 1,
    leader: { userId: 'DEALER-1', username: 'dealer', displayName: '经销商主号', role: 'LEADER' },
    collaborators: [], referenceImages: [],
    floors: [{ projectId: 'PROJ-PARENT', floorId: 'FLOOR-CHILD', name: '二楼', sortOrder: 1 }],
  }
  const zone = {
    id: 'ZONE-1', kind: 'zone', name: '开放办公区', zoneType: 'OPEN_OFFICE', x: 0, y: 0, w: 6000, h: 4000,
    reviewUnit: 'mm', confirmed: true, polygon: [[0, 0], [6000, 0], [6000, 4000], [0, 4000]],
  }
  const furniture = {
    id: 'FURNITURE-1', kind: 'furniture', zoneId: 'ZONE-1', productId: '', furnitureTemplateId: 'desk-straight',
    label: '办公桌', x: 500, y: 500, w: 1200, h: 600, rotation: 0,
  }
  const product = {
    id: 'VARIANT-1', rspuId: 'RSPU-1', variantId: 'VARIANT-1', name: '职员桌', desc: '', variant: '浅木色', price: 1250,
    kind: 'desk', uses: ['OPEN_OFFICE'], widthCm: 120, depthCm: 60, widthMm: 1200, depthMm: 600,
  }

  async function openProposal({ cart = [], confirmFails = false, preview = false } = {}) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 960 } })
    const page = await context.newPage()
    const confirmRequests = []
    await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: {
      userId: 'DEALER-1', username: 'dealer', displayName: '经销商主号', role: 'DEALER',
    } } }))
    await context.route('**/api/v1/**', async route => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      const respond = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: '成功', data }) })
      if (path === '/api/v1/office/projects' && request.method() === 'GET') return respond([project])
      if (path === '/api/v1/office/projects/PROJ-PARENT') return respond(project)
      if (path === '/api/v1/office/projects/FLOOR-CHILD/canvas') return respond({
        canvasId: 1, projectId: 'FLOOR-CHILD', revision: 1, mode: 'overview', designZoneId: null,
        objects: [zone, furniture], files: [], blank: false,
      })
      if (path === '/api/v1/office/projects/FLOOR-CHILD/cart') return respond(cart)
      if (path === '/api/v1/office/projects/FLOOR-CHILD/agent-messages') return respond([])
      if (path === '/api/v1/office/products') return respond([product])
      if (path === '/api/v1/office/projects/FLOOR-CHILD/proposal/catalog-check') return respond(null)
      if (path === '/api/v1/office/projects/PROJ-PARENT/quotation/confirm' && request.method() === 'POST') {
        confirmRequests.push(request.postDataJSON())
        if (confirmFails) return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 400, message: '模拟报价确认失败', data: null }) })
        return respond({ quotationId: 'QT-MOCK-1', projectId: 'PROJ-PARENT', status: 'CONFIRMED' })
      }
      return respond(null)
    })
    await page.goto(`${baseUrl}/proposal${preview ? '?preview=1' : ''}`, { waitUntil: 'networkidle' })
    await page.getByRole('heading', { name: '整体方案与清单', exact: true }).waitFor()
    return { context, page, confirmRequests }
  }

  const populatedCart = [{ lineId: 'CART-1', projectId: 'FLOOR-CHILD', zoneId: 'ZONE-1', zoneName: '开放办公区', productId: 'VARIANT-1', qty: 2 }]
  const success = await openProposal({ cart: populatedCart })
  await success.page.getByRole('button', { name: '确认方案', exact: true }).click()
  await success.page.getByRole('button', { name: '已确认方案', exact: true }).waitFor()
  assert.equal(success.confirmRequests.length, 1)
  assert.deepEqual(success.confirmRequests[0], {
    pricingMode: 'COEFFICIENT', pricingValue: 1.2, fees: [],
    lines: [{ floorId: 'FLOOR-CHILD', zoneId: 'ZONE-1', zoneName: '开放办公区', productId: 'VARIANT-1', qty: 2, unitPrice: 1250 }],
  })
  await success.context.close()

  const failed = await openProposal({ cart: populatedCart, confirmFails: true })
  await failed.page.getByRole('button', { name: '确认方案', exact: true }).click()
  await failed.page.getByRole('status').filter({ hasText: '模拟报价确认失败' }).waitFor()
  assert.equal(await failed.page.getByRole('button', { name: '确认方案', exact: true }).count(), 1)
  await failed.context.close()

  const empty = await openProposal()
  await empty.page.getByRole('button', { name: '确认方案', exact: true }).click()
  await empty.page.getByRole('status').filter({ hasText: '没有可确认的商品明细' }).waitFor()
  assert.equal(empty.confirmRequests.length, 0)
  assert.equal(await empty.page.getByRole('button', { name: '确认方案', exact: true }).count(), 1)
  await empty.context.close()

  const preview = await openProposal({ preview: true })
  await preview.page.getByRole('button', { name: '确认方案', exact: true }).click()
  await preview.page.getByRole('status').filter({ hasText: '预览数据不写入项目' }).waitFor()
  assert.equal(preview.confirmRequests.length, 0)
  assert.equal(await preview.page.getByRole('button', { name: '确认方案', exact: true }).count(), 1)
  await preview.context.close()

  console.log('PASS: project-scoped request payload, confirmed state after success only, error/empty/preview paths')
} finally {
  await browser?.close()
  app.kill()
}
