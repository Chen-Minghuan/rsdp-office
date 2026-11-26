<script setup lang="ts">
import { sameOriginAssetUrl } from '~/utils/apiBase'

const props = defineProps<{
  name: string
  images: { id: string, url: string, primary: boolean }[]
}>()

const selectedImageId = ref<string>()
const selectedImage = computed(() => props.images.find(image => image.id === selectedImageId.value) ?? props.images[0])
const currentImageNumber = computed(() => props.images.findIndex(image => image.id === selectedImage.value?.id) + 1)
</script>

<template>
  <section class="min-w-0 border border-line bg-white" aria-label="商品图片">
    <div class="p-3 sm:p-5">
      <div class="aspect-[4/3] overflow-hidden border border-line bg-white sm:aspect-[8/5]">
        <ProductImagePreview :src="selectedImage?.url" :alt="`${name}图片`" show-zoom-hint enable-lightbox />
      </div>
    </div>
    <div v-if="images.length > 1" class="flex min-w-0 items-center justify-between gap-3 border-t border-line px-3 py-2.5 sm:px-5 sm:py-3">
      <div aria-label="选择商品图片" class="flex min-w-0 gap-2 overflow-x-auto p-1">
        <button v-for="(image, index) in images" :key="image.id" type="button"
          class="relative grid size-14 shrink-0 place-items-center overflow-hidden border bg-white transition sm:size-[68px]"
          :class="selectedImage?.id === image.id ? 'border-accent ring-1 ring-inset ring-accent' : 'border-line hover:border-accent'"
          :aria-label="image.primary ? '查看主图' : `查看商品图片 ${index + 1}`"
          :aria-pressed="selectedImage?.id === image.id" @click="selectedImageId = image.id">
          <img :src="sameOriginAssetUrl(image.url)" alt="" class="absolute inset-0 h-full w-full object-contain" loading="lazy">
          <span v-if="image.primary" class="absolute bottom-0 left-0 bg-accent-ink px-1.5 py-0.5 text-[10px] text-white">主图</span>
        </button>
      </div>
      <span class="shrink-0 text-xs text-muted tabular-nums" aria-live="polite">
        第 {{ currentImageNumber }} 张 / 共 {{ images.length }} 张
      </span>
    </div>
  </section>
</template>
