<script setup lang="ts">
import { projectBenchPath } from '~/utils/projectBenchRoutes'

const workspace = useWorkspaceStore()
const { canCreateProject } = useAuth()
const { isProjectDrawing } = useWorkspaceNavigation()
const showCreate = ref(false)
</script>

<template>
  <div class="flex h-full min-h-0 flex-col gap-4 p-4 lg:p-5">
    <header class="flex flex-none flex-wrap items-center justify-between gap-3">
      <h1 class="text-[25px] font-bold tracking-tight">项目工作台</h1>
      <div class="flex items-center gap-3">
        <NuxtLink v-if="isProjectDrawing && workspace.currentFloorId" :to="projectBenchPath" class="text-sm text-bronze hover:underline">返回项目列表</NuxtLink>
        <button v-if="canCreateProject && workspace.projects.length" type="button" class="bg-bronze px-4 py-2 text-sm font-semibold text-white hover:bg-bronze-dark" @click="showCreate = true">新建项目</button>
      </div>
    </header>

    <ProjectEmptyState v-if="!workspace.projects.length" />
    <div v-else-if="isProjectDrawing && workspace.currentFloorId" class="min-h-0 flex-1">
      <ProjectDrawingRecognition :key="workspace.currentFloorId" />
    </div>
    <div v-else class="flex min-h-0 flex-1 flex-col items-start gap-5 overflow-y-auto lg:flex-row">
      <ProjectBenchList class="w-full min-w-0 lg:flex-1" />
      <ProjectBenchPoster v-if="workspace.currentFloorId" class="w-full flex-none lg:w-[300px]" />
    </div>

    <ProjectCreateDialog v-if="showCreate && canCreateProject" @close="showCreate = false" />
  </div>
</template>
