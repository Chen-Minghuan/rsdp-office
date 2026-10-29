export interface WorkspaceFloor {
  floorId: string
  projectId: string
  floorName: string
  sortOrder: number
}

export interface WorkspaceMember {
  userId: string
  username: string
  displayName: string
  role: 'LEADER' | 'COLLABORATOR'
}

export interface ProjectReferenceImage {
  url: string
  name: string
}

export interface AssignableUser {
  userId: string
  username: string
  displayName: string
}

export interface WorkspaceProject {
  projectId: string
  projectName: string
  customerRequirements: string
  requirementsRevision: number
  leader: WorkspaceMember | null
  collaborators: WorkspaceMember[]
  referenceImages: ProjectReferenceImage[]
  floors: WorkspaceFloor[]
}

export interface CreateProjectRequest {
  projectName: string
  customerRequirements: string
  floors: Array<{ floorName: string; sortOrder: number }>
  leaderUserId?: string
  collaboratorUserIds?: string[]
  referenceImages?: ProjectReferenceImage[]
}

export const DEFAULT_WORKSPACE_ID = 'OFFICE-PROJECT-001'

export function defaultWorkspaceProject(): WorkspaceProject {
  return {
    projectId: DEFAULT_WORKSPACE_ID,
    projectName: '默认办公项目',
    customerRequirements: '',
    requirementsRevision: 1,
    leader: null,
    collaborators: [],
    referenceImages: [],
    floors: [{ floorId: DEFAULT_WORKSPACE_ID, projectId: DEFAULT_WORKSPACE_ID, floorName: '默认楼层', sortOrder: 1 }],
  }
}
