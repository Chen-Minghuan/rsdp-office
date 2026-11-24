<script setup lang="ts">
import type { FurnitureObject } from '~/types/office'
import type { ModuleRecommendation, ModuleProductCandidate } from '~/types/moduleRecommendation'
import { applyModuleProductSelection } from '~/utils/moduleProductSelection'
import { sameOriginAssetUrl } from '~/utils/apiBase'
import { money } from '~/utils/helpers'

/**
 * 「替换商品」对话框（RENDER-004）。
 * 复用既有服务端推荐/选品链路（module-recommendations / module-products）。
 * 布局参考主图+左右箭头+底部缩略图：大图浏览当前候选，下方缩略图可点选切换。
 */
const props = defineProps<{ zoneId: string; item: FurnitureObject | null; bulkMode?: boolean }>()
const show = defineModel<boolean>('show', { default: false })
const emit = defineEmits<{ (event: 'applied', message: string): void }>()

const store = useOfficeStore()
const api = useOfficeApi()

const result = ref<ModuleRecommendation | null>(null)
const loading = ref(false), applying = ref(false), error = ref('')
const selectedIndex = ref(0)
const thumbnails = ref<HTMLElement | null>(null)
let sequence = 0

const modules = computed(() => store.furnitureForZone(props.zoneId))
const allConfirmed = computed(() => modules.value.length > 0 && modules.value.every(item => item.layoutConfirmed))
const number = (value: number) => Number(value.toFixed(2)).toLocaleString()
const message = (failure: any) => failure?.data?.message || failure?.message || '请求失败，请重试'

const current = computed<ModuleProductCandidate | null>(() => result.value?.candidates[selectedIndex.value] || null)

function selectIndex(index: number) {
  if (!result.value) return
  selectedIndex.value = Math.max(0, Math.min(index, result.value.candidates.length - 1))
  nextTick(() => {
    const thumb = thumbnails.value?.children[selectedIndex.value] as HTMLElement | undefined
    thumb?.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' })
  })
}

function prev() { selectIndex(selectedIndex.value - 1) }
function next() { selectIndex(selectedIndex.value + 1) }
function onWheel(event: WheelEvent) {
  event.preventDefault()
  if (event.deltaY > 0 || event.deltaX > 0) next()
  else prev()
}

async function refresh() {
  const ticket = ++sequence
  result.value = null; error.value = ''; selectedIndex.value = 0
  const item = props.item
  if (!item || !allConfirmed.value) { loading.value = false; return }
  loading.value = true
  const revision = store.canvasRevision
  try {
    await api.saveCanvas({ objects: JSON.parse(JSON.stringify(store.objects)), files: store.files, mode: store.canvasMode,
      designZoneId: store.designZoneId, revision, blank: store.blank })
    const response = await api.recommendModule({ zoneId: props.zoneId, moduleId: item.id, canvasRevision: revision, allowRotation: true })
    if (ticket !== sequence) return
    if (!response || response.moduleId !== item.id || response.zoneId !== props.zoneId || response.canvasRevision !== revision || !Array.isArray(response.candidates)) {
      throw new Error('商品推荐接口尚未就绪，请启动最新后端')
    }
    result.value = response
  } catch (failure) {
    if (ticket === sequence) error.value = message(failure)
  } finally { if (ticket === sequence) loading.value = false }
}

async function choose() {
  const candidate = current.value
  if (!result.value || !candidate || applying.value) return
  applying.value = true; error.value = ''
  try {
    const response = await api.selectModuleProduct({ zoneId: result.value.zoneId, moduleId: result.value.moduleId,
      canvasRevision: result.value.canvasRevision, allowRotation: true, productId: candidate.product.id, applyToSameModules: props.bulkMode })
    if (!response?.canvas || !Array.isArray(response.cartItems)) throw new Error('保存结果无效，请重新读取')
    applyModuleProductSelection(store, response)
    show.value = false
    const scopeTip = props.bulkMode ? '同类型模块' : '当前模块'
    emit('applied', `已绑定商品「${candidate.product.name}」到${scopeTip}，占地保持 ${number(result.value.widthMm)}×${number(result.value.depthMm)}mm 不变，清单已同步`)
  } catch (failure) { error.value = message(failure) }
  finally { applying.value = false }
}

watch([show, () => props.item?.id], ([open]) => { if (open) void refresh() })
watch(() => result.value?.candidates.length, () => { selectedIndex.value = 0 })
</script>

<template>
  <Teleport to="body">
    <div v-if="show" class="fixed inset-0 z-[95] flex items-center justify-center bg-black/40 p-4" data-testid="white-model-product-dialog" @click.self="show = false">
      <section role="dialog" aria-modal="true" :aria-label="bulkMode ? '批量替换商品' : '替换商品'" class="flex max-h-[90vh] w-full max-w-4xl flex-col bg-white text-xs shadow-xl">
        <header class="flex flex-none items-center justify-between border-b border-line px-4 py-3">
          <div>
            <h2 class="text-sm font-bold">{{ bulkMode ? '批量替换商品' : '替换商品' }} · {{ item?.label || '家具' }}</h2>
            <p class="mt-0.5 text-[10px] text-muted">按品类相关性和尺寸贴合度推荐在售规格</p>
          </div>
          <button aria-label="关闭替换商品" class="px-2 py-1 text-muted" @click="show = false">✕</button>
        </header>
        <div class="min-h-0 flex-1 p-4" :aria-busy="loading || applying">
          <p v-if="!allConfirmed" class="rounded-none bg-amber-50 px-3 py-2.5 leading-relaxed text-amber-700" data-testid="white-model-product-blocked">
            当前空间排版尚未全部确认。请先在 2D 排版中确认当前空间，再绑定或替换真实商品。
          </p>
          <p v-else-if="loading" role="status" class="py-6 text-center text-muted">正在读取真实商品规格…</p>
          <p v-if="error" role="alert" class="mb-2 rounded-none bg-red-50 px-3 py-2 text-red-700">{{ error }}</p>
          <template v-if="result && !loading">
            <div v-if="current" class="flex flex-col gap-3" @wheel.prevent="onWheel">
              <!-- 主图 + 左右箭头 -->
              <div class="relative flex items-center justify-center rounded-none border border-line bg-[#f7f4f0]">
                <button
                  class="absolute left-2 top-1/2 z-10 flex h-8 w-8 -translate-y-1/2 items-center justify-center rounded-full bg-white/90 text-lg text-[#596252] shadow hover:bg-white disabled:opacity-30"
                  :disabled="selectedIndex === 0" aria-label="上一个商品" @click="prev"
                >◀</button>
                <img
                  v-if="current.product.imageUrl"
                  :src="sameOriginAssetUrl(current.product.imageUrl)"
                  :alt="current.product.name"
                  class="max-h-[320px] w-full object-contain p-4"
                >
                <span v-else class="grid h-64 w-full place-items-center text-[10px] text-muted">暂无图片</span>
                <button
                  class="absolute right-2 top-1/2 z-10 flex h-8 w-8 -translate-y-1/2 items-center justify-center rounded-full bg-white/90 text-lg text-[#596252] shadow hover:bg-white disabled:opacity-30"
                  :disabled="selectedIndex === result.candidates.length - 1" aria-label="下一个商品" @click="next"
                >▶</button>
                <span class="absolute bottom-2 right-2 rounded-none bg-black/60 px-2 py-0.5 text-[10px] text-white">{{ selectedIndex + 1 }} / {{ result.candidates.length }}</span>
              </div>

              <!-- 当前商品信息 -->
              <div class="flex flex-col gap-1 border-b border-line pb-3">
                <div class="flex items-baseline justify-between">
                  <h3 class="text-sm font-bold">{{ current.product.name }}</h3>
                  <strong class="text-base text-bronze">{{ current.product.price > 0 ? money(current.product.price) : '待报价' }}</strong>
                </div>
                <div class="text-[10px] text-muted">{{ current.product.variant || '规格暂无数据' }}</div>
                <div class="text-[11px]">
                  商品尺寸：{{ number(current.product.widthMm!) }}×{{ number(current.product.depthMm!) }}mm<span v-if="current.product.heightMm"> · 高 {{ number(current.product.heightMm) }}mm</span>
                  <span v-if="current.rotationDeg" class="ml-2 text-amber-700">需旋转 90° 放置</span>
                </div>
                <div class="text-[10px] text-muted">
                  <span class="font-semibold text-bronze">适配度 {{ number(current.relevanceScore) }} / 100 · {{ current.matchReason }}</span>
                  <span class="ml-2">尺寸差异：剩余宽 {{ number(current.remainingWidthMm) }} / 深 {{ number(current.remainingDepthMm) }}mm</span>
                </div>
                <p class="text-[10px] text-muted">
                  当前模块占地 {{ number(result.widthMm) }}×{{ number(result.depthMm) }}mm；绑定只更换商品，不改变布局几何。
                </p>
                <button
                  class="mt-1 w-full rounded-none bg-bronze py-2 text-sm font-semibold text-white disabled:opacity-50"
                  :disabled="applying || current.product.price <= 0"
                  @click="choose"
                >
                  {{ applying ? '保存中…' : (bulkMode ? '批量绑定该商品' : '绑定该商品') }}
                </button>
              </div>

              <!-- 底部缩略图 -->
              <div ref="thumbnails" class="flex gap-2 overflow-x-auto overflow-y-hidden pb-1">
                <button
                  v-for="(candidate, index) in result.candidates"
                  :key="candidate.product.id"
                  :data-testid="'white-model-product-option-' + candidate.product.id"
                  :class="[
                    'flex-shrink-0 rounded-none border p-1 transition hover:bg-[#f7f3ef]',
                    index === selectedIndex ? 'border-bronze ring-1 ring-bronze' : 'border-line',
                  ]"
                  @click="selectIndex(index)"
                >
                  <img
                    v-if="candidate.product.imageUrl"
                    :src="sameOriginAssetUrl(candidate.product.imageUrl)"
                    :alt="candidate.product.name"
                    class="h-12 w-12 object-contain"
                  >
                  <span v-else class="grid h-12 w-12 place-items-center text-[9px] text-muted">暂无</span>
                </button>
              </div>
            </div>
            <p v-else class="py-6 text-center text-muted">
              {{ result.targetCategory === null ? '当前家具品类未确定，请先在产品选型中选择对应图例。' : result.eligibleVariantCount === 0 ? '暂无符合推荐条件的在售规格，请核对商品和规格的在售状态。' : '暂无品类相关且尺寸可容纳的在售商品。' }}
            </p>
          </template>
        </div>
      </section>
    </div>
  </Teleport>
</template>
