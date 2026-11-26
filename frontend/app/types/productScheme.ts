import type { OfficeObject, CartItem } from './office'

export type SchemeSlot = 'A' | 'B'
export interface RoomSchemeState {
  activeSlot: SchemeSlot
  alternativeSaved: boolean
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
