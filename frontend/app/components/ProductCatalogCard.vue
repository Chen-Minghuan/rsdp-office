<script setup lang="ts">
import type { ImportedProduct } from '~/types/imported-products'
import { catalogSourceLabel } from '~/utils/catalogSource'

const props = defineProps<{
  product: ImportedProduct
  managed: boolean
  personalFavorite: boolean
  projectFavorite: boolean
  projectAvailable: boolean
  bundleAvailable: boolean
}>()

const emit = defineEmits<{ personal: []; project: []; bundle: [] }>()
const name = computed(() => props.product.productName || '未命名商品')
const price = computed(() => {
  const amount = props.product.retailPrice
  return amount == null ? '价格待定' : `¥ ${amount.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
})
const projectActionTitle = computed(() => !props.projectAvailable
  ? '请先选择项目'
  : props.projectFavorite ? '已加入当前项目，点击调整' : '收藏到当前项目')

function dragProduct(event: DragEvent) {
  if (!props.bundleAvailable || props.product.status !== 'active' || !event.dataTransfer) return
  event.dataTransfer.setData('application/x-rsdp-product', props.product.rspuId)
  event.dataTransfer.effectAllowed = 'copy'
}
</script>

<template>
  <li class="catalog-card group relative flex min-w-0 flex-col border border-neutral-300 bg-white"
    :draggable="bundleAvailable && product.status === 'active'" @dragstart="dragProduct">
    <NuxtLink :to="`/products/${product.rspuId}`"
      class="flex h-full min-w-0 flex-col outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-accent"
      :aria-label="`查看${product.productName || '商品'}详情`">
      <div class="relative aspect-square border-b border-neutral-100 bg-white">
        <ProductImagePreview v-if="product.primaryImageUrl" :src="product.primaryImageUrl" alt="" size="small" />
        <div v-else class="grid h-full place-items-center px-3 text-center text-xs text-neutral-500">暂无商品图片</div>
      </div>
      <div class="flex flex-1 flex-col gap-1 px-2.5 py-2">
        <span class="line-clamp-2 wrap-break-word text-sm font-semibold leading-5 group-hover:text-accent-ink" :title="name">{{ name }}</span>
        <span class="truncate text-xs text-neutral-600">{{ product.rspuCode || product.externalCode || product.rspuId }}</span>
        <span v-if="product.sourceChannel || managed" class="truncate text-xs text-neutral-600">
          {{ catalogSourceLabel(product.sourceChannel, product.sourceFactoryName || product.ownerFactoryCode) }}
        </span>
        <div class="mt-auto flex flex-wrap items-baseline justify-between gap-x-1.5 gap-y-0.5 pt-1">
          <span class="text-xs" :class="product.status === 'active' ? 'text-green' : 'text-neutral-600'">{{ product.status === 'active' ? '在售' : '非在售' }}</span>
          <span class="wrap-break-word text-sm font-semibold text-accent-ink tabular-nums">{{ price }}</span>
        </div>
      </div>
    </NuxtLink>
    <button v-if="product.status === 'active'" type="button"
      class="border-t border-line px-2.5 py-1.5 text-left text-xs font-medium text-accent-ink enabled:hover:bg-accent-tint disabled:opacity-45"
      :aria-label="`加入组合 ${name}`" :disabled="!bundleAvailable" @click="emit('bundle')">加入组合</button>
    <button type="button" class="favorite-action absolute top-2 left-2 z-10 grid size-8 place-items-center border border-neutral-200 bg-white text-accent-ink disabled:opacity-40"
      :class="{ 'is-saved': projectFavorite }" :aria-label="`项目收藏${product.productName || '商品'}`"
      :aria-pressed="projectFavorite" :disabled="!projectAvailable" :title="projectActionTitle" @click="emit('project')">
      <AppIcon name="plus" size="5" />
    </button>
    <button type="button" class="favorite-action absolute top-2 right-2 z-10 grid size-8 place-items-center border border-neutral-200 bg-white text-accent-ink"
      :class="{ 'is-saved': personalFavorite }" :aria-label="`个人收藏${product.productName || '商品'}`"
      :aria-pressed="personalFavorite" :title="personalFavorite ? '已收藏，点击取消收藏' : '添加到个人收藏夹'" @click="emit('personal')">
      <AppIcon :name="personalFavorite ? 'heartSolid' : 'heart'" size="5" />
    </button>
  </li>
</template>

<style scoped>
.catalog-card:hover,
.catalog-card:focus-within { border-color: var(--color-accent); }

.favorite-action {
  border-radius: 999px !important;
  opacity: 0;
}

.favorite-action.is-saved {
  background: var(--color-accent-tint);
  border-color: var(--color-accent-line);
}

.favorite-action:hover:not(:disabled) { background: var(--color-accent-tint); }
.catalog-card:hover .favorite-action:not(:disabled),
.catalog-card:focus-within .favorite-action:not(:disabled),
.favorite-action:focus-visible { opacity: 1; }

@media (hover: none) {
  .favorite-action:not(:disabled) { opacity: 1; }
}
</style>
