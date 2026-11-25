import assert from 'node:assert/strict'
import { chromium } from 'playwright'
import { mkdir } from 'node:fs/promises'

// 项目阶段由后端按方案确认数据派生（全部楼层已确认 → 交付中，否则设计中），
// 前端只读展示：看板计数、列表阶段列、分类筛选均来自接口返回的 status。
const baseUrl = process.env.BASE_URL || 'http://localhost:3001'
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
async function scenario() {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  const projects = [
    { projectId: 'P1', name: '总部办公', status: 'DELIVERING', zoneTotal: 4, zoneDelivered: 4 },
    { projectId: 'P2', name: '设计项目', status: 'DESIGNING', zoneTotal: 5, zoneDelivered: 2 },
    // 无区域项目：进度显示占位
    { projectId: 'P3', name: '空白项目', status: 'DESIGNING', zoneTotal: 0, zoneDelivered: 0 },
  ].map(project => ({
    ...project,
    customerRequirements: '', requirementsRevision: 1,
    leader: { userId: 'LEAD', username: 'lead', displayName: '项目负责人', role: 'LEADER' }, collaborators: [], referenceImages: [],
    floors: [{ floorId: `F-${project.projectId}`, projectId: project.projectId, name: '1F', sortOrder: 1 }],
  }))
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: { userId: 'LEAD', username: 'lead', displayName: '开发账号', role: 'DEALER' } } }))
  await context.route('**/api/v1/**', route => {
    const request = route.request(), path = new URL(request.url()).pathname
    const respond = data => route.fulfill({ json: { code: 200, data } })
    if (path === '/api/v1/office/projects') return respond(projects)
    if (path === '/api/v1/office/projects/budgets') return respond({})
    return respond(path.endsWith('/canvas') ? null : [])
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(baseUrl + '/workspace/project-bench', { waitUntil: 'networkidle' })
  return { page, context, errors }
}

try {
  const { page, context, errors } = await scenario()
  const cards = page.getByRole('region', { name: '项目阶段筛选' })
  const list = page.getByRole('region', { name: '项目列表', exact: true })
  assert.equal(await cards.getByRole('button').count(), 4, '看板为全部/设计中/交付中/已完成占位')
  assert.equal(await cards.getByRole('button', { name: /全部项目/ }).innerText(), '全部项目\n3')
  assert.equal(await cards.getByRole('button', { name: /交付中/ }).innerText(), '交付中\n1')
  assert.equal(await cards.getByRole('button', { name: /设计中/ }).innerText(), '设计中\n2')
  const completedCard = cards.getByRole('button', { name: /已完成/ })
  assert.equal(await completedCard.innerText(), '已完成\n—', '已完成看板占位不展示数据')
  assert.ok(await completedCard.isDisabled(), '已完成看板禁用、不参与筛选')
  await cards.getByRole('button', { name: /交付中/ }).click()
  assert.equal(await list.locator('tbody tr').count(), 1)
  await cards.getByRole('button', { name: /设计中/ }).click()
  assert.equal(await list.locator('tbody tr').count(), 2)
  await cards.getByRole('button', { name: /全部项目/ }).click()
  assert.equal(await list.locator('tbody tr').count(), 3)

  // 阶段为派生只读：不再渲染可编辑下拉，也不发起阶段修改请求
  assert.equal(await page.locator('select[aria-label$="的项目阶段"]').count(), 0)
  await list.getByText('交付中').first().waitFor()
  await list.getByText('设计中').first().waitFor()

  // 进度列：交付中区域/全部区域的百分比；无区域项目显示占位
  assert.ok((await list.locator('thead').innerText()).includes('进度'), '列表包含进度列')
  const deliveringRow = list.locator('tbody tr', { hasText: '总部办公' })
  assert.ok((await deliveringRow.innerText()).includes('100%'), '全部楼层确认的项目进度 100%')
  assert.ok((await deliveringRow.innerText()).includes('4/4 区域'))
  const designingRow = list.locator('tbody tr', { hasText: '设计项目' })
  assert.ok((await designingRow.innerText()).includes('40%'), '部分交付按区域占比取整')
  assert.ok((await designingRow.innerText()).includes('2/5 区域'))
  const emptyRow = list.locator('tbody tr', { hasText: '空白项目' })
  assert.ok((await emptyRow.innerText()).includes('—'), '无区域项目进度显示占位')

  await page.getByRole('searchbox', { name: '搜索项目名称' }).fill('不存在')
  await list.getByText('没有匹配的项目，换个关键词试试。').waitFor()
  assert.equal(await cards.getByRole('button', { name: /全部项目/ }).innerText(), '全部项目\n3', '搜索不改变分类统计')
  await page.getByRole('searchbox', { name: '搜索项目名称' }).fill('')

  await mkdir('/tmp/rsdp-project-status', { recursive: true })
  await page.screenshot({ path: '/tmp/rsdp-project-status/desktop.png' })
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await cards.getByRole('button').count(), 4)
  await page.screenshot({ path: '/tmp/rsdp-project-status/mobile.png' })
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), '窄屏卡片和列表不能撑宽页面')
  assert.deepEqual(errors, [])
  await context.close()
  console.log('Project status E2E passed: derived read-only stages, counts, filters, search and mobile layout. API mocked.')
} finally {
  await browser.close()
}
