import type { CreateProjectRequest, WorkspaceFloor, WorkspaceProject } from '~/types/workspace'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8082'

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await $fetch<ApiResponse<T>>(`${API_BASE}${path}`, {
    method: method as any,
    body: body as any,
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
  })
  if (response.code !== 200) throw new Error(response.message || '请求失败')
  return response.data
}

type FloorDto = Omit<WorkspaceFloor, 'floorName'> & { name: string }
type ProjectDto = Omit<WorkspaceProject, 'projectName' | 'floors'> & { name: string; floors: FloorDto[] }

function floorFromDto(dto: FloorDto): WorkspaceFloor {
  return { ...dto, floorName: dto.name }
}

function projectFromDto(dto: ProjectDto): WorkspaceProject {
  return { ...dto, projectName: dto.name, floors: (dto.floors || []).map(floorFromDto) }
}

export function useWorkspaceApi() {
  return {
    listProjects: async () => (await request<ProjectDto[]>('GET', '/api/v1/office/projects')).map(projectFromDto),
    createProject: async (payload: CreateProjectRequest) => projectFromDto(await request<ProjectDto>('POST', '/api/v1/office/projects', { name: payload.projectName, customerRequirements: payload.customerRequirements, floors: payload.floors.map(floor => ({ name: floor.floorName })) })),
    addFloor: async (projectId: string, floorName: string) => floorFromDto(await request<FloorDto>('POST', `/api/v1/office/projects/${projectId}/floors`, { name: floorName })),
    updateProject: async (projectId: string, projectName: string) => projectFromDto(await request<ProjectDto>('PATCH', `/api/v1/office/projects/${projectId}`, { name: projectName })),
    updateFloor: async (projectId: string, floorId: string, floorName: string) => floorFromDto(await request<FloorDto>('PATCH', `/api/v1/office/projects/${projectId}/floors/${floorId}`, { name: floorName })),
    updateRequirements: async (projectId: string, customerRequirements: string) => projectFromDto(await request<ProjectDto>('PATCH', `/api/v1/office/projects/${projectId}/requirements`, { customerRequirements })),
  }
}
