<script setup lang="ts">
import type { RenderPreview } from '~/types/renderPreview'
import type { ProjectBudget, WorkspaceProject } from '~/types/workspace'
import { sameOriginAssetUrl } from '~/utils/apiBase'
import { drawingMatchesPlan } from '~/utils/drawingContext'
import { projectDrawingRoute, projectOverviewRoute } from '~/utils/projectBenchRoutes'
import { ringProblem, zoneMeasurements } from '~/utils/spatial/review'

const props = defineProps<{ project: WorkspaceProject; floorId: string }>()
const store = useOfficeStore()
const showRequirements = ref(false)
const selectedPreviewZone = ref('')
const previews = ref<RenderPreview[]>([])
const previewsLoading = ref(false)
const previewsFailed = ref(false)
const retryPreviews = ref(0)
const loadedBudget = ref<ProjectBudget>()
const { storageScope } = useAuth()
const budgetApi = useWorkspaceApi()

watch([() => props.project.projectId, storageScope], async ([projectId], _, onCleanup) => {
  let active = true
  onCleanup(() => { active = false })
  loadedBudget.value = undefined
  if (props.project.budgetTotal != null) return
  try {
    const totals = await budgetApi.listProjectBudgets()
    if (active) loadedBudget.value = totals?.[projectId]
  } catch { /* The budget is optional in the project overview. */ }
}, { immediate: true })

const floors = computed(() => [...props.project.floors].sort((a, b) => a.sortOrder - b.sortOrder))
const cover = computed(() => props.project.referenceImages[0])

const showProposalDate = ref(false)
const proposalTargetDate = ref('')
const proposalDateStorageKey = (projectId: string) => `rsdp-office:proposal-target-date:${projectId}`

watch(() => props.project.projectId, (projectId) => {
  const stored = localStorage.getItem(proposalDateStorageKey(projectId)) ?? ''
  proposalTargetDate.value = /^\d{4}-\d{2}-\d{2}$/.test(stored) ? stored : ''
}, { immediate: true })

function onProposalDateSave(date: string) {
  proposalTargetDate.value = date
  if (date) localStorage.setItem(proposalDateStorageKey(props.project.projectId), date)
  else localStorage.removeItem(proposalDateStorageKey(props.project.projectId))
  showProposalDate.value = false
}

const todayStamp = ref(new Date().toDateString())
function refreshToday() { todayStamp.value = new Date().toDateString() }
let todayTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  todayTimer = setInterval(refreshToday, 60_000)
  document.addEventListener('visibilitychange', refreshToday)
})
onBeforeUnmount(() => {
  clearInterval(todayTimer)
  document.removeEventListener('visibilitychange', refreshToday)
})

const proposalDays = computed(() => {
  todayStamp.value // 跨日后触发重新计算
  if (!proposalTargetDate.value) return null
  const target = new Date(`${proposalTargetDate.value}T00:00:00`)
  if (Number.isNaN(target.getTime())) return null
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return Math.max(0, Math.round((target.getTime() - today.getTime()) / 86400000))
})

const memberName = (member: { displayName: string; username: string } | null) => member ? member.displayName || member.username : ''
const teamText = computed(() => {
  const leader = memberName(props.project.leader)
  const collaborators = props.project.collaborators.map(memberName).filter(Boolean)
  if (leader && collaborators.length) return `${leader} | ${collaborators.join('；')}`
  return leader || collaborators.join('；') || '暂无团队成员'
})
const budget = computed(() => {
  const { budgetTotal, budgetStatus } = loadedBudget.value ?? props.project
  if (budgetTotal == null || budgetStatus === 'UNAVAILABLE') return '—'
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', minimumFractionDigits: 0 }).format(budgetTotal)
})
const drawing = computed(() => store.files[0])
const drawingStatus = computed(() => {
  if (drawing.value?.mock) return '演示图纸'
  if (store.floorPlan?.status === 'failed' && drawingMatchesPlan(drawing.value, store.floorPlan)) return '识别失败'
  if (drawingMatchesPlan(drawing.value, store.floorPlan) && store.zones.length) return '空间已导入'
  return drawing.value?.analysisId ? '已提交识别' : '待识别'
})

const zoneRows = computed(() => {
  const mismatches = new Map(store.proposalFurnitureCartMismatches.map(item => [item.zoneId, item]))
  return store.zones.map(zone => {
    const modules = store.furnitureForZone(zone.id)
    const missing = mismatches.get(zone.id)?.missing.reduce((total, item) => total + item.quantity, 0) ?? 0
    const area = zone.polygon && !ringProblem(zone.polygon) ? zoneMeasurements(zone).area : null
    return {
      id: zone.id,
      name: zone.name,
      demo: zone.evidenceKind === 'DEMO',
      area: area === null ? (zone.reviewUnit === 'mm' ? '待修正' : '待校准') : `${area.toFixed(1)} m²`,
      selectedCount: modules.length - missing,
      moduleCount: modules.length,
      review: zone.reviewConfirmation?.status,
      layoutConfirmed: store.layoutConfirmedForZone(zone.id),
    }
  })
})

const previewZones = computed(() => zoneRows.value.filter(zone => !zone.demo))
const previewZoneId = computed(() => previewZones.value.find(zone => zone.id === selectedPreviewZone.value)?.id
  ?? previewZones.value[0]?.id ?? '')
const previewTiles = computed(() => {
  const { imageUrl } = useRenderPreviewApi(props.floorId)
  return previews.value.flatMap(preview => {
    const url = imageUrl(preview.imageUrl)
    const label = preview.purpose === 'CONFIRMED_SCHEME' ? '方案效果图' : '概念效果图'
    return preview.status === 'SUCCEEDED' && url ? [{ ...preview, label, url }] : []
  }).sort((left, right) => {
    const rank = (preview: RenderPreview) => preview.stale ? 2 : preview.purpose === 'CONFIRMED_SCHEME' ? 0 : 1
    return rank(left) - rank(right)
  }).slice(0, 4)
})

watch([() => props.floorId, previewZoneId, retryPreviews], async ([floorId, zoneId], _, onCleanup) => {
  let active = true
  onCleanup(() => { active = false })
  previews.value = []
  previewsFailed.value = false
  previewsLoading.value = false
  if (!zoneId) return

  previewsLoading.value = true
  try {
    const items = await useRenderPreviewApi(floorId).list(zoneId)
    if (active) previews.value = Array.isArray(items) ? items : []
  } catch {
    if (active) previewsFailed.value = true
  } finally {
    if (active) previewsLoading.value = false
  }
}, { immediate: true })

function roomRoute(zoneId: string) {
  return {
    path: '/canvas',
    query: { floorId: props.floorId, zoneId, ...(!store.layoutEntryBlocker ? { mode: 'design', view: 'products' } : {}) },
  }
}
</script>

<template>
  <div class="grid gap-5 text-neutral-900" data-testid="project-bench-overview">
    <section class="overview-hero grid items-stretch gap-4 border-y border-neutral-200 py-4" aria-label="项目概况">
      <figure v-if="cover" class="m-0 min-w-0">
        <img :src="sameOriginAssetUrl(cover.url)" :alt="cover.name || '项目参考图'" class="aspect-[1.9] w-full border border-neutral-200 object-cover">
      </figure>
      <div v-else class="grid min-h-25 place-items-center bg-neutral-100 px-3 text-sm text-neutral-600">暂无项目参考图</div>
      <div class="flex min-w-0 flex-col justify-between gap-3">
        <h2 class="wrap-break-word text-xl font-bold leading-tight xl:text-2xl">{{ project.projectName }}</h2>
        <p class="text-sm text-neutral-600">{{ floors.length }} 个楼层</p>
      </div>
      <div class="min-w-0">
        <p class="wrap-break-word text-[clamp(1.65rem,3.4vw,3.75rem)] font-bold leading-none tracking-tight tabular-nums" data-testid="project-reference-budget">{{ budget }}</p>
        <p class="mt-2 text-sm font-medium">商品参考金额</p>
        <p v-if="(loadedBudget ?? project).budgetStatus === 'PENDING_PRICING'" class="mt-1 text-xs text-amber-800">部分商品待补价</p>
        <p v-else-if="budget === '—'" class="mt-1 text-xs text-neutral-600">暂无可用价格</p>
      </div>
      <div class="min-w-0 text-sm">
        <p class="flex items-end gap-2">
          <span class="grid text-xs leading-tight text-neutral-600"><span>距离</span><span>汇报提案</span></span>
          <span class="text-[clamp(1.65rem,3vw,3rem)] font-bold leading-none tracking-tight tabular-nums" data-testid="proposal-countdown">{{ proposalDays ?? '—' }}</span>
          <span v-if="proposalDays !== null" class="font-semibold">天</span>
          <button type="button" aria-label="设置汇报提案日期" class="ml-auto rounded-lg p-2 text-neutral-600 hover:bg-accent-tint"
            @click="showProposalDate = true"><AppIcon name="settings" /></button>
        </p>
        <p v-if="proposalDays === null" class="mt-1 text-xs text-neutral-600">点击右侧按钮设置提案日期</p>
        <h3 class="mb-2 mt-3 font-semibold">项目团队</h3>
        <p class="wrap-break-word text-xs text-neutral-600" data-testid="project-team">{{ teamText }}</p>
      </div>
    </section>

    <section aria-labelledby="planning-heading">
      <div class="flex flex-wrap items-center gap-x-6 gap-y-2 border-b border-neutral-300 pb-3">
        <h2 id="planning-heading" class="text-xl font-semibold">空间规划</h2>
        <nav class="flex max-w-full flex-wrap gap-2" aria-label="项目总览楼层">
          <NuxtLink v-for="floor in floors" :key="floor.floorId" :to="projectOverviewRoute(floor.floorId)"
            :aria-current="floor.floorId === floorId ? 'page' : undefined"
            class="floor-link px-3 py-1.5 text-sm font-semibold"
          >{{ floor.floorName }}</NuxtLink>
        </nav>
      </div>

      <div class="overview-content grid min-w-0 gap-5 pt-4">
        <div class="min-w-0 space-y-5">
          <section v-if="previewZones.length" aria-label="空间效果图">
            <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
              <h3 class="font-semibold">空间效果图</h3>
              <select :value="previewZoneId" aria-label="效果图空间" class="max-w-full border border-neutral-300 bg-white px-2 py-1 text-sm"
                @change="selectedPreviewZone = ($event.target as HTMLSelectElement).value">
                <option v-for="zone in previewZones" :key="zone.id" :value="zone.id">{{ zone.name }}</option>
              </select>
            </div>
            <p v-if="previewsLoading" role="status" class="preview-message">正在读取效果图…</p>
            <div v-else-if="previewsFailed" class="preview-message">
              <p role="alert">暂时无法读取效果图。</p>
              <button type="button" class="text-accent-ink underline" @click="retryPreviews++">重试</button>
            </div>
            <div v-else-if="previewTiles.length" class="grid grid-cols-2 gap-3 xl:grid-cols-4">
              <a v-for="preview in previewTiles" :key="preview.previewId" :href="preview.url" target="_blank" rel="noopener noreferrer" class="grid min-w-0 gap-1 text-sm hover:underline">
                <img :src="preview.url" :alt="`${preview.zoneName}${preview.label}`" loading="lazy" class="aspect-[1.45] w-full bg-neutral-100 object-cover">
                <span class="wrap-break-word">{{ preview.label }}<template v-if="preview.style"> · {{ preview.style }}</template><template v-if="preview.stale"> · 历史版本</template></span>
              </a>
            </div>
            <p v-else class="preview-message">这个空间暂无已完成的效果图。</p>
          </section>

          <div v-if="zoneRows.length" class="overflow-x-auto">
            <table class="w-full min-w-[29rem] border-collapse text-left text-sm" aria-label="当前楼层房间进度">
              <thead class="text-neutral-600">
                <tr>
                  <th scope="col">房间名称</th><th scope="col">面积</th><th scope="col">已选品</th><th scope="col">排版</th><th scope="col">空间复核</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="zone in zoneRows" :key="zone.id" class="border-t border-neutral-200">
                  <th scope="row">
                    <NuxtLink :to="roomRoute(zone.id)" class="font-semibold text-accent-ink hover:underline">{{ zone.name }}</NuxtLink>
                    <span v-if="zone.demo" class="ml-1 text-xs font-normal text-neutral-600">演示</span>
                  </th>
                  <td>{{ zone.area }}</td>
                  <td>{{ zone.selectedCount }} / {{ zone.moduleCount }} 件</td>
                  <td>{{ zone.layoutConfirmed ? '已确认' : zone.moduleCount ? '待确认' : '待排版' }}</td>
                  <td>{{ zone.review === 'CONFIRMED' ? '已复核' : zone.review === 'STALE' ? '复核失效' : '待复核' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else class="preview-message">当前楼层暂无空间。可上传图纸识别，或前往空间设计进行手绘。</p>
        </div>

        <aside class="overview-files space-y-4" aria-label="楼层图纸与项目需求">
          <button type="button" class="w-full border border-neutral-300 px-3 py-2 text-left font-semibold text-accent-ink hover:bg-accent-tint" @click="showRequirements = true">项目需求录入与更新</button>
          <div class="space-y-2 text-sm">
            <h3 class="font-semibold">当前楼层图纸</h3>
            <template v-if="drawing">
              <p class="wrap-break-word" data-testid="project-overview-drawing">{{ drawing.name }}</p>
              <p class="text-xs text-neutral-600">{{ drawingStatus }}</p>
            </template>
            <p v-else class="text-neutral-600">尚未上传图纸</p>
            <NuxtLink :to="projectDrawingRoute(floorId)" class="inline-block font-semibold text-accent-ink hover:underline">上传或识别图纸</NuxtLink>
          </div>
        </aside>
      </div>
    </section>
    <CustomerRequirementsDialog v-if="showRequirements" :key="project.projectId" @close="showRequirements = false" />
    <ProposalTargetDateDialog v-if="showProposalDate" :date="proposalTargetDate"
      @save="onProposalDateSave" @close="showProposalDate = false" />
  </div>
</template>

<style scoped>
.overview-hero { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.overview-content { grid-template-columns: minmax(0, 1fr); }
.floor-link:hover { background: var(--color-accent-tint); color: var(--color-accent-ink); }
.floor-link[aria-current='page'] { background: var(--color-accent); color: white; }
.preview-message { background: #f5f5f5; color: #525252; padding: 1.1rem; font-size: 0.875rem; }
th, td { padding: 0.65rem 0.5rem; }
.overview-files { border-top: 1px solid #d4d4d4; padding-top: 0.75rem; }

@media (max-width: 520px) {
  table { min-width: 0; font-size: 0.75rem; }
  th, td { padding: 0.55rem 0.25rem; }
}

@media (min-width: 1120px) {
  .overview-hero { grid-template-columns: minmax(0, 1fr) minmax(0, 1.15fr) minmax(0, 1.25fr) minmax(0, 0.8fr); }
  .overview-content { grid-template-columns: minmax(0, 1fr) minmax(12rem, 22%); }
  .overview-files { border-top: 0; border-left: 1px solid #d4d4d4; padding: 0 0 0 0.75rem; }
}
</style>
