<script setup lang="ts">
import type { FurnitureObject } from '~/types/office'

const props = defineProps<{ item: FurnitureObject | null; itemIds?: string[]; anchor?: HTMLElement | null }>()
const show = defineModel<boolean>('show', { default: false })
const emit = defineEmits<{ (event: 'applied', message: string): void }>()

const store = useOfficeStore()
const width = ref(0)
const depth = ref(0)
const error = ref('')
const position = ref<{ left: number; bottom: number } | null>(null)

watch([() => props.item, () => show.value], () => {
  if (props.item && show.value) {
    width.value = Math.round(props.item.w)
    depth.value = Math.round(props.item.h)
    error.value = ''
    nextTick(updatePosition)
  }
})

function updatePosition() {
  if (!props.anchor) { position.value = null; return }
  const rect = props.anchor.getBoundingClientRect()
  position.value = { left: rect.left, bottom: window.innerHeight - rect.top + 8 }
}

function apply() {
  if (!props.item) return
  error.value = ''
  const ids = props.itemIds?.length ? props.itemIds : [props.item.id]
  for (const id of ids) {
    const result = store.resizeFurniture(id, width.value, depth.value)
    if (result) { error.value = result; return }
  }
  show.value = false
  emit('applied', `已更新 ${ids.length} 个家具尺寸为 ${width.value}×${depth.value}mm`)
}

function onBackdropClick() {
  show.value = false
}

onMounted(() => { window.addEventListener('resize', updatePosition) })
onBeforeUnmount(() => { window.removeEventListener('resize', updatePosition) })
</script>

<template>
  <Teleport to="body">
    <div v-if="show" class="fixed inset-0 z-[95]" data-testid="furniture-size-dialog" @click.self="onBackdropClick">
      <section
        role="dialog"
        aria-modal="true"
        aria-label="修改尺寸"
        class="w-64 bg-white text-xs shadow-xl"
        :class="position ? 'fixed' : 'absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2'"
        :style="position ? { left: `${position.left}px`, bottom: `${position.bottom}px` } : undefined"
      >
        <header class="flex items-center justify-between border-b border-line px-3 py-2">
          <h2 class="text-[11px] font-bold">修改尺寸 · {{ item?.label || '家具' }}</h2>
          <button aria-label="关闭" class="px-1 py-0.5 text-muted" @click="show = false">✕</button>
        </header>
        <div class="p-3">
          <p v-if="error" role="alert" class="mb-2 rounded-none bg-red-50 px-2 py-1 text-[10px] text-red-700">{{ error }}</p>
          <div class="grid grid-cols-2 gap-2">
            <label class="text-[10px] text-muted">
              宽度 mm
              <input v-model.number="width" type="number" min="10" step="50" class="mt-1 w-full rounded-none border border-line bg-white px-2 py-1.5 text-[11px]">
            </label>
            <label class="text-[10px] text-muted">
              深度 mm
              <input v-model.number="depth" type="number" min="10" step="50" class="mt-1 w-full rounded-none border border-line bg-white px-2 py-1.5 text-[11px]">
            </label>
          </div>
          <p class="mt-1.5 text-[10px] text-muted">当前占地 {{ Math.round(item?.w || 0) }}×{{ Math.round(item?.h || 0) }}mm</p>
          <button class="mt-2 w-full rounded-none bg-bronze py-1.5 text-[11px] font-semibold text-white" @click="apply">应用尺寸</button>
        </div>
      </section>
    </div>
  </Teleport>
</template>
