<script setup lang="ts">
import { authEnabled } from '~/composables/useAuth'
import { workspaceOwnerLabel, workspaceTypeLabel, type WorkspaceType } from '~/utils/workspaceType'

interface AdminUserItem {
  userId: string
  username: string
  displayName: string
  role: string
  workspaceId?: string | null
  status: string
  lastLoginAt: string | null
  createdAt: string | null
}

const api = useOfficeApi()
const { me } = useAuth()
const authorized = ref(false)
const checking = ref(true)
const users = ref<AdminUserItem[]>([])
const workspaces = ref<Awaited<ReturnType<typeof api.listAdminWorkspaces>>>([])
const loading = ref(false)
const error = ref('')
const notice = ref('')

const wsName = ref('')
const wsType = ref<WorkspaceType>('DEALER')
const wsOwnerUsername = ref('')
const wsOwnerDisplayName = ref('')
const wsOwnerPassword = ref('')
const creatingWorkspace = ref(false)
const editingWorkspaceId = ref('')
const editedWorkspaceType = ref<WorkspaceType>('DEALER')
const savingWorkspaceType = ref(false)
const workspaceTypeByOwnerId = computed(() =>
  new Map(workspaces.value.map(workspace => [workspace.ownerUserId, workspace.workspaceType])),
)
type WorkspaceItem = (typeof workspaces.value)[number]

const resettingId = ref('')
const resetPassword = ref('')
const resetting = ref(false)
const busyUserId = ref('')
const deletingId = ref('')
const deleting = ref(false)

onMounted(async () => {
  checking.value = true
  try {
    if (!authEnabled) { authorized.value = true }
    else {
      const user = await me()
      authorized.value = user?.role === 'ADMIN'
    }
    if (authorized.value) await Promise.all([loadUsers(), loadWorkspaces()])
  } finally {
    checking.value = false
  }
})

async function loadUsers() {
  loading.value = true
  error.value = ''
  try {
    users.value = await api.listAdminUsers()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '用户列表加载失败'
  } finally {
    loading.value = false
  }
}

async function loadWorkspaces() {
  try {
    workspaces.value = await api.listAdminWorkspaces()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '工作区列表加载失败'
  }
}

async function createWorkspace() {
  error.value = ''
  notice.value = ''
  creatingWorkspace.value = true
  try {
    await api.createAdminWorkspace({
      name: wsName.value.trim(),
      workspaceType: wsType.value,
      ownerUsername: wsOwnerUsername.value.trim(),
      ownerDisplayName: wsOwnerDisplayName.value.trim(),
      ownerPassword: wsOwnerPassword.value,
    })
    notice.value = `已创建${workspaceTypeLabel(wsType.value)}「${wsName.value.trim()}」及主号 ${wsOwnerUsername.value.trim()}`
    wsName.value = ''
    wsOwnerUsername.value = ''
    wsOwnerDisplayName.value = ''
    wsOwnerPassword.value = ''
    await Promise.all([loadWorkspaces(), loadUsers()])
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '创建工作区失败'
  } finally {
    creatingWorkspace.value = false
  }
}

function startWorkspaceTypeEdit(workspace: WorkspaceItem) {
  editingWorkspaceId.value = workspace.workspaceId
  editedWorkspaceType.value = workspace.workspaceType
}

async function saveWorkspaceType() {
  const workspaceId = editingWorkspaceId.value
  if (!workspaceId || savingWorkspaceType.value) return
  error.value = ''
  notice.value = ''
  savingWorkspaceType.value = true
  try {
    const updated = await api.updateAdminWorkspaceType(workspaceId, editedWorkspaceType.value)
    workspaces.value = workspaces.value.map(workspace => workspace.workspaceId === workspaceId ? updated : workspace)
    editingWorkspaceId.value = ''
    notice.value = `「${updated.name}」已设置为${workspaceTypeLabel(updated.workspaceType)}`
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '工作区类型更新失败'
  } finally {
    savingWorkspaceType.value = false
  }
}

async function toggleStatus(user: AdminUserItem) {
  error.value = ''
  notice.value = ''
  busyUserId.value = user.userId
  try {
    const next = user.status === 'active' ? 'disabled' : 'active'
    await api.updateAdminUserStatus(user.userId, next)
    notice.value = next === 'disabled' ? `已停用 ${user.username}，其会话已失效` : `已启用 ${user.username}`
    await loadUsers()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '状态更新失败'
  } finally {
    busyUserId.value = ''
  }
}

function startReset(user: AdminUserItem) {
  resettingId.value = user.userId
  resetPassword.value = ''
  deletingId.value = ''
  error.value = ''
  notice.value = ''
}

function startDelete(user: AdminUserItem) {
  deletingId.value = user.userId
  resettingId.value = ''
  error.value = ''
  notice.value = ''
}

async function confirmDelete(user: AdminUserItem) {
  error.value = ''
  deleting.value = true
  try {
    await api.deleteAdminUser(user.userId)
    notice.value = `已删除账号 ${user.username}`
    deletingId.value = ''
    await loadUsers()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '删除账号失败'
  } finally {
    deleting.value = false
  }
}

async function confirmReset(user: AdminUserItem) {
  error.value = ''
  resetting.value = true
  try {
    await api.resetAdminUserPassword(user.userId, resetPassword.value)
    notice.value = `已重置 ${user.username} 的密码，其会话已失效`
    resettingId.value = ''
    resetPassword.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '重置密码失败'
  } finally {
    resetting.value = false
  }
}

function formatTime(value: string | null) {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}

function roleLabel(user: AdminUserItem) {
  if (user.role === 'ADMIN') return '系统管理员'
  if (user.role === 'DEALER') return workspaceOwnerLabel(workspaceTypeByOwnerId.value.get(user.userId))
  return user.workspaceId ? '工作区成员' : '未分配工作区'
}
</script>

<template>
  <div class="mx-auto w-full max-w-4xl p-6">
    <header class="mb-4">
      <h1 class="text-lg font-bold">用户管理</h1>
      <p class="mt-1 text-xs text-muted">系统管理员管理工厂和经销商的办公工作区及账号。项目角色在各项目中分配。</p>
    </header>

    <p v-if="checking" class="text-sm text-muted">正在核验管理员身份…</p>
    <p v-else-if="!authorized" class="border border-line bg-white px-4 py-6 text-sm text-muted">无权访问：只有系统管理员可以管理用户。</p>

    <template v-else>
      <form class="mb-5 border border-line bg-white p-4" @submit.prevent="createWorkspace">
        <h2 class="mb-1 text-sm font-semibold">创建工作区 <span class="ml-2 text-xs font-normal text-muted">{{ workspaces.length }} / 20</span></h2>
        <p class="mb-3 text-xs text-muted">主号在本工作区开通成员账号、管理项目并指定负责人。</p>
        <div class="grid gap-2 md:grid-cols-3">
          <input v-model="wsName" class="min-w-0 border border-line bg-white px-2 py-1.5 text-sm" placeholder="工作区名称" aria-label="工作区名称" maxlength="200">
          <select v-model="wsType" class="border border-line bg-white px-2 py-1.5 text-sm" aria-label="工作区类型">
            <option value="DEALER">经销商工作区</option>
            <option value="FACTORY">工厂工作区</option>
          </select>
          <input v-model="wsOwnerUsername" class="min-w-0 border border-line bg-white px-2 py-1.5 text-sm" placeholder="主号用户名（登录用）" aria-label="主号用户名" maxlength="32">
          <input v-model="wsOwnerDisplayName" class="min-w-0 border border-line bg-white px-2 py-1.5 text-sm" placeholder="主号显示名称" aria-label="主号显示名称" maxlength="64">
          <input v-model="wsOwnerPassword" type="password" class="min-w-0 border border-line bg-white px-2 py-1.5 text-sm" placeholder="主号初始密码（至少 6 位）" aria-label="主号初始密码">
          <button class="bg-bronze px-3 py-1.5 text-sm text-white disabled:opacity-50" :disabled="creatingWorkspace || workspaces.length >= 20">{{ creatingWorkspace ? '创建中…' : '创建工作区' }}</button>
        </div>
      </form>

      <div v-if="workspaces.length" class="mb-5 border border-line bg-white">
        <table class="w-full text-sm">
          <thead>
            <tr class="border-b border-line text-left text-xs text-muted">
              <th class="px-3 py-2 font-medium">工作区</th>
              <th class="px-3 py-2 font-medium">类型</th>
              <th class="px-3 py-2 font-medium">工作区主号</th>
              <th class="px-3 py-2 font-medium">账号数</th>
              <th class="px-3 py-2 font-medium">项目数</th>
              <th class="px-3 py-2 font-medium">创建时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="workspace in workspaces" :key="workspace.workspaceId" class="border-b border-line last:border-b-0">
              <td class="px-3 py-2 font-medium">{{ workspace.name }}</td>
              <td class="px-3 py-2">
                <div v-if="editingWorkspaceId === workspace.workspaceId" class="flex flex-wrap items-center gap-2">
                  <select v-model="editedWorkspaceType" class="border border-line bg-white px-2 py-1 text-xs" :aria-label="'修改 ' + workspace.name + ' 的工作区类型'" :disabled="savingWorkspaceType">
                    <option value="DEALER">经销商工作区</option>
                    <option value="FACTORY">工厂工作区</option>
                  </select>
                  <button class="text-xs text-bronze-dark" :disabled="savingWorkspaceType" @click="saveWorkspaceType">保存</button>
                  <button class="text-xs text-muted" :disabled="savingWorkspaceType" @click="editingWorkspaceId = ''">取消</button>
                </div>
                <template v-else>
                  {{ workspaceTypeLabel(workspace.workspaceType) }}
                  <button class="ml-1 text-xs text-bronze-dark" :disabled="savingWorkspaceType" @click="startWorkspaceTypeEdit(workspace)">修改</button>
                </template>
              </td>
              <td class="px-3 py-2">{{ workspace.ownerDisplayName }}（{{ workspace.ownerUsername }}）<span v-if="workspace.ownerStatus !== 'active'" class="ml-1 text-xs text-red-700">已停用</span></td>
              <td class="px-3 py-2 text-xs text-muted">{{ workspace.userCount }}</td>
              <td class="px-3 py-2 text-xs text-muted">{{ workspace.projectCount }}</td>
              <td class="px-3 py-2 text-xs text-muted">{{ formatTime(workspace.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <p v-if="error" role="alert" class="mb-2 text-xs text-red-700">{{ error }}</p>
      <p v-if="notice" class="mb-2 text-xs text-green">{{ notice }}</p>

      <div class="border border-line bg-white">
        <table class="w-full text-sm">
          <thead>
            <tr class="border-b border-line text-left text-xs text-muted">
              <th class="px-3 py-2 font-medium">用户名</th>
              <th class="px-3 py-2 font-medium">显示名称</th>
              <th class="px-3 py-2 font-medium">账号身份</th>
              <th class="px-3 py-2 font-medium">状态</th>
              <th class="px-3 py-2 font-medium">最近登录</th>
              <th class="px-3 py-2 font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading"><td colspan="6" class="px-3 py-4 text-center text-xs text-muted">加载中…</td></tr>
            <tr v-else-if="!users.length"><td colspan="6" class="px-3 py-4 text-center text-xs text-muted">暂无用户</td></tr>
            <tr v-for="user in users" :key="user.userId" class="border-b border-line last:border-b-0">
              <td class="px-3 py-2 font-medium">{{ user.username }}</td>
              <td class="px-3 py-2">{{ user.displayName }}</td>
              <td class="px-3 py-2"><span class="text-xs" :class="user.role === 'USER' ? 'text-muted' : 'text-bronze-dark font-semibold'">{{ roleLabel(user) }}</span></td>
              <td class="px-3 py-2"><span class="text-xs" :class="user.status === 'active' ? 'text-green' : 'text-red-700'">{{ user.status === 'active' ? '在用' : '已停用' }}</span></td>
              <td class="px-3 py-2 text-xs text-muted">{{ formatTime(user.lastLoginAt) }}</td>
              <td class="px-3 py-2">
                <span v-if="user.role === 'ADMIN'" class="text-xs text-muted">—</span>
                <template v-else>
                <div v-if="resettingId === user.userId" class="flex items-center gap-1">
                  <input v-model="resetPassword" type="password" class="w-32 border border-bronze px-2 py-1 text-xs" placeholder="新密码" :aria-label="'重置 ' + user.username + ' 的密码'" :disabled="resetting" @keydown.enter.prevent="confirmReset(user)" @keydown.esc.prevent="resettingId = ''">
                  <button class="px-2 py-1 text-xs text-bronze-dark hover:bg-cream disabled:opacity-50" :disabled="resetting" @click="confirmReset(user)">确认</button>
                  <button class="px-2 py-1 text-xs text-muted hover:bg-cream" :disabled="resetting" @click="resettingId = ''">取消</button>
                </div>
                <div v-else-if="deletingId === user.userId" class="flex items-center gap-1">
                  <span class="text-xs text-red-700">删除后不可恢复，确认？</span>
                  <button class="px-2 py-1 text-xs text-red-700 hover:bg-red-50 disabled:opacity-50" :disabled="deleting" @click="confirmDelete(user)">确认删除</button>
                  <button class="px-2 py-1 text-xs text-muted hover:bg-cream" :disabled="deleting" @click="deletingId = ''">取消</button>
                </div>
                <div v-else class="flex items-center gap-1">
                  <button class="px-2 py-1 text-xs text-bronze-dark hover:bg-cream disabled:opacity-50" :disabled="busyUserId === user.userId" @click="toggleStatus(user)">{{ user.status === 'active' ? '停用' : '启用' }}</button>
                  <button class="px-2 py-1 text-xs text-bronze-dark hover:bg-cream" @click="startReset(user)">重置密码</button>
                  <button class="px-2 py-1 text-xs text-red-700 hover:bg-red-50" @click="startDelete(user)">删除</button>
                </div>
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>
