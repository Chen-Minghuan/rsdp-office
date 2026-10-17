import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'

async function moduleUrl(path, dependencies = {}) {
  let source = stripTypeScriptTypes(await readFile(new URL(path, import.meta.url), 'utf8'))
  for (const [alias, url] of Object.entries(dependencies)) source = source.replaceAll(`'${alias}'`, `'${url}'`)
  return 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
}
const geometry = await moduleUrl('../app/utils/spatial/geometry.ts')
const review = await moduleUrl('../app/utils/spatial/review.ts', { '~/utils/spatial/geometry': geometry })
const { applyDesignCorrections: apply, sourceContour, canRestoreSourceContour } = await import(await moduleUrl('../app/utils/spatial/designCorrections.ts', { '~/utils/spatial/review': review }))
const original = [[100,100],[400,100],[400,500],[100,500]]
const corrected = [[100,100],[450,100],[450,300],[400,300],[400,600],[100,600]]
const makeZone = () => ({ id: 'NEW', name: '休闲区', polygon: structuredClone(original), sourcePolygon: structuredClone(original), reviewUnit: 'source', confirmed: false })
const plan = {
  inputMode: 'image', imageSha256: 'a'.repeat(64), unitResolution: { canonicalUnit: 'px' },
  previewBounds: { minX:0,minY:0,maxX:1000,maxY:800 }, rooms: [{ roomId: 'NEW' }],
  designCorrections: [{ name: '休闲区', polygon: corrected, sourceAnalysisId: 'OLD', sourceAnalysisRevision: 1, canvasRevision: 20 }],
}
let zones = [makeZone()]
const before = structuredClone(plan)
assert.equal(apply(zones, plan), 1)
assert.deepEqual(zones[0].polygon, corrected)
assert.deepEqual(zones[0].sourcePolygon, original)
assert.equal(zones[0].sourceAnalysisId, undefined, '不偷换当前来源关联')
assert.equal(zones[0].evidenceKind, 'MANUAL')
assert.equal(zones[0].reviewUnit, 'source')
assert.equal(zones[0].confirmed, false)
assert.deepEqual(plan, before, '来源识别对象不可变')
assert.equal(apply([makeZone()], { ...plan, imageSha256: null }), 0)
assert.equal(apply([makeZone()], { ...plan, inputMode: 'cad' }), 0)
assert.equal(apply([makeZone(), makeZone()], plan), 0, '重名不猜对应关系')
assert.equal(apply([makeZone()], { ...plan, designCorrections: [plan.designCorrections[0], plan.designCorrections[0]] }), 0)
for (const polygon of [[[0,0],[1000,800],[1000,0],[0,800]], [[0,0],[2000,0],[2000,500],[0,500]], [null, null, null]]) {
  assert.equal(apply([makeZone()], { ...plan, designCorrections: [{ ...plan.designCorrections[0], polygon }] }), 0)
}
const misplaced = [[600,100],[900,100],[900,500],[600,500]]
const misplacedPlan = { ...plan, designCorrections: [{ ...plan.designCorrections[0], polygon: misplaced }] }
assert.equal(apply([makeZone()], misplacedPlan), 1, '开放分区沿用人工划定的功能边界')
zones = [makeZone()]
assert.equal(apply(zones, { ...misplacedPlan, rooms: [{ roomId: 'NEW', contourGrounded: true }] }), 0, '旧错位框不能覆盖新定位的独立房间')
assert.deepEqual(zones[0].polygon, original)
const truncated = [[110,200],[390,200],[390,480],[110,480]]
zones = [makeZone()]
assert.equal(apply(zones, { ...plan, rooms: [{ roomId: 'NEW', contourGrounded: true }],
  designCorrections: [{ ...plan.designCorrections[0], polygon: truncated }] }), 0, '重叠的旧小框不能截短新墙线轮廓')
assert.deepEqual(zones[0].polygon, original)
assert.match(zones[0].note, /历史编辑.*未自动套用/)
const close = original.map(([x,y]) => [x+2,y+2])
zones = [makeZone()]
assert.equal(apply(zones, { ...plan, rooms: [{ roomId: 'NEW', contourGrounded: true }],
  designCorrections: [{ ...plan.designCorrections[0], polygon: close }] }), 1, '边界相近的历史微调仍保留')
assert.deepEqual(zones[0].polygon, close)
assert.equal(canRestoreSourceContour(makeZone()), false)
assert.equal(canRestoreSourceContour({ ...makeZone(), polygon: truncated }), true)
assert.deepEqual(sourceContour({ ...makeZone(), reviewUnit: 'mm', mmPerSourceUnit: 10 }), original.map(([x,y]) => [x*10,y*10]))
assert.equal(sourceContour({ ...makeZone(), reviewUnit: 'mm', mmPerSourceUnit: undefined }), null, '未知比例不可猜测恢复尺寸')
assert.equal(sourceContour({ ...makeZone(), sourcePolygon: undefined }), null)
console.log('同图轮廓继承通过：来源保留、人工证据、比例不自动确认、重名/异图/非法轮廓保护、封闭房间错位旧框保护。')
