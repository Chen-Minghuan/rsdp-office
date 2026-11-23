<script setup lang="ts">
import { authEnabled } from '~/composables/useAuth'
import { workspaceOwnerLabel } from '~/utils/workspaceType'

const auth = useAuth()
const { user } = auth
const workspace = useWorkspaceStore()

const displayName = computed(() => user.value?.displayName || user.value?.username || (authEnabled ? '' : '本地开发'))
const roleLabel = computed(() => {
  const account = user.value
  if (!account) return ''
  if (account.role === 'ADMIN') return '系统管理员'
  if (account.role === 'DEALER') return workspaceOwnerLabel(account.workspaceType)
  if (!workspace.initialized) return ''

  const project = workspace.currentProject
  if (project?.leader?.userId === account.userId) return '项目负责人'
  if (project?.collaborators.some(member => member.userId === account.userId)) return '项目协作者'
  return workspace.projects.length ? '普通账号' : '设计师'
})
const avatarChar = computed(() => (displayName.value.trim()[0] || '').toUpperCase())

async function logout() {
  await auth.logout()
  useWorkspaceStore().$reset()
  useOfficeStore().$reset()
  // 认证边界使用完整跳转，确保旧页面组件和异步任务不会留在新登录页。
  window.location.assign(useRouter().resolve('/login').href)
}
</script>

<template>
  <div class="flex items-center gap-2 border-t border-line px-1 py-3 text-sm text-[#615b55]" data-testid="user-session">
    <span v-if="avatarChar" class="grid h-8 w-8 flex-none place-items-center rounded-full bg-[#e8ddd0] text-xs font-semibold text-[#886a4a]" aria-hidden="true">{{ avatarChar }}</span>
    <div class="min-w-0 flex-1">
      <div v-if="displayName" class="truncate text-xs">{{ displayName }}</div>
      <div v-if="roleLabel" class="mt-0.5 text-[10px] text-muted" data-testid="user-session-role">{{ roleLabel }}</div>
    </div>
    <button v-if="authEnabled" type="button" class="flex-none rounded-lg px-2 py-1 text-xs text-muted hover:bg-cream hover:text-ink" @click="logout">退出登录</button>
  </div>
</template>
