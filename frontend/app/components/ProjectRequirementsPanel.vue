<script setup lang="ts">
const workspace = useWorkspaceStore()
const showRequirements = ref(false)
const editing = ref(false)
const viewMode = ref<'raw' | 'summary'>('raw')
const value = ref('')
const original = ref('')
const projectId = ref('')
const saving = ref(false)
const error = ref('')
const input = ref<HTMLTextAreaElement | null>(null)
const summaryPoints = ref<string[]>([])
const summarySourceRevision = ref<number | null>(null)
const summaryLoading = ref(false)
const summaryError = ref('')
const summaryRequestId = ref(0)
const summaryText = computed(() => summaryPoints.value.map((point, index) => `${index + 1}. ${point}`).join('\n\n'))
const rawText = ref<HTMLTextAreaElement | null>(null)
const summaryTextEl = ref<HTMLTextAreaElement | null>(null)

async function fitTextareas() {
  await nextTick()
  for (const el of [rawText.value, summaryTextEl.value]) {
    if (!el) continue
    el.style.height = 'auto'
    el.style.height = `${el.scrollHeight}px`
  }
}

onMounted(fitTextareas)
watch(() => [workspace.currentProject?.customerRequirements, summaryText.value, viewMode.value, editing.value], fitTextareas)

watch(() => [workspace.currentProjectId, workspace.currentProject?.requirementsRevision], () => {
  summaryRequestId.value++
  viewMode.value = 'raw'
  editing.value = false
  summaryPoints.value = []
  summarySourceRevision.value = null
  summaryLoading.value = false
  summaryError.value = ''
})

function startEdit() {
  if (!workspace.currentProject || saving.value) return
  summaryRequestId.value++
  summaryLoading.value = false
  error.value = ''
  viewMode.value = 'raw'
  projectId.value = workspace.currentProject.projectId
  value.value = workspace.currentProject.customerRequirements
  original.value = value.value
  editing.value = true
  nextTick(() => input.value?.focus())
}

function openRequirements() {
  summaryRequestId.value++
  summaryLoading.value = false
  viewMode.value = 'raw'
  summaryError.value = ''
  showRequirements.value = true
}

async function toggleSummary() {
  if (viewMode.value === 'summary') {
    viewMode.value = 'raw'
    return
  }
  const project = workspace.currentProject
  if (!project || summaryLoading.value) return
  if (summarySourceRevision.value === project.requirementsRevision && summaryPoints.value.length) {
    viewMode.value = 'summary'
    return
  }
  const projectId = project.projectId
  const revision = project.requirementsRevision
  const requestId = ++summaryRequestId.value
  summaryLoading.value = true
  summaryError.value = ''
  try {
    const summary = await useWorkspaceApi().summarizeRequirements(projectId, revision)
    const current = workspace.currentProject
    if (requestId !== summaryRequestId.value || !current || current.projectId !== projectId
      || current.requirementsRevision !== revision || summary.sourceRevision !== revision) return
    summaryPoints.value = summary.keyPoints
    summarySourceRevision.value = summary.sourceRevision
    viewMode.value = 'summary'
  } catch (cause) {
    if (requestId !== summaryRequestId.value) return
    viewMode.value = 'raw'
    summaryError.value = cause instanceof Error ? cause.message : 'AI总结失败，请稍后重试'
  } finally {
    if (requestId === summaryRequestId.value) summaryLoading.value = false
  }
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
  <div
    v-if="workspace.currentProject"
    class="w-full text-left"
    data-testid="project-requirements-panel"
    title="双击直接填写项目需求"
    @dblclick="viewMode === 'raw' && startEdit()"
  >
    <div class="flex items-center justify-between gap-2">
      <div class="flex items-center gap-2">
        <span class="h-6 w-1 rounded-full bg-bronze" aria-hidden="true" />
        <h2 class="text-sm font-bold text-ink">项目需求</h2>
      </div>
      <div class="flex gap-1.5">
        <button type="button" class="requirements-edit-button h-[26px] w-[52px] rounded-full border border-bronze bg-[#f0e7db] text-[10px] leading-none text-bronze-dark transition hover:bg-cream" @click="openRequirements" @dblclick.stop>编辑</button>
        <button
          type="button"
          role="switch"
          aria-label="总结"
          class="requirements-summary-switch"
          :aria-checked="viewMode === 'summary'"
          :aria-busy="summaryLoading"
          :disabled="summaryLoading"
          @click="toggleSummary"
          @dblclick.stop
        >
          <span class="requirements-summary-track" aria-hidden="true">
            <span data-testid="summary-switch-thumb" class="requirements-summary-thumb" />
          </span>
          <span class="requirements-summary-label" aria-hidden="true">{{ summaryLoading ? '生成中…' : '总结' }}</span>
        </button>
      </div>
    </div>
    <div class="relative mt-3 pb-2 [perspective:1600px]">
      <div class="requirements-card-backing absolute bottom-0 left-2 right-0 top-3 rounded-[24px] border border-line bg-[#f1efeb]" aria-hidden="true" />
      <div
        data-testid="requirements-flip-card"
        class="requirements-flip-card relative mr-3 h-[300px]"
        :class="{ 'is-summary': viewMode === 'summary' }"
        :data-view="viewMode"
      >
        <section
          data-testid="requirements-raw-face"
          class="requirements-face rounded-[24px] border border-[#aaa7a1] bg-[#fffefb] px-4 py-4 shadow-sm"
          :aria-hidden="viewMode !== 'raw'"
          :inert="viewMode !== 'raw'"
        >
          <template v-if="editing">
            <textarea ref="input" v-model="value" rows="9" maxlength="4000" class="w-full resize-y rounded-xl border border-bronze bg-white px-3 py-2 text-[11px] leading-5 outline-none" aria-label="双击填写客户需求" :disabled="saving" placeholder="填写项目需求与注意事项" @dblclick.stop @keydown.ctrl.enter.prevent="saveEdit" @keydown.meta.enter.prevent="saveEdit" @keydown.esc.prevent="cancelEdit" @blur="saveEdit" />
            <p class="mt-2 text-[9px] text-muted">Ctrl+Enter 保存 · Esc 取消</p>
          </template>
          <textarea v-else ref="rawText" :value="workspace.currentProject.customerRequirements" readonly class="min-h-[252px] w-full resize-none border-0 bg-transparent text-[10px] leading-5 text-muted outline-none" aria-label="项目需求原文" placeholder="暂无项目需求，双击填写" />
        </section>
        <section
          data-testid="requirements-summary-face"
          class="requirements-face requirements-summary-face rounded-[24px] border border-[#aaa7a1] bg-[#f1efeb] px-4 py-4 shadow-sm"
          :aria-hidden="viewMode !== 'summary'"
          :inert="viewMode !== 'summary'"
        >
          <p class="text-[9px] leading-4 text-[#4b4742]">以下内容为<b class="font-semibold text-bronze-dark">AI总结</b>需求要点：</p>
          <textarea ref="summaryTextEl" :value="summaryText" readonly class="mt-4 min-h-[220px] w-full resize-none border-0 bg-transparent text-[10px] leading-[1.65] text-[#302d29] outline-none" aria-label="AI总结关键点" />
          <p class="mt-5 text-[10px] text-muted">……</p>
        </section>
      </div>
    </div>
    <p v-if="summaryError" role="alert" class="mt-2 text-[10px] leading-4 text-red-700">{{ summaryError }}</p>
    <p v-if="error" role="alert" class="mt-1 text-[10px] leading-4 text-red-700">{{ error }}</p>
    <CustomerRequirementsDialog v-if="showRequirements" @close="showRequirements = false" />
  </div>
</template>

<style scoped>
.requirements-flip-card {
  transform-style: preserve-3d;
  transition: transform 900ms cubic-bezier(0.2, 0.75, 0.25, 1);
}
.requirements-flip-card.is-summary {
  transform: rotateY(180deg);
}
/* 全局 main.css 强制所有元素直角，本面板按需求局部恢复圆角 */
.requirements-edit-button {
  border-radius: 999px !important;
}
.requirements-card-backing {
  border-radius: 18px !important;
}
.requirements-face {
  position: absolute;
  inset: 0;
  overflow-y: auto;
  backface-visibility: hidden;
  -webkit-backface-visibility: hidden;
  border-radius: 18px !important;
}
.requirements-face textarea {
  overflow: hidden;
  scrollbar-width: none;
  border-radius: 12px !important;
}
.requirements-face textarea::-webkit-scrollbar {
  display: none;
}
.requirements-summary-face {
  transform: rotateY(180deg);
}
.requirements-summary-switch {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 26px;
  padding: 0;
  border-radius: 999px !important;
  color: var(--color-bronze-dark);
  font-size: 10px;
  line-height: 1;
}
.requirements-summary-switch:disabled {
  cursor: wait;
  opacity: 0.6;
}
.requirements-summary-track {
  position: absolute;
  inset: 0;
  border: 1px solid var(--color-bronze);
  border-radius: 999px !important;
  background: #f0e7db;
  transition: background 350ms ease;
}
.requirements-summary-thumb {
  position: absolute;
  top: 50%;
  left: 4px;
  width: 16px;
  height: 16px;
  border: 1px solid var(--color-bronze);
  border-radius: 50% !important;
  background: #fffefb;
  transform: translate(0, -50%);
  transition: transform 350ms cubic-bezier(0.4, 1.3, 0.5, 1), opacity 200ms;
}
.requirements-summary-label {
  position: absolute;
  top: 50%;
  right: 8px;
  z-index: 1;
  pointer-events: none;
  white-space: nowrap;
  transform: translateY(-50%);
  transition: color 300ms;
}
.requirements-summary-switch[aria-checked="true"] .requirements-summary-track {
  background: linear-gradient(135deg, #c49a74, var(--color-bronze));
}
.requirements-summary-switch[aria-checked="true"] .requirements-summary-thumb {
  transform: translate(40px, -50%);
}
.requirements-summary-switch[aria-checked="true"] .requirements-summary-label {
  right: auto;
  left: 8px;
  color: #fffefb;
}
.requirements-summary-switch[aria-busy="true"] .requirements-summary-thumb {
  opacity: 0;
}
.requirements-summary-switch[aria-busy="true"] .requirements-summary-label {
  left: 0;
  right: 0;
  text-align: center;
}
@media (prefers-reduced-motion: reduce) {
  .requirements-flip-card,
  .requirements-summary-track,
  .requirements-summary-thumb {
    transition: none;
  }
}
</style>
