<script setup lang="ts">
import type { FloorPlanAnalysisResponse } from '~/types/floorPlan'
import type { ZoneObject } from '~/types/office'
import type { LayoutReference, LayoutReferenceEditing } from '~/types/layoutPlanning'
import { canvasDrawingRect, canvasZoneBounds } from '~/utils/officeCanvasView'
import { furnitureById } from '~/utils/manualFurniture'
import { uid } from '~/utils/helpers'
import { deleteReferenceSelection, rotateReferenceSelection, MIXED_CATEGORY_IDS, MIXED_LAYOUT_CATEGORIES, REFERENCE_TEMPLATES } from '~/utils/mixedLayout'
import { furnitureLayoutSignature, layoutReferenceTaskKey, mergeRecognizedMarks } from '~/utils/layoutReference'
import type { LayoutReferenceTask } from '~/stores/layoutReference'

const props = defineProps<{ plan: FloorPlanAnalysisResponse; zone: ZoneObject; categoryIds: string[]; existing: LayoutReference | null; problem: string }>()
const emit = defineEmits<{ close: []; apply: [value: LayoutReference] }>()
const api = useOfficeApi()
const officeStore = useOfficeStore()
const taskStore = useLayoutReferenceStore()
taskStore.ensureHydrated()
const editing = defineModel<LayoutReferenceEditing | null>({ required: true })
const upload = ref<Blob>()
const category = ref(props.categoryIds[0] || 'workstation')
const template = ref('')
const addToExistingGroup = ref(false)
const includeOtherCategories = ref(props.existing?.includeOtherCategories ?? !props.categoryIds.length)
const status = ref('正在读取当前空间底图…')
const gateProblem = ref('')
const gateClearAndRecognize = ref(false)
const confirmingClear = ref(false)
const options = (id: string) => (REFERENCE_TEMPLATES[id] || []).filter(key => furnitureById.has(key))
const selected = computed(() => editing.value?.selected == null ? null : editing.value.marks[editing.value.selected])
const label = (id: string) => furnitureById.get(id)?.name || id
const categoryLabel = (id: string) => MIXED_LAYOUT_CATEGORIES[MIXED_CATEGORY_IDS.indexOf(id)]?.label || id
const revision = props.plan.analysisRevision || 1
const taskKey = computed(() => layoutReferenceTaskKey(props.zone.id, props.plan.analysisId, revision))
const task = computed(() => taskStore.taskFor(props.zone.id, props.plan.analysisId, revision))
const busy = computed(() => task.value?.status === 'running' && !task.value.dismissed)
let disposed = false
watch(category, id => { template.value = options(id)[0] || ''; addToExistingGroup.value = false }, { immediate: true })
onUnmounted(() => {
  disposed = true
  // 面板卸载（跳转/隐藏）前把当前编辑同步进任务快照：已展示结果与展示后的用户编辑在跨页面重开时可恢复。
  // crop 守卫避免裁图尚未完成时用空快照覆盖有效记录；显式关闭/应用成功时记录已清除，这里自动跳过。
  const record = task.value
  if (record && !record.dismissed && editing.value?.crop) taskStore.syncEditing(taskKey.value, editing.value)
})
// 识别任务记录在 store 内原地更新（对象引用不变），以 updatedAt 作为变化信号。
watch(() => task.value?.updatedAt, () => {
  const record = task.value
  if (!record || disposed || record.dismissed || record.status === 'running' || record.consumed) return
  applyTaskResult(record)
})

onMounted(async () => {
  const bounds = props.plan.previewBounds || props.plan.drawingBounds
  const scale = props.zone.mmPerSourceUnit
  const source = props.plan.previewUrl || props.plan.referenceImageUrl || props.plan.imageUrl
  if (!source || !bounds || !scale || scale <= 0 || props.zone.reviewUnit !== 'mm') {
    status.value = '当前图纸没有与毫米画布对齐的可用底图'
    return
  }
  try {
    const original = await api.layoutReferenceSource(props.plan.analysisId, revision)
    if (disposed) return
    const url = URL.createObjectURL(original)
    const image = new Image()
    image.src = url
    try { await image.decode() } finally { URL.revokeObjectURL(url) }
    if (disposed) return
    const world = canvasDrawingRect(bounds, scale, props.zone.sourceYAxis)
    if (!world) throw new Error('底图范围无效')
    const { x: left, y: top, w: worldW, h: worldH } = world
    const zone = canvasZoneBounds(props.zone)
    const margin = 120
    const x1 = Math.max(0, Math.floor((zone.minX - margin - left) / worldW * image.naturalWidth))
    const y1 = Math.max(0, Math.floor((zone.minY - margin - top) / worldH * image.naturalHeight))
    const x2 = Math.min(image.naturalWidth, Math.ceil((zone.maxX + margin - left) / worldW * image.naturalWidth))
    const y2 = Math.min(image.naturalHeight, Math.ceil((zone.maxY + margin - top) / worldH * image.naturalHeight))
    if (x2 <= x1 || y2 <= y1) throw new Error('当前空间不在底图范围内')
    const w = x2 - x1, h = y2 - y1, resize = Math.min(1, 1600 / Math.max(w, h))
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(w * resize))
    canvas.height = Math.max(1, Math.round(h * resize))
    const context = canvas.getContext('2d')!
    context.fillStyle = '#fff'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.drawImage(image, x1, y1, w, h, 0, 0, canvas.width, canvas.height)
    const worldCrop = { x: left + x1 / image.naturalWidth * worldW, y: top + y1 / image.naturalHeight * worldH, w: w / image.naturalWidth * worldW, h: h / image.naturalHeight * worldH }
    const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/png'))
    if (disposed) return
    if (!blob) throw new Error('无法生成裁图')
    if (!editing.value) return
    upload.value = blob
    const current = editing.value
    const previous = props.existing
    // 裁图范围一致时保留已有标记：优先面板快照（跨页面恢复），其次已应用的参考方案。
    const keepCurrent = !!current.crop && JSON.stringify(current.crop) === JSON.stringify(worldCrop)
    const keepPrevious = !keepCurrent && !!previous && JSON.stringify(previous.crop) === JSON.stringify(worldCrop)
    editing.value = { ...current, crop: worldCrop, marks: keepCurrent ? current.marks : keepPrevious ? previous!.marks.map(mark => ({ ...mark })) : [] }
    status.value = ''
    // 恢复跨页面保留的识别任务：进行中提示识别中，已完成/失败则合并结果；已展示过的结果随快照恢复并回显文案。
    const record = task.value
    if (record && !record.dismissed) {
      if (record.status === 'running') status.value = '正在识别裁图中的家具…'
      else if (!record.consumed) applyTaskResult(record)
      else status.value = record.message
    }
    // 画布已有家具时直接亮出一键清空入口，省掉一次注定被拦截的「识别原图家具」点击。
    if (!busy.value && officeStore.furnitureForZone(props.zone.id).length > 0) {
      gateProblem.value = '请清除当前画布上的家具'
      gateClearAndRecognize.value = true
    }
  } catch (error) {
    if (!disposed) status.value = error instanceof Error ? '无法读取空间底图 · ' + error.message : '无法读取空间底图'
  }
})

/** 合并一次识别结果：失败不合并（保留上一轮标记）；排版/crop 已变化的旧结果不能并入。 */
function applyTaskResult(record: LayoutReferenceTask) {
  const current = editing.value
  if (!current?.crop) return
  if (record.status === 'error') {
    status.value = record.message
    taskStore.consume(taskKey.value)
    return
  }
  if (furnitureLayoutSignature(officeStore.furnitureForZone(props.zone.id)) !== record.layoutSignature) {
    status.value = '识别期间画布排版已变化，结果已失效，请清除家具后重新识别'
    taskStore.clear(taskKey.value)
    return
  }
  if (record.editing.crop && JSON.stringify(record.editing.crop) !== JSON.stringify(current.crop)) {
    status.value = '底图范围已变化，请重新识别'
    taskStore.clear(taskKey.value)
    return
  }
  editing.value = { ...current, marks: mergeRecognizedMarks(current.marks, record.marks), selected: null, placement: null }
  status.value = record.message
  // 快照同步为已展示结果：未应用就离开页面时按原样恢复。
  taskStore.syncEditing(taskKey.value, editing.value)
  taskStore.consume(taskKey.value)
}

async function recognize() {
  if (!upload.value || busy.value || !editing.value) return
  gateProblem.value = ''
  gateClearAndRecognize.value = false
  confirmingClear.value = false
  if (officeStore.furnitureForZone(props.zone.id).length > 0) {
    gateProblem.value = '请清除当前画布上的家具'
    gateClearAndRecognize.value = true
    return
  }
  status.value = '正在识别裁图中的家具…'
  // 识别生命周期由 store 持有：跳转到其他页面不中断请求，回来后从任务记录恢复状态与结果。
  await taskStore.runRecognition({
    zoneId: props.zone.id,
    analysisId: props.plan.analysisId,
    revision,
    upload: upload.value,
    editing: editing.value,
    layoutSignature: furnitureLayoutSignature(officeStore.furnitureForZone(props.zone.id)),
    isValid: mark => options(mark.categoryId).includes(mark.templateId),
  })
}

/** 识别结果面向空画布（recognize 的拦截）；面板内两步确认后清空家具并立即继续识别。 */
function clearAndRecognize() {
  if (busy.value || !editing.value) return
  confirmingClear.value = true
}

function confirmClearAndRecognize() {
  confirmingClear.value = false
  officeStore.clearFurniture(props.zone.id)
  gateProblem.value = ''
  gateClearAndRecognize.value = false
  void recognize()
}

/** 放弃进行中的识别并回到组合布局：迟到的结果由 store 按运行令牌丢弃；记录已放弃，不会自动重开面板。 */
function cancelRecognition() {
  if (!busy.value) return
  taskStore.dismiss(taskKey.value)
  emit('close')
}

function close() {
  const record = task.value
  if (record) {
    // 进行中显式返回 = 放弃（settle 时丢弃）；结果已展示 = 清除，不再自动重开。
    if (record.status === 'running') taskStore.dismiss(taskKey.value)
    else taskStore.clear(taskKey.value)
  }
  emit('close')
}


function add() {
  const state = editing.value
  if (!state?.crop || !template.value || busy.value) return
  const group = addToExistingGroup.value && selected.value?.categoryId === category.value ? selected.value.group : uid('ref')
  editing.value = { ...state, placement: { templateId: template.value, categoryId: category.value, group, rotation: 0 } }
}
function applyCurrent() {
  const state = editing.value
  if (!state?.crop || !state.marks.length) return
  // 兜底拦截：结果展示后画布才摆上家具时，阻止基于空画布识别的旧结果覆盖新排版。
  if (officeStore.furnitureForZone(props.zone.id).length > 0) {
    gateProblem.value = '请清除当前画布上的家具'
    gateClearAndRecognize.value = false
    return
  }
  emit('apply', { crop: state.crop, analysisId: props.plan.analysisId, revision, marks: state.marks.map(mark => ({ ...mark })), includeOtherCategories: includeOtherCategories.value })
}
</script>

<template>
  <section class="flex min-h-0 flex-1 flex-col" data-testid="layout-reference-panel" aria-label="参考原图摆位">
    <div class="min-h-0 flex-1 space-y-2 overflow-auto px-3 py-3">
      <div class="flex items-center justify-between gap-2">
        <div><h2 class="text-sm font-bold text-[#403a33]">参考原图摆位</h2><p class="text-[10px] text-muted">{{ zone.name }}</p></div>
        <button type="button" class="shrink-0 border border-line px-2 py-1 text-[10px]" @click="close">返回组合布局</button>
      </div>
      <p v-if="status" class="text-[11px] text-muted" role="status">{{ status }}</p>
      <template v-if="editing?.crop">
        <div class="flex gap-2">
          <button type="button" data-testid="reference-recognize" class="min-w-0 flex-1 border border-line px-2 py-2 text-xs font-medium disabled:opacity-50" :disabled="busy" @click="recognize">{{ busy ? '正在识别…' : '识别原图家具' }}</button>
          <button v-if="busy" type="button" data-testid="reference-cancel-recognize" class="flex-none border border-accent px-3 py-2 text-xs text-accent-ink hover:bg-accent-tint" @click="cancelRecognition">取消识别</button>
        </div>
        <p v-if="gateProblem" class="text-[10px] text-[#a65e4c]" role="status">{{ gateProblem }}</p>
        <div v-if="gateClearAndRecognize">
          <button v-if="!confirmingClear" type="button" data-testid="reference-clear-and-recognize" class="w-full border border-accent bg-accent-tint px-2 py-1.5 text-[11px] font-medium text-accent-ink disabled:opacity-50" :disabled="busy" @click="clearAndRecognize">清空画布家具并继续识别</button>
          <div v-else role="alertdialog" aria-label="确认清空画布家具" data-testid="reference-clear-confirm" class="border border-accent bg-white p-2 text-[11px]">
            <p class="font-semibold text-[#403a33]">确认清空「{{ zone.name }}」画布上的全部家具？</p>
            <p class="mt-0.5 text-[10px] text-muted">清空后立即继续识别原图家具，可通过工具栏撤销恢复。</p>
            <div class="mt-2 flex gap-2">
              <button type="button" data-testid="reference-clear-confirm-ok" class="flex-1 bg-accent px-2 py-1.5 font-semibold text-white disabled:opacity-50" :disabled="busy" @click="confirmClearAndRecognize">确认清空并继续</button>
              <button type="button" class="flex-1 border border-line px-2 py-1.5 text-muted" @click="confirmingClear = false">取消</button>
            </div>
          </div>
        </div>
        <p class="text-[10px] text-muted">在画布上点选家具，拖动调整位置。橙色图例待确认。</p>
        <div class="space-y-2 border-y border-line py-2 text-xs">
          <div class="grid grid-cols-2 gap-1">
            <select v-model="category" aria-label="标注功能" class="min-w-0 border border-line px-1.5 py-1"><option v-for="id in MIXED_CATEGORY_IDS" :key="id" :value="id">{{ categoryLabel(id) }}</option></select>
            <select v-model="template" aria-label="标注图例" class="min-w-0 border border-line px-1.5 py-1"><option v-for="id in options(category)" :key="id" :value="id">{{ label(id) }}</option></select>
          </div>
          <label v-if="selected?.categoryId === category" class="flex items-center gap-1"><input v-model="addToExistingGroup" type="checkbox"> 与选中家具同组</label>
          <button v-if="editing.placement" type="button" class="w-full border border-line px-2 py-1" @click="editing.placement = null">取消放置</button>
          <button v-else type="button" data-testid="reference-add" class="w-full border border-line px-2 py-1 disabled:opacity-50" :disabled="busy" @click="add">在画布添加家具</button>
          <p v-if="editing.placement" class="text-[10px] text-muted">点击布局画布放置 {{ label(editing.placement.templateId) }}</p>
        </div>
        <div v-for="(mark, index) in editing.marks" :key="index" class="border-b border-line pb-2 text-xs">
          <button type="button" class="w-full px-1 py-1 text-left font-semibold" :class="editing.selected === index ? 'bg-[#e9f8df]' : ''" :aria-pressed="editing.selected === index" @click="editing.selected = index; editing.placement = null">{{ label(mark.templateId) }} · {{ mark.confidence === 'manual' ? '人工确认' : mark.confidence === 'low' ? '低可信，待确认' : '识别估计' }}</button>
          <div v-if="editing.selected === index" class="mt-1 flex flex-wrap items-center gap-2 text-[11px]">
            <select v-model="mark.templateId" aria-label="修正图例" class="max-w-full border border-line p-1" @change="mark.confidence = 'manual'"><option v-for="id in options(mark.categoryId)" :key="id" :value="id">{{ label(id) }}</option></select>
            <button type="button" @click="rotateReferenceSelection(editing)">旋转 90°</button>
            <button v-if="mark.confidence !== 'manual'" type="button" @click="mark.confidence = 'manual'">确认</button>
            <button type="button" class="text-[#a65e4c]" @click="deleteReferenceSelection(editing)">删除</button>
          </div>
        </div>
        <label class="flex items-center gap-1 text-xs"><input v-model="includeOtherCategories" type="checkbox"> 纳入原图中其他功能的家具</label>
      </template>
    </div>
    <div class="border-t border-line px-3 py-2">
      <p v-if="problem" class="mb-2 text-[10px] text-[#a65e4c]" role="status">{{ problem }}</p>
      <button type="button" data-testid="reference-apply" class="w-full bg-accent px-3 py-2 text-xs font-semibold disabled:opacity-50" :disabled="busy || !editing?.crop || !editing.marks.length" @click="applyCurrent">应用当前方案</button>
    </div>
  </section>
</template>
