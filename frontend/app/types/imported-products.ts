export interface ImportedProduct {
  rspuId: string
  rspuCode: string | null
  externalCode: string | null
  productName: string | null
  primaryImageUrl: string | null
  retailPrice: number | null
  status: string | null
  auditStatus: string | null
  auditRemark: string | null
  createdAt: string
  updatedAt: string
}

export interface ProductImportIssue {
  rowIndex: number
  externalCode: string | null
  rspuId: string | null
  reason: string
}

export interface CleansingIssue {
  rowIndex: number
  field: string
  original: string
  cleaned: string
  message: string
}

export interface DataCleansingReport {
  cleanedRows: number
  issuesFound: number
  issues: CleansingIssue[]
}

export interface ProductImportResult {
  totalRows: number
  successCount: number
  failedCount: number
  skippedCount: number
  embeddedImageCount?: number
  imagePreviews?: string[]
  failures: ProductImportIssue[]
  warnings: ProductImportIssue[]
  cleansingReport: DataCleansingReport
}

export interface ProductImportRecord {
  id: string
  fileName: string
  createdAt: string
  updateIfExists: boolean
  result: ProductImportResult | null
  error: string | null
}

export interface ProductImportColumn {
  key: string
  label: string
  type: 'text' | 'number'
}

export interface ProductImportPreviewRow {
  rowIndex: number
  sourceSheetIndex?: number
  sourceRowIndex?: number
  sourceSheetName?: string
  sourceSizeText?: string
  sourceVariantCount?: number
  sourceVariantNote?: string
  embeddedImageCount?: number
  primaryEmbeddedImageIndex?: number | null
  selectedEmbeddedImageIndexes?: number[] | null
  imagePreviews?: string[]
  imageWarnings?: string[]
  reviewWarnings?: string[]
  fields: Record<string, string>
  skipped: boolean
  groupKey: string
  action: string
  errors: string[]
}

export interface ProductImportPreview {
  columns: ProductImportColumn[]
  rows: ProductImportPreviewRow[]
  productCount: number
  invalidRows: number
  skippedRows: number
  embeddedImageCount?: number
  rowsWithImages?: number
  cleansingReport: DataCleansingReport
}

export interface DictItem {
  dictCode: string
  dictName: string
  dictNameEn?: string
}

export interface ManualProductEntryRequest {
  productName?: string
  colorPrimaryName?: string
  description?: string
  retailPrice?: number | null
  externalCode?: string

  variantDisplayName?: string
  sizeCode?: string
  sizeText?: string
  widthMm?: number | null
  depthMm?: number | null
  heightMm?: number | null
  colorCode?: string
  colorText?: string
  materialCode?: string
  materialText?: string

  factoryPrice?: number | null
  moq?: number | null
  leadTimeDays?: number | null

  imageUrls?: string[]
}

export interface ProductVariant {
  variantId: string
  displayName: string
  sizeCode: string | null
  sizeText: string | null
  dimensions: string | null
  packageCount: number | null
  packageVolumeCbm: number | null
  grossWeight: number | null
  colorCode: string | null
  colorText: string | null
  materialCode: string | null
  materialText: string | null
  status: string
  factoryPrice: number | null
  moq: number | null
  leadTimeDays: number | null
  createdAt: string
  updatedAt: string
}

export interface ProductImage {
  imageId: string
  variantId: string | null
  imageType: string
  storageUrl: string
  primary: boolean
  fileSize: number | null
  width: number | null
  height: number | null
  format: string | null
  createdAt: string
}

export interface ProductDetail {
  rspuId: string
  rspuCode: string | null
  externalCode: string | null
  productName: string | null
  colorPrimaryName: string | null
  description: string | null
  retailPrice: number | null
  primaryImageUrl: string | null
  detailImageUrls: string | null
  status: string | null
  auditStatus: string | null
  auditRemark: string | null
  createdAt: string
  updatedAt: string
  deletedAt: string | null
  variants: ProductVariant[]
  images: ProductImage[]
}
