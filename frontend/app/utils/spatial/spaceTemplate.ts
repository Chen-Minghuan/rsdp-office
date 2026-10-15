import type { SpaceTemplate } from '~/types/spaceTemplate'
import type { SpatialSceneSnapshot } from '~/types/spatial'
import type { ZoneObject } from '~/types/office'
import type { FloorPlanAnalysisResponse } from '~/types/floorPlan'
import { reviewScene, zoneMeasurements, zoneReviewMessage } from './review'
import { polygonBounds } from './geometry'
import { mapRoomType } from '~/utils/floorPlan'

export function templateSignature(scene: SpatialSceneSnapshot) {
  return JSON.stringify(scene.spaces.map(space => ({
    id: space.spaceId, name: space.name, category: space.category,
    geometry: { outer: space.designGeometry.outer, holes: space.designGeometry.holes || [] },
  })).sort((a, b) => a.id.localeCompare(b.id)))
}
export function captureSpaceTemplate(zones: ZoneObject[], plan: FloorPlanAnalysisResponse | null, revision: number, name: string): SpaceTemplate {
  const scene = JSON.parse(JSON.stringify(reviewScene(zones, plan))) as SpatialSceneSnapshot
  scene.revision = revision
  scene.qualityIssues = zones.flatMap(zone => {
    const message = zoneReviewMessage(zone, zones)
    return message ? [{ issueId: 'REVIEW-' + zone.id, code: 'REVIEW_SUGGESTED', severity: 'WARN', spaceIds: [zone.id], objectIds: [], message, blocksConfirmation: false }] : []
  })
  const templateId = 'LSTPL-' + crypto.randomUUID()
  scene.sceneId = templateId
  return {
    templateId, projectId: scene.projectId, name,
    version: 1, contentHash: '', sourceCanvasRevision: revision, sourceAnalysisId: plan?.analysisId || null, sourceAnalysisRevision: plan?.analysisId ? plan.analysisRevision || 1 : null,
    spaceCount: zones.length, areaM2: zones.reduce((sum, zone) => sum + (zoneMeasurements(zone).area || 0), 0),
    evidenceKind: zones.some(zone => zone.evidenceKind === 'DEMO') ? 'DEMO' : zones.some(zone => !zone.evidenceKind || zone.evidenceKind === 'ESTIMATED') ? 'ESTIMATED' : zones.some(zone => zone.evidenceKind === 'REAL_SERVICE') ? 'REAL_SERVICE' : 'MANUAL',
    createdAt: new Date().toISOString(), scene, storage: 'LOCAL',
  }
}
export function templateMatches(template: SpaceTemplate, zones: ZoneObject[], plan: FloorPlanAnalysisResponse | null) {
  const sameSource = zones.every(zone => zone.spaceTemplateId === template.templateId) || (template.sourceAnalysisId === (plan?.analysisId || null) && (template.sourceAnalysisRevision || 1) === (plan?.analysisRevision || 1))
  return sameSource && zones.every(zone => zone.reviewUnit === 'mm') && templateSignature(template.scene) === templateSignature(reviewScene(zones, plan))
}
export function templateLayoutProblem(template: SpaceTemplate): string | null {
  // The current Office Canvas layout does not support these layers. Preserve them in the template instead of dropping them.
  if (template.scene.boundaryRelations?.length || template.scene.spaces.some(space => space.parentSpaceId || space.kind !== 'ROOM' || space.includedInDesign === false)) return '此模板包含嵌套空间或边界关系，当前排版尚不支持；模板结构已保留，可导出使用'
  if (template.scene.spaces.some(space => space.designGeometry.holes?.length) || template.scene.obstacles?.length || template.scene.walls?.length || template.scene.openings?.length) return '此模板包含孔洞或建筑约束层，当前排版尚不支持；模板结构已保留，可导出使用'
  return null
}
export function zonesFromTemplate(template: SpaceTemplate): ZoneObject[] {
  return template.scene.spaces.map((space, index) => {
    const polygon = space.designGeometry.outer.map(([x, y]): [number, number] => [x, -y])
    const bounds = polygonBounds(space.designGeometry)!
    return {
      id: space.spaceId, kind: 'zone', x: bounds.minX, y: -bounds.maxY, w: bounds.maxX - bounds.minX, h: bounds.maxY - bounds.minY,
      name: space.name, zoneType: mapRoomType(space.category), seats: 0, note: '', tone: (['blue', 'green', 'pink', 'yellow', 'purple'] as const)[index % 5] as ZoneObject['tone'],
      polygon, sourcePolygon: polygon.map(([x, y]) => [x, y]), reviewUnit: 'mm', mmPerSourceUnit: 1, pixelToMeter: 0.001,
      actualWidthMm: space.derived.rectangleDimensions?.widthMm, actualDepthMm: space.derived.rectangleDimensions?.depthMm,
      source: 'recognition', geometrySource: space.origin === 'VISION' ? 'ai_vision' : space.origin === 'CAD' ? 'cad_geometry' : 'manual_geometry',
      evidenceKind: space.evidenceKind, sourceAnalysisId: template.templateId, sourceAnalysisRevision: 1, sourceYAxis: 'down',
      spaceTemplateId: template.templateId, templateSpaceId: space.spaceId, confirmed: false,
    }
  })
}
export function templatePlan(template: SpaceTemplate): FloorPlanAnalysisResponse {
  // This is explicitly a template underlay, not the original drawing or new recognition evidence.
  const points = template.scene.spaces.flatMap(space => space.designGeometry.outer.map(([x, y]) => [x, -y]))
  const bounds = { minX: Math.min(...points.map(p => p[0])), minY: Math.min(...points.map(p => p[1])), maxX: Math.max(...points.map(p => p[0])), maxY: Math.max(...points.map(p => p[1])) }
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="' + [bounds.minX, bounds.minY, bounds.maxX - bounds.minX, bounds.maxY - bounds.minY].join(' ') + '">' +
    template.scene.spaces.map(space => '<polygon points="' + space.designGeometry.outer.map(([x, y]) => [x, -y].join(',')).join(' ') + '" fill="#f2f4ef" stroke="#73877b" stroke-width="60"/>').join('') + '</svg>'
  return {
    analysisId: template.templateId, sourceName: template.name + ' · 空间模板', status: 'draft', inputMode: 'manual',
    evidenceKind: template.evidenceKind, unitResolution: { canonicalUnit: 'mm', confirmed: true }, previewBounds: bounds,
    previewUrl: 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg),
    rooms: template.scene.spaces.map(space => ({ roomId: space.spaceId, label: space.name, roomType: space.category, polygon: space.designGeometry.outer.map(([x, y]) => [x, -y]) })),
  }
}
