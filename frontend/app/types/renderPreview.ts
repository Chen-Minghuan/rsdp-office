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
  /** 3D 白模当前视角截图（data:image/png;base64,...）；存在时作为图1结构参考，替代空间立体引导图 */
  viewImage?: string
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
  /** 结构参考来源：白模视角截图（whiteModelView）或空间立体引导图（spaceGuide，缺省） */
  sourceType?: 'whiteModelView' | 'spaceGuide'
  /** 生成时使用的白模视角截图（sourceType 为 whiteModelView 时存在） */
  viewImageUrl?: string
  productImagesUsed?: boolean
  productReferences?: { version: string; items: Array<{ referenceId: string; productId: string; name: string; variant: string; imageUrl: string; imageSha256: string; usedImageCount?: number; availableImageCount?: number; images?: Array<{viewId:string;imageUrl:string;imageSha256:string}>; warnings?: string[]; furnitureNumbers: number[] }>; sheetUrls: string[] }
  referenceUrl?: string
  errorMessage?: string
  providerTaskId?: string
  providerRequestId?: string
  createdAt: string
}
