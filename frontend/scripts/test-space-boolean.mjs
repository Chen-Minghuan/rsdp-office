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
    if (spec === 'polygon-clipping') {
      source = source.replaceAll(`'${spec}'`, `'${new URL('../node_modules/polygon-clipping/dist/polygon-clipping.esm.js', import.meta.url).href}'`)
      continue
    }
    if (!spec.startsWith('.') && !spec.startsWith('~/')) continue
    const resolved = new URL((spec.startsWith('~/') ? '../app/' + spec.slice(2) : spec) + (spec.endsWith('.ts') ? '' : '.ts'), spec.startsWith('~/') ? import.meta.url : path)
    source = source.replaceAll(`'${spec}'`, `'${await load(resolved)}'`)
  }
  const url = 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
  cache.set(path.href, url)
  return url
}
const { unionRings, subtractRings } = await import(await load(new URL('../app/utils/spatial/boolean.ts', import.meta.url)))
const { ringArea } = await import(await load(new URL('../app/utils/spatial/geometry.ts', import.meta.url)))

const area = (ring) => Math.abs(ringArea(ring))
const rect = (x, y, w, h) => [[x, y], [x + w, y], [x + w, y + h], [x, y + h]]
const bounds = (ring) => ({ minX: Math.min(...ring.map(p => p[0])), maxX: Math.max(...ring.map(p => p[0])), minY: Math.min(...ring.map(p => p[1])), maxY: Math.max(...ring.map(p => p[1])) })

// 合并：相邻矩形共边 → 单环大矩形。
{
  const result = unionRings(rect(0, 0, 3000, 4000), rect(3000, 0, 3000, 4000))
  assert.ok('ring' in result, 'adjacent rects merge: ' + JSON.stringify(result))
  assert.equal(result.ring.length, 4, 'merged ring is a clean rectangle')
  assert.equal(area(result.ring), 6000 * 4000, 'merged area is the sum')
  assert.deepEqual(bounds(result.ring), { minX: 0, maxX: 6000, minY: 0, maxY: 4000 })
}
// 合并：部分重叠 → L 形，面积 = 和 − 交集。
{
  const result = unionRings(rect(0, 0, 4000, 4000), rect(2000, 2000, 4000, 4000))
  assert.ok('ring' in result, 'overlapping rects merge: ' + JSON.stringify(result))
  assert.equal(area(result.ring), 4000 * 4000 + 4000 * 4000 - 2000 * 2000, 'L shape area')
  assert.deepEqual(bounds(result.ring), { minX: 0, maxX: 6000, minY: 0, maxY: 6000 })
}
// 合并：不相交 → 拒绝（分离多块）。
{
  const result = unionRings(rect(0, 0, 3000, 4000), rect(10000, 0, 3000, 4000))
  assert.ok('problem' in result && result.problem.includes('不相连'), 'disjoint merge refused: ' + JSON.stringify(result))
}
// 剔除：边缘重叠 → 轮廓收窄为剩余矩形。
{
  const result = subtractRings(rect(0, 0, 6000, 4000), rect(4000, -500, 3000, 5000))
  assert.ok('ring' in result, 'edge subtract: ' + JSON.stringify(result))
  assert.equal(area(result.ring), 4000 * 4000, 'remaining area')
  assert.deepEqual(bounds(result.ring), { minX: 0, maxX: 4000, minY: 0, maxY: 4000 })
}
// 剔除：角部重叠 → L 形。
{
  const result = subtractRings(rect(0, 0, 6000, 4000), rect(4000, 2000, 3000, 3000))
  assert.ok('ring' in result, 'corner subtract: ' + JSON.stringify(result))
  assert.equal(result.ring.length, 6, 'L ring vertex count')
  assert.equal(area(result.ring), 6000 * 4000 - 2000 * 2000, 'L area')
}
// 剔除：刀具完全在内部 → 孔洞拒绝，并提示改用禁放区。
{
  const result = subtractRings(rect(0, 0, 6000, 4000), rect(2000, 1000, 1000, 1000))
  assert.ok('problem' in result && result.problem.includes('孔洞') && result.problem.includes('禁放区'), 'interior hole refused: ' + JSON.stringify(result))
}
// 剔除：横贯中间 → 分离多块拒绝。
{
  const result = subtractRings(rect(0, 0, 6000, 4000), rect(-500, 1500, 7000, 1000))
  assert.ok('problem' in result && result.problem.includes('多块'), 'splitting subtract refused: ' + JSON.stringify(result))
}
// 剔除：刀具全覆盖 → 为空拒绝。
{
  const result = subtractRings(rect(0, 0, 3000, 3000), rect(-500, -500, 4000, 4000))
  assert.ok('problem' in result && result.problem.includes('不剩'), 'full subtract refused: ' + JSON.stringify(result))
}
// 剔除：无交集 → 返回原轮廓（store 层另有「无重叠」拦截）。
{
  const result = subtractRings(rect(0, 0, 3000, 3000), rect(10000, 0, 1000, 1000))
  assert.ok('ring' in result && Math.abs(area(result.ring) - 3000 * 3000) < 1, 'disjoint subtract keeps subject')
}
// 退化输入。
assert.ok('problem' in unionRings([[0, 0], [1, 1]], rect(0, 0, 100, 100)), 'degenerate ring refused')

console.log('test-space-boolean: 10 assertions passed')
