<script setup lang="ts">
import type { FurnitureObject, ZoneObject } from '~/types/office'
import type { OfficeModuleTemplate } from '~/types/officeModule'
import { furnitureById } from '~/utils/manualFurniture'
import { ZONE_TYPES } from '~/utils/sample'

const store = useOfficeStore()
const selectedId = defineModel<string | null>('selectedId')
/** 画布上实时（可能已被用户拖动调整）的组合预览，应用时需以它为准覆盖生成位置。 */
defineProps<{ currentPreview?: { zoneId: string; furniture: FurnitureObject[]; replaceIds: string[]; seatClearanceMm: number } | null }>()
const emit = defineEmits<{ preview: [value: { zoneId: string; furniture: FurnitureObject[]; replaceIds: string[]; seatClearanceMm: number } | null] }>()
type WorkflowPage = 'auto' | 'products' | 'recommendations'
type AutoLayoutPanel = { selectModule: (module: OfficeModuleTemplate) => void; applyAutoLayout: () => void }
const activePage = ref<WorkflowPage>('auto')
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
    if (selectedCategories.value.length === 1) autoLayout.value?.selectModule(module)
  }
}
function selectModule(module: OfficeModuleTemplate) {
  selectedCatalogId.value = module.id
  selectedCategories.value = [module.category === '会议与洽谈' ? 'meeting' : module.category === '接待休闲' ? 'sofa' : module.category === '前台' ? 'bar' : module.category === '收纳' ? 'entry' : 'workstation']
  activePage.value = 'auto'
  nextTick(() => autoLayout.value?.selectModule(module))
}
function confirmAndRecommend() {
  if (!zone.value) return
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
          @click="activePage = page.id"
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
      <OfficeCanvasMixedAutoLayoutPanel v-if="activePage === 'auto' && selectedCategories.length !== 1" :category-ids="selectedCategories" :current-preview="currentPreview" @preview="emit('preview', $event)" />
      <OfficeCanvasDesignLayoutSidebar v-else-if="activePage === 'auto'" ref="autoLayout" embedded :module-id="selectedCatalogId" :current-preview="currentPreview" @preview="emit('preview', $event)" />
      <OfficeCanvasProductLayoutEditor v-else-if="activePage === 'products'" v-model:selected-id="selectedId" v-model:selected-catalog-id="selectedCatalogId" @navigate="activePage = 'auto'" @confirmed="activePage = 'recommendations'" @select-module="selectModule" />
      <OfficeCanvasDesignSidebar v-else embedded :selected-id="selectedId" />
    </div>
    <div v-if="activePage === 'auto' && hasLayout" v-show="!collapsed" class="flex-none border-t border-line bg-[#fafbf7] px-3 py-2.5" data-testid="auto-layout-next-actions">
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
