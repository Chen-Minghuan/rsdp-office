export function uid(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`
}

export function money(n: number): string {
  return `¥${(Number(n) || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 })}`
}
