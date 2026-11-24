<script setup lang="ts">
import { authEnabled } from '~/composables/useAuth'

const router = useRouter()
const auth = useAuth()
const mobileOpen = ref(false)
const sidebarContentId = useId()
watch(router.currentRoute, () => { mobileOpen.value = false })

const defaultSidebarWidth = 240
const minSidebarWidth = 192
const sidebarWidth = ref(defaultSidebarWidth)
const isSidebarResizing = ref(false)
let sidebarResizeStartX = 0
let sidebarResizeStartWidth = defaultSidebarWidth
let activeSidebarResizePointerId: number | null = null

const isAdmin = computed(() => !authEnabled || auth.user.value?.role === 'ADMIN')

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
    class="app-sidebar relative flex-none border-b border-line bg-white md:border-b-0 md:border-r"
    :style="{ '--sidebar-width': `${sidebarWidth}px` }"
  >
    <button type="button" class="px-4 py-2 text-xs font-semibold text-bronze-dark md:hidden" :aria-controls="sidebarContentId" :aria-expanded="mobileOpen" @click="mobileOpen = !mobileOpen">
      项目侧栏
    </button>
    <div :id="sidebarContentId" class="sidebar-content max-h-[45dvh] flex-col overflow-y-auto p-3 md:flex md:h-full md:max-h-none" :class="mobileOpen ? 'flex' : 'hidden'">
      <ProjectFloorTree class="mb-3" />
      <nav class="grid gap-1" aria-label="工作区导航">
        <NuxtLink to="/products/import-records" class="flex items-center gap-2 px-2.5 py-2 text-sm text-neutral-700 hover:bg-neutral-100" exact-active-class="font-semibold">
          <AppIcon name="documentText" />
          导入记录
        </NuxtLink>
        <NuxtLink v-if="isAdmin" to="/users" class="flex items-center gap-2 px-2.5 py-2 text-sm text-neutral-700 hover:bg-neutral-100" exact-active-class="font-semibold">
          <AppIcon name="user" />
          用户管理
        </NuxtLink>
      </nav>
      <div class="mt-auto border-t border-line pt-2">
        <AppUserSession />
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
.sidebar-content { scrollbar-width: none; }

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
  .app-sidebar {
    width: var(--sidebar-width, 240px);
  }
}
</style>
