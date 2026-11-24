<script setup lang="ts">
import type { ImportedProduct } from '~/types/imported-products'

defineProps<{
  items: ImportedProduct[]
  loading: boolean
  error: string | null
  total: number
  searching: boolean
  emptyText: string
}>()

const emit = defineEmits<{
  open: [product: ImportedProduct]
  remove: [product: ImportedProduct]
  retry: []
}>()

function formatPrice(value: number | string | null | undefined) {
  if (value === null || value === undefined || value === '') return '价格待定'
  const amount = Number(value)
  if (!Number.isFinite(amount)) return '价格待定'
  return `¥${amount.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`
}
</script>

<template>
  <div v-if="loading" class="grid min-h-[240px] place-items-center rounded-xl border border-line bg-white text-sm text-muted" role="status">正在加载收藏商品…</div>

  <div v-else-if="error" class="grid min-h-[240px] place-items-center rounded-xl border border-line bg-white p-6" role="alert">
    <div class="text-center">
      <p class="mb-4 text-sm text-muted">{{ error }}</p>
      <button type="button" class="rounded-lg bg-bronze px-5 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark" @click="emit('retry')">重新加载</button>
    </div>
  </div>

  <div v-else-if="total === 0" class="grid min-h-[240px] place-items-center rounded-xl border border-dashed border-line bg-[#fdfcfa] p-6">
    <div class="text-center">
      <div class="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-xl bg-cream text-bronze"><AppIcon name="heart" size="6" /></div>
      <h2 class="mb-2 text-base font-bold">{{ emptyText }}</h2>
      <p class="mb-5 text-sm text-muted">去商品库把常用商品收藏进来，随时在这里查看。</p>
      <NuxtLink to="/products" class="inline-flex items-center rounded-lg bg-bronze px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-bronze-dark">去商品库挑选</NuxtLink>
    </div>
  </div>

  <div v-else-if="items.length === 0" class="grid min-h-[240px] place-items-center rounded-xl border border-line bg-white p-6">
    <div class="text-center"><h2 class="mb-2 text-base font-bold">没有找到匹配的商品</h2><p class="text-sm text-muted">试试其他关键词。</p></div>
  </div>

  <ul v-else aria-label="收藏商品列表" class="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
    <li v-for="product in items" :key="product.rspuId" class="group flex min-w-0 flex-col overflow-hidden rounded-xl border border-line bg-white transition duration-200 hover:-translate-y-0.5 hover:border-bronze/60 hover:shadow-panel">
      <div class="relative shrink-0">
        <NuxtLink :to="`/products/${product.rspuId}`" class="relative block aspect-[4/3] overflow-hidden border-b border-line bg-white outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-bronze" :aria-label="`查看${product.productName || '商品'}详情`">
          <ProductImagePreview :src="product.primaryImageUrl" alt="" size="small" />
          <span class="absolute left-3 top-3 rounded-md px-2 py-1 text-xs shadow-sm" :class="product.status === 'active' ? 'bg-green/10 text-green' : 'bg-bg text-muted'">{{ product.status === 'active' ? '在售' : '非在售' }}</span>
        </NuxtLink>
        <button type="button" class="absolute right-3 top-3 z-10 grid h-9 w-9 place-items-center rounded-full border border-bronze bg-bronze text-white shadow-sm transition hover:bg-bronze-dark focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-bronze focus-visible:ring-offset-2" :aria-label="`取消收藏${product.productName || '商品'}`" title="取消收藏" @click="emit('remove', product)">
          <AppIcon name="heartSolid" size="5" />
        </button>
      </div>
      <div class="flex flex-1 flex-col p-4">
        <NuxtLink :to="`/products/${product.rspuId}`" class="line-clamp-2 min-h-10 text-sm font-semibold leading-5 outline-none transition hover:text-bronze-dark focus-visible:rounded focus-visible:ring-2 focus-visible:ring-bronze" :title="product.productName || undefined">{{ product.productName || '未命名商品' }}</NuxtLink>
        <div class="mt-1 truncate text-xs text-muted">{{ product.rspuCode || product.externalCode || product.rspuId }}</div>
        <div class="mt-4 text-lg font-semibold tabular-nums text-bronze-dark">{{ formatPrice(product.retailPrice) }}</div>
        <div class="mt-auto flex pt-4">
          <NuxtLink :to="`/products/${product.rspuId}`" class="flex flex-1 items-center justify-center rounded-l-md border-t border-line bg-cream px-3 py-2 text-xs font-semibold text-bronze-dark outline-none transition hover:bg-bronze hover:text-white focus-visible:ring-2 focus-visible:ring-bronze focus-visible:ring-offset-2" :aria-label="`查看${product.productName || '商品'}详情`">查看详情</NuxtLink>
          <button type="button" class="flex flex-1 items-center justify-center rounded-r-md border-l border-t border-line bg-white px-3 py-2 text-xs font-semibold text-muted outline-none transition hover:bg-cream hover:text-bronze-dark focus-visible:ring-2 focus-visible:ring-bronze focus-visible:ring-offset-2" :aria-label="`调整收藏夹${product.productName || '商品'}`" title="调整收藏夹" @click="emit('open', product)">调整收藏夹</button>
        </div>
      </div>
    </li>
  </ul>
</template>
