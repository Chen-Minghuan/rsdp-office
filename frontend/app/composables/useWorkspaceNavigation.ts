import { projectBenchPath } from '~/utils/projectBenchRoutes'

export function useWorkspaceNavigation() {
  const route = useRouter().currentRoute
  const store = useOfficeStore()
  const workspace = useWorkspaceStore()
  const designRoutes = useState<Record<string, string>>('office-design-routes', () => ({}))
  const isProjectBench = computed(() => route.value.path.replace(/\/$/, '') === projectBenchPath)
  const isProjectDrawing = computed(() => isProjectBench.value
    && (typeof route.value.query.floorId === 'string' || route.value.query.view === 'drawing'))
  const isDesignWorkflow = computed(() => ['/canvas', '/proposal', '/agent'].includes(route.value.path))
  const isProjectWorkflow = computed(() => isProjectDrawing.value || isDesignWorkflow.value)
  const floorReady = computed(() => {
    const floorId = workspace.currentFloorId
    const requestedFloorId = route.value.query.floorId
    return !!floorId && !workspace.switching && store.initialized && store.workspaceId === floorId
      && (typeof requestedFloorId !== 'string' || requestedFloorId === floorId)
  })

  watch([() => route.value.fullPath, () => workspace.currentFloorId, floorReady], ([path, floorId, ready]) => {
    if (ready && isDesignWorkflow.value && route.value.path !== '/agent') {
      designRoutes.value[floorId] = path
    }
  }, { immediate: true })

  const lastProjectRoute = computed(() => {
    const floorId = workspace.currentFloorId
    if (!floorId) return projectBenchPath
    return designRoutes.value[floorId] || { path: '/canvas', query: { floorId } }
  })

  const designRoute = computed(() => {
    const zoneId = floorReady.value && store.zones.some(zone => zone.id === store.designZoneId) ? store.designZoneId : null
    return { path: '/canvas', query: { mode: 'design', floorId: workspace.currentFloorId, ...(zoneId ? { zoneId } : {}) } }
  })

  const projectSteps = computed(() => [
    { label: '项目工作台', to: projectBenchPath },
    { label: '空间总览', to: { path: '/canvas', query: { floorId: workspace.currentFloorId } } },
    { label: '布局选品', to: designRoute.value },
    { label: '预算清单', to: { path: '/proposal', query: { floorId: workspace.currentFloorId } } },
  ])

  const currentStep = computed(() => {
    if (isProjectBench.value) return 0
    if (!isDesignWorkflow.value) return -1
    if (route.value.path === '/proposal') return 3
    if (route.value.path === '/agent' || route.value.query.mode === 'design') return 2
    return 1
  })

  return { isProjectBench, isProjectDrawing, isDesignWorkflow, isProjectWorkflow, floorReady, lastProjectRoute, projectSteps, currentStep }
}
