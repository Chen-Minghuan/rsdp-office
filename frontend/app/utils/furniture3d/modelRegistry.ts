/**
 * 3D 家具模型资源映射层（RENDER-004 P2）。
 * 家具布局实例 → 类别/模板 → 已选真实商品 → 已注册 GLB 模型；查不到逐级回退，最终回退白模方块。
 * 模型资源必须声明单位与原始尺寸，缩放只影响渲染，绝不回写商品业务尺寸。
 */

export interface FurnitureModelAsset {
  /** 稳定资源键（去重与缓存键）。 */
  key: string
  /** GLB 资源地址（本站相对路径或同源地址）。 */
  url: string
  /** 模型文件的原始单位。 */
  unit: 'mm' | 'm'
  /** 模型在自身单位下换算为毫米的原始外包尺寸，用于把模型规范缩放进业务占地。 */
  nativeSizeMm: { widthMm: number; depthMm: number; heightMm: number }
  /** 锚点约定：底面中心落地。 */
  anchor: 'floor-center'
}

export interface ResolvedFurnitureModel {
  asset: FurnitureModelAsset
  matchedBy: 'product' | 'category'
  /** true=类别默认示意模型；false=真实商品模型。 */
  schematic: boolean
}

const productModels = new Map<string, FurnitureModelAsset>()
const categoryModels = new Map<string, FurnitureModelAsset>()

function assertAsset(asset: FurnitureModelAsset): FurnitureModelAsset {
  const size = asset?.nativeSizeMm
  if (!asset?.key || !asset.url || !size) throw new Error('模型资源缺少 key/url/原始尺寸')
  if (![size.widthMm, size.depthMm, size.heightMm].every(value => Number.isFinite(value) && value > 0)) {
    throw new Error(`模型资源 ${asset.key} 的原始尺寸无效`)
  }
  if (asset.unit !== 'mm' && asset.unit !== 'm') throw new Error(`模型资源 ${asset.key} 的单位无效`)
  return asset
}

/** 注册真实商品模型（按商品 ID）。 */
export function registerProductModel(productId: string, asset: FurnitureModelAsset) {
  if (!productId) throw new Error('商品模型必须绑定商品 ID')
  productModels.set(productId, assertAsset(asset))
}

/** 注册家具类别默认示意模型（按模板 kind，如 desk/chair/sofa）。 */
export function registerCategoryModel(kind: string, asset: FurnitureModelAsset) {
  if (!kind) throw new Error('类别模型必须绑定类别')
  categoryModels.set(kind, assertAsset(asset))
}

export function clearFurnitureModelRegistry() {
  productModels.clear()
  categoryModels.clear()
}

/**
 * 解析家具实例的可用模型：真实商品模型优先，其次类别示意模型，都没有返回 null（调用方回退白模方块）。
 * @param templateKind 模板类别 kind（如 desk/chair），由调用方从图例目录解析，保持本模块纯数据。
 */
export function resolveFurnitureModel(
  item: { productId?: string },
  templateKind?: string,
): ResolvedFurnitureModel | null {
  if (item.productId && productModels.has(item.productId)) {
    return { asset: productModels.get(item.productId)!, matchedBy: 'product', schematic: false }
  }
  if (templateKind && categoryModels.has(templateKind)) {
    return { asset: categoryModels.get(templateKind)!, matchedBy: 'category', schematic: true }
  }
  return null
}

export interface ModelScaleResult {
  /** 应用于模型根的均匀缩放系数（毫米场景下）。 */
  scale: number
  /** 缩放后的实际外包尺寸（毫米），仅用于展示与锚点计算，不回写业务几何。 */
  fittedSizeMm: { widthMm: number; depthMm: number; heightMm: number }
}

/**
 * 计算模型缩放：等比缩小/放大到恰好嵌入业务占地（宽深取较小比例，不高过声明高度太多时仍等比）。
 * 业务尺寸永远是家具实例的 w/h，模型只是视觉近似，绝不反向修改业务尺寸。
 */
export function computeModelScale(
  asset: FurnitureModelAsset,
  target: { widthMm: number; depthMm: number },
): ModelScaleResult | null {
  const { widthMm, depthMm, heightMm } = asset.nativeSizeMm
  if (![widthMm, depthMm, heightMm].every(value => Number.isFinite(value) && value > 0)) return null
  if (![target.widthMm, target.depthMm].every(value => Number.isFinite(value) && value > 0)) return null
  const scale = Math.min(target.widthMm / widthMm, target.depthMm / depthMm)
  if (!Number.isFinite(scale) || scale <= 0) return null
  return {
    scale,
    fittedSizeMm: { widthMm: widthMm * scale, depthMm: depthMm * scale, heightMm: heightMm * scale },
  }
}
