export interface WorkspaceFloor {
  floorId: string
  projectId: string
  floorName: string
  sortOrder: number
}

export interface WorkspaceProject {
  projectId: string
  projectName: string
  customerRequirements: string
  requirementsRevision: number
  floors: WorkspaceFloor[]
}

export interface CreateProjectRequest {
  projectName: string
  customerRequirements: string
  floors: Array<{ floorName: string; sortOrder: number }>
}

export const DEFAULT_WORKSPACE_ID = 'OFFICE-PROJECT-001'

export function defaultWorkspaceProject(): WorkspaceProject {
  return {
    projectId: DEFAULT_WORKSPACE_ID,
    projectName: '默认办公项目',
    customerRequirements: '',
    requirementsRevision: 1,
    floors: [{ floorId: DEFAULT_WORKSPACE_ID, projectId: DEFAULT_WORKSPACE_ID, floorName: '默认楼层', sortOrder: 1 }],
  }
}
