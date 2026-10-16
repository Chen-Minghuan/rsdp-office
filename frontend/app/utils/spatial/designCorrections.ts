import type { ZoneObject } from '~/types/office'
import type { FloorPlanAnalysisResponse } from '~/types/floorPlan'
import { ringProblem, ringsOverlap } from '~/utils/spatial/review'

/** Reapply source-page manual corrections to a new design copy, never to sourcePolygon. */
export function applyDesignCorrections(zones: ZoneObject[], plan: FloorPlanAnalysisResponse): number {
  const unit = plan.unitResolution as { canonicalUnit?: string } | null
  if (plan.inputMode === 'cad' || unit?.canonicalUnit !== 'px' || !/^[a-f0-9]{64}$/i.test(plan.imageSha256 || '')) return 0
  const key = (name: string) => name.replace(/\s+/g, '')
  const corrections = plan.designCorrections || []
  const bounds = plan.previewBounds
  if (!bounds) return 0
  let count = 0
  for (const zone of zones) {
    const name = key(zone.name)
    if (!name || zones.filter(other => key(other.name) === name).length !== 1) continue
    const matches = corrections.filter(correction => key(correction.name) === name)
    if (matches.length !== 1) continue
    const correction = matches[0]
    const ring = correction.polygon
    if (!Array.isArray(ring) || ring.length > 256
      || ring.some(point => !Array.isArray(point) || point.length !== 2 || point.some(value => !Number.isFinite(value))) || ringProblem(ring)
      || ring.some(([x, y]) => x < bounds.minX || x > bounds.maxX || y < bounds.minY || y > bounds.maxY)) continue
    // A historic misplaced manual box must not displace a freshly label-grounded enclosed room.
    // Open functional partitions have no such closed-room evidence and retain their manual boundaries.
    if (plan.rooms.find(room => room.roomId === zone.id)?.contourGrounded
      && !ringsOverlap(ring, zone.sourcePolygon || [])) continue
    const xs = ring.map(point => point[0]), ys = ring.map(point => point[1])
    Object.assign(zone, {
      polygon: ring.map(([x, y]) => [x, y]),
      x: Math.min(...xs), y: Math.min(...ys), w: Math.max(...xs) - Math.min(...xs), h: Math.max(...ys) - Math.min(...ys),
      source: 'user_annotation', geometrySource: 'manual_geometry', evidenceKind: 'MANUAL', confirmed: false,
      contourCorrection: { sourceAnalysisId: correction.sourceAnalysisId, sourceAnalysisRevision: correction.sourceAnalysisRevision, canvasRevision: correction.canvasRevision },
    })
    delete zone.confirmedAt
    count++
  }
  return count
}
