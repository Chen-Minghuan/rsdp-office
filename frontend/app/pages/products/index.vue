<script setup lang="ts">
import type { CatalogBrowseFacets, CatalogBrowseRow, ImportedProduct } from '~/types/imported-products'

const api = useOfficeApi()
const workspace = useWorkspaceStore()
const { storageScope } = useAuth()
const catalogFacets = ref<CatalogBrowseFacets | null>(null)
const localCategoryLabels = shallowRef(new Map<string, string>())
const serverProducts = ref<CatalogBrowseRow[]>([])
const resultTotal = ref(0)
const catalogTotal = ref(0)
const activeCount = ref(0)
const localTotal = ref(0)
const canManageLocal = ref(false)
const sharedUnavailable = ref(false)
let localCategoriesRequested = false
const collectionItems = ref<ImportedProduct[]>([])
const loadingCollectionItems = ref(false)
const failedCollectionItems = ref(false)
const facetLoading = ref(false)
const facetError = ref('')
let facetRequest = 0
let listRequest = 0

const isLoading = ref(true)
const listError = ref<string | null>(null)
const actionError = ref<string | null>(null)
const actionMessage = ref<string | null>(null)
const search = ref('')
const submittedSearch = ref('')
const status = ref('')
const category = ref('')
const color = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const pageInput = ref(1)
const pageSizeOptions = [10, 20, 50, 100]
const removingFavoriteProduct = ref<ImportedProduct | null>(null)
const removingFavorite = ref(false)
const removeFavoriteError = ref<string | null>(null)
const removeFavoriteDialog = ref<HTMLDialogElement | null>(null)
const favoriteProductIds = ref<Set<string>>(new Set())
const projectFavoriteProductIds = ref<Set<string>>(new Set())
const favoriteStatusError = ref<string | null>(null)
const projectFavoriteStatusError = ref<string | null>(null)
const favoriteLoading = ref(true)
const projectFavoriteLoading = ref(false)
const collectionOpen = ref(false)
const bundlesAvailable = ref(false)
const collectionId = useId()
const collectionPanel = ref<HTMLElement | null>(null)
const selectedCollection = ref<'project' | 'personal'>()
const favoriteDialog = ref<{ open: (product: ImportedProduct, section?: 'project' | 'personal') => Promise<void> } | null>(null)
const bundlePanel = ref<{ addProduct: (product: ImportedProduct) => Promise<void> } | null>(null)

function addToBundle(product: ImportedProduct) {
  collectionOpen.value = true
  void bundlePanel.value?.addProduct(product)
  nextTick(() => collectionPanel.value?.focus())
}
let favoriteIdsRequest = 0
let favoriteStateVersion = 0
let projectFavoriteIdsRequest = 0
let projectFavoriteStateVersion = 0
/** 公共库工作区级开关状态（仅经销商主号可切换；成员无个人开关，完全跟随主号设置） */
const catalogStatus = ref<{ workspaceEnabled: boolean; dealer: boolean; effectiveVisible: boolean } | null>(null)
const catalogStatusReady = ref(false)
const localOnly = computed(() => catalogStatus.value?.effectiveVisible === false)
const showRsdpProducts = computed(() => catalogStatusReady.value && catalogStatus.value?.effectiveVisible === true)
const savingCatalogStatus = ref(false)
let statusRequest = 0
const statsAvailable = computed(() => !isLoading.value && !listError.value)
const categories = computed(() => [
  ...(catalogFacets.value?.localCategories ?? []).map(code => ({ value: 'local:' + code, label: localCategoryLabel(code) })),
  ...(catalogFacets.value?.sharedCategories ?? []).map(label => ({ value: 'shared:' + label, label })),
].sort((a, b) => a.label.localeCompare(b.label, 'zh-CN')))
const colors = computed(() => [...(catalogFacets.value?.colors ?? [])].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const filtersApplied = computed(() => !!(search.value || status.value || category.value || color.value))

function localCategoryLabel(code: string) {
  const label = localCategoryLabels.value.get(code)
  return label ? `${label} · ${code}` : code
}

function loadLocalCategoryLabels() {
  if (localCategoriesRequested) return
  localCategoriesRequested = true
  void api.listLocalProductCategories().then(labels => {
    localCategoryLabels.value = new Map(labels.map(item => [item.dictCode, item.dictName]))
  }).catch(() => { localCategoriesRequested = false })
}

const collection = computed(() => selectedCollection.value ?? (workspace.currentProjectId ? 'project' : 'personal'))
const collectionLoading = computed(() => loadingCollectionItems.value
  || (collection.value === 'project' ? projectFavoriteLoading.value : favoriteLoading.value))
const collectionError = computed(() => failedCollectionItems.value
  || !!(collection.value === 'project' ? projectFavoriteStatusError.value : favoriteStatusError.value))
const collectionProducts = computed(() => {
  if (!statsAvailable.value) return []
  const ids = collection.value === 'project' ? projectFavoriteProductIds.value : favoriteProductIds.value
  return collectionItems.value.filter(product => ids.has(product.rspuId))
})
const pageCount = computed(() => Math.max(1, Math.ceil(resultTotal.value / pageSize.value)))
const pageProducts = computed(() => localOnly.value ? serverProducts.value.filter(row => row.local) : serverProducts.value)
const sharedPageProducts = computed(() => pageProducts.value.flatMap(row => row.shared ? [row.shared] : []))
const pageStart = computed(() => resultTotal.value ? (currentPage.value - 1) * pageSize.value + 1 : 0)
const pageEnd = computed(() => Math.min(currentPage.value * pageSize.value, resultTotal.value))
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
watch(search, (value, _, onCleanup) => {
  const timeout = setTimeout(() => { submittedSearch.value = value.trim() }, 180)
  onCleanup(() => clearTimeout(timeout))
})
watch([submittedSearch, status, category, color, pageSize], () => { currentPage.value = 1 }, { flush: 'sync' })
watch(currentPage, value => { pageInput.value = value })
watch([submittedSearch, status, category, color, pageSize, currentPage, catalogStatusReady, localOnly], () => { void loadProducts() })
watch(localOnly, () => {
  listRequest++
  isLoading.value = true
  status.value = ''
  invalidateFacets()
  currentPage.value = 1
}, { flush: 'sync' })
watch(storageScope, () => { void loadCatalogContext() })
onBeforeUnmount(() => { listRequest++; facetRequest++; statusRequest++ })

async function loadProducts() {
  if (!catalogStatusReady.value) return
  const request = ++listRequest
  isLoading.value = true
  listError.value = null
  try {
    const result = await api.browseProductCatalog({
      page: currentPage.value, size: pageSize.value, search: submittedSearch.value,
      status: status.value, category: category.value, color: color.value,
    })
    if (request !== listRequest) return
    serverProducts.value = result.rows
    resultTotal.value = result.total
    catalogTotal.value = result.catalogTotal
    activeCount.value = result.activeTotal
    localTotal.value = result.localTotal
    canManageLocal.value = result.canManageLocal
    sharedUnavailable.value = result.sharedUnavailable
    if (result.localTotal) loadLocalCategoryLabels()
    if (result.sharedUnavailable && !result.localTotal) listError.value = '公共库暂时无法加载'
    if (currentPage.value > pageCount.value) currentPage.value = pageCount.value
  } catch (error) {
    if (request === listRequest) listError.value = error instanceof Error ? error.message : '商品列表请求失败'
  } finally {
    if (request === listRequest) isLoading.value = false
  }
}

async function loadFacets() {
  if (!catalogStatusReady.value || facetLoading.value || (catalogFacets.value && !catalogFacets.value.sharedUnavailable)) return
  const request = ++facetRequest
  facetLoading.value = true
  facetError.value = ''
  try {
    const facets = await api.browseProductFacets()
    if (request === facetRequest) {
      catalogFacets.value = facets
      if (facets.sharedUnavailable) facetError.value = '公共库的分类和颜色暂时无法加载。'
      if (facets.localCategories.length) loadLocalCategoryLabels()
    }
  } catch {
    if (request === facetRequest) facetError.value = '分类和颜色暂时无法加载，请重试。'
  } finally {
    if (request === facetRequest) facetLoading.value = false
  }
}

function invalidateFacets() {
  facetRequest++
  catalogFacets.value = null
  facetError.value = ''
  facetLoading.value = false
  category.value = ''
  color.value = ''
}

function refreshCatalog() {
  const filtersChange = category.value || color.value || currentPage.value !== 1
  invalidateFacets()
  currentPage.value = 1
  if (!filtersChange) void loadProducts()
}

async function loadCatalogContext() {
  const request = ++statusRequest
  listRequest++
  catalogStatusReady.value = false
  catalogStatus.value = null
  canManageLocal.value = false
  invalidateFacets()
  currentPage.value = 1
  try {
    const current = await api.getSharedCatalogStatus()
    if (request === statusRequest) catalogStatus.value = current
  } catch {
    // The browse endpoint independently checks workspace visibility.
  } finally {
    if (request === statusRequest) catalogStatusReady.value = true
  }
}

async function toggleWorkspaceSharedCatalog() {
  const current = catalogStatus.value
  if (!current?.dealer || savingCatalogStatus.value) return
  const request = statusRequest

  savingCatalogStatus.value = true
  actionError.value = null
  actionMessage.value = null
  try {
    const updated = await api.setWorkspaceSharedCatalog(!current.workspaceEnabled)
    if (request !== statusRequest) return
    catalogStatus.value = updated
    actionMessage.value = updated.workspaceEnabled ? '公共库已开启，对整个工作区生效' : '公共库已关闭，对整个工作区生效'
  } catch (error) {
    if (request === statusRequest) actionError.value = error instanceof Error ? error.message : '公共库开关切换失败'
  } finally {
    savingCatalogStatus.value = false
  }
}

function resetFilters() {
  search.value = ''
  submittedSearch.value = ''
  status.value = ''
  category.value = ''
  color.value = ''
}

function goToPage(value = pageInput.value) {
  const target = Math.min(pageCount.value, Math.max(1, Math.trunc(Number(value) || 1)))
  currentPage.value = target
  pageInput.value = target
}

async function loadFavoriteProductIds() {
  const request = ++favoriteIdsRequest
  const version = favoriteStateVersion
  favoriteLoading.value = true
  try {
    const ids = await api.loadFavoriteProductIds()
    if (request !== favoriteIdsRequest || version !== favoriteStateVersion) return
    favoriteProductIds.value = new Set(ids)
    favoriteStatusError.value = null
  } catch (error) {
    if (request === favoriteIdsRequest && version === favoriteStateVersion) {
      favoriteStatusError.value = error instanceof Error ? error.message : '收藏状态加载失败'
    }
  } finally {
    if (request === favoriteIdsRequest && version === favoriteStateVersion) favoriteLoading.value = false
  }
}

async function loadProjectFavoriteProductIds(preserveCurrent = false) {
  const request = ++projectFavoriteIdsRequest
  const version = projectFavoriteStateVersion
  const projectId = workspace.currentProjectId
  if (!preserveCurrent) projectFavoriteProductIds.value = new Set()
  projectFavoriteStatusError.value = null
  projectFavoriteLoading.value = !!projectId
  if (!projectId) return
  try {
    const ids = await api.loadProjectFavoriteProductIds(projectId)
    if (request !== projectFavoriteIdsRequest || version !== projectFavoriteStateVersion || workspace.currentProjectId !== projectId) return
    projectFavoriteProductIds.value = new Set(ids)
  } catch (error) {
    if (request === projectFavoriteIdsRequest && version === projectFavoriteStateVersion && workspace.currentProjectId === projectId) {
      projectFavoriteStatusError.value = error instanceof Error ? error.message : '项目收藏状态加载失败'
    }
  } finally {
    if (request === projectFavoriteIdsRequest && version === projectFavoriteStateVersion && workspace.currentProjectId === projectId) projectFavoriteLoading.value = false
  }
}

watch([() => workspace.currentProjectId, showRsdpProducts], () => {
  if (showRsdpProducts.value) void loadProjectFavoriteProductIds()
})
watch(showRsdpProducts, visible => {
  if (visible) void loadFavoriteProductIds()
})

function openFavorites(product: ImportedProduct, section: 'project' | 'personal' = 'personal') {
  void favoriteDialog.value?.open(product, section)
}

const collectionVersion = ref(0)
watch([collection, () => workspace.currentProjectId, storageScope, collectionVersion, showRsdpProducts],
  async ([section, projectId], _, onCleanup) => {
    if (!showRsdpProducts.value) return
    let current = true
    onCleanup(() => { current = false })
    collectionItems.value = []
    loadingCollectionItems.value = true
    failedCollectionItems.value = false
    try {
      const rows = section === 'project' ? (projectId ? await api.loadProjectFavoriteItems(projectId) : []) : await api.loadFavoriteItems()
      if (current) collectionItems.value = rows
    } catch {
      if (current) failedCollectionItems.value = true
    } finally {
      if (current) loadingCollectionItems.value = false
    }
  }, { immediate: true })

function reloadCollection() {
  collectionVersion.value++
  if (collection.value === 'project') return loadProjectFavoriteProductIds(true)
  return loadFavoriteProductIds()
}

function toggleCollection() {
  collectionOpen.value = !collectionOpen.value
  if (collectionOpen.value) nextTick(() => collectionPanel.value?.focus())
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
  collectionVersion.value++
  favoriteStateVersion++
  const next = new Set(favoriteProductIds.value)
  if (payload.folderIds.length) next.add(payload.rspuId)
  else next.delete(payload.rspuId)
  favoriteProductIds.value = next
  favoriteStatusError.value = null
  void loadFavoriteProductIds()
}

function projectFavoriteSaved(payload: { rspuId: string, projectId: string, selected: boolean }) {
  collectionVersion.value++
  if (payload.projectId !== workspace.currentProjectId) return
  projectFavoriteStateVersion++
  const next = new Set(projectFavoriteProductIds.value)
  if (payload.selected) next.add(payload.rspuId)
  else next.delete(payload.rspuId)
  projectFavoriteProductIds.value = next
  projectFavoriteStatusError.value = null
  void loadProjectFavoriteProductIds(true)
}

onMounted(loadCatalogContext)
</script>

<template>
  <div class="grid min-w-0 content-start gap-4 p-3 sm:p-4">
    <header class="flex flex-wrap items-center justify-between gap-3">
      <h1 class="text-2xl font-bold tracking-tight">商品库</h1>
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
        <NuxtLink v-if="canManageLocal" to="/products/local" class="inline-flex items-center gap-2 rounded-lg border border-line bg-white px-4 py-2.5 text-sm font-semibold transition hover:bg-cream">
          管理本地商品
        </NuxtLink>
        <button v-if="showRsdpProducts" type="button" class="collection-toggle border border-neutral-300 px-3 py-2 text-sm font-semibold text-accent-ink"
          :aria-controls="collectionId" :aria-expanded="collectionOpen" @click="toggleCollection">收藏夹</button>
      </div>
    </header>

    <p v-if="localOnly" class="text-xs text-muted">公共库已关闭</p>
    <p v-if="actionError" role="alert" class="rounded-lg bg-red-50 px-4 py-2 text-sm text-red-700">{{ actionError }}</p>
    <p v-if="actionMessage" role="status" class="rounded-lg bg-green/10 px-4 py-2 text-sm text-green">{{ actionMessage }}</p>
    <p v-if="!catalogStatusReady" role="status" class="text-sm text-muted">加载中…</p>
    <template v-else>
    <div class="flex flex-none flex-wrap items-center gap-x-6 gap-y-2 text-xs text-muted" aria-live="polite">
      <span>商品总数 <strong class="ml-1 text-sm text-ink">{{ statsAvailable ? catalogTotal : '—' }}</strong></span>
      <span>本地商品 <strong class="ml-1 text-sm text-ink">{{ statsAvailable ? localTotal : '—' }}</strong></span>
      <span v-if="!localOnly">在售商品 <strong class="ml-1 text-sm text-green">{{ statsAvailable ? activeCount : '—' }}</strong></span>
      <span v-if="showRsdpProducts && favoriteStatusError" class="text-red-800">收藏状态加载失败 <button type="button" class="font-semibold underline" @click="loadFavoriteProductIds">重试</button></span>
      <span v-if="showRsdpProducts && projectFavoriteStatusError" class="text-red-800">项目收藏状态加载失败 <button type="button" class="font-semibold underline" @click="loadProjectFavoriteProductIds()">重试</button></span>
    </div>

    <p v-if="sharedUnavailable && !listError && !isLoading" role="status" class="text-sm text-amber-800">
      公共库暂时无法加载，当前显示本地商品。
    </p>
    <div class="catalog-split" :class="{ 'with-collections': showRsdpProducts }">
      <section class="catalog-main min-w-0" aria-label="商品列表">
        <form class="catalog-filters grid items-end gap-2.5 border-b-[3px] border-accent pb-3" aria-label="商品筛选" @submit.prevent>
          <label class="filter-search grid min-w-0 gap-1 text-xs font-medium text-neutral-700">
            搜索商品
            <span class="relative block">
              <AppIcon name="search" size="4" class="absolute left-3 top-3 text-muted" />
              <input v-model="search" type="search" maxlength="120" aria-label="搜索商品名称或编码" placeholder="商品名称、编码" class="w-full border border-neutral-300 bg-white py-2.5 pl-9 pr-2 text-sm" :disabled="!statsAvailable">
            </span>
          </label>
          <label class="grid min-w-0 gap-1 text-xs font-medium text-neutral-700">
            产品分类
            <select v-model="category" aria-label="筛选产品分类" class="min-w-0 border border-neutral-300 bg-white px-2 py-2.5 text-sm" @focus="loadFacets" @pointerenter="loadFacets" :disabled="!statsAvailable || !catalogTotal">
              <option value="">全部分类</option>
              <option v-for="value in categories" :key="value.value" :value="value.value">{{ value.label }}</option>
            </select>
          </label>
          <label class="grid min-w-0 gap-1 text-xs font-medium text-neutral-700">
            商品颜色
            <select v-model="color" aria-label="筛选商品颜色" class="min-w-0 border border-neutral-300 bg-white px-2 py-2.5 text-sm" @focus="loadFacets" @pointerenter="loadFacets" :disabled="!statsAvailable || !catalogTotal">
              <option value="">全部颜色</option>
              <option v-for="value in colors" :key="value" :value="value">{{ value }}</option>
            </select>
          </label>
          <label v-if="!localOnly" class="grid min-w-0 gap-1 text-xs font-medium text-neutral-700">
            在售状态
            <select v-model="status" aria-label="筛选商品状态" class="min-w-0 border border-neutral-300 bg-white px-2 py-2.5 text-sm" :disabled="!statsAvailable">
              <option value="">全部状态</option><option value="active">在售</option><option value="other">非在售</option>
            </select>
          </label>
          <div class="flex flex-wrap items-center gap-2">
            <button type="button" class="border border-neutral-300 p-2.5 text-neutral-600 hover:bg-neutral-100 disabled:opacity-50" aria-label="刷新商品列表" :disabled="isLoading" @click="refreshCatalog"><AppIcon name="reset" size="4" :class="{ 'animate-spin': isLoading }" /></button>
            <button v-if="filtersApplied" type="button" class="px-1 text-xs font-semibold text-accent-ink hover:underline" @click="resetFilters">清空条件</button>
          </div>
        </form>

        <p v-if="facetError" class="py-2 text-xs text-red-700" role="status">
          {{ facetError }} <button type="button" class="underline" @click="loadFacets">重试</button>
        </p>

        <div v-if="isLoading" class="py-3" role="status" aria-label="正在加载商品">
          <div class="sr-only">加载商品数据中…</div>
          <ProductCatalogGrid aria-hidden="true">
            <li v-for="card in 10" :key="card" class="min-w-0 animate-pulse border border-neutral-200">
              <div class="aspect-square bg-neutral-100" />
              <div class="space-y-2 p-3"><div class="h-4 w-2/3 bg-neutral-100" /><div class="h-3 w-1/2 bg-neutral-100" /></div>
            </li>
          </ProductCatalogGrid>
        </div>

        <div v-else-if="listError" class="grid min-h-[320px] place-items-center p-6" role="alert">
          <div class="w-full max-w-md text-center">
            <div class="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-xl bg-red-50 text-red-700"><AppIcon name="warning" size="6" /></div>
            <h2 class="mb-2 text-base font-bold">商品暂时无法加载</h2>
            <p class="mb-5 text-sm text-muted">请检查服务连接后重试，商品数量暂不可用。</p>
            <button type="button" class="inline-flex items-center gap-2 rounded-lg bg-bronze px-5 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark" @click="refreshCatalog"><AppIcon name="reset" size="4" />重新加载</button>
            <details class="mt-5 text-left text-xs text-muted"><summary class="cursor-pointer text-center">技术详情</summary><p class="mt-3 break-all rounded-lg bg-bg p-3">{{ listError }}</p></details>
          </div>
        </div>

        <div v-else-if="catalogTotal === 0" class="grid min-h-[320px] place-items-center p-6">
          <div class="text-center"><div class="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-xl bg-cream text-bronze"><AppIcon name="cube" size="6" /></div><h2 class="mb-2 text-base font-bold">商品库还是空的</h2><p class="text-sm text-muted">当前范围没有可读取的商品。</p></div>
        </div>

        <div v-else-if="resultTotal === 0" class="grid min-h-[280px] place-items-center p-6">
          <div class="text-center"><h2 class="mb-2 text-base font-bold">没有找到匹配的商品</h2><p class="mb-4 text-sm text-muted">试试其他关键词，或调整筛选条件。</p><button type="button" class="rounded-lg bg-cream px-4 py-2 text-sm font-semibold text-bronze-dark" @click="resetFilters">清空筛选</button></div>
        </div>

        <ProductCatalogGrid v-else>
          <template v-for="row in pageProducts" :key="row.local ? 'local:' + row.local.id : 'shared:' + row.shared.rspuId">
            <ProductCatalogTile v-if="row.local" :name="row.local.name" :code="row.local.factoryModel"
              :image-url="row.local.primaryImageId ? api.localProductImagePath(row.local.primaryImageId) : undefined"
              :to="{ path: `/products/local/${encodeURIComponent(row.local.id)}`, query: { from: 'catalog' } }">
              <template #metadata>
                <span class="truncate text-xs text-neutral-600">{{ localCategoryLabel(row.local.categoryCode) }}</span>
                <span class="mt-auto pt-1 text-xs font-medium text-accent-ink">本地商品</span>
              </template>
            </ProductCatalogTile>
            <ProductCatalogCard v-else :product="row.shared"
              :personal-favorite="favoriteProductIds.has(row.shared.rspuId)"
              :project-favorite="projectFavoriteProductIds.has(row.shared.rspuId)"
              :project-available="!!workspace.currentProjectId"
              :bundle-available="bundlesAvailable"
              @personal="togglePersonalFavorite(row.shared)" @project="openFavorites(row.shared, 'project')" @bundle="addToBundle(row.shared)"
            />
          </template>
        </ProductCatalogGrid>

        <div v-if="statsAvailable && resultTotal" class="border-t border-line bg-[#fcfbfa] px-4 py-4 sm:px-5" aria-label="商品分页">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <div class="text-xs text-muted" aria-live="polite">
              正在显示 <strong class="font-semibold text-ink">{{ pageStart }}–{{ pageEnd }}</strong> 条，共 <strong class="font-semibold text-ink">{{ resultTotal }}</strong> 件商品
            </div>

            <div class="flex min-w-0 flex-wrap items-center gap-3">
              <label class="flex items-center gap-2 text-xs text-muted">
                每页显示
                <select v-model.number="pageSize" aria-label="每页显示数量" class="rounded-lg border border-line bg-white px-2.5 py-2 text-xs font-medium text-ink outline-bronze">
                  <option v-for="size in pageSizeOptions" :key="size" :value="size">{{ size }} 条</option>
                </select>
              </label>

              <nav class="flex min-w-0 max-w-full items-center gap-1 overflow-x-auto" aria-label="商品列表页码">
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
      <aside v-if="showRsdpProducts" :id="collectionId" ref="collectionPanel" class="catalog-collections border border-neutral-200 bg-neutral-100 p-3.5"
        :class="{ 'is-open': collectionOpen }" aria-label="产品库收藏" tabindex="-1">
        <ProductBundlePanel ref="bundlePanel" v-model:available="bundlesAvailable" :products="sharedPageProducts" class="mb-4" />
        <ProductCatalogFavorites
          :section="collection"
          :project-name="workspace.currentProject?.projectName || ''"
          :products="collectionProducts.slice(0, 6)"
          :total="collectionProducts.length"
          :loading="collectionLoading"
          :error="collectionError"
          :catalog-unavailable="!!listError"
          @select="selectedCollection = $event"
          @retry="reloadCollection"
          @open="openFavorites($event, collection)"
        />
      </aside>
    </div>

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
        <div class="flex flex-wrap items-center justify-between gap-3 border-t border-line pt-4">
          <button type="button" class="rounded-lg border border-line px-5 py-2.5 text-sm disabled:opacity-50" :disabled="removingFavorite" @click="adjustFavoriteFolders">调整收藏夹</button>
          <div class="flex gap-3">
            <button type="button" class="rounded-lg border border-line px-5 py-2.5 text-sm disabled:opacity-50" :disabled="removingFavorite" @click="cancelRemoveFavorite">取消</button>
            <button type="button" class="rounded-lg bg-[#b3402f] px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-[#9c3627] disabled:opacity-50" :disabled="removingFavorite" @click="confirmRemoveFavorite">{{ removingFavorite ? '处理中…' : '确认取消收藏' }}</button>
          </div>
        </div>
      </div>
    </dialog>

    </template>
  </div>
</template>

<style scoped>
.catalog-split {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
  gap: 1rem;
}

.catalog-main { container: catalog / inline-size; }
.catalog-collections { display: none; min-width: 0; }
.catalog-collections.is-open { display: block; grid-row: 1; }
.catalog-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.filter-search { grid-column: 1 / -1; }

@media (min-width: 1100px) {
  .catalog-split.with-collections { grid-template-columns: minmax(0, 1fr) clamp(13rem, 17vw, 18rem); }
  .catalog-collections,
  .catalog-collections.is-open { display: block; grid-row: auto; }
  .collection-toggle { display: none; }
}

@container catalog (min-width: 760px) {
  .catalog-filters { grid-template-columns: minmax(12rem, 1.5fr) minmax(0, 1fr) minmax(0, .9fr) minmax(0, .75fr) auto; }
  .filter-search { grid-column: auto; }
}
</style>
