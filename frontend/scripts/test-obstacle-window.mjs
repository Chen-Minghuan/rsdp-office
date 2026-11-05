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
const { obstacleLabel, rectangleObstacle, snapObstacleToZone, zoneLayoutExclusions, windowOnWall, windowLineObstacle, windowLinePoints, resizeWindowOnWall, moveObstacle } = await import(await load(new URL('../app/utils/spatial/obstacles.ts', import.meta.url)))
const { zoneLayoutProblem, zoneMeasurements } = await import(await load(new URL('../app/utils/spatial/review.ts', import.meta.url)))

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
  const win = windowLineObstacle('Z', { x: 3015, y: 1000 }, { x: 3015, y: 1400 })
  const { obstacle, snapped, edgeIndex } = snapObstacleToZone(zone, win, 20)
  assert.equal(snapped, true, 'window snaps to edge')
  assert.equal(edgeIndex, 1, 'right edge x=3000')
  const xs = windowLinePoints(obstacle).map(p => p[0])
  assert.deepEqual(xs, [3000, 3000], 'window centerline snapped to wall')
}

// 超出容差不吸附
{
  const win = rectangleObstacle('Z', 'WINDOW', { x: 1000, y: 1000 }, { x: 2000, y: 1400 })
  assert.equal(snapObstacleToZone(zone, win, 20).snapped, false, 'no snap beyond tolerance')
}

// 新窗沿墙线绘制，允许纯横向/纵向拖动，端点限制在真实有限墙段。
const vertical = windowOnWall(zone, { x: 5, y: 1000 }, { x: 5, y: 2400 }, 'LINE')
assert.deepEqual(windowLinePoints(vertical), [[0, 1000], [0, 2400]])
assert.equal(vertical.excludesFloorArea, false)
assert.equal(zoneLayoutProblem({ ...zone, obstacles: [vertical] }), null, '沿墙标注带满足既有保存校验')
assert.equal(zoneMeasurements({ ...zone, obstacles: [vertical] }).areaM2, zoneMeasurements(zone).areaM2, '窗线不扣面积')
assert.deepEqual(zoneLayoutExclusions({ ...zone, obstacles: [vertical] }), [], '窗线不占用家具排版区域')
const horizontal = windowOnWall(zone, { x: 500, y: 2 }, { x: 4500, y: 2 })
assert.deepEqual(windowLinePoints(horizontal), [[500, 0], [3000, 0]], '绘制超出墙端时裁到墙角')
assert.equal(windowOnWall(zone, { x: 5, y: 1000 }, { x: 5, y: 1000 }), null, '无长度不创建')
const resized = resizeWindowOnWall(zone, vertical, 2000)
assert.equal(Math.hypot(...[windowLinePoints(resized)[1][0] - windowLinePoints(resized)[0][0], windowLinePoints(resized)[1][1] - windowLinePoints(resized)[0][1]]), 2000)
assert.equal(resized.obstacleId, 'LINE', '编辑保留稳定 ID')
assert.equal(resizeWindowOnWall(zone, vertical, 5000), null, '拒绝超过所在墙长的数值')
const moved = snapObstacleToZone(zone, moveObstacle(vertical, 40, 100), Infinity).obstacle
assert.deepEqual(windowLinePoints(moved).sort((a, b) => a[1] - b[1]), [[0, 1100], [0, 2500]], '整窗移动后贴墙且保留长度')
const slope = { ...zone, polygon: [[0, 0], [3000, 0], [3000, -4000]] }
const sloped = windowOnWall(slope, { x: 600, y: 820 }, { x: 1800, y: 2400 })
const slopedLine = windowLinePoints(sloped)
for (const [x, y] of slopedLine) assert.ok(Math.abs(y - x * 4 / 3) < 0.001, '端点吸附斜墙')
assert.equal(zoneLayoutProblem({ ...slope, obstacles: [sloped] }), null)
console.log('test-obstacle-window: legacy rectangles, wall lines, finite edges, slope snapping, stable edits, net area and layout exclusions passed')
