<script setup lang="ts">
import type { FurnitureObject } from '~/types/office'
import type { CustomOfficeModule, OfficeModuleTemplate } from '~/types/officeModule'
import { furnitureById, furnitureCatalogTemplates, furnitureCategories, furnitureCategoryForItem, preferredFurnitureCategory } from '~/utils/manualFurniture'
import { meetingRingDescription } from '~/utils/meetingRingTable'
import { moduleGlyphId } from '~/utils/officeModules'
import { OFFICE_LAYOUT_SPACING } from '~/utils/officeAutoLayout'
import { OFFICE_MODULE_DRAG_TYPE } from '~/utils/officeManualPlacement'

const store = useOfficeStore()
const selectedId = defineModel<string | null>('selectedId')
const emit = defineEmits<{ confirmed: []; 'select-module': [module: OfficeModuleTemplate, size?: [number, number]] }>()
const zone = computed(() => store.zones.find(item => item.id === store.designZoneId) || null)
const items = computed(() => zone.value ? store.furnitureForZone(zone.value.id) : [])
type FurnitureDisplayGroup = { representative: FurnitureObject; items: FurnitureObject[] }
const displayedItemGroups = computed<FurnitureDisplayGroup[]>(() => {
  const groups: FurnitureDisplayGroup[] = []
  for (const item of items.value) {
    const group = groups.find(entry => entry.representative.label === item.label && entry.representative.w === item.w && entry.representative.h === item.h)
    if (group) group.items.push(item)
    else groups.push({ representative: item, items: [item] })
  }
  return groups
})
const catalogCategories = computed(() => zone.value?.zoneType === 'MEETING_ROOM' ? ['会议与洽谈', ...furnitureCategories.filter(category => category !== '会议与洽谈')] : furnitureCategories)
const selectedItem = computed(() => items.value.find(item => item.id === selectedId.value) || null)
const catalog = ref<HTMLElement | null>(null)
const preferredCatalogCategory = computed(() => furnitureCategoryForItem(selectedItem.value) || preferredFurnitureCategory(zone.value))
watch([() => zone.value?.id, preferredCatalogCategory], ([, category]) => {
  nextTick(() => {
    catalog.value?.querySelectorAll<HTMLDetailsElement>('[data-catalog-category]').forEach(group => {
      const open = group.dataset.catalogCategory === category
      group.toggleAttribute('open', open)
      if (open) group.scrollIntoView({ block: 'nearest' })
    })
  })
}, { immediate: true, flush: 'post' })
const itemEditor = ref<HTMLElement | null>(null)
watch(itemEditor, editor => editor?.scrollIntoView({ block: 'nearest' }), { flush: 'post' })
const confirmed = computed(() => zone.value ? store.layoutConfirmedForZone(zone.value.id) : false)
const confirmProblem = computed(() => zone.value && items.value.length && !confirmed.value ? store.layoutConfirmProblem(zone.value.id) : null)
const editWidth = ref(0), editDepth = ref(0), message = ref('')
const selectedRingParts = computed(() => selectedItem.value ? meetingRingDescription(glyph(selectedItem.value), selectedItem.value.w, selectedItem.value.h) : '')
const showModuleDialog = ref(false), editingModule = ref<CustomOfficeModule | null>(null)
const lastDeletedModule = ref<CustomOfficeModule | null>(null)
const selectedCatalogId = defineModel<string>('selectedCatalogId', { default: '' })
const replacementMode = ref(false)
watch(() => selectedItem.value?.id, () => { replacementMode.value = false })
watch(() => [selectedItem.value?.id, selectedItem.value?.w, selectedItem.value?.h], () => {
  editWidth.value = Math.round(selectedItem.value?.w || 0)
  editDepth.value = Math.round(selectedItem.value?.h || 0)
}, { immediate: true })

function glyph(item: FurnitureObject) {
  return item.moduleSnapshot?.glyphTemplateId || item.furnitureTemplateId || 'desk-straight'
}
function groupSelected(group: FurnitureDisplayGroup) {
  return group.items.some(item => item.id === selectedId.value)
}
function resizeSelected() {
  if (!selectedItem.value) return
  const result = store.resizeFurniture(selectedItem.value.id, editWidth.value, editDepth.value)
  message.value = result || '尺寸已更新，可撤销'
}
const editableMeetingGroup = computed(() => {
  const item = selectedItem.value
  if (!item || item.layoutRole !== 'primary' || item.layoutGroupId !== `auto:${item.zoneId}` || item.layoutSettings?.layoutContext !== 'meeting') return null
  const template = furnitureById.get(item.furnitureTemplateId || '')
  return template?.reference && !['T16', 'S15'].includes(template.reference.code) ? template : null
})
function resizeMeetingGroup() {
  if (editableMeetingGroup.value) emit('select-module', editableMeetingGroup.value, [editWidth.value, editDepth.value])
}
function rotateSelected() {
  if (!selectedItem.value || !zone.value) return
  const result = store.rotateFurnitureGroup([selectedItem.value.id], zone.value.id)
  message.value = result || '已旋转 90°，可撤销'
}
function copySelected() {
  if (!selectedItem.value) return
  message.value = store.duplicateFurniture(selectedItem.value.id) || '已复制所选家具，可撤销'
}
function removeSelected() {
  if (!selectedItem.value || !zone.value) return
  if (store.deleteFurnitureGroup([selectedItem.value.id], zone.value.id)) {
    selectedId.value = null
    message.value = '已删除所选家具，可撤销'
  }
}
function clearLayout() {
  if (!zone.value) return
  store.clearFurniture(zone.value.id)
  store.unconfirmLayout(zone.value.id)
  selectedId.value = null
  message.value = '已清空当前空间排版，可撤销'
}
function addModule(module: OfficeModuleTemplate) {
  if (!zone.value) return
  selectedCatalogId.value = module.id
  selectedId.value = null
  replacementMode.value = false
  const started = store.beginFurniturePlacement(zone.value.id, module.id, module.widthMm, module.depthMm, { ...OFFICE_LAYOUT_SPACING })
  message.value = started ? '' : '当前空间或模块尺寸无法用于摆放'
}
function chooseModule(module: OfficeModuleTemplate) {
  selectedCatalogId.value = module.id
  if (replacementMode.value && selectedItem.value) {
    const result = store.replaceFurnitureModule(selectedItem.value.id, module.id)
    message.value = result || `已替换为「${module.name}」，位置与旋转保持不变，可撤销`
    replacementMode.value = false
    return
  }
  addModule(module)
}
function beginReplacement() {
  if (!selectedItem.value) return
  store.cancelFurniturePlacement()
  replacementMode.value = true
  message.value = '请点击下方图例替换当前家具'
}
function startModuleDrag(module: OfficeModuleTemplate, event: DragEvent) {
  if (!zone.value || !event.dataTransfer) return
  addModule(module)
  if (store.furniturePlacement?.template.id !== module.id) { event.preventDefault(); return }
  event.dataTransfer.effectAllowed = 'copy'
  event.dataTransfer.setData(OFFICE_MODULE_DRAG_TYPE, module.id)
}
function endModuleDrag(module: OfficeModuleTemplate, event: DragEvent) {
  if (event.dataTransfer?.dropEffect === 'copy') return
  if (store.furniturePlacement?.template.id === module.id) store.cancelFurniturePlacement()
}
function openModuleDialog(module: CustomOfficeModule | null = null) {
  store.cancelFurniturePlacement()
  editingModule.value = module
  showModuleDialog.value = true
}
function moduleSaved(module: CustomOfficeModule) {
  showModuleDialog.value = false
  selectedCatalogId.value = module.id
}
function removeModule(module: CustomOfficeModule) {
  if (!store.removeCustomModule(module.id)) return
  lastDeletedModule.value = { ...module }
  message.value = '已删除目录模块，已放置家具保留'
}
function undoModuleDeletion() {
  const module = lastDeletedModule.value
  if (!module) return
  if (!store.restoreCustomModule(module)) { message.value = '目录已有同名模块，无法恢复，请先修改名称'; return }
  lastDeletedModule.value = null
  chooseModule(module)
}
function confirmLayout() {
  if (!zone.value) return
  const problem = store.layoutConfirmProblem(zone.value.id)
  if (problem) { message.value = problem; return }
  if (store.confirmLayout(zone.value.id) === false) { message.value = '当前家具存在越界、重叠或约束冲突，请调整后确认'; return }
  emit('confirmed')
}
</script>

<template>
  <section class="flex min-h-0 flex-1 flex-col" data-testid="product-layout-editor">
    <OfficeCanvasCustomModuleDialog
      v-if="showModuleDialog"
      :module="editingModule"
      :source-id="editingModule ? moduleGlyphId(editingModule) : 'desk-straight'"
      :width-mm="editingModule?.widthMm || 1200"
      :depth-mm="editingModule?.depthMm || 600"
      @close="showModuleDialog = false"
      @saved="moduleSaved"
    />
    <div class="flex-none border-b border-line px-3 py-2.5">
      <div class="flex items-center justify-between">
        <h2 class="text-xs font-semibold text-[#42533d]">产品选型 · 手动排版</h2>
        <span class="text-[10px] text-muted"><b data-testid="current-furniture-count">{{ items.length }}</b> 件 · {{ confirmed ? '已确认' : '待确认' }}</span>
      </div>
      <p class="mt-1 text-[10px] text-muted">在画布中拖动位置；选中家具后可改尺寸、旋转、复制或进入替换模式。</p>
    </div>

    <div class="min-h-0 flex-1 overflow-y-auto">
      <div v-if="!items.length" class="px-4 py-8 text-center">
        <AppIcon name="cube" size="8" class="mx-auto mb-3 text-[#c9d3c1]" />
        <h3 class="text-xs font-semibold text-[#56614f]">从手动排版开始</h3>
        <p class="mt-2 text-[11px] leading-relaxed text-muted">点击下方图例后移动到画布点击放置，也可直接把图例拖拽到画布中。</p>
      </div>

      <template v-else>
        <section class="border-b border-line px-3 py-2.5">
          <h3 class="mb-2 text-[11px] font-semibold text-[#56614f]">已排家具</h3>
          <div class="grid gap-1">
            <button
              v-for="group in displayedItemGroups"
              :key="group.representative.id"
              type="button"
              :data-furniture-id="group.representative.id"
              :aria-pressed="groupSelected(group)"
              :class="['flex min-w-0 items-center gap-2 rounded-none border px-2 py-1.5 text-left', groupSelected(group) ? 'border-[#83c95a] bg-[#eef8e7]' : 'border-line bg-white hover:bg-[#f7f9f4]']"
              @click="selectedId = group.representative.id"
            >
              <span class="h-9 w-12 flex-none"><OfficeCanvasFurniturePreview :template-id="glyph(group.representative)" :width-mm="group.representative.w" :depth-mm="group.representative.h" thumbnail /></span>
              <span class="min-w-0 flex-1">
                <span class="block truncate text-[11px] font-medium text-[#4f493f]">{{ group.representative.label || '单位模块' }}</span>
                <span class="mt-0.5 block text-[10px] text-muted">{{ Math.round(group.representative.w) }} × {{ Math.round(group.representative.h) }} mm</span>
              </span>
              <AppIcon v-if="groupSelected(group)" name="check" size="3.5" class="flex-none text-[#55a138]" />
            </button>
          </div>

          <div v-if="selectedItem" :key="selectedItem.id" ref="itemEditor" class="mt-2 border border-[#d8e5cf] bg-[#fafcf7] p-2.5" data-testid="product-item-editor">
            <p class="mb-2 text-[11px] font-semibold text-[#4f493f]">{{ selectedItem.label || '单位模块' }}</p>
            <div class="grid grid-cols-2 gap-2">
              <label class="text-[10px] text-muted">宽度 mm<input v-model.number="editWidth" data-testid="product-item-width" type="number" min="10" step="50" class="mt-1 w-full rounded-none border border-line bg-white px-2 py-1.5 text-[11px]"></label>
              <label class="text-[10px] text-muted">深度 mm<input v-model.number="editDepth" data-testid="product-item-depth" type="number" min="10" step="50" class="mt-1 w-full rounded-none border border-line bg-white px-2 py-1.5 text-[11px]"></label>
            </div>
            <p v-if="selectedRingParts" class="mt-1 text-[10px] text-muted">桌面拼接建议 · {{ selectedRingParts }}</p>
            <div class="mt-2 grid grid-cols-4 gap-1.5">
              <button data-testid="product-item-resize" class="rounded-none bg-[#83d94f] px-1 py-1.5 text-[10px] font-semibold text-[#214326]" @click="editableMeetingGroup ? resizeMeetingGroup() : resizeSelected()">{{ editableMeetingGroup ? '预览新组合' : '应用尺寸' }}</button>
              <button class="rounded-none border border-line bg-white px-1 py-1.5 text-[10px] text-[#596252]" @click="rotateSelected">旋转 90°</button>
              <button data-testid="product-copy-furniture" class="rounded-none border border-line bg-white px-1 py-1.5 text-[10px] text-[#596252]" @click="copySelected">复制</button>
              <button class="rounded-none border border-[#ead7d2] bg-white px-1 py-1.5 text-[10px] text-[#a65e4c]" @click="removeSelected">删除</button>
            </div>
            <button data-testid="product-replace-module" class="mt-1.5 w-full border border-line bg-white px-2 py-1.5 text-[10px] text-[#596252]" @click="beginReplacement">选择图例替换当前模块</button>
          </div>
        </section>
      </template>

      <section ref="catalog" class="px-3 py-2.5" data-testid="unit-module-catalog">
        <div class="mb-2 flex items-center justify-between">
          <h3 class="text-[11px] font-semibold text-[#56614f]">家具图例</h3>
          <span class="text-[9px] text-muted">全部品类均可选择</span>
        </div>
        <p class="mb-2 text-[9px] leading-relaxed text-muted">点击图例后在画布点击放置，或直接把图例拖到画布中。</p>
        <p v-if="replacementMode" class="mb-2 bg-[#eef8e7] px-2 py-1.5 text-[10px] text-[#4d713d]">正在替换「{{ selectedItem?.label || '当前家具' }}」：请点击一个图例完成替换。<button class="ml-1 underline" @click="replacementMode = false">取消</button></p>
        <button class="mb-2 w-full border border-dashed border-[#a8c99a] px-3 py-2 text-[11px] text-[#4d713d]" @click="openModuleDialog()">＋ 新增单位模块</button>
        <div v-if="lastDeletedModule" role="status" class="mb-2 flex items-center justify-between gap-2 text-[10px] text-muted"><span>已删除 {{ lastDeletedModule.name }}</span><button class="flex-none text-[#4d713d] underline" @click="undoModuleDeletion">撤销删除目录模块</button></div>
        <details v-if="store.customModules.length" open class="mb-1.5">
          <summary class="cursor-pointer py-1.5 text-[11px] text-[#5b5751]">我的模块 · 本机 {{ store.customModules.length }} 款</summary>
          <div class="grid grid-cols-3 gap-1.5">
            <div v-for="module in store.customModules" :key="module.id" class="min-w-0 border border-line bg-white">
              <button
                :data-module-id="module.id"
                :aria-label="module.name"
                :aria-pressed="selectedCatalogId === module.id"
                :title="`${module.name}：点击后移动到画布放置，或拖拽到画布`"
                draggable="true"
                class="w-full cursor-grab p-1.5 text-left hover:bg-[#f5f9f1] active:cursor-grabbing"
                @click="chooseModule(module)"
                @dragstart="startModuleDrag(module, $event)"
                @dragend="endModuleDrag(module, $event)"
              >
                <span class="block h-9 pointer-events-none"><OfficeCanvasFurniturePreview :template-id="module.glyphTemplateId" :width-mm="module.widthMm" :depth-mm="module.depthMm" thumbnail /></span>
                <span class="block truncate text-[10px] font-medium">{{ module.name }}</span>
                <span class="block mt-0.5 text-[8px] text-muted">{{ module.widthMm }}×{{ module.depthMm }}</span>
              </button>
              <div class="flex border-t border-line text-[9px]"><button class="flex-1 py-1" :aria-label="'编辑模块 ' + module.name" @click="openModuleDialog(module)">编辑</button><button class="flex-1 py-1 text-muted" :aria-label="'删除目录模块 ' + module.name" @click="removeModule(module)">删除</button></div>
            </div>
          </div>
        </details>
        <p v-if="store.furniturePlacement" class="mb-2 bg-[#eef8e7] px-2 py-1.5 text-[10px] text-[#4d713d]">正在摆放：移动到画布点击放置，空格旋转，Esc 或右键结束。</p>
        <details v-for="category in catalogCategories" :key="category" :data-catalog-category="category" class="mb-1.5">
          <summary class="cursor-pointer py-1.5 text-[11px] text-[#5b5751]">{{ category }} <span class="text-muted">{{ furnitureCatalogTemplates.filter(module => module.category === category).length }} 款</span></summary>
          <div class="grid grid-cols-3 gap-1.5">
            <div v-for="module in furnitureCatalogTemplates.filter(module => module.category === category)" :key="module.id" class="min-w-0 border border-line bg-white">
              <button
                type="button"
                :data-module-id="module.id"
                :data-product-module-id="module.id"
                :aria-label="module.name"
                :aria-pressed="selectedCatalogId === module.id"
                :title="`${module.name}：点击后移动到画布放置，或拖拽到画布`"
                draggable="true"
                class="w-full cursor-grab p-1.5 text-left hover:bg-[#f5f9f1] active:cursor-grabbing"
                @click="chooseModule(module)"
                @dragstart="startModuleDrag(module, $event)"
                @dragend="endModuleDrag(module, $event)"
              >
                <span class="block h-9 pointer-events-none"><OfficeCanvasFurniturePreview :template-id="module.id" thumbnail /></span>
                <span class="block truncate text-[10px] font-medium" :title="module.name">{{ module.name }}</span>
                <span class="block mt-0.5 text-[8px] text-muted">{{ module.widthMm }}×{{ module.depthMm }}<span v-if="module.reference"> · {{ module.reference.code }}</span></span>
              </button>
            </div>
          </div>
        </details>
      </section>
    </div>

    <div class="flex-none border-t border-line bg-white px-3 py-2.5">
      <p v-if="message" role="status" class="mb-1.5 text-[10px] leading-relaxed text-[#66745e]">{{ message }}</p>
      <button v-if="store.furniturePlacement" class="mb-1.5 w-full rounded-none border border-line bg-white px-3 py-2 text-[11px] font-semibold text-[#4d713d]" @click="store.cancelFurniturePlacement()">结束摆放</button>
      <p v-if="confirmProblem" data-testid="confirm-problem" class="mb-1.5 text-[10px] leading-relaxed text-amber-600">暂不能确认：{{ confirmProblem }}</p>
      <button v-if="items.length && !confirmed" class="w-full rounded-none bg-[#83d94f] px-3 py-2 text-[11px] font-semibold text-[#214326]" @click="confirmLayout">确认当前排版</button>
      <button v-else-if="confirmed" class="w-full rounded-none border border-line px-3 py-2 text-[11px] font-semibold text-[#596252]" @click="store.unconfirmLayout(zone!.id)">重新调整排版</button>
      <button v-if="items.length" class="mt-1.5 w-full rounded-none border border-line px-3 py-1.5 text-[10px] text-muted hover:text-[#a65e4c]" @click="clearLayout">清空排版</button>
    </div>
  </section>
</template>
