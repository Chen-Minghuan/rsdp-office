<script setup lang="ts">
import { projectBenchPath, projectOverviewRoute } from '~/utils/projectBenchRoutes'

import { projectStatusOptions, type ProjectStatusFilter } from '~/utils/projectStatus'

const workspace = useWorkspaceStore()
const { canCreateProject } = useAuth()
const { isProjectDrawing, isProjectOverview } = useWorkspaceNavigation()
const showCreate = ref(false)
const activeStatus = ref<ProjectStatusFilter>('ALL')
// 列表视图每次进入时重取项目列表：方案确认/失效、新增楼层后阶段与进度由后端重新派生。
onMounted(() => {
  if (!isProjectDrawing.value && !isProjectOverview.value) void workspace.refreshProjects()
})
// 阶段卡片与 projectStatusOptions 一一对应（设计中/交付中/已完成），均可点击筛选
const statusCards = computed(() => [
  { value: 'ALL' as const, label: '全部项目', count: workspace.projects.length as number | null },
  ...projectStatusOptions.map(option => ({ value: option.value as ProjectStatusFilter, label: option.label, count: workspace.projects.filter(project => project.status === option.value).length as number | null })),
])
</script>

<template>
  <div class="flex h-full min-h-0 flex-col gap-4 p-4 lg:p-5">
    <header class="flex flex-none flex-wrap items-center justify-between gap-3">
      <h1 class="text-[25px] font-bold tracking-tight">项目工作台</h1>
      <div class="flex flex-wrap items-center gap-2">
        <NuxtLink v-if="isProjectDrawing && workspace.currentFloorId" :to="projectOverviewRoute(workspace.currentFloorId)" class="border border-accent-line px-3 py-2 text-sm font-semibold text-accent-ink hover:bg-accent-tint">项目总览</NuxtLink>
        <NuxtLink v-if="isProjectDrawing || isProjectOverview" :to="projectBenchPath" class="border border-accent-line px-3 py-2 text-sm font-semibold text-accent-ink hover:bg-accent-tint">返回项目列表</NuxtLink>
        <button v-if="canCreateProject && workspace.projects.length" type="button" class="border border-accent bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-ink" @click="showCreate = true">新建项目</button>
      </div>
    </header>

    <section v-if="!isProjectDrawing && !isProjectOverview" class="grid flex-none grid-cols-2 gap-3 md:grid-cols-4" aria-label="项目阶段筛选">
      <button v-for="card in statusCards" :key="card.value" type="button" class="border p-4 text-left transition focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-60 sm:p-5"
        :class="activeStatus === card.value ? 'border-accent bg-accent-tint text-accent-ink' : 'border-line bg-white hover:border-accent-line'"
        :aria-pressed="activeStatus === card.value" :disabled="card.count === null" @click="card.count !== null && (activeStatus = card.value)">
        <span class="block text-sm">{{ card.label }}</span>
        <span class="mt-2 block text-3xl font-bold tabular-nums">{{ card.count !== null && workspace.initialized && !workspace.error ? card.count : '—' }}</span>
      </button>
    </section>

    <ProjectEmptyState v-if="!workspace.projects.length" />
    <div v-else-if="isProjectDrawing && workspace.currentFloorId" class="min-h-0 flex-1">
      <ProjectDrawingRecognition :key="workspace.currentFloorId" />
    </div>
    <ProjectBenchOverview v-else-if="isProjectOverview && workspace.currentProject && workspace.currentFloorId" :project="workspace.currentProject" :floor-id="workspace.currentFloorId" />
    <ProjectBenchList v-else :status-filter="activeStatus" />

    <ProjectCreateDialog v-if="showCreate && canCreateProject" @close="showCreate = false" />
  </div>
</template>
