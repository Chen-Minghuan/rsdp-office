import tailwindcss from '@tailwindcss/vite'

const devApiProxyTarget = process.env.VITE_DEV_API_PROXY_TARGET || 'http://localhost:8082'

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
    server: {
      proxy: {
        '/api': { target: devApiProxyTarget, changeOrigin: true },
        '/uploads': { target: devApiProxyTarget, changeOrigin: true },
      },
    },
    plugins: [tailwindcss()],
    optimizeDeps: {
      include: [
        '@heroicons/vue/24/outline',
        'polygon-clipping',
        'three',
        'three/examples/jsm/controls/OrbitControls.js',
      ],
    },
  },
  imports: {
    dirs: ['stores'],
  },
  runtimeConfig: {
    public: {
      // Build-time same-origin API prefix; '/office' for the domain subpath build.
      apiBaseUrl: '',
      rsdpWebUrl: '',
    },
  },
})
