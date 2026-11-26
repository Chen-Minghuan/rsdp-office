import type { AssignableUser, CreateProjectRequest, ProjectBudget, ProjectReferenceImage, WorkspaceFloor, WorkspaceProject } from '~/types/workspace'
import { apiBase } from '~/utils/apiBase'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await $fetch<ApiResponse<T>>(`${apiBase}${path}`, {
    method: method as any,
    body: body as any,
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
  })
  if (response.code !== 200) throw new Error(response.message || '请求失败')
  return response.data
}

type FloorDto = Omit<WorkspaceFloor, 'floorName'> & { name: string }
type ProjectDto = Omit<WorkspaceProject, 'projectName' | 'floors'> & {
  name: string
  floors: FloorDto[]
}

function floorFromDto(dto: FloorDto): WorkspaceFloor {
  return { ...dto, floorName: dto.name }
}

function projectFromDto(dto: ProjectDto): WorkspaceProject {
  return {
    ...dto,
    projectName: dto.name,
    status: dto.status ?? 'DESIGNING',
    canManageStatus: dto.canManageStatus ?? false,
    leader: dto.leader ?? null,
    collaborators: dto.collaborators ?? [],
    referenceImages: dto.referenceImages ?? [],
    floors: (dto.floors || []).map(floorFromDto),
    zoneTotal: dto.zoneTotal ?? null,
    zoneDelivered: dto.zoneDelivered ?? null,
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
    listProjects: async () => (await request<ProjectDto[]>('GET', '/api/v1/office/projects?includeBudget=false')).map(projectFromDto),
    listProjectBudgets: () => request<Record<string, ProjectBudget>>('GET', '/api/v1/office/projects/budgets'),
    updateProjectStatus: (projectId: string, status: import('~/types/workspace').ProjectStatus) => request<{ status: import('~/types/workspace').ProjectStatus }>('PATCH', `/api/v1/office/projects/${encodeURIComponent(projectId)}/status`, { status }),
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
    summarizeRequirements: (projectId: string, requirementsRevision: number) => request<WorkspaceRequirementsSummary>('POST', `/api/v1/office/projects/${projectId}/requirements/summary`, { requirementsRevision }),
    listAssignableUsers: async (keyword?: string) => request<AssignableUser[]>('GET', `/api/v1/office/users/assignable${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''}`),
    replaceMembers: async (projectId: string, leaderUserId: string, collaboratorUserIds: string[],
      permissions?: Record<string, import('~/types/workspace').ProjectPermission[]>) =>
      projectFromDto(await request<ProjectDto>('PUT', `/api/v1/office/projects/${projectId}/members`, { leaderUserId, collaboratorUserIds, permissions })),
    updateReferences: async (projectId: string, referenceImages: ProjectReferenceImage[]) =>
      projectFromDto(await request<ProjectDto>('PATCH', `/api/v1/office/projects/${projectId}/references`, { referenceImages })),
  }
}
