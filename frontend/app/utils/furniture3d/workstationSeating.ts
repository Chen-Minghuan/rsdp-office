import type { FurnitureObject } from '~/types/office'
import { furnitureById } from '~/utils/manualFurniture'

export interface WorkstationSeat {
  deskId: string
  /** 相对桌占地中心，画布 X/Y 向下；尚未施加整个工位旋转。 */
  x: number
  y: number
  w: number
  d: number
  heightMm: number
  /** 附近已存在的独立办公椅，匹配后不再补示意椅。 */
  existingChairId?: string
}

/** 与 ManualFurnitureGlyph 的默认椅位、L 桌翼宽/台面深度共用计算。 */
export function workstationSeatGeometry(item: Pick<FurnitureObject, 'w' | 'h' | 'wingWidthMm' | 'furnitureTemplateId'>, kind: string) {
  const w = item.w, d = item.h
  if (![w, d].every(value => Number.isFinite(value) && value > 0)) return null
  const wing = Math.min(item.wingWidthMm ?? 550, w / 2, d / 2)
  const desktopDepth = Math.min(d * 0.48, item.furnitureTemplateId?.startsWith('desk-executive') ? 800 : 650)
  if (kind === 'desk') return { cx: w * 0.39, cy: d + 290, width: Math.min(500, w * 0.42), depth: 540, wing, desktopDepth }
  if (kind === 'l-desk' || kind === 'rounded-l-desk') return {
    cx: (w - wing) * 0.42, cy: (d + desktopDepth) / 2,
    width: Math.min(440, (w - wing) * 0.48), depth: Math.min(440, (d - desktopDepth) * 0.52), wing, desktopDepth,
  }
  if (kind === 'u-desk') return { cx: w / 2, cy: (d + wing) / 2, width: Math.min(500, w - 2 * wing - 40), depth: Math.min(540, d - wing - 40), wing, desktopDepth: wing }
  return null
}

export function workstationSeats(furniture: FurnitureObject[]): WorkstationSeat[] {
  const kindOf = (item: FurnitureObject) => item.moduleSnapshot?.kind || furnitureById.get(item.furnitureTemplateId || '')?.kind
  const chairs = furniture.filter(item => kindOf(item) === 'chair')
  const seats: WorkstationSeat[] = []
  const matches: Array<{ seat: WorkstationSeat; chair: FurnitureObject; distance: number }> = []
  for (const desk of furniture) {
    const template = furnitureById.get(desk.furnitureTemplateId || '')
    const category = desk.moduleSnapshot?.category || template?.category
    if (!category || !['职员桌', '主管桌', '高管桌'].includes(category)) continue
    const geometry = workstationSeatGeometry(desk, kindOf(desk) || '')
    if (!geometry || geometry.width < 100 || geometry.depth < 100) continue
    const seat: WorkstationSeat = { deskId: desk.id, x: (desk.mirrorX ? desk.w - geometry.cx : geometry.cx) - desk.w / 2,
      y: geometry.cy - desk.h / 2, w: geometry.width, d: geometry.depth, heightMm: 850 }
    seats.push(seat)
    const angle = desk.rotation * Math.PI / 180, cos = Math.cos(angle), sin = Math.sin(angle)
    const cx = desk.x + desk.w / 2 + seat.x * cos - seat.y * sin
    const cy = desk.y + desk.h / 2 + seat.x * sin + seat.y * cos
    for (const chair of chairs) {
      if (chair.zoneId !== desk.zoneId) continue
      const distance = Math.hypot(chair.x + chair.w / 2 - cx, chair.y + chair.h / 2 - cy)
      // 覆盖组合布局中拉开一段距离的独立椅；一把椅只匹配一个最近工位。
      if (distance <= Math.max(650, chair.w, chair.h)) matches.push({ seat, chair, distance })
    }
  }
  const assigned = new Set<string>()
  for (const match of matches.sort((a, b) => a.distance - b.distance)) {
    if (match.seat.existingChairId || assigned.has(match.chair.id)) continue
    match.seat.existingChairId = match.chair.id
    assigned.add(match.chair.id)
  }
  return seats
}
