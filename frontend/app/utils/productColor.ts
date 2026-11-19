import type { Product } from '~/types/office'

export type ProductColorTone = 'DARK' | 'LIGHT' | 'UNKNOWN'
export type ProductSearchFields = Pick<Product, 'colorPrimaryName' | 'name' | 'variant'>

const DARK_PRIMARY_COLORS = new Set(['暮山黑橡', '月影胡桃', '雾山核桃', '纳柯', '深棕'])
const LIGHT_PRIMARY_COLORS = new Set(['蜜芽胡桃', '雅士白', '天使白', '液态银', '灰绿色', '海鸥灰'])
const SEARCH_ALIASES: Record<string, string[]> = {
  胡桃: ['月影胡桃', '蜜芽胡桃', '雾山核桃'],
  核桃: ['雾山核桃'],
  白色: ['雅士白', '天使白'],
  银色: ['液态银'],
}

/** Normalize display text without guessing whether unknown words are colors. */
export function normalizeColorText(value?: string | null): string {
  return (value || '').normalize('NFKC').trim()
    .replace(/[／/、，,]/g, '+')
    .replace(/\s*\+\s*/g, '+')
    .replace(/\++/g, '+')
    .replace(/^\+|\+$/g, '')
}

export function normalizeColorSearch(value?: string | null): string {
  return (value || '').normalize('NFKC').toLowerCase()
    .replace(/[\s+／/、，,·._-]+/g, '')
}

export function primaryColorName(value?: string | null): string {
  return normalizeColorText(value).split('+')[0] || ''
}

export function productColorTone(value?: string | null): ProductColorTone {
  const primary = primaryColorName(value)
  if (!primary) return 'UNKNOWN'
  if (DARK_PRIMARY_COLORS.has(primary)) return 'DARK'
  if (LIGHT_PRIMARY_COLORS.has(primary)) return 'LIGHT'
  if (/[黑深暗墨炭]/.test(primary)) return 'DARK'
  if (/[白浅米银亮]/.test(primary)) return 'LIGHT'
  return 'UNKNOWN'
}

export function colorToneLabel(tone: ProductColorTone): string {
  return tone === 'DARK' ? '深色' : tone === 'LIGHT' ? '浅色' : '未分类'
}

export function matchesProductColorSearch(product: ProductSearchFields, keyword: string): boolean {
  const query = normalizeColorSearch(keyword)
  if (!query) return true
  const searchable = normalizeColorSearch([product.colorPrimaryName, product.name, product.variant].filter(Boolean).join(' '))
  if (searchable.includes(query)) return true
  return (SEARCH_ALIASES[query] || []).some(alias => searchable.includes(normalizeColorSearch(alias)))
}
