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
              <el-tag>{{ formatProjectKindLabel(project?.projectKind || 'REGISTERED') }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="环境">{{ project?.environment || '-' }}</el-descriptions-item>
            <el-descriptions-item label="可见性">
              <el-tag>{{ formatVisibilityLabel(project?.visibility || 'PRIVATE') }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="负责人">{{ project?.owner || '-' }}</el-descriptions-item>
            <el-descriptions-item label="能力数">{{ project?.toolCount ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="根地址" :span="2">{{ project?.baseUrl || '-' }}</el-descriptions-item>
            <el-descriptions-item label="Context Path" :span="2">{{ project?.contextPath || '-' }}</el-descriptions-item>
          </el-descriptions>
          <div class="quick-actions">
            <el-button type="primary" @click="goApiCatalog" :disabled="!project?.id">API 目录与 Tool 关联</el-button>
            <el-button @click="goCapabilitySync">能力变更评审</el-button>
            <el-button @click="goCapability('/tool')">查看 Tool</el-button>
            <el-button @click="goCapability('/skill')">查看 Skill</el-button>
            <el-button @click="goCapability('/agent')">查看 Agent</el-button>
          </div>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>
            <div class="config-example-header">
              <span>业务系统配置示例</span>
              <el-button type="primary" link :icon="DocumentCopy" @click="copyConfigSnippet">复制</el-button>
            </div>
          </template>
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

    <el-card v-if="project?.id" class="section-card registry-scan-link-card" shadow="never">
      <template #header>
        <span>接口与参数说明来源</span>
      </template>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="配置入口已统一到 API 接口目录页"
        description="「接口与参数说明来源」等与 SDK / 离线扫描共用的 scan_settings，请在「API 目录与 Tool 关联」页面顶部折叠面板「扫描与接口说明设置」中编辑并保存。保存后业务系统 SDK 下次同步能力时将按新规则解析。"
      />
      <div class="registry-scan-link-actions">
        <el-button type="primary" @click="goApiCatalog">前往 API 接口目录页</el-button>
      </div>
    </el-card>

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
import { DocumentCopy } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getScanProjects, getScanProjectDetail } from '@/api/scanProject'
import { listRegistryProjectInstances } from '@/api/registry'
import type { ScanProject } from '@/types/scanProject'
import type { ProjectInstance } from '@/types/registry'
import { useProjectStore } from '@/store/project'
import { formatProjectKindLabel, formatVisibilityLabel } from '@/utils/projectLabels'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()

const projectCode = computed(() => String(route.params.projectCode || ''))
const project = ref<ScanProject | null>(null)
const instances = ref<ProjectInstance[]>([])
const loadingInstances = ref(false)

/** 与 ai-agent-service 实际监听地址一致，由 VITE_AI_AGENT_SERVICE_URL 注入，默认本地 8603 */
const agentServiceBaseUrl = computed(() => {
  const raw = import.meta.env.VITE_AI_AGENT_SERVICE_URL?.trim()
  const fallback = 'http://localhost:8603'
  if (!raw) return fallback
  return raw.replace(/\/$/, '')
})

const exampleBusinessBaseUrl = computed(() => {
  const raw = import.meta.env.VITE_EXAMPLE_BUSINESS_BASE_URL?.trim()
  if (raw) return raw.replace(/\/$/, '')
  return 'http://127.0.0.1:8611'
})

const configSnippet = computed(() => `eaf:
  registry:
    enabled: true
    url: ${agentServiceBaseUrl.value}
  project:
    code: ${project.value?.projectCode || projectCode.value}
    name: ${project.value?.name || ''}
    base-url: ${project.value?.baseUrl || exampleBusinessBaseUrl.value}
    context-path: ${project.value?.contextPath || ''}
    environment: ${project.value?.environment || 'dev'}
    owner: ${project.value?.owner || 'your-team'}
    visibility: ${project.value?.visibility || 'PRIVATE'}
  capability:
    scan-controller: true
    sync-on-startup: true`)

async function copyConfigSnippet() {
  try {
    await navigator.clipboard.writeText(configSnippet.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选择文本复制')
  }
}

onMounted(refresh)

async function refresh() {
  const { data } = await getScanProjects()
  const found =
    data.find((item) => item.projectCode === projectCode.value || String(item.id) === projectCode.value) || null
  projectStore.projects = data
  if (found?.id) {
    try {
      const { data: detail } = await getScanProjectDetail(found.id)
      project.value = detail
    } catch {
      project.value = found
    }
  } else {
    project.value = found
  }
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

function goApiCatalog() {
  if (!project.value?.id) return
  projectStore.setCurrentProject(project.value.id)
  router.push({ name: 'ScanProjectDetail', params: { id: String(project.value.id) } })
}

function goCapabilitySync() {
  if (!project.value?.id) return
  projectStore.setCurrentProject(project.value.id)
  router.push({ name: 'CapabilitySyncDebug' })
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

.config-example-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.registry-scan-link-actions {
  margin-top: 16px;
}
</style>
