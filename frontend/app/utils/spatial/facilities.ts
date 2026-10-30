import type { SpatialFacility, SpatialFacilityKind } from '~/types/spatial'
import type { ZoneObject } from '~/types/office'
import { ringProblem, ringsOverlap, zoneRing } from './review'

/**
 * GEOM-018 设施/点位标注纯函数。
 *
 * 默认排版语义（不得升级为硬阻断）：
 * - POWER_OUTLET / DATA_OUTLET（点位型）：只提示位置，不阻挡家具、不扣面积；
 * - SKIRTING（线型，踢脚线）：靠墙安装提醒，不扣减室内面积；
 * - FIRE_FACILITY / ELECTRICAL_BOX（占用型）：占地为标注，操作净空 clearanceMm 必须来自人工标注，
 *   缺省只提示不阻断；需要实际禁放时应另建 NO_PLACEMENT 障碍。
 * 全部设施不扣净面积、不进入 zoneLayoutExclusions。
 * 坐标约定与障碍物一致：pointsMm 为世界毫米、Y 向上；画布坐标按 zone.reviewUnit 取反 Y。
 */

export function facilityLabel(kind: SpatialFacilityKind): string {
  return ({ FIRE_FACILITY: '消防设施', ELECTRICAL_BOX: '电箱', SKIRTING: '踢脚线', POWER_OUTLET: '电源点位', DATA_OUTLET: '弱电点位' } as const)[kind]
}

export function facilityShape(kind: SpatialFacilityKind): SpatialFacility['shape'] {
  if (kind === 'SKIRTING') return 'polyline'
  if (kind === 'POWER_OUTLET' || kind === 'DATA_OUTLET') return 'point'
  return 'polygon'
}

/** 点位型标注的默认渲染占地（仅展示用，毫米，不代表实际设备尺寸）。 */
export const FACILITY_POINT_MARKER_MM = 150

export function facilitiesForZone(zone: ZoneObject): SpatialFacility[] {
  return (zone.facilities || []).filter(facility => facility && facility.spaceId === zone.id)
}

/** 与 obstacleRing 同一换算：WORLD_MM（Y 向上）→ 画布坐标（毫米场景 Y 向下）。 */
export function facilityCanvasPoints(zone: ZoneObject, facility: SpatialFacility): Array<[number, number]> {
  const factor = zone.reviewUnit === 'mm' ? -1 : 1
  return (facility.pointsMm || []).map(([x, y]): [number, number] => [x, y * factor])
}

/**
 * 占地环（画布坐标）：polygon 用自身轮廓；point 用中心 + sizeMm（缺省渲染标记尺寸）的矩形；
 * polyline 没有占地概念返回 null。占地仅用于渲染与提示，不参与面积扣减与硬校验。
 */
export function facilityFootprintRing(zone: ZoneObject, facility: SpatialFacility): Array<[number, number]> | null {
  const points = facilityCanvasPoints(zone, facility)
  if (facility.shape === 'polyline' || !points.length) return null
  if (facility.shape === 'polygon') return points.length >= 3 ? points : null
  const [cx, cy] = points[0]
  const halfW = (facility.sizeMm?.widthMm || FACILITY_POINT_MARKER_MM) / 2
  const halfD = (facility.sizeMm?.depthMm || FACILITY_POINT_MARKER_MM) / 2
  return [[cx - halfW, cy - halfD], [cx + halfW, cy - halfD], [cx + halfW, cy + halfD], [cx - halfW, cy + halfD]]
}

/**
 * 操作净空环（画布坐标）：仅占用型且人工标注了 clearanceMm > 0 时存在，
 * 为占地包围盒四周外扩 clearanceMm（诚实标注为包围盒外扩，不假装精确多边形偏移）。
 */
export function facilityClearanceRing(zone: ZoneObject, facility: SpatialFacility): Array<[number, number]> | null {
  if (facility.shape !== 'polygon' || !Number.isFinite(facility.clearanceMm) || !(facility.clearanceMm! > 0)) return null
  const footprint = facilityFootprintRing(zone, facility)
  if (!footprint) return null
  const xs = footprint.map(point => point[0]), ys = footprint.map(point => point[1])
  const clearance = facility.clearanceMm!
  const minX = Math.min(...xs) - clearance, maxX = Math.max(...xs) + clearance
  const minY = Math.min(...ys) - clearance, maxY = Math.max(...ys) + clearance
  return [[minX, minY], [maxX, minY], [maxX, maxY], [minX, maxY]]
}

/** 点位提示环：电源/弱电附近需要预留接线时的提示范围（取占地环，仅提示用）。 */
export function facilityNoticeRing(zone: ZoneObject, facility: SpatialFacility): Array<[number, number]> | null {
  return facilityClearanceRing(zone, facility) || facilityFootprintRing(zone, facility)
}

/** 设施校验：点位/线型/占用型分别核验点数与有限坐标；polygon 过 ringProblem；整体须与空间轮廓相交。 */
export function facilityProblem(zone: ZoneObject, facility: SpatialFacility): string | null {
  const label = facilityLabel(facility.kind)
  const points = facilityCanvasPoints(zone, facility)
  if (points.some(point => point.length !== 2 || point.some(value => !Number.isFinite(value)))) return label + '包含无效坐标'
  if (facility.shape === 'point' && points.length !== 1) return label + '需要恰好一个点位'
  if (facility.shape === 'polyline' && points.length < 2) return label + '至少需要两个点'
  if (facility.shape === 'polygon') {
    if (points.length < 3) return label + '至少需要三个顶点'
    const problem = ringProblem(points)
    if (problem) return label + problem
  }
  const space = zoneRing(zone)
  if (space.length < 3) return null
  if (facility.shape === 'polygon') {
    if (!ringsOverlap(space, points)) return label + '未与所属空间相交，请调整'
  } else {
    const bounds = space.reduce((acc, point) => ({ minX: Math.min(acc.minX, point[0]), minY: Math.min(acc.minY, point[1]), maxX: Math.max(acc.maxX, point[0]), maxY: Math.max(acc.maxY, point[1]) }), { minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity })
    for (const [x, y] of points) {
      if (x < bounds.minX - 1 || x > bounds.maxX + 1 || y < bounds.minY - 1 || y > bounds.maxY + 1) return label + '不在所属空间范围内，请调整'
    }
  }
  return null
}

export interface FacilityLayoutHint {
  facilityId: string
  kind: SpatialFacilityKind
  targetId: string
  message: string
}

/**
 * 排版提示（全部提示级，不阻断）：
 * - 占用型：家具压占占地或人工标注的操作净空 → 提示现场复核；
 * - 点位型：家具压住点位 → 提示接线/检修位置被遮挡；
 * - 线型（踢脚线）：不参与逐件提示（靠墙安装的全局提醒由 UI 文案承担）。
 */
export function facilityLayoutHints(zone: ZoneObject, targets: Array<{ id: string; label?: string; ring: Array<[number, number]> }>): FacilityLayoutHint[] {
  const hints: FacilityLayoutHint[] = []
  for (const facility of facilitiesForZone(zone)) {
    if (facility.visible === false) continue
    const label = facility.name || facilityLabel(facility.kind)
    const notice = facility.shape === 'point' ? facilityFootprintRing(zone, facility) : facilityNoticeRing(zone, facility)
    if (!notice) continue
    for (const target of targets) {
      if (target.ring.length < 3 || !ringsOverlap(notice, target.ring)) continue
      const name = target.label || target.id
      if (facility.shape === 'point') {
        hints.push({ facilityId: facility.facilityId, kind: facility.kind, targetId: target.id, message: `家具「${name}」压住了${label}，接线或检修位置被遮挡，请现场复核` })
      } else {
        const clearance = Number.isFinite(facility.clearanceMm) && facility.clearanceMm! > 0 ? `（含人工标注操作净空 ${Math.round(facility.clearanceMm!)}mm）` : '（未标注操作净空，仅提示）'
        hints.push({ facilityId: facility.facilityId, kind: facility.kind, targetId: target.id, message: `家具「${name}」压占${label}范围${clearance}，请现场复核或调整` })
      }
    }
  }
  return hints
}

/** 旧画布兼容：原始 JSON → 合法设施数组；缺失按空数组，畸形项跳过。 */
export function sanitizeFacilities(spaceId: string, raw: unknown): SpatialFacility[] {
  if (!Array.isArray(raw)) return []
  const facilities: SpatialFacility[] = []
  const seen = new Set<string>()
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue
    const candidate = item as Record<string, unknown>
    const facilityId = typeof candidate.facilityId === 'string' && candidate.facilityId ? candidate.facilityId : null
    const kind = typeof candidate.kind === 'string' && ['FIRE_FACILITY', 'ELECTRICAL_BOX', 'SKIRTING', 'POWER_OUTLET', 'DATA_OUTLET'].includes(candidate.kind) ? candidate.kind as SpatialFacilityKind : null
    const points = Array.isArray(candidate.pointsMm)
      ? (candidate.pointsMm as unknown[]).filter((point): point is [number, number] => Array.isArray(point) && point.length === 2 && point.every(Number.isFinite))
      : []
    if (!facilityId || !kind || seen.has(facilityId)) continue
    const shape = facilityShape(kind)
    if ((shape === 'point' && points.length !== 1) || (shape === 'polyline' && points.length < 2) || (shape === 'polygon' && points.length < 3)) continue
    seen.add(facilityId)
    const sizeRaw = candidate.sizeMm as { widthMm?: unknown; depthMm?: unknown } | undefined
    facilities.push({
      facilityId,
      kind,
      spaceId,
      shape,
      pointsMm: points,
      name: typeof candidate.name === 'string' && candidate.name ? candidate.name : undefined,
      sizeMm: sizeRaw && Number.isFinite(sizeRaw.widthMm) && Number.isFinite(sizeRaw.depthMm) ? { widthMm: sizeRaw.widthMm as number, depthMm: sizeRaw.depthMm as number } : undefined,
      rotationDeg: Number.isFinite(candidate.rotationDeg) ? candidate.rotationDeg as number : undefined,
      clearanceMm: Number.isFinite(candidate.clearanceMm) && (candidate.clearanceMm as number) > 0 ? candidate.clearanceMm as number : undefined,
      visible: candidate.visible !== false,
      source: candidate.source === 'CAD_GEOMETRY' || candidate.source === 'AI_VISION' ? candidate.source : 'MANUAL',
      note: typeof candidate.note === 'string' && candidate.note ? candidate.note : undefined,
    })
  }
  return facilities
}
