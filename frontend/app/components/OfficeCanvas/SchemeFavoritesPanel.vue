<script setup lang="ts">
import type { ImportedProduct } from '~/types/imported-products'
import type { FavoriteFolder } from '~/types/favorites'

const section = defineModel<'project' | 'personal'>('section', { required: true })
const api = useOfficeApi()
const workspace = useWorkspaceStore()
const folders = ref<FavoriteFolder[]>([])
const selectedFolderId = ref('')
const allFavoriteCount = ref(0)
const items = ref<ImportedProduct[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const selectedProjectId = ref(workspace.currentProjectId)
const search = ref('')
const showCreate = ref(false)
const newFolderName = ref('')
const creating = ref(false)
const favoriteDialog = ref<{ open: (product: ImportedProduct, section?: 'project' | 'personal', projectId?: string) => void } | null>(null)
const removingProduct = ref<ImportedProduct | null>(null)
const removing = ref(false)
const removeError = ref<string | null>(null)
const removeDialog = ref<HTMLDialogElement | null>(null)
let itemsRequest = 0
let folderRequest = 0
let countRequest = 0

const projectOptions = computed(() => [...workspace.projects].sort((a, b) => {
  if (a.projectId === workspace.currentProjectId) return -1
  if (b.projectId === workspace.currentProjectId) return 1
  return 0
}))
const selectedFolder = computed(() => folders.value.find(folder => folder.folderId === selectedFolderId.value) || null)
const selectedProject = computed(() => workspace.projects.find(project => project.projectId === selectedProjectId.value) || null)
const filteredItems = computed(() => {
  const query = search.value.trim().toLowerCase()
  if (!query) return items.value
  return items.value.filter(product => [product.productName, product.rspuCode, product.externalCode]
    .some(value => value?.toLowerCase().includes(query)))
})
const listTitle = computed(() => section.value === 'personal'
  ? selectedFolder.value?.name || '全部收藏'
  : selectedProject.value?.projectName || '项目收藏')
const emptyText = computed(() => section.value === 'project'
  ? '该项目还没有收藏的商品'
  : selectedFolderId.value ? '该收藏夹还没有商品' : '还没有收藏的商品')
const centerRoute = computed(() => ({ path: '/favorites', query: section.value === 'project' ? { tab: 'project' } : {} }))

async function loadFolders() {
  const request = ++folderRequest
  try {
    const result = await api.loadFavoriteFolders()
    if (request === folderRequest) folders.value = result
  } catch {
    if (request === folderRequest) folders.value = []
  }
}

async function loadAllFavoriteCount() {
  const request = ++countRequest
  try {
    const result = await api.loadFavoriteProductIds()
    if (request === countRequest) allFavoriteCount.value = result.length
  } catch {
    if (request === countRequest) allFavoriteCount.value = 0
  }
}

async function loadItems() {
  const request = ++itemsRequest
  loading.value = true
  error.value = null
  try {
    const result = section.value === 'personal'
      ? await api.loadFavoriteItems(selectedFolderId.value || undefined)
      : selectedProjectId.value ? await api.loadProjectFavoriteItems(selectedProjectId.value) : []
    if (request === itemsRequest) items.value = result
  } catch (failure) {
    if (request === itemsRequest) error.value = failure instanceof Error ? failure.message : '收藏商品加载失败'
  } finally {
    if (request === itemsRequest) loading.value = false
  }
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
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '新建收藏夹失败'
  } finally {
    creating.value = false
  }
}

function openFavorite(product: ImportedProduct) {
  favoriteDialog.value?.open(product, section.value, section.value === 'project' ? selectedProjectId.value : undefined)
}

function handlePersonalSaved() {
  void loadFolders()
  void loadAllFavoriteCount()
  void loadItems()
}

function handleProjectSaved(payload: { projectId: string }) {
  if (payload.projectId === selectedProjectId.value) void loadItems()
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
    if (section.value === 'project') {
      await api.saveProjectFavorite(selectedProjectId.value, product.rspuId, false)
      removeDialog.value?.close()
      removingProduct.value = null
      await loadItems()
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
  } catch (failure) {
    removeError.value = failure instanceof Error ? failure.message : '取消收藏失败'
  } finally {
    removing.value = false
  }
}

watch(() => workspace.currentProjectId, projectId => {
  if (projectId) selectedProjectId.value = projectId
})
watch([section, selectedFolderId, selectedProjectId], () => {
  search.value = ''
  void loadItems()
}, { immediate: true })
onMounted(() => {
  void loadFolders()
  void loadAllFavoriteCount()
})
</script>

<template>
  <section class="scheme-favorites" data-testid="scheme-favorites-panel" aria-label="收藏选品">
    <div class="scheme-favorite-tabs" role="tablist" aria-label="收藏类型">
      <button id="scheme-project-favorite-tab" type="button" role="tab" aria-controls="scheme-project-favorite-panel" :aria-selected="section === 'project'" @click="section = 'project'">项目收藏夹</button>
      <button id="scheme-personal-favorite-tab" type="button" role="tab" aria-controls="scheme-personal-favorite-panel" :aria-selected="section === 'personal'" @click="section = 'personal'">个人收藏夹</button>
    </div>

    <div class="scheme-favorite-heading">
      <h3>{{ section === 'project' ? '项目收藏夹' : '个人收藏夹' }}</h3>
      <NuxtLink :to="centerRoute">打开收藏中心</NuxtLink>
    </div>

    <div v-if="section === 'project'" id="scheme-project-favorite-panel" role="tabpanel" aria-labelledby="scheme-project-favorite-tab" class="scheme-favorite-filters">
      <select v-model="selectedProjectId" aria-label="选择项目">
        <option v-for="project in projectOptions" :key="project.projectId" :value="project.projectId">{{ project.projectName }}{{ project.projectId === workspace.currentProjectId ? '（当前项目）' : '' }}</option>
      </select>
      <p>项目收藏仅当前账号可见，不与项目成员共享。</p>
    </div>

    <div v-else id="scheme-personal-favorite-panel" role="tabpanel" aria-labelledby="scheme-personal-favorite-tab" class="scheme-favorite-filters">
      <div class="scheme-folder-list" aria-label="收藏夹">
        <button type="button" :aria-pressed="selectedFolderId === ''" @click="selectedFolderId = ''"><span>全部收藏</span><small>{{ allFavoriteCount }}</small></button>
        <button v-for="folder in folders" :key="folder.folderId" type="button" :aria-pressed="selectedFolderId === folder.folderId" @click="selectedFolderId = folder.folderId">
          <span>{{ folder.name }}</span><small>{{ folder.itemCount }}</small>
        </button>
      </div>
      <button v-if="!showCreate" type="button" class="scheme-create-folder" @click="showCreate = true">＋ 新建收藏夹</button>
      <div v-else class="scheme-create-form">
        <input v-model="newFolderName" maxlength="50" aria-label="新建收藏夹名称" placeholder="输入收藏夹名称" :disabled="creating" @keyup.enter="createFolder">
        <button type="button" :disabled="creating" @click="createFolder">{{ creating ? '创建中…' : '创建收藏夹' }}</button>
      </div>
    </div>

    <div class="scheme-favorite-list-heading">
      <strong>{{ listTitle }} · {{ items.length }} 件</strong>
      <input v-model="search" type="search" aria-label="搜索收藏的商品" placeholder="搜索收藏的商品">
    </div>
    <FavoritesProductGrid :items="filteredItems" :loading="loading" :error="error" :total="items.length" :searching="!!search.trim()" :empty-text="emptyText" @open="openFavorite" @remove="askRemove" @retry="loadItems" />

    <FavoriteFolderDialog ref="favoriteDialog" @saved="handlePersonalSaved" @project-saved="handleProjectSaved" />
    <dialog ref="removeDialog" class="scheme-remove-dialog" aria-label="取消收藏" @cancel="event => { if (removing) event.preventDefault() }" @close="removingProduct = null">
      <div class="scheme-remove-content">
        <h3>取消收藏</h3>
        <p v-if="section === 'project'">确定将「{{ removingProduct?.productName || '商品' }}」从项目「{{ selectedProject?.projectName }}」取消收藏吗？</p>
        <p v-else-if="selectedFolderId">确定将「{{ removingProduct?.productName || '商品' }}」从「{{ selectedFolder?.name }}」取消收藏吗？</p>
        <p v-else>确定取消收藏「{{ removingProduct?.productName || '商品' }}」吗？将从你的所有个人收藏夹中移除。</p>
        <p v-if="removeError" role="alert" class="scheme-remove-error">{{ removeError }}</p>
        <div class="scheme-remove-actions">
          <button type="button" :disabled="removing" @click="cancelRemove">取消</button>
          <button type="button" :disabled="removing" @click="confirmRemove">{{ removing ? '处理中…' : '确认取消收藏' }}</button>
        </div>
      </div>
    </dialog>
  </section>
</template>

<style scoped>
.scheme-favorites {
  display: flex;
  min-height: 0;
  height: 100%;
  flex-direction: column;
  gap: .55rem;
  overflow-y: auto;
  padding: .6rem;
  background: white;
}
.scheme-favorite-tabs {
  display: grid;
  flex: none;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: .3rem;
}
.scheme-favorite-tabs button,
.scheme-favorite-filters select,
.scheme-create-folder,
.scheme-create-form input,
.scheme-create-form button,
.scheme-favorite-list-heading input {
  min-width: 0;
  border: 1px solid var(--color-accent-line);
  background: white;
  color: var(--color-accent-ink);
  padding: .45rem .5rem;
  font-size: .75rem;
}
.scheme-favorite-tabs button[aria-selected=true],
.scheme-folder-list button[aria-pressed=true] {
  border-color: var(--color-accent);
  background: var(--color-accent);
  color: white;
}
.scheme-favorite-heading,
.scheme-favorite-list-heading {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: .5rem;
}
.scheme-favorite-heading h3 {
  font-size: .85rem;
  font-weight: 700;
}
.scheme-favorite-heading a {
  color: var(--color-accent-ink);
  font-size: .68rem;
}
.scheme-favorite-filters {
  display: grid;
  flex: none;
  gap: .4rem;
}
.scheme-favorite-filters select {
  width: 100%;
}
.scheme-favorite-filters p {
  color: var(--color-muted);
  font-size: .65rem;
  line-height: 1.5;
}
.scheme-folder-list {
  display: flex;
  flex-wrap: wrap;
  gap: .3rem;
}
.scheme-folder-list button {
  display: flex;
  align-items: center;
  gap: .4rem;
  border: 1px solid var(--color-accent-line);
  background: white;
  color: var(--color-accent-ink);
  padding: .35rem .45rem;
  font-size: .68rem;
}
.scheme-folder-list small {
  font-size: .62rem;
}
.scheme-create-folder {
  width: 100%;
}
.scheme-create-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: .3rem;
}
.scheme-favorite-list-heading strong {
  flex: none;
  font-size: .75rem;
}
.scheme-favorite-list-heading input {
  width: 150px;
  padding: .35rem .45rem;
  font-size: .68rem;
}
.scheme-favorites :deep(ul[aria-label="收藏商品列表"]) {
  grid-template-columns: minmax(0, 1fr);
  gap: .55rem;
}
.scheme-favorites :deep(ul[aria-label="收藏商品列表"] > li) {
  min-width: 0;
}
.scheme-remove-dialog {
  margin: auto;
  width: min(420px, calc(100% - 32px));
  border: 1px solid var(--color-line);
  background: white;
  padding: 0;
}
.scheme-remove-dialog::backdrop {
  background: rgb(0 0 0 / 30%);
}
.scheme-remove-content {
  display: grid;
  gap: 1rem;
  padding: 1.25rem;
  font-size: .8rem;
}
.scheme-remove-content h3 {
  font-size: 1rem;
  font-weight: 700;
}
.scheme-remove-error {
  color: #992d39;
}
.scheme-remove-actions {
  display: flex;
  justify-content: flex-end;
  gap: .5rem;
  border-top: 1px solid var(--color-line);
  padding-top: .8rem;
}
.scheme-remove-actions button {
  border: 1px solid var(--color-line);
  background: white;
  padding: .45rem .8rem;
}
.scheme-remove-actions button:last-child {
  border-color: #b3402f;
  background: #b3402f;
  color: white;
}
.scheme-remove-actions button:disabled {
  opacity: .5;
}
</style>
