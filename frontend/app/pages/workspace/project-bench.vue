<script setup lang="ts">
const auth = useAuth()
const workspaceApi = useWorkspaceApi()

const displayName = computed(() => auth.user.value?.displayName || auth.user.value?.username || '')

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour >= 5 && hour < 11) return '早上好'
  if (hour >= 11 && hour < 14) return '中午好'
  if (hour >= 14 && hour < 18) return '下午好'
  return '晚上好'
})

const projectCount = ref<number | null>(null)

onMounted(async () => {
  try {
    projectCount.value = (await workspaceApi.listProjects()).length
  } catch {
    projectCount.value = null
  }
})
</script>

<template>
  <div class="flex h-full min-h-0 flex-col overflow-y-auto">
    <div class="flex-none">
      <div class="mb-3 text-xs text-muted">工作空间 / 项目工作台</div>
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 class="mb-2 text-[25px] font-bold tracking-tight">{{ greeting }}{{ displayName ? `，${displayName}` : '' }}</h1>
        </div>
      </div>
    </div>
    <div class="mt-5 grid flex-none grid-cols-4">
      <section class="rounded-xl border border-line bg-white p-6 shadow-panel" aria-label="全部项目">
        <div class="text-1xs text-muted">全部项目</div>
        <div class="mt-2 text-3xl font-bold tracking-tight">{{ projectCount ?? '—' }}</div>
      </section>
      <section class="rounded-xl border border-line bg-white p-5 shadow-panel" aria-label="数据看板占位">
      </section>
      <section class="rounded-xl border border-line bg-white p-5 shadow-panel" aria-label="数据看板占位">
      </section>
      <section class="rounded-xl border border-line bg-white p-5 shadow-panel" aria-label="数据看板占位">
      </section>
    </div>
  </div>
</template>
