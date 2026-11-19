export function useWorkspaceNavigation() {
  const route = useRouter().currentRoute
  const store = useOfficeStore()
  const lastProjectRoute = useState<string>('office-last-project-route', () => '/')
  const isProjectWorkflow = computed(() => ['index', 'canvas', 'proposal', 'agent'].some(name => name === route.value.name))

  watch(() => route.value.fullPath, (path) => {
    if (isProjectWorkflow.value && route.value.name !== 'agent') lastProjectRoute.value = path
  }, { immediate: true })

  const designRoute = computed(() => ({
    path: '/canvas',
    query: {
      mode: 'design',
      floorId: useWorkspaceStore().currentFloorId,
      ...(store.designZoneId ? { zoneId: store.designZoneId } : {}),
    },
  }))

  const projectSteps = computed(() => [
    { label: '项目创建', to: '/' },
    { label: '图纸识别', to: '/' },
    { label: '空间总览', to: '/canvas' },
    { label: '布局选品', to: designRoute.value },
    { label: '预算清单', to: '/proposal' },
  ])

  const currentStep = computed(() => {
    if (!isProjectWorkflow.value) return -1
    if (route.value.name === 'proposal') return 4
    if (route.value.name === 'agent' || (route.value.name === 'canvas' && route.value.query.mode === 'design')) return 3
    return route.value.name === 'canvas' ? 2 : 1
  })

  return { isProjectWorkflow, lastProjectRoute, projectSteps, currentStep }
}
