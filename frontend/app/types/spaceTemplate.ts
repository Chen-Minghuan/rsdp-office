import type { SpatialSceneSnapshot } from './spatial'

export interface SpaceTemplateSummary {
  templateId: string
  projectId: string
  name: string
  version: number
  contentHash: string
  sourceCanvasRevision: number
  sourceAnalysisId: string | null
  sourceAnalysisRevision?: number | null
  spaceCount: number
  /** Sum of individual spaces; overlapping space areas are not merged. */
  areaM2: number
  evidenceKind: 'DEMO' | 'ESTIMATED' | 'MANUAL' | 'REAL_SERVICE'
  createdAt: string
}
export interface SpaceTemplate extends SpaceTemplateSummary {
  scene: SpatialSceneSnapshot
  storage?: 'LOCAL' | 'SAVED'
}
export interface SpaceTemplateCreateRequest {
  name: string
  clientChangeId: string
  sourceCanvasRevision: number
  sourceAnalysisRevision?: number | null
  scene: SpatialSceneSnapshot
}
