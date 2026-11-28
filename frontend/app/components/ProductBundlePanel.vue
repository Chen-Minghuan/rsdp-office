<script setup lang="ts">
import type { BundleDisplayItem, ProductBundle } from '~/types/productScheme'
import type { ImportedProduct } from '~/types/imported-products'
import { catalogProductPath, localProductId } from '~/utils/catalogProductReference'
import { createUuid } from '~/utils/uuid'
import { productBundleError } from '~/utils/productBundleError'

const api = useOfficeApi()
const props = defineProps<{ products: ImportedProduct[] }>()
const available = defineModel<boolean>('available', { default: false })
const emit = defineEmits<{ selected: [bundle: ProductBundle | null]; saved: [bundle: ProductBundle] }>()
const bundles = ref<ProductBundle[]>([])
const active = ref<ProductBundle | null>(null)
const product = ref<ImportedProduct | null>(null)
const variants = ref<{ variantId: string; displayName: string }[]>([])
const variantId = ref('')
const loading = ref(false)
const variantLoading = ref(false)
const saving = ref(false)
const showItems = ref(true)
const showProductPicker = ref(false)
const nameInput = ref<HTMLInputElement | HTMLInputElement[] | null>(null)
const renaming = ref(false)
const error = ref('')
const notice = ref('')
const displayBundles = computed(() => active.value && !bundles.value.some(bundle => bundle.bundleId === active.value?.bundleId)
  ? [active.value, ...bundles.value]
  : bundles.value)
let variantRequest = 0
let autoSaveTimer: ReturnType<typeof setTimeout> | undefined

function scheduleSave() {
  clearTimeout(autoSaveTimer)
  autoSaveTimer = setTimeout(() => {
    autoSaveTimer = undefined
    void save()
  }, 600)
}

function cancelScheduledSave() {
  clearTimeout(autoSaveTimer)
  autoSaveTimer = undefined
}

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
  const shouldSave = autoSaveTimer !== undefined && !!active.value?.name.trim()
  cancelScheduledSave()
  if (shouldSave) void save()
  active.value = bundle ? { ...bundle, items: bundle.items.map(item => ({ ...item })) } : null
  emit('selected', active.value)
  product.value = null
  showItems.value = true
  showProductPicker.value = false
  renaming.value = false
  variantRequest++
  variantLoading.value = false
  error.value = ''
  notice.value = ''
}

function create() {
  if (!available.value) return
  select({ bundleId: createUuid(), name: '', revision: 0, items: [] })
}

function toggleBundle(bundle: ProductBundle) {
  if (active.value?.bundleId === bundle.bundleId) select(null)
  else select(bundle)
}

function focusRename() {
  renaming.value = true
  nextTick(() => {
    const input = Array.isArray(nameInput.value) ? nameInput.value[0] : nameInput.value
    input?.focus()
  })
}

function addFromPicker(item: ImportedProduct) {
  showProductPicker.value = false
  void addProduct(item)
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
    if (variants.value.length === 1) {
      variantId.value = variants.value[0]!.variantId
      addVariant()
    }
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
  notice.value = localProductId(item.rspuId) ? '本地商品规格已加入。' : '规格已加入。'
  void save()
}

function drop(event: DragEvent) {
  const id = event.dataTransfer?.getData('application/x-rsdp-product')
  const item = props.products.find(row => row.rspuId === id)
  if (item) void addProduct(item)
}

function removeItem(item: BundleDisplayItem) {
  if (active.value) {
    active.value.items = active.value.items.filter(row => row.rspuId !== item.rspuId || row.variantId !== item.variantId)
    void save()
  }
}

async function save() {
  cancelScheduledSave()
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
    if (active.value?.bundleId === result.bundleId) select(result)
    emit('saved', result)
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
onBeforeUnmount(cancelScheduledSave)
</script>

<template>
  <section class="bundle-panel" aria-label="自选商品组合" @dragover.prevent @drop.prevent="drop">
    <header class="bundle-heading">
      <div>
        <h2>自选组合</h2>
        <p>拖拽商品到组合卡，或点击商品空位添加</p>
      </div>
      <button class="bundle-primary" type="button" :disabled="!available || loading || saving" @click="create">＋ 新建组合</button>
    </header>
    <p v-if="loading" role="status">正在读取组合…</p>
    <div v-else-if="displayBundles.length" class="bundle-list">
      <article v-for="bundle in displayBundles" :key="bundle.bundleId" class="bundle-card" :class="{ 'is-active': active?.bundleId === bundle.bundleId }" data-testid="bundle-card">
        <div class="bundle-card-heading">
          <input v-if="renaming && active?.bundleId === bundle.bundleId" ref="nameInput" v-model="active.name" class="bundle-rename-input" aria-label="组合名称" maxlength="100" @click.stop @input="scheduleSave" @blur="renaming = false" @keyup.enter="renaming = false">
          <button v-else type="button" class="bundle-select" :aria-pressed="active?.bundleId === bundle.bundleId" :disabled="saving" @click="toggleBundle(bundle)">
            <strong>{{ bundle.name || '未命名组合' }}</strong>
            <small>{{ bundle.items.reduce((sum, item) => sum + item.quantity, 0) }} 件</small>
          </button>
          <div v-if="active?.bundleId === bundle.bundleId" class="bundle-card-actions">
            <button type="button" aria-label="重命名组合" @click="focusRename">重命名</button>
            <button v-if="active.revision" type="button" class="bundle-danger-link" @click="remove">删除</button>
          </div>
          <button type="button" class="bundle-toggle" :aria-label="active?.bundleId === bundle.bundleId ? '收起组合' : '展开组合'" :aria-expanded="active?.bundleId === bundle.bundleId" @click="toggleBundle(bundle)">
            <span :class="{ 'is-expanded': active?.bundleId === bundle.bundleId }">›</span>
          </button>
        </div>
        <form v-if="active?.bundleId === bundle.bundleId" class="bundle-editor" @submit.prevent="save">
          <fieldset :disabled="saving">
            <div class="bundle-visuals">
              <div v-for="item in active.items" :key="`visual-${JSON.stringify([item.rspuId, item.variantId])}`" class="bundle-thumb bundle-item-image">
                <ProductImagePreview :src="item.imageUrl" :alt="item.name || '商品图片'" size="small" />
              </div>
              <button type="button" class="bundle-add-slot" data-testid="bundle-add-slot" @click="showProductPicker = !showProductPicker">
                <span>＋</span><small>添加商品</small>
              </button>
            </div>
            <button v-if="active.items.length" type="button" class="bundle-collapse" @click="showItems = !showItems">{{ showItems ? '收起商品' : '展开商品' }}</button>
            <div v-if="showProductPicker" class="bundle-product-options">
              <p v-if="!props.products.length">请先在商品库选择商品加入组合，再返回这里编辑。</p>
              <button v-for="item in props.products.slice(0, 8)" :key="item.rspuId" type="button" @click="addFromPicker(item)">{{ item.productName || item.rspuId }}</button>
            </div>
            <ul v-if="active.items.length && showItems" class="bundle-items">
              <li v-for="item in active.items" :key="JSON.stringify([item.rspuId, item.variantId])">
                <ProductImagePreview :src="item.imageUrl" :alt="item.name || '商品图片'" size="small" class="bundle-row-image" />
                <div>
                  <NuxtLink v-if="item.name" :to="catalogProductPath(item.rspuId)">{{ item.name }}</NuxtLink>
                  <strong v-else>商品不可用</strong>
                  <small>{{ item.variant }}</small>
                  <small v-if="item.name && localProductId(item.rspuId)" class="text-muted">本地商品 · 待报价</small>
                </div>
                <input v-model.number="item.quantity" type="number" min="1" max="200" required :aria-label="`${item.name || '不可用商品'}数量`" @input="scheduleSave">
                <button type="button" :aria-label="`移除${item.name || '不可用商品'}`" @click="removeItem(item)">×</button>
              </li>
            </ul>
            <div v-if="product" class="bundle-picker">
              <p v-if="variantLoading" role="status">正在读取商品规格…</p>
              <label v-else-if="variants.length" class="bundle-label">{{ product.productName || '商品规格' }}
                <select v-model="variantId" aria-label="选择商品规格" @change="addVariant">
                  <option value="">选择商品规格</option>
                  <option v-for="variant in variants" :key="variant.variantId" :value="variant.variantId">{{ variant.displayName || variant.variantId }}</option>
                </select>
              </label>
            </div>
            <p v-else-if="!active.items.length">拖入商品，或点击商品卡片的「加入组合」。</p>
            <p class="bundle-save-hint">组合修改会自动保存，保存后可应用到当前房间</p>
          </fieldset>
        </form>
      </article>
    </div>
    <p v-else-if="available && !active">将常用商品保存为组合，可在房间方案中使用。</p>

    <p v-if="error" role="alert" class="bundle-error">{{ error }} <button v-if="!active" type="button" @click="load">重试</button></p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <button v-if="available && !loading" type="button" class="bundle-create" @click="create">＋ 创建新的组合</button>
  </section>
</template>

<style scoped>
.bundle-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  min-width: 0;
  gap: .65rem;
  padding: .75rem;
  background: white;
  border: 1px solid var(--color-line);
  color: var(--color-accent-ink);
  font-size: .75rem;
  overflow-wrap: anywhere;
}
.bundle-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: .55rem;
}
.bundle-heading h2 {
  font-size: 1rem;
  font-weight: 700;
}
.bundle-heading p {
  margin-top: .3rem;
  font-size: .68rem;
  line-height: 1.5;
}
.bundle-panel button,
.bundle-panel input,
.bundle-panel select {
  min-width: 0;
  max-width: 100%;
  border: 1px solid var(--color-accent-line);
  border-radius: 7px !important;
  background: white;
  color: inherit;
  padding: .36rem .5rem;
}
.bundle-panel button:disabled,
.bundle-panel fieldset:disabled {
  opacity: .5;
}
.bundle-panel .bundle-primary {
  border-color: var(--color-accent);
  background: var(--color-accent);
  color: white;
}
.bundle-list,
.bundle-editor fieldset,
.bundle-label,
.bundle-items,
.bundle-picker {
  display: grid;
  min-width: 0;
  gap: .45rem;
}
.bundle-card {
  min-width: 0;
  border: 1px solid var(--color-accent-line);
  border-radius: 14px !important;
  background: white;
  padding: .55rem;
}
.bundle-card.is-active {
  border-color: var(--color-accent);
  box-shadow: 0 0 0 1px var(--color-accent);
}
.bundle-card-heading {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: .3rem;
}
.bundle-select {
  display: flex;
  min-width: 0;
  flex: 1 1 0;
  align-items: center;
  justify-content: space-between;
  gap: .4rem;
  border: 0 !important;
  text-align: left;
}
.bundle-select strong {
  min-width: 0;
  overflow: hidden;
  font-size: .85rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bundle-select small {
  flex: none;
  color: var(--color-accent-ink);
  font-size: .68rem;
}
.bundle-toggle {
  display: grid;
  width: 28px;
  height: 28px;
  flex: none;
  place-items: center;
  border: 0 !important;
  color: var(--color-accent);
  font-size: 1.25rem;
  line-height: 1;
}
.bundle-toggle span {
  display: block;
  transition: transform .16s ease;
}
.bundle-toggle span.is-expanded {
  transform: rotate(90deg);
}
.bundle-danger-link {
  color: #a13f50 !important;
}
.bundle-editor {
  margin-top: .55rem;
  border-top: 1px solid var(--color-accent-line);
  padding-top: .55rem;
}
.bundle-label {
  font-size: .72rem;
  font-weight: 600;
}
.bundle-card-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: .3rem;
}
.bundle-card-actions button {
  padding: .25rem .4rem;
  font-size: .65rem;
}
.bundle-rename-input {
  min-width: 0;
  flex: 1;
  padding: .35rem .45rem;
}
.bundle-visuals {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: .45rem;
}
.bundle-thumb,
.bundle-add-slot {
  display: grid;
  min-width: 0;
  min-height: 96px;
  place-items: center;
  overflow: hidden;
  border: 1px solid var(--color-line);
  border-radius: 9px !important;
  background: #faf8fc;
}
.bundle-item-image {
  width: 100%;
  height: 100%;
}
.bundle-add-slot {
  align-content: center;
  color: var(--color-accent) !important;
}
.bundle-add-slot span {
  font-size: 1.7rem;
  line-height: 1;
}
.bundle-add-slot small {
  margin-top: .4rem;
  color: var(--color-accent-ink);
}
.bundle-collapse,
.bundle-create {
  width: 100%;
}
.bundle-product-options {
  display: grid;
  gap: .3rem;
  border: 1px solid var(--color-accent-line);
  border-radius: 8px !important;
  padding: .4rem;
  background: #faf8fc;
}
.bundle-product-options button {
  text-align: left;
}
.bundle-items li {
  display: grid;
  min-width: 0;
  grid-template-columns: 42px minmax(0, 1fr) 42px 24px;
  align-items: center;
  gap: .4rem;
  border: 1px solid var(--color-line);
  border-radius: 8px !important;
  padding: .45rem;
}
.bundle-row-image {
  width: 42px;
  height: 42px;
  overflow: hidden;
  border-radius: 5px !important;
  background: #faf8fc;
}
.bundle-items li > div {
  min-width: 0;
}
.bundle-items strong,
.bundle-items a,
.bundle-items small {
  display: block;
  overflow-wrap: anywhere;
}
.bundle-items a {
  color: var(--color-accent-ink);
  font-weight: 600;
}
.bundle-items small {
  overflow: hidden;
  color: var(--color-muted);
  font-size: .65rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bundle-items input {
  width: 42px;
  padding: .3rem .2rem;
  text-align: center;
}
.bundle-items li > button {
  border: 0 !important;
  color: var(--color-muted);
  font-size: 1rem;
}
.bundle-save-hint {
  border-radius: 7px !important;
  background: var(--color-accent-tint);
  padding: .45rem;
  color: var(--color-accent-ink) !important;
  font-size: .66rem;
  text-align: center;
}
.bundle-panel p {
  color: var(--color-muted);
}
.bundle-panel .bundle-error {
  color: #9b2c39;
}
</style>
