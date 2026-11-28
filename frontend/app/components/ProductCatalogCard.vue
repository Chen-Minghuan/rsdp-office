<script setup lang="ts">
import type { ImportedProduct } from '~/types/imported-products'
import { catalogProductPath, localProductId } from '~/utils/catalogProductReference'
import { catalogSourceLabel } from '~/utils/catalogSource'
import { localReferencePrice } from '~/utils/localReferencePrice'

const props = defineProps<{
  product: ImportedProduct
  personalFavorite: boolean
  projectFavorite: boolean
  projectAvailable: boolean
  bundleAvailable: boolean
  localCategory?: string
}>()

const emit = defineEmits<{ personal: []; project: []; bundle: [] }>()
const isLocal = computed(() => !!localProductId(props.product.rspuId))
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
  <ProductCatalogTile :name="name" :code="product.rspuCode || product.externalCode || product.rspuId"
    :image-url="product.primaryImageUrl" :to="catalogProductPath(product.rspuId)"
    :data-testid="`product-card-${product.rspuId}`"
    :draggable="bundleAvailable && product.status === 'active'" @dragstart="dragProduct">
    <template #metadata>
      <template v-if="isLocal">
        <span v-if="localCategory" class="truncate text-xs text-neutral-600">{{ localCategory }}</span>
        <div class="mt-auto flex flex-wrap items-baseline justify-between gap-x-1.5 gap-y-0.5 pt-1">
          <span class="text-xs font-medium text-accent-ink">本地商品</span>
          <span class="text-xs tabular-nums" :class="product.minReferencePrice == null ? 'text-muted' : 'font-semibold text-accent-ink'">
            {{ product.minReferencePrice == null ? '' : '参考价 ' }}{{ localReferencePrice(product.minReferencePrice, product.multipleVariants) }}
          </span>
        </div>
      </template>
      <template v-else>
        <span v-if="product.sourceChannel" class="truncate text-xs text-neutral-600">
          {{ catalogSourceLabel(product.sourceChannel, product.sourceFactoryName || product.ownerFactoryCode) }}
        </span>
        <div class="mt-auto flex flex-wrap items-baseline justify-between gap-x-1.5 gap-y-0.5 pt-1">
          <span class="text-xs" :class="product.status === 'active' ? 'text-green' : 'text-neutral-600'">{{ product.status === 'active' ? '在售' : '非在售' }}</span>
          <span class="wrap-break-word text-sm font-semibold text-accent-ink tabular-nums">{{ price }}</span>
        </div>
      </template>
    </template>
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
  </ProductCatalogTile>
</template>

<style scoped>
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
