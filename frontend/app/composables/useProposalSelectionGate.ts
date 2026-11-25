import type { ProposalFurnitureCartMismatch } from '~/utils/proposalFurnitureGate'

/**
 * 方案页选品门禁的共享状态：路由中间件在拦截跳转时写入不一致区域列表，
 * 全局弹窗组件（ProposalSelectionGateDialog）读取并渲染。
 */
export function useProposalSelectionGate() {
  const gate = useState<{ floorId: string; issues: ProposalFurnitureCartMismatch[] } | null>(
    'proposal-selection-gate',
    () => null,
  )
  return {
    gate,
    open(floorId: string, issues: ProposalFurnitureCartMismatch[]) {
      gate.value = { floorId, issues }
    },
    close() {
      gate.value = null
    },
  }
}
