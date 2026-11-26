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
  /** 由项目负责人或所属工作区主号人工维护。 */
  status: ProjectStatus
  /** 当前用户是否可修改项目阶段（负责人或工作区主号），由后端按成员关系计算。 */
  canManageStatus: boolean
  customerRequirements: string
  requirementsRevision: number
  leader: WorkspaceMember | null
  collaborators: WorkspaceMember[]
  referenceImages: ProjectReferenceImage[]
  floors: WorkspaceFloor[]
  /** 所有楼层区域(kind=zone)总数；仅项目列表返回，单项目接口为 null。 */
  zoneTotal: number | null
  /** 所在楼层方案已确认（交付中）的区域数；仅项目列表返回。 */
  zoneDelivered: number | null
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
