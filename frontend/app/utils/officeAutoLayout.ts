import type { ZoneObject } from '~/types/office'
import type { Arrangement, LayoutRect } from '~/utils/layout'
import { polygonBoundsMm } from '~/utils/floorPlan'
import type { OfficeArrangementPresetId, OfficeProductStyleId } from '~/types/officeArrangement'

export type OfficeLayoutMode = Arrangement | 'auto' | 'faceToFace' | 'staggered' | 'centerAisle'
/** 间距字段按几何语义拆开；旧 gapMm/pairMm 保留兼容读取。 */
export interface OfficeLayoutSpacing {
  wallMm: number
  aisleMm: number
  gapMm: number
  faceGapMm?: number
  backGapMm?: number
  pairMm?: number
  moduleGapMm?: number
  endClearanceMm?: number
  seatClearanceMm?: number
  screenGapMm?: number
  cornerGapMm?: number
  staggerOffsetMm?: number
}
export const OFFICE_LAYOUT_SPACING: OfficeLayoutSpacing = { wallMm: 300, aisleMm: 900, gapMm: 0, faceGapMm: 900, backGapMm: 0 }
export interface OfficeLayoutPlan { slots: LayoutRect[]; rows: number; columns: number; rotated: boolean; problem: string | null; presetId?: OfficeArrangementPresetId; productStyleId?: OfficeProductStyleId }
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
  const pairGap = mode === 'faceToFace' ? spacing.faceGapMm ?? spacing.pairMm ?? 900 : spacing.backGapMm ?? spacing.pairMm ?? 0
  if (![spacing.wallMm, spacing.aisleMm, spacing.gapMm, pairGap].every(value => Number.isFinite(value) && value >= 0)) return empty('间距必须为有效的非负毫米数')
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
    let topPlaced = false, bottomPlaced = false
    for (let i = 0; i < nx; i++) {
      const x = bounds.minX + (spanX - nx * widthMm - (nx - 1) * spacing.gapMm) / 2 + i * (widthMm + spacing.gapMm)
      const beforeTop = slots.length
      add({ x, y: bounds.minY + spacing.wallMm, w: widthMm, h: depthMm, rotation: 0 })
      topPlaced ||= slots.length > beforeTop
      const beforeBottom = slots.length
      add({ x, y: bounds.maxY - spacing.wallMm - depthMm, w: widthMm, h: depthMm, rotation: 180 })
      bottomPlaced ||= slots.length > beforeBottom
    }
    const sideMinY = bounds.minY + spacing.wallMm + (topPlaced ? depthMm + spacing.gapMm : 0)
    const sideMaxY = bounds.maxY - spacing.wallMm - (bottomPlaced ? depthMm + spacing.gapMm : 0)
    const sideCount = fit(sideMaxY - sideMinY, widthMm, spacing.gapMm)
    for (let i = 0; i < sideCount; i++) {
      const y = sideMinY + (sideMaxY - sideMinY - sideCount * widthMm - (sideCount - 1) * spacing.gapMm) / 2 + i * (widthMm + spacing.gapMm)
      add({ x: bounds.minX + spacing.wallMm, y, w: depthMm, h: widthMm, rotation: 270 })
      add({ x: bounds.maxX - spacing.wallMm - depthMm, y, w: depthMm, h: widthMm, rotation: 90 })
    }
    return { slots, rows: 0, columns: 0, rotated: false, problem: slots.length ? null : '当前家具尺寸和间距无法放入此空间' }
  }

  if (mode === 'centerAisle') {
    let best = empty(null), exceeded = false
    for (const rotated of [false, true]) {
      const localW = rotated ? spanY : spanX, localH = rotated ? spanX : spanY
      // A central aisle is a left/right pair of banks. Desks turn 90° so their
      // seating sides face the aisle; this is intentionally different from a
      // matrix, which fills horizontal rows without a dedicated aisle.
      const bankWidth = (localW - 2 * spacing.wallMm - spacing.aisleMm) / 2
      const bankCols = fit(bankWidth, depthMm, spacing.gapMm)
      const bankRows = fit(localH - 2 * spacing.wallMm, widthMm, spacing.gapMm)
      if (!bankCols || !bankRows) continue
      if (bankCols * bankRows * 2 > MAX_CELLS) { exceeded = true; continue }
      const usedBankW = bankCols * depthMm + (bankCols - 1) * spacing.gapMm
      const usedH = bankRows * widthMm + (bankRows - 1) * spacing.gapMm
      const xLeft = spacing.wallMm + (bankWidth - usedBankW) / 2
      const xRight = spacing.wallMm + bankWidth + spacing.aisleMm + (bankWidth - usedBankW) / 2
      const y0 = spacing.wallMm + (localH - 2 * spacing.wallMm - usedH) / 2
      for (const align of zone.polygon!.length > 4 ? [0.5, 0, 1] : [0.5]) {
        const slots: LayoutRect[] = [], rows = new Set<number>(), columns = new Set<number>()
        const offsetY = (localH - 2 * spacing.wallMm - usedH) * (align - 0.5)
        for (let bank = 0; bank < 2; bank++) for (let row = 0; row < bankRows; row++) for (let col = 0; col < bankCols; col++) {
          const x = (bank === 0 ? xLeft : xRight) + col * (depthMm + spacing.gapMm)
          const y = y0 + offsetY + row * (widthMm + spacing.gapMm)
          const slot = rotated
            ? { x: bounds.maxX - y - widthMm, y: bounds.minY + x, w: widthMm, h: depthMm, rotation: bank === 0 ? 180 : 0 }
            : { x: bounds.minX + x, y: bounds.minY + y, w: depthMm, h: widthMm, rotation: bank === 0 ? 90 : 270 }
          if (valid(slot)) { slots.push(slot); rows.add(bank * bankRows + row); columns.add(col) }
        }
        if (slots.length > best.slots.length) best = { slots, rows: rows.size, columns: columns.size, rotated, problem: null }
      }
    }
    return best.slots.length ? best : empty(exceeded ? '预计数量过多，请增大家具尺寸或间距' : '当前家具尺寸和间距无法放入此空间')
  }

  let best = empty(null), exceeded = false
  for (const rotated of mode === 'auto' ? [false, true] : [false]) {
    const localW = rotated ? spanY : spanX, localH = rotated ? spanX : spanY
    const cols = fit(localW - 2 * spacing.wallMm, widthMm, spacing.gapMm)
    const paired = mode === 'backToBack' || mode === 'faceToFace'
    const blockH = paired ? depthMm * 2 + pairGap : depthMm
    const blocks = mode === 'row' ? Math.min(1, fit(localH - 2 * spacing.wallMm, blockH, spacing.aisleMm)) : fit(localH - 2 * spacing.wallMm, blockH, spacing.aisleMm)
    if (!cols || !blocks) continue
    if (cols * blocks * (paired ? 2 : 1) > MAX_CELLS) { exceeded = true; continue }
    const usedW = cols * widthMm + (cols - 1) * spacing.gapMm
    const usedH = blocks * blockH + (blocks - 1) * spacing.aisleMm
    // Center first; edge-aligned alternatives help retain slots in concave rooms.
    for (const align of zone.polygon!.length > 4 ? [0.5, 0, 1] : [0.5]) {
      const slots: LayoutRect[] = [], rows = new Set<number>(), columns = new Set<number>()
      const x0 = spacing.wallMm + (localW - 2 * spacing.wallMm - usedW) * align
      const y0 = mode === 'row' ? spacing.wallMm : spacing.wallMm + (localH - 2 * spacing.wallMm - usedH) * align
      for (let row = 0; row < blocks * (paired ? 2 : 1); row++) for (let col = 0; col < cols; col++) {
        const x = x0 + col * (widthMm + spacing.gapMm)
          + (mode === 'staggered' && row % 2 === 1 && cols > 1 ? (widthMm + spacing.gapMm) / 2 : 0)
        const y = y0 + (paired ? Math.floor(row / 2) * (blockH + spacing.aisleMm) + (row % 2) * (depthMm + pairGap) : row * (depthMm + spacing.aisleMm))
        const slot = rotated
          ? { x: bounds.maxX - y - depthMm, y: bounds.minY + x, w: depthMm, h: widthMm, rotation: 90 }
          : { x: bounds.minX + x, y: bounds.minY + y, w: widthMm, h: depthMm, rotation: ((mode === 'faceToFace' && row % 2 === 0) || (mode === 'backToBack' && row % 2 === 1)) ? 180 : 0 }
        if (valid(slot)) { slots.push(slot); rows.add(row); columns.add(col) }
      }
      if (slots.length > best.slots.length) best = { slots, rows: rows.size, columns: columns.size, rotated, problem: null }
    }
  }
  return best.slots.length ? best : empty(exceeded ? '预计数量过多，请增大家具尺寸或间距' : '当前家具尺寸和间距无法放入此空间')
}

/**
 * 会议空间的安全默认排布：以一张会议桌为中心，外围由 wallMm 代表拉椅和通行预留。
 * 会议桌不是职员工位，不能用多排填充算法按房间面积复制成十几张桌子。
 */
export function planMeetingFurniture(
  zone: ZoneObject,
  widthMm: number,
  depthMm: number,
  spacing: OfficeLayoutSpacing = { ...OFFICE_LAYOUT_SPACING, wallMm: 1200, aisleMm: 1200 },
): OfficeLayoutPlan {
  const empty = (problem: string | null): OfficeLayoutPlan => ({ slots: [], rows: 0, columns: 0, rotated: false, problem })
  if (zone.reviewUnit !== 'mm' || ![widthMm, depthMm].every(value => Number.isFinite(value) && value >= 10)) return empty('请填写有效的家具毫米尺寸')
  if (![spacing.wallMm, spacing.aisleMm].every(value => Number.isFinite(value) && value >= 0)) return empty('间距必须为有效的非负毫米数')
  const bounds = polygonBoundsMm(zone.polygon)
  if (!bounds || !Object.values(bounds).every(Number.isFinite)) return empty('空间缺少有效轮廓')
  const spanX = bounds.maxX - bounds.minX, spanY = bounds.maxY - bounds.minY
  const valid = (slot: LayoutRect) => layoutRectInsideRing({ x: slot.x - spacing.wallMm, y: slot.y - spacing.wallMm, w: slot.w + 2 * spacing.wallMm, h: slot.h + 2 * spacing.wallMm }, zone.polygon!)
  const candidates = [
    { w: widthMm, h: depthMm, rotation: 0 },
    { w: depthMm, h: widthMm, rotation: 90 },
  ]
  const slot = candidates
    .map(candidate => ({ ...candidate, x: bounds.minX + (spanX - candidate.w) / 2, y: bounds.minY + (spanY - candidate.h) / 2 }))
    .find(candidate => candidate.w > 0 && candidate.h > 0 && valid(candidate))
  return slot
    ? { slots: [slot], rows: 1, columns: 1, rotated: slot.rotation === 90, problem: null }
    : empty('当前会议桌尺寸和外围通行预留无法放入此空间')
}

/**
 * Material-defined workstation arrangements. These deliberately do not call
 * the generic matrix planner: each preset has its own row/cluster relation so
 * changing the label cannot silently change the geometry.
 */
export function planStrictArrangement(
  zone: ZoneObject,
  widthMm: number,
  depthMm: number,
  productStyleId: OfficeProductStyleId,
  presetId: OfficeArrangementPresetId,
  spacing?: OfficeLayoutSpacing,
): OfficeLayoutPlan {
  const empty = (problem: string | null): OfficeLayoutPlan => ({ slots: [], rows: 0, columns: 0, rotated: false, problem, presetId, productStyleId })
  spacing = { ...OFFICE_LAYOUT_SPACING, ...(STRICT_PRESET_SPACING[presetId] || {}), ...(spacing || {}) }
  if (productStyleId === 'meeting-table' || presetId === 'meeting-center') return empty('会议空间请使用会议桌居中规划')
  if (zone.reviewUnit !== 'mm' || ![widthMm, depthMm].every(value => Number.isFinite(value) && value >= 10)) return empty('请填写有效的家具毫米尺寸')
  if (![spacing.wallMm, spacing.gapMm, spacing.faceGapMm, spacing.aisleMm].every(value => Number.isFinite(value as number) && (value as number) >= 0)) return empty('间距必须为有效的非负毫米数')
  const bounds = polygonBoundsMm(zone.polygon)
  if (!bounds || !Object.values(bounds).every(Number.isFinite)) return empty('空间缺少有效轮廓')
  const spanX = bounds.maxX - bounds.minX, spanY = bounds.maxY - bounds.minY
  const fit = (length: number, item: number, gap: number) => Math.max(0, Math.floor((length + gap + EPS) / (item + gap)))
  const availableWidth = spanX - 2 * spacing.wallMm
  const fittedColumns = fit(availableWidth, widthMm, spacing.gapMm)
  // The material diagrams are fixed composition templates. Do not stretch a
  // three-seat example into ten columns just because the room is wider.
  const diagramColumns = [
    'straight-desk-row', 'straight-desk-face-to-face',
    'l-desk-row', 'l-desk-face-to-face',
    'straight-screen-row', 'straight-screen-face-to-face',
  ].includes(presetId) ? 3 : presetId === 'screen-f-row' ? 4 : fittedColumns
  if (!fittedColumns || fittedColumns < diagramColumns) return empty('当前家具尺寸和离墙距离无法放入该图例排布')
  const columns = diagramColumns
  const valid = (slot: LayoutRect) => layoutRectInsideRing({ x: slot.x - spacing.wallMm, y: slot.y - spacing.wallMm, w: slot.w + 2 * spacing.wallMm, h: slot.h + 2 * spacing.wallMm }, zone.polygon!)
  const addRows = (rows: Array<{ y: number; rotation: number; mirrorX?: boolean; xOffset?: number; count?: number }>, totalHeight: number) => {
    if (!rows.length || totalHeight > spanY - 2 * spacing.wallMm + EPS) return empty('当前默认间距和家具尺寸无法放入此空间')
    const y0 = bounds.minY + spacing.wallMm + (spanY - 2 * spacing.wallMm - totalHeight) / 2
    const usedWidth = columns * widthMm + (columns - 1) * spacing.gapMm
    const x0 = bounds.minX + spacing.wallMm + (spanX - 2 * spacing.wallMm - usedWidth) / 2
    const slots: LayoutRect[] = []
    for (const row of rows) {
      const count = Math.min(columns, row.count ?? columns)
      const offset = row.xOffset ?? 0
      for (let col = 0; col < count; col++) {
        const slot = { x: x0 + offset + col * (widthMm + spacing.gapMm), y: y0 + row.y, w: widthMm, h: depthMm, rotation: row.rotation, ...(row.mirrorX ? { mirrorX: true } : {}) }
        if (valid(slot) && !slots.some(other => overlaps(slot, other))) slots.push(slot)
      }
    }
    const expected = rows.reduce((sum, row) => sum + Math.min(columns, row.count ?? columns), 0)
    if (slots.length !== expected) return empty('当前空间轮廓无法完整容纳该图例排布')
    return { slots, rows: rows.length, columns, rotated: false, problem: null, presetId, productStyleId }
  }

  const groupGap = spacing.faceGapMm ?? spacing.aisleMm ?? 900
  const row = (y: number, rotation = 0, xOffset = 0, count?: number, mirrorX = false) => ({ y, rotation, xOffset, count, mirrorX })
  const twoRowHeight = depthMm * 2 + groupGap
  const fourRowHeight = depthMm * 4 + groupGap

  if (presetId === 'straight-desk-row' || presetId === 'straight-screen-row') {
    // Two same-direction module lines, exactly as the single-row material.
    return addRows([row(0), row(depthMm + groupGap)], depthMm * 2 + groupGap)
  }
  if (presetId === 'l-desk-row') {
    // L desks keep the same return direction in the two parallel lines.
    return addRows([row(0), row(depthMm + groupGap)], depthMm * 2 + groupGap)
  }
  if (presetId === 'l-desk-face-to-face') {
    // The reference has the return panel on the right of both facing desks.
    // Rotate and reflect the upper row; the lower row keeps the base glyph.
    return addRows([row(0, 180, 0, undefined, true), row(depthMm, 0), row(depthMm * 2 + groupGap, 180, 0, undefined, true), row(depthMm * 3 + groupGap, 0)], fourRowHeight)
  }
  if (presetId === 'straight-desk-face-to-face' || presetId === 'straight-screen-face-to-face') {
    // Each face-to-face group is a touching pair; the two groups are separated
    // by the material's 1600/600mm aisle.
    return addRows([row(0, 0), row(depthMm, 180), row(depthMm * 2 + groupGap, 0), row(depthMm * 3 + groupGap, 180)], fourRowHeight)
  }
  if (presetId === 'screen-f-row') {
    // F: repeated L card positions on two continuous, parallel screen lines.
    return addRows([row(0), row(depthMm + groupGap)], twoRowHeight)
  }
  if (presetId === 'screen-t-face-to-face') {
    // T: compact 2-column / 3-row groups. The short middle row forms the
    // stem and groups are separated horizontally by the 900mm clear gap.
    const groupGap = spacing.faceGapMm ?? 900
    const groupWidth = widthMm * 2 + spacing.gapMm
    const groupHeight = depthMm * 3
    const availableWidth = spanX - 2 * spacing.wallMm
    const groupCount = Math.min(2, Math.floor((availableWidth + groupGap + EPS) / (groupWidth + groupGap)))
    const totalWidth = groupCount * groupWidth + Math.max(0, groupCount - 1) * groupGap
    if (groupCount < 1 || groupHeight > spanY - 2 * spacing.wallMm + EPS) return empty('当前默认间距和家具尺寸无法放入 T 型图例排布')
    const x0 = bounds.minX + spacing.wallMm + (availableWidth - totalWidth) / 2
    const y0 = bounds.minY + spacing.wallMm + (spanY - 2 * spacing.wallMm - groupHeight) / 2
    const slots: LayoutRect[] = []
    for (let group = 0; group < groupCount; group++) {
      const gx = x0 + group * (groupWidth + groupGap)
      const middleX = gx + (groupWidth - widthMm) / 2
      const groupSlots: LayoutRect[] = [
        { x: gx, y: y0, w: widthMm, h: depthMm, rotation: 0 },
        { x: gx + widthMm + spacing.gapMm, y: y0, w: widthMm, h: depthMm, rotation: 0 },
        { x: middleX, y: y0 + depthMm, w: widthMm, h: depthMm, rotation: 180 },
        { x: gx, y: y0 + depthMm * 2, w: widthMm, h: depthMm, rotation: 0 },
        { x: gx + widthMm + spacing.gapMm, y: y0 + depthMm * 2, w: widthMm, h: depthMm, rotation: 0 },
      ]
      for (const slot of groupSlots) {
        if (!valid(slot) || slots.some(other => overlaps(slot, other))) return empty('当前空间轮廓无法完整容纳 T 型图例排布')
        slots.push(slot)
      }
    }
    return { slots, rows: 3, columns: groupCount * 2, rotated: false, problem: null, presetId, productStyleId }
  }
  if (presetId === 'screen-c-face-to-face') {
    // C: each group is a 2-by-2 mirrored enclosure. Keep the two groups
    // separate so the 900mm opening is visible in the plan.
    const centerGap = spacing.faceGapMm ?? 900
    const groupWidth = widthMm * 2 + spacing.gapMm
    const availableWidth = spanX - 2 * spacing.wallMm
    const groupCount = Math.min(2, Math.floor((availableWidth + centerGap + EPS) / (groupWidth + centerGap)))
    const totalWidth = groupCount * groupWidth + Math.max(0, groupCount - 1) * centerGap
    const totalHeight = depthMm * 2
    if (groupCount < 1 || totalWidth > availableWidth + EPS || totalHeight > spanY - 2 * spacing.wallMm + EPS) return empty('当前默认间距和家具尺寸无法放入 C 型图例排布')
    const x0 = bounds.minX + spacing.wallMm + (spanX - 2 * spacing.wallMm - totalWidth) / 2
    const y0 = bounds.minY + spacing.wallMm + (spanY - 2 * spacing.wallMm - totalHeight) / 2
    const slots: LayoutRect[] = []
    for (let group = 0; group < groupCount; group++) {
      const gx = x0 + group * (groupWidth + centerGap)
      const groupSlots: LayoutRect[] = [
        { x: gx, y: y0, w: widthMm, h: depthMm, rotation: 0 },
        { x: gx + widthMm + spacing.gapMm, y: y0, w: widthMm, h: depthMm, rotation: 180 },
        { x: gx, y: y0 + depthMm, w: widthMm, h: depthMm, rotation: 180 },
        { x: gx + widthMm + spacing.gapMm, y: y0 + depthMm, w: widthMm, h: depthMm, rotation: 0 },
      ]
      for (const slot of groupSlots) {
        if (!valid(slot) || slots.some(other => overlaps(slot, other))) return empty('当前空间轮廓无法完整容纳 C 型图例排布')
        slots.push(slot)
      }
    }
    return { slots, rows: 2, columns: groupCount * 2, rotated: false, problem: null, presetId, productStyleId }
  }
  if (presetId === 'screen-cross-face-to-face') {
    // Cross: four rotated L modules meet around a central junction. Repeat
    // the cross vertically, with 900mm clear distance between groups.
    const sideGap = spacing.aisleMm ?? 900
    const groupWidth = widthMm * 2 + spacing.gapMm
    const groupHeight = depthMm * 2 + spacing.gapMm
    const availableHeight = spanY - 2 * spacing.wallMm
    const groupCount = Math.min(2, Math.floor((availableHeight + sideGap + EPS) / (groupHeight + sideGap)))
    const totalWidth = groupWidth
    const totalHeight = groupCount * groupHeight + Math.max(0, groupCount - 1) * sideGap
    if (groupCount < 1 || totalWidth > spanX - 2 * spacing.wallMm + EPS || totalHeight > availableHeight + EPS) return empty('当前默认间距和家具尺寸无法放入十字型图例排布')
    const x0 = bounds.minX + spacing.wallMm + (spanX - 2 * spacing.wallMm - totalWidth) / 2
    const y0 = bounds.minY + spacing.wallMm + (availableHeight - totalHeight) / 2
    const slots: LayoutRect[] = []
    for (let group = 0; group < groupCount; group++) {
      const gy = y0 + group * (groupHeight + sideGap)
      const groupSlots: LayoutRect[] = [
        { x: x0, y: gy, w: widthMm, h: depthMm, rotation: 0 },
        { x: x0 + widthMm + spacing.gapMm, y: gy, w: widthMm, h: depthMm, rotation: 90 },
        { x: x0, y: gy + depthMm + spacing.gapMm, w: widthMm, h: depthMm, rotation: 270 },
        { x: x0 + widthMm + spacing.gapMm, y: gy + depthMm + spacing.gapMm, w: widthMm, h: depthMm, rotation: 180 },
      ]
      for (const slot of groupSlots) {
        if (!valid(slot) || slots.some(other => overlaps(slot, other))) return empty('当前空间轮廓无法完整容纳十字型图例排布')
        slots.push(slot)
      }
    }
    return { slots, rows: groupCount * 2, columns: 2, rotated: false, problem: null, presetId, productStyleId }
  }
  return empty('该产品样式没有对应的图例排布')
}

const STRICT_PRESET_SPACING: Partial<Record<OfficeArrangementPresetId, Partial<OfficeLayoutSpacing>>> = {
  'straight-desk-row': { faceGapMm: 1000 },
  'straight-desk-face-to-face': { faceGapMm: 1600 },
  'l-desk-row': { faceGapMm: 600 },
  'l-desk-face-to-face': { faceGapMm: 600 },
  'straight-screen-row': { faceGapMm: 1000 },
  'straight-screen-face-to-face': { faceGapMm: 1600 },
  'screen-f-row': { faceGapMm: 900 },
  'screen-t-face-to-face': { faceGapMm: 900 },
  'screen-c-face-to-face': { faceGapMm: 900 },
  'screen-cross-face-to-face': { faceGapMm: 900, aisleMm: 900 },
}
