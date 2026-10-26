<script setup lang="ts">
const store = useOfficeStore()
const workspace = useWorkspaceStore()
const { isProjectWorkflow } = useWorkspaceNavigation()
const route = useRoute()
const isSpatialWorkbench = computed(() => route.path === '/spatial-canvas' || (route.path === '/canvas' && store.canvasMode === 'overview'))
watch(() => route.query.floorId, floorId => {
  if (workspace.initialized && typeof floorId === 'string' && floorId !== workspace.currentFloorId) void workspace.activateFloor(floorId)
})
onMounted(async () => {
  await workspace.init()
  await store.init()
})
</script>

<template>
  <div :class="isSpatialWorkbench ? '' : 'flex h-dvh min-h-[600px] flex-col overflow-hidden bg-bg text-ink md:flex-row'">
    <AppSidebar v-if="!isSpatialWorkbench" />
    <main :class="isSpatialWorkbench ? '' : 'flex flex-1 flex-col min-w-0 min-h-0'">
      <AppTopbar v-if="!isSpatialWorkbench" />
      <AppProjectSteps v-if="!isSpatialWorkbench && isProjectWorkflow" />
      <div :class="isSpatialWorkbench ? '' : 'flex flex-col flex-1 min-h-0 p-4 gap-4 md:p-5'">
        <div v-if="workspace.switching" class="grid flex-1 place-items-center text-sm text-muted">正在切换楼层并加载独立工作区…</div>
        <NuxtPage v-else :key="workspace.currentFloorId" />
      </div>
    </main>
  </div>
</template>
