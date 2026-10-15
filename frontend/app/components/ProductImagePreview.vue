<script setup lang="ts">
const props = withDefaults(defineProps<{
  src: string | null | undefined
  alt: string
  size?: 'small' | 'large'
}>(), { size: 'large' })

const stage = ref<HTMLDivElement | null>(null)
const imageLoaded = ref(false)
const hovering = ref(false)
const listPreviewStyle = ref<Record<string, string>>({})
const naturalSize = ref({ width: 0, height: 0 })
const displayedSize = ref({ width: 0, height: 0 })
const imagePoint = ref({ x: 0, y: 0 })
const pointer = ref({ x: 0, y: 0 })
const lensSize = 210
const zoom = 2.5

watch(() => props.src, () => {
  imageLoaded.value = false
  hovering.value = false
  naturalSize.value = { width: 0, height: 0 }
})

function onImageLoad(event: Event) {
  const image = event.target as HTMLImageElement
  naturalSize.value = { width: image.naturalWidth, height: image.naturalHeight }
  imageLoaded.value = image.naturalWidth > 0 && image.naturalHeight > 0
}

function showPreview(event: PointerEvent) {
  if (event.pointerType === 'touch' || !props.src || !imageLoaded.value || !stage.value) return
  if (props.size === 'small') {
    const bounds = stage.value.getBoundingClientRect()
    const width = Math.min(420, window.innerWidth - 24)
    const height = Math.min(420, window.innerHeight - 24)
    const left = bounds.right + 16 + width <= window.innerWidth
      ? bounds.right + 16
      : bounds.left - 16 - width >= 0
        ? bounds.left - 16 - width
        : Math.max(12, Math.min(bounds.left, window.innerWidth - width - 12))
    const top = Math.max(12, Math.min(bounds.top - 24, window.innerHeight - height - 12))
    listPreviewStyle.value = { left: `${left}px`, top: `${top}px`, width: `${width}px`, height: `${height}px` }
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
  <div ref="stage" class="relative grid h-full w-full place-items-center overflow-hidden bg-white" @pointerenter="showPreview" @pointermove="showPreview" @pointerleave="hovering = false">
    <img v-if="src" :src="src" :alt="alt" class="h-full w-full object-contain" :loading="size === 'small' ? 'lazy' : 'eager'" @load="onImageLoad" @error="imageLoaded = false; hovering = false">
    <div v-else class="flex flex-col items-center gap-2 text-muted"><AppIcon name="cube" :size="size === 'small' ? '5' : '10'" /><span v-if="size === 'large'" class="text-xs">暂无商品图片</span></div>
  </div>
  <Teleport to="body">
    <div v-if="hovering && src && size === 'small'" class="pointer-events-none fixed z-[100] grid place-items-center overflow-hidden rounded-xl border border-line bg-white p-4 shadow-[0_18px_55px_rgba(35,29,24,0.22)]" :style="listPreviewStyle" aria-hidden="true">
      <img :src="src" alt="" class="h-full w-full object-contain">
    </div>
    <div v-if="hovering && src && size === 'large'" class="pointer-events-none fixed z-[100] overflow-hidden rounded-full border-[3px] border-white bg-white shadow-[0_12px_38px_rgba(35,29,24,0.24)]" :style="lensStyle" aria-hidden="true">
      <img :src="src" alt="" class="absolute max-w-none" :style="enlargedStyle">
    </div>
  </Teleport>
</template>
