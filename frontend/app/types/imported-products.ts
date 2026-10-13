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
