export function productBundleError(failure: unknown, fallback: string, missing = fallback): string {
  if (typeof failure !== 'object' || failure === null) return fallback
  const error = failure as Error & { statusCode?: number; status?: number; data?: { message?: string } }
  const status = error.statusCode ?? error.status
  if (status === 404) return missing
  if (status === 401 || status === 403) return '当前账号无法访问商品组合，请确认登录状态。'
  if (status !== undefined) return status >= 500 ? fallback : error.data?.message || fallback
  return error instanceof Error && !/^\[(?:GET|POST|PUT|DELETE)\]/.test(error.message) ? error.message : fallback
}
