/** Same-origin API/upload base; '/office' only for the domain subpath build. */
export const apiBase = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8082').replace(/\/$/, '')

export function withApiBase(path: string): string {
  return apiBase + path
}

/**
 * Recognition stores absolute asset URLs tied to the entry point used at the
 * time (e.g. http://ip:8081/uploads/...). Every entry proxies the same backend,
 * and /uploads requires the session cookie, so reduce stored URLs to their path
 * and serve them from whichever entry the user is on. Root-relative app paths
 * get the same prefix; data: URLs and non-HTTP values pass through unchanged.
 */
export function sameOriginAssetUrl(url: string): string {
  if (!url) return url
  if (url.startsWith('/')) return withApiBase(url)
  try {
    const parsed = new URL(url)
    if (parsed.protocol === 'http:' || parsed.protocol === 'https:') return withApiBase(parsed.pathname)
  } catch {
    // Not an HTTP URL (data:, blob:); keep as-is.
  }
  return url
}
