<script setup lang="ts">
import type { WorkbookField, WorkbookFieldColumns, WorkbookMappingSelection, WorkbookMappingSheet } from '~/types/local-products'

const props = defineProps<{ sheets: WorkbookMappingSheet[], busy: boolean }>()
const emit = defineEmits<{ apply: [mapping: WorkbookMappingSelection], busyChange: [busy: boolean] }>()
const api = useOfficeApi()

const sheetIndex = ref(props.sheets[0]!.index)
const sheet = computed(() => props.sheets.find(item => item.index === sheetIndex.value)!)
const headerRow = ref(sheet.value.headers[0]!.row)
const header = computed(() => sheet.value.headers.find(row => row.row === headerRow.value))
const columns = ref<WorkbookFieldColumns>({})
const message = ref('')
const error = ref('')
const fields: { key: WorkbookField, label: string }[] = [
  { key: 'MODEL', label: '厂内型号（必填）' },
  { key: 'NAME', label: '商品名称（必填）' },
  { key: 'SIZE', label: '组合尺寸' },
  { key: 'WIDTH_MM', label: '宽（mm）' },
  { key: 'DEPTH_MM', label: '深（mm）' },
  { key: 'HEIGHT_MM', label: '高（mm）' },
  { key: 'PICTURE', label: '商品图片' },
  { key: 'COLOR', label: '颜色' },
  { key: 'MATERIAL', label: '材质' },
  { key: 'DESCRIPTION', label: '说明／备注' },
  { key: 'CATEGORY', label: '原表品类' },
]
const control = 'w-full rounded-md border border-line bg-white px-2 py-1.5 text-sm'
const button = 'rounded-md border border-line bg-white px-3 py-2 text-sm font-semibold disabled:opacity-50'
const duplicate = computed(() => {
  const selected = Object.values(columns.value).filter(value => value !== undefined)
  return new Set(selected).size !== selected.length
})
const labelled = computed(() => (header.value?.columns ?? []).filter(column => column.label))

function resetFields() {
  columns.value = {}
  message.value = ''
  error.value = ''
}

function changeSheet() {
  headerRow.value = sheet.value.headers[0]!.row
  resetFields()
}

function columnName(column: number) {
  let label = ''
  for (let value = column + 1; value > 0; value = Math.floor((value - 1) / 26)) {
    label = String.fromCharCode(65 + (value - 1) % 26) + label
  }
  return label
}

async function suggest() {
  if (props.busy || labelled.value.length < 2) return
  message.value = ''
  error.value = ''
  emit('busyChange', true)
  try {
    const suggestions = await api.suggestWorkbookFields(Object.fromEntries(labelled.value.map(column => [column.index, column.label])))
    const occupied = new Set(Object.values(columns.value))
    let added = 0
    for (const { key } of fields) {
      const suggestion = suggestions[key]
      if (columns.value[key] !== undefined || suggestion === undefined || occupied.has(suggestion)) continue
      columns.value[key] = suggestion
      occupied.add(suggestion)
      added++
    }
    message.value = added ? '已填入 ' + added + ' 项建议，请核对对应列。' : '没有可补充的字段建议，请手工选择。'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法获取字段建议，请手工选择'
  } finally {
    emit('busyChange', false)
  }
}
</script>

<template>
  <fieldset :disabled="busy" class="space-y-4 rounded-lg border border-line p-4">
    <legend class="px-2 text-sm font-semibold">确认表头和字段</legend>
    <p class="text-sm text-muted">选择商品表头所在行，再将 Excel 列对应到商品字段。可请 Qwen 根据列名给出建议。</p>
    <div class="grid gap-3 sm:grid-cols-2">
      <label class="space-y-1 text-sm"><span>工作表</span>
        <select v-model="sheetIndex" :class="control" @change="changeSheet">
          <option v-for="item in sheets" :key="item.index" :value="item.index">{{ item.name }}</option>
        </select>
      </label>
      <label class="space-y-1 text-sm"><span>表头行</span>
        <select v-model="headerRow" :class="control" @change="resetFields">
          <option v-for="row in sheet.headers" :key="row.row" :value="row.row">
            第 {{ row.row }} 行 · {{ row.columns.filter(column => column.label).slice(0, 5).map(column => column.label).join(' / ') }}
          </option>
        </select>
      </label>
    </div>
    <button type="button" :class="button" :disabled="labelled.length < 2" @click="suggest">Qwen 识别字段</button>
    <p v-if="message" role="status" class="text-sm text-muted">{{ message }}</p>
    <p v-if="error" role="alert" class="text-sm text-red-700">{{ error }}</p>
    <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      <label v-for="field in fields" :key="field.key" class="space-y-1 text-sm"><span>{{ field.label }}</span>
        <select v-model="columns[field.key]" :class="control">
          <option :value="undefined">未选择</option>
          <option v-for="column in header?.columns" :key="column.index" :value="column.index">
            {{ columnName(column.index) }} 列 · {{ column.label || '空白表头' }}
          </option>
        </select>
      </label>
    </div>
    <p v-if="duplicate" class="text-sm text-red-700">同一列只能对应一个商品字段。</p>
    <button type="button" :class="button" :disabled="columns.MODEL === undefined || columns.NAME === undefined || duplicate"
      @click="emit('apply', { sheetIndex, headerRow, columns: { ...columns } })">生成商品预览</button>
  </fieldset>
</template>
