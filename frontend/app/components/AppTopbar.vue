<script setup lang="ts">
const { links, activeLink, isDesignWorkflow, floorReady } = useWorkspaceNavigation()
const workspace = useWorkspaceStore()
const store = useOfficeStore()
const showTemplate = ref(false)
const showDesignContext = computed(() => isDesignWorkflow.value && floorReady.value)
watch(showDesignContext, visible => { if (!visible) showTemplate.value = false })

function openTemplateLayout(zoneId: string) {
  showTemplate.value = false
  return navigateTo({ path: '/canvas', query: { floorId: workspace.currentFloorId, mode: 'design', zoneId } })
}
</script>

<template>
  <header class="flex flex-none flex-wrap items-center gap-x-6 border-b border-neutral-300 bg-white px-2 py-2 sm:px-5" data-testid="workspace-topbar">
    <div class="flex items-center gap-2 px-2 py-1 md:min-w-45">
      <span class="text-2xl font-black tracking-tight">RSDP</span>
      <span class="text-[10px] leading-tight text-neutral-600">办公空间<br>智能设计平台</span>
    </div>
    <nav class="grid w-full grid-cols-5 items-center gap-1 text-center md:w-auto md:flex-1" aria-label="主导航">
      <NuxtLink
        v-for="link in links"
        :key="link.id"
        :to="link.to"
        :aria-current="activeLink === link.id ? 'page' : undefined"
        class="primary-link mx-auto px-1 py-2 text-xs font-semibold transition sm:px-3 sm:text-base lg:text-lg"
      >{{ link.label }}</NuxtLink>
    </nav>
    <div v-if="showDesignContext" class="flex min-w-0 basis-full flex-wrap items-center gap-x-3 px-2 pt-1 text-xs text-neutral-600">
      <span data-testid="workspace-topbar-context">{{ workspace.currentProjectName }} / {{ workspace.currentFloorName }}</span>
      <span v-if="store.files[0]" class="hidden max-w-[24rem] truncate sm:inline" :title="store.files[0].name">{{ store.files[0].name }}</span>
      <button v-if="store.spaceTemplate" type="button" class="text-bronze-dark hover:underline" @click="showTemplate = true">
        查看空间模板<template v-if="!store.spaceTemplateCurrent">（需更新）</template>
      </button>
    </div>
    <OfficeCanvasSpaceTemplateDialog
      v-if="showTemplate && showDesignContext"
      @close="showTemplate = false"
      @layout="openTemplateLayout"
    />
  </header>
</template>

<style scoped>
.primary-link { border-radius: 0.65rem !important; }
.primary-link:hover { background: #f4eee6; color: #8a5e36; }
.primary-link[aria-current='page'] { background: #a77b51; color: white; }
</style>
