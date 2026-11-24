export interface LocalProductVariant {
  id?: string
  name: string
  widthMm: number | null
  depthMm: number | null
  heightMm: number | null
  sizeText?: string
  color?: string
  material?: string
}

export interface LocalProductImage {
  id?: string
  mediaType: string
  content?: string
}

export interface LocalProductInput {
  id?: string
  revision?: number
  name: string
  categoryCode: string
  factoryCode: string
  factoryModel: string
  variants: LocalProductVariant[]
  images: LocalProductImage[]
}

export type LocalProductSummary = Pick<LocalProductInput, 'name' | 'categoryCode' | 'factoryCode' | 'factoryModel'> & {
  id: string
  revision: number
}
