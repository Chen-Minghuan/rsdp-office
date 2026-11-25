import type { FurnitureObject, ZoneObject } from './office'

export interface RenderPreviewSnapshot {
  zone: Partial<ZoneObject>
  furniture: Array<Partial<FurnitureObject>>
}

export interface RenderPreviewRequest {
  clientChangeId: string
  zoneId: string
  canvasRevision: number
  confirmationId?: string
  expectedSnapshot: RenderPreviewSnapshot
  style: string
  view: 'ISOMETRIC' | 'INTERIOR'
  instructions: string
  viewImage?: string
  promptMode?: 'TECHNICAL_V10' | 'CONCISE_V11'
  referenceMode?: 'GUIDE_PLAN_PRODUCT' | 'GUIDE_PRODUCT'
  promptExtend?: boolean
  seed?: number
  sampleIndex?: number
}

export interface RenderQualityAudit {
  status: 'PASS' | 'FAIL' | 'UNCERTAIN' | 'UNAVAILABLE'
  reason?: string
  furnitureCount?: { expected: number; observed?: number; status: 'PASS' | 'FAIL' | 'UNCERTAIN' }
  layout?: { status: 'PASS' | 'FAIL' | 'UNCERTAIN'; reason?: string }
  architecture?: { status: 'PASS' | 'FAIL' | 'UNCERTAIN'; reason?: string }
  imageQuality?: { status: 'PASS' | 'FAIL' | 'UNCERTAIN'; reason?: string }
  items?: Array<{ furnitureReferenceId: string; productReferenceId?: string; status: 'PASS' | 'FAIL' | 'UNCERTAIN'; reason?: string }>
}

export type RenderPoint1000 = [number, number]
export interface RenderSpatialImageGeometry {
  groundCenter1000: RenderPoint1000
  groundPolygon1000: RenderPoint1000[]
}
export interface RenderFurnitureSpacing {
  version: 'furniture-spacing-v1'
  unit: 'mm'
  modules: Array<{ referenceId: string; furnitureId: string; nearest?: { referenceId: string; edgeGapMm: number; centerDistanceMm: number } }>
  adjacent: Array<{ from: string; to: string; axis: 'width' | 'depth'; edgeGapMm: number; touching: boolean }>
  joinedRows: string[][]
  description: string
}
export interface RenderSpatialControl {
  version: 'spatial-control-v1'
  room: { planPolygon1000: RenderPoint1000[]; guidePolygon1000: RenderPoint1000[] }
  furniture: Array<{
    F: string; furnitureId: string; P?: string; type?: string
    worldCenterMm: [number, number]; worldSizeMm: [number, number]
    worldGroundPolygon: Array<[number, number]>; rotation: number; heightMm: number
    plan: RenderSpatialImageGeometry; guide: RenderSpatialImageGeometry
  }>
  layoutSemantics?: Array<{ description: string }>
  furnitureSpacing?: RenderFurnitureSpacing
}
export interface RenderGeometryAudit {
  version: 'geometry-audit-v1'
  status: 'PASS' | 'FAIL' | 'UNCERTAIN' | 'UNAVAILABLE'
  reason?: string
  composition?: { status: string; reason?: string; singleScene?: boolean; containsReferenceBoard?: boolean; containsPlan?: boolean; containsLabels?: boolean; containsCollage?: boolean; cameraMatchesGuide?: boolean }
  room?: { status: string; reason?: string; iou?: number }
  furnitureCount?: { expected: number; observed: number; status: string }
  items?: Array<{ referenceId: string; productReferenceId?: string; detectionId?: string; status: 'PASS' | 'FAIL' | 'REVIEW' | 'UNCERTAIN'; reason?: string; centerErrorRatio?: number; groundPolygonIou?: number; confidence?: number; expectedGroundCenter1000?: RenderPoint1000; observedGroundCenter1000?: RenderPoint1000 }>
}

export interface ProductVisualProfile {
  schemaVersion: string
  productId: string
  rspuId: string
  variantId: string
  cacheKey: string
  agentModel: string
  agentPromptVersion: string
  appearancePrompt?: string
  assemblyConstraints?: string[]
  uncertainties?: string[]
  views?: Array<{
    imageId: string
    imageSha256: string
    view: string
    imageComposition?: 'SINGLE_PRODUCT' | 'PRODUCT_SET' | 'MULTIPLE_PRODUCTS' | 'MULTI_VIEW' | 'UNKNOWN'
    target?: { status: 'IDENTIFIED' | 'AMBIGUOUS' | 'NOT_FOUND' | 'UNKNOWN'; category?: string; description?: string; bbox?: number[]; confidence?: number }
    containsNonTargetProducts?: boolean
    requiresSubjectIsolation?: boolean
    originalImageSuitableForDirectGeneration?: boolean
    confidence: number
    wholeProductVisible: boolean
    suitableAsPrimary: boolean
    warnings: string[]
  }>
}

export interface RenderProductUnderstanding {
  status: 'SUCCEEDED' | 'PARTIAL' | 'UNAVAILABLE' | 'DISABLED' | 'NOT_REQUIRED'
  agentModel?: string
  agentPromptVersion?: string
  schemaVersion?: string
  cacheHits: number
  generatedCount: number
  maxNewProductsPerRender?: number
  items?: Array<{ productId: string; status: string; profileCacheKey?: string; warnings: string[] }>
}

export interface RenderPreview {
  previewId: string
  zoneId: string
  zoneName: string
  canvasRevision: number
  purpose: 'CONCEPT_PREVIEW' | 'CONFIRMED_SCHEME'
  confirmationId?: string
  model: string
  style: string
  view: 'ISOMETRIC' | 'INTERIOR'
  instructions: string
  status: 'SUBMITTING' | 'PENDING' | 'RUNNING' | 'POLLING' | 'SUCCEEDED' | 'FAILED'
  snapshot: RenderPreviewSnapshot
  stale: boolean
  imageUrl?: string
  agentStatus?: string
  productUnderstanding?: RenderProductUnderstanding
  layoutContext?: { version: string; widthMm: number; depthMm: number; netAreaM2: number; furnitureCount: number; primaryCount: number; rowCount: number; columnCount: number; wallClearancesMm: Partial<Record<'left'|'right'|'top'|'bottom', number>>; groupGaps: Array<{axis:'x'|'y';distanceMm:number;count:number}>; reservedAreas: unknown[]; sourceDrawing: {status:string;sourceName?:string;roomLabel?:string;analysisId?:string;referenceImageUrl?:string}; openingsKnown: boolean; openings?: Array<{obstacleId:string;kind:string;wallIndex:number;widthMm:number;heightSource:string}>; unresolvedOpeningIds?: string[] }
  layoutGuideUrl?: string
  sourceType?: 'whiteModelView' | 'spaceGuide'
  viewImageUrl?: string
  productImagesUsed?: boolean
  generationTiming?: { requestStartedAt: number; submitStartedAt?: number; submittedAt?: number; firstRunningAt?: number; lastPolledAt?: number; providerCompletedAt?: number; readyAt?: number; failedAt?: number; totalMs: number; preparationMs?: number; submitMs?: number; providerMs?: number; downloadMs?: number }
  generationExperiment?: { promptMode: string; promptVersion: string; referenceMode: string; promptExtend: boolean; promptExtendMode?: string; seed?: number; sampleGroup: string; sampleIndex?: number }
  productReferences?: {
    version: string
    furnitureBindings?: Array<{ referenceId: string; furnitureId: string; type: string; location: string; centerX: number; centerY: number; rotation: number; productId: string; productReferenceId: string }>
    items: Array<{
      referenceId: string
      productId: string
      name: string
      variant: string
      imageUrl: string
      imageSha256: string
      generationImageSha256?: string
      usedImageCount?: number
      availableImageCount?: number
      candidateImageCount?: number
      agentStatus?: string
      visualProfile?: ProductVisualProfile
      images?: Array<{viewId:string;imageUrl:string;imageSha256:string;sourceImageSha256?:string;generationImageSha256?:string;isolationMethod?:string;targetBbox?:number[];appliedCropPixels?:number[];requiresManualReview?:boolean}>
      candidateImages?: Array<{viewId:string;imageUrl:string;imageSha256:string}>
      warnings?: string[]
      furnitureNumbers: number[]
      furnitureReferenceIds?: string[]
      quantity?: number
    }>
    sheetUrls: string[]
    planGuideUrl?: string
    bindingGuideUrl?: string
    generationImages?: Array<{ role: string; url: string; sha256: string }>
    spatialControl?: RenderSpatialControl
  }
  qualityAudit?: RenderQualityAudit
  geometryAudit?: RenderGeometryAudit
  planGuideUrl?: string
  referenceUrl?: string
  errorMessage?: string
  providerTaskId?: string
  providerRequestId?: string
  createdAt: string
}
