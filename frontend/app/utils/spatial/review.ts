import type { ZoneObject } from '~/types/office'
import type { FloorPlanAnalysisResponse, FloorPlanDrawingBounds } from '~/types/floorPlan'
import type { SpatialGeometry, SpatialSceneSnapshot } from '~/types/spatial'
import { inferRectangleFrame, polygonArea, polygonBounds } from '~/utils/spatial/geometry'

export function zoneRing(zone: ZoneObject): Array<[number, number]> {
  return zone.polygon?.length ? zone.polygon : []
}

export function geometryForZone(zone: ZoneObject): SpatialGeometry {
  return {
    type: 'POLYGON',
    coordinateSystem: zone.reviewUnit === 'mm' ? 'WORLD_MM' : 'SOURCE_PAGE',
    outer: zoneRing(zone).map(([x, y]) => [x, zone.reviewUnit === 'mm' ? -y : y]),
    outerVertexIds: zoneRing(zone).map((_, index) => zone.id + '-V-' + index),
    holes: [], holeVertexIds: [],
  }
}

export function zoneMeasurements(zone: ZoneObject) {
  const geometry = geometryForZone(zone)
  const bounds = polygonBounds(geometry)
  const frame = inferRectangleFrame(geometry)
  const point = (id: string) => geometry.outer[geometry.outerVertexIds.indexOf(id)]
  const distance = (a: string, b: string) => {
    const p = point(a), q = point(b)
    return Math.hypot(q[0] - p[0], q[1] - p[1])
  }
  return {
    bounds, frame,
    width: frame ? distance(frame.cornerVertexIds.u0v0, frame.cornerVertexIds.u1v0) : bounds ? bounds.maxX - bounds.minX : null,
    depth: frame ? distance(frame.cornerVertexIds.u0v0, frame.cornerVertexIds.u0v1) : bounds ? bounds.maxY - bounds.minY : null,
    area: zone.reviewUnit === 'mm' ? polygonArea(geometry) / 1_000_000 : null,
  }
}

export function validBounds(bounds?: FloorPlanDrawingBounds | null): bounds is FloorPlanDrawingBounds {
  return !!bounds && Object.values(bounds).every(Number.isFinite) && bounds.maxX > bounds.minX && bounds.maxY > bounds.minY
}

function cross(a: number[], b: number[], c: number[]) {
  return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
}

export function ringProblem(ring: Array<[number, number]>): string | null {
  if (ring.length < 3) return '缺少可核对轮廓，请重新识别或人工补充'
  if (ring.some(point => point.length !== 2 || point.some(value => !Number.isFinite(value)))) return '轮廓包含无效坐标'
  for (let i = 0; i < ring.length; i++) {
    const a = ring[i], b = ring[(i + 1) % ring.length]
    if (Math.hypot(a[0] - b[0], a[1] - b[1]) < 0.01) return '轮廓存在重复顶点或退化边'
    for (let j = i + 2; j < ring.length; j++) {
      if (i === 0 && j === ring.length - 1) continue
      const c = ring[j], d = ring[(j + 1) % ring.length]
      const on = (p: number[], q: number[], r: number[]) => Math.abs(cross(p, q, r)) < 1e-7 && r[0] >= Math.min(p[0], q[0]) - 1e-7 && r[0] <= Math.max(p[0], q[0]) + 1e-7 && r[1] >= Math.min(p[1], q[1]) - 1e-7 && r[1] <= Math.max(p[1], q[1]) + 1e-7
      if ((cross(a, b, c) * cross(a, b, d) < 0 && cross(c, d, a) * cross(c, d, b) < 0) || on(a, b, c) || on(a, b, d) || on(c, d, a) || on(c, d, b)) return '轮廓存在自交，请修正顶点'
    }
  }
  const area = Math.abs(ring.reduce((sum, a, i) => { const b = ring[(i + 1) % ring.length]; return sum + a[0] * b[1] - b[0] * a[1] }, 0)) / 2
  return area > 1 ? null : '轮廓面积过小'
}

/** Scan horizontal bands bounded by vertices and proper edge crossings. Shared edges have zero overlap. */
export function ringsOverlap(a: Array<[number, number]>, b: Array<[number, number]>): boolean {
  if (a.length < 3 || b.length < 3) return false
  const heights = [...a, ...b].map(p => p[1])
  for (let i = 0; i < a.length; i++) for (let j = 0; j < b.length; j++) {
    const p = a[i], q = a[(i + 1) % a.length], r = b[j], s = b[(j + 1) % b.length]
    const dx = q[0] - p[0], dy = q[1] - p[1], ex = s[0] - r[0], ey = s[1] - r[1]
    const det = dx * ey - dy * ex
    if (Math.abs(det) < 1e-9) continue
    const t = ((r[0] - p[0]) * ey - (r[1] - p[1]) * ex) / det
    const u = ((r[0] - p[0]) * dy - (r[1] - p[1]) * dx) / det
    if (t > 0 && t < 1 && u > 0 && u < 1) heights.push(p[1] + t * dy)
  }
  heights.sort((x, y) => x - y)
  const intervals = (ring: Array<[number, number]>, y: number) => {
    const xs: number[] = []
    ring.forEach((p, i) => {
      const q = ring[(i + 1) % ring.length]
      if ((p[1] > y) !== (q[1] > y)) xs.push(p[0] + (y - p[1]) * (q[0] - p[0]) / (q[1] - p[1]))
    })
    return xs.sort((x, y) => x - y)
  }
  for (let i = 0; i < heights.length - 1; i++) {
    if (heights[i + 1] - heights[i] < 1e-6) continue
    const y = (heights[i] + heights[i + 1]) / 2
    const xs = intervals(a, y), ys = intervals(b, y)
    for (let j = 0; j < xs.length - 1; j += 2) for (let k = 0; k < ys.length - 1; k += 2) {
      if (Math.min(xs[j + 1], ys[k + 1]) - Math.max(xs[j], ys[k]) > 0.01) return true
    }
  }
  return false
}

/** Only conditions that prevent reliable physical layout calculations. */
export function zoneLayoutProblem(zone: ZoneObject): string | null {
  const problem = ringProblem(zoneRing(zone))
  if (problem) return problem
  if (zone.reviewUnit !== 'mm') return '图纸比例待校准'
  return null
}

export function zoneProblem(zone: ZoneObject, zones: ZoneObject[]): string | null {
  const problem = zoneLayoutProblem(zone)
  if (problem) return problem
  const other = zones.find(candidate => candidate.id !== zone.id && ringsOverlap(zoneRing(zone), zoneRing(candidate)))
  return other ? '与「' + other.name + '」边界重叠，请复核' : null
}

export function zoneReviewMessage(zone: ZoneObject, zones: ZoneObject[]): string | null {
  return zoneProblem(zone, zones) || (zone.contourCorrection ? '已沿用同一底图的人工修正轮廓，可对照原图继续调整' : null) || (zone.geometrySource === 'ai_vision' && !zone.confirmed
    ? '图像识别轮廓建议对照原图复核；发现不符可直接修改，不影响继续排版' : null)
}

export function reviewScene(zones: ZoneObject[], plan: FloorPlanAnalysisResponse | null, pixelBounds?: FloorPlanDrawingBounds | null): SpatialSceneSnapshot {
  const calibrated = zones.length > 0 && zones.every(zone => zone.reviewUnit === 'mm')
  const rawBounds = validBounds(plan?.previewBounds) ? plan.previewBounds : validBounds(plan?.drawingBounds) ? plan.drawingBounds : pixelBounds
  const bounds = rawBounds && zones[0]?.sourceYAxis === 'up' ? { ...rawBounds, minY: -rawBounds.maxY, maxY: -rawBounds.minY } : rawBounds
  const scale = calibrated ? zones[0]?.mmPerSourceUnit ?? 1 : 1
  const evidenceKind = zones[0]?.evidenceKind ?? 'ESTIMATED'
  return {
    schemaVersion: '1.2', sceneId: plan?.analysisId ?? 'LOCAL-DRAFT', projectId: useWorkspaceStore().currentFloorId,
    revision: 1, scopeRevision: 1, geometryPolicyVersion: 'GEOM-P1-1', sourcePageId: plan?.imageId ?? 'PAGE',
    sourceSceneId: plan?.analysisId ?? null, failedRunId: null,
    workingCoordinateSystem: calibrated ? 'WORLD_MM' : 'SOURCE_PAGE',
    calibration: { revision: 1, status: calibrated ? 'CONFIRMED' : 'UNKNOWN', sourceToWorld: calibrated ? [scale, 0, 0, -scale, 0, 0] : null },
    underlays: validBounds(bounds) ? [{
      underlayId: 'UNDERLAY', sourcePageId: plan?.imageId ?? 'PAGE', assetId: '',
      widthPx: bounds.maxX - bounds.minX, heightPx: bounds.maxY - bounds.minY,
      imagePixelToWorld: calibrated ? [scale, 0, 0, -scale, bounds.minX * scale, -bounds.minY * scale] : [1, 0, 0, 1, bounds.minX, bounds.minY],
      transformRevision: 1,
    }] : [],
    spaces: zones.map(zone => {
      const geometry = geometryForZone(zone)
      const metrics = zoneMeasurements(zone)
      return {
        spaceId: zone.id, kind: 'ROOM', parentSpaceId: null, name: zone.name, category: zone.zoneType,
        includedInDesign: true, exclusionReason: null, boundaryBasis: 'INNER_WALL_FACE',
        designGeometry: geometry, rectangleFrame: metrics.frame,
        origin: zone.geometrySource === 'cad_geometry' ? 'CAD' : zone.geometrySource === 'ai_vision' ? 'VISION' : 'MANUAL',
        evidenceKind: zone.evidenceKind ?? 'ESTIMATED', sourceRef: null, confidence: null,
        geometryRevision: 1, requirementRevision: 1, metadataRevision: 1,
        derived: {
          areaM2: metrics.area, boundaryAreaM2: metrics.area, boundsMm: calibrated ? metrics.bounds : null,
          rectangleDimensions: calibrated && metrics.frame ? { widthMm: metrics.width!, depthMm: metrics.depth! } : null,
        },
        confirmation: { status: zone.confirmed && !zoneProblem(zone, zones) ? 'CONFIRMED' : 'UNCONFIRMED', confirmationId: null, confirmedRevision: null, confirmedGeometryRevision: null, calibrationRevision: null },
      }
    }),
    walls: [], openings: [], obstacles: [], annotations: [], boundaryRelations: [], issueDispositions: [],
    layerAvailability: { walls: 'UNKNOWN', openings: 'UNKNOWN', obstacles: 'UNKNOWN' },
    qualityIssues: [], tombstones: [], confirmedSceneId: null,
  }
}

export function reviewSceneBounds(scene: SpatialSceneSnapshot) {
  const points = scene.spaces.flatMap(space => space.designGeometry.outer)
  for (const underlay of scene.underlays) {
    const matrix = underlay.imagePixelToWorld ?? [1, 0, 0, 1, 0, 0]
    for (const [x, y] of [[0, 0], [underlay.widthPx, 0], [underlay.widthPx, underlay.heightPx], [0, underlay.heightPx]]) {
      points.push([matrix[0] * x + matrix[2] * y + matrix[4], matrix[1] * x + matrix[3] * y + matrix[5]])
    }
  }
  if (!points.length) return { minX: 0, minY: 0, maxX: 1000, maxY: 1000 }
  return { minX: Math.min(...points.map(point => point[0])), maxX: Math.max(...points.map(point => point[0])), minY: Math.min(...points.map(point => point[1])), maxY: Math.max(...points.map(point => point[1])) }
}
