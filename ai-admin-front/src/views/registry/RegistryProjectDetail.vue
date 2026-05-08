<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <el-button link @click="router.back()">返回</el-button>
        <h2>{{ project?.name || projectCode }}</h2>
        <p>注册项目概览、实例心跳与业务系统接入配置。</p>
      </div>
      <div class="actions">
        <el-button @click="refresh">刷新</el-button>
        <el-button type="primary" @click="setCurrentProject" :disabled="!project">设为当前项目</el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>项目概览</template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="项目编码">{{ project?.projectCode || projectCode }}</el-descriptions-item>
            <el-descriptions-item label="项目形态">
              <el-tag>{{ project?.projectKind || 'REGISTERED' }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="环境">{{ project?.environment || '-' }}</el-descriptions-item>
            <el-descriptions-item label="可见性">
              <el-tag>{{ project?.visibility || 'PRIVATE' }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="负责人">{{ project?.owner || '-' }}</el-descriptions-item>
            <el-descriptions-item label="能力数">{{ project?.toolCount ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="Base URL" :span="2">{{ project?.baseUrl || '-' }}</el-descriptions-item>
            <el-descriptions-item label="Context Path" :span="2">{{ project?.contextPath || '-' }}</el-descriptions-item>
          </el-descriptions>
          <div class="quick-actions">
            <el-button @click="goCapability('/tool')">查看 Tool</el-button>
            <el-button @click="goCapability('/skill')">查看 Skill</el-button>
            <el-button @click="goCapability('/agent')">查看 Agent</el-button>
          </div>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>业务系统配置示例</template>
          <el-input
            :model-value="configSnippet"
            type="textarea"
            :rows="13"
            readonly
            resize="none"
          />
        </el-card>
      </el-col>
    </el-row>

    <el-card class="section-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>实例心跳</span>
          <el-button link type="primary" @click="loadInstances">刷新实例</el-button>
        </div>
      </template>
      <el-table v-loading="loadingInstances" :data="instances" row-key="id">
        <el-table-column prop="instanceId" label="实例 ID" min-width="220" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ONLINE' ? 'success' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="host" label="Host" min-width="160" />
        <el-table-column prop="port" label="端口" width="90" />
        <el-table-column prop="appVersion" label="应用版本" width="130" />
        <el-table-column prop="sdkVersion" label="SDK 版本" width="130" />
        <el-table-column prop="lastHeartbeatAt" label="最近心跳" min-width="180" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getScanProjects } from '@/api/scanProject'
import { listRegistryProjectInstances } from '@/api/registry'
import type { ScanProject } from '@/types/scanProject'
import type { ProjectInstance } from '@/types/registry'
import { useProjectStore } from '@/store/project'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()

const projectCode = computed(() => String(route.params.projectCode || ''))
const project = ref<ScanProject | null>(null)
const instances = ref<ProjectInstance[]>([])
const loadingInstances = ref(false)

const configSnippet = computed(() => `eaf:
  registry:
    enabled: true
    url: http://ai-agent-service:8080
  project:
    code: ${project.value?.projectCode || projectCode.value}
    name: ${project.value?.name || ''}
    base-url: ${project.value?.baseUrl || 'http://your-service:8080'}
    context-path: ${project.value?.contextPath || ''}
    environment: ${project.value?.environment || 'dev'}
    owner: ${project.value?.owner || 'your-team'}
    visibility: ${project.value?.visibility || 'PRIVATE'}
  capability:
    scan-controller: true
    sync-on-startup: true`)

onMounted(refresh)

async function refresh() {
  const { data } = await getScanProjects()
  project.value = data.find((item) => item.projectCode === projectCode.value || String(item.id) === projectCode.value) || null
  projectStore.projects = data
  await loadInstances()
}

async function loadInstances() {
  if (!projectCode.value) return
  loadingInstances.value = true
  try {
    const { data } = await listRegistryProjectInstances(projectCode.value)
    instances.value = data
  } finally {
    loadingInstances.value = false
  }
}

function setCurrentProject() {
  if (!project.value) return
  projectStore.setCurrentProject(project.value.id)
  ElMessage.success(`已切换到项目：${project.value.name}`)
}

function goCapability(path: string) {
  if (project.value) {
    projectStore.setCurrentProject(project.value.id)
    router.push({ path, query: { projectId: project.value.id } })
  }
}
</script>

<style scoped lang="scss">
.page-container {
  padding: 24px;
}

.page-header,
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.page-header {
  margin-bottom: 16px;

  h2 {
    margin: 4px 0 6px;
  }

  p {
    margin: 0;
    color: var(--text-secondary);
  }
}

.actions,
.quick-actions {
  display: flex;
  gap: 8px;
}

.quick-actions {
  margin-top: 16px;
}

.section-card {
  margin-top: 16px;
}
</style>
