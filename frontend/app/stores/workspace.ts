import { defineStore } from 'pinia'
import type { CreateProjectRequest, ProjectReferenceImage, WorkspaceProject } from '~/types/workspace'
import { authEnabled, useAuth } from '~/composables/useAuth'

const SELECTION_KEY = 'rsdp-office-workspace-v1'

function selectionStorageKey() {
  return authEnabled ? `${SELECTION_KEY}:${useAuth().storageScope.value}` : SELECTION_KEY
}

export const useWorkspaceStore = defineStore('workspace', {
  state: () => ({
    projects: [] as WorkspaceProject[],
    currentProjectId: '',
    currentFloorId: '',
    collapsedProjectIds: [] as string[],
    collapsedFloorIds: [] as string[],
    initialized: false,
    loading: false,
    switching: false,
    error: '',
  }),
  getters: {
    currentProject: state => state.projects.find(project => project.projectId === state.currentProjectId) || null,
    currentFloor: state => state.projects.flatMap(project => project.floors).find(floor => floor.floorId === state.currentFloorId) || null,
    currentProjectName(): string { return this.currentProject?.projectName || '未选择项目' },
    currentFloorName(): string { return this.currentFloor?.floorName || '未选择楼层' },
  },
  actions: {
    rememberSelection() {
      if (import.meta.client) localStorage.setItem(selectionStorageKey(), JSON.stringify({ projectId: this.currentProjectId, floorId: this.currentFloorId, collapsed: this.collapsedProjectIds, collapsedFloors: this.collapsedFloorIds }))
    },
    restoreSelection() {
      if (!import.meta.client) return
      try {
        const saved = JSON.parse(localStorage.getItem(selectionStorageKey()) || 'null')
        const route = useRoute()
        const requestedFloor = typeof route.query.floorId === 'string' ? route.query.floorId : saved?.floorId
        const floor = this.projects.flatMap(project => project.floors).find(item => item.floorId === requestedFloor)
          || this.projects[0]?.floors[0]
        if (floor) {
          this.currentFloorId = floor.floorId
          this.currentProjectId = floor.projectId
        } else {
          this.currentFloorId = ''
          this.currentProjectId = ''
        }
        if (Array.isArray(saved?.collapsed)) this.collapsedProjectIds = saved.collapsed.filter((id: unknown): id is string => typeof id === 'string')
        if (Array.isArray(saved?.collapsedFloors)) this.collapsedFloorIds = saved.collapsedFloors.filter((id: unknown): id is string => typeof id === 'string')
      } catch { /* Ignore an invalid local selection and use the first available floor. */ }
    },
    async init() {
      if (this.initialized || this.loading) return
      this.loading = true
      this.error = ''
      const scope = useAuth().storageScope.value
      try {
        const projects = await useWorkspaceApi().listProjects()
        if (scope !== useAuth().storageScope.value) return
        this.projects = projects
        this.initialized = true
      } catch (error) {
        if (scope !== useAuth().storageScope.value) return
        // 失败不锁定初始化，也不伪造默认项目；保留真实错误供页面重试。
        this.projects = []
        this.error = error instanceof Error ? error.message : '项目列表暂时不可用'
      } finally {
        if (scope === useAuth().storageScope.value) {
          this.restoreSelection()
          this.loading = false
        }
      }
    },
    async refresh() {
      useOfficeStore().$reset()
      this.$reset()
      await this.init()
      await useOfficeStore().init()
    },
    async activateFloor(floorId: string) {
      const floor = this.projects.flatMap(project => project.floors).find(item => item.floorId === floorId)
      if (!floor || this.switching) return
      if (floorId === this.currentFloorId && useOfficeStore().initialized) return
      this.switching = true
      this.error = ''
      try {
        this.currentFloorId = floor.floorId
        this.currentProjectId = floor.projectId
        this.rememberSelection()
        const router = useRouter()
        await router.replace({ query: { ...useRoute().query, floorId: floor.floorId } })
        await useOfficeStore().activateFloor(floor.floorId)
      } catch (error) {
        this.error = error instanceof Error ? error.message : '楼层切换失败'
      } finally {
        this.switching = false
      }
    },
    toggleProject(projectId: string) {
      this.collapsedProjectIds = this.collapsedProjectIds.includes(projectId)
        ? this.collapsedProjectIds.filter(id => id !== projectId)
        : [...this.collapsedProjectIds, projectId]
      this.rememberSelection()
    },
    toggleFloorSpaces(floorId: string) {
      this.collapsedFloorIds = this.collapsedFloorIds.includes(floorId)
        ? this.collapsedFloorIds.filter(id => id !== floorId)
        : [...this.collapsedFloorIds, floorId]
      this.rememberSelection()
    },
    async createProject(payload: CreateProjectRequest) {
      const project = await useWorkspaceApi().createProject(payload)
      this.projects = [...this.projects, project]
      this.rememberSelection()
      await this.activateFloor(project.floors[0]!.floorId)
      return project
    },
    async addFloor(floorName: string) {
      if (!this.currentProject) throw new Error('请先选择项目')
      const floor = await useWorkspaceApi().addFloor(this.currentProject.projectId, floorName)
      this.currentProject.floors.push(floor)
      this.currentProject.floors.sort((a, b) => a.sortOrder - b.sortOrder)
      this.collapsedProjectIds = this.collapsedProjectIds.filter(id => id !== this.currentProjectId)
      await this.activateFloor(floor.floorId)
      return floor
    },
    async updateProjectName(projectId: string, projectName: string) {
      const updated = await useWorkspaceApi().updateProject(projectId, projectName)
      const index = this.projects.findIndex(project => project.projectId === projectId)
      if (index >= 0) this.projects[index] = { ...updated, floors: this.projects[index]!.floors }
    },
    async updateFloorName(projectId: string, floorId: string, floorName: string) {
      const updated = await useWorkspaceApi().updateFloor(projectId, floorId, floorName)
      const floor = this.projects.find(project => project.projectId === projectId)?.floors.find(item => item.floorId === floorId)
      if (floor) Object.assign(floor, updated)
    },
    async updateProjectDetails(projectName: string, floors: Array<{ floorId: string; floorName: string }>) {
      if (!this.currentProject) throw new Error('请先选择项目')
      const projectId = this.currentProject.projectId
      await this.updateProjectName(projectId, projectName)
      for (const floor of floors) {
        const current = this.currentProject?.floors.find(item => item.floorId === floor.floorId)
        if (!current || current.floorName === floor.floorName) continue
        await this.updateFloorName(projectId, floor.floorId, floor.floorName)
      }
    },
    async updateRequirements(customerRequirements: string, projectId = this.currentProjectId) {
      const project = this.projects.find(item => item.projectId === projectId)
      if (!project) throw new Error('请先选择项目')
      const updated = await useWorkspaceApi().updateRequirements(projectId, customerRequirements)
      const index = this.projects.findIndex(item => item.projectId === updated.projectId)
      if (index >= 0) this.projects[index] = { ...updated, floors: updated.floors?.length ? updated.floors : this.projects[index]!.floors }
      return updated
    },
    async updateMembers(projectId: string, leaderUserId: string, collaboratorUserIds: string[]) {
      const updated = await useWorkspaceApi().replaceMembers(projectId, leaderUserId, collaboratorUserIds)
      const index = this.projects.findIndex(item => item.projectId === projectId)
      if (index >= 0) this.projects[index] = { ...updated, floors: this.projects[index]!.floors }
      return updated
    },
    async updateReferenceImages(projectId: string, referenceImages: ProjectReferenceImage[]) {
      const updated = await useWorkspaceApi().updateReferences(projectId, referenceImages)
      const index = this.projects.findIndex(item => item.projectId === projectId)
      if (index >= 0) this.projects[index] = { ...updated, floors: this.projects[index]!.floors }
      return updated
    },
  },
})
