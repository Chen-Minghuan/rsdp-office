<script setup lang="ts">
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js'
import type { FurnitureObject } from '~/types/office'
import { buildWhiteModelScene, facilityKindLabel, obstacleKindLabel, WHITE_MODEL_FLOOR_THICKNESS_MM, type WhiteModelScene } from '~/utils/whiteModel'
import { furnitureById } from '~/utils/manualFurniture'
import { officeFurnitureMoveProblem, officeFurnitureMoveWarning } from '~/utils/officeManualPlacement'
import { reconcileSelection, shouldStartDrag } from '~/utils/furniture3d/selection'
import { furnitureKeyboardDelta } from '~/utils/furniture3d/keyboard'
import { computeModelScale, resolveFurnitureModel } from '~/utils/furniture3d/modelRegistry'
import { disposeObjectTree, loadFurnitureModel, releaseFurnitureModelCache } from '~/utils/furniture3d/modelLoader'
import { buildProceduralFurniture, furnitureSeed, setGroupEmissive } from '~/utils/furniture3d/procedural'
import { buildWindowModel } from '~/utils/furniture3d/windowModel'
import { buildWallGeometry } from '~/utils/furniture3d/wallGeometry'
import { buildDoorModel } from '~/utils/furniture3d/doorModel'
import { workstationSeatGeometry, type WorkstationSeat } from '~/utils/furniture3d/workstationSeating'

/**
 * 交互式 3D 白模（RENDER-003 初版，RENDER-004 升级）。
 * 2D 无限画布是同一份设计几何的唯一事实来源；本组件只做渲染、命中与编辑入口，
 * 所有写回都走 store 的既有动作（moveObject / replaceFurnitureModule / 服务端选品链路）。
 */
const props = withDefaults(defineProps<{
  /** 缺省为当前设计空间；方案页等场景可传入任意空间。 */
  zoneId?: string
  /** 只读：可选中查看属性，禁止拖动/旋转/替换（方案确认页）。 */
  readonly?: boolean
  /** 内联嵌入（方案页标签页）；缺省为全屏浮层。 */
  inline?: boolean
}>(), { zoneId: '', readonly: false, inline: false })

const show = defineModel<boolean>('show', { default: false })
/** ai-render 携带当前视角截图（data:image/png;base64,...），作为 AI 效果图的结构参考（RENDER-006）。 */
const emit = defineEmits<{ (event: 'ai-render', viewImage: string): void; (event: 'locate', id: string): void }>()

const store = useOfficeStore()
const api = useOfficeApi()

const activeZoneId = computed(() => props.zoneId || store.designZoneId || '')
const zone = computed(() => store.zones.find(item => item.id === activeZoneId.value) || null)
const furniture = computed(() => store.objects.filter(item => item.kind === 'furniture' && item.zoneId === activeZoneId.value) as FurnitureObject[])
const ready = computed(() => !!zone.value && zone.value.reviewUnit === 'mm')
const sceneData = computed<WhiteModelScene | null>(() => zone.value && ready.value ? buildWhiteModelScene(zone.value, furniture.value) : null)
const sceneKey = computed(() => JSON.stringify(sceneData.value))
const selectedWorkstationSeat = computed(() => sceneData.value?.workstationSeats.find(seat => seat.deskId === selectedId.value))

const host = ref<HTMLDivElement | null>(null)
const initError = ref('')
const ready3d = ref(false)
const selectedId = ref('')
const hoverLabel = ref('')
const productsError = ref('')
const notice = ref('')
const showProductSelector = ref(false)

const selectedItem = computed(() => furniture.value.find(item => item.id === selectedId.value) || null)
// 列表按模块与布局规格合并职员桌，商品绑定/排版来源不拆行；属性仍读取实际选中实例。
const furnitureRows = computed(() => {
  const rows: Array<{ item: FurnitureObject; ids: string[]; label: string }> = []
  const groups = new Map<string, typeof rows[number]>()
  for (const item of furniture.value) {
    const template = item.furnitureTemplateId ? furnitureById.get(item.furnitureTemplateId) : undefined
    const staffDesk = (item.moduleSnapshot?.category || template?.category) === '职员桌'
    const key = staffDesk ? JSON.stringify([
      item.furnitureTemplateId || item.moduleSnapshot?.id, item.moduleSnapshot?.kind, item.moduleSnapshot?.glyphTemplateId,
      item.w, item.h, item.heightMm, item.wingWidthMm,
    ]) : item.id
    const existing = groups.get(key)
    if (existing) existing.ids.push(item.id)
    else {
      const row = { item, ids: [item.id], label: staffDesk ? item.moduleSnapshot?.name || template?.name || item.label || '职员桌' : item.label || '家具' }
      groups.set(key, row)
      rows.push(row)
    }
  }
  return rows
})

function selectFurnitureRow(row: typeof furnitureRows.value[number]) {
  // 从场景选中组内任一件后，点击已高亮行不跳回第一件，属性继续对应实际选中实例。
  if (!row.ids.includes(selectedId.value)) selectedId.value = row.item.id
}

const C = { x: (x: number) => x, z: (y: number) => -y } // 画布 (x, y 向下) → three (x, z=-y)

let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.PerspectiveCamera | null = null
let controls: OrbitControls | null = null
let root: THREE.Group | null = null
let groundMarker: THREE.Group | null = null
let selectionFrame: THREE.LineSegments | null = null
let sun: THREE.DirectionalLight | null = null
let fill: THREE.DirectionalLight | null = null
const furnitureMeshes = new Map<string, THREE.Object3D>()
/** 点击与拖动分离：按下只记录，超过阈值才进入拖动（只读模式不拖动）。 */
let pointerDown: { x: number; y: number; id: string } | null = null
let dragging: { id: string; dx: number; dz: number; originX: number; originZ: number } | null = null
let framedFloorKey = ''
let modelToken = 0
const raycaster = new THREE.Raycaster()
const pointerNdc = new THREE.Vector2()
const floorPlane = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0)
let resizeObserver: ResizeObserver | null = null

const MATERIALS = {
  /** 地板顶面：init 后挂程序化木地板纹理（世界毫米平铺）；未挂纹理前为纯色降级。 */
  floor: new THREE.MeshStandardMaterial({ color: 0xdcd4c3, roughness: 0.95 }),
  /** 地板断面（厚度侧面），比顶面深一档以强调楼板边界。 */
  floorSide: new THREE.MeshStandardMaterial({ color: 0xa79b88, roughness: 0.92 }),
  wall: new THREE.MeshStandardMaterial({ color: 0xfaf8f4, roughness: 0.9 }),
  furniture: new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.75 }),
  furnitureSelected: new THREE.MeshStandardMaterial({ color: 0xffd9ae, roughness: 0.7 }),
  column: new THREE.MeshStandardMaterial({ color: 0xd8d4cd, roughness: 0.9 }),
  obstacle: new THREE.MeshStandardMaterial({ color: 0xc9c4bc, roughness: 0.9 }),
  noPlacement: new THREE.MeshStandardMaterial({ color: 0xd97757, transparent: true, opacity: 0.35 }),
  doorClearance: new THREE.MeshStandardMaterial({ color: 0x4b8bc6, transparent: true, opacity: 0.4 }),
  window: new THREE.MeshStandardMaterial({ color: 0x9ec7e8, transparent: true, opacity: 0.55 }),
  region: new THREE.MeshBasicMaterial({ color: 0x8fbf8f, transparent: true, opacity: 0.25, side: THREE.DoubleSide }),
  clearance: new THREE.MeshBasicMaterial({ color: 0xd97757, transparent: true, opacity: 0.18, side: THREE.DoubleSide }),
  contactShadow: new THREE.MeshBasicMaterial({ color: 0x2a2419, transparent: true, opacity: 0.16 }),
  edge: new THREE.LineBasicMaterial({ color: 0x3f3a33 }),
  /** 选中轮廓：醒目描边，独立于家具材质，不写回设计数据。 */
  selectionEdge: new THREE.LineBasicMaterial({ color: 0xc2410c }),
  /** 底部选中标记：跟随家具位置/旋转与实际占地。 */
  markerFill: new THREE.MeshBasicMaterial({ color: 0xd97757, transparent: true, opacity: 0.22, side: THREE.DoubleSide }),
  markerEdge: new THREE.LineBasicMaterial({ color: 0xc2410c }),
  /** 命中代理：不可见，按业务占地整体命中，避免示意模型稀疏处（桌腿间隙等）点不中家具。 */
  hitProxy: new THREE.MeshBasicMaterial({ visible: false }),
}
const sharedMaterials = new Set<THREE.Material>(Object.values(MATERIALS))
/**
 * 程序化木地板纹理（RENDER-007 实时画质增强）：颜色 + 粗糙度两张 CanvasTexture。
 * 模块级缓存共享、不随场景释放；Node 单测无 document 时退化为 null（地板保持纯色）。
 * 一块纹理对应世界 3600mm 见方：ExtrudeGeometry 顶面 UV 即形状毫米坐标，
 * repeat 取 1/3600 实现按物理尺寸平铺，与视图缩放无关；块越大、逐板色差越小，
 * 平铺重复感越弱。
 */
const FLOOR_TILE_MM = 3600
let floorFinish: { map: THREE.Texture; roughnessMap: THREE.Texture } | null = null
function floorFinishTextures() {
  if (floorFinish || typeof document === 'undefined') return floorFinish
  const size = 1024
  const color = document.createElement('canvas')
  color.width = color.height = size
  const rough = document.createElement('canvas')
  rough.width = rough.height = size
  const c = color.getContext('2d')!
  const r = rough.getContext('2d')!
  let rand = 20261105
  const next = () => (rand = (rand * 16807) % 2147483647) / 2147483647
  const rows = 24 // 3600mm / 24 = 150mm 板宽
  const rowH = size / rows
  r.fillStyle = '#c8c8c8'
  r.fillRect(0, 0, size, size)
  for (let row = 0; row < rows; row++) {
    const y = row * rowH
    const tone = 0.92 + next() * 0.12 // 每块板明度微差（小对比，弱化平铺条带感）
    c.fillStyle = `rgb(${Math.round(196 * tone)},${Math.round(166 * tone)},${Math.round(124 * tone)})`
    c.fillRect(0, y, size, rowH)
    // 木纹丝（沿板长方向的浅波）
    for (let i = 0; i < 26; i++) {
      const gy = y + next() * rowH
      c.strokeStyle = `rgba(122,92,58,${0.04 + next() * 0.08})`
      c.lineWidth = 0.8 + next() * 1.6
      c.beginPath()
      c.moveTo(0, gy)
      c.bezierCurveTo(size * 0.3, gy + (next() - 0.5) * 6, size * 0.7, gy + (next() - 0.5) * 6, size, gy + (next() - 0.5) * 4)
      c.stroke()
    }
    // 板缝：横向通缝 + 错缝竖接头（板长约 390–1060mm）
    c.fillStyle = 'rgba(96,72,46,0.45)'
    c.fillRect(0, y, size, 1.6)
    r.fillStyle = 'rgba(110,110,110,0.55)'
    r.fillRect(0, y, size, 1.6)
    let x = next() * 300
    while (x < size) {
      c.fillRect(x, y, 1.6, rowH)
      r.fillRect(x, y, 1.6, rowH)
      x += 220 + next() * 380
    }
    // 粗糙度：逐板微差，板缝略糙
    r.fillStyle = `rgba(255,255,255,${(next() * 0.06).toFixed(3)})`
    r.fillRect(0, y, size, rowH)
  }
  for (let i = 0; i < 2600; i++) { // 细噪点防色带
    c.fillStyle = `rgba(90,66,40,${next() * 0.05})`
    c.fillRect(next() * size, next() * size, 1.4, 1.4)
  }
  const map = new THREE.CanvasTexture(color)
  map.wrapS = map.wrapT = THREE.RepeatWrapping
  map.colorSpace = THREE.SRGBColorSpace
  const roughnessMap = new THREE.CanvasTexture(rough)
  roughnessMap.wrapS = roughnessMap.wrapT = THREE.RepeatWrapping
  const scale = 1 / FLOOR_TILE_MM
  map.repeat.set(scale, scale)
  roughnessMap.repeat.set(scale, scale)
  floorFinish = { map, roughnessMap }
  return floorFinish
}

/** 渲染器就绪后挂地板纹理（需要各向异性上限）；重复调用幂等。 */
function applyFloorFinish(activeRenderer: THREE.WebGLRenderer) {
  const finish = floorFinishTextures()
  if (!finish) return
  const anisotropy = Math.min(8, activeRenderer.capabilities.getMaxAnisotropy())
  finish.map.anisotropy = anisotropy
  finish.roughnessMap.anisotropy = anisotropy
  MATERIALS.floor.map = finish.map
  MATERIALS.floor.roughnessMap = finish.roughnessMap
  MATERIALS.floor.color.setHex(0xffffff)
  MATERIALS.floor.roughness = 1
  MATERIALS.floor.needsUpdate = true
}

/** 场景背景：极浅的纵向渐变，避免纯平色；画布外区域不影响 AI 截图语义。 */
let backgroundTexture: THREE.Texture | null = null
function backgroundGradient() {
  if (backgroundTexture || typeof document === 'undefined') return backgroundTexture
  const canvas = document.createElement('canvas')
  canvas.width = 2
  canvas.height = 512
  const ctx = canvas.getContext('2d')!
  const gradient = ctx.createLinearGradient(0, 0, 0, 512)
  gradient.addColorStop(0, '#f6f3ed')
  gradient.addColorStop(1, '#e2ddd2')
  ctx.fillStyle = gradient
  ctx.fillRect(0, 0, 2, 512)
  backgroundTexture = new THREE.CanvasTexture(canvas)
  backgroundTexture.colorSpace = THREE.SRGBColorSpace
  return backgroundTexture
}

const FACILITY_COLORS: Record<string, number> = {
  FIRE_FACILITY: 0xc45a3a, ELECTRICAL_BOX: 0xc99a2e, SKIRTING: 0x9a938a, POWER_OUTLET: 0x4b8bc6, DATA_OUTLET: 0x6a5acd,
}
const facilityMaterials = new Map<number, THREE.MeshStandardMaterial>()
function facilityMaterial(color: number) {
  let material = facilityMaterials.get(color)
  if (!material) { material = new THREE.MeshStandardMaterial({ color, roughness: 0.7 }); facilityMaterials.set(color, material) }
  return material
}

function edgeLines(mesh: THREE.Mesh, geometry: THREE.BufferGeometry) {
  const lines = new THREE.LineSegments(new THREE.EdgesGeometry(geometry), MATERIALS.edge)
  mesh.add(lines)
}

/** 给家具加不可见命中代理（业务占地整体），点击/悬停按实例归属识别，跟随父对象移动旋转。 */
function addHitProxy(target: THREE.Object3D, volume: { id: string; label: string; w: number; d: number; heightMm: number }, centerY: number) {
  const proxy = new THREE.Mesh(new THREE.BoxGeometry(volume.w, volume.heightMm, volume.d), MATERIALS.hitProxy)
  proxy.position.y = centerY
  proxy.userData = { id: volume.id, source: 'furniture', label: volume.label }
  target.add(proxy)
}

function addWorkstationSeat(target: THREE.Object3D, seat: WorkstationSeat) {
  if (seat.existingChairId) return
  const chair = buildProceduralFurniture({ kind: 'chair', w: seat.w, d: seat.d, heightMm: seat.heightMm, seed: furnitureSeed(seat.deskId) })
  if (!chair) return
  // 配椅归属原工位，不生成第二份业务实例；真实模型缩放不改变配椅毫米尺寸。
  const scale = target.scale.x || 1
  chair.scale.setScalar(1 / scale)
  chair.position.set(seat.x / scale, -target.position.y / scale, -seat.y / scale)
  chair.userData = { companionSeat: true, deskId: seat.deskId }
  addHitProxy(chair, { id: seat.deskId, label: `${target.userData.label || '工位'} · 配套办公椅`, w: seat.w, d: seat.d, heightMm: seat.heightMm }, seat.heightMm / 2)
  target.add(chair)
}

function polygonShape(ring: Array<[number, number]>) {
  const shape = new THREE.Shape()
  ring.forEach(([x, y], i) => (i === 0 ? shape.moveTo(x, y) : shape.lineTo(x, y)))
  shape.closePath()
  return shape
}

function rebuild() {
  if (!root || !sceneData.value) return
  for (const child of [...root.children]) disposeObjectTree(child, sharedMaterials)
  root.clear()
  furnitureMeshes.clear()
  modelToken++
  const data = sceneData.value

  // 地板与墙体
  // ExtrudeGeometry 分组 0=顶/底面、1=侧面 → [木地板, 断面深色]
  const floorGeo = new THREE.ExtrudeGeometry(polygonShape(data.floor), { depth: WHITE_MODEL_FLOOR_THICKNESS_MM, bevelEnabled: false })
  const floor = new THREE.Mesh(floorGeo, [MATERIALS.floor, MATERIALS.floorSide])
  floor.geometry.rotateX(-Math.PI / 2)
  floor.position.y = -WHITE_MODEL_FLOOR_THICKNESS_MM
  floor.receiveShadow = true
  root.add(floor)
  for (const wall of data.walls) {
    const length = Math.hypot(wall.b[0] - wall.a[0], wall.b[1] - wall.a[1])
    if (length < 1) continue
    const geo = buildWallGeometry(wall)
    const mesh = new THREE.Mesh(geo, MATERIALS.wall)
    mesh.position.set(C.x(wall.a[0]), 0, C.z(wall.a[1]))
    mesh.rotation.y = Math.atan2(wall.b[1] - wall.a[1], wall.b[0] - wall.a[0])
    mesh.castShadow = true
    mesh.receiveShadow = true
    edgeLines(mesh, geo)
    root.add(mesh)
  }
  for (const window of data.windows) root.add(buildWindowModel(window))
  for (const door of data.doors) root.add(buildDoorModel(door))

  // 布置区（仅展示）
  for (const region of data.regions) {
    const geo = new THREE.ShapeGeometry(polygonShape(region))
    geo.rotateX(-Math.PI / 2)
    const mesh = new THREE.Mesh(geo, MATERIALS.region)
    mesh.position.y = 2
    root.add(mesh)
  }

  // 家具与障碍体积
  for (const volume of data.volumes) {
    let mesh: THREE.Mesh
    if (volume.ring && volume.ring.length >= 3) {
      const geo = new THREE.ExtrudeGeometry(polygonShape(volume.ring), { depth: volume.heightMm, bevelEnabled: false })
      geo.rotateX(-Math.PI / 2)
      mesh = new THREE.Mesh(geo, MATERIALS[volume.tone])
      mesh.position.y = volume.baseMm
    } else {
      const geo = new THREE.BoxGeometry(volume.w, volume.heightMm, volume.d)
      mesh = new THREE.Mesh(geo, volume.source === 'furniture' && volume.id === selectedId.value ? MATERIALS.furnitureSelected : MATERIALS[volume.tone])
      mesh.position.set(C.x(volume.cx), volume.baseMm + volume.heightMm / 2, C.z(volume.cy))
      // 画布 (x,y 向下) → three (x,z=-y) 的映射本身带镜像，顺时针画布角 = 正 rotation.y
      // （与墙体 atan2(dy,dx) 同号）；取反会让 90/270° 的异形家具朝向反转。
      mesh.rotation.y = (volume.rotDeg * Math.PI) / 180
      edgeLines(mesh, geo)
      if (volume.source === 'furniture') {
        // 接地阴影板：让白色家具从地板中分离出来
        const pad = new THREE.Mesh(new THREE.BoxGeometry(volume.w + 90, 15, volume.d + 90), MATERIALS.contactShadow)
        pad.position.set(C.x(volume.cx), 8, C.z(volume.cy))
        pad.rotation.y = mesh.rotation.y
        root!.add(pad)
      }
    }
    mesh.userData = { id: volume.id, source: volume.source, label: volume.label, w: volume.w, d: volume.d, h: volume.heightMm }
    root.add(mesh)
    if (volume.source === 'furniture') {
      // 类别示意模型优先于白模方块（真实 GLB 在 upgradeFurnitureModels 中再覆盖）
      const item = furniture.value.find(entry => entry.id === volume.id)
      const kind = item?.moduleSnapshot?.kind || (item?.furnitureTemplateId ? furnitureById.get(item.furnitureTemplateId)?.kind : undefined)
      const referenceL = item && kind && ['l-desk', 'rounded-l-desk'].includes(kind) && data.workstationSeats.some(seat => seat.deskId === item.id) ? workstationSeatGeometry(item, kind) : null
      const model = kind ? buildProceduralFurniture({ kind, w: volume.w, d: volume.d, heightMm: volume.heightMm, screen: !!item?.furnitureTemplateId?.includes('screen'), mirrorX: item?.mirrorX, seed: furnitureSeed(item?.id), referenceL: referenceL || undefined }) : null
      if (model) {
        model.position.set(C.x(volume.cx), 0, C.z(volume.cy))
        model.rotation.y = mesh.rotation.y
        model.userData = { ...mesh.userData, procedural: true }
        root.remove(mesh)
        disposeObjectTree(mesh, sharedMaterials)
        root.add(model)
        addHitProxy(model, volume, volume.heightMm / 2) // 组原点落地，代理居中到半高
        furnitureMeshes.set(volume.id, model)
      } else {
        mesh.castShadow = true
        mesh.receiveShadow = true
        addHitProxy(mesh, volume, 0) // 方块网格原点即中心
        furnitureMeshes.set(volume.id, mesh)
      }
    } else {
      mesh.castShadow = true
      mesh.receiveShadow = true
    }
  }

  for (const seat of data.workstationSeats) {
    const desk = furnitureMeshes.get(seat.deskId)
    if (desk) addWorkstationSeat(desk, seat)
  }

  // 设施标注
  for (const facility of data.facilities) {
    const material = facilityMaterial(FACILITY_COLORS[facility.kind] ?? 0x9a938a)
    if (facility.shape === 'point') {
      const [x, y] = facility.points[0]!
      const w = facility.sizeMm?.widthMm ?? 120
      const d = facility.sizeMm?.depthMm ?? 120
      const mesh = new THREE.Mesh(new THREE.BoxGeometry(w, 300, d), material)
      mesh.position.set(C.x(x), 150, C.z(y))
      mesh.userData = { id: facility.id, source: 'facility', label: facility.label }
      root.add(mesh)
    } else if (facility.shape === 'polyline') {
      for (let i = 0; i < facility.points.length - 1; i++) {
        const [ax, ay] = facility.points[i]!, [bx, by] = facility.points[i + 1]!
        const length = Math.hypot(bx - ax, by - ay)
        if (length < 1) continue
        const mesh = new THREE.Mesh(new THREE.BoxGeometry(length, 100, 40), material)
        mesh.position.set(C.x((ax + bx) / 2), 50, C.z((ay + by) / 2))
        mesh.rotation.y = Math.atan2(by - ay, bx - ax)
        root.add(mesh)
      }
    } else {
      const geo = new THREE.ExtrudeGeometry(polygonShape(facility.points), { depth: 1200, bevelEnabled: false })
      geo.rotateX(-Math.PI / 2)
      const mesh = new THREE.Mesh(geo, material)
      mesh.userData = { id: facility.id, source: 'facility', label: facility.label }
      root.add(mesh)
      if (facility.clearanceMm && facility.clearanceMm > 0) {
        const cx = facility.points.reduce((s, p) => s + p[0], 0) / facility.points.length
        const cy = facility.points.reduce((s, p) => s + p[1], 0) / facility.points.length
        const ring = new THREE.Mesh(new THREE.RingGeometry(Math.max(60, facility.clearanceMm - 60), facility.clearanceMm, 48), MATERIALS.clearance)
        ring.rotation.x = -Math.PI / 2
        ring.position.set(C.x(cx), 3, C.z(cy))
        root.add(ring)
      }
    }
  }

  // 仅在空间轮廓变化时重置相机，家具编辑/选中不重置视角
  const floorKey = JSON.stringify(data.floor)
  if (floorKey !== framedFloorKey) { frameCamera(data); framedFloorKey = floorKey }
  syncSelectionVisuals()
  void upgradeFurnitureModels(data)
}

/** P2：已注册 GLB 模型时替换白模方块；未注册或加载失败保持方块（示意/真实在属性层区分）。 */
async function upgradeFurnitureModels(data: WhiteModelScene) {
  const token = modelToken
  for (const volume of data.volumes) {
    if (volume.source !== 'furniture') continue
    const item = furniture.value.find(entry => entry.id === volume.id)
    const resolved = resolveFurnitureModel({ productId: item?.productId }, item?.furnitureTemplateId ? furnitureById.get(item.furnitureTemplateId)?.kind : undefined)
    if (!resolved) continue
    const scaleInfo = computeModelScale(resolved.asset, { widthMm: volume.w, depthMm: volume.d })
    if (!scaleInfo) continue
    try {
      const model = await loadFurnitureModel(resolved.asset.url)
      if (token !== modelToken || !root) continue // 场景已重建或销毁，丢弃过期结果
      const box = furnitureMeshes.get(volume.id)
      if (!box) continue
      model.scale.setScalar(scaleInfo.scale)
      model.position.set(box.position.x, 0, box.position.z) // 锚点约定：底面中心落地
      model.rotation.y = box.rotation.y
      model.userData = { ...box.userData, schematic: resolved.schematic, modelKey: resolved.asset.key }
      root.remove(box)
      disposeObjectTree(box, sharedMaterials)
      root.add(model)
      addHitProxy(model, volume, volume.heightMm / 2)
      const seat = data.workstationSeats.find(seat => seat.deskId === volume.id)
      if (seat) addWorkstationSeat(model, seat)
      furnitureMeshes.set(volume.id, model)
    } catch {
      // 模型缺失/加载失败：回退白模方块，不阻断交互
    }
  }
}

function frameCamera(data: WhiteModelScene) {
  if (!camera || !controls) return
  const cx = (data.bounds.minX + data.bounds.maxX) / 2
  const cy = (data.bounds.minY + data.bounds.maxY) / 2
  const span = Math.max(data.bounds.maxX - data.bounds.minX, data.bounds.maxY - data.bounds.minY, 2000)
  camera.position.set(C.x(cx) + span * 0.55, span * 1.5, C.z(cy) + span * 0.75)
  controls.target.set(C.x(cx), 0, C.z(cy))
  controls.update()
  // 平行光跟随空间取景，阴影相机覆盖整个空间
  if (sun) {
    sun.position.set(C.x(cx) + span * 0.5, span * 1.2, C.z(cy) + span * 0.4)
    sun.target.position.set(C.x(cx), 0, C.z(cy))
    const shadowCam = sun.shadow.camera
    shadowCam.left = -span * 0.8; shadowCam.right = span * 0.8
    shadowCam.top = span * 0.8; shadowCam.bottom = -span * 0.8
    shadowCam.near = span * 0.1; shadowCam.far = span * 3.5
    shadowCam.updateProjectionMatrix()
  }
  if (fill) {
    fill.position.set(C.x(cx) - span * 0.6, span * 0.9, C.z(cy) - span * 0.55)
    fill.target.position.set(C.x(cx), 0, C.z(cy))
  }
}

/** 沿父链找到带业务 ID 的命中对象（GLB 子 Mesh 也归属同一家具实例）。 */
function pickTarget(event: PointerEvent): THREE.Object3D | null {
  if (!renderer || !camera || !root) return null
  const rect = renderer.domElement.getBoundingClientRect()
  pointerNdc.set(((event.clientX - rect.left) / rect.width) * 2 - 1, -((event.clientY - rect.top) / rect.height) * 2 + 1)
  raycaster.setFromCamera(pointerNdc, camera)
  const hits = raycaster.intersectObjects(root.children, true)
  for (const hit of hits) {
    let object: THREE.Object3D | null = hit.object
    while (object) {
      if (object.userData?.id) return object
      object = object.parent
    }
  }
  return null
}

function onPointerDown(event: PointerEvent) {
  if (event.button !== 0) return
  renderer?.domElement.focus({ preventScroll: true })
  const target = pickTarget(event)
  const id = (target?.userData?.id || '') as string
  pointerDown = { x: event.clientX, y: event.clientY, id: furnitureMeshes.has(id) ? id : '' }
}

function onPointerMove(event: PointerEvent) {
  if (!renderer || !camera || !root) return
  const rect = renderer.domElement.getBoundingClientRect()
  pointerNdc.set(((event.clientX - rect.left) / rect.width) * 2 - 1, -((event.clientY - rect.top) / rect.height) * 2 + 1)
  raycaster.setFromCamera(pointerNdc, camera)
  if (pointerDown && !dragging && pointerDown.id && !props.readonly && shouldStartDrag(pointerDown.x, pointerDown.y, event.clientX, event.clientY)) {
    const mesh = furnitureMeshes.get(pointerDown.id)
    if (mesh) {
      selectedId.value = pointerDown.id // 拖动即选中，与 2D 行为一致
      const point = new THREE.Vector3()
      raycaster.ray.intersectPlane(floorPlane, point)
      dragging = { id: pointerDown.id, dx: mesh.position.x - point.x, dz: mesh.position.z - point.z, originX: mesh.position.x, originZ: mesh.position.z }
      controls!.enabled = false
      renderer.domElement.setPointerCapture(event.pointerId)
    }
  }
  if (dragging) {
    const point = new THREE.Vector3()
    if (raycaster.ray.intersectPlane(floorPlane, point)) {
      const mesh = furnitureMeshes.get(dragging.id)!
      mesh.position.set(point.x + dragging.dx, mesh.position.y, point.z + dragging.dz)
      syncGroundMarker()
    }
    return
  }
  const target = pickTarget(event)
  hoverLabel.value = (target?.userData?.label || '') as string
  renderer.domElement.style.cursor = target && furnitureMeshes.has(target.userData.id) ? 'pointer' : ''
}

function onPointerUp(event: PointerEvent) {
  if (dragging) {
    const { id, originX, originZ } = dragging
    dragging = null
    pointerDown = null
    if (controls) controls.enabled = true
    renderer?.domElement.releasePointerCapture(event.pointerId)
    commitDrag(id, originX, originZ)
    return
  }
  // 未越过阈值：视为点击——选中家具 / 点空白（或障碍、设施）取消选中
  if (pointerDown && !shouldStartDrag(pointerDown.x, pointerDown.y, event.clientX, event.clientY)) {
    selectedId.value = pointerDown.id
  }
  pointerDown = null
}

function commitDrag(id: string, originX: number, originZ: number) {
  const mesh = furnitureMeshes.get(id)
  const item = furniture.value.find(entry => entry.id === id)
  if (!mesh || !item || !zone.value) return
  // 写回同一份设计几何：three → 画布坐标（含旋转后的占地近似仍以轴对齐记录）
  const cx = mesh.position.x, cy = -mesh.position.z
  const nx = Math.round(cx - item.w / 2), ny = Math.round(cy - item.h / 2)
  if (nx === Math.round(item.x) && ny === Math.round(item.y)) return
  // 与 2D 拖放同一套校验：越界/重叠/禁放区等硬冲突拒绝并回退，软提示放行
  const problem = officeFurnitureMoveProblem(zone.value, [{ ...item, x: nx, y: ny }], furniture.value)
  if (problem) {
    mesh.position.set(originX, mesh.position.y, originZ)
    syncGroundMarker()
    notice.value = `未移动「${item.label || '家具'}」：${problem}`
    return
  }
  store.moveObject(id, nx, ny)
  const moved = furniture.value.find(entry => entry.id === id)
  const warning = moved ? officeFurnitureMoveWarning(zone.value, [moved]) : null
  notice.value = `已移动「${item.label || '家具'}」到 (${nx}, ${ny})，与 2D 排版同步${warning ? `；提示：${warning}，确认排版时会被拦截` : ''}`
}

function onKeydown(event: KeyboardEvent) {
  if (event.defaultPrevented || !show.value || props.readonly || !ready3d.value || !camera || !zone.value
    || dragging || pointerDown || showProductSelector.value) return
  const target = event.target instanceof HTMLElement ? event.target : null
  if (target?.isContentEditable || target?.closest('input, textarea, select, [role="textbox"], [role="combobox"], [role="spinbutton"]')) return
  if ((event.ctrlKey || event.metaKey) && !event.altKey && !event.shiftKey && event.key.toLowerCase() === 'z') {
    event.preventDefault()
    event.stopPropagation()
    if (store.undoStack.length) { store.undo(); notice.value = '已撤销上一步修改，与 2D 排版同步' }
    return
  }
  const item = selectedItem.value
  if (!item) return
  // three 的相机右向量 → 画布地面方向；不随家具自身旋转改变按键方向。
  const right = new THREE.Vector3().setFromMatrixColumn(camera.matrixWorld, 0)
  const delta = furnitureKeyboardDelta(event, { x: right.x, y: -right.z })
  if (!delta) return
  event.preventDefault()
  event.stopPropagation()
  const { x, y, id } = item
  const nx = Math.round((x + delta.dx) * 100) / 100, ny = Math.round((y + delta.dy) * 100) / 100
  // 复用带校验与撤销快照的移动动作，同空间确认和方案失效由 store 处理。
  const result = store.moveFurnitureGroup([id], zone.value.id, nx - x, ny - y)
  const moved = furniture.value.find(entry => entry.id === id)
  notice.value = moved && (moved.x !== x || moved.y !== y)
    ? `已移动「${item.label || '家具'}」，与 2D 排版同步，可撤销${result ? `；${result.replace(/^已移动；/, '')}` : ''}`
    : `未移动「${item.label || '家具'}」：${result || '位置未改变'}`
}

function rotateSelected() {
  const item = selectedItem.value
  if (!item || props.readonly || !zone.value) return
  // 与 2D 同一套旋转：越界/重叠校验失败不改动，确认失效与撤销语义一致
  const result = store.rotateFurnitureGroup([item.id], zone.value.id)
  notice.value = result || `已旋转「${item.label || '家具'}」90°，与 2D 排版同步`
}

function replaceModule(templateId: string) {
  const item = selectedItem.value
  if (!item || props.readonly) return
  // 复用 2D 排版同一套带校验的模块替换：保留 ID/中心/旋转，失败不改旧家具，成功清除旧选品并使确认失效
  const result = store.replaceFurnitureModule(item.id, templateId)
  notice.value = result || `已替换模块，位置与旋转保持不变；旧商品绑定已清除，确认状态失效，可撤销`
}

function onProductApplied(message: string) {
  notice.value = message
}

function locateSelected() {
  const item = selectedItem.value
  if (!item) return
  emit('locate', item.id)
}

/**
 * 截取当前视角供 AI 效果图作结构参考：先隐藏选中提亮/占地标记/轮廓框等交互叠加物，
 * 同步渲染一帧后立即读取像素（无需 preserveDrawingBuffer），再恢复选中视觉。不改任何设计数据。
 */
function captureViewImage(): string | null {
  if (!renderer || !scene || !camera) return null
  const selected = selectedId.value ? furnitureMeshes.get(selectedId.value) : null
  if (groundMarker) groundMarker.visible = false
  if (selectionFrame) selectionFrame.visible = false
  if (selected) {
    if (selected.userData.procedural) setGroupEmissive(selected, 0)
    else if ((selected as THREE.Mesh).isMesh) (selected as THREE.Mesh).material = MATERIALS.furniture
  }
  try {
    renderer.render(scene, camera)
    return renderer.domElement.toDataURL('image/png')
  } catch {
    return null
  } finally {
    syncSelectionVisuals(false)
  }
}

function openAiRender() {
  const image = captureViewImage()
  if (!image) { notice.value = '当前 3D 场景尚未就绪，无法截取视角，请稍后重试'; return }
  emit('ai-render', image)
}

/** 选中视觉：材质提亮/自发光 + 占地轮廓框 + 底部选中标记（均不写回设计数据）。 */
function syncSelectionVisuals(rebuildGeometry = true) {
  for (const [meshId, mesh] of furnitureMeshes) {
    const selected = meshId === selectedId.value
    if (mesh.userData.procedural) setGroupEmissive(mesh, selected ? 0x4a3a14 : 0)
    else if ((mesh as THREE.Mesh).isMesh) (mesh as THREE.Mesh).material = selected ? MATERIALS.furnitureSelected : MATERIALS.furniture
  }
  syncGroundMarker(rebuildGeometry)
}

/** 底部选中标记 + 占地轮廓框：跟随家具位置、旋转与实际占地（含拖动过程）。 */
function syncGroundMarker(rebuildGeometry = false) {
  if (!groundMarker || !selectionFrame) return
  const mesh = furnitureMeshes.get(selectedId.value)
  const item = selectedItem.value
  if (!mesh || !item) { groundMarker.visible = false; selectionFrame.visible = false; return }
  if (rebuildGeometry) {
    for (const child of [...groundMarker.children]) { groundMarker.remove(child); disposeObjectTree(child, sharedMaterials) }
    const w = item.w + 140, d = item.h + 140
    const fill = new THREE.Mesh(new THREE.PlaneGeometry(w, d), MATERIALS.markerFill)
    fill.rotation.x = -Math.PI / 2
    const halfW = w / 2, halfD = d / 2
    const outlineGeo = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(-halfW, 0, -halfD), new THREE.Vector3(halfW, 0, -halfD),
      new THREE.Vector3(halfW, 0, halfD), new THREE.Vector3(-halfW, 0, halfD),
    ])
    groundMarker.add(fill, new THREE.LineLoop(outlineGeo, MATERIALS.markerEdge))
    // 轮廓框：家具占地的立体描边（白模方块与示意模型通用）
    const h = (mesh.userData.h as number) || 750
    selectionFrame.geometry.dispose()
    selectionFrame.geometry = new THREE.EdgesGeometry(new THREE.BoxGeometry(item.w + 36, h + 36, item.h + 36))
    selectionFrame.userData.frameHeight = h + 36
  }
  groundMarker.visible = true
  groundMarker.position.set(mesh.position.x, 6, mesh.position.z)
  groundMarker.rotation.y = mesh.rotation.y
  selectionFrame.visible = true
  selectionFrame.position.set(mesh.position.x, ((selectionFrame.userData.frameHeight as number) || 786) / 2, mesh.position.z)
  selectionFrame.rotation.y = mesh.rotation.y
}

async function init() {
  if (!host.value || renderer) return
  try {
    renderer = new THREE.WebGLRenderer({ antialias: true })
  } catch {
    initError.value = '当前浏览器不支持 WebGL，无法渲染 3D 场景。'
    return
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = THREE.PCFSoftShadowMap
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.06
  renderer.domElement.style.display = 'block'
  host.value.appendChild(renderer.domElement)
  scene = new THREE.Scene()
  scene.background = backgroundGradient() ?? new THREE.Color(0xfaf8f5)
  applyFloorFinish(renderer)
  // 环境贴图（程序化 RoomEnvironment，无外部资源）：木纹/金属/织物质感的主要来源
  const pmrem = new THREE.PMREMGenerator(renderer)
  scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture
  scene.environmentIntensity = 0.42 // 主要提供高光反射；过高会洗白固有色
  pmrem.dispose()
  camera = new THREE.PerspectiveCamera(45, 1, 10, 200000)
  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.maxPolarAngle = Math.PI / 2 - 0.02

  scene.add(new THREE.HemisphereLight(0xffffff, 0xcfc8bd, 1.0))
  scene.add(new THREE.AmbientLight(0xffffff, 0.15))
  sun = new THREE.DirectionalLight(0xfff2e0, 2.6)
  sun.castShadow = true
  sun.shadow.mapSize.set(2048, 2048)
  sun.shadow.bias = -0.0004
  scene.add(sun)
  scene.add(sun.target)
  // 冷色补光（不投影）：打开背光面暗部，避免大片死黑，方向在 frameCamera 中与太阳相对
  fill = new THREE.DirectionalLight(0xdfe6f2, 0.55)
  scene.add(fill)
  scene.add(fill.target)
  root = new THREE.Group()
  scene.add(root)
  groundMarker = new THREE.Group()
  groundMarker.visible = false
  scene.add(groundMarker)
  selectionFrame = new THREE.LineSegments(new THREE.BufferGeometry(), MATERIALS.selectionEdge)
  selectionFrame.visible = false
  scene.add(selectionFrame)

  renderer.domElement.addEventListener('pointerdown', onPointerDown)
  renderer.domElement.tabIndex = 0
  renderer.domElement.setAttribute('aria-label', '3D 场景，选中家具后可用方向键移动')
  renderer.domElement.addEventListener('pointermove', onPointerMove)
  renderer.domElement.addEventListener('pointerup', onPointerUp)

  const resize = () => {
    if (!host.value || !renderer || !camera) return
    const { clientWidth: w, clientHeight: h } = host.value
    if (w && h) { renderer.setSize(w, h); camera.aspect = w / h; camera.updateProjectionMatrix() }
  }
  resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(host.value)
  resize()
  renderer.setAnimationLoop(() => { controls?.update(); if (renderer && scene && camera) renderer.render(scene, camera) })
  // e2e 测试钩子：家具中心点（或 'point:x,y' 画布坐标）的屏幕坐标（点击/拖动仿真用）。
  ;(window as any).__whiteModelProject = (id: string) => {
    if (!camera || !renderer) return null
    let world: THREE.Vector3
    if (id.startsWith('point:')) {
      const [x, y] = id.slice(6).split(',').map(Number)
      if (!Number.isFinite(x) || !Number.isFinite(y)) return null
      world = new THREE.Vector3(C.x(x!), 0, C.z(y!))
    } else if (id.startsWith('seat:')) {
      const mesh = furnitureMeshes.get(id.slice(5))
      const chair = mesh?.children.find(child => child.userData.companionSeat)
      if (!chair) return null
      world = chair.getWorldPosition(new THREE.Vector3())
      world.y += 450
    } else {
      const mesh = furnitureMeshes.get(id)
      if (!mesh) return null
      world = mesh.position.clone()
    }
    const v = world.project(camera)
    const rect = renderer.domElement.getBoundingClientRect()
    return { x: rect.left + ((v.x + 1) / 2) * rect.width, y: rect.top + ((-v.y + 1) / 2) * rect.height }
  }
  ready3d.value = true
  rebuild()
}

function dispose() {
  delete (window as any).__whiteModelProject
  modelToken++
  resizeObserver?.disconnect()
  resizeObserver = null
  renderer?.setAnimationLoop(null)
  if (root) { for (const child of [...root.children]) disposeObjectTree(child, sharedMaterials); root.clear() }
  if (groundMarker) { for (const child of [...groundMarker.children]) disposeObjectTree(child, sharedMaterials) }
  selectionFrame?.geometry.dispose()
  if (scene) { scene.environment = null; scene.background = null }
  void releaseFurnitureModelCache()
  renderer?.dispose()
  renderer?.domElement.remove()
  renderer = null; scene = null; camera = null; controls = null; root = null; groundMarker = null; selectionFrame = null; sun = null; fill = null
  furnitureMeshes.clear(); dragging = null; pointerDown = null; ready3d.value = false
}

watch(show, async value => {
  if (value) {
    initError.value = ''; notice.value = ''; selectedId.value = ''
    framedFloorKey = ''
    if (store.products.length === 0) {
      try { store.products = await api.loadProducts() } catch { productsError.value = '商品列表加载失败，仅影响商品信息展示与替换' }
    }
    await nextTick()
    await init()
  } else dispose()
}, { immediate: true })

watch(sceneKey, () => { if (ready3d.value) rebuild() })
watch(selectedId, () => syncSelectionVisuals())
// 家具被删除或切换空间后清理选中，属性不串到其他空间
watch(() => furniture.value.map(item => item.id).join(','), ids => {
  selectedId.value = reconcileSelection(selectedId.value, ids ? ids.split(',') : [])
})
watch(activeZoneId, () => { selectedId.value = ''; notice.value = ''; showProductSelector.value = false })

onBeforeUnmount(dispose)

const legend = [
  ['white', '家具 / 墙体'],
  ['#d8d4cd', obstacleKindLabel.COLUMN + ' / 井道'],
  ['#d97757', obstacleKindLabel.NO_PLACEMENT],
  ['#4b8bc6', obstacleKindLabel.DOOR_CLEARANCE],
  ['#9ec7e8', obstacleKindLabel.WINDOW],
  ['#8fbf8f', '家具布置区'],
] as const
</script>

<template>
  <Teleport to="body" :disabled="inline">
    <div v-if="show" :class="inline
      ? 'relative flex h-full min-h-[320px] w-full flex-col bg-[#faf8f5]'
      : 'fixed inset-0 z-[90] flex flex-col bg-[#faf8f5]'"
      data-testid="white-model-preview"
      @keydown="onKeydown"
      :data-ready="ready3d || undefined"
      :data-selected="selectedId || undefined"
      :data-readonly="readonly || undefined"
      :data-furniture="sceneData?.volumes.filter(v => v.source === 'furniture').length ?? 0"
      :data-default-seats="sceneData?.workstationSeats.filter(seat => !seat.existingChairId).length ?? 0"
      :data-obstacles="(sceneData?.volumes.filter(v => v.source === 'obstacle').length ?? 0) + (sceneData?.windows.length ?? 0) + (sceneData?.doors.length ?? 0)"
      :data-windows="sceneData?.windows.length ?? 0"
      :data-doors="sceneData?.doors.length ?? 0"
      :data-openings="sceneData?.walls.reduce((sum, wall) => sum + (wall.openings?.length ?? 0), 0) ?? 0"
      :data-facilities="sceneData?.facilities.length ?? 0"
      :data-walls="sceneData?.walls.length ?? 0">
      <header class="flex flex-none items-center justify-between border-b border-line bg-white px-4 py-2.5">
        <div>
          <h2 class="text-sm font-bold">3D 空间预览 · {{ zone?.name || '当前空间' }}<span v-if="readonly" class="ml-2 text-[10px] font-normal text-muted">只读</span></h2>
          <p class="text-xs text-muted">{{ readonly ? '自由旋转/缩放视角；点选家具查看真实业务属性；修改请前往设计模式' : '自由旋转/缩放视角；点选家具可拖动、方向键微调、旋转和替换，改动实时同步 2D 排版' }}</p>
        </div>
        <div class="flex items-center gap-2">
          <button v-if="!inline" class="rounded-lg border border-line px-3 py-1.5 text-xs text-muted hover:bg-cream" :disabled="!ready3d" @click="openAiRender">AI 概念效果图</button>
          <button class="rounded-lg border border-line px-3 py-1.5 text-xs" aria-label="关闭 3D 预览" @click="show = false">✕ 关闭</button>
        </div>
      </header>
      <div class="relative flex min-h-0 flex-1">
        <div ref="host" class="relative min-w-0 flex-1 cursor-grab active:cursor-grabbing">
          <div v-if="!ready" class="absolute inset-0 z-10 grid place-items-center bg-[#faf8f5] text-sm text-muted">请先完成图纸比例校准（毫米）后再预览 3D 场景</div>
          <div v-else-if="initError" class="absolute inset-0 z-10 grid place-items-center bg-[#faf8f5] text-sm text-red-700">{{ initError }}</div>
          <div class="pointer-events-none absolute inset-0 z-[4]" style="background: radial-gradient(ellipse 118% 105% at 50% 42%, transparent 58%, rgba(40,34,26,0.13) 100%)"></div>
          <div v-if="hoverLabel" class="pointer-events-none absolute left-3 top-3 z-10 rounded-lg bg-black/70 px-2.5 py-1 text-xs text-white">{{ hoverLabel }}</div>
          <div class="absolute bottom-3 left-3 z-10 flex flex-wrap gap-2 rounded-lg bg-white/85 px-3 py-2 text-[11px] text-muted shadow">
            <span v-for="[color, label] in legend" :key="label" class="flex items-center gap-1"><i class="inline-block h-2.5 w-2.5 rounded-sm border border-line" :style="{ background: color }"></i>{{ label }}</span>
          </div>
        </div>
        <aside class="flex w-80 flex-none flex-col gap-3 overflow-y-auto border-l border-line bg-white p-3 text-xs max-md:absolute max-md:inset-x-0 max-md:bottom-0 max-md:max-h-[48%] max-md:border-l-0 max-md:border-t max-md:shadow-lg">
          <div>
            <h3 class="mb-1 font-semibold text-ink">家具（{{ furnitureRows.length }} 项 · {{ furniture.length }} 件）</h3>
            <ul class="space-y-1">
              <li v-for="row in furnitureRows" :key="row.item.id" data-testid="white-model-furniture-row" :data-count="row.ids.length">
                <button class="w-full rounded-lg border px-2.5 py-1.5 text-left transition"
                  :class="row.ids.includes(selectedId) ? 'border-bronze bg-[#f7f0e6] text-ink' : 'border-line text-muted hover:bg-cream'"
                  :aria-pressed="row.ids.includes(selectedId)"
                  :data-testid="'white-model-select-' + row.item.id" @click="selectFurnitureRow(row)">
                  {{ row.ids.length > 1 ? row.label : row.item.label || '家具' }} · {{ Math.round(row.item.w) }}×{{ Math.round(row.item.h) }}mm<span v-if="row.ids.length > 1"> · {{ row.ids.length }} 件</span>
                  <span v-if="sceneData?.workstationSeats.some(seat => row.ids.includes(seat.deskId))"> · 桌椅配套</span>
                </button>
              </li>
            </ul>
          </div>
          <OfficeCanvasSelectedFurniturePanel
            :item="selectedItem" :zone="zone" :readonly="readonly"
            :products="store.products" :products-error="productsError"
            @rotate="rotateSelected" @replace-module="replaceModule"
            @change-product="showProductSelector = true" @locate="locateSelected" />
          <p v-if="selectedWorkstationSeat" class="text-[11px] text-muted" data-testid="white-model-paired-seat">{{ selectedWorkstationSeat.existingChairId ? '桌椅配套 · 已有独立座椅' : '默认配套办公椅 · 随工位一起移动、旋转' }}</p>
          <p v-if="selectedItem && !readonly" class="text-[11px] leading-relaxed text-muted" data-testid="white-model-keyboard-help">方向键按当前视角移动 10 mm · Shift 100 mm · Alt 1 mm · Ctrl/⌘+Z 撤销</p>
          <div>
            <h3 class="mb-1 font-semibold text-ink">设施标注（{{ sceneData?.facilities.length || 0 }}）</h3>
            <ul class="space-y-1 text-muted">
              <li v-for="f in sceneData?.facilities || []" :key="f.id" class="flex items-center gap-1.5">
                <i class="inline-block h-2.5 w-2.5 rounded-full" :style="{ background: '#' + (FACILITY_COLORS[f.kind] ?? 0x9a938a).toString(16).padStart(6, '0') }"></i>
                {{ f.label }}<span class="text-[10px]">{{ facilityKindLabel[f.kind] }}<template v-if="f.clearanceMm"> · 净空 {{ f.clearanceMm }}mm</template></span>
              </li>
              <li v-if="!sceneData?.facilities.length" class="text-muted">当前空间没有设施标注</li>
            </ul>
          </div>
          <p v-if="notice" class="rounded-lg bg-[#eef5ee] px-2.5 py-2 text-[#3c6b3c]" data-testid="white-model-notice">{{ notice }}</p>
        </aside>
      </div>
      <OfficeCanvasFurnitureProductSelector v-if="!readonly && selectedItem" v-model:show="showProductSelector" :zone-id="activeZoneId" :item="selectedItem" @applied="onProductApplied" />
    </div>
  </Teleport>
</template>
