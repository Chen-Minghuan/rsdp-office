export interface ProductImagePreviewInput {
  anchor: { left: number; right: number; top: number }
  boundary?: { left: number; right: number; top: number; bottom: number }
  viewportWidth: number
  viewportHeight: number
  naturalWidth: number
  naturalHeight: number
  hasSide: boolean
  sideContentHeight: number
  leftGap: number
}

export interface ProductImagePreviewRect {
  left: number
  top: number
  width: number
  height: number
  imageWidth: number
  imageHeight: number
  sideWidth: number
  baseWidth: number
  baseHeight: number
  contentScale: number
}

export type ProductImagePreviewStyle = Record<string, string>

// 推荐卡片只在不缩小文字的情况下悬停展示；其余情况由组件改为点击完整预览。
export function shouldUseClickPreview(rect: Pick<ProductImagePreviewRect, 'contentScale'>): boolean {
  return rect.contentScale < 1
}

const VIEWPORT_MARGIN = 12
const ANCHOR_GAP = 16
const IMAGE_MAX_WIDTH = 560
const IMAGE_MAX_HEIGHT = 620
const SIDE_PREFERRED_WIDTH = 196
const SIDE_GAP = 14
const SIDE_PADDING = 24

export function calculateProductImagePreview(input: ProductImagePreviewInput): ProductImagePreviewRect {
  const viewportWidth = Math.max(1, input.viewportWidth)
  const viewportHeight = Math.max(1, input.viewportHeight)
  const boundary = {
    left: Math.max(0, Math.min(input.boundary?.left ?? 0, viewportWidth)),
    right: Math.max(0, Math.min(input.boundary?.right ?? viewportWidth, viewportWidth)),
    top: Math.max(0, Math.min(input.boundary?.top ?? 0, viewportHeight)),
    bottom: Math.max(0, Math.min(input.boundary?.bottom ?? viewportHeight, viewportHeight)),
  }
  const availableFrameWidth = Math.max(1, boundary.right - boundary.left - VIEWPORT_MARGIN * 2)
  const availableFrameHeight = Math.max(1, boundary.bottom - boundary.top - VIEWPORT_MARGIN * 2)
  const framePadding = input.hasSide ? 32 : 24
  const columnGap = input.hasSide ? SIDE_GAP : 0
  const sideWidth = input.hasSide ? SIDE_PREFERRED_WIDTH : 0
  const naturalWidth = Math.max(1, input.naturalWidth)
  const naturalHeight = Math.max(1, input.naturalHeight)
  const imageScale = Math.min(IMAGE_MAX_WIDTH / naturalWidth, IMAGE_MAX_HEIGHT / naturalHeight, 1)
  const imageWidth = naturalWidth * imageScale
  const imageHeight = naturalHeight * imageScale
  const sideHeight = input.hasSide ? input.sideContentHeight + SIDE_PADDING : 0
  const baseWidth = imageWidth + sideWidth + columnGap + framePadding
  const baseHeight = Math.max(imageHeight, sideHeight) + framePadding
  const contentScale = Math.min(1, availableFrameWidth / baseWidth, availableFrameHeight / baseHeight)
  const width = baseWidth * contentScale
  const height = baseHeight * contentScale

  const rightCandidate = input.anchor.right + ANCHOR_GAP
  const leftCandidate = input.anchor.left - input.leftGap - width
  const minLeft = boundary.left + VIEWPORT_MARGIN
  const maxLeft = boundary.right - VIEWPORT_MARGIN - width
  const left = rightCandidate >= minLeft && rightCandidate + width <= boundary.right - VIEWPORT_MARGIN
    ? rightCandidate
    : leftCandidate >= minLeft && leftCandidate + width <= boundary.right - VIEWPORT_MARGIN
      ? leftCandidate
      : Math.max(minLeft, Math.min(input.anchor.left, maxLeft))
  const minTop = boundary.top + VIEWPORT_MARGIN
  const top = Math.max(
    minTop,
    Math.min(input.anchor.top - 20, boundary.bottom - VIEWPORT_MARGIN - height),
  )

  return { left, top, width, height, imageWidth, imageHeight, sideWidth, baseWidth, baseHeight, contentScale }
}

export function productImagePreviewStyle(rect: ProductImagePreviewRect): ProductImagePreviewStyle {
  return {
    left: `${rect.left}px`,
    top: `${rect.top}px`,
    width: `${rect.width}px`,
    height: `${rect.height}px`,
    '--pip-image-width': `${rect.imageWidth}px`,
    '--pip-side-width': `${rect.sideWidth}px`,
    '--pip-base-width': `${rect.baseWidth}px`,
    '--pip-base-height': `${rect.baseHeight}px`,
    '--pip-content-scale': String(rect.contentScale),
  }
}
