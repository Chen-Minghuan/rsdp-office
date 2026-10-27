<script setup lang="ts">
withDefaults(defineProps<{ compact?: boolean }>(), { compact: false })
const workspace = useWorkspaceStore()
const office = useOfficeStore()
const route = useRoute()
const router = useRouter()
const showCreate = ref(false)
const showSettings = ref(false)
const showRequirements = ref(false)
const addingFloor = ref(false)
const floorName = ref('')
const actionError = ref('')
const savingFloor = ref(false)
const editingRequirements = ref(false)
const requirementsValue = ref('')
const requirementsOriginal = ref('')
const requirementsProjectId = ref('')
const savingRequirements = ref(false)
const requirementsInput = ref<HTMLTextAreaElement | null>(null)
const editingKey = ref('')
const editingName = ref('')
const editingOriginal = ref('')
const editingProjectId = ref('')
const editingFloorId = ref('')
const savingEdit = ref(false)
const nameInput = ref<HTMLInputElement | HTMLInputElement[] | null>(null)

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

function startRequirementsEdit() {
  if (!workspace.currentProject || savingRequirements.value) return
  actionError.value = ''
  requirementsProjectId.value = workspace.currentProject.projectId
  requirementsValue.value = workspace.currentProject.customerRequirements
  requirementsOriginal.value = requirementsValue.value
  editingRequirements.value = true
  nextTick(() => requirementsInput.value?.focus())
}

function cancelRequirementsEdit() {
  if (!savingRequirements.value) editingRequirements.value = false
}

async function saveRequirementsEdit() {
  if (!editingRequirements.value || savingRequirements.value) return
  const value = requirementsValue.value.trim()
  if (value === requirementsOriginal.value.trim()) { editingRequirements.value = false; return }
  savingRequirements.value = true
  actionError.value = ''
  try {
    await workspace.updateRequirements(value, requirementsProjectId.value)
    editingRequirements.value = false
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : '客户需求保存失败'
    nextTick(() => requirementsInput.value?.focus())
  } finally {
    savingRequirements.value = false
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
  if (!office.enterDesign(zoneId)) return
  if (route.path !== '/canvas') {
    await router.push({ path: '/canvas', query: { floorId: workspace.currentFloorId, mode: 'design', zoneId } })
  }
}
</script>

<template>
  <section class="project-floor-tree" aria-label="项目与楼层">
    <div class="mb-1 flex items-center justify-between px-1">
      <span class="text-[11px] font-medium tracking-wider text-muted">项目与楼层</span>
      <button class="rounded-none px-1.5 py-0.5 text-[11px] font-semibold text-bronze-dark hover:bg-cream" @click="showCreate = true">＋ 新建项目</button>
    </div>
    <div class="grid">
      <article v-for="project in workspace.projects" :key="project.projectId" class="border-y border-line bg-white">
        <div class="flex items-center" :class="project.projectId === workspace.currentProjectId ? 'bg-cream' : ''">
          <input v-if="editingKey === `project:${project.projectId}`" ref="nameInput" v-model="editingName" class="mx-2 min-w-0 flex-1 rounded-none border border-bronze bg-white px-2 py-1 text-xs font-semibold" aria-label="双击编辑项目名称" :disabled="savingEdit" @keydown.enter.prevent="saveEdit" @keydown.esc.prevent="cancelEdit" @blur="saveEdit">
          <button v-else class="min-w-0 flex-1 px-2 py-2 text-left" :aria-expanded="!workspace.collapsedProjectIds.includes(project.projectId)" title="单击折叠，双击修改项目名称" @click="workspace.toggleProject(project.projectId)" @dblclick.stop.prevent="startEdit(`project:${project.projectId}`, project.projectId, '', project.projectName)">
            <span class="flex items-center gap-1.5"><span class="text-[10px] text-muted">{{ workspace.collapsedProjectIds.includes(project.projectId) ? '▶' : '▼' }}</span><strong class="truncate text-xs">{{ project.projectName }}</strong></span>
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
              <button class="flex min-w-0 flex-1 items-center justify-between px-2 py-1.5 text-left text-xs" :aria-current="floor.floorId === workspace.currentFloorId ? 'page' : undefined" title="单击切换楼层，双击修改楼层名称" @click="workspace.activateFloor(floor.floorId)" @dblclick.stop.prevent="startEdit(`floor:${floor.floorId}`, project.projectId, floor.floorId, floor.floorName)">
                <span class="truncate">{{ floor.floorName }}</span><span v-if="floor.floorId === workspace.currentFloorId" class="text-[9px] font-normal opacity-80">当前</span>
              </button>
              <button v-if="floor.floorId === workspace.currentFloorId" class="mr-1 shrink-0 rounded-none px-1 py-0.5 text-[10px] hover:bg-white/15" :aria-label="`${workspace.collapsedFloorIds.includes(floor.floorId) ? '展开' : '折叠'}${floor.floorName}空间`" :aria-expanded="!workspace.collapsedFloorIds.includes(floor.floorId)" :title="workspace.collapsedFloorIds.includes(floor.floorId) ? '展开空间' : '折叠空间'" @click="workspace.toggleFloorSpaces(floor.floorId)">
                {{ workspace.collapsedFloorIds.includes(floor.floorId) ? '▶' : '▼' }}
              </button>
            </div>
            <OfficeCanvasDesignSpaceSwitcher v-if="floor.floorId === workspace.currentFloorId" v-show="!workspace.collapsedFloorIds.includes(floor.floorId)" :zone-id="office.designZoneId" @select="selectSpace" />
          </template>
          <button v-if="project.projectId === workspace.currentProjectId" class="rounded-none px-2 py-1 text-left text-[11px] text-bronze-dark hover:bg-bg" @click="addingFloor = !addingFloor">＋ 添加新楼层</button>
        </div>
      </article>
    </div>
    <form v-if="addingFloor" class="mt-1.5 flex gap-1" @submit.prevent="addFloor">
      <input v-model="floorName" class="min-w-0 flex-1 rounded-none border border-line bg-white px-2 py-1.5 text-xs" placeholder="如 3F / B1F" aria-label="新楼层名称">
      <button class="rounded-none bg-bronze px-2 text-xs text-white disabled:opacity-50" :disabled="savingFloor">添加</button>
    </form>
    <p v-if="actionError || workspace.error" role="alert" class="mt-1.5 px-1 text-[10px] leading-4 text-red-700">{{ actionError || workspace.error }}</p>
    <div v-if="workspace.currentProject" class="mt-2 w-full border-t border-line px-2 py-2 text-left hover:bg-cream" title="双击直接填写客户需求" @dblclick="startRequirementsEdit">
      <div class="flex items-center justify-between text-[11px] font-semibold"><span>客户需求</span><button class="rounded-none px-1 text-bronze-dark hover:bg-white" @click="showRequirements = true" @dblclick.stop>编辑</button></div>
      <textarea v-if="editingRequirements" ref="requirementsInput" v-model="requirementsValue" rows="4" maxlength="4000" class="mt-1.5 w-full resize-y rounded-none border border-bronze bg-white px-2 py-1.5 text-[11px] leading-5" aria-label="双击填写客户需求" :disabled="savingRequirements" placeholder="填写客户需求与注意事项" @dblclick.stop @keydown.ctrl.enter.prevent="saveRequirementsEdit" @keydown.meta.enter.prevent="saveRequirementsEdit" @keydown.esc.prevent="cancelRequirementsEdit" @blur="saveRequirementsEdit" />
      <template v-if="editingRequirements"><p class="mt-1 text-[9px] text-muted">Ctrl+Enter 保存 · Esc 取消</p></template>
      <p v-else class="mt-1 line-clamp-2 text-[10px] leading-4 text-muted">{{ workspace.currentProject.customerRequirements || '暂无需求备注，双击填写' }}</p>
    </div>
    <ProjectCreateDialog v-if="showCreate" @close="showCreate = false" />
    <ProjectSettingsDialog v-if="showSettings" @close="showSettings = false" />
    <CustomerRequirementsDialog v-if="showRequirements" @close="showRequirements = false" />
  </section>
</template>
