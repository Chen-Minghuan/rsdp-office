export default defineNuxtRouteMiddleware(async (to) => {
  if (to.path !== '/proposal' || to.query.preview === '1') return

  const workspace = useWorkspaceStore()
  if (!workspace.initialized) await workspace.init()
  if (!workspace.currentFloorId) return

  const store = useOfficeStore()
  if (!store.initialized) await store.init()
  const mismatches = store.proposalFurnitureCartMismatches
  if (!mismatches.length) return

  useProposalFurnitureGateDialog().show(mismatches)
  return navigateTo({
    path: '/canvas',
    query: {
      floorId: workspace.currentFloorId,
      mode: 'design',
      ...(store.designZoneId ? { zoneId: store.designZoneId } : {}),
    },
  })
})
