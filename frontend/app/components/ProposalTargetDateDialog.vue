<script setup lang="ts">
const props = defineProps<{ date: string }>()
const emit = defineEmits<{ save: [date: string]; close: [] }>()
const value = ref(props.date)

function save() {
  emit('save', value.value)
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50 grid place-items-center bg-black/30 p-4" @click.self="emit('close')">
      <form class="w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl" @submit.prevent="save">
        <div class="mb-5 flex items-start justify-between">
          <div>
            <h2 class="text-lg font-bold">汇报提案日期</h2>
            <p class="mt-1 text-xs text-neutral-600">用于项目总览页的「距离汇报提案」倒计时</p>
          </div>
          <button type="button" class="rounded-lg p-2 text-neutral-600 hover:bg-cream" aria-label="关闭日期设置" @click="emit('close')"><AppIcon name="close" /></button>
        </div>
        <label class="block text-sm font-medium">
          目标日期
          <input v-model="value" type="date" class="mt-2 w-full rounded-xl border border-line px-4 py-2.5 text-sm outline-none focus:border-bronze">
        </label>
        <p class="mt-3 rounded-xl bg-cream px-4 py-3 text-xs leading-5 text-bronze-dark">日期仅保存在本机浏览器，不会同步到项目数据或其他设备。</p>
        <div class="mt-5 flex justify-end gap-3">
          <button v-if="value" type="button" class="rounded-xl border border-line px-4 py-2 text-sm" @click="value = ''">清除</button>
          <button type="button" class="rounded-xl border border-line px-4 py-2 text-sm" @click="emit('close')">取消</button>
          <button type="submit" class="rounded-xl bg-bronze px-4 py-2 text-sm font-semibold text-white">{{ value ? '保存' : '不设置' }}</button>
        </div>
      </form>
    </div>
  </Teleport>
</template>
