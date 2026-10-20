import type { OfficeLayoutMode, OfficeLayoutSpacing } from '~/utils/officeAutoLayout'

/** 产品几何样式，而不是商品 SKU。一个样式可以对应多个真实商品。 */
export type OfficeProductStyleId =
  | 'straight-desk'
  | 'l-desk'
  | 'straight-screen'
  | 'l-screen'
  | 'meeting-table'
  | 'general'

/** 素材中的排布拓扑；generic-* 仅用于旧布局兼容读取。 */
export type OfficeArrangementPresetId =
  | 'generic-auto'
  | 'generic-row'
  | 'generic-grid'
  | 'generic-back-to-back'
  | 'generic-face-to-face'
  | 'generic-staggered'
  | 'generic-center-aisle'
  | 'generic-perimeter'
  | 'straight-desk-row'
  | 'straight-desk-face-to-face'
  | 'l-desk-row'
  | 'l-desk-face-to-face'
  | 'straight-screen-row'
  | 'straight-screen-face-to-face'
  | 'l-screen-row'
  | 'l-screen-face-to-face'
  | 'screen-f-row'
  | 'screen-t-face-to-face'
  | 'screen-c-face-to-face'
  | 'screen-cross-face-to-face'
  | 'meeting-center'
  | 'general-center'
  | 'general-auto'

export interface OfficeArrangementPreset {
  id: OfficeArrangementPresetId
  label: string
  description: string
  styleIds: OfficeProductStyleId[]
  /** 旧数据兼容所需的基础模式；新布局由样式专用规划器按 presetId 生成。 */
  baseMode: OfficeLayoutMode
  defaults: Partial<OfficeLayoutSpacing>
  topology: 'row' | 'paired' | 'aisle' | 'perimeter'
  sourceLabel?: string
}

export interface OfficeLayoutSettingsMeta {
  productStyleId?: OfficeProductStyleId
  arrangementPresetId?: OfficeArrangementPresetId
  arrangementRuleVersion?: string
  layoutContext?: 'workstation' | 'meeting' | 'other'
}
