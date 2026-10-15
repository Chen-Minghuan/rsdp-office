import type { FurnitureObject, Product, ZoneObject } from '~/types/office'
import type { FurnitureTemplate, ManualFurnitureItem } from '~/types/manualCanvas'
import { planOfficeFurniture, OFFICE_LAYOUT_SPACING } from '~/utils/officeAutoLayout'
import type { OfficeLayoutMode, OfficeLayoutSpacing } from '~/utils/officeAutoLayout'
import type { LayoutRect } from '~/utils/layout'
import { furnitureById } from '~/utils/manualFurniture'
import { uid } from '~/utils/helpers'
import type { OfficeModuleTemplate } from '~/types/officeModule'

/** Local SVG coordinates; the canvas applies the instance position and rotation. */
export function furnitureGlyphItem(template: FurnitureTemplate, widthMm = template.widthMm, depthMm = template.depthMm): ManualFurnitureItem {
  return {
    id: `glyph-${template.id}`, templateId: template.id, roomClientId: '',
    xMm: widthMm / 2, yMm: depthMm / 2, widthMm, depthMm, rotationDeg: 0,
    wingWidthMm: 550, heightMm: template.heightMm,
  }
}

/** Keep old product-bound instances readable without rewriting their saved data. */
export function officeFurnitureTemplateId(item: FurnitureObject, product?: Product): string | undefined {
  if (item.moduleSnapshot && furnitureById.has(item.moduleSnapshot.glyphTemplateId)) return item.moduleSnapshot.glyphTemplateId
  if (item.furnitureTemplateId && furnitureById.has(item.furnitureTemplateId)) return item.furnitureTemplateId
  const legacy: Partial<Record<Product['kind'], string>> = {
    workstation: 'desk-straight', desk: 'desk-executive', chair: 'chair-office',
    meeting: 'table-conference-rect', sofa: 'sofa-double', cabinet: 'cabinet-mobile',
  }
  return product ? legacy[product.kind] : undefined
}

export function furnitureTemplateArrangement(zone: ZoneObject, widthMm: number, depthMm: number, arrangement: OfficeLayoutMode, spacing?: OfficeLayoutSpacing) {
  return planOfficeFurniture(zone, widthMm, depthMm, arrangement, spacing).slots
}

/** Compare actual instances, including manual moves, to the unapplied preview. */
export function furnitureMatchesPlan(items: FurnitureObject[], templateId: string, widthMm: number, depthMm: number, slots: LayoutRect[], glyphTemplateId?: string, heightMm?: number): boolean {
  if (!slots.length || items.length !== slots.length) return false
  const near = (a: number, b: number) => Math.abs(a - b) < 0.01 // mm; stored arithmetic tolerance
  return items.every((item, index) => {
    const slot = slots[index]!
    const turned = item.rotation === 90 || item.rotation === 270
    const w = turned ? item.h : item.w, h = turned ? item.w : item.h
    return (item.heightMm ?? null) === (heightMm ?? null) && item.furnitureTemplateId === templateId && (!glyphTemplateId || officeFurnitureTemplateId(item) === glyphTemplateId) && near(item.w, widthMm) && near(item.h, depthMm)
      && item.rotation === slot.rotation && near(w, slot.w) && near(h, slot.h)
      && near(item.x + (item.w - w) / 2, slot.x) && near(item.y + (item.h - h) / 2, slot.y)
  })
}

export function createTemplateFurniture(zone: ZoneObject, template: OfficeModuleTemplate, widthMm: number, depthMm: number, arrangement: OfficeLayoutMode, spacing?: OfficeLayoutSpacing): FurnitureObject[] {
  return furnitureTemplateArrangement(zone, widthMm, depthMm, arrangement, spacing).map((slot, index) => {
    // Slots describe the rotated footprint; stored width/depth describe the actual object.
    const turned = slot.rotation === 90 || slot.rotation === 270
    const w = turned ? slot.h : slot.w, h = turned ? slot.w : slot.h
    return {
      id: uid('f'), kind: 'furniture', furnitureTemplateId: template.id,
      ...('custom' in template ? { moduleSnapshot: { ...template } } : {}),
      x: slot.x + (slot.w - w) / 2, y: slot.y + (slot.h - h) / 2,
      w, h, rotation: slot.rotation, productId: '', zoneId: zone.id,
      label: `${template.name} ${index + 1}`, wingWidthMm: 550, heightMm: template.heightMm,
      layoutConfirmed: false, layoutSettings: { mode: arrangement, spacing: { ...(spacing || OFFICE_LAYOUT_SPACING) } },
    }
  })
}
