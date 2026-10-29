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
  const errors=[]
  page.on('pageerror',error=>errors.push(error.message))
  const choose = async (id, category) => {
    await page.getByTestId('tab-product-selection').click()
    const section = catalog.locator('details').filter({hasText: category})
    if (!await section.getAttribute('open').then(value => value !== null)) await section.locator('summary').click()
    await catalog.locator('[data-auto-module-id="'+id+'"]').click()
    assert.equal(await page.getByTestId('tab-auto-layout').getAttribute('aria-selected'),'true','selection returns to automatic layout')
  }
  await choose('desk-straight','职员桌')
  await page.getByRole('spinbutton',{name:'目标人数',exact:true}).fill('6')
  await page.getByRole('tab',{name:'选择排布',exact:true}).click()
  assert.equal(await page.getByRole('button',{name:'一字单排',exact:true}).count(),1)
  assert.equal(await page.getByRole('button',{name:'L 型单排',exact:true}).count(),0)
  assert.equal(await page.getByRole('button',{name:'F 型单排',exact:true}).count(),0)
  assert.equal(await page.getByText('产品样式',{exact:true}).count(),0)
  await page.getByTestId('apply-layout').click()
  await page.getByTestId('layout-unchanged').waitFor()
  assert.ok((await state()).objects.filter(item=>item.layoutRole==='primary').every(item=>item.furnitureTemplateId==='desk-straight'))
  await page.getByTestId('auto-confirm-recommend').click()
  assert.equal(await page.getByTestId('tab-style-recommendation').getAttribute('aria-selected'),'true','automatic layout can confirm and open recommendations directly')
  await page.reload({waitUntil:'networkidle'})
  await page.getByTestId('layout-unchanged').waitFor()
  await page.getByTestId('tab-product-selection').click()
  assert.equal(await catalog.locator('[data-module-id="desk-straight"]').getAttribute('aria-pressed'),'true','meeting room restores chosen desk')
  await choose('desk-screen-l','职员桌')
  await page.getByRole('tab',{name:'调整参数',exact:true}).click()
  await page.getByRole('spinbutton',{name:'模块宽度（毫米）',exact:true}).fill('1500')
  await page.getByRole('tab',{name:'选择排布',exact:true}).click()
  await page.getByRole('button',{name:'T 型对坐',exact:true}).click()
  assert.equal(await page.getByRole('tab',{name:'选择排布',exact:true}).getAttribute('aria-selected'),'true')
  await page.getByRole('tab',{name:'调整参数',exact:true}).click()
  assert.equal(await page.getByRole('spinbutton',{name:'模块宽度（毫米）',exact:true}).inputValue(),'1500','preset does not reset size')
  await page.getByTestId('apply-layout').click()
  await page.reload({waitUntil:'networkidle'})
  await page.getByTestId('layout-unchanged').waitFor()
  await page.getByTestId('tab-product-selection').click()
  assert.equal(await catalog.locator('[data-module-id="desk-screen-l"]').getAttribute('aria-pressed'),'true')
  await choose('cabinet-mobile','收纳')
  await page.getByRole('spinbutton',{name:'目标数量',exact:true}).fill('3')
  await page.getByRole('tab',{name:'选择排布',exact:true}).click()
  await page.getByRole('button',{name:'按场地排布',exact:true}).click()
  assert.equal(Number((await page.getByTestId('module-capacity').innerText()).replace(/[^0-9]/g,'')),3)
  await page.getByTestId('apply-layout').click()
  await page.getByRole('button',{name:'确认当前排版',exact:false}).click()
  await page.reload({waitUntil:'networkidle'})
  await page.getByTestId('layout-unchanged').waitFor()
  assert.equal((await state()).objects.filter(item=>item.layoutGroupId==='auto:MEETING-ROOM').length,3)
  assert.ok((await state()).objects.filter(item=>item.layoutGroupId==='auto:MEETING-ROOM').every(item=>item.layoutConfirmed&&item.furnitureTemplateId==='cabinet-mobile'))
  await page.screenshot({path:'C:/Users/cmh/AppData/Local/Temp/rsdp-module-selection.png'})
  assert.deepEqual(errors,[])
  console.log('PASS: unrestricted meeting furniture, one module selector, compatible presets, no automatic tab change, size retention, screen persistence, generic quantity confirmation and reload')
} finally {
  try { await browser?.close() } finally {
    if (app.exitCode === null) { const exited = once(app, 'exit'); app.kill('SIGTERM'); await exited }
  }
}
