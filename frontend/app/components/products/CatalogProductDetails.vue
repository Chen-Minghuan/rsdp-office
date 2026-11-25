<script setup lang="ts">
import { sameOriginAssetUrl } from '~/utils/apiBase'

const props = defineProps<{
  name: string
  code: string
  images: { id: string, url: string, primary: boolean }[]
}>()

const selectedImageId = ref<string>()
const selectedImage = computed(() => props.images.find(image => image.id === selectedImageId.value) ?? props.images[0])
</script>

<template>
  <section class="rounded-xl border border-line bg-white p-5 shadow-panel sm:p-6">
    <div class="mb-6 flex flex-wrap items-center gap-3 border-b border-line pb-5">
      <span class="text-sm font-semibold text-ink">商品概览</span>
      <span class="rounded-md bg-bg px-2.5 py-1 text-xs text-muted">{{ code }}</span>
      <slot name="badges" />
    </div>

    <div class="grid gap-8 lg:grid-cols-[minmax(0,440px)_minmax(0,1fr)]">
      <section class="min-w-0" aria-label="商品图片">
        <div class="aspect-[4/3] overflow-hidden rounded-xl border border-line bg-white">
          <ProductImagePreview :src="selectedImage?.url" :alt="`${name}图片`" enable-lightbox />
        </div>
        <div v-if="images.length" class="mt-3 flex gap-3 overflow-x-auto pb-2" aria-label="选择商品图片">
          <button v-for="(image, index) in images" :key="image.id" type="button"
            class="relative grid h-20 w-20 shrink-0 place-items-center overflow-hidden rounded-lg border-2 bg-white transition"
            :class="selectedImage?.id === image.id ? 'border-bronze' : 'border-line hover:border-bronze/50'"
            :aria-label="image.primary ? '查看主图' : `查看商品图片 ${index + 1}`"
            :aria-pressed="selectedImage?.id === image.id" @click="selectedImageId = image.id">
            <img :src="sameOriginAssetUrl(image.url)" alt="" class="absolute inset-0 h-full w-full object-contain" loading="lazy">
            <span v-if="image.primary" class="absolute bottom-0 left-0 rounded-tr bg-bronze px-1.5 py-0.5 text-[10px] text-white">主图</span>
          </button>
        </div>
        <p v-if="images.length" class="mt-1 text-xs text-muted">共 {{ images.length }} 张图片</p>
      </section>

      <div class="min-w-0 max-w-[900px] space-y-5">
        <section>
          <div class="mb-4 flex items-end justify-between gap-3 border-b border-line pb-3">
            <div><div class="mb-1 text-xs text-muted">商品资料</div><h2 class="text-base font-semibold">基础信息</h2></div>
            <slot name="price" />
          </div>
          <dl class="grid gap-4 sm:grid-cols-2">
            <div><dt class="mb-1 text-xs text-muted">商品名称</dt><dd class="text-sm font-semibold text-ink">{{ name }}</dd></div>
            <slot name="facts" />
          </dl>
          <slot name="description" />
        </section>
        <slot name="dates" />
      </div>
    </div>
  </section>
</template>
