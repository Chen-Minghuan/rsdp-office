<script setup lang="ts">
import type { FavoriteFolder } from '~/types/favorites'
import type { ImportedProduct } from '~/types/imported-products'

type FavoriteSection = 'project' | 'personal'

const api = useOfficeApi()
const workspace = useWorkspaceStore()
const dialog = ref<HTMLDialogElement | null>(null)
const product = ref<ImportedProduct | null>(null)
const activeSection = ref<FavoriteSection>('personal')
const folders = ref<FavoriteFolder[]>([])
const selectedFolderIds = ref<Set<string>>(new Set())
const loadedRspuId = ref<string | null>(null)
const loading = ref(false)
const projectSelected = ref(false)
const selectedProjectId = ref('')
const projectLoadedKey = ref('')
const projectLoading = ref(false)
const projectError = ref<string | null>(null)
const saving = ref(false)
const creating = ref(false)
const showCreate = ref(false)
const newFolderName = ref('')
const errorMessage = ref<string | null>(null)
const deletingFolder = ref<FavoriteFolder | null>(null)
const deleting = ref(false)
const deleteError = ref<string | null>(null)
const deleteDialog = ref<HTMLDialogElement | null>(null)
const expandedFolderIds = ref<Set<string>>(new Set())
const folderItems = ref<Record<string, ImportedProduct[]>>({})
const folderItemsLoading = ref<Set<string>>(new Set())
const removingItemKey = ref('')
const busy = computed(() => saving.value || creating.value || deleting.value || !!removingItemKey.value)
const selectedCount = computed(() => selectedFolderIds.value.size)
const currentProject = computed(() => workspace.currentProject)
const selectedProject = computed(() => workspace.projects.find(project => project.projectId === selectedProjectId.value) || null)
const projectOptions = computed(() => [...workspace.projects].sort((a, b) => {
  if (a.projectId === workspace.currentProjectId) return -1
  if (b.projectId === workspace.currentProjectId) return 1
  return 0
}))
const canSave = computed(() => {
  if (!product.value || busy.value) return false
  if (activeSection.value === 'project') {
    return !!selectedProject.value && !projectLoading.value
      && projectLoadedKey.value === `${selectedProject.value.projectId}:${product.value.rspuId}`
  }
  return loadedRspuId.value === product.value.rspuId && !loading.value
})
const emit = defineEmits<{
  saved: [payload: { rspuId: string, folderIds: string[] }]
  projectSaved: [payload: { rspuId: string, projectId: string, selected: boolean }]
}>()
let session = 0
let projectRequest = 0

async function open(value: ImportedProduct, section: FavoriteSection = 'personal', projectId?: string) {
  const currentSession = ++session
  product.value = value
  activeSection.value = section
  folders.value = []
  selectedFolderIds.value = new Set()
  loadedRspuId.value = null
  loading.value = false
  projectRequest++
  projectSelected.value = false
  selectedProjectId.value = projectId || workspace.currentProjectId
  projectLoadedKey.value = ''
  projectLoading.value = false
  projectError.value = null
  saving.value = false
  creating.value = false
  showCreate.value = false
  newFolderName.value = ''
  errorMessage.value = null
  deletingFolder.value = null
  deleting.value = false
  deleteError.value = null
  deleteDialog.value?.close()
  expandedFolderIds.value = new Set()
  folderItems.value = {}
  folderItemsLoading.value = new Set()
  removingItemKey.value = ''
  await nextTick()
  if (currentSession !== session) return
  dialog.value?.showModal()
  await Promise.all([loadFolders(), loadProjectFavorite()])
}

function switchSection(section: FavoriteSection) {
  if (busy.value) return
  activeSection.value = section
}

async function loadFolders(preserveSelection = false) {
  if (!product.value) return
  const currentSession = session
  const rspuId = product.value.rspuId
  loading.value = true
  errorMessage.value = null
  try {
    const result = await api.loadFavoriteFolders(rspuId)
    if (currentSession !== session || product.value?.rspuId !== rspuId) return
    folders.value = result
    const availableIds = new Set(result.map(folder => folder.folderId))
    selectedFolderIds.value = preserveSelection
      ? new Set([...selectedFolderIds.value].filter(id => availableIds.has(id)))
      : new Set(result.filter(folder => folder.selected).map(folder => folder.folderId))
    loadedRspuId.value = rspuId
  } catch (error) {
    if (currentSession === session) errorMessage.value = error instanceof Error ? error.message : '收藏夹加载失败'
  } finally {
    if (currentSession === session) loading.value = false
  }
}

async function loadProjectFavorite(projectId = selectedProjectId.value) {
  if (!product.value || !projectId) return
  const currentSession = session
  const request = ++projectRequest
  const rspuId = product.value.rspuId
  projectLoading.value = true
  projectError.value = null
  try {
    const result = await api.loadProjectFavorite(projectId, rspuId)
    if (currentSession !== session || request !== projectRequest || product.value?.rspuId !== rspuId || selectedProjectId.value !== projectId) return
    projectSelected.value = result.selected
    projectLoadedKey.value = `${projectId}:${rspuId}`
  } catch (error) {
    if (currentSession === session && request === projectRequest) projectError.value = error instanceof Error ? error.message : '项目收藏加载失败'
  } finally {
    if (currentSession === session && request === projectRequest) projectLoading.value = false
  }
}

function selectProject(projectId: string) {
  if (busy.value || projectId === selectedProjectId.value) return
  selectedProjectId.value = projectId
  projectSelected.value = false
  projectLoadedKey.value = ''
  void loadProjectFavorite(projectId)
}

function close() {
  if (busy.value) return
  session++
  projectRequest++
  loadedRspuId.value = null
  projectLoadedKey.value = ''
  dialog.value?.close()
}

function cancelDialog(event: Event) {
  if (busy.value) {
    event.preventDefault()
    return
  }
  session++
  projectRequest++
  loadedRspuId.value = null
  projectLoadedKey.value = ''
}

function toggleFolder(folderId: string, event: Event) {
  if (busy.value) return
  const checked = (event.target as HTMLInputElement).checked
  const next = new Set(selectedFolderIds.value)
  if (checked) next.add(folderId)
  else next.delete(folderId)
  selectedFolderIds.value = next
}

async function createFolder() {
  if (busy.value) return
  const name = newFolderName.value.trim()
  if (!name) {
    errorMessage.value = '收藏夹名称不能为空'
    return
  }
  creating.value = true
  errorMessage.value = null
  const currentSession = session
  try {
    const folder = await api.createFavoriteFolder(name)
    if (currentSession !== session) return
    folders.value = [...folders.value, folder]
    selectedFolderIds.value = new Set([...selectedFolderIds.value, folder.folderId])
    newFolderName.value = ''
    showCreate.value = false
  } catch (error) {
    if (currentSession === session) errorMessage.value = error instanceof Error ? error.message : '新建收藏夹失败'
  } finally {
    if (currentSession === session) creating.value = false
  }
}

function askDeleteFolder(folder: FavoriteFolder) {
  if (busy.value) return
  deleteError.value = null
  deletingFolder.value = folder
  nextTick(() => deleteDialog.value?.showModal())
}

function cancelDeleteFolder() {
  if (deleting.value) return
  deleteDialog.value?.close()
  deletingFolder.value = null
}

async function confirmDeleteFolder() {
  const folder = deletingFolder.value
  if (!folder || deleting.value) return
  const currentSession = session
  deleting.value = true
  deleteError.value = null
  try {
    await api.deleteFavoriteFolder(folder.folderId)
    if (currentSession !== session) return
    deleteDialog.value?.close()
    deletingFolder.value = null
    await loadFolders(true)
    if (currentSession === session && product.value) {
      emit('saved', { rspuId: product.value.rspuId, folderIds: folders.value.filter(folder => folder.selected).map(folder => folder.folderId) })
    }
  } catch (error) {
    if (currentSession === session) deleteError.value = error instanceof Error ? error.message : '删除收藏夹失败'
  } finally {
    if (currentSession === session) deleting.value = false
  }
}

function isExpanded(folderId: string) {
  return expandedFolderIds.value.has(folderId)
}

function toggleExpand(folderId: string) {
  const next = new Set(expandedFolderIds.value)
  if (next.has(folderId)) {
    next.delete(folderId)
  } else {
    next.add(folderId)
    void loadFolderItems(folderId)
  }
  expandedFolderIds.value = next
}

async function loadFolderItems(folderId: string) {
  if (!product.value) return
  const currentSession = session
  folderItemsLoading.value = new Set([...folderItemsLoading.value, folderId])
  try {
    const result = await api.loadFavoriteItems(folderId)
    if (currentSession !== session) return
    folderItems.value = { ...folderItems.value, [folderId]: result }
  } catch (error) {
    if (currentSession === session) errorMessage.value = error instanceof Error ? error.message : '收藏夹内容加载失败'
  } finally {
    if (currentSession === session) {
      const next = new Set(folderItemsLoading.value)
      next.delete(folderId)
      folderItemsLoading.value = next
    }
  }
}

async function removeItemFromFolder(folder: FavoriteFolder, item: ImportedProduct) {
  if (busy.value) return
  removingItemKey.value = `${folder.folderId}:${item.rspuId}`
  errorMessage.value = null
  const currentSession = session
  try {
    const selected = (await api.loadFavoriteFolders(item.rspuId))
      .filter(entry => entry.selected && entry.folderId !== folder.folderId)
      .map(entry => entry.folderId)
    await api.saveProductFavoriteFolders(item.rspuId, selected)
    if (currentSession !== session) return
    if (item.rspuId === product.value?.rspuId) {
      selectedFolderIds.value = new Set([...selectedFolderIds.value].filter(id => id !== folder.folderId))
    }
    await Promise.all([loadFolders(true), loadFolderItems(folder.folderId)])
    if (currentSession === session && product.value) {
      emit('saved', { rspuId: product.value.rspuId, folderIds: folders.value.filter(folder => folder.selected).map(folder => folder.folderId) })
    }
  } catch (error) {
    if (currentSession === session) errorMessage.value = error instanceof Error ? error.message : '取消收藏失败'
  } finally {
    if (currentSession === session) removingItemKey.value = ''
  }
}

async function save() {
  if (!product.value || !canSave.value) return
  saving.value = true
  const currentSession = session
  const rspuId = product.value.rspuId
  if (activeSection.value === 'project') {
    const projectId = selectedProjectId.value
    const selected = !projectSelected.value
    projectError.value = null
    try {
      await api.saveProjectFavorite(projectId, rspuId, selected)
      if (currentSession !== session || product.value?.rspuId !== rspuId || selectedProjectId.value !== projectId) return
      emit('projectSaved', { rspuId, projectId, selected })
      dialog.value?.close()
    } catch (error) {
      if (currentSession === session) projectError.value = error instanceof Error ? error.message : '项目收藏保存失败'
    } finally {
      if (currentSession === session) saving.value = false
    }
    return
  }

  errorMessage.value = null
  const folderIds = [...selectedFolderIds.value]
  try {
    await api.saveProductFavoriteFolders(rspuId, folderIds)
    if (currentSession !== session || product.value?.rspuId !== rspuId) return
    emit('saved', { rspuId, folderIds })
    dialog.value?.close()
  } catch (error) {
    if (currentSession === session) errorMessage.value = error instanceof Error ? error.message : '收藏保存失败'
  } finally {
    if (currentSession === session) saving.value = false
  }
}

onBeforeUnmount(() => {
  session++
  projectRequest++
})

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="m-auto max-h-[90dvh] w-[min(560px,calc(100%_-_32px))] overflow-y-auto rounded-2xl border border-line bg-white p-0 text-ink shadow-panel backdrop:bg-black/30" aria-labelledby="favorite-dialog-title" @cancel="cancelDialog" @click="event => { if (event.target === event.currentTarget) close() }">
    <div class="p-6">
      <div class="mb-5 flex items-start justify-between gap-4">
        <h2 id="favorite-dialog-title" class="text-lg font-bold">收藏商品</h2>
        <button type="button" class="rounded-lg p-1.5 text-muted transition hover:bg-cream disabled:opacity-50" aria-label="关闭收藏夹弹窗" :disabled="busy" @click="close"><AppIcon name="close" size="5" /></button>
      </div>

      <div v-if="product" class="mb-5 flex items-center gap-4 rounded-xl border border-line bg-[#faf9f7] p-3">
        <div class="h-16 w-20 shrink-0 overflow-hidden rounded-lg border border-line bg-white"><ProductImagePreview :src="product.primaryImageUrl" alt="" size="small" /></div>
        <div class="min-w-0"><div class="truncate text-sm font-semibold">{{ product.productName || '未命名商品' }}</div><div class="mt-1 truncate text-xs text-muted">{{ product.rspuCode || product.externalCode || product.rspuId }}</div></div>
      </div>

      <div class="mb-5 flex border-b border-line" role="tablist" aria-label="收藏类型">
        <button id="project-favorite-tab" type="button" role="tab" aria-controls="project-favorite-panel" :aria-selected="activeSection === 'project'" class="flex-1 border-b-2 px-4 py-3 text-sm transition" :class="activeSection === 'project' ? 'border-bronze font-semibold text-bronze-dark' : 'border-transparent text-muted hover:text-ink'" :disabled="busy" @click="switchSection('project')">项目收藏</button>
        <button id="personal-favorite-tab" type="button" role="tab" aria-controls="personal-favorite-panel" :aria-selected="activeSection === 'personal'" class="flex-1 border-b-2 px-4 py-3 text-sm transition" :class="activeSection === 'personal' ? 'border-bronze font-semibold text-bronze-dark' : 'border-transparent text-muted hover:text-ink'" :disabled="busy" @click="switchSection('personal')">个人收藏</button>
      </div>

      <section v-if="activeSection === 'project'" id="project-favorite-panel" role="tabpanel" aria-labelledby="project-favorite-tab">
        <div class="mb-3 text-xs text-muted">选择项目收藏夹</div>
        <div v-if="projectError" class="mb-3 rounded-lg border border-red-100 bg-red-50 px-3 py-2 text-xs text-red-800" role="alert">{{ projectError }}</div>
        <div v-if="projectOptions.length" class="max-h-64 scroll-smooth overflow-y-auto rounded-xl border border-line">
          <label v-for="project in projectOptions" :key="project.projectId" class="relative flex min-h-[54px] cursor-pointer items-center gap-3 border-b border-line px-4 py-3 text-sm transition last:border-b-0" :class="selectedProjectId === project.projectId ? 'bg-cream font-semibold text-bronze-dark shadow-[inset_3px_0_0_#9a7645]' : 'bg-white hover:bg-bg'" :aria-busy="selectedProjectId === project.projectId && projectLoading">
            <input type="radio" name="project-favorite-folder" class="h-4 w-4 flex-none accent-[#9a7645]" :aria-label="project.projectName" :checked="selectedProjectId === project.projectId" :disabled="busy" @change="selectProject(project.projectId)">
            <span class="min-w-0 flex-1 truncate">{{ project.projectName }}</span>
            <span v-if="project.projectId === currentProject?.projectId" class="shrink-0 rounded bg-[#eee2d2] px-2 py-1 text-[10px] font-normal text-bronze-dark">当前项目 · 默认</span>
            <span v-else-if="selectedProjectId === project.projectId && projectLoading" class="h-3.5 w-3.5 shrink-0 animate-spin rounded-full border-2 border-bronze/25 border-t-bronze" aria-hidden="true"></span>
            <span v-else-if="selectedProjectId === project.projectId && projectSelected && projectLoadedKey" class="shrink-0 text-xs font-normal text-green">已收藏</span>
          </label>
        </div>
        <div v-else class="py-8 text-center text-sm text-muted">当前账号没有可访问的项目。</div>
        <div class="mt-2 text-xs text-muted">共 {{ projectOptions.length }} 个可访问项目，列表可滚动</div>
        <div class="mt-3 flex h-4 items-center justify-center text-xs text-muted" aria-live="polite">
          <span v-if="projectLoading">正在读取所选项目…</span>
        </div>
        <p class="mt-2 text-xs leading-5 text-muted">商品将收藏到所选项目，并仍只对当前账号可见。切换当前项目不会改变已经保存的项目归属。</p>
      </section>

      <section v-else id="personal-favorite-panel" role="tabpanel" aria-labelledby="personal-favorite-tab">
        <p class="mb-3 text-xs text-muted">选择个人收藏夹（可多选），不受项目切换影响</p>
        <div v-if="errorMessage" class="mb-3 rounded-lg border border-red-100 bg-red-50 px-3 py-2 text-xs text-red-800" role="alert">{{ errorMessage }}</div>
        <div v-if="loading" class="py-8 text-center text-sm text-muted" role="status">正在加载收藏夹…</div>
        <template v-else>
          <div class="max-h-64 space-y-2 overflow-y-auto">
            <div v-for="folder in folders" :key="folder.folderId" class="group relative overflow-hidden rounded-lg border transition" :class="selectedFolderIds.has(folder.folderId) ? 'border-bronze bg-cream' : 'border-line bg-white hover:bg-bg'">
              <label class="flex cursor-pointer items-center gap-3 px-4 py-3" :class="folder.defaultFolder ? 'pr-12' : 'pr-[76px]'">
                <input type="checkbox" class="h-4 w-4 accent-[#9a7645]" :aria-label="`${folder.name}，${folder.itemCount}件`" :checked="selectedFolderIds.has(folder.folderId)" :disabled="busy" @change="toggleFolder(folder.folderId, $event)">
                <span class="min-w-0 flex-1 truncate text-sm" :class="selectedFolderIds.has(folder.folderId) ? 'font-semibold' : ''">{{ folder.name }}</span>
                <span class="text-xs text-muted">{{ folder.itemCount }}</span>
              </label>
              <div class="absolute right-3 top-3 flex items-center gap-1">
                <button type="button" class="grid h-7 w-7 place-items-center rounded-md border border-line bg-white text-muted transition hover:text-ink" :aria-label="`${isExpanded(folder.folderId) ? '收起' : '展开'}收藏夹${folder.name}`" :aria-expanded="isExpanded(folder.folderId)" title="查看收藏夹内容" @click="toggleExpand(folder.folderId)">
                  <AppIcon name="chevronDown" size="3.5" :class="isExpanded(folder.folderId) ? '' : '-rotate-90'" />
                </button>
                <button v-if="!folder.defaultFolder" type="button" class="grid h-7 w-7 place-items-center rounded-md text-[#b3402f] opacity-0 transition hover:bg-red-50 focus-visible:opacity-100 group-hover:opacity-100 group-focus-within:opacity-100 disabled:opacity-30" :aria-label="`删除收藏夹${folder.name}`" title="删除收藏夹" :disabled="busy" @click="askDeleteFolder(folder)">
                  <AppIcon name="trash" size="4" />
                </button>
              </div>
              <div v-if="isExpanded(folder.folderId)" class="border-t border-line bg-[#fdfcfa] px-3 py-2">
                <div v-if="folderItemsLoading.has(folder.folderId)" class="py-3 text-center text-xs text-muted" role="status">正在加载收藏夹内容…</div>
                <template v-else>
                  <div v-if="!folderItems[folder.folderId]?.length" class="py-3 text-center text-xs text-muted">该收藏夹还没有商品</div>
                  <div v-for="item in folderItems[folder.folderId]" :key="item.rspuId" class="flex items-center gap-2.5 border-b border-dashed border-line py-2 last:border-b-0">
                    <div class="h-9 w-12 flex-none overflow-hidden rounded-md border border-line bg-white"><ProductImagePreview :src="item.primaryImageUrl" alt="" size="small" /></div>
                    <div class="min-w-0 flex-1">
                      <div class="truncate text-xs font-semibold">{{ item.productName || '未命名商品' }}</div>
                      <div class="mt-0.5 truncate text-[10px] text-muted">{{ item.rspuCode || item.externalCode || item.rspuId }}</div>
                    </div>
                    <button type="button" class="flex-none rounded-md bg-red-50 px-2.5 py-1.5 text-[11px] font-semibold text-[#b3402f] transition hover:bg-red-100 disabled:opacity-50" :aria-label="`取消收藏${item.productName || '商品'}`" :disabled="removingItemKey === `${folder.folderId}:${item.rspuId}`" @click="removeItemFromFolder(folder, item)">{{ removingItemKey === `${folder.folderId}:${item.rspuId}` ? '处理中…' : '取消收藏' }}</button>
                  </div>
                  <p class="px-0.5 pt-1.5 text-[10px] text-muted">「取消收藏」只把商品移出当前收藏夹，其他收藏夹不受影响。</p>
                </template>
              </div>
            </div>
          </div>

          <button v-if="!showCreate" type="button" class="mt-3 flex w-full items-center gap-2 rounded-lg border border-line px-4 py-3 text-left text-sm text-bronze-dark transition hover:bg-cream" :disabled="busy" @click="showCreate = true"><AppIcon name="plus" size="4" />新建收藏夹</button>
          <div v-else class="mt-3 flex gap-2">
            <input v-model="newFolderName" type="text" maxlength="50" aria-label="新建收藏夹名称" placeholder="输入收藏夹名称" class="min-w-0 flex-1 rounded-lg border border-bronze bg-white px-3 py-2.5 text-sm outline-bronze" :disabled="creating || saving" @keyup.enter="createFolder">
            <button type="button" class="shrink-0 rounded-lg bg-cream px-4 text-sm font-semibold text-bronze-dark disabled:opacity-50" :disabled="creating || saving" @click="createFolder">{{ creating ? '创建中…' : '创建收藏夹' }}</button>
          </div>
        </template>
      </section>

      <div class="mt-6 border-t border-line pt-4">
        <div class="mb-4 flex items-center justify-between gap-3 text-xs">
          <span class="text-green">{{ activeSection === 'project' ? '项目与个人收藏互不影响' : `已选 ${selectedCount} 个收藏夹` }}</span>
          <button v-if="activeSection === 'project' && projectError && !projectLoading" type="button" class="font-semibold text-bronze-dark" @click="loadProjectFavorite()">重新加载</button>
          <button v-else-if="activeSection === 'personal' && errorMessage && !loading && folders.length === 0" type="button" class="font-semibold text-bronze-dark" @click="loadFolders()">重新加载</button>
        </div>
        <div class="flex justify-end gap-3">
          <button type="button" class="rounded-lg border border-line px-5 py-2.5 text-sm disabled:opacity-50" :disabled="busy" @click="close">取消</button>
          <button type="button" class="rounded-lg bg-bronze px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-bronze-dark disabled:opacity-50" :disabled="!canSave" @click="save">{{ saving ? '保存中…' : activeSection === 'project' ? (projectLoading ? '读取中…' : projectSelected ? '取消收藏' : '确定收藏') : `确定收藏（${selectedCount}）` }}</button>
        </div>
      </div>
    </div>
  </dialog>

  <dialog ref="deleteDialog" class="m-auto w-[min(400px,calc(100%_-_32px))] rounded-2xl border border-line bg-white p-0 text-ink shadow-panel backdrop:bg-black/40" aria-label="删除收藏夹" @cancel="event => { if (deleting) event.preventDefault() }" @close="deletingFolder = null">
    <div class="p-6">
      <div class="mb-4 flex items-start justify-between gap-4">
        <h2 class="text-lg font-bold">删除收藏夹</h2>
        <button type="button" class="rounded-lg p-1.5 text-muted transition hover:bg-cream disabled:opacity-50" aria-label="关闭删除确认" :disabled="deleting" @click="cancelDeleteFolder"><AppIcon name="close" size="5" /></button>
      </div>
      <div class="mb-4 flex gap-3 rounded-xl border border-red-100 bg-red-50 p-3.5">
        <AppIcon name="warning" size="5" class="mt-0.5 flex-none text-[#b3402f]" />
        <p class="text-sm leading-6">确定删除收藏夹「<strong>{{ deletingFolder?.name }}</strong>」吗？其中的 <strong>{{ deletingFolder?.itemCount }}</strong> 件商品将同时移出该收藏夹。</p>
      </div>
      <p class="mb-5 text-xs leading-5 text-muted">已加入其他收藏夹的商品不受影响；仅在这个收藏夹中的商品将从你的个人收藏中移除。此操作不可恢复。</p>
      <div v-if="deleteError" class="mb-4 rounded-lg border border-red-100 bg-red-50 px-3 py-2 text-xs text-red-800" role="alert">{{ deleteError }}</div>
      <div class="flex justify-end gap-3 border-t border-line pt-4">
        <button type="button" class="rounded-lg border border-line px-5 py-2.5 text-sm disabled:opacity-50" :disabled="deleting" @click="cancelDeleteFolder">取消</button>
        <button type="button" class="rounded-lg bg-[#b3402f] px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-[#9c3627] disabled:opacity-50" :disabled="deleting" @click="confirmDeleteFolder">{{ deleting ? '删除中…' : '确认删除' }}</button>
      </div>
    </div>
  </dialog>
</template>
