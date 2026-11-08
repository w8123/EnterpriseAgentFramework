<template>
  <AppDialog
    :model-value="modelValue"
    title="环境变量"
    width="560px"
    @update:model-value="emit('update:modelValue', $event)"
    @open="reset"
  >
    <p class="env-hint">
      工作流级常量，节点里用 <code>env.名称</code> 或 <code v-pre>{{ env.名称 }}</code> 引用，例如默认仓库、审批金额上限。
      密码、密钥不要放这里，请用「Workflow 凭据」。
    </p>
    <div v-for="(row, index) in rows" :key="index" class="env-row">
      <el-input v-model="row.name" placeholder="名称，如 warehouse" :disabled="readOnly" />
      <el-input v-model="row.value" placeholder="值" :disabled="readOnly" />
      <el-button v-if="!readOnly" text type="danger" @click="rows.splice(index, 1)">删除</el-button>
    </div>
    <div v-if="!rows.length" class="env-empty">还没有环境变量。</div>
    <div v-if="error" class="env-error">{{ error }}</div>
    <template #footer>
      <el-button v-if="!readOnly" @click="rows.push({ name: '', value: '' })">添加</el-button>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button v-if="!readOnly" type="primary" @click="apply">确定</el-button>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import { ref } from 'vue'

const props = defineProps<{ modelValue: boolean; env: Record<string, string>; readOnly?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; apply: [env: Record<string, string>] }>()

const NAME = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/
const SECRET_HINT = /(password|passwd|secret|token|api[_-]?key|密码|密钥)/i

const rows = ref<Array<{ name: string; value: string }>>([])
const error = ref('')

function reset() {
  rows.value = Object.entries(props.env || {}).map(([name, value]) => ({ name, value: String(value ?? '') }))
  error.value = ''
}

function apply() {
  const next: Record<string, string> = {}
  for (const row of rows.value) {
    const name = row.name.trim()
    if (!name && !row.value.trim()) continue
    if (!NAME.test(name)) {
      error.value = `名称「${name || '（空）'}」只能用字母、数字和下划线，且不能以数字开头`
      return
    }
    if (SECRET_HINT.test(name)) {
      error.value = `「${name}」看起来是密钥，请改用 Workflow 凭据`
      return
    }
    if (name in next) {
      error.value = `名称「${name}」重复了`
      return
    }
    next[name] = row.value
  }
  emit('apply', next)
  emit('update:modelValue', false)
}
</script>

<style scoped>
.env-hint { margin: 0 0 12px; color: var(--text-muted); font-size: 13px; line-height: 1.6; }
.env-row { display: grid; grid-template-columns: 1fr 1.4fr auto; gap: 8px; margin-bottom: 8px; }
.env-empty { color: var(--text-muted); font-size: 13px; padding: 8px 0; }
.env-error { color: var(--el-color-danger); font-size: 13px; margin-top: 8px; }
</style>
