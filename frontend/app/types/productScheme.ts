import type { OfficeObject, CartItem } from './office'

export type SchemeSlot = 'A' | 'B'
export interface RoomSchemeState {
  activeSlot: SchemeSlot
  alternativeSaved: boolean
  /** 已确认使用的方案槽；null 表示尚未确认。确认期间切换被后端拒绝。 */
  confirmedSlot: SchemeSlot | null
}

/** 方案对比用的家具摘要（与后端 OfficeSchemeDtos.SlotFurniture 对齐）。 */
export interface SchemeSlotFurniture {
  id: string
  label: string | null
  productName: string | null
  /** 画布毫米尺寸。 */
  w: number
  h: number
}

/** active 为当前激活槽（画布）家具，inactive 为另一槽家具；无 Plan B 时 inactive 为空。 */
export interface RoomSchemeSlots {
  activeSlot: SchemeSlot
  active: SchemeSlotFurniture[]
  inactive: SchemeSlotFurniture[]
}

export interface SchemeVersion {
  revision: number
  schemeToken: string | null
}

export interface RoomSchemeResult {
  canvas: SchemeVersion & { projectId: string; objects: OfficeObject[] }
  cart: CartItem[]
  scheme: RoomSchemeState | null
}

export interface BundleItem {
  rspuId: string
  variantId: string
  quantity: number
}

export interface BundleDisplayItem extends BundleItem {
  available?: boolean
  name: string | null
  variant: string | null
  imageUrl?: string | null
}

export interface ProductBundle {
  bundleId: string
  name: string
  revision: number
  items: BundleDisplayItem[]
}

export interface ProposalConfirmation {
  confirmationId: string
  canvasRevision: number
  confirmedAt: string
  current: boolean
}
