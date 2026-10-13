<script setup lang="ts">
const { isProjectWorkflow } = useWorkspaceNavigation()
</script>

<template>
  <header class="flex min-h-[64px] flex-none items-center justify-between gap-3 border-b border-line bg-white/95 px-4 py-3 backdrop-blur md:px-7">
    <div class="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
      <span class="text-sm font-semibold text-ink">办公全案 · 工作区</span>
      <span class="hidden h-4 w-px bg-line sm:block" aria-hidden="true" />
      <span class="text-xs text-muted">{{ isProjectWorkflow ? '项目设计' : '商品资源' }}</span>
    </div>
    <div class="flex shrink-0 items-center gap-2 text-sm text-[#615b55]">
      <span class="grid h-8 w-8 place-items-center rounded-full bg-[#e8ddd0] text-xs font-semibold text-[#886a4a]" aria-hidden="true">R</span>
      <span class="hidden sm:inline">设计师</span>
    </div>
  </header>
</template>
