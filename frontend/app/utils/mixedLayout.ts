import type { FurnitureObject, ZoneObject } from '~/types/office'
import type { LayoutCandidate, LayoutObjective, LayoutProposal, LayoutReference, LayoutReferenceEditing, LayoutReferenceMark, LayoutScoreMode } from '~/types/layoutPlanning'
import type { OfficeLayoutSpacing } from './officeAutoLayout'
import { furnitureById } from './manualFurniture'
import { canvasZoneBounds } from './officeCanvasView'
import { layoutRectInsideRing } from './officeAutoLayout'
import { zoneLayoutExclusions } from './spatial/obstacles'
import { allowedPlacementAreas, ringWithinPlacementAreas } from './spatial/layoutRegions'
import { facilityLayoutHints } from './spatial/facilities'
import type { PlacementArea } from './spatial/layoutRegions'
import { rotatedFurnitureFootprint as officeFurnitureFootprint, rectOverlapsBlocker } from './layoutConstraints'
import { furnitureFootprintRects, rectsOverlap } from './furnitureFootprint'
import { channelRects } from './layoutChannels'
import { ringArea } from './spatial/geometry'
import { layoutCandidateScoreDetail, layoutFailureAdvice, layoutObjectiveGoal } from './layoutScoring'
import { meetingReferenceLayout } from './meetingReferenceLayout'

type BundleItem = { templateId: string; role: 'primary' | 'seat'; x: number; y: number; w: number; h: number; rotation?: number }
type Bundle = { categoryId: string; label: string; width: number; height: number; items: BundleItem[] }
type BundleDefinition = { label: string; primary: string; accessories?: Array<{ id: string; role?: 'primary' | 'seat'; count?: number }> }
type StrategyId = 'workstations' | 'social' | 'walls'
type Strategy = { id: StrategyId; order: string[] }


export const MIXED_LAYOUT_CATEGORIES: BundleDefinition[] = [
  { label: '办公桌椅', primary: 'desk-straight', accessories: [{ id: 'chair-office', role: 'seat' }] },
  { label: '沙发茶几', primary: 'sofa-double', accessories: [{ id: 'coffee-round' }] },
  { label: '会议桌椅', primary: 'table-conference-rect' },
  { label: '茶桌茶椅', primary: 'table-tea-rect', accessories: [{ id: 'chair-visitor', role: 'seat', count: 2 }] },
  { label: '餐厅桌椅', primary: 'table-dining-rect', accessories: [{ id: 'chair-visitor', role: 'seat', count: 4 }] },
  { label: '玄关模块', primary: 'cabinet-entry' },
  { label: '娱乐家具', primary: 'table-game', accessories: [{ id: 'chair-lounge', role: 'seat', count: 2 }, { id: 'plant' }] },
  { label: '厨房吧台', primary: 'counter-bar', accessories: [{ id: 'chair-bar', role: 'seat', count: 2 }] },
  { label: '休闲会客', primary: 'sofa-triple', accessories: [{ id: 'coffee-rect' }, { id: 'chair-lounge', role: 'seat', count: 2 }] },
]
export const MIXED_CATEGORY_IDS = ['workstation', 'sofa', 'meeting', 'tea', 'dining', 'entry', 'leisure', 'bar', 'lounge']

const STRATEGIES: Strategy[] = [
  { id: 'workstations', order: ['workstation', 'meeting', 'tea', 'dining', 'lounge', 'sofa', 'leisure', 'bar', 'entry'] },
  { id: 'social', order: ['meeting', 'lounge', 'sofa', 'tea', 'dining', 'leisure', 'workstation', 'bar', 'entry'] },
  { id: 'walls', order: ['entry', 'bar', 'workstation', 'meeting', 'lounge', 'sofa', 'tea', 'dining', 'leisure'] },
]

function createTableSceneBundle(categoryId: string, definition: BundleDefinition, primary: NonNullable<ReturnType<typeof furnitureById.get>>, chair: NonNullable<ReturnType<typeof furnitureById.get>>): Bundle {
  const gap = 300
  const nx = Math.max(1, Math.floor((primary.widthMm - 100) / 650))
  const ny = Math.floor(Math.max(0, primary.depthMm - 100) / 650)
  const width = primary.widthMm + (ny ? 2 * (chair.widthMm + gap) : 0)
  const height = primary.depthMm + 2 * (chair.depthMm + gap)
  const tableX = (width - primary.widthMm) / 2, tableY = chair.depthMm + gap
  const items: BundleItem[] = [{ templateId: primary.id, role: 'primary', x: tableX, y: tableY, w: primary.widthMm, h: primary.depthMm }]
  for (let index = 0; index < nx; index++) {
    const x = tableX + primary.widthMm / 2 + (index - (nx - 1) / 2) * 650 - chair.widthMm / 2
    items.push({ templateId: chair.id, role: 'seat', x, y: 0, w: chair.widthMm, h: chair.depthMm, rotation: 180 })
    items.push({ templateId: chair.id, role: 'seat', x, y: tableY + primary.depthMm + gap, w: chair.widthMm, h: chair.depthMm, rotation: 0 })
  }
  if (ny) {
    const y = tableY + (primary.depthMm - chair.depthMm) / 2
    items.push({ templateId: chair.id, role: 'seat', x: 0, y, w: chair.widthMm, h: chair.depthMm, rotation: 90 })
    items.push({ templateId: chair.id, role: 'seat', x: width - chair.widthMm, y, w: chair.widthMm, h: chair.depthMm, rotation: 270 })
  }
  return { categoryId, label: definition.label, width, height, items }
}

function createMeetingBundle(categoryId: string, definition: BundleDefinition, primary: NonNullable<ReturnType<typeof furnitureById.get>>): Bundle | null {
  const layout = meetingReferenceLayout(primary, primary.widthMm, primary.depthMm,
    { strategy: 'template', targetSeats: 0, includeChairs: true, resizeMeetingTable: false, seatClearanceMm: 300, channels: [] }, 0, 0, 900)
  if (!layout) return null
  const items = layout.pieces.map(piece => ({
    templateId: piece.template.id, role: piece.role, x: piece.x - piece.width / 2, y: piece.y - piece.depth / 2,
    w: piece.width, h: piece.depth, rotation: piece.rotation,
  }))
  const bounds = items.map(officeFurnitureFootprint)
  const minX = Math.min(...bounds.map(rect => rect.x)), minY = Math.min(...bounds.map(rect => rect.y))
  return {
    categoryId, label: definition.label, width: Math.max(...bounds.map(rect => rect.x + rect.w)) - minX,
    height: Math.max(...bounds.map(rect => rect.y + rect.h)) - minY,
    items: items.map(item => ({ ...item, x: item.x - minX, y: item.y - minY })),
  }
}

function createLoungeSceneBundle(categoryId: string, definition: BundleDefinition, primary: NonNullable<ReturnType<typeof furnitureById.get>>): Bundle | null {
  const gap = 300
  const accessories = (definition.accessories || []).flatMap(item => Array.from({ length: item.count || 1 }, () => ({ ...item, template: furnitureById.get(item.id) }))).filter(item => item.template)
  const table = accessories.find(item => item.role !== 'seat')?.template
  const chairs = accessories.filter(item => item.role === 'seat').map(item => item.template!)
  const items: BundleItem[] = []
  const tableWidth = table?.widthMm || 0
  const chairRowWidth = chairs.reduce((sum, chair) => sum + chair.widthMm, 0) + Math.max(0, chairs.length - 1) * gap
  const width = Math.max(primary.widthMm, tableWidth, chairRowWidth)
  const tableY = primary.depthMm + gap
  const chairsY = tableY + (table?.depthMm || 0) + (chairs.length ? gap : 0)
  const height = chairsY + (chairs.length ? Math.max(...chairs.map(chair => chair.depthMm)) : 0)
  items.push({ templateId: primary.id, role: 'primary', x: (width - primary.widthMm) / 2, y: 0, w: primary.widthMm, h: primary.depthMm, rotation: 180 })
  if (table) items.push({ templateId: table.id, role: 'primary', x: (width - table.widthMm) / 2, y: tableY, w: table.widthMm, h: table.depthMm })
  let x = (width - chairRowWidth) / 2
  for (const chair of chairs) {
    items.push({ templateId: chair.id, role: 'seat', x, y: chairsY, w: chair.widthMm, h: chair.depthMm })
    x += chair.widthMm + gap
  }
  return { categoryId, label: definition.label, width, height, items }
}

function createBundle(categoryId: string, zone: ZoneObject, strategy: StrategyId): Bundle | null {
  const index = MIXED_CATEGORY_IDS.indexOf(categoryId)
  const definition = MIXED_LAYOUT_CATEGORIES[index]
  if (!definition) return null
  const executive = categoryId === 'workstation' && zone.zoneType === 'EXECUTIVE_OFFICE'
  const primary = furnitureById.get(executive ? strategy === 'social' ? 'desk-executive' : 'desk-manager' : definition.primary)
  if (!primary) return null
  if (categoryId === 'meeting') return createMeetingBundle(categoryId, definition, primary)
  if (['tea', 'dining'].includes(categoryId)) {
    const chair = furnitureById.get('chair-visitor')
    if (chair) return createTableSceneBundle(categoryId, definition, primary, chair)
  }
  if (['sofa', 'lounge'].includes(categoryId)) return createLoungeSceneBundle(categoryId, definition, primary)
  const accessories = (definition.accessories || []).flatMap(item => Array.from({ length: item.count || 1 }, () => ({ ...item, template: furnitureById.get(item.id) }))).filter(item => item.template)
  const innerGap = 300
  const accessoryWidth = accessories.reduce((sum, item) => sum + item.template!.widthMm, 0) + Math.max(0, accessories.length - 1) * innerGap
  const width = Math.max(primary.widthMm, accessoryWidth)
  const accessoryHeight = accessories.length ? innerGap + Math.max(...accessories.map(item => item.template!.depthMm)) : 0
  const items: BundleItem[] = [{ templateId: primary.id, role: 'primary', x: (width - primary.widthMm) / 2, y: 0, w: primary.widthMm, h: primary.depthMm }]
  let x = 0
  for (const accessory of accessories) {
    items.push({ templateId: accessory.template!.id, role: accessory.role || 'primary', x: (width - accessoryWidth) / 2 + x, y: primary.depthMm + innerGap, w: accessory.template!.widthMm, h: accessory.template!.depthMm })
    x += accessory.template!.widthMm + innerGap
  }
  return { categoryId, label: definition.label, width, height: primary.depthMm + accessoryHeight, items }
}


type Rect = ReturnType<typeof officeFurnitureFootprint>
type RoomGeometry = {
  zone: ZoneObject
  bounds: ReturnType<typeof canvasZoneBounds>
  areas: PlacementArea[] | null
  blockers: ReturnType<typeof zoneLayoutExclusions>
  channels: ReturnType<typeof channelRects>
  entrances: Rect[]
}
type Placement = { items: FurnitureObject[]; cost: number }
type OccupiedRect = Rect & { mixedBundleId?: string }

function occupiedFootprint(item: FurnitureObject): OccupiedRect {
  return { ...officeFurnitureFootprint(item), mixedBundleId: item.mixedBundleId }
}

function separation(spacing: OfficeLayoutSpacing, occupied: OccupiedRect) {
  return occupied.mixedBundleId ? spacing.aisleMm : spacing.gapMm
}

export function mixedOfficeSeats(items: FurnitureObject[]) {
  return items.filter(item => item.layoutRole !== 'seat' && (item.furnitureTemplateId?.startsWith('desk-') || item.moduleSnapshot?.category === '职员桌')).length
}

function mixedGuestSeats(items: FurnitureObject[]) {
  return items.reduce((total, item) => {
    if (item.furnitureTemplateId === 'sofa-double') return total + 2
    if (item.furnitureTemplateId === 'sofa-triple') return total + 3
    const guest = item.layoutRole === 'seat' && !item.mixedBundleId?.includes(':workstation:')
    return total + Number(guest)
  }, 0)
}

export function mixedPlacementProblem(zone: ZoneObject, items: FurnitureObject[], spacing: OfficeLayoutSpacing, geometry = roomGeometry(zone)) {
  if (![spacing.wallMm, spacing.aisleMm, spacing.gapMm].every(value => Number.isFinite(value) && value >= 0)) return '间距必须为非负数'
  const footprints = items.map(officeFurnitureFootprint)
  const blocks = items.map(furnitureFootprintRects)
  for (const [i, item] of items.entries()) {
    if (!item.mixedBundleId) continue
    if (!canPlace(geometry, footprints[i]!, spacing.wallMm)) return '组合家具越界、离墙不足或占用建筑约束或通道'
    for (const [j, other] of items.entries()) {
      if (j === i || (other.mixedBundleId === item.mixedBundleId)) continue
      if (rectsOverlap(blocks[i]!, blocks[j]!, other.mixedBundleId ? spacing.aisleMm : spacing.gapMm)) return '组合家具的净距不足'
    }
  }
  return null
}

function roomGeometry(zone: ZoneObject): RoomGeometry {
  const blockers = zoneLayoutExclusions(zone)
  const entrances = blockers.filter(area => zone.obstacles?.some(obstacle => obstacle.kind === 'DOOR_CLEARANCE' && obstacle.obstacleId === area.id))
  return { zone, bounds: canvasZoneBounds(zone), areas: allowedPlacementAreas(zone), blockers, entrances, channels: channelRects(zone, zone.layoutPreferences?.channels || []) }
}

function canPlace(geometry: RoomGeometry, rect: Rect, wall: number) {
  if (!layoutRectInsideRing({ x: rect.x - wall, y: rect.y - wall, w: rect.w + 2 * wall, h: rect.h + 2 * wall }, geometry.zone.polygon || [])) return false
  const ring: Array<[number, number]> = [[rect.x, rect.y], [rect.x + rect.w, rect.y], [rect.x + rect.w, rect.y + rect.h], [rect.x, rect.y + rect.h]]
  return (geometry.areas === null || ringWithinPlacementAreas(geometry.areas, ring))
    && !geometry.blockers.some(blocker => rectOverlapsBlocker(rect, blocker))
    && !geometry.channels.some(channel => rectOverlapsBlocker(rect, channel))
}

function bundleFurniture(bundle: Bundle, x: number, y: number, angle: number, zoneId: string, spacing: OfficeLayoutSpacing, key: string): FurnitureObject[] {
  return bundle.items.map((item, index) => {
    const cx = item.x + item.w / 2, cy = item.y + item.h / 2
    const rotated = angle === 90 ? [bundle.height - cy, cx] : angle === 180 ? [bundle.width - cx, bundle.height - cy] : angle === 270 ? [cy, bundle.width - cx] : [cx, cy]
    return {
      id: `plan-mixed-${key}-${index}`, kind: 'furniture', x: x + rotated[0]! - item.w / 2, y: y + rotated[1]! - item.h / 2,
      w: item.w, h: item.h, rotation: ((item.rotation || 0) + angle) % 360, productId: '', zoneId,
      label: furnitureById.get(item.templateId)?.name || item.templateId, furnitureTemplateId: item.templateId,
      layoutGroupId: `auto:${zoneId}`, mixedBundleId: key, layoutRole: item.role,
      heightMm: furnitureById.get(item.templateId)?.heightMm,
      layoutSettings: { mode: 'auto', spacing: { ...spacing }, layoutContext: 'other', productStyleId: 'general', arrangementPresetId: 'generic-auto' },
    }
  })
}

function placements(bundle: Bundle, strategy: StrategyId, geometry: RoomGeometry, spacing: OfficeLayoutSpacing, taken: OccupiedRect[], key: string, branches: number): Placement[] {
  const { minX, minY, maxX, maxY } = geometry.bounds
  const wall = spacing.wallMm
  const cabinet = ['entry', 'bar'].includes(bundle.categoryId)
  const workspace = bundle.categoryId === 'workstation'
  const north = strategy !== 'workstations'
  const west = strategy !== 'social'
  const door = geometry.entrances[0]
  const placements: Placement[] = []
  for (const angle of (cabinet ? [0, 90, 180, 270] : workspace ? [0, 90, 180, 270] : [0, 90])) {
    const [w, h] = angle % 180 === 0 ? [bundle.width, bundle.height] : [bundle.height, bundle.width]
    const left = minX + wall, right = maxX - wall - w, top = minY + wall, bottom = maxY - wall - h
    if (right < left || bottom < top) continue
    const x0 = cabinet ? west ? left : right : workspace ? left : (left + right) / 2
    const y0 = cabinet ? (north ? top : bottom) : workspace ? north ? top : bottom : (top + bottom) / 2
    const step = Math.max(350, Math.max(right - left, bottom - top) / 16)
    const xs = new Set([left, right, (left + right) / 2, x0])
    const ys = new Set([top, bottom, (top + bottom) / 2, y0])
    // Concave boundaries can provide closer anchors than the bounding-box walls.
    for (const [x, y] of (geometry.zone.polygon || []).slice(0, 24)) {
      for (const anchor of [x + wall, x - wall - w]) if (anchor >= left && anchor <= right) xs.add(anchor)
      for (const anchor of [y + wall, y - wall - h]) if (anchor >= top && anchor <= bottom) ys.add(anchor)
    }
    for (let x = left; x <= right; x += step) xs.add(x)
    for (let y = top; y <= bottom; y += step) ys.add(y)
    // Also try exact alignments on both sides of existing pieces; limit the Cartesian grid in dense layouts.
    const nearby = taken.length > 8 ? taken.slice(-8) : taken
    for (const r of nearby) {
      const gap = separation(spacing, r)
      for (const x of [r.x - w - gap, r.x + r.w + gap]) if (x >= left && x <= right) xs.add(x)
      for (const y of [r.y - h - gap, r.y + r.h + gap]) if (y >= top && y <= bottom) ys.add(y)
    }
    const points: Array<{ x: number; y: number; cost: number }> = []
    for (const x of xs) for (const y of ys) {
      const wallAlign = angle % 180 === 0 ? Math.min(y - top, bottom - y) : Math.min(x - left, right - x)
      const doorDistance = door ? Math.hypot(x + w / 2 - door.x - door.w / 2, y + h / 2 - door.y - door.h / 2) : 0
      const chair = workspace ? bundle.items.find(item => item.role === 'seat') : undefined
      const chairAgainstWall = chair ? angle === 0 ? y + h + 400 > maxY : angle === 90 ? x < minX + 400 : angle === 180 ? y < minY + 400 : x + w + 400 > maxX : false
      const cost = Math.hypot(x - x0, y - y0) + (cabinet ? 2 * wallAlign : 0) + (chairAgainstWall ? 700 : 0)
        - (strategy === 'workstations' && workspace ? doorDistance * 0.15 : 0)
      points.push({ x, y, cost })
    }
    points.sort((a, b) => a.cost - b.cost)
    let accepted = 0
    for (const { x, y, cost } of points) {
      const items = bundleFurniture(bundle, x, y, angle, geometry.zone.id, spacing, key)
      const rects = items.map(officeFurnitureFootprint)
      if (rects.some((rect, i) => taken.some(other => rectOverlapsBlocker(rect, other, separation(spacing, other)))
        || rects.slice(0, i).some(other => rectOverlapsBlocker(rect, other)) || !canPlace(geometry, rect, wall))) continue
      placements.push({ items, cost })
      if (++accepted >= branches) break
    }
  }
  return placements.sort((a, b) => a.cost - b.cost).slice(0, branches)
}

function candidateFurnitureKey(furniture: FurnitureObject[]) {
  return furniture.map(item => [item.furnitureTemplateId, Math.round(item.x), Math.round(item.y), Math.round(item.w), Math.round(item.h), item.rotation % 360].join(':')).sort().join('|')
}

function accessQuality(geometry: RoomGeometry, items: FurnitureObject[], widths: number[]) {
  if (!geometry.entrances.length || !items.length) return null
  const { minX, minY, maxX, maxY } = geometry.bounds
  const step = Math.max(175, Math.max(maxX - minX, maxY - minY) / 50)
  const columns = Math.max(1, Math.ceil((maxX - minX) / step))
  const rows = Math.max(1, Math.ceil((maxY - minY) / step))
  const footprints = items.map(officeFurnitureFootprint)
  const targets = items.flatMap((item, i) => item.layoutRole === 'seat' || item.furnitureTemplateId?.startsWith('cabinet-') ? [footprints[i]!] : [])
  const distance = (a: Rect, b: Rect) => Math.hypot(Math.max(0, a.x - b.x - b.w, b.x - a.x - a.w), Math.max(0, a.y - b.y - b.h, b.y - a.y - a.h))
  for (const width of widths) {
    const open = new Uint8Array(columns * rows)
    const seen = new Uint8Array(open.length)
    const start = new Map<number, { id: number; distance: number }>()
    const cells: Rect[] = []
    for (let i = 0; i < open.length; i++) {
      const cell = { x: minX + (i % columns) * step + (step - width) / 2, y: minY + Math.floor(i / columns) * step + (step - width) / 2, w: width, h: width }
      cells.push(cell)
      if (!layoutRectInsideRing(cell, geometry.zone.polygon || []) || footprints.some(r => rectOverlapsBlocker(cell, r)) || geometry.blockers.some(r => !geometry.entrances.includes(r) && rectOverlapsBlocker(cell, r))) continue
      open[i] = 1
      geometry.entrances.forEach((door, index) => {
        const d = distance(cell, door)
        if (d <= step && (start.get(index)?.distance ?? Infinity) > d) start.set(index, { id: i, distance: d })
      })
    }
    // Check each entrance separately so disconnected components cannot jointly satisfy one route.
    for (const entry of start.values()) {
      seen.fill(0)
      const queue = [entry.id]
      seen[entry.id] = 1
      const reached = new Set<number>()
      for (let j = 0; j < queue.length; j++) {
        const id = queue[j]!
        targets.forEach((target, i) => { if (distance(cells[id]!, target) <= step * 1.5) reached.add(i) })
        const neighbors = [id - columns, id + columns, id % columns ? id - 1 : -1, id % columns < columns - 1 ? id + 1 : -1]
        for (const next of neighbors) if (open[next] && !seen[next]) { seen[next] = 1; queue.push(next) }
      }
      if (reached.size === targets.length) return width
    }
  }
  return 0
}

function operationClearanceCount(geometry: RoomGeometry, items: FurnitureObject[]) {
  const footprints = items.map(officeFurnitureFootprint)
  return items.filter((item, i) => {
    const chair = item.layoutRole === 'seat', cabinet = item.furnitureTemplateId?.startsWith('cabinet-')
    if (!chair && !cabinet) return false
    const angle = item.rotation * Math.PI / 180
    const clearance = chair ? 400 : 600
    const d = (item.h + clearance) / 2
    const cx = item.x + item.w / 2 - d * Math.sin(angle), cy = item.y + item.h / 2 + d * Math.cos(angle)
    const area = officeFurnitureFootprint({ x: cx - item.w / 2, y: cy - clearance / 2, w: item.w, h: clearance, rotation: item.rotation })
    return !layoutRectInsideRing(area, geometry.zone.polygon || []) || footprints.some((rect, j) => i !== j && rectOverlapsBlocker(area, rect))
      || geometry.blockers.some(rect => rectOverlapsBlocker(area, rect))
  }).length
}

export interface MixedLayoutOptions {
  objective?: LayoutObjective
  fixedQuantity?: number
  scoreMode?: LayoutScoreMode
}

export function planMixedLayout(zone: ZoneObject, existing: FurnitureObject[], categoryIds: string[], spacing: OfficeLayoutSpacing, targetSeats = 0, options: MixedLayoutOptions = {}): LayoutProposal {
  const replaceIds = existing.filter(item => item.layoutGroupId === `auto:${zone.id}`).map(item => item.id)
  const retained = existing.filter(item => !replaceIds.includes(item.id))
  const { objective, goal } = layoutObjectiveGoal({ objective: options.objective, targetSeats, fixedQuantity: options.fixedQuantity })
  const empty = { replaceIds, retainedIds: retained.map(item => item.id), furniture: [], seats: 0, shortfall: goal, label: '组合布局', sourceKey: JSON.stringify([zone, existing]), warnings: [] }
  if (!zone.polygon?.length || !categoryIds.length) return { ...empty, problem: '请先选择需要组合的功能模块' }
  if (![spacing.wallMm, spacing.aisleMm, spacing.gapMm].every(value => Number.isFinite(value) && value >= 0)
    || !Number.isInteger(goal) || goal < 0 || goal > 500) return { ...empty, problem: '请输入有效的间距与目标数量' }
  const geometry = roomGeometry(zone)
  if (geometry.areas !== null && !geometry.areas.length) return { ...empty, problem: '启用的布置区与空间没有可排布交集，请调整布置区' }
  const required = categoryIds.includes('workstation') ? objective === 'max-capacity' ? 500 : Math.max(1, goal) : 0
  const requests = objective === 'fixed-quantity' ? required : Math.max(0, required - mixedOfficeSeats(retained))
  const netArea = Math.max(1, ringArea(zone.polygon) - geometry.blockers.reduce((sum, area) => sum + area.w * area.h, 0))
  const floorArea = (items: FurnitureObject[]) => items.reduce((sum, item) => sum + item.w * item.h, 0)
  const labels: Record<StrategyId, string> = { workstations: '通行优先', social: '功能分区', walls: '沿墙收纳' }
  type PartialLayout = { furniture: FurnitureObject[]; rects: OccupiedRect[]; units: number; categories: Set<string>; cost: number }
  const candidates: Array<LayoutCandidate & { skipped: number; cost: number; units: number; clearanceIssues: number }> = []
  for (const strategy of STRATEGIES) {
    let beam: PartialLayout[] = [{ furniture: [], rects: retained.map(occupiedFootprint), units: 0, categories: new Set(), cost: 0 }]
    const order = strategy.order.filter(id => categoryIds.includes(id))
    // Keep non-office bundles in capacity exploration before repeatedly filling workstations.
    if (requests > 12 && order.includes('workstation')) order.push(...order.splice(order.indexOf('workstation'), 1))
    for (const id of order) {
      const count = id === 'workstation' ? requests : 1
      const bundle = createBundle(id, zone, strategy.id)
      if (!bundle) continue
      for (let index = 0; index < count; index++) {
        const key = `mixed:${id}:${index}`
        const width = requests > 12 ? 1 : 3
        const expanded = beam.flatMap(state => {
          if (id === 'workstation' && state.units !== index) return [state]
          const found = placements(bundle, strategy.id, geometry, spacing, state.rects, key, width)
          const next = found.map(result => ({
            furniture: [...state.furniture, ...result.items], rects: [...state.rects, ...result.items.map(occupiedFootprint)],
            units: state.units + Number(id === 'workstation'), categories: new Set([...state.categories, id]), cost: state.cost + result.cost,
          }))
          return next.length ? next : [state]
        }).sort((a, b) => b.categories.size - a.categories.size || b.units - a.units || a.cost - b.cost)
        if (id === 'workstation' && expanded.every(state => state.units <= index)) break
        beam = expanded.slice(0, width)
      }
    }
    for (const state of beam) {
      if (!state.furniture.length) continue
      const all = [...retained, ...state.furniture]
      const officeSeats = mixedOfficeSeats(all)
      const guestSeats = mixedGuestSeats(state.furniture)
      const missing = categoryIds.filter(id => !state.categories.has(id) && !(id === 'workstation' && requests === 0))
      const selectedPassage = Math.max(300, spacing.aisleMm)
      const passageMm = accessQuality(geometry, all, [...new Set([selectedPassage, 600, 450, 300])].sort((a, b) => b - a))
      const clearanceIssues = operationClearanceCount(geometry, all)
      const area = floorArea(all)
      const utilization = Math.min(1, area / netArea), seats = officeSeats + guestSeats
      const shortfall = Math.max(0, goal - (objective === 'fixed-quantity' ? state.units : officeSeats))
      const metrics = { utilization, freeAreaPerSeatM2: seats ? Math.max(0, (netArea - area) / seats / 1_000_000) : null }
      const notes = [...missing.map(id => `未放入：${MIXED_LAYOUT_CATEGORIES[MIXED_CATEGORY_IDS.indexOf(id)]?.label || id}`)]
      if (passageMm === null) notes.push('未标注入口，通行待核验')
      else if (!passageMm) notes.push('未找到连接入口和主要家具的采样通路')
      else notes.push(`入口通路估计 ≥ ${passageMm} mm`)
      if (clearanceIssues) notes.push(`${clearanceIssues} 处椅后或柜前净空需复核`)
      const scoreDetail = layoutCandidateScoreDetail({ seats, placed: objective === 'fixed-quantity' ? state.units : officeSeats, goal, ...metrics, requestedModules: categoryIds.length, placedModules: categoryIds.length - missing.length })
      const topologyKey = state.furniture.map(item => {
        const rect = officeFurnitureFootprint(item)
        const { minX, minY, maxX, maxY } = geometry.bounds
        const x = Math.min(2, Math.floor(3 * (rect.x + rect.w / 2 - minX) / (maxX - minX)))
        const y = Math.min(2, Math.floor(3 * (rect.y + rect.h / 2 - minY) / (maxY - minY)))
        return [item.furnitureTemplateId, item.rotation, x, y].join(':')
      }).sort().join('|')
      candidates.push({ furniture: state.furniture, seats, officeSeats, guestSeats, shortfall, label: labels[strategy.id], metrics, scoreDetail, topologyKey, notes, passageMm,
        spacing: { ...spacing }, skipped: missing.length, units: state.units, clearanceIssues, cost: state.cost })
    }
  }
  candidates.sort((a, b) => a.shortfall - b.shortfall || a.skipped - b.skipped
    || (objective === 'max-capacity' ? b.units - a.units : 0)
    || (b.passageMm ?? -1) - (a.passageMm ?? -1) || a.clearanceIssues - b.clearanceIssues
    || (options.scoreMode === 'balanced' ? (b.scoreDetail?.total ?? 0) - (a.scoreDetail?.total ?? 0) : 0) || a.cost - b.cost)
  const keys = new Set<string>()
  const topologies = new Set<string | undefined>()
  const distinct = candidates.filter(item => {
    const key = candidateFurnitureKey(item.furniture)
    if (keys.has(key) || topologies.has(item.topologyKey)) return false
    keys.add(key)
    topologies.add(item.topologyKey)
    return true
  })
  // Surface distinct design intents before adding another candidate of the same intent.
  const selected: LayoutCandidate[] = []
  for (const item of distinct) if (!selected.some(other => other.label === item.label)) selected.push(item)
  const top = [...selected, ...distinct.filter(item => !selected.includes(item)).map(item => ({ ...item, label: item.label + ' · 备选' }))].slice(0, 3)
  const best = top[0]
  if (!best) return { ...empty, problem: '所选功能模块在当前空间放不下，请减少模块或调整尺寸／间距', problemAdvice: layoutFailureAdvice(zone, goal), warnings: ['所选组合与现有家具、建筑约束或通道冲突'] }
  return {
    ...empty, ...best, candidates: top, problem: null,
    warnings: [
      ...(objective === 'max-capacity' ? ['最大容量为当前搜索结果，非施工或法规核准容量'] : []),
      ...facilityLayoutHints(zone, best.furniture.map(item => ({ id: item.id, label: item.label, ring: [[item.x, item.y], [item.x + item.w, item.y], [item.x + item.w, item.y + item.h], [item.x, item.y + item.h]] as Array<[number, number]> }))).map(hint => hint.message),
    ],
  }
}



export const REFERENCE_TEMPLATES: Record<string, string[]> = {
  workstation: ['desk-straight', 'desk-l', 'desk-manager', 'desk-manager-l', 'desk-executive', 'desk-executive-l', 'chair-office', 'chair-executive'],
  entry: ['cabinet-entry', 'cabinet-low', 'cabinet-high', 'cabinet-short'],
  sofa: ['sofa-double', 'coffee-round', 'coffee-rect', 'chair-lounge'],
  lounge: ['sofa-triple', 'chair-lounge', 'coffee-round', 'coffee-rect'],
  meeting: ['table-conference-rect', 'chair-meeting', 'chair-meeting-mid-back'],
  tea: ['table-tea-rect', 'chair-visitor'],
  dining: ['table-dining-rect', 'chair-visitor'],
  bar: ['counter-bar', 'chair-bar'],
  leisure: ['table-game', 'chair-lounge', 'plant'],
}

export function deleteReferenceSelection(state: LayoutReferenceEditing) {
  if (state.selected === null) return
  state.marks.splice(state.selected, 1)
  state.selected = null
  state.placement = null
}

export function rotateReferenceSelection(state: LayoutReferenceEditing) {
  if (state.placement) {
    state.placement.rotation = (state.placement.rotation + 90) % 360
    return
  }
  const mark = state.selected === null ? null : state.marks[state.selected]
  const crop = state.crop
  if (!mark || !crop) return
  const width = mark.depth * crop.h / crop.w, depth = mark.width * crop.w / crop.h
  Object.assign(mark, { x: mark.x + (mark.width - width) / 2, y: mark.y + (mark.depth - depth) / 2, width, depth,
    rotation: (mark.rotation + 90) % 360, confidence: 'manual' })
}

export function referenceFurniture(mark: LayoutReferenceMark, crop: LayoutReference['crop'], zoneId: string, id: string): FurnitureObject | null {
  const template = furnitureById.get(mark.templateId)
  const footprintW = mark.width * crop.w, footprintH = mark.depth * crop.h
  if (!template || footprintW < 10 || footprintH < 10) return null
  const cx = crop.x + (mark.x + mark.width / 2) * crop.w, cy = crop.y + (mark.y + mark.depth / 2) * crop.h
  const [w, h] = mark.rotation % 180 === 0 ? [footprintW, footprintH] : [footprintH, footprintW]
  return {
    id, kind: 'furniture', x: cx - w / 2, y: cy - h / 2, w, h, rotation: mark.rotation, zoneId,
    productId: '', furnitureTemplateId: template.id, label: template.name, heightMm: template.heightMm,
    layoutRole: template.kind === 'chair' ? 'seat' : 'primary',
  }
}


/** Convert the edited drawing directly to the same snapshot used by layout application. */
export function referenceLayoutSnapshot(zone: ZoneObject, existing: FurnitureObject[], categoryIds: string[], reference: LayoutReference): LayoutProposal {
  const replaceIds = existing.filter(item => item.layoutGroupId === `auto:${zone.id}`).map(item => item.id)
  const retainedIds = existing.filter(item => !replaceIds.includes(item.id)).map(item => item.id)
  const base = { replaceIds, retainedIds, furniture: [], seats: 0, shortfall: 0, label: '参考原图', warnings: [], sourceKey: JSON.stringify([zone, existing]) }
  const marks = reference.marks.filter(mark => reference.includeOtherCategories || categoryIds.includes(mark.categoryId))
  if (!marks.length) return { ...base, problem: '请选择要应用的家具，或勾选纳入其他功能的家具' }
  const pending = marks.filter(mark => mark.confidence === 'low').length
  if (pending) return { ...base, problem: `还有 ${pending} 件家具待确认，请确认或删除后再应用` }
  const furniture: FurnitureObject[] = []
  for (const [index, mark] of marks.entries()) {
    const item = referenceFurniture(mark, reference.crop, zone.id, `plan-ref-${index}`)
    if (!item || !REFERENCE_TEMPLATES[mark.categoryId]?.includes(mark.templateId)) return { ...base, problem: '有家具的图例或尺寸无效，请调整后再应用' }
    furniture.push({
      ...item, layoutGroupId: `auto:${zone.id}`, mixedBundleId: `ref:${mark.categoryId}:${mark.group}`,
      layoutReference: { analysisId: reference.analysisId, revision: reference.revision, evidence: mark.confidence === 'manual' ? 'MANUAL' : 'ESTIMATED' },
    })
  }
  return { ...base, furniture, seats: mixedOfficeSeats(furniture) + mixedGuestSeats(furniture), problem: null }
}
