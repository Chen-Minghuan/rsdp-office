/** Same-origin API/upload base; '/office' only for the domain subpath build. */
export const apiBase = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8082').replace(/\/$/, '')

export function withApiBase(path: string): string {
  return apiBase + path
}
