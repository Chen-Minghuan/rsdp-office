<script setup lang="ts">
const store = useOfficeStore()
const props = defineProps<{ zoneId: string | null }>()
const emit = defineEmits<{ select: [zoneId: string] }>()

const spaces = computed(() => store.zones.map(zone => {
  const count = store.furnitureForZone(zone.id).length
  const confirmed = count > 0 && store.layoutConfirmedForZone(zone.id)
  return { id: zone.id, name: zone.name, confirmed, status: confirmed ? `已确认 · ${count} 件` : count ? `待确认 · ${count} 件` : '待排版' }
}))
const currentIndex = computed(() => spaces.value.findIndex(space => space.id === props.zoneId))
const completedCount = computed(() => spaces.value.filter(space => space.confirmed).length)

function move(offset: number) {
  const space = spaces.value[currentIndex.value + offset]
  if (space) emit('select', space.id)
}
</script>

<template>
  <div data-testid="design-space-switcher" class="flex-none flex flex-wrap items-center gap-3 rounded-xl border border-line bg-white px-4 py-3">
    <label for="design-space-select" class="text-xs font-semibold flex items-center gap-1.5">
      <AppIcon name="zone" size="4" /> 排版空间
    </label>
    <select
      id="design-space-select"
      :value="props.zoneId || ''"
      :disabled="spaces.length < 2"
      aria-label="选择排版空间"
      class="flex-1 min-w-0 max-w-sm rounded-lg border border-line bg-[#fdfbf8] px-3 py-2 text-sm font-semibold outline-none focus:border-bronze"
      @change="emit('select', ($event.target as HTMLSelectElement).value)"
    >
      <option v-if="currentIndex < 0" value="" disabled>请选择空间</option>
      <option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }} · {{ space.status }}</option>
    </select>
    <div class="flex items-center gap-2">
      <button type="button" aria-label="上一个空间" :disabled="currentIndex <= 0" class="rounded-lg border border-line p-2 hover:bg-cream disabled:opacity-30 disabled:cursor-not-allowed" @click="move(-1)">
        <AppIcon name="arrowLeft" size="4" />
      </button>
      <span class="text-xs text-muted tabular-nums">{{ Math.max(0, currentIndex + 1) }} / {{ spaces.length }}</span>
      <button type="button" aria-label="下一个空间" :disabled="currentIndex < 0 || currentIndex >= spaces.length - 1" class="rounded-lg border border-line p-2 hover:bg-cream disabled:opacity-30 disabled:cursor-not-allowed" @click="move(1)">
        <AppIcon name="arrowRight" size="4" />
      </button>
    </div>
    <span class="text-xs text-muted sm:ml-auto">已确认排版 {{ completedCount }} / {{ spaces.length }} 个空间</span>
  </div>
</template>
