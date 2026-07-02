<template>
  <div class="credential-select">
    <el-select
      :model-value="modelValue"
      clearable
      filterable
      placeholder="选择凭据"
      style="width: 100%"
      @update:model-value="$emit('update:modelValue', $event || '')"
    >
      <el-option
        v-for="item in credentials"
        :key="item.credentialRef"
        :label="`${item.name} / ${item.type}`"
        :value="item.credentialRef"
      />
    </el-select>
    <el-button :icon="Plus" @click="dialogVisible = true">新建</el-button>
  </div>

  <el-dialog v-model="dialogVisible" title="新建工作流凭据" width="560px">
    <el-form label-width="96px">
      <el-form-item label="名称">
        <el-input v-model="form.name" placeholder="例如：订单系统 API 密钥" />
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="form.type" style="width: 100%">
          <el-option label="Bearer 令牌" value="BEARER" />
          <el-option label="基础认证" value="BASIC" />
          <el-option label="请求头 API Key" value="API_KEY_HEADER" />
          <el-option label="查询参数 API Key" value="API_KEY_QUERY" />
          <el-option label="自定义请求头" value="CUSTOM_HEADERS" />
        </el-select>
      </el-form-item>
      <el-form-item label="范围">
        <el-segmented v-model="form.scope" :options="scopeOptions" />
      </el-form-item>

      <template v-if="form.type === 'BEARER'">
        <el-form-item label="令牌">
          <el-input v-model="secret.token" type="password" show-password />
        </el-form-item>
      </template>
      <template v-else-if="form.type === 'BASIC'">
        <el-form-item label="用户名">
          <el-input v-model="secret.username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="secret.password" type="password" show-password />
        </el-form-item>
      </template>
      <template v-else-if="form.type === 'API_KEY_HEADER'">
        <el-form-item label="请求头">
          <el-input v-model="secret.headerName" placeholder="X-API-Key" />
        </el-form-item>
        <el-form-item label="API 密钥">
          <el-input v-model="secret.apiKey" type="password" show-password />
        </el-form-item>
      </template>
      <template v-else-if="form.type === 'API_KEY_QUERY'">
        <el-form-item label="参数名">
          <el-input v-model="secret.paramName" placeholder="api_key" />
        </el-form-item>
        <el-form-item label="API 密钥">
          <el-input v-model="secret.apiKey" type="password" show-password />
        </el-form-item>
      </template>
      <template v-else>
        <el-form-item label="请求头">
          <el-input
            v-model="customHeaders"
            type="textarea"
            :rows="5"
            placeholder="X-App-Key = abc&#10;X-App-Secret = xyz"
          />
        </el-form-item>
      </template>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存并选中</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import type { WorkflowCredential, WorkflowCredentialType } from '@/types/workflowCredential'
import { createWorkflowCredential } from '@/api/workflowCredential'
import { parseMap } from './panelUtils'

const props = defineProps<{
  modelValue?: string
  credentials: WorkflowCredential[]
  projectId?: number | null
  projectCode?: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  created: [credential: WorkflowCredential]
}>()

const dialogVisible = ref(false)
const saving = ref(false)
const customHeaders = ref('')
const scopeOptions = [
  { label: '项目', value: 'PROJECT' },
  { label: '全局', value: 'GLOBAL' },
]
const form = reactive({
  name: '',
  type: 'BEARER' as WorkflowCredentialType,
  scope: 'PROJECT' as 'PROJECT' | 'GLOBAL',
})
const secret = reactive<Record<string, string>>({
  token: '',
  username: '',
  password: '',
  headerName: 'X-API-Key',
  paramName: 'api_key',
  apiKey: '',
})

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写凭据名称')
    return
  }
  saving.value = true
  try {
    const { data } = await createWorkflowCredential({
      name: form.name.trim(),
      type: form.type,
      scope: form.scope,
      projectId: props.projectId ?? null,
      projectCode: props.projectCode ?? null,
      status: 'ACTIVE',
      secret: buildSecret(),
    })
    emit('created', data)
    emit('update:modelValue', data.credentialRef)
    dialogVisible.value = false
    reset()
    ElMessage.success('凭据已保存')
  } finally {
    saving.value = false
  }
}

function buildSecret() {
  if (form.type === 'BEARER') return { token: secret.token }
  if (form.type === 'BASIC') return { username: secret.username, password: secret.password }
  if (form.type === 'API_KEY_HEADER') return { headerName: secret.headerName || 'X-API-Key', apiKey: secret.apiKey }
  if (form.type === 'API_KEY_QUERY') return { paramName: secret.paramName || 'api_key', apiKey: secret.apiKey }
  return { headers: parseMap(customHeaders.value) }
}

function reset() {
  form.name = ''
  form.type = 'BEARER'
  form.scope = 'PROJECT'
  Object.assign(secret, {
    token: '',
    username: '',
    password: '',
    headerName: 'X-API-Key',
    paramName: 'api_key',
    apiKey: '',
  })
  customHeaders.value = ''
}
</script>

<style scoped lang="scss">
.credential-select {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px;
  width: 100%;
}
</style>
