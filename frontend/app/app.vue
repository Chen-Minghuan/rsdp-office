<script setup lang="ts">
import { projectBenchPath } from '~/utils/projectBenchRoutes'

const store = useOfficeStore()
const workspace = useWorkspaceStore()
const proposalFurnitureGateDialog = useProposalFurnitureGateDialog()
const { isProjectBench, isProjectWorkflow, isDesignWorkflow, floorReady } = useWorkspaceNavigation()
// 应用外壳先决定挂载哪个页面，不能等 NuxtPage 渲染完成后才读取路由。
const router = useRouter()
const route = router.currentRoute
const isLoginPage = computed(() => route.value.name === 'login')
const isSpatialWorkbench = computed(() => route.value.path === '/spatial-canvas')
const isProjectPage = computed(() => isProjectBench.value || isProjectWorkflow.value)
const floorPending = computed(() => workspace.switching || (isProjectPage.value && !!workspace.currentFloorId && !floorReady.value))
async function initializeWorkspace() {
  if (isLoginPage.value || (workspace.initialized && store.initialized)) return
  await workspace.init()
  if (!store.initialized) await store.init()
}
watch([() => route.value.query.floorId, () => workspace.initialized, () => workspace.switching], ([floorId, initialized, switching]) => {
  if (!initialized || switching || typeof floorId !== 'string') return
  // proposal 守卫正在加载目标楼层时，当前 URL 仍可能保留来源楼层；此时不能反向切回旧楼层。
  if (workspace.routeFloorActivationId && floorId !== workspace.routeFloorActivationId) return
  if (workspace.routeFloorActivationId === floorId) workspace.releaseRouteFloorActivation(floorId)
  if (floorId === workspace.currentFloorId) return
  if (workspace.projects.some(project => project.floors.some(floor => floor.floorId === floorId))) {
    void workspace.activateFloor(floorId)
  } else if (isProjectPage.value) {
    void router.replace(projectBenchPath)
  }
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
      <div :class="isSpatialWorkbench ? '' : 'flex flex-1 flex-col min-h-0 overflow-y-auto'">
        <div v-if="floorPending" class="grid flex-1 place-items-center text-sm text-muted">正在加载当前楼层…</div>
        <ProjectEmptyState v-else-if="isDesignWorkflow && !workspace.currentFloorId" />
        <NuxtPage v-else :key="workspace.currentFloorId" />
      </div>
    </main>
    <ProposalFurnitureGateDialog v-if="proposalFurnitureGateDialog.mismatches.value.length" :mismatches="proposalFurnitureGateDialog.mismatches.value" @close="proposalFurnitureGateDialog.close" />
  </div>
</template>
