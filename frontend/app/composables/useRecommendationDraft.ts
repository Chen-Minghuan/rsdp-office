import { createRecommendationDraftSaver } from '~/utils/recommendationDraft'

/** Scoped to the panel, account, floor and complete draft, including the scheme token. */
export function useRecommendationDraft() {
  const store = useOfficeStore()
  const api = useOfficeApi()
  const auth = useAuth()
  const saveOnce = createRecommendationDraftSaver()

  return async () => {
    const floorId = store.workspaceId
    const snapshot = JSON.parse(JSON.stringify({
      objects: store.objects, files: store.files, mode: store.canvasMode,
      designZoneId: store.designZoneId, revision: store.canvasRevision,
      schemeToken: store.schemeToken, blank: store.blank,
    }))
    const key = JSON.stringify([auth.storageScope.value, floorId, snapshot])
    await saveOnce(key, () => api.saveCanvas(snapshot, floorId))
  }
}
