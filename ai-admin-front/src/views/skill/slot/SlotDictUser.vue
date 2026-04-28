<template>
  <div class="page-container">
    <div class="page-header">
      <h2>人员字典（UserSlotExtractor 数据源）</h2>
      <div class="header-actions">
        <el-input
          v-model="filterName"
          size="default"
          placeholder="按姓名过滤"
          clearable
          :prefix-icon="Search"
          style="width: 200px"
          @keyup.enter="reload"
        />
        <el-button type="primary" :icon="Plus" @click="openCreate">新增人员</el-button>
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
      title="CSV 表头：dept_id,name,pinyin,employee_no,aliases"
      description="同名人员将由提取器在运行时按 ctx.userDeptId 软消歧；为减少误匹配建议补全 employee_no。"
      style="margin-bottom: 12px"
    />

    <el-card shadow="never">
      <el-table :data="rows" v-loading="loading" stripe size="default">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="deptId" label="部门 ID" width="100" />
        <el-table-column prop="name" label="姓名" min-width="120" />
        <el-table-column prop="pinyin" label="拼音" min-width="140" />
        <el-table-column prop="employeeNo" label="工号" width="120" />
        <el-table-column prop="aliases" label="别名" min-width="160" show-overflow-tooltip />
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
              :title="`确认删除 ${row.name}？`"
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

    <el-dialog v-model="dialogOpen" :title="editing?.id ? `编辑人员 #${editing.id}` : '新增人员'" width="480px">
      <el-form :model="editing" label-width="90px" v-if="editing">
        <el-form-item label="部门 ID">
          <el-input-number v-model="editing.deptId" :min="1" :step="1" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="editing.name" />
        </el-form-item>
        <el-form-item label="拼音">
          <el-input v-model="editing.pinyin" placeholder="zhang san" />
        </el-form-item>
        <el-form-item label="工号">
          <el-input v-model="editing.employeeNo" />
        </el-form-item>
        <el-form-item label="别名">
          <el-input v-model="editing.aliases" placeholder="逗号分隔" />
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
  createSlotUser,
  deleteSlotUser,
  importSlotUser,
  pageSlotUser,
  updateSlotUser,
} from '@/api/slotExtractor'
import type { SlotUserRow } from '@/types/slotExtractor'

const loading = ref(false)
const saving = ref(false)

const rows = ref<SlotUserRow[]>([])
const filterName = ref('')
const pagination = reactive({ current: 1, size: 20, total: 0 })

const dialogOpen = ref(false)
const editing = ref<Partial<SlotUserRow> | null>(null)

async function reload() {
  loading.value = true
  try {
    const { data } = await pageSlotUser({
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

function openEdit(row: SlotUserRow) {
  editing.value = { ...row }
  dialogOpen.value = true
}

async function handleSave() {
  if (!editing.value) return
  if (!editing.value.name) {
    ElMessage.warning('姓名必填')
    return
  }
  saving.value = true
  try {
    if (editing.value.id) {
      await updateSlotUser(editing.value.id, editing.value as SlotUserRow)
    } else {
      await createSlotUser(editing.value as SlotUserRow)
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
  await deleteSlotUser(id)
  ElMessage.success('已删除')
  await reload()
}

async function handleToggle(row: SlotUserRow, enabled: boolean) {
  if (!row.id) return
  await updateSlotUser(row.id, { ...row, enabled })
  await reload()
}

async function onFileSelected(file: UploadFile) {
  if (!file.raw) return
  try {
    const { data } = await importSlotUser(file.raw)
    ElMessage.success(`导入完成：成功 ${data.ok}，跳过 ${data.skip}`)
    await reload()
  } catch (ex) {
    /* request interceptor */
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
.header-actions { display: flex; gap: 8px; align-items: center; }
</style>
