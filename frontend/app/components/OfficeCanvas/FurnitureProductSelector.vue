<script setup lang="ts">
import type { FurnitureObject } from '~/types/office'
import type { ModuleRecommendation, ModuleProductCandidate } from '~/types/moduleRecommendation'
import { applyModuleProductSelection } from '~/utils/moduleProductSelection'
import { sameOriginAssetUrl } from '~/utils/apiBase'
import { money } from '~/utils/helpers'

/**
 * 3D 场景的「替换商品」对话框（RENDER-004）。
 * 复用既有服务端推荐/选品链路（module-recommendations / module-products）：
 * 服务端核验布局确认状态、商品在售与规格有效性并同步清单，前端不直接改 productId。
 */
const props = defineProps<{ zoneId: string; item: FurnitureObject | null }>()
const show = defineModel<boolean>('show', { default: false })
const emit = defineEmits<{ (event: 'applied', message: string): void }>()

const store = useOfficeStore()
const api = useOfficeApi()

const result = ref<ModuleRecommendation | null>(null)
const loading = ref(false), applying = ref(false), error = ref('')
let sequence = 0

const modules = computed(() => store.furnitureForZone(props.zoneId))
const allConfirmed = computed(() => modules.value.length > 0 && modules.value.every(item => item.layoutConfirmed))
const number = (value: number) => Number(value.toFixed(2)).toLocaleString()
const message = (failure: any) => failure?.data?.message || failure?.message || '请求失败，请重试'

async function refresh() {
  const ticket = ++sequence
  result.value = null; error.value = ''
  const item = props.item
  if (!item || !allConfirmed.value) { loading.value = false; return }
  loading.value = true
  const revision = store.canvasRevision
  try {
    // 与 2D 款式推荐一致：先冲刷当前草稿，服务端按已保存几何与修订号核验。
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

async function choose(candidate: ModuleProductCandidate) {
  if (!result.value || applying.value) return
  applying.value = true; error.value = ''
  try {
    const response = await api.selectModuleProduct({ zoneId: result.value.zoneId, moduleId: result.value.moduleId,
      canvasRevision: result.value.canvasRevision, allowRotation: true, productId: candidate.product.id, applyToSameModules: false })
    if (!response?.canvas || !Array.isArray(response.cartItems)) throw new Error('保存结果无效，请重新读取')
    applyModuleProductSelection(store, response)
    show.value = false
    emit('applied', `已绑定商品「${candidate.product.name}」，模块占地保持 ${number(result.value.widthMm)}×${number(result.value.depthMm)}mm 不变，清单已同步`)
  } catch (failure) { error.value = message(failure) }
  finally { applying.value = false }
}

watch([show, () => props.item?.id], ([open]) => { if (open) void refresh() })
</script>

<template>
  <Teleport to="body">
    <div v-if="show" class="fixed inset-0 z-[95] flex items-center justify-center bg-black/40 p-4" data-testid="white-model-product-dialog" @click.self="show = false">
      <section role="dialog" aria-modal="true" aria-label="替换商品" class="flex max-h-[85vh] w-full max-w-lg flex-col bg-white text-xs shadow-xl">
        <header class="flex flex-none items-center justify-between border-b border-line px-4 py-3">
          <div>
            <h2 class="text-sm font-bold">替换商品 · {{ item?.label || '家具' }}</h2>
            <p class="mt-0.5 text-[10px] text-muted">仅展示同类别、尺寸兼容、在售且审核通过的真实商品规格</p>
          </div>
          <button aria-label="关闭替换商品" class="px-2 py-1 text-muted" @click="show = false">✕</button>
        </header>
        <div class="min-h-0 flex-1 overflow-y-auto p-4" :aria-busy="loading || applying">
          <p v-if="!allConfirmed" class="rounded-none bg-amber-50 px-3 py-2.5 leading-relaxed text-amber-700" data-testid="white-model-product-blocked">
            当前空间排版尚未全部确认。请先在 2D 排版中确认当前空间，再绑定或替换真实商品。
          </p>
          <p v-else-if="loading" role="status" class="py-6 text-center text-muted">正在读取真实商品规格…</p>
          <p v-if="error" role="alert" class="mb-2 rounded-none bg-red-50 px-3 py-2 text-red-700">{{ error }}</p>
          <template v-if="result && !loading">
            <p class="mb-2 text-[10px] text-muted">
              当前模块占地 {{ number(result.widthMm) }}×{{ number(result.depthMm) }}mm；绑定只更换商品，不改变布局几何。尺寸差异以剩余宽深展示。
            </p>
            <article v-for="candidate in result.candidates" :key="candidate.product.id" class="mb-2 border border-line p-2.5" :data-testid="'white-model-product-option-' + candidate.product.id">
              <div class="flex gap-2">
                <img v-if="candidate.product.imageUrl" :src="sameOriginAssetUrl(candidate.product.imageUrl)" alt="" class="h-14 w-14 flex-none rounded-none border border-line bg-[#f7f4f0] object-contain">
                <span v-else class="grid h-14 w-14 flex-none place-items-center rounded-none border border-dashed border-line text-[10px] text-muted">暂无图片</span>
                <div class="min-w-0">
                  <div class="font-bold">{{ candidate.product.name }}</div>
                  <div class="mt-0.5 text-[10px] text-muted">{{ candidate.product.variant || '规格暂无数据' }}</div>
                  <div class="mt-0.5">{{ number(candidate.product.widthMm!) }}×{{ number(candidate.product.depthMm!) }}mm<span v-if="candidate.product.heightMm"> · 高 {{ number(candidate.product.heightMm) }}mm</span></div>
                </div>
              </div>
              <div class="mt-1.5 text-[10px] text-muted">
                尺寸差异：剩余宽 {{ number(candidate.remainingWidthMm) }} / 深 {{ number(candidate.remainingDepthMm) }}mm
                <span v-if="candidate.rotationDeg" class="text-amber-700"> · 商品需旋转 90° 放置</span>
              </div>
              <div class="mt-1.5 flex items-center justify-between">
                <strong class="text-bronze">{{ candidate.product.price > 0 ? money(candidate.product.price) : '待报价' }}</strong>
                <button class="rounded-none bg-bronze px-3 py-1.5 text-white disabled:opacity-50" :disabled="applying || candidate.product.price <= 0" @click="choose(candidate)">
                  {{ applying ? '保存中…' : '绑定该商品' }}
                </button>
              </div>
            </article>
            <p v-if="!result.candidates.length" class="py-6 text-center text-muted">
              {{ result.eligibleVariantCount === 0 ? '暂无已上架、审核通过且有有效规格的商品，请先核对商品审核和规格。' : '暂无宽深均可容纳的在售商品。可补齐商品尺寸或调整模块后重新匹配。' }}
            </p>
          </template>
        </div>
      </section>
    </div>
  </Teleport>
</template>
