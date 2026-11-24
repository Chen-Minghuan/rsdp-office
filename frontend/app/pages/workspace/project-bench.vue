<script setup lang="ts">
import { projectBenchPath, projectOverviewRoute } from '~/utils/projectBenchRoutes'

const workspace = useWorkspaceStore()
const { canCreateProject } = useAuth()
const { isProjectDrawing, isProjectOverview } = useWorkspaceNavigation()
const showCreate = ref(false)
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

    <ProjectEmptyState v-if="!workspace.projects.length" />
    <div v-else-if="isProjectDrawing && workspace.currentFloorId" class="min-h-0 flex-1">
      <ProjectDrawingRecognition :key="workspace.currentFloorId" />
    </div>
    <ProjectBenchOverview v-else-if="isProjectOverview && workspace.currentProject && workspace.currentFloorId" :project="workspace.currentProject" :floor-id="workspace.currentFloorId" />
    <ProjectBenchList v-else />

    <ProjectCreateDialog v-if="showCreate && canCreateProject" @close="showCreate = false" />
  </div>
</template>
