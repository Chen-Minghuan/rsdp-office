import type { FloorPlanAnalysisResponse } from '~/types/floorPlan'
import type { ZoneObject, ZoneType } from '~/types/office'

export function officeReviewExample(): { plan: FloorPlanAnalysisResponse; zones: ZoneObject[] } {
  const definitions: Array<[string, string, ZoneType, number, number, number, number]> = [
    ['ROOM-001', '总经办一', 'EXECUTIVE_OFFICE', 0, 0, 6000, 7200],
    ['ROOM-002', '总经办二', 'EXECUTIVE_OFFICE', 6000, 0, 5200, 7200],
    ['ROOM-003', '总经办三', 'EXECUTIVE_OFFICE', 11200, 0, 5200, 7200],
    ['ROOM-004', '接待区', 'RECEPTION', 16400, 0, 6400, 4400],
    ['ROOM-005', '储物间', 'OTHER', 22800, 0, 3600, 4400],
    ['ROOM-006', '茶水间', 'OTHER', 26400, 0, 4000, 4400],
    ['ROOM-007', '共享过道', 'OTHER', 0, 4400, 30400, 5200],
    ['ROOM-008', '开放办公区', 'OPEN_OFFICE', 0, 9600, 14800, 8000],
    ['ROOM-009', '会议室一', 'MEETING_ROOM', 14800, 9600, 7600, 8000],
    ['ROOM-010', '会议室二', 'MEETING_ROOM', 22400, 9600, 8000, 5200],
    ['ROOM-011', '打印区', 'OTHER', 22400, 14800, 4000, 2800],
    ['ROOM-012', '机房', 'OTHER', 26400, 14800, 4000, 2800],
  ]
  const zones: ZoneObject[] = definitions.map(([id, name, zoneType, x, y, w, h]) => ({
    id, name, zoneType, x, y, w, h, kind: 'zone', seats: zoneType === 'OPEN_OFFICE' ? 36 : 0, note: '', tone: 'green',
    polygon: id === 'ROOM-007' ? [[0, 7200], [16400, 7200], [16400, 4400], [30400, 4400], [30400, 9600], [0, 9600]] : [[x, y], [x + w, y], [x + w, y + h], [x, y + h]],
    reviewUnit: 'mm', mmPerSourceUnit: 1, pixelToMeter: 0.001,
    actualWidthMm: w, actualDepthMm: h, calibratedWidth: w / 1000, calibratedHeight: h / 1000,
    source: 'user_annotation', geometrySource: 'manual_geometry', evidenceKind: 'DEMO', sourceAnalysisId: 'OFFICE-REVIEW-EXAMPLE',
    confirmed: !['ROOM-004', 'ROOM-010'].includes(id),
  }))
  const plan: FloorPlanAnalysisResponse = {
    analysisId: 'OFFICE-REVIEW-EXAMPLE', sourceName: '办公平面图 · 演示.svg', status: 'awaiting_confirm',
    previewUrl: '/examples/office-review.svg', previewBounds: { minX: -1000, minY: -1000, maxX: 31400, maxY: 18600 },
    drawingBounds: { minX: 0, minY: 0, maxX: 30400, maxY: 17600 }, geometrySource: 'manual_geometry',
    unitResolution: { canonicalUnit: 'mm', confirmed: true, evidenceKind: 'DEMO' },
    rooms: zones.map(zone => ({ roomId: zone.id, roomType: zone.zoneType, label: zone.name, polygon: zone.polygon, widthMm: zone.actualWidthMm, depthMm: zone.actualDepthMm, geometrySource: 'manual_geometry' })),
  }
  zones.forEach(zone => { zone.sourcePolygon = zone.polygon?.map(([x, y]) => [x, y]) })
  const listOrder = [0, 1, 2, 3, 7, 9, 4, 5, 6, 8, 10, 11]
  return { plan, zones: listOrder.map(index => zones[index]) }
}
