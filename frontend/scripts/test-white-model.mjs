import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'

// 纯数据构建器测试：与 UI 无关，直接加载真实 TS 模块。
async function moduleUrl(path, dependencies = {}) {
  let source = stripTypeScriptTypes(await readFile(new URL(path, import.meta.url), 'utf8'))
  for (const [alias, url] of Object.entries(dependencies)) source = source.replaceAll(`'${alias}'`, `'${url}'`)
  return 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
}
const geometry = await moduleUrl('../app/utils/spatial/geometry.ts')
const uuid = await moduleUrl('../app/utils/uuid.ts')
const obstacles = await moduleUrl('../app/utils/spatial/obstacles.ts', { '~/utils/uuid': uuid, './geometry': geometry })
const manualLayoutGeometry = await moduleUrl('../app/utils/manualLayoutGeometry.ts')
const manualFurniture = await moduleUrl('../app/utils/manualFurniture.ts', { '~/utils/manualLayoutGeometry': manualLayoutGeometry })
const white = await import(await moduleUrl('../app/utils/whiteModel.ts', { '~/utils/spatial/obstacles': obstacles, '~/utils/manualFurniture': manualFurniture }))
const { buildWhiteModelScene, furnitureHeightMm, obstacleVolumeStyle, facilityKindLabel } = white

// 家具高度：模板显式高度 > 类别默认 > 750
assert.equal(furnitureHeightMm('cabinet-low'), 400)
assert.equal(furnitureHeightMm('chair-office'), 850)
assert.equal(furnitureHeightMm('desk-straight'), 750)
assert.equal(furnitureHeightMm('sofa-double'), 720)
assert.equal(furnitureHeightMm(undefined), 750)
assert.equal(furnitureHeightMm('no-such'), 750)

// 障碍体积策略
assert.deepEqual(obstacleVolumeStyle('COLUMN'), { heightMm: 2800, baseMm: 0, tone: 'column' })
assert.deepEqual(obstacleVolumeStyle('WINDOW'), { heightMm: 1200, baseMm: 900, tone: 'window' })
assert.equal(obstacleVolumeStyle('DOOR_CLEARANCE').heightMm, 20)
assert.equal(facilityKindLabel.POWER_OUTLET, '电源插座')

const zone = {
  id: 'Z1', kind: 'zone', name: '开放办公区', x: 0, y: 0, w: 6000, h: 4000,
  polygon: [[0, 0], [6000, 0], [6000, 4000], [0, 4000]], reviewUnit: 'mm',
  obstacles: [
    { obstacleId: 'OB1', kind: 'COLUMN', spaceId: 'Z1', geometry: { outer: [[1000, -1000], [1400, -1000], [1400, -600], [1000, -600]] }, excludesFloorArea: true },
    { obstacleId: 'OB2', kind: 'NO_PLACEMENT', spaceId: 'Z1', geometry: { outer: [[2000, -800], [3000, -800], [3000, -1200], [2000, -1200]] }, excludesFloorArea: false },
  ],
  facilities: [
    { facilityId: 'F1', kind: 'POWER_OUTLET', spaceId: 'Z1', shape: 'point', pointsMm: [[500, -3500]], visible: true },
    { facilityId: 'F2', kind: 'FIRE_FACILITY', spaceId: 'Z1', name: '灭火器箱', shape: 'polygon', pointsMm: [[5500, -500], [5900, -500], [5900, -900], [5500, -900]], sizeMm: { widthMm: 400, depthMm: 400 }, clearanceMm: 1000, visible: true },
    { facilityId: 'F3', kind: 'DATA_OUTLET', spaceId: 'Z1', shape: 'point', pointsMm: [[700, -3500]], visible: false },
  ],
  layoutRegions: [
    { regionId: 'R1', spaceId: 'Z1', name: '主布置区', polygonMm: [[100, -100], [5900, -100], [5900, -3900], [100, -3900]], isPrimary: true, visible: true, enabled: true },
    { regionId: 'R2', spaceId: 'Z1', name: '停用区', polygonMm: [[0, 0], [1, 0], [1, 1]], isPrimary: false, visible: true, enabled: false },
  ],
}
const furniture = [
  { id: 'A', kind: 'furniture', zoneId: 'Z1', furnitureTemplateId: 'desk-straight', label: '职员桌 A', x: 400, y: 400, w: 1200, h: 600, rotation: 90 },
  { id: 'B', kind: 'furniture', zoneId: 'Z1', furnitureTemplateId: 'chair-office', label: '椅子 B', x: 2000, y: 2000, w: 650, h: 650, rotation: 0 },
]

const scene = buildWhiteModelScene(zone, furniture)
assert.equal(scene.walls.length, 4, '四面墙')
assert.ok(scene.walls.every(w => w.heightMm === 1500 && w.thicknessMm === 120))
assert.deepEqual(scene.bounds, { minX: 0, minY: 0, maxX: 6000, maxY: 4000 })

const desks = scene.volumes.filter(v => v.source === 'furniture')
assert.equal(desks.length, 2)
assert.deepEqual(desks[0], { id: 'A', source: 'furniture', label: '职员桌 A', cx: 1000, cy: 700, w: 1200, d: 600, heightMm: 750, baseMm: 0, rotDeg: 90, tone: 'furniture' })
assert.equal(desks[1].heightMm, 850)

// 障碍：世界毫米 Y 向上 → 画布取反
const column = scene.volumes.find(v => v.id === 'OB1')
assert.equal(column.tone, 'column')
assert.equal(column.cx, 1200)
assert.equal(column.cy, 800, 'Y-up 世界坐标应翻转为画布坐标')
assert.deepEqual(column.ring[0], [1000, 1000])
const noPlacement = scene.volumes.find(v => v.id === 'OB2')
assert.equal(noPlacement.tone, 'noPlacement')
assert.equal(noPlacement.heightMm, 2000)

// 设施：同样翻转 Y；隐藏项不进入场景
assert.equal(scene.facilities.length, 2, 'visible=false 的点位不渲染')
assert.deepEqual(scene.facilities[0].points[0], [500, 3500])
assert.equal(scene.facilities[1].clearanceMm, 1000)
assert.equal(scene.facilities[1].label, '灭火器箱')

// 布置区：仅启用的参与，同样翻转
assert.equal(scene.regions.length, 1)
assert.deepEqual(scene.regions[0][0], [100, 100])

// 未校准空间（source 单位）：不翻转
const sourceZone = { ...zone, reviewUnit: 'source', obstacles: [zone.obstacles[0]], facilities: [], layoutRegions: [] }
const sourceScene = buildWhiteModelScene(sourceZone, [])
assert.deepEqual(sourceScene.volumes[0].ring[0], [1000, -1000], 'source 单位保持原值')

// 无多边形时退化为外接矩形
const noPoly = buildWhiteModelScene({ id: 'Z2', kind: 'zone', name: 'x', x: 10, y: 20, w: 3000, h: 2000, reviewUnit: 'mm' }, [])
assert.deepEqual(noPoly.floor, [[10, 20], [3010, 20], [3010, 2020], [10, 2020]])

console.log('PASS: 白模场景构建（墙体/家具高度/障碍翻转/设施过滤/布置区/退化兜底）')
