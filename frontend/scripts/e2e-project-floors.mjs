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
  cwd, env: { ...process.env, PORT: String(port), NITRO_PORT: String(port), HOST: '127.0.0.1', NITRO_HOST: '127.0.0.1' }, stdio: 'ignore',
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
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXioAAAAASUVORK5CYII=', 'base64')
  const projects = []
  const saves = []
  await context.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const respond = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
    if (path === '/api/v1/office/projects' && request.method() === 'GET') return respond(projects)
    if (path === '/api/v1/office/projects' && request.method() === 'POST') {
      const body = request.postDataJSON()
      const project = { projectId: 'OPRJ-TEST', name: body.name, customerRequirements: body.customerRequirements, requirementsRevision: 1,
        floors: body.floors.map((floor, index) => ({ projectId: 'OPRJ-TEST', floorId: `OFLR-${index + 1}`, name: floor.name, sortOrder: index + 1 })) }
      projects.push(project)
      return respond(project)
    }
    if (path === '/api/v1/office/projects/OPRJ-TEST' && request.method() === 'PATCH') {
      projects[0].name = request.postDataJSON().name
      return respond(projects[0])
    }
    if (path.startsWith('/api/v1/office/projects/OPRJ-TEST/floors/') && request.method() === 'PATCH') {
      const floor = projects[0].floors.find(item => item.floorId === path.split('/').at(-1))
      floor.name = request.postDataJSON().name
      return respond(floor)
    }
    if (path === '/api/v1/office/projects/OPRJ-TEST/requirements') {
      const project = projects[0]
      project.customerRequirements = request.postDataJSON().customerRequirements
      project.requirementsRevision++
      return respond(project)
    }
    if (path === '/api/v1/office/projects/OPRJ-TEST/floors') {
      const floor = { projectId: 'OPRJ-TEST', floorId: 'OFLR-3', name: request.postDataJSON().name, sortOrder: 3 }
      projects[0].floors.push(floor)
      return respond(floor)
    }
    if (path.endsWith('/canvas') && request.method() === 'PUT') { saves.push({ path, body: request.postDataJSON() }); return respond(null) }
    return respond(path.endsWith('/canvas') ? null : [])
  })

  await page.goto(baseUrl, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '＋ 新建项目', exact: true }).click()
  await page.getByPlaceholder('例如：深圳 XX 集团总部办公').fill('总部办公大楼')
  await page.getByPlaceholder('记录客户偏好、工位数量、预算或其他需要持续提醒的事项').fill('保留开放办公区，控制预算')
  await page.getByPlaceholder('例如：1F, 2F, 7~9F, 11F').fill('2F')
  await page.getByRole('button', { name: '添加楼层', exact: true }).click()
  assert.deepEqual(await page.locator('form span').filter({ hasText: /^1F$|^2F$/ }).allTextContents(), ['1F', '2F'])
  await page.getByRole('button', { name: '创建项目', exact: true }).click()
  await page.getByRole('heading', { name: '1F · 上传办公图纸', exact: true }).waitFor()
  assert.match(page.url(), /floorId=OFLR-1/)

  await page.locator('input[type="file"]').setInputFiles({ name: 'first.png', mimeType: 'image/png', buffer: png })
  await page.getByText('first.png', { exact: true }).last().waitFor()
  await page.getByRole('button', { name: '折叠1F空间', exact: true }).click()
  assert.equal(await page.getByTestId('design-space-switcher').isVisible(), false)
  await page.getByRole('button', { name: '2F', exact: true }).click()
  await page.getByRole('heading', { name: '2F · 上传办公图纸', exact: true }).waitFor()
  assert.equal(await page.getByTestId('design-space-switcher').isVisible(), true)
  assert.equal(await page.getByText('first.png', { exact: true }).count(), 0)
  await page.locator('input[type="file"]').setInputFiles({ name: 'second.png', mimeType: 'image/png', buffer: png })
  await page.getByText('second.png', { exact: true }).last().waitFor()
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByText('second.png', { exact: true }).last().waitFor()
  await page.getByRole('button', { name: '1F', exact: true }).click()
  await page.getByText('first.png', { exact: true }).last().waitFor()
  assert.equal(await page.getByTestId('design-space-switcher').isVisible(), false)
  await page.getByRole('button', { name: '展开1F空间', exact: true }).click()
  assert.equal(await page.getByTestId('design-space-switcher').isVisible(), true)
  const records = await page.evaluate(async () => {
    const db = await new Promise((resolve, reject) => { const req = indexedDB.open('rsdp-office-files'); req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error) })
    const tx = db.transaction('floorFiles', 'readonly')
    return await new Promise((resolve, reject) => { const req = tx.objectStore('floorFiles').getAll(); req.onsuccess = () => resolve(req.result.map(item => [item.floorId, item.name])); req.onerror = () => reject(req.error) })
  })
  assert.deepEqual(records.map(item => item[1]).sort(), ['first.png', 'second.png'])
  assert.ok(records.some(item => item[0] === 'OFLR-1') && records.some(item => item[0] === 'OFLR-2'))

  await page.getByRole('button', { name: /总部办公大楼/ }).click()
  assert.equal(await page.getByRole('button', { name: '2F', exact: true }).isVisible(), false)
  await page.getByRole('button', { name: /总部办公大楼/ }).click()
  await page.getByRole('button', { name: '需求', exact: true }).click()
  await page.locator('form textarea').fill('新增 20 个工位，保留会议室')
  await page.getByRole('button', { name: '保存备注', exact: true }).click()
  await page.getByText('已保存。既有推荐和方案请结合新需求重新复核。', { exact: true }).waitFor()
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await page.getByText('新增 20 个工位，保留会议室', { exact: true }).waitFor()
  await page.getByText('新增 20 个工位，保留会议室', { exact: true }).dblclick()
  await page.getByRole('textbox', { name: '双击填写客户需求', exact: true }).fill('双击补充需求\n第二行备注')
  await page.getByRole('textbox', { name: '双击填写客户需求', exact: true }).press('Control+Enter')
  await page.getByText('双击补充需求 第二行备注', { exact: true }).waitFor()
  assert.equal(await page.getByRole('textbox', { name: '双击填写客户需求', exact: true }).count(), 0)

  await page.getByTitle('编辑项目名称和楼层名称', { exact: true }).click()
  await page.getByRole('textbox', { name: '编辑项目名称' }).fill('总部办公项目（已编辑）')
  await page.getByRole('textbox', { name: '编辑楼层名称 1' }).fill('首层')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await page.getByRole('heading', { name: '编辑项目与楼层', exact: true }).waitFor({ state: 'hidden' })
  await page.getByRole('heading', { name: '首层 · 上传办公图纸', exact: true }).waitFor()
  assert.equal(await page.getByRole('region', { name: '项目设计流程' }).getByText('first.png', { exact: true }).count(), 0)
  assert.match(page.url(), /floorId=OFLR-1/)
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:OFLR-1')).files[0].name), 'first.png')

  await page.getByRole('button', { name: /首层 当前/ }).dblclick()
  await page.getByRole('textbox', { name: '双击编辑楼层名称', exact: true }).fill('一层')
  await page.getByRole('textbox', { name: '双击编辑楼层名称', exact: true }).press('Enter')
  await page.getByRole('button', { name: /一层 当前/ }).waitFor()
  await page.getByRole('button', { name: /总部办公项目（已编辑）/ }).dblclick()
  await page.getByRole('textbox', { name: '双击编辑项目名称', exact: true }).fill('总部项目双击')
  await page.getByRole('textbox', { name: '双击编辑项目名称', exact: true }).press('Enter')
  await page.getByRole('button', { name: /总部项目双击/ }).waitFor()
  const secondFloor = page.getByTitle('单击切换楼层，双击修改楼层名称').filter({ hasText: /^2F/ })
  await secondFloor.click()
  await page.getByRole('button', { name: /2F 当前/ }).waitFor()
  await secondFloor.dblclick()
  await page.getByRole('textbox', { name: '双击编辑楼层名称', exact: true }).fill('不会保存')
  await page.getByRole('textbox', { name: '双击编辑楼层名称', exact: true }).press('Escape')
  assert.equal(await page.getByRole('button', { name: /2F 当前/ }).count(), 1)
  await page.waitForURL(/floorId=OFLR-2/)
  await page.getByRole('button', { name: '一层', exact: true }).click()
  await page.waitForURL(/floorId=OFLR-1/)
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:OFLR-1')).files[0].name), 'first.png')

  await page.getByRole('button', { name: '＋ 添加新楼层', exact: true }).click()
  await page.getByRole('textbox', { name: '新楼层名称' }).fill('3F')
  await page.getByRole('button', { name: '添加', exact: true }).click()
  await page.getByRole('heading', { name: '3F · 上传办公图纸', exact: true }).waitFor()
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '3F · 上传办公图纸', exact: true }).waitFor()
  await page.getByRole('button', { name: '1 项目创建', exact: true }).click()
  await page.getByRole('heading', { name: '创建办公项目', exact: true }).waitFor()
  await page.getByRole('button', { name: '关闭创建项目', exact: true }).click()
  assert.ok(saves.every(save => save.path.includes(save.body.projectId)), '画布保存必须使用原楼层隔离键')
  assert.deepEqual(errors, [])
  console.log('E2E project floors passed')
} finally {
  if (browser) await browser.close()
  app.kill('SIGTERM')
}
