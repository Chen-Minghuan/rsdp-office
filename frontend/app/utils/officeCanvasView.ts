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
export function fitCanvasZone(zone: ZoneObject, world: { minX: number; minY: number; width: number; height: number }, viewportWidth: number, viewportHeight: number, padding = 80, rotation = 0) {
  const bounds = canvasZoneBounds(zone)
  const angle = rotation * Math.PI / 180
  const w = bounds.maxX - bounds.minX + padding * 2, h = bounds.maxY - bounds.minY + padding * 2
  const spanX = Math.abs(Math.cos(angle)) * w + Math.abs(Math.sin(angle)) * h
  const spanY = Math.abs(Math.sin(angle)) * w + Math.abs(Math.cos(angle)) * h
  const offset = rotateCanvasVector({ x: world.width / 2 - ((bounds.minX + bounds.maxX) / 2 - world.minX), y: world.height / 2 - ((bounds.minY + bounds.maxY) / 2 - world.minY) }, rotation)
  const zoom = Math.max(0.00001, Math.min(1.6,
    (viewportWidth - 40) / spanX,
    (viewportHeight - 40) / spanY))
  return {
    zoom,
    panX: offset.x * zoom,
    panY: offset.y * zoom,
  }
}

export function rotateCanvasVector(point: { x: number; y: number }, degrees: number) {
  const angle = degrees * Math.PI / 180
  return { x: point.x * Math.cos(angle) - point.y * Math.sin(angle), y: point.x * Math.sin(angle) + point.y * Math.cos(angle) }
}

/** Pointer inversion uses the same center/rotation as CSS; pan remains in screen pixels. */
export function canvasPointerWorld(point: { x: number; y: number }, viewport: { width: number; height: number }, world: { minX: number; minY: number; width: number; height: number }, camera: { zoom: number; panX: number; panY: number }, rotation = 0) {
  const local = rotateCanvasVector({ x: (point.x - viewport.width / 2 - camera.panX) / camera.zoom, y: (point.y - viewport.height / 2 - camera.panY) / camera.zoom }, -rotation)
  return { x: local.x + world.minX + world.width / 2, y: local.y + world.minY + world.height / 2 }
}
