<script setup lang="ts">
import type { FurnitureObject, Product, ZoneObject } from '~/types/office'
import { canvasZoneBounds } from '~/utils/officeCanvasView'
import { officeFurnitureTemplateId } from '~/utils/officeFurniture'
import { officeFurnitureFootprintBounds } from '~/utils/officeDimensionAnnotations'

const props = defineProps<{
  zone: ZoneObject | null
  furniture: FurnitureObject[]
  products: Product[]
}>()

const scene = computed(() => {
  if (!props.zone) return null
  const zone = props.zone
  const bounds = canvasZoneBounds(zone)
  const furnitureBounds = props.furniture.map(officeFurnitureFootprintBounds)
  const minX = Math.min(bounds.minX, ...furnitureBounds.map(b => b.x))
  const minY = Math.min(bounds.minY, ...furnitureBounds.map(b => b.y))
  const maxX = Math.max(bounds.maxX, ...furnitureBounds.map(b => b.x + b.w))
  const maxY = Math.max(bounds.maxY, ...furnitureBounds.map(b => b.y + b.h))
  const padding = Math.max(maxX - minX, maxY - minY) * 0.06 || 100
  const polygon = zone.polygon?.length ? zone.polygon : [
    [zone.x, zone.y], [zone.x + zone.w, zone.y],
    [zone.x + zone.w, zone.y + zone.h], [zone.x, zone.y + zone.h],
  ]
  return {
    viewBox: `${minX - padding} ${minY - padding} ${maxX - minX + padding * 2} ${maxY - minY + padding * 2}`,
    points: polygon.map(p => p.join(',')).join(' '),
    strokeScale: Math.max(maxX - minX, maxY - minY) / 800,
  }
})

function templateId(item: FurnitureObject) {
  return officeFurnitureTemplateId(item, props.products.find(product => product.id === item.productId))
}
</script>

<template>
  <svg v-if="scene" class="layout-plan-preview" :viewBox="scene.viewBox" role="img" :aria-label="`${zone?.name}排版平面图`">
    <polygon :points="scene.points" fill="#fffdf9" stroke="#a77b51" stroke-width="2" vector-effect="non-scaling-stroke" />
    <g v-for="item in furniture" :key="item.id" :data-placement-id="item.id"
      :transform="`translate(${item.x + item.w / 2} ${item.y + item.h / 2}) rotate(${item.rotation || 0}) scale(${item.mirrorX ? -1 : 1} 1) translate(${-item.w / 2} ${-item.h / 2})`">
      <foreignObject x="0" y="0" :width="item.w" :height="item.h" overflow="visible">
        <OfficeCanvasFurniturePreview v-if="templateId(item)" :template-id="templateId(item)!"
          :width-mm="item.w" :depth-mm="item.h" :wing-width-mm="item.wingWidthMm"
          :show-seat="item.layoutSettings?.arrangementPresetId === 'l-desk-face-to-face'" :stroke-scale="scene.strokeScale" />
        <div v-else class="layout-plan-fallback" :style="{ fontSize: `${Math.min(item.w, item.h) / 8}px` }">{{ item.label || '家具' }}</div>
      </foreignObject>
    </g>
  </svg>
  <div v-else class="layout-plan-empty">暂无排版数据</div>
</template>

<style scoped>
.layout-plan-preview{display:block;width:100%;height:100%;background:#faf8f4}
.layout-plan-fallback{box-sizing:border-box;width:100%;height:100%;border:1px solid #78736d;background:#fff;display:flex;align-items:center;justify-content:center;color:#514943}
.layout-plan-empty{display:grid;place-items:center;height:100%;color:#968c82;font-size:12px}
</style>
