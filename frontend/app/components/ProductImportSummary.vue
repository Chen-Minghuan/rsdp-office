<script setup lang="ts">
import type { ProductImportResult } from '~/types/imported-products'

const props = defineProps<{ result: ProductImportResult }>()
const resultTitle = computed(() => {
  if (!props.result.totalRows) return '文件中没有可处理的商品数据'
  if (props.result.failedCount === props.result.totalRows) return '导入未成功，请修正数据后重新上传'
  if (props.result.failedCount) return '处理完成，部分商品未能导入'
  if (props.result.skippedCount) return '处理完成，已跳过重复商品'
  return '商品导入完成'
})

function downloadFailures() {
  const escapeCell = (value: string | number | null) => {
    const text = String(value ?? '')
    const safe = /^[=+\-@\t\r]/.test(text) ? `'${text}` : text
    return `"${safe.replaceAll('"', '""')}"`
  }
  const rows = [
    ['行号', '外部编码', '商品ID', '失败原因'],
    ...props.result.failures.map(issue => [issue.rowIndex, issue.externalCode, issue.rspuId, issue.reason]),
  ]
  const blob = new Blob(['\uFEFF', rows.map(row => row.map(escapeCell).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = '商品导入失败明细.csv'
  anchor.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div>
    <div class="mb-5 flex items-start gap-3 rounded-xl p-4" :class="result.failedCount ? 'bg-amber-50 text-amber-800' : 'bg-green/10 text-green'" role="status">
      <AppIcon :name="result.failedCount ? 'warning' : 'checkCircle'" size="5" />
      <div>
        <h2 class="text-sm font-semibold">{{ resultTitle }}</h2>
        <p class="mt-1 text-xs leading-relaxed">成功 {{ result.successCount }} 行，失败 {{ result.failedCount }} 行，跳过 {{ result.skippedCount }} 行，关联内嵌图片 {{ result.embeddedImageCount || 0 }} 张。成功行已写入商品库，状态为「待审核」。</p>
      </div>
    </div>
    <dl class="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
      <div class="rounded-lg bg-bg p-4"><dt class="mb-1 text-xs text-muted">总行数</dt><dd class="text-xl font-bold">{{ result.totalRows }}</dd></div>
      <div class="rounded-lg bg-bg p-4"><dt class="mb-1 text-xs text-muted">成功</dt><dd class="text-xl font-bold text-green">{{ result.successCount }}</dd></div>
      <div class="rounded-lg bg-bg p-4"><dt class="mb-1 text-xs text-muted">失败</dt><dd class="text-xl font-bold" :class="result.failedCount ? 'text-red-700' : 'text-ink'">{{ result.failedCount }}</dd></div>
      <div class="rounded-lg bg-bg p-4"><dt class="mb-1 text-xs text-muted">跳过</dt><dd class="text-xl font-bold text-muted">{{ result.skippedCount }}</dd></div>
    </dl>
    <section v-if="result.failures.length" class="mb-5" aria-label="导入失败明细">
      <div class="mb-3 flex flex-wrap items-center justify-between gap-3"><h3 class="text-sm font-semibold">失败明细（{{ result.failures.length }}）</h3><button type="button" class="rounded-lg border border-line px-3 py-2 text-xs font-semibold text-bronze-dark hover:bg-cream" @click="downloadFailures">下载失败明细</button></div>
      <div class="space-y-2"><div v-for="(issue, index) in result.failures" :key="index" class="flex items-start gap-3 rounded-lg border border-red-100 bg-red-50 p-3 text-xs"><span class="shrink-0 text-red-700">行 {{ issue.rowIndex }}</span><div class="min-w-0 break-words"><div class="mb-1 font-medium">{{ issue.externalCode || issue.rspuId || '未识别编码' }}</div><p class="text-red-800">{{ issue.reason }}</p></div></div></div>
    </section>
    <section v-if="result.warnings.length" aria-label="导入提示"><h3 class="mb-3 text-sm font-semibold">处理提示（{{ result.warnings.length }}）</h3><div class="space-y-2"><div v-for="(issue, index) in result.warnings" :key="index" class="flex items-start gap-3 rounded-lg bg-cream p-3 text-xs"><span class="shrink-0 text-bronze-dark">行 {{ issue.rowIndex }}</span><p class="min-w-0 break-words text-[#6b6359]">{{ issue.reason }}</p></div></div></section>
  </div>
</template>
