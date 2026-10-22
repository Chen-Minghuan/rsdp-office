<script setup lang="ts">
import type { FloorPlanAnalysisResponse } from '~/types/floorPlan'
import { drawingMatchesPlan, recognitionRunning } from '~/utils/drawingContext'
import { sameOriginAssetUrl } from '~/utils/apiBase'
const store = useOfficeStore()
const route = useRoute()
const router = useRouter()

const tool = ref('select')
const selectedId = ref<string | null>(null)
const layoutPreview = ref<{ zoneId: string; furniture: import('~/types/office').FurnitureObject[]; replaceIds: string[]; seatClearanceMm: number; exclusions: import('~/types/layoutPlanning').LayoutExclusion[] } | null>(null)
const showSubmit = ref(false)
const showRenderPreview = ref(false)
const overviewCollapsed = ref(false)
const viewRotation = ref(0)
const showDimensions = ref(true)
const boardRef = ref<{ fitBoard: () => void; rotateView: (degrees: number) => void } | null>(null)

const mode = computed<'overview' | 'design'>(() => store.canvasMode)
const designZoneId = computed(() => store.designZoneId)

// Sync URL query -> store (browser back/forward, direct links)
watch([() => route.query, () => store.initialized], ([q, initialized]) => {
  if (!initialized) return
  const urlMode = q.mode === 'design' && store.canEnterLayout ? 'design' : 'overview'
  const urlZoneId = q.zoneId as string | undefined
  if (urlMode !== store.canvasMode || urlZoneId !== store.designZoneId) {
    if (urlMode === 'design') store.enterDesign(store.zones.some(zone => zone.id === urlZoneId) ? urlZoneId! : store.zones[0]?.id || '')
    else { store.canvasMode = 'overview'; store.designZoneId = null }
  }
  if (q.mode === 'design' && !store.canEnterLayout) {
    const { mode, zoneId, ...query } = q
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
    delete query.zoneId
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
  showSubmit.value = false
  showRenderPreview.value = false
  store.cancelFurniturePlacement()
  if (id && route.query.zoneId !== id) {
    const query: Record<string, string> = { ...route.query as Record<string, string>, mode: 'design', zoneId: id }
    if (store.floorPlan?.inputMode === 'manual' && /^(STPL|LSTPL)-/.test(store.floorPlan.analysisId)) delete query.analysisId
    router.replace({ path: route.path, query })
  }
})

function switchDesignSpace(zoneId: string) {
  if (zoneId !== store.designZoneId) store.enterDesign(zoneId)
}

function openSubmit() {
  store.submitCanvas()
  showSubmit.value = true
}

function fitBoard() {
  boardRef.value?.fitBoard()
}

watch(() => store.canEnterLayout, (confirmed) => {
  if (!confirmed && store.canvasMode === 'design') store.setCanvasMode('overview')
})


const title = computed(() => mode.value === 'design' ? '无限画布 · 单空间设计' : '无限画布 · 空间核对')
const subtitle = computed(() => mode.value === 'design'
  ? '选择要排版的空间，分别完成家具布局与选品；切换空间会保留已有排版。'
  : '校准尺寸、划分办公功能区、标注整体需求，完成后进入单空间设计。')
</script>

<template>
  <section v-if="entryBlocked" class="flex h-full flex-col gap-4 overflow-auto rounded-2xl border border-line bg-white p-6" data-testid="drawing-entry">
    <h1 class="text-xl font-bold">当前图纸：{{ drawing?.name || entryResult?.sourceName || '图纸' }}</h1>
    <p v-if="entryError" role="alert" class="text-sm text-red-700">{{ entryError }}</p>
    <p v-else-if="entryLoading" class="text-sm text-muted">正在准备当前图纸的空间，识别完成后进入编辑。</p>
    <p v-else class="text-sm text-muted">请先识别当前图纸，再进入空间。已有图纸的编辑仍保留。</p>
    <RecognitionProgress v-if="entryLoading || entryResult?.processing" :progress="entryResult?.processing" :pending="entryLoading" />
    <img v-if="entryResult?.previewUrl" :src="sameOriginAssetUrl(entryResult.previewUrl)" alt="当前图纸预览" class="min-h-0 max-h-[60vh] w-full object-contain">
    <div class="flex gap-3">
      <NuxtLink to="/" class="rounded-lg border border-line px-4 py-2 text-sm">返回图纸识别</NuxtLink>
      <button v-if="entryError && targetAnalysisId" class="rounded-lg border border-line px-4 py-2 text-sm" @click="entryRetry++">重试查询当前图纸</button>
    </div>
  </section>
  <OfficeCanvasSpaceReview v-else-if="mode === 'overview'" />
  <div v-else class="flex flex-col h-full gap-4">
    <div class="flex flex-wrap items-start justify-between gap-3 flex-none">
      <div>
        <h1 class="text-[25px] font-bold tracking-tight mb-2">{{ title }}</h1>
        <p class="text-xs text-muted">{{ subtitle }}</p>
      </div>
      <div class="flex items-center gap-2">
        <NuxtLink
          to="/manual-canvas"
          class="inline-flex items-center gap-1 px-3 py-2 rounded-lg text-xs font-semibold border border-line hover:bg-[#f7f3ef]"
        >
          <AppIcon name="zone" size="3.5" />
          <span>手动布局</span>
        </NuxtLink>
        <button v-if="mode === 'design'" class="inline-flex items-center gap-1 px-3 py-2 rounded-lg text-xs font-semibold border border-line hover:bg-[#f7f3ef]" @click="store.setCanvasMode('overview')">
          <AppIcon name="arrowLeft" size="3.5" />
          <span>返回空间总览</span>
        </button>
      </div>
    </div>

    <OfficeCanvasDesignSpaceSwitcher :zone-id="designZoneId" @select="switchDesignSpace" />

    <div class="flex-1 min-h-0 flex gap-3">
      <!-- Left sidebar -->
      <OfficeCanvasOverviewSidebar v-if="mode === 'overview'" v-model="selectedId" />
      <OfficeCanvasDesignLayoutSidebar v-else @preview="layoutPreview = $event" />

      <!-- Canvas -->
      <div class="flex-1 min-w-0 flex flex-col bg-white border border-line rounded-2xl shadow-panel overflow-hidden">
        <OfficeCanvasToolbar
          v-model:tool="tool"
          v-model:show-dimensions="showDimensions"
          :view-rotation="viewRotation"
          :mode="mode"
          @undo="store.undo"
          @zoom-in="store.zoom = Math.min(2, store.zoom * 1.17)"
          @zoom-out="store.zoom = Math.max(0.00001, store.zoom / 1.17)"
          @fit="fitBoard"
          @render="showRenderPreview = true"
          @rotate="boardRef?.rotateView($event)"
        />
        <OfficeCanvasBoard ref="boardRef" v-model:view-rotation="viewRotation" :show-dimensions="showDimensions" :layout-preview="layoutPreview" v-model:tool="tool" v-model:selected-id="selectedId" v-model:show-submit="showSubmit" v-model:mode="mode" />
        <div class="h-12 flex-none border-t border-line flex items-center justify-between px-4 text-xs bg-white">
          <div v-if="mode === 'design'" class="text-muted">当前空间：{{ store.zones.find(zone => zone.id === designZoneId)?.name || '未选择' }}</div>
          <div v-else class="flex gap-5">
            <span class="flex items-center gap-1.5">已圈选 <strong class="text-bronze text-sm">{{ store.zoneCount }}</strong> 个区域</span>
            <span class="flex items-center gap-1.5">便签 <strong class="text-bronze text-sm">{{ store.noteCount }}</strong> 条</span>
            <span class="flex items-center gap-1.5">参考图 <strong class="text-bronze text-sm">{{ store.refCount }}</strong> 张</span>
          </div>
          <div class="flex items-center gap-2">
            <button v-if="mode === 'overview'" class="px-4 py-2 rounded-lg text-xs font-semibold text-white bg-bronze hover:bg-bronze-dark flex items-center gap-2 shadow-sm" @click="openSubmit">
              整理需求并提交 <AppIcon name="arrowRight" size="3.5" />
            </button>
            <button v-else class="px-4 py-2 rounded-lg text-xs font-semibold text-white bg-bronze hover:bg-bronze-dark flex items-center gap-2 shadow-sm" @click="store.setCanvasMode('overview')">
              完成当前空间 <AppIcon name="check" size="3.5" />
            </button>
          </div>
        </div>
      </div>

      <!-- Right sidebar -->
      <OfficeCanvasDesignSidebar v-if="mode === 'design'" :selected-id="selectedId" />
      <aside v-else :class="['flex-none bg-white border border-line rounded-2xl shadow-panel overflow-hidden transition-all duration-300', overviewCollapsed ? 'w-12' : 'w-72']">
        <div v-show="!overviewCollapsed" class="w-72 h-full p-4 flex flex-col gap-4">
          <div class="relative flex items-center justify-between">
            <div class="flex items-center gap-2 text-[15px] font-bold">
              <AppIcon name="documentText" size="4" />
              <span>结构化需求</span>
            </div>
            <button class="absolute top-0 right-0 p-1 rounded hover:bg-[#f7f3ef] text-muted" title="收起侧边栏" @click="overviewCollapsed = true">
              <AppIcon name="arrowRight" size="4" />
            </button>
          </div>
          <div class="pb-3 border-b border-line">
            <div class="flex justify-between text-xs text-[#78736d] my-3">
              <span class="flex items-center gap-1"><AppIcon name="zone" size="3.5" /> 功能区数量</span>
              <strong class="text-[#383432]">{{ store.zoneCount }} 个</strong>
            </div>
            <div class="flex justify-between text-xs text-[#78736d] my-3">
              <span class="flex items-center gap-1"><AppIcon name="warning" size="3.5" /> 待校准</span>
              <strong class="text-[#383432]">{{ store.zones.filter(z => !z.calibratedWidth).length }} 个</strong>
            </div>
          </div>
          <div class="mt-auto">
            <div class="text-xs font-semibold mb-2 flex items-center gap-1"><AppIcon name="info" size="3.5" /> 提示</div>
            <div class="text-xs text-muted leading-relaxed">
              在总览模式下圈选办公区域后，点击左侧卡片「进入设计」即可切换到单空间设计模式，进行家具布局与选品。
            </div>
          </div>
        </div>
        <div v-show="overviewCollapsed" class="w-12 h-full flex flex-col items-center py-3">
          <button class="p-1.5 rounded-lg hover:bg-[#f7f3ef] text-muted mb-4" title="展开侧边栏" @click="overviewCollapsed = false">
            <AppIcon name="arrowLeft" size="5" />
          </button>
          <div class="text-[10px] text-muted tracking-widest" style="writing-mode: vertical-rl;">
            需求
          </div>
        </div>
      </aside>
    </div>

    <OfficeCanvasSubmitDialog v-model:show="showSubmit" />
    <OfficeCanvasRenderPreviewDialog v-model:show="showRenderPreview" />
  </div>
</template>
