import type { FurnitureObject, ZoneObject } from '~/types/office'
import type { LayoutCandidate, LayoutCandidateScoreDetail, LayoutObjective, LayoutPreferences, LayoutProposal, LayoutRequest } from '~/types/layoutPlanning'
import { layoutRectInsideRing, planOfficeFurniture, planMeetingFurniture, seatSideRect, seatWallClearance, workstationSeatCorner, workstationSeatOverhang, officeLayoutSpacingVariants } from './officeAutoLayout'
import { planAdaptiveArrangement } from './officeAdaptiveLayout'
import { officeFurnitureFootprint, officePlacementHardProblem, officePlacementSoftWarning } from './officeManualPlacement'
import { canvasZoneBounds } from './officeCanvasView'
import { OFFICE_ARRANGEMENT_RULE_VERSION, productStyleForTemplate, presetsForProductStyle } from './officeArrangementPresets'
import { zoneLayoutExclusions } from './spatial/obstacles'
import { allowedPlacementAreas, ringWithinPlacementAreas } from './spatial/layoutRegions'
import { facilityLayoutHints } from './spatial/facilities'
import { rectOverlapsBlocker } from './layoutConstraints'
import { footprintsOverlap, furnitureFootprintRects, rectOverlapsFootprints } from './furnitureFootprint'
import { channelRects, validateChannels } from './layoutChannels'
import { zoneNetAreaMm2 } from './layoutWarnings'
import { furnitureIntersects } from './meetingFurnitureCollision'
import { furnitureById } from './manualFurniture'
import { meetingReferenceLayout, meetingSeatsForItem } from './meetingReferenceLayout'
import { RACETRACK_TABLE } from './meetingRingTable'

/** LAYOUT-034：面板最多展示的候选方案数（策略值）。 */
export const LAYOUT_CANDIDATE_TOP_N = 3

/**
 * LAYOUT-034 定稿的候选评分口径（策略值，非规范）：
 * - 目标达成主导：指定目标人数时，每满足 1 个座位计 1000 分（封顶于目标）；
 * - 超出惩罚：超出目标的每个座位扣 1 分（宁愿刚好满足，不过量摆放）；
 * - 未指定目标时仅比容量（每座 1 分）。
 * 并列保持候选生成顺序（引擎先产出的是既有默认方案，保证默认行为不漂移）。
 * 面积利用率/人均余量等舒适度指标只展示、不参与排序。
 */
export function layoutCandidateScore(seats: number, targetSeats: number): number {
  return targetSeats > 0 ? Math.min(targetSeats, seats) * 1000 - Math.max(0, seats - targetSeats) : seats
}

// LAYOUT-036 可解释评分/求解目标/差异化纯函数见 layoutScoring.ts（独立模块便于轻量测试加载）；此处再导出保持既有引用口。
export { BALANCED_SCORE_WEIGHTS, layoutObjectiveGoal, layoutCandidateScoreDetail, layoutTopologyKey, selectDiverseCandidates, layoutFailureAdvice } from './layoutScoring'
import { layoutObjectiveGoal, layoutCandidateScoreDetail, layoutTopologyKey, selectDiverseCandidates, layoutFailureAdvice } from './layoutScoring'

export function defaultLayoutPreferences(): LayoutPreferences {
  return { strategy: 'template', targetSeats: 0, includeChairs: true, resizeMeetingTable: false, seatClearanceMm: 400, channels: [] }
}

export function layoutSourceKey(zone: ZoneObject, existing: FurnitureObject[]) {
  return JSON.stringify([zone, existing])
}

/**
 * LAYOUT-034：候选手项的稳定键（家具集合签名）。方形家具旋转 90° 占地不变，
 * 归一化旋转角以避免产出视觉相同的伪候选；同一候选重算后键不变，供面板选择跟随。
 */
export function layoutCandidateKey(candidate: Pick<LayoutCandidate, 'furniture'>): string {
  return candidate.furniture.map(entry => [entry.furnitureTemplateId, Math.round(entry.x), Math.round(entry.y), Math.round(entry.w), Math.round(entry.h), Math.round(entry.w) === Math.round(entry.h) ? 0 : Math.round(entry.rotation), entry.mirrorX ? 1 : 0].join(':')).sort().join('|')
}

export function automaticModuleProblem(zone: ZoneObject, request: LayoutRequest): string | null {
  const style = productStyleForTemplate(request.module)
  if (style !== request.productStyleId || !presetsForProductStyle(style).some(preset => preset.id === request.presetId)) return '排布方式与当前家具不匹配，请重新选择排布'
  return null
}

export function furnitureSeatCount(items: FurnitureObject[], meeting: boolean) {
  if (meeting) return items.reduce((count, item) => count + meetingSeatsForItem(item), 0)
  return items.filter(item => item.furnitureTemplateId?.startsWith('desk-') || item.moduleSnapshot?.category === '职员桌').length
}

export function replaceableLayoutIds(zone: ZoneObject, existing: FurnitureObject[], request: LayoutRequest) {
  return existing.filter(item => item.zoneId === zone.id && (item.layoutGroupId === `auto:${zone.id}`
    || !item.layoutGroupId && !!item.layoutSettings && (item.furnitureTemplateId === request.module.id
      || zone.zoneType === 'MEETING_ROOM' && item.layoutSettings.layoutContext === 'meeting'))).map(item => item.id)
}

type Rect = { x: number; y: number; w: number; h: number }

/** Chair-front clearance rotates with the chair; it is separate from the physical footprint. */
export function chairActivityRect(item: FurnitureObject, clearance: number): Rect | null {
  const activity = chairActivityFootprint(item, clearance)
  return activity ? officeFurnitureFootprint(activity) : null
}

function chairActivityFootprint(item: FurnitureObject, clearance: number) {
  if (item.layoutRole !== 'seat' || clearance <= 0) return null
  const radians = item.rotation * Math.PI / 180
  const reference = furnitureById.get(item.moduleSnapshot?.glyphTemplateId || item.furnitureTemplateId || '')?.reference
  const offset = (item.h + clearance) / 2 * (reference ? 1 : -1)
  const cx = item.x + item.w / 2 - Math.sin(radians) * offset
  const cy = item.y + item.h / 2 + Math.cos(radians) * offset
  return { ...item, x: cx - item.w / 2, y: cy - clearance / 2, h: clearance }
}

export function confirmedLayoutProblem(zone: ZoneObject, furniture: FurnitureObject[]) {
  // 通道是全空间约束：「仅从二排起」的通道用已保存排版的首排深度豁免首排，没有该记录时按全深校验。
  const savedChannels = zone.layoutPreferences?.channels || []
  const firstBlockDepthMm = furniture.find(item => item.layoutSettings?.firstBlockDepthMm)?.layoutSettings?.firstBlockDepthMm
  const channelBlockers = savedChannels.length ? channelRects(zone, savedChannels, firstBlockDepthMm) : []
  for (const item of furniture) {
    const others = furniture.filter(other => other.id !== item.id)
    const saved = item.layoutGroupId && !item.mixedBundleId ? item.layoutSettings?.spacing : undefined
    const problem = officePlacementHardProblem(zone, item, others, { wallMm: item.layoutRole === 'seat' ? Math.max(saved?.wallMm || 0, saved?.aisleMm || 0) : saved?.wallMm || 0, gapMm: saved?.gapMm || 0, aisleMm: 0 })
    if (problem) return problem
    if (furnitureFootprintRects(item).some(block => channelBlockers.some(rect => rectOverlapsBlocker(block, rect)))) return '家具压占通道，请调整家具或通道位置'
  }
  return null
}

export function confirmedLayoutWarning(zone: ZoneObject, furniture: FurnitureObject[]): string | null {
  const exclusions = zoneLayoutExclusions(zone)
  for (const item of furniture) {
    const others = furniture.filter(other => other.id !== item.id)
    const saved = item.layoutGroupId ? item.layoutSettings?.spacing : undefined
    const warning = officePlacementSoftWarning(zone, item, { wallMm: item.layoutRole === 'seat' ? Math.max(saved?.wallMm || 0, saved?.aisleMm || 0) : saved?.wallMm || 0, gapMm: saved?.gapMm || 0, aisleMm: 0 })
    if (warning) return warning + '，请现场复核'
    const activity = chairActivityFootprint(item, zone.layoutPreferences?.seatClearanceMm || 0)
    if (activity) {
      const rect = officeFurnitureFootprint(activity)
      if (!layoutRectInsideRing(rect, zone.polygon || []) || others.some(other => furnitureIntersects(activity, other)) || exclusions.some(area => rectOverlapsBlocker(rect, area))) return '椅子活动区可能被占用，请现场复核'
    }
  }
  return null
}

export function planLayoutProposal(zone: ZoneObject, existing: FurnitureObject[], request: LayoutRequest): LayoutProposal {
  // Preserve the reference topology while adapting its repeat count to the room.
  if (request.module.category === '职员桌') request = { ...request, spacing: { ...request.spacing, gapMm: 0 } }
  const prefs = request.preferences
  // LAYOUT-036：求解目标归一（target-seats 缺省旧行为 / fixed-quantity 件数 / max-capacity 不设上限）
  const { objective, goal } = layoutObjectiveGoal(prefs)
  const meeting = request.productStyleId === 'meeting-table'
  const general = request.productStyleId === 'general'
  const channels = prefs.channels || []
  const exclusions = zoneLayoutExclusions(zone)
  const replaceIds = replaceableLayoutIds(zone, existing, request)
  const retained = existing.filter(item => item.zoneId === zone.id && !replaceIds.includes(item.id))
  const result: LayoutProposal = { furniture: [], replaceIds, retainedIds: retained.map(item => item.id), seats: 0, shortfall: goal, label: '', problem: null, warnings: [exclusions.length ? '已按已记录的柱、门洞净空和禁放区避障；请复核现场通道' : '门窗、柱及真实通道尚未自动核验；请在空间核对中补充'], sourceKey: layoutSourceKey(zone, existing) }
  if (objective === 'max-capacity') result.warnings.push('最大容量为当前硬约束下的可容纳上限探索值，不等于施工或法规核准容量')
  if (channels.length && !general) result.warnings.push('「仅从二排起」仅对课桌式网格排布生效；当前排布按全深避让通道')
  const problem = automaticModuleProblem(zone, request)
  if (problem) return { ...result, problem }
  const { widthMm: w, depthMm: d, spacing } = request
  if (request.module.id === RACETRACK_TABLE && w <= d) return { ...result, problem: '跑道形环桌的宽度必须大于进深，才能布置直段' }
  const values = [w, d, spacing.wallMm, spacing.aisleMm, spacing.gapMm, goal, prefs.seatClearanceMm]
  if (zone.reviewUnit !== 'mm' || !zone.polygon?.length || values.some(v => !Number.isFinite(v) || v < 0) || w < 10 || d < 10
    || !Number.isInteger(goal) || goal > 500 || (objective === 'fixed-quantity' && !Number.isInteger(prefs.fixedQuantity || 0))
    || exclusions.some(r => ![r.x, r.y, r.w, r.h].every(Number.isFinite) || r.w <= 0 || r.h <= 0)) {
    return { ...result, problem: '请检查毫米尺寸、非负间距、目标人数或数量（0—500 整数）及禁放区尺寸', problemAdvice: layoutFailureAdvice(zone, goal) }
  }
  const channelProblem = channels.length ? validateChannels(zone, channels) : null
  if (channelProblem) return { ...result, problem: channelProblem }
  // GEOM-016：有启用布置区时，自动排版把「启用区并集 ∩ 空间」当作可排轮廓（多边形，非包围盒）。
  const placementAreas = allowedPlacementAreas(zone)
  if (placementAreas !== null && !placementAreas.length) return { ...result, problem: '启用的布置区与空间没有可排布交集，请调整布置区', problemAdvice: layoutFailureAdvice(zone, goal) }
  const insideLayout = (rect: { x: number; y: number; w: number; h: number }) => placementAreas === null
    ? layoutRectInsideRing(rect, zone.polygon!)
    : ringWithinPlacementAreas(placementAreas, [[rect.x, rect.y], [rect.x + rect.w, rect.y], [rect.x + rect.w, rect.y + rect.h], [rect.x, rect.y + rect.h]])
  const bounds = canvasZoneBounds(zone)
  // 课桌式网格由 planOfficeFurniture 原生切段排版，通道不进 blockers（否则首排豁免和贴边落子会被间距规则误杀）；
  // 会议/职员桌等排布把通道作为硬阻挡并入既有避障。
  const channelBlockers = general ? [] : channelRects(zone, channels)
  const blockerAreas = [...exclusions, ...channelBlockers]
  // 家具按有效占地面判断重叠：L 型桌拆成两块，内角不再算占用。
  const retainedBlocks = retained.flatMap(furnitureFootprintRects)
  const valid = (item: FurnitureObject, peers: FurnitureObject[], wall = spacing.wallMm, clearance = 0) => {
    const footprint = officeFurnitureFootprint(item)
    const expanded = { x: footprint.x - wall, y: footprint.y - wall, w: footprint.w + wall * 2, h: footprint.h + wall * 2 }
    const blocks = furnitureFootprintRects(item)
    const blockerGap = Math.max(spacing.gapMm, clearance)
    return insideLayout(expanded)
      && !blocks.some(block => retainedBlocks.some(other => rectOverlapsBlocker(block, other, blockerGap)))
      && !blocks.some(block => blockerAreas.some(area => rectOverlapsBlocker(block, area, blockerGap)))
      && !peers.some(other => footprintsOverlap(item, other, spacing.gapMm))
  }
  // Adaptive workstation entries are checked like the planner: the wall
  // clearance only applies on the seat side; panel sides may touch the wall.
  const seatOverhang = workstationSeatOverhang(request.productStyleId)
  const seatCorner = workstationSeatCorner(request.productStyleId)
  const validAdaptive = (entry: FurnitureObject, peers: FurnitureObject[]) => {
    const footprint = officeFurnitureFootprint(entry)
    const blocks = furnitureFootprintRects(entry)
    return insideLayout(seatSideRect({ ...footprint, rotation: entry.rotation, mirrorX: entry.mirrorX }, seatWallClearance(spacing, seatOverhang), seatCorner))
      && !blocks.some(block => retainedBlocks.some(other => rectOverlapsBlocker(block, other, spacing.aisleMm)))
      && !blocks.some(block => blockerAreas.some(area => rectOverlapsBlocker(block, area, spacing.aisleMm)))
      && !peers.some(other => footprintsOverlap(entry, other, spacing.gapMm))
  }
  const item = (id: string, x: number, y: number, width: number, depth: number, rotation: number, seat = false): FurnitureObject => ({
    id, kind: 'furniture', zoneId: zone.id, productId: '', x, y, w: width, h: depth, rotation,
    furnitureTemplateId: seat ? 'chair-meeting' : request.module.id,
    ...(!seat && 'custom' in request.module ? { moduleSnapshot: { ...request.module } } : {}),
    label: seat ? '会议椅' : request.module.name, layoutGroupId: `auto:${zone.id}`, layoutRole: seat ? 'seat' : 'primary', layoutConfirmed: false,
    heightMm: seat ? undefined : request.module.heightMm, wingWidthMm: seat ? undefined : 550,
    layoutSettings: { mode: 'auto', spacing: { ...spacing }, productStyleId: request.productStyleId, arrangementPresetId: request.presetId,
      arrangementRuleVersion: OFFICE_ARRANGEMENT_RULE_VERSION, layoutContext: meeting ? 'meeting' : request.module.category === '职员桌' ? 'workstation' : 'other' },
  })
  const retainedSeats = general ? retained.filter(entry => entry.furnitureTemplateId === request.module.id).length : furnitureSeatCount(retained, meeting)
  const neededSeats = Math.max(0, goal - retainedSeats)
  if (!meeting && objective !== 'max-capacity' && goal > 0 && neededSeats === 0) return { ...result, seats: retainedSeats, shortfall: 0, problem: '保留家具已满足目标，无需自动添加；可调整目标或逐件编辑' }
  const candidates: Array<{ furniture: FurnitureObject[]; seats: number; label: string; rows?: number; columns?: number; rotated?: boolean; fillCount?: number }> = []
  if (meeting && request.module.reference) {
    const code = request.module.reference.code
    const training = code === 'T17' || code === 'T18'
    const sizes: Array<[number, number]> = [[w, d]]
    const buildReference = (width: number, depth: number) => meetingReferenceLayout(request.module, width, depth, prefs, spacing.gapMm, neededSeats, spacing.aisleMm)
    const countReferenceSeats = (reference: ReturnType<typeof buildReference>) => reference?.pieces.reduce((count, piece) => count + meetingSeatsForItem({ furnitureTemplateId: piece.template.id }), 0) || 0
    const base = buildReference(w, d)
    if (objective === 'target-seats' && prefs.resizeMeetingTable && prefs.includeChairs && neededSeats > countReferenceSeats(base) && !training && !code.startsWith('S')) {
      const roomSpan = Math.max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY)
      for (let width = w + 100; width < roomSpan && width <= w + 20_000; width += 100) {
        const depth = request.module.kind === 'round-table' ? d * width / w : d
        if (countReferenceSeats(buildReference(width, depth)) >= neededSeats) {
          sizes.unshift([width, depth])
          break
        }
      }
    }
    for (const [width, depth] of sizes) {
      const reference = width === w && depth === d ? base : buildReference(width, depth)
      if (!reference) continue
      for (const angle of [0, 90, 180, 270]) {
        const radians = angle * Math.PI / 180, c = Math.round(Math.cos(radians)), s = Math.round(Math.sin(radians))
        const local = reference.pieces.map((piece, index) => {
          const entry = item(`plan-reference-${index}`, piece.x * c - piece.y * s - piece.width / 2, piece.x * s + piece.y * c - piece.depth / 2, piece.width, piece.depth, (piece.rotation + angle + 720) % 360, piece.role === 'seat')
          return { ...entry, furnitureTemplateId: piece.template.id, label: piece.template.name, heightMm: piece.template.heightMm }
        })
        const frame = local.map(officeFurnitureFootprint)
        const minX = Math.min(...frame.map(rect => rect.x)), maxX = Math.max(...frame.map(rect => rect.x + rect.w))
        const minY = Math.min(...frame.map(rect => rect.y)), maxY = Math.max(...frame.map(rect => rect.y + rect.h))
        const positions = prefs.strategy === 'recommended' ? [0.5, 0.25, 0.75, 0.375, 0.625] : [0.5]
        for (const px of positions) for (const py of positions) {
          let cx = bounds.minX + (bounds.maxX - bounds.minX) * px - (minX + maxX) / 2
          let cy = bounds.minY + (bounds.maxY - bounds.minY) * py - (minY + maxY) / 2
          if (code === 'T13') {
            if (angle === 0) cy = bounds.minY + spacing.wallMm + depth / 2
            if (angle === 90) cx = bounds.maxX - spacing.wallMm - depth / 2
            if (angle === 180) cy = bounds.maxY - spacing.wallMm - depth / 2
            if (angle === 270) cx = bounds.minX + spacing.wallMm + depth / 2
          }
          const furniture: FurnitureObject[] = []
          let seats = retainedSeats
          let complete = true
          for (const source of local) {
            const entry = { ...source, x: source.x + cx, y: source.y + cy }
            const seat = source.layoutRole === 'seat'
            const count = meetingSeatsForItem(entry)
            if (seat && count && neededSeats > 0 && seats >= goal && !training && !code.startsWith('S')) continue
            const activity = chairActivityFootprint(entry, prefs.seatClearanceMm)
            const rect = activity && officeFurnitureFootprint(activity)
            if (!valid(entry, [], seat ? Math.max(spacing.wallMm, spacing.aisleMm) : spacing.wallMm)
              || furniture.some(other => furnitureIntersects(entry, other, spacing.gapMm))
              || activity && rect && (!insideLayout(rect) || blockerAreas.some(blocker => rectOverlapsBlocker(rect, blocker))
                || retained.some(other => furnitureIntersects(activity, other)) || furniture.some(other => furnitureIntersects(activity, other)))
              || furniture.some(other => { const area = chairActivityFootprint(other, prefs.seatClearanceMm); return area && furnitureIntersects(area, entry) })) {
              if (!seat || training || code.startsWith('S') || count === 0) { complete = false; break }
              continue
            }
            furniture.push(entry)
            seats += count
          }
          if (complete) candidates.push({ furniture, seats, label: `${reference.name} · ${angle % 180 ? '纵向' : '横向'}${width !== w ? ' · 调整桌长' : ''}`, rotated: angle % 180 === 90 })
        }
      }
    }
  } else if (meeting) {
    const round = ['round-table', 'oval-table'].includes(request.module.kind)
    // Resize only explicitly, preserving round/oval proportions and the entered depth.
    // LAYOUT-036：桌长随人数自动推算仅属 target-seats 口径；fixed-quantity/max-capacity 不改写桌尺寸。
    const requestedW = objective === 'target-seats' && prefs.resizeMeetingTable && goal > 0
      ? Math.max(w, round ? Math.ceil(goal * 650 / Math.PI) : Math.ceil(Math.max(1, goal - 2 * Math.floor(Math.max(0, d - 100) / 650)) / 2) * 650 + 100) : w
    const sizeOptions = [[requestedW, round ? d * requestedW / w : d]]
    if (requestedW !== w) {
      const margin = prefs.includeChairs ? Math.max(spacing.wallMm, spacing.aisleMm) + 580 + prefs.seatClearanceMm : Math.max(spacing.wallMm, spacing.aisleMm)
      for (const span of [bounds.maxX - bounds.minX, bounds.maxY - bounds.minY]) {
        const fitted = Math.max(w, Math.min(requestedW, span - margin * 2))
        sizeOptions.push([fitted, round ? d * fitted / w : d])
      }
    }
    if (requestedW !== w) sizeOptions.push([w, d])
    for (const [width, depth] of sizeOptions) for (const angle of [0, 90]) {
      const positions = prefs.strategy === 'recommended' ? [0.5, 0.25, 0.75, 0.375, 0.625] : [0.5]
      for (const px of positions) for (const py of positions) {
        const cx = bounds.minX + (bounds.maxX - bounds.minX) * px, cy = bounds.minY + (bounds.maxY - bounds.minY) * py
        const table = item('plan-table', cx - width! / 2, cy - depth! / 2, width!, depth!, angle)
        const margin = prefs.includeChairs ? Math.max(spacing.wallMm, spacing.aisleMm) + 580 + prefs.seatClearanceMm : Math.max(spacing.wallMm, spacing.aisleMm)
        if (!valid(table, [], margin)) continue
        const furniture = [table]
        const chairs: Array<{ x: number; y: number; rotation: number }> = []
        const radiusX = width! / 2 + 290 + prefs.seatClearanceMm, radiusY = depth! / 2 + 290 + prefs.seatClearanceMm
        if (round) {
          const count = Math.min(100, Math.floor(Math.PI * (width! + depth!) / 2 / 650))
          for (let i = 0; i < count; i++) {
            const a = i * 2 * Math.PI / count
            chairs.push({ x: Math.cos(a) * radiusX, y: Math.sin(a) * radiusY, rotation: a * 180 / Math.PI - 90 })
          }
        } else {
          const nx = Math.floor(Math.max(0, width! - 100) / 650), ny = Math.floor(Math.max(0, depth! - 100) / 650)
          for (let i = 0; i < nx; i++) for (const sign of [-1, 1]) chairs.push({ x: (i - (nx - 1) / 2) * 650, y: sign * radiusY, rotation: sign < 0 ? 180 : 0 })
          for (let i = 0; i < ny; i++) for (const sign of [-1, 1]) chairs.push({ x: sign * radiusX, y: (i - (ny - 1) / 2) * 650, rotation: sign < 0 ? 90 : 270 })
        }
        if (prefs.includeChairs) for (const local of chairs) {
          if (goal > 0 && furniture.length - 1 >= neededSeats) break
          const x = angle ? -local.y : local.x, y = angle ? local.x : local.y
          const chair = item(`plan-chair-${furniture.length}`, cx + x - 275, cy + y - 290, 550, 580, (local.rotation + angle + 360) % 360, true)
          const activity = chairActivityRect(chair, prefs.seatClearanceMm)
          if (valid(chair, furniture, Math.max(spacing.wallMm, spacing.aisleMm), prefs.seatClearanceMm)
            && (!activity || layoutRectInsideRing(activity, zone.polygon!)
              && !retainedBlocks.some(rect => rectOverlapsBlocker(activity, rect))
              && !blockerAreas.some(area => rectOverlapsBlocker(activity, area))
              && !furniture.some(other => rectOverlapsFootprints(activity, other)))
            && !furniture.some(other => { const area = chairActivityRect(other, prefs.seatClearanceMm); return area && rectOverlapsFootprints(area, chair) })) furniture.push(chair)
        }
        candidates.push({ furniture, seats: furniture.length - 1 + retainedSeats, label: `会议桌椅组合 · ${angle ? '纵向' : '横向'}${width !== w ? ' · 调整桌长' : ''}` })
      }
    }
  } else {
    // LAYOUT-034：课桌式网格把 auto 内部评估过的其他有效变体（朝向/对齐）也作为候选；首个仍是既有默认方案。
    const primary = general
      ? request.presetId === 'general-center' ? planMeetingFurniture(zone, w, d, spacing) : planOfficeFurniture(zone, w, d, 'auto', spacing, channels, prefs.seatClearanceMm)
      : planAdaptiveArrangement(zone, w, d, request.productStyleId, request.presetId, spacing, [...retainedBlocks, ...blockerAreas], neededSeats)
    const plans = [primary, ...(primary.alternates || [])]
    for (const plan of plans) {
      const furniture: FurnitureObject[] = []
      const fillCount = general ? 0 : plan.slots.filter(slot => slot.fill).length
      for (const slot of plan.slots) {
        const entry = item(`plan-desk-${furniture.length}`, slot.x + (slot.w - w) / 2, slot.y + (slot.h - d) / 2, w, d, slot.rotation)
        if (general && goal > 0 && furniture.length >= neededSeats) break
        if (slot.mirrorX) entry.mirrorX = true
        entry.layoutClusterId = slot.clusterId
        if (entry.layoutSettings && plan.presetId) entry.layoutSettings.arrangementPresetId = plan.presetId
        if (entry.layoutSettings && plan.firstBlockDepthMm) entry.layoutSettings.firstBlockDepthMm = plan.firstBlockDepthMm
        if ((general ? valid(entry, furniture, spacing.wallMm, spacing.aisleMm) : validAdaptive(entry, furniture))) furniture.push(entry)
      }
      // The planner has already skipped complete blocked cells, never individual members.
      if (!general && furniture.length !== plan.slots.length) continue
      // Edge fills extend the reference grid without changing its topology; a
      // fill-only result means the intact motif fits nowhere in this room.
      const edgeLabel = !general && plan.slots.length && fillCount === plan.slots.length
        ? `沿墙单件补位 · ${fillCount} 件（完整排布单元无法容纳）`
        : `按场地排布 · ${plan.rotated ? '纵向' : '横向'}${fillCount ? ` · 边缘补位 ${fillCount} 件` : ''}`
      if (furniture.length) candidates.push({ furniture, seats: furniture.length + retainedSeats, label: edgeLabel, fillCount, rows: general ? new Set(furniture.map(entry => Math.round(entry.y))).size : plan.rows, columns: general ? new Set(furniture.map(entry => Math.round(entry.x))).size : plan.columns, rotated: plan.rotated })
      if (plan.problem && !candidates.length) result.problem = general ? plan.problem.replace('会议桌', '家具') : plan.problem
    }
  }
  // LAYOUT-036：legacy 默认排序口径不变（目标达成单因子）；balanced 显式选择时改按可解释加权总分。
  candidates.sort((a, b) => layoutCandidateScore(b.seats, goal) - layoutCandidateScore(a.seats, goal))
  // LAYOUT-034：去除家具完全相同的变体（会议位置枚举常产生同集结果），保留 Top N 并附展示指标。
  const seen = new Set<string>()
  const distinct = candidates.filter(candidate => {
    const key = layoutCandidateKey(candidate)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
  // 净面积 = 轮廓面积 − 建筑约束外包矩形面积（近似）；利用率与人均余量为展示指标，见 layoutCandidateScore 注释。
  const netAreaMm2 = zoneNetAreaMm2(zone)
  const retainedAreaMm2 = retained.reduce((sum, entry) => { const f = officeFurnitureFootprint(entry); return sum + f.w * f.h }, 0)
  const enriched = distinct.map(candidate => {
    const area = retainedAreaMm2 + candidate.furniture.reduce((sum, entry) => { const f = officeFurnitureFootprint(entry); return sum + f.w * f.h }, 0)
    const metrics = { utilization: Math.min(1, area / netAreaMm2), freeAreaPerSeatM2: candidate.seats > 0 ? Math.max(0, (netAreaMm2 - area) / candidate.seats / 1_000_000) : null }
    return {
      ...candidate, metrics,
      scoreDetail: layoutCandidateScoreDetail({ seats: candidate.seats, placed: candidate.seats, goal, utilization: metrics.utilization, freeAreaPerSeatM2: metrics.freeAreaPerSeatM2, fillCount: candidate.fillCount }),
      topologyKey: layoutTopologyKey(candidate),
    }
  })
  if (prefs.scoreMode === 'balanced') {
    enriched.sort((a, b) => b.scoreDetail.total - a.scoreDetail.total || layoutCandidateScore(b.seats, goal) - layoutCandidateScore(a.seats, goal))
  }
  // LAYOUT-036：差异化 Top N——优先拓扑互不相同的候选，不足额按分数顺序补实际数量。
  const top = selectDiverseCandidates(enriched, LAYOUT_CANDIDATE_TOP_N)
  const best = top[0]
  if (!best) return { ...result, problem: result.problem || '当前尺寸、保留家具或预留区域无法容纳方案；请调整参数或逐件放置', problemAdvice: layoutFailureAdvice(zone, goal) }
  const topCandidates: LayoutCandidate[] = top.map(candidate => ({
    furniture: candidate.furniture, seats: candidate.seats, shortfall: Math.max(0, goal - candidate.seats), label: candidate.label,
    rows: candidate.rows, columns: candidate.columns, rotated: candidate.rotated, fillCount: candidate.fillCount,
    metrics: candidate.metrics, scoreDetail: candidate.scoreDetail, topologyKey: candidate.topologyKey,
  }))
  const fillWarning = best.fillCount ? `主排布之外的边缘余量已用单件补位 ${best.fillCount} 件，座位朝房间内侧；请核对补位位置与现场通道` : null
  // GEOM-018：设施提示接入候选结果（提示级，不阻断；点位压占/操作净空压占提醒现场复核）。
  const facilityHints = facilityLayoutHints(zone, best.furniture.map(entry => {
    const f = officeFurnitureFootprint(entry)
    return { id: entry.id, label: entry.label, ring: [[f.x, f.y], [f.x + f.w, f.y], [f.x + f.w, f.y + f.h], [f.x, f.y + f.h]] as Array<[number, number]> }
  }))
  const shortfallNote = objective === 'fixed-quantity' && best.seats < goal ? `固定件数仅放下 ${best.seats}/${goal} 件，受空间或约束限制` : null
  return {
    ...result, ...best, shortfall: Math.max(0, goal - best.seats), problem: null,
    warnings: [...result.warnings, ...(fillWarning ? [fillWarning] : []), ...(shortfallNote ? [shortfallNote] : []), ...facilityHints.map(hint => hint.message)],
    candidates: topCandidates,
  }
}

/**
 * 标准使用当前间距；紧凑/舒适按相对标准的毫米差值联动计算，固定标准优先。
 * 几何相同的档位去重，放不下的档位在 warnings 说明；应用直接提交当前预览快照。
 */
export function planTieredLayoutProposal(zone: ZoneObject, existing: FurnitureObject[], request: LayoutRequest): LayoutProposal {
  const variants = officeLayoutSpacingVariants(request.spacing)
  const results = variants.map(variant => ({ variant, proposal: planLayoutProposal(zone, existing, { ...request, spacing: variant.spacing }) }))
  const picked: Array<{ tier: LayoutCandidate['spacingTier']; label: string; candidate: LayoutCandidate }> = []
  const seen = new Set<string>()
  for (const { variant, proposal } of results) {
    const best = proposal.candidates?.[0]
    if (!best) continue
    const key = layoutCandidateKey(best)
    if (seen.has(key)) continue
    seen.add(key)
    picked.push({ tier: variant.tier, label: variant.label, candidate: { ...best, spacingTier: variant.tier, label: `${variant.label} · ${best.label}` } })
  }
  if (!picked.length) {
    // 全部档位失败：沿用标准档（当前间距）的问题与建议，其余档位的失败并入说明。
    const first = results[0]!.proposal
    const notes = results.slice(1).map(({ variant, proposal }) => proposal.problem ? `${variant.label}档同样放不下` : null).filter(Boolean) as string[]
    return { ...first, warnings: [...first.warnings, ...notes] }
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
    rows: best.candidate.rows,
    columns: best.candidate.columns,
    rotated: best.candidate.rotated,
    fillCount: best.candidate.fillCount,
    problem: null,
    warnings: [...base.warnings, ...failureNotes],
    candidates: picked.map(item => item.candidate),
  }
}

export function proposalDifference(current: FurnitureObject[], proposal: LayoutProposal) {
  const replaced = current.filter(item => proposal.replaceIds.includes(item.id))
  if (!proposal.furniture.length) return proposal.problem || '暂无可应用方案'
  if (replaced.length !== proposal.furniture.length) return `自动组 ${replaced.length} → ${proposal.furniture.length} 件；保留 ${proposal.retainedIds.length} 件`
  const keys = ['furnitureTemplateId', 'x', 'y', 'w', 'h', 'rotation', 'mirrorX', 'wingWidthMm', 'heightMm', 'moduleSnapshot', 'layoutRole', 'layoutSettings'] as const
  const signature = (items: FurnitureObject[]) => items.map(item => JSON.stringify(keys.map(key => item[key] ?? null))).sort().join('|')
  if (signature(replaced) === signature(proposal.furniture)) return '与当前布局一致'
  const sizes = (items: FurnitureObject[]) => items.map(item => `${item.furnitureTemplateId}:${item.w}:${item.h}`).sort().join('|')
  return sizes(replaced) !== sizes(proposal.furniture) ? '模块或尺寸已变化，应用后更新自动组' : '位置、朝向、间距或组合已变化，应用后更新自动组'
}
