<script setup lang="ts">
import type { LocalProductImage, LocalProductInput, LocalProductSummary, LocalProductVariant } from '~/types/local-products'
import { createUuid } from '~/utils/uuid'

const api = useOfficeApi()
const rows = ref<LocalProductSummary[]>([])
const canWrite = ref(false)
const loading = ref(true)
const busy = ref(false)
const page = ref(1)
const hasMore = ref(false)
const mode = ref<'list' | 'edit' | 'excel'>('list')
const error = ref('')
const notice = ref('')
const form = ref<LocalProductInput | null>(null)
const categories = ref<{ dictCode: string, dictName: string }[]>([])
const preview = ref<Awaited<ReturnType<typeof api.previewLocalProductWorkbook>> | null>(null)
let lastSave: { body: string, id: string } | undefined

const maxWorkbookMb = 150
const maxWorkbookBytes = maxWorkbookMb * 1024 * 1024
const control = 'w-full rounded-md border border-line bg-white px-3 py-2 text-sm'
const secondary = 'rounded-lg border border-line bg-white px-4 py-2 text-sm font-semibold disabled:opacity-50'
const primary = 'rounded-lg bg-bronze-dark px-4 py-2 text-sm font-semibold text-white disabled:opacity-50'

onMounted(() => {
  void load()
  void api.listLocalProductCategories().then(data => { categories.value = data }).catch(() => {})
})

async function load(next = page.value) {
  loading.value = true
  try {
    const data = await api.listLocalProducts(next)
    rows.value = data.products
    page.value = next
    hasMore.value = data.hasMore
    canWrite.value = data.canWrite
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '本地商品加载失败'
  } finally {
    loading.value = false
  }
}

function emptyVariant(): LocalProductVariant {
  return { name: '', widthMm: null, depthMm: null, heightMm: null }
}

async function openProduct(id?: string) {
  error.value = ''
  notice.value = ''
  lastSave = undefined
  busy.value = true
  try {
    if (id) {
      const detail = await api.getLocalProduct(id)
      form.value = { ...detail.product, variants: detail.variants, images: detail.images }
    } else {
      form.value = { name: '', categoryCode: '', factoryCode: '', factoryModel: '', variants: [emptyVariant()], images: [] }
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
  lastSave = undefined
  error.value = ''
  notice.value = ''
  mode.value = 'excel'
}

function showList() {
  mode.value = 'list'
  form.value = null
  preview.value = null
  error.value = ''
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
  return image.id ? api.localProductImageUrl(image.id) : `data:${image.mediaType};base64,${image.content}`
}

async function readWorkbook(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  error.value = ''
  preview.value = null
  if (!file.size || !file.name.toLowerCase().endsWith('.xlsx')) {
    error.value = '请选择有效的 .xlsx 文件'
    return
  }
  if (file.size > maxWorkbookBytes) {
    error.value = `Excel 文件不能超过 ${maxWorkbookMb} MB`
    return
  }
  busy.value = true
  try {
    preview.value = await api.previewLocalProductWorkbook(file)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Excel 预览失败'
  } finally {
    busy.value = false
  }
}

async function save(products: LocalProductInput[]) {
  if (!products.length || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const body = JSON.stringify(products)
    if (body !== lastSave?.body) lastSave = { body, id: createUuid() }
    const saved = await api.saveLocalProducts(lastSave.id, products)
    notice.value = mode.value === 'excel' ? `已导入 ${saved.length} 件商品` : '商品已保存'
    showList()
    await load(1)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="h-full overflow-auto">
    <div class="mx-auto max-w-6xl space-y-5">
      <div class="flex flex-wrap items-end justify-between gap-3">
        <div>
          <NuxtLink to="/products" class="text-xs text-muted hover:underline">商品库</NuxtLink>
          <h1 class="text-2xl font-bold">本地商品</h1>
        </div>
        <div v-if="mode === 'list' && canWrite" class="flex gap-2">
          <button type="button" :class="secondary" :disabled="busy" @click="showExcel">Excel 导入</button>
          <button type="button" :class="primary" :disabled="busy" @click="openProduct()">新增商品</button>
        </div>
        <button v-if="mode !== 'list'" type="button" :class="secondary" :disabled="busy" @click="showList">返回列表</button>
      </div>
      <p v-if="notice" role="status" class="rounded-lg bg-green/10 px-4 py-2 text-sm text-green">{{ notice }}</p>
      <p v-if="error" role="alert" class="rounded-lg bg-red-50 px-4 py-2 text-sm text-red-700">{{ error }}</p>

      <section v-if="mode === 'list'" class="overflow-x-auto rounded-xl border border-line bg-white">
        <p v-if="loading" class="p-5 text-sm text-muted">加载中…</p>
        <p v-else-if="!rows.length && !error" class="p-5 text-sm text-muted">当前工作区暂无本地商品</p>
        <table v-else-if="rows.length" class="w-full min-w-[560px] text-left text-sm">
          <thead class="border-b border-line text-muted"><tr>
            <th class="p-3">名称</th><th class="p-3">分类</th><th class="p-3">供应厂</th><th class="p-3">厂内型号</th><th class="p-3">操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id" class="border-b border-line last:border-0">
              <td class="p-3 font-medium">{{ row.name }}</td><td class="p-3">{{ row.categoryCode }}</td>
              <td class="p-3">{{ row.factoryCode }}</td><td class="p-3">{{ row.factoryModel }}</td>
              <td class="p-3"><button type="button" class="text-bronze-dark hover:underline" :disabled="busy" @click="openProduct(row.id)">{{ canWrite ? '编辑' : '查看' }}</button></td>
            </tr>
          </tbody>
        </table>
        <div v-if="page > 1 || hasMore" class="flex items-center gap-3 border-t border-line p-3 text-sm">
          <button type="button" :class="secondary" :disabled="loading || page <= 1" @click="load(page - 1)">上一页</button>
          <span>第 {{ page }} 页</span>
          <button type="button" :class="secondary" :disabled="loading || !hasMore" @click="load(page + 1)">下一页</button>
        </div>
      </section>

      <form v-else-if="mode === 'edit' && form" class="space-y-4 rounded-xl border border-line bg-white p-5" @submit.prevent="save([form])">
        <h2 class="text-lg font-semibold">{{ form.id ? '商品详情' : '新增商品' }}</h2>
        <fieldset class="space-y-5" :disabled="busy || !canWrite">
          <div class="grid gap-3 sm:grid-cols-2">
            <label class="space-y-1 text-sm"><span>商品名称</span><input v-model.trim="form.name" :class="control" required maxlength="200"></label>
            <label class="space-y-1 text-sm"><span>分类编码</span><input v-model.trim="form.categoryCode" :class="control" list="local-product-categories" required maxlength="16"></label>
            <label class="space-y-1 text-sm"><span>供应厂编码</span><input v-model.trim="form.factoryCode" :class="control" required maxlength="16"></label>
            <label class="space-y-1 text-sm"><span>厂内型号</span><input v-model.trim="form.factoryModel" :class="control" required maxlength="64"></label>
          </div>
          <datalist id="local-product-categories">
            <option v-for="category in categories" :key="category.dictCode" :value="category.dictCode">{{ category.dictName }}</option>
          </datalist>

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

      <section v-else-if="mode === 'excel'" class="space-y-4 rounded-xl border border-line bg-white p-5">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <h2 class="text-lg font-semibold">Excel 导入</h2>
          <a :href="api.localProductTemplateUrl()" :class="secondary">下载模板</a>
        </div>
        <p class="text-sm text-muted">上传 .xlsx 文件，单文件最多 {{ maxWorkbookMb }} MB。相同供应厂和型号的行合为一件商品。</p>
        <p class="text-sm text-muted">单张内嵌图片最多 2 MB，每批图片合计最多 12 MB。</p>
        <input type="file" accept=".xlsx" aria-label="选择 Excel 文件" :disabled="busy" @change="readWorkbook">
        <p v-if="busy" class="text-sm text-muted">处理中…</p>
        <template v-if="preview">
          <div class="text-sm font-semibold">可导入 {{ preview.products.length }} 件，{{ preview.errors.length }} 行需要修正</div>
          <ul v-if="preview.errors.length" class="max-h-36 overflow-auto text-sm text-red-700">
            <li v-for="issue in preview.errors" :key="issue.row">第 {{ issue.row }} 行 · {{ issue.message }}</li>
          </ul>
          <ul v-if="preview.products.length" class="max-h-60 divide-y divide-line overflow-auto rounded border border-line">
            <li v-for="product in preview.products" :key="`${product.factoryCode}/${product.factoryModel}`" class="px-3 py-2 text-sm">
              {{ product.name }} · {{ product.factoryModel }} · {{ product.variants.length }} 个规格 · {{ product.images.length }} 张图
            </li>
          </ul>
          <button type="button" :class="primary" :disabled="busy || !preview.products.length" @click="save(preview.products)">确认导入</button>
        </template>
      </section>
    </div>
  </main>
</template>
