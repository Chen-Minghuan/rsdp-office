<script setup lang="ts">
import { projectBenchPath, projectDrawingRoute } from '~/utils/projectBenchRoutes'

withDefaults(defineProps<{ compact?: boolean; requirements?: boolean }>(), { compact: false, requirements: true })
const workspace = useWorkspaceStore()
const office = useOfficeStore()
const { canCreateProject } = useAuth()
const route = useRoute()
const router = useRouter()
const { isProjectWorkflow, floorReady } = useWorkspaceNavigation()
const showCreate = ref(false)
const showProjectList = ref(false)
const showSettings = ref(false)
const showRequirements = ref(false)
const addingFloor = ref(false)
const floorName = ref('')
const actionError = ref('')
const savingFloor = ref(false)
const editingKey = ref('')
const editingName = ref('')
const editingOriginal = ref('')
const editingProjectId = ref('')
const editingFloorId = ref('')
const savingEdit = ref(false)
const nameInput = ref<HTMLInputElement | HTMLInputElement[] | null>(null)
const projectListId = 'project-list-popover'
const visibleProjects = computed(() => workspace.currentProject ? [workspace.currentProject] : [])

function closeProjectList() {
  showProjectList.value = false
}

function toggleProjectList() {
  showProjectList.value = !showProjectList.value
}

function openCreateDialog() {
  closeProjectList()
  showCreate.value = true
}

async function selectProject(projectId: string) {
  if (workspace.switching) return
  actionError.value = ''
  if (projectId === workspace.currentProjectId) {
    closeProjectList()
    return
  }
  const project = workspace.projects.find(item => item.projectId === projectId)
  const floor = project?.floors[0]
  if (!floor) {
    actionError.value = '该项目暂无可切换楼层'
    return
  }
  closeProjectList()
  await workspace.activateFloor(floor.floorId, true)
}

function focusNameInput(select = false) {
  const input = Array.isArray(nameInput.value) ? nameInput.value[0] : nameInput.value
  input?.focus()
  if (select) input?.select()
}

function startEdit(key: string, projectId: string, floorId: string, name: string) {
  if (savingEdit.value) return
  actionError.value = ''
  editingKey.value = key
  editingProjectId.value = projectId
  editingFloorId.value = floorId
  editingName.value = name
  editingOriginal.value = name
  nextTick(() => focusNameInput(true))
}

function cancelEdit() {
  if (!savingEdit.value) editingKey.value = ''
}

async function saveEdit() {
  if (!editingKey.value || savingEdit.value) return
  const name = editingName.value.trim()
  if (!name) { actionError.value = '名称不能为空'; focusNameInput(); return }
  if (name === editingOriginal.value) { editingKey.value = ''; return }
  savingEdit.value = true
  actionError.value = ''
  try {
    if (editingKey.value.startsWith('project:')) await workspace.updateProjectName(editingProjectId.value, name)
    else await workspace.updateFloorName(editingProjectId.value, editingFloorId.value, name)
    editingKey.value = ''
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : '名称保存失败'
    nextTick(() => focusNameInput())
  } finally {
    savingEdit.value = false
  }
}

async function addFloor() {
  actionError.value = ''
  if (!floorName.value.trim()) { actionError.value = '请输入楼层名称'; return }
  savingFloor.value = true
  try {
    await workspace.addFloor(floorName.value.trim())
    floorName.value = ''
    addingFloor.value = false
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : '添加楼层失败'
  } finally {
    savingFloor.value = false
  }
}

async function selectSpace(zoneId: string) {
  if (route.path === '/canvas' && office.canvasMode === 'overview') {
    office.setActiveZone(zoneId)
    return
  }
  if (!office.enterDesign(zoneId)) return
  if (route.path !== '/canvas') {
    await router.push({ path: '/canvas', query: { floorId: workspace.currentFloorId, mode: 'design', zoneId } })
  }
}

async function selectFloor(floorId: string) {
  if (workspace.switching) return
  await workspace.activateFloor(floorId)
  if (workspace.currentFloorId !== floorId || workspace.error) return
  if (!office.zones.length || !isProjectWorkflow.value) {
    await router.push(office.zones.length ? { path: '/canvas', query: { floorId } } : projectDrawingRoute(floorId))
  }
}
</script>

<template>
  <section class="project-floor-tree" aria-label="项目与楼层">
    <ProjectRequirementsPanel v-if="requirements" class="mb-3 px-1 pt-1" />
    <NuxtLink
      v-if="workspace.initialized && !workspace.projects.length && !canCreateProject"
      :to="projectBenchPath"
      class="block hover:bg-cream"
    >
      <span class="mb-1 block px-1 text-[11px] font-medium tracking-wider text-muted">项目与楼层</span>
      <span class="block border-y border-line px-2 py-3 text-[11px] leading-5 text-muted">当前账号暂无可访问项目</span>
    </NuxtLink>
    <div v-else class="mb-1 flex items-center justify-between px-1">
      <span class="text-[11px] font-medium tracking-wider text-muted">项目与楼层</span>
      <div class="relative flex items-center gap-0.5">
        <button v-if="canCreateProject" class="relative left-[12px] rounded-none px-1.5 py-0.5 text-[11px] font-semibold text-bronze-dark hover:bg-cream" @click="openCreateDialog">＋ 新建项目</button>
        <button
          v-if="workspace.projects.length"
          type="button"
          class="relative left-[12px] rounded-none px-1.5 py-0.5 text-[11px] font-semibold"
          :class="showProjectList ? 'bg-cream text-bronze-dark' : 'text-bronze-dark hover:bg-cream'"
          :aria-expanded="showProjectList"
          :aria-pressed="showProjectList"
          :aria-controls="projectListId"
          @click="toggleProjectList"
        >项目列表</button>
        <div
          v-if="showProjectList"
          :id="projectListId"
          data-testid="project-list-popover"
          class="absolute right-[-14px] top-[calc(100%+4px)] z-50 max-h-[min(24rem,calc(100dvh-10rem))] w-59 overflow-y-auto border border-line bg-white py-1 shadow-panel"
        >
          <button
            v-for="project in workspace.projects"
            :key="project.projectId"
            type="button"
            class="grid w-full gap-0.5 px-3 py-2 text-left text-xs transition disabled:cursor-wait disabled:opacity-60"
            :class="project.projectId === workspace.currentProjectId ? 'bg-cream text-bronze-dark' : 'text-[#5f5b56] hover:bg-bg'"
            :aria-label="`切换到项目 ${project.projectName}`"
            :aria-current="project.projectId === workspace.currentProjectId ? 'true' : undefined"
            :disabled="workspace.switching"
            @click="selectProject(project.projectId)"
          >
            <span class="flex min-w-0 items-center gap-1.5"><strong class="truncate font-semibold">{{ project.projectName }}</strong><span v-if="project.projectId === workspace.currentProjectId" class="shrink-0 text-[10px] font-normal">当前</span></span>
            <span class="truncate text-[10px] leading-4 opacity-75"><template v-if="project.leader">负责人 {{ project.leader.displayName || project.leader.username }} · </template>{{ project.floors.length }} 个楼层<template v-if="project.collaborators.length"> · 协作 {{ project.collaborators.length }} 人</template></span>
          </button>
        </div>
      </div>
    </div>
    <div class="grid">
      <article v-for="project in visibleProjects" :key="project.projectId" class="border-y border-line bg-white">
        <div class="flex items-center" :class="project.projectId === workspace.currentProjectId ? 'bg-cream' : ''">
          <input v-if="editingKey === `project:${project.projectId}`" ref="nameInput" v-model="editingName" class="mx-2 min-w-0 flex-1 rounded-none border border-bronze bg-white px-2 py-1 text-xs font-semibold" aria-label="双击编辑项目名称" :disabled="savingEdit" @keydown.enter.prevent="saveEdit" @keydown.esc.prevent="cancelEdit" @blur="saveEdit">
          <button v-else class="min-w-0 flex-1 px-2 py-2 text-left" :aria-expanded="!workspace.collapsedProjectIds.includes(project.projectId)" title="单击折叠，双击修改项目名称" @click="workspace.toggleProject(project.projectId)" @dblclick.stop.prevent="startEdit(`project:${project.projectId}`, project.projectId, '', project.projectName)">
            <span class="flex items-center gap-1.5"><span class="text-[10px] text-muted">{{ workspace.collapsedProjectIds.includes(project.projectId) ? '▶' : '▼' }}</span><strong class="truncate text-xs">{{ project.projectName }}</strong></span>
            <span v-if="project.leader" class="mt-0.5 block truncate text-[10px] text-muted">负责人 {{ project.leader.displayName || project.leader.username }}<template v-if="project.collaborators.length"> · 协作 {{ project.collaborators.length }} 人</template></span>
          </button>
          <div v-if="project.projectId === workspace.currentProjectId" class="mr-1.5 flex shrink-0 items-center gap-0.5">
            <button class="rounded-none px-1 py-0.5 text-[10px] text-bronze-dark hover:bg-white" title="编辑项目名称和楼层名称" @click="showSettings = true">编辑</button>
            <button class="rounded-none px-1 py-0.5 text-[10px] text-bronze-dark hover:bg-white" title="查看或修改客户需求" @click="showRequirements = true">需求</button>
          </div>
        </div>
        <div v-show="!workspace.collapsedProjectIds.includes(project.projectId)" class="grid gap-0.5 border-t border-line p-1.5">
          <template v-for="floor in project.floors" :key="floor.floorId">
            <input v-if="editingKey === `floor:${floor.floorId}`" ref="nameInput" v-model="editingName" class="mx-1 rounded-none border border-bronze bg-white px-2 py-1 text-xs" aria-label="双击编辑楼层名称" :disabled="savingEdit" @keydown.enter.prevent="saveEdit" @keydown.esc.prevent="cancelEdit" @blur="saveEdit">
            <div v-else class="flex items-center rounded-none transition" :class="floor.floorId === workspace.currentFloorId ? 'bg-bronze font-semibold text-white' : 'text-[#5f5b56] hover:bg-bg'">
              <button class="flex min-w-0 flex-1 items-center justify-between px-2 py-1.5 text-left text-xs" :aria-current="floor.floorId === workspace.currentFloorId ? 'page' : undefined" title="单击切换楼层，双击修改楼层名称" @click="selectFloor(floor.floorId)" @dblclick.stop.prevent="startEdit(`floor:${floor.floorId}`, project.projectId, floor.floorId, floor.floorName)">
                <span class="truncate">{{ floor.floorName }}</span><span v-if="floor.floorId === workspace.currentFloorId" class="text-[9px] font-normal opacity-80">当前</span>
              </button>
              <button v-if="floor.floorId === workspace.currentFloorId" class="mr-1 shrink-0 rounded-none px-1 py-0.5 text-[10px] hover:bg-white/15" :aria-label="`${workspace.collapsedFloorIds.includes(floor.floorId) ? '展开' : '折叠'}${floor.floorName}空间`" :aria-expanded="!workspace.collapsedFloorIds.includes(floor.floorId)" :title="workspace.collapsedFloorIds.includes(floor.floorId) ? '展开空间' : '折叠空间'" @click="workspace.toggleFloorSpaces(floor.floorId)">
                {{ workspace.collapsedFloorIds.includes(floor.floorId) ? '▶' : '▼' }}
              </button>
            </div>
            <OfficeCanvasDesignSpaceSwitcher
              v-if="floorReady && floor.floorId === workspace.currentFloorId"
              v-show="!workspace.collapsedFloorIds.includes(floor.floorId)"
              :mode="route.path === '/canvas' && office.canvasMode === 'overview' ? 'review' : 'design'"
              :zone-id="route.path === '/canvas' && office.canvasMode === 'overview' ? office.activeZoneId : office.designZoneId"
              @select="selectSpace"
            />
          </template>
          <button v-if="project.projectId === workspace.currentProjectId" class="rounded-none px-2 py-1 text-left text-[11px] text-bronze-dark hover:bg-bg" @click="addingFloor = !addingFloor">＋ 添加新楼层</button>
        </div>
      </article>
    </div>
    <p v-if="canCreateProject && workspace.initialized && !workspace.projects.length" class="border-y border-line px-2 py-3 text-[11px] leading-5 text-muted">当前账号暂无可访问项目</p>
    <form v-if="addingFloor" class="mt-1.5 flex gap-1" @submit.prevent="addFloor">
      <input v-model="floorName" class="min-w-0 flex-1 rounded-none border border-line bg-white px-2 py-1.5 text-xs" placeholder="如 3F / B1F" aria-label="新楼层名称">
      <button class="rounded-none bg-bronze px-2 text-xs text-white disabled:opacity-50" :disabled="savingFloor">添加</button>
    </form>
    <p v-if="actionError || workspace.error" role="alert" class="mt-1.5 px-1 text-[10px] leading-4 text-red-700">{{ actionError || workspace.error }}</p>
    <ProjectCreateDialog v-if="showCreate && canCreateProject" @close="showCreate = false" />
    <ProjectSettingsDialog v-if="showSettings" @close="showSettings = false" />
    <CustomerRequirementsDialog v-if="showRequirements" @close="showRequirements = false" />
  </section>
</template>
