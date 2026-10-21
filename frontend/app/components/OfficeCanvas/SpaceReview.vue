<script setup lang="ts">
import SpatialCanvas from './SpatialCanvas.vue'
import SpatialInspector from './SpatialInspector.vue'
import DimensionCalibration from './DimensionCalibration.vue'
import SpaceTemplateDialog from './SpaceTemplateDialog.vue'
import type { DimensionReference, DimensionReferenceOverlay } from '~/types/dimensionCalibration'
import { polygonArea } from '~/utils/spatial/geometry'
import { reviewScene, reviewSceneBounds, geometryForZone, zoneMeasurements, ringProblem, zoneLayoutProblem, zoneReviewMessage } from '~/utils/spatial/review'
import { fitCameraToBoundsBySystem } from '~/utils/spatial/camera'
import type { CameraTransform } from '~/utils/spatial/camera'
import type { FloorPlanDrawingBounds } from '~/types/floorPlan'
import type { ZoneObject } from '~/types/office'
import { transformContour } from '~/utils/spatial/contourTransform'
import type { ContourOperation, ContourPoint } from '~/utils/spatial/contourTransform'
import { canRestoreSourceContour, sourceContour } from '~/utils/spatial/designCorrections'

const store = useOfficeStore(), router = useRouter(), route = useRoute()
const selectedId = ref<string | null>(null)
const tool = ref('select'), showAllSpaces = ref(false)
const showUnderlay = ref(true), showOutline = ref(true), showDimensions = ref(true), showOriginal = ref(false)
const comparingOriginal = ref(false)
const selectedDimensionReference = ref<DimensionReference | null>(null)
const underlayOpacity = ref(0.75)
const camera = ref<CameraTransform>({ zoom: 1, panX: 0, panY: 0 })
const viewport = ref({ width: 0, height: 0 }), fittedZoom = ref(1)
const pixelBounds = ref<FloorPlanDrawingBounds | null>(null)
const calibrating = ref(false), calibrationPoints = ref<Array<{ x: number; y: number }>>([])
const drag = ref<{ zoneId: string; index: number; polygon: Array<[number, number]>; initial?: Array<[number, number]>; start?: ContourPoint; operation?: ContourOperation } | null>(null)
const keepAspect = ref(false)
const shiftPressed = ref(false)
const message = ref('')
const tracing = ref<{ zoneId: string; points: Array<{ x: number; y: number }> } | null>(null)
const showTemplate = ref(false)
let messageTimer: ReturnType<typeof setTimeout> | null = null

const zones = computed(() => store.zones.map(zone => drag.value?.zoneId === zone.id ? { ...zone, polygon: drag.value.polygon } : zone))
const scene = computed(() => reviewScene(zones.value, store.floorPlan, pixelBounds.value))
const selected = computed(() => zones.value.find(zone => zone.id === selectedId.value) || null)
const selectedSpace = computed(() => scene.value.spaces.find(space => space.spaceId === selectedId.value) || null)
const selectedIssue = computed(() => selected.value ? zoneReviewMessage(selected.value, zones.value) : null)
const selectedBlocking = computed(() => !!selected.value && !!zoneLayoutProblem(selected.value))
const restorable = computed(() => !!selected.value && store.floorPlan?.inputMode !== 'manual'
  && selected.value.sourceAnalysisId === store.floorPlan?.analysisId && canRestoreSourceContour(selected.value))
const calibrated = computed(() => scene.value.workingCoordinateSystem === 'WORLD_MM')
const reviewSpaceIds = computed(() => zones.value.filter(zone => zoneReviewMessage(zone, zones.value)).map(zone => zone.id))
const pendingCount = computed(() => reviewSpaceIds.value.length)
const displayedZones = computed(() => showAllSpaces.value ? zones.value : zones.value.slice(0, 6))
const imageUrl = computed(() => store.floorPlan?.previewUrl || store.floorPlan?.referenceImageUrl || store.floorPlan?.imageUrl || '')
const isExample = computed(() => store.zones.some(zone => zone.evidenceKind === 'DEMO'))
const dimensionCalibrationEnabled = computed(() => !!store.floorPlan?.analysisId && !isExample.value && ['image', 'pdf'].includes(store.floorPlan.inputMode || '') && store.floorPlan.geometrySource !== 'cad_geometry')
const dimensionReferenceOverlay = computed<DimensionReferenceOverlay | null>(() => {
  const reference = selectedDimensionReference.value
  if (!reference?.start || !reference.end || calibrating.value || comparingOriginal.value) return null
  const scale = calibrated.value ? store.zones[0]?.mmPerSourceUnit || 1 : 1
  const point = ([x, y]: [number, number]) => ({ x: x * scale, y: y * scale * (calibrated.value ? -1 : 1) })
  return { start: point(reference.start), end: point(reference.end), label: reference.text, accepted: reference.status === 'ACCEPTED' }
})
const automaticPreviewArea = computed(() => {
  const report = store.floorPlan?.dimensionCalibration
  if (!selected.value || report?.status !== 'READY' || !report.mmPerPixel) return null
  const currentScale = calibrated.value ? store.zones[0]?.mmPerSourceUnit || 1 : 1
  return polygonArea(geometryForZone(selected.value)) * (report.mmPerPixel / currentScale) ** 2 / 1_000_000
})
const imageReady = computed(() => !!imageUrl.value && scene.value.underlays.length > 0)
const originalGeometries = computed(() => !showOriginal.value ? [] : store.zones.filter(zone => zone.sourcePolygon?.length).map(zone => geometryForZone({
  ...zone, polygon: zone.sourcePolygon!.map(([x, y]) => [x * (zone.reviewUnit === 'mm' ? zone.mmPerSourceUnit || 1 : 1), y * (zone.reviewUnit === 'mm' ? zone.mmPerSourceUnit || 1 : 1)]),
})))
const scaleWidth = computed(() => 2000 * camera.value.zoom)
const zoomPercent = computed(() => Math.round(camera.value.zoom / fittedZoom.value * 100))
const saveLabel = computed(() => store.syncState === 'saving' ? '保存中…' : store.syncState === 'error' ? '保存失败' : '✓ 本地草稿已保存')

watch(() => store.zones.map(zone => zone.id).join(','), () => {
  if (!store.zones.some(zone => zone.id === selectedId.value)) selectedId.value = store.zones[0]?.id || null
}, { immediate: true })
watch(() => store.floorPlan?.analysisId, () => {
  cancelTrace()
  selectedDimensionReference.value = null
  cancelCalibration()
  nextTick(fit)
}, { immediate: true })
watch(imageUrl, (url) => {
  pixelBounds.value = null
  if (!url) return
  const image = new Image()
  image.onload = () => {
    if (url !== imageUrl.value) return
    // Pixel fallback is only safe for an explicitly pixel-based source.
    const unit = store.floorPlan?.unitResolution as { canonicalUnit?: string } | null
    if (unit?.canonicalUnit === 'px') pixelBounds.value = { minX: 0, minY: 0, maxX: image.naturalWidth, maxY: image.naturalHeight }
    nextTick(fit)
  }
  image.src = url
}, { immediate: true })

function fit() {
  if (!viewport.value.width || !viewport.value.height) return
  camera.value = fitCameraToBoundsBySystem(reviewSceneBounds(scene.value), viewport.value.width, viewport.value.height, scene.value.workingCoordinateSystem, viewport.value.width > 800 ? 72 : 50)
  fittedZoom.value = camera.value.zoom
}
function onViewport(size: { width: number; height: number }) {
  const previous = viewport.value
  viewport.value = size
  if (!previous.width || !previous.height) fit()
  else camera.value = { ...camera.value, panX: camera.value.panX + (size.width - previous.width) / 2, panY: camera.value.panY + (size.height - previous.height) / 2 }
}
function zoom(factor: number) {
  if (drag.value) return
  const center = { x: viewport.value.width / 2, y: viewport.value.height / 2 }
  const next = Math.max(0.00001, Math.min(20, camera.value.zoom * factor))
  const ratio = next / camera.value.zoom
  camera.value = { zoom: next, panX: center.x - (center.x - camera.value.panX) * ratio, panY: center.y - (center.y - camera.value.panY) * ratio }
}
function selectSpace(id: string) {
  if (tracing.value) return
  selectedId.value = id
  if (!zones.value.slice(0, 6).some(zone => zone.id === id)) showAllSpaces.value = true
}
function restoreSelectedSource() {
  const zone = selected.value
  if (!zone || !restorable.value || tracing.value || calibrating.value || drag.value) return
  const polygon = sourceContour(zone)
  if (!polygon) return
  const xs = polygon.map(p => p[0]), ys = polygon.map(p => p[1])
  const w = Math.max(...xs) - Math.min(...xs), h = Math.max(...ys) - Math.min(...ys)
  const room = store.floorPlan?.rooms.find(room => room.roomId === zone.id)
  store.updateObject(zone.id, {
    polygon, x: Math.min(...xs), y: Math.min(...ys), w, h,
    actualWidthMm: zone.reviewUnit === 'mm' ? w : undefined,
    actualDepthMm: zone.reviewUnit === 'mm' ? h : undefined,
    geometrySource: room?.geometrySource || store.floorPlan?.geometrySource,
    source: 'recognition', evidenceKind: store.floorPlan?.evidenceKind || 'ESTIMATED', contourCorrection: undefined,
  } as Partial<ZoneObject>)
  notify('已恢复本次识别轮廓，可撤销；关联排版需重新核验')
}
function deleteSpace(zoneId: string) {
  if (tracing.value || calibrating.value || drag.value) return
  const zone = store.zones.find(item => item.id === zoneId)
  if (!zone || !store.deleteZone(zone.id)) return
  if (selectedId.value === zone.id) selectedId.value = store.zones[0]?.id || null
  notify(`已删除「${zone.name}」，可点击“撤销”恢复`)
}
function deleteSelectedSpace() {
  if (selectedId.value) deleteSpace(selectedId.value)
}
function dimensions(zone: ZoneObject) {
  if (zone.reviewUnit !== 'mm') return '尺寸待校准'
  const metrics = zoneMeasurements(zone)
  return metrics.width && metrics.depth ? Math.round(metrics.width).toLocaleString() + ' × ' + Math.round(metrics.depth).toLocaleString() + ' mm' : '轮廓待补充'
}
function status(zone: ZoneObject) {
  return zoneReviewMessage(zone, zones.value) ? '需复核' : '可用'
}
function notify(value: string) {
  message.value = value
  if (messageTimer) clearTimeout(messageTimer)
  messageTimer = setTimeout(() => { message.value = '' }, 3000)
}
function category(value: string) {
  if (selectedId.value) store.updateObject(selectedId.value, { zoneType: value as ZoneObject['zoneType'] })
}
function resize(width: number, depth: number) {
  if (!selectedId.value) return
  store.resizeZoneByMm(selectedId.value, width, depth)
  notify('空间轮廓与面积已更新，关联排版需更新')
}
function startCalibration() {
  cancelTrace()
  tool.value = 'select'
  calibrating.value = true
  calibrationPoints.value = []
  showUnderlay.value = true
}
function cancelCalibration() {
  calibrating.value = false
  calibrationPoints.value = []
}
function applyCalibration(length: number) {
  if (calibrationPoints.value.length !== 2) return
  const [a, b] = calibrationPoints.value
  if (!store.calibrateReviewPage(Math.hypot(a.x - b.x, a.y - b.y), length)) {
    notify('参考点不能重合，实际长度必须大于零')
    return
  }
  cancelCalibration()
  fit()
  notify('图纸比例已更新，可直接进入家具排版')
}
function applyAutomaticCalibration() {
  cancelCalibration()
  fit()
  notify('自动比例已应用，可直接进入家具排版')
}
function pointerDown(point: { x: number; y: number }) {
  if (tracing.value) {
    const points = tracing.value.points, previous = points.at(-1)
    if (!previous || Math.hypot(point.x - previous.x, point.y - previous.y) * camera.value.zoom > 3) points.push(point)
    return
  }
  if (calibrating.value && calibrationPoints.value.length < 2) calibrationPoints.value.push(point)
}
function startTrace() {
  if (!selected.value) return
  message.value = ''
  cancelCalibration()
  drag.value = null
  tool.value = 'trace'
  showUnderlay.value = true
  tracing.value = { zoneId: selected.value.id, points: [] }
}
function cancelTrace() {
  tracing.value = null
  if (tool.value === 'trace') tool.value = 'edit'
}
function applyTrace() {
  const draft = tracing.value
  if (!draft) return
  const polygon: Array<[number, number]> = draft.points.map(point => [point.x, calibrated.value ? -point.y : point.y])
  // Clicking the starting point to close is optional; the stored ring never repeats it.
  if (polygon.length > 3 && Math.hypot(polygon[0][0] - polygon.at(-1)![0], polygon[0][1] - polygon.at(-1)![1]) * camera.value.zoom < 8) polygon.pop()
  const problem = ringProblem(polygon)
  if (problem) { notify(problem + '，请调整描点后再保存'); return }
  if (!store.updateZonePolygon(draft.zoneId, polygon)) { notify('轮廓未保存，请重新选择空间'); return }
  cancelTrace()
  notify('已保存沿墙轮廓，尺寸和面积同步更新；可撤销')
}
function insertVertex(zoneId: string, index: number) {
  const zone = store.zones.find(item => item.id === zoneId)
  if (!zone?.polygon || tracing.value || calibrating.value) return
  const polygon = zone.polygon.map(([x, y]): [number, number] => [x, y])
  const a = polygon[index], b = polygon[(index + 1) % polygon.length]
  polygon.splice(index + 1, 0, [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2])
  drag.value = { zoneId, index: index + 1, polygon }
}
function traceKey(event: KeyboardEvent) {
  shiftPressed.value = event.shiftKey
  if (event.key === 'Escape' && tracing.value) { event.preventDefault(); cancelTrace() }
  if (event.key === 'Escape' && drag.value) { event.preventDefault(); drag.value = null; notify('已取消本次轮廓调整') }
  if ((event.key === 'Delete' || event.key === 'Backspace') && !event.repeat && !tracing.value && !calibrating.value && !drag.value && selectedId.value) {
    const target = event.target as HTMLElement | null
    if (target?.closest('input, textarea, select, [contenteditable]')) return
    event.preventDefault()
    deleteSelectedSpace()
  }
}
function keyUp(event: KeyboardEvent) { shiftPressed.value = event.shiftKey }
function cancelInteraction() { drag.value = null; shiftPressed.value = false }
function startTransform(zoneId: string, operation: ContourOperation, point: ContourPoint) {
  if (calibrating.value || tracing.value) return
  const zone = store.zones.find(item => item.id === zoneId)
  if (!zone?.polygon) return
  const polygon = zone.polygon.map(([x, y]): [number, number] => [x, y])
  drag.value = { zoneId, index: -1, polygon, initial: polygon, operation, start: { x: point.x, y: calibrated.value ? -point.y : point.y } }
}
function selectVertex(zoneId: string, vertexId: string) {
  if (calibrating.value || tool.value === 'pan') return
  const zone = store.zones.find(item => item.id === zoneId)
  if (!zone?.polygon) return
  const index = geometryForZone(zone).outerVertexIds.indexOf(vertexId)
  if (index >= 0) drag.value = { zoneId, index, polygon: zone.polygon.map(([x, y]) => [x, y]) }
}
function pointerMove(point: { x: number; y: number }) {
  if (!drag.value) return
  const draft = drag.value, current = { x: point.x, y: calibrated.value ? -point.y : point.y }
  if (draft.operation && draft.start && draft.initial) {
    if (Math.hypot(current.x - draft.start.x, current.y - draft.start.y) * camera.value.zoom < 3) {
      draft.polygon = draft.initial.map(([x, y]) => [x, y])
    } else draft.polygon = transformContour(draft.initial, draft.operation, draft.start, current, keepAspect.value || shiftPressed.value)
  } else draft.polygon[draft.index] = [current.x, current.y]
}
function pointerUp() {
  const draft = drag.value
  if (!draft) return
  const before = store.zones.find(zone => zone.id === draft.zoneId)?.polygon
  if (JSON.stringify(before) !== JSON.stringify(draft.polygon)) {
    const problem = ringProblem(draft.polygon)
    if (problem) notify(problem + '，已恢复拖动前轮廓')
    else store.updateZonePolygon(draft.zoneId, draft.polygon)
  }
  drag.value = null
}
function advance() {
  if (tracing.value) return
  if (!store.canEnterLayout || store.floorPlanLoading || store.floorPlanError) return
  const zone = store.zones.find(item => item.id === selectedId.value) || store.zones[0]
  if (zone && store.enterDesign(zone.id)) router.push({ path: '/canvas', query: { ...route.query, mode: 'design', zoneId: zone.id } })
}
function loadExample() {
  store.loadReviewExample()
  selectedId.value = store.zones[0]?.id ?? null
  showAllSpaces.value = false
  nextTick(fit)
}
onMounted(() => { window.addEventListener('keydown', traceKey); window.addEventListener('keyup', keyUp); window.addEventListener('blur', cancelInteraction) })
onBeforeUnmount(() => { if (messageTimer) clearTimeout(messageTimer); window.removeEventListener('keydown', traceKey); window.removeEventListener('keyup', keyUp); window.removeEventListener('blur', cancelInteraction) })
</script>

<template>
  <div class="space-review">
    <header class="review-topbar">
      <NuxtLink to="/" class="review-brand"><b>R</b><strong>RSDP</strong><i></i><span>办公空间设计</span></NuxtLink>
      <div class="review-topbar-right"><NuxtLink to="/">项目工作台 / 办公空间</NuxtLink><span class="local-save" :class="{ error: store.syncState === 'error', saving: store.syncState === 'saving' }" :title="store.syncError || undefined">{{ saveLabel }}</span><NuxtLink to="/products" class="designer-avatar" title="商品资源">设计</NuxtLink></div>
    </header>
    <section class="review-intro">
      <div><h1>无限画布 · 空间核对</h1><p>空间可直接用于家具排版；需复核处会提示，发现不符可随时修改。</p></div>
      <nav aria-label="项目设计步骤" class="review-steps">
        <NuxtLink to="/" class="done"><b>✓</b><span>图纸识别</span></NuxtLink><i></i>
        <span class="current" aria-current="step"><b>2</b><span>空间编辑</span></span><i></i>
        <button :disabled="!store.canEnterLayout || !!tracing" @click="advance"><b>3</b><span>家具排版</span></button><i></i>
        <span><b>4</b><span>商品推荐</span></span><i></i><span><b>5</b><span>方案与效果图</span></span>
      </nav>
    </section>
    <div v-if="store.floorPlanError" class="review-error" role="alert">{{ store.floorPlanError }}</div>
    <div class="review-columns">
      <aside class="review-project review-panel">
        <div class="project-section">
          <div class="section-heading"><h2>项目与空间</h2><small>本地草稿</small></div>
          <div class="review-project-card"><h3>{{ store.floorPlan?.sourceName || store.drawingName }}</h3><p>{{ calibrated ? '图纸比例已校准，尺寸单位 mm' : '比例待校准，可先核对空间轮廓' }}</p></div>
          <div class="review-source-file"><span>{{ store.floorPlan?.inputMode === 'manual' ? '模板' : store.floorPlan?.inputMode === 'pdf' ? 'PDF' : store.floorPlan?.inputMode === 'cad' ? 'CAD' : isExample ? 'SVG' : 'PNG' }}</span><div><strong>{{ store.floorPlan?.sourceName || '待关联原始图纸' }}</strong><small>{{ isExample ? '演示底图 · 示例几何' : store.floorPlan?.inputMode === 'manual' ? '空间模板 · 布局设计副本' : '原始底图 · 当前识别页' }}</small></div></div>
        </div>
        <div class="review-room-list">
          <div class="section-heading"><h4>识别空间</h4><small>{{ zones.length }} 个</small></div>
          <div v-for="zone in displayedZones" :key="zone.id" class="review-room-item" :class="{ selected: zone.id === selectedId }">
            <button class="review-room-card" :class="{ selected: zone.id === selectedId }" :aria-pressed="zone.id === selectedId" :aria-label="'选择空间 ' + zone.name" @click="selectSpace(zone.id)">
              <div><strong>{{ zone.name }}</strong><span class="review-badge" :class="status(zone) === '可用' ? 'verified' : 'pending'">{{ status(zone) }}</span></div>
              <div><small>{{ dimensions(zone) }}</small><small>{{ zoneMeasurements(zone).area?.toFixed(1) ?? '—' }} m²</small></div>
            </button>
            <button class="review-room-delete" type="button" :disabled="!!tracing || calibrating" :aria-label="'删除空间 ' + zone.name" title="删除空间（选中后也可按 Delete 键）" @click="deleteSpace(zone.id)">删除</button>
          </div>
          <button v-if="zones.length > 6" class="show-more-rooms" @click="showAllSpaces = !showAllSpaces">{{ showAllSpaces ? '收起空间列表 ↑' : '查看其余 ' + (zones.length - 6) + ' 个空间 ↓' }}</button>
          <p v-if="!zones.length" class="inspector-hint">暂无可核对空间，请先上传并识别图纸。</p>
        </div>
        <div class="review-project-note"><p>✓ 底图与轮廓共用同一坐标变换</p><p>✓ 原始识别与编辑副本分别保留</p></div>
        <button class="review-example-button" :disabled="!!tracing" @click="loadExample">加载工装示例（可撤销）</button>
      </aside>
      <section class="review-canvas-panel review-panel">
        <div v-if="store.spaceTemplate" class="template-layout-link"><span>{{ store.spaceTemplate.name }} · {{ store.spaceTemplateCurrent ? store.spaceTemplate.storage === 'SAVED' ? '模板已保存' : '本地模板' : '空间已修改，模板需更新' }}</span><button @click="showTemplate = true">查看模板</button></div>
        <div class="review-toolbar">
          <div class="review-tools" :inert="!!tracing">
            <button :class="{ active: tool === 'select' }" @click="tool = 'select'">选择 / 缩放</button>
            <button :class="{ active: tool === 'pan' }" @click="tool = 'pan'">拖动画布</button><i></i>
            <button :class="{ active: tool === 'edit' }" @click="tool = 'edit'">编辑轮廓</button>
            <button :class="{ active: calibrating }" @click="startCalibration">两点校准</button>
            <button :disabled="!store.undoStack.length" @click="store.undo()">撤销</button>
          </div>
          <div class="review-zoom"><button aria-label="缩小画布" @click="zoom(1 / 1.2)">−</button><span>{{ zoomPercent }}%</span><button aria-label="放大画布" @click="zoom(1.2)">＋</button><i></i><button @click="fit">适屏</button></div>
        </div>
        <div v-if="tool === 'select' && !calibrating" class="contour-edit-bar">
          <span>拖动内部移动；拉边调整宽深，拉角缩放。滚轮缩放视图，中键平移。</span>
          <label><input v-model="keepAspect" type="checkbox"> 锁定比例（角点 / Shift）</label>
        </div>
        <div v-if="tool === 'edit' || tracing" class="contour-edit-bar">
          <template v-if="tracing">
            <span>正在重描「{{ selected?.name }}」：沿墙内侧依次点击转角，已选 {{ tracing.points.length }} 点</span>
            <button :disabled="!tracing.points.length" @click="tracing.points.pop()">退回一点</button>
            <button :disabled="tracing.points.length < 3" @click="applyTrace">保存轮廓</button>
            <button @click="cancelTrace">取消重描</button>
          </template>
          <template v-else>
            <span>拖动顶点或整条边贴合墙体；点击 ＋ 补转折点。</span>
            <button :disabled="!selected" @click="startTrace">沿墙重描轮廓</button>
          </template>
        </div>
        <div class="review-canvas">
          <div class="review-alignment-status"><b :class="{ unknown: !calibrated }"></b><span>{{ calibrated ? '比例已校准' : '实际尺寸待校准' }}</span><i></i><span>{{ imageReady ? '底图与轮廓共用坐标' : '底图定位待补充' }}</span></div>
          <span v-if="isExample" class="example-label">界面示例 · 演示几何</span>
          <SpatialCanvas :scene="scene" :camera="camera" :selected-space-id="selectedId" :tool="tool" :interaction-active="!!drag"
            :pointer-in-source-coordinates="calibrating || !!tracing" :calibration-points="calibrationPoints" :trace-points="tracing?.points"
            :fallback-image-url="imageUrl" :show-underlay="showUnderlay || comparingOriginal" :show-outline="showOutline && !comparingOriginal"
            :show-dimensions="showDimensions" :underlay-opacity="underlayOpacity" :original-geometries="comparingOriginal ? [] : originalGeometries"
            :dimension-reference="dimensionReferenceOverlay"
            @viewport="onViewport" @update-camera="camera = $event" @select-space="selectSpace" @select-vertex="selectVertex" @insert-vertex="insertVertex" @transform-start="startTransform" @pointer-cancel="cancelInteraction"
            @pointer-down="pointerDown" @pointer-move="pointerMove" @pointer-up="pointerUp" />
          <div v-if="!imageReady" class="missing-source-note"><strong>{{ imageUrl ? '底图定位信息待补充' : '当前草稿未关联可对照底图' }}</strong><p>请重新应用带原图与轮廓的识别结果；原草稿保留，可撤销编辑。</p><NuxtLink to="/">返回图纸识别 →</NuxtLink></div>
          <div v-if="calibrating" class="calibration-canvas-hint">{{ calibrationPoints.length < 2 ? '两点校准：请依次点击已知尺寸边的两个端点' : '已选择参考边，请在右侧输入实际长度并应用' }}<button @click="cancelCalibration">取消</button></div>
          <div class="review-legend"><span><i class="outline"></i>空间轮廓</span><span><i class="selected"></i>当前选中</span></div>
          <div class="review-scale" v-if="calibrated && scaleWidth > 20 && scaleWidth < viewport.width / 2" :style="{ width: scaleWidth + 'px' }"><span>0</span><span>2 m</span><i :style="{ width: scaleWidth + 'px' }"></i></div>
          <div v-if="!tracing" class="review-canvas-actions"><button @pointerdown="comparingOriginal = true" @pointerup="comparingOriginal = false" @pointerleave="comparingOriginal = false" @pointercancel="comparingOriginal = false" @keydown.space.prevent="comparingOriginal = true" @keyup.space="comparingOriginal = false" @blur="comparingOriginal = false">{{ store.floorPlan?.inputMode === 'manual' ? '按住对照模板' : '按住对照原图' }}</button><button @click="fit">显示全部空间</button></div>
          <div v-if="message" class="review-toast" role="status">{{ message }}</div>
        </div>
        <footer class="review-canvas-footer"><div><span><b>{{ zones.length }}</b> 个空间</span><span class="pending-total"><b>{{ pendingCount }}</b> 需复核</span></div><button class="template-workbench-button" :disabled="!store.canEnterLayout || calibrating || !!tracing" @click="showTemplate = true">{{ store.spaceTemplate ? '查看空间模板' : '生成空间模板' }}</button><button :disabled="!store.canEnterLayout || calibrating || !!tracing" @click="advance">进入家具排版 →</button></footer>
      </section>
      <SpatialInspector :inert="!!tracing" :space="selectedSpace" :is-calibrated="calibrated" :pending-count="pendingCount" :issue="selectedIssue" :issue-blocking="selectedBlocking" :needs-review="!!selectedId && reviewSpaceIds.includes(selectedId)" :layout-blocker="store.layoutBlocker" :template-source="store.floorPlan?.inputMode === 'manual'"
        :source-note="selected?.note" :can-restore-source="restorable && !calibrating && !drag"
        :calibration-active="calibrating" :calibration-point-count="calibrationPoints.length"
        v-model:show-underlay="showUnderlay" v-model:show-outline="showOutline" v-model:show-dimensions="showDimensions" v-model:show-original="showOriginal" v-model:underlay-opacity="underlayOpacity"
        @resize="resize" @category="category" @start-calibration="startCalibration" @cancel-calibration="cancelCalibration" @apply-calibration="applyCalibration" @restore-source="restoreSelectedSource">
        <template #automatic-calibration><DimensionCalibration :enabled="dimensionCalibrationEnabled" :preview-area="automaticPreviewArea" @reference="selectedDimensionReference = $event" @applied="applyAutomaticCalibration" /></template>
      </SpatialInspector>
    </div>
    <SpaceTemplateDialog v-if="showTemplate" @close="showTemplate = false" @layout="zoneId => router.push({ path: '/canvas', query: { mode: 'design', zoneId } })" />
    <footer class="review-page-footer"><span>空间编辑 / 原图对照 → 比例校准 → 按需修正 → 家具排版</span><span>{{ isExample ? '交互示例 · 非真实识别结果' : '滚轮缩放 · 中键平移 · Delete 删除选中空间' }}</span></footer>
  </div>
</template>

<style src="~/assets/css/space-review.css"></style>
<style src="~/assets/css/space-template.css"></style>
