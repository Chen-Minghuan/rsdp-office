import type { RenderPreview, RenderPreviewRequest } from '~/types/renderPreview'

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8082'
export function useRenderPreviewApi(floorId?: string) {
  const resolveFloor = floorId === undefined ? useOfficeApi() : null
  async function request<T>(path: string, body?: RenderPreviewRequest): Promise<T> {
    const target = floorId ?? resolveFloor?.projectId
    if (!target) throw new Error('请先选择楼层')
    const base = API_BASE + '/api/v1/office/projects/' + encodeURIComponent(target) + '/render-previews'
    const response = await $fetch<{ code: number; message: string; data: T }>(base + path, {
      method: body ? 'POST' : 'GET', body, credentials: 'include', retry: 0,
    })
    if (response.code !== 200) throw new Error(response.message || '效果预览请求失败')
    return response.data
  }
  return {
    create: (body: RenderPreviewRequest) => request<RenderPreview>('', body),
    list: (zoneId: string) => request<RenderPreview[]>('?zoneId=' + encodeURIComponent(zoneId)),
    get: (id: string) => request<RenderPreview>('/' + encodeURIComponent(id)),
    imageUrl: (path?: string) => path?.startsWith('/uploads/render-previews/') ? API_BASE + path : '',
  }
}
