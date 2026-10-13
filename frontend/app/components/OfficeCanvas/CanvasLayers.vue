<script setup lang="ts">
import { TONES } from '~/utils/sample'

const store = useOfficeStore()
const selectedId = defineModel<string | null>('selectedId')

function dotColor(o: any) {
  if (o.kind === 'zone') return TONES[o.tone || 'blue'].stroke
  if (o.kind === 'note') return '#e9c66e'
  return '#b4aca2'
}

function iconFor(o: any) {
  if (o.kind === 'zone') return 'zone'
  if (o.kind === 'note') return 'note'
  return 'reference'
}

function label(o: any) {
  if (o.kind === 'zone') return o.name
  if (o.kind === 'note') return (o.text || '空便签').slice(0, 14)
  return o.caption || '办公参考图'
}
</script>

<template>
  <div class="flex flex-col gap-3 overflow-hidden">
    <div class="flex items-center justify-between">
      <strong class="text-xs">画布对象</strong>
      <span class="text-xs text-muted">{{ store.objects.length }} 项</span>
    </div>
    <div class="flex flex-col gap-1 overflow-auto">
      <button
        v-for="o in store.objects.filter((x) => x.kind !== 'arrow')"
        :key="o.id"
        :class="[
          'flex items-center justify-between px-2.5 py-2 rounded-lg text-xs cursor-pointer transition',
          selectedId === o.id ? 'bg-[#f8f1e9] text-bronze-dark' : 'hover:bg-[#f8f1e9] text-[#5b5751]',
        ]"
        @click="selectedId = o.id"
      >
        <span class="flex items-center overflow-hidden">
          <span class="w-2 h-2 rounded-[3px] mr-2 flex-none" :style="{ background: dotColor(o) }"></span>
          <AppIcon :name="iconFor(o)" size="3.5" class="mr-1.5 text-muted" />
          <span class="truncate">{{ label(o) }}</span>
        </span>
        <AppIcon name="pencil" size="3" class="text-muted" />
      </button>
      <div v-if="!store.objects.filter((x) => x.kind !== 'arrow').length" class="text-xs text-muted text-center py-4">
        暂无对象
      </div>
    </div>
  </div>
</template>
