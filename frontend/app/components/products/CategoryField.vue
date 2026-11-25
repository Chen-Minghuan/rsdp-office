<script setup lang="ts">
const props = defineProps<{
  categories: { dictCode: string, dictName: string }[]
  label?: string
  required?: boolean
}>()
const code = defineModel<string>({ required: true })
const emit = defineEmits<{ change: [value: string] }>()
const id = useId()
const missing = computed(() => code.value && !props.categories.some(item => item.dictCode === code.value))
const control = 'block w-full min-w-0 border border-line bg-white px-3 py-2 text-sm text-ink'
</script>

<template>
  <div class="min-w-0 space-y-1.5">
    <label :for="id" class="block text-sm font-medium text-ink">{{ label ?? '商品品类' }}</label>
    <select v-if="categories.length" :id="id" v-model="code" :class="control" :required="required" @change="emit('change', ($event.target as HTMLSelectElement).value)">
      <option value="">选择品类</option>
      <option v-if="missing" :value="code">{{ code }}</option>
      <option v-for="category in categories" :key="category.dictCode" :value="category.dictCode">
        {{ category.dictName }} · {{ category.dictCode }}
      </option>
    </select>
    <input v-else :id="id" v-model.trim="code" :class="control" :required="required" maxlength="16"
      placeholder="输入品类编码" @input="emit('change', ($event.target as HTMLInputElement).value.trim())">
  </div>
</template>
