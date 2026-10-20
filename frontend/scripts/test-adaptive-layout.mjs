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
const { planAdaptiveArrangement: plan } = await import(await load(new URL('../app/utils/officeAdaptiveLayout.ts', import.meta.url)))
const zone = (w,h) => ({ id: 'ROOM', reviewUnit: 'mm', polygon: [[-1000,-2000],[w-1000,-2000],[w-1000,h-2000],[-1000,h-2000]], x:-1000,y:-2000,w,h })
const spacing = { wallMm:300, aisleMm:900, gapMm:0, faceGapMm:600 }
const screenshot = plan(zone(6093,17810),1400,1400,'l-desk','l-desk-row',spacing)
assert.equal(screenshot.slots.length,36,'6093×17810 room grows to 36, never caps at the illustrated six')
assert.equal(screenshot.rotated,true,'compare both whole-layout orientations')
assert.equal(screenshot.rows,12)
assert.equal(screenshot.columns,3)
assert.ok(screenshot.slots.every(s=>s.rotation===90&&!s.mirrorX))
assert.equal(plan(zone(6093,17810),1400,1400,'l-desk','l-desk-row',spacing,[],10).slots.length,10)
const pair=plan(zone(6093,17810),1400,1400,'l-desk','l-desk-face-to-face',spacing,[],3)
assert.equal(pair.slots.length,4,'round up to whole opposing pairs')
for(const id of new Set(pair.slots.map(s=>s.clusterId)))assert.equal(pair.slots.filter(s=>s.clusterId===id).length,2)
const blocked=plan(zone(6093,17810),1400,1400,'l-desk','l-desk-row',spacing,[{x:-1000,y:-2000,w:6093,h:6000}])
assert.ok(blocked.slots.length>6&&blocked.slots.length<36,'blocked cells are skipped without rejecting remaining floor')
for(const preset of ['screen-t-face-to-face','screen-c-face-to-face','screen-cross-face-to-face']) {
 const size=preset==='screen-t-face-to-face'?2:4
 const result=plan(zone(12000,8000),1400,1400,'l-screen',preset,{...spacing,faceGapMm:900},[{x:3000,y:500,w:1200,h:1500}])
 assert.ok(result.slots.length>0)
 assert.equal(result.slots.length%size,0)
 for(const id of new Set(result.slots.map(s=>s.clusterId)))assert.equal(result.slots.filter(s=>s.clusterId===id).length,size)
}
const small=plan(zone(2600,2600),1400,1400,'l-desk','l-desk-row',spacing)
assert.equal(small.slots.length,1,'small room can contain fewer than the sample six')
assert.equal(plan(zone(1200,1200),1400,1400,'l-desk','l-desk-row',spacing).slots.length,0)
const concave={...zone(10000,10000),polygon:[[-1000,-2000],[9000,-2000],[9000,1000],[2000,1000],[2000,8000],[-1000,8000]]}
assert.ok(plan(concave,1200,600,'straight-desk','straight-desk-row',{...spacing,faceGapMm:1000}).slots.length>6)
assert.equal(plan(zone(1e8,1e8),1400,1400,'l-desk','l-desk-row',spacing).slots.length,0,'bounded search')
console.log('PASS: actual screenshot room 36, dynamic rows/columns, orientation, targets, whole pairs/T/C/cross cells, retained blockers, concave/small/oversized rooms')
