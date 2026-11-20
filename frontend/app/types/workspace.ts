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

export type ProjectBudgetStatus = 'COMPLETE' | 'PENDING_PRICING' | 'UNAVAILABLE'
export type ProjectStage = 'DESIGNING' | 'DELIVERING'

export interface AssignableUser {
  userId: string
  username: string
  displayName: string
}

export interface WorkspaceProject {
  stage: ProjectStage
  projectId: string
  projectName: string
  customerRequirements: string
  requirementsRevision: number
  leader: WorkspaceMember | null
  collaborators: WorkspaceMember[]
  referenceImages: ProjectReferenceImage[]
  floors: WorkspaceFloor[]
  budgetTotal: number | null
  budgetStatus: ProjectBudgetStatus
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
