import type { FurnitureObject, ZoneObject } from '~/types/office'
import type { OfficeModuleTemplate } from '~/types/officeModule'
import type { OfficeLayoutSpacing } from '~/utils/officeAutoLayout'
import { layoutRectInsideRing, seatSideRect } from '~/utils/officeAutoLayout'
import { furnitureById } from '~/utils/manualFurniture'
import { uid } from '~/utils/helpers'
import { zoneLayoutExclusions } from './spatial/obstacles'
import { allowedPlacementAreas, ringWithinPlacementAreas } from './spatial/layoutRegions'
import { rectOverlapsBlocker } from './layoutConstraints'

export interface OfficeFurniturePlacement {
  zoneId: string
  template: OfficeModuleTemplate
  widthMm: number
  depthMm: number
  rotation: 0 | 90 | 180 | 270
  spacing: OfficeLayoutSpacing
}

export function officeFurnitureFootprint(item: Pick<FurnitureObject, 'x' | 'y' | 'w' | 'h' | 'rotation'>) {
  const radians = item.rotation * Math.PI / 180
  const w = Math.abs(item.w * Math.cos(radians)) + Math.abs(item.h * Math.sin(radians))
  const h = Math.abs(item.w * Math.sin(radians)) + Math.abs(item.h * Math.cos(radians))
  return { x: item.x + (item.w - w) / 2, y: item.y + (item.h - h) / 2, w, h }
}

export function manualOfficeFurniture(draft: OfficeFurniturePlacement, xMm: number, yMm: number): FurnitureObject {
  return {
    id: '', kind: 'furniture', zoneId: draft.zoneId, productId: '', furnitureTemplateId: draft.template.id,
    x: xMm - draft.widthMm / 2, y: yMm - draft.depthMm / 2,
    w: draft.widthMm, h: draft.depthMm, rotation: draft.rotation, label: draft.template.name,
    ...('custom' in draft.template ? { moduleSnapshot: { ...draft.template } } : {}),
    heightMm: draft.template.heightMm, wingWidthMm: 550, layoutConfirmed: false,
  }
}

/** 手动摆放/移动/旋转的硬校验：占地在轮廓内、不与他人重叠、不占入口/禁放区。座椅净空等设计建议不在这里拦截。 */
export function officePlacementHardProblem(zone: ZoneObject, candidate: FurnitureObject, existing: FurnitureObject[], spacing: OfficeLayoutSpacing): string | null {
  if (zone.reviewUnit !== 'mm' || candidate.zoneId !== zone.id) return '请在当前已校准空间内放置'
  if (![candidate.x, candidate.y, candidate.w, candidate.h, candidate.rotation, spacing.wallMm, spacing.gapMm].every(Number.isFinite)
    || candidate.w < 10 || candidate.h < 10 || spacing.wallMm < 0 || spacing.gapMm < 0) return '家具尺寸或间距无效'
  const footprint = officeFurnitureFootprint(candidate)
  if (!layoutRectInsideRing(footprint, zone.polygon || [])) return '此位置超出空间边界'
  // GEOM-016：有启用布置区时，家具占地必须完整落在启用区并集 ∩ 空间内（多边形，非包围盒）。
  const areas = allowedPlacementAreas(zone)
  if (areas !== null) {
    if (!areas.length) return '启用的布置区与空间没有可排布交集'
    const ring: Array<[number, number]> = [[footprint.x, footprint.y], [footprint.x + footprint.w, footprint.y], [footprint.x + footprint.w, footprint.y + footprint.h], [footprint.x, footprint.y + footprint.h]]
    if (!ringWithinPlacementAreas(areas, ring)) return '此位置超出已启用的家具布置区'
  }
  for (const item of existing) {
    if (item.zoneId !== zone.id) continue
    const other = officeFurnitureFootprint(item), gap = spacing.gapMm, eps = 0.001
    if (footprint.x < other.x + other.w + gap - eps && other.x < footprint.x + footprint.w + gap - eps
      && footprint.y < other.y + other.h + gap - eps && other.y < footprint.y + footprint.h + gap - eps) return '此位置与已有家具重叠或间距不足'
  }
  for (const area of zoneLayoutExclusions(zone)) {
    if (rectOverlapsBlocker(footprint, area)) return '此位置占用了入口或禁放区'
  }
  return null
}

/** 座椅净空软提示：桌子座椅侧净空（默认 560mm）延伸出轮廓时返回提示文案；手动操作和确认均不据此拦截。 */
export function officePlacementSoftWarning(zone: ZoneObject, candidate: FurnitureObject, spacing: OfficeLayoutSpacing): string | null {
  const footprint = officeFurnitureFootprint(candidate)
  // Chairs (meeting seats) keep the clearance on all sides; desks and other
  // modules only owe the wall clearance on the seat side — panel sides may
  // touch the wall.
  const kind = candidate.moduleSnapshot?.kind || furnitureById.get(candidate.furnitureTemplateId || '')?.kind
  const corner = !!kind && ['l-desk', 'rounded-l-desk', 'u-desk'].includes(kind)
  // Manual placement and confirmation only owe the configured wall distance on the seat side; the stricter seat aisle is enforced by the auto planner.
  const seatWall = Math.max(spacing.wallMm, kind === 'desk' ? 560 : 0)
  const wallChecked = candidate.layoutRole === 'seat'
    ? { x: footprint.x - spacing.wallMm, y: footprint.y - spacing.wallMm, w: footprint.w + spacing.wallMm * 2, h: footprint.h + spacing.wallMm * 2 }
    : seatSideRect({ ...footprint, rotation: candidate.rotation, mirrorX: candidate.mirrorX }, seatWall, corner)
  if (!layoutRectInsideRing(wallChecked, zone.polygon || [])) return '座椅一侧离墙净空不足 560mm'
  return null
}

/** Validate a translated selection against the room and furniture outside the selection. */
export function officeFurnitureMoveProblem(zone: ZoneObject, candidates: FurnitureObject[], existing: FurnitureObject[]): string | null {
  if (!candidates.length || candidates.some(item => item.zoneId !== zone.id)) return '只能移动当前空间的家具'
  const movingIds = new Set(candidates.map(item => item.id))
  const others = existing.filter(item => !movingIds.has(item.id))
  const spacing: OfficeLayoutSpacing = { wallMm: 0, gapMm: 0, aisleMm: 0 }
  for (const candidate of candidates) {
    const problem = officePlacementHardProblem(zone, candidate, others, spacing)
    if (problem) return problem
  }
  return null
}

/** 移动/旋转生效后的软提示（座椅净空不足等），仅用于展示，不影响操作结果。 */
export function officeFurnitureMoveWarning(zone: ZoneObject, items: FurnitureObject[]): string | null {
  const spacing: OfficeLayoutSpacing = { wallMm: 0, gapMm: 0, aisleMm: 0 }
  for (const item of items) {
    const warning = officePlacementSoftWarning(zone, item, spacing)
    if (warning) return warning
  }
  return null
}

export function identifyManualFurniture(candidate: FurnitureObject): FurnitureObject {
  return { ...candidate, id: uid('f') }
}
