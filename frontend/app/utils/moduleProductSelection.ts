import type { CartItem, OfficeObject, Product } from '~/types/office'
import type { ModuleProductSelection } from '~/types/moduleRecommendation'

/** 应用选品结果所需的最小 store 面（结构化类型，避免 util 反向依赖 store 实现）。 */
export interface ModuleProductSelectionStore {
  objects: OfficeObject[]
  canvasRevision: number
  submitted: unknown
  products: Product[]
  cart: CartItem[]
  saveLocal: () => void
}

/**
 * 应用服务端模块选品结果（RENDER-004 抽取）：
 * 2D「款式推荐」与 3D「替换商品」共用同一份写回逻辑——画布对象、修订号、
 * 商品缓存与选品清单都来自服务端响应，前端不自行拼装商品事实。
 */
export function applyModuleProductSelection(store: ModuleProductSelectionStore, response: ModuleProductSelection) {
  store.objects = response.canvas.objects
  store.canvasRevision = response.canvas.revision
  store.submitted = null
  const product = response.match.product
  store.products = [...store.products.filter(item => item.id !== product.id), product]
  for (const line of response.cartItems) {
    store.cart = [
      ...store.cart.filter(item => item.id !== line.lineId),
      { id: line.lineId, projectId: line.projectId, zoneId: line.zoneId, zoneName: line.zoneName, productId: line.productId, qty: line.qty } as CartItem,
    ]
  }
  store.saveLocal()
}
