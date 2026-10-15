export interface Project {
  name: string
  area: number
  headcount: number
  budget: number
  style: string
  description: string
}

export type ZoneType =
  | 'OPEN_OFFICE'
  | 'MEETING_ROOM'
  | 'EXECUTIVE_OFFICE'
  | 'RECEPTION'
  | 'COLLABORATION'
  | 'OTHER'

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
}

export interface NoteObject {
  id: string
  kind: 'note'
  x: number
  y: number
  text: string
}

export interface ArrowObject {
  id: string
  kind: 'arrow'
  x: number
  y: number
  x2: number
  y2: number
}

export interface ReferenceObject {
  id: string
  kind: 'reference'
  x: number
  y: number
  caption: string
  image?: string
}

export interface FurnitureObject {
  id: string
  kind: 'furniture'
  x: number
  y: number
  w: number
  h: number
  rotation: number
  productId: string
  zoneId: string
  label?: string
}

export type OfficeObject = ZoneObject | NoteObject | ArrowObject | ReferenceObject | FurnitureObject

export type ToneKey = 'blue' | 'green' | 'yellow' | 'purple' | 'pink'

export type CanvasMode = 'overview' | 'design'

export interface ProjectFile {
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
}

export interface Product {
  id: string
  name: string
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
  projectContext: {
    name: string
    headcount: number
    budgetMax: number
    stylePreference: string
    description: string
  }
  zones: ZoneBrief[]
  annotations: { id: string; zoneId: null; content: string }[]
  references: { id: string; caption: string; imageAssetId: string }[]
  sourceAssets: { fileName: string; fileType: string; source: string }[]
  pendingQuestions: string[]
}
