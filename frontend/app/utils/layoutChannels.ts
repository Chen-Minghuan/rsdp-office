import type { ZoneObject } from '~/types/office'
import type { LayoutChannel } from '~/types/layoutPlanning'
import type { LayoutBlocker } from '~/utils/layoutConstraints'
import { canvasZoneBounds } from '~/utils/officeCanvasView'
import { clipRectToRingRects } from '~/utils/spatial/geometry'

export const DEFAULT_CHANNEL_WIDTH_MM = 900
/** 通行宽度提示阈值（毫米）：低于此值给出策略提示，不拦截；非建筑规范强制。 */
export const CHANNEL_WIDTH_HINT_MM = 900

/** 单条通道（含净距）允许的位置区间：[lo, hi]；禁放带宽度超过可用跨度时返回 null（不强行挪动）。 */
export function channelOffsetRange(zone: ZoneObject, channel: LayoutChannel): { lo: number; hi: number } | null {
  const bounds = canvasZoneBounds(zone)
  const clearance = Number.isFinite(channel.clearanceMm) && channel.clearanceMm! > 0 ? channel.clearanceMm! : 0
  const min = channel.orientation === 'vertical' ? bounds.minX : bounds.minY
  const max = channel.orientation === 'vertical' ? bounds.maxX : bounds.maxY
  if (![min, max].every(Number.isFinite)) return null
  const lo = min + clearance, hi = max - channel.widthMm - clearance
  return lo <= hi ? { lo, hi } : null
}

/** 把通道位置钳制在空间包围盒内：禁放带（含净距）不超出空间。越界输入收敛到最近合法位置。 */
export function clampChannelsToZone(zone: ZoneObject, channels: LayoutChannel[]): LayoutChannel[] {
  return channels.map(channel => {
    const range = channelOffsetRange(zone, channel)
    if (!range || !Number.isFinite(channel.offsetMm)) return channel
    const offsetMm = Math.min(Math.max(channel.offsetMm, range.lo), range.hi)
    return offsetMm === channel.offsetMm ? channel : { ...channel, offsetMm }
  })
}

/**
 * 通道沿走向的包围盒区间（世界毫米）：纵向取 Y、横向取 X。
 * 走向平移与端点拖柄（LAYOUT-040）共用同一基准。
 */
export function channelTravelSpan(zone: ZoneObject, channel: LayoutChannel): { axisMin: number; axisMax: number } | null {
  const bounds = canvasZoneBounds(zone)
  const axisMin = channel.orientation === 'vertical' ? bounds.minY : bounds.minX
  const axisMax = channel.orientation === 'vertical' ? bounds.maxY : bounds.maxX
  return [axisMin, axisMax].every(Number.isFinite) && axisMax > axisMin ? { axisMin, axisMax } : null
}

/** 当前走向窗口 [from, to]：缺省端点按包围盒端（贯通）处理，越界输入收敛到区间。 */
function channelTravelWindow(zone: ZoneObject, channel: LayoutChannel): { from: number; to: number; axisMin: number; axisMax: number } | null {
  const span = channelTravelSpan(zone, channel)
  if (!span) return null
  const from = Number.isFinite(channel.startMm) ? Math.min(Math.max(channel.startMm!, span.axisMin), span.axisMax) : span.axisMin
  const to = Number.isFinite(channel.endMm) ? Math.min(Math.max(channel.endMm!, span.axisMin), span.axisMax) : span.axisMax
  return { from, to, ...span }
}

/** 沿走向整体平移允许的增量区间 [min, max]（毫米）：窗口两端均不越出包围盒；贯通通道恒为 [0, 0]。 */
export function channelTravelDeltaRange(zone: ZoneObject, channel: LayoutChannel): { min: number; max: number } | null {
  const window = channelTravelWindow(zone, channel)
  if (!window || window.to <= window.from) return null
  return { min: window.axisMin - window.from, max: window.axisMax - window.to }
}

/**
 * 应用沿走向整体平移，返回 startMm/endMm 补丁：增量先按 channelTravelDeltaRange 钳制，
 * 平移后贴到包围盒边界的一端恢复贯通（undefined）；无可移动空间（贯通或区间非法）返回 null。
 */
export function shiftChannelTravel(zone: ZoneObject, channel: LayoutChannel, deltaMm: number): { startMm?: number; endMm?: number } | null {
  const window = channelTravelWindow(zone, channel)
  if (!window || window.to <= window.from || !Number.isFinite(deltaMm)) return null
  const dt = Math.min(Math.max(deltaMm, window.axisMin - window.from), window.axisMax - window.to)
  if (!dt) return null
  const from = window.from + dt, to = window.to + dt
  return {
    startMm: from <= window.axisMin ? undefined : Math.round(from),
    endMm: to >= window.axisMax ? undefined : Math.round(to),
  }
}

/**
 * 拖动单个走向端点（min=走向起点、max=终点）：值钳制在包围盒内且与另一端保持至少 1mm；
 * 拖到边界返回 undefined（恢复该端贯通）。另一端越界输入先收敛到区间再作基准。
 */
export function clampChannelTravelEndpoint(zone: ZoneObject, channel: LayoutChannel, edge: 'min' | 'max', valueMm: number): number | undefined {
  const window = channelTravelWindow(zone, channel)
  if (!window || !Number.isFinite(valueMm)) return undefined
  if (edge === 'min') {
    const value = Math.min(Math.max(valueMm, window.axisMin), window.to - 1)
    return value <= window.axisMin ? undefined : Math.round(value)
  }
  const value = Math.min(Math.max(valueMm, window.from + 1), window.axisMax)
  return value >= window.axisMax ? undefined : Math.round(value)
}

/**
 * 通道矩形与家具/禁放区共用世界毫米坐标；纵向通道沿 Y、横向通道沿 X 展开。
 * 走向范围：缺省贯通包围盒；startMm/endMm（LAYOUT-028 端点留断）与「仅从二排起」按最大交集收窄。
 * 禁放带先按包围盒钳制，再按房间多边形裁剪（LAYOUT-027）：凹形空间可能输出多段矩形；
 * 矩形空间输出与裁剪前完全一致。无多边形数据时退化为包围盒单矩形。
 * 排版切段（axisSegments）仍按未裁剪禁放带切轴——桌列不允许跨越通道线，裁剪与端点留断只影响绘制与压占校验。
 */
export function channelRects(zone: ZoneObject, channels: LayoutChannel[], firstBlockDepthMm?: number): LayoutBlocker[] {
  const bounds = canvasZoneBounds(zone)
  if (![bounds.minX, bounds.minY, bounds.maxX, bounds.maxY].every(Number.isFinite)) return []
  const ring = zone.polygon && zone.polygon.length >= 3 ? zone.polygon : null
  const rects: LayoutBlocker[] = []
  for (const channel of channels) {
    if (!Number.isFinite(channel.offsetMm) || !Number.isFinite(channel.widthMm) || channel.widthMm < 1) continue
    const clearance = Number.isFinite(channel.clearanceMm) && channel.clearanceMm! > 0 ? channel.clearanceMm! : 0
    // 沿走向的起止：端点留断与包围盒取交集；「仅从二排起」在首块深度之后生效。
    const axisMin = channel.orientation === 'vertical' ? bounds.minY : bounds.minX
    const axisMax = channel.orientation === 'vertical' ? bounds.maxY : bounds.maxX
    let axisFrom = axisMin, axisTo = axisMax
    if (Number.isFinite(channel.startMm)) axisFrom = Math.max(axisFrom, channel.startMm!)
    if (Number.isFinite(channel.endMm)) axisTo = Math.min(axisTo, channel.endMm!)
    // 「仅从二排起」仅在首块深度小于全跨时生效（与服务端 channelBands 语义一致）。
    if (channel.orientation === 'vertical' && channel.fromSecondRow && Number.isFinite(firstBlockDepthMm) && firstBlockDepthMm! > 0 && firstBlockDepthMm! < bounds.maxY - bounds.minY) axisFrom = Math.max(axisFrom, bounds.minY + firstBlockDepthMm!)
    if (axisTo <= axisFrom) continue
    let band: LayoutBlocker | null = null
    if (channel.orientation === 'vertical') {
      // 裁剪到包围盒：含净距的禁放带不超出空间（旧数据或净距增大后可能越界）。
      const from = Math.max(bounds.minX, channel.offsetMm - clearance), to = Math.min(bounds.maxX, channel.offsetMm + channel.widthMm + clearance)
      if (to > from) band = { x: from, y: axisFrom, w: to - from, h: axisTo - axisFrom }
    } else {
      const from = Math.max(bounds.minY, channel.offsetMm - clearance), to = Math.min(bounds.maxY, channel.offsetMm + channel.widthMm + clearance)
      if (to > from) band = { x: axisFrom, y: from, w: axisTo - axisFrom, h: to - from }
    }
    if (!band) continue
    if (ring) rects.push(...clipRectToRingRects(band, ring))
    else rects.push(band)
  }
  return rects
}

/** 返回第一条问题文案；全部合法返回 null。完全落在包围盒外的通道视为非法，避免静默失效。 */
export function validateChannels(zone: ZoneObject, channels: LayoutChannel[]): string | null {
  const bounds = canvasZoneBounds(zone)
  for (const channel of channels) {
    if (!Number.isFinite(channel.offsetMm) || !Number.isFinite(channel.widthMm)) return '通道位置或宽度必须是有效毫米数'
    if (channel.widthMm < 1) return '通道宽度必须大于 0'
    if (channel.clearanceMm !== undefined && (!Number.isFinite(channel.clearanceMm) || channel.clearanceMm < 0)) return '通道两侧净距必须是不小于 0 的毫米数'
    if (channel.startMm !== undefined && !Number.isFinite(channel.startMm)) return '通道起点必须是有效毫米数'
    if (channel.endMm !== undefined && !Number.isFinite(channel.endMm)) return '通道终点必须是有效毫米数'
    if (Number.isFinite(channel.startMm) && Number.isFinite(channel.endMm) && channel.startMm! >= channel.endMm!) return '通道起点必须小于终点'
    const spanFrom = channel.offsetMm - (channel.clearanceMm || 0), spanTo = channel.offsetMm + channel.widthMm + (channel.clearanceMm || 0)
    if (channel.orientation === 'vertical') {
      if (spanTo <= bounds.minX || spanFrom >= bounds.maxX) return '纵向通道不在空间范围内，请调整位置'
    } else if (spanTo <= bounds.minY || spanFrom >= bounds.maxY) {
      return '横向通道不在空间范围内，请调整位置'
    }
  }
  return null
}

/**
 * 把 [min, max] 可用轴区间（含两侧 wallMm 留距）按通道切成若干段。
 * 返回每段的起点与长度；与通道相交或不足 minSegmentMm 的段被丢弃。
 */
export function axisSegments(min: number, max: number, wallMm: number, channels: Array<{ offsetMm: number; widthMm: number; clearanceMm?: number }>, minSegmentMm = 1): Array<{ start: number; length: number }> {
  const lo = min + wallMm, hi = max - wallMm
  if (hi - lo < minSegmentMm) return []
  const cuts = channels
    .map(channel => {
      const clearance = Number.isFinite(channel.clearanceMm) && channel.clearanceMm! > 0 ? channel.clearanceMm! : 0
      return { from: Math.max(lo, channel.offsetMm - clearance), to: Math.min(hi, channel.offsetMm + channel.widthMm + clearance) }
    })
    .filter(cut => cut.to > cut.from)
    .sort((a, b) => a.from - b.from)
  const segments: Array<{ start: number; length: number }> = []
  let cursor = lo
  for (const cut of cuts) {
    if (cut.from - cursor >= minSegmentMm) segments.push({ start: cursor, length: cut.from - cursor })
    cursor = Math.max(cursor, cut.to)
  }
  if (hi - cursor >= minSegmentMm) segments.push({ start: cursor, length: hi - cursor })
  return segments
}
