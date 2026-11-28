import type { ModuleRecommendation, ModuleRecommendationRequest, ModuleProductSelection } from '~/types/moduleRecommendation'
import type { LayoutReferenceMark } from '~/types/layoutPlanning'
import type { OfficeObject, CartItem, Product, ProjectFile, ProjectQuotationPreview, QuotationPricingMode, ProjectQuotationRecord, ProjectQuotationLatest, ConfirmQuotationRequest } from '~/types/office'
import type { CatalogBrowseFacets, CatalogBrowsePage, CatalogCapabilities, CatalogProductFilters, CatalogProductPage, ImportedProduct, ProductDetail } from '~/types/imported-products'
import { FILTER_KEYS } from '~/utils/productFilter'
import type { FavoriteFolder, ProjectFavoriteState } from '~/types/favorites'
import type { WorkspaceType } from '~/utils/workspaceType'
import type { BundleItem, ProductBundle, ProposalConfirmation, RoomSchemeResult, RoomSchemeSlots, RoomSchemeState, SchemeSlot, SchemeVersion } from '~/types/productScheme'
import type {
  LocalProductInput, LocalProductCatalogItem, LocalProductDetail, LocalProductWorkbookPreview,
  LocalProductImportDetail, LocalProductImportRecord,
  LocalProductCapacity,
  WorkbookCategoryItem, WorkbookFieldColumns, WorkbookMappingSelection,
} from '~/types/local-products'

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8082'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

interface CanvasDto {
  canvasId: number
  projectId: string
  revision: number
  schemeToken?: string | null
  mode: 'overview' | 'design'
  designZoneId: string | null
  objects: OfficeObject[]
  files: ProjectFile[]
  blank: boolean
}

type CartDto = Omit<CartItem, 'id'> & { lineId: string }

function cartItem({ lineId, ...row }: CartDto): CartItem {
  return { id: lineId, ...row }
}

interface SpaceReviewConfirmItem {
  spaceId: string
  canvasRevision: number
  status: 'UNCONFIRMED' | 'CONFIRMED' | 'STALE'
  confirmedCanvasRevision?: number
  confirmedByUserId?: string
  confirmedAt?: string
}

interface AdminUserItem {
  userId: string
  username: string
  displayName: string
  role: string
  workspaceId?: string | null
  status: string
  lastLoginAt: string | null
  createdAt: string | null
}

interface WorkspaceItem {
  workspaceId: string
  name: string
  workspaceType: WorkspaceType
  ownerUserId: string
  ownerUsername: string
  ownerDisplayName: string
  ownerStatus: string
  dealerSlotNo?: number
  userCount: number
  projectCount: number
  createdAt: string | null
}

interface WorkspaceUserItem {
  userId: string
  username: string
  displayName: string
  role: string
  source: string | null
  status: string
  productEntryEnabled: boolean
  createdAt: string | null
}

/** 公共库（主项目全库商品）工作区级开关状态。 */
interface SharedCatalogStatus {
  /** 工作区级开关（仅经销商主号可切换，成员跟随） */
  workspaceEnabled: boolean
  /** 当前用户是否经销商主号（可切换工作区级开关） */
  dealer: boolean
  /** 综合可见性（= 工作区级开关） */
  effectiveVisible: boolean
}

interface ProductDto {
  id: string
  name: string
  desc: string
  variant: string
  price: number
  kind: Product['kind']
  uses: string[]
  widthCm: number | null
  depthCm: number | null
  imageUrl?: string
  rspuId?: string
  variantId?: string
  widthMm?: number
  depthMm?: number
  heightMm?: number
  colorPrimaryName?: string
}

function apiUrl(path: string) {
  return `${API_BASE}${path}`
}

async function request<T>(method: string, path: string, body?: unknown, headers?: Record<string, string>, timeout?: number): Promise<T> {
  const res = await $fetch<ApiResponse<T>>(apiUrl(path), {
    method: method as any,
    body: body as any,
    credentials: 'include',
    timeout,
    retry: timeout === undefined ? undefined : 0,
    headers: { 'Content-Type': 'application/json', ...headers },
  }).catch((failure) => {
    if (failure?.cause?.name === 'TimeoutError' || failure?.name === 'TimeoutError') {
      throw new Error('服务响应超时，请重试')
    }
    throw failure
  })
  if (res.code !== 200) {
    throw Object.assign(new Error(res.message || '请求失败'), { code: res.code })
  }
  return res.data
}

export function useOfficeApi() {
  const favoriteCountVersion = useState('favorite-count-version', () => 0)
  const selectedProjectId = () => useWorkspaceStore().currentFloorId || ''
  const requireProjectId = () => {
    const projectId = selectedProjectId()
    if (!projectId) throw new Error('请先创建或选择项目与楼层')
    return projectId
  }
  function schemeTokenFor(floorId: string) {
    const store = useOfficeStore()
    return store.workspaceId === floorId ? store.schemeToken : null
  }

  async function recognizeLayoutReference(image: Blob, analysisId: string, revision: number) {
    const form = new FormData()
    form.set('image', image, 'room.png')
    form.set('analysisId', analysisId)
    form.set('revision', String(revision))
    const response = await $fetch<ApiResponse<LayoutReferenceMark[]>>(apiUrl(`/api/v1/office/floors/${requireProjectId()}/layout-reference/recognize`), {
      method: 'POST', body: form, credentials: 'include',
    })
    if (response.code !== 200) throw new Error(response.message || '原图家具识别失败')
    return response.data
  }

  function layoutReferenceSource(analysisId: string, revision: number) {
    return $fetch<Blob>(apiUrl(`/api/v1/office/floors/${requireProjectId()}/layout-reference/source`), {
      query: { analysisId, revision }, credentials: 'include', responseType: 'blob',
    })
  }

  async function loadCanvas() {
    const dto = await request<CanvasDto | null>('GET', `/api/v1/office/projects/${requireProjectId()}/canvas`)
    if (!dto) return null
    return {
      objects: dto.objects || [],
      files: dto.files || [],
      mode: dto.mode || 'overview',
      designZoneId: dto.designZoneId || null,
      revision: dto.revision || 1,
      schemeToken: dto.schemeToken ?? null,
      blank: dto.blank || false,
    }
  }

  async function saveCanvas(payload: {
    objects: OfficeObject[]
    files: ProjectFile[]
    mode: 'overview' | 'design'
    designZoneId: string | null
    revision: number
    schemeToken?: string | null
    blank: boolean
  }, workspaceId?: string): Promise<CanvasDto | null> {
    // 调用方可能传入空字符串（尚未选择项目/楼层），此时回退到当前楼层；
    // 仍然为空则抛出明确提示，避免拼出 /projects//canvas 这类 404 地址。
    const targetProjectId = workspaceId || requireProjectId()
    const token = payload.schemeToken !== undefined ? payload.schemeToken : schemeTokenFor(targetProjectId)
    return await request<CanvasDto>('PUT', `/api/v1/office/projects/${encodeURIComponent(targetProjectId)}/canvas`, {
      projectId: targetProjectId,
      revision: payload.revision,
      ...(token ? { schemeToken: token } : {}),
      mode: payload.mode,
      designZoneId: payload.designZoneId,
      objects: payload.objects,
      files: payload.files,
      blank: payload.blank,
    }, undefined, 15_000)
  }

  const schemePath = (floorId: string) => `/api/v1/office/floors/${encodeURIComponent(floorId)}/room-scheme`
  const confirmationPath = (floorId: string) => `/api/v1/office/floors/${encodeURIComponent(floorId)}/proposal-confirmation`
  const bundlesPath = '/api/v1/office/product-bundles'

  function loadRoomScheme(floorId: string, zoneId: string) {
    return request<RoomSchemeState>('GET', `${schemePath(floorId)}?zoneId=${encodeURIComponent(zoneId)}`)
  }

  async function changeRoomScheme(floorId: string, action: 'activate' | 'clear-selection' | 'apply-products',
    payload: SchemeVersion & { zoneId: string; targetSlot?: SchemeSlot; automatic?: boolean; items?: BundleItem[] }): Promise<RoomSchemeResult> {
    const result = await request<{ canvas: CanvasDto; cart: CartDto[]; scheme: RoomSchemeState | null }>('POST', `${schemePath(floorId)}/${action}`, payload)
    return { ...result, canvas: { ...result.canvas, schemeToken: result.canvas.schemeToken ?? null }, cart: result.cart.map(cartItem) }
  }

  function loadRoomSchemeSlots(floorId: string, zoneId: string) {
    return request<RoomSchemeSlots>('GET', `${schemePath(floorId)}/slots?zoneId=${encodeURIComponent(zoneId)}`)
  }

  function changeRoomSchemeChoice(floorId: string, action: 'confirm-choice' | 'cancel-choice', payload: SchemeVersion & { zoneId: string; targetSlot: SchemeSlot }) {
    return request<RoomSchemeState>('POST', `${schemePath(floorId)}/${action}`, payload)
  }

  function loadProposalConfirmation(floorId: string) {
    return request<ProposalConfirmation | null>('GET', confirmationPath(floorId))
  }

  function confirmProposal(floorId: string, version: SchemeVersion) {
    return request<ProposalConfirmation>('POST', confirmationPath(floorId), { canvasRevision: version.revision, schemeToken: version.schemeToken })
  }

  function loadProductBundles() {
    return request<ProductBundle[]>('GET', bundlesPath)
  }

  function saveProductBundle(bundleId: string, payload: { name: string; revision: number; items: BundleItem[] }) {
    return request<ProductBundle>('PUT', `${bundlesPath}/${encodeURIComponent(bundleId)}`, payload)
  }

  function deleteProductBundle(bundleId: string, revision: number) {
    return request<void>('DELETE', `${bundlesPath}/${encodeURIComponent(bundleId)}?revision=${revision}`)
  }

  /** 设计复核确认（SPACE_REVIEW）：服务端基于当前画布签发凭据，baseCanvasRevision 必须等于服务端当前修订。 */
  async function confirmSpaceReview(zoneId: string, baseCanvasRevision: number, workspaceId?: string): Promise<SpaceReviewConfirmItem> {
    const targetFloorId = workspaceId || requireProjectId()
    return await request('POST', `/api/v1/office/floors/${encodeURIComponent(targetFloorId)}/spaces/${encodeURIComponent(zoneId)}/review-confirm`, { baseCanvasRevision })
  }

  /** 一键复核：服务端对当前楼层全部空间逐签确认凭据，无法确认的空间逐个返回原因。 */
  async function confirmAllSpaceReviews(baseCanvasRevision: number, workspaceId?: string): Promise<{
    canvasRevision: number
    confirmed: SpaceReviewConfirmItem[]
    alreadyConfirmed: SpaceReviewConfirmItem[]
    failed: Array<{ spaceId: string; spaceName: string; reason: string }>
  }> {
    const targetFloorId = workspaceId || requireProjectId()
    return await request('POST', `/api/v1/office/floors/${encodeURIComponent(targetFloorId)}/spaces/review-confirm-all`, { baseCanvasRevision })
  }

  async function loadCart(): Promise<CartItem[]> {
    const list = await request<CartDto[]>('GET', `/api/v1/office/projects/${requireProjectId()}/cart`)
    return list.map(cartItem)
  }

  function cartHeaders(floorId: string, schemeToken?: string | null): Record<string, string> {
    const token = schemeToken === undefined ? schemeTokenFor(floorId) : schemeToken
    return token ? { 'X-Office-Scheme': token } : {}
  }

  async function addCartItem(item: CartItem, workspaceId?: string, schemeToken?: string | null): Promise<CartItem> {
    const targetFloorId = workspaceId || requireProjectId()
    const c = await request<CartDto>('POST', `/api/v1/office/projects/${encodeURIComponent(targetFloorId)}/cart`, {
      lineId: item.id,
      projectId: item.projectId,
      zoneId: item.zoneId,
      zoneName: item.zoneName,
      productId: item.productId,
      qty: item.qty,
    }, cartHeaders(targetFloorId, schemeToken))
    return cartItem(c)
  }

  async function updateCartItemQty(lineId: string, qty: number, workspaceId?: string, schemeToken?: string | null): Promise<CartItem | null> {
    const targetFloorId = workspaceId || requireProjectId()
    const c = await request<CartDto | null>('PUT', `/api/v1/office/projects/${encodeURIComponent(targetFloorId)}/cart/${encodeURIComponent(lineId)}?qty=${qty}`, {}, cartHeaders(targetFloorId, schemeToken))
    return c ? cartItem(c) : null
  }

  async function deleteCartItem(lineId: string, workspaceId?: string, schemeToken?: string | null): Promise<void> {
    const targetFloorId = workspaceId || requireProjectId()
    await request<void>('DELETE', `/api/v1/office/projects/${encodeURIComponent(targetFloorId)}/cart/${encodeURIComponent(lineId)}`, undefined, cartHeaders(targetFloorId, schemeToken))
  }

  async function clearCart(workspaceId?: string): Promise<void> {
    const targetFloorId = workspaceId || requireProjectId()
    await request<void>('DELETE', `/api/v1/office/projects/${encodeURIComponent(targetFloorId)}/cart`, undefined, cartHeaders(targetFloorId))
  }

  async function loadProducts(): Promise<Product[]> {
    const list = await request<ProductDto[]>('GET', '/api/v1/office/products')
    return list.filter(p => p.widthCm != null && p.widthCm > 0 && p.depthCm != null && p.depthCm > 0
      && p.price > 0).map((p) => ({
      id: p.id, rspuId: p.rspuId, variantId: p.variantId, widthMm: p.widthMm, depthMm: p.depthMm, heightMm: p.heightMm,
      colorPrimaryName: p.colorPrimaryName,
      name: p.name,
      desc: p.desc || '',
      variant: p.variant || '',
      price: p.price || 0,
      kind: p.kind,
      uses: p.uses || [],
      widthCm: p.widthCm!,
      depthCm: p.depthCm!,
      imageUrl: p.imageUrl,
    }))
  }

  async function recommendModule(requestBody: ModuleRecommendationRequest): Promise<ModuleRecommendation> {
    return await request('POST', `/api/v1/office/projects/${requireProjectId()}/module-recommendations`, requestBody, undefined, 15_000)
  }

  async function selectModuleProduct(requestBody: ModuleRecommendationRequest): Promise<ModuleProductSelection> {
    const floorId = requireProjectId()
    const schemeToken = schemeTokenFor(floorId)
    return await request('POST', `/api/v1/office/projects/${encodeURIComponent(floorId)}/module-products`, {
      ...requestBody, ...(schemeToken ? { schemeToken } : {}),
    })
  }

  async function exportProposal(): Promise<Blob> {
    const res = await $fetch<Blob>(apiUrl(`/api/v1/office/projects/${requireProjectId()}/proposal/export`), {
      method: 'POST',
      credentials: 'include',
      responseType: 'blob',
    })
    if (res.type.includes('application/json')) {
      const result = JSON.parse(await res.text()) as ApiResponse<unknown>
      throw new Error(result.message || '方案导出失败')
    }
    return res
  }

  async function resetProject(): Promise<void> {
    await request<void>('POST', `/api/v1/office/projects/${requireProjectId()}/reset`, {})
  }

  /** 项目报价试算：仅经销商主号，只读预览，服务端强制校验身份。 */
  async function previewProjectQuotation(projectId: string, mode: QuotationPricingMode, value: number): Promise<ProjectQuotationPreview> {
    const query = new URLSearchParams({ mode, value: String(value) })
    return await request<ProjectQuotationPreview>('GET', `/api/v1/office/projects/${projectId}/quotation/preview?${query.toString()}`)
  }

  /** 确认报价：落库持久化，服务端按提交明细重算金额。 */
  async function confirmProjectQuotation(projectId: string, payload: ConfirmQuotationRequest): Promise<ProjectQuotationRecord> {
    return await request<ProjectQuotationRecord>('POST', `/api/v1/office/projects/${projectId}/quotation/confirm`, payload)
  }

  /** 导出当前编辑稿；业务错误可能以 JSON 返回，不能误保存为 Excel。 */
  async function exportProjectQuotation(projectId: string, payload: ConfirmQuotationRequest): Promise<Blob> {
    const blob = await $fetch<Blob>(apiUrl(`/api/v1/office/projects/${projectId}/quotation/export`), {
      method: 'POST', body: payload, credentials: 'include', responseType: 'blob',
    })
    if (blob.type.includes('application/json')) {
      const result = JSON.parse(await blob.text()) as ApiResponse<unknown>
      throw new Error(result.message || '报价导出失败')
    }
    return blob
  }

  async function exportLatestProjectQuotation(projectId: string): Promise<Blob> {
    const blob = await $fetch<Blob>(apiUrl(`/api/v1/office/projects/${encodeURIComponent(projectId)}/quotation/latest/export`), {
      credentials: 'include', responseType: 'blob',
    })
    if (blob.type.includes('application/json')) {
      const result = JSON.parse(await blob.text()) as ApiResponse<unknown>
      throw new Error(result.message || '报价导出失败')
    }
    return blob
  }

  /** 最近一次确认的报价单；没有确认记录时返回 null。 */
  async function latestProjectQuotation(projectId: string): Promise<ProjectQuotationRecord | null> {
    return await request<ProjectQuotationRecord | null>('GET', `/api/v1/office/projects/${projectId}/quotation/latest`)
  }

  /** 最近一条报价记录（任意状态）的状态与时间；没有报价记录时返回 null。 */
  async function latestProjectQuotationRecord(projectId: string): Promise<ProjectQuotationLatest | null> {
    return await request<ProjectQuotationLatest | null>('GET', `/api/v1/office/projects/${projectId}/quotation/latest-record`)
  }

  async function catalogImportHistory<T>(): Promise<T> {
    return await request<T>('GET', '/api/v1/products/import-batches')
  }

  async function catalogHistoricalStatus<T>(batchId: string): Promise<T> {
    return await request<T>('GET', `/api/v1/products/import-batches/${encodeURIComponent(batchId)}/status`)
  }

  async function loadImportedProducts(managed = false): Promise<ImportedProduct[]> {
    return await request<ImportedProduct[]>('GET', managed ? '/api/v1/products?managed=true' : '/api/v1/products')
  }

  async function loadImportedProductPage(options: { page: number; size: number; search: string; status: string; managed: boolean; filters?: CatalogProductFilters; refresh?: boolean }): Promise<CatalogProductPage> {
    const query = new URLSearchParams({ page: String(options.page), size: String(options.size) })
    if (options.search) query.set('search', options.search)
    if (options.status) query.set('status', options.status)
    if (options.managed) query.set('managed', 'true')
    if (options.refresh) query.set('refresh', 'true')
    if (options.filters) {
      for (const key of FILTER_KEYS) {
        for (const value of options.filters[key]) query.append(key, String(value))
      }
    }
    return await request<CatalogProductPage>('GET', `/api/v1/products/page?${query}`)
  }

  async function loadCatalogCapabilities(): Promise<CatalogCapabilities> {
    return await request('GET', '/api/v1/products/capabilities')
  }

  function browseProductCatalog(options: { page: number, size: number, search: string, status: string, category: string, color: string, filters?: CatalogProductFilters }) {
    const { page, size, filters, ...quickFilters } = options
    const query = new URLSearchParams({ page: String(page), size: String(size) })
    for (const [name, value] of Object.entries(quickFilters)) {
      if (value) query.set(name, value)
    }
    if (filters) for (const key of FILTER_KEYS) for (const value of filters[key]) query.append(key, String(value))
    return request<CatalogBrowsePage>('GET', `/api/v1/products/browse?${query}`)
  }

  function browseProductFacets(options?: { search?: string, status?: string }) {
    const query = new URLSearchParams()
    if (options?.search) query.set('search', options.search)
    if (options?.status) query.set('status', options.status)
    return request<CatalogBrowseFacets>('GET', `/api/v1/products/browse/facets?${query}`)
  }

  async function loadFavoriteFolders(rspuId?: string): Promise<FavoriteFolder[]> {
    const query = rspuId ? `?rspuId=${encodeURIComponent(rspuId)}` : ''
    return await request<FavoriteFolder[]>('GET', `/api/v1/office/favorites/folders${query}`)
  }

  async function createFavoriteFolder(name: string): Promise<FavoriteFolder> {
    return await request<FavoriteFolder>('POST', '/api/v1/office/favorites/folders', { name })
  }

  async function saveProductFavoriteFolders(rspuId: string, folderIds: string[]): Promise<void> {
    await request<void>('PUT', `/api/v1/office/favorites/products/${encodeURIComponent(rspuId)}`, { folderIds })
    favoriteCountVersion.value++
  }

  async function deleteFavoriteFolder(folderId: string): Promise<void> {
    await request<void>('DELETE', `/api/v1/office/favorites/folders/${encodeURIComponent(folderId)}`)
    favoriteCountVersion.value++
  }

  async function loadFavoriteProductIds(): Promise<string[]> {
    return await request<string[]>('GET', '/api/v1/office/favorites/product-ids')
  }

  async function loadFavoriteItems(folderId?: string): Promise<ImportedProduct[]> {
    const query = folderId ? `?folderId=${encodeURIComponent(folderId)}` : ''
    return await request<ImportedProduct[]>('GET', `/api/v1/office/favorites/items${query}`)
  }

  async function loadProjectFavoriteItems(projectId: string): Promise<ImportedProduct[]> {
    return await request<ImportedProduct[]>('GET', `/api/v1/office/projects/${encodeURIComponent(projectId)}/favorites/items`)
  }

  async function loadProjectFavoriteProductIds(projectId: string): Promise<string[]> {
    return await request<string[]>('GET', `/api/v1/office/projects/${encodeURIComponent(projectId)}/favorites/product-ids`)
  }

  async function loadProjectFavorite(projectId: string, rspuId: string): Promise<ProjectFavoriteState> {
    return await request<ProjectFavoriteState>('GET', `/api/v1/office/projects/${encodeURIComponent(projectId)}/favorites/${encodeURIComponent(rspuId)}`)
  }

  async function saveProjectFavorite(projectId: string, rspuId: string, selected: boolean): Promise<ProjectFavoriteState> {
    return await request<ProjectFavoriteState>('PUT', `/api/v1/office/projects/${encodeURIComponent(projectId)}/favorites/${encodeURIComponent(rspuId)}`, { selected })
  }

  async function parseCad(file: File): Promise<unknown> {
    const formData = new FormData()
    formData.append('file', file)
    const res = await $fetch<ApiResponse<unknown>>(apiUrl('/api/v1/cad/parse'), {
      method: 'POST',
      body: formData,
      credentials: 'include',
    })
    if (res.code !== 200) {
      throw new Error(res.message || 'CAD 解析失败')
    }
    return res.data
  }

  async function uploadImages(files: File[]): Promise<string[]> {
    const formData = new FormData()
    files.forEach(f => formData.append('files', f))
    const res = await $fetch<ApiResponse<string[]>>(apiUrl('/api/v1/images/upload'), {
      method: 'POST',
      body: formData,
      credentials: 'include',
    })
    if (res.code !== 200) {
      throw new Error(res.message || '图片上传失败')
    }
    return res.data
  }

  /** 系统管理员用户管理；服务端逐接口校验 ADMIN，前端入口只是展示层。 */
  async function listAdminUsers(): Promise<AdminUserItem[]> {
    return await request<AdminUserItem[]>('GET', '/api/v1/office/admin/users')
  }

  async function updateAdminUserStatus(userId: string, status: 'active' | 'disabled'): Promise<AdminUserItem> {
    return await request<AdminUserItem>('PATCH', `/api/v1/office/admin/users/${userId}/status`, { status })
  }

  async function resetAdminUserPassword(userId: string, password: string): Promise<AdminUserItem> {
    return await request<AdminUserItem>('POST', `/api/v1/office/admin/users/${userId}/password`, { password })
  }

  /** 删除账号；仍是项目负责人的账号会被服务端拒绝。 */
  async function deleteAdminUser(userId: string): Promise<void> {
    await request<void>('DELETE', `/api/v1/office/admin/users/${userId}`)
  }

  /** 系统管理员的工作区管理；服务端逐接口校验 ADMIN。 */
  async function listAdminWorkspaces(): Promise<WorkspaceItem[]> {
    return await request<WorkspaceItem[]>('GET', '/api/v1/office/admin/workspaces')
  }

  async function createAdminWorkspace(payload: { name: string; workspaceType: WorkspaceItem['workspaceType']; ownerUsername: string; ownerDisplayName: string; ownerPassword: string }): Promise<WorkspaceItem> {
    return await request<WorkspaceItem>('POST', '/api/v1/office/admin/workspaces', payload)
  }

  async function updateAdminWorkspaceType(workspaceId: string, workspaceType: WorkspaceItem['workspaceType']): Promise<WorkspaceItem> {
    return await request<WorkspaceItem>('PATCH', `/api/v1/office/admin/workspaces/${encodeURIComponent(workspaceId)}/type`, { workspaceType })
  }

  /** 经销商主号的工作区账号管理；只能操作自己拥有的工作区。 */
  async function getMyWorkspace(): Promise<WorkspaceItem> {
    return await request<WorkspaceItem>('GET', '/api/v1/office/workspace/me')
  }

  async function listWorkspaceUsers(): Promise<WorkspaceUserItem[]> {
    return await request<WorkspaceUserItem[]>('GET', '/api/v1/office/workspace/users')
  }

  function setWorkspaceProductEntry(userId: string, enabled: boolean) {
    return request<void>('PUT', `/api/v1/office/workspace/users/${encodeURIComponent(userId)}/product-entry`, { enabled })
  }

  const localProductsPath = '/api/v1/office/local-products'
  function listLocalProducts(page: number, search = '') {
    const query = new URLSearchParams({ page: String(page) })
    if (search) query.set('search', search)
    return request<{ canWrite: boolean, products: LocalProductCatalogItem[], hasMore: boolean }>('GET', `${localProductsPath}?${query}`)
  }
  const getLocalProduct = (id: string) => request<LocalProductDetail>('GET', `${localProductsPath}/${encodeURIComponent(id)}`)
  const getLocalProductCapacity = () => request<LocalProductCapacity>('GET', `${localProductsPath}/capacity`)
  function listLocalProductImports(page: number, search = '') {
    const query = new URLSearchParams({ page: String(page) })
    if (search) query.set('search', search)
    return request<{ canWrite: boolean, hasMore: boolean, records: LocalProductImportRecord[] }>(
      'GET', `${localProductsPath}/imports?${query}`)
  }
  const getLocalProductImport = (id: string) =>
    request<LocalProductImportDetail>('GET', `${localProductsPath}/imports/${encodeURIComponent(id)}`)
  const saveLocalProducts = (batchId: string, products: LocalProductInput[], previewId?: string | null) => request<{ id: string, name: string, revision: number }[]>(
    'POST', localProductsPath, { batchId, products, previewId })
  const localProductImagePath = (id: string) => `${localProductsPath}/images/${encodeURIComponent(id)}`
  const workbookPreviewImageUrl = (id: string, key: string) => apiUrl(`${localProductsPath}/excel-preview/${encodeURIComponent(id)}/images/${encodeURIComponent(key)}`)
  const localProductTemplateUrl = () => apiUrl(`${localProductsPath}/excel-template`)
  const listLocalProductCategories = () => request<{ dictCode: string, dictName: string }[]>('GET', '/api/v1/dicts/category')

  const suggestWorkbookFields = (headers: Record<number, string>) => request<WorkbookFieldColumns>(
    'POST', `${localProductsPath}/excel-field-suggestions`, { headers })
  const suggestWorkbookCategories = (candidates: string[], products: WorkbookCategoryItem[]) => request<Record<number, string>>(
    'POST', `${localProductsPath}/excel-category-suggestions`, { candidates, products })
  const suggestWorkbookMaterials = (descriptions: string[]) => request<Record<number, string>>(
    'POST', `${localProductsPath}/excel-material-suggestions`, { descriptions })

  async function previewLocalProductWorkbook(file: File, mapping?: WorkbookMappingSelection) {
    const form = new FormData()
    form.set('file', file)
    form.set('imageReferences', 'true')
    if (mapping) form.set('mapping', new Blob([JSON.stringify(mapping)], { type: 'application/json' }))
    const response = await $fetch<ApiResponse<LocalProductWorkbookPreview>>(
      apiUrl(`${localProductsPath}/excel-preview`), { method: 'POST', body: form, credentials: 'include' })
    if (response.code !== 200) throw new Error(response.message || 'Excel 预览失败')
    return response.data
  }

  async function createWorkspaceUser(payload: { username: string; displayName: string; password: string; source?: string }): Promise<WorkspaceUserItem> {
    return await request<WorkspaceUserItem>('POST', '/api/v1/office/workspace/users', payload)
  }

  /** 公共库（主项目全库商品）工作区级开关状态：仅主号可切换，成员跟随。 */
  async function getSharedCatalogStatus(): Promise<SharedCatalogStatus> {
    return await request<SharedCatalogStatus>('GET', '/api/v1/office/workspace/shared-catalog')
  }

  /** 工作区级公共库开关（仅经销商主号）。 */
  async function setWorkspaceSharedCatalog(enabled: boolean): Promise<SharedCatalogStatus> {
    return await request<SharedCatalogStatus>('PUT', '/api/v1/office/workspace/shared-catalog', { enabled })
  }

  async function getProduct(rspuId: string): Promise<ProductDetail> {
    return await request<ProductDetail>('GET', `/api/v1/products/${rspuId}`)
  }

  return {
    recognizeLayoutReference,
    layoutReferenceSource,
    get projectId() { return selectedProjectId() },
    loadCanvas,
    saveCanvas,
    loadRoomScheme,
    changeRoomScheme,
    loadRoomSchemeSlots,
    changeRoomSchemeChoice,
    loadProposalConfirmation,
    confirmProposal,
    loadProductBundles,
    saveProductBundle,
    deleteProductBundle,
    confirmSpaceReview,
    confirmAllSpaceReviews,
    loadCart,
    addCartItem,
    updateCartItemQty,
    deleteCartItem,
    clearCart,
    loadProducts,
    recommendModule,
    selectModuleProduct,
    exportProposal,
      previewProjectQuotation,
    confirmProjectQuotation,
    exportProjectQuotation,
    exportLatestProjectQuotation,
    latestProjectQuotation,
    latestProjectQuotationRecord,
    resetProject,
    loadImportedProducts,
    loadImportedProductPage,
    browseProductCatalog,
    browseProductFacets,
    loadCatalogCapabilities,
    loadFavoriteFolders,
    createFavoriteFolder,
    saveProductFavoriteFolders,
    deleteFavoriteFolder,
    loadFavoriteProductIds,
    loadFavoriteItems,
    loadProjectFavoriteProductIds,
    loadProjectFavoriteItems,
    loadProjectFavorite,
    saveProjectFavorite,
    catalogImportHistory,
    catalogHistoricalStatus,
    parseCad,
    uploadImages,
    listAdminUsers,
    updateAdminUserStatus,
    resetAdminUserPassword,
    deleteAdminUser,
    listAdminWorkspaces,
    createAdminWorkspace,
    updateAdminWorkspaceType,
    getMyWorkspace,
    listWorkspaceUsers,
    setWorkspaceProductEntry,
    listLocalProducts,
    getLocalProductCapacity,
    listLocalProductImports,
    getLocalProductImport,
    getLocalProduct,
    saveLocalProducts,
    localProductImagePath,
    workbookPreviewImageUrl,
    localProductTemplateUrl,
    listLocalProductCategories,
    previewLocalProductWorkbook,
    suggestWorkbookFields,
    suggestWorkbookCategories,
    suggestWorkbookMaterials,
    createWorkspaceUser,
    getSharedCatalogStatus,
    setWorkspaceSharedCatalog,
    getProduct,
  }
}
