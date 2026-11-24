import type { FurnitureObject } from '~/types/office'
import { rectOverlapsBlocker, rotatedFurnitureFootprint } from './layoutConstraints'
import { meetingRingTable } from './meetingRingTable'
import { footprintsOverlap, furnitureFootprintRects } from './furnitureFootprint'

type Point = [number, number]
type Footprint = Pick<FurnitureObject, 'x' | 'y' | 'w' | 'h' | 'rotation' | 'furnitureTemplateId' | 'moduleSnapshot' | 'mirrorX' | 'wingWidthMm'>
const V_TABLE = 'table-meeting-video-v'
const ROUND_TABLES = ['table-meeting-round', 'table-meeting-round-two']

const glyphId = (item: Footprint) => item.moduleSnapshot?.glyphTemplateId || item.furnitureTemplateId || ''

function hasMeetingGeometry(id: string) {
  return id.startsWith('chair-meeting-') || id.startsWith('table-meeting-') || id.startsWith('table-conference-')
}

/** Convex parts used for furniture-to-furniture checks. Ring openings remain reserved. */
function parts(item: Footprint): Point[][] {
  const blocks = furnitureFootprintRects(item)
  if (blocks.length > 1) return blocks.map(({ x, y, w, h }) => [[x, y], [x + w, y], [x + w, y + h], [x, y + h]])
  const { w, h } = item
  const id = glyphId(item)
  const ring = meetingRingTable(id, w, h)
  let local: Point[][] = [[[0, 0], [w, 0], [w, h], [0, h]]]
  if (ring || ROUND_TABLES.includes(id)) {
    const rx = ring?.radiusX ?? w / 2, ry = ring?.radiusY ?? h / 2
    const run = ring?.straightRun ?? 0
    // Intersect consecutive tangents at 7.5° intervals to enclose each curve.
    const tangent = (index: number, offset: number): Point => {
      const angle = (index + 0.5) * Math.PI / 24, scale = 1 / Math.cos(Math.PI / 48)
      return [rx + offset + rx * Math.cos(angle) * scale, ry + ry * Math.sin(angle) * scale]
    }
    local = [run ? [
      ...Array.from({ length: 24 }, (_, i) => tangent(i - 12, run)),
      ...Array.from({ length: 24 }, (_, i) => tangent(i + 12, 0)),
    ] : Array.from({ length: 48 }, (_, i) => tangent(i, 0))]
  } else if (id === V_TABLE) {
    local = [
      [[0, h * 0.162], [w * 0.093, 0], [w / 2, h * 0.677], [w / 2, h]],
      [[w / 2, h], [w / 2, h * 0.677], [w * 0.907, 0], [w, h * 0.162]],
    ]
  }
  const radians = item.rotation * Math.PI / 180
  const c = Math.cos(radians), s = Math.sin(radians)
  return local.map(part => part.map(([x, y]) => [
    item.x + w / 2 + (x - w / 2) * c - (y - h / 2) * s,
    item.y + h / 2 + (x - w / 2) * s + (y - h / 2) * c,
  ]))
}

function separated(a: Point[], b: Point[], gap: number) {
  return a.some(([x, y], index) => {
    const [nextX, nextY] = a[(index + 1) % a.length]!
    const length = Math.hypot(nextX - x, nextY - y)
    if (length < 0.001) return false
    const nx = (y - nextY) / length, ny = (nextX - x) / length
    const pa = a.map(point => point[0] * nx + point[1] * ny)
    const pb = b.map(point => point[0] * nx + point[1] * ny)
    return Math.min(Math.max(...pa), Math.max(...pb)) <= Math.max(Math.min(...pa), Math.min(...pb)) - gap + 0.001
  })
}

export function furnitureIntersects(a: Footprint, b: Footprint, gap = 0) {
  if (!rectOverlapsBlocker(rotatedFurnitureFootprint(a), rotatedFurnitureFootprint(b), gap)) return false
  if (!hasMeetingGeometry(glyphId(a)) && !hasMeetingGeometry(glyphId(b))) return footprintsOverlap(a, b, gap)
  const otherParts = parts(b)
  return parts(a).some(first => otherParts.some(second => !separated(first, second, gap) && !separated(second, first, gap)))
}
