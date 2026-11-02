import type { FurnitureObject } from './office'
import type { OfficeModuleTemplate } from './officeModule'
import type { OfficeLayoutSpacing } from '~/utils/officeAutoLayout'
import type { OfficeProductStyleId, OfficeArrangementPresetId } from './officeArrangement'

export interface LayoutExclusion { id: string; label: string; x: number; y: number; w: number; h: number; polygon?: Array<[number, number]> }
/**
 * LAYOUT-036 求解目标（与布局拓扑正交的两条轴之一）：
 * - target-seats（缺省，旧行为不漂移）：尽量达到目标人数，不过量摆放；
 * - fixed-quantity：按指定件数排布（桌/模块单元数；会议场景为椅数），报告可放/不可放及原因；
 * - max-capacity：在当前明确硬约束下探索可容纳上限——探索值，不等于施工或法规核准容量。
 */
export type LayoutObjective = 'target-seats' | 'fixed-quantity' | 'max-capacity'
/** LAYOUT-036 排序口径：legacy 为 LAYOUT-034 单因子公式（默认，不漂移）；balanced 为显式选择的可解释加权排序。 */
export type LayoutScoreMode = 'legacy' | 'balanced'
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
  /** LAYOUT-036 求解目标；缺省视为 target-seats（旧行为）。 */
  objective?: LayoutObjective
  /** objective = fixed-quantity 时的目标件数（桌/模块单元；会议场景为椅数），0—500 整数。 */
  fixedQuantity?: number
  /** 排序口径；缺省 legacy。balanced 仅影响候选排序，不改变任一候选的几何。 */
  scoreMode?: LayoutScoreMode
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
/**
 * LAYOUT-034 候选方案的展示指标：策略性参考值，从几何派生，不参与排序。
 * 净面积 = 空间轮廓面积 − 建筑约束外包矩形面积（近似，障碍按外包矩形计）。
 */
export interface LayoutCandidateMetrics {
  /** 面积利用率：候选家具（含保留件）物理占地 / 空间净面积（0–1）。 */
  utilization: number
  /** 人均余量：(净面积 − 占地) / 总座位（㎡）；无座位时为 null。 */
  freeAreaPerSeatM2: number | null
}
/**
 * LAYOUT-036 可解释评分：五个归一化子项（0–1）与加权总分，附人读理由/失分原因。
 * 权重为产品初始可调策略值（见 layoutPlanning.BALANCED_SCORE_WEIGHTS），不代表规范或外部网站权重。
 */
export interface LayoutCandidateScoreDetail {
  /** 需求完成：目标/件数达成率（无目标时为 1）。 */
  fulfillment: number
  /** 通行/舒适：人均余量归一化（4 ㎡/座 封顶，策略值）。 */
  comfort: number
  /** 功能分区：组合布局的必选模块完成率；单模块布局无此维度时按 1 计。 */
  zoning: number
  /** 空间利用：家具占地 / 净面积。 */
  utilization: number
  /** 对齐美观：规整度（边缘补位/散件惩罚）。 */
  alignment: number
  /** balanced 口径加权总分（0–100 展示分）。 */
  total: number
  /** 为什么适合（得分依据）。 */
  reasons: string[]
  /** 为什么失分。 */
  penalties: string[]
}
/** LAYOUT-034 候选方案：与 LayoutProposal 同源的单一变体，仅用于对比预览与选择应用。 */
export interface LayoutCandidate {
  furniture: FurnitureObject[]
  seats: number
  shortfall: number
  label: string
  rows?: number
  columns?: number
  rotated?: boolean
  fillCount?: number
  metrics: LayoutCandidateMetrics
  /** LAYOUT-036 可解释评分（展示与 balanced 排序）。 */
  scoreDetail?: LayoutCandidateScoreDetail
  /** LAYOUT-036 拓扑签名（模式/朝向/行列/补位结构），用于差异化挑选 Top N，不参与排序分。 */
  topologyKey?: string
  /** 三档间距候选（2026-11-02 定稿 v2）：该候选由哪个间距档求出；standard 即当前间距基准。 */
  spacingTier?: 'compact' | 'standard' | 'comfort'
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
  /** LAYOUT-036：求解失败时的可调整建议（策略性提示，非规范）。 */
  problemAdvice?: string[]
  warnings: string[]
  sourceKey: string
  /** LAYOUT-034 Top N 候选（按评分降序，candidates[0] 即本方案本身）；少于 2 个有效变体时只有 1 项。 */
  candidates?: LayoutCandidate[]
}
