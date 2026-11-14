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

const users = {
  designer: { userId: 'USER-A', username: 'designer', displayName: '设计师 A', role: 'USER' },
  dealer: { userId: 'USER-B', username: 'dealer', displayName: '经销商 B', role: 'DEALER' },
  collaborator: { userId: 'USER-C', username: 'collaborator', displayName: '协作者 C', role: 'USER' },
  admin: { userId: 'ADMIN', username: 'admin', displayName: '管理员', role: 'ADMIN' },
}
const legacyProject = {
  projectId: 'OFFICE-PROJECT-001', name: '历史项目', customerRequirements: '', requirementsRevision: 1,
  leader: { userId: 'USER-B', username: 'dealer', displayName: '经销商 B', role: 'LEADER' },
  collaborators: [{ userId: 'USER-C', username: 'collaborator', displayName: '协作者 C', role: 'COLLABORATOR' }], referenceImages: [],
  floors: [{ projectId: 'OFFICE-PROJECT-001', floorId: 'OFFICE-PROJECT-001', name: '旧楼层', sortOrder: 1 }],
}
const legacyDraft = {
  workspaceId: 'OFFICE-PROJECT-001', canvasRevision: 1, canvasMode: 'overview', designZoneId: null,
  files: [{ name: 'legacy.png', size: 12, type: 'image/png', mock: true }],
  objects: [{ id: 'LEGACY-ZONE', kind: 'zone', name: '旧项目空间', polygon: [[0, 0], [1000, 0], [1000, 1000], [0, 1000]] }],
  cart: [], chat: [], undoStack: [],
}

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
  const context = await browser.newContext({ viewport: { width: 1500, height: 950 } })
  let currentUser = users.designer
  let failProjects = true
  const createdProjects = []
  const canvasReads = []
  const errors = []

  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: currentUser } }))
  await context.route('**/api/auth/logout', route => route.fulfill({ json: { code: 200, data: null } }))
  await context.route('**/api/auth/login', async route => {
    const username = route.request().postDataJSON().username
    currentUser = Object.values(users).find(user => user.username === username) || null
    if (!currentUser) return route.fulfill({ json: { code: 401, message: '账号或密码错误', data: null } })
    return route.fulfill({ json: { code: 200, data: currentUser } })
  })
  await context.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const respond = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
    if (path === '/api/v1/office/projects' && request.method() === 'GET') {
      if (failProjects) return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 403, message: '项目列表暂时不可用', data: null }) })
      if (currentUser.userId === 'USER-A') return respond(createdProjects)
      if (currentUser.userId === 'USER-B' || currentUser.userId === 'USER-C') return respond([legacyProject])
      return respond([])
    }
    if (path === '/api/v1/office/projects' && request.method() === 'POST') {
      const body = request.postDataJSON()
      const project = {
        projectId: 'OPRJ-A', name: body.name, customerRequirements: body.customerRequirements, requirementsRevision: 1,
        leader: { ...currentUser, role: 'LEADER' }, collaborators: [], referenceImages: [],
        floors: [{ projectId: 'OPRJ-A', floorId: 'OFLR-A', name: body.floors[0].name, sortOrder: 1 }],
      }
      createdProjects.push(project)
      return respond(project)
    }
    if (path === '/api/v1/office/users/assignable') return respond([currentUser])
    if (path.endsWith('/canvas') && request.method() === 'GET') { canvasReads.push({ userId: currentUser.userId, path }); return respond(null) }
    if (path.endsWith('/canvas') && request.method() === 'PUT') return respond(null)
    return respond([])
  })

  const page = await context.newPage()
  page.on('pageerror', error => { errors.push(error.message); console.error('pageerror', error.message) })
  await page.addInitScript(draft => {
    if (!sessionStorage.getItem('workspace-isolation-seeded')) {
      localStorage.setItem('rsdp-office-v01', JSON.stringify(draft))
      sessionStorage.setItem('workspace-isolation-seeded', '1')
    }
  }, legacyDraft)

  await page.goto(baseUrl, { waitUntil: 'networkidle' })
  await page.getByTestId('project-empty-state').getByRole('alert').waitFor()
  assert.equal(await page.getByText('默认办公项目', { exact: true }).count(), 0)
  assert.equal(canvasReads.length, 0, '项目列表失败时不得读取默认画布')

  failProjects = false
  await page.getByRole('button', { name: '重新加载', exact: true }).click()
  await page.getByRole('heading', { name: '暂无可访问项目', exact: true }).waitFor()
  assert.equal(await page.getByText('默认办公项目', { exact: true }).count(), 0)
  assert.equal(canvasReads.length, 0, '空项目列表不得读取默认画布')

  await page.getByTestId('project-empty-state').getByRole('button', { name: '新建项目', exact: true }).click()
  await page.getByPlaceholder('例如：深圳 XX 集团总部办公').fill('账号 A 的项目')
  await page.getByRole('button', { name: '创建项目', exact: true }).click()
  await page.getByRole('heading', { name: '1F · 上传办公图纸', exact: true }).waitFor()
  await page.locator('input[type="file"]').setInputFiles({ name: 'a.png', mimeType: 'image/png', buffer: Buffer.from('account-a-file') })
  await page.getByText('a.png', { exact: true }).last().waitFor()
  await page.waitForFunction(() => JSON.parse(localStorage.getItem('rsdp-office-v01:USER-A:OFLR-A') || '{}').files?.[0]?.name === 'a.png')
  await page.waitForFunction(async () => {
    const db = await new Promise((resolve, reject) => { const req = indexedDB.open('rsdp-office-files'); req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error) })
    if (!db.objectStoreNames.contains('floorFiles')) return false
    const tx = db.transaction('floorFiles', 'readonly')
    const record = await new Promise((resolve, reject) => { const req = tx.objectStore('floorFiles').get('USER-A:OFLR-A:a.png'); req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error) })
    return record?.floorId === 'OFLR-A' && record?.storageScope === 'USER-A'
  })
  assert.ok(await page.evaluate(() => !!localStorage.getItem('rsdp-office-workspace-v1:USER-A')))

  async function logoutAndLogin(username) {
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page.waitForURL(/\/login/)
    await page.getByRole('heading', { name: '登录办公空间智能设计平台', exact: true }).waitFor()
    await page.locator('#username').fill(username)
    await page.locator('#password').fill('test-password')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.waitForURL(url => !url.pathname.endsWith('/login'))
  }

  await logoutAndLogin('collaborator')
  await page.getByRole('heading', { name: '旧楼层 · 上传办公图纸', exact: true }).waitFor()
  assert.equal(await page.getByText('legacy.png', { exact: true }).count(), 0, '协作者不得继承未分账号的旧草稿')
  assert.ok(await page.evaluate(() => !!localStorage.getItem('rsdp-office-v01')), '非负责人不得消费旧草稿')
  assert.equal(await page.evaluate(() => localStorage.getItem('rsdp-office-v01:USER-C:OFFICE-PROJECT-001')), null)

  await logoutAndLogin('dealer')
  await page.getByText('legacy.png', { exact: true }).last().waitFor()
  assert.equal(await page.evaluate(() => localStorage.getItem('rsdp-office-v01')), null, '负责人迁移后应移除旧共享键')
  const dealerDraft = await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:USER-B:OFFICE-PROJECT-001') || '{}'))
  assert.equal(dealerDraft.files[0].name, 'legacy.png')
  assert.equal(dealerDraft.objects[0].name, '旧项目空间')

  await logoutAndLogin('designer')
  await page.getByRole('heading', { name: '1F · 上传办公图纸', exact: true }).waitFor()
  await page.getByText('a.png', { exact: true }).last().waitFor()
  assert.equal(await page.getByText('legacy.png', { exact: true }).count(), 0)
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('rsdp-office-v01:USER-A:OFLR-A')).files[0].name), 'a.png')

  await logoutAndLogin('admin')
  await page.getByText('系统管理员用于管理账号和工作区，不直接创建设计项目。请使用经销商主号或设计师账号登录。', { exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '新建项目', exact: true }).count(), 0)
  assert.equal(await page.getByText('默认办公项目', { exact: true }).count(), 0)
  assert.deepEqual(errors, [])
  await context.close()

  // A 的旧项目列表请求晚于 B 登录返回时，不得覆盖 B 的真实项目。
  const staleContext = await browser.newContext()
  let staleUser = users.designer
  let releaseStale
  const staleGate = new Promise(resolve => { releaseStale = resolve })
  let delayNext = true
  await staleContext.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: staleUser } }))
  await staleContext.route('**/api/auth/logout', route => route.fulfill({ json: { code: 200, data: null } }))
  await staleContext.route('**/api/auth/login', route => {
    staleUser = route.request().postDataJSON().username === 'dealer' ? users.dealer : users.designer
    return route.fulfill({ json: { code: 200, data: staleUser } })
  })
  await staleContext.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const respond = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
    if (path === '/api/v1/office/projects' && request.method() === 'GET') {
      if (staleUser.userId === 'USER-A' && delayNext) { delayNext = false; await staleGate }
      return respond(staleUser.userId === 'USER-A' ? [] : [legacyProject])
    }
    return respond(path.endsWith('/canvas') ? null : [])
  })
  const stalePage = await staleContext.newPage()
  await stalePage.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' })
  await stalePage.locator('#username').fill('designer')
  await stalePage.locator('#password').fill('test-password')
  await stalePage.getByRole('button', { name: '登录', exact: true }).click()
  await stalePage.getByRole('button', { name: '退出登录', exact: true }).waitFor()
  await stalePage.getByRole('button', { name: '退出登录', exact: true }).click()
  await stalePage.locator('#username').fill('dealer')
  await stalePage.locator('#password').fill('test-password')
  await stalePage.getByRole('button', { name: '登录', exact: true }).click()
  await stalePage.getByRole('heading', { name: '旧楼层 · 上传办公图纸', exact: true }).waitFor()
  releaseStale()
  await stalePage.waitForTimeout(300)
  await stalePage.getByRole('heading', { name: '旧楼层 · 上传办公图纸', exact: true }).waitFor()
  assert.equal(await stalePage.getByRole('heading', { name: '暂无可访问项目', exact: true }).count(), 0)
  await staleContext.close()

  console.log('E2E workspace isolation passed')
} finally {
  if (browser) await browser.close()
  app.kill('SIGTERM')
}
