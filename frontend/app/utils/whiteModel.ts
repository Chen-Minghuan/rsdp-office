import type { FurnitureObject, ZoneObject } from '~/types/office'
import type { SpatialFacility, SpatialObstacle } from '~/types/spatial'
import { obstacleRing } from '~/utils/spatial/obstacles'
import { furnitureById } from '~/utils/manualFurniture'

/**
 * 3D 白模场景数据（纯数据，坐标与 2D 画布一致：毫米、Y 向下）。
 * 组件层只负责把这份数据映射进 three.js（canvas (x,y) → (x, -y) 竖直为 Y）。
 */

export const WHITE_MODEL_WALL_HEIGHT_MM = 1500 // 半高剖切：白模以保证家具可读性为先，墙体示意高度
export const WHITE_MODEL_WALL_THICKNESS_MM = 120
export const WHITE_MODEL_FLOOR_THICKNESS_MM = 100
/** 柱子/井道等实体障碍保持到顶，与剖切墙体区分。 */
export const WHITE_MODEL_OBSTACLE_FULL_HEIGHT_MM = 2800

export interface WhiteModelWall {
  a: [number, number]
  b: [number, number]
  heightMm: number
  thicknessMm: number
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
  /** 画布顺时针角度；组件层取反写入 three rotation.y。 */
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

export function buildWhiteModelScene(zone: ZoneObject, furniture: FurnitureObject[]): WhiteModelScene {
  const floor = zone.polygon?.length ? zone.polygon.map(([x, y]) => [x, y] as [number, number]) : rectRing(zone)
  const walls: WhiteModelWall[] = floor.map((a, i) => ({
    a, b: floor[(i + 1) % floor.length]!,
    heightMm: WHITE_MODEL_WALL_HEIGHT_MM, thicknessMm: WHITE_MODEL_WALL_THICKNESS_MM,
  }))

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
    floor, walls, volumes, facilities, regions,
    bounds: { minX: Math.min(...xs), minY: Math.min(...ys), maxX: Math.max(...xs), maxY: Math.max(...ys) },
  }
}

function rectRing(zone: ZoneObject): Array<[number, number]> {
  return [[zone.x, zone.y], [zone.x + zone.w, zone.y], [zone.x + zone.w, zone.y + zone.h], [zone.x, zone.y + zone.h]]
}
