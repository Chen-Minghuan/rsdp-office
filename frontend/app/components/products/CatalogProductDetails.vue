<script setup lang="ts">
defineProps<{
  name?: string | null
  backTo: string
  images: { id: string, url: string, primary: boolean }[]
  loading: boolean
  error?: string | null
}>()

defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="min-h-full min-w-0 bg-bg px-4 py-4 sm:px-6 sm:py-6 lg:px-8">
    <div class="catalog-detail mx-auto w-full max-w-[1280px] space-y-5 sm:space-y-6">
      <header class="flex flex-wrap items-end justify-between gap-4 border-b border-line pb-4 sm:pb-5">
        <div class="min-w-0">
          <NuxtLink :to="backTo" class="mb-3 inline-flex items-center gap-1.5 py-1 text-sm font-medium text-muted hover:text-accent-ink">
            <AppIcon name="arrowLeft" size="4" aria-hidden="true" />返回商品库
          </NuxtLink>
          <div class="flex flex-wrap items-center gap-x-3 gap-y-2">
            <h1 class="min-w-0 break-words text-2xl font-bold leading-tight tracking-tight text-ink sm:text-[32px]">
              {{ name || '商品详情' }}
            </h1>
            <slot name="badges" />
          </div>
        </div>
        <slot name="actions" />
      </header>

      <p v-if="loading" role="status" class="grid min-h-48 place-items-center border border-line bg-white text-sm text-muted">加载商品中…</p>
      <div v-else-if="error" role="alert" class="flex flex-wrap items-center gap-3 border border-red-100 bg-red-50 p-4 text-sm text-red-800">
        <span>{{ error }}</span>
        <button type="button" class="font-semibold underline" @click="$emit('retry')">重试</button>
      </div>
      <div v-else class="catalog-detail-body grid min-w-0 items-start gap-4 sm:gap-5">
        <ProductsCatalogProductGallery :name="name || '商品'" :images="images" />

        <div class="grid min-w-0 gap-4 sm:gap-5">
          <section class="catalog-detail-info order-2 min-w-0 border border-line bg-white p-4 sm:p-5" aria-labelledby="catalog-product-info">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <h2 id="catalog-product-info" class="text-base font-semibold">商品资料</h2>
              <slot name="price" />
            </div>
            <dl class="mt-4 grid grid-cols-2 gap-x-4 gap-y-3 border-t border-line pt-4 sm:gap-x-6">
              <slot name="facts" />
            </dl>
          </section>
          <slot />
          <div v-if="$slots.more" class="order-3 min-w-0">
            <slot name="more" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.catalog-detail {
  container-type: inline-size;
}

@container (min-width: 760px) {
  .catalog-detail-body {
    grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr);
  }

  .catalog-detail-info {
    order: 0;
  }
}
</style>
