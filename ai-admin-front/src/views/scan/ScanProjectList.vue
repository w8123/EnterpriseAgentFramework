<template>
  <div class="page-container">
    <div class="page-header">
      <h2>扫描项目</h2>
      <div class="header-actions">
        <el-button type="primary" @click="openCreateDialog">
          <el-icon><Plus /></el-icon>新建项目
        </el-button>
        <el-button :loading="loading" @click="fetchProjects">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
      </div>
    </div>

    <el-card shadow="never">
      <el-table :data="projects" v-loading="loading" stripe>
        <el-table-column prop="name" label="项目名" min-width="180" />
        <el-table-column prop="baseUrl" label="项目域名" min-width="220" />
        <el-table-column prop="scanPath" label="扫描路径" min-width="260" show-overflow-tooltip />
        <el-table-column label="扫描方式" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small">{{ row.scanType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="toolCount" label="接口数" width="90" align="center" />
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="错误信息" min-width="220">
          <template #default="{ row }">
            <span class="error-text">{{ row.errorMessage || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="goDetail(row.id)">详情</el-button>
            <el-button link type="primary" size="small" :loading="scanLoadingId === row.id" @click="handleScan(row.id)">
              {{ row.toolCount > 0 ? '重新扫描' : '扫描' }}
            </el-button>
            <el-button link type="primary" size="small" @click="openEditDialog(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && projects.length === 0" description="还没有扫描项目，先创建一个吧" />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEditMode ? `编辑项目 - ${form.name}` : '新建扫描项目'" width="720px">
      <el-form label-width="120px">
        <el-form-item label="项目名称" required>
          <el-input v-model="form.name" placeholder="如 legacy-crm" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="项目域名" required>
              <el-input v-model="form.baseUrl" placeholder="http://localhost:8602" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Context Path">
              <el-input v-model="form.contextPath" placeholder="/api" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="扫描路径" required>
          <el-input v-model="form.scanPath" placeholder="服务器上的绝对路径" />
        </el-form-item>
        <el-form-item label="扫描方式" required>
          <el-radio-group v-model="form.scanType">
            <el-radio label="openapi">OpenAPI</el-radio>
            <el-radio label="controller">Controller</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.scanType === 'openapi'" label="规范文件">
          <el-input v-model="form.specFile" placeholder="可选，相对 scanPath；留空自动发现" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import type { ScanProject, ScanProjectUpsertRequest } from '@/types/scanProject'
import {
  createScanProject,
  deleteScanProject,
  getScanProjectOperationBlockers,
  getScanProjects,
  triggerRescan,
  triggerScan,
  updateScanProject,
} from '@/api/scanProject'
import {
  formatScanProjectBlockersMessage,
  parseScanProjectBlockersFromError,
} from '@/utils/scanProjectBlockers'

const router = useRouter()
const projects = ref<ScanProject[]>([])
const loading = ref(false)
const saving = ref(false)
const scanLoadingId = ref<number | null>(null)
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const isEditMode = computed(() => editingId.value !== null)
const form = reactive<ScanProjectUpsertRequest>(createEmptyForm())

function createEmptyForm(): ScanProjectUpsertRequest {
  return {
    name: '',
    baseUrl: '',
    contextPath: '',
    scanPath: '',
    scanType: 'openapi',
    specFile: '',
  }
}

function applyForm(project: ScanProjectUpsertRequest) {
  form.name = project.name
  form.baseUrl = project.baseUrl
  form.contextPath = project.contextPath ?? ''
  form.scanPath = project.scanPath
  form.scanType = project.scanType
  form.specFile = project.specFile || ''
}

function statusTagType(status: ScanProject['status']) {
  if (status === 'scanned') return 'success'
  if (status === 'failed') return 'danger'
  if (status === 'scanning') return 'warning'
  return 'info'
}

async function fetchProjects() {
  loading.value = true
  try {
    const { data } = await getScanProjects()
    projects.value = Array.isArray(data) ? data : []
  } catch {
    projects.value = []
    ElMessage.error('加载扫描项目失败')
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  editingId.value = null
  applyForm(createEmptyForm())
  dialogVisible.value = true
}

function openEditDialog(project: ScanProject) {
  editingId.value = project.id
  applyForm({
    name: project.name,
    baseUrl: project.baseUrl,
    contextPath: project.contextPath ?? '',
    scanPath: project.scanPath,
    scanType: project.scanType,
    specFile: project.specFile || '',
  })
  dialogVisible.value = true
}

async function handleSave() {
  if (!form.name.trim() || !form.baseUrl.trim() || !form.scanPath.trim()) {
    ElMessage.warning('请填写项目名称、域名和扫描路径')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      specFile: form.scanType === 'openapi' ? form.specFile || null : null,
      contextPath: form.contextPath || '',
    }
    if (isEditMode.value && editingId.value !== null) {
      await updateScanProject(editingId.value, payload)
      ElMessage.success('扫描项目已更新')
    } else {
      await createScanProject(payload)
      ElMessage.success('扫描项目已创建')
    }
    dialogVisible.value = false
    await fetchProjects()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleScan(id: number) {
  scanLoadingId.value = id
  try {
    const { data: blockers } = await getScanProjectOperationBlockers(id)
    if (blockers.blocked) {
      await ElMessageBox.alert(formatScanProjectBlockersMessage(blockers), '操作被阻止', {
        type: 'warning',
        confirmButtonText: '知道了',
      })
      return
    }
    const current = projects.value.find((item) => item.id === id)
    const request = current && current.toolCount > 0 ? triggerRescan(id) : triggerScan(id)
    const { data } = await request
    ElMessage.success(`${current && current.toolCount > 0 ? '重新扫描' : '扫描'}完成，发现 ${data.toolCount} 个接口`)
    await fetchProjects()
    goDetail(id)
  } catch (error) {
    const bl = parseScanProjectBlockersFromError(error)
    if (bl?.blocked) {
      await ElMessageBox.alert(formatScanProjectBlockersMessage(bl), '操作被阻止', {
        type: 'warning',
        confirmButtonText: '知道了',
      })
      return
    }
    ElMessage.error((error as Error).message || '扫描失败')
    await fetchProjects()
  } finally {
    scanLoadingId.value = null
  }
}

async function handleDelete(project: ScanProject) {
  try {
    const { data: blockers } = await getScanProjectOperationBlockers(project.id)
    if (blockers.blocked) {
      await ElMessageBox.alert(formatScanProjectBlockersMessage(blockers), '无法删除', {
        type: 'warning',
        confirmButtonText: '知道了',
      })
      return
    }
    await ElMessageBox.confirm(`确认删除扫描项目 ${project.name} 吗？关联扫描工具也会一并删除。`, '删除确认', {
      type: 'warning',
    })
    await deleteScanProject(project.id)
    ElMessage.success('扫描项目已删除')
    await fetchProjects()
  } catch (error) {
    if ((error as Error).message === 'cancel') {
      return
    }
    const bl = parseScanProjectBlockersFromError(error)
    if (bl?.blocked) {
      await ElMessageBox.alert(formatScanProjectBlockersMessage(bl), '无法删除', {
        type: 'warning',
        confirmButtonText: '知道了',
      })
      return
    }
    ElMessage.error((error as Error).message || '删除失败')
  }
}

function goDetail(id: number) {
  router.push(`/scan-project/${id}`)
}

onMounted(fetchProjects)
</script>

<style scoped lang="scss">
.header-actions {
  display: flex;
  gap: 8px;
}

.error-text {
  color: #909399;
}
</style>
