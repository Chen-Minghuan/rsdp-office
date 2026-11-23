export interface CatalogCapabilities {
  managed: boolean
}

export interface ImportedProduct {
  sourceChannel?: string | null
  ownerFactoryCode?: string | null
  sourceFactoryName?: string | null
  rspuId: string
  rspuCode: string | null
  externalCode: string | null
  productName: string | null
  primaryImageUrl: string | null
  retailPrice: number | null
  status: string | null
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

export interface ProductVariant {
  variantId: string
  displayName: string
  sizeCode: string | null
  sizeText: string | null
  dimensions: string | null
  dimensionsText?: string | null
  packageCount: number | null
  packageVolumeCbm: number | null
  grossWeight: number | null
  colorCode: string | null
  colorText: string | null
  materialCode: string | null
  materialText: string | null
  status: string
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
  sourceChannel?: string | null
  ownerFactoryCode?: string | null
  sourceFactoryName?: string | null
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
  createdAt: string
  updatedAt: string
  deletedAt: string | null
  variants: ProductVariant[]
  images: ProductImage[]
}
