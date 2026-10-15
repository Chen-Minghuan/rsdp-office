<script setup lang="ts">
import { estimateCapacity } from '~/utils/layout'
import type { Product } from '~/types/office'

const store = useOfficeStore()
const collapsed = ref(false)

const zone = computed(() => store.zones.find((z) => z.id === store.designZoneId) || null)

const modules = computed(() => {
  if (!zone.value) return []
  return store.products.filter((p) => p.uses.includes(zone.value!.zoneType)
    || (p.kind === 'other' && p.uses.length === 1 && p.uses[0] === 'OTHER'))
})

const selectedModule = ref<Product | null>(null)
const unitW = ref(0)
const unitD = ref(0)
const arrangement = ref<'row' | 'backToBack' | 'grid' | 'perimeter'>('row')

watch(modules, (list) => {
  if (!selectedModule.value || !list.find((p) => p.id === selectedModule.value!.id)) {
    selectedModule.value = list[0] || null
  }
}, { immediate: true })

watch(selectedModule, (p) => {
  if (p) {
    unitW.value = p.widthCm
    unitD.value = p.depthCm
  }
}, { immediate: true })

const scaleHint = computed(() => {
  if (!zone.value) return ''
  if (zone.value.pixelToMeter && zone.value.pixelToMeter > 0) {
    return `已按校准比例换算（${zone.value.pixelToMeter.toFixed(4)} m/px）`
  }
  return '未校准尺寸，按默认 50 px/米估算'
})

const capacity = computed(() => {
  if (!zone.value || !selectedModule.value) return 0
  return estimateCapacity(zone.value, unitW.value, unitD.value, arrangement.value)
})

function selectModule(p: Product) {
  selectedModule.value = p
}

function applyLayout() {
  if (!zone.value || !selectedModule.value) return
  store.layoutFurniture(zone.value.id, selectedModule.value.id, unitW.value, unitD.value, arrangement.value)
}

function clearLayout() {
  if (!zone.value) return
  store.clearFurniture(zone.value.id)
}

const arrangements = [
  { key: 'row', label: '单排' },
  { key: 'backToBack', label: '背靠背' },
  { key: 'grid', label: '矩阵' },
  { key: 'perimeter', label: '沿边' },
] as const
</script>

<template>
  <div :class="['flex-none bg-white border border-line rounded-2xl shadow-panel flex flex-col overflow-hidden transition-all duration-300', collapsed ? 'w-12' : 'w-72']">
    <div v-show="!collapsed" class="w-72 h-full flex flex-col">
      <!-- Header -->
      <div class="relative px-4 py-3 border-b border-line flex-none">
        <button class="absolute top-3 right-3 p-1 rounded hover:bg-[#f7f3ef] text-muted" title="收起侧边栏" @click="collapsed = true">
          <AppIcon name="arrowLeft" size="4" />
        </button>
        <div class="flex items-center gap-2 text-[15px] font-bold">
          <AppIcon name="cube" size="4" />
          <span>单位模块与排版</span>
        </div>
        <p class="text-[10px] text-muted mt-1">
          {{ zone ? zone.name : '未选择空间' }}
        </p>
      </div>

      <!-- No zone -->
      <div v-if="!zone" class="flex-1 flex flex-col items-center justify-center text-center p-6 text-xs text-muted">
        <AppIcon name="zone" size="8" class="text-[#ddd8d2] mb-2" />
        <p>请在总览中选择一个功能区后进入设计</p>
      </div>

      <template v-else>
        <!-- Module list -->
        <div class="px-3 py-3 border-b border-line flex-none">
          <div class="text-[11px] font-semibold text-[#5b5751] mb-2">单位模块</div>
          <div class="flex gap-2 overflow-x-auto pb-1">
            <button
              v-for="p in modules"
              :key="p.id"
              :class="[
                'flex-none w-[88px] p-2 rounded-xl border text-left transition',
                selectedModule?.id === p.id ? 'bg-[#f7f0e8] border-bronze' : 'bg-white border-line hover:bg-[#f9f7f4]',
              ]"
              @click="selectModule(p)"
            >
              <div class="w-12 h-10 mx-auto mb-1.5">
                <AgentProductArt :kind="p.kind" />
              </div>
              <div class="text-[10px] font-bold truncate">{{ p.name }}</div>
              <div class="text-[9px] text-muted">{{ p.widthCm }}×{{ p.depthCm }} cm</div>
            </button>
          </div>
        </div>

        <!-- Dimensions -->
        <div class="px-4 py-3 border-b border-line flex-none">
          <div class="text-[11px] font-semibold text-[#5b5751] mb-2">模块尺寸</div>
          <div class="flex gap-2">
            <label class="flex-1">
              <span class="text-[10px] text-muted">宽 (cm)</span>
              <input v-model.number="unitW" type="number" min="1" class="w-full h-8 px-2 border border-line rounded-lg text-xs outline-none focus:border-bronze">
            </label>
            <label class="flex-1">
              <span class="text-[10px] text-muted">深 (cm)</span>
              <input v-model.number="unitD" type="number" min="1" class="w-full h-8 px-2 border border-line rounded-lg text-xs outline-none focus:border-bronze">
            </label>
          </div>
          <p class="text-[9px] text-muted mt-1.5">{{ scaleHint }}</p>
        </div>

        <!-- Arrangement -->
        <div class="px-4 py-3 border-b border-line flex-none">
          <div class="text-[11px] font-semibold text-[#5b5751] mb-2">排列方式</div>
          <div class="grid grid-cols-2 gap-2">
            <button
              v-for="a in arrangements"
              :key="a.key"
              :class="[
                'px-2 py-1.5 rounded-lg text-[11px] border transition',
                arrangement === a.key ? 'bg-bronze text-white border-bronze' : 'bg-white border-line hover:bg-[#f9f7f4]',
              ]"
              @click="arrangement = a.key"
            >
              {{ a.label }}
            </button>
          </div>
        </div>

        <!-- Preview stats -->
        <div class="px-4 py-3 bg-[#f9f7f4] flex-none">
          <div class="flex items-center justify-between text-xs">
            <span class="text-[#78736d]">预计可摆数量</span>
            <strong class="text-bronze text-lg">{{ capacity }}</strong>
          </div>
        </div>

        <!-- Actions -->
        <div class="px-4 py-3 mt-auto border-t border-line flex flex-col gap-2 flex-none">
          <button
            class="w-full px-4 py-2.5 rounded-lg text-xs font-semibold text-white bg-bronze hover:bg-bronze-dark flex items-center justify-center gap-1.5 disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="!selectedModule || capacity <= 0"
            @click="applyLayout"
          >
            <AppIcon name="cube" size="3.5" />
            <span>一键排版</span>
          </button>
          <button
            class="w-full px-4 py-2 rounded-lg text-xs font-semibold border border-line hover:bg-[#fff0f0] hover:text-red-500 flex items-center justify-center gap-1.5"
            @click="clearLayout"
          >
            <AppIcon name="trash" size="3.5" />
            <span>清空当前排版</span>
          </button>
        </div>
      </template>
    </div>

    <!-- Collapsed strip -->
    <div v-show="collapsed" class="w-12 h-full flex flex-col items-center py-3">
      <button class="p-1.5 rounded-lg hover:bg-[#f7f3ef] text-muted mb-4" title="展开侧边栏" @click="collapsed = false">
        <AppIcon name="arrowRight" size="5" />
      </button>
      <div class="text-[10px] text-muted tracking-widest" style="writing-mode: vertical-rl;">
        排版
      </div>
    </div>
  </div>
</template>
