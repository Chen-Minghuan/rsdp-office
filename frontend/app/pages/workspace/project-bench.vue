<script setup lang="ts">
import { projectBenchPath } from '~/utils/projectBenchRoutes'

const workspace = useWorkspaceStore()
const { canCreateProject } = useAuth()
const { isProjectDrawing } = useWorkspaceNavigation()
const showCreate = ref(false)
const projectCount = computed(() => workspace.initialized ? workspace.projects.length : '—')
const designingCount = computed(() => workspace.initialized ? workspace.projects.filter(project => project.stage === 'DESIGNING').length : '—')
const deliveringCount = computed(() => workspace.initialized ? workspace.projects.filter(project => project.stage === 'DELIVERING').length : '—')
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

    <div v-if="workspace.projects.length" class="grid flex-none grid-cols-3 gap-px overflow-hidden rounded-xl border border-line bg-line">
      <section class="bg-white p-4" aria-label="全部项目"><div class="text-1xs text-muted">全部项目</div><div class="mt-1 text-2xl font-bold tracking-tight">{{ projectCount }}</div></section>
      <section class="bg-white p-4" aria-label="项目设计中"><div class="text-1xs text-muted">项目设计中</div><div class="mt-1 text-2xl font-bold tracking-tight">{{ designingCount }}</div></section>
      <section class="bg-white p-4" aria-label="项目交付中"><div class="text-1xs text-muted">项目交付中</div><div class="mt-1 text-2xl font-bold tracking-tight">{{ deliveringCount }}</div></section>
    </div>

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
