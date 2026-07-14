<template>
  <WorkbenchPage class="mcp-visibility-board-page" layout="list">
    <PageHeader
      variant="standard"
      domain="governance"
      eyebrow="MCP Governance"
      title="MCP 暴露白名单"
      description="控制哪些 Tool 与粗粒度能力可以通过 MCP 协议对外发现，并与 Tool ACL、Client 白名单共同形成治理边界。"
    >
      <template #actions>
        <el-tooltip content="刷新暴露白名单" placement="top">
          <el-button circle :icon="Refresh" :loading="loading" aria-label="刷新暴露白名单" @click="reload" />
        </el-tooltip>
      </template>
    </PageHeader>

    <WorkbenchPanel level="control" density="comfortable">
      <div class="mcp-filter-row">
        <el-input v-model="filterText" placeholder="过滤名称" clearable style="width: 200px" :prefix-icon="Search" />
        <el-radio-group v-model="filterKind" size="default">
          <el-radio-button label="" />
          <el-radio-button label="TOOL" />
          <el-radio-button label="SKILL" />
        </el-radio-group>
      </div>
    </WorkbenchPanel>

    <el-alert
      type="warning"
      show-icon
      :closable="false"
      title="默认所有 Tool / 粗粒度能力不对外暴露；勾选后才允许通过 MCP 协议访问"
      description="对外暴露 = Cursor/Claude Desktop/Dify 等 MCP Client 可在 tools/list 看到该能力。即使勾选，仍需 ToolACL 决策通过、且在 Client 自己的 toolWhitelist 内。"
    />

    <el-card class="workbench-list-surface" shadow="never">
      <el-table :data="filteredRows" v-loading="loading" stripe size="default">
        <el-table-column prop="targetKind" label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.targetKind === 'SKILL' ? 'success' : ''">{{ row.targetKind }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="targetName" label="名称" min-width="220">
          <template #default="{ row }"><code>{{ row.targetName }}</code></template>
        </el-table-column>
        <el-table-column prop="exposed" label="对外暴露" width="120">
          <template #default="{ row }">
            <el-switch
              :model-value="row.exposed"
              @change="(v: boolean) => handleToggle(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="note" label="备注" min-width="220">
          <template #default="{ row }">
            <el-input
              v-model="row.note"
              size="small"
              placeholder="审批单号 / 暴露原因"
              @blur="handleNoteUpdate(row)"
            />
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-divider />
    <div class="hint-add">
      <h4>新增暴露</h4>
      <el-form :inline="true" :model="addForm">
        <el-form-item label="类型">
          <el-radio-group v-model="addForm.kind">
            <el-radio-button label="TOOL" />
            <el-radio-button label="SKILL" />
          </el-radio-group>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="addForm.name" placeholder="tool / 能力名" style="width: 240px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="addForm.note" placeholder="审批单号" style="width: 200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleAdd" :disabled="!addForm.name">添加</el-button>
        </el-form-item>
      </el-form>
    </div>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import PageHeader from '@/components/common/PageHeader.vue'

import { listMcpVisibility, setMcpVisibility } from '@/api/mcp'
import type { McpVisibility } from '@/types/mcp'

const loading = ref(false)
const rows = ref<McpVisibility[]>([])
const filterText = ref('')
const filterKind = ref<'' | 'TOOL' | 'SKILL'>('')

const addForm = reactive({
  kind: 'TOOL' as 'TOOL' | 'SKILL',
  name: '',
  note: '',
})

const filteredRows = computed(() => {
  const q = filterText.value.toLowerCase()
  return rows.value.filter((r) => {
    if (filterKind.value && r.targetKind !== filterKind.value) return false
    if (q && !r.targetName.toLowerCase().includes(q)) return false
    return true
  })
})

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
  await setMcpVisibility({ kind: row.targetKind, name: row.targetName, exposed, note: row.note ?? undefined })
  ElMessage.success(exposed ? '已对外暴露' : '已停止暴露')
  await reload()
}

async function handleNoteUpdate(row: McpVisibility) {
  await setMcpVisibility({
    kind: row.targetKind,
    name: row.targetName,
    exposed: row.exposed,
    note: row.note ?? undefined,
  })
}

async function handleAdd() {
  if (!addForm.name.trim()) {
    ElMessage.warning('名称必填')
    return
  }
  await setMcpVisibility({ kind: addForm.kind, name: addForm.name.trim(), exposed: true, note: addForm.note })
  ElMessage.success('已添加并暴露')
  addForm.name = ''
  addForm.note = ''
  await reload()
}

onMounted(reload)
</script>

<style scoped lang="scss">
.mcp-filter-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.hint-add h4 { margin: 8px 0; }
</style>
