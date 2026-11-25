<script setup lang="ts">
import type { RenderFurnitureSpacing, RenderPreview, RenderPreviewRequest } from '~/types/renderPreview'
import { renderPreviewSnapshot, renderSnapshotKey, renderPreviewActive } from '~/utils/renderPreview'
import { createUuid } from '~/utils/uuid'

const show = defineModel<boolean>('show', { default: false })
const props = defineProps<{ formal?: boolean; confirmationId?: string; initialStyle?: string; initialPreviewId?: string }>()
const dialogElement = ref<HTMLElement | null>(null)
const store = useOfficeStore()
const auth = useAuth()
const api = useRenderPreviewApi()
const zoneId = computed(() => store.designZoneId || '')
const snapshot = computed(() => renderPreviewSnapshot(store.objects, zoneId.value))
const selectedProductCount = computed(() => new Set(snapshot.value?.furniture.map(item => item.productId).filter(Boolean)).size)
const currentKey = computed(() => renderSnapshotKey(snapshot.value))
const style = ref('现代简约')
const instructions = ref('')
const route = useRoute()
const experimentEnabled = computed(() => route.query.renderExperiment === '1')
const experimentGroup = ref<'A' | 'B' | 'C' | 'D' | 'E'>('E')
const experimentSample = ref(1)
const experimentSeed = computed(() => 1100 + experimentSample.value)
function experimentRequestOptions() {
  if (!experimentEnabled.value) return {}
  return { promptMode: experimentGroup.value === 'A' || experimentGroup.value === 'E' ? 'TECHNICAL_V10' as const : 'CONCISE_V11' as const,
    referenceMode: experimentGroup.value === 'C' ? 'GUIDE_PRODUCT' as const : 'GUIDE_PLAN_PRODUCT' as const,
    promptExtend: experimentGroup.value === 'D' || experimentGroup.value === 'E', seed: experimentSeed.value, sampleIndex: experimentSample.value }
}
const jobs = ref<RenderPreview[]>([])
const selectedId = ref('')
const selected = computed(() => jobs.value.find(item => item.previewId === selectedId.value) || jobs.value[0])
const loading = ref(false), submitting = ref(false), error = ref(''), notice = ref('')
const ready = computed(() => snapshot.value?.zone.reviewUnit === 'mm' && (snapshot.value?.furniture.length || 0) > 0 && (!props.formal || !!props.confirmationId))
const measuredSpacing = ref<RenderFurnitureSpacing | null>(null)
const measuringSpacing = ref(false)
const spacingError = ref('')
let spacingRequestId = 0
watch([show, currentKey, () => store.workspaceId, () => props.confirmationId], () => {
  spacingRequestId++
  measuredSpacing.value = null
  measuringSpacing.value = false
  spacingError.value = ''
})
async function measureSpacing() {
  if (!ready.value || measuringSpacing.value) return
  const requestId = ++spacingRequestId
  const expectedKey = currentKey.value
  measuringSpacing.value = true
  spacingError.value = ''
  measuredSpacing.value = null
  try {
    const result = await api.layoutSpacing(zoneId.value, props.formal ? props.confirmationId : undefined)
    if (requestId !== spacingRequestId || expectedKey !== currentKey.value) return
    if (!result?.spacing) throw new Error('间距工具暂不可用，请刷新后重试')
    if (renderSnapshotKey(result.snapshot) !== expectedKey) throw new Error('当前排版与已保存版本不同，请保存后重新测量')
    measuredSpacing.value = result.spacing
  } catch (failure) {
    if (requestId === spacingRequestId) spacingError.value = message(failure)
  } finally {
    if (requestId === spacingRequestId) measuringSpacing.value = false
  }
}
const activeCurrent = computed(() => jobs.value.some(item => renderPreviewActive(item.status) && !stale(item)
  && (props.formal ? item.confirmationId === props.confirmationId : item.purpose === 'CONCEPT_PREVIEW')))
const stale = (job: RenderPreview) => job.stale || renderSnapshotKey(job.snapshot) !== currentKey.value
const statusText = (status: string) => ({ SUBMITTING: '正在提交', PENDING: '排队中', POLLING: '正在查询', RUNNING: '生成中', SUCCEEDED: '已生成', FAILED: '生成失败' }[status] || status)
const qualityStatus = (job: RenderPreview) => {
  const product = job.qualityAudit?.status || 'UNAVAILABLE'
  if (!job.productReferences?.spatialControl) return product
  const geometry = job.geometryAudit?.status || 'UNCERTAIN'
  if (product === 'FAIL' || geometry === 'FAIL') return 'FAIL'
  if (product === 'UNCERTAIN' || geometry === 'UNCERTAIN') return 'UNCERTAIN'
  if (product === 'UNAVAILABLE' || geometry === 'UNAVAILABLE') return 'UNAVAILABLE'
  return 'PASS'
}
const qualityText = (job: RenderPreview) => ({
  PASS: '布局与商品参考检查通过', FAIL: '布局或商品还原未通过',
  UNCERTAIN: '部分内容需要人工核对', UNAVAILABLE: '自动检查不可用，请人工核对',
}[qualityStatus(job)])
const clock = ref(Date.now())
let clockTimer: ReturnType<typeof setInterval> | undefined
const duration = (ms?: number) => {
  if (ms === undefined || ms < 0) return '未记录'
  const seconds = Math.round(ms / 1000)
  return seconds < 60 ? `${seconds}秒` : `${Math.floor(seconds / 60)}分${seconds % 60}秒`
}
const timingTitle = (job: RenderPreview) => {
  const active = renderPreviewActive(job.status)
  const elapsed = active && job.generationTiming?.requestStartedAt ? clock.value - job.generationTiming.requestStartedAt : job.generationTiming?.totalMs
  return active ? `已等待 ${duration(elapsed)}` : `本次耗时 ${duration(elapsed)}`
}
const timingDetail = (job: RenderPreview) => {
  const value = job.generationTiming
  if (!value) return ''
  const parts = []
  if (value.preparationMs !== undefined || value.submitMs !== undefined) parts.push(`准备与提交 ${duration((value.preparationMs || 0) + (value.submitMs || 0))}`)
  if (value.providerMs !== undefined) parts.push(`服务生成 ${duration(value.providerMs)}`)
  if (value.downloadMs !== undefined) parts.push(`图片就绪 ${duration(value.downloadMs)}`)
  return parts.join(' · ')
}
const percent = (value?: number) => value === undefined ? '未测得' : `${(value * 100).toFixed(1)}%`
const productAgentText = (job: RenderPreview) => {
  const value = job.productUnderstanding
  if (!value) return ''
  const counts = `新识别 ${value.generatedCount || 0} 款 · 缓存命中 ${value.cacheHits || 0} 款`
  return ({
    SUCCEEDED: `本次使用了商品理解智能体 · ${counts}`,
    PARTIAL: `部分商品使用理解智能体，其余按静态规则生成 · ${counts}`,
    UNAVAILABLE: '商品理解不可用，本次按静态规则生成',
    DISABLED: '商品理解智能体未启用，本次按静态规则生成',
    NOT_REQUIRED: '本次没有需要理解的已选商品',
  } as Record<string, string>)[value.status] || '商品理解状态未知，请人工核对'
}
const productAgentItemStatus = (status?: string) => ({
  GENERATED: '已完成商品理解', CACHE_HIT: '使用已缓存商品档案', INVALID_RESPONSE: '理解结果未通过校验，已静态降级',
  UNAVAILABLE: '理解不可用，已静态降级', SKIPPED: '超过本次理解数量，已静态降级', DISABLED: '静态规则',
} as Record<string, string>)[status || ''] || '未使用商品理解'
const imageCompositionText = (value?: string) => ({
  SINGLE_PRODUCT: '单品参考', PRODUCT_SET: '套组图片', MULTIPLE_PRODUCTS: '多商品图片',
  MULTI_VIEW: '同商品多视角', UNKNOWN: '图片构成未知',
} as Record<string, string>)[value || ''] || '旧版商品档案'
const message = (failure: any) => failure?.data?.message || failure?.message || '请求失败，请重试'
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
let sequence = 0
let timer: ReturnType<typeof setTimeout> | undefined
// Persist an uncertain submission's ID across reopening/reload. Retrying it cannot create a second billed task.
const pendingStorageKey = () => 'rsdp-render-request:' + auth.storageScope.value + ':' + store.workspaceId + ':' + zoneId.value + (props.formal ? ':formal' : '')
let pending: RenderPreviewRequest | null = null

function remember(value: RenderPreviewRequest | null, key = pendingStorageKey()) {
  pending = value
  try { if (value) sessionStorage.setItem(key, JSON.stringify(value)); else sessionStorage.removeItem(key) } catch {}
}

function merge(job: RenderPreview) {
  if (!job?.previewId || !job.snapshot || !['CONCEPT_PREVIEW', 'CONFIRMED_SCHEME'].includes(job.purpose)) throw new Error('效果预览接口返回无效，请启动最新后端')
  if (props.formal && job.purpose !== 'CONFIRMED_SCHEME') return
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
    selectedId.value = jobs.value.find(job => job.previewId === props.initialPreviewId)?.previewId || jobs.value[0]?.previewId || ''
    await poll(ticket)
  } catch (failure) { if (ticket === sequence) error.value = message(failure) }
  finally { if (ticket === sequence) loading.value = false }
}

async function generate() {
  if (submitting.value || !ready.value || !style.value.trim() || activeCurrent.value) return
  const ticket = sequence, expectedKey = currentKey.value, storageKey = pendingStorageKey()
  const revision = store.canvasRevision
  const expectedSnapshot = JSON.parse(JSON.stringify(snapshot.value))
  const options: Omit<RenderPreviewRequest, 'clientChangeId'> = { zoneId: zoneId.value, canvasRevision: revision, expectedSnapshot, style: style.value.trim(), view: 'ISOMETRIC', instructions: instructions.value.trim(), ...experimentRequestOptions(),
    ...(props.formal ? { confirmationId: props.confirmationId } : {}) }
  if (!pending || renderSnapshotKey({ ...pending, clientChangeId: '', canvasRevision: 0 }) !== renderSnapshotKey({ ...options, clientChangeId: '', canvasRevision: 0 })) {
    remember({ ...options, clientChangeId: createUuid() })
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
    anchor.href = url; anchor.download = `${job.zoneName}-${job.purpose === 'CONFIRMED_SCHEME' ? '方案效果图' : '概念效果图'}-${job.previewId}.png`; anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (failure) { error.value = message(failure) }
}

watch([show, zoneId, () => store.workspaceId, () => props.formal, () => props.confirmationId], ([open]) => {
  sequence++; clearTimeout(timer)
  if (open && zoneId.value) load()
}, { immediate: true })
watch(show, async open => {
  clearInterval(clockTimer)
  if (!open) return
  if (props.initialStyle) style.value = props.initialStyle
  clock.value = Date.now()
  clockTimer = setInterval(() => { clock.value = Date.now() }, 1000)
  await nextTick()
  dialogElement.value?.focus()
})
onUnmounted(() => clearInterval(clockTimer))
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
        <header><div><h2 id="render-preview-title">{{ props.formal ? '方案效果图' : '单空间效果预览' }}</h2><p>{{ snapshot?.zone.name || '当前空间' }} · Qwen-Image-3.0 · 二维图片</p></div><button aria-label="关闭效果预览" @click="show = false">✕</button></header>
        <div class="render-content">
          <aside class="render-settings">
            <div class="render-concept">{{ props.formal ? '生成绑定所确认的方案版本，请对照排版和商品核对图片。' : '概念效果图，用于查看空间氛围，家具数量、位置和款式需对照排版核对。' }}</div>
            <p v-if="snapshot?.zone.evidenceKind === 'DEMO'" class="render-hint">当前为演示空间，生成结果也保留演示来源。</p>
            <label>装修风格<input v-model="style" aria-label="效果图风格" maxlength="100" :disabled="submitting" placeholder="例如：现代简约，浅木色与暖白色"></label>
            <div class="render-view-source"><span>观察视角：45° 斜俯视 · 家具定位结构参考</span></div>
            <label>补充偏好<textarea v-model="instructions" aria-label="效果图补充偏好" maxlength="500" rows="3" :disabled="submitting" placeholder="例如：灰色地毯、柔和自然光"></textarea></label>
            <details v-if="experimentEnabled" class="render-experiment" data-testid="render-experiment-panel" open><summary>生图提示词 A/B 实验</summary><label>实验组<select v-model="experimentGroup" aria-label="生图实验组" :disabled="submitting"><option value="A">A · 当前完整技术版</option><option value="B">B · 精简视觉任务版</option><option value="C">C · 精简版且不发送平面图</option><option value="D">D · 精简版＋Direct改写</option><option value="E">E · 完整技术版＋Direct改写</option></select></label><label>样本序号<input v-model.number="experimentSample" aria-label="实验样本序号" type="number" min="1" max="999" :disabled="submitting"></label><p>Seed {{ experimentSeed }} · 每次点击只创建1次收费生图，不会自动批量生成。</p></details>
            <p v-if="experimentEnabled" class="render-hint">当前选择实验 {{ experimentGroup }}：{{ experimentGroup === 'A' || experimentGroup === 'E' ? '完整提示词' : '精简提示词' }}，{{ experimentGroup === 'C' ? '不发送平面图，但保留审核平面图' : '发送无编号平面图' }}，Prompt Extend {{ experimentGroup === 'D' || experimentGroup === 'E' ? 'direct' : 'false' }}。</p>
            <p v-else class="render-hint">按当前空间轮廓和 {{ snapshot?.furniture.length || 0 }} 个家具模块生成。结构图和平面图提供排布，商品图提供外观，模块净距由后端从已保存排版计算。</p>
            <p v-if="selectedProductCount" class="render-hint" data-testid="render-selected-products">已绑定 {{ selectedProductCount }} 款商品。候选图片单款最多 4 张；启用商品理解时，程序按识别结果选择 1–2 张，不可用时按静态规则参考外观。</p>
            <button class="rounded-lg border border-line px-3 py-2 text-xs disabled:opacity-50" :disabled="!ready || measuringSpacing" @click="measureSpacing">{{ measuringSpacing ? '正在测量…' : '测量已保存的家具间距' }}</button>
            <p v-if="spacingError" role="status" class="render-hint">{{ spacingError }}</p>
            <OfficeCanvasRenderFurnitureSpacing v-if="measuredSpacing" :spacing="measuredSpacing" data-testid="render-current-spacing" />
            <button class="render-primary" :disabled="!ready || submitting || activeCurrent || !style.trim()" @click="generate">{{ submitting ? '正在理解商品并提交…' : activeCurrent ? '当前布局正在生成…' : props.formal ? '生成方案效果图' : '生成效果预览' }}</button>
            <p v-if="!ready" class="render-hint">{{ props.formal && !props.confirmationId ? '请到项目清单确认当前楼层方案。' : '请先校准当前空间，并摆放家具。' }}</p>
            <p v-if="pending && !submitting" class="render-hint">上次提交结果可能未知。保持参数重试会查询同一生成请求；改动参数会创建新任务。</p>
            <div class="render-history-heading"><strong>生成记录</strong><button :disabled="loading || submitting" @click="load">刷新记录</button></div>
            <p v-if="loading" role="status" class="render-hint">正在读取记录…</p>
            <p v-else-if="!jobs.length" class="render-hint">尚无效果预览</p>
            <button v-for="job in jobs" :key="job.previewId" class="render-history-item" :class="{ selected: selected?.previewId === job.previewId }" @click="selectedId = job.previewId">
              <span>{{ job.sourceType === 'whiteModelView' ? '白模视角' : job.view === 'ISOMETRIC' ? '鸟瞰' : '室内透视' }} · {{ statusText(job.status) }} <b v-if="stale(job)">历史布局</b></span><small>{{ job.style }} · {{ time(job.createdAt) }}</small>
            </button>
          </aside>
          <main class="render-result" :aria-busy="submitting || (selected && renderPreviewActive(selected.status))">
            <p v-if="error" role="alert" class="render-error">{{ error }} <button v-if="jobs.some(job => renderPreviewActive(job.status))" @click="poll()">重新查询任务</button></p>
            <p v-if="notice" role="status" class="render-hint">{{ notice }}</p>
            <template v-if="selected">
              <div class="render-result-heading"><strong>{{ selected.zoneName }} · {{ statusText(selected.status) }}</strong><span>{{ stale(selected) ? '布局已变化，需重新生成' : '对应当前布局' }}</span></div>
              <p v-if="selected.generationExperiment" class="render-hint" data-testid="render-generation-experiment">实验 {{ selected.generationExperiment.sampleGroup }}<template v-if="selected.generationExperiment.sampleIndex">-{{ selected.generationExperiment.sampleIndex }}</template> · {{ selected.generationExperiment.promptMode }} · {{ selected.generationExperiment.referenceMode }} · Prompt Extend {{ selected.generationExperiment.promptExtend ? selected.generationExperiment.promptExtendMode : 'false' }} · Seed {{ selected.generationExperiment.seed ?? '默认' }}</p>
              <div v-if="selected.generationTiming" class="render-timing" data-testid="render-generation-timing"><strong>{{ timingTitle(selected) }}</strong><p v-if="timingDetail(selected)">{{ timingDetail(selected) }}</p><small v-if="selected.generationExperiment?.promptExtend">Direct改写包含在本次任务耗时中，当前无法从服务商单独拆出改写耗时。</small></div>
              <p v-if="selected.sourceType === 'whiteModelView'" class="render-hint" data-testid="render-source-type">结构参考：3D 白模视角截图（视角与布局以截图为准）</p>
              <p v-if="selected.snapshot.furniture.some(item => item.productId) && !selected.productImagesUsed" class="render-outdated" role="status">此任务未使用所选商品图片，请重新生成以参考商品外观。</p>
              <p v-if="selected.productImagesUsed" class="render-hint" data-testid="render-product-images-used">已使用 {{ selected.productReferences?.items.length || 0 }} 款所选商品、{{ selected.productReferences?.items.reduce((sum, item) => sum + (item.usedImageCount || 1), 0) || 0 }} 张图片作为外观参考。</p>
              <p v-if="productAgentText(selected)" class="render-agent" :class="'agent-' + (selected.productUnderstanding?.status || '').toLowerCase()" data-testid="render-product-agent">{{ productAgentText(selected) }}</p>
              <p v-for="item in selected.productReferences?.items.filter(item => item.warnings?.length)" :key="item.productId" class="render-hint">{{ item.name }}：{{ item.warnings?.join('；') }}</p>
              <div v-if="selected.qualityAudit" class="render-audit" :class="'audit-' + qualityStatus(selected).toLowerCase()" data-testid="render-quality-audit">
                <strong>{{ qualityText(selected) }}</strong>
                <p v-if="selected.qualityAudit.furnitureCount">家具数量：要求 {{ selected.qualityAudit.furnitureCount.expected }} 件<template v-if="selected.qualityAudit.furnitureCount.observed !== undefined">，识别到 {{ selected.qualityAudit.furnitureCount.observed }} 件</template>。</p>
                <p v-if="selected.qualityAudit.layout?.reason">{{ selected.qualityAudit.layout.reason }}</p>
                <p v-if="selected.qualityAudit.architecture?.reason">建筑：{{ selected.qualityAudit.architecture.reason }}</p>
                <p v-if="selected.qualityAudit.imageQuality?.reason">画面：{{ selected.qualityAudit.imageQuality.reason }}</p>
                <p v-for="item in selected.qualityAudit.items?.filter(item => item.reason)" :key="item.furnitureReferenceId">{{ item.furnitureReferenceId }}<template v-if="item.productReferenceId"> / {{ item.productReferenceId }}</template>：{{ item.reason }}</p>
                <p v-if="selected.qualityAudit.reason">{{ selected.qualityAudit.reason }}</p>
              </div>
              <div v-if="selected.geometryAudit" class="render-audit" :class="'audit-' + selected.geometryAudit.status.toLowerCase()" data-testid="render-geometry-audit">
                <strong>位置几何审核：{{ { PASS: '通过', FAIL: '未通过', UNCERTAIN: '需人工核对', UNAVAILABLE: '不可用' }[selected.geometryAudit.status] }}</strong>
                <p v-if="selected.geometryAudit.furnitureCount">几何检测家具：要求 {{ selected.geometryAudit.furnitureCount.expected }} 件，有效识别 {{ selected.geometryAudit.furnitureCount.observed }} 件。</p>
                <p v-if="selected.geometryAudit.composition?.reason">构图：{{ selected.geometryAudit.composition.reason }}</p>
                <p v-if="selected.geometryAudit.room?.iou !== undefined">房间轮廓 IoU：{{ percent(selected.geometryAudit.room.iou) }}</p>
                <p v-for="item in selected.geometryAudit.items" :key="item.referenceId">{{ item.referenceId }}<template v-if="item.productReferenceId"> / {{ item.productReferenceId }}</template>：{{ item.status }} · 中心偏移 {{ percent(item.centerErrorRatio) }} · 地面 IoU {{ percent(item.groundPolygonIou) }}<template v-if="item.reason">；{{ item.reason }}</template></p>
                <p v-if="selected.geometryAudit.reason">{{ selected.geometryAudit.reason }}</p>
              </div>
              <p v-if="stale(selected)" class="render-outdated" role="status">此图绑定生成时的布局快照，仅作为历史预览。</p>
              <img v-if="selected.status === 'SUCCEEDED' && selected.imageUrl" class="render-image" :src="api.imageUrl(selected.imageUrl)" :alt="selected.zoneName + (selected.purpose === 'CONFIRMED_SCHEME' ? '方案效果图' : '概念效果图')">
              <div v-else-if="renderPreviewActive(selected.status)" class="render-wait" role="status"><div class="render-spinner"></div><strong>{{ statusText(selected.status) }}，请稍候</strong><p>可关闭窗口继续排版，稍后打开查看。</p></div>
              <div v-else class="render-wait"><strong>本次生成未完成</strong><p>{{ selected.errorMessage || '请重试生成' }}</p><button class="render-primary" :disabled="submitting || !ready || activeCurrent" @click="generate">按当前布局重试生成</button></div>
              <div v-if="selected.status === 'SUCCEEDED'" class="render-actions"><span>AI 生成 · 概念预览 · {{ selected.model }}</span><button @click="download(selected)">下载图片</button></div>
              <details v-if="selected.productReferences?.furnitureBindings?.length" class="render-reference" data-testid="render-fp-bindings"><summary>查看本次 F/P 商品映射与地面坐标</summary><div class="render-hint"><template v-for="binding in selected.productReferences.furnitureBindings" :key="binding.referenceId"><p><strong>{{ binding.referenceId }} → {{ binding.productReferenceId || '未绑定商品' }}</strong>　{{ binding.type }} · {{ binding.location }}</p><p v-if="selected.productReferences.spatialControl?.furniture.find(item => item.F === binding.referenceId)" class="render-coordinate">世界中心 {{ selected.productReferences.spatialControl.furniture.find(item => item.F === binding.referenceId)?.worldCenterMm.join(', ') }} mm · 旋转 {{ binding.rotation }}° · 平面地面中心 {{ selected.productReferences.spatialControl.furniture.find(item => item.F === binding.referenceId)?.plan.groundCenter1000.join(', ') }} · 白模地面中心 {{ selected.productReferences.spatialControl.furniture.find(item => item.F === binding.referenceId)?.guide.groundCenter1000.join(', ') }}</p></template><p>坐标范围均为 0～1000；地面中心不是家具顶部或标签位置。编号不应出现在最终效果图中。</p></div></details>
              <details v-if="selected.productReferences?.spatialControl?.layoutSemantics?.length" class="render-reference" data-testid="render-layout-semantics"><summary>查看程序识别的排列特征</summary><div class="render-hint"><p v-for="(fact, index) in selected.productReferences.spatialControl.layoutSemantics" :key="index">{{ fact.description }}</p></div></details>
              <details v-if="selected.productReferences?.spatialControl?.furnitureSpacing" class="render-reference" data-testid="render-snapshot-spacing">
                <summary>查看本次生图使用的实测间距</summary>
                <OfficeCanvasRenderFurnitureSpacing :spacing="selected.productReferences.spatialControl.furnitureSpacing" />
              </details>
              <details v-if="selected.productReferences?.items.length" class="render-reference" data-testid="render-product-agent-details"><summary>查看商品理解与选图结果</summary><div v-for="item in selected.productReferences.items" :key="item.productId" class="render-hint"><strong>{{ item.name }} · {{ productAgentItemStatus(item.agentStatus) }}</strong><p>候选 {{ item.candidateImageCount || item.availableImageCount || 0 }} 张，实际使用 {{ item.usedImageCount || 1 }} 张；选中 {{ item.images?.map(image => image.viewId).join('、') || item.imageSha256.slice(0, 12) }}。</p><template v-for="view in item.visualProfile?.views || []" :key="item.productId + ':' + view.imageId"><p v-if="view.imageComposition">{{ view.imageId }}：{{ imageCompositionText(view.imageComposition) }}<template v-if="view.target?.description">；目标：{{ view.target.description }}</template></p><p v-if="view.requiresSubjectIsolation">原图含非目标商品，需主体隔离；{{ view.target?.bbox?.length === 4 ? '已提供目标区域。' : '没有可靠目标区域。' }}</p></template><p v-for="image in item.images?.filter(image => image.isolationMethod && image.isolationMethod !== 'NONE') || []" :key="item.productId + ':crop:' + image.viewId">{{ image.viewId }} 已使用目标框裁剪，原图与生成图摘要分别保存；裁剪不等于像素级分割，请人工核对。</p><p v-if="item.visualProfile?.appearancePrompt">{{ item.visualProfile.appearancePrompt }}</p><p v-if="item.visualProfile?.uncertainties?.length">不确定项：{{ item.visualProfile.uncertainties.join('；') }}</p><p v-if="item.visualProfile">档案 {{ item.visualProfile.schemaVersion }} · {{ item.visualProfile.agentModel }} · {{ item.visualProfile.cacheKey.slice(0, 12) }}</p></div></details>
              <details v-if="selected.productReferences?.sheetUrls.length" class="render-reference"><summary>查看生成时使用的图3 · 商品图片</summary><p class="render-hint"><strong v-for="item in selected.productReferences.items" :key="item.referenceId" style="margin-right:12px">{{ item.referenceId }} × {{ item.quantity || item.furnitureReferenceIds?.length || item.furnitureNumbers.length }}</strong></p><img v-for="url in selected.productReferences.sheetUrls" :key="url" :src="api.imageUrl(url)" alt="生成时所选商品外观参考板"><p>每款商品只展示一次，数量由 F/P 映射决定；商品板不负责家具数量和布局。</p></details>
              <details v-if="selected.layoutContext" class="render-reference" data-testid="render-layout-context"><summary>查看本次生成参考的空间与间距</summary><div class="render-hint">
                <p>空间 {{ selected.layoutContext.widthMm }} × {{ selected.layoutContext.depthMm }} mm · 净面积 {{ selected.layoutContext.netAreaM2.toFixed(2) }} ㎡</p>
                <p>主体 {{ selected.layoutContext.primaryCount }} 件 · {{ selected.layoutContext.rowCount }} 排 × {{ selected.layoutContext.columnCount }} 列（混合尺寸按实际位置）</p>
                <p>离墙净距：<span v-for="side in (['left','right','top','bottom'] as const)" :key="side">{{ {left:'左',right:'右',top:'上',bottom:'下'}[side] }} {{ selected.layoutContext.wallClearancesMm[side] ?? '未知' }} mm　</span></p>
                <p v-for="gap in selected.layoutContext.groupGaps" :key="gap.axis + ':' + gap.distanceMm">{{ gap.axis === 'x' ? '横向' : '纵向' }}组间净距 {{ gap.distanceMm }} mm · {{ gap.count }} 处</p>
                <p>{{ selected.layoutContext.sourceDrawing.status === 'AVAILABLE' ? '已关联原户型中的当前空间，采用当前编辑后的轮廓。' : '原户型来源未能可靠对应，采用当前空间轮廓。' }} {{ selected.layoutContext.openingsKnown ? `已按人工标注保留 ${selected.layoutContext.openings?.length || 0} 处门窗；高度为渲染示意。` : '门窗位置未有可靠数据，生成时不作为既有事实。' }}</p>
                <p v-if="selected.layoutContext.unresolvedOpeningIds?.length">另有 {{ selected.layoutContext.unresolvedOpeningIds.length }} 处门窗净空未能可靠贴墙，未在效果图中猜测位置。</p>
              </div></details>
              <details v-if="selected.layoutGuideUrl" class="render-reference"><summary>查看实际参与生图的图1 · 无编号45°空间参考</summary><img :src="api.imageUrl(selected.layoutGuideUrl)" alt="实际参与生图的无编号空间立体参考"><p>图1负责相机、透视和立体结构，不包含F编号，避免编号被复制到成品；最终主效果图只应呈现一个完整空间画面。</p></details>
              <details v-if="selected.planGuideUrl" class="render-reference" data-testid="render-plan-guide"><summary>查看实际参与生图的图2 · 无编号平面图</summary><img :src="api.imageUrl(selected.planGuideUrl)" alt="实际参与生图的无编号平面控制图"><p>图2与数值地面坐标共同约束两排直线、排列顺序、实际间距、旋转和头尾位置；它不负责最终相机，也不向生图模型展示F编号。</p></details><details v-if="selected.productReferences?.bindingGuideUrl" class="render-reference" data-testid="render-binding-guide"><summary>查看仅供审核的 F 编号45°参考</summary><img :src="api.imageUrl(selected.productReferences.bindingGuideUrl)" alt="仅供商品审核和人工追溯的带F编号空间参考"><p>此图不再作为生图输入，只供商品审核和人工核对F/P映射，避免生图模型把F编号及相邻家具复制到结果中。</p></details>
              <details v-if="selected.viewImageUrl" class="render-reference" data-testid="render-view-source-detail"><summary>查看生成时的视角截图</summary><img :src="api.imageUrl(selected.viewImageUrl)" alt="生成时使用的 3D 白模视角截图"><p>本图以该白模截图的视角、透视与家具布局为结构参考生成。</p></details>
              <details v-if="selected.referenceUrl && !selected.planGuideUrl" class="render-reference"><summary>查看历史任务的布局参考 · {{ selected.snapshot.furniture.length }} 个模块</summary><img :src="api.imageUrl(selected.referenceUrl)" alt="历史任务的空间与家具布局参考图"><p>旧版平面图仅供人工核对，未参与生图。</p></details>
            </template>
            <div v-else class="render-empty"><span>空间排版 → 效果预览</span><h3>看看这个空间的立体效果</h3><p>选择风格和视角，生成带有材质、透视和光影的二维效果图。</p></div>
          </main>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.render-backdrop{position:fixed;inset:0;z-index:95;background:#25231f85;display:flex;align-items:center;justify-content:center;padding:24px}.render-dialog{width:min(1240px,96vw);height:min(850px,94vh);display:flex;flex-direction:column;background:#fff;border-radius:18px;box-shadow:0 25px 80px #0003;color:#45413b;overflow:hidden}.render-dialog header{display:flex;justify-content:space-between;align-items:center;padding:18px 24px;border-bottom:1px solid #e9e3db}.render-dialog h2{font-size:19px;font-weight:700}.render-dialog header p{font-size:12px;color:#8a8073;margin-top:4px}.render-dialog header button{padding:8px 12px;font-size:18px}.render-content{display:flex;flex:1;min-height:0}.render-settings{width:300px;flex:none;border-right:1px solid #e9e3db;overflow:auto;padding:18px;display:flex;flex-direction:column;gap:14px}.render-concept{font-size:12px;line-height:1.7;background:#f7efe1;color:#866a39;padding:12px;border-radius:10px}.render-settings label{display:flex;flex-direction:column;gap:7px;font-size:12px;font-weight:600}.render-settings input,.render-settings select,.render-settings textarea{border:1px solid #ded8cf;border-radius:8px;padding:9px;font-size:12px;font-weight:400;background:white;width:100%}.render-hint{font-size:11px;line-height:1.8;color:#888075}.render-view-source{display:flex;flex-direction:column;gap:7px;font-size:12px;font-weight:600}.render-view-source span{font-size:12px}.render-view-source img{width:100%;max-height:140px;object-fit:contain;background:#f4f1ec;border:1px solid #ded8cf;border-radius:8px}.render-primary{border-radius:9px;background:#9c7047;color:white;padding:11px 14px;font-size:12px;font-weight:600}.render-primary:disabled{opacity:.5;cursor:not-allowed}.render-history-heading{display:flex;justify-content:space-between;font-size:12px;border-top:1px solid #e9e3db;padding-top:16px}.render-history-heading button,.render-actions button{color:#976c43;font-size:12px}.render-history-item{text-align:left;border:1px solid #e8e1d8;border-radius:8px;padding:10px;font-size:11px}.render-history-item.selected{border-color:#ae8254;background:#fcf7ef}.render-history-item small{display:block;color:#8a8073;margin-top:5px}.render-history-item b{font-size:10px;color:#b17529;margin-left:4px}.render-result{flex:1;min-width:0;overflow:auto;background:#faf8f5;padding:22px;display:flex;flex-direction:column;gap:14px}.render-result-heading{display:flex;justify-content:space-between;gap:10px;font-size:13px}.render-result-heading span{font-size:11px;color:#8a8073}.render-outdated{background:#fff0d9;color:#986f2d;padding:10px;border-radius:8px;font-size:12px}.render-error{background:#fff0ed;color:#a64c38;padding:12px;border-radius:8px;font-size:12px;line-height:1.7}.render-error button{text-decoration:underline;margin-left:8px}.render-image{width:100%;max-height:530px;object-fit:contain;background:#eee9e0;border-radius:12px}.render-wait,.render-empty{flex:1;min-height:280px;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:16px;text-align:center;padding:30px}.render-wait p,.render-empty p{font-size:12px;line-height:1.8;color:#8a8073}.render-empty span{font-size:11px;color:#a17d54;letter-spacing:2px}.render-empty h3{font-size:23px;font-weight:600}.render-spinner{width:34px;height:34px;border:3px solid #e8dfd1;border-top-color:#9c7047;border-radius:50%;animation:render-spin 1s linear infinite}.render-actions{display:flex;justify-content:space-between;align-items:center}.render-actions span{font-size:10px;color:#8a8073}.render-actions button{border:1px solid #d9c9b5;border-radius:8px;padding:7px 14px}.render-reference{font-size:11px;color:#82786b}.render-reference summary{cursor:pointer;padding:6px 0}.render-reference img{width:100%;max-height:320px;object-fit:contain;background:white;border-radius:8px}.render-reference p{margin-top:7px}@keyframes render-spin{to{transform:rotate(360deg)}}@media(max-width:760px){.render-backdrop{padding:8px}.render-content{flex-direction:column;overflow:auto}.render-settings{width:auto;border-right:0;border-bottom:1px solid #e9e3db;overflow:visible}.render-result{overflow:visible;min-height:400px}.render-result-heading{flex-direction:column}}
.render-agent{padding:10px 12px;border-radius:8px;font-size:12px;line-height:1.7;background:#edf4f6;color:#39616b}.agent-partial,.agent-unavailable{background:#fff0d9;color:#986f2d}.agent-disabled,.agent-not_required{background:#f3f0eb;color:#82786b}.render-audit{padding:11px 13px;border-radius:8px;font-size:12px;line-height:1.7}.render-audit p{margin-top:4px}.audit-pass{background:#edf6ed;color:#376b3d}.audit-fail{background:#fff0ed;color:#a64c38}.audit-uncertain,.audit-unavailable{background:#fff0d9;color:#986f2d}
.render-coordinate{padding-left:10px;color:#6d7478}.render-experiment{padding:10px 0;font-size:11px}.render-experiment summary{cursor:pointer;font-weight:600}.render-experiment p{color:#82786b;line-height:1.5}.render-timing{padding:8px 12px;border-radius:8px;background:#edf4f6;color:#39616b;font-size:12px}.render-timing p,.render-timing small{display:block;margin-top:3px}.render-hint strong{color:#62574b}
</style>
