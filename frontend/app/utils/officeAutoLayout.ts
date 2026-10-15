import type { ZoneObject } from '~/types/office'
import type { Arrangement, LayoutRect } from '~/utils/layout'
import { polygonBoundsMm } from '~/utils/floorPlan'

export type OfficeLayoutMode = Arrangement | 'auto'
export interface OfficeLayoutSpacing { wallMm: number; aisleMm: number; gapMm: number }
export const OFFICE_LAYOUT_SPACING: OfficeLayoutSpacing = { wallMm: 300, aisleMm: 900, gapMm: 100 }
export interface OfficeLayoutPlan { slots: LayoutRect[]; rows: number; columns: number; rotated: boolean; problem: string | null }
const EPS = 0.0001 // millimetres; boundary arithmetic only
const MAX_CELLS = 2000
type Rect = { x: number; y: number; w: number; h: number }

function pointInRing(x: number, y: number, ring: number[][]) {
  let inside = false
  for (let i = 0; i < ring.length; i++) {
    const a = ring[i]!, b = ring[(i + 1) % ring.length]!
    const length = Math.hypot(b[0]! - a[0]!, b[1]! - a[1]!)
    const cross = (b[0]! - a[0]!) * (y - a[1]!) - (b[1]! - a[1]!) * (x - a[0]!)
    if (Math.abs(cross) <= EPS * length && x >= Math.min(a[0]!, b[0]!) - EPS && x <= Math.max(a[0]!, b[0]!) + EPS && y >= Math.min(a[1]!, b[1]!) - EPS && y <= Math.max(a[1]!, b[1]!) + EPS) return true
    if ((a[1]! > y) !== (b[1]! > y) && x < a[0]! + (y - a[1]!) * (b[0]! - a[0]!) / (b[1]! - a[1]!)) inside = !inside
  }
  return inside
}

/** Reject concave notches even when all four rectangle corners happen to be inside. */
export function layoutRectInsideRing(rect: Rect, ring: Array<[number, number]>): boolean {
  if (ring.length < 3 || ![[rect.x, rect.y], [rect.x + rect.w, rect.y], [rect.x + rect.w, rect.y + rect.h], [rect.x, rect.y + rect.h]].every(([x, y]) => pointInRing(x!, y!, ring))) return false
  for (let i = 0; i < ring.length; i++) {
    const a = ring[i]!, b = ring[(i + 1) % ring.length]!
    let start = 0, end = 1
    for (const [p, delta, min, max] of [
      [a[0], b[0] - a[0], rect.x + EPS, rect.x + rect.w - EPS],
      [a[1], b[1] - a[1], rect.y + EPS, rect.y + rect.h - EPS],
    ]) {
      if (Math.abs(delta!) < EPS) { if (p! < min! || p! > max!) { end = -1; break } }
      else {
        const t1 = (min! - p!) / delta!, t2 = (max! - p!) / delta!
        start = Math.max(start, Math.min(t1, t2)); end = Math.min(end, Math.max(t1, t2))
      }
    }
    if (start <= end) return false
  }
  return true
}

function overlaps(a: Rect, b: Rect, gap = 0) {
  return a.x < b.x + b.w + gap - EPS && b.x < a.x + a.w + gap - EPS && a.y < b.y + b.h + gap - EPS && b.y < a.y + a.h + gap - EPS
}

export function planOfficeFurniture(zone: ZoneObject, widthMm: number, depthMm: number, mode: OfficeLayoutMode, spacing: OfficeLayoutSpacing = OFFICE_LAYOUT_SPACING): OfficeLayoutPlan {
  const empty = (problem: string | null): OfficeLayoutPlan => ({ slots: [], rows: 0, columns: 0, rotated: false, problem })
  if (zone.reviewUnit !== 'mm' || ![widthMm, depthMm].every(value => Number.isFinite(value) && value >= 10)) return empty('请填写有效的家具毫米尺寸')
  if (![spacing.wallMm, spacing.aisleMm, spacing.gapMm].every(value => Number.isFinite(value) && value >= 0)) return empty('间距必须为有效的非负毫米数')
  const bounds = polygonBoundsMm(zone.polygon)
  if (!bounds || !Object.values(bounds).every(Number.isFinite)) return empty('空间缺少有效轮廓')
  const spanX = bounds.maxX - bounds.minX, spanY = bounds.maxY - bounds.minY
  const fit = (length: number, item: number, gap: number) => Math.max(0, Math.floor((length + gap + EPS) / (item + gap)))
  const valid = (slot: LayoutRect) => layoutRectInsideRing({ x: slot.x - spacing.wallMm, y: slot.y - spacing.wallMm, w: slot.w + 2 * spacing.wallMm, h: slot.h + 2 * spacing.wallMm }, zone.polygon!)

  if (mode === 'perimeter') {
    const nx = fit(spanX - 2 * spacing.wallMm, widthMm, spacing.gapMm)
    const ny = fit(spanY - 2 * spacing.wallMm, widthMm, spacing.gapMm)
    if (2 * (nx + ny) > MAX_CELLS) return empty('预计数量过多，请增大家具尺寸或间距')
    const slots: LayoutRect[] = []
    const add = (slot: LayoutRect) => { if (valid(slot) && !slots.some(other => overlaps(slot, other, spacing.gapMm))) slots.push(slot) }
    for (let i = 0; i < nx; i++) {
      const x = bounds.minX + (spanX - nx * widthMm - (nx - 1) * spacing.gapMm) / 2 + i * (widthMm + spacing.gapMm)
      add({ x, y: bounds.minY + spacing.wallMm, w: widthMm, h: depthMm, rotation: 0 })
      add({ x, y: bounds.maxY - spacing.wallMm - depthMm, w: widthMm, h: depthMm, rotation: 180 })
    }
    for (let i = 0; i < ny; i++) {
      const y = bounds.minY + (spanY - ny * widthMm - (ny - 1) * spacing.gapMm) / 2 + i * (widthMm + spacing.gapMm)
      add({ x: bounds.minX + spacing.wallMm, y, w: depthMm, h: widthMm, rotation: 90 })
      add({ x: bounds.maxX - spacing.wallMm - depthMm, y, w: depthMm, h: widthMm, rotation: 270 })
    }
    return { slots, rows: 0, columns: 0, rotated: false, problem: slots.length ? null : '当前家具尺寸和间距无法放入此空间' }
  }

  let best = empty(null), exceeded = false
  for (const rotated of mode === 'auto' ? [false, true] : [false]) {
    const localW = rotated ? spanY : spanX, localH = rotated ? spanX : spanY
    const cols = fit(localW - 2 * spacing.wallMm, widthMm, spacing.gapMm)
    const paired = mode === 'backToBack'
    const blockH = paired ? depthMm * 2 + spacing.gapMm : depthMm
    const blocks = mode === 'row' ? Math.min(1, fit(localH - 2 * spacing.wallMm, blockH, spacing.aisleMm)) : fit(localH - 2 * spacing.wallMm, blockH, spacing.aisleMm)
    if (!cols || !blocks) continue
    if (cols * blocks * (paired ? 2 : 1) > MAX_CELLS) { exceeded = true; continue }
    const usedW = cols * widthMm + (cols - 1) * spacing.gapMm
    const usedH = blocks * blockH + (blocks - 1) * spacing.aisleMm
    // Center first; edge-aligned alternatives help retain slots in concave rooms.
    for (const align of zone.polygon!.length > 4 ? [0.5, 0, 1] : [0.5]) {
      const slots: LayoutRect[] = [], rows = new Set<number>(), columns = new Set<number>()
      const x0 = spacing.wallMm + (localW - 2 * spacing.wallMm - usedW) * align
      const y0 = spacing.wallMm + (localH - 2 * spacing.wallMm - usedH) * align
      for (let row = 0; row < blocks * (paired ? 2 : 1); row++) for (let col = 0; col < cols; col++) {
        const x = x0 + col * (widthMm + spacing.gapMm)
        const y = y0 + (paired ? Math.floor(row / 2) * (blockH + spacing.aisleMm) + (row % 2) * (depthMm + spacing.gapMm) : row * (depthMm + spacing.aisleMm))
        const slot = rotated
          ? { x: bounds.maxX - y - depthMm, y: bounds.minY + x, w: depthMm, h: widthMm, rotation: 90 }
          : { x: bounds.minX + x, y: bounds.minY + y, w: widthMm, h: depthMm, rotation: paired && row % 2 === 0 ? 180 : 0 }
        if (valid(slot)) { slots.push(slot); rows.add(row); columns.add(col) }
      }
      if (slots.length > best.slots.length) best = { slots, rows: rows.size, columns: columns.size, rotated, problem: null }
    }
  }
  return best.slots.length ? best : empty(exceeded ? '预计数量过多，请增大家具尺寸或间距' : '当前家具尺寸和间距无法放入此空间')
}
