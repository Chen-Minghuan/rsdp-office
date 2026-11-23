<script setup lang="ts">
const { isProjectWorkflow, isProjectBench, floorReady } = useWorkspaceNavigation()
const isProjectPage = computed(() => isProjectWorkflow.value || isProjectBench.value)
const showProjectSteps = computed(() => isProjectPage.value && floorReady.value)
</script>

<template>
  <header class="flex min-h-[64px] flex-none flex-wrap items-center gap-x-4 gap-y-2 border-b border-line bg-white/95 px-4 py-2.5 backdrop-blur md:px-7" data-testid="workspace-topbar">
    <div class="flex shrink-0 items-center gap-3 whitespace-nowrap">
      <span class="text-sm font-semibold text-ink">办公全案 · 工作区</span>
      <template v-if="!showProjectSteps">
        <span class="hidden h-4 w-px bg-line sm:block" aria-hidden="true" />
        <span class="text-xs text-muted">{{ isProjectPage ? '项目设计' : '商品资源' }}</span>
      </template>
    </div>
    <AppProjectSteps v-if="showProjectSteps" />
  </header>
</template>
