import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { createReviewConfirmMock, fulfillReviewConfirm, confirmAllReviews } from './helpers/review-confirm-mock.mjs'

// 单模块与组合预览未应用即可拖动：应用直接提交当前预览，不重新求解。
const cwd = fileURLToPath(new URL('../', import.meta.url))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = `http://127.0.0.1:${port}`
const app = spawn(process.execPath, ['.output/server/index.mjs'], { cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' }, stdio: 'ignore' })
let browser
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先 pnpm build；预览服务未就绪')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1700, height: 1100 } })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const source = {
    analysisId: 'PREVIEW-DRAG', analysisRevision: 1, status: 'awaiting_confirm', sourceName: '预览拖动测试.dxf',
    inputMode: 'cad', geometrySource: 'cad_geometry', evidenceKind: 'REAL_SERVICE',
    unitResolution: { canonicalUnit: 'mm', confirmed: true },
    rooms: [{ roomId: 'DRAG-ROOM', label: '开放办公区', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[0, 0], [16000, 0], [16000, 10000], [0, 10000]] }],
  }
  let floorPlanRequests = 0
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  const reviewMock = createReviewConfirmMock()
  await context.route('**/api/v1/**', route => {
    if (fulfillReviewConfirm(reviewMock, route)) return
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/office/projects' && route.request().method() === 'GET') {
      const project = { projectId: 'OFFICE-PROJECT-001', name: '预览拖动测试项目', customerRequirements: '', requirementsRevision: 1, leader: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'LEADER' }, collaborators: [], referenceImages: [], floors: [{ projectId: 'OFFICE-PROJECT-001', floorId: 'OFFICE-PROJECT-001', name: '默认楼层', sortOrder: 1 }] }
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: [project] }) })
    }
    const data = path.endsWith('/floor-plan/PREVIEW-DRAG') ? (floorPlanRequests++ === 0 ? source : null) : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')))
  await page.goto(`${baseUrl}/canvas?analysisId=PREVIEW-DRAG`, { waitUntil: 'networkidle' })
  await page.locator('.review-rooms').getByRole('button', { name: '选择空间 开放办公区', exact: true }).waitFor()
  // 与 e2e-auto-layout 相同：把识别副本转成已校准设计副本，直接进入排版阶段。
  await page.evaluate(() => {
    const draft = JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001'))
    for (const zone of draft.objects.filter(object => object.kind === 'zone')) zone.reviewUnit = 'mm'
    localStorage.setItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001', JSON.stringify(draft))
  })
  await page.goto(`${baseUrl}/canvas?analysisId=PREVIEW-DRAG`, { waitUntil: 'networkidle' })
  await confirmAllReviews(page)
  await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()

  const previewItems = page.locator('[data-preview-id]')
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    window.previewApplyResults = []
    store.$onAction(({ name, args, after }) => {
      if (name !== 'applyLayoutProposal') return
      const [zoneId, proposal] = args
      const zone = store.zones.find(item => item.id === zoneId)
      const existing = store.furnitureForZone(zoneId)
      window.previewApplyDebug = { sourceMatches: proposal.sourceKey === JSON.stringify([zone, existing]), problem: proposal.problem, count: proposal.furniture.length, unique: new Set(proposal.furniture.map(item => item.id)).size === proposal.furniture.length }
      after(result => window.previewApplyResults.push(result))
    })
  })
  // 关闭吸附，保证拖动位移可精确断言。
  await page.getByRole('button', { name: '家具吸附', exact: true }).click()
  const zoom = await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').zoom)
  async function dragFirstPreview(dx, dy) {
    const item = previewItems.first()
    const before = await item.evaluate(el => ({ left: parseFloat(el.style.left), top: parseFloat(el.style.top) }))
    const box = await item.boundingBox()
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width / 2 + dx * zoom, box.y + box.height / 2 + dy * zoom, { steps: 6 })
    await page.mouse.up()
    const after = await item.evaluate(el => ({ left: parseFloat(el.style.left), top: parseFloat(el.style.top) }))
    assert.ok(Math.abs(after.left - before.left - dx) <= 2, `预览横向拖动 ${dx}mm，实际 ${after.left - before.left}`)
    assert.ok(Math.abs(after.top - before.top - dy) <= 2, `预览纵向拖动 ${dy}mm，实际 ${after.top - before.top}`)
    return { after, count: await previewItems.count() }
  }
  async function assertAppliedAtPreview(after, count) {
    const placed = (await state()).objects.filter(object => object.kind === 'furniture')
    assert.equal(placed.length, count, '应用后家具件数与预览一致')
    assert.ok(placed.some(object => Math.abs(object.x - after.left) <= 2 && Math.abs(object.y - after.top) <= 2), `应用位置与拖动后的预览位置一致（期望 x≈${after.left} y≈${after.top}）`)
    assert.equal(await previewItems.count(), 0, '应用后预览清空')
  }

  // 单模块：空空间拖动预览，应用必须采用当前预览快照。
  await page.getByTestId('functional-category-workstation').click()
  await page.getByRole('spinbutton', { name: '目标人数', exact: true }).fill('4')
  await page.waitForFunction(() => document.querySelector('[data-testid="layout-seat-summary"]')?.textContent?.includes('工位 4 · 目标 4'))
  assert.equal(await previewItems.count(), 4, '目标 4 人生成 4 个单模块预览')
  const single = await dragFirstPreview(0, 30)
  await page.getByTestId('preview-manually-adjusted').waitFor()
  assert.equal((await state()).objects.filter(object => object.kind === 'furniture').length, 0, '单模块拖动预览不写入已排版家具')
  await page.getByTestId('apply-layout').click()
  await page.waitForFunction(() => window.previewApplyResults.length === 1)
  assert.equal(await page.evaluate(() => window.previewApplyResults[0]), true, JSON.stringify(await page.evaluate(() => window.previewApplyDebug)))
  await page.waitForFunction(() => (JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects || []).filter(object => object.kind === 'furniture').length === 4)
  await assertAppliedAtPreview(single.after, single.count)

  // 已有 4 件再生成 5 件预览：覆盖用户反馈的“旧排版 + 手动预览 + 应用”场景。
  await page.getByRole('spinbutton', { name: '目标人数', exact: true }).fill('5')
  await page.getByRole('checkbox', { name: '显示排版预览' }).check()
  await page.waitForFunction(() => document.querySelectorAll('[data-preview-id]').length === 5)
  const replanned = await dragFirstPreview(0, -25)
  await page.getByTestId('auto-confirm-recommend').click()
  await page.getByText('当前预览尚未应用，请先应用当前预览，再确认排版', { exact: true }).waitFor()
  await page.getByTestId('apply-layout').click()
  await page.waitForFunction(() => (JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects || []).filter(object => object.kind === 'furniture').length === 5)
  await assertAppliedAtPreview(replanned.after, replanned.count)
  const beforeReload = (await state()).objects.filter(object => object.kind === 'furniture').map(item => [item.x, item.y, item.w, item.h, item.rotation]).sort()
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByTestId('design-workflow-sidebar').waitFor()
  const afterReload = (await state()).objects.filter(object => object.kind === 'furniture').map(item => [item.x, item.y, item.w, item.h, item.rotation]).sort()
  assert.deepEqual(afterReload, beforeReload, '刷新后保留手动调整后的排版几何')
  await page.getByTestId('auto-clear-layout').click()
  await page.waitForFunction(() => !(JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects || []).some(object => object.kind === 'furniture'))

  // 组合布局沿用同一提交语义。
  await page.getByTestId('functional-category-workstation').click()
  await page.getByTestId('functional-category-sofa').click()
  await page.getByTestId('mixed-layout-generate').click()
  await previewItems.first().waitFor()
  // 刷新会恢复默认吸附；组合拖动仍使用无吸附的精确位移。
  await page.getByRole('button', { name: '家具吸附', exact: true }).click()
  const mixed = await dragFirstPreview(60, 30)
  await page.getByTestId('mixed-preview-manually-adjusted').waitFor()

  // 未应用前不写入已排版家具。
  assert.equal((await state()).objects.filter(object => object.kind === 'furniture').length, 0, '拖动预览不写入已排版家具')

  // 应用：已排版家具按拖动后的预览位置生成。
  await page.getByTestId('mixed-layout-apply').click()
  await page.waitForFunction(() => (JSON.parse(localStorage.getItem('rsdp-office-v01:MOCK-USER:OFFICE-PROJECT-001')).objects || []).some(object => object.kind === 'furniture'))
  await assertAppliedAtPreview(mixed.after, mixed.count)

  assert.deepEqual(errors, [], '页面无脚本错误')
  console.log('预览拖动 E2E 通过：单模块空空间/已有排版与组合预览均按手动调整后的快照应用。')
} finally {
  if (browser) await browser.close()
  app.kill()
}
