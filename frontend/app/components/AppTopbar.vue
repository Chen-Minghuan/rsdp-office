<script setup lang="ts">
import { authEnabled } from '~/composables/useAuth'
import { workspaceOwnerLabel } from '~/utils/workspaceType'

const { isProjectWorkflow } = useWorkspaceNavigation()
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
  <header class="flex min-h-[64px] flex-none items-center justify-between gap-3 border-b border-line bg-white/95 px-4 py-3 backdrop-blur md:px-7">
    <div class="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
      <span class="text-sm font-semibold text-ink">办公全案 · 工作区</span>
      <span class="hidden h-4 w-px bg-line sm:block" aria-hidden="true" />
      <span class="text-xs text-muted">{{ isProjectWorkflow ? '项目设计' : '商品资源' }}</span>
    </div>
    <div class="flex shrink-0 items-center gap-2 text-sm text-[#615b55]">
      <span v-if="avatarChar" class="grid h-8 w-8 place-items-center rounded-full bg-[#e8ddd0] text-xs font-semibold text-[#886a4a]" aria-hidden="true">{{ avatarChar }}</span>
      <span v-if="displayName" class="hidden sm:inline">{{ displayName }}</span>
      <span v-if="roleLabel" class="hidden border border-line px-1.5 py-0.5 text-[10px] text-muted sm:inline" data-testid="topbar-role">{{ roleLabel }}</span>
      <button v-if="authEnabled" type="button" class="rounded-lg px-2 py-1 text-xs text-muted hover:bg-cream hover:text-ink" @click="logout">退出登录</button>
    </div>
  </header>
</template>
