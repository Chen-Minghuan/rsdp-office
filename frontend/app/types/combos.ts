export interface ComboItem {
  rspuId: string
  productName: string | null
  primaryImageUrl: string | null
  retailPrice: number | null
  available: boolean
}

export interface ProductCombo {
  comboId: string
  name: string
  items: ComboItem[]
}
