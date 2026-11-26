<script setup lang="ts">
import type { BundleDisplayItem, ProductBundle } from '~/types/productScheme'
import type { ImportedProduct } from '~/types/imported-products'
import { localProductId } from '~/utils/catalogProductReference'
import { catalogProductPath } from '~/utils/catalogProductReference'
import { createUuid } from '~/utils/uuid'

const props = defineProps<{ products: ImportedProduct[] }>()
const available = defineModel<boolean>('available', { default: false })

const api = useOfficeApi()
const bundles = ref<ProductBundle[]>([])
const panelError = ref('')
const notice = ref('')
const loading = ref(true)
const savingIds = ref<Set<string>>(new Set())
const expandedIds = ref<Set<string>>(new Set())
let bundlesRequest = 0

const panelWidth = ref(320)
const minPanelWidth = 280
const maxPanelWidth = 480
let resizeState: { startX: number, startWidth: number } | null = null

function startResize(event: PointerEvent) {
  resizeState = { startX: event.clientX, startWidth: panelWidth.value }
  window.addEventListener('pointermove', resizePanel)
  window.addEventListener('pointerup', stopResize, { once: true })
}

function resizePanel(event: PointerEvent) {
  if (!resizeState) return
  const next = resizeState.startWidth + (resizeState.startX - event.clientX)
  panelWidth.value = Math.min(maxPanelWidth, Math.max(minPanelWidth, next))
}

function stopResize() {
  resizeState = null
  window.removeEventListener('pointermove', resizePanel)
}

onBeforeUnmount(stopResize)

async function loadBundles() {
  // 只有最后发出的请求可以写入界面，防止迟到的旧列表覆盖新数据。
  const request = ++bundlesRequest
  try {
    const result = await api.loadProductBundles()
    if (request !== bundlesRequest) return
    bundles.value = result
    available.value = true
    panelError.value = ''
  } catch (error) {
    if (request !== bundlesRequest) return
    available.value = false
    panelError.value = error instanceof Error ? error.message : '组合加载失败'
  } finally {
    if (request === bundlesRequest) loading.value = false
  }
}

onMounted(loadBundles)

function isExpanded(bundleId: string) {
  return expandedIds.value.has(bundleId)
}

function toggleExpanded(bundleId: string) {
  const next = new Set(expandedIds.value)
  if (next.has(bundleId)) next.delete(bundleId)
  else next.add(bundleId)
  expandedIds.value = next
}

function isSaving(bundleId: string) {
  return savingIds.value.has(bundleId)
}

function replaceBundle(result: ProductBundle) {
  const exists = bundles.value.some(row => row.bundleId === result.bundleId)
  bundles.value = exists
    ? bundles.value.map(row => row.bundleId === result.bundleId ? result : row)
    : [...bundles.value, result]
}

// 保存成功一律用服务端返回的最新 revision 更新本地，不再整表回拉，避免慢回拉覆盖新名称/新组合。
async function persist(bundle: ProductBundle, name: string, items: BundleDisplayItem[]) {
  if (isSaving(bundle.bundleId)) return null
  savingIds.value = new Set(savingIds.value).add(bundle.bundleId)
  panelError.value = ''
  try {
    return await api.saveProductBundle(bundle.bundleId, {
      name,
      revision: bundle.revision,
      items: items.map(({ rspuId, variantId, quantity }) => ({ rspuId, variantId, quantity })),
    })
  } catch (error) {
    panelError.value = error instanceof Error ? error.message : '保存组合失败'
    return null
  } finally {
    const next = new Set(savingIds.value)
    next.delete(bundle.bundleId)
    savingIds.value = next
  }
}

// 命名弹窗：创建与重命名共用
const nameDialog = ref<HTMLDialogElement | null>(null)
const nameInput = ref('')
const nameError = ref('')
const nameSaving = ref(false)
const editingBundle = ref<ProductBundle | null>(null)
const pendingProduct = ref<ImportedProduct | null>(null)

function openNameDialog(bundle: ProductBundle | null) {
  editingBundle.value = bundle
  nameInput.value = bundle?.name ?? ''
  nameError.value = ''
  nameDialog.value?.showModal()
}

async function confirmName() {
  if (nameSaving.value) return
  nameSaving.value = true
  nameError.value = ''
  try {
    if (editingBundle.value) {
      const result = await persist(editingBundle.value, nameInput.value.trim(), editingBundle.value.items)
      if (result) {
        replaceBundle(result)
        nameDialog.value?.close()
      } else {
        nameError.value = panelError.value
      }
    } else {
      const created = await persist(
        { bundleId: createUuid(), name: '', revision: 0, items: [] }, nameInput.value.trim(), [])
      if (created) {
        replaceBundle(created)
        nameDialog.value?.close()
        if (pendingProduct.value) {
          const product = pendingProduct.value
          pendingProduct.value = null
          openPicker(created, product)
        }
      } else {
        nameError.value = panelError.value
      }
    }
  } finally {
    nameSaving.value = false
  }
}

// 删除确认
const deleteDialog = ref<HTMLDialogElement | null>(null)
const deletingBundle = ref<ProductBundle | null>(null)
const deleting = ref(false)
const deleteError = ref('')

function askDelete(bundle: ProductBundle) {
  deletingBundle.value = bundle
  deleteError.value = ''
  deleteDialog.value?.showModal()
}

async function confirmDelete() {
  const bundle = deletingBundle.value
  if (!bundle || deleting.value) return
  deleting.value = true
  deleteError.value = ''
  try {
    await api.deleteProductBundle(bundle.bundleId, bundle.revision)
    bundles.value = bundles.value.filter(row => row.bundleId !== bundle.bundleId)
    deleteDialog.value?.close()
    deletingBundle.value = null
  } catch (error) {
    deleteError.value = error instanceof Error ? error.message : '删除失败'
  } finally {
    deleting.value = false
  }
}

// 变体解析：与 ProductBundlePanel 同一套规则，本地商品与主库商品分别取规格。
async function loadVariantOptions(product: ImportedProduct) {
  const productId = localProductId(product.rspuId)
  if (productId) {
    const detail = await api.getLocalProduct(productId)
    return detail.variants.flatMap(variant => variant.id ? [{ variantId: variant.id, displayName: variant.name }] : [])
  }
  const detail = await api.getProduct(product.rspuId)
  return detail.variants.filter(variant => variant.status === 'active')
}

function mergeItems(bundle: ProductBundle, additions: BundleDisplayItem[]) {
  const items = bundle.items.map(item => ({ ...item }))
  for (const addition of additions) {
    const existing = items.find(item => item.rspuId === addition.rspuId && item.variantId === addition.variantId)
    if (existing) existing.quantity += addition.quantity
    else items.push(addition)
  }
  return items
}

async function onDrop(bundle: ProductBundle, event: DragEvent) {
  const rspuId = event.dataTransfer?.getData('application/x-rsdp-product')
  if (!rspuId) return
  const product = props.products.find(row => row.rspuId === rspuId)
  if (!product || product.status !== 'active') return
  await addProductsToBundle(bundle, [{ product }])
}

async function addProductsToBundle(bundle: ProductBundle, selections: { product: ImportedProduct, variantId?: string }[]) {
  panelError.value = ''
  const additions: BundleDisplayItem[] = []
  for (const selection of selections) {
    const product = selection.product
    try {
      const options = await loadVariantOptions(product)
      if (!options.length) {
        panelError.value = `「${product.productName || product.rspuId}」暂无可加入的规格`
        return
      }
      const variantId = selection.variantId || options[0]!.variantId
      const variant = options.find(option => option.variantId === variantId) || options[0]!
      additions.push({
        rspuId: product.rspuId, variantId: variant.variantId, quantity: 1,
        name: product.productName || product.rspuId, variant: variant.displayName, imageUrl: product.primaryImageUrl,
      })
    } catch (error) {
      panelError.value = error instanceof Error ? error.message : '读取商品规格失败'
      return
    }
  }
  const items = mergeItems(bundle, additions)
  if (items.length > 5) {
    panelError.value = '每个组合最多5个商品'
    return
  }
  const result = await persist(bundle, bundle.name, items)
  if (result) replaceBundle(result)
}

async function removeItem(bundle: ProductBundle, target: BundleDisplayItem) {
  const items = bundle.items.filter(item => item.rspuId !== target.rspuId || item.variantId !== target.variantId)
  const result = await persist(bundle, bundle.name, items)
  if (result) replaceBundle(result)
}

// 选品弹窗
const pickerDialog = ref<HTMLDialogElement | null>(null)
const pickerBundle = ref<ProductBundle | null>(null)
const pickerSelection = ref<Map<string, ImportedProduct>>(new Map())
const pickerSearch = ref('')
const pickerError = ref('')
const pickerSaving = ref(false)
const pickerProducts = ref<ImportedProduct[]>([])
const pickerLoading = ref(false)
let pickerRequest = 0
let pickerSearchTimer: ReturnType<typeof setTimeout> | undefined

const pickerExistingIds = computed(() => new Set(pickerBundle.value?.items.map(item => item.rspuId) ?? []))
const pickerVariants = ref<Record<string, { variantId: string, displayName: string }[]>>({})
const pickerVariantIds = ref<Record<string, string>>({})

async function ensureVariantOptions(product: ImportedProduct) {
  if (pickerVariants.value[product.rspuId]) return
  try {
    const options = await loadVariantOptions(product)
    pickerVariants.value = { ...pickerVariants.value, [product.rspuId]: options }
    if (options.length) pickerVariantIds.value = { ...pickerVariantIds.value, [product.rspuId]: options[0]!.variantId }
  } catch {
    pickerVariants.value = { ...pickerVariants.value, [product.rspuId]: [] }
  }
}

async function loadPickerProducts() {
  const request = ++pickerRequest
  pickerLoading.value = true
  try {
    const result = await api.loadImportedProductPage({
      page: 1, size: 100, search: pickerSearch.value.trim(), status: '', managed: false,
    })
    if (request === pickerRequest) pickerProducts.value = result?.rows ?? []
  } catch {
    if (request === pickerRequest) pickerProducts.value = []
  } finally {
    if (request === pickerRequest) pickerLoading.value = false
  }
}

const pickerDisplayProducts = computed(() => {
  const rows = [...pickerProducts.value]
  for (const product of pickerSelection.value.values()) {
    if (!rows.some(row => row.rspuId === product.rspuId)) rows.push(product)
  }
  return rows
})

const pickerDisplayProductsEmpty = computed(() => !pickerDisplayProducts.value.length)

watch(pickerSearch, () => {
  if (!pickerDialog.value?.open) return
  clearTimeout(pickerSearchTimer)
  pickerSearchTimer = setTimeout(() => void loadPickerProducts(), 200)
})

onBeforeUnmount(() => clearTimeout(pickerSearchTimer))

function openPicker(bundle: ProductBundle, preselect?: ImportedProduct) {
  pickerBundle.value = bundle
  pickerSelection.value = preselect ? new Map([[preselect.rspuId, preselect]]) : new Map()
  pickerSearch.value = ''
  pickerError.value = ''
  pickerDialog.value?.showModal()
  if (preselect) void ensureVariantOptions(preselect)
  void loadPickerProducts()
}

function togglePickerProduct(product: ImportedProduct) {
  const next = new Map(pickerSelection.value)
  if (next.has(product.rspuId)) next.delete(product.rspuId)
  else {
    next.set(product.rspuId, product)
    void ensureVariantOptions(product)
  }
  pickerSelection.value = next
}

async function confirmPicker() {
  const bundle = pickerBundle.value
  if (!bundle || pickerSaving.value || !pickerSelection.value.size) return
  pickerSaving.value = true
  pickerError.value = ''
  await addProductsToBundle(bundle, [...pickerSelection.value.values()]
    .map(product => ({ product, variantId: pickerVariantIds.value[product.rspuId] })))
  pickerSaving.value = false
  if (!panelError.value) pickerDialog.value?.close()
  else pickerError.value = panelError.value
}

// 商品卡片「加入组合」入口：无组合时先引导创建，否则向第一个组合选品。
async function addProduct(product: ImportedProduct) {
  if (!available.value || product.status !== 'active') return
  if (!bundles.value.length) {
    pendingProduct.value = product
    openNameDialog(null)
    return
  }
  openPicker(bundles.value[0]!, product)
}

defineExpose({ addProduct })
</script>

<template>
  <aside data-testid="combo-panel" class="relative flex shrink-0 flex-col border border-line bg-white"
    :style="{ width: '100%', maxWidth: `${panelWidth}px` }" aria-label="自选组合侧边栏">
    <button type="button" data-testid="combo-panel-resize-handle" aria-label="拖动调整自选组合栏宽度"
      class="absolute -left-1.5 top-0 z-10 hidden h-full w-3 cursor-ew-resize md:block" @pointerdown="startResize" />
    <div class="border-b border-line p-4">
      <h2 class="text-lg font-bold">自选组合</h2>
      <p class="mt-2 rounded-lg bg-cream px-3 py-2 text-xs text-muted">拖拽商品到组合卡，或点组合空位＋添加；保存后可在产品方案页应用。</p>
    </div>
    <div v-if="panelError" class="border-b border-red-100 bg-red-50 px-4 py-2 text-xs text-red-800" role="alert">{{ panelError }}</div>
    <div v-if="notice" class="border-b border-green/20 bg-green/10 px-4 py-2 text-xs text-green" role="status">{{ notice }}</div>

    <div class="min-h-0 flex-1 overflow-y-auto p-4">
      <p v-if="loading" class="text-xs text-muted" role="status">组合加载中…</p>
      <p v-else-if="!available" class="text-xs text-muted">组合服务暂不可用。<button type="button" class="font-semibold underline" @click="loadBundles">重试</button></p>
      <p v-else-if="!bundles.length" class="mb-3 text-xs text-muted">还没有组合，点击下方按钮创建。</p>
      <ul class="space-y-4">
        <li v-for="bundle in bundles" :key="bundle.bundleId" data-testid="combo-card"
          class="rounded-xl border border-line bg-white p-3 transition hover:border-bronze/60"
          @dragover.prevent @drop.prevent="onDrop(bundle, $event)">
          <div class="mb-3 flex items-center justify-between gap-2">
            <h3 class="min-w-0 flex-1 truncate text-sm font-bold" :title="bundle.name">{{ bundle.name }}</h3>
            <button type="button" class="shrink-0 rounded-md border border-line px-2 py-1 text-xs text-bronze-dark transition hover:bg-cream" :disabled="isSaving(bundle.bundleId)" @click="openNameDialog(bundle)">重命名</button>
            <button type="button" class="shrink-0 rounded-md border border-line px-2 py-1 text-xs text-[#b3402f] transition hover:bg-red-50" @click="askDelete(bundle)">删除</button>
            <span class="shrink-0 text-xs font-semibold text-bronze-dark">{{ bundle.items.length }}/5</span>
          </div>
          <div class="grid grid-cols-2 gap-2">
            <div v-for="item in bundle.items" :key="`${item.rspuId}-${item.variantId}`"
              class="relative aspect-[4/3] overflow-hidden rounded-lg border border-line bg-bg" :title="item.name || item.rspuId">
              <ProductImagePreview :src="item.imageUrl" :alt="item.name || ''" size="small" />
              <span v-if="!item.name" class="absolute inset-x-0 bottom-0 bg-black/50 py-0.5 text-center text-[10px] text-white">已失效</span>
            </div>
            <button v-if="bundle.items.length < 5" type="button" aria-label="添加商品"
              class="grid aspect-[4/3] place-items-center rounded-lg border border-dashed border-line text-xl text-muted transition hover:border-bronze hover:text-bronze-dark"
              :disabled="isSaving(bundle.bundleId)" @click="openPicker(bundle)">＋</button>
          </div>
          <p class="mt-1 text-xs text-muted">拖拽商品到此处，或点空位＋添加</p>
          <button type="button" class="mt-2 w-full rounded-lg bg-cream py-1.5 text-xs text-bronze-dark transition hover:bg-bronze/10"
            :aria-expanded="isExpanded(bundle.bundleId)" @click="toggleExpanded(bundle.bundleId)">
            {{ isExpanded(bundle.bundleId) ? '↑ 收起商品' : '↓ 展开商品' }}
          </button>
          <ul v-if="isExpanded(bundle.bundleId)" data-testid="combo-item-list" class="mt-2 space-y-2">
            <li v-for="item in bundle.items" :key="`${item.rspuId}-${item.variantId}`" class="flex items-center gap-2 rounded-lg border border-line bg-bg p-2">
              <div class="h-10 w-10 shrink-0 overflow-hidden rounded-md border border-line bg-white">
                <ProductImagePreview :src="item.imageUrl" alt="" size="small" />
              </div>
              <div class="min-w-0 flex-1">
                <p class="truncate text-xs font-semibold">
                  <NuxtLink v-if="item.name" :to="catalogProductPath(item.rspuId)" class="hover:text-bronze-dark hover:underline">{{ item.name }}</NuxtLink>
                  <span v-else>{{ item.rspuId }}<span class="ml-1 text-red-800">（已失效）</span></span>
                </p>
                <p class="text-xs text-muted">{{ item.variant || '默认规格' }} · 数量 ×{{ item.quantity }}</p>
              </div>
              <button type="button" :aria-label="`移除${item.name || item.rspuId}`" :disabled="isSaving(bundle.bundleId)"
                class="shrink-0 rounded-md px-2 py-1 text-sm text-muted transition hover:bg-cream" @click="removeItem(bundle, item)">×</button>
            </li>
          </ul>
        </li>
      </ul>
    </div>

    <div class="border-t border-line p-4">
      <button type="button" class="w-full rounded-xl border border-bronze py-2.5 text-sm font-semibold text-bronze-dark transition hover:bg-cream disabled:cursor-not-allowed disabled:opacity-50"
        :disabled="!available || bundles.length >= 10" :title="bundles.length >= 10 ? '最多创建10个组合' : undefined"
        @click="openNameDialog(null)">＋ 创建新的组合</button>
      <p v-if="bundles.length >= 10" class="mt-2 text-center text-xs text-muted">最多创建10个组合</p>
    </div>

    <dialog ref="nameDialog" class="m-auto w-[min(400px,calc(100%_-_32px))] rounded-2xl border border-line bg-white p-0 text-ink shadow-panel backdrop:bg-black/30"
      aria-label="命名组合" @close="editingBundle = null">
      <form class="p-6" @submit.prevent="confirmName">
        <h2 class="mb-4 text-lg font-bold">{{ editingBundle ? '重命名组合' : '创建新的组合' }}</h2>
        <input v-model="nameInput" type="text" aria-label="组合名称" maxlength="100" placeholder="给组合起个名字，例如：会议洽谈组合"
          class="w-full rounded-lg border border-line bg-bg px-3 py-2.5 text-sm outline-bronze">
        <p v-if="nameError" class="mt-3 rounded-lg border border-red-100 bg-red-50 px-3 py-2 text-xs text-red-800" role="alert">{{ nameError }}</p>
        <div class="mt-5 flex justify-end gap-3">
          <button type="button" class="rounded-lg border border-line px-4 py-2 text-sm disabled:opacity-50" :disabled="nameSaving" @click="nameDialog?.close()">取消</button>
          <button type="submit" class="rounded-lg bg-bronze px-4 py-2 text-sm font-semibold text-white transition hover:bg-bronze-dark disabled:opacity-50" :disabled="nameSaving">{{ nameSaving ? '保存中…' : '确认' }}</button>
        </div>
      </form>
    </dialog>

    <dialog ref="deleteDialog" class="m-auto w-[min(400px,calc(100%_-_32px))] rounded-2xl border border-line bg-white p-0 text-ink shadow-panel backdrop:bg-black/30" aria-label="删除组合">
      <div class="p-6">
        <h2 class="mb-4 text-lg font-bold">删除组合</h2>
        <p class="text-sm leading-6">确定删除组合「<strong>{{ deletingBundle?.name }}</strong>」吗？仅解散组合，不会删除商品本身。</p>
        <p v-if="deleteError" class="mt-3 rounded-lg border border-red-100 bg-red-50 px-3 py-2 text-xs text-red-800" role="alert">{{ deleteError }}</p>
        <div class="mt-5 flex justify-end gap-3">
          <button type="button" class="rounded-lg border border-line px-4 py-2 text-sm disabled:opacity-50" :disabled="deleting" @click="deleteDialog?.close()">取消</button>
          <button type="button" class="rounded-lg bg-[#b3402f] px-4 py-2 text-sm font-semibold text-white transition hover:bg-[#9c3627] disabled:opacity-50" :disabled="deleting" @click="confirmDelete">{{ deleting ? '删除中…' : '确认删除' }}</button>
        </div>
      </div>
    </dialog>

    <dialog ref="pickerDialog" class="m-auto w-[min(520px,calc(100%_-_32px))] rounded-2xl border border-line bg-white p-0 text-ink shadow-panel backdrop:bg-black/30" aria-label="选择商品">
      <div class="flex max-h-[80dvh] min-h-0 flex-col p-6">
        <h2 class="mb-1 text-lg font-bold">选择商品</h2>
        <p class="mb-4 text-xs text-muted">加入「{{ pickerBundle?.name }}」，每组最多 5 个，已选 {{ pickerSelection.size }} 个</p>
        <input v-model="pickerSearch" type="search" aria-label="搜索商品" placeholder="搜索商品名称、编码"
          class="mb-3 w-full rounded-lg border border-line bg-bg px-3 py-2.5 text-sm outline-bronze">
        <div class="min-h-0 flex-1 overflow-y-auto">
          <ul class="space-y-2">
            <li v-for="product in pickerDisplayProducts" :key="product.rspuId">
              <label class="flex cursor-pointer items-center gap-3 rounded-lg border border-line p-2.5 transition hover:border-bronze/60"
                :class="{ 'opacity-50': pickerExistingIds.has(product.rspuId) || product.status !== 'active' }">
                <input type="checkbox" class="h-4 w-4 shrink-0 accent-bronze" :aria-label="product.productName || product.rspuId"
                  :checked="pickerSelection.has(product.rspuId)"
                  :disabled="pickerExistingIds.has(product.rspuId) || product.status !== 'active'"
                  @change="togglePickerProduct(product)">
                <div class="h-10 w-10 shrink-0 overflow-hidden rounded-md border border-line bg-white">
                  <ProductImagePreview :src="product.primaryImageUrl" alt="" size="small" />
                </div>
                <div class="min-w-0 flex-1">
                  <p class="truncate text-sm font-semibold">{{ product.productName || product.rspuId }}</p>
                  <p class="text-xs text-muted">{{ product.rspuCode || product.rspuId }}</p>
                </div>
                <span v-if="pickerExistingIds.has(product.rspuId)" class="shrink-0 text-xs text-muted">已加入</span>
                <span v-else-if="product.status !== 'active'" class="shrink-0 text-xs text-muted">非在售</span>
              </label>
              <select v-if="pickerSelection.has(product.rspuId) && (pickerVariants[product.rspuId]?.length || 0) >= 1"
                v-model="pickerVariantIds[product.rspuId]" aria-label="选择商品规格"
                class="mt-1 w-full rounded-lg border border-line bg-white px-2 py-1.5 text-xs outline-bronze" @click.stop>
                <option v-for="option in pickerVariants[product.rspuId]" :key="option.variantId" :value="option.variantId">{{ option.displayName || option.variantId }}</option>
              </select>
            </li>
          </ul>
          <p v-if="pickerLoading" class="py-6 text-center text-xs text-muted" role="status">商品加载中…</p>
          <p v-else-if="pickerDisplayProductsEmpty" class="py-6 text-center text-xs text-muted">没有匹配的商品</p>
        </div>
        <p v-if="pickerError" class="mt-3 rounded-lg border border-red-100 bg-red-50 px-3 py-2 text-xs text-red-800" role="alert">{{ pickerError }}</p>
        <div class="mt-5 flex justify-end gap-3">
          <button type="button" class="rounded-lg border border-line px-4 py-2 text-sm disabled:opacity-50" :disabled="pickerSaving" @click="pickerDialog?.close()">取消</button>
          <button type="button" class="rounded-lg bg-bronze px-4 py-2 text-sm font-semibold text-white transition hover:bg-bronze-dark disabled:opacity-50"
            :disabled="pickerSaving || !pickerSelection.size" @click="confirmPicker">{{ pickerSaving ? '加入中…' : '确认加入' }}</button>
        </div>
      </div>
    </dialog>
  </aside>
</template>
