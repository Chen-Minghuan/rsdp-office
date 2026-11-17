import type { LayoutExclusion } from '~/types/layoutPlanning'
import { ringsOverlap } from './spatial/review'

export type LayoutBlocker = { x: number; y: number; w: number; h: number; polygon?: Array<[number, number]> }

export function rotatedFurnitureFootprint(item: LayoutBlocker & { rotation: number }) {
  const radians = item.rotation * Math.PI / 180
  const w = Math.abs(item.w * Math.cos(radians)) + Math.abs(item.h * Math.sin(radians))
  const h = Math.abs(item.w * Math.sin(radians)) + Math.abs(item.h * Math.cos(radians))
  return { x: item.x + (item.w - w) / 2, y: item.y + (item.h - h) / 2, w, h }
}

export function rectOverlapsBlocker(rect: LayoutBlocker, blocker: LayoutBlocker | LayoutExclusion, gap = 0) {
  if (!blocker.polygon?.length) return rect.x < blocker.x + blocker.w + gap - 0.001 && blocker.x < rect.x + rect.w + gap - 0.001 && rect.y < blocker.y + blocker.h + gap - 0.001 && blocker.y < rect.y + rect.h + gap - 0.001
  const expanded = { x: rect.x - gap, y: rect.y - gap, w: rect.w + gap * 2, h: rect.h + gap * 2 }
  const ring: Array<[number, number]> = [[expanded.x, expanded.y], [expanded.x + expanded.w, expanded.y], [expanded.x + expanded.w, expanded.y + expanded.h], [expanded.x, expanded.y + expanded.h]]
  return ringsOverlap(ring, blocker.polygon)
}
