export function useWorkspaceNavigation() {
  const route = useRoute()
  const store = useOfficeStore()
  const lastProjectRoute = useState<string>('office-last-project-route', () => '/')
  const isProjectWorkflow = computed(() => ['/', '/canvas', '/proposal', '/agent'].includes(route.path))

  watch(() => route.fullPath, (path) => {
    if (isProjectWorkflow.value && route.path !== '/agent') lastProjectRoute.value = path
  }, { immediate: true })

  const designRoute = computed(() => ({
    path: '/canvas',
    query: {
      mode: 'design',
      ...(store.designZoneId ? { zoneId: store.designZoneId } : {}),
    },
  }))

  const projectSteps = computed(() => [
    { label: '上传图纸', to: '/' },
    { label: '空间总览', to: '/canvas' },
    { label: '单空间设计', to: designRoute.value },
    { label: '方案报价', to: '/proposal' },
  ])

  const currentStep = computed(() => {
    if (!isProjectWorkflow.value) return -1
    if (route.path === '/proposal') return 3
    if (route.path === '/agent' || (route.path === '/canvas' && route.query.mode === 'design')) return 2
    return route.path === '/canvas' ? 1 : 0
  })

  return { isProjectWorkflow, lastProjectRoute, projectSteps, currentStep }
}
