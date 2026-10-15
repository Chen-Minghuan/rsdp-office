<script setup lang="ts">
import type { CustomOfficeModule } from '~/types/officeModule'
import { furnitureCatalogTemplates, furnitureById } from '~/utils/manualFurniture'

const props = defineProps<{ module?: CustomOfficeModule | null; sourceId: string; widthMm: number; depthMm: number }>()
const emit = defineEmits<{ close: []; saved: [module: CustomOfficeModule] }>()
const store = useOfficeStore()
const name = ref(props.module?.name || '')
const glyphId = ref(props.module?.glyphTemplateId || props.sourceId)
const width = ref(props.module?.widthMm || props.widthMm)
const depth = ref(props.module?.depthMm || props.depthMm)
const height = ref<number | ''>(props.module?.heightMm ?? furnitureById.get(props.sourceId)?.heightMm ?? '')
const error = ref('')
const source = computed(() => furnitureById.get(glyphId.value))
watch([name, glyphId, width, depth, height], () => { error.value = '' })
watch(glyphId, id => {
  const template = furnitureById.get(id)
  if (template) { width.value = template.widthMm; depth.value = template.depthMm; height.value = template.heightMm ?? '' }
})
function save() {
  const result = store.saveCustomModule({ name: name.value, glyphTemplateId: glyphId.value,
    widthMm: width.value, depthMm: depth.value, ...(height.value === '' ? {} : { heightMm: height.value }) }, props.module?.id)
  if (typeof result === 'string') { error.value = result; return }
  emit('saved', result)
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50 bg-black/30 grid place-items-center p-4" @keydown.esc.stop.prevent="emit('close')">
      <section role="dialog" aria-modal="true" :aria-label="module ? '编辑单位模块' : '新增单位模块'" class="bg-white rounded-2xl shadow-xl w-full max-w-md max-h-[90vh] overflow-y-auto p-5">
        <div class="flex items-center justify-between mb-4"><h2 class="text-lg font-bold">{{ module ? '编辑单位模块' : '新增单位模块' }}</h2><button aria-label="关闭模块窗口" class="p-2" @click="emit('close')">×</button></div>
        <form novalidate class="flex flex-col gap-3" @submit.prevent="save">
          <label class="text-xs">模块名称<input v-model="name" autofocus maxlength="60" aria-label="自定义模块名称" class="block w-full mt-1 h-9 border border-line rounded-lg px-2"></label>
          <label class="text-xs">家具图例<select v-model="glyphId" aria-label="自定义模块图例" class="block w-full mt-1 h-9 border border-line rounded-lg px-2"><option v-for="template in furnitureCatalogTemplates" :key="template.id" :value="template.id">{{ template.category }} · {{ template.name }}</option></select></label>
          <div class="h-24 bg-[#faf9f7] rounded-lg p-2"><OfficeCanvasFurniturePreview :template-id="glyphId" :width-mm="Number(width) >= 10 ? Number(width) : source?.widthMm" :depth-mm="Number(depth) >= 10 ? Number(depth) : source?.depthMm" thumbnail /></div>
          <div class="grid grid-cols-2 gap-3">
            <label class="text-xs">宽度（毫米）<input v-model.number="width" aria-label="自定义模块宽度（毫米）" type="number" min="10" step="any" class="w-full mt-1 h-9 border border-line rounded-lg px-2"></label>
            <label class="text-xs">进深（毫米）<input v-model.number="depth" aria-label="自定义模块进深（毫米）" type="number" min="10" step="any" class="w-full mt-1 h-9 border border-line rounded-lg px-2"></label>
          </div>
          <label class="text-xs">高度（毫米，选填）<input v-model.number="height" aria-label="自定义模块高度（毫米）" type="number" min="1" step="any" class="w-full mt-1 h-9 border border-line rounded-lg px-2"></label>
          <p class="text-[11px] text-muted">自定义模块保存在当前浏览器。保存或修改目录不会改动已放置的家具。</p>
          <p v-if="error" role="alert" class="text-xs text-red-600">{{ error }}</p>
          <div class="flex justify-end gap-2 mt-1"><button type="button" class="px-4 py-2 border border-line rounded-lg text-xs" @click="emit('close')">取消</button><button type="submit" class="px-4 py-2 bg-bronze text-white rounded-lg text-xs">保存模块</button></div>
        </form>
      </section>
    </div>
  </Teleport>
</template>
