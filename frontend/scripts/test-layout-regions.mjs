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
const regions = await import(await load(new URL('../app/utils/spatial/layoutRegions.ts', import.meta.url)))
const {
  layoutRegionRing, layoutRegionWorldRing, activeLayoutRegionsForZone, allowedPlacementAreas,
  ringWithinPlacementAreas, layoutRegionProblem, layoutRegionEdgeMeasurements,
  sanitizeLayoutRegions, setPrimaryLayoutRegion, removeLayoutRegion,
  moveLayoutRegion, moveLayoutRegionVertex, nextLayoutRegionName, pointInRing,
} = regions

// 画布坐标（毫米场景 Y 向下）矩形环。
const rect = (x, y, w, h) => [[x, y], [x + w, y], [x + w, y + h], [x, y + h]]
// LayoutRegion.polygonMm 为 WORLD_MM（Y 向上）：画布环 y 取反。
const world = (ring) => ring.map(([x, y]) => [x, -y])
const makeZone = (polygon, extra = {}) => ({ id: 'ROOM-1', kind: 'zone', name: '测试空间', reviewUnit: 'mm', polygon, obstacles: [], ...extra })
const makeRegion = (canvasRing, extra = {}) => ({
  regionId: extra.regionId || 'R1', spaceId: 'ROOM-1', name: extra.name || '布置区',
  polygonMm: world(canvasRing), isPrimary: !!extra.isPrimary, visible: extra.visible !== false, enabled: extra.enabled !== false,
  ...extra,
})
const areaOf = (ring) => Math.abs(ring.reduce((sum, p, i) => { const q = ring[(i + 1) % ring.length]; return sum + p[0] * q[1] - q[0] * p[1] }, 0)) / 2
const totalArea = (areas) => areas.reduce((sum, a) => sum + areaOf(a.outer) - a.holes.reduce((s, h) => s + areaOf(h), 0), 0)

// 9000×6000 房间（画布坐标原点左上）。
const room = rect(0, 0, 9000, 6000)

// 1. 无布置区 → null（维持旧的全房间行为）；有区但全部停用 → 同样 null。
{
  const zone = makeZone(room)
  assert.equal(allowedPlacementAreas(zone), null, 'no regions → unconstrained')
  assert.equal(ringWithinPlacementAreas(null, rect(-999, -999, 100, 100)), true, 'null areas allow anything')
  zone.layoutRegions = [makeRegion(rect(1000, 1000, 3000, 2000), { enabled: false })]
  assert.equal(allowedPlacementAreas(zone), null, 'all disabled → unconstrained')
  assert.equal(activeLayoutRegionsForZone(zone).length, 0)
}

// 2. 单个矩形区：有效区等于该区；visible=false 不影响计算。
{
  const zone = makeZone(room, { layoutRegions: [makeRegion(rect(1000, 1000, 3000, 2000), { visible: false })] })
  const areas = allowedPlacementAreas(zone)
  assert.equal(areas.length, 1)
  assert.equal(totalArea(areas), 3000 * 2000, 'region area preserved')
  assert.ok(ringWithinPlacementAreas(areas, rect(1100, 1100, 800, 600)), 'furniture inside region allowed')
  assert.ok(!ringWithinPlacementAreas(areas, rect(500, 1100, 800, 600)), 'furniture crossing region edge rejected')
  assert.ok(!ringWithinPlacementAreas(areas, rect(5000, 100, 800, 600)), 'furniture outside region rejected')
}

// 3. 两个不相交区 → 两块有效区，不得合并为矩形包围盒（凹角/负坐标一并覆盖）。
{
  const zone = makeZone(room, {
    layoutRegions: [
      makeRegion(rect(500, 500, 2000, 1500), { regionId: 'RA' }),
      makeRegion(rect(6000, 3000, 2000, 1500), { regionId: 'RB' }),
    ],
  })
  const areas = allowedPlacementAreas(zone)
  assert.equal(areas.length, 2, 'disjoint regions stay disjoint (no bounding-box merge)')
  assert.equal(totalArea(areas), 2 * 2000 * 1500)
  assert.ok(ringWithinPlacementAreas(areas, rect(600, 600, 500, 500)), 'in A allowed')
  assert.ok(ringWithinPlacementAreas(areas, rect(6100, 3100, 500, 500)), 'in B allowed')
  assert.ok(!ringWithinPlacementAreas(areas, rect(4000, 2000, 500, 500)), 'between A and B rejected (bbox would wrongly allow)')
}

// 4. 交叠区并集为一块（L 形）；并集 ∩ 空间仍精确。
{
  const zone = makeZone(room, {
    layoutRegions: [
      makeRegion(rect(1000, 1000, 3000, 2000), { regionId: 'RA' }),
      makeRegion(rect(2000, 2000, 3000, 2000), { regionId: 'RB' }),
    ],
  })
  const areas = allowedPlacementAreas(zone)
  assert.equal(areas.length, 1, 'overlapping regions union into one area')
  assert.equal(totalArea(areas), 3000 * 2000 + 3000 * 2000 - 2000 * 1000, 'union area exact')
}

// 5. 凹多边形（L 形）区：有效区严格遵从多边形，缺口处不可排。
{
  const lShape = [[1000, 1000], [4000, 1000], [4000, 2500], [2500, 2500], [2500, 4000], [1000, 4000]]
  const zone = makeZone(room, { layoutRegions: [makeRegion(lShape)] })
  const areas = allowedPlacementAreas(zone)
  assert.equal(areas.length, 1)
  assert.equal(areas[0].outer.length, 6, 'concave ring preserved, not bbox')
  assert.ok(ringWithinPlacementAreas(areas, rect(1100, 1100, 800, 600)), 'in upper bar allowed')
  assert.ok(!ringWithinPlacementAreas(areas, rect(2600, 2600, 800, 600)), 'in concave notch rejected (bbox would allow)')
}

// 6. 负世界坐标：房间整体在负坐标象限，区域换算一致。
{
  const negRoom = rect(-6000, -4500, 4000, 3000)
  const zone = makeZone(negRoom, { layoutRegions: [makeRegion(rect(-5500, -4000, 2000, 1500))] })
  const areas = allowedPlacementAreas(zone)
  assert.equal(areas.length, 1)
  assert.equal(totalArea(areas), 2000 * 1500)
  assert.ok(ringWithinPlacementAreas(areas, rect(-5400, -3900, 500, 500)))
  assert.ok(!ringWithinPlacementAreas(areas, rect(-1000, -1000, 500, 500)))
}

// 7. 区部分越出空间：有效区被空间轮廓裁剪；layoutRegionProblem 标记越界。
{
  const zone = makeZone(room, { layoutRegions: [makeRegion(rect(8000, 4000, 3000, 3000))] })
  const areas = allowedPlacementAreas(zone)
  assert.equal(totalArea(areas), 1000 * 2000, 'clipped to space outline')
  assert.match(layoutRegionProblem(zone, zone.layoutRegions[0]), /超出空间净轮廓/)
}

// 8. 启用区与空间完全无交集 → 空数组（什么都不可排）。
{
  const zone = makeZone(room, { layoutRegions: [makeRegion(rect(20000, 20000, 1000, 1000))] })
  const areas = allowedPlacementAreas(zone)
  assert.deepEqual(areas, [])
  assert.ok(!ringWithinPlacementAreas(areas, rect(100, 100, 500, 500)))
}

// 9. 自交/退化区：problem 非空；且不进入有效区计算。
{
  const bowtie = [[1000, 1000], [3000, 3000], [3000, 1000], [1000, 3000]]
  const zone = makeZone(room, { layoutRegions: [makeRegion(bowtie), makeRegion(rect(500, 500, 1000, 1000), { regionId: 'OK' })] })
  assert.match(layoutRegionProblem(zone, zone.layoutRegions[0]), /自交/)
  assert.match(layoutRegionProblem(zone, { ...makeRegion([[0, 0], [1, 1]]), polygonMm: [[0, 0], [1, 1]] }), /缺少可核对轮廓|面积过小|顶点/)
  const areas = allowedPlacementAreas(zone)
  assert.equal(areas.length, 1, 'malformed region skipped, valid one kept')
  assert.equal(totalArea(areas), 1000 * 1000)
}

// 10. 边尺寸与距墙量测：3000×2000 区在 (1000,1000)，四边长度与到 9000×6000 房间各墙距离。
{
  const zone = makeZone(room, { layoutRegions: [makeRegion(rect(1000, 1000, 3000, 2000))] })
  const measurements = layoutRegionEdgeMeasurements(zone, zone.layoutRegions[0])
  assert.equal(measurements.length, 4)
  assert.deepEqual(measurements.map(m => Math.round(m.lengthMm)), [3000, 2000, 3000, 2000])
  assert.deepEqual(measurements.map(m => Math.round(m.wallDistanceMm)), [1000, 5000, 3000, 1000], 'top/right/bottom/left wall distances')
}

// 11. sanitize：旧数据缺字段 → []；畸形项跳过；重复 ID 去重；多个主区只留第一个；严禁自动创建。
{
  assert.deepEqual(sanitizeLayoutRegions('ROOM-1', undefined), [])
  assert.deepEqual(sanitizeLayoutRegions('ROOM-1', 'garbage'), [])
  assert.deepEqual(sanitizeLayoutRegions('ROOM-1', [{ noId: true }]), [])
  const raw = [
    { regionId: 'A', polygonMm: world(rect(0, 0, 100, 100)), isPrimary: true },
    { regionId: 'A', polygonMm: world(rect(0, 0, 200, 200)) },
    { regionId: 'B', polygonMm: world(rect(0, 0, 300, 300)), isPrimary: true, visible: false, enabled: false, name: 'B 区' },
  ]
  const cleaned = sanitizeLayoutRegions('ROOM-1', raw)
  assert.equal(cleaned.length, 2, 'duplicate id dropped')
  assert.equal(cleaned.filter(r => r.isPrimary).length, 1, 'single primary enforced')
  assert.equal(cleaned[1].visible, false)
  assert.equal(cleaned[1].enabled, false)
  assert.equal(cleaned[1].name, 'B 区')
}

// 12. 设主/删除规则：删主区 → 数组序第一个剩余区升主；删光 → 空。
{
  let list = [makeRegion(rect(0, 0, 10, 10), { regionId: 'A' }), makeRegion(rect(0, 0, 10, 10), { regionId: 'B', isPrimary: true }), makeRegion(rect(0, 0, 10, 10), { regionId: 'C' })]
  list = setPrimaryLayoutRegion(list, 'C')
  assert.deepEqual(list.map(r => r.isPrimary), [false, false, true])
  list = removeLayoutRegion(list, 'C')
  assert.deepEqual(list.map(r => r.regionId), ['A', 'B'])
  assert.deepEqual(list.map(r => r.isPrimary), [true, false], 'first remaining promoted')
  assert.deepEqual(removeLayoutRegion(list, 'A').map(r => r.isPrimary), [true], 'B promoted when A removed')
  assert.deepEqual(removeLayoutRegion(removeLayoutRegion(list, 'A'), 'B'), [])
  assert.equal(removeLayoutRegion(list, 'MISSING'), list, 'unknown id is a no-op')
}

// 13. 平移/顶点编辑返回新对象并清掉旧量测；坐标往返换算一致。
{
  const region = makeRegion(rect(1000, 1000, 3000, 2000), { wallOffsetsMm: [{ edgeIndex: 0, distanceMm: 1000 }] })
  const moved = moveLayoutRegion(region, 500, -500)
  assert.deepEqual(moved.polygonMm[0], [1000 + 500, -1000 - 500], 'WORLD_MM delta applies directly (Y up)')
  assert.equal(moved.wallOffsetsMm, undefined, 'stale measurements cleared')
  assert.notEqual(moved, region)
  const edited = moveLayoutRegionVertex(region, 1, 4500, -1000)
  assert.deepEqual(edited.polygonMm[1], [4500, -1000])
  const zone = makeZone(room)
  const roundTrip = layoutRegionWorldRing(zone, layoutRegionRing(zone, region))
  assert.deepEqual(roundTrip, region.polygonMm, 'canvas ↔ world ring round-trip')
}

// 14. 命名递增与点包含。
{
  assert.equal(nextLayoutRegionName([]), '布置区 1')
  assert.equal(nextLayoutRegionName([{ name: '布置区 1' }]), '布置区 2')
  assert.ok(pointInRing(room, 4500, 3000))
  assert.ok(!pointInRing(room, 9500, 3000))
}

console.log('layout-regions: 14 groups of assertions passed')
