import type { ZoneObject } from '~/types/office'

export interface SnapRect { x: number; y: number; w: number; h: number }
export interface FurnitureSnapResult { x: number; y: number; guideX: number | null; guideY: number | null }

/**
 * Snap distance for manual furniture arranging. 50mm is about 3–4% of a
 * workstation module and roughly 5 screen pixels at overview zoom: easy to
 * feel, hard to trigger by accident. Obstacles keep their finer 20mm snap.
 */
export const FURNITURE_SNAP_TOLERANCE_MM = 50

/**
 * Snap a furniture footprint to zone walls and to other modules while
 * arranging manually. Per axis the nearest candidate within the tolerance
 * wins: any axis-aligned wall edge (either side of the piece) and every other
 * footprint's edges (touching with zero gap, or aligning the same edge).
 * Guides identify the wall x/y or module edge that captured the snap.
 */
export function snapFurnitureEdges(rect: SnapRect, zone: ZoneObject, others: SnapRect[], toleranceMm = FURNITURE_SNAP_TOLERANCE_MM): FurnitureSnapResult {
  const result: FurnitureSnapResult = { x: rect.x, y: rect.y, guideX: null, guideY: null }
  if (zone.reviewUnit !== 'mm' || !zone.polygon?.length || !Number.isFinite(toleranceMm) || toleranceMm <= 0) return result
  let bestX = Infinity, bestY = Infinity
  const offerX = (delta: number, guide: number) => { const d = Math.abs(delta); if (d <= toleranceMm && d < bestX) { bestX = d; result.x = rect.x + delta; result.guideX = guide } }
  const offerY = (delta: number, guide: number) => { const d = Math.abs(delta); if (d <= toleranceMm && d < bestY) { bestY = d; result.y = rect.y + delta; result.guideY = guide } }
  const ring = zone.polygon
  for (let i = 0; i < ring.length; i++) {
    const a = ring[i]!, b = ring[(i + 1) % ring.length]!
    if (Math.abs(a[0]! - b[0]!) < 0.001 && Math.abs(a[1]! - b[1]!) > 1) {
      const minY = Math.min(a[1]!, b[1]!), maxY = Math.max(a[1]!, b[1]!)
      if (rect.y + rect.h < minY || rect.y > maxY) continue
      offerX(a[0]! - rect.x, a[0]!)
      offerX(a[0]! - (rect.x + rect.w), a[0]!)
    } else if (Math.abs(a[1]! - b[1]!) < 0.001 && Math.abs(a[0]! - b[0]!) > 1) {
      const minX = Math.min(a[0]!, b[0]!), maxX = Math.max(a[0]!, b[0]!)
      if (rect.x + rect.w < minX || rect.x > maxX) continue
      offerY(a[1]! - rect.y, a[1]!)
      offerY(a[1]! - (rect.y + rect.h), a[1]!)
    }
  }
  for (const other of others) {
    offerX(other.x - (rect.x + rect.w), other.x)
    offerX(other.x + other.w - rect.x, other.x + other.w)
    offerX(other.x - rect.x, other.x)
    offerX(other.x + other.w - (rect.x + rect.w), other.x + other.w)
    offerY(other.y - (rect.y + rect.h), other.y)
    offerY(other.y + other.h - rect.y, other.y + other.h)
    offerY(other.y - rect.y, other.y)
    offerY(other.y + other.h - (rect.y + rect.h), other.y + other.h)
  }
  return result
}
