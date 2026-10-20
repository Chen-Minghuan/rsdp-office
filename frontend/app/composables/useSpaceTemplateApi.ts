import type { SpaceTemplate, SpaceTemplateSummary, SpaceTemplateCreateRequest } from '~/types/spaceTemplate'
const API_BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8082'
export function useSpaceTemplateApi() {
  const base = API_BASE + '/api/v1/office/projects/' + encodeURIComponent(useOfficeApi().projectId) + '/space-templates'
  async function request<T>(path: string, body?: SpaceTemplateCreateRequest): Promise<T> {
    const result = await $fetch<{ code: number; message: string; data: T }>(base + path, { method: body ? 'POST' : 'GET', body, credentials: 'include' })
    if (result.code !== 200) throw new Error(result.message || '空间模板请求失败')
    return result.data
  }
  return {
    create: (body: SpaceTemplateCreateRequest) => request<SpaceTemplate>('', body),
    list: () => request<SpaceTemplateSummary[]>(''),
    get: (id: string) => request<SpaceTemplate>('/' + encodeURIComponent(id)),
  }
}
