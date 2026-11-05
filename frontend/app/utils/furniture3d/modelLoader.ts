import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'

/**
 * GLB 模型加载与缓存（RENDER-004 P2）。
 * 多个家具引用同一个 GLB 时只下载一次；返回值为克隆实例，调用方自行缩放/定位。
 */

const loader = new GLTFLoader()
const cache = new Map<string, Promise<THREE.Group>>()

/** 加载（含缓存）。同一个 url 并发/重复调用共享同一个 Promise。 */
export function loadFurnitureModel(url: string): Promise<THREE.Group> {
  let pending = cache.get(url)
  if (!pending) {
    pending = new Promise((resolve, reject) => {
      loader.load(url, gltf => resolve(gltf.scene), undefined, reject)
    })
    cache.set(url, pending)
    pending.catch(() => cache.delete(url)) // 失败不缓存，允许重试
  }
  return pending.then(scene => scene.clone(true))
}

export function furnitureModelCacheSize(): number {
  return cache.size
}

/** 深度释放一个对象树独占的几何体与材质（共享材质由调用方持有时不要调用）。 */
export function disposeObjectTree(object: THREE.Object3D, sharedMaterials?: Set<THREE.Material>) {
  object.traverse(node => {
    const mesh = node as THREE.Mesh
    if (mesh.geometry) mesh.geometry.dispose()
    const material = mesh.material as THREE.Material | THREE.Material[] | undefined
    for (const item of Array.isArray(material) ? material : material ? [material] : []) {
      if (!sharedMaterials?.has(item)) item.dispose()
    }
  })
}

/** 清空模型缓存并释放 GPU 资源（视图销毁时调用）。 */
export async function releaseFurnitureModelCache() {
  const pending = Array.from(cache.values())
  cache.clear()
  const scenes = await Promise.allSettled(pending)
  for (const result of scenes) {
    if (result.status === 'fulfilled') disposeObjectTree(result.value)
  }
}
