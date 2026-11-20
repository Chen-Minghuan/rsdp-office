<script setup lang="ts">
import type { ZoneObject } from '~/types/office'
import type { LayoutPreview } from '~/types/layoutPlanning'
import type { LayoutReferenceEditing, LayoutReferenceRecognitionTask } from '~/types/layoutPlanning'
import type { OfficeModuleTemplate } from '~/types/officeModule'
import { furnitureById } from '~/utils/manualFurniture'
import { ZONE_TYPES } from '~/utils/sample'
import { REFERENCE_TEMPLATES } from '~/utils/mixedLayout'

const store = useOfficeStore()
const api = useOfficeApi()
const selectedId = defineModel<string | null>('selectedId')
const referenceEditing = defineModel<LayoutReferenceEditing | null>('referenceEditing', { required: true })
/** 画布上实时（可能已被用户拖动调整）的排版预览，应用时以它为准。 */
const props = defineProps<{ currentPreview?: LayoutPreview | null }>()
const emit = defineEmits<{ preview: [value: LayoutPreview | null] }>()
type WorkflowPage = 'auto' | 'products' | 'recommendations'
type AutoLayoutPanel = { selectModule: (module: OfficeModuleTemplate) => void }
const activePage = ref<WorkflowPage>('auto')
watch(activePage, () => store.cancelFurniturePlacement())
const recognition = reactive<LayoutReferenceRecognitionTask>({
  status: 'idle', workspaceId: '', analysisId: '', revision: 0, zoneId: '', message: '', result: null, sequence: 0,
})
const plan = computed(() => store.floorPlan)
const recognitionKey = (analysisId: string, revision: number, zoneId: string) => `${store.workspaceId}|${analysisId}|${revision}|${zoneId}`
const currentRecognitionKey = () => recognitionKey(plan.value?.analysisId || '', plan.value?.analysisRevision || 1, store.designZoneId || '')
function resetRecognition() {
  recognition.sequence += 1
  Object.assign(recognition, { status: 'idle', workspaceId: store.workspaceId, analysisId: plan.value?.analysisId || '', revision: plan.value?.analysisRevision || 0, zoneId: store.designZoneId || '', message: '', result: null })
}
watch(() => [store.workspaceId, store.designZoneId, plan.value?.analysisId, plan.value?.analysisRevision], resetRecognition, { immediate: true })
async function recognizeReference(payload: { image: Blob; analysisId: string; revision: number; zoneId: string }) {
  if (recognition.status === 'running') return
  const key = recognitionKey(payload.analysisId, payload.revision, payload.zoneId)
  const sequence = recognition.sequence + 1
  Object.assign(recognition, { status: 'running', workspaceId: store.workspaceId, analysisId: payload.analysisId, revision: payload.revision, zoneId: payload.zoneId, message: '', result: null, sequence })
  try {
    const detected = await api.recognizeLayoutReference(payload.image, payload.analysisId, payload.revision)
    if (sequence !== recognition.sequence || key !== currentRecognitionKey()) return
    const valid = detected.filter(mark => furnitureById.has(mark.templateId) && (REFERENCE_TEMPLATES[mark.categoryId] || []).includes(mark.templateId))
    const state = referenceEditing.value
    if (state?.zoneId === payload.zoneId) {
      const manual = state.marks.filter(mark => mark.confidence === 'manual')
      const newMarks = valid.filter(mark => !manual.some(item => item.categoryId === mark.categoryId
        && Math.hypot(item.x + item.width / 2 - mark.x - mark.width / 2, item.y + item.depth / 2 - mark.y - mark.depth / 2) < 0.04))
      referenceEditing.value = { ...state, marks: [...manual, ...newMarks], selected: null, placement: null }
    }
    Object.assign(recognition, { status: 'success', result: valid, message: valid.length ? `识别到 ${valid.length} 个家具标记，请核对图例、方向和分组` : '裁图中未识别到可靠家具，可以在画布手动添加' })
  } catch (error) {
    if (sequence !== recognition.sequence || key !== currentRecognitionKey()) return
    Object.assign(recognition, { status: 'error', result: null, message: error instanceof Error ? error.message : '识别失败，可手动添加' })
  }
}
function openFurniture(id: string, fromGroup: boolean) {
  if (!store.designZoneId || !store.furnitureForZone(store.designZoneId).some(item => item.id === id)) return
  if (fromGroup && activePage.value !== 'recommendations') return
  selectedId.value = id
  if (activePage.value !== 'recommendations') activePage.value = 'products'
  collapsed.value = false
}
defineExpose({ openFurniture })
const collapsed = ref(false), autoLayout = ref<AutoLayoutPanel | null>(null), actionMessage = ref('')
const pages: Array<{ id: WorkflowPage; label: string; testId: string }> = [
  { id: 'auto', label: '自动布局', testId: 'tab-auto-layout' },
  { id: 'products', label: '产品选型', testId: 'tab-product-selection' },
  { id: 'recommendations', label: '款式推荐', testId: 'tab-style-recommendation' },
]
const categories = [
  { id: 'workstation', label: '办公桌椅', moduleId: 'desk-straight' },
  { id: 'sofa', label: '沙发茶几', moduleId: 'sofa-double' },
  { id: 'meeting', label: '小型会议', moduleId: 'table-conference-rect' },
  { id: 'tea', label: '茶桌茶椅', moduleId: 'coffee-round' },
  { id: 'dining', label: '餐厅桌椅', moduleId: 'table-meeting-round' },
  { id: 'entry', label: '玄关模块', moduleId: 'cabinet-low' },
  { id: 'leisure', label: '娱乐家具', moduleId: 'chair-lounge' },
  { id: 'bar', label: '厨房吧台', moduleId: 'counter-straight' },
  { id: 'lounge', label: '休闲会客', moduleId: 'sofa-triple' },
]
// 进入排版默认不选中任何功能模块，由用户按需点选，避免未操作就生成办公桌椅预览。
const selectedCategories = ref<string[]>([]), selectedCatalogId = ref('')
const zone = computed(() => store.zones.find(item => item.id === store.designZoneId) || null)
const hasLayout = computed(() => zone.value ? store.furnitureForZone(zone.value.id).length > 0 : false)
const zoneType = computed({
  get: () => zone.value?.zoneType || 'OTHER',
  set: (value: ZoneObject['zoneType']) => {
    if (zone.value && value !== zone.value.zoneType) store.updateZoneType(zone.value.id, value)
  },
})
watch(() => store.designZoneId, () => {
  activePage.value = 'auto'
  selectedCategories.value = []
  selectedCatalogId.value = ''
}, { immediate: true })
function selectCategory(id: string) {
  selectedCategories.value = selectedCategories.value.includes(id) ? selectedCategories.value.filter(item => item !== id) : [...selectedCategories.value, id]
  const category = categories.find(item => item.id === id)
  const module = category ? furnitureById.get(category.moduleId) : null
  if (module) {
    selectedCatalogId.value = module.id
    if (selectedCategories.value.length === 1) nextTick(() => autoLayout.value?.selectModule(module))
  }
}
function selectModule(module: OfficeModuleTemplate) {
  selectedCatalogId.value = module.id
  selectedCategories.value = [module.category === '会议与洽谈' ? 'meeting' : module.category === '接待休闲' ? 'sofa' : module.category === '前台' ? 'bar' : module.category === '收纳' ? 'entry' : 'workstation']
  activePage.value = 'auto'
  nextTick(() => autoLayout.value?.selectModule(module))
}
/** 页签切换只切页签；点击图例回填模块由 selectModule 自己处理，避免覆盖已有多选。 */
function openPage(page: WorkflowPage) {
  activePage.value = page
}
function confirmAndRecommend() {
  if (!zone.value) return
  if (props.currentPreview?.zoneId === zone.value.id && props.currentPreview.furniture.length) {
    actionMessage.value = '当前预览尚未应用，请先应用当前预览，再确认排版'
    return
  }
  const problem = store.layoutConfirmProblem(zone.value.id)
  if (problem) { actionMessage.value = problem; return }
  if (store.confirmLayout(zone.value.id) === false) { actionMessage.value = '当前家具存在越界、重叠或约束冲突，请调整后确认'; return }
  actionMessage.value = ''
  activePage.value = 'recommendations'
}
function clearAutomaticLayout() {
  if (!zone.value) return
  store.clearFurniture(zone.value.id)
  store.unconfirmLayout(zone.value.id)
  selectedCategories.value = []
  selectedCatalogId.value = ''
  selectedId.value = null
  actionMessage.value = ''
  emit('preview', null)
}
</script>

<template>
  <aside :class="['flex flex-none flex-col overflow-hidden border-y border-r border-line bg-white', collapsed ? 'w-12' : 'w-[360px]']" data-testid="design-workflow-sidebar">
    <div v-show="!collapsed" class="flex-none border-b border-line px-3 py-2.5">
      <div class="mb-2 flex items-center justify-between gap-2">
        <button class="inline-flex items-center gap-1 text-[11px] text-muted hover:text-[#3f7f52]" @click="store.setCanvasMode('overview')">
          <AppIcon name="arrowLeft" size="3.5" />
          <span>返回空间总览</span>
        </button>
        <span class="min-w-0 flex-1 truncate text-right text-xs font-semibold text-[#4f493f]" :title="zone?.name">{{ zone?.name || '未选择空间' }}</span>
        <button class="flex-none p-0.5 text-muted hover:bg-[#f7f3ef]" title="收起侧边栏" @click="collapsed = true"><AppIcon name="arrowRight" size="3.5" /></button>
      </div>
      <div class="grid grid-cols-3 gap-1" role="tablist" aria-label="布局选品页面">
        <button
          v-for="page in pages"
          :key="page.id"
          type="button"
          role="tab"
          :data-testid="page.testId"
          :aria-selected="activePage === page.id"
          :class="['rounded-none px-2 py-1.5 text-[11px] font-medium transition', activePage === page.id ? 'bg-[#83d94f] text-[#214326]' : 'bg-[#f4f5f1] text-[#6f776a] hover:bg-[#e8eee1']"
          @click="openPage(page.id)"
        >{{ page.label }}</button>
      </div>
    </div>

    <template v-if="activePage === 'auto' && zone">
      <div v-show="!collapsed" class="flex-none border-b border-line bg-[#fafbf7] px-3 py-2">
        <div class="flex items-center justify-between gap-2">
          <label class="flex min-w-0 items-center gap-2 text-[11px] text-[#596252]">
            <span class="flex-none font-semibold">房间类型</span>
            <select v-model="zoneType" aria-label="布局房间类型" title="采用识别初步判断；修改后重新核验当前排版" class="min-w-0 rounded-none border border-[#cbd8c1] bg-white px-2 py-1 text-[11px] text-[#38523a]">
              <option v-for="[value, label] in ZONE_TYPES" :key="value" :value="value">{{ label }}</option>
            </select>
          </label>
          <span class="flex-none text-[9px] text-muted"><span>尺寸（毫米）</span> {{ Math.round(zone.w) }} × {{ Math.round(zone.h) }}</span>
        </div>
      </div>

      <div v-show="!collapsed" class="flex-none border-b border-line px-3 py-2.5">
        <div class="flex items-center justify-between">
          <h3 class="text-[11px] font-semibold text-[#56614f]">功能模块</h3>
          <span class="text-[9px] text-muted">可多选 · 不限制后续选品</span>
        </div>
        <div class="mt-2 grid grid-cols-3 gap-1.5" data-testid="functional-categories">
          <button
            v-for="category in categories"
            :key="category.id"
            type="button"
            :data-testid="'functional-category-' + category.id"
            :aria-pressed="selectedCategories.includes(category.id)"
            :class="['rounded-none px-1.5 py-1.5 text-[10px]', selectedCategories.includes(category.id) ? 'bg-[#83d94f] text-[#214326]' : 'bg-[#f4f5f1] text-[#687260] hover:bg-[#e8eee1]']"
            @click="selectCategory(category.id)"
          >{{ category.label }}</button>
        </div>
        <p class="mt-1 text-[9px] text-muted">多选后按组合生成，全部家具均可在产品选型中继续调整。</p>
      </div>

    </template>

    <div v-show="!collapsed" class="flex min-h-0 flex-1 flex-col">
      <OfficeCanvasMixedAutoLayoutPanel v-if="activePage === 'auto' && selectedCategories.length !== 1" v-model:reference-editing="referenceEditing" :category-ids="selectedCategories" :current-preview="currentPreview" :recognition="recognition" @recognize="recognizeReference" @preview="emit('preview', $event)" />
      <OfficeCanvasDesignLayoutSidebar v-else-if="activePage === 'auto'" ref="autoLayout" embedded :module-id="selectedCatalogId" :current-preview="currentPreview" @preview="emit('preview', $event)" />
      <OfficeCanvasProductLayoutEditor v-else-if="activePage === 'products'" v-model:selected-id="selectedId" v-model:selected-catalog-id="selectedCatalogId" @confirmed="activePage = 'recommendations'" />
      <OfficeCanvasDesignSidebar v-else embedded v-model:selected-id="selectedId" />
    </div>
    <div v-if="activePage === 'auto' && hasLayout && !referenceEditing" v-show="!collapsed" class="flex-none border-t border-line bg-[#fafbf7] px-3 py-2.5" data-testid="auto-layout-next-actions">
      <p class="mb-2 text-[9px] leading-relaxed text-muted">可直接确认并推荐款式，也可进入产品选型手动调整，无需按固定顺序操作。</p>
      <div class="grid grid-cols-2 gap-1.5">
        <button data-testid="auto-manual-layout" class="rounded-none border border-line bg-white px-2 py-2 text-[10px] font-semibold text-[#596252]" @click="activePage = 'products'">产品选型 · 手动调整</button>
        <button data-testid="auto-confirm-recommend" class="rounded-none bg-[#83d94f] px-2 py-2 text-[10px] font-semibold text-[#214326]" @click="confirmAndRecommend">确认排版并进入款式推荐</button>
      </div>
      <button data-testid="auto-clear-layout" title="清空当前空间家具，并取消全部已选功能模块" class="mt-1.5 w-full rounded-none border border-line bg-white px-3 py-1.5 text-[10px] text-muted hover:text-[#a65e4c]" @click="clearAutomaticLayout">清空排版</button>
      <p v-if="actionMessage" role="status" class="mt-1.5 text-[10px] leading-relaxed text-[#a65e4c]">{{ actionMessage }}</p>
    </div>
    <div v-if="collapsed" class="flex h-full flex-col items-center py-3">
      <button class="p-1.5 text-muted hover:bg-[#f7f3ef]" title="展开侧边栏" @click="collapsed = false"><AppIcon name="arrowLeft" size="5" /></button>
      <span class="mt-3 text-[10px] text-muted" style="writing-mode: vertical-rl">布局选品</span>
    </div>
  </aside>
</template>
