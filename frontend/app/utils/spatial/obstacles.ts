import type { OfficeObject, ZoneObject } from '~/types/office'
import type { LayoutExclusion } from '~/types/layoutPlanning'
import type { SpatialObstacle, SpatialObstacleKind } from '~/types/spatial'
import { createUuid } from '~/utils/uuid'
import { polygonBounds } from './geometry'

export function obstacleLabel(kind: SpatialObstacleKind) {
  return ({ COLUMN: '柱', SHAFT: '井道', NO_PLACEMENT: '禁放区', DOOR_CLEARANCE: '门洞净空', WINDOW: '窗', OTHER: '固定障碍' } as const)[kind]
}

/** 窗的语义是中心线；1mm 标注带仅兼容既有 POLYGON 存储/校验，不代表实际窗厚。 */
export function windowLinePoints(obstacle: { geometry: { outer: Array<[number, number]> } }): Array<[number, number]> {
  const ring = obstacle.geometry.outer
  if (ring.length !== 4) return []
  const midpoint = (a: number[], b: number[]): [number, number] => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]
  const length = (a: number[], b: number[]) => Math.hypot(a[0] - b[0], a[1] - b[1])
  return length(ring[0], ring[1]) >= length(ring[1], ring[2])
    ? [midpoint(ring[0], ring[3]), midpoint(ring[1], ring[2])]
    : [midpoint(ring[0], ring[1]), midpoint(ring[3], ring[2])]
}

export function windowLineObstacle(spaceId: string, start: { x: number; y: number }, end: { x: number; y: number }, obstacleId = createUuid()): SpatialObstacle | null {
  const dx = end.x - start.x, dy = end.y - start.y, length = Math.hypot(dx, dy)
  if (![start.x, start.y, end.x, end.y].every(Number.isFinite) || length < 1) return null
  const nx = -dy / length * 0.5, ny = dx / length * 0.5
  const outer: Array<[number, number]> = [[start.x + nx, start.y + ny], [end.x + nx, end.y + ny], [end.x - nx, end.y - ny], [start.x - nx, start.y - ny]]
  return { obstacleId, kind: 'WINDOW', spaceId, geometry: { type: 'POLYGON', coordinateSystem: 'WORLD_MM', outer, outerVertexIds: outer.map((_, index) => obstacleId + '-V-' + index), holes: [], holeVertexIds: [] }, excludesFloorArea: false, source: 'MANUAL' }
}

function windowWallEdges(zone: ZoneObject) {
  const ring = zone.reviewUnit === 'mm' ? zone.polygon?.map(([x, y]) => [x, -y]) : null
  return (ring || []).map((a, edgeIndex) => {
    const b = ring![(edgeIndex + 1) % ring!.length], length = Math.hypot(b[0] - a[0], b[1] - a[1])
    return { a, length, ux: (b[0] - a[0]) / length, uy: (b[1] - a[1]) / length, edgeIndex }
  }).filter(edge => Number.isFinite(edge.length) && edge.length >= 1)
}

function nearestWindowWall(zone: ZoneObject, point: { x: number; y: number }, minLength = 1) {
  return windowWallEdges(zone).filter(edge => edge.length + 0.001 >= minLength).map(edge => {
    const t = Math.max(0, Math.min(edge.length, (point.x - edge.a[0]) * edge.ux + (point.y - edge.a[1]) * edge.uy))
    return { ...edge, distance: Math.hypot(point.x - edge.a[0] - edge.ux * t, point.y - edge.a[1] - edge.uy * t) }
  }).sort((a, b) => a.distance - b.distance)[0]
}

/** 绘制起点选墙，终点投影到同一有限墙段；水平、竖直和斜墙使用同一投影。 */
export function windowOnWall(zone: ZoneObject, start: { x: number; y: number }, end: { x: number; y: number }, obstacleId = createUuid()) {
  const wall = nearestWindowWall(zone, start)
  if (!wall) return null
  const project = (p: { x: number; y: number }) => {
    const t = Math.max(0, Math.min(wall.length, (p.x - wall.a[0]) * wall.ux + (p.y - wall.a[1]) * wall.uy))
    return { x: wall.a[0] + t * wall.ux, y: wall.a[1] + t * wall.uy }
  }
  return windowLineObstacle(zone.id, project(start), project(end), obstacleId)
}

export function resizeWindowOnWall(zone: ZoneObject, obstacle: SpatialObstacle, lengthMm: number) {
  const line = windowLinePoints(obstacle)
  if (line.length !== 2 || !Number.isFinite(lengthMm) || lengthMm < 1) return null
  const center = { x: (line[0][0] + line[1][0]) / 2, y: (line[0][1] + line[1][1]) / 2 }
  const wall = nearestWindowWall(zone, center)
  if (!wall || lengthMm > wall.length + 0.001) return null
  const offset = Math.max(lengthMm / 2, Math.min(wall.length - lengthMm / 2, (center.x - wall.a[0]) * wall.ux + (center.y - wall.a[1]) * wall.uy))
  const next = windowLineObstacle(zone.id, { x: wall.a[0] + (offset - lengthMm / 2) * wall.ux, y: wall.a[1] + (offset - lengthMm / 2) * wall.uy }, { x: wall.a[0] + (offset + lengthMm / 2) * wall.ux, y: wall.a[1] + (offset + lengthMm / 2) * wall.uy }, obstacle.obstacleId)
  return next ? { ...obstacle, geometry: next.geometry, excludesFloorArea: false } : null
}

function snapWindowToZone(zone: ZoneObject, obstacle: SpatialObstacle, toleranceMm: number) {
  const unchanged = { obstacle, snapped: false, edgeIndex: null as number | null }
  const line = windowLinePoints(obstacle)
  if (line.length !== 2 || toleranceMm <= 0 || Number.isNaN(toleranceMm)) return unchanged
  const length = Math.hypot(line[1][0] - line[0][0], line[1][1] - line[0][1])
  const center = { x: (line[0][0] + line[1][0]) / 2, y: (line[0][1] + line[1][1]) / 2 }
  const wall = nearestWindowWall(zone, center)
  if (!wall || wall.length + 0.001 < length || wall.distance > toleranceMm) return unchanged
  const next = resizeWindowOnWall(zone, obstacle, length)
  // 最近短墙不足以容纳整窗时不缩短窗或跳往另一墙面。
  if (!next) return unchanged
  return { obstacle: next, snapped: true, edgeIndex: wall.edgeIndex }
}

export function rectangleObstacle(spaceId: string, kind: SpatialObstacleKind, start: { x: number; y: number }, end: { x: number; y: number }, obstacleId = createUuid()): SpatialObstacle | null {
  if (kind === 'DOOR_CLEARANCE') return doorClearanceObstacle(spaceId, start, end, obstacleId)
  const minX = Math.min(start.x, end.x), maxX = Math.max(start.x, end.x)
  const minY = Math.min(start.y, end.y), maxY = Math.max(start.y, end.y)
  if (![minX, minY, maxX, maxY].every(Number.isFinite) || maxX - minX < 1 || maxY - minY < 1) return null
  const outer: Array<[number, number]> = [[minX, minY], [maxX, minY], [maxX, maxY], [minX, maxY]]
  return {
    obstacleId, kind, spaceId,
    geometry: { type: 'POLYGON', coordinateSystem: 'WORLD_MM', outer, outerVertexIds: outer.map((_, index) => obstacleId + '-V-' + index), holes: [], holeVertexIds: [] },
    excludesFloorArea: kind === 'COLUMN' || kind === 'SHAFT', source: 'MANUAL',
  }
}

export function doorClearanceObstacle(spaceId: string, hinge: { x: number; y: number }, end: { x: number; y: number }, obstacleId = createUuid()): SpatialObstacle | null {
  const dx = end.x - hinge.x, dy = end.y - hinge.y, radius = Math.max(Math.abs(dx), Math.abs(dy))
  if (![hinge.x, hinge.y, end.x, end.y, radius].every(Number.isFinite) || Math.abs(dx) < 1 || Math.abs(dy) < 1 || radius < 1) return null
  const signX = dx > 0 ? 1 : -1, signY = dy > 0 ? 1 : -1
  const outer: Array<[number, number]> = [[hinge.x, hinge.y]]
  for (let index = 0; index <= 16; index++) {
    const angle = index * Math.PI / 32
    outer.push([hinge.x + signX * radius * Math.cos(angle), hinge.y + signY * radius * Math.sin(angle)])
  }
  return {
    obstacleId, kind: 'DOOR_CLEARANCE', spaceId,
    geometry: { type: 'POLYGON', coordinateSystem: 'WORLD_MM', outer, outerVertexIds: outer.map((_, index) => obstacleId + '-V-' + index), holes: [], holeVertexIds: [] },
    excludesFloorArea: false, source: 'MANUAL',
  }
}

export function obstacleRing(zone: ZoneObject, obstacle: SpatialObstacle): Array<[number, number]> {
  const factor = zone.reviewUnit === 'mm' ? -1 : 1
  return obstacle.geometry.outer.map(([x, y]) => [x, y * factor])
}

export function obstacleExclusion(zone: ZoneObject, obstacle: SpatialObstacle): LayoutExclusion | null {
  const bounds = polygonBounds({ type: 'POLYGON', coordinateSystem: zone.reviewUnit === 'mm' ? 'WORLD_MM' : 'SOURCE_PAGE', outer: obstacleRing(zone, obstacle), outerVertexIds: [], holes: [], holeVertexIds: [] })
  if (!bounds) return null
  const polygon = obstacleRing(zone, obstacle)
  return { id: obstacle.obstacleId, label: obstacle.label || obstacleLabel(obstacle.kind), x: bounds.minX, y: bounds.minY, w: bounds.maxX - bounds.minX, h: bounds.maxY - bounds.minY, polygon }
}

/** 排版避障只认建筑约束（GEOM-013）；窗为标注型约束（GEOM-015），不扣面积也不参与排版避让；旧 layoutPreferences.exclusions 由 migrateLayoutExclusions 迁移。 */
export function zoneLayoutExclusions(zone: ZoneObject): LayoutExclusion[] {
  return (zone.obstacles || []).filter(obstacle => obstacle.kind !== 'WINDOW').map(obstacle => obstacleExclusion(zone, obstacle)).filter((area): area is LayoutExclusion => !!area)
}

/** 旧排版面板预留区域（layoutPreferences.exclusions）迁移为 NO_PLACEMENT 建筑约束；返回发生实际几何迁移的空间 ID。 */
export function migrateLayoutExclusions(objects: OfficeObject[]) {
  const changedZoneIds: string[] = []
  for (const object of objects) {
    if (object.kind !== 'zone') continue
    const zone = object as ZoneObject
    const preferences = zone.layoutPreferences
    if (!preferences || !('exclusions' in preferences)) continue
    const exclusions = Array.isArray(preferences.exclusions) ? preferences.exclusions : []
    delete preferences.exclusions
    if (!exclusions.length) continue
    const factor = zone.reviewUnit === 'mm' ? -1 : 1
    const obstacles = [...(zone.obstacles || [])]
    for (const area of exclusions) {
      if (![area.x, area.y, area.w, area.h].every(Number.isFinite) || area.w <= 0 || area.h <= 0) continue
      if (obstacles.some(item => item.obstacleId === area.id)) continue
      const obstacle = rectangleObstacle(zone.id, 'NO_PLACEMENT', { x: area.x, y: area.y * factor }, { x: area.x + area.w, y: (area.y + area.h) * factor }, area.id || createUuid())
      if (!obstacle) continue
      if (area.label) obstacle.label = area.label
      obstacles.push(obstacle)
    }
    zone.obstacles = obstacles
    changedZoneIds.push(zone.id)
  }
  return changedZoneIds
}

export function migrateLegacyDoorClearances(objects: OfficeObject[]) {
  const changedZoneIds: string[] = []
  for (const object of objects) {
    if (object.kind !== 'zone' || !object.polygon?.length) continue
    const roomBounds = polygonBounds({ type: 'POLYGON', coordinateSystem: 'SOURCE_PAGE', outer: object.polygon, outerVertexIds: [], holes: [], holeVertexIds: [] })
    if (!roomBounds) continue
    const roomCenter = { x: (roomBounds.minX + roomBounds.maxX) / 2, y: -(roomBounds.minY + roomBounds.maxY) / 2 }
    object.obstacles = object.obstacles?.map(obstacle => {
      if (obstacle.kind !== 'DOOR_CLEARANCE' || obstacle.geometry.outer.length !== 4) return obstacle
      const bounds = polygonBounds(obstacle.geometry)
      if (!bounds) return obstacle
      const corners: Array<[number, number]> = [[bounds.minX, bounds.minY], [bounds.maxX, bounds.minY], [bounds.maxX, bounds.maxY], [bounds.minX, bounds.maxY]]
      const hinge = corners.sort((a, b) => Math.hypot(b[0] - roomCenter.x, b[1] - roomCenter.y) - Math.hypot(a[0] - roomCenter.x, a[1] - roomCenter.y))[0]
      const signX = roomCenter.x === hinge[0] ? hinge[0] === bounds.minX ? 1 : -1 : Math.sign(roomCenter.x - hinge[0])
      const signY = roomCenter.y === hinge[1] ? hinge[1] === bounds.minY ? 1 : -1 : Math.sign(roomCenter.y - hinge[1])
      const radius = Math.max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY)
      const migrated = doorClearanceObstacle(obstacle.spaceId!, { x: hinge[0], y: hinge[1] }, { x: hinge[0] + signX * radius, y: hinge[1] + signY * radius }, obstacle.obstacleId)
      if (!migrated) return obstacle
      migrated.source = obstacle.source || 'MANUAL'
      if (!changedZoneIds.includes(object.id)) changedZoneIds.push(object.id)
      return migrated
    })
  }
  return changedZoneIds
}

export function moveObstacle(obstacle: SpatialObstacle, dx: number, dy: number): SpatialObstacle {
  return {
    ...obstacle,
    geometry: {
      ...obstacle.geometry,
      outer: obstacle.geometry.outer.map(([x, y]) => [x + dx, y + dy]),
      holes: obstacle.geometry.holes.map(ring => ring.map(([x, y]) => [x + dx, y + dy])),
    },
  }
}

export function snapObstacleToZone(zone: ZoneObject, obstacle: SpatialObstacle, toleranceMm: number) {
  if (obstacle.kind === 'WINDOW') return snapWindowToZone(zone, obstacle, toleranceMm)
  const unchanged = { obstacle, snapped: false, edgeIndex: null as number | null }
  if (!['COLUMN', 'SHAFT', 'WINDOW'].includes(obstacle.kind) || obstacle.geometry.outer.length !== 4 || zone.reviewUnit !== 'mm' || !Number.isFinite(toleranceMm) || toleranceMm <= 0) return unchanged
  const bounds = polygonBounds(obstacle.geometry), ring = zone.polygon?.map(([x, y]): [number, number] => [x, -y])
  if (!bounds || !ring?.length) return unchanged
  let candidate: { distance: number; dx: number; dy: number; edgeIndex: number } | null = null
  const offer = (distance: number, dx: number, dy: number, edgeIndex: number) => {
    if (distance <= toleranceMm && (!candidate || distance < candidate.distance)) candidate = { distance, dx, dy, edgeIndex }
  }
  for (let index = 0; index < ring.length; index++) {
    const start = ring[index], end = ring[(index + 1) % ring.length]
    if (Math.abs(start[0] - end[0]) < 0.001 && Math.abs(start[1] - end[1]) > 1) {
      const minY = Math.min(start[1], end[1]), maxY = Math.max(start[1], end[1])
      if (bounds.maxY < minY - 0.001 || bounds.minY > maxY + 0.001) continue
      offer(Math.abs(bounds.minX - start[0]), start[0] - bounds.minX, 0, index)
      offer(Math.abs(bounds.maxX - start[0]), start[0] - bounds.maxX, 0, index)
    } else if (Math.abs(start[1] - end[1]) < 0.001 && Math.abs(start[0] - end[0]) > 1) {
      const minX = Math.min(start[0], end[0]), maxX = Math.max(start[0], end[0])
      if (bounds.maxX < minX - 0.001 || bounds.minX > maxX + 0.001) continue
      offer(Math.abs(bounds.minY - start[1]), 0, start[1] - bounds.minY, index)
      offer(Math.abs(bounds.maxY - start[1]), 0, start[1] - bounds.maxY, index)
    }
  }
  if (!candidate) return unchanged
  return { obstacle: moveObstacle(obstacle, candidate.dx, candidate.dy), snapped: true, edgeIndex: candidate.edgeIndex }
}

export function reshapeObstacle(obstacle: SpatialObstacle, handle: string, point: { x: number; y: number }): SpatialObstacle | null {
  let next: SpatialObstacle | null = null
  if (obstacle.kind === 'DOOR_CLEARANCE') {
    const hinge = obstacle.geometry.outer[0], first = obstacle.geometry.outer[1], last = obstacle.geometry.outer.at(-1)
    if (!hinge || !first || !last) return null
    const center = { x: hinge[0], y: hinge[1] }
    const radius = Math.hypot(point.x - center.x, point.y - center.y)
    next = doorClearanceObstacle(obstacle.spaceId!, center, { x: center.x + Math.sign(first[0] - center.x) * radius, y: center.y + Math.sign(last[1] - center.y) * radius }, obstacle.obstacleId)
  } else {
    const index = Number(handle), anchor = obstacle.geometry.outer[(index + 2) % 4]
    if (!Number.isInteger(index) || index < 0 || index > 3 || !anchor) return null
    next = rectangleObstacle(obstacle.spaceId!, obstacle.kind, { x: anchor[0], y: anchor[1] }, point, obstacle.obstacleId)
  }
  if (!next) return null
  next.source = obstacle.source
  next.excludesFloorArea = obstacle.excludesFloorArea
  return next
}
