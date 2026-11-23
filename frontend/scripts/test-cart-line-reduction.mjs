import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'

const cache = new Map()
async function load(path) {
  if (cache.has(path.href)) return cache.get(path.href)
  let source = stripTypeScriptTypes(await readFile(path, 'utf8'))
  const imports = [...source.matchAll(/(?:from\s*|import\s*)['"]([^'"]+)['"]/g)]
  for (const match of imports) {
    const spec = match[1]
    if (!spec.startsWith('.') && !spec.startsWith('~/')) continue
    const resolved = new URL((spec.startsWith('~/') ? '../app/' + spec.slice(2) : spec) + (spec.endsWith('.ts') ? '' : '.ts'), spec.startsWith('~/') ? import.meta.url : path)
    source = source.replaceAll(`'${spec}'`, `'${await load(resolved)}'`)
  }
  const url = 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
  cache.set(path.href, url)
  return url
}

const { planCartLineReduction } = await import(await load(new URL('../app/utils/cartLineReduction.ts', import.meta.url)))

const furniture = (id, lineId, productId = 'P1') => ({
  id, kind: 'furniture', zoneId: 'Z1', x: 0, y: 0, w: 1200, h: 600,
  productId, productSelection: lineId ? { cartLineId: lineId } : undefined,
})
const line = (id, qty, productId = 'P1', zoneId = 'Z1') => ({ id, projectId: 'F1', zoneId, zoneName: '空间', productId, qty })

// 1. 共享行（qty=2）删一件家具：只扣减数量，不删整行
{
  const cart = [line('L1', 2)]
  const items = [furniture('F-A', 'L1'), furniture('F-B', 'L1')]
  const plan = planCartLineReduction(cart, [items[0]])
  assert.equal(plan.toDelete.length, 0, '共享行删一件不得删除整行')
  assert.equal(plan.toUpdate.length, 1)
  assert.equal(plan.toUpdate[0].qty, 1, '数量从 2 扣减为 1')
  assert.equal(plan.nextCart.length, 1)
  assert.equal(plan.nextCart[0].qty, 1)
}

// 2. 同一批删掉共享行的两件家具：数量耗尽，整行删除
{
  const cart = [line('L1', 2)]
  const items = [furniture('F-A', 'L1'), furniture('F-B', 'L1')]
  const plan = planCartLineReduction(cart, items)
  assert.equal(plan.toUpdate.length, 0)
  assert.equal(plan.toDelete.length, 1, '引用数耗尽应整行删除')
  assert.equal(plan.toDelete[0].id, 'L1')
  assert.equal(plan.nextCart.length, 0)
}

// 3. 独占行（qty=1）删唯一家具：整行删除
{
  const cart = [line('L1', 1)]
  const plan = planCartLineReduction(cart, [furniture('F-A', 'L1')])
  assert.equal(plan.toDelete.length, 1)
  assert.equal(plan.nextCart.length, 0)
}

// 4. 清空排版：只清关联行，未关联/其他空间/手动加购行保留
{
  const cart = [line('L1', 1), line('L2', 3, 'P2'), line('L3', 5, 'P3', 'Z2')]
  const cleared = [furniture('F-A', 'L1')]
  const plan = planCartLineReduction(cart, cleared)
  assert.deepEqual(plan.toDelete.map(item => item.id), ['L1'])
  assert.deepEqual(plan.nextCart.map(item => item.id), ['L2', 'L3'], '无关购物车行必须保留')
  assert.equal(plan.nextCart[0].qty, 3)
}

// 5. 家具未绑定选品（无 cartLineId）：不影响任何购物车行
{
  const cart = [line('L1', 2)]
  const plan = planCartLineReduction(cart, [furniture('F-A', undefined)])
  assert.equal(plan.toDelete.length, 0)
  assert.equal(plan.toUpdate.length, 0)
  assert.deepEqual(plan.nextCart, cart)
}

// 6. 购物车行数量大于引用数（历史手动加购合并）：只扣减被删家具的引用
{
  const cart = [line('L1', 5)]
  const items = [furniture('F-A', 'L1'), furniture('F-B', 'L1')]
  const plan = planCartLineReduction(cart, items)
  assert.equal(plan.toDelete.length, 0)
  assert.equal(plan.toUpdate[0].qty, 3, '5 - 2 = 3，保留手动加购数量')
  assert.equal(plan.nextCart[0].qty, 3)
}

console.log('test-cart-line-reduction: all 6 cases passed')
