/**
 * 删除楼层后清理浏览器侧残留的楼层数据：office store 本地画布草稿（localStorage）
 * 与图纸上传缓存（IndexedDB，库结构见 ProjectDrawingRecognition.vue）。
 * 清理为尽力而为，失败仅告警，不阻塞删除流程。
 */
const DB_NAME = 'rsdp-office-files'
const DB_STORE = 'floorFiles'
const DB_VERSION = 2

function openFloorFileDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onerror = () => reject(request.error)
    request.onsuccess = () => resolve(request.result)
    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(DB_STORE)) db.createObjectStore(DB_STORE, { keyPath: 'id' })
    }
  })
}

export async function clearFloorClientData(floorId: string) {
  if (typeof window === 'undefined') return
  try {
    useOfficeStore().removeDraft(floorId)
  } catch (error) {
    console.warn('清理楼层画布草稿失败', error)
  }
  try {
    const db = await openFloorFileDb()
    if (db.objectStoreNames.contains(DB_STORE)) {
      await new Promise<void>((resolve, reject) => {
        const tx = db.transaction(DB_STORE, 'readwrite')
        const store = tx.objectStore(DB_STORE)
        const getAll = store.getAll()
        getAll.onsuccess = () => {
          for (const record of getAll.result as Array<{ id: string, floorId?: string }>) {
            if (record?.floorId === floorId) store.delete(record.id)
          }
        }
        tx.oncomplete = () => resolve()
        tx.onerror = () => reject(tx.error)
      })
    }
    db.close()
  } catch (error) {
    console.warn('清理楼层图纸文件失败', error)
  }
}
