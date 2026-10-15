<script setup lang="ts">
import SpaceTemplateDialog from './SpaceTemplateDialog.vue'
import { furnitureCatalogTemplates, furnitureCategories } from '~/utils/manualFurniture'
import { planOfficeFurniture, OFFICE_LAYOUT_SPACING } from '~/utils/officeAutoLayout'
import type { OfficeLayoutMode } from '~/utils/officeAutoLayout'
import { furnitureMatchesPlan } from '~/utils/officeFurniture'
import type { CustomOfficeModule, OfficeModuleTemplate } from '~/types/officeModule'
import { moduleGlyphId } from '~/utils/officeModules'

const store = useOfficeStore()
const templateRouter = useRouter()
const collapsed = ref(false)
const showTemplate = ref(false)
const applicationMessage = ref('')

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
const spacing = reactive({ ...OFFICE_LAYOUT_SPACING })

watch(selectedModule, (p) => {
  if (p) {
    unitW.value = p.widthMm
    unitD.value = p.depthMm
  }
}, { immediate: true, flush: 'sync' })

watch([() => zone.value?.id, () => zone.value ? store.furnitureForZone(zone.value.id)[0]?.id : undefined], () => {
  const item = zone.value ? store.furnitureForZone(zone.value.id)[0] : null
  const settings = item?.layoutSettings
  const template = modules.value.find(template => template.id === item?.furnitureTemplateId) || builtInModules.find(template => template.id === item?.moduleSnapshot?.glyphTemplateId)
  if (template) {
    selectedModule.value = template
    unitW.value = item!.w
    unitD.value = item!.h
  }
  if (!settings || !['auto', 'row', 'grid', 'backToBack', 'perimeter'].includes(settings.mode)) return
  arrangement.value = settings.mode
  Object.assign(spacing, settings.spacing)
}, { immediate: true })

const selectedSize = computed(() => selectedModule.value.sizeOptions?.some(([w, d]) => w === unitW.value && d === unitD.value)
  ? `${unitW.value},${unitD.value}` : '')

const plan = computed(() => zone.value ? planOfficeFurniture(zone.value, unitW.value, unitD.value, arrangement.value, spacing) : null)
const capacity = computed(() => plan.value?.slots.length || 0)
const currentFurniture = computed(() => zone.value ? store.furnitureForZone(zone.value.id) : [])
const autoPlan = computed(() => zone.value ? planOfficeFurniture(zone.value, unitW.value, unitD.value, 'auto', spacing) : null)
const draftApplied = computed(() => furnitureMatchesPlan(currentFurniture.value, selectedModule.value.id, unitW.value, unitD.value, plan.value?.slots || [], moduleGlyphId(selectedModule.value), selectedModule.value.heightMm))
watch(draftApplied, applied => { if (!applied) applicationMessage.value = '' }, { flush: 'sync' })
watch([selectedModule, unitW, unitD, arrangement, () => spacing.aisleMm, () => spacing.wallMm, () => spacing.gapMm, () => zone.value?.id], () => { applicationMessage.value = '' }, { flush: 'sync' })

function selectModule(template: OfficeModuleTemplate) {
  store.cancelFurniturePlacement()
  selectedModule.value = template
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
  const applied = store.layoutFurnitureTemplate(zone.value.id, selectedModule.value.id, unitW.value, unitD.value, arrangement.value, { ...spacing })
  applicationMessage.value = applied
    ? `已应用${arrangement.value === 'auto' ? '自动多排' : '当前方式'}：${store.furnitureForZone(zone.value.id).length} 件`
    : '排版未应用，请检查空间和尺寸后重试'
}

function applyAutoLayout() {
  // Primary action always optimizes, even when an older draft restores single-row mode.
  arrangement.value = 'auto'
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

const arrangements = [
  { key: 'auto', label: '自动优化', description: '比较横纵朝向，布置多排并保留排间净距' },
  { key: 'row', label: '单排', description: '只布置一排，其余空间保持留白' },
  { key: 'backToBack', label: '背靠背', description: '多组相向双排，组间保留排间净距' },
  { key: 'grid', label: '矩阵', description: '按当前朝向布置多排，保留排间净距' },
  { key: 'perimeter', label: '沿边', description: '沿周边布置，跳过转角相互重叠的位置' },
] as const
</script>

<template>
  <OfficeCanvasCustomModuleDialog v-if="showModuleDialog" :module="editingModule" :source-id="moduleGlyphId(selectedModule)" :width-mm="unitW" :depth-mm="unitD" @close="showModuleDialog = false" @saved="moduleSaved" />
  <SpaceTemplateDialog v-if="showTemplate" @close="showTemplate = false" @layout="zoneId => templateRouter.replace({ path: '/canvas', query: { mode: 'design', zoneId } })" />
  <div :class="['flex-none bg-white border border-line rounded-2xl shadow-panel flex flex-col overflow-hidden transition-all duration-300', collapsed ? 'w-12' : 'w-72']">
    <div v-show="!collapsed" class="w-72 h-full flex flex-col">
      <!-- Header -->
      <div class="relative px-4 py-3 border-b border-line flex-none">
        <button class="absolute top-3 right-3 p-1 rounded hover:bg-[#f7f3ef] text-muted" title="收起侧边栏" @click="collapsed = true">
          <AppIcon name="arrowLeft" size="4" />
        </button>
        <div class="flex items-center gap-2 text-[15px] font-bold">
          <AppIcon name="cube" size="4" />
          <span>单位模块与排版</span>
        </div>
        <p class="text-[10px] text-muted mt-1">
          {{ zone ? zone.name : '未选择空间' }}
        </p>
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
        <div class="px-3 py-3 border-b border-line max-h-[280px] overflow-y-auto" data-testid="unit-module-catalog">
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
        </div>

        <!-- Dimensions -->
        <div class="px-4 py-3 border-b border-line flex-none">
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
        </div>

        <!-- Arrangement -->
        <div class="px-4 py-3 border-b border-line flex-none">
          <div class="text-[11px] font-semibold text-[#5b5751] mb-2">排列方式</div>
          <div class="grid grid-cols-2 gap-2">
            <button
              v-for="a in arrangements"
              :key="a.key"
              :class="[
                'px-2 py-1.5 rounded-lg text-[11px] border transition',
                a.key === 'auto' ? 'col-span-2' : '',
                arrangement === a.key ? 'bg-bronze text-white border-bronze' : 'bg-white border-line hover:bg-[#f9f7f4]',
              ]"
              :aria-pressed="arrangement === a.key" @click="arrangement = a.key"
            >
              {{ a.label }}
            </button>
          </div>
          <p class="mt-2 text-[10px] text-muted">{{ arrangements.find(option => option.key === arrangement)?.description }}</p>
          <details class="mt-2 text-[10px] text-muted">
            <summary class="cursor-pointer py-1">间距设置（毫米）</summary>
            <div class="grid grid-cols-3 gap-1.5 mt-1">
              <label>排间净距<input v-model.number="spacing.aisleMm" aria-label="排间净距（毫米）" type="number" min="0" step="100" class="w-full h-7 px-1 border border-line rounded-lg"></label>
              <label>离墙距离<input v-model.number="spacing.wallMm" aria-label="离墙距离（毫米）" type="number" min="0" step="100" class="w-full h-7 px-1 border border-line rounded-lg"></label>
              <label>同排间距<input v-model.number="spacing.gapMm" aria-label="同排间距（毫米）" type="number" min="0" step="50" class="w-full h-7 px-1 border border-line rounded-lg"></label>
            </div>
          </details>
        </div>

        </div>
        <!-- Preview stats -->
        <div class="px-4 py-3 bg-[#f9f7f4] flex-none">
          <div class="flex items-center justify-between text-xs mb-1">
            <span class="text-[#78736d]">当前已放置</span><strong data-testid="current-furniture-count">{{ currentFurniture.length }} 件</strong>
          </div>
          <div class="flex items-center justify-between text-xs">
            <span class="text-[#78736d]">当前方式预估</span>
            <strong data-testid="module-capacity" class="text-bronze text-lg">{{ capacity }}</strong>
          </div>
          <div v-if="capacity && plan?.rows" data-testid="layout-row-summary" class="mt-1 text-[10px] text-muted">{{ plan.rows }} 排 · 每排最多 {{ plan.columns }} 件 · {{ plan.rotated ? '纵向' : '横向' }}</div>
          <p v-if="currentFurniture.length && capacity && !draftApplied" data-testid="layout-pending" class="mt-1 text-[10px] text-[#a67c2e]">新方案尚未应用，画布保留当前 {{ currentFurniture.length }} 件。</p>
          <p v-if="applicationMessage" role="status" class="mt-1 text-[10px] text-[#56636e]">{{ applicationMessage }}</p>
          <p v-if="plan?.problem" role="status" class="mt-1 text-[10px] text-[#a65e4c]">{{ plan.problem }}</p>
        </div>

        <!-- Actions -->
        <div class="px-4 py-3 mt-auto border-t border-line flex flex-col gap-2 flex-none">
          <button :class="['w-full px-3 py-2 rounded-lg text-xs border font-semibold', store.furniturePlacement ? 'border-bronze bg-[#f7f0e8] text-bronze' : 'border-line hover:bg-[#f9f7f4]']" @click="addToCanvas">{{ store.furniturePlacement ? '结束逐件添加' : '添加到画布' }}</button>
          <div v-if="store.furniturePlacement" class="text-[10px] text-muted">点击空间内放置，R 旋转，Esc 结束。<button class="ml-1 text-bronze underline" @click="store.rotateFurniturePlacement()">旋转 90°</button></div>
          <p v-if="autoPlan?.slots.length" class="text-[10px] text-muted">自动多排方案：<strong data-testid="auto-layout-capacity">{{ autoPlan.slots.length }} 件 · {{ autoPlan.rows }} 排</strong></p>
          <button
            class="w-full px-4 py-2.5 rounded-lg text-xs font-semibold text-white bg-bronze hover:bg-bronze-dark flex items-center justify-center gap-1.5 disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="!autoPlan?.slots.length"
            @click="applyAutoLayout"
          >
            <AppIcon name="cube" size="3.5" />
            <span>一键自动排版</span>
          </button>
          <button v-if="arrangement !== 'auto'" class="w-full px-3 py-2 rounded-lg text-xs border border-line hover:bg-[#f9f7f4] disabled:opacity-50"
            :disabled="capacity <= 0" @click="applyLayout">应用{{ arrangements.find(option => option.key === arrangement)?.label }}方案</button>
          <button
            v-if="store.furnitureForZone(zone.id).length > 0 && !layoutConfirmed"
            class="w-full px-4 py-2 rounded-lg text-xs font-semibold bg-[#e6f5ee] text-green border border-[#d0ead9] hover:bg-[#d8f0e3] flex items-center justify-center gap-1.5"
            @click="confirmLayout"
          >
            <AppIcon name="check" size="3.5" />
            <span>确认当前排版</span>
          </button>
          <button
            v-else-if="layoutConfirmed"
            class="w-full px-4 py-2 rounded-lg text-xs font-semibold border border-line hover:bg-[#fff0f0] hover:text-red-500 flex items-center justify-center gap-1.5"
            @click="unconfirmLayout"
          >
            <AppIcon name="arrowPath" size="3.5" />
            <span>重新调整排版</span>
          </button>
          <button
            class="w-full px-4 py-2 rounded-lg text-xs font-semibold border border-line hover:bg-[#fff0f0] hover:text-red-500 flex items-center justify-center gap-1.5"
            @click="clearLayout"
          >
            <AppIcon name="trash" size="3.5" />
            <span>清空当前排版</span>
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
