<script setup lang="ts">
import { workbookPreviewPageSize } from '~/utils/localProductImport'

const props = defineProps<{ total: number }>()
const page = defineModel<number>({ required: true })
const pages = computed(() => Math.max(1, Math.ceil(props.total / workbookPreviewPageSize)))
watch(pages, value => { if (page.value > value) page.value = value })
</script>

<template>
  <nav v-if="pages > 1" class="flex flex-wrap items-center gap-1.5 text-xs text-muted" aria-label="预览商品分页">
    <button type="button" class="border border-line px-2 py-1 hover:bg-bg disabled:opacity-40" :disabled="page <= 1" @click="page--">上一页</button>
    <label>
      <span class="sr-only">跳转预览页</span>
      <select v-model="page" class="border border-line bg-white px-1 py-1">
        <option v-for="number in pages" :key="number" :value="number">第 {{ number }} 页</option>
      </select>
    </label>
    <button type="button" class="border border-line px-2 py-1 hover:bg-bg disabled:opacity-40" :disabled="page >= pages" @click="page++">下一页</button>
    <span>共 {{ pages }} 页</span>
  </nav>
</template>
