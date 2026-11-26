/** Remember only the latest successful draft save, never the recommendation or SKU state. */
export function createRecommendationDraftSaver() {
  let savedKey: string | undefined
  let sequence = 0
  const pending = new Map<string, Promise<void>>()

  return async (key: string, save: () => Promise<unknown>): Promise<void> => {
    if (key === savedKey) return
    let active = pending.get(key)
    if (!active) {
      const ticket = ++sequence
      // A failed/interrupted write may have reached the server. Recheck on the next attempt.
      savedKey = undefined
      active = Promise.resolve().then(save).then(() => {
        if (ticket === sequence) savedKey = key
      }).finally(() => pending.delete(key))
      pending.set(key, active)
    }
    await active
  }
}
