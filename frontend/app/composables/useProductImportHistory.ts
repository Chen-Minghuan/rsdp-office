import type { ProductImportRecord, ProductImportResult } from '~/types/imported-products'
import { uid } from '~/utils/helpers'

const storageKey = `rsdp-product-import-history:${import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8082'}`

function isResult(value: unknown): value is ProductImportResult {
  if (!value || typeof value !== 'object') return false
  const result = value as ProductImportResult
  const counts = [result.totalRows, result.successCount, result.failedCount, result.skippedCount]
  const validIssue = (issue: unknown) => {
    if (!issue || typeof issue !== 'object') return false
    const item = issue as { rowIndex: number; reason: string }
    return Number.isInteger(item.rowIndex) && typeof item.reason === 'string'
  }
  return counts.every(count => Number.isInteger(count) && count >= 0)
    && Array.isArray(result.failures) && result.failures.every(validIssue)
    && Array.isArray(result.warnings) && result.warnings.every(validIssue)
}

function isRecord(value: unknown): value is ProductImportRecord {
  if (!value || typeof value !== 'object') return false
  const record = value as ProductImportRecord
  return typeof record.id === 'string' && typeof record.fileName === 'string'
    && typeof record.createdAt === 'string' && !Number.isNaN(Date.parse(record.createdAt))
    && typeof record.updateIfExists === 'boolean'
    && (record.result === null ? typeof record.error === 'string' : isResult(record.result))
}

export function useProductImportHistory() {
  const records = useState<ProductImportRecord[]>('product-import-history', () => [])
  const initialized = useState<boolean>('product-import-history-ready', () => false)
  const storageAvailable = useState<boolean>('product-import-history-storage', () => true)

  function init() {
    if (initialized.value || !import.meta.client) return
    initialized.value = true
    try {
      const stored = JSON.parse(localStorage.getItem(storageKey) || '[]')
      records.value = Array.isArray(stored) ? stored.filter(isRecord).slice(0, 30) : []
    } catch {
      storageAvailable.value = false
    }
  }

  function addRecord(record: Omit<ProductImportRecord, 'id' | 'createdAt'>) {
    init()
    records.value = [{ ...record, id: uid('import'), createdAt: new Date().toISOString() }, ...records.value].slice(0, 30)
    try {
      localStorage.setItem(storageKey, JSON.stringify(records.value))
      storageAvailable.value = true
    } catch {
      storageAvailable.value = false
    }
  }

  onMounted(init)
  return { records, storageAvailable, addRecord }
}
