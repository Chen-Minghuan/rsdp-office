<script setup lang="ts">
import type { FurnitureObject } from '~/types/office'

const store = useOfficeStore()
const selectedId = defineModel<string | null>('selectedId')
const selectedFurnitureIds = defineModel<string[]>('selectedFurnitureIds', { default: () => [] })
const emit = defineEmits<{ (event: 'editSize', item: FurnitureObject, itemIds: string[], anchor: HTMLElement): void; (event: 'bulkReplace', item: FurnitureObject, itemIds: string[]): void }>()

type FurnitureDisplayGroup = { representative: FurnitureObject; items: FurnitureObject[] }

const zoneId = computed(() => store.designZoneId)
const items = computed(() => zoneId.value ? store.furnitureForZone(zoneId.value) : [])
function groupKey(item: FurnitureObject) {
  return [
    item.label,
    item.w,
    item.h,
    item.furnitureTemplateId || '',
    item.heightMm ?? '',
    JSON.stringify(item.moduleSnapshot || null),
  ].join('\u0000')
}

const groups = computed<FurnitureDisplayGroup[]>(() => {
  const result: FurnitureDisplayGroup[] = []
  for (const item of items.value) {
    const key = groupKey(item)
    const group = result.find(entry => groupKey(entry.representative) === key)
    if (group) group.items.push(item)
    else result.push({ representative: item, items: [item] })
  }
  return result
})

const allSelectedIds = computed(() => new Set(selectedFurnitureIds.value))
const isGroupFullySelected = (group: FurnitureDisplayGroup) => group.items.length > 0 && group.items.every(item => allSelectedIds.value.has(item.id))

function selectGroup(group: FurnitureDisplayGroup) {
  selectedFurnitureIds.value = group.items.map(item => item.id)
  selectedId.value = group.representative.id
}

function onItemClick(group: FurnitureDisplayGroup) {
  if (!isGroupFullySelected(group)) {
    selectGroup(group)
    return
  }
  const groupIds = new Set(group.items.map(item => item.id))
  selectedFurnitureIds.value = selectedFurnitureIds.value.filter(id => !groupIds.has(id))
  if (selectedId.value && groupIds.has(selectedId.value)) {
    selectedId.value = selectedFurnitureIds.value.length === 1 ? selectedFurnitureIds.value[0]! : null
  }
}

function onItemDoubleClick(event: MouseEvent, group: FurnitureDisplayGroup) {
  event.preventDefault()
  selectGroup(group)
  emit('bulkReplace', group.representative, group.items.map(item => item.id))
}

function onItemContextMenu(event: MouseEvent, group: FurnitureDisplayGroup) {
  event.preventDefault()
  selectGroup(group)
  emit('editSize', group.representative, group.items.map(item => item.id), event.currentTarget as HTMLElement)
}

function glyph(item: FurnitureObject) {
  return item.moduleSnapshot?.glyphTemplateId || item.furnitureTemplateId || 'desk-straight'
}
</script>

<template>
  <section v-if="groups.length" class="flex-none border-t border-line bg-white px-3 py-2" data-testid="canvas-furniture-strip">
    <div class="flex items-center gap-3 overflow-x-auto pb-1">
      <div
        v-for="group in groups"
        :key="groupKey(group.representative)"
        :data-testid="'furniture-strip-item-' + group.representative.id"
        :class="[
          'group relative flex-shrink-0 flex cursor-pointer flex-col items-center rounded-none border bg-white p-2 transition hover:bg-[#f7f3ef]',
          isGroupFullySelected(group) ? 'border-bronze ring-1 ring-bronze' : 'border-line',
        ]"
        style="min-width: 96px;"
        @click="onItemClick(group)"
        @dblclick="onItemDoubleClick($event, group)"
        @contextmenu.prevent="onItemContextMenu($event, group)"
      >
        <span class="absolute -right-1 -top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-bronze px-1 text-[9px] font-bold text-white">
          {{ group.items.length }}
        </span>
        <span class="block h-10 w-12 pointer-events-none">
          <OfficeCanvasFurniturePreview :template-id="glyph(group.representative)" :width-mm="group.representative.w" :depth-mm="group.representative.h" thumbnail />
        </span>
        <span class="mt-1 max-w-[84px] truncate text-[10px] text-muted">{{ group.representative.label }}</span>
        <span class="text-[8px] text-muted">{{ Math.round(group.representative.w) }}×{{ Math.round(group.representative.h) }}</span>
      </div>
    </div>
    <p class="mt-1 text-[10px] text-muted">单击选择/取消，双击替换选品，右键修改尺寸。</p>
  </section>
</template>
