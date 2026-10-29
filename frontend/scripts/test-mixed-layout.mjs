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
const manualUrl = await moduleUrl('../app/utils/manualFurniture.ts', { '~/utils/manualLayoutGeometry': manualGeometryUrl })
const uuidUrl = await moduleUrl('../app/utils/uuid.ts')
const obstaclesUrl = await moduleUrl('../app/utils/spatial/obstacles.ts', { './geometry': geometryUrl, '~/utils/uuid': uuidUrl })
const reviewUrl = await moduleUrl('../app/utils/spatial/review.ts', { '~/utils/spatial/geometry': geometryUrl, './obstacles': obstaclesUrl })
const constraintsUrl = await moduleUrl('../app/utils/layoutConstraints.ts', { './spatial/review': reviewUrl })
const { planMixedLayout } = await import(await moduleUrl('../app/utils/mixedLayout.ts', {
  './manualFurniture': manualUrl,
  './officeCanvasView': canvasViewUrl,
  './officeAutoLayout': autoUrl,
  './spatial/obstacles': obstaclesUrl,
  './layoutConstraints': constraintsUrl,
  './layoutChannels': channelsUrl,
  './spatial/geometry': geometryUrl,
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
assert.equal(targeted.seats, 8)
const meetingScene = planMixedLayout(zone, [], ['meeting'], spacing)
assert.equal(meetingScene.furniture.length, 11, '会议场景桌居中并生成双侧及短边座椅')
const meetingChairs = meetingScene.furniture.filter(item => item.layoutRole === 'seat')
assert.ok(new Set(meetingChairs.map(item => item.y)).size >= 3, '会议椅围绕桌子多侧布置，而非单行排列')
assert.ok(meetingChairs.some(item => item.rotation === 90) && meetingChairs.some(item => item.rotation === 270), '短边座椅具有相向朝向')
const meetingTable = meetingScene.furniture.find(item => item.furnitureTemplateId === 'table-conference-rect')
const meetingCenterX = meetingTable.x + meetingTable.w / 2
assert.ok(Math.abs(meetingCenterX - 6000) < 0.001 && Math.abs(meetingTable.y + meetingTable.h / 2 - 4000) < 0.001, '会议区整体居中而非挤在角落')
for (const rotation of [0, 180]) {
  const row = meetingChairs.filter(item => item.rotation === rotation)
  const rowCenter = (Math.min(...row.map(item => item.x)) + Math.max(...row.map(item => item.x + item.w))) / 2
  assert.equal(row.length, 4, '长边两侧各有四把椅子')
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
const { rectOverlapsBlocker } = await import(constraintsUrl)
const door = doorClearanceObstacle(zone.id, { x: 11000, y: -7000 }, { x: 12000, y: -6000 })
const doorZone = { ...zone, obstacles: [door] }
const doorCandidates = planMixedLayout(doorZone, [], ['meeting', 'entry'], spacing).candidates
for (const candidate of doorCandidates) {
  const cabinet = candidate.furniture.find(item => item.furnitureTemplateId === 'cabinet-entry')
  assert.equal(rectOverlapsBlocker(cabinet, zoneLayoutExclusions(doorZone)[0]), false, '玄关柜不得压占门洞净空')
}
const channelBlocked = { ...zone, layoutPreferences: { channels: [{ id: 'CH-ALL', orientation: 'vertical', offsetMm: 0, widthMm: 12000 }] } }
assert.equal(planMixedLayout(channelBlocked, [], ['workstation', 'sofa'], spacing).furniture.length, 0, '组合候选不得压占通道')
console.log('混合布局算法通过：整体候选、会议场景双侧/短边座椅、会客交流区、目标人数、边界、禁放区与通道校验。')
