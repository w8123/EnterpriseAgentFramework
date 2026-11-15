<template>
  <AppDialog :model-value="modelValue" :title="editing ? '更新凭据' : projectOnly ? '新建项目凭据' : '新建工作流凭据'"
    :description="editing ? '重新填写密钥后保存。为了安全，用到这个凭据的已发布流程要重新发布一次才会用新的；令牌经常过期的系统建议改成「登录换令牌」，以后就不用再改。' : '秘密仅在本次保存时提交，不会在页面回显。'" width="560px"
    @update:model-value="emit('update:modelValue', $event)">
    <el-form novalidate label-width="96px" @submit.prevent="submit">
      <el-form-item label="名称" :error="errors.name">
        <el-input ref="nameInput" v-model="form.name" maxlength="100" placeholder="例如：订单系统测试密钥" />
      </el-form-item>
      <el-form-item label="类型" :error="errors.type">
        <el-select v-model="form.type" style="width: 100%" aria-label="凭据类型" :disabled="Boolean(editing)">
          <el-option v-if="permits('BEARER')" label="Bearer 令牌" value="BEARER" />
          <el-option v-if="permits('API_KEY_HEADER')" label="请求头 API Key" value="API_KEY_HEADER" />
          <el-option v-if="permits('BASIC')" label="基础认证" value="BASIC" />
          <el-option v-if="permits('API_KEY_QUERY')" label="查询参数 API Key" value="API_KEY_QUERY" />
          <el-option v-if="permits('CUSTOM_HEADERS')" label="自定义请求头" value="CUSTOM_HEADERS" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="!projectOnly && !editing" label="范围">
        <el-segmented v-model="form.scope" :options="scopeOptions" />
      </el-form-item>
      <el-form-item v-if="form.type === 'API_KEY_HEADER'" label="请求头" :error="errors.headerName">
        <el-input v-model="secret.headerName" placeholder="X-API-Key" />
      </el-form-item>
      <el-form-item v-if="supportsLogin" label="令牌来源">
        <el-segmented v-model="source" :options="sourceOptions" aria-label="令牌来源" />
        <small class="credential-hint">业务系统的令牌过一段时间就失效时选「登录换令牌」：平台用账号密码登录拿令牌，过期后自动重新登录。</small>
      </el-form-item>
      <template v-if="supportsLogin && source === 'LOGIN'">
        <el-form-item label="登录地址" :error="errors.loginUrl">
          <el-input v-model="login.url" placeholder="https://erp.example.com/api/login" aria-label="登录地址" />
        </el-form-item>
        <el-form-item label="请求格式">
          <el-segmented v-model="login.contentType" :options="contentTypeOptions" aria-label="登录请求格式" />
        </el-form-item>
        <el-form-item label="登录内容" :error="errors.secret">
          <el-input v-model="login.body" type="textarea" :rows="3" resize="none" aria-label="登录内容"
            :placeholder="login.contentType === 'application/json' ? '{&quot;username&quot;:&quot;admin&quot;,&quot;password&quot;:&quot;******&quot;}' : 'username=admin&password=******'" />
          <small class="credential-hint">照着业务系统登录接口的要求填，包含账号和密码。加密保存，页面不会回显。</small>
        </el-form-item>
        <el-form-item label="令牌位置" :error="errors.tokenPath">
          <el-input v-model="login.tokenPath" placeholder="不填则自动找，例如 data.token" aria-label="令牌位置" />
        </el-form-item>
        <el-form-item v-if="form.type === 'API_KEY_HEADER'" label="令牌前缀">
          <el-input v-model="login.prefix" placeholder="一般不填；需要时填 Bearer 加一个空格" aria-label="令牌前缀" />
        </el-form-item>
        <el-form-item label="重新登录">
          <el-input-number v-model="login.ttlMinutes" :min="1" :max="10080" aria-label="多少分钟后重新登录" />
          <small class="credential-hint">每隔这么多分钟重新登录一次；业务系统提前说令牌过期时也会马上重新登录。</small>
        </el-form-item>
      </template>
      <el-form-item v-else-if="form.type === 'BEARER'" label="令牌" :error="errors.secret">
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
      <LiquidGlassButton :disabled="saving" @click="emit('update:modelValue', false)">取消</LiquidGlassButton>
      <LiquidGlassButton type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '保存并选中' }}</LiquidGlassButton>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import { LiquidGlassButton } from '@/components/common/liquid-glass'
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AppDialog from '@/components/common/AppDialog.vue'
import { createWorkflowCredential, updateWorkflowCredential } from '@/api/workflowCredential'
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
  /** 传入已有凭据时是「更新」：名称、类型带出来，密钥重新填 */
  editing?: WorkflowCredential | null
}>(), { projectOnly: false })
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; created: [credential: WorkflowCredential];
  updated: [credential: WorkflowCredential] }>()
const sourceOptions = [{ label: '固定令牌', value: 'FIXED' }, { label: '登录换令牌', value: 'LOGIN' }]
const contentTypeOptions = [{ label: 'JSON', value: 'application/json' }, { label: '表单', value: 'application/x-www-form-urlencoded' }]
const source = ref<'FIXED' | 'LOGIN'>('FIXED')
const login = reactive({ url: '', contentType: 'application/json', body: '', tokenPath: '', prefix: '', ttlMinutes: 30 })
const supportsLogin = computed(() => form.type === 'BEARER' || form.type === 'API_KEY_HEADER')
const scopeOptions = [{ label: '项目', value: 'PROJECT' }, { label: '全局', value: 'GLOBAL' }]
const form = reactive({ name: '', type: 'BEARER' as WorkflowCredentialType, scope: 'PROJECT' as 'PROJECT' | 'GLOBAL' })
const secret = reactive({ token: '', username: '', password: '', headerName: 'X-API-Key',
  paramName: 'api_key', apiKey: '' })
const customHeaders = ref('')
const saving = ref(false)
const saveError = ref('')
const errors = reactive({ name: '', type: '', headerName: '', username: '', secret: '', loginUrl: '', tokenPath: '' })
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
  source.value = 'FIXED'
  Object.assign(login, { url: '', contentType: 'application/json', body: '', tokenPath: '', prefix: '', ttlMinutes: 30 })
  Object.keys(errors).forEach((key) => { errors[key as keyof typeof errors] = '' })
  const editing = props.editing
  if (!editing) return
  form.name = editing.name
  form.type = editing.type
  form.scope = editing.scope
  const preview = editing.secretPreview || {}
  if (typeof preview.headerName === 'string' && editing.type === 'API_KEY_HEADER') secret.headerName = preview.headerName
  const saved = preview.login as Record<string, unknown> | undefined
  if (saved && typeof saved === 'object') {
    source.value = 'LOGIN'
    Object.assign(login, { url: String(saved.url || ''), contentType: String(saved.contentType || 'application/json'),
      tokenPath: String(saved.tokenPath || ''), prefix: String(saved.prefix || ''), ttlMinutes: Number(saved.ttlMinutes) || 30 })
  }
}
watch(() => [props.modelValue, props.projectId, props.projectCode, props.editing], () => { if (props.modelValue) reset() })

function validate() {
  Object.keys(errors).forEach((key) => { errors[key as keyof typeof errors] = '' })
  if (!form.name.trim()) errors.name = '请填写凭据名称'
  if (!permits(form.type)) errors.type = '当前 API 不支持此凭据类型'
  if ((props.projectOnly || form.scope === 'PROJECT') && (!props.projectId || !props.projectCode)) {
    saveError.value = '请先确认当前项目，再创建项目凭据'
  }
  const byLogin = supportsLogin.value && source.value === 'LOGIN'
  if (byLogin) {
    if (!/^https?:\/\/[^\s/?#@]+(\/\S*)?$/i.test(login.url.trim())) errors.loginUrl = '请填写以 http:// 或 https:// 开头的登录地址'
    if (!login.body.trim()) errors.secret = '请填写登录内容（账号和密码）'
    if (login.tokenPath.trim() && !/^[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)*$/.test(login.tokenPath.trim())) errors.tokenPath = '写成 data.token 这样用点分隔的字段名'
  }
  if (form.type === 'BEARER' && !byLogin && !secret.token.trim()) errors.secret = '请填写令牌'
  if (form.type === 'API_KEY_HEADER') {
    if (!/^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}$/.test(secret.headerName)) errors.headerName = '请求头名称无效'
    if (!byLogin && !secret.apiKey.trim()) errors.secret = '请填写 API 密钥'
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
  if (supportsLogin.value && source.value === 'LOGIN') {
    const signIn = { url: login.url.trim(), method: 'POST', contentType: login.contentType, body: login.body,
      tokenPath: login.tokenPath.trim(), prefix: form.type === 'API_KEY_HEADER' ? login.prefix : '', ttlMinutes: login.ttlMinutes }
    return form.type === 'BEARER' ? { login: signIn } : { headerName: secret.headerName, login: signIn }
  }
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
    const editing = props.editing
    const payload = {
      name: form.name.trim(), type: form.type, scope: editing ? editing.scope : props.projectOnly ? 'PROJECT' as const : form.scope,
      projectId: editing ? editing.projectId ?? null : props.projectId ?? null,
      projectCode: editing ? editing.projectCode ?? null : props.projectCode ?? null,
      status: 'ACTIVE' as const, secret: buildSecret(),
    }
    if (editing) {
      const { data } = await updateWorkflowCredential(editing.id, { ...payload, credentialRef: editing.credentialRef })
      emit('updated', data)
    } else {
      const { data } = await createWorkflowCredential(payload)
      emit('created', data)
    }
    emit('update:modelValue', false)
    reset()
    ElMessage.success(editing ? '凭据已更新' : '凭据已保存')
  } catch (error) {
    const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    saveError.value = message && /[\u4e00-\u9fa5]/.test(message) ? `凭据保存失败：${message}` : '凭据保存失败；请检查项目权限和填写内容后重试'
  } finally { saving.value = false }
}
</script>

<style scoped lang="scss">
.credential-error { color: var(--el-color-danger); margin: 0 0 8px 96px; font-size: 13px; }
.credential-hint { display: block; width: 100%; margin-top: 4px; font-size: 12px; line-height: 1.5; color: var(--text-secondary); }
</style>
