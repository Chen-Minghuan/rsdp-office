import type { FurnitureObject, ZoneObject } from '~/types/office'
import type { LayoutChannel } from '~/types/layoutPlanning'
import type { LayoutBlocker } from '~/utils/layoutConstraints'
import { channelRects } from '~/utils/layoutChannels'
import { obstacleExclusion, obstacleRing } from '~/utils/spatial/obstacles'
import { clipRectToRingRects } from '~/utils/spatial/geometry'
import { officeFurnitureFootprint } from '~/utils/officeManualPlacement'

/** 通道端头与门洞净空的衔接缓冲（毫米）：端头该范围内有门洞净空即视为衔接到入口。策略值，非建筑规范。 */
export const DOOR_LINK_BUFFER_MM = 600
/** 通道与入口之间衔接路径的最小净宽（毫米）：低于此值提示路径被截断。策略值，非建筑规范。 */
export const MIN_PASSAGE_MM = 600

type Rect = { x: number; y: number; w: number; h: number }

function bounds(ring: Array<[number, number]>): Rect {
  const xs = ring.map(p => p[0]), ys = ring.map(p => p[1])
  const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys)
  return { x: minX, y: minY, w: maxX - minX, h: maxY - minY }
}

function intersects(a: Rect, b: Rect): boolean {
  return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
}

/** 走廊（轴对齐矩形）内被 blockers 挤占后的最小剩余净宽；沿较长轴在 blocker 边界处分段取交集，矩形输入下结果精确。 */
function minFreeWidth(corridor: Rect, blockers: Rect[]): number {
  const vertical = corridor.h >= corridor.w
  const cFrom = vertical ? corridor.y : corridor.x, cTo = cFrom + (vertical ? corridor.h : corridor.w)
  const crossFrom = vertical ? corridor.x : corridor.y, crossTo = crossFrom + (vertical ? corridor.w : corridor.h)
  const inside = blockers.filter(b => intersects(b, corridor))
  const events = new Set<number>([cFrom, cTo])
  for (const b of inside) {
    events.add(Math.max(cFrom, vertical ? b.y : b.x))
    events.add(Math.min(cTo, (vertical ? b.y : b.x) + (vertical ? b.h : b.w)))
  }
  const sorted = [...events].sort((a, b) => a - b)
  let minFree = crossTo - crossFrom
  for (let i = 0; i + 1 < sorted.length; i++) {
    const mid = (sorted[i] + sorted[i + 1]) / 2
    if (mid <= cFrom || mid >= cTo) continue
    const intervals: Array<[number, number]> = []
    for (const b of inside) {
      const from = vertical ? b.y : b.x, to = from + (vertical ? b.h : b.w)
      if (mid > from && mid < to) intervals.push([Math.max(crossFrom, vertical ? b.x : b.y), Math.min(crossTo, (vertical ? b.x : b.y) + (vertical ? b.w : b.h))])
    }
    intervals.sort((a, b) => a[0] - b[0])
    let blocked = 0, cur = -Infinity
    for (const [from, to] of intervals) {
      const s = Math.max(from, cur)
      if (to > s) { blocked += to - s; cur = to }
    }
    minFree = Math.min(minFree, crossTo - crossFrom - blocked)
  }
  return minFree
}

/**
 * LAYOUT-035 结构化出口：kind 供统一预警分级（unmarked=info、unlinked/blocked=warning）。
 */
export interface ChannelEgressIssue { kind: 'unmarked' | 'unlinked' | 'blocked'; text: string }

/**
 * 通道与入口/疏散联动提示（LAYOUT-029，提示级不拦截——saveCanvas 无 warnings 通道，后端不接入，如实记录于任务卡）。
 * 入口代理 = 人工标注的门洞净空障碍（DOOR_CLEARANCE）；识别层不产出门/开口数据，未标注时只提示未核验。
 * 规则 1：通道带走向两端头（含 028 端点留断后的实际跨度、027 多边形裁剪后的分段包围范围）至少一端在
 *         DOOR_LINK_BUFFER_MM 缓冲内接到门洞净空，否则提示未衔接入口。
 * 规则 2：已衔接端头到对应门洞的走廊（端头边缘与门洞包围盒的并集包围盒）被家具占地/禁放区/柱等挤占后
 *         剩余净宽低于 MIN_PASSAGE_MM，提示衔接路径被截断。门洞净空自身不算阻挡；窗为标注型约束（GEOM-015），也不算阻挡。
 */
export function channelEgressIssues(zone: ZoneObject, channels: LayoutChannel[], furniture: FurnitureObject[], firstBlockDepthMm?: number): ChannelEgressIssue[] {
  if (!channels.length) return []
  const doors = (zone.obstacles || []).filter(obstacle => obstacle.kind === 'DOOR_CLEARANCE')
  if (!doors.length) return [{ kind: 'unmarked', text: '未标注门洞净空（入口）：通道与入口的连通未核验，可在空间核对中补充标注' }]
  const doorRings = doors.map(obstacle => obstacleRing(zone, obstacle))
  const doorBoxes = doorRings.map(bounds)
  const blockers: Rect[] = [
    ...furniture.map(item => officeFurnitureFootprint(item)),
    ...(zone.layoutPreferences?.exclusions || []).map(area => ({ x: area.x, y: area.y, w: area.w, h: area.h })),
    ...(zone.obstacles || []).filter(obstacle => obstacle.kind !== 'DOOR_CLEARANCE' && obstacle.kind !== 'WINDOW')
      .map(obstacle => obstacleExclusion(zone, obstacle)).filter((area): area is NonNullable<typeof area> => !!area)
      .map(area => ({ x: area.x, y: area.y, w: area.w, h: area.h })),
  ]
  const hints: ChannelEgressIssue[] = []
  channels.forEach((channel, index) => {
    const label = `${channel.orientation === 'vertical' ? '纵向' : '横向'}通道${channels.length > 1 ? ' ' + (index + 1) : ''}`
    const segments = channelRects(zone, [channel], firstBlockDepthMm)
    if (!segments.length) return
    const travelMin = Math.min(...segments.map(s => channel.orientation === 'vertical' ? s.y : s.x))
    const travelMax = Math.max(...segments.map(s => (channel.orientation === 'vertical' ? s.y : s.x) + (channel.orientation === 'vertical' ? s.h : s.w)))
    const crossMin = Math.min(...segments.map(s => channel.orientation === 'vertical' ? s.x : s.y))
    const crossMax = Math.max(...segments.map(s => (channel.orientation === 'vertical' ? s.x : s.y) + (channel.orientation === 'vertical' ? s.w : s.h)))
    const B = DOOR_LINK_BUFFER_MM
    const vertical = channel.orientation === 'vertical'
    const caps: Rect[] = [
      vertical ? { x: crossMin - B, y: travelMin - B, w: crossMax - crossMin + 2 * B, h: 2 * B } : { x: travelMin - B, y: crossMin - B, w: 2 * B, h: crossMax - crossMin + 2 * B },
      vertical ? { x: crossMin - B, y: travelMax - B, w: crossMax - crossMin + 2 * B, h: 2 * B } : { x: travelMax - B, y: crossMin - B, w: 2 * B, h: crossMax - crossMin + 2 * B },
    ]
    const endEdge: Rect[] = [
      vertical ? { x: crossMin, y: travelMin, w: crossMax - crossMin, h: 1 } : { x: travelMin, y: crossMin, w: 1, h: crossMax - crossMin },
      vertical ? { x: crossMin, y: travelMax - 1, w: crossMax - crossMin, h: 1 } : { x: travelMax - 1, y: crossMin, w: 1, h: crossMax - crossMin },
    ]
    let linked = 0
    caps.forEach((cap, end) => {
      const doorIndex = doorRings.findIndex(ring => clipRectToRingRects(cap, ring).some(rect => rect.w * rect.h > 1))
      if (doorIndex < 0) return
      linked++
      const door = doorBoxes[doorIndex], edge = endEdge[end]
      const corridor: Rect = {
        x: Math.min(door.x, edge.x), y: Math.min(door.y, edge.y),
        w: Math.max(door.x + door.w, edge.x + edge.w) - Math.min(door.x, edge.x),
        h: Math.max(door.y + door.h, edge.y + edge.h) - Math.min(door.y, edge.y),
      }
      const free = minFreeWidth(corridor, blockers)
      if (free < MIN_PASSAGE_MM) hints.push({ kind: 'blocked', text: `「${label}」${end === 0 ? (vertical ? '上' : '左') : (vertical ? '下' : '右')}端与入口之间的衔接路径净宽不足 ${MIN_PASSAGE_MM}mm（剩余约 ${Math.round(free)}mm），可能被家具或禁放区截断（策略提示，非规范强制）` })
    })
    if (!linked) hints.push({ kind: 'unlinked', text: `「${label}」两端都未衔接到门洞净空（入口），请核对通道是否连到入口（策略提示，非规范强制）` })
  })
  return hints
}

/** 兼容包装：只取文案（LAYOUT-035 起面板经 channelEgressIssues 拿分级）。 */
export function channelEgressHints(zone: ZoneObject, channels: LayoutChannel[], furniture: FurnitureObject[], firstBlockDepthMm?: number): string[] {
  return channelEgressIssues(zone, channels, furniture, firstBlockDepthMm).map(issue => issue.text)
}
