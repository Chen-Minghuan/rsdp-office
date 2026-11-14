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
