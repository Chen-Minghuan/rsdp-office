<script setup lang="ts">
const route = useRoute()
const store = useOfficeStore()
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
  <aside class="flex-none bg-white border-b border-line p-3 md:w-56 md:border-b-0 md:border-r md:flex md:flex-col md:p-5 md:pb-4">
    <div class="hidden items-center gap-2 mb-9 px-3 md:flex">
      <span class="text-2xl font-extrabold tracking-tight text-ink">RSDP</span>
      <span class="text-[10px] text-muted leading-tight">办公空间<br>智能设计平台</span>
    </div>
    <nav class="grid grid-cols-2 gap-3 md:grid-cols-1 md:gap-7" aria-label="工作区导航">
      <div>
        <div class="px-3 mb-2 text-[11px] font-medium tracking-wider text-muted">项目工作台</div>
        <NuxtLink
          :to="lastProjectRoute"
          :aria-current="isProjectWorkflow ? 'page' : undefined"
          class="flex items-center gap-3 rounded-xl px-3 py-3 text-sm transition"
          :class="isProjectWorkflow ? 'bg-cream text-bronze-dark font-semibold' : 'text-[#5f5b56] hover:bg-cream'"
        >
          <AppIcon name="map" />
          <span>空间设计</span>
        </NuxtLink>
        <div class="hidden px-3 mt-3 text-xs leading-relaxed text-muted md:block">
          <div class="text-[10px] mb-1">当前图纸</div>
          <div class="line-clamp-2">{{ store.drawingName }}</div>
        </div>
      </div>
      <div class="grid content-start gap-1.5">
        <div class="px-3 mb-0.5 text-[11px] font-medium tracking-wider text-muted">商品资源</div>
      <NuxtLink
        v-for="link in productLinks"
        :key="link.to"
        :to="link.to"
        :aria-current="isActive(link.to) ? 'page' : undefined"
        :class="[
          'flex items-center gap-3 w-full px-3 py-3 rounded-xl text-sm transition',
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
