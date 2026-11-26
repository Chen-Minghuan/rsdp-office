<script setup lang="ts">
import type { FloorPlanAnalysisResponse } from '~/types/floorPlan'
import type { LayoutPreview, LayoutReferenceEditing } from '~/types/layoutPlanning'
import type { FurnitureObject } from '~/types/office'
import type { OfficeModuleTemplate } from '~/types/officeModule'
import { drawingMatchesPlan, recognitionRunning } from '~/utils/drawingContext'
import { sameOriginAssetUrl } from '~/utils/apiBase'
import { projectDrawingRoute } from '~/utils/projectBenchRoutes'
import { ringProblem, zoneMeasurements } from '~/utils/spatial/review'
import { money } from '~/utils/helpers'
import { furnitureById } from '~/utils/manualFurniture'
import { isMeetingAutoLayoutPrimary } from '~/utils/autoLayoutPrimary'
const store = useOfficeStore()
const workspace = useWorkspaceStore()
const route = useRoute()
const router = useRouter()

const tool = ref('select')
const selectedId = ref<string | null>(null)
const selectedFurnitureIds = ref<string[]>([])
const layoutPreview = ref<LayoutPreview | null>(null)
const referenceEditing = ref<LayoutReferenceEditing | null>(null)
const showRenderPreview = ref(false)
const showWhiteModel = ref(false)
const showSizeDialog = ref(false)
const sizeDialogItem = ref<FurnitureObject | null>(null)
const sizeDialogItemIds = ref<string[]>([])
const sizeDialogAnchor = ref<HTMLElement | null>(null)
const showBulkReplace = ref(false)
const bulkReplaceItem = ref<FurnitureObject | null>(null)
const bulkReplaceItemIds = ref<string[]>([])
const selectedRenderId = ref<string>()
function openRenderPreview(previewId?: string) {
  selectedRenderId.value = previewId
  showRenderPreview.value = true
}
const sizeMessage = ref('')
let sizeMessageTimer: ReturnType<typeof setTimeout> | undefined
/** 改尺寸成功但含复核提示时不关静默丢弃，短暂展示在上方。 */
function onSizeApplied(message: string) {
  sizeMessage.value = message
  if (sizeMessageTimer) clearTimeout(sizeMessageTimer)
  sizeMessageTimer = setTimeout(() => { sizeMessage.value = '' }, 4000)
}

function openSizeDialog(item: FurnitureObject, ids: string[], anchor?: HTMLElement) {
  if (isMeetingAutoLayoutPrimary(item, store.zones)) {
    const templateId = item.furnitureTemplateId || item.moduleSnapshot?.glyphTemplateId
    const template = templateId ? furnitureById.get(templateId) : undefined
    if (template) {
      workflowRef.value?.selectModule(template, [Math.round(item.w), Math.round(item.h)])
      return
    }
  }
  sizeDialogItem.value = item
  sizeDialogItemIds.value = ids
  sizeDialogAnchor.value = anchor || null
  showSizeDialog.value = true
}
function openBulkReplace(item: FurnitureObject, ids: string[]) {
  bulkReplaceItem.value = item
  bulkReplaceItemIds.value = ids
  showBulkReplace.value = true
}
const isProductScheme = computed(() => route.query.view === 'products' && store.canvasMode === 'design')
const {
  state: schemeState, busy: schemeBusy, error: schemeError, message: schemeMessage, source: schemeSource, favoriteIds,
  sourceError, bundles, bundleId, clipboard, room: schemeRoom, furniture: schemeFurniture, selectedCount,
  canEdit: canEditScheme, canApply, canClear, currentConfirmation, renders, refreshRenders, change: changeScheme, copy: copyScheme, applyBundle,
} = useProductScheme(isProductScheme)
watch(showRenderPreview, open => { if (!open && isProductScheme.value) void refreshRenders() })
const schemeArea = computed(() => {
  const zone = schemeRoom.value
  if (!zone?.polygon || ringProblem(zone.polygon)) return null
  return zoneMeasurements(zone).area?.toFixed(1)
})
const renderStyles = ['现代简约', '暖木自然', '深色商务']
const renderStyle = ref(renderStyles[0])
const renderFiles = useRenderPreviewApi()
const selectedSchemeProducts = computed(() => schemeFurniture.value
  .filter(item => item.productSelection && item.productId).map(item => ({ id: item.id, product: item.productSelection!.product })))
const inspectedProduct = computed(() => selectedSchemeProducts.value.find(item => item.id === selectedId.value)?.product)
const proposalRoute = computed(() => ({ path: '/proposal', query: { floorId: workspace.currentFloorId } }))
// Plan B 按需创建：后端 alternativeSaved=true 表示另一方案槽已有保存内容（即 Plan B 已存在）
const hasPlanB = computed(() => !!schemeState.value?.alternativeSaved)
async function switchScheme(targetSlot: 'A' | 'B') {
  if (!await changeScheme('activate', { targetSlot })) return
  layoutPreview.value = null
  referenceEditing.value = null
  selectedId.value = null
  tool.value = 'select'
}
function clearScheme() {
  if (window.confirm('清空当前房间方案中的商品？家具排版将保留。')) void changeScheme('clear-selection')
}
const viewRotation = ref(0)
const showDimensions = ref(true)
const snapEnabled = ref(true)
const showUnderlay = ref(true)
const boardRef = ref<{ fitBoard: () => void; rotateView: (degrees: number) => void } | null>(null)
const workflowRef = ref<{ openFurniture: (id: string, fromGroup: boolean) => void; openRecommendations: () => void; selectModule: (module: OfficeModuleTemplate, size?: [number, number]) => void } | null>(null)
function pickSchemeSource(source: 'auto' | 'project' | 'personal') {
  schemeSource.value = source
  if (store.designZoneId && store.layoutConfirmedForZone(store.designZoneId)) workflowRef.value?.openRecommendations()
}
watch(tool, value => { if (value.startsWith('constraints')) showUnderlay.value = true })
watch(() => !!referenceEditing.value, active => {
  if (active) {
    tool.value = 'select'
    selectedId.value = null
    showUnderlay.value = true
    store.cancelFurniturePlacement()
  }
})
watch(tool, value => {
  if (value !== 'pan' && value !== 'select') referenceEditing.value = null
})

const mode = computed<'overview' | 'design'>(() => store.canvasMode)

// Sync URL query -> store (browser back/forward, direct links)
watch([() => route.query, () => store.initialized, () => workspace.loading], ([q, initialized]) => {
  if (!initialized) return
  const urlMode = q.mode === 'design' && !store.layoutEntryBlocker ? 'design' : 'overview'
  const urlZoneId = typeof q.zoneId === 'string' ? q.zoneId : undefined
  if (urlMode !== store.canvasMode || urlZoneId !== store.designZoneId) {
    if (urlMode === 'design') store.enterDesign(urlZoneId && store.zones.some(zone => zone.id === urlZoneId) ? urlZoneId : store.zones[0]?.id || '')
    else { store.canvasMode = 'overview'; store.designZoneId = null }
  }
  if (q.mode !== 'design' && urlZoneId && store.zones.some(zone => zone.id === urlZoneId)) {
    store.reviewFocusZoneId = urlZoneId
  }
  // 楼层数据加载途中门禁条件会短暂不满足，等加载结束再决定是否剥离设计入口参数，避免误丢 zoneId
  if (q.mode === 'design' && !!store.layoutEntryBlocker && !workspace.loading) {
    const { mode, zoneId, view, ...query } = q
    router.replace({ path: '/canvas', query })
  }
}, { immediate: true, deep: true })

// Sync store -> URL
watch(() => store.canvasMode, (m, oldM) => {
  if (m === oldM) return
  const query: Record<string, string> = { ...route.query as Record<string, string> }
  if (store.floorPlan?.inputMode === 'manual' && /^(STPL|LSTPL)-/.test(store.floorPlan.analysisId)) delete query.analysisId
  if (m === 'design') {
    query.mode = 'design'
    query.zoneId = store.designZoneId || store.zones[0]?.id || ''
  } else {
    delete query.mode
    if (route.query.mode === 'design') delete query.zoneId
    delete query.view
  }
  router.replace({ path: route.path, query })
})

// 从 URL 加载户型图分析结果
const analysisIdFromQuery = computed(() => route.query.analysisId as string | undefined)
const drawing = computed(() => store.files[0])
const canvasMatchesDrawing = computed(() => drawingMatchesPlan(drawing.value, store.floorPlan)
  || (drawing.value?.mock && !!analysisIdFromQuery.value && analysisIdFromQuery.value === store.floorPlan?.analysisId))
const targetAnalysisId = computed(() => {
  if (drawing.value?.drawingId) {
    if (store.floorPlan?.inputMode === 'manual' && canvasMatchesDrawing.value && !analysisIdFromQuery.value) return undefined
    return drawing.value.analysisId
  }
  return analysisIdFromQuery.value || (!canvasMatchesDrawing.value ? drawing.value?.analysisId : undefined)
})
const entryResult = ref<FloorPlanAnalysisResponse | null>(null)
const entryLoading = ref(false), entryError = ref(''), entryRetry = ref(0)
const entryBlocked = computed(() => entryLoading.value || !!entryError.value || !canvasMatchesDrawing.value)
watch([targetAnalysisId, () => store.initialized, () => drawing.value?.drawingId, entryRetry], ([id, initialized], _, onCleanup) => {
  let cancelled = false
  let timer: ReturnType<typeof setTimeout> | undefined
  onCleanup(() => { cancelled = true; clearTimeout(timer) })
  entryResult.value = null
  entryError.value = ''
  entryLoading.value = false
  if (!initialized || !id || store.floorPlan?.analysisId === id && canvasMatchesDrawing.value) return
  entryLoading.value = true
  async function load() {
    try {
      const snapshot = await useFloorPlanApi().getFloorPlan(id!)
      if (cancelled || targetAnalysisId.value !== id) return
      if (snapshot.analysisId !== id) throw new Error('识别结果与当前图纸任务不一致，请重新识别')
      if (snapshot.projectId && snapshot.projectId !== store.workspaceId) throw new Error('识别结果不属于当前楼层，请返回对应楼层查看')
      if (drawing.value && !drawing.value.drawingId && !drawing.value.mock && snapshot.sourceName !== drawing.value.name) {
        throw new Error('当前识别结果不属于 ' + drawing.value.name + '，请返回上传页识别当前图纸')
      }
      entryResult.value = snapshot
      if (snapshot.status === 'failed') throw new Error(snapshot.errorMessage || '当前图纸识别失败，请返回上传页重新识别')
      if (recognitionRunning(snapshot)) { timer = setTimeout(load, 1000); return }
      if (!snapshot.rooms?.length) throw new Error('当前图纸未识别出可用空间，请返回上传页重新识别')
      // Preserve intentionally empty legacy drafts; a newly selected drawing is an explicit new import.
      if (!store.blank || store.zones.length || drawing.value?.analysisId === id && drawing.value.appliedAnalysisId !== id) {
        store.importFloorPlanToZones({ ...snapshot, sourceDrawingId: drawing.value?.analysisId === id ? drawing.value.drawingId : undefined })
      } else {
        store.setFloorPlan({ ...snapshot, sourceDrawingId: drawing.value?.drawingId })
        store.saveLocal()
      }
      entryLoading.value = false
    } catch (error) {
      if (cancelled) return
      entryLoading.value = false
      entryError.value = error instanceof Error ? error.message : '当前图纸加载失败'
    }
  }
  void load()
}, { immediate: true })

watch(() => store.designZoneId, (id, oldId) => {
  if (id === oldId || store.canvasMode !== 'design') return
  selectedId.value = null
  tool.value = 'select'
  showRenderPreview.value = false
  showWhiteModel.value = false
  store.cancelFurniturePlacement()
  if (id && route.query.zoneId !== id) {
    const query: Record<string, string> = { ...route.query as Record<string, string>, mode: 'design', zoneId: id }
    if (store.floorPlan?.inputMode === 'manual' && /^(STPL|LSTPL)-/.test(store.floorPlan.analysisId)) delete query.analysisId
    router.replace({ path: route.path, query })
  }
})

// 3D「定位到 2D」：关闭预览并在无限画布中选中同一家具实例
function onLocateFrom3d(id: string) {
  showWhiteModel.value = false
  selectedId.value = id
}

function fitBoard() {
  boardRef.value?.fitBoard()
}

watch(() => store.canEnterLayout, (confirmed) => {
  if (!confirmed && store.canvasMode === 'design') store.setCanvasMode('overview')
})

</script>

<template>
  <section v-if="entryBlocked" class="flex h-full flex-col gap-4 overflow-auto rounded-none border border-line bg-white p-6" data-testid="drawing-entry">
    <h1 class="text-xl font-bold">当前图纸：{{ drawing?.name || entryResult?.sourceName || '图纸' }}</h1>
    <p v-if="entryError" role="alert" class="text-sm text-red-700">{{ entryError }}</p>
    <p v-else-if="entryLoading" class="text-sm text-muted">正在准备当前图纸的空间，识别完成后进入编辑。</p>
    <p v-else class="text-sm text-muted">请先识别当前图纸，再进入空间。已有图纸的编辑仍保留。</p>
    <RecognitionProgress v-if="entryLoading || entryResult?.processing" :progress="entryResult?.processing" :pending="entryLoading" />
    <img v-if="entryResult?.previewUrl" :src="sameOriginAssetUrl(entryResult.previewUrl)" alt="当前图纸预览" class="min-h-0 max-h-[60vh] w-full object-contain">
    <div class="flex gap-3">
      <NuxtLink :to="projectDrawingRoute(workspace.currentFloorId)" class="rounded-none border border-line px-4 py-2 text-sm">返回图纸识别</NuxtLink>
      <button v-if="entryError && targetAnalysisId" class="rounded-none border border-line px-4 py-2 text-sm" @click="entryRetry++">重试查询当前图纸</button>
    </div>
  </section>
  <OfficeCanvasSpaceReview v-else-if="mode === 'overview'" />
  <div v-else class="flex h-full min-w-0 flex-col" :data-testid="isProductScheme ? 'product-scheme' : undefined">
    <header v-if="isProductScheme" class="scheme-header">
      <span class="scheme-floor">{{ workspace.currentFloorName }}</span>
      <h1>{{ schemeRoom?.name || '未选择空间' }}</h1>
      <span v-if="schemeArea">{{ schemeArea }} m²</span>
      <span>已选 <strong>{{ selectedCount }}</strong> / {{ schemeFurniture.length }} 件</span>
      <div class="scheme-versions" aria-label="房间方案">
        <button type="button"
          :aria-pressed="schemeState ? schemeState.activeSlot === 'A' : undefined" :disabled="!canEditScheme || schemeBusy || !store.apiReady || !schemeRoom"
          @click="switchScheme('A')">Plan A</button>
        <button v-if="hasPlanB" type="button"
          :aria-pressed="schemeState ? schemeState.activeSlot === 'B' : undefined" :disabled="!canEditScheme || schemeBusy || !store.apiReady || !schemeRoom"
          @click="switchScheme('B')">Plan B</button>
        <button v-else type="button" class="scheme-add" aria-label="新建 Plan B" title="以 Plan A 为起点创建 Plan B"
          :disabled="!schemeState || !canEditScheme || schemeBusy || !store.apiReady || !schemeRoom"
          @click="switchScheme('B')">+</button>
      </div>
      <button type="button" :disabled="!canEditScheme || schemeBusy || !store.apiReady || !canClear" @click="clearScheme">清空选品</button>
      <p v-if="schemeError || schemeMessage" class="scheme-feedback" :role="schemeError ? 'alert' : 'status'">{{ schemeError || schemeMessage }}</p>
    </header>
    <div class="flex-1 min-h-0 flex" :class="{ 'scheme-body': isProductScheme }" :inert="schemeBusy || undefined">
      <div class="flex-1 min-w-0 flex flex-col overflow-hidden border-y border-r border-line bg-white" :class="{ 'scheme-board': isProductScheme }">
        <OfficeCanvasToolbar
          v-model:tool="tool"
          v-model:show-dimensions="showDimensions"
          v-model:snap-enabled="snapEnabled"
          :view-rotation="viewRotation"
          :mode="mode"
          :preview-in-footer="isProductScheme"
          @undo="store.undo"
          @redo="store.redo"
          @zoom-in="store.zoom = Math.min(2, store.zoom * 1.17)"
          @zoom-out="store.zoom = Math.max(0.00001, store.zoom / 1.17)"
          @fit="fitBoard"
          @render="openRenderPreview()"
          @white-model="showWhiteModel = true"
          @rotate="boardRef?.rotateView($event)"
        />
        <p v-if="sizeMessage" role="status" class="mx-3 mt-2 flex-none border border-[#d8e6c8] bg-[#f3f9ee] px-2 py-1.5 text-[10px] text-[#4d713d]">{{ sizeMessage }}</p>
        <OfficeCanvasBoard ref="boardRef" v-model:view-rotation="viewRotation" v-model:show-underlay="showUnderlay" :show-dimensions="showDimensions" v-model:layout-preview="layoutPreview" v-model:reference-editing="referenceEditing" v-model:tool="tool" v-model:selected-id="selectedId" v-model:selected-furniture-ids="selectedFurnitureIds" :mode="mode" v-model:snap-enabled="snapEnabled" :updating-scheme="schemeBusy" @edit-furniture="(id, fromGroup) => workflowRef?.openFurniture(id, fromGroup)" @edit-size="(id, anchor) => openSizeDialog(store.objects.find(o => o.id === id) as FurnitureObject, [id], anchor)" />
        <OfficeCanvasFurnitureStrip v-if="mode === 'design' && !isProductScheme" v-model:selected-id="selectedId" v-model:selected-furniture-ids="selectedFurnitureIds" @edit-size="(item, ids, anchor) => openSizeDialog(item, ids, anchor)" @bulk-replace="(item, ids) => openBulkReplace(item, ids)" />
        <div v-if="isProductScheme" class="scheme-board-footer">
          <span v-if="inspectedProduct"><strong>{{ inspectedProduct.name }}</strong> · {{ inspectedProduct.variant }} · {{ money(inspectedProduct.price) }}</span>
          <span v-else-if="selectedCount">点击画布家具查看已选商品</span>
          <button type="button" :disabled="!selectedCount" @click="copyScheme">复制选品</button>
          <button type="button" :disabled="!canApply || !clipboard.length" @click="changeScheme('apply-products', { items: clipboard })">粘贴选品</button>
        </div>
        <div v-else class="h-12 flex-none border-t border-line flex items-center justify-between px-4 text-xs bg-white">
          <div class="text-muted">当前空间：{{ store.zones.find(zone => zone.id === store.designZoneId)?.name || '未选择' }}</div>
          <button type="button" class="bg-bronze px-4 py-2 text-xs font-semibold text-white hover:bg-bronze-dark" @click="store.setCanvasMode('overview')">完成当前空间</button>
        </div>
      </div>
      <OfficeCanvasDesignWorkflowSidebar ref="workflowRef" v-model:selected-id="selectedId" v-model:reference-editing="referenceEditing"
        :current-preview="layoutPreview" :open-selection="isProductScheme" :allowed-rspu-ids="isProductScheme ? favoriteIds : undefined" @preview="layoutPreview = $event">
        <template v-if="isProductScheme" #scheme-tools>
          <div class="scheme-tools">
            <div class="scheme-sources" aria-label="选品来源">
              <button type="button" :aria-pressed="schemeSource === 'auto'" @click="pickSchemeSource('auto')">自动选品</button>
              <button type="button" :aria-pressed="schemeSource === 'project'" @click="pickSchemeSource('project')">项目选品库</button>
              <button type="button" :aria-pressed="schemeSource === 'personal'" @click="pickSchemeSource('personal')">个人收藏夹</button>
            </div>
            <p v-if="sourceError" role="alert" class="text-xs text-red-700">{{ sourceError }}</p>
            <button v-if="schemeSource === 'auto'" type="button" class="scheme-auto" :disabled="!canApply" @click="changeScheme('apply-products', { automatic: true })">一键匹配待选家具</button>
            <div v-if="bundles.length" class="scheme-bundle">
              <select v-model="bundleId" aria-label="选择商品组合">
                <option value="">选择商品组合</option>
                <option v-for="bundle in bundles" :key="bundle.bundleId" :value="bundle.bundleId" :disabled="!bundle.items.length">{{ bundle.name }}</option>
              </select>
              <button type="button" :disabled="!canApply || !bundleId" @click="applyBundle">应用组合</button>
            </div>
          </div>
        </template>
      </OfficeCanvasDesignWorkflowSidebar>
    </div>

    <footer v-if="isProductScheme" class="scheme-output" :inert="schemeBusy || undefined">
      <div class="scheme-styles" aria-label="效果图风格">
        <button v-for="style in renderStyles" :key="style" type="button" :aria-pressed="style === renderStyle" @click="renderStyle = style">{{ style }}</button>
      </div>
      <button type="button" :disabled="!schemeFurniture.length" @click="showWhiteModel = true">3D 预览</button>
      <button v-if="canEditScheme && currentConfirmation" class="scheme-generate" type="button" @click="openRenderPreview()">生成方案效果图</button>
      <NuxtLink v-else-if="canEditScheme" class="scheme-generate" :to="proposalRoute">去项目清单确认</NuxtLink>
      <button type="button" @click="openRenderPreview()">生成记录</button>
      <button v-for="image in renders" :key="image.previewId" class="scheme-thumbnail" type="button" :aria-label="image.style + '效果图'" @click="openRenderPreview(image.previewId)">
        <img :src="renderFiles.imageUrl(image.imageUrl)" :alt="image.style" :class="{ 'opacity-50': image.stale }">
      </button>
    </footer>

    <OfficeCanvasRenderPreviewDialog v-model:show="showRenderPreview" :formal="isProductScheme"
      :confirmation-id="canEditScheme ? currentConfirmation : undefined" :initial-style="isProductScheme ? renderStyle : undefined" :initial-preview-id="selectedRenderId" />
    <OfficeCanvasWhiteModelPreview v-model:show="showWhiteModel" :readonly="isProductScheme && !canEditScheme" @ai-render="openRenderPreview()" @locate="onLocateFrom3d" />
    <OfficeCanvasFurnitureSizeDialog v-if="store.designZoneId" v-model:show="showSizeDialog" :item="sizeDialogItem" :item-ids="sizeDialogItemIds" :anchor="sizeDialogAnchor" @applied="onSizeApplied" />
    <OfficeCanvasFurnitureProductSelector v-if="store.designZoneId" v-model:show="showBulkReplace" :zone-id="store.designZoneId" :item="bulkReplaceItem" :target-ids="bulkReplaceItemIds" bulk-mode />
  </div>
</template>

<style scoped>
.scheme-header,
.scheme-output,
.scheme-board-footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: .6rem;
  background: #fff;
  border: 1px solid var(--color-line);
  padding: .7rem;
}

.scheme-header {
  border-bottom: 3px solid var(--color-accent);
  color: var(--color-muted);
  font-size: .8rem;
}

.scheme-header h1 {
  font-size: 1.08rem;
  font-weight: 700;
  color: #27202e;
  margin-right: .4rem;
}

.scheme-header strong {
  color: var(--color-accent-ink);
}

.scheme-floor {
  background: var(--color-accent);
  color: white;
  padding: .22rem .45rem;
  font-weight: 700;
}

.scheme-versions {
  display: flex;
  gap: .25rem;
  margin-left: auto;
}

.scheme-add {
  min-width: 2rem;
}

.scheme-header button,
.scheme-output button,
.scheme-output a,
.scheme-board-footer button,
.scheme-tools button,
.scheme-tools select {
  padding: .37rem .55rem;
  border: 1px solid var(--color-accent-line);
  color: var(--color-accent-ink);
  background: white;
  font-size: .75rem;
  font-weight: 600;
}

.scheme-header button[aria-pressed=true],
.scheme-tools button[aria-pressed=true],
.scheme-output .scheme-generate,
.scheme-output button[aria-pressed=true] {
  background: var(--color-accent);
  color: white;
  border-color: var(--color-accent);
}

.scheme-header button:disabled,
.scheme-output button:disabled,
.scheme-tools button:disabled,
.scheme-board-footer button:disabled {
  opacity: .45;
}

.scheme-feedback {
  width: 100%;
  margin: 0;
}

.scheme-feedback[role=alert] {
  color: #992d39;
}

.scheme-tools {
  display: grid;
  gap: .4rem;
  padding: .55rem;
  background: var(--color-accent-tint);
  border-bottom: 1px solid var(--color-line);
}

.scheme-sources,
.scheme-bundle,
.scheme-styles {
  display: flex;
  gap: .3rem;
  flex-wrap: wrap;
}

.scheme-sources button {
  flex: 1 1 auto;
}

.scheme-bundle select {
  min-width: 0;
  flex: 1;
}

.scheme-board-footer {
  justify-content: flex-end;
  font-size: .75rem;
  color: var(--color-muted);
}

.scheme-board-footer span {
  margin-right: auto;
}

.scheme-output {
  border-top: 0;
}

.scheme-output .scheme-thumbnail {
  padding: 0;
}

.scheme-thumbnail img {
  width: 3.25rem;
  height: 2rem;
  object-fit: cover;
}

@media (max-width: 1100px) {
  .scheme-body {
    flex-direction: column;
    overflow: auto;
  }

  .scheme-body .scheme-board {
    flex: none;
    height: max(28rem, 62vh);
  }

  .scheme-body :deep([data-testid=design-workflow-sidebar]) {
    width: 100%;
    min-height: 34rem;
  }
}
</style>
