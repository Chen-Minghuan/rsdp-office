<script setup lang="ts">
const store = useOfficeStore()
const { isProjectWorkflow } = useWorkspaceNavigation()
const route = useRoute()
const isLoginPage = computed(() => route.path === '/login')
const isSpatialWorkbench = computed(() => route.path === '/spatial-canvas' || (route.path === '/canvas' && store.canvasMode === 'overview'))
onMounted(() => {
  if (!isLoginPage.value && !store.initialized) store.init()
})
watch(isLoginPage, (loginPage, wasLoginPage) => {
  if (wasLoginPage && !loginPage && !store.initialized) store.init()
})
</script>

<template>
  <NuxtPage v-if="isLoginPage" />
  <div v-else :class="isSpatialWorkbench ? '' : 'flex h-dvh min-h-[600px] flex-col overflow-hidden bg-bg text-ink md:flex-row'">
    <AppSidebar v-if="!isSpatialWorkbench" />
    <main :class="isSpatialWorkbench ? '' : 'flex flex-1 flex-col min-w-0 min-h-0'">
      <AppTopbar v-if="!isSpatialWorkbench" />
      <AppProjectSteps v-if="!isSpatialWorkbench && isProjectWorkflow" />
      <div :class="isSpatialWorkbench ? '' : 'flex flex-col flex-1 min-h-0 overflow-y-auto p-4 gap-4 md:p-5'">
        <NuxtPage />
      </div>
    </main>
  </div>
</template>
