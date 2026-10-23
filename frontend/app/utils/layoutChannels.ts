import type { ZoneObject } from '~/types/office'
import type { LayoutChannel } from '~/types/layoutPlanning'
import type { LayoutBlocker } from '~/utils/layoutConstraints'
import { canvasZoneBounds } from '~/utils/officeCanvasView'

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

/** 通道矩形与家具/禁放区共用世界毫米坐标；纵向通道沿 Y 贯通，横向通道沿 X 贯通。 */
export function channelRects(zone: ZoneObject, channels: LayoutChannel[], firstBlockDepthMm?: number): LayoutBlocker[] {
  const bounds = canvasZoneBounds(zone)
  if (![bounds.minX, bounds.minY, bounds.maxX, bounds.maxY].every(Number.isFinite)) return []
  const rects: LayoutBlocker[] = []
  for (const channel of channels) {
    if (!Number.isFinite(channel.offsetMm) || !Number.isFinite(channel.widthMm) || channel.widthMm < 1) continue
    const clearance = Number.isFinite(channel.clearanceMm) && channel.clearanceMm! > 0 ? channel.clearanceMm! : 0
    if (channel.orientation === 'vertical') {
      let y = bounds.minY, h = bounds.maxY - bounds.minY
      if (channel.fromSecondRow && Number.isFinite(firstBlockDepthMm) && firstBlockDepthMm! > 0 && firstBlockDepthMm! < h) {
        y = bounds.minY + firstBlockDepthMm!
        h = bounds.maxY - y
      }
      // 裁剪到包围盒：含净距的禁放带不超出空间（旧数据或净距增大后可能越界）。
      const from = Math.max(bounds.minX, channel.offsetMm - clearance), to = Math.min(bounds.maxX, channel.offsetMm + channel.widthMm + clearance)
      if (to > from) rects.push({ x: from, y, w: to - from, h })
    } else {
      const from = Math.max(bounds.minY, channel.offsetMm - clearance), to = Math.min(bounds.maxY, channel.offsetMm + channel.widthMm + clearance)
      if (to > from) rects.push({ x: bounds.minX, y: from, w: bounds.maxX - bounds.minX, h: to - from })
    }
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
