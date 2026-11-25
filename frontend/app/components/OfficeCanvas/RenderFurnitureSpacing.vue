<script setup lang="ts">
import type { RenderFurnitureSpacing } from '~/types/renderPreview'

defineProps<{ spacing: RenderFurnitureSpacing }>()
</script>

<template>
  <div class="text-xs leading-relaxed">
    <p v-if="spacing.description" class="mb-1">{{ spacing.description }}</p>
    <p>{{ spacing.modules.length }} 个模块 · {{ spacing.adjacent.filter(pair => pair.touching).length }} 对相贴模块</p>
    <details class="my-1">
      <summary class="cursor-pointer">逐件查看最近距离</summary>
      <ul class="mt-1 space-y-1">
        <li v-for="item in spacing.modules" :key="item.referenceId">
          {{ item.referenceId }}
          <template v-if="item.nearest"> → {{ item.nearest.referenceId }} · 边到边 {{ item.nearest.edgeGapMm }} mm · 中心距 {{ item.nearest.centerDistanceMm }} mm</template>
          <template v-else> · 无相邻模块</template>
        </li>
      </ul>
    </details>
    <details v-if="spacing.adjacent.length">
      <summary class="cursor-pointer">查看对齐模块的边到边净距</summary>
      <ul class="mt-1 space-y-1">
        <li v-for="pair in spacing.adjacent" :key="pair.axis + ':' + pair.from + ':' + pair.to">
          {{ pair.from }} → {{ pair.to }} · {{ pair.axis === 'width' ? '并排' : '前后' }} · {{ pair.edgeGapMm }} mm{{ pair.touching ? '（贴合）' : '' }}
        </li>
      </ul>
    </details>
  </div>
</template>
