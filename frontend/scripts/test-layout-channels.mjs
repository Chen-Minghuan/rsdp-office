import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'
const cache = new Map()
async function load(path) {
  if (cache.has(path.href)) return cache.get(path.href)
  let source = stripTypeScriptTypes(await readFile(path, 'utf8'))
  const imports = [...source.matchAll(/(?:from\s*|import\s*)['"]([^'"]+)['"]/g)]
  for (const match of imports) {
    const spec = match[1]
    if (spec === 'polygon-clipping') {
      source = source.replaceAll(`'${spec}'`, `'${new URL('../node_modules/polygon-clipping/dist/polygon-clipping.esm.js', import.meta.url).href}'`)
      continue
    }
    if (!spec.startsWith('.') && !spec.startsWith('~/')) continue
    const resolved = new URL((spec.startsWith('~/') ? '../app/' + spec.slice(2) : spec) + (spec.endsWith('.ts') ? '' : '.ts'), spec.startsWith('~/') ? import.meta.url : path)
    source = source.replaceAll(`'${spec}'`, `'${await load(resolved)}'`)
  }
  const url = 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
  cache.set(path.href, url)
  return url
}
const { clipRectToRingRects } = await import(await load(new URL('../app/utils/spatial/geometry.ts', import.meta.url)))
const { channelRects, axisSegments, clampChannelsToZone, validateChannels } = await import(await load(new URL('../app/utils/layoutChannels.ts', import.meta.url)))
const { doorClearanceObstacle } = await import(await load(new URL('../app/utils/spatial/obstacles.ts', import.meta.url)))
const { channelEgressHints, MIN_PASSAGE_MM } = await import(await load(new URL('../app/utils/channelEgress.ts', import.meta.url)))

const rect = (x, y, w, h) => ({ x, y, w, h })
// 矩形空间：裁剪输出与输入逐值一致（回归基线）。
const room = [[0, 0], [6000, 0], [6000, 4000], [0, 4000]]
assert.deepEqual(clipRectToRingRects(rect(1000, 0, 900, 4000), room), [rect(1000, 0, 900, 4000)], 'rect room keeps band identical')
// U 形（顶部中间缺角 x∈[2500,4500], y∈[0,2000]）。
const uRing = [[0, 0], [2500, 0], [2500, 2000], [4500, 2000], [4500, 0], [7000, 0], [7000, 5000], [0, 5000]]
assert.deepEqual(clipRectToRingRects(rect(3000, 0, 900, 5000), uRing), [rect(3000, 2000, 900, 3000)], 'vertical band over notch starts below notch')
assert.deepEqual(clipRectToRingRects(rect(0, 500, 7000, 900), uRing), [rect(0, 500, 2500, 900), rect(4500, 500, 2500, 900)], 'horizontal band across notch splits into two segments')
// L 形（右下缺角 x>3000, y>1000）。
const lRing = [[0, 0], [6000, 0], [6000, 1000], [3000, 1000], [3000, 4000], [0, 4000]]
assert.deepEqual(clipRectToRingRects(rect(4000, 0, 900, 4000), lRing), [rect(4000, 0, 900, 1000)], 'L notch truncates band to top arm')
assert.deepEqual(clipRectToRingRects(rect(1000, 0, 900, 4000), lRing), [rect(1000, 0, 900, 4000)], 'band inside solid leg stays full depth')
// 完全落在多边形外 / 无效输入。
assert.deepEqual(clipRectToRingRects(rect(4000, 1500, 900, 2000), lRing), [], 'band fully in missing corner yields nothing')
assert.deepEqual(clipRectToRingRects(rect(0, 0, 0, 100), room), [], 'degenerate rect')
assert.deepEqual(clipRectToRingRects(rect(0, 0, 100, 100), [[0, 0], [1, 1]]), [], 'degenerate ring')

// channelRects：矩形空间与旧行为一致（含净距包围盒钳制）。
const rectZone = { id: 'Z', reviewUnit: 'mm', polygon: room, x: 0, y: 0, w: 6000, h: 4000 }
assert.deepEqual(channelRects(rectZone, [{ id: 'c', orientation: 'vertical', offsetMm: 1000, widthMm: 900 }]), [rect(1000, 0, 900, 4000)])
assert.deepEqual(channelRects(rectZone, [{ id: 'c', orientation: 'vertical', offsetMm: 100, widthMm: 900, clearanceMm: 300 }]), [rect(0, 0, 1300, 4000)], 'clearance clipped by bbox')
// 凹形空间：一条通道输出多段 / 截断。
const uZone = { id: 'Z', reviewUnit: 'mm', polygon: uRing, x: 0, y: 0, w: 7000, h: 5000 }
assert.deepEqual(channelRects(uZone, [{ id: 'c', orientation: 'vertical', offsetMm: 3000, widthMm: 900 }]), [rect(3000, 2000, 900, 3000)], 'channel clipped by notch')
assert.deepEqual(channelRects(uZone, [{ id: 'c', orientation: 'horizontal', offsetMm: 500, widthMm: 900 }]), [rect(0, 500, 2500, 900), rect(4500, 500, 2500, 900)], 'horizontal channel splits at notch')
// fromSecondRow 先于多边形裁剪应用。
assert.deepEqual(channelRects(uZone, [{ id: 'c', orientation: 'vertical', offsetMm: 1000, widthMm: 900, fromSecondRow: true }], 1200), [rect(1000, 1200, 900, 3800)], 'fromSecondRow shifts start before clipping')
// 无多边形数据退化为包围盒单矩形。
const legacyZone = { id: 'Z', reviewUnit: 'mm', x: 0, y: 0, w: 6000, h: 4000 }
assert.deepEqual(channelRects(legacyZone, [{ id: 'c', orientation: 'horizontal', offsetMm: 1500, widthMm: 900 }]), [rect(0, 1500, 6000, 900)], 'no polygon falls back to bbox band')

// axisSegments 仍按未裁剪禁放带全轴切段（LAYOUT-027 定稿语义）。
assert.deepEqual(axisSegments(0, 6000, 300, [{ offsetMm: 2500, widthMm: 900 }]), [{ start: 300, length: 2200 }, { start: 3400, length: 2300 }])
assert.deepEqual(axisSegments(0, 6000, 300, [{ offsetMm: 2500, widthMm: 900, clearanceMm: 200 }]), [{ start: 300, length: 2000 }, { start: 3600, length: 2100 }], 'clearance widens the cut')
// 端点留断（LAYOUT-028）：startMm/endMm 收窄走向范围，缺省贯通；越界自动按包围盒收敛。
assert.deepEqual(channelRects(rectZone, [{ id: 'c', orientation: 'vertical', offsetMm: 1000, widthMm: 900, startMm: 1500, endMm: 3000 }]), [rect(1000, 1500, 900, 1500)], 'vertical endpoints bound the span')
assert.deepEqual(channelRects(rectZone, [{ id: 'c', orientation: 'horizontal', offsetMm: 1000, widthMm: 900, startMm: 2000 }]), [rect(2000, 1000, 4000, 900)], 'horizontal start only, open end')
assert.deepEqual(channelRects(rectZone, [{ id: 'c', orientation: 'vertical', offsetMm: 1000, widthMm: 900, startMm: -500, endMm: 99000 }]), [rect(1000, 0, 900, 4000)], 'out-of-range endpoints clamp to bbox')
assert.deepEqual(channelRects(rectZone, [{ id: 'c', orientation: 'vertical', offsetMm: 1000, widthMm: 900, startMm: 3000, endMm: 3000 }]), [], 'empty span yields no band')
// 端点与二排起取交集：startMm 更靠下时保持 startMm。
assert.deepEqual(channelRects(rectZone, [{ id: 'c', orientation: 'vertical', offsetMm: 1000, widthMm: 900, fromSecondRow: true, startMm: 2000 }], 1200), [rect(1000, 2000, 900, 2000)], 'startMm wins when deeper than first block')
assert.deepEqual(channelRects(rectZone, [{ id: 'c', orientation: 'vertical', offsetMm: 1000, widthMm: 900, fromSecondRow: true, endMm: 3000 }], 1200), [rect(1000, 1200, 900, 1800)], 'fromSecondRow shifts start, endMm bounds the end')
// 端点与多边形裁剪叠加：先收窄再裁剪。
assert.deepEqual(channelRects(uZone, [{ id: 'c', orientation: 'vertical', offsetMm: 3000, widthMm: 900, endMm: 4500 }]), [rect(3000, 2000, 900, 2500)], 'endpoints compose with polygon clipping')
assert.equal(validateChannels(rectZone, [{ id: 'c', orientation: 'vertical', offsetMm: 1000, widthMm: 900, startMm: 3000, endMm: 2000 }]), '通道起点必须小于终点')
assert.equal(validateChannels(rectZone, [{ id: 'c', orientation: 'vertical', offsetMm: 1000, widthMm: 900, startMm: NaN }]), '通道起点必须是有效毫米数')
// clamp/validate 基本行为保持。
assert.deepEqual(clampChannelsToZone(rectZone, [{ id: 'c', orientation: 'vertical', offsetMm: -500, widthMm: 900 }])[0].offsetMm, 0)
assert.equal(validateChannels(rectZone, [{ id: 'c', orientation: 'vertical', offsetMm: 9000, widthMm: 900 }]), '纵向通道不在空间范围内，请调整位置')

// 通道-入口/疏散联动提示（LAYOUT-029，提示级不拦截）。门洞净空存储坐标 Y 向上（mm 空间读取时翻转）。
const egressRoom = [[0, 0], [8000, 0], [8000, 6000], [0, 6000]]
const egressZone = { id: 'Z', reviewUnit: 'mm', polygon: egressRoom, x: 0, y: 0, w: 8000, h: 6000 }
const egressChannel = [{ id: 'c', orientation: 'vertical', offsetMm: 4000, widthMm: 900 }] // 带 x[4000,4900] 全深
// 无门洞净空标注：只提示未核验。
assert.deepEqual(channelEgressHints(egressZone, egressChannel, []), ['未标注门洞净空（入口）：通道与入口的连通未核验，可在空间核对中补充标注'])
// 门在下墙（存储铰链 y=-6000 → 画布 y=6000），与通道下端头衔接：无提示。
const doorBottom = doorClearanceObstacle('Z', { x: 4400, y: -6000 }, { x: 5300, y: -5100 })
assert.deepEqual(channelEgressHints({ ...egressZone, obstacles: [doorBottom] }, egressChannel, []), [], 'channel end linked to door gives no hint')
// 门在左下角远离通道：两端都未衔接。
const doorFar = doorClearanceObstacle('Z', { x: 100, y: -6000 }, { x: 1000, y: -5100 })
assert.equal(channelEgressHints({ ...egressZone, obstacles: [doorFar] }, egressChannel, []).length, 1, 'door out of reach warns')
assert.match(channelEgressHints({ ...egressZone, obstacles: [doorFar] }, egressChannel, [])[0], /两端都未衔接/)
// 衔接走廊被家具挤占到净宽不足：走廊 x[4000,5300]×y[5100,6000]，纵向家具贯穿走廊全高。
const blocker = { id: 'F', zoneId: 'Z', x: 4600, y: 5000, w: 100, h: 1200, rotation: 0 }
const blockedHints = channelEgressHints({ ...egressZone, obstacles: [doorBottom] }, egressChannel, [blocker])
assert.equal(blockedHints.length, 1, 'corridor blocked warns')
assert.match(blockedHints[0], new RegExp(`净宽不足 ${MIN_PASSAGE_MM}mm`))
// 家具只占走廊一角、剩余净宽 ≥ 阈值：不提示。
const partial = { id: 'F', zoneId: 'Z', x: 4600, y: 5100, w: 100, h: 300, rotation: 0 }
assert.deepEqual(channelEgressHints({ ...egressZone, obstacles: [doorBottom] }, egressChannel, [partial]), [], 'partial blockage above threshold stays silent')
// 端点留断（028）：通道下端止于 y=3000，门在下墙 → 未衔接。
assert.match(channelEgressHints({ ...egressZone, obstacles: [doorBottom] }, [{ ...egressChannel[0], endMm: 3000 }], [])[0] || '', /两端都未衔接/, 'end-break channel away from door warns')
// 横向通道：门在左墙（存储铰链 (0,-3400) → 画布 (0,3400)），与左端头衔接；走廊被禁放区截断时提示。
const doorLeft = doorClearanceObstacle('Z', { x: 0, y: -3400 }, { x: 900, y: -2500 })
const hChannel = [{ id: 'h', orientation: 'horizontal', offsetMm: 3000, widthMm: 900 }]
assert.deepEqual(channelEgressHints({ ...egressZone, obstacles: [doorLeft] }, hChannel, []), [], 'horizontal channel linked to left wall door')
const legacyBlock = { ...egressZone, obstacles: [doorLeft], layoutPreferences: { exclusions: [{ id: 'E', label: '禁放', x: -100, y: 3000, w: 1100, h: 100 }] } }
assert.equal(channelEgressHints(legacyBlock, hChannel, []).length, 1, 'legacy exclusion can sever the corridor')
// 无通道时不核验。
assert.deepEqual(channelEgressHints(egressZone, [], []), [])
console.log('PASS: rect×polygon clipping (rect identity, U/L segmentation, degenerate guards), channelRects clipping + fallback, axisSegments full-band cuts, endpoint breaks (startMm/endMm + fromSecondRow compose), clamp/validate, egress hints (LAYOUT-029: unmarked/door-linked/corridor-blocked/end-break/horizontal)')
