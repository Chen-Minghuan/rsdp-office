<script setup lang="ts">
import SpaceTemplateDialog from './SpaceTemplateDialog.vue'
import { furnitureCatalogTemplates, furnitureCategories } from '~/utils/manualFurniture'
import { OFFICE_LAYOUT_SPACING } from '~/utils/officeAutoLayout'
import type { OfficeLayoutMode, OfficeLayoutSpacing } from '~/utils/officeAutoLayout'
import type { FurnitureObject, ZoneObject } from '~/types/office'
import type { LayoutChannel, LayoutPreferences, LayoutRequest } from '~/types/layoutPlanning'
import { defaultLayoutPreferences, planLayoutProposal, proposalDifference } from '~/utils/layoutPlanning'
import { DEFAULT_CHANNEL_WIDTH_MM, CHANNEL_WIDTH_HINT_MM } from '~/utils/layoutChannels'
import { channelEgressHints } from '~/utils/channelEgress'
import { uid } from '~/utils/helpers'
import { canvasZoneBounds } from '~/utils/officeCanvasView'
import { zoneLayoutExclusions } from '~/utils/spatial/obstacles'
import type { CustomOfficeModule, OfficeModuleTemplate } from '~/types/officeModule'
import type { OfficeArrangementPresetId, OfficeProductStyleId } from '~/types/officeArrangement'
import { moduleGlyphId } from '~/utils/officeModules'
import { defaultPresetForProductStyle, officeArrangementPreset, presetsForProductStyle, productStyleForTemplate } from '~/utils/officeArrangementPresets'

const emit = defineEmits<{ preview: [value: { zoneId: string; furniture: FurnitureObject[]; replaceIds: string[]; seatClearanceMm: number; exclusions: LayoutPreferences['exclusions'] } | null] }>()
const preferences = reactive<LayoutPreferences>(defaultLayoutPreferences())
// Off by default: entering a space shows only actually placed furniture. Selecting a module/preset or the checkbox enables the proposal preview.
const showPreview = ref(false)
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
const editingZoneName = ref(false), zoneNameDraft = ref('')
const zoneNameInput = ref<HTMLInputElement | null>(null)
function startZoneNameEdit() {
  if (!zone.value) return
  zoneNameDraft.value = zone.value.name
  editingZoneName.value = true
  nextTick(() => zoneNameInput.value?.focus())
}
function commitZoneName() {
  if (!editingZoneName.value) return
  editingZoneName.value = false
  const trimmed = zoneNameDraft.value.trim()
  if (zone.value && trimmed && trimmed !== zone.value.name) store.updateObject(zone.value.id, { name: trimmed } as Partial<ZoneObject>)
}
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
const isMeetingLayout = computed(() => selectedModule.value.category === '会议与洽谈')
const isWorkstation = computed(() => selectedModule.value.category === '职员桌')
const isGeneral = computed(() => productStyle.value === 'general')
const catalogCategories = computed(() => isMeetingSpace.value ? ['会议与洽谈', ...furnitureCategories.filter(category => category !== '会议与洽谈')] : furnitureCategories)
const nameSeatSuggestion = computed(() => Number(zone.value?.name.match(/(?:\(|（)?(\d+)\s*人/)?.[1] || 0))
const zoneBounds = computed(() => zone.value ? canvasZoneBounds(zone.value) : { minX: 0, minY: 0 })
watch(() => zone.value?.id, () => {
  Object.assign(preferences, defaultLayoutPreferences(), JSON.parse(JSON.stringify(zone.value?.layoutPreferences || {})))
  activeStep.value = 'module'
}, { immediate: true })
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

watch(() => zone.value?.id, () => {
  const item = zone.value ? store.furnitureForZone(zone.value.id).find(item => item.layoutRole === 'primary') || store.furnitureForZone(zone.value.id).find(item => item.layoutSettings) : null
  const settings = item?.layoutSettings
  let template = modules.value.find(template => template.id === item?.furnitureTemplateId) || builtInModules.find(template => template.id === item?.moduleSnapshot?.glyphTemplateId)
  // Migrate legacy screen choices to explicit modules; custom modules retain their source.
  if (template && !('custom' in template) && ['straight-screen', 'l-screen'].includes(settings?.productStyleId || '')) template = builtInModules.find(module => module.id === (settings?.productStyleId === 'l-screen' ? 'desk-screen-l' : 'desk-screen-straight')) || template
  if (template) {
    selectedModule.value = template
    unitW.value = item!.w
    unitD.value = item!.h
  } else if (zone.value) {
    const recommended = defaultModuleForZone(zone.value)
    if (selectedModule.value.id !== recommended.id) selectedModule.value = recommended
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
  else productStyle.value = productStyleForTemplate(selectedModule.value)
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
const selectedStyleLabel = computed(() => selectedPreset.value?.label || '')
const activeStepIndex = computed(() => layoutPanelSteps.findIndex(step => step.id === activeStep.value) + 1)

const selectedSize = computed(() => selectedModule.value.sizeOptions?.some(([w, d]) => w === unitW.value && d === unitD.value)
  ? `${unitW.value},${unitD.value}` : '')

const currentFurniture = computed(() => zone.value ? store.furnitureForZone(zone.value.id) : [])
// 通道直接读写 zone.layoutPreferences（画布拖动也写同一份），不走面板本地副本，避免双写分叉。
const channels = computed<LayoutChannel[]>(() => zone.value?.layoutPreferences?.channels || [])
// 通道-入口/疏散联动提示（LAYOUT-029，提示级不拦截）：入口代理为人工标注的门洞净空；首排深度取已保存排版记录。
const channelEgressMessages = computed(() => {
  const z = zone.value
  if (!z) return []
  const items = currentFurniture.value
  const firstBlockDepthMm = items.find(item => item.layoutSettings?.firstBlockDepthMm)?.layoutSettings?.firstBlockDepthMm
  return channelEgressHints(z, channels.value, items, firstBlockDepthMm)
})
function saveChannels(next: LayoutChannel[]) {
  if (!zone.value) return
  store.updateZoneChannels(zone.value.id, next)
}
function addChannel(orientation: LayoutChannel['orientation']) {
  if (!zone.value) return
  const bounds = canvasZoneBounds(zone.value)
  const center = orientation === 'vertical' ? (bounds.minX + bounds.maxX) / 2 : (bounds.minY + bounds.maxY) / 2
  saveChannels([...channels.value, { id: uid('ch'), orientation, offsetMm: Math.round(center - DEFAULT_CHANNEL_WIDTH_MM / 2), widthMm: DEFAULT_CHANNEL_WIDTH_MM }])
}
function updateChannel(id: string, patch: Partial<LayoutChannel>) {
  saveChannels(channels.value.map(channel => channel.id === id ? { ...channel, ...patch } : channel))
}
function removeChannel(id: string) {
  saveChannels(channels.value.filter(channel => channel.id !== id))
}
const buildingConstraints = computed(() => zone.value ? zoneLayoutExclusions(zone.value, false) : [])
const request = computed<LayoutRequest>(() => ({ module: selectedModule.value, widthMm: unitW.value, depthMm: unitD.value, spacing: { ...spacing, ...(isWorkstation.value ? { gapMm: 0 } : {}) }, productStyleId: productStyle.value, presetId: effectivePresetId.value, preferences: { ...JSON.parse(JSON.stringify(preferences)), channels: JSON.parse(JSON.stringify(channels.value)), strategy: isMeetingLayout.value ? preferences.strategy : 'template' } }))
const proposal = computed(() => zone.value ? planLayoutProposal(zone.value, currentFurniture.value, request.value) : null)
const difference = computed(() => proposal.value ? proposalDifference(currentFurniture.value, proposal.value) : '')
const preferencesChanged = computed(() => JSON.stringify(request.value.preferences) !== JSON.stringify(zone.value?.layoutPreferences || defaultLayoutPreferences()))
const draftApplied = computed(() => difference.value === '与当前布局一致' && !preferencesChanged.value)
const differenceText = computed(() => difference.value === '与当前布局一致' && preferencesChanged.value ? '需求或预留参数已变化，应用后保存新参数' : difference.value)
const plan = computed(() => proposal.value ? { slots: proposal.value.furniture, rows: proposal.value.rows || new Set(proposal.value.furniture.filter(f => f.layoutRole === 'primary').map(f => Math.round(f.y))).size, columns: proposal.value.columns || 0, rotated: proposal.value.rotated || false, problem: proposal.value.problem } : null)
const autoPlan = plan
const capacity = computed(() => proposal.value?.furniture.length || 0)
watch([proposal, showPreview, draftApplied], () => {
  applicationMessage.value = ''
  emit('preview', zone.value && showPreview.value ? { zoneId: zone.value.id, furniture: draftApplied.value ? [] : proposal.value?.furniture || [], replaceIds: proposal.value?.replaceIds || [], seatClearanceMm: preferences.seatClearanceMm, exclusions: preferences.exclusions } : null)
}, { immediate: true, deep: true })
onUnmounted(() => emit('preview', null))


// A manual delete/clear is an intentional removal: pause the proposal preview
// so the canvas shows only what is actually placed, instead of immediately
// re-drawing the removed modules as dimmed ghosts. Re-enable via the checkbox.
watch(() => store.lastManualFurnitureEdit, mark => { if (mark && mark.zoneId === zone.value?.id) showPreview.value = false })
function addExclusion() {
  if (!zone.value) return
  const bounds = canvasZoneBounds(zone.value)
  preferences.exclusions.push({ id: 'reserved-' + Date.now(), label: '入口或禁放区', x: bounds.minX, y: bounds.minY, w: 1000, h: 1000 })
}

function selectModule(template: OfficeModuleTemplate) {
  showPreview.value = true
  store.cancelFurniturePlacement()
  selectedModule.value = template
}

function selectPreset(id: OfficeArrangementPresetId) {
  showPreview.value = true
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
  if (!zone.value || !proposal.value || proposal.value.problem || draftApplied.value) return
  const planned = proposal.value
  const applied = store.applyLayoutProposal(zone.value.id, request.value, planned.sourceKey)
  if (!applied) { applicationMessage.value = '空间或家具已变化，请重新预览后应用'; return }
  nextTick(() => { applicationMessage.value = '已应用：' + planned.furniture.length + ' 件，保留 ' + planned.retainedIds.length + ' 件' })
}

function applyAutoLayout() {
  applyLayout()
}

function confirmLayout() {
  if (!zone.value) return
  if (store.confirmLayout(zone.value.id) === false) applicationMessage.value = '当前家具存在越界、重叠、禁放区冲突或目标不足，请检查后确认'
}

function unconfirmLayout() {
  if (!zone.value) return
  store.unconfirmLayout(zone.value.id)
}

function clearLayout() {
  if (!zone.value) return
  store.clearFurniture(zone.value.id)
  showPreview.value = false
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
          <span>家具选择与排版</span>
        </div>
        <p class="text-[10px] text-muted mt-1 pr-6 truncate" @click.stop>
          <input v-if="editingZoneName && zone" ref="zoneNameInput" v-model="zoneNameDraft" aria-label="排版空间名称" maxlength="40"
            class="w-full rounded border border-line bg-white px-1.5 py-0.5 text-[10px] font-normal text-[#4f493f]"
            @keydown.enter.prevent="commitZoneName" @keydown.esc.prevent="editingZoneName = false" @blur="commitZoneName">
          <button v-else-if="zone" type="button" class="underline decoration-dotted underline-offset-2 hover:text-bronze-dark" title="点击重命名空间" @click="startZoneNameEdit">{{ zone.name }}</button>
          <template v-else>未选择空间</template>
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
          <details v-for="category in catalogCategories" :key="category" :open="category === catalogCategories[0]" class="mb-2">
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

        <div class="px-3 py-3 border-b border-line text-[11px] space-y-2">
          <div class="font-semibold">方案需求</div>
          <div v-if="isMeetingLayout" class="flex gap-2"><button type="button" :aria-pressed="preferences.strategy === 'template'" class="flex-1 rounded border border-line py-2" @click="preferences.strategy = 'template'">{{ isMeetingLayout ? '单桌居中' : '按场地排布' }}</button><button v-if="isMeetingLayout" type="button" :aria-pressed="preferences.strategy === 'recommended'" class="flex-1 rounded border border-line py-2" @click="preferences.strategy = 'recommended'">自动推荐</button></div>
          <label class="flex items-center justify-between gap-2">{{ isGeneral ? '目标数量' : '目标人数' }}<input v-model.number="preferences.targetSeats" :aria-label="isGeneral ? '目标数量' : '目标人数'" type="number" min="0" max="500" class="w-24 rounded border border-line px-2 py-1"></label>
          <p class="text-[10px] text-muted">{{ isGeneral ? '0 表示未指定数量；多件请选择按场地排布。' : '0 表示未指定；房名中的人数需确认后使用。' }}</p>
          <button v-if="!isGeneral && nameSeatSuggestion && nameSeatSuggestion <= 500 && !preferences.targetSeats" class="text-bronze underline" @click="preferences.targetSeats = nameSeatSuggestion">采用房名建议 {{ nameSeatSuggestion }} 人</button>
          <template v-if="isMeetingLayout">
            <label class="block"><input v-model="preferences.includeChairs" type="checkbox" aria-label="生成会议椅"> 同时生成会议椅</label>
            <label class="block"><input v-model="preferences.resizeMeetingTable" type="checkbox" aria-label="按人数调整桌长"> 按目标人数调整桌长（预览实际尺寸）</label>
            <label class="flex items-center justify-between">拉椅活动预留 mm<input v-model.number="preferences.seatClearanceMm" aria-label="拉椅活动预留（毫米）" type="number" min="0" class="w-24 rounded border border-line px-2 py-1"></label>
          </template>
          <p v-if="isMeetingSpace" class="text-muted">建议会议桌椅，也可按实际需求选择任意家具。</p>
          <label class="block"><input v-model="showPreview" type="checkbox" aria-label="显示排版预览"> 显示待应用预览</label>
          <p v-if="!showPreview" class="text-[10px] text-muted mt-1">预览已关闭：画布只显示实际已放置的家具；选择模块/排布或勾选后查看方案预览，删除或清空排版会自动关闭预览。</p>
          <details><summary class="cursor-pointer">入口与禁放区 · {{ preferences.exclusions.length }}</summary>
            <p v-if="buildingConstraints.length" class="mt-2 rounded bg-[#f9eeeb] p-2 text-[10px] text-[#a24f45]">空间核对已标注 {{ buildingConstraints.length }} 处建筑约束，会自动参与避障；请返回空间核对修改。</p>
            <p class="mt-2 text-[10px] text-muted">从空间左上角起量，单位 mm；红框区域不可摆放。</p>
            <div v-for="area in preferences.exclusions" :key="area.id" class="mt-2 rounded border border-line p-2">
              <input v-model="area.label" aria-label="预留区域名称" class="w-full border border-line px-1">
              <div class="grid grid-cols-2 gap-1 mt-1"><label v-for="key in (['x', 'y', 'w', 'h'] as const)" :key="key">{{ { x: '距左', y: '距上', w: '宽', h: '深' }[key] }} mm<input :value="area[key] - (key === 'x' ? zoneBounds.minX : key === 'y' ? zoneBounds.minY : 0)" @input="area[key] = Number(($event.target as HTMLInputElement).value) + (key === 'x' ? zoneBounds.minX : key === 'y' ? zoneBounds.minY : 0)" :aria-label="'预留区域 ' + key" type="number" :min="key === 'w' || key === 'h' ? 1 : undefined" class="w-full border border-line px-1"></label></div>
              <button class="mt-1 text-bronze underline" @click="preferences.exclusions = preferences.exclusions.filter(item => item.id !== area.id)">移除预留区域</button>
            </div><button class="mt-2 text-bronze underline" @click="addExclusion">添加入口或禁放区</button>
          </details>
          <details data-testid="channel-panel"><summary class="cursor-pointer">通道 · {{ channels.length }}</summary>
            <p class="mt-2 text-[10px] text-muted">通道是排版约束：自动排版绕开通道、确认时家具不得压占；位置可直接在画布上拖动。两侧净距会在宽度之外再留出空档。宽度数值为当前策略，非规范要求。</p>
            <div v-for="channel in channels" :key="channel.id" class="mt-2 rounded border border-line p-2" :data-testid="'channel-' + channel.orientation">
              <div class="flex items-center justify-between text-[10px] font-semibold text-[#5b5751]">
                <span>{{ channel.orientation === 'vertical' ? '纵向通道' : '横向通道' }}</span>
                <button class="font-normal text-bronze underline" :aria-label="'移除' + (channel.orientation === 'vertical' ? '纵向' : '横向') + '通道'" @click="removeChannel(channel.id)">移除</button>
              </div>
              <div class="grid grid-cols-2 gap-1 mt-1">
                <label>宽度 mm<input :value="channel.widthMm" type="number" min="1" step="100" aria-label="通道宽度（毫米）" class="w-full border border-line px-1" @change="updateChannel(channel.id, { widthMm: Math.max(1, Number(($event.target as HTMLInputElement).value) || channel.widthMm) })"></label>
                <label>{{ channel.orientation === 'vertical' ? '距左' : '距上' }} mm<input :value="Math.round(channel.offsetMm - (channel.orientation === 'vertical' ? zoneBounds.minX : zoneBounds.minY))" type="number" step="100" aria-label="通道位置（毫米）" class="w-full border border-line px-1" @change="updateChannel(channel.id, { offsetMm: Number(($event.target as HTMLInputElement).value) + (channel.orientation === 'vertical' ? zoneBounds.minX : zoneBounds.minY) })"></label>
                <label>起点 mm（{{ channel.orientation === 'vertical' ? '距上' : '距左' }}，空=贯通）<input :value="channel.startMm === undefined ? '' : Math.round(channel.startMm - (channel.orientation === 'vertical' ? zoneBounds.minY : zoneBounds.minX))" type="number" step="100" aria-label="通道起点（毫米）" class="w-full border border-line px-1" @change="updateChannel(channel.id, { startMm: ($event.target as HTMLInputElement).value === '' ? undefined : Number(($event.target as HTMLInputElement).value) + (channel.orientation === 'vertical' ? zoneBounds.minY : zoneBounds.minX) })"></label>
                <label>终点 mm（空=贯通）<input :value="channel.endMm === undefined ? '' : Math.round(channel.endMm - (channel.orientation === 'vertical' ? zoneBounds.minY : zoneBounds.minX))" type="number" step="100" aria-label="通道终点（毫米）" class="w-full border border-line px-1" @change="updateChannel(channel.id, { endMm: ($event.target as HTMLInputElement).value === '' ? undefined : Number(($event.target as HTMLInputElement).value) + (channel.orientation === 'vertical' ? zoneBounds.minY : zoneBounds.minX) })"></label>
              </div>
              <label class="mt-1 block">两侧净距 mm<input :value="channel.clearanceMm || 0" type="number" min="0" step="50" aria-label="通道两侧净距（毫米）" class="w-full border border-line px-1" @change="updateChannel(channel.id, { clearanceMm: Math.max(0, Number(($event.target as HTMLInputElement).value) || 0) })"></label>
              <p v-if="channel.widthMm < CHANNEL_WIDTH_HINT_MM" data-testid="channel-width-hint" class="mt-1 text-[10px] text-amber-600">宽度低于 {{ CHANNEL_WIDTH_HINT_MM }}mm 通行提示阈值（策略提示，非规范强制）</p>
              <label v-if="channel.orientation === 'vertical'" class="mt-1 block"><input :checked="!!channel.fromSecondRow" type="checkbox" aria-label="仅从二排起" @change="updateChannel(channel.id, { fromSecondRow: ($event.target as HTMLInputElement).checked })"> 仅从二排起（首排桌列不断开）</label>
            </div>
            <div class="mt-2 flex gap-3">
              <button class="text-bronze underline" data-testid="add-channel-vertical" @click="addChannel('vertical')">添加纵向通道</button>
              <button class="text-bronze underline" data-testid="add-channel-horizontal" @click="addChannel('horizontal')">添加横向通道</button>
            </div>
            <p v-for="message in channelEgressMessages" :key="message" data-testid="channel-egress-hint" class="mt-2 text-[10px] text-amber-600">{{ message }}</p>
          </details>
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
          <p class="text-[9px] text-muted mt-1.5">图例来自共享家具模板目录，按实际毫米尺寸排版</p>
          <div class="mt-4 border-t border-[#eee7df] pt-3">
            <div class="flex items-center justify-between mb-2">
              <span class="text-[11px] font-semibold text-[#5b5751]">空间间距</span>
              <span class="text-[9px] text-muted">单位：mm</span>
            </div>
            <div class="grid grid-cols-2 gap-2 text-[10px] text-muted">
              <label v-if="!isWorkstation">{{ arrangement === 'centerAisle' ? '中央通道/排间净距' : '排间净距' }}<input v-model.number="spacing.aisleMm" aria-label="排间净距（毫米）" type="number" min="0" step="100" class="mt-1 w-full h-8 px-2 border border-line rounded-lg text-xs text-[#5b5751]"></label>
              <label v-if="isWorkstation">图例组间净距<input v-model.number="spacing.faceGapMm" aria-label="图例组间净距（毫米）" type="number" min="0" step="100" class="mt-1 w-full h-8 px-2 border border-line rounded-lg text-xs text-[#5b5751]"></label>
              <label>离墙距离（座位侧）<input v-model.number="spacing.wallMm" aria-label="离墙距离（毫米）" type="number" min="0" step="100" class="mt-1 w-full h-8 px-2 border border-line rounded-lg text-xs text-[#5b5751]"></label>
              <p class="text-[10px] text-muted -mt-1">屏风/桌板侧可靠墙；座位朝墙时按组间过道留出入位通道。</p>
              <label v-if="!isWorkstation">同排间距<input v-model.number="spacing.gapMm" aria-label="同排间距（毫米）" type="number" min="0" step="50" class="mt-1 w-full h-8 px-2 border border-line rounded-lg text-xs text-[#5b5751]"></label>
              <label v-if="isMeetingLayout && (arrangement === 'backToBack' || arrangement === 'faceToFace')">{{ arrangement === 'faceToFace' ? '面对净距' : '背靠中缝' }}<input v-model.number="pairGap" aria-label="对排中缝（毫米）" type="number" min="0" step="50" class="mt-1 w-full h-8 px-2 border border-line rounded-lg text-xs text-[#5b5751]"></label>
            </div>
            <label v-if="isMeetingLayout" class="mt-2 flex items-center gap-2 rounded-lg bg-[#faf7f3] px-2 py-1.5 text-[10px] text-muted"><input type="checkbox" aria-label="左右桌子留缝" :checked="spacing.gapMm > 0" @change="spacing.gapMm = ($event.target as HTMLInputElement).checked ? 100 : 0">启用同排留缝</label>
            <p class="mt-2 text-[9px] leading-relaxed text-muted">{{ isWorkstation ? '保持参考图的朝向、镜像和紧贴关系，按空间大小自动增加排数、列数或组合；横纵方向自动比较。' : '按当前家具实际尺寸及间距检查方案，已放置的其他家具会保留并避让。' }}</p>
          <p v-if="currentFurniture.length && !draftApplied" class="mt-3 rounded-lg border border-[#f0d9b3] bg-[#fff8e8] px-2.5 py-2 text-[10px] leading-relaxed text-[#8a6a2c]">
            {{ differenceText }}。画布虚线为待应用预览，原排版保留。
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
          <div class="text-[11px] font-semibold text-[#5b5751] mt-3 mb-2">当前家具支持的排布</div><p v-if="isWorkstation" class="text-[10px] text-muted mb-2">数量由实际空间决定。0 人表示尽量排满；指定人数后优先满足目标，对坐、T/C/十字保持完整单元。完整单元放不下的边缘余量会以单件沿墙补位，座位朝内。座椅为座席示意，可另行选配。</p>
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
          <div v-if="capacity && plan?.rows" data-testid="layout-row-summary" class="mt-1 text-[10px] text-muted">{{ isMeetingLayout ? '桌椅组合' : `${plan.rows} 排 · 最多 ${plan.columns} 列 · ${plan.rotated ? '纵向' : '横向'}` }} · {{ proposal?.furniture.filter(item => item.layoutRole === 'primary').length }} 件主体家具</div>
          <p v-if="currentFurniture.length && capacity && !draftApplied" data-testid="layout-pending" class="mt-2 rounded-lg border border-[#f0d9b3] bg-[#fff8e8] px-2 py-1.5 text-[10px] leading-relaxed text-[#8a6a2c]">{{ differenceText }}</p>
          <p v-if="proposal && !proposal.problem" data-testid="layout-seat-summary" class="mt-1 text-[10px] text-muted">{{ isMeetingLayout ? '已排座位' : isGeneral ? '家具数量' : '工位' }} {{ proposal.seats }} · 目标 {{ preferences.targetSeats || '未指定' }}<span v-if="proposal.shortfall" class="text-[#a65e4c]"> · 尚缺 {{ proposal.shortfall }} {{ isGeneral ? '件' : '人' }}</span></p>
          <p v-if="isMeetingLayout && proposal?.furniture.length" class="mt-1 text-[10px] text-muted">本次桌面实际 {{ Math.round(proposal.furniture[0]!.w) }} × {{ Math.round(proposal.furniture[0]!.h) }} mm</p>
          <p v-if="draftApplied" data-testid="layout-unchanged" class="mt-1 text-[10px] text-green">与当前布局一致</p>
          <p v-if="proposal" class="mt-1 text-[9px] text-muted">门窗、柱及连续通道待现场核验；可添加预留区域。</p>
          <p v-if="applicationMessage" role="status" class="mt-1 text-[10px] text-[#56636e]">{{ applicationMessage }}</p>
          <p v-if="plan?.problem" role="status" class="mt-1 text-[10px] text-[#a65e4c]">{{ plan.problem }}</p>
        </div>

        <!-- Actions -->
        <div class="px-3 py-3 mt-auto border-t border-line flex flex-col gap-2 flex-none bg-white">
          <div v-if="store.furniturePlacement" class="text-[10px] text-muted">点击空间内放置，R 旋转，Esc 结束。<button class="ml-1 text-bronze underline" @click="store.rotateFurniturePlacement()">旋转 90°</button></div>
          <div v-if="autoPlan?.slots.length" class="flex items-center justify-between rounded-lg bg-[#faf7f3] px-2.5 py-1.5 text-[10px] text-muted">
            <span>{{ proposal?.label || '当前方案' }}</span>
            <strong data-testid="auto-layout-capacity" class="text-[#5d554c]">{{ autoPlan.slots.length }} 件</strong>
          </div>
          <button
            data-testid="apply-layout"
            class="w-full px-4 py-2.5 rounded-xl text-xs font-semibold text-white bg-bronze hover:bg-bronze-dark flex items-center justify-center gap-1.5 shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="!autoPlan?.slots.length || !!proposal?.problem || draftApplied"
            @click="applyAutoLayout"
          >
            <AppIcon name="cube" size="3.5" />
            <span>{{ draftApplied ? '与当前布局一致' : isMeetingLayout && preferences.strategy === 'recommended' ? '应用自动推荐方案' : '应用当前方案' }}</span>
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
            <AppIcon name="reset" size="3.5" />
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
