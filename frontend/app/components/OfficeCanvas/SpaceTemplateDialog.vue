<script setup lang="ts">
import SpaceTemplatePlan from './SpaceTemplatePlan.vue'
import type { SpaceTemplate, SpaceTemplateSummary, SpaceTemplateCreateRequest } from '~/types/spaceTemplate'
import { templateMatches, templateLayoutProblem } from '~/utils/spatial/spaceTemplate'
const emit = defineEmits<{ (event: 'close'): void; (event: 'layout', zoneId: string): void }>()
const store = useOfficeStore(), api = useSpaceTemplateApi()
const candidate = ref<SpaceTemplate | null>(store.spaceTemplateCurrent ? JSON.parse(JSON.stringify(store.spaceTemplate)) : store.captureSpaceTemplate())
const name = ref(candidate.value?.name || '')
const library = ref<SpaceTemplateSummary[]>([]), busy = ref(''), error = ref(''), notice = ref(''), selectedId = ref('')
const sameDraft = computed(() => !!candidate.value && templateMatches(candidate.value, store.zones, store.floorPlan))
const unsupported = computed(() => candidate.value ? templateLayoutProblem(candidate.value) : null)
const projectScope = () => (store.floorPlan?.analysisId || '') + ':' + (store.floorPlan?.analysisRevision || 1)
const dialog = ref<HTMLElement | null>(null)
const previousFocus = typeof document !== 'undefined' ? document.activeElement as HTMLElement : null
let generation = 0, active = true
let pendingSave: { signature: string; request: SpaceTemplateCreateRequest } | null = null
onBeforeUnmount(() => { active = false; generation++; previousFocus?.focus() })
onMounted(async () => {
  dialog.value?.focus()
  const started = generation
  try { const list = await api.list(); if (active && started === generation && Array.isArray(list)) library.value = list } catch { /* Current draft remains usable when the library is unavailable. */ }
})
function fresh() {
  if (busy.value) return
  candidate.value = store.captureSpaceTemplate(name.value || undefined)
  error.value = ''; notice.value = ''; pendingSave = null; selectedId.value = ''
}
async function load(id: string) {
  if (!id || busy.value) return
  const requestId = ++generation
  busy.value = 'loading'; error.value = ''; notice.value = ''
  try {
    const result = await api.get(id)
    if (!active || requestId !== generation) return
    if (!result?.scene?.spaces?.length) throw new Error('模板内容无效，请重新选择')
    candidate.value = { ...result, storage: 'SAVED' }; name.value = result.name; selectedId.value = ''; pendingSave = null
  } catch (failure) { if (active) error.value = failure instanceof Error ? failure.message : '模板读取失败' }
  finally { if (active && requestId === generation) busy.value = '' }
}
async function save() {
  const snapshot = candidate.value
  if (!snapshot || busy.value || !name.value.trim()) return
  const source = projectScope(), canvasRevision = store.canvasRevision, requestId = ++generation
  const body = { name: name.value.trim(), sourceCanvasRevision: snapshot.sourceCanvasRevision, sourceAnalysisRevision: snapshot.sourceAnalysisRevision ?? null, scene: JSON.parse(JSON.stringify(snapshot.scene)) }
  const signature = JSON.stringify(body)
  if (!pendingSave || pendingSave.signature !== signature) pendingSave = { signature, request: { ...body, clientChangeId: crypto.randomUUID() } }
  busy.value = 'saving'; error.value = ''; notice.value = ''
  try {
    const result = await api.create(pendingSave.request)
    if (!active || requestId !== generation) return
    if (!result?.templateId || !result.scene?.spaces?.length) throw new Error('模板没有成功保存，请重试')
    const saved: SpaceTemplate = { ...result, storage: 'SAVED' }
    library.value = [saved, ...library.value.filter(item => item.templateId !== saved.templateId)].slice(0, 50)
    candidate.value = saved; pendingSave = null
    if (source === projectScope() && canvasRevision === store.canvasRevision && sameDraft.value && store.attachSpaceTemplate(saved)) notice.value = '空间模板已保存，可用于后续布局'
    else notice.value = '模板快照已保存；当前草稿已变化，未覆盖草稿。可重新生成当前版本'
  } catch (failure) { if (active) error.value = failure instanceof Error ? failure.message : '模板保存失败，请重试' }
  finally { if (active && requestId === generation) busy.value = '' }
}
function useTemplate() {
  if (!candidate.value || busy.value || unsupported.value) return
  const zoneId = store.useSpaceTemplate(candidate.value)
  if (zoneId) { emit('layout', zoneId); emit('close') }
  else error.value = '当前模板无法用于排版，请检查比例和空间轮廓'
}
function download() {
  if (!candidate.value) return
  const blob = new Blob([JSON.stringify(candidate.value, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob), link = document.createElement('a')
  link.href = url; link.download = (candidate.value.name.replace(/[\\/:*?"<>|]/g, '_') || '空间模板') + '.json'; link.click(); URL.revokeObjectURL(url)
}
</script>
<template>
  <Teleport to="body">
    <div class="space-template-backdrop" @click.self="emit('close')">
      <section ref="dialog" tabindex="-1" class="space-template-dialog" role="dialog" aria-modal="true" aria-label="空间模板" @keydown.esc="emit('close')">
        <header><div><span class="template-kicker">SPACE TEMPLATE</span><h2>空间模板</h2><p>保留整层空间结构，为后续家具布局和排版提供基础。</p></div><button aria-label="关闭空间模板" @click="emit('close')">×</button></header>
        <div class="space-template-content">
          <main><div class="template-plan-caption"><strong>整层空间结构</strong><span>{{ candidate?.evidenceKind === 'DEMO' ? '演示模板' : '毫米空间模型' }}</span></div><SpaceTemplatePlan v-if="candidate" :scene="candidate.scene" :selected-id="selectedId" @select="selectedId = $event" /><div v-else class="template-empty">{{ store.layoutBlocker || '暂无可生成的空间' }}</div><p>色块表示空间用途，保持真实轮廓与相对位置。建筑图层未识别完整时，后续仍可补充。</p></main>
          <aside>
            <label>模板名称<input v-model="name" maxlength="120" aria-label="空间模板名称" :disabled="!!busy"></label>
            <div v-if="candidate" class="template-summary"><div><b>{{ candidate.spaceCount }}</b><span>空间</span></div><div><b>{{ candidate.areaM2.toFixed(1) }}</b><span>面积合计 m²</span></div></div>
            <p class="template-save-state" :class="{ saved: candidate?.storage === 'SAVED' }">{{ candidate?.storage === 'SAVED' ? '✓ 已保存的独立模板' : '当前为本地模板快照' }}</p>
            <p v-if="candidate && !sameDraft" class="template-warning">此模板与当前草稿不同。使用它将创建新的空间布局副本，原稿可撤销；已有方案清单保留供核对。</p>
            <button class="template-refresh" :disabled="!!busy || !store.canEnterLayout" @click="fresh">按当前空间重新生成</button>
            <label v-if="library.length">已保存模板<select aria-label="选择已保存空间模板" :disabled="!!busy" @change="load(($event.target as HTMLSelectElement).value)"><option value="">选择模板…</option><option v-for="item in library" :key="item.templateId" :value="item.templateId">{{ item.name }} · {{ item.spaceCount }} 个空间 · {{ item.createdAt.replace('T', ' ').slice(5, 16) }}</option></select></label>
            <div class="template-room-list"><button v-for="space in candidate?.scene.spaces || []" :key="space.spaceId" :class="{ selected: selectedId === space.spaceId }" @click="selectedId = space.spaceId"><strong>{{ space.name }}</strong><span>{{ space.derived.areaM2?.toFixed(2) }} m²</span></button></div>
            <p v-if="candidate?.scene.qualityIssues.length" class="template-warning">有 {{ candidate.scene.qualityIssues.length }} 处复核提示，可按需修改，不要求逐个确认。</p>
            <p v-if="unsupported" class="template-warning">{{ unsupported }}</p>
          </aside>
        </div>
        <div v-if="error" class="template-feedback error" role="alert">{{ error }}</div><div v-if="notice" class="template-feedback" role="status">{{ notice }}</div>
        <footer><button :disabled="!candidate" @click="download">导出模板 JSON</button><div><button :disabled="!!busy || !candidate || !name.trim()" @click="save">{{ busy === 'saving' ? '保存中…' : '保存空间模板' }}</button><button class="template-primary" :disabled="!!busy || !candidate || !!unsupported" @click="useTemplate">{{ sameDraft ? '基于模板进入排版 →' : '用此模板新建布局 →' }}</button></div></footer>
      </section>
    </div>
  </Teleport>
</template>
<style src="~/assets/css/space-template.css"></style>
