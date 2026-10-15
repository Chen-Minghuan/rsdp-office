<script setup lang="ts">
const store = useManualCanvasStore()

onMounted(() => {
  store.init()
})

function downloadJson() {
  const blob = new Blob([JSON.stringify(store.layout, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `manual-canvas-${Date.now()}.json`
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1500)
}
</script>

<template>
  <div class="flex flex-col h-full gap-4">
    <div class="flex items-start justify-between flex-none">
      <div>
        <h1 class="text-[25px] font-bold tracking-tight mb-2">无限画布 · 手动布局</h1>
        <p class="text-xs text-muted">无界毫米坐标：绘制空间、编辑轮廓、划分布置区、摆放办公家具图例。</p>
      </div>
      <div class="flex items-center gap-2">
        <button class="inline-flex items-center gap-1 px-3 py-2 rounded-lg text-xs font-semibold border border-line hover:bg-[#f7f3ef]" @click="store.loadSample">
          加载示例
        </button>
        <button class="inline-flex items-center gap-1 px-3 py-2 rounded-lg text-xs font-semibold border border-line hover:bg-[#f7f3ef]" @click="downloadJson">
          导出 JSON
        </button>
      </div>
    </div>

    <div class="flex-1 min-h-0">
      <OfficeCanvasManualCanvas v-if="store.initialized" v-model="store.layout" />
    </div>
  </div>
</template>
