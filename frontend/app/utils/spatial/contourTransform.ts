export type ContourPoint = { x: number; y: number }
export type ResizeHandle = 'nw' | 'n' | 'ne' | 'e' | 'se' | 's' | 'sw' | 'w'
export type ContourOperation = ResizeHandle | 'move' | { edge: number }
type Ring = Array<[number, number]>

/** Design coordinates, Y down, in the zone's own mm/source unit. No camera state. */
export function transformContour(ring: Ring, operation: ContourOperation, start: ContourPoint, current: ContourPoint, keepAspect = false): Ring {
  const dx = current.x - start.x, dy = current.y - start.y
  if (operation === 'move') return ring.map(([x, y]) => [x + dx, y + dy])
  if (typeof operation === 'object') {
    const i = operation.edge, j = (i + 1) % ring.length, a = ring[i], b = ring[j]
    const ex = b[0] - a[0], ey = b[1] - a[1], length2 = ex * ex + ey * ey
    if (!length2) return ring.map(([x, y]) => [x, y])
    const offset = (-ey * dx + ex * dy) / length2
    return ring.map(([x, y], index) => index === i || index === j ? [x - ey * offset, y + ex * offset] : [x, y])
  }
  const xs = ring.map(p => p[0]), ys = ring.map(p => p[1])
  const left = Math.min(...xs), right = Math.max(...xs), top = Math.min(...ys), bottom = Math.max(...ys)
  const width = right - left, height = bottom - top
  if (width <= 0 || height <= 0) return ring.map(([x, y]) => [x, y])
  const horizontal = operation.includes('w') ? -1 : operation.includes('e') ? 1 : 0
  const vertical = operation.includes('n') ? -1 : operation.includes('s') ? 1 : 0
  let sx = horizontal ? Math.max(1e-6, (width + horizontal * dx) / width) : 1
  let sy = vertical ? Math.max(1e-6, (height + vertical * dy) / height) : 1
  if (keepAspect && horizontal && vertical) {
    sx = sy = Math.abs(dx / width) > Math.abs(dy / height) ? sx : sy
  }
  const anchorX = horizontal < 0 ? right : left, anchorY = vertical < 0 ? bottom : top
  return ring.map(([x, y]) => [anchorX + (x - anchorX) * sx, anchorY + (y - anchorY) * sy])
}
