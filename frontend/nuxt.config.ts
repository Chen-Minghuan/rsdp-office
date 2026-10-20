import tailwindcss from '@tailwindcss/vite'

// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2025-07-15',
  devtools: { enabled: false },
  ssr: false,
  modules: [
    '@pinia/nuxt',
  ],
  css: ['~/assets/css/main.css'],
  vite: {
    plugins: [tailwindcss()],
  },
  imports: {
    dirs: ['stores'],
  },
  runtimeConfig: {
    public: {
      // Build-time same-origin API prefix; '/office' for the domain subpath build.
      apiBaseUrl: '',
    },
  },
})
