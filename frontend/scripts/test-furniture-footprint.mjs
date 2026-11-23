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
    if (!spec.startsWith('.') && !spec.startsWith('~/')) continue
    const resolved = new URL((spec.startsWith('~/') ? '../app/' + spec.slice(2) : spec) + (spec.endsWith('.ts') ? '' : '.ts'),
      spec.startsWith('~/') ? import.meta.url : path)
    source = source.replaceAll(`'${spec}'`, `'${await load(resolved)}'`)
  }
  const url = 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
  cache.set(path.href, url)
  return url
}

const { furnitureFootprintRects, footprintsOverlap } = await import(
  await load(new URL('../app/utils/furnitureFootprint.ts', import.meta.url)))

function rect(actual, expected, message) {
  const keys = ['x', 'y', 'w', 'h']
  for (const key of keys) {
    assert.ok(Math.abs(actual[key] - expected[key]) < 0.01, `${message}: ${key} 期望 ${expected[key]}，实际 ${actual[key]}`)
  }
}

const lDesk = {
  id: 'L', kind: 'furniture', zoneId: 'R', productId: '', x: 1000, y: 1000, w: 1400, h: 1400,
  rotation: 0, wingWidthMm: 550, furnitureTemplateId: 'desk-l',
}
const chair = { id: 'C', kind: 'furniture', zoneId: 'R', productId: '', x: 1150, y: 1750, w: 550, h: 580, rotation: 0, furnitureTemplateId: 'chair-office' }

// L 桌 = 主台面 + 右侧翼；主台面深度 min(h*0.48, 650)
const blocks = furnitureFootprintRects(lDesk)
assert.equal(blocks.length, 2)
rect(blocks[0], { x: 1000, y: 1000, w: 1400, h: 650 }, '主台面')
rect(blocks[1], { x: 1850, y: 1650, w: 550, h: 750 }, '侧翼')

// 内角（主台面下方、侧翼左侧）可放椅子
assert.equal(footprintsOverlap(lDesk, chair), false, '内角不应判为重叠')
// 压到侧翼上仍判重叠
assert.equal(footprintsOverlap(lDesk, { ...chair, x: 1900 }), true, '侧翼应判为重叠')
// 压到主台面上仍判重叠
assert.equal(footprintsOverlap(lDesk, { ...chair, y: 1100 }), true, '主台面应判为重叠')

// 普通桌与自定义模块仍是单块
assert.equal(furnitureFootprintRects({ ...lDesk, furnitureTemplateId: 'desk-straight' }).length, 1)
assert.equal(furnitureFootprintRects({ ...lDesk, furnitureTemplateId: 'custom-x', moduleSnapshot: { kind: 'other' } }).length, 1)
// 缺少翼宽时回退单块，不影响旧数据
assert.equal(furnitureFootprintRects({ ...lDesk, wingWidthMm: 0 }).length, 1)

// 镜像：侧翼换到左侧
rect(furnitureFootprintRects({ ...lDesk, mirrorX: true })[1], { x: 1000, y: 1650, w: 550, h: 750 }, '镜像侧翼')

// 旋转 90°：内角跟着转到左上，仍可放椅子
const rotated = { ...lDesk, rotation: 90 }
const rotatedBlocks = furnitureFootprintRects(rotated)
assert.equal(rotatedBlocks.length, 2)
rect(rotatedBlocks[0], { x: 1750, y: 1000, w: 650, h: 1400 }, '旋转主台面')
rect(rotatedBlocks[1], { x: 1000, y: 1850, w: 750, h: 550 }, '旋转侧翼')
assert.equal(footprintsOverlap(rotated, { ...chair, x: 1100, y: 1200 }), false, '旋转后内角不应重叠')
assert.equal(footprintsOverlap(rotated, { ...chair, x: 1200, y: 2000 }), true, '旋转后侧翼应重叠')

console.log('furniture-footprint: all assertions passed')
