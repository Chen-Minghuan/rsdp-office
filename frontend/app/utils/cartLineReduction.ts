import type { CartItem, FurnitureObject } from '~/types/office'

export interface CartLineReductionPlan {
  nextCart: CartItem[]
  toDelete: CartItem[]
  toUpdate: Array<{ line: CartItem, qty: number }>
}

/**
 * 家具删除/清空时的购物车同步计划：按被移除家具对每行的引用数扣减，
 * 行数量耗尽才整行删除；未关联的行保持不变。同一行被多件家具共享时只扣减对应数量。
 */
export function planCartLineReduction(cart: CartItem[], items: FurnitureObject[]): CartLineReductionPlan {
  const referenced = new Map<string, number>()
  for (const item of items) {
    const lineId = item.productSelection?.cartLineId
    if (lineId && item.productId) referenced.set(lineId, (referenced.get(lineId) || 0) + 1)
  }
  const toDelete: CartItem[] = []
  const toUpdate: Array<{ line: CartItem, qty: number }> = []
  const nextCart = cart.flatMap((line) => {
    const deduction = referenced.get(line.id) || 0
    if (!deduction) return [line]
    if (line.qty > deduction) {
      toUpdate.push({ line, qty: line.qty - deduction })
      return [{ ...line, qty: line.qty - deduction }]
    }
    toDelete.push(line)
    return []
  })
  return { nextCart, toDelete, toUpdate }
}
