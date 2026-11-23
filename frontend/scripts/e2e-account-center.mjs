import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const baseUrl = process.env.BASE_URL || 'http://localhost:3001'
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })

const project = {
  projectId: 'PROJ-1',
  name: '报价测试项目',
  customerRequirements: '',
  requirementsRevision: 1,
  leader: { userId: 'DEALER-1', username: 'dealer', displayName: '开发经销商主号', role: 'LEADER' },
  collaborators: [],
  referenceImages: [],
  floors: [{ projectId: 'PROJ-1', floorId: 'FLOOR-1', name: '1F', sortOrder: 1 }],
}
const quote = {
  quotationId: 'QT-20261122-01',
  projectId: 'PROJ-1',
  projectName: '报价测试项目',
  revision: 2,
  status: 'CONFIRMED',
  pricingMode: 'COEFFICIENT',
  pricingValue: 1.18,
  goodsTotal: 118420,
  serviceFee: 6000,
  logisticsFee: 4220,
  fees: [],
  totalAmount: 128640,
  lineCount: 48,
  missingPriceLines: 0,
  createdBy: 'DEALER-1',
  createdAt: '2026-11-22T15:40:00',
  lines: [],
}
const workspaceUsers = [
  { userId: 'DEALER-1', username: 'dealer', displayName: '开发经销商主号', role: 'DEALER', source: null, status: 'active', createdAt: '2026-11-01T09:00:00' },
  { userId: 'U-2', username: 'lead', displayName: '项目负责人', role: 'USER', source: 'DEALER_STAFF', status: 'active', createdAt: '2026-11-02T09:00:00' },
  { userId: 'U-3', username: 'designer', displayName: '空间设计师', role: 'USER', source: 'DEALER_STAFF', status: 'active', createdAt: '2026-11-03T09:00:00' },
  { userId: 'U-4', username: 'former', displayName: '已停用员工', role: 'USER', source: 'DEALER_STAFF', status: 'disabled', createdAt: '2026-11-04T09:00:00' },
]

async function openAs(user) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 960 } })
  const reads = { latestQuote: 0, workspaceUsers: 0, sharedCatalog: 0, favoriteProductIds: 0 }
  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: user } }))
  await context.route('**/api/v1/**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const respond = data => route.fulfill({ json: { code: 200, message: '成功', data } })
    if (path === '/api/v1/office/projects' && request.method() === 'GET') return respond([project])
    if (path === '/api/v1/office/projects/PROJ-1/quotation/latest') {
      reads.latestQuote += 1
      return respond(quote)
    }
    if (path === '/api/v1/office/workspace/users') {
      reads.workspaceUsers += 1
      return respond(workspaceUsers)
    }
    if (path === '/api/v1/office/workspace/shared-catalog') {
      reads.sharedCatalog += 1
      return respond({ workspaceEnabled: true, dealer: true, effectiveVisible: true })
    }
    if (path === '/api/v1/products') return respond([])
    if (path === '/api/v1/office/favorites/product-ids') {
      reads.favoriteProductIds += 1
      return respond([])
    }
    if (path === '/api/v1/products/capabilities') return respond({ managed: false })
    return respond(path.endsWith('/canvas') || path.endsWith('/workspace/me') ? null : [])
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  return { context, page, errors, reads }
}

try {
  const dealer = await openAs({
    userId: 'DEALER-1', username: 'dealer', displayName: '开发经销商主号', role: 'DEALER', workspaceType: 'DEALER',
  })
  await dealer.page.goto(`${baseUrl}/products`, { waitUntil: 'networkidle' })
  await dealer.page.getByTestId('user-session-role').filter({ hasText: /^经销商主号$/ }).waitFor()
  const nav = dealer.page.getByRole('navigation', { name: '工作区导航' })
  assert.equal(await nav.getByRole('link', { name: '商品报价', exact: true }).count(), 0, '商品报价不再作为侧栏独立入口')
  assert.equal(await nav.getByRole('link', { name: '工作区账号', exact: true }).count(), 0, '工作区账号不再作为侧栏独立入口')
  assert.equal(dealer.reads.latestQuote, 0, '最近报价不得在页面加载时预取')
  assert.equal(dealer.reads.workspaceUsers, 0, '工作区成员不得在页面加载时预取')

  const trigger = dealer.page.getByTestId('workspace-center-trigger')
  await trigger.waitFor()
  assert.equal(await trigger.getAttribute('aria-expanded'), 'false')
  const sharedCatalogReadsBeforeOpen = dealer.reads.sharedCatalog
  const favoriteReadsBeforeOpen = dealer.reads.favoriteProductIds
  await trigger.hover()
  const panel = dealer.page.getByTestId('workspace-center-panel')
  await panel.waitFor()
  assert.equal(await trigger.getAttribute('aria-expanded'), 'true', '悬停账号入口应标记面板已展开')
  await panel.getByText('最近', { exact: true }).waitFor()
  await dealer.page.waitForTimeout(180)
  assert.equal(dealer.reads.latestQuote, 1, '经销商展开后读取最近报价徽标')
  assert.equal(dealer.reads.workspaceUsers, 1, '经销商展开后读取成员徽标')
  assert.equal(dealer.reads.favoriteProductIds, favoriteReadsBeforeOpen + 1, '展开后在商品页自身加载之外再读取一次个人收藏数徽标')
  assert.equal(dealer.reads.sharedCatalog, sharedCatalogReadsBeforeOpen, '精简菜单不再读取公共库预览')

  const favoritesEntry = panel.getByRole('link', { name: /^收藏/ })
  const quotesEntry = panel.getByRole('link', { name: /^商品报价/ })
  const accountsEntry = panel.getByRole('link', { name: /^工作区账号/ })
  assert.equal(await favoritesEntry.getAttribute('href'), '/favorites', '收藏入口应直达收藏页面')
  assert.match(await panel.getByRole('link').first().textContent(), /收藏/, '收藏入口位于菜单第一项')
  assert.equal(await quotesEntry.getAttribute('href'), '/workspace/quotes', '商品报价入口应直达报价页面')
  assert.equal(await accountsEntry.getAttribute('href'), '/workspace/users', '工作区账号入口应直达账号页面')
  assert.equal(await panel.getByText('当前账号', { exact: true }).count(), 0, '浮层不显示当前账号区域')
  assert.equal(await panel.getByText('最近报价', { exact: true }).count(), 0, '浮层不显示报价预览区域')
  assert.equal(await panel.getByTestId('workspace-center-accounts').count(), 0, '浮层不显示成员预览区域')
  assert.equal(await panel.getByText('开发经销商主号', { exact: true }).count(), 0, '浮层不重复显示账号信息')
  const panelBox = await panel.boundingBox()
  const triggerBox = await trigger.boundingBox()
  assert.ok(Math.abs(panelBox.width - 192) < 2, '浮层收缩为单列菜单宽度')
  assert.ok(panelBox.height < 200, '浮层只保留标题和三项菜单')
  assert.ok(Math.abs(panelBox.x - 16) < 2, '浮层左对齐侧栏账号区域')
  assert.ok(Math.abs(panelBox.y + panelBox.height - (triggerBox.y - 8)) < 2, '浮层位于账号行正上方并保留 8px 间距')
  assert.equal(await panel.getByRole('link', { name: '查看全部报价', exact: true }).count(), 0, '底部报价入口已移除')
  assert.equal(await panel.getByRole('link', { name: '进入工作区账号', exact: true }).count(), 0, '底部账号入口已移除')

  await panel.hover()
  await dealer.page.waitForTimeout(250)
  assert.equal(await trigger.getAttribute('aria-expanded'), 'true', '鼠标持续悬停浮层时面板应保持展开')
  await trigger.hover()
  await dealer.page.waitForTimeout(250)
  assert.equal(await trigger.getAttribute('aria-expanded'), 'true', '鼠标从浮层移回账号行时面板应保持展开')
  await panel.hover()
  await dealer.page.waitForTimeout(250)
  assert.equal(await trigger.getAttribute('aria-expanded'), 'true', '鼠标在账号行与浮层之间往返时面板应保持展开')
  await dealer.page.mouse.move(1100, 120)
  await panel.waitFor({ state: 'hidden' })
  assert.equal(await trigger.getAttribute('aria-expanded'), 'false', '鼠标离开账号与浮层后面板应收起')

  await trigger.focus()
  await dealer.page.keyboard.press('Enter')
  await panel.waitFor()
  await dealer.page.keyboard.press('Escape')
  await panel.waitFor({ state: 'hidden' })
  assert.equal(await dealer.page.evaluate(() => document.activeElement?.getAttribute('data-testid')), 'workspace-center-trigger', 'Esc 收起后焦点应回到账号入口')

  await trigger.hover()
  await panel.waitFor()
  await panel.getByRole('link', { name: /^商品报价/ }).click()
  await dealer.page.waitForURL('**/workspace/quotes')
  await dealer.page.goto(`${baseUrl}/products`, { waitUntil: 'networkidle' })
  await trigger.hover()
  await panel.waitFor()
  await panel.getByRole('link', { name: /^工作区账号/ }).click()
  await dealer.page.waitForURL('**/workspace/users')
  await dealer.page.goto(`${baseUrl}/products`, { waitUntil: 'networkidle' })
  await trigger.hover()
  await panel.waitFor()
  await panel.getByRole('link', { name: /^收藏/ }).click()
  await dealer.page.waitForURL('**/favorites')

  assert.deepEqual(dealer.errors, [])
  await dealer.context.close()

  const designer = await openAs({
    userId: 'U-3', username: 'designer', displayName: '空间设计师', role: 'USER', workspaceType: 'DEALER',
  })
  await designer.page.goto(`${baseUrl}/products`, { waitUntil: 'networkidle' })
  await designer.page.getByText('空间设计师', { exact: true }).first().waitFor()
  const designerTrigger = designer.page.getByTestId('workspace-center-trigger')
  await designerTrigger.waitFor()
  const designerFavoriteReadsBeforeOpen = designer.reads.favoriteProductIds
  await designerTrigger.hover()
  const designerPanel = designer.page.getByTestId('workspace-center-panel')
  await designerPanel.waitFor()
  assert.equal(await designerPanel.getByRole('link', { name: /^收藏/ }).getAttribute('href'), '/favorites', '非经销商也有收藏入口')
  assert.equal(await designerPanel.getByRole('link', { name: /商品报价|工作区账号/ }).count(), 0, '非经销商浮层不得出现报价/账号入口')
  assert.equal(await designerPanel.getByRole('link').count(), 1, '非经销商浮层只有收藏一个入口')
  assert.equal(await designer.page.getByRole('navigation', { name: '工作区导航' }).getByRole('link', { name: /商品报价|工作区账号/ }).count(), 0)
  assert.equal(await designer.page.getByRole('button', { name: '退出登录', exact: true }).count(), 1, '普通成员仍保留现有退出登录')
  await designer.page.waitForTimeout(180)
  assert.equal(designer.reads.latestQuote, 0, '非经销商不得请求报价数据')
  assert.equal(designer.reads.workspaceUsers, 0, '非经销商不得请求成员数据')
  assert.equal(designer.reads.favoriteProductIds, designerFavoriteReadsBeforeOpen + 1, '非经销商展开后同样读取个人收藏数徽标')
  await designerPanel.getByRole('link', { name: /^收藏/ }).click()
  await designer.page.waitForURL('**/favorites')
  assert.deepEqual(designer.errors, [])
  await designer.context.close()

  const admin = await openAs({
    userId: 'ADMIN-1', username: 'admin', displayName: '系统管理员', role: 'ADMIN', workspaceType: null,
  })
  await admin.page.goto(`${baseUrl}/products`, { waitUntil: 'networkidle' })
  await admin.page.getByTestId('user-session-role').filter({ hasText: /^系统管理员$/ }).waitFor()
  const adminTrigger = admin.page.getByTestId('workspace-center-trigger')
  await adminTrigger.waitFor()
  await adminTrigger.hover()
  const adminPanel = admin.page.getByTestId('workspace-center-panel')
  await adminPanel.waitFor()
  assert.equal(await adminPanel.getByRole('link', { name: /^收藏/ }).count(), 1, '管理员也有收藏入口')
  assert.equal(await adminPanel.getByRole('link', { name: /商品报价|工作区账号/ }).count(), 0, '管理员浮层不得出现经销商入口')
  await admin.page.waitForTimeout(180)
  assert.equal(admin.reads.latestQuote, 0, '管理员不得请求报价数据')
  assert.equal(admin.reads.workspaceUsers, 0, '管理员不得请求成员数据')
  assert.equal(await admin.page.getByRole('navigation', { name: '工作区导航' }).getByRole('link', { name: '用户管理', exact: true }).count(), 1, '管理员原有系统管理入口保留')
  assert.deepEqual(admin.errors, [])
  await admin.context.close()

  console.log('Account center E2E passed: dealer compact links, keyboard close, role isolation and no prefetch.')
} finally {
  await browser.close()
}