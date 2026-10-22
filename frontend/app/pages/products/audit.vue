<script setup lang="ts">
import type { ImportedProduct } from '~/types/imported-products'
import { sameOriginAssetUrl } from '~/utils/apiBase'

const api = useOfficeApi()
const products = ref<ImportedProduct[]>([])
const isLoading = ref(true)
const isBatchProcessing = ref(false)
const error = ref<string | null>(null)
const notice = ref<string | null>(null)
const rejectDialog = ref<HTMLDialogElement | null>(null)
const approveAllDialog = ref<HTMLDialogElement | null>(null)
const rejectIds = ref<string[]>([])
const rejectRemark = ref('')
const selectedIds = ref<Set<string>>(new Set())

const allSelected = computed(() => products.value.length > 0 && products.value.every(product => selectedIds.value.has(product.rspuId)))
const someSelected = computed(() => products.value.some(product => selectedIds.value.has(product.rspuId)) && !allSelected.value)

async function loadPending() {
  isLoading.value = true
  error.value = null
  try {
    products.value = await api.loadPendingProducts()
    selectedIds.value = new Set()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载待审核商品失败'
  } finally {
    isLoading.value = false
  }
}

function formatPrice(price: number | null) {
  return price == null ? '—' : `¥ ${price.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function formatDate(date: string) {
  const timestamp = new Date(date)
  return Number.isNaN(timestamp.getTime()) ? '—' : timestamp.toLocaleDateString('zh-CN')
}

function toggleProduct(rspuId: string) {
  const next = new Set(selectedIds.value)
  if (next.has(rspuId)) next.delete(rspuId)
  else next.add(rspuId)
  selectedIds.value = next
}

function toggleAll() {
  selectedIds.value = allSelected.value ? new Set() : new Set(products.value.map(product => product.rspuId))
}

function clearSelection() {
  selectedIds.value = new Set()
}

function batchResultMessage(action: string, processedCount: number, skippedCount: number) {
  return skippedCount > 0
    ? `已${action} ${processedCount} 件商品，${skippedCount} 件已处理或不存在被跳过`
    : `已${action} ${processedCount} 件商品`
}

function openReject(product: ImportedProduct) {
  rejectIds.value = [product.rspuId]
  rejectRemark.value = ''
  rejectDialog.value?.showModal()
}

function openBatchReject() {
  if (!selectedIds.value.size || isBatchProcessing.value) return
  rejectIds.value = [...selectedIds.value]
  rejectRemark.value = ''
  rejectDialog.value?.showModal()
}

function closeReject() {
  rejectDialog.value?.close()
  rejectIds.value = []
}

async function approve(product: ImportedProduct) {
  error.value = null
  notice.value = null
  try {
    await api.approveProduct(product.rspuId)
    await loadPending()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '审核通过失败'
  }
}

async function approveSelected() {
  if (!selectedIds.value.size || isBatchProcessing.value) return
  isBatchProcessing.value = true
  error.value = null
  notice.value = null
  try {
    const result = await api.approveProductsBatch([...selectedIds.value])
    await loadPending()
    notice.value = batchResultMessage('通过', result.processedCount, result.skippedCount)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '批量审核通过失败'
  } finally {
    isBatchProcessing.value = false
  }
}

async function confirmReject() {
  if (!rejectIds.value.length || isBatchProcessing.value) return
  isBatchProcessing.value = true
  error.value = null
  notice.value = null
  try {
    if (rejectIds.value.length === 1) {
      await api.rejectProduct(rejectIds.value[0], rejectRemark.value || undefined)
    } else {
      const result = await api.rejectProductsBatch(rejectIds.value, rejectRemark.value || undefined)
      notice.value = batchResultMessage('驳回', result.processedCount, result.skippedCount)
    }
    closeReject()
    await loadPending()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '审核驳回失败'
  } finally {
    isBatchProcessing.value = false
  }
}

function openApproveAll() {
  if (!products.value.length || isBatchProcessing.value) return
  approveAllDialog.value?.showModal()
}

function closeApproveAll() {
  approveAllDialog.value?.close()
}

async function confirmApproveAll() {
  if (isBatchProcessing.value) return
  isBatchProcessing.value = true
  error.value = null
  notice.value = null
  try {
    const result = await api.approveAllPendingProducts()
    closeApproveAll()
    await loadPending()
    notice.value = `一键审核完成，已通过 ${result.processedCount} 件待审核商品`
  } catch (err) {
    error.value = err instanceof Error ? err.message : '一键审核通过失败'
  } finally {
    isBatchProcessing.value = false
  }
}

onMounted(loadPending)
</script>

<template>
  <div class="flex h-full min-h-0 flex-col gap-5 overflow-y-auto">
    <div class="flex-none">
      <div class="mb-3 text-xs text-muted">
        <NuxtLink to="/products" class="hover:text-bronze-dark">商品资源 / 商品库</NuxtLink> / 商品审核
      </div>
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 class="mb-2 text-[25px] font-bold tracking-tight">商品审核</h1>
          <p class="text-xs text-muted">审核新导入的商品，通过后方可进入商品库供设计使用。</p>
        </div>
        <div class="flex flex-wrap items-center gap-3">
          <button
            type="button"
            class="inline-flex items-center gap-2 rounded-lg bg-green px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-green/90 disabled:cursor-not-allowed disabled:opacity-45"
            :disabled="isLoading || isBatchProcessing || products.length === 0"
            @click="openApproveAll"
          >
            <AppIcon name="checkCircle" size="4" />一键全部通过（{{ products.length }}）
          </button>
          <NuxtLink to="/products" class="inline-flex items-center gap-2 rounded-lg border border-line bg-white px-4 py-2.5 text-sm hover:bg-cream">
            <AppIcon name="arrowLeft" size="4" />返回商品库
          </NuxtLink>
        </div>
      </div>
    </div>

    <div class="flex flex-none flex-wrap items-center gap-x-6 gap-y-2 text-xs text-muted">
      <span>待审核 <strong class="ml-1 text-sm text-ink">{{ isLoading ? '—' : products.length }}</strong></span>
      <span v-if="selectedIds.size">已选择 <strong class="ml-1 text-sm text-ink">{{ selectedIds.size }}</strong> 件</span>
      <div v-if="selectedIds.size" class="flex items-center gap-2">
        <button
          type="button"
          class="rounded-lg bg-green/10 px-3 py-1.5 text-xs font-semibold text-green transition hover:bg-green/20 disabled:cursor-not-allowed disabled:opacity-45"
          :disabled="isBatchProcessing"
          @click="approveSelected"
        >批量通过（{{ selectedIds.size }}）</button>
        <button
          type="button"
          class="rounded-lg bg-red-50 px-3 py-1.5 text-xs font-semibold text-red-700 transition hover:bg-red-100 disabled:cursor-not-allowed disabled:opacity-45"
          :disabled="isBatchProcessing"
          @click="openBatchReject"
        >批量驳回（{{ selectedIds.size }}）</button>
        <button type="button" class="px-2 py-1.5 text-xs text-muted hover:text-ink" :disabled="isBatchProcessing" @click="clearSelection">清空选择</button>
      </div>
    </div>

    <div v-if="error" class="flex-none rounded-xl border border-red-100 bg-red-50 p-4 text-sm text-red-800" role="alert">
      <p>{{ error }}</p>
    </div>

    <div v-if="notice" class="flex-none rounded-xl border border-green/20 bg-green/5 p-4 text-sm text-green" role="status">
      <p>{{ notice }}</p>
    </div>

    <section class="flex-1 min-h-0 overflow-hidden rounded-xl border border-line bg-white shadow-panel" aria-label="待审核商品列表">
      <div v-if="isLoading" class="grid min-h-[320px] place-items-center p-6">
        <div class="flex flex-col items-center gap-3 text-muted">
          <AppIcon name="reset" size="7" class="animate-spin" />
          <span class="text-sm">加载待审核商品中…</span>
        </div>
      </div>

      <div v-else-if="products.length === 0" class="grid min-h-[320px] place-items-center p-6">
        <div class="text-center">
          <div class="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-xl bg-cream text-bronze">
            <AppIcon name="checkCircle" size="6" />
          </div>
          <h2 class="mb-2 text-base font-bold">暂无待审核商品</h2>
          <p class="mb-5 text-sm text-muted">所有商品已审核完毕，或尚未导入新商品。</p>
          <NuxtLink to="/products/import" class="inline-flex items-center gap-2 rounded-lg bg-bronze px-5 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark">
            <AppIcon name="import" size="4" />去导入商品
          </NuxtLink>
        </div>
      </div>

      <div v-else class="overflow-x-auto">
        <table class="w-full whitespace-nowrap text-left text-sm">
          <thead class="bg-bg text-xs text-muted">
            <tr>
              <th class="w-12 px-5 py-3 font-medium">
                <input type="checkbox" :checked="allSelected" :indeterminate="someSelected" :disabled="isBatchProcessing" aria-label="选择全部待审核商品" @change="toggleAll">
              </th>
              <th class="px-2 py-3 font-medium">商品名称 / 编码</th>
              <th class="px-4 py-3 text-right font-medium">零售价</th>
              <th class="px-4 py-3 font-medium">导入时间</th>
              <th class="px-5 py-3 text-right font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="product in products" :key="product.rspuId" class="border-t border-line transition hover:bg-bg/70">
              <td class="px-5 py-3">
                <input type="checkbox" :checked="selectedIds.has(product.rspuId)" :disabled="isBatchProcessing" :aria-label="`选择${product.productName || '商品'}`" @change="toggleProduct(product.rspuId)">
              </td>
              <td class="px-2 py-3">
                <div class="flex items-center gap-3">
                  <div class="h-10 w-10 shrink-0 overflow-hidden rounded-lg bg-cream">
                    <img v-if="product.primaryImageUrl" :src="sameOriginAssetUrl(product.primaryImageUrl)" :alt="product.productName || ''" class="h-full w-full object-cover">
                    <div v-else class="grid h-full w-full place-items-center text-bronze/50"><AppIcon name="cube" size="5" /></div>
                  </div>
                  <div class="min-w-0">
                    <div class="truncate font-medium">{{ product.productName || '未命名商品' }}</div>
                    <div class="text-xs text-muted">{{ product.rspuCode || product.rspuId }}</div>
                  </div>
                </div>
              </td>
              <td class="px-4 py-3 text-right">{{ formatPrice(product.retailPrice) }}</td>
              <td class="px-4 py-3 text-xs text-muted">{{ formatDate(product.createdAt) }}</td>
              <td class="px-5 py-3 text-right">
                <div class="flex items-center justify-end gap-2">
                  <button type="button" class="rounded-lg bg-green/10 px-3 py-1.5 text-xs font-semibold text-green hover:bg-green/20 disabled:cursor-not-allowed disabled:opacity-45" :disabled="isBatchProcessing" @click="approve(product)">通过</button>
                  <button type="button" class="rounded-lg bg-red-50 px-3 py-1.5 text-xs font-semibold text-red-700 hover:bg-red-100 disabled:cursor-not-allowed disabled:opacity-45" :disabled="isBatchProcessing" @click="openReject(product)">驳回</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <dialog ref="rejectDialog" class="rounded-2xl border border-line bg-white p-6 shadow-panel backdrop:bg-black/20">
      <h3 class="mb-2 text-base font-semibold">驳回商品{{ rejectIds.length > 1 ? `（${rejectIds.length} 件）` : '' }}</h3>
      <p class="mb-4 text-xs text-muted">驳回后{{ rejectIds.length > 1 ? '这些商品' : '该商品' }}不会进入商品库。请填写驳回原因。</p>
      <textarea v-model="rejectRemark" rows="3" class="mb-4 w-full rounded-lg border border-line p-3 text-sm outline-bronze" placeholder="例如：商品信息缺失、价格异常…"></textarea>
      <div class="flex justify-end gap-3">
        <button type="button" class="rounded-lg border border-line px-4 py-2 text-sm hover:bg-cream" :disabled="isBatchProcessing" @click="closeReject">取消</button>
        <button type="button" class="rounded-lg bg-red-700 px-4 py-2 text-sm font-semibold text-white hover:bg-red-800 disabled:cursor-not-allowed disabled:opacity-45" :disabled="isBatchProcessing" @click="confirmReject">确认驳回</button>
      </div>
    </dialog>

    <dialog ref="approveAllDialog" class="rounded-2xl border border-line bg-white p-6 shadow-panel backdrop:bg-black/20">
      <h3 class="mb-2 text-base font-semibold">一键通过全部待审核商品？</h3>
      <p class="mb-4 text-xs text-muted">
        将把当前 <strong class="text-ink">{{ products.length }}</strong> 件待审核商品全部标记为审核通过并进入商品库。操作前请确认这些商品信息已核对无误。
      </p>
      <div class="flex justify-end gap-3">
        <button type="button" class="rounded-lg border border-line px-4 py-2 text-sm hover:bg-cream" :disabled="isBatchProcessing" @click="closeApproveAll">取消</button>
        <button type="button" class="rounded-lg bg-green px-4 py-2 text-sm font-semibold text-white hover:bg-green/90 disabled:cursor-not-allowed disabled:opacity-45" :disabled="isBatchProcessing" @click="confirmApproveAll">
          {{ isBatchProcessing ? '正在通过…' : '确认全部通过' }}
        </button>
      </div>
    </dialog>
  </div>
</template>