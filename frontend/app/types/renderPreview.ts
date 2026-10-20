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
  layoutContext?: { version: string; widthMm: number; depthMm: number; netAreaM2: number; furnitureCount: number; primaryCount: number; rowCount: number; columnCount: number; wallClearancesMm: Partial<Record<'left'|'right'|'top'|'bottom', number>>; groupGaps: Array<{axis:'x'|'y';distanceMm:number;count:number}>; reservedAreas: unknown[]; sourceDrawing: {status:string;sourceName?:string;roomLabel?:string;analysisId?:string;referenceImageUrl?:string}; openingsKnown: boolean }
  layoutGuideUrl?: string
  productImagesUsed?: boolean
  productReferences?: { version: string; items: Array<{ referenceId: string; productId: string; name: string; variant: string; imageUrl: string; imageSha256: string; usedImageCount?: number; availableImageCount?: number; images?: Array<{viewId:string;imageUrl:string;imageSha256:string}>; warnings?: string[]; furnitureNumbers: number[] }>; sheetUrls: string[] }
  referenceUrl?: string
  errorMessage?: string
  providerTaskId?: string
  providerRequestId?: string
  createdAt: string
}
