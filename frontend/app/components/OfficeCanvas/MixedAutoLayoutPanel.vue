<script setup lang="ts">
import type { FurnitureObject } from '~/types/office'
import type { OfficeLayoutSpacing } from '~/utils/officeAutoLayout'
import { OFFICE_LAYOUT_SPACING } from '~/utils/officeAutoLayout'
import { defaultLayoutPreferences } from '~/utils/layoutPlanning'
import { planTieredMixedLayout, MIXED_LAYOUT_CATEGORIES, MIXED_CATEGORY_IDS } from '~/utils/mixedLayout'
import { AREA_PER_SEAT_HINT_M2, zoneNetAreaMm2 } from '~/utils/layoutWarnings'

const props = defineProps<{ categoryIds: string[]; currentPreview?: { zoneId: string; furniture: FurnitureObject[]; replaceIds: string[]; seatClearanceMm: number } | null }>()
const emit = defineEmits<{ preview: [value: { zoneId: string; furniture: FurnitureObject[]; replaceIds: string[]; seatClearanceMm: number } | null] }>()
const store = useOfficeStore()
const zone = computed(() => store.zones.find(item => item.id === store.designZoneId) || null)
const preferences = reactive(defaultLayoutPreferences())
const spacing = reactive<OfficeLayoutSpacing>({ ...OFFICE_LAYOUT_SPACING })
const applicationMessage = ref('')
const previewReady = ref(false)
const selectedCandidateIndex = ref(0)
const categoryLabels = computed(() => props.categoryIds.map(id => MIXED_LAYOUT_CATEGORIES[MIXED_CATEGORY_IDS.indexOf(id)]?.label).filter(Boolean))
// LAYOUT-036：按面积推荐人数（2 ㎡/人为可调策略值，非规范）；一键采用，手动改过不覆盖。
const recommendedSeats = computed(() => zone.value ? Math.max(0, Math.floor(zoneNetAreaMm2(zone.value) / 1_000_000 / AREA_PER_SEAT_HINT_M2)) : 0)
const proposal = computed(() => zone.value && props.categoryIds.length ? planTieredMixedLayout(zone.value, store.furnitureForZone(zone.value.id), props.categoryIds, spacing, preferences.targetSeats, { objective: preferences.objective, fixedQuantity: preferences.fixedQuantity, scoreMode: preferences.scoreMode }) : null)
const activeProposal = computed(() => {
  const base = proposal.value
  if (!base) return null
  const candidate = selectedCandidateIndex.value > 0 ? base.candidates?.[selectedCandidateIndex.value] : undefined
  return candidate ? { ...base, furniture: candidate.furniture, seats: candidate.seats, shortfall: candidate.shortfall, label: candidate.label, rows: candidate.rows, columns: candidate.columns, rotated: candidate.rotated } : base
})
watch(proposal, () => {
  applicationMessage.value = ''
  previewReady.value = false
  selectedCandidateIndex.value = 0
}, { immediate: true, deep: true })
watch([activeProposal, previewReady], () => {
  const value = activeProposal.value
  emit('preview', previewReady.value && zone.value && value?.furniture.length ? { zoneId: zone.value.id, furniture: value.furniture, replaceIds: value.replaceIds, seatClearanceMm: preferences.seatClearanceMm } : null)
}, { immediate: true, deep: true })
onUnmounted(() => emit('preview', null))
function generate() {
  previewReady.value = true
}
/** 预览在家具 id 不变（未重新生成）时可能已被用户在画布上拖动，应用时以预览位置为准。 */
function withPreviewPositions<T extends { furniture: FurnitureObject[]; candidates?: Array<{ furniture: FurnitureObject[] }> }>(base: T): T {
  const live = props.currentPreview
  if (!live || live.zoneId !== zone.value?.id || !live.furniture.length) return base
  const positions = new Map(live.furniture.map(item => [item.id, item] as const))
  const merge = (items: FurnitureObject[]) => items.map(item => positions.has(item.id) ? { ...item, x: positions.get(item.id)!.x, y: positions.get(item.id)!.y } : item)
  return { ...base, furniture: merge(base.furniture), candidates: base.candidates?.map(candidate => ({ ...candidate, furniture: merge(candidate.furniture) })) }
}
function apply() {
  if (!zone.value || !activeProposal.value || activeProposal.value.problem || !previewReady.value) return
  const savedPreferences = { ...JSON.parse(JSON.stringify(preferences)), channels: JSON.parse(JSON.stringify(zone.value.layoutPreferences?.channels || [])) }
  if (!store.applyMixedLayoutProposal(zone.value.id, withPreviewPositions(activeProposal.value), savedPreferences, selectedCandidateIndex.value)) { applicationMessage.value = '当前空间或家具已变化，请重新生成组合'; return }
  applicationMessage.value = `已生成 ${activeProposal.value.furniture.length} 件家具的组合布局，可在产品选型中继续调整`
}
</script>

<template>
  <section class="flex min-h-0 flex-1 flex-col" data-testid="mixed-auto-layout-panel">
    <div class="min-h-0 flex-1 overflow-y-auto px-3 py-3">
      <div class="border-b border-line pb-2.5">
        <h3 class="text-[11px] font-semibold text-[#56614f]">组合方案</h3>
        <p class="mt-1 text-[10px] leading-relaxed text-muted">{{ categoryLabels.join(' + ') || '请先选择功能模块' }}</p>
        <p class="mt-1 text-[9px] text-muted">选完需求后统一生成候选，不会点击品类就立即出图；办公桌椅跟随目标人数，其他模块默认一套。</p>
      </div>
      <div class="space-y-2 border-b border-line py-2.5 text-[11px]">
        <div v-if="categoryIds.includes('workstation')" class="space-y-1.5">
          <div class="flex items-center justify-between gap-2">
            <span class="text-muted">求解目标</span>
            <div class="flex gap-1" role="radiogroup" aria-label="求解目标">
              <button type="button" data-testid="mixed-objective-target-seats" :aria-pressed="(preferences.objective || 'target-seats') === 'target-seats'" :class="['rounded-none border px-1.5 py-0.5 text-[10px]', (preferences.objective || 'target-seats') === 'target-seats' ? 'border-[#83c95a] bg-[#eef8e7] text-[#38523a]' : 'border-line text-muted']" @click="preferences.objective = 'target-seats'">目标人数</button>
              <button type="button" data-testid="mixed-objective-fixed-quantity" :aria-pressed="preferences.objective === 'fixed-quantity'" :class="['rounded-none border px-1.5 py-0.5 text-[10px]', preferences.objective === 'fixed-quantity' ? 'border-[#83c95a] bg-[#eef8e7] text-[#38523a]' : 'border-line text-muted']" @click="preferences.objective = 'fixed-quantity'">固定套数</button>
              <button type="button" data-testid="mixed-objective-max-capacity" :aria-pressed="preferences.objective === 'max-capacity'" :class="['rounded-none border px-1.5 py-0.5 text-[10px]', preferences.objective === 'max-capacity' ? 'border-[#83c95a] bg-[#eef8e7] text-[#38523a]' : 'border-line text-muted']" @click="preferences.objective = 'max-capacity'">最大容量</button>
            </div>
          </div>
          <label v-if="(preferences.objective || 'target-seats') === 'target-seats'" class="flex items-center justify-between gap-2">办公座位目标<input v-model.number="preferences.targetSeats" data-testid="mixed-target-seats" type="number" min="0" max="500" class="w-24 rounded-none border border-line px-2 py-1"></label>
          <p v-if="(preferences.objective || 'target-seats') === 'target-seats' && recommendedSeats > 0 && !preferences.targetSeats" class="text-right text-[10px] text-muted">按面积约可容 {{ recommendedSeats }} 人（策略参考值）<button type="button" data-testid="mixed-adopt-recommended-seats" class="ml-1 underline decoration-dotted" @click="preferences.targetSeats = recommendedSeats">采用</button></p>
          <label v-else-if="preferences.objective === 'fixed-quantity'" class="flex items-center justify-between gap-2">工位套数<input v-model.number="preferences.fixedQuantity" data-testid="mixed-fixed-quantity" type="number" min="0" max="500" class="w-24 rounded-none border border-line px-2 py-1"></label>
          <p v-else class="text-[10px] leading-relaxed text-muted">探索当前硬约束下可容纳的工位上限（估计值，非核准容量）。</p>
        </div>
        <label class="flex items-center justify-between gap-2">离墙距离 mm<input v-model.number="spacing.wallMm" type="number" min="0" step="100" class="w-24 rounded-none border border-line px-2 py-1"></label>
        <label class="flex items-center justify-between gap-2">组合净距 mm<input v-model.number="spacing.aisleMm" type="number" min="0" step="100" class="w-24 rounded-none border border-line px-2 py-1"></label>
        <label class="flex items-center justify-between gap-2">模块间距 mm<input v-model.number="spacing.gapMm" type="number" min="0" step="100" class="w-24 rounded-none border border-line px-2 py-1"></label>
        <label class="flex items-center justify-between gap-2 text-muted">综合评分排序<input v-model="preferences.scoreMode" type="checkbox" true-value="balanced" false-value="legacy" data-testid="mixed-score-mode" aria-label="综合评分排序"></label>
      </div>
      <div v-if="categoryIds.length" class="py-2.5 text-[11px]" data-testid="mixed-layout-results">
        <div class="flex items-center justify-between"><span class="text-muted">组合预估</span><strong data-testid="mixed-layout-count" class="text-[#4f7d3e]">{{ activeProposal?.furniture.length || 0 }} 件</strong></div>
        <template v-if="previewReady">
          <p v-if="activeProposal?.problem" role="status" class="mt-2 text-[10px] leading-relaxed text-[#a65e4c]">{{ activeProposal.problem }}</p>
          <ul v-if="activeProposal?.problem && activeProposal.problemAdvice?.length" class="mt-1 list-inside list-disc text-[10px] leading-relaxed text-muted" data-testid="mixed-problem-advice">
            <li v-for="advice in activeProposal.problemAdvice" :key="advice">{{ advice }}</li>
          </ul>
          <div v-for="warning in activeProposal?.warnings || []" :key="warning" data-testid="mixed-layout-warning" class="mt-2 border border-[#f0d9b3] bg-[#fff8e8] px-2 py-1.5 text-[10px] leading-relaxed text-[#8a6a2c]">{{ warning }}</div>
        </template>
        <!-- 候选卡落在画布右下角浮层（CanvasBoard 的 #layout-candidate-dock），随选随预览 -->
        <Teleport to="#layout-candidate-dock" defer>
          <div v-if="previewReady && (activeProposal?.candidates?.length || 0) > 1" class="pointer-events-auto rounded-xl border border-line bg-white/95 p-2 shadow-panel" data-testid="mixed-layout-candidates" @pointerdown.stop>
            <p class="mb-1.5 text-[10px] text-muted">方案选择 · 默认标准，调整间距后三档联动</p>
            <div class="flex flex-col gap-1.5">
              <button
                v-for="(candidate, index) in activeProposal?.candidates || []"
                :key="index"
                type="button"
                data-testid="mixed-layout-candidate"
                :class="['rounded-none border px-2 py-2 text-left text-[10px] leading-snug', index === selectedCandidateIndex ? 'border-[#83c95a] bg-[#eef8e7] text-[#38523a]' : 'border-line bg-white text-muted hover:bg-[#f7f9f4]']"
                :aria-pressed="index === selectedCandidateIndex"
                @click="selectedCandidateIndex = index"
              >
                <span class="block font-semibold">{{ candidate.label.split(' · ')[0] }}</span>
                <span class="mt-0.5 block">{{ candidate.furniture.length }} 件 · 座位 {{ candidate.seats }} · 利用率 {{ Math.round(candidate.metrics.utilization * 100) }}%</span>
              </button>
            </div>
          </div>
        </Teleport>
        <p v-if="applicationMessage" role="status" class="mt-2 text-[10px] leading-relaxed text-[#56636e]">{{ applicationMessage }}</p>
      </div>
    </div>
    <div class="flex-none space-y-1.5 border-t border-line bg-white p-3">
      <button v-if="!previewReady" data-testid="mixed-layout-generate" class="w-full rounded-none border border-dashed border-[#8bc66b] bg-[#e9f8df] px-3 py-2.5 text-xs font-semibold text-[#38682d] disabled:cursor-not-allowed disabled:opacity-50" :disabled="!categoryIds.length" @click="generate">生成组合预览</button>
      <button v-else data-testid="mixed-layout-apply" class="w-full rounded-none border border-dashed border-[#8bc66b] bg-[#e9f8df] px-3 py-2.5 text-xs font-semibold text-[#38682d] disabled:cursor-not-allowed disabled:opacity-50" :disabled="!categoryIds.length || !!activeProposal?.problem" @click="apply">应用所选组合</button>
    </div>
  </section>
</template>
