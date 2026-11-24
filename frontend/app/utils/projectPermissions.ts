import type { ProjectPermission, WorkspaceProject } from '~/types/workspace'

export function projectPermissionsFor(project: WorkspaceProject | null | undefined, userId: string | undefined): ProjectPermission[] {
  if (!project || !userId) return []
  const member = project.leader?.userId === userId
    ? project.leader
    : project.collaborators.find(item => item.userId === userId)
  return member?.permissions ?? []
}
