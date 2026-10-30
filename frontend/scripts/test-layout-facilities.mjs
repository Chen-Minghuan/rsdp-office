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
const facilities = await import(await load(new URL('../app/utils/spatial/facilities.ts', import.meta.url)))
const { facilityLabel, facilityShape, facilityFootprintRing, facilityClearanceRing, facilityProblem, facilityLayoutHints, sanitizeFacilities, FACILITY_POINT_MARKER_MM } = facilities
const { zoneMeasurements } = await import(await load(new URL('../app/utils/spatial/review.ts', import.meta.url)))

const rect = (x, y, w, h) => [[x, y], [x + w, y], [x + w, y + h], [x, y + h]]
const world = (ring) => ring.map(([x, y]) => [x, -y])
const makeZone = (polygon, extra = {}) => ({ id: 'ROOM-1', kind: 'zone', name: '测试空间', reviewUnit: 'mm', polygon, obstacles: [], ...extra })
const makeFacility = (kind, canvasPoints, extra = {}) => ({
  facilityId: extra.facilityId || 'F1', kind, spaceId: 'ROOM-1',
  shape: facilityShape(kind), pointsMm: canvasPoints.map(([x, y]) => [x, -y]),
  visible: extra.visible !== false, source: 'MANUAL', ...extra,
})
const room = rect(0, 0, 9000, 6000)

// 1. 类型语义：点位型/线型/占用型分类与标签。
{
  assert.equal(facilityShape('POWER_OUTLET'), 'point')
  assert.equal(facilityShape('DATA_OUTLET'), 'point')
  assert.equal(facilityShape('SKIRTING'), 'polyline')
  assert.equal(facilityShape('FIRE_FACILITY'), 'polygon')
  assert.equal(facilityShape('ELECTRICAL_BOX'), 'polygon')
  assert.equal(facilityLabel('FIRE_FACILITY'), '消防设施')
  assert.equal(facilityLabel('DATA_OUTLET'), '弱电点位')
}

// 2. 点位占地：默认渲染标记尺寸；sizeMm 自定义；线型无占地。
{
  const zone = makeZone(room)
  const outlet = makeFacility('POWER_OUTLET', [[1000, 1000]])
  const footprint = facilityFootprintRing(zone, outlet)
  assert.equal(footprint.length, 4)
  const xs = footprint.map(p => p[0])
  assert.equal(Math.max(...xs) - Math.min(...xs), FACILITY_POINT_MARKER_MM, 'default marker size')
  const sized = makeFacility('POWER_OUTLET', [[1000, 1000]], { sizeMm: { widthMm: 300, depthMm: 120 } })
  const sizedFootprint = facilityFootprintRing(zone, sized)
  assert.equal(Math.max(...sizedFootprint.map(p => p[0])) - Math.min(...sizedFootprint.map(p => p[0])), 300)
  const skirting = makeFacility('SKIRTING', [[0, 100], [5000, 100]])
  assert.equal(facilityFootprintRing(zone, skirting), null, 'polyline has no footprint')
}

// 3. 操作净空：仅占用型 + 人工 clearanceMm 生效；包围盒外扩；缺省无净空环。
{
  const zone = makeZone(room)
  const fire = makeFacility('FIRE_FACILITY', rect(1000, 1000, 800, 600))
  assert.equal(facilityClearanceRing(zone, fire), null, 'no clearance by default (never fabricate norms)')
  const withClearance = makeFacility('FIRE_FACILITY', rect(1000, 1000, 800, 600), { clearanceMm: 900 })
  const ring = facilityClearanceRing(zone, withClearance)
  const xs = ring.map(p => p[0]), ys = ring.map(p => p[1])
  assert.deepEqual([Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)], [100, 100, 2700, 2500], 'bbox expanded by clearance')
}

// 4. 校验：点数/形状不符、自交、出空间范围。
{
  const zone = makeZone(room)
  assert.match(facilityProblem(zone, makeFacility('POWER_OUTLET', [[1, 1], [2, 2]])), /恰好一个点位/)
  assert.match(facilityProblem(zone, makeFacility('SKIRTING', [[1, 1]])), /至少需要两个点/)
  const bowtie = makeFacility('FIRE_FACILITY', [[1000, 1000], [3000, 3000], [3000, 1000], [1000, 3000]])
  assert.match(facilityProblem(zone, bowtie), /自交/)
  assert.match(facilityProblem(zone, makeFacility('DATA_OUTLET', [[20000, 20000]])), /不在所属空间范围内/)
  assert.equal(facilityProblem(zone, makeFacility('FIRE_FACILITY', rect(1000, 1000, 800, 600))), null)
  assert.equal(facilityProblem(zone, makeFacility('SKIRTING', [[0, 100], [5000, 100]])), null)
}

// 5. 排版提示（全部提示级）：占用型压占提示、未标注净空注明「仅提示」、点位遮挡提示、线型不逐件提示、隐藏设施不提示。
{
  const zone = makeZone(room, {
    facilities: [
      makeFacility('FIRE_FACILITY', rect(1000, 1000, 800, 600), { facilityId: 'FIRE', name: '消防箱' }),
      makeFacility('ELECTRICAL_BOX', rect(5000, 1000, 500, 400), { facilityId: 'BOX', clearanceMm: 800 }),
      makeFacility('POWER_OUTLET', [[7000, 1000]], { facilityId: 'PWR', sizeMm: { widthMm: 200, depthMm: 200 } }),
      makeFacility('SKIRTING', [[0, 5900], [9000, 5900]], { facilityId: 'SKIRT' }),
      makeFacility('DATA_OUTLET', [[8000, 5000]], { facilityId: 'HIDDEN', visible: false }),
    ],
  })
  const targets = [
    { id: 'T1', label: '职员桌', ring: rect(900, 900, 1200, 600) },
    { id: 'T2', label: '书柜', ring: rect(4500, 900, 900, 400) },
    { id: 'T3', label: '沙发', ring: rect(6900, 900, 1500, 700) },
    { id: 'T4', label: '茶几', ring: rect(7800, 4800, 800, 500) },
    { id: 'T5', label: '隔断', ring: rect(100, 5600, 800, 300) },
  ]
  const hints = facilityLayoutHints(zone, targets)
  const fire = hints.find(h => h.facilityId === 'FIRE' && h.targetId === 'T1')
  assert.ok(fire, 'furniture over fire facility flagged')
  assert.match(fire.message, /仅提示/, 'un-annotated clearance says hint-only')
  assert.match(fire.message, /消防箱/)
  const box = hints.find(h => h.facilityId === 'BOX' && h.targetId === 'T2')
  assert.ok(box, 'furniture within manual clearance flagged')
  assert.match(box.message, /800mm/, 'manual clearance value quoted')
  const pwr = hints.find(h => h.facilityId === 'PWR' && h.targetId === 'T3')
  assert.ok(pwr, 'furniture covering outlet flagged')
  assert.match(pwr.message, /遮挡/)
  assert.ok(!hints.some(h => h.facilityId === 'SKIRT'), 'skirting never produces per-item hints')
  assert.ok(!hints.some(h => h.facilityId === 'HIDDEN'), 'hidden facility produces no hints')
  assert.ok(!hints.some(h => h.targetId === 'T4' && h.facilityId !== 'HIDDEN'), 'unrelated furniture not flagged')
}

// 6. 净面积不受影响：设施不算 excludesFloorArea 障碍，zoneMeasurements 口径不变。
{
  const zone = makeZone(room, { facilities: [makeFacility('FIRE_FACILITY', rect(1000, 1000, 800, 600))] })
  const measurements = zoneMeasurements(zone)
  assert.equal(measurements.area, measurements.boundaryArea, 'facilities never deduct floor area')
}

// 7. sanitize：旧数据缺字段 → []；未知 kind/形状点数不符跳过；clearanceMm 非正数清除；source 白名单。
{
  assert.deepEqual(sanitizeFacilities('ROOM-1', undefined), [])
  assert.deepEqual(sanitizeFacilities('ROOM-1', [{ facilityId: 'X', kind: 'COLUMN', pointsMm: [[0, 0]] }]), [])
  assert.deepEqual(sanitizeFacilities('ROOM-1', [{ facilityId: 'X', kind: 'POWER_OUTLET', pointsMm: [[0, 0], [1, 1]] }]), [])
  const cleaned = sanitizeFacilities('ROOM-1', [
    { facilityId: 'A', kind: 'FIRE_FACILITY', pointsMm: world(rect(0, 0, 100, 100)), clearanceMm: -5, source: 'HACKED', name: '消火栓' },
  ])
  assert.equal(cleaned.length, 1)
  assert.equal(cleaned[0].clearanceMm, undefined, 'non-positive clearance dropped')
  assert.equal(cleaned[0].source, 'MANUAL', 'unknown source falls back')
  assert.equal(cleaned[0].name, '消火栓')
  assert.equal(cleaned[0].shape, 'polygon', 'shape derived from kind')
}

console.log('layout-facilities: 7 groups of assertions passed')
