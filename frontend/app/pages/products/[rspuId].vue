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

onMounted(async () => {
  await loadProduct()
})
</script>

<template>
  <div class="mx-auto flex h-full min-h-0 w-full max-w-[1480px] flex-col overflow-y-auto">
    <div class="flex-none">
      <div class="mb-3 text-xs text-muted">
        <NuxtLink to="/products" class="hover:text-bronze-dark">商品资源 / 商品库</NuxtLink> / 商品详情
      </div>
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 class="mb-2 text-[25px] font-bold tracking-tight">{{ product?.productName || '商品详情' }}</h1>
          <p class="text-xs text-muted">查看商品资料与规格报价（商品数据由 RSDP 主项目统一维护）。</p>
        </div>
        <NuxtLink to="/products" class="inline-flex items-center gap-2 rounded-lg border border-line bg-white px-4 py-2.5 text-sm hover:bg-cream">
          <AppIcon name="arrowLeft" size="4" />返回商品库
        </NuxtLink>
      </div>
    </div>

    <div v-if="error" class="flex-none rounded-xl border border-red-100 bg-red-50 p-4 text-sm text-red-800">
      {{ error }}
    </div>

    <div v-if="isLoading" class="grid flex-1 place-items-center">
      <div class="flex items-center gap-2 text-muted">
        <AppIcon name="reset" size="5" class="animate-spin" />
        <span class="text-sm">加载商品中…</span>
      </div>
    </div>

    <div v-else-if="!product" class="grid flex-1 place-items-center">
      <div class="text-center">
        <h2 class="text-base font-bold">商品不存在或已删除</h2>
        <NuxtLink to="/products" class="mt-3 inline-block rounded-lg bg-bronze px-4 py-2 text-sm text-white">返回商品库</NuxtLink>
      </div>
    </div>

    <div v-else class="flex-none space-y-5">
      <ProductsCatalogProductDetails :name="product.productName || '未命名商品'" :code="product.rspuCode || product.rspuId" :images="galleryImages">
        <template #badges>
          <span class="rounded-md px-2.5 py-1 text-xs" :class="product.status === 'active' ? 'bg-green/10 text-green' : 'bg-bg text-muted'">{{ product.status === 'active' ? '在售' : '非在售' }}</span>
          <span class="text-xs text-muted">{{ catalogSourceLabel(product.sourceChannel, product.sourceFactoryName || product.ownerFactoryCode) }}</span>
        </template>
        <template #price>
          <span class="text-xl font-bold text-bronze-dark">{{ formatPrice(product.retailPrice) }}</span>
        </template>
        <template #facts>
          <div><dt class="mb-1 text-xs text-muted">主色</dt><dd class="text-sm text-ink">{{ product.colorPrimaryName || '—' }}</dd></div>
          <div><dt class="mb-1 text-xs text-muted">外部编码</dt><dd class="break-all text-sm text-ink">{{ product.externalCode || '—' }}</dd></div>
          <div><dt class="mb-1 text-xs text-muted">在售状态</dt><dd class="text-sm text-ink">{{ product.status === 'active' ? '在售' : product.status ? '非在售' : '—' }}</dd></div>
        </template>
        <template #description>
          <div class="mt-5 border-t border-line pt-4"><div class="mb-1 text-xs text-muted">描述 / 配置说明</div><p class="whitespace-pre-wrap text-sm leading-6 text-ink">{{ product.description || '暂无说明' }}</p></div>
        </template>
        <template #dates>
          <section class="grid gap-3 rounded-lg border border-line bg-[#fbfaf8] p-4 text-xs sm:grid-cols-2">
            <div><span class="text-muted">创建时间</span><div class="mt-1 font-medium text-ink">{{ formatDate(product.createdAt) }}</div></div>
            <div><span class="text-muted">更新时间</span><div class="mt-1 font-medium text-ink">{{ formatDate(product.updatedAt) }}</div></div>
          </section>
        </template>
      </ProductsCatalogProductDetails>
      <ProductsCatalogProductSpecifications :variants="specifications" show-logistics />
    </div>
  </div>
</template>
