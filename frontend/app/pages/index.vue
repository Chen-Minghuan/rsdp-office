<script setup lang="ts">
import type { ProjectFile, ZoneObject, ToneKey, ZoneType } from '~/types/office'

const store = useOfficeStore()
const api = useOfficeApi()
const floorPlanApi = useFloorPlanApi()
const fileInput = ref<HTMLInputElement | null>(null)
const showProjectInfo = ref(true)
const isParsing = ref(false)
const parseError = ref<string | null>(null)
const recognitionResult = ref<any>(null)
const activeFile = ref<string | null>(null)
const recognitionSource = ref<'cad' | 'vision' | null>(null)
const analysisId = ref<string | null>(null)

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

watch(() => store.project, () => store.schedulePersist(), { deep: true })

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

async function registerFiles(list: FileList | null) {
  if (!list) return
  for (const f of Array.from(list)) {
    fileMap.value.set(f.name, f)
    await persistFile(f)
  }
}

async function onDrop(e: DragEvent) {
  const files = e.dataTransfer?.files || null
  await registerFiles(files)
  store.addFiles(files)
}

async function onFileChange(e: Event) {
  const files = (e.target as HTMLInputElement).files
  await registerFiles(files)
  store.addFiles(files)
  if (fileInput.value) fileInput.value.value = ''
}

async function removeFile(name: string) {
  store.removeFile(name)
  fileMap.value.delete(name)
  await deletePersistedFile(name)
  if (activeFile.value === name) {
    activeFile.value = null
    recognitionResult.value = null
    recognitionSource.value = null
    parseError.value = null
  }
}

const recognizableFiles = computed(() => store.files.filter((f) => isRecognizable(f.name)))
const hasRecognizableFiles = computed(() => recognizableFiles.value.length > 0)

async function recognizeFile(file: ProjectFile) {
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
    const result = await floorPlanApi.analyzeFloorPlan({
      image: isCad ? undefined : rawFile,
      cad: isCad ? rawFile : undefined,
      projectId: api.projectId,
      sourceName: file.name,
    })
    recognitionSource.value = isCad ? 'cad' : 'vision'
    analysisId.value = result.analysisId
    recognitionResult.value = result

    // 立即拉取一次详情展示
    const detail = await floorPlanApi.getFloorPlan(result.analysisId)
    recognitionResult.value = detail
  } catch (e: any) {
    parseError.value = e?.message || '图纸识别失败'
  } finally {
    isParsing.value = false
  }
}

function previewImage() {
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

  // 拉取最新详情，确保 rooms 已写入
  const detail = await floorPlanApi.getFloorPlan(analysisId.value)
  store.setFloorPlan(detail)
  store.importFloorPlanToZones()

  navigateTo(`/canvas?analysisId=${analysisId.value}&mode=overview`)
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

    <div class="flex-1 min-h-0 grid grid-cols-[minmax(420px,1fr)_minmax(320px,0.7fr)] gap-4">
      <!-- 左侧上传与识别结果 -->
      <div class="bg-white border border-line rounded-2xl p-6 shadow-panel flex flex-col gap-5 overflow-hidden">
        <div class="flex items-center justify-between flex-none">
          <span class="text-[15px] font-bold">图纸上传</span>
          <span class="px-1.5 py-0.5 rounded text-[10px] bg-[#f4eee5] text-[#946c45]">PDF / PNG / CAD</span>
        </div>

        <div
          class="border border-dashed border-[#dbc4ad] rounded-xl bg-[#fffcf9] min-h-[160px] flex flex-col items-center justify-center gap-3 cursor-pointer transition hover:bg-[#fdf8f2] hover:border-bronze flex-none"
          @click="fileInput?.click()"
          @drop.prevent="onDrop"
          @dragover.prevent
        >
          <div class="w-12 h-12 rounded-full bg-[#f4e9dc] text-bronze grid place-items-center">
            <AppIcon name="upload" size="6" />
          </div>
          <strong class="text-sm">点击或拖放办公图纸到这里</strong>
          <span class="text-xs text-muted">支持 PDF、PNG、JPG、DWG、DXF</span>
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
        <input ref="fileInput" type="file" multiple accept=".pdf,.png,.jpg,.jpeg,.dwg,.dxf" hidden @change="onFileChange">

        <!-- 已上传文件 -->
        <div v-if="store.files.length > 0" class="flex flex-col gap-2 flex-none max-h-[180px] overflow-auto">
          <div class="flex items-center justify-between">
            <strong class="text-sm">已上传文件</strong>
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
                <button
                  v-if="isRecognizable(f.name)"
                  class="px-3 py-1.5 rounded-lg text-xs font-semibold transition"
                  :class="canRecognize(f)
                    ? 'text-bronze-dark bg-cream hover:bg-[#ebe3d8]'
                    : 'text-muted bg-[#f4f4f4] cursor-not-allowed'"
                  :disabled="isParsing || !canRecognize(f)"
                  :title="f.mock ? '演示文件无法识别，请重新上传真实文件' : ''"
                  @click.stop="recognizeFile(f)"
                >
                  <span v-if="isParsing && activeFile === f.name">识别中…</span>
                  <span v-else>{{ recognizeLabel(f.name, f.mock) }}</span>
                </button>
                <button
                  class="w-7 h-7 rounded-lg hover:bg-[#f0e8dd] text-muted hover:text-[#9e3a3a] grid place-items-center transition"
                  @click.stop="removeFile(f.name)"
                >
                  <AppIcon name="close" size="3.5" />
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- 图纸识别结果 -->
        <div v-if="hasRecognizableFiles" class="flex-1 min-h-0 overflow-auto flex flex-col gap-4">
          <div class="flex items-center justify-between flex-none">
            <strong class="text-sm">图纸识别结果</strong>
            <span v-if="recognitionResult" class="px-2 py-0.5 rounded-lg text-[10px] font-semibold" :class="statusClass(recognitionResult.parseStatus)">
              {{ statusText(recognitionResult.parseStatus) }}
            </span>
            <span v-else class="text-xs text-muted">选择文件点击「识别图纸」</span>
          </div>

          <div v-if="parseError" class="p-4 rounded-xl bg-[#fff5f5] border border-[#f5d8d8] text-sm text-[#9e3a3a] flex items-start gap-2">
            <AppIcon name="warning" size="4.5" class="shrink-0 mt-0.5" />
            <span>{{ parseError }}</span>
          </div>

          <div v-else-if="!recognitionResult" class="flex-1 grid place-items-center text-center py-8 text-xs text-[#958f87] bg-[#faf9f7] rounded-xl border border-dashed border-line">
            <div>
              <AppIcon name="photo" size="8" class="mx-auto mb-2 text-muted" />
              上传 PDF/PNG 或 CAD 后点击识别<br>将解析房间、面积与空间布局
            </div>
          </div>

          <div v-else class="flex flex-col gap-4">
            <!-- 预览图 -->
            <div v-if="previewImage()" class="rounded-xl border border-line overflow-hidden bg-[#faf9f7]">
              <img :src="previewImage()" alt="图纸预览" class="w-full h-auto max-h-[260px] object-contain">
            </div>

            <!-- 统计 -->
            <div class="grid grid-cols-3 gap-3">
              <div class="bg-[#f9f7f4] rounded-xl p-3.5">
                <div class="text-[10px] text-muted mb-1">识别房间</div>
                <div class="text-[20px] font-extrabold">{{ (recognitionResult.rooms?.length || 0) + (recognitionResult.unnamedRegions?.length || 0) }}</div>
              </div>
              <div class="bg-[#f9f7f4] rounded-xl p-3.5">
                <div class="text-[10px] text-muted mb-1">命名房间</div>
                <div class="text-[20px] font-extrabold text-bronze-dark">{{ recognitionResult.rooms?.length || 0 }}</div>
              </div>
              <div class="bg-[#f9f7f4] rounded-xl p-3.5">
                <div class="text-[10px] text-muted mb-1">总面积</div>
                <div class="text-[20px] font-extrabold text-green">{{ (recognitionResult.rooms || []).reduce((s: number, r: any) => s + (r.areaM2 || 0), 0).toFixed(1) }}㎡</div>
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
                    <span class="text-[10px] px-1.5 py-0.5 rounded" :class="room.confidence === 'high' ? 'bg-[#e6f5ee] text-green' : 'bg-[#fff5e6] text-[#c97d1a]'">
                      {{ room.confidence === 'high' ? '高置信' : '中低置信' }}
                    </span>
                  </div>
                  <div class="text-xs text-muted">
                    {{ room.widthMm?.toFixed(0) }} × {{ room.depthMm?.toFixed(0) }} mm · {{ room.areaM2?.toFixed(2) }}㎡
                  </div>
                </div>
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
              @click="applyRecognitionToCanvas"
            >
              <AppIcon name="map" size="4" />
              <span>应用到无限画布</span>
            </button>
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

      <!-- 右侧项目信息 -->
      <div class="bg-white border border-line rounded-2xl p-6 shadow-panel flex flex-col gap-5 overflow-hidden">
        <button class="flex items-center justify-between w-full" @click="showProjectInfo = !showProjectInfo">
          <span class="text-[15px] font-bold">项目信息</span>
          <AppIcon :name="showProjectInfo ? 'chevronDown' : 'arrowRight'" size="4" class="text-muted" />
        </button>

        <div v-show="showProjectInfo" class="flex flex-col gap-4 overflow-auto">
          <div class="bg-[#f6f0e8] border border-[#efe5d7] rounded-lg p-3.5 text-xs text-[#8b7055] leading-relaxed flex items-start gap-2">
            <AppIcon name="info" size="4" class="mt-0.5 flex-none" />
            <span>填写项目信息后，识别结果将自动关联到空间总览。</span>
          </div>

          <div class="flex flex-col gap-1.5">
            <label class="text-xs text-[#746c64] font-medium">项目名称</label>
            <input v-model="store.project.name" class="h-10 px-3 border border-[#e9e4de] rounded-lg outline-none focus:border-[#be9369]" placeholder="例如：星河科技总部办公空间">
          </div>

          <div class="grid grid-cols-2 gap-4">
            <div class="flex flex-col gap-1.5">
              <label class="text-xs text-[#746c64] font-medium">建筑面积</label>
              <input v-model.number="store.project.area" type="number" min="0" class="h-10 px-3 border border-[#e9e4de] rounded-lg outline-none focus:border-[#be9369]" placeholder="如 800">
            </div>
            <div class="flex flex-col gap-1.5">
              <label class="text-xs text-[#746c64] font-medium">预计人数</label>
              <input v-model.number="store.project.headcount" type="number" min="0" class="h-10 px-3 border border-[#e9e4de] rounded-lg outline-none focus:border-[#be9369]" placeholder="如 60">
            </div>
            <div class="flex flex-col gap-1.5">
              <label class="text-xs text-[#746c64] font-medium">预算（元）</label>
              <input v-model.number="store.project.budget" type="number" min="0" class="h-10 px-3 border border-[#e9e4de] rounded-lg outline-none focus:border-[#be9369]" placeholder="如 300000">
            </div>
            <div class="flex flex-col gap-1.5">
              <label class="text-xs text-[#746c64] font-medium">整体偏好</label>
              <select v-model="store.project.style" class="h-10 px-3 border border-[#e9e4de] rounded-lg outline-none focus:border-[#be9369]">
                <option>现代简约</option>
                <option>现代商务</option>
                <option>自然原木</option>
                <option>工业风</option>
                <option>暂未确定</option>
              </select>
            </div>
          </div>

          <div class="flex flex-col gap-1.5">
            <label class="text-xs text-[#746c64] font-medium">项目说明</label>
            <textarea v-model="store.project.description" rows="3" class="w-full border border-[#e9e4de] rounded-lg p-3 outline-none focus:border-[#be9369] resize-none leading-relaxed" placeholder="可描述会议室、接待区、工位配置等需求" />
          </div>
        </div>

        <div class="mt-auto text-[11px] text-[#a29b94] leading-relaxed">
          演示数据仅保存在当前浏览器中。正式版需接入 RSDP 项目、鉴权和数据库服务。
        </div>
      </div>
    </div>

    <div class="bg-white border border-line rounded-xl p-3 flex items-center justify-between flex-none">
      <button class="px-4 py-2.5 rounded-lg text-xs font-semibold border border-line hover:bg-[#f7f3ef] flex items-center gap-2" @click="store.loadSample">
        <AppIcon name="reset" size="4" /> 恢复演示示例
      </button>
      <div class="flex gap-2.5">
        <button class="px-4 py-2.5 rounded-lg text-xs font-semibold border border-line hover:bg-[#f7f3ef]" @click="fileInput?.click()">
          <AppIcon name="plus" size="3" /> 继续上传
        </button>
        <NuxtLink to="/canvas" class="px-5 py-2.5 rounded-lg text-xs font-semibold text-white bg-bronze hover:bg-bronze-dark flex items-center gap-2">
          进入空间总览 <AppIcon name="arrowRight" size="4" />
        </NuxtLink>
      </div>
    </div>
  </div>
</template>
