<script setup lang="ts">
import type { ProductImportRecord } from '~/types/imported-products'

const { records, storageAvailable } = useProductImportHistory()
const selectedRecord = ref<ProductImportRecord | null>(null)

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
    <div class="flex-none"><div class="mb-3 text-xs text-muted">商品资源 / 导入记录</div><div class="flex flex-wrap items-start justify-between gap-4"><div><h1 class="mb-2 text-[25px] font-bold tracking-tight">导入记录</h1><p class="text-xs leading-relaxed text-muted">仅展示当前浏览器发起的最近 30 次导入，不是服务器完整历史。</p></div><NuxtLink to="/products/import" class="inline-flex items-center gap-2 rounded-lg bg-bronze px-4 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark"><AppIcon name="import" size="4" />新建导入</NuxtLink></div></div>
    <p v-if="!storageAvailable" class="flex-none rounded-lg bg-cream p-3 text-xs text-bronze-dark" role="status">本地记录存储不可用，当前会话记录可能在刷新后丢失。</p>
    <section class="flex-none overflow-hidden rounded-xl border border-line bg-white shadow-panel" aria-label="导入记录列表">
      <div v-if="!records.length" class="grid min-h-[320px] place-items-center p-6"><div class="text-center"><div class="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-xl bg-cream text-bronze"><AppIcon name="documentText" size="6" /></div><h2 class="mb-2 text-base font-bold">还没有导入记录</h2><p class="mb-5 text-sm text-muted">完成一次商品导入后，可在这里查看结果和失败明细。</p><NuxtLink to="/products/import" class="rounded-lg bg-bronze px-4 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark">开始导入</NuxtLink></div></div>
      <div v-else class="overflow-x-auto"><table class="w-full whitespace-nowrap text-left text-sm"><thead class="bg-bg text-xs text-muted"><tr><th class="px-5 py-3 font-medium">导入文件</th><th class="px-4 py-3 font-medium">成功 / 失败 / 跳过</th><th class="px-4 py-3 font-medium">状态</th><th class="px-4 py-3 font-medium">导入时间</th><th class="px-5 py-3 text-right font-medium">操作</th></tr></thead><tbody><tr v-for="record in records" :key="record.id" class="border-t border-line hover:bg-bg/70"><td class="px-5 py-4"><div class="max-w-[240px] truncate font-medium" :title="record.fileName">{{ record.fileName }}</div><div class="mt-1 text-xs text-muted">{{ record.updateIfExists ? '覆盖更新已有商品' : '跳过已有商品' }}</div></td><td class="px-4 py-4 text-xs tabular-nums">{{ record.result ? `${record.result.successCount} / ${record.result.failedCount} / ${record.result.skippedCount}` : '—' }}</td><td class="px-4 py-4"><span class="rounded-md px-2 py-1 text-xs" :class="!record.result || record.result.failedCount ? 'bg-amber-50 text-amber-800' : 'bg-green/10 text-green'">{{ recordStatus(record) }}</span></td><td class="px-4 py-4 text-xs text-muted">{{ formatDate(record.createdAt) }}</td><td class="px-5 py-4 text-right"><button type="button" class="rounded-md px-2 py-1 text-xs font-semibold text-bronze-dark hover:bg-cream" :aria-label="`查看${record.fileName}导入结果`" @click="selectedRecord = selectedRecord?.id === record.id ? null : record">{{ selectedRecord?.id === record.id ? '收起结果' : '查看结果' }}</button></td></tr></tbody></table></div>
    </section>
    <section v-if="selectedRecord" class="flex-none rounded-xl border border-line bg-white p-5 sm:p-6" aria-label="历史导入结果"><div class="mb-5 flex items-start justify-between gap-3"><h2 class="min-w-0 break-all text-sm font-semibold">{{ selectedRecord.fileName }}</h2><button type="button" class="rounded-lg p-1 text-muted hover:bg-cream" aria-label="收起导入结果" @click="selectedRecord = null"><AppIcon name="close" size="5" /></button></div><ProductImportSummary v-if="selectedRecord.result" :result="selectedRecord.result" /><div v-else role="status"><p class="text-sm text-muted">该次请求未完成，是否有商品写入请以商品库为准。</p><details class="mt-3 text-xs text-muted"><summary class="cursor-pointer">技术详情</summary><p class="mt-2 break-all">{{ selectedRecord.error }}</p></details></div></section>
  </div>
</template>
