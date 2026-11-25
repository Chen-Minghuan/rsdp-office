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
const floorId = 'OFLR-RENDER'
const projectId = 'OPRJ-RENDER'
const storageKey = 'rsdp-office-v01:MOCK-USER:' + floorId
const zone = { id: 'ROOM-X', kind: 'zone', x: -6000, y: -4000, w: 6000, h: 4000, name: '效果预览办公室', zoneType: 'OPEN_OFFICE', seats: 4, note: '', tone: 'sand', reviewUnit: 'mm', reviewConfirmation: { status: 'CONFIRMED' }, evidenceKind: 'MANUAL', polygon: [[-6000, -4000], [0, -4000], [0, -2000], [-2000, -2000], [-2000, 0], [-6000, 0]] }
const furniture = { id: 'F1', kind: 'furniture', zoneId: zone.id, x: -5500, y: -3500, w: 1200, h: 600, rotation: 90, productId: '', label: '职员直线桌', furnitureTemplateId: 'desk-straight', layoutConfirmed: false }
let saved = { revision: 3, mode: 'design', designZoneId: zone.id, objects: [zone, furniture], files: [], blank: false }
let boundProduct = false
let jobs = [], submissions = [], polls = 0, mode = 'RUNNING', failedSubmission = false, loseFirstResponse = true
let spacingReads = 0
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
    if (path === '/api/v1/office/projects') return respond([{ projectId, name: '效果图测试', leader: { userId: 'MOCK-USER', role: 'LEADER', permissions: ['READ', 'EDIT'] }, floors: [{ projectId, floorId, name: '1F' }] }])
    if (path.endsWith('/canvas')) {
      if (req.method() === 'PUT') saved = req.postDataJSON()
      return respond(saved)
    }
    if (path.endsWith('/render-previews/layout-spacing')) {
      assert.equal(req.method(), 'GET')
      assert.equal(url.searchParams.get('zoneId'), zone.id)
      spacingReads++
      const snapshot = {
        zone: { id: zone.id, name: zone.name, zoneType: zone.zoneType, reviewUnit: zone.reviewUnit, evidenceKind: zone.evidenceKind, polygon: zone.polygon },
        furniture: [{ id: furniture.id, zoneId: zone.id, x: furniture.x + (spacingReads === 1 ? 1 : 0), y: furniture.y,
          w: furniture.w, h: furniture.h, rotation: furniture.rotation, label: furniture.label, furnitureTemplateId: furniture.furnitureTemplateId,
          productId: furniture.productId }],
      }
      return respond({ snapshot, spacing: { version: 'furniture-spacing-v1', unit: 'mm', modules: [{ referenceId: 'F1', furnitureId: 'F1' }],
        adjacent: [], joinedRows: [], description: '' } })
    }
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
      assert.equal(body.viewImage, undefined, '所有入口均使用后端45°家具定位结构图')
      assert.equal(body.view, 'ISOMETRIC')
      const job = { previewId: 'RPRE-' + submissions.length, zoneId: zone.id, zoneName: zone.name, canvasRevision: body.canvasRevision, purpose: 'CONCEPT_PREVIEW',
        model: 'qwen-image-3.0', style: body.style, view: body.view, instructions: body.instructions, snapshot: body.expectedSnapshot,
        generationTiming: {requestStartedAt:Date.now()-65000,submittedAt:Date.now()-60000,providerCompletedAt:Date.now()-5000,readyAt:Date.now(),totalMs:65000,preparationMs:1000,submitMs:4000,providerMs:55000,downloadMs:5000},
        generationExperiment: body.promptMode ? {promptMode:body.promptMode,promptVersion:body.promptMode==='CONCISE_V11'?'visual-task-v1':'technical-v10',referenceMode:body.referenceMode,promptExtend:body.promptExtend,promptExtendMode:body.promptExtend?'direct':null,seed:body.seed,sampleGroup:body.promptMode==='TECHNICAL_V10'?(body.promptExtend?'E':'A'):body.referenceMode==='GUIDE_PRODUCT'?'C':body.promptExtend?'D':'B',sampleIndex:body.sampleIndex} : undefined,
        status: failedSubmission ? 'FAILED' : 'PENDING', errorMessage: failedSubmission ? '生图鉴权失败，请核对模型调用权限' : null, stale: false,
        layoutContext: {version:'physical-layout-v1',widthMm:6000,depthMm:4000,netAreaM2:20,furnitureCount:1,primaryCount:1,rowCount:1,columnCount:1,wallClearancesMm:{left:500,right:3500,top:500,bottom:700},groupGaps:[],reservedAreas:[],sourceDrawing:{status:'UNAVAILABLE'},openingsKnown:false},
        layoutGuideUrl: '/uploads/render-previews/space-guide.png', planGuideUrl: '/uploads/render-previews/reference.png', productImagesUsed: boundProduct,
        productUnderstanding: boundProduct ? {status:'SUCCEEDED',agentModel:'qwen3-vl-max',generatedCount:1,cacheHits:0,items:[{productId:'SKU-SELECTED',status:'GENERATED',warnings:[]}]} : {status:'NOT_REQUIRED',generatedCount:0,cacheHits:0},
        productReferences: boundProduct ? {
          version:'selected-products-multiview-v10-clean-spatial-observer',
          furnitureBindings:[{referenceId:'F1',furnitureId:'F1',type:'职员桌',location:'空间中间中部',centerX:0,centerY:0,rotation:90,productId:'SKU-SELECTED',productReferenceId:'P1'}],
          spatialControl:{version:'spatial-control-v1',room:{planPolygon1000:[[0,0],[1000,0],[1000,1000],[0,1000]],guidePolygon1000:[[0,0],[1000,0],[1000,1000],[0,1000]]},layoutSemantics:[{description:'严格保持图2所示各家具的相对位置、排列连线和实际间距，不重新组织布局。'}],furniture:[{F:'F1',furnitureId:'F1',P:'P1',worldCenterMm:[0,0],worldSizeMm:[1200,600],worldGroundPolygon:[[-300,-600],[300,-600],[300,600],[-300,600]],rotation:90,heightMm:740,plan:{groundCenter1000:[500,500],groundPolygon1000:[[450,400],[550,400],[550,600],[450,600]]},guide:{groundCenter1000:[500,500],groundPolygon1000:[[450,400],[550,400],[550,600],[450,600]]}}]},
          items:[{referenceId:'P1',productId:'SKU-SELECTED',name:'所选红色桌',variant:'1200×600',imageUrl:'/uploads/images/product.png',imageSha256:'hash',usedImageCount:1,availableImageCount:3,candidateImageCount:3,quantity:1,agentStatus:'GENERATED',images:[{viewId:'IMG-abc'}],candidateImages:[{viewId:'IMG-abc'}],visualProfile:{schemaVersion:'product-visual-profile-v2',agentModel:'qwen3-vl-max',cacheKey:'abcdef1234567890',appearancePrompt:'保持单侧柜体',uncertainties:[],views:[{imageId:'IMG-abc',imageSha256:'hash',view:'FRONT_3Q',imageComposition:'SINGLE_PRODUCT',target:{status:'IDENTIFIED',category:'办公桌',description:'红色单侧柜桌',bbox:[],confidence:0.95},containsNonTargetProducts:false,requiresSubjectIsolation:false,originalImageSuitableForDirectGeneration:true,confidence:0.95,wholeProductVisible:true,suitableAsPrimary:true,warnings:[]}]},furnitureNumbers:[1],furnitureReferenceIds:['F1']}],bindingGuideUrl:'/uploads/render-previews/space-guide-labelled.png',sheetUrls:['/uploads/render-previews/product-board.png']
        } : undefined,
        qualityAudit: { status: 'PASS', furnitureCount: { expected: 1, observed: 1, status: 'PASS' }, layout: { status: 'PASS' }, items: [] },
        geometryAudit: boundProduct ? {version:'geometry-audit-v1',status:'PASS',composition:{status:'PASS',cameraMatchesGuide:true},room:{status:'PASS',iou:1},furnitureCount:{expected:1,observed:1,status:'PASS'},items:[{referenceId:'F1',productReferenceId:'P1',detectionId:'D1',status:'PASS',centerErrorRatio:.01,groundPolygonIou:.9,confidence:.95}]} : undefined,
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
    await page.getByRole('button', { name: 'AI 效果图', exact: true }).click()
    await page.getByRole('dialog', { name: '单空间效果预览' }).waitFor()
  }
  await page.goto(baseUrl + '/canvas?mode=design&zoneId=ROOM-X&floorId=' + floorId + '&renderExperiment=1', { waitUntil: 'networkidle' })
  await page.getByTestId('functional-category-workstation').click()
  await page.getByTestId('apply-layout').waitFor()
  await open()
  await page.getByText('尚无效果预览', { exact: true }).waitFor()
  await page.getByTestId('render-experiment-panel').waitFor()
  await page.getByRole('button', { name: '测量已保存的家具间距' }).click()
  await page.getByText('当前排版与已保存版本不同，请保存后重新测量').waitFor()
  await page.getByRole('button', { name: '测量已保存的家具间距' }).click()
  await page.getByTestId('render-current-spacing').getByText('1 个模块 · 0 对相贴模块').waitFor()
  assert.equal(submissions.length, 0, '测量净距不会提交生图')
  await page.getByLabel('效果图风格', { exact: true }).fill('浅木色现代办公室')
  await page.getByLabel('效果图补充偏好').fill('灰色地毯，柔和自然光')
  await page.getByRole('button', { name: '生成效果预览', exact: true }).click()
  await page.getByRole('alert').waitFor()
  await page.getByText('上次提交结果可能未知。', { exact: false }).waitFor()
  await page.getByRole('button', { name: '生成效果预览', exact: true }).click()
  await page.getByRole('button', { name: '当前布局正在生成…', exact: true }).waitFor()
  assert.equal(submissions.length, 1)
  assert.equal(submissions[0].promptMode, 'TECHNICAL_V10')
  assert.equal(submissions[0].referenceMode, 'GUIDE_PLAN_PRODUCT')
  assert.equal(submissions[0].promptExtend, true)
  assert.equal(submissions[0].seed, 1101)
  assert.equal(attemptedIds.length, 2)
  assert.equal(attemptedIds[0], attemptedIds[1], '丢失响应后复用生成请求 ID，不重复创建收费任务')
  assert.equal(await page.getByRole('button', { name: '当前布局正在生成…', exact: true }).isDisabled(), true)

  // Close/reload while pending, then edit this room before the late result arrives.
  await page.getByRole('button', { name: '关闭效果预览' }).click()
  saved = JSON.parse(JSON.stringify(saved)); saved.revision++; saved.objects[1].x += 200
  await page.evaluate(({ data, key }) => {
    const local = JSON.parse(localStorage.getItem(key) || '{}')
    local.canvasMode = 'design'; local.designZoneId = 'ROOM-X'
    local.objects = data.objects; local.canvasRevision = data.revision
    localStorage.setItem(key, JSON.stringify(local))
  }, { data: saved, key: storageKey })
  mode = 'SUCCEEDED'
  await page.reload({ waitUntil: 'networkidle' })
  await open()
  await page.getByAltText(zone.name + '概念效果图').waitFor()
  await page.getByText('布局与商品参考检查通过', { exact: true }).waitFor()
  await page.getByTestId('render-generation-timing').waitFor()
  assert.ok((await page.getByTestId('render-generation-timing').innerText()).includes('本次耗时'))
  await page.getByText('布局已变化，需重新生成', { exact: true }).waitFor()
  assert.equal(await page.evaluate(key => JSON.parse(localStorage.getItem(key)).objects.find(item => item.id === 'F1').x, storageKey), -5300)
  assert.equal(submissions.length, 1, '恢复查询不得再次提交生图')
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载图片', exact: true }).click()
  assert.match((await download).suggestedFilename(), /概念效果图.*\.png$/)
  await page.getByText('查看实际参与生图的图2 · 无编号平面图', { exact: true }).click()
  await page.getByAltText('实际参与生图的无编号平面控制图').waitFor()

  failedSubmission = true

  await page.getByRole('button', { name: '生成效果预览', exact: true }).click()
  await page.getByText('生图鉴权失败，请核对模型调用权限', { exact: true }).waitFor()
  assert.equal(submissions[1].view, 'ISOMETRIC')
  failedSubmission = false; mode = 'ERROR'
  await page.getByRole('button', { name: '按当前布局重试生成', exact: true }).click()
  await page.getByText('暂时无法查询，请稍后重试', { exact: false }).waitFor()
  assert.equal(submissions.length, 3)
  mode = 'SUCCEEDED'
  await page.getByRole('button', { name: '重新查询任务', exact: true }).click()
  await page.getByAltText(zone.name + '概念效果图').waitFor()
  await page.getByText('布局与商品参考检查通过', { exact: true }).waitFor()
  await page.getByTestId('render-generation-timing').waitFor()
  assert.ok((await page.getByTestId('render-generation-timing').innerText()).includes('本次耗时'))
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
  await page.getByLabel('生图实验组', { exact: true }).selectOption('E')
  await page.getByRole('button',{name:'生成效果预览',exact:true}).click()
  await page.getByTestId('render-product-images-used').waitFor()
  assert.ok((await page.getByTestId('render-product-images-used').innerText()).includes('1 张图片'))
  await page.getByText('查看实际参与生图的图1 · 无编号45°空间参考',{exact:true}).click()
  await page.getByAltText('实际参与生图的无编号空间立体参考').waitFor()
  await page.getByText('查看本次生成参考的空间与间距',{exact:true}).click()
  assert.ok((await page.getByTestId('render-layout-context').innerText()).includes('6000 × 4000 mm'))
  assert.ok((await page.getByTestId('render-layout-context').innerText()).includes('左 500 mm'))
  assert.equal(submissions.at(-1).expectedSnapshot.furniture[0].productId,'SKU-SELECTED')
  assert.equal(submissions.at(-1).promptMode, 'TECHNICAL_V10')
  assert.equal(submissions.at(-1).promptExtend, true)
  assert.equal(jobs[0].generationExperiment.sampleGroup, 'E')
  assert.ok((await page.getByTestId('render-generation-timing').innerText()).includes('Direct改写包含在本次任务耗时中'))
  await page.getByText('查看本次 F/P 商品映射与地面坐标',{exact:true}).click()
  assert.ok((await page.getByTestId('render-fp-bindings').innerText()).includes('F1 → P1'))
  await page.getByTestId('render-product-agent').waitFor()
  assert.ok((await page.getByTestId('render-product-agent').innerText()).includes('本次使用了商品理解智能体'))
  await page.getByText('查看商品理解与选图结果',{exact:true}).click()
  assert.ok((await page.getByTestId('render-product-agent-details').innerText()).includes('实际使用 1 张'))
  assert.ok((await page.getByTestId('render-product-agent-details').innerText()).includes('保持单侧柜体'))
  assert.ok((await page.getByTestId('render-product-agent-details').innerText()).includes('单品参考'))
  await page.getByText('查看生成时使用的图3 · 商品图片',{exact:true}).click()
  await page.getByAltText('生成时所选商品外观参考板').waitFor()
  assert.equal(jobs[0].productReferences.version, 'selected-products-multiview-v10-clean-spatial-observer')
  await page.getByTestId('render-plan-guide').click()
  await page.getByAltText('实际参与生图的无编号平面控制图').waitFor()
  await page.getByTestId('render-binding-guide').click()
  await page.getByAltText('仅供商品审核和人工追溯的带F编号空间参考').waitFor()
  await page.getByTestId('render-layout-semantics').click()
  assert.ok((await page.getByTestId('render-layout-semantics').innerText()).includes('排列连线'))
  assert.ok((await page.getByTestId('render-geometry-audit').innerText()).includes('中心偏移 1.0%'))
  assert.equal(jobs[0].productReferences.sheetUrls.length, 1)
  assert.ok(jobs[0].layoutGuideUrl)
  jobs[0].productImagesUsed = false; jobs[0].productReferences = undefined
  await page.getByRole('button',{name:'刷新记录',exact:true}).click()
  await page.getByText('此任务未使用所选商品图片，请重新生成以参考商品外观。',{exact:true}).waitFor()

  assert.deepEqual(pageErrors, [])
  await page.keyboard.press('Escape')
  await page.getByRole('dialog', { name: '单空间效果预览' }).waitFor({ state: 'hidden' })
  assert.equal(await page.getByRole('dialog', { name: '单空间效果预览' }).count(), 0)
  console.log('Render preview mock UI passed: snapshot, pending/reload, stale result, download, failure/retry, query recovery')
} catch (error) {
  if (page) await page.screenshot({ path: '../output/validation/e2e-render-preview-failure.png', fullPage: true })
  throw error
} finally {
  await browser?.close()
  app.kill('SIGTERM')
}
