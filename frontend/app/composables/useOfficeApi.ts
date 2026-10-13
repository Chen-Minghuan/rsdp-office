import type { Project, OfficeObject, AgentMessage, CartItem, Product, ProjectFile, DesignBrief } from '~/types/office'
import type { ImportedProduct, ProductImportResult } from '~/types/imported-products'

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
  widthCm: number
  depthCm: number
  imageUrl?: string
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

  async function loadProject(): Promise<Project> {
    const dto = await request<Project>('GET', `/api/v1/office/projects/${projectId}`)
    return {
      name: dto.projectName || '',
      area: dto.area || 0,
      headcount: dto.headcount || 0,
      budget: dto.budget || 0,
      style: dto.style || '现代简约',
      description: dto.description || '',
    }
  }

  async function saveProject(project: Project): Promise<void> {
    await request<void>('PUT', `/api/v1/office/projects/${projectId}`, {
      projectName: project.name,
      area: project.area,
      headcount: project.headcount,
      budget: project.budget,
      style: project.style,
      description: project.description,
    })
  }

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
    return list.map((p) => ({
      id: p.id,
      name: p.name,
      desc: p.desc || '',
      variant: p.variant || '',
      price: p.price || 0,
      kind: p.kind,
      uses: p.uses || [],
      widthCm: p.widthCm || 120,
      depthCm: p.depthCm || 60,
      imageUrl: p.imageUrl,
    }))
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
    loadProject,
    saveProject,
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
  }
}
