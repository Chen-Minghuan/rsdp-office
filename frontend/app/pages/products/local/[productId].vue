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
  <div class="mx-auto flex h-full min-h-0 w-full max-w-[1480px] flex-col gap-4 overflow-y-auto">
    <header class="flex-none">
      <div class="mb-3 text-xs text-muted">
        <NuxtLink :to="backTo" class="hover:text-bronze-dark">商品资源 / 本地商品</NuxtLink> / 商品详情
      </div>
      <div class="flex flex-wrap items-start justify-between gap-4">
        <h1 class="text-[25px] font-bold tracking-tight">{{ detail?.product.name || '商品详情' }}</h1>
        <div class="flex flex-wrap gap-2">
          <NuxtLink v-if="detail?.canWrite === true" :to="{ path: '/products/local', query: { edit: detail.product.id } }"
            class="inline-flex items-center gap-2 rounded-lg border border-accent-line bg-accent-tint px-4 py-2.5 text-sm font-semibold text-accent-ink hover:border-accent">编辑商品</NuxtLink>
          <NuxtLink :to="backTo" class="inline-flex items-center gap-2 rounded-lg border border-line bg-white px-4 py-2.5 text-sm hover:bg-cream">
            <AppIcon name="arrowLeft" size="4" />返回商品库
          </NuxtLink>
        </div>
      </div>
    </header>

    <p v-if="isLoading" role="status" class="grid flex-1 place-items-center text-sm text-muted">加载商品中…</p>
    <div v-else-if="error" role="alert" class="flex flex-wrap items-center gap-3 rounded-xl border border-red-100 bg-red-50 p-4 text-sm text-red-800">
      <span>{{ error }}</span>
      <button type="button" class="font-semibold underline" @click="loadProduct">重试</button>
    </div>
    <div v-else-if="detail" class="flex-none space-y-5">
      <ProductsCatalogProductDetails :name="detail.product.name" :code="detail.product.factoryModel" :images="images">
        <template #badges>
          <span class="rounded-md bg-accent-tint px-2.5 py-1 text-xs text-accent-ink">本地商品</span>
        </template>
        <template #facts>
          <div>
            <dt class="mb-1 text-xs text-muted">产品分类</dt>
            <dd class="text-sm text-ink">{{ categoryName ? `${categoryName} · ${detail.product.categoryCode}` : detail.product.categoryCode }}</dd>
          </div>
          <div><dt class="mb-1 text-xs text-muted">厂内型号</dt><dd class="break-all text-sm text-ink">{{ detail.product.factoryModel }}</dd></div>
        </template>
      </ProductsCatalogProductDetails>
      <ProductsCatalogProductSpecifications :variants="specifications" />
    </div>
  </div>
</template>
