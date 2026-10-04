<template>
  <AppDialog :model-value="modelValue" :title="projectOnly ? '新建项目凭据' : '新建工作流凭据'"
    description="秘密仅在本次保存时提交，不会在页面回显。" width="560px"
    @update:model-value="emit('update:modelValue', $event)">
    <el-form novalidate label-width="96px" @submit.prevent="submit">
      <el-form-item label="名称" :error="errors.name">
        <el-input ref="nameInput" v-model="form.name" maxlength="100" placeholder="例如：订单系统测试密钥" />
      </el-form-item>
      <el-form-item label="类型" :error="errors.type">
        <el-select v-model="form.type" style="width: 100%" aria-label="凭据类型">
          <el-option v-if="permits('BEARER')" label="Bearer 令牌" value="BEARER" />
          <el-option v-if="permits('API_KEY_HEADER')" label="请求头 API Key" value="API_KEY_HEADER" />
          <el-option v-if="permits('BASIC')" label="基础认证" value="BASIC" />
          <el-option v-if="permits('API_KEY_QUERY')" label="查询参数 API Key" value="API_KEY_QUERY" />
          <el-option v-if="permits('CUSTOM_HEADERS')" label="自定义请求头" value="CUSTOM_HEADERS" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="!projectOnly" label="范围">
        <el-segmented v-model="form.scope" :options="scopeOptions" />
      </el-form-item>
      <el-form-item v-if="form.type === 'API_KEY_HEADER'" label="请求头" :error="errors.headerName">
        <el-input v-model="secret.headerName" placeholder="X-API-Key" />
      </el-form-item>
      <el-form-item v-if="form.type === 'BEARER'" label="令牌" :error="errors.secret">
        <el-input v-model="secret.token" type="password" show-password autocomplete="new-password" />
      </el-form-item>
      <el-form-item v-else-if="form.type === 'API_KEY_HEADER'" label="API 密钥" :error="errors.secret">
        <el-input v-model="secret.apiKey" type="password" show-password autocomplete="new-password" />
      </el-form-item>
      <template v-else-if="form.type === 'BASIC'">
        <el-form-item label="用户名" :error="errors.username"><el-input v-model="secret.username" /></el-form-item>
        <el-form-item label="密码" :error="errors.secret">
          <el-input v-model="secret.password" type="password" show-password autocomplete="new-password" />
        </el-form-item>
      </template>
      <template v-else-if="form.type === 'API_KEY_QUERY'">
        <el-form-item label="参数名" :error="errors.headerName"><el-input v-model="secret.paramName" /></el-form-item>
        <el-form-item label="API 密钥" :error="errors.secret">
          <el-input v-model="secret.apiKey" type="password" show-password autocomplete="new-password" />
        </el-form-item>
      </template>
      <el-form-item v-else-if="form.type === 'CUSTOM_HEADERS'" label="请求头" :error="errors.secret">
        <el-input v-model="customHeaders" type="textarea" :rows="5" resize="none" placeholder="X-App-Key = abc" />
      </el-form-item>
      <p v-if="saveError" class="credential-error" role="alert">{{ saveError }}</p>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存并选中</el-button>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AppDialog from '@/components/common/AppDialog.vue'
import { createWorkflowCredential } from '@/api/workflowCredential'
import type { WorkflowCredential, WorkflowCredentialType } from '@/types/workflowCredential'
import { parseMap } from '@/views/workflow/studio-panels/panelUtils'

const props = withDefaults(defineProps<{
  modelValue: boolean
  projectId?: number | null
  projectCode?: string | null
  projectOnly?: boolean
  allowedTypes?: WorkflowCredentialType[]
  initialType?: WorkflowCredentialType
  suggestedHeader?: string
}>(), { projectOnly: false })
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; created: [credential: WorkflowCredential] }>()
const scopeOptions = [{ label: '项目', value: 'PROJECT' }, { label: '全局', value: 'GLOBAL' }]
const form = reactive({ name: '', type: 'BEARER' as WorkflowCredentialType, scope: 'PROJECT' as 'PROJECT' | 'GLOBAL' })
const secret = reactive({ token: '', username: '', password: '', headerName: 'X-API-Key',
  paramName: 'api_key', apiKey: '' })
const customHeaders = ref('')
const saving = ref(false)
const saveError = ref('')
const errors = reactive({ name: '', type: '', headerName: '', username: '', secret: '' })
const nameInput = ref<{ focus: () => void } | null>(null)

function permits(type: WorkflowCredentialType) { return !props.allowedTypes || props.allowedTypes.includes(type) }
function reset() {
  form.name = ''
  form.type = props.initialType && permits(props.initialType) ? props.initialType : props.allowedTypes?.[0] || 'BEARER'
  form.scope = 'PROJECT'
  Object.assign(secret, { token: '', username: '', password: '', headerName: props.suggestedHeader || 'X-API-Key',
    paramName: 'api_key', apiKey: '' })
  customHeaders.value = ''
  saveError.value = ''
  Object.keys(errors).forEach((key) => { errors[key as keyof typeof errors] = '' })
}
watch(() => [props.modelValue, props.projectId, props.projectCode], () => { if (props.modelValue) reset() })

function validate() {
  Object.keys(errors).forEach((key) => { errors[key as keyof typeof errors] = '' })
  if (!form.name.trim()) errors.name = '请填写凭据名称'
  if (!permits(form.type)) errors.type = '当前 API 不支持此凭据类型'
  if ((props.projectOnly || form.scope === 'PROJECT') && (!props.projectId || !props.projectCode)) {
    saveError.value = '请先确认当前项目，再创建项目凭据'
  }
  if (form.type === 'BEARER' && !secret.token.trim()) errors.secret = '请填写令牌'
  if (form.type === 'API_KEY_HEADER') {
    if (!/^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}$/.test(secret.headerName)) errors.headerName = '请求头名称无效'
    if (!secret.apiKey.trim()) errors.secret = '请填写 API 密钥'
  }
  if (form.type === 'BASIC') {
    if (!secret.username.trim()) errors.username = '请填写用户名'
    if (!secret.password) errors.secret = '请填写密码'
  }
  if (form.type === 'API_KEY_QUERY') {
    if (!secret.paramName.trim()) errors.headerName = '请填写参数名'
    if (!secret.apiKey.trim()) errors.secret = '请填写 API 密钥'
  }
  if (form.type === 'CUSTOM_HEADERS' && !customHeaders.value.trim()) errors.secret = '请填写请求头'
  if (errors.name) nameInput.value?.focus()
  return !Object.values(errors).some(Boolean) && !saveError.value
}
function buildSecret() {
  if (form.type === 'BEARER') return { token: secret.token }
  if (form.type === 'API_KEY_HEADER') return { headerName: secret.headerName, apiKey: secret.apiKey }
  if (form.type === 'BASIC') return { username: secret.username, password: secret.password }
  if (form.type === 'API_KEY_QUERY') return { paramName: secret.paramName, apiKey: secret.apiKey }
  return { headers: parseMap(customHeaders.value) }
}
async function submit() {
  if (saving.value) return
  saveError.value = ''
  if (!validate()) return
  saving.value = true
  try {
    const { data } = await createWorkflowCredential({
      name: form.name.trim(), type: form.type, scope: props.projectOnly ? 'PROJECT' : form.scope,
      projectId: props.projectId ?? null, projectCode: props.projectCode ?? null,
      status: 'ACTIVE', secret: buildSecret(),
    })
    emit('created', data)
    emit('update:modelValue', false)
    reset()
    ElMessage.success('凭据已保存')
  } catch {
    saveError.value = '凭据保存失败；请检查项目权限和填写内容后重试'
  } finally { saving.value = false }
}
</script>

<style scoped lang="scss">
.credential-error { color: var(--el-color-danger); margin: 0 0 8px 96px; font-size: 13px; }
</style>
