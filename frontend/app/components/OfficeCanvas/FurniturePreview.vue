<script setup lang="ts">
import ManualFurnitureGlyph from './ManualFurnitureGlyph.vue'
import { furnitureById } from '~/utils/manualFurniture'
import { furnitureGlyphItem } from '~/utils/officeFurniture'

const props = defineProps<{
  templateId: string
  widthMm?: number
  depthMm?: number
  wingWidthMm?: number
  showSeat?: boolean
  selected?: boolean
  thumbnail?: boolean
  strokeScale?: number
}>()
const template = computed(() => furnitureById.get(props.templateId))
const item = computed(() => template.value ? {
  ...furnitureGlyphItem(template.value, props.widthMm, props.depthMm),
  ...(props.wingWidthMm ? { wingWidthMm: props.wingWidthMm } : {}),
} : null)
const viewBox = computed(() => {
  if (!item.value) return '0 0 1 1'
  const { widthMm: w, depthMm: d } = item.value
  const padding = props.thumbnail ? Math.max(w, d) * 0.09 : 0
  return `${-padding} ${-padding} ${w + padding * 2} ${d + padding * 2}`
})
</script>

<template>
  <svg :viewBox="viewBox" :data-furniture-template="templateId" class="block h-full w-full overflow-visible" aria-hidden="true">
    <ManualFurnitureGlyph v-if="item" :item="item" :selected="selected" :stroke-scale="strokeScale" :show-seat="showSeat" />
  </svg>
</template>
