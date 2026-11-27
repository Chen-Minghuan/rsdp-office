import assert from 'node:assert/strict'
import {
  emptyProductFilters,
  countProductFilters,
  cloneProductFilters,
  toggleProductFilter,
  FILTER_KEYS
} from '../app/utils/productFilter.ts'

const initial = emptyProductFilters()
assert.equal(countProductFilters(initial), 0)
assert.deepEqual(FILTER_KEYS, ['levels', 'spaceTypes', 'productTypes', 'materials', 'colors', 'widthRanges', 'legTypes', 'storageTypes'])

const next = toggleProductFilter(initial, 'spaceTypes', 'STAFF')
assert.deepEqual(next.spaceTypes, ['STAFF'])
assert.equal(initial.spaceTypes.length, 0, 'toggle 不得修改原对象')
assert.equal(countProductFilters(next), 1)
assert.deepEqual(toggleProductFilter(next, 'spaceTypes', 'STAFF').spaceTypes, [])

const withWidth = toggleProductFilter(next, 'widthRanges', 'R1200_1600')
assert.deepEqual(withWidth.widthRanges, ['R1200_1600'])
assert.equal(countProductFilters(withWidth), 2)
assert.deepEqual(toggleProductFilter(withWidth, 'widthRanges', 'R1200_1600').widthRanges, [])

const cloned = cloneProductFilters(withWidth)
assert.notEqual(cloned.spaceTypes, withWidth.spaceTypes)
assert.deepEqual(cloned, withWidth)
cloned.colors.push('纳柯')
assert.equal(withWidth.colors.length, 0, 'clone 必须是深拷贝')

console.log('test-product-filter: all assertions passed')
