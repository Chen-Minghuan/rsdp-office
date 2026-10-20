import type { ZoneObject } from '~/types/office'
import type { LayoutRect } from '~/utils/layout'
import type { OfficeLayoutPlan, OfficeLayoutSpacing } from './officeAutoLayout'
import { layoutRectInsideRing, OFFICE_LAYOUT_SPACING } from './officeAutoLayout'
import type { OfficeArrangementPresetId, OfficeProductStyleId } from '~/types/officeArrangement'
import { officeArrangementPreset, OFFICE_ARRANGEMENT_RULE_VERSION } from './officeArrangementPresets'
import { canvasZoneBounds } from './officeCanvasView'

type Rect = { x: number; y: number; w: number; h: number }
const EPS = 0.001
const MAX_MODULES = 2000
const overlaps = (a: Rect, b: Rect, gap: number) => a.x < b.x + b.w + gap - EPS && b.x < a.x + a.w + gap - EPS && a.y < b.y + b.h + gap - EPS && b.y < a.y + a.h + gap - EPS

/** Repeat the smallest intact reference cell; the reference illustration is not a capacity limit. */
export function planAdaptiveArrangement(zone: ZoneObject, w: number, d: number, style: OfficeProductStyleId, presetId: OfficeArrangementPresetId, input?: OfficeLayoutSpacing, blockers: Rect[] = [], target = 0): OfficeLayoutPlan {
  const empty = (problem: string): OfficeLayoutPlan => ({ slots: [], rows: 0, columns: 0, rotated: false, problem, presetId, productStyleId: style })
  const preset = officeArrangementPreset(presetId)
  if (!preset?.styleIds.includes(style) || presetId.startsWith('generic-') || style === 'meeting-table') return empty('请选择当前产品样式对应的排布方式')
  const spacing = { ...OFFICE_LAYOUT_SPACING, ...preset.defaults, ...(input || {}) }
  const gap = spacing.faceGapMm ?? preset.defaults.faceGapMm ?? 900
  if (zone.reviewUnit !== 'mm' || !zone.polygon?.length || ![w, d, spacing.wallMm, spacing.aisleMm, gap, target].every(v => Number.isFinite(v) && v >= 0) || w < 10 || d < 10 || !Number.isInteger(target)) return empty('请检查空间、毫米尺寸和间距')
  const bounds = canvasZoneBounds(zone)
  const spanX = bounds.maxX - bounds.minX - spacing.wallMm * 2, spanY = bounds.maxY - bounds.minY - spacing.wallMm * 2
  const motif: LayoutRect[] = []
  const add = (x: number, y: number, rotation = 0, mirrorX = false) => motif.push({ x, y, w, h: d, rotation, ...(mirrorX ? { mirrorX: true } : {}) })
  let cellW = w, cellH = d, gapX = 0, gapY = gap
  if (presetId === 'screen-t-face-to-face') {
    cellW = w * 2; gapX = gap; gapY = 0
    add(0, 0); add(w, 0, 0, true)
  } else if (presetId === 'screen-c-face-to-face') {
    cellW = w * 2; cellH = d * 2; gapX = gap
    add(0, 0); add(w, 0, 0, true); add(0, d, 180, true); add(w, d, 180)
  } else if (presetId === 'screen-cross-face-to-face') {
    cellW = w * 2; cellH = d * 2
    add(0, 0, 180, true); add(w, 0, 180); add(0, d); add(w, d, 0, true)
  } else if (presetId.endsWith('face-to-face')) {
    cellH = d * 2
    add(0, 0, 180, true); add(0, d)
  } else {
    // Same-direction row/F layouts can grow by one workstation at a time.
    add(0, 0)
  }
  const fit = (span: number, size: number, spacing: number) => Math.max(0, Math.floor((span + spacing + EPS) / (size + spacing)))
  const candidates: OfficeLayoutPlan[] = []
  let oversized = false
  for (const turned of [false, true]) {
    const width = turned ? spanY : spanX, height = turned ? spanX : spanY
    const nx = fit(width, cellW, gapX), ny = fit(height, cellH, gapY)
    if (!nx || !ny) continue
    if (nx * ny * motif.length > MAX_MODULES) { oversized = true; continue }
    const usedW = nx * cellW + (nx - 1) * gapX, usedH = ny * cellH + (ny - 1) * gapY
    // Compare centered and wall-aligned grids; concave rooms and retained furniture
    // may admit a different grid origin without changing the reference topology.
    const offsetsX = [...new Set([(width - usedW) / 2, 0, width - usedW])]
    const offsetsY = [...new Set([(height - usedH) / 2, 0, height - usedH])]
    for (const ox of offsetsX) for (const oy of offsetsY) {
      const slots: LayoutRect[] = []
      for (let row = 0; row < ny; row++) for (let col = 0; col < nx; col++) {
        if (target && slots.length >= target) continue
        const clusterId = `${OFFICE_ARRANGEMENT_RULE_VERSION}:${row}:${col}`
        const cell = motif.map(part => {
          const x = ox + col * (cellW + gapX) + part.x, y = oy + row * (cellH + gapY) + part.y
          return { ...part, clusterId,
            x: bounds.minX + spacing.wallMm + (turned ? spanX - y - part.h : x),
            y: bounds.minY + spacing.wallMm + (turned ? x : y),
            w: turned ? part.h : part.w, h: turned ? part.w : part.h,
            rotation: (part.rotation + (turned ? 90 : 0)) % 360,
          }
        })
        const valid = cell.every(slot => layoutRectInsideRing({ x: slot.x - spacing.wallMm, y: slot.y - spacing.wallMm, w: slot.w + spacing.wallMm * 2, h: slot.h + spacing.wallMm * 2 }, zone.polygon!) && !blockers.some(block => overlaps(slot, block, spacing.aisleMm)))
        // A facing pair/T pair/C/cross cell is either kept whole or skipped whole.
        if (valid) slots.push(...cell)
      }
      if (slots.length) candidates.push({ slots, rows: new Set(slots.map(s => Math.round(s.y * 1000))).size, columns: new Set(slots.map(s => Math.round(s.x * 1000))).size, rotated: turned, problem: null, presetId, productStyleId: style })
    }
  }
  candidates.sort((a, b) => {
    const score = (plan: OfficeLayoutPlan) => target ? Math.min(target, plan.slots.length) * 1000 - Math.max(0, plan.slots.length - target) : plan.slots.length
    return score(b) - score(a)
  })
  return candidates[0] || empty(oversized ? '预计模块超过 2000 件，请增大尺寸或缩小排版范围' : '当前空间、保留家具和预留区域无法容纳此排布单元，请调整尺寸或间距')
}
