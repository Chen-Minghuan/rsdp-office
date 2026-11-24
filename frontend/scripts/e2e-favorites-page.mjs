import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const baseUrl = process.env.BASE_URL || 'http://localhost:3001'
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })

const projects = [
  {
    projectId: 'PROJ-1',
    name: '湖南科技有限公司',
    customerRequirements: '',
    requirementsRevision: 1,
    leader: { userId: 'DEALER-1', username: 'dealer', displayName: '开发经销商主号', role: 'LEADER' },
    collaborators: [],
    referenceImages: [],
    floors: [{ projectId: 'PROJ-1', floorId: 'FLOOR-1', name: '1F', sortOrder: 1 }],
  },
  {
    projectId: 'PROJ-2',
    name: '本地开发示例项目',
    customerRequirements: '',
    requirementsRevision: 1,
    leader: { userId: 'DEALER-1', username: 'dealer', displayName: '开发经销商主号', role: 'LEADER' },
    collaborators: [],
    referenceImages: [],
    floors: [{ projectId: 'PROJ-2', floorId: 'FLOOR-2', name: '1F', sortOrder: 1 }],
  },
  {
    projectId: 'PROJ-3',
    name: '空白演示项目',
    customerRequirements: '',
    requirementsRevision: 1,
    leader: { userId: 'DEALER-1', username: 'dealer', displayName: '开发经销商主号', role: 'LEADER' },
    collaborators: [],
    referenceImages: [],
    floors: [{ projectId: 'PROJ-3', floorId: 'FLOOR-3', name: '1F', sortOrder: 1 }],
  },
]

const products = {
  P1: { rspuId: 'P1', rspuCode: 'RSPU-1', productName: '职员桌四人位简约款', primaryImageUrl: null, retailPrice: 1280, status: 'active', updatedAt: '2026-11-20T10:00:00' },
  P2: { rspuId: 'P2', rspuCode: 'RSPU-2', productName: '人体工学网布办公椅', primaryImageUrl: null, retailPrice: 860, status: 'active', updatedAt: '2026-11-20T10:00:00' },
  P3: { rspuId: 'P3', rspuCode: 'RSPU-3', productName: '会议桌六人位板式款', primaryImageUrl: null, retailPrice: 3450, status: 'active', updatedAt: '2026-11-20T10:00:00' },
}
const dealerUser = { userId: 'DEALER-1', username: 'dealer', displayName: '开发经销商主号', role: 'DEALER', workspaceType: 'DEALER' }

async function openAs(user) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 960 } })
  const state = {
    folders: [
      { folderId: 'F0', name: '默认收藏夹', defaultFolder: true },
      { folderId: 'F1', name: '会议室方案', defaultFolder: false },
    ],
    membership: { P1: new Set(['F0']), P2: new Set(['F0']), P3: new Set(['F1']) },
    projectItems: { 'PROJ-1': [products.P3, products.P1], 'PROJ-2': [products.P2], 'PROJ-3': [] },
    projectFavoriteSaves: [],
    personalSaves: [],
    deletedFolders: [],
    delayInitialFolders: false,
    foldersReadDone: false,
    reads: { latestQuote: 0, workspaceUsers: 0 },
  }
  const personalAll = () => Object.keys(products).filter(id => state.membership[id].size > 0).map(id => products[id])
  const folderDtos = rspuId => state.folders.map(folder => ({
    folderId: folder.folderId,
    name: folder.name,
    itemCount: Object.values(state.membership).filter(set => set.has(folder.folderId)).length,
    defaultFolder: folder.defaultFolder,
    selected: rspuId ? state.membership[rspuId]?.has(folder.folderId) ?? false : false,
  }))

  await context.route('**/api/auth/me', route => route.fulfill({ json: { code: 200, data: user } }))
  await context.route('**/api/v1/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const respond = data => route.fulfill({ json: { code: 200, message: '成功', data } })
    if (path === '/api/v1/office/projects' && request.method() === 'GET') return respond(projects)
    if (path === '/api/v1/office/projects/PROJ-1/quotation/latest') {
      state.reads.latestQuote += 1
      return respond(null)
    }
    if (path === '/api/v1/office/workspace/users') {
      state.reads.workspaceUsers += 1
      return respond([])
    }
    if (path === '/api/v1/office/workspace/shared-catalog') return respond({ workspaceEnabled: true, dealer: user.role === 'DEALER', effectiveVisible: true })
    if (path === '/api/v1/office/favorites/product-ids') return respond(personalAll().map(product => product.rspuId))
    if (path === '/api/v1/office/favorites/folders' && request.method() === 'GET') {
      const rspuId = url.searchParams.get('rspuId')
      if (state.delayInitialFolders && !state.foldersReadDone && !rspuId) {
        state.foldersReadDone = true
        const snapshot = folderDtos(null)
        await new Promise(resolve => setTimeout(resolve, 700))
        return respond(snapshot)
      }
      return respond(folderDtos(rspuId))
    }
    if (path === '/api/v1/office/favorites/folders' && request.method() === 'POST') {
      const body = request.postDataJSON()
      const folder = { folderId: `F-${state.folders.length + 10}`, name: body.name, defaultFolder: false }
      state.folders.push(folder)
      return respond({ ...folder, itemCount: 0, selected: false })
    }
    const folderDeleteMatch = path.match(/^\/api\/v1\/office\/favorites\/folders\/([^/]+)$/)
    if (folderDeleteMatch && request.method() === 'DELETE') {
      const folderId = folderDeleteMatch[1]
      state.deletedFolders.push(folderId)
      state.folders = state.folders.filter(folder => folder.folderId !== folderId)
      for (const set of Object.values(state.membership)) set.delete(folderId)
      return respond(null)
    }
    const saveProductMatch = path.match(/^\/api\/v1\/office\/favorites\/products\/([^/]+)$/)
    if (saveProductMatch && request.method() === 'PUT') {
      if (state.holdPersonalSave) await state.holdPersonalSave;
      const folderIds = request.postDataJSON().folderIds || []
      state.personalSaves.push({ rspuId: saveProductMatch[1], folderIds })
      state.membership[saveProductMatch[1]] = new Set(folderIds)
      return respond(null)
    }
    if (path === '/api/v1/office/favorites/items') {
      const folderId = url.searchParams.get('folderId')
      const items = personalAll().filter(product => !folderId || state.membership[product.rspuId].has(folderId))
      return respond(items)
    }
    const projectItemsMatch = path.match(/^\/api\/v1\/office\/projects\/([^/]+)\/favorites\/items$/)
    if (projectItemsMatch) return respond(state.projectItems[projectItemsMatch[1]] || [])
    const projectFavoriteMatch = path.match(/^\/api\/v1\/office\/projects\/([^/]+)\/favorites\/([^/]+)$/)
    if (projectFavoriteMatch && request.method() === 'GET') return respond({ selected: state.projectItems[projectFavoriteMatch[1]]?.some(p => p.rspuId === projectFavoriteMatch[2]) ?? false })
    if (projectFavoriteMatch && request.method() === 'PUT') {
      const [, projectId, rspuId] = projectFavoriteMatch
      const selected = request.postDataJSON().selected
      state.projectFavoriteSaves.push({ projectId, rspuId, selected })
      const list = state.projectItems[projectId] || []
      state.projectItems[projectId] = selected
        ? (list.some(p => p.rspuId === rspuId) ? list : [...list, products[rspuId]])
        : list.filter(p => p.rspuId !== rspuId)
      return respond({ selected })
    }
    if (path === '/api/v1/products/page') return respond({ rows: [], total: 0, catalogTotal: 0, activeTotal: 0 })
    if (path === '/api/v1/products/capabilities') return respond({ managed: false })
    return respond(path.endsWith('/canvas') || path.endsWith('/workspace/me') ? null : [])
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  return { context, page, errors, state }
}

try {
  // 清理其他商品、移除当前商品和删除文件夹时，分别保留当前商品的未提交勾选。
  const draft = await openAs(dealerUser)
  await draft.page.goto(`${baseUrl}/favorites`, { waitUntil: 'networkidle' })
  const draftDialog = draft.page.getByRole('dialog', { name: '收藏商品', exact: true })
  await draft.page.getByRole('button', { name: `调整收藏夹${products.P1.productName}`, exact: true }).click()
  await draftDialog.getByRole('checkbox', { name: /^会议室方案，/ }).check()
  await draftDialog.getByRole('button', { name: '展开收藏夹默认收藏夹', exact: true }).click()
  let releaseRemoval
  draft.state.holdPersonalSave = new Promise(resolve => { releaseRemoval = resolve })
  const removingOther = draft.page.waitForRequest(request => request.method() === 'PUT'
    && new URL(request.url()).pathname.endsWith('/favorites/products/P2'))
  await draftDialog.getByRole('button', { name: `取消收藏${products.P2.productName}`, exact: true }).click()
  await removingOther
  assert.equal(await draftDialog.getByRole('button', { name: /^确定收藏/ }).isDisabled(), true, '取消其他收藏进行中不能同时提交旧勾选')
  draft.state.holdPersonalSave = null
  releaseRemoval()
  await draftDialog.getByRole('button', { name: /^确定收藏/ }).waitFor({ state: 'visible' })
  await draft.page.waitForFunction(() => !document.querySelector('dialog[open] button[aria-label="取消收藏人体工学网布办公椅"]'))
  assert.equal(await draftDialog.getByRole('checkbox', { name: /^会议室方案，/ }).isChecked(), true, '清理其他商品不得覆盖未保存的勾选')
  await draftDialog.getByRole('button', { name: /^确定收藏/ }).click()
  await draftDialog.waitFor({ state: 'hidden' })
  assert.deepEqual([...draft.state.membership.P1].sort(), ['F0', 'F1'])

  await draft.page.getByRole('button', { name: `调整收藏夹${products.P1.productName}`, exact: true }).click()
  await draftDialog.getByRole('checkbox', { name: /^会议室方案，/ }).uncheck()
  await draftDialog.getByRole('button', { name: '展开收藏夹默认收藏夹', exact: true }).click()
  await draftDialog.getByRole('button', { name: `取消收藏${products.P1.productName}`, exact: true }).click()
  await draft.page.waitForFunction(() => !document.querySelector('dialog[open] button[aria-label="取消收藏职员桌四人位简约款"]'))
  assert.equal(await draftDialog.getByRole('checkbox', { name: /^默认收藏夹，/ }).isChecked(), false, '直接取消当前商品应更新该文件夹勾选')
  assert.equal(await draftDialog.getByRole('checkbox', { name: /^会议室方案，/ }).isChecked(), false, '当前商品的其他草稿修改应保留')
  await draftDialog.getByRole('button', { name: '取消', exact: true }).click()
  assert.deepEqual([...draft.state.membership.P1], ['F1'], '取消弹窗不应提交其他草稿')

  await draft.page.getByRole('button', { name: `调整收藏夹${products.P3.productName}`, exact: true }).click()
  await draftDialog.getByRole('checkbox', { name: /^默认收藏夹，/ }).check()
  await draftDialog.getByRole('button', { name: '删除收藏夹会议室方案', exact: true }).click()
  const draftDelete = draft.page.getByRole('dialog', { name: '删除收藏夹', exact: true })
  await draftDelete.getByRole('button', { name: '确认删除', exact: true }).click()
  await draftDelete.waitFor({ state: 'hidden' })
  await draft.page.waitForFunction(() => !document.querySelector('dialog[open] input[aria-label^="会议室方案，"]'))
  assert.equal(await draftDialog.getByRole('checkbox', { name: /^默认收藏夹，/ }).isChecked(), true, '删除文件夹只清理被删除的 ID，其余草稿保留')
  await draftDialog.getByRole('button', { name: /^确定收藏/ }).click()
  await draftDialog.waitFor({ state: 'hidden' })
  assert.deepEqual([...draft.state.membership.P3], ['F0'])
  await draft.context.close()

  const dealer = await openAs(dealerUser)
  const { page, state } = dealer
  await page.goto(`${baseUrl}/favorites`, { waitUntil: 'networkidle' })

  await page.getByRole('heading', { name: '我的收藏' }).waitFor()
  assert.equal(await page.getByRole('tab', { name: '个人收藏' }).getAttribute('aria-selected'), 'true', '默认打开个人收藏页签')

  const personalPanel = page.getByTestId('personal-favorites-panel')
  await personalPanel.getByRole('button', { name: /^默认收藏夹/ }).waitFor()
  assert.equal(await personalPanel.getByRole('button', { name: /^全部收藏/ }).count(), 1, '收藏夹栏包含全部收藏')
  await personalPanel.getByRole('button', { name: /^会议室方案/ }).waitFor()

  const grid = page.getByRole('list', { name: '收藏商品列表' })
  await grid.getByText('职员桌四人位简约款', { exact: true }).waitFor()
  assert.equal(await grid.getByRole('listitem').count(), 3, '全部收藏展示三件商品')

  await personalPanel.getByRole('button', { name: /^会议室方案/ }).click()
  await grid.getByText('会议桌六人位板式款', { exact: true }).waitFor()
  assert.equal(await grid.getByRole('listitem').count(), 1, '切换收藏夹后只展示该收藏夹商品')

  await page.getByPlaceholder('搜索收藏的商品').fill('不存在的关键词')
  await page.getByText('没有找到匹配的商品', { exact: true }).waitFor()
  await page.getByPlaceholder('搜索收藏的商品').fill('')
  await grid.getByText('会议桌六人位板式款', { exact: true }).waitFor()

  await personalPanel.getByRole('button', { name: '新建收藏夹', exact: true }).click()
  await page.getByPlaceholder('输入收藏夹名称').fill('接待区选品')
  await page.getByRole('button', { name: '创建收藏夹', exact: true }).click()
  await personalPanel.getByRole('button', { name: /^接待区选品/ }).waitFor()

  await personalPanel.getByRole('button', { name: /^默认收藏夹/ }).click()
  await grid.getByText('人体工学网布办公椅', { exact: true }).waitFor()
  const dialog = page.getByRole('dialog', { name: '收藏商品' })
  await grid.getByRole('button', { name: '调整收藏夹人体工学网布办公椅', exact: true }).click()
  await dialog.waitFor()
  assert.equal(await dialog.getByRole('tab', { name: '个人收藏' }).getAttribute('aria-selected'), 'true', '调整收藏夹打开弹窗个人页签')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })

  // 项目页签：卡片爱心直接取消项目收藏
  await page.getByRole('tab', { name: '项目收藏' }).click()
  const projectPanel = page.getByTestId('project-favorites-panel')
  const projectSelect = page.getByRole('combobox', { name: '选择项目' })
  await projectSelect.waitFor()
  assert.equal(await projectSelect.inputValue(), 'PROJ-1', '项目选择器默认当前项目')
  await grid.getByText('会议桌六人位板式款', { exact: true }).waitFor()
  assert.equal(await grid.getByRole('listitem').count(), 2, '项目收藏展示当前项目两件商品')

  await grid.getByRole('button', { name: '取消收藏职员桌四人位简约款', exact: true }).click()
  const removeDialog = page.getByRole('dialog', { name: '取消收藏' })
  await removeDialog.waitFor()
  await removeDialog.getByText('湖南科技有限公司', { exact: false }).first().waitFor()
  await removeDialog.getByRole('button', { name: '确认取消收藏', exact: true }).click()
  await removeDialog.waitFor({ state: 'hidden' })
  assert.deepEqual(state.projectFavoriteSaves[0], { projectId: 'PROJ-1', rspuId: 'P1', selected: false }, '项目卡片取消应保存 selected=false')
  await grid.getByText('会议桌六人位板式款', { exact: true }).waitFor()
  assert.equal(await grid.getByRole('listitem').count(), 1, '取消后项目列表移除该商品')

  // 跨项目调整弹窗仍默认正在查看的项目
  await projectSelect.selectOption('PROJ-2')
  await grid.getByText('人体工学网布办公椅', { exact: true }).waitFor()
  await grid.getByRole('button', { name: '调整收藏夹人体工学网布办公椅', exact: true }).click()
  await dialog.waitFor()
  assert.equal(await dialog.getByRole('tab', { name: '项目收藏' }).getAttribute('aria-selected'), 'true', '调整入口打开弹窗项目页签')
  assert.equal(await dialog.getByRole('radio', { name: '本地开发示例项目' }).isChecked(), true, '非当前项目的弹窗应默认选中正在查看的项目')
  await page.waitForFunction(() => [...document.querySelectorAll('dialog button')]
    .some(button => button.textContent === '取消收藏' && !button.disabled))
  await dialog.getByRole('button', { name: '取消收藏', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  assert.deepEqual(state.projectFavoriteSaves[1], { projectId: 'PROJ-2', rspuId: 'P2', selected: false }, '跨项目取消收藏也必须写入正在查看的项目')
  await projectPanel.getByText('该项目还没有收藏的商品', { exact: true }).waitFor()

  await projectSelect.selectOption('PROJ-3')
  await projectPanel.getByText('该项目还没有收藏的商品', { exact: true }).waitFor()
  assert.equal(await grid.count(), 0, '空项目不展示商品网格')
  const toProducts = projectPanel.getByRole('link', { name: '去商品库挑选', exact: true })
  assert.equal(await toProducts.getAttribute('href'), '/products', '空状态提供商品库入口')

  await projectSelect.selectOption('PROJ-1')
  await grid.getByText('会议桌六人位板式款', { exact: true }).waitFor()
  await grid.getByRole('button', { name: '调整收藏夹会议桌六人位板式款', exact: true }).click()
  await dialog.waitFor()
  assert.equal(await dialog.getByRole('tab', { name: '项目收藏' }).getAttribute('aria-selected'), 'true')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })

  // 个人页签：弹窗展开收藏夹并逐件取消收藏
  await page.getByRole('tab', { name: '个人收藏' }).click()
  await grid.getByText('人体工学网布办公椅', { exact: true }).waitFor()

  const accountTrigger = page.getByTestId('workspace-center-trigger')
  await accountTrigger.hover()
  const accountPanel = page.getByTestId('workspace-center-panel')
  await accountPanel.waitFor()
  await page.waitForFunction(() => document.querySelector('[data-testid="workspace-center-panel"] a[href="/favorites"]')?.textContent?.includes('3'), { timeout: 5000 })
  await page.mouse.move(1200, 120)
  await accountPanel.waitFor({ state: 'hidden' })

  await grid.getByRole('button', { name: '调整收藏夹人体工学网布办公椅', exact: true }).click()
  await dialog.waitFor()
  await dialog.getByRole('button', { name: '展开收藏夹默认收藏夹', exact: true }).click()
  await dialog.getByRole('button', { name: '取消收藏人体工学网布办公椅', exact: true }).waitFor()
  assert.equal(await dialog.getByRole('button', { name: '取消收藏职员桌四人位简约款', exact: true }).count(), 1, '展开后能看到收藏夹内两件商品')
  await dialog.getByRole('button', { name: '取消收藏人体工学网布办公椅', exact: true }).click()
  await dialog.getByRole('button', { name: '取消收藏人体工学网布办公椅', exact: true }).waitFor({ state: 'detached' })
  assert.equal(await dialog.isVisible(), true, '逐件取消收藏后弹窗保持打开')
  assert.deepEqual(state.personalSaves.at(-1), { rspuId: 'P2', folderIds: [] }, '逐件取消应保存去掉当前收藏夹后的勾选集合')
  await dialog.getByRole('checkbox', { name: /默认收藏夹，1件/ }).waitFor()
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  assert.equal(await grid.getByRole('listitem').count(), 1, '取消后默认收藏夹只剩一件商品')
  await personalPanel.getByRole('button', { name: /^全部收藏\s*2/ }).waitFor()

  await accountTrigger.hover()
  await accountPanel.waitFor()
  await page.waitForFunction(() => document.querySelector('[data-testid="workspace-center-panel"] a[href="/favorites"]')?.textContent?.includes('2'), { timeout: 5000 })
  await page.mouse.move(1200, 120)
  await accountPanel.waitFor({ state: 'hidden' })

  // 全部收藏中点击爱心 = 从所有个人收藏夹取消收藏
  await personalPanel.getByRole('button', { name: /^全部收藏/ }).click()
  await grid.getByText('会议桌六人位板式款', { exact: true }).waitFor()
  assert.equal(await grid.getByRole('listitem').count(), 2)
  await grid.getByRole('button', { name: '取消收藏会议桌六人位板式款', exact: true }).click()
  await removeDialog.waitFor()
  await removeDialog.getByText('所有个人收藏夹', { exact: false }).waitFor()
  await removeDialog.getByRole('button', { name: '确认取消收藏', exact: true }).click()
  await removeDialog.waitFor({ state: 'hidden' })
  assert.deepEqual(state.personalSaves.at(-1), { rspuId: 'P3', folderIds: [] }, '全部收藏中取消应清空所有收藏夹勾选')
  assert.equal(await grid.getByRole('listitem').count(), 1, '取消后全部收藏只剩一件商品')

  // 页面级删除收藏夹（F1 已空）
  assert.equal(await personalPanel.getByRole('button', { name: '删除收藏夹默认收藏夹', exact: true }).count(), 0, '默认收藏夹不提供删除按钮')
  await personalPanel.getByRole('button', { name: /^会议室方案/ }).click()
  await personalPanel.getByText('该收藏夹还没有商品', { exact: true }).waitFor()
  await personalPanel.getByRole('button', { name: '删除收藏夹会议室方案', exact: true }).click()
  const deleteDialog = page.getByRole('dialog', { name: '删除收藏夹' })
  await deleteDialog.waitFor()
  await deleteDialog.getByRole('button', { name: '确认删除', exact: true }).click()
  await deleteDialog.waitFor({ state: 'hidden' })
  assert.deepEqual(state.deletedFolders, ['F1'], '删除请求应指向被确认的收藏夹')
  await personalPanel.getByRole('button', { name: /^会议室方案/ }).waitFor({ state: 'detached' })
  await grid.getByText('职员桌四人位简约款', { exact: true }).waitFor()
  assert.equal(await grid.getByRole('listitem').count(), 1, '删除当前收藏夹后回到全部收藏')

  // 弹窗内删除收藏夹
  await grid.getByRole('button', { name: '调整收藏夹职员桌四人位简约款', exact: true }).click()
  await dialog.waitFor()
  await dialog.getByRole('checkbox', { name: /默认收藏夹/ }).waitFor()
  assert.equal(await dialog.getByRole('button', { name: '删除收藏夹默认收藏夹', exact: true }).count(), 0, '弹窗中默认收藏夹不可删除')
  await dialog.getByRole('button', { name: '删除收藏夹接待区选品', exact: true }).click()
  const dialogDelete = page.getByRole('dialog', { name: '删除收藏夹' })
  await dialogDelete.waitFor()
  await dialogDelete.getByRole('button', { name: '确认删除', exact: true }).click()
  await dialogDelete.waitFor({ state: 'hidden' })
  await dialog.getByRole('checkbox', { name: /接待区选品/ }).waitFor({ state: 'detached' })
  assert.equal(await dialog.isVisible(), true, '删除收藏夹后收藏弹窗保持打开')
  assert.deepEqual(state.deletedFolders, ['F1', 'F-12'], '弹窗内删除应调用同一删除接口')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })

  assert.deepEqual(dealer.errors, [])
  await dealer.context.close()

  const member = await openAs({
    userId: 'U-3', username: 'designer', displayName: '空间设计师', role: 'USER', workspaceType: 'DEALER',
  })
  await member.page.goto(`${baseUrl}/favorites`, { waitUntil: 'networkidle' })
  await member.page.getByRole('heading', { name: '我的收藏' }).waitFor()
  await member.page.getByRole('list', { name: '收藏商品列表' }).getByText('职员桌四人位简约款', { exact: true }).waitFor()
  assert.equal(member.state.reads.latestQuote, 0, '收藏页面不请求报价数据')
  assert.equal(member.state.reads.workspaceUsers, 0, '收藏页面不请求成员数据')
  assert.deepEqual(member.errors, [])
  await member.context.close()

  const stale = await openAs(dealerUser)
  stale.state.delayInitialFolders = true
  await stale.page.goto(`${baseUrl}/favorites`, { waitUntil: 'domcontentloaded' })
  await stale.page.getByRole('button', { name: '新建收藏夹', exact: true }).click()
  await stale.page.getByPlaceholder('输入收藏夹名称').fill('慢响应分组')
  await stale.page.getByRole('button', { name: '创建收藏夹', exact: true }).click()
  await stale.page.getByRole('button', { name: /^慢响应分组/ }).waitFor()
  await stale.page.waitForTimeout(1100)
  assert.equal(await stale.page.getByRole('button', { name: /^慢响应分组/ }).count(), 1, '旧的慢响应不得覆盖新建后的收藏夹列表')
  assert.deepEqual(stale.errors, [])
  await stale.context.close()

  console.log('Favorites page E2E passed: expand folders, per-item remove, card remove confirm, adjust entry, cross-project dialog, delete folder, stale guard, badge refresh, empty state, role access.')
} finally {
  await browser.close()
}
