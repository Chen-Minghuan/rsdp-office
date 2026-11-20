<script setup lang="ts">
import { sameOriginAssetUrl } from '~/utils/apiBase'
import { calculateProductImagePreview, productImagePreviewStyle } from '~/utils/productImagePreview'

const props = withDefaults(defineProps<{
  src: string | null | undefined
  alt: string
  size?: 'small' | 'large'
  enableLightbox?: boolean
  // 悬浮框放在缩略图左侧时，右边缘与缩略图左边缘的距离（用于与父容器对齐）
  leftGap?: number
  // 可选：将小图悬浮预览限制在指定容器内；未传时使用整个视口
  previewBoundarySelector?: string
}>(), { size: 'large', leftGap: 16, previewBoundarySelector: undefined })

// Stored product image URLs may be absolute and tied to another entry point.
const normalizedSrc = computed(() => props.src ? sameOriginAssetUrl(props.src) : props.src)

const slots = useSlots()
const hasPreviewSide = computed(() => Boolean(slots['preview-side']))
const stage = ref<HTMLDivElement | null>(null)
const imageLoaded = ref(false)
const hovering = ref(false)
const previewBounds = ref<DOMRect | null>(null)
const sideRef = ref<HTMLElement | null>(null)
const sideContentHeight = ref(0)
let sideObserver: ResizeObserver | undefined

// 数据栏内容由插槽传入、高度不可预知，实测后参与浮层高度计算
watch(sideRef, element => {
  sideObserver?.disconnect()
  if (element && typeof ResizeObserver !== 'undefined') {
    sideObserver = sideObserver || new ResizeObserver(entries => {
      for (const entry of entries) sideContentHeight.value = entry.contentRect.height
    })
    sideObserver.observe(element)
  }
})
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
onUnmounted(() => sideObserver?.disconnect())

function calculateListPreview(bounds: DOMRect) {
  const boundary = props.previewBoundarySelector && typeof document !== 'undefined'
    ? document.querySelector<HTMLElement>(props.previewBoundarySelector)?.getBoundingClientRect()
    : undefined
  return calculateProductImagePreview({
    anchor: bounds,
    boundary,
    viewportWidth: typeof window === 'undefined' ? 1200 : window.innerWidth,
    viewportHeight: typeof window === 'undefined' ? 800 : window.innerHeight,
    naturalWidth: naturalSize.value.width,
    naturalHeight: naturalSize.value.height,
    hasSide: hasPreviewSide.value,
    sideContentHeight: sideContentHeight.value,
    leftGap: props.leftGap,
  })
}
const listPreviewStyle = computed(() => previewBounds.value
  ? productImagePreviewStyle(calculateListPreview(previewBounds.value))
  : {})

function showPreview(event: PointerEvent) {
  if (event.pointerType === 'touch' || !props.src || !imageLoaded.value || !stage.value) return
  if (props.size === 'small') {
    previewBounds.value = stage.value.getBoundingClientRect()
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
  <div ref="stage" class="relative grid h-full w-full place-items-center overflow-hidden bg-white" :class="[enableLightbox && src && imageLoaded ? 'cursor-zoom-in' : '', hovering && size === 'small' && hasPreviewSide ? 'pip-soft-hover' : '']" @pointerenter="showPreview" @pointermove="showPreview" @pointerleave="hovering = false" @click="openLightbox">
    <img v-if="src" :src="normalizedSrc" :alt="alt" class="absolute inset-0 h-full w-full object-center object-contain" :loading="size === 'small' ? 'lazy' : 'eager'" @load="onImageLoad" @error="imageLoaded = false; hovering = false">
    <div v-else class="flex flex-col items-center gap-2 text-muted"><AppIcon name="cube" :size="size === 'small' ? '5' : '10'" /><span v-if="size === 'large'" class="text-xs">暂无商品图片</span></div>
  </div>
  <Teleport to="body">
    <div v-if="hovering && src && size === 'small'" class="pointer-events-none fixed z-[100] overflow-hidden bg-white" :class="hasPreviewSide ? 'pip-soft-float' : 'rounded-xl border border-line shadow-[0_18px_55px_rgba(35,29,24,0.22)]'" :style="listPreviewStyle" aria-hidden="true">
      <div v-if="hasPreviewSide" class="pip-scaled-content flex items-stretch gap-3.5 p-4">
        <div class="grid min-w-0 shrink-0 place-items-center" style="width:var(--pip-image-width)"><img :src="normalizedSrc" alt="" class="block h-full w-full object-contain"></div>
        <div class="pip-soft-side shrink-0 overflow-hidden p-3 text-left" style="width:var(--pip-side-width)"><div ref="sideRef"><slot name="preview-side" /></div></div>
      </div>
      <div v-else class="pip-scaled-content grid place-items-center p-3"><img :src="normalizedSrc" alt="" class="block h-full w-full object-contain"></div>
    </div>
    <div v-if="hovering && src && size === 'large'" class="pointer-events-none fixed z-[100] overflow-hidden rounded-full border-[3px] border-white bg-white shadow-[0_12px_38px_rgba(35,29,24,0.24)]" :style="lensStyle" aria-hidden="true">
      <img :src="normalizedSrc" alt="" class="absolute max-w-none" :style="enlargedStyle">
    </div>
    <div v-if="lightboxOpen && src" class="fixed inset-0 z-[120] flex items-center justify-center bg-black/75 p-4 sm:p-8" role="dialog" aria-modal="true" :aria-label="`${alt}完整预览`" @click.self="closeLightbox" @keydown="onKeydown">
      <div class="relative flex max-h-full max-w-full flex-col items-center gap-3">
        <button type="button" class="absolute right-2 top-2 z-10 rounded-full bg-black/60 px-3 py-1.5 text-sm font-medium text-white transition hover:bg-black/80" aria-label="关闭图片预览" @click="closeLightbox">关闭</button>
        <div class="grid max-h-[calc(100dvh-88px)] max-w-[calc(100vw-32px)] place-items-center overflow-hidden rounded-xl bg-white p-3 shadow-2xl sm:max-w-[calc(100vw-64px)]">
          <img :src="normalizedSrc" :alt="alt" class="block max-h-[calc(100dvh-112px)] max-w-[calc(100vw-56px)] object-contain sm:max-h-[calc(100dvh-128px)] sm:max-w-[calc(100vw-96px)]">
        </div>
        <p class="max-w-[min(720px,calc(100vw-32px))] truncate text-xs text-white/80">点击空白处或按 Esc 关闭</p>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
/* main.css 全局强制 * { border-radius:0 !important; box-shadow:none !important }，悬浮预览卡作为浮层例外 */
.pip-soft-float {
  border-radius: 10px !important;
  box-shadow: 0 24px 60px rgba(80, 64, 45, 0.14), 0 4px 14px rgba(80, 64, 45, 0.08) !important;
}
.pip-soft-side {
  border-radius: 8px !important;
  background: #faf7f1;
}
.pip-soft-hover {
  outline: 2px solid var(--color-bronze);
  outline-offset: -2px;
}
.pip-scaled-content {
  width: var(--pip-base-width);
  height: var(--pip-base-height);
  transform: scale(var(--pip-content-scale));
  transform-origin: left top;
}
</style>
