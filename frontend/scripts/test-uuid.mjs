import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'
import { webcrypto } from 'node:crypto'
import vm from 'node:vm'

const source = stripTypeScriptTypes(await readFile(new URL('../app/utils/uuid.ts', import.meta.url), 'utf8')).replace('export function', 'function')
function load(crypto) {
  const context = vm.createContext({ crypto })
  return vm.runInContext(source + '\ncreateUuid', context)
}

const native = 'cc77253c-7673-45cc-88bf-fc0f5fab81c4'
const nativeCrypto = { randomUUID() { assert.equal(this, nativeCrypto); return native } }
assert.equal(load(nativeCrypto)(), native, 'Use the native API with its Crypto receiver')

// Public HTTP exposes getRandomValues but omits randomUUID.
const fallback = load({ getRandomValues: bytes => webcrypto.getRandomValues(bytes) })
const ids = Array.from({ length: 1000 }, () => fallback())
ids.forEach(id => assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/))
assert.equal(new Set(ids).size, ids.length, 'Independent drawing / retry identities must not collide')
assert.equal(load({ getRandomValues(bytes) { bytes.fill(0); return bytes } })(), '00000000-0000-4000-8000-000000000000', 'Correct RFC version, variant and padding')
assert.throws(load(undefined), /当前浏览器不支持生成唯一标识/)
console.log('PASS: native UUID, HTTP fallback, v4 format, independent IDs and unavailable crypto')
