import type { ZoneObject } from '~/types/office'
import type { FloorPlanAnalysisResponse } from '~/types/floorPlan'
import { ringProblem } from '~/utils/spatial/review'

/** Source pixels. Compare edges as well as corners so an overlapping, truncated box is rejected. */
function boundariesAgree(a: Array<[number, number]>, b: Array<[number, number]>, tolerance = 10): boolean {
  if (a.length < 3 || b.length < 3) return false
  const near = (p: number[], ring: Array<[number, number]>) => ring.some((q, i) => {
    const r = ring[(i + 1) % ring.length], dx = r[0] - q[0], dy = r[1] - q[1]
    const length2 = dx * dx + dy * dy
    const t = length2 ? Math.max(0, Math.min(1, ((p[0] - q[0]) * dx + (p[1] - q[1]) * dy) / length2)) : 0
    return Math.hypot(p[0] - q[0] - t * dx, p[1] - q[1] - t * dy) <= tolerance
  })
  const follows = (ring: Array<[number, number]>, other: Array<[number, number]>) => ring.every((p, i) => {
    const q = ring[(i + 1) % ring.length], steps = Math.max(1, Math.ceil(Math.hypot(q[0] - p[0], q[1] - p[1]) / 5))
    for (let j = 0; j <= steps; j++) if (!near([p[0] + (q[0] - p[0]) * j / steps, p[1] + (q[1] - p[1]) * j / steps], other)) return false
    return true
  })
  return follows(a, b) && follows(b, a)
}

/** sourcePolygon already uses the design copy's Y direction; calibration only scales it. */
export function sourceContour(zone: ZoneObject): Array<[number, number]> | null {
  const factor = zone.reviewUnit === 'mm' ? zone.mmPerSourceUnit : 1
  if (!zone.sourcePolygon?.length || !Number.isFinite(factor) || Number(factor) <= 0) return null
  const ring = zone.sourcePolygon.map(([x, y]): [number, number] => [x * Number(factor), y * Number(factor)])
  return ringProblem(ring) ? null : ring
}

export function canRestoreSourceContour(zone: ZoneObject): boolean {
  const ring = sourceContour(zone)
  return !!ring && !boundariesAgree(zone.polygon || [], ring, .01)
}

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
    // A historic box can overlap the correct room and still truncate its entrance or full depth.
    // Keep that history available without silently replacing newly wall-grounded geometry.
    if (plan.rooms.find(room => room.roomId === zone.id)?.contourGrounded
      && !boundariesAgree(ring, zone.sourcePolygon || [])) {
      zone.note = '历史编辑与本次墙线轮廓不一致，未自动套用；历史草稿已保留'
      continue
    }
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
