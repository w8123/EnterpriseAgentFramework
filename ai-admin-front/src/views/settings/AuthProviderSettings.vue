<template>
  <WorkbenchPage class="auth-provider-page" layout="list">
    <PageHeader
      variant="standard"
      domain="platform"
      eyebrow="Identity Provider"
      title="认证源配置"
      description="管理平台登录的本地、网关请求头、OIDC、SAML 等认证源。"
    >
      <template #actions>
        <el-tooltip content="刷新认证源" placement="top">
          <el-button circle :icon="Refresh" :loading="loading" aria-label="刷新认证源" @click="reload" />
        </el-tooltip>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增认证源</el-button>
      </template>
    </PageHeader>

    <el-table class="workbench-list-surface" :data="providers" v-loading="loading" stripe>
      <el-table-column prop="providerCode" label="编码" width="130">
        <template #default="{ row }">
          <code>{{ row.providerCode }}</code>
        </template>
      </el-table-column>
      <el-table-column prop="providerName" label="名称" min-width="180">
        <template #default="{ row }">
          {{ formatAuthProviderName(row.providerCode, row.providerName) }}
        </template>
      </el-table-column>
      <el-table-column prop="providerType" label="类型" width="150">
        <template #default="{ row }">
          <el-tag size="small">{{ formatAuthProviderTypeLabel(row.providerType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="110">
        <template #default="{ row }">
          <CommonStatusTag :status="row.status" />
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

    <AppDialog v-model="dialogOpen" title="认证源配置" width="680px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="保存会覆盖完整 config_json；如包含 clientSecret、password、token 等字段，请重新填入真实值。"
        style="margin-bottom: 12px"
      />
      <el-form :model="editing" label-width="110px">
        <el-form-item label="认证源编码" required>
          <el-input v-model="editing.providerCode" placeholder="如 OIDC、SAML、HEADER、LOCAL" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="editing.providerName" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="editing.providerType">
            <el-option
              v-for="item in AUTH_PROVIDER_TYPE_SELECT_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="editing.status">
            <el-option
              v-for="item in COMMON_STATUS_SELECT_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
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
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import CommonStatusTag from '@/components/CommonStatusTag.vue'
import {
  AUTH_PROVIDER_TYPE_SELECT_OPTIONS,
  COMMON_STATUS_SELECT_OPTIONS,
  formatAuthProviderName,
  formatAuthProviderTypeLabel,
} from '@/utils/uiLabels'
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
    ElMessage.warning('请填写认证源编码和类型')
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
code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: #344054;
}
</style>
