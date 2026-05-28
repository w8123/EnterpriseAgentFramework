<template>
  <div class="auth-provider-page">
    <div class="page-header">
      <div>
        <h2>认证源配置</h2>
        <p>管理平台登录的 LOCAL、HEADER、OIDC、SAML Provider。敏感字段只允许写入，不回显明文。</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" :icon="Plus" @click="openCreate">新增 Provider</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      </div>
    </div>

    <el-table :data="providers" v-loading="loading" stripe>
      <el-table-column prop="providerCode" label="编码" width="130">
        <template #default="{ row }">
          <code>{{ row.providerCode }}</code>
        </template>
      </el-table-column>
      <el-table-column prop="providerName" label="名称" min-width="180" />
      <el-table-column prop="providerType" label="类型" width="120">
        <template #default="{ row }">
          <el-tag size="small">{{ row.providerType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="configJson" label="配置 JSON（敏感值已脱敏）" min-width="360" show-overflow-tooltip>
        <template #default="{ row }">
          <code>{{ row.configJson }}</code>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="170" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogOpen" title="认证源配置" width="680px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="保存会覆盖完整 config_json；如包含 clientSecret、password、token 等字段，请重新填入真实值。"
        style="margin-bottom: 12px"
      />
      <el-form :model="editing" label-width="110px">
        <el-form-item label="Provider 编码" required>
          <el-input v-model="editing.providerCode" placeholder="OIDC / SAML / HEADER / LOCAL" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="editing.providerName" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="editing.providerType">
            <el-option label="LOCAL" value="LOCAL" />
            <el-option label="HEADER" value="HEADER" />
            <el-option label="OIDC" value="OIDC" />
            <el-option label="SAML" value="SAML" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="editing.status">
            <el-option label="ACTIVE" value="ACTIVE" />
            <el-option label="INACTIVE" value="INACTIVE" />
          </el-select>
        </el-form-item>
        <el-form-item label="配置 JSON">
          <el-input
            v-model="editing.configJson"
            type="textarea"
            :rows="10"
            spellcheck="false"
            placeholder='{"issuerUri":"https://iam.example.com","clientId":"reachai"}'
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  listPlatformAuthProviders,
  savePlatformAuthProvider,
} from '@/api/platformAuth'
import type {
  PlatformAuthProviderCommand,
  PlatformAuthProviderView,
} from '@/api/platformAuth'

const loading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const providers = ref<PlatformAuthProviderView[]>([])
const editing = reactive<PlatformAuthProviderCommand>({
  providerCode: '',
  providerName: '',
  providerType: 'OIDC',
  status: 'INACTIVE',
  configJson: '{}',
})

async function reload() {
  loading.value = true
  try {
    const { data } = await listPlatformAuthProviders()
    providers.value = data ?? []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.providerCode = ''
  editing.providerName = ''
  editing.providerType = 'OIDC'
  editing.status = 'INACTIVE'
  editing.configJson = '{}'
  dialogOpen.value = true
}

function openEdit(row: PlatformAuthProviderView) {
  editing.providerCode = row.providerCode
  editing.providerName = row.providerName
  editing.providerType = row.providerType
  editing.status = row.status
  editing.configJson = row.configJson || '{}'
  dialogOpen.value = true
}

async function save() {
  if (!editing.providerCode || !editing.providerType) {
    ElMessage.warning('请填写 Provider 编码和类型')
    return
  }
  try {
    JSON.parse(editing.configJson || '{}')
  } catch {
    ElMessage.warning('配置 JSON 格式不正确')
    return
  }
  saving.value = true
  try {
    await savePlatformAuthProvider({ ...editing })
    ElMessage.success('已保存认证源配置')
    dialogOpen.value = false
    await reload()
  } finally {
    saving.value = false
  }
}

onMounted(reload)
</script>

<style scoped lang="scss">
.auth-provider-page {
  padding: 16px 20px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;

  h2 {
    margin: 0 0 4px;
    font-size: 20px;
  }

  p {
    margin: 0;
    color: #667085;
    font-size: 13px;
  }
}

.header-actions {
  display: flex;
  gap: 8px;
}

code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: #344054;
}
</style>
