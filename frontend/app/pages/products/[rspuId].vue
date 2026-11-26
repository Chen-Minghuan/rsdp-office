<script setup lang="ts">
import type { ProductDetail } from '~/types/imported-products'
import { catalogSourceLabel } from '~/utils/catalogSource'

const route = useRoute()
const api = useOfficeApi()
const rspuId = route.params.rspuId as string

const isLoading = ref(true)
const error = ref<string | null>(null)
const product = ref<ProductDetail | null>(null)

const galleryImages = computed(() => {
  const current = product.value
  if (!current) return []
  const images = current.images.filter(image => image.storageUrl).map(image => ({
    id: image.imageId,
    url: image.storageUrl,
    primary: image.primary,
  }))
  if (current.primaryImageUrl && !images.some(image => image.url === current.primaryImageUrl)) {
    images.unshift({ id: 'primary', url: current.primaryImageUrl, primary: true })
  }
  return images
})
const specifications = computed(() => (product.value?.variants ?? []).map(variant => ({
  id: variant.variantId,
  name: variant.displayName,
  dimensions: variant.sizeText?.trim() || variant.dimensionsText || variant.sizeCode,
  color: variant.colorText || variant.colorCode,
  material: variant.materialText || variant.materialCode,
  packageCount: variant.packageCount,
  packageVolumeCbm: variant.packageVolumeCbm,
  grossWeight: variant.grossWeight,
  status: variant.status,
})))

async function loadProduct() {
  isLoading.value = true
  error.value = null
  try {
    product.value = await api.getProduct(rspuId)
    if (!product.value) error.value = '商品不存在或已删除'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载商品失败'
  } finally {
    isLoading.value = false
  }
}

function formatPrice(price: number | null) {
  return price == null ? '—' : `¥ ${price.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function formatDate(date: string) {
  const ts = new Date(date)
  return Number.isNaN(ts.getTime()) ? '—' : ts.toLocaleDateString('zh-CN')
}

onMounted(loadProduct)
</script>

<template>
  <ProductsCatalogProductDetails :name="product ? product.productName || '未命名商品' : undefined" back-to="/products"
    :images="galleryImages" :loading="isLoading" :error="error" @retry="loadProduct">
    <template v-if="product" #badges>
      <span class="border border-accent-line bg-accent-tint px-2 py-1 text-xs font-semibold text-accent-ink">RSDP 商品</span>
      <span v-if="product.status" class="px-2 py-1 text-xs font-semibold"
        :class="product.status === 'active' ? 'bg-green/10 text-green' : 'bg-white text-muted'">
        {{ product.status === 'active' ? '在售' : '非在售' }}
      </span>
    </template>
    <template v-if="product?.retailPrice != null" #price>
      <span class="text-lg font-bold text-accent-ink tabular-nums sm:text-xl">{{ formatPrice(product.retailPrice) }}</span>
    </template>
    <template v-if="product" #facts>
      <div class="min-w-0">
        <dt class="text-xs text-muted">商品编码</dt>
        <dd class="mt-1 break-words text-sm font-semibold tabular-nums">{{ product.rspuCode || product.rspuId }}</dd>
      </div>
      <div class="min-w-0">
        <dt class="text-xs text-muted">外部编码</dt>
        <dd class="mt-1 break-words text-sm font-semibold tabular-nums">{{ product.externalCode || '—' }}</dd>
      </div>
      <div class="min-w-0">
        <dt class="text-xs text-muted">主色</dt>
        <dd class="mt-1 break-words text-sm font-medium">{{ product.colorPrimaryName || '—' }}</dd>
      </div>
      <div class="min-w-0">
        <dt class="text-xs text-muted">商品来源</dt>
        <dd class="mt-1 break-words text-sm font-medium">{{ catalogSourceLabel(product.sourceChannel, product.sourceFactoryName || product.ownerFactoryCode) }}</dd>
      </div>
    </template>
    <ProductsCatalogProductSpecifications :variants="specifications" />
    <template v-if="product" #more>
      <details class="border border-line bg-white">
        <summary class="cursor-pointer px-4 py-3 text-sm font-semibold sm:px-5">说明与记录</summary>
        <div class="border-t border-line p-4 sm:p-5">
          <h2 class="text-sm font-semibold">描述 / 配置说明</h2>
          <p class="mt-2 whitespace-pre-wrap break-words text-sm leading-6">{{ product.description || '暂无说明' }}</p>
          <dl class="mt-4 grid grid-cols-2 gap-4 border-t border-line pt-4">
            <div><dt class="text-xs text-muted">创建时间</dt><dd class="mt-1 text-sm tabular-nums">{{ formatDate(product.createdAt) }}</dd></div>
            <div><dt class="text-xs text-muted">更新时间</dt><dd class="mt-1 text-sm tabular-nums">{{ formatDate(product.updatedAt) }}</dd></div>
          </dl>
        </div>
      </details>
    </template>
  </ProductsCatalogProductDetails>
</template>
