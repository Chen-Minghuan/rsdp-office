import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'

async function moduleUrl(path, dependencies = {}) {
  let source = stripTypeScriptTypes(await readFile(new URL(path, import.meta.url), 'utf8'))
  for (const [alias, url] of Object.entries(dependencies)) source = source.replaceAll(`'${alias}'`, `'${url}'`)
  return 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
}
const boundsUrl = await moduleUrl('../app/utils/floorPlan.ts')
const geometryUrl = await moduleUrl('../app/utils/spatial/geometry.ts')
const canvasViewUrl = await moduleUrl('../app/utils/officeCanvasView.ts', { '~/utils/floorPlan': boundsUrl })
const channelsUrl = await moduleUrl('../app/utils/layoutChannels.ts', { '~/utils/officeCanvasView': canvasViewUrl, '~/utils/spatial/geometry': geometryUrl })
const autoUrl = await moduleUrl('../app/utils/officeAutoLayout.ts', { '~/utils/floorPlan': boundsUrl, '~/utils/layoutChannels': channelsUrl })
const manualGeometryUrl = await moduleUrl('../app/utils/manualLayoutGeometry.ts')
const meetingCatalogUrl = await moduleUrl('../app/utils/meetingFurnitureCatalog.ts')
const ringUrl = await moduleUrl('../app/utils/meetingRingTable.ts')
const meetingLayoutUrl = await moduleUrl('../app/utils/meetingReferenceLayout.ts', { './meetingRingTable': ringUrl, './meetingFurnitureCatalog': meetingCatalogUrl })
const manualUrl = await moduleUrl('../app/utils/manualFurniture.ts', { '~/utils/manualLayoutGeometry': manualGeometryUrl, './meetingFurnitureCatalog': meetingCatalogUrl })
const uuidUrl = await moduleUrl('../app/utils/uuid.ts')
const obstaclesUrl = await moduleUrl('../app/utils/spatial/obstacles.ts', { './geometry': geometryUrl, '~/utils/uuid': uuidUrl })
const reviewUrl = await moduleUrl('../app/utils/spatial/review.ts', { '~/utils/spatial/geometry': geometryUrl, './obstacles': obstaclesUrl })
const constraintsUrl = await moduleUrl('../app/utils/layoutConstraints.ts', { './spatial/review': reviewUrl })
const workstationSeatingUrl = await moduleUrl('../app/utils/furniture3d/workstationSeating.ts', { '~/utils/manualFurniture': manualUrl })
const footprintUrl = await moduleUrl('../app/utils/furnitureFootprint.ts', {
  '~/utils/manualFurniture': manualUrl,
  '~/utils/layoutConstraints': constraintsUrl,
  '~/utils/furniture3d/workstationSeating': workstationSeatingUrl,
})
const regionsUrl = await moduleUrl('../app/utils/spatial/layoutRegions.ts', { 'polygon-clipping': new URL('../node_modules/polygon-clipping/dist/polygon-clipping.esm.js', import.meta.url).href, './review': reviewUrl })
const facilitiesUrl = await moduleUrl('../app/utils/spatial/facilities.ts', { './review': reviewUrl })
const scoringUrl = await moduleUrl('../app/utils/layoutScoring.ts', { './spatial/layoutRegions': regionsUrl, './spatial/obstacles': obstaclesUrl })
const { planMixedLayout, referenceLayoutSnapshot, mixedPlacementProblem } = await import(await moduleUrl('../app/utils/mixedLayout.ts', {
  './manualFurniture': manualUrl,
  './meetingReferenceLayout': meetingLayoutUrl,
  './furnitureFootprint': footprintUrl,
  './officeCanvasView': canvasViewUrl,
  './officeAutoLayout': autoUrl,
  './spatial/obstacles': obstaclesUrl,
  './layoutConstraints': constraintsUrl,
  './layoutChannels': channelsUrl,
  './spatial/geometry': geometryUrl,
  './spatial/layoutRegions': regionsUrl,
  './spatial/facilities': facilitiesUrl,
  './layoutScoring': scoringUrl,
}))
const spacing = { wallMm: 0, aisleMm: 900, gapMm: 300 }
const zone = { id: 'MIXED', kind: 'zone', name: '组合空间', x: 0, y: 0, w: 12000, h: 8000, reviewUnit: 'mm', polygon: [[0,0],[12000,0],[12000,8000],[0,8000]] }
const combined = planMixedLayout(zone, [], ['workstation', 'sofa'], spacing)
assert.equal(combined.furniture.length, 4, '办公桌椅与沙发茶几只生成各自一套，不追求排满')
assert.deepEqual(combined.furniture.map(item => item.furnitureTemplateId).sort(), ['chair-office', 'coffee-round', 'desk-straight', 'sofa-double'].sort())
assert.equal(combined.problem, null)
assert.ok(combined.candidates.length >= 2, '组合求解提供多个整体候选')
assert.notDeepEqual(combined.candidates[0].furniture, combined.candidates[1].furniture, '候选应具有不同整体排布')
for (const candidate of combined.candidates) {
  assert.ok(candidate.metrics.utilization > 0 && candidate.metrics.utilization <= 1)
  for (const item of candidate.furniture) {
    assert.ok(item.x >= 0 && item.y >= 0 && item.x + item.w <= zone.w && item.y + item.h <= zone.h, '组合家具必须在空间内')
  }
}
const targeted = planMixedLayout(zone, [], ['workstation', 'tea'], spacing, 3)
assert.equal(targeted.furniture.length, 9, '三组办公桌椅加一套茶桌茶椅共 9 件')
assert.equal(targeted.furniture.filter(item => item.furnitureTemplateId === 'desk-straight').length, 3)
assert.equal(targeted.furniture.filter(item => item.furnitureTemplateId === 'table-tea-rect').length, 1)
assert.equal(targeted.seats, 5)
const meetingScene = planMixedLayout(zone, [], ['meeting'], spacing)
assert.equal(meetingScene.furniture.length, 9, 'L06 会议场景桌居中并生成 8 席')
const meetingChairs = meetingScene.furniture.filter(item => item.layoutRole === 'seat')
assert.ok(new Set(meetingChairs.map(item => item.y)).size >= 3, '会议椅围绕桌子多侧布置，而非单行排列')
assert.ok(meetingChairs.some(item => item.rotation === 90) && meetingChairs.some(item => item.rotation === 270), '短边座椅具有相向朝向')
const meetingTable = meetingScene.furniture.find(item => item.furnitureTemplateId === 'table-conference-rect')
const meetingCenterX = meetingTable.x + meetingTable.w / 2
assert.ok(Math.abs(meetingCenterX - 6000) < 0.001 && Math.abs(meetingTable.y + meetingTable.h / 2 - 4000) < 0.001, '会议区整体居中而非挤在角落')
for (const rotation of [0, 180]) {
  const row = meetingChairs.filter(item => item.rotation === rotation)
  const rowCenter = (Math.min(...row.map(item => item.x)) + Math.max(...row.map(item => item.x + item.w))) / 2
  assert.equal(row.length, 3, '长边两侧各有三把椅子')
  assert.ok(Math.abs(rowCenter - meetingCenterX) < 0.001, '上下排椅子必须相对桌面居中，不能偏向一端')
  assert.ok(row.every(item => item.x >= meetingTable.x && item.x + item.w <= meetingTable.x + meetingTable.w), '长边椅子不得排出桌面覆盖范围')
}
const meetingWithEntry = planMixedLayout(zone, [], ['meeting', 'entry'], spacing)
const entryPositions = meetingWithEntry.candidates.map(candidate => {
  const cabinet = candidate.furniture.find(item => item.furnitureTemplateId === 'cabinet-entry')
  return { x: cabinet.x, y: cabinet.y }
})
assert.ok(new Set(entryPositions.map(position => `${position.x}:${position.y}`)).size > 1, '沿墙收纳提供不同位置，同时保持会议区居中')
const loungeScene = planMixedLayout(zone, [], ['lounge'], spacing)
assert.equal(loungeScene.furniture.length, 4, '会客场景由沙发、茶几和两把椅子组成')
const loungeTable = loungeScene.furniture.find(item => item.furnitureTemplateId === 'coffee-rect')
const loungeChairs = loungeScene.furniture.filter(item => item.layoutRole === 'seat')
assert.ok(loungeChairs.every(item => item.y > loungeTable.y), '会客座椅与沙发形成面对面交流区')
assert.equal(loungeScene.furniture.find(item => item.furnitureTemplateId === 'sofa-triple').rotation, 180)
const blocked = { ...zone, obstacles: [{ obstacleId: 'BLOCK', kind: 'NO_PLACEMENT', spaceId: zone.id, geometry: { outer: zone.polygon.map(([x, y]) => [x, -y]) } }] }
const rejected = planMixedLayout(blocked, [], ['workstation', 'meeting'], spacing)
assert.equal(rejected.furniture.length, 0, '全空间禁放时不得生成组合')
assert.ok(rejected.problem)
const { zoneLayoutExclusions, doorClearanceObstacle } = await import(obstaclesUrl)
const { rotatedFurnitureFootprint: officeFurnitureFootprint, rectOverlapsBlocker } = await import(constraintsUrl)
const door = doorClearanceObstacle(zone.id, { x: 11000, y: -7000 }, { x: 12000, y: -6000 })
const doorZone = { ...zone, obstacles: [door] }
const doorCandidates = planMixedLayout(doorZone, [], ['meeting', 'entry'], spacing).candidates
for (const candidate of doorCandidates) {
  const cabinet = candidate.furniture.find(item => item.furnitureTemplateId === 'cabinet-entry')
  assert.equal(rectOverlapsBlocker(officeFurnitureFootprint(cabinet), zoneLayoutExclusions(doorZone)[0]), false, '玄关柜不得压占门洞净空')
}
const channelBlocked = { ...zone, layoutPreferences: { channels: [{ id: 'CH-ALL', orientation: 'vertical', offsetMm: 0, widthMm: 12000 }] } }
assert.equal(planMixedLayout(channelBlocked, [], ['workstation', 'sofa'], spacing).furniture.length, 0, '组合候选不得压占通道')

// --- LAYOUT-036 组合布局求解目标三入口与可解释评分 ---
// 固定套数：恰好 3 套工位 + 其他模块各一套
const fixed3 = planMixedLayout(zone, [], ['workstation', 'sofa'], spacing, 0, { objective: 'fixed-quantity', fixedQuantity: 3 })
assert.equal(fixed3.furniture.filter(item => item.furnitureTemplateId === 'desk-straight').length, 3, '固定套数恰好 3 套工位')
assert.equal(fixed3.shortfall, 0)
// 固定套数超容量：报告缺口与原因
const fixedOver = planMixedLayout(zone, [], ['workstation'], spacing, 0, { objective: 'fixed-quantity', fixedQuantity: 490 })
assert.ok(fixedOver.shortfall > 0, '超出容量报告缺口')
// 最大容量：超过目标 4 的套数，附非核准容量提示
const maxCapMixed = planMixedLayout(zone, [], ['workstation'], spacing, 0, { objective: 'max-capacity' })
assert.ok(maxCapMixed.furniture.filter(item => item.furnitureTemplateId === 'desk-straight').length > 4, '最大容量排满工位')
assert.ok(maxCapMixed.warnings.some(w => /非施工或法规核准容量/.test(w)), '最大容量附非核准容量提示')
// 旧行为不漂移：无 objective 时与之前一致（工位 1 套）
const legacyMixed = planMixedLayout(zone, [], ['workstation', 'sofa'], spacing)
assert.equal(legacyMixed.furniture.filter(item => item.furnitureTemplateId === 'desk-straight').length, 1, '缺省仍是一套')
// 可解释评分与拓扑差异：候选附 scoreDetail/topologyKey，balanced 按总分降序
for (const candidate of maxCapMixed.candidates) {
  assert.ok(candidate.scoreDetail && candidate.topologyKey, '组合候选附可解释评分与拓扑签名')
}
const balancedMixed = planMixedLayout(zone, [], ['workstation', 'meeting', 'entry'], spacing, 0, { objective: 'max-capacity', scoreMode: 'balanced' })
assert.ok(balancedMixed.candidates.every(candidate => candidate.scoreDetail && candidate.officeSeats > 0), '综合排序仍以需求、通行和净空为先，保留评分依据')
// 失败建议：全通道封死时附 problemAdvice
const blockedAdvice = planMixedLayout(channelBlocked, [], ['workstation', 'sofa'], spacing)
assert.ok(blockedAdvice.problem && blockedAdvice.problemAdvice?.length >= 2, '组合失败附调整建议')
const executive = { ...zone, zoneType: 'EXECUTIVE_OFFICE', name: '副总办公室', w: 4361, h: 5049, polygon: [[0,0],[4361,0],[4361,5049],[0,5049]] }
const strategyPlan = planMixedLayout(executive, [], ['workstation', 'entry'], spacing, 1)
assert.equal(strategyPlan.officeSeats, 1)
assert.equal(strategyPlan.guestSeats, 0)
assert.equal(strategyPlan.seats, 1)
assert.equal(strategyPlan.passageMm, null)
assert.ok(strategyPlan.candidates.every(candidate => candidate.furniture.some(item => ['desk-manager', 'desk-executive'].includes(item.furnitureTemplateId))))
assert.ok(strategyPlan.candidates.some(candidate => candidate.furniture.some(item => item.furnitureTemplateId === 'desk-manager')))
assert.equal(strategyPlan.furniture.length, 3)
assert.equal(new Set(strategyPlan.candidates.map(candidate => candidate.topologyKey)).size, strategyPlan.candidates.length)
for (const candidate of strategyPlan.candidates) assert.equal(mixedPlacementProblem(executive, candidate.furniture, spacing), null)
const wideGapPlan = planMixedLayout(executive, [], ['workstation', 'entry'], { ...spacing, aisleMm: 2000 }, 1)
assert.notDeepEqual(wideGapPlan.furniture.map(item => [item.x, item.y]), strategyPlan.furniture.map(item => [item.x, item.y]), '组合净距参与求解')
assert.equal(mixedPlacementProblem(executive, wideGapPlan.furniture, { ...spacing, aisleMm: 2000 }), null)
const closePair = wideGapPlan.furniture.map(item => item.furnitureTemplateId === 'cabinet-entry' ? { ...item, x: wideGapPlan.furniture[0].x, y: wideGapPlan.furniture[0].y } : item)
assert.ok(mixedPlacementProblem(executive, closePair, spacing))
assert.ok(mixedPlacementProblem(executive, [...closePair].reverse(), spacing), '组间净距与插入顺序无关')

const make = (templateId, categoryId, group, x, y, width, depth, confidence = 'high', rotation = 0) => ({ templateId, categoryId, group, x, y, width, depth, confidence, rotation })
const reference = { analysisId: 'drawing', revision: 2, crop: { x: 0, y: 0, w: executive.w, h: executive.h }, includeOtherCategories: false, marks: [
  make('desk-manager', 'workstation', 'office', .12, .28, .28, .11),
  make('chair-office', 'workstation', 'office', .18, .14, .12, .10, 'manual', 180),
  make('cabinet-high', 'entry', 'storage', .9, .52, .08, .24, 'high', 90),
  make('chair-lounge', 'lounge', 'social', .65, .17, .13, .10, 'low'),
] }
const fromDrawing = referenceLayoutSnapshot(executive, [], ['workstation', 'entry'], reference)
assert.equal(fromDrawing.label, '参考原图')
assert.equal(fromDrawing.seats, 1)
assert.equal(fromDrawing.problem, null)
assert.equal(fromDrawing.furniture.filter(item => item.layoutReference).length, 3)
assert.equal(fromDrawing.furniture.find(item => item.furnitureTemplateId === 'chair-office').layoutReference.evidence, 'MANUAL')
const cabinet = fromDrawing.furniture.find(item => item.furnitureTemplateId === 'cabinet-high')
assert.equal(cabinet.rotation, 90)
assert.ok(Math.abs(officeFurnitureFootprint(cabinet).w - .08 * executive.w) < .001)
assert.ok(Math.abs(officeFurnitureFootprint(cabinet).x - .9 * executive.w) < .001, '直接应用保留已编辑位置')
assert.equal(mixedPlacementProblem(executive, fromDrawing.furniture, { wallMm: 0, aisleMm: 0, gapMm: 0 }), null)
assert.match(referenceLayoutSnapshot(executive, [], [], reference).problem, /勾选纳入/)
const pending = referenceLayoutSnapshot(executive, [], [], { ...reference, includeOtherCategories: true })
assert.match(pending.problem, /1 件家具待确认/)
assert.equal(pending.furniture.length, 0, '低置信家具未确认时不能部分提交')
console.log('混合布局算法通过：组合搜索、房型模板、座位口径、对称净距、建筑约束及原图参考。')
