<template>
  <WorkbenchPage class="mcp-visibility-board-page" layout="list">
    <PageHeader
      variant="standard"
      domain="governance"
      eyebrow="MCP Governance"
      title="MCP 暴露白名单"
      description="决定哪些 Tool 可被外部 MCP Client 发现。"
    >
      <template #tags>
        <el-tag round effect="plain" type="success">{{ exposedCount }} 已暴露</el-tag>
        <el-tag round effect="plain" type="info">{{ rows.length }} 条规则</el-tag>
      </template>
      <template #actions>
        <el-tooltip content="刷新暴露白名单" placement="top">
          <el-button circle :icon="Refresh" :loading="loading" aria-label="刷新暴露白名单" @click="reload" />
        </el-tooltip>
        <el-button type="primary" :icon="Plus" @click="openAddDialog">新增暴露项</el-button>
      </template>
    </PageHeader>

    <section v-loading="loading" class="visibility-surface glass-surface-panel workbench-list-surface">
      <header class="visibility-surface__header">
        <div class="visibility-heading">
          <div class="visibility-heading__title-row">
            <h2>暴露清单</h2>
            <span class="visibility-heading__count">显示 {{ filteredRows.length }} / {{ rows.length }}</span>
          </div>
          <p>在这里登记的对象才会进入 MCP 的发现范围；关闭开关可立即停止对外发现。</p>
        </div>

        <div class="visibility-filters" aria-label="暴露清单筛选">
          <el-input
            v-model="filterText"
            class="visibility-search"
            placeholder="搜索 Tool 名称"
            clearable
            :prefix-icon="Search"
          />
          <el-radio-group v-model="filterKind" aria-label="按类型筛选">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="TOOL">Tool</el-radio-button>
          </el-radio-group>
        </div>
      </header>

      <div class="policy-strip" role="note" aria-label="MCP 外部调用门禁说明">
        <div class="policy-strip__copy">
          <span class="policy-strip__icon" aria-hidden="true">
            <el-icon><Lock /></el-icon>
          </span>
          <div>
            <strong>暴露不等于可直接调用</strong>
            <p>外部 Client 必须同时通过以下三道门禁。</p>
          </div>
        </div>
        <div class="policy-gates" aria-hidden="true">
          <span class="policy-gate is-current">本页已暴露</span>
          <span class="policy-gate__operator">+</span>
          <span class="policy-gate">Tool ACL 放行</span>
          <span class="policy-gate__operator">+</span>
          <span class="policy-gate">Client 白名单命中</span>
        </div>
      </div>

      <div v-if="filteredRows.length || loading" class="visibility-table-wrap">
        <el-table :data="filteredRows" class="visibility-table" row-key="id" size="default">
          <el-table-column prop="targetKind" label="类型" width="112">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" type="info">
                {{ kindLabel(row.targetKind) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="targetName" label="名称" min-width="280">
            <template #default="{ row }">
              <span class="target-name">{{ row.targetName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="exposed" label="发现状态" width="156">
            <template #default="{ row }">
              <div class="visibility-switch">
                <el-switch
                  :model-value="row.exposed"
                  :loading="updatingKey === rowKey(row)"
                  :aria-label="`${row.targetName} ${row.exposed ? '停止暴露' : '允许暴露'}`"
                  @change="(value: boolean) => handleToggle(row, value)"
                />
                <span :class="{ 'is-exposed': row.exposed }">
                  {{ row.exposed ? '已暴露' : '未暴露' }}
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="审批依据 / 备注" min-width="280">
            <template #default="{ row }">
              <el-input
                v-model="row.note"
                size="small"
                clearable
                placeholder="补充审批单号、用途或责任人"
                @blur="handleNoteUpdate(row)"
              />
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div v-else class="visibility-empty">
        <span class="visibility-empty__icon" aria-hidden="true">
          <el-icon><Lock /></el-icon>
        </span>
        <h3>{{ hasActiveFilters ? '没有匹配的暴露项' : '还没有配置暴露规则' }}</h3>
        <p>
          {{
            hasActiveFilters
              ? '换一个名称或类型筛选，查看其他已登记对象。'
              : '默认不向外部 Client 暴露任何 Tool 或能力，可从第一条规则开始。'
          }}
        </p>
        <el-button v-if="hasActiveFilters" @click="clearFilters">清除筛选</el-button>
        <el-button v-else type="primary" :icon="Plus" @click="openAddDialog">新增第一条规则</el-button>
      </div>

      <footer v-if="rows.length" class="visibility-surface__footer">
        <span>共 {{ rows.length }} 条规则，其中 {{ exposedCount }} 条正在对外暴露</span>
        <span>备注在输入框失焦后自动保存</span>
      </footer>
    </section>

    <AppDialog
      v-model="addDialogOpen"
      title="新增 MCP 暴露项"
      description="登记后将立即允许外部 MCP Client 发现该对象，实际调用仍需通过 Tool ACL 与 Client 白名单。"
      width="520px"
      destroy-on-close
    >
      <el-form label-position="top" :model="addForm" class="add-visibility-form" @submit.prevent="handleAdd">
        <el-form-item label="对象类型" required>
          <el-radio-group v-model="addForm.kind" class="add-kind-selector">
            <el-radio-button value="TOOL">Tool</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input
            v-model="addForm.name"
            autofocus
            clearable
            placeholder="例如 order.query 或 customerProfile"
          />
        </el-form-item>
        <el-form-item label="审批依据 / 备注">
          <el-input
            v-model="addForm.note"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="审批单号、使用场景或责任人（可选）"
          />
        </el-form-item>
        <div class="add-form-hint">
          <el-icon aria-hidden="true"><InfoFilled /></el-icon>
          <span>新增项默认开启“对外暴露”，之后可随时在清单中关闭。</span>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="addDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!addForm.name.trim()" @click="handleAdd">
          添加并暴露
        </el-button>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { InfoFilled, Lock, Plus, Refresh, Search } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'

import { listMcpVisibility, setMcpVisibility } from '@/api/mcp'
import type { McpVisibility } from '@/types/mcp'

const loading = ref(false)
const saving = ref(false)
const updatingKey = ref('')
const rows = ref<McpVisibility[]>([])
const filterText = ref('')
const filterKind = ref<'' | 'TOOL'>('')
const addDialogOpen = ref(false)

const addForm = reactive({
  kind: 'TOOL' as 'TOOL',
  name: '',
  note: '',
})

const filteredRows = computed(() => {
  const query = filterText.value.trim().toLowerCase()
  return rows.value.filter((row) => {
    if (filterKind.value && row.targetKind !== filterKind.value) return false
    if (query && !row.targetName.toLowerCase().includes(query)) return false
    return true
  })
})

const exposedCount = computed(() => rows.value.filter((row) => row.exposed).length)
const hasActiveFilters = computed(() => Boolean(filterText.value.trim() || filterKind.value))

function kindLabel(_kind: McpVisibility['targetKind']) {
  return 'Tool'
}

function rowKey(row: McpVisibility) {
  return `${row.targetKind}:${row.targetName}`
}

function clearFilters() {
  filterText.value = ''
  filterKind.value = ''
}

function openAddDialog() {
  addForm.kind = 'TOOL'
  addForm.name = ''
  addForm.note = ''
  addDialogOpen.value = true
}

async function reload() {
  loading.value = true
  try {
    const { data } = await listMcpVisibility()
    rows.value = data ?? []
  } finally {
    loading.value = false
  }
}

async function handleToggle(row: McpVisibility, exposed: boolean) {
  const key = rowKey(row)
  updatingKey.value = key
  try {
    await setMcpVisibility({
      kind: row.targetKind,
      name: row.targetName,
      exposed,
      note: row.note?.trim() || undefined,
    })
    ElMessage.success(exposed ? '已允许对外发现' : '已停止对外发现')
    await reload()
  } finally {
    if (updatingKey.value === key) updatingKey.value = ''
  }
}

async function handleNoteUpdate(row: McpVisibility) {
  await setMcpVisibility({
    kind: row.targetKind,
    name: row.targetName,
    exposed: row.exposed,
    note: row.note?.trim() || undefined,
  })
}

async function handleAdd() {
  const name = addForm.name.trim()
  if (!name) {
    ElMessage.warning('请输入 Tool 或能力名称')
    return
  }

  saving.value = true
  try {
    await setMcpVisibility({
      kind: addForm.kind,
      name,
      exposed: true,
      note: addForm.note.trim() || undefined,
    })
    ElMessage.success('已添加并允许对外发现')
    addDialogOpen.value = false
    await reload()
  } finally {
    saving.value = false
  }
}

onMounted(reload)
</script>

<style scoped lang="scss">
.visibility-surface {
  display: flex;
  min-height: 460px;
  flex-direction: column;
  overflow: hidden;
  border-radius: var(--radius-lg);
  color: var(--text-primary);
}

.visibility-surface__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--section-gap);
  padding: 18px 20px 16px;
  border-bottom: 1px solid var(--border-divider);
}

.visibility-heading {
  min-width: 0;
}

.visibility-heading__title-row,
.visibility-filters,
.policy-strip,
.policy-strip__copy,
.policy-gates,
.visibility-switch,
.visibility-surface__footer,
.add-form-hint {
  display: flex;
  align-items: center;
}

.visibility-heading__title-row {
  flex-wrap: wrap;
  gap: 10px;
}

.visibility-heading h2,
.visibility-heading p,
.policy-strip p,
.visibility-empty h3,
.visibility-empty p {
  margin: 0;
}

.visibility-heading h2 {
  font-size: 1.0625rem;
  line-height: 1.4;
}

.visibility-heading__count {
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--status-neutral-soft);
  color: var(--text-muted);
  font-size: 0.75rem;
  font-weight: 700;
}

.visibility-heading p {
  margin-top: 4px;
  color: var(--text-secondary);
  font-size: 0.8125rem;
  line-height: 1.5;
}

.visibility-filters {
  min-width: 0;
  flex: 0 1 auto;
  justify-content: flex-end;
  gap: 10px;
}

.visibility-filters :deep(.el-radio-group) {
  flex: 0 0 auto;
  flex-wrap: nowrap;
}

.visibility-search {
  width: min(280px, 34vw);
}

.policy-strip {
  justify-content: space-between;
  gap: 18px;
  margin: 14px 20px 0;
  padding: 11px 13px;
  border: 1px solid color-mix(in srgb, var(--status-warning) 24%, var(--border-subtle));
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--status-warning-soft) 82%, transparent);
}

.policy-strip__copy {
  min-width: 0;
  gap: 10px;
}

.policy-strip__icon,
.visibility-empty__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  border-radius: 12px;
}

.policy-strip__icon {
  width: 34px;
  height: 34px;
  background: color-mix(in srgb, var(--status-warning) 15%, transparent);
  color: var(--status-warning);
  font-size: 1rem;
}

.policy-strip strong {
  display: block;
  font-size: 0.8125rem;
  line-height: 1.4;
}

.policy-strip p {
  margin-top: 2px;
  color: var(--text-secondary);
  font-size: 0.75rem;
  line-height: 1.4;
}

.policy-gates {
  flex: 0 0 auto;
  gap: 7px;
}

.policy-gate {
  padding: 5px 9px;
  border: 1px solid var(--border-readable);
  border-radius: 999px;
  background: var(--surface-solid-control);
  color: var(--text-secondary);
  font-size: 0.75rem;
  font-weight: 650;
  white-space: nowrap;
}

.policy-gate.is-current {
  border-color: color-mix(in srgb, var(--status-warning) 42%, var(--border-readable));
  color: var(--status-warning);
}

.policy-gate__operator {
  color: var(--text-muted);
  font-size: 0.75rem;
  font-weight: 800;
}

.visibility-table-wrap {
  min-height: 0;
  flex: 1 1 auto;
  margin: 14px 20px 0;
  overflow: hidden;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
}

.visibility-table {
  width: 100%;
}

.visibility-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}

.target-name {
  display: inline-flex;
  max-width: 100%;
  overflow: hidden;
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  background: var(--status-neutral-soft);
  color: var(--text-primary);
  font-family: var(--el-font-family-monospace, ui-monospace, SFMono-Regular, Consolas, monospace);
  font-size: 0.8125rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.visibility-switch {
  gap: 9px;
}

.visibility-switch span {
  color: var(--text-muted);
  font-size: 0.75rem;
  font-weight: 650;
}

.visibility-switch span.is-exposed {
  color: var(--status-success);
}

.visibility-empty {
  display: flex;
  min-height: 270px;
  flex: 1 1 auto;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  padding: 36px 20px;
  text-align: center;
}

.visibility-empty__icon {
  width: 54px;
  height: 54px;
  margin-bottom: 14px;
  border: 1px solid color-mix(in srgb, var(--brand-primary) 22%, var(--border-subtle));
  background: color-mix(in srgb, var(--brand-selected-bg) 68%, var(--surface-solid-control));
  color: var(--brand-active);
  box-shadow: var(--inner-highlight);
  font-size: 1.45rem;
}

.visibility-empty h3 {
  font-size: 1rem;
  line-height: 1.5;
}

.visibility-empty p {
  max-width: 480px;
  margin-top: 7px;
  color: var(--text-secondary);
  font-size: 0.8125rem;
  line-height: 1.65;
}

.visibility-empty .el-button {
  margin-top: 18px;
}

.visibility-surface__footer {
  justify-content: space-between;
  gap: 16px;
  padding: 11px 20px 13px;
  color: var(--text-muted);
  font-size: 0.75rem;
}

.add-visibility-form :deep(.el-form-item:last-of-type) {
  margin-bottom: 14px;
}

.add-kind-selector {
  width: 100%;
}

.add-kind-selector :deep(.el-radio-button) {
  flex: 1;
}

.add-kind-selector :deep(.el-radio-button__inner) {
  width: 100%;
}

.add-form-hint {
  gap: 7px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  background: var(--status-info-soft);
  color: var(--text-secondary);
  font-size: 0.75rem;
  line-height: 1.5;
}

.add-form-hint .el-icon {
  flex: 0 0 auto;
  color: var(--status-info);
}

@media (max-width: 1360px) {
  .visibility-surface__header {
    align-items: flex-start;
    flex-direction: column;
  }

  .visibility-filters {
    width: 100%;
    justify-content: space-between;
  }

  .visibility-search {
    width: min(360px, 56vw);
  }
}

@media (max-width: 1080px) {
  .policy-strip {
    align-items: flex-start;
    flex-direction: column;
  }

  .policy-gates {
    flex-wrap: wrap;
    padding-left: 44px;
  }
}

@media (max-width: 720px) {
  .visibility-surface {
    min-height: 0;
  }

  .visibility-surface__header {
    padding: 16px;
  }

  .visibility-filters {
    align-items: stretch;
    flex-direction: column;
  }

  .visibility-search,
  .visibility-filters :deep(.el-radio-group) {
    width: 100%;
  }

  .visibility-filters :deep(.el-radio-button) {
    flex: 1;
  }

  .visibility-filters :deep(.el-radio-button__inner) {
    width: 100%;
  }

  .policy-strip {
    margin: 10px 16px 0;
  }

  .policy-gates {
    padding-left: 0;
  }

  .visibility-table-wrap {
    margin: 10px 16px 0;
    overflow-x: auto;
  }

  .visibility-table {
    min-width: 760px;
  }

  .visibility-surface__footer {
    align-items: flex-start;
    flex-direction: column;
    padding-inline: 16px;
  }
}
</style>
