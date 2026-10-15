import type { FurnitureObject, ZoneObject } from '~/types/office'
import type { OfficeModuleTemplate } from '~/types/officeModule'
import type { OfficeLayoutSpacing } from '~/utils/officeAutoLayout'
import { layoutRectInsideRing } from '~/utils/officeAutoLayout'
import { uid } from '~/utils/helpers'

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

export function officePlacementProblem(zone: ZoneObject, candidate: FurnitureObject, existing: FurnitureObject[], spacing: OfficeLayoutSpacing): string | null {
  if (zone.reviewUnit !== 'mm' || candidate.zoneId !== zone.id) return '请在当前已校准空间内放置'
  if (![candidate.x, candidate.y, candidate.w, candidate.h, candidate.rotation, spacing.wallMm, spacing.gapMm].every(Number.isFinite)
    || candidate.w < 10 || candidate.h < 10 || spacing.wallMm < 0 || spacing.gapMm < 0) return '家具尺寸或间距无效'
  const footprint = officeFurnitureFootprint(candidate)
  if (!layoutRectInsideRing({ x: footprint.x - spacing.wallMm, y: footprint.y - spacing.wallMm,
    w: footprint.w + spacing.wallMm * 2, h: footprint.h + spacing.wallMm * 2 }, zone.polygon || [])) return '此位置超出空间或离墙距离不足'
  for (const item of existing) {
    if (item.zoneId !== zone.id) continue
    const other = officeFurnitureFootprint(item), gap = spacing.gapMm, eps = 0.001
    if (footprint.x < other.x + other.w + gap - eps && other.x < footprint.x + footprint.w + gap - eps
      && footprint.y < other.y + other.h + gap - eps && other.y < footprint.y + footprint.h + gap - eps) return '此位置与已有家具重叠或间距不足'
  }
  return null
}

export function identifyManualFurniture(candidate: FurnitureObject): FurnitureObject {
  return { ...candidate, id: uid('f') }
}
