<script setup lang="ts">
import type { LocalProductCatalogItem, LocalProductImage, LocalProductInput, LocalProductVariant, WorkbookImportProgress, WorkbookMappingSelection } from '~/types/local-products'
import { withApiBase } from '~/utils/apiBase'
import { createUuid } from '~/utils/uuid'
import { splitWorkbookProducts, workbookPreviewPageSize, workbookSize } from '~/utils/localProductImport'

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
const importProgress = ref<WorkbookImportProgress | null>(null)
const readingWorkbook = ref(false)
const dragActive = ref(false)
const categoryLabels = computed(() => new Map(categories.value.map(item => [item.dictCode, item.dictName])))
let lastSave: { body: string, id: string } | undefined
let listRequest = 0

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
})

watch(search, (_, _old, onCleanup) => {
  // Invalidate the previous query during the debounce as well.
  listRequest++
  loading.value = true
  const timeout = setTimeout(() => { void load(1) }, 180)
  onCleanup(() => clearTimeout(timeout))
})
onBeforeUnmount(() => { listRequest++ })

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
  lastSave = undefined
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
  lastSave = undefined
  error.value = ''
  notice.value = ''
  mode.value = 'excel'
}

function showList() {
  mode.value = 'list'
  form.value = null
  preview.value = null
  workbookName.value = ''
  workbookFile.value = null
  error.value = ''
  if (route.path === '/products/local' && route.query.edit) {
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
  lastSave = undefined
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
    preview.value = await api.previewLocalProductWorkbook(file, mapping)
    templatePage.value = 1
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
    if (body !== lastSave?.body) lastSave = { body, id: createUuid() }
    await api.saveLocalProducts(lastSave.id, inputs)
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
    for (const [index, batch] of batches.entries()) {
      importProgress.value = { number: index + 1, total: batches.length }
      const body = JSON.stringify({ previewId, batch })
      if (body !== lastSave?.body) lastSave = { body, id: createUuid() }
      const saved = await api.saveLocalProducts(lastSave.id, batch, previewId)
      savedCount += saved.length
      lastSave = undefined
      onSaved(batch)
    }
    notice.value = `已导入 ${savedCount} 件商品`
    return true
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : '导入失败'
    error.value = savedCount ? `已导入 ${savedCount} 件，剩余商品可继续确认。${message}` : message
    return false
  } finally {
    importProgress.value = null
    if (savedCount) await load(1)
    busy.value = false
  }
}

async function importTemplate() {
  const current = preview.value
  if (!current) return
  if (await saveWorkbook(current.products, saved => {
    const models = new Set(saved.map(product => product.factoryModel))
    current.products = current.products.filter(product => !models.has(product.factoryModel))
  })) showList()
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

        <template v-if="preview">
          <details v-if="preview.errors.length" class="border border-amber-200 bg-amber-50 px-4 py-2.5 text-sm text-amber-900">
            <summary class="font-medium">{{ preview.errors.length }} 条原表提示</summary>
            <ul class="mt-2 max-h-36 space-y-1 overflow-auto">
              <li v-for="(issue, index) in preview.errors" :key="index">
                {{ issue.sheet ? issue.sheet + ' · ' + issue.column + issue.row : '第 ' + issue.row + ' 行' }} · {{ issue.message }}
              </li>
            </ul>
          </details>
          <ProductsWorkbookFieldMapping v-if="preview.mappingSheets?.length" :sheets="preview.mappingSheets" :busy="busy"
            @apply="applyWorkbookMapping" @busy-change="busy = $event" />
          <ProductsSupplierWorkbookReview v-else-if="preview.supplier" :preview="preview.supplier" :categories="categories" :busy="busy"
            :preview-id="preview.previewId" :image-sizes="preview.imageSizes ?? {}" :file-bytes="workbookFile?.size ?? 0"
            :progress="importProgress" :save-batch="saveWorkbook" @busy-change="busy = $event" />
          <section v-else class="border border-line bg-white" aria-label="模板导入预览">
            <div class="flex flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-3 sm:px-5">
              <div><h2 class="text-base font-semibold">预览商品</h2>
                <p class="mt-0.5 text-sm text-muted">可导入 {{ preview.products.length }} 件</p></div>
              <ProductsWorkbookPreviewPager v-model="templatePage" :total="preview.products.length" />
            </div>
            <ul v-if="preview.products.length" class="divide-y divide-line">
              <li v-for="product in preview.products.slice((templatePage - 1) * workbookPreviewPageSize, templatePage * workbookPreviewPageSize)"
                :key="product.factoryModel" class="grid gap-1 px-4 py-3 text-sm sm:grid-cols-[1fr_1fr_auto] sm:px-5">
                <div class="min-w-0 break-words font-medium">{{ product.name }} <span class="font-normal text-muted">· {{ product.factoryModel }}</span></div>
                <span class="break-words text-muted">{{ categoryLabel(product.categoryCode) }}</span>
                <span class="text-muted">{{ product.variants.length }} 个规格 · {{ product.images.length }} 张图</span>
              </li>
            </ul>
            <p v-else class="px-5 py-6 text-sm text-muted">没有可导入的商品。可查看原表提示后更换文件。</p>
            <div class="sticky bottom-0 z-10 flex flex-wrap items-center justify-between gap-2 border-t border-line bg-white px-4 py-3">
              <p class="text-sm text-muted">Excel {{ workbookSize(workbookFile?.size ?? 0) }} · {{ preview.products.length }} 件待导入</p>
              <button type="button" :class="primary" :disabled="busy || !preview.products.length" @click="importTemplate">
                {{ importProgress ? `正在导入 ${importProgress.number}/${importProgress.total}` : '确认导入' }}
              </button>
            </div>
          </section>
        </template>
      </section>
    </div>
  </main>
</template>
