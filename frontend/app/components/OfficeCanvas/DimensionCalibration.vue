<script setup lang="ts">
import type { DimensionCalibrationReport, DimensionReference, DimensionUnit } from '~/types/dimensionCalibration'

const props = defineProps<{ enabled: boolean; previewArea?: number | null }>()
const emit = defineEmits<{
  (e: 'applied'): void
  (e: 'reference', reference: DimensionReference | null): void
}>()
const store = useOfficeStore(), api = useFloorPlanApi()
const report = computed(() => store.floorPlan?.dimensionCalibration)
const busy = ref<'reading' | 'validating' | 'confirming' | null>(null)
const error = ref('')
const unit = ref<DimensionUnit>('mm')
const selectedReferenceId = ref('')
let sequence = 0
const scope = () => (store.floorPlan?.analysisId || '') + ':' + (store.floorPlan?.analysisRevision || 1)
const applied = computed(() => !!report.value && store.zones.length > 0 && store.zones.every(zone =>
  zone.reviewUnit === 'mm' && zone.calibrationEvidence?.reportId === report.value!.reportId &&
  zone.calibrationEvidence?.assumedUnit === report.value!.assumedUnit &&
  Math.abs((zone.mmPerSourceUnit || 1) - (report.value!.mmPerPixel || 0)) < 1e-9))
const statusLabel = computed(() => {
  if (applied.value) return '已确认并应用'
  return report.value?.status === 'READY' ? '比例一致 · 待确认' : report.value?.status === 'CONFLICT' ? '比例存在冲突' : report.value?.status === 'INSUFFICIENT' ? '参考数量不足' : '未获得可靠标注'
})
watch(scope, () => {
  sequence++
  busy.value = null
  error.value = ''
  unit.value = report.value?.assumedUnit || 'mm'
  selectedReferenceId.value = ''
  emit('reference', null)
}, { immediate: true })
watch(() => report.value, value => {
  if (!value) return
  unit.value = value.assumedUnit
  const selected = value.references.find(reference => reference.referenceId === selectedReferenceId.value && reference.start && reference.end) ||
    value.references.find(reference => reference.status === 'ACCEPTED') || value.references.find(reference => reference.start && reference.end)
  if (selected) focusReference(selected)
}, { immediate: true })
function focusReference(reference: DimensionReference) {
  selectedReferenceId.value = reference.referenceId
  emit('reference', reference)
}
async function fetchReport(readAgain: boolean) {
  const plan = store.floorPlan
  if (!plan || !props.enabled || busy.value) return
  const requestId = ++sequence, source = scope(), selectedUnit = unit.value
  busy.value = readAgain ? 'reading' : 'validating'
  error.value = ''
  try {
    const result = readAgain ? await api.recognizeDimensions(plan.analysisId, plan.analysisRevision || 1) :
      await api.validateDimensions(plan.analysisId, plan.analysisRevision || 1, selectedUnit)
    if (requestId !== sequence || source !== scope()) return
    if (result) store.setDimensionReport(result)
    else error.value = '没有可校验的尺寸报告，请先读取图中尺寸'
  } catch (failure) {
    if (requestId === sequence && source === scope()) {
      error.value = failure instanceof Error ? failure.message : '尺寸读取失败，请重试或手工校准'
      unit.value = report.value?.assumedUnit || 'mm'
    }
  } finally {
    if (requestId === sequence) busy.value = null
  }
}
async function confirm() {
  const candidate = report.value, plan = store.floorPlan
  if (!candidate || !plan || candidate.status !== 'READY' || busy.value || applied.value || candidate.assumedUnit !== unit.value) return
  const requestId = ++sequence, source = scope(), canvasRevision = store.canvasRevision
  busy.value = 'confirming'
  error.value = ''
  try {
    const result = await api.confirmDimensionCalibration(plan.analysisId, plan.analysisRevision || 1, candidate.reportId, unit.value)
    if (requestId !== sequence || source !== scope()) return
    if (store.canvasRevision !== canvasRevision || store.floorPlan?.dimensionCalibration?.reportId !== candidate.reportId) {
      error.value = '确认期间草稿已变化，本次未应用比例；请重新核对后确认'
      return
    }
    if (!store.applyDimensionCalibration(result)) {
      error.value = '当前图纸或比例候选已变化，本次未应用；请重新读取并核对'
      return
    }
    emit('applied')
  } catch (failure) {
    if (requestId === sequence && source === scope()) error.value = failure instanceof Error ? failure.message : '比例确认失败，请重试'
  } finally {
    if (requestId === sequence) busy.value = null
  }
}
</script>

<template>
  <div v-if="enabled" class="dimension-calibration" aria-label="自动读取尺寸与比例">
    <div class="dimension-calibration-heading"><strong>图中尺寸自动校准</strong><button :disabled="!!busy" @click="fetchReport(true)">{{ busy === 'reading' ? '读取中…' : report ? '重新读取' : '读取图中尺寸' }}</button></div>
    <p v-if="!report" class="dimension-calibration-note">自动读取尺寸文字与尺寸线，核对多条比例后由你确认；保留当前空间编辑。</p>
    <template v-else>
      <div class="dimension-calibration-status" :class="{ ready: report.status === 'READY', conflict: report.status === 'CONFLICT' }">{{ statusLabel }}</div>
      <p v-if="report.status === 'READY'" class="dimension-calibration-note">{{ report.acceptedCount }} 条尺寸一致，最大偏差 {{ (report.maxDeviationPercent || 0).toFixed(1) }}%。应用后可直接进入家具排版，疑点按提示复核。</p>
      <label v-if="report.unitAssumed" class="dimension-unit-label">无单位标注按
        <select v-model="unit" aria-label="尺寸标注默认单位" :disabled="!!busy" @change="fetchReport(false)"><option value="mm">毫米 mm</option><option value="cm">厘米 cm</option><option value="m">米 m</option></select>解释
      </label>
      <p v-for="warning in report.warnings" :key="warning" class="dimension-warning">{{ warning }}</p>
      <div v-if="report.references.length" class="dimension-reference-list">
        <button v-for="reference in report.references" :key="reference.referenceId" :disabled="!reference.start || !reference.end"
          :aria-label="'对照尺寸 ' + reference.text" :aria-pressed="selectedReferenceId === reference.referenceId"
          :class="{ selected: selectedReferenceId === reference.referenceId, rejected: reference.status === 'REJECTED' || reference.status === 'OUTLIER' }"
          :title="reference.reason || '点击在底图上对照尺寸线端点'" @click="focusReference(reference)">
          <strong>{{ reference.text || '未读清' }}</strong>
          <span>{{ reference.status === 'ACCEPTED' ? '采用' : reference.status === 'OUTLIER' ? '不一致' : reference.status === 'REJECTED' ? '未采用' : '待核验' }}</span>
          <small v-if="reference.reason">{{ reference.reason }}</small>
        </button>
      </div>
      <p v-if="report.status === 'READY' && !applied && previewArea != null" class="dimension-calibration-preview">应用后所选空间约 <strong>{{ previewArea.toFixed(2) }} m²</strong></p>
      <button v-if="report.status === 'READY'" class="dimension-confirm-button" :disabled="!!busy || applied || report.assumedUnit !== unit" @click="confirm">{{ busy === 'confirming' ? '确认中…' : applied ? '✓ 自动比例已应用' : '确认比例并应用到全部空间' }}</button>
      <p v-else class="dimension-calibration-note">请核对标注，或使用下方两点校准。</p>
    </template>
    <p v-if="busy === 'validating'" class="dimension-calibration-note" role="status">正在重新校验单位与比例…</p>
    <p v-if="error" class="dimension-error" role="alert">{{ error }}</p>
  </div>
</template>
