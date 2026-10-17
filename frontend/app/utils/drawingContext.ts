import type { ProjectFile } from '~/types/office'
import type { FloorPlanAnalysisResponse } from '~/types/floorPlan'

export function drawingMatchesPlan(file: ProjectFile | undefined, plan: FloorPlanAnalysisResponse | null): boolean {
  if (!file) return true
  if (!plan) return false
  if (file.analysisId && plan.inputMode !== 'manual') return file.analysisId === plan.analysisId
  if (file.drawingId) return plan.sourceDrawingId === file.drawingId
  if (file.analysisId) return file.analysisId === plan.analysisId
  // Compatibility for existing drafts without upload IDs. New uploads never rely on names.
  return plan.inputMode === 'manual' || file.name === plan.sourceName
}

export function recognitionRunning(plan: FloorPlanAnalysisResponse | null | undefined): boolean {
  return !!plan && plan.status !== 'failed' && (
    ['pending', 'analyzing'].includes(plan.status)
    || (!!plan.processing && !['DONE', 'FAILED'].includes(plan.processing.stage)))
}
