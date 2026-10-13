export interface ImportedProduct {
  rspuId: string
  rspuCode: string | null
  externalCode: string | null
  categoryCode: string | null
  categoryPath: string | null
  productName: string | null
  positioningLabel: string | null
  primaryImageUrl: string | null
  retailPrice: number | null
  referencePriceBand: string | null
  productLevel: string | null
  warrantyYears: number | null
  sixDimTags: string | null
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
  cleansingReport: DataCleansingReport
}

export interface DictItem {
  dictCode: string
  dictName: string
  dictNameEn?: string
}

export interface ManualProductEntryRequest {
  categoryCode: string
  positioningLabel: string
  productName?: string
  colorPrimaryName?: string
  materialTags?: string[]
  sceneTags?: string[]
  productLevel?: string
  warrantyYears?: number | null
  referencePriceBand?: string
  sixDimTags?: string
  keySpecs?: string
  description?: string
  retailPrice?: number | null
  externalCode?: string

  variantDisplayName?: string
  sizeCode?: string
  sizeText?: string
  colorCode?: string
  colorText?: string
  materialCode?: string
  materialText?: string
  variantReferencePriceBand?: string
  variantProductLevel?: string

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
  colorCode: string | null
  colorText: string | null
  materialCode: string | null
  materialText: string | null
  referencePriceBand: string | null
  productLevel: string | null
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
  categoryCode: string | null
  categoryPath: string | null
  positioningLabel: string | null
  productName: string | null
  colorPrimaryName: string | null
  description: string | null
  retailPrice: number | null
  referencePriceBand: string | null
  productLevel: string | null
  warrantyYears: number | null
  sixDimTags: string | null
  materialTags: string | null
  sceneTags: string | null
  keySpecs: string | null
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
