import type { FurnitureObject, ZoneObject } from '~/types/office'
import { layoutRectInsideRing } from '~/utils/officeAutoLayout'
import { officeFurnitureFootprint } from '~/utils/officeManualPlacement'
import { canvasZoneBounds } from '~/utils/officeCanvasView'

export interface OfficeDimensionBounds {
  minX: number
  minY: number
  maxX: number
  maxY: number
  width: number
  height: number
}

export interface OfficeDimensionRow {
  id: string
  minX: number
  maxX: number
  minY: number
  maxY: number
  count: number
  gapFromPreviousMm: number | null
}

/**
 * Returns the axis-aligned footprint actually visible after a module rotates.
 * Furniture x/y/w/h describe the unrotated object; the planner's slot is the
 * rotated footprint centered on that object.
 */
export function officeFurnitureFootprintBounds(item: Pick<FurnitureObject, 'x' | 'y' | 'w' | 'h' | 'rotation'>) {
  return officeFurnitureFootprint(item)
}

export function officeDimensionBounds(zone: ZoneObject): OfficeDimensionBounds {
  const bounds = canvasZoneBounds(zone)
  return { ...bounds, width: Math.max(0, bounds.maxX - bounds.minX), height: Math.max(0, bounds.maxY - bounds.minY) }
}

/**
 * Groups modules by their visible horizontal bands so each generated row can
 * carry a total-length and inter-row-clearance annotation.
 */
export function officeDimensionRows(items: FurnitureObject[]): OfficeDimensionRow[] {
  const sorted = items
    .map(item => ({ item, footprint: officeFurnitureFootprintBounds(item) }))
    .sort((a, b) => a.footprint.y - b.footprint.y || a.footprint.x - b.footprint.x)
  const rows: Array<OfficeDimensionRow & { centerY: number }> = []

  for (const entry of sorted) {
    const centerY = entry.footprint.y + entry.footprint.h / 2
    const row = rows.find(candidate => Math.abs(candidate.centerY - centerY) <= Math.max(candidate.maxY - candidate.minY, entry.footprint.h) * 0.45)
    if (!row) {
      rows.push({
        id: `dimension-row-${rows.length + 1}`,
        minX: entry.footprint.x,
        maxX: entry.footprint.x + entry.footprint.w,
        minY: entry.footprint.y,
        maxY: entry.footprint.y + entry.footprint.h,
        count: 1,
        gapFromPreviousMm: null,
        centerY,
      })
      continue
    }
    row.minX = Math.min(row.minX, entry.footprint.x)
    row.maxX = Math.max(row.maxX, entry.footprint.x + entry.footprint.w)
    row.minY = Math.min(row.minY, entry.footprint.y)
    row.maxY = Math.max(row.maxY, entry.footprint.y + entry.footprint.h)
    row.centerY = (row.minY + row.maxY) / 2
    row.count += 1
  }

  rows.sort((a, b) => a.minY - b.minY)
  rows.forEach((row, index) => {
    row.gapFromPreviousMm = index === 0 ? null : Math.max(0, row.minY - rows[index - 1]!.maxY)
  })
  return rows.map(({ centerY: _centerY, ...row }) => row)
}

export function formatDimensionMm(value: number): string {
  const rounded = Math.round(value)
  return `${rounded.toLocaleString('zh-CN')} mm`
}

export interface OfficeClearanceDimension {
  id: string
  kind: 'wall' | 'group'
  axis: 'x' | 'y'
  x1: number; y1: number; x2: number; y2: number
  distanceMm: number
  label: string
  occurrences?: number
}

/** Axis rays intersect the actual polygon, including re-entrant walls. No bounding-box wall substitutes. */
export function officeLayoutClearances(zone: ZoneObject, items: FurnitureObject[]): OfficeClearanceDimension[] {
  const ring = zone.polygon || []
  if (zone.reviewUnit !== 'mm' || ring.length < 3 || !items.length) return []
  const bounds = canvasZoneBounds(zone)
  const rects = items.map(officeFurnitureFootprintBounds)
  const result: OfficeClearanceDimension[] = []
  const sides = [ ['left', 'x', -1, '左侧离墙'], ['right', 'x', 1, '右侧离墙'], ['top', 'y', -1, '上侧离墙'], ['bottom', 'y', 1, '下侧离墙'] ] as const
  for (const [side, axis, sign, label] of sides) {
    let best: OfficeClearanceDimension | null = null
    for (const rect of rects) {
      const lo = axis === 'x' ? rect.y : rect.x, hi = lo + (axis === 'x' ? rect.h : rect.w)
      const start = axis === 'x' ? rect.x + (sign > 0 ? rect.w : 0) : rect.y + (sign > 0 ? rect.h : 0)
      const samples = [(lo + hi) / 2, lo, hi, ...ring.map(p => axis === 'x' ? p[1] : p[0]).filter(v => v > lo && v < hi)]
      for (const cross of samples) {
        let nearest = Infinity
        for (let i = 0; i < ring.length; i++) {
          const a = ring[i]!, b = ring[(i + 1) % ring.length]!
          const ac = axis === 'x' ? a[1] : a[0], bc = axis === 'x' ? b[1] : b[0]
          const av = axis === 'x' ? a[0] : a[1], bv = axis === 'x' ? b[0] : b[1]
          if (Math.abs(bc - ac) < 0.001) continue
          const t = (cross - ac) / (bc - ac)
          if (t < -0.000001 || t > 1.000001) continue
          const distance = (av + (bv - av) * t - start) * sign
          if (distance >= -0.001) nearest = Math.min(nearest, Math.max(0, distance))
        }
        if (!Number.isFinite(nearest)) continue
        const middle = axis === 'x' ? (bounds.minY + bounds.maxY) / 2 : (bounds.minX + bounds.maxX) / 2
        if (best && (nearest > best.distanceMm + 0.001 || Math.abs(nearest - best.distanceMm) < 0.001 && Math.abs(cross - middle) >= Math.abs((axis === 'x' ? best.y1 : best.x1) - middle))) continue
        const end = start + sign * nearest
        best = { id: side, kind: 'wall', axis, x1: axis === 'x' ? start : cross, y1: axis === 'x' ? cross : start, x2: axis === 'x' ? end : cross, y2: axis === 'x' ? cross : end, distanceMm: nearest, label }
      }
    }
    if (best) result.push(best)
  }
  // Nearest neighbors must share an actual cross-section. Touching modules are a single group.
  const corridors = new Map<string, OfficeClearanceDimension>()
  for (const axis of ['x', 'y'] as const) for (const a of rects) {
    let best: OfficeClearanceDimension | null = null
    for (const b of rects) {
      const start = axis === 'x' ? a.x + a.w : a.y + a.h, end = axis === 'x' ? b.x : b.y
      if (end < start - 0.001) continue
      const lo = Math.max(axis === 'x' ? a.y : a.x, axis === 'x' ? b.y : b.x)
      const hi = Math.min(axis === 'x' ? a.y + a.h : a.x + a.w, axis === 'x' ? b.y + b.h : b.x + b.w)
      if (hi - lo < 0.001) continue
      if (best && end - start >= best.distanceMm) continue
      const cross = (lo + hi) / 2
      if (end - start > 0.001 && !layoutRectInsideRing(axis === 'x' ? {x:start,y:cross-0.001,w:end-start,h:0.002} : {x:cross-0.001,y:start,w:0.002,h:end-start}, ring)) continue
      best = { id: '', kind: 'group', axis, x1: axis === 'x' ? start : cross, y1: axis === 'x' ? cross : start, x2: axis === 'x' ? end : cross, y2: axis === 'x' ? cross : end, distanceMm: Math.max(0, end - start), label: '组间净距' }
    }
    if (!best || best.distanceMm < 0.001) continue
    const key = axis + ':' + Math.round((axis === 'x' ? best.x1 : best.y1) * 1000) + ':' + Math.round((axis === 'x' ? best.x2 : best.y2) * 1000)
    if (!corridors.has(key)) corridors.set(key, { ...best, id: key })
  }
  return [...result, ...corridors.values()]
}
