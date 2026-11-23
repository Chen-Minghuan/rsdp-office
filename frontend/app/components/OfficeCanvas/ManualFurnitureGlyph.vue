<script setup lang="ts">
import { computed } from 'vue'
import type { ManualFurnitureItem } from '~/types/manualCanvas'
import { furnitureById } from '~/utils/manualFurniture'
import { workstationSeatGeometry } from '~/utils/furniture3d/workstationSeating'
import { meetingFurnitureLinework } from '~/utils/meetingFurnitureLinework'
import { meetingRingLinework } from '~/utils/meetingRingTable'
import { tableFurnitureLinework } from '~/utils/tableFurnitureLinework'

const props = defineProps<{ item: ManualFurnitureItem; selected?: boolean; invalid?: boolean; strokeScale?: number; showSeat?: boolean }>()
const template = computed(() => furnitureById.get(props.item.templateId))
const deskTemplateId = computed(() => template.value?.seatlessOf || props.item.templateId)
const w = computed(() => props.item.widthMm)
const d = computed(() => props.item.depthMm)
const referenceCode = computed(() => template.value?.reference?.code || '')
const referencePaths = computed(() => meetingFurnitureLinework[referenceCode.value])
const tableLinework = computed(() => tableFurnitureLinework[referenceCode.value || props.item.templateId])
const ring = computed(() => meetingRingLinework(props.item.templateId, w.value, d.value))
const wing = computed(() => Math.min(props.item.wingWidthMm ?? 550, w.value / 2, d.value / 2))
const transform = computed(() => `translate(${props.item.xMm} ${props.item.yMm}) rotate(${props.item.rotationDeg}) translate(${-w.value / 2} ${-d.value / 2})`)
const sofaSeats = computed(() => props.item.templateId === 'sofa-triple' ? 3 : props.item.templateId === 'sofa-double' ? 2 : 1)
const isReferenceStraightDesk = computed(() => [
  'desk-straight', 'desk-screen-straight', 'desk-manager', 'desk-manager-double', 'desk-executive',
].includes(deskTemplateId.value))
const isReferenceLDesk = computed(() => [
  'desk-l', 'desk-l-rounded', 'desk-screen-l', 'desk-manager-l',
  'desk-executive-l',
].includes(deskTemplateId.value))
const rightPanel = computed(() => [
  'desk-straight', 'desk-screen-straight', 'desk-l', 'desk-manager', 'desk-manager-double',
  'desk-manager-l', 'desk-executive', 'desk-executive-l',
].includes(deskTemplateId.value))
const leftPanel = computed(() => [
  'desk-manager-double', 'desk-executive',
].includes(deskTemplateId.value))
const panelWidth = computed(() => Math.min(w.value * 0.24, 340))
const seatGeometry = computed(() => workstationSeatGeometry({ w: w.value, h: d.value, wingWidthMm: props.item.wingWidthMm, furnitureTemplateId: deskTemplateId.value }, template.value?.kind || ''))
const desktopDepth = computed(() => seatGeometry.value?.desktopDepth ?? Math.min(d.value * 0.48, 650))
const innerRadius = computed(() => Math.min(wing.value * 0.9, desktopDepth.value * 0.9))
const seatX = computed(() => seatGeometry.value?.cx ?? 0)
const seatY = computed(() => seatGeometry.value?.cy ?? 0)
const seatRx = computed(() => (seatGeometry.value?.width ?? 0) / 2)
const seatRy = computed(() => (seatGeometry.value?.depth ?? 0) / 2)
const isReferenceCabinet = computed(() => ['cabinet-low', 'cabinet-short', 'cabinet-high'].includes(props.item.templateId))
const cabinetModuleCount = computed(() => Math.max(1, Math.round(w.value / 400)))
</script>

<template>
  <g v-if="template" :transform="transform" class="manual-furniture-glyph" :class="{ selected, invalid }" :style="{ strokeWidth: (selected ? 2 : 1.3) * (strokeScale ?? 1) }">
    <template v-if="ring">
      <path :d="ring.body" fill-rule="evenodd" />
      <path :d="ring.seams" fill="none" />
    </template>
    <g v-else-if="tableLinework" :data-table-detail="referenceCode || item.templateId" :transform="`scale(${w / 100} ${d / 100})`">
      <path :d="tableLinework.outline || referencePaths?.[0]" />
      <path v-if="tableLinework.support" class="table-support" :d="tableLinework.support" />
      <path class="table-feature" :d="tableLinework.feature" />
    </g>
    <g v-else-if="referencePaths" :data-reference-furniture="referenceCode" :transform="`scale(${w / 100} ${d / 100})`">
      <rect width="100" height="100" fill="transparent" stroke="none" />
      <path v-for="(path, index) in referencePaths" :key="index" :d="path" fill="none" />
    </g>
    <template v-else-if="isReferenceStraightDesk">
      <rect x="0" y="0" :width="w" :height="d" />
      <rect v-if="rightPanel" class="reference-guide" :x="w - panelWidth" y="0" :width="panelWidth" :height="d" />
      <rect v-if="leftPanel" class="reference-guide" x="0" y="0" :width="panelWidth" :height="d" />
      <g v-if="showSeat" class="layout-seat" fill="none">
        <ellipse :cx="w * 0.39" :cy="d + 290" :rx="Math.min(250, w * 0.21)" ry="220" />
        <path :d="`M ${w * 0.39 - Math.min(250, w * 0.21) * 0.7} ${d + 290 + 220 * 0.45} Q ${w * 0.39} ${d + 290 + 220} ${w * 0.39 + Math.min(250, w * 0.21) * 0.7} ${d + 290 + 220 * 0.45}`" />
      </g>
    </template>
    <template v-else-if="isReferenceLDesk">
      <path
        v-if="template.kind === 'rounded-l-desk'"
        :d="`M 0 0 H ${w} V ${d} H ${w - wing} V ${desktopDepth + innerRadius} Q ${w - wing} ${desktopDepth} ${w - wing - innerRadius} ${desktopDepth} H 0 Z`"
      />
      <path v-else :d="`M 0 0 H ${w} V ${d} H ${w - wing} V ${desktopDepth} H 0 Z`" />
      <rect v-if="rightPanel" class="reference-guide" :x="w - panelWidth" y="0" :width="panelWidth" :height="desktopDepth" />
      <rect v-if="leftPanel" class="reference-guide" x="0" y="0" :width="panelWidth" :height="desktopDepth" />
      <g v-if="showSeat" class="layout-seat" fill="none">
        <ellipse :cx="seatX" :cy="seatY" :rx="seatRx" :ry="seatRy" />
        <path :d="`M ${seatX - seatRx * 0.7} ${seatY + seatRy * 0.45} Q ${seatX} ${seatY + seatRy} ${seatX + seatRx * 0.7} ${seatY + seatRy * 0.45}`" />
      </g>
    </template>
    <template v-else-if="template.kind === 'l-desk'">
      <path :d="`M 0 0 H ${w} V ${wing} H ${wing} V ${d} H 0 Z`" />
      <line :x1="wing" :y1="wing * 0.45" :x2="w - wing * 0.3" :y2="wing * 0.45" />
    </template>
    <template v-else-if="template.kind === 'u-desk'">
      <path :d="`M 0 0 H ${w} V ${d} H ${w - wing} V ${wing} H ${wing} V ${d} H 0 Z`" />
      <line :x1="wing" :y1="wing * 0.45" :x2="w - wing" :y2="wing * 0.45" />
    </template>
    <template v-else-if="template.kind === 'chair'">
      <g :transform="`scale(${w / 100} ${d / 100})`" :data-chair-glyph="template.id">
        <template v-if="template.id === 'chair-office'">
          <path d="M 50 52 L 50 8 M 50 52 L 5 45 M 50 52 L 18 90 M 50 52 L 82 90 M 50 52 L 95 45" fill="none" />
          <rect x="22" y="21" width="56" height="54" rx="11" />
          <path d="M 23 65 Q 23 82 50 85 Q 77 82 77 65" />
          <path d="M 14 33 V 62 M 86 33 V 62" fill="none" stroke-linecap="round" />
          <circle v-for="[x, y] in [[50, 8], [5, 45], [18, 90], [82, 90], [95, 45]]" :key="x" :cx="x" :cy="y" r="3" />
        </template>
        <template v-else-if="template.id === 'chair-meeting'">
          <path d="M 23 17 H 77 L 82 70 H 18 Z" />
          <path d="M 23 70 L 19 83 M 77 70 L 81 83" fill="none" />
          <rect x="17" y="73" width="66" height="12" rx="3" />
        </template>
        <template v-else-if="template.id === 'chair-visitor'">
          <path d="M 12 71 V 24 Q 12 13 23 13 H 77 Q 88 13 88 24 V 71" fill="none" />
          <rect x="24" y="20" width="52" height="51" rx="5" />
          <path d="M 23 66 Q 50 73 77 66 V 82 Q 50 92 23 82 Z" />
        </template>
        <template v-else-if="template.id === 'chair-lounge'">
          <path d="M 7 36 Q 7 14 30 13 Q 50 21 70 13 Q 93 14 93 36 V 65 Q 93 90 68 92 H 32 Q 7 90 7 65 Z" />
          <path d="M 22 32 Q 50 24 78 32 V 62 Q 78 77 50 77 Q 22 77 22 62 Z" />
          <path d="M 10 48 L 22 44 M 90 48 L 78 44" fill="none" />
        </template>
        <template v-else-if="template.id === 'chair-bar'">
          <circle cx="50" cy="50" r="41" />
          <path d="M 20 24 L 80 76 M 80 24 L 20 76" fill="none" />
          <circle cx="50" cy="50" r="29" />
        </template>
        <template v-else-if="template.id === 'chair-executive'">
          <path d="M 50 49 L 50 5 M 50 49 L 7 52 M 50 49 L 20 94 M 50 49 L 80 94 M 50 49 L 93 52" fill="none" />
          <path d="M 20 20 Q 50 10 80 20 L 75 57 H 25 Z" />
          <path d="M 23 53 L 18 75 Q 34 87 50 81 Q 66 87 82 75 L 77 53 Z" />
          <path d="M 29 73 Q 50 65 71 73" fill="none" />
          <rect x="31" y="77" width="38" height="13" rx="6" />
          <rect x="7" y="33" width="12" height="32" rx="5" />
          <rect x="81" y="33" width="12" height="32" rx="5" />
        </template>
        <rect v-else x="15" y="15" width="70" height="70" rx="12" />
      </g>
    </template>
    <template v-else-if="template.kind === 'round-table' || template.kind === 'plant'">
      <ellipse :cx="w / 2" :cy="d / 2" :rx="w / 2" :ry="d / 2" />
      <ellipse v-if="template.kind === 'plant'" :cx="w / 2" :cy="d / 2" :rx="w * 0.22" :ry="d * 0.22" />
      <line v-if="template.kind === 'plant'" :x1="w * 0.23" :y1="d * 0.3" :x2="w * 0.77" :y2="d * 0.7" />
    </template>
    <template v-else-if="template.kind === 'oval-table'">
      <ellipse :cx="w / 2" :cy="d / 2" :rx="w / 2" :ry="d / 2" />
      <rect :x="w * 0.43" :y="d * 0.45" :width="w * 0.14" :height="d * 0.1" rx="10" />
    </template>
    <template v-else-if="template.kind === 'sofa'">
      <rect :x="0" :y="0" :width="w" :height="d" :rx="Math.min(w, d) * 0.12" />
      <line :x1="w * 0.08" :y1="d * 0.76" :x2="w * 0.92" :y2="d * 0.76" />
      <line v-for="index in sofaSeats - 1" :key="index" :x1="w * index / sofaSeats" :y1="d * 0.12" :x2="w * index / sofaSeats" :y2="d * 0.76" />
      <line :x1="w * 0.08" :y1="d * 0.12" :x2="w * 0.08" :y2="d * 0.76" />
      <line :x1="w * 0.92" :y1="d * 0.12" :x2="w * 0.92" :y2="d * 0.76" />
    </template>
    <template v-else-if="template.kind === 'cabinet' && isReferenceCabinet">
      <rect x="0" y="0" :width="w" :height="d" />
      <line
        v-for="index in cabinetModuleCount - 1" :key="`divider-${index}`"
        :x1="w * index / cabinetModuleCount" y1="0" :x2="w * index / cabinetModuleCount" :y2="d"
      />
      <template v-for="index in cabinetModuleCount" :key="`module-${index}`">
        <path
          v-if="item.templateId === 'cabinet-low'"
          :d="`M ${w * (index - 1) / cabinetModuleCount} 0 L ${w * (index - 0.5) / cabinetModuleCount} ${d} L ${w * index / cabinetModuleCount} 0`"
        />
        <line
          v-else-if="item.templateId === 'cabinet-short'"
          :x1="w * (index - 1) / cabinetModuleCount" y1="0" :x2="w * index / cabinetModuleCount" :y2="d"
        />
        <path
          v-else
          :d="`M ${w * (index - 1) / cabinetModuleCount} 0 L ${w * index / cabinetModuleCount} ${d} M ${w * index / cabinetModuleCount} 0 L ${w * (index - 1) / cabinetModuleCount} ${d}`"
        />
      </template>
    </template>
    <template v-else>
      <rect :x="0" :y="0" :width="w" :height="d" :rx="template.kind === 'table' ? Math.min(w, d) * 0.06 : 0" />
      <template v-if="template.kind === 'desk' || template.kind === 'counter'">
        <line :x1="w * 0.05" :y1="d * 0.18" :x2="w * 0.95" :y2="d * 0.18" />
        <rect v-if="template.kind === 'desk'" :x="w * 0.43" :y="d * 0.35" :width="w * 0.14" :height="d * 0.16" rx="8" />
      </template>
      <template v-else-if="template.kind === 'cabinet'">
        <line :x1="w / 2" :y1="0" :x2="w / 2" :y2="d" />
        <line :x1="w * 0.18" :y1="d * 0.72" :x2="w * 0.31" :y2="d * 0.72" />
        <line :x1="w * 0.69" :y1="d * 0.72" :x2="w * 0.82" :y2="d * 0.72" />
      </template>
      <template v-else-if="template.kind === 'device'">
        <rect :x="w * 0.2" :y="d * 0.18" :width="w * 0.6" :height="d * 0.25" />
        <line :x1="w * 0.16" :y1="d * 0.7" :x2="w * 0.84" :y2="d * 0.7" />
      </template>
      <line v-else-if="template.kind === 'wall'" :x1="w * 0.08" :y1="d / 2" :x2="w * 0.92" :y2="d / 2" />
      <rect v-else-if="template.kind === 'table'" :x="w * 0.43" :y="d * 0.43" :width="w * 0.14" :height="d * 0.14" rx="8" />
    </template>
    <g v-if="showSeat && referenceCode === 'T15'" class="reference-guide" pointer-events="none">
      <circle v-for="(point, index) in [[w / 4, -300], [w * 3 / 4, -300], [w / 4, d + 300], [w * 3 / 4, d + 300], [-300, d / 2], [w + 300, d / 2]]" :key="index" :cx="point[0]" :cy="point[1]" :r="Math.min(230, w / 5, d / 3)" />
    </g>
  </g>
</template>

<style scoped>
.manual-furniture-glyph { fill: #fff; stroke: #78736d; stroke-width: 1.3; stroke-linejoin: round; vector-effect: non-scaling-stroke; cursor: move; }
.manual-furniture-glyph :is(rect, path, ellipse, line, circle) { vector-effect: non-scaling-stroke; }
.manual-furniture-glyph .reference-guide { fill: none; stroke-dasharray: 4 3; }
.manual-furniture-glyph .table-feature { fill: none; stroke-opacity: 0.85; stroke-linecap: round; }
.manual-furniture-glyph .table-support { fill: none; stroke-opacity: 0.6; stroke-dasharray: 3 2; }
.manual-furniture-glyph.selected { stroke: #306fcb; stroke-width: 2; }
.manual-furniture-glyph.invalid { stroke: #c45c4c; }
</style>
