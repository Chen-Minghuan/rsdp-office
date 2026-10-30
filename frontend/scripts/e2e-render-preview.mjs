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
const app = spawn(process.execPath, ['.output/server/index.mjs'], { cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' }, stdio: 'ignore' })
const picture = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aV1sAAAAASUVORK5CYII=', 'base64')
const zone = { id: 'ROOM-X', kind: 'zone', x: -6000, y: -4000, w: 6000, h: 4000, name: '效果预览办公室', zoneType: 'OPEN_OFFICE', seats: 4, note: '', tone: 'sand', reviewUnit: 'mm', evidenceKind: 'MANUAL', polygon: [[-6000, -4000], [0, -4000], [0, -2000], [-2000, -2000], [-2000, 0], [-6000, 0]] }
const furniture = { id: 'F1', kind: 'furniture', zoneId: zone.id, x: -5500, y: -3500, w: 1200, h: 600, rotation: 90, productId: '', label: '职员直线桌', furnitureTemplateId: 'desk-straight', layoutConfirmed: false }
let saved = { revision: 3, mode: 'design', designZoneId: zone.id, objects: [zone, furniture], files: [], blank: false }
let boundProduct = false
let jobs = [], submissions = [], polls = 0, mode = 'RUNNING', failedSubmission = false, loseFirstResponse = true
const byChange = new Map(), attemptedIds = []
let browser, page
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先 pnpm build；预览服务未就绪')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 }, acceptDownloads: true })
  await context.addInitScript(() => Object.defineProperty(Crypto.prototype, 'randomUUID', { value: undefined, configurable: true }))
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'USER' } } }))
  await context.route('**/api/v1/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname
    const respond = (data, code = 200, message = 'success') => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code, message, data }) })
    if (path.endsWith('/canvas')) {
      if (req.method() === 'PUT') saved = req.postDataJSON()
      return respond(saved)
    }
    if (path.endsWith('/OFFICE-PROJECT-001')) return respond({ projectName: '效果图测试', style: '现代简约' })
    if (path.endsWith('/render-previews')) {
      if (req.method() === 'GET') return respond(jobs)
      const body = req.postDataJSON()
      attemptedIds.push(body.clientChangeId)
      if (byChange.has(body.clientChangeId)) return respond(byChange.get(body.clientChangeId))
      submissions.push(body)
      assert.equal(body.expectedSnapshot.zone.id, zone.id)
      assert.equal(body.expectedSnapshot.zone.polygon.length, 6)
      assert.equal(body.expectedSnapshot.furniture[0].rotation, 90)
      assert.equal(body.expectedSnapshot.furniture[0].productId, boundProduct ? 'SKU-SELECTED' : '')
      if (boundProduct) assert.equal(body.expectedSnapshot.furniture[0].productSelection.product.id, 'SKU-SELECTED')
      assert.equal(body.expectedSnapshot.furniture[0].layoutConfirmed, undefined)
      assert.deepEqual(body.expectedSnapshot.furniture[0].x, saved.objects[1].x)
      const job = { previewId: 'RPRE-' + submissions.length, zoneId: zone.id, zoneName: zone.name, canvasRevision: body.canvasRevision, purpose: 'CONCEPT_PREVIEW',
        model: 'qwen-image-3.0', style: body.style, view: body.view, instructions: body.instructions, snapshot: body.expectedSnapshot,
        status: failedSubmission ? 'FAILED' : 'PENDING', errorMessage: failedSubmission ? '生图鉴权失败，请核对模型调用权限' : null, stale: false,
        layoutContext: {version:'physical-layout-v1',widthMm:6000,depthMm:4000,netAreaM2:20,furnitureCount:1,primaryCount:1,rowCount:1,columnCount:1,wallClearancesMm:{left:500,right:3500,top:500,bottom:700},groupGaps:[],reservedAreas:[],sourceDrawing:{status:'UNAVAILABLE'},openingsKnown:false},
        layoutGuideUrl: '/uploads/render-previews/space-guide.png', productImagesUsed: boundProduct, productReferences: boundProduct ? {version:'selected-products-multiview-v2',items:[{referenceId:'P1',productId:'SKU-SELECTED',name:'所选红色桌',variant:'1200×600',imageUrl:'/uploads/images/product.png',imageSha256:'hash',usedImageCount:3,availableImageCount:3,images:[{viewId:'P1-V1'},{viewId:'P1-V2'},{viewId:'P1-V3'}],furnitureNumbers:[1]}],sheetUrls:['/uploads/render-previews/product-board.png']} : undefined,
        referenceUrl: '/uploads/render-previews/reference.png', createdAt: new Date().toISOString() }
      jobs.unshift(job); byChange.set(body.clientChangeId, job)
      if (loseFirstResponse) { loseFirstResponse = false; return route.abort('failed') }
      return respond(job)
    }
    if (path.includes('/render-previews/RPRE-')) {
      polls++
      if (mode === 'ERROR') return respond(null, 400, '暂时无法查询，请稍后重试')
      const job = jobs.find(item => path.endsWith('/' + item.previewId))
      job.status = mode
      if (mode === 'SUCCEEDED') job.imageUrl = '/uploads/render-previews/result.png'
      return respond(job)
    }
    return respond([])
  })
  await context.route('**/uploads/render-previews/**', route => route.fulfill({ contentType: 'image/png', headers: { 'Access-Control-Allow-Origin': '*' }, body: picture }))
  page = await context.newPage()
  const pageErrors = []
  page.on('pageerror', error => pageErrors.push(error.message))
  const open = async () => {
    await page.getByRole('button', { name: '效果预览', exact: true }).click()
    await page.getByRole('dialog', { name: '单空间效果预览' }).waitFor()
  }
  await page.goto(baseUrl + '/canvas?mode=design&zoneId=ROOM-X', { waitUntil: 'networkidle' })
  await page.getByTestId('functional-category-workstation').click()
  await page.getByTestId('apply-layout').waitFor()
  await open()
  await page.getByText('尚无效果预览', { exact: true }).waitFor()
  await page.getByLabel('效果图风格', { exact: true }).fill('浅木色现代办公室')
  await page.getByLabel('效果图补充偏好').fill('灰色地毯，柔和自然光')
  await page.getByRole('button', { name: '生成效果预览', exact: true }).click()
  await page.getByRole('alert').waitFor()
  await page.getByText('上次提交结果可能未知。', { exact: false }).waitFor()
  await page.getByRole('button', { name: '生成效果预览', exact: true }).click()
  await page.getByRole('button', { name: '当前布局正在生成…', exact: true }).waitFor()
  assert.equal(submissions.length, 1)
  assert.equal(attemptedIds.length, 2)
  assert.equal(attemptedIds[0], attemptedIds[1], '丢失响应后复用生成请求 ID，不重复创建收费任务')
  assert.equal(await page.getByRole('button', { name: '当前布局正在生成…', exact: true }).isDisabled(), true)

  // Close/reload while pending, then edit this room before the late result arrives.
  await page.getByRole('button', { name: '关闭效果预览' }).click()
  saved = JSON.parse(JSON.stringify(saved)); saved.revision++; saved.objects[1].x += 200
  await page.evaluate(data => {
    const local = JSON.parse(localStorage.getItem('rsdp-office-v01') || '{}')
    local.project = { name: '效果图测试', area: 0, headcount: 4, budget: 0, style: '现代简约', description: '' }
    local.canvasMode = 'design'; local.designZoneId = 'ROOM-X'
    local.objects = data.objects; local.canvasRevision = data.revision
    localStorage.setItem('rsdp-office-v01', JSON.stringify(local))
  }, saved)
  mode = 'SUCCEEDED'
  await page.reload({ waitUntil: 'networkidle' })
  await open()
  await page.getByAltText(zone.name + '概念效果图').waitFor()
  await page.getByText('布局已变化，需重新生成', { exact: true }).waitFor()
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')).objects.find(item => item.id === 'F1').x), -5300)
  assert.equal(submissions.length, 1, '恢复查询不得再次提交生图')
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载图片', exact: true }).click()
  assert.match((await download).suggestedFilename(), /概念效果图.*\.png$/)
  await page.getByText('查看生成时的布局参考', { exact: false }).click()
  await page.getByAltText('生成时的空间与家具布局参考图').waitFor()

  failedSubmission = true
  await page.getByLabel('效果图视角').selectOption('INTERIOR')
  await page.getByRole('button', { name: '生成效果预览', exact: true }).click()
  await page.getByText('生图鉴权失败，请核对模型调用权限', { exact: true }).waitFor()
  assert.equal(submissions[1].view, 'INTERIOR')
  failedSubmission = false; mode = 'ERROR'
  await page.getByRole('button', { name: '按当前布局重试生成', exact: true }).click()
  await page.getByText('暂时无法查询，请稍后重试', { exact: false }).waitFor()
  assert.equal(submissions.length, 3)
  mode = 'SUCCEEDED'
  await page.getByRole('button', { name: '重新查询任务', exact: true }).click()
  await page.getByAltText(zone.name + '概念效果图').waitFor()
  await page.getByText('对应当前布局', { exact: true }).waitFor()
  assert.equal(submissions.length, 3, '查询失败后的重试不得创建新任务')
  assert.ok(polls >= 3)
  await page.getByRole('button', {name:'关闭效果预览',exact:true}).click()
  boundProduct = true
  await page.evaluate(() => {
    const store=document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    store.objectUpdate(() => {
      const item=store.objects.find(item=>item.id==='F1')
      item.productId='SKU-SELECTED'
      item.productSelection={product:{id:'SKU-SELECTED',name:'所选红色桌',imageUrl:'/uploads/images/product.png'},rotationDeg:0}
    })
  })
  await open()
  await page.getByTestId('render-selected-products').waitFor()
  await page.getByRole('button',{name:'生成效果预览',exact:true}).click()
  await page.getByTestId('render-product-images-used').waitFor()
  assert.ok((await page.getByTestId('render-product-images-used').innerText()).includes('3 张图片'))
  await page.getByText('查看生成时的空间参考',{exact:true}).click()
  await page.getByAltText('生成时使用的空间立体参考').waitFor()
  await page.getByText('查看本次生成参考的空间与间距',{exact:true}).click()
  assert.ok((await page.getByTestId('render-layout-context').innerText()).includes('6000 × 4000 mm'))
  assert.ok((await page.getByTestId('render-layout-context').innerText()).includes('左 500 mm'))
  assert.equal(submissions.at(-1).expectedSnapshot.furniture[0].productId,'SKU-SELECTED')
  await page.getByText('查看生成时使用的商品图片',{exact:true}).click()
  await page.getByAltText('生成时所选商品外观参考板').waitFor()
  jobs[0].productImagesUsed = false; jobs[0].productReferences = undefined
  await page.getByRole('button',{name:'刷新记录',exact:true}).click()
  await page.getByText('此任务未使用所选商品图片，请重新生成以参考商品外观。',{exact:true}).waitFor()

  assert.deepEqual(pageErrors, [])
  await page.keyboard.press('Escape')
  await page.getByRole('dialog', { name: '单空间效果预览' }).waitFor({ state: 'hidden' })
  assert.equal(await page.getByRole('dialog', { name: '单空间效果预览' }).count(), 0)
  console.log('Render preview mock UI passed: snapshot, pending/reload, stale result, download, failure/retry, query recovery')
} catch (error) {
  throw error
} finally {
  await browser?.close()
  app.kill('SIGTERM')
}
