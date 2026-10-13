<script setup lang="ts">
import { onBeforeRouteLeave } from 'vue-router'
import type { ProductImportResult } from '~/types/imported-products'

const api = useOfficeApi()
const { addRecord, storageAvailable } = useProductImportHistory()
const fileInput = ref<HTMLInputElement | null>(null)
const selectedFile = ref<File | null>(null)
const isDragging = ref(false)
const isImporting = ref(false)
const isDownloading = ref(false)
const updateIfExists = ref(false)
const currentStep = ref(0)
const importResult = ref<ProductImportResult | null>(null)
const errorMessage = ref<string | null>(null)
const technicalError = ref<string | null>(null)
const steps = ['上传文件', '确认导入', '导入结果']
const hasPendingImport = computed(() => !!selectedFile.value && !importResult.value)

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  return bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function acceptFile(file?: File) {
  if (!file || isImporting.value) return
  errorMessage.value = null
  technicalError.value = null
  if (!/\.(xlsx|xls|csv)$/i.test(file.name)) {
    errorMessage.value = '请选择 .xlsx、.xls 或 .csv 格式的文件。'
    return
  }
  if (!file.size || file.size > 50 * 1024 * 1024) {
    errorMessage.value = file.size ? '文件大小不能超过 50MB。' : '文件内容为空，请重新选择。'
    return
  }
  selectedFile.value = file
  importResult.value = null
  currentStep.value = 0
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
  if (isImporting.value) return
  selectedFile.value = null
  importResult.value = null
  errorMessage.value = null
  technicalError.value = null
  updateIfExists.value = false
  currentStep.value = 0
}

function confirmFile() {
  if (!selectedFile.value) return
  errorMessage.value = null
  technicalError.value = null
  currentStep.value = 1
}

async function downloadTemplate() {
  if (isDownloading.value) return
  isDownloading.value = true
  errorMessage.value = null
  technicalError.value = null
  try {
    const blob = await api.downloadImportTemplate()
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = '办公产品导入模板.xlsx'
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    errorMessage.value = '模板暂时无法下载，请检查服务连接后重试。'
    technicalError.value = error instanceof Error ? error.message : '模板下载请求失败'
  } finally {
    isDownloading.value = false
  }
}

async function startImport() {
  if (!selectedFile.value || isImporting.value) return
  const file = selectedFile.value
  const overwrite = updateIfExists.value
  isImporting.value = true
  errorMessage.value = null
  technicalError.value = null
  try {
    importResult.value = await api.importProducts(file, overwrite)
    errorMessage.value = null
    currentStep.value = 2
    addRecord({ fileName: file.name, updateIfExists: overwrite, result: importResult.value, error: null })
  } catch (error) {
    errorMessage.value = '导入请求未完成，请检查服务连接。重试前建议查看商品库，确认是否已有数据写入。'
    technicalError.value = error instanceof Error ? error.message : '商品导入请求失败'
    addRecord({ fileName: file.name, updateIfExists: overwrite, result: null, error: technicalError.value })
  } finally {
    isImporting.value = false
  }
}

onBeforeRouteLeave(() => {
  if (isImporting.value) {
    errorMessage.value = '正在导入商品，请等待处理完成后再离开。'
    return false
  }
  if (hasPendingImport.value) return window.confirm('当前文件尚未完成导入，离开后需要重新选择。确定离开吗？')
})

function beforeUnload(event: BeforeUnloadEvent) {
  if (!hasPendingImport.value && !isImporting.value) return
  event.preventDefault()
  event.returnValue = ''
}

onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>

<template>
  <div class="flex h-full min-h-0 flex-col gap-5 overflow-y-auto">
    <div class="flex-none">
      <div class="mb-3 text-xs text-muted"><NuxtLink to="/products" class="hover:text-bronze-dark">商品资源 / 商品库</NuxtLink> / 批量导入</div>
      <div class="flex flex-wrap items-start justify-between gap-4"><div><h1 class="mb-2 text-[25px] font-bold tracking-tight">批量导入商品</h1><p class="text-xs text-muted">上传商品文件，确认处理规则后导入商品库。</p></div><NuxtLink to="/products" class="inline-flex items-center gap-2 rounded-lg border border-line bg-white px-4 py-2.5 text-sm hover:bg-cream"><AppIcon name="arrowLeft" size="4" />返回商品库</NuxtLink></div>
    </div>

    <ol class="grid flex-none grid-cols-3 gap-2 rounded-xl border border-line bg-white p-3 sm:p-4" aria-label="商品导入步骤">
      <li v-for="(step, index) in steps" :key="step" class="flex items-center justify-center gap-2 text-xs sm:text-sm" :class="index === currentStep ? 'font-semibold text-bronze-dark' : 'text-muted'" :aria-current="index === currentStep ? 'step' : undefined"><span class="grid h-7 w-7 shrink-0 place-items-center rounded-full" :class="index === currentStep ? 'bg-bronze text-white' : index < currentStep ? 'bg-green/10 text-green' : 'bg-bg'"><AppIcon v-if="index < currentStep" name="check" size="4" /><span v-else>{{ index + 1 }}</span></span><span>{{ step }}</span></li>
    </ol>

    <div v-if="errorMessage" class="flex-none rounded-xl border border-red-100 bg-red-50 p-4 text-sm text-red-800" role="alert"><p>{{ errorMessage }}</p><details v-if="technicalError" class="mt-2 text-xs"><summary class="cursor-pointer">技术详情</summary><p class="mt-2 break-all">{{ technicalError }}</p></details></div>

    <div v-if="currentStep === 0" class="grid flex-none gap-5 xl:grid-cols-[minmax(0,1fr)_280px]">
      <section class="rounded-xl border border-line bg-white p-5 shadow-panel sm:p-6" aria-label="上传商品文件">
        <div class="mb-5 flex flex-wrap items-center justify-between gap-3"><h2 class="text-base font-semibold">上传数据文件</h2><button type="button" class="inline-flex items-center gap-2 rounded-lg bg-cream px-3 py-2 text-xs font-semibold text-bronze-dark disabled:opacity-50" :disabled="isDownloading" @click="downloadTemplate"><AppIcon :name="isDownloading ? 'reset' : 'inbox'" size="4" :class="{ 'animate-spin': isDownloading }" />{{ isDownloading ? '正在下载…' : '下载 Excel 模板' }}</button></div>
        <div class="rounded-xl border border-dashed p-5 text-center transition sm:p-8" :class="isDragging ? 'border-bronze bg-cream' : 'border-[#dbc4ad] bg-[#fffcf9]'" @drop.prevent="onDrop" @dragover.prevent="isDragging = true" @dragleave.prevent="isDragging = false">
          <div class="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-xl bg-cream text-bronze"><AppIcon name="upload" size="6" /></div>
          <h3 class="mb-2 text-sm font-semibold">拖放文件到这里，或点击选择文件</h3><p class="mb-5 text-xs leading-relaxed text-muted">支持 .xlsx / .xls / .csv，单次最多 500 行，不超过 50MB。</p><button type="button" class="rounded-lg border border-line bg-white px-4 py-2 text-sm font-semibold text-bronze-dark hover:bg-cream" @click="fileInput?.click()">{{ selectedFile ? '重新选择文件' : '选择文件' }}</button><input ref="fileInput" type="file" accept=".xlsx,.xls,.csv" class="hidden" aria-label="选择商品数据文件" @change="onFileChange">
        </div>
        <div v-if="selectedFile" class="mt-4 flex items-center gap-3 rounded-lg bg-bg p-4"><AppIcon name="table" size="5" class="text-bronze" /><div class="min-w-0 flex-1"><div class="break-all text-sm font-medium">{{ selectedFile.name }}</div><div class="mt-1 text-xs text-muted">{{ formatSize(selectedFile.size) }} · 已选择，尚未导入</div></div><button type="button" class="rounded-md p-2 text-muted hover:bg-cream" aria-label="移除商品文件" @click="resetImport"><AppIcon name="close" size="4" /></button></div>
        <div class="mt-6 flex justify-end border-t border-line pt-5"><button type="button" class="inline-flex items-center gap-2 rounded-lg bg-bronze px-5 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark disabled:cursor-not-allowed disabled:opacity-40" :disabled="!selectedFile" @click="confirmFile">下一步，确认导入<AppIcon name="arrowRight" size="4" /></button></div>
      </section>
      <aside class="self-start rounded-xl border border-line bg-white p-5" aria-label="导入填写说明"><h2 class="mb-4 text-sm font-semibold">填写说明</h2><dl class="space-y-4 text-xs leading-relaxed"><div><dt class="mb-1 font-semibold">使用标准模板</dt><dd class="text-muted">保留模板中的列名，不要合并单元格。</dd></div><div><dt class="mb-1 font-semibold">必填商品信息</dt><dd class="text-muted">品类码与商品名称必填，例如 DK（办公桌）、CH（办公椅）。</dd></div><div><dt class="mb-1 font-semibold">唯一识别编码</dt><dd class="text-muted">外部编码用于识别重复商品；同一文件内请勿重复。</dd></div><div><dt class="mb-1 font-semibold">六维标签</dt><dd class="break-all text-muted">使用 JSON 格式，例如 {"A":["A1"],"B":["B2"]}。</dd></div></dl></aside>
    </div>

    <section v-else-if="currentStep === 1 && selectedFile" class="flex-none rounded-xl border border-line bg-white p-5 shadow-panel sm:p-6" aria-label="确认文件与导入规则">
      <h2 class="mb-2 text-base font-semibold">确认文件与处理规则</h2><p class="mb-5 text-xs leading-relaxed text-muted">此处确认文件和重复商品处理方式。商品字段与行数据会在执行导入时由服务器逐行校验。</p>
      <div class="mb-6 flex items-start gap-3 rounded-lg bg-bg p-4"><AppIcon name="table" size="6" class="text-bronze" /><div class="min-w-0"><div class="break-all text-sm font-semibold">{{ selectedFile.name }}</div><div class="mt-1 text-xs text-muted">{{ formatSize(selectedFile.size) }} · 尚未写入商品库</div></div></div>
      <fieldset :disabled="isImporting" class="mb-6 space-y-3"><legend class="mb-3 text-sm font-semibold">发现重复商品时</legend><label class="flex items-start gap-3 rounded-lg border p-4 text-sm" :class="!updateIfExists ? 'border-bronze bg-cream/50' : 'border-line'"><input v-model="updateIfExists" type="radio" :value="false" name="duplicate-policy" class="mt-1 accent-bronze"><span><strong class="font-medium">跳过已有商品（推荐）</strong><span class="mt-1 block text-xs text-muted">保留现有商品资料，仅导入新增商品。</span></span></label><label class="flex items-start gap-3 rounded-lg border p-4 text-sm" :class="updateIfExists ? 'border-bronze bg-cream/50' : 'border-line'"><input v-model="updateIfExists" type="radio" :value="true" name="duplicate-policy" class="mt-1 accent-bronze"><span><strong class="font-medium">覆盖更新已有商品</strong><span class="mt-1 block text-xs text-muted">按编码匹配已有商品，并使用文件中的资料更新。</span></span></label></fieldset>
      <p class="rounded-lg bg-bg p-4 text-xs leading-relaxed text-muted">导入按行处理，成功行会直接保存，失败行不会阻止其他行入库。请在结果页面查看失败与跳过明细。</p>
      <div class="mt-6 flex flex-wrap items-center justify-between gap-3 border-t border-line pt-5"><button type="button" class="rounded-lg border border-line px-4 py-2.5 text-sm disabled:opacity-40" :disabled="isImporting" @click="currentStep = 0">上一步</button><button type="button" class="inline-flex items-center gap-2 rounded-lg bg-bronze px-5 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark disabled:opacity-50" :disabled="isImporting" @click="startImport"><AppIcon :name="isImporting ? 'reset' : 'import'" size="4" :class="{ 'animate-spin': isImporting }" />{{ isImporting ? '正在校验并导入，请稍候…' : '确认并执行导入' }}</button></div>
    </section>

    <section v-else-if="importResult" class="flex-none rounded-xl border border-line bg-white p-5 shadow-panel sm:p-6" aria-label="商品导入结果">
      <div class="mb-5 text-xs text-muted">{{ selectedFile?.name }}</div><ProductImportSummary :result="importResult" />

      <!-- 数据清洗报告 -->
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
        <div v-if="importResult.cleansingReport.issues.length > 10" class="mt-2 text-xs text-muted">还有 {{ importResult.cleansingReport.issues.length - 10 }} 条清洗记录…</div>
      </div>

      <p v-if="!storageAvailable" class="mt-4 text-xs text-muted">浏览器未能保存导入记录，本次结果仅在当前会话中保留。</p>
      <div class="mt-6 flex flex-wrap items-center justify-between gap-3 border-t border-line pt-5"><button type="button" class="rounded-lg border border-line px-4 py-2.5 text-sm hover:bg-cream" @click="resetImport">继续导入</button><div class="flex flex-wrap gap-3"><NuxtLink to="/products/import-records" class="rounded-lg border border-line px-4 py-2.5 text-sm hover:bg-cream">查看导入记录</NuxtLink><NuxtLink to="/products/audit" class="rounded-lg bg-cream px-4 py-2.5 text-sm font-semibold text-bronze-dark hover:bg-[#ebe3d8]">去审核商品</NuxtLink><NuxtLink to="/products" class="rounded-lg bg-bronze px-4 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark">查看商品库</NuxtLink></div></div>
    </section>
  </div>
</template>
