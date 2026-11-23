import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'

async function loadTypeScript(path) {
  const source = stripTypeScriptTypes(await readFile(path, 'utf8'))
  return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

const { calculateProductImagePreview, productImagePreviewStyle, shouldUseClickPreview } = await import(await loadTypeScript(new URL('../app/utils/productImagePreview.ts', import.meta.url)))

function assertInsideViewport(rect, viewportWidth, viewportHeight) {
  assert.ok(rect.left >= 12, `left edge ${rect.left}px is inside the safety margin`)
  assert.ok(rect.top >= 12, `top edge ${rect.top}px is inside the safety margin`)
  assert.ok(rect.left + rect.width <= viewportWidth - 12, `right edge ${rect.left + rect.width}px is inside ${viewportWidth - 12}px`)
  assert.ok(rect.top + rect.height <= viewportHeight - 12, `bottom edge ${rect.top + rect.height}px is inside ${viewportHeight - 12}px`)
}

function assertInsideBoundary(rect, boundary) {
  assert.ok(rect.left >= boundary.left + 12, `left edge ${rect.left}px is inside canvas ${boundary.left + 12}px`)
  assert.ok(rect.top >= boundary.top + 12, `top edge ${rect.top}px is inside canvas ${boundary.top + 12}px`)
  assert.ok(rect.left + rect.width <= boundary.right - 12, `right edge ${rect.left + rect.width}px is inside canvas ${boundary.right - 12}px`)
  assert.ok(rect.top + rect.height <= boundary.bottom - 12, `bottom edge ${rect.top + rect.height}px is inside canvas ${boundary.bottom - 12}px`)
}

function assertRatio(rect, width, height, message) {
  assert.ok(Math.abs(rect.imageWidth / rect.imageHeight - width / height) < 1e-10, message)
}

const ultraWide = calculateProductImagePreview({
  anchor: { left: 980, right: 1052, top: 160 },
  viewportWidth: 1200,
  viewportHeight: 800,
  naturalWidth: 1600,
  naturalHeight: 300,
  hasSide: true,
  sideContentHeight: 260,
  leftGap: 23,
})
assertInsideViewport(ultraWide, 1200, 800)
assertRatio(ultraWide, 1600, 300, 'ultra-wide image keeps its natural ratio')
assert.ok(ultraWide.sideWidth > 0, 'ultra-wide image keeps a visible data sidebar')

const narrow = calculateProductImagePreview({
  anchor: { left: 620, right: 692, top: 180 },
  viewportWidth: 768,
  viewportHeight: 640,
  naturalWidth: 1200,
  naturalHeight: 900,
  hasSide: true,
  sideContentHeight: 360,
  leftGap: 23,
})
assertInsideViewport(narrow, 768, 640)
assertRatio(narrow, 1200, 900, 'narrow viewport does not distort the image')
assert.equal(narrow.sideWidth, 196, 'narrow viewport keeps the sidebar in the same proportional layout')
assert.ok(narrow.contentScale < 1, 'narrow viewport scales the complete card instead of clipping it')
assert.equal(shouldUseClickPreview(narrow), true, 'recommendation cards switch to click preview instead of shrinking text')
assert.equal(narrow.width, narrow.baseWidth * narrow.contentScale, 'frame width is the proportionally scaled card width')
assert.equal(narrow.height, narrow.baseHeight * narrow.contentScale, 'frame height is the proportionally scaled card height')
assert.deepEqual(productImagePreviewStyle(narrow), {
  left: `${narrow.left}px`,
  top: `${narrow.top}px`,
  width: `${narrow.width}px`,
  height: `${narrow.height}px`,
  '--pip-image-width': `${narrow.imageWidth}px`,
  '--pip-side-width': `${narrow.sideWidth}px`,
  '--pip-base-width': `${narrow.baseWidth}px`,
  '--pip-base-height': `${narrow.baseHeight}px`,
  '--pip-content-scale': String(narrow.contentScale),
}, 'component style uses the single geometry result for frame and columns')

const sidePaddingBudget = calculateProductImagePreview({
  anchor: { left: 800, right: 872, top: 100 },
  viewportWidth: 1200,
  viewportHeight: 800,
  naturalWidth: 1600,
  naturalHeight: 300,
  hasSide: true,
  sideContentHeight: 260,
  leftGap: 23,
})
assert.equal(sidePaddingBudget.height, 316, 'frame height includes 32px frame padding and 24px sidebar padding')

const tallSide = calculateProductImagePreview({
  anchor: { left: 400, right: 472, top: 80 },
  viewportWidth: 900,
  viewportHeight: 500,
  naturalWidth: 800,
  naturalHeight: 600,
  hasSide: true,
  sideContentHeight: 900,
  leftGap: 23,
})
assertInsideViewport(tallSide, 900, 500)
assert.equal(tallSide.height, 476, 'tall sidebar is capped by 12px top and bottom safety margins')
assert.ok(tallSide.contentScale < 1, 'tall sidebar scales with the complete card instead of being clipped or scrolled')
assert.equal(shouldUseClickPreview(tallSide), true, 'insufficient height also switches recommendation cards to click preview')

const ultraTall = calculateProductImagePreview({
  anchor: { left: 300, right: 372, top: 100 },
  viewportWidth: 1000,
  viewportHeight: 600,
  naturalWidth: 300,
  naturalHeight: 1600,
  hasSide: true,
  sideContentHeight: 200,
  leftGap: 23,
})
assertInsideViewport(ultraTall, 1000, 600)
assertRatio(ultraTall, 300, 1600, 'ultra-tall image keeps its natural ratio')

const imageOnly = calculateProductImagePreview({
  anchor: { left: 600, right: 672, top: 200 },
  viewportWidth: 1440,
  viewportHeight: 900,
  naturalWidth: 2000,
  naturalHeight: 2000,
  hasSide: false,
  sideContentHeight: 0,
  leftGap: 16,
})
assert.equal(imageOnly.imageWidth, 560, 'image-only preview keeps the existing 560px width cap')
assert.equal(imageOnly.imageHeight, 560, 'image-only square preview keeps its natural ratio within the 620px height cap')
assertInsideViewport(imageOnly, 1440, 900)

const canvasBoundary = { left: 362, right: 1335, top: 320, bottom: 920 }
const canvasConstrained = calculateProductImagePreview({
  anchor: { left: 1370, right: 1426, top: 600 },
  boundary: canvasBoundary,
  viewportWidth: 1892,
  viewportHeight: 982,
  naturalWidth: 1600,
  naturalHeight: 1000,
  hasSide: true,
  sideContentHeight: 390,
  leftGap: 23,
})
assertInsideBoundary(canvasConstrained, canvasBoundary)
assertRatio(canvasConstrained, 1600, 1000, 'canvas-constrained image keeps its natural ratio')
assert.equal(shouldUseClickPreview(canvasConstrained), false, 'a full-size card that fits the canvas keeps hover preview')
assert.equal(shouldUseClickPreview({ contentScale: 1 }), false, 'the cutoff does not require a readability scale tolerance')
assert.equal(shouldUseClickPreview({ contentScale: 0.999 }), true, 'any text scaling activates click preview')

console.log('PASS: responsive product image preview geometry')
