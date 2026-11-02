import type { LayoutCandidate, LayoutCandidateScoreDetail, LayoutObjective, LayoutPreferences } from '~/types/layoutPlanning'
import type { ZoneObject } from '~/types/office'
import { allowedPlacementAreas } from './spatial/layoutRegions'
import { zoneLayoutExclusions } from './spatial/obstacles'

/**
 * LAYOUT-036 可解释评分与候选差异化纯函数（独立模块，供单模块排版与组合布局共用；
 * 与 planLayoutProposal 解耦以便轻量测试环境直接加载）。
 */

/** balanced 排序权重（产品初始可调策略值，需真实样本标定；不代表建筑规范，也不代表任何外部网站的实际权重）。 */
export const BALANCED_SCORE_WEIGHTS = { fulfillment: 40, comfort: 25, zoning: 20, utilization: 10, alignment: 5 } as const

/**
 * 求解目标归一：返回本次求解的目标件数/人数。
 * max-capacity 归一为 0（不设上限，纯容量探索）；fixed-quantity 取 fixedQuantity；缺省 target-seats 旧行为。
 * 取原值不取整：非法输入（负数/非整数）由调用方既有校验拒绝，保持旧报错行为。
 */
export function layoutObjectiveGoal(prefs: Pick<LayoutPreferences, 'objective' | 'targetSeats' | 'fixedQuantity'>): { objective: LayoutObjective; goal: number } {
  const objective = prefs.objective || 'target-seats'
  if (objective === 'max-capacity') return { objective, goal: 0 }
  return { objective, goal: objective === 'fixed-quantity' ? prefs.fixedQuantity ?? 0 : prefs.targetSeats }
}

/**
 * 可解释评分：五个归一化子项 + 加权总分 + 人读理由。
 * 舒适归一化 4 ㎡/座 封顶、补位每件扣 0.15 均为策略初始值（非规范）。
 */
export function layoutCandidateScoreDetail(args: {
  seats: number; placed: number; goal: number; utilization: number; freeAreaPerSeatM2: number | null
  fillCount?: number; requestedModules?: number; placedModules?: number
}): LayoutCandidateScoreDetail {
  const fulfillment = args.goal > 0 ? Math.min(1, args.placed / args.goal) : 1
  const comfort = args.seats > 0 && args.freeAreaPerSeatM2 != null ? Math.min(1, args.freeAreaPerSeatM2 / 4) : 1
  const zoning = args.requestedModules ? (args.placedModules || 0) / args.requestedModules : 1
  const utilization = Math.min(1, Math.max(0, args.utilization))
  const alignment = args.fillCount ? Math.max(0, 1 - args.fillCount * 0.15) : 1
  const total = Math.round(fulfillment * BALANCED_SCORE_WEIGHTS.fulfillment + comfort * BALANCED_SCORE_WEIGHTS.comfort
    + zoning * BALANCED_SCORE_WEIGHTS.zoning + utilization * BALANCED_SCORE_WEIGHTS.utilization + alignment * BALANCED_SCORE_WEIGHTS.alignment)
  const reasons: string[] = []
  const penalties: string[] = []
  if (args.goal > 0) {
    if (args.placed >= args.goal) reasons.push(`达成目标 ${args.placed}/${args.goal}`)
    else penalties.push(`仅达成 ${args.placed}/${args.goal}，尚缺 ${args.goal - args.placed}`)
    if (args.seats > args.goal) penalties.push(`超出目标 ${args.seats - args.goal} 座`)
  } else reasons.push(`探索容量 ${args.seats} 座（上限估计，非核准容量）`)
  if (args.freeAreaPerSeatM2 != null && args.seats > 0) reasons.push(`人均余量 ${args.freeAreaPerSeatM2.toFixed(1)} ㎡`)
  reasons.push(`面积利用率 ${Math.round(utilization * 100)}%`)
  if (zoning < 1) penalties.push(`${(args.requestedModules || 0) - (args.placedModules || 0)} 个必选模块未放入`)
  if (args.fillCount) penalties.push(`边缘单件补位 ${args.fillCount} 件，规整性下降`)
  if (utilization > 0.85) penalties.push('空间利用偏挤，通行余量有限')
  return { fulfillment, comfort, zoning, utilization, alignment, total, reasons, penalties }
}

/**
 * 拓扑签名：模式语义（标签词干，剔除件数数字）+ 朝向 + 行列规模 + 补位结构。
 * 整体平移/微调的变体共享同一签名；横排与纵排、网格与补位为不同拓扑。
 */
export function layoutTopologyKey(candidate: Pick<LayoutCandidate, 'label' | 'rows' | 'columns' | 'rotated'> & { fillCount?: number }): string {
  const stem = candidate.label.replace(/[\d]+/g, '').replace(/\s+/g, '')
  return [stem, candidate.rotated ? '纵向' : '横向', `${candidate.rows || 0}x${candidate.columns || 0}`, candidate.fillCount ? '含补位' : '规整'].join('|')
}

/**
 * 差异化挑选：按已排序列表贪婪选取拓扑互不相同的候选；
 * 不同拓扑不足 limit 时按原顺序补足实际数量（不编造候选，也不让 Top N 全是同拓扑平移）。
 */
export function selectDiverseCandidates<T extends { topologyKey?: string }>(sorted: T[], limit: number): T[] {
  const picked: T[] = []
  const seen = new Set<string>()
  for (const candidate of sorted) {
    const key = candidate.topologyKey || ''
    if (seen.has(key)) continue
    seen.add(key)
    picked.push(candidate)
    if (picked.length >= limit) return picked
  }
  for (const candidate of sorted) {
    if (picked.length >= limit) break
    if (!picked.includes(candidate)) picked.push(candidate)
  }
  return picked
}

/** 求解失败时的通用调整建议（策略性提示）。 */
export function layoutFailureAdvice(zone: ZoneObject, goal: number): string[] {
  const advice = ['减小家具毫米规格或间距后再试', '减少目标人数/件数，或改用「最大容量」探索当前约束上限']
  if (allowedPlacementAreas(zone) !== null) advice.push('检查布置区：启用区与空间交集过小可调整边界或启用其他区')
  if (zoneLayoutExclusions(zone).length) advice.push('核对柱/门洞/禁放区范围是否过大')
  if (goal > 0) advice.push('保留家具占用较多时可先移除部分保留件')
  return advice
}
