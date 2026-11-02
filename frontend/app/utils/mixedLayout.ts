import type { FurnitureObject, ZoneObject } from '~/types/office'
import type { LayoutCandidate, LayoutObjective, LayoutProposal, LayoutScoreMode } from '~/types/layoutPlanning'
import type { OfficeLayoutSpacing } from './officeAutoLayout'
import { furnitureById } from './manualFurniture'
import { canvasZoneBounds } from './officeCanvasView'
import { layoutRectInsideRing, officeLayoutSpacingVariants } from './officeAutoLayout'
import { zoneLayoutExclusions } from './spatial/obstacles'
import { allowedPlacementAreas, ringWithinPlacementAreas } from './spatial/layoutRegions'
import { facilityLayoutHints } from './spatial/facilities'
import type { PlacementArea } from './spatial/layoutRegions'
import { rectOverlapsBlocker } from './layoutConstraints'
import { channelRects } from './layoutChannels'
import { ringArea } from './spatial/geometry'
import { layoutCandidateScoreDetail, layoutFailureAdvice, layoutObjectiveGoal, layoutTopologyKey, selectDiverseCandidates } from './layoutScoring'

type BundleItem = { templateId: string; role: 'primary' | 'seat'; x: number; y: number; w: number; h: number; rotation?: number }
type Bundle = { categoryId: string; label: string; width: number; height: number; items: BundleItem[] }
type BundleDefinition = { label: string; primary: string; accessories?: Array<{ id: string; role?: 'primary' | 'seat'; count?: number }> }
type StrategyId = 'workstations' | 'social' | 'walls'
type Strategy = { id: StrategyId; label: string; order: string[] }
type PlacedCandidate = LayoutCandidate & { skipped: number; span: number; anchorPenalty: number; strategy: StrategyId; units: number }

export const MIXED_LAYOUT_CATEGORIES: BundleDefinition[] = [
  { label: '办公桌椅', primary: 'desk-straight', accessories: [{ id: 'chair-office', role: 'seat' }] },
  { label: '沙发茶几', primary: 'sofa-double', accessories: [{ id: 'coffee-round' }] },
  { label: '小型会议', primary: 'table-conference-rect', accessories: [{ id: 'chair-meeting', role: 'seat', count: 4 }] },
  { label: '茶桌茶椅', primary: 'table-tea-rect', accessories: [{ id: 'chair-visitor', role: 'seat', count: 2 }] },
  { label: '餐厅桌椅', primary: 'table-dining-rect', accessories: [{ id: 'chair-visitor', role: 'seat', count: 4 }] },
  { label: '玄关模块', primary: 'cabinet-entry' },
  { label: '娱乐家具', primary: 'table-game', accessories: [{ id: 'chair-lounge', role: 'seat', count: 2 }, { id: 'plant' }] },
  { label: '厨房吧台', primary: 'counter-bar', accessories: [{ id: 'chair-bar', role: 'seat', count: 2 }] },
  { label: '休闲会客', primary: 'sofa-triple', accessories: [{ id: 'coffee-rect' }, { id: 'chair-lounge', role: 'seat', count: 2 }] },
]
export const MIXED_CATEGORY_IDS = ['workstation', 'sofa', 'meeting', 'tea', 'dining', 'entry', 'leisure', 'bar', 'lounge']

const STRATEGIES: Strategy[] = [
  { id: 'workstations', label: '工位集中 · 功能分区', order: ['workstation', 'meeting', 'tea', 'dining', 'lounge', 'sofa', 'leisure', 'bar', 'entry'] },
  { id: 'social', label: '会客优先 · 中央组合', order: ['meeting', 'lounge', 'sofa', 'tea', 'dining', 'leisure', 'workstation', 'bar', 'entry'] },
  { id: 'walls', label: '沿墙收纳 · 工位集中', order: ['entry', 'bar', 'workstation', 'meeting', 'lounge', 'sofa', 'tea', 'dining', 'leisure'] },
]

function createTableSceneBundle(categoryId: string, definition: BundleDefinition, primary: NonNullable<ReturnType<typeof furnitureById.get>>, chair: NonNullable<ReturnType<typeof furnitureById.get>>): Bundle {
  const gap = 300
  const nx = Math.max(1, Math.floor((primary.widthMm - 100) / 650))
  const ny = Math.floor(Math.max(0, primary.depthMm - 100) / 650)
  const width = primary.widthMm + (ny ? 2 * (chair.widthMm + gap) : 0)
  const height = primary.depthMm + 2 * (chair.depthMm + gap)
  const tableX = (width - primary.widthMm) / 2, tableY = chair.depthMm + gap
  const items: BundleItem[] = [{ templateId: primary.id, role: 'primary', x: tableX, y: tableY, w: primary.widthMm, h: primary.depthMm }]
  for (let index = 0; index < nx; index++) {
    const x = tableX + primary.widthMm / 2 + (index - (nx - 1) / 2) * 650 - chair.widthMm / 2
    items.push({ templateId: chair.id, role: 'seat', x, y: 0, w: chair.widthMm, h: chair.depthMm, rotation: 180 })
    items.push({ templateId: chair.id, role: 'seat', x, y: tableY + primary.depthMm + gap, w: chair.widthMm, h: chair.depthMm, rotation: 0 })
  }
  if (ny) {
    const y = tableY + (primary.depthMm - chair.depthMm) / 2
    items.push({ templateId: chair.id, role: 'seat', x: 0, y, w: chair.widthMm, h: chair.depthMm, rotation: 90 })
    items.push({ templateId: chair.id, role: 'seat', x: width - chair.widthMm, y, w: chair.widthMm, h: chair.depthMm, rotation: 270 })
  }
  return { categoryId, label: definition.label, width, height, items }
}

function createLoungeSceneBundle(categoryId: string, definition: BundleDefinition, primary: NonNullable<ReturnType<typeof furnitureById.get>>): Bundle | null {
  const gap = 300
  const accessories = (definition.accessories || []).flatMap(item => Array.from({ length: item.count || 1 }, () => ({ ...item, template: furnitureById.get(item.id) }))).filter(item => item.template)
  const table = accessories.find(item => item.role !== 'seat')?.template
  const chairs = accessories.filter(item => item.role === 'seat').map(item => item.template!)
  const items: BundleItem[] = []
  const tableWidth = table?.widthMm || 0
  const chairRowWidth = chairs.reduce((sum, chair) => sum + chair.widthMm, 0) + Math.max(0, chairs.length - 1) * gap
  const width = Math.max(primary.widthMm, tableWidth, chairRowWidth)
  const tableY = primary.depthMm + gap
  const chairsY = tableY + (table?.depthMm || 0) + (chairs.length ? gap : 0)
  const height = chairsY + (chairs.length ? Math.max(...chairs.map(chair => chair.depthMm)) : 0)
  items.push({ templateId: primary.id, role: 'primary', x: (width - primary.widthMm) / 2, y: 0, w: primary.widthMm, h: primary.depthMm, rotation: 180 })
  if (table) items.push({ templateId: table.id, role: 'primary', x: (width - table.widthMm) / 2, y: tableY, w: table.widthMm, h: table.depthMm })
  let x = (width - chairRowWidth) / 2
  for (const chair of chairs) {
    items.push({ templateId: chair.id, role: 'seat', x, y: chairsY, w: chair.widthMm, h: chair.depthMm })
    x += chair.widthMm + gap
  }
  return { categoryId, label: definition.label, width, height, items }
}

function createBundle(categoryId: string): Bundle | null {
  const index = MIXED_CATEGORY_IDS.indexOf(categoryId)
  const definition = MIXED_LAYOUT_CATEGORIES[index]
  if (!definition) return null
  const primary = furnitureById.get(definition.primary)
  if (!primary) return null
  if (['meeting', 'tea', 'dining'].includes(categoryId)) {
    const chairId = categoryId === 'meeting' ? 'chair-meeting' : 'chair-visitor'
    const chair = furnitureById.get(chairId)
    if (chair) return createTableSceneBundle(categoryId, definition, primary, chair)
  }
  if (['sofa', 'lounge'].includes(categoryId)) return createLoungeSceneBundle(categoryId, definition, primary)
  const accessories = (definition.accessories || []).flatMap(item => Array.from({ length: item.count || 1 }, () => ({ ...item, template: furnitureById.get(item.id) }))).filter(item => item.template)
  const innerGap = 300
  const accessoryWidth = accessories.reduce((sum, item) => sum + item.template!.widthMm, 0) + Math.max(0, accessories.length - 1) * innerGap
  const width = Math.max(primary.widthMm, accessoryWidth)
  const accessoryHeight = accessories.length ? innerGap + Math.max(...accessories.map(item => item.template!.depthMm)) : 0
  const items: BundleItem[] = [{ templateId: primary.id, role: 'primary', x: (width - primary.widthMm) / 2, y: 0, w: primary.widthMm, h: primary.depthMm }]
  let x = 0
  for (const accessory of accessories) {
    items.push({ templateId: accessory.template!.id, role: accessory.role || 'primary', x, y: primary.depthMm + innerGap, w: accessory.template!.widthMm, h: accessory.template!.depthMm })
    x += accessory.template!.widthMm + innerGap
  }
  return { categoryId, label: definition.label, width, height: primary.depthMm + accessoryHeight, items }
}

function overlaps(a: { x: number; y: number; w: number; h: number }, b: { x: number; y: number; w: number; h: number }, gap = 0) {
  return a.x < b.x + b.w + gap - 0.001 && b.x < a.x + a.w - 0.001 && a.y < b.y + b.h + gap - 0.001 && b.y < a.y + a.h - 0.001
}

function preferredPoint(bundle: Bundle, strategy: StrategyId, bounds: ReturnType<typeof canvasZoneBounds>, wall: number) {
  const center = { x: bounds.minX + (bounds.maxX - bounds.minX - bundle.width) / 2, y: bounds.minY + (bounds.maxY - bounds.minY - bundle.height) / 2 }
  if (['meeting', 'lounge', 'sofa', 'tea', 'dining', 'leisure'].includes(bundle.categoryId)) return center
  if (['entry', 'bar'].includes(bundle.categoryId)) {
    if (strategy === 'social') return { x: bounds.maxX - wall - bundle.width, y: bounds.minY + wall }
    if (strategy === 'walls') return { x: bounds.minX + wall, y: bounds.maxY - wall - bundle.height }
    return { x: bounds.maxX - wall - bundle.width, y: bounds.maxY - wall - bundle.height }
  }
  if (bundle.categoryId === 'workstation' && strategy !== 'workstations') return { x: bounds.minX + wall, y: bounds.maxY - wall - bundle.height }
  return { x: bounds.minX + wall, y: bounds.minY + wall }
}

function bundleFurniture(bundle: Bundle, x: number, y: number, zoneId: string, spacing: OfficeLayoutSpacing, startIndex: number): FurnitureObject[] {
  return bundle.items.map((item, index) => ({
    id: `plan-mixed-${startIndex + index}-${item.templateId}`, kind: 'furniture',
    x: x + item.x, y: y + item.y, w: item.w, h: item.h, rotation: item.rotation || 0,
    productId: '', zoneId, label: furnitureById.get(item.templateId)?.name || item.templateId,
    furnitureTemplateId: item.templateId, layoutGroupId: `auto:${zoneId}`, layoutRole: item.role,
    layoutSettings: { mode: 'auto', spacing: { ...spacing }, layoutContext: 'other', productStyleId: 'general', arrangementPresetId: 'generic-auto' },
  }))
}

function findPosition(bundle: Bundle, strategy: StrategyId, zone: ZoneObject, spacing: OfficeLayoutSpacing, taken: FurnitureObject[], blockers: ReturnType<typeof zoneLayoutExclusions>, placementAreas: PlacementArea[] | null): { x: number; y: number } | null {
  const bounds = canvasZoneBounds(zone)
  const wall = Math.max(0, spacing.wallMm), gap = Math.max(300, spacing.gapMm)
  const preferred = preferredPoint(bundle, strategy, bounds, wall)
  const positions: Array<{ x: number; y: number; distance: number }> = []
  const step = Math.max(300, gap)
  for (let y = bounds.minY + wall; y + bundle.height <= bounds.maxY - wall + 0.001; y += step) {
    for (let x = bounds.minX + wall; x + bundle.width <= bounds.maxX - wall + 0.001; x += step) {
      positions.push({ x, y, distance: Math.hypot(x - preferred.x, y - preferred.y) })
    }
  }
  positions.push({ ...preferred, distance: 0 })
  positions.sort((a, b) => a.distance - b.distance)
  // GEOM-016：有启用布置区时按「启用区并集 ∩ 空间」判定可排位置（多边形，非包围盒）。
  const insideLayout = (item: FurnitureObject) => placementAreas === null
    ? layoutRectInsideRing(item, zone.polygon!)
    : ringWithinPlacementAreas(placementAreas, [[item.x, item.y], [item.x + item.w, item.y], [item.x + item.w, item.y + item.h], [item.x, item.y + item.h]])
  for (const position of positions) {
    const candidates = bundleFurniture(bundle, position.x, position.y, zone.id, spacing, 0)
    if (candidates.every(item => insideLayout(item)
      && !blockers.some(blocker => rectOverlapsBlocker(item, blocker))
      && !taken.some(other => overlaps(item, other, gap)))) return position
  }
  return null
}

function candidateFurnitureKey(furniture: FurnitureObject[]) {
  return furniture.map(item => [item.furnitureTemplateId, Math.round(item.x), Math.round(item.y), Math.round(item.w), Math.round(item.h)].join(':')).sort().join('|')
}

/** LAYOUT-036 组合布局求解选项；缺省全部为旧行为（目标人数驱动工位套数，legacy 排序）。 */
export interface MixedLayoutOptions {
  objective?: LayoutObjective
  /** objective = fixed-quantity 时的工位套数目标（0—500 整数）。 */
  fixedQuantity?: number
  scoreMode?: LayoutScoreMode
}

export function planMixedLayout(zone: ZoneObject, existing: FurnitureObject[], categoryIds: string[], spacing: OfficeLayoutSpacing, targetSeats = 0, options: MixedLayoutOptions = {}): LayoutProposal {
  const replaceIds = existing.filter(item => item.layoutGroupId === `auto:${zone.id}`).map(item => item.id)
  const retainedIds = existing.filter(item => !replaceIds.includes(item.id)).map(item => item.id)
  const sourceKey = JSON.stringify([zone, existing])
  // LAYOUT-036：求解目标归一；max-capacity 不设上限（工位套数探索到放不下为止，上限 500 防御）。
  const { objective, goal } = layoutObjectiveGoal({ objective: options.objective, targetSeats, fixedQuantity: options.fixedQuantity })
  const empty = { replaceIds, retainedIds, seats: 0, shortfall: goal, sourceKey, candidates: undefined }
  if (!zone.polygon?.length || !categoryIds.length) return { ...empty, furniture: [], label: '组合布局', problem: '请先选择需要组合的功能模块', warnings: [] }

  const requests = !categoryIds.includes('workstation') ? 1
    : objective === 'max-capacity' ? 500
    : goal > 0 ? Math.max(1, Math.floor(goal)) : 1
  const blockers = [
    ...zoneLayoutExclusions(zone),
    ...channelRects(zone, zone.layoutPreferences?.channels || []),
  ]
  const placementAreas = allowedPlacementAreas(zone)
  if (placementAreas !== null && !placementAreas.length) return { ...empty, furniture: [], label: '组合布局', problem: '启用的布置区与空间没有可排布交集，请调整布置区', warnings: [] }
  const retained = existing.filter(item => !replaceIds.includes(item.id))
  const candidates: PlacedCandidate[] = []
  for (const strategy of STRATEGIES) {
    const taken = [...retained]
    const furniture: FurnitureObject[] = [], warnings: string[] = [], placedCategories = new Set<string>()
    let units = 0
    for (const categoryId of strategy.order.filter(id => categoryIds.includes(id))) {
      const bundle = createBundle(categoryId)
      if (!bundle) continue
      const count = categoryId === 'workstation' ? requests : 1
      let placed = 0
      for (let index = 0; index < count; index++) {
        const position = findPosition(bundle, strategy.id, zone, spacing, taken, blockers, placementAreas)
        // 放不下一件时后续同款包也不会更放得下（占用只增不减），直接结束该品类——max-capacity 下避免 500 次无效全扫描
        if (!position) break
        const items = bundleFurniture(bundle, position.x, position.y, zone.id, spacing, furniture.length)
        furniture.push(...items)
        taken.push(...items)
        placed++
      }
      if (categoryId === 'workstation') units = placed
      if (placed) placedCategories.add(categoryId)
      if (placed < count && !warnings.includes(`${bundle.label}在当前空间无法完整放下，已跳过`)) warnings.push(`${bundle.label}在当前空间无法完整放下，已跳过`)
    }
    if (!furniture.length) continue
    const xs = furniture.map(item => item.x), ys = furniture.map(item => item.y)
    const span = (Math.max(...xs) - Math.min(...xs)) + (Math.max(...ys) - Math.min(...ys))
    const seats = furniture.filter(item => item.furnitureTemplateId?.startsWith('desk-') || item.furnitureTemplateId?.startsWith('chair-')).length
    const skipped = new Set(categoryIds.filter(id => !placedCategories.has(id))).size
    const bounds = canvasZoneBounds(zone)
    const table = furniture.find(item => item.furnitureTemplateId === 'table-conference-rect')
    const anchorPenalty = table ? Math.hypot((table.x + table.w / 2 - (bounds.minX + bounds.maxX) / 2) / (bounds.maxX - bounds.minX), (table.y + table.h / 2 - (bounds.minY + bounds.maxY) / 2) / (bounds.maxY - bounds.minY)) : 0
    const label = strategy.id === 'workstations' && !categoryIds.includes('workstation') && categoryIds.includes('meeting') ? '会议居中 · 功能分区' : strategy.label
    candidates.push({
      furniture, seats, shortfall: Math.max(0, goal - (objective === 'fixed-quantity' ? units : seats)), label,
      rows: new Set(furniture.map(item => Math.round(item.y))).size,
      columns: new Set(furniture.map(item => Math.round(item.x))).size,
      metrics: { utilization: 0, freeAreaPerSeatM2: null }, skipped, span, anchorPenalty, strategy: strategy.id, units,
    })
  }

  // LAYOUT-036：legacy 缺省排序口径不变（缺口→跳过模块→锚点→紧凑度）；balanced 显式选择时按加权总分。
  candidates.sort((a, b) => a.shortfall - b.shortfall || a.skipped - b.skipped || a.anchorPenalty - b.anchorPenalty || a.span - b.span || STRATEGIES.findIndex(s => s.id === a.strategy) - STRATEGIES.findIndex(s => s.id === b.strategy))
  const seen = new Set<string>()
  const distinct = candidates.filter(candidate => {
    const key = candidateFurnitureKey(candidate.furniture)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
  if (!distinct.length) return { ...empty, furniture: [], label: '组合布局', problem: '所选功能模块在当前空间放不下，请减少模块或调整尺寸/间距', problemAdvice: layoutFailureAdvice(zone, goal), warnings: ['所选组合与现有家具、建筑约束或通道冲突'] }

  const netAreaMm2 = Math.max(1, ringArea(zone.polygon) - zoneLayoutExclusions(zone).reduce((sum, area) => sum + area.w * area.h, 0))
  const retainedAreaMm2 = retained.reduce((sum, item) => sum + item.w * item.h, 0)
  const enriched = distinct.map(candidate => {
    const area = retainedAreaMm2 + candidate.furniture.reduce((sum, item) => sum + item.w * item.h, 0)
    const metrics = { utilization: Math.min(1, area / netAreaMm2), freeAreaPerSeatM2: candidate.seats > 0 ? Math.max(0, (netAreaMm2 - area) / candidate.seats / 1_000_000) : null }
    return {
      ...candidate, metrics,
      scoreDetail: layoutCandidateScoreDetail({
        seats: candidate.seats, placed: objective === 'fixed-quantity' ? candidate.units : candidate.seats, goal,
        utilization: metrics.utilization, freeAreaPerSeatM2: metrics.freeAreaPerSeatM2,
        requestedModules: categoryIds.length, placedModules: categoryIds.length - candidate.skipped,
      }),
      topologyKey: layoutTopologyKey(candidate),
    }
  })
  if (options.scoreMode === 'balanced') {
    enriched.sort((a, b) => b.scoreDetail.total - a.scoreDetail.total || a.shortfall - b.shortfall || a.skipped - b.skipped)
  }
  // LAYOUT-036：差异化 Top N——三策略本身即不同拓扑；不足额按顺序补实际数量，不编造。
  const top = selectDiverseCandidates(enriched, 3)
  const topCandidates: LayoutCandidate[] = top.map(candidate => ({
    furniture: candidate.furniture, seats: candidate.seats, shortfall: candidate.shortfall, label: candidate.label,
    rows: candidate.rows, columns: candidate.columns,
    metrics: candidate.metrics, scoreDetail: candidate.scoreDetail, topologyKey: candidate.topologyKey,
  }))
  const best = top[0]!
  const warnings = [
    ...(best.skipped ? ['部分功能模块无法完整放入当前空间，已跳过；可减少模块或调整间距'] : []),
    ...(objective === 'max-capacity' ? ['最大容量为当前硬约束下的可容纳上限探索值，不等于施工或法规核准容量'] : []),
    // GEOM-018：设施提示（提示级，不阻断）
    ...facilityLayoutHints(zone, best.furniture.map(item => ({ id: item.id, label: item.label, ring: [[item.x, item.y], [item.x + item.w, item.y], [item.x + item.w, item.y + item.h], [item.x, item.y + item.h]] as Array<[number, number]> }))).map(hint => hint.message),
  ]
  return {
    ...empty,
    furniture: best.furniture,
    seats: best.seats,
    shortfall: best.shortfall,
    label: `组合布局 · ${best.label}`,
    problem: null,
    warnings,
    candidates: topCandidates,
  }
}

/**
 * 标准使用当前间距；紧凑/舒适按相对标准的毫米差值联动计算，固定标准优先。
 * 几何相同的档位去重，放不下的档位在 warnings 说明；预览与应用共用此入口。
 */
export function planTieredMixedLayout(zone: ZoneObject, existing: FurnitureObject[], categoryIds: string[], spacing: OfficeLayoutSpacing, targetSeats = 0, options: MixedLayoutOptions = {}): LayoutProposal {
  const variants = officeLayoutSpacingVariants(spacing)
  const results = variants.map(variant => ({ variant, proposal: planMixedLayout(zone, existing, categoryIds, variant.spacing, targetSeats, options) }))
  const picked: Array<{ tier: LayoutCandidate['spacingTier']; label: string; candidate: LayoutCandidate }> = []
  const seen = new Set<string>()
  for (const { variant, proposal } of results) {
    const best = proposal.candidates?.[0]
    if (!best) continue
    const key = candidateFurnitureKey(best.furniture)
    if (seen.has(key)) continue
    seen.add(key)
    picked.push({ tier: variant.tier, label: variant.label, candidate: { ...best, spacingTier: variant.tier, label: `${variant.label} · ${best.label}` } })
  }
  if (!picked.length) {
    const first = results[0]!.proposal
    return { ...first, warnings: [...first.warnings, ...results.slice(1).map(({ variant, proposal }) => proposal.problem ? `${variant.label}档同样放不下` : null).filter(Boolean) as string[]] }
  }
  // 候选固定 标准→紧凑→舒适 顺序（变体定义序），不按分数重排：标准档始终是默认基准。
  const failureNotes = results
    .filter(({ variant, proposal }) => proposal.problem && !picked.some(item => item.tier === variant.tier))
    .map(({ variant, proposal }) => `${variant.label}档放不下：${proposal.problem}`)
  const best = picked[0]!
  const base = results.find(({ variant }) => variant.tier === best.tier)!.proposal
  return {
    ...base,
    furniture: best.candidate.furniture,
    seats: best.candidate.seats,
    shortfall: best.candidate.shortfall,
    label: best.candidate.label,
    problem: null,
    warnings: [...base.warnings, ...failureNotes],
    candidates: picked.map(item => item.candidate),
  }
}
