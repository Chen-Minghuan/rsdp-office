<script setup lang="ts">
import type { LocalProductInput, LocalProductVariant, SupplierWorkbookPreview, WorkbookImportProgress, WorkbookVariantEvidence } from '~/types/local-products'
import { workbookImageBytes, workbookPreviewPageSize, workbookSize } from '~/utils/localProductImport'

const props = defineProps<{
  preview: SupplierWorkbookPreview
  categories: { dictCode: string, dictName: string }[]
  busy: boolean
  previewId?: string | null
  imageSizes: Record<string, number>
  fileBytes: number
  progress?: WorkbookImportProgress | null
  capacityBlocked: boolean
  priceConfirmationRequired?: boolean
  saveBatch: (products: LocalProductInput[], onSaved: (products: LocalProductInput[]) => void) => Promise<boolean>
}>()
const emit = defineEmits<{ busyChange: [busy: boolean], selectionChange: [count: number] }>()
const api = useOfficeApi()
type CategorySource = 'workbook' | 'default' | 'ai' | 'manual'
type MaterialOrigin = 'COLUMN' | 'DESCRIPTION' | 'QWEN' | null
type DraftVariant = LocalProductVariant & {
  evidence?: WorkbookVariantEvidence
  materialOrigin: MaterialOrigin
  suggestedMaterial?: string
}

const drafts = ref(props.preview.products.map(({ product, images, sources, sourceCategory, variantEvidence }) => ({
  product: { ...product, variants: product.variants.map((variant, index): DraftVariant => ({
    ...variant, evidence: variantEvidence?.[index], materialOrigin: variantEvidence?.[index]?.materialSource ?? null,
  })) },
  images: images.map(image => ({ ...image })),
  sources,
  sourceCategory,
  categorySource: null as CategorySource | null,
  selected: false,
})))
type Draft = typeof drafts.value[number]
type ReviewFilter = 'all' | 'needs-review' | 'ready' | 'selected'
const activeModel = ref(drafts.value[0]?.product.factoryModel)
const query = ref('')
const filter = ref<ReviewFilter>('all')
const page = ref(1)
const showCategorySetup = ref(true)
const categoryQuery = ref('')
const listPanel = ref<HTMLElement | null>(null)
const detailPanel = ref<HTMLElement | null>(null)
const startedCount = drafts.value.length
const categoryCode = ref('')
const categoryMode = ref<'single' | 'mixed' | null>(null)
const candidateCodes = ref<string[]>([])
const suggestionNotice = ref('')
const suggestionError = ref('')
const materialNotice = ref('')
const materialError = ref('')
const recognizingMaterial = ref(false)
const classifying = ref(false)
const saving = ref(false)
const categoryLabels = computed(() => new Map(props.categories.map(category => [category.dictCode, category.dictName])))
const unfilledMaterials = computed(() => drafts.value.flatMap(draft => draft.product.variants)
  .filter(variant => !variant.material?.trim() && variant.evidence?.description))
const visibleCategories = computed(() => {
  const search = categoryQuery.value.trim().toLowerCase()
  return props.categories.filter(category => !search || `${category.dictName} ${category.dictCode}`.toLowerCase().includes(search))
})
const unclassified = computed(() => drafts.value.filter(draft => !draft.product.categoryCode.trim()
  && draft.product.name.trim() && draft.product.name.length <= 200))
const control = 'block w-full min-w-0 border border-line bg-white px-3 py-2 text-sm text-ink'
const button = 'border border-line bg-white px-3 py-2 text-sm font-semibold text-ink hover:border-accent-line hover:bg-accent-tint disabled:opacity-50'
const primary = 'border border-accent-ink bg-accent-ink px-4 py-2 text-sm font-semibold text-white hover:bg-accent disabled:opacity-50'

const imageBytes = Object.fromEntries(Object.entries(props.preview.images)
  .map(([key, image]) => [key, workbookImageBytes(image, props.imageSizes)]))

const selected = computed(() => drafts.value.filter(draft => draft.selected))
const totalBytes = computed(() => selected.value.reduce((bytes, draft) => bytes + draftBytes(draft), 0))
const issues = computed(() => new Map(drafts.value.map(draft => [draft.product.factoryModel, issue(draft)])))
const readyCount = computed(() => drafts.value.filter(draft => !draftIssue(draft)).length)
const needsReview = computed(() => drafts.value.length - readyCount.value)
const importedCount = computed(() => startedCount - drafts.value.length)
const activeDraft = computed(() => drafts.value.find(draft => draft.product.factoryModel === activeModel.value))
const visibleDrafts = computed(() => {
  const search = query.value.trim().toLowerCase()
  return drafts.value.filter(draft => {
    if (filter.value === 'needs-review' && !draftIssue(draft)) return false
    if (filter.value === 'ready' && draftIssue(draft)) return false
    if (filter.value === 'selected' && !draft.selected) return false
    return !search || `${draft.product.name} ${draft.product.factoryModel} ${categoryLabel(draft.product.categoryCode)}`.toLowerCase().includes(search)
  })
})
const pageDrafts = computed(() => visibleDrafts.value.slice((page.value - 1) * workbookPreviewPageSize, page.value * workbookPreviewPageSize))
const readyOnPage = computed(() => pageDrafts.value.filter(draft => !draftIssue(draft)))
const allReadyOnPageSelected = computed(() => readyOnPage.value.length > 0 && readyOnPage.value.every(draft => draft.selected))
const currentIndex = computed(() => visibleDrafts.value.findIndex(draft => draft === activeDraft.value))
const filters: { key: ReviewFilter, label: string }[] = [
  { key: 'all', label: '全部' }, { key: 'needs-review', label: '待核对' },
  { key: 'ready', label: '可导入' }, { key: 'selected', label: '已选' },
]
const filterCounts = computed(() => ({ all: drafts.value.length, 'needs-review': needsReview.value, ready: readyCount.value, selected: selected.value.length }))
const hasPickable = computed(() => visibleDrafts.value.some(draft => !draft.selected && !draftIssue(draft)))
const selectedIssues = computed(() => selected.value.filter(draft => draftIssue(draft)).length)
const canSubmit = computed(() => categoryMode.value !== null && selected.value.length > 0
  && !selectedIssues.value && !props.capacityBlocked && !props.priceConfirmationRequired)
watch(() => selected.value.length, count => emit('selectionChange', count), { immediate: true })

function categoryLabel(code: string) {
  const name = categoryLabels.value.get(code.trim().toUpperCase())
  return name ? `${name} · ${code}` : code
}

function draftIssue(draft: Draft) {
  return issues.value.get(draft.product.factoryModel)
}

watch([filter, query], () => {
  page.value = 1
  if (pageDrafts.value.length && !pageDrafts.value.includes(activeDraft.value!)) activeModel.value = pageDrafts.value[0]!.product.factoryModel
})
watch(page, () => {
  if (pageDrafts.value.length && !pageDrafts.value.includes(activeDraft.value!)) activeModel.value = pageDrafts.value[0]!.product.factoryModel
})

function moveDraft(direction: number) {
  const next = currentIndex.value + direction
  const draft = visibleDrafts.value[next]
  if (!draft) return
  activeModel.value = draft.product.factoryModel
  page.value = Math.floor(next / workbookPreviewPageSize) + 1
}

async function showDraft(draft: Draft) {
  activeModel.value = draft.product.factoryModel
  if (window.matchMedia('(max-width: 1023px)').matches) {
    await nextTick()
    detailPanel.value?.scrollIntoView({ block: 'start' })
  }
}

function issue({ product }: Draft) {
  const category = product.categoryCode.trim().toUpperCase()
  if (!category || (categoryLabels.value.size > 0 && !categoryLabels.value.has(category))) return '待核对品类'
  if (!product.name.trim() || product.name.length > 200) return '请核对商品名称'
  if (!product.variants.length || product.variants.length > 30) return '每件商品须有 1–30 个规格'
  if (product.variants.some(variant => !millimetres(variant.widthMm) || !millimetres(variant.depthMm)
    || (String(variant.heightMm ?? '') !== '' && !millimetres(variant.heightMm)))) {
    return '待核对尺寸'
  }
  if (product.variants.some(variant => !variant.name.trim() || (variant.sizeText?.length ?? 0) > 64
    || (variant.color?.length ?? 0) > 64 || (variant.material?.length ?? 0) > 128)) {
    return '待核对规格资料'
  }
  if (product.variants.some(({ referencePrice }) => referencePrice != null
    && (referencePrice <= 0 || !/^\d{1,10}(?:\.\d{1,2})?$/.test(String(referencePrice))))) return '待核对参考价'
  return ''
}

function updateReferencePrice(variant: DraftVariant, event: Event) {
  const input = event.target as HTMLInputElement
  variant.referencePrice = input.value ? input.valueAsNumber : null
}

function millimetres(value: unknown) {
  return typeof value === 'number' && value > 0 && value <= 100_000 && /^\d+(?:\.\d{1,2})?$/.test(String(value))
}

const materialLabels = { COLUMN: 'Excel 材质列', DESCRIPTION: '从 Excel 说明提取', QWEN: 'Qwen 建议' }

function setMaterial(variant: DraftVariant, value: string) {
  variant.material = value
  variant.materialOrigin = 'QWEN'
  variant.suggestedMaterial = undefined
}

async function suggestMaterials(variants: DraftVariant[]) {
  if (props.busy || recognizingMaterial.value) return
  const byDescription = new Map<string, DraftVariant[]>()
  for (const variant of variants) {
    const description = variant.evidence?.description
    if (!description) continue
    const group = byDescription.get(description)
    if (group) group.push(variant)
    else byDescription.set(description, [variant])
  }
  const descriptions = [...byDescription.keys()]
  if (!descriptions.length) return

  materialNotice.value = ''
  materialError.value = ''
  recognizingMaterial.value = true
  emit('busyChange', true)
  let found = 0
  try {
    for (let offset = 0; offset < descriptions.length; offset += 20) {
      const batch = descriptions.slice(offset, offset + 20)
      const suggestions = await api.suggestWorkbookMaterials(batch)
      for (const [index, description] of batch.entries()) {
        const suggestion = suggestions[index]
        if (!suggestion) continue
        for (const variant of byDescription.get(description) ?? []) {
          if (!variant.material?.trim()) setMaterial(variant, suggestion)
          else variant.suggestedMaterial = suggestion
          found++
        }
      }
    }
    materialNotice.value = found ? `${found} 个规格有材质建议，请对照原表核对。` : '未从所选说明中确认材质，可手工填写。'
  } catch (cause) {
    materialError.value = cause instanceof Error ? cause.message : '材质识别暂不可用，可手工填写'
  } finally {
    recognizingMaterial.value = false
    emit('busyChange', false)
  }
}

function draftBytes(draft: Draft) {
  return draft.images.reduce((bytes, candidate) => bytes + (candidate.selected ? (imageBytes[candidate.key] ?? 0) : 0), 0)
}

function applyDefaults() {
  for (const draft of drafts.value) {
    if (categoryMode.value === 'single' && categoryCode.value && !draft.product.categoryCode) {
      draft.product.categoryCode = categoryCode.value
      draft.categorySource = 'default'
    }
  }
}

watchEffect(() => {
  for (const draft of drafts.value) {
    if (!draft.sourceCategory || draft.categorySource || draft.product.categoryCode) continue
    const value = draft.sourceCategory.trim()
    const code = value.toUpperCase()
    const byName = props.categories.filter(category => category.dictName === value)
    const match = categoryLabels.value.has(code) ? code : byName.length === 1 ? byName[0]!.dictCode : ''
    if (match) {
      draft.product.categoryCode = match
      draft.categorySource = 'workbook'
    }
  }
})

function clearCategorySuggestions(source: 'default' | 'ai', validCodes: string[] = []) {
  for (const draft of drafts.value) {
    if (draft.categorySource === source && !validCodes.includes(draft.product.categoryCode)) {
      draft.product.categoryCode = ''
      draft.categorySource = null
    }
  }
}

function changeMode() {
  clearCategorySuggestions(categoryMode.value === 'mixed' ? 'default' : 'ai')
  suggestionNotice.value = ''
  suggestionError.value = ''
}

function changeCandidates() {
  clearCategorySuggestions('ai', candidateCodes.value)
  suggestionNotice.value = ''
  suggestionError.value = ''
}

async function suggestCategories() {
  if (props.busy || candidateCodes.value.length < 2 || !unclassified.value.length) return
  const targets = unclassified.value
  const candidates = candidateCodes.value.slice()
  suggestionNotice.value = ''
  suggestionError.value = ''
  let suggested = 0
  classifying.value = true
  emit('busyChange', true)
  try {
    for (let offset = 0; offset < targets.length; offset += 50) {
      const group = targets.slice(offset, offset + 50)
      const suggestions = await api.suggestWorkbookCategories(candidates, group.map(({ product }) => ({
        name: product.name,
        size: product.variants.map(variant => variant.sizeText || sizeLabel(variant)).join(' / ').slice(0, 160),
        material: product.variants.find(variant => variant.material)?.material?.slice(0, 128),
      })))
      group.forEach((draft, index) => {
        const code = suggestions[index]
        if (code && candidates.includes(code) && !draft.product.categoryCode) {
          draft.product.categoryCode = code
          draft.categorySource = 'ai'
          suggested++
        }
      })
    }
  } catch (cause) {
    suggestionError.value = cause instanceof Error ? cause.message : '品类识别暂不可用，可手工选择'
  } finally {
    suggestionNotice.value = '已为 ' + suggested + ' 件商品填入建议，' + (targets.length - suggested) + ' 件待手工核对。'
    classifying.value = false
    emit('busyChange', false)
  }
}

function pickReady() {
  for (const draft of visibleDrafts.value) {
    if (!draftIssue(draft)) draft.selected = true
  }
}

function toggleReadyPage() {
  const selected = !allReadyOnPageSelected.value
  for (const draft of readyOnPage.value) draft.selected = selected
}

function imageSrc(key: string) {
  const image = props.preview.images[key]
  if (props.previewId && image?.id) return api.workbookPreviewImageUrl(props.previewId, image.id)
  return image ? 'data:' + image.mediaType + ';base64,' + image.content : undefined
}

function mainImage(draft: Draft) {
  return draft.images.find(image => image.selected)
}

function sizeLabel(variant: LocalProductVariant) {
  if (!variant.widthMm || !variant.depthMm) return '尺寸待补'
  return [variant.widthMm, variant.depthMm, variant.heightMm].filter(Boolean).join(' × ') + ' mm'
}

async function confirm() {
  if (!canSubmit.value || props.busy) return
  const batch = selected.value
  const products = batch.map(draft => ({
    ...draft.product,
    variants: draft.product.variants.map(({ evidence, materialOrigin, suggestedMaterial, ...variant }) => ({
      ...variant,
      heightMm: variant.heightMm || null,
      referencePrice: variant.referencePrice ?? null,
    })),
    images: draft.images.filter(image => image.selected).map(image => props.preview.images[image.key]!),
  }))
  saving.value = true
  try {
    await props.saveBatch(products, saved => {
      const models = new Set(saved.map(product => product.factoryModel))
      drafts.value = drafts.value.filter(draft => !models.has(draft.product.factoryModel))
      if (filter.value === 'selected' && !selected.value.length) filter.value = 'all'
      if (!activeDraft.value) activeModel.value = (pageDrafts.value[0] ?? visibleDrafts.value[0] ?? drafts.value[0])?.product.factoryModel
    })
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <fieldset class="min-w-0 space-y-4" :disabled="busy">
    <legend class="sr-only">商品导入预览</legend>

    <template v-if="drafts.length">
      <section class="min-w-0 border border-line bg-white" aria-labelledby="import-category-heading">
        <div class="flex flex-wrap items-center justify-between gap-3 px-4 py-3 sm:px-5">
          <div>
            <h2 id="import-category-heading" class="text-base font-semibold">导入方式</h2>
            <p v-if="!showCategorySetup" class="mt-0.5 text-sm text-muted">
              {{ categoryMode === 'mixed' ? '混合品类导入' : '单一品类导入' }}
              <template v-if="categoryMode === 'single' && categoryCode"> · {{ categoryLabel(categoryCode) }}</template>
              <template v-else-if="categoryMode === 'mixed' && candidateCodes.length"> · {{ candidateCodes.length }} 个候选</template>
            </p>
          </div>
          <button v-if="categoryMode" type="button" class="inline-flex items-center gap-1 px-1 py-1.5 text-sm font-medium text-accent-ink"
            :aria-expanded="showCategorySetup" aria-controls="import-category-setup" @click="showCategorySetup = !showCategorySetup">
            {{ showCategorySetup ? '收起设置' : '展开设置' }}
            <AppIcon name="chevronDown" size="4" :class="showCategorySetup ? 'rotate-180' : ''" aria-hidden="true" />
          </button>
        </div>
        <div v-if="showCategorySetup" id="import-category-setup" class="space-y-3 px-4 pb-4 sm:px-5">
          <div class="grid gap-2 sm:grid-cols-2">
            <label class="relative flex cursor-pointer items-start gap-3 border p-3 transition-colors"
              :class="categoryMode === 'single' ? 'border-accent-line bg-accent-tint' : 'border-line hover:bg-bg'">
              <input v-model="categoryMode" name="excel-category-mode" type="radio" value="single" class="mt-0.5 size-4 shrink-0 accent-accent"
                aria-label="单一品类导入" @change="changeMode">
              <span class="min-w-0"><span class="block text-sm font-semibold">单一品类</span>
                <span class="mt-0.5 block text-xs text-muted">用同一个品类补齐空白项</span></span>
            </label>
            <label class="relative flex cursor-pointer items-start gap-3 border p-3 transition-colors"
              :class="categoryMode === 'mixed' ? 'border-accent-line bg-accent-tint' : 'border-line hover:bg-bg'">
              <input v-model="categoryMode" name="excel-category-mode" type="radio" value="mixed" class="mt-0.5 size-4 shrink-0 accent-accent"
                aria-label="混合品类导入" @change="changeMode">
              <span class="min-w-0"><span class="block text-sm font-semibold">混合品类</span>
                <span class="mt-0.5 block text-xs text-muted">从候选范围内逐件识别品类</span></span>
            </label>
          </div>

          <div v-if="categoryMode === 'single'" class="flex flex-wrap items-end gap-2 border-t border-line pt-3">
            <ProductsCategoryField v-model="categoryCode" :categories="categories" label="默认商品品类" class="w-full sm:w-64"
              @change="code => clearCategorySuggestions('default', [code])" />
            <button type="button" :class="button" :disabled="!categoryCode" @click="applyDefaults">填入空白项</button>
          </div>
          <template v-if="categoryMode === 'mixed'">
            <p v-if="!categories.length" class="text-sm text-muted">品类字典暂不可用，可在商品明细手工填写品类编码。</p>
            <div v-else class="space-y-3 border-t border-line pt-3">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <label class="relative w-full sm:max-w-[17rem]">
                  <span class="sr-only">搜索候选品类</span>
                  <AppIcon name="search" size="4" aria-hidden="true" class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
                  <input v-model="categoryQuery" type="search" placeholder="搜索品类名称或编码" :class="control" class="pl-9">
                </label>
                <span class="text-sm text-muted">已选 {{ candidateCodes.length }} 项 · 至少选 2 项</span>
              </div>
              <div v-if="visibleCategories.length" class="grid max-h-32 grid-cols-1 gap-1 overflow-y-auto sm:grid-cols-2 xl:grid-cols-3">
                <label v-for="category in visibleCategories" :key="category.dictCode" class="flex min-w-0 items-center gap-2 border border-line px-2.5 py-2 text-sm"
                  :class="candidateCodes.includes(category.dictCode) ? 'bg-accent-tint text-accent-ink' : 'hover:bg-bg'">
                  <input v-model="candidateCodes" type="checkbox" :value="category.dictCode" class="size-4 shrink-0 accent-accent"
                    :disabled="candidateCodes.length >= 40 && !candidateCodes.includes(category.dictCode)" @change="changeCandidates">
                  <span class="min-w-0 break-words">{{ category.dictName }} · {{ category.dictCode }}</span>
                </label>
              </div>
              <p v-else class="px-2 py-1 text-sm text-muted">没有匹配的品类。</p>
              <div class="flex flex-wrap items-center gap-3">
                <button type="button" :class="button" :disabled="candidateCodes.length < 2 || !unclassified.length" @click="suggestCategories">
                  {{ classifying ? '正在识别…' : 'Qwen 识别品类' }}
                </button>
                <p v-if="suggestionNotice" role="status" class="text-sm text-muted">{{ suggestionNotice }}</p>
              </div>
              <p v-if="suggestionError" role="alert" class="text-sm text-red-700">{{ suggestionError }}</p>
            </div>
          </template>
        </div>
      </section>

      <div class="grid min-w-0 items-start gap-4 lg:grid-cols-[minmax(17rem,2fr)_minmax(0,3fr)]">
        <section ref="listPanel" class="min-w-0 border border-line bg-white" aria-labelledby="import-products-heading">
          <div class="space-y-3 border-b border-line px-3 py-3 sm:px-4">
            <div class="flex flex-wrap items-baseline justify-between gap-1.5">
              <h2 id="import-products-heading" class="text-base font-semibold">预览商品 <span class="ml-1 text-sm font-normal text-muted">{{ drafts.length }} 件</span></h2>
              <span v-if="importedCount" class="text-xs font-medium text-green">已导入 {{ importedCount }} 件</span>
            </div>
            <button v-if="unfilledMaterials.length" type="button" :class="button" @click="suggestMaterials(unfilledMaterials)">
              {{ recognizingMaterial ? '正在识别材质…' : 'Qwen 补齐空白材质' }}
            </button>
            <label class="relative block">
              <span class="sr-only">搜索预览商品</span>
              <AppIcon name="search" size="4" aria-hidden="true" class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
              <input v-model="query" type="search" placeholder="搜索名称、型号、品类" :class="control" class="pl-9">
            </label>
            <div class="flex flex-wrap gap-1" role="group" aria-label="筛选预览商品">
              <button v-for="item in filters" :key="item.key" type="button" :aria-pressed="filter === item.key"
                class="border px-2 py-1.5 text-xs font-medium whitespace-nowrap"
                :class="filter === item.key ? 'border-accent-line bg-accent-tint text-accent-ink' : 'border-transparent text-muted hover:bg-bg'"
                @click="filter = item.key">
                {{ item.label }} {{ filterCounts[item.key] }}
              </button>
            </div>
            <div v-if="visibleDrafts.length" class="flex flex-wrap items-center justify-between gap-2">
              <ProductsWorkbookPreviewPager v-model="page" :total="visibleDrafts.length" />
              <button type="button" class="text-xs font-medium text-accent-ink disabled:opacity-45" :disabled="!readyOnPage.length" @click="toggleReadyPage">
                {{ allReadyOnPageSelected ? '取消本页可导入项' : '选中本页可导入项' }}
              </button>
            </div>
          </div>

          <div v-if="pageDrafts.length" class="divide-y divide-line">
            <article v-for="draft in pageDrafts" :key="draft.product.factoryModel"
              class="flex min-w-0 items-start gap-2 px-3 py-2 transition-colors sm:px-4"
              :class="draft === activeDraft ? 'bg-accent-tint' : 'hover:bg-bg'">
              <input v-model="draft.selected" type="checkbox" :aria-label="'选择 ' + draft.product.factoryModel" class="mt-3.5 size-4 shrink-0 accent-accent">
              <button type="button" class="flex min-w-0 flex-1 items-start gap-2.5 text-left"
                :aria-label="'核对 ' + draft.product.factoryModel" :aria-pressed="draft === activeDraft"
                aria-controls="import-product-detail" @click="showDraft(draft)">
                <span class="grid size-11 shrink-0 place-items-center border border-line bg-white text-muted">
                  <img v-if="mainImage(draft)" :src="imageSrc(mainImage(draft)!.key)" loading="lazy" alt="" class="size-full object-contain">
                  <AppIcon v-else name="photo" size="4.25" aria-hidden="true" />
                </span>
                <span class="min-w-0 flex-1">
                  <span class="block break-words text-sm font-semibold">{{ draft.product.name }}</span>
                  <span class="mt-0.5 block break-all text-xs text-muted">{{ draft.product.factoryModel }}</span>
                  <span v-if="draft.product.categoryCode" class="mt-1 block break-words text-xs text-muted">
                    {{ categoryLabel(draft.product.categoryCode) }}
                  </span>
                  <span v-if="draftIssue(draft)" class="mt-1 block text-xs font-medium text-amber-800">{{ draftIssue(draft) }}</span>
                </span>
              </button>
            </article>
          </div>
          <p v-else class="px-4 py-8 text-center text-sm text-muted">没有符合当前条件的商品。</p>
        </section>

        <section v-if="activeDraft" id="import-product-detail" ref="detailPanel"
          class="min-w-0 scroll-mt-4 border border-line bg-white" aria-labelledby="import-detail-heading">
          <div class="flex flex-wrap items-start justify-between gap-3 border-b border-line px-4 py-3 sm:px-5">
            <div class="min-w-0">
              <h2 id="import-detail-heading" class="break-words text-base font-semibold">{{ activeDraft.product.name }}</h2>
              <p class="mt-1 break-words text-xs text-muted">
                {{ activeDraft.product.factoryModel }} · {{ activeDraft.sources.map(source => source.sheet + ' · ' + source.column + source.row).join(' / ') }}
              </p>
            </div>
            <div class="flex flex-wrap items-center gap-2">
              <button type="button" class="px-1 py-1 text-xs font-medium text-accent-ink disabled:opacity-40"
                :disabled="currentIndex <= 0" @click="moveDraft(-1)">上一件</button>
              <button type="button" class="px-1 py-1 text-xs font-medium text-accent-ink disabled:opacity-40"
                :disabled="currentIndex < 0 || currentIndex + 1 >= visibleDrafts.length" @click="moveDraft(1)">下一件</button>
              <span v-if="draftIssue(activeDraft)" class="border border-amber-200 bg-amber-50 px-2 py-1 text-xs font-medium text-amber-800">
                {{ draftIssue(activeDraft) }}
              </span>
              <span v-else class="border border-green/25 bg-green/10 px-2 py-1 text-xs font-medium text-green">资料齐全</span>
              <button type="button" class="px-1 py-1 text-xs font-medium text-accent-ink lg:hidden"
                @click="listPanel?.scrollIntoView({ block: 'start' })">查看商品列表</button>
            </div>
          </div>
          <div class="space-y-5 p-4 sm:p-5">
            <div class="grid min-w-0 gap-3 sm:grid-cols-2">
              <label class="min-w-0 space-y-1.5 text-sm">
                <span class="block font-medium">商品名称</span><input v-model.trim="activeDraft.product.name" :class="control" maxlength="200">
              </label>
              <div class="min-w-0">
                <ProductsCategoryField v-model="activeDraft.product.categoryCode" :categories="categories" @change="activeDraft.categorySource = 'manual'" />
                <p v-if="activeDraft.categorySource === 'ai'" class="mt-1 text-xs text-accent-ink">Qwen 建议</p>
                <p v-else-if="activeDraft.sourceCategory" class="mt-1 break-words text-xs text-muted">原表品类 {{ activeDraft.sourceCategory }}</p>
              </div>
            </div>

            <section class="space-y-2" aria-label="商品规格">
              <h3 class="text-sm font-semibold">规格与尺寸 <span class="ml-1 font-normal text-muted">{{ activeDraft.product.variants.length }} 个规格</span></h3>
              <p v-if="materialNotice" class="text-xs text-accent-ink" role="status">{{ materialNotice }}</p>
              <p v-if="materialError" class="text-xs text-red-700" role="alert">{{ materialError }}</p>
              <div v-for="(variant, index) in activeDraft.product.variants" :key="index"
                class="grid grid-cols-2 gap-2.5 border border-line bg-bg p-3 md:gap-3 xl:grid-cols-4">
                <label class="space-y-1 text-xs"><span class="font-medium">规格名称</span><input v-model.trim="variant.name" :class="control" maxlength="128"></label>
                <label class="space-y-1 text-xs"><span class="font-medium">宽（mm）</span><input v-model.number="variant.widthMm" :class="control" type="number" min="0.01" max="100000" step="0.01"></label>
                <label class="space-y-1 text-xs"><span class="font-medium">深（mm）</span><input v-model.number="variant.depthMm" :class="control" type="number" min="0.01" max="100000" step="0.01"></label>
                <label class="space-y-1 text-xs"><span class="font-medium">高（mm）</span><input v-model.number="variant.heightMm" :class="control" type="number" min="0.01" max="100000" step="0.01"></label>
                <label class="space-y-1 text-xs"><span class="font-medium">尺寸原文</span><input v-model.trim="variant.sizeText" :class="control" maxlength="64"></label>
                <label class="space-y-1 text-xs"><span class="font-medium">颜色</span><input v-model.trim="variant.color" :class="control" maxlength="64"></label>
                <label class="space-y-1 text-xs"><span class="font-medium">参考价（元）</span>
                  <input :value="variant.referencePrice ?? ''" :class="control" type="number" min="0.01" max="9999999999.99" step="0.01"
                    placeholder="待补" @input="updateReferencePrice(variant, $event)">
                  <span v-if="variant.evidence?.referencePriceCell" class="block text-muted">
                    原表 {{ variant.evidence.referencePriceCell.column }}{{ variant.evidence.referencePriceCell.row }}
                  </span>
                </label>
                <label class="min-w-0 space-y-1 text-xs"><span class="font-medium">材质</span>
                  <input v-model.trim="variant.material" :class="control" maxlength="128"
                    @input="variant.materialOrigin = null; variant.suggestedMaterial = undefined">
                  <span v-if="variant.materialOrigin" class="block font-medium text-accent-ink">{{ materialLabels[variant.materialOrigin] }}</span>
                </label>
                <button v-if="activeDraft.product.variants.length > 1" type="button" class="self-end justify-self-start px-1 py-2 text-xs text-red-700"
                  @click="activeDraft.product.variants.splice(index, 1)">移除此规格</button>
                <div v-if="variant.evidence?.description" class="col-span-full space-y-1.5 border-t border-line pt-2">
                  <div class="flex flex-wrap items-start justify-between gap-2">
                    <details class="min-w-0 flex-1 text-xs text-muted">
                      <summary class="font-medium">原表说明
                        <span v-if="variant.evidence.descriptionCell" class="font-normal">· {{ variant.evidence.descriptionCell.column }}{{ variant.evidence.descriptionCell.row }}</span>
                      </summary>
                      <p class="mt-1.5 whitespace-pre-wrap break-words">{{ variant.evidence.description }}</p>
                    </details>
                    <button type="button" class="shrink-0 text-xs font-medium text-accent-ink hover:underline"
                      @click="suggestMaterials([variant])">{{ recognizingMaterial ? '识别中…' : 'Qwen 核对材质' }}</button>
                  </div>
                  <div v-if="variant.suggestedMaterial" class="flex flex-wrap items-center gap-2 text-xs">
                    <span class="min-w-0 break-words text-accent-ink">Qwen 建议 {{ variant.suggestedMaterial }}</span>
                    <button type="button" class="font-medium text-accent-ink underline" @click="setMaterial(variant, variant.suggestedMaterial)">采用建议</button>
                  </div>
                </div>
              </div>
            </section>

            <section class="space-y-2" aria-label="商品图片">
              <h3 class="text-sm font-semibold">商品图片 <span class="ml-1 font-normal text-muted">已选 {{ activeDraft.images.filter(image => image.selected).length }} 张</span></h3>
              <div v-if="activeDraft.images.length" class="flex flex-wrap gap-3">
                <figure v-for="(image, index) in activeDraft.images" :key="image.key" class="w-24 space-y-1.5">
                  <img :src="imageSrc(image.key)" loading="lazy" :alt="'候选图 ' + (index + 1)" class="h-20 w-full border border-line object-contain">
                  <figcaption class="flex flex-wrap items-center justify-between gap-1 text-xs">
                    <label class="inline-flex items-center gap-1">
                      <input v-model="image.selected" type="checkbox" class="accent-accent"
                        :disabled="!image.selected && activeDraft.images.filter(item => item.selected).length >= 6">
                      {{ image.selected ? '已选' : '选用' }}
                    </label>
                    <span v-if="image === mainImage(activeDraft)" class="font-medium text-accent-ink">主图</span>
                    <button v-else-if="image.selected" type="button" class="text-accent-ink"
                      @click="activeDraft.images.unshift(image); activeDraft.images.splice(index + 1, 1)">设为主图</button>
                  </figcaption>
                </figure>
              </div>
              <p v-else class="text-sm text-muted">这件商品没有关联图片，可在导入后补图。</p>
            </section>
          </div>
        </section>
      </div>

      <div class="sticky bottom-0 z-10 flex flex-wrap items-center justify-between gap-2 border border-accent-line bg-white px-3 py-3 sm:px-4">
        <div class="min-w-0" aria-live="polite">
          <p class="text-sm font-semibold">已选 {{ selected.length }} 件
            <span class="font-normal text-muted">· Excel {{ workbookSize(fileBytes) }} · 已选图片 {{ workbookSize(totalBytes) }}</span>
          </p>
          <p v-if="selected.length && !canSubmit" class="mt-0.5 text-xs text-amber-800">
            <template v-if="!categoryMode">请先选择导入方式。</template>
            <template v-else-if="selectedIssues">有 {{ selectedIssues }} 件还需核对。</template>
            <template v-else-if="capacityBlocked">已超出本工作区剩余名额，请减少选择。</template>
            <template v-else-if="priceConfirmationRequired">请选择参考价列或确认跳过未匹配的价格。</template>
          </p>
        </div>
        <div class="flex flex-wrap gap-2">
          <button v-if="selected.length" type="button" class="px-1 py-2 text-xs font-medium text-muted hover:text-ink"
            @click="selected.forEach(draft => { draft.selected = false })">清空选择</button>
          <button type="button" :class="button" :disabled="!categoryMode || !hasPickable" @click="pickReady">选取可导入项</button>
          <button type="button" :class="primary" :disabled="!canSubmit || saving" @click="confirm">
            {{ progress ? `正在导入 ${progress.number}/${progress.total}` : '确认导入' }}
          </button>
        </div>
      </div>
    </template>
    <div v-else class="flex items-center gap-3 border border-line bg-white p-5" role="status">
      <AppIcon name="checkCircle" size="6" class="shrink-0 text-green" aria-hidden="true" />
      <p class="text-sm font-medium">此文件的 {{ importedCount }} 件商品已导入，可更换文件继续录入。</p>
      <span class="text-sm text-muted">Excel {{ workbookSize(fileBytes) }}</span>
    </div>
  </fieldset>
</template>
