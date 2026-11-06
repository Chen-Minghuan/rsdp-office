<script setup lang="ts">
import { authEnabled } from '~/composables/useAuth'

interface WorkspaceUserItem {
  userId: string
  username: string
  displayName: string
  role: string
  source: string | null
  status: string
  createdAt: string | null
}

const api = useOfficeApi()
const { me } = useAuth()
const authorized = ref(false)
const checking = ref(true)
const workspaceName = ref('')
const users = ref<WorkspaceUserItem[]>([])
const loading = ref(false)
const error = ref('')
const notice = ref('')

const newUsername = ref('')
const newDisplayName = ref('')
const newPassword = ref('')
const newSource = ref<'DEALER_STAFF' | 'FACTORY_SALES'>('DEALER_STAFF')
const creating = ref(false)

onMounted(async () => {
  checking.value = true
  try {
    if (!authEnabled) { authorized.value = true }
    else {
      const user = await me()
      authorized.value = user?.role === 'DEALER'
    }
    if (authorized.value) await loadAll()
  } finally {
    checking.value = false
  }
})

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const workspace = await api.getMyWorkspace()
    workspaceName.value = workspace.name
    users.value = await api.listWorkspaceUsers()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '工作区数据加载失败'
  } finally {
    loading.value = false
  }
}

async function createUser() {
  error.value = ''
  notice.value = ''
  creating.value = true
  try {
    await api.createWorkspaceUser({
      username: newUsername.value.trim(),
      displayName: newDisplayName.value.trim(),
      password: newPassword.value,
      source: newSource.value,
    })
    notice.value = `已开通账号 ${newUsername.value.trim()}，可被拉入本工作区项目`
    newUsername.value = ''
    newDisplayName.value = ''
    newPassword.value = ''
    newSource.value = 'DEALER_STAFF'
    await loadAll()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '开户失败'
  } finally {
    creating.value = false
  }
}

function sourceLabel(source: string | null) {
  if (source === 'FACTORY_SALES') return '厂家业务员'
  if (source === 'DEALER_STAFF') return '经销商员工'
  return '—'
}

function formatTime(value: string | null) {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}
</script>

<template>
  <div class="mx-auto w-full max-w-4xl p-6">
    <header class="mb-4">
      <h1 class="text-lg font-bold">工作区账号</h1>
      <p class="mt-1 text-xs text-muted">经销商主号在本工作区内开通普通账号（员工或厂家业务员）；项目负责人在项目内从本工作区账号池中增删协作者。停用与重置密码由系统管理员处理。</p>
    </header>

    <p v-if="checking" class="text-sm text-muted">正在核验经销商主号身份…</p>
    <p v-else-if="!authorized" class="border border-line bg-white px-4 py-6 text-sm text-muted">无权访问：只有经销商主号可以管理工作区账号。</p>

    <template v-else>
      <p v-if="workspaceName" class="mb-3 text-sm text-muted">当前工作区：<span class="font-semibold text-ink">{{ workspaceName }}</span></p>

      <form class="mb-5 border border-line bg-white p-4" @submit.prevent="createUser">
        <h2 class="mb-3 text-sm font-semibold">开通账号</h2>
        <div class="grid gap-2 md:grid-cols-[1fr_1fr_1fr_140px_auto]">
          <input v-model="newUsername" class="border border-line bg-white px-2 py-1.5 text-sm" placeholder="用户名（登录用）" aria-label="新账号用户名" maxlength="32">
          <input v-model="newDisplayName" class="border border-line bg-white px-2 py-1.5 text-sm" placeholder="显示名称" aria-label="新账号显示名称" maxlength="64">
          <input v-model="newPassword" type="password" class="border border-line bg-white px-2 py-1.5 text-sm" placeholder="初始密码（至少 6 位）" aria-label="新账号初始密码">
          <select v-model="newSource" class="border border-line bg-white px-2 py-1.5 text-sm" aria-label="新账号来源">
            <option value="DEALER_STAFF">经销商员工</option>
            <option value="FACTORY_SALES">厂家业务员</option>
          </select>
          <button class="bg-bronze px-3 py-1.5 text-sm text-white disabled:opacity-50" :disabled="creating">{{ creating ? '开通中…' : '开通' }}</button>
        </div>
      </form>

      <p v-if="error" role="alert" class="mb-2 text-xs text-red-700">{{ error }}</p>
      <p v-if="notice" class="mb-2 text-xs text-green">{{ notice }}</p>

      <div class="border border-line bg-white">
        <table class="w-full text-sm">
          <thead>
            <tr class="border-b border-line text-left text-xs text-muted">
              <th class="px-3 py-2 font-medium">用户名</th>
              <th class="px-3 py-2 font-medium">显示名称</th>
              <th class="px-3 py-2 font-medium">身份</th>
              <th class="px-3 py-2 font-medium">来源</th>
              <th class="px-3 py-2 font-medium">状态</th>
              <th class="px-3 py-2 font-medium">创建时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading"><td colspan="6" class="px-3 py-4 text-center text-xs text-muted">加载中…</td></tr>
            <tr v-else-if="!users.length"><td colspan="6" class="px-3 py-4 text-center text-xs text-muted">暂无账号</td></tr>
            <tr v-for="user in users" :key="user.userId" class="border-b border-line last:border-b-0">
              <td class="px-3 py-2 font-medium">{{ user.username }}</td>
              <td class="px-3 py-2">{{ user.displayName }}</td>
              <td class="px-3 py-2"><span class="text-xs" :class="user.role === 'DEALER' ? 'text-bronze-dark font-semibold' : 'text-muted'">{{ user.role === 'DEALER' ? '经销商主号' : '设计师' }}</span></td>
              <td class="px-3 py-2 text-xs text-muted">{{ sourceLabel(user.source) }}</td>
              <td class="px-3 py-2"><span class="text-xs" :class="user.status === 'active' ? 'text-green' : 'text-red-700'">{{ user.status === 'active' ? '在用' : '已停用' }}</span></td>
              <td class="px-3 py-2 text-xs text-muted">{{ formatTime(user.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>
