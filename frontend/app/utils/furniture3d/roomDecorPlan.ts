import type { FurnitureObject } from '~/types/office'
import type { WhiteModelScene } from '~/utils/whiteModel'
import { furnitureFootprintRects } from '~/utils/furnitureFootprint'
import { pointInRing } from '~/utils/spatial/layoutRegions'
import { ringIntersectionArea, ringsOverlap } from '~/utils/spatial/review'

export const roomDecorThemes = {
  'slate': { label: '浅灰商务', floor: '#91999f', seam: '#747d85', wall: '#e1e4e5', accent: '#536a7a', panel: '#8298a4', pot: '#dedbd4', light: 0xfff5e7 },
  'oak': { label: '暖木自然', floor: '#c6a888', seam: '#a68668', wall: '#ede4d7', accent: '#737f67', panel: '#a47c57', pot: '#ab7456', light: 0xffe6c8 },
} as const

export type RoomDecorTheme = keyof typeof roomDecorThemes
type Point = [number, number]

export interface RoomDecorPlan {
  version: 1
  units: 'mm'
  theme: RoomDecorTheme
  panel: { wallIndex: number; center: Point; width: number; height: number; midHeight: number; rotationRad: number } | null
  plant: Point | null
  lights: Array<{ center: Point; length: number; height: number; rotationRad: number }>
  eye: { position: Point; target: Point } | null
}

const rectangle = (x: number, y: number, w: number, d: number): Point[] =>
  [[x, y], [x + w, y], [x + w, y + d], [x, y + d]]

/** Produces JSON-compatible decoration and camera data without modifying the input layout. */
export function planRoomDecor(scene: WhiteModelScene, furniture: FurnitureObject[], theme: RoomDecorTheme, doorClearances: Point[][]): RoomDecorPlan {
  const { floor, walls, bounds } = scene
  const occupied = [
    ...furniture.flatMap(item => furnitureFootprintRects(item).map(rect => rectangle(rect.x, rect.y, rect.w, rect.h))),
    ...scene.volumes.filter(volume => volume.source === 'obstacle').map(volume => volume.ring ??
      rectangle(volume.cx - volume.w / 2, volume.cy - volume.d / 2, volume.w, volume.d)),
  ]
  const fits = (center: Point, radius: number, blockers: Point[][]) => {
    const box = rectangle(center[0] - radius, center[1] - radius, radius * 2, radius * 2)
    return ringIntersectionArea(box, floor) >= 4 * radius ** 2 - 1 && !blockers.some(blocker => ringsOverlap(box, blocker))
  }
  const edges = walls.map((wall, index) => {
    const [ax, ay] = wall.a
    const dx = wall.b[0] - ax, dy = wall.b[1] - ay
    const length = Math.hypot(dx, dy) || 1
    const angle = Math.atan2(dy, dx)
    const nx = -dy / length, ny = dx / length
    const mid: Point = [ax + dx / 2, ay + dy / 2]
    const sign = pointInRing(floor, mid[0] + nx * 160, mid[1] + ny * 160) ? 1 : -1
    return { wall, index, length, angle, at: (along: number, depth: number): Point =>
      [ax + dx / length * along + nx * depth * sign, ay + dy / length * along + ny * depth * sign] }
  }).sort((a, b) => b.length - a.length)

  const accent = edges.find(edge => edge.length >= 1500 && edge.wall.heightMm >= 2000 && !edge.wall.openings?.length)
  const panel = accent ? {
    wallIndex: accent.index, center: accent.at(accent.length / 2, accent.wall.thicknessMm / 2 + 26),
    width: Math.min(accent.length * 0.72, 5400), height: 1000, midHeight: 1580, rotationRad: accent.angle,
  } : null
  const corners = edges.flatMap(edge => edge.length >= 1800
    ? [edge.at(400, 350), edge.at(edge.length - 400, 350)] : [])
  // Leave at least 750 mm between the plant's 350 mm envelope and any planned furniture or obstacle.
  const plant = corners.find(center => fits(center, 180, []) && ![...occupied, ...doorClearances, ...scene.regions].some(blocker =>
    ringsOverlap(rectangle(center[0] - 930, center[1] - 930, 1860, 1860), blocker))) ?? null

  const center: Point = [(bounds.minX + bounds.maxX) / 2, (bounds.minY + bounds.maxY) / 2]
  const width = bounds.maxX - bounds.minX, depth = bounds.maxY - bounds.minY
  const horizontal = width >= depth
  const lightLength = Math.min(1900, Math.max(width, depth) * 0.27)
  const lights = [-1, 0, 1].flatMap(offset => {
    const point: Point = horizontal ? [center[0] + offset * width * 0.23, center[1]] : [center[0], center[1] + offset * depth * 0.23]
    return lightLength >= 500 && fits(point, lightLength / 2 + 90, []) ? [{
      center: point, length: lightLength, height: Math.min(...walls.map(wall => wall.heightMm)) - 180,
      rotationRad: horizontal ? 0 : Math.PI / 2,
    }] : []
  })

  const target = panel?.center ?? center
  const cameraPlaces = [
    ...edges.flatMap(edge => [0.22, 0.5, 0.78].map(fraction => edge.at(edge.length * fraction, 420))),
    ...[0.2, 0.4, 0.6, 0.8].flatMap(x => [0.2, 0.4, 0.6, 0.8].map(y =>
      [bounds.minX + width * x, bounds.minY + depth * y] as Point)),
  ]
  const position = cameraPlaces.filter(point => fits(point, 180, occupied)).sort((a, b) =>
    Math.hypot(b[0] - target[0], b[1] - target[1]) - Math.hypot(a[0] - target[0], a[1] - target[1]))
    .find(point => Math.hypot(point[0] - target[0], point[1] - target[1]) >= 1200 &&
      [0.2, 0.4, 0.6, 0.8].every(t => pointInRing(floor, point[0] + (target[0] - point[0]) * t, point[1] + (target[1] - point[1]) * t)))
  return { version: 1, units: 'mm', theme, panel, plant, lights, eye: position ? { position, target } : null }
}
