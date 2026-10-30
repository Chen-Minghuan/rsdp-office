import polygonClipping from 'polygon-clipping'
import type { LayoutRegion } from '~/types/spatial'
import type { ZoneObject } from '~/types/office'
import { ringProblem, zoneRing } from './review'

/**
 * GEOM-016 家具布置区（layoutRegions）纯函数。
 *
 * 坐标约定：LayoutRegion.polygonMm 为世界毫米、Y 向上（与 SpatialObstacle.geometry.outer 同一约定）；
 * 画布/排版坐标与 zone.polygon 一致（毫米场景 Y 向下），用 layoutRegionRing 换算。
 *
 * 有效可排区规则（供布局引擎 C 调用，前后端必须等价）：
 * - 空间没有启用的布置区 → allowedPlacementAreas 返回 null，维持旧的全房间可排布行为；
 * - 有启用区 → 有效区 = 启用区并集 ∩ 空间净轮廓（多边形，可为多块/带孔，绝不退化为矩形包围盒）；
 * - 硬禁放区/门洞净空/通道仍由既有 zoneLayoutExclusions / 通道校验另行扣除，不在本模块重复；
 * - visible 只影响渲染，绝不进入本模块任何计算。
 */

type Ring = Array<[number, number]>
type MultiPolygon = Array<Array<Ring>>

const toMulti = (ring: Ring): MultiPolygon => [[ring]]

/** 与 obstacleRing 同一换算：WORLD_MM（Y 向上）→ 画布坐标（毫米场景 Y 向下）。 */
export function layoutRegionRing(zone: ZoneObject, region: LayoutRegion): Ring {
  const factor = zone.reviewUnit === 'mm' ? -1 : 1
  return (region.polygonMm || []).map(([x, y]): [number, number] => [x, y * factor])
}

/** 反向换算：画布坐标 → WORLD_MM（Y 向上），用于把绘制/编辑结果写回 polygonMm。 */
export function layoutRegionWorldRing(zone: ZoneObject, ring: Ring): Ring {
  const factor = zone.reviewUnit === 'mm' ? -1 : 1
  return ring.map(([x, y]): [number, number] => [x, y * factor])
}

export function layoutRegionsForZone(zone: ZoneObject): LayoutRegion[] {
  return (zone.layoutRegions || []).filter(region => region && region.spaceId === zone.id)
}

/** 启用中的布置区；visible 不影响。 */
export function activeLayoutRegionsForZone(zone: ZoneObject): LayoutRegion[] {
  return layoutRegionsForZone(zone).filter(region => region.enabled)
}

function multiArea(mp: MultiPolygon): number {
  let area = 0
  for (const polygon of mp) {
    polygon.forEach((ring, index) => {
      let sum = 0
      for (let i = 0; i < ring.length; i++) {
        const [x1, y1] = ring[i]
        const [x2, y2] = ring[(i + 1) % ring.length]
        sum += x1 * y2 - x2 * y1
      }
      area += (index === 0 ? 1 : -1) * Math.abs(sum / 2)
    })
  }
  return area
}

/** ring 落在 cover 之外的面积（mm²）；cover 为空时为 ring 全部面积。 */
function ringAreaOutside(ring: Ring, cover: MultiPolygon): number {
  if (ring.length < 3) return 0
  if (!cover.length) return Math.abs(ringAreaOf(ring))
  return multiArea(polygonClipping.difference(toMulti(ring), cover) as MultiPolygon)
}

function ringAreaOf(ring: Ring): number {
  let sum = 0
  for (let i = 0; i < ring.length; i++) {
    const [x1, y1] = ring[i]
    const [x2, y2] = ring[(i + 1) % ring.length]
    sum += x1 * y2 - x2 * y1
  }
  return sum / 2
}

/** 单块有效可排区：一个外环 + 零至多个孔洞。 */
export interface PlacementArea {
  outer: Ring
  holes: Ring[]
}

/**
 * 有效可排区（画布坐标，毫米场景 Y 向下）：
 * - null = 无启用布置区，维持旧的全房间行为（调用方继续用空间轮廓）；
 * - [] = 有启用区但与空间净轮廓没有交集，当前不可排布任何家具；
 * - 其他 = 启用区并集 ∩ 空间净轮廓，保留多块与孔洞。
 * 轮廓无效（自交/退化）的启用区被跳过并应由 layoutRegionProblem 在 UI 标记。
 */
export function allowedPlacementAreas(zone: ZoneObject): PlacementArea[] | null {
  const ring = zoneRing(zone)
  const actives = activeLayoutRegionsForZone(zone).filter(region => !ringProblem(layoutRegionRing(zone, region)))
  if (!actives.length) return null
  if (ring.length < 3) return []
  let mp: MultiPolygon = []
  for (const region of actives) {
    const regionRing = layoutRegionRing(zone, region)
    mp = mp.length ? (polygonClipping.union(mp, toMulti(regionRing)) as MultiPolygon) : toMulti(regionRing)
  }
  mp = polygonClipping.intersection(mp, toMulti(ring)) as MultiPolygon
  // polygon-clipping 输出首尾重复的闭合环；归一化为不重复首尾点的环（与 zone.polygon 约定一致，避免下游 ringProblem 误判退化边）。
  const unclose = (ring: Ring): Ring => ring.length > 1 && Math.hypot(ring[0][0] - ring[ring.length - 1][0], ring[0][1] - ring[ring.length - 1][1]) <= 0.02 ? ring.slice(0, -1) : ring
  return mp
    .filter(polygon => polygon.length && polygon[0].length >= 3)
    .map(polygon => ({ outer: unclose(polygon[0]), holes: polygon.slice(1).map(unclose) }))
    .filter(area => Math.abs(ringAreaOf(area.outer)) > 1)
}

export function placementAreasToMulti(areas: PlacementArea[]): MultiPolygon {
  return areas.map(area => [area.outer, ...area.holes])
}

/**
 * 家具占地环是否完整落在有效可排区内。areas 为 null（无启用区）时恒 true，由调用方按旧规则检查空间轮廓。
 * toleranceMm2 默认 1 mm²，与 ringProblem 的面积下限同量级，仅吸收布尔运算浮点尾差。
 */
export function ringWithinPlacementAreas(areas: PlacementArea[] | null, ring: Ring, toleranceMm2 = 1): boolean {
  if (areas === null) return true
  if (!areas.length || ring.length < 3) return false
  return ringAreaOutside(ring, placementAreasToMulti(areas)) <= toleranceMm2
}

/**
 * 布置区校验（绘制/编辑提交时拒绝落库；空间几何变更后用于标记「需人工修复」）：
 * 轮廓自交/退化/面积过小 → 拒绝；超出空间净轮廓 → 拒绝或标记（同一判定）。
 */
export function layoutRegionProblem(zone: ZoneObject, region: LayoutRegion): string | null {
  const ring = layoutRegionRing(zone, region)
  const problem = ringProblem(ring)
  if (problem) return '布置区' + problem
  const space = zoneRing(zone)
  if (space.length < 3) return '布置区所属空间缺少轮廓'
  if (ringAreaOutside(ring, toMulti(space)) > 1) return '布置区超出空间净轮廓，请调整边界'
  return null
}

/** 点到线段距离。 */
function pointSegmentDistance(px: number, py: number, ax: number, ay: number, bx: number, by: number): number {
  const dx = bx - ax, dy = by - ay
  const len2 = dx * dx + dy * dy
  const t = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2))
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
}

/** 射线（p + t·n, t>0）与线段的最近交点距离；无交点返回 null。 */
function raySegmentDistance(px: number, py: number, nx: number, ny: number, ax: number, ay: number, bx: number, by: number): number | null {
  const dx = bx - ax, dy = by - ay
  const det = nx * dy - ny * dx
  if (Math.abs(det) < 1e-12) return null
  const t = ((ax - px) * dy - (ay - py) * dx) / det
  const u = ((ax - px) * ny - (ay - py) * nx) / det
  if (t <= 1e-6 || u < -1e-9 || u > 1 + 1e-9) return null
  return t
}

/**
 * 每条边的尺寸与距墙量测（只读展示，毫米；非毫米场景返回空）。
 * 距墙 = 沿该边外法线从边中点到空间轮廓边界的距离（轴对齐矩形即通常意义的墙距）；
 * 法线方向无交点时退化为中点到最近轮廓边的距离。这是量测读数，不表示几何已内缩。
 */
export function layoutRegionEdgeMeasurements(zone: ZoneObject, region: LayoutRegion): Array<{ edgeIndex: number; lengthMm: number; wallDistanceMm: number }> {
  if (zone.reviewUnit !== 'mm') return []
  const ring = layoutRegionRing(zone, region)
  const space = zoneRing(zone)
  if (ring.length < 3 || space.length < 3) return []
  const signedArea = ringAreaOf(ring)
  const out: Array<{ edgeIndex: number; lengthMm: number; wallDistanceMm: number }> = []
  for (let i = 0; i < ring.length; i++) {
    const [ax, ay] = ring[i]
    const [bx, by] = ring[(i + 1) % ring.length]
    const midX = (ax + bx) / 2, midY = (ay + by) / 2
    const lengthMm = Math.hypot(bx - ax, by - ay)
    // 外法线：CCW 环取方向右转 90°，CW 环取左转 90°。
    let nx = signedArea > 0 ? (by - ay) : -(by - ay)
    let ny = signedArea > 0 ? -(bx - ax) : (bx - ax)
    const norm = Math.hypot(nx, ny) || 1
    nx /= norm
    ny /= norm
    let wall = Infinity
    let fallback = Infinity
    for (let j = 0; j < space.length; j++) {
      const [cx, cy] = space[j]
      const [dx, dy] = space[(j + 1) % space.length]
      const hit = raySegmentDistance(midX, midY, nx, ny, cx, cy, dx, dy)
      if (hit !== null) wall = Math.min(wall, hit)
      fallback = Math.min(fallback, pointSegmentDistance(midX, midY, cx, cy, dx, dy))
    }
    out.push({ edgeIndex: i, lengthMm, wallDistanceMm: Number.isFinite(wall) ? wall : fallback })
  }
  return out
}

/** 刷新 region.wallOffsetsMm（只读量测快照；量测失败时置为空，不保留旧值冒充当前几何）。 */
export function measureLayoutRegionWallOffsets(zone: ZoneObject, region: LayoutRegion): LayoutRegion {
  const measurements = layoutRegionEdgeMeasurements(zone, region)
  return { ...region, wallOffsetsMm: measurements.length ? measurements.map(({ edgeIndex, wallDistanceMm }) => ({ edgeIndex, distanceMm: Math.round(wallDistanceMm * 100) / 100 })) : undefined }
}

/** 旧画布兼容：原始 JSON → 合法布置区数组。缺失/畸形字段按空数组或跳过处理，严禁自动创建任何布置区。 */
export function sanitizeLayoutRegions(spaceId: string, raw: unknown): LayoutRegion[] {
  if (!Array.isArray(raw)) return []
  const regions: LayoutRegion[] = []
  const seen = new Set<string>()
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue
    const candidate = item as Record<string, unknown>
    const regionId = typeof candidate.regionId === 'string' && candidate.regionId ? candidate.regionId : null
    const polygon = Array.isArray(candidate.polygonMm)
      ? (candidate.polygonMm as unknown[]).filter((point): point is [number, number] => Array.isArray(point) && point.length === 2 && point.every(Number.isFinite))
      : []
    if (!regionId || seen.has(regionId) || polygon.length < 3) continue
    seen.add(regionId)
    regions.push({
      regionId,
      spaceId,
      name: typeof candidate.name === 'string' && candidate.name ? candidate.name : '布置区',
      polygonMm: polygon,
      isPrimary: candidate.isPrimary === true,
      visible: candidate.visible !== false,
      enabled: candidate.enabled !== false,
      wallOffsetsMm: Array.isArray(candidate.wallOffsetsMm)
        ? (candidate.wallOffsetsMm as unknown[]).filter((entry): entry is { edgeIndex: number; distanceMm: number } => !!entry && typeof entry === 'object' && Number.isInteger((entry as { edgeIndex?: unknown }).edgeIndex) && Number.isFinite((entry as { distanceMm?: unknown }).distanceMm))
        : undefined,
    })
  }
  // 同空间最多一个主区：保留数组序第一个。
  let primarySeen = false
  for (const region of regions) {
    if (region.isPrimary && primarySeen) region.isPrimary = false
    if (region.isPrimary) primarySeen = true
  }
  return regions
}

/** 设为主布置区（同空间唯一）；regionId 为空表示不设主区。 */
export function setPrimaryLayoutRegion(regions: LayoutRegion[], regionId: string | null): LayoutRegion[] {
  return regions.map(region => ({ ...region, isPrimary: regionId !== null && region.regionId === regionId }))
}

/** 删除布置区；被删的是主区时按数组序把第一个剩余区提升为主区，无剩余则为空，不遗留悬空 ID。 */
export function removeLayoutRegion(regions: LayoutRegion[], regionId: string): LayoutRegion[] {
  const remaining = regions.filter(region => region.regionId !== regionId)
  if (remaining.length === regions.length) return regions
  if (remaining.length && !remaining.some(region => region.isPrimary)) {
    return remaining.map((region, index) => index === 0 ? { ...region, isPrimary: true } : region)
  }
  return remaining
}

/** 整体平移（WORLD_MM 增量，即拖动在画布上的毫米位移，X 同向、Y 与屏幕相反）。 */
export function moveLayoutRegion(region: LayoutRegion, dxMm: number, dyMm: number): LayoutRegion {
  return { ...region, polygonMm: region.polygonMm.map(([x, y]): [number, number] => [x + dxMm, y + dyMm]), wallOffsetsMm: undefined }
}

/** 顶点调整（WORLD_MM 坐标）。 */
export function moveLayoutRegionVertex(region: LayoutRegion, vertexIndex: number, x: number, y: number): LayoutRegion {
  if (!Number.isInteger(vertexIndex) || vertexIndex < 0 || vertexIndex >= region.polygonMm.length) return region
  return { ...region, polygonMm: region.polygonMm.map((point, index): [number, number] => index === vertexIndex ? [x, y] : point), wallOffsetsMm: undefined }
}

export function nextLayoutRegionName(regions: LayoutRegion[]): string {
  let index = regions.length + 1
  const names = new Set(regions.map(region => region.name))
  while (names.has('布置区 ' + index)) index++
  return '布置区 ' + index
}

/** 射线法点在环内（含边界容差 1e-6）。 */
export function pointInRing(ring: Ring, x: number, y: number): boolean {
  let inside = false
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i]
    const [xj, yj] = ring[j]
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside
  }
  return inside
}
