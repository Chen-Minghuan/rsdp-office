import { defineStore } from 'pinia'
import type {
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
  ProductCombo,
} from '~/types/office'
import type { FloorPlanAnalysisResponse } from '~/types/floorPlan'
import { useFloorPlanApi } from '~/composables/useFloorPlanApi'
import {
  sampleFiles,
  PRODUCTS as SAMPLE_PRODUCTS,
  PRODUCT_COMBOS as SAMPLE_COMBOS,
  TONES,
  createWelcomeMessages,
} from '~/utils/sample'
import { uid, money } from '~/utils/helpers'
import { createFurnitureObjects } from '~/utils/layout'
import type { OfficeLayoutMode, OfficeLayoutSpacing } from '~/utils/officeAutoLayout'
import { furnitureById } from '~/utils/manualFurniture'
import { createTemplateFurniture } from '~/utils/officeFurniture'
import type { CustomOfficeModule, OfficeModuleInput } from '~/types/officeModule'
import type { OfficeLayoutSettingsMeta } from '~/types/officeArrangement'
import { customModuleDefinition, moduleInputProblem, restoreCustomModules } from '~/utils/officeModules'
import { manualOfficeFurniture, officePlacementProblem, officeFurnitureMoveProblem, identifyManualFurniture } from '~/utils/officeManualPlacement'
import type { OfficeFurniturePlacement } from '~/utils/officeManualPlacement'
import { OFFICE_LAYOUT_SPACING } from '~/utils/officeAutoLayout'
import { normalizePolygon, polygonBoundsMm, mapRoomType } from '~/utils/floorPlan'
import { geometryForZone, ringProblem, ringsOverlap, zoneProblem, zoneRing, zoneLayoutProblem, zoneReviewMessage } from '~/utils/spatial/review'
import { inferRectangleFrame, resizeRectangle } from '~/utils/spatial/geometry'
import type { SpaceTemplate } from '~/types/spaceTemplate'
import { captureSpaceTemplate, templateMatches, templateLayoutProblem, zonesFromTemplate, templatePlan } from '~/utils/spatial/spaceTemplate'
import { officeReviewExample } from '~/utils/spatial/example'
import { applyDesignCorrections } from '~/utils/spatial/designCorrections'
import { drawingMatchesPlan, recognitionRunning } from '~/utils/drawingContext'
import type { LayoutRequest } from '~/types/layoutPlanning'
import type { SpatialObstacle } from '~/types/spatial'
import { migrateLegacyDoorClearances } from '~/utils/spatial/obstacles'
import { planLayoutProposal, layoutSourceKey, furnitureSeatCount, confirmedLayoutProblem } from '~/utils/layoutPlanning'

const STORAGE_KEY = 'rsdp-office-v01'
// Read existing drafts once so the workspace rename preserves browser data.
const LEGACY_STORAGE_KEY = 'rsdp-office-demo-v01'

interface OfficeState {
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
  combos: ProductCombo[]
  apiReady: boolean
  loading: boolean
  initialized: boolean
  floorPlan: FloorPlanAnalysisResponse | null
  floorPlanLoading: boolean
  floorPlanError: string | null
  spaceTemplate: SpaceTemplate | null
  customModules: CustomOfficeModule[]
  furniturePlacement: OfficeFurniturePlacement | null
  /** Session-only signal: the user manually edited furniture in a zone (delete/clear/move/place); the design sidebar pauses the proposal preview so the canvas shows only what is actually placed. */
  lastManualFurnitureEdit: { zoneId: string; at: number } | null
  syncState: 'idle' | 'saving' | 'saved' | 'error'
  syncError: string | null
}

function defaultState(): OfficeState {
  return {
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
    combos: [],
    apiReady: false,
    loading: false,
    initialized: false,
    floorPlan: null,
    floorPlanLoading: false,
    floorPlanError: null,
    spaceTemplate: null,
    customModules: [],
    furniturePlacement: null,
    lastManualFurnitureEdit: null,
    syncState: 'idle',
    syncError: null,
  }
}

function singleDrawing(files: ProjectFile[] = [], sourceName?: string): ProjectFile[] {
  const file = files.find(file => file.name === sourceName) || files[0]
  return file ? [file] : []
}

function loadLocalState(): Partial<OfficeState> {
  if (typeof window === 'undefined') return {}
  try {
    const current = localStorage.getItem(STORAGE_KEY)
    const raw = current || localStorage.getItem(LEGACY_STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw, (key, value) => {
      if (key === 'project' || key === 'projectContext') return undefined
      if (key === 'projectId' && value === 'DEMO-PROJECT-001') return 'OFFICE-PROJECT-001'
      if ((key === 'productId' || key === 'id') && typeof value === 'string' && /^DEMO-OF-0[1-7]$/.test(value)) {
        return value.replace('DEMO-OF-', 'SAMPLE-OF-')
      }
      if (key === 'imageAssetId' && value === 'demo-reference') return 'sample-reference'
      if (key === 'source' && value === 'demo') return 'sample'
      return value
    }) as OfficeState
    parsed.files = singleDrawing(parsed.files, parsed.floorPlan?.sourceName)
    const migratedZoneIds = migrateLegacyDoorClearances(parsed.objects || [])
    if (migratedZoneIds.length) {
      parsed.canvasRevision = (parsed.canvasRevision || 0) + 1
      parsed.objects?.forEach(object => { if (object.kind === 'furniture' && migratedZoneIds.includes(object.zoneId)) object.layoutConfirmed = false })
      parsed.submitted = null
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(parsed))
    localStorage.removeItem(LEGACY_STORAGE_KEY)
    const { undoStack, ...rest } = parsed
    return { ...rest, customModules: restoreCustomModules(rest.customModules), furniturePlacement: null }
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
  state: (): OfficeState => ({ ...defaultState(), ...loadLocalState(), initialized: false, loading: false, floorPlanLoading: false, syncState: 'idle', syncError: null }),

  getters: {
    drawingName: (state): string => state.files[0]?.name || state.floorPlan?.sourceName || '办公空间',
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
      const allProducts = this.products.length ? this.products : SAMPLE_PRODUCTS
      return this.cart.reduce((sum, c) => {
        const p = allProducts.find((x) => x.id === c.productId)
        return sum + (p?.price || 0) * c.qty
      }, 0)
    },
    layoutBlocker(): string | null {
      if (this.floorPlanLoading) return '正在加载图纸'
      if (this.floorPlanError) return this.floorPlanError
      if (!drawingMatchesPlan(this.files[0], this.floorPlan)) return '当前图纸尚未应用识别结果，请先识别并进入对应空间'
      if (!this.zones.length) return '请先识别或创建空间'
      const ids = this.zones.map(zone => zone.id)
      if (ids.some(id => !id) || new Set(ids).size !== ids.length) return '空间 ID 缺失或重复'
      const zone = this.zones.find(zone => zoneLayoutProblem(zone))
      return zone ? zone.name + '：' + zoneLayoutProblem(zone) : null
    },
    canEnterLayout(): boolean {
      return !this.layoutBlocker
    },
    spaceTemplateCurrent(): boolean {
      return this.canEnterLayout && !!this.spaceTemplate && templateMatches(this.spaceTemplate, this.zones, this.floorPlan)
    },
    allZonesConfirmed(): boolean {
      if (this.floorPlanLoading || this.floorPlanError) return false
      return this.zones.length > 0 && this.zones.every((z) => z.confirmed && !zoneProblem(z, this.zones))
    },
    layoutConfirmedForZone(): (zoneId: string) => boolean {
      return (zoneId: string) => {
        const items = this.objects.filter((o): o is FurnitureObject => o.kind === 'furniture' && o.zoneId === zoneId)
        return items.length > 0 && items.every(item => item.layoutConfirmed)
      }
    },
    comboById(): (id: string) => ProductCombo | undefined {
      const allCombos = this.combos.length ? this.combos : SAMPLE_COMBOS
      return (id: string) => allCombos.find((c) => c.id === id)
    },
  },

  actions: {
    async init() {
      if (this.loading || this.initialized) return
      this.loading = true
      const api = useOfficeApi()
      try {
        const [canvasResult, cart, messages, products] = await Promise.all([
          api.loadCanvas().then(canvas => ({ available: true, canvas })).catch(() => ({ available: false, canvas: null })),
          api.loadCart().catch(() => [] as CartItem[]),
          api.loadMessages().catch(() => [] as AgentMessage[]),
          api.loadProducts().catch(() => [] as Product[]),
        ])

        this.apiReady = canvasResult.available
        const canvas = canvasResult.canvas
        if (canvas && Array.isArray(canvas.objects) && canvas.revision >= this.canvasRevision) {
          this.objects = canvas.objects
          this.files = singleDrawing(canvas.files, this.floorPlan?.sourceName)
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
        this.combos = SAMPLE_COMBOS
      } catch (e) {
        console.warn('后端不可用，使用本地演示数据', e)
        this.apiReady = false
        this.products = SAMPLE_PRODUCTS
        this.combos = SAMPLE_COMBOS
      }
      const migratedZoneIds = migrateLegacyDoorClearances(this.objects)
      if (migratedZoneIds.length) {
        this.snapshotUndo()
        this.canvasRevision++
        this.objects.forEach(object => { if (object.kind === 'furniture' && migratedZoneIds.includes(object.zoneId)) object.layoutConfirmed = false })
        this.submitted = null
        this.schedulePersist()
      }

      const templateId = this.zones[0]?.spaceTemplateId
      if (templateId?.startsWith('STPL-') && this.zones.every(zone => zone.spaceTemplateId === templateId) && !this.spaceTemplateCurrent) {
        try {
          const saved = await useSpaceTemplateApi().get(templateId)
          if (saved?.scene && templateMatches(saved, this.zones, this.floorPlan)) this.spaceTemplate = { ...saved, storage: 'SAVED' }
        } catch { /* Keep the editable canvas even if its saved template cannot currently be fetched. */ }
      }
      if (!this.canEnterLayout) {
        this.canvasMode = 'overview'
        this.designZoneId = null
      }
      this.loading = false
      this.initialized = true
    },

    saveLocal() {
      if (typeof window === 'undefined') return
      const { furniturePlacement, syncState, syncError, lastManualFurnitureEdit, ...persisted } = this.$state
      const payload = { ...persisted, undoStack: [] }
      localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
    },

    schedulePersist() {
      this.saveLocal()
      this.syncState = this.apiReady ? 'saving' : 'saved'
      this.syncError = null
      this._persistToApi()
    },

    _persistToApi: debounce(async function (this: any) {
      const api = useOfficeApi()
      if (!this.apiReady) {
        this.syncState = 'saved'
        return
      }
      try {
        await api.saveCanvas({
          objects: this.objects,
          files: this.files,
          mode: this.canvasMode,
          designZoneId: this.designZoneId,
          revision: this.canvasRevision,
          blank: this.blank,
        })
        this.syncState = 'saved'
        this.syncError = null
      } catch (e) {
        this.syncState = 'error'
        this.syncError = e instanceof Error ? e.message : '远端保存失败'
        console.warn('同步到后端失败', e)
      }
    }, 800),

    snapshotUndo() {
      this.undoStack.push(JSON.stringify({ objects: this.objects, blank: this.blank, floorPlan: this.floorPlan, spaceTemplate: this.spaceTemplate, cart: this.cart, submitted: this.submitted }))
      if (this.undoStack.length > 20) this.undoStack.shift()
    },

    objectUpdate(fn: () => void) {
      this.snapshotUndo()
      fn()
      this.canvasRevision++
      this.schedulePersist()
    },

    loadSampleLocal() {
      const example = officeReviewExample()
      this.$patch({
        files: sampleFiles(),
        objects: example.zones,
        floorPlan: example.plan,
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
      this.objectUpdate(() => {
        Object.assign(this.objects[idx], patch)
        if (this.objects[idx].kind === 'zone' && ['polygon', 'x', 'y', 'w', 'h', 'zoneType', 'pixelToMeter', 'obstacles'].some(key => key in patch)) {
          const zone = this.objects[idx] as ZoneObject
          zone.confirmed = false
          zone.confirmedAt = undefined
          this.invalidateZoneLayout(id)
          this.zones.filter(other => other.id !== id && ringsOverlap(zoneRing(zone), zoneRing(other))).forEach(other => {
            other.confirmed = false
            other.confirmedAt = undefined
            this.invalidateZoneLayout(other.id)
          })
        }
      })
    },

    deleteObject(id: string) {
      const target = this.objects.find(object => object.id === id)
      if (!target) return false
      this.objectUpdate(() => {
        this.objects = this.objects.filter(object => object.id !== id)
        if (target.kind === 'furniture') {
          this.lastManualFurnitureEdit = { zoneId: target.zoneId, at: Date.now() }
          this.furnitureForZone(target.zoneId).forEach(item => { item.layoutConfirmed = false })
          this.submitted = null
        }
      })
      return true
    },

    deleteZone(id: string) {
      const target = this.zones.find(zone => zone.id === id)
      if (!target) return false
      this.objectUpdate(() => {
        const remaining = this.objects.filter(object => object.kind === 'zone' ? object.id !== id : object.zoneId !== id)
        this.objects = remaining
        this.cart = this.cart.filter(item => item.zoneId !== id)
        this.submitted = null
        this.spaceTemplate = null
        // An empty canvas is an intentional user decision. Persist it so a
        // route carrying analysisId does not silently re-import deleted rooms.
        this.blank = !remaining.some(object => object.kind === 'zone')
        if (this.activeZoneId === id) this.activeZoneId = this.zones[0]?.id || null
        if (this.designZoneId === id) {
          this.canvasMode = 'overview'
          this.designZoneId = null
        }
      })
      return true
    },

    deleteFurnitureGroup(ids: string[], zoneId: string) {
      const selected = new Set(ids)
      const items = this.furnitureForZone(zoneId).filter(item => selected.has(item.id))
      if (!items.length || items.length !== selected.size) return false
      this.objectUpdate(() => {
      this.lastManualFurnitureEdit = { zoneId, at: Date.now() }
        this.objects = this.objects.filter(object => !selected.has(object.id))
        this.furnitureForZone(zoneId).forEach(item => { item.layoutConfirmed = false })
        this.submitted = null
      })
      return true
    },

    moveFurnitureGroup(ids: string[], zoneId: string, dx: number, dy: number): string | null {
      const zone = this.zones.find(item => item.id === zoneId)
      const selected = new Set(ids)
      const current = this.furnitureForZone(zoneId)
      const items = current.filter(item => selected.has(item.id))
      if (!zone || !items.length || items.length !== selected.size || !Number.isFinite(dx) || !Number.isFinite(dy)) return '所选家具或位移无效'
      if (dx === 0 && dy === 0) return null
      const candidates = items.map(item => ({ ...item, x: item.x + dx, y: item.y + dy }))
      const problem = officeFurnitureMoveProblem(zone, candidates, current)
      if (problem) return problem
      this.lastManualFurnitureEdit = { zoneId, at: Date.now() }
      this.objectUpdate(() => {
        items.forEach(item => { item.x += dx; item.y += dy })
        this.furnitureForZone(zoneId).forEach(item => { item.layoutConfirmed = false })
        this.submitted = null
      })
      return null
    },

    rotateFurnitureGroup(ids: string[], zoneId: string): string | null {
      const zone = this.zones.find(item => item.id === zoneId)
      const selected = new Set(ids)
      const current = this.furnitureForZone(zoneId)
      const items = current.filter(item => selected.has(item.id))
      if (!zone || !items.length || items.length !== selected.size) return '所选家具无效'
      // Rotation swaps the footprint of non-square modules; revalidate bounds and overlaps.
      const candidates = items.map(item => ({ ...item, rotation: ((item.rotation + 90) % 360 + 360) % 360 }))
      const problem = officeFurnitureMoveProblem(zone, candidates, current)
      if (problem) return problem
      this.lastManualFurnitureEdit = { zoneId, at: Date.now() }
      this.objectUpdate(() => {
        items.forEach(item => { item.rotation = ((item.rotation + 90) % 360 + 360) % 360 })
        this.furnitureForZone(zoneId).forEach(item => { item.layoutConfirmed = false })
        this.submitted = null
      })
      return null
    },

    mirrorFurnitureGroup(ids: string[], zoneId: string): string | null {
      const selected = new Set(ids)
      const items = this.furnitureForZone(zoneId).filter(item => selected.has(item.id))
      if (!items.length || items.length !== selected.size) return '所选家具无效'
      // Mirroring keeps the footprint; only the glyph and the seat-corner sides flip.
      this.lastManualFurnitureEdit = { zoneId, at: Date.now() }
      this.objectUpdate(() => {
        items.forEach(item => { item.mirrorX = !item.mirrorX })
        this.furnitureForZone(zoneId).forEach(item => { item.layoutConfirmed = false })
        this.submitted = null
      })
      return null
    },
    moveObject(id: string, x: number, y: number) {
      const o = this.objects.find((o) => o.id === id)
      if (!o) return
      if (o.kind === 'zone' && o.polygon?.length) {
        this.updateZonePolygon(id, o.polygon.map(([px, py]) => [px + x - o.x, py + y - o.y]))
        return
      }
      if (o.kind === 'furniture') this.lastManualFurnitureEdit = { zoneId: o.zoneId, at: Date.now() }
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
      if ('floorPlan' in prev) this.floorPlan = prev.floorPlan
      if ('spaceTemplate' in prev) this.spaceTemplate = prev.spaceTemplate
      if ('cart' in prev) this.cart = prev.cart
      if ('submitted' in prev) this.submitted = prev.submitted
      if (!this.canEnterLayout) { this.canvasMode = 'overview'; this.designZoneId = null }
      this.canvasRevision++
      this.schedulePersist()
    },

    setActiveZone(id: string | null) {
      this.activeZoneId = id
      this.saveLocal()
    },

    setCanvasMode(mode: CanvasMode, zoneId?: string | null) {
      if (mode === 'design' && !this.canEnterLayout) return false
      if (mode === 'design') return this.enterDesign(zoneId || this.zones[0]?.id || '')
      this.canvasMode = mode
      this.designZoneId = zoneId ?? null
      this.schedulePersist()
      return true
    },

    captureSpaceTemplate(name?: string) {
      if (!this.canEnterLayout) return null
      return captureSpaceTemplate(this.zones, this.floorPlan, this.canvasRevision, name || this.drawingName + ' · 空间模板')
    },
    attachSpaceTemplate(template: SpaceTemplate) {
      if (!templateMatches(template, this.zones, this.floorPlan)) return false
      this.spaceTemplate = JSON.parse(JSON.stringify(template)) as SpaceTemplate
      this.saveLocal()
      return true
    },
    useSpaceTemplate(template: SpaceTemplate) {
      if (templateLayoutProblem(template)) return false
      if (templateMatches(template, this.zones, this.floorPlan)) {
        this.spaceTemplate = JSON.parse(JSON.stringify(template)) as SpaceTemplate
      } else {
        this.objectUpdate(() => {
          this.objects = zonesFromTemplate(template)
          this.floorPlan = { ...templatePlan(template), sourceDrawingId: this.files[0]?.drawingId }
          this.floorPlanError = null
          this.spaceTemplate = JSON.parse(JSON.stringify(template)) as SpaceTemplate
          this.submitted = null
          this.blank = false
        })
      }
      const zone = this.zones.find(zone => zone.zoneType === 'OPEN_OFFICE') || this.zones[0]
      return zone?.id || null
    },
    enterDesign(zoneId: string) {
      if (!this.canEnterLayout || !this.zones.some(zone => zone.id === zoneId)) return false
      if (!this.spaceTemplateCurrent) this.spaceTemplate = this.captureSpaceTemplate()
      this.zones.forEach(zone => { zone.spaceTemplateId = this.spaceTemplate!.templateId; zone.templateSpaceId = zone.id })
      this.canvasMode = 'design'
      this.designZoneId = zoneId
      this.activeZoneId = zoneId
      this.schedulePersist()
      return true
    },

    confirmZone(id: string) {
      const zone = this.zones.find(item => item.id === id)
      if (!zone || zoneProblem(zone, this.zones)) return false
      this.updateObject(id, { confirmed: true, confirmedAt: Date.now() } as Partial<OfficeObject>)
      return true
    },

    unconfirmZone(id: string) {
      this.updateObject(id, { confirmed: false, confirmedAt: undefined } as Partial<OfficeObject>)
      this.invalidateZoneLayout(id)
      this.schedulePersist()
    },

    resizeZoneByMm(id: string, widthMm: number, depthMm: number) {
      const zone = this.zones.find((z) => z.id === id)
      if (!zone || !Number.isFinite(widthMm) || !Number.isFinite(depthMm) || widthMm <= 0 || depthMm <= 0) return
      if (zone.polygon?.length) {
        if (zone.reviewUnit !== 'mm') return
        const geometry = geometryForZone(zone)
        const frame = inferRectangleFrame(geometry)
        if (!frame) return
        const next = resizeRectangle(geometry, frame, widthMm, depthMm)
        this.updateZonePolygon(id, next.outer.map(([x, y]) => [x, -y]))
        return
      }
      const pxPerMeter = zone.pixelToMeter && zone.pixelToMeter > 0 ? 1 / zone.pixelToMeter : 50
      const newW = (widthMm / 1000) * pxPerMeter
      const newH = (depthMm / 1000) * pxPerMeter
      this.updateObject(id, {
        w: Math.round(newW),
        h: Math.round(newH),
        actualWidthMm: widthMm,
        actualDepthMm: depthMm,
        confirmed: false,
      } as Partial<OfficeObject>)
    },

    invalidateZoneLayout(zoneId: string) {
      this.objects.forEach(object => {
        if (object.kind === 'furniture' && object.zoneId === zoneId) object.layoutConfirmed = false
      })
      this.submitted = null
      this.canvasMode = 'overview'
      this.designZoneId = null
    },

    updateZonePolygon(id: string, polygon: Array<[number, number]>) {
      const zone = this.zones.find(item => item.id === id)
      const bounds = polygonBoundsMm(polygon)
      if (!zone || !bounds || ringProblem(polygon)) return false
      this.updateObject(id, {
        polygon, x: bounds.minX, y: bounds.minY, w: bounds.maxX - bounds.minX, h: bounds.maxY - bounds.minY,
        actualWidthMm: zone.reviewUnit === 'mm' ? bounds.maxX - bounds.minX : undefined,
        actualDepthMm: zone.reviewUnit === 'mm' ? bounds.maxY - bounds.minY : undefined,
        geometrySource: 'manual_geometry',
      } as Partial<ZoneObject>)
      return true
    },

    saveZoneObstacle(zoneId: string, obstacle: SpatialObstacle) {
      const zone = this.zones.find(item => item.id === zoneId)
      if (!zone || obstacle.spaceId !== zoneId) return '建筑约束归属无效'
      const obstacles = zone.obstacles?.some(item => item.obstacleId === obstacle.obstacleId)
        ? zone.obstacles.map(item => item.obstacleId === obstacle.obstacleId ? obstacle : item)
        : [...(zone.obstacles || []), obstacle]
      const problem = zoneLayoutProblem({ ...zone, obstacles })
      if (problem) return problem
      this.updateObject(zoneId, { obstacles } as Partial<ZoneObject>)
      return null
    },

    removeZoneObstacle(zoneId: string, obstacleId: string) {
      const zone = this.zones.find(item => item.id === zoneId)
      if (!zone || !zone.obstacles?.some(item => item.obstacleId === obstacleId)) return false
      this.updateObject(zoneId, { obstacles: zone.obstacles.filter(item => item.obstacleId !== obstacleId) } as Partial<ZoneObject>)
      return true
    },

    calibrateReviewPage(referenceDistance: number, lengthMm: number) {
      if (!Number.isFinite(referenceDistance) || !Number.isFinite(lengthMm) || referenceDistance <= 0 || lengthMm <= 0 || !this.zones.length) return false
      const factor = lengthMm / referenceDistance
      this.objectUpdate(() => {
        this.zones.forEach(zone => {
          const ring = zoneRing(zone)
          if (!zone.sourcePolygon) zone.sourcePolygon = ring.map(([x, y]) => [x, y])
          zone.polygon = ring.map(([x, y]) => [x * factor, y * factor])
          zone.obstacles?.forEach(obstacle => { obstacle.geometry.outer = obstacle.geometry.outer.map(([x, y]) => [x * factor, y * factor]) })
          const bounds = polygonBoundsMm(zone.polygon)
          if (bounds) Object.assign(zone, { x: bounds.minX, y: bounds.minY, w: bounds.maxX - bounds.minX, h: bounds.maxY - bounds.minY, actualWidthMm: bounds.maxX - bounds.minX, actualDepthMm: bounds.maxY - bounds.minY })
          zone.mmPerSourceUnit = (zone.mmPerSourceUnit || 1) * factor
          zone.reviewUnit = 'mm'
          zone.pixelToMeter = 0.001
          zone.confirmed = false
          zone.confirmedAt = undefined
          this.invalidateZoneLayout(zone.id)
        })
      })
      return true
    },

    loadReviewExample() {
      const example = officeReviewExample()
      this.objectUpdate(() => {
        this.floorPlan = example.plan
        this.floorPlanError = null
        this.objects = example.zones
        this.canvasMode = 'overview'
        this.designZoneId = null
        this.blank = false
      })
    },

    confirmLayout(zoneId: string) {
      const items = this.objects.filter((o): o is FurnitureObject => o.kind === 'furniture' && o.zoneId === zoneId)
      const zone = this.zones.find(zone => zone.id === zoneId)
      if (!zone || !this.canEnterLayout || !items.length || confirmedLayoutProblem(zone, items)) return false
      const target = zone.layoutPreferences?.targetSeats || 0
      const primary = items.find(item => item.layoutRole === 'primary')
      const seats = primary?.layoutSettings?.layoutContext === 'other' ? items.filter(item => item.furnitureTemplateId === primary.furnitureTemplateId).length : furnitureSeatCount(items, items.some(item => item.layoutSettings?.layoutContext === 'meeting'))
      if (target && seats < target) return false
      this.objectUpdate(() => {
        for (const o of this.objects) {
          if (o.kind === 'furniture' && o.zoneId === zoneId) {
            ;(o as FurnitureObject).layoutConfirmed = true
          }
        }
      })
      return true
    },

    unconfirmLayout(zoneId: string) {
      // Skip the undo snapshot when nothing would change; otherwise clearing a layout needs two undos (the second entry is a redundant post-clear state).
      if (!this.objects.some(o => o.kind === 'furniture' && o.zoneId === zoneId && (o as FurnitureObject).layoutConfirmed)) return
      this.objectUpdate(() => {
        for (const o of this.objects) {
          if (o.kind === 'furniture' && o.zoneId === zoneId) {
            ;(o as FurnitureObject).layoutConfirmed = false
          }
        }
      })
    },

    setFloorPlan(floorPlan: FloorPlanAnalysisResponse | null) {
      this.floorPlan = floorPlan
      this.floorPlanError = null
    },

    async loadFloorPlan(analysisId: string) {
      this.floorPlanLoading = true
      this.floorPlanError = null
      try {
        const api = useFloorPlanApi()
        const res = await api.getFloorPlan(analysisId)
        return res
      } catch (e: any) {
        this.floorPlanError = e.message || '加载户型图失败'
        console.warn('加载户型图失败', e)
      } finally {
        this.floorPlanLoading = false
      }
    },

    async confirmFloorPlanRooms(analysisId: string, request: import('~/types/floorPlan').FloorPlanConfirmRequest) {
      this.floorPlanLoading = true
      try {
        const api = useFloorPlanApi()
        const res = await api.confirmFloorPlanRooms(analysisId, request)
        this.floorPlan = res
      } catch (e: any) {
        this.floorPlanError = e.message || '确认空间失败'
        throw e
      } finally {
        this.floorPlanLoading = false
      }
    },

    clearFloorPlan() {
      this.floorPlan = null
      this.floorPlanError = null
    },

    importFloorPlanToZones(snapshot?: FloorPlanAnalysisResponse) {
      const source = snapshot || this.floorPlan
      if (!source || recognitionRunning(source) || source.status === 'failed' || !source.rooms?.length) return
      const drawing = this.files[0]
      const plan = { ...source, sourceDrawingId: source.sourceDrawingId
        || (drawing?.analysisId === source.analysisId ? drawing.drawingId : undefined)
        || (this.floorPlan?.analysisId === source.analysisId ? this.floorPlan.sourceDrawingId : undefined) }
      const replacingDrawing = !!this.floorPlan && this.floorPlan.analysisId !== plan.analysisId && (
        !!plan.sourceDrawingId && plan.sourceDrawingId !== this.floorPlan.sourceDrawingId
        || !!plan.sourceName && !!this.floorPlan.sourceName && plan.sourceName !== this.floorPlan.sourceName)
      // Restore source metadata after loading the saved canvas, without replacing its design geometry.
      const existing = this.zones.filter(zone => zone.sourceAnalysisId === plan.analysisId)
      if (existing.length) {
        const sourceRevision = plan.analysisRevision || 1
        const previous = this.floorPlan?.analysisId === plan.analysisId ? this.floorPlan : null
        const matchingRevision = existing.every(zone => (zone.sourceAnalysisRevision ?? previous?.analysisRevision ?? 1) === sourceRevision)
        if (matchingRevision) {
          if (drawing?.analysisId === plan.analysisId) drawing.appliedAnalysisId = plan.analysisId
          this.setFloorPlan(plan)
          this.saveLocal()
        } else this.floorPlanError = '来源识别版本已变化，已保留当前编辑；请核对新版本后重新导入，不自动替换底图'
        return
      }
      const unit = plan.unitResolution as { canonicalUnit?: string; confirmed?: boolean } | null
      const calibrated = unit?.canonicalUnit === 'mm' && unit.confirmed === true
      const sourceYAxis = plan.inputMode === 'cad' || plan.geometrySource === 'cad_geometry' ? 'up' : 'down'

      // 将多边形房间映射为 ZoneObject，保留 polygon 用于渲染
      const newZones: ZoneObject[] = plan.rooms.map((room, idx) => {
        const toneKeys = Object.keys(TONES) as Array<keyof typeof TONES>
        const tone = toneKeys[idx % toneKeys.length]
        const polygon = normalizePolygon(room.polygon).map(([x, y]): [number, number] => [x, sourceYAxis === 'up' ? -y : y])
        const bounds = polygonBoundsMm(polygon)
        return {
          id: room.roomId,
          kind: 'zone',
          x: bounds ? bounds.minX : 0,
          y: bounds ? bounds.minY : 0,
          w: bounds ? bounds.maxX - bounds.minX : 0,
          h: bounds ? bounds.maxY - bounds.minY : 0,
          name: room.label || '未命名空间',
          zoneType: mapRoomType(room.roomType),
          seats: 0,
          note: '',
          tone,
          calibratedWidth: room.widthMm ? room.widthMm / 1000 : undefined,
          calibratedHeight: room.depthMm ? room.depthMm / 1000 : undefined,
          actualWidthMm: room.widthMm,
          actualDepthMm: room.depthMm,
          polygon,
          source: 'recognition',
          geometrySource: room.geometrySource || plan.geometrySource,
          confirmed: false,
          reviewUnit: calibrated ? 'mm' : 'source',
          mmPerSourceUnit: 1,
          pixelToMeter: calibrated ? 0.001 : undefined,
          sourceAnalysisId: plan.analysisId,
          sourceAnalysisRevision: plan.analysisRevision || 1,
          sourceImageSha256: plan.imageSha256 || undefined,
          sourceYAxis,
          sourcePolygon: polygon.map(([x, y]) => [x, y]),
          evidenceKind: plan.evidenceKind || 'ESTIMATED',
        } as ZoneObject
      })

      applyDesignCorrections(newZones, plan)
      this.objectUpdate(() => {
        this.floorPlan = plan
        this.floorPlanError = null
        if (drawing?.analysisId === plan.analysisId) drawing.appliedAnalysisId = plan.analysisId
        this.objects = replacingDrawing ? [] : this.objects.filter((o) => o.kind !== 'zone')
        this.objects.forEach(object => { if (object.kind === 'furniture') object.layoutConfirmed = false })
        this.objects.push(...newZones)
        this.spaceTemplate = null
        this.activeZoneId = null
        this.canvasMode = 'overview'
        this.designZoneId = null
        this.submitted = null
        this.blank = false
      })
    },

    submitCanvas() {
      const layoutReady = this.canEnterLayout
      const reviewCount = this.zones.filter(z => zoneReviewMessage(z, this.zones)).length
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
        geometryVerified: (z.confirmed === true) as any,
        factSource: 'user_annotation' as const,
        note: z.note || '',
      }))
      const pendingQuestions: string[] = []
      if (reviewCount) pendingQuestions.push(`有 ${reviewCount} 个空间建议复核，发现不符可直接修改，不影响继续排版`)
      if (!layoutReady && this.layoutBlocker) pendingQuestions.push(this.layoutBlocker)
      if (this.zones.some((z) => !z.pixelToMeter)) {
        pendingQuestions.push('部分区域尚未进行可靠比例尺校准')
      }
      if (pendingQuestions.length === 0) {
        pendingQuestions.push('局部工位数量与会议室容量需要业务确认')
      }
      const brief: DesignBrief = {
        projectId: useOfficeApi().projectId,
        canvasRevisionId: `CANVAS-REV-${this.canvasRevision}`,
        sceneRevisionId: null,
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
        pendingQuestions,
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

    addComboToCart(comboId: string) {
      const allCombos = this.combos.length ? this.combos : SAMPLE_COMBOS
      const combo = allCombos.find((c) => c.id === comboId)
      if (!combo) return
      const zone = this.activeZone
      for (const item of combo.items) {
        const product = this.products.find((p) => p.id === item.productId) || SAMPLE_PRODUCTS.find((p) => p.id === item.productId)
        if (!product) continue
        for (let i = 0; i < item.qty; i++) {
          const cartItem: CartItem = {
            id: uid('line'),
            productId: item.productId,
            zoneId: zone?.id || null,
            zoneName: zone?.name || '未分区',
            qty: 1,
            comboId,
          }
          if (this.apiReady) {
            try {
              useOfficeApi().addCartItem(cartItem).then((saved) => { cartItem.id = saved.id }).catch(() => {})
            } catch (e) {
              console.warn('添加购物车失败', e)
            }
          }
          this.cart.push(cartItem)
        }
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

    saveCustomModule(input: OfficeModuleInput, id?: string): CustomOfficeModule | string {
      const problem = moduleInputProblem(input)
      if (problem) return problem
      if (id && !this.customModules.some(module => module.id === id)) return '此自定义模块已移除，请重新新增'
      if (this.customModules.some(module => module.id !== id && module.name === input.name.trim())) return '已有同名自定义模块，请使用不同名称'
      const module = customModuleDefinition(id || uid('UMOD'), input)
      if (!module) return '模块资料无效'
      this.customModules = [...this.customModules.filter(item => item.id !== module.id), module]
      this.furniturePlacement = null
      this.saveLocal()
      return module
    },

    removeCustomModule(id: string) {
      if (!this.customModules.some(module => module.id === id)) return false
      this.customModules = this.customModules.filter(module => module.id !== id)
      this.furniturePlacement = null
      this.saveLocal()
      return true
    },

    restoreCustomModule(module: CustomOfficeModule) {
      const restored = customModuleDefinition(module.id, module)
      if (!restored || this.customModules.some(item => item.id === restored.id || item.name === restored.name)) return false
      this.customModules.push(restored)
      this.saveLocal()
      return true
    },

    beginFurniturePlacement(zoneId: string, templateId: string, widthMm: number, depthMm: number, spacing: OfficeLayoutSpacing = OFFICE_LAYOUT_SPACING): boolean {
      if (!this.canEnterLayout || this.canvasMode !== 'design' || this.designZoneId !== zoneId) return false
      if (![widthMm, depthMm].every(value => Number.isFinite(value) && value >= 10) || !Object.values(spacing).every(value => Number.isFinite(value) && value >= 0)) return false
      const template = this.customModules.find(module => module.id === templateId) || furnitureById.get(templateId)
      if (!template) return false
      if (!this.spaceTemplateCurrent && !this.enterDesign(zoneId)) return false
      this.furniturePlacement = { zoneId, template: { ...template }, widthMm, depthMm, rotation: 0, spacing: { ...spacing } }
      return true
    },

    cancelFurniturePlacement() { this.furniturePlacement = null },

    rotateFurniturePlacement() {
      if (this.furniturePlacement) this.furniturePlacement.rotation = ((this.furniturePlacement.rotation + 90) % 360) as 0 | 90 | 180 | 270
    },

    placeFurnitureAt(xMm: number, yMm: number): string | null {
      const draft = this.furniturePlacement
      const zone = this.zones.find(zone => zone.id === draft?.zoneId)
      if (!draft || !zone || this.designZoneId !== zone.id || this.canvasMode !== 'design' || !this.canEnterLayout) return '请重新选择当前空间的模块'
      if (!this.spaceTemplateCurrent) return '空间已修改，请重新开始添加'
      const candidate = manualOfficeFurniture(draft, xMm, yMm)
      const problem = officePlacementProblem(zone, candidate, this.furnitureForZone(zone.id), draft.spacing)
      if (problem) return problem
      const item = identifyManualFurniture(candidate)
      item.spaceTemplateId = this.spaceTemplate!.templateId
      item.templateSpaceId = zone.id
      this.objectUpdate(() => {
        this.furnitureForZone(zone.id).forEach(furniture => { furniture.layoutConfirmed = false })
      this.lastManualFurnitureEdit = { zoneId: zone.id, at: Date.now() }
        this.submitted = null
        this.objects.push(item)
      })
      return null
    },

    clearFurniture(zoneId: string) {
      this.cancelFurniturePlacement()
      if (this.objects.some(o => o.kind === 'furniture' && o.zoneId === zoneId)) this.lastManualFurnitureEdit = { zoneId, at: Date.now() }
      this.objectUpdate(() => {
        this.objects = this.objects.filter((o) => !(o.kind === 'furniture' && o.zoneId === zoneId))
      })
    },

    applyLayoutProposal(zoneId: string, request: LayoutRequest, sourceKey: string) {
      const zone = this.zones.find(zone => zone.id === zoneId)
      if (!zone || this.canvasMode !== 'design' || this.designZoneId !== zoneId || !this.canEnterLayout) return false
      const existing = this.furnitureForZone(zoneId)
      if (sourceKey !== layoutSourceKey(zone, existing)) return false
      const template = this.customModules.find(module => module.id === request.module.id) || furnitureById.get(request.module.id)
      if (!template) return false
      const proposal = planLayoutProposal(zone, existing, { ...request, module: template })
      if (proposal.problem || !proposal.furniture.length) return false
      this.cancelFurniturePlacement()
      if (!this.spaceTemplateCurrent && !this.enterDesign(zoneId)) return false
      this.objectUpdate(() => {
        const removedProducts = new Map<string, number>()
        for (const item of existing) if (proposal.replaceIds.includes(item.id) && item.productId) removedProducts.set(item.productId, (removedProducts.get(item.productId) || 0) + 1)
        this.objects = this.objects.filter(object => !proposal.replaceIds.includes(object.id))
        for (const furniture of proposal.furniture) this.objects.push({ ...furniture, id: uid('f'), spaceTemplateId: this.spaceTemplate!.templateId, templateSpaceId: zoneId })
        zone.layoutPreferences = JSON.parse(JSON.stringify(request.preferences))
        this.furnitureForZone(zoneId).forEach(item => { item.layoutConfirmed = false })
        this.submitted = null
        this.cart = this.cart.flatMap(item => {
          const removed = item.zoneId === zoneId ? removedProducts.get(item.productId) || 0 : 0
          if (!removed) return [item]
          const deduction = Math.min(item.qty, removed)
          removedProducts.set(item.productId, removed - deduction)
          return item.qty > deduction ? [{ ...item, qty: item.qty - deduction }] : []
        })
      })
      return true
    },

    layoutFurnitureTemplate(
      zoneId: string,
      templateId: string,
      widthMm: number,
      depthMm: number,
      arrangement: OfficeLayoutMode,
      spacing?: OfficeLayoutSpacing,
      meta?: OfficeLayoutSettingsMeta,
    ) {
      if (!this.canEnterLayout) return false
      const zone = this.zones.find(zone => zone.id === zoneId)
      const template = this.customModules.find(module => module.id === templateId) || furnitureById.get(templateId)
      if (!zone || !template) return false
      const placed = createTemplateFurniture(zone, template, widthMm, depthMm, arrangement, spacing, meta)
      if (!placed.length) return false
      this.cancelFurniturePlacement()
      if (!this.spaceTemplateCurrent && !this.enterDesign(zoneId)) return false
      placed.forEach(item => { item.spaceTemplateId = this.spaceTemplate!.templateId; item.templateSpaceId = zoneId })
      this.objectUpdate(() => {
        this.objects = this.objects.filter(object => !(object.kind === 'furniture' && object.zoneId === zoneId))
        this.objects.push(...placed)
      })
      return true
    },

    layoutFurniture(zoneId: string, productId: string, unitW: number, unitD: number, arrangement: string) {
      if (!this.canEnterLayout) return
      const zone = this.zones.find((z) => z.id === zoneId)
      const product = this.products.find((p) => p.id === productId) || SAMPLE_PRODUCTS.find((p) => p.id === productId)
      if (!zone || !product) return
      if (!this.spaceTemplateCurrent && !this.enterDesign(zoneId)) return
      const placed = createFurnitureObjects(zone, product.id, product.name, unitW, unitD, arrangement as any)
      placed.forEach(item => { item.spaceTemplateId = this.spaceTemplate!.templateId; item.templateSpaceId = zoneId })
      this.objectUpdate(() => {
        this.objects = this.objects.filter((o) => !(o.kind === 'furniture' && o.zoneId === zoneId))
        for (const f of placed) {
          f.layoutConfirmed = false
        }
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
