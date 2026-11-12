<script setup lang="ts">
import type { ProductDetail } from '~/types/imported-products'
import { sameOriginAssetUrl } from '~/utils/apiBase'

const route = useRoute()
const api = useOfficeApi()
const rspuId = route.params.rspuId as string

const isLoading = ref(true)
const error = ref<string | null>(null)
const product = ref<ProductDetail | null>(null)

const form = ref<Partial<ProductDetail>>({})

const galleryImages = computed(() => {
  const images = (form.value.images || []).filter(image => image.storageUrl).map(image => ({
    id: image.imageId,
    url: image.storageUrl,
    primary: image.primary,
    label: image.primary ? '主图' : '商品图片',
  }))
  const primaryWasInAssets = (product.value?.images || []).some(image => image.storageUrl === product.value?.primaryImageUrl)
  if (product.value?.primaryImageUrl && !primaryWasInAssets && !images.some(image => image.url === product.value?.primaryImageUrl)) {
    images.unshift({ id: 'primary', url: product.value.primaryImageUrl, primary: true, label: '主图' })
  }
  return images
})
const selectedImageId = ref<string | null>(null)
const selectedImage = computed(() => galleryImages.value.find(image => image.id === selectedImageId.value) || galleryImages.value[0] || null)

watch(galleryImages, images => {
  if (!images.some(image => image.id === selectedImageId.value)) selectedImageId.value = images[0]?.id || null
})

async function loadProduct() {
  isLoading.value = true
  error.value = null
  try {
    product.value = await api.getProduct(rspuId)
    form.value = { ...product.value, images: [...product.value.images], variants: [...product.value.variants] }
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
      <section class="rounded-xl border border-line bg-white p-5 shadow-panel sm:p-6">
      <div class="mb-6 flex flex-wrap items-center justify-between gap-4 border-b border-line pb-5">
        <div class="flex flex-wrap items-center gap-3">
          <span class="text-sm font-semibold text-ink">商品概览</span>
          <span class="rounded-md bg-bg px-2.5 py-1 text-xs text-muted">{{ product.rspuCode || product.rspuId }}</span>
          <span class="rounded-md px-2.5 py-1 text-xs" :class="product.status === 'active' ? 'bg-green/10 text-green' : 'bg-bg text-muted'">{{ product.status === 'active' ? '在售' : '非在售' }}</span>
        </div>
      </div>

      <div class="grid gap-8 lg:grid-cols-[minmax(0,440px)_minmax(0,1fr)]">
        <section class="min-w-0" aria-label="商品图片">
          <div class="aspect-[4/3] overflow-hidden rounded-xl border border-line bg-white">
            <ProductImagePreview :src="selectedImage?.url" :alt="`${product.productName || '商品'}图片`" enable-lightbox />
          </div>
          <div v-if="galleryImages.length" class="mt-3 flex gap-3 overflow-x-auto pb-2" aria-label="选择商品图片">
            <button v-for="image in galleryImages" :key="image.id" type="button" class="relative grid h-20 w-20 shrink-0 place-items-center overflow-hidden rounded-lg border-2 bg-white transition" :class="selectedImage?.id === image.id ? 'border-bronze' : 'border-line hover:border-bronze/50'" :aria-label="`查看${image.label}`" :aria-pressed="selectedImage?.id === image.id" @click="selectedImageId = image.id">
              <img :src="sameOriginAssetUrl(image.url)" alt="" class="absolute inset-0 h-full w-full object-contain" loading="lazy">
              <span v-if="image.primary" class="absolute bottom-0 left-0 rounded-tr bg-bronze px-1.5 py-0.5 text-[10px] text-white">主图</span>
            </button>
          </div>
          <p v-if="galleryImages.length" class="mt-1 text-xs text-muted">共 {{ galleryImages.length }} 张图片</p>
        </section>

        <div class="min-w-0 max-w-[900px] space-y-5">
          <section>
            <div class="mb-4 flex items-end justify-between gap-3 border-b border-line pb-3">
              <div><div class="mb-1 text-xs text-muted">商品资料</div><h2 class="text-base font-semibold">基础信息</h2></div>
              <span class="text-xl font-bold text-bronze-dark">{{ formatPrice(product.retailPrice) }}</span>
            </div>
            <div>
              <dl class="grid gap-4 sm:grid-cols-2">
                <div><dt class="mb-1 text-xs text-muted">商品名称</dt><dd class="text-sm font-semibold text-ink">{{ product.productName || '—' }}</dd></div>
                <div><dt class="mb-1 text-xs text-muted">主色</dt><dd class="text-sm text-ink">{{ product.colorPrimaryName || '—' }}</dd></div>
                <div><dt class="mb-1 text-xs text-muted">外部编码</dt><dd class="break-all text-sm text-ink">{{ product.externalCode || '—' }}</dd></div>
                <div><dt class="mb-1 text-xs text-muted">审核状态</dt><dd class="text-sm text-ink">{{ product.auditStatus === 'pending' ? '待审核' : product.auditStatus === 'approved' ? '已通过' : product.auditStatus || '—' }}</dd></div>
              </dl>
              <div class="mt-5 border-t border-line pt-4"><div class="mb-1 text-xs text-muted">描述 / 配置说明</div><p class="whitespace-pre-wrap text-sm leading-6 text-ink">{{ product.description || '暂无说明' }}</p></div>
            </div>
          </section>

          <section class="grid gap-3 rounded-lg border border-line bg-[#fbfaf8] p-4 text-xs sm:grid-cols-2">
            <div><span class="text-muted">创建时间</span><div class="mt-1 font-medium text-ink">{{ formatDate(product.createdAt) }}</div></div>
            <div><span class="text-muted">更新时间</span><div class="mt-1 font-medium text-ink">{{ formatDate(product.updatedAt) }}</div></div>
          </section>
        </div>
      </div>
      </section>

      <section class="rounded-xl border border-line bg-white p-5 shadow-panel sm:p-6">
            <div class="mb-4 flex flex-wrap items-end justify-between gap-2"><div><div class="mb-1 text-xs text-muted">商品规格</div><h2 class="text-base font-semibold">规格与报价</h2></div><span class="text-xs text-muted">{{ form.variants?.length || 0 }} 个规格</span></div>
            <div class="overflow-x-auto rounded-lg border border-line">
              <table class="w-full min-w-[1050px] text-left text-sm">
                <thead class="bg-bg text-xs text-muted">
                  <tr>
                    <th class="px-4 py-3 font-medium">变体名称</th>
                    <th class="px-4 py-3 font-medium">尺寸</th>
                    <th class="px-4 py-3 font-medium">颜色</th>
                    <th class="px-4 py-3 font-medium">材质</th>
                    <th class="px-4 py-3 text-right font-medium">包数</th>
                    <th class="px-4 py-3 text-right font-medium">体积(CBM)</th>
                    <th class="px-4 py-3 text-right font-medium">重量(原表值)</th>
                    <th class="px-4 py-3 text-right font-medium">工厂价</th>
                    <th class="px-4 py-3 text-right font-medium">MOQ</th>
                    <th class="px-4 py-3 text-right font-medium">交期</th>
                    <th class="px-4 py-3 font-medium">状态</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!form.variants?.length"><td colspan="11" class="px-4 py-8 text-center text-sm text-muted">暂无规格信息</td></tr>
                  <tr v-for="variant in form.variants" :key="variant.variantId" class="border-t border-line">
                    <td class="px-4 py-3">{{ variant.displayName || '—' }}</td>
                    <td class="px-4 py-3 text-xs text-muted">{{ variant.sizeText || variant.sizeCode || '—' }}</td>
                    <td class="px-4 py-3 text-xs text-muted">{{ variant.colorText || variant.colorCode || '—' }}</td>
                    <td class="px-4 py-3 text-xs text-muted">{{ variant.materialText || variant.materialCode || '—' }}</td>
                    <td class="px-4 py-3 text-right tabular-nums">{{ variant.packageCount ?? '—' }}</td>
                    <td class="px-4 py-3 text-right tabular-nums">{{ variant.packageVolumeCbm ?? '—' }}</td>
                    <td class="px-4 py-3 text-right tabular-nums">{{ variant.grossWeight ?? '—' }}</td>
                    <td class="px-4 py-3 text-right tabular-nums">{{ formatPrice(variant.factoryPrice) }}</td>
                    <td class="px-4 py-3 text-right tabular-nums">{{ variant.moq ?? '—' }}</td>
                    <td class="px-4 py-3 text-right tabular-nums">{{ variant.leadTimeDays ?? '—' }} 天</td>
                    <td class="px-4 py-3 text-xs">
                      <span class="rounded px-1.5 py-0.5" :class="variant.status === 'active' ? 'bg-green/10 text-green' : 'bg-bg text-muted'">{{ variant.status === 'active' ? '有效' : variant.status }}</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
      </section>
    </div>
  </div>
</template>