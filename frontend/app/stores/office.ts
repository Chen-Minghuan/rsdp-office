import { defineStore } from 'pinia'
import type {
  Project,
  ProjectFile,
  OfficeObject,
  ZoneObject,
  FurnitureObject,
  AgentMessage,
  CartItem,
  DesignBrief,
  ZoneType,
  CanvasMode,
  Product,
} from '~/types/office'
import {
  sampleProject,
  sampleFiles,
  sampleObjects,
  PRODUCTS as SAMPLE_PRODUCTS,
  TONES,
  createWelcomeMessages,
} from '~/utils/sample'
import { uid, money } from '~/utils/helpers'
import { createFurnitureObjects } from '~/utils/layout'

const STORAGE_KEY = 'rsdp-office-v01'
// Read existing drafts once so the workspace rename preserves browser data.
const LEGACY_STORAGE_KEY = 'rsdp-office-demo-v01'

interface OfficeState {
  project: Project
  files: ProjectFile[]
  objects: OfficeObject[]
  blank: boolean
  canvasRevision: number
  canvasMode: CanvasMode
  designZoneId: string | null
  submitted: DesignBrief | null
  chat: AgentMessage[]
  cart: CartItem[]
  activeZoneId: string | null
  undoStack: string[]
  zoom: number
  panX: number
  panY: number
  products: Product[]
  apiReady: boolean
  loading: boolean
}

function defaultState(): OfficeState {
  return {
    project: { name: '', area: 0, headcount: 0, budget: 0, style: '现代简约', description: '' },
    files: [],
    objects: [],
    blank: false,
    canvasRevision: 1,
    canvasMode: 'overview',
    designZoneId: null,
    submitted: null,
    chat: [],
    cart: [],
    activeZoneId: null,
    undoStack: [],
    zoom: 0.62,
    panX: 0,
    panY: 0,
    products: [],
    apiReady: false,
    loading: false,
  }
}

function loadLocalState(): Partial<OfficeState> {
  if (typeof window === 'undefined') return {}
  try {
    const current = localStorage.getItem(STORAGE_KEY)
    const raw = current || localStorage.getItem(LEGACY_STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw, (key, value) => {
      if (key === 'projectId' && value === 'DEMO-PROJECT-001') return 'OFFICE-PROJECT-001'
      if ((key === 'productId' || key === 'id') && typeof value === 'string' && /^DEMO-OF-0[1-7]$/.test(value)) {
        return value.replace('DEMO-OF-', 'SAMPLE-OF-')
      }
      if (key === 'imageAssetId' && value === 'demo-reference') return 'sample-reference'
      if (key === 'source' && value === 'demo') return 'sample'
      return value
    }) as OfficeState
    if (!current) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(parsed))
      localStorage.removeItem(LEGACY_STORAGE_KEY)
    }
    const { undoStack, ...rest } = parsed
    return rest
  } catch {
    return {}
  }
}

function debounce<T extends (this: any, ...args: any[]) => any>(fn: T, ms: number): (this: any, ...args: Parameters<T>) => void {
  let timer: ReturnType<typeof setTimeout> | null = null
  return function (this: any, ...args: Parameters<T>) {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => fn.apply(this, args), ms)
  }
}

export const useOfficeStore = defineStore('office', {
  state: (): OfficeState => ({ ...defaultState(), ...loadLocalState() }),

  getters: {
    zones: (state): ZoneObject[] => state.objects.filter((o) => o.kind === 'zone') as ZoneObject[],
    activeZone: (state): ZoneObject | null => {
      const list = (state.objects.filter((o) => o.kind === 'zone') as ZoneObject[])
      return list.find((z) => z.id === state.activeZoneId) || list[0] || null
    },
    zoneCount: (state) => state.objects.filter((o) => o.kind === 'zone').length,
    noteCount: (state) => state.objects.filter((o) => o.kind === 'note').length,
    refCount: (state) => state.objects.filter((o) => o.kind === 'reference').length,
    furnitureForZone: (state) => (zoneId: string) =>
      state.objects.filter((o): o is FurnitureObject => o.kind === 'furniture' && o.zoneId === zoneId),
    totalPrice(): number {
      return this.cart.reduce((sum, c) => {
        const p = this.products.find((x) => x.id === c.productId) || SAMPLE_PRODUCTS.find((x) => x.id === c.productId)
        return sum + (p?.price || 0) * c.qty
      }, 0)
    },
  },

  actions: {
    async init() {
      if (this.loading) return
      this.loading = true
      const api = useOfficeApi()
      try {
        const [project, canvas, cart, messages, products] = await Promise.all([
          api.loadProject().catch(() => null),
          api.loadCanvas().catch(() => null),
          api.loadCart().catch(() => [] as CartItem[]),
          api.loadMessages().catch(() => [] as AgentMessage[]),
          api.loadProducts().catch(() => [] as Product[]),
        ])

        this.apiReady = project !== null

        if (project) {
          this.project = project
        }
        if (canvas) {
          this.objects = canvas.objects
          this.files = canvas.files
          this.canvasMode = canvas.mode
          this.designZoneId = canvas.designZoneId
          this.canvasRevision = canvas.revision
          this.blank = canvas.blank
        }
        if (cart.length) {
          this.cart = cart
        }
        if (messages.length) {
          this.chat = messages
        }
        this.products = products.length ? products : SAMPLE_PRODUCTS
      } catch (e) {
        console.warn('后端不可用，使用本地演示数据', e)
        this.apiReady = false
        this.products = SAMPLE_PRODUCTS
      }

      if (!this.project.name) {
        this.loadSampleLocal()
      } else if (this.objects.length === 0) {
        // 后端项目已创建但画布为空，加载演示画布并同步
        this.objects = sampleObjects()
        this.files = sampleFiles()
        this.canvasRevision = 1
        this.schedulePersist()
      }
      this.loading = false
    },

    saveLocal() {
      if (typeof window === 'undefined') return
      const payload = { ...this.$state, undoStack: [] }
      localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
    },

    schedulePersist() {
      this.saveLocal()
      this._persistToApi()
    },

    _persistToApi: debounce(async function (this: any) {
      const api = useOfficeApi()
      if (!this.apiReady) return
      try {
        await api.saveProject(this.project)
        await api.saveCanvas({
          objects: this.objects,
          files: this.files,
          mode: this.canvasMode,
          designZoneId: this.designZoneId,
          revision: this.canvasRevision,
          blank: this.blank,
        })
      } catch (e) {
        console.warn('同步到后端失败', e)
      }
    }, 800),

    snapshotUndo() {
      this.undoStack.push(JSON.stringify({ objects: this.objects, blank: this.blank }))
      if (this.undoStack.length > 20) this.undoStack.shift()
    },

    objectUpdate(fn: () => void) {
      this.snapshotUndo()
      fn()
      this.canvasRevision++
      this.schedulePersist()
    },

    loadSampleLocal() {
      this.$patch({
        project: sampleProject(),
        files: sampleFiles(),
        objects: sampleObjects(),
        blank: false,
        canvasRevision: 1,
        canvasMode: 'overview',
        designZoneId: null,
        submitted: null,
        chat: [],
        cart: [],
        activeZoneId: null,
        undoStack: [],
      })
      this.saveLocal()
    },

    async loadSample() {
      this.loadSampleLocal()
      const api = useOfficeApi()
      if (this.apiReady) {
        try {
          await api.resetProject()
          await api.saveProject(this.project)
          await api.saveCanvas({
            objects: this.objects,
            files: this.files,
            mode: this.canvasMode,
            designZoneId: this.designZoneId,
            revision: this.canvasRevision,
            blank: this.blank,
          })
          await api.clearCart()
          this.cart = []
        } catch (e) {
          console.warn('重置后端演示数据失败', e)
        }
      }
    },

    reset() {
      this.$patch(defaultState())
      this.saveLocal()
    },

    setBlank() {
      this.snapshotUndo()
      this.blank = true
      this.objects = []
      this.canvasRevision++
      this.schedulePersist()
    },

    updateProject(p: Partial<Project>) {
      Object.assign(this.project, p)
      this.schedulePersist()
    },

    addFiles(list: FileList | null) {
      if (!list) return
      const allowed = ['pdf', 'png', 'jpg', 'jpeg', 'dwg', 'dxf']
      const accepted = Array.from(list).filter((f) => {
        const ext = (f.name.split('.').pop() || '').toLowerCase()
        return allowed.includes(ext)
      })
      for (const f of accepted) {
        const ext = (f.name.split('.').pop() || '').toUpperCase()
        this.files.push({ name: f.name, size: f.size, type: ext, mock: false })
      }
      this.schedulePersist()
    },

    removeFile(name: string) {
      this.files = this.files.filter((f) => f.name !== name)
      this.schedulePersist()
    },

    addObject(o: OfficeObject) {
      this.objectUpdate(() => this.objects.push(o))
    },

    updateObject(id: string, patch: Partial<OfficeObject>) {
      const idx = this.objects.findIndex((o) => o.id === id)
      if (idx === -1) return
      this.objectUpdate(() => Object.assign(this.objects[idx], patch))
    },

    deleteObject(id: string) {
      this.objectUpdate(() => {
        this.objects = this.objects.filter((o) => o.id !== id)
      })
    },

    moveObject(id: string, x: number, y: number) {
      const o = this.objects.find((o) => o.id === id)
      if (!o) return
      o.x = x
      o.y = y
      this.canvasRevision++
      this.schedulePersist()
    },

    undo() {
      if (!this.undoStack.length) return
      const prev = JSON.parse(this.undoStack.pop()!)
      this.objects = prev.objects
      this.blank = prev.blank
      this.canvasRevision++
      this.schedulePersist()
    },

    setActiveZone(id: string | null) {
      this.activeZoneId = id
      this.saveLocal()
    },

    setCanvasMode(mode: CanvasMode, zoneId?: string | null) {
      this.canvasMode = mode
      this.designZoneId = zoneId ?? null
      this.schedulePersist()
    },

    enterDesign(zoneId: string) {
      this.canvasMode = 'design'
      this.designZoneId = zoneId
      this.activeZoneId = zoneId
      this.schedulePersist()
    },

    submitCanvas() {
      const zones = this.zones.map((z) => ({
        zoneId: z.id,
        name: z.name,
        type: z.zoneType,
        targetSeats: z.seats,
        geometry: {
          kind: 'canvas_rect' as const,
          x: z.x,
          y: z.y,
          width: z.w,
          height: z.h,
        },
        geometryVerified: false as const,
        factSource: 'user_annotation' as const,
        note: z.note || '',
      }))
      const brief: DesignBrief = {
        projectId: useOfficeApi().projectId,
        canvasRevisionId: `CANVAS-REV-${this.canvasRevision}`,
        sceneRevisionId: null,
        projectContext: {
          name: this.project.name,
          headcount: this.project.headcount,
          budgetMax: this.project.budget,
          stylePreference: this.project.style,
          description: this.project.description,
        },
        zones,
        annotations: this.objects
          .filter((o) => o.kind === 'note')
          .map((o) => ({ id: o.id, zoneId: null, content: (o as any).text })),
        references: this.objects
          .filter((o) => o.kind === 'reference')
          .map((o) => ({
            id: o.id,
            caption: (o as any).caption || '办公参考图',
            imageAssetId: (o as any).image ? 'browser-local-image' : 'sample-reference',
          })),
        sourceAssets: this.files.map((f) => ({
          fileName: f.name,
          fileType: f.type,
          source: f.mock ? 'sample' : 'local_upload',
        })),
        pendingQuestions: [
          '尺寸/面积尚未进行可靠比例尺校准',
          '局部工位数量与会议室容量需要业务确认',
        ],
      }
      this.submitted = brief
      this.chat = createWelcomeMessages(brief)
      this.activeZoneId = this.zones[0]?.id || null
      this.schedulePersist()
      this._persistMessages()
    },

    async sendChat(text: string) {
      if (!text.trim()) return
      const trimmed = text.trim()
      this.chat.push({ role: 'user', content: trimmed })
      const api = useOfficeApi()
      if (this.apiReady) {
        try {
          const reply = await api.sendMessage(trimmed)
          this.chat.push(reply)
          return
        } catch (e) {
          console.warn('发送消息到后端失败', e)
        }
      }
      const zone = this.activeZone
      let reply = `已记录您对${zone ? '「' + zone.name + '」' : '办公项目'}的补充说明：“${trimmed}”。\n\n右侧可查看演示商品候选，您可以加入当前区域并调整数量；真实推荐将在接入 RSDP 商品服务与 Office Skill 后实现。`
      if (/预算|便宜|经济|价格/.test(text)) {
        reply = '已记录预算敏感偏好。演示中可先比较候选价格；真实环境将以 RSDP 可售商品、规格与定价策略进行检索，不直接采用模拟价格。'
      } else if (/图纸|画布|空间/.test(text)) {
        reply = '您可以使用左侧“返回修改画布”继续修改区域或标注，再提交新版需求快照。原有已选商品将保留以便复核。'
      }
      this.chat.push({ role: 'bot', content: reply })
      this.saveLocal()
    },

    async _persistMessages() {
      // Messages are persisted when sendChat calls API; local copy is enough for welcome messages.
      this.saveLocal()
    },

    async addToCart(productId: string) {
      const product = this.products.find((p) => p.id === productId) || SAMPLE_PRODUCTS.find((p) => p.id === productId)
      if (!product) return
      const zone = this.activeZone
      const existing = this.cart.find((c) => c.productId === productId && c.zoneId === (zone?.id || null))
      if (existing) {
        existing.qty++
        if (this.apiReady) {
          try {
            await useOfficeApi().updateCartItemQty(existing.id, existing.qty)
          } catch (e) {
            console.warn('更新购物车失败', e)
          }
        }
      } else {
        const item: CartItem = {
          id: uid('line'),
          productId,
          zoneId: zone?.id || null,
          zoneName: zone?.name || '未分区',
          qty: 1,
        }
        if (this.apiReady) {
          try {
            const saved = await useOfficeApi().addCartItem(item)
            item.id = saved.id
          } catch (e) {
            console.warn('添加购物车失败', e)
          }
        }
        this.cart.push(item)
      }
      this.saveLocal()
    },

    async changeQty(lineId: string, delta: number) {
      const item = this.cart.find((c) => c.id === lineId)
      if (!item) return
      item.qty += delta
      if (item.qty <= 0) {
        this.cart = this.cart.filter((c) => c.id !== lineId)
        if (this.apiReady) {
          try {
            await useOfficeApi().deleteCartItem(lineId)
          } catch (e) {
            console.warn('删除购物车失败', e)
          }
        }
      } else {
        if (this.apiReady) {
          try {
            await useOfficeApi().updateCartItemQty(lineId, item.qty)
          } catch (e) {
            console.warn('更新购物车失败', e)
          }
        }
      }
      this.saveLocal()
    },

    clearFurniture(zoneId: string) {
      this.objectUpdate(() => {
        this.objects = this.objects.filter((o) => !(o.kind === 'furniture' && o.zoneId === zoneId))
      })
    },

    layoutFurniture(zoneId: string, productId: string, unitW: number, unitD: number, arrangement: string) {
      const zone = this.zones.find((z) => z.id === zoneId)
      const product = this.products.find((p) => p.id === productId) || SAMPLE_PRODUCTS.find((p) => p.id === productId)
      if (!zone || !product) return
      const placed = createFurnitureObjects(zone, product.id, product.name, unitW, unitD, arrangement as any)
      this.objectUpdate(() => {
        this.objects = this.objects.filter((o) => !(o.kind === 'furniture' && o.zoneId === zoneId))
        this.objects.push(...placed)
      })
    },

    async exportCsv() {
      const api = useOfficeApi()
      if (this.apiReady) {
        try {
          const blob = await api.exportProposal()
          const url = URL.createObjectURL(blob)
          const a = document.createElement('a')
          a.href = url
          a.download = 'RSDP_办公方案_演示BOM.csv'
          a.click()
          setTimeout(() => URL.revokeObjectURL(url), 1500)
          return
        } catch (e) {
          console.warn('后端导出失败，降级本地导出', e)
        }
      }
      const products = this.products.length ? this.products : SAMPLE_PRODUCTS
      const cols = ['空间区域', '演示商品ID', '商品名称', '规格', '数量', '参考单价', '参考小计']
      const rows = this.cart.map((c) => {
        const p = products.find((x) => x.id === c.productId)
        return [c.zoneName, p?.id, p?.name, p?.variant, c.qty, p?.price, (p?.price || 0) * c.qty]
      })
      const csv =
        '\uFEFF' +
        [cols, ...rows]
          .map((row) => row.map((v) => `"${String(v ?? '').replace(/"/g, '""')}"`).join(','))
          .join('\r\n')
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = 'RSDP_办公方案_演示BOM.csv'
      a.click()
      setTimeout(() => URL.revokeObjectURL(url), 1500)
    },
  },
})
