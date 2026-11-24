export interface WorkspaceFloor {
  floorId: string
  projectId: string
  floorName: string
  sortOrder: number
  referenceImages: ProjectReferenceImage[]
}

export interface WorkspaceMember {
  userId: string
  username: string
  displayName: string
  role: 'LEADER' | 'COLLABORATOR'
  permissions?: ProjectPermission[]
}

export type ProjectPermission = 'READ' | 'EDIT' | 'QUOTE_READ' | 'MARGIN_READ' | 'EXPORT_PROJECT' | 'MANAGE_MEMBERS' | 'EXPORT_QUOTE'

export interface ProjectReferenceImage {
  url: string
  name: string
}

export type ProjectBudgetStatus = 'COMPLETE' | 'PENDING_PRICING' | 'UNAVAILABLE'

export interface ProjectBudget {
  budgetTotal: number | null
  budgetStatus: ProjectBudgetStatus
}

export interface AssignableUser {
  userId: string
  username: string
  displayName: string
}

export interface WorkspaceProject {
  projectId: string
  projectName: string
  status: ProjectStatus
  canManageStatus: boolean
  customerRequirements: string
  requirementsRevision: number
  leader: WorkspaceMember | null
  collaborators: WorkspaceMember[]
  referenceImages: ProjectReferenceImage[]
  floors: WorkspaceFloor[]
}

export type ProjectStatus = 'DESIGNING' | 'DELIVERING' | 'COMPLETED'

export interface WorkspaceRequirementsSummary {
  keyPoints: string[]
  sourceRevision: number
  cacheHit: boolean
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
