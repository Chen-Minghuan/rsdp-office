import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'

const source = stripTypeScriptTypes(await readFile(new URL('../app/utils/recommendationDraft.ts', import.meta.url), 'utf8'))
const { createRecommendationDraftSaver } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'))
const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

// Switching furniture or rotation leaves the same saved draft: merge overlapping saves and skip repeats.
{
  const saveOnce = createRecommendationDraftSaver(), request = deferred()
  let calls = 0
  const save = () => { calls++; return request.promise }
  const first = saveOnce('account:floor:token:revision:geometry', save)
  const second = saveOnce('account:floor:token:revision:geometry', save)
  await Promise.resolve()
  assert.equal(calls, 1)
  request.resolve()
  await Promise.all([first, second])
  await saveOnce('account:floor:token:revision:geometry', save)
  assert.equal(calls, 1)
  for (const key of ['other-account', 'other-floor', 'other-token', 'other-revision', 'other-geometry']) {
    await saveOnce(key, async () => { calls++ })
  }
  assert.equal(calls, 6, 'changed account/floor/token/revision/geometry must save again')
}
// Failed writes do not suppress a later retry, even if the server might have received the first write.
{
  const saveOnce = createRecommendationDraftSaver()
  let calls = 0
  const save = async () => { if (++calls === 1) throw new Error('timeout') }
  await assert.rejects(saveOnce('draft', save), /timeout/)
  await saveOnce('draft', save)
  assert.equal(calls, 2)
}
// A late old response cannot make an obsolete draft the remembered successful version.
{
  const saveOnce = createRecommendationDraftSaver(), old = deferred()
  const first = saveOnce('old', () => old.promise)
  await saveOnce('new', async () => {})
  old.resolve()
  await first
  let calls = 0
  await saveOnce('new', async () => { calls++ })
  assert.equal(calls, 0)
  await saveOnce('old', async () => { calls++ })
  assert.equal(calls, 1)
}
console.log('Recommendation draft saves passed: concurrent deduplication, exact draft invalidation, retries and late responses.')
