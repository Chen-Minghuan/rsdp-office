<script setup lang="ts">
import type { ProjectFile, ZoneObject, ToneKey, ZoneType } from '~/types/office'
import { drawingFileDescriptor } from '~/utils/drawingUpload'
import { drawingMatchesPlan, recognitionRunning } from '~/utils/drawingContext'
import { createUuid } from '~/utils/uuid'
import { sameOriginAssetUrl } from '~/utils/apiBase'

const store = useOfficeStore()
const api = useOfficeApi()
const floorPlanApi = useFloorPlanApi()
const fileInput = ref<HTMLInputElement | null>(null)
const recognitionPanel = ref<HTMLElement | null>(null)
const isParsing = ref(false)
const isUploading = ref(false)
const isApplying = ref(false)
const uploadError = ref<string | null>(null)
const uploadBusy = computed(() => isParsing.value || isUploading.value || isApplying.value)
const parseError = ref<string | null>(null)
const recognitionResult = ref<any>(null)
const activeFile = ref<string | null>(null)
const recognitionSource = ref<'cad' | 'vision' | null>(null)
const analysisId = ref<string | null>(null)
const pdfPages = reactive<Record<string, number>>({})
const taskStorageKey = 'rsdp-office-recognition-task-v1'
const isRecognitionRunning = recognitionRunning
const hasRunningResult = computed(() => isRecognitionRunning(recognitionResult.value))
let recognitionSequence = 0
let recognitionPoll: ReturnType<typeof setTimeout> | undefined
let disposed = false

function forgetRecognitionTask() {
  recognitionSequence++
  clearTimeout(recognitionPoll)
  recognitionPoll = undefined
  sessionStorage.removeItem(taskStorageKey)
}

async function pollRecognition(id: string, ticket: number) {
  if (disposed || ticket !== recognitionSequence || analysisId.value !== id) return
  recognitionPoll = undefined
  try {
    const detail = await floorPlanApi.getFloorPlan(id)
    if (disposed || ticket !== recognitionSequence || analysisId.value !== id) return
    const reveal = !recognitionResult.value || (!recognitionResult.value.previewUrl && detail.previewUrl)
    recognitionResult.value = detail
    if (reveal) {
      await nextTick()
      if (disposed || ticket !== recognitionSequence) return
      recognitionPanel.value?.scrollIntoView({ block: 'start' })
    }
    if (detail.status === 'failed') {
      parseError.value = detail.errorMessage || '图纸识别失败'
      isParsing.value = false
      sessionStorage.removeItem(taskStorageKey)
      return
    }
    if (isRecognitionRunning(detail)) {
      recognitionPoll = setTimeout(() => pollRecognition(id, ticket), 1000)
    } else {
      isParsing.value = false
      sessionStorage.removeItem(taskStorageKey)
    }
  } catch (error) {
    if (disposed || ticket !== recognitionSequence) return
    parseError.value = (error instanceof Error ? error.message : '任务查询失败') + '；可继续查询，无需重新识别。'
    isParsing.value = false
  }
}

function resumeRecognition() {
  if (!analysisId.value) return
  clearTimeout(recognitionPoll)
  parseError.value = null
  isParsing.value = true
  void pollRecognition(analysisId.value, ++recognitionSequence)
}

onMounted(() => {
  watch(() => store.initialized, ready => {
    if (!ready || isParsing.value) return
    try {
      const task = JSON.parse(sessionStorage.getItem(taskStorageKey) || 'null')
      const drawing = store.files[0]
      if (!task || task.projectId !== api.projectId || task.name !== drawing?.name || task.size !== drawing?.size) return
      if (task.drawingId && task.drawingId !== drawing?.drawingId) return
      if (drawing && (!drawing.drawingId || drawing.analysisId !== task.analysisId)) {
        const bound = { ...drawing, drawingId: drawing.drawingId || createUuid(), analysisId: task.analysisId, analysisPageNumber: task.pageNumber }
        store.$patch({ files: [bound] })
        store.schedulePersist()
        sessionStorage.setItem(taskStorageKey, JSON.stringify({ ...task, drawingId: bound.drawingId }))
      }
      activeFile.value = task.name
      pdfPages[task.name] = task.pageNumber
      analysisId.value = task.analysisId
      recognitionSource.value = task.source
      resumeRecognition()
    } catch { sessionStorage.removeItem(taskStorageKey) }
  }, { immediate: true })
})
onBeforeUnmount(() => { disposed = true; recognitionSequence++; clearTimeout(recognitionPoll) })

const requiresCalibration = computed(() => recognitionResult.value?.unitResolution?.canonicalUnit === 'px')

// 保留真实 File 对象，用于图纸识别时读取二进制内容
const fileMap = ref<Map<string, File>>(new Map())

// IndexedDB：刷新页面后仍可恢复已上传文件，避免识别时提示“找不到文件”
const DB_NAME = 'rsdp-office-files'
const DB_STORE = 'files'
const DB_VERSION = 1

function openFileDB(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onerror = () => reject(req.error)
    req.onsuccess = () => resolve(req.result)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(DB_STORE)) {
        db.createObjectStore(DB_STORE, { keyPath: 'name' })
      }
    }
  })
}

async function persistFile(file: File): Promise<void> {
  try {
    const db = await openFileDB()
    const tx = db.transaction(DB_STORE, 'readwrite')
    const os = tx.objectStore(DB_STORE)
    os.clear()
    os.put({ name: file.name, type: file.type, size: file.size, blob: file })
    await new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
  } catch (e) {
    console.warn('持久化文件失败', e)
  }
}

async function loadPersistedFile(name: string): Promise<File | null> {
  try {
    const db = await openFileDB()
    const tx = db.transaction(DB_STORE, 'readonly')
    const os = tx.objectStore(DB_STORE)
    const record = await new Promise<any>((resolve, reject) => {
      const req = os.get(name)
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => reject(req.error)
    })
    if (!record?.blob) return null
    return new File([record.blob], record.name, { type: record.type })
  } catch (e) {
    console.warn('读取持久化文件失败', e)
    return null
  }
}

async function deletePersistedFile(name: string): Promise<void> {
  try {
    const db = await openFileDB()
    const tx = db.transaction(DB_STORE, 'readwrite')
    const os = tx.objectStore(DB_STORE)
    os.delete(name)
    await new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
  } catch (e) {
    console.warn('删除持久化文件失败', e)
  }
}

onMounted(async () => {
  // 从 IndexedDB 恢复刷新前上传的真实文件
  for (const f of store.files) {
    if (f.mock || fileMap.value.has(f.name)) continue
    const restored = await loadPersistedFile(f.name)
    if (restored) {
      fileMap.value.set(f.name, restored)
    }
  }
})

function fileType(name: string) {
  const ext = (name.split('.').pop() || '').toUpperCase()
  if (ext === 'PDF') return 'PDF'
  if (['PNG', 'JPG', 'JPEG', 'WEBP'].includes(ext)) return 'IMAGE'
  if (['DWG', 'DXF'].includes(ext)) return 'CAD'
  return 'OTHER'
}

function isRecognizable(name: string) {
  const t = fileType(name)
  return t === 'CAD' || t === 'IMAGE' || t === 'PDF'
}

function recognizeLabel(name: string, mock: boolean) {
  if (mock) return '演示文件'
  return fileType(name) === 'CAD' ? '识别 CAD' : '识别图纸'
}

function canRecognize(f: ProjectFile) {
  return isRecognizable(f.name) && !f.mock
}

function fileIcon(type: string) {
  if (type === 'PDF') return 'file'
  if (type === 'IMAGE') return 'photo'
  if (type === 'CAD') return 'cube'
  return 'folder'
}

function fileLabel(type: string) {
  if (type === 'PDF') return 'PDF 文档'
  if (type === 'IMAGE') return '图片'
  if (type === 'CAD') return 'CAD 图纸'
  return '文件'
}

function fmtSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

async function acceptDrawing(list: FileList | null) {
  if (!list?.length || uploadBusy.value) return
  uploadError.value = null
  if (list.length !== 1) {
    uploadError.value = '一次只能上传一张图纸，请只选择一个文件。'
    return
  }
  const file = list[0]!
  isUploading.value = true
  try {
    const drawing = drawingFileDescriptor(file)
    forgetRecognitionTask()
    store.$patch({ files: [drawing] })
    store.schedulePersist()
    fileMap.value.clear()
    fileMap.value.set(file.name, file)
    activeFile.value = null
    recognitionResult.value = null
    recognitionSource.value = null
    analysisId.value = null
    parseError.value = null
    for (const name of Object.keys(pdfPages)) delete pdfPages[name]
    await persistFile(file)
  } catch (error) {
    uploadError.value = error instanceof Error ? error.message : '上传图纸失败'
  } finally {
    isUploading.value = false
  }
}

async function onDrop(e: DragEvent) {
  await acceptDrawing(e.dataTransfer?.files || null)
}

async function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  try {
    await acceptDrawing(input.files)
  } finally {
    input.value = ''
  }
}

async function removeFile(name: string) {
  if (uploadBusy.value) return
  isUploading.value = true
  uploadError.value = null
  try {
    forgetRecognitionTask()
    store.removeFile(name)
    fileMap.value.delete(name)
    if (activeFile.value === name) {
      activeFile.value = null
      recognitionResult.value = null
      recognitionSource.value = null
      analysisId.value = null
      parseError.value = null
    }
    await deletePersistedFile(name)
  } finally {
    isUploading.value = false
  }
}

const recognizableFiles = computed(() => store.files.filter((f) => isRecognizable(f.name)))
const hasRecognizableFiles = computed(() => recognizableFiles.value.length > 0)

async function recognizeFile(file: ProjectFile) {
  if (uploadBusy.value) return
  forgetRecognitionTask()
  const ticket = recognitionSequence
  activeFile.value = file.name
  isParsing.value = true
  parseError.value = null
  recognitionResult.value = null
  recognitionSource.value = null
  analysisId.value = null

  try {
    let rawFile = fileMap.value.get(file.name)
    if (!rawFile && !file.mock) {
      rawFile = await loadPersistedFile(file.name)
      if (rawFile) fileMap.value.set(file.name, rawFile)
    }
    if (!rawFile) {
      parseError.value = '找不到对应的文件，请重新上传'
      return
    }

    const type = fileType(file.name)
    const isCad = type === 'CAD'
    const pageNumber = type === 'PDF' ? (pdfPages[file.name] ?? 1) : 1
    if (!Number.isInteger(pageNumber) || pageNumber < 1) {
      throw new Error('请输入从 1 开始的整数 PDF 页码')
    }
    const result = await floorPlanApi.analyzeFloorPlan({
      image: isCad ? undefined : rawFile,
      cad: isCad ? rawFile : undefined,
      projectId: api.projectId,
      sourceName: file.name,
      pageNumber,
    })
    if (disposed || ticket !== recognitionSequence) return
    recognitionSource.value = isCad ? 'cad' : 'vision'
    analysisId.value = result.analysisId
    store.$patch({ files: store.files.map(drawing => drawing === file || drawing.drawingId && drawing.drawingId === file.drawingId
      ? { ...drawing, analysisId: result.analysisId, analysisPageNumber: pageNumber } : drawing) })
    store.schedulePersist()
    sessionStorage.setItem(taskStorageKey, JSON.stringify({ analysisId: result.analysisId, name: file.name, size: file.size,
      drawingId: file.drawingId, projectId: api.projectId, pageNumber, source: recognitionSource.value }))
    await pollRecognition(result.analysisId, ticket)
  } catch (e: any) {
    if (disposed || ticket !== recognitionSequence) return
    parseError.value = e?.message || '图纸识别失败'
  } finally {
    if (!recognitionPoll && ticket === recognitionSequence) isParsing.value = false
  }
}

function previewImage() {
  if (recognitionResult.value?.previewUrl) return sameOriginAssetUrl(recognitionResult.value.previewUrl)
  if (!recognitionResult.value?.preview?.pngBase64) return null
  return `data:image/png;base64,${recognitionResult.value.preview.pngBase64}`
}

function statusClass(status: string) {
  if (status === 'SUCCESS') return 'bg-green/10 text-green'
  if (status === 'PARTIAL') return 'bg-[#fff5e6] text-[#c97d1a]'
  if (status === 'NEEDS_REVIEW') return 'bg-[#fff0f0] text-[#b44b4b]'
  return 'bg-[#f4f4f4] text-muted'
}

function statusText(status: string) {
  if (status === 'SUCCESS') return '解析成功'
  if (status === 'PARTIAL') return '部分识别'
  if (status === 'NEEDS_REVIEW') return '需人工复核'
  if (status === 'FAILED') return '解析失败'
  return status
}

function roomTypeText(type?: string) {
  const map: Record<string, string> = {
    LIVING_ROOM: '客厅',
    DINING_ROOM: '餐厅',
    BEDROOM: '卧室',
    KITCHEN: '厨房',
    BATHROOM: '卫生间',
    BALCONY: '阳台',
    STUDY: '书房',
    HALLWAY: '走廊',
    OTHER: '其他',
    OPEN_OFFICE: '开放办公区',
    MEETING_ROOM: '会议室',
    RECEPTION: '接待区',
    EXECUTIVE_OFFICE: '高管办公室',
    LOUNGE: '休闲区',
  }
  return type ? (map[type] || type) : '未命名空间'
}

function mapRoomTypeToZoneType(roomType?: string): ZoneType {
  switch (roomType) {
    case 'OPEN_OFFICE':
    case 'LIVING_ROOM':
      return 'OPEN_OFFICE'
    case 'MEETING_ROOM':
    case 'DINING_ROOM':
      return 'MEETING_ROOM'
    case 'EXECUTIVE_OFFICE':
    case 'BEDROOM':
    case 'STUDY':
      return 'EXECUTIVE_OFFICE'
    case 'RECEPTION':
      return 'RECEPTION'
    case 'COLLABORATION':
    case 'LOUNGE':
      return 'COLLABORATION'
    default:
      return 'OTHER'
  }
}

function polygonBounds(polygon: number[][]): { minX: number; minY: number; maxX: number; maxY: number } {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  for (const [x, y] of polygon) {
    minX = Math.min(minX, x)
    minY = Math.min(minY, y)
    maxX = Math.max(maxX, x)
    maxY = Math.max(maxY, y)
  }
  return { minX, minY, maxX, maxY }
}

async function applyRecognitionToCanvas() {
  if (!analysisId.value) {
    parseError.value = '尚未完成图纸分析'
    return
  }

  if (uploadBusy.value || hasRunningResult.value || recognitionResult.value?.status === 'failed') return
  const id = analysisId.value
  const drawingId = store.files[0]?.drawingId
  const ticket = recognitionSequence
  isApplying.value = true
  try {
    const detail = await floorPlanApi.getFloorPlan(id)
    if (disposed || ticket !== recognitionSequence || store.files[0]?.drawingId !== drawingId) return
    if (detail.analysisId !== id) throw new Error('识别结果与当前图纸任务不一致，请重新识别')
    if (isRecognitionRunning(detail) || detail.status === 'failed') throw new Error('空间识别尚未完成，请稍后再应用')
    if (!detail.rooms?.length) throw new Error('当前图纸未识别出可用空间，请重新识别')
    store.importFloorPlanToZones({ ...detail, sourceDrawingId: store.files[0]?.drawingId })
    await navigateTo(`/canvas?analysisId=${id}&mode=overview`)
  } catch (error) {
    if (disposed || ticket !== recognitionSequence) return
    parseError.value = error instanceof Error ? error.message : '导入空间失败'
  } finally {
    isApplying.value = false
  }
}

const canOpenCurrentDrawing = computed(() => {
  const drawing = store.files[0]
  return !isUploading.value && !isApplying.value && (!isParsing.value || !!analysisId.value)
    && (!!drawing?.analysisId || drawingMatchesPlan(drawing, store.floorPlan))
})
async function enterCurrentDrawing() {
  if (!canOpenCurrentDrawing.value) return
  const id = store.files[0]?.analysisId
  if (id && id === analysisId.value && recognitionResult.value && !hasRunningResult.value && recognitionResult.value.status !== 'failed') {
    await applyRecognitionToCanvas()
  } else await navigateTo(id ? { path: '/canvas', query: { analysisId: id, mode: 'overview' } } : '/canvas')
}
</script>

<template>
  <div class="flex flex-col gap-4 h-full">
    <div class="flex items-start justify-between gap-5 flex-none">
      <div>
        <h1 class="text-[25px] font-bold tracking-tight mb-2">上传办公图纸</h1>
        <p class="text-xs text-muted">上传 PDF/PNG 参考图或 CAD 图纸（DWG/DXF），自动识别空间布局并生成可编辑的办公功能分区。</p>
      </div>
    </div>

    <div class="flex-1 min-h-0 flex flex-col gap-4">
      <!-- 上传与识别结果 -->
      <div class="flex-1 min-h-0 bg-white border border-line rounded-2xl p-6 shadow-panel flex flex-col gap-5 overflow-auto">
        <div class="flex items-center justify-between flex-none">
          <span class="text-[15px] font-bold">图纸上传</span>
          <span class="px-1.5 py-0.5 rounded text-[10px] bg-[#f4eee5] text-[#946c45]">PDF / PNG / CAD</span>
        </div>

        <div
          class="border border-dashed border-[#dbc4ad] rounded-xl bg-[#fffcf9] min-h-[160px] flex flex-col items-center justify-center gap-3 cursor-pointer transition hover:bg-[#fdf8f2] hover:border-bronze flex-none"
          @click="!uploadBusy && fileInput?.click()"
          @drop.prevent="onDrop"
          @dragover.prevent
        >
          <div class="w-12 h-12 rounded-full bg-[#f4e9dc] text-bronze grid place-items-center">
            <AppIcon name="upload" size="6" />
          </div>
          <strong class="text-sm">{{ store.files.length ? '点击或拖放以更换图纸' : '点击或拖放办公图纸到这里' }}</strong>
          <span class="text-xs text-muted">一次只能上传一张图纸 · 支持 PDF、PNG、JPG、DWG、DXF</span>
          <div class="flex gap-2 mt-1 flex-wrap justify-center">
            <span class="px-2.5 py-1.5 rounded-lg text-[11px] text-[#837265] bg-white border border-[#e8e1da] flex items-center gap-1">
              <AppIcon name="file" size="3.5" /> PDF
            </span>
            <span class="px-2.5 py-1.5 rounded-lg text-[11px] text-[#837265] bg-white border border-[#e8e1da] flex items-center gap-1">
              <AppIcon name="photo" size="3.5" /> 图片
            </span>
            <span class="px-2.5 py-1.5 rounded-lg text-[11px] text-[#837265] bg-white border border-[#e8e1da] flex items-center gap-1">
              <AppIcon name="cube" size="3.5" /> DWG/DXF
            </span>
          </div>
        </div>
        <input ref="fileInput" type="file" :disabled="uploadBusy" aria-label="上传一张办公图纸" accept=".pdf,.png,.jpg,.jpeg,.dwg,.dxf" hidden @change="onFileChange">

        <div v-if="uploadError" role="alert" class="rounded-xl border border-[#f5d8d8] bg-[#fff5f5] p-3 text-sm text-[#9e3a3a]">{{ uploadError }}</div>

        <!-- 已上传文件 -->
        <div v-if="store.files.length > 0" class="flex flex-col gap-2 flex-none max-h-[180px] overflow-auto">
          <div class="flex items-center justify-between">
            <strong class="text-sm">当前图纸</strong>
            <span class="text-xs text-muted">{{ store.files.length }} 个</span>
          </div>
          <div class="space-y-2">
            <div
              v-for="f in store.files"
              :key="f.name"
              class="flex items-center gap-3 p-3 rounded-xl border border-line hover:bg-[#faf8f5] transition group"
            >
              <div class="w-10 h-10 rounded-lg bg-[#f4f1ed] text-[#916e4c] grid place-items-center flex-none">
                <AppIcon :name="fileIcon(fileType(f.name))" size="5" />
              </div>
              <div class="flex-1 min-w-0">
                <div class="text-sm font-semibold truncate">{{ f.name }}</div>
                <div class="text-[10px] text-muted">{{ fileLabel(fileType(f.name)) }} · {{ fmtSize(f.size) }}</div>
              </div>
              <div class="flex items-center gap-1">
                <label v-if="fileType(f.name) === 'PDF' && !f.mock" class="flex items-center gap-1 mr-2 text-xs text-muted">
                  第
                  <input v-model.number="pdfPages[f.name]" :placeholder="'1'" type="number" min="1" step="1"
                    :disabled="uploadBusy" :aria-label="`${f.name} 识别页码`"
                    class="w-14 rounded border border-line px-2 py-1 bg-white text-ink" @click.stop>
                  页
                </label>
                <button
                  v-if="isRecognizable(f.name)"
                  class="px-3 py-1.5 rounded-lg text-xs font-semibold transition"
                  :class="canRecognize(f)
                    ? 'text-bronze-dark bg-cream hover:bg-[#ebe3d8]'
                    : 'text-muted bg-[#f4f4f4] cursor-not-allowed'"
                  :disabled="uploadBusy || !canRecognize(f)"
                  :title="f.mock ? '演示文件无法识别，请重新上传真实文件' : ''"
                  @click.stop="recognizeFile(f)"
                >
                  <span v-if="isParsing && activeFile === f.name">识别中…</span>
                  <span v-else>{{ recognizeLabel(f.name, f.mock) }}</span>
                </button>
                <button
                  class="w-7 h-7 rounded-lg hover:bg-[#f0e8dd] text-muted hover:text-[#9e3a3a] grid place-items-center transition"
                  :disabled="uploadBusy"
                  :aria-label="`移除 ${f.name}`"
                  @click.stop="removeFile(f.name)"
                >
                  <AppIcon name="close" size="3.5" />
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- 图纸识别结果 -->
        <div v-if="hasRecognizableFiles" ref="recognitionPanel" class="flex-none flex flex-col gap-4">
          <div class="flex items-center justify-between flex-none">
            <strong class="text-sm">图纸识别结果</strong>
            <span v-if="recognitionResult && !hasRunningResult" class="px-2 py-0.5 rounded-lg text-[10px] font-semibold" :class="statusClass(recognitionResult.parseStatus)">
              {{ statusText(recognitionResult.parseStatus) }}
            </span>
            <span v-else-if="isParsing || hasRunningResult" class="text-xs text-muted">后台处理中</span>
            <span v-else class="text-xs text-muted">选择文件点击「识别图纸」</span>
          </div>

          <RecognitionProgress v-if="isParsing || recognitionResult?.processing" :progress="recognitionResult?.processing" :pending="isParsing || hasRunningResult" />
          <button v-if="parseError && analysisId && recognitionResult?.status !== 'failed' && !isParsing" type="button" class="text-xs text-bronze underline self-start" @click="resumeRecognition">继续查询识别进度</button>

          <div v-if="parseError" role="alert" class="p-4 rounded-xl bg-[#fff5f5] border border-[#f5d8d8] text-sm text-[#9e3a3a] flex items-start gap-2">
            <AppIcon name="warning" size="4.5" class="shrink-0 mt-0.5" />
            <span>{{ parseError }}</span>
          </div>

          <div v-if="!recognitionResult && !parseError" class="flex-1 grid place-items-center text-center py-8 text-xs text-[#958f87] bg-[#faf9f7] rounded-xl border border-dashed border-line">
            <div>
              <AppIcon name="photo" size="8" class="mx-auto mb-2 text-muted" />
              上传 PDF/PNG 或 CAD 后点击识别<br>将解析房间、面积与空间布局
            </div>
          </div>

          <div v-if="recognitionResult" class="flex flex-col gap-4">
            <div v-if="requiresCalibration && !hasRunningResult && recognitionResult.status !== 'failed'" class="rounded-xl border border-line bg-cream p-3 text-xs text-bronze-dark">
              已识别图中空间候选，请核对轮廓；进入空间后先用「两点校准」标注一处已知尺寸的实际长度，校准前不计算实际尺寸与面积。
              <span v-if="recognitionResult.inputMode === 'pdf'">当前识别第 {{ recognitionResult.unitResolution.pageNumber }} 页，共 {{ recognitionResult.unitResolution.pageCount }} 页。</span>
            </div>
            <!-- 预览图 -->
            <div v-if="previewImage()" class="rounded-xl border border-line overflow-hidden bg-[#faf9f7]">
              <img :src="previewImage()" alt="图纸预览" class="w-full h-auto max-h-[260px] object-contain">
            </div>

            <template v-if="!hasRunningResult && recognitionResult.status !== 'failed'">
            <!-- 统计 -->
            <div class="grid grid-cols-3 gap-3">
              <div class="bg-[#f9f7f4] rounded-xl p-3.5">
                <div class="text-[10px] text-muted mb-1">识别房间</div>
                <div class="text-[20px] font-extrabold">{{ (recognitionResult.rooms?.length || 0) + (recognitionResult.unnamedRegions?.length || 0) }}</div>
              </div>
              <div class="bg-[#f9f7f4] rounded-xl p-3.5">
                <div class="text-[10px] text-muted mb-1">命名房间</div>
                <div class="text-[20px] font-extrabold text-bronze-dark">{{ (recognitionResult.rooms || []).filter((r: any) => r.label && r.label !== '未命名空间').length }}</div>
              </div>
              <div class="bg-[#f9f7f4] rounded-xl p-3.5">
                <div class="text-[10px] text-muted mb-1">总面积</div>
                <div v-if="requiresCalibration" class="text-[20px] font-extrabold text-muted">待校准</div>
                <div v-else class="text-[20px] font-extrabold text-green">{{ (recognitionResult.rooms || []).reduce((s: number, r: any) => s + (r.areaM2 || 0), 0).toFixed(1) }}㎡</div>
              </div>
            </div>

            <!-- 房间列表 -->
            <div>
              <div class="text-xs font-semibold text-[#6b6359] mb-2">房间明细</div>
              <div class="space-y-2">
                <div
                  v-for="(room, idx) in recognitionResult.rooms"
                  :key="room.roomId || idx"
                  class="p-3 rounded-xl border border-line hover:bg-[#faf8f5] transition"
                >
                  <div class="flex items-center justify-between mb-1">
                    <div class="flex items-center gap-2">
                      <span class="text-sm font-bold">{{ room.label || roomTypeText(room.roomType) }}</span>
                      <span v-if="room.roomType" class="px-1.5 py-0.5 rounded bg-[#f4eee6] text-bronze-dark text-[10px]">{{ roomTypeText(room.roomType) }}</span>
                    </div>
                    <span class="text-[10px] px-1.5 py-0.5 rounded" :class="(room.dimensionConfidence || room.confidence) === 'high' ? 'bg-[#e6f5ee] text-green' : 'bg-[#fff5e6] text-[#c97d1a]'">
                      {{ (room.dimensionConfidence || room.confidence) === 'high' ? '高置信' : '中低置信' }}
                    </span>
                  </div>
                  <div class="text-xs text-muted">
                    <span v-if="requiresCalibration">实际尺寸与面积待校准</span>
                    <span v-else>{{ room.widthMm?.toFixed(0) }} × {{ room.depthMm?.toFixed(0) }} mm · {{ room.areaM2?.toFixed(2) }}㎡</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- 语义已识别但墙网未闭合的候选 -->
            <div v-if="recognitionResult.unresolvedSpaces?.length" class="rounded-xl border border-[#f1d7ad] bg-[#fffaf1] p-3">
              <div class="text-xs font-semibold text-[#9a641f] mb-1">待补画空间（{{ recognitionResult.unresolvedSpaces.length }}）</div>
              <div class="text-xs text-[#8b6b42] mb-2">已识别房名或功能，但原图墙网没有形成唯一闭合轮廓，请在画布中补充边界。</div>
              <div class="flex flex-wrap gap-1.5">
                <span v-for="(candidate, idx) in recognitionResult.unresolvedSpaces" :key="idx" class="px-2 py-1 rounded-lg bg-white border border-[#f1d7ad] text-xs text-[#78572d]">
                  {{ candidate.label || roomTypeText(candidate.roomType || 'OTHER') }}
                </span>
              </div>
            </div>

            <!-- 质量问题 -->
            <div v-if="recognitionResult.qualityIssues?.length">
              <div class="text-xs font-semibold text-[#9e3a3a] mb-2">质量提示</div>
              <div class="space-y-2">
                <div
                  v-for="(issue, idx) in recognitionResult.qualityIssues"
                  :key="idx"
                  class="p-3 rounded-xl bg-[#fff8f8] border border-[#f5e0e0] text-xs"
                >
                  <span class="font-medium text-[#7a3a3a]">{{ issue.code }}</span>
                  <span class="text-[#9e6b6b] ml-2">{{ issue.message }}</span>
                </div>
              </div>
            </div>

            <!-- 应用到画布 -->
            <button
              v-if="recognitionResult.rooms?.length"
              type="button"
              class="mt-2 inline-flex w-full items-center justify-center gap-2 rounded-xl bg-bronze px-5 py-3 text-sm font-semibold text-white shadow-md shadow-[rgba(167,123,81,0.18)] transition hover:bg-bronze-dark"
              :disabled="uploadBusy"
              @click="applyRecognitionToCanvas"
            >
              <AppIcon name="map" size="4" />
              <span>应用到无限画布</span>
            </button>
            </template>
          </div>
        </div>

        <!-- 空状态 -->
        <div v-else class="flex-1 grid place-items-center text-center py-10 text-xs text-[#958f87] bg-[#faf9f7] rounded-xl border border-dashed border-line">
          <div>
            <AppIcon name="upload" size="8" class="mx-auto mb-2 text-muted" />
            暂无图纸<br>上传 PDF、PNG 或 CAD 文件开始识别空间布局
          </div>
        </div>
      </div>

    </div>

    <div class="bg-white border border-line rounded-xl p-3 flex items-center justify-between flex-none">
      <button class="px-4 py-2.5 rounded-lg text-xs font-semibold border border-line hover:bg-[#f7f3ef] flex items-center gap-2" :disabled="uploadBusy" @click="store.loadSample">
        <AppIcon name="reset" size="4" /> 恢复演示示例
      </button>
      <div class="flex gap-2.5">
        <button class="px-4 py-2.5 rounded-lg text-xs font-semibold border border-line hover:bg-[#f7f3ef]" :disabled="uploadBusy" @click="!uploadBusy && fileInput?.click()">
          <AppIcon name="upload" size="3" /> {{ store.files.length ? '更换图纸' : '上传图纸' }}
        </button>
        <button :disabled="!canOpenCurrentDrawing" :title="canOpenCurrentDrawing ? undefined : '请先识别当前图纸'" class="px-5 py-2.5 rounded-lg text-xs font-semibold text-white bg-bronze hover:bg-bronze-dark flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed" @click="enterCurrentDrawing">
          进入空间总览 <AppIcon name="arrowRight" size="4" />
        </button>
      </div>
    </div>
  </div>
</template>
