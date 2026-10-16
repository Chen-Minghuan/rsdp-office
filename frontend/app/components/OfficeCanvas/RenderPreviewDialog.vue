<script setup lang="ts">
import type { RenderPreview, RenderPreviewRequest } from '~/types/renderPreview'
import { renderPreviewSnapshot, renderSnapshotKey, renderPreviewActive } from '~/utils/renderPreview'

const show = defineModel<boolean>('show', { default: false })
const dialogElement = ref<HTMLElement | null>(null)
const store = useOfficeStore()
const api = useRenderPreviewApi()
const zoneId = computed(() => store.designZoneId || '')
const snapshot = computed(() => renderPreviewSnapshot(store.objects, zoneId.value))
const currentKey = computed(() => renderSnapshotKey(snapshot.value))
const style = ref(store.project.style || '现代简约')
const view = ref<'ISOMETRIC' | 'INTERIOR'>('ISOMETRIC')
const instructions = ref('')
const jobs = ref<RenderPreview[]>([])
const selectedId = ref('')
const selected = computed(() => jobs.value.find(item => item.previewId === selectedId.value) || jobs.value[0])
const loading = ref(false), submitting = ref(false), error = ref(''), notice = ref('')
const ready = computed(() => snapshot.value?.zone.reviewUnit === 'mm' && (snapshot.value?.furniture.length || 0) > 0)
const activeCurrent = computed(() => jobs.value.some(item => renderPreviewActive(item.status) && renderSnapshotKey(item.snapshot) === currentKey.value))
const stale = (job: RenderPreview) => job.stale || renderSnapshotKey(job.snapshot) !== currentKey.value
const statusText = (status: string) => ({ SUBMITTING: '正在提交', PENDING: '排队中', RUNNING: '生成中', SUCCEEDED: '已生成', FAILED: '生成失败' }[status] || status)
const message = (failure: any) => failure?.data?.message || failure?.message || '请求失败，请重试'
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
let sequence = 0
let timer: ReturnType<typeof setTimeout> | undefined
// Persist an uncertain submission's ID across reopening/reload. Retrying it cannot create a second billed task.
const pendingStorageKey = () => 'rsdp-render-request:' + useOfficeApi().projectId + ':' + zoneId.value
let pending: RenderPreviewRequest | null = null

function remember(value: RenderPreviewRequest | null, key = pendingStorageKey()) {
  pending = value
  try { if (value) sessionStorage.setItem(key, JSON.stringify(value)); else sessionStorage.removeItem(key) } catch {}
}

function merge(job: RenderPreview) {
  if (!job?.previewId || !job.snapshot || job.purpose !== 'CONCEPT_PREVIEW') throw new Error('效果预览接口返回无效，请启动最新后端')
  jobs.value = [job, ...jobs.value.filter(item => item.previewId !== job.previewId)]
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.previewId.localeCompare(a.previewId)).slice(0, 30)
}

function schedule(ticket: number) {
  clearTimeout(timer)
  if (ticket === sequence && show.value && jobs.value.some(item => renderPreviewActive(item.status))) timer = setTimeout(() => poll(ticket), 3000)
}

async function poll(ticket = sequence) {
  if (ticket !== sequence || !show.value) return
  const active = jobs.value.filter(item => renderPreviewActive(item.status))
  const results = await Promise.allSettled(active.map(item => api.get(item.previewId)))
  if (ticket !== sequence || !show.value) return
  let failure = ''
  results.forEach(result => {
    if (result.status === 'fulfilled') { try { merge(result.value) } catch (problem) { failure = message(problem) } }
    else failure = message(result.reason)
  })
  error.value = failure
  // Stop automatic polling on failure; the user can query again without submitting another task.
  if (!failure) schedule(ticket)
}

async function load() {
  const ticket = ++sequence
  clearTimeout(timer); jobs.value = []; selectedId.value = ''; error.value = ''; notice.value = ''; loading.value = true
  try {
    const raw = sessionStorage.getItem(pendingStorageKey())
    pending = raw ? JSON.parse(raw) : null
  } catch { pending = null }
  try {
    const response = await api.list(zoneId.value)
    if (ticket !== sequence || !show.value) return
    if (!Array.isArray(response)) throw new Error('效果预览接口尚未就绪，请启动最新后端')
    response.forEach(merge)
    selectedId.value = jobs.value[0]?.previewId || ''
    await poll(ticket)
  } catch (failure) { if (ticket === sequence) error.value = message(failure) }
  finally { if (ticket === sequence) loading.value = false }
}

async function generate() {
  if (submitting.value || !ready.value || !style.value.trim() || activeCurrent.value) return
  const ticket = sequence, expectedKey = currentKey.value, storageKey = pendingStorageKey()
  const revision = store.canvasRevision
  const expectedSnapshot = JSON.parse(JSON.stringify(snapshot.value))
  const options = { zoneId: zoneId.value, canvasRevision: revision, expectedSnapshot, style: style.value.trim(), view: view.value, instructions: instructions.value.trim() }
  if (!pending || renderSnapshotKey({ ...pending, clientChangeId: '', canvasRevision: 0 }) !== renderSnapshotKey({ ...options, clientChangeId: '', canvasRevision: 0 })) {
    remember({ ...options, clientChangeId: crypto.randomUUID() })
  } else remember({ ...pending, canvasRevision: revision })
  const request = pending!
  submitting.value = true; error.value = ''; notice.value = ''
  try {
    // Flush a copy; backend compares both the revision and the selected room snapshot.
    await useOfficeApi().saveCanvas({ objects: JSON.parse(JSON.stringify(store.objects)), files: JSON.parse(JSON.stringify(store.files)),
      mode: store.canvasMode, designZoneId: store.designZoneId, revision, blank: store.blank })
    if (ticket !== sequence || expectedKey !== currentKey.value || !show.value) return
    const job = await api.create(request)
    try { sessionStorage.removeItem(storageKey) } catch {}
    if (ticket !== sequence || !show.value) return
    pending = null
    merge(job); selectedId.value = job.previewId
    if (expectedKey !== currentKey.value) notice.value = '布局已变化，本次结果将保留为历史预览。'
    schedule(ticket)
  } catch (failure) { if (ticket === sequence) error.value = message(failure) }
  finally { submitting.value = false }
}

async function download(job: RenderPreview) {
  error.value = ''
  try {
    const response = await fetch(api.imageUrl(job.imageUrl))
    if (!response.ok) throw new Error('图片下载失败，请重试')
    const url = URL.createObjectURL(await response.blob())
    const anchor = document.createElement('a')
    anchor.href = url; anchor.download = `${job.zoneName}-概念效果图-${job.previewId}.png`; anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (failure) { error.value = message(failure) }
}

watch([show, zoneId], ([open]) => {
  sequence++; clearTimeout(timer)
  if (open && zoneId.value) load()
}, { immediate: true })
watch(show, async open => { if (open) { await nextTick(); dialogElement.value?.focus() } })
function trapFocus(event: KeyboardEvent) {
  const controls = Array.from(dialogElement.value?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary') || [])
    .filter(element => element.getClientRects().length)
  const first = controls[0], last = controls[controls.length - 1]
  if (event.shiftKey && (document.activeElement === first || document.activeElement === dialogElement.value)) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
function handleEscape(event: KeyboardEvent) {
  if (show.value && event.key === 'Escape') { show.value = false; event.preventDefault() }
}
onMounted(() => window.addEventListener('keydown', handleEscape))
onBeforeUnmount(() => { sequence++; clearTimeout(timer); window.removeEventListener('keydown', handleEscape) })
</script>

<template>
  <Teleport to="body">
    <div v-if="show" class="render-backdrop" @click.self="show = false" @keydown.esc="show = false">
      <section ref="dialogElement" role="dialog" aria-modal="true" aria-labelledby="render-preview-title" tabindex="-1" class="render-dialog" data-testid="render-preview-dialog" @keydown.tab="trapFocus">
        <header><div><h2 id="render-preview-title">单空间效果预览</h2><p>{{ snapshot?.zone.name || '当前空间' }} · Qwen-Image-3.0 · 二维图片</p></div><button aria-label="关闭效果预览" @click="show = false">✕</button></header>
        <div class="render-content">
          <aside class="render-settings">
            <div class="render-concept">概念效果图：用于查看空间氛围，家具数量、位置和款式需对照排版核对。</div>
            <p v-if="snapshot?.zone.evidenceKind === 'DEMO'" class="render-hint">当前为演示空间，生成结果也保留演示来源。</p>
            <label>装修风格<input v-model="style" aria-label="效果图风格" maxlength="100" :disabled="submitting" placeholder="例如：现代简约，浅木色与暖白色"></label>
            <label>观察视角<select v-model="view" aria-label="效果图视角" :disabled="submitting"><option value="ISOMETRIC">45° 鸟瞰 · 查看整体布局</option><option value="INTERIOR">室内透视 · 查看空间氛围</option></select></label>
            <label>补充偏好<textarea v-model="instructions" aria-label="效果图补充偏好" maxlength="500" rows="3" :disabled="submitting" placeholder="例如：灰色地毯、柔和自然光"></textarea></label>
            <p class="render-hint">按当前空间轮廓和 {{ snapshot?.furniture.length || 0 }} 个家具模块生成，无需先绑定商品。未提供的层高、门窗和材质由模型作概念补充。</p>
            <button class="render-primary" :disabled="!ready || submitting || activeCurrent || !style.trim()" @click="generate">{{ submitting ? '正在保存并提交…' : activeCurrent ? '当前布局正在生成…' : '生成效果预览' }}</button>
            <p v-if="!ready" class="render-hint">请先校准当前空间，并摆放家具。</p>
            <p v-if="pending && !submitting" class="render-hint">上次提交结果可能未知。保持参数重试会查询同一生成请求；改动参数会创建新任务。</p>
            <div class="render-history-heading"><strong>生成记录</strong><button :disabled="loading || submitting" @click="load">刷新记录</button></div>
            <p v-if="loading" role="status" class="render-hint">正在读取记录…</p>
            <p v-else-if="!jobs.length" class="render-hint">尚无效果预览</p>
            <button v-for="job in jobs" :key="job.previewId" class="render-history-item" :class="{ selected: selected?.previewId === job.previewId }" @click="selectedId = job.previewId">
              <span>{{ job.view === 'ISOMETRIC' ? '鸟瞰' : '室内透视' }} · {{ statusText(job.status) }} <b v-if="stale(job)">历史布局</b></span><small>{{ job.style }} · {{ time(job.createdAt) }}</small>
            </button>
          </aside>
          <main class="render-result" :aria-busy="submitting || (selected && renderPreviewActive(selected.status))">
            <p v-if="error" role="alert" class="render-error">{{ error }} <button v-if="jobs.some(job => renderPreviewActive(job.status))" @click="poll()">重新查询任务</button></p>
            <p v-if="notice" role="status" class="render-hint">{{ notice }}</p>
            <template v-if="selected">
              <div class="render-result-heading"><strong>{{ selected.zoneName }} · {{ statusText(selected.status) }}</strong><span>{{ stale(selected) ? '布局已变化，需重新生成' : '对应当前布局' }}</span></div>
              <p v-if="stale(selected)" class="render-outdated" role="status">此图绑定生成时的布局快照，仅作为历史预览。</p>
              <img v-if="selected.status === 'SUCCEEDED' && selected.imageUrl" class="render-image" :src="api.imageUrl(selected.imageUrl)" :alt="selected.zoneName + '概念效果图'">
              <div v-else-if="renderPreviewActive(selected.status)" class="render-wait" role="status"><div class="render-spinner"></div><strong>{{ statusText(selected.status) }}，请稍候</strong><p>可关闭窗口继续排版，稍后打开查看。</p></div>
              <div v-else class="render-wait"><strong>本次生成未完成</strong><p>{{ selected.errorMessage || '请重试生成' }}</p><button class="render-primary" :disabled="submitting || !ready || activeCurrent" @click="generate">按当前布局重试生成</button></div>
              <div v-if="selected.status === 'SUCCEEDED'" class="render-actions"><span>AI 生成 · 概念预览 · {{ selected.model }}</span><button @click="download(selected)">下载图片</button></div>
              <details v-if="selected.referenceUrl" class="render-reference"><summary>查看生成时的布局参考 · {{ selected.snapshot.furniture.length }} 个模块</summary><img :src="api.imageUrl(selected.referenceUrl)" alt="生成时的空间与家具布局参考图"><p>保留原始毫米轮廓、家具占地和旋转；朝向同时通过家具清单提供给模型。</p></details>
            </template>
            <div v-else class="render-empty"><span>空间排版 → 效果预览</span><h3>看看这个空间的立体效果</h3><p>选择风格和视角，生成带有材质、透视和光影的二维效果图。</p></div>
          </main>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.render-backdrop{position:fixed;inset:0;z-index:90;background:#25231f85;display:flex;align-items:center;justify-content:center;padding:24px}.render-dialog{width:min(1240px,96vw);height:min(850px,94vh);display:flex;flex-direction:column;background:#fff;border-radius:18px;box-shadow:0 25px 80px #0003;color:#45413b;overflow:hidden}.render-dialog header{display:flex;justify-content:space-between;align-items:center;padding:18px 24px;border-bottom:1px solid #e9e3db}.render-dialog h2{font-size:19px;font-weight:700}.render-dialog header p{font-size:12px;color:#8a8073;margin-top:4px}.render-dialog header button{padding:8px 12px;font-size:18px}.render-content{display:flex;flex:1;min-height:0}.render-settings{width:300px;flex:none;border-right:1px solid #e9e3db;overflow:auto;padding:18px;display:flex;flex-direction:column;gap:14px}.render-concept{font-size:12px;line-height:1.7;background:#f7efe1;color:#866a39;padding:12px;border-radius:10px}.render-settings label{display:flex;flex-direction:column;gap:7px;font-size:12px;font-weight:600}.render-settings input,.render-settings select,.render-settings textarea{border:1px solid #ded8cf;border-radius:8px;padding:9px;font-size:12px;font-weight:400;background:white;width:100%}.render-hint{font-size:11px;line-height:1.8;color:#888075}.render-primary{border-radius:9px;background:#9c7047;color:white;padding:11px 14px;font-size:12px;font-weight:600}.render-primary:disabled{opacity:.5;cursor:not-allowed}.render-history-heading{display:flex;justify-content:space-between;font-size:12px;border-top:1px solid #e9e3db;padding-top:16px}.render-history-heading button,.render-actions button{color:#976c43;font-size:12px}.render-history-item{text-align:left;border:1px solid #e8e1d8;border-radius:8px;padding:10px;font-size:11px}.render-history-item.selected{border-color:#ae8254;background:#fcf7ef}.render-history-item small{display:block;color:#8a8073;margin-top:5px}.render-history-item b{font-size:10px;color:#b17529;margin-left:4px}.render-result{flex:1;min-width:0;overflow:auto;background:#faf8f5;padding:22px;display:flex;flex-direction:column;gap:14px}.render-result-heading{display:flex;justify-content:space-between;gap:10px;font-size:13px}.render-result-heading span{font-size:11px;color:#8a8073}.render-outdated{background:#fff0d9;color:#986f2d;padding:10px;border-radius:8px;font-size:12px}.render-error{background:#fff0ed;color:#a64c38;padding:12px;border-radius:8px;font-size:12px;line-height:1.7}.render-error button{text-decoration:underline;margin-left:8px}.render-image{width:100%;max-height:530px;object-fit:contain;background:#eee9e0;border-radius:12px}.render-wait,.render-empty{flex:1;min-height:280px;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:16px;text-align:center;padding:30px}.render-wait p,.render-empty p{font-size:12px;line-height:1.8;color:#8a8073}.render-empty span{font-size:11px;color:#a17d54;letter-spacing:2px}.render-empty h3{font-size:23px;font-weight:600}.render-spinner{width:34px;height:34px;border:3px solid #e8dfd1;border-top-color:#9c7047;border-radius:50%;animation:render-spin 1s linear infinite}.render-actions{display:flex;justify-content:space-between;align-items:center}.render-actions span{font-size:10px;color:#8a8073}.render-actions button{border:1px solid #d9c9b5;border-radius:8px;padding:7px 14px}.render-reference{font-size:11px;color:#82786b}.render-reference summary{cursor:pointer;padding:6px 0}.render-reference img{width:100%;max-height:320px;object-fit:contain;background:white;border-radius:8px}.render-reference p{margin-top:7px}@keyframes render-spin{to{transform:rotate(360deg)}}@media(max-width:760px){.render-backdrop{padding:8px}.render-content{flex-direction:column;overflow:auto}.render-settings{width:auto;border-right:0;border-bottom:1px solid #e9e3db;overflow:visible}.render-result{overflow:visible;min-height:400px}.render-result-heading{flex-direction:column}}
</style>
