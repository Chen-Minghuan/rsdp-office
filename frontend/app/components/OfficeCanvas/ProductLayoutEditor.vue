<script setup lang="ts">
import type { FurnitureObject } from '~/types/office'
import type { CustomOfficeModule, OfficeModuleTemplate } from '~/types/officeModule'
import { furnitureCatalogTemplates, furnitureCategories } from '~/utils/manualFurniture'
import { moduleGlyphId } from '~/utils/officeModules'
import { OFFICE_LAYOUT_SPACING } from '~/utils/officeAutoLayout'

const store = useOfficeStore()
const selectedId = defineModel<string | null>('selectedId')
const emit = defineEmits<{ navigate: []; confirmed: []; 'select-module': [module: OfficeModuleTemplate] }>()
const zone = computed(() => store.zones.find(item => item.id === store.designZoneId) || null)
const items = computed(() => zone.value ? store.furnitureForZone(zone.value.id) : [])
const catalogCategories = computed(() => zone.value?.zoneType === 'MEETING_ROOM' ? ['会议与洽谈', ...furnitureCategories.filter(category => category !== '会议与洽谈')] : furnitureCategories)
const selectedItem = computed(() => items.value.find(item => item.id === selectedId.value) || null)
const confirmed = computed(() => zone.value ? store.layoutConfirmedForZone(zone.value.id) : false)
const confirmProblem = computed(() => zone.value && items.value.length && !confirmed.value ? store.layoutConfirmProblem(zone.value.id) : null)
const editWidth = ref(0), editDepth = ref(0), message = ref('')
const showModuleDialog = ref(false), editingModule = ref<CustomOfficeModule | null>(null)
const lastDeletedModule = ref<CustomOfficeModule | null>(null)
const selectedCatalogId = defineModel<string>('selectedCatalogId', { default: '' })
const selectedCatalogModule = computed(() => store.customModules.find(module => module.id === selectedCatalogId.value) || furnitureCatalogTemplates.find(module => module.id === selectedCatalogId.value) || null)
watch(() => [selectedItem.value?.id, selectedItem.value?.w, selectedItem.value?.h], () => {
  editWidth.value = Math.round(selectedItem.value?.w || 0)
  editDepth.value = Math.round(selectedItem.value?.h || 0)
}, { immediate: true })

function glyph(item: FurnitureObject) {
  return item.moduleSnapshot?.glyphTemplateId || item.furnitureTemplateId || 'desk-straight'
}
function resizeSelected() {
  if (!selectedItem.value) return
  const result = store.resizeFurniture(selectedItem.value.id, editWidth.value, editDepth.value)
  message.value = result || '尺寸已更新，可撤销'
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
  if (store.furniturePlacement) { store.cancelFurniturePlacement(); return }
  const started = store.beginFurniturePlacement(zone.value.id, module.id, module.widthMm, module.depthMm, { ...OFFICE_LAYOUT_SPACING })
  message.value = started ? `请在画布中点击放置「${module.name}」，R 旋转，Esc 结束` : '当前空间或模块尺寸无法用于逐件添加'
}
function chooseModule(module: OfficeModuleTemplate) {
  selectedCatalogId.value = module.id
  if (!selectedItem.value) {
    message.value = `已选择「${module.name}」；点击“逐件”手动添加，或点击“自动”用于自动布局`
    return
  }
  const result = store.replaceFurnitureModule(selectedItem.value.id, module.id)
  message.value = result || `已替换为「${module.name}」，位置与旋转保持不变，可撤销`
}
function selectForAuto(module: OfficeModuleTemplate) {
  selectedCatalogId.value = module.id
  emit('select-module', module)
}
function openModuleDialog(module: CustomOfficeModule | null = null) {
  store.cancelFurniturePlacement()
  editingModule.value = module
  showModuleDialog.value = true
}
function moduleSaved(module: CustomOfficeModule) {
  showModuleDialog.value = false
  chooseModule(module)
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
      <p class="mt-1 text-[10px] text-muted">在画布中拖动位置；选中家具后可改尺寸、旋转、复制或点击图例替换模块。</p>
    </div>

    <div class="min-h-0 flex-1 overflow-y-auto">
      <div v-if="!items.length" class="px-4 py-8 text-center">
        <AppIcon name="cube" size="8" class="mx-auto mb-3 text-[#c9d3c1]" />
        <h3 class="text-xs font-semibold text-[#56614f]">从手动排版开始</h3>
        <p class="mt-2 text-[11px] leading-relaxed text-muted">可直接在下方选择图例并点击“逐件”添加，也可使用自动布局生成初步方案。</p>
        <button class="mt-4 rounded-none border border-line bg-white px-4 py-2 text-[11px] font-semibold text-[#596252]" @click="emit('navigate')">使用自动布局</button>
      </div>

      <template v-else>
        <section class="border-b border-line px-3 py-2.5">
          <h3 class="mb-2 text-[11px] font-semibold text-[#56614f]">已排家具</h3>
          <div class="grid gap-1">
            <button
              v-for="item in items"
              :key="item.id"
              type="button"
              :data-furniture-id="item.id"
              :aria-pressed="selectedItem?.id === item.id"
              :class="['flex min-w-0 items-center gap-2 rounded-none border px-2 py-1.5 text-left', selectedItem?.id === item.id ? 'border-[#83c95a] bg-[#eef8e7]' : 'border-line bg-white hover:bg-[#f7f9f4]']"
              @click="selectedId = item.id"
            >
              <span class="h-9 w-12 flex-none"><OfficeCanvasFurniturePreview :template-id="glyph(item)" :width-mm="item.w" :depth-mm="item.h" thumbnail /></span>
              <span class="min-w-0 flex-1">
                <span class="block truncate text-[11px] font-medium text-[#4f493f]">{{ item.label || '单位模块' }}</span>
                <span class="mt-0.5 block text-[10px] text-muted">{{ Math.round(item.w) }} × {{ Math.round(item.h) }} mm</span>
              </span>
              <AppIcon v-if="selectedItem?.id === item.id" name="check" size="3.5" class="flex-none text-[#55a138]" />
            </button>
          </div>

          <div v-if="selectedItem" class="mt-2 border border-[#d8e5cf] bg-[#fafcf7] p-2.5" data-testid="product-item-editor">
            <div class="grid grid-cols-2 gap-2">
              <label class="text-[10px] text-muted">宽度 mm<input v-model.number="editWidth" data-testid="product-item-width" type="number" min="10" step="50" class="mt-1 w-full rounded-none border border-line bg-white px-2 py-1.5 text-[11px]"></label>
              <label class="text-[10px] text-muted">深度 mm<input v-model.number="editDepth" data-testid="product-item-depth" type="number" min="10" step="50" class="mt-1 w-full rounded-none border border-line bg-white px-2 py-1.5 text-[11px]"></label>
            </div>
            <div class="mt-2 grid grid-cols-4 gap-1.5">
              <button data-testid="product-item-resize" class="rounded-none bg-[#83d94f] px-1 py-1.5 text-[10px] font-semibold text-[#214326]" @click="resizeSelected">应用尺寸</button>
              <button class="rounded-none border border-line bg-white px-1 py-1.5 text-[10px] text-[#596252]" @click="rotateSelected">旋转 90°</button>
              <button data-testid="product-copy-furniture" class="rounded-none border border-line bg-white px-1 py-1.5 text-[10px] text-[#596252]" @click="copySelected">复制</button>
              <button class="rounded-none border border-[#ead7d2] bg-white px-1 py-1.5 text-[10px] text-[#a65e4c]" @click="removeSelected">删除</button>
            </div>
          </div>
        </section>
      </template>

      <section class="px-3 py-2.5" data-testid="unit-module-catalog">
        <div class="mb-2 flex items-center justify-between">
          <h3 class="text-[11px] font-semibold text-[#56614f]">家具图例</h3>
          <span class="text-[9px] text-muted">全部品类均可选择</span>
        </div>
        <p class="mb-2 text-[9px] leading-relaxed text-muted">选中已排家具后，点击图例即可替换模块；“逐件”用于手动添加，“自动”用于生成自动布局。</p>
        <button class="mb-2 w-full border border-dashed border-[#a8c99a] px-3 py-2 text-[11px] text-[#4d713d]" @click="openModuleDialog()">＋ 新增单位模块</button>
        <div v-if="lastDeletedModule" role="status" class="mb-2 flex items-center justify-between gap-2 text-[10px] text-muted"><span>已删除 {{ lastDeletedModule.name }}</span><button class="flex-none text-[#4d713d] underline" @click="undoModuleDeletion">撤销删除目录模块</button></div>
        <details v-if="store.customModules.length" open class="mb-1.5">
          <summary class="cursor-pointer py-1.5 text-[11px] text-[#5b5751]">我的模块 · 本机 {{ store.customModules.length }} 款</summary>
          <div class="grid grid-cols-3 gap-1.5">
            <div v-for="module in store.customModules" :key="module.id" class="min-w-0 border border-line bg-white">
              <button :data-module-id="module.id" :aria-label="module.name" :aria-pressed="selectedCatalogId === module.id" class="w-full p-1.5 text-left hover:bg-[#f5f9f1]" @click="chooseModule(module)">
                <div class="h-9"><OfficeCanvasFurniturePreview :template-id="module.glyphTemplateId" :width-mm="module.widthMm" :depth-mm="module.depthMm" thumbnail /></div>
                <div class="truncate text-[10px] font-medium">{{ module.name }}</div>
                <div class="mt-0.5 text-[8px] text-muted">{{ module.widthMm }}×{{ module.depthMm }}</div>
              </button>
              <div class="grid grid-cols-2 border-t border-line text-[9px]">
                <button :data-auto-module-id="module.id" :aria-label="'用于自动布局 ' + module.name" class="py-1 text-[#4d713d]" @click="selectForAuto(module)">自动</button>
                <button :aria-label="'逐件添加 ' + module.name" class="border-l border-line py-1 text-[#4d713d]" @click="addModule(module)">逐件</button>
              </div>
              <div class="flex border-t border-line text-[9px]"><button class="flex-1 py-1" :aria-label="'编辑模块 ' + module.name" @click="openModuleDialog(module)">编辑</button><button class="flex-1 py-1 text-muted" :aria-label="'删除目录模块 ' + module.name" @click="removeModule(module)">删除</button></div>
            </div>
          </div>
        </details>
        <p v-if="store.furniturePlacement" class="mb-2 bg-[#eef8e7] px-2 py-1.5 text-[10px] text-[#4d713d]">正在逐件添加：点击画布放置，R 旋转，Esc 结束。</p>
        <details v-for="category in catalogCategories" :key="category" :open="category === catalogCategories[0]" class="mb-1.5">
          <summary class="cursor-pointer py-1.5 text-[11px] text-[#5b5751]">{{ category }} <span class="text-muted">{{ furnitureCatalogTemplates.filter(module => module.category === category).length }} 款</span></summary>
          <div class="grid grid-cols-3 gap-1.5">
            <div v-for="module in furnitureCatalogTemplates.filter(module => module.category === category)" :key="module.id" class="min-w-0 border border-line bg-white">
              <button
                type="button"
                :data-module-id="module.id"
                :data-product-module-id="module.id"
                :aria-label="module.name" :aria-pressed="selectedCatalogId === module.id"
                class="w-full p-1.5 text-left hover:bg-[#f5f9f1]"
                @click="chooseModule(module)"
              >
                <div class="h-9"><OfficeCanvasFurniturePreview :template-id="module.id" thumbnail /></div>
                <div class="truncate text-[10px] font-medium" :title="module.name">{{ module.name }}</div>
                <div class="mt-0.5 text-[8px] text-muted">{{ module.widthMm }}×{{ module.depthMm }}</div>
              </button>
              <div class="grid grid-cols-2 border-t border-line text-[9px]">
                <button type="button" :data-auto-module-id="module.id" :aria-label="'用于自动布局 ' + module.name" class="py-1 text-[#4d713d] hover:bg-[#eef8e7]" @click="selectForAuto(module)">自动</button>
                <button type="button" class="border-l border-line py-1 text-[#4d713d] hover:bg-[#eef8e7]" :aria-label="'逐件添加 ' + module.name" @click="addModule(module)">逐件</button>
              </div>
            </div>
          </div>
        </details>
      </section>
    </div>

    <div class="flex-none border-t border-line bg-white px-3 py-2.5">
      <p v-if="message" role="status" class="mb-1.5 text-[10px] leading-relaxed text-[#66745e]">{{ message }}</p>
      <button v-if="selectedCatalogModule" class="mb-1.5 w-full rounded-none border border-line bg-white px-3 py-2 text-[11px] font-semibold text-[#4d713d]" @click="addModule(selectedCatalogModule)">{{ store.furniturePlacement ? '结束逐件添加' : '逐件添加' }}</button>
      <p v-if="confirmProblem" data-testid="confirm-problem" class="mb-1.5 text-[10px] leading-relaxed text-amber-600">暂不能确认：{{ confirmProblem }}</p>
      <button v-if="items.length && !confirmed" class="w-full rounded-none bg-[#83d94f] px-3 py-2 text-[11px] font-semibold text-[#214326]" @click="confirmLayout">确认当前排版</button>
      <button v-else-if="confirmed" class="w-full rounded-none border border-line px-3 py-2 text-[11px] font-semibold text-[#596252]" @click="store.unconfirmLayout(zone!.id)">重新调整排版</button>
      <button v-if="items.length" class="mt-1.5 w-full rounded-none border border-line px-3 py-1.5 text-[10px] text-muted hover:text-[#a65e4c]" @click="clearLayout">清空排版</button>
    </div>
  </section>
</template>
