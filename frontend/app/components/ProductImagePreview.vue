<script setup lang="ts">
const props = withDefaults(defineProps<{
  src: string | null | undefined
  alt: string
  size?: 'small' | 'large'
  enableLightbox?: boolean
}>(), { size: 'large' })

const stage = ref<HTMLDivElement | null>(null)
const imageLoaded = ref(false)
const hovering = ref(false)
const listPreviewStyle = ref<Record<string, string>>({})
const naturalSize = ref({ width: 0, height: 0 })
const displayedSize = ref({ width: 0, height: 0 })
const imagePoint = ref({ x: 0, y: 0 })
const pointer = ref({ x: 0, y: 0 })
const lightboxOpen = ref(false)
const lensSize = 210
const zoom = 2.5

watch(() => props.src, () => {
  imageLoaded.value = false
  hovering.value = false
  lightboxOpen.value = false
  naturalSize.value = { width: 0, height: 0 }
})

function onImageLoad(event: Event) {
  const image = event.target as HTMLImageElement
  naturalSize.value = { width: image.naturalWidth, height: image.naturalHeight }
  imageLoaded.value = image.naturalWidth > 0 && image.naturalHeight > 0
}

function openLightbox() {
  if (props.enableLightbox && props.src && imageLoaded.value) lightboxOpen.value = true
}

function closeLightbox() {
  lightboxOpen.value = false
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closeLightbox()
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))

function calculateListPreview(bounds: DOMRect) {
  const viewportWidth = typeof window === 'undefined' ? 1200 : window.innerWidth
  const viewportHeight = typeof window === 'undefined' ? 800 : window.innerHeight
  const naturalWidth = naturalSize.value.width || 1
  const naturalHeight = naturalSize.value.height || 1
  const maxWidth = Math.min(560, viewportWidth - 40)
  const maxHeight = Math.min(620, viewportHeight - 40)
  const scale = Math.min(maxWidth / naturalWidth, maxHeight / naturalHeight, 1)
  const width = Math.max(96, Math.round(naturalWidth * scale))
  const height = Math.max(96, Math.round(naturalHeight * scale))
  const frameWidth = width + 24
  const frameHeight = height + 24
  const left = bounds.right + 16 + frameWidth <= viewportWidth
    ? bounds.right + 16
    : bounds.left - 16 - frameWidth >= 0
      ? bounds.left - 16 - frameWidth
      : Math.max(12, Math.min(bounds.left, viewportWidth - frameWidth - 12))
  const top = Math.max(12, Math.min(bounds.top - 20, viewportHeight - frameHeight - 12))
  return { left: `${left}px`, top: `${top}px`, width: `${frameWidth}px`, height: `${frameHeight}px` }
}

function showPreview(event: PointerEvent) {
  if (event.pointerType === 'touch' || !props.src || !imageLoaded.value || !stage.value) return
  if (props.size === 'small') {
    const bounds = stage.value.getBoundingClientRect()
    listPreviewStyle.value = calculateListPreview(bounds)
  } else {
    const bounds = stage.value.getBoundingClientRect()
    const scale = Math.min(bounds.width / naturalSize.value.width, bounds.height / naturalSize.value.height)
    const width = naturalSize.value.width * scale
    const height = naturalSize.value.height * scale
    const x = event.clientX - bounds.left - (bounds.width - width) / 2
    const y = event.clientY - bounds.top - (bounds.height - height) / 2
    hovering.value = x >= 0 && x <= width && y >= 0 && y <= height
    if (!hovering.value) return
    displayedSize.value = { width, height }
    imagePoint.value = { x, y }
    pointer.value = { x: event.clientX, y: event.clientY }
  }
  hovering.value = true
}

const lensStyle = computed(() => {
  const viewportWidth = typeof window === 'undefined' ? 1200 : window.innerWidth
  const viewportHeight = typeof window === 'undefined' ? 800 : window.innerHeight
  const left = pointer.value.x + 18 + lensSize <= viewportWidth
    ? pointer.value.x + 18
    : Math.max(8, pointer.value.x - 18 - lensSize)
  const top = Math.max(8, Math.min(pointer.value.y - lensSize / 2, viewportHeight - lensSize - 8))
  return { left: `${left}px`, top: `${top}px`, width: `${lensSize}px`, height: `${lensSize}px` }
})

const enlargedStyle = computed(() => ({
  width: `${displayedSize.value.width * zoom}px`,
  height: `${displayedSize.value.height * zoom}px`,
  left: `${lensSize / 2 - imagePoint.value.x * zoom}px`,
  top: `${lensSize / 2 - imagePoint.value.y * zoom}px`,
}))

</script>

<template>
  <div ref="stage" class="relative grid h-full w-full place-items-center overflow-hidden bg-white" :class="enableLightbox && src && imageLoaded ? 'cursor-zoom-in' : ''" @pointerenter="showPreview" @pointermove="showPreview" @pointerleave="hovering = false" @click="openLightbox">
    <img v-if="src" :src="src" :alt="alt" class="absolute inset-0 h-full w-full object-center object-contain" :loading="size === 'small' ? 'lazy' : 'eager'" @load="onImageLoad" @error="imageLoaded = false; hovering = false">
    <div v-else class="flex flex-col items-center gap-2 text-muted"><AppIcon name="cube" :size="size === 'small' ? '5' : '10'" /><span v-if="size === 'large'" class="text-xs">暂无商品图片</span></div>
  </div>
  <Teleport to="body">
    <div v-if="hovering && src && size === 'small'" class="pointer-events-none fixed z-[100] grid place-items-center overflow-hidden rounded-xl border border-line bg-white p-3 shadow-[0_18px_55px_rgba(35,29,24,0.22)]" :style="listPreviewStyle" aria-hidden="true">
      <img :src="src" alt="" class="block h-full w-full object-contain">
    </div>
    <div v-if="hovering && src && size === 'large'" class="pointer-events-none fixed z-[100] overflow-hidden rounded-full border-[3px] border-white bg-white shadow-[0_12px_38px_rgba(35,29,24,0.24)]" :style="lensStyle" aria-hidden="true">
      <img :src="src" alt="" class="absolute max-w-none" :style="enlargedStyle">
    </div>
    <div v-if="lightboxOpen && src" class="fixed inset-0 z-[120] flex items-center justify-center bg-black/75 p-4 sm:p-8" role="dialog" aria-modal="true" :aria-label="`${alt}完整预览`" @click.self="closeLightbox" @keydown="onKeydown">
      <div class="relative flex max-h-full max-w-full flex-col items-center gap-3">
        <button type="button" class="absolute right-2 top-2 z-10 rounded-full bg-black/60 px-3 py-1.5 text-sm font-medium text-white transition hover:bg-black/80" aria-label="关闭图片预览" @click="closeLightbox">关闭</button>
        <div class="grid max-h-[calc(100dvh-88px)] max-w-[calc(100vw-32px)] place-items-center overflow-hidden rounded-xl bg-white p-3 shadow-2xl sm:max-w-[calc(100vw-64px)]">
          <img :src="src" :alt="alt" class="block max-h-[calc(100dvh-112px)] max-w-[calc(100vw-56px)] object-contain sm:max-h-[calc(100dvh-128px)] sm:max-w-[calc(100vw-96px)]">
        </div>
        <p class="max-w-[min(720px,calc(100vw-32px))] truncate text-xs text-white/80">点击空白处或按 Esc 关闭</p>
      </div>
    </div>
  </Teleport>
</template>
