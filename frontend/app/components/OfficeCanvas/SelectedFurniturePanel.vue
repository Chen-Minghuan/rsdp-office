<script setup lang="ts">
import type { FurnitureObject, Product, ZoneObject } from '~/types/office'
import { furnitureById, furnitureCatalogTemplates } from '~/utils/manualFurniture'
import { furnitureHeightMm } from '~/utils/whiteModel'
import { sameOriginAssetUrl } from '~/utils/apiBase'
import { money } from '~/utils/helpers'

/**
 * 3D 场景选中家具的属性面板（RENDER-004）。
 * 只读取 props 传入的真实业务数据（家具实例、商品列表、productSelection 快照），
 * 不维护第二份商品数据；没有的数据明确显示「暂无数据」，不虚构编号、价格或材质。
 */
const props = withDefaults(defineProps<{
  item: FurnitureObject | null
  zone: ZoneObject | null
  readonly?: boolean
  products: Product[]
  productsError?: string
}>(), { readonly: false, productsError: '' })

const emit = defineEmits<{
  (event: 'rotate'): void
  (event: 'replace-module', templateId: string): void
  (event: 'change-product'): void
  (event: 'locate'): void
}>()

const store = useOfficeStore()

const template = computed(() => {
  const item = props.item
  if (!item) return null
  return store.customModules.find(module => module.id === item.furnitureTemplateId)
    || (item.furnitureTemplateId ? furnitureById.get(item.furnitureTemplateId) : null)
    || null
})

const categoryLabel = computed(() => {
  if (!props.item) return ''
  if (props.item.moduleSnapshot) return '自定义模块'
  return template.value && 'category' in template.value ? template.value.category || '未分类' : '未分类'
})

const moduleName = computed(() => {
  if (!props.item) return ''
  return props.item.moduleSnapshot?.name || template.value?.name || props.item.label || '布局模块'
})

/** 高度：实例显式高度为真实数据；否则为模板/类别的渲染估计值，明确标识不写回业务几何。 */
const heightInfo = computed(() => {
  const item = props.item
  if (!item) return { text: '暂无数据', estimated: false }
  if (item.heightMm && item.heightMm > 0) return { text: `${Math.round(item.heightMm)} mm`, estimated: false }
  return { text: `约 ${furnitureHeightMm(item.furnitureTemplateId)} mm（渲染估计）`, estimated: true }
})

/** 已绑定商品：优先在售列表中的实时数据，其次 productSelection 快照（真实服务端写入）。 */
const liveProduct = computed(() => props.products.find(product => product.id === props.item?.productId) || null)
const snapshotProduct = computed(() => props.item?.productSelection?.product || null)
const product = computed(() => liveProduct.value || snapshotProduct.value)
const productStale = computed(() => !!props.item?.productId && !liveProduct.value)

const specText = computed(() => {
  const p = product.value
  if (!p) return ''
  const parts: string[] = []
  if (p.widthMm && p.depthMm) parts.push(`${Math.round(p.widthMm)}×${Math.round(p.depthMm)}mm`)
  if (p.heightMm) parts.push(`高 ${Math.round(p.heightMm)}mm`)
  return parts.length ? parts.join(' · ') : '暂无数据'
})

const templateOptions = computed(() => [
  ...furnitureCatalogTemplates.map(item => ({ id: item.id, name: item.name, size: `${item.widthMm}×${item.depthMm}` })),
  ...store.customModules.map(item => ({ id: item.id, name: `${item.name}（自定义）`, size: `${item.widthMm}×${item.depthMm}` })),
])

function onReplaceModule(event: Event) {
  const id = (event.target as HTMLSelectElement).value
  if (id) emit('replace-module', id)
}
</script>

<template>
  <div v-if="item" class="rounded-lg border border-line p-2.5" data-testid="white-model-panel">
    <h3 class="mb-2 font-semibold text-ink">{{ item.label || moduleName }}</h3>

    <!-- 基础属性 -->
    <dl class="mb-2 space-y-1 text-[11px]">
      <div class="flex justify-between gap-2"><dt class="text-muted">家具类别</dt><dd>{{ categoryLabel }}</dd></div>
      <div class="flex justify-between gap-2"><dt class="text-muted">模块</dt><dd class="text-right">{{ moduleName }}</dd></div>
      <div class="flex justify-between gap-2"><dt class="text-muted">所属空间</dt><dd>{{ zone?.name || '未分配空间' }}</dd></div>
      <div class="flex justify-between gap-2"><dt class="text-muted">宽 × 深</dt><dd data-testid="white-model-size">{{ Math.round(item.w) }}×{{ Math.round(item.h) }}mm</dd></div>
      <div class="flex justify-between gap-2"><dt class="text-muted">高度</dt><dd :class="heightInfo.estimated ? 'text-amber-700' : ''">{{ heightInfo.text }}</dd></div>
      <div class="flex justify-between gap-2"><dt class="text-muted">旋转角度</dt><dd>{{ item.rotation || 0 }}°</dd></div>
      <div class="flex justify-between gap-2">
        <dt class="text-muted">排版状态</dt>
        <dd :class="item.layoutConfirmed ? 'text-[#438064]' : 'text-amber-700'">{{ item.layoutConfirmed ? '已确认' : '待确认' }}</dd>
      </div>
    </dl>
    <details class="mb-2 text-[10px] text-muted">
      <summary class="cursor-pointer">实例 ID</summary>
      <p class="mt-1 break-all">{{ item.id }}</p>
    </details>

    <!-- 商品信息 -->
    <div class="mb-2 border-t border-line pt-2">
      <template v-if="product">
        <div class="mb-1.5 flex gap-2">
          <img v-if="product.imageUrl" :src="sameOriginAssetUrl(product.imageUrl)" :alt="product.name" class="h-14 w-14 flex-none rounded-none border border-line bg-[#f7f4f0] object-contain">
          <span v-else class="grid h-14 w-14 flex-none place-items-center rounded-none border border-dashed border-line text-[10px] text-muted">暂无图片</span>
          <div class="min-w-0">
            <div class="text-xs font-bold text-ink" data-testid="white-model-product-name">{{ product.name }}</div>
            <div class="mt-0.5 text-[10px] text-muted">{{ product.variant || '规格暂无数据' }}</div>
            <div class="mt-0.5 text-xs text-bronze">{{ product.price > 0 ? money(product.price) : '待报价' }}</div>
          </div>
        </div>
        <dl class="space-y-1 text-[11px]">
          <div class="flex justify-between gap-2"><dt class="text-muted">商品 ID</dt><dd class="break-all text-right">{{ product.id }}</dd></div>
          <div class="flex justify-between gap-2"><dt class="text-muted">RSPU</dt><dd>{{ product.rspuId || '暂无数据' }}</dd></div>
          <div class="flex justify-between gap-2"><dt class="text-muted">Variant</dt><dd>{{ product.variantId || product.variant || '暂无数据' }}</dd></div>
          <div class="flex justify-between gap-2"><dt class="text-muted">规格</dt><dd class="text-right">{{ specText }}</dd></div>
          <div class="flex justify-between gap-2"><dt class="text-muted">材质</dt><dd>暂无数据</dd></div>
          <div class="flex justify-between gap-2"><dt class="text-muted">颜色</dt><dd>暂无数据</dd></div>
          <div class="flex justify-between gap-2">
            <dt class="text-muted">商品状态</dt>
            <dd :class="productStale ? 'text-amber-700' : 'text-[#438064]'">{{ productStale ? '不在当前在售列表' : '在售列表内' }}</dd>
          </div>
        </dl>
        <p v-if="productStale" class="mt-1.5 rounded-none bg-amber-50 px-2 py-1.5 text-[10px] leading-relaxed text-amber-700" data-testid="white-model-product-stale">
          该商品可能已下架或待审核；旧方案保留引用，新的商品推荐不会再包含它。替换商品请先确认排版后重新选择。
        </p>
      </template>
      <template v-else>
        <p class="rounded-none bg-[#f7f3ee] px-2 py-2 text-[11px] leading-relaxed text-muted" data-testid="white-model-unbound">
          当前家具为布局模块<br>尚未绑定真实供应链商品
        </p>
      </template>
      <p v-if="props.productsError" class="mt-1 text-[10px] text-amber-700">{{ props.productsError }}</p>
    </div>

    <!-- 操作 -->
    <div class="space-y-1.5 border-t border-line pt-2">
      <template v-if="!readonly">
        <button class="w-full rounded-lg border border-line px-2 py-1.5 hover:bg-cream" data-testid="white-model-rotate" @click="emit('rotate')">旋转 90°</button>
        <button class="w-full rounded-lg border border-line px-2 py-1.5 hover:bg-cream" data-testid="white-model-product-change" @click="emit('change-product')">
          {{ product ? '替换商品' : '选择商品' }}
        </button>
        <label class="block text-muted">替换家具模块</label>
        <select class="w-full rounded-lg border border-line bg-white px-2 py-1.5" :value="item.furnitureTemplateId || ''" data-testid="white-model-template" @change="onReplaceModule">
          <option v-if="!templateOptions.some(option => option.id === item.furnitureTemplateId)" :value="item.furnitureTemplateId || ''">{{ moduleName }}（当前）</option>
          <option v-for="option in templateOptions" :key="option.id" :value="option.id">{{ option.name }}（{{ option.size }}）</option>
        </select>
      </template>
      <p v-else class="text-[10px] text-muted">方案只读查看；修改请前往设计模式。</p>
      <button class="w-full rounded-lg border border-line px-2 py-1.5 text-muted hover:bg-cream" data-testid="white-model-locate" @click="emit('locate')">
        {{ readonly ? '前往设计模式编辑' : '定位到 2D' }}
      </button>
    </div>
  </div>
  <div v-else class="rounded-lg border border-dashed border-line p-3 text-muted" data-testid="white-model-panel-empty">
    点击场景中的家具查看属性
  </div>
</template>
