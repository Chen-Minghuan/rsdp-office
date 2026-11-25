/**
 * 项目清单页（/proposal）全局门禁：跳转前校验当前楼层的画布家具与购物车清单
 * 是否一致；不一致则中止跳转并弹出全局弹窗（直接打开/刷新时放行并叠加弹窗）。
 * 校验逻辑复用 store.proposalFurnitureCartMismatches，演示模式（?preview=1）豁免。
 */
export default defineNuxtRouteMiddleware(async (to, from) => {
  if (to.path !== '/proposal') return
  if (to.query.preview === '1') return

  const workspace = useWorkspaceStore()
  const store = useOfficeStore()
  await workspace.init()
  const floorId = typeof to.query.floorId === 'string' ? to.query.floorId : workspace.currentFloorId
  if (!floorId) return

  // office.init 始终读取 workspace.currentFloorId，校验其他楼层前必须先切换楼层上下文；
  // 不用 workspace.activateFloor（它会 router.replace，与进行中的导航冲突）。
  const previousFloorId = workspace.currentFloorId
  if (store.workspaceId !== floorId || !store.initialized) {
    if (workspace.currentFloorId !== floorId) workspace.selectFloorContext(floorId)
    if (workspace.currentFloorId !== floorId) return // 楼层不在项目列表中，无法加载校验
    await store.activateFloor(floorId)
  }
  // init 在 loading 中会早退，此处轮询等待加载完成；超时放行，行内提示兜底。
  const deadline = Date.now() + 10_000
  while (store.loading && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 50))
  if (!store.initialized || store.workspaceId !== floorId) return

  const issues = store.proposalFurnitureCartMismatches
  if (!issues.length) return

  useProposalSelectionGate().open(floorId, issues)
  if (from.matched.length === 0) return // 直达/刷新：放行，弹窗叠加在方案页上

  // 中止跳转前把楼层上下文和画布数据还原到来路楼层，避免停留页与 store 脱节卡在加载遮罩。
  const restoreFloorId = typeof from.query.floorId === 'string' ? from.query.floorId : previousFloorId
  if (restoreFloorId && workspace.currentFloorId !== restoreFloorId) workspace.selectFloorContext(restoreFloorId)
  if (restoreFloorId && store.workspaceId !== restoreFloorId) {
    await store.activateFloor(restoreFloorId).catch(() => undefined)
  }
  return abortNavigation()
})
