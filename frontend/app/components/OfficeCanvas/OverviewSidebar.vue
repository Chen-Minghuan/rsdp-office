<script setup lang="ts">
import { money } from '~/utils/helpers'

const store = useOfficeStore()
const selectedId = defineModel<string | null>('selectedId')

function fileIcon(type: string) {
  if (type === 'PDF') return 'file'
  if (['PNG', 'JPG', 'JPEG', 'WEBP'].includes(type)) return 'photo'
  return 'cube'
}

function enterDesign(z: any) {
  store.enterDesign(z.id)
}

function calibrate(z: any) {
  const px = Number(prompt('请输入区域在图纸上的宽度（像素）：', String(z.w)))
  const real = Number(prompt('请输入区域对应的实际宽度（米）：', String(z.calibratedWidth || '')))
  if (px > 0 && real > 0) {
    store.updateObject(z.id, {
      calibratedWidth: real,
      pixelToMeter: real / px,
    } as any)
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
          <span v-if="z.calibratedWidth">≈ {{ z.calibratedWidth }}m</span>
        </div>
        <div class="flex gap-1.5 mt-1">
          <button class="flex-1 px-2 py-1 rounded text-[10px] bg-[#f7f0e8] text-bronze-dark border border-[#efe4d7]" @click.stop="calibrate(z)">
            校准尺寸
          </button>
          <button class="flex-1 px-2 py-1 rounded text-[10px] bg-bronze text-white" @click.stop="enterDesign(z)">
            进入设计
          </button>
        </div>
      </div>
      <div v-if="!store.zones.length" class="text-xs text-muted text-center py-4">
        暂无功能区，请在画布上圈选区域
      </div>
    </div>

    <OfficeCanvasLayers v-model="selectedId" class="flex-1 min-h-0" />

    <div class="mt-auto flex items-start gap-2 bg-[#f6f0e8] border border-[#efe5d7] rounded-lg p-3 text-[11px] text-[#8b7055] leading-relaxed">
      <AppIcon name="info" size="3.5" class="mt-0.5 flex-none" />
      <span>这里是<strong>空间总览</strong>。圈定功能区后可点击「进入设计」进行家具布局与选品。</span>
    </div>
  </div>
</template>
