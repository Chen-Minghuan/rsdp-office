import type { Product, OfficeObject, ProjectFile } from './office'

export interface ModuleRecommendationRequest {
  zoneId: string
  moduleId: string
  canvasRevision: number
  allowRotation: boolean
  productId?: string
  applyToSameModules?: boolean
}
export interface ModuleProductCandidate {
  product: Product
  tier: 'CLOSE' | 'RELAXED' | 'SMALL'
  fitRatio: number
  rotationDeg: number
  remainingWidthMm: number
  remainingDepthMm: number
}
export interface ModuleRecommendation {
  moduleId: string
  zoneId: string
  canvasRevision: number
  widthMm: number
  depthMm: number
  sameModuleCount: number
  missingDimensions: number
  eligibleVariantCount: number
  candidates: ModuleProductCandidate[]
}
export interface ModuleProductSelection {
  canvas: { objects: OfficeObject[]; files: ProjectFile[]; revision: number }
  cartItems: Array<{ lineId: string; projectId: string; zoneId: string; zoneName: string; productId: string; qty: number }>
  match: ModuleProductCandidate
}
