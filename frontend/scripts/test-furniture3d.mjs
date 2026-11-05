import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'

// 家具 3D 模型注册层与选中/拖拽纯判定测试：与 UI 无关，直接加载真实 TS 模块。
async function moduleUrl(path, dependencies = {}) {
  let source = stripTypeScriptTypes(await readFile(new URL(path, import.meta.url), 'utf8'))
  for (const [alias, url] of Object.entries(dependencies)) source = source.replaceAll(`'${alias}'`, `'${url}'`)
  return 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
}

const registry = await import(await moduleUrl('../app/utils/furniture3d/modelRegistry.ts'))
const selection = await import(await moduleUrl('../app/utils/furniture3d/selection.ts'))
const { furnitureKeyboardDelta } = await import(await moduleUrl('../app/utils/furniture3d/keyboard.ts'))
const threeUrl = new URL('../node_modules/three/build/three.module.js', import.meta.url).href
const roundedBoxUrl = new URL('../node_modules/three/examples/jsm/geometries/RoundedBoxGeometry.js', import.meta.url).href
const procedural = await import(await moduleUrl('../app/utils/furniture3d/procedural.ts', { 'three/addons/geometries/RoundedBoxGeometry.js': roundedBoxUrl, three: threeUrl }))
const THREE = await import(threeUrl)
const { buildWindowModel } = await import(await moduleUrl('../app/utils/furniture3d/windowModel.ts', { three: threeUrl }))
const { buildDoorModel } = await import(await moduleUrl('../app/utils/furniture3d/doorModel.ts', { three: threeUrl }))
const polygonUrl = new URL('../node_modules/polygon-clipping/dist/polygon-clipping.esm.js', import.meta.url).href
const { buildWallGeometry } = await import(await moduleUrl('../app/utils/furniture3d/wallGeometry.ts', { three: threeUrl, 'polygon-clipping': polygonUrl }))

const { registerProductModel, registerCategoryModel, clearFurnitureModelRegistry, resolveFurnitureModel, computeModelScale } = registry
const { shouldStartDrag, reconcileSelection, FURNITURE_DRAG_THRESHOLD_PX } = selection
const { buildProceduralFurniture, setGroupEmissive } = procedural

// ---- 点击/拖动阈值与选中 reconciliation ----
assert.equal(shouldStartDrag(100, 100, 100 + FURNITURE_DRAG_THRESHOLD_PX, 100), false, '阈值内是点击')
assert.equal(shouldStartDrag(100, 100, 100 + FURNITURE_DRAG_THRESHOLD_PX + 1, 100), true, '超过阈值才拖动')
assert.equal(shouldStartDrag(0, 0, 5, 5), true, '斜向位移按欧氏距离（7.07 > 6）')
assert.equal(shouldStartDrag(0, 0, 4, 4), false, '斜向 5.66 仍在阈值内')
assert.equal(reconcileSelection('A', ['A', 'B']), 'A', '家具仍在则保持选中')
assert.equal(reconcileSelection('A', ['B']), '', '家具删除后清空选中')
assert.equal(reconcileSelection('A', []), '', '切换空间清空选中')

// 方向键遵循相机地面方向；旋转视角后重新映射，默认/快移/微调都使用毫米。
assert.deepEqual(furnitureKeyboardDelta({ key: 'ArrowRight' }, { x: 4, y: 3 }), { dx: 8, dy: 6 })
assert.deepEqual(furnitureKeyboardDelta({ key: 'ArrowUp' }, { x: 4, y: 3 }), { dx: -6, dy: 8 })
assert.deepEqual(furnitureKeyboardDelta({ key: 'ArrowDown', shiftKey: true }, { x: 4, y: 3 }), { dx: 60, dy: -80 })
assert.deepEqual(furnitureKeyboardDelta({ key: 'ArrowLeft', altKey: true }, { x: 4, y: 3 }), { dx: -0.8, dy: -0.6 })
assert.deepEqual(furnitureKeyboardDelta({ key: 'ArrowRight' }, { x: 0, y: 1 }), { dx: 0, dy: 10 }, '相机旋转 90°，屏幕向右对应另一设计轴')
assert.equal(furnitureKeyboardDelta({ key: 'ArrowRight', ctrlKey: true }, { x: 1, y: 0 }), null)
assert.equal(furnitureKeyboardDelta({ key: 'ArrowLeft', metaKey: true }, { x: 1, y: 0 }), null)
assert.equal(furnitureKeyboardDelta({ key: 'x' }, { x: 1, y: 0 }), null)
assert.equal(furnitureKeyboardDelta({ key: 'ArrowRight' }, { x: 0, y: 0 }), null)
assert.equal(furnitureKeyboardDelta({ key: 'ArrowRight' }, { x: NaN, y: 0 }), null)

// ---- 模型注册与解析回退链 ----
clearFurnitureModelRegistry()
const productAsset = { key: 'p1-glb', url: '/models/p1.glb', unit: 'mm', nativeSizeMm: { widthMm: 1500, depthMm: 750, heightMm: 760 }, anchor: 'floor-center' }
const deskAsset = { key: 'desk-default', url: '/models/desk.glb', unit: 'm', nativeSizeMm: { widthMm: 1400, depthMm: 700, heightMm: 750 }, anchor: 'floor-center' }

assert.equal(resolveFurnitureModel({ productId: '' }, 'desk'), null, '未注册任何模型时回退白模')
registerCategoryModel('desk', deskAsset)
assert.deepEqual(resolveFurnitureModel({ productId: '' }, 'desk'), { asset: deskAsset, matchedBy: 'category', schematic: true }, '类别示意模型')
registerProductModel('P1', productAsset)
assert.deepEqual(resolveFurnitureModel({ productId: 'P1' }, 'desk'), { asset: productAsset, matchedBy: 'product', schematic: false }, '真实商品模型优先于类别模型')
assert.equal(resolveFurnitureModel({ productId: 'P1' }, undefined)?.matchedBy, 'product', '无类别时仍命中商品模型')
assert.equal(resolveFurnitureModel({ productId: 'OTHER' }, 'chair'), null, '商品未注册且类别未注册时回退白模')
clearFurnitureModelRegistry()
assert.equal(resolveFurnitureModel({ productId: 'P1' }, 'desk'), null, '清空注册表后全部回退白模')

// 资源元数据校验：缺尺寸/单位非法直接拒绝
assert.throws(() => registerCategoryModel('desk', { key: 'bad', url: '/m.glb', unit: 'mm', nativeSizeMm: { widthMm: 0, depthMm: 700, heightMm: 750 }, anchor: 'floor-center' }), /原始尺寸无效/)
assert.throws(() => registerProductModel('P2', { key: 'bad2', url: '', unit: 'mm', nativeSizeMm: { widthMm: 1, depthMm: 1, heightMm: 1 }, anchor: 'floor-center' }), /缺少/)

// ---- 模型缩放：等比嵌入业务占地，不回写业务尺寸 ----
const fit = computeModelScale(productAsset, { widthMm: 1200, depthMm: 600 })
assert.ok(Math.abs(fit.scale - 0.8) < 1e-9, '取宽深较小比例等比缩放')
assert.deepEqual(fit.fittedSizeMm, { widthMm: 1200, depthMm: 600, heightMm: 608 })
const shrink = computeModelScale(productAsset, { widthMm: 3000, depthMm: 900 })
assert.ok(Math.abs(shrink.scale - 1.2) < 1e-9, '深度受限时按深度缩放')
assert.equal(computeModelScale(productAsset, { widthMm: 0, depthMm: 600 }), null, '业务占地无效不缩放')

// ---- 程序化示意模型：占地不超出业务 w×d，朝向与 2D 线稿一致，未知类别回退 ----
const footprintOf = group => {
  group.updateMatrixWorld(true)
  const box3 = new THREE.Box3().setFromObject(group)
  return { w: box3.max.x - box3.min.x, d: box3.max.z - box3.min.z, h: box3.max.y - box3.min.y, minY: box3.min.y, box3 }
}

const desk = buildProceduralFurniture({ kind: 'desk', w: 1200, d: 600, heightMm: 750 })
const df = footprintOf(desk)
assert.ok(df.w <= 1200.01 && df.d <= 600.01, `桌子占地不得超出业务尺寸：${df.w}×${df.d}`)
assert.ok(df.minY >= -0.01, '底面落地')
assert.ok(df.h > 750, '桌面+显示器总高应超过桌面高度')
assert.ok(df.box3.max.z > 600 / 2 - 200, '显示器应在 +z 侧（画布 -y，与 2D 线稿座位侧相对）')

const screenDesk = buildProceduralFurniture({ kind: 'desk', w: 1200, d: 600, heightMm: 750, screen: true })
const sf = footprintOf(screenDesk)
assert.ok(sf.w <= 1200.01 && sf.d <= 600.01, '屏风卡位占地不得超出业务尺寸')
assert.ok(sf.box3.max.y >= 1100, '屏风应高出桌面')

const chair = buildProceduralFurniture({ kind: 'chair', w: 650, d: 650, heightMm: 850 })
const cf = footprintOf(chair)
assert.ok(cf.w <= 650.01 && cf.d <= 650.01 && cf.minY >= -0.01, '椅子占地与落地')
assert.ok(cf.box3.min.z < -650 / 2 + 130, '靠背应在 -z 侧（画布 +y，与 2D 线稿一致）')

const lDesk = buildProceduralFurniture({ kind: 'l-desk', w: 1400, d: 1400, heightMm: 750 })
const lf = footprintOf(lDesk)
assert.ok(lf.w <= 1400.01 && lf.d <= 1400.01, 'L 桌占地不超出')
const lMirror = footprintOf(buildProceduralFurniture({ kind: 'l-desk', w: 1400, d: 1400, heightMm: 750, mirrorX: true }))
assert.ok(lMirror.w <= 1400.01 && lMirror.d <= 1400.01, '镜像 L 桌占地不超出')
for (const mirrorX of [false, true]) {
  const referenceL = buildProceduralFurniture({ kind: 'l-desk', w: 1400, d: 1400, heightMm: 750, mirrorX, referenceL: { wing: 550, desktopDepth: 650 } })
  referenceL.updateMatrixWorld(true)
  const x = mirrorX ? 343 : -343
  const hits = new THREE.Raycaster(new THREE.Vector3(x, 1600, -325), new THREE.Vector3(0, -1, 0)).intersectObject(referenceL, true)
  assert.equal(hits.length, 0, '目录 L 桌按 2D 轮廓留出配椅缺口，镜像后也不把椅放进侧翼')
}

for (const [kind, w, d, h] of [['table', 2800, 1200, 750], ['oval-table', 2800, 1200, 750], ['round-table', 1000, 1000, 750], ['sofa', 1600, 850, 720], ['cabinet', 1200, 450, 900], ['counter', 2200, 700, 1050], ['plant', 500, 500, 1300], ['wall', 1800, 100, 1600], ['u-desk', 2000, 1600, 750]]) {
  const model = buildProceduralFurniture({ kind, w, d, heightMm: h })
  assert.ok(model, `${kind} 应有示意模型`)
  const f = footprintOf(model)
  assert.ok(f.w <= w + 0.01 && f.d <= d + 0.01, `${kind} 占地不得超出业务尺寸：${f.w.toFixed(1)}×${f.d.toFixed(1)} 超 ${w}×${d}`)
  assert.ok(f.minY >= -0.01, `${kind} 底面落地`)
}

assert.equal(buildProceduralFurniture({ kind: 'unknown-kind', w: 1000, d: 600, heightMm: 750 }), null, '未知类别回退白模方块')
assert.equal(buildProceduralFurniture({ kind: 'desk', w: 0, d: 600, heightMm: 750 }), null, '无效尺寸回退')

// 选中提亮只改自身实例材质，不串色
const chairA = buildProceduralFurniture({ kind: 'chair', w: 650, d: 650, heightMm: 850 })
const chairB = buildProceduralFurniture({ kind: 'chair', w: 650, d: 650, heightMm: 850 })
setGroupEmissive(chairA, 0x4a3a14)
let aEmissive = 0, bEmissive = 0
chairA.traverse(n => { if (n.material?.emissive) aEmissive = n.material.emissive.getHex() })
chairB.traverse(n => { if (n.material?.emissive) bEmissive = n.material.emissive.getHex() })
assert.equal(aEmissive, 0x4a3a14, '选中实例提亮')
assert.equal(bEmissive, 0, '同类其他实例不受影响')

// 取消选中恢复材质固有自发光（显示器屏幕微光不被清掉）
const glowDesk = buildProceduralFurniture({ kind: 'desk', w: 1200, d: 600, heightMm: 750 })
let screenMat = null
glowDesk.traverse(n => { if (n.material?.userData?.baseEmissive) screenMat = n.material })
assert.ok(screenMat, '显示器屏幕应带固有自发光')
setGroupEmissive(glowDesk, 0x4a3a14)
assert.equal(screenMat.emissive.getHex(), 0x4a3a14, '选中时提亮覆盖')
setGroupEmissive(glowDesk, 0)
assert.equal(screenMat.emissive.getHex(), 0x1b2836, '取消后恢复屏幕微光')

// 逐实例配色微调：不同 seed 的同类别模型配色不同，同 seed 稳定
const deskSeed1 = buildProceduralFurniture({ kind: 'desk', w: 1200, d: 600, heightMm: 750, seed: 101 })
const deskSeed2 = buildProceduralFurniture({ kind: 'desk', w: 1200, d: 600, heightMm: 750, seed: 202 })
const deskSeed1b = buildProceduralFurniture({ kind: 'desk', w: 1200, d: 600, heightMm: 750, seed: 101 })
const slabColorOf = g => { let c = 0; g.traverse(n => { if (!c && n.isMesh) c = n.material.color.getHex() }); return c }
assert.notEqual(slabColorOf(deskSeed1), slabColorOf(deskSeed2), '不同 seed 配色应不同')
assert.equal(slabColorOf(deskSeed1), slabColorOf(deskSeed1b), '同 seed 配色稳定')

// 窗洞必须贯穿墙厚，而不是完整墙面上叠玻璃；墙垛、窗台下墙和窗楣仍为实体。
const windowWall = { a: [0, 0], b: [6000, 0], heightMm: 2800, thicknessMm: 120, openings: [
  { startMm: 1000, endMm: 2500, bottomMm: 900, topMm: 2100 },
  { startMm: 3500, endMm: 5000, bottomMm: 900, topMm: 2100 },
] }
const wallMesh = new THREE.Mesh(buildWallGeometry(windowWall), new THREE.MeshBasicMaterial({ side: THREE.DoubleSide }))
const throughWall = (x, y, reverse = false) => new THREE.Raycaster(new THREE.Vector3(x, y, reverse ? -1000 : 1000), new THREE.Vector3(0, 0, reverse ? 1 : -1)).intersectObject(wallMesh).length
assert.equal(throughWall(1800, 1500), 0, '第一窗洞可从外向内穿透')
assert.equal(throughWall(4200, 1500, true), 0, '第二窗洞可从内向外穿透')
assert.ok(throughWall(3000, 1500) > 0, '两窗之间墙垛存在')
assert.ok(throughWall(1800, 400) > 0, '窗台下墙存在')
assert.ok(throughWall(1800, 2500) > 0, '窗楣存在')
const windowDescriptor = { id: 'W1', label: '窗', wallIndex: 0, a: [1000, 2000], b: [2800, 4400], widthMm: 3000, heightMm: 1200, baseMm: 900, wallThicknessMm: 120 }
const windowModel = buildWindowModel(windowDescriptor)
windowModel.updateMatrixWorld(true)
const parts = part => windowModel.children.filter(child => child.userData.windowPart === part)
assert.equal(parts('frame').length, 4)
assert.equal(parts('glass').length, 3, '宽窗分格，独立玻璃嵌入窗扇')
assert.equal(parts('sill').length, 1)
assert.ok(parts('handle').length > 0)
const paneBottom = windowModel.localToWorld(new THREE.Vector3(-1500, 0, 0))
const paneTop = windowModel.localToWorld(new THREE.Vector3(1500, 1200, 0))
assert.ok(paneBottom.distanceTo(new THREE.Vector3(1000, 900, -2000)) < 1e-8, '斜墙窗起点与窗台高度正确')
assert.ok(paneTop.distanceTo(new THREE.Vector3(2800, 2100, -4400)) < 1e-8, '斜墙窗末端与洞顶对齐')

// 门洞与窗孔可在同墙相交；落地通行空间无残留薄墙，门楣与分离墙垛保留。
const doorWall = { ...windowWall, openings: [
  ...windowWall.openings, { startMm: 1900, endMm: 3000, bottomMm: 0, topMm: 2100 },
] }
const doorWallMesh = new THREE.Mesh(buildWallGeometry(doorWall), new THREE.MeshBasicMaterial({ side: THREE.DoubleSide }))
const hitsDoorWall = (x, y) => new THREE.Raycaster(new THREE.Vector3(x, y, 1000), new THREE.Vector3(0, 0, -1)).intersectObject(doorWallMesh).length
assert.equal(hitsDoorWall(2700, 1), 0, '门洞一直通到地面')
assert.equal(hitsDoorWall(2700, 1200), 0, '门洞中部贯穿')
assert.equal(hitsDoorWall(2200, 1200), 0, '门窗相交处不生成破面或残墙')
assert.ok(hitsDoorWall(2700, 2500) > 0, '门楣保留')
assert.ok(hitsDoorWall(3200, 1200) > 0, '门与第二窗之间墙垛保留')
assert.ok(hitsDoorWall(1500, 400) > 0, '门旁窗下墙保留')
for (const opensTo of [-1, 1]) {
  const door = { id: 'D', label: '门', wallIndex: 0, a: [1000, 2000], b: [1540, 2720], widthMm: 900, heightMm: 2100, wallThicknessMm: 120, opensTo }
  const model = buildDoorModel(door)
  model.updateMatrixWorld(true)
  const leaf = model.children.find(child => child.userData.doorPart === 'leaf')
  assert.equal(model.children.filter(child => child.userData.doorPart === 'frame').length, 3, '两边框与顶框，无底部横框')
  assert.equal(model.children.filter(child => child.userData.doorPart === 'hinge').length, 3)
  assert.ok(leaf.children.some(child => child.userData.doorPart === 'handle'), '打开的门扇带独立把手')
  const start = leaf.localToWorld(new THREE.Vector3(0, 0, 0)), end = leaf.localToWorld(new THREE.Vector3(700, 0, 0))
  const direction = end.sub(start).normalize()
  assert.ok(direction.distanceTo(new THREE.Vector3(0.8 * opensTo, 0, 0.6 * opensTo)) < 1e-8, '斜墙门扇开启到墙的正确一侧')
  assert.ok(new THREE.Box3().setFromObject(model).min.y >= -0.01, '门框落地，不穿入地板')
}
console.log('PASS: 家具 3D 模型注册/解析回退/缩放 + 选中/拖动 + 示意模型占地/朝向/提亮 + 窗洞贯通/墙垛/窗楣/立体窗斜墙对齐')
