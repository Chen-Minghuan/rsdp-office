<script setup lang="ts">
import { onBeforeRouteLeave } from 'vue-router'
import type { ProductImportResult, ProductImportPreview, ProductImportPreviewRow, ProductImportColumn } from '~/types/imported-products'

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

const steps = ['上传文件', '预览编辑', '导入结果']
const hasPendingImport = computed(() => !!selectedFile.value && currentStep.value < 2)

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
  if (!file.size || file.size > 50 * 1024 * 1024) {
    setError(file.size ? '文件大小不能超过 50MB。' : '文件内容为空，请重新选择。')
    return
  }
  selectedFile.value = file
  preview.value = null
  importResult.value = null
  currentStep.value = 0
  expandedGroups.value = new Set()

  isPreviewLoading.value = true
  try {
    preview.value = await api.previewImport(file)
    currentStep.value = 1
    // 默认展开所有分组
    preview.value.rows.forEach(r => expandedGroups.value.add(r.groupKey))
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
    preview.value = await api.validateImportPreview(preview.value.rows, updateIfExists.value)
    expandedGroups.value = new Set(preview.value.rows.map(r => r.groupKey))
  } catch (error) {
    setError('校验失败，请检查数据后重试。', error instanceof Error ? error.message : '校验请求失败')
  } finally {
    isValidating.value = false
  }
}

async function startImport() {
  if (!preview.value || isImporting.value) return
  syncAllSharedFields()
  isImporting.value = true
  clearError()
  try {
    importResult.value = await api.confirmImport(preview.value.rows, updateIfExists.value)
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

const groupedRows = computed(() => {
  if (!preview.value) return []
  const map = new Map<string, ProductImportPreviewRow[]>()
  for (const row of preview.value.rows) {
    if (!map.has(row.groupKey)) map.set(row.groupKey, [])
    map.get(row.groupKey)!.push(row)
  }
  return Array.from(map.entries()).map(([groupKey, rows]) => ({
    groupKey,
    rows,
    action: rows[0]?.action || '',
    sharedFields: preview.value!.columns.filter(c => !isVariantField(c.key)),
    variantFields: preview.value!.columns.filter(c => isVariantField(c.key)),
  }))
})

function isVariantField(key: string) {
  return ['variantDisplayName', 'sizeCode', 'sizeText', 'colorCode', 'colorText',
    'materialCode', 'materialText', 'variantReferencePriceBand', 'variantProductLevel',
    'factoryPrice', 'moq', 'leadTimeDays'].includes(key)
}

function columnLabel(key: string) {
  return preview.value?.columns.find(c => c.key === key)?.label || key
}

function toggleGroup(groupKey: string) {
  if (expandedGroups.value.has(groupKey)) expandedGroups.value.delete(groupKey)
  else expandedGroups.value.add(groupKey)
}

function syncSharedFields(group: typeof groupedRows.value[0]) {
  const shared = group.sharedFields
  for (let i = 1; i < group.rows.length; i++) {
    for (const col of shared) {
      group.rows[i].fields[col.key] = group.rows[0].fields[col.key]
    }
  }
}

function syncAllSharedFields() {
  for (const group of groupedRows.value) syncSharedFields(group)
}

function toggleSkip(row: ProductImportPreviewRow) {
  row.skipped = !row.skipped
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
          <p class="text-xs text-muted">上传商品文件，预览并编辑后确认入库。</p>
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
          <p class="mb-5 text-xs leading-relaxed text-muted">支持 .xlsx / .xls / .csv，单次最多 500 行，不超过 50MB。</p>
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
          <div><dt class="mb-1 font-semibold">使用标准模板</dt><dd class="text-muted">保留模板中的列名，不要合并单元格。</dd></div>
          <div><dt class="mb-1 font-semibold">必填商品信息</dt><dd class="text-muted">商品名称必填；品类码为空时默认 OF。</dd></div>
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
            {{ skippedRowCount() }} 行已跳过
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
            <input v-model="updateIfExists" type="radio" :value="false" name="duplicate-policy" class="mt-1 accent-bronze">
            <span><strong class="font-medium">跳过已有商品</strong><span class="block text-xs text-muted">保留现有资料，仅导入新增商品。</span></span>
          </label>
          <label class="flex items-start gap-2 text-sm">
            <input v-model="updateIfExists" type="radio" :value="true" name="duplicate-policy" class="mt-1 accent-bronze">
            <span><strong class="font-medium">覆盖更新已有商品</strong><span class="block text-xs text-muted">按编码匹配并更新非空字段。</span></span>
          </label>
        </div>
      </fieldset>

      <!-- 清洗报告 -->
      <div v-if="preview.cleansingReport?.issuesFound" class="mb-5 rounded-lg bg-cream/50 p-4">
        <div class="mb-2 flex items-center gap-2 text-sm font-semibold text-bronze-dark">
          <AppIcon name="reset" size="4" />
          <span>数据清洗报告：共 {{ preview.cleansingReport.issuesFound }} 处自动修正</span>
        </div>
        <div class="max-h-[120px] overflow-auto space-y-1 text-xs text-muted">
          <div v-for="(issue, idx) in preview.cleansingReport.issues.slice(0, 5)" :key="idx">
            行 {{ issue.rowIndex }} · {{ issue.field }}：{{ issue.original }} → {{ issue.cleaned }}（{{ issue.message }}）
          </div>
          <div v-if="preview.cleansingReport.issues.length > 5">还有 {{ preview.cleansingReport.issues.length - 5 }} 条…</div>
        </div>
      </div>

      <!-- 分组表格 -->
      <div class="space-y-3">
        <div
          v-for="group in groupedRows"
          :key="group.groupKey"
          class="overflow-hidden rounded-xl border border-line"
        >
          <button
            type="button"
            class="flex w-full items-center justify-between bg-bg px-4 py-3 text-left"
            @click="toggleGroup(group.groupKey)"
          >
            <div class="flex items-center gap-3 text-sm">
              <AppIcon :name="expandedGroups.has(group.groupKey) ? 'chevronDown' : 'arrowRight'" size="4" class="text-muted" />
              <span class="font-semibold">{{ group.action }}</span>
              <span class="rounded bg-white px-2 py-0.5 text-xs text-muted">{{ group.rows.length }} 个规格</span>
              <span v-if="hasGroupError(group.rows)" class="rounded bg-red-50 px-2 py-0.5 text-xs text-red-700">存在错误</span>
            </div>
          </button>

          <div v-show="expandedGroups.has(group.groupKey)" class="p-4">
            <!-- 共享字段 -->
            <div class="mb-3 grid grid-cols-2 gap-3 md:grid-cols-4">
              <div v-for="col in group.sharedFields" :key="col.key" class="flex flex-col gap-1">
                <label class="text-[10px] text-muted">{{ col.label }}</label>
                <input
                  v-model="group.rows[0].fields[col.key]"
                  type="text"
                  class="rounded border border-line px-2 py-1.5 text-xs outline-bronze"
                  :placeholder="col.label"
                  @input="syncSharedFields(group)"
                >
              </div>
            </div>

            <!-- 规格明细 -->
            <div class="overflow-x-auto rounded-lg border border-line">
              <table class="w-full text-left text-xs">
                <thead class="bg-bg text-muted">
                  <tr>
                    <th class="px-3 py-2 font-medium">跳过</th>
                    <th class="px-3 py-2 font-medium">行号</th>
                    <th v-for="col in group.variantFields" :key="col.key" class="px-3 py-2 font-medium">{{ col.label }}</th>
                    <th class="px-3 py-2 font-medium">操作/状态</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in group.rows" :key="row.rowIndex" class="border-t border-line" :class="row.skipped ? 'bg-gray-50 text-muted' : ''">
                    <td class="px-3 py-2">
                      <input type="checkbox" :checked="row.skipped" class="accent-bronze" @change="toggleSkip(row)">
                    </td>
                    <td class="px-3 py-2">{{ row.rowIndex }}</td>
                    <td v-for="col in group.variantFields" :key="col.key" class="px-3 py-2">
                      <input
                        v-model="row.fields[col.key]"
                        type="text"
                        class="w-24 rounded border border-line px-2 py-1 outline-bronze sm:w-28"
                        :disabled="row.skipped"
                      >
                    </td>
                    <td class="px-3 py-2">
                      <span v-if="row.skipped" class="rounded bg-bg px-2 py-0.5 text-muted">已跳过</span>
                      <span v-else-if="row.errors.length" class="group relative inline-flex cursor-help items-center gap-1 rounded bg-red-50 px-2 py-0.5 text-red-700">
                        <AppIcon name="warning" size="3" /> {{ row.errors.length }} 个错误
                        <span class="absolute bottom-full left-0 z-10 hidden w-56 rounded-lg border border-line bg-white p-2 text-xs shadow-md group-hover:block">
                          {{ row.errors.join('；') }}
                        </span>
                      </span>
                      <span v-else class="rounded bg-green/10 px-2 py-0.5 text-green">有效</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>

      <div class="mt-6 flex flex-wrap items-center justify-between gap-3 border-t border-line pt-5">
        <button type="button" class="rounded-lg border border-line px-4 py-2.5 text-sm disabled:opacity-40" :disabled="isImporting" @click="resetImport">重新上传</button>
        <button
          type="button"
          class="inline-flex items-center gap-2 rounded-lg bg-bronze px-5 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark disabled:opacity-50"
          :disabled="isImporting || isValidating || invalidRowCount() > 0"
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
  </div>
</template>
