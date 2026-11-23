<script setup lang="ts">
const workspace = useWorkspaceStore()
const store = useOfficeStore()
const router = useRouter()
const { projectSteps, currentStep } = useWorkspaceNavigation()
const showTemplate = ref(false)

function openTemplateLayout(zoneId: string) {
  router.push({ path: '/canvas', query: { floorId: workspace.currentFloorId, mode: 'design', zoneId } })
}
</script>

<template>
  <section class="flex min-w-0 flex-[1_1_34rem] flex-wrap items-center justify-between gap-x-3 gap-y-1" aria-label="项目设计流程">
    <div class="flex min-w-0 flex-[1_1_9rem] items-center gap-2 text-xs">
      <strong class="min-w-0 truncate" :title="`${workspace.currentProjectName} / ${workspace.currentFloorName}`">{{ workspace.currentProjectName }} / {{ workspace.currentFloorName }}</strong>
      <span class="text-line">·</span>
      <span class="shrink-0">空间设计</span>
      <template v-if="store.drawingName">
        <span class="hidden text-line lg:inline">·</span>
        <span class="hidden min-w-0 truncate text-muted lg:inline" :title="`当前图纸：${store.drawingName}`">当前图纸：{{ store.drawingName }}</span>
      </template>
      <button v-if="store.spaceTemplate" type="button" class="hidden min-w-0 truncate text-bronze hover:underline xl:inline" :aria-label="`查看当前空间模板 ${store.spaceTemplate.name}`" :title="`当前空间模板：${store.spaceTemplate.name}${store.spaceTemplateCurrent ? '' : '（需更新）'}`" @click="showTemplate = true">
        当前空间模板：{{ store.spaceTemplate.name }}<template v-if="!store.spaceTemplateCurrent">（需更新）</template>
      </button>
    </div>
    <nav class="flex max-w-full flex-wrap items-center gap-1 sm:gap-2" aria-label="项目设计步骤">
      <template v-for="(step, index) in projectSteps" :key="step.label">
        <NuxtLink
          :to="step.to"
          :aria-current="index === currentStep ? 'step' : undefined"
          class="flex items-center gap-2 whitespace-nowrap rounded-lg px-2 py-2 text-xs transition sm:px-3"
          :class="index === currentStep ? 'bg-white text-bronze-dark font-semibold shadow-sm ring-1 ring-line' : 'text-muted hover:bg-cream hover:text-ink'"
        >
          <span class="grid h-6 w-6 place-items-center rounded-full border text-[11px]" :class="index === currentStep ? 'border-bronze bg-bronze text-white' : 'border-line bg-white'">{{ index + 1 }}</span>
          <span>{{ step.label }}</span>
        </NuxtLink>
        <span v-if="index < projectSteps.length - 1" class="hidden h-px w-4 bg-line sm:block" aria-hidden="true" />
      </template>
    </nav>
    <OfficeCanvasSpaceTemplateDialog v-if="showTemplate" @close="showTemplate = false" @layout="openTemplateLayout" />
  </section>
</template>
