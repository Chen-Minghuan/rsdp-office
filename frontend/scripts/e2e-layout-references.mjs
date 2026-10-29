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
const baseUrl = `http://127.0.0.1:${port}`
const app = spawn(process.execPath, ['.output/server/index.mjs'], {
  cwd,
  env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' },
  stdio: 'ignore',
})

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
  const source = {
    analysisId: 'MEETING-TEST', analysisRevision: 1, status: 'awaiting_confirm', sourceName: '会议室测试.dxf',
    inputMode: 'cad', geometrySource: 'cad_geometry', evidenceKind: 'REAL_SERVICE',
    unitResolution: { canonicalUnit: 'mm', confirmed: true },
    rooms: [{ roomId: 'MEETING-ROOM', label: '测试会议室', roomType: 'MEETING_ROOM', geometrySource: 'cad_geometry', polygon: [[0, 0], [12000, 0], [12000, 8000], [0, 8000]] }, { roomId: 'OPEN-ROOM', label: '测试办公区', roomType: 'OPEN_OFFICE', geometrySource: 'cad_geometry', polygon: [[20000, 0], [26093, 0], [26093, 17810], [20000, 17810]] }],
  }
  await context.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    const data = path.endsWith('/floor-plan/MEETING-TEST') ? source : path.endsWith('/OFFICE-PROJECT-001') || path.endsWith('/canvas') ? null : []
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const state = () => page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01')))
  const catalog = page.getByTestId('unit-module-catalog')
  await page.goto(`${baseUrl}/canvas?analysisId=MEETING-TEST`, { waitUntil: 'networkidle' })
  await page.locator('.review-rooms').getByRole('button', { name: '选择空间 测试会议室', exact: true }).waitFor()
  await page.getByRole('button', { name: '进入家具排版 →', exact: true }).click()
  await page.getByTestId('tab-product-selection').click()
  const errors=[]
  page.on('pageerror',error=>errors.push(error.message))
  await page.locator('[data-space-id="OPEN-ROOM"]').click()
  await page.waitForURL(url=>url.searchParams.get('zoneId')==='OPEN-ROOM')
  await page.getByTestId('tab-product-selection').click()
  for (const id of ['desk-straight','desk-l','desk-screen-straight','desk-screen-l','desk-manager','desk-executive']) assert.equal(await catalog.locator('[data-module-id="'+id+'"] .layout-seat').count(),1,'catalogue chair for '+id)
  await catalog.locator('[data-auto-module-id="desk-l"]').click()
  const references = page.locator('[aria-label="排版尺寸标注"]')
  await references.locator('[data-clearance-kind="wall"]').first().waitFor()
  assert.equal(await references.locator('[data-clearance-kind="wall"]').count(),4,'pending previews show actual wall clearances')
  assert.ok(await references.locator('[data-clearance-kind="group"]').count()>0,'vertical desk banks have gap measurements')
  assert.ok(await page.locator('[data-preview-id] .layout-seat').count()>0,'L single-row has illustrative chairs')
  await page.getByTestId('office-design-canvas').screenshot({path:'C:/Users/cmh/AppData/Local/Temp/rsdp-clearances-preview.png'})
  await page.getByRole('spinbutton',{name:'目标人数',exact:true}).fill('1')
  await page.getByTestId('apply-layout').click()
  await page.getByTestId('layout-unchanged').waitFor()
  const saved=(await state()).objects
  const measures = async()=>references.locator('[data-clearance-mm]').evaluateAll(nodes=>nodes.map(n=>[n.dataset.clearanceId,n.dataset.clearanceMm]))
  const originalMeasures = await measures()
  for (const angle of [90,180,270]) {
    await page.getByRole('button',{name:'旋转场景视图',exact:true}).click()
    await page.waitForFunction(angle=>document.querySelector('[data-testid="office-scene-board"]').dataset.viewRotation===String(angle),angle)
    assert.deepEqual((await state()).objects,saved,'view rotation does not modify geometry')
    assert.deepEqual(await measures(),originalMeasures,'view rotation preserves physical distances')
  }
  await page.getByRole('button',{name:'复位场景视图',exact:true}).click()
  assert.equal(await page.getByTestId('office-scene-board').getAttribute('data-view-rotation'),'0')
  await page.getByRole('button',{name:'旋转场景视图',exact:true}).click()
  await page.getByRole('checkbox',{name:'显示排版预览',exact:true}).uncheck()
  const before = (await state()).objects.find(item=>item.layoutGroupId==='auto:OPEN-ROOM')
  const zoom = await page.evaluate(()=>document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office').zoom)
  const furnitureNode = page.locator('.board-element[data-id="'+before.id+'"]')
  const box = await furnitureNode.boundingBox()
  await page.mouse.move(box.x+box.width/2,box.y+box.height/2)
  await page.mouse.down()
  await page.mouse.move(box.x+box.width/2-200*zoom,box.y+box.height/2,{steps:8})
  await page.mouse.up()
  const after = (await state()).objects.find(item=>item.id===before.id)
  assert.ok(Math.abs(after.x-before.x)<3&&Math.abs(after.y-before.y-200)<3,'screen-left drag at 90 degrees moves world-down')
  await page.getByRole('button',{name:'显示参考尺寸',exact:true}).click()
  assert.equal(await references.count(),0)
  await page.getByRole('button',{name:'显示参考尺寸',exact:true}).click()
  assert.equal(await references.count(),1)
  await page.getByTestId('office-design-canvas').screenshot({path:'C:/Users/cmh/AppData/Local/Temp/rsdp-scene-rotated.png'})
  const target = await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    const z=store.zones.find(z=>z.id==='OPEN-ROOM'),rect=document.querySelector('[data-testid="office-design-canvas"]').getBoundingClientRect()
    const x=z.x+z.w/2,y=z.y+z.h*0.75
    return {x,y,screenX:rect.left+rect.width/2+store.panX-(y-z.y-z.h/2)*store.zoom,screenY:rect.top+rect.height/2+store.panY+(x-z.x-z.w/2)*store.zoom}
  })
  await page.getByTestId('tab-product-selection').click()
  await page.getByRole('button',{name:'逐件添加',exact:true}).click()
  await page.mouse.move(target.screenX,target.screenY)
  await page.mouse.click(target.screenX,target.screenY)
  const manual=(await state()).objects.find(item=>item.kind==='furniture'&&item.zoneId==='OPEN-ROOM'&&!item.layoutGroupId)
  assert.ok(manual&&Math.abs(manual.x+manual.w/2-target.x)<3&&Math.abs(manual.y+manual.h/2-target.y)<3,'manual placement in rotated view uses world position')
  await page.getByRole('button',{name:'结束逐件添加',exact:true}).click()
  assert.deepEqual(errors,[])
  console.log('PASS: preview wall/group distances, L-desk seats, 90/180/270/reset view with unchanged geometry, rotation-aware dragging/manual placement, catalogue chairs and dimension toggle')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
