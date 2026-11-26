<script setup lang="ts">
import type { LocalProductDetail } from '~/types/local-products'

const route = useRoute()
const api = useOfficeApi()
const detail = ref<LocalProductDetail>()
const categoryName = ref<string>()
const isLoading = ref(true)
const error = ref('')
const backTo = computed(() => route.query.from === 'catalog' ? '/products' : '/products/local')
const images = computed(() => (detail.value?.images ?? []).flatMap((image, index) => image.id
  ? [{ id: image.id, url: api.localProductImagePath(image.id), primary: index === 0 }]
  : []))
const specifications = computed(() => (detail.value?.variants ?? []).map((variant, index) => {
  const dimensions = [variant.widthMm, variant.depthMm, variant.heightMm].filter(value => value != null)
  return {
    id: variant.id ?? String(index),
    name: variant.name,
    dimensions: dimensions.length ? `${dimensions.join(' × ')} mm` : variant.sizeText?.trim(),
    color: variant.color,
    material: variant.material,
  }
}))

async function loadProduct() {
  isLoading.value = true
  error.value = ''
  detail.value = undefined
  categoryName.value = undefined
  try {
    const product = await api.getLocalProduct(String(route.params.productId))
    detail.value = product
    void api.listLocalProductCategories().then(categories => {
      categoryName.value = categories.find(item => item.dictCode === product.product.categoryCode)?.dictName
    }).catch(() => {})
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '本地商品加载失败'
  } finally {
    isLoading.value = false
  }
}

onMounted(loadProduct)
</script>

<template>
  <ProductsCatalogProductDetails :name="detail?.product.name" :back-to="backTo" :images="images"
    :loading="isLoading" :error="error" @retry="loadProduct">
    <template #badges>
      <span v-if="detail" class="border border-accent-line bg-accent-tint px-2 py-1 text-xs font-semibold text-accent-ink">本地商品</span>
    </template>
    <template #actions>
      <NuxtLink v-if="detail?.canWrite === true" :to="{ path: '/products/local', query: { edit: detail.product.id } }"
        class="inline-flex min-h-10 shrink-0 items-center gap-2 bg-accent-ink px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-accent">
        <AppIcon name="pencil" size="4" aria-hidden="true" />编辑商品
      </NuxtLink>
    </template>
    <template v-if="detail" #facts>
      <div class="min-w-0">
        <dt class="text-xs text-muted">厂内型号</dt>
        <dd class="mt-1 break-words text-sm font-semibold tabular-nums">{{ detail.product.factoryModel }}</dd>
      </div>
      <div class="min-w-0">
        <dt class="text-xs text-muted">产品分类</dt>
        <dd class="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 break-words text-sm font-semibold">
          <span>{{ categoryName || detail.product.categoryCode }}</span>
          <span v-if="categoryName" class="bg-bg px-1.5 py-0.5 text-xs font-medium text-muted">{{ detail.product.categoryCode }}</span>
        </dd>
      </div>
    </template>
    <ProductsCatalogProductSpecifications :variants="specifications" />
  </ProductsCatalogProductDetails>
</template>
