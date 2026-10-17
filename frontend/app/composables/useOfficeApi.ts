import type { ModuleRecommendation, ModuleRecommendationRequest, ModuleProductSelection } from '~/types/moduleRecommendation'
import type { OfficeObject, AgentMessage, CartItem, Product, ProjectFile, DesignBrief } from '~/types/office'
import type { ImportedProduct, ProductImportResult, ProductImportPreview, ProductImportPreviewRow, DictItem, ManualProductEntryRequest, ProductDetail } from '~/types/imported-products'

const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8082'
const PROJECT_ID = 'OFFICE-PROJECT-001'

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

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await $fetch<ApiResponse<T>>(apiUrl(path), {
    method: method as any,
    body: body as any,
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
  })
  if (res.code !== 200) {
    throw new Error(res.message || '请求失败')
  }
  return res.data
}

export function useOfficeApi() {
  const projectId = PROJECT_ID

  async function loadCanvas(): Promise<{ objects: OfficeObject[]; files: ProjectFile[]; mode: 'overview' | 'design'; designZoneId: string | null; revision: number; blank: boolean } | null> {
    const dto = await request<CanvasDto | null>('GET', `/api/v1/office/projects/${projectId}/canvas`)
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
  }): Promise<void> {
    await request<void>('PUT', `/api/v1/office/projects/${projectId}/canvas`, {
      projectId,
      revision: payload.revision,
      mode: payload.mode,
      designZoneId: payload.designZoneId,
      objects: payload.objects,
      files: payload.files,
      blank: payload.blank,
    })
  }

  async function loadCart(): Promise<CartItem[]> {
    const list = await request<Array<{ lineId: string; projectId: string; zoneId: string | null; zoneName: string; productId: string; qty: number }>>('GET', `/api/v1/office/projects/${projectId}/cart`)
    return list.map(c => ({ id: c.lineId, projectId: c.projectId, zoneId: c.zoneId, zoneName: c.zoneName, productId: c.productId, qty: c.qty }))
  }

  async function addCartItem(item: CartItem): Promise<CartItem> {
    const c = await request<{ lineId: string; projectId: string; zoneId: string | null; zoneName: string; productId: string; qty: number }>('POST', `/api/v1/office/projects/${projectId}/cart`, {
      lineId: item.id,
      projectId: item.projectId,
      zoneId: item.zoneId,
      zoneName: item.zoneName,
      productId: item.productId,
      qty: item.qty,
    })
    return { id: c.lineId, projectId: c.projectId, zoneId: c.zoneId, zoneName: c.zoneName, productId: c.productId, qty: c.qty }
  }

  async function updateCartItemQty(lineId: string, qty: number): Promise<CartItem | null> {
    const c = await request<{ lineId: string; projectId: string; zoneId: string | null; zoneName: string; productId: string; qty: number } | null>('PUT', `/api/v1/office/projects/${projectId}/cart/${lineId}?qty=${qty}`, {})
    if (!c) return null
    return { id: c.lineId, projectId: c.projectId, zoneId: c.zoneId, zoneName: c.zoneName, productId: c.productId, qty: c.qty }
  }

  async function deleteCartItem(lineId: string): Promise<void> {
    await request<void>('DELETE', `/api/v1/office/projects/${projectId}/cart/${lineId}`)
  }

  async function clearCart(): Promise<void> {
    await request<void>('DELETE', `/api/v1/office/projects/${projectId}/cart`)
  }

  async function loadMessages(): Promise<AgentMessage[]> {
    return await request<AgentMessage[]>('GET', `/api/v1/office/projects/${projectId}/agent-messages`)
  }

  async function sendMessage(content: string): Promise<AgentMessage> {
    return await request<AgentMessage>('POST', `/api/v1/office/projects/${projectId}/agent-messages`, { role: 'user', content })
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
    return await request('POST', `/api/v1/office/projects/${projectId}/module-recommendations`, requestBody)
  }

  async function selectModuleProduct(requestBody: ModuleRecommendationRequest): Promise<ModuleProductSelection> {
    return await request('POST', `/api/v1/office/projects/${projectId}/module-products`, requestBody)
  }

  async function exportProposal(): Promise<Blob> {
    const res = await $fetch<Blob>(apiUrl(`/api/v1/office/projects/${projectId}/proposal/export`), {
      method: 'POST',
      credentials: 'include',
    })
    return res
  }

  async function resetProject(): Promise<void> {
    await request<void>('POST', `/api/v1/office/projects/${projectId}/reset`, {})
  }

  async function downloadImportTemplate(): Promise<Blob> {
    return await $fetch<Blob>(apiUrl('/api/v1/products/import-template'), {
      method: 'GET',
      credentials: 'include',
    })
  }

  async function importProducts(file: File, updateIfExists = false): Promise<ProductImportResult> {
    const formData = new FormData()
    formData.append('file', file)
    const res = await $fetch<ApiResponse<ProductImportResult>>(apiUrl(`/api/v1/products/import?updateIfExists=${updateIfExists}`), {
      method: 'POST',
      body: formData,
      credentials: 'include',
    })
    if (res.code !== 200) {
      throw new Error(res.message || '导入失败')
    }
    return res.data
  }

  async function loadImportedProducts(): Promise<ImportedProduct[]> {
    return await request<ImportedProduct[]>('GET', '/api/v1/products')
  }

  async function loadPendingProducts(): Promise<ImportedProduct[]> {
    return await request<ImportedProduct[]>('GET', '/api/v1/products/audit/pending')
  }

  async function approveProduct(rspuId: string): Promise<ImportedProduct> {
    return await request<ImportedProduct>('POST', `/api/v1/products/${rspuId}/approve`, {})
  }

  async function rejectProduct(rspuId: string, remark?: string): Promise<ImportedProduct> {
    return await request<ImportedProduct>('POST', `/api/v1/products/${rspuId}/reject`, { remark })
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

  async function previewImport(file: File): Promise<ProductImportPreview> {
    const formData = new FormData()
    formData.append('file', file)
    const res = await $fetch<ApiResponse<ProductImportPreview>>(apiUrl('/api/v1/products/import-preview'), {
      method: 'POST',
      body: formData,
      credentials: 'include',
    })
    if (res.code !== 200) {
      throw new Error(res.message || '预览失败')
    }
    return res.data
  }

  async function validateImportPreview(rows: ProductImportPreviewRow[], updateIfExists = false): Promise<ProductImportPreview> {
    const requestRows = rows.map(({ imagePreviews: _imagePreviews, ...row }) => row)
    const res = await $fetch<ApiResponse<ProductImportPreview>>(apiUrl('/api/v1/products/import-preview/validate'), {
      method: 'POST',
      body: { rows: requestRows, updateIfExists },
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
    })
    if (res.code !== 200) {
      throw new Error(res.message || '校验失败')
    }
    return res.data
  }

  async function confirmImport(rows: ProductImportPreviewRow[], updateIfExists = false, file?: File): Promise<ProductImportResult> {
    const requestRows = rows.map(({ imagePreviews: _imagePreviews, ...row }) => row)
    const formData = new FormData()
    if (file) {
      formData.append('file', file)
      formData.append('request', new Blob([JSON.stringify({ rows: requestRows, updateIfExists })], { type: 'application/json' }))
    }
    const res = await $fetch<ApiResponse<ProductImportResult>>(apiUrl('/api/v1/products/import-confirm'), {
      method: 'POST',
      body: file ? formData : { rows: requestRows, updateIfExists },
      credentials: 'include',
      ...(!file ? { headers: { 'Content-Type': 'application/json' } } : {}),
    })
    if (res.code !== 200) {
      throw new Error(res.message || '导入确认失败')
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

  async function loadDicts(type: string): Promise<DictItem[]> {
    return await request<DictItem[]>('GET', `/api/v1/dicts/${type}`)
  }

  async function createManualProduct(request: ManualProductEntryRequest): Promise<{ rspuId: string; message: string }> {
    return await request<{ rspuId: string; message: string }>('POST', '/api/v1/products/manual', request)
  }

  async function getProduct(rspuId: string): Promise<ProductDetail> {
    return await request<ProductDetail>('GET', `/api/v1/products/${rspuId}`)
  }

  async function updateProduct(rspuId: string, request: ManualProductEntryRequest): Promise<{ rspuId: string; message: string }> {
    return await request<{ rspuId: string; message: string }>('PUT', `/api/v1/products/${rspuId}`, request)
  }

  async function deleteProduct(rspuId: string): Promise<void> {
    await request<void>('DELETE', `/api/v1/products/${rspuId}`)
  }

  async function deleteProducts(rspuIds: string[]): Promise<number> {
    return await request<number>('DELETE', '/api/v1/products/batch', rspuIds)
  }

  async function restoreProduct(rspuId: string): Promise<void> {
    await request<void>('POST', `/api/v1/products/${rspuId}/restore`, {})
  }

  async function recognizeImage(file: File): Promise<unknown> {
    const formData = new FormData()
    formData.append('file', file)
    const res = await $fetch<ApiResponse<unknown>>(apiUrl('/api/v1/vision/recognize'), {
      method: 'POST',
      body: formData,
      credentials: 'include',
    })
    if (res.code !== 200) {
      throw new Error(res.message || '图纸识别失败')
    }
    return res.data
  }

  return {
    projectId,
    loadCanvas,
    saveCanvas,
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
    resetProject,
    downloadImportTemplate,
    importProducts,
    loadImportedProducts,
    loadPendingProducts,
    approveProduct,
    rejectProduct,
    parseCad,
    recognizeImage,
    previewImport,
    validateImportPreview,
    confirmImport,
    uploadImages,
    loadDicts,
    createManualProduct,
    getProduct,
    updateProduct,
    deleteProduct,
    deleteProducts,
    restoreProduct,
  }
}
