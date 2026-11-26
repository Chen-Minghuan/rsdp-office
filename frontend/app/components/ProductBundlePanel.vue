<script setup lang="ts">
import type { BundleDisplayItem, ProductBundle } from '~/types/productScheme'
import type { ImportedProduct } from '~/types/imported-products'
import { catalogProductPath, localProductId } from '~/utils/catalogProductReference'
import { createUuid } from '~/utils/uuid'
import { productBundleError } from '~/utils/productBundleError'

const api = useOfficeApi()
const props = defineProps<{ products: ImportedProduct[] }>()
const available = defineModel<boolean>('available', { default: false })
const bundles = ref<ProductBundle[]>([])
const active = ref<ProductBundle | null>(null)
const product = ref<ImportedProduct | null>(null)
const variants = ref<{ variantId: string; displayName: string }[]>([])
const variantId = ref('')
const loading = ref(false)
const variantLoading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
let variantRequest = 0

async function load() {
  if (loading.value || saving.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await api.loadProductBundles()
    if (!Array.isArray(result)) throw new Error('商品组合服务暂不可用')
    bundles.value = result
    available.value = true
  } catch (failure) {
    available.value = false
    error.value = productBundleError(failure, '暂时无法读取商品组合，请重试。', '当前服务暂未提供商品组合，更新服务后可重试。')
  } finally {
    loading.value = false
  }
}

function select(bundle: ProductBundle | null) {
  active.value = bundle ? { ...bundle, items: bundle.items.map(item => ({ ...item })) } : null
  product.value = null
  variantRequest++
  variantLoading.value = false
  error.value = ''
  notice.value = ''
}

function create() {
  if (!available.value) return
  select({ bundleId: createUuid(), name: '', revision: 0, items: [] })
}

async function addProduct(item: ImportedProduct) {
  if (item.status !== 'active' || !available.value || loading.value || saving.value) return
  if (!active.value) create()
  product.value = item
  variants.value = []
  variantId.value = ''
  error.value = ''
  notice.value = ''
  variantLoading.value = true
  const request = ++variantRequest
  try {
    const productId = localProductId(item.rspuId)
    const options = productId
      ? (await api.getLocalProduct(productId)).variants.flatMap(variant => variant.id
          ? [{ variantId: variant.id, displayName: variant.name }]
          : [])
      : (await api.getProduct(item.rspuId)).variants.filter(variant => variant.status === 'active')
    if (request !== variantRequest) return
    variants.value = options
    if (variants.value.length === 1) variantId.value = variants.value[0]!.variantId
    if (!variants.value.length) error.value = '此商品暂无可加入的规格'
  } catch (failure) {
    if (request === variantRequest) error.value = productBundleError(failure, '暂时无法读取商品规格，请重试。')
  } finally {
    if (request === variantRequest) variantLoading.value = false
  }
}

function addVariant() {
  const bundle = active.value
  const item = product.value
  const variant = variants.value.find(option => option.variantId === variantId.value)
  if (!bundle || !item || !variant) return
  if (bundle.items.reduce((sum, row) => sum + row.quantity, 0) >= 200) {
    error.value = '每个组合最多 200 件商品'
    return
  }
  const existing = bundle.items.find(row => row.rspuId === item.rspuId && row.variantId === variant.variantId)
  if (existing) existing.quantity++
  else bundle.items.push({
    rspuId: item.rspuId, variantId: variant.variantId, quantity: 1,
    name: item.productName || item.rspuId, variant: variant.displayName, imageUrl: item.primaryImageUrl,
  })
  product.value = null
  error.value = ''
  notice.value = localProductId(item.rspuId) ? '本地商品规格已加入。' : '规格已加入，保存后可在产品方案页应用。'
}

function drop(event: DragEvent) {
  const id = event.dataTransfer?.getData('application/x-rsdp-product')
  const item = props.products.find(row => row.rspuId === id)
  if (item) void addProduct(item)
}

function removeItem(item: BundleDisplayItem) {
  if (active.value) active.value.items = active.value.items.filter(row => row.rspuId !== item.rspuId || row.variantId !== item.variantId)
}

async function save() {
  const bundle = active.value
  if (!available.value || !bundle || !bundle.name.trim() || saving.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await api.saveProductBundle(bundle.bundleId, {
      name: bundle.name, revision: bundle.revision,
      items: bundle.items.map(({ rspuId, variantId, quantity }) => ({ rspuId, variantId, quantity })),
    })
    bundles.value = [result, ...bundles.value.filter(row => row.bundleId !== result.bundleId)]
    select(result)
    notice.value = '组合已保存'
  } catch (failure) {
    error.value = productBundleError(failure, '暂时无法保存商品组合，请重试。')
  } finally {
    saving.value = false
  }
}

async function remove() {
  const bundle = active.value
  if (!bundle || !bundle.revision || saving.value || !window.confirm(`删除商品组合「${bundle.name}」？`)) return
  saving.value = true
  error.value = ''
  try {
    await api.deleteProductBundle(bundle.bundleId, bundle.revision)
    bundles.value = bundles.value.filter(row => row.bundleId !== bundle.bundleId)
    select(null)
    notice.value = '组合已删除'
  } catch (failure) {
    error.value = productBundleError(failure, '暂时无法删除商品组合，请重试。')
  } finally {
    saving.value = false
  }
}

defineExpose({ addProduct })
onMounted(load)
</script>

<template>
  <section class="bundle-panel" aria-label="自选商品组合" @dragover.prevent @drop.prevent="drop">
    <header class="bundle-heading">
      <h2 class="text-sm font-bold">自选组合</h2>
      <button class="bundle-primary" type="button" :disabled="!available || loading || saving" @click="create">＋ 新建组合</button>
    </header>
    <p v-if="loading" role="status">正在读取组合…</p>
    <div v-else-if="bundles.length" class="bundle-list">
      <button v-for="bundle in bundles" :key="bundle.bundleId" type="button" :aria-pressed="active?.bundleId === bundle.bundleId"
        :disabled="saving" @click="select(bundle)">
        <span>{{ bundle.name }}</span><small>{{ bundle.items.reduce((sum, item) => sum + item.quantity, 0) }} 件</small>
      </button>
    </div>
    <p v-else-if="available && !active">将常用商品保存为组合，可在房间方案中使用。</p>

    <form v-if="active" class="bundle-editor" @submit.prevent="save">
      <fieldset :disabled="saving">
        <label class="bundle-label">组合名称<input v-model="active.name" aria-label="组合名称" maxlength="100" required></label>
        <ul v-if="active.items.length" class="bundle-items">
          <li v-for="item in active.items" :key="JSON.stringify([item.rspuId, item.variantId])">
            <div>
              <NuxtLink v-if="item.name" :to="catalogProductPath(item.rspuId)" class="font-semibold text-accent-ink hover:underline">{{ item.name }}</NuxtLink>
              <strong v-else>商品不可用</strong>
              <small>{{ item.variant }}</small>
              <small v-if="item.name && localProductId(item.rspuId)" class="text-muted">本地商品 · 待报价</small>
            </div>
            <input v-model.number="item.quantity" type="number" min="1" max="200" required :aria-label="`${item.name || '不可用商品'}数量`">
            <button type="button" :aria-label="`移除${item.name || '不可用商品'}`" @click="removeItem(item)">×</button>
          </li>
        </ul>
        <div v-if="product" class="bundle-picker">
          <p v-if="variantLoading" role="status">正在读取商品规格…</p>
          <label v-else-if="variants.length" class="bundle-label">{{ product.productName || '商品规格' }}
            <select v-model="variantId" aria-label="选择商品规格">
              <option value="">选择商品规格</option>
              <option v-for="variant in variants" :key="variant.variantId" :value="variant.variantId">{{ variant.displayName || variant.variantId }}</option>
            </select>
          </label>
          <button v-if="variants.length" type="button" :disabled="!variantId" @click="addVariant">加入规格</button>
          <button type="button" @click="product = null; variantRequest++; variantLoading = false">取消选规格</button>
        </div>
        <p v-else-if="!active.items.length">拖入商品，或点击商品卡片的「加入组合」。</p>
        <div class="flex flex-wrap gap-2">
          <button class="bundle-primary" type="submit" :disabled="!active.name.trim() || variantLoading">保存组合</button>
          <button v-if="active.revision" type="button" @click="remove">删除组合</button>
          <button type="button" @click="select(null)">关闭编辑</button>
        </div>
      </fieldset>
    </form>
    <p v-if="error" role="alert" class="bundle-error">{{ error }} <button v-if="!active" type="button" @click="load">重试</button></p>
    <p v-if="notice" role="status">{{ notice }}</p>
  </section>
</template>

<style scoped>
.bundle-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  min-width: 0;
  gap: .55rem;
  padding: .7rem;
  background: white;
  border: 1px solid var(--color-line);
  color: #30243a;
  font-size: .75rem;
  overflow-wrap: anywhere;
}

.bundle-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: .5rem;
}

.bundle-panel button,
.bundle-panel input,
.bundle-panel select {
  border: 1px solid var(--color-accent-line);
  background: #fff;
  color: inherit;
  padding: .32rem .48rem;
  min-width: 0;
  max-width: 100%;
}

.bundle-panel button:disabled,
.bundle-panel fieldset:disabled {
  opacity: .5;
}

.bundle-panel .bundle-primary,
.bundle-list button[aria-pressed=true] {
  background: var(--color-accent);
  color: #fff;
  border-color: var(--color-accent);
}

.bundle-list,
.bundle-editor fieldset,
.bundle-label,
.bundle-items,
.bundle-picker {
  display: grid;
  gap: .4rem;
  min-width: 0;
}

.bundle-list button,
.bundle-items li {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: .3rem;
  text-align: left;
}

.bundle-list span,
.bundle-items strong,
.bundle-items a,
.bundle-items small {
  display: block;
  overflow-wrap: anywhere;
}

.bundle-list small,
.bundle-items small {
  flex-shrink: 0;
}

.bundle-items input {
  width: 3rem;
  margin-left: auto;
}

.bundle-items li > div {
  min-width: 0;
}

.bundle-panel p {
  color: var(--color-muted);
}

.bundle-panel .bundle-error {
  color: #9b2c39;
}
</style>
