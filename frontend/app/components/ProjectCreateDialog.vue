<script setup lang="ts">
import { parseFloorNames } from '~/utils/workspaceFloors'

const emit = defineEmits<{ close: []; created: [] }>()
const workspace = useWorkspaceStore()
const projectName = ref('')
const customerRequirements = ref('')
const floorNames = ref(['1F'])
const floorInput = ref('')
const submitting = ref(false)
const error = ref('')

function addFloors() {
  error.value = ''
  try {
    const parsed = parseFloorNames(floorInput.value)
    if (!parsed.length) return
    floorNames.value = [...new Set([...floorNames.value, ...parsed])]
    floorInput.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '楼层格式不正确'
  }
}

function removeFloor(index: number) {
  if (floorNames.value.length > 1) floorNames.value.splice(index, 1)
}

async function submit() {
  error.value = ''
  const name = projectName.value.trim()
  const floors = floorNames.value.map(floor => floor.trim()).filter(Boolean)
  if (!name) { error.value = '请输入项目名称'; return }
  if (!floors.length) { error.value = '请至少保留一个楼层'; return }
  if (new Set(floors).size !== floors.length) { error.value = '楼层名称不能重复'; return }
  submitting.value = true
  try {
    await workspace.createProject({ projectName: name, customerRequirements: customerRequirements.value.trim(), floors: floors.map((floorName, index) => ({ floorName, sortOrder: index + 1 })) })
    emit('created')
    emit('close')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '项目创建失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50 grid place-items-center bg-black/30 p-4" @click.self="emit('close')">
      <form class="w-full max-w-2xl overflow-hidden rounded-2xl bg-white shadow-2xl" @submit.prevent="submit">
        <header class="flex items-start justify-between border-b border-line px-6 py-5">
          <div><h2 class="text-xl font-bold">创建办公项目</h2><p class="mt-1 text-xs text-muted">先规划项目与楼层，进入楼层后再分别上传设计图。</p></div>
          <button type="button" class="rounded-lg p-2 text-muted hover:bg-cream" aria-label="关闭创建项目" @click="emit('close')"><AppIcon name="close" /></button>
        </header>
        <div class="grid max-h-[70vh] gap-5 overflow-y-auto px-6 py-5">
          <label class="grid gap-2 text-sm font-semibold">项目名称 <span class="text-xs font-normal text-muted">必填</span>
            <input v-model="projectName" maxlength="100" autofocus class="rounded-xl border border-line px-4 py-3 text-sm font-normal outline-none focus:border-bronze" placeholder="例如：深圳 XX 集团总部办公">
          </label>
          <label class="grid gap-2 text-sm font-semibold">客户需求 <span class="text-xs font-normal text-muted">全流程备注，可在过程中修改</span>
            <textarea v-model="customerRequirements" maxlength="4000" rows="4" class="resize-none rounded-xl border border-line px-4 py-3 text-sm font-normal leading-6 outline-none focus:border-bronze" placeholder="记录客户偏好、工位数量、预算或其他需要持续提醒的事项" />
          </label>
          <section class="grid gap-3">
            <div><h3 class="text-sm font-semibold">楼层规划</h3><p class="mt-1 text-xs text-muted">支持 1F、B1F 等名称；连续楼层可输入 7~9F，多个楼层用逗号分隔。</p></div>
            <div class="flex gap-2">
              <input v-model="floorInput" class="min-w-0 flex-1 rounded-xl border border-line px-4 py-2.5 text-sm outline-none focus:border-bronze" placeholder="例如：1F, 2F, 7~9F, 11F" @keydown.enter.prevent="addFloors">
              <button type="button" class="rounded-xl border border-line px-4 text-sm font-semibold hover:bg-cream" @click="addFloors">添加楼层</button>
            </div>
            <div class="flex flex-wrap gap-2 rounded-xl bg-bg p-3">
              <span v-for="(floor, index) in floorNames" :key="floor" class="inline-flex items-center gap-1 rounded-lg border border-line bg-white py-1.5 pl-3 pr-1.5 text-xs font-semibold">
                {{ floor }}<button type="button" class="rounded p-1 text-muted hover:bg-cream hover:text-ink" :disabled="floorNames.length === 1" :aria-label="`移除楼层 ${floor}`" @click="removeFloor(index)"><AppIcon name="close" size="3" /></button>
              </span>
            </div>
          </section>
          <p v-if="error" role="alert" class="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{{ error }}</p>
        </div>
        <footer class="flex justify-end gap-3 border-t border-line bg-bg px-6 py-4">
          <button type="button" class="rounded-xl border border-line bg-white px-5 py-2.5 text-sm font-semibold" @click="emit('close')">取消</button>
          <button type="submit" class="rounded-xl bg-bronze px-5 py-2.5 text-sm font-semibold text-white hover:bg-bronze-dark disabled:opacity-50" :disabled="submitting">{{ submitting ? '创建中…' : '创建项目' }}</button>
        </footer>
      </form>
    </div>
  </Teleport>
</template>
