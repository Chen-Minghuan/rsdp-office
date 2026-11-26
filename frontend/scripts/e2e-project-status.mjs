import assert from 'node:assert/strict'
import { chromium } from 'playwright'
import { mkdir } from 'node:fs/promises'

// 项目阶段由负责人/工作区主号人工维护（canManageStatus 由后端按成员关系返回），
// 其余成员只读；看板计数、分类筛选与列表阶段列都来自接口返回的 status。
const baseUrl = process.env.BASE_URL || 'http://localhost:3001'
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
async function scenario(role, canManageStatus) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  const stages = ['DESIGNING', 'DELIVERING', 'COMPLETED']
  const projects = stages.map((status, index) => ({
    projectId: `P${index + 1}`, name: ['总部办公', '交付项目', '完成项目'][index], status, canManageStatus,
    customerRequirements: '', requirementsRevision: 1,
    leader: { userId: 'LEAD', username: 'lead', displayName: '项目负责人', role: 'LEADER' }, collaborators: [], referenceImages: [],
    floors: [{ floorId: `F${index + 1}`, projectId: `P${index + 1}`, name: '1F', sortOrder: 1 }],
  }))
  let rejectUpdate = false
  let delayNextProjects = false
  const mutations = []
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'LEAD', username: 'lead', displayName: '开发账号', role } } }))
  await context.route('**/api/v1/**', route => {
    const request = route.request(), path = new URL(request.url()).pathname
    const respond = data => route.fulfill({ json: { code: 200, data } })
    if (path === '/api/v1/office/projects') {
      // 延迟响应按请求发出时的快照返回，模拟"列表请求先于阶段保存发出、响应后到"的竞态
      if (delayNextProjects) {
        delayNextProjects = false
        const stale = JSON.parse(JSON.stringify(projects))
        return new Promise(resolve => setTimeout(() => resolve(route.fulfill({ json: { code: 200, data: stale } })), 800))
      }
      return respond(projects)
    }
    if (path === '/api/v1/office/projects/budgets') return respond({})
    const match = path.match(/^\/api\/v1\/office\/projects\/([^/]+)\/status$/)
    if (match && request.method() === 'PATCH') {
      mutations.push(request.postDataJSON())
      if (rejectUpdate) return route.fulfill({ json: { code: 403, message: '阶段保存失败，请重试' } })
      const project = projects.find(project => project.projectId === match[1])
      project.status = request.postDataJSON().status
      return respond({ status: project.status })
    }
    return respond(path.endsWith('/canvas') ? null : [])
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(baseUrl + '/workspace/project-bench', { waitUntil: 'networkidle' })
  return { page, context, projects, mutations, errors, reject: () => { rejectUpdate = true }, accept: () => { rejectUpdate = false }, delayNextList: () => { delayNextProjects = true } }
}

try {
  const owner = await scenario('DEALER', true)
  const { page } = owner
  const cards = page.getByRole('region', { name: '项目阶段筛选' })
  const list = page.getByRole('region', { name: '项目列表', exact: true })
  assert.equal(await cards.getByRole('button', { name: /全部项目/ }).innerText(), '全部项目\n3')
  for (const label of ['设计中', '交付中', '已完成']) {
    await cards.getByRole('button', { name: new RegExp(label) }).click()
    assert.equal(await list.locator('tbody tr').count(), 1)
  }
  await page.getByRole('searchbox', { name: '搜索项目名称' }).fill('不存在')
  await list.getByText('没有匹配的项目，换个关键词试试。').waitFor()
  assert.equal(await cards.getByRole('button', { name: /全部项目/ }).innerText(), '全部项目\n3', '搜索不改变分类统计')
  await page.getByRole('searchbox', { name: '搜索项目名称' }).fill('')
  await cards.getByRole('button', { name: /全部项目/ }).click()
  await page.getByRole('combobox', { name: '总部办公的项目阶段' }).selectOption('DELIVERING')
  await page.waitForFunction(() => [...document.querySelectorAll('section[aria-label="项目阶段筛选"] button')].some(button => /交付中\s+2/.test(button.innerText)))
  await cards.getByRole('button', { name: /设计中/ }).click()
  await list.getByText('当前分类暂无项目。').waitFor()
  await page.reload({ waitUntil: 'networkidle' })
  assert.equal(await page.getByRole('combobox', { name: '总部办公的项目阶段' }).inputValue(), 'DELIVERING', '刷新后从后端读取已保存阶段')
  owner.reject()
  await page.getByRole('combobox', { name: '总部办公的项目阶段' }).selectOption('COMPLETED')
  await list.getByRole('alert').filter({ hasText: '阶段保存失败' }).waitFor()
  assert.equal(await page.getByRole('combobox', { name: '总部办公的项目阶段' }).inputValue(), 'DELIVERING', '失败恢复原值')
  assert.equal(owner.projects[0].status, 'DELIVERING')

  // 延迟响应回归：进入列表触发重取后立即保存阶段，迟到的旧列表响应不得覆盖刚保存的阶段
  owner.accept()
  owner.delayNextList()
  await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '项目工作台', exact: true }).click()
  await page.getByRole('link', { name: '返回项目列表', exact: true }).click()
  await page.getByRole('combobox', { name: '总部办公的项目阶段' }).selectOption('COMPLETED')
  await page.getByRole('combobox', { name: '总部办公的项目阶段' }).waitFor()
  await page.waitForTimeout(1200)
  assert.equal(await page.getByRole('combobox', { name: '总部办公的项目阶段' }).inputValue(), 'COMPLETED', '迟到的列表响应不得覆盖刚保存的阶段')
  assert.equal(owner.projects[0].status, 'COMPLETED')
  await mkdir('/tmp/rsdp-project-status', { recursive: true })
  await page.screenshot({ path: '/tmp/rsdp-project-status/desktop.png' })
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await cards.getByRole('button').count(), 4)
  await page.screenshot({ path: '/tmp/rsdp-project-status/mobile.png' })
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), '窄屏卡片和列表不能撑宽页面')
  assert.deepEqual(owner.errors, [])
  await owner.context.close()

  const leader = await scenario('USER', true)
  await leader.page.getByRole('combobox', { name: '总部办公的项目阶段' }).selectOption('COMPLETED')
  await leader.page.waitForFunction(() => [...document.querySelectorAll('section[aria-label="项目阶段筛选"] button')].some(button => /已完成\s+2/.test(button.innerText)))
  await leader.context.close()
  const reader = await scenario('USER', false)
  assert.equal(await reader.page.locator('select[aria-label$="的项目阶段"]').count(), 0)
  assert.equal(reader.mutations.length, 0)
  assert.deepEqual(reader.errors, [])
  await reader.context.close()
  console.log('Project status E2E passed: counts, filters, search, owner/leader editing, read-only members, persisted reload, error rollback, stale refetch race and mobile layout. API mocked.')
} finally {
  await browser.close()
}
