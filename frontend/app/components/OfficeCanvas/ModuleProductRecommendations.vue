<script setup lang="ts">
import type { ModuleRecommendation, ModuleProductCandidate } from '~/types/moduleRecommendation'
import type { FurnitureObject } from '~/types/office'
import { applyModuleProductSelection } from '~/utils/moduleProductSelection'
import { money } from '~/utils/helpers'
import { colorToneLabel, matchesProductColorSearch, primaryColorName, productColorTone } from '~/utils/productColor'

const selectedId = defineModel<string | null>('selectedId')
const store = useOfficeStore()
const api = useOfficeApi()
const allowRotation = ref(true)
const applyToSameModules = ref(false)
const tier = ref('ALL')
const searchKeyword = ref('')
const colorFilter = ref('ALL')
const result = ref<ModuleRecommendation | null>(null)
const loading = ref(false), applying = ref(false), error = ref(''), feedback = ref('')
const modules = computed(() => store.furnitureForZone(store.designZoneId || ''))
const module = computed(() => modules.value.find(item => item.id === selectedId.value))
type ModuleDisplayGroup = { key: string; representative: FurnitureObject; modules: FurnitureObject[] }

function stableJson(value: unknown): string {
  if (value === null) return 'null'
  if (Array.isArray(value)) return `[${value.map(item => stableJson(item)).join(',')}]`
  if (typeof value === 'object') {
    return `{${Object.entries(value as Record<string, unknown>)
      .filter(([, item]) => item !== undefined && typeof item !== 'function' && typeof item !== 'symbol')
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, item]) => `${JSON.stringify(key)}:${stableJson(item)}`)
      .join(',')}}`
  }
  return JSON.stringify(value) ?? 'null'
}

function sameModuleKey(item: FurnitureObject): string {
  const savedField = (key: 'furnitureTemplateId' | 'moduleSnapshot' | 'heightMm') =>
    item[key] === undefined ? '__missing__' : stableJson(item[key])
  return [savedField('furnitureTemplateId'), savedField('moduleSnapshot'), savedField('heightMm'), stableJson(item.w), stableJson(item.h)].join('\u001f')
}

/** 选品计数与分组共用同一份键（含高度/模板），与服务端 sameModule 对齐，避免不同规格串用计数。 */
function moduleSpecificationKey(item: FurnitureObject): string {
  return sameModuleKey(item)
}

const moduleGroups = computed<ModuleDisplayGroup[]>(() => {
  const groups = new Map<string, ModuleDisplayGroup>()
  for (const item of modules.value) {
    const key = sameModuleKey(item)
    const group = groups.get(key)
    if (group) group.modules.push(item)
    else groups.set(key, { key, representative: item, modules: [item] })
  }
  return [...groups.values()]
})

const selectedModuleCounts = computed(() => {
  const cartByLineId = new Map(store.cart.map(item => [item.id, item]))
  const counts = new Map<string, number>()
  for (const item of modules.value) {
    const selection = item.productSelection
    const cartItem = selection ? cartByLineId.get(selection.cartLineId) : undefined
    if (!selection?.cartLineId || cartItem?.productId !== item.productId) continue
    const key = moduleSpecificationKey(item)
    counts.set(key, (counts.get(key) || 0) + 1)
  }
  return counts
})

function groupSelectedCount(group: ModuleDisplayGroup): number {
  return selectedModuleCounts.value.get(moduleSpecificationKey(group.representative)) || 0
}

function groupOptionValue(group: ModuleDisplayGroup): string {
  return group.modules.some(item => item.id === module.value?.id) ? module.value!.id : group.representative.id
}

const sameModuleCount = computed(() => result.value?.sameModuleCount || 0)
const allConfirmed = computed(() => modules.value.length > 0 && modules.value.every(item => item.layoutConfirmed))
const layoutContext = computed(() => JSON.stringify([store.workspaceId, store.designZoneId, store.canvasRevision, allConfirmed.value]))
const signature = computed(() => JSON.stringify([layoutContext.value, module.value?.id, allowRotation.value]))
const sizeCandidates = computed(() => (result.value?.candidates || []).filter(item => tier.value === 'ALL' || item.tier === tier.value))
const colorInfo = (candidate: ModuleProductCandidate) => ({
  primary: primaryColorName(candidate.product.colorPrimaryName),
  tone: productColorTone(candidate.product.colorPrimaryName),
})
const primaryColorOptions = computed(() => {
  const counts = new Map<string, number>()
  for (const candidate of sizeCandidates.value) {
    const primary = colorInfo(candidate).primary
    if (primary) counts.set(primary, (counts.get(primary) || 0) + 1)
  }
  return [...counts.entries()].map(([name, count]) => ({ name, count }))
    .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name, 'zh-CN'))
})
const hasUnknownColors = computed(() => sizeCandidates.value.some(candidate => colorInfo(candidate).tone === 'UNKNOWN'))
const candidates = computed(() => sizeCandidates.value.filter(candidate => {
  const info = colorInfo(candidate)
  if (colorFilter.value.startsWith('TONE:') && info.tone !== colorFilter.value.slice(5)) return false
  if (colorFilter.value.startsWith('PRIMARY:') && info.primary !== colorFilter.value.slice(8)) return false
  return matchesProductColorSearch(candidate.product, searchKeyword.value)
}))
const groupedCandidates = computed(() => {
  const groups = new Map<string, { label: string, items: ModuleProductCandidate[], topScore: number, firstIndex: number }>()
  candidates.value.forEach((candidate, index) => {
    const primary = colorInfo(candidate).primary
    const key = primary || '__UNCLASSIFIED__'
    const score = candidate.relevanceScore ?? -1
    const group = groups.get(key) || { label: primary || '未分类', items: [], topScore: score, firstIndex: index }
    group.items.push(candidate)
    group.topScore = Math.max(group.topScore, score)
    groups.set(key, group)
  })
  return [...groups.values()].sort((a, b) => b.topScore - a.topScore || a.firstIndex - b.firstIndex)
})
const filtersDisabled = computed(() => !module.value || loading.value || !result.value)
const clearColorFilters = () => { searchKeyword.value = ''; colorFilter.value = 'ALL' }
const tiers = [{ key: 'ALL', name: '全部兼容', short: '全部' }, { key: 'CLOSE', name: '贴合 ≥85%', short: '≥85%' }, { key: 'RELAXED', name: '宽松 60–85%', short: '60–85%' }, { key: 'SMALL', name: '偏小 <60%', short: '<60%' }]
let sequence = 0
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
const number = (value: number) => Number(value.toFixed(2)).toLocaleString()
const message = (failure: any) => failure?.data?.message || failure?.message || '读取失败，请重试'

watch(() => module.value?.id, () => {
  applyToSameModules.value = false
  tier.value = 'ALL'
  clearColorFilters()
})
watch(tier, () => {
  if (colorFilter.value.startsWith('PRIMARY:') && !primaryColorOptions.value.some(option => colorFilter.value === 'PRIMARY:' + option.name)) colorFilter.value = 'ALL'
})

async function refresh() {
  if (applying.value) return
  const ticket = ++sequence, current = signature.value, item = module.value
  result.value = null; error.value = ''; feedback.value = ''
  if (!item || !allConfirmed.value) { loading.value = false; return }
  loading.value = true
  const revision = store.canvasRevision, zoneId = item.zoneId, rotation = allowRotation.value
  try {
    // Flush the exact reviewed draft; the server reads saved geometry and checks its revision.
    await api.saveCanvas({ objects: JSON.parse(JSON.stringify(store.objects)), files: store.files, mode: store.canvasMode,
      designZoneId: store.designZoneId, revision, blank: store.blank })
    if (ticket !== sequence || current !== signature.value) return
    const response = await api.recommendModule({ zoneId, moduleId: item.id, canvasRevision: revision, allowRotation: rotation })
    if (ticket !== sequence || current !== signature.value) return
    if (!response || response.moduleId !== item.id || response.zoneId !== zoneId || response.canvasRevision !== revision || !Array.isArray(response.candidates)) throw new Error('商品推荐接口尚未就绪，请启动最新后端')
    result.value = response
  } catch (failure) {
    if (ticket === sequence && current === signature.value) error.value = message(failure)
  } finally { if (ticket === sequence) loading.value = false }
}
watch(signature, () => {
  sequence++; clearTimeout(timer); result.value = null; error.value = ''; feedback.value = ''; loading.value = false
  if (!applying.value && module.value && allConfirmed.value) {
    loading.value = true
    timer = setTimeout(refresh, 250)
  }
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; sequence++; clearTimeout(timer) })

async function choose(candidate: ModuleProductCandidate) {
  const recommendation = result.value
  if (!recommendation || recommendation.moduleId !== module.value?.id || recommendation.canvasRevision !== store.canvasRevision || applying.value) return
  const current = signature.value
  const context = layoutContext.value
  let appliedCount: number | undefined
  let selectionError = ''
  applying.value = true; error.value = ''; feedback.value = ''
  try {
    const response = await api.selectModuleProduct({ zoneId: recommendation.zoneId, moduleId: recommendation.moduleId,
      canvasRevision: recommendation.canvasRevision, allowRotation: allowRotation.value, productId: candidate.product.id,
      applyToSameModules: applyToSameModules.value && sameModuleCount.value > 1 })
    if (context !== layoutContext.value) throw new Error('布局已变更，请重新匹配，已保存的商品选择请在方案中核对')
    if (!response?.canvas || !Array.isArray(response.cartItems)) throw new Error('保存结果无效，请重新读取')
    applyModuleProductSelection(store, response)
    appliedCount = response.cartItems.length
  } catch (failure) {
    selectionError = message(failure)
  }
  finally { applying.value = false }
  await nextTick()
  if (disposed) return
  const refreshed = signature.value
  if (current !== refreshed || !result.value) {
    clearTimeout(timer)
    await refresh()
  }
  if (refreshed !== signature.value || module.value?.id !== recommendation.moduleId) return
  if (selectionError) error.value = selectionError
  if (appliedCount !== undefined) feedback.value = `已为 ${appliedCount} 个模块匹配商品，模块占地保持不变`
}
</script>

<template>
  <section data-testid="module-product-recommendations" class="flex flex-col flex-1 min-h-0">
    <div class="p-3 border-b border-line space-y-2 flex-none">
      <div class="flex items-center justify-between"><h3 class="text-sm font-bold">按家具品类和尺寸推荐</h3><button class="text-xs text-bronze disabled:opacity-50" :disabled="!module || loading || applying" @click="refresh">重新匹配</button></div>
      <label class="block text-[11px] text-muted">当前家具
        <select aria-label="匹配单位模块" :value="module?.id || ''" class="mt-1 w-full rounded-none border border-line bg-white px-2 py-2 text-xs" @change="selectedId = ($event.target as HTMLSelectElement).value || null">
          <option value="" disabled>选择家具</option>
          <option v-for="(group, index) in moduleGroups" :key="group.key" :value="groupOptionValue(group)">{{ index + 1 }}. {{ group.representative.label || '单位模块' }} · {{ number(group.representative.w) }} × {{ number(group.representative.h) }} mm · 数量：{{ group.modules.length }}<template v-if="groupSelectedCount(group) > 0"> · 已选 {{ groupSelectedCount(group) }}/{{ group.modules.length }}</template></option>
        </select>
      </label>
      <div class="flex items-center gap-2">
        <input v-model="searchKeyword" data-testid="color-search" type="search" aria-label="搜索颜色或商品名" placeholder="搜索颜色 / 商品名" :disabled="filtersDisabled" class="min-w-0 flex-1 rounded-none border border-line bg-white px-2 py-2 text-xs disabled:opacity-50">
        <select v-model="colorFilter" data-testid="color-filter" aria-label="色系筛选" :disabled="filtersDisabled" class="w-28 flex-none rounded-none border border-line bg-white px-2 py-2 text-xs disabled:opacity-50">
          <option value="ALL">全部色系</option>
          <option value="TONE:DARK">深色系</option>
          <option value="TONE:LIGHT">浅色系</option>
          <option v-if="hasUnknownColors" value="TONE:UNKNOWN">未分类</option>
          <optgroup v-if="primaryColorOptions.length" label="具体主色">
            <option v-for="option in primaryColorOptions" :key="option.name" :value="'PRIMARY:' + option.name">{{ option.name }} ({{ option.count }})</option>
          </optgroup>
        </select>
      </div>
      <div v-if="module" class="rounded-none bg-[#f7f3ee] p-2 text-xs leading-relaxed"><div v-if="result?.targetCategory">推荐品类：{{ result.targetCategory }}</div>商品宽 {{ number(Math.max(0, module.w - 500)) }} ~ {{ number(module.w) }} mm，深 {{ number(Math.max(0, module.h - 500)) }} ~ {{ number(module.h) }} mm</div>
      <div v-if="module" class="flex flex-wrap gap-x-3 gap-y-1 text-[11px]">
        <label><input v-model="allowRotation" type="checkbox" :disabled="applying"> 允许旋转 90°</label>
        <label v-if="sameModuleCount > 1"><input v-model="applyToSameModules" type="checkbox" :disabled="applying"> 同步 {{ sameModuleCount }} 个同规格模块</label>
      </div>
      <div v-if="result" class="flex flex-nowrap gap-1" data-testid="tier-row"><button v-for="entry in tiers" :key="entry.key" :class="['px-2 py-1 rounded-none text-[10px]', tier === entry.key ? 'bg-bronze text-white' : 'bg-[#f7f4f0] text-muted']" @click="tier = entry.key" :title="entry.name">{{ entry.short }} <span>({{ entry.key === 'ALL' ? result.candidates.length : result.candidates.filter(item => item.tier === entry.key).length }})</span></button></div>
      <p v-if="module" class="text-[10px] text-muted">按品类相关性和尺寸贴合度排序。上方档位筛选尺寸贴合度，取宽、深占比中的较低值。</p>
    </div>
    <div :key="module?.id" class="flex-1 min-h-[160px] overflow-auto p-3 space-y-2" :aria-busy="loading || applying">
      <p v-if="!allConfirmed" class="text-xs text-muted py-5">请先确认当前空间的全部排版</p>
      <p v-else-if="!module" class="text-xs text-muted py-5">点击画布上的家具，或从上方列表选择。</p>
      <p v-else-if="applying && !result" role="status" class="text-xs text-muted py-5">正在保存刚才的商品选择…</p>
      <p v-else-if="loading" role="status" class="text-xs text-muted py-5">正在读取真实商品规格…</p>
      <p v-if="error" role="alert" class="text-xs text-red-700 p-2 bg-red-50 rounded">{{ error }}</p>
      <p v-if="feedback" role="status" class="text-xs text-green-700 p-2 bg-green-50 rounded">{{ feedback }}</p>
      <template v-if="result && !loading">
        <p v-if="result.missingDimensions" class="text-[10px] text-muted">{{ result.missingDimensions }} 个在售规格缺少可靠宽深，待核对后参与匹配。</p>
        <template v-for="group in groupedCandidates" :key="group.label">
          <div class="flex items-center justify-between pt-1 text-[11px] font-semibold text-muted" data-testid="color-group"><span>{{ group.label }}</span><span>{{ group.items.length }} 件</span></div>
        <article v-for="candidate in group.items" :key="candidate.product.id" :data-product-id="candidate.product.id" class="border border-line rounded-none p-2.5 space-y-1.5">
          <div class="flex gap-2">
            <div class="h-14 w-14 shrink-0 overflow-hidden bg-[#f7f4f0]">
              <ProductImagePreview :src="candidate.product.imageUrl" :alt="candidate.product.name || '商品图片'" size="small" :left-gap="23" preview-boundary-selector="[data-testid='office-design-canvas']" click-fallback>
                <template #preview-side>
                  <div class="flex flex-col text-xs text-ink">
                    <div class="text-[15px] font-bold leading-snug">{{ candidate.product.name }}</div>
                    <div v-if="candidate.product.variant" class="mt-0.5 text-[11px] text-muted">{{ candidate.product.variant }}</div>
                    <div class="my-3">
                      <div class="text-[10px] text-muted">价格</div>
                      <div data-testid="preview-price" class="text-[22px] font-bold leading-snug text-bronze">{{ candidate.product.price > 0 ? money(candidate.product.price) : '待报价' }}</div>
                    </div>
                    <div class="flex items-start justify-between gap-3 leading-7"><span class="shrink-0 text-muted">尺寸</span><strong class="min-w-0 break-words text-right font-semibold">{{ number(candidate.product.widthMm!) }} × {{ number(candidate.product.depthMm!) }} mm</strong></div>
                    <div v-if="candidate.product.heightMm" class="flex items-start justify-between gap-3 leading-7"><span class="shrink-0 text-muted">高度</span><strong class="min-w-0 break-words text-right font-semibold">{{ number(candidate.product.heightMm) }} mm</strong></div>
                    <div class="flex items-start justify-between gap-3 leading-7"><span class="shrink-0 text-muted">贴合度</span><strong class="min-w-0 break-words text-right font-semibold">{{ Math.floor(candidate.fitRatio * 100) }}% · {{ tiers.find(entry => entry.key === candidate.tier)?.name }}</strong></div>
                    <div class="leading-7"><span class="text-muted">主色</span><strong class="block break-words font-semibold">{{ candidate.product.colorPrimaryName || '未分类' }}<span v-if="candidate.product.colorPrimaryName"> · {{ colorToneLabel(colorInfo(candidate).tone) }}</span></strong></div>
                    <div v-if="candidate.relevanceScore != null" class="mt-3"><span class="rec-badge inline-block bg-bronze px-3 py-1 text-[11px] font-semibold text-white">适配度 {{ number(candidate.relevanceScore) }} / 100</span></div>
                  </div>
                </template>
              </ProductImagePreview>
            </div>
            <div class="min-w-0"><div class="text-xs font-bold">{{ candidate.product.name }}</div><div class="text-[10px] text-muted">{{ candidate.product.variant }}</div><div class="text-xs mt-1">{{ number(candidate.product.widthMm!) }} × {{ number(candidate.product.depthMm!) }} mm</div></div>
          </div>
          <div v-if="candidate.relevanceScore != null" data-testid="product-relevance" class="text-[11px] font-semibold text-bronze">适配度 {{ number(candidate.relevanceScore) }} / 100 · {{ candidate.matchReason }}</div>
          <div class="text-[10px] text-muted">尺寸 {{ Math.floor(candidate.fitRatio * 100) }}% 贴合<span v-if="candidate.rotationDeg"> · 需旋转 90°</span> · {{ tiers.find(entry => entry.key === candidate.tier)?.name }}</div>
          <div class="text-[10px] text-muted">剩余宽 {{ number(candidate.remainingWidthMm) }} / 深 {{ number(candidate.remainingDepthMm) }} mm<span v-if="candidate.product.heightMm"> · 商品高 {{ number(candidate.product.heightMm) }} mm</span></div>
          <div class="flex items-center justify-between gap-1"><strong class="text-sm text-bronze">{{ candidate.product.price > 0 ? money(candidate.product.price) : '待报价' }}</strong><NuxtLink :to="'/products/' + candidate.product.rspuId" class="text-[10px] text-muted hover:underline">详情</NuxtLink><button :disabled="applying || candidate.product.price <= 0" class="text-[10px] px-2 py-1.5 rounded-none bg-bronze text-white disabled:opacity-50" @click="choose(candidate)">{{ candidate.product.price <= 0 ? '待补报价' : applying ? '保存中…' : applyToSameModules && sameModuleCount > 1 ? `应用到 ${sameModuleCount} 个模块` : '应用到当前家具' }}</button></div>
          <div data-testid="product-color" class="text-[10px] text-muted">主色：{{ candidate.product.colorPrimaryName || '未分类' }}<span v-if="candidate.product.colorPrimaryName"> · {{ colorToneLabel(colorInfo(candidate).tone) }}</span></div>
        </article>
        </template>
        <div v-if="!candidates.length" class="text-xs text-muted py-5 space-y-2">
          <p v-if="result.targetCategory === null">当前家具品类未确定。可回到「产品选型」选择对应的家具图例，再确认排版。</p>
          <template v-else-if="result.eligibleVariantCount === 0"><p>暂无符合推荐条件的在售规格，请核对商品和规格的在售状态。</p><NuxtLink to="/products" class="text-bronze underline">查看商品库</NuxtLink></template>
          <p v-else-if="!result.candidates.length">暂无品类相关且尺寸可容纳的在售商品。</p>
          <p v-else-if="!sizeCandidates.length">该尺寸档暂无商品，可切换其他档位。</p>
          <p v-else>当前颜色或搜索条件下无商品。<button class="text-bronze underline" @click="clearColorFilters">清空颜色与搜索</button></p>
        </div>
      </template>
    </div>
  </section>
</template>
<style scoped>
/* main.css 全局强制直角，胶囊徽章例外 */
.rec-badge {
  border-radius: 999px !important;
}
</style>
