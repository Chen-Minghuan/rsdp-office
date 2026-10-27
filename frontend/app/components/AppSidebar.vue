<script setup lang="ts">
const route = useRoute()
const { isProjectWorkflow, lastProjectRoute } = useWorkspaceNavigation()
const productLinks = [
  { icon: 'cube', label: '商品库', to: '/products' },
  { icon: 'documentText', label: '导入记录', to: '/products/import-records' },
]

function isActive(to: string) {
  if (to === '/products') return route.path === '/products' || route.path === '/products/import'
  return route.path === to
}
</script>

<template>
  <aside class="max-h-[45dvh] flex-none overflow-y-auto border-b border-line bg-white p-3 md:max-h-none md:w-60 md:border-b-0 md:border-r md:flex md:flex-col md:p-3">
    <div class="hidden items-center gap-2 mb-6 px-2 md:flex">
      <span class="text-2xl font-extrabold tracking-tight text-ink">RSDP</span>
      <span class="text-[10px] text-muted leading-tight">办公空间<br>智能设计平台</span>
    </div>
    <ProjectFloorTree class="mb-3" />
    <nav class="grid grid-cols-2 gap-2 md:grid-cols-1 md:gap-3" aria-label="工作区导航">
      <div>
        <div class="mb-1 px-2 text-[11px] font-medium tracking-wider text-muted">项目工作台</div>
        <NuxtLink
          :to="lastProjectRoute"
          :aria-current="isProjectWorkflow ? 'page' : undefined"
          class="flex items-center gap-2 rounded-none px-2.5 py-2 text-sm transition"
          :class="isProjectWorkflow ? 'bg-cream text-bronze-dark font-semibold' : 'text-[#5f5b56] hover:bg-cream'"
        >
          <AppIcon name="map" />
          <span>空间设计</span>
        </NuxtLink>
      </div>
      <div class="grid content-start gap-1">
        <div class="mb-1 px-2 text-[11px] font-medium tracking-wider text-muted">商品资源</div>
      <NuxtLink
        v-for="link in productLinks"
        :key="link.to"
        :to="link.to"
        :aria-current="isActive(link.to) ? 'page' : undefined"
        :class="[
          'flex w-full items-center gap-2 rounded-none px-2.5 py-2 text-sm transition',
          isActive(link.to) ? 'bg-cream text-bronze-dark font-semibold' : 'text-[#5f5b56] hover:bg-[#f8f3ef]',
        ]"
      >
        <AppIcon :name="link.icon" />
        <span>{{ link.label }}</span>
      </NuxtLink>
      </div>
    </nav>
    <div class="hidden mt-auto pt-4 border-t border-line md:block">
      <div class="text-xs text-muted px-3 flex items-center gap-1.5">
        <span class="w-1.5 h-1.5 rounded-full bg-green"></span>
        办公空间智能设计平台
      </div>
    </div>
  </aside>
</template>
