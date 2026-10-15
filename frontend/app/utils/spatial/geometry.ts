import type { RectangleFrame, SpatialGeometry, SpatialSpace } from '~/types/spatial'

export function ringArea(ring: Array<[number, number]>): number {
  let sum = 0
  const n = ring.length
  for (let i = 0; i < n; i++) {
    const [x1, y1] = ring[i]
    const [x2, y2] = ring[(i + 1) % n]
    sum += x1 * y2 - x2 * y1
  }
  return sum / 2
}

export function polygonArea(geometry: SpatialGeometry): number {
  if (!geometry) return 0
  let area = Math.abs(ringArea(geometry.outer))
  for (const hole of geometry.holes || []) {
    area -= Math.abs(ringArea(hole))
  }
  return Math.max(0, area)
}

export function polygonCentroid(geometry: SpatialGeometry): { x: number; y: number } | null {
  if (!geometry?.outer?.length) return null
  const ring = geometry.outer
  let cx = 0
  let cy = 0
  let a = 0
  const n = ring.length
  for (let i = 0; i < n; i++) {
    const [x0, y0] = ring[i]
    const [x1, y1] = ring[(i + 1) % n]
    const cross = x0 * y1 - x1 * y0
    cx += (x0 + x1) * cross
    cy += (y0 + y1) * cross
    a += cross
  }
  const area = a / 2
  if (Math.abs(area) < 1e-12) return null
  cx /= 6 * area
  cy /= 6 * area
  return { x: cx, y: cy }
}

export function polygonBounds(geometry: SpatialGeometry): { minX: number; minY: number; maxX: number; maxY: number } | null {
  if (!geometry?.outer?.length) return null
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const [x, y] of geometry.outer) {
    minX = Math.min(minX, x)
    minY = Math.min(minY, y)
    maxX = Math.max(maxX, x)
    maxY = Math.max(maxY, y)
  }
  return { minX, minY, maxX, maxY }
}

export function createVertexIds(count: number): string[] {
  return Array.from({ length: count }, (_, i) => `V-${i}`)
}

export function ensureVertexIds(geometry: SpatialGeometry): SpatialGeometry {
  if (geometry.outerVertexIds?.length === geometry.outer.length) return geometry
  return {
    ...geometry,
    outerVertexIds: createVertexIds(geometry.outer.length),
  }
}

export function inferRectangleFrame(geometry: SpatialGeometry): RectangleFrame | null {
  if (geometry.outer.length !== 4) return null
  const pts = geometry.outer
  const ids = geometry.outerVertexIds
  if (!ids || ids.length !== 4) return null

  const edges: Array<{ dx: number; dy: number; len: number; ang: number }> = []
  for (let i = 0; i < 4; i++) {
    const [x1, y1] = pts[i]
    const [x2, y2] = pts[(i + 1) % 4]
    const dx = x2 - x1
    const dy = y2 - y1
    const len = Math.hypot(dx, dy)
    if (len < 0.01) return null
    edges.push({ dx, dy, len, ang: Math.atan2(dy, dx) })
  }

  const eps = 1e-6
  const parallel = (a: number, b: number) => Math.abs(normAngle(a - b)) < eps
  const perpendicular = (a: number, b: number) => {
    const d = Math.abs(normAngle(a - b))
    return Math.abs(d - Math.PI / 2) < eps
  }

  if (!parallel(edges[0].ang, edges[2].ang) || !parallel(edges[1].ang, edges[3].ang)) return null
  if (!perpendicular(edges[0].ang, edges[1].ang)) return null
  if (Math.abs(edges[0].len - edges[2].len) > 0.01 || Math.abs(edges[1].len - edges[3].len) > 0.01) return null

  let uDir = normAngle(edges[0].ang)
  const alt = normAngle(edges[1].ang)
  if (Math.abs(alt) < Math.abs(uDir)) uDir = alt
  const theta = uDir

  const cu = Math.cos(theta)
  const su = Math.sin(theta)
  const u = pts.map(([x, y]) => x * cu + y * su)
  const v = pts.map(([x, y]) => -x * su + y * cu)

  const argMin = (fn: (i: number) => number) => {
    let best = 0
    let bestVal = fn(0)
    for (let i = 1; i < 4; i++) {
      const val = fn(i)
      if (val < bestVal) {
        bestVal = val
        best = i
      }
    }
    return best
  }

  return {
    angleDeg: (theta * 180) / Math.PI,
    cornerVertexIds: {
      u0v0: ids[argMin((i) => u[i] + v[i])],
      u1v0: ids[argMin((i) => -u[i] + v[i])],
      u1v1: ids[argMin((i) => -u[i] - v[i])],
      u0v1: ids[argMin((i) => u[i] - v[i])],
    },
  }
}

export function resizeRectangle(
  geometry: SpatialGeometry,
  frame: RectangleFrame,
  widthMm: number,
  depthMm: number,
  anchorCornerId?: string,
): SpatialGeometry {
  if (geometry.outer.length !== 4 || !frame) return geometry
  const pts = geometry.outer.map(([x, y]) => ({ x, y }))
  const ids = geometry.outerVertexIds
  const indexById = Object.fromEntries(ids.map((id, i) => [id, i]))

  const c = frame.cornerVertexIds
  const ia = indexById[c.u0v0]
  const ib = indexById[c.u1v0]
  const ic = indexById[c.u1v1]
  const id = indexById[c.u0v1]
  if (ia == null || ib == null || ic == null || id == null) return geometry

  const theta = (frame.angleDeg * Math.PI) / 180
  const cu = Math.cos(theta)
  const su = Math.sin(theta)

  const anchorId = anchorCornerId && indexById[anchorCornerId] != null ? anchorCornerId : c.u0v0
  const anchorIdx = indexById[anchorId]
  const ax = pts[anchorIdx].x
  const ay = pts[anchorIdx].y

  const uBase = ax * cu + ay * su
  const vBase = -ax * su + ay * cu

  const anchorRole = anchorIdx === ia ? 0 : anchorIdx === ib ? 1 : anchorIdx === ic ? 2 : 3
  const uMax = anchorRole === 1 || anchorRole === 2
  const vMax = anchorRole === 2 || anchorRole === 3

  const uMin = uMax ? uBase - widthMm : uBase
  const uMaxVal = uMax ? uBase : uBase + widthMm
  const vMin = vMax ? vBase - depthMm : vBase
  const vMaxVal = vMax ? vBase : vBase + depthMm

  const setUv = (idx: number, u: number, v: number) => {
    pts[idx] = { x: u * cu - v * su, y: u * su + v * cu }
  }

  setUv(ia, uMin, vMin)
  setUv(ib, uMaxVal, vMin)
  setUv(ic, uMaxVal, vMaxVal)
  setUv(id, uMin, vMaxVal)

  return {
    ...geometry,
    outer: pts.map((p) => [p.x, p.y]),
  }
}

export function moveVertex(geometry: SpatialGeometry, vertexId: string, x: number, y: number): SpatialGeometry {
  const idx = geometry.outerVertexIds.indexOf(vertexId)
  if (idx < 0) return geometry
  const outer = geometry.outer.map(([px, py], i) => (i === idx ? [x, y] : [px, py])) as Array<[number, number]>
  return { ...geometry, outer }
}

export function updateSpaceGeometry(space: SpatialSpace, geometry: SpatialGeometry): SpatialSpace {
  const isRect = inferRectangleFrame(geometry)
  return {
    ...space,
    designGeometry: ensureVertexIds(geometry),
    rectangleFrame: isRect,
    geometryRevision: space.geometryRevision + 1,
    derived: {
      ...space.derived,
      areaM2: polygonArea(geometry) / 1_000_000,
      boundaryAreaM2: polygonArea(geometry) / 1_000_000,
      rectangleDimensions: isRect
        ? {
            widthMm: rectWidth(geometry, isRect),
            depthMm: rectDepth(geometry, isRect),
          }
        : null,
      boundsMm: polygonBounds(geometry),
    },
  }
}

function rectWidth(geometry: SpatialGeometry, frame: RectangleFrame): number {
  const map = Object.fromEntries(geometry.outerVertexIds.map((id, i) => [id, geometry.outer[i]]))
  const a = map[frame.cornerVertexIds.u0v0]
  const b = map[frame.cornerVertexIds.u1v0]
  return Math.hypot(b[0] - a[0], b[1] - a[1])
}

function rectDepth(geometry: SpatialGeometry, frame: RectangleFrame): number {
  const map = Object.fromEntries(geometry.outerVertexIds.map((id, i) => [id, geometry.outer[i]]))
  const a = map[frame.cornerVertexIds.u0v0]
  const d = map[frame.cornerVertexIds.u0v1]
  return Math.hypot(d[0] - a[0], d[1] - a[1])
}

function normAngle(rad: number): number {
  while (rad <= -Math.PI) rad += Math.PI
  while (rad > Math.PI) rad -= Math.PI
  if (rad > Math.PI / 2) rad -= Math.PI
  if (rad <= -Math.PI / 2) rad += Math.PI
  return rad
}
