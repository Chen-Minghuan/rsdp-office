<script setup lang="ts">
import { ZONE_TYPES } from '~/utils/sample'
import type { OfficeObject } from '~/types/office'

const store = useOfficeStore()
const selectedId = defineModel<string | null>('selectedId')
const fileInput = ref<HTMLInputElement | null>(null)

const obj = computed(() => store.objects.find((o) => o.id === selectedId.value) || null)

function update(patch: Partial<OfficeObject>) {
  if (!selectedId.value) return
  store.updateObject(selectedId.value, patch)
}

function remove() {
  if (!selectedId.value) return
  store.deleteObject(selectedId.value)
  selectedId.value = null
}

function onImageChange(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file || !selectedId.value) return
  if (file.size > 1024 * 1024) {
    alert('演示版图片请控制在 1MB 内，正式版需使用对象存储')
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    update({ image: String(reader.result), caption: file.name.slice(0, 50) } as Partial<OfficeObject>)
  }
  reader.readAsDataURL(file)
}
</script>

<template>
  <div v-if="!obj" class="text-center py-8 text-xs text-[#958f87]">
    <AppIcon name="pencil" size="8" class="mx-auto mb-3 text-[#d8d3cd]" />
    点击画布中的功能区、便签或参考图，可以在这里编辑属性。
  </div>

  <div v-else-if="obj.kind === 'zone'" class="flex flex-col gap-3">
    <div class="flex items-center justify-between mb-1">
      <span class="text-sm font-bold flex items-center gap-2"><AppIcon name="zone" size="4" /> 区域属性</span>
      <span :class="['px-1.5 py-0.5 rounded text-[10px]', (obj as any).confirmed ? 'bg-[#e6f5ee] text-green' : 'bg-[#fff8e6] text-[#a67c2e]']">
        {{ (obj as any).confirmed ? '已确认' : '待确认' }}
      </span>
    </div>
    <div class="flex flex-col gap-1">
      <label class="text-[11px] text-[#746c64]">区域名称</label>
      <input :value="obj.name" class="h-9 px-2.5 border border-[#e9e4de] rounded-lg text-sm outline-none focus:border-[#be9369]" @input="update({ name: ($event.target as HTMLInputElement).value })">
    </div>
    <div class="flex flex-col gap-1">
      <label class="text-[11px] text-[#746c64]">功能类型</label>
      <select :value="obj.zoneType" class="h-9 px-2.5 border border-[#e9e4de] rounded-lg text-sm outline-none focus:border-[#be9369]" @change="update({ zoneType: ($event.target as HTMLSelectElement).value as any })">
        <option v-for="[val, label] in ZONE_TYPES" :key="val" :value="val">{{ label }}</option>
      </select>
    </div>
    <div class="flex flex-col gap-1">
      <label class="text-[11px] text-[#746c64]">目标人数 / 工位</label>
      <input :value="obj.seats" type="number" min="0" class="h-9 px-2.5 border border-[#e9e4de] rounded-lg text-sm outline-none focus:border-[#be9369]" @input="update({ seats: Math.max(0, Number(($event.target as HTMLInputElement).value) || 0) })">
    </div>
    <div class="flex flex-col gap-1">
      <label class="text-[11px] text-[#746c64]">区域说明</label>
      <textarea :value="obj.note" rows="3" class="w-full border border-[#e9e4de] rounded-lg p-2.5 text-sm outline-none focus:border-[#be9369] resize-none leading-relaxed" @input="update({ note: ($event.target as HTMLTextAreaElement).value })" />
    </div>

    <div class="bg-[#f9f7f4] rounded-xl p-3 flex flex-col gap-2">
      <div class="text-[11px] font-semibold text-[#5b5751]">实际尺寸</div>
      <div class="flex gap-2">
        <label class="flex-1">
          <span class="text-[10px] text-muted">宽 (mm)</span>
          <input
            :value="Math.round((obj as any).actualWidthMm || obj.w * 20)"
            type="number"
            min="1"
            class="w-full h-8 px-2 border border-line rounded-lg text-xs"
            @change="store.resizeZoneByMm(obj.id, Number(($event.target as HTMLInputElement).value), (obj as any).actualDepthMm || obj.h * 20)"
          >
        </label>
        <label class="flex-1">
          <span class="text-[10px] text-muted">深 (mm)</span>
          <input
            :value="Math.round((obj as any).actualDepthMm || obj.h * 20)"
            type="number"
            min="1"
            class="w-full h-8 px-2 border border-line rounded-lg text-xs"
            @change="store.resizeZoneByMm(obj.id, (obj as any).actualWidthMm || obj.w * 20, Number(($event.target as HTMLInputElement).value))"
          >
        </label>
      </div>
      <div class="text-[10px] text-muted">
        画布尺寸：{{ Math.round(obj.w) }}×{{ Math.round(obj.h) }} px
        <span v-if="(obj as any).pixelToMeter">，比例 {{ (1 / (obj as any).pixelToMeter).toFixed(1) }} px/米</span>
      </div>
    </div>

    <div class="flex gap-2">
      <button
        v-if="!(obj as any).confirmed"
        class="flex-1 px-4 py-2 rounded-lg text-xs font-semibold bg-[#e6f5ee] text-green border border-[#d0ead9] hover:bg-[#d8f0e3]"
        @click="store.confirmZone(obj.id)"
      >
        确认空间
      </button>
      <button
        v-else
        class="flex-1 px-4 py-2 rounded-lg text-xs font-semibold border border-line hover:bg-[#fff0f0] hover:text-red-500"
        @click="store.unconfirmZone(obj.id)"
      >
        取消确认
      </button>
    </div>

    <button class="mt-2 px-4 py-2 rounded-lg text-xs font-semibold border border-line hover:bg-[#fff0f0] hover:text-red-500 flex items-center justify-center gap-2 transition" @click="remove">
      <AppIcon name="trash" size="4" /> 删除该区域
    </button>
  </div>

  <div v-else-if="obj.kind === 'note'" class="flex flex-col gap-3">
    <div class="flex items-center justify-between mb-1">
      <span class="text-sm font-bold flex items-center gap-2"><AppIcon name="note" size="4" /> 便签内容</span>
      <span class="px-1.5 py-0.5 rounded text-[10px] bg-[#f4eee5] text-[#946c45]">用户说明</span>
    </div>
    <textarea :value="obj.text" rows="6" class="w-full border border-[#e9e4de] rounded-lg p-2.5 text-sm outline-none focus:border-[#be9369] resize-none leading-relaxed" @input="update({ text: ($event.target as HTMLTextAreaElement).value })" />
    <button class="mt-2 px-4 py-2 rounded-lg text-xs font-semibold border border-line hover:bg-[#fff0f0] hover:text-red-500 flex items-center justify-center gap-2 transition" @click="remove">
      <AppIcon name="trash" size="4" /> 删除便签
    </button>
  </div>

  <div v-else-if="obj.kind === 'reference'" class="flex flex-col gap-3">
    <div class="flex items-center justify-between mb-1">
      <span class="text-sm font-bold flex items-center gap-2"><AppIcon name="photo" size="4" /> 参考素材</span>
    </div>
    <div class="flex flex-col gap-1">
      <label class="text-[11px] text-[#746c64]">素材说明</label>
      <input :value="obj.caption" class="h-9 px-2.5 border border-[#e9e4de] rounded-lg text-sm outline-none focus:border-[#be9369]" @input="update({ caption: ($event.target as HTMLInputElement).value })">
    </div>
    <label class="mt-2 px-4 py-2 rounded-lg text-xs font-semibold text-bronze-dark bg-[#f7f0e8] border border-[#efe4d7] text-center cursor-pointer flex items-center justify-center gap-2 hover:bg-[#f3e8dc] transition">
      <AppIcon name="upload" size="4" /> 选择本地参考图片
      <input ref="fileInput" type="file" accept="image/png,image/jpeg,image/webp" hidden @change="onImageChange">
    </label>
    <button class="px-4 py-2 rounded-lg text-xs font-semibold border border-line hover:bg-[#fff0f0] hover:text-red-500 flex items-center justify-center gap-2 transition" @click="remove">
      <AppIcon name="trash" size="4" /> 删除参考图
    </button>
  </div>

  <div v-else-if="obj.kind === 'furniture'" class="flex flex-col gap-3">
    <div class="flex items-center justify-between mb-1">
      <span class="text-sm font-bold flex items-center gap-2"><AppIcon name="cube" size="4" /> 家具单元</span>
      <span class="px-1.5 py-0.5 rounded text-[10px] bg-[#f4eee5] text-[#946c45]">已摆放</span>
    </div>
    <div class="text-xs text-[#78736d]">
      产品：<strong class="text-[#383432]">{{ store.products.find((p) => p.id === (obj as any).productId)?.name || (obj as any).productId }}</strong>
    </div>
    <div class="flex flex-col gap-1">
      <label class="text-[11px] text-[#746c64]">宽 (px)</label>
      <input :value="obj.w" type="number" min="1" class="h-9 px-2.5 border border-[#e9e4de] rounded-lg text-sm outline-none focus:border-[#be9369]" @input="update({ w: Math.max(1, Number(($event.target as HTMLInputElement).value) || 0) })">
    </div>
    <div class="flex flex-col gap-1">
      <label class="text-[11px] text-[#746c64]">深 (px)</label>
      <input :value="obj.h" type="number" min="1" class="h-9 px-2.5 border border-[#e9e4de] rounded-lg text-sm outline-none focus:border-[#be9369]" @input="update({ h: Math.max(1, Number(($event.target as HTMLInputElement).value) || 0) })">
    </div>
    <button class="mt-2 px-4 py-2 rounded-lg text-xs font-semibold border border-line hover:bg-[#fff0f0] hover:text-red-500 flex items-center justify-center gap-2 transition" @click="remove">
      <AppIcon name="trash" size="4" /> 删除该家具
    </button>
  </div>
</template>
