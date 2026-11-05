/**
 * 3D 场景选中与拖拽的纯判定逻辑（RENDER-004），与 three.js 解耦以便单测。
 */

/** 点击与拖动的位移阈值（屏幕像素）：小于阈值视为点击（选中/取消），超过才进入拖动。 */
export const FURNITURE_DRAG_THRESHOLD_PX = 6

export function shouldStartDrag(downX: number, downY: number, moveX: number, moveY: number, thresholdPx = FURNITURE_DRAG_THRESHOLD_PX): boolean {
  return Math.hypot(moveX - downX, moveY - downY) > thresholdPx
}

/**
 * 场景重建/数据变化后的选中 reconciliation：
 * 对应家具仍然存在（同空间、未删除）则保持选中，否则清空。
 */
export function reconcileSelection(selectedId: string, availableIds: readonly string[]): string {
  return selectedId && availableIds.includes(selectedId) ? selectedId : ''
}
