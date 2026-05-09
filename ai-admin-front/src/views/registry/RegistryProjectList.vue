<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>AI 注册中心项目</h2>
        <p>统一查看 SDK 注册、扫描接入和混合接入的业务项目。</p>
      </div>
      <div class="page-header-actions">
        <el-button :icon="Document" @click="guideDrawerVisible = true">项目接入指南</el-button>
        <el-button type="primary" @click="openCreateDialog">创建注册项目</el-button>
      </div>
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
            <el-tag :type="kindTagType(row.projectKind)">{{ formatProjectKindLabel(row.projectKind || 'SCAN') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="environment" label="环境" width="110" />
        <el-table-column prop="visibility" label="可见性" width="120">
          <template #default="{ row }">
            <el-tag>{{ formatVisibilityLabel(row.visibility || 'PRIVATE') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="根地址" min-width="220" show-overflow-tooltip />
        <el-table-column prop="toolCount" label="能力数" width="100" />
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.status === 'failed' ? 'danger' : 'success'">{{ formatScanStatusLabel(row.status) }}</el-tag>
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
            <el-option v-for="opt in VISIBILITY_SELECT_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="根地址（Base URL）" required>
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

    <el-drawer v-model="guideDrawerVisible" title="业务系统接入指南（EAF SDK）" size="560px" destroy-on-close>
      <div class="guide-drawer">
        <el-alert
          type="info"
          show-icon
          :closable="false"
          title="接入思路"
          description="在本页创建「注册项目」后，业务系统引入 ai-spring-boot-starter，配置注册中心地址与项目信息；应用启动后会自动注册项目、上报心跳、扫描 Controller 能力并同步到平台。平台侧也可继续用「项目与 API 接入」做纯扫描。"
        />

        <h3 class="guide-h3">1. Maven 依赖</h3>
        <p class="guide-p">在业务系统根 <code>pom.xml</code> 中引入（版本与 EAF 根工程 <code>1.0.0-SNAPSHOT</code> 对齐，或改为你们私服已发布的坐标）：</p>
        <div class="guide-code-wrap">
          <el-button class="guide-copy" size="small" text type="primary" @click="copyText(mavenSnippet)">复制</el-button>
          <pre class="guide-pre">{{ mavenSnippet }}</pre>
        </div>

        <h3 class="guide-h3">2. Spring Boot 配置</h3>
        <p class="guide-p">
          将 <code>registry.url</code> 指向可访问的 <strong>ai-agent-service</strong> 根地址（示例 <code>http://localhost:8603</code>）。<code>project.code</code> 须与平台中<strong>项目编码</strong>一致；<code>app-key</code> / <code>app-secret</code> 与创建注册项目时填写的一致，用于注册与能力同步请求签名。
        </p>
        <div class="guide-code-wrap">
          <el-button class="guide-copy" size="small" text type="primary" @click="copyText(yamlSnippet)">复制</el-button>
          <pre class="guide-pre">{{ yamlSnippet }}</pre>
        </div>

        <h3 class="guide-h3">3. 能力声明（可选）</h3>
        <p class="guide-p">在对外暴露的 Controller 方法上使用 <code>@AiCapability</code>、参数上使用 <code>@AiParam</code>，便于生成更准确的工具描述与参数 schema。未标注时 Starter 仍会按 Spring MVC 映射上报基础能力。</p>
        <div class="guide-code-wrap">
          <el-button class="guide-copy" size="small" text type="primary" @click="copyText(javaSnippet)">复制</el-button>
          <pre class="guide-pre">{{ javaSnippet }}</pre>
        </div>

        <h3 class="guide-h3">4. 启动后自检</h3>
        <ul class="guide-ul">
          <li>管理端本页应能看到对应项目，<strong>详情</strong>中可查看实例心跳。</li>
          <li>若开启 <code>expose-actuator-endpoint</code>，可在业务系统访问 <code>/actuator/eaf-capabilities</code> 查看本机解析到的能力列表。</li>
          <li>在 <strong>能力变更评审</strong> 页可对 SDK 同步批次做 diff / 逐条 apply（与平台评审策略一致）。</li>
        </ul>

        <p class="guide-foot">
          更完整的背景与 API 说明见仓库内 <code>docs/AI注册中心企业级改造设计.md</code> 与根目录 <code>README.md</code>。
        </p>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Document } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getScanProjects, updateScanProject } from '@/api/scanProject'
import { registerRegistryProject } from '@/api/registry'
import type { ScanProject } from '@/types/scanProject'
import type { RegistryProjectRegisterRequest } from '@/types/registry'
import { useProjectStore } from '@/store/project'
import {
  VISIBILITY_SELECT_OPTIONS,
  formatProjectKindLabel,
  formatScanStatusLabel,
  formatVisibilityLabel,
} from '@/utils/projectLabels'

const router = useRouter()
const projectStore = useProjectStore()

const loading = ref(false)
const saving = ref(false)
const projects = ref<ScanProject[]>([])
const dialogVisible = ref(false)
const guideDrawerVisible = ref(false)
const editingProject = ref<ScanProject | null>(null)
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

const mavenSnippet = `<!-- 与 Enterprise Agent Framework 根 pom 版本一致，或改为你们私服坐标 -->
<dependency>
  <groupId>com.enterprise.ai</groupId>
  <artifactId>ai-spring-boot-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>`

const yamlSnippet = `eaf:
  registry:
    enabled: true
    # ai-agent-service 根地址，须从业务网络可达
    url: http://localhost:8603
    # 与「创建注册项目」时填写的 appKey / appSecret 一致；平台将用于签名校验
    app-key: your-app-key
    app-secret: your-app-secret
    heartbeat-interval-ms: 30000
  project:
    # 须与本平台项目「项目编码」一致
    code: your-project-code
    name: 你的业务系统名称
    base-url: http://localhost:8080
    context-path: ""
    environment: dev
    visibility: PRIVATE
  capability:
    scan-controller: true
    expose-actuator-endpoint: true
    sync-on-startup: true`

const javaSnippet = `import com.enterprise.ai.skill.AiCapability;
import com.enterprise.ai.skill.AiParam;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderApi {

    @AiCapability(
        name = "queryOrder",
        title = "查询订单",
        description = "按订单号查询订单详情"
    )
    @GetMapping("/{orderNo}")
    public OrderDTO get(@AiParam("订单号") @PathVariable String orderNo) {
        return orderService.get(orderNo);
    }
}`

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选择文本复制')
  }
}

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
    ElMessage.warning('请填写项目名称、项目编码和根地址')
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

.page-header-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.muted {
  color: var(--text-secondary);
}

.guide-drawer {
  padding-right: 8px;
}

.guide-h3 {
  margin: 20px 0 8px;
  font-size: 15px;
  font-weight: 600;
}

.guide-p {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.guide-ul {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-secondary);
}

.guide-foot {
  margin-top: 24px;
  font-size: 12px;
  color: var(--text-secondary);
}

.guide-code-wrap {
  position: relative;
}

.guide-copy {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
}

.guide-pre {
  margin: 0;
  padding: 12px 12px 12px 12px;
  max-height: 280px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.5;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
