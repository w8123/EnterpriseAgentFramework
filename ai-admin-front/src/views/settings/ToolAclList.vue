<template>
  <LiquidGlassPage class="tool-acl-list-page">
    <PageHeader
      variant="standard"
      presentation="plain"
      action-presentation="inline"
      domain="governance"
      title="调用权限"
      description="控制业务系统里的哪些角色，可以通过 AI 调用哪些能力。"
    >
      <template #actions>
        <LiquidGlassButton source="wallpaper" :icon="MagicStick" aria-label="权限检查" @click="openExplainDialog">权限检查</LiquidGlassButton>
        <LiquidGlassButton source="wallpaper" :icon="Files" aria-label="批量授权" @click="openBatchDialog">批量授权</LiquidGlassButton>
        <el-tooltip content="刷新" placement="top">
          <LiquidGlassButton
            source="wallpaper"
            circle
            :icon="Refresh"
            :loading="loading"
            aria-label="刷新调用权限规则"
            @click="reloadAll"
          />
        </el-tooltip>
        <LiquidGlassButton source="wallpaper" type="primary" :icon="Plus" @click="openCreateDialog">新建规则</LiquidGlassButton>
      </template>
    </PageHeader>

    <LiquidGlassNotice source="wallpaper" title="拒绝优先：没有匹配到任何规则时，默认拒绝调用">
      同一角色同时命中「允许」和「拒绝」时按拒绝处理。平台范围的规则对所有项目生效，项目规则只对所选项目生效。
    </LiquidGlassNotice>

    <div class="acl-body workbench-list-surface">
      <!-- 左栏：角色 -->
      <LiquidGlassCard as="div" source="wallpaper" class="role-panel" role="navigation" aria-label="按角色筛选">
        <div class="role-title">业务角色</div>
        <LiquidGlassInput
          v-model="roleFilter"
          class="role-filter"
          placeholder="搜索角色"
          aria-label="搜索角色"
          clearable
          :prefix-icon="Search"
        />
        <el-scrollbar class="role-scroll">
          <button
            type="button"
            class="role-item"
            :class="{ active: selectedRole === '' }"
            :aria-pressed="selectedRole === ''"
            @click="selectRole('')"
          >
            <span class="role-item__text"><span class="role-item__label">全部角色</span></span>
            <span class="role-item__count">{{ totalCount }}</span>
          </button>
          <button
            v-for="r in filteredRoles"
            :key="r"
            type="button"
            class="role-item"
            :class="{ active: selectedRole === r }"
            :aria-pressed="selectedRole === r"
            :title="r"
            @click="selectRole(r)"
          >
            <span class="role-item__text">
              <span class="role-item__label">{{ toolAclRoleDisplay(r).label }}</span>
              <span v-if="toolAclRoleDisplay(r).code" class="role-item__code">{{ toolAclRoleDisplay(r).code }}</span>
            </span>
            <span class="role-item__count">{{ roleRuleCounts[r] ?? '–' }}</span>
          </button>
        </el-scrollbar>
      </LiquidGlassCard>

      <!-- 右栏：规则表 -->
      <LiquidGlassTable
        v-model:current-page="pagination.current"
        v-model:page-size="pagination.size"
        class="rule-panel"
        :loading="loading"
        :total="pagination.total"
        :page-sizes="[20, 50, 100]"
        pagination-label="调用权限规则分页"
        @change="reload"
      >
          <el-table :data="rules" size="default" row-key="id">
            <el-table-column label="角色" min-width="130">
              <template #default="{ row }">
                <div class="cell-stack" :title="row.roleCode">
                  <span class="cell-main">{{ toolAclRoleDisplay(row.roleCode).label }}</span>
                  <span v-if="toolAclRoleDisplay(row.roleCode).code" class="cell-sub">{{ toolAclRoleDisplay(row.roleCode).code }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="能力" min-width="190">
              <template #default="{ row }">
                <div class="cell-stack" :title="row.targetName">
                  <span class="cell-main" :class="{ wildcard: targetDisplay(row).wildcard }">{{ targetDisplay(row).label }}</span>
                  <span v-if="targetDisplay(row).hint" class="cell-sub">{{ targetDisplay(row).hint }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="适用范围" min-width="110" show-overflow-tooltip>
              <template #default="{ row }">{{ projectScopeLabel(row) }}</template>
            </el-table-column>
            <el-table-column label="决策" width="72">
              <template #default="{ row }">
                <el-tag size="small" :type="row.permission === 'ALLOW' ? 'success' : 'danger'">
                  {{ TOOL_ACL_PERMISSION_LABELS[row.permission as ToolAclPermission] ?? row.permission }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="76">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.enabled !== false"
                  :aria-label="`启用规则 #${row.id}`"
                  @change="(v: boolean) => handleToggle(row, v)"
                />
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="130">
              <template #default="{ row }">
                <div class="cell-stack">
                  <span class="cell-main cell-note" :title="row.note || ''">{{ row.note || '—' }}</span>
                  <span v-if="row.updatedAt" class="cell-sub">更新于 {{ formatDateTime(row.updatedAt).slice(0, 10) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="104" class-name="acl-actions">
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
      </LiquidGlassTable>
    </div>

    <!-- 新建 / 编辑弹窗 -->
    <AppDialog v-model="editDialogOpen" :title="editing?.id ? `编辑规则 #${editing.id}` : '新建规则'" width="540px">
      <el-form :model="editing" label-width="90px" v-if="editing">
        <ToolAclProjectScopeField :model-value="editing" :disabled="saving" @update:model-value="setEditingScope" />
        <el-form-item label="角色" required>
          <el-input v-model="editing.roleCode" placeholder="业务系统传入的角色编码，如 sales_manager" />
        </el-form-item>
        <el-form-item label="能力范围" required>
          <el-radio-group :model-value="editingTargetMode" @update:model-value="setEditingTargetMode">
            <el-radio-button value="one">指定能力</el-radio-button>
            <el-radio-button value="all">全部能力</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="editingTargetMode === 'one'" label="能力标识" required>
          <el-input v-model="editing.targetName" placeholder="能力的唯一标识，可在业务能力或 API 详情页复制" />
        </el-form-item>
        <el-form-item label="决策" required>
          <el-radio-group v-model="editing.permission">
            <el-radio-button value="ALLOW">允许</el-radio-button>
            <el-radio-button value="DENY">拒绝</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editing.enabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editing.note" type="textarea" :rows="2" placeholder="选填，说明为什么这样授权" />
        </el-form-item>
      </el-form>
      <template #footer>
        <LiquidGlassButton @click="editDialogOpen = false">取消</LiquidGlassButton>
        <LiquidGlassButton type="primary" :loading="saving" @click="handleSave">保存</LiquidGlassButton>
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
            <el-radio-button value="ALLOW">允许</el-radio-button>
            <el-radio-button value="DENY">拒绝</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="能力列表" required>
          <el-input
            v-model="batchForm.toolsRaw"
            type="textarea"
            :rows="4"
            placeholder="每行一个能力标识"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="batchForm.note" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <LiquidGlassButton @click="batchDialogOpen = false">取消</LiquidGlassButton>
        <LiquidGlassButton type="primary" :loading="saving" @click="handleBatchGrant">提交</LiquidGlassButton>
      </template>
    </AppDialog>

    <!-- 权限检查：给定角色和能力，看最终能不能调用 -->
    <AppDialog v-model="explainDialogOpen" title="权限检查" width="640px">
      <p class="dialog-lead">输入角色和能力，查看按当前规则能否调用。</p>
      <el-form :model="explainForm" label-width="90px">
        <ToolAclProjectScopeField :model-value="explainForm" :disabled="explaining" @update:model-value="setExplainScope" />
        <el-form-item label="角色" required>
          <el-select v-model="explainForm.roles" multiple filterable allow-create placeholder="选择或输入角色">
            <el-option v-for="r in roles" :key="r" :label="roleOptionLabel(r)" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item label="能力" required>
          <el-input
            v-model="explainForm.targetsRaw"
            type="textarea"
            :rows="3"
            placeholder="每行一个能力标识，例如 orders.search"
          />
        </el-form-item>
        <el-form-item>
          <LiquidGlassButton type="primary" :loading="explaining" @click="handleExplain">开始检查</LiquidGlassButton>
        </el-form-item>
      </el-form>

      <el-table v-if="explainResult.length" :data="explainResult" size="small">
        <el-table-column label="能力" min-width="220">
          <template #default="{ row }">
            <div class="cell-stack" :title="row.name">
              <span class="cell-main">{{ targetDisplay({ targetKind: row.kind, targetName: row.name }).label }}</span>
              <span v-if="targetDisplay({ targetKind: row.kind, targetName: row.name }).hint" class="cell-sub">
                {{ targetDisplay({ targetKind: row.kind, targetName: row.name }).hint }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="结果" min-width="180">
          <template #default="{ row }">
            <el-tag size="small" :type="decisionTag(row.decision)">{{ TOOL_ACL_DECISION_LABELS[row.decision as ToolAclDecision] ?? row.decision }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </AppDialog>
  </LiquidGlassPage>
</template>

<script setup lang="ts">
import { formatDateTime } from '@/utils/dateTime'
import AppDialog from '@/components/common/AppDialog.vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Files, MagicStick, Plus, Refresh, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import {
  LiquidGlassButton,
  LiquidGlassCard,
  LiquidGlassInput,
  LiquidGlassNotice,
  LiquidGlassPage,
  LiquidGlassTable,
} from '@/components/common/liquid-glass'
import ToolAclProjectScopeField from './components/ToolAclProjectScopeField.vue'
import { useProjectStore } from '@/store/project'
import { listAllTools } from '@/api/tool'
import {
  TOOL_ACL_DECISION_LABELS,
  TOOL_ACL_PERMISSION_LABELS,
  toolAclRoleDisplay,
  toolAclTargetDisplay,
} from './toolAclLabels'
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
/** 能力标识 → 中文名；目录读不到时页面退回显示标识本身 */
const toolTitles = ref(new Map<string, string>())
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

const editingTargetMode = computed(() => (editing.value?.targetName === '*' ? 'all' : 'one'))

function setEditingTargetMode(mode: string | number | boolean | undefined) {
  if (!editing.value || mode === editingTargetMode.value) return
  if (mode === 'all') {
    editing.value.targetKind = 'ALL'
    editing.value.targetName = '*'
  } else {
    editing.value.targetKind = 'TOOL'
    editing.value.targetName = ''
  }
}

function targetDisplay(rule: Pick<ToolAclRule, 'targetKind' | 'targetName'>) {
  return toolAclTargetDisplay(rule, toolTitles.value)
}

function roleOptionLabel(roleCode: string) {
  const { label, code } = toolAclRoleDisplay(roleCode)
  return code ? `${label}（${code}）` : label
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
  const counts = await Promise.all(roles.value.map(async (roleCode) => {
    try {
      const { data } = await listToolAcl({ current: 1, size: 1, roleCode })
      return [roleCode, data?.total ?? 0] as const
    } catch {
      return [roleCode, undefined] as const
    }
  }))
  const known: Record<string, number> = {}
  for (const [roleCode, total] of counts) {
    if (total != null) known[roleCode] = total
  }
  roleRuleCounts.value = known
}

async function loadToolTitles() {
  try {
    const tools = await listAllTools({}, undefined, { errorFeedback: 'local' })
    const titles = new Map<string, string>()
    for (const tool of tools) {
      const title = tool.title?.trim()
      if (!title) continue
      for (const key of [tool.name, tool.qualifiedName, tool.sourceQualifiedName]) {
        if (key && !titles.has(key)) titles.set(key, title)
      }
    }
    toolTitles.value = titles
  } catch {
    // 能力目录只用于显示中文名，读不到时保留原始标识
  }
}

async function reloadAll() {
  await reload()
  await reloadRoles()
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
  if (scope.projectId == null && !scope.projectCode) return '所有项目'
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
    ElMessage.warning('请填写角色和能力')
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
    ElMessage.warning('请填写角色')
    return
  }
  const targets: ToolAclTargetRef[] = [
    ...parseLines(batchForm.toolsRaw, 'TOOL'),
  ]
  if (targets.length === 0) {
    ElMessage.warning('请至少填写一个能力')
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
    ElMessage.warning('请至少选择一个角色')
    return
  }
  const targets: ToolAclTargetRef[] = explainForm.targetsRaw
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => !!l)
    .map((line) => {
      // 兼容旧写法 `TOOL:名称` / `ALL:名称`；其余整行都是能力标识（标识本身可能含冒号）
      const prefixed = /^(TOOL|ALL):(.+)$/i.exec(line)
      return prefixed
        ? { kind: prefixed[1].toUpperCase() as ToolAclTargetKind, name: prefixed[2].trim() }
        : { kind: 'TOOL' as ToolAclTargetKind, name: line }
    })
  if (!targets.length) {
    ElMessage.warning('请至少填写一个能力')
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
    if (requestRevision === explainRevision) ElMessage.error('检查失败：' + (err as Error).message)
  } finally {
    if (requestRevision === explainRevision) explaining.value = false
  }
}

onMounted(async () => {
  void loadToolTitles()
  await reloadAll()
})
</script>

<style scoped lang="scss">
.acl-body {
  display: grid;
  flex: 1;
  min-height: 0;
  grid-template-columns: 232px minmax(0, 1fr);
  gap: var(--layout-section-gap);
}

.role-panel {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 8px;
  padding: 12px;

  .role-title {
    padding: 0 2px;
    font-weight: 650;
  }

  .role-scroll {
    flex: 1;
    min-height: 0;
  }

  .role-item {
    display: flex;
    width: 100%;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 2px;
    gap: 8px;
    padding: 7px 12px;
    border: 1px solid transparent;
    border-radius: var(--radius-pill);
    color: var(--text-primary);
    background: transparent;
    font: inherit;
    text-align: left;
    cursor: pointer;

    &:hover {
      background: var(--surface-glass-inset);
    }

    &:focus-visible {
      outline: 2px solid var(--border-focus);
      outline-offset: -2px;
    }

    &.active {
      border-color: var(--border-glass-edge);
      color: var(--brand-on-surface);
      background: var(--surface-glass-inset);
      box-shadow: var(--glass-rim-double);
      font-weight: 650;
    }
  }

  .role-item__text {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 1px;
  }

  .role-item__label,
  .role-item__code {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .role-item__code {
    color: var(--text-secondary);
    font-size: 11px;
    font-weight: 400;
  }

  .role-item__count {
    flex: 0 0 auto;
    color: var(--text-secondary);
    font-size: 12px;
    font-variant-numeric: tabular-nums;
  }
}

.rule-panel {
  min-width: 0;
  min-height: 0;
}

.cell-stack {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
  line-height: 1.4;
}

.cell-main,
.cell-sub {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-sub {
  color: var(--text-secondary);
  font-size: 12px;
}

.cell-note {
  color: var(--text-primary);
}

.wildcard {
  color: var(--brand-on-surface);
  font-weight: 600;
}

:deep(.acl-actions .cell) {
  white-space: nowrap;
}

.dialog-lead {
  margin: 0 0 14px;
  color: var(--text-secondary);
}

@media (max-width: 860px) {
  .acl-body {
    display: flex;
    flex-direction: column;
  }

  .role-panel,
  .rule-panel {
    flex: 0 0 auto;
  }

  .role-panel .role-scroll {
    flex: 0 0 auto;
    height: 180px;
  }
}
</style>
