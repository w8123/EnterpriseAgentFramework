<template>
  <div class="page-container">
    <div class="page-header">
      <h2>MCP Client 凭证</h2>
      <div class="header-actions">
        <el-button type="primary" :icon="Plus" @click="openCreate">新建 Client</el-button>
        <el-button :icon="Refresh" @click="reload" :loading="loading">刷新</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="API Key 仅在创建后明文显示一次；请立即复制保存"
      description="DB 仅保存 SHA-256 哈希。Client 调用时通过 Authorization: Bearer <apiKey> 传入。"
      style="margin-bottom: 12px"
    />

    <el-card shadow="never">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="apiKeyPrefix" label="API Key" width="140">
          <template #default="{ row }">
            <code>{{ row.apiKeyPrefix }}…</code>
          </template>
        </el-table-column>
        <el-table-column label="角色" min-width="200">
          <template #default="{ row }">
            <el-tag v-for="r in parseList(row.rolesJson)" :key="r" size="small" style="margin-right: 4px">
              {{ r }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="工具白名单" min-width="200">
          <template #default="{ row }">
            <el-tag
              v-for="t in parseList(row.toolWhitelistJson)"
              :key="t"
              size="small"
              type="info"
              style="margin-right: 4px"
            >
              {{ t }}
            </el-tag>
            <span v-if="!parseList(row.toolWhitelistJson).length" class="dim">不限（按 ACL 决策）</span>
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="启用" width="80">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled !== false"
              @change="(v: boolean) => handleToggle(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="expiresAt" label="过期" width="180" />
        <el-table-column prop="lastUsedAt" label="上次调用" width="180" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link @click="openEdit(row)">编辑</el-button>
            <el-popconfirm :title="`确认删除 ${row.name}？`" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button size="small" link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 创建/编辑 -->
    <el-dialog v-model="dialogOpen" :title="editing?.id ? `编辑 ${editing.name}` : '新建 MCP Client'" width="540px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：Cursor (个人开发) / Dify SaaS" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roles" multiple filterable allow-create placeholder="如 admin / readonly">
            <el-option v-for="r in roleSuggestions" :key="r" :value="r" :label="r" />
          </el-select>
        </el-form-item>
        <el-form-item label="工具白名单">
          <el-select v-model="form.toolWhitelist" multiple filterable allow-create placeholder="留空 = 不限">
            <el-option v-for="t in toolSuggestions" :key="t" :value="t" :label="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker v-model="form.expiresAt" type="datetime" placeholder="留空 = 永不过期" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 创建后展示明文 API Key -->
    <el-dialog v-model="apiKeyDialogOpen" title="API Key 已生成" width="600px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" show-icon>
        请立即复制并保存以下 API Key，关闭后将无法再次查看。
      </el-alert>
      <div class="apikey-box">
        <code>{{ generatedApiKey }}</code>
        <el-button type="primary" :icon="DocumentCopy" @click="copyKey">复制</el-button>
      </div>
      <h4>Cursor 接入示例（~/.cursor/mcp.json）</h4>
      <pre>{{ cursorExample }}</pre>
      <template #footer>
        <el-button type="primary" @click="apiKeyDialogOpen = false">已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentCopy, Plus, Refresh } from '@element-plus/icons-vue'

import {
  createMcpClient,
  deleteMcpClient,
  listMcpClients,
  listMcpVisibility,
  updateMcpClient,
} from '@/api/mcp'
import { listToolAclRoles } from '@/api/toolAcl'
import type { McpClient } from '@/types/mcp'

const loading = ref(false)
const saving = ref(false)
const rows = ref<McpClient[]>([])

const dialogOpen = ref(false)
const editing = ref<McpClient | null>(null)
const form = reactive({
  name: '',
  roles: [] as string[],
  toolWhitelist: [] as string[],
  expiresAt: null as string | null,
})

const roleSuggestions = ref<string[]>([])
const toolSuggestions = ref<string[]>([])

const apiKeyDialogOpen = ref(false)
const generatedApiKey = ref('')

const cursorExample = computed(() => `{
  "mcpServers": {
    "enterprise-agent-framework": {
      "url": "${window.location.origin}/mcp/jsonrpc",
      "headers": { "Authorization": "Bearer ${generatedApiKey.value}" }
    }
  }
}`)

function parseList(json?: string): string[] {
  if (!json) return []
  try { const arr = JSON.parse(json); return Array.isArray(arr) ? arr : [] } catch { return [] }
}

async function reload() {
  loading.value = true
  try {
    const [clients, vis, roles] = await Promise.all([
      listMcpClients(),
      listMcpVisibility(),
      listToolAclRoles(),
    ])
    rows.value = clients.data ?? []
    toolSuggestions.value = (vis.data ?? []).filter((v) => v.exposed).map((v) => v.targetName)
    roleSuggestions.value = roles.data ?? []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.name = ''
  form.roles = []
  form.toolWhitelist = []
  form.expiresAt = null
  dialogOpen.value = true
}

function openEdit(row: McpClient) {
  editing.value = row
  form.name = row.name
  form.roles = parseList(row.rolesJson)
  form.toolWhitelist = parseList(row.toolWhitelistJson)
  form.expiresAt = row.expiresAt ?? null
  dialogOpen.value = true
}

async function handleSave() {
  if (!form.name.trim()) {
    ElMessage.warning('名称必填')
    return
  }
  saving.value = true
  try {
    if (editing.value && editing.value.id) {
      await updateMcpClient(editing.value.id, {
        name: form.name,
        roles: form.roles,
        toolWhitelist: form.toolWhitelist,
        expiresAt: form.expiresAt,
      })
      ElMessage.success('已保存')
    } else {
      const { data } = await createMcpClient({
        name: form.name,
        roles: form.roles,
        toolWhitelist: form.toolWhitelist,
        expiresAt: form.expiresAt,
      })
      generatedApiKey.value = data.plaintextApiKey
      apiKeyDialogOpen.value = true
    }
    dialogOpen.value = false
    await reload()
  } finally {
    saving.value = false
  }
}

async function handleDelete(id?: number) {
  if (!id) return
  await deleteMcpClient(id)
  ElMessage.success('已删除')
  await reload()
}

async function handleToggle(row: McpClient, enabled: boolean) {
  if (!row.id) return
  await updateMcpClient(row.id, { enabled })
  await reload()
}

function copyKey() {
  navigator.clipboard.writeText(generatedApiKey.value)
  ElMessage.success('已复制')
}

onMounted(reload)
</script>

<style scoped lang="scss">
.page-container { padding: 16px; }
.page-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;
  h2 { margin: 0; font-size: 18px; }
}
.header-actions { display: flex; gap: 8px; }
.dim { color: #999; font-size: 12px; }
.apikey-box {
  display: flex; gap: 8px; align-items: center;
  margin: 12px 0;
  background: #f5f7fa;
  padding: 12px;
  border-radius: 6px;
  code { flex: 1; word-break: break-all; user-select: all; }
}
pre { background: #2b2d3a; color: #fff; padding: 12px; border-radius: 6px; overflow: auto; }

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .dim {
    color: #c0c4cc;
  }

  pre {
    background: #f5f7fa;
    color: #1e293b;
    border: 1px solid #ebeef5;
  }
}
</style>
