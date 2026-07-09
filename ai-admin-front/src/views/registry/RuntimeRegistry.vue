<template>
  <div class="runtime-page">
    <section class="runtime-hero">
      <div>
        <p class="eyebrow">Runtime Control Plane</p>
        <h1>Runtime 纳管</h1>
        <p class="hero-desc">统一查看中台 Agent Runtime 与业务系统 Capability Host，区分 JDK17 运行侧和 JDK8 接入侧。</p>
      </div>
      <el-button type="primary" :icon="Refresh" :loading="loading" @click="loadRuntimes">刷新</el-button>
    </section>

    <section class="metric-grid">
      <div v-for="item in metrics" :key="item.label" class="metric-item">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </div>
    </section>

    <el-card class="runtime-card" shadow="never">
      <template #header>
        <div class="table-toolbar">
          <div class="toolbar-title">
            <span class="title-mark" />
            <span>Runtime 列表</span>
          </div>
          <div class="filters">
            <el-select v-model="sourceFilter" clearable placeholder="来源" style="width: 150px">
              <el-option label="平台内置" value="PLATFORM" />
              <el-option label="业务接入" value="PROJECT_INSTANCE" />
            </el-select>
            <el-select v-model="roleFilter" clearable placeholder="角色" style="width: 170px">
              <el-option
                v-for="item in RUNTIME_ROLE_SELECT_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <el-select v-model="placementFilter" clearable placeholder="运行位置" style="width: 150px">
              <el-option
                v-for="item in RUNTIME_PLACEMENT_SELECT_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <el-select v-model="statusFilter" clearable placeholder="状态" style="width: 150px">
              <el-option
                v-for="item in INSTANCE_STATUS_SELECT_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" :data="filteredRuntimes" row-key="id" class="runtime-table">
        <el-table-column label="Runtime" min-width="260" fixed>
          <template #default="{ row }">
            <div class="runtime-name">
              <strong>{{ row.displayName || row.runtimeType }}</strong>
              <span>{{ row.id }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="130">
          <template #default="{ row }">
            <el-tag :type="row.source === 'PLATFORM' ? 'primary' : 'success'" effect="plain">
              {{ row.source === 'PLATFORM' ? '平台内置' : '业务接入' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="runtimeRole" label="角色" width="150">
          <template #default="{ row }">
            <el-tag :type="row.runtimeRole === 'CAPABILITY_HOST' ? 'success' : 'primary'" effect="plain">
              {{ formatRuntimeRoleLabel(row.runtimeRole) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="runtimePlacement" label="运行位置" width="130">
          <template #default="{ row }">
            <el-tag effect="plain">{{ formatRuntimePlacementLabel(row.runtimePlacement) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="130">
          <template #default="{ row }">
            <span class="status-pill" :class="row.status.toLowerCase()">
              <i />
              {{ formatInstanceStatusLabel(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="能力" min-width="280">
          <template #default="{ row }">
            <div class="capability-tags">
              <el-tag
                v-for="feat in formatRuntimeFeatureLabels(row)"
                :key="feat"
                size="small"
                type="info"
                effect="plain"
              >
                {{ feat }}
              </el-tag>
              <el-tag size="small" type="success" effect="plain">
                {{ formatRuntimeTypeLabel(row.runtimeType) }}
              </el-tag>
              <span v-if="!formatRuntimeFeatureLabels(row).length && !row.runtimeType" class="muted">-</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="项目 / 实例" min-width="260">
          <template #default="{ row }">
            <div v-if="row.source === 'PROJECT_INSTANCE'" class="instance-cell">
              <strong>{{ row.projectCode || '-' }}</strong>
              <span>{{ row.instanceId || '-' }}</span>
            </div>
            <span v-else class="muted">平台 Agent Runtime</span>
          </template>
        </el-table-column>
        <el-table-column label="地址" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.baseUrl || row.host || '-' }}</template>
        </el-table-column>
        <el-table-column prop="sdkVersion" label="SDK" width="130">
          <template #default="{ row }">{{ row.sdkVersion || '-' }}</template>
        </el-table-column>
        <el-table-column prop="lastHeartbeatAt" label="最近心跳" min-width="180">
          <template #default="{ row }">{{ formatTime(row.lastHeartbeatAt) }}</template>
        </el-table-column>
        <el-table-column label="治理策略" min-width="240">
          <template #default="{ row }">
            <div class="policy-cell">
              <div class="policy-tags">
                <el-tag v-if="row.policyDisabled" type="danger" effect="plain">已禁用</el-tag>
                <el-tag v-else type="success" effect="plain">允许心跳</el-tag>
                <el-tag v-if="row.minSdkVersion" type="warning" effect="plain">
                  SDK >= {{ row.minSdkVersion }}
                </el-tag>
                <el-tag :type="row.allowEmbeddedExecution === false ? 'danger' : 'info'" effect="plain">
                  Embedded {{ row.allowEmbeddedExecution === false ? '禁止' : '允许' }}
                </el-tag>
                <el-tag :type="row.allowHybridExecution === false ? 'danger' : 'info'" effect="plain">
                  Hybrid {{ row.allowHybridExecution === false ? '禁止' : '允许' }}
                </el-tag>
              </div>
              <span>{{ row.policyMessage || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="治理动作" width="220" fixed="right">
          <template #default="{ row }">
            <div v-if="row.source === 'PROJECT_INSTANCE'" class="runtime-actions">
              <el-button link type="primary" @click="openProject(row)">查看项目</el-button>
              <el-button
                v-if="row.status === 'DISABLED'"
                link
                type="success"
                :loading="statusUpdatingId === row.id"
                @click="setRuntimeStatus(row, 'ONLINE')"
              >
                启用
              </el-button>
              <el-button
                v-else
                link
                type="danger"
                :loading="statusUpdatingId === row.id"
                @click="setRuntimeStatus(row, 'DISABLED')"
              >
                禁用
              </el-button>
              <el-button link type="primary" @click="openPolicyDrawer(row)">策略</el-button>
            </div>
            <span v-else class="muted">平台内置</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="policyDrawerVisible" size="420px" title="Runtime 治理策略">
      <el-form label-position="top" class="policy-form">
        <el-form-item label="Runtime 实例">
          <div class="selected-runtime">
            <strong>{{ selectedRuntime?.projectCode || '-' }}</strong>
            <span>{{ selectedRuntime?.instanceId || '-' }}</span>
          </div>
        </el-form-item>
        <el-form-item label="禁用本地 Runtime">
          <el-switch v-model="policyForm.disabled" active-text="禁用" inactive-text="允许" />
        </el-form-item>
        <el-form-item label="最低 SDK 版本">
          <el-input v-model="policyForm.minSdkVersion" placeholder="例如 1.2.0，留空表示不限制" clearable />
        </el-form-item>
        <el-form-item label="Embedded 执行准入">
          <el-switch v-model="policyForm.allowEmbeddedExecution" active-text="允许" inactive-text="禁止" />
        </el-form-item>
        <el-form-item label="Hybrid 执行准入">
          <el-switch v-model="policyForm.allowHybridExecution" active-text="允许" inactive-text="禁止" />
        </el-form-item>
        <el-form-item label="策略提示">
          <el-input
            v-model="policyForm.message"
            type="textarea"
            :rows="4"
            placeholder="给 SDK 或治理人员看的策略说明"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="drawer-footer">
          <el-button @click="policyDrawerVisible = false">取消</el-button>
          <el-button type="primary" :loading="policySaving" @click="savePolicy">保存策略</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  updateRegistryProjectInstanceGovernancePolicy,
  updateRegistryProjectInstanceStatus,
} from '@/api/registry'
import { listRuntimeRegistry } from '@/api/runtime'
import type { RuntimeRegistryEntry } from '@/types/agent'
import type { ProjectInstance } from '@/types/registry'
import {
  formatInstanceStatusLabel,
  formatRuntimeFeatureLabels,
  formatRuntimePlacementLabel,
  formatRuntimeRoleLabel,
  formatRuntimeTypeLabel,
  INSTANCE_STATUS_SELECT_OPTIONS,
  RUNTIME_PLACEMENT_SELECT_OPTIONS,
  RUNTIME_ROLE_SELECT_OPTIONS,
} from '@/utils/registryLabels'

const router = useRouter()
const loading = ref(false)
const statusUpdatingId = ref('')
const policySaving = ref(false)
const policyDrawerVisible = ref(false)
const selectedRuntime = ref<RuntimeRegistryEntry | null>(null)
const runtimes = ref<RuntimeRegistryEntry[]>([])
const sourceFilter = ref('')
const roleFilter = ref('')
const placementFilter = ref('')
const statusFilter = ref('')
const policyForm = ref({
  disabled: false,
  minSdkVersion: '',
  allowEmbeddedExecution: true,
  allowHybridExecution: true,
  message: '',
})

const filteredRuntimes = computed(() =>
  runtimes.value.filter((item) => {
    if (sourceFilter.value && item.source !== sourceFilter.value) return false
    if (roleFilter.value && item.runtimeRole !== roleFilter.value) return false
    if (placementFilter.value && item.runtimePlacement !== placementFilter.value) return false
    if (statusFilter.value && item.status !== statusFilter.value) return false
    return true
  }),
)

const metrics = computed(() => {
  const all = runtimes.value
  return [
    { label: '全部 Runtime', value: all.length },
    { label: 'Agent Runtime', value: all.filter((item) => item.runtimeRole === 'AGENT_RUNTIME').length },
    { label: 'Capability Host', value: all.filter((item) => item.runtimeRole === 'CAPABILITY_HOST').length },
    { label: '在线', value: all.filter((item) => item.status === 'ONLINE').length },
  ]
})

onMounted(loadRuntimes)

async function loadRuntimes() {
  loading.value = true
  try {
    const { data } = await listRuntimeRegistry()
    runtimes.value = Array.isArray(data) ? data : []
  } catch (error) {
    runtimes.value = []
    ElMessage.error((error as Error).message || '加载 Runtime 列表失败')
  } finally {
    loading.value = false
  }
}

function openProject(row: RuntimeRegistryEntry) {
  if (!row.projectCode) return
  router.push(`/registry/projects/${row.projectCode}`)
}

async function setRuntimeStatus(row: RuntimeRegistryEntry, status: ProjectInstance['status']) {
  if (!row.projectCode || !row.instanceId) {
    ElMessage.warning('缺少项目编码或实例 ID，无法更新状态')
    return
  }
  if (status === 'DISABLED') {
    await ElMessageBox.confirm(
      `禁用后，SDK 心跳将收到禁用治理策略：${row.projectCode} / ${row.instanceId}`,
      '确认禁用 Runtime 实例',
      { type: 'warning', confirmButtonText: '禁用', cancelButtonText: '取消' },
    )
  }
  statusUpdatingId.value = row.id
  try {
    await updateRegistryProjectInstanceStatus(row.projectCode, {
      instanceId: row.instanceId,
      status,
    })
    ElMessage.success(status === 'DISABLED' ? '实例已禁用' : '实例已启用')
    await loadRuntimes()
  } catch (error) {
    ElMessage.error((error as Error).message || '更新 Runtime 实例状态失败')
  } finally {
    statusUpdatingId.value = ''
  }
}

function openPolicyDrawer(row: RuntimeRegistryEntry) {
  selectedRuntime.value = row
  policyForm.value = {
    disabled: Boolean(row.policyDisabled),
    minSdkVersion: row.minSdkVersion || '',
    allowEmbeddedExecution: row.allowEmbeddedExecution !== false,
    allowHybridExecution: row.allowHybridExecution !== false,
    message: row.policyMessage || '',
  }
  policyDrawerVisible.value = true
}

async function savePolicy() {
  const row = selectedRuntime.value
  if (!row?.projectCode || !row.instanceId) {
    ElMessage.warning('缺少项目编码或实例 ID，无法保存策略')
    return
  }
  policySaving.value = true
  try {
    await updateRegistryProjectInstanceGovernancePolicy(row.projectCode, {
      instanceId: row.instanceId,
      disabled: policyForm.value.disabled,
      minSdkVersion: policyForm.value.minSdkVersion || null,
      allowEmbeddedExecution: policyForm.value.allowEmbeddedExecution,
      allowHybridExecution: policyForm.value.allowHybridExecution,
      message: policyForm.value.message || null,
    })
    ElMessage.success('Runtime 治理策略已保存')
    policyDrawerVisible.value = false
    await loadRuntimes()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存 Runtime 治理策略失败')
  } finally {
    policySaving.value = false
  }
}

function formatTime(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}/${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
</script>

<style scoped lang="scss">
.runtime-page {
  min-height: calc(100vh - 56px);
  padding: var(--reachai-workbench-page-padding, var(--reachai-workbench-title-gap, 10px) 28px 16px);
  background: transparent;
  color: #101828;
}

.runtime-hero {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: flex-start;
  min-height: var(--reachai-workbench-title-height, 120px);
  margin-bottom: 16px;
  padding: var(--reachai-workbench-title-padding, 26px 28px);
  border: 1px solid rgb(var(--brand-selected-rgb) / 0.58);
  border-radius: var(--reachai-workbench-title-radius, 16px);
  background: var(--brand-glass-bg);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.82),
    0 18px 44px rgb(var(--brand-primary-rgb) / 0.12);
  backdrop-filter: blur(20px) saturate(1.08);

  h1 {
    margin: 4px 0 8px;
    font-size: 30px;
    line-height: 1.2;
    letter-spacing: 0;
    color: var(--text-primary);
  }
}

.eyebrow {
  margin: 0;
  color: var(--brand-primary);
  font-size: 13px;
  font-weight: 800;
  letter-spacing: 0;
}

.hero-desc {
  max-width: 760px;
  margin: 0;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.6;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.metric-item {
  min-height: 88px;
  padding: 18px 20px;
  border: 1px solid rgb(var(--brand-selected-rgb) / 0.52);
  border-radius: 8px;
  background: var(--brand-glass-card-bg);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.76),
    0 12px 28px rgb(var(--brand-primary-rgb) / 0.08);
  backdrop-filter: blur(16px);

  span {
    display: block;
    margin-bottom: 10px;
    color: var(--text-secondary);
    font-size: 13px;
  }

  strong {
    color: var(--text-primary);
    font-size: 28px;
    line-height: 1;
  }
}

.runtime-card {
  border: 1px solid rgb(var(--brand-selected-rgb) / 0.56);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.76),
    0 18px 42px rgb(var(--brand-primary-rgb) / 0.1);
  backdrop-filter: blur(18px);

  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid rgb(var(--brand-selected-rgb) / 0.58);
    background: linear-gradient(90deg, rgb(var(--brand-selected-rgb) / 0.34), rgba(255, 255, 255, 0.42));
  }

  :deep(.el-card__body) {
    padding: 0;
  }

  :deep(th.el-table__cell) {
    background: rgb(var(--brand-selected-rgb) / 0.44) !important;
    background-color: rgb(var(--brand-selected-rgb) / 0.44) !important;
    color: var(--brand-active);
    font-weight: 700;
  }

  :deep(.el-table__row:hover > td.el-table__cell) {
    background: rgb(var(--brand-selected-rgb) / 0.2);
  }
}

.table-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
}

.toolbar-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 16px;
  font-weight: 750;
  color: var(--text-primary);
}

.title-mark {
  width: 4px;
  height: 18px;
  border-radius: 2px;
  background: linear-gradient(180deg, var(--brand-primary), var(--brand-hover));
  box-shadow: 0 0 14px rgb(var(--brand-primary-rgb) / 0.28);
}

.filters,
.capability-tags,
.runtime-actions,
.policy-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.runtime-actions {
  align-items: center;
}

.policy-cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;

  span {
    color: var(--text-secondary);
    font-size: 12px;
    line-height: 1.4;
  }
}

.policy-form {
  :deep(.el-form-item__label) {
    font-weight: 700;
    color: var(--text-primary);
  }
}

.selected-runtime {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid rgb(var(--brand-selected-rgb) / 0.58);
  border-radius: 8px;
  background: rgb(var(--brand-selected-rgb) / 0.34);

  strong {
    color: var(--text-primary);
  }

  span {
    color: var(--text-secondary);
    font-size: 12px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.runtime-name,
.instance-cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;

  strong {
    color: var(--text-primary);
    font-weight: 750;
  }

  span {
    color: var(--text-secondary);
    font-size: 12px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 26px;
  padding: 0 12px;
  border: 1px solid #abefc6;
  border-radius: 6px;
  background: #ecfdf3;
  color: #067647;
  font-size: 13px;
  font-weight: 700;

  i {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #12b76a;
  }

  &.offline,
  &.stale {
    border-color: #e4e7ec;
    background: #f2f4f7;
    color: #667085;

    i {
      background: #98a2b3;
    }
  }

  &.disabled {
    border-color: #fecdd6;
    background: #fff1f3;
    color: #c01048;

    i {
      background: #f43f5e;
    }
  }
}

.muted {
  color: #98a2b3;
}

@media (max-width: 1100px) {
  .runtime-hero,
  .table-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

:global([data-theme="dark"]) {
  .runtime-page {
    color: #e5e7eb;
  }

  .runtime-hero,
  .metric-item,
  .runtime-card {
    border-color: rgb(var(--brand-primary-rgb) / 0.28);
    background: linear-gradient(145deg, rgba(15, 23, 42, 0.82), rgb(var(--brand-primary-rgb) / 0.18));
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.08),
      0 18px 44px rgba(0, 0, 0, 0.24);
  }

  .runtime-card {
    :deep(.el-card__header) {
      border-bottom-color: rgb(var(--brand-primary-rgb) / 0.24);
      background: linear-gradient(90deg, rgb(var(--brand-primary-rgb) / 0.2), rgba(15, 23, 42, 0.42));
    }

    :deep(th.el-table__cell) {
      background: rgb(var(--brand-primary-rgb) / 0.24) !important;
      background-color: rgb(var(--brand-primary-rgb) / 0.24) !important;
      color: var(--brand-selected-bg);
    }

    :deep(td.el-table__cell) {
      background: rgba(15, 23, 42, 0.56);
      color: #e2e8f0;
    }
  }
}
</style>
