<script setup lang="ts">
import type { ImportedProduct } from '~/types/imported-products'

const api = useOfficeApi()
const products = ref<ImportedProduct[]>([])
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
const selectedIds = ref<Set<string>>(new Set())
const isDeleting = ref(false)
const deleteDialog = ref<HTMLDialogElement | null>(null)
const selectedProduct = ref<ImportedProduct | null>(null)
const detailDialog = ref<HTMLDialogElement | null>(null)
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
const pageProductIds = computed(() => pageProducts.value.map(product => product.rspuId))
const allPageSelected = computed(() => pageProductIds.value.length > 0 && pageProductIds.value.every(id => selectedIds.value.has(id)))
const somePageSelected = computed(() => pageProductIds.value.some(id => selectedIds.value.has(id)) && !allPageSelected.value)
const allFilteredSelected = computed(() => filteredProducts.value.length > 0 && filteredProducts.value.every(product => selectedIds.value.has(product.rspuId)))
const selectedProducts = computed(() => products.value.filter(product => selectedIds.value.has(product.rspuId)))

watch([search, status, pageSize], () => {
  currentPage.value = 1
  selectedIds.value = new Set()
})
watch(pageCount, count => {
  currentPage.value = Math.min(currentPage.value, count)
  pageInput.value = currentPage.value
})
watch(currentPage, value => { pageInput.value = value })

async function loadProducts() {
  isLoading.value = true
  listError.value = null
  try {
    products.value = await api.loadImportedProducts()
  } catch (error) {
    listError.value = error instanceof Error ? error.message : '商品列表请求失败'
  } finally {
    isLoading.value = false
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

function toggleProduct(productId: string) {
  const next = new Set(selectedIds.value)
  if (next.has(productId)) next.delete(productId)
  else next.add(productId)
  selectedIds.value = next
  actionError.value = null
  actionMessage.value = null
}

function togglePageSelection() {
  const next = new Set(selectedIds.value)
  if (allPageSelected.value) pageProductIds.value.forEach(id => next.delete(id))
  else pageProductIds.value.forEach(id => next.add(id))
  selectedIds.value = next
  actionError.value = null
  actionMessage.value = null
}

function toggleFilteredSelection() {
  const next = new Set(selectedIds.value)
  if (allFilteredSelected.value) filteredProducts.value.forEach(product => next.delete(product.rspuId))
  else filteredProducts.value.forEach(product => next.add(product.rspuId))
  selectedIds.value = next
  actionError.value = null
  actionMessage.value = null
}

function clearSelection() {
  selectedIds.value = new Set()
  actionError.value = null
}

function openDeleteDialog() {
  if (!selectedIds.value.size || isDeleting.value) return
  deleteDialog.value?.showModal()
}

function closeDeleteDialog() {
  if (!isDeleting.value) deleteDialog.value?.close()
}

function goToPage(value = pageInput.value) {
  const target = Math.min(pageCount.value, Math.max(1, Math.trunc(Number(value) || 1)))
  currentPage.value = target
  pageInput.value = target
}

async function deleteSelectedProducts() {
  const ids = [...selectedIds.value]
  if (!ids.length || isDeleting.value) return
  isDeleting.value = true
  actionError.value = null
  actionMessage.value = null
  try {
    const deletedCount = await api.deleteProducts(ids)
    if (deletedCount !== ids.length) throw new Error(`请求删除 ${ids.length} 件商品，服务仅确认删除 ${deletedCount} 件，请刷新核对。`)
    deleteDialog.value?.close()
    selectedIds.value = new Set()
    await loadProducts()
    if (!listError.value) actionMessage.value = `已永久删除 ${deletedCount} 件商品。`
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : '批量删除失败'
  } finally {
    isDeleting.value = false
  }
}

async function showDetails(product: ImportedProduct) {
  selectedProduct.value = product
  await nextTick()
  detailDialog.value?.showModal()
}

onMounted(loadProducts)
</script>

<template>
  <div class="flex h-full min-h-0 flex-col overflow-y-auto">
    <div class="flex-none">
      <div class="mb-3 text-xs text-muted">商品资源 / 商品库</div>
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 class="mb-2 text-[25px] font-bold tracking-tight">商品库</h1>
          <p class="text-xs text-muted">维护办公商品资料，为空间设计与方案报价提供商品资源。</p>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <NuxtLink to="/products/audit" class="inline-flex items-center gap-2 rounded-lg border border-line bg-white px-4 py-2.5 text-sm font-semibold transition hover:bg-cream">
            <AppIcon name="checkCircle" size="4" />商品审核
          </NuxtLink>
          <NuxtLink to="/products/manual" class="inline-flex items-center gap-2 rounded-lg border border-line bg-white px-4 py-2.5 text-sm font-semibold transition hover:bg-cream">
            <AppIcon name="plus" size="4" />手工录入
          </NuxtLink>
          <NuxtLink to="/products/import" class="inline-flex items-center gap-2 rounded-lg bg-bronze px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-bronze-dark">
            <AppIcon name="import" size="4" />批量导入
          </NuxtLink>
        </div>
      </div>
    </div>

    <div class="flex flex-none flex-wrap items-center gap-x-6 gap-y-2 text-xs text-muted" aria-live="polite">
      <span>商品总数 <strong class="ml-1 text-sm text-ink">{{ statsAvailable ? products.length : '—' }}</strong></span>
      <span>在售商品 <strong class="ml-1 text-sm text-green">{{ statsAvailable ? activeCount : '—' }}</strong></span>
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
        <button type="button" class="rounded-lg bg-red-600 px-3 py-2.5 text-sm font-semibold text-white transition hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-45" :disabled="!selectedIds.size || isDeleting || !statsAvailable" @click="openDeleteDialog">批量删除{{ selectedIds.size ? `（${selectedIds.size}）` : '' }}</button>
        <button type="button" class="rounded-lg border border-line p-2.5 text-muted transition hover:bg-cream disabled:opacity-50" aria-label="刷新商品列表" :disabled="isLoading" @click="loadProducts"><AppIcon name="reset" size="4" :class="{ 'animate-spin': isLoading }" /></button>
      </div>

      <div v-if="selectedIds.size && statsAvailable" class="flex flex-wrap items-center gap-3 border-b border-line bg-red-50/60 px-4 py-3 text-sm">
        <strong class="text-red-800">已选择 {{ selectedIds.size }} 件商品</strong>
        <span class="text-xs text-muted">可跨页勾选</span>
        <button v-if="filteredProducts.length" type="button" class="font-medium text-bronze-dark hover:underline" @click="toggleFilteredSelection">{{ allFilteredSelected ? '取消选择筛选结果' : `选择全部筛选结果（${filteredProducts.length}）` }}</button>
        <button type="button" class="font-medium text-muted hover:text-ink hover:underline" @click="clearSelection">清空选择</button>
      </div>
      <p v-else-if="statsAvailable && products.length" class="border-b border-line px-4 py-2 text-xs text-muted">勾选商品后可批量永久删除；表头可选择当前页。</p>
      <div v-if="actionError" class="border-b border-red-100 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">{{ actionError }}</div>
      <div v-if="actionMessage" class="border-b border-green/20 bg-green/10 px-4 py-3 text-sm text-green" role="status">{{ actionMessage }}</div>

      <div v-if="isLoading" class="p-5" role="status" aria-label="正在加载商品">
        <div class="sr-only">加载商品数据中…</div>
        <div v-for="row in 5" :key="row" class="flex animate-pulse items-center gap-4 border-b border-line py-4 last:border-0" aria-hidden="true"><div class="h-11 w-11 rounded-lg bg-cream" /><div class="flex-1 space-y-2"><div class="h-3 w-2/5 rounded bg-cream" /><div class="h-2 w-1/4 rounded bg-bg" /></div><div class="h-3 w-16 rounded bg-cream" /></div>
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
        <div class="text-center"><div class="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-xl bg-cream text-bronze"><AppIcon name="cube" size="6" /></div><h2 class="mb-2 text-base font-bold">商品库还是空的</h2><p class="mb-5 text-sm text-muted">导入第一份商品档案，建立可供设计使用的商品资源。</p><NuxtLink to="/products/import" class="inline-flex items-center gap-2 rounded-lg bg-bronze px-5 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark"><AppIcon name="import" size="4" />导入商品</NuxtLink></div>
      </div>

      <div v-else-if="filteredProducts.length === 0" class="grid min-h-[280px] place-items-center p-6">
        <div class="text-center"><h2 class="mb-2 text-base font-bold">没有找到匹配的商品</h2><p class="mb-4 text-sm text-muted">试试其他关键词，或调整筛选条件。</p><button type="button" class="rounded-lg bg-cream px-4 py-2 text-sm font-semibold text-bronze-dark" @click="resetFilters">清空筛选</button></div>
      </div>

      <div v-else class="overflow-x-auto">
        <table class="w-full whitespace-nowrap text-left text-sm">
          <thead class="bg-bg text-xs text-muted"><tr><th class="w-12 px-5 py-3 font-medium"><input type="checkbox" :checked="allPageSelected" :indeterminate="somePageSelected" :disabled="isDeleting" aria-label="选择当前页商品" @change="togglePageSelection"></th><th class="px-2 py-3 font-medium">商品名称 / 编码</th><th class="px-4 py-3 text-right font-medium">零售价</th><th class="px-4 py-3 font-medium">状态</th><th class="px-4 py-3 font-medium">更新日期</th><th class="px-5 py-3 text-right font-medium">操作</th></tr></thead>
          <tbody>
            <tr v-for="product in pageProducts" :key="product.rspuId" class="border-t border-line transition hover:bg-bg/70">
              <td class="px-5 py-4"><input type="checkbox" :checked="selectedIds.has(product.rspuId)" :disabled="isDeleting" :aria-label="`选择${product.productName || '商品'}`" @change="toggleProduct(product.rspuId)"></td>
              <td class="px-2 py-3">
                <NuxtLink :to="`/products/${product.rspuId}`" class="group flex w-fit items-center gap-3 rounded-lg outline-none focus-visible:ring-2 focus-visible:ring-bronze focus-visible:ring-offset-2" :aria-label="`查看${product.productName || '商品'}详情`">
                  <div class="h-[72px] w-[72px] shrink-0 overflow-hidden rounded-lg border border-line bg-white transition group-hover:border-bronze"><ProductImagePreview :src="product.primaryImageUrl" alt="" size="small" /></div>
                  <div class="min-w-0 max-w-[250px]"><div class="truncate font-medium group-hover:text-bronze-dark group-hover:underline" :title="product.productName || undefined">{{ product.productName || '未命名商品' }}</div><div class="mt-1 text-xs text-muted">{{ product.rspuCode || product.externalCode || product.rspuId }}</div></div>
                </NuxtLink>
              </td>
              <td class="px-4 py-4 text-right font-medium tabular-nums">{{ formatPrice(product.retailPrice) }}</td>
              <td class="px-4 py-4"><span class="rounded-md px-2 py-1 text-xs" :class="product.status === 'active' ? 'bg-green/10 text-green' : 'bg-bg text-muted'">{{ product.status === 'active' ? '在售' : '非在售' }}</span></td>
              <td class="px-4 py-4 text-xs text-muted">{{ formatDate(product.updatedAt) }}</td>
              <td class="px-5 py-4 text-right">
                <NuxtLink :to="`/products/${product.rspuId}`" class="rounded-md px-2 py-1 text-xs font-semibold text-bronze-dark hover:bg-cream" :aria-label="`查看${product.productName || '商品'}详情`">查看详情</NuxtLink>
              </td>
            </tr>
          </tbody>
        </table>
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

    <dialog ref="deleteDialog" class="m-auto max-h-[85dvh] w-[min(520px,calc(100%_-_32px))] overflow-y-auto rounded-2xl border border-line bg-white p-6 text-ink shadow-panel backdrop:bg-black/40" aria-labelledby="batch-delete-title" @cancel="event => { if (isDeleting) event.preventDefault() }">
      <h2 id="batch-delete-title" class="text-lg font-bold">确认永久删除 {{ selectedIds.size }} 件商品？</h2>
      <p class="mt-2 text-sm leading-6 text-muted">商品主档及关联的规格、图片、报价等数据库记录会被删除，操作无法撤销。</p>
      <div class="mt-4 max-h-48 overflow-y-auto rounded-lg border border-line bg-bg px-4 py-2 text-sm">
        <div v-for="product in selectedProducts.slice(0, 8)" :key="product.rspuId" class="flex justify-between gap-3 border-b border-line py-2 last:border-0"><span class="min-w-0 truncate">{{ product.productName || '未命名商品' }}</span><span class="shrink-0 text-xs text-muted">{{ product.rspuCode || product.rspuId }}</span></div>
        <p v-if="selectedProducts.length > 8" class="py-2 text-xs text-muted">另有 {{ selectedProducts.length - 8 }} 件商品</p>
      </div>
      <p v-if="actionError" class="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-800" role="alert">{{ actionError }}</p>
      <div class="mt-6 flex justify-end gap-2">
        <button type="button" class="rounded-lg border border-line px-4 py-2 text-sm hover:bg-bg disabled:opacity-50" :disabled="isDeleting" @click="closeDeleteDialog">取消</button>
        <button type="button" class="rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white hover:bg-red-700 disabled:opacity-50" :disabled="isDeleting" @click="deleteSelectedProducts">{{ isDeleting ? '删除中…' : `永久删除 ${selectedIds.size} 件` }}</button>
      </div>
    </dialog>

    <dialog ref="detailDialog" class="m-auto max-h-[85dvh] w-[min(560px,calc(100%_-_32px))] overflow-y-auto rounded-2xl border border-line bg-white p-6 text-ink shadow-panel backdrop:bg-black/30" aria-labelledby="product-detail-title" @close="selectedProduct = null" @click="event => { if (event.target === event.currentTarget) detailDialog?.close() }">
      <template v-if="selectedProduct">
        <div class="mb-5 flex items-start justify-between gap-4"><div><div class="mb-1 text-xs text-muted">商品详情</div><h2 id="product-detail-title" class="text-lg font-bold">{{ selectedProduct.productName || '未命名商品' }}</h2></div><form method="dialog"><button type="submit" class="rounded-lg p-2 text-muted hover:bg-cream" aria-label="关闭商品详情"><AppIcon name="close" size="5" /></button></form></div>
        <dl class="grid grid-cols-[90px_minmax(0,1fr)] gap-x-3 gap-y-4 text-sm"><dt class="text-muted">商品编码</dt><dd class="break-all">{{ selectedProduct.rspuCode || selectedProduct.rspuId }}</dd><dt class="text-muted">外部编码</dt><dd class="break-all">{{ selectedProduct.externalCode || '—' }}</dd><dt class="text-muted">零售价</dt><dd class="font-semibold text-bronze-dark">{{ formatPrice(selectedProduct.retailPrice) }}</dd><dt class="text-muted">更新日期</dt><dd>{{ formatDate(selectedProduct.updatedAt) }}</dd></dl>
      </template>
    </dialog>
  </div>
</template>
