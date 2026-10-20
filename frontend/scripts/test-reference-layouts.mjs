import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'
async function moduleUrl(path, deps = {}) {
  let code = stripTypeScriptTypes(await readFile(new URL(path, import.meta.url), 'utf8'))
  for (const [key, url] of Object.entries(deps)) code = code.replaceAll(`'${key}'`, `'${url}'`)
  return 'data:text/javascript;base64,' + Buffer.from(code).toString('base64')
}
const bounds = await moduleUrl('../app/utils/floorPlan.ts')
const { planStrictArrangement: plan } = await import(await moduleUrl('../app/utils/officeAutoLayout.ts', { '~/utils/floorPlan': bounds }))
const zone = { id: 'REFERENCE', reviewUnit: 'mm', x: -10000, y: -8000, w: 20000, h: 16000, polygon: [[-10000,-8000],[10000,-8000],[10000,8000],[-10000,8000]] }
const cases = [
  ['straight-desk', 'straight-desk-row', 1200, 600, 6, 1000],
  ['straight-desk', 'straight-desk-face-to-face', 1200, 600, 12, 1600],
  ['straight-screen', 'straight-screen-row', 1200, 600, 6, 1000],
  ['straight-screen', 'straight-screen-face-to-face', 1200, 600, 12, 1600],
  ['l-desk', 'l-desk-row', 1400, 1400, 6, 600],
  ['l-desk', 'l-desk-face-to-face', 1400, 1400, 12, 600],
  ['l-screen', 'screen-f-row', 1400, 1400, 8, 900],
  ['l-screen', 'screen-t-face-to-face', 1400, 1400, 12, 900],
  ['l-screen', 'screen-c-face-to-face', 1400, 1400, 8, 900],
  ['l-screen', 'screen-cross-face-to-face', 1400, 1400, 16, 900],
]
for (const [style, preset, w, d, count, gap] of cases) {
  const result = plan(zone, w, d, style, preset)
  assert.equal(result.problem, null, preset)
  assert.equal(result.slots.length, count, preset)
  const minX = Math.min(...result.slots.map(s => s.x)), minY = Math.min(...result.slots.map(s => s.y))
  const actual = result.slots.map(s => [s.x - minX, s.y - minY, s.rotation, !!s.mirrorX]).sort((a, b) => a[1] - b[1] || a[0] - b[0])
  const expected = []
  if (preset.includes('desk-row') || preset === 'straight-screen-row' || preset === 'screen-f-row') {
    for (const y of [0, d + gap]) for (let x = 0; x < (preset === 'screen-f-row' ? 4 : 3); x++) expected.push([x * w, y, 0, false])
  } else if (preset === 'screen-t-face-to-face' || preset === 'screen-c-face-to-face') {
    for (let row = 0; row < (preset.startsWith('screen-t') ? 3 : 2); row++) for (const x of [0, 2 * w + gap]) {
      const bottomC = preset.startsWith('screen-c') && row === 1
      expected.push([x, row * d, bottomC ? 180 : 0, bottomC], [x + w, row * d, bottomC ? 180 : 0, !bottomC])
    }
  } else if (preset.startsWith('screen-cross')) {
    for (const y of [0, 2 * d + gap]) for (let row = 0; row < 2; row++) for (let x = 0; x < 4; x++) expected.push([x * w, y + row * d, row === 0 ? 180 : 0, row === 0 ? x % 2 === 0 : x % 2 === 1])
  } else {
    for (const y of [0, 2 * d + gap]) for (let row = 0; row < 2; row++) for (let x = 0; x < 3; x++) expected.push([x * w, y + row * d, row === 0 ? 180 : 0, row === 0])
  }
  expected.sort((a, b) => a[1] - b[1] || a[0] - b[0])
  assert.deepEqual(actual, expected, `${preset}: exact reference coordinates, orientation and mirror`)
  assert.deepEqual(plan(zone, w, d, style, preset, { wallMm: 300, aisleMm: gap, gapMm: 250, faceGapMm: gap }).slots, result.slots, 'intra-group seams must stay closed')
}
const narrow = { ...zone, polygon: [[0,0],[4500,0],[4500,8000],[0,8000]] }
assert.equal(plan(narrow, 1400, 1400, 'l-screen', 'screen-t-face-to-face').slots.length, 0, 'cannot silently keep only one T group')
assert.equal(plan(narrow, 1400, 1400, 'l-screen', 'screen-c-face-to-face').slots.length, 0, 'cannot silently keep only one C group')
assert.equal(plan(zone, 1400, 1400, 'straight-desk', 'screen-t-face-to-face').slots.length, 0, 'reject incompatible preset')
console.log('PASS: all 10 reference layouts, exact default gaps, fixed counts, mirrors/rotations, touching seams and whole-composition fit')
