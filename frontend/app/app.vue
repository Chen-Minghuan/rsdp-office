<script setup lang="ts">
const store = useOfficeStore()
const workspace = useWorkspaceStore()
const { isProjectWorkflow } = useWorkspaceNavigation()
const route = useRoute()
const isLoginPage = computed(() => route.path === '/login')
const isSpatialWorkbench = computed(() => route.path === '/spatial-canvas' || (route.path === '/canvas' && store.canvasMode === 'overview'))
async function initializeWorkspace() {
  if (isLoginPage.value || store.initialized) return
  await workspace.init()
  if (!store.initialized) await store.init()
}
watch(() => route.query.floorId, floorId => {
  if (workspace.initialized && typeof floorId === 'string' && floorId !== workspace.currentFloorId) void workspace.activateFloor(floorId)
})
onMounted(() => { void initializeWorkspace() })
watch(isLoginPage, (loginPage, wasLoginPage) => {
  if (wasLoginPage && !loginPage) void initializeWorkspace()
})
</script>

<template>
  <NuxtPage v-if="isLoginPage" />
  <div v-else :class="isSpatialWorkbench ? '' : 'flex h-dvh min-h-[600px] flex-col overflow-hidden bg-bg text-ink md:flex-row'">
    <AppSidebar v-if="!isSpatialWorkbench" />
    <main :class="isSpatialWorkbench ? '' : 'flex flex-1 flex-col min-w-0 min-h-0'">
      <AppTopbar v-if="!isSpatialWorkbench" />
      <AppProjectSteps v-if="!isSpatialWorkbench && isProjectWorkflow" />
      <div :class="isSpatialWorkbench ? '' : 'flex flex-col flex-1 min-h-0 overflow-y-auto p-4 gap-4 md:p-5'">
        <div v-if="workspace.switching" class="grid flex-1 place-items-center text-sm text-muted">正在切换楼层并加载独立工作区…</div>
        <NuxtPage v-else :key="workspace.currentFloorId" />
      </div>
    </main>
  </div>
</template>
