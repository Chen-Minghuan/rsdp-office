<script setup lang="ts">
import { projectBenchPath } from '~/utils/projectBenchRoutes'

const store = useOfficeStore()
const workspace = useWorkspaceStore()
const { isProjectBench, isProjectWorkflow, isDesignWorkflow, floorReady } = useWorkspaceNavigation()
// 应用外壳先决定挂载哪个页面，不能等 NuxtPage 渲染完成后才读取路由。
const router = useRouter()
const route = router.currentRoute
const isLoginPage = computed(() => route.value.name === 'login')
const isSpatialWorkbench = computed(() => route.value.path === '/spatial-canvas')
const isProjectPage = computed(() => isProjectBench.value || isProjectWorkflow.value)
const pageKey = computed(() => isProjectWorkflow.value ? workspace.currentFloorId : route.value.path)
const floorPending = computed(() => isProjectWorkflow.value &&
  (workspace.switching || (!!workspace.currentFloorId && !floorReady.value)))

async function initializeWorkspace() {
  if (isLoginPage.value) return
  await workspace.init()
  if (!isProjectWorkflow.value || !workspace.initialized || workspace.switching) return
  if (!store.initialized) await store.init()
  else if (workspace.currentFloorId && store.workspaceId !== workspace.currentFloorId) {
    await store.activateFloor(workspace.currentFloorId)
  }
}
watch([() => route.value.query.floorId, () => workspace.initialized, () => workspace.switching], ([floorId, initialized, switching]) => {
  if (!initialized || switching || typeof floorId !== 'string') return
  if (floorId === workspace.currentFloorId) return
  if (workspace.projects.some(project => project.floors.some(floor => floor.floorId === floorId))) {
    if (isProjectWorkflow.value) void workspace.activateFloor(floorId)
    else workspace.selectFloorContext(floorId)
  } else if (isProjectPage.value) {
    void router.replace(projectBenchPath)
  }
})
onMounted(() => { void initializeWorkspace() })
watch([() => route.value.path, isProjectWorkflow], () => { void initializeWorkspace() })
</script>

<template>
  <NuxtPage v-if="isLoginPage" />
  <div v-else :class="isSpatialWorkbench ? '' : 'flex h-dvh min-h-[600px] flex-col overflow-hidden bg-white text-ink'">
    <AppTopbar v-if="!isSpatialWorkbench" />
    <div :class="isSpatialWorkbench ? '' : 'flex flex-1 min-h-0 flex-col md:flex-row'">
      <AppSidebar v-if="!isSpatialWorkbench" />
      <main :class="isSpatialWorkbench ? '' : 'flex flex-1 flex-col min-w-0 min-h-0 overflow-y-auto'">
        <div v-if="floorPending" class="grid flex-1 place-items-center text-sm text-muted">正在加载当前楼层…</div>
        <ProjectEmptyState v-else-if="isDesignWorkflow && !workspace.currentFloorId" />
        <NuxtPage v-else :key="pageKey" />
      </main>
    </div>
  </div>
</template>
