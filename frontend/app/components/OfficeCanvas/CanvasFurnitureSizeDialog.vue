<script setup lang="ts">
import type { FurnitureObject } from '~/types/office'
import { sameOriginAssetUrl } from '~/utils/apiBase'
import { money } from '~/utils/helpers'

const props = defineProps<{ item: FurnitureObject | null; itemIds?: string[]; anchor?: HTMLElement | null }>()
const show = defineModel<boolean>('show', { default: false })
const emit = defineEmits<{ (event: 'applied', message: string): void }>()

const store = useOfficeStore()
const width = ref(0)
const depth = ref(0)
const error = ref('')
const position = ref<{ left: number; bottom: number } | null>(null)

// 仅单件编辑时展示绑定商品（批量改尺寸时指向单一商品会误导）
const isSingle = computed(() => !props.itemIds?.length || props.itemIds.length <= 1)
// 优先在售列表实时数据，回退 productSelection 快照（与 SelectedFurniturePanel 同口径）
const boundProduct = computed(() => {
  const item = props.item
  if (!item || !isSingle.value || !item.productSelection) return null
  return store.products.find(product => product.id === item.productId) || item.productSelection.product || null
})
const productLink = computed(() => boundProduct.value?.rspuId ? `/products/${boundProduct.value.rspuId}` : null)
const fmtDim = (value?: number) => value ? `${Math.round(value)}` : '—'
const productDims = computed(() => {
  const product = boundProduct.value
  if (!product) return ''
  return `宽${fmtDim(product.widthMm)} 高${fmtDim(product.heightMm)} 深${fmtDim(product.depthMm)}mm`
})

watch([() => props.item, () => show.value], () => {
  if (props.item && show.value) {
    width.value = Math.round(props.item.w)
    depth.value = Math.round(props.item.h)
    error.value = ''
    nextTick(updatePosition)
  }
})

function updatePosition() {
  if (!props.anchor) { position.value = null; return }
  const rect = props.anchor.getBoundingClientRect()
  position.value = { left: rect.left, bottom: window.innerHeight - rect.top + 8 }
}

function apply() {
  if (!props.item) return
  error.value = ''
  const ids = props.itemIds?.length ? props.itemIds : [props.item.id]
  const idSet = new Set(ids)
  const selectedItems = store.objects.filter((object): object is FurnitureObject => object.kind === 'furniture' && idSet.has(object.id))
  const changedCount = selectedItems.filter(item => item.w !== width.value || item.h !== depth.value).length
  if (selectedItems.length === idSet.size && changedCount === 0) {
    show.value = false
    emit('applied', '尺寸未变化，无需更新')
    return
  }
  const result = store.resizeFurnitureBatch(ids, width.value, depth.value)
  if (result) {
    if (result.startsWith('已调整尺寸')) {
      show.value = false
      emit('applied', result)
      return
    }
    error.value = result
    return
  }
  show.value = false
  emit('applied', `已更新 ${changedCount} 个家具尺寸为 ${width.value}×${depth.value}mm`)
}

function onBackdropClick() {
  show.value = false
}

onMounted(() => { window.addEventListener('resize', updatePosition) })
onBeforeUnmount(() => { window.removeEventListener('resize', updatePosition) })
</script>

<template>
  <Teleport to="body">
    <div v-if="show" class="fixed inset-0 z-[95]" data-testid="furniture-size-dialog" @click.self="onBackdropClick">
      <section
        role="dialog"
        aria-modal="true"
        aria-label="修改尺寸"
        class="bg-white text-xs shadow-xl"
        :class="[boundProduct ? 'w-80' : 'w-64', position ? 'fixed' : 'absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2']"
        :style="position ? { left: `${position.left}px`, bottom: `${position.bottom}px` } : undefined"
      >
        <header class="flex items-center justify-between border-b border-line px-3 py-2">
          <h2 class="text-[11px] font-bold">修改尺寸 · {{ item?.label || '家具' }}</h2>
          <button aria-label="关闭" class="px-1 py-0.5 text-muted" @click="show = false">✕</button>
        </header>
        <div class="flex gap-2 p-3">
          <div class="min-w-0 flex-1">
            <p v-if="error" role="alert" class="mb-2 rounded-none bg-red-50 px-2 py-1 text-[10px] text-red-700">{{ error }}</p>
            <div class="grid grid-cols-2 gap-2">
              <label class="text-[10px] text-muted">
                宽度 mm
                <input v-model.number="width" type="number" min="10" step="50" class="mt-1 w-full rounded-none border border-line bg-white px-2 py-1.5 text-[11px]">
              </label>
              <label class="text-[10px] text-muted">
                深度 mm
                <input v-model.number="depth" type="number" min="10" step="50" class="mt-1 w-full rounded-none border border-line bg-white px-2 py-1.5 text-[11px]">
              </label>
            </div>
            <p class="mt-1.5 text-[10px] text-muted">当前占地 {{ Math.round(item?.w || 0) }}×{{ Math.round(item?.h || 0) }}mm</p>
            <button class="mt-2 w-full rounded-none bg-bronze py-1.5 text-[11px] font-semibold text-white" @click="apply">应用尺寸</button>
          </div>
          <div v-if="boundProduct" class="flex w-27 flex-none flex-col self-stretch" data-testid="size-dialog-product">
            <div class="group relative flex min-h-0 flex-1" :title="boundProduct.name">
              <NuxtLink v-if="productLink" :to="productLink" class="block h-full w-full">
                <img v-if="boundProduct.imageUrl" :src="sameOriginAssetUrl(boundProduct.imageUrl)" :alt="boundProduct.name" class="h-full w-full rounded-none border border-line bg-[#f7f4f0] object-contain">
                <span v-else class="grid h-full w-full place-items-center rounded-none border border-dashed border-line text-[9px] text-muted">暂无图片</span>
              </NuxtLink>
              <template v-else>
                <img v-if="boundProduct.imageUrl" :src="sameOriginAssetUrl(boundProduct.imageUrl)" :alt="boundProduct.name" class="h-full w-full rounded-none border border-line bg-[#f7f4f0] object-contain">
                <span v-else class="grid h-full w-full place-items-center rounded-none border border-dashed border-line text-[9px] text-muted">暂无图片</span>
              </template>
              <div class="absolute right-0 top-full z-10 mt-1 hidden w-40 rounded-none border border-line bg-white p-2 shadow-lg group-hover:block" data-testid="size-dialog-product-tip">
                <p class="text-[11px] font-bold text-ink">{{ boundProduct.name }}</p>
                <p class="mt-0.5 text-[11px] text-bronze">{{ boundProduct.price > 0 ? money(boundProduct.price) : '待报价' }}</p>
                <p class="mt-0.5 text-[10px] text-muted">主色：{{ boundProduct.colorPrimaryName || '未分类' }}</p>
                <p class="mt-0.5 text-[10px] text-muted">宽度：{{ fmtDim(boundProduct.widthMm) }}mm</p>
                <p class="mt-0.5 text-[10px] text-muted">高度：{{ fmtDim(boundProduct.heightMm) }}mm</p>
                <p class="mt-0.5 text-[10px] text-muted">深度：{{ fmtDim(boundProduct.depthMm) }}mm</p>
              </div>
            </div>
            <p class="mt-1 flex-none text-[6.3px] leading-tight text-muted" title="商品规格（mm）">{{ productDims }}</p>
          </div>
        </div>
      </section>
    </div>
  </Teleport>
</template>
