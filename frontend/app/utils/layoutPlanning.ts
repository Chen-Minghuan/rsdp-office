import type { FurnitureObject, ZoneObject } from '~/types/office'
import type { LayoutCandidate, LayoutPreferences, LayoutProposal, LayoutRequest } from '~/types/layoutPlanning'
import { layoutRectInsideRing, planOfficeFurniture, planMeetingFurniture, seatSideRect, seatWallClearance, workstationSeatCorner, workstationSeatOverhang } from './officeAutoLayout'
import { planAdaptiveArrangement } from './officeAdaptiveLayout'
import { officeFurnitureFootprint, officePlacementProblem } from './officeManualPlacement'
import { canvasZoneBounds } from './officeCanvasView'
import { OFFICE_ARRANGEMENT_RULE_VERSION, productStyleForTemplate, presetsForProductStyle } from './officeArrangementPresets'
import { zoneLayoutExclusions } from './spatial/obstacles'
import { ringArea } from './spatial/geometry'
import { rectOverlapsBlocker } from './layoutConstraints'
import { channelRects, validateChannels } from './layoutChannels'

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
  return items.filter(item => meeting
    ? item.furnitureTemplateId?.startsWith('chair-') || item.moduleSnapshot?.kind === 'chair'
    : item.furnitureTemplateId?.startsWith('desk-') || item.moduleSnapshot?.category === '职员桌').length
}

export function replaceableLayoutIds(zone: ZoneObject, existing: FurnitureObject[], request: LayoutRequest) {
  return existing.filter(item => item.zoneId === zone.id && (item.layoutGroupId === `auto:${zone.id}`
    || !item.layoutGroupId && !!item.layoutSettings && (item.furnitureTemplateId === request.module.id
      || zone.zoneType === 'MEETING_ROOM' && item.layoutSettings.layoutContext === 'meeting'))).map(item => item.id)
}

type Rect = { x: number; y: number; w: number; h: number }
function overlap(a: Rect, b: Rect, gap = 0) {
  return a.x < b.x + b.w + gap - 0.001 && b.x < a.x + a.w + gap - 0.001 && a.y < b.y + b.h + gap - 0.001 && b.y < a.y + a.h + gap - 0.001
}

/** Chair-front clearance rotates with the chair; it is separate from the physical footprint. */
export function chairActivityRect(item: FurnitureObject, clearance: number): Rect | null {
  if (item.layoutRole !== 'seat' || clearance <= 0) return null
  const radians = item.rotation * Math.PI / 180
  const offset = -item.h / 2 - clearance / 2
  const cx = item.x + item.w / 2 - Math.sin(radians) * offset
  const cy = item.y + item.h / 2 + Math.cos(radians) * offset
  return officeFurnitureFootprint({ x: cx - item.w / 2, y: cy - clearance / 2, w: item.w, h: clearance, rotation: item.rotation })
}

export function confirmedLayoutProblem(zone: ZoneObject, furniture: FurnitureObject[]) {
  const exclusions = zoneLayoutExclusions(zone)
  // 通道是全空间约束：「仅从二排起」的通道用已保存排版的首排深度豁免首排，没有该记录时按全深校验。
  const savedChannels = zone.layoutPreferences?.channels || []
  const firstBlockDepthMm = furniture.find(item => item.layoutSettings?.firstBlockDepthMm)?.layoutSettings?.firstBlockDepthMm
  const channelBlockers = savedChannels.length ? channelRects(zone, savedChannels, firstBlockDepthMm) : []
  for (const item of furniture) {
    const others = furniture.filter(other => other.id !== item.id)
    const saved = item.layoutGroupId ? item.layoutSettings?.spacing : undefined
    const problem = officePlacementProblem(zone, item, others, { wallMm: item.layoutRole === 'seat' ? Math.max(saved?.wallMm || 0, saved?.aisleMm || 0) : saved?.wallMm || 0, gapMm: saved?.gapMm || 0, aisleMm: 0 })
    if (problem) return problem
    if (channelBlockers.some(rect => rectOverlapsBlocker(officeFurnitureFootprint(item), rect))) return '家具压占通道，请调整家具或通道位置'
    const activity = chairActivityRect(item, zone.layoutPreferences?.seatClearanceMm || 0)
    if (activity && (!layoutRectInsideRing(activity, zone.polygon || []) || others.some(other => overlap(activity, officeFurnitureFootprint(other))) || exclusions.some(area => rectOverlapsBlocker(activity, area)))) return '椅子活动区被占用，请调整位置或拉椅预留'
  }
  return null
}

export function planLayoutProposal(zone: ZoneObject, existing: FurnitureObject[], request: LayoutRequest): LayoutProposal {
  // Preserve the reference topology while adapting its repeat count to the room.
  if (request.module.category === '职员桌') request = { ...request, spacing: { ...request.spacing, gapMm: 0 } }
  const prefs = request.preferences
  const meeting = request.module.category === '会议与洽谈'
  const general = request.productStyleId === 'general'
  const channels = prefs.channels || []
  const exclusions = zoneLayoutExclusions(zone)
  const replaceIds = replaceableLayoutIds(zone, existing, request)
  const retained = existing.filter(item => item.zoneId === zone.id && !replaceIds.includes(item.id))
  const result: LayoutProposal = { furniture: [], replaceIds, retainedIds: retained.map(item => item.id), seats: 0, shortfall: prefs.targetSeats, label: '', problem: null, warnings: [exclusions.length ? '已按已记录的柱、门洞净空和禁放区避障；请复核现场通道' : '门窗、柱及真实通道尚未自动核验；请在空间核对中补充'], sourceKey: layoutSourceKey(zone, existing) }
  if (channels.length && !general) result.warnings.push('「仅从二排起」仅对课桌式网格排布生效；当前排布按全深避让通道')
  const problem = automaticModuleProblem(zone, request)
  if (problem) return { ...result, problem }
  const { widthMm: w, depthMm: d, spacing } = request
  const values = [w, d, spacing.wallMm, spacing.aisleMm, spacing.gapMm, prefs.targetSeats, prefs.seatClearanceMm]
  if (zone.reviewUnit !== 'mm' || !zone.polygon?.length || values.some(v => !Number.isFinite(v) || v < 0) || w < 10 || d < 10
    || !Number.isInteger(prefs.targetSeats) || prefs.targetSeats > 500 || exclusions.some(r => ![r.x, r.y, r.w, r.h].every(Number.isFinite) || r.w <= 0 || r.h <= 0)) {
    return { ...result, problem: '请检查毫米尺寸、非负间距、目标人数或数量（0—500 整数）及禁放区尺寸' }
  }
  const channelProblem = channels.length ? validateChannels(zone, channels) : null
  if (channelProblem) return { ...result, problem: channelProblem }
  const bounds = canvasZoneBounds(zone)
  // 课桌式网格由 planOfficeFurniture 原生切段排版，通道不进 blockers（否则首排豁免和贴边落子会被间距规则误杀）；
  // 会议/职员桌等排布把通道作为硬阻挡并入既有避障。
  const channelBlockers = general ? [] : channelRects(zone, channels)
  const blockers = [...retained.map(officeFurnitureFootprint), ...exclusions, ...channelBlockers]
  const valid = (item: FurnitureObject, peers: FurnitureObject[], wall = spacing.wallMm, clearance = 0) => {
    const footprint = officeFurnitureFootprint(item)
    const expanded = { x: footprint.x - wall, y: footprint.y - wall, w: footprint.w + wall * 2, h: footprint.h + wall * 2 }
    return layoutRectInsideRing(expanded, zone.polygon!) && !blockers.some(b => rectOverlapsBlocker(footprint, b, Math.max(spacing.gapMm, clearance)))
      && !peers.some(other => overlap(footprint, officeFurnitureFootprint(other), spacing.gapMm))
  }
  // Adaptive workstation entries are checked like the planner: the wall
  // clearance only applies on the seat side; panel sides may touch the wall.
  const seatOverhang = workstationSeatOverhang(request.productStyleId)
  const seatCorner = workstationSeatCorner(request.productStyleId)
  const validAdaptive = (entry: FurnitureObject, peers: FurnitureObject[]) => {
    const footprint = officeFurnitureFootprint(entry)
    return layoutRectInsideRing(seatSideRect({ ...footprint, rotation: entry.rotation, mirrorX: entry.mirrorX }, seatWallClearance(spacing, seatOverhang), seatCorner), zone.polygon!)
      && !blockers.some(b => rectOverlapsBlocker(footprint, b, spacing.aisleMm))
      && !peers.some(other => overlap(footprint, officeFurnitureFootprint(other), spacing.gapMm))
  }
  const item = (id: string, x: number, y: number, width: number, depth: number, rotation: number, seat = false): FurnitureObject => ({
    id, kind: 'furniture', zoneId: zone.id, productId: '', x, y, w: width, h: depth, rotation,
    furnitureTemplateId: seat ? 'chair-meeting' : request.module.id,
    ...(!seat && 'custom' in request.module ? { moduleSnapshot: { ...request.module } } : {}),
    label: seat ? '会议椅' : request.module.name, layoutGroupId: `auto:${zone.id}`, layoutRole: seat ? 'seat' : 'primary', layoutConfirmed: false,
    heightMm: seat ? undefined : request.module.heightMm, wingWidthMm: seat ? undefined : 550,
    layoutSettings: { mode: 'auto', spacing: { ...spacing }, productStyleId: request.productStyleId, arrangementPresetId: request.presetId,
      arrangementRuleVersion: OFFICE_ARRANGEMENT_RULE_VERSION, layoutContext: request.module.category === '会议与洽谈' ? 'meeting' : request.module.category === '职员桌' ? 'workstation' : 'other' },
  })
  const retainedSeats = general ? retained.filter(entry => entry.furnitureTemplateId === request.module.id).length : furnitureSeatCount(retained, meeting)
  const neededSeats = Math.max(0, prefs.targetSeats - retainedSeats)
  if (!meeting && prefs.targetSeats > 0 && neededSeats === 0) return { ...result, seats: retainedSeats, shortfall: 0, problem: '保留家具已满足目标，无需自动添加；可调整目标或逐件编辑' }
  const candidates: Array<{ furniture: FurnitureObject[]; seats: number; label: string; rows?: number; columns?: number; rotated?: boolean; fillCount?: number }> = []
  if (meeting) {
    const round = ['round-table', 'oval-table'].includes(request.module.kind)
    // Resize only explicitly, preserving round/oval proportions and the entered depth.
    const requestedW = prefs.resizeMeetingTable && prefs.targetSeats > 0
      ? Math.max(w, round ? Math.ceil(prefs.targetSeats * 650 / Math.PI) : Math.ceil(Math.max(1, prefs.targetSeats - 2 * Math.floor(Math.max(0, d - 100) / 650)) / 2) * 650 + 100) : w
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
          if (prefs.targetSeats && furniture.length - 1 >= neededSeats) break
          const x = angle ? -local.y : local.x, y = angle ? local.x : local.y
          const chair = item(`plan-chair-${furniture.length}`, cx + x - 275, cy + y - 290, 550, 580, (local.rotation + angle + 360) % 360, true)
          const activity = chairActivityRect(chair, prefs.seatClearanceMm)
          if (valid(chair, furniture, Math.max(spacing.wallMm, spacing.aisleMm), prefs.seatClearanceMm)
            && (!activity || layoutRectInsideRing(activity, zone.polygon!) && ![...blockers, ...furniture.map(officeFurnitureFootprint)].some(rect => rectOverlapsBlocker(activity, rect)))
            && !furniture.some(other => { const area = chairActivityRect(other, prefs.seatClearanceMm); return area && overlap(area, officeFurnitureFootprint(chair)) })) furniture.push(chair)
        }
        candidates.push({ furniture, seats: furniture.length - 1 + retainedSeats, label: `会议桌椅组合 · ${angle ? '纵向' : '横向'}${width !== w ? ' · 调整桌长' : ''}` })
      }
    }
  } else {
    // LAYOUT-034：课桌式网格把 auto 内部评估过的其他有效变体（朝向/对齐）也作为候选；首个仍是既有默认方案。
    const primary = general
      ? request.presetId === 'general-center' ? planMeetingFurniture(zone, w, d, spacing) : planOfficeFurniture(zone, w, d, 'auto', spacing, channels)
      : planAdaptiveArrangement(zone, w, d, request.productStyleId, request.presetId, spacing, blockers, neededSeats)
    const plans = [primary, ...(primary.alternates || [])]
    for (const plan of plans) {
      const furniture: FurnitureObject[] = []
      const fillCount = general ? 0 : plan.slots.filter(slot => slot.fill).length
      for (const slot of plan.slots) {
        const entry = item(`plan-desk-${furniture.length}`, slot.x + (slot.w - w) / 2, slot.y + (slot.h - d) / 2, w, d, slot.rotation)
        if (general && prefs.targetSeats > 0 && furniture.length >= neededSeats) break
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
  candidates.sort((a, b) => layoutCandidateScore(b.seats, prefs.targetSeats) - layoutCandidateScore(a.seats, prefs.targetSeats))
  // LAYOUT-034：去除家具完全相同的变体（会议位置枚举常产生同集结果），保留 Top N 并附展示指标。
  const seen = new Set<string>()
  const distinct = candidates.filter(candidate => {
    const key = layoutCandidateKey(candidate)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
  // 净面积 = 轮廓面积 − 建筑约束外包矩形面积（近似）；利用率与人均余量为展示指标，见 layoutCandidateScore 注释。
  const netAreaMm2 = Math.max(1, Math.abs(ringArea(zone.polygon!)) - exclusions.reduce((sum, area) => sum + area.w * area.h, 0))
  const retainedAreaMm2 = retained.reduce((sum, entry) => { const f = officeFurnitureFootprint(entry); return sum + f.w * f.h }, 0)
  const best = distinct[0]
  if (!best) return { ...result, problem: result.problem || '当前尺寸、保留家具或预留区域无法容纳方案；请调整参数或逐件放置' }
  const topCandidates: LayoutCandidate[] = distinct.slice(0, LAYOUT_CANDIDATE_TOP_N).map(candidate => {
    const area = retainedAreaMm2 + candidate.furniture.reduce((sum, entry) => { const f = officeFurnitureFootprint(entry); return sum + f.w * f.h }, 0)
    return {
      furniture: candidate.furniture, seats: candidate.seats, shortfall: Math.max(0, prefs.targetSeats - candidate.seats), label: candidate.label,
      rows: candidate.rows, columns: candidate.columns, rotated: candidate.rotated, fillCount: candidate.fillCount,
      metrics: { utilization: Math.min(1, area / netAreaMm2), freeAreaPerSeatM2: candidate.seats > 0 ? Math.max(0, (netAreaMm2 - area) / candidate.seats / 1_000_000) : null },
    }
  })
  const fillWarning = best.fillCount ? `主排布之外的边缘余量已用单件补位 ${best.fillCount} 件，座位朝房间内侧；请核对补位位置与现场通道` : null
  return { ...result, ...best, shortfall: Math.max(0, prefs.targetSeats - best.seats), problem: null, warnings: fillWarning ? [...result.warnings, fillWarning] : result.warnings, candidates: topCandidates }
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
