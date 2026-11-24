import type { ProjectStatus } from '~/types/workspace'

export const projectStatusOptions: Array<{ value: ProjectStatus; label: string }> = [
  { value: 'DESIGNING', label: '设计中' },
  { value: 'DELIVERING', label: '交付中' },
  { value: 'COMPLETED', label: '已完成' },
]

export type ProjectStatusFilter = ProjectStatus | 'ALL'
