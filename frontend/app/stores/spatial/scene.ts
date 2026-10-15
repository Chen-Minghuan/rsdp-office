import { defineStore } from 'pinia'
import type { CameraTransform, DraftRequest, MutationData, SpatialSceneSnapshot, SpatialSpace } from '~/types/spatial'
import { useSpatialSceneApi } from '~/composables/spatial/useSpatialSceneApi'
import { fitCameraToBoundsBySystem, fromScreen, toScreen } from '~/utils/spatial/camera'
import { ensureVertexIds, inferRectangleFrame, moveVertex, polygonBounds, resizeRectangle, updateSpaceGeometry } from '~/utils/spatial/geometry'

export type SaveStatus = 'idle' | 'saving' | 'conflict' | 'error'

interface SpatialSceneState {
  scene: SpatialSceneSnapshot | null
  serverBaseline: SpatialSceneSnapshot | null
  workingDraft: SpatialSceneSnapshot | null
  pendingSourceScene: SpatialSceneSnapshot | null
  pendingSourceAction: 'replace' | 'merge' | null
  inFlight: boolean
  saveStatus: SaveStatus
  saveError: string | null
  camera: CameraTransform
  selectedSpaceId: string | null
  selectedVertexId: string | null
  calibrationStep: 'none' | 'first' | 'second'
  calibrationPoints: Array<{ x: number; y: number }>
}

function uid(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).slice(2, 8).toUpperCase()}`
}

export const useSpatialSceneStore = defineStore('spatialScene', {
  state: (): SpatialSceneState => ({
    scene: null,
    serverBaseline: null,
    workingDraft: null,
    pendingSourceScene: null,
    pendingSourceAction: null,
    inFlight: false,
    saveStatus: 'idle',
    saveError: null,
    camera: { zoom: 1, panX: 0, panY: 0 },
    selectedSpaceId: null,
    selectedVertexId: null,
    calibrationStep: 'none',
    calibrationPoints: [],
  }),

  getters: {
    currentScene: (state) => state.workingDraft || state.serverBaseline || state.scene,
    selectedSpace: (state): SpatialSpace | null => {
      const s = state.workingDraft || state.serverBaseline || state.scene
      if (!s || !state.selectedSpaceId) return null
      return s.spaces.find((sp) => sp.spaceId === state.selectedSpaceId) || null
    },
    isCalibrated: (state) => {
      const s = state.workingDraft || state.serverBaseline || state.scene
      return s?.workingCoordinateSystem === 'WORLD_MM'
    },
  },

  actions: {
    setScene(snapshot: SpatialSceneSnapshot) {
      this.scene = snapshot
      this.serverBaseline = snapshot
      this.workingDraft = JSON.parse(JSON.stringify(snapshot))
      this.fitCamera()
    },

    fitCamera() {
      const s = this.currentScene
      if (!s) return
      const bounds = sceneBounds(s, s.workingCoordinateSystem)
      this.camera = fitCameraToBoundsBySystem(bounds, 1200, 800, s.workingCoordinateSystem, 40)
    },

    /**
     * 重新识别后，不直接覆盖当前设计稿，而是进入待处理状态，由用户选择替换或合并。
     */
    proposeSourceReplace(newSourceScene: SpatialSceneSnapshot) {
      if (!this.workingDraft) {
        this.setScene(newSourceScene)
        return
      }
      this.pendingSourceScene = newSourceScene
      this.pendingSourceAction = null
      this.saveError = null
    },

    applySourceReplace(mode: 'replace' | 'merge') {
      if (!this.pendingSourceScene || !this.workingDraft) return
      const source = this.pendingSourceScene
      const draft = this.workingDraft

      if (mode === 'replace') {
        // 替换：保留当前校准、图层可用性等元数据，空间使用新的来源结果
        draft.spaces = source.spaces.map((sp) => ({ ...sp }))
        draft.sourceSceneId = source.sourceSceneId
        draft.scopeRevision += 1
      } else {
        // 合并：保留现有空间，新增不重叠的来源空间；重叠空间保留原设计并提示
        const existingIds = new Set(draft.spaces.map((s) => s.spaceId))
        const newSpaces = source.spaces.filter((sp) => !existingIds.has(sp.spaceId))
        draft.spaces.push(...newSpaces)
        draft.sourceSceneId = source.sourceSceneId
        draft.scopeRevision += 1
      }

      this.pendingSourceScene = null
      this.pendingSourceAction = null
      this.fitCamera()
    },

    dismissSourceReplace() {
      this.pendingSourceScene = null
      this.pendingSourceAction = null
    },

    selectSpace(id: string | null) {
      this.selectedSpaceId = id
      this.selectedVertexId = null
    },

    selectVertex(spaceId: string, vertexId: string | null) {
      this.selectedSpaceId = spaceId
      this.selectedVertexId = vertexId
    },

    resizeSelectedSpace(widthMm: number, depthMm: number, anchorId?: string) {
      const space = this.selectedSpace
      if (!space) return
      const frame = space.rectangleFrame || inferRectangleFrame(space.designGeometry)
      if (!frame) return
      const newGeometry = resizeRectangle(space.designGeometry, frame, widthMm, depthMm, anchorId)
      this.updateSpace(space.spaceId, updateSpaceGeometry(space, newGeometry))
    },

    moveSelectedVertex(x: number, y: number) {
      const space = this.selectedSpace
      if (!space || !this.selectedVertexId) return
      const newGeometry = moveVertex(space.designGeometry, this.selectedVertexId, x, y)
      this.updateSpace(space.spaceId, updateSpaceGeometry(space, newGeometry))
    },

    updateSpace(spaceId: string, space: SpatialSpace) {
      if (!this.workingDraft) return
      const idx = this.workingDraft.spaces.findIndex((s) => s.spaceId === spaceId)
      if (idx >= 0) {
        this.workingDraft.spaces[idx] = space
      }
    },

    async saveDraft() {
      const s = this.workingDraft
      if (!s || this.inFlight) return
      this.inFlight = true
      this.saveStatus = 'saving'
      this.saveError = null

      const api = useSpatialSceneApi()
      const request: DraftRequest = {
        schemaVersion: '1.2',
        baseRevision: this.serverBaseline!.revision,
        clientChangeId: uid('CHG'),
        workingCoordinateSystem: s.workingCoordinateSystem,
        calibrationRevision: s.calibration.revision,
        spaces: s.spaces.map((sp) => ({
          ...sp,
          designGeometry: ensureVertexIds(sp.designGeometry),
        })),
        walls: s.walls,
        openings: s.openings,
        obstacles: s.obstacles,
        annotations: s.annotations,
        boundaryRelations: s.boundaryRelations,
        issueDispositions: s.issueDispositions,
        deletions: s.tombstones.map((t) => ({ layer: t.layer, objectId: t.objectId, reason: t.reason })),
      }

      try {
        const res = await api.saveDraft(s.sceneId, request)
        this.applyMutation(res)
      } catch (e: any) {
        this.saveStatus = e.statusCode === 409 ? 'conflict' : 'error'
        this.saveError = e.message || '保存失败'
      } finally {
        this.inFlight = false
      }
    },

    async applyCalibration(referenceLengthMm: number) {
      const s = this.workingDraft
      if (!s || this.calibrationPoints.length !== 2 || this.inFlight) return
      this.inFlight = true
      const api = useSpatialSceneApi()
      try {
        const res = await api.applyCalibration(s.sceneId, {
          schemaVersion: '1.2',
          baseRevision: s.revision,
          clientChangeId: uid('CHG'),
          calibrationRevision: s.calibration.revision,
          sourcePageId: s.sourcePageId,
          referencePoints: this.calibrationPoints.map((p) => [p.x, p.y]),
          referenceLengthMm,
        })
        this.applyMutation(res)
        this.calibrationStep = 'none'
        this.calibrationPoints = []
      } catch (e: any) {
        this.saveError = e.message || '校准失败'
      } finally {
        this.inFlight = false
      }
    },

    async confirmSpaces(spaceIds?: string[]) {
      const s = this.workingDraft
      if (!s || this.inFlight) return
      this.inFlight = true
      const api = useSpatialSceneApi()
      const targets = spaceIds || s.spaces.filter((sp) => sp.includedInDesign).map((sp) => sp.spaceId)
      try {
        const res = await api.confirm(s.sceneId, {
          schemaVersion: '1.2',
          baseRevision: s.revision,
          clientChangeId: uid('CHG'),
          scope: targets.length === s.spaces.filter((sp) => sp.includedInDesign).length ? 'SCENE' : 'SPACES',
          calibrationRevision: s.calibration.revision,
          scopeRevision: s.scopeRevision,
          geometryPolicyVersion: 'GEOM-P1-1',
          spaces: targets.map((id) => {
            const sp = s.spaces.find((x) => x.spaceId === id)!
            return {
              spaceId: id,
              geometryRevision: sp.geometryRevision,
              requirementRevision: sp.requirementRevision,
            }
          }),
        })
        this.applyMutation(res)
      } catch (e: any) {
        this.saveError = e.message || '确认失败'
      } finally {
        this.inFlight = false
      }
    },

    applyMutation(res: MutationData) {
      this.serverBaseline = res.scene
      this.workingDraft = JSON.parse(JSON.stringify(res.scene))
      this.saveStatus = 'idle'
      this.saveError = null
    },

    resetCalibration() {
      this.calibrationStep = 'none'
      this.calibrationPoints = []
    },
  },
})

function sceneBounds(s: SpatialSceneSnapshot, coordinateSystem: 'SOURCE_PAGE' | 'WORLD_MM' = 'SOURCE_PAGE') {
  // 包围盒只用于定位标签、适屏或辅助操作，不作为房间轮廓
  let b = { minX: 0, minY: 0, maxX: 1000, maxY: 1000 }
  for (const sp of s.spaces) {
    const pb = polygonBounds(sp.designGeometry)
    if (!pb) continue
    b = {
      minX: Math.min(b.minX, pb.minX),
      minY: Math.min(b.minY, pb.minY),
      maxX: Math.max(b.maxX, pb.maxX),
      maxY: Math.max(b.maxY, pb.maxY),
    }
  }
  // 未校准时底图像素也参与适屏
  const underlay = s.underlays?.[0]
  if (underlay && coordinateSystem === 'SOURCE_PAGE') {
    b = {
      minX: Math.min(b.minX, 0),
      minY: Math.min(b.minY, 0),
      maxX: Math.max(b.maxX, underlay.widthPx),
      maxY: Math.max(b.maxY, underlay.heightPx),
    }
  }
  // 保证非退化
  if (b.maxX <= b.minX) b.maxX = b.minX + 1
  if (b.maxY <= b.minY) b.maxY = b.minY + 1
  return b
}
