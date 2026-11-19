<script setup lang="ts">
import type { ProductImportRecord } from '~/types/imported-products'

const { records: browserRecords, storageAvailable } = useProductImportHistory()
const { user } = useAuth()
const records = computed(() => user.value?.role === 'ADMIN' ? browserRecords.value : [])
const selectedRecord = ref<ProductImportRecord | null>(null)
const api = useOfficeApi()
const rsdpWeb = useRsdpWeb()
interface HistoricalBatch {
  batchId: string
  fileName: string
  status: string
  successCount?: number
  failedCount?: number
}
interface HistoricalResult extends HistoricalBatch {
  rows: { excelRowNumber: number; status: string; generatedRspuId?: string; failureReason?: string }[]
}
const batches = ref<HistoricalBatch[]>([])
const batchResult = ref<HistoricalResult | null>(null)
const batchError = ref('')

onMounted(async () => {
  try {
    if ((await api.loadCatalogCapabilities()).managed) {
      batches.value = await api.catalogImportHistory<HistoricalBatch[]>()
    }
  } catch (error) {
    batchError.value = error instanceof Error ? error.message : '服务器导入记录读取失败'
  }
})

async function viewBatch(batchId: string) {
  batchError.value = ''
  try {
    batchResult.value = await api.catalogHistoricalStatus<HistoricalResult>(batchId)
  } catch (error) {
    batchError.value = error instanceof Error ? error.message : '旧批次结果读取失败'
  }
}

function recordStatus(record: ProductImportRecord) {
  if (!record.result) return '请求未完成'
  if (!record.result.totalRows) return '无可导入数据'
  if (record.result.failedCount === record.result.totalRows) return '导入失败'
  if (record.result.failedCount) return '部分成功'
  if (record.result.skippedCount || record.result.warnings.length) return '完成（有提示）'
  return '导入完成'
}

function formatDate(date: string) {
  return new Date(date).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col overflow-y-auto">
    <div class="flex-none"><div class="mb-3 text-xs text-muted">商品资源 / 导入记录</div><div class="flex flex-wrap items-start justify-between gap-4"><div><h1 class="mb-2 text-[25px] font-bold tracking-tight">Office 历史导入记录</h1><p class="text-xs leading-relaxed text-muted">查询本人发起的旧 Office 批次。新批次在 RSDP 查看。</p></div><a :href="rsdpWeb('/products/excel-ai-import')" target="_blank" rel="noopener" class="rounded-lg border border-line px-4 py-2.5 text-sm font-semibold">去 RSDP 导入</a></div></div>
    <p v-if="batchError" role="alert" class="my-2 text-sm text-red-700">{{ batchError }}</p>
    <section v-if="batches.length" aria-label="服务器历史批次" class="my-3 border border-line bg-white p-4">
      <h2 class="text-sm font-semibold">服务器历史批次</h2>
      <ul class="divide-y divide-line">
        <li v-for="batch in batches" :key="batch.batchId" class="flex flex-wrap items-center justify-between gap-2 py-2 text-sm">
          <span>{{ batch.fileName }} · {{ batch.status }} · 成功 {{ batch.successCount || 0 }} / 失败 {{ batch.failedCount || 0 }}</span>
          <button class="text-bronze-dark" type="button" @click="viewBatch(batch.batchId)">查看批次</button>
        </li>
      </ul>
      <div v-if="batchResult" class="mt-3 border-t border-line pt-3 text-sm">
        <h3 class="font-semibold">{{ batchResult.fileName }} · {{ batchResult.status }}</h3>
        <ul class="mt-2">
          <li v-for="row in batchResult.rows" :key="row.excelRowNumber" class="py-1">
            第 {{ row.excelRowNumber }} 行 · {{ row.status }} {{ row.generatedRspuId || row.failureReason }}
          </li>
        </ul>
      </div>
    </section>
    <p v-if="!storageAvailable" class="flex-none rounded-lg bg-cream p-3 text-xs text-bronze-dark" role="status">本地记录存储不可用，当前会话记录可能在刷新后丢失。</p>
    <section class="flex-none overflow-hidden rounded-xl border border-line bg-white shadow-panel" aria-label="导入记录列表">
      <div v-if="!records.length && !batches.length" class="grid min-h-[240px] place-items-center p-6"><p class="text-sm text-muted">暂无可查询的旧 Office 导入记录。</p></div>
      <div v-else class="overflow-x-auto"><table class="w-full whitespace-nowrap text-left text-sm"><thead class="bg-bg text-xs text-muted"><tr><th class="px-5 py-3 font-medium">导入文件</th><th class="px-4 py-3 font-medium">成功 / 失败 / 跳过</th><th class="px-4 py-3 font-medium">状态</th><th class="px-4 py-3 font-medium">导入时间</th><th class="px-5 py-3 text-right font-medium">操作</th></tr></thead><tbody><tr v-for="record in records" :key="record.id" class="border-t border-line hover:bg-bg/70"><td class="px-5 py-4"><div class="max-w-[240px] truncate font-medium" :title="record.fileName">{{ record.fileName }}</div><div class="mt-1 text-xs text-muted">{{ record.updateIfExists ? '覆盖更新已有商品' : '跳过已有商品' }}</div></td><td class="px-4 py-4 text-xs tabular-nums">{{ record.result ? `${record.result.successCount} / ${record.result.failedCount} / ${record.result.skippedCount}` : '—' }}</td><td class="px-4 py-4"><span class="rounded-md px-2 py-1 text-xs" :class="!record.result || record.result.failedCount ? 'bg-amber-50 text-amber-800' : 'bg-green/10 text-green'">{{ recordStatus(record) }}</span></td><td class="px-4 py-4 text-xs text-muted">{{ formatDate(record.createdAt) }}</td><td class="px-5 py-4 text-right"><button type="button" class="rounded-md px-2 py-1 text-xs font-semibold text-bronze-dark hover:bg-cream" :aria-label="`查看${record.fileName}导入结果`" @click="selectedRecord = selectedRecord?.id === record.id ? null : record">{{ selectedRecord?.id === record.id ? '收起结果' : '查看结果' }}</button></td></tr></tbody></table></div>
    </section>
    <section v-if="selectedRecord" class="flex-none rounded-xl border border-line bg-white p-5 sm:p-6" aria-label="历史导入结果"><div class="mb-5 flex items-start justify-between gap-3"><h2 class="min-w-0 break-all text-sm font-semibold">{{ selectedRecord.fileName }}</h2><button type="button" class="rounded-lg p-1 text-muted hover:bg-cream" aria-label="收起导入结果" @click="selectedRecord = null"><AppIcon name="close" size="5" /></button></div><ProductImportSummary v-if="selectedRecord.result" :result="selectedRecord.result" /><div v-else role="status"><p class="text-sm text-muted">该次请求未完成，是否有商品写入请以商品库为准。</p><details class="mt-3 text-xs text-muted"><summary class="cursor-pointer">技术详情</summary><p class="mt-2 break-all">{{ selectedRecord.error }}</p></details></div></section>
  </div>
</template>
