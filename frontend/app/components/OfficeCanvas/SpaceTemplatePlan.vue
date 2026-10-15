<script setup lang="ts">
import type { SpatialSceneSnapshot, SpatialSpace } from '~/types/spatial'
import { polygonBounds } from '~/utils/spatial/geometry'
const props = defineProps<{ scene: SpatialSceneSnapshot; selectedId?: string | null }>()
const emit = defineEmits<{ (event: 'select', id: string): void }>()
const svg = ref<SVGSVGElement | null>(null)
const viewport = ref({ width: 800, height: 500 })
let observer: ResizeObserver | null = null
onMounted(() => {
  observer = new ResizeObserver(entries => {
    const rect = entries[0]?.contentRect
    if (rect) viewport.value = { width: Math.max(1, rect.width), height: Math.max(1, rect.height) }
  })
  if (svg.value) observer.observe(svg.value)
})
onBeforeUnmount(() => observer?.disconnect())
const bounds = computed(() => {
  const points = props.scene.spaces.flatMap(space => space.designGeometry.outer)
  const xs = points.map(point => point[0]), ys = points.map(point => -point[1])
  return { minX: Math.min(...xs), minY: Math.min(...ys), width: Math.max(...xs) - Math.min(...xs), height: Math.max(...ys) - Math.min(...ys) }
})
const padding = computed(() => Math.max(bounds.value.width, bounds.value.height) * 0.065)
const viewBox = computed(() => [bounds.value.minX - padding.value, bounds.value.minY - padding.value, bounds.value.width + padding.value * 2, bounds.value.height + padding.value * 2].join(' '))
const colors: Record<string, string> = { OPEN_OFFICE: '#bdd8fa', MEETING_ROOM: '#bce9c9', EXECUTIVE_OFFICE: '#f4c2d4', RECEPTION: '#ffe1a7', OTHER: '#dcd2f3', LOUNGE: '#bedfeb' }
const color = (space: SpatialSpace) => colors[space.category] || '#cce4e0'
function path(space: SpatialSpace) {
  return [space.designGeometry.outer, ...(space.designGeometry.holes || [])].map(ring => ring.map(([x, y], index) => (index ? 'L' : 'M') + x + ',' + -y).join(' ') + 'Z').join(' ')
}
function inside(point: number[], ring: number[][]) {
  let result = false
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const a = ring[i], b = ring[j]
    if ((a[1] > point[1]) !== (b[1] > point[1]) && point[0] < (b[0] - a[0]) * (point[1] - a[1]) / (b[1] - a[1]) + a[0]) result = !result
  }
  return result
}
function label(space: SpatialSpace) {
  const box = polygonBounds(space.designGeometry)!, shape = space.designGeometry
  let best = { x: (box.minX + box.maxX) / 2, y: (box.minY + box.maxY) / 2 }, clearance = -1
  for (let row = 1; row < 16; row++) for (let col = 1; col < 16; col++) {
    const p = [box.minX + (box.maxX - box.minX) * col / 16, box.minY + (box.maxY - box.minY) * row / 16]
    if (!inside(p, shape.outer) || shape.holes?.some(hole => inside(p, hole))) continue
    let distance = Infinity
    for (const ring of [shape.outer, ...(shape.holes || [])]) ring.forEach((a, i) => {
      const b = ring[(i + 1) % ring.length], dx = b[0] - a[0], dy = b[1] - a[1]
      const t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy || 1)))
      distance = Math.min(distance, Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy))
    })
    // Favor clearance, then the geometric center for rooms with long parallel sides.
    distance -= Math.hypot(p[0] - (box.minX + box.maxX) / 2, p[1] - (box.minY + box.maxY) / 2) * 0.1
    if (distance > clearance) { clearance = distance; best = { x: p[0], y: p[1] } }
  }
  const size = Math.min(13 * Math.max((bounds.value.width + padding.value * 2) / viewport.value.width, (bounds.value.height + padding.value * 2) / viewport.value.height), (box.maxX - box.minX) / Math.max(6, space.name.length * 1.1), (box.maxY - box.minY) / 5)
  return { ...best, y: -best.y, size }
}
const labels = computed(() => new Map(props.scene.spaces.map(space => [space.spaceId, label(space)])))
</script>
<template>
  <svg ref="svg" v-if="scene.spaces.length" class="space-template-plan" :viewBox="viewBox" role="img" aria-label="整层空间模板分区图">
    <g v-for="space in scene.spaces" :key="space.spaceId" :data-template-space="space.spaceId" role="button" tabindex="0" :aria-label="'模板空间 ' + space.name" @click="emit('select', space.spaceId)" @keydown.enter.prevent="emit('select', space.spaceId)" @keydown.space.prevent="emit('select', space.spaceId)">
      <title>{{ space.name }} · {{ space.derived.areaM2?.toFixed(2) }} m²</title>
      <path :d="path(space)" :fill="color(space)" fill-rule="evenodd" :stroke="selectedId === space.spaceId ? '#2462b2' : '#53616a'" :stroke-width="selectedId === space.spaceId ? 4 : 3" stroke-linejoin="round" vector-effect="non-scaling-stroke" />
      <g :transform="'translate(' + labels.get(space.spaceId)!.x + ',' + labels.get(space.spaceId)!.y + ')'" text-anchor="middle" fill="#283d4b" pointer-events="none">
        <text :font-size="labels.get(space.spaceId)!.size" font-weight="650">{{ space.name }}</text>
        <text :y="labels.get(space.spaceId)!.size * 1.8" :font-size="labels.get(space.spaceId)!.size * 0.78">{{ space.derived.areaM2?.toFixed(1) }} m²</text>
      </g>
    </g>
  </svg>
</template>
