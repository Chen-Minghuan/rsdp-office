import type { ProposalFurnitureCartMismatch } from '~/utils/proposalFurnitureGate'

const STATE_KEY = 'office-proposal-furniture-gate-dialog'

export function useProposalFurnitureGateDialog() {
  const mismatches = useState<ProposalFurnitureCartMismatch[]>(STATE_KEY, () => [])

  function show(next: ProposalFurnitureCartMismatch[]) {
    mismatches.value = next
  }

  function close() {
    mismatches.value = []
  }

  return { mismatches, show, close }
}
