<script setup lang="ts">
import { projectDrawingRoute } from '~/utils/projectBenchRoutes'

const workspace = useWorkspaceStore()

const projectName = computed(() => workspace.currentProject?.projectName || '当前项目')
const projectMeta = computed(() => {
  const floorCount = workspace.currentProject?.floors.length ?? 0
  return floorCount ? `${floorCount} 个楼层 · 办公空间设计` : '尚未规划楼层'
})
</script>

<template>
  <section class="group flex min-h-[390px] flex-col overflow-hidden border border-line bg-[#fbfbf9] shadow-panel" aria-label="海报">
    <div class="h-[178px] flex-none overflow-hidden bg-cream">
      <img
        src="https://img.lubanyuan.com.cn/d/file/p/zixun/guandian/2026/01-21/1768976818_897.jpg"
        alt="项目海报"
        class="block h-full w-full object-cover transition duration-500 group-hover:scale-[1.02]"
        loading="lazy"
      >
    </div>
    <div class="flex min-h-0 flex-1 flex-col p-5">
      <div>
        <p class="text-[10px] font-bold tracking-[0.18em] text-[#aaa89f]">FOCUS PROJECT</p>
        <h2 class="mt-2 text-[22px] font-bold leading-tight tracking-tight text-[#333431]">{{ projectName }}</h2>
        <p class="mt-3 text-xs text-[#929087]">{{ projectMeta }}</p>
      </div>
      <div class="mt-5 flex flex-wrap gap-x-4 gap-y-2 text-xs font-medium text-[#74736d]">
        <span class="inline-flex items-center gap-1.5"><AppIcon name="checkCircle" size="3.5" class="text-[#76a789]" />当前项目</span>
        <span class="inline-flex items-center gap-1.5"><AppIcon name="building" size="3.5" class="text-[#9a988f]" />楼层规划</span>
      </div>
      <NuxtLink
        :to="projectDrawingRoute(workspace.currentFloorId)"
        class="mt-auto inline-flex w-full items-center justify-center gap-2 bg-[#171817] px-5 py-3.5 text-sm font-semibold text-white transition hover:bg-[#30312e] active:scale-[0.99]"
      >
        {{ workspace.currentFloorName }} · 上传识别图纸 <AppIcon name="arrowRight" size="4" />
      </NuxtLink>
    </div>
  </section>
</template>
