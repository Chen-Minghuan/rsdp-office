import type { FurnitureObject, ZoneObject } from '~/types/office'
import type { SpatialFacility, SpatialObstacle } from '~/types/spatial'
import { obstacleRing, windowLinePoints } from '~/utils/spatial/obstacles'
import { furnitureById } from '~/utils/manualFurniture'
import { workstationSeats, type WorkstationSeat } from '~/utils/furniture3d/workstationSeating'

/**
 * 3D 白模场景数据（纯数据，坐标与 2D 画布一致：毫米、Y 向下）。
 * 组件层只负责把这份数据映射进 three.js（canvas (x,y) → (x, -y) 竖直为 Y）。
 */

export const WHITE_MODEL_WALL_HEIGHT_MM = 2800 // 所有墙体统一使用完整示意高度，不因窗户改变墙高
export const WHITE_MODEL_WALL_THICKNESS_MM = 120
export const WHITE_MODEL_FLOOR_THICKNESS_MM = 100
/** 柱子/井道等实体障碍与墙体同高。 */
export const WHITE_MODEL_OBSTACLE_FULL_HEIGHT_MM = WHITE_MODEL_WALL_HEIGHT_MM

export interface WhiteModelWall {
  a: [number, number]
  b: [number, number]
  heightMm: number
  thicknessMm: number
  openings?: Array<{ startMm: number; endMm: number; bottomMm: number; topMm: number }>
}

/** 由 2D 窗线派生的立体窗；高度仍是示意默认值，长度和墙边来自设计几何。 */
export interface WhiteModelWindow {
  id: string
  label: string
  wallIndex: number
  a: [number, number]
  b: [number, number]
  widthMm: number
  heightMm: number
  baseMm: number
  wallThicknessMm: number
}

/** a 为铰链、b 为关闭时门扇末端；opensTo 为 three 局部 Z 的开启侧。 */
export interface WhiteModelDoor {
  id: string
  label: string
  wallIndex: number
  a: [number, number]
  b: [number, number]
  widthMm: number
  heightMm: number
  wallThicknessMm: number
  opensTo: 1 | -1
}

export interface WhiteModelVolume {
  id: string
  source: 'furniture' | 'obstacle'
  label: string
  /** 中心点（画布坐标）。 */
  cx: number
  cy: number
  w: number
  d: number
  heightMm: number
  /** 底面离地高度（窗台等）。 */
  baseMm: number
  /** 画布顺时针角度；组件层直接写入 three rotation.y（y→-z 映射已含镜像，方向一致）。 */
  rotDeg: number
  /** 多边形体积（障碍）按外环拉伸；缺省用 w/d 盒子。 */
  ring?: Array<[number, number]>
  tone: 'furniture' | 'column' | 'noPlacement' | 'doorClearance' | 'window' | 'obstacle'
  obstacleKind?: SpatialObstacle['kind']
}

export interface WhiteModelFacility {
  id: string
  kind: SpatialFacility['kind']
  label: string
  shape: SpatialFacility['shape']
  points: Array<[number, number]>
  sizeMm?: { widthMm: number; depthMm: number }
  clearanceMm?: number
}

export interface WhiteModelScene {
  floor: Array<[number, number]>
  walls: WhiteModelWall[]
  windows: WhiteModelWindow[]
  doors: WhiteModelDoor[]
  workstationSeats: WorkstationSeat[]
  volumes: WhiteModelVolume[]
  facilities: WhiteModelFacility[]
  regions: Array<[number, number][]>
  bounds: { minX: number; minY: number; maxX: number; maxY: number }
}

/** 白模家具高度：优先模板显式高度，否则按类别取行业常用值（仅渲染，不回写几何）。 */
export function furnitureHeightMm(templateId: string | undefined): number {
  const template = templateId ? furnitureById.get(templateId) : undefined
  if (template?.heightMm) return template.heightMm
  switch (template?.kind) {
    case 'chair': return 850
    case 'table': return 750
    case 'desk': case 'l-desk': case 'rounded-l-desk': case 'u-desk': return 750
    case 'counter': return 1050
    case 'sofa': return 720
    case 'cabinet': return 900
    case 'plant': return 1300
    case 'wall': return 1600
    default: return 750
  }
}

export function obstacleVolumeStyle(kind: SpatialObstacle['kind']): { heightMm: number; baseMm: number; tone: WhiteModelVolume['tone'] } {
  switch (kind) {
    case 'COLUMN': case 'SHAFT': return { heightMm: WHITE_MODEL_OBSTACLE_FULL_HEIGHT_MM, baseMm: 0, tone: 'column' }
    case 'NO_PLACEMENT': return { heightMm: 2000, baseMm: 0, tone: 'noPlacement' }
    case 'DOOR_CLEARANCE': return { heightMm: 20, baseMm: 0, tone: 'doorClearance' }
    case 'WINDOW': return { heightMm: 1200, baseMm: 900, tone: 'window' }
    default: return { heightMm: 2000, baseMm: 0, tone: 'obstacle' }
  }
}

export const facilityKindLabel: Record<SpatialFacility['kind'], string> = {
  FIRE_FACILITY: '消防设施',
  ELECTRICAL_BOX: '配电箱',
  SKIRTING: '踢脚线',
  POWER_OUTLET: '电源插座',
  DATA_OUTLET: '网络点位',
}

export const obstacleKindLabel: Record<SpatialObstacle['kind'], string> = {
  COLUMN: '柱子', SHAFT: '井道', NO_PLACEMENT: '禁放区', DOOR_CLEARANCE: '门洞净空', WINDOW: '窗', OTHER: '障碍',
}

function facilityToCanvas(zone: ZoneObject, facility: SpatialFacility): WhiteModelFacility {
  // pointsMm 为世界毫米、Y 向上；毫米模式下画布 Y 向下需取反（同 obstacleRing 约定）。
  const factor = zone.reviewUnit === 'mm' ? -1 : 1
  return {
    id: facility.facilityId,
    kind: facility.kind,
    label: facility.name || facilityKindLabel[facility.kind],
    shape: facility.shape,
    points: facility.pointsMm.map(([x, y]) => [x, y * factor] as [number, number]),
    sizeMm: facility.sizeMm,
    clearanceMm: facility.clearanceMm,
  }
}

function projectWallLine(wall: WhiteModelWall, wallIndex: number, line: number[][]) {
  const dx = wall.b[0] - wall.a[0], dy = wall.b[1] - wall.a[1], length = Math.hypot(dx, dy)
  if (length < 1) return null
  const ux = dx / length, uy = dy / length
  const ts = line.map(p => Math.max(0, Math.min(length, (p[0]! - wall.a[0]) * ux + (p[1]! - wall.a[1]) * uy)))
  const distances = line.map((p, index) => Math.hypot(p[0]! - wall.a[0] - ts[index]! * ux, p[1]! - wall.a[1] - ts[index]! * uy))
  return { wall, wallIndex, length, ux, uy, ts, start: Math.min(...ts), end: Math.max(...ts),
    distance: distances.reduce((sum, value) => sum + value, 0), maxDistance: Math.max(...distances) }
}

export function buildWhiteModelScene(zone: ZoneObject, furniture: FurnitureObject[]): WhiteModelScene {
  const floor = zone.polygon?.length ? zone.polygon.map(([x, y]) => [x, y] as [number, number]) : rectRing(zone)
  const walls: WhiteModelWall[] = floor.map((a, i) => ({
    a, b: floor[(i + 1) % floor.length]!,
    heightMm: WHITE_MODEL_WALL_HEIGHT_MM, thicknessMm: WHITE_MODEL_WALL_THICKNESS_MM,
  }))
  const windows: WhiteModelWindow[] = []
  for (const obstacle of zone.obstacles || []) {
    if (obstacle.kind !== 'WINDOW') continue
    const factor = zone.reviewUnit === 'mm' ? -1 : 1
    const line = windowLinePoints(obstacle).map(([x, y]) => [x, y * factor])
    if (line.length !== 2) continue
    // 老矩形窗仅在渲染副本投影回墙，不改写原始标注或房间。
    const choices = walls.map((wall, wallIndex) => projectWallLine(wall, wallIndex, line))
      .filter((choice): choice is NonNullable<typeof choice> => !!choice && choice.end - choice.start >= 10)
      .sort((a, b) => a.distance - b.distance)
    const match = choices[0]
    if (!match) continue
    const { wall, wallIndex, ux, uy, start, end } = match
    const style = obstacleVolumeStyle('WINDOW')
    wall.openings ||= []
    wall.openings.push({ startMm: Math.max(0.1, start), endMm: Math.min(match.length - 0.1, end), bottomMm: style.baseMm, topMm: style.baseMm + style.heightMm })
    windows.push({ id: obstacle.obstacleId, label: obstacle.label || '窗', wallIndex,
      a: [wall.a[0] + start * ux, wall.a[1] + start * uy], b: [wall.a[0] + end * ux, wall.a[1] + end * uy],
      widthMm: end - start, heightMm: style.heightMm, baseMm: style.baseMm, wallThicknessMm: wall.thicknessMm })
  }
  const doors: WhiteModelDoor[] = []
  for (const obstacle of zone.obstacles || []) {
    if (obstacle.kind !== 'DOOR_CLEARANCE') continue
    const ring = obstacleRing(zone, obstacle)
    // 现有门标注为 铰链 + 弧起点 ... 弧终点；旧矩形/自由净空不猜测实体门。
    if (ring.length < 5) continue
    const hinge = ring[0]!, ends = [ring[1]!, ring.at(-1)!]
    const choices = ends.flatMap((end, radialIndex) => walls.map((wall, wallIndex) => {
      const match = projectWallLine(wall, wallIndex, [hinge, end])
      if (!match || !(match.end - match.start >= 10) || !(match.maxDistance <= wall.thicknessMm)) return null
      return { ...match, radialIndex }
    })).filter((choice): choice is NonNullable<typeof choice> => !!choice).sort((a, b) => a.distance - b.distance)
    const match = choices[0]
    if (!match) continue // 离墙净空区继续显示地面标注，不在远处墙上凭空开洞。
    const { wall, wallIndex, ux, uy, ts, start, end, radialIndex } = match
    const a: [number, number] = [wall.a[0] + ts[0]! * ux, wall.a[1] + ts[0]! * uy]
    const b: [number, number] = [wall.a[0] + ts[1]! * ux, wall.a[1] + ts[1]! * uy]
    const dx = b[0] - a[0], dy = b[1] - a[1], width = Math.hypot(dx, dy)
    const other = ends[1 - radialIndex]!
    const opensTo = (other[0] - hinge[0]) * dy / width - (other[1] - hinge[1]) * dx / width >= 0 ? 1 : -1
    const height = 2100 // 门高尚无实测字段，仅示意；不改变整圈墙高。
    wall.openings ||= []
    wall.openings.push({ startMm: start, endMm: end, bottomMm: 0, topMm: height })
    doors.push({ id: obstacle.obstacleId, label: obstacle.label || '门', wallIndex, a, b, widthMm: width,
      heightMm: height, wallThicknessMm: wall.thicknessMm, opensTo })
  }
  // 同竖向范围的重叠/相邻开口合并；门窗交叠由渲染层布尔扣除处理。
  for (const wall of walls) {
    if (!wall.openings?.length) continue
    const merged: NonNullable<WhiteModelWall['openings']> = []
    for (const opening of wall.openings.sort((a, b) => a.startMm - b.startMm)) {
      const last = merged.at(-1)
      if (last && opening.bottomMm === last.bottomMm && opening.topMm === last.topMm && opening.startMm <= last.endMm + 0.001) last.endMm = Math.max(last.endMm, opening.endMm)
      else merged.push({ ...opening })
    }
    wall.openings = merged
  }

  const volumes: WhiteModelVolume[] = []
  for (const item of furniture) {
    volumes.push({
      id: item.id, source: 'furniture', label: item.label || '家具',
      cx: item.x + item.w / 2, cy: item.y + item.h / 2, w: item.w, d: item.h,
      heightMm: furnitureHeightMm(item.furnitureTemplateId), baseMm: 0,
      rotDeg: item.rotation || 0, tone: 'furniture',
    })
  }
  for (const obstacle of zone.obstacles || []) {
    if (obstacle.kind === 'WINDOW') continue
    if (obstacle.kind === 'DOOR_CLEARANCE' && doors.some(door => door.id === obstacle.obstacleId)) continue
    const ring = obstacleRing(zone, obstacle)
    if (!ring.length) continue
    const xs = ring.map(p => p[0]), ys = ring.map(p => p[1])
    const style = obstacleVolumeStyle(obstacle.kind)
    volumes.push({
      id: obstacle.obstacleId, source: 'obstacle',
      label: obstacle.label || obstacleKindLabel[obstacle.kind] || '障碍',
      cx: (Math.min(...xs) + Math.max(...xs)) / 2, cy: (Math.min(...ys) + Math.max(...ys)) / 2,
      w: Math.max(...xs) - Math.min(...xs), d: Math.max(...ys) - Math.min(...ys),
      heightMm: style.heightMm, baseMm: style.baseMm, rotDeg: 0, ring, tone: style.tone,
      obstacleKind: obstacle.kind,
    })
  }

  const facilities = (zone.facilities || []).filter(item => item.visible !== false).map(item => facilityToCanvas(zone, item))
  const regions = (zone.layoutRegions || [])
    .filter(region => region.enabled && region.polygonMm?.length >= 3)
    .map(region => {
      const factor = zone.reviewUnit === 'mm' ? -1 : 1
      return region.polygonMm.map(([x, y]) => [x, y * factor] as [number, number])
    })

  const xs = floor.map(p => p[0]), ys = floor.map(p => p[1])
  return {
    floor, walls, windows, doors, workstationSeats: workstationSeats(furniture), volumes, facilities, regions,
    bounds: { minX: Math.min(...xs), minY: Math.min(...ys), maxX: Math.max(...xs), maxY: Math.max(...ys) },
  }
}

function rectRing(zone: ZoneObject): Array<[number, number]> {
  return [[zone.x, zone.y], [zone.x + zone.w, zone.y], [zone.x + zone.w, zone.y + zone.h], [zone.x, zone.y + zone.h]]
}
