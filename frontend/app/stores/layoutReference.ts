import { defineStore } from 'pinia'
import type { LayoutReferenceEditing, LayoutReferenceMark } from '~/types/layoutPlanning'
import { cloneReferenceEditing, layoutReferenceTaskKey } from '~/utils/layoutReference'

export interface LayoutReferenceTask {
  zoneId: string
  analysisId: string
  revision: number
  /** 任务启动时所在楼层；识别跨楼层切换后 settle 仍写回该楼层的持久化键。 */
  projectId: string
  status: 'running' | 'done' | 'error'
  /** 识别出的有效标记（未与面板内人工标记合并）。 */
  marks: LayoutReferenceMark[]
  /** 面板展示用的结果文案。 */
  message: string
  /** 启动时的编辑快照，用于跨页面重开面板时恢复。 */
  editing: LayoutReferenceEditing
  /** 识别启动时区域内已排版家具的签名；结果到达时比对，防止旧结果覆盖飞行期间的新排版。 */
  layoutSignature: string
  /** running 期间用户显式放弃：不自动重开，settle 时丢弃结果。 */
  dismissed: boolean
  /** done/error 结果已在面板展示过。 */
  consumed: boolean
  updatedAt: number
}

interface LayoutReferenceState {
  tasks: Record<string, LayoutReferenceTask>
  hydratedFor: string
}

/** 进行中的请求持有在模块级，组件卸载不会取消它。 */
const inflight = new Map<string, Promise<void>>()
/** 每次识别的身份令牌：取消后重新识别时，旧请求落地凭令牌丢弃，避免写进新记录（Pinia 状态是代理，不能比对象引用）。 */
const runTokens = new Map<string, symbol>()
const STALE_MS = 24 * 3600 * 1000

function storageKeyFor(projectId: string) {
  return `rsdp-office-layout-reference-tasks-v1:${useAuth().storageScope.value}:${projectId}`
}

function readStorageMap(projectId: string): Record<string, LayoutReferenceTask> {
  try {
    const parsed = JSON.parse(sessionStorage.getItem(storageKeyFor(projectId)) || '{}')
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    sessionStorage.removeItem(storageKeyFor(projectId))
    return {}
  }
}

export const useLayoutReferenceStore = defineStore('layoutReference', {
  state: (): LayoutReferenceState => ({ tasks: {}, hydratedFor: '' }),
  getters: {
    taskFor: state => (zoneId: string, analysisId: string, revision: number): LayoutReferenceTask | null =>
      state.tasks[layoutReferenceTaskKey(zoneId, analysisId, revision)] || null,
    /** 该空间是否有进行中且未放弃的识别任务；识别结果面向空画布，运行期间同空间的家具写入需经确认放行。 */
    runningTaskFor: state => (zoneId: string): LayoutReferenceTask | null =>
      Object.values(state.tasks).find(task => task.zoneId === zoneId && task.status === 'running' && !task.dismissed) || null,
  },
  actions: {
    /** 从 sessionStorage 水合当前楼层的任务记录；stale running 视为中断（整页刷新已杀死请求）。 */
    ensureHydrated() {
      if (typeof sessionStorage === 'undefined') return
      const projectId = useOfficeApi().projectId
      if (!projectId || this.hydratedFor === projectId) return
      this.hydratedFor = projectId
      const now = Date.now()
      for (const [key, record] of Object.entries(readStorageMap(projectId))) {
        if (this.tasks[key] || now - record.updatedAt > STALE_MS) continue
        this.tasks[key] = record.status === 'running'
          ? { ...record, status: 'error', message: '识别已中断，请重新识别', updatedAt: now }
          : record
      }
    },
    persistRecord(record: LayoutReferenceTask | null, key: string, projectId?: string) {
      if (typeof sessionStorage === 'undefined') return
      const floor = projectId || record?.projectId || this.tasks[key]?.projectId || useOfficeApi().projectId
      if (!floor) return
      const map = readStorageMap(floor)
      if (record) map[key] = record
      else delete map[key]
      if (Object.keys(map).length) sessionStorage.setItem(storageKeyFor(floor), JSON.stringify(map))
      else sessionStorage.removeItem(storageKeyFor(floor))
    },
    /**
     * 识别生命周期完全由 store 持有：面板卸载不中断请求，结果写入记录供重开后恢复。
     * 永不 reject——所有失败落到记录里，调用方无需 try/catch。
     */
    runRecognition(params: {
      zoneId: string
      analysisId: string
      revision: number
      upload: Blob
      editing: LayoutReferenceEditing
      layoutSignature: string
      isValid: (mark: LayoutReferenceMark) => boolean
    }): Promise<void> {
      const key = layoutReferenceTaskKey(params.zoneId, params.analysisId, params.revision)
      const existing = inflight.get(key)
      // 已放弃的旧请求仍在飞行时不复用：允许取消后立即重新识别，旧结果落地时按记录身份丢弃。
      if (existing && this.tasks[key] && !this.tasks[key].dismissed) return existing
      this.ensureHydrated()
      const record: LayoutReferenceTask = {
        zoneId: params.zoneId,
        analysisId: params.analysisId,
        revision: params.revision,
        projectId: useOfficeApi().projectId,
        status: 'running',
        marks: [],
        message: '',
        editing: cloneReferenceEditing(params.editing),
        layoutSignature: params.layoutSignature,
        dismissed: false,
        consumed: false,
        updatedAt: Date.now(),
      }
      this.tasks[key] = record
      this.persistRecord(record, key)
      const token = Symbol(key)
      runTokens.set(key, token)
      const promise = (async () => {
        try {
          const detected = await useOfficeApi().recognizeLayoutReference(params.upload, params.analysisId, params.revision)
          const valid = detected.filter(params.isValid)
          const current = this.tasks[key]
          if (!current || runTokens.get(key) !== token) return
          if (current.dismissed) { this.clear(key); return }
          current.status = 'done'
          current.marks = valid
          current.message = valid.length ? `识别到 ${valid.length} 个家具标记，请核对图例、方向和分组` : '裁图中未识别到可靠家具，可以在画布手动添加'
          current.updatedAt = Date.now()
          this.persistRecord(current, key)
        } catch (error) {
          const current = this.tasks[key]
          if (!current || runTokens.get(key) !== token) return
          if (current.dismissed) { this.clear(key); return }
          current.status = 'error'
          current.message = error instanceof Error ? error.message : '识别失败，可手动添加'
          current.updatedAt = Date.now()
          this.persistRecord(current, key)
        } finally {
          if (inflight.get(key) === promise) inflight.delete(key)
        }
      })()
      inflight.set(key, promise)
      return promise
    },
    /**
     * 把面板当前编辑状态同步进记录快照（结果展示后用户的确认/旋转/删除/补录），
     * 供离开页面后重开时恢复。刻意不更新 updatedAt：updatedAt 是恢复 watch 的重开信号，
     * 编辑同步若触发它，用户主动隐藏（Escape/切工具）后会被立即重开。
     */
    syncEditing(key: string, editing: LayoutReferenceEditing) {
      const record = this.tasks[key]
      if (!record || record.dismissed) return
      record.editing = cloneReferenceEditing(editing)
      this.persistRecord(record, key)
    },
    consume(key: string) {
      const record = this.tasks[key]
      if (!record) return
      record.consumed = true
      record.updatedAt = Date.now()
      this.persistRecord(record, key)
    },
    dismiss(key: string) {
      const record = this.tasks[key]
      if (!record || record.status !== 'running') return
      record.dismissed = true
      record.updatedAt = Date.now()
      this.persistRecord(record, key)
    },
    clear(key: string) {
      const projectId = this.tasks[key]?.projectId
      delete this.tasks[key]
      this.persistRecord(null, key, projectId)
    },
  },
})
