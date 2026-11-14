<script setup lang="ts">
import { authEnabled, type AuthUser } from '~/composables/useAuth'

const { isProjectWorkflow } = useWorkspaceNavigation()
const auth = useAuth()
const route = useRoute()
const user = ref<AuthUser | null>(null)

// 顶栏在非登录页常驻；登录后按当前会话刷新用户与角色显示
async function refreshUser() {
  user.value = authEnabled ? await auth.me() : null
}
onMounted(refreshUser)
watch(() => route.path, refreshUser)

const displayName = computed(() => user.value?.displayName || user.value?.username || '设计师')
const roleLabel = computed(() => {
  if (!authEnabled) return '设计师'
  if (!user.value) return ''
  if (user.value.role === 'ADMIN') return '系统管理员'
  if (user.value.role === 'DEALER') return '经销商主号'
  return '设计师'
})
const avatarChar = computed(() => (displayName.value.trim()[0] || '设').toUpperCase())

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
      <span class="grid h-8 w-8 place-items-center rounded-full bg-[#e8ddd0] text-xs font-semibold text-[#886a4a]" aria-hidden="true">{{ avatarChar }}</span>
      <span class="hidden sm:inline">{{ displayName }}</span>
      <span v-if="roleLabel" class="hidden border border-line px-1.5 py-0.5 text-[10px] text-muted sm:inline" data-testid="topbar-role">{{ roleLabel }}</span>
      <button v-if="authEnabled" type="button" class="rounded-lg px-2 py-1 text-xs text-muted hover:bg-cream hover:text-ink" @click="logout">退出登录</button>
    </div>
  </header>
</template>
