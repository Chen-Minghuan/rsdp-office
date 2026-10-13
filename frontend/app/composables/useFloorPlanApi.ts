import type {
  FloorPlanAnalysisResponse,
  FloorPlanAnalyzeResponse,
  FloorPlanConfirmRequest,
} from '~/types/floorPlan'

const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8082'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

function apiUrl(path: string) {
  return `${API_BASE}${path}`
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  const options: Record<string, any> = {
    method: method as any,
    credentials: 'include',
  }
  if (body && !(body instanceof FormData)) {
    options.body = body as any
    headers['Content-Type'] = 'application/json'
  }
  if (Object.keys(headers).length > 0) {
    options.headers = headers
  }
  const res = await $fetch<ApiResponse<T>>(apiUrl(path), options)
  if (res.code !== 200) {
    throw new Error(res.message || '请求失败')
  }
  return res.data
}

export function useFloorPlanApi() {
  async function analyzeFloorPlan(params: {
    image?: File
    cad?: File
    hint?: string
    projectId?: string
    sourceName?: string
  }): Promise<FloorPlanAnalyzeResponse> {
    const formData = new FormData()
    if (params.image) formData.append('image', params.image)
    if (params.cad) formData.append('cad', params.cad)
    if (params.hint) formData.append('hint', params.hint)
    if (params.projectId) formData.append('projectId', params.projectId)
    if (params.sourceName) formData.append('sourceName', params.sourceName)

    const res = await $fetch<ApiResponse<FloorPlanAnalyzeResponse>>(apiUrl('/api/v1/floor-plan/analyze'), {
      method: 'POST',
      body: formData,
      credentials: 'include',
    })
    if (res.code !== 200) {
      throw new Error(res.message || '分析任务创建失败')
    }
    return res.data
  }

  async function getFloorPlan(analysisId: string, analysisRevision?: number): Promise<FloorPlanAnalysisResponse> {
    const query = analysisRevision !== undefined ? `?analysisRevision=${analysisRevision}` : ''
    return request<FloorPlanAnalysisResponse>('GET', `/api/v1/floor-plan/${analysisId}${query}`)
  }

  async function confirmFloorPlanRooms(
    analysisId: string,
    request: FloorPlanConfirmRequest,
  ): Promise<FloorPlanAnalysisResponse> {
    return request<FloorPlanAnalysisResponse>('PUT', `/api/v1/floor-plan/${analysisId}/rooms`, request)
  }

  return {
    analyzeFloorPlan,
    getFloorPlan,
    confirmFloorPlanRooms,
  }
}
