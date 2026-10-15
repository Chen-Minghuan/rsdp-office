import type { ZoneType } from '~/types/office'
import type { FloorPlanRoom } from '~/types/floorPlan'

/** 将各种 polygon 格式统一为 [[x,y],...] 的点对数组。 */
export function normalizePolygon(
  polygon: FloorPlanRoom['polygon'],
): Array<[number, number]> {
  if (!polygon || !Array.isArray(polygon)) return []
  const result: Array<[number, number]> = []
  if (typeof polygon[0] === 'number') {
    for (let index = 0; index + 1 < polygon.length; index += 2) {
      result.push([polygon[index] as number, polygon[index + 1] as number])
    }
  }
  for (const item of polygon) {
    if (Array.isArray(item)) {
      if (item.length >= 2 && typeof item[0] === 'number' && typeof item[1] === 'number') {
        result.push([item[0], item[1]])
      }
    } else if (typeof item === 'object' && item !== null && 'x' in item && 'y' in item) {
      result.push([(item as any).x, (item as any).y])
    }
  }
  if (result.length > 1 && result[0][0] === result[result.length - 1][0] && result[0][1] === result[result.length - 1][1]) result.pop()
  return result
}

/** 计算多边形的毫米包围盒。 */
export function polygonBoundsMm(polygon: Array<[number, number]> | undefined): {
  minX: number
  minY: number
  maxX: number
  maxY: number
} | undefined {
  if (!polygon || polygon.length === 0) return undefined
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  for (const [x, y] of polygon) {
    minX = Math.min(minX, x)
    minY = Math.min(minY, y)
    maxX = Math.max(maxX, x)
    maxY = Math.max(maxY, y)
  }
  return { minX, minY, maxX, maxY }
}

/** 将 RSDP 户型空间类型映射为 rsdp-office 的 ZoneType。 */
export function mapRoomType(roomType: string): ZoneType {
  const map: Record<string, ZoneType> = {
    OPEN_OFFICE: 'OPEN_OFFICE',
    MEETING_ROOM: 'MEETING_ROOM',
    EXECUTIVE_OFFICE: 'EXECUTIVE_OFFICE',
    RECEPTION: 'RECEPTION',
    COLLABORATION: 'COLLABORATION',
    LOUNGE: 'COLLABORATION',
    HALLWAY: 'OTHER',
    OTHER: 'OTHER',
  }
  return map[roomType] || 'OTHER'
}

/** 计算一组多边形的包围盒（毫米）。 */
export function roomsBounds(rooms: Array<{ polygon?: Array<[number, number]> }>): {
  minX: number
  minY: number
  maxX: number
  maxY: number
} | undefined {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  let has = false
  for (const room of rooms) {
    const bounds = polygonBoundsMm(room.polygon)
    if (!bounds) continue
    has = true
    minX = Math.min(minX, bounds.minX)
    minY = Math.min(minY, bounds.minY)
    maxX = Math.max(maxX, bounds.maxX)
    maxY = Math.max(maxY, bounds.maxY)
  }
  if (!has) return undefined
  return { minX, minY, maxX, maxY }
}
