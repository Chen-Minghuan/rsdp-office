<script setup lang="ts">
import type { FurnitureObject, ZoneObject } from '~/types/office'
import { formatDimensionMm, officeDimensionBounds, officeLayoutClearances, officeFurnitureFootprintBounds } from '~/utils/officeDimensionAnnotations'

const props = defineProps<{ zone: ZoneObject; items: FurnitureObject[]; selectedIds: string[]; zoom: number; viewRotation: number; world: { width: number; height: number } }>()
const bounds = computed(() => officeDimensionBounds(props.zone))
const references = computed(() => officeLayoutClearances(props.zone, props.items))
const unit = computed(() => 1 / Math.max(0.00001, props.zoom))
const selected = computed(() => props.items.filter(item => props.selectedIds.includes(item.id)))
function labelPoint(d: ReturnType<typeof officeLayoutClearances>[number]) {
  const x = (d.x1 + d.x2) / 2, y = (d.y1 + d.y2) / 2
  if (d.kind === 'wall') {
    if (d.id === 'left' || d.id === 'right') return { x: d.x2 + (d.id === 'left' ? -20 : 20) * unit.value, y }
    return { x, y: d.y2 + (d.id === 'top' ? -20 : 20) * unit.value }
  }
  return { x: x + (d.axis === 'y' ? 15 : 0) * unit.value, y: y - (d.axis === 'x' ? 12 : 0) * unit.value }
}
function wallTextAngle(d: ReturnType<typeof officeLayoutClearances>[number]) {
  const base = d.axis === 'x' ? 90 : 0
  return base + ((base + props.viewRotation) % 360 >= 180 ? 180 : 0)
}
</script>

<template>
  <svg :width="world.width" :height="world.height" :viewBox="`0 0 ${world.width} ${world.height}`" class="absolute inset-0 pointer-events-none overflow-visible" aria-label="排版尺寸标注">
    <g fill="none" stroke="#9a7657" :stroke-width="unit * 1.2">
      <line :x1="bounds.minX" :y1="bounds.minY - unit * 46" :x2="bounds.maxX" :y2="bounds.minY - unit * 46" />
      <line v-for="x in [bounds.minX, bounds.maxX]" :key="x" :x1="x" :y1="bounds.minY - unit * 49" :x2="x" :y2="bounds.minY - unit * 43" />
    </g>
    <g fill="#795a41" :font-size="11 * unit" font-family="Arial, 'Microsoft YaHei', sans-serif" text-anchor="middle" dominant-baseline="middle" paint-order="stroke" stroke="#fffdf9" :stroke-width="4 * unit" stroke-linejoin="round">
      <text :x="(bounds.minX + bounds.maxX) / 2" :y="bounds.minY - unit * 46" :transform="`rotate(${viewRotation >= 180 ? 180 : 0} ${(bounds.minX + bounds.maxX) / 2} ${bounds.minY - unit * 46})`">空间外接尺寸 {{ formatDimensionMm(bounds.width) }} × {{ formatDimensionMm(bounds.height) }}</text>
    </g>
    <g v-for="d in references" :key="d.id" :data-clearance-kind="d.kind" :data-clearance-id="d.id" :data-clearance-mm="Math.round(d.distanceMm)">
      <title>{{ d.label }} {{ formatDimensionMm(d.distanceMm) }}</title>
      <g fill="none" :stroke="d.kind === 'wall' ? '#387b67' : '#956d47'" :stroke-width="unit * 1.2">
        <line :x1="d.x1" :y1="d.y1" :x2="d.x2" :y2="d.y2" />
        <line v-for="p in [{x:d.x1,y:d.y1},{x:d.x2,y:d.y2}]" :key="`${p.x}:${p.y}`" :x1="p.x - (d.axis === 'y' ? unit * 3 : 0)" :y1="p.y - (d.axis === 'x' ? unit * 3 : 0)" :x2="p.x + (d.axis === 'y' ? unit * 3 : 0)" :y2="p.y + (d.axis === 'x' ? unit * 3 : 0)" />
      </g>
      <text :x="labelPoint(d).x" :y="labelPoint(d).y" :transform="`rotate(${d.kind === 'wall' ? wallTextAngle(d) : -viewRotation} ${labelPoint(d).x} ${labelPoint(d).y})`" :fill="d.kind === 'wall' ? '#286954' : '#795a41'" :font-size="10 * unit" font-family="Arial, 'Microsoft YaHei', sans-serif" text-anchor="middle" dominant-baseline="middle" paint-order="stroke" stroke="#fffdf9" :stroke-width="4 * unit" stroke-linejoin="round">{{ d.kind === 'wall' ? d.label + ' ' : '' }}{{ formatDimensionMm(d.distanceMm) }}</text>
    </g>
    <g v-for="item in selected" :key="item.id" fill="#4f4a45" :font-size="11 * unit" text-anchor="middle" paint-order="stroke" stroke="#fffdf9" :stroke-width="4 * unit">
      <text :x="officeFurnitureFootprintBounds(item).x + officeFurnitureFootprintBounds(item).w / 2" :y="officeFurnitureFootprintBounds(item).y + officeFurnitureFootprintBounds(item).h / 2" :transform="`rotate(${-viewRotation} ${officeFurnitureFootprintBounds(item).x + officeFurnitureFootprintBounds(item).w / 2} ${officeFurnitureFootprintBounds(item).y + officeFurnitureFootprintBounds(item).h / 2})`">{{ Math.round(item.w) }} × {{ Math.round(item.h) }} mm</text>
    </g>
  </svg>
</template>
