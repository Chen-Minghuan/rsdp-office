<script setup lang="ts">
import { money } from '~/utils/helpers'
import { sameOriginAssetUrl } from '~/utils/apiBase'
import { PRODUCTS } from '~/utils/sample'
import type { CartItem, FurnitureObject, Product, ZoneObject } from '~/types/office'
import type { RenderPreview } from '~/types/renderPreview'


type ProposalModule = {
  key: string
  label: string
  product: Product | null
  qty: number
  furniture: FurnitureObject | null
  cartItem: CartItem | null
}

type ProposalSpace = {
  zoneId: string
  name: string
  category: string
  status: '已确认' | '待确认'
  note: string
  zone: ZoneObject | null
  modules: ProposalModule[]
  layoutObjects: FurnitureObject[]
  total: number
  preview: boolean
}

type ProposalGroup = {
  id: string
  floorLabel: string
  name: string
  spaces: ProposalSpace[]
}

type Detail =
  | { kind: 'requirement'; space: ProposalSpace }
  | { kind: 'layout'; space: ProposalSpace }
  | { kind: 'products'; space: ProposalSpace }
  | { kind: 'render'; space: ProposalSpace }
  | { kind: 'module'; space: ProposalSpace; module: ProposalModule }
  | { kind: 'comment'; space: ProposalSpace }

const store = useOfficeStore()
const route = useRoute()
const selectedDetail = ref<Detail | null>(null)
const confirmed = ref(false)
const notice = ref('')
const collapsedGroups = ref<Set<string>>(new Set())
const activeSpaceIds = ref<Record<string, string>>({})
const confirmedSpaceIds = ref<Record<string, boolean>>({})
const renderJobs = ref<Record<string, RenderPreview[]>>({})
const loadedRenderZones = new Set<string>()
const renderApi = useRenderPreviewApi()

const previewMode = computed(() => route.query.preview === '1')
const products = computed(() => previewMode.value ? (store.products.length ? store.products : PRODUCTS) : store.products)
const assetBaseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8082'

function assetUrl(url: string | undefined) {
  if (!url) return ''
  if (/^https?:/i.test(url)) return sameOriginAssetUrl(url)
  if (/^(?:data:|blob:)/i.test(url)) return url
  return url.startsWith('/') ? `${assetBaseUrl}${url}` : url
}

function productImageUrl(product: Product | null) {
  return assetUrl(product?.imageUrl)
}

function findProduct(id: string) {
  return products.value.find(product => product.id === id) || null
}

function moduleTotal(module: ProposalModule) {
  return (module.product?.price || 0) * module.qty
}

function dimensionText(product: Product | null) {
  if (!product) return '待补充'
  const width = product.widthMm ?? (product.widthCm ? product.widthCm * 10 : null)
  const depth = product.depthMm ?? (product.depthCm ? product.depthCm * 10 : null)
  return width && depth ? `${width} × ${depth} mm` : '待补充'
}

function areaText(zone: ZoneObject | null, preview = false) {
  if (preview) return '36 m²'
  if (!zone) return '待补充'
  if (zone.reviewUnit !== 'mm') return '待校准'
  if (zone.polygon && zone.polygon.length >= 3) {
    const area = Math.abs(zone.polygon.reduce((sum, [x, y], index, points) => {
      const [nextX, nextY] = points[(index + 1) % points.length]
      return sum + x * nextY - nextX * y
    }, 0)) / 2 / 1_000_000
    if (Number.isFinite(area) && area > 0) return `${area.toFixed(1)} m²`
  }
  const width = zone.actualWidthMm || zone.w
  const depth = zone.actualDepthMm || zone.h
  return `${((width * depth) / 1_000_000).toFixed(1)} m²`
}

function statusFor(zone: ZoneObject | null, preview = false): ProposalSpace['status'] {
  return preview || zone?.confirmed ? '已确认' : '待确认'
}

function floorLabel() {
  const source = store.drawingName || ''
  const match = source.match(/(?:^|[\s_-])(\d{1,3})\s*(?:F|楼层|楼)(?:$|[\s_-])/i)
  return match ? `${match[1]}楼` : '1楼'
}

function categoryFor(zone: ZoneObject | null, name: string) {
  if (/会议|洽谈/.test(name) || zone?.zoneType === 'MEETING_ROOM') return '会议空间'
  if (/总裁|经理|主管|董事/.test(name) || zone?.zoneType === 'EXECUTIVE_OFFICE') return '管理空间'
  if (/接待|前台|茶水|休闲/.test(name) || zone?.zoneType === 'RECEPTION' || zone?.zoneType === 'COLLABORATION') return '公共空间'
  return '办公空间'
}

function createModule(key: string, label: string, product: Product | null, qty: number, furniture: FurnitureObject | null = null, cartItem: CartItem | null = null): ProposalModule {
  return { key, label, product, qty, furniture, cartItem }
}

function buildPreviewSpaces(): ProposalSpace[] {
  const chair = findProduct('SAMPLE-OF-02')
  return [
    { zoneId: 'preview-president', name: '总裁办公室', category: '办公空间', status: '已确认', note: '靠窗位置，保留访客洽谈区；整体采用浅木色与暖白色。', zone: null, preview: true, modules: [createModule('preview-president-desk', '经理办公桌', findProduct('SAMPLE-OF-06'), 1), createModule('preview-president-chair', '人体工学办公椅', chair, 1), createModule('preview-president-cabinet', '移动文件柜', findProduct('SAMPLE-OF-07'), 1)], layoutObjects: [], total: 5520 },
    { zoneId: 'preview-open', name: '开放办公区', category: '办公空间', status: '已确认', note: '保留连续中央通道，靠窗布置工位，入口侧预留打印和收纳位置。', zone: null, preview: true, modules: [createModule('preview-open-desk', '四人工位组合', findProduct('SAMPLE-OF-01'), 4), createModule('preview-open-chair', '人体工学办公椅', chair, 16), createModule('preview-open-cabinet', '移动文件柜', findProduct('SAMPLE-OF-07'), 4)], layoutObjects: [], total: 28880 },
    { zoneId: 'preview-meeting-1', name: '会议室1', category: '会议空间', status: '已确认', note: '中型会议室，满足日常会议和视频会议，保留墙面显示屏位置。', zone: null, preview: true, modules: [createModule('preview-meeting-1-table', '会议桌', findProduct('SAMPLE-OF-03'), 1), createModule('preview-meeting-1-chair', '会议椅', findProduct('SAMPLE-OF-04'), 8)], layoutObjects: [], total: 14440 },
    { zoneId: 'preview-meeting-2', name: '会议室2', category: '会议空间', status: '待确认', note: '小型讨论空间，建议使用灵活移动桌椅，方便临时拆分。', zone: null, preview: true, modules: [createModule('preview-meeting-2-table', '会议桌', findProduct('SAMPLE-OF-03'), 1), createModule('preview-meeting-2-chair', '会议椅', findProduct('SAMPLE-OF-04'), 6)], layoutObjects: [], total: 12900 },
    { zoneId: 'preview-reception', name: '前台接待区', category: '公共空间', status: '待确认', note: '入口区域需要体现品牌形象，并保留访客等候和导视位置。', zone: null, preview: true, modules: [createModule('preview-reception-sofa', '商务接待沙发', findProduct('SAMPLE-OF-05'), 1), createModule('preview-reception-table', '茶几', findProduct('SAMPLE-OF-08'), 1)], layoutObjects: [], total: 6000 },
  ].map(space => confirmedSpaceIds.value[space.zoneId] ? { ...space, status: '已确认' as const } : space)
}

const spaces = computed<ProposalSpace[]>(() => {
  if (previewMode.value) return buildPreviewSpaces()
  const map = new Map<string, ProposalSpace>()
  for (const zone of store.zones) {
    map.set(zone.id, { zoneId: zone.id, name: zone.name, category: categoryFor(zone, zone.name), status: confirmedSpaceIds.value[zone.id] ? '已确认' : statusFor(zone), note: zone.note || '暂无补充说明，点击查看空间需求。', zone, modules: [], layoutObjects: store.furnitureForZone(zone.id), total: 0, preview: false })
  }
  for (const item of store.cart) {
    const key = item.zoneId || `cart-${item.zoneName || '未分区'}`
    const group = map.get(key) || { zoneId: key, name: item.zoneName || '未分区商品', category: '办公空间', status: '待确认' as const, note: '这些商品还没有绑定到已识别空间。', zone: null, modules: [], layoutObjects: [], total: 0, preview: false }
    const furniture = item.zoneId ? store.furnitureForZone(item.zoneId).find(entry => entry.productId === item.productId) || null : null
    const product = findProduct(item.productId)
    const existing = group.modules.find(module => module.cartItem?.productId === item.productId || module.product?.id === item.productId)
    if (existing) {
      existing.qty += item.qty
      group.total += (product?.price || 0) * item.qty
    } else {
      const module = createModule(item.id, product?.name || '未命名商品', product, item.qty, furniture, item)
      group.modules.push(module)
      group.total += moduleTotal(module)
    }
    map.set(key, group)
  }
  for (const space of map.values()) {
    if (space.modules.length) continue
    const byProduct = new Map<string, ProposalModule>()
    for (const furniture of space.zone ? store.furnitureForZone(space.zone.id) : []) {
      const product = findProduct(furniture.productId)
      const existing = byProduct.get(furniture.productId)
      if (existing) existing.qty += 1
      else byProduct.set(furniture.productId, createModule(furniture.id, product?.name || furniture.label || '未绑定模块', product, 1, furniture))
    }
    space.modules = Array.from(byProduct.values())
    space.total = space.modules.reduce((sum, module) => sum + moduleTotal(module), 0)
    space.layoutObjects = space.zone ? store.furnitureForZone(space.zone.id) : []
  }
  return Array.from(map.values()).filter(space => space.modules.length || space.zone)
})

function latestRender(space: ProposalSpace) {
  const jobs = renderJobs.value[space.zoneId] || []
  return jobs.find(job => job.status === 'SUCCEEDED' && job.imageUrl) || jobs[0] || null
}

function renderStatus(job: RenderPreview | null) {
  if (!job) return '尚未生成'
  if (job.status === 'SUCCEEDED') return '已生成'
  if (job.status === 'FAILED') return '生成失败'
  return '生成中'
}

function renderJobsFor(space: ProposalSpace) {
  return (renderJobs.value[space.zoneId] || []).slice(0, 3)
}

function renderAt(space: ProposalSpace, index: number) {
  return renderJobsFor(space)[index] || null
}

function rotateTransform(item: { rotation: number; x: number; y: number; w: number; h: number }) {
  return `rotate(${item.rotation} ${item.x + item.w / 2} ${item.y + item.h / 2})`
}

async function loadRenderPreviews() {
  if (previewMode.value || !store.initialized) return
  const ids = spaces.value.map(space => space.zoneId).filter(id => !id.startsWith('cart-') && !loadedRenderZones.has(id))
  if (!ids.length) return
  ids.forEach(id => loadedRenderZones.add(id))
  const results = await Promise.allSettled(ids.map(async id => ({ id, jobs: await renderApi.list(id) })))
  const next = { ...renderJobs.value }
  for (const result of results) {
    if (result.status === 'fulfilled') next[result.value.id] = result.value.jobs || []
  }
  renderJobs.value = next
}

watch(() => [store.initialized, previewMode.value, spaces.value.map(space => space.zoneId).join(',')], () => { void loadRenderPreviews() }, { immediate: true })

const groups = computed<ProposalGroup[]>(() => {
  const floor = floorLabel()
  const map = new Map<string, ProposalGroup>()
  for (const space of spaces.value) {
    const id = `${floor}-${space.category}`
    const existing = map.get(id)
    if (existing) existing.spaces.push(space)
    else map.set(id, { id, floorLabel: floor, name: `${floor}${space.category}`, spaces: [space] })
  }
  return Array.from(map.values())
})

const totalModules = computed(() => spaces.value.reduce((sum, space) => sum + space.modules.reduce((subtotal, module) => subtotal + module.qty, 0), 0))
const totalPrice = computed(() => spaces.value.reduce((sum, space) => sum + space.total, 0))
const confirmedSpaces = computed(() => spaces.value.filter(space => space.status === '已确认').length)
const pendingSpaces = computed(() => spaces.value.length - confirmedSpaces.value)
const confirmedGroups = computed(() => groups.value.filter(group => group.spaces.every(space => space.status === '已确认')).length)

function groupTotal(group: ProposalGroup) { return group.spaces.reduce((sum, space) => sum + space.total, 0) }
function groupArea(group: ProposalGroup) {
  const areas = group.spaces
    .map(space => Number.parseFloat(areaText(space.zone, space.preview)))
    .filter(Number.isFinite)
  return areas.length ? `${areas.reduce((sum, area) => sum + area, 0).toFixed(0)} m²` : '待校准'
}
function groupModules(group: ProposalGroup) { return group.spaces.reduce((sum, space) => sum + space.modules.reduce((subtotal, module) => subtotal + module.qty, 0), 0) }
function isGroupOpen(group: ProposalGroup) { return !collapsedGroups.value.has(group.id) }
function toggleGroup(group: ProposalGroup) { const next = new Set(collapsedGroups.value); if (next.has(group.id)) next.delete(group.id); else next.add(group.id); collapsedGroups.value = next }
function activeSpace(group: ProposalGroup) { const id = activeSpaceIds.value[group.id] || group.spaces[0]?.zoneId; return group.spaces.find(space => space.zoneId === id) || group.spaces[0] }
function chooseSpace(group: ProposalGroup, space: ProposalSpace) { activeSpaceIds.value = { ...activeSpaceIds.value, [group.id]: space.zoneId } }
function openDetail(detail: Detail) { selectedDetail.value = detail }
function closeDetail() { selectedDetail.value = null }

function changeQty(module: ProposalModule, delta: number) {
  if (previewMode.value || !module.cartItem) { notice.value = '预览数据不写入项目草稿'; window.setTimeout(() => { notice.value = '' }, 2200); return }
  void store.changeQty(module.cartItem.id, delta)
}

function setNotice(message: string) {
  notice.value = message
  window.setTimeout(() => { notice.value = '' }, 2600)
}

function confirmSpace(space: ProposalSpace) {
  if (space.status === '已确认') return
  if (space.preview || !space.zone) {
    confirmedSpaceIds.value = { ...confirmedSpaceIds.value, [space.zoneId]: true }
    setNotice(`${space.name} 已确认`)
    return
  }
  if (!store.confirmZone(space.zoneId)) {
    setNotice(`${space.name} 暂不能确认，请先处理空间校验问题`)
    return
  }
  confirmedSpaceIds.value = { ...confirmedSpaceIds.value, [space.zoneId]: true }
  setNotice(`${space.name} 已确认`)
}

function confirmGroup(group: ProposalGroup) {
  const pending = group.spaces.filter(space => space.status !== '已确认')
  if (!pending.length) return
  const failed: ProposalSpace[] = []
  for (const space of pending) {
    if (space.preview || !space.zone) {
      confirmedSpaceIds.value = { ...confirmedSpaceIds.value, [space.zoneId]: true }
      continue
    }
    if (store.confirmZone(space.zoneId)) confirmedSpaceIds.value = { ...confirmedSpaceIds.value, [space.zoneId]: true }
    else failed.push(space)
  }
  setNotice(failed.length ? `${failed.length} 个空间暂不能确认，请先处理校验问题` : `${group.name} 已全部确认`)
}

function confirmProposal() { confirmed.value = true; setNotice('方案确认状态已更新（当前为界面预览）') }

function detailTitle(detail: Detail | null) {
  if (!detail) return ''
  if (detail.kind === 'requirement') return '需求描述'
  if (detail.kind === 'layout') return '排版平面效果'
  if (detail.kind === 'products') return '商品清单'
  if (detail.kind === 'render') return '效果图预览'
  if (detail.kind === 'comment') return '客户批注'
  return detail.module.label
}
</script>

<template>
  <div class="proposal-page">
    <header class="proposal-heading"><div><div class="proposal-eyebrow">项目工作台 / 方案确认 · 深圳 XX 项目清单</div><div class="proposal-title-line"><h1>整体方案与清单</h1><span class="proposal-file">{{ store.drawingName }}</span></div><p>按楼层、空间类别和具体空间逐级核对方案，确认后进入正式交付。</p></div><span class="heading-status"><span></span>待最终确认</span></header>
    <div v-if="previewMode" class="preview-banner"><AppIcon name="info" size="3.5" /><span>界面预览数据：同一张户型图自动归入 {{ floorLabel() }}，下面按空间类别分组，不会写入项目草稿。</span></div>

    <div v-if="!spaces.length" class="proposal-empty"><div class="proposal-empty-icon"><AppIcon name="table" size="7" /></div><h2>还没有可确认的空间方案</h2><p>请先完成空间确认、家具排版和商品选择，再进入方案确认页。</p><NuxtLink to="/canvas" class="proposal-primary">返回空间总览 <AppIcon name="arrowRight" size="3.5" /></NuxtLink></div>

    <div v-else class="proposal-layout">
      <main class="proposal-main">
        <section class="group-list"><div class="group-list-heading"><div><span class="section-kicker">FLOOR &amp; SPACE GROUPS</span><h2>空间分组</h2></div><span>{{ floorLabel() }} · {{ groups.length }} 个类别</span></div>
          <article v-for="group in groups" :key="group.id" class="group-card" :class="{ collapsed: !isGroupOpen(group) }">
            <div class="group-header-row">
              <button type="button" class="group-header" @click="toggleGroup(group)"><span class="floor-badge">{{ group.floorLabel.replace('楼', 'F') }}</span><span class="group-name">{{ group.name }}</span><span class="group-chevron">{{ isGroupOpen(group) ? '▼' : '▶' }}</span><span class="group-meta">面积：{{ groupArea(group) }} <i>|</i> 金额：{{ money(groupTotal(group)) }} <i>|</i> {{ groupModules(group) }} 件商品</span><span class="group-status" :class="group.spaces.every(space => space.status === '已确认') ? 'is-confirmed' : 'is-pending'"><span></span>{{ group.spaces.every(space => space.status === '已确认') ? '已确认' : '待确认' }}</span></button>
              <button type="button" class="group-confirm-button" :class="group.spaces.every(space => space.status === '已确认') ? 'is-confirmed' : 'is-pending'" :disabled="group.spaces.every(space => space.status === '已确认')" @click="confirmGroup(group)"><AppIcon name="check" size="3" />{{ group.spaces.every(space => space.status === '已确认') ? '区域已确认' : '确认区域' }}</button>
            </div>
            <div v-if="isGroupOpen(group)" class="group-body">
              <div class="space-rows"><button v-for="space in group.spaces" :key="space.zoneId" type="button" class="space-row" :class="{ active: activeSpace(group)?.zoneId === space.zoneId }" @click="chooseSpace(group, space)"><span class="space-row-chevron">{{ activeSpace(group)?.zoneId === space.zoneId ? '▼' : '▶' }}</span><strong>{{ space.name }}</strong><span>{{ areaText(space.zone, space.preview) }}</span><span>{{ money(space.total) }}</span><span>{{ space.modules.reduce((sum, module) => sum + module.qty, 0) }} 件商品</span><span class="space-row-status" :class="space.status === '已确认' ? 'is-confirmed' : 'is-pending'"><span></span>{{ space.status }}</span></button></div>
              <div v-if="activeSpace(group)" class="space-detail-board">
                <div class="space-detail-heading"><div><span class="section-kicker">CURRENT SPACE</span><h3>{{ activeSpace(group)?.name }}</h3><p>{{ areaText(activeSpace(group)?.zone || null, activeSpace(group)?.preview) }} · {{ activeSpace(group)?.modules.length }} 类模块</p></div><div class="space-detail-actions"><button type="button" class="space-detail-status" @click="openDetail({ kind: 'requirement', space: activeSpace(group)! })"><span></span>{{ activeSpace(group)?.status }} · 查看需求</button><button type="button" class="space-confirm-button" :disabled="activeSpace(group)?.status === '已确认'" @click="confirmSpace(activeSpace(group)!)"><AppIcon name="check" size="3" />{{ activeSpace(group)?.status === '已确认' ? '空间已确认' : '确认空间' }}</button></div></div>
                <div class="space-modules preset-space-board">
                  <button type="button" class="preset-panel requirement-panel" @click="openDetail({ kind: 'requirement', space: activeSpace(group)! })">
                    <div class="preset-panel-title"><span>需求描述</span><AppIcon name="arrowRight" size="3" /></div>
                    <div class="requirement-copy"><strong>{{ activeSpace(group)?.name }}</strong><p>{{ activeSpace(group)?.note }}</p></div>
                    <div class="requirement-scroll"><span>需求描述框跟随滚动</span><i></i></div>
                    <div class="preset-panel-foot"><span>{{ activeSpace(group)?.zone?.seats || '—' }} 个座位</span><small>查看空间需求</small></div>
                  </button>
                  <button type="button" class="preset-panel layout-panel" @click="openDetail({ kind: 'layout', space: activeSpace(group)! })">
                    <div class="preset-panel-title"><span>排版平面效果</span><AppIcon name="arrowRight" size="3" /></div>
                    <div class="layout-thumb preset-layout-thumb"><OfficeCanvasLayoutPlanPreview :zone="activeSpace(group)!.zone" :furniture="activeSpace(group)!.layoutObjects" :products="products" /></div>
                    <div class="layout-room-label"><strong>{{ activeSpace(group)?.name }}</strong><span>× {{ activeSpace(group)?.layoutObjects.length || 0 }} 件家具</span></div>
                    <div class="layout-note-box"><span>备注信息</span><p>{{ activeSpace(group)?.note || '暂无布局备注' }}</p></div>
                  </button>
                  <div class="preset-panel products-panel">
                    <div class="preset-panel-title"><span>商品清单</span><AppIcon name="arrowRight" size="3" /></div>
                    <div v-if="activeSpace(group)?.modules.length" class="product-grid">
                      <button v-for="module in activeSpace(group)?.modules" :key="module.key" type="button" class="product-tile" @click="openDetail({ kind: 'module', space: activeSpace(group)!, module })">
                        <span class="product-image-tile"><ProductImagePreview v-if="module.product?.imageUrl" :src="productImageUrl(module.product)" :alt="module.label" size="small" /><span v-else class="product-no-image">暂无图片</span></span>
                        <span class="product-tile-caption"><strong>{{ module.label }}</strong><span>× {{ module.qty }}</span></span>
                      </button>
                    </div>
                    <div v-else class="product-grid-empty">当前空间还没有绑定商品</div>
                    <div class="preset-panel-foot"><span>{{ activeSpace(group)?.modules.reduce((sum, item) => sum + item.qty, 0) }} 件 · {{ money(activeSpace(group)?.total || 0) }}</span><small>点击商品查看详情</small></div>
                  </div>
                  <div class="preset-panel render-panel">
                    <div class="preset-panel-title"><span>效果图</span><AppIcon name="arrowRight" size="3" /></div>
                    <div class="render-stack">
                      <button v-for="index in 3" :key="index" type="button" class="render-slot" @click="openDetail({ kind: 'render', space: activeSpace(group)! })">
                        <img v-if="renderAt(activeSpace(group)!, index - 1)?.status === 'SUCCEEDED' && renderAt(activeSpace(group)!, index - 1)?.imageUrl" :src="assetUrl(renderAt(activeSpace(group)!, index - 1)?.imageUrl)" :alt="activeSpace(group)?.name + '效果图' + index">
                        <span v-else>效果图{{ index }}<small>{{ renderStatus(renderAt(activeSpace(group)!, index - 1)) }}</small></span>
                      </button>
                    </div>
                    <div class="preset-panel-foot"><span>{{ renderJobsFor(activeSpace(group)!).length }} 张真实效果图</span><small>查看生成状态</small></div>
                  </div>
                  <button type="button" class="preset-panel comment-module comment-panel" @click="openDetail({ kind: 'comment', space: activeSpace(group)! })">
                    <div class="preset-panel-title"><span>客户批注</span><AppIcon name="arrowRight" size="3" /></div>
                    <div class="comment-copy"><p>{{ activeSpace(group)?.note || '暂无客户批注' }}</p></div>
                    <div class="preset-panel-foot"><span>批注与空间一起保存</span><small>点击编辑批注</small></div>
                  </button>
                </div>
              </div>
            </div>
          </article>
        </section>
      </main>

      <aside class="proposal-aside"><div class="aside-card sticky-card"><div class="aside-title"><AppIcon name="documentText" size="4" /><span>方案摘要</span></div><div class="aside-summary"><span>楼层 / 类别</span><strong>{{ groups.length }}</strong></div><div class="aside-summary"><span>空间确认</span><strong>{{ confirmedSpaces }} / {{ spaces.length }}</strong></div><div class="aside-summary"><span>商品总数</span><strong>{{ totalModules }} 件</strong></div><div class="aside-summary"><span>参考金额</span><strong class="price">{{ money(totalPrice) }}</strong></div><div class="aside-divider"></div><div class="aside-title small"><AppIcon name="check" size="4" /><span>确认状态</span></div><div class="confirm-state" :class="confirmed ? 'confirmed' : ''"><span class="state-dot"></span><div><strong>{{ confirmed ? '方案已确认' : '待最终确认' }}</strong><p>{{ confirmed ? '可以进入效果图与正式报价阶段。' : '确认前请逐项查看空间分组和具体空间。' }}</p></div></div><button type="button" class="proposal-primary full" @click="confirmProposal"><AppIcon name="check" size="3.5" />{{ confirmed ? '已确认方案' : '确认方案' }}</button><button type="button" class="proposal-secondary full" @click="store.exportCsv"><AppIcon name="documentText" size="3.5" />导出方案清单 CSV</button><NuxtLink to="/canvas?mode=design" class="proposal-secondary full"><AppIcon name="arrowLeft" size="3.5" />返回继续设计</NuxtLink><NuxtLink to="/canvas" class="proposal-secondary full"><AppIcon name="map" size="3.5" />查看空间总览</NuxtLink><p class="aside-note"><AppIcon name="info" size="3.5" />同一张户型图的空间会自动归入同一楼层，正式报价仍需锁定真实 Variant、供应商和服务费用。</p></div></aside>
    </div>

    <div v-if="notice" class="proposal-toast" role="status">{{ notice }}</div>
    <div v-if="selectedDetail" class="detail-backdrop" @click.self="closeDetail"><aside class="detail-drawer" :class="{ 'layout-detail-dialog': selectedDetail.kind === 'layout' }" role="dialog" aria-modal="true" :aria-label="detailTitle(selectedDetail)"><div class="detail-header"><div><span class="section-kicker">{{ selectedDetail.space.category }} · {{ selectedDetail.space.name }}</span><h2>{{ detailTitle(selectedDetail) }}</h2></div><button type="button" class="detail-close" aria-label="关闭详情" @click="closeDetail"><AppIcon name="close" size="5" /></button></div>
      <template v-if="selectedDetail.kind === 'requirement'"><div class="detail-hero blue"><AppIcon name="documentText" size="8" /><div><strong>空间需求说明</strong><span>{{ areaText(selectedDetail.space.zone, selectedDetail.space.preview) }} · {{ selectedDetail.space.status }}</span></div></div><dl class="detail-list"><div><dt>空间类别</dt><dd>{{ selectedDetail.space.category }}</dd></div><div><dt>空间用途</dt><dd>{{ selectedDetail.space.name }}</dd></div><div><dt>备注</dt><dd>{{ selectedDetail.space.note }}</dd></div></dl></template>
      <template v-else-if="selectedDetail.kind === 'layout'"><p class="layout-detail-hint">前一步的实际家具排版 · 按空间真实比例展示</p><div class="layout-plan-detail"><OfficeCanvasLayoutPlanPreview :zone="selectedDetail.space.zone" :furniture="selectedDetail.space.layoutObjects" :products="products" /></div><div class="detail-stat-grid"><div><span>家具件数</span><strong>{{ selectedDetail.space.layoutObjects.length }}</strong></div><div><span>空间面积</span><strong>{{ areaText(selectedDetail.space.zone, selectedDetail.space.preview) }}</strong></div></div></template>
      <template v-else-if="selectedDetail.kind === 'products'"><div class="drawer-product-list"><button v-for="module in selectedDetail.space.modules" :key="module.key" type="button" class="drawer-product" @click="openDetail({ kind: 'module', space: selectedDetail.space, module })"><span class="drawer-product-art"><ProductImagePreview v-if="module.product?.imageUrl" :src="productImageUrl(module.product)" :alt="module.label" size="small" /><span v-else class="product-no-image">无图</span></span><span class="drawer-product-copy"><strong>{{ module.label }}</strong><small>{{ module.product?.variant || '未绑定商品规格' }}</small></span><span class="drawer-product-qty">× {{ module.qty }}</span><AppIcon name="arrowRight" size="3" /></button></div><div class="drawer-total"><span>空间参考金额</span><strong>{{ money(selectedDetail.space.total) }}</strong></div></template>
      <template v-else-if="selectedDetail.kind === 'module'"><div class="detail-product-hero"><span class="large-product-art"><ProductImagePreview v-if="selectedDetail.module.product?.imageUrl" :src="productImageUrl(selectedDetail.module.product)" :alt="selectedDetail.module.label" size="large" /><span v-else class="product-no-image">暂无图片</span></span><div><h3>{{ selectedDetail.module.label }}</h3><p>{{ selectedDetail.module.product?.desc || '当前模块尚未绑定真实商品。' }}</p></div></div><dl class="detail-list"><div><dt>Variant</dt><dd>{{ selectedDetail.module.product?.variant || '待补充' }}</dd></div><div><dt>尺寸</dt><dd>{{ dimensionText(selectedDetail.module.product) }}</dd></div><div><dt>数量</dt><dd class="qty-control"><button type="button" @click="changeQty(selectedDetail.module, -1)">−</button><b>{{ selectedDetail.module.qty }}</b><button type="button" @click="changeQty(selectedDetail.module, 1)">＋</button></dd></div><div><dt>参考小计</dt><dd class="detail-price">{{ money(moduleTotal(selectedDetail.module)) }}</dd></div></dl></template>
      <template v-else-if="selectedDetail.kind === 'render'"><div v-if="latestRender(selectedDetail.space)?.status === 'SUCCEEDED' && latestRender(selectedDetail.space)?.imageUrl" class="drawer-render drawer-render-image"><img :src="assetUrl(latestRender(selectedDetail.space)?.imageUrl)" :alt="`${selectedDetail.space.name}效果图`"><span>真实生成结果 · {{ latestRender(selectedDetail.space)?.view === 'INTERIOR' ? '室内透视' : '鸟瞰' }}</span></div><div v-else class="drawer-render"><span>CONCEPT PREVIEW</span><strong>{{ renderStatus(latestRender(selectedDetail.space)) }}</strong><p>{{ latestRender(selectedDetail.space)?.errorMessage || '该空间尚未生成效果图；生成后会在这里显示真实结果，并绑定当前家具快照。' }}</p></div><button type="button" class="proposal-primary full" @click="closeDetail">返回方案确认</button></template>
      <template v-else><label class="comment-editor">客户批注<textarea :value="selectedDetail.space.note" rows="6" placeholder="记录客户对空间、材质或预算的补充要求"></textarea></label><p class="drawer-hint">批注会和空间一起进入方案快照，便于后续复核。</p><button type="button" class="proposal-primary full" @click="closeDetail">保存批注</button></template>
    </aside></div>
  </div>
</template>

<style scoped>
.proposal-page{display:flex;flex-direction:column;gap:0;min-height:100%;color:#332f2a}.proposal-heading{display:flex;justify-content:space-between;gap:24px;align-items:flex-end}.proposal-eyebrow{font-size:11px;color:#9b8d7d;margin-bottom:7px}.proposal-title-line{display:flex;align-items:center;gap:9px}.proposal-heading h1{font-size:24px;font-weight:800;letter-spacing:-.04em}.proposal-heading p{font-size:12px;color:#958d84;margin-top:6px}.proposal-file{font-size:11px;color:#8d7c68;background:#f2ece4;border:1px solid #eadfd2;border-radius:6px;padding:5px 9px}.heading-status{display:flex;align-items:center;gap:7px;color:#a47745;font-size:11px;font-weight:700}.heading-status>span{width:8px;height:8px;border-radius:50%;background:#dda94a}.preview-banner{display:flex;gap:8px;align-items:center;padding:10px 13px;border:1px solid #e4d4bc;background:#fbf5ec;color:#8e704f;border-radius:10px;font-size:11px}.proposal-layout{display:grid;grid-template-columns:minmax(0,1fr) 290px;gap:0;align-items:start}.proposal-main{min-width:0;display:flex;flex-direction:column;gap:0}.proposal-overview-card,.group-list,.aside-card{background:#fff;border:1px solid #e7e0d7;border-radius:16px;box-shadow:0 12px 30px rgba(82,58,35,.045)}.proposal-overview-card{padding:20px}.section-title-row,.group-list-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.section-kicker{display:block;color:#aa7d50;letter-spacing:.16em;font-size:9px;font-weight:800;margin-bottom:4px}.section-title-row h2,.group-list-heading h2{font-size:18px;font-weight:800;letter-spacing:-.02em}.section-help{font-size:11px;color:#958d84;line-height:1.7;margin-top:7px}.prototype-tag{display:flex;gap:5px;align-items:center;color:#5a8eb8;background:#edf6ff;border-radius:5px;padding:5px 8px;font-size:10px;white-space:nowrap}.overview-metrics{display:grid;grid-template-columns:1fr 1fr 1.35fr;gap:10px;margin-top:18px}.overview-metrics>div{background:#faf8f5;border-radius:11px;padding:13px 15px}.overview-metrics span,.overview-metrics small{display:block;color:#948b82;font-size:10px}.overview-metrics strong{display:block;font-size:23px;line-height:1.15;margin:8px 0 5px;letter-spacing:-.04em}.overview-metrics .accent strong{color:#a07146}.group-list{padding:20px}.group-list-heading{align-items:end;margin-bottom:12px}.group-list-heading>span{font-size:11px;color:#9b9289}.group-card{border:1px solid #e2d9cf;border-radius:12px;overflow:hidden;margin-top:12px;background:#fff}.group-card.collapsed{background:#fcfaf7}.group-header{display:grid;grid-template-columns:38px auto 20px minmax(0,1fr) auto;align-items:center;gap:9px;width:100%;padding:13px 15px;text-align:left;background:#f8f5f1}.group-header:hover{background:#f5eee6}.floor-badge{display:grid;place-items:center;width:30px;height:30px;border-radius:6px;background:#f04428;color:#fff;font-size:11px;font-weight:800}.group-name{display:inline-flex;align-items:center;min-width:100px;padding:6px 14px;border-radius:8px;background:#d7d7d7;color:#3f3b36;font-size:13px;font-weight:800}.group-chevron{color:#8f877e;font-size:11px}.group-meta{font-size:11px;color:#6d6258}.group-meta i{font-style:normal;color:#b2a79c;margin:0 5px}.group-status,.space-row-status,.space-detail-status{display:flex;align-items:center;gap:5px;font-size:10px;font-weight:800;white-space:nowrap}.group-status>span,.space-row-status>span,.space-detail-status>span{width:8px;height:8px;border-radius:50%;background:#dda94a}.is-confirmed{color:#438064}.is-confirmed>span{background:#65b38b}.is-pending{color:#a3783d}.group-body{padding:0 12px 12px}.space-rows{display:flex;flex-direction:column;gap:5px;padding:10px 0 8px;border-bottom:1px solid #eee7df}.space-row{display:grid;grid-template-columns:20px minmax(130px,1.4fr) .8fr .9fr .8fr auto;align-items:center;gap:10px;width:100%;padding:9px 10px;border:1px solid transparent;border-radius:8px;text-align:left;font-size:11px;color:#6e655d}.space-row:hover,.space-row.active{background:#fbf5ed;border-color:#ead8c4}.space-row strong{font-size:12px;color:#403932}.space-row-chevron{color:#aa7b4a}.space-row-status{justify-self:end}.space-detail-board{padding:15px 4px 2px}.space-detail-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:15px;padding:0 5px 12px}.space-detail-heading h3{font-size:16px;font-weight:800}.space-detail-heading p{font-size:10px;color:#968c82;margin-top:4px}.space-detail-status{padding:7px 10px;border-radius:8px;border:1px solid #e5dbcf;background:#fff}.space-modules{display:grid;grid-template-columns:1.05fr 1.15fr 1.15fr 1fr .9fr;gap:0;border-top:1px solid #eee6dc;border-bottom:1px solid #eee6dc}.space-module{min-width:0;text-align:left;padding:12px 10px;border-right:1px solid #eee6dc;transition:background .16s,transform .16s}.space-module:last-child{border-right:0}.space-module:hover{background:#fcf8f3;transform:translateY(-1px)}.module-label{display:flex;align-items:center;gap:6px;font-size:11px;font-weight:800;color:#5b5148}.module-label>span:nth-child(2){flex:1}.module-label>svg{color:#ad9a89}.module-icon{width:24px;height:24px;border-radius:7px;display:grid;place-items:center}.module-icon.blue{background:#e4f0fb;color:#4d83b4}.module-icon.sand{background:#f4eadc;color:#a87542}.module-icon.green{background:#e4f1e9;color:#4e8a6c}.module-icon.purple{background:#eee7f7;color:#8167ad}.module-icon.yellow{background:#fbf0ce;color:#ad8330}.space-module p{display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden;font-size:10px;line-height:1.65;color:#877d73;margin-top:10px;min-height:48px}.space-module small,.module-foot small{display:block;color:#ad9f93;font-size:9px;margin-top:8px}.layout-thumb{height:76px;margin-top:10px;border-radius:8px;overflow:hidden;background:#f7f2ea}.layout-thumb img{display:block;width:100%;height:100%;object-fit:contain}.layout-thumb svg{display:block;width:100%;height:100%}.layout-empty{height:100%;display:grid;place-items:center;color:#a3988d;font-size:10px}.module-foot{display:flex;justify-content:space-between;gap:5px;align-items:end;margin-top:8px;font-size:10px;color:#6d6258}.module-foot small{margin:0;white-space:nowrap}.product-strip{display:flex;gap:5px;margin-top:16px;min-height:34px;align-items:center}.product-thumb{display:grid;place-items:center;width:31px;height:31px;border-radius:7px;background:#f1e9df;color:#ae9172;overflow:hidden}.product-thumb :deep(svg){width:23px;height:23px}.product-thumb :deep(.relative){width:100%;height:100%}.product-no-image{font-size:9px;color:#a89b8e;white-space:nowrap}.product-more{font-size:10px;color:#9b8d80}.render-thumb{height:76px;margin-top:10px;border-radius:8px;background:linear-gradient(135deg,#e4d9cc,#a8866b 52%,#413d39);display:flex;flex-direction:column;align-items:center;justify-content:center;color:#fff;letter-spacing:.12em;text-shadow:0 1px 2px #0008;overflow:hidden}.render-thumb img{display:block;width:100%;height:100%;object-fit:cover}.render-thumb span{font-size:10px}.render-thumb b{font-size:18px;margin-top:4px}.comment-module{background:#fffbeb}.proposal-aside{min-width:0}.sticky-card{position:sticky;top:0;padding:18px;display:flex;flex-direction:column;gap:11px}.aside-title{display:flex;align-items:center;gap:7px;font-size:14px;font-weight:800}.aside-title.small{font-size:12px}.aside-summary{display:flex;justify-content:space-between;align-items:center;font-size:11px;color:#8d8379;padding:4px 0}.aside-summary strong{font-size:12px;color:#4e4741}.aside-summary .price{font-size:18px;color:#a07146}.aside-divider{height:1px;background:#eee7df;margin:3px 0}.confirm-state{display:flex;gap:9px;padding:11px;border-radius:10px;background:#fbf5ec}.confirm-state.confirmed{background:#eef8f1}.state-dot{width:9px;height:9px;border-radius:50%;background:#dda94a;margin-top:4px;flex:none}.confirm-state.confirmed .state-dot{background:#58a87d}.confirm-state strong{font-size:11px}.confirm-state p{font-size:10px;color:#91867c;margin-top:4px;line-height:1.5}.proposal-primary,.proposal-secondary{display:flex;justify-content:center;align-items:center;gap:6px;border-radius:8px;padding:10px 12px;font-size:11px;font-weight:700;transition:background .16s,transform .16s}.proposal-primary{background:#a77b51;color:#fff}.proposal-primary:hover{background:#8f633e;transform:translateY(-1px)}.proposal-secondary{border:1px solid #e4ddd4;color:#72675c;background:#fff}.proposal-secondary:hover{background:#faf7f3}.proposal-primary.full,.proposal-secondary.full{width:100%}.aside-note{display:flex;gap:6px;color:#a39a91;font-size:10px;line-height:1.6;margin-top:7px}.proposal-empty{flex:1;min-height:430px;display:grid;place-items:center;text-align:center;background:#fff;border:1px solid #e7e0d7;border-radius:16px;padding:40px}.proposal-empty-icon{width:60px;height:60px;display:grid;place-items:center;background:#f3ece3;color:#a77b51;border-radius:16px;margin:0 auto 14px}.proposal-empty h2{font-size:18px;font-weight:800}.proposal-empty p{font-size:12px;color:#958d84;margin:8px 0 18px}.proposal-toast{position:fixed;right:26px;bottom:26px;z-index:100;background:#382f28;color:#fff;border-radius:9px;padding:11px 15px;font-size:11px;box-shadow:0 12px 28px #0002}.detail-backdrop{position:fixed;inset:0;z-index:90;background:#2c241c55;display:flex;justify-content:flex-end}.detail-drawer{width:min(520px,94vw);height:100%;overflow:auto;background:#fff;padding:24px;box-shadow:-18px 0 45px #0002}.detail-header{display:flex;justify-content:space-between;gap:15px;margin-bottom:24px}.detail-header h2{font-size:21px;font-weight:800}.detail-close{width:32px;height:32px;display:grid;place-items:center;border-radius:8px;color:#81776c}.detail-close:hover{background:#f6f1eb}.detail-hero{display:flex;gap:12px;align-items:center;padding:16px;border-radius:12px}.detail-hero.blue{background:#edf5fc;color:#467eae}.detail-hero strong,.detail-hero span{display:block}.detail-hero strong{font-size:14px;color:#4d433b}.detail-hero span{font-size:11px;color:#8c8177;margin-top:5px}.detail-list{display:flex;flex-direction:column;margin-top:18px}.detail-list>div{display:grid;grid-template-columns:90px minmax(0,1fr);gap:10px;border-bottom:1px solid #eee7df;padding:13px 0;font-size:12px}.detail-list dt{color:#968b81}.detail-list dd{color:#514943;line-height:1.7}.drawer-layout{background:#faf7f2;border-radius:12px;padding:10px}.drawer-layout img,.drawer-layout svg{display:block;width:100%;max-height:520px;height:auto;object-fit:contain}.detail-stat-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:14px}.detail-stat-grid>div{background:#faf8f5;border-radius:10px;padding:13px}.detail-stat-grid span{display:block;color:#948b82;font-size:10px}.detail-stat-grid strong{display:block;font-size:18px;margin-top:7px}.drawer-product-list{display:flex;flex-direction:column;gap:8px}.drawer-product{width:100%;display:flex;align-items:center;gap:9px;padding:10px;border:1px solid #eee6dc;border-radius:10px;text-align:left}.drawer-product:hover{background:#faf7f2;border-color:#d8c4ad}.drawer-product-art{width:38px;height:38px;border-radius:8px;background:#f1e9df;display:grid;place-items:center;overflow:hidden}.drawer-product-art :deep(svg){width:27px;height:27px}.drawer-product-art :deep(.relative){width:100%;height:100%}.drawer-product-copy{flex:1;min-width:0}.drawer-product-copy strong,.drawer-product-copy small{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.drawer-product-copy strong{font-size:11px}.drawer-product-copy small{font-size:10px;color:#958b82;margin-top:4px}.drawer-product-qty{font-size:11px;color:#887c70}.drawer-total{display:flex;justify-content:space-between;border-top:1px solid #ece5dc;margin-top:16px;padding-top:14px;font-size:12px}.drawer-total strong,.detail-price{color:#a07146;font-size:17px;font-weight:800}.detail-product-hero{display:flex;gap:13px;padding:14px;background:#faf7f2;border-radius:12px}.large-product-art{width:58px;height:58px;display:grid;place-items:center;background:#f0e5d7;border-radius:10px;overflow:hidden}.large-product-art :deep(svg){width:42px;height:42px}.large-product-art :deep(.relative){width:100%;height:100%}.detail-product-hero h3{font-size:16px;font-weight:800}.detail-product-hero p{font-size:11px;color:#8f857b;line-height:1.6;margin-top:5px}.qty-control{display:flex;align-items:center;gap:12px}.qty-control button{width:26px;height:26px;border:1px solid #e3dbd1;border-radius:6px;color:#9a704c}.drawer-render{min-height:280px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;border-radius:14px;background:linear-gradient(135deg,#ece0d2,#a48268 55%,#363331);color:#fff;padding:30px}.drawer-render img{display:block;width:100%;max-height:430px;object-fit:contain;border-radius:10px}.drawer-render-image{gap:12px}.drawer-render span{font-size:10px;letter-spacing:.2em}.drawer-render strong{font-size:21px;margin-top:13px}.drawer-render p{max-width:280px;font-size:11px;line-height:1.7;color:#f0e6db;margin-top:10px}.comment-editor{display:flex;flex-direction:column;gap:8px;font-size:12px;font-weight:700}.comment-editor textarea{width:100%;resize:vertical;border:1px solid #e1d9cf;border-radius:9px;padding:11px;font:inherit;font-size:12px;font-weight:400;line-height:1.7}.drawer-hint{font-size:11px;color:#968c82;line-height:1.7;margin:12px 0 18px}@media(max-width:1100px){.proposal-heading{align-items:flex-start;flex-direction:column}.proposal-layout{grid-template-columns:minmax(0,1fr)}.proposal-aside{order:-1}.sticky-card{position:static}.group-header{grid-template-columns:34px auto 18px 1fr auto}.group-meta{font-size:10px}.space-modules{grid-template-columns:repeat(2,minmax(0,1fr))}.space-module:nth-child(2n){border-right:0}.space-module:nth-child(n+3){border-top:1px solid #eee6dc}}@media(max-width:700px){.proposal-page{gap:0}.proposal-heading h1{font-size:21px}.group-header{grid-template-columns:30px 1fr 16px auto}.group-meta{grid-column:2 / -1}.space-row{grid-template-columns:18px 1fr auto}.space-row>span:nth-child(3),.space-row>span:nth-child(4),.space-row>span:nth-child(5){display:none}.space-modules{grid-template-columns:1fr}.space-module{border-right:0;border-bottom:1px solid #eee6dc}.space-module:last-child{border-bottom:0}.space-module:nth-child(n+3){border-top:0}.detail-drawer{padding:18px}}
/* Preset confirmation board: five columns matching the reference layout. */
.preset-space-board{display:grid;grid-template-columns:minmax(135px,1.05fr) minmax(150px,1.1fr) minmax(245px,1.9fr) minmax(130px,1fr) minmax(110px,.9fr);min-height:430px;border-top:1px solid #eee6dc;border-bottom:1px solid #eee6dc;overflow:hidden}
.preset-panel{position:relative;display:flex;flex-direction:column;min-width:0;min-height:430px;padding:14px 12px;border:0;border-right:1px solid #eee6dc;text-align:left;font:inherit;color:inherit;overflow:hidden}
.preset-panel:last-child{border-right:0}
.preset-panel-title{display:flex;justify-content:space-between;align-items:center;gap:8px;font-size:11px;font-weight:800;line-height:1.3}
.preset-panel-title svg{flex:none;color:currentColor;opacity:.7}
.preset-panel-foot{display:flex;justify-content:space-between;align-items:flex-end;gap:8px;margin-top:auto;padding-top:12px;font-size:10px;color:#6d6258}
.preset-panel-foot small{font-size:9px;color:#ad9f93;white-space:nowrap}
.requirement-panel{background:#087dbb;color:#fff;border-radius:16px;cursor:pointer}
.requirement-panel:hover{background:#0874ae}
.requirement-copy{margin-top:18px}
.requirement-copy strong{display:block;font-size:14px;line-height:1.35}
.requirement-copy p{margin-top:12px;font-size:11px;line-height:1.8;color:#e9f7ff;display:-webkit-box;-webkit-line-clamp:4;-webkit-box-orient:vertical;overflow:hidden}
.requirement-scroll{position:relative;flex:1;min-height:190px;margin-top:18px;padding-top:90px;color:#e9f7ff;font-size:10px}
.requirement-scroll i{position:absolute;right:6px;top:30px;width:4px;height:56px;border-radius:999px;background:#5ec7f5}
.layout-panel,.products-panel,.render-panel{background:#fff}
.layout-panel,.comment-panel{cursor:pointer}
.preset-layout-thumb{height:150px;margin-top:12px;border-radius:8px}
.layout-room-label{display:flex;justify-content:space-between;gap:8px;margin-top:10px;font-size:10px;line-height:1.4}
.layout-room-label strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.layout-room-label span{color:#8d8379;white-space:nowrap}
.layout-note-box{min-height:112px;margin-top:10px;padding:12px;border:1px solid #d8d1c8;border-radius:16px}
.layout-note-box span{font-size:10px;font-weight:700}
.layout-note-box p{margin-top:10px;font-size:10px;line-height:1.7;color:#877d73;display:-webkit-box;-webkit-line-clamp:4;-webkit-box-orient:vertical;overflow:hidden}
.products-panel{padding-right:10px}
.product-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px 8px;margin-top:12px;align-content:start}
.product-tile{display:block;min-width:0;padding:0;border:0;background:transparent;text-align:center;font:inherit;color:inherit;cursor:pointer}
.product-tile:hover .product-image-tile{outline:2px solid #d7b48f;outline-offset:1px}
.product-image-tile{display:grid;place-items:center;height:72px;border-radius:4px;background:#111;overflow:hidden}
.product-image-tile :deep(.relative){width:100%;height:100%;background:#fff}
.product-image-tile :deep(img){width:100%;height:100%;object-fit:contain}
.product-image-tile .product-no-image{color:#fff}
.product-tile-caption{display:flex;justify-content:space-between;gap:4px;margin-top:5px;font-size:9px;line-height:1.3}
.product-tile-caption strong{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:500}
.product-tile-caption span{white-space:nowrap;color:#6d6258}
.product-grid-empty{margin-top:14px;padding:24px 8px;text-align:center;color:#a3988d;font-size:10px;border:1px dashed #ded5ca;border-radius:8px}
.render-stack{display:flex;flex-direction:column;gap:12px;margin-top:12px}
.render-slot{height:92px;padding:0;border:0;border-radius:4px;overflow:hidden;background:#111;color:#fff;font:inherit;cursor:pointer}
.render-slot:hover{outline:2px solid #d7b48f;outline-offset:1px}
.render-slot img{display:block;width:100%;height:100%;object-fit:cover}
.render-slot span{display:flex;flex-direction:column;align-items:center;justify-content:center;height:100%;font-size:10px}
.render-slot small{margin-top:5px;color:#cfcfcf;font-size:9px}
.comment-panel{background:#fff36a}
.comment-panel:hover{background:#ffef58}
.comment-copy{flex:1;min-height:280px;margin-top:18px}
.comment-copy p{font-size:11px;line-height:1.8;white-space:pre-wrap}
@media(max-width:1100px){.preset-space-board{grid-template-columns:repeat(5,minmax(220px,1fr));overflow-x:auto;overflow-y:hidden}.preset-panel{min-height:410px}.product-grid{grid-template-columns:repeat(4,minmax(42px,1fr))}}
@media(max-width:700px){.preset-space-board{grid-template-columns:1fr;overflow:visible}.preset-panel{min-height:270px;border-right:0;border-bottom:1px solid #eee6dc}.preset-panel:last-child{border-bottom:0}.requirement-scroll{min-height:110px}.comment-copy{min-height:120px}.preset-layout-thumb{height:180px}}
/* Confirmation controls and compact detail modal. */
.group-header-row{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:stretch;border-bottom:1px solid #eee6dc;background:#f8f5f1}
.group-header-row .group-header{border:0;border-radius:0}
.group-confirm-button,.space-confirm-button{display:inline-flex;align-items:center;justify-content:center;gap:5px;border:1px solid #e5dbcf;border-radius:8px;background:#fff;color:#a3783d;font-size:10px;font-weight:800;white-space:nowrap;cursor:pointer}
.group-confirm-button{align-self:center;margin:0 12px 0 4px;padding:7px 10px}
.space-detail-actions{display:flex;align-items:center;gap:7px}
.space-confirm-button{padding:7px 10px;background:#a77b51;border-color:#a77b51;color:#fff}
.group-confirm-button:hover,.space-confirm-button:hover{background:#8f633e;border-color:#8f633e;color:#fff}
.group-confirm-button.is-confirmed,.space-confirm-button:disabled{background:#eef8f1;border-color:#cfe6d8;color:#438064;cursor:default}
.group-confirm-button:disabled{opacity:1}
.detail-backdrop{align-items:center;justify-content:center;padding:24px;background:rgba(44,36,28,.38);backdrop-filter:blur(2px)}
.detail-drawer{width:min(560px,calc(100vw - 48px));height:auto;max-height:calc(100dvh - 48px);overflow:auto;border-radius:18px;padding:22px;box-shadow:0 24px 70px rgba(44,36,28,.24)}
@media(max-width:700px){.group-header-row{grid-template-columns:minmax(0,1fr)}.group-confirm-button{margin:0 12px 10px;min-height:32px}.space-detail-heading{flex-direction:column}.space-detail-actions{width:100%;justify-content:flex-start;flex-wrap:wrap}.detail-backdrop{padding:12px}.detail-drawer{width:calc(100vw - 24px);max-height:calc(100dvh - 24px);padding:18px}}
/* Give product imagery more room while keeping the compact five-column board. */
.product-image-tile{height:86px}
.large-product-art{width:82px;height:82px;flex:none}
.large-product-art :deep(svg){width:58px;height:58px}
.detail-product-hero{gap:16px;padding:16px}
.layout-detail-dialog{width:min(1100px,calc(100vw - 48px))}
.layout-plan-detail{height:min(65dvh,700px);min-height:240px;border:1px solid #e7e0d7;border-radius:12px;overflow:hidden}
.layout-detail-hint{font-size:12px;color:#968c82;margin-bottom:12px}
</style>