import type { ModuleRecommendation, ModuleRecommendationRequest, ModuleProductSelection } from '~/types/moduleRecommendation'
import type { LayoutReferenceMark } from '~/types/layoutPlanning'
import type { OfficeObject, AgentMessage, CartItem, Product, ProjectFile, DesignBrief, ProjectQuotationPreview, QuotationPricingMode, ProjectQuotationRecord, ConfirmQuotationRequest } from '~/types/office'
import type { CatalogCapabilities, ImportedProduct, ProductDetail } from '~/types/imported-products'
import type { WorkspaceType } from '~/utils/workspaceType'

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
  mode: 'overview' | 'design'
  designZoneId: string | null
  objects: OfficeObject[]
  files: ProjectFile[]
  blank: boolean
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
}

function apiUrl(path: string) {
  return `${API_BASE}${path}`
}

async function request<T>(method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<T> {
  const res = await $fetch<ApiResponse<T>>(apiUrl(path), {
    method: method as any,
    body: body as any,
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...headers },
  })
  if (res.code !== 200) {
    throw new Error(res.message || '请求失败')
  }
  return res.data
}

export function useOfficeApi() {
  const selectedProjectId = () => useWorkspaceStore().currentFloorId || ''
  const requireProjectId = () => {
    const projectId = selectedProjectId()
    if (!projectId) throw new Error('请先创建或选择项目与楼层')
    return projectId
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

  async function loadCanvas(): Promise<{ objects: OfficeObject[]; files: ProjectFile[]; mode: 'overview' | 'design'; designZoneId: string | null; revision: number; blank: boolean } | null> {
    const dto = await request<CanvasDto | null>('GET', `/api/v1/office/projects/${requireProjectId()}/canvas`)
    if (!dto) return null
    return {
      objects: dto.objects || [],
      files: dto.files || [],
      mode: dto.mode || 'overview',
      designZoneId: dto.designZoneId || null,
      revision: dto.revision || 1,
      blank: dto.blank || false,
    }
  }

  async function saveCanvas(payload: {
    objects: OfficeObject[]
    files: ProjectFile[]
    mode: 'overview' | 'design'
    designZoneId: string | null
    revision: number
    blank: boolean
  }, workspaceId?: string): Promise<CanvasDto | null> {
    // 调用方可能传入空字符串（尚未选择项目/楼层），此时回退到当前楼层；
    // 仍然为空则抛出明确提示，避免拼出 /projects//canvas 这类 404 地址。
    const targetProjectId = workspaceId || requireProjectId()
    return await request<CanvasDto>('PUT', `/api/v1/office/projects/${encodeURIComponent(targetProjectId)}/canvas`, {
      projectId: targetProjectId,
      revision: payload.revision,
      mode: payload.mode,
      designZoneId: payload.designZoneId,
      objects: payload.objects,
      files: payload.files,
      blank: payload.blank,
    })
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
    const list = await request<Array<{ lineId: string; projectId: string; zoneId: string | null; zoneName: string; productId: string; qty: number }>>('GET', `/api/v1/office/projects/${requireProjectId()}/cart`)
    return list.map(c => ({ id: c.lineId, projectId: c.projectId, zoneId: c.zoneId, zoneName: c.zoneName, productId: c.productId, qty: c.qty }))
  }

  async function addCartItem(item: CartItem, workspaceId?: string): Promise<CartItem> {
    const targetFloorId = workspaceId || requireProjectId()
    const c = await request<{ lineId: string; projectId: string; zoneId: string | null; zoneName: string; productId: string; qty: number }>('POST', `/api/v1/office/projects/${encodeURIComponent(targetFloorId)}/cart`, {
      lineId: item.id,
      projectId: item.projectId,
      zoneId: item.zoneId,
      zoneName: item.zoneName,
      productId: item.productId,
      qty: item.qty,
    })
    return { id: c.lineId, projectId: c.projectId, zoneId: c.zoneId, zoneName: c.zoneName, productId: c.productId, qty: c.qty }
  }

  async function updateCartItemQty(lineId: string, qty: number, workspaceId?: string): Promise<CartItem | null> {
    const targetFloorId = workspaceId || requireProjectId()
    const c = await request<{ lineId: string; projectId: string; zoneId: string | null; zoneName: string; productId: string; qty: number } | null>('PUT', `/api/v1/office/projects/${encodeURIComponent(targetFloorId)}/cart/${lineId}?qty=${qty}`, {})
    if (!c) return null
    return { id: c.lineId, projectId: c.projectId, zoneId: c.zoneId, zoneName: c.zoneName, productId: c.productId, qty: c.qty }
  }

  async function deleteCartItem(lineId: string, workspaceId?: string): Promise<void> {
    const targetFloorId = workspaceId || requireProjectId()
    await request<void>('DELETE', `/api/v1/office/projects/${encodeURIComponent(targetFloorId)}/cart/${lineId}`)
  }

  async function clearCart(workspaceId?: string): Promise<void> {
    const targetFloorId = workspaceId || requireProjectId()
    await request<void>('DELETE', `/api/v1/office/projects/${encodeURIComponent(targetFloorId)}/cart`)
  }

  async function loadMessages(): Promise<AgentMessage[]> {
    return await request<AgentMessage[]>('GET', `/api/v1/office/projects/${requireProjectId()}/agent-messages`)
  }

  async function sendMessage(content: string): Promise<AgentMessage> {
    return await request<AgentMessage>('POST', `/api/v1/office/projects/${requireProjectId()}/agent-messages`, { role: 'user', content })
  }

  async function loadProducts(): Promise<Product[]> {
    const list = await request<ProductDto[]>('GET', '/api/v1/office/products')
    return list.filter(p => p.widthCm != null && p.widthCm > 0 && p.depthCm != null && p.depthCm > 0
      && p.price > 0).map((p) => ({
      id: p.id, rspuId: p.rspuId, variantId: p.variantId, widthMm: p.widthMm, depthMm: p.depthMm, heightMm: p.heightMm,
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
    return await request('POST', `/api/v1/office/projects/${requireProjectId()}/module-recommendations`, requestBody)
  }

  async function selectModuleProduct(requestBody: ModuleRecommendationRequest): Promise<ModuleProductSelection> {
    return await request('POST', `/api/v1/office/projects/${requireProjectId()}/module-products`, requestBody)
  }

  async function checkProposalCatalog(): Promise<void> {
    return await request<void>('POST', '/api/v1/office/projects/' + encodeURIComponent(requireProjectId()) + '/proposal/catalog-check')
  }

  async function exportProposal(): Promise<Blob> {
    const res = await $fetch<Blob>(apiUrl(`/api/v1/office/projects/${requireProjectId()}/proposal/export`), {
      method: 'POST',
      credentials: 'include',
    })
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

  /** 最近一次确认的报价单；没有确认记录时返回 null。 */
  async function latestProjectQuotation(projectId: string): Promise<ProjectQuotationRecord | null> {
    return await request<ProjectQuotationRecord | null>('GET', `/api/v1/office/projects/${projectId}/quotation/latest`)
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

  async function loadCatalogCapabilities(): Promise<CatalogCapabilities> {
    return await request('GET', '/api/v1/products/capabilities')
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

  async function createAdminUser(payload: { username: string; displayName: string; password: string; role?: string }): Promise<AdminUserItem> {
    return await request<AdminUserItem>('POST', '/api/v1/office/admin/users', payload)
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
    confirmSpaceReview,
    confirmAllSpaceReviews,
    loadCart,
    addCartItem,
    updateCartItemQty,
    deleteCartItem,
    clearCart,
    loadMessages,
    sendMessage,
    loadProducts,
    recommendModule,
    selectModuleProduct,
    exportProposal,
    checkProposalCatalog,
    previewProjectQuotation,
    confirmProjectQuotation,
    exportProjectQuotation,
    latestProjectQuotation,
    resetProject,
    loadImportedProducts,
    loadCatalogCapabilities,
    catalogImportHistory,
    catalogHistoricalStatus,
    parseCad,
    uploadImages,
    listAdminUsers,
    createAdminUser,
    updateAdminUserStatus,
    resetAdminUserPassword,
    deleteAdminUser,
    listAdminWorkspaces,
    createAdminWorkspace,
    updateAdminWorkspaceType,
    getMyWorkspace,
    listWorkspaceUsers,
    createWorkspaceUser,
    getSharedCatalogStatus,
    setWorkspaceSharedCatalog,
    getProduct,
  }
}
