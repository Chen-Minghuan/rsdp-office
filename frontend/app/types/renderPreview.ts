import type { FurnitureObject, ZoneObject } from './office'

export interface RenderPreviewSnapshot {
  zone: Partial<ZoneObject>
  furniture: Array<Partial<FurnitureObject>>
}

export interface RenderPreviewRequest {
  clientChangeId: string
  zoneId: string
  canvasRevision: number
  expectedSnapshot: RenderPreviewSnapshot
  style: string
  view: 'ISOMETRIC' | 'INTERIOR'
  instructions: string
}

export interface RenderPreview {
  previewId: string
  zoneId: string
  zoneName: string
  canvasRevision: number
  purpose: 'CONCEPT_PREVIEW'
  model: string
  style: string
  view: 'ISOMETRIC' | 'INTERIOR'
  instructions: string
  status: 'SUBMITTING' | 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  snapshot: RenderPreviewSnapshot
  stale: boolean
  imageUrl?: string
  referenceUrl?: string
  errorMessage?: string
  providerTaskId?: string
  providerRequestId?: string
  createdAt: string
}
