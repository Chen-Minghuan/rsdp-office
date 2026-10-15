<script setup lang="ts">
import { onBeforeRouteLeave } from 'vue-router'
import type { ProductImportResult, ProductImportPreview, ProductImportPreviewRow } from '~/types/imported-products'

const api = useOfficeApi()
const { addRecord, storageAvailable } = useProductImportHistory()
const fileInput = ref<HTMLInputElement | null>(null)
const selectedFile = ref<File | null>(null)
const isDragging = ref(false)
const isDownloading = ref(false)
const isPreviewLoading = ref(false)
const isValidating = ref(false)
const isImporting = ref(false)
const updateIfExists = ref(false)
const currentStep = ref(0)
const preview = ref<ProductImportPreview | null>(null)
const importResult = ref<ProductImportResult | null>(null)
const errorMessage = ref<string | null>(null)
const technicalError = ref<string | null>(null)
const expandedGroups = ref<Set<string>>(new Set())
const showReviewOnly = ref(false)
const needsValidation = ref(false)
const enlargedImage = ref<string | null>(null)

const primarySharedKeys = ['externalCode', 'productName', 'retailPrice', 'colorPrimaryName', 'description']
const primaryVariantKeys = ['sizeText', 'widthMm', 'depthMm', 'heightMm', 'colorText', 'factoryPrice',
  'packageCount', 'packageVolumeCbm', 'grossWeight']

const steps = ['上传文件', '预览编辑', '导入结果']
const hasPendingImport = computed(() => !!selectedFile.value && currentStep.value < 2)

function isSelectedImage(row: ProductImportPreviewRow, index: number) {
  return row.selectedEmbeddedImageIndexes == null || row.selectedEmbeddedImageIndexes.includes(index)
}

function primaryImageIndex(row: ProductImportPreviewRow) {
  if (row.primaryEmbeddedImageIndex != null && isSelectedImage(row, row.primaryEmbeddedImageIndex))
    return row.primaryEmbeddedImageIndex
  if (row.fields.primaryImageUrl?.trim()) return -1
  return Array.from({ length: row.embeddedImageCount || 0 }, (_, index) => index)
    .find(index => isSelectedImage(row, index)) ?? -1
}

function toggleImage(row: ProductImportPreviewRow, index: number) {
  const selected = row.selectedEmbeddedImageIndexes == null
    ? Array.from({ length: row.embeddedImageCount || 0 }, (_, value) => value)
    : [...row.selectedEmbeddedImageIndexes]
  row.selectedEmbeddedImageIndexes = selected.includes(index)
    ? selected.filter(value => value !== index)
    : [...selected, index].sort((a, b) => a - b)
  if (!isSelectedImage(row, row.primaryEmbeddedImageIndex ?? -1)) row.primaryEmbeddedImageIndex = null
  syncSourceImageSelection(row)
  needsValidation.value = true
}

function syncSourceImageSelection(row: ProductImportPreviewRow) {
  if (!preview.value || (row.sourceVariantCount || 0) < 2) return
  for (const peer of preview.value.rows) {
    if (peer === row || peer.sourceSheetIndex !== row.sourceSheetIndex || peer.sourceRowIndex !== row.sourceRowIndex) continue
    peer.selectedEmbeddedImageIndexes = row.selectedEmbeddedImageIndexes == null ? null : [...row.selectedEmbeddedImageIndexes]
    peer.primaryEmbeddedImageIndex = row.primaryEmbeddedImageIndex
  }
}

function setPrimaryImage(row: ProductImportPreviewRow, index: number) {
  row.primaryEmbeddedImageIndex = index
  syncSourceImageSelection(row)
  needsValidation.value = true
}

function imageRows(rows: ProductImportPreviewRow[]) {
  const seen = new Set<string>()
  return rows.filter(row => {
    if ((row.sourceVariantCount || 0) < 2) return true
    const key = `${row.sourceSheetIndex}:${row.sourceRowIndex}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  return bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function setError(message: string, technical?: string) {
  errorMessage.value = message
  technicalError.value = technical || null
}

function clearError() {
  errorMessage.value = null
  technicalError.value = null
}

async function acceptFile(file?: File) {
  if (!file || isPreviewLoading.value) return
  clearError()
  if (!/\.(xlsx|xls|csv)$/i.test(file.name)) {
    setError('请选择 .xlsx、.xls 或 .csv 格式的文件。')
    return
  }
  if (!file.size || file.size > 150 * 1024 * 1024) {
    setError(file.size ? '文件大小不能超过 150MB。' : '文件内容为空，请重新选择。')
    return
  }
  selectedFile.value = file
  preview.value = null
  importResult.value = null
  currentStep.value = 0
  expandedGroups.value = new Set()
  showReviewOnly.value = false
  needsValidation.value = false
  enlargedImage.value = null

  isPreviewLoading.value = true
  try {
    preview.value = await api.previewImport(file)
    currentStep.value = 1
    if (preview.value.productCount <= 30) preview.value.rows.forEach(r => expandedGroups.value.add(r.groupKey))
  } catch (error) {
    setError('文件预览失败，请检查格式与表头。', error instanceof Error ? error.message : '预览请求失败')
  } finally {
    isPreviewLoading.value = false
  }
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  acceptFile(input.files?.[0])
  input.value = ''
}

function onDrop(event: DragEvent) {
  isDragging.value = false
  acceptFile(event.dataTransfer?.files[0])
}

function resetImport() {
  if (isImporting.value || isValidating.value) return
  selectedFile.value = null
  preview.value = null
  importResult.value = null
  clearError()
  updateIfExists.value = false
  currentStep.value = 0
  expandedGroups.value = new Set()
  showReviewOnly.value = false
  needsValidation.value = false
  enlargedImage.value = null
}

async function downloadTemplate() {
  if (isDownloading.value) return
  isDownloading.value = true
  clearError()
  try {
    const blob = await api.downloadImportTemplate()
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = '办公产品导入模板.xlsx'
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    setError('模板暂时无法下载，请检查服务连接后重试。', error instanceof Error ? error.message : '模板下载请求失败')
  } finally {
    isDownloading.value = false
  }
}

async function validatePreview() {
  if (!preview.value || isValidating.value) return
  syncAllSharedFields()
  isValidating.value = true
  clearError()
  try {
    const imagePreviews = new Map(preview.value.rows.map(row => [row.rowIndex, {
      sheet: row.sourceSheetIndex, sourceRow: row.sourceRowIndex, images: row.imagePreviews,
    }]))
    const validated = await api.validateImportPreview(preview.value.rows, updateIfExists.value)
    for (const row of validated.rows) {
      const previous = imagePreviews.get(row.rowIndex)
      if (previous?.sheet === row.sourceSheetIndex && previous?.sourceRow === row.sourceRowIndex)
        row.imagePreviews = previous.images
    }
    preview.value = validated
    needsValidation.value = false
    expandedGroups.value = preview.value.productCount <= 30 ? new Set(preview.value.rows.map(r => r.groupKey)) : new Set()
  } catch (error) {
    setError('校验失败，请检查数据后重试。', error instanceof Error ? error.message : '校验请求失败')
  } finally {
    isValidating.value = false
  }
}

async function startImport() {
  if (!preview.value || isImporting.value || needsValidation.value || invalidRowCount() > 0) return
  syncAllSharedFields()
  isImporting.value = true
  clearError()
  try {
    importResult.value = await api.confirmImport(preview.value.rows, updateIfExists.value, selectedFile.value || undefined)
    currentStep.value = 2
    addRecord({
      fileName: selectedFile.value?.name || 'unknown',
      updateIfExists: updateIfExists.value,
      result: importResult.value,
      error: null,
    })
  } catch (error) {
    setError(
      '导入请求未完成，请检查服务连接。重试前建议查看商品库，确认是否已有数据写入。',
      error instanceof Error ? error.message : '导入确认请求失败',
    )
    addRecord({
      fileName: selectedFile.value?.name || 'unknown',
      updateIfExists: updateIfExists.value,
      result: null,
      error: error instanceof Error ? error.message : '导入确认请求失败',
    })
  } finally {
    isImporting.value = false
  }
}

const allGroups = computed(() => {
  if (!preview.value) return []
  const map = new Map<string, ProductImportPreviewRow[]>()
  for (const row of preview.value.rows) {
    if (!map.has(row.groupKey)) map.set(row.groupKey, [])
    map.get(row.groupKey)!.push(row)
  }
  return Array.from(map.entries()).map(([groupKey, rows]) => {
    const coverRow = rows.find(row => row.imagePreviews?.some((_, index) => isSelectedImage(row, index)))
    return ({
    groupKey,
    rows,
    editRow: rows.find(row => !row.skipped) || rows[0],
    action: (rows.find(row => !row.skipped) || rows[0])?.action || '',
    coverImage: coverRow ? coverRow.imagePreviews?.[Math.max(0, primaryImageIndex(coverRow))] : undefined,
    sharedFields: preview.value!.columns.filter(c => !isVariantField(c.key)),
    variantFields: preview.value!.columns.filter(c => isVariantField(c.key)),
    primarySharedFields: preview.value!.columns.filter(c => primarySharedKeys.includes(c.key)),
    extraSharedFields: preview.value!.columns.filter(c => !isVariantField(c.key) && !primarySharedKeys.includes(c.key)
      && rows.some(r => String(r.fields[c.key] || '').trim())),
    primaryVariantFields: preview.value!.columns.filter(c => primaryVariantKeys.includes(c.key)),
    extraVariantFields: preview.value!.columns.filter(c => isVariantField(c.key) && !primaryVariantKeys.includes(c.key)
      && rows.some(r => String(r.fields[c.key] || '').trim())),
    })
  })
})

const groupedRows = computed(() => showReviewOnly.value
  ? allGroups.value.filter(group => group.rows.some(row => !row.skipped &&
    ((row.reviewWarnings?.length || 0) > 0 || (row.imageWarnings?.length || 0) > 0)))
  : allGroups.value)

function isVariantField(key: string) {
  return ['variantDisplayName', 'sizeCode', 'sizeText', 'widthMm', 'depthMm', 'heightMm',
    'packageCount', 'packageVolumeCbm', 'grossWeight', 'colorCode', 'colorText',
    'materialCode', 'materialText',
    'factoryPrice', 'moq', 'leadTimeDays'].includes(key)
}

function toggleGroup(groupKey: string) {
  if (expandedGroups.value.has(groupKey)) expandedGroups.value.delete(groupKey)
  else expandedGroups.value.add(groupKey)
}

function syncSharedFields(group: typeof groupedRows.value[0]) {
  const shared = group.sharedFields
  for (const row of group.rows) {
    if (row === group.editRow) continue
    for (const col of shared) {
      row.fields[col.key] = group.editRow.fields[col.key]
    }
  }
}

function onSharedInput(group: typeof groupedRows.value[0]) {
  syncSharedFields(group)
  needsValidation.value = true
}

function syncAllSharedFields() {
  for (const group of allGroups.value) syncSharedFields(group)
}

function toggleSkip(row: ProductImportPreviewRow) {
  row.skipped = !row.skipped
  needsValidation.value = true
}

function onlyImportGroup(groupKey: string) {
  if (!preview.value) return
  for (const row of preview.value.rows) row.skipped = row.groupKey !== groupKey
  updateIfExists.value = true
  showReviewOnly.value = false
  expandedGroups.value = new Set([groupKey])
  needsValidation.value = true
}

function hasGroupError(rows: ProductImportPreviewRow[]) {
  return rows.some(r => !r.skipped && r.errors.length)
}

function invalidRowCount() {
  return preview.value?.rows.filter(r => !r.skipped && r.errors.length).length || 0
}

function skippedRowCount() {
  return preview.value?.rows.filter(r => r.skipped).length || 0
}

const dimensionReadyCount = computed(() => preview.value?.rows.filter(r => !r.skipped && !r.errors.length &&
  Number(r.fields.widthMm) > 0 && Number(r.fields.depthMm) > 0).length || 0)
const reviewRowCount = computed(() => preview.value?.rows.filter(r => !r.skipped && (r.reviewWarnings?.length || r.imageWarnings?.length)).length || 0)

onBeforeRouteLeave(() => {
  if (isImporting.value || isValidating.value) {
    setError('正在处理导入，请等待完成后再离开。')
    return false
  }
  if (hasPendingImport.value) return window.confirm('当前导入尚未完成，离开后需要重新选择文件。确定离开吗？')
})

function beforeUnload(event: BeforeUnloadEvent) {
  if (!hasPendingImport.value && !isImporting.value && !isValidating.value) return
  event.preventDefault()
  event.returnValue = ''
}

onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>

<template>
  <div class="flex h-full min-h-0 flex-col gap-5 overflow-y-auto">
    <div class="flex-none">
      <div class="mb-3 text-xs text-muted">
        <NuxtLink to="/products" class="hover:text-bronze-dark">商品资源 / 商品库</NuxtLink> / 批量导入
      </div>
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 class="mb-2 text-[25px] font-bold tracking-tight">批量导入商品</h1>
          <p class="text-xs text-muted">上传商品文件，预览并编辑后确认入库；Excel 内嵌图片会自动关联商品。</p>
        </div>
        <NuxtLink to="/products" class="inline-flex items-center gap-2 rounded-lg border border-line bg-white px-4 py-2.5 text-sm hover:bg-cream">
          <AppIcon name="arrowLeft" size="4" />返回商品库
        </NuxtLink>
      </div>
    </div>

    <ol class="grid flex-none grid-cols-3 gap-2 rounded-xl border border-line bg-white p-3 sm:p-4" aria-label="商品导入步骤">
      <li
        v-for="(step, index) in steps"
        :key="step"
        class="flex items-center justify-center gap-2 text-xs sm:text-sm"
        :class="index === currentStep ? 'font-semibold text-bronze-dark' : 'text-muted'"
        :aria-current="index === currentStep ? 'step' : undefined"
      >
        <span class="grid h-7 w-7 shrink-0 place-items-center rounded-full" :class="index === currentStep ? 'bg-bronze text-white' : index < currentStep ? 'bg-green/10 text-green' : 'bg-bg'">
          <AppIcon v-if="index < currentStep" name="check" size="4" />
          <span v-else>{{ index + 1 }}</span>
        </span>
        <span>{{ step }}</span>
      </li>
    </ol>

    <div v-if="errorMessage" class="flex-none rounded-xl border border-red-100 bg-red-50 p-4 text-sm text-red-800" role="alert">
      <p>{{ errorMessage }}</p>
      <details v-if="technicalError" class="mt-2 text-xs">
        <summary class="cursor-pointer">技术详情</summary>
        <p class="mt-2 break-all">{{ technicalError }}</p>
      </details>
    </div>

    <!-- 步骤 1：上传文件 -->
    <div v-if="currentStep === 0" class="grid flex-none gap-5 xl:grid-cols-[minmax(0,1fr)_280px]">
      <section class="rounded-xl border border-line bg-white p-5 shadow-panel sm:p-6" aria-label="上传商品文件">
        <div class="mb-5 flex flex-wrap items-center justify-between gap-3">
          <h2 class="text-base font-semibold">上传数据文件</h2>
          <button type="button" class="inline-flex items-center gap-2 rounded-lg bg-cream px-3 py-2 text-xs font-semibold text-bronze-dark disabled:opacity-50" :disabled="isDownloading" @click="downloadTemplate">
            <AppIcon :name="isDownloading ? 'reset' : 'inbox'" size="4" :class="{ 'animate-spin': isDownloading }" />
            {{ isDownloading ? '正在下载…' : '下载 Excel 模板' }}
          </button>
        </div>
        <div
          class="rounded-xl border border-dashed p-5 text-center transition sm:p-8"
          :class="isDragging ? 'border-bronze bg-cream' : 'border-[#dbc4ad] bg-[#fffcf9]'"
          @drop.prevent="onDrop"
          @dragover.prevent="isDragging = true"
          @dragleave.prevent="isDragging = false"
        >
          <div class="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-xl bg-cream text-bronze">
            <AppIcon name="upload" size="6" />
          </div>
          <h3 class="mb-2 text-sm font-semibold">拖放文件到这里，或点击选择文件</h3>
          <p class="mb-5 text-xs leading-relaxed text-muted">支持 .xlsx / .xls / .csv，单次最多 500 行，不超过 150MB。内嵌图片支持 .xlsx。</p>
          <button type="button" class="rounded-lg border border-line bg-white px-4 py-2 text-sm font-semibold text-bronze-dark hover:bg-cream" @click="fileInput?.click()">
            选择文件
          </button>
          <input ref="fileInput" type="file" accept=".xlsx,.xls,.csv" class="hidden" aria-label="选择商品数据文件" @change="onFileChange">
        </div>
        <div v-if="isPreviewLoading" class="mt-4 flex items-center gap-2 text-sm text-muted">
          <AppIcon name="reset" size="4" class="animate-spin" /> 正在解析文件…
        </div>
      </section>

      <aside class="self-start rounded-xl border border-line bg-white p-5" aria-label="导入填写说明">
        <h2 class="mb-4 text-sm font-semibold">填写说明</h2>
        <dl class="space-y-4 text-xs leading-relaxed">
          <div><dt class="mb-1 font-semibold">文件格式</dt><dd class="text-muted">支持标准模板和“ITEM NO. / ITEM NAME”供应商工作簿；预览时核对字段与内嵌图片。</dd></div>
          <div><dt class="mb-1 font-semibold">规格尺寸</dt><dd class="text-muted">该供应商表按毫米解析。明确的双宽度规格会展开成两个尺寸；其他不确定写法保留原文并提示复核。</dd></div>
          <div><dt class="mb-1 font-semibold">价格与包装</dt><dd class="text-muted">J 列是展示价格，L 列是出厂价；G/H/I 列是包数、体积（CBM）和重量原表值。包数不是商品数量。</dd></div>
          <div><dt class="mb-1 font-semibold">必填商品信息</dt><dd class="text-muted">商品名称必填；规格尺寸和价格建议在预览中核对。</dd></div>
          <div><dt class="mb-1 font-semibold">唯一识别编码</dt><dd class="text-muted">外部编码用于识别重复商品；同一文件内请勿重复。</dd></div>
          <div><dt class="mb-1 font-semibold">多规格</dt><dd class="text-muted">同一外部编码的多行归为一个商品，尺寸/颜色/材质不同则建立不同规格。</dd></div>
        </dl>
      </aside>
    </div>

    <!-- 步骤 2：预览编辑 -->
    <div v-else-if="currentStep === 1 && preview" class="flex-none rounded-xl border border-line bg-white p-5 shadow-panel sm:p-6">
      <div class="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 class="text-base font-semibold">预览并确认导入数据</h2>
          <p class="mt-1 text-xs text-muted">
            共 {{ preview.rows.length }} 行 · {{ preview.productCount }} 个商品 ·
            <span :class="invalidRowCount() ? 'text-red-700' : 'text-green'">{{ invalidRowCount() }} 行有问题</span> ·
            {{ skippedRowCount() }} 行已跳过 · {{ dimensionReadyCount }} 行具备占地尺寸 · {{ reviewRowCount }} 行待复核 · {{ preview.rowsWithImages || 0 }} 行识别到内嵌图片
          </p>
        </div>
        <div class="flex items-center gap-2">
          <button type="button" class="rounded-lg border border-line px-3 py-2 text-xs hover:bg-cream" @click="resetImport">重新上传</button>
          <button
            type="button"
            class="inline-flex items-center gap-2 rounded-lg bg-cream px-3 py-2 text-xs font-semibold text-bronze-dark hover:bg-[#ebe3d8] disabled:opacity-50"
            :disabled="isValidating"
            @click="validatePreview"
          >
            <AppIcon :name="isValidating ? 'reset' : 'checkCircle'" size="4" :class="{ 'animate-spin': isValidating }" />
            {{ isValidating ? '校验中…' : '重新校验' }}
          </button>
        </div>
      </div>

      <!-- 重复策略 -->
      <fieldset :disabled="isImporting" class="mb-5 rounded-lg bg-bg p-4">
        <legend class="mb-2 text-sm font-semibold">发现重复商品时</legend>
        <div class="flex flex-wrap gap-4">
          <label class="flex items-start gap-2 text-sm">
            <input v-model="updateIfExists" type="radio" :value="false" name="duplicate-policy" class="mt-1 accent-bronze" @change="needsValidation = true">
            <span><strong class="font-medium">跳过已有商品</strong><span class="block text-xs text-muted">保留现有资料，仅导入新增商品。</span></span>
          </label>
          <label class="flex items-start gap-2 text-sm">
            <input v-model="updateIfExists" type="radio" :value="true" name="duplicate-policy" class="mt-1 accent-bronze" @change="needsValidation = true">
            <span><strong class="font-medium">覆盖更新已有商品</strong><span class="block text-xs text-muted">按编码匹配并更新非空字段。</span></span>
          </label>
        </div>
      </fieldset>

      <!-- 清洗报告 -->
      <details v-if="preview.cleansingReport?.issuesFound" class="mb-5 rounded-lg bg-cream/50 p-4">
        <summary class="cursor-pointer text-sm font-semibold text-bronze-dark">数据清洗报告 · {{ preview.cleansingReport.issuesFound }} 处自动修正</summary>
        <div class="mt-3 max-h-48 space-y-2 overflow-auto text-xs text-muted">
          <div v-for="(issue, idx) in preview.cleansingReport.issues" :key="idx" class="rounded-lg bg-white p-2">
            <span class="font-medium">行 {{ issue.rowIndex }} · {{ issue.field }}</span>
            <span class="ml-2">{{ issue.message }}</span>
            <p class="mt-1 truncate" :title="`${issue.original} → ${issue.cleaned}`">{{ issue.original }} → {{ issue.cleaned }}</p>
          </div>
        </div>
      </details>

      <div class="mb-3 flex items-center justify-between gap-2 text-xs">
        <span class="text-muted">{{ showReviewOnly ? `显示 ${groupedRows.length} 个待复核商品` : `显示全部 ${groupedRows.length} 个商品分组` }}</span>
        <button type="button" class="rounded-lg border border-line px-3 py-1.5 text-bronze-dark hover:bg-cream" @click="showReviewOnly = !showReviewOnly">
          {{ showReviewOnly ? '查看全部' : `只看待复核（${reviewRowCount} 行）` }}
        </button>
      </div>
      <div class="space-y-3">
        <section v-for="group in groupedRows" :key="group.groupKey" class="overflow-hidden rounded-xl border border-line bg-white">
          <div class="flex items-center bg-bg">
          <button type="button" class="flex min-w-0 flex-1 items-center gap-3 px-4 py-3 text-left hover:bg-cream/60" :aria-expanded="expandedGroups.has(group.groupKey)" @click="toggleGroup(group.groupKey)">
            <img v-if="group.coverImage" :src="group.coverImage" alt="商品图片预览" class="h-14 w-14 shrink-0 rounded-lg border border-line bg-white object-contain">
            <span v-else class="grid h-14 w-14 shrink-0 place-items-center rounded-lg border border-line bg-white text-[11px] text-muted">无图片</span>
            <span class="min-w-0 flex-1">
              <span class="block truncate text-sm font-semibold">{{ group.editRow.fields.productName || '未填写商品名称' }}</span>
              <span class="mt-1 block truncate text-xs text-muted">{{ group.editRow.fields.externalCode || '无外部编码' }} · {{ group.action }} · {{ group.rows.length }} 个规格</span>
            </span>
            <span v-if="hasGroupError(group.rows)" class="rounded bg-red-50 px-2 py-1 text-xs text-red-700">有错误</span>
            <span v-else-if="group.rows.some(row => !row.skipped && ((row.reviewWarnings?.length || 0) || (row.imageWarnings?.length || 0)))" class="rounded bg-amber-50 px-2 py-1 text-xs text-amber-700">待复核</span>
            <AppIcon :name="expandedGroups.has(group.groupKey) ? 'chevronDown' : 'arrowRight'" size="4" class="shrink-0 text-muted" />
          </button>
          <button type="button" class="mr-3 shrink-0 rounded-lg border border-line bg-white px-2 py-1.5 text-xs text-bronze-dark hover:bg-cream" @click="onlyImportGroup(group.groupKey)">仅处理此商品</button>
          </div>

          <div v-if="expandedGroups.has(group.groupKey)" class="space-y-5 p-4 sm:p-5">
            <p v-if="updateIfExists && group.rows.some(row => !row.skipped)" class="text-xs text-muted">更新已有商品会同步此处填写的商品资料、规格报价和已选图片；如只需补图，请先核对这些字段。</p>
            <div class="grid gap-5 xl:grid-cols-[minmax(0,1fr)_220px]">
              <div>
                <h3 class="mb-3 text-sm font-semibold">商品资料</h3>
                <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  <label v-for="col in group.primarySharedFields.filter(c => c.key !== 'description')" :key="col.key" class="flex min-w-0 flex-col gap-1 text-xs text-muted">
                    {{ col.label }}
                    <input v-model="group.editRow.fields[col.key]" :type="col.type === 'number' ? 'number' : 'text'" class="w-full rounded-lg border border-line bg-white px-3 py-2 text-sm text-ink outline-bronze" @input="onSharedInput(group)">
                  </label>
                </div>
                <label class="mt-3 flex flex-col gap-1 text-xs text-muted">描述 / 配置说明
                  <textarea v-model="group.editRow.fields.description" rows="3" class="w-full rounded-lg border border-line px-3 py-2 text-sm text-ink outline-bronze" @input="onSharedInput(group)" />
                </label>
                <details v-if="group.extraSharedFields.length" class="mt-3 rounded-lg border border-line bg-bg p-3">
                  <summary class="cursor-pointer text-xs font-medium text-bronze-dark">其他已填写商品字段（{{ group.extraSharedFields.length }}）</summary>
                  <div class="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                    <label v-for="col in group.extraSharedFields" :key="col.key" class="flex min-w-0 flex-col gap-1 text-xs text-muted">{{ col.label }}
                      <input v-model="group.editRow.fields[col.key]" :type="col.type === 'number' ? 'number' : 'text'" class="w-full rounded border border-line bg-white px-2 py-1.5 text-xs text-ink outline-bronze" @input="onSharedInput(group)">
                    </label>
                  </div>
                </details>
              </div>
              <div class="rounded-xl border border-line bg-bg p-3">
                <h3 class="mb-2 text-xs font-semibold">关联图片</h3>
                <p class="mb-2 text-xs text-muted">每个商品选择一张主图，其余已选图片作为详情图。合并图片区域的图片会列给覆盖的各商品，请核对是否都适用。</p>
                <div v-for="row in imageRows(group.rows)" :key="row.rowIndex" class="mb-3">
                  <p class="mb-1 text-[11px] font-medium text-ink">{{ row.fields.externalCode || `第 ${row.sourceRowIndex || row.rowIndex} 行` }}{{ row.sourceVariantCount && row.sourceVariantCount > 1 ? ` · ${row.sourceVariantCount} 个规格共用` : '' }} · 已选 {{ row.selectedEmbeddedImageIndexes?.length ?? row.embeddedImageCount ?? 0 }}/{{ row.embeddedImageCount || 0 }} 张图</p>
                  <div class="grid grid-cols-2 gap-2">
                    <div v-for="(src, index) in row.imagePreviews || []" :key="index" class="overflow-hidden rounded-lg border bg-white" :class="primaryImageIndex(row) === index ? 'border-bronze' : 'border-line'">
                      <button type="button" class="block w-full" :aria-label="`查看 ${row.fields.externalCode || row.sourceRowIndex} 图片 ${index + 1}`" @click="enlargedImage = src">
                        <img :src="src" :alt="`${row.fields.externalCode || row.sourceRowIndex} 图片 ${index + 1}`" class="h-24 w-full object-contain">
                      </button>
                      <div class="flex items-center justify-between gap-1 border-t border-line px-2 py-1 text-[11px]">
                        <label class="flex items-center gap-1 text-muted"><input type="checkbox" :checked="isSelectedImage(row, index)" @change="toggleImage(row, index)">使用</label>
                        <button type="button" :disabled="!isSelectedImage(row, index)" :class="primaryImageIndex(row) === index ? 'font-semibold text-bronze-dark' : 'text-muted disabled:opacity-40'" @click="setPrimaryImage(row, index)">
                          {{ primaryImageIndex(row) === index ? '主图' : '设为主图' }}
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
                <p v-if="!group.rows.some(row => row.imagePreviews?.length)" class="rounded-lg border border-dashed border-line bg-white p-3 text-xs text-muted">暂无可展示图片，请核对原表。</p>
                <p class="mt-2 text-[11px] leading-relaxed text-muted">此处为缩略预览；确认导入时会从原文件提取图片。</p>
              </div>
            </div>

            <div>
              <h3 class="mb-3 text-sm font-semibold">规格与来源行</h3>
              <div class="space-y-3">
                <div v-for="row in group.rows" :key="row.rowIndex" class="rounded-xl border border-line p-3 sm:p-4" :class="row.skipped ? 'bg-gray-50 opacity-70' : ''">
                  <div class="mb-3 flex flex-wrap items-center gap-2 text-xs">
                    <label class="flex items-center gap-2 font-medium"><input type="checkbox" :checked="row.skipped" class="accent-bronze" @change="toggleSkip(row)">跳过此规格</label>
                    <span class="text-muted">{{ row.sourceSheetName ? `${row.sourceSheetName} · 第 ${row.sourceRowIndex} 行` : `第 ${row.rowIndex} 行` }}</span>
                    <span v-if="row.sourceVariantCount && row.sourceVariantCount > 1" class="rounded bg-cream px-2 py-0.5 text-bronze-dark">拆分规格 {{ row.fields.sizeText }}</span>
                    <span v-if="row.embeddedImageCount" class="rounded bg-green/10 px-2 py-0.5 text-green">{{ row.embeddedImageCount }} 张图</span>
                    <span v-if="row.skipped" class="rounded bg-bg px-2 py-0.5 text-muted">已跳过</span>
                    <span v-else-if="row.errors.length" class="rounded bg-red-50 px-2 py-0.5 text-red-700">{{ row.errors.length }} 个错误</span>
                    <span v-else-if="row.reviewWarnings?.length" class="rounded bg-amber-50 px-2 py-0.5 text-amber-700">待核对</span>
                    <span v-else class="rounded bg-green/10 px-2 py-0.5 text-green">可导入</span>
                  </div>
                  <div v-if="row.errors.length || row.reviewWarnings?.length || row.imageWarnings?.length" class="mb-3 space-y-1 rounded-lg bg-amber-50 p-3 text-xs leading-relaxed">
                    <p v-for="message in row.errors" :key="message" class="text-red-700">{{ message }}</p>
                    <p v-for="message in row.reviewWarnings || []" :key="message" class="text-amber-800">{{ message }}</p>
                    <p v-for="message in row.imageWarnings || []" :key="message" class="text-amber-800">{{ message }}</p>
                  </div>
                  <fieldset :disabled="row.skipped" class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
                    <label v-for="col in group.primaryVariantFields" :key="col.key" class="flex min-w-0 flex-col gap-1 text-xs text-muted">{{ col.label }}
                      <input v-model="row.fields[col.key]" :type="col.type === 'number' ? 'number' : 'text'" :min="['widthMm', 'depthMm', 'heightMm'].includes(col.key) ? 1 : undefined" class="w-full rounded-lg border border-line bg-white px-3 py-2 text-sm text-ink outline-bronze" @input="needsValidation = true">
                    </label>
                  </fieldset>
                  <details v-if="group.extraVariantFields.length" class="mt-3 rounded-lg bg-bg p-3">
                    <summary class="cursor-pointer text-xs font-medium text-bronze-dark">其他已填写规格字段（{{ group.extraVariantFields.length }}）</summary>
                    <fieldset :disabled="row.skipped" class="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                      <label v-for="col in group.extraVariantFields" :key="col.key" class="flex min-w-0 flex-col gap-1 text-xs text-muted">{{ col.label }}
                        <input v-model="row.fields[col.key]" :type="col.type === 'number' ? 'number' : 'text'" class="w-full rounded border border-line bg-white px-2 py-1.5 text-xs text-ink outline-bronze" @input="needsValidation = true">
                      </label>
                    </fieldset>
                  </details>
                </div>
              </div>
            </div>
          </div>
        </section>
      </div>

      <div class="mt-6 flex flex-wrap items-center justify-between gap-3 border-t border-line pt-5">
        <button type="button" class="rounded-lg border border-line px-4 py-2.5 text-sm disabled:opacity-40" :disabled="isImporting" @click="resetImport">重新上传</button>
        <span v-if="needsValidation" class="text-xs text-amber-700">数据已修改，请先点击“重新校验”</span>
        <button
          type="button"
          class="inline-flex items-center gap-2 rounded-lg bg-bronze px-5 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark disabled:opacity-50"
          :disabled="isImporting || isValidating || needsValidation || invalidRowCount() > 0"
          @click="startImport"
        >
          <AppIcon :name="isImporting ? 'reset' : 'import'" size="4" :class="{ 'animate-spin': isImporting }" />
          {{ isImporting ? '正在导入，请稍候…' : '确认并执行导入' }}
        </button>
      </div>
    </div>

    <!-- 步骤 3：导入结果 -->
    <div v-else-if="currentStep === 2 && importResult" class="flex-none rounded-xl border border-line bg-white p-5 shadow-panel sm:p-6">
      <div class="mb-5 text-xs text-muted">{{ selectedFile?.name }}</div>
      <ProductImportSummary :result="importResult" />

      <div v-if="importResult.cleansingReport?.issuesFound" class="mt-5 rounded-xl border border-line bg-bg p-4">
        <div class="mb-3 flex items-center gap-2 text-sm font-semibold">
          <AppIcon name="reset" size="4" class="text-bronze" />
          <span>数据清洗报告</span>
        </div>
        <p class="mb-3 text-xs text-muted">共清洗 {{ importResult.cleansingReport.cleanedRows }} 行，发现并修复 {{ importResult.cleansingReport.issuesFound }} 处问题。</p>
        <div class="max-h-[200px] overflow-auto space-y-2">
          <div v-for="(issue, idx) in importResult.cleansingReport.issues.slice(0, 10)" :key="idx" class="rounded-lg bg-white p-3 text-xs">
            <div class="flex items-center gap-2">
              <span class="shrink-0 rounded bg-cream px-1.5 py-0.5 text-bronze-dark">行 {{ issue.rowIndex }}</span>
              <span class="font-medium">{{ issue.field }}</span>
            </div>
            <div class="mt-1 text-muted">{{ issue.message }}</div>
          </div>
        </div>
      </div>

      <div v-if="importResult.failures.length" class="mt-5 rounded-xl border border-red-100 bg-red-50 p-4">
        <h3 class="mb-3 text-sm font-semibold text-red-800">失败明细</h3>
        <div class="max-h-[240px] overflow-auto space-y-2">
          <div v-for="(failure, idx) in importResult.failures" :key="idx" class="rounded-lg bg-white p-3 text-xs">
            <div class="flex items-center gap-2">
              <span class="shrink-0 rounded bg-red-50 px-1.5 py-0.5 text-red-700">行 {{ failure.rowIndex }}</span>
              <span class="font-medium">{{ failure.externalCode || failure.rspuId || '—' }}</span>
            </div>
            <div class="mt-1 text-red-700">{{ failure.reason }}</div>
          </div>
        </div>
      </div>

      <p v-if="!storageAvailable" class="mt-4 text-xs text-muted">浏览器未能保存导入记录，本次结果仅在当前会话中保留。</p>
      <div class="mt-6 flex flex-wrap items-center justify-between gap-3 border-t border-line pt-5">
        <button type="button" class="rounded-lg border border-line px-4 py-2.5 text-sm hover:bg-cream" @click="resetImport">继续导入</button>
        <div class="flex flex-wrap gap-3">
          <NuxtLink to="/products/import-records" class="rounded-lg border border-line px-4 py-2.5 text-sm hover:bg-cream">查看导入记录</NuxtLink>
          <NuxtLink to="/products/audit" class="rounded-lg bg-cream px-4 py-2.5 text-sm font-semibold text-bronze-dark hover:bg-[#ebe3d8]">去审核商品</NuxtLink>
          <NuxtLink to="/products" class="rounded-lg bg-bronze px-4 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark">查看商品库</NuxtLink>
        </div>
      </div>
    </div>
    <div v-if="enlargedImage" class="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-5" role="dialog" aria-modal="true" aria-label="商品图片预览" @click.self="enlargedImage = null">
      <div class="relative max-h-full max-w-3xl rounded-xl bg-white p-3 shadow-xl">
        <button type="button" class="absolute right-3 top-3 rounded-lg bg-white px-3 py-1.5 text-sm shadow" @click="enlargedImage = null">关闭</button>
        <img :src="enlargedImage" alt="放大的商品图片预览" class="max-h-[80vh] max-w-full object-contain">
      </div>
    </div>
  </div>
</template>
