<script setup lang="ts">
import type { LocalProductCapacity, LocalProductCatalogItem, LocalProductImage, LocalProductInput, LocalProductVariant, WorkbookImportProgress, WorkbookMappingSelection } from '~/types/local-products'
import { withApiBase } from '~/utils/apiBase'
import { createUuid } from '~/utils/uuid'
import { splitWorkbookProducts, workbookPreviewPageSize, workbookSize } from '~/utils/localProductImport'
import { localReferencePrice } from '~/utils/localReferencePrice'

const api = useOfficeApi()
const route = useRoute()
const router = useRouter()
const rows = ref<LocalProductCatalogItem[]>([])
const canWrite = ref(false)
const loading = ref(true)
const busy = ref(false)
const page = ref(1)
const hasMore = ref(false)
const search = ref('')
const mode = ref<'list' | 'edit' | 'excel'>('list')
const error = ref('')
const listError = ref('')
const notice = ref('')
const form = ref<LocalProductInput | null>(null)
const categories = ref<{ dictCode: string, dictName: string }[]>([])
const preview = ref<Awaited<ReturnType<typeof api.previewLocalProductWorkbook>> | null>(null)
const workbookName = ref('')
const workbookFile = shallowRef<File | null>(null)
const templatePage = ref(1)
const templateSelection = ref(new Set<string>())
const supplierSelectedCount = ref(0)
const showWorkbookMapping = ref(false)
const skipUnmappedPrices = ref(false)
const importProgress = ref<WorkbookImportProgress | null>(null)
const readingWorkbook = ref(false)
const dragActive = ref(false)
const categoryLabels = computed(() => new Map(categories.value.map(item => [item.dictCode, item.dictName])))
const lastSave = shallowRef<{ body: string, id: string, workbook: boolean }>()
let listRequest = 0
let capacityRequest = 0
const capacity = ref<LocalProductCapacity>()
const capacityError = ref(false)
const capacityLoading = ref(false)
const selectedTemplateProducts = computed(() => (preview.value?.products ?? [])
  .filter(product => templateSelection.value.has(product.factoryModel)))
const selectedCount = computed(() => preview.value?.supplier ? supplierSelectedCount.value : selectedTemplateProducts.value.length)
const remainingCapacity = computed(() => capacity.value ? Math.max(0, capacity.value.limit - capacity.value.used) : null)
const projectedUsed = computed(() => (capacity.value?.used ?? 0) + selectedCount.value)
const capacityExcess = computed(() => remainingCapacity.value === null ? 0 : Math.max(0, selectedCount.value - remainingCapacity.value))
const capacityBlocksImport = computed(() => capacityExcess.value > 0 && !lastSave.value?.workbook)
const capacityRate = new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 2 })
const capacityNumber = new Intl.NumberFormat('zh-CN')

const maxWorkbookMb = 150
const maxWorkbookBytes = maxWorkbookMb * 1024 * 1024
const control = 'w-full rounded-md border border-line bg-white px-3 py-2 text-sm'
const secondary = 'rounded-lg border border-line bg-white px-4 py-2 text-sm font-semibold disabled:opacity-50'
const primary = 'rounded-lg bg-bronze-dark px-4 py-2 text-sm font-semibold text-white disabled:opacity-50'

onMounted(async () => {
  void api.listLocalProductCategories().then(data => { categories.value = data }).catch(() => {})
  await load()
  const id = route.query.edit
  if (typeof id === 'string' && id && !listError.value) await openProduct(id)
  else if (route.query.view === 'import' && canWrite.value) showExcel()
})

watch(search, (_, _old, onCleanup) => {
  // Invalidate the previous query during the debounce as well.
  listRequest++
  loading.value = true
  const timeout = setTimeout(() => { void load(1) }, 180)
  onCleanup(() => clearTimeout(timeout))
})
onBeforeUnmount(() => { listRequest++; capacityRequest++ })

async function refreshCapacity() {
  const request = ++capacityRequest
  capacityLoading.value = true
  capacityError.value = false
  try {
    const current = await api.getLocalProductCapacity()
    if (request === capacityRequest) capacity.value = current
  } catch {
    if (request === capacityRequest) capacityError.value = true
  } finally {
    if (request === capacityRequest) capacityLoading.value = false
  }
}

function capacityWidth(used: number, limit: number) {
  return Math.min(100, used / limit * 100) + '%'
}

async function load(next = page.value) {
  const request = ++listRequest
  loading.value = true
  listError.value = ''
  try {
    const data = await api.listLocalProducts(next, search.value.trim())
    if (request !== listRequest) return
    rows.value = data.products
    page.value = next
    hasMore.value = data.hasMore
    canWrite.value = data.canWrite
  } catch (cause) {
    if (request === listRequest) listError.value = cause instanceof Error ? cause.message : '本地商品加载失败'
  } finally {
    if (request === listRequest) loading.value = false
  }
}

function emptyVariant(): LocalProductVariant {
  return { name: '', widthMm: null, depthMm: null, heightMm: null }
}

function updateReferencePrice(variant: LocalProductVariant, event: Event) {
  const input = event.target as HTMLInputElement
  variant.referencePrice = input.value ? input.valueAsNumber : null
}

function categoryLabel(code: string) {
  const name = categoryLabels.value.get(code)
  return name ? `${name} · ${code}` : code
}

async function openProduct(id?: string) {
  if (!canWrite.value) {
    error.value = '当前账号没有商品编辑权限'
    return
  }
  error.value = ''
  notice.value = ''
  lastSave.value = undefined
  busy.value = true
  try {
    if (id) {
      const detail = await api.getLocalProduct(id)
      if (detail.canWrite !== true) {
        canWrite.value = false
        error.value = '当前账号没有商品编辑权限'
        return
      }
      form.value = { ...detail.product, variants: detail.variants, images: detail.images }
    } else {
      form.value = { name: '', categoryCode: '', factoryModel: '', variants: [emptyVariant()], images: [] }
    }
    mode.value = 'edit'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '商品读取失败'
  } finally {
    busy.value = false
  }
}

function showExcel() {
  preview.value = null
  workbookName.value = ''
  workbookFile.value = null
  lastSave.value = undefined
  error.value = ''
  notice.value = ''
  mode.value = 'excel'
  void refreshCapacity()
}

function showList() {
  mode.value = 'list'
  form.value = null
  preview.value = null
  workbookName.value = ''
  workbookFile.value = null
  error.value = ''
  if (route.path === '/products/local' && (route.query.edit || route.query.view === 'import')) {
    void router.replace({ path: route.path })
  }
}

async function addImages(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!form.value || !files.length) return
  const product = form.value
  if (product.images.length + files.length > 6) {
    error.value = '每个商品最多 6 张图片'
    return
  }
  busy.value = true
  error.value = ''
  try {
    for (const file of files) {
      if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 2 * 1024 * 1024) {
        throw new Error('单张图片须为 JPG、PNG 或 WebP，且不超过 2 MB')
      }
    }
    const images = await Promise.all(files.map(file => new Promise<LocalProductImage>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve({ mediaType: file.type, content: String(reader.result).split(',')[1] })
      reader.onerror = () => reject(new Error('图片读取失败'))
      reader.readAsDataURL(file)
    })))
    product.images.push(...images)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '图片读取失败'
  } finally {
    busy.value = false
  }
}

function imageSrc(image: LocalProductImage) {
  return image.id ? withApiBase(api.localProductImagePath(image.id)) : `data:${image.mediaType};base64,${image.content}`
}

async function readWorkbook(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) await selectWorkbook(file)
}

function dragLeave(event: DragEvent) {
  if (!(event.relatedTarget instanceof Node) || !(event.currentTarget as HTMLElement).contains(event.relatedTarget)) {
    dragActive.value = false
  }
}

async function dropWorkbook(event: DragEvent) {
  dragActive.value = false
  const files = event.dataTransfer?.files
  if (busy.value || !files?.length) return
  if (files.length > 1) {
    error.value = '请一次上传一份 Excel 文件'
    return
  }
  await selectWorkbook(files[0]!)
}

async function selectWorkbook(file: File) {
  if (busy.value) return
  workbookName.value = file.name
  workbookFile.value = null
  error.value = ''
  notice.value = ''
  preview.value = null
  lastSave.value = undefined
  if (!file.size || !file.name.toLowerCase().endsWith('.xlsx')) {
    error.value = '请选择有效的 .xlsx 文件'
    return
  }
  if (file.size > maxWorkbookBytes) {
    error.value = `Excel 文件不能超过 ${maxWorkbookMb} MB`
    return
  }
  workbookFile.value = file
  await previewWorkbook(file)
}

async function previewWorkbook(file: File, mapping?: WorkbookMappingSelection) {
  busy.value = true
  readingWorkbook.value = true
  error.value = ''
  try {
    const result = await api.previewLocalProductWorkbook(file, mapping)
    showWorkbookMapping.value = !result.supplier && Boolean(result.mappingSheets?.length)
    skipUnmappedPrices.value = false
    supplierSelectedCount.value = 0
    templateSelection.value = new Set(result.products.map(product => product.factoryModel))
    preview.value = result
    templatePage.value = 1
    void refreshCapacity()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Excel 预览失败'
  } finally {
    readingWorkbook.value = false
    busy.value = false
  }
}

async function applyWorkbookMapping(mapping: WorkbookMappingSelection) {
  if (workbookFile.value) await previewWorkbook(workbookFile.value, mapping)
}

async function save(products: LocalProductInput[]) {
  if (!products.length || busy.value || !canWrite.value) return false
  busy.value = true
  error.value = ''
  try {
    const inputs = products.map(({ factoryCode, ...product }) => product)
    const body = JSON.stringify(inputs)
    if (body !== lastSave.value?.body) lastSave.value = { body, id: createUuid(), workbook: false }
    await api.saveLocalProducts(lastSave.value.id, inputs)
    notice.value = '商品已保存'
    showList()
    await load(1)
    return true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败'
    return false
  } finally {
    busy.value = false
  }
}

async function saveWorkbook(products: LocalProductInput[], onSaved: (products: LocalProductInput[]) => void) {
  if (!preview.value || busy.value || !canWrite.value || !products.length) return false
  const { previewId, imageSizes } = preview.value
  busy.value = true
  error.value = ''
  notice.value = ''
  let savedCount = 0
  try {
    const batches = splitWorkbookProducts(products.map(({ factoryCode, ...product }) => product), imageSizes ?? {})
    const retrying = lastSave.value?.workbook && lastSave.value.body === JSON.stringify({ previewId, batch: batches[0] })
    await refreshCapacity()
    if (!retrying && remainingCapacity.value !== null && products.length > remainingCapacity.value) {
      throw new Error(`还可录入 ${remainingCapacity.value} 件商品，请减少本次选择。`)
    }
    for (const [index, batch] of batches.entries()) {
      importProgress.value = { number: index + 1, total: batches.length }
      const body = JSON.stringify({ previewId, batch })
      if (body !== lastSave.value?.body) lastSave.value = { body, id: createUuid(), workbook: true }
      const saved = await api.saveLocalProducts(lastSave.value.id, batch, previewId)
      savedCount += saved.length
      lastSave.value = undefined
      onSaved(batch)
    }
    notice.value = `已导入 ${savedCount} 件商品`
    return true
  } catch (cause) {
    if (cause instanceof Error && 'code' in cause && cause.code === 409) lastSave.value = undefined
    const message = cause instanceof Error ? cause.message : '导入失败'
    error.value = savedCount ? `已导入 ${savedCount} 件，剩余商品可继续确认。${message}` : message
    return false
  } finally {
    importProgress.value = null
    await Promise.all([refreshCapacity(), savedCount ? load(1) : undefined])
    busy.value = false
  }
}

async function importTemplate() {
  const current = preview.value
  if (!current) return
  if (await saveWorkbook(selectedTemplateProducts.value, saved => {
    const models = new Set(saved.map(product => product.factoryModel))
    current.products = current.products.filter(product => !models.has(product.factoryModel))
  }) && !current.products.length) showList()
}
</script>

<template>
  <main class="h-full overflow-auto">
    <div class="mx-auto space-y-4" :class="mode === 'excel' ? 'max-w-[86rem] px-3 py-3 sm:px-5' : 'max-w-6xl'">
      <div class="flex flex-wrap items-end justify-between gap-3">
        <div>
          <NuxtLink to="/products" class="text-xs text-muted hover:underline">商品库</NuxtLink>
          <h1 class="text-2xl font-bold">
            {{ mode === 'excel' ? 'Excel 商品导入' : '本地商品' }}
          </h1>
        </div>
        <div v-if="mode === 'list' && canWrite" class="flex gap-2">
          <button type="button" :class="secondary" :disabled="busy" @click="showExcel">Excel 导入</button>
          <button type="button" :class="primary" :disabled="busy" @click="openProduct()">新增商品</button>
        </div>
        <button v-if="mode !== 'list'" type="button" :class="secondary" :disabled="busy" @click="showList">返回列表</button>
      </div>
      <p v-if="notice" role="status" class="rounded-lg bg-green/10 px-4 py-2 text-sm text-green">{{ notice }}</p>
      <p v-if="error" role="alert" class="rounded-lg bg-red-50 px-4 py-2 text-sm text-red-700">{{ error }}</p>

      <section v-if="mode === 'list'" class="min-w-0" aria-label="本地商品列表">
        <form class="border-b-[3px] border-accent pb-3" role="search" @submit.prevent>
          <label class="grid max-w-md gap-1 text-xs font-medium text-neutral-700">
            搜索商品
            <span class="relative block">
              <AppIcon name="search" size="4" class="absolute left-3 top-3 text-muted" />
              <input v-model="search" type="search" maxlength="120" placeholder="商品名称、厂内型号"
                class="w-full border border-neutral-300 bg-white py-2.5 pl-9 pr-2 text-sm">
            </span>
          </label>
        </form>
        <p v-if="loading" role="status" class="p-5 text-sm text-muted">加载中…</p>
        <div v-else-if="listError" class="flex flex-wrap items-center gap-3 p-5 text-sm" role="alert">
          <span class="text-red-700">{{ listError }}</span>
          <button type="button" :class="secondary" @click="load(1)">重新加载</button>
        </div>
        <p v-else-if="!rows.length" class="p-5 text-sm text-muted">
          {{ search.trim() ? '没有找到匹配的本地商品' : '当前工作区暂无本地商品' }}
        </p>
        <ProductCatalogGrid v-else>
          <ProductCatalogTile v-for="row in rows" :key="row.id" :name="row.name" :code="row.factoryModel"
            :image-url="row.primaryImageId ? api.localProductImagePath(row.primaryImageId) : undefined"
            :to="{ path: `/products/local/${encodeURIComponent(row.id)}` }">
            <template #metadata>
              <span class="truncate text-xs text-neutral-600">{{ categoryLabel(row.categoryCode) }}</span>
              <span class="mt-auto pt-1 text-xs tabular-nums" :class="row.minReferencePrice == null ? 'text-muted' : 'font-semibold text-accent-ink'">
                {{ row.minReferencePrice == null ? '' : '参考价 ' }}{{ localReferencePrice(row.minReferencePrice, row.multipleVariants) }}
              </span>
            </template>
          </ProductCatalogTile>
        </ProductCatalogGrid>
        <div v-if="page > 1 || hasMore" class="flex items-center gap-3 border-t border-line p-3 text-sm">
          <button type="button" :class="secondary" :disabled="loading || page <= 1" @click="load(page - 1)">上一页</button>
          <span>第 {{ page }} 页</span>
          <button type="button" :class="secondary" :disabled="loading || !hasMore" @click="load(page + 1)">下一页</button>
        </div>
      </section>

      <form v-else-if="mode === 'edit' && form" class="space-y-4 rounded-xl border border-line bg-white p-5" @submit.prevent="save([form])">
        <h2 class="text-lg font-semibold">{{ form.id ? '编辑商品' : '新增商品' }}</h2>
        <fieldset class="space-y-5" :disabled="busy || !canWrite">
          <div class="grid gap-3 sm:grid-cols-2">
            <label class="space-y-1 text-sm"><span>商品名称</span><input v-model.trim="form.name" :class="control" required maxlength="200"></label>
            <ProductsCategoryField v-model="form.categoryCode" :categories="categories" required />
            <label class="space-y-1 text-sm"><span>厂内型号</span><input v-model.trim="form.factoryModel" :class="control" required maxlength="64"></label>
          </div>

          <section class="space-y-3">
            <div class="flex items-center justify-between gap-2">
              <h3 class="font-semibold">规格</h3>
              <button v-if="canWrite" type="button" :class="secondary" :disabled="form.variants.length >= 30" @click="form.variants.push(emptyVariant())">添加规格</button>
            </div>
            <div v-for="(variant, index) in form.variants" :key="variant.id ?? index" class="space-y-3 rounded-lg border border-line p-3">
              <div class="grid gap-2 sm:grid-cols-4">
                <label class="space-y-1 text-sm"><span>规格名称</span><input v-model.trim="variant.name" :class="control" maxlength="128" required></label>
                <label class="space-y-1 text-sm"><span>宽（mm）</span><input v-model.number="variant.widthMm" :class="control" type="number" min="0.01" max="100000" step="0.01" required></label>
                <label class="space-y-1 text-sm"><span>深（mm）</span><input v-model.number="variant.depthMm" :class="control" type="number" min="0.01" max="100000" step="0.01" required></label>
                <label class="space-y-1 text-sm"><span>高（mm）</span><input v-model.number="variant.heightMm" :class="control" type="number" min="0.01" max="100000" step="0.01"></label>
                <label class="space-y-1 text-sm"><span>尺寸原文</span><input v-model.trim="variant.sizeText" :class="control" maxlength="64"></label>
                <label class="space-y-1 text-sm"><span>颜色</span><input v-model.trim="variant.color" :class="control" maxlength="64"></label>
                <label class="space-y-1 text-sm"><span>材质</span><input v-model.trim="variant.material" :class="control" maxlength="128"></label>
                <label class="space-y-1 text-sm"><span>参考价（元）</span>
                  <input :value="variant.referencePrice ?? ''" :class="control" type="number" min="0.01" max="9999999999.99" step="0.01"
                    placeholder="待补" @input="updateReferencePrice(variant, $event)">
                </label>
                <button v-if="canWrite && form.variants.length > 1" type="button" class="justify-self-end self-end px-2 py-2 text-sm text-red-700 hover:underline" @click="form.variants.splice(index, 1)">移除此规格</button>
              </div>
            </div>
          </section>

          <section class="space-y-2">
            <h3 class="font-semibold">商品图片</h3>
            <div class="flex flex-wrap gap-3">
              <figure v-for="(image, index) in form.images" :key="image.id ?? index" class="w-28 space-y-1">
                <img :src="imageSrc(image)" :alt="`商品图片 ${index + 1}`" class="h-24 w-full rounded border border-line object-contain">
                <figcaption class="flex justify-between text-xs">
                  <span v-if="index === 0">主图</span>
                  <button v-else-if="canWrite" type="button" class="text-bronze-dark" @click="form.images.unshift(image); form.images.splice(index + 1, 1)">设为主图</button>
                  <button v-if="canWrite" type="button" class="text-red-700" @click="form.images.splice(index, 1)">移除</button>
                </figcaption>
              </figure>
            </div>
            <input v-if="canWrite && form.images.length < 6" type="file" aria-label="添加商品图片" multiple accept="image/jpeg,image/png,image/webp" class="text-sm" @change="addImages">
          </section>
          <button v-if="canWrite" type="submit" :class="primary">保存</button>
        </fieldset>
      </form>

      <section v-else-if="mode === 'excel'" class="min-w-0 space-y-4">
        <div class="min-w-0 border border-line bg-white p-4 sm:p-5">
          <input id="local-product-workbook" class="peer sr-only" type="file" accept=".xlsx" aria-label="选择 Excel 文件" :disabled="busy" @change="readWorkbook">
          <label v-if="!workbookName" for="local-product-workbook"
            class="grid min-h-40 cursor-pointer place-content-center justify-items-center gap-2 border border-dashed px-5 py-6 text-center transition-colors peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-disabled:cursor-not-allowed"
            :class="dragActive ? 'border-accent bg-accent-tint' : 'border-accent-line bg-bg hover:bg-accent-tint'"
            @dragenter.prevent="dragActive = !busy" @dragover.prevent @dragleave="dragLeave" @drop.prevent="dropWorkbook">
            <span class="grid size-10 place-items-center border border-accent-line bg-white text-accent-ink">
              <AppIcon name="upload" size="5.5" aria-hidden="true" />
            </span>
            <span class="text-sm font-semibold text-ink">选择 Excel 文件或拖到这里</span>
            <span class="text-xs text-muted">供应商原表或 Office 模板 · .xlsx · 最多 {{ maxWorkbookMb }} MB</span>
          </label>
          <div v-else class="flex min-w-0 flex-wrap items-center gap-3 border border-line bg-bg px-3 py-2.5 sm:flex-nowrap">
            <span class="grid size-10 shrink-0 place-items-center border border-accent-line bg-accent-tint text-accent-ink">
              <AppIcon name="table" size="5" aria-hidden="true" />
            </span>
            <div class="min-w-0 flex-1">
              <div class="break-words text-sm font-semibold" role="status">{{ workbookName }}</div>
              <p v-if="readingWorkbook" class="mt-1 text-xs text-accent-ink" role="status">正在解析文件…</p>
              <p v-else-if="workbookFile" class="mt-1 text-xs text-muted">{{ workbookSize(workbookFile.size) }}</p>
            </div>
            <button v-if="workbookFile && !preview && !busy" type="button" :class="secondary" @click="previewWorkbook(workbookFile)">重试预览</button>
            <label for="local-product-workbook" class="shrink-0 cursor-pointer border border-line bg-white px-3 py-2 text-sm font-medium text-ink hover:bg-accent-tint"
              :class="{ 'cursor-not-allowed opacity-50': busy }">更换文件</label>
          </div>
          <div class="mt-3 flex flex-wrap items-center justify-between gap-2">
            <p class="text-xs text-muted">Excel 最大 {{ maxWorkbookMb }} MB · 单张内嵌图片最多 2 MB · 导入时自动分批</p>
            <a :href="api.localProductTemplateUrl()" class="whitespace-nowrap text-sm font-medium text-accent-ink hover:underline">下载模板</a>
          </div>
        </div>

        <section class="space-y-3 border border-line bg-white px-4 py-3 sm:px-5" aria-label="本地商品录入占用率">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <h2 class="text-sm font-semibold">本地商品录入占用率</h2>
            <button type="button" class="text-xs font-semibold text-accent-ink disabled:opacity-50"
              :disabled="capacityLoading || busy" @click="refreshCapacity">刷新额度</button>
          </div>
          <div v-if="capacity" class="space-y-2">
            <div class="flex flex-wrap items-baseline gap-x-4 gap-y-1 text-xs text-muted" aria-live="polite">
              <span>已用 <strong class="text-sm text-ink">{{ capacityNumber.format(capacity.used) }} / {{ capacityNumber.format(capacity.limit) }} 件</strong>
                · {{ capacityRate.format(capacity.used / capacity.limit) }}</span>
              <span>剩余 {{ capacityNumber.format(remainingCapacity ?? 0) }} 件</span>
              <span v-if="selectedCount && !busy" :class="capacityExcess ? 'font-semibold text-amber-800' : 'font-semibold text-accent-ink'">
                本次已选 {{ selectedCount }} 件 · 导入后预计 {{ capacityRate.format(projectedUsed / capacity.limit) }}
              </span>
            </div>
            <div class="relative h-2 overflow-hidden rounded-full bg-bg" role="progressbar" aria-label="本地商品录入占用率"
              :aria-valuemin="0" :aria-valuemax="capacity.limit" :aria-valuenow="Math.min(capacity.used, capacity.limit)">
              <div v-if="selectedCount && !busy" class="absolute inset-y-0 left-0"
                :class="capacityExcess ? 'bg-amber-300' : 'bg-accent-line'" :style="{ width: capacityWidth(projectedUsed, capacity.limit) }" />
              <div class="absolute inset-y-0 left-0 bg-accent" :style="{ width: capacityWidth(capacity.used, capacity.limit) }" />
            </div>
            <p v-if="capacityExcess && !busy" class="text-xs text-amber-800">
              {{ lastSave?.workbook ? '上一批结果待确认，可以重试。' : `超出剩余名额 ${capacityExcess} 件，请减少本次选择。` }}
            </p>
          </div>
          <p v-if="capacityError" role="status" class="text-xs text-amber-800">暂时无法读取最新额度。</p>
          <p v-else-if="!capacity" role="status" class="text-xs text-muted">正在读取工作区额度…</p>
        </section>

        <template v-if="preview">
          <details v-if="preview.errors.length" class="border border-amber-200 bg-amber-50 px-4 py-2.5 text-sm text-amber-900">
            <summary class="font-medium">{{ preview.errors.length }} 条原表提示</summary>
            <ul class="mt-2 max-h-36 space-y-1 overflow-auto">
              <li v-for="(issue, index) in preview.errors" :key="index">
                {{ issue.sheet ? issue.sheet + ' · ' + issue.column + issue.row : '第 ' + issue.row + ' 行' }} · {{ issue.message }}
              </li>
            </ul>
          </details>
          <div v-if="preview.supplier && preview.mappingSheets?.length" class="flex flex-wrap items-center gap-x-4 gap-y-2 border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
            <span>原表有未确认用途的价格列，请选择展示价列或跳过这些价格。</span>
            <button type="button" class="font-semibold underline" :disabled="busy" @click="showWorkbookMapping = !showWorkbookMapping">
              {{ showWorkbookMapping ? '返回商品预览' : '选择参考价列' }}
            </button>
            <label v-if="!showWorkbookMapping" class="inline-flex items-center gap-2">
              <input v-model="skipUnmappedPrices" type="checkbox" :disabled="busy">跳过未确认的价格
            </label>
          </div>
          <ProductsWorkbookFieldMapping v-if="showWorkbookMapping && preview.mappingSheets?.length" :sheets="preview.mappingSheets" :busy="busy"
            @apply="applyWorkbookMapping" @busy-change="busy = $event" />
          <ProductsSupplierWorkbookReview v-else-if="preview.supplier" :key="preview.previewId ?? workbookName"
            :preview="preview.supplier" :categories="categories" :busy="busy"
            :preview-id="preview.previewId" :image-sizes="preview.imageSizes ?? {}" :file-bytes="workbookFile?.size ?? 0"
            :progress="importProgress" :capacity-blocked="capacityBlocksImport" :save-batch="saveWorkbook"
            :price-confirmation-required="Boolean(preview.mappingSheets?.length) && !skipUnmappedPrices"
            @selection-change="supplierSelectedCount = $event" @busy-change="busy = $event" />
          <section v-else class="border border-line bg-white" aria-label="模板导入预览">
            <div class="flex flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-3 sm:px-5">
              <div><h2 class="text-base font-semibold">预览商品</h2>
                <p class="mt-0.5 text-sm text-muted">可导入 {{ preview.products.length }} 件</p></div>
              <div class="flex flex-wrap items-center gap-3">
                <button v-if="preview.products.length" type="button" class="text-sm font-semibold text-accent-ink" :disabled="busy"
                  @click="templateSelection = new Set(selectedCount === preview.products.length ? [] : preview.products.map(product => product.factoryModel))">
                  {{ selectedCount === preview.products.length ? '清空选择' : '全选' }}
                </button>
                <ProductsWorkbookPreviewPager v-model="templatePage" :total="preview.products.length" />
              </div>
            </div>
            <ul v-if="preview.products.length" class="divide-y divide-line">
              <li v-for="product in preview.products.slice((templatePage - 1) * workbookPreviewPageSize, templatePage * workbookPreviewPageSize)"
                :key="product.factoryModel" class="grid gap-1 px-4 py-3 text-sm sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] sm:px-5">
                <label class="flex min-w-0 items-start gap-2 break-words font-medium">
                  <input v-model="templateSelection" type="checkbox" :value="product.factoryModel" :disabled="busy"
                    :aria-label="'选择 ' + product.factoryModel" class="mt-1 shrink-0 accent-accent">
                  <span class="min-w-0">{{ product.name }} <span class="font-normal text-muted">· {{ product.factoryModel }}</span></span>
                </label>
                <span class="break-words text-muted">{{ categoryLabel(product.categoryCode) }}</span>
                <div class="space-y-1 text-muted">
                  <span>{{ product.variants.length }} 个规格 · {{ product.images.length }} 张图</span>
                  <details v-if="product.variants.some(variant => variant.referencePrice != null)">
                    <summary class="cursor-pointer">规格参考价</summary>
                    <div v-for="(variant, index) in product.variants" :key="index" class="text-xs">
                      {{ variant.name }} · {{ localReferencePrice(variant.referencePrice) }}
                    </div>
                  </details>
                </div>
              </li>
            </ul>
            <p v-else class="px-5 py-6 text-sm text-muted">没有可导入的商品。可查看原表提示后更换文件。</p>
            <div class="sticky bottom-0 z-10 flex flex-wrap items-center justify-between gap-2 border-t border-line bg-white px-4 py-3">
              <p class="text-sm text-muted">Excel {{ workbookSize(workbookFile?.size ?? 0) }} · 已选 {{ selectedCount }} 件</p>
              <button type="button" :class="primary" :disabled="busy || !selectedCount || capacityBlocksImport" @click="importTemplate">
                {{ importProgress ? `正在导入 ${importProgress.number}/${importProgress.total}` : '确认导入' }}
              </button>
            </div>
          </section>
        </template>
      </section>
    </div>
  </main>
</template>
