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
    const resolved = new URL((spec.startsWith('~/') ? '../app/' + spec.slice(2) : spec) + (spec.endsWith('.ts') ? '' : '.ts'), spec.startsWith('~/') ? import.meta.url : path)
    source = source.replaceAll(`'${spec}'`, `'${await load(resolved)}'`)
  }
  const url = 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
  cache.set(path.href, url)
  return url
}

const { layoutReferenceTaskKey, mergeRecognizedMarks, cloneReferenceEditing, furnitureLayoutSignature } = await import(await load(new URL('../app/utils/layoutReference.ts', import.meta.url)))

const mark = (overrides = {}) => ({ templateId: 'desk-straight', categoryId: 'workstation', group: 'g1', x: 0.1, y: 0.2, width: 0.16, depth: 0.11, rotation: 0, confidence: 'high', ...overrides })

assert.equal(layoutReferenceTaskKey('ZONE-1', 'ANALYSIS-1', 3), 'ZONE-1:ANALYSIS-1:3')

// 人工确认的标记保留；与人工标记近似重合（<0.04）的同类机器建议被丢弃。
const manual = mark({ confidence: 'manual' })
const near = mark({ x: 0.11, y: 0.21, confidence: 'high' })
const merged = mergeRecognizedMarks([manual], [near])
assert.equal(merged.length, 1)
assert.equal(merged[0].confidence, 'manual')

// 类别不同即使位置相近也保留。
const otherCategory = mark({ categoryId: 'sofa', templateId: 'sofa-double' })
assert.equal(mergeRecognizedMarks([manual], [otherCategory]).length, 2)

// 距离超过阈值的同类建议保留。
const far = mark({ x: 0.6, y: 0.6 })
assert.equal(mergeRecognizedMarks([manual], [far]).length, 2)

// 非人工的既有标记被新一轮识别整体替换。
const stale = mark({ confidence: 'low' })
const fresh = mark({ x: 0.5, y: 0.5 })
const replaced = mergeRecognizedMarks([stale], [fresh])
assert.equal(replaced.length, 1)
assert.equal(replaced[0].x, 0.5)

// 快照深拷贝：marks/crop 不共享引用，选中态与放置态清空。
const editing = {
  zoneId: 'ZONE-1',
  crop: { x: 1, y: 2, w: 3000, h: 2000 },
  marks: [mark()],
  selected: 0,
  placement: { templateId: 'desk-straight', categoryId: 'workstation', group: 'g1', rotation: 0 },
}
const snapshot = cloneReferenceEditing(editing)
assert.deepEqual(snapshot, { ...editing, selected: null, placement: null })
assert.notEqual(snapshot.marks, editing.marks)
assert.notEqual(snapshot.marks[0], editing.marks[0])
assert.notEqual(snapshot.crop, editing.crop)
snapshot.marks[0].x = 0.9
assert.equal(editing.marks[0].x, 0.1, '修改快照不影响原编辑状态')

// 排版签名：空画布为空串；与顺序无关；增/删/换家具都会改变签名。
assert.equal(furnitureLayoutSignature([]), '')
assert.equal(furnitureLayoutSignature([{ id: 'b' }, { id: 'a' }]), furnitureLayoutSignature([{ id: 'a' }, { id: 'b' }]), '签名与家具顺序无关')
assert.notEqual(furnitureLayoutSignature([{ id: 'a' }]), furnitureLayoutSignature([{ id: 'a' }, { id: 'b' }]), '新增家具改变签名')
assert.notEqual(furnitureLayoutSignature([{ id: 'a' }, { id: 'b' }]), furnitureLayoutSignature([{ id: 'a' }]), '移除家具改变签名')
assert.notEqual(furnitureLayoutSignature([{ id: 'a' }]), furnitureLayoutSignature([{ id: 'c' }]), '同数量换家具也改变签名')

console.log('PASS: layoutReferenceTaskKey, mergeRecognizedMarks (manual 保留/近似去重/异类保留/旧建议替换), cloneReferenceEditing 深拷贝与状态清空, furnitureLayoutSignature 排版签名')
