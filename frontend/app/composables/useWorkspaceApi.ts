import type { AssignableUser, CreateProjectRequest, ProjectReferenceImage, WorkspaceFloor, WorkspaceProject } from '~/types/workspace'

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
  return {
    ...dto,
    projectName: dto.name,
    leader: dto.leader ?? null,
    collaborators: dto.collaborators ?? [],
    referenceImages: dto.referenceImages ?? [],
    floors: (dto.floors || []).map(floorFromDto),
  }
}

/** 上传接口返回绝对地址；保存到项目时统一转回站内相对路径。 */
export function toRelativeUploadPath(url: string): string {
  const value = (url || '').trim()
  if (value.startsWith('/uploads/images/')) return value
  try {
    const parsed = new URL(value)
    if (parsed.pathname.startsWith('/uploads/images/')) return parsed.pathname
  } catch { /* Not an absolute URL; fall through to the original value. */ }
  return value
}

export function useWorkspaceApi() {
  return {
    listProjects: async () => (await request<ProjectDto[]>('GET', '/api/v1/office/projects')).map(projectFromDto),
    createProject: async (payload: CreateProjectRequest) => projectFromDto(await request<ProjectDto>('POST', '/api/v1/office/projects', {
      name: payload.projectName,
      customerRequirements: payload.customerRequirements,
      floors: payload.floors.map(floor => ({ name: floor.floorName })),
      leaderUserId: payload.leaderUserId || undefined,
      collaboratorUserIds: payload.collaboratorUserIds?.length ? payload.collaboratorUserIds : undefined,
      referenceImages: payload.referenceImages?.length ? payload.referenceImages : undefined,
    })),
    addFloor: async (projectId: string, floorName: string) => floorFromDto(await request<FloorDto>('POST', `/api/v1/office/projects/${projectId}/floors`, { name: floorName })),
    updateProject: async (projectId: string, projectName: string) => projectFromDto(await request<ProjectDto>('PATCH', `/api/v1/office/projects/${projectId}`, { name: projectName })),
    updateFloor: async (projectId: string, floorId: string, floorName: string) => floorFromDto(await request<FloorDto>('PATCH', `/api/v1/office/projects/${projectId}/floors/${floorId}`, { name: floorName })),
    updateRequirements: async (projectId: string, customerRequirements: string) => projectFromDto(await request<ProjectDto>('PATCH', `/api/v1/office/projects/${projectId}/requirements`, { customerRequirements })),
    listAssignableUsers: async (keyword?: string) => request<AssignableUser[]>('GET', `/api/v1/office/users/assignable${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''}`),
    replaceMembers: async (projectId: string, leaderUserId: string, collaboratorUserIds: string[]) =>
      projectFromDto(await request<ProjectDto>('PUT', `/api/v1/office/projects/${projectId}/members`, { leaderUserId, collaboratorUserIds })),
    updateReferences: async (projectId: string, referenceImages: ProjectReferenceImage[]) =>
      projectFromDto(await request<ProjectDto>('PATCH', `/api/v1/office/projects/${projectId}/references`, { referenceImages })),
  }
}
