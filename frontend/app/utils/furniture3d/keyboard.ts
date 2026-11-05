export interface FurnitureKeyboardInput {
  key: string
  shiftKey?: boolean
  altKey?: boolean
  ctrlKey?: boolean
  metaKey?: boolean
}

/** 当前视角向右在画布毫米平面的方向；向上对应远离相机的地面方向。 */
export function furnitureKeyboardDelta(event: FurnitureKeyboardInput, viewRight: { x: number; y: number }): { dx: number; dy: number } | null {
  if (event.ctrlKey || event.metaKey) return null
  const length = Math.hypot(viewRight.x, viewRight.y)
  if (!Number.isFinite(length) || length < 1e-6) return null
  const step = event.altKey ? 1 : event.shiftKey ? 100 : 10
  const x = viewRight.x / length * step, y = viewRight.y / length * step
  switch (event.key) {
    case 'ArrowRight': return { dx: x, dy: y }
    case 'ArrowLeft': return { dx: -x, dy: -y }
    case 'ArrowUp': return { dx: -y, dy: x }
    case 'ArrowDown': return { dx: y, dy: -x }
    default: return null
  }
}
