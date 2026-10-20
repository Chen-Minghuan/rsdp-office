import { chromium } from 'playwright'
import { mkdir } from 'node:fs/promises'

const baseUrl = process.env.BASE_URL || 'http://localhost:3000'
const outDir = process.env.OUT_DIR || './screenshots'
await mkdir(outDir, { recursive: true })

let pending = [
  { rspuId: 'P-001', rspuCode: 'PR-001', productName: '批量审核测试商品1', primaryImageUrl: null, retailPrice: 100, createdAt: '2026-10-15T08:00:00' },
  { rspuId: 'P-002', rspuCode: 'PR-002', productName: '批量审核测试商品2', primaryImageUrl: null, retailPrice: 200, createdAt: '2026-10-15T08:00:00' },
  { rspuId: 'P-003', rspuCode: 'PR-003', productName: '批量审核测试商品3', primaryImageUrl: null, retailPrice: 300, createdAt: '2026-10-15T08:00:00' },
  { rspuId: 'P-004', rspuCode: 'PR-004', productName: '批量审核测试商品4', primaryImageUrl: null, retailPrice: 400, createdAt: '2026-10-15T08:00:00' },
  { rspuId: 'P-005', rspuCode: 'PR-005', productName: '批量审核测试商品5', primaryImageUrl: null, retailPrice: 500, createdAt: '2026-10-15T08:00:00' },
]
const calls = { batchApprove: [], batchReject: [], approveAll: 0 }

const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.route('**/api/v1/**', route => {
  const url = new URL(route.request().url())
  const path = url.pathname
  const method = route.request().method()
  const ok = data => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: '成功', data }) })
  if (path.endsWith('/products/audit/pending') && method === 'GET') return ok(pending)
  if (path.endsWith('/products/audit/batch-approve') && method === 'POST') {
    const body = route.request().postDataJSON()
    calls.batchApprove.push(body.rspuIds)
    const set = new Set(body.rspuIds)
    const processed = pending.filter(p => set.has(p.rspuId)).length
    pending = pending.filter(p => !set.has(p.rspuId))
    return ok({ processedCount: processed, skippedCount: body.rspuIds.length - processed })
  }
  if (path.endsWith('/products/audit/batch-reject') && method === 'POST') {
    const body = route.request().postDataJSON()
    calls.batchReject.push(body)
    const set = new Set(body.rspuIds)
    const processed = pending.filter(p => set.has(p.rspuId)).length
    pending = pending.filter(p => !set.has(p.rspuId))
    return ok({ processedCount: processed, skippedCount: body.rspuIds.length - processed })
  }
  if (path.endsWith('/products/audit/approve-all') && method === 'POST') {
    calls.approveAll += 1
    const processed = pending.length
    pending = []
    return ok({ processedCount: processed, skippedCount: 0 })
  }
  return ok([])
})
const page = await context.newPage()

function assert(cond, message) {
  if (!cond) throw new Error(`断言失败: ${message}`)
  console.log(`  ✓ ${message}`)
}

console.log('Step 1: 打开商品审核页')
await page.goto(`${baseUrl}/products/audit`, { waitUntil: 'networkidle' })
await page.waitForSelector('h1:has-text("商品审核")', { timeout: 15000 })
await page.waitForSelector('text=批量审核测试商品5', { timeout: 10000 })
assert(await page.locator('button:has-text("一键全部通过（5）")').isVisible(), '显示一键全部通过按钮与待审核数量')
await page.screenshot({ path: `${outDir}/batch-audit-01-pending.png` })

console.log('Step 2: 勾选 2 件商品并批量通过')
await page.getByRole('checkbox', { name: '选择批量审核测试商品1' }).check()
await page.getByRole('checkbox', { name: '选择批量审核测试商品2' }).check()
assert(await page.locator('button:has-text("批量通过（2）")').isVisible(), '显示批量通过（2）')
assert(await page.locator('button:has-text("批量驳回（2）")').isVisible(), '显示批量驳回（2）')
await page.locator('button:has-text("批量通过（2）")').click()
await page.waitForSelector('text=已通过 2 件商品', { timeout: 10000 })
assert(calls.batchApprove.length === 1 && calls.batchApprove[0].length === 2, '批量通过接口被调用一次且携带 2 个商品 ID')
await page.waitForSelector('text=待审核', { timeout: 5000 })
assert(await page.locator('button:has-text("一键全部通过（3）")').isVisible(), '批量通过后列表刷新为剩余 3 件')
await page.screenshot({ path: `${outDir}/batch-audit-02-batch-approved.png` })

console.log('Step 3: 表头全选并批量驳回')
await page.getByRole('checkbox', { name: '选择全部待审核商品' }).check()
assert(await page.locator('button:has-text("批量驳回（3）")').isVisible(), '表头全选后显示批量驳回（3）')
await page.locator('button:has-text("批量驳回（3）")').click()
await page.waitForSelector('h3:has-text("驳回商品（3 件）")', { timeout: 5000 })
await page.fill('textarea', '信息缺失')
await page.locator('button:has-text("确认驳回")').click()
await page.waitForSelector('text=已驳回 3 件商品', { timeout: 10000 })
assert(calls.batchReject.length === 1 && calls.batchReject[0].rspuIds.length === 3 && calls.batchReject[0].remark === '信息缺失', '批量驳回接口携带 3 个 ID 与驳回原因')
assert(await page.locator('h2:has-text("暂无待审核商品")').isVisible(), '全部驳回后显示空列表')
await page.screenshot({ path: `${outDir}/batch-audit-03-batch-rejected.png` })

console.log('Step 4: 重新载入数据并一键全部通过')
pending = [
  { rspuId: 'P-101', rspuCode: 'PR-101', productName: '一键审核测试商品1', primaryImageUrl: null, retailPrice: 10, createdAt: '2026-10-15T08:00:00' },
  { rspuId: 'P-102', rspuCode: 'PR-102', productName: '一键审核测试商品2', primaryImageUrl: null, retailPrice: 20, createdAt: '2026-10-15T08:00:00' },
]
await page.reload({ waitUntil: 'networkidle' })
await page.waitForSelector('text=一键审核测试商品2', { timeout: 10000 })
await page.locator('button:has-text("一键全部通过（2）")').click()
await page.waitForSelector('h3:has-text("一键通过全部待审核商品？")', { timeout: 5000 })
await page.screenshot({ path: `${outDir}/batch-audit-04-approve-all-dialog.png` })
await page.locator('button:has-text("确认全部通过")').click()
await page.waitForSelector('text=已通过 2 件待审核商品', { timeout: 10000 })
assert(calls.approveAll === 1, '一键全部通过接口被调用一次')
assert(await page.locator('h2:has-text("暂无待审核商品")').isVisible(), '一键通过后显示空列表')
await page.screenshot({ path: `${outDir}/batch-audit-05-all-approved.png` })

console.log('全部断言通过')
await browser.close()
