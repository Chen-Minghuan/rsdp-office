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
  <div data-testid="design-space-switcher" class="border-b border-line px-1 py-1">
    <div class="mb-0.5 flex items-center justify-between px-1">
      <span class="text-[10px] font-medium text-muted">空间 {{ Math.max(0, currentIndex + 1) }} / {{ spaces.length }}</span>
      <div v-if="spaces.length > 1" class="flex items-center gap-0.5">
        <button type="button" aria-label="上一个空间" :disabled="currentIndex <= 0" class="rounded-none p-0.5 text-muted hover:bg-white disabled:cursor-not-allowed disabled:opacity-30" @click="move(-1)">
          <AppIcon name="arrowLeft" size="3" />
        </button>
        <button type="button" aria-label="下一个空间" :disabled="currentIndex < 0 || currentIndex >= spaces.length - 1" class="rounded-none p-0.5 text-muted hover:bg-white disabled:cursor-not-allowed disabled:opacity-30" @click="move(1)">
          <AppIcon name="arrowRight" size="3" />
        </button>
      </div>
    </div>
    <div v-if="spaces.length" class="grid gap-px">
      <button
        v-for="space in spaces"
        :key="space.id"
        type="button"
        :data-space-id="space.id"
        :aria-label="`选择排版空间 ${space.name}`"
        :aria-current="space.id === props.zoneId ? 'page' : undefined"
        :disabled="!store.canEnterLayout"
        :title="store.canEnterLayout ? `进入${space.name}排版` : '完成图纸校准后即可进入排版'"
        class="min-w-0 rounded-none px-1.5 py-1 text-left transition disabled:cursor-not-allowed disabled:opacity-50"
        :class="space.id === props.zoneId ? 'bg-cream font-semibold text-bronze-dark' : 'text-[#5f5b56] hover:bg-cream'"
        @click="emit('select', space.id)"
      >
        <span class="flex items-center gap-1">
          <AppIcon name="zone" size="3" class="shrink-0" />
          <span class="min-w-0 flex-1 truncate text-[11px]">{{ space.name }}</span>
          <span class="shrink-0 text-[9px] font-normal" :class="space.confirmed ? 'text-green' : 'text-muted'">{{ space.status }}</span>
        </span>
      </button>
    </div>
    <p v-else class="px-1.5 py-1 text-[10px] text-muted">当前楼层暂无空间</p>
    <p v-if="spaces.length" class="px-1.5 pt-0.5 text-[9px] text-muted">已确认排版 {{ completedCount }} / {{ spaces.length }} 个空间</p>
  </div>
</template>
