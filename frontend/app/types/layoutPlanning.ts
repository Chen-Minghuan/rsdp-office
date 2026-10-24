import type { FurnitureObject } from './office'
import type { OfficeModuleTemplate } from './officeModule'
import type { OfficeLayoutSpacing } from '~/utils/officeAutoLayout'
import type { OfficeProductStyleId, OfficeArrangementPresetId } from './officeArrangement'

export interface LayoutExclusion { id: string; label: string; x: number; y: number; w: number; h: number; polygon?: Array<[number, number]> }
/**
 * 通道是排版约束兼画布对象：纵向通道前后走向、分割桌列；横向通道左右走向、分割桌排。
 * offsetMm 与家具 x/y 共用世界毫米坐标（纵向取 x、横向取 y），不是相对偏移。
 * fromSecondRow 仅对纵向通道和课桌式网格排布生效：首排（对组模式的首个对组）不被断开。
 */
export interface LayoutChannel {
  id: string
  orientation: 'vertical' | 'horizontal'
  offsetMm: number
  widthMm: number
  fromSecondRow?: boolean
  /** 通道两侧额外净距（毫米），排版与确认按 widthMm + 2×clearanceMm 的禁放带处理；缺省为 0。 */
  clearanceMm?: number
  /** 沿走向的起点（世界毫米：纵向为 y、横向为 x）；缺省从空间包围盒起贯通（LAYOUT-028）。 */
  startMm?: number
  /** 沿走向的终点（世界毫米）；缺省贯通到包围盒末端。 */
  endMm?: number
}
export interface LayoutPreferences {
  strategy: 'template' | 'recommended'
  targetSeats: number
  includeChairs: boolean
  resizeMeetingTable: boolean
  seatClearanceMm: number
  /** @deprecated GEOM-013 起迁移为 zone.obstacles（NO_PLACEMENT）；仅保留旧数据读取兼容。 */
  exclusions?: LayoutExclusion[]
  channels?: LayoutChannel[]
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
  /** Second-pass wall-strip fill singles included in the proposal. */
  fillCount?: number
  problem: string | null
  warnings: string[]
  sourceKey: string
}
