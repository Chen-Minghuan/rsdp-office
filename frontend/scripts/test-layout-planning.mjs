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
const { planLayoutProposal: plan, defaultLayoutPreferences: defaults, proposalDifference, confirmedLayoutProblem, chairActivityRect } = await import(await load(new URL('../app/utils/layoutPlanning.ts', import.meta.url)))
const { furnitureById } = await import(await load(new URL('../app/utils/manualFurniture.ts', import.meta.url)))
const { rectangleObstacle, zoneLayoutExclusions, migrateLegacyDoorClearances, migrateLayoutExclusions, moveObstacle, reshapeObstacle, snapObstacleToZone } = await import(await load(new URL('../app/utils/spatial/obstacles.ts', import.meta.url)))
const { rectOverlapsBlocker } = await import(await load(new URL('../app/utils/layoutConstraints.ts', import.meta.url)))
const { zoneMeasurements, reviewScene, zoneLayoutProblem } = await import(await load(new URL('../app/utils/spatial/review.ts', import.meta.url)))
const { captureSpaceTemplate, zonesFromTemplate } = await import(await load(new URL('../app/utils/spatial/spaceTemplate.ts', import.meta.url)))
const zone = { id: 'ROOM', zoneType: 'MEETING_ROOM', reviewUnit: 'mm', x: -12000, y: -8000, w: 12000, h: 8000, polygon: [[-12000,-8000],[0,-8000],[0,0],[-12000,0]] }
const request = { module: furnitureById.get('table-conference-rect'), widthMm: 2800, depthMm: 1200, spacing: { wallMm: 300, aisleMm: 900, gapMm: 0 }, productStyleId: 'meeting-table', presetId: 'meeting-center', preferences: { ...defaults(), targetSeats: 8 } }
const before = JSON.stringify([zone, request])
const column = rectangleObstacle(zone.id, 'COLUMN', { x: -6200, y: 3800 }, { x: -5800, y: 4200 }, 'COL-1')
const movedColumn = moveObstacle(column, 100, 200)
assert.deepEqual(movedColumn.geometry.outer[0], [column.geometry.outer[0][0] + 100, column.geometry.outer[0][1] + 200])
assert.equal(movedColumn.obstacleId, column.obstacleId)
const reshapedColumn = reshapeObstacle(column, '2', { x: -5600, y: 4400 })
assert.deepEqual(reshapedColumn.geometry.outer[2], [-5600, 4400])
assert.deepEqual(reshapedColumn.geometry.outer[0], column.geometry.outer[0])
const doorClearance = rectangleObstacle(zone.id, 'DOOR_CLEARANCE', { x: -1000, y: 0 }, { x: 0, y: 1000 }, 'DOOR-1')
const movedDoor = moveObstacle(doorClearance, 200, 300)
assert.deepEqual(movedDoor.geometry.outer[0], [-800, 300])
const reshapedDoor = reshapeObstacle(doorClearance, 'radius', { x: -500, y: 0 })
assert.equal(reshapedDoor.geometry.outer[1][0] - reshapedDoor.geometry.outer[0][0], 500)
assert.equal(reshapedDoor.obstacleId, doorClearance.obstacleId)
const snapZone = { id: 'SNAP', reviewUnit: 'mm', polygon: [[0,0],[6000,0],[6000,7200],[0,7200]] }
const nearLeft = rectangleObstacle('SNAP', 'COLUMN', { x: 12, y: -2000 }, { x: 412, y: -1600 }, 'SNAP-LEFT')
const leftSnap = snapObstacleToZone(snapZone, nearLeft, 20)
assert.equal(leftSnap.snapped, true)
assert.equal(leftSnap.obstacle.geometry.outer[0][0], 0)
assert.equal(leftSnap.obstacle.geometry.outer[1][0], 400)
assert.equal(leftSnap.obstacle.obstacleId, nearLeft.obstacleId)
const nearBottom = rectangleObstacle('SNAP', 'COLUMN', { x: 1000, y: -7212 }, { x: 1400, y: -6800 }, 'SNAP-BOTTOM')
assert.equal(snapObstacleToZone(snapZone, nearBottom, 20).obstacle.geometry.outer[0][1], -7200)
const outsideTolerance = rectangleObstacle('SNAP', 'COLUMN', { x: 30, y: -2000 }, { x: 430, y: -1600 }, 'SNAP-FAR')
assert.equal(snapObstacleToZone(snapZone, outsideTolerance, 20).snapped, false)
const doorNearWall = rectangleObstacle('SNAP', 'DOOR_CLEARANCE', { x: 0, y: -2000 }, { x: 800, y: -2800 }, 'SNAP-DOOR')
assert.equal(snapObstacleToZone(snapZone, doorNearWall, 20).snapped, false)
assert.ok(doorClearance.geometry.outer.length > 4, 'door clearance uses a quarter-sector arc')
const legacyDoor = { ...doorClearance, geometry: { ...doorClearance.geometry, outer: [[-1000,0],[0,0],[0,1000],[-1000,1000]] } }
const legacyZone = { ...zone, kind: 'zone', obstacles: [legacyDoor] }
assert.deepEqual(migrateLegacyDoorClearances([legacyZone]), [zone.id])
assert.ok(legacyZone.obstacles[0].geometry.outer.length > 4, 'legacy rectangular door is upgraded in place')
assert.deepEqual(migrateLegacyDoorClearances([legacyZone]), [])
// GEOM-013：旧排版预留区域迁移为 NO_PLACEMENT 建筑约束；y 轴换算、命名保留、幂等。
const legacyExclusionZone = { ...zone, kind: 'zone', obstacles: [], layoutPreferences: { ...defaults(), exclusions: [{ id: 'EX-1', label: '正门入口', x: -12000, y: -8000, w: 1000, h: 900 }] } }
assert.deepEqual(migrateLayoutExclusions([legacyExclusionZone]), [zone.id])
assert.equal('exclusions' in legacyExclusionZone.layoutPreferences, false)
assert.equal(legacyExclusionZone.obstacles.length, 1)
const migratedExclusion = legacyExclusionZone.obstacles[0]
assert.equal(migratedExclusion.kind, 'NO_PLACEMENT')
assert.equal(migratedExclusion.obstacleId, 'EX-1')
assert.equal(migratedExclusion.label, '正门入口')
assert.equal(migratedExclusion.excludesFloorArea, false)
const migratedArea = zoneLayoutExclusions(legacyExclusionZone)[0]
assert.deepEqual([migratedArea.x, migratedArea.y, migratedArea.w, migratedArea.h], [-12000, -8000, 1000, 900], 'canvas rect survives the y-flip round trip')
assert.equal(migratedArea.label, '正门入口')
assert.deepEqual(migrateLayoutExclusions([legacyExclusionZone]), [], 'migration is idempotent')
const emptyExclusionZone = { ...zone, kind: 'zone', obstacles: [], layoutPreferences: { ...defaults(), exclusions: [] } }
assert.deepEqual(migrateLayoutExclusions([emptyExclusionZone]), [], 'empty key cleanup is not a geometry change')
assert.equal('exclusions' in emptyExclusionZone.layoutPreferences, false)
const doorExclusion = zoneLayoutExclusions({ ...zone, obstacles: [doorClearance] }, false)[0]
assert.equal(rectOverlapsBlocker({ x: -200, y: -900, w: 50, h: 50 }, doorExclusion), false, 'bounding box outside the arc is not blocked')
assert.equal(rectOverlapsBlocker({ x: -900, y: -100, w: 50, h: 50 }, doorExclusion), true, 'furniture inside the arc is blocked')
const resizedDoor = rectangleObstacle('ROOM-001', 'DOOR_CLEARANCE', { x: 4107.767325020249, y: -4923.767059260259 }, { x: 4607.767325020249, y: -5423.767059260259 }, 'DOOR-RESIZED')
assert.equal(zoneLayoutProblem({ id: 'ROOM-001', reviewUnit: 'mm', polygon: [[0,0],[6000,0],[6000,7200],[0,7200]], obstacles: [resizedDoor] }), null)
const constrainedZone = { ...zone, obstacles: [column, doorClearance] }
assert.equal(zoneMeasurements(zone).area, 96)
assert.equal(zoneMeasurements(constrainedZone).area, 95.84)
assert.equal(reviewScene([constrainedZone], null).obstacles.length, 2)
const constrainedTemplate = captureSpaceTemplate([constrainedZone], null, 2, '约束测试')
assert.equal(zonesFromTemplate(constrainedTemplate)[0].obstacles.length, 2)
assert.ok(plan(constrainedZone, [], request).problem, 'meeting table must avoid the reviewed column')
const meeting = plan(zone, [], request)
assert.equal(meeting.problem, null)
assert.equal(meeting.seats, 8)
assert.equal(meeting.furniture.length, 9)
assert.equal(meeting.shortfall, 0)
assert.equal(JSON.stringify([zone, request]), before, 'preview is pure')
assert.equal(confirmedLayoutProblem({ ...zone, layoutPreferences: request.preferences }, meeting.furniture), null)
const chair = meeting.furniture.find(item => item.layoutRole === 'seat')
const activity = chairActivityRect(chair, 400)
const obstruction = { ...cabinetForTest(), ...activity, id: 'IN-ACTIVITY', zoneId: zone.id, rotation: 0 }
assert.match(confirmedLayoutProblem({ ...zone, layoutPreferences: request.preferences }, [...meeting.furniture, obstruction]), /活动区/)
function cabinetForTest() { return { kind: 'furniture', furnitureTemplateId: 'cabinet-mobile', productId: '' } }
assert.equal(plan(zone, [], { ...request, module: furnitureById.get('desk-l') }).furniture.length, 0)
assert.match(plan(zone, [], { ...request, module: furnitureById.get('desk-l') }).problem, /不匹配/)
const oldWrongMeetingModule = { ...meeting.furniture[0], id: 'OLD-WRONG', layoutGroupId: undefined, furnitureTemplateId: 'desk-l' }
const migratedMeeting = plan(zone, [oldWrongMeetingModule], request)
assert.deepEqual(migratedMeeting.replaceIds, ['OLD-WRONG'], 'explicit legacy meeting context can migrate wrong automatic desk')
assert.equal(migratedMeeting.problem, null)
const sixty = plan(zone, [], { ...request, preferences: { ...request.preferences, targetSeats: 60 } })
assert.equal(sixty.shortfall, 60 - sixty.seats)
assert.ok(sixty.shortfall > 0, 'never label one table as sixty seats')
const resized = plan({ ...zone, w: 30000, polygon: [[-12000,-8000],[18000,-8000],[18000,0],[-12000,0]] }, [], { ...request, preferences: { ...request.preferences, targetSeats: 20, resizeMeetingTable: true } })
assert.ok(resized.seats >= meeting.seats)
assert.ok(resized.furniture[0].w > 2800)
const cabinet = { id: 'MANUAL', kind: 'furniture', zoneId: zone.id, x: -11700, y: -7700, w: 500, h: 500, rotation: 0, furnitureTemplateId: 'cabinet-mobile' }
const withManual = plan(zone, [cabinet], request)
assert.deepEqual(withManual.retainedIds, ['MANUAL'])
assert.deepEqual(withManual.replaceIds, [])
const existing = meeting.furniture.map((item, index) => ({ ...item, id: 'OLD-' + index }))
const again = plan(zone, [...existing, cabinet], request)
assert.equal(again.replaceIds.length, 9)
assert.deepEqual(again.retainedIds, ['MANUAL'])
assert.equal(proposalDifference([...existing, cabinet], again), '与当前布局一致')
assert.match(proposalDifference(existing.map((item, i) => i ? item : { ...item, x: item.x + 100 }), meeting), /自动组|位置/)
const fullRoomBlock = rectangleObstacle(zone.id, 'NO_PLACEMENT', { x: -12000, y: 8000 }, { x: 0, y: 0 }, 'ALL')
const blocked = plan({ ...zone, obstacles: [fullRoomBlock] }, [], request)
assert.ok(blocked.problem)
assert.equal(blocked.furniture.length, 0)
const open = { ...zone, zoneType: 'OPEN_OFFICE' }
const deskRequest = { ...request, module: furnitureById.get('desk-straight'), widthMm: 1200, depthMm: 600, productStyleId: 'straight-desk', presetId: 'straight-desk-face-to-face', preferences: { ...defaults(), strategy: 'recommended', targetSeats: 12 } }
const office = plan(open, [cabinet], deskRequest)
assert.equal(office.problem, null)
assert.equal(office.seats, 12)
assert.deepEqual(office.retainedIds, ['MANUAL'])
assert.ok(plan(open, [], { ...deskRequest, preferences: { ...defaults(), targetSeats: -1 } }).problem)
assert.ok(plan(zone, [], { ...request, preferences: { ...defaults(), seatClearanceMm: NaN } }).problem)
const round = plan(zone, [], { ...request, module: furnitureById.get('table-meeting-round'), widthMm: 1000, depthMm: 1000, preferences: { ...defaults(), targetSeats: 4 } })
assert.equal(round.seats, 4)
assert.equal(confirmedLayoutProblem({ ...zone, layoutPreferences: defaults() }, round.furniture), null)
const roundTable = round.furniture[0]
const rightSeat = round.furniture.find(item => item.layoutRole === 'seat' && item.x > roundTable.x + roundTable.w)
assert.equal(rightSeat.rotation, 270, 'round-table chairs face inward')
const inMeeting = plan(zone, [], deskRequest)
assert.equal(inMeeting.problem, null, 'meeting rooms allow desks')
assert.equal(inMeeting.seats, 12)
for (const id of ['cabinet-mobile', 'sofa-double', 'chair-office', 'desk-manager', 'plant']) {
 const module = furnitureById.get(id)
 const generic = { ...request, module, widthMm: module.widthMm, depthMm: module.depthMm, productStyleId: 'general', presetId: 'general-center', preferences: defaults() }
 const single = plan(zone, [], generic)
 assert.equal(single.problem, null, id)
 assert.equal(single.furniture.length, 1)
 assert.equal(single.furniture[0].layoutSettings.layoutContext, 'other')
 const multiple = plan(zone, [], { ...generic, presetId: 'general-auto', preferences: { ...defaults(), targetSeats: 3 } })
 assert.equal(multiple.furniture.length, 3, id)
 assert.equal(confirmedLayoutProblem({ ...zone, layoutPreferences: defaults() }, multiple.furniture), null)
 const blockedGeneral = plan({ ...zone, obstacles: [fullRoomBlock] }, [], { ...generic, presetId: 'general-auto', preferences: defaults() })
 assert.equal(blockedGeneral.furniture.length, 0)
}
// 通道端点留断（LAYOUT-028）：确认校验按留断范围生效；缺省贯通保持旧行为。
const channelZone = { ...zone, layoutPreferences: { ...defaults(), channels: [{ id: 'CH', orientation: 'vertical', offsetMm: -6000, widthMm: 900, startMm: -4000 }] } }
const aboveBreak = { ...cabinetForTest(), id: 'ABOVE', zoneId: zone.id, x: -6300, y: -7000, w: 600, h: 400, rotation: 0 } // 跨过通道线但位于留断范围之上
const insideBreak = { ...aboveBreak, id: 'INSIDE', y: -3500 }
assert.equal(confirmedLayoutProblem(channelZone, [aboveBreak]), null, '留断范围外的家具不受通道约束')
assert.match(confirmedLayoutProblem(channelZone, [insideBreak]) || '', /压占通道/, '留断范围内仍不得压占')
const fullSpanZone = { ...zone, layoutPreferences: { ...defaults(), channels: [{ id: 'CH', orientation: 'vertical', offsetMm: -6000, widthMm: 900 }] } }
assert.match(confirmedLayoutProblem(fullSpanZone, [aboveBreak]) || '', /压占通道/, '缺省贯通时同一位置仍压占')
console.log('PASS: purpose, actual seats/shortfall, explicit resize, pure preview, retained manual furniture, group replacement, spatial obstacles, net area, template roundtrip, blockers, recommended layouts, invalid inputs')
