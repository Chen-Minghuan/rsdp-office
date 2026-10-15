export type DimensionUnit = 'mm' | 'cm' | 'm'

export interface DimensionReference {
  referenceId: string
  text: string
  unit: DimensionUnit | null
  unitAssumed: boolean
  confidence: string
  start: [number, number] | null
  end: [number, number] | null
  lengthMm: number | null
  pixelLength: number | null
  mmPerPixel: number | null
  axis: 'HORIZONTAL' | 'VERTICAL' | 'ALIGNED' | null
  status: 'VALID' | 'REJECTED' | 'ACCEPTED' | 'OUTLIER'
  reason: string | null
}

export interface DimensionCalibrationReport {
  reportId: string
  policyVersion: string
  analysisId: string
  analysisRevision: number
  imageSha256: string
  imageWidth: number
  imageHeight: number
  assumedUnit: DimensionUnit
  status: 'READY' | 'CONFLICT' | 'INSUFFICIENT' | 'NO_DIMENSIONS'
  mmPerPixel: number | null
  maxDeviationPercent: number | null
  acceptedCount: number
  validCount: number
  unitAssumed: boolean
  warnings: string[]
  references: DimensionReference[]
  confirmationId: string | null
  confirmedAt: string | null
}

export interface DimensionCalibrationEvidence {
  reportId: string
  confirmationId: string
  sourceImageSha256: string
  assumedUnit: DimensionUnit
  mmPerPixel: number
}

export interface DimensionReferenceOverlay {
  start: { x: number; y: number }
  end: { x: number; y: number }
  label: string
  accepted: boolean
}
