<template>
  <AppDialog :model-value="modelValue" title="接口鉴权" width="620px" destroy-on-close
    description="业务系统一般都要登录或带 Token 才能调用。这里配置的服务地址和凭据，本项目所有 API 都会默认使用；凭据加密保存，页面不会回显。"
    @update:model-value="emit('update:modelValue', $event)">
    <LiquidGlassNotice v-if="!sampleApiId" type="info" source="backdrop" title="先添加一个 API"
      description="项目里还没有 API。先用「添加 API」导入或填写接口，再回来配置鉴权。" />
    <div v-else-if="loading" class="auth-loading">正在读取当前配置…</div>
    <LiquidGlassNotice v-else-if="loadError" type="error" source="backdrop" :title="loadError">
      <template #actions><el-button link type="primary" @click="load">重新读取</el-button></template>
    </LiquidGlassNotice>
    <el-form v-else-if="current" novalidate label-position="top" @submit.prevent="save">
      <p class="auth-status">
        <el-tag size="small" effect="light" :type="current.status === 'CONFIGURED' ? 'success' : current.status === 'BLOCKED' ? 'danger' : 'info'">
          {{ current.status === 'CONFIGURED' ? '已配置' : current.status === 'BLOCKED' ? '配置有问题' : '未配置' }}
        </el-tag>
        <span>{{ current.projectCode }} · {{ current.environment }}</span>
      </p>
      <p v-if="current.blockingReason && current.status === 'BLOCKED'" class="auth-warning" role="status">{{ current.blockingReason }}</p>
      <el-form-item label="服务地址" :error="originError">
        <el-input v-model="origin" placeholder="https://erp.example.com" :disabled="!canWrite" aria-label="服务地址" />
        <small class="auth-hint">只填协议、域名和端口，比如 https://erp.example.com:8443；接口路径会自动拼上。</small>
      </el-form-item>
      <el-form-item label="鉴权方式">
        <el-radio-group v-model="authMode" :disabled="!canWrite">
          <el-radio-button value="BEARER">Bearer 令牌</el-radio-button>
          <el-radio-button value="API_KEY_HEADER">请求头 API Key</el-radio-button>
          <el-radio-button value="NONE">不需要</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="authMode !== 'NONE'" label="凭据">
        <div class="auth-credential-row">
          <el-select v-model="credentialRef" filterable clearable placeholder="选择本项目的凭据" :loading="credentialsLoading"
            :disabled="!canWrite || credentialsLoading" aria-label="凭据">
            <el-option v-for="item in eligibleCredentials" :key="item.credentialRef" :label="`${item.name} / ${item.type === 'BEARER' ? 'Bearer' : 'API Key'}`" :value="item.credentialRef" />
          </el-select>
          <el-button v-if="canManageCredential" @click="credentialDialog = true">新建凭据</el-button>
        </div>
        <small v-if="!credentialsLoading && !eligibleCredentials.length" class="auth-hint">
          还没有这种类型的凭据。{{ canManageCredential ? '点「新建凭据」，把 Token 或 API Key 填进去。' : '请联系有凭据管理权限的同事新建。' }}
        </small>
      </el-form-item>
      <p v-if="saveError" class="auth-warning" role="alert">{{ saveError }}</p>
      <p v-if="!current.configurationAllowed" class="auth-warning">当前还不能配置：请先接纳至少一个 API 的契约。</p>
    </el-form>
    <CredentialCreateDialog v-if="current" v-model="credentialDialog" project-only :project-id="projectId" :project-code="projectCode"
      :allowed-types="['API_KEY_HEADER', 'BEARER']" :initial-type="authMode === 'API_KEY_HEADER' ? 'API_KEY_HEADER' : 'BEARER'"
      @created="credentialCreated" />
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button v-if="current" type="primary" :loading="saving" :disabled="!canWrite || !current.configurationAllowed" @click="save">保存</el-button>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AppDialog from '@/components/common/AppDialog.vue'
import CredentialCreateDialog from '@/components/credential/CredentialCreateDialog.vue'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'
import { getHttpApiProjectDefaultConnection, saveHttpApiProjectDefaultConnection } from '@/api/httpApi'
import { listWorkflowCredentials } from '@/api/workflowCredential'
import type { HttpApiProjectDefaultConnection } from '@/types/httpApi'
import type { WorkflowCredential } from '@/types/workflowCredential'

const props = defineProps<{
  modelValue: boolean
  projectId: number
  projectCode: string
  baseUrl?: string | null
  /** 项目默认连接通过本项目任一 API 定位 */
  sampleApiId: number | null
  canWrite: boolean
  canManageCredential: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const current = ref<HttpApiProjectDefaultConnection | null>(null)
const loading = ref(false)
const loadError = ref('')
const saving = ref(false)
const saveError = ref('')
const originError = ref('')
const origin = ref('')
const authMode = ref<'NONE' | 'API_KEY_HEADER' | 'BEARER'>('BEARER')
const credentialRef = ref('')
const credentials = ref<WorkflowCredential[]>([])
const credentialsLoading = ref(false)
const credentialDialog = ref(false)
let sequence = 0

const eligibleCredentials = computed(() => credentials.value.filter(item => item.scope === 'PROJECT'
  && item.projectCode === props.projectCode && item.type === authMode.value))

watch(() => props.modelValue, open => { if (open) void load() })

function originOf(url?: string | null) {
  try { return url ? new URL(url).origin : '' } catch { return '' }
}

async function load() {
  if (!props.sampleApiId) return
  const mine = ++sequence
  loading.value = true; loadError.value = ''; saveError.value = ''; originError.value = ''; current.value = null
  try {
    const { data } = await getHttpApiProjectDefaultConnection(props.sampleApiId)
    if (mine !== sequence) return
    current.value = data
    origin.value = data.origin || originOf(props.baseUrl)
    authMode.value = (['NONE', 'API_KEY_HEADER', 'BEARER'].includes(data.authMode || '') ? data.authMode : 'BEARER') as typeof authMode.value
    credentialRef.value = data.credentialRef || ''
    void loadCredentials()
  } catch {
    if (mine === sequence) loadError.value = '读取鉴权配置失败'
  } finally { if (mine === sequence) loading.value = false }
}
async function loadCredentials() {
  credentialsLoading.value = true
  try {
    const { data } = await listWorkflowCredentials({ projectId: props.projectId, projectCode: props.projectCode })
    credentials.value = Array.isArray(data) ? data : []
  } catch { credentials.value = [] }
  finally { credentialsLoading.value = false }
}
function credentialCreated(item: WorkflowCredential) {
  credentials.value = [...credentials.value.filter(entry => entry.credentialRef !== item.credentialRef), item]
  credentialRef.value = item.credentialRef
}
async function save() {
  if (!current.value || !props.sampleApiId || saving.value || !props.canWrite) return
  saveError.value = ''; originError.value = ''
  if (!/^https?:\/\/[^/?#]+\/?$/i.test(origin.value.trim())) { originError.value = '只填 http(s)://域名:端口，不要带路径'; return }
  if (authMode.value !== 'NONE' && !eligibleCredentials.value.some(item => item.credentialRef === credentialRef.value)) {
    saveError.value = '请选择或新建一个凭据'; return
  }
  saving.value = true
  try {
    await saveHttpApiProjectDefaultConnection(props.sampleApiId, { origin: origin.value.trim().replace(/\/$/, ''), authMode: authMode.value,
      credentialRef: authMode.value === 'NONE' ? null : credentialRef.value, expectedRevision: current.value.revision ?? null })
    ElMessage.success('接口鉴权已保存，本项目的 API 会默认使用')
    emit('update:modelValue', false)
  } catch {
    saveError.value = '保存失败：配置可能刚被别人改过，请重新打开后再试'
  } finally { saving.value = false }
}
</script>

<style scoped>
.auth-loading { padding: 24px 0; color: var(--text-secondary); }
.auth-status { display: flex; align-items: center; gap: 8px; margin: 0 0 12px; font-size: 13px; color: var(--text-secondary); }
.auth-warning { margin: 0 0 12px; font-size: 13px; color: var(--el-color-warning); }
.auth-hint { display: block; margin-top: 4px; font-size: 12px; line-height: 1.5; color: var(--text-secondary); }
.auth-credential-row { display: flex; gap: 8px; width: 100%; }
.auth-credential-row .el-select { flex: 1; }
</style>
