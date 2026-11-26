<script setup lang="ts">
import { authEnabled } from '~/composables/useAuth'
import { sameOriginAssetUrl } from '~/utils/apiBase'
import { createUuid } from '~/utils/uuid'
import { projectPermissionsFor } from '~/utils/projectPermissions'
import type {
  ConfirmQuotationRequest,
  ProjectQuotationPreview,
  ProjectQuotationRecord,
  QuotationFee,
  QuotationLine,
  QuotationPricingMode,
  QuotationRecordLine,
} from '~/types/office'
import type { ProposalConfirmation } from '~/types/productScheme'

interface RowOverride {
  qty?: number | null
  unitPrice?: number | null
}

interface FeeRow {
  key: string
  name: string
  amount: number | null
}

interface QuotationTableRow {
  key: string
  floorId: string
  zoneId: string
  floorName: string
  spaceName: string
  line: QuotationLine
  qty: number
  unitPrice: number | null
  subtotal: number | null
}

interface QuotationDraft {
  projectId: string
  projectName: string
  pricingMode: QuotationPricingMode
  pricingValue: number
  pricingLabel: string
  generatedAt: string
  rows: QuotationRecordLine[]
  fees: QuotationFee[]
  goodsTotal: number
  feeTotal: number
  grandTotal: number
}

const api = useOfficeApi()
const { me, user } = useAuth()
const workspace = useWorkspaceStore()

const canPrice = computed(() => !authEnabled || user.value?.role === 'DEALER')
const checking = ref(true)
const loading = ref(false)
const error = ref('')
const notice = ref('')
const preview = ref<ProjectQuotationPreview | null>(null)
const record = ref<ProjectQuotationRecord | null>(null)
const confirming = ref(false)
const downloading = ref(false)
const dialogError = ref('')
const draft = ref<QuotationDraft | null>(null)
// 各楼层方案确认状态：显式 null = 从未确认；!current = 已失效；键缺失 = 读取失败按未知处理（后端权威拦截兜底）
const floorConfirmations = ref<Record<string, ProposalConfirmation | null>>({})
const confirmationsLoading = ref(false)

const projectId = ref('')
const mode = ref<QuotationPricingMode>('COEFFICIENT')
const coefficient = ref(1.2)
const marginPercent = ref(35)
const overrides = ref<Record<string, RowOverride>>({})
const fees = ref<FeeRow[]>([createFee('服务费'), createFee('物流搬运安装费')])

const projects = computed(() => canPrice.value ? workspace.projects : workspace.projects.filter(project =>
  projectPermissionsFor(project, user.value?.userId).includes('QUOTE_READ')))
const authorized = computed(() => canPrice.value || projects.value.length > 0)
const currentProject = computed(() => projects.value.find(project => project.projectId === projectId.value) || null)
const canExport = computed(() => projectPermissionsFor(currentProject.value, user.value?.userId).includes('EXPORT_QUOTE'))

/** 报价行涉及但方案确认缺失/已失效的楼层；stale 区分“从未确认”与“已失效需重新确认”。 */
const blockedFloors = computed(() => (preview.value?.floors || [])
  .filter((floor) => {
    const status = floorConfirmations.value[floor.floorId]
    return status === null || (status !== undefined && !status.current)
  })
  .map(floor => ({ floorId: floor.floorId, floorName: floor.floorName, stale: !!floorConfirmations.value[floor.floorId] })))

onMounted(async () => {
  checking.value = true
  try {
    if (authEnabled) await me()
    selectAccessibleProject()
  } finally {
    checking.value = false
  }
})

function selectAccessibleProject() {
  const ids = projects.value.map(project => project.projectId)
  if (!ids.includes(projectId.value)) {
    projectId.value = ids.includes(workspace.currentProjectId) ? workspace.currentProjectId : ids[0] ?? ''
  }
}

watch(projects, selectAccessibleProject)

watch(projectId, (value, previous) => {
  if (value !== previous) {
    resetFees()
    record.value = null
    draft.value = null
    notice.value = ''
    dialogError.value = ''
    floorConfirmations.value = {}
  }
  if (authorized.value) {
    if (canPrice.value) void loadPreview()
    void loadLatest()
  }
})

async function loadFloorConfirmations(floorIds: string[]) {
  floorConfirmations.value = {}
  if (!floorIds.length) return
  confirmationsLoading.value = true
  const requestProject = projectId.value
  try {
    await Promise.all(floorIds.map(async (floorId) => {
      try {
        const status = await api.loadProposalConfirmation(floorId)
        if (requestProject === projectId.value) floorConfirmations.value = { ...floorConfirmations.value, [floorId]: status }
      } catch { /* 单楼层状态读取失败按未知处理，不阻塞确认入口；后端确认时会权威拦截 */ }
    }))
  } finally {
    if (requestProject === projectId.value) confirmationsLoading.value = false
  }
}

async function loadPreview() {
  if (!projectId.value) {
    preview.value = null
    return
  }
  loading.value = true
  error.value = ''
  draft.value = null
  try {
    preview.value = await api.previewProjectQuotation(projectId.value, mode.value, currentPricingValue())
    resetOverrides()
    void loadFloorConfirmations(preview.value.floors.map(floor => floor.floorId))
  } catch (cause) {
    preview.value = null
    floorConfirmations.value = {}
    error.value = cause instanceof Error ? cause.message : '报价试算失败'
  } finally {
    loading.value = false
  }
}

async function loadLatest() {
  if (!projectId.value) {
    record.value = null
    return
  }
  const requestProject = projectId.value
  if (!canPrice.value) {
    loading.value = true
    error.value = ''
  }
  try {
    const latest = await api.latestProjectQuotation(requestProject)
    if (requestProject === projectId.value) record.value = latest
  } catch (cause) {
    if (requestProject === projectId.value) {
      record.value = null
      if (!canPrice.value) error.value = cause instanceof Error ? cause.message : '报价读取失败'
    }
  } finally {
    if (!canPrice.value && requestProject === projectId.value) loading.value = false
  }
}

async function downloadConfirmedQuote() {
  if (!currentProject.value || !canExport.value) return
  downloading.value = true
  error.value = ''
  const selected = currentProject.value
  try {
    const blob = await api.exportLatestProjectQuotation(selected.projectId)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${selected.projectName}-报价单.xlsx`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '报价导出失败'
  } finally {
    downloading.value = false
  }
}

function currentPricingValue() {
  return mode.value === 'MARGIN' ? Math.round(marginPercent.value * 100) / 10000 : coefficient.value
}

function roundMoney(value: number) {
  return Math.round((value + Number.EPSILON) * 100) / 100
}

function createFee(name = ''): FeeRow {
  return { key: createUuid(), name, amount: null }
}

function resetFees() {
  fees.value = [createFee('服务费'), createFee('物流搬运安装费')]
}

function addFee() {
  fees.value.push(createFee())
}

function removeFee(index: number) {
  fees.value.splice(index, 1)
}

function normalizedFees() {
  const normalized: QuotationFee[] = []
  for (const fee of fees.value) {
    const name = fee.name.trim()
    const rawAmount = fee.amount === null || fee.amount === undefined ? 0 : Number(fee.amount)
    if (!Number.isFinite(rawAmount) || rawAmount < 0) throw new Error('费用金额须为不小于 0 的有效数字')
    const amount = roundMoney(rawAmount)
    if (!name) {
      if (amount !== 0) throw new Error('请填写费用名称，或删除空白费用行')
      continue
    }
    if (name.length > 100) throw new Error('费用名称不能超过 100 字')
    normalized.push({ name, amount })
  }
  return normalized
}

function openConfirmation() {
  error.value = ''
  notice.value = ''
  dialogError.value = ''
  try {
    if (!preview.value || !projectId.value || !rows.value.length) return
    if (blockedFloors.value.length) {
      throw new Error(`楼层「${blockedFloors.value.map(floor => floor.floorName).join('、')}」的方案尚未确认或已失效，请先在项目清单页确认当前方案`)
    }
    if (preview.value.pricingMode !== mode.value || Number(preview.value.pricingValue) !== Number(currentPricingValue())) {
      throw new Error('报价口径已修改，请先重新试算')
    }
    for (const row of rows.value) {
      if (!Number.isInteger(row.qty) || row.qty < 0) throw new Error(`${row.line.productName} 的数量须为不小于 0 的整数`)
      if (row.unitPrice !== null && (!Number.isFinite(row.unitPrice) || row.unitPrice < 0)) {
        throw new Error(`${row.line.productName} 的成交单价须为不小于 0 的有效数字`)
      }
    }
    const normalized = normalizedFees()
    const frozenRows: QuotationRecordLine[] = rows.value.map(row => ({
      floorId: row.floorId,
      floorName: row.floorName,
      zoneId: row.zoneId,
      zoneName: row.spaceName || '未分区商品',
      productId: row.line.productId,
      rspuId: row.line.rspuId,
      productName: row.line.productName,
      variant: row.line.variant,
      imageUrl: row.line.imageUrl,
      qty: row.qty,
      factoryPrice: row.line.factoryPrice,
      unitPrice: row.unitPrice,
      subtotal: row.subtotal,
      issue: row.line.issue,
    }))
    const goodsTotal = roundMoney(frozenRows.reduce((sum, row) => sum + (row.subtotal || 0), 0))
    const feeTotal = roundMoney(normalized.reduce((sum, fee) => sum + (fee.amount || 0), 0))
    draft.value = {
      projectId: projectId.value,
      projectName: preview.value.projectName,
      pricingMode: mode.value,
      pricingValue: currentPricingValue(),
      pricingLabel: modeLabel(),
      generatedAt: new Date().toLocaleString('zh-CN', { hour12: false }),
      rows: frozenRows,
      fees: normalized,
      goodsTotal,
      feeTotal,
      grandTotal: roundMoney(goodsTotal + feeTotal),
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '请检查报价内容'
  }
}

function closeConfirmation() {
  if (confirming.value || downloading.value) return
  draft.value = null
  dialogError.value = ''
}

function draftPayload(active: QuotationDraft): ConfirmQuotationRequest {
  return {
    pricingMode: active.pricingMode,
    pricingValue: active.pricingValue,
    fees: active.fees.map(fee => ({ ...fee })),
    lines: active.rows.map(row => ({
      floorId: row.floorId,
      zoneId: row.zoneId,
      zoneName: row.zoneName,
      productId: row.productId,
      qty: row.qty,
      unitPrice: row.issue ? null : row.unitPrice,
    })),
  }
}

async function confirmQuotation() {
  const active = draft.value
  if (!active || confirming.value) return
  confirming.value = true
  dialogError.value = ''
  try {
    record.value = await api.confirmProjectQuotation(active.projectId, draftPayload(active))
    notice.value = `已确认报价 ${record.value.quotationId}（第 ${record.value.revision} 版）`
    draft.value = null
  } catch (cause) {
    dialogError.value = cause instanceof Error ? cause.message : '确认报价失败'
  } finally {
    confirming.value = false
  }
}

async function downloadQuotation() {
  const active = draft.value
  if (!active || downloading.value) return
  downloading.value = true
  dialogError.value = ''
  try {
    const blob = await api.exportProjectQuotation(active.projectId, draftPayload(active))
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${active.projectName.replace(/[\\/:*?"<>|]/g, '_') || '项目'}-报价单.xlsx`
    anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (cause) {
    dialogError.value = cause instanceof Error ? cause.message : '报价导出失败'
  } finally {
    downloading.value = false
  }
}

function resetOverrides() {
  const next: Record<string, RowOverride> = {}
  for (const floor of preview.value?.floors || []) {
    for (const space of floor.spaces) {
      for (const line of space.lines) {
        next[rowKey(floor.floorId, space.zoneId, line.productId)] = { qty: line.qty, unitPrice: line.unitPrice }
      }
    }
  }
  overrides.value = next
}

function rowKey(floorId: string, zoneId: string, productId: string) {
  return `${floorId}|${zoneId}|${productId}`
}

const rows = computed<QuotationTableRow[]>(() => {
  if (!preview.value) return []
  return preview.value.floors.flatMap(floor => floor.spaces.flatMap(space => space.lines.map(line => {
    const key = rowKey(floor.floorId, space.zoneId, line.productId)
    const override = overrides.value[key] || {}
    const qty = Number(override.qty ?? line.qty)
    const rawUnitPrice = override.unitPrice ?? line.unitPrice
    const unitPrice = rawUnitPrice === null || rawUnitPrice === undefined ? null : roundMoney(Number(rawUnitPrice))
    const subtotal = unitPrice === null || !Number.isFinite(unitPrice) || !Number.isFinite(qty)
      ? null : roundMoney(unitPrice * qty)
    return {
      key,
      floorId: floor.floorId,
      zoneId: space.zoneId,
      floorName: floor.floorName,
      spaceName: space.zoneName,
      line,
      qty,
      unitPrice,
      subtotal,
    }
  })))
})

const missingPriceLines = computed(() => rows.value.filter(row => row.line.issue).length)
const goodsTotal = computed(() => roundMoney(rows.value.reduce((sum, row) => sum + (row.subtotal || 0), 0)))
const feeTotal = computed(() => roundMoney(fees.value.reduce((sum, fee) => sum + (Number(fee.amount) || 0), 0)))
const grandTotal = computed(() => roundMoney(goodsTotal.value + feeTotal.value))

function amount(n: number | null | undefined) {
  if (n === null || n === undefined || !Number.isFinite(Number(n))) return '—'
  return `¥${Number(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function modeLabel() {
  return mode.value === 'MARGIN' ? `毛利率 ${marginPercent.value}%` : `系数 ${coefficient.value}`
}
</script>

<template>
  <div class="flex min-h-full flex-col p-4 md:p-6">
    <header class="mb-4">
      <h1 class="text-lg font-bold">商品报价</h1>
      <p v-if="canPrice" class="mt-1 text-xs text-muted">
        按项目楼层与空间汇总已选商品，支持试算成交价、调整数量和费用。
      </p>
    </header>

    <p v-if="checking" class="text-sm text-muted">正在核验报价权限…</p>
    <p v-else-if="!authorized" class="border border-line bg-white px-4 py-6 text-sm text-muted">当前账号暂无报价查看权限。</p>

    <section v-else-if="!canPrice" class="space-y-4 border border-line bg-white p-4">
      <div class="flex flex-wrap items-end justify-between gap-3">
        <label class="grid gap-1 text-sm">项目
          <select v-model="projectId" aria-label="报价所属项目" class="border border-line px-3 py-2">
            <option v-for="project in projects" :key="project.projectId" :value="project.projectId">{{ project.projectName }}</option>
          </select>
        </label>
        <button v-if="canExport && record" class="rounded-lg bg-bronze px-4 py-2 text-sm text-white" :disabled="downloading" @click="downloadConfirmedQuote">导出已确认报价</button>
      </div>
      <p v-if="error" role="alert" class="text-sm text-red-700">{{ error }}</p>
      <p v-else-if="loading" class="text-sm text-muted">报价加载中…</p>
      <p v-else-if="!record" class="text-sm text-muted">暂无已确认报价</p>
      <div v-if="record" class="overflow-x-auto">
        <p class="mb-3 text-sm font-semibold">报价合计 ¥{{ record.totalAmount }}</p>
        <p v-if="record.pricingMode" class="mb-3 text-sm text-muted">{{ record.pricingMode === 'MARGIN' ? `毛利率 ${(record.pricingValue ?? 0) * 100}%` : `报价系数 ${record.pricingValue}` }}</p>
        <table class="w-full min-w-[480px] text-left text-sm">
          <thead><tr class="border-b border-line">
            <th class="p-2">商品</th><th class="p-2">规格</th><th class="p-2">数量</th>
            <th v-if="record.pricingMode" class="p-2">出厂价</th><th class="p-2">成交单价</th><th class="p-2">小计</th>
            <th v-if="record.pricingMode" class="p-2">商品毛利</th>
          </tr></thead>
          <tbody>
            <tr v-for="(line, index) in record.lines" :key="index" class="border-b border-line">
              <td class="p-2">{{ line.productName }}</td><td class="p-2">{{ line.variant || '—' }}</td><td class="p-2">{{ line.qty }}</td>
              <td v-if="record.pricingMode" class="p-2">{{ line.factoryPrice ?? '—' }}</td>
              <td class="p-2">{{ line.unitPrice ?? '—' }}</td><td class="p-2">{{ line.subtotal ?? '—' }}</td>
              <td v-if="record.pricingMode" class="p-2">{{ line.factoryPrice != null && line.subtotal != null ? amount(line.subtotal - line.factoryPrice * line.qty) : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <template v-else>
      <section class="mb-4 border border-line bg-white p-4" aria-label="报价条件">
        <div class="grid gap-3 md:grid-cols-[minmax(0,1fr)_auto]">
          <label class="block text-xs text-muted">
            项目
            <select v-model="projectId" class="mt-1 w-full border border-line bg-white px-2 py-1.5 text-sm text-ink">
              <option value="" disabled>请选择项目</option>
              <option v-for="project in projects" :key="project.projectId" :value="project.projectId">{{ project.projectName }}</option>
            </select>
          </label>
          <div class="flex flex-wrap items-end gap-2">
            <div role="group" aria-label="报价口径" class="inline-flex border border-line">
              <button
                type="button"
                class="px-3 py-1.5 text-sm"
                :class="mode === 'COEFFICIENT' ? 'bg-bronze text-white' : 'bg-white text-[#5f5b56]'"
                @click="mode = 'COEFFICIENT'; loadPreview()"
              >系数</button>
              <button
                type="button"
                class="px-3 py-1.5 text-sm"
                :class="mode === 'MARGIN' ? 'bg-bronze text-white' : 'bg-white text-[#5f5b56]'"
                @click="mode = 'MARGIN'; loadPreview()"
              >毛利率</button>
            </div>
            <label v-if="mode === 'COEFFICIENT'" class="text-xs text-muted">
              系数
              <input v-model.number="coefficient" type="number" min="0.01" max="100" step="0.01" class="ml-1 w-24 border border-line px-2 py-1.5 text-sm text-ink" aria-label="报价系数">
            </label>
            <label v-else class="text-xs text-muted">
              毛利率（%）
              <input v-model.number="marginPercent" type="number" min="1" max="99" step="1" class="ml-1 w-24 border border-line px-2 py-1.5 text-sm text-ink" aria-label="毛利率百分比">
            </label>
            <button type="button" class="bg-bronze px-3 py-1.5 text-sm text-white disabled:opacity-50" :disabled="loading || !projectId" @click="loadPreview">
              {{ loading ? '试算中…' : '重新试算' }}
            </button>
          </div>
        </div>
        <p class="mt-2 text-xs text-muted">当前口径：{{ modeLabel() }}<span v-if="currentProject"> · 项目：{{ currentProject.projectName }}</span></p>
      </section>

      <p v-if="error" role="alert" class="mb-3 border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">{{ error }}</p>
      <p v-if="!projects.length" class="border border-line bg-white px-4 py-6 text-sm text-muted">当前账号还没有可访问的项目，请先在项目工作台创建项目与楼层。</p>

      <p v-if="notice" class="mb-3 border border-line bg-white px-3 py-2 text-xs text-green">{{ notice }}</p>

      <div v-if="blockedFloors.length" class="mb-3 border border-line bg-[#faf7f3] px-3 py-2 text-xs" data-testid="quote-confirmation-blockers">
        <p class="font-semibold text-red-700">以下楼层的方案尚未确认或已失效，确认报价前需要先在项目清单页确认当前方案：</p>
        <ul class="mt-1 space-y-1">
          <li v-for="floor in blockedFloors" :key="floor.floorId" class="flex items-center gap-2">
            <span class="text-ink">{{ floor.floorName }}（{{ floor.stale ? '方案已失效，需重新确认' : '尚未确认方案' }}）</span>
            <NuxtLink :to="{ path: '/proposal', query: { floorId: floor.floorId } }" class="font-semibold text-bronze-dark hover:underline">去项目清单确认</NuxtLink>
          </li>
        </ul>
      </div>
      <section v-if="record" class="mb-3 border border-line bg-white px-4 py-3" aria-label="最近确认报价">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <p class="text-xs text-muted">
            最近确认：<span class="font-semibold text-ink">{{ record.quotationId }}</span>
            · 第 {{ record.revision }} 版
            · {{ record.status === 'CONFIRMED' ? '当前有效' : record.status === 'SUPERSEDED' ? '已被新版本取代' : '待重算' }}
            · {{ record.createdAt }}
          </p>
          <p class="text-xs text-muted">
            确认金额 <span class="font-semibold text-bronze-dark tabular-nums">{{ amount(record.totalAmount) }}</span>
            <span v-if="record.missingPriceLines" class="ml-2 text-red-700">含缺价 {{ record.missingPriceLines }} 行（未计入）</span>
          </p>
        </div>
      </section>

      <p v-if="missingPriceLines" class="mb-3 border border-line bg-cream px-3 py-2 text-xs text-muted">
        有 {{ missingPriceLines }} 行缺少出厂价或商品已不可用，已从商品金额中排除并在明细中标注，需到商品库补齐后再试算。
      </p>

      <div class="flex-1 border border-line bg-white">
        <div class="overflow-x-auto">
          <table class="w-full min-w-[860px] text-sm">
            <thead>
              <tr class="border-b border-line text-left text-xs text-muted">
                <th class="px-4 py-2 font-medium">商品</th>
                <th class="px-4 py-2 font-medium">楼层 / 空间</th>
                <th class="px-4 py-2 font-medium">规格</th>
                <th class="px-4 py-2 text-right font-medium">数量</th>
                <th class="px-4 py-2 text-right font-medium">出厂价</th>
                <th class="px-4 py-2 text-right font-medium">成交单价</th>
                <th class="px-4 py-2 text-right font-medium">小计</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading && !rows.length"><td colspan="7" class="px-4 py-6 text-center text-xs text-muted">试算中…</td></tr>
              <tr v-else-if="!rows.length"><td colspan="7" class="px-4 py-6 text-center text-xs text-muted">该项目还没有可报价的商品：请先在楼层画布中确认空间并选择商品。</td></tr>
              <tr v-for="row in rows" :key="row.key" class="border-b border-line last:border-b-0">
                <td class="px-4 py-2">
                  <div class="flex items-center gap-3">
                    <div class="h-12 w-12 shrink-0 overflow-hidden border border-line bg-[#faf7f3]">
                      <img
                        v-if="row.line.imageUrl"
                        :src="sameOriginAssetUrl(row.line.imageUrl)"
                        :alt="row.line.productName"
                        class="h-full w-full object-cover"
                        loading="lazy"
                      >
                    </div>
                    <div class="min-w-0">
                      <div class="truncate font-medium" :title="row.line.productName">{{ row.line.productName }}</div>
                      <div v-if="row.line.issue" class="mt-0.5 text-xs text-red-700">{{ row.line.issue }}</div>
                    </div>
                  </div>
                </td>
                <td class="px-4 py-2 text-xs text-muted">{{ row.floorName }} / {{ row.spaceName || '未分区商品' }}</td>
                <td class="px-4 py-2 text-xs text-muted">{{ row.line.variant || '—' }}</td>
                <td class="px-4 py-2 text-right">
                  <input
                    v-if="overrides[row.key]"
                    v-model.number="overrides[row.key].qty"
                    type="number"
                    min="0"
                    step="1"
                    class="w-20 border border-line px-2 py-1 text-right text-sm tabular-nums outline-bronze"
                    :aria-label="row.line.productName + ' 数量'"
                  >
                  <span v-else class="tabular-nums">{{ row.qty }}</span>
                </td>
                <td class="px-4 py-2 text-right tabular-nums">{{ amount(row.line.factoryPrice) }}</td>
                <td class="px-4 py-2 text-right">
                  <input
                    v-if="overrides[row.key] && !row.line.issue"
                    v-model.number="overrides[row.key].unitPrice"
                    type="number"
                    min="0"
                    step="0.01"
                    class="w-24 border border-line px-2 py-1 text-right text-sm tabular-nums outline-bronze"
                    :aria-label="row.line.productName + ' 成交单价'"
                  >
                  <span v-else class="tabular-nums">{{ amount(row.unitPrice) }}</span>
                </td>
                <td class="px-4 py-2 text-right font-semibold tabular-nums" :class="row.line.issue ? 'text-muted' : ''">{{ amount(row.subtotal) }}</td>
              </tr>
            </tbody>
            <tbody>
              <tr v-for="(fee, index) in fees" :key="fee.key" class="border-b border-line bg-[#faf7f3]">
                <td colspan="4" class="px-4 py-2">
                  <div class="flex flex-wrap items-center gap-2">
                    <input
                      v-model="fee.name"
                      type="text"
                      maxlength="100"
                      placeholder="费用名称，如 设计服务费"
                      class="w-56 border border-line bg-white px-2 py-1 text-xs text-ink outline-bronze"
                      :aria-label="`费用名称 ${index + 1}`"
                    >
                    <button type="button" class="border border-line px-2 py-1 text-xs text-muted hover:text-red-700" :aria-label="`删除费用 ${index + 1}`" @click="removeFee(index)">删除</button>
                  </div>
                </td>
                <td colspan="2" class="px-4 py-2 text-right">
                  <input
                    v-model.number="fee.amount"
                    type="number"
                    min="0"
                    step="0.01"
                    placeholder="0.00"
                    class="w-28 border border-line bg-white px-2 py-1 text-right text-sm tabular-nums outline-bronze"
                    :aria-label="`费用金额 ${index + 1}`"
                  >
                </td>
                <td class="px-4 py-2 text-right tabular-nums">{{ amount(fee.amount) }}</td>
              </tr>
              <tr class="border-b border-line bg-[#faf7f3]">
                <td colspan="7" class="px-4 py-2">
                  <button type="button" class="border border-line bg-white px-3 py-1.5 text-xs text-bronze-dark" @click="addFee">＋ 添加其他费用</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="sticky bottom-0 z-10 mt-3 border border-line bg-white px-4 py-3 shadow-[0_-2px_10px_rgba(0,0,0,0.06)]">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div class="flex flex-wrap items-center gap-x-5 gap-y-1 text-xs text-muted">
            <span>商品小计 <span class="font-semibold text-ink tabular-nums">{{ amount(goodsTotal) }}</span></span>
            <span>费用合计 <span class="tabular-nums">{{ amount(feeTotal) }}</span></span>
            <span v-if="missingPriceLines" class="text-red-700">缺价 {{ missingPriceLines }} 行</span>
          </div>
          <div class="flex items-center gap-3">
            <div class="flex items-baseline gap-2">
              <span class="text-xs text-muted">商品报价合计</span>
              <span class="text-xl font-bold text-bronze-dark tabular-nums">{{ amount(grandTotal) }}</span>
            </div>
            <button
              type="button"
              class="bg-bronze px-4 py-2 text-sm text-white disabled:opacity-50"
              :disabled="!rows.length || loading || confirmationsLoading || blockedFloors.length > 0"
              @click="openConfirmation"
            >确认报价</button>
          </div>
        </div>
      </div>

      <p v-if="preview" class="mt-2 text-xs text-muted">试算时间：{{ preview.generatedAt }} · 口径 {{ preview.pricingMode }} = {{ preview.pricingValue }}（出厂价来自主项目本厂报价；未确认的手填调整仅本页有效，确认后随报价快照保存）</p>

      <QuotationPreviewDialog
        v-if="draft"
        :project-name="draft.projectName"
        :pricing-label="draft.pricingLabel"
        :generated-at="draft.generatedAt"
        :rows="draft.rows"
        :fees="draft.fees"
        :goods-total="draft.goodsTotal"
        :fee-total="draft.feeTotal"
        :grand-total="draft.grandTotal"
        :confirming="confirming"
        :downloading="downloading"
        :error="dialogError"
        @confirm="confirmQuotation"
        @cancel="closeConfirmation"
        @download="downloadQuotation"
      />
    </template>
  </div>
</template>
