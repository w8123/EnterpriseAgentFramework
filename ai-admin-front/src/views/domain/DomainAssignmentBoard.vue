<template>
  <div class="page-container">
    <div class="page-header">
      <h2>领域归属画布</h2>
      <div class="header-actions">
        <el-tag size="small" type="info">{{ domains.length }} 个领域</el-tag>
        <el-button :icon="Refresh" @click="reload" :loading="loading">刷新</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="左：领域树。中：当前领域已挂的 Tool/Skill/Agent/Project。右：候选目标，勾选后批量挂接。"
      description="挂接 source = AUTO_FROM_PROJECT 的条目由扫描器自动生成；删除后下次扫描会被重新写入，建议直接调整 scan_project.default_domain_code。"
      style="margin-bottom: 12px"
    />

    <el-row :gutter="12">
      <!-- 领域树 -->
      <el-col :span="6">
        <el-card shadow="never">
          <template #header><span>领域</span></template>
          <el-scrollbar max-height="70vh">
            <div
              v-for="d in domains"
              :key="d.code"
              class="dom-item"
              :class="{ active: selectedCode === d.code }"
              @click="selectDomain(d.code)"
            >
              <div class="dom-name">{{ d.name }}</div>
              <code class="dom-code">{{ d.code }}</code>
              <el-tag size="small">{{ coverageMap[d.code]?.toolCount ?? 0 }} T</el-tag>
              <el-tag size="small" type="success">{{ coverageMap[d.code]?.skillCount ?? 0 }} S</el-tag>
            </div>
          </el-scrollbar>
        </el-card>
      </el-col>

      <!-- 已挂接 -->
      <el-col :span="9">
        <el-card shadow="never">
          <template #header>
            <span>{{ selectedCode || '请选择领域' }} 已挂接</span>
            <el-tag size="small" style="margin-left: 8px">{{ assignments.length }}</el-tag>
          </template>
          <el-scrollbar max-height="70vh">
            <div v-if="!selectedCode" class="dim">从左侧选择一个领域</div>
            <el-empty v-else-if="!assignments.length" description="未挂接任何目标" />
            <el-table v-else :data="assignments" size="small" stripe>
              <el-table-column prop="targetKind" label="类型" width="80">
                <template #default="{ row }">
                  <el-tag size="small" :type="kindTagType(row.targetKind)">{{ row.targetKind }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="targetName" label="目标" min-width="160" show-overflow-tooltip />
              <el-table-column prop="source" label="来源" width="120">
                <template #default="{ row }">
                  <el-tag v-if="row.source === 'AUTO_FROM_PROJECT'" size="small" type="warning">自动</el-tag>
                  <el-tag v-else size="small">手动</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="80">
                <template #default="{ row }">
                  <el-button size="small" link type="danger" @click="handleUnassign(row)">解绑</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-scrollbar>
        </el-card>
      </el-col>

      <!-- 候选目标 -->
      <el-col :span="9">
        <el-card shadow="never">
          <template #header>
            <span>候选目标</span>
            <el-radio-group v-model="targetKind" size="small" style="margin-left: 12px">
              <el-radio-button label="TOOL" />
              <el-radio-button label="SKILL" />
            </el-radio-group>
            <el-input
              v-model="searchText"
              size="small"
              placeholder="过滤名称"
              style="width: 160px; margin-left: 8px"
              clearable
            />
            <el-button
              type="primary"
              size="small"
              :disabled="!selectedCode || !checked.length"
              @click="handleAssign"
              style="float: right"
            >
              批量挂接 ({{ checked.length }})
            </el-button>
          </template>
          <el-scrollbar max-height="70vh">
            <el-table
              :data="filteredCandidates"
              size="small"
              stripe
              @selection-change="onSelectionChange"
              ref="tableRef"
              row-key="name"
            >
              <el-table-column type="selection" width="44" />
              <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
              <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
              <el-table-column label="已挂" width="120">
                <template #default="{ row }">
                  <el-tag
                    v-for="d in row.domains"
                    :key="d"
                    size="small"
                    style="margin-right: 4px"
                    :type="d === selectedCode ? 'success' : 'info'"
                  >
                    {{ d }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-scrollbar>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'

import {
  deleteAssignment,
  getDomainCoverage,
  grantAssignmentBatch,
  listAssignments,
  listDomains,
} from '@/api/domain'
import { getTools } from '@/api/tool'
import { getSkills } from '@/api/skill'
import type {
  DomainAssignment,
  DomainCoverageRow,
  DomainDef,
  TargetRefBody,
} from '@/types/domain'

const loading = ref(false)
const domains = ref<DomainDef[]>([])
const coverageMap = ref<Record<string, DomainCoverageRow>>({})
const assignments = ref<DomainAssignment[]>([])
const selectedCode = ref<string>('')

interface Candidate {
  name: string
  description?: string
  domains: string[]
}

const tools = ref<Candidate[]>([])
const skills = ref<Candidate[]>([])
const targetKind = ref<'TOOL' | 'SKILL'>('TOOL')
const searchText = ref('')
const checked = ref<Candidate[]>([])

async function reload() {
  loading.value = true
  try {
    const [d, cov, t, s] = await Promise.all([
      listDomains(),
      getDomainCoverage(),
      getTools({ current: 1, size: 500 }),
      getSkills({ current: 1, size: 500 }),
    ])
    domains.value = d.data ?? []
    const m: Record<string, DomainCoverageRow> = {}
    for (const r of cov.data ?? []) m[r.domainCode] = r
    coverageMap.value = m
    tools.value = (t.data?.records ?? []).map((row: any) => ({
      name: row.name,
      description: row.description ?? row.aiDescription,
      domains: [],
    }))
    skills.value = (s.data?.records ?? []).map((row: any) => ({
      name: row.name,
      description: row.description,
      domains: [],
    }))
    if (!selectedCode.value && domains.value.length) {
      selectedCode.value = domains.value[0].code
    }
    if (selectedCode.value) await loadAssignments(selectedCode.value)
  } finally {
    loading.value = false
  }
}

async function loadAssignments(code: string) {
  const { data } = await listAssignments(code)
  assignments.value = data ?? []
  // 把"已挂到当前领域"的标记同步到候选列表，便于直观判断
  const set = new Set(assignments.value.map((a) => `${a.targetKind}:${a.targetName}`))
  for (const t of tools.value) t.domains = set.has(`TOOL:${t.name}`) ? [code] : []
  for (const s of skills.value) s.domains = set.has(`SKILL:${s.name}`) ? [code] : []
}

function selectDomain(code: string) {
  selectedCode.value = code
  loadAssignments(code)
}

const candidates = computed<Candidate[]>(() =>
  targetKind.value === 'TOOL' ? tools.value : skills.value,
)

const filteredCandidates = computed<Candidate[]>(() => {
  if (!searchText.value) return candidates.value
  const q = searchText.value.toLowerCase()
  return candidates.value.filter(
    (c) => c.name.toLowerCase().includes(q) || (c.description ?? '').toLowerCase().includes(q),
  )
})

function onSelectionChange(rows: Candidate[]) {
  checked.value = rows
}

async function handleAssign() {
  if (!selectedCode.value || !checked.value.length) return
  const targets: TargetRefBody[] = checked.value.map((c) => ({
    kind: targetKind.value,
    name: c.name,
  }))
  await grantAssignmentBatch(selectedCode.value, targets)
  ElMessage.success(`已挂接 ${targets.length} 项`)
  await loadAssignments(selectedCode.value)
}

async function handleUnassign(row: DomainAssignment) {
  if (!row.id) return
  await deleteAssignment(row.id)
  ElMessage.success('已解绑')
  await loadAssignments(selectedCode.value)
}

function kindTagType(kind: string): 'success' | 'warning' | 'info' | '' {
  if (kind === 'TOOL') return ''
  if (kind === 'SKILL') return 'success'
  if (kind === 'AGENT') return 'warning'
  return 'info'
}

onMounted(reload)
</script>

<style scoped lang="scss">
.page-container { padding: 16px; }
.page-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;
  h2 { margin: 0; font-size: 18px; }
}
.header-actions { display: flex; gap: 8px; align-items: center; }

.dom-item {
  display: flex; gap: 6px; align-items: center;
  padding: 8px 10px; border-radius: 6px; cursor: pointer;
  transition: background 0.15s;
  &:hover { background: #f5f7fa; }
  &.active { background: #ecf5ff; }
  .dom-name { font-weight: 600; flex: 1; }
  .dom-code { color: #888; font-size: 12px; }
}
.dim { color: #999; padding: 8px; }
</style>
