<script setup lang="ts">
import type { LocalProductImportDetail } from '~/types/local-products'
import { workbookSize } from '~/utils/localProductImport'

const api = useOfficeApi()
const page = ref(1)
const search = ref('')
const submittedSearch = ref('')
const listing = ref<Awaited<ReturnType<typeof api.listLocalProductImports>>>()
const loading = ref(true)
const error = ref('')
const selectedId = ref('')
const detail = ref<LocalProductImportDetail>()
const detailLoading = ref(false)
const detailError = ref('')
const showLegacy = ref(false)
let listRequest = 0
let detailRequest = 0

const dateFormatter = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })

async function load(next = page.value) {
  const request = ++listRequest
  loading.value = true
  error.value = ''
  selectedId.value = ''
  detailRequest++
  try {
    const result = await api.listLocalProductImports(next, submittedSearch.value)
    if (request !== listRequest) return
    listing.value = result
    page.value = next
  } catch (cause) {
    if (request === listRequest) error.value = cause instanceof Error ? cause.message : '导入记录暂时无法读取'
  } finally {
    if (request === listRequest) loading.value = false
  }
}

function applySearch() {
  submittedSearch.value = search.value.trim()
  void load(1)
}

async function viewImport(id: string) {
  const request = ++detailRequest
  if (selectedId.value === id && !detailError.value) {
    selectedId.value = ''
    return
  }

  selectedId.value = id
  detail.value = undefined
  detailError.value = ''
  detailLoading.value = true
  try {
    const result = await api.getLocalProductImport(id)
    if (request === detailRequest) detail.value = result
  } catch (cause) {
    if (request === detailRequest) detailError.value = cause instanceof Error ? cause.message : '导入商品暂时无法读取'
  } finally {
    if (request === detailRequest) detailLoading.value = false
  }
}

onMounted(() => { void load() })
onBeforeUnmount(() => { listRequest++; detailRequest++ })
</script>

<template>
  <div class="min-w-0 space-y-5 px-3 py-4 sm:px-5 sm:py-6">
    <header class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <NuxtLink to="/products" class="text-xs text-muted hover:text-accent-ink">商品资源 / 商品库</NuxtLink>
        <h1 class="mt-1 text-2xl font-bold tracking-tight">导入记录</h1>
        <p class="mt-1 text-sm text-muted">查看通过 Office Excel 导入并已保存的商品。</p>
      </div>
      <NuxtLink v-if="listing?.canWrite" :to="{ path: '/products/local', query: { view: 'import' } }"
        class="inline-flex items-center gap-2 bg-accent px-4 py-2.5 text-sm font-semibold text-white hover:bg-accent-ink">
        <AppIcon name="upload" size="4" />导入 Excel
      </NuxtLink>
    </header>

    <section class="min-w-0 border border-line bg-white" aria-label="Office 导入记录">
      <div class="flex flex-wrap items-center justify-between gap-3 border-b border-line p-3 sm:px-4">
        <form class="flex min-w-0 max-w-md grow gap-2" role="search" @submit.prevent="applySearch">
          <input v-model="search" type="search" maxlength="120" aria-label="搜索 Excel 文件名" placeholder="搜索 Excel 文件名"
            class="min-w-0 flex-1 border border-line px-3 py-2 text-sm">
          <button type="submit" class="border border-line px-3 py-2 text-sm font-semibold hover:bg-accent-tint">搜索</button>
        </form>
        <button type="button" class="inline-flex items-center gap-1.5 px-2 py-2 text-sm text-accent-ink disabled:opacity-50"
          :disabled="loading" @click="load()"><AppIcon name="reset" size="4" />刷新</button>
      </div>

      <p v-if="loading" role="status" class="p-6 text-sm text-muted">正在读取导入记录…</p>
      <div v-else-if="error" role="alert" class="flex flex-wrap items-center gap-3 p-6 text-sm text-red-700">
        <span>{{ error }}</span><button type="button" class="font-semibold underline" @click="load()">重试</button>
      </div>
      <div v-else-if="!listing?.records.length" class="grid min-h-56 place-content-center gap-2 px-5 py-7 text-center">
        <AppIcon name="table" size="6" class="mx-auto mb-2 text-muted" />
        <h2 class="text-base font-semibold">{{ submittedSearch ? '没有匹配的导入记录' : '暂无 Office 导入记录' }}</h2>
        <p v-if="!submittedSearch" class="text-sm text-muted">确认导入 Excel 后，已保存的商品会出现在这里。</p>
        <button v-else type="button" class="text-sm font-semibold text-accent-ink" @click="search = ''; applySearch()">清空搜索</button>
      </div>

      <ul v-else class="divide-y divide-line">
        <li v-for="record in listing.records" :key="record.id">
          <div class="grid min-w-0 grid-cols-[minmax(0,1fr)_auto] items-center gap-3 px-3 py-4 sm:px-4 lg:grid-cols-[minmax(0,1.5fr)_minmax(7rem,1fr)_minmax(8rem,1fr)_minmax(6rem,0.7fr)_auto]">
            <div class="flex min-w-0 items-center gap-3">
              <span class="grid size-9 shrink-0 place-items-center bg-accent-tint text-accent-ink"><AppIcon name="table" size="4" /></span>
              <div class="min-w-0">
                <p class="break-words text-sm font-semibold">{{ record.fileName }}</p>
                <span class="text-xs text-muted">{{ workbookSize(record.fileBytes) }}</span>
              </div>
            </div>
            <div class="col-span-2 row-start-2 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted lg:contents">
              <span class="break-words text-sm text-muted">{{ record.actorName }}</span>
              <time :datetime="record.createdAt" class="text-sm text-muted">{{ dateFormatter.format(new Date(record.createdAt)) }}</time>
              <span class="text-sm font-medium text-green">已入库 {{ record.productCount }} 件</span>
            </div>
            <button type="button" class="col-start-2 row-start-1 inline-flex items-center gap-1 border border-line px-2.5 py-2 text-xs font-semibold text-accent-ink hover:bg-accent-tint lg:col-start-auto lg:row-start-auto"
              :aria-expanded="selectedId === record.id" @click="viewImport(record.id)">
              {{ selectedId === record.id ? '收起' : '查看商品' }}
              <AppIcon name="chevronDown" size="3.5" :class="{ 'rotate-180': selectedId === record.id }" />
            </button>
          </div>
          <div v-if="selectedId === record.id" class="border-t border-line bg-bg px-3 py-4 sm:px-4">
            <p v-if="detailLoading" role="status" class="text-sm text-muted">正在读取商品…</p>
            <div v-else-if="detailError" role="alert" class="text-sm text-red-700">
              {{ detailError }} <button type="button" class="font-semibold underline" @click="viewImport(record.id)">重试</button>
            </div>
            <div v-else-if="detail" class="space-y-3">
              <p class="text-xs text-muted">{{ detail.record.productCount }} 件商品 · {{ detail.record.batchCount }} 个保存批次</p>
              <ul class="grid max-h-72 gap-2 overflow-y-auto sm:grid-cols-2 lg:grid-cols-3">
                <li v-for="product in detail.products" :key="product.id" class="min-w-0">
                  <NuxtLink :to="`/products/local/${encodeURIComponent(product.id)}`"
                    class="block break-words border border-line bg-white p-2.5 text-sm font-medium text-accent-ink hover:bg-accent-tint">{{ product.name }}</NuxtLink>
                </li>
              </ul>
            </div>
          </div>
        </li>
      </ul>

      <div v-if="page > 1 || listing?.hasMore" class="flex items-center justify-end gap-3 border-t border-line p-3 text-sm">
        <button type="button" class="border border-line px-3 py-2 disabled:opacity-40" :disabled="loading || page <= 1" @click="load(page - 1)">上一页</button>
        <span>第 {{ page }} 页</span>
        <button type="button" class="border border-line px-3 py-2 disabled:opacity-40" :disabled="loading || !listing?.hasMore" @click="load(page + 1)">下一页</button>
      </div>
    </section>

    <details class="border border-line bg-white" @toggle="showLegacy ||= ($event.target as HTMLDetailsElement).open">
      <summary class="cursor-pointer px-4 py-3 text-sm font-semibold text-muted">早期导入记录</summary>
      <ProductsLegacyImportRecords v-if="showLegacy" />
    </details>
  </div>
</template>
