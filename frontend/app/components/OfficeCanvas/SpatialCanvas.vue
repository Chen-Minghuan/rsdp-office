<script setup lang="ts">
import type { SpatialGeometry, SpatialSceneSnapshot, SpatialSpace } from '~/types/spatial'
import type { CameraTransform } from '~/utils/spatial/camera'
import type { DimensionReferenceOverlay } from '~/types/dimensionCalibration'
import { fromScreen, toScreen } from '~/utils/spatial/camera'
import { polygonCentroid, polygonBounds } from '~/utils/spatial/geometry'

const props = withDefaults(defineProps<{
  scene: SpatialSceneSnapshot | null
  camera: CameraTransform
  selectedSpaceId?: string | null
  selectedVertexId?: string | null
  pointerInSourceCoordinates?: boolean
  fallbackImageUrl?: string
  showUnderlay?: boolean
  showOutline?: boolean
  showDimensions?: boolean
  underlayOpacity?: number
  tool?: string
  calibrationPoints?: Array<{ x: number; y: number }>
  originalGeometries?: SpatialGeometry[]
  dimensionReference?: DimensionReferenceOverlay | null
  reviewSpaceIds?: string[]
}>(), { showUnderlay: true, showOutline: true, showDimensions: true, underlayOpacity: 0.65, tool: 'select' })

const emit = defineEmits<{
  (e: 'selectSpace', id: string): void
  (e: 'selectVertex', spaceId: string, vertexId: string): void
  (e: 'pointerDown', p: { x: number; y: number }): void
  (e: 'pointerMove', p: { x: number; y: number }): void
  (e: 'pointerUp', p: { x: number; y: number }): void
  (e: 'updateCamera', camera: CameraTransform): void
  (e: 'viewport', size: { width: number; height: number }): void
}>()

const svgRef = ref<SVGSVGElement | null>(null)
const drag = ref<{ screen: { x: number; y: number }; camera: CameraTransform; pan: boolean } | null>(null)
let observer: ResizeObserver | null = null
const coordinateSystem = computed(() => props.scene?.workingCoordinateSystem ?? 'SOURCE_PAGE')
const selected = computed(() => props.scene?.spaces.find(space => space.spaceId === props.selectedSpaceId))
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
  return [space.designGeometry.outer, ...space.designGeometry.holes].map(ring => ring.map((p, i) => {
    const screen = pointToSvg({ x: p[0], y: p[1] })
    return (i ? 'L' : 'M') + screen.x + ',' + screen.y
  }).join(' ') + 'Z').join(' ')
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
  if (!svgRef.value || ![0, 1].includes(event.button)) return
  event.preventDefault()
  svgRef.value.setPointerCapture(event.pointerId)
  const screen = screenPoint(event)
  const isPan = props.tool === 'pan' || event.button === 1
  drag.value = { screen, camera: { ...props.camera }, pan: isPan }
  if (isPan) return
  const element = event.target as Element
  const spaceId = element.closest('[data-space-id]')?.getAttribute('data-space-id')
  const vertexId = element.getAttribute('data-vertex-id')
  if (!props.pointerInSourceCoordinates && spaceId) {
    emit('selectSpace', spaceId)
    if (vertexId) emit('selectVertex', spaceId, vertexId)
  }
  emit('pointerDown', svgToPoint(screen))
}
function onPointerMove(event: PointerEvent) {
  if (!drag.value || !svgRef.value) return
  const screen = screenPoint(event), start = drag.value
  if (start.pan) {
    emit('updateCamera', { ...start.camera, panX: start.camera.panX + screen.x - start.screen.x, panY: start.camera.panY + screen.y - start.screen.y })
    return
  }
  emit('pointerMove', svgToPoint(screen))
}
function onPointerUp(event: PointerEvent) {
  if (!svgRef.value || !drag.value) return
  emit('pointerUp', svgToPoint(screenPoint(event)))
  drag.value = null
  if (svgRef.value.hasPointerCapture(event.pointerId)) svgRef.value.releasePointerCapture(event.pointerId)
}
function onWheel(event: WheelEvent) {
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
function needsReview(space: SpatialSpace) {
  return props.reviewSpaceIds ? props.reviewSpaceIds.includes(space.spaceId) : space.confirmation.status !== 'CONFIRMED'
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
    @pointerdown="onPointerDown" @pointermove="onPointerMove" @pointerup="onPointerUp" @pointercancel="onPointerUp" @wheel.prevent="onWheel">
    <g v-if="scene">
      <g v-if="showUnderlay && scene.underlays[0]" :transform="underlayTransform()" :opacity="underlayOpacity" data-layer="underlay">
        <image v-if="fallbackImageUrl || scene.underlays[0].assetId"
          :href="scene.underlays[0].assetId ? '/uploads/floor-plan/' + scene.underlays[0].assetId : fallbackImageUrl"
          :width="scene.underlays[0].widthPx" :height="scene.underlays[0].heightPx" preserveAspectRatio="none" />
      </g>
      <g v-if="showOutline" data-layer="design">
        <g v-for="space in scene.spaces" :key="space.spaceId" :data-space-id="space.spaceId">
          <path :d="polygonPath(space)" fill-rule="evenodd"
            :fill="selectedSpaceId === space.spaceId ? '#79b2de' : !needsReview(space) ? '#78b19d' : '#dcb567'"
            :fill-opacity="selectedSpaceId === space.spaceId ? 0.14 : 0.065"
            :stroke="selectedSpaceId === space.spaceId ? '#4b8bc6' : !needsReview(space) ? '#7a9e8b' : '#cc9b4d'"
            :stroke-width="selectedSpaceId === space.spaceId ? 2 : 1.25"
            :stroke-dasharray="selectedSpaceId !== space.spaceId && needsReview(space) ? '5 4' : undefined"
            class="cursor-pointer hover:fill-opacity-20" />
          <g v-if="labelPosition(space) && labelVisible(space)" class="pointer-events-none" :transform="'translate(' + labelPosition(space)!.x + ',' + labelPosition(space)!.y + ')'">
            <rect x="-52" y="-15" width="104" height="40" rx="4" fill="#fff" fill-opacity="0.55" />
            <text text-anchor="middle" fill="#424e47" font-size="12" font-weight="600">{{ space.name }}</text>
            <text y="18" text-anchor="middle" fill="#7c8579" font-size="10">{{ space.derived.areaM2 != null ? space.derived.areaM2.toFixed(1) + ' m²' : '尺寸待校准' }}</text>
          </g>
          <template v-if="selectedSpaceId === space.spaceId && !pointerInSourceCoordinates">
            <rect v-for="(point, index) in space.designGeometry.outer" :key="space.designGeometry.outerVertexIds[index]"
              :x="pointToSvg({ x: point[0], y: point[1] }).x - 4" :y="pointToSvg({ x: point[0], y: point[1] }).y - 4" width="8" height="8"
              :data-vertex-id="space.designGeometry.outerVertexIds[index]" fill="#fff" stroke="#4b8bc6" stroke-width="1.8" class="cursor-move" />
          </template>
        </g>
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
      <g v-if="dimensionReference" data-layer="dimension-reference" pointer-events="none" :stroke="dimensionReference.accepted ? '#b1813e' : '#b97054'" stroke-width="2">
        <line :x1="pointToSvg(dimensionReference.start).x" :y1="pointToSvg(dimensionReference.start).y" :x2="pointToSvg(dimensionReference.end).x" :y2="pointToSvg(dimensionReference.end).y" stroke-dasharray="5 3" />
        <circle v-for="(point, index) in [dimensionReference.start, dimensionReference.end]" :key="index" :cx="pointToSvg(point).x" :cy="pointToSvg(point).y" r="5" fill="#fffaf1" />
        <g :transform="'translate(' + ((pointToSvg(dimensionReference.start).x + pointToSvg(dimensionReference.end).x) / 2) + ',' + ((pointToSvg(dimensionReference.start).y + pointToSvg(dimensionReference.end).y) / 2 - 16) + ')'">
          <rect x="-44" y="-12" width="88" height="23" rx="5" fill="#fff9ed" stroke-width="1" />
          <text y="4" text-anchor="middle" font-size="11" fill="#9e713c" stroke="none">{{ dimensionReference.label }}</text>
        </g>
      </g>
    </g>
  </svg>
</template>
