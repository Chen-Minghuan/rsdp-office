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
const { obstacleLabel, rectangleObstacle, snapObstacleToZone, zoneLayoutExclusions } = await import(await load(new URL('../app/utils/spatial/obstacles.ts', import.meta.url)))

const zone = { id: 'Z', kind: 'zone', reviewUnit: 'mm', polygon: [[0, 0], [3000, 0], [3000, -4000], [0, -4000]], obstacles: [] }

// 标签
assert.equal(obstacleLabel('WINDOW'), '窗', 'window label')

// 窗矩形：4 顶点、不扣面积（excludesFloorArea=false）
{
  const win = rectangleObstacle('Z', 'WINDOW', { x: 1000, y: 3000 }, { x: 2200, y: 3300 })
  assert.ok(win, 'window obstacle created')
  assert.equal(win.kind, 'WINDOW')
  assert.equal(win.geometry.outer.length, 4, 'rect ring')
  assert.equal(win.excludesFloorArea, false, 'window never deducts floor area')
}

// 排版避让：窗不参与，柱参与
{
  const column = rectangleObstacle('Z', 'COLUMN', { x: 500, y: 500 }, { x: 900, y: 900 })
  const win = rectangleObstacle('Z', 'WINDOW', { x: 1000, y: 3000 }, { x: 2200, y: 3300 })
  const exclusions = zoneLayoutExclusions({ ...zone, obstacles: [column, win] })
  assert.deepEqual(exclusions.map(e => e.id), [column.obstacleId], 'window excluded from layout avoidance, column kept')
  assert.equal(exclusions[0].label, '柱')
}

// 贴墙吸附：窗与柱/井一样吸附到空间边缘（容差 20mm 内）
{
  const win = rectangleObstacle('Z', 'WINDOW', { x: 2800, y: 1000 }, { x: 3015, y: 1400 })
  const { obstacle, snapped, edgeIndex } = snapObstacleToZone(zone, win, 20)
  assert.equal(snapped, true, 'window snaps to edge')
  assert.equal(edgeIndex, 1, 'right edge x=3000')
  const xs = obstacle.geometry.outer.map(p => p[0])
  assert.equal(Math.max(...xs), 3000, 'window maxX snapped to wall')
}

// 超出容差不吸附
{
  const win = rectangleObstacle('Z', 'WINDOW', { x: 1000, y: 1000 }, { x: 2000, y: 1400 })
  assert.equal(snapObstacleToZone(zone, win, 20).snapped, false, 'no snap beyond tolerance')
}

console.log('test-obstacle-window: 7 assertions passed')
