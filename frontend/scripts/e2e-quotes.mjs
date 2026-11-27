import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdir } from 'node:fs/promises'
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
const outDir = process.env.OUT_DIR || '/tmp/rsdp-quotation'
let browser
try {
  let ready = false
  for (let attempt = 0; attempt < 75; attempt++) {
    try { if ((await fetch(baseUrl)).ok) { ready = true; break } } catch {}
    if (app.exitCode !== null) throw new Error('测试预览服务启动失败，请先执行 pnpm build')
    await new Promise(resolve => setTimeout(resolve, 200))
  }
  assert.ok(ready)
  await mkdir(outDir, { recursive: true })
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 }, acceptDownloads: true })
  const page = await context.newPage()
  const errors = []
  const exportBodies = []
  const confirmBodies = []
  let exportFails = false
  let confirmFails = false
  let downloads = 0
  // 楼层方案确认状态：null = 从未确认；current:false = 已失效；current:true = 有效
  let proposalConfirmation = null
  page.on('pageerror', error => errors.push(error.message))
  page.on('download', () => { downloads++ })

  const project = {
    projectId: 'PROJ-1', name: '报价测试项目', customerRequirements: '', requirementsRevision: 1,
    leader: { userId: 'DEALER-1', username: 'dealer', displayName: '经销商主号', role: 'LEADER' },
    collaborators: [], referenceImages: [],
    floors: [{ projectId: 'PROJ-1', floorId: 'FLOOR-1', name: '1F', sortOrder: 1 }],
  }
  const preview = {
    projectId: 'PROJ-1', projectName: '报价测试项目', pricingMode: 'COEFFICIENT', pricingValue: 1.2,
    generatedAt: '2026-11-18T15:00:00',
    floors: [{
      floorId: 'FLOOR-1', floorName: '1F', factoryTotal: 344, dealTotal: 412.8,
      spaces: [{
        zoneId: 'Z1', zoneName: '开放办公区', factoryTotal: 344, dealTotal: 412.8,
        lines: [{
          productId: 'V-DESK', rspuId: 'RSPU-1', productName: '1.2米单人位职员台', imageUrl: null,
          variant: '默认规格 / PB', qty: 1, factoryPrice: 344, unitPrice: 412.8, subtotal: 412.8, issue: null,
        }],
      }],
    }],
    totals: { lineCount: 1, missingFactoryPriceLines: 0, factoryTotal: 344, dealTotal: 412.8 },
  }

  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: {
    userId: 'DEALER-1', username: 'dealer', displayName: '经销商主号', role: 'DEALER',
  } } }))
  await context.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const respond = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
    if (path === '/api/v1/office/projects' && request.method() === 'GET') return respond([project])
    if (path === '/api/v1/office/projects/PROJ-1/quotation/preview') return respond(preview)
    if (path === '/api/v1/office/projects/PROJ-1/quotation/latest') return respond(null)
    if (path === '/api/v1/office/projects/PROJ-1/quotation/export' && request.method() === 'POST') {
      exportBodies.push(request.postDataJSON())
      if (exportFails) return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 400, message: '模拟导出失败' }) })
      return route.fulfill({
        contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        body: Buffer.from('mock xlsx content'),
      })
    }
    if (path === '/api/v1/office/projects/PROJ-1/quotation/confirm' && request.method() === 'POST') {
      const body = request.postDataJSON()
      confirmBodies.push(body)
      if (confirmFails) return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 400, message: '模拟确认失败' }) })
      return respond({
        quotationId: 'QT-MOCK-1', projectId: 'PROJ-1', projectName: '报价测试项目', revision: 1, status: 'CONFIRMED',
        pricingMode: body.pricingMode, pricingValue: body.pricingValue, goodsTotal: 412.8,
        serviceFee: 100, logisticsFee: 50, fees: body.fees, totalAmount: 613.4,
        lineCount: 1, missingPriceLines: 0, createdBy: 'DEALER-1', createdAt: '2026-11-18T15:01:00', lines: [],
      })
    }
    if (path === '/api/v1/office/projects/PROJ-1') return respond(project)
    if (path.endsWith('/proposal-confirmation')) return respond(proposalConfirmation)
    if (path.endsWith('/canvas') || path.endsWith('/workspace/me')) return respond(null)
    return respond([])
  })

  await page.goto(`${baseUrl}/workspace/quotes`, { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '商品报价', exact: true }).waitFor()
  await page.getByText('1.2米单人位职员台', { exact: true }).waitFor()

  // 方案确认门禁：从未确认 → 已失效 → 有效，三种状态分别断言横幅与按钮
  const confirmButton = page.getByRole('button', { name: '确认报价', exact: true })
  const blockers = page.getByTestId('quote-confirmation-blockers')
  await blockers.waitFor()
  assert.match(await blockers.innerText(), /1F/)
  assert.match(await blockers.innerText(), /尚未确认方案/)
  assert.match(await blockers.innerText(), /去项目清单确认/)
  assert.equal(await confirmButton.isDisabled(), true, '楼层方案未确认时确认报价按钮禁用')

  proposalConfirmation = { confirmationId: 'PROP-1', canvasRevision: 1, confirmedAt: '2026-11-18T14:00:00', current: false }
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByText('1.2米单人位职员台', { exact: true }).waitFor()
  await blockers.waitFor()
  assert.match(await blockers.innerText(), /已失效，需重新确认/)
  assert.equal(await confirmButton.isDisabled(), true, '方案确认已失效时确认报价按钮禁用')

  proposalConfirmation = { confirmationId: 'PROP-1', canvasRevision: 2, confirmedAt: '2026-11-18T14:30:00', current: true }
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByText('1.2米单人位职员台', { exact: true }).waitFor()
  await confirmButton.waitFor()
  assert.equal(await blockers.count(), 0, '方案确认有效时不显示阻塞横幅')
  assert.equal(await confirmButton.isDisabled(), false, '方案确认有效后确认报价按钮启用')

  await page.getByLabel('费用金额 1', { exact: true }).fill('100')
  await page.getByLabel('费用金额 2', { exact: true }).fill('50')
  await page.getByRole('button', { name: '＋ 添加其他费用', exact: true }).click()
  await page.getByRole('button', { name: '＋ 添加其他费用', exact: true }).click()
  await page.getByLabel('费用名称 3', { exact: true }).fill('额外服务费')
  await page.getByLabel('费用金额 3', { exact: true }).fill('20.10')
  await page.getByLabel('费用名称 4', { exact: true }).fill('加急费')
  await page.getByLabel('费用金额 4', { exact: true }).fill('30.50')
  await page.getByRole('button', { name: '＋ 添加其他费用', exact: true }).click()
  await page.getByLabel('费用名称 5', { exact: true }).fill('待删除费用')
  await page.getByLabel('费用金额 5', { exact: true }).fill('999')
  await page.getByRole('button', { name: '删除费用 5', exact: true }).click()
  assert.equal(await page.getByLabel('费用名称 5', { exact: true }).count(), 0)
  assert.match(await page.locator('.sticky.bottom-0').innerText(), /¥613\.40/)

  await page.getByRole('button', { name: '确认报价', exact: true }).click()
  const dialog = page.getByTestId('quotation-preview-dialog')
  await dialog.waitFor()
  assert.equal(confirmBodies.length, 0)
  assert.match(await dialog.innerText(), /额外服务费/)
  assert.match(await dialog.innerText(), /加急费/)
  assert.match(await dialog.innerText(), /¥613\.40/)
  const box = await dialog.boundingBox()
  assert.ok(Math.abs(box.x + box.width / 2 - 800) < 2, '弹窗应水平居中')
  assert.deepEqual(await dialog.locator('footer button').allTextContents(), ['确认', '取消'])
  const downloadBox = await dialog.getByRole('button', { name: '下载 Excel', exact: true }).boundingBox()
  assert.ok(downloadBox.x > box.x + box.width / 2, '下载按钮位于弹窗右上方')
  await page.screenshot({ path: `${outDir}/01-quotation-dialog.png`, animations: 'disabled' })

  await page.getByLabel('费用金额 1', { exact: true }).evaluate(input => {
    input.value = '999'
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
  assert.match(await dialog.innerText(), /¥613\.40/, '弹窗金额不随背景编辑变化')
  const expectedPayload = {
    pricingMode: 'COEFFICIENT', pricingValue: 1.2,
    fees: [
      { name: '服务费', amount: 100 },
      { name: '物流搬运安装费', amount: 50 },
      { name: '额外服务费', amount: 20.1 },
      { name: '加急费', amount: 30.5 },
    ],
    lines: [{ floorId: 'FLOOR-1', zoneId: 'Z1', zoneName: '开放办公区', productId: 'V-DESK', qty: 1, unitPrice: 412.8 }],
  }
  const downloadPromise = page.waitForEvent('download')
  await dialog.getByRole('button', { name: '下载 Excel', exact: true }).click()
  const download = await downloadPromise
  assert.match(download.suggestedFilename(), /报价测试项目-报价单\.xlsx$/)
  assert.deepEqual(exportBodies[0], expectedPayload)
  await dialog.waitFor()

  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await page.getByText(/已确认报价 QT-MOCK-1/).waitFor()
  assert.equal(confirmBodies.length, 1)
  assert.deepEqual(confirmBodies[0], expectedPayload)
  assert.equal(await dialog.count(), 0)

  await page.getByRole('button', { name: '确认报价', exact: true }).click()
  await dialog.waitFor()
  exportFails = true
  await dialog.getByRole('button', { name: '下载 Excel', exact: true }).click()
  await dialog.getByRole('alert').filter({ hasText: '模拟导出失败' }).waitFor()
  assert.equal(downloads, 1, 'JSON 错误不能作为 Excel 下载')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  assert.equal(await dialog.count(), 0)
  assert.equal(await page.getByLabel('费用金额 1', { exact: true }).inputValue(), '999', '取消保留页内编辑')

  await page.getByRole('button', { name: '确认报价', exact: true }).click()
  await dialog.waitFor()
  confirmFails = true
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await dialog.getByRole('alert').filter({ hasText: '模拟确认失败' }).waitFor()
  assert.equal(await dialog.count(), 1, '确认失败保留弹窗')
  confirmFails = false
  await dialog.getByRole('button', { name: '取消', exact: true }).click()

  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '确认报价', exact: true }).click()
  await dialog.waitFor()
  assert.equal(await dialog.getByRole('button', { name: '下载 Excel', exact: true }).isVisible(), true)
  assert.equal(await dialog.getByRole('button', { name: '确认', exact: true }).isVisible(), true)
  await page.screenshot({ path: `${outDir}/02-quotation-mobile.png`, animations: 'disabled' })
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  assert.deepEqual(errors, [])
  console.log('报价 UI 通过：方案确认门禁（未确认/已失效禁用确认）、多项费用增删与合计、居中 Excel 预览、冻结草稿、下载/确认同载荷、取消与失败保留、窄屏可用。全部 API 模拟。')
} catch (failure) {
  throw failure
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
