import type { CatalogProductFilters } from '~/types/imported-products'

export const FILTER_KEYS = ['levels', 'spaceTypes', 'productTypes', 'materials', 'colors', 'widthRanges', 'legTypes', 'storageTypes'] as const satisfies readonly (keyof CatalogProductFilters)[]

export function emptyProductFilters(): CatalogProductFilters {
  return { levels: [], spaceTypes: [], productTypes: [], materials: [], colors: [], widthRanges: [], legTypes: [], storageTypes: [] }
}

export function cloneProductFilters(value: CatalogProductFilters): CatalogProductFilters {
  return Object.fromEntries(FILTER_KEYS.map(key => [key, [...value[key]]])) as unknown as CatalogProductFilters
}

export function countProductFilters(value: CatalogProductFilters): number {
  return FILTER_KEYS.reduce((total, key) => total + value[key].length, 0)
}

export function toggleProductFilter<K extends keyof CatalogProductFilters>(
  value: CatalogProductFilters,
  key: K,
  option: CatalogProductFilters[K][number]
): CatalogProductFilters {
  const copy = cloneProductFilters(value)
  const values = copy[key] as Array<CatalogProductFilters[K][number]>
  const index = values.indexOf(option)
  if (index >= 0) values.splice(index, 1)
  else values.push(option)
  return copy
}
