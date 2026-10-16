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
assert.equal(auto.slots.length, 9)
assert.equal(auto.rows, 3)
assert.equal(auto.columns, 3)
assert.equal(plan(square, 1200, 600, 'auto', { wallMm: 300, aisleMm: 900, gapMm: 100 }).slots.length, 6, '旧草稿的 100mm 间距仍可复现')
assert.equal(auto.slots[1].x - auto.slots[0].x - 1200, 0, '新布局默认同排紧贴')
assert.ok(Math.max(...auto.slots.map(item => item.y + item.h)) > square.h * 0.8, '下部空间也参与布置')
const singleRow = plan(square, 1200, 600, 'row')
assert.equal(singleRow.slots.length, 3, '显式单排仍只有一排')
assert.ok(singleRow.slots.every(item => item.y === 300), '单排应沿房间上侧布置')
assert.ok(plan(square, 1200, 600, 'auto', { wallMm: 300, aisleMm: 1600, gapMm: 100 }).slots.length < auto.slots.length)
assert.deepEqual(square, before, '计算不得修改空间几何')
const negative = zone(4358, 4376, -18000, -9000)
assert.equal(plan(negative, 1200, 600, 'auto').slots.length, 9)
const facing = plan(square, 1200, 600, 'faceToFace')
assert.ok(facing.slots.length > 0 && facing.slots.some(item => item.rotation === 180) && facing.slots.some(item => item.rotation === 0))
assert.equal(facing.slots[3].y - facing.slots[0].y - 600, 900, '面对面默认留出座位侧净距')
const backToBack = plan(square, 1200, 600, 'backToBack')
assert.equal(backToBack.slots[3].y - backToBack.slots[0].y - 600, 0, '背靠背默认贴合')
assert.equal(backToBack.slots[0].rotation, 0)
assert.equal(backToBack.slots[3].rotation, 180)
const pairedGap = plan(square, 1200, 600, 'faceToFace', { wallMm: 300, aisleMm: 900, gapMm: 0, pairMm: 200 })
assert.ok(pairedGap.slots.length > 0)
assert.equal(pairedGap.slots[3].y - pairedGap.slots[0].y - 600, 200, '对排中缝不受同排间距影响')
const staggered = plan(square, 1200, 600, 'staggered')
assert.ok(staggered.slots.some(item => item.y !== staggered.slots[0].y && !auto.slots.some(other => other.x === item.x && other.y === item.y)), '错位排应移动相邻排')
const centerAisle = plan(square, 1200, 600, 'centerAisle')
assert.ok(centerAisle.slots.length > 0 && centerAisle.rotated === false)
assert.equal(centerAisle.slots.length, 12, '中央通道应形成左右两侧桌列')
const leftBank = centerAisle.slots.filter(item => item.x < 2000)
const rightBank = centerAisle.slots.filter(item => item.x > 2000)
assert.equal(leftBank.length, 6)
assert.equal(rightBank.length, 6)
assert.ok(leftBank.every(item => item.rotation === 90) && rightBank.every(item => item.rotation === 270), '两侧桌子应朝向中央通道')
assert.ok(Math.min(...rightBank.map(item => item.x)) - Math.max(...leftBank.map(item => item.x + item.w)) >= 900, '中央通道应是连续净空')
const perimeter = plan(square, 1200, 600, 'perimeter', { wallMm: 300, aisleMm: 900, gapMm: 100 })
assert.equal(perimeter.slots.length, 6, '沿边应在不重叠时布满四侧')
assert.ok(perimeter.slots.some(item => item.rotation === 270 && item.x === 300))
assert.ok(perimeter.slots.some(item => item.rotation === 90 && item.x + item.w === square.w - 300))
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
for (const shape of [square, negative, lShape, zone(2400, 1600)]) for (const mode of ['auto','grid','row','backToBack','faceToFace','staggered','centerAisle','perimeter']) {
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
console.log('自动排版算法通过：默认同排 0mm、旧 100mm 设置兼容、面对面/错位排/中央通道、对排中缝、横纵比较、异形边界及各模式防重叠。')
