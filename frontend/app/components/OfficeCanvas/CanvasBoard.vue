<script setup lang="ts">
import { uid } from '~/utils/helpers'
import { sameOriginAssetUrl } from '~/utils/apiBase'
import { TONES } from '~/utils/sample'
import type { OfficeObject, ZoneObject, FurnitureObject } from '~/types/office'
import type { FloorPlanAnalysisResponse, FloorPlanDrawingBounds } from '~/types/floorPlan'
import { normalizePolygon, roomsBounds } from '~/utils/floorPlan'
import { manualOfficeFurniture, officePlacementProblem, officeFurnitureMoveProblem, officeFurnitureFootprint } from '~/utils/officeManualPlacement'
import { officeFurnitureTemplateId, officeFurnitureShowsSeat } from '~/utils/officeFurniture'
import { chairActivityRect } from '~/utils/layoutPlanning'
import { canvasObjectsForView, canvasZoneBounds, fitCanvasZone, canvasPointerWorld } from '~/utils/officeCanvasView'


const store = useOfficeStore()
const props = defineProps<{ layoutPreview?: { zoneId: string; furniture: FurnitureObject[]; replaceIds: string[]; seatClearanceMm: number; exclusions: import('~/types/layoutPlanning').LayoutExclusion[] } | null; showDimensions?: boolean }>()
const activePreview = computed(() => props.layoutPreview?.zoneId === store.designZoneId ? props.layoutPreview : null)
const viewRotation = defineModel<number>('viewRotation', { default: 0 })
const tool = defineModel<string>('tool', { default: 'select' })
const selectedId = defineModel<string | null>('selectedId')
const showSubmit = defineModel<boolean>('showSubmit')
const mode = defineModel<'overview' | 'design'>('mode', { default: 'overview' })

const DEFAULT_W = 1540
const DEFAULT_H = 960
const WORLD_PADDING = 240

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
  if (mode.value === 'design' && tool.value === 'select') return '点击选中 · Shift/Ctrl 点选多个 · 空白处框选 · 拖动选中家具一起移动'
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

const placementPointer = ref<{ x: number; y: number } | null>(null)
const placementMessage = ref('')
const placing = computed(() => mode.value === 'design' && store.furniturePlacement?.zoneId === store.designZoneId && !!store.furniturePlacement)
const placementPreview = computed(() => placing.value && placementPointer.value
  ? manualOfficeFurniture(store.furniturePlacement!, placementPointer.value.x, placementPointer.value.y) : null)
const placementProblem = computed(() => placementPreview.value && designZone.value
  ? officePlacementProblem(designZone.value, placementPreview.value, furniture.value, store.furniturePlacement!.spacing) : null)
watch(() => store.furniturePlacement, draft => {
  placementPointer.value = null
  placementMessage.value = ''
  if (draft) { tool.value = 'select'; selectedId.value = null; selectedFurnitureIds.value = [] }
})
watch(tool, value => { selectedFurnitureIds.value = []; if (!['select', 'pan'].includes(value)) store.cancelFurniturePlacement() })

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
  mode: 'pan' | 'move' | 'furniture' | 'marquee' | 'zone' | 'arrow'
  cx?: number
  cy?: number
  px?: number
  py?: number
  id?: string
  ox?: number
  oy?: number
  moved?: boolean
  x?: number
  y?: number
  lastX?: number
  lastY?: number
  ids?: string[]
  dx?: number
  dy?: number
}

const drag = ref<DragState | null>(null)
const selectedFurniture = computed(() => furniture.value.find(item => item.id === selectedId.value) || null)
const selectedFurnitureIds = ref<string[]>([])
const selectedFurnitureItems = computed(() => furniture.value.filter(item => selectedFurnitureIds.value.includes(item.id)))
const moveProblem = ref('')
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
  if (store.deleteObject(object.id)) {
    if (object.kind === 'furniture') lastDeletedFurnitureId.value = object.id
    selectedId.value = null
    selectedFurnitureIds.value = []
  }
}

function chooseTool(t: string) {
  if (mode.value === 'design' && ['zone', 'arrow'].includes(t)) return
  tool.value = t
  selectedId.value = null
  selectedFurnitureIds.value = []
  moveProblem.value = ''
}

function onWheel(e: WheelEvent) {
  e.preventDefault()
  store.zoom = Math.max(0.00001, Math.min(2.3, store.zoom * (e.deltaY < 0 ? 1.09 : 1 / 1.09)))
  placeBoard()
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
    const problem = store.placeFurnitureAt(p.x, p.y)
    placementMessage.value = problem || `已添加 ${store.furniturePlacement?.template.name || '家具'}，可继续放置`
    return
  }

  if (tool.value === 'select') {
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
    const candidates = furniture.value.filter(item => ids.has(item.id)).map(item => ({ ...item, x: item.x + d.dx!, y: item.y + d.dy! }))
    moveProblem.value = officeFurnitureMoveProblem(designZone.value, candidates, furniture.value) || ''
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
    moveProblem.value = ''
    showSubmit.value = false
    return
  }
  if (typing || showSubmit.value || document.querySelector('[role="dialog"][aria-modal="true"]')) return
  if (placing.value) {
    if (e.key.toLowerCase() === 'r') { e.preventDefault(); store.rotateFurniturePlacement() }
    return
  }
  if (['Delete', 'Backspace'].includes(e.key) && (selectedId.value || selectedFurnitureIds.value.length)) {
    e.preventDefault()
    deleteSelection()
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
  moveProblem.value = ''
  nextTick(() => fitBoard())
}, { immediate: true })

defineExpose({ fitBoard, rotateView })

function objectStyle(o: OfficeObject) {
  const base: Record<string, string> = { position: 'absolute', left: o.x + 'px', top: o.y + 'px' }
  return base
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
    <div v-if="mode === 'design' && props.showDimensions !== false" class="absolute right-3 bottom-12 z-10 rounded-lg border border-[#e8dfd5] bg-white/90 px-2 py-1 text-[10px] text-[#786b5d] pointer-events-none">绿色：离墙 · 棕色：组间净距<br>按家具外包占地测量，椅子为座席示意</div>
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
        v-if="mode !== 'design'"
        class="absolute pointer-events-none overflow-hidden"
        :style="{ ...alignedImageStyle, opacity: store.blank ? 0.05 : 0.75 }"
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

      <svg v-if="canonicalPlan" :width="worldBounds.width" :height="worldBounds.height" class="absolute inset-0 pointer-events-none overflow-visible">
        <g v-for="zone in visibleZones" :key="zone.id">
          <polygon :points="zone.polygon?.map(point => point.join(',')).join(' ')" :data-id="zone.id"
            class="board-element pointer-events-auto cursor-pointer" fill="#79ac94" fill-opacity="0.07" stroke="#749c86" :stroke-width="1.5 / store.zoom" @pointerdown.stop="onPointerDown" />
        </g>
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
            class="board-element pointer-events-auto w-[180px] min-h-[114px] bg-[#fff0a5] shadow-lg p-4 text-[#634c2b] whitespace-pre-wrap text-[17px] leading-relaxed cursor-move overflow-wrap-anywhere transition-opacity"
            style="transform: rotate(-3deg);"
            :class="[
              selectedId === o.id ? 'outline outline-4 outline-[#5092db]' : '',
            ]"
            :style="objectStyle(o)"
            @pointerdown.stop="onPointerDown"
          >
            {{ (o as any).text }}
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
              <img v-if="(o as any).image" :src="(o as any).image" class="w-full h-full object-cover">
              <div v-else class="absolute inset-0 grid place-items-center text-[#927f6d] text-sm">▤<br>办公风格参考</div>
            </div>
            <div class="text-[13px] text-center text-[#6b6056] pt-1 truncate">{{ (o as any).caption || '办公参考图' }}</div>
          </div>
        </template>

        <!-- Furniture -->
        <div
          v-for="f in furniture"
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
        <div v-for="area in activePreview.exclusions" :key="area.id" :style="{ position: 'absolute', left: area.x + 'px', top: area.y + 'px', width: area.w + 'px', height: area.h + 'px', border: `${2 / store.zoom}px dashed #c45c4c`, background: '#c45c4c22', fontSize: `${11 / store.zoom}px` }">{{ area.label }}</div>
      </div>

      <OfficeCanvasLayoutDimensions v-if="mode === 'design' && designZone && props.showDimensions !== false" :zone="designZone" :items="dimensionFurniture" :selected-ids="selectedFurnitureIds" :zoom="store.zoom" :view-rotation="viewRotation" :world="worldBounds" />

      <div v-if="placementPreview" data-testid="furniture-placement-preview" class="absolute pointer-events-none opacity-60"
        :data-valid="!placementProblem" :style="{ ...furnitureStyle(placementPreview), outline: `${2 / store.zoom}px dashed ${placementProblem ? '#c45c4c' : '#306fcb'}` }">
        <OfficeCanvasFurniturePreview :template-id="officeFurnitureTemplateId(placementPreview)!" :width-mm="placementPreview.w" :depth-mm="placementPreview.h" :stroke-scale="1 / store.zoom" />
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
      <button class="flex-none rounded-lg px-2 py-1 text-xs text-red-600 hover:bg-red-50" title="Delete / Backspace" @click.stop="deleteSelection">删除选中模块</button>
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