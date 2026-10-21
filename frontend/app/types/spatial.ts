export type CoordinateSystem = 'SOURCE_PAGE' | 'WORLD_MM'

export interface Point {
  x: number
  y: number
}

export interface RingPoint extends Array<number> {}

export interface SpatialGeometry {
  type: 'POLYGON'
  coordinateSystem: CoordinateSystem
  outer: Array<[number, number]>
  outerVertexIds: string[]
  holes: Array<Array<[number, number]>>
  holeVertexIds: string[][]
}

export interface RectangleFrame {
  angleDeg: number
  cornerVertexIds: {
    u0v0: string
    u1v0: string
    u1v1: string
    u0v1: string
  }
}

export interface SpatialBounds {
  minX: number
  minY: number
  maxX: number
  maxY: number
}

export interface Derived {
  boundaryAreaM2: number | null
  areaM2: number | null
  rectangleDimensions: { widthMm: number; depthMm: number } | null
  boundsMm: SpatialBounds | null
}

export interface SpaceConfirmation {
  status: 'UNCONFIRMED' | 'CONFIRMED' | 'EXCLUDED'
  confirmationId: string | null
  confirmedRevision: number | null
  confirmedGeometryRevision: number | null
  calibrationRevision: number | null
}

export interface SpatialSpace {
  spaceId: string
  kind: 'ROOM' | 'FUNCTION_ZONE'
  parentSpaceId: string | null
  name: string
  category: string
  includedInDesign: boolean
  exclusionReason: string | null
  boundaryBasis: 'INNER_WALL_FACE' | 'FUNCTIONAL_DIVISION' | 'UNRESOLVED'
  designGeometry: SpatialGeometry
  rectangleFrame: RectangleFrame | null
  origin: 'VISION' | 'CAD' | 'MANUAL' | 'AGENT'
  evidenceKind: 'REAL_SERVICE' | 'ESTIMATED' | 'DEMO' | 'MANUAL'
  sourceRef: {
    assetId: string
    pageId: string
    runId: string
    sourceSceneId: string
    sourceRoomId: string
  } | null
  confidence: number | null
  geometryRevision: number
  requirementRevision: number
  metadataRevision: number
  derived: Derived
  confirmation: SpaceConfirmation
}

export interface CalibrationSnapshot {
  revision: number
  status: 'UNKNOWN' | 'ESTIMATED' | 'CONFIRMED'
  sourceToWorld: number[] | null
}

export interface Underlay {
  underlayId: string
  sourcePageId: string
  assetId: string
  widthPx: number
  heightPx: number
  imagePixelToWorld: number[] | null
  transformRevision: number
}

export interface SpatialSceneSnapshot {
  schemaVersion: string
  sceneId: string
  projectId: string
  revision: number
  scopeRevision: number
  geometryPolicyVersion: string
  sourcePageId: string
  sourceSceneId: string | null
  failedRunId: string | null
  workingCoordinateSystem: CoordinateSystem
  calibration: CalibrationSnapshot
  underlays: Underlay[]
  spaces: SpatialSpace[]
  walls: any[]
  openings: any[]
  obstacles: any[]
  annotations: any[]
  boundaryRelations: any[]
  issueDispositions: any[]
  layerAvailability: {
    walls: 'AVAILABLE' | 'PARTIAL' | 'UNKNOWN' | 'NOT_APPLICABLE'
    openings: 'AVAILABLE' | 'PARTIAL' | 'UNKNOWN' | 'NOT_APPLICABLE'
    obstacles: 'AVAILABLE' | 'PARTIAL' | 'UNKNOWN' | 'NOT_APPLICABLE'
  }
  qualityIssues: any[]
  tombstones: any[]
  confirmedSceneId: string | null
}

export type SpatialObstacleKind = 'COLUMN' | 'SHAFT' | 'NO_PLACEMENT' | 'DOOR_CLEARANCE' | 'OTHER'

export interface SpatialObstacle {
  obstacleId: string
  kind: SpatialObstacleKind
  spaceId: string | null
  geometry: SpatialGeometry
  excludesFloorArea: boolean
  source?: 'MANUAL' | 'CAD_GEOMETRY' | 'AI_VISION'
}

export interface DraftRequest {
  schemaVersion: string
  baseRevision: number
  clientChangeId: string
  workingCoordinateSystem: CoordinateSystem
  calibrationRevision: number
  spaces: Partial<SpatialSpace>[]
  walls: any[]
  openings: any[]
  obstacles: any[]
  annotations: any[]
  boundaryRelations: any[]
  issueDispositions: any[]
  deletions: Array<{ layer: string; objectId: string; reason: string }>
}

export interface MutationData {
  clientChangeId: string
  sceneId: string
  previousRevision: number
  revision: number
  affectedSpaceIds: string[]
  invalidatedSpaceIds: string[]
  confirmationIds: string[]
  confirmedSceneId: string | null
  scene: SpatialSceneSnapshot
}

export interface ApiResult<T> {
  code: number
  message: string
  data: T
}
