import type { DimensionCalibrationReport } from './dimensionCalibration'

/**
 * 户型图 / 无限画布类型定义（从 RSDP 抽取并简化）。
 */

export type FloorPlanAnalysisStatus =
  | 'draft'
  | 'pending'
  | 'analyzing'
  | 'awaiting_confirm'
  | 'confirmed'
  | 'failed'

export type DimensionConfidence = 'high' | 'mid' | 'low'

export interface RoomBBox {
  x: number
  y: number
  w: number
  h: number
}

export interface FloorPlanQualityIssue {
  level: string
  code: string
  message: string
}

export interface FloorPlanPoint {
  x: number
  y: number
}

export interface FloorPlanRoom {
  contourGrounded?: boolean | null
  roomId: string
  roomType: string
  label?: string | null
  bbox?: RoomBBox | null
  widthMm?: number | null
  depthMm?: number | null
  areaM2?: number | null
  dimensionSource?: string | null
  dimensionConfidence?: DimensionConfidence | null
  dimensionText?: string | null
  polygon?: Array<[number, number]> | Array<{ x: number; y: number }> | number[] | null
  labelPoint?: FloorPlanPoint | null
  confirmationStatus?: 'AUTO' | 'CONFIRMED' | null
  sortOrder?: number
  geometrySource?: 'ai_vision' | 'cad_geometry' | 'manual_geometry' | null
}

export interface FloorPlanDrawingBounds {
  minX: number
  minY: number
  maxX: number
  maxY: number
}

export interface FloorPlanAnalysisResponse {
  /** Local upload binding; independent of names and AI image hashes. */
  sourceDrawingId?: string
  processing?: FloorPlanProcessing | null
  imageSha256?: string | null
  /** Source-bound manual design overlays; recognition rooms remain unchanged. */
  designCorrections?: Array<{
    name: string
    polygon: Array<[number, number]>
    sourceAnalysisId: string
    sourceAnalysisRevision: number
    canvasRevision: number
  }>
  dimensionCalibration?: DimensionCalibrationReport | null
  evidenceKind?: 'REAL_SERVICE' | 'ESTIMATED' | 'DEMO' | 'MANUAL'
  analysisId: string
  projectId?: string | null
  sourceName?: string | null
  imageId?: string | null
  imageUrl?: string | null
  referenceImageUrl?: string | null
  previewImageId?: string | null
  previewUrl?: string | null
  taskId?: string | null
  status: FloorPlanAnalysisStatus
  errorMessage?: string | null
  scaleRatio?: number | null
  geometrySource?: 'ai_vision' | 'cad_geometry' | 'manual_geometry' | null
  qualityIssues?: FloorPlanQualityIssue[]
  drawingBounds?: FloorPlanDrawingBounds | null
  previewBounds?: FloorPlanDrawingBounds | null
  rooms: FloorPlanRoom[]
  /** Semantic candidates whose boundaries could not be closed by the wall topology. */
  unresolvedSpaces?: Array<{
    label?: string | null
    roomType?: string | null
    confidence?: DimensionConfidence | null
    dimensionText?: string | null
  }>
  analysisRevision?: number | null
  revisionStatus?: string | null
  activeAnalysisRevision?: number | null
  workingAnalysisRevision?: number | null
  latestAnalysisRevision?: number | null
  roomRevision?: number | null
  furnitureRevision?: number | null
  parseStatus?: string | null
  unitResolution?: unknown
  inputMode?: 'image' | 'pdf' | 'cad' | 'manual' | null
}

export interface FloorPlanProcessing {
  stage: 'QUEUED' | 'PREPARING' | 'RECOGNIZING' | 'REFINING' | 'SAVING' | 'DONE' | 'FAILED'
  startedAt?: string
  updatedAt?: string
  elapsedMs?: number
  cacheHit?: boolean
  timingsMs?: Partial<Record<'prepare' | 'spaces' | 'labels' | 'contours' | 'save', number>>
}

export interface FloorPlanAnalyzeResponse {
  analysisId: string
  taskId: string
}

export interface FloorPlanConfirmRoom {
  roomId?: string | null
  roomType: string
  label?: string | null
  widthMm?: number | null
  depthMm?: number | null
  bbox?: RoomBBox | null
}

export interface FloorPlanConfirmRequest {
  rooms: FloorPlanConfirmRoom[]
  scaleRatio?: number | null
  projectId?: string | null
  analysisRevision?: number | null
  baseRoomRevision?: number | null
}

export const FLOOR_PLAN_STATUS_TEXT: Record<FloorPlanAnalysisStatus, string> = {
  draft: '草稿',
  pending: '等待中',
  analyzing: '分析中',
  awaiting_confirm: '待校正',
  confirmed: '已确认',
  failed: '识别失败',
}
