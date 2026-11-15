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
  mode: 'OAUTH' as 'OAUTH' | 'PASSWORD',
  loginContentType: 'JSON' as 'JSON' | 'FORM',
  loginBody: '',
  tokenPath: '',
  tokenHeader: '',
  tokenPrefix: '',
  defaultRole: '',
  employeeToken: 'OFF' as 'OFF' | 'PREFERRED' | 'REQUIRED',
})
const passwordMode = computed(() => form.mode === 'PASSWORD')

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
    mode: 'OAUTH',
    loginContentType: 'JSON',
    loginBody: '',
    tokenPath: '',
    tokenHeader: '',
    tokenPrefix: '',
    defaultRole: '',
    employeeToken: 'OFF',
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
    authorizeUrl: row.authorizeUrl ?? '',
    tokenUrl: row.tokenUrl,
    userInfoUrl: row.userInfoUrl ?? '',
    clientId: row.clientId ?? '',
    clientSecret: '',
    clientSecretPresent: row.clientSecretPresent,
    scope: row.scope ?? '',
    userIdPath: row.userIdPath ?? '',
    userNamePath: row.userNamePath ?? '',
    headers: headerLines(row.extraHeaders),
    active: row.active,
    mode: row.mode === 'PASSWORD' ? 'PASSWORD' : 'OAUTH',
    loginContentType: row.loginContentType === 'FORM' ? 'FORM' : 'JSON',
    loginBody: row.loginBody ?? '',
    tokenPath: row.tokenPath ?? '',
    tokenHeader: row.tokenHeader ?? '',
    tokenPrefix: row.tokenPrefix ?? '',
    defaultRole: row.defaultRole ?? '',
    employeeToken: row.employeeToken === 'REQUIRED' || row.employeeToken === 'PREFERRED' ? row.employeeToken : 'OFF',
  })
  dialogOpen.value = true
}

/** Fills the paths for the chosen kind of business system; addresses follow the system's address. */
function applyPreset() {
  const values = businessSsoPresets[preset.value].fill(systemOrigin.value)
  // Leaving the 芋道 preset: its sample code and names must not stick to another system.
  if (preset.value !== 'yudao' && form.code === 'yudao' && form.projectCode === 'yudao-erp') {
    Object.assign(form, { code: '', label: '', projectCode: '' })
  }
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
      tokenUrl: form.tokenUrl,
      userInfoUrl: form.userInfoUrl || undefined,
      userIdPath: form.userIdPath || undefined,
      userNamePath: form.userNamePath || undefined,
      defaultRole: form.defaultRole.trim() || undefined,
      employeeToken: form.employeeToken,
      extraHeaders,
      active: form.active,
      ...(passwordMode.value
        ? {
            mode: 'PASSWORD' as const,
            loginContentType: form.loginContentType,
            loginBody: form.loginBody,
            tokenPath: form.tokenPath.trim() || undefined,
            tokenHeader: form.tokenHeader.trim() || undefined,
            // Kept as typed: "Bearer " needs its trailing space.
            tokenPrefix: form.userInfoUrl ? form.tokenPrefix : undefined,
          }
        : {
            mode: 'OAUTH' as const,
            authorizeUrl: form.authorizeUrl,
            clientId: form.clientId,
            clientSecret: form.clientSecret || undefined,
            scope: form.scope || undefined,
          }),
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
          业务系统没有统一登录也可以：选「只有登录接口」，员工在这里输入业务系统的账号密码，业务系统不用改。
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
          ><template #default="{ row }"><span class="sso-url">{{ row.mode === 'PASSWORD' ? `${row.tokenUrl}（账号密码验证）` : row.authorizeUrl }}</span></template></el-table-column
        >
        <el-table-column label="状态" width="120"
          ><template #default="{ row }"
            ><el-tag v-if="row.active" type="success" effect="light">已启用</el-tag
            ><el-tag v-else type="info" effect="light">未启用</el-tag
            ><el-tag v-if="row.mode !== 'PASSWORD' && !row.clientSecretPresent" type="warning" effect="light">缺密钥</el-tag></template
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
        <template v-if="passwordMode">
          <el-form-item label="登录接口地址" required>
            <el-input v-model="form.tokenUrl" placeholder="业务系统自己的登录接口，例如 https://erp.example.com/api/login" />
          </el-form-item>
          <el-form-item label="请求格式">
            <el-radio-group v-model="form.loginContentType">
              <el-radio-button value="JSON">JSON</el-radio-button>
              <el-radio-button value="FORM">表单</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="登录请求内容" required>
            <el-input v-model="form.loginBody" type="textarea" :rows="3" />
            <div class="form-hint">
              照业务系统登录接口的格式写，用 <code v-pre>{{username}}</code>、<code v-pre>{{password}}</code> 代表员工输入的账号和密码；
              系统要求密码先做 MD5 的话用 <code v-pre>{{password_md5}}</code>。
            </div>
          </el-form-item>
          <el-form-item label="登录令牌字段" required>
            <el-input v-model="form.tokenPath" placeholder="登录成功后返回里令牌的位置，例如 data.token 或 data" />
            <div class="form-hint">返回里有这个令牌才算登录成功，所以必须填对。</div>
          </el-form-item>
          <el-form-item label="用户信息地址">
            <el-input v-model="form.userInfoUrl" placeholder="可不填。填了会带着令牌去取员工的 ID 和姓名" />
          </el-form-item>
          <template v-if="form.userInfoUrl">
            <el-form-item label="令牌请求头"><el-input v-model="form.tokenHeader" placeholder="默认 Authorization" /></el-form-item>
            <el-form-item label="令牌前缀"><el-input v-model="form.tokenPrefix" placeholder="例如 Bearer（后面带一个空格），不需要就留空" /></el-form-item>
          </template>
        </template>
        <template v-else>
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
        </template>
        <el-form-item label="用户 ID 字段" :required="!passwordMode">
          <el-input v-model="form.userIdPath" :placeholder="passwordMode ? '例如 data.id；不填就用登录账号当 ID' : '例如 data.id 或 sub'" />
        </el-form-item>
        <el-form-item label="姓名字段"><el-input v-model="form.userNamePath" placeholder="例如 data.nickname 或 name" /></el-form-item>
        <el-form-item label="附加请求头">
          <el-input v-model="form.headers" type="textarea" :rows="2" placeholder="每行一个，例如 tenant-id: 1" />
        </el-form-item>
        <el-form-item label="默认角色">
          <el-input v-model="form.defaultRole" placeholder="可不填，例如 employee" />
          <div class="form-hint">
            员工第一次登录、在这个系统里还没有任何角色时，自动给这个角色。再到「调用权限」里给它配一条规则，所有能登录业务系统的员工就都能用了。
          </div>
        </el-form-item>
        <el-form-item label="调用身份">
          <el-radio-group v-model="form.employeeToken" aria-label="调用业务系统时用谁的身份">
            <el-radio-button value="OFF">共用凭据</el-radio-button>
            <el-radio-button value="PREFERRED">优先员工本人</el-radio-button>
            <el-radio-button value="REQUIRED">只用员工本人</el-radio-button>
          </el-radio-group>
          <div class="form-hint">
            员工从这里登录后，AI 替他调用这个业务系统时用谁的身份。选「员工本人」时用他登录时业务系统给的令牌，业务系统按他自己的权限放行、日志里记的也是他；
            「优先」在令牌过期后改用「接口鉴权」里的共用凭据，「只用」则让员工重新登录。令牌只放在内存里，不落库，最长保留 7 天，服务重启后要重新登录。
            只对「接口鉴权」用 Bearer 令牌或请求头 API Key、并且接口地址与这里的登录地址或用户信息地址是同一个站点（协议、域名、端口都相同）的接口生效。
          </div>
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
