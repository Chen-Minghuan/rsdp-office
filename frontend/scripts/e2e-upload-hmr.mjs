import assert from 'node:assert/strict'
import { cp, mkdtemp, readFile, realpath, rm, symlink, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { chromium } from 'playwright'

// 独立副本启动真实 Nuxt dev，仅修改副本来模拟页面先更新、store 实例仍旧的窗口。
const root = fileURLToPath(new URL('../', import.meta.url))
const sandbox = await realpath(await mkdtemp(join(tmpdir(), 'rsdp-upload-hmr-')))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = `http://127.0.0.1:${port}`
let app, browser, output = ''
try {
  await cp(join(root, 'app'), join(sandbox, 'app'), { recursive: true })
  for (const name of ['nuxt.config.ts', 'package.json']) await cp(join(root, name), join(sandbox, name))
  await symlink(join(root, 'node_modules'), join(sandbox, 'node_modules'))
  const pagePath = join(sandbox, 'app/pages/index.vue')
  const storePath = join(sandbox, 'app/stores/office.ts')
  const currentPage = await readFile(pagePath, 'utf8')
  const currentStore = await readFile(storePath, 'utf8')
  assert.ok(currentPage.includes('store.$patch({ files: [drawing] })'))
  await writeFile(storePath, currentStore.replace('  actions: {', `  actions: {
    legacyDrawingUpload(drawing: ProjectFile) { this.files = [drawing] },`))
  await writeFile(pagePath, currentPage
    .replace('store.$patch({ files: [drawing] })', 'store.legacyDrawingUpload(drawing)')
    .replace('<input ref="fileInput"', '<input data-upload-version="legacy" ref="fileInput"'))
  app = spawn(process.execPath, [join(root, 'node_modules/nuxt/bin/nuxt.mjs'), 'dev', sandbox, '--port', String(port), '--host', '127.0.0.1'], {
    cwd: sandbox, env: { ...process.env, NUXT_TELEMETRY_DISABLED: '1' }, stdio: ['ignore', 'pipe', 'pipe'],
  })
  for (const stream of [app.stdout, app.stderr]) stream.on('data', chunk => { output = (output + chunk).slice(-6000) })
  let ready = false
  for (let attempt = 0; attempt < 150; attempt++) {
    try { if ((await fetch(baseUrl)).ok) { ready = true; break } } catch {}
    if (app.exitCode !== null) throw new Error('Nuxt dev 未启动')
    await new Promise(resolve => setTimeout(resolve, 200))
  }
  assert.ok(ready, 'Nuxt dev 未就绪')
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext()
  await context.route('**/api/v1/**', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: route.request().url().includes('/canvas') ? null : [] }) }))
  const page = await context.newPage()
  page.setDefaultTimeout(15000)
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(baseUrl, { waitUntil: 'networkidle' })
  const upload = page.locator('input[type="file"]')
  await upload.setInputFiles({ name: 'before.png', mimeType: 'image/png', buffer: Buffer.from('mock image') })
  await page.getByLabel('移除 before.png').waitFor()
  await page.waitForFunction(() => !document.querySelector('input[type="file"]').disabled)
  await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    window.uploadRegressionStore = store
    store.$patch({ objects: [{ kind: 'note', id: 'HMR-NOTE', text: '保留已有编辑', x: 0, y: 0, w: 100, h: 100 }] })
  })
  // 保持浏览器和 store 不变，仅热更新页面。
  await writeFile(pagePath, currentPage)
  await page.waitForFunction(() => !document.querySelector('input[type="file"]').hasAttribute('data-upload-version'))
  await upload.setInputFiles({ name: 'after.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF UI-only fixture') })
  await page.getByLabel('移除 after.pdf').waitFor()
  await page.waitForFunction(() => !document.querySelector('input[type="file"]').disabled)
  const state = await page.evaluate(() => {
    const store = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$pinia._s.get('office')
    return { sameInstance: store === window.uploadRegressionStore, obsoleteAction: typeof store.legacyDrawingUpload, missingNewAction: typeof store.replaceDrawingFile, files: store.files.map(file => file.name), ids: store.objects.map(object => object.id) }
  })
  assert.deepEqual(state, { sameInstance: true, obsoleteAction: 'function', missingNewAction: 'undefined', files: ['after.pdf'], ids: ['HMR-NOTE'] })
  assert.equal(await page.getByRole('alert').count(), 0)
  assert.deepEqual(errors, [])
  console.log('上传热更新回归通过：页面更新后保留旧 store 实例（无 replaceDrawingFile），单图替换正常，已有编辑保留。独立 Nuxt dev 副本和 mock API。')
} catch (error) {
  console.error(output)
  throw error
} finally {
  await browser?.close()
  if (app && app.exitCode === null) {
    const exited = once(app, 'exit')
    app.kill('SIGTERM')
    await exited
  }
  await rm(sandbox, { recursive: true, force: true })
}
