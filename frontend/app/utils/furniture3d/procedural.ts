import * as THREE from 'three'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'

/**
 * 类别默认示意模型（RENDER-004 P2）：按家具类别用参数化几何体拼装的轻量模型，
 * 替代纯白方块。真实商品 GLB 优先（modelRegistry），没有则用本模块，未知类别回退方块。
 *
 * 约定（与 2D 线稿 ManualFurnitureGlyph 一致）：
 * - 输出 Group 原点 = 占地中心、底面落地（y=0）；单位毫米。
 * - 模型占地绝不超出业务 w×d，业务尺寸始终是唯一事实来源。
 * - 朝向：椅子/沙发靠背在 three -z 侧（画布 +y 侧），面向 +z；显示器/屏风在 +z 侧。
 * - 材质逐实例创建（选中高亮改 emissive 不会串色），由调用方随场景释放。
 * - 纹理为程序化生成的 CanvasTexture（木纹/织物噪点），模块级缓存共享、不随场景释放；
 *   无外部图片资源。Node 环境（单测）无 document，纹理退化为 null。
 * - seed 提供逐实例确定性配色微调，避免全场同一色；同一 ID 每次渲染一致。
 */

export interface ProceduralFurnitureParams {
  kind: string
  /** 业务占地宽（画布 x）。 */
  w: number
  /** 业务占地深（画布 y → three z）。 */
  d: number
  /** 总高（渲染估计值或实例显式高度）。 */
  heightMm: number
  /** 屏风卡位：桌面后侧加隔断与显示器。 */
  screen?: boolean
  /** 水平镜像（L/U 形翼侧）。 */
  mirrorX?: boolean
  /** 逐实例配色微调种子（建议由稳定 ID 哈希，见 furnitureSeed）。 */
  seed?: number
  /** 目录 L 型桌的真实线稿轮廓参数；缺省保留通用左翼模型。 */
  referenceL?: { wing: number; desktopDepth: number }
}

/** 由稳定实例 ID 生成配色种子。 */
export function furnitureSeed(id: string | undefined): number {
  if (!id) return 0
  let h = 0
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) | 0
  return Math.abs(h)
}

const COLORS = {
  wood: 0xcfa068, // 桌面木纹色（ACES 下会略降饱和，调色已补偿）
  woodDark: 0x99744a,
  metal: 0x43444c, // 桌腿/椅架
  chair: 0x6e7a66, // 椅面织物
  screen: 0x87a07c, // 屏风隔断
  monitor: 0x26272c,
  monitorGlow: 0x1b2836, // 屏幕微光（baseEmissive）
  sofa: 0x84907e,
  pillow: 0xb08e60,
  cabinet: 0xded5c4,
  plantPot: 0x8d5c34,
  plantLeaf: 0x4d7240,
  panel: 0xe6dfd1, // 柜体/前台主体
} as const

// ---- 程序化纹理（模块级缓存，共享、不随场景释放；Node 单测环境退化为 null） ----

const textureCache = new Map<string, THREE.Texture>()

/** 木纹：浅色底 + 竖向深浅条纹 + 噪点，配合材质 color 叠色。 */
function grainTexture(): THREE.Texture | null {
  if (typeof document === 'undefined') return null
  let t = textureCache.get('wood')
  if (!t) {
    const canvas = document.createElement('canvas')
    canvas.width = canvas.height = 256
    const ctx = canvas.getContext('2d')!
    ctx.fillStyle = '#f4ece0'
    ctx.fillRect(0, 0, 256, 256)
    let rand = 42
    const next = () => (rand = (rand * 16807) % 2147483647) / 2147483647
    for (let i = 0; i < 46; i++) {
      const x = next() * 256
      ctx.strokeStyle = `rgba(150,115,75,${0.05 + next() * 0.1})`
      ctx.lineWidth = 1 + next() * 3
      ctx.beginPath()
      ctx.moveTo(x, 0)
      ctx.bezierCurveTo(x + (next() - 0.5) * 24, 85, x + (next() - 0.5) * 24, 170, x + (next() - 0.5) * 16, 256)
      ctx.stroke()
    }
    for (let i = 0; i < 500; i++) {
      ctx.fillStyle = `rgba(120,90,60,${next() * 0.08})`
      ctx.fillRect(next() * 256, next() * 256, 1.5, 1.5)
    }
    t = new THREE.CanvasTexture(canvas)
    t.wrapS = t.wrapT = THREE.RepeatWrapping
    t.colorSpace = THREE.SRGBColorSpace
    textureCache.set('wood', t)
  }
  return t
}

/** 织物噪点：细微明暗颗粒。 */
function weaveTexture(): THREE.Texture | null {
  if (typeof document === 'undefined') return null
  let t = textureCache.get('weave')
  if (!t) {
    const canvas = document.createElement('canvas')
    canvas.width = canvas.height = 128
    const ctx = canvas.getContext('2d')!
    ctx.fillStyle = '#efefef'
    ctx.fillRect(0, 0, 128, 128)
    let rand = 7
    const next = () => (rand = (rand * 48271) % 2147483647) / 2147483647
    for (let i = 0; i < 1600; i++) {
      const v = 205 + Math.floor(next() * 50)
      ctx.fillStyle = `rgb(${v},${v},${v})`
      ctx.fillRect(Math.floor(next() * 128), Math.floor(next() * 128), 2, 1)
    }
    t = new THREE.CanvasTexture(canvas)
    t.wrapS = t.wrapT = THREE.RepeatWrapping
    t.repeat.set(3, 3)
    t.colorSpace = THREE.SRGBColorSpace
    textureCache.set('weave', t)
  }
  return t
}

/** 逐实例确定性配色微调（明度/饱和度小幅偏移，色相微移）。 */
function tint(hex: number, seed = 0): number {
  if (!seed) return hex
  const c = new THREE.Color(hex)
  const hsl = { h: 0, s: 0, l: 0 }
  c.getHSL(hsl)
  const r = ((seed % 997) / 997) - 0.5
  c.setHSL(
    hsl.h + r * 0.025,
    THREE.MathUtils.clamp(hsl.s + r * 0.07, 0, 1),
    THREE.MathUtils.clamp(hsl.l + r * 0.09, 0.06, 0.94),
  )
  return c.getHex()
}

interface MatOptions {
  roughness?: number
  metalness?: number
  map?: THREE.Texture | null
  /** 材质固有自发光（显示器屏幕等），选中提亮结束后恢复为该值。 */
  emissive?: number
}

function mat(color: number, opts: MatOptions = {}) {
  const m = new THREE.MeshStandardMaterial({
    color, roughness: opts.roughness ?? 0.75, metalness: opts.metalness ?? 0, map: opts.map ?? null,
  })
  m.userData.baseEmissive = opts.emissive ?? 0
  if (opts.emissive) m.emissive.setHex(opts.emissive)
  return m
}

/** 圆角方块；过薄的部件自动退化为普通方块。 */
function box(w: number, h: number, d: number, color: number, x = 0, y = 0, z = 0, opts: MatOptions & { radius?: number } = {}) {
  const r = Math.min(opts.radius ?? 14, Math.min(w, h, d) * 0.24)
  const geo = r > 0.6 ? new RoundedBoxGeometry(w, h, d, 2, r) : new THREE.BoxGeometry(w, h, d)
  const mesh = new THREE.Mesh(geo, mat(color, opts))
  mesh.position.set(x, y, z)
  mesh.castShadow = true
  mesh.receiveShadow = true
  return mesh
}

function cylinder(rTop: number, rBottom: number, h: number, color: number, x = 0, y = 0, z = 0, opts: MatOptions & { segments?: number } = {}) {
  const mesh = new THREE.Mesh(new THREE.CylinderGeometry(rTop, rBottom, h, opts.segments ?? 20), mat(color, opts))
  mesh.position.set(x, y, z)
  mesh.castShadow = true
  mesh.receiveShadow = true
  return mesh
}

const METAL: MatOptions = { roughness: 0.38, metalness: 0.65 }

/** 四条金属圆柱腿，内收 inset，顶到 y=top。 */
function legs(group: THREE.Group, w: number, d: number, top: number, inset = 90) {
  const xs = [-w / 2 + inset, w / 2 - inset]
  const zs = [-d / 2 + inset, d / 2 - inset]
  for (const x of xs) for (const z of zs) group.add(cylinder(24, 28, top, COLORS.metal, x, top / 2, z, METAL))
}

/** 显示器：机身 + 微光屏幕（面向 -z 座位侧）+ 支架，放在桌面 y=top 上。 */
function monitor(group: THREE.Group, top: number, z: number, x = 0) {
  group.add(box(500, 320, 28, COLORS.monitor, x, top + 240, z, { roughness: 0.5, radius: 10 }))
  group.add(box(478, 298, 4, COLORS.monitorGlow, x, top + 240, z - 16, { roughness: 0.35, emissive: COLORS.monitorGlow, radius: 2 }))
  group.add(box(40, 120, 40, COLORS.metal, x, top + 60, z + 10, METAL))
  group.add(box(220, 16, 160, COLORS.metal, x, top + 8, z + 20, METAL))
}

/** 桌面圆角板 + 腿 + 穿线孔。 */
function deskSlab(group: THREE.Group, w: number, d: number, top: number, wood: number, cx = 0, cz = 0) {
  group.add(box(w, 44, d, wood, cx, top - 22, cz, { map: grainTexture(), roughness: 0.55, radius: 12 }))
  const leg = new THREE.Group()
  legs(leg, w, d, top - 44)
  leg.position.set(cx, 0, cz)
  group.add(leg)
  // 穿线孔
  group.add(cylinder(42, 42, 6, COLORS.monitor, cx + w * 0.28, top + 2, cz + d / 2 - 130, { roughness: 0.5 }))
}

function buildDesk(p: ProceduralFurnitureParams): THREE.Group {
  const g = new THREE.Group()
  const top = Math.min(p.heightMm, 760)
  const wood = tint(COLORS.wood, p.seed)
  deskSlab(g, p.w, p.d, top, wood)
  if (p.screen) {
    // 屏风：靠 +z 沿口立板（织物面 + 金属顶帽）
    g.add(box(p.w - 60, 380, 26, tint(COLORS.screen, p.seed), 0, top + 190, p.d / 2 - 26, { map: weaveTexture(), roughness: 0.95 }))
    g.add(box(p.w - 60, 26, 34, COLORS.metal, 0, top + 386, p.d / 2 - 26, METAL))
    monitor(g, top, p.d / 2 - 190)
  } else {
    // 挡板（用户侧 -z）：宽桌带吊挂挡板
    if (p.w >= 1400) g.add(box(p.w - 260, Math.max(260, top - 200), 16, tint(COLORS.woodDark, p.seed), 0, top - 60 - Math.max(260, top - 200) / 2, -p.d / 2 + 80, { map: grainTexture(), roughness: 0.6 }))
    if (p.w >= 1000) monitor(g, top, p.d / 2 - 160)
  }
  return g
}

function buildLDesk(p: ProceduralFurnitureParams): THREE.Group {
  const g = new THREE.Group()
  const top = Math.min(p.heightMm, 760)
  const wing = p.referenceL?.wing ?? Math.min(600, p.d * 0.45, p.w * 0.45)
  const desktopDepth = p.referenceL?.desktopDepth ?? wing
  const wood = tint(COLORS.wood, p.seed)
  // 与 2D 线稿同形：画布顶边整条 + 翼板（侧向由镜像决定）；three 中画布 y 小侧 = +z
  deskSlab(g, p.w, desktopDepth, top, wood, 0, p.d / 2 - desktopDepth / 2)
  const rightWing = !!p.referenceL !== !!p.mirrorX
  const wingX = rightWing ? p.w / 2 - wing / 2 : -p.w / 2 + wing / 2
  deskSlab(g, wing, p.d - desktopDepth, top, wood, wingX, -(desktopDepth / 2))
  if (p.screen) {
    // 背屏：主台面后沿（画布顶边）
    g.add(box(p.w - 60, 380, 26, tint(COLORS.screen, p.seed), 0, top + 190, p.d / 2 - 26, { map: weaveTexture(), roughness: 0.95 }))
    g.add(box(p.w - 60, 26, 34, COLORS.metal, 0, top + 386, p.d / 2 - 26, METAL))
    // 侧屏：翼板外沿整条，与背屏围合成 L 形卡位（镜像时换到另一侧）
    const sideX = (rightWing ? 1 : -1) * (p.w / 2 - 26)
    g.add(box(26, 380, p.d - 60, tint(COLORS.screen, p.seed), sideX, top + 190, 0, { map: weaveTexture(), roughness: 0.95 }))
    g.add(box(34, 26, p.d - 60, COLORS.metal, sideX, top + 386, 0, METAL))
  } else if (p.w >= 1000) monitor(g, top, p.d / 2 - 150, rightWing ? -p.w * 0.2 : p.w * 0.2)
  return g
}

function buildUDesk(p: ProceduralFurnitureParams): THREE.Group {
  const g = new THREE.Group()
  const top = Math.min(p.heightMm, 760)
  const wing = Math.min(600, p.d * 0.4, p.w * 0.3)
  const wood = tint(COLORS.wood, p.seed)
  deskSlab(g, p.w, wing, top, wood, 0, p.d / 2 - wing / 2)
  deskSlab(g, wing, p.d - wing, top, wood, -p.w / 2 + wing / 2, -(wing / 2))
  deskSlab(g, wing, p.d - wing, top, wood, p.w / 2 - wing / 2, -(wing / 2))
  if (p.w >= 1000) monitor(g, top, p.d / 2 - 150)
  return g
}

function buildChair(p: ProceduralFurnitureParams): THREE.Group {
  const g = new THREE.Group()
  const seatH = 450
  const seatW = p.w * 0.76, seatD = p.d * 0.64
  const fabric = tint(COLORS.chair, p.seed)
  const weave = weaveTexture()
  // 靠背在 -z 侧（画布 +y 侧），面向 +z，轻微后仰
  g.add(box(seatW, 90, seatD, fabric, 0, seatH, 0, { map: weave, roughness: 0.95, radius: 26 }))
  const backH = Math.max(320, p.heightMm - seatH - 60)
  const back = box(seatW, backH, 70, fabric, 0, 0, 0, { map: weave, roughness: 0.95, radius: 30 })
  back.position.set(0, seatH + backH / 2 + 30, -p.d / 2 + 60)
  back.rotation.x = -0.06
  g.add(back)
  // 扶手：立柱 + 软包面
  for (const side of [-1, 1]) {
    const ax = side * (seatW / 2 + 30)
    g.add(box(36, 180, 36, COLORS.metal, ax, seatH + 130, 0, METAL))
    g.add(box(64, 32, seatD * 0.66, fabric, ax, seatH + 235, 0, { map: weave, roughness: 0.95, radius: 14 }))
  }
  // 气杆 + 五星脚 + 滚轮
  g.add(cylinder(28, 28, seatH - 90, COLORS.metal, 0, (seatH - 90) / 2 + 55, 0, METAL))
  const spokeLen = p.w * 0.3
  for (let i = 0; i < 5; i++) {
    const a = (i / 5) * Math.PI * 2 + Math.PI / 10
    const spoke = box(spokeLen, 26, 44, COLORS.metal, 0, 0, 0, METAL)
    spoke.position.set(Math.cos(a) * spokeLen / 2, 58, Math.sin(a) * spokeLen / 2)
    spoke.rotation.y = -a
    g.add(spoke)
    // 脚轮：远处看为小圆墩
    g.add(cylinder(26, 26, 30, COLORS.monitor, Math.cos(a) * (spokeLen - 20), 15, Math.sin(a) * (spokeLen - 20), { roughness: 0.5 }))
  }
  return g
}

function buildTable(p: ProceduralFurnitureParams): THREE.Group {
  const g = new THREE.Group()
  const top = Math.min(p.heightMm, 760)
  const wood = tint(COLORS.wood, p.seed)
  if (p.kind === 'round-table' || p.kind === 'oval-table') {
    const topMesh = cylinder(0.5, 0.5, 48, wood, 0, top - 24, 0, { segments: 40, map: grainTexture(), roughness: 0.55 })
    topMesh.scale.set(p.w, 1, p.d) // 单位圆柱缩放为圆/椭圆桌面
    g.add(topMesh)
    g.add(cylinder(90, 110, top - 48, COLORS.metal, 0, (top - 48) / 2, 0, METAL))
    g.add(cylinder(Math.min(p.w, p.d) * 0.28, Math.min(p.w, p.d) * 0.3, 30, COLORS.metal, 0, 15, 0, METAL))
    return g
  }
  g.add(box(p.w, 48, p.d, wood, 0, top - 24, 0, { map: grainTexture(), roughness: 0.55, radius: 14 }))
  if (p.w >= 1800) {
    // 会议桌：两块板式桌脚
    for (const side of [-1, 1]) {
      g.add(box(56, top - 48, p.d * 0.62, tint(COLORS.woodDark, p.seed), side * (p.w / 2 - 150), (top - 48) / 2, 0, { map: grainTexture(), roughness: 0.6 }))
    }
    g.add(box(p.w - 480, 120, 40, COLORS.metal, 0, top - 110, 0, METAL)) // 横梁
  } else {
    legs(g, p.w, p.d, top - 48)
  }
  return g
}

function buildSofa(p: ProceduralFurnitureParams): THREE.Group {
  const g = new THREE.Group()
  const fabric = tint(COLORS.sofa, p.seed)
  const weave = weaveTexture()
  const armW = Math.min(200, p.w * 0.14)
  const baseH = 380
  const backH = Math.max(420, p.heightMm - 200)
  // 靠背在 -z 侧，面向 +z；与 2D 线稿（靠背线在画布底部）一致
  g.add(box(p.w, baseH, p.d - 200, fabric, 0, baseH / 2, 100, { map: weave, roughness: 0.95, radius: 30 }))
  g.add(box(p.w, backH, 200, fabric, 0, backH / 2, -(p.d / 2 - 100), { map: weave, roughness: 0.95, radius: 40 }))
  g.add(box(armW, 560, p.d, fabric, -(p.w / 2 - armW / 2), 280, 0, { map: weave, roughness: 0.95, radius: 40 }))
  g.add(box(armW, 560, p.d, fabric, p.w / 2 - armW / 2, 280, 0, { map: weave, roughness: 0.95, radius: 40 }))
  // 独立坐垫/靠垫，按座位数分块
  const seats = Math.max(1, Math.round((p.w - armW * 2) / 750))
  const innerW = p.w - armW * 2 - 60
  const cw = (innerW - (seats - 1) * 24) / seats
  const cushion = tint(fabric, (p.seed ?? 0) + 500)
  for (let i = 0; i < seats; i++) {
    const cx = -innerW / 2 + cw / 2 + i * (cw + 24)
    g.add(box(cw, 130, p.d - 340, cushion, cx, baseH + 65, 70, { map: weave, roughness: 0.95, radius: 40 }))
    const backCushion = box(cw, 300, 130, cushion, 0, 0, 0, { map: weave, roughness: 0.95, radius: 40 })
    backCushion.position.set(cx, baseH + 240, -(p.d / 2 - 235))
    backCushion.rotation.x = 0.1
    g.add(backCushion)
  }
  // 抱枕点缀
  if (p.w >= 1400) {
    const pillow = box(280, 280, 110, tint(COLORS.pillow, p.seed), 0, 0, 0, { map: weave, roughness: 0.95, radius: 46 })
    pillow.position.set(-innerW / 2 + 150, baseH + 240, -(p.d / 2 - 300))
    pillow.rotation.set(0.16, 0.2, 0.08)
    g.add(pillow)
  }
  return g
}

function buildCabinet(p: ProceduralFurnitureParams): THREE.Group {
  const g = new THREE.Group()
  const h = p.heightMm
  g.add(box(p.w, h - 60, p.d - 20, tint(COLORS.panel, p.seed), 0, (h - 60) / 2 + 40, 0, { roughness: 0.6 }))
  g.add(box(p.w, 24, p.d, tint(COLORS.wood, p.seed), 0, h - 32, 0, { map: grainTexture(), roughness: 0.55 })) // 台面
  g.add(box(p.w - 80, 40, p.d - 40, COLORS.metal, 0, 20, 0, METAL)) // 踢脚
  // 抽屉分缝 + 拉手（正面 +z，均不超出占地）
  const rows = Math.max(1, Math.min(4, Math.round(h / 400)))
  for (let i = 1; i < rows; i++) g.add(box(p.w - 100, 10, 6, COLORS.metal, 0, 40 + ((h - 100) * i) / rows, p.d / 2 - 12, { radius: 3 }))
  for (let i = 0; i < rows; i++) {
    const handle = cylinder(7, 7, 110, COLORS.metal, 0, 40 + ((h - 100) * (i + 0.5)) / rows, p.d / 2 - 14, METAL)
    handle.rotation.z = Math.PI / 2
    g.add(handle)
  }
  return g
}

function buildCounter(p: ProceduralFurnitureParams): THREE.Group {
  const g = new THREE.Group()
  const h = Math.max(p.heightMm, 1000)
  g.add(box(p.w, h - 80, p.d - 120, tint(COLORS.panel, p.seed), 0, (h - 80) / 2, 40, { roughness: 0.6, radius: 20 }))
  g.add(box(p.w, 80, p.d, tint(COLORS.wood, p.seed), 0, h - 40, 0, { map: grainTexture(), roughness: 0.55, radius: 18 })) // 台面前挑
  g.add(box(p.w - 120, 120, 30, tint(COLORS.woodDark, p.seed), 0, h - 220, p.d / 2 - 40, { map: grainTexture(), roughness: 0.6 })) // 前挡板线条
  g.add(box(p.w - 160, 90, 20, COLORS.metal, 0, 45, p.d / 2 - 60, METAL)) // 踢脚
  return g
}

function buildPlant(p: ProceduralFurnitureParams): THREE.Group {
  const g = new THREE.Group()
  const r = Math.min(p.w, p.d) / 2
  const pot = tint(COLORS.plantPot, p.seed)
  g.add(cylinder(r * 0.52, r * 0.42, 300, pot, 0, 150, 0, { roughness: 0.85 }))
  g.add(cylinder(r * 0.56, r * 0.52, 40, pot, 0, 310, 0, { roughness: 0.85 })) // 盆沿
  g.add(cylinder(r * 0.46, r * 0.46, 16, 0x4a3a28, 0, 330, 0, { roughness: 1 })) // 土面
  const trunkH = Math.max(300, p.heightMm * 0.45)
  g.add(cylinder(24, 32, trunkH, 0x6b5138, 0, 330 + trunkH / 2, 0, { segments: 8, roughness: 0.9 }))
  const leaf = tint(COLORS.plantLeaf, p.seed)
  const crownAt = (cr: number, x: number, y: number, z: number) => {
    const crown = new THREE.Mesh(new THREE.IcosahedronGeometry(cr, 1), mat(tint(leaf, (p.seed ?? 0) + Math.round(x + y)), { roughness: 0.9, map: weaveTexture() }))
    crown.position.set(x, y, z)
    crown.castShadow = true
    g.add(crown)
  }
  const top = p.heightMm
  crownAt(r * 0.85, 0, top - r * 0.7, 0)
  crownAt(r * 0.5, -r * 0.42, top - r * 1.3, r * 0.1)
  crownAt(r * 0.45, r * 0.44, top - r * 1.45, -r * 0.08)
  return g
}

function buildWallPanel(p: ProceduralFurnitureParams): THREE.Group {
  const g = new THREE.Group()
  const h = Math.max(p.heightMm, 1200)
  g.add(box(p.w, h, Math.min(p.d, 80), tint(COLORS.screen, p.seed), 0, h / 2, 0, { map: weaveTexture(), roughness: 0.95, radius: 16 }))
  g.add(box(p.w, 30, Math.min(p.d, 90), COLORS.metal, 0, h - 15, 0, METAL)) // 顶帽
  g.add(box(Math.min(360, p.w * 0.3), 30, p.d, COLORS.metal, -(p.w / 2 - 200), 15, 0, METAL))
  g.add(box(Math.min(360, p.w * 0.3), 30, p.d, COLORS.metal, p.w / 2 - 200, 15, 0, METAL))
  return g
}

/** 按类别构建示意模型；未知类别返回 null（调用方回退白模方块）。 */
export function buildProceduralFurniture(p: ProceduralFurnitureParams): THREE.Group | null {
  if (![p.w, p.d, p.heightMm].every(v => Number.isFinite(v) && v > 0)) return null
  switch (p.kind) {
    case 'desk': return buildDesk(p)
    case 'l-desk': case 'rounded-l-desk': return buildLDesk(p)
    case 'u-desk': return buildUDesk(p)
    case 'chair': return buildChair(p)
    case 'table': case 'round-table': case 'oval-table': return buildTable(p)
    case 'sofa': return buildSofa(p)
    case 'cabinet': return buildCabinet(p)
    case 'counter': return buildCounter(p)
    case 'plant': return buildPlant(p)
    case 'wall': return buildWallPanel(p)
    default: return null
  }
}

/** 选中提亮：逐实例材质设置 emissive；取消时恢复材质固有自发光（显示器屏幕等）。不影响其他同类家具。 */
export function setGroupEmissive(group: THREE.Object3D, color: number) {
  group.traverse(node => {
    const mesh = node as THREE.Mesh
    const material = mesh.material as THREE.MeshStandardMaterial | undefined
    if (mesh.isMesh && material?.isMeshStandardMaterial) {
      material.emissive.setHex(color || ((material.userData.baseEmissive as number) ?? 0))
    }
  })
}
