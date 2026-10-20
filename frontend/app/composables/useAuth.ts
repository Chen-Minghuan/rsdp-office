const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8082').replace(/\/$/, '')

export const authEnabled = import.meta.env.VITE_AUTH_ENABLED === '1'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

interface AuthUser {
  userId: string
  username: string
  displayName: string
  role: string
}

export interface LoginOptions {
  rememberMe?: boolean
  captchaCode?: string
}

export class LoginError extends Error {
  captchaRequired: boolean
  constructor(message: string, captchaRequired: boolean) {
    super(message)
    this.captchaRequired = captchaRequired
  }
}

export function useAuth() {
  async function login(username: string, password: string, options: LoginOptions = {}): Promise<AuthUser> {
    try {
      const response = await $fetch<ApiResponse<AuthUser>>(`${API_BASE}/api/auth/login`, {
        method: 'POST',
        body: { username, password, rememberMe: !!options.rememberMe, captchaCode: options.captchaCode || undefined },
        credentials: 'include',
      })
      if (response.code !== 200) throw new Error(response.message || '登录失败')
      return response.data
    } catch (cause: any) {
      if (cause instanceof LoginError) throw cause
      throw new LoginError(cause?.data?.message || cause?.message || '登录失败', !!cause?.data?.data?.captchaRequired)
    }
  }

  async function me(): Promise<AuthUser | null> {
    try {
      const response = await $fetch<ApiResponse<AuthUser>>(`${API_BASE}/api/auth/me`, { credentials: 'include' })
      return response.code === 200 ? response.data : null
    } catch {
      return null
    }
  }

  async function logout(): Promise<void> {
    await $fetch(`${API_BASE}/api/auth/logout`, { method: 'POST', credentials: 'include' })
  }

  return { login, me, logout }
}
