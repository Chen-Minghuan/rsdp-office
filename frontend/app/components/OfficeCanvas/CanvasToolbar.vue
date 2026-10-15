<script setup lang="ts">
const store = useOfficeStore()
const tool = defineModel<string>('tool', { default: 'select' })
const mode = defineModel<'overview' | 'design'>('mode', { default: 'overview' })

const overviewTools = [
  { key: 'select', icon: 'pencil', label: '选择' },
  { key: 'pan', icon: 'hand', label: '拖动' },
  { key: 'zone', icon: 'zone', label: '区域' },
  { key: 'note', icon: 'note', label: '便签' },
  { key: 'arrow', icon: 'arrow', label: '箭头' },
  { key: 'reference', icon: 'reference', label: '参考图' },
]

const designTools = [
  { key: 'select', icon: 'pencil', label: '选择' },
  { key: 'pan', icon: 'hand', label: '拖动' },
  { key: 'note', icon: 'note', label: '标注' },
  { key: 'reference', icon: 'reference', label: '效果图' },
]

const tools = computed(() => mode.value === 'design' ? designTools : overviewTools)

const emit = defineEmits<{
  undo: []
  zoomIn: []
  zoomOut: []
  fit: []
}>()

watch(tools, () => {
  // Reset tool if not available in current mode
  if (!tools.value.some(t => t.key === tool.value)) {
    tool.value = 'select'
  }
})
</script>

<template>
  <div class="h-14 flex items-center justify-between border-b border-line px-2.5 flex-none bg-white">
    <div class="flex items-center gap-2">
      <span class="text-[10px] px-1.5 py-0.5 rounded bg-[#f4eee5] text-[#946c45]">
        {{ mode === 'design' ? '单空间设计' : '空间总览' }}
      </span>
      <div class="flex gap-1">
        <button
          v-for="t in tools"
          :key="t.key"
          :class="[
            'flex flex-col items-center gap-0.5 px-2.5 py-1.5 rounded-lg min-w-[52px] text-[10px] transition',
            tool === t.key ? 'text-[#976c43] bg-[#f4e9dc]' : 'text-[#746f69] hover:bg-[#f6f2ed]',
          ]"
          @click="tool = t.key"
        >
          <AppIcon :name="t.icon" size="4" />
          <span>{{ t.label }}</span>
        </button>
      </div>
    </div>
    <div class="flex gap-1 items-center text-sm">
      <button class="p-1.5 rounded-lg text-[11px] border border-line hover:bg-[#f7f3ef] flex items-center gap-1" title="撤销" @click="emit('undo')">
        <AppIcon name="undo" size="4" />
      </button>
      <button class="p-1.5 rounded-lg text-[11px] border border-line hover:bg-[#f7f3ef]" aria-label="缩小画布" @click="emit('zoomOut')">
        <AppIcon name="zoomOut" size="4" />
      </button>
      <span class="text-xs text-[#7e7770] min-w-[44px] text-center font-medium">{{ Math.round(store.zoom * 100) }}%</span>
      <button class="p-1.5 rounded-lg text-[11px] border border-line hover:bg-[#f7f3ef]" aria-label="放大画布" @click="emit('zoomIn')">
        <AppIcon name="zoomIn" size="4" />
      </button>
      <button class="px-2 py-1.5 rounded-lg text-[11px] border border-line hover:bg-[#f7f3ef] flex items-center gap-1" @click="emit('fit')">
        <AppIcon name="fit" size="4" /> 适屏
      </button>
    </div>
  </div>
</template>
