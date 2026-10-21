<script setup lang="ts">
import type { SpatialGeometry, SpatialObstacleKind, SpatialSceneSnapshot, SpatialSpace } from '~/types/spatial'
import type { CameraTransform } from '~/utils/spatial/camera'
import { fromScreen, toScreen } from '~/utils/spatial/camera'
import { withApiBase } from '~/utils/apiBase'
import { polygonCentroid, polygonBounds } from '~/utils/spatial/geometry'
import type { ContourOperation, ResizeHandle } from '~/utils/spatial/contourTransform'
import { obstacleLabel } from '~/utils/spatial/obstacles'

const props = withDefaults(defineProps<{
  scene: SpatialSceneSnapshot | null
  camera: CameraTransform
  selectedSpaceId?: string | null
  selectedVertexId?: string | null
  selectedObstacleId?: string | null
  obstacleDraft?: SpatialGeometry | null
  obstacleDraftKind?: SpatialObstacleKind
  pointerInSourceCoordinates?: boolean
  fallbackImageUrl?: string
  showUnderlay?: boolean
  showOutline?: boolean
  showDimensions?: boolean
  underlayOpacity?: number
  tool?: string
  calibrationPoints?: Array<{ x: number; y: number }>
  originalGeometries?: SpatialGeometry[]
  tracePoints?: Array<{ x: number; y: number }>
  interactionActive?: boolean
}>(), { showUnderlay: true, showOutline: true, showDimensions: true, underlayOpacity: 0.65, tool: 'select' })

const emit = defineEmits<{
  (e: 'selectSpace', id: string): void
  (e: 'selectObstacle', id: string | null): void
  (e: 'obstacleTransformStart', id: string, handle: string, point: { x: number; y: number }): void
  (e: 'selectVertex', spaceId: string, vertexId: string): void
  (e: 'insertVertex', spaceId: string, edgeIndex: number): void
  (e: 'transformStart', spaceId: string, operation: ContourOperation, point: { x: number; y: number }): void
  (e: 'pointerCancel'): void
  (e: 'pointerDown', p: { x: number; y: number }): void
  (e: 'pointerMove', p: { x: number; y: number }): void
  (e: 'pointerUp', p: { x: number; y: number }): void
  (e: 'updateCamera', camera: CameraTransform): void
  (e: 'viewport', size: { width: number; height: number }): void
}>()

const svgRef = ref<SVGSVGElement | null>(null)
const drag = ref<{ pointerId: number; screen: { x: number; y: number }; camera: CameraTransform; pan: boolean } | null>(null)
let observer: ResizeObserver | null = null
const coordinateSystem = computed(() => props.scene?.workingCoordinateSystem ?? 'SOURCE_PAGE')
const selected = computed(() => props.scene?.spaces.find(space => space.spaceId === props.selectedSpaceId))
const selectedObstacle = computed(() => props.scene?.obstacles.find(obstacle => obstacle.obstacleId === props.selectedObstacleId))
const obstacleHandles = computed(() => {
  const obstacle = selectedObstacle.value
  if (!obstacle || props.tool !== 'select' || props.pointerInSourceCoordinates || props.interactionActive) return []
  if (obstacle.kind === 'DOOR_CLEARANCE') return [{ handle: 'radius', point: obstacle.geometry.outer[Math.floor(obstacle.geometry.outer.length / 2)] }]
  return obstacle.geometry.outer.slice(0, 4).map((point, index) => ({ handle: String(index), point }))
})
const selectionFrame = computed(() => {
  if (!selected.value || props.selectedObstacleId || props.tool !== 'select' || props.pointerInSourceCoordinates || !props.showOutline) return null
  const bounds = polygonBounds(selected.value.designGeometry)
  if (!bounds) return null
  const corners = selected.value.designGeometry.outer.map(([x, y]) => pointToSvg({ x, y }))
  const x = Math.min(...corners.map(p => p.x)), y = Math.min(...corners.map(p => p.y))
  const width = Math.max(...corners.map(p => p.x)) - x, height = Math.max(...corners.map(p => p.y)) - y
  const positions: Array<[ResizeHandle, number, number, string]> = [
    ['nw', x, y, 'nwse-resize'], ['n', x + width / 2, y, 'ns-resize'], ['ne', x + width, y, 'nesw-resize'],
    ['e', x + width, y + height / 2, 'ew-resize'], ['se', x + width, y + height, 'nwse-resize'],
    ['s', x + width / 2, y + height, 'ns-resize'], ['sw', x, y + height, 'nesw-resize'], ['w', x, y + height / 2, 'ew-resize'],
  ]
  return { x, y, width, height, handles: positions }
})
const dimensions = computed(() => {
  const space = selected.value
  if (!space || !props.showDimensions || space.designGeometry.coordinateSystem !== 'WORLD_MM') return []
  const ring = space.designGeometry.outer
  return ring.map((a, i) => {
    if (space.rectangleFrame && i !== 0 && i !== ring.length - 1) return null
    const b = ring[(i + 1) % ring.length]
    const start = pointToSvg({ x: a[0], y: a[1] }), end = pointToSvg({ x: b[0], y: b[1] })
    const dx = end.x - start.x, dy = end.y - start.y, screenLength = Math.hypot(dx, dy)
    if (screenLength < 40) return null
    const center = labelPosition(space)!
    const midpoint = { x: (start.x + end.x) / 2, y: (start.y + end.y) / 2 }
    let nx = -dy / screenLength, ny = dx / screenLength
    if (nx * (midpoint.x - center.x) + ny * (midpoint.y - center.y) < 0) { nx = -nx; ny = -ny }
    const offset = 31
    return { start, end, a: { x: start.x + nx * offset, y: start.y + ny * offset }, b: { x: end.x + nx * offset, y: end.y + ny * offset }, label: { x: midpoint.x + nx * offset, y: midpoint.y + ny * offset }, value: Math.round(Math.hypot(b[0] - a[0], b[1] - a[1])).toLocaleString(), vertical: Math.abs(dy) > Math.abs(dx) }
  }).filter(Boolean)
})

function pointToSvg(p: { x: number; y: number }) {
  return toScreen(p, coordinateSystem.value, props.camera)
}
function svgToPoint(p: { x: number; y: number }) {
  return fromScreen(p, coordinateSystem.value, props.camera)
}
function polygonPath(space: SpatialSpace): string {
  return geometryPath(space.designGeometry)
}
function geometryPath(geometry: SpatialGeometry): string {
  return [geometry.outer, ...geometry.holes].map(ring => ring.map((p, i) => {
    const screen = pointToSvg({ x: p[0], y: p[1] })
    return (i ? 'L' : 'M') + screen.x + ',' + screen.y
  }).join(' ') + 'Z').join(' ')
}
function obstacleColor(kind: SpatialObstacleKind) {
  return kind === 'DOOR_CLEARANCE' ? '#2586a0' : kind === 'NO_PLACEMENT' ? '#b07b38' : '#b44f45'
}
function obstacleSize(geometry: SpatialGeometry, kind?: SpatialObstacleKind) {
  const bounds = polygonBounds(geometry)
  if (!bounds) return ''
  return kind === 'DOOR_CLEARANCE' ? 'R ' + Math.round(Math.max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY)) + ' mm' : Math.round(bounds.maxX - bounds.minX) + ' × ' + Math.round(bounds.maxY - bounds.minY) + ' mm'
}
function obstacleLabelVisible(geometry: SpatialGeometry) {
  const bounds = polygonBounds(geometry)
  return !!bounds && (bounds.maxX - bounds.minX) * props.camera.zoom > 54 && (bounds.maxY - bounds.minY) * props.camera.zoom > 28
}
function edgeTransform(space: SpatialSpace, index: number) {
  const ring = space.designGeometry.outer, a = ring[index], b = ring[(index + 1) % ring.length]
  const point = pointToSvg({ x: (a[0] + b[0]) / 2, y: (a[1] + b[1]) / 2 })
  return 'translate(' + point.x + ',' + point.y + ')'
}
function originalPath(geometry: SpatialGeometry) {
  return geometry.outer.map((point, index) => {
    const screen = pointToSvg({ x: point[0], y: point[1] })
    return (index ? 'L' : 'M') + screen.x + ',' + screen.y
  }).join(' ') + 'Z'
}
function underlayTransform(): string {
  const underlay = props.scene?.underlays?.[0]
  if (!underlay) return ''
  const matrix = underlay.imagePixelToWorld ?? [1, 0, 0, 1, 0, 0]
  const project = (x: number, y: number) => pointToSvg({
    x: matrix[0] * x + matrix[2] * y + matrix[4],
    y: matrix[1] * x + matrix[3] * y + matrix[5],
  })
  // Compose source-pixel → scene → screen; do not fit the image separately.
  const origin = project(0, 0), x = project(1, 0), y = project(0, 1)
  return 'matrix(' + [x.x - origin.x, x.y - origin.y, y.x - origin.x, y.y - origin.y, origin.x, origin.y].join(',') + ')'
}
function screenPoint(event: PointerEvent | WheelEvent) {
  const rect = svgRef.value!.getBoundingClientRect()
  return { x: event.clientX - rect.left, y: event.clientY - rect.top }
}
function onPointerDown(event: PointerEvent) {
  if (!svgRef.value || drag.value || ![0, 1].includes(event.button)) return
  event.preventDefault()
  svgRef.value.setPointerCapture(event.pointerId)
  const screen = screenPoint(event)
  const isPan = props.tool === 'pan' || event.button === 1
  drag.value = { pointerId: event.pointerId, screen, camera: { ...props.camera }, pan: isPan }
  if (isPan) return
  const element = event.target as Element
  const spaceId = element.closest('[data-space-id]')?.getAttribute('data-space-id')
  const vertexId = element.getAttribute('data-vertex-id')
  const edgeIndex = element.getAttribute('data-edge-index')
  const handle = element.getAttribute('data-resize-handle') as ResizeHandle | null
  const boundaryEdge = element.getAttribute('data-boundary-edge')
  const obstacleId = element.closest('[data-obstacle-id]')?.getAttribute('data-obstacle-id')
  if (obstacleId) {
    emit('selectObstacle', obstacleId)
    if (props.tool === 'select') emit('obstacleTransformStart', obstacleId, element.getAttribute('data-obstacle-handle') || 'move', svgToPoint(screen))
  } else if (props.tool === 'select') emit('selectObstacle', null)
  if (!props.pointerInSourceCoordinates && spaceId && !obstacleId) {
    emit('selectSpace', spaceId)
    if (props.tool === 'select') emit('transformStart', spaceId, handle || 'move', svgToPoint(screen))
    else if (props.tool === 'edit') {
      if (vertexId) emit('selectVertex', spaceId, vertexId)
      else if (edgeIndex !== null) emit('insertVertex', spaceId, Number(edgeIndex))
      else if (boundaryEdge !== null) emit('transformStart', spaceId, { edge: Number(boundaryEdge) }, svgToPoint(screen))
    }
  }
  emit('pointerDown', svgToPoint(screen))
}
function onPointerMove(event: PointerEvent) {
  if (!drag.value || !svgRef.value || drag.value.pointerId !== event.pointerId) return
  const screen = screenPoint(event), start = drag.value
  if (start.pan) {
    emit('updateCamera', { ...start.camera, panX: start.camera.panX + screen.x - start.screen.x, panY: start.camera.panY + screen.y - start.screen.y })
    return
  }
  emit('pointerMove', svgToPoint(screen))
}
function onPointerUp(event: PointerEvent) {
  if (!svgRef.value || !drag.value || drag.value.pointerId !== event.pointerId) return
  if (!drag.value.pan) emit('pointerMove', svgToPoint(screenPoint(event)))
  emit('pointerUp', svgToPoint(screenPoint(event)))
  drag.value = null
  if (svgRef.value.hasPointerCapture(event.pointerId)) svgRef.value.releasePointerCapture(event.pointerId)
}
function onPointerCancel(event: PointerEvent) {
  if (drag.value?.pointerId !== event.pointerId) return
  drag.value = null
  emit('pointerCancel')
  if (svgRef.value?.hasPointerCapture(event.pointerId)) svgRef.value.releasePointerCapture(event.pointerId)
}
function onWheel(event: WheelEvent) {
  if (props.interactionActive || drag.value) return
  const anchor = screenPoint(event)
  const zoom = Math.max(0.00001, Math.min(20, props.camera.zoom * Math.exp(-event.deltaY * 0.0015)))
  const ratio = zoom / props.camera.zoom
  emit('updateCamera', { zoom, panX: anchor.x - (anchor.x - props.camera.panX) * ratio, panY: anchor.y - (anchor.y - props.camera.panY) * ratio })
}
function labelPosition(space: SpatialSpace) {
  const center = polygonCentroid(space.designGeometry)
  if (!center) return null
  return pointToSvg(center)
}
function labelVisible(space: SpatialSpace) {
  const bounds = polygonBounds(space.designGeometry)
  return bounds && (bounds.maxX - bounds.minX) * props.camera.zoom > 48 && (bounds.maxY - bounds.minY) * props.camera.zoom > 35
}
onMounted(() => {
  observer = new ResizeObserver(entries => {
    const rect = entries[0]?.contentRect
    if (rect) emit('viewport', { width: rect.width, height: rect.height })
  })
  if (svgRef.value) observer.observe(svgRef.value)
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <svg ref="svgRef" role="img" aria-label="空间核对画布" class="absolute inset-0 h-full w-full touch-none select-none"
    :class="tool === 'pan' ? 'cursor-grab' : pointerInSourceCoordinates ? 'cursor-crosshair' : 'cursor-default'"
    @pointerdown="onPointerDown" @pointermove="onPointerMove" @pointerup="onPointerUp" @pointercancel="onPointerCancel" @wheel.prevent="onWheel">
    <g v-if="scene">
      <g v-if="showUnderlay && scene.underlays[0]" :transform="underlayTransform()" :opacity="underlayOpacity" data-layer="underlay">
        <image v-if="fallbackImageUrl || scene.underlays[0].assetId"
          :href="scene.underlays[0].assetId ? withApiBase('/uploads/floor-plan/' + scene.underlays[0].assetId) : fallbackImageUrl"
          :width="scene.underlays[0].widthPx" :height="scene.underlays[0].heightPx" preserveAspectRatio="none" />
      </g>
      <g v-if="showOutline" data-layer="design">
        <g v-for="space in scene.spaces" :key="space.spaceId" :data-space-id="space.spaceId">
          <path :d="polygonPath(space)" fill-rule="evenodd"
            :fill="selectedSpaceId === space.spaceId ? '#79b2de' : '#dcb567'"
            :fill-opacity="selectedSpaceId === space.spaceId ? 0.14 : 0.065"
            :stroke="selectedSpaceId === space.spaceId ? '#4b8bc6' : '#cc9b4d'"
            :stroke-width="selectedSpaceId === space.spaceId ? 2 : 1.25"
            :stroke-dasharray="selectedSpaceId === space.spaceId ? undefined : '5 4'"
            :class="tool === 'select' ? 'cursor-move hover:fill-opacity-20' : 'cursor-pointer hover:fill-opacity-20'" />
          <g v-if="labelPosition(space) && labelVisible(space)" class="pointer-events-none" :transform="'translate(' + labelPosition(space)!.x + ',' + labelPosition(space)!.y + ')'">
            <rect x="-52" y="-15" width="104" height="40" rx="4" fill="#fff" fill-opacity="0.55" />
            <text text-anchor="middle" fill="#424e47" font-size="12" font-weight="600">{{ space.name }}</text>
            <text y="18" text-anchor="middle" fill="#7c8579" font-size="10">{{ space.derived.areaM2 != null ? space.derived.areaM2.toFixed(1) + ' m²' : '尺寸待校准' }}</text>
          </g>
          <template v-if="selectedSpaceId === space.spaceId && !pointerInSourceCoordinates && tool === 'edit'">
            <line v-for="(point, index) in space.designGeometry.outer" :key="'boundary-' + index"
              :x1="pointToSvg({ x: point[0], y: point[1] }).x" :y1="pointToSvg({ x: point[0], y: point[1] }).y"
              :x2="pointToSvg({ x: space.designGeometry.outer[(index + 1) % space.designGeometry.outer.length][0], y: space.designGeometry.outer[(index + 1) % space.designGeometry.outer.length][1] }).x"
              :y2="pointToSvg({ x: space.designGeometry.outer[(index + 1) % space.designGeometry.outer.length][0], y: space.designGeometry.outer[(index + 1) % space.designGeometry.outer.length][1] }).y"
              stroke="transparent" stroke-width="10" :data-boundary-edge="index" class="cursor-move" />
            <template v-if="tool === 'edit'">
              <g v-for="(point, index) in space.designGeometry.outer" :key="'edge-' + index"
                :transform="edgeTransform(space, index)">
                <circle r="7" fill="#fff" stroke="#4b8bc6" :data-edge-index="index" class="cursor-crosshair"><title>添加转折点</title></circle>
                <path d="M-3 0H3 M0-3V3" stroke="#4b8bc6" pointer-events="none" />
              </g>
            </template>
            <rect v-for="(point, index) in space.designGeometry.outer" :key="space.designGeometry.outerVertexIds[index]"
              :x="pointToSvg({ x: point[0], y: point[1] }).x - 4" :y="pointToSvg({ x: point[0], y: point[1] }).y - 4" width="8" height="8"
              :data-vertex-id="space.designGeometry.outerVertexIds[index]" fill="#fff" stroke="#4b8bc6" stroke-width="1.8" class="cursor-move" />
          </template>
        </g>
      </g>
      <g v-if="scene?.obstacles?.length" data-layer="obstacles">
        <g v-for="obstacle in scene.obstacles" :key="obstacle.obstacleId" :data-obstacle-id="obstacle.obstacleId" class="cursor-pointer">
          <path :d="geometryPath(obstacle.geometry)" :fill="obstacleColor(obstacle.kind)" fill-opacity="0.24" :stroke="obstacleColor(obstacle.kind)" :stroke-width="selectedObstacleId === obstacle.obstacleId ? 3 : 1.8" stroke-dasharray="6 3" />
          <text v-if="obstacleLabelVisible(obstacle.geometry)" :x="pointToSvg(polygonCentroid(obstacle.geometry)!).x" :y="pointToSvg(polygonCentroid(obstacle.geometry)!).y" text-anchor="middle" dominant-baseline="middle" :fill="obstacleColor(obstacle.kind)" font-size="11" font-weight="600" pointer-events="none">{{ obstacleLabel(obstacle.kind) }} · {{ obstacleSize(obstacle.geometry, obstacle.kind) }}</text>
          <title>{{ obstacleLabel(obstacle.kind) }} · {{ obstacleSize(obstacle.geometry, obstacle.kind) }}</title>
        </g>
      </g>
      <g v-if="obstacleDraft" data-layer="obstacle-draft" pointer-events="none">
        <path :d="geometryPath(obstacleDraft)" :fill="obstacleColor(obstacleDraftKind || 'COLUMN')" fill-opacity="0.18" :stroke="obstacleColor(obstacleDraftKind || 'COLUMN')" stroke-width="2" stroke-dasharray="5 3" />
      </g>
      <g v-if="selectedObstacle && obstacleHandles.length" data-layer="obstacle-handles">
        <rect v-for="handle in obstacleHandles" :key="handle.handle" :x="pointToSvg({ x: handle.point[0], y: handle.point[1] }).x - 5" :y="pointToSvg({ x: handle.point[0], y: handle.point[1] }).y - 5" width="10" height="10" rx="2"
          :data-obstacle-id="selectedObstacle.obstacleId" :data-obstacle-handle="handle.handle" fill="#fff" :stroke="obstacleColor(selectedObstacle.kind)" stroke-width="2" class="cursor-move"><title>{{ selectedObstacle.kind === 'DOOR_CLEARANCE' ? '拖动调整门洞半径' : '拖动调整障碍尺寸' }}</title></rect>
      </g>
      <g v-if="selectionFrame && selected" :data-space-id="selected.spaceId" data-layer="selection-frame">
        <rect :x="selectionFrame.x" :y="selectionFrame.y" :width="selectionFrame.width" :height="selectionFrame.height" fill="none" stroke="#4b8bc6" stroke-width="1" stroke-dasharray="4 3" pointer-events="none" />
        <rect v-for="[name, x, y, cursor] in selectionFrame.handles" :key="name" :x="x - 5" :y="y - 5" width="10" height="10" rx="1"
          :data-resize-handle="name" :style="{ cursor }" fill="white" stroke="#4b8bc6" stroke-width="1.8"><title>拖动缩放空间</title></rect>
      </g>
      <g v-if="tracePoints?.length" data-layer="trace" pointer-events="none">
        <path :d="tracePoints.map((p, i) => { const s = pointToSvg(p); return (i ? 'L' : 'M') + s.x + ',' + s.y }).join(' ') + (tracePoints.length > 2 ? 'Z' : '')" fill="#79b2de" fill-opacity="0.16" stroke="#4b8bc6" stroke-width="2" stroke-dasharray="6 3" />
        <circle v-for="(point, index) in tracePoints" :key="index" :cx="pointToSvg(point).x" :cy="pointToSvg(point).y" r="4" fill="#fff" stroke="#4b8bc6" stroke-width="2" />
      </g>
      <g v-if="originalGeometries?.length" data-layer="original" fill="none" stroke="#8d8a80" stroke-width="1.5" stroke-dasharray="3 4" pointer-events="none">
        <path v-for="(geometry, index) in originalGeometries" :key="index" :d="originalPath(geometry)" />
      </g>
      <g v-if="showOutline && showDimensions" data-layer="dimensions" class="pointer-events-none" stroke="#8fb1d6" stroke-width="1">
        <g v-for="(dimension, index) in dimensions" :key="index">
          <template v-if="dimension">
            <path :d="'M' + dimension.start.x + ',' + dimension.start.y + 'L' + dimension.a.x + ',' + dimension.a.y + 'L' + dimension.b.x + ',' + dimension.b.y + 'L' + dimension.end.x + ',' + dimension.end.y" fill="none" />
            <g :transform="'translate(' + dimension.label.x + ',' + dimension.label.y + ') ' + (dimension.vertical ? 'rotate(-90)' : '')">
              <rect x="-29" y="-10" width="58" height="20" rx="3" fill="#f0f6fc" stroke="none" />
              <text y="4" text-anchor="middle" fill="#6687af" stroke="none" font-size="11">{{ dimension.value }}</text>
            </g>
          </template>
        </g>
      </g>
      <g v-if="pointerInSourceCoordinates && calibrationPoints?.length">
        <circle v-for="(point, index) in calibrationPoints" :key="index" :cx="pointToSvg(point).x" :cy="pointToSvg(point).y" r="5" fill="#b18452" stroke="#fff" stroke-width="2" />
        <line v-if="calibrationPoints.length === 2" :x1="pointToSvg(calibrationPoints[0]).x" :y1="pointToSvg(calibrationPoints[0]).y" :x2="pointToSvg(calibrationPoints[1]).x" :y2="pointToSvg(calibrationPoints[1]).y" stroke="#b18452" stroke-width="2" stroke-dasharray="5 4" />
      </g>

    </g>
  </svg>
</template>
