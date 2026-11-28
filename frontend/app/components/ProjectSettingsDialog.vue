<script setup lang="ts">
import { authEnabled } from '~/composables/useAuth'
import type { AssignableUser, ProjectPermission, ProjectReferenceImage } from '~/types/workspace'
import { projectPermissionsFor } from '~/utils/projectPermissions'

const emit = defineEmits<{ close: [] }>()
const workspace = useWorkspaceStore()
const { me } = useAuth()
const project = workspace.currentProject
const projectName = ref(project?.projectName || '')
const floors = ref((project?.floors || []).map(floor => ({ floorId: floor.floorId, floorName: floor.floorName })))
const leader = ref<AssignableUser | null>(project?.leader ? { ...project.leader } : null)
const collaborators = ref<AssignableUser[]>(project?.collaborators.map(user => ({ ...user })) || [])
const referenceImages = ref<ProjectReferenceImage[]>(project?.referenceImages.map(image => ({ ...image })) || [])
const canManageMembers = ref(!authEnabled)
const canGrant = ref(false)
const canEdit = ref(!authEnabled)
const members = computed(() => [
  ...(leader.value ? [{ ...leader.value, title: '负责人' }] : []),
  ...collaborators.value.map(member => ({ ...member, title: '协作者' })),
])
const originalMembers = [...(project?.leader ? [project.leader] : []), ...(project?.collaborators ?? [])]
const grants = reactive<Record<string, ProjectPermission[]>>(Object.fromEntries(
  originalMembers.map(member => [member.userId, [...(member.permissions ?? [])]]),
))
const permissionChoices: { key: ProjectPermission; label: string }[] = [
  { key: 'READ', label: '项目查看' },
  { key: 'EDIT', label: '项目编辑' },
  { key: 'QUOTE_READ', label: '报价查看' },
  { key: 'MARGIN_READ', label: '毛利查看' },
  { key: 'EXPORT_PROJECT', label: '方案导出' },
  { key: 'EXPORT_QUOTE', label: '报价导出' },
  { key: 'MANAGE_MEMBERS', label: '成员管理' },
]
const saving = ref(false)
const error = ref('')
const deleteDialog = ref<HTMLDialogElement | null>(null)
const deletingFloor = ref<{ floorId: string, floorName: string } | null>(null)
const deleting = ref(false)
const deleteError = ref('')

function askDeleteFloor(floor: { floorId: string, floorName: string }) {
  if (saving.value || deleting.value || floors.value.length <= 1) return
  deleteError.value = ''
  deletingFloor.value = floor
  nextTick(() => deleteDialog.value?.showModal())
}

function cancelDeleteFloor() {
  if (deleting.value) return
  deleteDialog.value?.close()
  deletingFloor.value = null
}

async function confirmDeleteFloor() {
  const floor = deletingFloor.value
  if (!floor || deleting.value) return
  deleting.value = true
  deleteError.value = ''
  try {
    await workspace.deleteFloor(floor.floorId)
    floors.value = floors.value.filter(item => item.floorId !== floor.floorId)
    deleteDialog.value?.close()
    deletingFloor.value = null
  } catch (cause) {
    deleteError.value = cause instanceof Error ? cause.message : '楼层删除失败'
  } finally {
    deleting.value = false
  }
}

onMounted(async () => {
  if (!authEnabled) return
  const user = await me()
  const permissions = projectPermissionsFor(project, user?.userId)
  canGrant.value = user?.role === 'DEALER'
  canEdit.value = canGrant.value || permissions.includes('EDIT')
  canManageMembers.value = canGrant.value || permissions.includes('MANAGE_MEMBERS')
})

function togglePermission(userId: string, permission: ProjectPermission, checked: boolean) {
  const flags = new Set(grants[userId] || [])
  if (checked) {
    flags.add('READ')
    flags.add(permission)
    if (permission === 'MARGIN_READ' || permission === 'EXPORT_QUOTE') flags.add('QUOTE_READ')
  } else if (permission === 'READ') {
    flags.clear()
  } else {
    flags.delete(permission)
    if (permission === 'QUOTE_READ') {
      flags.delete('MARGIN_READ')
      flags.delete('EXPORT_QUOTE')
    }
  }
  grants[userId] = [...flags]
}

async function save() {
  error.value = ''
  const name = projectName.value.trim()
  const names = floors.value.map(floor => floor.floorName.trim())
  if (!name) { error.value = '请输入项目名称'; return }
  if (names.some(floorName => !floorName)) { error.value = '楼层名称不能为空'; return }
  if (new Set(names.map(floorName => floorName.toLowerCase())).size !== names.length) { error.value = '楼层名称不能重复'; return }
  if (authEnabled && !leader.value) { error.value = '项目负责人不能为空'; return }
  saving.value = true
  try {
    if (!project) throw new Error('请先选择项目')
    const projectId = project.projectId
    const detailsChanged = name !== project.projectName || floors.value.some((floor, index) => names[index] !== project.floors[index]?.floorName)
    if (canEdit.value && detailsChanged) {
      await workspace.updateProjectDetails(name, floors.value.map((floor, index) => ({ ...floor, floorName: names[index]! })))
    }
    if (leader.value && canManageMembers.value) {
      const membersChanged = leader.value.userId !== project?.leader?.userId
        || collaborators.value.map(user => user.userId).join() !== (project?.collaborators || []).map(user => user.userId).join()
      const permissions = Object.fromEntries(members.value.map(member => [member.userId, grants[member.userId] ?? []]))
      const grantsChanged = canGrant.value && members.value.some(member => {
        const before = originalMembers.find(item => item.userId === member.userId)?.permissions ?? []
        const after = permissions[member.userId] ?? []
        return before.length !== after.length || before.some(permission => !after.includes(permission))
      })
      if (membersChanged || grantsChanged) await workspace.updateMembers(projectId, leader.value.userId,
        collaborators.value.map(user => user.userId), canGrant.value ? permissions : undefined)
    }
    const imagesChanged = JSON.stringify(referenceImages.value) !== JSON.stringify(project?.referenceImages || [])
    if (canEdit.value && imagesChanged) await workspace.updateReferenceImages(projectId, referenceImages.value)
    emit('close')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '项目信息保存失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50 grid place-items-center bg-black/30 p-4" @click.self="emit('close')">
      <form class="w-full max-w-3xl rounded-2xl bg-white p-6 shadow-2xl" @submit.prevent="save">
        <div class="mb-5 flex items-start justify-between">
          <div><h2 class="text-lg font-bold">编辑项目与楼层</h2><p class="mt-1 text-xs text-muted">修改名称不会改变楼层 ID，已有图纸和设计数据保持关联；删除楼层会同时删除该楼层的全部设计数据。</p></div>
          <button type="button" class="rounded-lg p-2 text-muted hover:bg-cream" aria-label="关闭项目编辑" @click="emit('close')"><AppIcon name="close" /></button>
        </div>
        <div class="grid max-h-[65vh] gap-5 overflow-y-auto pr-1">
          <label class="grid gap-2 text-sm font-semibold">项目名称
            <input v-model="projectName" :disabled="!canEdit" maxlength="200" class="rounded-xl border border-line px-4 py-3 text-sm font-normal outline-none focus:border-bronze" aria-label="编辑项目名称">
          </label>
          <section class="grid gap-2">
            <h3 class="text-sm font-semibold">楼层名称</h3>
            <div v-for="(floor, index) in floors" :key="floor.floorId" class="flex items-center gap-3 text-xs text-muted">
              <span class="w-12 shrink-0">楼层 {{ index + 1 }}</span>
              <input v-model="floor.floorName" :disabled="!canEdit" maxlength="200" class="min-w-0 flex-1 rounded-xl border border-line px-4 py-2.5 text-sm text-ink outline-none focus:border-bronze" :aria-label="`编辑楼层名称 ${index + 1}`">
              <button type="button" class="shrink-0 rounded-lg p-1.5 text-muted transition hover:text-red-600 disabled:opacity-30"
                :disabled="!canEdit || saving || floors.length <= 1"
                :title="floors.length <= 1 ? '项目至少需要保留一个楼层' : '删除楼层'"
                :aria-label="`删除楼层 ${floor.floorName}`" @click="askDeleteFloor(floor)">
                <AppIcon name="trash" size="4" />
              </button>
            </div>
          </section>
          <ProjectMemberEditor v-model:leader="leader" v-model:collaborators="collaborators"
            :leader-optional="!authEnabled" :leader-disabled="authEnabled && !canGrant" :disabled="!canManageMembers" />
          <section v-if="canGrant && members.length" class="overflow-x-auto rounded-xl border border-line">
            <table class="w-full whitespace-nowrap text-left text-xs" aria-label="项目成员权限">
              <thead class="bg-cream text-muted"><tr>
                <th scope="col" class="p-2">成员</th>
                <th v-for="permission in permissionChoices" :key="permission.key" scope="col" class="p-2 text-center">{{ permission.label }}</th>
              </tr></thead>
              <tbody>
                <tr v-for="member in members" :key="member.userId" class="border-t border-line">
                  <th scope="row" class="p-2">{{ member.displayName || member.username }} · {{ member.title }}</th>
                  <td v-for="permission in permissionChoices" :key="permission.key" class="p-2 text-center">
                    <input type="checkbox" :checked="grants[member.userId]?.includes(permission.key)" :disabled="saving"
                      :aria-label="`${member.displayName || member.username}的${permission.label}`"
                      @change="togglePermission(member.userId, permission.key, ($event.target as HTMLInputElement).checked)">
                  </td>
                </tr>
              </tbody>
            </table>
          </section>
          <ProjectReferenceImagesEditor v-if="canEdit" v-model:images="referenceImages" />
        </div>
        <p v-if="error" role="alert" class="mt-4 text-xs text-red-700">{{ error }}</p>
        <div class="mt-5 flex justify-end gap-3">
          <button type="button" class="rounded-xl border border-line px-4 py-2 text-sm" @click="emit('close')">关闭</button>
          <button v-if="canEdit || canManageMembers" type="submit" class="rounded-xl bg-bronze px-4 py-2 text-sm font-semibold text-white disabled:opacity-50" :disabled="saving">{{ saving ? '保存中…' : '保存修改' }}</button>
        </div>
      </form>
      <dialog ref="deleteDialog" class="m-auto w-[min(400px,calc(100%_-_32px))] rounded-2xl border border-line bg-white p-0 text-ink shadow-panel backdrop:bg-black/40" aria-label="删除楼层" @cancel="event => { if (deleting) event.preventDefault() }" @close="deletingFloor = null">
        <div class="p-6">
          <div class="mb-4 flex items-start justify-between gap-4">
            <h2 class="text-lg font-bold">删除楼层</h2>
            <button type="button" class="rounded-lg p-1.5 text-muted transition hover:bg-cream disabled:opacity-50" aria-label="关闭删除确认" :disabled="deleting" @click="cancelDeleteFloor"><AppIcon name="close" size="5" /></button>
          </div>
          <div class="mb-4 flex gap-3 rounded-xl border border-red-100 bg-red-50 p-3.5">
            <AppIcon name="warning" size="5" class="mt-0.5 flex-none text-[#b3402f]" />
            <p class="text-sm leading-6">确定删除楼层「<strong>{{ deletingFloor?.floorName }}</strong>」吗？该楼层的图纸、识别结果、画布设计、空间方案、排版、报价确认、效果图、空间模板和购物车条目将一并删除。</p>
          </div>
          <p class="mb-5 text-xs leading-5 text-muted">此操作不可恢复；项目的历史报价单保留，仍可在报价记录中查看。</p>
          <div v-if="deleteError" class="mb-4 rounded-lg border border-red-100 bg-red-50 px-3 py-2 text-xs text-red-800" role="alert">{{ deleteError }}</div>
          <div class="flex justify-end gap-3 border-t border-line pt-4">
            <button type="button" class="rounded-lg border border-line px-5 py-2.5 text-sm disabled:opacity-50" :disabled="deleting" @click="cancelDeleteFloor">取消</button>
            <button type="button" class="rounded-lg bg-[#b3402f] px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-[#9c3627] disabled:opacity-50" :disabled="deleting" @click="confirmDeleteFloor">{{ deleting ? '删除中…' : '确认删除' }}</button>
          </div>
        </div>
      </dialog>
    </div>
  </Teleport>
</template>
