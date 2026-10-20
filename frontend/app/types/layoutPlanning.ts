import type { FurnitureObject } from './office'
import type { OfficeModuleTemplate } from './officeModule'
import type { OfficeLayoutSpacing } from '~/utils/officeAutoLayout'
import type { OfficeProductStyleId, OfficeArrangementPresetId } from './officeArrangement'

export interface LayoutExclusion { id: string; label: string; x: number; y: number; w: number; h: number }
export interface LayoutPreferences {
  strategy: 'template' | 'recommended'
  targetSeats: number
  includeChairs: boolean
  resizeMeetingTable: boolean
  seatClearanceMm: number
  exclusions: LayoutExclusion[]
}
export interface LayoutRequest {
  module: OfficeModuleTemplate
  widthMm: number
  depthMm: number
  spacing: OfficeLayoutSpacing
  productStyleId: OfficeProductStyleId
  presetId: OfficeArrangementPresetId
  preferences: LayoutPreferences
}
export interface LayoutProposal {
  rows?: number
  columns?: number
  rotated?: boolean
  furniture: FurnitureObject[]
  replaceIds: string[]
  retainedIds: string[]
  seats: number
  shortfall: number
  label: string
  problem: string | null
  warnings: string[]
  sourceKey: string
}
