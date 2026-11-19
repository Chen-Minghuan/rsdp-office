import type { CustomOfficeModule } from './officeModule'
import type { OfficeLayoutMode, OfficeLayoutSpacing } from '~/utils/officeAutoLayout'
import type { OfficeLayoutSettingsMeta } from './officeArrangement'

export type ZoneType =
  | 'OPEN_OFFICE'
  | 'MEETING_ROOM'
  | 'EXECUTIVE_OFFICE'
  | 'RECEPTION'
  | 'COLLABORATION'
  | 'TRAINING_ROOM'
  | 'PHONE_BOOTH'
  | 'PANTRY'
  | 'PRINT_AREA'
  | 'STORAGE'
  | 'SERVER_ROOM'
  | 'CORRIDOR'
  | 'OTHER'

/** 设计复核确认（SPACE_REVIEW）；凭据由服务端签发，普通保存不能伪造。 */
export interface SpaceReviewConfirmation {
  status: 'UNCONFIRMED' | 'CONFIRMED' | 'STALE'
  confirmedCanvasRevision?: number
  confirmedByUserId?: string
  confirmedAt?: string
}

export interface ZoneObject {
  id: string
  kind: 'zone'
  x: number
  y: number
  w: number
  h: number
  name: string
  zoneType: ZoneType
  seats: number
  note: string
  tone: ToneKey
  calibratedWidth?: number
  calibratedHeight?: number
  pixelToMeter?: number
  /** 识别来源多边形（毫米坐标），存在时优先渲染真实轮廓。 */
  polygon?: Array<[number, number]>
  /** 来源：recognition / user_annotation。 */
  source?: 'recognition' | 'user_annotation'
  /** 几何来源：cad_geometry / ai_vision / manual_geometry。 */
  geometrySource?: 'cad_geometry' | 'ai_vision' | 'manual_geometry'
  /** 设计复核确认状态；与排版确认、客户方案确认相互独立。 */
  reviewConfirmation?: SpaceReviewConfirmation
  /** 空间是否已确认（纳入方案）。 */
  confirmed?: boolean
  /** 确认时间戳。 */
  confirmedAt?: number
  /** 实际宽度（毫米），用于尺寸双向编辑。 */
  actualWidthMm?: number
  /** 实际进深（毫米），用于尺寸双向编辑。 */
  actualDepthMm?: number
  /** Geometry basis of polygon/x/y/w/h. Old px drafts stay uncalibrated. */
  reviewUnit?: 'mm' | 'source'
  sourceAnalysisId?: string
  sourceAnalysisRevision?: number
  sourceImageSha256?: string
  contourCorrection?: { sourceAnalysisId: string; sourceAnalysisRevision: number; canvasRevision: number }
  spaceTemplateId?: string
  templateSpaceId?: string
  /** CAD source bounds are Y-up; stored design rings use Y-down. */
  sourceYAxis?: 'up' | 'down'
  sourcePolygon?: Array<[number, number]>
  /** Original page units to canonical millimetres; shared by the page. */
  mmPerSourceUnit?: number
  evidenceKind?: 'DEMO' | 'REAL_SERVICE' | 'MANUAL' | 'ESTIMATED'
  obstacles?: import('./spatial').SpatialObstacle[]
  /** 正向家具布置区（GEOM-016）；缺省=无布置区，维持全房间可排布。 */
  layoutRegions?: import('./spatial').LayoutRegion[]
  /** 设施/点位标注（GEOM-018）；提示用途，不参与硬校验与净面积。 */
  facilities?: import('./spatial').SpatialFacility[]
  layoutPreferences?: import('./layoutPlanning').LayoutPreferences
}

export interface NoteObject {
  /** 单空间设计中的附属内容；无归属的内容仅在总览展示。 */
  zoneId?: string
  id: string
  kind: 'note'
  x: number
  y: number
  text: string
}

export interface ArrowObject {
  /** 单空间设计中的附属内容；无归属的内容仅在总览展示。 */
  zoneId?: string
  id: string
  kind: 'arrow'
  x: number
  y: number
  x2: number
  y2: number
}

export interface ReferenceObject {
  /** 单空间设计中的附属内容；无归属的内容仅在总览展示。 */
  zoneId?: string
  id: string
  kind: 'reference'
  x: number
  y: number
  caption: string
  image?: string
}

export interface FurnitureObject {
  /** Only an explicitly generated group may be replaced by replanning. */
  layoutGroupId?: string
  layoutClusterId?: string
  layoutRole?: 'primary' | 'seat'
  /** 共享家具模板目录的图例；与实际商品引用分开。 */
  furnitureTemplateId?: string
  /** Immutable definition for a custom instance, independent of local catalog edits. */
  moduleSnapshot?: CustomOfficeModule
  layoutSettings?: { mode: OfficeLayoutMode; spacing: OfficeLayoutSpacing } & OfficeLayoutSettingsMeta
  mixedBundleId?: string
  layoutReference?: { analysisId: string; revision: number; evidence: 'ESTIMATED' | 'MANUAL'; adjusted?: boolean }
  wingWidthMm?: number
  heightMm?: number
  spaceTemplateId?: string
  templateSpaceId?: string
  id: string
  kind: 'furniture'
  x: number
  y: number
  w: number
  h: number
  rotation: number
  /** Reflect the drawn glyph within the same physical footprint. */
  mirrorX?: boolean
  productId: string
  zoneId: string
  label?: string
  /** 该家具是否属于已确认的排版。 */
  productSelection?: import('./moduleRecommendation').ModuleProductCandidate & { cartLineId: string; sourceCanvasRevision: number }
  layoutConfirmed?: boolean
}

export interface ProductComboItem {
  productId: string
  qty: number
  optional?: boolean
}

export interface ProductCombo {
  id: string
  name: string
  desc: string
  style?: string
  kind: 'workstation' | 'meeting' | 'reception' | 'executive' | 'collaboration'
  uses: ZoneType[]
  items: ProductComboItem[]
  price: number
}

export type OfficeObject = ZoneObject | NoteObject | ArrowObject | ReferenceObject | FurnitureObject

export type ToneKey = 'blue' | 'green' | 'yellow' | 'purple' | 'pink'

export type CanvasMode = 'overview' | 'design'

export interface ProjectFile {
  /** Stable identity for this upload; replacing a same-named file creates a new identity. */
  drawingId?: string
  analysisId?: string
  appliedAnalysisId?: string
  analysisPageNumber?: number
  name: string
  size: number
  type: string
  mock: boolean
}

export interface AgentMessage {
  role: 'bot' | 'user'
  content: string
}

export interface CartItem {
  id: string
  productId: string
  zoneId: string | null
  zoneName: string
  qty: number
  /** 若来自组合推荐，记录组合 ID。 */
  comboId?: string
}

export interface Product {
  rspuId?: string
  variantId?: string
  widthMm?: number
  depthMm?: number
  heightMm?: number
  id: string
  name: string
  /** RSPU 主色原文；空值表示未分类。 */
  colorPrimaryName?: string
  desc: string
  variant: string
  price: number
  kind: 'workstation' | 'chair' | 'meeting' | 'sofa' | 'desk' | 'cabinet' | 'other'
  uses: ZoneType[]
  widthCm: number
  depthCm: number
  imageUrl?: string
}

export interface ZoneBrief {
  zoneId: string
  name: string
  type: ZoneType
  targetSeats: number
  geometry: {
    kind: 'canvas_rect'
    x: number
    y: number
    width: number
    height: number
  }
  geometryVerified: false
  factSource: 'user_annotation'
  note: string
}

export interface DesignBrief {
  projectId: string
  canvasRevisionId: string
  sceneRevisionId: null
  zones: ZoneBrief[]
  annotations: { id: string; zoneId: null; content: string }[]
  references: { id: string; caption: string; imageAssetId: string }[]
  sourceAssets: { fileName: string; fileType: string; source: string }[]
  pendingQuestions: string[]
}

/** 项目报价口径：系数或毛利率（毛利率以小数传输，0.35 表示 35%）。 */
export type QuotationPricingMode = 'COEFFICIENT' | 'MARGIN'

export interface QuotationLine {
  productId: string
  rspuId: string | null
  productName: string
  imageUrl: string | null
  variant: string | null
  qty: number
  factoryPrice: number | null
  unitPrice: number | null
  subtotal: number | null
  /** 无法计价的原因；为空表示可计价。 */
  issue: string | null
}

export interface QuotationSpace {
  zoneId: string
  zoneName: string
  lines: QuotationLine[]
  factoryTotal: number
  dealTotal: number
}

export interface QuotationFloor {
  floorId: string
  floorName: string
  spaces: QuotationSpace[]
  factoryTotal: number
  dealTotal: number
}

export interface QuotationTotals {
  lineCount: number
  missingFactoryPriceLines: number
  factoryTotal: number
  dealTotal: number
}

export interface ProjectQuotationPreview {
  projectId: string
  projectName: string
  pricingMode: QuotationPricingMode
  pricingValue: number
  generatedAt: string
  floors: QuotationFloor[]
  totals: QuotationTotals
}

export interface QuotationRecordLine {
  floorId: string
  floorName: string
  zoneId: string | null
  zoneName: string
  productId: string
  rspuId: string | null
  productName: string
  variant: string | null
  imageUrl: string | null
  qty: number
  factoryPrice: number | null
  unitPrice: number | null
  subtotal: number | null
  issue: string | null
}

/** 报价附加费用：名称与金额随确认快照保存。 */
export interface QuotationFee {
  name: string
  amount: number | null
}

/** 已确认报价单：确认时的价格与名称快照，只读。 */
export interface ProjectQuotationRecord {
  quotationId: string
  projectId: string
  projectName: string
  revision: number
  status: 'CONFIRMED' | 'SUPERSEDED' | 'STALE'
  pricingMode: QuotationPricingMode
  pricingValue: number
  goodsTotal: number
  serviceFee: number
  logisticsFee: number
  fees: QuotationFee[]
  totalAmount: number
  lineCount: number
  missingPriceLines: number
  createdBy: string | null
  createdAt: string
  lines: QuotationRecordLine[]
}

export interface ConfirmQuotationLine {
  floorId: string
  zoneId: string | null
  zoneName: string
  productId: string
  qty: number
  unitPrice: number | null
}

export interface ConfirmQuotationRequest {
  pricingMode: QuotationPricingMode
  pricingValue: number
  fees: QuotationFee[]
  lines: ConfirmQuotationLine[]
}
