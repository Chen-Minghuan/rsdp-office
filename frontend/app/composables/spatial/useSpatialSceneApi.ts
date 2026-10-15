import type { ApiResult, CalibrationRequest, ConfirmationRequest, CreateSceneRequest, DraftRequest, MutationData, SpatialSceneSnapshot } from '~/types/spatial'

const API_BASE = '/api/v1/floor-plan'

export function useSpatialSceneApi() {
  const config = useRuntimeConfig()
  const baseUrl = config.public.apiBaseUrl || ''

  async function createScene(request: CreateSceneRequest): Promise<MutationData> {
    const res = await $fetch<ApiResult<MutationData>>(`${baseUrl}${API_BASE}/design-scenes`, {
      method: 'POST',
      body: request,
    })
    return res.data
  }

  async function getScene(sceneId: string): Promise<SpatialSceneSnapshot> {
    const res = await $fetch<ApiResult<SpatialSceneSnapshot>>(`${baseUrl}${API_BASE}/design-scenes/${sceneId}`)
    return res.data
  }

  async function saveDraft(sceneId: string, request: DraftRequest): Promise<MutationData> {
    const res = await $fetch<ApiResult<MutationData>>(`${baseUrl}${API_BASE}/design-scenes/${sceneId}/draft`, {
      method: 'PUT',
      body: request,
    })
    return res.data
  }

  async function applyCalibration(sceneId: string, request: CalibrationRequest): Promise<MutationData> {
    const res = await $fetch<ApiResult<MutationData>>(`${baseUrl}${API_BASE}/design-scenes/${sceneId}/calibrations`, {
      method: 'POST',
      body: request,
    })
    return res.data
  }

  async function confirm(sceneId: string, request: ConfirmationRequest): Promise<MutationData> {
    const res = await $fetch<ApiResult<MutationData>>(`${baseUrl}${API_BASE}/design-scenes/${sceneId}/confirmations`, {
      method: 'POST',
      body: request,
    })
    return res.data
  }

  return { createScene, getScene, saveDraft, applyCalibration, confirm }
}
