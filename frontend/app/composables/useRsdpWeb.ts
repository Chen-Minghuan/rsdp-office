export function useRsdpWeb() {
  const configured = useRuntimeConfig().public.rsdpWebUrl.replace(/\/+$/, '')
  const base = configured || (import.meta.dev ? 'http://localhost:5173' : '')
  return (path: string) => base + path
}
