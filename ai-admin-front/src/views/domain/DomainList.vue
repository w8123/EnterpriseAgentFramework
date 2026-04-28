<template>
  <div class="page-container">
    <div class="page-header">
      <h2>领域定义（DomainClassifier）</h2>
      <div class="header-actions">
        <el-button type="primary" :icon="Plus" @click="openCreate">新建领域</el-button>
        <el-button :icon="Refresh" @click="reload" :loading="loading">刷新</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="领域 = Tool/Skill 的业务标签集合，分类器在召回前做软过滤"
      description="关键词以 JSON 数组形式存储；命中后由 KeywordDomainClassifier 按命中长度加权排序。后端开关：ai.domain.enabled / ai.domain.soft-fallback。"
      style="margin-bottom: 12px"
    />

    <el-card shadow="never">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="code" label="code" width="120">
          <template #default="{ row }"><code>{{ row.code }}</code></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" width="160" />
        <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
        <el-table-column label="关键词" min-width="280">
          <template #default="{ row }">
            <el-tag
              v-for="kw in parseKeywords(row.keywordsJson)"
              :key="kw"
              size="small"
              style="margin-right: 4px; margin-bottom: 2px"
            >
              {{ kw }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="agentVisible" label="对 Agent 可见" width="120">
          <template #default="{ row }">
            <el-switch
              :model-value="row.agentVisible !== false"
              @change="(v: boolean) => handleToggleVisible(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="启用" width="80">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled !== false"
              @change="(v: boolean) => handleToggleEnabled(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link @click="openEdit(row)">编辑</el-button>
            <el-popconfirm
              :title="`确认删除领域 ${row.code}？同时会移除其归属关系！`"
              @confirm="handleDelete(row.id)"
            >
              <template #reference>
                <el-button size="small" link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogOpen" :title="editing?.id ? `编辑领域 ${editing.code}` : '新建领域'" width="560px">
      <el-form :model="editing" label-width="120px" v-if="editing">
        <el-form-item label="code" required>
          <el-input v-model="editing.code" :disabled="!!editing.id" placeholder="hr / finance / crm" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="editing.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editing.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="关键词">
          <el-select
            v-model="keywordList"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="输入关键词回车添加"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="父领域 code">
          <el-input v-model="editing.parentCode" placeholder="可选，构建领域层级" />
        </el-form-item>
        <el-form-item label="对 Agent 可见">
          <el-switch v-model="editing.agentVisible" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editing.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'

import { createDomain, deleteDomain, listDomains, updateDomain } from '@/api/domain'
import type { DomainDef } from '@/types/domain'

const loading = ref(false)
const saving = ref(false)
const rows = ref<DomainDef[]>([])

const dialogOpen = ref(false)
const editing = ref<Partial<DomainDef> | null>(null)
const keywordList = ref<string[]>([])

watch(
  () => editing.value,
  (v) => {
    keywordList.value = v ? parseKeywords(v.keywordsJson) : []
  },
)

async function reload() {
  loading.value = true
  try {
    const { data } = await listDomains()
    rows.value = data ?? []
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function parseKeywords(json?: string): string[] {
  if (!json) return []
  try {
    const arr = JSON.parse(json)
    return Array.isArray(arr) ? arr.map((x) => String(x)) : []
  } catch {
    return []
  }
}

function openCreate() {
  editing.value = { code: '', name: '', enabled: true, agentVisible: true, keywordsJson: '[]' }
  dialogOpen.value = true
}

function openEdit(row: DomainDef) {
  editing.value = { ...row }
  dialogOpen.value = true
}

async function handleSave() {
  if (!editing.value) return
  if (!editing.value.code || !editing.value.name) {
    ElMessage.warning('code / 名称必填')
    return
  }
  editing.value.keywordsJson = JSON.stringify(keywordList.value ?? [])
  saving.value = true
  try {
    if (editing.value.id) {
      await updateDomain(editing.value.id, editing.value as DomainDef)
    } else {
      await createDomain(editing.value as DomainDef)
    }
    ElMessage.success('保存成功')
    dialogOpen.value = false
    await reload()
  } finally {
    saving.value = false
  }
}

async function handleDelete(id?: number) {
  if (!id) return
  await deleteDomain(id)
  ElMessage.success('已删除')
  await reload()
}

async function handleToggleEnabled(row: DomainDef, enabled: boolean) {
  if (!row.id) return
  await updateDomain(row.id, { ...row, enabled })
  await reload()
}

async function handleToggleVisible(row: DomainDef, agentVisible: boolean) {
  if (!row.id) return
  await updateDomain(row.id, { ...row, agentVisible })
  await reload()
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
</style>
