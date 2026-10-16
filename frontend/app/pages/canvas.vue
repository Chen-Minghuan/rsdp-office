<script setup lang="ts">
const store = useOfficeStore()
const route = useRoute()
const router = useRouter()

const tool = ref('select')
const selectedId = ref<string | null>(null)
const showSubmit = ref(false)
const showRenderPreview = ref(false)
const overviewCollapsed = ref(false)
const boardRef = ref<{ fitBoard: () => void } | null>(null)

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
watch([analysisIdFromQuery, () => store.initialized], async ([id, initialized]) => {
  if (initialized && id && (!store.floorPlan || store.floorPlan.analysisId !== id)) {
    const snapshot = await store.loadFloorPlan(id)
    if (snapshot?.analysisId === id && analysisIdFromQuery.value === id) store.importFloorPlanToZones(snapshot)
  }
}, { immediate: true })

watch(() => store.designZoneId, (id, oldId) => {
  if (id === oldId || store.canvasMode !== 'design') return
  if (id && route.query.zoneId !== id) {
    const query: Record<string, string> = { ...route.query as Record<string, string>, mode: 'design', zoneId: id }
    if (store.floorPlan?.inputMode === 'manual' && /^(STPL|LSTPL)-/.test(store.floorPlan.analysisId)) delete query.analysisId
    router.replace({ path: route.path, query })
  }
})

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
  ? '聚焦单个功能区，与 Agent 协作完成家具布局与选品。'
  : '校准尺寸、划分办公功能区、标注整体需求，完成后进入单空间设计。')
</script>

<template>
  <OfficeCanvasSpaceReview v-if="mode === 'overview'" />
  <div v-else class="flex flex-col h-full gap-4">
    <div class="flex items-start justify-between flex-none">
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

    <div class="flex-1 min-h-0 flex gap-3">
      <!-- Left sidebar -->
      <OfficeCanvasOverviewSidebar v-if="mode === 'overview'" v-model="selectedId" />
      <OfficeCanvasDesignLayoutSidebar v-else />

      <!-- Canvas -->
      <div class="flex-1 min-w-0 flex flex-col bg-white border border-line rounded-2xl shadow-panel overflow-hidden">
        <OfficeCanvasToolbar
          v-model:tool="tool"
          :mode="mode"
          @undo="store.undo"
          @zoom-in="store.zoom = Math.min(2, store.zoom * 1.17)"
          @zoom-out="store.zoom = Math.max(0.00001, store.zoom / 1.17)"
          @fit="fitBoard"
          @render="showRenderPreview = true"
        />
        <OfficeCanvasBoard ref="boardRef" v-model:tool="tool" v-model:selected-id="selectedId" v-model:show-submit="showSubmit" v-model:mode="mode" />
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
