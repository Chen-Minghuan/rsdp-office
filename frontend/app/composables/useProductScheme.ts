import type { BundleItem, ProductBundle, ProposalConfirmation, RoomSchemeState, SchemeSlot } from '~/types/productScheme'
import type { RenderPreview } from '~/types/renderPreview'
import { productBundleError } from '~/utils/productBundleError'

export function useProductScheme(active: Ref<boolean>) {
  const store = useOfficeStore()
  const workspace = useWorkspaceStore()
  const api = useOfficeApi()
  const state = ref<RoomSchemeState | null>(null)
  const busy = ref(false)
  const message = ref('')
  const error = ref('')
  const source = ref<'auto' | 'project' | 'personal'>('auto')
  const favoriteIds = ref<string[]>()
  const sourceError = ref('')
  const bundles = ref<ProductBundle[]>([])
  const bundleId = ref('')
  const clipboard = ref<BundleItem[]>([])
  const confirmation = ref<ProposalConfirmation | null>(null)
  const renders = ref<RenderPreview[]>([])
  const room = computed(() => store.zones.find(zone => zone.id === store.designZoneId))
  const ready = computed(() => store.apiReady && !!room.value && room.value.evidenceKind !== 'DEMO' && store.layoutConfirmedForZone(room.value.id))
  const furniture = computed(() => store.furnitureForZone(room.value?.id || ''))
  const selectedCount = computed(() => furniture.value.filter(item => item.productSelection && item.productId).length)
  const canClear = computed(() => !!room.value && (furniture.value.some(item => item.productId || item.productSelection)
    || store.cart.some(item => item.zoneId === room.value?.id)))
  const canApply = computed(() => ready.value && selectedCount.value < furniture.value.length)
  const currentConfirmation = computed(() => confirmation.value?.current && confirmation.value.canvasRevision === store.canvasRevision
    ? confirmation.value.confirmationId : undefined)
  const feedback = (failure: unknown) => failure instanceof Error ? failure.message : '操作失败，请重试'
  let renderRead = 0

  async function refreshRenders() {
    const read = ++renderRead, floorId = store.workspaceId, zoneId = store.designZoneId
    if (!active.value || !floorId || !zoneId) return
    try {
      const data = await useRenderPreviewApi(floorId).list(zoneId)
      if (read !== renderRead) return
      renders.value = Array.isArray(data)
        ? data.filter(item => item.purpose === 'CONFIRMED_SCHEME' && item.status === 'SUCCEEDED' && !!item.imageUrl).slice(0, 2)
        : []
    } catch {
      if (read === renderRead) renders.value = []
    }
  }

  watch([active, () => store.workspaceId, () => store.designZoneId, () => store.canvasRevision, () => store.schemeToken],
    ([open, floorId, zoneId], _, onCleanup) => {
      let current = true
      onCleanup(() => { current = false })
      if (!open || !floorId || !zoneId || !store.apiReady) {
        renderRead++
        return
      }
      void api.loadRoomScheme(floorId, zoneId).then(data => {
        if (current) state.value = data?.activeSlot ? data : null
      }).catch(() => { if (current) state.value = null })
      void api.loadProposalConfirmation(floorId).then(data => {
        if (current) confirmation.value = data
      }).catch(() => { if (current) confirmation.value = null })
      void refreshRenders()
    }, { immediate: true })

  watch([() => store.workspaceId, () => store.designZoneId, active], () => {
    state.value = null
    confirmation.value = null
    renders.value = []
    error.value = ''
    message.value = ''
  })

  watch([active, source, () => workspace.currentProjectId], async ([open, currentSource, projectId], _, onCleanup) => {
    let current = true
    onCleanup(() => { current = false })
    favoriteIds.value = currentSource === 'auto' ? undefined : []
    sourceError.value = ''
    if (!open || currentSource === 'auto') return
    try {
      const data = currentSource === 'personal' ? await api.loadFavoriteProductIds()
        : projectId ? await api.loadProjectFavoriteProductIds(projectId) : []
      if (current) favoriteIds.value = data || []
    } catch (failure) {
      if (current) sourceError.value = feedback(failure)
    }
  }, { immediate: true })

  watch(active, async (open, _, onCleanup) => {
    let current = true
    onCleanup(() => { current = false })
    if (!open) return
    try {
      const data = await api.loadProductBundles()
      if (current) bundles.value = data || []
    } catch (failure) {
      if (current) error.value = productBundleError(failure, '暂时无法读取商品组合。', '当前服务暂未提供商品组合。')
    }
  }, { immediate: true })

  async function change(action: 'activate' | 'clear-selection' | 'apply-products',
    options: { targetSlot?: SchemeSlot; automatic?: boolean; items?: BundleItem[] } = {}) {
    const floorId = store.workspaceId, zoneId = store.designZoneId
    if (!floorId || !zoneId || !store.apiReady || busy.value) return false
    if (action === 'activate' && state.value?.activeSlot === options.targetSlot) return false
    busy.value = true
    error.value = ''
    message.value = ''
    try {
      const version = await store.flushSchemeCanvas(floorId)
      if (zoneId !== store.designZoneId) return false
      const result = await api.changeRoomScheme(floorId, action, { ...version, zoneId, ...options })
      if (store.workspaceId !== floorId) return false
      store.applyRoomScheme(result, floorId)
      confirmation.value = null
      if (zoneId !== store.designZoneId) return false
      if (result.scheme) state.value = result.scheme
      message.value = action === 'activate' ? `已切换至 Plan ${result.scheme?.activeSlot}`
        : action === 'clear-selection' ? '已清空当前房间选品'
          : `已更新当前房间选品，剩余 ${furniture.value.length - selectedCount.value} 件家具待选`
      return true
    } catch (failure) {
      if (store.workspaceId === floorId && store.designZoneId === zoneId) error.value = feedback(failure)
      return false
    } finally {
      busy.value = false
    }
  }

  function copy() {
    const items = new Map<string, BundleItem>()
    for (const item of furniture.value) {
      const product = item.productSelection?.product
      if (!product?.rspuId || !product.id) continue
      const key = JSON.stringify([product.rspuId, product.id])
      const current = items.get(key)
      if (current) current.quantity++
      else items.set(key, { rspuId: product.rspuId, variantId: product.id, quantity: 1 })
    }
    clipboard.value = [...items.values()]
    error.value = ''
    message.value = `已复制 ${clipboard.value.reduce((sum, item) => sum + item.quantity, 0)} 件商品`
  }

  function applyBundle() {
    const bundle = bundles.value.find(item => item.bundleId === bundleId.value)
    if (bundle?.items.length) void change('apply-products', { items: bundle.items })
  }

  return { state, busy, error, message, source, favoriteIds, sourceError, bundles, bundleId, clipboard,
    room, furniture, selectedCount, canApply, canClear, currentConfirmation, renders, refreshRenders, change, copy, applyBundle }
}
