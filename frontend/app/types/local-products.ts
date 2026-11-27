export interface LocalProductVariant {
  id?: string
  name: string
  widthMm: number | null
  depthMm: number | null
  heightMm: number | null
  sizeText?: string
  color?: string
  material?: string
  referencePrice?: number | null
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
  factoryCode?: string
  factoryModel: string
  variants: LocalProductVariant[]
  images: LocalProductImage[]
}

export type LocalProductSummary = Pick<LocalProductInput, 'name' | 'categoryCode' | 'factoryModel'> & {
  id: string
  revision: number
  factoryCode: string
}

export type LocalProductCatalogItem = LocalProductSummary & {
  primaryImageId?: string | null
  minReferencePrice?: number | null
  multipleVariants?: boolean
}

export interface LocalProductDetail {
  product: LocalProductSummary
  variants: LocalProductVariant[]
  images: LocalProductImage[]
  canWrite: boolean
}

export interface LocalProductCapacity {
  used: number
  limit: number
}

export interface LocalProductImportRecord {
  id: string
  fileName: string
  fileBytes: number
  actorName: string
  createdAt: string
  batchCount: number
  productCount: number
}

export interface LocalProductImportDetail {
  record: LocalProductImportRecord
  products: { id: string, name: string, revision: number }[]
}

export interface LocalProductWorkbookIssue {
  row: number
  message: string
  sheet?: string | null
  column?: string | null
}

export interface WorkbookVariantEvidence {
  description: string
  descriptionCell?: { sheet: string, row: number, column: string } | null
  referencePriceCell?: { sheet: string, row: number, column: string } | null
  materialSource?: 'COLUMN' | 'DESCRIPTION' | null
}

export interface SupplierWorkbookPreview {
  products: {
    product: LocalProductInput
    sources: { sheet: string, row: number, column: string }[]
    images: { key: string, selected: boolean }[]
    sourceCategory?: string | null
    variantEvidence?: WorkbookVariantEvidence[]
  }[]
  images: Record<string, LocalProductImage>
}

export interface LocalProductWorkbookPreview {
  products: LocalProductInput[]
  errors: LocalProductWorkbookIssue[]
  supplier?: SupplierWorkbookPreview | null
  mappingSheets?: WorkbookMappingSheet[]
  previewId?: string | null
  imageSizes?: Record<string, number>
}

export interface WorkbookImportProgress {
  number: number
  total: number
}

export type WorkbookField = 'MODEL' | 'NAME' | 'PICTURE' | 'SIZE' | 'WIDTH_MM' | 'DEPTH_MM' | 'HEIGHT_MM' | 'COLOR' | 'MATERIAL' | 'CATEGORY' | 'DESCRIPTION' | 'REFERENCE_PRICE'
export type WorkbookFieldColumns = Partial<Record<WorkbookField, number>>

export interface WorkbookMappingSheet {
  index: number
  name: string
  headers: { row: number, columns: { index: number, label: string }[] }[]
}

export interface WorkbookMappingSelection {
  sheetIndex: number
  headerRow: number
  columns: WorkbookFieldColumns
}

export interface WorkbookCategoryItem {
  name: string
  size?: string
  material?: string
}
