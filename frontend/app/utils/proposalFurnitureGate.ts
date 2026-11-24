import type { CartItem, FurnitureObject, OfficeObject, ZoneObject } from '~/types/office'
import { furnitureById } from '~/utils/manualFurniture'

export interface ProposalMissingFurniture {
  zoneId: string
  zoneName: string
  name: string
  widthMm: number
  depthMm: number
  quantity: number
}

export interface ProposalFurnitureCartMismatch {
  zoneId: string
  zoneName: string
  furnitureCount: number
  cartQuantity: number
  cartExcessQuantity: number
  missing: ProposalMissingFurniture[]
}

/** 从清单回到发现选品差异的房间，待复核的楼层先进入空间设计。 */
export function proposalFurnitureSelectionRoute(floorId: string, zoneId: string, layoutReady: boolean) {
  return {
    path: '/canvas',
    query: { floorId, zoneId, ...(layoutReady ? { mode: 'design', view: 'products' } : {}) },
  }
}

function furnitureName(item: FurnitureObject): string {
  return item.label?.trim()
    || item.moduleSnapshot?.name?.trim()
    || furnitureById.get(item.furnitureTemplateId || '')?.name
    || '未命名家具'
}

function lineQuantity(item: CartItem): number {
  return Number.isFinite(item.qty) && item.qty > 0 ? Math.floor(item.qty) : 0
}

function selectedInCart(item: FurnitureObject, zoneId: string, cartById: Map<string, CartItem>, remainingQuantity: Map<string, number>): boolean {
  const lineId = item.productSelection?.cartLineId
  if (!lineId || !item.productId) return false
  const line = cartById.get(lineId)
  const remaining = remainingQuantity.get(lineId) || 0
  if (!line || line.zoneId !== zoneId || line.productId !== item.productId || remaining < 1) return false
  remainingQuantity.set(lineId, remaining - 1)
  return true
}

function groupMissing(zone: ZoneObject, furniture: FurnitureObject[]): ProposalMissingFurniture[] {
  const groups = new Map<string, ProposalMissingFurniture>()
  for (const item of furniture) {
    const name = furnitureName(item)
    const widthMm = Math.round(item.w)
    const depthMm = Math.round(item.h)
    const key = `${name}\u001f${widthMm}\u001f${depthMm}`
    const existing = groups.get(key)
    if (existing) existing.quantity++
    else groups.set(key, { zoneId: zone.id, zoneName: zone.name, name, widthMm, depthMm, quantity: 1 })
  }
  return [...groups.values()]
}

/**
 * 方案确认校验：每个空间的画布家具必须有对应购物车数量，且每件家具必须保留
 * 一个有效的购物车选品关联。未分区购物车行不参与任何空间的校验。
 */
export function proposalFurnitureCartMismatches(zones: ZoneObject[], objects: OfficeObject[], cart: CartItem[]): ProposalFurnitureCartMismatch[] {
  const furnitureByZone = new Map<string, FurnitureObject[]>()
  for (const item of objects) {
    if (item.kind !== 'furniture') continue
    const list = furnitureByZone.get(item.zoneId) || []
    list.push(item)
    furnitureByZone.set(item.zoneId, list)
  }

  const cartByZone = new Map<string, CartItem[]>()
  for (const item of cart) {
    if (!item.zoneId) continue
    const list = cartByZone.get(item.zoneId) || []
    list.push(item)
    cartByZone.set(item.zoneId, list)
  }

  return zones.flatMap((zone) => {
    const furniture = furnitureByZone.get(zone.id) || []
    const zoneCart = cartByZone.get(zone.id) || []
    const cartQuantity = zoneCart.reduce((sum, item) => sum + lineQuantity(item), 0)
    const cartById = new Map(zoneCart.map(item => [item.id, item]))
    const remainingQuantity = new Map(zoneCart.map(item => [item.id, lineQuantity(item)]))
    const missing = furniture.filter(item => !selectedInCart(item, zone.id, cartById, remainingQuantity))

    if (furniture.length === cartQuantity && !missing.length) return []
    return [{
      zoneId: zone.id,
      zoneName: zone.name,
      furnitureCount: furniture.length,
      cartQuantity,
      cartExcessQuantity: Math.max(0, cartQuantity - furniture.length),
      missing: groupMissing(zone, missing),
    }]
  })
}
