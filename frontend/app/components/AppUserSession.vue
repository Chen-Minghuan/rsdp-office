<script setup lang="ts">
import { authEnabled } from '~/composables/useAuth'
import { workspaceOwnerLabel } from '~/utils/workspaceType'
import { projectPermissionsFor } from '~/utils/projectPermissions'

const auth = useAuth()
const { user } = auth
const workspace = useWorkspaceStore()
const api = useOfficeApi()
const route = useRoute()

const root = ref<HTMLElement | null>(null)
const trigger = ref<HTMLButtonElement | null>(null)
const open = ref(false)
const panelStyle = ref<Record<string, string>>({})
const hasRecentQuote = ref(false)
const accountBadge = ref<number | null>(null)
const favoriteCount = ref<number | null>(null)
const badgesLoading = ref(false)
const badgesLoaded = ref(false)
let closeTimer: ReturnType<typeof setTimeout> | null = null
let badgeGeneration = 0
let favoriteGeneration = 0
let favoriteCountLoaded = false
let favoriteLoading = false
let sidebarObserver: ResizeObserver | null = null

const showDealerEntries = computed(() => !authEnabled || user.value?.role === 'DEALER')
const showQuoteEntry = computed(() => showDealerEntries.value || workspace.projects.some(project =>
  projectPermissionsFor(project, user.value?.userId).includes('QUOTE_READ')))
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
  return ''
})
const avatarChar = computed(() => (displayName.value.trim()[0] || '').toUpperCase())
const quoteProject = computed(() => workspace.currentProject || workspace.projects[0] || null)

function clearCloseTimer() {
  if (closeTimer) clearTimeout(closeTimer)
  closeTimer = null
}

function updatePanelPosition() {
  const triggerElement = trigger.value
  if (!triggerElement) return
  const aside = root.value?.closest('aside')?.getBoundingClientRect()
  const triggerRect = triggerElement.getBoundingClientRect()
  const width = Math.min(192, window.innerWidth - 24)
  const anchorLeft = aside?.left ?? triggerRect.left
  const left = Math.max(12, Math.min(anchorLeft + 16, window.innerWidth - width - 12))
  panelStyle.value = {
    width: `${width}px`,
    left: `${left}px`,
    bottom: `${window.innerHeight - triggerRect.top + 8}px`,
  }
}

function openPanel() {
  clearCloseTimer()
  if (open.value) return
  updatePanelPosition()
  open.value = true
  void loadMenuBadges()
}

function closePanel(restoreFocus = false) {
  clearCloseTimer()
  if (!open.value) return
  open.value = false
  if (restoreFocus) nextTick(() => trigger.value?.focus())
}

function scheduleClose() {
  clearCloseTimer()
  closeTimer = setTimeout(() => {
    closeTimer = null
    closePanel()
  }, 160)
}

function togglePanel() {
  if (open.value) closePanel()
  else openPanel()
}

function handlePointerEnter(event: PointerEvent) {
  if (event.pointerType === 'mouse') openPanel()
}

function handlePointerLeave(event: PointerEvent) {
  if (event.pointerType !== 'mouse' || !open.value) return
  const next = event.relatedTarget as Node | null
  if (next && root.value?.contains(next)) return
  scheduleClose()
}

function handleFocusOut(event: FocusEvent) {
  if (!open.value) return
  const next = event.relatedTarget as Node | null
  if (next && root.value?.contains(next)) return
  scheduleClose()
}

function handleDocumentPointerDown(event: PointerEvent) {
  if (open.value && !root.value?.contains(event.target as Node)) closePanel()
}

function handleDocumentKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !open.value) return
  event.preventDefault()
  closePanel(true)
}

async function loadMenuBadges(force = false) {
  void loadFavoriteBadge(force)
  if (showDealerEntries.value) void loadDealerBadges(force)
}

async function loadFavoriteBadge(force = false) {
  if (favoriteLoading || (favoriteCountLoaded && !force)) return
  favoriteLoading = true
  const generation = ++favoriteGeneration
  const result = await api.loadFavoriteProductIds().catch(() => null)
  favoriteLoading = false
  if (generation !== favoriteGeneration || result === null) return
  favoriteCount.value = result.length
  favoriteCountLoaded = true
}

async function loadDealerBadges(force = false) {
  if (badgesLoading.value || (badgesLoaded.value && !force)) return
  const generation = ++badgeGeneration
  badgesLoading.value = true
  const projectId = quoteProject.value?.projectId || ''
  const [quoteResult, membersResult] = await Promise.allSettled([
    projectId ? api.latestProjectQuotation(projectId) : Promise.resolve(null),
    api.listWorkspaceUsers(),
  ])
  if (generation !== badgeGeneration) return

  hasRecentQuote.value = quoteResult.status === 'fulfilled' && Boolean(quoteResult.value)
  accountBadge.value = membersResult.status === 'fulfilled' && membersResult.value.length
    ? membersResult.value.filter(member => member.status === 'active').length
    : null
  badgesLoaded.value = quoteResult.status === 'fulfilled' && membersResult.status === 'fulfilled'
  badgesLoading.value = false
}

async function logout() {
  await auth.logout()
  useWorkspaceStore().$reset()
  useOfficeStore().$reset()
  // 认证边界使用完整跳转，确保旧页面组件和异步任务不会留在新登录页。
  window.location.assign(useRouter().resolve('/login').href)
}

watch(open, opened => {
  if (opened) {
    nextTick(updatePanelPosition)
    window.addEventListener('resize', updatePanelPosition)
    window.addEventListener('scroll', updatePanelPosition, true)
    document.addEventListener('pointerdown', handleDocumentPointerDown)
    document.addEventListener('keydown', handleDocumentKeydown)
    const aside = root.value?.closest('aside')
    if (aside && typeof ResizeObserver !== 'undefined') {
      sidebarObserver = new ResizeObserver(updatePanelPosition)
      sidebarObserver.observe(aside)
    }
  } else {
    window.removeEventListener('resize', updatePanelPosition)
    window.removeEventListener('scroll', updatePanelPosition, true)
    document.removeEventListener('pointerdown', handleDocumentPointerDown)
    document.removeEventListener('keydown', handleDocumentKeydown)
    sidebarObserver?.disconnect()
    sidebarObserver = null
  }
})

watch(() => quoteProject.value?.projectId, () => {
  badgeGeneration++
  badgesLoading.value = false
  badgesLoaded.value = false
  hasRecentQuote.value = false
  accountBadge.value = null
  if (open.value) void loadMenuBadges(true)
})

watch(() => route.fullPath, () => {
  closePanel()
  badgeGeneration++
  badgesLoading.value = false
  badgesLoaded.value = false
  hasRecentQuote.value = false
  accountBadge.value = null
  favoriteGeneration++
  favoriteCountLoaded = false
  favoriteLoading = false
  favoriteCount.value = null
})

// 同页收藏保存/删除收藏夹后，侧栏收藏数立即失效并重取
const favoriteCountVersion = useState('favorite-count-version', () => 0)
watch(favoriteCountVersion, () => {
  favoriteGeneration++
  favoriteCountLoaded = false
  favoriteLoading = false
  favoriteCount.value = null
  if (open.value) void loadFavoriteBadge(true)
})

onBeforeUnmount(() => {
  clearCloseTimer()
  badgeGeneration++
  favoriteGeneration++
  window.removeEventListener('resize', updatePanelPosition)
  window.removeEventListener('scroll', updatePanelPosition, true)
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
  document.removeEventListener('keydown', handleDocumentKeydown)
  sidebarObserver?.disconnect()
})
</script>

<template>
  <div
    ref="root"
    class="relative flex items-center gap-2 border-t border-line px-1 py-3 text-sm text-muted"
    data-testid="user-session"
    @pointerenter="handlePointerEnter"
    @pointerleave="handlePointerLeave"
    @focusout="handleFocusOut"
  >
    <button
      ref="trigger"
      type="button"
      class="flex min-w-0 flex-1 items-center gap-2 rounded-lg px-1 py-1 text-left transition hover:bg-cream focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-bronze"
      :class="{ 'bg-cream': open }"
      :aria-expanded="open"
      aria-controls="workspace-account-panel"
      data-testid="workspace-center-trigger"
      @click="togglePanel"
    >
      <span v-if="avatarChar" class="grid h-8 w-8 flex-none place-items-center rounded-full bg-accent-tint text-xs font-semibold text-accent-ink" aria-hidden="true">{{ avatarChar }}</span>
      <span class="min-w-0 flex-1">
        <span class="block truncate text-xs">{{ displayName }}</span>
        <span v-if="roleLabel" class="mt-0.5 block text-[10px] text-muted" data-testid="user-session-role">{{ roleLabel }}</span>
      </span>
      <AppIcon name="chevronDown" size="3.5" class="text-muted transition" :class="{ 'rotate-180': open }" />
    </button>
    <button v-if="authEnabled" type="button" class="flex-none rounded-lg px-2 py-1 text-xs text-muted hover:bg-cream hover:text-ink" @click="logout">退出登录</button>

    <Transition name="workspace-center-panel">
      <div
        v-if="open"
        id="workspace-account-panel"
        class="fixed z-[80] max-h-[calc(100dvh-24px)] overflow-y-auto rounded-xl border border-line bg-white text-ink shadow-panel"
        :style="panelStyle"
        role="region"
        aria-label="工作区中心"
        data-testid="workspace-center-panel"
        @mouseenter="clearCloseTimer"
        @pointerleave="handlePointerLeave"
      >
        <aside class="bg-bg p-2.5">
          <div class="px-2.5 pb-2 pt-1 text-[10px] tracking-[0.12em] text-muted">工作区中心</div>
          <NuxtLink
            to="/favorites"
            class="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-xs transition"
            :class="route.path === '/favorites' ? 'bg-accent-tint font-semibold text-bronze-dark' : 'text-muted hover:bg-accent-tint'"
            @click="closePanel()"
          >
            <AppIcon name="heartSolid" size="4" />
            <span>收藏</span>
            <span v-if="favoriteCount" class="ml-auto rounded-full bg-white/70 px-1.5 py-0.5 text-[9px] font-medium text-bronze-dark">{{ favoriteCount }}</span>
          </NuxtLink>
          <NuxtLink
            v-if="showQuoteEntry"
            to="/workspace/quotes"
            class="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-xs transition"
            :class="route.path === '/workspace/quotes' ? 'bg-accent-tint font-semibold text-bronze-dark' : 'text-muted hover:bg-accent-tint'"
            @click="closePanel()"
          >
            <AppIcon name="documentText" size="4" />
            <span>商品报价</span>
            <span v-if="hasRecentQuote" class="ml-auto rounded-full bg-white/70 px-1.5 py-0.5 text-[9px] font-medium text-bronze-dark">最近</span>
          </NuxtLink>
          <NuxtLink
            v-if="showDealerEntries"
            to="/workspace/users"
            class="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-xs transition"
            :class="route.path === '/workspace/users' ? 'bg-accent-tint font-semibold text-bronze-dark' : 'text-muted hover:bg-accent-tint'"
            @click="closePanel()"
          >
            <AppIcon name="user" size="4" />
            <span>工作区账号</span>
            <span v-if="accountBadge !== null" class="ml-auto rounded-full bg-white/70 px-1.5 py-0.5 text-[9px] font-medium text-bronze-dark">{{ accountBadge }}</span>
          </NuxtLink>
        </aside>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.workspace-center-panel-enter-active,
.workspace-center-panel-leave-active {
  transition: opacity 120ms ease, transform 120ms ease;
}

.workspace-center-panel-enter-from,
.workspace-center-panel-leave-to {
  opacity: 0;
  transform: translateY(6px) scale(0.99);
}

@media (prefers-reduced-motion: reduce) {
  .workspace-center-panel-enter-active,
  .workspace-center-panel-leave-active {
    transition: none;
  }
}
</style>
