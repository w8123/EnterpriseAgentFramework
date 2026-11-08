<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import {
  LiquidGlassButton,
  LiquidGlassEmptyState,
  LiquidGlassNotice,
  LiquidGlassTable,
} from '@/components/common/liquid-glass'
import {
  listBusinessSsoProviders,
  saveBusinessSsoProvider,
  type BusinessSsoProviderList,
  type BusinessSsoProviderView,
} from '@/api/platformAuth'
import { businessSsoPresets, headerLines, parseHeaderLines, type BusinessSsoPresetKey } from './businessSsoPresets'

const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const list = ref<BusinessSsoProviderList | null>(null)
const dialogOpen = ref(false)
const isNew = ref(true)
const preset = ref<BusinessSsoPresetKey>('yudao')
const systemOrigin = ref('')

const form = reactive({
  code: '',
  label: '',
  projectCode: '',
  authorizeUrl: '',
  tokenUrl: '',
  userInfoUrl: '',
  clientId: '',
  clientSecret: '',
  clientSecretPresent: false,
  scope: '',
  userIdPath: '',
  userNamePath: '',
  headers: '',
  active: true,
})

const providers = computed(() => list.value?.providers ?? [])

async function reload() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await listBusinessSsoProviders()
    list.value = data
  } catch (error) {
    const failure = error as { response?: { data?: { message?: string } }; message?: string }
    loadError.value = failure?.response?.data?.message || failure?.message || '请检查网络后重试'
  } finally {
    loading.value = false
  }
}

function openCreate() {
  isNew.value = true
  preset.value = 'yudao'
  systemOrigin.value = ''
  Object.assign(form, {
    code: '',
    label: '',
    projectCode: '',
    authorizeUrl: '',
    tokenUrl: '',
    userInfoUrl: '',
    clientId: 'reachai-login',
    clientSecret: '',
    clientSecretPresent: false,
    scope: '',
    userIdPath: '',
    userNamePath: '',
    headers: '',
    active: true,
  })
  applyPreset()
  dialogOpen.value = true
}

function openEdit(row: BusinessSsoProviderView) {
  isNew.value = false
  Object.assign(form, {
    code: row.code,
    label: row.label,
    projectCode: row.projectCode,
    authorizeUrl: row.authorizeUrl,
    tokenUrl: row.tokenUrl,
    userInfoUrl: row.userInfoUrl,
    clientId: row.clientId,
    clientSecret: '',
    clientSecretPresent: row.clientSecretPresent,
    scope: row.scope ?? '',
    userIdPath: row.userIdPath,
    userNamePath: row.userNamePath ?? '',
    headers: headerLines(row.extraHeaders),
    active: row.active,
  })
  dialogOpen.value = true
}

/** Fills the paths for the chosen kind of business system; addresses follow the system's address. */
function applyPreset() {
  const values = businessSsoPresets[preset.value].fill(systemOrigin.value)
  Object.assign(form, values)
}

async function save() {
  let extraHeaders: Record<string, string>
  try {
    extraHeaders = parseHeaderLines(form.headers)
  } catch (error) {
    ElMessage.warning((error as Error).message)
    return
  }
  if (!form.code.trim()) {
    ElMessage.warning('请填写编码')
    return
  }
  saving.value = true
  try {
    await saveBusinessSsoProvider(form.code.trim(), {
      label: form.label,
      projectCode: form.projectCode,
      authorizeUrl: form.authorizeUrl,
      tokenUrl: form.tokenUrl,
      userInfoUrl: form.userInfoUrl,
      clientId: form.clientId,
      clientSecret: form.clientSecret || undefined,
      scope: form.scope || undefined,
      userIdPath: form.userIdPath,
      userNamePath: form.userNamePath || undefined,
      extraHeaders,
      active: form.active,
    })
    ElMessage.success(form.active ? '已保存，登录页会显示这个按钮' : '已保存（未启用）')
    dialogOpen.value = false
    await reload()
  } catch (error) {
    const failure = error as { response?: { data?: { message?: string } }; message?: string }
    ElMessage.error(failure?.response?.data?.message || failure?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function copyCallback() {
  if (!list.value?.callbackUrl) return
  try {
    await navigator.clipboard.writeText(list.value.callbackUrl)
    ElMessage.success('已复制回调地址')
  } catch {
    ElMessage.info(list.value.callbackUrl)
  }
}

onMounted(reload)
</script>

<template>
  <section class="sso-panel" aria-labelledby="sso-panel-title">
    <header class="sso-panel__header">
      <div>
        <h2 id="sso-panel-title">用业务系统账号登录</h2>
        <p>
          登录页会多一个「用 XX 账号登录」按钮。员工已经登录业务系统的话，点一下就进来，没有确认页，进的是「我的」个人页。
          在「平台用户」里绑定了业务系统账号的人（经理、开发人员）会直接进管理端。
        </p>
      </div>
      <LiquidGlassButton type="primary" :icon="Plus" @click="openCreate">添加</LiquidGlassButton>
    </header>

    <LiquidGlassNotice
      v-if="loadError"
      type="error"
      title="读取失败"
      :description="loadError"
      ><template #actions><LiquidGlassButton :disabled="loading" @click="reload">重试</LiquidGlassButton></template></LiquidGlassNotice
    >
    <LiquidGlassNotice
      v-else-if="list && !list.encryptionAvailable"
      type="warning"
      title="服务器没有配置加密密钥"
      description="需要设置 REACHAI_INTERNAL_SERVICE_SECRET（或 REACHAI_BUSINESS_SSO_KEY_BASE64）后才能保存登录密钥。"
    />
    <div v-if="list" class="sso-panel__callback">
      <span>回调地址（在业务系统里登记）</span>
      <code>{{ list.callbackUrl }}</code>
      <LiquidGlassButton size="small" @click="copyCallback">复制</LiquidGlassButton>
    </div>

    <LiquidGlassEmptyState
      v-if="list && !providers.length"
      compact
      title="还没有业务系统登录"
      description="添加后员工就能用芋道等业务系统的账号直接登录 ReachAI。"
    />
    <LiquidGlassTable v-else-if="list" :loading="loading" :show-pagination="false" class="workbench-list-surface">
      <el-table :data="providers" row-key="code">
        <el-table-column label="按钮" min-width="160"
          ><template #default="{ row }"
            ><strong>{{ row.label }}</strong> <code>{{ row.code }}</code></template
          ></el-table-column
        >
        <el-table-column prop="projectCode" label="业务系统" min-width="120" />
        <el-table-column label="登录页地址" min-width="220"
          ><template #default="{ row }"><span class="sso-url">{{ row.authorizeUrl }}</span></template></el-table-column
        >
        <el-table-column label="状态" width="120"
          ><template #default="{ row }"
            ><el-tag v-if="row.active" type="success" effect="light">已启用</el-tag
            ><el-tag v-else type="info" effect="light">未启用</el-tag
            ><el-tag v-if="!row.clientSecretPresent" type="warning" effect="light">缺密钥</el-tag></template
          ></el-table-column
        >
        <el-table-column label="操作" width="90"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="openEdit(row)">编辑</el-button></template
          ></el-table-column
        >
      </el-table>
    </LiquidGlassTable>

    <AppDialog v-model="dialogOpen" :title="isNew ? '添加业务系统登录' : '编辑业务系统登录'" width="680px">
      <el-form :model="form" label-width="128px">
        <template v-if="isNew">
          <el-form-item label="业务系统类型">
            <el-radio-group v-model="preset" @change="applyPreset">
              <el-radio-button v-for="(item, key) in businessSsoPresets" :key="key" :value="key">{{
                item.label
              }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="业务系统地址">
            <el-input v-model="systemOrigin" placeholder="例如 https://yudao-test.w8123.com" @change="applyPreset" />
            <div class="form-hint">{{ businessSsoPresets[preset].hint }}</div>
          </el-form-item>
        </template>
        <el-form-item label="编码" required>
          <el-input v-model="form.code" :disabled="!isNew" placeholder="小写字母、数字、横线，例如 yudao" />
        </el-form-item>
        <el-form-item label="按钮名称" required>
          <el-input v-model="form.label" placeholder="例如 芋道 ERP，按钮显示为「用芋道 ERP 账号登录」" />
        </el-form-item>
        <el-form-item label="业务系统编码" required>
          <el-input v-model="form.projectCode" placeholder="ReachAI 里的业务系统编码，例如 yudao-erp" />
          <div class="form-hint">员工登录后按这个系统里的角色使用 AI 工具，和从业务系统 AI 面板进来时一样。</div>
        </el-form-item>
        <el-form-item label="登录页地址" required><el-input v-model="form.authorizeUrl" /></el-form-item>
        <el-form-item label="换令牌地址" required><el-input v-model="form.tokenUrl" /></el-form-item>
        <el-form-item label="用户信息地址" required><el-input v-model="form.userInfoUrl" /></el-form-item>
        <el-form-item label="Client ID" required><el-input v-model="form.clientId" /></el-form-item>
        <el-form-item label="Client Secret" :required="!form.clientSecretPresent">
          <el-input
            v-model="form.clientSecret"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="form.clientSecretPresent ? '已保存，不填就保持不变' : '在业务系统里创建登录客户端时得到'"
          />
        </el-form-item>
        <el-form-item label="权限范围"><el-input v-model="form.scope" placeholder="例如 user.read" /></el-form-item>
        <el-form-item label="用户 ID 字段" required>
          <el-input v-model="form.userIdPath" placeholder="例如 data.id 或 sub" />
        </el-form-item>
        <el-form-item label="姓名字段"><el-input v-model="form.userNamePath" placeholder="例如 data.nickname 或 name" /></el-form-item>
        <el-form-item label="附加请求头">
          <el-input v-model="form.headers" type="textarea" :rows="2" placeholder="每行一个，例如 tenant-id: 1" />
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.active" /></el-form-item>
      </el-form>
      <template #footer>
        <LiquidGlassButton @click="dialogOpen = false">取消</LiquidGlassButton>
        <LiquidGlassButton type="primary" :loading="saving" @click="save">保存</LiquidGlassButton>
      </template>
    </AppDialog>
  </section>
</template>

<style scoped lang="scss">
.sso-panel {
  display: grid;
  gap: 12px;
  min-width: 0;
}
.sso-panel__header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.sso-panel__header h2 {
  margin: 0 0 4px;
  color: var(--text-primary);
  font-size: 16px;
  font-weight: 650;
}
.sso-panel__header p {
  max-width: 720px;
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.sso-panel__callback {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 13px;
}
.sso-panel__callback code,
code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: var(--text-secondary);
  overflow-wrap: anywhere;
}
.sso-url {
  color: var(--text-secondary);
  overflow-wrap: anywhere;
}
.form-hint {
  width: 100%;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
}
</style>
