import type { CoordinateSystem, Point } from '~/types/spatial'

export interface CameraTransform {
  panX: number
  panY: number
  zoom: number
}

export interface AffineMatrix {
  a: number
  b: number
  c: number
  d: number
  e: number
  f: number
}

export function matrixFromArray(m: number[]): AffineMatrix {
  return { a: m[0], b: m[1], c: m[2], d: m[3], e: m[4], f: m[5] }
}

export function applyMatrix(m: AffineMatrix, p: Point): Point {
  return {
    x: m.a * p.x + m.c * p.y + m.e,
    y: m.b * p.x + m.d * p.y + m.f,
  }
}

export function inverseMatrix(m: AffineMatrix): AffineMatrix | null {
  const det = m.a * m.d - m.b * m.c
  if (Math.abs(det) < 1e-12) return null
  return {
    a: m.d / det,
    b: -m.b / det,
    c: -m.c / det,
    d: m.a / det,
    e: (m.c * m.f - m.d * m.e) / det,
    f: (m.b * m.e - m.a * m.f) / det,
  }
}

/**
 * 统一坐标到屏幕变换。
 * - SOURCE_PAGE：与屏幕同向（Y 向下），直接应用相机缩放/平移。
 * - WORLD_MM：Y 向上，需要翻转 Y。
 */
export function toScreen(
  p: Point,
  coordinateSystem: CoordinateSystem,
  camera: CameraTransform,
  sourceToWorld?: AffineMatrix | null,
): Point {
  if (coordinateSystem === 'SOURCE_PAGE') {
    return {
      x: p.x * camera.zoom + camera.panX,
      y: p.y * camera.zoom + camera.panY,
    }
  }
  // WORLD_MM
  return {
    x: p.x * camera.zoom + camera.panX,
    y: -p.y * camera.zoom + camera.panY,
  }
}

export function fromScreen(
  p: Point,
  coordinateSystem: CoordinateSystem,
  camera: CameraTransform,
): Point {
  if (coordinateSystem === 'SOURCE_PAGE') {
    return {
      x: (p.x - camera.panX) / camera.zoom,
      y: (p.y - camera.panY) / camera.zoom,
    }
  }
  // WORLD_MM
  return {
    x: (p.x - camera.panX) / camera.zoom,
    y: -(p.y - camera.panY) / camera.zoom,
  }
}

export function sourceToWorldPoint(p: Point, matrix: AffineMatrix): Point {
  return applyMatrix(matrix, p)
}

export function worldToSourcePoint(p: Point, matrix: AffineMatrix): Point | null {
  const inv = inverseMatrix(matrix)
  if (!inv) return null
  return applyMatrix(inv, p)
}

export function fitCameraToBounds(
  bounds: { minX: number; minY: number; maxX: number; maxY: number },
  viewportW: number,
  viewportH: number,
  padding = 40,
): CameraTransform {
  return fitCameraToBoundsBySystem(bounds, viewportW, viewportH, 'SOURCE_PAGE', padding)
}

export function fitCameraToBoundsBySystem(
  bounds: { minX: number; minY: number; maxX: number; maxY: number },
  viewportW: number,
  viewportH: number,
  coordinateSystem: CoordinateSystem,
  padding = 40,
): CameraTransform {
  const contentW = Math.max(1, bounds.maxX - bounds.minX)
  const contentH = Math.max(1, bounds.maxY - bounds.minY)
  const scale = Math.min(
    (viewportW - padding * 2) / contentW,
    (viewportH - padding * 2) / contentH,
    2,
  )
  const cx = (bounds.minX + bounds.maxX) / 2
  const cy = (bounds.minY + bounds.maxY) / 2
  // WORLD_MM 的 Y 向上，屏幕 Y 向下，因此 panY 需要取反
  const ySign = coordinateSystem === 'WORLD_MM' ? 1 : -1
  return {
    zoom: scale,
    panX: viewportW / 2 - cx * scale,
    panY: viewportH / 2 + ySign * cy * scale,
  }
}
