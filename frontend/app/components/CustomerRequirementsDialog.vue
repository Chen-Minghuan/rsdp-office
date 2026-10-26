<script setup lang="ts">
const emit = defineEmits<{ close: [] }>()
const workspace = useWorkspaceStore()
const value = ref(workspace.currentProject?.customerRequirements || '')
const saving = ref(false)
const saved = ref(false)
const error = ref('')

async function save() {
  saving.value = true
  saved.value = false
  error.value = ''
  try {
    await workspace.updateRequirements(value.value.trim())
    saved.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '客户需求保存失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50 grid place-items-center bg-black/30 p-4" @click.self="emit('close')">
      <form class="w-full max-w-xl rounded-2xl bg-white p-6 shadow-2xl" @submit.prevent="save">
        <div class="mb-5 flex items-start justify-between">
          <div><h2 class="text-lg font-bold">客户需求备注</h2><p class="mt-1 text-xs text-muted">{{ workspace.currentProjectName }} · 在整个设计流程中持续提醒</p></div>
          <button type="button" class="rounded-lg p-2 text-muted hover:bg-cream" aria-label="关闭客户需求" @click="emit('close')"><AppIcon name="close" /></button>
        </div>
        <textarea v-model="value" rows="8" maxlength="4000" class="w-full resize-none rounded-xl border border-line px-4 py-3 text-sm leading-6 outline-none focus:border-bronze" placeholder="填写客户需求与注意事项" />
        <p class="mt-3 rounded-xl bg-cream px-4 py-3 text-xs leading-5 text-bronze-dark">需求备注只作提醒，不会自动修改空间、家具或商品。修改后请复核既有推荐和方案。</p>
        <p v-if="saved" role="status" class="mt-3 text-xs text-green">已保存。既有推荐和方案请结合新需求重新复核。</p>
        <p v-if="error" role="alert" class="mt-3 text-xs text-red-700">{{ error }}</p>
        <div class="mt-5 flex justify-end gap-3">
          <button type="button" class="rounded-xl border border-line px-4 py-2 text-sm" @click="emit('close')">关闭</button>
          <button type="submit" class="rounded-xl bg-bronze px-4 py-2 text-sm font-semibold text-white disabled:opacity-50" :disabled="saving">{{ saving ? '保存中…' : '保存备注' }}</button>
        </div>
      </form>
    </div>
  </Teleport>
</template>
