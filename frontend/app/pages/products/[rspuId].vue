<script setup lang="ts">
import type { DictItem, ProductDetail, ProductVariant } from '~/types/imported-products'

const route = useRoute()
const router = useRouter()
const api = useOfficeApi()
const rspuId = route.params.rspuId as string

const isLoading = ref(true)
const isSaving = ref(false)
const isDeleting = ref(false)
const error = ref<string | null>(null)
const product = ref<ProductDetail | null>(null)
const isEditing = ref(false)

const categories = ref<DictItem[]>([])
const styles = ref<DictItem[]>([])
const scenes = ref<DictItem[]>([])
const materials = ref<DictItem[]>([])
const levels = ref<DictItem[]>([])

const imageFiles = ref<File[]>([])
const imagePreviewUrls = ref<string[]>([])

const form = ref<Partial<ProductDetail>>({})

async function loadDicts() {
  try {
    const [c, s, sc, m, l] = await Promise.all([
      api.loadDicts('category'),
      api.loadDicts('style'),
      api.loadDicts('scene'),
      api.loadDicts('material'),
      api.loadDicts('factory_level'),
    ])
    categories.value = c
    styles.value = s
    scenes.value = sc
    materials.value = m
    levels.value = l
  } catch (e) {
    console.warn('字典加载失败', e)
  }
}

async function loadProduct() {
  isLoading.value = true
  error.value = null
  try {
    product.value = await api.getProduct(rspuId)
    resetForm()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载商品失败'
  } finally {
    isLoading.value = false
  }
}

function resetForm() {
  if (!product.value) return
  form.value = { ...product.value }
  imageFiles.value = []
  imagePreviewUrls.value = []
  isEditing.value = false
}

function parseTags(json: string | null): Array<{ key: string; values: string[] }> {
  if (!json) return []
  try {
    const tags = JSON.parse(json)
    if (!tags || typeof tags !== 'object' || Array.isArray(tags)) return []
    return Object.entries(tags).map(([key, value]) => ({ key, values: (Array.isArray(value) ? value : [value]).map(String) }))
  } catch { return [] }
}

function formatPrice(price: number | null) {
  return price == null ? '—' : `¥ ${price.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function formatDate(date: string) {
  const ts = new Date(date)
  return Number.isNaN(ts.getTime()) ? '—' : ts.toLocaleDateString('zh-CN')
}

function onImageSelect(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files || [])
  for (const file of files) {
    if (!file.type.startsWith('image/') || file.size > 10 * 1024 * 1024) continue
    imageFiles.value.push(file)
    imagePreviewUrls.value.push(URL.createObjectURL(file))
  }
  ;(event.target as HTMLInputElement).value = ''
}

function removeNewImage(index: number) {
  URL.revokeObjectURL(imagePreviewUrls.value[index])
  imageFiles.value.splice(index, 1)
  imagePreviewUrls.value.splice(index, 1)
}

function removeExistingImage(index: number) {
  if (!form.value.images) return
  form.value.images.splice(index, 1)
}

async function saveProduct() {
  if (!product.value) return
  isSaving.value = true
  error.value = null
  try {
    let imageUrls = (form.value.images || []).map(img => img.storageUrl)
    if (imageFiles.value.length) {
      const uploaded = await api.uploadImages(imageFiles.value)
      imageUrls = imageUrls.concat(uploaded)
    }

    const request = {
      categoryCode: form.value.categoryCode || '',
      positioningLabel: form.value.positioningLabel || '',
      productName: form.value.productName || undefined,
      colorPrimaryName: form.value.colorPrimaryName || undefined,
      materialTags: parseTags(form.value.materialTags).flatMap(t => t.values),
      sceneTags: parseTags(form.value.sceneTags).flatMap(t => t.values),
      productLevel: form.value.productLevel || undefined,
      warrantyYears: form.value.warrantyYears ?? undefined,
      referencePriceBand: form.value.referencePriceBand || undefined,
      sixDimTags: form.value.sixDimTags || undefined,
      keySpecs: form.value.keySpecs || undefined,
      description: form.value.description || undefined,
      retailPrice: form.value.retailPrice ?? undefined,
      externalCode: form.value.externalCode || undefined,

      variantDisplayName: form.value.variants?.[0]?.displayName || undefined,
      sizeCode: form.value.variants?.[0]?.sizeCode || undefined,
      sizeText: form.value.variants?.[0]?.sizeText || undefined,
      colorCode: form.value.variants?.[0]?.colorCode || undefined,
      colorText: form.value.variants?.[0]?.colorText || undefined,
      materialCode: form.value.variants?.[0]?.materialCode || undefined,
      materialText: form.value.variants?.[0]?.materialText || undefined,
      variantReferencePriceBand: form.value.variants?.[0]?.referencePriceBand || undefined,
      variantProductLevel: form.value.variants?.[0]?.productLevel || undefined,

      factoryPrice: form.value.variants?.[0]?.factoryPrice ?? undefined,
      moq: form.value.variants?.[0]?.moq ?? undefined,
      leadTimeDays: form.value.variants?.[0]?.leadTimeDays ?? undefined,

      imageUrls,
    }

    await api.updateProduct(rspuId, request)
    await loadProduct()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    isSaving.value = false
  }
}

async function deleteProduct() {
  if (!confirm('确定要删除该商品吗？删除后可在详情页恢复。')) return
  isDeleting.value = true
  try {
    await api.deleteProduct(rspuId)
    await loadProduct()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '删除失败'
  } finally {
    isDeleting.value = false
  }
}

async function restoreProduct() {
  try {
    await api.restoreProduct(rspuId)
    await loadProduct()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '恢复失败'
  }
}

onMounted(async () => {
  await loadDicts()
  await loadProduct()
})
</script>

<template>
  <div class="flex h-full min-h-0 flex-col gap-5 overflow-y-auto">
    <div class="flex-none">
      <div class="mb-3 text-xs text-muted">
        <NuxtLink to="/products" class="hover:text-bronze-dark">商品资源 / 商品库</NuxtLink> / 商品详情
      </div>
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 class="mb-2 text-[25px] font-bold tracking-tight">{{ product?.productName || '商品详情' }}</h1>
          <p class="text-xs text-muted">查看、编辑商品资料与规格报价。</p>
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

    <div v-else class="flex-none rounded-xl border border-line bg-white p-5 shadow-panel sm:p-6">
      <div class="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div class="flex items-center gap-3">
          <div class="grid h-14 w-14 shrink-0 place-items-center overflow-hidden rounded-lg bg-cream text-bronze">
            <img v-if="product.primaryImageUrl" :src="product.primaryImageUrl" class="h-full w-full object-cover" alt="">
            <AppIcon v-else name="cube" size="7" />
          </div>
          <div>
            <div class="text-lg font-bold">{{ product.productName || '未命名商品' }}</div>
            <div class="text-xs text-muted">{{ product.rspuCode || product.rspuId }}</div>
          </div>
        </div>
        <div class="flex flex-wrap gap-2">
          <button v-if="product.deletedAt" type="button" class="rounded-lg bg-green/10 px-4 py-2 text-sm font-semibold text-green hover:bg-green/20" @click="restoreProduct">恢复商品</button>
          <button v-else type="button" class="rounded-lg bg-cream px-4 py-2 text-sm font-semibold text-bronze-dark hover:bg-[#ebe3d8]" :class="isEditing ? 'bg-bronze text-white hover:bg-bronze-dark' : ''" @click="isEditing ? saveProduct() : isEditing = true">
            {{ isEditing ? (isSaving ? '保存中…' : '保存') : '编辑' }}
          </button>
          <button v-if="!product.deletedAt" type="button" class="rounded-lg bg-red-50 px-4 py-2 text-sm font-semibold text-red-700 hover:bg-red-100 disabled:opacity-50" :disabled="isDeleting" @click="deleteProduct">删除</button>
          <button v-if="isEditing" type="button" class="rounded-lg border border-line px-4 py-2 text-sm hover:bg-cream" @click="resetForm">取消</button>
        </div>
      </div>

      <div class="grid gap-6 lg:grid-cols-[1fr_320px]">
        <div class="space-y-5">
          <section>
            <h2 class="mb-3 text-base font-semibold">基础信息</h2>
            <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
              <div v-for="field in [
                { label: '品类', key: 'categoryCode', type: 'select', options: categories },
                { label: '定位标签', key: 'positioningLabel', type: 'select', options: styles },
                { label: '产品等级', key: 'productLevel', type: 'select', options: levels },
                { label: '商品名称', key: 'productName', type: 'text' },
                { label: '主色', key: 'colorPrimaryName', type: 'text' },
                { label: '外部编码', key: 'externalCode', type: 'text' },
                { label: '零售参考价', key: 'retailPrice', type: 'number' },
                { label: '保修年限', key: 'warrantyYears', type: 'number' },
              ]" :key="field.key" class="flex flex-col gap-1.5">
                <label class="text-xs font-medium text-muted">{{ field.label }}</label>
                <select v-if="field.type === 'select' && field.options" v-model="form[field.key as keyof typeof form]" :disabled="!isEditing" class="rounded-lg border border-line bg-white px-3 py-2.5 text-sm outline-bronze disabled:bg-bg">
                  <option value="">—</option>
                  <option v-for="opt in field.options" :key="opt.dictCode" :value="opt.dictCode">{{ opt.dictName }}（{{ opt.dictCode }}）</option>
                </select>
                <input v-else v-model="form[field.key as keyof typeof form]" :type="field.type" :disabled="!isEditing" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze disabled:bg-bg">
              </div>
              <div class="flex flex-col gap-1.5 sm:col-span-2 lg:col-span-3">
                <label class="text-xs font-medium text-muted">描述/配置说明</label>
                <textarea v-model="form.description" :disabled="!isEditing" rows="2" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze disabled:bg-bg" />
              </div>
            </div>
          </section>

          <section>
            <h2 class="mb-3 text-base font-semibold">规格与报价</h2>
            <div class="overflow-x-auto rounded-lg border border-line">
              <table class="w-full text-left text-sm">
                <thead class="bg-bg text-xs text-muted">
                  <tr>
                    <th class="px-4 py-3 font-medium">变体名称</th>
                    <th class="px-4 py-3 font-medium">尺寸</th>
                    <th class="px-4 py-3 font-medium">颜色</th>
                    <th class="px-4 py-3 font-medium">材质</th>
                    <th class="px-4 py-3 text-right font-medium">工厂价</th>
                    <th class="px-4 py-3 text-right font-medium">MOQ</th>
                    <th class="px-4 py-3 text-right font-medium">交期</th>
                    <th class="px-4 py-3 font-medium">状态</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="variant in form.variants" :key="variant.variantId" class="border-t border-line">
                    <td class="px-4 py-3">{{ variant.displayName || '—' }}</td>
                    <td class="px-4 py-3 text-xs text-muted">{{ variant.sizeText || variant.sizeCode || '—' }}</td>
                    <td class="px-4 py-3 text-xs text-muted">{{ variant.colorText || variant.colorCode || '—' }}</td>
                    <td class="px-4 py-3 text-xs text-muted">{{ variant.materialText || variant.materialCode || '—' }}</td>
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

        <aside class="space-y-5">
          <section>
            <h2 class="mb-3 text-base font-semibold">商品图片</h2>
            <div class="flex flex-wrap gap-2">
              <div v-for="(img, idx) in form.images" :key="img.imageId" class="relative h-20 w-20 overflow-hidden rounded-lg border border-line bg-bg">
                <img :src="img.storageUrl" class="h-full w-full object-cover" alt="">
                <span v-if="img.primary" class="absolute left-1 top-1 rounded bg-bronze px-1 py-0.5 text-[10px] text-white">主图</span>
                <button v-if="isEditing" type="button" class="absolute right-1 top-1 grid h-5 w-5 place-items-center rounded-full bg-white/90 text-red-700 shadow" @click="removeExistingImage(idx)">
                  <AppIcon name="close" size="3" />
                </button>
              </div>
              <div v-for="(url, idx) in imagePreviewUrls" :key="`new-${idx}`" class="relative h-20 w-20 overflow-hidden rounded-lg border border-line bg-bg">
                <img :src="url" class="h-full w-full object-cover" alt="">
                <button type="button" class="absolute right-1 top-1 grid h-5 w-5 place-items-center rounded-full bg-white/90 text-red-700 shadow" @click="removeNewImage(idx)">
                  <AppIcon name="close" size="3" />
                </button>
              </div>
              <label v-if="isEditing" class="grid h-20 w-20 cursor-pointer place-items-center rounded-lg border border-dashed border-line bg-bg text-muted hover:bg-cream">
                <AppIcon name="plus" size="5" />
                <input type="file" accept="image/*" multiple class="hidden" @change="onImageSelect">
              </label>
            </div>
          </section>

          <section class="rounded-lg bg-bg p-4 text-xs text-muted">
            <div class="mb-2 flex justify-between"><span>商品编码</span><span class="text-ink">{{ product.rspuCode || product.rspuId }}</span></div>
            <div class="mb-2 flex justify-between"><span>状态</span><span class="text-ink">{{ product.status === 'active' ? '在售' : product.status }}</span></div>
            <div class="mb-2 flex justify-between"><span>审核状态</span><span class="text-ink">{{ product.auditStatus === 'pending' ? '待审核' : product.auditStatus === 'approved' ? '已通过' : product.auditStatus }}</span></div>
            <div class="mb-2 flex justify-between"><span>创建时间</span><span class="text-ink">{{ formatDate(product.createdAt) }}</span></div>
            <div class="flex justify-between"><span>更新时间</span><span class="text-ink">{{ formatDate(product.updatedAt) }}</span></div>
          </section>
        </aside>
      </div>
    </div>
  </div>
</template>
