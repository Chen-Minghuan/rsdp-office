/**
 * 无界毫米画布相机（源自早期手动布局沙盒相机，已适配空间核对场景）。
 * 缩放只改变视野，不改变布局的世界坐标。
 */

interface LegacyCanvasCamera {
  x: number
  y: number
  zoom: number
}

interface LegacyCanvasViewport {
  widthPx: number
  heightPx: number
  initialWidthMm: number
  initialHeightMm: number
}

interface LegacyCanvasBounds {
  minX: number
  minY: number
  maxX: number
  maxY: number
}

function legacyCameraSize(viewport: LegacyCanvasViewport, zoom: number): { width: number; height: number } {
  const ratio = Math.max(1, viewport.widthPx) / Math.max(1, viewport.heightPx)
  const baseWidth = Math.max(viewport.initialWidthMm, viewport.initialHeightMm * ratio)
  const width = baseWidth / zoom
  return { width, height: width / ratio }
}

/** 保持鼠标所在的世界点不动，适用于滚轮和工具栏按钮。 */
function legacyZoomCameraAt(
  camera: LegacyCanvasCamera,
  viewport: LegacyCanvasViewport,
  factor: number,
  anchorX: number,
  anchorY: number,
): LegacyCanvasCamera {
  const zoom = Math.min(16, Math.max(0.0000001, camera.zoom * factor))
  const before = legacyCameraSize(viewport, camera.zoom)
  const after = legacyCameraSize(viewport, zoom)
  const fx = Math.min(1, Math.max(0, anchorX / Math.max(1, viewport.widthPx)))
  const fy = Math.min(1, Math.max(0, anchorY / Math.max(1, viewport.heightPx)))
  return {
    x: camera.x + fx * (before.width - after.width),
    y: camera.y + fy * (before.height - after.height),
    zoom,
  }
}

/** 空布局回到起始视野；非空布局按全部空间包围盒留白后适配。 */
function legacyFitCamera(viewport: LegacyCanvasViewport, bounds?: LegacyCanvasBounds): LegacyCanvasCamera {
  if (!bounds) return { x: 0, y: 0, zoom: 1 }
  const width = Math.max(1, bounds.maxX - bounds.minX)
  const height = Math.max(1, bounds.maxY - bounds.minY)
  const marginX = Math.max(500, width * 0.08)
  const marginY = Math.max(500, height * 0.08)
  const fitted = {
    width: width + marginX * 2,
    height: height + marginY * 2,
  }
  const base = legacyCameraSize(viewport, 1)
  const zoom = Math.min(16, Math.max(0.0000001, Math.min(base.width / fitted.width, base.height / fitted.height)))
  const size = legacyCameraSize(viewport, zoom)
  return {
    x: (bounds.minX + bounds.maxX - size.width) / 2,
    y: (bounds.minY + bounds.maxY - size.height) / 2,
    zoom,
  }
}

/** 计算一组多边形的包围盒（毫米）。 */
export function polygonBounds(polygons: Array<Array<[number, number]>> | undefined): LegacyCanvasBounds | undefined {
  if (!polygons || polygons.length === 0) return undefined
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  for (const polygon of polygons) {
    for (const [x, y] of polygon) {
      minX = Math.min(minX, x)
      minY = Math.min(minY, y)
      maxX = Math.max(maxX, x)
      maxY = Math.max(maxY, y)
    }
  }
  if (!isFinite(minX)) return undefined
  return { minX, minY, maxX, maxY }
}
