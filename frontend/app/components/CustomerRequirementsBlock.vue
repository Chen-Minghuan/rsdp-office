<script setup lang="ts">
/** 客户需求备注块：双击就地编辑（Ctrl+Enter 保存 / Esc 取消），或点「编辑」打开完整对话框。 */
const workspace = useWorkspaceStore()
const showRequirements = ref(false)
const editing = ref(false)
const value = ref('')
const original = ref('')
const projectId = ref('')
const saving = ref(false)
const error = ref('')
const input = ref<HTMLTextAreaElement | null>(null)

function startEdit() {
  if (!workspace.currentProject || saving.value) return
  error.value = ''
  projectId.value = workspace.currentProject.projectId
  value.value = workspace.currentProject.customerRequirements
  original.value = value.value
  editing.value = true
  nextTick(() => input.value?.focus())
}

function cancelEdit() {
  if (!saving.value) editing.value = false
}

async function saveEdit() {
  if (!editing.value || saving.value) return
  const trimmed = value.value.trim()
  if (trimmed === original.value.trim()) { editing.value = false; return }
  saving.value = true
  error.value = ''
  try {
    await workspace.updateRequirements(trimmed, projectId.value)
    editing.value = false
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '客户需求保存失败'
    nextTick(() => input.value?.focus())
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-if="workspace.currentProject" class="w-full text-left" title="双击直接填写客户需求" @dblclick="startEdit">
    <div class="flex items-center justify-between text-[11px] font-semibold"><span>客户需求</span><button class="rounded-none px-1 text-bronze-dark hover:bg-white" @click="showRequirements = true" @dblclick.stop>编辑</button></div>
    <textarea v-if="editing" ref="input" v-model="value" rows="4" maxlength="4000" class="mt-1.5 w-full resize-y rounded-none border border-bronze bg-white px-2 py-1.5 text-[11px] leading-5" aria-label="双击填写客户需求" :disabled="saving" placeholder="填写客户需求与注意事项" @dblclick.stop @keydown.ctrl.enter.prevent="saveEdit" @keydown.meta.enter.prevent="saveEdit" @keydown.esc.prevent="cancelEdit" @blur="saveEdit" />
    <template v-if="editing"><p class="mt-1 text-[9px] text-muted">Ctrl+Enter 保存 · Esc 取消</p></template>
    <p v-else class="mt-1 line-clamp-2 text-[10px] leading-4 text-muted">{{ workspace.currentProject.customerRequirements || '暂无需求备注，双击填写' }}</p>
    <p v-if="error" role="alert" class="mt-1 text-[10px] leading-4 text-red-700">{{ error }}</p>
    <CustomerRequirementsDialog v-if="showRequirements" @close="showRequirements = false" />
  </div>
</template>
