<script setup lang="ts">
import SpatialCanvas from './SpatialCanvas.vue'
import { sameOriginAssetUrl } from '~/utils/apiBase'
import SpatialInspector from './SpatialInspector.vue'
import SpaceTemplateDialog from './SpaceTemplateDialog.vue'
import { reviewScene, reviewSceneBounds, geometryForZone, zoneMeasurements, ringProblem, zoneLayoutProblem, zoneReviewMessage } from '~/utils/spatial/review'
import { fitCameraToBoundsBySystem, toScreen } from '~/utils/spatial/camera'
import type { CameraTransform } from '~/utils/spatial/camera'
import type { FloorPlanDrawingBounds } from '~/types/floorPlan'
import type { ZoneObject } from '~/types/office'
import { transformContour } from '~/utils/spatial/contourTransform'
import type { ContourOperation, ContourPoint } from '~/utils/spatial/contourTransform'
import { canRestoreSourceContour, sourceContour } from '~/utils/spatial/designCorrections'
import { polygonBounds, polygonCentroid, sketchShapePolygon } from '~/utils/spatial/geometry'
import { ZONE_TYPES } from '~/utils/sample'
import type { SketchShapeKind } from '~/utils/spatial/geometry'
import { doorClearanceObstacle, moveObstacle, obstacleLabel, rectangleObstacle, reshapeObstacle, snapObstacleToZone } from '~/utils/spatial/obstacles'
import type { SpatialObstacle, SpatialObstacleKind } from '~/types/spatial'

const store = useOfficeStore(), router = useRouter(), route = useRoute()
const selectedId = ref<string | null>(null)
const focusedZoneId = ref<string | null>(null), selectedObstacleId = ref<string | null>(null)
const obstacleDraft = ref<{ spaceId: string; kind: SpatialObstacleKind; start: { x: number; y: number }; current: { x: number; y: number } } | null>(null)
const obstacleManipulation = ref<{ spaceId: string; obstacleId: string; handle: string; start: { x: number; y: number }; original: SpatialObstacle; current: SpatialObstacle; snapped: boolean } | null>(null)
const snapToEdges = ref(true)
const tool = ref('select'), showAllSpaces = ref(false)
const showUnderlay = ref(true), showOutline = ref(true), showDimensions = ref(true), showOriginal = ref(false)
const comparingOriginal = ref(false)
const underlayOpacity = ref(0.75)
const camera = ref<CameraTransform>({ zoom: 1, panX: 0, panY: 0 })
const viewport = ref({ width: 0, height: 0 }), fittedZoom = ref(1)
const pixelBounds = ref<FloorPlanDrawingBounds | null>(null)
const calibrating = ref(false), calibrationPoints = ref<Array<{ x: number; y: number }>>([])
const calibrationLength = ref(6000), calibrationInput = ref<HTMLInputElement | null>(null)
const drag = ref<{ zoneId: string; index: number; polygon: Array<[number, number]>; initial?: Array<[number, number]>; start?: ContourPoint; operation?: ContourOperation } | null>(null)
const keepAspect = ref(false)
// Stays false while the camera is only auto-fitted; manual pan/zoom disables resize refit.
const cameraTouched = ref(false)
const shiftPressed = ref(false)
const message = ref('')
const tracing = ref<{ zoneId: string; points: Array<{ x: number; y: number }> } | null>(null)
const sketching = ref<{ points: Array<{ x: number; y: number }> } | null>(null)
const renamingId = ref<string | null>(null), renameDraft = ref('')
const labelEditing = ref(false)
const labelName = ref(''), labelCategory = ref<ZoneObject['zoneType']>('OPEN_OFFICE')
const labelWidth = ref<number | null>(null), labelDepth = ref<number | null>(null)
const sketchShape = ref<SketchShapeKind | 'poly'>('rect')
const sketchAngle = ref(0)
const sketchDrag = ref<{ start: { x: number; y: number }; current: { x: number; y: number } } | null>(null)
const showTemplate = ref(false)
let messageTimer: ReturnType<typeof setTimeout> | null = null

const zones = computed(() => store.zones.map(zone => drag.value?.zoneId === zone.id ? { ...zone, polygon: drag.value.polygon } : zone))
const scene = computed(() => reviewScene(zones.value, store.floorPlan, pixelBounds.value))
const selected = computed(() => zones.value.find(zone => zone.id === selectedId.value) || null)
const selectedSpace = computed(() => scene.value.spaces.find(space => space.spaceId === selectedId.value) || null)
const sceneForView = computed(() => {
  const obstacles = scene.value.obstacles.map(obstacle => obstacleManipulation.value?.obstacleId === obstacle.obstacleId ? obstacleManipulation.value.current : obstacle)
  return { ...scene.value, spaces: focusedZoneId.value ? scene.value.spaces.filter(space => space.spaceId === focusedZoneId.value) : scene.value.spaces, obstacles: focusedZoneId.value ? obstacles.filter(obstacle => obstacle.spaceId === focusedZoneId.value) : obstacles }
})
const selectedObstacle = computed(() => obstacleManipulation.value?.obstacleId === selectedObstacleId.value ? obstacleManipulation.value.current : selected.value?.obstacles?.find(obstacle => obstacle.obstacleId === selectedObstacleId.value) || null)
const selectedObstacleBounds = computed(() => selectedObstacle.value ? polygonBounds(selectedObstacle.value.geometry) : null)
/** 端点先取整再旋转，保持模块形状的直角；角度在屏幕上顺时针为正（毫米场景 Y 向上，符号取反）。 */
function sketchSnap(v: number) {
  return (calibrated.value || !store.floorPlan) ? Math.round(v / 100) * 100 : Math.round(v * 100) / 100
}
function sketchShapeRing(start: { x: number; y: number }, current: { x: number; y: number }) {
  const sceneAngle = calibrated.value ? -sketchAngle.value : sketchAngle.value
  return sketchShapePolygon(sketchShape.value as SketchShapeKind,
    { x: sketchSnap(start.x), y: sketchSnap(start.y) },
    { x: sketchSnap(current.x), y: sketchSnap(current.y) }, sceneAngle)
}
const sketchPreview = computed(() => {
  if (sketchDrag.value) {
    const ring = sketchShapeRing(sketchDrag.value.start, sketchDrag.value.current)
    return ring ? ring.map(([x, y]) => ({ x, y })) : sketching.value?.points
  }
  return sketching.value?.points
})
const draftObstaclePreview = computed(() => {
  if (!obstacleDraft.value) return null
  const obstacle = rectangleObstacle(obstacleDraft.value.spaceId, obstacleDraft.value.kind, obstacleDraft.value.start, obstacleDraft.value.current, 'DRAFT')
  return obstacle ? withEdgeSnap(obstacleDraft.value.spaceId, obstacle) : null
})
const draftObstacleGeometry = computed(() => draftObstaclePreview.value?.obstacle.geometry || null)
const selectedIssue = computed(() => selected.value ? zoneReviewMessage(selected.value, zones.value) : null)
const selectedBlocking = computed(() => !!selected.value && !!zoneLayoutProblem(selected.value))
const restorable = computed(() => !!selected.value && store.floorPlan?.inputMode !== 'manual'
  && selected.value.sourceAnalysisId === store.floorPlan?.analysisId && canRestoreSourceContour(selected.value))
const calibrated = computed(() => scene.value.workingCoordinateSystem === 'WORLD_MM')
function withEdgeSnap(zoneId: string, obstacle: SpatialObstacle) {
  const zone = store.zones.find(item => item.id === zoneId)
  if (!zone || !snapToEdges.value) return { obstacle, snapped: false, edgeIndex: null }
  return snapObstacleToZone(zone, obstacle, Math.min(8 / camera.value.zoom, 20))
}
// Screen position of the inline length form: midpoint of the picked edge, clamped into the viewport.
const calibrationInputAnchor = computed(() => {
  if (!calibrating.value || calibrationPoints.value.length !== 2) return null
  const [a, b] = calibrationPoints.value
  const screen = toScreen({ x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }, scene.value.workingCoordinateSystem, camera.value)
  return {
    x: Math.max(120, Math.min(screen.x, Math.max(120, viewport.value.width - 120))),
    y: Math.max(8, Math.min(screen.y, Math.max(8, viewport.value.height - 70))),
  }
})
const labelEditAnchor = computed(() => {
  if (!labelEditing.value || !selectedSpace.value) return null
  const center = polygonCentroid(selectedSpace.value.designGeometry)
  if (!center) return null
  const screen = toScreen(center, scene.value.workingCoordinateSystem, camera.value)
  return {
    x: Math.max(140, Math.min(screen.x, Math.max(140, viewport.value.width - 140))),
    y: Math.max(8, Math.min(screen.y, Math.max(8, viewport.value.height - 170))),
  }
})
const reviewSpaceIds = computed(() => zones.value.filter(zone => zoneReviewMessage(zone, zones.value)).map(zone => zone.id))
const pendingCount = computed(() => reviewSpaceIds.value.length)
const displayedZones = computed(() => showAllSpaces.value ? zones.value : zones.value.slice(0, 6))
const imageUrl = computed(() => sameOriginAssetUrl(store.floorPlan?.previewUrl || store.floorPlan?.referenceImageUrl || store.floorPlan?.imageUrl || ''))
const isExample = computed(() => store.zones.some(zone => zone.evidenceKind === 'DEMO'))


const imageReady = computed(() => !!imageUrl.value && scene.value.underlays.length > 0)
const originalGeometries = computed(() => !showOriginal.value ? [] : store.zones.filter(zone => zone.sourcePolygon?.length).map(zone => geometryForZone({
  ...zone, polygon: zone.sourcePolygon!.map(([x, y]) => [x * (zone.reviewUnit === 'mm' ? zone.mmPerSourceUnit || 1 : 1), y * (zone.reviewUnit === 'mm' ? zone.mmPerSourceUnit || 1 : 1)]),
})))
const scaleWidth = computed(() => 2000 * camera.value.zoom)
const zoomPercent = computed(() => Math.round(camera.value.zoom / fittedZoom.value * 100))
const saveLabel = computed(() => store.syncState === 'saving' ? '保存中…' : store.syncState === 'error' ? '保存失败' : '✓ 本地草稿已保存')

watch(tool, () => { labelEditing.value = false })
watch(selectedId, () => { labelEditing.value = false })
watch(() => store.zones.map(zone => zone.id).join(','), () => {
  if (!store.zones.some(zone => zone.id === selectedId.value)) selectedId.value = store.zones[0]?.id || null
}, { immediate: true })
watch(() => store.floorPlan?.analysisId, () => {
  cancelTrace()
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
  const selectedBounds = focusedZoneId.value && selectedSpace.value ? polygonBounds(selectedSpace.value.designGeometry) : null
  const padding = selectedBounds ? Math.max(600, Math.max(selectedBounds.maxX - selectedBounds.minX, selectedBounds.maxY - selectedBounds.minY) * 0.12) : 0
  const blankManual = !store.floorPlan && !store.zones.length
  const bounds = selectedBounds ? { minX: selectedBounds.minX - padding, minY: selectedBounds.minY - padding, maxX: selectedBounds.maxX + padding, maxY: selectedBounds.maxY + padding } : blankManual ? { minX: 0, minY: 0, maxX: 12000, maxY: 8000 } : reviewSceneBounds(scene.value)
  camera.value = fitCameraToBoundsBySystem(bounds, viewport.value.width, viewport.value.height, scene.value.workingCoordinateSystem, viewport.value.width > 800 ? 72 : 50)
  fittedZoom.value = camera.value.zoom
  cameraTouched.value = false
}
function onCameraUpdate(value: CameraTransform) {
  cameraTouched.value = true
  labelEditing.value = false
  camera.value = value
}
function onViewport(size: { width: number; height: number }) {
  const previous = viewport.value
  viewport.value = size
  if (!previous.width || !previous.height) fit()
  // The first fit may have run on a tiny/transient viewport; recover once the canvas grows,
  // but never override a camera the user already panned or zoomed.
  else if (!cameraTouched.value && !drag.value && size.width * size.height > previous.width * previous.height * 1.4) fit()
  else camera.value = { ...camera.value, panX: camera.value.panX + (size.width - previous.width) / 2, panY: camera.value.panY + (size.height - previous.height) / 2 }
}
function zoom(factor: number) {
  if (drag.value) return
  labelEditing.value = false
  cameraTouched.value = true
  const center = { x: viewport.value.width / 2, y: viewport.value.height / 2 }
  const next = Math.max(0.00001, Math.min(20, camera.value.zoom * factor))
  const ratio = next / camera.value.zoom
  camera.value = { zoom: next, panX: center.x - (center.x - camera.value.panX) * ratio, panY: center.y - (center.y - camera.value.panY) * ratio }
}
function selectSpace(id: string) {
  if (tracing.value) return
  selectedId.value = id
  selectedObstacleId.value = null
  if (!zones.value.slice(0, 6).some(zone => zone.id === id)) showAllSpaces.value = true
}
function focusSelectedSpace() {
  if (!selectedId.value) return
  focusedZoneId.value = focusedZoneId.value ? null : selectedId.value
  selectedObstacleId.value = null
  obstacleManipulation.value = null
  tool.value = 'select'
  cameraTouched.value = false
  nextTick(fit)
}
function showEverySpace() {
  focusedZoneId.value = null
  selectedObstacleId.value = null
  obstacleDraft.value = null
  obstacleManipulation.value = null
  tool.value = 'select'
  cameraTouched.value = false
  nextTick(fit)
}
function startObstacle(kind: SpatialObstacleKind) {
  if (!selected.value || !calibrated.value) return
  if (!focusedZoneId.value) focusSelectedSpace()
  selectedObstacleId.value = null
  obstacleDraft.value = null
  obstacleManipulation.value = null
  tool.value = 'obstacle-' + kind
  showUnderlay.value = true
}
function selectObstacle(id: string | null) {
  if (tool.value !== 'select') return
  selectedObstacleId.value = id
  if (id) selectedId.value = store.zones.find(zone => zone.obstacles?.some(obstacle => obstacle.obstacleId === id))?.id || selectedId.value
}
function startObstacleTransform(id: string, handle: string, point: { x: number; y: number }) {
  if (tool.value !== 'select' || calibrating.value || tracing.value) return
  const zone = store.zones.find(item => item.obstacles?.some(obstacle => obstacle.obstacleId === id))
  const obstacle = zone?.obstacles?.find(item => item.obstacleId === id)
  if (!zone || !obstacle) return
  selectedId.value = zone.id
  selectedObstacleId.value = id
  const original = JSON.parse(JSON.stringify(obstacle)) as SpatialObstacle
  obstacleManipulation.value = { spaceId: zone.id, obstacleId: id, handle, start: point, original, current: JSON.parse(JSON.stringify(original)), snapped: false }
}
function removeSelectedObstacle() {
  if (!selected.value || !selectedObstacleId.value) return
  if (store.removeZoneObstacle(selected.value.id, selectedObstacleId.value)) notify('已删除建筑约束，相关排版需重新核验；可撤销')
  selectedObstacleId.value = null
}
function resizeObstacle(key: 'width' | 'depth' | 'radius', value: number) {
  const obstacle = selectedObstacle.value, bounds = selectedObstacleBounds.value
  if (!obstacle || !bounds || !Number.isFinite(value) || value < 1) return
  if (obstacle.kind === 'DOOR_CLEARANCE') {
    const hinge = obstacle.geometry.outer[0], first = obstacle.geometry.outer[1], last = obstacle.geometry.outer.at(-1)
    if (!hinge || !first || !last || key !== 'radius') return
    const center = { x: hinge[0], y: hinge[1] }
    const next = doorClearanceObstacle(obstacle.spaceId!, center, { x: center.x + Math.sign(first[0] - center.x) * value, y: center.y + Math.sign(last[1] - center.y) * value }, obstacle.obstacleId)
    const problem = next ? store.saveZoneObstacle(obstacle.spaceId!, next) : '扇形半径无效'
    if (problem) notify('半径未保存：' + problem)
    return
  }
  const width = key === 'width' ? value : bounds.maxX - bounds.minX, depth = key === 'depth' ? value : bounds.maxY - bounds.minY
  const center = { x: (bounds.minX + bounds.maxX) / 2, y: (bounds.minY + bounds.maxY) / 2 }
  const next = rectangleObstacle(obstacle.spaceId!, obstacle.kind, { x: center.x - width / 2, y: center.y - depth / 2 }, { x: center.x + width / 2, y: center.y + depth / 2 }, obstacle.obstacleId)
  const snapped = next ? withEdgeSnap(obstacle.spaceId!, next) : null
  const problem = snapped ? store.saveZoneObstacle(obstacle.spaceId!, snapped.obstacle) : '障碍尺寸无效'
  if (problem) notify('尺寸未保存：' + problem)
  else if (snapped?.snapped) notify('尺寸已保存，并已吸附空间边缘')
}
function rotateSelectedDoor() {
  const obstacle = selectedObstacle.value
  if (!obstacle || obstacle.kind !== 'DOOR_CLEARANCE') return
  const hinge = obstacle.geometry.outer[0], first = obstacle.geometry.outer[1], last = obstacle.geometry.outer.at(-1), bounds = selectedObstacleBounds.value
  if (!hinge || !first || !last || !bounds) return
  const directions = [[1, 1], [-1, 1], [-1, -1], [1, -1]]
  const center = { x: hinge[0], y: hinge[1] }
  const signX = Math.sign(first[0] - center.x), signY = Math.sign(last[1] - center.y)
  const index = directions.findIndex(([x, y]) => x === signX && y === signY), nextDirection = directions[(index + 1) % directions.length]
  const radius = Math.max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY)
  const next = doorClearanceObstacle(obstacle.spaceId!, center, { x: center.x + nextDirection[0] * radius, y: center.y + nextDirection[1] * radius }, obstacle.obstacleId)
  const problem = next ? store.saveZoneObstacle(obstacle.spaceId!, next) : '开启方向无效'
  if (problem) notify('方向未保存：' + problem)
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
  if (focusedZoneId.value === zone.id) focusedZoneId.value = null
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
watch(() => calibrating.value && calibrationPoints.value.length === 2, ready => {
  if (ready) nextTick(() => calibrationInput.value?.focus())
})
function applyCalibrationInput() {
  if (!Number.isFinite(calibrationLength.value) || calibrationLength.value <= 0) return
  applyCalibration(Number(calibrationLength.value))
}

function pointerDown(point: { x: number; y: number }) {
  labelEditing.value = false
  if (tool.value.startsWith('obstacle-') && selected.value && calibrated.value) {
    obstacleDraft.value = { spaceId: selected.value.id, kind: tool.value.slice(9) as SpatialObstacleKind, start: point, current: point }
    return
  }
  if (sketching.value && sketchShape.value !== 'poly') {
    sketchDrag.value = { start: point, current: point }
    return
  }
  if (tracing.value || sketching.value) {
    const points = (tracing.value || sketching.value)!.points, previous = points.at(-1)
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
function startSketch() {
  message.value = ''
  cancelCalibration()
  cancelTrace()
  drag.value = null
  tool.value = 'draw-space'
  sketching.value = { points: [] }
}
function cancelSketch() {
  sketching.value = null
  sketchDrag.value = null
  if (tool.value === 'draw-space') tool.value = 'select'
}
/** 形状模块/逐点描共用的落库：毫米场景自动取整到 100mm，源单位保留两位小数。 */
function saveSketchRing(raw: Array<[number, number]>, snapMm: boolean) {
  const asMm = calibrated.value || !store.floorPlan
  const snap = (v: number) => asMm && snapMm ? Math.round(v / 100) * 100 : Math.round(v * 100) / 100
  let ring = raw.map(([px, py]): [number, number] => [snap(px), snap(calibrated.value ? -py : py)])
  ring = ring.filter((point, index) => index === 0 || Math.hypot(point[0] - ring[index - 1][0], point[1] - ring[index - 1][1]) > 0.01)
  if (ring.length > 3 && Math.hypot(ring[0][0] - ring.at(-1)![0], ring[0][1] - ring.at(-1)![1]) < 0.01) ring.pop()
  const problem = ringProblem(ring)
  if (problem) { notify(problem + '，请调整后再保存'); return }
  const zone = store.addDrawnZone(ring, asMm)
  if (!zone) { notify('空间未保存，请重新绘制'); return }
  cancelSketch()
  selectedId.value = zone.id
  if (store.zones.length === 1) nextTick(fit)
  notify(asMm ? `已创建「${zone.name}」（毫米），可在右侧改名、编辑轮廓与尺寸；可撤销` : `已按底图源单位创建「${zone.name}」，两点校准后获得毫米尺寸；可撤销`)
}
function applySketch() {
  const draft = sketching.value
  if (!draft) return
  const points = draft.points
  if (points.length > 3 && Math.hypot(points[0].x - points.at(-1)!.x, points[0].y - points.at(-1)!.y) * camera.value.zoom < 8) points.pop()
  saveSketchRing(points.map((point): [number, number] => [point.x, point.y]), false)
}
function renameSpace(name: string) {
  const zone = selected.value, trimmed = name.trim()
  if (!zone || !trimmed || trimmed === zone.name) return
  store.updateObject(zone.id, { name: trimmed } as Partial<ZoneObject>)
  notify(`已重命名为「${trimmed}」`)
}
function startRoomRename(zone: ZoneObject) {
  renamingId.value = zone.id
  renameDraft.value = zone.name
  nextTick(() => (document.querySelector('.review-room-rename-form input') as HTMLInputElement | null)?.focus())
}
function commitRoomRename(zoneId: string) {
  if (renamingId.value !== zoneId) return
  renamingId.value = null
  const zone = store.zones.find(item => item.id === zoneId), trimmed = renameDraft.value.trim()
  if (!zone || !trimmed || trimmed === zone.name) return
  store.updateObject(zone.id, { name: trimmed } as Partial<ZoneObject>)
  notify(`已重命名为「${trimmed}」`)
}
/** 点画布上选中空间的名称标签：就地改名称、用途与矩形尺寸。 */
function onSpaceLabel(zoneId: string) {
  if (tool.value !== 'select' || tracing.value || sketching.value || calibrating.value || drag.value) return
  const zone = store.zones.find(item => item.id === zoneId)
  if (!zone) return
  selectedId.value = zoneId
  labelName.value = zone.name
  labelCategory.value = zone.zoneType
  const metrics = zoneMeasurements(zone)
  labelWidth.value = metrics.width != null ? Math.round(metrics.width) : null
  labelDepth.value = metrics.depth != null ? Math.round(metrics.depth) : null
  labelEditing.value = true
}
function applyLabelEdit() {
  const zone = selected.value
  if (!zone) { labelEditing.value = false; return }
  const name = labelName.value.trim()
  if (name && name !== zone.name) store.updateObject(zone.id, { name } as Partial<ZoneObject>)
  if (labelCategory.value !== zone.zoneType) store.updateObject(zone.id, { zoneType: labelCategory.value } as Partial<ZoneObject>)
  const metrics = zoneMeasurements(zone)
  if (selectedSpace.value?.rectangleFrame && calibrated.value && labelWidth.value && labelDepth.value && labelWidth.value > 0 && labelDepth.value > 0
    && (Math.round(metrics.width || 0) !== labelWidth.value || Math.round(metrics.depth || 0) !== labelDepth.value)) {
    store.resizeZoneByMm(zone.id, labelWidth.value, labelDepth.value)
  }
  labelEditing.value = false
  notify('空间属性已更新，尺寸与面积同步联动')
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
  if (event.key === 'Escape' && sketching.value) { event.preventDefault(); cancelSketch() }
  if (event.key === 'Escape' && labelEditing.value) { event.preventDefault(); labelEditing.value = false }
  if (event.key === 'Escape' && drag.value) { event.preventDefault(); drag.value = null; notify('已取消本次轮廓调整') }
  if (event.key === 'Escape' && obstacleDraft.value) { event.preventDefault(); obstacleDraft.value = null }
  if (event.key === 'Escape' && obstacleManipulation.value) { event.preventDefault(); obstacleManipulation.value = null; notify('已取消本次约束调整') }
  else if (event.key === 'Escape' && tool.value.startsWith('obstacle-')) { event.preventDefault(); tool.value = 'select' }
  if ((event.key === 'Delete' || event.key === 'Backspace') && !event.repeat && !tracing.value && !sketching.value && !calibrating.value && !drag.value && !obstacleManipulation.value && selectedId.value) {
    const target = event.target as HTMLElement | null
    if (target?.closest('input, textarea, select, [contenteditable]')) return
    event.preventDefault()
    if (selectedObstacleId.value) removeSelectedObstacle()
    else deleteSelectedSpace()
  }
}
function keyUp(event: KeyboardEvent) { shiftPressed.value = event.shiftKey }
function cancelInteraction() { drag.value = null; obstacleDraft.value = null; obstacleManipulation.value = null; sketchDrag.value = null; labelEditing.value = false; shiftPressed.value = false }
function startTransform(zoneId: string, operation: ContourOperation, point: ContourPoint) {
  if (calibrating.value || tracing.value || sketching.value) return
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
  if (sketchDrag.value) { sketchDrag.value.current = point; return }
  if (obstacleDraft.value) { obstacleDraft.value.current = point; return }
  if (obstacleManipulation.value) {
    const draft = obstacleManipulation.value
    const candidate = draft.handle === 'move' ? moveObstacle(draft.original, point.x - draft.start.x, point.y - draft.start.y) : reshapeObstacle(draft.original, draft.handle, point)
    if (candidate) {
      const snapped = withEdgeSnap(draft.spaceId, candidate)
      draft.current = snapped.obstacle
      draft.snapped = snapped.snapped
    }
    return
  }
  if (!drag.value) return
  const draft = drag.value, current = { x: point.x, y: calibrated.value ? -point.y : point.y }
  if (draft.operation && draft.start && draft.initial) {
    if (Math.hypot(current.x - draft.start.x, current.y - draft.start.y) * camera.value.zoom < 3) {
      draft.polygon = draft.initial.map(([x, y]) => [x, y])
    } else draft.polygon = transformContour(draft.initial, draft.operation, draft.start, current, keepAspect.value || shiftPressed.value)
  } else draft.polygon[draft.index] = [current.x, current.y]
}
function pointerUp() {
  if (sketchDrag.value) {
    const draft = sketchDrag.value
    sketchDrag.value = null
    const ring = sketchShapeRing(draft.start, draft.current)
    const tooSmall = Math.abs(draft.current.x - draft.start.x) * camera.value.zoom < 6 || Math.abs(draft.current.y - draft.start.y) * camera.value.zoom < 6
    if (!ring || tooSmall) { notify('请拖出清晰的空间范围'); return }
    saveSketchRing(ring, false)
    return
  }
  if (obstacleDraft.value) {
    const draft = obstacleDraft.value
    const raw = rectangleObstacle(draft.spaceId, draft.kind, draft.start, draft.current)
    const snapped = raw ? withEdgeSnap(draft.spaceId, raw) : null
    const obstacle = snapped?.obstacle || null
    obstacleDraft.value = null
    const bounds = obstacle ? polygonBounds(obstacle.geometry) : null
    if (!obstacle || !bounds || (bounds.maxX - bounds.minX) * camera.value.zoom < 6 || (bounds.maxY - bounds.minY) * camera.value.zoom < 6) { notify(draft.kind === 'DOOR_CLEARANCE' ? '请从铰链角拖出清晰的开启区域' : '请拖出清晰的矩形区域'); return }
    const problem = store.saveZoneObstacle(draft.spaceId, obstacle)
    if (problem) { notify('约束未保存：' + problem); return }
    selectedObstacleId.value = obstacle.obstacleId
    tool.value = 'select'
    notify(obstacleLabel(obstacle.kind) + (snapped?.snapped ? '已吸附空间边缘并保存；可撤销' : '已保存，排版将自动避开；可撤销'))
    return
  }
  if (obstacleManipulation.value) {
    const draft = obstacleManipulation.value
    obstacleManipulation.value = null
    if (JSON.stringify(draft.original.geometry) === JSON.stringify(draft.current.geometry)) return
    const problem = store.saveZoneObstacle(draft.spaceId, draft.current)
    if (problem) { notify('约束调整未保存：' + problem); return }
    notify(draft.snapped ? '已吸附空间边缘并保存；可撤销' : draft.handle === 'move' ? '约束位置已更新；可撤销' : '约束尺寸已更新；可撤销')
    return
  }
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
  if (tracing.value || sketching.value || obstacleDraft.value || obstacleManipulation.value) return
  if (!store.canEnterLayout || store.floorPlanLoading || store.floorPlanError) return
  const zone = store.zones.find(item => item.id === selectedId.value) || store.zones[0]
  if (zone && store.enterDesign(zone.id)) router.push({ path: '/canvas', query: { ...route.query, mode: 'design', zoneId: zone.id } })
}
function loadExample() {
  store.loadReviewExample()
  selectedId.value = store.zones[0]?.id ?? null
  showAllSpaces.value = false
  focusedZoneId.value = null
  selectedObstacleId.value = null
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
            <form v-if="renamingId === zone.id" class="review-room-rename-form" @submit.prevent="commitRoomRename(zone.id)">
              <input v-model="renameDraft" :aria-label="'重命名空间 ' + zone.name" maxlength="40" @blur="commitRoomRename(zone.id)" @keydown.esc.prevent="renamingId = null">
            </form>
            <button v-else class="review-room-card" :class="{ selected: zone.id === selectedId }" :aria-pressed="zone.id === selectedId" :aria-label="'选择空间 ' + zone.name" @click="selectSpace(zone.id)">
              <div><strong>{{ zone.name }}</strong><span class="review-badge" :class="status(zone) === '可用' ? 'verified' : 'pending'">{{ status(zone) }}</span></div>
              <div><small>{{ dimensions(zone) }}</small><small>{{ zoneMeasurements(zone).area?.toFixed(1) ?? '—' }} m²</small></div>
            </button>
            <button class="review-room-rename" type="button" :disabled="!!tracing || calibrating || !!sketching" :aria-label="'重命名空间 ' + zone.name" title="重命名空间" @click="startRoomRename(zone)">改名</button>
            <button class="review-room-delete" type="button" :disabled="!!tracing || calibrating" :aria-label="'删除空间 ' + zone.name" title="删除空间（选中后也可按 Delete 键）" @click="deleteSpace(zone.id)">删除</button>
          </div>
          <button v-if="zones.length > 6" class="show-more-rooms" @click="showAllSpaces = !showAllSpaces">{{ showAllSpaces ? '收起空间列表 ↑' : '查看其余 ' + (zones.length - 6) + ' 个空间 ↓' }}</button>
          <template v-if="!zones.length">
            <p class="inspector-hint">暂无空间：上传图纸识别，或直接手动画区。</p>
            <button class="review-example-button" type="button" data-testid="sketch-start" @click="startSketch">手动画新空间</button>
          </template>
        </div>
        <div class="review-project-note"><p>✓ 底图与轮廓共用同一坐标变换</p><p>✓ 原始识别与编辑副本分别保留</p></div>
        <button class="review-example-button" :disabled="!!tracing" @click="loadExample">加载工装示例（可撤销）</button>
      </aside>
      <section class="review-canvas-panel review-panel">
        <div v-if="store.spaceTemplate" class="template-layout-link"><span>{{ store.spaceTemplate.name }} · {{ store.spaceTemplateCurrent ? store.spaceTemplate.storage === 'SAVED' ? '模板已保存' : '本地模板' : '空间已修改，模板需更新' }}</span><button @click="showTemplate = true">查看模板</button></div>
        <div class="review-toolbar">
          <div class="review-tools" :inert="!!tracing || !!sketching">
            <button :class="{ active: tool === 'select' }" @click="tool = 'select'">选择 / 缩放</button>
            <button :class="{ active: tool === 'pan' }" @click="tool = 'pan'">拖动画布</button><i></i>
            <button :class="{ active: tool === 'edit' }" @click="tool = 'edit'">编辑轮廓</button>
            <button :class="{ active: tool === 'draw-space' }" :disabled="calibrating" title="在画布上逐点画出新的空间轮廓" @click="startSketch">画新空间</button>
            <button :class="{ active: calibrating }" @click="startCalibration">两点校准</button>
            <button :disabled="!store.undoStack.length" @click="store.undo()">撤销</button>
          </div>
          <div class="review-zoom"><button aria-label="缩小画布" @click="zoom(1 / 1.2)">−</button><span>{{ zoomPercent }}%</span><button aria-label="放大画布" @click="zoom(1.2)">＋</button><i></i><button @click="fit">适屏</button></div>
        </div>
          <div v-if="focusedZoneId && !calibrating && !tracing" class="constraint-edit-bar">
            <div class="constraint-tool-buttons">
              <button :class="{ active: tool === 'obstacle-COLUMN' }" @click="startObstacle('COLUMN')">标柱</button>
              <button :class="{ active: tool === 'obstacle-DOOR_CLEARANCE' }" @click="startObstacle('DOOR_CLEARANCE')">门洞净空</button>
              <button :class="{ active: tool === 'obstacle-NO_PLACEMENT' }" @click="startObstacle('NO_PLACEMENT')">禁放区</button>
            </div>
            <label class="edge-snap-toggle" title="柱/井在创建、移动或调整时吸附到空间水平/垂直边，最大吸附距离 20mm"><input v-model="snapToEdges" type="checkbox">贴墙吸附</label>
            <span v-if="draftObstaclePreview?.snapped || obstacleManipulation?.snapped" class="edge-snap-active">已吸附边缘</span>
            <span v-if="tool === 'obstacle-DOOR_CLEARANCE'">从门扇铰链角拖向开启区域，形成四分之一圆弧，Esc 取消</span>
            <span v-else-if="tool.startsWith('obstacle-')">在当前空间底图上拖出矩形，Esc 取消</span>
            <div v-else-if="selectedObstacle && selectedObstacleBounds" class="selected-obstacle-editor">
              <strong>{{ obstacleLabel(selectedObstacle.kind) }}</strong>
              <label v-if="selectedObstacle.kind === 'DOOR_CLEARANCE'">半径 mm<input :value="Math.round(Math.max(selectedObstacleBounds.maxX - selectedObstacleBounds.minX, selectedObstacleBounds.maxY - selectedObstacleBounds.minY))" type="number" min="1" @change="resizeObstacle('radius', Number(($event.target as HTMLInputElement).value))"></label>
              <template v-else><label>宽 mm<input :value="Math.round(selectedObstacleBounds.maxX - selectedObstacleBounds.minX)" type="number" min="1" @change="resizeObstacle('width', Number(($event.target as HTMLInputElement).value))"></label><label>深 mm<input :value="Math.round(selectedObstacleBounds.maxY - selectedObstacleBounds.minY)" type="number" min="1" @change="resizeObstacle('depth', Number(($event.target as HTMLInputElement).value))"></label></template>
              <button v-if="selectedObstacle.kind === 'DOOR_CLEARANCE'" @click="rotateSelectedDoor">换向</button>
              <button @click="removeSelectedObstacle">删除</button>
            </div>
            <span v-else>拖动标注移动；角点或弧线手柄调整尺寸，柱靠近墙边可自动吸附</span>
          </div>
          <div v-else-if="tool === 'select' && !calibrating" class="contour-edit-bar">
          <span>拖动内部移动；拉边调整宽深，拉角缩放。滚轮缩放视图，中键平移。</span>
          <label><input v-model="keepAspect" type="checkbox"> 锁定比例（角点 / Shift）</label>
        </div>
        <div v-if="sketching" class="contour-edit-bar" data-testid="sketch-bar">
          <span class="sketch-shapes" role="group" aria-label="空间形状模块">
            <button :class="{ active: sketchShape === 'rect' }" data-testid="sketch-shape-rect" @click="sketchShape = 'rect'">矩形</button>
            <button :class="{ active: sketchShape === 'L' }" data-testid="sketch-shape-L" @click="sketchShape = 'L'">L 形</button>
            <button :class="{ active: sketchShape === 'U' }" data-testid="sketch-shape-U" @click="sketchShape = 'U'">U 形</button>
            <button :class="{ active: sketchShape === 'poly' }" data-testid="sketch-shape-poly" @click="sketchShape = 'poly'">逐点描</button>
          </span>
          <label v-if="sketchShape !== 'poly'" class="sketch-angle">旋转<input v-model.number="sketchAngle" type="number" min="-180" max="180" step="5" aria-label="画区旋转角度（度）"><span>°</span></label>
          <span v-else>沿墙依次点击转角，已选 {{ sketching.points.length }} 点{{ store.floorPlan ? '' : '（空白画布，单位毫米）' }}</span>
          <span v-if="sketchShape !== 'poly'">在画布上拖出空间范围，松开即生成{{ sketchShape === 'rect' ? '矩形' : sketchShape === 'L' ? 'L 形' : 'U 形' }}轮廓{{ sketchAngle ? '（顺时针 ' + sketchAngle + '°）' : '' }}{{ calibrated || !store.floorPlan ? '，自动取整 100mm' : '' }}，生成后可用「编辑轮廓」微调</span>
          <template v-if="sketchShape === 'poly'">
            <button :disabled="!sketching.points.length" @click="sketching.points.pop()">退回一点</button>
            <button :disabled="sketching.points.length < 3" data-testid="sketch-save" @click="applySketch">保存空间</button>
          </template>
          <button @click="cancelSketch">取消</button>
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
          <SpatialCanvas :scene="sceneForView" :camera="camera" :selected-space-id="selectedId" :selected-obstacle-id="selectedObstacleId" :obstacle-draft="draftObstacleGeometry" :obstacle-draft-kind="obstacleDraft?.kind" :tool="tool" :interaction-active="!!drag || !!obstacleManipulation"
            :pointer-in-source-coordinates="calibrating || !!tracing || !!sketching" :calibration-points="calibrationPoints" :trace-points="tracing?.points || sketchPreview"
            :fallback-image-url="imageUrl" :show-underlay="showUnderlay || comparingOriginal" :show-outline="showOutline && !comparingOriginal"
            :show-dimensions="showDimensions" :underlay-opacity="underlayOpacity" :original-geometries="comparingOriginal ? [] : originalGeometries"
            @viewport="onViewport" @update-camera="onCameraUpdate" @select-space="selectSpace" @space-label="onSpaceLabel" @select-obstacle="selectObstacle" @obstacle-transform-start="startObstacleTransform" @select-vertex="selectVertex" @insert-vertex="insertVertex" @transform-start="startTransform" @pointer-cancel="cancelInteraction"
            @pointer-down="pointerDown" @pointer-move="pointerMove" @pointer-up="pointerUp" />
          <div v-if="!imageReady && !sketching && (store.floorPlan || !zones.length)" class="missing-source-note">
            <template v-if="!store.floorPlan">
              <strong>空白手动画布</strong>
              <p>无需图纸：点击「画新空间」逐点描出房间轮廓，单位为毫米；也可以返回上传图纸识别。</p>
              <button type="button" data-testid="sketch-start-overlay" @click="startSketch">开始画空间</button>
              <NuxtLink to="/">返回图纸识别 →</NuxtLink>
            </template>
            <template v-else>
              <strong>{{ imageUrl ? '底图定位信息待补充' : '当前草稿未关联可对照底图' }}</strong><p>请重新应用带原图与轮廓的识别结果；原草稿保留，可撤销编辑。</p><NuxtLink to="/">返回图纸识别 →</NuxtLink>
            </template>
          </div>
          <div v-if="calibrating && calibrationPoints.length < 2" class="calibration-canvas-hint">两点校准：请依次点击已知尺寸边的两个端点<button @click="cancelCalibration">取消</button></div>
          <form v-if="calibrationInputAnchor" class="calibration-inline" :style="{ left: calibrationInputAnchor.x + 'px', top: calibrationInputAnchor.y + 'px' }" @submit.prevent="applyCalibrationInput">
            <label>实际长度<input ref="calibrationInput" v-model.number="calibrationLength" aria-label="校准参考长度（毫米）" type="number" min="1" step="any"><span>mm</span></label>
            <div class="calibration-inline-actions"><button type="submit" :disabled="!Number.isFinite(calibrationLength) || calibrationLength <= 0">应用校准</button><button type="button" @click="cancelCalibration">取消</button></div>
          </form>
          <form v-if="labelEditAnchor" class="calibration-inline label-inline" :style="{ left: labelEditAnchor.x + 'px', top: labelEditAnchor.y + 'px' }" data-testid="label-inline" @submit.prevent="applyLabelEdit">
            <label>名称<input v-model="labelName" aria-label="画布空间名称" maxlength="40"></label>
            <label>用途<select v-model="labelCategory" aria-label="画布空间用途"><option v-for="[value, text] in ZONE_TYPES" :key="value" :value="value">{{ text }}</option></select></label>
            <template v-if="selectedSpace?.rectangleFrame && calibrated">
              <label>宽度<input v-model.number="labelWidth" aria-label="画布空间宽度（毫米）" type="number" min="1" step="any"><span>mm</span></label>
              <label>进深<input v-model.number="labelDepth" aria-label="画布空间进深（毫米）" type="number" min="1" step="any"><span>mm</span></label>
            </template>
            <div class="calibration-inline-actions"><button type="submit" :disabled="!labelName.trim()">保存</button><button type="button" @click="labelEditing = false">取消</button></div>
          </form>
          <div class="review-legend"><span><i class="outline"></i>空间轮廓</span><span><i class="selected"></i>当前选中</span><span v-if="scene.obstacles.length"><i class="obstacle"></i>固定障碍</span><span v-if="scene.obstacles.some(item => item.kind === 'DOOR_CLEARANCE')"><i class="door"></i>门洞净空</span></div>
          <div class="review-scale" v-if="calibrated && scaleWidth > 20 && scaleWidth < viewport.width / 2" :style="{ width: scaleWidth + 'px' }"><span>0</span><span>2 m</span><i :style="{ width: scaleWidth + 'px' }"></i></div>
          <div v-if="!tracing" class="review-canvas-actions"><button @pointerdown="comparingOriginal = true" @pointerup="comparingOriginal = false" @pointerleave="comparingOriginal = false" @pointercancel="comparingOriginal = false" @keydown.space.prevent="comparingOriginal = true" @keyup.space="comparingOriginal = false" @blur="comparingOriginal = false">{{ store.floorPlan?.inputMode === 'manual' ? '按住对照模板' : '按住对照原图' }}</button><button :disabled="!selectedId" @click="focusSelectedSpace">{{ focusedZoneId ? '返回全图' : '放大核对当前空间' }}</button><button @click="showEverySpace">显示全部空间</button></div>
          <div v-if="message" class="review-toast" role="status">{{ message }}</div>
        </div>
        <footer class="review-canvas-footer"><div><span><b>{{ zones.length }}</b> 个空间</span><span><b>{{ scene.obstacles.length }}</b> 处建筑约束</span><span class="pending-total"><b>{{ pendingCount }}</b> 需复核</span></div><button class="template-workbench-button" :disabled="!store.canEnterLayout || calibrating || !!tracing || !!obstacleDraft || !!obstacleManipulation" @click="showTemplate = true">{{ store.spaceTemplate ? '查看空间模板' : '生成空间模板' }}</button><button :disabled="!store.canEnterLayout || calibrating || !!tracing || !!obstacleDraft || !!obstacleManipulation" @click="advance">进入家具排版 →</button></footer>
      </section>
      <SpatialInspector :inert="!!tracing" :space="selectedSpace" :is-calibrated="calibrated" :pending-count="pendingCount" :issue="selectedIssue" :issue-blocking="selectedBlocking" :needs-review="!!selectedId && reviewSpaceIds.includes(selectedId)" :layout-blocker="store.layoutBlocker" :template-source="store.floorPlan?.inputMode === 'manual'"
        :source-note="selected?.note" :can-restore-source="restorable && !calibrating && !drag"
        :calibration-active="calibrating"
        v-model:show-underlay="showUnderlay" v-model:show-outline="showOutline" v-model:show-dimensions="showDimensions" v-model:show-original="showOriginal" v-model:underlay-opacity="underlayOpacity"
        @resize="resize" @category="category" @rename="renameSpace" @start-calibration="startCalibration" @restore-source="restoreSelectedSource">
      </SpatialInspector>
    </div>
    <SpaceTemplateDialog v-if="showTemplate" @close="showTemplate = false" @layout="zoneId => router.push({ path: '/canvas', query: { mode: 'design', zoneId } })" />
    <footer class="review-page-footer"><span>空间编辑 / 原图对照 → 比例校准 → 按需修正 → 家具排版</span><span>{{ isExample ? '交互示例 · 非真实识别结果' : '滚轮缩放 · 中键平移 · Delete 删除选中空间' }}</span></footer>
  </div>
</template>

<style src="~/assets/css/space-review.css"></style>
<style src="~/assets/css/space-template.css"></style>
