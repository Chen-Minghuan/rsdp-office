<script setup lang="ts">
import type { CatalogFacets, CatalogProductFilters } from '~/types/imported-products'
import { cloneProductFilters, countProductFilters, emptyProductFilters, FILTER_KEYS, toggleProductFilter } from '~/utils/productFilter'

const props = defineProps<{
  open: boolean
  filters: CatalogProductFilters
  facets: CatalogFacets | null
  saving: boolean
  error: string
  top?: number
}>()

const emit = defineEmits<{
  confirm: [value: CatalogProductFilters]
  cancel: []
}>()

const GROUP_LABELS: Record<string, string> = {
  levels: '产品等级',
  spaceTypes: '空间类型',
  productTypes: '产品分类',
  materials: '材质分类',
  colors: '颜色分类',
  widthRanges: '尺寸分类',
  legTypes: '桌脚分类',
  storageTypes: '收纳分类',
}

const TAB_SIZE = 4
const MAX_TAB_START = FILTER_KEYS.length - TAB_SIZE

const dialog = ref<HTMLDialogElement | null>(null)
const bodyEl = ref<HTMLElement | null>(null)
const draft = ref<CatalogProductFilters>(emptyProductFilters())
const activeGroup = ref<string>('levels')
const tabStart = ref(0)
const expanded = ref<Record<string, boolean>>({})
const groupSections = new Map<string, HTMLElement>()
const draftCount = computed(() => countProductFilters(draft.value))
const visibleTabs = computed(() => FILTER_KEYS.slice(tabStart.value, tabStart.value + TAB_SIZE))

watch(() => props.open, (open) => {
  if (open) {
    draft.value = cloneProductFilters(props.filters)
    activeGroup.value = FILTER_KEYS.find(key => props.filters[key].length > 0) ?? 'levels'
    tabStart.value = Math.min(Math.max(0, FILTER_KEYS.indexOf(activeGroup.value as typeof FILTER_KEYS[number]) - 1), MAX_TAB_START)
    expanded.value = {}
    nextTick(() => { if (dialog.value && !dialog.value.open) dialog.value.show() })
    window.addEventListener('keydown', onKeydown)
  } else if (dialog.value?.open) {
    window.removeEventListener('keydown', onKeydown)
    dialog.value.close()
  }
})

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') emit('cancel')
}

watch(() => props.filters, (value) => {
  draft.value = cloneProductFilters(value)
})

watch(() => props.error, (value) => {
  if (value) draft.value = cloneProductFilters(props.filters)
})

function optionsFor(key: string) {
  return props.facets?.groups?.[key as keyof CatalogProductFilters] ?? []
}

function visibleOptions(key: string) {
  const options = optionsFor(key)
  return expanded.value[key] ? options : options.slice(0, 6)
}

function isSelected(key: string, code: string) {
  return draft.value[key as keyof CatalogProductFilters].some(value => String(value) === code)
}

function toggleOption(key: string, code: string) {
  const next = toggleProductFilter(draft.value, key as keyof CatalogProductFilters, code)
  draft.value = next
  emit('confirm', cloneProductFilters(next))
}

let scrollIntentUntil = 0

function selectGroup(key: string) {
  activeGroup.value = key
  scrollIntentUntil = Date.now() + 500
  ensureTabVisible(key)
  nextTick(() => groupSections.get(key)?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}

function ensureTabVisible(key: string) {
  const index = FILTER_KEYS.indexOf(key as typeof FILTER_KEYS[number])
  if (index < tabStart.value) tabStart.value = index
  else if (index >= tabStart.value + TAB_SIZE) tabStart.value = Math.min(index - TAB_SIZE + 1, MAX_TAB_START)
}

function onBodyScroll() {
  if (Date.now() < scrollIntentUntil || !bodyEl.value) return
  const body = bodyEl.value
  if (body.scrollTop + body.clientHeight >= body.scrollHeight - 2) {
    const last = FILTER_KEYS[FILTER_KEYS.length - 1]
    if (activeGroup.value !== last) activeGroup.value = last
    ensureTabVisible(last)
    return
  }
  const bodyTop = body.getBoundingClientRect().top
  let current: string = FILTER_KEYS[0]
  for (const key of FILTER_KEYS) {
    const el = groupSections.get(key)
    if (el && el.getBoundingClientRect().top - bodyTop <= 16) current = key
  }
  if (current !== activeGroup.value) activeGroup.value = current
  ensureTabVisible(current)
}

function moveTabs(delta: number) {
  tabStart.value = Math.min(Math.max(0, tabStart.value + delta), MAX_TAB_START)
  selectGroup(FILTER_KEYS[tabStart.value])
}

function setSectionRef(key: string, el: unknown) {
  if (el instanceof HTMLElement) groupSections.set(key, el)
}

function toggleExpand(key: string) {
  expanded.value = { ...expanded.value, [key]: !expanded.value[key] }
}

function clearDraft() {
  draft.value = emptyProductFilters()
  emit('confirm', emptyProductFilters())
}

function cancel() {
  emit('cancel')
}

function onClose() {
  if (props.open) emit('cancel')
}
</script>

<template>
  <dialog ref="dialog" class="advanced-filter-dialog" aria-label="全部筛选" :style="{ '--af-top': `${props.top ?? 0}px` }" @close="onClose">
    <header class="af-header">
      <h2 class="af-title">全部筛选</h2>
      <span class="af-draft-count" aria-live="polite">已选 {{ draftCount }} 项</span>
      <button type="button" class="af-close" aria-label="关闭全部筛选" @click="cancel">
        <AppIcon name="close" size="5" />
      </button>
    </header>
    <div class="af-tabs">
      <button type="button" class="af-tab-arrow" aria-label="上一组筛选分类" :disabled="tabStart === 0" @click="moveTabs(-1)">
        <svg viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M10 3 5 8l5 5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" /></svg>
      </button>
      <button v-for="key in visibleTabs" :key="key" type="button" class="af-tab"
        :class="{ 'is-active': activeGroup === key }" @click="selectGroup(key)">
        {{ GROUP_LABELS[key] }}
      </button>
      <button type="button" class="af-tab-arrow" aria-label="下一组筛选分类" :disabled="tabStart >= MAX_TAB_START" @click="moveTabs(1)">
        <svg viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="m6 3 5 5-5 5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" /></svg>
      </button>
    </div>
    <div ref="bodyEl" class="af-body" @scroll.passive="onBodyScroll">
      <section v-for="key in FILTER_KEYS" :key="key" :ref="(el: unknown) => setSectionRef(key, el)"
        class="af-group" :aria-label="GROUP_LABELS[key]">
        <h3 class="af-group-title">
          {{ GROUP_LABELS[key] }}
          <button v-if="optionsFor(key).length > 6" type="button" class="af-expand" @click="toggleExpand(key)">
            {{ expanded[key] ? '收起' : '展开全部' }}
          </button>
        </h3>
        <p v-if="optionsFor(key).length === 0" class="af-empty">当前范围内没有可用的{{ GROUP_LABELS[key] }}选项。</p>
        <div v-else class="af-options">
          <button v-for="option in visibleOptions(key)" :key="option.code" type="button" class="af-option"
            :class="{ 'is-selected': isSelected(key, option.code) }" :aria-pressed="isSelected(key, option.code)"
            :disabled="option.disabled && !isSelected(key, option.code)"
            @click="toggleOption(key, option.code)">
            {{ option.label }}
          </button>
        </div>
      </section>
    </div>
    <footer class="af-footer" data-testid="advanced-filter-footer">
      <p v-if="error" class="af-error" role="alert">{{ error }}</p>
      <div class="af-actions">
        <button type="button" class="af-clear" :disabled="draftCount === 0" @click="clearDraft">清空</button>
        <span v-if="saving" class="af-saving">应用中…</span>
        <button type="button" class="af-confirm" @click="cancel">完成</button>
      </div>
    </footer>
  </dialog>
</template>

<style scoped>
.advanced-filter-dialog {
  display: none;
  position: sticky;
  top: 12px;
  margin: 0;
  width: 100%;
  max-width: none;
  height: calc(100dvh - var(--af-top, 0px) - 12px);
  max-height: none;
  padding: 0;
  border: 1px solid #ece7df;
  border-radius: 0.6rem;
  background: #fff;
  color: #26221c;
  box-shadow: 0 8px 24px rgb(61 55 48 / 0.06);
  flex-direction: column;
}
.advanced-filter-dialog[open] { display: flex; }
.af-header { display: flex; align-items: center; gap: 0.75rem; padding: 1rem 1.1rem 0.7rem; }
.af-title { flex: 1; font-size: 1.05rem; font-weight: 700; }
.af-draft-count { font-size: 0.75rem; color: #7c7267; }
.af-close { border: none; background: none; padding: 0.25rem; color: #7c7267; cursor: pointer; border-radius: 0.5rem; }
.af-close:hover { background: #f6f2ec; }
.af-tabs { display: flex; align-items: center; gap: 0.15rem; padding: 0 0.6rem 0.55rem; border-bottom: 1px solid #f1ece4; }
.af-tab-arrow {
  width: 1.4rem; height: 1.9rem; border: none; border-radius: 0.35rem; background: none;
  color: #8b8178; cursor: pointer; display: inline-flex; align-items: center; justify-content: center;
}
.af-tab-arrow svg { width: 0.85rem; height: 0.85rem; }
.af-tab-arrow:hover:not(:disabled) { background: #f6f2ec; color: #35302a; }
.af-tab-arrow:disabled { opacity: 0.3; cursor: default; }
.af-tab {
  flex: 1;
  border: none; background: none; cursor: pointer; white-space: nowrap;
  padding: 0.45rem 0.3rem; font-size: 0.82rem; color: #6b6258; text-align: center;
  border-bottom: 2px solid transparent;
}
.af-tab.is-active { color: #3d3730; border-bottom-color: #8b8178; font-weight: 700; }
.af-body { flex: 1; min-height: 0; overflow-y: auto; padding: 0.25rem 1.1rem 1rem; }
.af-group { padding: 0.85rem 0 0.35rem; scroll-margin-top: 0.5rem; }
.af-group-title { display: flex; align-items: center; justify-content: space-between; font-size: 0.82rem; font-weight: 700; margin-bottom: 0.6rem; }
.af-expand { border: none; background: none; color: #a39a8e; font-size: 0.7rem; font-weight: 400; cursor: pointer; }
.af-expand:hover { color: #6b6258; }
.af-empty { font-size: 0.78rem; color: #7c7267; }
.af-options { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.5rem; }
.af-option {
  border: 1px solid #e9e2d8; border-radius: 0.3rem; background: #fbfaf8;
  min-height: 2.2rem; padding: 0.3rem 0.25rem; font-size: 0.78rem; color: #4a443c; cursor: pointer;
}
.af-option:hover:not(:disabled) { border-color: #b8b0a4; }
.af-option.is-selected { border-color: #8b8178; background: #f6f2ec; color: #35302a; font-weight: 600; }
.af-option:disabled { color: #c4bbaf; background: #faf9f7; border-color: #f0ebe2; cursor: not-allowed; }
.af-footer { border-top: 1px solid #f1ece4; padding: 0.75rem 1.1rem; }
.af-error { font-size: 0.75rem; color: #9c3627; margin-bottom: 0.5rem; }
.af-actions { display: flex; align-items: center; justify-content: flex-end; gap: 0.6rem; }
.af-clear { margin-right: auto; border: none; background: none; color: #7c7267; font-size: 0.8rem; cursor: pointer; padding: 0.4rem 0.2rem; }
.af-clear:hover:not(:disabled) { color: #35302a; text-decoration: underline; }
.af-clear:disabled { opacity: 0.4; cursor: not-allowed; }
.af-saving { font-size: 0.75rem; color: #8b8178; }
.af-confirm { flex: 1; max-width: 220px; border: none; border-radius: 0.4rem; background: #7c5cab; color: #fff; padding: 0.65rem 1rem; font-size: 0.9rem; font-weight: 700; cursor: pointer; }
.af-confirm:hover:not(:disabled) { background: #6a4c96; }
.af-confirm:disabled { opacity: 0.55; cursor: not-allowed; }

@media (min-width: 641px) and (max-width: 1099px) {
  .advanced-filter-dialog {
    position: fixed;
    top: var(--af-top, 0px);
    right: 0;
    bottom: 0;
    left: auto;
    width: 400px;
    height: auto;
    border-radius: 0;
    z-index: 50;
  }
}

@media (max-width: 640px) {
  .advanced-filter-dialog { position: fixed; inset: 0; top: 0; width: 100vw; height: 100dvh; border: none; border-radius: 0; z-index: 50; }
  .af-footer { padding: 0.75rem 1.1rem calc(0.75rem + env(safe-area-inset-bottom)); }
}
</style>
