import type { ZoneObject, FurnitureObject } from '~/types/office'
import { uid } from './helpers'

export type Arrangement = 'row' | 'backToBack' | 'grid' | 'perimeter'

const DEFAULT_PX_PER_METER = 50
const PADDING = 20
const GAP = 10
const ROW_GAP = 30

function pxPerMeter(zone: ZoneObject): number {
  if (zone.reviewUnit === 'mm') return 1000
  if (zone.pixelToMeter && zone.pixelToMeter > 0) {
    return 1 / zone.pixelToMeter
  }
  return DEFAULT_PX_PER_METER
}

function cmToPx(cm: number, zone: ZoneObject): number {
  return (cm / 100) * pxPerMeter(zone)
}

export interface LayoutRect {
  /** Indivisible topology cell when adapting a reference style to a room. */
  clusterId?: string
  x: number
  y: number
  w: number
  h: number
  rotation: number
  /** Reflect the glyph within its footprint; used by mirrored L desk diagrams. */
  mirrorX?: boolean
}

function clampInside(rect: LayoutRect, zone: ZoneObject): LayoutRect {
  const x = Math.max(zone.x + PADDING, Math.min(rect.x, zone.x + zone.w - PADDING - rect.w))
  const y = Math.max(zone.y + PADDING, Math.min(rect.y, zone.y + zone.h - PADDING - rect.h))
  return { ...rect, x, y }
}

export function computeArrangement(
  zone: ZoneObject,
  unitW: number,
  unitD: number,
  arrangement: Arrangement,
): LayoutRect[] {
  const uw = Math.max(10, cmToPx(unitW, zone))
  const ud = Math.max(10, cmToPx(unitD, zone))
  const availW = Math.max(uw, zone.w - PADDING * 2)
  const availH = Math.max(ud, zone.h - PADDING * 2)

  const rects: LayoutRect[] = []

  if (arrangement === 'row') {
    const perRow = Math.max(1, Math.floor((availW + GAP) / (uw + GAP)))
    const startX = zone.x + PADDING
    const startY = zone.y + PADDING
    for (let i = 0; i < perRow; i++) {
      const x = startX + i * (uw + GAP)
      if (x + uw > zone.x + zone.w - PADDING) break
      rects.push(clampInside({ x, y: startY, w: uw, h: ud, rotation: 0 }, zone))
    }
    return rects
  }

  if (arrangement === 'backToBack') {
    const perRow = Math.max(1, Math.floor((availW + GAP) / (uw + GAP)))
    if (ud * 2 + ROW_GAP > availH) {
      // not enough height, fall back to single row
      return computeArrangement(zone, unitW, unitD, 'row')
    }
    const startX = zone.x + PADDING
    for (let r = 0; r < 2; r++) {
      const y = zone.y + PADDING + r * (ud + ROW_GAP)
      for (let i = 0; i < perRow; i++) {
        const x = startX + i * (uw + GAP)
        if (x + uw > zone.x + zone.w - PADDING) break
        rects.push(clampInside({ x, y, w: uw, h: ud, rotation: 0 }, zone))
      }
    }
    return rects
  }

  if (arrangement === 'grid') {
    const perRow = Math.max(1, Math.floor((availW + GAP) / (uw + GAP)))
    const rows = Math.max(1, Math.floor((availH + GAP) / (ud + GAP)))
    const startX = zone.x + PADDING
    const startY = zone.y + PADDING
    for (let r = 0; r < rows; r++) {
      const y = startY + r * (ud + GAP)
      if (y + ud > zone.y + zone.h - PADDING) break
      for (let i = 0; i < perRow; i++) {
        const x = startX + i * (uw + GAP)
        if (x + uw > zone.x + zone.w - PADDING) break
        rects.push(clampInside({ x, y, w: uw, h: ud, rotation: 0 }, zone))
      }
    }
    return rects
  }

  if (arrangement === 'perimeter') {
    // top + bottom
    const perRow = Math.max(1, Math.floor((availW + GAP) / (uw + GAP)))
    for (let i = 0; i < perRow; i++) {
      const x = zone.x + PADDING + i * (uw + GAP)
      if (x + uw > zone.x + zone.w - PADDING) break
      rects.push(clampInside({ x, y: zone.y + PADDING, w: uw, h: ud, rotation: 0 }, zone))
      rects.push(clampInside({ x, y: zone.y + zone.h - PADDING - ud, w: uw, h: ud, rotation: 0 }, zone))
    }
    // left + right (rotate so width runs along wall)
    const perCol = Math.max(1, Math.floor((availH + GAP) / (uw + GAP)))
    for (let i = 0; i < perCol; i++) {
      const y = zone.y + PADDING + i * (uw + GAP)
      if (y + uw > zone.y + zone.h - PADDING) break
      rects.push(clampInside({ x: zone.x + PADDING, y, w: ud, h: uw, rotation: 90 }, zone))
      rects.push(clampInside({ x: zone.x + zone.w - PADDING - ud, y, w: ud, h: uw, rotation: 90 }, zone))
    }
    return rects
  }

  return rects
}

export function estimateCapacity(zone: ZoneObject, unitW: number, unitD: number, arrangement: Arrangement): number {
  return computeArrangement(zone, unitW, unitD, arrangement).length
}

export function createFurnitureObjects(
  zone: ZoneObject,
  productId: string,
  productName: string,
  unitW: number,
  unitD: number,
  arrangement: Arrangement,
): FurnitureObject[] {
  const rects = computeArrangement(zone, unitW, unitD, arrangement)
  return rects.map((r, i) => ({
    id: uid('f'),
    kind: 'furniture',
    x: r.x,
    y: r.y,
    w: r.w,
    h: r.h,
    rotation: r.rotation,
    productId,
    zoneId: zone.id,
    label: `${productName} ${i + 1}`,
  }))
}
