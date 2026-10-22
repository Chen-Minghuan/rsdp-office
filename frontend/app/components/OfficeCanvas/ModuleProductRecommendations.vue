<script setup lang="ts">
import type { FurnitureObject } from '~/types/office'
import { sameOriginAssetUrl } from '~/utils/apiBase'
import type { ModuleRecommendation, ModuleProductCandidate } from '~/types/moduleRecommendation'
import { money } from '~/utils/helpers'

const props = defineProps<{ selectedId?: string | null }>()
const store = useOfficeStore()
const api = useOfficeApi()
const moduleId = ref('')
const allowRotation = ref(true)
const applyToSameModules = ref(true)
const tier = ref('ALL')
const result = ref<ModuleRecommendation | null>(null)
const loading = ref(false), applying = ref(false), error = ref(''), feedback = ref('')
const modules = computed(() => store.furnitureForZone(store.designZoneId || ''))
const groups = computed(() => {
  const list: Array<{ key: string; ids: string[]; item: FurnitureObject }> = []
  for (const item of modules.value) {
    const signature = JSON.stringify([item.furnitureTemplateId, item.moduleSnapshot, item.w, item.h, item.heightMm])
    const group = list.find(group => group.key === signature)
    if (group) group.ids.push(item.id)
    else list.push({ key: signature, ids: [item.id], item })
  }
  return list
})
const module = computed(() => modules.value.find(item => item.id === moduleId.value) || modules.value[0])
const group = computed(() => groups.value.find(group => group.ids.includes(module.value?.id || '')))
const allConfirmed = computed(() => modules.value.length > 0 && modules.value.every(item => item.layoutConfirmed))
const signature = computed(() => JSON.stringify([store.designZoneId, store.canvasRevision, module.value?.id, modules.value, allowRotation.value]))
const candidates = computed(() => (result.value?.candidates || []).filter(item => tier.value === 'ALL' || item.tier === tier.value))
const tiers = [{ key: 'ALL', name: '全部兼容' }, { key: 'CLOSE', name: '贴合 ≥85%' }, { key: 'RELAXED', name: '宽松 60–85%' }, { key: 'SMALL', name: '偏小 <60%' }]
let sequence = 0
let timer: ReturnType<typeof setTimeout> | undefined
const number = (value: number) => Number(value.toFixed(2)).toLocaleString()
const message = (failure: any) => failure?.data?.message || failure?.message || '读取失败，请重试'

watch(() => props.selectedId, id => {
  if (modules.value.some(item => item.id === id)) moduleId.value = id!
}, { immediate: true })

async function refresh() {
  const ticket = ++sequence, current = signature.value, item = module.value
  result.value = null; error.value = ''; feedback.value = ''
  if (!item || !allConfirmed.value) { loading.value = false; return }
  loading.value = true
  const revision = store.canvasRevision, zoneId = store.designZoneId!
  try {
    // Flush the exact reviewed draft; the server reads saved geometry and checks its revision.
    await api.saveCanvas({ objects: JSON.parse(JSON.stringify(store.objects)), files: store.files, mode: store.canvasMode,
      designZoneId: store.designZoneId, revision, blank: store.blank })
    const response = await api.recommendModule({ zoneId, moduleId: item.id, canvasRevision: revision, allowRotation: allowRotation.value })
    if (ticket !== sequence || current !== signature.value) return
    if (!response || response.moduleId !== item.id || response.zoneId !== zoneId || response.canvasRevision !== revision || !Array.isArray(response.candidates)) throw new Error('商品推荐接口尚未就绪，请启动最新后端')
    result.value = response
  } catch (failure) {
    if (ticket === sequence && current === signature.value) error.value = message(failure)
  } finally { if (ticket === sequence) loading.value = false }
}
watch(signature, () => {
  sequence++; clearTimeout(timer); result.value = null; error.value = ''; feedback.value = ''; loading.value = false
  timer = setTimeout(refresh, 250)
}, { immediate: true })
onBeforeUnmount(() => { sequence++; clearTimeout(timer) })

async function choose(candidate: ModuleProductCandidate) {
  if (!result.value || !module.value || applying.value) return
  const current = signature.value
  applying.value = true; error.value = ''; feedback.value = ''
  try {
    const response = await api.selectModuleProduct({ zoneId: result.value.zoneId, moduleId: result.value.moduleId,
      canvasRevision: result.value.canvasRevision, allowRotation: allowRotation.value, productId: candidate.product.id,
      applyToSameModules: applyToSameModules.value })
    if (current !== signature.value) { error.value = '布局已变更，请重新匹配，已保存的商品选择请在方案中核对'; return }
    if (!response?.canvas || !Array.isArray(response.cartItems)) throw new Error('保存结果无效，请重新读取')
    store.objects = response.canvas.objects
    store.canvasRevision = response.canvas.revision
    store.submitted = null
    const product = response.match.product
    store.products = [...store.products.filter(item => item.id !== product.id), product]
    for (const line of response.cartItems) {
      store.cart = [...store.cart.filter(item => item.id !== line.lineId), { id: line.lineId, projectId: line.projectId,
        zoneId: line.zoneId, zoneName: line.zoneName, productId: line.productId, qty: line.qty }]
    }
    store.saveLocal()
    await nextTick()
    clearTimeout(timer)
    await refresh()
    feedback.value = `已为 ${response.cartItems.length} 个模块匹配商品，模块占地保持不变`
  } catch (failure) { error.value = message(failure) }
  finally { applying.value = false }
}
</script>

<template>
  <section data-testid="module-product-recommendations" class="flex flex-col flex-1 min-h-0">
    <div class="p-3 border-b border-line space-y-2 flex-none">
      <div class="flex items-center justify-between"><h3 class="text-sm font-bold">按模块尺寸推荐</h3><button class="text-xs text-bronze" :disabled="loading || applying" @click="refresh">重新匹配</button></div>
      <label class="block text-[11px] text-muted">匹配模块
        <select aria-label="匹配单位模块" :value="group?.item.id" :disabled="applying" class="mt-1 w-full rounded-lg border border-line bg-white px-2 py-2 text-xs" @change="moduleId = ($event.target as HTMLSelectElement).value">
          <option v-for="entry in groups" :key="entry.key" :value="entry.item.id">{{ entry.item.label || '单位模块' }} · {{ number(entry.item.w) }} × {{ number(entry.item.h) }} mm · {{ entry.ids.length }} 件</option>
        </select>
      </label>
      <div v-if="module" class="rounded-lg bg-[#f7f3ee] p-2 text-xs leading-relaxed">商品宽 ≤ {{ number(module.w) }} mm，深 ≤ {{ number(module.h) }} mm<br><span class="text-[10px] text-muted">按实际毫米尺寸向下兼容，不按面积代替宽深比较</span></div>
      <div class="flex flex-wrap gap-x-3 gap-y-1 text-[11px]">
        <label><input v-model="allowRotation" type="checkbox" :disabled="applying"> 允许旋转 90°</label>
        <label v-if="(group?.ids.length || 0) > 1"><input v-model="applyToSameModules" type="checkbox" :disabled="applying"> 同步 {{ group?.ids.length }} 个同规格模块</label>
      </div>
      <div class="flex gap-1 flex-wrap"><button v-for="entry in tiers" :key="entry.key" :class="['px-2 py-1 rounded text-[10px]', tier === entry.key ? 'bg-bronze text-white' : 'bg-[#f7f4f0] text-muted']" @click="tier = entry.key">{{ entry.name }}<span v-if="result"> ({{ entry.key === 'ALL' ? result.candidates.length : result.candidates.filter(item => item.tier === entry.key).length }})</span></button></div>
      <p class="text-[10px] text-muted">贴合度取宽、深占比中较低值；档位仅供排序；仅查询上架且审核通过的规格。高度、结构与家具用途请结合商品详情核对。</p>
    </div>
    <div class="flex-1 overflow-auto p-3 space-y-2" :aria-busy="loading || applying">
      <p v-if="!allConfirmed" class="text-xs text-muted py-5">请先确认当前空间的全部排版</p>
      <p v-else-if="loading" role="status" class="text-xs text-muted py-5">正在读取真实商品规格…</p>
      <p v-if="error" role="alert" class="text-xs text-red-700 p-2 bg-red-50 rounded">{{ error }}</p>
      <p v-if="feedback" role="status" class="text-xs text-green-700 p-2 bg-green-50 rounded">{{ feedback }}</p>
      <template v-if="result && !loading">
        <p v-if="result.missingDimensions" class="text-[10px] text-muted">{{ result.missingDimensions }} 个在售规格缺少可靠宽深，待核对后参与匹配。</p>
        <article v-for="candidate in candidates" :key="candidate.product.id" :data-product-id="candidate.product.id" class="border border-line rounded-xl p-2.5 space-y-1.5">
          <div class="flex gap-2">
            <img v-if="candidate.product.imageUrl" :src="sameOriginAssetUrl(candidate.product.imageUrl)" alt="" class="w-14 h-14 object-contain rounded bg-[#f7f4f0]">
            <div class="min-w-0"><div class="text-xs font-bold">{{ candidate.product.name }}</div><div class="text-[10px] text-muted">{{ candidate.product.variant }}</div><div class="text-xs mt-1">{{ number(candidate.product.widthMm!) }} × {{ number(candidate.product.depthMm!) }} mm</div></div>
          </div>
          <div class="text-[10px] text-bronze">{{ Math.floor(candidate.fitRatio * 100) }}% 贴合<span v-if="candidate.rotationDeg"> · 需旋转 90°</span> · {{ tiers.find(entry => entry.key === candidate.tier)?.name }}</div>
          <div class="text-[10px] text-muted">剩余宽 {{ number(candidate.remainingWidthMm) }} / 深 {{ number(candidate.remainingDepthMm) }} mm<span v-if="candidate.product.heightMm"> · 商品高 {{ number(candidate.product.heightMm) }} mm</span></div>
          <div class="flex items-center justify-between gap-1"><strong class="text-sm text-bronze">{{ candidate.product.price > 0 ? money(candidate.product.price) : '待报价' }}</strong><NuxtLink :to="'/products/' + candidate.product.rspuId" class="text-[10px] text-muted hover:underline">详情</NuxtLink><button :disabled="applying || candidate.product.price <= 0" class="text-[10px] px-2 py-1.5 rounded bg-bronze text-white disabled:opacity-50" @click="choose(candidate)">{{ candidate.product.price <= 0 ? '待补报价' : applying ? '保存中…' : applyToSameModules && (group?.ids.length || 0) > 1 ? `应用到 ${group?.ids.length} 个模块` : '加入该模块方案' }}</button></div>
        </article>
        <div v-if="!candidates.length" class="text-xs text-muted py-5 space-y-2">
          <template v-if="result.eligibleVariantCount === 0"><p>暂无已上架、审核通过且有有效规格的商品，请先核对商品审核和规格。</p><NuxtLink to="/products/audit" class="text-bronze underline">前往商品审核</NuxtLink></template>
          <p v-else>{{ result.candidates.length ? '该尺寸档暂无商品，可切换其他档位。' : '暂无宽深均可容纳的在售商品。可补齐商品尺寸或调整模块后重新匹配。' }}</p>
        </div>
      </template>
    </div>
  </section>
</template>