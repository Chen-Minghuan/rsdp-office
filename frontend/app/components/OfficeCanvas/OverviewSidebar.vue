<script setup lang="ts">
import { money } from '~/utils/helpers'
import type { ZoneObject } from '~/types/office'

const store = useOfficeStore()
const selectedId = defineModel<string | null>('selectedId')

const editingZoneId = ref<string | null>(null)
const editWidth = ref(0)
const editDepth = ref(0)
const editScale = ref(0)

function fileIcon(type: string) {
  if (type === 'PDF') return 'file'
  if (['PNG', 'JPG', 'JPEG', 'WEBP'].includes(type)) return 'photo'
  return 'cube'
}

function enterDesign(z: ZoneObject) {
  store.enterDesign(z.id)
}

function startEdit(z: ZoneObject) {
  editingZoneId.value = z.id
  editWidth.value = z.actualWidthMm ? Math.round(z.actualWidthMm) : Math.round(z.w * 20)
  editDepth.value = z.actualDepthMm ? Math.round(z.actualDepthMm) : Math.round(z.h * 20)
  editScale.value = z.pixelToMeter ? 1 / z.pixelToMeter : 50
}

function applyEdit(z: ZoneObject) {
  if (editWidth.value > 0 && editDepth.value > 0) {
    store.resizeZoneByMm(z.id, editWidth.value, editDepth.value)
  }
  if (editScale.value > 0) {
    store.updateObject(z.id, { pixelToMeter: 1 / editScale.value } as Partial<ZoneObject>)
  }
  editingZoneId.value = null
}

function cancelEdit() {
  editingZoneId.value = null
}

function calibrateByPrompt(z: ZoneObject) {
  const px = Number(prompt('请输入区域在图纸上的宽度（像素）：', String(Math.round(z.w))))
  const real = Number(prompt('请输入区域对应的实际宽度（米）：', String(z.calibratedWidth || '')))
  if (px > 0 && real > 0) {
    store.updateObject(z.id, {
      calibratedWidth: real,
      pixelToMeter: real / px,
      actualWidthMm: real * 1000,
    } as Partial<ZoneObject>)
  }
}
</script>

<template>
  <div class="w-64 flex-none bg-white border border-line rounded-2xl shadow-panel p-4 flex flex-col gap-4 overflow-hidden">
    <div class="flex items-center justify-between flex-none">
      <div class="flex items-center gap-2 text-[15px] font-bold">
        <AppIcon name="folder" size="4" />
        <span>项目与空间</span>
      </div>
      <span class="px-1.5 py-0.5 rounded text-[10px] bg-[#e6f5ee] text-green flex items-center gap-1">
        <AppIcon name="check" size="3" />
        <span>自动保存</span>
      </span>
    </div>

    <div class="bg-[#f9f7f4] rounded-xl p-3.5 text-xs">
      <strong class="block truncate text-sm mb-1">{{ store.project.name }}</strong>
      <div class="text-[11px] text-muted flex items-center gap-3">
        <span class="flex items-center gap-1"><AppIcon name="user" size="3" /> {{ store.project.headcount || '待定' }} 人</span>
        <span class="flex items-center gap-1"><AppIcon name="banknotes" size="3" /> {{ money(store.project.budget) }}</span>
      </div>
      <div class="text-[11px] text-muted mt-1 flex items-center gap-1"><AppIcon name="swatch" size="3" /> {{ store.project.style }}</div>
    </div>

    <div class="h-px bg-[#f0ece8]" />
    <div class="flex items-center justify-between flex-none">
      <strong class="text-xs">项目资料</strong>
      <NuxtLink to="/" class="text-bronze text-xs flex items-center gap-1 hover:underline">
        <AppIcon name="plus" size="3" />
        <span>添加</span>
      </NuxtLink>
    </div>
    <div class="flex flex-col gap-1.5 overflow-auto max-h-[120px]">
      <div v-for="f in store.files" :key="f.name" class="flex items-center gap-2 text-xs px-2 py-1.5 rounded-lg hover:bg-[#f9f7f4]">
        <AppIcon :name="fileIcon(f.type)" size="4" class="text-muted" />
        <span class="truncate">{{ f.name }}</span>
      </div>
      <div v-if="!store.files.length" class="text-xs text-muted">尚未添加文件</div>
    </div>

    <div class="h-px bg-[#f0ece8]" />
    <div class="flex items-center justify-between flex-none">
      <strong class="text-xs">办公功能区</strong>
      <span class="text-xs text-muted">{{ store.zoneCount }} 个</span>
    </div>
    <div class="flex flex-col gap-1.5 overflow-auto">
      <div
        v-for="z in store.zones"
        :key="z.id"
        :class="[
          'group flex flex-col gap-1 rounded-lg px-3 py-2.5 text-xs border transition',
          selectedId === z.id ? 'bg-[#f5eadf] border-[#e5d2bd] text-[#8c633d]' : 'bg-white border-line hover:bg-[#f8f3ef]',
          z.confirmed ? 'border-l-4 border-l-green' : 'border-l-4 border-l-[#e5a04c]',
        ]"
        @click="selectedId = z.id"
      >
        <div class="flex items-center justify-between">
          <span class="font-semibold flex items-center gap-1.5">
            <AppIcon name="zone" size="3" />
            {{ z.name }}
          </span>
          <span class="text-[10px] text-muted">{{ z.seats ? z.seats + '人' : '' }}</span>
        </div>
        <div class="text-[10px] text-muted flex items-center gap-2">
          <span>{{ Math.round(z.w) }}×{{ Math.round(z.h) }} px</span>
          <span v-if="z.actualWidthMm">≈ {{ (z.actualWidthMm / 1000).toFixed(2) }}m</span>
        </div>

        <!-- Confirm / edit controls -->
        <div v-if="editingZoneId !== z.id" class="flex gap-1.5 mt-1">
          <button
            v-if="!z.confirmed"
            class="flex-1 px-2 py-1 rounded text-[10px] bg-[#e6f5ee] text-green border border-[#d0ead9] hover:bg-[#d8f0e3]"
            @click.stop="store.confirmZone(z.id)"
          >
            确认空间
          </button>
          <button
            v-else
            class="flex-1 px-2 py-1 rounded text-[10px] bg-white text-[#78736d] border border-line hover:bg-[#fff0f0] hover:text-red-500"
            @click.stop="store.unconfirmZone(z.id)"
          >
            取消确认
          </button>
          <button class="flex-1 px-2 py-1 rounded text-[10px] bg-[#f7f0e8] text-bronze-dark border border-[#efe4d7]" @click.stop="startEdit(z)">
            编辑尺寸
          </button>
          <button class="flex-1 px-2 py-1 rounded text-[10px] bg-bronze text-white" @click.stop="enterDesign(z)">
            进入设计
          </button>
        </div>

        <!-- Inline dimension editor -->
        <div v-else class="mt-2 p-2 bg-[#faf8f5] rounded-lg border border-[#efe4d7] flex flex-col gap-2">
          <div class="flex gap-2">
            <label class="flex-1">
              <span class="text-[10px] text-muted">宽 (mm)</span>
              <input v-model.number="editWidth" type="number" min="1" class="w-full h-7 px-1.5 border border-line rounded text-xs">
            </label>
            <label class="flex-1">
              <span class="text-[10px] text-muted">深 (mm)</span>
              <input v-model.number="editDepth" type="number" min="1" class="w-full h-7 px-1.5 border border-line rounded text-xs">
            </label>
          </div>
          <div class="flex gap-2">
            <label class="flex-1">
              <span class="text-[10px] text-muted">比例 (px/米)</span>
              <input v-model.number="editScale" type="number" min="1" class="w-full h-7 px-1.5 border border-line rounded text-xs">
            </label>
          </div>
          <div class="flex gap-1.5">
            <button class="flex-1 px-2 py-1 rounded text-[10px] bg-bronze text-white" @click.stop="applyEdit(z)">应用</button>
            <button class="flex-1 px-2 py-1 rounded text-[10px] border border-line hover:bg-[#f7f3ef]" @click.stop="cancelEdit">取消</button>
          </div>
          <button class="text-[10px] text-bronze-dark underline text-left" @click.stop="calibrateByPrompt(z)">通过图纸像素校准比例</button>
        </div>
      </div>
      <div v-if="!store.zones.length" class="text-xs text-muted text-center py-4">
        暂无功能区，请在画布上圈选区域
      </div>
    </div>

    <OfficeCanvasLayers v-model="selectedId" class="flex-1 min-h-0" />

    <div class="mt-auto flex items-start gap-2 bg-[#f6f0e8] border border-[#efe5d7] rounded-lg p-3 text-[11px] text-[#8b7055] leading-relaxed">
      <AppIcon name="info" size="3.5" class="mt-0.5 flex-none" />
      <span>这里是<strong>空间总览</strong>。核对尺寸后点击「确认空间」，全部确认后即可提交需求。</span>
    </div>
  </div>
</template>
