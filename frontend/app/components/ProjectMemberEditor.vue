<script setup lang="ts">
import type { AssignableUser } from '~/types/workspace'

const props = withDefaults(defineProps<{
  leader: AssignableUser | null
  collaborators: AssignableUser[]
  disabled?: boolean
  /** 开发模式（未开启认证）允许不指定负责人。 */
  leaderOptional?: boolean
}>(), { disabled: false, leaderOptional: false })

const emit = defineEmits<{
  'update:leader': [AssignableUser | null]
  'update:collaborators': [AssignableUser[]]
}>()

const api = useWorkspaceApi()
const candidates = ref<AssignableUser[]>([])
const keyword = ref('')
const searching = ref(false)
const loadError = ref('')

const leaderOptions = computed(() => {
  const options = [...candidates.value]
  if (props.leader && !options.some(user => user.userId === props.leader!.userId)) options.unshift(props.leader)
  return options
})

const collaboratorOptions = computed(() => candidates.value.filter(user =>
  user.userId !== props.leader?.userId && !props.collaborators.some(item => item.userId === user.userId)))

async function search() {
  searching.value = true
  loadError.value = ''
  try {
    candidates.value = await api.listAssignableUsers(keyword.value.trim() || undefined)
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : '用户查询失败'
  } finally {
    searching.value = false
  }
}

let timer: ReturnType<typeof setTimeout> | undefined
watch(keyword, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(search, 300)
})

function selectLeader(event: Event) {
  const userId = (event.target as HTMLSelectElement).value
  const user = leaderOptions.value.find(item => item.userId === userId) || null
  emit('update:leader', user)
  if (user) emit('update:collaborators', props.collaborators.filter(item => item.userId !== user.userId))
}

function addCollaborator(user: AssignableUser) {
  if (props.disabled) return
  emit('update:collaborators', [...props.collaborators, user])
}

function removeCollaborator(userId: string) {
  if (props.disabled) return
  emit('update:collaborators', props.collaborators.filter(item => item.userId !== userId))
}

function userLabel(user: AssignableUser) {
  return user.displayName && user.displayName !== user.username ? `${user.displayName}（${user.username}）` : user.username
}

onMounted(search)
</script>

<template>
  <section class="grid gap-3" data-testid="project-member-editor">
    <div>
      <h3 class="text-sm font-semibold">项目成员</h3>
      <p class="mt-1 text-xs text-muted">每个项目恰好一位负责人；协作者可查看和编辑项目，仅负责人可管理成员。</p>
    </div>
    <label class="grid gap-2 text-sm font-semibold">项目负责人
      <select :value="leader?.userId || ''" :disabled="disabled" data-testid="leader-select"
        class="rounded-xl border border-line bg-white px-4 py-2.5 text-sm font-normal outline-none focus:border-bronze disabled:opacity-60"
        @change="selectLeader">
        <option v-if="leaderOptional" value="">（暂不指定）</option>
        <option v-for="user in leaderOptions" :key="user.userId" :value="user.userId">{{ userLabel(user) }}</option>
      </select>
    </label>
    <div class="grid gap-2">
      <span class="text-sm font-semibold">协作者</span>
      <div v-if="collaborators.length" class="flex flex-wrap gap-2">
        <span v-for="user in collaborators" :key="user.userId" data-testid="collaborator-tag"
          class="inline-flex items-center gap-1 rounded-lg border border-line bg-white py-1.5 pl-3 pr-1.5 text-xs font-semibold">
          {{ userLabel(user) }}
          <button type="button" class="rounded p-1 text-muted hover:bg-cream hover:text-ink" :disabled="disabled"
            :aria-label="`移除协作者 ${userLabel(user)}`" @click="removeCollaborator(user.userId)"><AppIcon name="close" size="3" /></button>
        </span>
      </div>
      <template v-if="!disabled">
        <input v-model="keyword" class="rounded-xl border border-line px-4 py-2.5 text-sm outline-none focus:border-bronze"
          placeholder="搜索用户名或显示名，添加协作者" data-testid="collaborator-search">
        <div v-if="collaboratorOptions.length" class="grid gap-1 rounded-xl border border-line bg-bg p-2">
          <button v-for="user in collaboratorOptions" :key="user.userId" type="button" data-testid="collaborator-option"
            class="flex items-center justify-between rounded-lg px-3 py-2 text-left text-sm hover:bg-white"
            @click="addCollaborator(user)">
            <span>{{ userLabel(user) }}</span><AppIcon name="plus" size="4" class="text-muted" />
          </button>
        </div>
        <p v-else-if="!searching" class="text-xs text-muted">暂无可添加的用户</p>
      </template>
      <p v-if="loadError" role="alert" class="text-xs text-red-700">{{ loadError }}</p>
    </div>
  </section>
</template>
