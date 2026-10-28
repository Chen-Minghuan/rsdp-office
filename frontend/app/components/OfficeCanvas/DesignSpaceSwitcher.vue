<script setup lang="ts">
import type { ZoneObject } from '~/types/office'
import { ZONE_TYPES } from '~/utils/sample'

const store = useOfficeStore()
/** 用途区域标签（开放办公区/会议室等），未知类型回退原始值。 */
const zoneTypeLabel = (type: string | undefined) => ZONE_TYPES.find(([value]) => value === type)?.[1] || type || '未设置用途'
const props = withDefaults(defineProps<{ zoneId: string | null; mode?: 'review' | 'design' }>(), { mode: 'design' })
const emit = defineEmits<{ select: [zoneId: string] }>()

const spaces = computed(() => store.zones.map(zone => {
  const count = store.furnitureForZone(zone.id).length
  const confirmed = count > 0 && store.layoutConfirmedForZone(zone.id)
  return { id: zone.id, name: zone.name, confirmed, status: confirmed ? `已确认 · ${count} 件` : count ? `待确认 · ${count} 件` : '待排版' }
}))
const currentIndex = computed(() => spaces.value.findIndex(space => space.id === props.zoneId))
const completedCount = computed(() => spaces.value.filter(space => space.confirmed).length)
const showAllSpaces = ref(false)
const displayedZones = computed(() => showAllSpaces.value ? store.zones : store.zones.slice(0, 6))
watch(() => props.zoneId, id => {
  if (props.mode === 'review' && id && !store.zones.slice(0, 6).some(zone => zone.id === id)) showAllSpaces.value = true
}, { immediate: true })
const renamingId = ref<string | null>(null)
const renameDraft = ref('')

function move(offset: number) {
  const space = spaces.value[currentIndex.value + offset]
  if (space) emit('select', space.id)
}

function startRoomRename(zone: ZoneObject) {
  renamingId.value = zone.id
  renameDraft.value = zone.name
  nextTick(() => (document.querySelector('.review-space-rename-form input') as HTMLInputElement | null)?.focus())
}

function commitRoomRename(zoneId: string) {
  if (renamingId.value !== zoneId) return
  renamingId.value = null
  const zone = store.zones.find(item => item.id === zoneId), trimmed = renameDraft.value.trim()
  if (!zone || !trimmed || trimmed === zone.name) return
  store.updateObject(zone.id, { name: trimmed } as Partial<ZoneObject>)
}

function loadExample() {
  store.loadReviewExample()
  store.setActiveZone(store.zones[0]?.id || null)
  showAllSpaces.value = false
}
</script>

<template>
  <div data-testid="design-space-switcher" class="border-b border-line px-1 py-1">
    <div class="mb-0.5 flex items-center justify-between px-1">
      <span v-if="mode === 'review'" class="text-[10px] font-medium text-muted">识别空间 {{ store.zones.length }} 个</span>
      <span v-else class="text-[10px] font-medium text-muted">空间 {{ Math.max(0, currentIndex + 1) }} / {{ spaces.length }}</span>
      <div v-if="mode === 'design' && spaces.length > 1" class="flex items-center gap-0.5">
        <button type="button" aria-label="上一个空间" :disabled="currentIndex <= 0" class="rounded-none p-0.5 text-muted hover:bg-white disabled:cursor-not-allowed disabled:opacity-30" @click="move(-1)">
          <AppIcon name="arrowLeft" size="3" />
        </button>
        <button type="button" aria-label="下一个空间" :disabled="currentIndex < 0 || currentIndex >= spaces.length - 1" class="rounded-none p-0.5 text-muted hover:bg-white disabled:cursor-not-allowed disabled:opacity-30" @click="move(1)">
          <AppIcon name="arrowRight" size="3" />
        </button>
      </div>
    </div>

    <template v-if="mode === 'review'">
      <div class="grid gap-px">
        <div
          v-for="zone in displayedZones"
          :key="zone.id"
          class="flex items-center rounded-none transition"
          :class="zone.id === zoneId ? 'bg-cream font-semibold text-bronze-dark' : 'text-[#5f5b56] hover:bg-cream'"
        >
          <form v-if="renamingId === zone.id" class="review-space-rename-form min-w-0 flex-1" @submit.prevent="commitRoomRename(zone.id)">
            <input v-model="renameDraft" class="mx-1 w-[calc(100%-0.5rem)] rounded-none border border-bronze bg-white px-1.5 py-1 text-[11px]" :aria-label="'重命名空间 ' + zone.name" maxlength="40" @blur="commitRoomRename(zone.id)" @keydown.esc.prevent="renamingId = null">
          </form>
          <button
            v-else
            type="button"
            :data-review-space-id="zone.id"
            class="min-w-0 flex-1 rounded-none px-1.5 py-1 text-left"
            :aria-pressed="zone.id === zoneId"
            :aria-label="'选择空间 ' + zone.name"
            title="单击选择空间，双击修改名称"
            @click="emit('select', zone.id)"
            @dblclick.stop.prevent="startRoomRename(zone)"
          >
            <span class="flex items-center gap-1">
              <AppIcon name="zone" size="3" class="shrink-0" />
              <span class="min-w-0 flex-1 truncate text-[11px]">{{ zone.name }}</span>
              <span class="shrink-0 truncate text-[9px] font-normal text-muted">{{ zoneTypeLabel(zone.zoneType) }}</span>
            </span>
          </button>
          <button v-if="renamingId !== zone.id" type="button" class="mr-1 shrink-0 rounded-none px-1 py-0.5 text-[9px] font-normal text-muted hover:bg-white" :aria-label="'删除空间 ' + zone.name" title="删除空间（选中后也可按 Delete 键）" @click="store.deleteZone(zone.id)">删除</button>
        </div>
      </div>
      <button v-if="store.zones.length > 6" class="show-more-rooms" @click="showAllSpaces = !showAllSpaces">{{ showAllSpaces ? '收起空间列表 ↑' : '查看其余 ' + (store.zones.length - 6) + ' 个空间 ↓' }}</button>
      <p v-if="!store.zones.length" class="inspector-hint">暂无空间：上传图纸识别，或在画布上手动画区。</p>
      <div class="review-project-note"><p>✓ 底图与轮廓共用同一坐标变换</p><p>✓ 原始识别与编辑副本分别保留</p></div>
      <button class="review-example-button" @click="loadExample">加载工装示例（可撤销）</button>
    </template>

    <template v-else>
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
    </template>
  </div>
</template>
