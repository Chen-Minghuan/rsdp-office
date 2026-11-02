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
const { planLayoutProposal: plan, defaultLayoutPreferences: defaults, proposalDifference, confirmedLayoutProblem, confirmedLayoutWarning, chairActivityRect, layoutCandidateScore, LAYOUT_CANDIDATE_TOP_N } = await import(await load(new URL('../app/utils/layoutPlanning.ts', import.meta.url)))
const { layoutPanelWarnings, layoutWarningTestId, zoneNetAreaMm2, AREA_PER_SEAT_HINT_M2 } = await import(await load(new URL('../app/utils/layoutWarnings.ts', import.meta.url)))
const { planOfficeFurniture, chairBackMinGapMm, CHAIR_BACK_PASSAGE_MM } = await import(await load(new URL('../app/utils/officeAutoLayout.ts', import.meta.url)))
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
assert.equal(confirmedLayoutProblem({ ...zone, layoutPreferences: request.preferences }, [...meeting.furniture, obstruction]), null, '椅子活动区不阻止确认')
assert.match(confirmedLayoutWarning({ ...zone, layoutPreferences: request.preferences }, [...meeting.furniture, obstruction]), /活动区/)
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
// LAYOUT-034 多方案候选：评分口径（目标达成 1000/座封顶 − 超出 1/座；未指定目标按容量；并列保持生成顺序）。
assert.equal(layoutCandidateScore(8, 8), 8000)
assert.equal(layoutCandidateScore(10, 8), 7998)
assert.equal(layoutCandidateScore(6, 8), 6000)
assert.equal(layoutCandidateScore(5, 0), 5)
const sig = items => items.map(e => [e.furnitureTemplateId, Math.round(e.x), Math.round(e.y), Math.round(e.w), Math.round(e.h), Math.round(e.rotation)].join(':')).sort().join('|')
const meetingCandidates = meeting.candidates
assert.ok(meetingCandidates.length >= 2 && meetingCandidates.length <= LAYOUT_CANDIDATE_TOP_N, '会议枚举应给出 2~N 个候选')
assert.equal(sig(meetingCandidates[0].furniture), sig(meeting.furniture), 'candidates[0] 即默认方案')
assert.equal(new Set(meetingCandidates.map(c => sig(c.furniture))).size, meetingCandidates.length, '候选按家具签名去重')
for (let i = 1; i < meetingCandidates.length; i++) assert.ok(layoutCandidateScore(meetingCandidates[i - 1].seats, 8) >= layoutCandidateScore(meetingCandidates[i].seats, 8), '候选按评分降序')
for (const c of meetingCandidates) {
  assert.ok(c.metrics.utilization > 0 && c.metrics.utilization <= 1, '利用率在 (0,1]')
  assert.ok(c.metrics.freeAreaPerSeatM2 >= 0, '人均余量非负')
  assert.equal(c.shortfall, Math.max(0, 8 - c.seats), '候选缺口与座位一致')
}
// 课桌式网格：auto 内部的备选朝向进入候选集（两种朝向都有效时 ≥2 个候选，默认方案不变）。
const generalDeskRequest = { ...request, module: furnitureById.get('sofa-double'), widthMm: 1600, depthMm: 850, productStyleId: 'general', presetId: 'general-auto', preferences: { ...defaults(), targetSeats: 8 } }
const squareZone = { id: 'SQ', zoneType: 'OPEN_OFFICE', reviewUnit: 'mm', polygon: [[0, 0], [6000, 0], [6000, 6000], [0, 6000]] }
const squareDesks = plan(squareZone, [], generalDeskRequest)
assert.equal(squareDesks.problem, null)
assert.ok(squareDesks.candidates.length >= 2, '横纵朝向都有效时应给出候选')
assert.ok(squareDesks.candidates.some(c => c.rotated) && squareDesks.candidates.some(c => !c.rotated), '候选覆盖两种朝向')
assert.equal(sig(squareDesks.candidates[0].furniture), sig(squareDesks.furniture), '网格默认方案不漂移')
// 单变体路径（general-center 居中单件 / 职员桌 adaptive）候选恰好 1 个且等于默认方案。
const singleCandidate = plan(zone, [], { ...request, module: furnitureById.get('cabinet-mobile'), widthMm: 500, depthMm: 500, productStyleId: 'general', presetId: 'general-center', preferences: defaults() })
assert.equal(singleCandidate.candidates.length, 1)
assert.equal(sig(singleCandidate.candidates[0].furniture), sig(singleCandidate.furniture))
// LAYOUT-035 统一校核预警分级（红=硬冲突；黄=低于策略阈值；info=未核验说明；黄/红必附建议）。
const synth = patch => ({ furniture: [], replaceIds: [], retainedIds: [], seats: 0, shortfall: 0, label: '', problem: null, warnings: [], sourceKey: '', ...patch })
// 净面积：zone 为 12000×8000=96㎡；禁放障碍按外包矩形扣除。
assert.equal(Math.round(zoneNetAreaMm2(zone) / 1e6), 96)
const zoneWithBlock = { ...zone, kind: 'zone', obstacles: [rectangleObstacle(zone.id, 'NO_PLACEMENT', { x: -2000, y: -2000 }, { x: 0, y: 0 }, 'NP-1')] }
assert.equal(Math.round(zoneNetAreaMm2(zoneWithBlock) / 1e6), 96 - 4)
const byId = list => Object.fromEntries(list.map(w => [w.id, w]))
// 座位不足为红级（确认会被拒绝）并附建议；补足则不出现。
let ws = byId(layoutPanelWarnings({ zone, channels: [], furniture: [], proposal: synth({ seats: 4, shortfall: 4 }) }))
assert.equal(ws.shortfall.level, 'error')
assert.match(ws.shortfall.text, /座位不足/)
assert.ok(ws.shortfall.advice)
ws = byId(layoutPanelWarnings({ zone, channels: [], furniture: [], proposal: synth({ seats: 8, shortfall: 0 }) }))
assert.ok(!ws.shortfall)
// 人均面积：96㎡/60 座=1.6 < 阈值 2 → 黄级附建议；96/8=12 不出现。
ws = byId(layoutPanelWarnings({ zone, channels: [], furniture: [], proposal: synth({ seats: 60 }) }))
assert.equal(ws['area-per-seat'].level, 'warning')
assert.match(ws['area-per-seat'].text, new RegExp(`低于 ${AREA_PER_SEAT_HINT_M2}`))
assert.ok(ws['area-per-seat'].advice)
ws = byId(layoutPanelWarnings({ zone, channels: [], furniture: [], proposal: synth({ seats: 8 }) }))
assert.ok(!ws['area-per-seat'])
// 方案无法排出为红级；边缘补位警告为黄级；现场核验为 info。
ws = byId(layoutPanelWarnings({ zone, channels: [], furniture: [], proposal: synth({ problem: '当前尺寸、保留家具或预留区域无法容纳方案；请调整参数或逐件放置' }) }))
assert.equal(ws['proposal-problem'].level, 'error')
assert.ok(ws['proposal-problem'].advice)
ws = byId(layoutPanelWarnings({ zone, channels: [], furniture: [], proposal: synth({ warnings: ['主排布之外的边缘余量已用单件补位 2 件，座位朝房间内侧；请核对补位位置与现场通道'] }) }))
assert.equal(ws['edge-fill'].level, 'warning')
assert.ok(ws['edge-fill'].advice)
ws = byId(layoutPanelWarnings({ zone, channels: [], furniture: [], proposal: synth({ warnings: ['门窗、柱及真实通道尚未自动核验；请在空间核对中补充'] }) }))
assert.equal(ws['field-review'].level, 'info')
// 通道宽度黄级（沿用 023 阈值与 testid 映射）。
const narrow = [{ id: 'CH1', orientation: 'vertical', offsetMm: -6000, widthMm: 600 }]
ws = layoutPanelWarnings({ zone: { ...zone, kind: 'zone', obstacles: [] }, channels: narrow, furniture: [], proposal: null })
const widthItem = ws.find(w => w.id === 'channel-width')
assert.equal(widthItem.level, 'warning')
assert.ok(widthItem.advice)
assert.equal(layoutWarningTestId(widthItem), 'channel-width-hint')
// 入口联动迁入：未标注门洞为 info，保留 029 testid 映射；无通道时不产出。
const egressItems = layoutPanelWarnings({ zone: { ...zone, kind: 'zone', obstacles: [] }, channels: [{ id: 'CH2', orientation: 'vertical', offsetMm: -6000, widthMm: 900 }], furniture: [], proposal: null })
const unmarked = egressItems.find(w => w.id === 'channel-egress-unmarked')
assert.equal(unmarked.level, 'info')
assert.equal(layoutWarningTestId(unmarked), 'channel-egress-hint')
assert.equal(layoutPanelWarnings({ zone, channels: [], furniture: [], proposal: null }).length, 0)
// 排序：红 > 黄 > info。
const mixed = layoutPanelWarnings({ zone: { ...zone, kind: 'zone', obstacles: [] }, channels: narrow, furniture: [], proposal: synth({ seats: 4, shortfall: 4 }) })
const levels = mixed.map(w => w.level)
assert.deepEqual(levels, [...levels].sort((a, b) => ({ error: 0, warning: 1, info: 2 })[a] - ({ error: 0, warning: 1, info: 2 })[b]))
// LAYOUT-038 椅背相对通行净宽：引擎自动加宽（faceToFace 组内间隔、backToBack 组间排距），不足→减排；提示说明原因与数值。
assert.equal(chairBackMinGapMm(400), 2 * 400 + CHAIR_BACK_PASSAGE_MM)
const pairedSpacing = { wallMm: 300, aisleMm: 900, gapMm: 0, faceGapMm: 900, backGapMm: 0 }
// faceToFace：faceGapMm 0 自动加宽到 1300，与显式 1300 结果一致；显式 2000 不被缩小。
const faceAuto = planOfficeFurniture(open, 1200, 600, 'faceToFace', { ...pairedSpacing, faceGapMm: 0 }, [], 400)
const faceExplicit = planOfficeFurniture(open, 1200, 600, 'faceToFace', { ...pairedSpacing, faceGapMm: 1300 }, [], 400)
assert.deepEqual(faceAuto.slots, faceExplicit.slots, 'faceToFace 组内间隔加宽到净宽')
const faceLarger = planOfficeFurniture(open, 1200, 600, 'faceToFace', { ...pairedSpacing, faceGapMm: 2000 }, [], 400)
assert.ok(faceLarger.slots.length <= faceAuto.slots.length && faceLarger.slots.length > 0, '显式更大间隔保留且不缩减')
// backToBack：组间 aisleMm 900 自动加宽到 1300，与显式 1300 结果一致。
const backAuto = planOfficeFurniture(open, 1200, 600, 'backToBack', pairedSpacing, [], 400)
const backExplicit = planOfficeFurniture(open, 1200, 600, 'backToBack', { ...pairedSpacing, aisleMm: 1300 }, [], 400)
assert.deepEqual(backAuto.slots, backExplicit.slots, 'backToBack 组间排距加宽到净宽')
// 加宽后放不下→减少排数：扁空间（12000×3900）拉椅预留 400 时加宽减排，预留 0（净宽 500<900 不加宽）保持。
const flatZone = { id: 'FLAT', zoneType: 'OPEN_OFFICE', reviewUnit: 'mm', polygon: [[0, 0], [12000, 0], [12000, 3900], [0, 3900]] }
const flatWidened = planOfficeFurniture(flatZone, 1200, 600, 'backToBack', pairedSpacing, [], 400)
const flatUnwidened = planOfficeFurniture(flatZone, 1200, 600, 'backToBack', pairedSpacing, [], 0)
assert.ok(flatWidened.slots.length > 0 && flatWidened.slots.length < flatUnwidened.slots.length, '加宽后放不下则减少排数')
// 提示：低于净宽时黄级说明原因与数值（含自动加宽后的值），达标不出现；faceToFace 检查 faceGapMm。
let ws038 = layoutPanelWarnings({ zone: open, channels: [], furniture: [], proposal: null, spacing: pairedSpacing, presetId: 'generic-back-to-back', seatClearanceMm: 400 })
const gapItem = ws038.find(w => w.id === 'chair-back-gap')
assert.equal(gapItem.level, 'warning')
assert.match(gapItem.text, /自动加宽到 1300mm/)
assert.ok(gapItem.advice)
ws038 = layoutPanelWarnings({ zone: open, channels: [], furniture: [], proposal: null, spacing: { ...pairedSpacing, aisleMm: 1300 }, presetId: 'generic-back-to-back', seatClearanceMm: 400 })
assert.ok(!ws038.some(w => w.id === 'chair-back-gap'), '排距达标不提示')
ws038 = layoutPanelWarnings({ zone: open, channels: [], furniture: [], proposal: null, spacing: pairedSpacing, presetId: 'generic-face-to-face', seatClearanceMm: 400 })
assert.ok(ws038.some(w => w.id === 'chair-back-gap'), 'faceToFace 组内间隔低于净宽同样提示')
ws038 = layoutPanelWarnings({ zone: open, channels: [], furniture: [], proposal: null, spacing: pairedSpacing, presetId: 'generic-auto', seatClearanceMm: 400 })
assert.ok(!ws038.some(w => w.id === 'chair-back-gap'), '非配对排布不提示')
// 样式参考排布（adaptive 路径）间距按素材固定：低于净宽只提示、文案不含自动加宽。
ws038 = layoutPanelWarnings({ zone: open, channels: [], furniture: [], proposal: null, spacing: { ...pairedSpacing, faceGapMm: 1000 }, presetId: 'straight-desk-row', seatClearanceMm: 400 })
const refGapItem = ws038.find(w => w.id === 'chair-back-gap')
assert.equal(refGapItem.level, 'warning')
assert.match(refGapItem.text, /未自动加宽/)
ws038 = layoutPanelWarnings({ zone: open, channels: [], furniture: [], proposal: null, spacing: { ...pairedSpacing, faceGapMm: 1600 }, presetId: 'straight-desk-face-to-face', seatClearanceMm: 400 })
assert.ok(!ws038.some(w => w.id === 'chair-back-gap'), '参考排布净距达标不提示')

// --- LAYOUT-036 求解目标三入口、可解释评分、差异化 Top N、失败建议 ---
const { layoutObjectiveGoal, layoutCandidateScoreDetail, layoutTopologyKey, selectDiverseCandidates, BALANCED_SCORE_WEIGHTS } = await import(await load(new URL('../app/utils/layoutPlanning.ts', import.meta.url)))
assert.deepEqual(layoutObjectiveGoal({ targetSeats: 8 }), { objective: 'target-seats', goal: 8 }, '缺省为目标人数旧行为')
assert.deepEqual(layoutObjectiveGoal({ objective: 'fixed-quantity', targetSeats: 8, fixedQuantity: 5 }), { objective: 'fixed-quantity', goal: 5 })
assert.deepEqual(layoutObjectiveGoal({ objective: 'max-capacity', targetSeats: 8 }), { objective: 'max-capacity', goal: 0 })
assert.deepEqual(layoutObjectiveGoal({ targetSeats: -1 }), { objective: 'target-seats', goal: -1 }, '非法值不取整不钳制，交由既有校验拒绝')

// 固定件数：恰好放下指定件数；超过容量时报告缺口而非静默少放
const fixed6 = plan(open, [], { ...deskRequest, preferences: { ...defaults(), strategy: 'recommended', objective: 'fixed-quantity', fixedQuantity: 6 } })
assert.equal(fixed6.problem, null)
assert.equal(fixed6.seats, 6, '固定件数恰好 6 件')
assert.equal(fixed6.shortfall, 0)
const fixedHuge = plan(open, [], { ...deskRequest, preferences: { ...defaults(), strategy: 'recommended', objective: 'fixed-quantity', fixedQuantity: 480 } })
assert.ok(fixedHuge.seats < 480 && fixedHuge.shortfall > 0, '超出容量报告缺口')
assert.ok(fixedHuge.warnings.some(w => /固定件数仅放下/.test(w)), '固定件数缺口附原因说明')
assert.ok(plan(open, [], { ...deskRequest, preferences: { ...defaults(), objective: 'fixed-quantity', fixedQuantity: 1.5 } }).problem, '非整件数拒绝')
assert.ok(plan(open, [], { ...deskRequest, preferences: { ...defaults(), objective: 'fixed-quantity', fixedQuantity: -2 } }).problem, '负件数拒绝')

// 最大容量：不设上限排满，附探索值免责提示；目标人数口径同空间不漂移
const maxCap = plan(open, [], { ...deskRequest, preferences: { ...defaults(), strategy: 'recommended', objective: 'max-capacity' } })
assert.equal(maxCap.problem, null)
assert.ok(maxCap.seats > 12, '最大容量超过目标人数 12 的摆放量')
assert.equal(maxCap.shortfall, 0, '容量探索无缺口概念')
assert.ok(maxCap.warnings.some(w => /不等于施工或法规核准容量/.test(w)), '最大容量附非核准容量提示')
const legacyAgain = plan(open, [cabinet], deskRequest)
assert.equal(legacyAgain.seats, 12, '目标人数默认行为不漂移')

// 可解释评分：子项齐全、理由可读；balanced 排序按总分降序
for (const candidate of maxCap.candidates) {
  assert.ok(candidate.scoreDetail, '候选附可解释评分')
  assert.ok(candidate.scoreDetail.reasons.length > 0, '说明为什么适合')
  assert.equal(typeof candidate.scoreDetail.total, 'number')
  assert.ok(candidate.topologyKey, '候选附拓扑签名')
}
const balanced = plan(open, [], { ...deskRequest, preferences: { ...defaults(), strategy: 'recommended', objective: 'max-capacity', scoreMode: 'balanced' } })
for (let i = 1; i < balanced.candidates.length; i++) {
  assert.ok(balanced.candidates[i - 1].scoreDetail.total >= balanced.candidates[i].scoreDetail.total, 'balanced 按总分降序')
}
assert.equal(BALANCED_SCORE_WEIGHTS.fulfillment + BALANCED_SCORE_WEIGHTS.comfort + BALANCED_SCORE_WEIGHTS.zoning + BALANCED_SCORE_WEIGHTS.utilization + BALANCED_SCORE_WEIGHTS.alignment, 100, '权重合计 100')

// 差异化：Top N 拓扑签名优先互不相同（网格横/纵朝向）
const gridVariants = plan(open, [], { ...deskRequest, preferences: { ...defaults(), strategy: 'recommended', objective: 'max-capacity' } })
if (gridVariants.candidates.length > 1) {
  const keys = gridVariants.candidates.map(c => c.topologyKey)
  assert.ok(new Set(keys).size >= Math.min(2, keys.length), 'Top N 候选拓扑不同，非同拓扑平移')
}
// 纯函数：差异化挑选不足额按顺序补足，不编造
const diverse = selectDiverseCandidates([{ topologyKey: 'a', v: 1 }, { topologyKey: 'a', v: 2 }, { topologyKey: 'b', v: 3 }], 3)
assert.deepEqual(diverse.map(x => x.v), [1, 3, 2], '先取不同拓扑再按序补足')
assert.equal(layoutTopologyKey({ label: '按场地排布 · 横向', rows: 2, columns: 4, rotated: false }), layoutTopologyKey({ label: '按场地排布 · 横向', rows: 2, columns: 4, rotated: false }))
assert.notEqual(layoutTopologyKey({ label: '按场地排布 · 横向', rows: 2, columns: 4, rotated: false }), layoutTopologyKey({ label: '按场地排布 · 纵向', rows: 4, columns: 2, rotated: true }))

// 失败建议：排不下时附 problemAdvice
const impossible = plan(open, [], { ...deskRequest, widthMm: 20000, depthMm: 9000, preferences: { ...defaults(), strategy: 'recommended', targetSeats: 4 } })
assert.ok(impossible.problem)
assert.ok(Array.isArray(impossible.problemAdvice) && impossible.problemAdvice.length >= 2, '失败附调整建议')

// 评分细节纯函数：达成/缺口/超出/补位文案
const detail = layoutCandidateScoreDetail({ seats: 10, placed: 8, goal: 10, utilization: 0.5, freeAreaPerSeatM2: 3.2, fillCount: 2 })
assert.ok(detail.penalties.some(s => /尚缺 2/.test(s)))
assert.ok(detail.penalties.some(s => /补位 2 件/.test(s)))
assert.equal(detail.fulfillment, 0.8)
const detailMax = layoutCandidateScoreDetail({ seats: 30, placed: 30, goal: 0, utilization: 0.6, freeAreaPerSeatM2: 2.5 })
assert.ok(detailMax.reasons.some(s => /上限估计，非核准容量/.test(s)))

// --- 三档联动：默认标准，紧凑/舒适按标准间距变化；不改家具尺寸 ---
const { planTieredLayoutProposal } = await import(await load(new URL('../app/utils/layoutPlanning.ts', import.meta.url)))
const { OFFICE_LAYOUT_SPACING_PRESETS, officeLayoutSpacingVariants } = await import(await load(new URL('../app/utils/officeAutoLayout.ts', import.meta.url)))
// 当前间距取档间值（排距由 aisle/face 驱动且有饱和，1000 介于紧凑 800 与舒适 1200 之间），避免与对比档几何相同被去重
const currentSpacing = { ...deskRequest.spacing, aisleMm: 1000, faceGapMm: 1000 }
const tiered = planTieredLayoutProposal(open, [], { ...deskRequest, spacing: currentSpacing, preferences: { ...defaults(), strategy: 'recommended', targetSeats: 12 } })
assert.equal(tiered.problem, null)
const tiers = tiered.candidates.map(candidate => candidate.spacingTier)
assert.deepEqual(tiers, ['standard', 'compact', 'comfort'], '标准档（当前间距）固定首位，紧凑/舒适随后')
assert.equal(new Set(tiers).size, tiers.length, '几何相同的档位去重，不出重复卡片')
for (const candidate of tiered.candidates) assert.match(candidate.label, /^(标准|紧凑|舒适) · /, '候选标注间距档位且无自定义')
assert.equal(tiered.furniture, tiered.candidates[0].furniture, 'candidates[0] 即默认标准基准方案')
assert.equal(tiered.seats, tiered.candidates[0].seats)
// 调大标准间距后，三个档位都必须重新求解，不再使用固定紧凑/舒适预设。
const adjustedSpacing = { ...currentSpacing, wallMm: 500, aisleMm: 1100, faceGapMm: 1100 }
const adjustedTiered = planTieredLayoutProposal(open, [], { ...deskRequest, spacing: adjustedSpacing, preferences: { ...defaults(), strategy: 'recommended', targetSeats: 12 } })
assert.equal(adjustedTiered.candidates[0].spacingTier, 'standard')
for (const candidate of tiered.candidates) {
  const updated = adjustedTiered.candidates.find(item => item.spacingTier === candidate.spacingTier)
  assert.ok(updated, `${candidate.spacingTier} 仍有可用候选`)
  assert.notDeepEqual(updated.furniture[0].layoutSettings.spacing, candidate.furniture[0].layoutSettings.spacing, `${candidate.spacingTier} 使用新的标准间距重新求解`)
  if (candidate.spacingTier !== 'comfort') assert.notDeepEqual(updated.furniture.map(item => [item.x, item.y]), candidate.furniture.map(item => [item.x, item.y]), `${candidate.spacingTier} 在有排布余量时随间距变化`)
  // 舒适档在此样本受完整单元/座席净距约束，重新求解后允许保留相同坐标。
  assert.ok(updated.furniture.every(item => item.w === deskRequest.widthMm && item.h === deskRequest.depthMm || item.w === deskRequest.depthMm && item.h === deskRequest.widthMm), '不缩放家具尺寸')
}
const baseSpacing = { wallMm: 400, aisleMm: 1000, gapMm: 600, faceGapMm: 1100, backGapMm: 50, seatClearanceMm: 450 }
const inputCopy = structuredClone(baseSpacing)
const variants = officeLayoutSpacingVariants(baseSpacing)
assert.deepEqual(variants.map(item => item.spacing.aisleMm), [1000, 900, 1300], '三档相对标准间距计算')
assert.deepEqual(variants.map(item => item.spacing.gapMm), [600, 300, 800])
assert.deepEqual(variants.map(item => item.spacing.wallMm), [400, 400, 1000])
assert.deepEqual(variants.map(item => item.spacing.faceGapMm), [1100, 1000, 1400])
assert.deepEqual(variants.map(item => item.spacing.backGapMm), [50, 50, 350])
assert.ok(variants.every(item => item.spacing.seatClearanceMm === 450), '其他预留不参与档位缩减')
assert.deepEqual(baseSpacing, inputCopy, '三档派生不修改标准输入')
assert.equal(officeLayoutSpacingVariants({ wallMm: 0, aisleMm: 50, gapMm: 0 })[1].spacing.aisleMm, 0, '紧凑档最小为零')
assert.equal(officeLayoutSpacingVariants({ wallMm: 0, aisleMm: -1, gapMm: 0 })[1].spacing.aisleMm, -1, '非法标准参数不能被归零掩盖')
const legacyVariants = officeLayoutSpacingVariants({ wallMm: 0, aisleMm: 900, gapMm: 0, pairMm: 1000 })
assert.equal(legacyVariants[1].spacing.faceGapMm, 900, '旧对排中缝仍按其保存值派生')
const presetVariants = officeLayoutSpacingVariants(OFFICE_LAYOUT_SPACING_PRESETS[1].values)
assert.deepEqual(presetVariants[1].spacing, OFFICE_LAYOUT_SPACING_PRESETS[0].values, '标准预设下紧凑值保持原差值')
assert.deepEqual(presetVariants[2].spacing, OFFICE_LAYOUT_SPACING_PRESETS[2].values, '标准预设下舒适值保持原差值')
// 用户截图尺寸：三档均为 12 工位和约 41% 利用率，差异在真实净距而非件数。
const { officeLayoutClearances } = await import(await load(new URL('../app/utils/officeDimensionAnnotations.ts', import.meta.url)))
const narrowZone = { ...open, id: 'NARROW', x: 0, y: 0, w: 7524, h: 2805, polygon: [[0, 0], [7524, 0], [7524, 2805], [0, 2805]] }
const narrowProposal = planTieredLayoutProposal(narrowZone, [], { ...deskRequest, presetId: 'straight-desk-row', spacing: { wallMm: 0, aisleMm: 900, gapMm: 0, faceGapMm: 1000, backGapMm: 0 }, preferences: defaults() })
assert.deepEqual(narrowProposal.candidates.map(candidate => candidate.seats), [12, 12, 12])
assert.deepEqual(narrowProposal.candidates.map(candidate => Math.round(candidate.metrics.utilization * 100)), [41, 41, 41])
const narrowGaps = narrowProposal.candidates.map(candidate => Math.min(...officeLayoutClearances(narrowZone, candidate.furniture).filter(item => item.kind === 'group').map(item => item.distanceMm)))
assert.deepEqual(narrowGaps, [1302.5, 1252.5, 1452.5], '实际净距与画布同源，可区分数量相同的方案')
const tieredFail = planTieredLayoutProposal(open, [], { ...deskRequest, widthMm: 20000, depthMm: 9000, preferences: { ...defaults(), targetSeats: 4 } })
assert.ok(tieredFail.problem, '全部档位放不下时返回问题')
assert.ok(tieredFail.problemAdvice?.length >= 2, '三档失败同样附调整建议')
console.log('PASS: purpose, actual seats/shortfall, explicit resize, pure preview, retained manual furniture, group replacement, spatial obstacles, net area, template roundtrip, blockers, recommended layouts, invalid inputs, candidate scoring & comparison (LAYOUT-034), unified warning levels & advice (LAYOUT-035), chair-back passage auto-widen (LAYOUT-038), solve objectives & explainable diverse scoring (LAYOUT-036), tiered spacing candidates')
