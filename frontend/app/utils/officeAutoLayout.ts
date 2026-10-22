import type { ZoneObject } from '~/types/office'
import type { Arrangement, LayoutRect } from '~/utils/layout'
import { polygonBoundsMm } from '~/utils/floorPlan'
import type { OfficeArrangementPresetId, OfficeProductStyleId } from '~/types/officeArrangement'
import type { LayoutChannel } from '~/types/layoutPlanning'
import { axisSegments, channelRects } from '~/utils/layoutChannels'

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
export interface OfficeLayoutPlan { slots: LayoutRect[]; rows: number; columns: number; rotated: boolean; problem: string | null; presetId?: OfficeArrangementPresetId; productStyleId?: OfficeProductStyleId; firstBlockDepthMm?: number }
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

export function planOfficeFurniture(zone: ZoneObject, widthMm: number, depthMm: number, mode: OfficeLayoutMode, spacing: OfficeLayoutSpacing = OFFICE_LAYOUT_SPACING, channels: LayoutChannel[] = []): OfficeLayoutPlan {
  const empty = (problem: string | null): OfficeLayoutPlan => ({ slots: [], rows: 0, columns: 0, rotated: false, problem })
  if (zone.reviewUnit !== 'mm' || ![widthMm, depthMm].every(value => Number.isFinite(value) && value >= 10)) return empty('请填写有效的家具毫米尺寸')
  const pairGap = mode === 'faceToFace' ? spacing.faceGapMm ?? spacing.pairMm ?? 900 : spacing.backGapMm ?? spacing.pairMm ?? 0
  if (![spacing.wallMm, spacing.aisleMm, spacing.gapMm, pairGap].every(value => Number.isFinite(value) && value >= 0)) return empty('间距必须为有效的非负毫米数')
  const bounds = polygonBoundsMm(zone.polygon)
  if (!bounds || !Object.values(bounds).every(Number.isFinite)) return empty('空间缺少有效轮廓')
  const spanX = bounds.maxX - bounds.minX, spanY = bounds.maxY - bounds.minY
  const fit = (length: number, item: number, gap: number) => Math.max(0, Math.floor((length + gap + EPS) / (item + gap)))
  const valid = (slot: LayoutRect) => layoutRectInsideRing({ x: slot.x - spacing.wallMm, y: slot.y - spacing.wallMm, w: slot.w + 2 * spacing.wallMm, h: slot.h + 2 * spacing.wallMm }, zone.polygon!)
  // 沿边与中央通道模式没有排/列网格，通道作为硬阻挡：落子重叠即跳过，不切断网格。
  const channelBlockers = channelRects(zone, channels)

  if (mode === 'perimeter') {
    const nx = fit(spanX - 2 * spacing.wallMm, widthMm, spacing.gapMm)
    const ny = fit(spanY - 2 * spacing.wallMm, widthMm, spacing.gapMm)
    if (2 * (nx + ny) > MAX_CELLS) return empty('预计数量过多，请增大家具尺寸或间距')
    const slots: LayoutRect[] = []
    const add = (slot: LayoutRect) => { if (valid(slot) && !slots.some(other => overlaps(slot, other, spacing.gapMm)) && !channelBlockers.some(blocker => overlaps(slot, blocker))) slots.push(slot) }
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
          if (valid(slot) && !channelBlockers.some(blocker => overlaps(slot, blocker))) { slots.push(slot); rows.add(bank * bankRows + row); columns.add(col) }
        }
        if (slots.length > best.slots.length) best = { slots, rows: rows.size, columns: columns.size, rotated, problem: null }
      }
    }
    return best.slots.length ? best : empty(exceeded ? '预计数量过多，请增大家具尺寸或间距' : '当前家具尺寸和间距无法放入此空间')
  }

  // 有通道时走分段排版：纵向通道切列轴、横向通道切排轴，每段独立拟合并居中；
  // 「仅从二排起」的纵向通道不切断首排（对组模式的首个对组）。无通道时保持原整体拟合逻辑不变。
  if (channels.length) {
    const pairedChannelMode = mode === 'backToBack' || mode === 'faceToFace'
    const channelBlockH = pairedChannelMode ? depthMm * 2 + pairGap : depthMm
    const grid = (rotated: boolean): OfficeLayoutPlan => {
      const localW = rotated ? spanY : spanX, localH = rotated ? spanX : spanY
      // 通道换算到局部坐标：旋转 90° 时世界 X 映射到局部排轴（方向反转），世界 Y 映射到局部列轴。
      // 「仅从二排起」只对未旋转的纵向通道生效；旋转后它切断的是排轴，按普通通道处理。
      const colCuts: Array<{ offsetMm: number; widthMm: number; clearanceMm?: number; fromSecondRow?: boolean }> = []
      const rowCuts: Array<{ offsetMm: number; widthMm: number; clearanceMm?: number }> = []
      for (const channel of channels) {
        if (channel.orientation === 'vertical') {
          if (rotated) rowCuts.push({ offsetMm: bounds.maxX - channel.offsetMm - channel.widthMm, widthMm: channel.widthMm, clearanceMm: channel.clearanceMm })
          else colCuts.push({ offsetMm: channel.offsetMm - bounds.minX, widthMm: channel.widthMm, clearanceMm: channel.clearanceMm, fromSecondRow: channel.fromSecondRow })
        } else if (rotated) colCuts.push({ offsetMm: channel.offsetMm - bounds.minY, widthMm: channel.widthMm, clearanceMm: channel.clearanceMm })
        else rowCuts.push({ offsetMm: channel.offsetMm - bounds.minY, widthMm: channel.widthMm, clearanceMm: channel.clearanceMm })
      }
      const steadyColCuts = colCuts.filter(cut => !cut.fromSecondRow)
      const deferredColCuts = colCuts.filter(cut => cut.fromSecondRow)
      const fullRow = [{ start: spacing.wallMm, length: localH - 2 * spacing.wallMm }]
      const fullCol = [{ start: spacing.wallMm, length: localW - 2 * spacing.wallMm }]
      const rowSegments = rowCuts.length ? axisSegments(0, localH, spacing.wallMm, rowCuts, channelBlockH) : fullRow
      const steadyColSegments = steadyColCuts.length ? axisSegments(0, localW, spacing.wallMm, steadyColCuts) : fullCol
      const deferredColSegments = deferredColCuts.length ? axisSegments(0, localW, spacing.wallMm, [...steadyColCuts, ...deferredColCuts]) : steadyColSegments
      if (!rowSegments.length || !steadyColSegments.length || !deferredColSegments.length) return empty('通道占满可用空间，请调整通道位置或宽度')
      const totalBlocks = rowSegments.reduce((sum, segment) => sum + fit(segment.length, channelBlockH, spacing.aisleMm), 0)
      const maxFitCols = Math.max(...steadyColSegments.map(segment => fit(segment.length, widthMm, spacing.gapMm)))
      if (totalBlocks * maxFitCols * (pairedChannelMode ? 2 : 1) > MAX_CELLS) return empty('预计数量过多，请增大家具尺寸或间距')
      const slots: LayoutRect[] = [], rows = new Set<number>()
      let globalRow = 0, maxRowCols = 0, firstBlockDepthMm: number | undefined, firstBlockEmitted = false
      rowSegments.forEach((rowSegment, segmentIndex) => {
        let blocks = fit(rowSegment.length, channelBlockH, spacing.aisleMm)
        if (mode === 'row') blocks = segmentIndex === 0 ? Math.min(1, blocks) : 0
        if (!blocks) return
        const usedH = blocks * channelBlockH + (blocks - 1) * spacing.aisleMm
        const y0 = rowSegment.start + (rowSegment.length - usedH) / 2
        for (let block = 0; block < blocks; block++) {
          const isFirstBlock = !firstBlockEmitted
          firstBlockEmitted = true
          // 首个对组的局部排轴终点即世界坐标下相对包围盒顶部的深度（fromSecondRow 仅未旋转时生效）。
          if (isFirstBlock && deferredColCuts.length && !rotated) firstBlockDepthMm = y0 + block * (channelBlockH + spacing.aisleMm) + channelBlockH
          const colSegments = isFirstBlock ? steadyColSegments : deferredColSegments
          for (let sub = 0; sub < (pairedChannelMode ? 2 : 1); sub++) {
            const y = y0 + block * (channelBlockH + spacing.aisleMm) + sub * (depthMm + pairGap)
            const rotationRow = pairedChannelMode ? sub : globalRow
            let rowCols = 0
            for (const colSegment of colSegments) {
              const cols = fit(colSegment.length, widthMm, spacing.gapMm)
              if (!cols) continue
              const usedW = cols * widthMm + (cols - 1) * spacing.gapMm
              const x0 = colSegment.start + (colSegment.length - usedW) / 2
              for (let col = 0; col < cols; col++) {
                const x = x0 + col * (widthMm + spacing.gapMm)
                  + (mode === 'staggered' && globalRow % 2 === 1 && cols > 1 ? (widthMm + spacing.gapMm) / 2 : 0)
                const slot = rotated
                  ? { x: bounds.maxX - y - depthMm, y: bounds.minY + x, w: depthMm, h: widthMm, rotation: 90 }
                  : { x: bounds.minX + x, y: bounds.minY + y, w: widthMm, h: depthMm, rotation: ((mode === 'faceToFace' && rotationRow % 2 === 0) || (mode === 'backToBack' && rotationRow % 2 === 1)) ? 180 : 0 }
                if (valid(slot)) { slots.push(slot); rows.add(globalRow); rowCols++ }
              }
            }
            maxRowCols = Math.max(maxRowCols, rowCols)
            globalRow++
          }
        }
      })
      if (!slots.length) return empty('当前家具尺寸、间距和通道无法放入此空间')
      return { slots, rows: rows.size, columns: maxRowCols, rotated, problem: null, firstBlockDepthMm }
    }
    const channelCandidates = (mode === 'auto' ? [false, true] : [false]).map(rotated => grid(rotated))
    return channelCandidates.reduce((best, candidate) => candidate.slots.length > best.slots.length ? candidate : best, empty(null))
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
 * Reference illustration specimens used to verify the supplied diagrams.
 * Runtime capacity is computed by planAdaptiveArrangement, which repeats
 * their minimum topology cells to fit the actual room.
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
  const groupGap = spacing.faceGapMm ?? 900
  const slots: LayoutRect[] = []
  const add = (col: number, y: number, rotation = 0, mirrorX = false, xOffset = 0) => {
    slots.push({ x: col * widthMm + xOffset, y, w: widthMm, h: depthMm, rotation, ...(mirrorX ? { mirrorX: true } : {}) })
  }
  const line = (count: number, y: number, rotation = 0, mirrorX = false) => {
    for (let col = 0; col < count; col++) add(col, y, rotation, mirrorX)
  }
  const compatible: Record<string, string[]> = {
    'straight-desk': ['straight-desk-row', 'straight-desk-face-to-face'],
    'straight-screen': ['straight-screen-row', 'straight-screen-face-to-face'],
    'l-desk': ['l-desk-row', 'l-desk-face-to-face'],
    'l-screen': ['l-desk-row', 'l-desk-face-to-face', 'screen-f-row', 'screen-t-face-to-face', 'screen-c-face-to-face', 'screen-cross-face-to-face'],
  }
  if (!compatible[productStyleId]?.includes(presetId)) return empty('该产品样式没有对应的图例排布')
  if (['straight-desk-row', 'straight-screen-row', 'l-desk-row', 'screen-f-row'].includes(presetId)) {
    const count = presetId === 'screen-f-row' ? 4 : 3
    line(count, 0)
    line(count, depthMm + groupGap)
  } else if (['straight-desk-face-to-face', 'straight-screen-face-to-face', 'l-desk-face-to-face'].includes(presetId)) {
    // Reflect vertically: panels and L returns stay on the right, seats face out.
    for (const y of [0, depthMm * 2 + groupGap]) {
      line(3, y, 180, true)
      line(3, y + depthMm)
    }
  } else if (presetId === 'screen-t-face-to-face' || presetId === 'screen-c-face-to-face') {
    // Exactly two groups. Every pair shares the central returns.
    for (const x of [0, widthMm * 2 + groupGap]) {
      if (presetId === 'screen-t-face-to-face') {
        for (let row = 0; row < 3; row++) { add(0, row * depthMm, 0, false, x); add(1, row * depthMm, 0, true, x) }
      } else {
        add(0, 0, 0, false, x); add(1, 0, 0, true, x)
        add(0, depthMm, 180, true, x); add(1, depthMm, 180, false, x)
      }
    }
  } else if (presetId === 'screen-cross-face-to-face') {
    // Two horizontal double-cross banks, each 4 columns x 2 rows (8 modules).
    for (const y of [0, depthMm * 2 + groupGap]) for (let col = 0; col < 4; col++) {
      add(col, y, 180, col % 2 === 0)
      add(col, y + depthMm, 0, col % 2 === 1)
    }
  }
  const usedWidth = Math.max(...slots.map(slot => slot.x + slot.w))
  const usedHeight = Math.max(...slots.map(slot => slot.y + slot.h))
  if (usedWidth > spanX - 2 * spacing.wallMm + EPS || usedHeight > spanY - 2 * spacing.wallMm + EPS) return empty('当前空间无法完整容纳该图例组合；请调整模块尺寸或组间净距')
  const x0 = bounds.minX + (spanX - usedWidth) / 2, y0 = bounds.minY + (spanY - usedHeight) / 2
  const placed = slots.map(slot => ({ ...slot, x: x0 + slot.x, y: y0 + slot.y }))
  if (placed.some((slot, i) => !layoutRectInsideRing({ x: slot.x - spacing.wallMm, y: slot.y - spacing.wallMm, w: slot.w + spacing.wallMm * 2, h: slot.h + spacing.wallMm * 2 }, zone.polygon!) || placed.slice(0, i).some(other => overlaps(slot, other)))) return empty('当前空间轮廓无法完整容纳该图例排布')
  return { slots: placed, rows: new Set(placed.map(slot => slot.y)).size, columns: new Set(placed.map(slot => slot.x)).size, rotated: false, problem: null, presetId, productStyleId }
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
