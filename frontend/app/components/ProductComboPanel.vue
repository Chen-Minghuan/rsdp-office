<script setup lang="ts">
import type { ImportedProduct } from '~/types/imported-products'
import type { ProductCombo } from '~/types/combos'

const props = defineProps<{ products: ImportedProduct[] }>()

const api = useOfficeApi()
const combos = ref<ProductCombo[]>([])
const panelError = ref<string | null>(null)
const loading = ref(true)
const expandedIds = ref<Set<string>>(new Set())

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

async function loadCombos() {
  try {
    combos.value = await api.loadCombos()
    panelError.value = null
  } catch (error) {
    panelError.value = error instanceof Error ? error.message : '组合加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(loadCombos)

function isExpanded(comboId: string) {
  return expandedIds.value.has(comboId)
}

function toggleExpanded(comboId: string) {
  const next = new Set(expandedIds.value)
  if (next.has(comboId)) next.delete(comboId)
  else next.add(comboId)
  expandedIds.value = next
}

function formatPrice(price: number | null) {
  return price == null ? '—' : `¥ ${price.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

async function onDrop(combo: ProductCombo, event: DragEvent) {
  const rspuId = event.dataTransfer?.getData('text/plain')
  if (!rspuId) return
  panelError.value = null
  if (combo.items.some(item => item.rspuId === rspuId)) return
  if (combo.items.length >= 5) {
    panelError.value = '每个组合最多5个商品'
    return
  }
  try {
    await api.addComboItems(combo.comboId, [rspuId])
    await loadCombos()
  } catch (error) {
    panelError.value = error instanceof Error ? error.message : '加入组合失败'
  }
}

async function removeItem(combo: ProductCombo, rspuId: string) {
  panelError.value = null
  try {
    await api.removeComboItem(combo.comboId, rspuId)
    await loadCombos()
  } catch (error) {
    panelError.value = error instanceof Error ? error.message : '移除商品失败'
  }
}

// 命名弹窗：创建与改名共用
const nameDialog = ref<HTMLDialogElement | null>(null)
const nameInput = ref('')
const nameError = ref<string | null>(null)
const nameSaving = ref(false)
const editingCombo = ref<ProductCombo | null>(null)

function openNameDialog(combo: ProductCombo | null) {
  editingCombo.value = combo
  nameInput.value = combo?.name ?? ''
  nameError.value = null
  nameDialog.value?.showModal()
}

async function confirmName() {
  if (nameSaving.value) return
  nameSaving.value = true
  nameError.value = null
  try {
    if (editingCombo.value) {
      await api.renameCombo(editingCombo.value.comboId, nameInput.value)
      editingCombo.value.name = nameInput.value.trim()
    } else {
      const created = await api.createCombo(nameInput.value)
      combos.value = [...combos.value, created]
    }
    nameDialog.value?.close()
  } catch (error) {
    nameError.value = error instanceof Error ? error.message : '保存失败'
  } finally {
    nameSaving.value = false
  }
}

// 删除确认
const deleteDialog = ref<HTMLDialogElement | null>(null)
const deletingCombo = ref<ProductCombo | null>(null)
const deleting = ref(false)
const deleteError = ref<string | null>(null)

function askDelete(combo: ProductCombo) {
  deletingCombo.value = combo
  deleteError.value = null
  deleteDialog.value?.showModal()
}

async function confirmDelete() {
  const combo = deletingCombo.value
  if (!combo || deleting.value) return
  deleting.value = true
  deleteError.value = null
  try {
    await api.deleteCombo(combo.comboId)
    combos.value = combos.value.filter(item => item.comboId !== combo.comboId)
    deleteDialog.value?.close()
    deletingCombo.value = null
  } catch (error) {
    deleteError.value = error instanceof Error ? error.message : '删除失败'
  } finally {
    deleting.value = false
  }
}

// 选品弹窗
const pickerDialog = ref<HTMLDialogElement | null>(null)
const pickerCombo = ref<ProductCombo | null>(null)
const pickerSelection = ref<Set<string>>(new Set())
const pickerSearch = ref('')
const pickerError = ref<string | null>(null)
const pickerSaving = ref(false)

const pickerExistingIds = computed(() => new Set(pickerCombo.value?.items.map(item => item.rspuId) ?? []))

const pickerProducts = computed(() => {
  const query = pickerSearch.value.trim().toLowerCase()
  if (!query) return props.products
  return props.products.filter(product =>
    [product.productName, product.rspuCode, product.externalCode, product.rspuId]
      .some(value => value?.toLowerCase().includes(query)))
})

function openPicker(combo: ProductCombo) {
  pickerCombo.value = combo
  pickerSelection.value = new Set()
  pickerSearch.value = ''
  pickerError.value = null
  pickerDialog.value?.showModal()
}

function togglePickerProduct(rspuId: string) {
  const next = new Set(pickerSelection.value)
  if (next.has(rspuId)) next.delete(rspuId)
  else next.add(rspuId)
  pickerSelection.value = next
}

async function confirmPicker() {
  const combo = pickerCombo.value
  if (!combo || pickerSaving.value) return
  pickerSaving.value = true
  pickerError.value = null
  try {
    await api.addComboItems(combo.comboId, [...pickerSelection.value])
    await loadCombos()
    pickerDialog.value?.close()
  } catch (error) {
    pickerError.value = error instanceof Error ? error.message : '加入组合失败'
  } finally {
    pickerSaving.value = false
  }
}
</script>

<template>
  <aside data-testid="combo-panel" class="relative flex shrink-0 flex-col border-l border-line bg-white"
    :style="{ width: `${panelWidth}px` }" aria-label="自选组合侧边栏">
    <button type="button" data-testid="combo-panel-resize-handle" aria-label="拖动调整自选组合栏宽度"
      class="absolute -left-1.5 top-0 z-10 hidden h-full w-3 cursor-ew-resize md:block" @pointerdown="startResize" />
    <div class="border-b border-line p-4">
      <h2 class="text-lg font-bold">自选组合</h2>
      <p class="mt-2 rounded-lg bg-cream px-3 py-2 text-xs text-muted">拖拽商品到组合卡，或点组合空位＋添加</p>
    </div>
    <div v-if="panelError" class="border-b border-red-100 bg-red-50 px-4 py-2 text-xs text-red-800" role="alert">{{ panelError }}</div>

    <div class="min-h-0 flex-1 overflow-y-auto p-4">
      <p v-if="loading" class="text-xs text-muted" role="status">组合加载中…</p>
      <p v-else-if="!combos.length" class="mb-3 text-xs text-muted">还没有组合，点击下方按钮创建。</p>
      <ul class="space-y-4">
        <li v-for="combo in combos" :key="combo.comboId" data-testid="combo-card"
          class="rounded-xl border border-line bg-white p-3 transition hover:border-bronze/60"
          @dragover.prevent @drop.prevent="onDrop(combo, $event)">
          <div class="mb-3 flex items-center justify-between gap-2">
            <h3 class="min-w-0 flex-1 truncate text-sm font-bold" :title="combo.name">{{ combo.name }}</h3>
            <button type="button" class="shrink-0 rounded-md border border-line px-2 py-1 text-xs text-bronze-dark transition hover:bg-cream" @click="openNameDialog(combo)">重命名</button>
            <button type="button" class="shrink-0 rounded-md border border-line px-2 py-1 text-xs text-[#b3402f] transition hover:bg-red-50" @click="askDelete(combo)">删除</button>
            <span class="shrink-0 text-xs font-semibold text-bronze-dark">{{ combo.items.length }}/5</span>
          </div>
          <div class="grid grid-cols-2 gap-2">
            <div v-for="item in combo.items" :key="item.rspuId"
              class="relative aspect-[4/3] overflow-hidden rounded-lg border border-line bg-bg" :title="item.productName || item.rspuId">
              <ProductImagePreview :src="item.primaryImageUrl" :alt="item.productName || ''" size="small" />
              <span v-if="!item.available" class="absolute inset-x-0 bottom-0 bg-black/50 py-0.5 text-center text-[10px] text-white">已失效</span>
            </div>
            <button v-if="combo.items.length < 5" type="button" aria-label="添加商品"
              class="grid aspect-[4/3] place-items-center rounded-lg border border-dashed border-line text-xl text-muted transition hover:border-bronze hover:text-bronze-dark"
              @click="openPicker(combo)">＋</button>
          </div>
          <p class="mt-1 text-xs text-muted">拖拽商品到此处，或点空位＋添加</p>
          <button type="button" class="mt-2 w-full rounded-lg bg-cream py-1.5 text-xs text-bronze-dark transition hover:bg-bronze/10"
            :aria-expanded="isExpanded(combo.comboId)" @click="toggleExpanded(combo.comboId)">
            {{ isExpanded(combo.comboId) ? '↑ 收起商品' : '↓ 展开商品' }}
          </button>
          <ul v-if="isExpanded(combo.comboId)" data-testid="combo-item-list" class="mt-2 space-y-2">
            <li v-for="item in combo.items" :key="item.rspuId" class="flex items-center gap-2 rounded-lg border border-line bg-bg p-2">
              <div class="h-10 w-10 shrink-0 overflow-hidden rounded-md border border-line bg-white">
                <ProductImagePreview :src="item.primaryImageUrl" alt="" size="small" />
              </div>
              <div class="min-w-0 flex-1">
                <p class="truncate text-xs font-semibold">{{ item.productName || item.rspuId }}<span v-if="!item.available" class="ml-1 text-red-800">（已失效）</span></p>
                <p class="text-xs text-bronze-dark">{{ formatPrice(item.retailPrice) }}</p>
              </div>
              <button type="button" :aria-label="`移除${item.productName || item.rspuId}`"
                class="shrink-0 rounded-md px-2 py-1 text-sm text-muted transition hover:bg-cream" @click="removeItem(combo, item.rspuId)">×</button>
            </li>
          </ul>
        </li>
      </ul>
    </div>

    <div class="border-t border-line p-4">
      <button type="button" class="w-full rounded-xl border border-bronze py-2.5 text-sm font-semibold text-bronze-dark transition hover:bg-cream disabled:cursor-not-allowed disabled:opacity-50"
        :disabled="combos.length >= 10" :title="combos.length >= 10 ? '最多创建10个组合' : undefined"
        @click="openNameDialog(null)">＋ 创建新的组合</button>
      <p v-if="combos.length >= 10" class="mt-2 text-center text-xs text-muted">最多创建10个组合</p>
    </div>

    <dialog ref="nameDialog" class="m-auto w-[min(400px,calc(100%_-_32px))] rounded-2xl border border-line bg-white p-0 text-ink shadow-panel backdrop:bg-black/30"
      aria-label="命名组合" @close="editingCombo = null">
      <form class="p-6" @submit.prevent="confirmName">
        <h2 class="mb-4 text-lg font-bold">{{ editingCombo ? '重命名组合' : '创建新的组合' }}</h2>
        <input v-model="nameInput" type="text" aria-label="组合名称" maxlength="50" placeholder="给组合起个名字，例如：会议洽谈组合"
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
        <p class="text-sm leading-6">确定删除组合「<strong>{{ deletingCombo?.name }}</strong>」吗？仅解散组合，不会删除商品本身。</p>
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
        <p class="mb-4 text-xs text-muted">加入「{{ pickerCombo?.name }}」，每组最多 5 个，已选 {{ pickerSelection.size }} 个</p>
        <input v-model="pickerSearch" type="search" aria-label="搜索商品" placeholder="搜索商品名称、编码"
          class="mb-3 w-full rounded-lg border border-line bg-bg px-3 py-2.5 text-sm outline-bronze">
        <div class="min-h-0 flex-1 overflow-y-auto">
          <ul class="space-y-2">
            <li v-for="product in pickerProducts" :key="product.rspuId">
              <label class="flex cursor-pointer items-center gap-3 rounded-lg border border-line p-2.5 transition hover:border-bronze/60"
                :class="{ 'opacity-50': pickerExistingIds.has(product.rspuId) }">
                <input type="checkbox" class="h-4 w-4 shrink-0 accent-bronze" :aria-label="product.productName || product.rspuId"
                  :checked="pickerSelection.has(product.rspuId)"
                  :disabled="pickerExistingIds.has(product.rspuId)"
                  @change="togglePickerProduct(product.rspuId)">
                <div class="h-10 w-10 shrink-0 overflow-hidden rounded-md border border-line bg-white">
                  <ProductImagePreview :src="product.primaryImageUrl" alt="" size="small" />
                </div>
                <div class="min-w-0 flex-1">
                  <p class="truncate text-sm font-semibold">{{ product.productName || product.rspuId }}</p>
                  <p class="text-xs text-muted">{{ product.rspuCode || product.rspuId }}</p>
                </div>
                <span v-if="pickerExistingIds.has(product.rspuId)" class="shrink-0 text-xs text-muted">已加入</span>
                <span v-else class="shrink-0 text-xs font-semibold text-bronze-dark">{{ formatPrice(product.retailPrice) }}</span>
              </label>
            </li>
          </ul>
          <p v-if="!pickerProducts.length" class="py-6 text-center text-xs text-muted">没有匹配的商品</p>
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
