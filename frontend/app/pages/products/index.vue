<script setup lang="ts">
import type { CatalogCapabilities, ImportedProduct } from '~/types/imported-products'
import { catalogSourceLabel } from '~/utils/catalogSource'

const api = useOfficeApi()
const workspace = useWorkspaceStore()
const rsdpWeb = useRsdpWeb()
const products = ref<ImportedProduct[]>([])
const capabilities = ref<CatalogCapabilities | null>(null)
const managed = ref(false)
let listRequest = 0

async function loadCapabilities() {
  try {
    capabilities.value = await api.loadCatalogCapabilities()
  } catch {
    capabilities.value = null
  }
}

function setManaged(next: boolean) {
  if (managed.value === next) return
  managed.value = next
  currentPage.value = 1
  void loadProducts()
}
const isLoading = ref(true)
const listError = ref<string | null>(null)
const actionError = ref<string | null>(null)
const actionMessage = ref<string | null>(null)
const search = ref('')
const status = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const pageInput = ref(1)
const pageSizeOptions = [10, 20, 50, 100]
const selectedProduct = ref<ImportedProduct | null>(null)
const detailDialog = ref<HTMLDialogElement | null>(null)
const removingFavoriteProduct = ref<ImportedProduct | null>(null)
const removingFavorite = ref(false)
const removeFavoriteError = ref<string | null>(null)
const removeFavoriteDialog = ref<HTMLDialogElement | null>(null)
const favoriteProductIds = ref<Set<string>>(new Set())
const projectFavoriteProductIds = ref<Set<string>>(new Set())
const favoriteStatusError = ref<string | null>(null)
const projectFavoriteStatusError = ref<string | null>(null)
const favoriteDialog = ref<{ open: (product: ImportedProduct, section?: 'project' | 'personal') => Promise<void> } | null>(null)
let favoriteIdsRequest = 0
let favoriteStateVersion = 0
let projectFavoriteIdsRequest = 0
let projectFavoriteStateVersion = 0
/** 公共库工作区级开关状态（仅经销商主号可切换；成员无个人开关，完全跟随主号设置） */
const catalogStatus = ref<{ workspaceEnabled: boolean; dealer: boolean; effectiveVisible: boolean } | null>(null)
const savingCatalogStatus = ref(false)
const activeCount = computed(() => products.value.filter(product => product.status === 'active').length)
const statsAvailable = computed(() => !isLoading.value && !listError.value)
const filteredProducts = computed(() => {
  const query = search.value.trim().toLowerCase()
  return products.value.filter(product => {
    const matchesSearch = !query || [product.productName, product.rspuCode, product.externalCode, product.rspuId].some(value => value?.toLowerCase().includes(query))
    const matchesStatus = !status.value || (status.value === 'active' ? product.status === 'active' : product.status !== 'active')
    return matchesSearch && matchesStatus
  })
})
const pageCount = computed(() => Math.max(1, Math.ceil(filteredProducts.value.length / pageSize.value)))
const pageProducts = computed(() => filteredProducts.value.slice((currentPage.value - 1) * pageSize.value, currentPage.value * pageSize.value))
const pageStart = computed(() => filteredProducts.value.length ? (currentPage.value - 1) * pageSize.value + 1 : 0)
const pageEnd = computed(() => Math.min(currentPage.value * pageSize.value, filteredProducts.value.length))
const paginationItems = computed<Array<number | 'ellipsis'>>(() => {
  const total = pageCount.value
  const current = currentPage.value
  if (total <= 7) return Array.from({ length: total }, (_, index) => index + 1)
  const pages = new Set([1, total, current, current - 1, current + 1])
  const sorted = [...pages].filter(page => page >= 1 && page <= total).sort((a, b) => a - b)
  const items: Array<number | 'ellipsis'> = []
  sorted.forEach((page, index) => {
    if (index > 0 && page - sorted[index - 1] > 1) items.push('ellipsis')
    items.push(page)
  })
  return items
})
watch([search, status, pageSize], () => {
  currentPage.value = 1
})
watch(pageCount, count => {
  currentPage.value = Math.min(currentPage.value, count)
  pageInput.value = currentPage.value
})
watch(currentPage, value => { pageInput.value = value })

async function loadProducts() {
  const request = ++listRequest
  isLoading.value = true
  listError.value = null
  try {
    const result = await api.loadImportedProducts(managed.value)
    if (request === listRequest) products.value = result
  } catch (error) {
    if (request === listRequest) listError.value = error instanceof Error ? error.message : '商品列表请求失败'
  } finally {
    if (request === listRequest) isLoading.value = false
  }
}

async function loadCatalogStatus() {
  try {
    catalogStatus.value = await api.getSharedCatalogStatus()
  } catch {
    // 状态拉取失败不阻断商品列表，提示不展示
    catalogStatus.value = null
  }
}

async function toggleWorkspaceSharedCatalog() {
  const current = catalogStatus.value
  if (!current?.dealer || savingCatalogStatus.value) return

  savingCatalogStatus.value = true
  actionError.value = null
  actionMessage.value = null
  try {
    const updated = await api.setWorkspaceSharedCatalog(!current.workspaceEnabled)
    catalogStatus.value = updated
    managed.value = false
    currentPage.value = 1
    actionMessage.value = updated.workspaceEnabled ? '公共库已开启，对整个工作区生效' : '公共库已关闭，对整个工作区生效'
    await loadProducts()
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : '公共库开关切换失败'
  } finally {
    savingCatalogStatus.value = false
  }
}

function formatPrice(price: number | null) {
  return price == null ? '—' : `¥ ${price.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function formatDate(date: string) {
  const timestamp = new Date(date)
  return Number.isNaN(timestamp.getTime()) ? '—' : timestamp.toLocaleDateString('zh-CN')
}

function resetFilters() {
  search.value = ''
  status.value = ''
}

function goToPage(value = pageInput.value) {
  const target = Math.min(pageCount.value, Math.max(1, Math.trunc(Number(value) || 1)))
  currentPage.value = target
  pageInput.value = target
}

function onProductDragStart(product: ImportedProduct, event: DragEvent) {
  event.dataTransfer?.setData('text/plain', product.rspuId)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy'
}

async function showDetails(product: ImportedProduct) {
  selectedProduct.value = product
  await nextTick()
  detailDialog.value?.showModal()
}

async function loadFavoriteProductIds() {
  const request = ++favoriteIdsRequest
  const version = favoriteStateVersion
  try {
    const ids = await api.loadFavoriteProductIds()
    if (request !== favoriteIdsRequest || version !== favoriteStateVersion) return
    favoriteProductIds.value = new Set(ids)
    favoriteStatusError.value = null
  } catch (error) {
    if (request === favoriteIdsRequest && version === favoriteStateVersion) {
      favoriteStatusError.value = error instanceof Error ? error.message : '收藏状态加载失败'
    }
  }
}

async function loadProjectFavoriteProductIds(preserveCurrent = false) {
  const request = ++projectFavoriteIdsRequest
  const version = projectFavoriteStateVersion
  const projectId = workspace.currentProjectId
  if (!preserveCurrent) projectFavoriteProductIds.value = new Set()
  projectFavoriteStatusError.value = null
  if (!projectId) return
  try {
    const ids = await api.loadProjectFavoriteProductIds(projectId)
    if (request !== projectFavoriteIdsRequest || version !== projectFavoriteStateVersion || workspace.currentProjectId !== projectId) return
    projectFavoriteProductIds.value = new Set(ids)
  } catch (error) {
    if (request === projectFavoriteIdsRequest && version === projectFavoriteStateVersion && workspace.currentProjectId === projectId) {
      projectFavoriteStatusError.value = error instanceof Error ? error.message : '项目收藏状态加载失败'
    }
  }
}

watch(() => workspace.currentProjectId, () => {
  void loadProjectFavoriteProductIds()
}, { immediate: true })

function openFavorites(product: ImportedProduct, section: 'project' | 'personal' = 'personal') {
  void favoriteDialog.value?.open(product, section)
}

function togglePersonalFavorite(product: ImportedProduct) {
  if (favoriteProductIds.value.has(product.rspuId)) askRemoveFavorite(product)
  else openFavorites(product, 'personal')
}

function askRemoveFavorite(product: ImportedProduct) {
  removeFavoriteError.value = null
  removingFavoriteProduct.value = product
  nextTick(() => removeFavoriteDialog.value?.showModal())
}

function cancelRemoveFavorite() {
  if (removingFavorite.value) return
  removeFavoriteDialog.value?.close()
  removingFavoriteProduct.value = null
}

function adjustFavoriteFolders() {
  const product = removingFavoriteProduct.value
  if (!product || removingFavorite.value) return
  removeFavoriteDialog.value?.close()
  removingFavoriteProduct.value = null
  openFavorites(product, 'personal')
}

async function confirmRemoveFavorite() {
  const product = removingFavoriteProduct.value
  if (!product || removingFavorite.value) return
  removingFavorite.value = true
  removeFavoriteError.value = null
  try {
    await api.saveProductFavoriteFolders(product.rspuId, [])
    removeFavoriteDialog.value?.close()
    removingFavoriteProduct.value = null
    favoriteSaved({ rspuId: product.rspuId, folderIds: [] })
  } catch (error) {
    removeFavoriteError.value = error instanceof Error ? error.message : '取消收藏失败'
  } finally {
    removingFavorite.value = false
  }
}

function favoriteSaved(payload: { rspuId: string, folderIds: string[] }) {
  favoriteStateVersion++
  const next = new Set(favoriteProductIds.value)
  if (payload.folderIds.length) next.add(payload.rspuId)
  else next.delete(payload.rspuId)
  favoriteProductIds.value = next
  favoriteStatusError.value = null
  void loadFavoriteProductIds()
}

function projectFavoriteSaved(payload: { rspuId: string, projectId: string, selected: boolean }) {
  if (payload.projectId !== workspace.currentProjectId) return
  projectFavoriteStateVersion++
  const next = new Set(projectFavoriteProductIds.value)
  if (payload.selected) next.add(payload.rspuId)
  else next.delete(payload.rspuId)
  projectFavoriteProductIds.value = next
  projectFavoriteStatusError.value = null
  void loadProjectFavoriteProductIds(true)
}

onMounted(() => {
  loadProducts()
  loadCatalogStatus()
  loadCapabilities()
  loadFavoriteProductIds()
})
</script>

<template>
  <div class="flex h-full min-h-0">
    <div class="flex h-full min-h-0 min-w-0 flex-1 flex-col overflow-y-auto">
    <div class="flex-none">
      <div class="mb-3 text-xs text-muted">商品资源 / 商品库</div>
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 class="mb-2 text-[25px] font-bold tracking-tight">商品库</h1>
          <p class="text-xs text-muted">从 RSDP 读取商品，供空间设计、选品和报价使用。</p>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <button v-if="catalogStatus?.dealer" type="button" role="switch" aria-label="公共库"
            :aria-checked="catalogStatus.workspaceEnabled" :disabled="savingCatalogStatus"
            class="inline-flex items-center gap-2 rounded-lg border border-line bg-white px-4 py-2.5 text-sm font-semibold transition hover:bg-cream disabled:opacity-60"
            @click="toggleWorkspaceSharedCatalog">
            <span>公共库</span>
            <span class="rounded px-1.5 py-0.5 text-xs" :class="catalogStatus.workspaceEnabled ? 'bg-green/10 text-green' : 'bg-bg text-muted'">
              {{ savingCatalogStatus ? '保存中' : catalogStatus.workspaceEnabled ? '已开启' : '已关闭' }}
            </span>
          </button>
          <a :href="rsdpWeb('/products')" target="_blank" rel="noopener" class="inline-flex items-center gap-2 rounded-lg border border-line bg-white px-4 py-2.5 text-sm font-semibold transition hover:bg-cream">
            去 RSDP 管理商品
          </a>
        </div>
      </div>
    </div>

    <nav v-if="capabilities?.managed && catalogStatus?.workspaceEnabled" class="mb-3 flex gap-2" aria-label="商品库范围">
      <button type="button" class="rounded-md border border-line px-3 py-2 text-sm" :class="{ 'bg-cream font-semibold': !managed }" :aria-pressed="!managed" @click="setManaged(false)">共享商品</button>
      <button type="button" class="rounded-md border border-line px-3 py-2 text-sm" :class="{ 'bg-cream font-semibold': managed }" :aria-pressed="managed" @click="setManaged(true)">本厂录入</button>
    </nav>
    <div class="flex flex-none flex-wrap items-center gap-x-6 gap-y-2 text-xs text-muted" aria-live="polite">
      <span>商品总数 <strong class="ml-1 text-sm text-ink">{{ statsAvailable ? products.length : '—' }}</strong></span>
      <span>在售商品 <strong class="ml-1 text-sm text-green">{{ statsAvailable ? activeCount : '—' }}</strong></span>
      <span v-if="catalogStatus && !catalogStatus.workspaceEnabled">公共库已被工作区主号关闭，当前仅显示本厂商品</span>
      <span v-if="favoriteStatusError" class="text-red-800">收藏状态加载失败 <button type="button" class="font-semibold underline" @click="loadFavoriteProductIds">重试</button></span>
      <span v-if="projectFavoriteStatusError" class="text-red-800">项目收藏状态加载失败 <button type="button" class="font-semibold underline" @click="loadProjectFavoriteProductIds()">重试</button></span>
    </div>

    <section class="flex-none overflow-hidden rounded-xl border border-line bg-white shadow-panel" aria-label="商品列表">
      <div class="flex flex-wrap items-center gap-3 border-b border-line p-4">
        <div class="relative min-w-0 flex-[1_1_240px]">
          <AppIcon name="search" size="4" class="absolute left-3 top-3 text-muted" />
          <input v-model="search" type="search" aria-label="搜索商品名称或编码" placeholder="搜索商品名称、编码" class="w-full rounded-lg border border-line bg-bg py-2.5 pl-9 pr-3 text-sm outline-bronze" :disabled="!statsAvailable">
        </div>
        <select v-model="status" aria-label="筛选商品状态" class="min-w-0 rounded-lg border border-line bg-white px-3 py-2.5 text-sm outline-bronze" :disabled="!statsAvailable">
          <option value="">全部状态</option><option value="active">在售</option><option value="other">非在售</option>
        </select>
        <button type="button" class="rounded-lg border border-line p-2.5 text-muted transition hover:bg-cream disabled:opacity-50" aria-label="刷新商品列表" :disabled="isLoading" @click="loadProducts"><AppIcon name="reset" size="4" :class="{ 'animate-spin': isLoading }" /></button>
      </div>

      <div v-if="actionError" class="border-b border-red-100 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">{{ actionError }}</div>
      <div v-if="actionMessage" class="border-b border-green/20 bg-green/10 px-4 py-3 text-sm text-green" role="status">{{ actionMessage }}</div>

      <div v-if="isLoading" class="p-4 sm:p-5" role="status" aria-label="正在加载商品">
        <div class="sr-only">加载商品数据中…</div>
        <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 2xl:grid-cols-8" aria-hidden="true">
          <div v-for="card in 10" :key="card" class="animate-pulse overflow-hidden rounded-xl border border-line">
            <div class="aspect-[4/3] bg-cream" />
            <div class="space-y-3 p-4"><div class="h-4 w-2/3 rounded bg-cream" /><div class="h-3 w-1/2 rounded bg-bg" /><div class="h-5 w-24 rounded bg-cream" /><div class="h-3 w-32 rounded bg-bg" /><div class="h-8 rounded-md bg-cream" /></div>
          </div>
        </div>
      </div>

      <div v-else-if="listError" class="grid min-h-[320px] place-items-center p-6" role="alert">
        <div class="w-full max-w-md text-center">
          <div class="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-xl bg-red-50 text-red-700"><AppIcon name="warning" size="6" /></div>
          <h2 class="mb-2 text-base font-bold">商品暂时无法加载</h2>
          <p class="mb-5 text-sm text-muted">请检查服务连接后重试，商品数量暂不可用。</p>
          <button type="button" class="inline-flex items-center gap-2 rounded-lg bg-bronze px-5 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark" @click="loadProducts"><AppIcon name="reset" size="4" />重新加载</button>
          <details class="mt-5 text-left text-xs text-muted"><summary class="cursor-pointer text-center">技术详情</summary><p class="mt-3 break-all rounded-lg bg-bg p-3">{{ listError }}</p></details>
        </div>
      </div>

      <div v-else-if="products.length === 0" class="grid min-h-[320px] place-items-center p-6">
        <div class="text-center"><div class="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-xl bg-cream text-bronze"><AppIcon name="cube" size="6" /></div><h2 class="mb-2 text-base font-bold">商品库还是空的</h2><p class="text-sm text-muted">当前范围没有可读取的商品。</p></div>
      </div>

      <div v-else-if="filteredProducts.length === 0" class="grid min-h-[280px] place-items-center p-6">
        <div class="text-center"><h2 class="mb-2 text-base font-bold">没有找到匹配的商品</h2><p class="mb-4 text-sm text-muted">试试其他关键词，或调整筛选条件。</p><button type="button" class="rounded-lg bg-cream px-4 py-2 text-sm font-semibold text-bronze-dark" @click="resetFilters">清空筛选</button></div>
      </div>

      <div v-else class="p-4 sm:p-5">
        <ul aria-label="商品卡片列表" class="grid grid-cols-1 gap-4 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 2xl:grid-cols-8">
          <li v-for="product in pageProducts" :key="product.rspuId" :data-testid="`product-card-${product.rspuId}`"
            draggable="true" @dragstart="onProductDragStart(product, $event)"
            class="group flex min-w-0 flex-col overflow-hidden rounded-xl border border-line bg-white transition duration-200 hover:-translate-y-0.5 hover:border-bronze/60 hover:shadow-panel">
            <div class="relative shrink-0">
              <NuxtLink :to="`/products/${product.rspuId}`" class="relative block aspect-[4/3] overflow-hidden border-b border-line bg-white outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-bronze" :aria-label="`查看${product.productName || '商品'}详情`">
                <ProductImagePreview :src="product.primaryImageUrl" alt="" size="small" />
                <span class="absolute left-3 top-3 rounded-md px-2 py-1 text-xs shadow-sm" :class="product.status === 'active' ? 'bg-green/10 text-green' : 'bg-bg text-muted'">{{ product.status === 'active' ? '在售' : '非在售' }}</span>
              </NuxtLink>
              <button type="button" class="favorite-action absolute right-14 top-3 z-10 grid h-9 w-9 place-items-center rounded-full border shadow-sm transition hover:bg-cream focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-bronze focus-visible:ring-offset-2 disabled:cursor-not-allowed" :class="[projectFavoriteProductIds.has(product.rspuId) ? 'border-bronze bg-bronze text-white' : 'border-line bg-white/95 text-bronze-dark']" :aria-label="`项目收藏${product.productName || '商品'}`" :aria-pressed="projectFavoriteProductIds.has(product.rspuId)" :disabled="!workspace.currentProjectId" :title="workspace.currentProjectId ? (projectFavoriteProductIds.has(product.rspuId) ? '已加入当前项目，点击调整' : '收藏到当前项目') : '请先选择项目'" @click="openFavorites(product, 'project')">
                <AppIcon name="plus" size="5" />
              </button>
              <button type="button" class="favorite-action absolute right-3 top-3 z-10 grid h-9 w-9 place-items-center rounded-full border border-line bg-white/95 text-bronze-dark shadow-sm transition hover:bg-cream focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-bronze focus-visible:ring-offset-2" :aria-label="`个人收藏${product.productName || '商品'}`" :aria-pressed="favoriteProductIds.has(product.rspuId)" :title="favoriteProductIds.has(product.rspuId) ? '已收藏，点击取消收藏' : '添加到个人收藏夹'" @click="togglePersonalFavorite(product)">
                <AppIcon :name="favoriteProductIds.has(product.rspuId) ? 'heartSolid' : 'heart'" size="5" />
              </button>
            </div>
            <div class="flex flex-1 flex-col p-4">
              <NuxtLink :to="`/products/${product.rspuId}`" class="line-clamp-2 min-h-10 text-sm font-semibold leading-5 outline-none transition hover:text-bronze-dark focus-visible:rounded focus-visible:ring-2 focus-visible:ring-bronze" :title="product.productName || undefined">{{ product.productName || '未命名商品' }}</NuxtLink>
              <div class="mt-1 truncate text-xs text-muted">{{ product.rspuCode || product.externalCode || product.rspuId }}</div>
              <div v-if="product.sourceChannel || managed" class="mt-1 truncate text-xs text-muted">{{ catalogSourceLabel(product.sourceChannel, product.sourceFactoryName || product.ownerFactoryCode) }}</div>
              <div class="mt-4 text-lg font-semibold tabular-nums text-bronze-dark">{{ formatPrice(product.retailPrice) }}</div>
              <div class="mt-3 flex items-center justify-between gap-2 text-xs text-muted"><span>更新日期</span><time :datetime="product.updatedAt">{{ formatDate(product.updatedAt) }}</time></div>
              <div class="mt-auto pt-4">
                <NuxtLink :to="`/products/${product.rspuId}`" class="flex items-center justify-center rounded-md border-t border-line bg-cream px-3 py-2 text-xs font-semibold text-bronze-dark outline-none transition hover:bg-bronze hover:text-white focus-visible:ring-2 focus-visible:ring-bronze focus-visible:ring-offset-2" :aria-label="`查看${product.productName || '商品'}详情`">查看详情</NuxtLink>
              </div>
            </div>
          </li>
        </ul>
      </div>

      <div v-if="statsAvailable && filteredProducts.length" class="border-t border-line bg-[#fcfbfa] px-4 py-4 sm:px-5" aria-label="商品分页">
        <div class="flex flex-col gap-4 xl:flex-row xl:items-center xl:justify-between">
          <div class="text-xs text-muted" aria-live="polite">
            正在显示 <strong class="font-semibold text-ink">{{ pageStart }}–{{ pageEnd }}</strong> 条，共 <strong class="font-semibold text-ink">{{ filteredProducts.length }}</strong> 件商品
          </div>

          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-end">
            <label class="flex items-center gap-2 text-xs text-muted">
              每页显示
              <select v-model.number="pageSize" aria-label="每页显示数量" class="rounded-lg border border-line bg-white px-2.5 py-2 text-xs font-medium text-ink outline-bronze">
                <option v-for="size in pageSizeOptions" :key="size" :value="size">{{ size }} 条</option>
              </select>
            </label>

            <nav class="flex items-center gap-1 overflow-x-auto" aria-label="商品列表页码">
              <button type="button" class="inline-flex h-9 shrink-0 items-center rounded-lg border border-line bg-white px-3 text-xs font-medium text-ink transition hover:bg-cream disabled:cursor-not-allowed disabled:opacity-40" :disabled="currentPage === 1" aria-label="第一页" @click="goToPage(1)">首页</button>
              <button type="button" class="inline-flex h-9 shrink-0 items-center rounded-lg border border-line bg-white px-3 text-xs font-medium text-ink transition hover:bg-cream disabled:cursor-not-allowed disabled:opacity-40" :disabled="currentPage === 1" aria-label="上一页" @click="goToPage(currentPage - 1)">上一页</button>
              <template v-for="(item, index) in paginationItems" :key="`${item}-${index}`">
                <span v-if="item === 'ellipsis'" class="grid h-9 w-8 shrink-0 place-items-center text-xs text-muted" aria-hidden="true">…</span>
                <button v-else type="button" class="grid h-9 w-9 shrink-0 place-items-center rounded-lg border text-xs font-medium transition" :class="item === currentPage ? 'border-bronze bg-bronze text-white' : 'border-line bg-white text-ink hover:bg-cream'" :aria-current="item === currentPage ? 'page' : undefined" :aria-label="`第${item}页`" @click="goToPage(item)">{{ item }}</button>
              </template>
              <button type="button" class="inline-flex h-9 shrink-0 items-center rounded-lg border border-line bg-white px-3 text-xs font-medium text-ink transition hover:bg-cream disabled:cursor-not-allowed disabled:opacity-40" :disabled="currentPage === pageCount" aria-label="下一页" @click="goToPage(currentPage + 1)">下一页</button>
              <button type="button" class="inline-flex h-9 shrink-0 items-center rounded-lg border border-line bg-white px-3 text-xs font-medium text-ink transition hover:bg-cream disabled:cursor-not-allowed disabled:opacity-40" :disabled="currentPage === pageCount" aria-label="最后一页" @click="goToPage(pageCount)">末页</button>
            </nav>

            <label class="flex items-center gap-2 text-xs text-muted">
              跳至
              <input v-model.number="pageInput" type="number" min="1" :max="pageCount" class="h-9 w-16 rounded-lg border border-line bg-white px-2 text-center text-xs text-ink outline-bronze" aria-label="跳转到页码" @keyup.enter="goToPage()">
              <span>页</span>
              <button type="button" class="h-9 rounded-lg border border-line bg-white px-3 text-xs font-medium text-ink transition hover:bg-cream" @click="goToPage()">跳转</button>
            </label>
          </div>
        </div>
      </div>
    </section>

    <FavoriteFolderDialog ref="favoriteDialog" @saved="favoriteSaved" @project-saved="projectFavoriteSaved" />

    <dialog ref="removeFavoriteDialog" class="m-auto w-[min(430px,calc(100%_-_32px))] rounded-2xl border border-line bg-white p-0 text-ink shadow-panel backdrop:bg-black/30" aria-label="取消收藏" @cancel="event => { if (removingFavorite) event.preventDefault() }" @close="removingFavoriteProduct = null">
      <div class="p-6">
        <div class="mb-4 flex items-start justify-between gap-4">
          <h2 class="text-lg font-bold">取消收藏</h2>
          <button type="button" class="rounded-lg p-1.5 text-muted transition hover:bg-cream disabled:opacity-50" aria-label="关闭取消收藏确认" :disabled="removingFavorite" @click="cancelRemoveFavorite"><AppIcon name="close" size="5" /></button>
        </div>
        <div class="mb-4 flex gap-3 rounded-xl border border-red-100 bg-red-50 p-3.5">
          <AppIcon name="warning" size="5" class="mt-0.5 flex-none text-[#b3402f]" />
          <p class="text-sm leading-6">确定取消收藏「<strong>{{ removingFavoriteProduct?.productName || '商品' }}</strong>」吗？将从你的所有个人收藏夹中移除。</p>
        </div>
        <p class="mb-5 text-xs leading-5 text-muted">其他收藏夹中的收藏不受影响，可随时在商品库重新收藏。若只想换分组，可点下方「调整收藏夹」。</p>
        <div v-if="removeFavoriteError" class="mb-4 rounded-lg border border-red-100 bg-red-50 px-3 py-2 text-xs text-red-800" role="alert">{{ removeFavoriteError }}</div>
        <div class="flex items-center justify-between gap-3 border-t border-line pt-4">
          <button type="button" class="rounded-lg border border-line px-5 py-2.5 text-sm disabled:opacity-50" :disabled="removingFavorite" @click="adjustFavoriteFolders">调整收藏夹</button>
          <div class="flex gap-3">
            <button type="button" class="rounded-lg border border-line px-5 py-2.5 text-sm disabled:opacity-50" :disabled="removingFavorite" @click="cancelRemoveFavorite">取消</button>
            <button type="button" class="rounded-lg bg-[#b3402f] px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-[#9c3627] disabled:opacity-50" :disabled="removingFavorite" @click="confirmRemoveFavorite">{{ removingFavorite ? '处理中…' : '确认取消收藏' }}</button>
          </div>
        </div>
      </div>
    </dialog>

    <dialog ref="detailDialog" class="m-auto max-h-[85dvh] w-[min(560px,calc(100%_-_32px))] overflow-y-auto rounded-2xl border border-line bg-white p-6 text-ink shadow-panel backdrop:bg-black/30" aria-labelledby="product-detail-title" @close="selectedProduct = null" @click="event => { if (event.target === event.currentTarget) detailDialog?.close() }">
      <template v-if="selectedProduct">
        <div class="mb-5 flex items-start justify-between gap-4"><div><div class="mb-1 text-xs text-muted">商品详情</div><h2 id="product-detail-title" class="text-lg font-bold">{{ selectedProduct.productName || '未命名商品' }}</h2></div><form method="dialog"><button type="submit" class="rounded-lg p-2 text-muted hover:bg-cream" aria-label="关闭商品详情"><AppIcon name="close" size="5" /></button></form></div>
        <dl class="grid grid-cols-[90px_minmax(0,1fr)] gap-x-3 gap-y-4 text-sm"><dt class="text-muted">商品编码</dt><dd class="break-all">{{ selectedProduct.rspuCode || selectedProduct.rspuId }}</dd><dt class="text-muted">外部编码</dt><dd class="break-all">{{ selectedProduct.externalCode || '—' }}</dd><dt class="text-muted">零售价</dt><dd class="font-semibold text-bronze-dark">{{ formatPrice(selectedProduct.retailPrice) }}</dd><dt class="text-muted">更新日期</dt><dd>{{ formatDate(selectedProduct.updatedAt) }}</dd></dl>
      </template>
    </dialog>
    </div>
    <ProductComboPanel :products="products" class="hidden md:flex" />
  </div>
</template>

<style scoped>
.favorite-action {
  opacity: 0;
}

.group:hover .favorite-action,
.group:focus-within .favorite-action,
.favorite-action:focus-visible {
  opacity: 1;
}

@media (hover: none) {
  .favorite-action {
    opacity: 1;
  }
}
</style>
