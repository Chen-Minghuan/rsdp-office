import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { once } from 'node:events'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const cwd = fileURLToPath(new URL('../', import.meta.url))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = 'http://127.0.0.1:' + port
const app = spawn(process.execPath, ['.output/server/index.mjs'], { cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' }, stdio: 'ignore' })
let browser, debugPage
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先 pnpm build；预览服务未就绪')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1600, height: 1100 } })
  await context.addInitScript(() => Object.defineProperty(Crypto.prototype, 'randomUUID', { value: undefined, configurable: true }))
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  const page = await context.newPage(), errors = [], requests = [], saved = []
  debugPage = page
  page.on('pageerror', error => errors.push(error.message))
  let fail = true, hold = false, pending = null
  const source = {
    analysisId: 'TEMPLATE-TEST', analysisRevision: 1, status: 'awaiting_confirm', sourceName: '空间模板测试.dxf',
    inputMode: 'cad', geometrySource: 'cad_geometry', evidenceKind: 'REAL_SERVICE',
    unitResolution: { canonicalUnit: 'mm', confirmed: true }, previewBounds: { minX: 0, minY: 0, maxX: 10500, maxY: 6000 },
    previewUrl: 'data:image/svg+xml;base64,' + Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="1050" height="600"><rect width="1050" height="600" fill="white"/><path d="M0 600H600V200H0Z M650 600H1050V200H850V0H650Z" fill="none" stroke="black" stroke-width="4"/></svg>').toString('base64'),
    rooms: [
      { roomId: 'TPL-A', label: '开放办公区', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[0,0],[6000,0],[6000,4000],[0,4000]] },
      { roomId: 'TPL-L', label: 'L 形会议区', roomType: 'MEETING_ROOM', geometrySource: 'cad_geometry', polygon: [[6500,0],[10500,0],[10500,4000],[8500,4000],[8500,6000],[6500,6000]] },
    ],
  }
  await context.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname, method = route.request().method()
    const respond = (data, code = 200, message = '成功') => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code, message, data }) })
    if (path.endsWith('/space-templates') && method === 'POST') {
      const body = route.request().postDataJSON(); requests.push(body)
      if (fail) return respond(null, 500, '模拟保存失败，请重试')
      const existing = saved.find(item => item.clientChangeId === body.clientChangeId)
      const value = existing || {
        templateId: 'STPL-MOCK-' + (saved.length + 1), projectId: 'OFFICE-PROJECT-001', name: body.name, version: 1,
        sourceCanvasRevision: body.sourceCanvasRevision, sourceAnalysisId: body.scene.sourceSceneId, sourceAnalysisRevision: body.sourceAnalysisRevision,
        contentHash: 'MOCK-HASH-' + saved.length, spaceCount: body.scene.spaces.length, areaM2: body.scene.spaces.reduce((sum, room) => sum + room.derived.areaM2, 0),
        createdAt: '2026-10-15T10:00:00', evidenceKind: 'REAL_SERVICE', scene: structuredClone(body.scene), clientChangeId: body.clientChangeId,
      }
      value.scene.sceneId = value.templateId
      value.scene.spaces.forEach(space => { space.confirmation = { status: 'UNCONFIRMED', confirmationId: null } })
      if (!existing) saved.unshift(value)
      if (hold) { pending = () => respond(value); return }
      return respond(value)
    }
    if (path.endsWith('/space-templates')) return respond(saved.map(({ scene, ...summary }) => summary))
    if (path.includes('/space-templates/')) return respond(saved.find(item => path.endsWith('/' + item.templateId)))
    if (path.endsWith('/floor-plan/TEMPLATE-TEST')) return respond(source)
    return respond(path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : [])
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  const room = async () => (await state()).objects.find(room => room.id === 'TPL-A')
  const open = () => page.getByRole('button', { name: /^(生成空间模板|查看空间模板)$/ }).click()
  const dialog = () => page.getByRole('dialog', { name: '空间模板', exact: true })
  const save = () => dialog().getByRole('button', { name: '保存空间模板', exact: true }).click()
  const close = () => dialog().getByRole('button', { name: '关闭空间模板', exact: true }).click()
  await page.goto(baseUrl + '/canvas?analysisId=TEMPLATE-TEST', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '选择空间 开放办公区', exact: true }).waitFor()
  const initial = (await state()).objects
  await open()
  await dialog().waitFor()
  assert.equal(await dialog().locator('[data-template-space]').count(), 2)
  assert.equal((await dialog().locator('[data-template-space="TPL-L"] path').getAttribute('d')).match(/L/g).length, 5, 'L 形不能改成包围盒')
  await dialog().getByText('24.0 m²', { exact: true }).waitFor()
  await dialog().getByText('20.0 m²', { exact: true }).waitFor()
  assert.deepEqual((await state()).objects, initial, '生成预览不改设计稿')
  await dialog().getByLabel('空间模板名称').fill('总部一层空间模板')
  await save()
  await dialog().getByRole('alert').filter({ hasText: '模拟保存失败' }).waitFor()
  assert.equal((await state()).spaceTemplate, null)
  fail = false
  await save()
  await dialog().getByRole('status').filter({ hasText: '空间模板已保存' }).waitFor()
  assert.equal(requests[0].clientChangeId, requests[1].clientChangeId, '失败重试保留幂等 ID')
  assert.equal((await state()).spaceTemplate.templateId, 'STPL-MOCK-1')
  assert.deepEqual((await state()).objects, initial)
  const downloadEvent = page.waitForEvent('download')
  await dialog().getByRole('button', { name: '导出模板 JSON', exact: true }).click()
  assert.match((await downloadEvent).suggestedFilename(), /总部一层空间模板.*json/)
  await close()
  await page.getByLabel('空间宽度（毫米）').fill('5000')
  await page.getByRole('button', { name: '应用尺寸', exact: true }).click()
  await page.getByText('空间已修改，模板需更新', { exact: false }).waitFor()
  assert.equal((await room()).actualWidthMm, 5000)
  assert.equal(saved[0].scene.spaces[0].designGeometry.outer[1][0], 6000, '编辑草稿不能修改已保存模板')
  await open()
  await dialog().getByLabel('选择已保存空间模板').selectOption('STPL-MOCK-1')
  await dialog().getByText('此模板与当前草稿不同。', { exact: false }).waitFor()
  await dialog().getByRole('button', { name: '用此模板新建布局 →', exact: true }).click()
  await page.getByRole('heading', { name: '无限画布 · 单空间设计', exact: true }).waitFor()
  assert.doesNotMatch(page.url(), /analysisId=TEMPLATE-TEST/, '模板副本不能刷新后被旧识别结果覆盖')
  assert.equal((await state()).spaceTemplate.templateId, 'STPL-MOCK-1')
  assert.equal((await room()).actualWidthMm, 6000)
  assert.equal((await room()).confirmed, false)
  assert.equal((await room()).spaceTemplateId, 'STPL-MOCK-1')
  assert.equal((await state()).objects.find(room => room.id === 'TPL-L').polygon.length, 6)
  await page.getByRole('button', { name: '返回空间总览', exact: true }).first().click()
  await page.getByRole('heading', { name: '无限画布 · 空间核对', exact: true }).waitFor()
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  assert.equal((await room()).actualWidthMm, 5000, '使用模板可撤销恢复此前编辑')
  await open()
  await dialog().getByLabel('选择已保存空间模板').selectOption('STPL-MOCK-1')
  await dialog().getByRole('button', { name: '用此模板新建布局 →', exact: true }).click()
  await page.getByRole('heading', { name: '无限画布 · 单空间设计', exact: true }).waitFor()
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '无限画布 · 单空间设计', exact: true }).waitFor()
  assert.equal((await room()).actualWidthMm, 6000)
  assert.equal((await state()).spaceTemplate.templateId, 'STPL-MOCK-1')
  await page.getByRole('button', { name: '返回空间总览', exact: true }).first().click()
  await page.getByRole('heading', { name: '无限画布 · 空间核对', exact: true }).waitFor()
  await page.getByLabel('空间宽度（毫米）').fill('5000')
  await page.getByRole('button', { name: '应用尺寸', exact: true }).click()
  await open()
  await dialog().getByLabel('选择已保存空间模板').selectOption('STPL-MOCK-1')
  await dialog().getByRole('button', { name: '用此模板新建布局 →', exact: true }).waitFor()
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
  await close()
  await page.setViewportSize({ width: 1600, height: 1100 })

  // Closing a pending save then editing cannot attach the late snapshot to the new draft.
  await open()
  await dialog().getByRole('button', { name: '按当前空间重新生成', exact: true }).click()
  hold = true
  await save()
  while (!pending) await new Promise(resolve => setTimeout(resolve, 10))
  await close()
  await page.getByLabel('空间宽度（毫米）').fill('4800')
  await page.getByRole('button', { name: '应用尺寸', exact: true }).click()
  const beforeLate = (await state()).spaceTemplate
  await pending()
  assert.equal((await room()).actualWidthMm, 4800)
  assert.deepEqual((await state()).spaceTemplate, beforeLate, '过期保存结果不能绑定新草稿')
  assert.deepEqual(errors, [])
  console.log('空间模板 UI 通过：彩色真实轮廓/面积、保存失败与幂等重试、历史模板、独立布局/撤销/刷新、导出、移动布局及过期保存保护。全部 API 模拟。')
} catch (failure) {
  if (debugPage) {
    console.error('Template failure state:', await debugPage.evaluate(() => {
      const state = JSON.parse(localStorage.getItem('rsdp-office-v01') || '{}')
      return { source: state.floorPlan?.analysisId, mode: state.canvasMode, template: state.spaceTemplate?.templateId, rooms: state.objects?.filter(object => object.kind === 'zone').map(room => ({ id: room.id, unit: room.reviewUnit, width: room.actualWidthMm })), text: document.body.innerText.slice(-1800) }
    }).catch(() => null))
  }
  throw failure
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
