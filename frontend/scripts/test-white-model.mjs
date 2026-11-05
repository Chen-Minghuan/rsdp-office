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
const workstationSeating = await moduleUrl('../app/utils/furniture3d/workstationSeating.ts', { '~/utils/manualFurniture': manualFurniture })
const white = await import(await moduleUrl('../app/utils/whiteModel.ts', { '~/utils/spatial/obstacles': obstacles, '~/utils/manualFurniture': manualFurniture, '~/utils/furniture3d/workstationSeating': workstationSeating }))
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

// 旧矩形窗派生为嵌墙窗洞，不把标注带深度当成窗厚或改写原始标注。
const legacyWindow = { obstacleId: 'WIN', kind: 'WINDOW', spaceId: 'WIN-ROOM', geometry: { outer: [[1000, 0], [2200, 0], [2200, -300], [1000, -300]] }, excludesFloorArea: false }
const legacySnapshot = JSON.stringify(legacyWindow)
const windowScene = buildWhiteModelScene({ id: 'WIN-ROOM', reviewUnit: 'mm', x: 0, y: 0, w: 3000, h: 4000, obstacles: [legacyWindow] }, [])
assert.equal(windowScene.volumes.length, 0, '窗不再生成薄片体积')
assert.equal(windowScene.windows[0].widthMm, 1200)
assert.deepEqual(windowScene.windows[0].a, [1000, 0])
assert.deepEqual(windowScene.windows[0].b, [2200, 0])
assert.deepEqual(windowScene.walls[0].openings, [{ startMm: 1000, endMm: 2200, bottomMm: 900, topMm: 2100 }])
assert.ok(windowScene.walls.every(w => w.heightMm === 2800), '有窗与无窗的墙均保持同一完整高度')
const noWindowScene = buildWhiteModelScene({ id: 'WIN-ROOM', reviewUnit: 'mm', x: 0, y: 0, w: 3000, h: 4000, obstacles: [] }, [])
assert.deepEqual(windowScene.walls.map(w => w.heightMm), noWindowScene.walls.map(w => w.heightMm), '添加窗户不改变任何墙段高度')
assert.ok(windowScene.walls[0].heightMm > windowScene.walls[0].openings[0].topMm, '窗洞保持在墙体内部')
assert.equal(JSON.stringify(legacyWindow), legacySnapshot, '投影仅用于 3D 渲染，不修改原标注')

const { windowLineObstacle } = await import(obstacles)
const win = (a, b, id) => windowLineObstacle('POLY', { x: a[0], y: -a[1] }, { x: b[0], y: -b[1] }, id)
const polyZone = { id: 'POLY', reviewUnit: 'mm', polygon: [[-2000, -1000], [4000, -1000], [4000, 3000], [0, 6000], [-2000, 3000]], obstacles: [
  win([-1000, -1000], [0, -1000], 'H1'), win([500, -1000], [1500, -1000], 'H2'),
  win([4000, 0], [4000, 1600], 'V'), win([3200, 3600], [1600, 4800], 'S'),
] }
const polySnapshot = JSON.stringify(polyZone)
const polyScene = buildWhiteModelScene(polyZone, [])
assert.ok(polyScene.walls.every(w => w.heightMm === 2800), '异形房间所有墙段等高')
assert.equal(polyScene.windows.length, 4)
assert.equal(polyScene.walls[0].openings.length, 2, '同墙两个分离窗保留中间墙垛')
assert.equal(polyScene.windows[2].wallIndex, 1, '竖墙窗匹配原墙')
assert.equal(polyScene.windows[2].widthMm, 1600)
assert.equal(polyScene.windows[3].wallIndex, 2, '斜墙窗匹配原墙')
assert.equal(polyScene.windows[3].widthMm, 2000)
assert.deepEqual(polyScene.windows[3].b, [1600, 4800])
assert.equal(JSON.stringify(polyZone), polySnapshot, '坐标含负值的房间与窗数据均不变')
const overlapScene = buildWhiteModelScene({ ...polyZone, obstacles: [win([-1000, -1000], [1000, -1000], 'O1'), win([0, -1000], [1500, -1000], 'O2')] }, [])
assert.equal(overlapScene.walls[0].openings.length, 1, '重叠标注只生成一个可三角化洞口')
assert.deepEqual(overlapScene.walls[0].openings[0], { startMm: 1000, endMm: 3500, bottomMm: 900, topMm: 2100 })

// 门洞从扇形铰链与两条径向边匹配墙，不把整块净空外接矩形当门宽。
const { doorClearanceObstacle } = await import(obstacles)
const doorZone = { id: 'DOOR-ROOM', reviewUnit: 'mm', x: 0, y: 0, w: 6000, h: 4000 }
const doorCases = [
  { hinge: [1000, 0], end: [1900, 900], closed: [1900, 0], open: [0, 1] },
  { hinge: [2400, 0], end: [1500, 900], closed: [1500, 0], open: [0, 1] },
  { hinge: [1000, 4000], end: [1900, 3100], closed: [1900, 4000], open: [0, -1] },
  { hinge: [6000, 1000], end: [5100, 1900], closed: [6000, 1900], open: [-1, 0] },
  { hinge: [0, 2200], end: [900, 1300], closed: [0, 1300], open: [1, 0] },
  { hinge: [1000, 0], end: [1900, -900], closed: [1900, 0], open: [0, -1] },
]
for (const [index, item] of doorCases.entries()) {
  const obstacle = doorClearanceObstacle(doorZone.id, { x: item.hinge[0], y: -item.hinge[1] }, { x: item.end[0], y: -item.end[1] }, `D${index}`)
  const before = JSON.stringify(obstacle)
  const doorScene = buildWhiteModelScene({ ...doorZone, obstacles: [obstacle] }, [])
  assert.equal(doorScene.doors.length, 1)
  const door = doorScene.doors[0], wall = doorScene.walls[door.wallIndex]
  assert.deepEqual(door.a, item.hinge)
  assert.deepEqual(door.b, item.closed)
  assert.equal(door.widthMm, 900)
  const normal = [(door.b[1] - door.a[1]) / door.widthMm, -(door.b[0] - door.a[0]) / door.widthMm]
  assert.ok(Math.hypot(normal[0] * door.opensTo - item.open[0], normal[1] * door.opensTo - item.open[1]) < 1e-8, '铰链左右/内外开启方向一致')
  assert.equal(wall.openings[0].bottomMm, 0, '门洞落地，无挡路门槛')
  assert.equal(wall.openings[0].topMm, 2100)
  assert.ok(doorScene.walls.every(w => w.heightMm === 2800), '门洞不改变墙高')
  assert.equal(doorScene.volumes.length, 0, '立体门不重复叠加原蓝色净空块')
  assert.equal(JSON.stringify(obstacle), before, '门洞仅派生渲染，不改净空及排版约束')
}
const freeDoor = doorClearanceObstacle(doorZone.id, { x: 2200, y: -1600 }, { x: 3100, y: -2500 }, 'FREE')
const freeDoorScene = buildWhiteModelScene({ ...doorZone, obstacles: [freeDoor] }, [])
assert.equal(freeDoorScene.doors.length, 0, '离墙净空不在远处墙面生成虚构门洞')
assert.equal(freeDoorScene.volumes[0].id, 'FREE', '未建模净空保留原标注展示')

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
assert.equal(scene.workstationSeats.length, 1, '办公桌默认配一把椅，独立椅不再配椅')
assert.equal(scene.workstationSeats[0].deskId, 'A')
assert.equal(scene.workstationSeats[0].heightMm, 850)
assert.ok(scene.workstationSeats[0].y + scene.workstationSeats[0].d / 2 <= furniture[0].h / 2 + 560, '直线桌配椅位于已有 560mm 椅位预留内')
const { workstationSeats, workstationSeatGeometry } = await import(workstationSeating)
const seatingSnapshot = JSON.stringify(furniture)
const rotatedSeat = scene.workstationSeats[0]
const seatCenter = { x: 1000 - rotatedSeat.y, y: 700 + rotatedSeat.x } // A 旋转 90°
const actualChair = { ...furniture[1], id: 'MATCH', x: seatCenter.x - 325, y: seatCenter.y - 325, rotation: 90 }
assert.equal(workstationSeats([...furniture, actualChair])[0].existingChairId, 'MATCH', '已有独立椅在旋转后的椅位，不重复补椅')
assert.equal(JSON.stringify(furniture), seatingSnapshot, '派生椅位不修改原业务家具')
const duplicates = workstationSeats([furniture[0], { ...furniture[0], id: 'A2' }, actualChair])
assert.equal(duplicates.filter(seat => seat.existingChairId).length, 1, '同一把椅不会同时配给两个工位')
assert.equal(workstationSeats([{ ...furniture[0], furnitureTemplateId: 'counter-l' }]).length, 0, '前台不自动增加办公椅')
for (const id of ['desk-manager', 'desk-executive', 'desk-screen-straight', 'desk-l', 'desk-screen-l', 'desk-manager-l', 'desk-executive-l']) {
  const desk = { ...furniture[0], furnitureTemplateId: id, w: 1600, h: id.endsWith('-l') ? 1400 : 700 }
  const normal = workstationSeats([desk])[0], mirrored = workstationSeats([{ ...desk, mirrorX: true }])[0]
  assert.ok(normal, `${id} 默认桌椅配套`)
  assert.equal(mirrored.x, -normal.x, '镜像时椅位同步镜像')
  assert.equal(mirrored.y, normal.y)
}
const lSeat = workstationSeatGeometry({ w: 1400, h: 1400, furnitureTemplateId: 'desk-l', wingWidthMm: 550 }, 'l-desk')
assert.ok(lSeat.cx + lSeat.width / 2 < 1400 - lSeat.wing && lSeat.cy - lSeat.depth / 2 > lSeat.desktopDepth, 'L 桌椅位在台面与侧翼之间的缺口内')
assert.equal(workstationSeatGeometry({ w: NaN, h: 600 }, 'desk'), null, '无效尺寸不派生配椅')
assert.equal(scene.walls.length, 4, '四面墙')
assert.ok(scene.walls.every(w => w.heightMm === 2800 && w.thicknessMm === 120))
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
