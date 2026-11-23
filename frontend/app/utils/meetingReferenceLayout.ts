import type { FurnitureTemplate } from '~/types/manualCanvas'
import type { LayoutPreferences } from '~/types/layoutPlanning'
import { meetingRingTable } from './meetingRingTable'
import { meetingFurnitureTemplates } from './meetingFurnitureCatalog'

export const TRAINING_ARRANGEMENTS = [
  { id: 'classroom', name: 'L17 单向课堂' },
  { id: 'islands', name: 'L18 双岛分组' },
  { id: 'u', name: 'L19 U 型' },
  { id: 'o', name: 'L20 O 型' },
  { id: 'fan', name: 'L21 阶梯扇形' },
] as const

export type TrainingArrangement = typeof TRAINING_ARRANGEMENTS[number]['id']
type Placement = { template: FurnitureTemplate; x: number; y: number; rotation: number; role: 'primary' | 'seat'; width: number; depth: number }
const byCode = new Map(meetingFurnitureTemplates.map(template => [template.reference!.code, template]))

const references: Record<string, { name: string; chair: string; pitch: number; pattern: string; ends?: number }> = {
  T01: { name: 'L01 双人对坐', chair: 'S02', pitch: 800, pattern: 'opposite' },
  T02: { name: 'L02 四席围坐', chair: 'S01', pitch: 700, pattern: 'round' },
  T03: { name: 'L05 双侧四席', chair: 'S03', pitch: 700, pattern: 'sides' },
  T04: { name: 'L06 八席主桌', chair: 'S04', pitch: 800, pattern: 'sides', ends: 1100 },
  T05: { name: 'L07 主桌与旁听席', chair: 'S05', pitch: 700, pattern: 'sides', ends: 1400 },
  T06: { name: 'L08 分段圆环', chair: 'S05', pitch: 780, pattern: 'round' },
  T07: { name: 'L09 跑道形环桌', chair: 'S06', pitch: 700, pattern: 'race' },
  T08: { name: 'L10 两人同屏', chair: 'S07', pitch: 700, pattern: 'single' },
  T09: { name: 'L11 小船型四席', chair: 'S07', pitch: 800, pattern: 'boat' },
  T10: { name: 'L12 船型八席', chair: 'S04', pitch: 650, pattern: 'boat' },
  T11: { name: '单侧弧形视频桌', chair: 'S08', pitch: 650, pattern: 'single' },
  T12: { name: 'L13 双翼 V 形', chair: 'S08', pitch: 720, pattern: 'v' },
  T13: { name: 'L14 靠墙短谈', chair: 'S11', pitch: 600, pattern: 'single' },
  T14: { name: 'L15 四席吧台', chair: 'S11', pitch: 600, pattern: 'single' },
  T16: { name: '旁听条桌', chair: 'S09', pitch: 700, pattern: 'single' },
  T22: { name: '紧凑弧边视频桌', chair: 'S07', pitch: 700, pattern: 'single' },
}

export function meetingReferenceName(code?: string, training?: TrainingArrangement) {
  if (!code) return ''
  if (code === 'S12') return 'L03 沙发接待 · U 型'
  if (code === 'S13') return 'L04 沙发接待 · 口型'
  if (code === 'T15') return 'L16 独立围站'
  if (code === 'T17' || code === 'T18') return (TRAINING_ARRANGEMENTS.find(item => item.id === training) || TRAINING_ARRANGEMENTS[0]).name
  return references[code]?.name || ''
}

export function meetingSeatsForItem(item: { furnitureTemplateId?: string; moduleSnapshot?: { kind?: string } }) {
  const id = item.furnitureTemplateId || ''
  if (id === 'sofa-meeting-double') return 2
  if (id === 'sofa-meeting-single') return 1
  if (id === 'chair-meeting-observer') return 0
  return Number(id.startsWith('chair-') || item.moduleSnapshot?.kind === 'chair')
}

function centered(count: number, pitch: number) {
  return Array.from({ length: Math.min(count, 100) }, (_, index) => (index - (Math.min(count, 100) - 1) / 2) * pitch)
}

/** Local centers relative to the primary table. The solver validates the whole arrangement in room coordinates. */
export function meetingReferenceLayout(module: FurnitureTemplate, w: number, d: number, prefs: LayoutPreferences, gap: number, goal: number, aisle: number) {
  const code = module.reference?.code
  if (!code) return null
  const name = meetingReferenceName(code, prefs.meetingTrainingArrangement)
  const pieces: Placement[] = []
  const add = (template: FurnitureTemplate, x: number, y: number, rotation = 0, role: Placement['role'] = 'primary', width = template.widthMm, depth = template.depthMm) => {
    pieces.push({ template, x, y, rotation, role, width, depth })
  }
  const addChair = (chair: FurnitureTemplate, x: number, y: number, rotation: number) => {
    if (prefs.includeChairs) add(chair, x, y, rotation, 'seat')
  }
  const clearance = Math.max(prefs.seatClearanceMm, gap)
  // All PDF chairs have their backs at the top; zero rotation faces down.
  const along = (chair: FurnitureTemplate, x: number, y: number, outward: number, turnout = 0, curveAllowance = 0) => {
    const angle = outward * Math.PI / 180
    const offset = clearance + chair.depthMm / 2 + (turnout ? 80 : 0) + curveAllowance
    addChair(chair, x + Math.cos(angle) * offset, y + Math.sin(angle) * offset, outward + 90 + turnout)
  }
  const sides = (chair: FurnitureTemplate, length: number, edge: number, pitch: number, signs = [-1, 1], turnout = 0) => {
    const xs = centered(Math.floor((length + 0.001) / pitch), pitch).sort((a, b) => Math.abs(a) - Math.abs(b))
    for (const x of xs) for (const sign of signs) along(chair, x, sign * edge, sign * 90, sign * turnout)
  }
  if (code === 'S12' || code === 'S13') {
    const side = byCode.get('S15')!, chair = byCode.get('S14')!
    const seatSpread = Math.max(w, d) + side.widthMm / 2 + gap * 2
    const across = w / 2 + side.widthMm / 2 + gap
    const flank = across + w / 2 + d / 2 + 150 + gap
    for (const sign of [-1, 1]) {
      add(module, sign * across, -seatSpread, 0, 'primary', w, d)
      if (code === 'S12') {
        for (const y of [0, 2 * across]) add(module, sign * flank, y, sign * 90, 'primary', w, d)
        add(side, sign * flank, across)
      } else {
        add(module, sign * flank, 0, sign * 90, 'primary', w, d)
        const bottom = seatSpread + chair.depthMm / 2 + gap
        addChair(chair, sign * across, bottom, 180)
        add(side, sign * (across + (chair.widthMm + side.widthMm) / 2 + gap), bottom)
      }
    }
    add(side, 0, -seatSpread)
    return { name, pieces }
  }
  if (code === 'T17' || code === 'T18') {
    const chair = byCode.get('S10')!, pitch = Math.max(600, chair.widthMm + gap)
    const seatsPerTable = Math.floor(w / pitch)
    const arrangement = prefs.meetingTrainingArrangement || 'classroom'
    const count = Math.min(100, goal && seatsPerTable ? Math.ceil(goal / seatsPerTable) : 8)
    const table = (x: number, y: number, rotation: number) => {
      add(module, x, y, rotation, 'primary', w, d)
      const a = rotation * Math.PI / 180
      const offset = d / 2 + clearance + chair.depthMm / 2
      for (const dx of centered(seatsPerTable, pitch)) addChair(chair, x + Math.cos(a) * dx - Math.sin(a) * offset, y + Math.sin(a) * dx + Math.cos(a) * offset, rotation + 180)
    }
    if (arrangement === 'u' || arrangement === 'o') {
      const cross = Math.max(1, Math.round(count / 4)), wings = Math.max(1, Math.ceil((count - cross * (arrangement === 'o' ? 2 : 1)) / 2))
      const halfWidth = cross * (w + gap) / 2
      const end = wings * (w + gap) / 2 - d / 2
      for (const sign of [-1, 1]) for (const y of centered(wings, w + gap)) table(sign * (halfWidth + d / 2 + gap), y, sign === -1 ? 90 : 270)
      for (const x of centered(cross, w + gap)) {
        table(x, end, 0)
        if (arrangement === 'o') table(x, -end, 180)
      }
    } else if (arrangement === 'islands') {
      const columns = Math.max(1, Math.ceil(count / 4))
      const distance = (columns * (w + gap) + Math.max(aisle, 600)) / 2
      for (const sign of [-1, 1]) for (const x of centered(columns, w + gap)) for (const row of [-1, 1]) {
        table(sign * distance + x, row * (d + gap) / 2, row > 0 ? 0 : 180)
      }
    } else {
      const rowStep = d + chair.depthMm + clearance + Math.max(aisle, gap)
      const rows = arrangement === 'fan' ? [Math.max(1, Math.floor(count * 3 / 8)), Math.max(0, count - Math.max(1, Math.floor(count * 3 / 8)))]
        : Array.from({ length: Math.ceil(count / 2) }, (_, i) => Math.min(2, count - i * 2))
      rows.forEach((columns, row) => {
        for (const x of centered(columns, w + (arrangement === 'fan' ? Math.max(aisle, 550) : Math.max(aisle, 600)))) {
          const turn = arrangement === 'fan' && columns > 1 ? -x / ((columns - 1) * (w + Math.max(aisle, 550)) / 2) * (row ? 38 : 24) : 0
          table(x, row * rowStep - Math.abs(turn) * 10, turn)
        }
      })
    }
    return { name, pieces }
  }
  const reference = references[code]
  add(module, 0, 0, 0, 'primary', w, d)
  if (!reference || !prefs.includeChairs) return { name: name || module.name, pieces }
  const chair = byCode.get(reference.chair)!
  const pitch = Math.max(reference.pitch, chair.widthMm + gap)
  const ring = meetingRingTable(module.id, w, d)
  if (reference.pattern === 'opposite') {
    for (const sign of [-1, 1]) along(chair, sign * w / 2, 0, sign > 0 ? 0 : 180)
  } else if (reference.pattern === 'round') {
    const count = Math.min(100, Math.floor(Math.PI * Math.sqrt((w * w + d * d) / 2) / pitch))
    const start = code === 'T02' ? 0 : 0.5
    for (let i = 0; i < count; i++) {
      const angle = (i + start) * 2 * Math.PI / count
      const outward = Math.atan2(w * Math.sin(angle), d * Math.cos(angle)) * 180 / Math.PI
      along(chair, w / 2 * Math.cos(angle), d / 2 * Math.sin(angle), outward, 0, Math.max(w, d) * 0.002)
    }
  } else if (reference.pattern === 'race' && ring) {
    sides(chair, ring.straightRun, d / 2, pitch)
    const count = Math.min(50, Math.floor(Math.PI * ring.radiusX / Math.max(780, chair.widthMm + gap)))
    for (let i = 0; i < count; i++) for (const sign of [-1, 1]) {
      const angle = (i + 0.5) * Math.PI / count - Math.PI / 2
      along(chair, sign * (ring.straightRun / 2 + ring.radiusX * Math.cos(angle)), ring.radiusY * Math.sin(angle), sign > 0 ? angle * 180 / Math.PI : 180 - angle * 180 / Math.PI, 0, d * 0.002)
    }
  } else if (reference.pattern === 'v') {
    const wingW = w / 2, wingD = d * 0.838, length = Math.hypot(wingW, wingD)
    const count = Math.min(50, Math.floor(length / pitch))
    for (let i = 0; i < count; i++) for (const sign of [-1, 1]) {
      const t = (i + 0.5) / count
      along(chair, sign * t * wingW, d / 2 - t * wingD, 90 - sign * Math.atan2(wingD, wingW) * 180 / Math.PI)
    }
  } else {
    sides(chair, w, d / 2, pitch, reference.pattern === 'single' ? [1] : [-1, 1], reference.pattern === 'boat' ? 12 : 0)
    if (reference.ends) {
      for (const y of centered(Math.floor(d / Math.max(chair.widthMm + gap, reference.ends)), Math.max(chair.widthMm + gap, reference.ends))) {
        for (const sign of [-1, 1]) along(chair, sign * w / 2, y, sign > 0 ? 0 : 180)
      }
    }
  }
  if (code === 'T05') {
    const table = byCode.get('T16')!, observer = byCode.get('S09')!
    const row = d / 2 + clearance + chair.depthMm + Math.max(aisle, gap, 450) + table.depthMm / 2
    for (const x of centered(Math.max(1, Math.round(w / 2100)), table.widthMm + gap)) {
      add(table, x, row)
      for (const offset of centered(2, Math.max(700, observer.widthMm + gap))) along(observer, x + offset, row + table.depthMm / 2, 90)
    }
  }
  // The video boat's long axis points at the screen end, shown at the top in the PDF.
  if (reference.pattern === 'boat') for (const piece of pieces) {
    const x = piece.x
    piece.x = -piece.y
    piece.y = x
    piece.rotation += 90
  }
  if ((reference.pattern === 'round' || reference.pattern === 'race') && goal > 0 && goal < pieces.length - 1) {
    const perimeter = pieces.slice(1).sort((a, b) => Math.atan2(a.y, a.x) - Math.atan2(b.y, b.x))
    const chosen = new Set(Array.from({ length: goal }, (_, index) => Math.round(index * perimeter.length / goal) % perimeter.length))
    const selected = [...chosen].map(index => perimeter[index]!)
    return { name, pieces: [pieces[0]!, ...selected, ...perimeter.filter((_, index) => !chosen.has(index))] }
  }
  return { name, pieces }
}
