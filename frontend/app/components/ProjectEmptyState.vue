<script setup lang="ts">
import { authEnabled } from '~/composables/useAuth'

const workspace = useWorkspaceStore()
const auth = useAuth()
const { isProjectWorkflow } = useWorkspaceNavigation()
const { canCreateProject, user } = auth
const showCreate = ref(false)
const retrying = ref(false)
const loading = computed(() => retrying.value || workspace.loading || (!workspace.initialized && !workspace.error))
const isAdmin = computed(() => authEnabled && auth.user.value?.role === 'ADMIN')

async function retry() {
  retrying.value = true
  try { await workspace.refresh(isProjectWorkflow.value) } finally { retrying.value = false }
}
</script>

<template>
  <section class="grid flex-1 place-items-center bg-bg p-6" data-testid="project-empty-state">
    <div class="w-full max-w-md border border-line bg-white p-8 text-center">
      <template v-if="loading">
        <AppIcon name="folder" size="8" class="mx-auto mb-4 text-muted" />
        <h1 class="text-lg font-semibold">正在加载项目</h1>
        <p class="mt-2 text-sm text-muted">正在读取当前账号可访问的项目与楼层…</p>
      </template>
      <template v-else-if="workspace.error">
        <AppIcon name="warning" size="8" class="mx-auto mb-4 text-bronze" />
        <h1 class="text-lg font-semibold">项目列表暂时不可用</h1>
        <p class="mt-2 text-sm text-red-700" role="alert">{{ workspace.error }}</p>
        <button type="button" class="mt-5 border border-line bg-white px-5 py-2 text-sm font-semibold hover:bg-cream" @click="retry">重新加载</button>
      </template>
      <template v-else>
        <AppIcon name="folder" size="8" class="mx-auto mb-4 text-muted" />
        <h1 class="text-lg font-semibold">暂无可访问项目</h1>
        <p v-if="isAdmin" class="mt-2 text-sm leading-6 text-muted">系统管理员用于管理账号和工作区。请使用工作区主号或成员账号进入项目设计。</p>
        <p v-else-if="canCreateProject" class="mt-2 text-sm leading-6 text-muted">创建项目并分配成员后，即可按楼层上传图纸并开始空间设计。</p>
        <p v-else class="mt-2 text-sm leading-6 text-muted">{{ user?.workspaceType === 'FACTORY' ? '请联系工厂工作区主号创建项目并分配给你。' : '请联系经销商创建项目并分配给你。' }}</p>
        <button v-if="canCreateProject" type="button" class="mt-5 bg-bronze px-5 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark" @click="showCreate = true">新建项目</button>
      </template>
    </div>
    <ProjectCreateDialog v-if="showCreate && canCreateProject" @close="showCreate = false" />
  </section>
</template>
