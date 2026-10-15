import { defineStore } from 'pinia'
import type { ManualFloorPlanLayout } from '~/types/manualCanvas'
import { sampleManualLayout } from '~/utils/sample'

const STORAGE_KEY = 'rsdp-office-manual-canvas-v01'

interface ManualCanvasState {
  layout: ManualFloorPlanLayout
  initialized: boolean
}

function defaultLayout(): ManualFloorPlanLayout {
  return {
    version: 3,
    canvas: { widthMm: 16000, heightMm: 9000, gridMm: 100, mode: 'unbounded' },
    rooms: [],
    furniture: [],
  }
}

function loadLayout(): ManualFloorPlanLayout {
  if (typeof window === 'undefined') return defaultLayout()
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return defaultLayout()
    const parsed = JSON.parse(raw) as ManualFloorPlanLayout
    if (!parsed || !parsed.canvas || !Array.isArray(parsed.rooms)) return defaultLayout()
    parsed.version = 3
    parsed.canvas.mode = 'unbounded'
    return parsed
  } catch {
    return defaultLayout()
  }
}

export const useManualCanvasStore = defineStore('manualCanvas', {
  state: (): ManualCanvasState => ({
    layout: defaultLayout(),
    initialized: false,
  }),

  actions: {
    init() {
      if (this.initialized) return
      this.layout = loadLayout()
      if (!this.layout.rooms.length) {
        this.layout = sampleManualLayout()
        this.saveLocal()
      }
      this.initialized = true
    },

    saveLocal() {
      if (typeof window === 'undefined') return
      localStorage.setItem(STORAGE_KEY, JSON.stringify(this.layout))
    },

    updateLayout(next: ManualFloorPlanLayout) {
      this.layout = next
      this.saveLocal()
    },

    loadSample() {
      this.layout = sampleManualLayout()
      this.saveLocal()
    },

    clear() {
      this.layout = defaultLayout()
      this.saveLocal()
    },
  },
})
