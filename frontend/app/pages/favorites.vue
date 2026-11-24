<script setup lang="ts">
import type { ImportedProduct } from '~/types/imported-products'
import type { FavoriteFolder } from '~/types/favorites'

type FavoriteTab = 'personal' | 'project'

const api = useOfficeApi()
const workspace = useWorkspaceStore()
const route = useRoute()

const activeTab = ref<FavoriteTab>(route.query.tab === 'project' ? 'project' : 'personal')
const folders = ref<FavoriteFolder[]>([])
const selectedFolderId = ref('')
const allFavoriteCount = ref(0)
const personalItems = ref<ImportedProduct[]>([])
const personalLoading = ref(false)
const personalError = ref<string | null>(null)
const projectItems = ref<ImportedProduct[]>([])
const projectLoading = ref(false)
const projectError = ref<string | null>(null)
const selectedProjectId = ref('')
const search = ref('')
const creating = ref(false)
const showCreate = ref(false)
const newFolderName = ref('')
const favoriteDialog = ref<{ open: (product: ImportedProduct, section?: FavoriteTab, projectId?: string) => void } | null>(null)
const deletingFolder = ref<FavoriteFolder | null>(null)
const deleting = ref(false)
const deleteError = ref<string | null>(null)
const deleteDialog = ref<HTMLDialogElement | null>(null)
const removingProduct = ref<ImportedProduct | null>(null)
const removing = ref(false)
const removeError = ref<string | null>(null)
const removeDialog = ref<HTMLDialogElement | null>(null)
let personalRequest = 0
let projectRequest = 0
let folderRequest = 0
let countRequest = 0

const projectOptions = computed(() => [...workspace.projects].sort((a, b) => {
  if (a.projectId === workspace.currentProjectId) return -1
  if (b.projectId === workspace.currentProjectId) return 1
  return 0
}))
const selectedFolder = computed(() => folders.value.find(folder => folder.folderId === selectedFolderId.value) || null)
const selectedProject = computed(() => workspace.projects.find(project => project.projectId === selectedProjectId.value) || null)
const currentItems = computed(() => activeTab.value === 'personal' ? personalItems.value : projectItems.value)
const currentLoading = computed(() => activeTab.value === 'personal' ? personalLoading.value : projectLoading.value)
const currentError = computed(() => activeTab.value === 'personal' ? personalError.value : projectError.value)
const filteredItems = computed(() => {
  const query = search.value.trim().toLowerCase()
  if (!query) return currentItems.value
  return currentItems.value.filter(product =>
    [product.productName, product.rspuCode, product.externalCode].some(value => value?.toLowerCase().includes(query)))
})
const listTitle = computed(() => activeTab.value === 'personal'
  ? selectedFolder.value?.name || '全部收藏'
  : selectedProject.value?.projectName || '项目收藏')

function switchTab(tab: FavoriteTab) {
  if (activeTab.value === tab) return
  activeTab.value = tab
  search.value = ''
  if (tab === 'project') void loadProjectItems()
}

function selectFolder(folderId: string) {
  if (folderId === selectedFolderId.value) return
  selectedFolderId.value = folderId
  search.value = ''
  void loadPersonalItems()
}

async function loadFolders() {
  const request = ++folderRequest
  try {
    const result = await api.loadFavoriteFolders()
    if (request !== folderRequest) return
    folders.value = result
  } catch {
    if (request === folderRequest) folders.value = []
  }
}

async function loadAllFavoriteCount() {
  const request = ++countRequest
  try {
    const result = await api.loadFavoriteProductIds()
    if (request !== countRequest) return
    allFavoriteCount.value = result.length
  } catch {
    if (request === countRequest) allFavoriteCount.value = 0
  }
}

async function loadPersonalItems() {
  const request = ++personalRequest
  const folderId = selectedFolderId.value
  personalLoading.value = true
  personalError.value = null
  try {
    const result = await api.loadFavoriteItems(folderId || undefined)
    if (request !== personalRequest || folderId !== selectedFolderId.value) return
    personalItems.value = result
  } catch (error) {
    if (request === personalRequest) personalError.value = error instanceof Error ? error.message : '收藏商品加载失败'
  } finally {
    if (request === personalRequest) personalLoading.value = false
  }
}

async function loadProjectItems() {
  const projectId = selectedProjectId.value
  if (!projectId) return
  const request = ++projectRequest
  projectLoading.value = true
  projectError.value = null
  try {
    const result = await api.loadProjectFavoriteItems(projectId)
    if (request !== projectRequest || projectId !== selectedProjectId.value) return
    projectItems.value = result
  } catch (error) {
    if (request === projectRequest) projectError.value = error instanceof Error ? error.message : '项目收藏加载失败'
  } finally {
    if (request === projectRequest) projectLoading.value = false
  }
}

function selectProject(projectId: string) {
  if (projectId === selectedProjectId.value) return
  selectedProjectId.value = projectId
  search.value = ''
  void loadProjectItems()
}

async function createFolder() {
  const name = newFolderName.value.trim()
  if (!name || creating.value) return
  creating.value = true
  try {
    await api.createFavoriteFolder(name)
    await loadFolders()
    newFolderName.value = ''
    showCreate.value = false
  } catch (error) {
    personalError.value = error instanceof Error ? error.message : '新建收藏夹失败'
  } finally {
    creating.value = false
  }
}

function openFavorite(product: ImportedProduct) {
  favoriteDialog.value?.open(product, activeTab.value, activeTab.value === 'project' ? selectedProjectId.value : undefined)
}

function handlePersonalSaved() {
  void loadFolders()
  void loadAllFavoriteCount()
  void loadPersonalItems()
}

function handleProjectSaved(payload: { projectId: string }) {
  if (payload.projectId === selectedProjectId.value) void loadProjectItems()
}

function askRemove(product: ImportedProduct) {
  removeError.value = null
  removingProduct.value = product
  nextTick(() => removeDialog.value?.showModal())
}

function cancelRemove() {
  if (removing.value) return
  removeDialog.value?.close()
  removingProduct.value = null
}

async function confirmRemove() {
  const product = removingProduct.value
  if (!product || removing.value) return
  removing.value = true
  removeError.value = null
  try {
    if (activeTab.value === 'project') {
      await api.saveProjectFavorite(selectedProjectId.value, product.rspuId, false)
      removeDialog.value?.close()
      removingProduct.value = null
      await loadProjectItems()
    } else {
      const folderId = selectedFolderId.value
      let remaining: string[] = []
      if (folderId) {
        remaining = (await api.loadFavoriteFolders(product.rspuId))
          .filter(folder => folder.selected && folder.folderId !== folderId)
          .map(folder => folder.folderId)
      }
      await api.saveProductFavoriteFolders(product.rspuId, remaining)
      removeDialog.value?.close()
      removingProduct.value = null
      handlePersonalSaved()
    }
  } catch (error) {
    removeError.value = error instanceof Error ? error.message : '取消收藏失败'
  } finally {
    removing.value = false
  }
}

function askDeleteFolder(folder: FavoriteFolder) {
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
  deleting.value = true
  deleteError.value = null
  try {
    await api.deleteFavoriteFolder(folder.folderId)
    deleteDialog.value?.close()
    const wasSelected = selectedFolderId.value === folder.folderId
    deletingFolder.value = null
    if (wasSelected) selectedFolderId.value = ''
    await Promise.all([loadFolders(), loadAllFavoriteCount(), loadPersonalItems()])
  } catch (error) {
    deleteError.value = error instanceof Error ? error.message : '删除收藏夹失败'
  } finally {
    deleting.value = false
  }
}

watch(() => workspace.currentProjectId, projectId => {
  if (!projectId) return
  selectedProjectId.value = projectId
  if (activeTab.value === 'project') void loadProjectItems()
})

onMounted(() => {
  selectedProjectId.value = workspace.currentProjectId
  void loadFolders()
  void loadAllFavoriteCount()
  void loadPersonalItems()
  if (activeTab.value === 'project') void loadProjectItems()
})
</script>

<template>
  <div class="mx-auto w-full max-w-[1600px] p-4 sm:p-6">
    <h1 class="text-xl font-bold">我的收藏</h1>
    <div class="mt-4 flex border-b border-line" role="tablist" aria-label="收藏类型">
      <button id="favorites-personal-tab" type="button" role="tab" aria-controls="favorites-personal-panel" :aria-selected="activeTab === 'personal'" class="border-b-2 px-5 py-3 text-sm transition" :class="activeTab === 'personal' ? 'border-bronze font-semibold text-bronze-dark' : 'border-transparent text-muted hover:text-ink'" @click="switchTab('personal')">个人收藏</button>
      <button id="favorites-project-tab" type="button" role="tab" aria-controls="favorites-project-panel" :aria-selected="activeTab === 'project'" class="border-b-2 px-5 py-3 text-sm transition" :class="activeTab === 'project' ? 'border-bronze font-semibold text-bronze-dark' : 'border-transparent text-muted hover:text-ink'" @click="switchTab('project')">项目收藏</button>
    </div>

    <section v-if="activeTab === 'personal'" id="favorites-personal-panel" data-testid="personal-favorites-panel" role="tabpanel" aria-labelledby="favorites-personal-tab" class="mt-5 flex flex-col gap-5 lg:flex-row lg:items-start">
      <aside class="w-full shrink-0 rounded-xl border border-line bg-white p-4 lg:w-52" aria-label="收藏夹">
        <div class="px-1 pb-2 text-xs text-muted">收藏夹</div>
        <button type="button" class="flex w-full items-center rounded-lg px-3 py-2.5 text-left text-sm transition" :class="selectedFolderId === '' ? 'bg-cream font-semibold text-bronze-dark' : 'text-[#615b55] hover:bg-bg'" @click="selectFolder('')">
          <span class="truncate">全部收藏</span><span class="ml-auto pl-2 text-xs text-muted">{{ allFavoriteCount }}</span>
        </button>
        <div v-for="folder in folders" :key="folder.folderId" class="group mt-1 flex items-center rounded-lg transition" :class="selectedFolderId === folder.folderId ? 'bg-cream' : 'hover:bg-bg'">
          <button type="button" class="flex min-w-0 flex-1 items-center rounded-lg px-3 py-2.5 text-left text-sm" :class="selectedFolderId === folder.folderId ? 'font-semibold text-bronze-dark' : 'text-[#615b55]'" @click="selectFolder(folder.folderId)">
            <span class="truncate">{{ folder.name }}</span><span class="ml-auto pl-2 text-xs text-muted">{{ folder.itemCount }}</span>
          </button>
          <button v-if="!folder.defaultFolder" type="button" class="mr-2 grid h-7 w-7 flex-none place-items-center rounded-md text-[#b3402f] opacity-0 transition hover:bg-red-50 focus-visible:opacity-100 group-hover:opacity-100 group-focus-within:opacity-100" :aria-label="`删除收藏夹${folder.name}`" title="删除收藏夹" @click="askDeleteFolder(folder)">
            <AppIcon name="trash" size="4" />
          </button>
        </div>
        <button v-if="!showCreate" type="button" class="mt-3 flex w-full items-center justify-center gap-1.5 rounded-lg border border-line px-3 py-2.5 text-xs font-semibold text-bronze-dark transition hover:bg-cream" @click="showCreate = true"><AppIcon name="plus" size="3.5" />新建收藏夹</button>
        <div v-else class="mt-3 space-y-2">
          <input v-model="newFolderName" type="text" maxlength="50" placeholder="输入收藏夹名称" aria-label="新建收藏夹名称" class="w-full rounded-lg border border-bronze bg-white px-3 py-2 text-sm outline-bronze" :disabled="creating" @keyup.enter="createFolder">
          <button type="button" class="w-full rounded-lg bg-cream px-3 py-2 text-xs font-semibold text-bronze-dark disabled:opacity-50" :disabled="creating" @click="createFolder">{{ creating ? '创建中…' : '创建收藏夹' }}</button>
        </div>
        <p class="mt-4 px-1 text-[11px] leading-5 text-muted">个人收藏与当前账号绑定，切换项目不影响。</p>
      </aside>

      <div class="min-w-0 flex-1">
        <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 class="text-base font-bold">{{ listTitle }}<span class="ml-2 text-xs font-normal text-muted">共 {{ currentItems.length }} 件</span></h2>
          <input v-model="search" type="search" placeholder="搜索收藏的商品" aria-label="搜索收藏的商品" class="w-full rounded-lg border border-line bg-white px-3 py-2 text-sm outline-bronze sm:w-56">
        </div>
        <FavoritesProductGrid :items="filteredItems" :loading="currentLoading" :error="currentError" :total="currentItems.length" :searching="!!search.trim()" :empty-text="selectedFolderId ? '该收藏夹还没有商品' : '还没有收藏的商品'" @open="openFavorite" @remove="askRemove" @retry="loadPersonalItems" />
      </div>
    </section>

    <section v-else id="favorites-project-panel" data-testid="project-favorites-panel" role="tabpanel" aria-labelledby="favorites-project-tab" class="mt-5">
      <div class="mb-5 flex flex-wrap items-center gap-3">
        <span class="text-sm text-muted">项目</span>
        <select :value="selectedProjectId" aria-label="选择项目" class="min-w-60 rounded-lg border border-line bg-white px-3 py-2.5 text-sm font-semibold outline-bronze" @change="selectProject(($event.target as HTMLSelectElement).value)">
          <option v-for="project in projectOptions" :key="project.projectId" :value="project.projectId">{{ project.projectName }}{{ project.projectId === workspace.currentProjectId ? '（当前项目）' : '' }}</option>
        </select>
        <span class="text-xs text-muted">项目收藏仅当前账号可见，不同项目互不影响。</span>
      </div>
      <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h2 class="text-base font-bold">{{ listTitle }}<span class="ml-2 text-xs font-normal text-muted">共 {{ currentItems.length }} 件</span></h2>
        <input v-model="search" type="search" placeholder="搜索收藏的商品" aria-label="搜索收藏的商品" class="w-full rounded-lg border border-line bg-white px-3 py-2 text-sm outline-bronze sm:w-56">
      </div>
      <FavoritesProductGrid :items="filteredItems" :loading="currentLoading" :error="currentError" :total="currentItems.length" :searching="!!search.trim()" empty-text="该项目还没有收藏的商品" @open="openFavorite" @remove="askRemove" @retry="loadProjectItems" />
    </section>

    <dialog ref="removeDialog" class="m-auto w-[min(430px,calc(100%_-_32px))] rounded-2xl border border-line bg-white p-0 text-ink shadow-panel backdrop:bg-black/30" aria-label="取消收藏" @cancel="event => { if (removing) event.preventDefault() }" @close="removingProduct = null">
      <div class="p-6">
        <div class="mb-4 flex items-start justify-between gap-4">
          <h2 class="text-lg font-bold">取消收藏</h2>
          <button type="button" class="rounded-lg p-1.5 text-muted transition hover:bg-cream disabled:opacity-50" aria-label="关闭取消收藏确认" :disabled="removing" @click="cancelRemove"><AppIcon name="close" size="5" /></button>
        </div>
        <div class="mb-4 flex gap-3 rounded-xl border border-red-100 bg-red-50 p-3.5">
          <AppIcon name="warning" size="5" class="mt-0.5 flex-none text-[#b3402f]" />
          <p v-if="activeTab === 'project'" class="text-sm leading-6">确定将「<strong>{{ removingProduct?.productName || '商品' }}</strong>」从项目「<strong>{{ selectedProject?.projectName }}</strong>」取消收藏吗？</p>
          <p v-else-if="selectedFolderId" class="text-sm leading-6">确定将「<strong>{{ removingProduct?.productName || '商品' }}</strong>」从「<strong>{{ selectedFolder?.name }}</strong>」取消收藏吗？</p>
          <p v-else class="text-sm leading-6">确定取消收藏「<strong>{{ removingProduct?.productName || '商品' }}</strong>」吗？将从你的所有个人收藏夹中移除。</p>
        </div>
        <p v-if="activeTab === 'project'" class="mb-5 text-xs leading-5 text-muted">仅影响当前项目的收藏，个人收藏和其他项目不受影响。若需收藏到其他项目，可点卡片底部的「调整收藏夹」。</p>
        <p v-else class="mb-5 text-xs leading-5 text-muted">其他收藏夹中的收藏不受影响，可随时在商品库重新收藏。若只想换分组，可点卡片底部的「调整收藏夹」。</p>
        <div v-if="removeError" class="mb-4 rounded-lg border border-red-100 bg-red-50 px-3 py-2 text-xs text-red-800" role="alert">{{ removeError }}</div>
        <div class="flex justify-end gap-3 border-t border-line pt-4">
          <button type="button" class="rounded-lg border border-line px-5 py-2.5 text-sm disabled:opacity-50" :disabled="removing" @click="cancelRemove">取消</button>
          <button type="button" class="rounded-lg bg-[#b3402f] px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-[#9c3627] disabled:opacity-50" :disabled="removing" @click="confirmRemove">{{ removing ? '处理中…' : '确认取消收藏' }}</button>
        </div>
      </div>
    </dialog>

    <dialog ref="deleteDialog" class="m-auto w-[min(420px,calc(100%_-_32px))] rounded-2xl border border-line bg-white p-0 text-ink shadow-panel backdrop:bg-black/30" aria-label="删除收藏夹" @cancel="event => { if (deleting) event.preventDefault() }" @close="deletingFolder = null">
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

    <FavoriteFolderDialog ref="favoriteDialog" @saved="handlePersonalSaved" @project-saved="handleProjectSaved" />
  </div>
</template>
