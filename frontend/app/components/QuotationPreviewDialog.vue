<script setup lang="ts">
import type { QuotationFee, QuotationRecordLine } from '~/types/office'

const props = defineProps<{
  projectName: string
  pricingLabel: string
  generatedAt: string
  rows: QuotationRecordLine[]
  fees: QuotationFee[]
  goodsTotal: number
  feeTotal: number
  grandTotal: number
  confirming: boolean
  downloading: boolean
  error: string
}>()

const emit = defineEmits<{
  confirm: []
  cancel: []
  download: []
}>()

const dialog = ref<HTMLElement | null>(null)
const busy = computed(() => props.confirming || props.downloading)

onMounted(() => dialog.value?.focus())

function cancel() {
  if (!busy.value) emit('cancel')
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    cancel()
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const focusable = Array.from(dialog.value.querySelectorAll<HTMLElement>('button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])'))
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.value)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function amount(value: number | null | undefined) {
  if (value === null || value === undefined) return '—'
  return `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" @click.self="cancel">
      <section
        ref="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="quotation-preview-title"
        data-testid="quotation-preview-dialog"
        tabindex="-1"
        class="flex max-h-[90vh] w-full max-w-6xl flex-col border border-line bg-white outline-none"
        @keydown="onKeydown"
      >
        <header class="flex items-center justify-between gap-3 border-b border-line px-4 py-3">
          <div>
            <h2 id="quotation-preview-title" class="text-base font-bold">报价单 Excel 预览</h2>
            <p class="mt-0.5 text-xs text-muted">预览、下载与确认使用同一份报价草稿</p>
          </div>
          <button type="button" class="border border-line px-3 py-2 text-sm text-bronze-dark disabled:opacity-50" :disabled="busy" @click="emit('download')">
            {{ downloading ? '下载中…' : '下载 Excel' }}
          </button>
        </header>

        <div class="overflow-auto p-4">
          <div class="min-w-[900px] border border-[#b8b8b8] text-sm">
            <div class="border-b border-[#b8b8b8] px-3 py-3 text-left text-lg font-bold md:text-center">{{ projectName }} · 项目报价单</div>
            <div class="grid grid-cols-2 border-b border-[#b8b8b8] text-xs">
              <div class="border-r border-[#b8b8b8] px-3 py-2">项目：{{ projectName }}</div>
              <div class="px-3 py-2">{{ pricingLabel }} · {{ generatedAt }}</div>
            </div>
            <table class="w-full border-collapse text-sm">
              <thead>
                <tr class="bg-[#f3f0ec] text-left text-xs">
                  <th class="border border-[#c8c8c8] px-2 py-2 font-medium">商品</th>
                  <th class="border border-[#c8c8c8] px-2 py-2 font-medium">楼层 / 空间</th>
                  <th class="border border-[#c8c8c8] px-2 py-2 font-medium">规格</th>
                  <th class="border border-[#c8c8c8] px-2 py-2 text-right font-medium">数量</th>
                  <th class="border border-[#c8c8c8] px-2 py-2 text-right font-medium">出厂价</th>
                  <th class="border border-[#c8c8c8] px-2 py-2 text-right font-medium">成交单价</th>
                  <th class="border border-[#c8c8c8] px-2 py-2 text-right font-medium">小计</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in rows" :key="`${row.floorId}|${row.zoneId}|${row.productId}`">
                  <td class="border border-[#c8c8c8] px-2 py-2">
                    <div>{{ row.productName }}</div>
                    <div v-if="row.issue" class="mt-0.5 text-xs text-red-700">{{ row.issue }}</div>
                  </td>
                  <td class="border border-[#c8c8c8] px-2 py-2">{{ row.floorName }} / {{ row.zoneName }}</td>
                  <td class="border border-[#c8c8c8] px-2 py-2">{{ row.variant || '—' }}</td>
                  <td class="border border-[#c8c8c8] px-2 py-2 text-right tabular-nums">{{ row.qty }}</td>
                  <td class="border border-[#c8c8c8] px-2 py-2 text-right tabular-nums">{{ amount(row.factoryPrice) }}</td>
                  <td class="border border-[#c8c8c8] px-2 py-2 text-right tabular-nums">{{ amount(row.unitPrice) }}</td>
                  <td class="border border-[#c8c8c8] px-2 py-2 text-right tabular-nums">{{ amount(row.subtotal) }}</td>
                </tr>
                <tr v-for="(fee, index) in fees" :key="`${fee.name}-${index}`">
                  <td class="border border-[#c8c8c8] px-2 py-2" colspan="6">{{ fee.name }}</td>
                  <td class="border border-[#c8c8c8] px-2 py-2 text-right tabular-nums">{{ amount(fee.amount) }}</td>
                </tr>
                <tr class="bg-[#faf7f3] font-semibold">
                  <td class="border border-[#c8c8c8] px-2 py-2" colspan="6">商品小计</td>
                  <td class="border border-[#c8c8c8] px-2 py-2 text-right tabular-nums">{{ amount(goodsTotal) }}</td>
                </tr>
                <tr class="bg-[#faf7f3] font-semibold">
                  <td class="border border-[#c8c8c8] px-2 py-2" colspan="6">费用合计</td>
                  <td class="border border-[#c8c8c8] px-2 py-2 text-right tabular-nums">{{ amount(feeTotal) }}</td>
                </tr>
                <tr class="bg-[#f3f0ec] font-bold">
                  <td class="border border-[#c8c8c8] px-2 py-2" colspan="6">商品报价合计</td>
                  <td class="border border-[#c8c8c8] px-2 py-2 text-right text-bronze-dark tabular-nums">{{ amount(grandTotal) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <p v-if="error" role="alert" class="mx-4 mb-3 border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">{{ error }}</p>
        <footer class="flex justify-end gap-2 border-t border-line px-4 py-3">
          <button type="button" class="bg-bronze px-5 py-2 text-sm text-white disabled:opacity-50" :disabled="busy" @click="emit('confirm')">
            {{ confirming ? '确认中…' : '确认' }}
          </button>
          <button type="button" class="border border-line px-5 py-2 text-sm text-[#5f5b56] disabled:opacity-50" :disabled="busy" @click="cancel">取消</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>
