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
    const resolved = new URL(
      (spec.startsWith('~/') ? '../app/' + spec.slice(2) : spec) + (spec.endsWith('.ts') ? '' : '.ts'),
      spec.startsWith('~/') ? import.meta.url : path,
    )
    source = source.replaceAll(`'${spec}'`, `'${await load(resolved)}'`)
  }
  const url = 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
  cache.set(path.href, url)
  return url
}

const { shouldGateProposalRoute } = await import(await load(new URL('../app/utils/proposalFurnitureGate.ts', import.meta.url)))

assert.equal(shouldGateProposalRoute('/proposal'), true)
assert.equal(shouldGateProposalRoute('/proposal/'), true)
assert.equal(shouldGateProposalRoute('/proposal///'), true)
assert.equal(shouldGateProposalRoute('/proposal-extra'), false)
assert.equal(shouldGateProposalRoute('/canvas/'), false)
assert.equal(shouldGateProposalRoute('/proposal', '1'), false)
assert.equal(shouldGateProposalRoute('/proposal/', '1'), false)

console.log('PASS: proposal route gate normalizes trailing slashes')
