import { layoutReferenceTaskKey } from '~/utils/layoutReference'

interface RecognitionGateRequest {
  zoneId: string
  zoneName: string
  resolve: (proceed: boolean) => void
}

/**
 * 识别原图进行中拦截同空间的家具写入（摆放/应用排版/清空）：画布中央弹确认，
 * 「取消识别并继续」放弃任务后放行，「保持识别」中止本次操作。弹窗渲染在 CanvasBoard。
 */
export function useRecognitionGuard() {
  const pending = useState<RecognitionGateRequest | null>('recognition-gate-pending', () => null)

  async function gateFurnitureEdit(zoneId: string): Promise<boolean> {
    const taskStore = useLayoutReferenceStore()
    taskStore.ensureHydrated()
    if (!taskStore.runningTaskFor(zoneId)) return true
    if (pending.value) {
      // 已有未决请求（快速连点两个入口）：先按「保持识别」结算旧请求，避免 Promise 悬挂。
      pending.value.resolve(false)
      pending.value = null
    }
    const zoneName = useOfficeStore().zones.find(item => item.id === zoneId)?.name || '当前空间'
    return await new Promise<boolean>(resolve => { pending.value = { zoneId, zoneName, resolve } })
  }

  function answerGate(cancelRecognition: boolean) {
    const request = pending.value
    if (!request) return
    pending.value = null
    if (cancelRecognition) {
      const taskStore = useLayoutReferenceStore()
      const task = taskStore.runningTaskFor(request.zoneId)
      if (task) taskStore.dismiss(layoutReferenceTaskKey(task.zoneId, task.analysisId, task.revision))
    }
    request.resolve(cancelRecognition)
  }

  return { pending, gateFurnitureEdit, answerGate }
}
