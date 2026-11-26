import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

// 工作台项目列表刷新回归：项目阶段由负责人/主号人工维护（mock 用可变状态模拟），
// 区域进度仍由后端在列表接口按方案确认读时派生，前端在进入项目列表视图时静默重取。
// 本脚本用可变 mock 状态模拟方案确认、确认失效（清单变化）、新增楼层与外部阶段修改，
// 断言 SPA 返回列表后阶段/进度即时更新。
const cwd = fileURLToPath(new URL('../', import.meta.url))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const baseUrl = `http://127.0.0.1:${port}`
const app = spawn(process.execPath, ['.output/server/index.mjs'], {
  cwd, env: { ...process.env, NITRO_PORT: String(port), NITRO_HOST: '127.0.0.1' }, stdio: 'ignore',
})

const projectId = 'OPRJ-REFRESH'
const floorId = 'OFLR-1F'
const zone = {
  id: 'ROOM', kind: 'zone', x: 0, y: 0, w: 6000, h: 4000, name: '副总办公室', zoneType: 'EXECUTIVE_OFFICE',
  reviewUnit: 'mm', reviewConfirmation: { status: 'CONFIRMED' }, evidenceKind: 'MANUAL',
  polygon: [[0, 0], [6000, 0], [6000, 4000], [0, 4000]],
}
const furniture = {
  id: 'DESK', zoneId: zone.id, kind: 'furniture', x: 900, y: 700, w: 1500, h: 800, rotation: 0,
  furnitureTemplateId: 'desk-straight', layoutConfirmed: true, label: '办公桌',
}
const product = { id: 'SKU', variantId: 'SKU', rspuId: 'RSPU', name: '胡桃木办公桌', variant: '1400×700', price: 1250, widthMm: 1400, depthMm: 700 }
const catalog = [{ rspuId: 'RSPU', productName: product.name, retailPrice: 1250, status: 'active', categoryPath: ['办公家具', '办公桌'] }]
const selected = { ...furniture, productId: product.id, productSelection: { cartLineId: 'LINE', product } }
const cartLine = { lineId: 'LINE', zoneId: zone.id, zoneName: zone.name, projectId: floorId, productId: product.id, qty: 1 }

// 单一可变状态：项目列表处理器按它返回人工维护的阶段并派生进度，写接口负责翻转确认状态。
const state = {
  status: 'DESIGNING',
  floors: [{ floorId, name: '1F', confirmed: false, cartQty: 1, zones: 1 }],
  canvas: { projectId: floorId, revision: 5, mode: 'design', designZoneId: zone.id, objects: [zone, selected], files: [], blank: false },
  cart: [cartLine],
  confirmation: null,
  delayNextProjects: false,
}

function projectDto() {
  return [{
    projectId, name: '总部办公', customerRequirements: '', requirementsRevision: 1,
    status: state.status,
    canManageStatus: false,
    zoneTotal: state.floors.reduce((total, floor) => total + floor.zones, 0),
    zoneDelivered: state.floors.reduce((total, floor) => total + (floor.confirmed && floor.cartQty > 0 ? floor.zones : 0), 0),
    leader: { userId: 'MOCK-USER', username: 'test', displayName: '测试用户', role: 'LEADER' },
    collaborators: [], referenceImages: [],
    floors: state.floors.map((floor, index) => ({ projectId, floorId: floor.floorId, name: floor.name, sortOrder: index + 1 })),
  }]
}

let browser
try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(baseUrl)).ok) break } catch {}
    if (attempt >= 75 || app.exitCode !== null) throw new Error('请先执行 pnpm build')
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined })
  const context = await browser.newContext({ viewport: { width: 1536, height: 1000 } })
  await context.route('**/api/auth/me', route => route.fulfill({ json: {
    code: 200, data: { userId: 'MOCK-USER', username: 'test', displayName: '测试经销商', role: 'DEALER' },
  } }))
  await context.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    const ok = data => route.fulfill({ json: { code: 200, data } })
    const pathFloorId = path.match(/^\/api\/v1\/office\/projects\/([^/]+)\//)?.[1]
    if (path === '/api/v1/office/projects' && method === 'GET') {
      if (state.delayNextProjects) {
        state.delayNextProjects = false
        await new Promise(resolve => setTimeout(resolve, 400))
      }
      return ok(projectDto())
    }
    if (path === '/api/v1/office/projects/budgets') return ok({})
    if (path === `/api/v1/office/projects/${projectId}/floors` && method === 'POST') {
      const floor = { floorId: 'OFLR-2F', name: request.postDataJSON().name, confirmed: false, cartQty: 0, zones: 0 }
      state.floors.push(floor)
      return ok({ projectId, floorId: floor.floorId, name: floor.name, sortOrder: state.floors.length })
    }
    if (path === '/api/v1/products') return ok(catalog)
    if (path === '/api/v1/products/page') return ok({ rows: catalog, total: catalog.length, catalogTotal: catalog.length, activeTotal: catalog.length, page: 1, size: 20 })
    if (path.endsWith('/products/RSPU')) return ok({ variants: [{ variantId: 'SKU', displayName: product.variant, status: 'active' }] })
    if (path.endsWith('/product-bundles')) return ok([])
    if (path.endsWith('/canvas')) {
      if (pathFloorId !== floorId) return ok(null)
      if (method === 'PUT') {
        const draft = request.postDataJSON()
        assert.equal(draft.schemeToken, state.canvas.schemeToken)
        // 画布保存使既有方案确认失效（服务端 retire 语义）
        state.canvas = draft
        state.confirmation = null
        state.floors[0].confirmed = false
      }
      return ok(state.canvas)
    }
    if (path.endsWith('/cart')) return ok(pathFloorId === floorId ? state.cart : [])
    if (path.endsWith('/room-scheme')) return ok({ activeSlot: 'A', alternativeSaved: false })
    if (path.includes('/room-scheme/')) {
      const action = path.split('/').at(-1)
      const body = request.postDataJSON()
      assert.equal(action, 'clear-selection')
      assert.equal(body.zoneId, zone.id)
      assert.equal(body.revision, state.canvas.revision)
      // 清单变化：清空选品并使方案确认失效
      state.canvas = { ...state.canvas, objects: [zone, furniture], revision: state.canvas.revision + 1, schemeToken: 'TOKEN-' + (state.canvas.revision + 1) }
      state.cart = []
      state.confirmation = null
      state.floors[0].confirmed = false
      state.floors[0].cartQty = 0
      return ok({ canvas: state.canvas, cart: state.cart, scheme: { activeSlot: 'A', alternativeSaved: false } })
    }
    if (path.endsWith('/proposal-confirmation')) {
      if (method === 'POST') {
        assert.deepEqual(request.postDataJSON(), { canvasRevision: state.canvas.revision, schemeToken: state.canvas.schemeToken ?? null })
        state.floors[0].confirmed = true
        state.confirmation = { confirmationId: 'PROP-TEST', canvasRevision: state.canvas.revision, confirmedAt: '2026-11-25T12:00:00', current: true }
      }
      return ok(state.confirmation)
    }
    return ok([])
  })

  const page = await context.newPage()
  const pageErrors = []
  page.on('pageerror', error => pageErrors.push(error.message))
  const nav = page.getByRole('navigation', { name: '主导航' })
  const cards = page.getByRole('region', { name: '项目阶段筛选' })
  const list = page.getByRole('region', { name: '项目列表', exact: true })
  const row = () => list.locator('tbody tr', { hasText: '总部办公' })

  // SPA 返回项目列表的真实路径：项目工作台（总览）→ 返回项目列表，全程不刷新页面
  async function backToProjectList() {
    await nav.getByRole('link', { name: '项目工作台', exact: true }).click()
    await page.getByRole('link', { name: '返回项目列表', exact: true }).click()
  }

  await page.goto(baseUrl + '/workspace/project-bench', { waitUntil: 'networkidle' })
  await row().getByText('设计中', { exact: true }).waitFor()
  assert.equal(await row().getByRole('progressbar').getAttribute('aria-valuenow'), '0', '未确认楼层进度为 0%')
  assert.ok((await row().innerText()).includes('0/1 区域'))
  assert.equal(await cards.getByRole('button', { name: /设计中/ }).innerText(), '设计中\n1')

  // 场景 1：确认方案 → 返回工作台，进度前进 100%；阶段为人工维护，不随确认自动变化
  await nav.getByRole('link', { name: '项目清单', exact: true }).click()
  await page.waitForURL(url => url.pathname === '/proposal')
  await page.getByRole('button', { name: '确认方案', exact: true }).click()
  await page.getByText('方案已确认', { exact: true }).waitFor()
  await backToProjectList()
  await row().getByText('1/1 区域', { exact: true }).waitFor()
  assert.equal(await row().getByRole('progressbar').getAttribute('aria-valuenow'), '100', '全部楼层确认后进度 100%')
  await row().getByText('设计中', { exact: true }).waitFor()
  assert.equal(await cards.getByRole('button', { name: /设计中/ }).innerText(), '设计中\n1')
  assert.equal(await cards.getByRole('button', { name: /交付中/ }).innerText(), '交付中\n0')

  // 场景 2：清单变化导致确认失效 → 返回工作台，进度回退；阶段仍保持人工值
  await nav.getByRole('link', { name: '产品方案', exact: true }).click()
  await page.getByTestId('product-scheme').waitFor()
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '清空选品' }).click()
  await page.getByText('已清空当前房间选品').waitFor()
  await backToProjectList()
  await row().getByText('0/1 区域', { exact: true }).waitFor()
  assert.equal(await row().getByRole('progressbar').getAttribute('aria-valuenow'), '0', '确认失效后进度回退 0%')
  await row().getByText('设计中', { exact: true }).waitFor()
  assert.equal(await cards.getByRole('button', { name: /设计中/ }).innerText(), '设计中\n1')

  // 场景 3：已确认项目新增未确认楼层 → 返回工作台，阶段保持人工值、楼层数更新
  state.floors[0].confirmed = true
  state.floors[0].cartQty = 1
  state.confirmation = { confirmationId: 'PROP-TEST', canvasRevision: state.canvas.revision, confirmedAt: '2026-11-25T12:00:00', current: true }
  await backToProjectList()
  await row().getByText('1/1 区域', { exact: true }).waitFor()
  await page.getByRole('button', { name: '＋ 添加新楼层', exact: true }).click()
  await page.getByRole('textbox', { name: '新楼层名称' }).fill('2F')
  await page.getByRole('button', { name: '添加', exact: true }).click()
  await page.getByRole('heading', { name: '2F · 上传办公图纸', exact: true }).waitFor()
  await page.getByRole('link', { name: '返回项目列表', exact: true }).click()
  await row().getByText('设计中', { exact: true }).waitFor()
  assert.ok((await row().innerText()).includes('2'), '新增楼层后列表显示 2 个楼层')
  assert.equal(await cards.getByRole('button', { name: /设计中/ }).innerText(), '设计中\n1')

  // 场景 4：重取期间保留旧数据，不闪烁加载占位；外部（负责人）修改的阶段在重取后生效
  state.floors[0].confirmed = true
  state.floors[1].confirmed = true
  await nav.getByRole('link', { name: '项目工作台', exact: true }).click()
  state.delayNextProjects = true
  state.status = 'DELIVERING'
  await page.getByRole('link', { name: '返回项目列表', exact: true }).click()
  await row().getByText('设计中', { exact: true }).waitFor()
  assert.equal(await list.getByText('正在加载项目…', { exact: true }).count(), 0, '静默重取不应清空列表')
  await row().getByText('交付中', { exact: true }).waitFor()
  assert.ok((await row().innerText()).includes('1/1 区域'), '无区域的新楼层不影响区域进度')

  assert.deepEqual(pageErrors, [])
  console.log('Project refresh E2E passed: confirm/invalidation/floor-add update progress and externally changed stage on SPA return; refetch keeps stale rows visible. API mocked.')
} finally {
  await browser?.close()
  app.kill('SIGTERM')
}
