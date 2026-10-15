/**
 * 无限画布（手动布局）类型定义。
 * 从 RSDP 主项目精简而来，移除 SceneRevision / CAD 通道等 rsdp-office 暂不依赖的概念。
 */

/** 手画布局毫米坐标矩形（坐标原点为画布左上角，Y 轴向下与 SVG 一致）。 */
export interface ManualLayoutRect {
  id?: string | null
  x: number
  y: number
  w: number
  h: number
}

/** 手画布局中的空间。 */
export interface ManualLayoutRoom {
  clientId: string
  label: string
  roomType: string
  parts: ManualLayoutRect[]
  placementZone?: ManualLayoutRect | null
  /** 全部布置区；首个为主布置区。 */
  placementZones?: ManualLayoutRect[]
}

/** 按真实毫米尺寸摆放的规划家具。 */
export interface ManualFurnitureItem {
  id: string
  templateId: string
  roomClientId: string
  /** 家具中心点，毫米坐标。 */
  xMm: number
  yMm: number
  widthMm: number
  depthMm: number
  rotationDeg: 0 | 90 | 180 | 270
  /** L/U 形桌的侧翼桌面宽。 */
  wingWidthMm?: number
  /** 柜体高度。 */
  heightMm?: number
}

/** 画布配置。 */
export interface ManualLayoutCanvas {
  widthMm: number
  heightMm: number
  gridMm: number
  mode?: 'unbounded'
}

/** 可自动保存和恢复的手画布局完整快照。 */
export interface ManualFloorPlanLayout {
  version: 1 | 2 | 3
  canvas: ManualLayoutCanvas
  rooms: ManualLayoutRoom[]
  furniture?: ManualFurnitureItem[]
}

/** 正交点。 */
export interface ManualPoint {
  x: number
  y: number
}

/** 矩形合并结果。 */
export interface ManualUnionResult {
  valid: boolean
  polygon: ManualPoint[]
  areaMm2: number
  reason?: string
}

/** 外轮廓线段（用于尺寸标注）。 */
export interface ManualPolygonSegment {
  from: ManualPoint
  to: ManualPoint
  lengthMm: number
  orientation: 'horizontal' | 'vertical'
  outwardX: -1 | 0 | 1
  outwardY: -1 | 0 | 1
}

/** 布置区到墙净距。 */
export interface ManualWallClearance {
  side: 'top' | 'right' | 'bottom' | 'left'
  distanceMm: number
  zonePoint: ManualPoint
  wallPoint: ManualPoint
}

/** 家具模板分类。 */
export type FurnitureCategory = '职员桌' | '主管桌' | '高管桌' | '前台' | '座椅' | '会议与洽谈' | '收纳' | '接待休闲' | '设备辅助'

/** 家具几何种类。 */
export type FurnitureKind =
  | 'desk' | 'l-desk' | 'rounded-l-desk' | 'u-desk' | 'chair' | 'round-table' | 'oval-table'
  | 'table' | 'cabinet' | 'sofa' | 'counter' | 'device' | 'plant' | 'wall'

/** 家具模板。 */
export interface FurnitureTemplate {
  id: string
  name: string
  category: FurnitureCategory
  kind: FurnitureKind
  widthMm: number
  depthMm: number
  sizeOptions?: Array<[number, number]>
  heightMm?: number
  heightMinMm?: number
  heightMaxMm?: number
  catalogHidden?: true
}
