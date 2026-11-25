import { defineStore } from 'pinia'
import type { CreateProjectRequest, ProjectReferenceImage, WorkspaceProject } from '~/types/workspace'
import { authEnabled, useAuth } from '~/composables/useAuth'
import { projectBenchPath, projectDrawingRoute, projectOverviewRoute } from '~/utils/projectBenchRoutes'

const SELECTION_KEY = 'rsdp-office-workspace-v1'
let activeFloorTransition: { floorId: string, completion: Promise<void> } | null = null

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
    async updateProjectStatus(projectId: string, status: import('~/types/workspace').ProjectStatus) {
      const scope = useAuth().storageScope.value
      const updated = await useWorkspaceApi().updateProjectStatus(projectId, status)
      if (scope !== useAuth().storageScope.value) return
      const project = this.projects.find(item => item.projectId === projectId)
      if (project) project.status = updated.status
    },
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
    // 派生字段（阶段、区域进度）只能由列表接口给出；进入工作台列表时静默重取，
    // 保留现有数据避免闪烁，不触碰 office 画布状态。首次加载仍由 init() 负责。
    async refreshProjects() {
      if (!this.initialized || this.loading) return
      this.loading = true
      const scope = useAuth().storageScope.value
      try {
        const projects = await useWorkspaceApi().listProjects()
        if (scope !== useAuth().storageScope.value) return
        this.projects = projects
        this.error = ''
      } catch (error) {
        if (scope === useAuth().storageScope.value) this.error = error instanceof Error ? error.message : '项目列表暂时不可用'
      } finally {
        if (scope === useAuth().storageScope.value) this.loading = false
      }
    },
    async refresh(loadFloor: boolean) {
      useOfficeStore().$reset()
      this.$reset()
      await this.init()
      if (loadFloor) await useOfficeStore().init()
    },
    selectFloorContext(floorId: string) {
      const floor = this.projects.flatMap(project => project.floors).find(item => item.floorId === floorId)
      if (!floor) return
      this.currentFloorId = floor.floorId
      this.currentProjectId = floor.projectId
      this.rememberSelection()
    },
    async activateFloor(floorId: string, openDrawing = false) {
      const floor = this.projects.flatMap(project => project.floors).find(item => item.floorId === floorId)
      if (!floor) return
      const router = useRouter()
      const sourceRoute = router.currentRoute.value
      const office = useOfficeStore()
      if (floorId === this.currentFloorId && office.initialized && office.workspaceId === floorId) {
        if (openDrawing) await router.push(projectDrawingRoute(floorId))
        return
      }
      // 同一楼层的重复请求等待当前加载结束，避免同时切换画布和清单。
      if (this.switching) {
        if (activeFloorTransition?.floorId === floorId) await activeFloorTransition.completion
        if (openDrawing && floorId === this.currentFloorId && office.initialized && office.workspaceId === floorId) await router.push(projectDrawingRoute(floorId))
        return
      }
      let finishTransition!: () => void
      const completion = new Promise<void>(resolve => { finishTransition = resolve })
      activeFloorTransition = { floorId, completion }
      this.switching = true
      this.error = ''
      try {
        this.currentFloorId = floor.floorId
        this.currentProjectId = floor.projectId
        this.rememberSelection()
        await office.activateFloor(floor.floorId)
        if (openDrawing) await router.push(projectDrawingRoute(floorId))
        else if (router.currentRoute.value === sourceRoute && sourceRoute.query.floorId !== floorId) {
          const { path, query } = router.currentRoute.value
          // 楼层数据就绪后同步地址。
          const overview = path === projectBenchPath && query.view === 'overview'
          const design = path === '/canvas' && query.mode === 'design'
          await router.replace(overview ? projectOverviewRoute(floorId) : {
            path,
            query: { floorId, mode: design ? 'design' : undefined, view: design && query.view === 'products' ? 'products' : undefined },
          })
        }
      } catch (error) {
        this.error = error instanceof Error ? error.message : '楼层切换失败'
      } finally {
        this.switching = false
        finishTransition()
        if (activeFloorTransition?.completion === completion) activeFloorTransition = null
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
      await this.activateFloor(project.floors[0]!.floorId, true)
      return project
    },
    async addFloor(floorName: string) {
      if (!this.currentProject) throw new Error('请先选择项目')
      const floor = await useWorkspaceApi().addFloor(this.currentProject.projectId, floorName)
      this.currentProject.floors.push(floor)
      this.currentProject.floors.sort((a, b) => a.sortOrder - b.sortOrder)
      this.collapsedProjectIds = this.collapsedProjectIds.filter(id => id !== this.currentProjectId)
      await this.activateFloor(floor.floorId, true)
      return floor
    },
    async updateProjectName(projectId: string, projectName: string) {
      const updated = await useWorkspaceApi().updateProject(projectId, projectName)
      const project = this.projects.find(item => item.projectId === projectId)
      if (project) project.projectName = updated.projectName
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
      if (!this.projects.some(item => item.projectId === projectId)) throw new Error('请先选择项目')
      const updated = await useWorkspaceApi().updateRequirements(projectId, customerRequirements)
      const project = this.projects.find(item => item.projectId === updated.projectId)
      if (project && updated.requirementsRevision >= project.requirementsRevision) {
        project.customerRequirements = updated.customerRequirements
        project.requirementsRevision = updated.requirementsRevision
      }
      return updated
    },
    async updateMembers(projectId: string, leaderUserId: string, collaboratorUserIds: string[],
      permissions?: Record<string, import('~/types/workspace').ProjectPermission[]>) {
      const updated = await useWorkspaceApi().replaceMembers(projectId, leaderUserId, collaboratorUserIds, permissions)
      const project = this.projects.find(item => item.projectId === projectId)
      if (project) {
        project.leader = updated.leader
        project.collaborators = updated.collaborators
      }
      return updated
    },
    async updateReferenceImages(projectId: string, referenceImages: ProjectReferenceImage[]) {
      const updated = await useWorkspaceApi().updateReferences(projectId, referenceImages)
      const project = this.projects.find(item => item.projectId === projectId)
      if (project) project.referenceImages = updated.referenceImages
      return updated
    },
  },
})
