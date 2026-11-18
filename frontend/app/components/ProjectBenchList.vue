<script setup lang="ts">
import type { WorkspaceProject } from '~/types/workspace'

// 项目列表与侧栏项目树同源：useWorkspaceStore 由 app.vue 挂载时 init() 拉取，组件不重复请求。
const workspace = useWorkspaceStore()

const search = ref('')
const sortKey = ref<'name' | 'floors'>('name')

const sortOptions = [
  { value: 'name', label: '名称' },
  { value: 'floors', label: '楼层数' },
] as const

function leaderName(project: WorkspaceProject) {
  return project.leader ? project.leader.displayName || project.leader.username : ''
}

const visibleProjects = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  const filtered = keyword
    ? workspace.projects.filter(p =>
        p.projectName.toLowerCase().includes(keyword)
        || leaderName(p).toLowerCase().includes(keyword))
    : [...workspace.projects]
  const by: Record<typeof sortKey.value, (a: WorkspaceProject, b: WorkspaceProject) => number> = {
    name: (a, b) => a.projectName.localeCompare(b.projectName, 'zh'),
    floors: (a, b) => b.floors.length - a.floors.length,
  }
  return filtered.sort(by[sortKey.value])
})
</script>

<template>
  <section class="border border-line bg-white" aria-label="项目列表">
    <div class="flex flex-wrap items-center justify-between gap-3 px-5 pt-4 pb-3">
      <div class="flex items-baseline gap-2">
        <h2 class="text-sm font-semibold">项目列表</h2>
        <span class="text-xs text-muted">共 {{ workspace.projects.length }} 个项目</span>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <div class="relative">
          <AppIcon name="search" :size="4" class="absolute left-3 top-2.5 text-muted" />
          <input
            v-model="search"
            type="search"
            aria-label="搜索项目名称"
            placeholder="搜索项目名称"
            class="w-52 border border-line bg-bg py-2 pl-9 pr-3 text-sm outline-bronze"
          >
        </div>
        <select v-model="sortKey" aria-label="项目排序" class="border border-line bg-white px-3 py-2 text-sm outline-bronze">
          <option v-for="opt in sortOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
      </div>
    </div>

    <p v-if="workspace.error" role="alert" class="border-t border-line px-5 py-6 text-sm text-red-700">{{ workspace.error }}</p>
    <p v-else-if="!workspace.initialized" class="border-t border-line px-5 py-8 text-center text-sm text-muted">正在加载项目…</p>
    <p v-else-if="!workspace.projects.length" class="border-t border-line px-5 py-8 text-center text-sm text-muted">当前账号暂无可访问项目</p>
    <p v-else-if="!visibleProjects.length" class="border-t border-line px-5 py-8 text-center text-sm text-muted">没有匹配的项目，换个关键词试试。</p>
    <ul v-else class="border-t border-line">
      <li
        v-for="project in visibleProjects"
        :key="project.projectId"
        class="group relative px-5 py-3.5 transition hover:bg-[#f8f3ef]"
      >
        <span class="absolute left-0 top-0 h-full w-0.5 bg-bronze opacity-0 transition group-hover:opacity-100" aria-hidden="true" />
        <div class="min-w-0">
          <div class="flex items-center gap-2">
            <span class="truncate text-sm font-semibold">{{ project.projectName }}</span>
            <span v-if="project.projectId === workspace.currentProjectId" class="flex-none border border-bronze/40 px-1.5 py-px text-[11px] leading-4 text-bronze-dark">
              当前
            </span>
          </div>
          <div class="mt-1 text-xs text-muted">
            <template v-if="leaderName(project)">负责人 {{ leaderName(project) }} · </template>{{ project.floors.length }} 个楼层<template v-if="project.collaborators.length"> · 协作 {{ project.collaborators.length }} 人</template>
          </div>
        </div>
      </li>
    </ul>
  </section>
</template>
