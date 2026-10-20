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
const zone = { id: 'ROOM', zoneType: 'MEETING_ROOM', reviewUnit: 'mm', x: -12000, y: -8000, w: 12000, h: 8000, polygon: [[-12000,-8000],[0,-8000],[0,0],[-12000,0]] }
const request = { module: furnitureById.get('table-conference-rect'), widthMm: 2800, depthMm: 1200, spacing: { wallMm: 300, aisleMm: 900, gapMm: 0 }, productStyleId: 'meeting-table', presetId: 'meeting-center', preferences: { ...defaults(), targetSeats: 8 } }
const before = JSON.stringify([zone, request])
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
const blocked = plan(zone, [], { ...request, preferences: { ...request.preferences, exclusions: [{ id: 'ALL', label: '禁放区', x: -12000, y: -8000, w: 12000, h: 8000 }] } })
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
 const blockedGeneral = plan(zone, [], { ...generic, presetId: 'general-auto', preferences: { ...defaults(), exclusions: [{id: 'ALL', x:-12000,y:-8000,w:12000,h:8000}] } })
 assert.equal(blockedGeneral.furniture.length, 0)
}
console.log('PASS: purpose, actual seats/shortfall, explicit resize, pure preview, retained manual furniture, group replacement, blockers, recommended layouts, invalid inputs')
