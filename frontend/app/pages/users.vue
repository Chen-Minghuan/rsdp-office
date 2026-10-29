<script setup lang="ts">
import { authEnabled } from '~/composables/useAuth'

interface AdminUserItem {
  userId: string
  username: string
  displayName: string
  role: string
  status: string
  lastLoginAt: string | null
  createdAt: string | null
}

const api = useOfficeApi()
const { me } = useAuth()
const authorized = ref(false)
const checking = ref(true)
const users = ref<AdminUserItem[]>([])
const loading = ref(false)
const error = ref('')
const notice = ref('')

const newUsername = ref('')
const newDisplayName = ref('')
const newPassword = ref('')
const newRole = ref<'USER' | 'ADMIN'>('USER')
const creating = ref(false)

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
    if (authorized.value) await loadUsers()
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

async function createUser() {
  error.value = ''
  notice.value = ''
  creating.value = true
  try {
    await api.createAdminUser({
      username: newUsername.value.trim(),
      displayName: newDisplayName.value.trim(),
      password: newPassword.value,
      role: newRole.value,
    })
    notice.value = `已创建用户 ${newUsername.value.trim()}`
    newUsername.value = ''
    newDisplayName.value = ''
    newPassword.value = ''
    newRole.value = 'USER'
    await loadUsers()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '创建用户失败'
  } finally {
    creating.value = false
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
</script>

<template>
  <div class="mx-auto w-full max-w-4xl p-6">
    <header class="mb-4">
      <h1 class="text-lg font-bold">用户管理</h1>
      <p class="mt-1 text-xs text-muted">系统管理员创建账号、停用/启用与重置密码；项目负责人在项目内从已有账号中指定。</p>
    </header>

    <p v-if="checking" class="text-sm text-muted">正在核验管理员身份…</p>
    <p v-else-if="!authorized" class="border border-line bg-white px-4 py-6 text-sm text-muted">无权访问：只有系统管理员可以管理用户。</p>

    <template v-else>
      <form class="mb-5 border border-line bg-white p-4" @submit.prevent="createUser">
        <h2 class="mb-3 text-sm font-semibold">创建用户</h2>
        <div class="grid gap-2 md:grid-cols-[1fr_1fr_1fr_120px_auto]">
          <input v-model="newUsername" class="border border-line bg-white px-2 py-1.5 text-sm" placeholder="用户名（登录用）" aria-label="新用户名" maxlength="32">
          <input v-model="newDisplayName" class="border border-line bg-white px-2 py-1.5 text-sm" placeholder="显示名称" aria-label="新用户显示名称" maxlength="64">
          <input v-model="newPassword" type="password" class="border border-line bg-white px-2 py-1.5 text-sm" placeholder="初始密码（至少 6 位）" aria-label="新用户初始密码">
          <select v-model="newRole" class="border border-line bg-white px-2 py-1.5 text-sm" aria-label="新用户角色">
            <option value="USER">协作者</option>
            <option value="ADMIN">系统管理员</option>
          </select>
          <button class="bg-bronze px-3 py-1.5 text-sm text-white disabled:opacity-50" :disabled="creating">{{ creating ? '创建中…' : '创建' }}</button>
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
              <th class="px-3 py-2 font-medium">角色</th>
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
              <td class="px-3 py-2"><span class="text-xs" :class="user.role === 'ADMIN' ? 'text-bronze-dark font-semibold' : 'text-muted'">{{ user.role === 'ADMIN' ? '系统管理员' : '协作者' }}</span></td>
              <td class="px-3 py-2"><span class="text-xs" :class="user.status === 'active' ? 'text-green' : 'text-red-700'">{{ user.status === 'active' ? '在用' : '已停用' }}</span></td>
              <td class="px-3 py-2 text-xs text-muted">{{ formatTime(user.lastLoginAt) }}</td>
              <td class="px-3 py-2">
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
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>
