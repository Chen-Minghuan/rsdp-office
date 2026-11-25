<script setup lang="ts">
import type { RouteLocationRaw } from 'vue-router'

defineProps<{
  name: string
  code: string
  imageUrl?: string | null
  to: RouteLocationRaw
}>()
</script>

<template>
  <li class="catalog-card group relative flex min-w-0 flex-col border border-neutral-300 bg-white">
    <NuxtLink :to="to"
      class="flex h-full min-w-0 flex-col outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-accent"
      :aria-label="`查看${name}详情`">
      <div class="relative aspect-square border-b border-neutral-100 bg-white">
        <ProductImagePreview v-if="imageUrl" :src="imageUrl" alt="" size="small" />
        <div v-else class="grid h-full place-items-center px-3 text-center text-xs text-neutral-500">暂无商品图片</div>
      </div>
      <div class="flex flex-1 flex-col gap-1 px-2.5 py-2">
        <span class="line-clamp-2 wrap-break-word text-sm font-semibold leading-5 group-hover:text-accent-ink" :title="name">{{ name }}</span>
        <span class="truncate text-xs text-neutral-600">{{ code }}</span>
        <slot name="metadata" />
      </div>
    </NuxtLink>
    <slot />
  </li>
</template>

<style scoped>
.catalog-card:hover,
.catalog-card:focus-within { border-color: var(--color-accent); }
</style>
