<template>
  <div class="page-container">
    <div class="page-header">
      <h2>部门字典（DeptSlotExtractor 数据源）</h2>
      <div class="header-actions">
        <el-input
          v-model="filterName"
          size="default"
          placeholder="按名称过滤"
          clearable
          :prefix-icon="Search"
          style="width: 200px"
          @keyup.enter="reload"
        />
        <el-button type="primary" :icon="Plus" @click="openCreate">新增部门</el-button>
        <el-upload
          :show-file-list="false"
          :auto-upload="false"
          :on-change="onFileSelected"
          accept=".csv,.txt"
        >
          <el-button :icon="UploadFilled">导入 CSV</el-button>
        </el-upload>
        <el-button :icon="Refresh" @click="reload" :loading="loading">刷新</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="CSV 表头：parent_id,name,pinyin,aliases,project_scope"
      description="第一行作为表头跳过；name 必填；其余可空。导入后前端自动刷新。"
      style="margin-bottom: 12px"
    />

    <el-card shadow="never">
      <el-table :data="rows" v-loading="loading" stripe size="default">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="parentId" label="父部门 ID" width="120" />
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="pinyin" label="拼音" min-width="160" />
        <el-table-column prop="aliases" label="别名" min-width="180" show-overflow-tooltip />
        <el-table-column prop="projectScope" label="项目范围" width="110" />
        <el-table-column prop="enabled" label="启用" width="80">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled !== false"
              @change="(v: boolean) => handleToggle(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link @click="openEdit(row)">编辑</el-button>
            <el-popconfirm
              :title="`确认删除部门 ${row.name}？`"
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

    <el-dialog v-model="dialogOpen" :title="editing?.id ? `编辑部门 #${editing.id}` : '新增部门'" width="500px">
      <el-form :model="editing" label-width="100px" v-if="editing">
        <el-form-item label="父部门 ID">
          <el-input-number v-model="editing.parentId" :min="1" :step="1" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="editing.name" />
        </el-form-item>
        <el-form-item label="拼音">
          <el-input v-model="editing.pinyin" placeholder="如 yanfa / caiwu" />
        </el-form-item>
        <el-form-item label="别名">
          <el-input v-model="editing.aliases" type="textarea" :rows="2" placeholder="逗号分隔，如 研发,RD" />
        </el-form-item>
        <el-form-item label="项目范围">
          <el-input-number v-model="editing.projectScope" :min="1" :step="1" controls-position="right" style="width: 100%" />
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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Search, UploadFilled } from '@element-plus/icons-vue'
import type { UploadFile } from 'element-plus'

import {
  createSlotDept,
  deleteSlotDept,
  importSlotDept,
  pageSlotDept,
  updateSlotDept,
} from '@/api/slotExtractor'
import type { SlotDeptRow } from '@/types/slotExtractor'

const loading = ref(false)
const saving = ref(false)

const rows = ref<SlotDeptRow[]>([])
const filterName = ref('')
const pagination = reactive({ current: 1, size: 20, total: 0 })

const dialogOpen = ref(false)
const editing = ref<Partial<SlotDeptRow> | null>(null)

async function reload() {
  loading.value = true
  try {
    const { data } = await pageSlotDept({
      current: pagination.current,
      size: pagination.size,
      name: filterName.value || undefined,
    })
    rows.value = data?.records ?? []
    pagination.total = data?.total ?? 0
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = { name: '', enabled: true }
  dialogOpen.value = true
}

function openEdit(row: SlotDeptRow) {
  editing.value = { ...row }
  dialogOpen.value = true
}

async function handleSave() {
  if (!editing.value) return
  if (!editing.value.name) {
    ElMessage.warning('名称必填')
    return
  }
  saving.value = true
  try {
    if (editing.value.id) {
      await updateSlotDept(editing.value.id, editing.value as SlotDeptRow)
    } else {
      await createSlotDept(editing.value as SlotDeptRow)
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
  await deleteSlotDept(id)
  ElMessage.success('已删除')
  await reload()
}

async function handleToggle(row: SlotDeptRow, enabled: boolean) {
  if (!row.id) return
  await updateSlotDept(row.id, { ...row, enabled })
  await reload()
}

async function onFileSelected(file: UploadFile) {
  if (!file.raw) return
  try {
    const { data } = await importSlotDept(file.raw)
    ElMessage.success(`导入完成：成功 ${data.ok}，跳过 ${data.skip}`)
    await reload()
  } catch (ex) {
    /* error msg already shown by request interceptor */
  }
}

onMounted(reload)
</script>

<style scoped lang="scss">
.page-container { padding: 16px; }
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  h2 { margin: 0; font-size: 18px; }
}
.header-actions {
  display: flex; gap: 8px; align-items: center;
}
</style>
