import type { OfficeObject, ZoneObject } from '~/types/office'
import type { LayoutExclusion } from '~/types/layoutPlanning'
import type { SpatialObstacle, SpatialObstacleKind } from '~/types/spatial'
import { createUuid } from '~/utils/uuid'
import { polygonBounds } from './geometry'

export function obstacleLabel(kind: SpatialObstacleKind) {
  return ({ COLUMN: '柱', SHAFT: '井道', NO_PLACEMENT: '禁放区', DOOR_CLEARANCE: '门洞净空', OTHER: '固定障碍' } as const)[kind]
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

/** 排版避障只认建筑约束（GEOM-013）；旧 layoutPreferences.exclusions 由 migrateLayoutExclusions 迁移。 */
export function zoneLayoutExclusions(zone: ZoneObject): LayoutExclusion[] {
  return (zone.obstacles || []).map(obstacle => obstacleExclusion(zone, obstacle)).filter((area): area is LayoutExclusion => !!area)
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
  const unchanged = { obstacle, snapped: false, edgeIndex: null as number | null }
  if (!['COLUMN', 'SHAFT'].includes(obstacle.kind) || obstacle.geometry.outer.length !== 4 || zone.reviewUnit !== 'mm' || !Number.isFinite(toleranceMm) || toleranceMm <= 0) return unchanged
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
