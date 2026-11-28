const priceFormat = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export function localReferencePrice(amount?: number | null, from = false) {
  if (amount == null) return '价格待补'
  return `¥ ${priceFormat.format(amount)}${from ? ' 起' : ''}`
}
