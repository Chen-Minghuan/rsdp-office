export default defineNuxtRouteMiddleware(async (to) => {
  if (!authEnabled || to.name === 'login') return
  const user = await useAuth().me()
  if (!user) {
    return navigateTo({ path: '/login', query: { redirect: to.fullPath } })
  }
})
