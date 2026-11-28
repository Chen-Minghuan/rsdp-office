<script setup lang="ts">
import { localReferencePrice } from '~/utils/localReferencePrice'

const props = defineProps<{
  variants: {
    id: string
    name?: string | null
    dimensions?: string | null
    color?: string | null
    material?: string | null
    referencePrice?: number | null
    packageCount?: number | null
    packageVolumeCbm?: number | null
    grossWeight?: number | null
    status?: string
  }[]
  hasProductMaterial?: boolean
}>()

const selectedId = ref('')
const selected = computed(() => props.variants.find(variant => variant.id === selectedId.value) ?? props.variants[0])
const singleName = computed(() => {
  const variant = selected.value
  if (props.variants.length !== 1 || !variant?.name) return ''
  const normalize = (value: string) => value.toLowerCase().replace(/\s+/g, '').replace(/mm$/, '').replaceAll('×', 'x')
  return normalize(variant.name) === normalize(variant.dimensions ?? '') ? '' : variant.name
})
const packaging = computed(() => {
  const variant = selected.value
  return [
    { label: '包数', value: variant?.packageCount },
    { label: '体积（CBM）', value: variant?.packageVolumeCbm },
    { label: '重量（原表值）', value: variant?.grossWeight },
  ].filter(field => field.value != null)
})
</script>

<template>
  <section class="min-w-0 border border-line bg-white p-4 sm:p-5" aria-labelledby="catalog-product-specifications">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <h2 id="catalog-product-specifications" class="text-base font-semibold">商品规格</h2>
      <span v-if="variants.length" class="text-xs text-muted">{{ variants.length }} 个规格</span>
    </div>
    <div v-if="selected" class="mt-4 space-y-4">
      <label v-if="variants.length > 1" class="grid gap-1.5 text-xs font-medium text-muted">
        选择规格
        <select :value="selected.id" aria-label="选择规格"
          class="w-full min-w-0 border border-line bg-white px-3 py-2.5 text-sm font-medium text-ink"
          @change="selectedId = ($event.target as HTMLSelectElement).value">
          <option v-for="(variant, index) in variants" :key="variant.id" :value="variant.id">
            {{ variant.name || `规格 ${index + 1}` }}
          </option>
        </select>
      </label>
      <dl class="grid grid-cols-2 gap-x-4 gap-y-4 sm:gap-x-6">
        <div v-if="singleName" class="col-span-2 min-w-0">
          <dt class="text-xs text-muted">规格名称</dt>
          <dd class="mt-1 break-words text-sm font-medium">{{ singleName }}</dd>
        </div>
        <div class="col-span-2 min-w-0 border-l-2 border-accent bg-accent-tint px-3.5 py-3 sm:px-4">
          <dt class="text-xs font-medium text-accent-ink">尺寸</dt>
          <dd class="mt-1 break-words text-xl font-semibold tracking-tight text-ink tabular-nums sm:text-[25px]" data-testid="catalog-product-dimensions">
            {{ selected.dimensions || '—' }}
          </dd>
        </div>
        <div class="min-w-0">
          <dt class="text-xs text-muted">颜色</dt>
          <dd class="mt-1 break-words text-sm font-medium">{{ selected.color || '—' }}</dd>
        </div>
        <div class="min-w-0">
          <dt class="text-xs text-muted">材质</dt>
          <dd class="mt-1 break-words text-sm font-medium">{{ selected.material || (hasProductMaterial ? '见商品资料' : '—') }}</dd>
        </div>
        <div v-if="selected.referencePrice !== undefined" class="min-w-0">
          <dt class="text-xs text-muted">参考价</dt>
          <dd class="mt-1 break-words text-sm font-semibold tabular-nums" :class="selected.referencePrice == null ? 'text-muted' : 'text-accent-ink'">
            {{ localReferencePrice(selected.referencePrice) }}
          </dd>
        </div>
        <div v-for="field in packaging" :key="field.label" class="min-w-0">
          <dt class="text-xs text-muted">{{ field.label }}</dt>
          <dd class="mt-1 break-words text-sm font-medium tabular-nums">{{ field.value }}</dd>
        </div>
        <div v-if="selected.status" class="min-w-0">
          <dt class="text-xs text-muted">规格状态</dt>
          <dd class="mt-1 break-words text-sm font-medium" :class="selected.status === 'active' ? 'text-green' : 'text-muted'">
            {{ selected.status === 'active' ? '有效' : selected.status }}
          </dd>
        </div>
      </dl>
    </div>
    <p v-else class="mt-4 border-t border-line pt-4 text-sm text-muted">暂无规格信息</p>
  </section>
</template>
