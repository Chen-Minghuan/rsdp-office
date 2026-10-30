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

export type SpatialObstacleKind = 'COLUMN' | 'SHAFT' | 'NO_PLACEMENT' | 'DOOR_CLEARANCE' | 'WINDOW' | 'OTHER'

/**
 * 正向家具布置区（GEOM-016）：独立于空间轮廓与障碍物（obstacles）的可选对象。
 * polygonMm 为世界毫米、Y 向上，与 SpatialObstacle.geometry.outer 同一约定；
 * 渲染/画布交互时按 zone.reviewUnit 取反 Y（同 obstacleRing）。
 * 同空间最多一个 isPrimary=true；没有启用区时维持旧的全房间可排布行为。
 */
export interface LayoutRegion {
  regionId: string
  spaceId: string
  name: string
  polygonMm: Array<[number, number]>
  /** 主布置区只用于优先布局/聚焦展示，不代表其他启用区被禁止。 */
  isPrimary: boolean
  /** 显隐只影响渲染，绝不改变几何计算与家具可排区。 */
  visible: boolean
  /** 启用才参与有效可排区计算；需有明确 UI 开关。 */
  enabled: boolean
  /** 每条边的距墙量测结果（只读展示；edgeIndex 对应 polygonMm 的边序号，由量测函数刷新，不用于几何内缩）。 */
  wallOffsetsMm?: Array<{ edgeIndex: number; distanceMm: number }>
}

export type SpatialFacilityKind = 'FIRE_FACILITY' | 'ELECTRICAL_BOX' | 'SKIRTING' | 'POWER_OUTLET' | 'DATA_OUTLET'

/**
 * 设施/点位标注（GEOM-018）：与障碍物分开建模——
 * 点位型（POWER_OUTLET/DATA_OUTLET，shape=point）、线型（SKIRTING，shape=polyline）、占用型（FIRE_FACILITY/ELECTRICAL_BOX，shape=polygon）。
 * 统一语义：标注与提示用途，不参与排版硬阻断、不扣净面积（excludesFloorArea 恒为 false 的语义等价物）；
 * 占用型的操作净空 clearanceMm 必须来自人工标注，缺省只提示；需要实际禁放时应另建 NO_PLACEMENT 障碍。
 * pointsMm 为世界毫米、Y 向上（同障碍物约定）：point=1 点（中心）；polyline=≥2 点；polygon=≥3 点单外环。
 */
export interface SpatialFacility {
  facilityId: string
  kind: SpatialFacilityKind
  spaceId: string
  name?: string
  shape: 'point' | 'polyline' | 'polygon'
  pointsMm: Array<[number, number]>
  /** 点位/占用型的占地尺寸（渲染与净空提示用；不用于扣减面积）。 */
  sizeMm?: { widthMm: number; depthMm: number }
  rotationDeg?: number
  /** 人工标注的操作净空毫米（仅占用型）；缺省仅提示不阻断，不得虚构消防规范数值。 */
  clearanceMm?: number
  /** 显隐只影响渲染。 */
  visible: boolean
  source?: 'MANUAL' | 'CAD_GEOMETRY' | 'AI_VISION'
  note?: string
}

export interface SpatialObstacle {
  obstacleId: string
  kind: SpatialObstacleKind
  spaceId: string | null
  /** 可选用户命名（如迁移自旧排版预留区的名称）；缺省按 kind 显示。 */
  label?: string
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
