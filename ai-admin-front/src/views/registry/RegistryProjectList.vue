<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>AI 注册中心项目</h2>
        <p>统一查看 SDK 注册、扫描接入和混合接入的业务项目。</p>
      </div>
      <el-button type="primary" @click="openCreateDialog">创建注册项目</el-button>
    </div>

    <el-card>
      <el-table v-loading="loading" :data="projects" row-key="id">
        <el-table-column prop="name" label="项目" min-width="160" />
        <el-table-column prop="projectCode" label="项目编码" min-width="150">
          <template #default="{ row }">
            <el-tag v-if="row.projectCode" type="info">{{ row.projectCode }}</el-tag>
            <span v-else class="muted">未设置</span>
          </template>
        </el-table-column>
        <el-table-column label="形态" width="120">
          <template #default="{ row }">
            <el-tag :type="kindTagType(row.projectKind)">{{ row.projectKind || 'SCAN' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="environment" label="环境" width="110" />
        <el-table-column prop="visibility" label="可见性" width="120">
          <template #default="{ row }">
            <el-tag>{{ row.visibility || 'PRIVATE' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="Base URL" min-width="220" show-overflow-tooltip />
        <el-table-column prop="toolCount" label="能力数" width="100" />
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.status === 'failed' ? 'danger' : 'success'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row)">详情</el-button>
            <el-button link @click="useProject(row)">设为当前项目</el-button>
            <el-button link @click="openEditDialog(row)">编辑</el-button>
            <el-dropdown trigger="click" @command="(cmd: string) => goCapability(row, cmd)">
              <el-button link>能力入口</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="tool">Tool</el-dropdown-item>
                  <el-dropdown-item command="skill">Skill</el-dropdown-item>
                  <el-dropdown-item command="agent">Agent</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingProject ? '编辑项目' : '创建注册项目'" width="640px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="项目名称" required>
          <el-input v-model="form.name" placeholder="如：订单中心" />
        </el-form-item>
        <el-form-item label="项目编码" required>
          <el-input v-model="form.projectCode" :disabled="!!editingProject" placeholder="如：order-service" />
        </el-form-item>
        <el-form-item label="环境">
          <el-input v-model="form.environment" placeholder="dev / test / prod" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="form.owner" />
        </el-form-item>
        <el-form-item label="可见性">
          <el-select v-model="form.visibility" style="width: 100%">
            <el-option v-for="item in visibilityOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL" required>
          <el-input v-model="form.baseUrl" placeholder="http://localhost:8080" />
        </el-form-item>
        <el-form-item label="Context Path">
          <el-input v-model="form.contextPath" placeholder="/api" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveProject">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getScanProjects, updateScanProject } from '@/api/scanProject'
import { registerRegistryProject } from '@/api/registry'
import type { ScanProject } from '@/types/scanProject'
import type { ProjectVisibility, RegistryProjectRegisterRequest } from '@/types/registry'
import { useProjectStore } from '@/store/project'

const router = useRouter()
const projectStore = useProjectStore()

const loading = ref(false)
const saving = ref(false)
const projects = ref<ScanProject[]>([])
const dialogVisible = ref(false)
const editingProject = ref<ScanProject | null>(null)
const visibilityOptions: ProjectVisibility[] = ['PRIVATE', 'PROJECT', 'SHARED', 'PUBLIC']

const form = reactive<RegistryProjectRegisterRequest>({
  projectCode: '',
  name: '',
  environment: 'dev',
  owner: '',
  visibility: 'PRIVATE',
  baseUrl: '',
  contextPath: '',
})

onMounted(loadProjects)

async function loadProjects() {
  loading.value = true
  try {
    const { data } = await getScanProjects()
    projects.value = data
    projectStore.projects = data
  } finally {
    loading.value = false
  }
}

function resetForm(project?: ScanProject) {
  form.projectCode = project?.projectCode || ''
  form.name = project?.name || ''
  form.environment = project?.environment || 'dev'
  form.owner = project?.owner || ''
  form.visibility = project?.visibility || 'PRIVATE'
  form.baseUrl = project?.baseUrl || ''
  form.contextPath = project?.contextPath || ''
}

function openCreateDialog() {
  editingProject.value = null
  resetForm()
  dialogVisible.value = true
}

function openEditDialog(project: ScanProject) {
  editingProject.value = project
  resetForm(project)
  dialogVisible.value = true
}

async function saveProject() {
  if (!form.name || !form.projectCode || !form.baseUrl) {
    ElMessage.warning('请填写项目名称、项目编码和 Base URL')
    return
  }
  saving.value = true
  try {
    if (editingProject.value) {
      await updateScanProject(editingProject.value.id, {
        name: form.name,
        projectCode: form.projectCode,
        projectKind: editingProject.value.projectKind || 'REGISTERED',
        environment: form.environment,
        owner: form.owner,
        visibility: form.visibility,
        baseUrl: form.baseUrl,
        contextPath: form.contextPath || '',
        scanPath: editingProject.value.scanPath || '',
        scanType: editingProject.value.scanType || 'auto',
        specFile: editingProject.value.specFile || null,
      })
    } else {
      await registerRegistryProject(form)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    await loadProjects()
  } finally {
    saving.value = false
  }
}

function kindTagType(kind?: string) {
  if (kind === 'REGISTERED') return 'success'
  if (kind === 'HYBRID') return 'warning'
  return 'info'
}

function goDetail(project: ScanProject) {
  const code = project.projectCode || String(project.id)
  router.push(`/registry/projects/${encodeURIComponent(code)}`)
}

function useProject(project: ScanProject) {
  projectStore.setCurrentProject(project.id)
  ElMessage.success(`已切换到项目：${project.name}`)
}

function goCapability(project: ScanProject, target: string) {
  projectStore.setCurrentProject(project.id)
  const pathMap: Record<string, string> = {
    tool: '/tool',
    skill: '/skill',
    agent: '/agent',
  }
  router.push({ path: pathMap[target], query: { projectId: project.id } })
}
</script>

<style scoped lang="scss">
.page-container {
  padding: 24px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;

  h2 {
    margin: 0 0 6px;
  }

  p {
    margin: 0;
    color: var(--text-secondary);
  }
}

.muted {
  color: var(--text-secondary);
}
</style>
