<script setup lang="ts">
const store = useOfficeStore()
const { projectSteps, currentStep } = useWorkspaceNavigation()
</script>

<template>
  <section class="flex-none border-b border-line bg-bg px-5 py-3 xl:px-7" aria-label="项目设计流程">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div class="min-w-0">
        <div class="text-[11px] text-muted mb-1">项目工作台 / 空间设计</div>
        <div class="truncate text-sm font-semibold">{{ store.drawingName }}</div>
      </div>
      <nav class="flex flex-wrap items-center gap-1 sm:gap-2" aria-label="项目设计步骤">
        <template v-for="(step, index) in projectSteps" :key="step.label">
          <NuxtLink
            :to="step.to"
            :aria-current="index === currentStep ? 'step' : undefined"
            class="flex items-center gap-2 rounded-lg px-2 py-2 text-xs transition sm:px-3"
            :class="index === currentStep ? 'bg-white text-bronze-dark font-semibold shadow-sm ring-1 ring-line' : 'text-muted hover:bg-cream hover:text-ink'"
          >
            <span class="grid h-6 w-6 place-items-center rounded-full border text-[11px]" :class="index === currentStep ? 'border-bronze bg-bronze text-white' : 'border-line bg-white'">{{ index + 1 }}</span>
            <span>{{ step.label }}</span>
          </NuxtLink>
          <span v-if="index < projectSteps.length - 1" class="hidden h-px w-4 bg-line sm:block" aria-hidden="true" />
        </template>
      </nav>
    </div>
  </section>
</template>
