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
assert.equal(screenshot.columns,2)
const shotMains=screenshot.slots.filter(s=>!s.fill),shotFills=screenshot.slots.filter(s=>s.fill)
assert.equal(shotMains.length,24)
assert.equal(shotFills.length,12,'left strip takes a panel-to-wall column of singles, seats facing the room')
assert.ok(shotMains.every(s=>s.rotation===90&&!s.mirrorX))
assert.ok(shotFills.every(s=>s.x===-1000&&(s.rotation===270||s.rotation===180)))
assert.equal(plan(zone(6093,17810),1400,1400,'l-desk','l-desk-row',spacing,[],10).slots.length,10)
const pair=plan(zone(6093,17810),1400,1400,'l-desk','l-desk-face-to-face',spacing,[],3)
assert.equal(pair.slots.length,4,'round up to whole opposing pairs')
for(const id of new Set(pair.slots.map(s=>s.clusterId)))assert.equal(pair.slots.filter(s=>s.clusterId===id).length,2)
const blocked=plan(zone(6093,17810),1400,1400,'l-desk','l-desk-row',spacing,[{x:-1000,y:-2000,w:6093,h:6000}])
assert.ok(blocked.slots.length>6&&blocked.slots.length<36,'blocked cells are skipped without rejecting remaining floor')
const overlap=(a,b,gap=0)=>a.x<b.x+b.w+gap-0.001&&b.x<a.x+a.w+gap-0.001&&a.y<b.y+b.h+gap-0.001&&b.y<a.y+a.h+gap-0.001
for(const preset of ['screen-t-face-to-face','screen-c-face-to-face','screen-cross-face-to-face']) {
 const size=preset==='screen-t-face-to-face'?2:4
 const result=plan(zone(12000,8000),1400,1400,'l-screen',preset,{...spacing,faceGapMm:900},[{x:3000,y:500,w:1200,h:1500}])
 assert.ok(result.slots.length>0)
 const mains=result.slots.filter(s=>!s.fill),fills=result.slots.filter(s=>s.fill)
 assert.equal(mains.length%size,0,`${preset}: main grid keeps whole cells`)
 for(const id of new Set(mains.map(s=>s.clusterId)))assert.equal(mains.filter(s=>s.clusterId===id).length,size)
 for(const id of new Set(fills.map(s=>s.clusterId)))assert.equal(fills.filter(s=>s.clusterId===id).length,1,`${preset}: fill singles stay independent`)
 for(const f of fills)assert.ok(!mains.some(m=>overlap(f,m))&&!fills.some(o=>o!==f&&overlap(f,o)),`${preset}: fills never overlap other slots`)
}
const small=plan(zone(2600,2600),1400,1400,'l-desk','l-desk-row',spacing)
assert.equal(small.slots.length,1,'small room can contain fewer than the sample six')
assert.equal(plan(zone(1200,1200),1400,1400,'l-desk','l-desk-row',spacing).slots.length,0)
const concave={...zone(10000,10000),polygon:[[-1000,-2000],[9000,-2000],[9000,1000],[2000,1000],[2000,8000],[-1000,8000]]}
assert.ok(plan(concave,1200,600,'straight-desk','straight-desk-row',{...spacing,faceGapMm:1000}).slots.length>6)
assert.equal(plan(zone(1e8,1e8),1400,1400,'l-desk','l-desk-row',spacing).slots.length,0,'bounded search')
// Two-stage fill: wall-strip singles keep the seat zone inside the room and
// respect the group gap in front of the seat.
const zoneOf=(s,clear)=>s.rotation===0?{...s,h:s.h+clear}:s.rotation===180?{...s,y:s.y-clear,h:s.h+clear}:s.rotation===90?{...s,x:s.x-clear,w:s.w+clear}:{...s,w:s.w+clear}
const strip=plan(zone(12500,8000),1400,1400,'l-screen','screen-t-face-to-face',{...spacing,faceGapMm:900})
assert.equal(strip.slots.filter(s=>!s.fill).length,30,'main grid: 5 rows × 6 columns of T pairs')
assert.equal(strip.slots.filter(s=>s.fill).length,0,'side strip fits the desk but not the seat aisle: stays empty')
assert.equal(strip.rows,5,'rows/columns describe the main grid, not the fills')
assert.equal(strip.columns,6)
const stripBlocked=plan(zone(12500,8000),1400,1400,'l-screen','screen-t-face-to-face',{...spacing,faceGapMm:900},[{x:9200,y:-2000,w:3800,h:4000}])
const sbFills=stripBlocked.slots.filter(s=>s.fill)
assert.equal(sbFills.length,6,'clear corners still take fills around the blocker')
assert.ok(sbFills.every(f=>!overlap(f,{x:9200,y:-2000,w:3800,h:4000},900)),'fills keep the obstacle aisle')
// Straight desks: the chair illustration extends beyond the footprint, so
// neither mains nor fills may let a seat zone cross the room boundary.
const wallSide=plan(zone(7556,5552),1200,600,'straight-desk','straight-desk-row',{...spacing,faceGapMm:1000})
assert.ok(wallSide.slots.length>0)
assert.ok(wallSide.slots.every(s=>{ const z=zoneOf(s,560); return z.x>=-1000-0.001&&z.x+z.w<=6556+0.001&&z.y>=-2000-0.001&&z.y+z.h<=3552+0.001 }),'seats never cross the room boundary')
// A leftover strip that only fits the desk but not the chair plus group gap
// stays empty instead of creating a 152mm dead aisle.
const aisle=plan(zone(7556,5552),1400,1400,'l-desk','l-desk-row',{...spacing,faceGapMm:600})
const aisleFills=aisle.slots.filter(s=>s.fill)
assert.ok(aisleFills.every(f=>!aisle.slots.some(m=>!m.fill&&overlap(zoneOf(f,600),m))),'fill rows keep the 600mm group gap')
const tight=plan(zone(6000,4000),1400,1400,'l-screen','screen-t-face-to-face',{...spacing,faceGapMm:900})
assert.equal(tight.slots.length,4)
assert.equal(tight.slots.filter(s=>s.fill).length,0,'seat aisle shrinks the tight-room grid instead of wall-jamming seats')
const tightTarget=plan(zone(6000,4000),1400,1400,'l-screen','screen-t-face-to-face',{...spacing,faceGapMm:900},[],5)
assert.equal(tightTarget.slots.length,4,'room too tight for the target: report shortfall instead of squeezing')
assert.equal(tightTarget.slots.filter(s=>s.fill).length,0)
// The intact motif fits nowhere: fall back to wall-side singles instead of a dead end.
const fillOnly=plan(zone(2400,2400),1400,1400,'l-screen','screen-t-face-to-face',{...spacing,faceGapMm:900})
assert.equal(fillOnly.problem,null)
assert.equal(fillOnly.slots.length,1,'fill-only starter layout for tight rooms')
assert.ok(fillOnly.slots.every(s=>s.fill))
console.log('PASS: actual screenshot room 36, dynamic rows/columns, orientation, targets, whole pairs/T/C/cross cells, retained blockers, concave/small/oversized rooms, wall-strip fills, fill top-up and fill-only fallback')
