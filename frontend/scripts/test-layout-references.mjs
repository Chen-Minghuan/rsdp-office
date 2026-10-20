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

const {officeLayoutClearances: clearances,officeFurnitureFootprintBounds: footprint}=await import(await load(new URL('../app/utils/officeDimensionAnnotations.ts',import.meta.url)))
const {canvasPointerWorld,rotateCanvasVector,fitCanvasZone}=await import(await load(new URL('../app/utils/officeCanvasView.ts',import.meta.url)))
const zone={id:'R',reviewUnit:'mm',polygon:[[0,0],[8000,0],[8000,6000],[0,6000]],x:0,y:0,w:8000,h:6000}
const item=(id,x,y,w=1200,h=600,rotation=0)=>({id,kind:'furniture',zoneId:'R',productId:'',x,y,w,h,rotation,furnitureTemplateId:'desk-straight'})
const refs=clearances(zone,[item('a',300,400),item('b',2500,400),item('c',300,2000)])
for(const [side,d] of [['left',300],['right',4300],['top',400],['bottom',3400]])assert.equal(refs.find(r=>r.id===side).distanceMm,d,side)
assert.ok(refs.some(r=>r.kind==='group'&&r.axis==='x'&&r.distanceMm===1000))
assert.ok(refs.some(r=>r.kind==='group'&&r.axis==='y'&&r.distanceMm===1000))
assert.equal(clearances(zone,[item('a',300,400),item('b',1500,400)]).filter(r=>r.kind==='group').length,0,'touching desks have no aisle')
const concave={...zone,polygon:[[0,0],[8000,0],[8000,2000],[3000,2000],[3000,6000],[0,6000]]}
assert.equal(clearances(concave,[item('a',1000,3000)]).find(r=>r.id==='right').distanceMm,800,'use re-entrant polygon wall, not bounding box')
const sloped={...zone,polygon:[[0,0],[8000,0],[6000,6000],[0,6000]]}
assert.equal(clearances(sloped,[item('a',3000,3000,1000,600)]).find(r=>r.id==='right').distanceMm,2800,'slope clearance takes the nearest point along the object side')
for (const [key, value] of Object.entries({x:1300,y:1700,w:600,h:1200})) assert.ok(Math.abs(footprint(item('a',1000,2000,1200,600,90))[key]-value)<1e-6)
assert.equal(clearances(zone,[]).length,0)
assert.equal(clearances({...zone,reviewUnit:'px'},[item('a',0,0)]).length,0)
const world={minX:-15000,minY:-3000,width:24000,height:15000},viewport={width:800,height:600},camera={zoom:0.04,panX:37,panY:-26}
for(const angle of [0,90,180,270,33]){
 const p={x:-13200,y:4700}
 const offset=rotateCanvasVector({x:p.x-world.minX-world.width/2,y:p.y-world.minY-world.height/2},angle)
 const screen={x:viewport.width/2+camera.panX+offset.x*camera.zoom,y:viewport.height/2+camera.panY+offset.y*camera.zoom}
 const recovered=canvasPointerWorld(screen,viewport,world,camera,angle)
 assert.ok(Math.abs(p.x-recovered.x)<1e-6&&Math.abs(p.y-recovered.y)<1e-6,'invert rotated pointer')
 const fit=fitCanvasZone(zone,world,800,600,80,angle)
 assert.ok(fit.zoom>0&&Number.isFinite(fit.panX)&&Number.isFinite(fit.panY))
}
console.log('PASS: four wall distances, horizontal/vertical group gaps, touching desks, concave/sloped walls, rotated footprints, empty/uncalibrated spaces, camera inversion and fitting')
