<script setup lang="ts">
import type { LayoutPreview, LayoutProposal, LayoutReference, LayoutReferenceEditing } from '~/types/layoutPlanning'
import type { ZoneObject } from '~/types/office'
import type { OfficeLayoutSpacing } from '~/utils/officeAutoLayout'
import { OFFICE_LAYOUT_SPACING } from '~/utils/officeAutoLayout'
import { confirmedLayoutProblem, defaultLayoutPreferences } from '~/utils/layoutPlanning'
import { mixedPlacementProblem, planMixedLayout, referenceLayoutSnapshot, MIXED_LAYOUT_CATEGORIES, MIXED_CATEGORY_IDS } from '~/utils/mixedLayout'
import { AREA_PER_SEAT_HINT_M2, zoneNetAreaMm2 } from '~/utils/layoutWarnings'
import { cloneReferenceEditing, layoutReferenceTaskKey } from '~/utils/layoutReference'

const props = defineProps<{ categoryIds: string[]; currentPreview?: LayoutPreview | null }>()
const emit = defineEmits<{ preview: [value: LayoutPreview | null] }>()
const store = useOfficeStore()
const guard = useRecognitionGuard()
const zone = computed(() => store.zones.find(item => item.id === store.designZoneId) || null)
const preferences = reactive(defaultLayoutPreferences())
const spacing = reactive<OfficeLayoutSpacing>({ ...OFFICE_LAYOUT_SPACING })
const applicationMessage = ref('')
const previewReady = ref(false)
const selectedCandidateIndex = ref(0)
const referenceEditing = defineModel<LayoutReferenceEditing | null>('referenceEditing', { required: true })
const reference = ref<LayoutReference | null>(null)
const referenceMessage = ref('')
const plan = computed(() => store.floorPlan)
const referenceTasks = useLayoutReferenceStore()
referenceTasks.ensureHydrated()
const referenceTask = computed(() => zone.value && plan.value
  ? referenceTasks.taskFor(zone.value.id, plan.value.analysisId, plan.value.analysisRevision || 1)
  : null)
// 跨页面保留的识别任务：进行中、结果未展示、或结果已展示但未应用（记录仍在）时都自动重开参考面板。
// 任务记录在 store 内原地更新（引用不变），以 updatedAt 为变化信号；不监听 referenceEditing，
// 因此用户主动隐藏（Escape/切工具）不被打断，仅在任务状态推进或本面板重挂载时重开。显式「返回组合布局」才是放弃。
watch([() => referenceTask.value?.updatedAt, () => referenceTask.value && layoutReferenceTaskKey(referenceTask.value.zoneId, referenceTask.value.analysisId, referenceTask.value.revision)], () => {
  const record = referenceTask.value
  if (!record || record.dismissed || referenceEditing.value) return
  referenceEditing.value = cloneReferenceEditing(record.editing)
}, { immediate: true })
const hasSource = computed(() => !!(plan.value && (plan.value.previewUrl || plan.value.referenceImageUrl || plan.value.imageUrl)
  && (plan.value.previewBounds || plan.value.drawingBounds) && zone.value?.mmPerSourceUnit && zone.value.reviewUnit === 'mm'))
const categoryLabels = computed(() => props.categoryIds.map(id => MIXED_LAYOUT_CATEGORIES[MIXED_CATEGORY_IDS.indexOf(id)]?.label).filter(Boolean))
// LAYOUT-036：按面积推荐人数（2 ㎡/人为可调策略值，非规范）；一键采用，手动改过不覆盖。
const recommendedSeats = computed(() => zone.value ? Math.max(0, Math.floor(zoneNetAreaMm2(zone.value) / 1_000_000 / AREA_PER_SEAT_HINT_M2)) : 0)
const proposal = computed(() => {
  const room = zone.value
  if (!room || !props.categoryIds.length) return null
  const items = store.furnitureForZone(room.id)
  const options = { objective: preferences.objective, fixedQuantity: preferences.fixedQuantity, scoreMode: preferences.scoreMode }
  return planMixedLayout(room, items, props.categoryIds, spacing, preferences.targetSeats, options)
})
const activeProposal = computed(() => {
  const base = proposal.value
  if (!base) return null
  const candidate = base.candidates?.[selectedCandidateIndex.value]
  return candidate ? { ...base, ...candidate, problem: null, warnings: base.problem ? [] : base.warnings } : base
})
const activeCandidate = computed(() => proposal.value?.candidates?.[selectedCandidateIndex.value])
const liveProblem = computed(() => {
  const room = zone.value, preview = props.currentPreview, selected = activeCandidate.value
  if (!room || preview?.zoneId !== room.id || !preview.manuallyAdjusted || !selected?.spacing) return null
  const final = [...store.furnitureForZone(room.id).filter(item => !preview.replaceIds.includes(item.id)), ...preview.furniture]
  return confirmedLayoutProblem(room, final) || mixedPlacementProblem(room, final, selected.spacing)
})
watch(() => JSON.stringify([store.workspaceId, zone.value?.id, zone.value?.polygon, zone.value?.mmPerSourceUnit, plan.value?.analysisId, plan.value?.analysisRevision]), () => {
  referenceEditing.value = null
  reference.value = null
  referenceMessage.value = ''
})
watch(proposal, () => {
  applicationMessage.value = ''
  previewReady.value = false
  selectedCandidateIndex.value = 0
}, { immediate: true, deep: true })
watch([activeProposal, previewReady, () => !!referenceEditing.value], () => {
  const value = activeProposal.value
  emit('preview', !referenceEditing.value && previewReady.value && zone.value && value?.furniture.length ? {
    zoneId: zone.value.id, furniture: value.furniture, replaceIds: value.replaceIds, seatClearanceMm: preferences.seatClearanceMm, mixedSpacing: activeCandidate.value?.spacing,
  } : null)
}, { immediate: true, deep: true })
onUnmounted(() => {
  referenceEditing.value = null
  emit('preview', null)
})
function openReference() {
  if (!zone.value) return
  referenceMessage.value = ''
  // 有未放弃的识别任务时从快照恢复（进行中、结果未展示、已展示未应用；保留此前编辑），否则全新开始。
  const record = referenceTask.value
  referenceEditing.value = record && !record.dismissed
    ? cloneReferenceEditing(record.editing)
    : { zoneId: zone.value.id, crop: null, marks: [], selected: null, placement: null }
}
function generate() {
  previewReady.value = true
}
async function applyReference(value: LayoutReference) {
  const room = zone.value
  if (!room || referenceEditing.value?.zoneId !== room.id || plan.value?.analysisId !== value.analysisId || (plan.value.analysisRevision || 1) !== value.revision) {
    referenceMessage.value = '当前空间或原图版本已变化，请重新打开原图参考'
    return
  }
  const items = store.furnitureForZone(room.id)
  const snapshot = referenceLayoutSnapshot(room, items, props.categoryIds, value)
  const retained = items.filter(item => !snapshot.replaceIds.includes(item.id))
  referenceMessage.value = snapshot.problem || confirmedLayoutProblem(room, [...retained, ...snapshot.furniture]) || ''
  if (referenceMessage.value) return
  // A user-positioned layout keeps physical constraints without generated group spacing.
  const manualSpacing = { wallMm: 0, aisleMm: 0, gapMm: 0 }
  if (!store.applyLayoutProposal(room.id, snapshot, applicationPreferences(room, manualSpacing))) {
    referenceMessage.value = '当前排版未应用，请核对家具位置或空间状态'
    return
  }
  reference.value = value
  referenceEditing.value = null
  referenceTasks.clear(layoutReferenceTaskKey(room.id, value.analysisId, value.revision))
  previewReady.value = false
  await nextTick()
  applicationMessage.value = `已应用当前方案，共 ${snapshot.furniture.length} 件参考家具`
}
/** 预览在家具 id 不变（未重新生成）时可能已被用户在画布上拖动，应用时以预览位置为准。 */
function withPreviewPositions(base: LayoutProposal): LayoutProposal | null {
  const live = props.currentPreview
  if (!live || live.zoneId !== zone.value?.id || live.furniture.length !== base.furniture.length) return null
  const positions = new Map(live.furniture.map(item => [item.id, item] as const))
  if (!base.furniture.every(item => positions.has(item.id))) return null
  return { ...base, furniture: base.furniture.map(item => positions.get(item.id)!) }
}
function applicationPreferences(room: ZoneObject, appliedSpacing: OfficeLayoutSpacing) {
  const hasWorkstations = props.categoryIds.includes('workstation')
  return {
    ...preferences,
    targetSeats: hasWorkstations && (preferences.objective || 'target-seats') === 'target-seats' ? preferences.targetSeats : 0,
    fixedQuantity: hasWorkstations && preferences.objective === 'fixed-quantity' ? preferences.fixedQuantity : 0,
    mixed: { spacing: { ...appliedSpacing } },
    channels: JSON.parse(JSON.stringify(room.layoutPreferences?.channels || [])),
  }
}
async function apply() {
  if (!zone.value || !activeProposal.value || activeProposal.value.problem || !previewReady.value) return
  if (!await guard.gateFurnitureEdit(zone.value.id)) return
  const savedPreferences = applicationPreferences(zone.value, activeCandidate.value?.spacing || spacing)
  const snapshot = withPreviewPositions(activeProposal.value)
  if (!snapshot || !store.applyLayoutProposal(zone.value.id, snapshot, savedPreferences)) { applicationMessage.value = '预览已过期或存在越界、重叠、约束冲突，请重新生成或调整'; return }
  applicationMessage.value = `已生成 ${activeProposal.value.furniture.length} 件家具的组合布局，可在产品选型中继续调整`
}
</script>

<template>
  <section class="flex min-h-0 flex-1 flex-col" data-testid="mixed-auto-layout-panel">
    <OfficeCanvasLayoutReferencePanel v-if="referenceEditing && plan && zone" v-model="referenceEditing" :plan="plan" :zone="zone" :category-ids="categoryIds" :existing="reference" :problem="referenceMessage" @close="referenceEditing = null" @apply="applyReference" />
    <template v-else>
    <div class="min-h-0 flex-1 overflow-y-auto px-3 py-3">
      <div class="border-b border-line pb-2.5">
        <h3 class="text-[11px] font-semibold text-[#56614f]">组合方案</h3>
        <p class="mt-1 text-[10px] leading-relaxed text-muted">{{ categoryLabels.join(' + ') || '请先选择功能模块' }}</p>
        <p class="mt-1 text-[9px] text-muted">选完需求后统一生成候选，不会点击品类就立即出图；办公桌椅跟随目标人数，其他模块默认一套。</p>
      </div>
      <div class="border-b border-line py-2">
        <button type="button" data-testid="mixed-layout-reference" class="w-full border border-line px-2 py-1.5 text-[11px] font-semibold disabled:opacity-50" :disabled="!hasSource" @click="openReference">参考原图摆位</button>
        <p v-if="!hasSource" class="mt-1 text-[10px] text-muted">需要当前空间有已校准的上传底图</p>
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
          <p v-else-if="preferences.objective === 'max-capacity'" class="text-[10px] leading-relaxed text-muted">探索当前硬约束下可容纳的工位上限（估计值，非核准容量）。</p>
        </div>
        <label class="flex items-center justify-between gap-2">离墙距离 mm<input v-model.number="spacing.wallMm" type="number" min="0" step="100" class="w-24 rounded-none border border-line px-2 py-1"></label>
        <label class="flex items-center justify-between gap-2">组合净距 mm<input v-model.number="spacing.aisleMm" type="number" min="0" step="100" class="w-24 rounded-none border border-line px-2 py-1"></label>
        <label class="flex items-center justify-between gap-2" title="生成的功能组与保留家具之间的最小净距">距保留家具 mm<input v-model.number="spacing.gapMm" type="number" min="0" step="100" class="w-24 rounded-none border border-line px-2 py-1"></label>
        <label class="flex items-center justify-between gap-2 text-muted">综合评分排序<input v-model="preferences.scoreMode" type="checkbox" true-value="balanced" false-value="legacy" data-testid="mixed-score-mode" aria-label="综合评分排序"></label>
      </div>
      <p v-if="applicationMessage" class="mt-2 text-[10px] text-[#56636e]" role="status">{{ applicationMessage }}</p>
      <div v-if="categoryIds.length" class="py-2.5 text-[11px]" data-testid="mixed-layout-results">
        <div class="flex items-center justify-between"><span class="text-muted">组合预估</span><strong data-testid="mixed-layout-count" class="text-[#4f7d3e]">{{ activeProposal?.furniture.length || 0 }} 件</strong></div>
        <template v-if="previewReady">
          <p v-if="activeProposal?.problem" role="status" class="mt-2 text-[10px] leading-relaxed text-[#a65e4c]">{{ activeProposal.problem }}</p>
          <ul v-if="activeProposal?.problem && activeProposal.problemAdvice?.length" class="mt-1 list-inside list-disc text-[10px] leading-relaxed text-muted" data-testid="mixed-problem-advice">
            <li v-for="advice in activeProposal.problemAdvice" :key="advice">{{ advice }}</li>
          </ul>
          <div v-for="warning in activeProposal?.warnings || []" :key="warning" data-testid="mixed-layout-warning" class="mt-2 border border-[#f0d9b3] bg-[#fff8e8] px-2 py-1.5 text-[10px] leading-relaxed text-[#8a6a2c]">{{ warning }}</div>
          <p v-if="activeCandidate?.shortfall" class="mt-2 text-[10px] text-[#a65e4c]">当前目标还差 {{ activeCandidate.shortfall }} {{ preferences.objective === 'fixed-quantity' ? '套' : '个办公工位' }}</p>
          <ul v-if="activeCandidate?.notes?.length" class="mt-2 list-inside list-disc text-[10px] text-muted"><li v-for="note in activeCandidate.notes" :key="note">{{ note }}</li></ul>
        </template>
        <!-- 候选卡落在画布右下角浮层（CanvasBoard 的 #layout-candidate-dock），随选随预览 -->
        <Teleport to="#layout-candidate-dock" defer>
          <div v-if="previewReady && activeProposal?.candidates?.length" class="pointer-events-auto rounded-xl border border-line bg-white/95 p-2 shadow-panel" data-testid="mixed-layout-candidates" @pointerdown.stop>
            <p class="mb-1.5 text-[10px] text-muted">方案选择</p>
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
                <span class="block font-semibold">{{ candidate.label }}</span>
                <span class="mt-0.5 block">{{ candidate.furniture.length }} 件 · 办公 {{ candidate.officeSeats || 0 }} · 会客 {{ candidate.guestSeats || 0 }} · 占地 {{ Math.round(candidate.metrics.utilization * 100) }}%</span>
                <span class="mt-0.5 block">{{ candidate.passageMm === null ? '入口待核验' : candidate.passageMm ? '通路估计 ≥ ' + candidate.passageMm + ' mm' : '通行需复核' }}</span>
              </button>
            </div>
          </div>
        </Teleport>
        <p v-if="currentPreview?.manuallyAdjusted" data-testid="mixed-preview-manually-adjusted" class="mt-2 text-[10px] text-[#4d713d]">已手动调整 · 应用时保留当前预览位置</p>
        <p v-if="liveProblem" class="mt-2 text-[10px] text-[#a65e4c]" role="status">{{ liveProblem }}</p>
      </div>
    </div>
    <div class="flex-none space-y-1.5 border-t border-line bg-white p-3">
      <button v-if="!previewReady" data-testid="mixed-layout-generate" class="w-full rounded-none border border-dashed border-[#8bc66b] bg-[#e9f8df] px-3 py-2.5 text-xs font-semibold text-[#38682d] disabled:cursor-not-allowed disabled:opacity-50" :disabled="!categoryIds.length" @click="generate">生成组合预览</button>
      <button v-else data-testid="mixed-layout-apply" class="w-full rounded-none border border-dashed border-[#8bc66b] bg-[#e9f8df] px-3 py-2.5 text-xs font-semibold text-[#38682d] disabled:cursor-not-allowed disabled:opacity-50" :disabled="!activeCandidate || !!activeProposal?.problem || !!liveProblem" @click="apply">{{ currentPreview?.manuallyAdjusted ? '应用当前预览（含手动调整）' : '应用所选组合' }}</button>
    </div>
    </template>
  </section>
</template>
