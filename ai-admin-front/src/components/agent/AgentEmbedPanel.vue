<script setup lang="ts">
import { LiquidGlassButton } from '@/components/common/liquid-glass'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import AppDialog from '@/components/common/AppDialog.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import {
  getEmbedReadiness,
  listEmbedCredentialPolicies,
  listEmbedSessions,
  updateEmbedCredentialPolicy,
  type EmbedReadinessView,
  type EmbedSessionView,
} from '@/api/embedOps'
import { copyAiCodingText } from '@/utils/aiCodingClipboard'
import {
  backendSnippet,
  frontendSnippet,
  nextAllowedAgentIds,
  parseOrigins,
  policiesByProject,
  type ProjectEmbedPolicy,
} from './agentEmbed'

const props = defineProps<{
  agentId: string
  agentKeySlug?: string | null
  canManage: boolean
}>()

const agent = computed(() => ({ id: props.agentId, keySlug: props.agentKeySlug }))
const loading = ref(false)
const policies = ref<ProjectEmbedPolicy[]>([])
const sessions = ref<EmbedSessionView[]>([])
const sessionsLoading = ref(false)

const editVisible = ref(false)
const saving = ref(false)
const editing = ref<ProjectEmbedPolicy | null>(null)
const editForm = reactive({ originsText: '', allow: true, tokenTtlSeconds: 600 })
const originErrors = computed(() => parseOrigins(editForm.originsText).invalid)

const check = reactive({ projectCode: '', origin: '' })
const checking = ref(false)
const readiness = ref<EmbedReadinessView | null>(null)

const snippetAgentId = computed(() => props.agentKeySlug || props.agentId)
const reachAiBase = typeof window === 'undefined' ? '' : window.location.origin
const frontCode = computed(() => frontendSnippet({ reachAiBase, agentId: snippetAgentId.value }))
const backCode = backendSnippet()
const embeddable = computed(() => policies.value.filter((policy) => policy.status === 'ACTIVE' && policy.agentAllowed))

async function load() {
  loading.value = true
  try {
    const { data } = await listEmbedCredentialPolicies({ limit: 500 })
    policies.value = policiesByProject(data || [], agent.value)
    if (!check.projectCode) {
      const first = embeddable.value[0] || policies.value[0]
      if (first) {
        check.projectCode = first.projectCode
        check.origin = first.origins.find((origin) => !origin.includes('*')) || ''
      }
    }
  } finally {
    loading.value = false
  }
  void loadSessions()
}

async function loadSessions() {
  sessionsLoading.value = true
  try {
    const ids = [...new Set([props.agentId, props.agentKeySlug].filter(Boolean) as string[])]
    const results = await Promise.all(ids.map((agentId) => listEmbedSessions({ agentId, limit: 50 })))
    sessions.value = results
      .flatMap((result) => result.data || [])
      .sort((left, right) => String(right.createdAt || '').localeCompare(String(left.createdAt || '')))
      .slice(0, 50)
  } finally {
    sessionsLoading.value = false
  }
}

function openEdit(policy: ProjectEmbedPolicy) {
  editing.value = policy
  editForm.originsText = policy.origins.join('\n')
  editForm.allow = policy.agentAllowed
  editForm.tokenTtlSeconds = policy.tokenTtlSeconds
  editVisible.value = true
}

async function saveEdit() {
  const policy = editing.value
  if (!policy) return
  const { origins, invalid } = parseOrigins(editForm.originsText)
  if (invalid.length) {
    ElMessage.warning(`这些来源格式不对：${invalid.join('、')}`)
    return
  }
  const change = nextAllowedAgentIds(policy, agent.value, editForm.allow)
  if (!change.ok) {
    ElMessage.warning(change.reason)
    return
  }
  saving.value = true
  try {
    await updateEmbedCredentialPolicy(policy.id, {
      allowedOrigins: origins,
      allowedAgentIds: change.agentIds,
      tokenTtlSeconds: editForm.tokenTtlSeconds,
      status: policy.status,
    })
    ElMessage.success('嵌入设置已保存')
    editVisible.value = false
    if (check.projectCode === policy.projectCode && !check.origin && origins[0] && !origins[0].includes('*')) {
      check.origin = origins[0]
    }
    await load()
  } finally {
    saving.value = false
  }
}

async function runCheck() {
  if (!check.projectCode || !check.origin.trim()) {
    ElMessage.warning('请选择业务系统并填写业务页面地址')
    return
  }
  checking.value = true
  try {
    const { data } = await getEmbedReadiness({
      projectCode: check.projectCode,
      agentId: snippetAgentId.value,
      origin: check.origin.trim(),
    })
    readiness.value = data
  } finally {
    checking.value = false
  }
}

async function copy(text: string) {
  const result = await copyAiCodingText(text)
  if (result.copied) ElMessage.success('已复制')
  else ElMessage.warning('浏览器不允许自动复制，请手动选中复制')
}

function formatTime(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-'
}

watch(() => props.agentId, () => {
  readiness.value = null
  void load()
})
onMounted(load)
</script>

<template>
  <section class="agent-embed" v-loading="loading">
    <WorkbenchPanel
      title="可以嵌入的业务系统"
      description="终端用户在业务系统页面里通过对话框使用这个 Agent。每个业务系统都有自己的来源白名单，换令牌和浏览器跨域都按它放行。"
      density="compact"
    >
      <template #actions>
        <StatusTag
          :label="embeddable.length ? `可在 ${embeddable.length} 个业务系统嵌入` : '尚未允许任何业务系统'"
          :tone="embeddable.length ? 'success' : 'warning'"
        />
      </template>
      <el-empty v-if="!policies.length && !loading" description="还没有业务系统完成 SDK 接入。先在「项目接入」里接入业务系统。" />
      <el-table v-else :data="policies" class="agent-embed__table">
        <el-table-column label="业务系统" min-width="140">
          <template #default="{ row }"><strong>{{ row.projectCode }}</strong></template>
        </el-table-column>
        <el-table-column label="此 Agent" width="130">
          <template #default="{ row }">
            <StatusTag v-if="row.status !== 'ACTIVE'" label="凭据已停用" tone="neutral" />
            <StatusTag v-else-if="row.agentAllowed" :label="row.allAgents ? '允许（全部 Agent）' : '允许'" tone="success" />
            <StatusTag v-else label="未允许" tone="warning" />
          </template>
        </el-table-column>
        <el-table-column label="来源白名单" min-width="260">
          <template #default="{ row }">
            <span v-if="!row.origins.length" class="agent-embed__muted">为空，仅允许 localhost 调试</span>
            <span v-else class="agent-embed__origins">
              <el-tag v-for="origin in row.origins" :key="origin" size="small" effect="plain">{{ origin }}</el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="令牌有效期" width="110">
          <template #default="{ row }">{{ Math.round(row.tokenTtlSeconds / 60) }} 分钟</template>
        </el-table-column>
        <el-table-column v-if="canManage" label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">设置</el-button>
          </template>
        </el-table-column>
      </el-table>
    </WorkbenchPanel>

    <WorkbenchPanel
      title="检查能否打开"
      description="填写业务页面的地址，逐项检查凭据、来源白名单、Agent 授权和浏览器跨域。"
      density="compact"
    >
      <div class="agent-embed__check">
        <el-select v-model="check.projectCode" placeholder="业务系统" class="agent-embed__check-project">
          <el-option v-for="policy in policies" :key="policy.projectCode" :label="policy.projectCode" :value="policy.projectCode" />
        </el-select>
        <el-input v-model="check.origin" placeholder="https://erp.example.com" class="agent-embed__check-origin" @keyup.enter="runCheck" />
        <el-button type="primary" :loading="checking" @click="runCheck">检查</el-button>
      </div>
      <ul v-if="readiness" class="agent-embed__results">
        <li v-for="item in readiness.checks" :key="item.key" :class="item.ok ? 'is-ok' : 'is-blocked'">
          <span class="agent-embed__result-mark">{{ item.ok ? '✓' : '✗' }}</span>{{ item.message }}
        </li>
        <li :class="readiness.ready ? 'is-ok' : 'is-blocked'" class="agent-embed__result-summary">
          {{ readiness.ready ? `可以在 ${readiness.origin} 打开这个 Agent` : '还不能打开，请先处理上面标 ✗ 的项' }}
        </li>
      </ul>
    </WorkbenchPanel>

    <WorkbenchPanel
      title="接入代码"
      description="交给业务系统的开发人员。浏览器里不需要也不能放项目密钥，令牌由业务后端用当前登录用户换取。"
      density="compact"
    >
      <div class="agent-embed__code">
        <header><span>前端页面</span><el-button link type="primary" @click="copy(frontCode)">复制</el-button></header>
        <pre>{{ frontCode }}</pre>
      </div>
      <div class="agent-embed__code">
        <header><span>业务后端（Spring Boot starter）</span><el-button link type="primary" @click="copy(backCode)">复制</el-button></header>
        <pre>{{ backCode }}</pre>
      </div>
    </WorkbenchPanel>

    <WorkbenchPanel title="最近的嵌入会话" description="最近 50 个通过嵌入式对话发起的会话。" density="compact">
      <template #actions>
        <el-button link :loading="sessionsLoading" @click="loadSessions">刷新</el-button>
      </template>
      <el-empty v-if="!sessions.length && !sessionsLoading" description="还没有用户通过嵌入式对话使用这个 Agent。" />
      <el-table v-else :data="sessions" v-loading="sessionsLoading">
        <el-table-column label="开始时间" width="170"><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="业务系统" prop="projectCode" width="130" />
        <el-table-column label="用户" prop="externalUserId" min-width="120" />
        <el-table-column label="页面" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.origin }}{{ row.route || '' }}</template>
        </el-table-column>
        <el-table-column label="状态" prop="status" width="100" />
        <el-table-column label="操作" width="110" align="center">
          <template #default="{ row }">
            <router-link class="agent-embed__link" :to="{ name: 'RunConversations', query: { sessionId: row.sessionId } }">
              查看对话
            </router-link>
          </template>
        </el-table-column>
      </el-table>
    </WorkbenchPanel>

    <AppDialog v-model="editVisible" :title="`嵌入设置 · ${editing?.projectCode || ''}`" width="560px">
      <el-form label-position="top">
        <el-form-item label="来源白名单（业务系统页面地址，每行一个）">
          <el-input
            v-model="editForm.originsText"
            type="textarea"
            :rows="4"
            placeholder="https://erp.example.com&#10;https://*.example.com"
          />
          <p v-if="originErrors.length" class="agent-embed__error">格式不对：{{ originErrors.join('、') }}</p>
          <p class="agent-embed__muted">只保留协议、域名和端口。白名单为空时只允许 localhost 调试。这里的设置对该业务系统里所有 Agent 生效。</p>
        </el-form-item>
        <el-form-item label="允许在这个业务系统嵌入此 Agent">
          <el-switch v-model="editForm.allow" :disabled="editing?.allAgents" />
          <p v-if="editing?.allAgents" class="agent-embed__muted">该业务系统目前允许嵌入所有 Agent。</p>
        </el-form-item>
        <el-form-item label="令牌有效期（秒）">
          <el-input-number v-model="editForm.tokenTtlSeconds" :min="60" :max="86400" :step="60" />
        </el-form-item>
      </el-form>
      <template #footer>
        <LiquidGlassButton @click="editVisible = false">取消</LiquidGlassButton>
        <LiquidGlassButton type="primary" :loading="saving" :disabled="originErrors.length > 0" @click="saveEdit">保存</LiquidGlassButton>
      </template>
    </AppDialog>
  </section>
</template>

<style scoped>
.agent-embed {
  display: flex;
  flex-direction: column;
  gap: var(--section-gap);
  min-height: 0;
  overflow: auto;
}

.agent-embed__origins {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
}

.agent-embed__link {
  color: var(--el-color-primary);
  text-decoration: none;
}

.agent-embed__muted {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
}

.agent-embed__error {
  margin: 4px 0 0;
  color: var(--el-color-danger);
  font-size: 12px;
}

.agent-embed__check {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.agent-embed__check-project {
  width: 180px;
}

.agent-embed__check-origin {
  flex: 1;
  min-width: 220px;
}

.agent-embed__results {
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.agent-embed__results .is-ok {
  color: var(--el-color-success);
}

.agent-embed__results .is-blocked {
  color: var(--el-color-danger);
}

.agent-embed__result-mark {
  display: inline-block;
  width: 1.4em;
  font-weight: 600;
}

.agent-embed__result-summary {
  margin-top: 4px;
  font-weight: 600;
}

.agent-embed__code + .agent-embed__code {
  margin-top: 12px;
}

.agent-embed__code header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--text-secondary);
  font-size: 13px;
}

.agent-embed__code pre {
  margin: 6px 0 0;
  padding: 12px;
  overflow: auto;
  border-radius: 6px;
  background: var(--code-surface);
  color: var(--code-text);
  font-size: 12px;
  line-height: 1.5;
}
</style>
