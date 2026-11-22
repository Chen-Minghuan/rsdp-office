import type { FurnitureObject } from '~/types/office'
import { furnitureById } from '~/utils/manualFurniture'
import { rotatedFurnitureFootprint } from '~/utils/layoutConstraints'
import { workstationSeatGeometry } from '~/utils/furniture3d/workstationSeating'

export interface FootprintRect {
  x: number
  y: number
  w: number
  h: number
}

const EPSILON = 0.001
const L_DESK_KINDS = ['l-desk', 'rounded-l-desk']

type FootprintSource = Pick<FurnitureObject, 'x' | 'y' | 'w' | 'h' | 'rotation' | 'mirrorX'
  | 'wingWidthMm' | 'furnitureTemplateId' | 'moduleSnapshot'>

export function furnitureKind(item: FootprintSource): string {
  return item.moduleSnapshot?.kind || furnitureById.get(item.furnitureTemplateId || '')?.kind || ''
}

/**
 * 家具的有效占地面：L 型桌由「主台面 + 侧翼」两块组成，内角留空；
 * 其余家具仍是一块旋转外接矩形，保持既有判定行为。
 */
export function furnitureFootprintRects(item: FootprintSource): FootprintRect[] {
  const kind = furnitureKind(item)
  if (!L_DESK_KINDS.includes(kind)) return [rotatedFurnitureFootprint(item as FurnitureObject)]
  const shape = workstationSeatGeometry(item as FurnitureObject, kind)
  if (!shape || shape.wing <= 0 || shape.desktopDepth <= 0 || shape.desktopDepth >= item.h) {
    return [rotatedFurnitureFootprint(item as FurnitureObject)]
  }
  const blocks = [
    { x: 0, y: 0, w: item.w, h: shape.desktopDepth },
    { x: item.w - shape.wing, y: shape.desktopDepth, w: shape.wing, h: item.h - shape.desktopDepth },
  ]
  return blocks.map(block => rotatedBlock(item, block))
}

export function rectsOverlap(first: FootprintRect[], second: FootprintRect[], gap = 0): boolean {
  return first.some(a => second.some(b =>
    a.x < b.x + b.w + gap - EPSILON && b.x < a.x + a.w + gap - EPSILON
    && a.y < b.y + b.h + gap - EPSILON && b.y < a.y + a.h + gap - EPSILON))
}

export function footprintsOverlap(first: FootprintSource, second: FootprintSource, gap = 0): boolean {
  return rectsOverlap(furnitureFootprintRects(first), furnitureFootprintRects(second), gap)
}

/** 矩形（如椅子活动区、禁放区）与家具占地面是否重叠。 */
export function rectOverlapsFootprints(rect: FootprintRect, item: FootprintSource, gap = 0): boolean {
  return rectsOverlap([rect], furnitureFootprintRects(item), gap)
}

/** 把本地矩形按镜像与旋转换算为画布坐标下的外接矩形。 */
function rotatedBlock(item: FootprintSource, block: FootprintRect): FootprintRect {
  const left = item.mirrorX ? item.w - block.x - block.w : block.x
  const radians = item.rotation * Math.PI / 180
  const cos = Math.cos(radians), sin = Math.sin(radians)
  const cx = item.w / 2, cy = item.h / 2
  const corners: Array<[number, number]> = [
    [left, block.y], [left + block.w, block.y], [left + block.w, block.y + block.h], [left, block.y + block.h],
  ].map(([x, y]) => {
    const dx = x - cx, dy = y - cy
    return [cx + dx * cos - dy * sin, cy + dx * sin + dy * cos]
  })
  const xs = corners.map(point => point[0]), ys = corners.map(point => point[1])
  const minX = Math.min(...xs), minY = Math.min(...ys)
  return {
    x: item.x + snap(minX),
    y: item.y + snap(minY),
    w: snap(Math.max(...xs) - minX),
    h: snap(Math.max(...ys) - minY),
  }
}

/** 直角旋转会产生 1e-13 级误差，吸附到毫米以下 3 位，避免贴边被判成重叠。 */
function snap(value: number): number {
  return Math.round(value * 1000) / 1000
}
