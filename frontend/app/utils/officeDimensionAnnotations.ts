import type { FurnitureObject, ZoneObject } from '~/types/office'
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
  const turned = item.rotation === 90 || item.rotation === 270
  if (!turned) return { x: item.x, y: item.y, w: item.w, h: item.h }
  return {
    x: item.x + (item.w - item.h) / 2,
    y: item.y + (item.h - item.w) / 2,
    w: item.h,
    h: item.w,
  }
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
