<script setup lang="ts">
const store = useOfficeStore()
const { isProjectWorkflow } = useWorkspaceNavigation()
onMounted(() => {
  store.init()
})
</script>

<template>
  <div class="flex h-dvh min-h-[600px] flex-col overflow-hidden bg-bg text-ink md:flex-row">
    <AppSidebar />
    <main class="flex flex-1 flex-col min-w-0 min-h-0">
      <AppTopbar />
      <AppProjectSteps v-if="isProjectWorkflow" />
      <div class="flex flex-col flex-1 min-h-0 p-4 gap-4 md:p-5">
        <NuxtPage />
      </div>
    </main>
  </div>
</template>
