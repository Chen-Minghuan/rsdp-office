import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'

// Load the actual pure TS modules with Node 22's built-in stripper; no test-only geometry.
async function moduleUrl(path, dependencies = {}) {
  let source = stripTypeScriptTypes(await readFile(new URL(path, import.meta.url), 'utf8'))
  for (const [alias, url] of Object.entries(dependencies)) source = source.replaceAll(`'${alias}'`, `'${url}'`)
  return 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
}
const boundsUrl = await moduleUrl('../app/utils/floorPlan.ts')
const { planOfficeFurniture: plan, layoutRectInsideRing: inside } = await import(await moduleUrl('../app/utils/officeAutoLayout.ts', { '~/utils/floorPlan': boundsUrl }))
const { manualRectContainedByUnion: contained } = await import(await moduleUrl('../app/utils/manualLayoutGeometry.ts'))
const zone = (w, h, x = 0, y = 0) => ({ id: 'TEST', reviewUnit: 'mm', x, y, w, h, polygon: [[x,y],[x+w,y],[x+w,y+h],[x,y+h]] })
const square = zone(4358, 4376)
const before = structuredClone(square)
const auto = plan(square, 1200, 600, 'auto')
assert.equal(auto.slots.length, 6)
assert.equal(auto.rows, 3)
assert.equal(auto.columns, 2)
assert.ok(Math.max(...auto.slots.map(item => item.y + item.h)) > square.h * 0.8, '下部空间也参与布置')
assert.equal(plan(square, 1200, 600, 'row').slots.length, 2, '显式单排仍只有一排')
assert.ok(plan(square, 1200, 600, 'auto', { wallMm: 300, aisleMm: 1600, gapMm: 100 }).slots.length < auto.slots.length)
assert.deepEqual(square, before, '计算不得修改空间几何')
const negative = zone(4358, 4376, -18000, -9000)
assert.equal(plan(negative, 1200, 600, 'auto').slots.length, 6)
const wide = plan(zone(3000, 8000), 1200, 600, 'auto')
assert.ok(wide.rotated && wide.rows > 1, '比较整体排布朝向，而非只旋转桌面')
assert.equal(plan(square, 10000, 600, 'auto').slots.length, 0)
assert.equal(plan(square, NaN, 600, 'auto').slots.length, 0)
assert.ok(plan(square, 1200, 600, 'auto', { wallMm: -1, aisleMm: 900, gapMm: 100 }).problem)
assert.ok(plan(zone(1e7, 1e7), 10, 10, 'auto').problem, '极端数量明确返回，不能卡死或静默截断')

// A narrow notch can cross a rectangle without containing any rectangle corner.
const notch = [[0,0],[4000,0],[4000,4000],[2100,4000],[2100,900],[1900,900],[1900,4000],[0,4000]]
assert.equal(inside({ x: 1000, y: 1000, w: 2000, h: 1000 }, notch), false)
assert.equal(inside({ x: 0, y: 0, w: 4000, h: 900 }, notch), true, '贴边不当成越界')
assert.equal(inside({ x: 2000, y: 2000, w: 1000, h: 1000 }, [[0,0],[4000,0],[0,4000]]), false)
const lShape = { ...zone(7000, 7000), polygon: [[0,0],[7000,0],[7000,2500],[2500,2500],[2500,7000],[0,7000]] }
const parts = [{ x: 0, y: 0, w: 7000, h: 2500 }, { x: 0, y: 2500, w: 2500, h: 4500 }]
for (const shape of [square, negative, lShape, zone(2400, 1600)]) for (const mode of ['auto','grid','row','backToBack','perimeter']) {
  const result = plan(shape, 1200, 600, mode)
  for (const item of result.slots) {
    const expanded = { x: item.x - 300, y: item.y - 300, w: item.w + 600, h: item.h + 600 }
    const expected = shape === lShape ? parts : [{ x: shape.x, y: shape.y, w: shape.w, h: shape.h }]
    assert.ok(contained(expanded, expected), mode + ': 独立矩形并集算法核对离墙范围')
    assert.deepEqual([item.w, item.h].sort((a,b) => a-b), [600,1200], '不得伸缩家具')
  }
  for (let i = 0; i < result.slots.length; i++) for (let j = i + 1; j < result.slots.length; j++) {
    const a = result.slots[i], b = result.slots[j]
    assert.ok(a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y, mode + ': 家具不能重叠')
  }
}
console.log('自动排版算法通过：4.4m 近方空间 6 件/3 排、单排、净距变化、横纵比较、负坐标、凹口/斜边、异形离墙范围、旋转尺寸、各模式防重叠和极端输入。')
