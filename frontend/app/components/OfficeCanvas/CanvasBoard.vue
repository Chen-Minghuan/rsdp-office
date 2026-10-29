<script setup lang="ts">
import { uid } from '~/utils/helpers'
import { sameOriginAssetUrl } from '~/utils/apiBase'
import { TONES } from '~/utils/sample'
import type { OfficeObject, ZoneObject, FurnitureObject, NoteObject } from '~/types/office'
import type { FloorPlanAnalysisResponse, FloorPlanDrawingBounds } from '~/types/floorPlan'
import { normalizePolygon, roomsBounds } from '~/utils/floorPlan'
import { manualOfficeFurniture, officePlacementHardProblem, officeFurnitureMoveProblem, officeFurnitureFootprint } from '~/utils/officeManualPlacement'
import { snapFurnitureEdges, FURNITURE_SNAP_TOLERANCE_MM } from '~/utils/furnitureSnap'
import { officeFurnitureTemplateId, officeFurnitureShowsSeat } from '~/utils/officeFurniture'
import { chairActivityRect } from '~/utils/layoutPlanning'
import { canvasObjectsForView, canvasZoneBounds, fitCanvasZone, canvasPointerWorld, rotateCanvasVector } from '~/utils/officeCanvasView'
import { rectangleObstacle, moveObstacle, reshapeObstacle, obstacleExclusion, obstacleLabel, snapObstacleToZone } from '~/utils/spatial/obstacles'
import type { SpatialObstacle, SpatialObstacleKind } from '~/types/spatial'
import type { LayoutChannel, LayoutExclusion } from '~/types/layoutPlanning'
import type { LayoutBlocker } from '~/utils/layoutConstraints'
import { channelRects, channelOffsetRange, CHANNEL_WIDTH_HINT_MM } from '~/utils/layoutChannels'


const store = useOfficeStore()
const props = defineProps<{ layoutPreview?: { zoneId: string; furniture: FurnitureObject[]; replaceIds: string[]; seatClearanceMm: number } | null }>()
const activePreview = computed(() => props.layoutPreview?.zoneId === store.designZoneId ? props.layoutPreview : null)
const viewRotation = defineModel<number>('viewRotation', { default: 0 })
const showDimensions = defineModel<boolean>('showDimensions', { default: true })
const tool = defineModel<string>('tool', { default: 'select' })
const selectedId = defineModel<string | null>('selectedId')
const showSubmit = defineModel<boolean>('showSubmit')
const mode = defineModel<'overview' | 'design'>('mode', { default: 'overview' })
const snapEnabled = defineModel<boolean>('snapEnabled', { default: true })
const showUnderlay = defineModel<boolean>('showUnderlay', { default: true })
const underlayOpacity = defineModel<number>('underlayOpacity', { default: 0.75 })
const showOutline = ref(true)
const showOriginal = ref(false)

const DEFAULT_W = 1540
const DEFAULT_H = 960
const WORLD_PADDING = 240
const NOTE_SCREEN_WIDTH = 220
const NOTE_SCREEN_MIN_HEIGHT = 104
const NOTE_SCREEN_FONT_SIZE = 14

const canvasWindow = ref<HTMLDivElement | null>(null)
const board = ref<HTMLDivElement | null>(null)
const drawPreview = ref<HTMLDivElement | null>(null)

const visibleObjects = computed(() => canvasObjectsForView(store.objects, mode.value, store.designZoneId))
const worldBounds = computed(() => {
  if (mode.value === 'design' && designZone.value) {
    const b = canvasZoneBounds(designZone.value)
    return { minX: b.minX - WORLD_PADDING, minY: b.minY - WORLD_PADDING, maxX: b.maxX + WORLD_PADDING, maxY: b.maxY + WORLD_PADDING, width: b.maxX - b.minX + WORLD_PADDING * 2, height: b.maxY - b.minY + WORLD_PADDING * 2 }
  }
  let minX = 0
  let minY = 0
  let maxX = DEFAULT_W
  let maxY = DEFAULT_H
  for (const o of visibleObjects.value) {
    let w = 0
    let h = 0
    if (o.kind === 'zone' || o.kind === 'furniture' || o.kind === 'reference') {
      w = (o as any).w || 0
      h = (o as any).h || 0
    } else if (o.kind === 'note') {
      w = 180
      h = 114
    } else if (o.kind === 'arrow') {
      w = Math.abs((o as any).x2 - o.x)
      h = Math.abs((o as any).y2 - o.y)
    }
    minX = Math.min(minX, o.x - WORLD_PADDING)
    minY = Math.min(minY, o.y - WORLD_PADDING)
    maxX = Math.max(maxX, o.x + w + WORLD_PADDING)
    maxY = Math.max(maxY, o.y + h + WORLD_PADDING)
  }
  return { minX, minY, maxX, maxY, width: maxX - minX, height: maxY - minY }
})

const hintText = computed(() => {
  if (placing.value) return placementMessage.value || placementProblem.value || '点击空间内逐件放置 · R 旋转 · Esc 结束'
  if (moveProblem.value) return moveProblem.value
  if (deleteFeedback.value) return deleteFeedback.value
  if (constraintMessage.value) return constraintMessage.value
  if (mode.value === 'design' && tool.value === 'select') return '点击选中 · Shift/Ctrl 点选多个 · 空白处框选 · 拖动选中家具一起移动'
  if (isConstraintTool.value) return constraintKind.value === 'DOOR_CLEARANCE' ? '从门扇铰链角拖向开启区域，Esc 取消' : '在当前空间底图上拖出矩形标注，Esc 取消'
  const map: Record<string, string> = {
    select: '选择并拖动画布对象，右侧可编辑属性',
    pan: '按住并拖动画布，也可以使用滚轮缩放',
    zone: '在画布上按住并拖出矩形功能区',
    note: '在画布上单击，添加并编辑需求便签',
    arrow: '在画布上拖拽，绘制指向箭头',
    reference: '点击画布放置参考素材，再编辑其说明',
  }
  return map[tool.value] || ''
})

const zones = computed(() => store.objects.filter((o) => o.kind === 'zone') as ZoneObject[])
const visibleZones = computed(() => visibleObjects.value.filter((o) => o.kind === 'zone') as ZoneObject[])
const arrows = computed(() => visibleObjects.value.filter((o) => o.kind === 'arrow'))
const designZone = computed(() => zones.value.find((z) => z.id === store.designZoneId) || null)
const designObstacles = computed(() => designZone.value?.obstacles?.map(obstacle => obstacleExclusion(designZone.value!, obstacle)).filter((area): area is LayoutExclusion => !!area) || [])
const constraintKind = computed<SpatialObstacleKind>(() => tool.value.startsWith('constraints-') ? tool.value.slice('constraints-'.length) as SpatialObstacleKind : 'NO_PLACEMENT')
const isConstraintTool = computed(() => tool.value.startsWith('constraints'))
const selectedConstraintId = ref<string | null>(null)
const constraintEditPreview = ref<SpatialObstacle | null>(null)
const constraintMessage = ref('')
const displayedDesignObstacles = computed(() => designObstacles.value.map(area => {
  if (constraintEditPreview.value?.obstacleId !== area.id || !designZone.value) return area
  return obstacleExclusion(designZone.value, constraintEditPreview.value) || area
}))
const selectedConstraint = computed(() => designZone.value?.obstacles?.find(obstacle => obstacle.obstacleId === selectedConstraintId.value) || null)
const selectedConstraintArea = computed(() => displayedDesignObstacles.value.find(area => area.id === selectedConstraintId.value) || null)
const constraintCreateArea = computed<LayoutExclusion | null>(() => {
  const current = drag.value
  if (current?.mode !== 'constraints' || constraintKind.value !== 'DOOR_CLEARANCE' || !designZone.value || current.lastX === undefined || current.lastY === undefined) return null
  const obstacle = rectangleObstacle(designZone.value.id, 'DOOR_CLEARANCE', { x: current.x || 0, y: -(current.y || 0) }, { x: current.lastX, y: -current.lastY }, 'DOOR_DRAFT')
  return obstacle ? obstacleExclusion(designZone.value, obstacle) : null
})
function isEditableConstraint(area: LayoutExclusion) {
  return designZone.value?.obstacles?.some(obstacle => obstacle.obstacleId === area.id) || false
}
/** 通道条带：渲染和拖动预览基于多边形裁剪后的分段矩形（凹形空间一条通道可能多段）；标签与拖柄挂在最大分段上。 */
const designChannels = computed(() => {
  const zone = designZone.value
  if (!zone) return [] as Array<{ channel: LayoutChannel; rect: LayoutBlocker; primary: boolean; key: string }>
  return (zone.layoutPreferences?.channels || []).flatMap(channel => {
    const segments = channelRects(zone, [channel])
    if (!segments.length) return [] as Array<{ channel: LayoutChannel; rect: LayoutBlocker; primary: boolean; key: string }>
    const largest = segments.reduce((best, rect) => rect.w * rect.h > best.w * best.h ? rect : best, segments[0])
    return segments.map((rect, index) => ({ channel, rect, primary: rect === largest, key: `${channel.id}:${index}` }))
  })
})
function channelStyle(entry: { channel: LayoutChannel; rect: LayoutBlocker }) {
  const d = drag.value
  const vertical = entry.channel.orientation === 'vertical'
  const selected = selectedChannelId.value === entry.channel.id
  let { x, y, w, h } = entry.rect
  if (d?.mode === 'channel' && d.channelId === entry.channel.id) {
    // 位置拖动预览：只沿垂直轴平移
    if (vertical) x += d.dx || 0
    else y += d.dx || 0
  } else if (d?.mode === 'channel-width' && d.channelId === entry.channel.id) {
    // 宽度拖柄预览：净距不变，禁放带边缘随宽度等量伸缩
    const dx = d.dx || 0
    if (vertical) { if (d.edge === 'max') w += dx; else { x += dx; w -= dx } }
    else { if (d.edge === 'max') h += dx; else { y += dx; h -= dx } }
  }
  return {
    left: x + 'px',
    top: y + 'px',
    width: Math.max(1, w) + 'px',
    height: Math.max(1, h) + 'px',
    background: selected
      ? 'repeating-linear-gradient(45deg, rgba(48,111,203,0.22) 0 12px, rgba(48,111,203,0.06) 12px 24px)'
      : 'repeating-linear-gradient(45deg, rgba(48,111,203,0.10) 0 12px, rgba(48,111,203,0.02) 12px 24px)',
    border: `${(selected ? 2.5 : 1.5) / store.zoom}px ${selected ? 'solid' : 'dashed'} #306fcb`,
  }
}
/** 选中的通道（design 模式点选条带）；编辑走 store.updateZoneChannels 同一入口。 */
const selectedChannel = computed(() => {
  const zone = designZone.value
  if (!zone || !selectedChannelId.value) return null
  return (zone.layoutPreferences?.channels || []).find(channel => channel.id === selectedChannelId.value) || null
})
watch([designZone, designChannels], () => {
  if (selectedChannelId.value && !selectedChannel.value) selectedChannelId.value = null
})
function updateSelectedChannel(patch: Partial<LayoutChannel>) {
  const zone = designZone.value
  if (!zone || !selectedChannelId.value) return
  const id = selectedChannelId.value
  store.updateZoneChannels(zone.id, (zone.layoutPreferences?.channels || []).map(channel => channel.id === id ? { ...channel, ...patch } : channel))
}
function removeSelectedChannel() {
  const zone = designZone.value
  if (!zone || !selectedChannelId.value) return
  const id = selectedChannelId.value
  store.updateZoneChannels(zone.id, (zone.layoutPreferences?.channels || []).filter(channel => channel.id !== id))
  selectedChannelId.value = null
}
/** 选中通道位置输入的相对基准（与排版面板「距左/距上」一致）。 */
const designZoneBounds = computed(() => designZone.value ? canvasZoneBounds(designZone.value) : null)
/** 选中通道浮动编辑卡的屏幕位置：跟随通道条带（含位置/宽度拖动预览），优先放条带右侧（横向条带放下侧），空间不足换边，并钳制在画布窗口内。 */
const channelEditorStyle = computed(() => {
  const channel = selectedChannel.value
  const v = canvasWindow.value
  if (!channel || !v) return {}
  const entry = designChannels.value.find(item => item.channel.id === channel.id && item.primary) || designChannels.value.find(item => item.channel.id === channel.id)
  if (!entry) return {}
  let { x, y, w, h } = entry.rect
  const d = drag.value
  if (d?.mode === 'channel' && d.channelId === channel.id) {
    if (channel.orientation === 'vertical') x += d.dx || 0
    else y += d.dx || 0
  } else if (d?.mode === 'channel-width' && d.channelId === channel.id) {
    const dx = d.dx || 0
    if (channel.orientation === 'vertical') { if (d.edge === 'max') w += dx; else { x += dx; w -= dx } }
    else { if (d.edge === 'max') h += dx; else { y += dx; h -= dx } }
  }
  const world = worldBounds.value
  const center = { x: world.minX + world.width / 2, y: world.minY + world.height / 2 }
  const toScreen = (wx: number, wy: number) => {
    const local = rotateCanvasVector({ x: wx - center.x, y: wy - center.y }, viewRotation.value)
    return { x: local.x * store.zoom + v.clientWidth / 2 + store.panX, y: local.y * store.zoom + v.clientHeight / 2 + store.panY }
  }
  const p0 = toScreen(x, y), p1 = toScreen(x + w, y + h)
  const band = { minX: Math.min(p0.x, p1.x), maxX: Math.max(p0.x, p1.x), minY: Math.min(p0.y, p1.y), maxY: Math.max(p0.y, p1.y) }
  const cardW = 264, cardH = 260, gap = 12
  let left: number, top: number
  if (band.maxY - band.minY >= band.maxX - band.minX) {
    const rightSpace = v.clientWidth - 8 - (band.maxX + gap)
    const leftSpace = band.minX - gap - 8
    left = rightSpace >= cardW ? band.maxX + gap : leftSpace >= cardW ? band.minX - gap - cardW : rightSpace >= leftSpace ? v.clientWidth - 8 - cardW : 8
    top = band.minY
  } else {
    const belowSpace = v.clientHeight - 8 - (band.maxY + gap)
    const aboveSpace = band.minY - gap - 8
    top = belowSpace >= cardH ? band.maxY + gap : aboveSpace >= cardH ? band.minY - gap - cardH : belowSpace >= aboveSpace ? v.clientHeight - 8 - cardH : 8
    left = band.minX
  }
  left = Math.max(8, Math.min(left, Math.max(8, v.clientWidth - cardW - 8)))
  top = Math.max(8, Math.min(top, Math.max(8, v.clientHeight - cardH - 8)))
  return { left: `${Math.round(left)}px`, top: `${Math.round(top)}px` }
})
function obstaclePoints(area: LayoutExclusion) {
  const polygon = area.polygon || [[area.x, area.y], [area.x + area.w, area.y], [area.x + area.w, area.y + area.h], [area.x, area.y + area.h]]
  return polygon.map(([x, y]) => `${x - area.x},${y - area.y}`).join(' ')
}
function constraintHandles(area: LayoutExclusion) {
  const polygon = area.polygon || []
  const kind = designZone.value?.obstacles?.find(obstacle => obstacle.obstacleId === area.id)?.kind
  if (kind === 'DOOR_CLEARANCE') {
    const point = polygon[Math.floor(polygon.length / 2)]
    return point ? [{ handle: 'radius', point }] : []
  }
  return polygon.slice(0, 4).map((point, index) => ({ handle: String(index), point }))
}
const furniture = computed(() => {
  if (mode.value !== 'design') return []
  return visibleObjects.value.filter((o) => o.kind === 'furniture') as FurnitureObject[]
})
const dimensionFurniture = computed(() => {
  const pending = activePreview.value
  const items = drag.value?.mode !== 'furniture' && pending?.furniture.length ? [...furniture.value.filter(item => !pending.replaceIds.includes(item.id)), ...pending.furniture] : furniture.value
  return items.map(item => drag.value?.mode === 'furniture' && drag.value.ids?.includes(item.id)
    ? { ...item, x: item.x + (drag.value.dx || 0), y: item.y + (drag.value.dy || 0) } : item)
})

// While a pending proposal preview is shown, hide the auto-group pieces it will
// replace: the canvas shows the post-apply state instead of stacking the new
// plan on top of the old one. Nothing is deleted until the user applies.
const displayedFurniture = computed(() => {
  const pending = activePreview.value
  if (drag.value?.mode === 'furniture' || !pending?.furniture.length) return furniture.value
  return furniture.value.filter(item => !pending.replaceIds.includes(item.id))
})

const placementPointer = ref<{ x: number; y: number } | null>(null)
const placementMessage = ref('')
const placing = computed(() => mode.value === 'design' && store.furniturePlacement?.zoneId === store.designZoneId && !!store.furniturePlacement)
const placementPreview = computed(() => placing.value && placementPointer.value
  ? manualOfficeFurniture(store.furniturePlacement!, placementPointer.value.x, placementPointer.value.y) : null)
// Manual arranging snaps to walls and other modules within the tolerance.
const placementSnap = computed(() => {
  if (!placementPreview.value || !designZone.value || !snapEnabled.value) return { item: placementPreview.value, guides: { x: null as number | null, y: null as number | null } }
  const item = placementPreview.value
  const fp = officeFurnitureFootprint(item)
  const snapped = snapFurnitureEdges(fp, designZone.value, furniture.value.map(officeFurnitureFootprint), FURNITURE_SNAP_TOLERANCE_MM)
  return { item: { ...item, x: item.x + snapped.x - fp.x, y: item.y + snapped.y - fp.y }, guides: { x: snapped.guideX, y: snapped.guideY } }
})
const placementShown = computed(() => placementSnap.value.item)
const activeSnapGuides = computed(() => placing.value ? placementSnap.value.guides : dragSnapGuides.value)
const placementProblem = computed(() => placementPreview.value && designZone.value
  ? officePlacementHardProblem(designZone.value, placementShown.value!, furniture.value, store.furniturePlacement!.spacing) : null)
watch(() => store.furniturePlacement, draft => {
  placementPointer.value = null
  placementMessage.value = ''
  if (draft) { tool.value = 'select'; selectedId.value = null; selectedFurnitureIds.value = []; selectedChannelId.value = null }
})
watch(tool, value => {
  selectedFurnitureIds.value = []
  selectedChannelId.value = null
  if (value !== 'select') selectedConstraintId.value = null
  constraintEditPreview.value = null
  if (!['select', 'pan'].includes(value)) store.cancelFurniturePlacement()
})

// 识别图层：底图 + 多边形
const floorPlan = computed<FloorPlanAnalysisResponse | null>(() => store.floorPlan)
const floorPlanRooms = computed(() => floorPlan.value?.rooms || [])
const floorPlanImageUrl = computed(() => sameOriginAssetUrl(floorPlan.value?.previewUrl || floorPlan.value?.referenceImageUrl || floorPlan.value?.imageUrl || ''))
const canonicalPlan = computed(() => zones.value.length > 0 && zones.value.every(zone => zone.reviewUnit === 'mm'))
const alignedImageStyle = computed(() => {
  if (!canonicalPlan.value) return { left: '230px', top: '170px', width: '1090px', height: '640px' }
  const bounds = floorPlan.value?.previewBounds || floorPlan.value?.drawingBounds
  if (!bounds) return { display: 'none' }
  const scale = zones.value[0]?.mmPerSourceUnit || 1
  const top = zones.value[0]?.sourceYAxis === 'up' ? -bounds.maxY : bounds.minY
  return { left: bounds.minX * scale + 'px', top: top * scale + 'px', width: (bounds.maxX - bounds.minX) * scale + 'px', height: (bounds.maxY - bounds.minY) * scale + 'px' }
})
const floorPlanBounds = computed<FloorPlanDrawingBounds | null>(() => {
  const plan = floorPlan.value
  if (plan?.drawingBounds) return plan.drawingBounds
  if (plan?.previewBounds) return plan.previewBounds
  const rooms = floorPlanRooms.value.map((r) => ({ polygon: normalizePolygon(r.polygon) }))
  const b = roomsBounds(rooms)
  if (!b) return null
  return { minX: b.minX, minY: b.minY, maxX: b.maxX, maxY: b.maxY }
})

const SVG_W = 1090
const SVG_H = 640

function mmToSvg(mmX: number, mmY: number): { x: number; y: number } | null {
  const b = floorPlanBounds.value
  if (!b) return null
  const width = Math.max(1, b.maxX - b.minX)
  const height = Math.max(1, b.maxY - b.minY)
  return {
    x: ((mmX - b.minX) / width) * SVG_W,
    y: ((mmY - b.minY) / height) * SVG_H,
  }
}

function polygonSvgPoints(polygon: Array<[number, number]> | undefined): string {
  if (!polygon || polygon.length === 0) return ''
  const points = polygon.map(([x, y]) => {
    const p = mmToSvg(x, y)
    return p ? `${p.x},${p.y}` : ''
  }).filter(Boolean)
  return points.join(' ')
}

function zonePolygonPoints(zone: ZoneObject): string {
  if (zone.polygon && zone.polygon.length > 0) {
    return polygonSvgPoints(zone.polygon)
  }
  return `${zone.x},${zone.y} ${zone.x + zone.w},${zone.y} ${zone.x + zone.w},${zone.y + zone.h} ${zone.x},${zone.y + zone.h}`
}

function placeBoard() {
  if (!board.value) return
  board.value.style.transform = `translate(calc(-50% + ${store.panX}px), calc(-50% + ${store.panY}px)) rotate(${viewRotation.value}deg) scale(${store.zoom})`
}

function fitBoard() {
  if (mode.value === 'design' && designZone.value) {
    fitZone(designZone.value)
    return
  }
  const v = canvasWindow.value
  if (!v || !v.clientWidth || !v.clientHeight) return
  const b = worldBounds.value
  store.zoom = Math.min(0.84, (v.clientWidth - 30) / b.width, (v.clientHeight - 30) / b.height)
  store.panX = 0
  store.panY = 0
  placeBoard()
}

function fitZone(z: ZoneObject, padding = 80) {
  const v = canvasWindow.value
  if (!v || !v.clientWidth || !v.clientHeight) return
  const camera = fitCanvasZone(z, worldBounds.value, Math.max(80, v.clientWidth - 110), Math.max(80, v.clientHeight - 110), padding, viewRotation.value)
  store.zoom = camera.zoom
  store.panX = camera.panX
  store.panY = camera.panY
  placeBoard()
}

function point(e: PointerEvent) {
  const rect = canvasWindow.value!.getBoundingClientRect()
  const world = canvasPointerWorld({ x: e.clientX - rect.left, y: e.clientY - rect.top }, { width: rect.width, height: rect.height }, worldBounds.value, store, viewRotation.value)
  return { x: Math.round(world.x), y: Math.round(world.y) }
}

function rotateView(degrees: number) {
  if (drag.value || mode.value !== 'design') return
  viewRotation.value = ((viewRotation.value + degrees) % 360 + 360) % 360
  placementPointer.value = null
  nextTick(fitBoard)
}

interface DragState {
  mode: 'pan' | 'move' | 'furniture' | 'marquee' | 'zone' | 'arrow' | 'channel' | 'channel-width' | 'constraints' | 'constraint'
  cx?: number
  cy?: number
  px?: number
  py?: number
  id?: string
  ox?: number
  oy?: number
  ow?: number
  moved?: boolean
  x?: number
  y?: number
  lastX?: number
  lastY?: number
  ids?: string[]
  dx?: number
  dy?: number
  channelId?: string
  orientation?: 'vertical' | 'horizontal'
  edge?: 'min' | 'max'
  constraintHandle?: string
}

const drag = ref<DragState | null>(null)
const selectedFurniture = computed(() => furniture.value.find(item => item.id === selectedId.value) || null)
const selectedNote = computed(() => {
  const object = visibleObjects.value.find(item => item.id === selectedId.value)
  return object?.kind === 'note' ? object : null
})
const selectedFurnitureIds = ref<string[]>([])
const editingNoteId = ref<string | null>(null)
const draftNoteText = ref('')
const noteEditor = ref<HTMLTextAreaElement | null>(null)
let lastNotePointerDown: { id: string; at: number; x: number; y: number } | null = null

function setNoteEditor(element: unknown) {
  noteEditor.value = element instanceof HTMLTextAreaElement ? element : null
}
const selectedChannelId = ref<string | null>(null)
const selectedFurnitureItems = computed(() => furniture.value.filter(item => selectedFurnitureIds.value.includes(item.id)))
const moveProblem = ref('')
const dragSnapGuides = ref<{ x: number | null; y: number | null }>({ x: null, y: null })
watch(selectedId, id => {
  if (id && furniture.value.some(item => item.id === id) && !selectedFurnitureIds.value.includes(id)) selectedFurnitureIds.value = [id]
})
const lastDeletedFurnitureId = ref<string | null>(null)
const deleteFeedback = computed(() => lastDeletedFurnitureId.value && !store.objects.some(item => item.id === lastDeletedFurnitureId.value) ? '已删除模块，可点击工具栏撤销恢复' : '')

function deleteSelection() {
  if (mode.value === 'design' && selectedFurnitureIds.value.length > 1 && store.designZoneId) {
    const ids = [...selectedFurnitureIds.value]
    if (store.deleteFurnitureGroup(ids, store.designZoneId)) {
      lastDeletedFurnitureId.value = ids[0] || null
      selectedFurnitureIds.value = []
      selectedId.value = null
    }
    return
  }
  const object = visibleObjects.value.find(item => item.id === selectedId.value)
  if (!object || (mode.value === 'design' && object.kind === 'zone')) return
  if (object.kind === 'note' && editingNoteId.value === object.id) cancelNoteEdit()
  if (store.deleteObject(object.id)) {
    if (object.kind === 'furniture') lastDeletedFurnitureId.value = object.id
    selectedId.value = null
    selectedFurnitureIds.value = []
  }
}

function startNoteEdit(note: NoteObject) {
  selectedId.value = note.id
  selectedFurnitureIds.value = []
  editingNoteId.value = note.id
  draftNoteText.value = note.text
  nextTick(() => {
    noteEditor.value?.focus()
    noteEditor.value?.select()
  })
}

function saveNoteEdit() {
  const id = editingNoteId.value
  if (!id) return
  const note = store.objects.find(item => item.id === id && item.kind === 'note') as NoteObject | undefined
  const text = draftNoteText.value
  editingNoteId.value = null
  if (note && note.text !== text) store.updateObject(id, { text })
}

function cancelNoteEdit() {
  editingNoteId.value = null
}

function onNotePointerDown(note: NoteObject, event: PointerEvent) {
  if (editingNoteId.value === note.id) return
  const now = Date.now()
  const previous = lastNotePointerDown
  const isDoubleClick = previous?.id === note.id && now - previous.at <= 350
    && Math.hypot(event.clientX - previous.x, event.clientY - previous.y) <= 6
  lastNotePointerDown = isDoubleClick ? null : { id: note.id, at: now, x: event.clientX, y: event.clientY }
  if (isDoubleClick) {
    event.preventDefault()
    startNoteEdit(note)
    return
  }
  onPointerDown(event)
}

function rotateSelection() {
  if (mode.value !== 'design' || !store.designZoneId || !selectedFurnitureIds.value.length) return
  moveProblem.value = store.rotateFurnitureGroup(selectedFurnitureIds.value, store.designZoneId) || ''
}

function mirrorSelection() {
  if (mode.value !== 'design' || !store.designZoneId || !selectedFurnitureIds.value.length) return
  moveProblem.value = store.mirrorFurnitureGroup(selectedFurnitureIds.value, store.designZoneId) || ''
}

function chooseTool(t: string) {
  if (mode.value === 'design' && ['zone', 'arrow'].includes(t)) return
  cancelNoteEdit()
  tool.value = t
  selectedId.value = null
  selectedFurnitureIds.value = []
  selectedChannelId.value = null
  moveProblem.value = ''
}

function onWheel(e: WheelEvent) {
  e.preventDefault()
  store.zoom = Math.max(0.00001, Math.min(2.3, store.zoom * (e.deltaY < 0 ? 1.09 : 1 / 1.09)))
  placeBoard()
}

function onChannelPointerDown(channel: LayoutChannel, e: PointerEvent) {
  if (e.button !== 0 || mode.value !== 'design' || tool.value !== 'select' || placing.value || !canvasWindow.value) return
  const p = point(e)
  // 点选条带即选中通道（与家具/对象选中互斥），拖动提交后选中保持。
  selectedId.value = null
  selectedFurnitureIds.value = []
  selectedChannelId.value = channel.id
  drag.value = { mode: 'channel', channelId: channel.id, orientation: channel.orientation, cx: p.x, cy: p.y, ox: channel.offsetMm, dx: 0 }
  canvasWindow.value.setPointerCapture(e.pointerId)
  e.preventDefault()
}

/** 宽度拖柄：拖 max 边只改宽度；拖 min 边同时改位置与宽度（远端固定）。净距不变，钳制使禁放带不出包围盒。 */
function onChannelEdgePointerDown(channel: LayoutChannel, edge: 'min' | 'max', e: PointerEvent) {
  if (e.button !== 0 || mode.value !== 'design' || tool.value !== 'select' || placing.value || !canvasWindow.value) return
  const p = point(e)
  selectedId.value = null
  selectedFurnitureIds.value = []
  selectedChannelId.value = channel.id
  drag.value = { mode: 'channel-width', channelId: channel.id, orientation: channel.orientation, edge, cx: p.x, cy: p.y, ox: channel.offsetMm, ow: channel.widthMm, dx: 0 }
  canvasWindow.value.setPointerCapture(e.pointerId)
  e.preventDefault()
}

function onConstraintPointerDown(area: LayoutExclusion, handle: string, e: PointerEvent) {
  if (e.button !== 0 || mode.value !== 'design' || tool.value !== 'select' || placing.value || !canvasWindow.value || !isEditableConstraint(area)) return
  const obstacle = designZone.value?.obstacles?.find(item => item.obstacleId === area.id)
  if (!obstacle) return
  const p = point(e)
  selectedId.value = null
  selectedFurnitureIds.value = []
  selectedChannelId.value = null
  selectedConstraintId.value = area.id
  constraintMessage.value = ''
  constraintEditPreview.value = JSON.parse(JSON.stringify(obstacle)) as SpatialObstacle
  drag.value = { mode: 'constraint', id: area.id, constraintHandle: handle, cx: p.x, cy: p.y }
  canvasWindow.value.setPointerCapture(e.pointerId)
  e.preventDefault()
}

function removeSelectedConstraint() {
  if (!designZone.value || !selectedConstraintId.value) return
  const label = obstacleLabel(selectedConstraint.value?.kind || 'NO_PLACEMENT')
  if (store.removeZoneObstacle(designZone.value.id, selectedConstraintId.value)) constraintMessage.value = `已删除${label}，可撤销`
  selectedConstraintId.value = null
  constraintEditPreview.value = null
}

function resizeSelectedConstraint(key: 'width' | 'depth' | 'radius', event: Event) {
  const area = selectedConstraintArea.value, obstacle = selectedConstraint.value
  const value = Number((event.target as HTMLInputElement).value)
  if (!area || !obstacle || !Number.isFinite(value) || value < 1) return
  let next: SpatialObstacle | null = null
  if (obstacle.kind === 'DOOR_CLEARANCE') {
    const hinge = obstacle.geometry.outer[0], first = obstacle.geometry.outer[1], last = obstacle.geometry.outer.at(-1)
    if (!hinge || !first || !last || key !== 'radius') return
    next = rectangleObstacle(obstacle.spaceId!, obstacle.kind, { x: hinge[0], y: hinge[1] }, { x: hinge[0] + Math.sign(first[0] - hinge[0]) * value, y: hinge[1] + Math.sign(last[1] - hinge[1]) * value }, obstacle.obstacleId)
  } else {
    const width = key === 'width' ? value : area.w, depth = key === 'depth' ? value : area.h
    const center = { x: area.x + area.w / 2, y: area.y + area.h / 2 }
    next = rectangleObstacle(obstacle.spaceId!, obstacle.kind, { x: center.x - width / 2, y: -center.y - depth / 2 }, { x: center.x + width / 2, y: -center.y + depth / 2 }, obstacle.obstacleId)
  }
  if (!next) { constraintMessage.value = '标注尺寸无效'; return }
  const snapped = designZone.value && snapEnabled.value ? snapObstacleToZone(designZone.value, next, Math.min(8 / store.zoom, 20)).obstacle : next
  const problem = store.saveZoneObstacle(obstacle.spaceId!, snapped)
  constraintMessage.value = problem || '标注尺寸已更新，可撤销'
}

function rotateSelectedConstraintDoor() {
  const obstacle = selectedConstraint.value, area = selectedConstraintArea.value
  if (!obstacle || obstacle.kind !== 'DOOR_CLEARANCE' || !area) return
  const hinge = obstacle.geometry.outer[0], first = obstacle.geometry.outer[1], last = obstacle.geometry.outer.at(-1)
  if (!hinge || !first || !last) return
  const directions = [[1, 1], [-1, 1], [-1, -1], [1, -1]]
  const signX = Math.sign(first[0] - hinge[0]), signY = Math.sign(last[1] - hinge[1])
  const index = directions.findIndex(([x, y]) => x === signX && y === signY), nextDirection = directions[(index + 1) % directions.length]!
  const radius = Math.max(area.w, area.h)
  const next = rectangleObstacle(obstacle.spaceId!, obstacle.kind, { x: hinge[0], y: hinge[1] }, { x: hinge[0] + nextDirection[0]! * radius, y: hinge[1] + nextDirection[1]! * radius }, obstacle.obstacleId)
  if (!next) { constraintMessage.value = '开启方向无效'; return }
  const problem = store.saveZoneObstacle(obstacle.spaceId!, next)
  constraintMessage.value = problem || '门洞开启方向已更新，可撤销'
}

function clampChannelWidthDelta(d: DragState, rawDx: number): number {
  const zone = designZone.value
  if (!zone) return rawDx
  const b = canvasZoneBounds(zone)
  const channel = (zone.layoutPreferences?.channels || []).find(item => item.id === d.channelId)
  if (!channel) return rawDx
  const clearance = Number.isFinite(channel.clearanceMm) && channel.clearanceMm! > 0 ? channel.clearanceMm! : 0
  const min = d.orientation === 'vertical' ? b.minX : b.minY
  const max = d.orientation === 'vertical' ? b.maxX : b.maxY
  const ox = d.ox || 0, ow = d.ow || 1
  // min 边：offset+dx ≥ min+clearance 且宽度 ow-dx ≥ 1；max 边：宽度 ow+dx ≥ 1 且 offset+width+clearance ≤ max
  return d.edge === 'max'
    ? Math.min(Math.max(rawDx, 1 - ow), max - clearance - ox - ow)
    : Math.min(Math.max(rawDx, min + clearance - ox), ow - 1)
}

function onPointerDown(e: PointerEvent) {
  if (e.button !== 0 || !canvasWindow.value) return
  const target = e.target as HTMLElement
  const hit = target.closest('.board-element') as HTMLElement | null
  const p = point(e)

  if (tool.value === 'pan') {
    drag.value = { mode: 'pan', cx: e.clientX, cy: e.clientY, px: store.panX, py: store.panY }
    canvasWindow.value.setPointerCapture(e.pointerId)
    return
  }

  if (placing.value) {
    e.preventDefault()
    placementPointer.value = p
    const placed = placementSnap.value.item
    const problem = store.placeFurnitureAt(placed ? placed.x + placed.w / 2 : p.x, placed ? placed.y + placed.h / 2 : p.y)
    placementMessage.value = problem || `已添加 ${store.furniturePlacement?.template.name || '家具'}，可继续放置`
    return
  }

  if (isConstraintTool.value && designZone.value) {
    selectedConstraintId.value = null
    constraintEditPreview.value = null
    constraintMessage.value = ''
    drag.value = { mode: 'constraints', x: p.x, y: p.y }
    canvasWindow.value.setPointerCapture(e.pointerId)
    if (drawPreview.value) { drawPreview.value.style.display = 'block'; updatePreview(p, p) }
    e.preventDefault()
    return
  }

  if (tool.value === 'select') {
    selectedChannelId.value = null
    selectedConstraintId.value = null
    constraintEditPreview.value = null
    if (hit) {
      const id = hit.dataset.id!
      const o = store.objects.find((x) => x.id === id)!
      if (o.kind === 'furniture' && mode.value === 'design') {
        if (e.shiftKey || e.ctrlKey || e.metaKey) {
          selectedFurnitureIds.value = selectedFurnitureIds.value.includes(id)
            ? selectedFurnitureIds.value.filter(item => item !== id)
            : [...selectedFurnitureIds.value, id]
          selectedId.value = selectedFurnitureIds.value.length === 1 ? selectedFurnitureIds.value[0]! : null
          return
        }
        if (!selectedFurnitureIds.value.includes(id)) selectedFurnitureIds.value = [id]
        selectedId.value = selectedFurnitureIds.value.length === 1 ? id : null
        drag.value = { mode: 'furniture', cx: p.x, cy: p.y, ids: [...selectedFurnitureIds.value], dx: 0, dy: 0 }
        canvasWindow.value.setPointerCapture(e.pointerId)
        moveProblem.value = ''
        return
      }
      selectedFurnitureIds.value = []
      moveProblem.value = ''
      selectedId.value = id
      // Confirmed space geometry is edited in the review stage, not while placing furniture.
      if (o.kind === 'zone' && canonicalPlan.value) {
        selectedId.value = null
        if (mode.value !== 'design') return
      } else {
        store.snapshotUndo()
        drag.value = { mode: 'move', id, cx: p.x, cy: p.y, ox: o.x, oy: o.y, moved: false }
        canvasWindow.value.setPointerCapture(e.pointerId)
        return
      }
    }
    selectedId.value = null
    moveProblem.value = ''
    if (!e.shiftKey && !e.ctrlKey && !e.metaKey) selectedFurnitureIds.value = []
    if (mode.value === 'design') {
      drag.value = { mode: 'marquee', x: p.x, y: p.y, lastX: p.x, lastY: p.y, moved: false }
      canvasWindow.value.setPointerCapture(e.pointerId)
      if (drawPreview.value) { drawPreview.value.style.display = 'block'; updatePreview(p, p) }
    }
    return
  }

  if (tool.value === 'note') {
    store.addObject({ id: uid('n'), kind: 'note', zoneId: mode.value === 'design' ? store.designZoneId || undefined : undefined, x: p.x, y: p.y, text: '在这里填写办公需求说明…' })
    selectedId.value = store.objects[store.objects.length - 1].id
    tool.value = 'select'
    return
  }

  if (tool.value === 'reference') {
    store.addObject({ id: uid('r'), kind: 'reference', zoneId: mode.value === 'design' ? store.designZoneId || undefined : undefined, x: p.x, y: p.y, caption: '办公风格参考' })
    selectedId.value = store.objects[store.objects.length - 1].id
    tool.value = 'select'
    return
  }

  if (tool.value === 'zone' || tool.value === 'arrow') {
    drag.value = { mode: tool.value, x: p.x, y: p.y, lastX: p.x, lastY: p.y }
    canvasWindow.value.setPointerCapture(e.pointerId)
    if (tool.value === 'zone' && drawPreview.value) {
      drawPreview.value.style.display = 'block'
      updatePreview(p, p)
    }
  }
}

function updatePreview(a: { x: number; y: number }, b: { x: number; y: number }) {
  if (!drawPreview.value) return
  drawPreview.value.style.left = Math.min(a.x, b.x) + 'px'
  drawPreview.value.style.top = Math.min(a.y, b.y) + 'px'
  drawPreview.value.style.width = Math.abs(a.x - b.x) + 'px'
  drawPreview.value.style.height = Math.abs(a.y - b.y) + 'px'
}

function onPointerMove(e: PointerEvent) {
  if (placing.value && canvasWindow.value) { placementPointer.value = point(e); placementMessage.value = '' }
  if (!drag.value || !canvasWindow.value) return
  const d = drag.value
  if (d.mode === 'pan') {
    store.panX = (d.px || 0) + (e.clientX - (d.cx || 0))
    store.panY = (d.py || 0) + (e.clientY - (d.cy || 0))
    placeBoard()
  } else if (d.mode === 'furniture' && d.ids?.length && designZone.value) {
    const p = point(e)
    d.dx = p.x - (d.cx || 0)
    d.dy = p.y - (d.cy || 0)
    const ids = new Set(d.ids)
    if (snapEnabled.value) {
      const anchor = furniture.value.find(item => item.id === d.ids![0])
      if (anchor) {
        const fp = officeFurnitureFootprint(anchor)
        const snapped = snapFurnitureEdges({ x: fp.x + d.dx, y: fp.y + d.dy, w: fp.w, h: fp.h }, designZone.value, furniture.value.filter(item => !ids.has(item.id)).map(officeFurnitureFootprint), FURNITURE_SNAP_TOLERANCE_MM)
        d.dx = snapped.x - fp.x
        d.dy = snapped.y - fp.y
        dragSnapGuides.value = { x: snapped.guideX, y: snapped.guideY }
      }
    } else dragSnapGuides.value = { x: null, y: null }
    const candidates = furniture.value.filter(item => ids.has(item.id)).map(item => ({ ...item, x: item.x + d.dx!, y: item.y + d.dy! }))
    moveProblem.value = officeFurnitureMoveProblem(designZone.value, candidates, furniture.value) || ''
  } else if (d.mode === 'channel' && d.channelId) {
    const p = point(e)
    let dx = d.orientation === 'vertical' ? p.x - (d.cx || 0) : p.y - (d.cy || 0)
    // 预览即钳制：通道（含净距）不允许拖出空间包围盒，松手提交同一值。
    const zone = designZone.value
    const channel = (zone?.layoutPreferences?.channels || []).find(channel => channel.id === d.channelId)
    const range = channel && zone ? channelOffsetRange(zone, channel) : null
    if (range) dx = Math.min(Math.max(dx, range.lo - (d.ox || 0)), range.hi - (d.ox || 0))
    // 吸附（与家具同一 50mm 阈值与开关）：禁放带边缘/中线对齐墙边、家具边缘与其他通道边缘。
    if (zone && channel && snapEnabled.value) {
      const b = canvasZoneBounds(zone)
      const clearance = Number.isFinite(channel.clearanceMm) && channel.clearanceMm! > 0 ? channel.clearanceMm! : 0
      const vertical = d.orientation === 'vertical'
      const band = vertical
        ? { x: (d.ox || 0) + dx - clearance, y: b.minY, w: channel.widthMm + clearance * 2, h: b.maxY - b.minY }
        : { x: b.minX, y: (d.ox || 0) + dx - clearance, w: b.maxX - b.minX, h: channel.widthMm + clearance * 2 }
      const others = [
        ...furniture.value.map(item => officeFurnitureFootprint(item)),
        ...(zone.layoutPreferences?.channels || [])
          .filter(other => other.id !== d.channelId)
          .flatMap(other => channelRects(zone, [other])),
      ]
      const snapped = snapFurnitureEdges(band, zone, others, FURNITURE_SNAP_TOLERANCE_MM)
      let snappedDx = dx + (vertical ? snapped.x - band.x : snapped.y - band.y)
      if (range) snappedDx = Math.min(Math.max(snappedDx, range.lo - (d.ox || 0)), range.hi - (d.ox || 0))
      d.dx = snappedDx
      dragSnapGuides.value = vertical ? { x: snapped.guideX, y: null } : { x: null, y: snapped.guideY }
    } else {
      d.dx = dx
      dragSnapGuides.value = { x: null, y: null }
    }
  } else if (d.mode === 'channel-width' && d.channelId) {
    const p = point(e)
    const rawDx = d.orientation === 'vertical' ? p.x - (d.cx || 0) : p.y - (d.cy || 0)
    d.dx = clampChannelWidthDelta(d, rawDx)
  } else if (d.mode === 'constraints') {
    const p = point(e)
    d.lastX = p.x
    d.lastY = p.y
    if (constraintKind.value === 'DOOR_CLEARANCE' && drawPreview.value) drawPreview.value.style.display = 'none'
    else updatePreview(d, p)
  } else if (d.mode === 'constraint' && d.id && designZone.value) {
    const original = designZone.value.obstacles?.find(obstacle => obstacle.obstacleId === d.id)
    if (!original) return
    const p = point(e)
    constraintEditPreview.value = d.constraintHandle === 'move'
      ? moveObstacle(original, p.x - (d.cx || 0), -(p.y - (d.cy || 0)))
      : reshapeObstacle(original, d.constraintHandle || '', { x: p.x, y: -p.y })
  } else if (d.mode === 'marquee') {
    const p = point(e)
    d.lastX = p.x
    d.lastY = p.y
    d.moved = Math.abs(p.x - (d.x || 0)) + Math.abs(p.y - (d.y || 0)) > 8
    updatePreview(d as { x: number; y: number }, p)
  } else if (d.mode === 'move' && d.id) {
    const p = point(e)
    const o = store.objects.find((x) => x.id === d.id)
    if (!o) return
    const nx = (d.ox || 0) + p.x - (d.cx || 0)
    const ny = (d.oy || 0) + p.y - (d.cy || 0)
    if (Math.abs(nx - o.x) > 1 || Math.abs(ny - o.y) > 1) {
      if (!d.moved) d.moved = true
      const el = document.querySelector(`.board-element[data-id="${d.id}"]`) as HTMLElement
      if (el) {
        el.style.left = nx + 'px'
        el.style.top = ny + 'px'
      }
      if (o.kind === 'arrow') {
        ;(o as any).x2 = (o as any).x2 + nx - o.x
        ;(o as any).y2 = (o as any).y2 + ny - o.y
      }
      o.x = nx
      o.y = ny
    }
  } else if (d.mode === 'zone') {
    updatePreview(d, point(e))
  } else if (d.mode === 'arrow') {
    d.lastX = point(e).x
    d.lastY = point(e).y
  }
}

function onPointerUp(e: PointerEvent) {
  dragSnapGuides.value = { x: null, y: null }
  if (!drag.value || !drawPreview.value) {
    drag.value = null
    return
  }
  const d = drag.value
  drag.value = null

  if (d.mode === 'zone') {
    drawPreview.value.style.display = 'none'
    const p = point(e)
    const x = Math.min(p.x, d.x || 0)
    const y = Math.min(p.y, d.y || 0)
    const w = Math.abs(p.x - (d.x || 0))
    const h = Math.abs(p.y - (d.y || 0))
    if (w > 45 && h > 35) {
      const toneKeys = Object.keys(TONES) as Array<keyof typeof TONES>
      const tone = toneKeys[zones.value.length % toneKeys.length]
      store.addObject({
        id: uid('z'),
        kind: 'zone',
        x,
        y,
        w,
        h,
        name: '新办公区域',
        zoneType: 'OPEN_OFFICE',
        seats: 0,
        note: '',
        tone,
      })
      selectedId.value = store.objects[store.objects.length - 1].id
      tool.value = 'select'
    }
  } else if (d.mode === 'constraints' && designZone.value) {
    drawPreview.value.style.display = 'none'
    const p = point(e)
    const raw = rectangleObstacle(designZone.value.id, constraintKind.value, { x: d.x || 0, y: -(d.y || 0) }, { x: p.x, y: -p.y })
    if (!raw) { constraintMessage.value = constraintKind.value === 'DOOR_CLEARANCE' ? '请从铰链角拖出清晰的开启区域' : '请拖出清晰的矩形区域'; return }
    const obstacle = snapEnabled.value ? snapObstacleToZone(designZone.value, raw, Math.min(8 / store.zoom, 20)).obstacle : raw
    const problem = store.saveZoneObstacle(designZone.value.id, obstacle)
    if (problem) { constraintMessage.value = problem; return }
    selectedConstraintId.value = obstacle.obstacleId
    tool.value = 'select'
    constraintMessage.value = obstacleLabel(obstacle.kind) + '已保存，可继续拖动或调整尺寸'
  } else if (d.mode === 'constraint' && d.id && designZone.value) {
    const next = constraintEditPreview.value
    constraintEditPreview.value = null
    if (!next) return
    const original = designZone.value.obstacles?.find(obstacle => obstacle.obstacleId === d.id)
    if (JSON.stringify(original?.geometry) === JSON.stringify(next.geometry)) return
    const snapped = snapEnabled.value ? snapObstacleToZone(designZone.value, next, Math.min(8 / store.zoom, 20)).obstacle : next
    const problem = store.saveZoneObstacle(designZone.value.id, snapped)
    constraintMessage.value = problem || obstacleLabel(snapped.kind) + '已更新，可撤销'
  } else if (d.mode === 'marquee') {
    drawPreview.value.style.display = 'none'
    if (d.moved) {
      const left = Math.min(d.x!, d.lastX!), right = Math.max(d.x!, d.lastX!)
      const top = Math.min(d.y!, d.lastY!), bottom = Math.max(d.y!, d.lastY!)
      const ids = furniture.value.filter(item => {
        const rect = officeFurnitureFootprint(item)
        return rect.x < right && rect.x + rect.w > left && rect.y < bottom && rect.y + rect.h > top
      }).map(item => item.id)
      selectedFurnitureIds.value = e.shiftKey || e.ctrlKey || e.metaKey ? [...new Set([...selectedFurnitureIds.value, ...ids])] : ids
      selectedId.value = selectedFurnitureIds.value.length === 1 ? selectedFurnitureIds.value[0]! : null
    }
  } else if (d.mode === 'furniture' && d.ids?.length && store.designZoneId) {
    const dx = d.dx || 0, dy = d.dy || 0
    if (dx || dy) moveProblem.value = store.moveFurnitureGroup(d.ids, store.designZoneId, dx, dy) || ''
  } else if (d.mode === 'channel' && d.channelId && store.designZoneId) {
    // 拖动只改垂直轴向位置；松手一次性提交，走 store 的失效与撤销路径。
    if (d.dx) {
      const offsetMm = Math.round((d.ox || 0) + d.dx)
      const channels = (designZone.value?.layoutPreferences?.channels || []).map(channel => channel.id === d.channelId ? { ...channel, offsetMm } : channel)
      store.updateZoneChannels(store.designZoneId, channels)
    }
  } else if (d.mode === 'channel-width' && d.channelId && store.designZoneId) {
    // 宽度拖柄：max 边只改宽度；min 边位置与宽度联动（远端固定）。
    if (d.dx) {
      const dx = Math.round(d.dx)
      const channels = (designZone.value?.layoutPreferences?.channels || []).map(channel => channel.id === d.channelId
        ? (d.edge === 'max'
          ? { ...channel, widthMm: Math.max(1, Math.round((d.ow || 1) + dx)) }
          : { ...channel, offsetMm: Math.round((d.ox || 0) + dx), widthMm: Math.max(1, Math.round((d.ow || 1) - dx)) })
        : channel)
      store.updateZoneChannels(store.designZoneId, channels)
    }
  } else if (d.mode === 'arrow') {
    const p = point(e)
    if (Math.abs(p.x - (d.x || 0)) + Math.abs(p.y - (d.y || 0)) > 30) {
      store.addObject({ id: uid('a'), kind: 'arrow', x: d.x || 0, y: d.y || 0, x2: p.x, y2: p.y })
      tool.value = 'select'
    }
  } else if (d.mode === 'move' && d.id && d.moved) {
    const o = store.objects.find((x) => x.id === d.id)
    if (o) {
      store.moveObject(d.id, o.x, o.y)
    }
  }
}

function onPointerCancel() {
  const d = drag.value
  if (d?.mode === 'move' && d.id) {
    const object = store.objects.find(item => item.id === d.id)
    if (object && d.ox !== undefined && d.oy !== undefined) {
      if (object.kind === 'arrow') {
        object.x2 += d.ox - object.x
        object.y2 += d.oy - object.y
      }
      object.x = d.ox
      object.y = d.oy
    }
  }
  if (drawPreview.value) drawPreview.value.style.display = 'none'
  constraintEditPreview.value = null
  drag.value = null
  moveProblem.value = ''
}

function onKeydown(e: KeyboardEvent) {
  const active = document.activeElement as HTMLElement
  const typing = active && (['INPUT', 'TEXTAREA', 'SELECT'].includes(active.tagName) || active.isContentEditable)
  if (e.key === 'Escape') {
    store.cancelFurniturePlacement()
    selectedId.value = null
    selectedFurnitureIds.value = []
    selectedChannelId.value = null
    selectedConstraintId.value = null
    constraintEditPreview.value = null
    if (isConstraintTool.value) tool.value = 'select'
    moveProblem.value = ''
    showSubmit.value = false
    return
  }
  if (typing || showSubmit.value || document.querySelector('[role="dialog"][aria-modal="true"]')) return
  if (placing.value) {
    if (e.key.toLowerCase() === 'r') { e.preventDefault(); store.rotateFurniturePlacement() }
    return
  }
  if (e.key.toLowerCase() === 'r' && mode.value === 'design' && selectedFurnitureIds.value.length) { e.preventDefault(); rotateSelection(); return }
  if (['Delete', 'Backspace'].includes(e.key) && (selectedId.value || selectedFurnitureIds.value.length || selectedChannelId.value || selectedConstraintId.value)) {
    e.preventDefault()
    if (selectedConstraintId.value) removeSelectedConstraint()
    else if (selectedChannelId.value) removeSelectedChannel()
    else deleteSelection()
    return
  }
  const shortcuts: Record<string, string> = { v: 'select', h: 'pan', r: 'zone', n: 'note', a: 'arrow' }
  const t = shortcuts[e.key.toLowerCase()]
  if (t) chooseTool(t)
}

let resizeObserver: ResizeObserver | undefined
onMounted(() => {
  resizeObserver = new ResizeObserver(() => {
    if (mode.value === 'design') fitBoard()
  })
  if (canvasWindow.value) resizeObserver.observe(canvasWindow.value)
  window.addEventListener('keydown', onKeydown)
  nextTick(() => {
    fitBoard()
  })
})
onUnmounted(() => {
  store.cancelFurniturePlacement()
  resizeObserver?.disconnect()
  window.removeEventListener('keydown', onKeydown)
})

watch([() => store.zoom, () => store.panX, () => store.panY, viewRotation], placeBoard)

watch(() => store.objects.length, () => {
  nextTick(placeBoard)
})

watch([mode, designZone], () => {
  if (store.furniturePlacement && store.furniturePlacement.zoneId !== store.designZoneId) store.cancelFurniturePlacement()
  selectedId.value = null
  selectedFurnitureIds.value = []
  selectedConstraintId.value = null
  constraintEditPreview.value = null
  moveProblem.value = ''
  nextTick(() => fitBoard())
}, { immediate: true })

defineExpose({ fitBoard, rotateView })

function objectStyle(o: OfficeObject) {
  const base: Record<string, string> = { position: 'absolute', left: o.x + 'px', top: o.y + 'px' }
  return base
}

function noteStyle(o: NoteObject) {
  const zoom = Math.max(store.zoom, 0.00001)
  return {
    ...objectStyle(o),
    width: `${NOTE_SCREEN_WIDTH / zoom}px`,
    minHeight: `${NOTE_SCREEN_MIN_HEIGHT / zoom}px`,
    padding: `${14 / zoom}px`,
    fontSize: `${NOTE_SCREEN_FONT_SIZE / zoom}px`,
    lineHeight: '1.55',
    borderRadius: `${3 / zoom}px`,
    boxShadow: `0 ${4 / zoom}px ${12 / zoom}px rgba(74, 55, 25, 0.18)`,
    outline: selectedId.value === o.id ? `${3 / zoom}px solid #5092db` : undefined,
    outlineOffset: `${2 / zoom}px`,
  }
}

function zoneStyle(o: ZoneObject) {
  const t = TONES[o.tone] || TONES.blue
  return {
    position: 'absolute',
    left: o.x + 'px',
    top: o.y + 'px',
    width: o.w + 'px',
    height: o.h + 'px',
    borderRadius: '8px',
    border: `2px solid ${t.stroke}`,
    backgroundColor: t.fill,
  }
}

function furnitureStyle(o: FurnitureObject) {
  return {
    position: 'absolute',
    left: o.x + 'px',
    top: o.y + 'px',
    width: o.w + 'px',
    height: o.h + 'px',
    transform: o.rotation || o.mirrorX ? `${o.rotation ? `rotate(${o.rotation}deg) ` : ''}${o.mirrorX ? 'scaleX(-1)' : ''}` : undefined,
  }
}

function furnitureProduct(o: FurnitureObject) {
  return store.products.find((p) => p.id === o.productId)
}
</script>

<template>
  <div
    ref="canvasWindow"
    data-testid="office-design-canvas"
    class="relative flex-1 overflow-hidden bg-[#faf9f7] cursor-crosshair touch-none"
    style="background-image: radial-gradient(#dfdad4 1px, transparent 1px); background-size: 18px 18px;"
    @wheel.prevent="onWheel"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerCancel"
    @pointerleave="placementPointer = null"
  >
    <div v-if="mode === 'design' && floorPlanImageUrl" class="canvas-layers" data-testid="design-underlay-controls" @pointerdown.stop>
      <h4>图层与对照</h4>
      <label class="layer-toggle"><span>原始图纸</span><input v-model="showUnderlay" data-testid="design-underlay-toggle" type="checkbox" role="switch" aria-label="显示原始图纸"></label>
      <label class="opacity-label"><span>底图透明度</span><span>{{ Math.round(underlayOpacity * 100) }}%</span></label>
      <input :value="underlayOpacity * 100" data-testid="design-underlay-opacity" type="range" min="0" max="100" step="5" aria-label="底图透明度" class="opacity-slider" @input="underlayOpacity = Number(($event.target as HTMLInputElement).value) / 100">
      <label class="layer-toggle"><span>设计空间轮廓</span><input v-model="showOutline" type="checkbox" role="switch" aria-label="设计空间轮廓"></label>
      <label class="layer-toggle"><span>实际尺寸标注</span><input v-model="showDimensions" type="checkbox" role="switch" aria-label="实际尺寸标注"></label>
      <label class="layer-toggle"><span>原始识别轮廓</span><input v-model="showOriginal" type="checkbox" role="switch" aria-label="原始识别轮廓"></label>
    </div>
    <div v-if="selectedConstraintArea" class="absolute bottom-12 left-3 z-20 w-56 border border-line bg-white/95 p-2.5 text-[11px] text-[#5b5751]" data-testid="design-constraint-editor" @pointerdown.stop>
      <div class="mb-2 flex items-center justify-between"><strong>{{ obstacleLabel(selectedConstraint?.kind || 'NO_PLACEMENT') }}</strong><button class="text-[#a65e4c]" :aria-label="'删除' + obstacleLabel(selectedConstraint?.kind || 'NO_PLACEMENT')" @click="removeSelectedConstraint">删除</button></div>
      <label v-if="selectedConstraint?.kind === 'DOOR_CLEARANCE'" class="block text-[10px] text-muted">半径 mm<input :value="Math.round(Math.max(selectedConstraintArea.w, selectedConstraintArea.h))" aria-label="门洞半径（毫米）" type="number" min="1" step="50" class="mt-1 w-full border border-line bg-white px-1.5 py-1" @change="resizeSelectedConstraint('radius', $event)"></label>
      <div v-else class="grid grid-cols-2 gap-2">
        <label class="text-[10px] text-muted">宽度 mm<input :value="Math.round(selectedConstraintArea.w)" aria-label="标注宽度（毫米）" type="number" min="1" step="50" class="mt-1 w-full border border-line bg-white px-1.5 py-1" @change="resizeSelectedConstraint('width', $event)"></label>
        <label class="text-[10px] text-muted">深度 mm<input :value="Math.round(selectedConstraintArea.h)" aria-label="标注深度（毫米）" type="number" min="1" step="50" class="mt-1 w-full border border-line bg-white px-1.5 py-1" @change="resizeSelectedConstraint('depth', $event)"></label>
      </div>
      <div class="mt-1.5 flex items-center justify-between text-[10px] text-muted">
        <button v-if="selectedConstraint?.kind === 'DOOR_CLEARANCE'" class="text-[#596252]" @click="rotateSelectedConstraintDoor">换向</button>
        <label v-if="['COLUMN', 'SHAFT', 'WINDOW'].includes(selectedConstraint?.kind || '')"><input v-model="snapEnabled" type="checkbox" aria-label="建筑标注贴墙吸附"> 贴墙吸附</label>
      </div>
      <p class="mt-1.5 text-[9px] text-muted">拖动移动，拖角点或弧线手柄调整，Delete 删除。</p>
    </div>
    <div v-if="mode === 'design' && showDimensions !== false" class="absolute right-3 bottom-12 z-10 rounded-lg border border-[#e8dfd5] bg-white/90 px-2 py-1 text-[10px] text-[#786b5d] pointer-events-none">绿色：离墙 · 棕色：组间净距<br>按家具外包占地测量，椅子为座席示意</div>
    <!-- 选中通道浮动编辑卡：屏幕坐标系，不随画布缩放；编辑走 updateZoneChannels 同一入口 -->
    <div
      v-if="selectedChannel && designZoneBounds"
      data-testid="channel-editor"
      class="absolute z-20 w-64 rounded-xl border border-line bg-white/95 shadow-panel px-3 py-2.5 text-[11px] text-[#5b5751]"
      :style="channelEditorStyle"
      @pointerdown.stop
    >
      <div class="flex items-center justify-between font-semibold">
        <span>{{ selectedChannel.orientation === 'vertical' ? '纵向通道' : '横向通道' }}</span>
        <button class="font-normal text-bronze underline" aria-label="移除选中通道" @click="removeSelectedChannel">移除</button>
      </div>
      <div class="grid grid-cols-2 gap-1.5 mt-2">
        <label>宽度 mm<input :value="selectedChannel.widthMm" type="number" min="1" step="100" aria-label="通道属性宽度（毫米）" class="w-full border border-line px-1" @change="updateSelectedChannel({ widthMm: Math.max(1, Number(($event.target as HTMLInputElement).value) || selectedChannel!.widthMm) })"></label>
        <label>两侧净距 mm<input :value="selectedChannel.clearanceMm || 0" type="number" min="0" step="50" aria-label="通道属性净距（毫米）" class="w-full border border-line px-1" @change="updateSelectedChannel({ clearanceMm: Math.max(0, Number(($event.target as HTMLInputElement).value) || 0) })"></label>
        <label class="col-span-2">位置 mm（{{ selectedChannel.orientation === 'vertical' ? '距左' : '距上' }}）<input :value="Math.round(selectedChannel.offsetMm - (selectedChannel.orientation === 'vertical' ? designZoneBounds.minX : designZoneBounds.minY))" type="number" step="100" aria-label="通道属性位置（毫米）" class="w-full border border-line px-1" @change="updateSelectedChannel({ offsetMm: Number(($event.target as HTMLInputElement).value) + (selectedChannel!.orientation === 'vertical' ? designZoneBounds!.minX : designZoneBounds!.minY) })"></label>
        <label>起点 mm（{{ selectedChannel.orientation === 'vertical' ? '距上' : '距左' }}，空=贯通）<input :value="selectedChannel.startMm === undefined ? '' : Math.round(selectedChannel.startMm - (selectedChannel.orientation === 'vertical' ? designZoneBounds.minY : designZoneBounds.minX))" type="number" step="100" aria-label="通道属性起点（毫米）" class="w-full border border-line px-1" @change="updateSelectedChannel({ startMm: ($event.target as HTMLInputElement).value === '' ? undefined : Number(($event.target as HTMLInputElement).value) + (selectedChannel!.orientation === 'vertical' ? designZoneBounds!.minY : designZoneBounds!.minX) })"></label>
        <label>终点 mm（空=贯通）<input :value="selectedChannel.endMm === undefined ? '' : Math.round(selectedChannel.endMm - (selectedChannel.orientation === 'vertical' ? designZoneBounds.minY : designZoneBounds.minX))" type="number" step="100" aria-label="通道属性终点（毫米）" class="w-full border border-line px-1" @change="updateSelectedChannel({ endMm: ($event.target as HTMLInputElement).value === '' ? undefined : Number(($event.target as HTMLInputElement).value) + (selectedChannel!.orientation === 'vertical' ? designZoneBounds!.minY : designZoneBounds!.minX) })"></label>
      </div>
      <label v-if="selectedChannel.orientation === 'vertical'" class="mt-1.5 block"><input :checked="!!selectedChannel.fromSecondRow" type="checkbox" aria-label="通道属性首排不断开" @change="updateSelectedChannel({ fromSecondRow: ($event.target as HTMLInputElement).checked })"> 仅从二排起（首排桌列不断开）</label>
      <p v-if="selectedChannel.widthMm < CHANNEL_WIDTH_HINT_MM" data-testid="channel-editor-width-hint" class="mt-1 text-[10px] text-amber-600">宽度低于 {{ CHANNEL_WIDTH_HINT_MM }}mm 通行提示阈值（策略提示，非规范强制）</p>
      <p class="mt-1 text-[10px] text-muted">Delete 移除 · Esc 取消选中 · 排版面板同步编辑</p>
    </div>
    <div
      ref="board"
      data-testid="office-scene-board"
      :data-view-rotation="viewRotation"
      class="absolute left-1/2 top-1/2"
      :style="{
        width: `${worldBounds.width}px`,
        height: `${worldBounds.height}px`,
        transformOrigin: 'center center',
        willChange: 'transform',
      }"
    >
      <div class="absolute inset-0" :style="{ transform: `translate(${-worldBounds.minX}px, ${-worldBounds.minY}px)` }">
      <!-- Floor plan SVG -->
      <div
        v-if="mode !== 'design' || (showUnderlay && floorPlanImageUrl)"
        class="absolute pointer-events-none overflow-hidden"
        :style="{ ...alignedImageStyle, opacity: mode === 'design' ? underlayOpacity : store.blank ? 0.05 : 0.75 }"
        :data-testid="mode === 'design' ? 'design-underlay' : undefined"
      >
        <img
          v-if="floorPlanImageUrl"
          :src="floorPlanImageUrl"
          class="absolute inset-0 w-full h-full object-fill"
          alt="户型底图"
        >
        <svg
          v-else
          width="1090"
          height="640"
          viewBox="0 0 1090 640"
        >
          <rect width="1090" height="640" fill="#fffdf9" />
          <g fill="none" stroke="#47505a" stroke-width="7">
            <path d="M24 19 H1066 V621 H24 Z" />
            <path d="M735 19 V290 M24 402 H735 M504 402 V621 M735 290 H1066 M895 290 V621" />
          </g>
          <g fill="none" stroke="#767b7a" stroke-width="2">
            <path d="M42 20 V42 M150 20 V42 M320 20 V42 M495 20 V42 M690 20 V42 M850 20 V42 M1020 20 V42" />
            <path d="M735 75 h18 M735 150 h18 M735 225 h18 M895 350 h-18 M895 450 h-18" />
            <path d="M270 402 V380 M430 402 V380 M650 402 V380" />
          </g>
          <g stroke="#a4aeb0" stroke-width="3">
            <path d="M50 16 H185 M310 16 H460 M750 16 H865 M925 16 H1045" stroke="#78b7d4" />
            <path d="M24 150 V292 M1066 410 V540" stroke="#78b7d4" />
          </g>
          <text x="63" y="599" fill="#8f8b82" font-size="16">模拟办公图纸 · 非真实识别</text>
        </svg>

        <!-- 识别多边形叠加层 -->
        <svg
          v-if="!canonicalPlan && (floorPlanRooms.length > 0 || zones.some(z => z.polygon))"
          class="absolute inset-0 w-full h-full pointer-events-none"
          :viewBox="`0 0 ${SVG_W} ${SVG_H}`"
        >
          <g v-for="room in floorPlanRooms" :key="`fp-${room.roomId}`">
            <polygon
              :points="polygonSvgPoints(normalizePolygon(room.polygon))"
              fill="rgba(76, 118, 182, 0.12)"
              stroke="#4c76b6"
              stroke-width="2"
            />
            <text
              v-if="room.labelPoint"
              :x="mmToSvg(room.labelPoint.x, room.labelPoint.y)?.x"
              :y="mmToSvg(room.labelPoint.x, room.labelPoint.y)?.y"
              fill="#33434b"
              font-size="14"
              font-weight="bold"
            >{{ room.label || '未命名空间' }}</text>
          </g>
          <g v-for="zone in zones.filter(z => z.polygon)" :key="`zone-poly-${zone.id}`">
            <polygon
              :points="zonePolygonPoints(zone)"
              fill="rgba(76, 118, 182, 0.08)"
              stroke="#306fcb"
              stroke-width="2"
              stroke-dasharray="6 4"
            />
          </g>
        </svg>
      </div>

      <svg v-if="canonicalPlan && showOutline" :width="worldBounds.width" :height="worldBounds.height" class="absolute inset-0 pointer-events-none overflow-visible">
        <g v-for="zone in visibleZones" :key="zone.id">
          <polygon :points="zone.polygon?.map(point => point.join(',')).join(' ')" :data-id="zone.id"
            class="board-element pointer-events-auto cursor-pointer" fill="#79ac94" fill-opacity="0.07" stroke="#749c86" :stroke-width="1.5 / store.zoom" @pointerdown.stop="onPointerDown" />
        </g>
      </svg>
      <svg v-if="canonicalPlan && showOriginal" :width="worldBounds.width" :height="worldBounds.height" class="absolute inset-0 pointer-events-none overflow-visible" data-testid="original-recognition">
        <polygon v-for="zone in visibleZones.filter(zone => zone.sourcePolygon?.length)" :key="zone.id" :points="zone.sourcePolygon!.map(point => point.join(',')).join(' ')" fill="none" stroke="#8d8a80" :stroke-width="1.5 / store.zoom" stroke-dasharray="3 4" />
      </svg>

      <!-- Arrows -->
      <svg :width="worldBounds.width" :height="worldBounds.height" :viewBox="`0 0 ${worldBounds.width} ${worldBounds.height}`" class="absolute inset-0 pointer-events-none">
        <defs>
          <marker id="arrowHead" markerWidth="12" markerHeight="12" refX="8" refY="4" orient="auto">
            <path d="M0 0 L8 4 L0 8" stroke="#b36a43" fill="none" stroke-width="2" />
          </marker>
        </defs>
        <line
          v-for="a in arrows"
          :key="a.id"
          :x1="a.x"
          :y1="a.y"
          :x2="(a as any).x2"
          :y2="(a as any).y2"
          stroke="#b36a43"
          stroke-width="4"
          stroke-linecap="round"
          marker-end="url(#arrowHead)"
        />
      </svg>

      <!-- Objects -->
      <div class="absolute inset-0 pointer-events-none">
        <template v-for="o in visibleObjects" :key="o.id">
          <!-- Zone -->
          <div
            v-if="o.kind === 'zone' && !canonicalPlan"
            :data-id="o.id"
            class="board-element pointer-events-auto flex items-center justify-center text-center p-1.5 cursor-move select-none overflow-hidden transition-opacity"
            :class="[
              selectedId === o.id ? 'border-[3px] border-dashed border-[#306fcb] shadow-[0_0_0_4px_rgba(76,118,182,0.13)]' : 'hover:saturate-110 border-2 border-transparent',
            ]"
            :style="zoneStyle(o as any)"
            @pointerdown.stop="onPointerDown"
          >
            <div>
              <div class="text-xl font-bold text-[#33434b] whitespace-nowrap">{{ (o as any).name }}</div>
              <div class="mt-1 text-[15px] text-[#56636e]">{{ (o as any).seats ? '约 ' + (o as any).seats + ' 个位置' : '功能区域' }}</div>
            </div>
          </div>

          <!-- Note -->
          <div
            v-else-if="o.kind === 'note'"
            :data-id="o.id"
            data-testid="canvas-note"
            class="board-element pointer-events-auto bg-[#fff0a5] text-[#634c2b] whitespace-pre-wrap overflow-wrap-anywhere transition-opacity"
            :class="editingNoteId === o.id ? 'cursor-text' : 'cursor-move'"
            style="transform: rotate(-3deg);"
            :style="noteStyle(o)"
            @pointerdown.stop="onNotePointerDown(o, $event)"
            @dblclick.stop
          >
            <textarea
              v-if="editingNoteId === o.id"
              :ref="setNoteEditor"
              v-model="draftNoteText"
              data-testid="note-editor"
              aria-label="标注文字"
              class="block w-full resize-none border-0 bg-transparent p-0 outline-none"
              style="font: inherit;"
              :style="{ height: `${70 / Math.max(store.zoom, 0.00001)}px` }"
              @pointerdown.stop
              @dblclick.stop
              @blur="saveNoteEdit"
              @keydown.esc.prevent.stop="cancelNoteEdit"
              @keydown.ctrl.enter.prevent.stop="saveNoteEdit"
              @keydown.meta.enter.prevent.stop="saveNoteEdit"
            />
            <template v-else>{{ o.text || '空标注' }}</template>
          </div>

          <!-- Reference -->
          <div
            v-else-if="o.kind === 'reference'"
            :data-id="o.id"
            class="board-element pointer-events-auto w-[188px] h-[168px] shadow-xl bg-white border-[8px] border-white cursor-move transition-opacity"
            style="transform: rotate(3deg);"
            :class="[
              selectedId === o.id ? 'outline outline-4 outline-[#5092db]' : '',
            ]"
            :style="objectStyle(o)"
            @pointerdown.stop="onPointerDown"
          >
            <div class="h-[119px] overflow-hidden bg-gradient-to-br from-[#ddd0be] to-[#faf5ed] relative">
              <img v-if="(o as any).image" :src="sameOriginAssetUrl((o as any).image)" class="w-full h-full object-cover">
              <div v-else class="absolute inset-0 grid place-items-center text-[#927f6d] text-sm">▤<br>办公风格参考</div>
            </div>
            <div class="text-[13px] text-center text-[#6b6056] pt-1 truncate">{{ (o as any).caption || '办公参考图' }}</div>
          </div>
        </template>

        <div v-if="constraintCreateArea" class="absolute pointer-events-none" data-testid="door-clearance-draft" :style="{ left: constraintCreateArea.x + 'px', top: constraintCreateArea.y + 'px', width: constraintCreateArea.w + 'px', height: constraintCreateArea.h + 'px' }">
          <svg :viewBox="`0 0 ${constraintCreateArea.w} ${constraintCreateArea.h}`" width="100%" height="100%" style="overflow: visible">
            <polygon :points="obstaclePoints(constraintCreateArea)" fill="#2586a022" stroke="#2586a0" :stroke-width="2 / store.zoom" stroke-dasharray="6 3" />
          </svg>
        </div>
        <div v-if="mode === 'design' && displayedDesignObstacles.length" class="absolute inset-0 pointer-events-none" data-testid="design-obstacles">
          <div
            v-for="area in displayedDesignObstacles"
            :key="area.id"
            :data-obstacle-id="area.id"
            :data-testid="isEditableConstraint(area) ? 'design-constraint' : undefined"
            :class="isEditableConstraint(area) ? 'pointer-events-auto cursor-move' : ''"
            :style="{ position: 'absolute', left: area.x + 'px', top: area.y + 'px', width: area.w + 'px', height: area.h + 'px' }"
            @pointerdown.stop="isEditableConstraint(area) && onConstraintPointerDown(area, 'move', $event)"
          >
            <svg :viewBox="`0 0 ${area.w} ${area.h}`" width="100%" height="100%" style="overflow: visible">
              <polygon :points="obstaclePoints(area)" :fill="area.label === '门洞净空' ? '#2586a022' : '#b44f4522'" :stroke="area.label === '门洞净空' ? '#2586a0' : '#b44f45'" :stroke-width="(selectedConstraintId === area.id ? 3 : 2) / store.zoom" stroke-dasharray="6 3" />
              <text x="4" y="14" :fill="area.label === '门洞净空' ? '#2586a0' : '#b44f45'" :font-size="11 / store.zoom" font-weight="600">{{ area.label }}</text>
              <template v-if="selectedConstraintId === area.id && isEditableConstraint(area)">
                <rect
                  v-for="handle in constraintHandles(area)"
                  :key="handle.handle"
                  :data-testid="'constraint-handle-' + handle.handle"
                  :x="handle.point[0] - area.x - 5 / store.zoom"
                  :y="handle.point[1] - area.y - 5 / store.zoom"
                  :width="10 / store.zoom"
                  :height="10 / store.zoom"
                  fill="#fff"
                  stroke="#b44f45"
                  :stroke-width="2 / store.zoom"
                  class="pointer-events-auto cursor-nwse-resize"
                  @pointerdown.stop="onConstraintPointerDown(area, handle.handle, $event)"
                />
              </template>
            </svg>
          </div>
        </div>

        <div v-if="mode === 'design' && designChannels.length" class="absolute inset-0 pointer-events-none" data-testid="layout-channels">
          <div
            v-for="entry in designChannels"
            :key="entry.key"
            :data-channel-id="entry.channel.id"
            class="pointer-events-auto absolute select-none"
            :class="entry.channel.orientation === 'vertical' ? 'cursor-ew-resize' : 'cursor-ns-resize'"
            :title="`通道 ${entry.channel.widthMm} mm${entry.channel.clearanceMm ? ` + 两侧净距 ${entry.channel.clearanceMm} mm` : ''}${entry.channel.fromSecondRow && entry.channel.orientation === 'vertical' ? ' · 仅从二排起' : ''} · 拖动调整位置，选中后拖两端手柄改宽度`"
            :style="channelStyle(entry)"
            @pointerdown.stop="onChannelPointerDown(entry.channel, $event)"
          >
            <span v-if="entry.primary" class="absolute left-1/2 -translate-x-1/2 whitespace-nowrap font-semibold pointer-events-none" :style="{ top: 4 / store.zoom + 'px', fontSize: 11 / store.zoom + 'px', color: '#306fcb' }">通道 {{ entry.channel.widthMm }}{{ entry.channel.clearanceMm ? `+2×${entry.channel.clearanceMm}` : '' }}{{ entry.channel.fromSecondRow && entry.channel.orientation === 'vertical' ? ' · 二排起' : '' }}</span>
            <!-- 选中后显示宽度拖柄（恒定屏幕尺寸，挂在最大分段上） -->
            <template v-if="entry.primary && selectedChannelId === entry.channel.id">
              <div
                v-for="edge in (['min', 'max'] as const)"
                :key="edge"
                :data-testid="`channel-width-handle-${edge}`"
                :class="entry.channel.orientation === 'vertical' ? 'cursor-ew-resize' : 'cursor-ns-resize'"
                class="absolute"
                :style="entry.channel.orientation === 'vertical'
                  ? { [edge === 'min' ? 'left' : 'right']: '0', top: '0', bottom: '0', width: 10 / store.zoom + 'px', transform: `translateX(${edge === 'min' ? -50 : 50}%)` }
                  : { [edge === 'min' ? 'top' : 'bottom']: '0', left: '0', right: '0', height: 10 / store.zoom + 'px', transform: `translateY(${edge === 'min' ? -50 : 50}%)` }"
                @pointerdown.stop="onChannelEdgePointerDown(entry.channel, edge, $event)"
              >
                <div
                  class="absolute rounded-full bg-[#306fcb]"
                  :style="entry.channel.orientation === 'vertical'
                    ? { left: '50%', top: '50%', width: 4 / store.zoom + 'px', height: 36 / store.zoom + 'px', transform: 'translate(-50%, -50%)' }
                    : { top: '50%', left: '50%', height: 4 / store.zoom + 'px', width: 36 / store.zoom + 'px', transform: 'translate(-50%, -50%)' }"
                ></div>
              </div>
            </template>
          </div>
        </div>

        <!-- Furniture -->
        <div
          v-for="f in displayedFurniture"
          :key="f.id"
          :data-id="f.id"
          class="board-element pointer-events-auto absolute cursor-move select-none"
          :title="`${f.label || furnitureProduct(f)?.name || '家具'} · ${Math.round(f.w)} × ${Math.round(f.h)} mm`"
          :class="[
            selectedFurnitureIds.includes(f.id) ? 'ring-[3px] ring-[#306fcb]/30 z-10' : '',
            mode === 'overview' ? 'opacity-50' : 'opacity-100',
          ]"
          :style="{ ...furnitureStyle(f), left: `${f.x + (drag?.mode === 'furniture' && drag.ids?.includes(f.id) ? drag.dx || 0 : 0)}px`, top: `${f.y + (drag?.mode === 'furniture' && drag.ids?.includes(f.id) ? drag.dy || 0 : 0)}px`, outline: drag?.mode === 'furniture' && drag.ids?.includes(f.id) && moveProblem ? `${2 / store.zoom}px dashed #c45c4c` : undefined }"
          @pointerdown.stop="onPointerDown"
        >
          <OfficeCanvasFurniturePreview v-if="officeFurnitureTemplateId(f, furnitureProduct(f))"
            :template-id="officeFurnitureTemplateId(f, furnitureProduct(f))!" :width-mm="f.w" :depth-mm="f.h"
            :wing-width-mm="f.wingWidthMm" :show-seat="officeFurnitureShowsSeat(f)"
            :selected="selectedFurnitureIds.includes(f.id)" :stroke-scale="1 / store.zoom" />
          <div v-else class="h-full w-full border border-[#78736d] bg-white/90 flex items-center justify-center text-[10px]">{{ f.label || furnitureProduct(f)?.name || '家具' }}</div>
        </div>
      </div>

      <div v-if="mode === 'design' && activePreview" class="absolute inset-0 pointer-events-none" data-testid="layout-plan-preview">
        <template v-for="item in activePreview.furniture" :key="'activity-' + item.id">
          <div v-if="chairActivityRect(item, activePreview.seatClearanceMm)" :style="{ position: 'absolute', left: chairActivityRect(item, activePreview.seatClearanceMm)?.x + 'px', top: chairActivityRect(item, activePreview.seatClearanceMm)?.y + 'px', width: chairActivityRect(item, activePreview.seatClearanceMm)?.w + 'px', height: chairActivityRect(item, activePreview.seatClearanceMm)?.h + 'px', border: `${1 / store.zoom}px dotted #306fcb`, opacity: 0.35 }" />
        </template>
        <div v-for="item in activePreview.furniture" :key="item.id" :data-preview-id="item.id" class="absolute opacity-50" :style="{ ...furnitureStyle(item), outline: `${1.5 / store.zoom}px dashed #306fcb` }">
          <OfficeCanvasFurniturePreview :template-id="officeFurnitureTemplateId(item)!" :width-mm="item.w" :depth-mm="item.h" :wing-width-mm="item.wingWidthMm" :stroke-scale="1 / store.zoom" :show-seat="officeFurnitureShowsSeat(item)" />
        </div>
      </div>

      <OfficeCanvasLayoutDimensions v-if="mode === 'design' && designZone && showDimensions !== false" :zone="designZone" :items="dimensionFurniture" :selected-ids="selectedFurnitureIds" :zoom="store.zoom" :view-rotation="viewRotation" :world="worldBounds" />

      <div v-if="placementShown" data-testid="furniture-placement-preview" class="absolute pointer-events-none opacity-60"
        :data-valid="!placementProblem" :style="{ ...furnitureStyle(placementShown), outline: `${2 / store.zoom}px dashed ${placementProblem ? '#c45c4c' : '#306fcb'}` }">
        <OfficeCanvasFurniturePreview :template-id="officeFurnitureTemplateId(placementShown)!" :width-mm="placementShown.w" :depth-mm="placementShown.h" :stroke-scale="1 / store.zoom" />
      <template v-if="mode === 'design'">
        <div v-if="activeSnapGuides.x !== null" data-testid="snap-guide-x" class="absolute pointer-events-none" :style="{ left: activeSnapGuides.x + 'px', top: worldBounds.minY + 'px', height: (worldBounds.maxY - worldBounds.minY) + 'px', width: '0px', borderLeft: `${1.5 / store.zoom}px dashed #306fcb`, opacity: 0.7 }" />
        <div v-if="activeSnapGuides.y !== null" data-testid="snap-guide-y" class="absolute pointer-events-none" :style="{ top: activeSnapGuides.y + 'px', left: worldBounds.minX + 'px', width: (worldBounds.maxX - worldBounds.minX) + 'px', height: '0px', borderTop: `${1.5 / store.zoom}px dashed #306fcb`, opacity: 0.7 }" />
      </template>
      </div>
      <!-- Draw preview -->
      <div
        ref="drawPreview"
        class="absolute border-2 border-dashed border-[#4185c2] bg-[#6ca6da32] pointer-events-none hidden"
      />
      </div>
    </div>

    <div v-if="selectedFurnitureItems.length && !placing" class="absolute right-4 top-4 z-10 max-w-[60%] flex items-center gap-2 rounded-lg border border-line bg-white/95 px-3 py-2 shadow-sm" @pointerdown.stop>
      <span class="text-xs truncate">{{ selectedFurnitureItems.length === 1 ? (selectedFurniture?.label || '已选家具模块') : `已选 ${selectedFurnitureItems.length} 件模块` }}</span>
      <button class="flex-none rounded-lg px-2 py-1 text-xs text-[#5b5751] hover:bg-[#f3efe9]" title="旋转 90°（R）" data-testid="rotate-selection" @click.stop="rotateSelection">旋转 90°</button>
      <button class="flex-none rounded-lg px-2 py-1 text-xs text-[#5b5751] hover:bg-[#f3efe9]" title="水平镜像" data-testid="mirror-selection" @click.stop="mirrorSelection">水平镜像</button>
      <button class="flex-none rounded-lg px-2 py-1 text-xs text-red-600 hover:bg-red-50" title="Delete / Backspace" @click.stop="deleteSelection">删除选中模块</button>
    </div>
    <div v-if="selectedNote && !placing" data-testid="note-actions" class="absolute right-4 top-4 z-10 flex items-center gap-2 rounded-lg border border-line bg-white/95 px-3 py-2 shadow-sm" @pointerdown.prevent.stop>
      <span class="text-xs text-[#5b5751]">标注</span>
      <template v-if="editingNoteId === selectedNote.id">
        <button class="rounded-lg px-2 py-1 text-xs text-[#5b5751] hover:bg-[#f3efe9]" aria-label="完成标注编辑" @click.stop="saveNoteEdit">完成</button>
        <button class="rounded-lg px-2 py-1 text-xs text-[#5b5751] hover:bg-[#f3efe9]" aria-label="取消标注编辑" @click.stop="cancelNoteEdit">取消</button>
      </template>
      <template v-else>
        <button class="rounded-lg px-2 py-1 text-xs text-[#5b5751] hover:bg-[#f3efe9]" aria-label="编辑标注" data-testid="edit-note" @click.stop="startNoteEdit(selectedNote)">编辑</button>
        <button class="rounded-lg px-2 py-1 text-xs text-red-600 hover:bg-red-50" aria-label="删除标注" data-testid="delete-note" @click.stop="deleteSelection">删除</button>
      </template>
    </div>
    <div v-if="mode === 'design' && designZone" class="absolute left-4 top-4 rounded-lg border border-line bg-white/90 px-3 py-2 text-xs text-[#56636e] pointer-events-none">{{ designZone.name }}</div>
    <div :role="placing || deleteFeedback ? 'status' : undefined" class="absolute left-4 bottom-3.5 bg-white/90 border border-line rounded-lg px-3 py-2 text-[11px] text-[#79736d] z-10">
      {{ hintText }}
    </div>
    <div class="absolute right-3.5 bottom-3.5 bg-white border border-line rounded-lg px-2 py-1.5 text-[#7e7770] text-xs flex items-center gap-2 z-10">
      <span>⌕</span>
      <span>{{ mode === 'design' ? '当前空间 · ' + (designZone?.reviewUnit === 'mm' ? '毫米' : '待校准') : '空间总览' }}</span>
    </div>
  </div>
</template>
