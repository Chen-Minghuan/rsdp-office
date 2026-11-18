<script setup lang="ts">
import { authEnabled } from '~/composables/useAuth'

const router = useRouter()
const { lastProjectRoute } = useWorkspaceNavigation()
const productLinks = [
  { icon: 'cube', label: '商品库', to: '/products' },
  { icon: 'documentText', label: '导入记录', to: '/products/import-records' },
]

// 两个工作空间入口共享同一活动键，避免路由切换期间出现双高亮。
const activeWorkspaceTab = computed<'project-bench' | 'space-design' | null>(() => {
  const path = router.currentRoute.value.path
  if (path === '/workspace/project-bench') return 'project-bench'
  return ['/', '/canvas', '/proposal', '/agent'].includes(path) ? 'space-design' : null
})

// 用户管理入口只对系统管理员可见，工作区账号入口只对经销商主号可见；服务端仍逐接口校验
const isAdmin = ref(false)
const isDealer = ref(false)
onMounted(async () => {
  if (!authEnabled) { isAdmin.value = true; isDealer.value = true; return }
  const user = await useAuth().me()
  isAdmin.value = user?.role === 'ADMIN'
  isDealer.value = user?.role === 'DEALER'
})

function isActive(to: string) {
  const path = router.currentRoute.value.path
  if (to === '/products') return path === '/products' || path === '/products/import'
  return path === to
}
</script>

<template>
  <aside data-testid="app-sidebar" class="app-sidebar-scroll max-h-[45dvh] flex-none overflow-y-auto border-b border-line bg-white p-3 md:max-h-none md:w-60 md:border-b-0 md:border-r md:flex md:flex-col md:p-3">
    <div class="hidden items-center gap-2 mb-6 px-2 md:flex">
      <span class="text-2xl font-extrabold tracking-tight text-ink">RSDP</span>
      <span class="text-[10px] text-muted leading-tight">办公空间<br>智能设计平台</span>
    </div>
    <ProjectFloorTree class="mb-3" />
    <nav class="grid grid-cols-2 gap-2 md:grid-cols-1 md:gap-3" aria-label="工作区导航">
      <div>
        <div class="mb-1 px-2 text-[11px] font-medium tracking-wider text-muted">工作空间</div>
        <NuxtLink
          to="/workspace/project-bench"
          :aria-current="activeWorkspaceTab === 'project-bench' ? 'page' : undefined"
          :class="[
            'flex w-full items-center gap-2 rounded-none px-2.5 py-2 text-sm transition',
            activeWorkspaceTab === 'project-bench' ? 'bg-cream text-bronze-dark font-semibold' : 'text-[#5f5b56] hover:bg-[#f8f3ef]',
          ]"
        >
          <AppIcon name="zone" />
          <span>项目工作台</span>
        </NuxtLink>
        <NuxtLink
          :to="lastProjectRoute"
          :aria-current="activeWorkspaceTab === 'space-design' ? 'page' : undefined"
          class="flex w-full items-center gap-2 rounded-none px-2.5 py-2 text-sm transition"
          :class="activeWorkspaceTab === 'space-design' ? 'bg-cream text-bronze-dark font-semibold' : 'text-[#5f5b56] hover:bg-[#f8f3ef]'"
        >
          <AppIcon name="map" />
          <span>空间设计</span>
        </NuxtLink>
      </div>
      <div class="grid content-start gap-1">
        <div class="mb-1 px-2 text-[11px] font-medium tracking-wider text-muted">商品资源</div>
      <NuxtLink
        v-for="link in productLinks"
        :key="link.to"
        :to="link.to"
        :aria-current="isActive(link.to) ? 'page' : undefined"
        :class="[
          'flex w-full items-center gap-2 rounded-none px-2.5 py-2 text-sm transition',
          isActive(link.to) ? 'bg-cream text-bronze-dark font-semibold' : 'text-[#5f5b56] hover:bg-[#f8f3ef]',
        ]"
      >
        <AppIcon :name="link.icon" />
        <span>{{ link.label }}</span>
      </NuxtLink>
      </div>
      <div v-if="isDealer" class="grid content-start gap-1">
        <div class="mb-1 px-2 text-[11px] font-medium tracking-wider text-muted">经销商工作区</div>

        <NuxtLink
          to="/workspace/users"
          :aria-current="isActive('/workspace/users') ? 'page' : undefined"
          :class="[
            'flex w-full items-center gap-2 rounded-none px-2.5 py-2 text-sm transition',
            isActive('/workspace/users') ? 'bg-cream text-bronze-dark font-semibold' : 'text-[#5f5b56] hover:bg-[#f8f3ef]',
          ]"
        >
          <AppIcon name="user" />
          <span>工作区账号</span>
        </NuxtLink>
      </div>
      <div v-if="isAdmin" class="grid content-start gap-1">
        <div class="mb-1 px-2 text-[11px] font-medium tracking-wider text-muted">系统管理</div>
        <NuxtLink
          to="/users"
          :aria-current="isActive('/users') ? 'page' : undefined"
          :class="[
            'flex w-full items-center gap-2 rounded-none px-2.5 py-2 text-sm transition',
            isActive('/users') ? 'bg-cream text-bronze-dark font-semibold' : 'text-[#5f5b56] hover:bg-[#f8f3ef]',
          ]"
        >
          <AppIcon name="user" />
          <span>用户管理</span>
        </NuxtLink>
      </div>
    </nav>
    <div class="hidden mt-auto pt-4 border-t border-line md:block">
      <div class="text-xs text-muted px-3 flex items-center gap-1.5">
        <span class="w-1.5 h-1.5 rounded-full bg-green"></span>
        办公空间智能设计平台
      </div>
    </div>
  </aside>
</template>

<style scoped>
.app-sidebar-scroll {
  -ms-overflow-style: none;
  scrollbar-width: none;
}

.app-sidebar-scroll::-webkit-scrollbar {
  display: none;
}
</style>
