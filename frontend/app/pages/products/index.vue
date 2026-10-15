<script setup lang="ts">
import type { ImportedProduct } from '~/types/imported-products'

const api = useOfficeApi()
const products = ref<ImportedProduct[]>([])
const isLoading = ref(true)
const listError = ref<string | null>(null)
const search = ref('')
const status = ref('')
const currentPage = ref(1)
const pageSize = ref(12)
const pageInput = ref(1)
const pageSizeOptions = [12, 24, 48, 96]
const selectedIds = ref<Set<string>>(new Set())
const isDeleting = ref(false)
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
const pageProductIds = computed(() => pageProducts.value.map(product => product.rspuId))
const allPageSelected = computed(() => pageProductIds.value.length > 0 && pageProductIds.value.every(id => selectedIds.value.has(id)))
const somePageSelected = computed(() => pageProductIds.value.some(id => selectedIds.value.has(id)) && !allPageSelected.value)

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
}

function togglePageSelection() {
  const next = new Set(selectedIds.value)
  if (allPageSelected.value) pageProductIds.value.forEach(id => next.delete(id))
  else pageProductIds.value.forEach(id => next.add(id))
  selectedIds.value = next
}

function goToPage() {
  const target = Math.min(pageCount.value, Math.max(1, Math.trunc(Number(pageInput.value) || 1)))
  currentPage.value = target
}

async function deleteSelectedProducts() {
  const ids = [...selectedIds.value]
  if (!ids.length || isDeleting.value) return
  if (!confirm(`确定永久删除选中的 ${ids.length} 件商品吗？商品及其规格、图片和报价数据会从数据库中删除，无法恢复。`)) return
  isDeleting.value = true
  listError.value = null
  try {
    await api.deleteProducts(ids)
    selectedIds.value = new Set()
    await loadProducts()
  } catch (error) {
    listError.value = error instanceof Error ? error.message : '批量删除失败'
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
  <div class="flex h-full min-h-0 flex-col gap-5 overflow-y-auto">
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
        <button v-if="selectedIds.size" type="button" class="rounded-lg bg-red-600 px-3 py-2.5 text-sm font-semibold text-white transition hover:bg-red-700 disabled:opacity-50" :disabled="isDeleting" @click="deleteSelectedProducts">{{ isDeleting ? '删除中…' : `批量删除（${selectedIds.size}）` }}</button>
        <button type="button" class="rounded-lg border border-line p-2.5 text-muted transition hover:bg-cream disabled:opacity-50" aria-label="刷新商品列表" :disabled="isLoading" @click="loadProducts"><AppIcon name="reset" size="4" :class="{ 'animate-spin': isLoading }" /></button>
      </div>

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
          <thead class="bg-bg text-xs text-muted"><tr><th class="w-12 px-5 py-3 font-medium"><input type="checkbox" :checked="allPageSelected" :indeterminate="somePageSelected" aria-label="选择当前页商品" @change="togglePageSelection"></th><th class="px-2 py-3 font-medium">商品名称 / 编码</th><th class="px-4 py-3 text-right font-medium">零售价</th><th class="px-4 py-3 font-medium">状态</th><th class="px-4 py-3 font-medium">更新日期</th><th class="px-5 py-3 text-right font-medium">操作</th></tr></thead>
          <tbody>
            <tr v-for="product in pageProducts" :key="product.rspuId" class="border-t border-line transition hover:bg-bg/70">
              <td class="px-5 py-4"><input type="checkbox" :checked="selectedIds.has(product.rspuId)" :aria-label="`选择${product.productName || '商品'}`" @change="toggleProduct(product.rspuId)"></td>
              <td class="px-2 py-4"><div class="flex items-center gap-3"><div class="grid h-11 w-11 shrink-0 place-items-center overflow-hidden rounded-lg bg-cream text-bronze"><img v-if="product.primaryImageUrl" :src="product.primaryImageUrl" :alt="product.productName || '商品图片'" class="h-full w-full object-cover" loading="lazy"><AppIcon v-else name="cube" size="5" /></div><div class="max-w-[250px]"><div class="truncate font-medium" :title="product.productName || undefined">{{ product.productName || '未命名商品' }}</div><div class="mt-1 text-xs text-muted">{{ product.rspuCode || product.externalCode || product.rspuId }}</div></div></div></td>
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

      <div v-if="statsAvailable && products.length" class="flex flex-wrap items-center justify-between gap-3 border-t border-line px-4 py-3 text-xs text-muted">
        <span aria-live="polite">共 {{ filteredProducts.length }} 件商品 · 每页 {{ pageSize }} 件</span>
        <div class="flex flex-wrap items-center justify-end gap-3"><label class="flex items-center gap-2">每页<select v-model.number="pageSize" class="rounded-md border border-line bg-white px-2 py-1.5 text-ink"><option v-for="size in pageSizeOptions" :key="size" :value="size">{{ size }}</option></select>件</label><button type="button" class="rounded-md border border-line px-3 py-2 text-ink disabled:opacity-40" :disabled="currentPage === 1" @click="currentPage--">上一页</button><label class="flex items-center gap-1">第<input v-model.number="pageInput" type="number" min="1" :max="pageCount" class="w-14 rounded-md border border-line px-2 py-1.5 text-center text-ink" aria-label="跳转到页码" @keyup.enter="goToPage" @blur="goToPage">页 / {{ pageCount }}</label><button type="button" class="rounded-md border border-line px-3 py-2 text-ink disabled:opacity-40" :disabled="currentPage === pageCount" @click="currentPage++">下一页</button></div>
      </div>
    </section>

    <dialog ref="detailDialog" class="m-auto max-h-[85dvh] w-[min(560px,calc(100%_-_32px))] overflow-y-auto rounded-2xl border border-line bg-white p-6 text-ink shadow-panel backdrop:bg-black/30" aria-labelledby="product-detail-title" @close="selectedProduct = null" @click="event => { if (event.target === event.currentTarget) detailDialog?.close() }">
      <template v-if="selectedProduct">
        <div class="mb-5 flex items-start justify-between gap-4"><div><div class="mb-1 text-xs text-muted">商品详情</div><h2 id="product-detail-title" class="text-lg font-bold">{{ selectedProduct.productName || '未命名商品' }}</h2></div><form method="dialog"><button type="submit" class="rounded-lg p-2 text-muted hover:bg-cream" aria-label="关闭商品详情"><AppIcon name="close" size="5" /></button></form></div>
        <dl class="grid grid-cols-[90px_minmax(0,1fr)] gap-x-3 gap-y-4 text-sm"><dt class="text-muted">商品编码</dt><dd class="break-all">{{ selectedProduct.rspuCode || selectedProduct.rspuId }}</dd><dt class="text-muted">外部编码</dt><dd class="break-all">{{ selectedProduct.externalCode || '—' }}</dd><dt class="text-muted">零售价</dt><dd class="font-semibold text-bronze-dark">{{ formatPrice(selectedProduct.retailPrice) }}</dd><dt class="text-muted">更新日期</dt><dd>{{ formatDate(selectedProduct.updatedAt) }}</dd></dl>
      </template>
    </dialog>
  </div>
</template>
