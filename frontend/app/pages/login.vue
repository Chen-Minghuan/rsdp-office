<script setup lang="ts">
const route = useRoute()
const router = useRouter()
const auth = useAuth()
const username = ref('')
const password = ref('')
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
    await router.replace(typeof route.query.redirect === 'string' ? route.query.redirect : '/')
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
  <main class="grid min-h-dvh place-items-center bg-[#f6f1eb] px-5 py-10 text-ink">
    <form class="w-full max-w-sm rounded-3xl border border-line bg-white p-8 shadow-sm" @submit.prevent="submit">
      <div class="mb-8">
        <div class="text-2xl font-extrabold tracking-tight">RSDP</div>
        <h1 class="mt-5 text-xl font-semibold">登录办公空间智能设计平台</h1>
        <p class="mt-2 text-sm leading-6 text-muted">请输入账号和密码继续访问。</p>
      </div>
      <label class="block text-sm font-medium" for="username">账号</label>
      <input id="username" v-model="username" autocomplete="username" required class="mt-2 w-full rounded-xl border border-line px-3 py-2.5 outline-none focus:border-bronze" />
      <label class="mt-4 block text-sm font-medium" for="password">密码</label>
      <input id="password" v-model="password" type="password" autocomplete="current-password" required class="mt-2 w-full rounded-xl border border-line px-3 py-2.5 outline-none focus:border-bronze" />
      <template v-if="captchaRequired">
        <label class="mt-4 block text-sm font-medium" for="captcha">验证码</label>
        <div class="mt-2 flex items-center gap-3">
          <input id="captcha" v-model="captchaCode" required maxlength="4" autocomplete="off" class="w-full rounded-xl border border-line px-3 py-2.5 uppercase outline-none focus:border-bronze" />
          <img :src="captchaUrl" alt="验证码" class="h-11 w-[120px] shrink-0 cursor-pointer rounded-xl border border-line" title="点击刷新" @click="refreshCaptcha" />
        </div>
      </template>
      <label class="mt-4 flex items-center gap-2 text-sm text-muted">
        <input v-model="rememberMe" type="checkbox" class="h-4 w-4 rounded border-line accent-[#9b704b]" />
        记住我（30 天内免登录）
      </label>
      <p v-if="error" role="alert" class="mt-4 rounded-xl bg-[#fff1ee] px-3 py-2 text-sm text-[#a14332]">{{ error }}</p>
      <button type="submit" :disabled="submitting" class="mt-6 w-full rounded-xl bg-[#9b704b] px-4 py-3 text-sm font-semibold text-white transition hover:bg-[#805a3d] disabled:cursor-wait disabled:opacity-60">
        {{ submitting ? '登录中…' : '登录' }}
      </button>
    </form>
  </main>
</template>
