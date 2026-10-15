<script setup lang="ts">
const api = useOfficeApi()
const router = useRouter()

const isSubmitting = ref(false)
const submitError = ref<string | null>(null)

const imageFiles = ref<File[]>([])
const imagePreviewUrls = ref<string[]>([])
const isUploadingImages = ref(false)

const form = ref({
  productName: '',
  colorPrimaryName: '',
  description: '',
  retailPrice: null as number | null,
  externalCode: '',

  variantDisplayName: '',
  sizeCode: '',
  sizeText: '',
  widthMm: null as number | null,
  depthMm: null as number | null,
  heightMm: null as number | null,
  colorCode: '',
  colorText: '',
  materialCode: '',
  materialText: '',

  factoryPrice: null as number | null,
  moq: null as number | null,
  leadTimeDays: null as number | null,
})

function onImageSelect(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files || [])
  addImageFiles(files)
  ;(event.target as HTMLInputElement).value = ''
}

function addImageFiles(files: File[]) {
  for (const file of files) {
    if (!file.type.startsWith('image/')) continue
    if (file.size > 10 * 1024 * 1024) continue
    imageFiles.value.push(file)
    imagePreviewUrls.value.push(URL.createObjectURL(file))
  }
}

function removeImage(index: number) {
  URL.revokeObjectURL(imagePreviewUrls.value[index])
  imageFiles.value.splice(index, 1)
  imagePreviewUrls.value.splice(index, 1)
}

async function submitForm() {
  if (isSubmitting.value) return
  if (!form.value.productName?.trim()) {
    submitError.value = '请填写商品名称'
    return
  }
  isSubmitting.value = true
  submitError.value = null

  try {
    let imageUrls: string[] = []
    if (imageFiles.value.length) {
      isUploadingImages.value = true
      imageUrls = await api.uploadImages(imageFiles.value)
      isUploadingImages.value = false
    }

    const result = await api.createManualProduct({
      ...form.value,
      imageUrls,
    })
    router.push(`/products/${result.rspuId}`)
  } catch (e) {
    submitError.value = e instanceof Error ? e.message : '录入失败'
  } finally {
    isSubmitting.value = false
    isUploadingImages.value = false
  }
}

</script>

<template>
  <div class="flex h-full min-h-0 flex-col gap-5 overflow-y-auto">
    <div class="flex-none">
      <div class="mb-3 text-xs text-muted">
        <NuxtLink to="/products" class="hover:text-bronze-dark">商品资源 / 商品库</NuxtLink> / 手工录入
      </div>
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 class="mb-2 text-[25px] font-bold tracking-tight">手工录入商品</h1>
          <p class="text-xs text-muted">按表单创建单个商品及默认规格，录入后进入待审核状态。</p>
        </div>
        <NuxtLink to="/products" class="inline-flex items-center gap-2 rounded-lg border border-line bg-white px-4 py-2.5 text-sm hover:bg-cream">
          <AppIcon name="arrowLeft" size="4" />返回商品库
        </NuxtLink>
      </div>
    </div>

    <div v-if="submitError" class="flex-none rounded-xl border border-red-100 bg-red-50 p-4 text-sm text-red-800">
      {{ submitError }}
    </div>

    <form class="flex-none rounded-xl border border-line bg-white p-5 shadow-panel sm:p-6" @submit.prevent="submitForm">
      <h2 class="mb-4 text-base font-semibold">基础信息</h2>
      <div class="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <div class="flex flex-col gap-1.5">
          <label class="text-xs font-medium text-muted">商品名称 <span class="text-red-700">*</span></label>
          <input v-model="form.productName" type="text" required class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="如：人体工学办公椅">
        </div>
        <div class="flex flex-col gap-1.5">
          <label class="text-xs font-medium text-muted">主色</label>
          <input v-model="form.colorPrimaryName" type="text" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="如：深灰色">
        </div>
        <div class="flex flex-col gap-1.5">
          <label class="text-xs font-medium text-muted">外部编码</label>
          <input v-model="form.externalCode" type="text" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="如：CH-001">
        </div>
        <div class="flex flex-col gap-1.5">
          <label class="text-xs font-medium text-muted">零售参考价</label>
          <input v-model.number="form.retailPrice" type="number" min="0" step="0.01" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="如：1299">
        </div>
        <div class="flex flex-col gap-1.5 sm:col-span-2 lg:col-span-3">
          <label class="text-xs font-medium text-muted">描述/配置说明</label>
          <textarea v-model="form.description" rows="2" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="如：可调节扶手、透气网布…" />
        </div>
      </div>

      <h2 class="mb-4 text-base font-semibold">默认规格与报价</h2>
      <div class="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <div class="flex flex-col gap-1.5">
          <label class="text-xs font-medium text-muted">变体显示名称</label>
          <input v-model="form.variantDisplayName" type="text" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="如：标准版-网布">
        </div>
        <div class="flex flex-col gap-1.5">
          <label class="text-xs font-medium text-muted">尺寸码</label>
          <input v-model="form.sizeCode" type="text" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="如：M">
        </div>
        <div class="flex flex-col gap-1.5">
          <label class="text-xs font-medium text-muted">尺寸原文</label>
          <input v-model="form.sizeText" type="text" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="如：680×650×1100">
        </div>
        <div v-for="field in [{ key: 'widthMm', label: '宽度(mm)' }, { key: 'depthMm', label: '进深(mm)' }, { key: 'heightMm', label: '高度(mm)' }]" :key="field.key" class="flex flex-col gap-1.5">
          <label class="text-xs font-medium text-muted">{{ field.label }}</label>
          <input v-model.number="form[field.key as 'widthMm' | 'depthMm' | 'heightMm']" type="number" min="1" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="毫米">
        </div>
        <div class="flex flex-col gap-1.5">
          <label class="text-xs font-medium text-muted">颜色码</label>
          <input v-model="form.colorCode" type="text" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="如：BK">
        </div>
        <div class="flex flex-col gap-1.5">
          <label class="text-xs font-medium text-muted">颜色原文</label>
          <input v-model="form.colorText" type="text" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="如：黑色">
        </div>
        <div class="flex flex-col gap-1.5">
          <label class="text-xs font-medium text-muted">材质码</label>
          <input v-model="form.materialCode" type="text" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="如：MES">
        </div>
        <div class="flex flex-col gap-1.5">
          <label class="text-xs font-medium text-muted">材质原文</label>
          <input v-model="form.materialText" type="text" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="如：网布">
        </div>
        <div class="flex flex-col gap-1.5">
          <label class="text-xs font-medium text-muted">工厂价</label>
          <input v-model.number="form.factoryPrice" type="number" min="0" step="0.01" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="如：899">
        </div>
        <div class="flex flex-col gap-1.5">
          <label class="text-xs font-medium text-muted">MOQ</label>
          <input v-model.number="form.moq" type="number" min="1" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="如：10">
        </div>
        <div class="flex flex-col gap-1.5">
          <label class="text-xs font-medium text-muted">交期天数</label>
          <input v-model.number="form.leadTimeDays" type="number" min="0" class="rounded-lg border border-line px-3 py-2.5 text-sm outline-bronze" placeholder="如：15">
        </div>
      </div>

      <h2 class="mb-4 text-base font-semibold">商品图片</h2>
      <div class="mb-6">
        <div class="flex flex-wrap gap-3">
          <div v-for="(url, idx) in imagePreviewUrls" :key="idx" class="relative h-24 w-24 overflow-hidden rounded-lg border border-line bg-bg">
            <img :src="url" class="h-full w-full object-cover" alt="">
            <button type="button" class="absolute right-1 top-1 grid h-5 w-5 place-items-center rounded-full bg-white/90 text-red-700 shadow" @click="removeImage(idx)">
              <AppIcon name="close" size="3" />
            </button>
          </div>
          <label class="grid h-24 w-24 cursor-pointer place-items-center rounded-lg border border-dashed border-line bg-bg text-muted hover:bg-cream">
            <AppIcon name="plus" size="6" />
            <input type="file" accept="image/*" multiple class="hidden" @change="onImageSelect">
          </label>
        </div>
        <p class="mt-2 text-xs text-muted">支持 JPG/PNG/WebP，单张不超过 10MB，第一张将作为主图。</p>
      </div>

      <div class="flex justify-end gap-3 border-t border-line pt-5">
        <NuxtLink to="/products" class="rounded-lg border border-line px-5 py-2.5 text-sm hover:bg-cream">取消</NuxtLink>
        <button type="submit" class="inline-flex items-center gap-2 rounded-lg bg-bronze px-5 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark disabled:opacity-50" :disabled="isSubmitting">
          <AppIcon v-if="isSubmitting || isUploadingImages" name="reset" size="4" class="animate-spin" />
          {{ isSubmitting ? '保存中…' : '保存商品' }}
        </button>
      </div>
    </form>
  </div>
</template>
