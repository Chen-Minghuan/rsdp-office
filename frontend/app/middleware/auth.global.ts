export default defineNuxtRouteMiddleware(async (to) => {
  if (!authEnabled || to.path === '/login') return
  const user = await useAuth().me()
  if (!user) {
    return navigateTo({ path: '/login', query: { redirect: to.fullPath } })
  }
})
