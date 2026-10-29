<script setup lang="ts">
import type { FurnitureObject } from '~/types/office'
import type { OfficeLayoutSpacing } from '~/utils/officeAutoLayout'
import { OFFICE_LAYOUT_SPACING } from '~/utils/officeAutoLayout'
import { defaultLayoutPreferences } from '~/utils/layoutPlanning'
import { planMixedLayout, MIXED_LAYOUT_CATEGORIES, MIXED_CATEGORY_IDS } from '~/utils/mixedLayout'

const props = defineProps<{ categoryIds: string[] }>()
const emit = defineEmits<{ preview: [value: { zoneId: string; furniture: FurnitureObject[]; replaceIds: string[]; seatClearanceMm: number } | null] }>()
const store = useOfficeStore()
const zone = computed(() => store.zones.find(item => item.id === store.designZoneId) || null)
const preferences = reactive(defaultLayoutPreferences())
const spacing = reactive<OfficeLayoutSpacing>({ ...OFFICE_LAYOUT_SPACING })
const applicationMessage = ref('')
const previewReady = ref(false)
const selectedCandidateIndex = ref(0)
const categoryLabels = computed(() => props.categoryIds.map(id => MIXED_LAYOUT_CATEGORIES[MIXED_CATEGORY_IDS.indexOf(id)]?.label).filter(Boolean))
const proposal = computed(() => zone.value ? planMixedLayout(zone.value, store.furnitureForZone(zone.value.id), props.categoryIds, spacing, preferences.targetSeats) : null)
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
function apply() {
  if (!zone.value || !activeProposal.value || activeProposal.value.problem || !previewReady.value) return
  const savedPreferences = { ...JSON.parse(JSON.stringify(preferences)), channels: JSON.parse(JSON.stringify(zone.value.layoutPreferences?.channels || [])) }
  if (!store.applyMixedLayoutProposal(zone.value.id, activeProposal.value, savedPreferences, selectedCandidateIndex.value)) { applicationMessage.value = '当前空间或家具已变化，请重新生成组合'; return }
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
        <label v-if="categoryIds.includes('workstation')" class="flex items-center justify-between gap-2">办公座位目标<input v-model.number="preferences.targetSeats" data-testid="mixed-target-seats" type="number" min="0" max="500" class="w-24 rounded-none border border-line px-2 py-1"></label>
        <label class="flex items-center justify-between gap-2">离墙距离 mm<input v-model.number="spacing.wallMm" type="number" min="0" step="100" class="w-24 rounded-none border border-line px-2 py-1"></label>
        <label class="flex items-center justify-between gap-2">组合净距 mm<input v-model.number="spacing.aisleMm" type="number" min="0" step="100" class="w-24 rounded-none border border-line px-2 py-1"></label>
        <label class="flex items-center justify-between gap-2">模块间距 mm<input v-model.number="spacing.gapMm" type="number" min="0" step="100" class="w-24 rounded-none border border-line px-2 py-1"></label>
      </div>
      <div class="py-2.5 text-[11px]">
        <div class="flex items-center justify-between"><span class="text-muted">组合预估</span><strong data-testid="mixed-layout-count" class="text-[#4f7d3e]">{{ activeProposal?.furniture.length || 0 }} 件</strong></div>
        <p v-if="activeProposal?.problem" role="status" class="mt-2 text-[10px] leading-relaxed text-[#a65e4c]">{{ activeProposal.problem }}</p>
        <div v-for="warning in activeProposal?.warnings || []" :key="warning" class="mt-2 border border-[#f0d9b3] bg-[#fff8e8] px-2 py-1.5 text-[10px] leading-relaxed text-[#8a6a2c]">{{ warning }}</div>
        <div v-if="previewReady && (activeProposal?.candidates?.length || 0) > 1" class="mt-2" data-testid="mixed-layout-candidates">
          <p class="mb-1.5 text-[10px] text-muted">选择整体候选，预览与应用使用同一方案。</p>
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
              <span class="mt-0.5 block">{{ candidate.furniture.length }} 件 · 座位 {{ candidate.seats }}<span v-if="candidate.shortfall"> · 尚缺 {{ candidate.shortfall }}</span> · 利用率 {{ Math.round(candidate.metrics.utilization * 100) }}%<span v-if="candidate.metrics.freeAreaPerSeatM2 != null"> · 人均余量 {{ candidate.metrics.freeAreaPerSeatM2.toFixed(1) }} ㎡</span></span>
            </button>
          </div>
        </div>
        <p v-if="applicationMessage" role="status" class="mt-2 text-[10px] leading-relaxed text-[#56636e]">{{ applicationMessage }}</p>
      </div>
    </div>
    <div class="flex-none space-y-1.5 border-t border-line bg-white p-3">
      <button v-if="!previewReady" data-testid="mixed-layout-generate" class="w-full rounded-none border border-dashed border-[#8bc66b] bg-[#e9f8df] px-3 py-2.5 text-xs font-semibold text-[#38682d] disabled:cursor-not-allowed disabled:opacity-50" :disabled="!categoryIds.length" @click="generate">生成组合预览</button>
      <button v-else data-testid="mixed-layout-apply" class="w-full rounded-none border border-dashed border-[#8bc66b] bg-[#e9f8df] px-3 py-2.5 text-xs font-semibold text-[#38682d] disabled:cursor-not-allowed disabled:opacity-50" :disabled="!categoryIds.length || !!activeProposal?.problem" @click="apply">应用所选组合</button>
    </div>
  </section>
</template>
