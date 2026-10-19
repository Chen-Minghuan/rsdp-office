<script setup lang="ts">
import SpaceTemplateDialog from './SpaceTemplateDialog.vue'
import { furnitureCatalogTemplates, furnitureCategories } from '~/utils/manualFurniture'
import { planMeetingFurniture, planStrictArrangement, OFFICE_LAYOUT_SPACING } from '~/utils/officeAutoLayout'
import type { OfficeLayoutMode, OfficeLayoutSpacing } from '~/utils/officeAutoLayout'
import { furnitureMatchesPlan } from '~/utils/officeFurniture'
import type { CustomOfficeModule, OfficeModuleTemplate } from '~/types/officeModule'
import type { OfficeArrangementPresetId, OfficeProductStyleId } from '~/types/officeArrangement'
import { moduleGlyphId } from '~/utils/officeModules'
import { OFFICE_MEETING_PRODUCT_STYLES, OFFICE_PRODUCT_STYLES, defaultPresetForProductStyle, officeArrangementPreset, presetsForProductStyle, productStyleForTemplate, OFFICE_ARRANGEMENT_RULE_VERSION } from '~/utils/officeArrangementPresets'

const store = useOfficeStore()
const templateRouter = useRouter()
const collapsed = ref(false)
const showTemplate = ref(false)
const applicationMessage = ref('')
type LayoutPanelStep = 'module' | 'settings' | 'style'
const activeStep = ref<LayoutPanelStep>('module')
const layoutPanelSteps: Array<{ id: LayoutPanelStep; label: string; shortLabel: string }> = [
  { id: 'module', label: '选择模块', shortLabel: '模块' },
  { id: 'settings', label: '调整参数', shortLabel: '参数' },
  { id: 'style', label: '选择排布', shortLabel: '排布' },
]

const zone = computed(() => store.zones.find((z) => z.id === store.designZoneId) || null)
const layoutConfirmed = computed(() => zone.value ? store.layoutConfirmedForZone(zone.value.id) : false)

const builtInModules = furnitureCatalogTemplates
const modules = computed(() => [...builtInModules, ...store.customModules])
const selectedModule = ref<OfficeModuleTemplate>(builtInModules[0]!)
const showModuleDialog = ref(false)
const editingModule = ref<CustomOfficeModule | null>(null)
const lastDeletedModule = ref<CustomOfficeModule | null>(null)
const unitW = ref(0)
const unitD = ref(0)
const arrangement = ref<OfficeLayoutMode>('auto')
const productStyle = ref<OfficeProductStyleId>(productStyleForTemplate(builtInModules[0]!))
const presetId = ref<OfficeArrangementPresetId>(defaultPresetForProductStyle(productStyle.value).id)
const spacing = reactive<OfficeLayoutSpacing>({ ...OFFICE_LAYOUT_SPACING })
const isMeetingSpace = computed(() => zone.value?.zoneType === 'MEETING_ROOM')
const isMeetingLayout = computed(() => isMeetingSpace.value || selectedModule.value.category === '会议与洽谈')
const availableProductStyles = computed(() => isMeetingLayout.value ? OFFICE_MEETING_PRODUCT_STYLES : OFFICE_PRODUCT_STYLES)
const pairGap = computed({
  get: () => arrangement.value === 'faceToFace' ? spacing.faceGapMm ?? 900 : spacing.backGapMm ?? 0,
  set: (value: number) => { if (arrangement.value === 'faceToFace') spacing.faceGapMm = value; else spacing.backGapMm = value },
})

watch(selectedModule, (p) => {
  if (p) {
    unitW.value = p.widthMm
    unitD.value = p.depthMm
    const detectedStyle = productStyleForTemplate(p)
    productStyle.value = detectedStyle
    if (!presetsForProductStyle(productStyle.value).some(preset => preset.id === presetId.value)) selectPreset(defaultPresetForProductStyle(productStyle.value).id)
  }
}, { immediate: true, flush: 'sync' })

function defaultModuleForZone(currentZone: typeof zone.value) {
  if (currentZone?.zoneType === 'MEETING_ROOM') return builtInModules.find(module => module.category === '会议与洽谈') || builtInModules[0]!
  return builtInModules[0]!
}

watch([() => zone.value?.id, () => zone.value ? store.furnitureForZone(zone.value.id)[0]?.id : undefined], () => {
  const item = zone.value ? store.furnitureForZone(zone.value.id)[0] : null
  const settings = item?.layoutSettings
  const template = modules.value.find(template => template.id === item?.furnitureTemplateId) || builtInModules.find(template => template.id === item?.moduleSnapshot?.glyphTemplateId)
  if (template) {
    selectedModule.value = template
    unitW.value = item!.w
    unitD.value = item!.h
  } else if (zone.value && !item) {
    const recommended = defaultModuleForZone(zone.value)
    if (selectedModule.value.id !== recommended.id) selectModule(recommended)
  }
  if (!settings || !['auto', 'row', 'grid', 'backToBack', 'perimeter', 'faceToFace', 'staggered', 'centerAisle'].includes(settings.mode)) {
    if (isMeetingLayout.value) {
      productStyle.value = 'meeting-table'
      presetId.value = 'meeting-center'
      arrangement.value = 'auto'
      Object.assign(spacing, OFFICE_LAYOUT_SPACING, { wallMm: 1200, aisleMm: 1200 })
    } else {
      const defaultPreset = defaultPresetForProductStyle(productStyle.value)
      presetId.value = defaultPreset.id
      arrangement.value = defaultPreset.baseMode
      Object.assign(spacing, OFFICE_LAYOUT_SPACING, defaultPreset.defaults)
    }
    return
  }
  arrangement.value = settings.mode
  if (isMeetingLayout.value) productStyle.value = 'meeting-table'
  else if (settings.productStyleId) productStyle.value = settings.productStyleId
  if (settings.arrangementPresetId) presetId.value = settings.arrangementPresetId
  else {
    const legacyPreset: Partial<Record<OfficeLayoutMode, OfficeArrangementPresetId>> = {
      auto: 'generic-auto', row: 'generic-row', grid: 'generic-grid', backToBack: 'generic-back-to-back',
      faceToFace: 'generic-face-to-face', staggered: 'generic-staggered', centerAisle: 'generic-center-aisle', perimeter: 'generic-perimeter',
    }
    presetId.value = legacyPreset[settings.mode] || 'generic-auto'
  }
  if (!presetsForProductStyle(productStyle.value).some(preset => preset.id === presetId.value)) presetId.value = defaultPresetForProductStyle(productStyle.value).id
  delete spacing.pairMm
  const meetingDefaults = isMeetingLayout.value && settings.layoutContext !== 'meeting' ? { wallMm: 1200, aisleMm: 1200 } : {}
  const savedSpacing = isMeetingLayout.value && settings.layoutContext !== 'meeting' ? {} : settings.spacing
  Object.assign(spacing, OFFICE_LAYOUT_SPACING, meetingDefaults, savedSpacing)
  if (settings.spacing.pairMm !== undefined && settings.spacing.faceGapMm === undefined && settings.mode === 'faceToFace') spacing.faceGapMm = settings.spacing.pairMm
  if (settings.spacing.pairMm !== undefined && settings.spacing.backGapMm === undefined && settings.mode === 'backToBack') spacing.backGapMm = settings.spacing.pairMm
}, { immediate: true })

const availablePresets = computed(() => presetsForProductStyle(isMeetingLayout.value ? 'meeting-table' : productStyle.value))
// Older saved layouts may only contain the legacy generic mode. Once a
// product style is selected, keep the preview and the apply action on the
// material-defined template instead of silently falling back to the old
// room-width based planner.
const effectivePresetId = computed<OfficeArrangementPresetId>(() => {
  if (isMeetingLayout.value) return 'meeting-center'
  return availablePresets.value.some(preset => preset.id === presetId.value)
    ? presetId.value
    : defaultPresetForProductStyle(productStyle.value).id
})
// Only a preset in the current style's allow-list can be shown or applied.
// This also prevents an older, now-retired preset from leaking back into the
// summary when a saved layout is opened.
const selectedPreset = computed(() => availablePresets.value.find(preset => preset.id === effectivePresetId.value) || availablePresets.value[0])
const screenGeometryPending = computed(() => !isMeetingLayout.value && productStyle.value.endsWith('screen') && !selectedModule.value.name.includes('屏风'))
const selectedStyleLabel = computed(() => availableProductStyles.value.find(style => style.id === productStyle.value)?.label || '未选择样式')
const activeStepIndex = computed(() => layoutPanelSteps.findIndex(step => step.id === activeStep.value) + 1)

const selectedSize = computed(() => selectedModule.value.sizeOptions?.some(([w, d]) => w === unitW.value && d === unitD.value)
  ? `${unitW.value},${unitD.value}` : '')

const plan = computed(() => zone.value
  ? isMeetingLayout.value
    ? planMeetingFurniture(zone.value, unitW.value, unitD.value, spacing)
    : planStrictArrangement(zone.value, unitW.value, unitD.value, productStyle.value, effectivePresetId.value, spacing)
  : null)
const capacity = computed(() => plan.value?.slots.length || 0)
const currentFurniture = computed(() => zone.value ? store.furnitureForZone(zone.value.id) : [])
const autoPlan = computed(() => zone.value
  ? isMeetingLayout.value
    ? planMeetingFurniture(zone.value, unitW.value, unitD.value, spacing)
    : plan.value
  : null)
const draftApplied = computed(() => furnitureMatchesPlan(currentFurniture.value, selectedModule.value.id, unitW.value, unitD.value, plan.value?.slots || [], moduleGlyphId(selectedModule.value), selectedModule.value.heightMm))
const mountedForDraftInvalidation = ref(false)
watch(draftApplied, applied => { if (!applied) applicationMessage.value = '' }, { flush: 'sync' })
watch([selectedModule, unitW, unitD, arrangement, productStyle, presetId, () => spacing.aisleMm, () => spacing.wallMm, () => spacing.gapMm, () => spacing.faceGapMm, () => spacing.backGapMm, () => zone.value?.id], () => {
  applicationMessage.value = ''
  // A confirmed layout is tied to its module size and spacing. Keep the
  // existing furniture visible, but require a new explicit application after
  // any input that changes the calculated slots.
  if (mountedForDraftInvalidation.value && zone.value && currentFurniture.value.length && layoutConfirmed.value && !draftApplied.value) {
    store.unconfirmLayout(zone.value.id)
  }
}, { flush: 'sync' })
onMounted(() => { mountedForDraftInvalidation.value = true })

function selectModule(template: OfficeModuleTemplate) {
  store.cancelFurniturePlacement()
  selectedModule.value = template
  activeStep.value = 'settings'
}

function selectProductStyle(styleId: OfficeProductStyleId) {
  // Keep the two selectors in sync in both directions. Product styles describe
  // the layout geometry, while the module catalog supplies the concrete glyph
  // and dimensions used by the planner. Screen styles currently reuse the
  // matching desk glyph until dedicated screen geometry is available.
  if (styleId === 'meeting-table') {
    const targetModule = builtInModules.find(module => module.category === '会议与洽谈')
    if (targetModule) selectModule(targetModule)
    productStyle.value = styleId
    presetId.value = 'meeting-center'
    arrangement.value = 'auto'
    Object.assign(spacing, OFFICE_LAYOUT_SPACING, { wallMm: 1200, aisleMm: 1200 })
    if (targetModule) {
      nextTick(() => {
        const moduleCard = Array.from(document.querySelectorAll<HTMLElement>('[data-module-id]'))
          .find(element => element.dataset.moduleId === targetModule.id)
        moduleCard?.scrollIntoView({ block: 'nearest' })
      })
    }
    return
  }
  const targetKind = styleId === 'l-desk' || styleId === 'l-screen'
    ? ['l-desk', 'rounded-l-desk']
    : ['desk']
  const targetModule = builtInModules.find(module =>
    module.category === '职员桌' && targetKind.includes(module.kind),
  )
  if (targetModule && productStyleForTemplate(targetModule) !== styleId) {
    selectModule(targetModule)
  } else if (targetModule && productStyleForTemplate(selectedModule.value) !== styleId) {
    selectModule(targetModule)
  }
  // Selecting a screen style intentionally remains a screen rule selection;
  // its desk glyph is only the current editable footprint placeholder.
  productStyle.value = styleId
  const current = availablePresets.value.find(preset => preset.id === presetId.value)
  if (!current) selectPreset(defaultPresetForProductStyle(styleId).id)

  if (targetModule) {
    nextTick(() => {
      const moduleCard = Array.from(document.querySelectorAll<HTMLElement>('[data-module-id]'))
        .find(element => element.dataset.moduleId === targetModule.id)
      moduleCard?.scrollIntoView({ block: 'nearest' })
    })
  }
}

function selectPreset(id: OfficeArrangementPresetId) {
  const preset = officeArrangementPreset(id)
  if (!preset || !preset.styleIds.includes(productStyle.value)) return
  presetId.value = id
  arrangement.value = preset.baseMode
  Object.assign(spacing, preset.defaults)
}

function goToStep(step: LayoutPanelStep) {
  activeStep.value = step
}

function openModuleDialog(module: CustomOfficeModule | null = null) {
  store.cancelFurniturePlacement()
  editingModule.value = module
  showModuleDialog.value = true
}

function moduleSaved(module: CustomOfficeModule) {
  selectedModule.value = module
  showModuleDialog.value = false
  applicationMessage.value = '自定义模块已保存在本机，可逐件添加或用于自动排版'
}

function removeModule(module: CustomOfficeModule) {
  if (!store.removeCustomModule(module.id)) return
  lastDeletedModule.value = { ...module }
  if (selectedModule.value.id === module.id) selectedModule.value = builtInModules.find(source => source.id === module.glyphTemplateId) || builtInModules[0]!
  applicationMessage.value = '已删除目录模块，已放置家具保留'
}

function undoModuleDeletion() {
  const module = lastDeletedModule.value
  if (!module) return
  if (!store.restoreCustomModule(module)) { applicationMessage.value = '目录已有同名模块，无法恢复，请先修改名称'; return }
  selectModule(store.customModules.find(item => item.id === module.id)!)
  lastDeletedModule.value = null
  applicationMessage.value = '已恢复目录模块'
}

function addToCanvas() {
  if (store.furniturePlacement) { store.cancelFurniturePlacement(); return }
  if (!zone.value || !store.beginFurniturePlacement(zone.value.id, selectedModule.value.id, unitW.value, unitD.value, { ...spacing })) {
    applicationMessage.value = '请检查当前空间、模块尺寸和间距后再添加'
  }
}

function applyLayout() {
  if (!zone.value || capacity.value <= 0) return
  const applied = store.layoutFurnitureTemplate(zone.value.id, selectedModule.value.id, unitW.value, unitD.value, arrangement.value, { ...spacing }, {
    productStyleId: productStyle.value,
    arrangementPresetId: effectivePresetId.value,
    arrangementRuleVersion: OFFICE_ARRANGEMENT_RULE_VERSION,
    layoutContext: isMeetingLayout.value ? 'meeting' : 'workstation',
  })
  if (!applied) { applicationMessage.value = '排版未应用，请检查空间和尺寸后重试'; return }
  const message = `已应用${isMeetingLayout.value ? '会议桌居中' : selectedPreset.value?.label || '当前排布'}：${store.furnitureForZone(zone.value.id).length} 件`
  nextTick(() => { applicationMessage.value = message })
}

function applyAutoLayout() {
  applyLayout()
}

function confirmLayout() {
  if (!zone.value) return
  store.confirmLayout(zone.value.id)
}

function unconfirmLayout() {
  if (!zone.value) return
  store.unconfirmLayout(zone.value.id)
}

function clearLayout() {
  if (!zone.value) return
  store.clearFurniture(zone.value.id)
  store.unconfirmLayout(zone.value.id)
}

</script>

<template>
  <OfficeCanvasCustomModuleDialog v-if="showModuleDialog" :module="editingModule" :source-id="moduleGlyphId(selectedModule)" :width-mm="unitW" :depth-mm="unitD" @close="showModuleDialog = false" @saved="moduleSaved" />
  <SpaceTemplateDialog v-if="showTemplate" @close="showTemplate = false" @layout="zoneId => templateRouter.replace({ path: '/canvas', query: { mode: 'design', zoneId } })" />
  <div :class="['flex-none bg-white border border-line rounded-2xl shadow-panel flex flex-col overflow-hidden transition-all duration-300', collapsed ? 'w-12' : 'w-80']">
    <div v-show="!collapsed" class="w-80 h-full flex flex-col">
      <!-- Header -->
      <div class="relative px-4 py-3 border-b border-line flex-none bg-gradient-to-br from-white to-[#fbf8f4]">
        <button class="absolute top-3 right-3 p-1 rounded hover:bg-[#f7f3ef] text-muted" title="收起侧边栏" @click="collapsed = true">
          <AppIcon name="arrowLeft" size="4" />
        </button>
        <div class="flex items-center gap-2 text-[15px] font-bold text-[#4f493f]">
          <AppIcon name="cube" size="4" />
          <span>单位模块与排版</span>
        </div>
        <p class="text-[10px] text-muted mt-1 pr-6 truncate">
          {{ zone ? zone.name : '未选择空间' }}
        </p>
        <div v-if="zone" class="mt-3 grid grid-cols-3 gap-1 rounded-xl bg-[#f2ede7] p-1" role="tablist" aria-label="排版步骤">
          <button
            v-for="(step, index) in layoutPanelSteps"
            :key="step.id"
            :class="['rounded-lg px-1.5 py-1.5 text-[10px] transition', activeStep === step.id ? 'bg-white text-bronze shadow-sm font-semibold' : 'text-[#847a70] hover:text-[#5f574f]']"
            :aria-label="step.label"
            :aria-selected="activeStep === step.id"
            role="tab"
            @click="goToStep(step.id)"
          >
            <span :class="['mr-0.5 inline-grid h-4 w-4 place-items-center rounded-full text-[9px]', activeStep === step.id ? 'bg-[#f0e2d3] text-bronze' : 'bg-white/70 text-[#9c9186]']">{{ index + 1 }}</span>
            {{ step.shortLabel }}
          </button>
        </div>
        <div v-if="zone" class="mt-2 flex items-center gap-1.5 text-[10px] text-[#81766b]">
          <span class="truncate">{{ selectedModule.name }}</span>
          <span class="text-[#c8b9aa]">·</span>
          <span class="truncate">{{ selectedStyleLabel }}</span>
          <span class="ml-auto flex-none rounded-full bg-[#f0e2d3] px-1.5 py-0.5 text-[9px] text-bronze">{{ activeStepIndex }}/3</span>
        </div>
      </div>

      <div v-if="store.spaceTemplate" class="template-layout-link"><span>{{ store.spaceTemplateCurrent ? '基于空间模板' : '空间已修改，模板需更新' }} · {{ store.spaceTemplate.name }}</span><button @click="showTemplate = true">查看</button></div>
      <!-- No zone -->
      <div v-if="!zone" class="flex-1 flex flex-col items-center justify-center text-center p-6 text-xs text-muted">
        <AppIcon name="zone" size="8" class="text-[#ddd8d2] mb-2" />
        <p>请在总览中选择一个功能区后进入设计</p>
      </div>

      <template v-else>
        <div class="flex-1 min-h-0 overflow-y-auto">
        <!-- Same catalog and glyphs as the manual layout workspace. -->
        <div v-if="activeStep === 'module'" class="px-3 py-3 border-b border-line" data-testid="unit-module-catalog">
          <div class="flex items-center justify-between text-[11px] font-semibold text-[#5b5751] mb-2"><span>单位模块 · 办公家具图例</span><span class="text-muted">{{ modules.length }} 种</span></div>
          <button class="w-full border border-dashed border-bronze text-bronze rounded-lg py-2 text-xs mb-2" @click="openModuleDialog()">＋ 新增单位模块</button>
          <div v-if="lastDeletedModule" role="status" class="text-[10px] text-muted mb-2 flex items-center justify-between gap-2"><span>已删除 {{ lastDeletedModule.name }}</span><button class="text-bronze underline flex-none" @click="undoModuleDeletion">撤销删除目录模块</button></div>
          <details v-if="store.customModules.length" open class="mb-2">
            <summary class="cursor-pointer py-1.5 text-[11px] text-[#5b5751]">我的模块 · 本机 {{ store.customModules.length }} 款</summary>
            <div class="grid grid-cols-2 gap-2 mt-1">
              <div v-for="module in store.customModules" :key="module.id" class="min-w-0 border border-line rounded-xl overflow-hidden">
                <button :data-module-id="module.id" :aria-label="module.name" :aria-pressed="selectedModule.id === module.id" :class="['w-full text-left p-2', selectedModule.id === module.id ? 'bg-[#f7f0e8]' : 'bg-white']" @click="selectModule(module)">
                  <div class="h-12 mb-1.5"><OfficeCanvasFurniturePreview :template-id="module.glyphTemplateId" :width-mm="module.widthMm" :depth-mm="module.depthMm" thumbnail /></div>
                  <div class="text-[10px] font-bold break-words">{{ module.name }}</div><div class="text-[9px] text-muted">{{ module.widthMm }} × {{ module.depthMm }} mm</div>
                </button>
                <div class="flex border-t border-line text-[10px]"><button class="flex-1 py-1.5 hover:bg-[#f9f7f4]" :aria-label="'编辑模块 ' + module.name" @click="openModuleDialog(module)">编辑</button><button class="flex-1 py-1.5 text-muted hover:bg-[#fff0f0]" :aria-label="'删除目录模块 ' + module.name" @click="removeModule(module)">删除</button></div>
              </div>
            </div>
          </details>
          <details v-for="category in furnitureCategories" :key="category" :open="category === furnitureCategories[0]" class="mb-2">
            <summary class="cursor-pointer py-1.5 text-[11px] text-[#5b5751]">{{ category }} <span class="text-muted">{{ builtInModules.filter(module => module.category === category).length }} 款</span></summary>
            <div class="grid grid-cols-2 gap-2 mt-1">
              <button v-for="module in builtInModules.filter(module => module.category === category)" :key="module.id"
                :data-module-id="module.id" :aria-label="module.name" :aria-pressed="selectedModule.id === module.id"
                :class="['min-w-0 p-2 rounded-xl border text-left transition', selectedModule.id === module.id ? 'bg-[#f7f0e8] border-bronze' : 'bg-white border-line hover:bg-[#f9f7f4]']"
                @click="selectModule(module)">
                <div class="h-12 w-full mb-1.5"><OfficeCanvasFurniturePreview :template-id="module.id" thumbnail /></div>
                <div class="text-[10px] font-bold">{{ module.name }}</div>
                <div class="text-[9px] text-muted">{{ module.widthMm }} × {{ module.depthMm }} mm</div>
              </button>
            </div>
          </details>
          <button class="mt-1 w-full rounded-lg border border-[#eadfd3] bg-[#fcfaf7] px-3 py-2 text-[11px] font-semibold text-[#705a43] hover:border-bronze" @click="goToStep('settings')">
            下一步：设置尺寸和间距
            <AppIcon name="arrowRight" size="3" class="ml-1 inline-block" />
          </button>
        </div>

        <!-- Dimensions -->
        <div v-if="activeStep === 'settings'" class="px-4 py-3 border-b border-line flex-none">
          <div class="text-[11px] font-semibold text-[#5b5751] mb-2">{{ selectedModule.name }} · 模块尺寸</div>
          <select v-if="selectedModule.sizeOptions?.length" aria-label="模块常用规格" class="mb-2 w-full h-8 px-2 border border-line rounded-lg text-xs" :value="selectedSize" @change="[unitW, unitD] = ($event.target as HTMLSelectElement).value.split(',').map(Number)">
            <option value="" disabled>自定义规格</option>
            <option v-for="size in selectedModule.sizeOptions" :key="size.join(',')" :value="size.join(',')">{{ size[0] }} × {{ size[1] }} mm</option>
          </select>
          <div class="flex gap-2">
            <label class="flex-1">
              <span class="text-[10px] text-muted">宽 (mm)</span>
              <input v-model.number="unitW" aria-label="模块宽度（毫米）" type="number" min="10" class="w-full h-8 px-2 border border-line rounded-lg text-xs outline-none focus:border-bronze">
            </label>
            <label class="flex-1">
              <span class="text-[10px] text-muted">深 (mm)</span>
              <input v-model.number="unitD" aria-label="模块进深（毫米）" type="number" min="10" class="w-full h-8 px-2 border border-line rounded-lg text-xs outline-none focus:border-bronze">
            </label>
          </div>
          <p class="text-[9px] text-muted mt-1.5">图例与手动布局共用，按实际毫米尺寸排版</p>
          <div class="mt-4 border-t border-[#eee7df] pt-3">
            <div class="flex items-center justify-between mb-2">
              <span class="text-[11px] font-semibold text-[#5b5751]">空间间距</span>
              <span class="text-[9px] text-muted">单位：mm</span>
            </div>
            <div class="grid grid-cols-2 gap-2 text-[10px] text-muted">
              <label>{{ arrangement === 'centerAisle' ? '中央通道/排间净距' : '排间净距' }}<input v-model.number="spacing.aisleMm" aria-label="排间净距（毫米）" type="number" min="0" step="100" class="mt-1 w-full h-8 px-2 border border-line rounded-lg text-xs text-[#5b5751]"></label>
              <label>离墙距离<input v-model.number="spacing.wallMm" aria-label="离墙距离（毫米）" type="number" min="0" step="100" class="mt-1 w-full h-8 px-2 border border-line rounded-lg text-xs text-[#5b5751]"></label>
              <label>同排间距<input v-model.number="spacing.gapMm" aria-label="同排间距（毫米）" type="number" min="0" step="50" class="mt-1 w-full h-8 px-2 border border-line rounded-lg text-xs text-[#5b5751]"></label>
              <label v-if="arrangement === 'backToBack' || arrangement === 'faceToFace'">{{ arrangement === 'faceToFace' ? '面对净距' : '背靠中缝' }}<input v-model.number="pairGap" aria-label="对排中缝（毫米）" type="number" min="0" step="50" class="mt-1 w-full h-8 px-2 border border-line rounded-lg text-xs text-[#5b5751]"></label>
            </div>
            <label class="mt-2 flex items-center gap-2 rounded-lg bg-[#faf7f3] px-2 py-1.5 text-[10px] text-muted"><input type="checkbox" aria-label="左右桌子留缝" :checked="spacing.gapMm > 0" @change="spacing.gapMm = ($event.target as HTMLInputElement).checked ? 100 : 0">启用同排留缝</label>
            <p class="mt-2 text-[9px] leading-relaxed text-muted">默认间距来自当前产品图例，可按项目现场调整。</p>
          <p v-if="currentFurniture.length && !draftApplied" class="mt-3 rounded-lg border border-[#f0d9b3] bg-[#fff8e8] px-2.5 py-2 text-[10px] leading-relaxed text-[#8a6a2c]">
            尺寸或间距已变化，当前画布保留原排版。请完成参数设置后进入“排布”，重新应用方案。
          </p>
          <button class="mt-3 w-full rounded-lg border border-[#eadfd3] bg-[#fcfaf7] px-3 py-2 text-[11px] font-semibold text-[#705a43] hover:border-bronze" @click="goToStep('style')">
            下一步：选择排布
            <AppIcon name="arrowRight" size="3" class="ml-1 inline-block" />
          </button>
          </div>
        </div>

        <!-- Product style and arrangement topology -->
        <div v-if="activeStep === 'style'" class="px-4 py-3 border-b border-line flex-none">
          <button class="mb-3 flex w-full items-center gap-2 rounded-xl border border-[#eadfd3] bg-[#fcfaf7] px-2.5 py-2 text-left transition hover:border-bronze" aria-label="修改单位模块" @click="goToStep('module')">
            <span class="grid h-9 w-9 flex-none place-items-center rounded-lg bg-white border border-[#eee5dc]">
              <OfficeCanvasFurniturePreview :template-id="moduleGlyphId(selectedModule)" :width-mm="unitW" :depth-mm="unitD" thumbnail />
            </span>
            <span class="min-w-0 flex-1">
              <span class="block truncate text-[11px] font-semibold text-[#5c544b]">{{ selectedModule.name }}</span>
              <span class="block text-[10px] text-muted">{{ unitW }} × {{ unitD }} mm · 点击更换</span>
            </span>
            <AppIcon name="arrowRight" size="3.5" class="flex-none text-[#aa8a6a]" />
          </button>
          <div class="text-[11px] font-semibold text-[#5b5751] mb-2">{{ isMeetingLayout ? '会议空间样式' : '产品样式' }}</div>
          <div class="grid grid-cols-2 gap-2">
            <button
              v-for="style in availableProductStyles"
              :key="style.id"
              :class="['px-2 py-1.5 rounded-lg text-[10px] border text-left transition', productStyle === style.id ? 'bg-bronze text-white border-bronze' : 'bg-white border-line hover:bg-[#f9f7f4]']"
              :aria-label="style.label"
              :aria-pressed="productStyle === style.id"
              @click="selectProductStyle(style.id)"
            >
              <span class="font-semibold">{{ style.label }}</span>
              <span class="block mt-0.5 opacity-75 leading-tight">{{ style.description }}</span>
            </button>
          </div>
          <p v-if="isMeetingLayout" class="mt-2 rounded-lg bg-[#eef6f2] px-2 py-1.5 text-[10px] leading-tight text-[#356451]">
            会议空间默认采用“单桌居中”，外围为拉椅和通行预留；职员桌多排只在开放办公区使用。
          </p>
          <p v-if="screenGeometryPending" class="mt-2 rounded-lg bg-[#fff8e8] px-2 py-1.5 text-[10px] leading-tight text-[#8a6a2c]">
            当前目录没有独立屏风线稿，排布槽位已严格按屏风图例生成；屏风共享线段暂按对应卡位占地显示。
          </p>
          <div class="text-[11px] font-semibold text-[#5b5751] mt-3 mb-2">排布方式</div>
          <div class="grid grid-cols-2 gap-2">
            <button
              v-for="preset in availablePresets"
              :key="preset.id"
              :class="['px-2 py-1.5 rounded-lg text-[10px] border text-left transition', presetId === preset.id ? 'bg-bronze text-white border-bronze' : 'bg-white border-line hover:bg-[#f9f7f4]']"
              :aria-label="preset.label"
              :aria-pressed="presetId === preset.id"
              @click="selectPreset(preset.id)"
            >
              <span class="font-semibold">{{ preset.label }}</span>
              <span class="block mt-0.5 opacity-75 leading-tight">{{ preset.description }}</span>
            </button>
          </div>
          <p class="mt-2 text-[10px] text-muted">{{ selectedPreset?.sourceLabel || selectedPreset?.description }}</p>
        </div>

        </div>
        <!-- Preview stats -->
        <div class="px-3 py-2.5 bg-[#f9f7f4] flex-none">
          <div class="grid grid-cols-2 gap-2">
            <div class="rounded-xl border border-[#eee5dc] bg-white px-2.5 py-2">
              <div class="text-[9px] text-[#8a8178]">当前已放置</div>
              <strong data-testid="current-furniture-count" class="mt-0.5 block text-sm text-[#5d554c]">{{ currentFurniture.length }} <span class="text-[10px] font-normal">件</span></strong>
            </div>
            <div class="rounded-xl border border-[#eadacb] bg-[#fffaf5] px-2.5 py-2">
              <div class="text-[9px] text-[#8a8178]">当前方案预估</div>
              <strong data-testid="module-capacity" class="mt-0.5 block text-sm text-bronze">{{ capacity }} <span class="text-[10px] font-normal">件</span></strong>
            </div>
          </div>
          <div v-if="capacity && plan?.rows" data-testid="layout-row-summary" class="mt-1 text-[10px] text-muted">{{ plan.rows }} 排 · 每排最多 {{ plan.columns }} 件 · {{ plan.rotated ? '纵向' : '横向' }}</div>
          <p v-if="currentFurniture.length && capacity && !draftApplied" data-testid="layout-pending" class="mt-2 rounded-lg border border-[#f0d9b3] bg-[#fff8e8] px-2 py-1.5 text-[10px] leading-relaxed text-[#8a6a2c]">画布仍是原排布 {{ currentFurniture.length }} 件；点击下方应用按钮后替换为 {{ capacity }} 件。</p>
          <p v-if="applicationMessage" role="status" class="mt-1 text-[10px] text-[#56636e]">{{ applicationMessage }}</p>
          <p v-if="plan?.problem" role="status" class="mt-1 text-[10px] text-[#a65e4c]">{{ plan.problem }}</p>
        </div>

        <!-- Actions -->
        <div class="px-3 py-3 mt-auto border-t border-line flex flex-col gap-2 flex-none bg-white">
          <div v-if="store.furniturePlacement" class="text-[10px] text-muted">点击空间内放置，R 旋转，Esc 结束。<button class="ml-1 text-bronze underline" @click="store.rotateFurniturePlacement()">旋转 90°</button></div>
          <div v-if="autoPlan?.slots.length" class="flex items-center justify-between rounded-lg bg-[#faf7f3] px-2.5 py-1.5 text-[10px] text-muted">
            <span>{{ isMeetingLayout ? '会议桌居中方案' : (selectedPreset?.label || '当前排布') }}</span>
            <strong data-testid="auto-layout-capacity" class="text-[#5d554c]">{{ autoPlan.slots.length }} 件 · {{ autoPlan.rows }} 排</strong>
          </div>
          <button
            data-testid="apply-layout"
            class="w-full px-4 py-2.5 rounded-xl text-xs font-semibold text-white bg-bronze hover:bg-bronze-dark flex items-center justify-center gap-1.5 shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="!autoPlan?.slots.length"
            @click="applyAutoLayout"
          >
            <AppIcon name="cube" size="3.5" />
            <span>{{ isMeetingLayout ? '应用单桌居中' : currentFurniture.length && !draftApplied ? `替换为${selectedPreset?.label || '当前排布'} · ${capacity}件` : `应用${selectedPreset?.label || '当前排布'}` }}</span>
          </button>
          <div class="grid grid-cols-2 gap-2">
            <button :class="['px-2.5 py-2 rounded-xl text-[11px] border font-semibold', store.furniturePlacement ? 'border-bronze bg-[#f7f0e8] text-bronze' : 'border-line hover:bg-[#f9f7f4]']" @click="addToCanvas">{{ store.furniturePlacement ? '结束逐件添加' : '逐件添加' }}</button>
            <button class="px-2.5 py-2 rounded-xl text-[11px] border border-line text-[#746d65] hover:bg-[#fff5f3] hover:text-red-500" @click="clearLayout">清空排版</button>
          </div>
          <button
            v-if="store.furnitureForZone(zone.id).length > 0 && !layoutConfirmed"
            class="w-full px-3 py-2 rounded-xl text-[11px] font-semibold bg-[#e6f5ee] text-green border border-[#d0ead9] hover:bg-[#d8f0e3] flex items-center justify-center gap-1.5"
            @click="confirmLayout"
          >
            <AppIcon name="check" size="3.5" />
            <span>确认当前排版</span>
          </button>
          <button
            v-else-if="layoutConfirmed"
            class="w-full px-3 py-2 rounded-xl text-[11px] font-semibold border border-line hover:bg-[#fff0f0] hover:text-red-500 flex items-center justify-center gap-1.5"
            @click="unconfirmLayout"
          >
            <AppIcon name="arrowPath" size="3.5" />
            <span>重新调整排版</span>
          </button>
        </div>
      </template>
    </div>

    <!-- Collapsed strip -->
    <div v-show="collapsed" class="w-12 h-full flex flex-col items-center py-3">
      <button class="p-1.5 rounded-lg hover:bg-[#f7f3ef] text-muted mb-4" title="展开侧边栏" @click="collapsed = false">
        <AppIcon name="arrowRight" size="5" />
      </button>
      <div class="text-[10px] text-muted tracking-widest" style="writing-mode: vertical-rl;">
        排版
      </div>
    </div>
  </div>
</template>

<style src="~/assets/css/space-template.css"></style>
