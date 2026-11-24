<script setup lang="ts">
import type { ImportedProduct } from '~/types/imported-products'

defineProps<{
  section: 'personal' | 'project'
  projectName: string
  products: ImportedProduct[]
  total: number
  loading: boolean
  error: boolean
  catalogUnavailable: boolean
}>()

const emit = defineEmits<{
  select: [section: 'personal' | 'project']
  retry: []
  open: [product: ImportedProduct]
}>()
</script>

<template>
  <div class="space-y-3">
    <div class="flex items-center justify-between gap-2">
      <h2 class="text-base font-semibold">收藏夹</h2>
      <NuxtLink :to="{ path: '/favorites', query: section === 'project' ? { tab: 'project' } : {} }"
        class="text-xs font-semibold text-accent-ink hover:underline">打开收藏中心</NuxtLink>
    </div>
    <div class="grid grid-cols-2 gap-1" role="group" aria-label="收藏范围">
      <button type="button" class="favorite-scope px-1.5 py-2 text-sm font-semibold" :aria-pressed="section === 'project'" @click="emit('select', 'project')">项目收藏</button>
      <button type="button" class="favorite-scope px-1.5 py-2 text-sm font-semibold" :aria-pressed="section === 'personal'" @click="emit('select', 'personal')">个人收藏</button>
    </div>
    <p v-if="section === 'project' && projectName" class="wrap-break-word text-sm font-medium text-neutral-700">{{ projectName }}</p>
    <p v-if="section === 'project' && !projectName" class="text-sm text-neutral-600">进入项目后可查看该项目的收藏。</p>
    <p v-else-if="loading" role="status" class="text-sm text-neutral-600">正在读取收藏…</p>
    <div v-else-if="error" class="flex flex-wrap items-center gap-2 text-sm text-red-800">
      <p role="alert">暂时无法读取收藏。</p>
      <button type="button" class="font-semibold underline" @click="emit('retry')">重试收藏夹</button>
    </div>
    <p v-else-if="catalogUnavailable" class="text-sm text-neutral-600">商品目录暂不可用。</p>
    <p v-else-if="!products.length" class="text-sm text-neutral-600">当前目录范围内暂无收藏商品。</p>
    <template v-else>
      <ul aria-label="当前目录收藏商品" class="grid gap-2">
        <li v-for="product in products" :key="product.rspuId" class="flex min-w-0 items-center gap-2 border border-neutral-200 bg-white p-2">
          <NuxtLink :to="`/products/${product.rspuId}`" class="flex min-w-0 flex-1 items-center gap-2 hover:text-accent-ink">
            <span class="relative block size-10 shrink-0 bg-white">
              <ProductImagePreview :src="product.primaryImageUrl" alt="" size="small" />
            </span>
            <span class="line-clamp-2 wrap-break-word text-xs font-semibold" :title="product.productName || undefined">{{ product.productName || '未命名商品' }}</span>
          </NuxtLink>
          <button type="button" class="shrink-0 p-1 text-xs font-semibold text-accent-ink hover:underline" :aria-label="`调整收藏${product.productName || '商品'}`" @click="emit('open', product)">调整</button>
        </li>
      </ul>
      <p v-if="total > products.length" class="text-xs text-neutral-600">已展示 {{ products.length }} / {{ total }} 件</p>
    </template>
  </div>
</template>

<style scoped>
.favorite-scope {
  border-radius: 0.55rem !important;
  color: #525252;
}
.favorite-scope:hover { background: #eae2f1; }
.favorite-scope[aria-pressed='true'] { background: var(--color-accent); color: white; }
</style>
