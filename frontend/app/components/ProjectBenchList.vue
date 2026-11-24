<script setup lang="ts">
import type { ProjectBudget, WorkspaceProject } from '~/types/workspace'
import { projectOverviewRoute } from '~/utils/projectBenchRoutes'

// 项目树由应用外壳加载，只有本列表展示时才单独读取预算。
const workspace = useWorkspaceStore()
const { storageScope } = useAuth()
const api = useWorkspaceApi()
const budgetByProject = ref<Record<string, ProjectBudget>>({})
const budgetsLoading = ref(false)
const projectKeys = computed(() => workspace.projects.map(project => project.projectId).join(','))

watch([projectKeys, storageScope], async ([projects, scope], _previous, onCleanup) => {
  budgetByProject.value = {}
  budgetsLoading.value = !!projects
  if (!projects) return

  let current = true
  onCleanup(() => { current = false })
  try {
    const budgets = await api.listProjectBudgets()
    if (current && scope === storageScope.value) budgetByProject.value = budgets ?? {}
  } catch {
    // An unavailable budget must not hide projects or prevent opening them.
  } finally {
    if (current && scope === storageScope.value) budgetsLoading.value = false
  }
}, { immediate: true })

const search = ref('')
const sortKey = ref<'name' | 'floors'>('name')

const sortOptions = [
  { value: 'name', label: '名称' },
  { value: 'floors', label: '楼层数' },
] as const

function leaderName(project: WorkspaceProject) {
  return project.leader ? project.leader.displayName || project.leader.username : ''
}

async function openProject(project: WorkspaceProject) {
  const firstFloor = [...project.floors].sort((a, b) => a.sortOrder - b.sortOrder)[0]
  if (!firstFloor || workspace.switching) return
  await navigateTo(projectOverviewRoute(firstFloor.floorId))
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
    <div v-else class="overflow-x-auto border-t border-line">
      <table class="w-full min-w-[600px] border-collapse text-left">
        <thead class="bg-bg">
          <tr class="text-xs text-muted">
            <th scope="col" class="px-5 py-2.5 text-left font-normal">项目名</th>
            <th scope="col" class="px-4 py-2.5 font-normal">负责人</th>
            <th scope="col" class="px-4 py-2.5 font-normal">楼层数</th>
            <th scope="col" class="px-5 py-2.5 font-normal">商品参考金额</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="project in visibleProjects"
            :key="project.projectId"
            class="group border-t border-line transition hover:bg-neutral-50"
          >
            <td class="relative px-5 py-3.5 text-left">
              <span class="absolute left-0 top-0 h-full w-0.5 bg-bronze opacity-0 transition group-hover:opacity-100" aria-hidden="true" />
              <button type="button" class="flex w-full min-w-0 items-center gap-2 text-left hover:text-[#693299] disabled:opacity-50" :aria-label="`进入项目 ${project.projectName}`" :disabled="!project.floors.length || workspace.switching" @click="openProject(project)">
                <span class="truncate text-sm font-semibold">{{ project.projectName }}</span>
                <span v-if="project.projectId === workspace.currentProjectId" class="flex-none border border-bronze/40 px-1.5 py-px text-[11px] leading-4 text-bronze-dark">
                  当前
                </span>
              </button>
              <div v-if="project.collaborators.length" class="mt-1 text-xs text-muted">协作 {{ project.collaborators.length }} 人</div>
            </td>
            <td class="px-4 py-3.5 text-sm">
              <span v-if="leaderName(project)">{{ leaderName(project) }}</span>
              <span v-else class="text-muted">—</span>
            </td>
            <td class="px-4 py-3.5 text-sm [font-variant-numeric:tabular-nums]">{{ project.floors.length }}</td>
            <td class="px-5 py-3.5 text-sm [font-variant-numeric:tabular-nums]">
              <span v-if="budgetsLoading" class="text-xs text-muted">加载中…</span>
              <template v-else-if="budgetByProject[project.projectId]?.budgetTotal != null && budgetByProject[project.projectId]?.budgetStatus !== 'UNAVAILABLE'">
                <span>{{ money(budgetByProject[project.projectId].budgetTotal) }}</span>
                <span v-if="budgetByProject[project.projectId]?.budgetStatus === 'PENDING_PRICING'" class="ml-1 text-xs text-bronze-dark">待补价</span>
              </template>
              <span v-else class="text-muted">—</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
