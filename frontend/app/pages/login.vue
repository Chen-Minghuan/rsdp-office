<script setup lang="ts">
import officeImage from '~/assets/login-office.webp'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const username = ref('')
const password = ref('')
const passwordVisible = ref(false)
const rememberMe = ref(false)
const captchaRequired = ref(false)
const captchaCode = ref('')
const captchaUrl = ref('')
const error = ref('')
const submitting = ref(false)

const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8082').replace(/\/$/, '')

function refreshCaptcha() {
  captchaCode.value = ''
  captchaUrl.value = `${API_BASE}/api/auth/captcha?t=${Date.now()}`
}

async function submit() {
  error.value = ''
  submitting.value = true
  try {
    await auth.login(username.value.trim(), password.value, {
      rememberMe: rememberMe.value,
      captchaCode: captchaRequired.value ? captchaCode.value : undefined,
    })
    useWorkspaceStore().$reset()
    useOfficeStore().$reset()
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') && !route.query.redirect.startsWith('//') ? route.query.redirect : '/workspace/project-bench'
    window.location.replace(router.resolve(redirect).href)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '登录失败，请重试'
    if (cause instanceof LoginError && cause.captchaRequired) {
      captchaRequired.value = true
      refreshCaptcha()
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <div class="login-frame">
      <header class="login-cover">
        <img :src="officeImage" alt="" class="login-cover-image" />
        <div class="login-brand">
          <span class="login-brand-mark" aria-hidden="true"><AppIcon name="building" :size="5" /></span>
          <span>RSDP</span>
        </div>
        <p class="login-product">办公空间<span>智能设计平台</span></p>
      </header>

      <div class="login-panel">
        <form class="grid w-full max-w-95 gap-5" aria-labelledby="login-title" @submit.prevent="submit">
          <div class="mb-3">
            <h1 id="login-title" class="text-[clamp(1.75rem,3.5vw,2.5rem)] leading-tight font-bold tracking-tight">登录工作台</h1>
            <p class="mt-3 text-base leading-relaxed text-muted">使用工作区账号继续。</p>
          </div>

          <div class="grid gap-2">
            <label class="text-sm font-semibold" for="username">账号</label>
            <input id="username" v-model="username" name="username" autocomplete="username" placeholder="请输入账号" required class="login-input" />
          </div>

          <div class="grid gap-2">
            <label class="text-sm font-semibold" for="password">密码</label>
            <div class="relative">
              <input id="password" v-model="password" name="password" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入密码" required class="login-input login-password" />
              <button type="button" class="login-password-toggle" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" aria-controls="password" @click="passwordVisible = !passwordVisible">
                <AppIcon :name="passwordVisible ? 'eyeSlash' : 'eye'" :size="5" aria-hidden="true" />
              </button>
            </div>
          </div>

          <div v-if="captchaRequired" class="grid gap-2">
            <label class="text-sm font-semibold" for="captcha">验证码</label>
            <div class="flex items-center gap-3">
              <input id="captcha" v-model="captchaCode" required maxlength="4" autocomplete="off" placeholder="请输入验证码" class="login-input uppercase placeholder:normal-case" />
              <button type="button" class="shrink-0 border border-accent-line" aria-label="刷新验证码" title="刷新验证码" @click="refreshCaptcha">
                <img :src="captchaUrl" alt="验证码" width="120" height="44" />
              </button>
            </div>
          </div>

          <label class="flex w-fit cursor-pointer items-center gap-2.5 py-0.5 text-sm text-muted">
            <input v-model="rememberMe" type="checkbox" class="size-4 shrink-0 accent-accent" />
            记住我（30 天内免登录）
          </label>

          <p v-if="error" role="alert" class="border border-red-200 bg-red-50 px-3 py-2.5 text-sm text-red-800">{{ error }}</p>

          <button type="submit" :disabled="submitting" class="min-h-12 w-full bg-accent px-4 text-base font-semibold text-white hover:bg-accent-ink disabled:cursor-wait disabled:opacity-70 motion-safe:transition-colors">
            {{ submitting ? '登录中…' : '登录' }}
          </button>
        </form>
      </div>
    </div>
  </main>
</template>

<style scoped>
.login-page {
  display: grid;
  min-height: 100dvh;
  place-items: center;
  padding: clamp(1rem, 5vw, 5rem);
  background: radial-gradient(ellipse at 12% 3%, var(--color-accent-tint), transparent 64%), var(--color-bg);
}

.login-frame {
  display: grid;
  grid-template-columns: 1.08fr 1fr;
  width: min(100%, 76rem);
  min-height: clamp(38rem, 79dvh, 48rem);
  border: 1px solid var(--color-line);
  background: var(--color-panel);
}

.login-cover {
  --cover-ink: color-mix(in srgb, var(--color-ink) 80%, var(--color-accent-ink));
  position: relative;
  isolation: isolate;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 1.75rem;
  min-width: 0;
  padding: clamp(1.5rem, 4vw, 3.5rem);
  color: white;
  background: var(--cover-ink);
}

.login-cover-image,
.login-cover::after {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  z-index: -1;
}

.login-cover-image {
  object-fit: cover;
  object-position: center 61%;
}

.login-cover::after {
  content: '';
  background: linear-gradient(180deg, var(--cover-ink), transparent 35%),
    linear-gradient(0deg, var(--cover-ink), transparent 58%);
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-size: 1.625rem;
  font-weight: 900;
  letter-spacing: -0.035em;
}

.login-brand-mark {
  display: grid;
  width: 2.25rem;
  aspect-ratio: 1;
  place-items: center;
  border: 1px solid var(--color-accent-line);
  background: var(--color-accent-tint);
  color: var(--color-accent-ink);
}

.login-product {
  font-size: clamp(2rem, 3.75vw, 3.5rem);
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: -0.025em;
}

.login-product::before {
  display: block;
  width: 2.5rem;
  height: 0.1875rem;
  margin-bottom: 1.25rem;
  background: var(--color-accent-line);
  content: '';
}

.login-product span {
  display: block;
  margin-top: 0.375rem;
}

.login-panel {
  display: grid;
  min-width: 0;
  place-items: center;
  padding: clamp(1.25rem, 5vw, 4.5rem);
}

.login-input {
  width: 100%;
  min-width: 0;
  min-height: 3.125rem;
  border: 1px solid color-mix(in srgb, var(--color-line) 44%, var(--color-muted));
  padding: 0.625rem 0.875rem;
  background: var(--color-bg);
  font-size: 1rem;
}

.login-input::placeholder {
  color: var(--color-muted);
}

.login-input:focus {
  border-color: var(--color-accent);
  outline-offset: 2px;
  background: var(--color-panel);
}

.login-password {
  padding-right: 3.125rem;
}

.login-password::-ms-reveal {
  display: none;
}

.login-password-toggle {
  position: absolute;
  inset: 0.1875rem 0.1875rem 0.1875rem auto;
  display: grid;
  width: 2.75rem;
  place-items: center;
  color: var(--color-muted);
  outline-offset: -2px;
}

.login-password-toggle:hover {
  color: var(--color-accent-ink);
  background: var(--color-accent-tint);
}

@media (max-width: 48rem) {
  .login-frame {
    grid-template-columns: minmax(0, 1fr);
    width: min(100%, 34rem);
    min-height: 0;
  }

  .login-cover {
    min-height: 11rem;
    padding: 1.25rem;
  }

  .login-product {
    font-size: clamp(1.125rem, 5.4vw, 1.75rem);
  }

  .login-product::before {
    margin-bottom: 0.65rem;
  }

  .login-product span {
    display: inline;
  }

  .login-panel {
    padding-block: 2rem;
  }
}
</style>
