/**
 * 模拟服务端空间复核确认接口（FLOW-004）：
 * 跟踪最近一次画布保存（PUT canvas）中的空间对象，
 * 为单个/一键复核确认请求签发 CONFIRMED 凭据，语义与真实服务端一致（先保存后签发）。
 *
 * 用法：在 context.route('**\/api/v1/**') 处理函数最前面调用 handle；
 * 返回非 null 时直接用 { code: 200, data } 响应。
 */
export function createReviewConfirmMock() {
  let zones = []
  let revision = 1
  const confirmations = new Map()

  function confirmItem(spaceId) {
    const item = {
      spaceId,
      canvasRevision: revision + 1,
      status: 'CONFIRMED',
      confirmedCanvasRevision: revision,
      confirmedByUserId: 'MOCK-USER',
      confirmedAt: new Date().toISOString(),
    }
    confirmations.set(spaceId, item)
    revision += 1
    return item
  }

  return {
    handle(request) {
      const path = new URL(request.url()).pathname
      const method = request.method()
      if (method === 'PUT' && /\/api\/v1\/office\/projects\/[^/]+\/canvas$/.test(path)) {
        let body = null
        try { body = request.postDataJSON() } catch { body = null }
        if (body && Array.isArray(body.objects)) zones = body.objects.filter(item => item && item.kind === 'zone')
        if (body && Number.isFinite(body.revision)) revision = Math.max(revision, body.revision)
        return null
      }
      if (method !== 'POST') return null
      const single = path.match(/\/api\/v1\/office\/floors\/[^/]+\/spaces\/([^/]+)\/review-confirm$/)
      if (single) {
        const zone = zones.find(item => item.id === single[1])
        if (!zone) return { __status: 400, message: '空间不存在或已被删除' }
        if (zone.reviewUnit !== 'mm') return { __status: 400, message: '请先校准空间的实际尺寸' }
        return confirmItem(zone.id)
      }
      if (/\/api\/v1\/office\/floors\/[^/]+\/spaces\/review-confirm-all$/.test(path)) {
        if (!zones.length) return { __status: 400, message: '请先识别或创建空间' }
        const confirmed = []
        const failed = []
        for (const zone of zones) {
          if (zone.reviewUnit === 'mm') confirmed.push(confirmItem(zone.id))
          else failed.push({ spaceId: zone.id, spaceName: zone.name || '', reason: '请先校准空间的实际尺寸' })
        }
        return { canvasRevision: revision, confirmed, alreadyConfirmed: [], failed }
      }
      return null
    },
  }
}

/** 在通用 API 路由处理函数内优先接入复核 mock；命中时完成响应并返回 true。 */
export function fulfillReviewConfirm(reviewMock, route) {
  const data = reviewMock.handle(route.request())
  if (data === null) return false
  if (data.__status) {
    void route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 400, message: data.message, data: null }) })
    return true
  }
  void route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  return true
}

/** 在核对页点击「一键复核」并等待全部空间确认完成（按钮在有待复核空间时存在，确认后消失；已全部确认时直接跳过）。 */
export async function confirmAllReviews(page) {
  const button = page.getByTestId('confirm-all-review')
  if (await button.count() === 0) return
  await button.click()
  await button.waitFor({ state: 'detached', timeout: 10000 })
}
