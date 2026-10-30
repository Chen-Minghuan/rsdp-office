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
const { snapFurnitureEdges, FURNITURE_SNAP_TOLERANCE_MM } = await import(await load(new URL('../app/utils/furnitureSnap.ts', import.meta.url)))
assert.equal(FURNITURE_SNAP_TOLERANCE_MM, 50)
const zone = { id: 'ROOM', reviewUnit: 'mm', polygon: [[0,0],[6000,0],[6000,4000],[0,4000]], x: 0, y: 0, w: 6000, h: 4000 }
const other = { x: 2000, y: 1000, w: 800, h: 500 }
// Wall snap: left/right/top/bottom walls on either side of the piece.
assert.deepEqual(snapFurnitureEdges({ x: 30, y: 1200, w: 800, h: 500 }, zone, []), { x: 0, y: 1200, guideX: 0, guideY: null })
assert.deepEqual(snapFurnitureEdges({ x: 5170, y: 1200, w: 800, h: 500 }, zone, []), { x: 5200, y: 1200, guideX: 6000, guideY: null })
assert.deepEqual(snapFurnitureEdges({ x: 1200, y: 40, w: 800, h: 500 }, zone, []), { x: 1200, y: 0, guideX: null, guideY: 0 })
assert.deepEqual(snapFurnitureEdges({ x: 1200, y: 3460, w: 800, h: 500 }, zone, []), { x: 1200, y: 3500, guideX: null, guideY: 4000 })
// Beyond the tolerance nothing happens.
assert.deepEqual(snapFurnitureEdges({ x: 60, y: 1200, w: 800, h: 500 }, zone, []), { x: 60, y: 1200, guideX: null, guideY: null })
// Module snap: touching (gap 0) and edge alignment.
assert.deepEqual(snapFurnitureEdges({ x: 1170, y: 3000, w: 800, h: 500 }, zone, [other]), { x: 1200, y: 3000, guideX: 2000, guideY: null }, 'touch other left edge')
assert.deepEqual(snapFurnitureEdges({ x: 2820, y: 3000, w: 800, h: 500 }, zone, [other]), { x: 2800, y: 3000, guideX: 2800, guideY: null }, 'touch other right edge')
assert.deepEqual(snapFurnitureEdges({ x: 2030, y: 3000, w: 800, h: 500 }, zone, [other]), { x: 2000, y: 3000, guideX: 2000, guideY: null }, 'align left edges')
assert.deepEqual(snapFurnitureEdges({ x: 1100, y: 470, w: 800, h: 500 }, zone, [other]), { x: 1100, y: 500, guideX: null, guideY: 1000 }, 'touch other top edge')
assert.deepEqual(snapFurnitureEdges({ x: 1100, y: 1530, w: 800, h: 500 }, zone, [other]), { x: 1100, y: 1500, guideX: null, guideY: 1500 }, 'touch other bottom edge')
// Nearest candidate wins on each axis independently.
const both = snapFurnitureEdges({ x: 30, y: 470, w: 700, h: 500 }, zone, [other])
assert.equal(both.x, 0)
assert.equal(both.y, 500)
assert.equal(both.guideX, 0)
assert.equal(both.guideY, 1000)
// Concave walls: only edges whose span overlaps the piece capture the snap.
const concave = { ...zone, polygon: [[0,0],[6000,0],[6000,1000],[3000,1000],[3000,4000],[0,4000]] }
assert.deepEqual(snapFurnitureEdges({ x: 2970, y: 2000, w: 800, h: 500 }, concave, []), { x: 3000, y: 2000, guideX: 3000, guideY: null }, 'concave notch wall snaps when spans overlap')
assert.deepEqual(snapFurnitureEdges({ x: 4500, y: 970, w: 800, h: 500 }, concave, []), { x: 4500, y: 1000, guideX: null, guideY: 1000 }, 'overlapping notch ledge snaps like a wall')
assert.deepEqual(snapFurnitureEdges({ x: 100, y: 970, w: 800, h: 500 }, concave, []), { x: 100, y: 970, guideX: null, guideY: null }, 'notch edge does not snap pieces outside its span')
// Uncalibrated or pixel spaces never snap.
assert.deepEqual(snapFurnitureEdges({ x: 30, y: 30, w: 800, h: 500 }, { ...zone, reviewUnit: 'px' }, []), { x: 30, y: 30, guideX: null, guideY: null })
console.log('PASS: furniture snap to walls/modules, touch/align candidates, nearest wins, concave spans, tolerance and uncalibrated guards')
