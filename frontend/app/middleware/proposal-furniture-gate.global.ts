import { shouldGateProposalRoute } from '~/utils/proposalFurnitureGate'

export default defineNuxtRouteMiddleware(async (to) => {
  if (!shouldGateProposalRoute(to.path, to.query.preview)) return

  const workspace = useWorkspaceStore()
  if (!workspace.initialized) await workspace.init()
  const requestedFloorId = typeof to.query.floorId === 'string' ? to.query.floorId : workspace.currentFloorId
  if (!requestedFloorId) return

  const targetExists = workspace.projects.some(project => project.floors.some(floor => floor.floorId === requestedFloorId))
  // 无效楼层仍交给全局工作区守卫回到项目工作台，不能用旧楼层数据误做方案校验。
  if (!targetExists) return

  const store = useOfficeStore()
  if (workspace.currentFloorId !== requestedFloorId || !store.initialized || store.workspaceId !== requestedFloorId) {
    // 路由守卫内禁止再次改写地址，否则会触发嵌套导航并短暂读取旧楼层画布。
    await workspace.activateFloor(requestedFloorId, false, false)
  }
  // 楼层状态未就绪时必须拒绝本次 proposal 导航，不能回退到来源楼层数据做数量校验。
  if (workspace.currentFloorId !== requestedFloorId || !store.initialized || store.workspaceId !== requestedFloorId) {
    workspace.releaseRouteFloorActivation(requestedFloorId)
    return abortNavigation()
  }

  const mismatches = store.proposalFurnitureCartMismatches
  if (!mismatches.length) return

  useProposalFurnitureGateDialog().show(mismatches)
  return navigateTo({
    path: '/canvas',
    query: {
      floorId: requestedFloorId,
      mode: 'design',
      ...(store.designZoneId ? { zoneId: store.designZoneId } : {}),
    },
  })
})
