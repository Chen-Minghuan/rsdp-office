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

/**
 * 矩形 × 单环多边形裁剪（LAYOUT-027）：返回覆盖交集的轴对齐矩形集合。
 * 扫描线按顶点 y 分段采样（奇偶规则求交），同跨度相邻段合并为大矩形。
 * 轴对齐多边形结果精确；斜边在顶点 y 处阶梯近似（偏差由 eps 控制）。
 * 输入矩形或环无效时返回空数组。
 */
export function clipRectToRingRects(
  rect: { x: number; y: number; w: number; h: number },
  ring: Array<[number, number]>,
  eps = 0.5,
): Array<{ x: number; y: number; w: number; h: number }> {
  const x0 = rect.x, x1 = rect.x + rect.w, y0 = rect.y, y1 = rect.y + rect.h
  if (![x0, x1, y0, y1].every(Number.isFinite) || !(x1 > x0 && y1 > y0) || ring.length < 3) return []
  const ys: number[] = []
  for (const v of [y0, ...ring.map(([, py]) => py).filter(py => py > y0 + eps && py < y1 - eps), y1].sort((a, b) => a - b)) {
    if (!ys.length || v - ys[ys.length - 1] > eps) ys.push(v)
  }
  // 某一水平扫描线与环的奇偶交叉区间（已按矩形 x 范围裁剪）。
  const intervalsAt = (midY: number): Array<{ x: number; w: number }> => {
    const xs: number[] = []
    for (let i = 0; i < ring.length; i++) {
      const [ax, ay] = ring[i], [bx, by] = ring[(i + 1) % ring.length]
      if ((ay <= midY) === (by <= midY)) continue
      xs.push(ax + ((midY - ay) / (by - ay)) * (bx - ax))
    }
    xs.sort((a, b) => a - b)
    const out: Array<{ x: number; w: number }> = []
    for (let i = 0; i + 1 < xs.length; i += 2) {
      const from = Math.max(x0, xs[i]), to = Math.min(x1, xs[i + 1])
      if (to - from > eps) out.push({ x: from, w: to - from })
    }
    return out
  }
  const sameIntervals = (a: Array<{ x: number; w: number }>, b: Array<{ x: number; w: number }>) =>
    a.length === b.length && a.every((seg, i) => Math.abs(seg.x - b[i].x) <= eps && Math.abs(seg.w - b[i].w) <= eps)
  const rects: Array<{ x: number; y: number; w: number; h: number }> = []
  let open: Array<{ x: number; w: number }> = [], openY = y0
  for (let s = 0; s + 1 < ys.length; s++) {
    const ya = ys[s], yb = ys[s + 1]
    if (yb - ya <= eps) continue
    const intervals = intervalsAt((ya + yb) / 2)
    if (open.length && !sameIntervals(open, intervals)) {
      for (const seg of open) rects.push({ x: seg.x, y: openY, w: seg.w, h: ya - openY })
      open = []
    }
    if (intervals.length && !open.length) { open = intervals; openY = ya }
  }
  for (const seg of open) rects.push({ x: seg.x, y: openY, w: seg.w, h: y1 - openY })
  return rects
}

/** 手动画区的形状模块：拖出包围盒，一次生成对应轮廓多边形（逐顶点，不重复首尾点）。 */
export type SketchShapeKind = 'rect' | 'L' | 'U'

export function sketchShapePolygon(shape: SketchShapeKind, a: { x: number; y: number }, b: { x: number; y: number }, angleDeg = 0): Array<[number, number]> | null {
  const minX = Math.min(a.x, b.x), maxX = Math.max(a.x, b.x)
  const minY = Math.min(a.y, b.y), maxY = Math.max(a.y, b.y)
  if (!(maxX > minX && maxY > minY)) return null
  let ring: Array<[number, number]>
  const midX = (minX + maxX) / 2, midY = (minY + maxY) / 2
  if (shape === 'rect') ring = [[minX, minY], [maxX, minY], [maxX, maxY], [minX, maxY]]
  else if (shape === 'L') ring = [[minX, minY], [maxX, minY], [maxX, midY], [midX, midY], [midX, maxY], [minX, maxY]]
  else {
    const x1 = minX + (maxX - minX) * 0.25, x2 = minX + (maxX - minX) * 0.75
    ring = [[minX, minY], [x1, minY], [x1, midY], [x2, midY], [x2, minY], [maxX, minY], [maxX, maxY], [minX, maxY]]
  }
  if (!angleDeg) return ring
  // 绕包围盒中心旋转；角度在当前场景坐标系中应用，调用方负责按坐标系调整符号。
  const rad = angleDeg * Math.PI / 180, cos = Math.cos(rad), sin = Math.sin(rad)
  const cx = (minX + maxX) / 2, cy = (minY + maxY) / 2
  return ring.map(([x, y]): [number, number] => [cx + (x - cx) * cos - (y - cy) * sin, cy + (x - cx) * sin + (y - cy) * cos])
}

/** 绕多边形面积质心旋转（角度在当前坐标系内，度；Y 向下坐标系中正值在屏幕上为顺时针）。质心是旋转不动点，连续旋转不漂移。 */
export function rotateRing(ring: Array<[number, number]>, angleDeg: number): Array<[number, number]> | null {
  if (ring.length < 3 || !Number.isFinite(angleDeg) || !angleDeg) return null
  let twiceArea = 0, cx = 0, cy = 0
  for (let i = 0; i < ring.length; i++) {
    const [x0, y0] = ring[i], [x1, y1] = ring[(i + 1) % ring.length]
    const cross = x0 * y1 - x1 * y0
    twiceArea += cross
    cx += (x0 + x1) * cross
    cy += (y0 + y1) * cross
  }
  if (Math.abs(twiceArea) < 1e-9) return null
  cx /= 3 * twiceArea
  cy /= 3 * twiceArea
  const rad = angleDeg * Math.PI / 180, cos = Math.cos(rad), sin = Math.sin(rad)
  return ring.map(([x, y]): [number, number] => [cx + (x - cx) * cos - (y - cy) * sin, cy + (x - cx) * sin + (y - cy) * cos])
}
