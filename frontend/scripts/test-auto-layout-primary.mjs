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

const { isMeetingAutoLayoutPrimary } = await import(await load(new URL('../app/utils/autoLayoutPrimary.ts', import.meta.url)))
const zone = { id: 'ROOM-L', kind: 'zone' }
const spacing = { wallMm: 300, aisleMm: 900, gapMm: 0 }
const primary = {
  id: 'MEETING-T', kind: 'furniture', zoneId: 'ROOM-L', x: 0, y: 0, w: 2400, h: 1100, rotation: 0, productId: '',
  furnitureTemplateId: 'table-conference-rect', label: '会议桌', layoutRole: 'primary', layoutGroupId: 'auto:ROOM-L',
  layoutSettings: { mode: 'auto', spacing, layoutContext: 'meeting', productStyleId: 'meeting-table', arrangementPresetId: 'meeting-center' },
}

assert.equal(isMeetingAutoLayoutPrimary(primary, [zone]), true, '当前空间显式自动组主会议桌才进入组合重排')
assert.equal(isMeetingAutoLayoutPrimary({ ...primary, layoutGroupId: undefined }, [zone]), false, '缺失 layoutGroupId 不得按自动组处理')
assert.equal(isMeetingAutoLayoutPrimary({ ...primary, layoutGroupId: 'auto:OTHER' }, [zone]), false, 'layoutGroupId 不匹配不得按当前空间自动组处理')
assert.equal(isMeetingAutoLayoutPrimary({ ...primary, layoutRole: 'seat' }, [zone]), false, '座位不是自动排版主家具')
assert.equal(isMeetingAutoLayoutPrimary({ ...primary, layoutSettings: { ...primary.layoutSettings, layoutContext: 'workstation' } }, [zone]), false, '非会议上下文不进入会议组合重排')
assert.equal(isMeetingAutoLayoutPrimary({ ...primary, furnitureTemplateId: 'table-meeting-observer' }, [zone]), false, 'T16 旁听条桌保持排除')
assert.equal(isMeetingAutoLayoutPrimary({ ...primary, furnitureTemplateId: 'coffee-meeting-side' }, [zone]), false, 'S15 座间边几保持排除')

console.log('PASS: meeting auto-layout primary requires exact layoutGroupId, meeting context, primary role and eligible reference')
