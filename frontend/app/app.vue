<script setup lang="ts">
const store = useOfficeStore()
const workspace = useWorkspaceStore()
const proposalFurnitureGateDialog = useProposalFurnitureGateDialog()
const { isProjectWorkflow } = useWorkspaceNavigation()
// 应用外壳先决定挂载哪个页面，不能等 NuxtPage 渲染完成后才读取路由。
const route = useRouter().currentRoute
const isLoginPage = computed(() => route.value.name === 'login')
const isSpatialWorkbench = computed(() => route.value.path === '/spatial-canvas')
async function initializeWorkspace() {
  if (isLoginPage.value || (workspace.initialized && store.initialized)) return
  await workspace.init()
  if (!store.initialized) await store.init()
}
watch(() => route.value.query.floorId, floorId => {
  if (workspace.initialized && typeof floorId === 'string' && floorId !== workspace.currentFloorId) void workspace.activateFloor(floorId)
})
onMounted(() => { void initializeWorkspace() })
watch(isLoginPage, (loginPage, wasLoginPage) => {
  if (wasLoginPage && !loginPage) void initializeWorkspace()
})
// 初始化曾失败（未锁定 initialized）时，切换路由自动重试加载真实工作区
watch(() => route.value.path, () => {
  if (!workspace.initialized && !isLoginPage.value) void initializeWorkspace()
})
</script>

<template>
  <NuxtPage v-if="isLoginPage" />
  <div v-else :class="isSpatialWorkbench ? '' : 'flex h-dvh min-h-[600px] flex-col overflow-hidden bg-bg text-ink md:flex-row'">
    <AppSidebar v-if="!isSpatialWorkbench" />
    <main :class="isSpatialWorkbench ? '' : 'flex flex-1 flex-col min-w-0 min-h-0'">
      <AppTopbar v-if="!isSpatialWorkbench" />
      <AppProjectSteps v-if="!isSpatialWorkbench && isProjectWorkflow && workspace.currentFloorId" />
      <div :class="isSpatialWorkbench ? '' : 'flex flex-1 flex-col min-h-0 overflow-y-auto'">
        <div v-if="workspace.switching" class="grid flex-1 place-items-center text-sm text-muted">正在切换楼层并加载独立工作区…</div>
        <ProjectEmptyState v-else-if="isProjectWorkflow && !workspace.currentFloorId" />
        <NuxtPage v-else :key="workspace.currentFloorId" />
      </div>
    </main>
    <ProposalFurnitureGateDialog v-if="proposalFurnitureGateDialog.mismatches.value.length" :mismatches="proposalFurnitureGateDialog.mismatches.value" @close="proposalFurnitureGateDialog.close" />
  </div>
</template>
