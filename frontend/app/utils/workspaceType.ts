export type WorkspaceType = 'DEALER' | 'FACTORY'

export function workspaceTypeLabel(type?: WorkspaceType | null) {
  return type === 'FACTORY' ? '工厂工作区' : '经销商工作区'
}

export function workspaceOwnerLabel(type?: WorkspaceType | null) {
  return type === 'FACTORY' ? '工厂工作区主号' : '经销商主号'
}
