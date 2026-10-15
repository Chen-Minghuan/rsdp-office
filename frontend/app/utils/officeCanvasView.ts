import type { OfficeObject, ZoneObject } from '~/types/office'
import { polygonBoundsMm } from '~/utils/floorPlan'

/** A view filters the scene without deleting or relocating design data. */
export function canvasObjectsForView(objects: OfficeObject[], mode: 'overview' | 'design', zoneId: string | null): OfficeObject[] {
  if (mode === 'overview') return objects
  if (!zoneId || !objects.some(object => object.kind === 'zone' && object.id === zoneId)) return []
  return objects.filter(object => object.kind === 'zone' ? object.id === zoneId : 'zoneId' in object && object.zoneId === zoneId)
}

export function canvasZoneBounds(zone: ZoneObject) {
  return polygonBoundsMm(zone.polygon) || { minX: zone.x, minY: zone.y, maxX: zone.x + zone.w, maxY: zone.y + zone.h }
}

/** Camera values are screen pixels; all bounds and geometry remain in world units. */
export function fitCanvasZone(zone: ZoneObject, world: { minX: number; minY: number; width: number; height: number }, viewportWidth: number, viewportHeight: number, padding = 80) {
  const bounds = canvasZoneBounds(zone)
  const zoom = Math.max(0.00001, Math.min(1.6,
    (viewportWidth - 40) / (bounds.maxX - bounds.minX + padding * 2),
    (viewportHeight - 40) / (bounds.maxY - bounds.minY + padding * 2)))
  return {
    zoom,
    panX: (world.width / 2 - ((bounds.minX + bounds.maxX) / 2 - world.minX)) * zoom,
    panY: (world.height / 2 - ((bounds.minY + bounds.maxY) / 2 - world.minY)) * zoom,
  }
}
