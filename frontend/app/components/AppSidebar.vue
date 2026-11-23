<script setup lang="ts">
import { authEnabled } from '~/composables/useAuth'
import { workspaceTypeLabel } from '~/utils/workspaceType'
import { projectBenchPath } from '~/utils/projectBenchRoutes'

const router = useRouter()
const auth = useAuth()
const { isProjectBench, isDesignWorkflow, lastProjectRoute } = useWorkspaceNavigation()
const productLinks = [
  { icon: 'cube', label: '商品库', to: '/products' },
  { icon: 'documentText', label: '导入记录', to: '/products/import-records' },
]

const defaultSidebarWidth = 240
const minSidebarWidth = 192
const sidebarWidth = ref(defaultSidebarWidth)
const isSidebarResizing = ref(false)
let sidebarResizeStartX = 0
let sidebarResizeStartWidth = defaultSidebarWidth
let activeSidebarResizePointerId: number | null = null

// 用户管理入口只对系统管理员可见，工作区账号入口只对经销商主号可见；服务端仍逐接口校验
const isAdmin = ref(false)
const isDealer = ref(false)
onMounted(async () => {
  if (!authEnabled) { isAdmin.value = true; isDealer.value = true; return }
  const user = await auth.me()
  isAdmin.value = user?.role === 'ADMIN'
  isDealer.value = user?.role === 'DEALER'
})

function isActive(to: string) {
  const path = router.currentRoute.value.path
  if (to === '/products') return path === '/products' || path === '/products/import'
  return path === to
}

function maxSidebarWidth() {
  return Math.max(minSidebarWidth, Math.floor(window.innerWidth / 2))
}

function clampSidebarWidth(width: number) {
  return Math.min(maxSidebarWidth(), Math.max(minSidebarWidth, width))
}

function stopSidebarResize() {
  isSidebarResizing.value = false
  activeSidebarResizePointerId = null
  document.body.style.userSelect = ''
  window.removeEventListener('pointermove', resizeSidebar)
  window.removeEventListener('pointerup', endSidebarResize)
  window.removeEventListener('pointercancel', endSidebarResize)
}

function startSidebarResize(event: PointerEvent) {
  if (event.button !== 0) return
  const handle = event.currentTarget as HTMLElement
  activeSidebarResizePointerId = event.pointerId
  sidebarResizeStartX = event.clientX
  sidebarResizeStartWidth = sidebarWidth.value
  isSidebarResizing.value = true
  document.body.style.userSelect = 'none'
  handle.setPointerCapture(event.pointerId)
  window.addEventListener('pointermove', resizeSidebar)
  window.addEventListener('pointerup', endSidebarResize)
  window.addEventListener('pointercancel', endSidebarResize)
  event.preventDefault()
}

function resizeSidebar(event: PointerEvent) {
  if (!isSidebarResizing.value || event.pointerId !== activeSidebarResizePointerId) return
  sidebarWidth.value = clampSidebarWidth(sidebarResizeStartWidth + event.clientX - sidebarResizeStartX)
}

function endSidebarResize(event: PointerEvent) {
  if (event.pointerId !== activeSidebarResizePointerId) return
  stopSidebarResize()
}

function constrainSidebarWidthToViewport() {
  sidebarWidth.value = clampSidebarWidth(sidebarWidth.value)
}

onMounted(() => window.addEventListener('resize', constrainSidebarWidthToViewport))
onBeforeUnmount(() => {
  stopSidebarResize()
  window.removeEventListener('resize', constrainSidebarWidthToViewport)
})
</script>

<template>
  <aside
    data-testid="app-sidebar"
    class="app-sidebar-scroll relative max-h-[45dvh] flex-none overflow-y-auto border-b border-line bg-white p-3 md:max-h-none md:border-b-0 md:border-r md:flex md:flex-col md:p-3"
    :style="{ '--sidebar-width': `${sidebarWidth}px` }"
  >
    <div class="hidden items-center gap-2 mb-6 px-2 md:flex">
      <span class="text-2xl font-extrabold tracking-tight text-ink">RSDP</span>
      <span class="text-[10px] text-muted leading-tight">办公空间<br>智能设计平台</span>
    </div>
    <ProjectFloorTree class="mb-3" />
    <nav class="grid grid-cols-2 gap-2 md:grid-cols-1 md:gap-3" aria-label="工作区导航">
      <div>
        <div class="mb-1 px-2 text-[11px] font-medium tracking-wider text-muted">工作空间</div>
        <NuxtLink
          :to="projectBenchPath"
          :aria-current="isProjectBench ? 'page' : undefined"
          :class="[
            'flex w-full items-center gap-2 rounded-none px-2.5 py-2 text-sm transition',
            isProjectBench ? 'bg-cream text-bronze-dark font-semibold' : 'text-[#5f5b56] hover:bg-[#f8f3ef]',
          ]"
        >
          <AppIcon name="zone" />
          <span>项目工作台</span>
        </NuxtLink>
        <NuxtLink
          :to="lastProjectRoute"
          :aria-current="isDesignWorkflow ? 'page' : undefined"
          class="flex w-full items-center gap-2 rounded-none px-2.5 py-2 text-sm transition"
          :class="isDesignWorkflow ? 'bg-cream text-bronze-dark font-semibold' : 'text-[#5f5b56] hover:bg-[#f8f3ef]'"
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
        <div class="mb-1 px-2 text-[11px] font-medium tracking-wider text-muted">{{ workspaceTypeLabel(auth.user.value?.workspaceType) }}</div>

        <NuxtLink
          to="/workspace/quotes"
          :aria-current="isActive('/workspace/quotes') ? 'page' : undefined"
          :class="[
            'flex w-full items-center gap-2 rounded-none px-2.5 py-2 text-sm transition',
            isActive('/workspace/quotes') ? 'bg-cream text-bronze-dark font-semibold' : 'text-[#5f5b56] hover:bg-[#f8f3ef]',
          ]"
        >
          <AppIcon name="documentText" />
          <span>商品报价</span>
        </NuxtLink>
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
    <div class="mt-auto">
      <AppUserSession />
      <div class="hidden pt-4 border-t border-line md:block">
        <div class="text-xs text-muted px-3 flex items-center gap-1.5">
          <span class="w-1.5 h-1.5 rounded-full bg-green"></span>
          办公空间智能设计平台
        </div>
      </div>
    </div>
    <div
      data-testid="sidebar-resize-handle"
      role="separator"
      aria-label="调整侧边栏宽度"
      aria-orientation="vertical"
      class="sidebar-resize-handle hidden md:block"
      :class="{ 'is-resizing': isSidebarResizing }"
      @pointerdown="startSidebarResize"
      @lostpointercapture="endSidebarResize"
    ></div>
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

.sidebar-resize-handle {
  position: absolute;
  z-index: 20;
  top: 0;
  right: 0;
  bottom: 0;
  width: 10px;
  cursor: ew-resize;
  touch-action: none;
}

.sidebar-resize-handle::before {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 50%;
  width: 1px;
  content: '';
  background: transparent;
  transform: translateX(-50%);
  transition: background-color 150ms ease;
}

.sidebar-resize-handle:hover::before,
.sidebar-resize-handle.is-resizing::before {
  background: #a77b51;
}

@media (min-width: 768px) {
  .app-sidebar-scroll {
    width: var(--sidebar-width, 240px);
  }
}
</style>
