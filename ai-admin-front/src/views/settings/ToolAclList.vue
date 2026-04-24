<template>
  <div class="page-container">
    <div class="page-header">
      <h2>Tool ACL（角色 × 能力 黑白名单）</h2>
      <div class="header-actions">
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">新建规则</el-button>
        <el-button :icon="Magnet" @click="openBatchDialog">批量授权</el-button>
        <el-button :icon="MagicStick" @click="openExplainDialog">诊断</el-button>
        <el-button :icon="Refresh" @click="reload" :loading="loading">刷新</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="决策规则：DENY 优先；无命中默认拒绝"
      description="上下文 roles 为空时后端走兼容旧行为（不拦截，仅 warn），接入生产前请确保所有入口都把用户角色注入 ChatRequest.roles 或由网关从 JWT 解出。target_name='*' 代表通配；target_kind='ALL' = TOOL ∪ SKILL。"
      style="margin-bottom: 12px"
    />

    <div class="acl-body">
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
                <el-button size="small" link @click="openEditDialog(row)">编辑</el-button>
                <el-popconfirm
                  :title="`确认删除规则 #${row.id}？`"
                  @confirm="handleDelete(row.id)"
                >
                  <template #reference>
                    <el-button size="small" link type="danger">删除</el-button>
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
    <el-dialog v-model="editDialogOpen" :title="editing?.id ? `编辑规则 #${editing.id}` : '新建规则'" width="520px">
      <el-form :model="editing" label-width="90px" v-if="editing">
        <el-form-item label="角色" required>
          <el-input v-model="editing.roleCode" placeholder="admin / ops / customer-service" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="editing.targetKind">
            <el-radio-button label="TOOL" />
            <el-radio-button label="SKILL" />
            <el-radio-button label="ALL" />
          </el-radio-group>
        </el-form-item>
        <el-form-item label="能力" required>
          <el-input v-model="editing.targetName" placeholder="tool/skill 名，或 * 表示通配" />
        </el-form-item>
        <el-form-item label="决策" required>
          <el-radio-group v-model="editing.permission">
            <el-radio-button label="ALLOW" />
            <el-radio-button label="DENY" />
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
    </el-dialog>

    <!-- 批量授权 -->
    <el-dialog v-model="batchDialogOpen" title="批量授权" width="600px">
      <el-form :model="batchForm" label-width="90px">
        <el-form-item label="角色" required>
          <el-input v-model="batchForm.roleCode" placeholder="一次授权给一个角色" />
        </el-form-item>
        <el-form-item label="决策" required>
          <el-radio-group v-model="batchForm.permission">
            <el-radio-button label="ALLOW" />
            <el-radio-button label="DENY" />
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
        <el-form-item label="SKILL 列表">
          <el-input
            v-model="batchForm.skillsRaw"
            type="textarea"
            :rows="3"
            placeholder="每行一个 skill 名"
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
    </el-dialog>

    <!-- 诊断：给 roles + targets 查看决策 -->
    <el-dialog v-model="explainDialogOpen" title="ACL 决策诊断" width="640px">
      <el-form :model="explainForm" label-width="90px">
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
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Search, Magnet, MagicStick } from '@element-plus/icons-vue'

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
  ToolAclRule,
  ToolAclTargetKind,
  ToolAclTargetRef,
} from '@/types/toolAcl'

const loading = ref(false)
const saving = ref(false)
const explaining = ref(false)

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
  roleCode: '',
  permission: 'ALLOW' as ToolAclPermission,
  toolsRaw: '',
  skillsRaw: '',
  note: '',
})

const explainDialogOpen = ref(false)
const explainForm = reactive({
  roles: [] as string[],
  targetsRaw: '',
})
const explainResult = ref<{ kind: string; name: string; decision: ToolAclDecision }[]>([])

const filteredRoles = computed(() => {
  const f = roleFilter.value.trim().toLowerCase()
  if (!f) return roles.value
  return roles.value.filter((r) => r.toLowerCase().includes(f))
})

function kindTagType(kind: ToolAclTargetKind) {
  if (kind === 'SKILL') return 'warning'
  if (kind === 'ALL') return 'info'
  return ''
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

function openCreateDialog() {
  editing.value = {
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
  editing.value = { ...row }
  editDialogOpen.value = true
}

async function handleSave() {
  if (!editing.value) return
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
  batchForm.roleCode = selectedRole.value || ''
  batchForm.permission = 'ALLOW'
  batchForm.toolsRaw = ''
  batchForm.skillsRaw = ''
  batchForm.note = ''
  batchDialogOpen.value = true
}

async function handleBatchGrant() {
  if (!batchForm.roleCode) {
    ElMessage.warning('请填写 role')
    return
  }
  const targets: ToolAclTargetRef[] = [
    ...parseLines(batchForm.toolsRaw, 'TOOL'),
    ...parseLines(batchForm.skillsRaw, 'SKILL'),
  ]
  if (targets.length === 0) {
    ElMessage.warning('请至少填写一个 tool 或 skill')
    return
  }
  saving.value = true
  try {
    const { data } = await grantToolAclBatch({
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
  explainForm.roles = selectedRole.value ? [selectedRole.value] : []
  explainForm.targetsRaw = ''
  explainResult.value = []
  explainDialogOpen.value = true
}

async function handleExplain() {
  if (!explainForm.roles.length) {
    ElMessage.warning('请选择至少一个 role')
    return
  }
  const targets: ToolAclTargetRef[] = explainForm.targetsRaw
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => !!l)
    .map((line) => {
      const [kind, name] = line.split(':').map((s) => s.trim())
      return {
        kind: (kind?.toUpperCase() as ToolAclTargetKind) || 'TOOL',
        name: name || kind,
      }
    })
  if (!targets.length) {
    ElMessage.warning('请填写至少一条 target')
    return
  }
  explaining.value = true
  try {
    const { data } = await explainToolAcl({ roles: explainForm.roles, targets })
    explainResult.value = targets.map((t) => ({
      kind: t.kind,
      name: t.name,
      decision: (data?.[t.name] ?? 'DENY_NO_MATCH') as ToolAclDecision,
    }))
  } catch (err) {
    ElMessage.error('诊断失败：' + (err as Error).message)
  } finally {
    explaining.value = false
  }
}

onMounted(async () => {
  await reload()
  await reloadRoles()
})
</script>

<style scoped lang="scss">
.page-container {
  padding: 16px 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;

  h2 {
    margin: 0;
    font-size: 18px;
  }

  .header-actions {
    display: flex;
    gap: 8px;
  }
}

.acl-body {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 16px;
}

.role-panel {
  background: #fff;
  border: 1px solid #ebeef5;
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
</style>
