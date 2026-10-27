<script setup lang="ts">
const store = useOfficeStore()
const props = defineProps<{ viewRotation?: number }>()
const showDimensions = defineModel<boolean>('showDimensions', { default: true })
const snapEnabled = defineModel<boolean>('snapEnabled', { default: true })
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
  { key: 'render', icon: 'reference', label: '效果预览' },
]

const tools = computed(() => mode.value === 'design' ? designTools : overviewTools)

const emit = defineEmits<{
  undo: []
  zoomIn: []
  zoomOut: []
  fit: []
  render: []
  rotate: [degrees: number]
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
      <span class="text-[10px] px-1.5 py-0.5 rounded-none bg-[#f4eee5] text-[#946c45]">
        {{ mode === 'design' ? '单空间设计' : '空间总览' }}
      </span>
      <div class="flex gap-1">
        <button
          v-for="t in tools"
          :key="t.key"
          :class="[
            'flex flex-col items-center gap-0.5 px-2.5 py-1.5 rounded-none min-w-[52px] text-[10px] transition',
            tool === t.key ? 'text-[#976c43] bg-[#f4e9dc]' : 'text-[#746f69] hover:bg-[#f6f2ed]',
          ]"
          @click="t.key === 'render' ? emit('render') : tool = t.key"
        >
          <AppIcon :name="t.icon" size="4" />
          <span>{{ t.label }}</span>
        </button>
      </div>
    </div>
    <div class="flex gap-1 items-center text-sm">
      <template v-if="mode === 'design'">
        <button class="px-2 py-1.5 rounded-none text-[11px] border border-line" :aria-pressed="showDimensions" aria-label="显示参考尺寸" @click="showDimensions = !showDimensions">尺寸</button>
        <button class="px-2 py-1.5 rounded-none text-[11px] border border-line" :aria-pressed="snapEnabled" aria-label="家具吸附" title="拖动/放置家具时吸附到墙面和其他模块（50mm）" @click="snapEnabled = !snapEnabled">吸附</button>
        <button class="px-2 py-1.5 rounded-none text-[11px] border border-line" aria-label="旋转场景视图" title="顺时针旋转视图 90°，不改变排版" @click="emit('rotate', 90)">↻ {{ props.viewRotation || 0 }}°</button>
        <button v-if="props.viewRotation" class="px-2 py-1.5 rounded-none text-[11px] border border-line" aria-label="复位场景视图" @click="emit('rotate', -props.viewRotation)">复位</button>
      </template>
      <button class="p-1.5 rounded-none text-[11px] border border-line hover:bg-[#f7f3ef] flex items-center gap-1" title="撤销" @click="emit('undo')">
        <AppIcon name="undo" size="4" />
      </button>
      <button class="p-1.5 rounded-none text-[11px] border border-line hover:bg-[#f7f3ef]" aria-label="缩小画布" @click="emit('zoomOut')">
        <AppIcon name="zoomOut" size="4" />
      </button>
      <span class="text-xs text-[#7e7770] min-w-[44px] text-center font-medium">{{ Math.round(store.zoom * 100) }}%</span>
      <button class="p-1.5 rounded-none text-[11px] border border-line hover:bg-[#f7f3ef]" aria-label="放大画布" @click="emit('zoomIn')">
        <AppIcon name="zoomIn" size="4" />
      </button>
      <button class="px-2 py-1.5 rounded-none text-[11px] border border-line hover:bg-[#f7f3ef] flex items-center gap-1" @click="emit('fit')">
        <AppIcon name="fit" size="4" /> 适屏
      </button>
    </div>
  </div>
</template>
