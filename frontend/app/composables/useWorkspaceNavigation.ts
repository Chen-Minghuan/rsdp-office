import { projectBenchPath, projectOverviewRoute } from '~/utils/projectBenchRoutes'

export function useWorkspaceNavigation() {
  const route = useRouter().currentRoute
  const store = useOfficeStore()
  const workspace = useWorkspaceStore()
  const isProjectBench = computed(() => route.value.name === 'workspace-project-bench' || route.value.path.replace(/\/$/, '') === projectBenchPath)
  const isProjectOverview = computed(() => isProjectBench.value && route.value.query.view === 'overview')
  const isProjectDrawing = computed(() => isProjectBench.value
    && !isProjectOverview.value
    && (typeof route.value.query.floorId === 'string' || route.value.query.view === 'drawing'))
  const isDesignWorkflow = computed(() => ['canvas', 'proposal', 'agent'].includes(String(route.value.name)) || ['/canvas', '/proposal', '/agent'].includes(route.value.path))
  const isProjectWorkflow = computed(() => isProjectOverview.value || isProjectDrawing.value || isDesignWorkflow.value)
  const floorReady = computed(() => {
    const floorId = workspace.currentFloorId
    const requestedFloorId = route.value.query.floorId
    return !!floorId && !workspace.switching && store.initialized && store.workspaceId === floorId
      && (typeof requestedFloorId !== 'string' || requestedFloorId === floorId)
  })

  const links = computed(() => {
    const floorId = workspace.currentFloorId || undefined
    const activeZone = store.designZoneId ?? store.activeZoneId
    const zoneId = floorReady.value && store.zones.some(zone => zone.id === activeZone) ? activeZone : undefined
    return [
      { id: 'workbench', label: '项目工作台', to: floorId ? projectOverviewRoute(floorId) : projectBenchPath },
      { id: 'products', label: '产品库', to: '/products' },
      { id: 'space', label: '空间设计', to: { path: '/canvas', query: { floorId } } },
      { id: 'scheme', label: '产品方案', to: { path: '/canvas', query: { floorId, mode: 'design', view: 'products', zoneId } } },
      { id: 'proposal', label: '项目清单', to: { path: '/proposal', query: { floorId } } },
    ]
  })
  const activeLink = computed(() => {
    const { name, path, query } = route.value
    if (isProjectBench.value) return 'workbench'
    if (String(name).startsWith('products') || path === '/products' || path.startsWith('/products/')) return 'products'
    if (name === 'proposal' || path === '/proposal') return 'proposal'
    if (name === 'agent' || path === '/agent' || ((name === 'canvas' || path === '/canvas') && query.mode === 'design' && query.view === 'products')) return 'scheme'
    if (name === 'canvas' || path === '/canvas') return 'space'
  })

  return { isProjectBench, isProjectDrawing, isProjectOverview, isDesignWorkflow, isProjectWorkflow, floorReady, links, activeLink }
}
