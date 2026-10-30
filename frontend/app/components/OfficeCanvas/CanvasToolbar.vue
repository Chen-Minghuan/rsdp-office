<script setup lang="ts">
import type { LayoutChannel } from '~/types/layoutPlanning'
import type { SpatialFacilityKind } from '~/types/spatial'
import { DEFAULT_CHANNEL_WIDTH_MM } from '~/utils/layoutChannels'
import { canvasZoneBounds } from '~/utils/officeCanvasView'
import { uid } from '~/utils/helpers'

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
  { key: 'note', icon: 'note', label: '便签' },
  { key: 'render', icon: 'reference', label: '效果预览' },
  { key: 'constraints', icon: 'door', label: '建筑标注' },
  { key: 'regions', icon: 'zone', label: '布置区' },
  { key: 'facilities', icon: 'tag', label: '设施标注' },
  { key: 'channels', icon: 'merge', label: '通道' },
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
const constraintsOpen = ref(false)
const regionsOpen = ref(false)
const facilitiesOpen = ref(false)
function openConstraints() {
  constraintsOpen.value = !constraintsOpen.value
  regionsOpen.value = false
  facilitiesOpen.value = false
  channelsOpen.value = false
}
/** 弹出层互斥：同一时间只展开一个工具面板。 */
function togglePopover(which: 'regions' | 'facilities' | 'channels') {
  const target = which === 'regions' ? regionsOpen : which === 'facilities' ? facilitiesOpen : channelsOpen
  target.value = !target.value
  if (!target.value) return
  constraintsOpen.value = false
  if (which !== 'regions') regionsOpen.value = false
  if (which !== 'facilities') facilitiesOpen.value = false
  if (which !== 'channels') channelsOpen.value = false
}
function startConstraint(kind: 'COLUMN' | 'DOOR_CLEARANCE' | 'WINDOW' | 'NO_PLACEMENT') {
  tool.value = 'constraints-' + kind
  constraintsOpen.value = false
}
/** GEOM-016 布置区绘制入口：矩形拖绘或逐点多边形，绘制提交走 store.saveZoneLayoutRegion。 */
function startRegionTool(shape: 'rect' | 'poly') {
  tool.value = 'region-' + shape
  regionsOpen.value = false
}
/** GEOM-018 设施标注入口：点型单击、线型逐点、占用型拖绘矩形。 */
function startFacilityTool(kind: SpatialFacilityKind) {
  tool.value = 'facility-' + kind
  facilitiesOpen.value = false
}
const facilityTools: Array<{ kind: SpatialFacilityKind; label: string; hint: string }> = [
  { kind: 'FIRE_FACILITY', label: '消防设施', hint: '拖出占地矩形，可另标注操作净空' },
  { kind: 'ELECTRICAL_BOX', label: '电箱', hint: '拖出占地矩形，可另标注操作净空' },
  { kind: 'SKIRTING', label: '踢脚线', hint: '沿墙逐点绘制，Enter 或双击结束' },
  { kind: 'POWER_OUTLET', label: '电源点位', hint: '单击放置' },
  { kind: 'DATA_OUTLET', label: '弱电点位', hint: '单击放置' },
]
const channelsOpen = ref(false)
const zone = computed(() => store.zones.find(item => item.id === store.designZoneId) || null)
const channels = computed<LayoutChannel[]>(() => zone.value?.layoutPreferences?.channels || [])
const zoneBounds = computed(() => zone.value ? canvasZoneBounds(zone.value) : { minX: 0, minY: 0 })
watch(() => store.designZoneId, () => { channelsOpen.value = false; constraintsOpen.value = false; regionsOpen.value = false; facilitiesOpen.value = false })
function saveChannels(next: LayoutChannel[]) {
  if (zone.value) store.updateZoneChannels(zone.value.id, next)
}
function addChannel(orientation: LayoutChannel['orientation']) {
  if (!zone.value) return
  const center = orientation === 'vertical' ? (zoneBounds.value.minX + zoneBounds.value.maxX) / 2 : (zoneBounds.value.minY + zoneBounds.value.maxY) / 2
  saveChannels([...channels.value, { id: uid('ch'), orientation, offsetMm: Math.round(center - DEFAULT_CHANNEL_WIDTH_MM / 2), widthMm: DEFAULT_CHANNEL_WIDTH_MM }])
}
function updateChannel(id: string, patch: Partial<LayoutChannel>) {
  saveChannels(channels.value.map(channel => channel.id === id ? { ...channel, ...patch } : channel))
}
function removeChannel(id: string) {
  saveChannels(channels.value.filter(channel => channel.id !== id))
}
</script>

<template>
  <div class="relative h-14 flex items-center justify-between border-b border-line px-2.5 flex-none bg-white">
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
          :data-testid="t.key === 'constraints' ? 'toolbar-constraints' : t.key === 'channels' ? 'toolbar-channels' : t.key === 'regions' ? 'toolbar-regions' : t.key === 'facilities' ? 'toolbar-facilities' : undefined"
          :aria-expanded="t.key === 'constraints' ? constraintsOpen : t.key === 'channels' ? channelsOpen : t.key === 'regions' ? regionsOpen : t.key === 'facilities' ? facilitiesOpen : undefined"
          @click="t.key === 'render' ? emit('render') : t.key === 'constraints' ? openConstraints() : t.key === 'channels' ? togglePopover('channels') : t.key === 'regions' ? togglePopover('regions') : t.key === 'facilities' ? togglePopover('facilities') : tool = t.key"
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
    <div v-if="constraintsOpen && zone" class="absolute left-48 top-full z-30 w-40 border border-line bg-white p-2 text-[11px] text-[#5f5b56]" data-testid="constraint-popover" @click.stop>
      <strong class="block px-1 pb-1.5 text-xs text-[#4f493f]">建筑标注</strong>
      <button class="w-full px-2 py-1.5 text-left hover:bg-[#f7f3ef]" data-testid="constraint-tool-COLUMN" @click="startConstraint('COLUMN')">标柱</button>
      <button class="w-full px-2 py-1.5 text-left hover:bg-[#f7f3ef]" data-testid="constraint-tool-DOOR_CLEARANCE" @click="startConstraint('DOOR_CLEARANCE')">门洞净空</button>
      <button class="w-full px-2 py-1.5 text-left hover:bg-[#f7f3ef]" data-testid="constraint-tool-WINDOW" @click="startConstraint('WINDOW')">窗</button>
      <button class="w-full px-2 py-1.5 text-left hover:bg-[#f7f3ef]" data-testid="constraint-tool-NO_PLACEMENT" @click="startConstraint('NO_PLACEMENT')">禁放区</button>
      <p class="px-2 pt-1 text-[9px] leading-relaxed text-muted">在当前空间底图上拖出标注，Esc 取消。</p>
    </div>
    <div v-if="regionsOpen && zone" class="absolute left-56 top-full z-30 w-52 border border-line bg-white p-2 text-[11px] text-[#5f5b56]" data-testid="region-popover" @click.stop>
      <strong class="block px-1 pb-1.5 text-xs text-[#4f493f]">家具布置区</strong>
      <button class="w-full px-2 py-1.5 text-left hover:bg-[#f7f3ef]" data-testid="region-tool-rect" @click="startRegionTool('rect')">矩形布置区</button>
      <button class="w-full px-2 py-1.5 text-left hover:bg-[#f7f3ef]" data-testid="region-tool-poly" @click="startRegionTool('poly')">多边形布置区</button>
      <p class="px-2 pt-1 text-[9px] leading-relaxed text-muted">启用中的布置区限制家具可排范围（并集 ∩ 空间轮廓）；没有启用区时维持全房间可排。多边形逐点绘制，Enter 或双击结束，Esc 取消。列表与开关在画布左侧面板。</p>
    </div>
    <div v-if="facilitiesOpen && zone" class="absolute left-64 top-full z-30 w-56 border border-line bg-white p-2 text-[11px] text-[#5f5b56]" data-testid="facility-popover" @click.stop>
      <strong class="block px-1 pb-1.5 text-xs text-[#4f493f]">设施 / 点位标注</strong>
      <button v-for="item in facilityTools" :key="item.kind" class="w-full px-2 py-1.5 text-left hover:bg-[#f7f3ef]" :data-testid="'facility-tool-' + item.kind" @click="startFacilityTool(item.kind)">
        {{ item.label }}<span class="block text-[9px] text-muted">{{ item.hint }}</span>
      </button>
      <p class="px-2 pt-1 text-[9px] leading-relaxed text-muted">设施只提示不阻断排版、不扣净面积；操作净空需人工标注。需要实际禁放请用建筑标注的禁放区。</p>
    </div>
    <div v-if="channelsOpen && zone" class="absolute left-60 top-full z-30 max-h-[75vh] w-[380px] overflow-y-auto border border-line bg-white p-3 text-[11px] text-[#5f5b56]" data-testid="channel-popover" @click.stop>
      <div class="mb-2 flex items-center justify-between">
        <strong class="text-xs text-[#4f493f]">通道设置 · {{ channels.length }}</strong>
        <button class="px-1 text-muted hover:text-[#4f493f]" aria-label="关闭通道设置" @click="channelsOpen = false"><AppIcon name="close" size="3.5" /></button>
      </div>
      <p class="mb-2 text-[10px] leading-relaxed text-muted">通道是排版约束，自动排版与确认都会避让。位置可直接在画布上拖动，宽度与净距均为策略值。</p>
      <div v-for="channel in channels" :key="channel.id" class="mt-2 rounded-none border border-line p-2" :data-testid="'channel-' + channel.orientation">
        <div class="flex items-center justify-between text-[10px] font-semibold">
          <span>{{ channel.orientation === 'vertical' ? '纵向通道' : '横向通道' }}</span>
          <button class="font-normal text-[#8c633d] underline" :aria-label="'移除' + (channel.orientation === 'vertical' ? '纵向' : '横向') + '通道'" @click="removeChannel(channel.id)">移除</button>
        </div>
        <div class="mt-1 grid grid-cols-2 gap-1 text-[10px] text-muted">
          <label>宽度 mm<input :value="channel.widthMm" type="number" min="1" step="100" aria-label="通道宽度（毫米）" class="w-full border border-line bg-white px-1" @change="updateChannel(channel.id, { widthMm: Math.max(1, Number(($event.target as HTMLInputElement).value) || channel.widthMm) })"></label>
          <label>{{ channel.orientation === 'vertical' ? '距左' : '距上' }} mm<input :value="Math.round(channel.offsetMm - (channel.orientation === 'vertical' ? zoneBounds.minX : zoneBounds.minY))" type="number" step="100" aria-label="通道位置（毫米）" class="w-full border border-line bg-white px-1" @change="updateChannel(channel.id, { offsetMm: Number(($event.target as HTMLInputElement).value) + (channel.orientation === 'vertical' ? zoneBounds.minX : zoneBounds.minY) })"></label>
          <label>起点 mm（{{ channel.orientation === 'vertical' ? '距上' : '距左' }}，空=贯通）<input :value="channel.startMm === undefined ? '' : Math.round(channel.startMm - (channel.orientation === 'vertical' ? zoneBounds.minY : zoneBounds.minX))" type="number" step="100" aria-label="通道起点（毫米）" class="w-full border border-line bg-white px-1" @change="updateChannel(channel.id, { startMm: ($event.target as HTMLInputElement).value === '' ? undefined : Number(($event.target as HTMLInputElement).value) + (channel.orientation === 'vertical' ? zoneBounds.minY : zoneBounds.minX) })"></label>
          <label>终点 mm（空=贯通）<input :value="channel.endMm === undefined ? '' : Math.round(channel.endMm - (channel.orientation === 'vertical' ? zoneBounds.minY : zoneBounds.minX))" type="number" step="100" aria-label="通道终点（毫米）" class="w-full border border-line bg-white px-1" @change="updateChannel(channel.id, { endMm: ($event.target as HTMLInputElement).value === '' ? undefined : Number(($event.target as HTMLInputElement).value) + (channel.orientation === 'vertical' ? zoneBounds.minY : zoneBounds.minX) })"></label>
        </div>
        <label class="mt-1 block text-[10px] text-muted">两侧净距 mm<input :value="channel.clearanceMm || 0" type="number" min="0" step="50" aria-label="通道两侧净距（毫米）" class="w-full border border-line bg-white px-1" @change="updateChannel(channel.id, { clearanceMm: Math.max(0, Number(($event.target as HTMLInputElement).value) || 0) })"></label>
        <label v-if="channel.orientation === 'vertical'" class="mt-1 block text-[10px] text-muted"><input :checked="!!channel.fromSecondRow" type="checkbox" aria-label="仅从二排起" @change="updateChannel(channel.id, { fromSecondRow: ($event.target as HTMLInputElement).checked })"> 仅从二排起（首排桌列不断开）</label>
      </div>
      <div class="mt-2 flex gap-3">
        <button class="text-[#5d7f4c] underline" data-testid="add-channel-vertical" @click="addChannel('vertical')">添加纵向通道</button>
        <button class="text-[#5d7f4c] underline" data-testid="add-channel-horizontal" @click="addChannel('horizontal')">添加横向通道</button>
      </div>
    </div>
  </div>
</template>
