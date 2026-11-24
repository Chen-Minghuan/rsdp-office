<script setup lang="ts">
import type { FloorPlanAnalysisResponse } from '~/types/floorPlan'
import type { ZoneObject } from '~/types/office'
import type { LayoutReference, LayoutReferenceEditing, LayoutReferenceMark, LayoutReferenceRecognitionTask } from '~/types/layoutPlanning'
import { canvasDrawingRect, canvasZoneBounds } from '~/utils/officeCanvasView'
import { furnitureById } from '~/utils/manualFurniture'
import { uid } from '~/utils/helpers'
import { deleteReferenceSelection, rotateReferenceSelection, MIXED_CATEGORY_IDS, MIXED_LAYOUT_CATEGORIES, REFERENCE_TEMPLATES } from '~/utils/mixedLayout'

const emit = defineEmits<{ close: []; apply: [value: LayoutReference]; recognize: [value: { image: Blob; analysisId: string; revision: number; zoneId: string }] }>()
const props = defineProps<{ plan: FloorPlanAnalysisResponse; zone: ZoneObject; categoryIds: string[]; existing: LayoutReference | null; problem: string; recognition: LayoutReferenceRecognitionTask }>()
const editing = defineModel<LayoutReferenceEditing | null>({ required: true })
/** 识别结果早于面板裁图完成时暂存，crop 就绪后合并，避免跨页面返回时丢失标记。 */
const pendingMarks = ref<LayoutReferenceMark[] | null>(null)
const api = useOfficeApi()
const upload = ref<Blob>()
const category = ref(props.categoryIds[0] || 'workstation')
const template = ref('')
const addToExistingGroup = ref(false)
const includeOtherCategories = ref(props.existing?.includeOtherCategories ?? !props.categoryIds.length)
const status = ref('正在读取当前空间底图…')
const statusText = computed(() => props.recognition.status === 'running' ? '正在识别裁图中的家具…' : props.recognition.message || status.value)
const options = (id: string) => (REFERENCE_TEMPLATES[id] || []).filter(key => furnitureById.has(key))
const selected = computed(() => editing.value?.selected == null ? null : editing.value.marks[editing.value.selected])
const label = (id: string) => furnitureById.get(id)?.name || id
const categoryLabel = (id: string) => MIXED_LAYOUT_CATEGORIES[MIXED_CATEGORY_IDS.indexOf(id)]?.label || id
const revision = props.plan.analysisRevision || 1
let disposed = false
watch(category, id => { template.value = options(id)[0] || ''; addToExistingGroup.value = false }, { immediate: true })
watch(() => editing.value?.marks, (marks, previous) => {
  const state = editing.value
  if (!state || state.crop || !marks?.length) return
  if (marks.every(mark => mark.confidence === 'manual')) return
  pendingMarks.value = marks.map(mark => ({ ...mark }))
  editing.value = { ...state, marks: (previous || []).map(mark => ({ ...mark })) }
})
onUnmounted(() => { disposed = true })

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
    const previous = props.existing
    const current = editing.value
    const marks = JSON.stringify(current.crop) === JSON.stringify(worldCrop)
      ? current.marks.map(mark => ({ ...mark }))
      : previous && JSON.stringify(previous.crop) === JSON.stringify(worldCrop)
        ? previous.marks.map(mark => ({ ...mark }))
        : []
    const merged = pendingMarks.value?.length ? [...marks.filter(mark => mark.confidence === 'manual'), ...pendingMarks.value] : marks
    pendingMarks.value = null
    editing.value = { ...current, crop: worldCrop, marks: merged }
    status.value = ''
  } catch (error) {
    if (!disposed) status.value = error instanceof Error ? '无法读取空间底图 · ' + error.message : '无法读取空间底图'
  }
})

function recognize() {
  if (!upload.value || props.recognition.status === 'running') return
  emit('recognize', { image: upload.value, analysisId: props.plan.analysisId, revision, zoneId: props.zone.id })
}


function add() {
  const state = editing.value
  if (!state?.crop || !template.value || props.recognition.status === 'running') return
  const group = addToExistingGroup.value && selected.value?.categoryId === category.value ? selected.value.group : uid('ref')
  editing.value = { ...state, placement: { templateId: template.value, categoryId: category.value, group, rotation: 0 } }
}
function applyCurrent() {
  const state = editing.value
  if (!state?.crop || !state.marks.length) return
  emit('apply', { crop: state.crop, analysisId: props.plan.analysisId, revision, marks: state.marks.map(mark => ({ ...mark })), includeOtherCategories: includeOtherCategories.value })
}
</script>

<template>
  <section class="flex min-h-0 flex-1 flex-col" data-testid="layout-reference-panel" aria-label="参考原图摆位">
    <div class="min-h-0 flex-1 space-y-2 overflow-auto px-3 py-3">
      <div class="flex items-center justify-between gap-2">
        <div><h2 class="text-sm font-bold text-[#403a33]">参考原图摆位</h2><p class="text-[10px] text-muted">{{ zone.name }}</p></div>
        <button type="button" class="shrink-0 border border-line px-2 py-1 text-[10px]" @click="emit('close')">返回组合布局</button>
      </div>
      <p v-if="statusText" class="text-[11px] text-muted" role="status">{{ statusText }}</p>
      <template v-if="editing?.crop">
        <button type="button" data-testid="reference-recognize" class="w-full border border-line px-2 py-2 text-xs font-medium disabled:opacity-50" :disabled="recognition.status === 'running'" @click="recognize">{{ recognition.status === 'running' ? '正在识别…' : '识别原图家具' }}</button>
        <p class="text-[10px] text-muted">在画布上点选家具，拖动调整位置。橙色图例待确认。</p>
        <div class="space-y-2 border-y border-line py-2 text-xs">
          <div class="grid grid-cols-2 gap-1">
            <select v-model="category" aria-label="标注功能" class="min-w-0 border border-line px-1.5 py-1"><option v-for="id in MIXED_CATEGORY_IDS" :key="id" :value="id">{{ categoryLabel(id) }}</option></select>
            <select v-model="template" aria-label="标注图例" class="min-w-0 border border-line px-1.5 py-1"><option v-for="id in options(category)" :key="id" :value="id">{{ label(id) }}</option></select>
          </div>
          <label v-if="selected?.categoryId === category" class="flex items-center gap-1"><input v-model="addToExistingGroup" type="checkbox"> 与选中家具同组</label>
          <button v-if="editing.placement" type="button" class="w-full border border-line px-2 py-1" @click="editing.placement = null">取消放置</button>
          <button v-else type="button" data-testid="reference-add" class="w-full border border-line px-2 py-1 disabled:opacity-50" :disabled="recognition.status === 'running'" @click="add">在画布添加家具</button>
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
      <button type="button" data-testid="reference-apply" class="w-full bg-[#83d94f] px-3 py-2 text-xs font-semibold disabled:opacity-50" :disabled="recognition.status === 'running' || !editing?.crop || !editing.marks.length" @click="applyCurrent">应用当前方案</button>
    </div>
  </section>
</template>
