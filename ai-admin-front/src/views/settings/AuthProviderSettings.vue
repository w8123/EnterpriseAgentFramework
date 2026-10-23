<template>
  <LiquidGlassPage class="auth-provider-page">
    <PageHeader
      variant="standard"
      presentation="plain"
      action-presentation="inline"
      domain="platform"
      title="认证源配置"
      description="配置平台登录方式及身份认证来源。"
    >
      <template v-if="loaded" #tags>
        <el-tag type="info" effect="light">共 {{ providers.length }} 个认证源</el-tag>
        <el-tag type="success" effect="light">当前启用 {{ activeCount }} 个</el-tag>
      </template>
      <template #actions>
        <el-tooltip content="刷新认证源" placement="top">
          <LiquidGlassButton source="wallpaper" circle :icon="Refresh" :loading="loading" aria-label="刷新认证源" @click="reload" />
        </el-tooltip>
      </template>
    </PageHeader>

    <LiquidGlassTable
      class="workbench-list-surface"
      :loading="loading"
      :empty="loaded && providers.length === 0"
      empty-description="暂无认证源"
      :show-pagination="false"
    >
    <el-table :data="providers" stripe>
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
      <el-table-column label="配置" min-width="180">
        <template #default="{ row }">
          <el-tag v-if="row.configurationPresent" type="warning">已配置（敏感值不返回浏览器）</el-tag>
          <span v-else class="text-muted">无敏感配置</span>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="170" :formatter="formatTableDateTime" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>
    </LiquidGlassTable>

    <AppDialog v-model="dialogOpen" title="认证源配置" width="680px">
      <LiquidGlassNotice
        type="info"
        title="当前版本只支持且固定启用 LOCAL 登录。OIDC、SAML 和 HEADER 保持禁用，且认证源密钥不会在浏览器中编辑或返回。"
        style="margin-bottom: 12px"
        source="backdrop"
      />
      <el-form :model="editing" label-width="110px">
        <el-form-item label="认证源编码" required>
          <el-input v-model="editing.providerCode" disabled />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="editing.providerName" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="editing.providerType" disabled>
            <el-option
              v-for="item in AUTH_PROVIDER_TYPE_SELECT_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="editing.status" disabled>
            <el-option
              v-for="item in COMMON_STATUS_SELECT_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <LiquidGlassButton @click="dialogOpen = false">取消</LiquidGlassButton>
        <LiquidGlassButton type="primary" :loading="saving" @click="save">保存</LiquidGlassButton>
      </template>
    </AppDialog>
  </LiquidGlassPage>
</template>

<script setup lang="ts">
import { formatTableDateTime } from '@/utils/dateTime'
import AppDialog from '@/components/common/AppDialog.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { LiquidGlassButton, LiquidGlassPage, LiquidGlassTable, LiquidGlassNotice } from '@/components/common/liquid-glass'
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
const loaded = ref(false)
const activeCount = computed(() => providers.value.filter((item) => item.status === 'ACTIVE').length)
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
    loaded.value = true
  } finally {
    loading.value = false
  }
}

function openEdit(row: PlatformAuthProviderView) {
  editing.providerCode = row.providerCode
  editing.providerName = row.providerName
  editing.providerType = row.providerType
  editing.status = row.status
  editing.configJson = '{}'
  dialogOpen.value = true
}

async function save() {
  if (!editing.providerCode || !editing.providerType) {
    ElMessage.warning('请填写认证源编码和类型')
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
  color: var(--text-secondary);
}
</style>
