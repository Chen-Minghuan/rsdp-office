<script setup lang="ts">
import type { ProjectReferenceImage } from '~/types/workspace'

const props = withDefaults(defineProps<{
  images: ProjectReferenceImage[]
  disabled?: boolean
  max?: number
  heading?: string
  hint?: string
  listMode?: 'grid' | 'names' | 'hidden'
}>(), { disabled: false, max: 10, heading: '参考图片', hint: '', listMode: 'grid' })

const emit = defineEmits<{ 'update:images': [ProjectReferenceImage[]] }>()

const officeApi = useOfficeApi()
const uploading = ref(false)
const error = ref('')
const fileInput = ref<HTMLInputElement | null>(null)

const uploadButtonClass = computed(() => props.listMode === 'names'
  ? 'inline-block text-left font-semibold text-accent-ink hover:underline disabled:opacity-50'
  : 'inline-flex items-center gap-2 rounded-xl border border-line px-4 py-2.5 text-sm font-semibold hover:bg-cream disabled:opacity-50')

function previewUrl(url: string) {
  return sameOriginAssetUrl(url)
}

async function pickFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  if (!files.length || props.disabled) return
  error.value = ''
  if (props.images.length + files.length > props.max) {
    error.value = `参考图片不能超过 ${props.max} 张`
    return
  }
  uploading.value = true
  try {
    const urls = await officeApi.uploadImages(files)
    const added = urls.map((url, index) => ({ url: toRelativeUploadPath(url), name: files[index]?.name?.replace(/\.[^.]+$/, '') || '' }))
    emit('update:images', [...props.images, ...added])
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '图片上传失败'
  } finally {
    uploading.value = false
  }
}

function removeImage(index: number) {
  if (props.disabled) return
  const next = [...props.images]
  next.splice(index, 1)
  emit('update:images', next)
}
</script>

<template>
  <section class="grid gap-3" data-testid="reference-images-editor">
    <div>
      <h3 class="text-sm font-semibold">{{ heading }}</h3>
    </div>
    <div v-if="images.length && listMode === 'grid'" class="grid grid-cols-3 gap-3">
      <figure v-for="(image, index) in images" :key="image.url" data-testid="reference-image"
        class="relative overflow-hidden rounded-xl border border-line bg-bg">
        <img :src="previewUrl(image.url)" :alt="image.name || '项目参考图片'" class="h-24 w-full object-cover">
        <figcaption class="truncate px-2 py-1 text-xs text-muted">{{ image.name || '参考图片' }}</figcaption>
        <button v-if="!disabled" type="button" :aria-label="`删除参考图片 ${index + 1}`" data-testid="remove-reference-image"
          class="absolute right-1 top-1 rounded-lg bg-white/90 p-1 text-muted hover:text-red-600"
          @click="removeImage(index)"><AppIcon name="trash" size="4" /></button>
      </figure>
    </div>
    <div v-if="!disabled">
      <button type="button" :class="uploadButtonClass"
        :disabled="uploading" data-testid="upload-reference-image" @click="fileInput?.click()">
        <AppIcon v-if="listMode !== 'names'" name="photo" size="4" />{{ uploading ? '上传中…' : '上传参考图片' }}
      </button>
      <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" multiple class="hidden" @change="pickFiles">
    </div>
    <ul v-if="images.length && listMode === 'names'" data-testid="reference-image-names"
      class="grid gap-1 text-sm">
      <li v-for="(image, index) in images" :key="image.url" class="flex min-w-0 items-center justify-between gap-2">
        <span class="truncate">{{ image.name || '参考图片' }}</span>
        <button v-if="!disabled" type="button" :aria-label="`删除参考图片 ${index + 1}`" data-testid="remove-reference-image"
          class="shrink-0 p-1 text-muted hover:text-red-600"
          @click="removeImage(index)"><AppIcon name="trash" size="4" /></button>
      </li>
    </ul>
    <p v-if="error" role="alert" class="text-xs text-red-700">{{ error }}</p>
  </section>
</template>
