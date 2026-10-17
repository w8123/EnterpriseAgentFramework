<template>
  <WorkbenchPage class="tool-acl-list-page" layout="list">
    <PageHeader
      variant="standard"
      domain="governance"
      eyebrow="Runtime Governance"
      title="调用权限（角色 × 能力）"
      description="以角色为入口管理 AI 调用目标的允许、拒绝规则，并在发布前完成权限诊断。"
    >
      <template #actions>
        <el-tooltip content="批量授权" placement="top">
          <el-button circle :icon="Magnet" aria-label="批量授权" @click="openBatchDialog" />
        </el-tooltip>
        <el-tooltip content="诊断" placement="top">
          <el-button circle :icon="MagicStick" aria-label="ACL 决策诊断" @click="openExplainDialog" />
        </el-tooltip>
        <el-tooltip content="刷新" placement="top">
          <el-button
            circle
            :icon="Refresh"
            :loading="loading"
            aria-label="刷新 ACL 规则"
            @click="reload"
          />
        </el-tooltip>
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">新建规则</el-button>
      </template>
    </PageHeader>

    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="决策规则：DENY 优先；无命中默认拒绝"
      description="角色为空时 ACL 诊断返回 SKIPPED，该结果不表示允许调用；MCP 与 Console 等严格入口仅接受 ALLOW，并从可信身份取得角色。平台范围规则适用于所有项目，项目规则仅匹配指定项目。能力名 * 代表通配，类型 ALL 表示全部调用目标。"
    />

    <div class="acl-body workbench-list-surface">
      <!-- 左栏：角色 -->
      <aside class="role-panel">
        <div class="role-title">
          角色 <el-tag size="small" type="info">{{ roles.length }}</el-tag>
        </div>
        <el-input
          v-model="roleFilter"
          size="small"
          placeholder="过滤角色"
          clearable
          :prefix-icon="Search"
          style="margin-bottom: 8px"
        />
        <el-scrollbar max-height="70vh">
          <div
            class="role-item"
            :class="{ active: selectedRole === '' }"
            @click="selectRole('')"
          >
            <span>全部</span>
            <el-tag size="small" type="info">{{ totalCount }}</el-tag>
          </div>
          <div
            v-for="r in filteredRoles"
            :key="r"
            class="role-item"
            :class="{ active: selectedRole === r }"
            @click="selectRole(r)"
          >
            <span>{{ r }}</span>
            <el-tag size="small">{{ roleRuleCounts[r] ?? 0 }}</el-tag>
          </div>
        </el-scrollbar>
      </aside>

      <!-- 右栏：规则表 -->
      <section class="rule-panel">
        <el-card shadow="never">
          <el-table :data="rules" v-loading="loading" stripe size="default">
            <el-table-column prop="roleCode" label="角色" min-width="140" />
            <el-table-column label="项目范围" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ projectScopeLabel(row) }}</template>
            </el-table-column>
            <el-table-column prop="targetKind" label="类型" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="kindTagType(row.targetKind)">{{ row.targetKind }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="targetName" label="能力" min-width="180">
              <template #default="{ row }">
                <span v-if="row.targetName === '*'" class="wildcard">*（全部）</span>
                <code v-else>{{ row.targetName }}</code>
              </template>
            </el-table-column>
            <el-table-column prop="permission" label="决策" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.permission === 'ALLOW' ? 'success' : 'danger'">
                  {{ row.permission }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="enabled" label="启用" width="90">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.enabled !== false"
                  @change="(v: boolean) => handleToggle(row, v)"
                />
              </template>
            </el-table-column>
            <el-table-column prop="note" label="备注" min-width="180" show-overflow-tooltip />
            <el-table-column prop="updatedAt" label="更新时间" width="170" />
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button size="small" link @click.stop="openEditDialog(row)">编辑</el-button>
                <el-popconfirm
                  :title="`确认删除规则 #${row.id}？`"
                  @confirm="handleDelete(row.id)"
                >
                  <template #reference>
                    <el-button size="small" link type="danger" @click.stop>删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-model:current-page="pagination.current"
            v-model:page-size="pagination.size"
            :total="pagination.total"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="reload"
            @size-change="reload"
            style="margin-top: 12px; justify-content: flex-end"
          />
        </el-card>
      </section>
    </div>

    <!-- 新建 / 编辑弹窗 -->
    <AppDialog v-model="editDialogOpen" :title="editing?.id ? `编辑规则 #${editing.id}` : '新建规则'" width="520px">
      <el-form :model="editing" label-width="90px" v-if="editing">
        <ToolAclProjectScopeField :model-value="editing" :disabled="saving" @update:model-value="setEditingScope" />
        <el-form-item label="角色" required>
          <el-input v-model="editing.roleCode" placeholder="PLATFORM_ADMIN / ops / customer-service" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="editing.targetKind">
            <el-radio-button value="TOOL">TOOL</el-radio-button>
            <el-radio-button value="ALL">ALL</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="能力" required>
          <el-input v-model="editing.targetName" placeholder="调用目标名，或 * 表示通配" />
        </el-form-item>
        <el-form-item label="决策" required>
          <el-radio-group v-model="editing.permission">
            <el-radio-button value="ALLOW">ALLOW</el-radio-button>
            <el-radio-button value="DENY">DENY</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editing.enabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editing.note" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </AppDialog>

    <!-- 批量授权 -->
    <AppDialog v-model="batchDialogOpen" title="批量授权" width="600px">
      <el-form :model="batchForm" label-width="90px">
        <ToolAclProjectScopeField :model-value="batchForm" :disabled="saving" @update:model-value="setBatchScope" />
        <el-form-item label="角色" required>
          <el-input v-model="batchForm.roleCode" placeholder="一次授权给一个角色" />
        </el-form-item>
        <el-form-item label="决策" required>
          <el-radio-group v-model="batchForm.permission">
            <el-radio-button value="ALLOW">ALLOW</el-radio-button>
            <el-radio-button value="DENY">DENY</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="TOOL 列表">
          <el-input
            v-model="batchForm.toolsRaw"
            type="textarea"
            :rows="3"
            placeholder="每行一个 tool 名，留空则跳过"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="batchForm.note" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleBatchGrant">提交</el-button>
      </template>
    </AppDialog>

    <!-- 诊断：给 roles + targets 查看决策 -->
    <AppDialog v-model="explainDialogOpen" title="ACL 决策诊断" width="640px">
      <el-form :model="explainForm" label-width="90px">
        <ToolAclProjectScopeField :model-value="explainForm" :disabled="explaining" @update:model-value="setExplainScope" />
        <el-form-item label="roles" required>
          <el-select v-model="explainForm.roles" multiple filterable allow-create placeholder="选择或输入角色">
            <el-option v-for="r in roles" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item label="targets">
          <el-input
            v-model="explainForm.targetsRaw"
            type="textarea"
            :rows="3"
            placeholder="每行 `kind:name`，例如 `TOOL:delete_order`"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="explaining" @click="handleExplain">查询决策</el-button>
        </el-form-item>
      </el-form>

      <el-table v-if="explainResult.length" :data="explainResult" size="small" stripe>
        <el-table-column prop="kind" label="类型" width="100" />
        <el-table-column prop="name" label="能力" />
        <el-table-column prop="decision" label="决策">
          <template #default="{ row }">
            <el-tag size="small" :type="decisionTag(row.decision)">{{ row.decision }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Search, Magnet, MagicStick } from '@element-plus/icons-vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import ToolAclProjectScopeField from './components/ToolAclProjectScopeField.vue'
import { useProjectStore } from '@/store/project'
import { usePageProjectScope } from '@/composables/usePageProjectScope'

import {
  createToolAcl,
  deleteToolAcl,
  explainToolAcl,
  grantToolAclBatch,
  listToolAcl,
  listToolAclRoles,
  toggleToolAcl,
  updateToolAcl,
} from '@/api/toolAcl'
import type {
  ToolAclDecision,
  ToolAclPermission,
  ToolAclProjectScope,
  ToolAclRule,
  ToolAclTargetKind,
  ToolAclTargetRef,
} from '@/types/toolAcl'

const loading = ref(false)
const saving = ref(false)
const explaining = ref(false)
const projectStore = useProjectStore()
const pageProjectScope = usePageProjectScope()

const rules = ref<ToolAclRule[]>([])
const roles = ref<string[]>([])
const roleRuleCounts = ref<Record<string, number>>({})
const totalCount = ref(0)

const selectedRole = ref('')
const roleFilter = ref('')

const pagination = reactive({ current: 1, size: 20, total: 0 })

const editDialogOpen = ref(false)
const editing = ref<Partial<ToolAclRule> | null>(null)

const batchDialogOpen = ref(false)
const batchForm = reactive({
  projectId: undefined as number | null | undefined,
  projectCode: undefined as string | null | undefined,
  roleCode: '',
  permission: 'ALLOW' as ToolAclPermission,
  toolsRaw: '',
  note: '',
})

const explainDialogOpen = ref(false)
const explainForm = reactive({
  projectId: undefined as number | null | undefined,
  projectCode: undefined as string | null | undefined,
  roles: [] as string[],
  targetsRaw: '',
})
const explainResult = ref<{ kind: string; name: string; decision: ToolAclDecision }[]>([])
let explainRevision = 0

function invalidateExplain() {
  explainRevision += 1
  explainResult.value = []
  explaining.value = false
}

watch([
  () => explainForm.projectId,
  () => explainForm.projectCode,
  () => [...explainForm.roles],
  () => explainForm.targetsRaw,
  () => explainDialogOpen.value,
], invalidateExplain, { flush: 'sync' })

onBeforeUnmount(invalidateExplain)

const filteredRoles = computed(() => {
  const f = roleFilter.value.trim().toLowerCase()
  if (!f) return roles.value
  return roles.value.filter((r) => r.toLowerCase().includes(f))
})

function kindTagType(kind: ToolAclTargetKind) {
  if (kind === 'ALL') return 'info'
  return 'primary'
}

function decisionTag(d: ToolAclDecision) {
  if (d === 'ALLOW') return 'success'
  if (d === 'SKIPPED') return 'info'
  return 'danger'
}

async function reload() {
  loading.value = true
  try {
    const { data } = await listToolAcl({
      current: pagination.current,
      size: pagination.size,
      roleCode: selectedRole.value || undefined,
    })
    rules.value = data?.records ?? []
    pagination.total = data?.total ?? 0
    if (!selectedRole.value) {
      totalCount.value = pagination.total
    }
  } catch {
    rules.value = []
  } finally {
    loading.value = false
  }
}

async function reloadRoles() {
  try {
    const { data } = await listToolAclRoles()
    roles.value = data ?? []
  } catch {
    roles.value = []
  }
  // 顺便做一次"全量数据 → role 分布"近似统计：不再调一次接口，用 roles 长度占位
  // 精确计数需要遍历规则列表，这里权衡前端复杂度后只统计首页的近似值
  roleRuleCounts.value = {}
  for (const r of roles.value) {
    roleRuleCounts.value[r] = 0
  }
  for (const rule of rules.value) {
    roleRuleCounts.value[rule.roleCode] = (roleRuleCounts.value[rule.roleCode] ?? 0) + 1
  }
}

function selectRole(r: string) {
  selectedRole.value = r
  pagination.current = 1
  reload()
}

function defaultProjectScope(): ToolAclProjectScope {
  if (pageProjectScope?.isActive.value) {
    const current = pageProjectScope.requestParams.value
    return { projectId: current?.projectId, projectCode: current?.projectId ? current.projectCode || null : undefined }
  }
  const project = projectStore.currentProject
  return { projectId: project?.id, projectCode: project ? project.projectCode?.trim() || null : undefined }
}

function hasSelectedScope(scope: ToolAclProjectScope) {
  return scope.projectId != null || Boolean(scope.projectCode?.trim())
    || (scope.projectId === null && scope.projectCode === null)
}

function projectScopeLabel(scope: ToolAclProjectScope) {
  if (scope.projectId == null && !scope.projectCode) return '平台范围（所有项目）'
  const project = projectStore.projects.find(project => scope.projectId != null
    ? project.id === scope.projectId : project.projectCode === scope.projectCode)
  return project ? projectStore.projectLabel(project) : scope.projectCode || `项目 #${scope.projectId}`
}

function setEditingScope(scope: ToolAclProjectScope) {
  if (editing.value) Object.assign(editing.value, scope)
}

function setBatchScope(scope: ToolAclProjectScope) {
  Object.assign(batchForm, scope)
}

function setExplainScope(scope: ToolAclProjectScope) {
  Object.assign(explainForm, scope)
}

function openCreateDialog() {
  editing.value = {
    ...defaultProjectScope(),
    roleCode: selectedRole.value || '',
    targetKind: 'TOOL',
    targetName: '',
    permission: 'ALLOW',
    enabled: true,
    note: '',
  }
  editDialogOpen.value = true
}

function openEditDialog(row: ToolAclRule) {
  editing.value = { ...row, projectId: row.projectId ?? null, projectCode: row.projectCode ?? null }
  editDialogOpen.value = true
}

async function handleSave() {
  if (!editing.value || saving.value) return
  if (!hasSelectedScope(editing.value)) {
    ElMessage.warning('请选择项目范围')
    return
  }
  if (!editing.value.roleCode || !editing.value.targetName) {
    ElMessage.warning('请填写角色与能力')
    return
  }
  saving.value = true
  try {
    if (editing.value.id) {
      await updateToolAcl(editing.value.id, editing.value)
      ElMessage.success('已更新')
    } else {
      await createToolAcl(editing.value)
      ElMessage.success('已创建')
    }
    editDialogOpen.value = false
    await reload()
    await reloadRoles()
  } catch (err) {
    ElMessage.error('保存失败：' + (err as Error).message)
  } finally {
    saving.value = false
  }
}

async function handleToggle(row: ToolAclRule, enabled: boolean) {
  try {
    await toggleToolAcl(row.id, enabled)
    row.enabled = enabled
    ElMessage.success(enabled ? '已启用' : '已停用')
  } catch {
    // noop
  }
}

async function handleDelete(id: number) {
  try {
    await deleteToolAcl(id)
    ElMessage.success('已删除')
    await reload()
    await reloadRoles()
  } catch {
    // noop
  }
}

function parseLines(raw: string, kind: ToolAclTargetKind): ToolAclTargetRef[] {
  return raw
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => !!l)
    .map((name) => ({ kind, name }))
}

function openBatchDialog() {
  Object.assign(batchForm, defaultProjectScope())
  batchForm.roleCode = selectedRole.value || ''
  batchForm.permission = 'ALLOW'
  batchForm.toolsRaw = ''
  batchForm.note = ''
  batchDialogOpen.value = true
}

async function handleBatchGrant() {
  if (saving.value) return
  if (!hasSelectedScope(batchForm)) {
    ElMessage.warning('请选择项目范围')
    return
  }
  if (!batchForm.roleCode) {
    ElMessage.warning('请填写 role')
    return
  }
  const targets: ToolAclTargetRef[] = [
    ...parseLines(batchForm.toolsRaw, 'TOOL'),
  ]
  if (targets.length === 0) {
    ElMessage.warning('请至少填写一个 tool 条目')
    return
  }
  saving.value = true
  try {
    const { data } = await grantToolAclBatch({
      projectId: batchForm.projectId,
      projectCode: batchForm.projectCode,
      roleCode: batchForm.roleCode,
      permission: batchForm.permission,
      targets,
      note: batchForm.note,
    })
    ElMessage.success(`已处理 ${data?.count ?? targets.length} 条`)
    batchDialogOpen.value = false
    await reload()
    await reloadRoles()
  } catch (err) {
    ElMessage.error('批量授权失败：' + (err as Error).message)
  } finally {
    saving.value = false
  }
}

function openExplainDialog() {
  invalidateExplain()
  Object.assign(explainForm, defaultProjectScope())
  explainForm.roles = selectedRole.value ? [selectedRole.value] : []
  explainForm.targetsRaw = ''
  explainDialogOpen.value = true
}

async function handleExplain() {
  if (!explainDialogOpen.value || explaining.value) return
  if (!hasSelectedScope(explainForm)) {
    ElMessage.warning('请选择项目范围')
    return
  }
  if (!explainForm.roles.length) {
    ElMessage.warning('请选择至少一个 role')
    return
  }
  const targets: ToolAclTargetRef[] = explainForm.targetsRaw
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => !!l)
    .map((line) => {
      const separator = line.indexOf(':')
      const kind = (separator < 0 ? 'TOOL' : line.slice(0, separator)).trim()
      const name = (separator < 0 ? line : line.slice(separator + 1)).trim()
      return {
        kind: (kind?.toUpperCase() as ToolAclTargetKind) || 'TOOL',
        name: name || kind,
      }
    })
  if (!targets.length) {
    ElMessage.warning('请填写至少一条 target')
    return
  }
  const requestRevision = ++explainRevision
  explainResult.value = []
  explaining.value = true
  try {
    const { data } = await explainToolAcl({ roles: [...explainForm.roles], targets,
      projectId: explainForm.projectId, projectCode: explainForm.projectCode })
    if (requestRevision !== explainRevision) return
    explainResult.value = targets.map((t) => ({
      kind: t.kind,
      name: t.name,
      decision: (data?.[t.name] ?? 'DENY_NO_MATCH') as ToolAclDecision,
    }))
  } catch (err) {
    if (requestRevision === explainRevision) ElMessage.error('诊断失败：' + (err as Error).message)
  } finally {
    if (requestRevision === explainRevision) explaining.value = false
  }
}

onMounted(async () => {
  await reload()
  await reloadRoles()
})
</script>

<style scoped lang="scss">
.acl-body {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 16px;
}

.role-panel {
  background: #fff;
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 4px;
  padding: 12px;

  .role-title {
    font-weight: 600;
    margin-bottom: 8px;
    display: flex;
    justify-content: space-between;
  }

  .role-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 6px 10px;
    border-radius: 4px;
    cursor: pointer;
    margin-bottom: 2px;

    &:hover {
      background: #f5f7fa;
    }

    &.active {
      background: #ecf5ff;
      color: #409eff;
    }
  }
}

.rule-panel {
  min-width: 0;
}

.wildcard {
  color: #f56c6c;
  font-weight: 500;
}

code {
  background: #f4f4f5;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
}

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .role-panel {
    border: 1px solid #ebeef5;
  }
}
</style>
