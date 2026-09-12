<template>
  <WorkbenchPage class="skill-center" density="compact" layout="list">
    <CollapsibleHeaderRegion :collapsed="isSkillHeaderCollapsed">
      <PageHeader
        title="Skill 管理"
        eyebrow="Agent Skills"
        description="统一管理岗位工作手册的标准包、版本与治理状态。"
        domain="agent"
        variant="overview"
        :collapsed="isSkillHeaderCollapsed"
      >
        <template #tags>
          <el-tag effect="light">岗位工作手册</el-tag>
          <el-tag type="success" effect="light">{{ publishedCount }} 个可绑定</el-tag>
          <el-tag v-if="pendingCount" type="warning" effect="light">{{ pendingCount }} 个待治理</el-tag>
        </template>
        <template #actions>
          <el-button :icon="Compass" @click="router.push('/skill-market')">Skill 市场</el-button>
          <el-button v-if="canImportSkills" type="primary" :icon="Upload" @click="openImport">导入标准包</el-button>
        </template>
      </PageHeader>

      <template #summary>
        <MetricStrip :items="metricItems" density="compact" aria-label="Skill 管理指标概览" />
      </template>
    </CollapsibleHeaderRegion>

    <DataTableShell
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      class="skill-catalog-shell workbench-list-surface"
      density="compact"
      :loading="loading"
      :empty="skills.length === 0"
      empty-description="暂无符合条件的 Skill"
      :total="skills.length"
      :page-sizes="[9, 18, 36]"
    >
      <template #toolbar>
        <FilterBar
          class="skill-filter-bar"
          density="compact"
          :loading="loading"
          @query="handleQuery"
          @reset="resetFilters"
        >
          <el-input
            v-model="search"
            clearable
            class="skill-filter-control is-keyword"
            placeholder="搜索名称、发布者或描述"
            :prefix-icon="Search"
            @clear="handleQuery"
          />
          <el-select
            v-model="statusFilter"
            clearable
            class="skill-filter-control is-status"
            placeholder="全部状态"
            @clear="handleQuery"
          >
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>

          <template #actions>
            <el-button
              v-if="hasActiveFilters"
              text
              class="skill-filter-clear"
              native-type="button"
              @click="resetFilters"
            >
              清空
            </el-button>
            <el-button type="primary" native-type="submit" :icon="Search" :loading="loading">搜索</el-button>
          </template>
        </FilterBar>
      </template>

      <div v-if="skills.length" class="skill-card-grid">
        <article
          v-for="skill in pagedSkills"
          :key="skill.id"
          class="skill-card glass-surface-card"
          tabindex="0"
          :aria-label="`查看 ${skill.displayName || skill.name} 详情`"
          @click="openDetail(skill)"
          @keydown.enter.prevent="openDetail(skill)"
          @keydown.space.prevent="openDetail(skill)"
        >
          <header class="skill-card__header">
            <div class="skill-card__mark">{{ skillInitial(skill) }}</div>
            <div class="skill-card__identity">
              <h3>{{ skill.displayName || skill.name }}</h3>
              <code>{{ skill.publisher }}/{{ skill.name }}</code>
            </div>
            <StatusTag
              v-if="skill.latestVersionStatus"
              :label="statusLabel(skill.latestVersionStatus)"
              :tone="statusTone(skill.latestVersionStatus)"
            />
          </header>

          <p class="skill-card__description">{{ skill.description || '尚未填写这份岗位工作手册的用途说明。' }}</p>

          <div class="skill-card__tags">
            <StatusTag :label="scopeLabel(skill)" tone="neutral" />
            <StatusTag
              :label="publisherTrustLabel(skill.publisher)"
              :tone="skill.publisher === 'reachai' ? 'success' : 'warning'"
            />
          </div>

          <dl class="skill-card__metrics">
            <div>
              <dt>最新版本</dt>
              <dd>{{ skill.latestVersion || '—' }}</dd>
            </div>
            <div>
              <dt>默认版本</dt>
              <dd>{{ skill.defaultVersion || '未设置' }}</dd>
            </div>
            <div>
              <dt>版本数</dt>
              <dd>{{ skill.versionCount }}</dd>
            </div>
          </dl>

        </article>
      </div>

      <template #empty>
        <div class="empty-state">
          <strong>没有匹配的 Skill</strong>
          <p>可导入任何符合 Agent Skills 标准、包含 SKILL.md 的 ZIP 包。</p>
          <el-button v-if="canImportSkills" type="primary" :icon="Upload" @click="openImport">导入标准包</el-button>
          <small v-else>当前账号拥有只读访问；请联系管理员授予 skill:import 后导入。</small>
        </div>
      </template>
    </DataTableShell>

    <AppDrawer title="Skill 详情" v-model="detailVisible" size="min(760px, 92vw)" destroy-on-close>
      <template #header>
        <div v-if="detail" class="drawer-heading">
          <div>
            <small>标准身份</small>
            <h2>{{ detail.skill.displayName || detail.skill.name }}</h2>
            <code>{{ detail.skill.publisher }}/{{ detail.skill.name }}</code>
          </div>
          <div class="header-tags">
            <StatusTag :label="scopeLabel(detail.skill)" tone="neutral" />
            <StatusTag
              :label="publisherTrustLabel(detail.skill.publisher)"
              :tone="detail.skill.publisher === 'reachai' ? 'success' : 'warning'"
            />
          </div>
        </div>
      </template>

      <div v-loading="detailLoading" class="detail-body">
        <p class="detail-description">{{ detail?.skill.description || '暂无说明' }}</p>
        <div class="boundary-note">
          <strong>运行边界</strong>
          <span>Skill 负责工作方法与资源；Capability 是业务能力资产，Tool 是调用协议，Workflow 是 GraphSpec 编排。allowed-tools 不等于授权。</span>
        </div>

        <section v-if="detail" class="version-section">
          <div class="section-heading">
            <div><small>IMMUTABLE VERSIONS</small><h3>版本与治理</h3></div>
            <span>{{ detail.versions.length }} 个版本</span>
          </div>
          <el-table :data="detail.versions" row-key="id" size="small" highlight-current-row @row-click="selectVersion">
            <el-table-column prop="version" label="版本" width="110" />
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <StatusTag :label="statusLabel(row.status)" :tone="statusTone(row.status)" />
              </template>
            </el-table-column>
            <el-table-column label="来源" width="104">
              <template #default="{ row }">{{ sourceLabel(row.sourceType) }}</template>
            </el-table-column>
            <el-table-column label="风险" min-width="120">
              <template #default="{ row }">
                <span :class="['risk-label', { warning: row.hasScripts }]">
                  {{ row.hasScripts ? '含脚本 · 需复核' : '说明与资源' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="238" align="right">
              <template #default="{ row }">
                <el-button text :icon="Download" @click.stop="downloadVersion(row)">下载</el-button>
                <el-dropdown
                  v-if="hasGovernanceAction(row)"
                  trigger="click"
                  @command="(command: string) => handleVersionCommand(command, row)"
                >
                  <el-button text type="primary" :icon="MoreFilled" @click.stop>治理</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item v-if="canReviewSelectedSkill && isReviewable(row)" command="approve">评审通过</el-dropdown-item>
                      <el-dropdown-item v-if="canReviewSelectedSkill && isReviewable(row)" command="reject">驳回</el-dropdown-item>
                      <el-dropdown-item v-if="canPublishSelectedSkill && row.status === 'APPROVED'" command="publish">发布</el-dropdown-item>
                      <el-dropdown-item v-if="canPublishSelectedSkill && row.status === 'PUBLISHED'" command="default">设为默认</el-dropdown-item>
                      <el-dropdown-item v-if="canPublishSelectedSkill && row.status === 'PUBLISHED'" command="deprecate" divided>废弃</el-dropdown-item>
                      <el-dropdown-item v-if="canPublishSelectedSkill && row.status !== 'REVOKED'" command="revoke">撤销</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section v-if="selectedVersion" class="version-inspector">
          <div class="section-heading">
            <div><small>PACKAGE EVIDENCE</small><h3>{{ selectedVersion.version }} 制品证据</h3></div>
            <span>{{ formatBytes(selectedVersion.artifactSize) }}</span>
          </div>
          <div class="evidence-grid">
            <article><span>原始 ZIP SHA-256</span><code>{{ selectedVersion.sourceSha256 }}</code></article>
            <article><span>规范化文件树 SHA-256</span><code>{{ selectedVersion.contentTreeSha256 }}</code></article>
            <article><span>License</span><strong>{{ selectedVersion.declaredLicense || '未声明' }}</strong></article>
            <article><span>Compatibility</span><strong>{{ selectedVersion.declaredCompatibility || '未声明' }}</strong></article>
          </div>
          <el-tabs v-model="inspectorTab" class="inspector-tabs">
            <el-tab-pane label="文件预览" name="files">
              <div class="file-browser">
                <el-table
                  :data="selectedFiles"
                  row-key="path"
                  size="small"
                  max-height="360"
                  highlight-current-row
                  @row-click="loadFilePreview"
                >
                  <el-table-column prop="path" label="路径" min-width="220" show-overflow-tooltip />
                  <el-table-column label="类型" width="92">
                    <template #default="{ row }">{{ fileKindLabel(row.kind) }}</template>
                  </el-table-column>
                  <el-table-column label="大小" width="88" align="right">
                    <template #default="{ row }">{{ formatBytes(row.size) }}</template>
                  </el-table-column>
                </el-table>
                <section v-loading="filePreviewLoading" class="file-preview-pane">
                  <header v-if="filePreview">
                    <div><strong>{{ filePreview.path }}</strong><small>{{ fileKindLabel(filePreview.kind) }} · {{ formatBytes(filePreview.size) }}</small></div>
                    <StatusTag v-if="filePreview.truncated" label="预览已截断" tone="warning" />
                  </header>
                  <pre v-if="filePreview?.previewable">{{ filePreview.content }}</pre>
                  <el-empty
                    v-else
                    :description="filePreview ? '该文件是二进制内容；为安全起见只展示摘要，请下载完整包离线检查。' : '点击左侧文件查看只读内容'"
                    :image-size="64"
                  />
                </section>
              </div>
            </el-tab-pane>
            <el-tab-pane label="包清单" name="manifest"><pre>{{ prettyJson(selectedVersion.packageManifest) }}</pre></el-tab-pane>
            <el-tab-pane label="校验报告" name="validation"><pre>{{ prettyJson(selectedVersion.validationReport) }}</pre></el-tab-pane>
            <el-tab-pane label="风险报告" name="risk"><pre>{{ prettyJson(selectedVersion.riskReport) }}</pre></el-tab-pane>
            <el-tab-pane label="兼容性" name="compatibility">
              <div class="compatibility-panel">
                <div class="compatibility-overview">
                  <article>
                    <span>Host 依赖</span>
                    <strong>{{ dependencyStatusLabel(compatibilityDependency) }}</strong>
                    <small>声明只用于评审，不会自动获得 Tool、MCP 或凭据权限。</small>
                  </article>
                  <article>
                    <span>真实 Host E2E</span>
                    <strong>{{ hostE2eLabel(compatibilityHostE2e) }}</strong>
                    <small>格式、适配器和真实对话证据分开记录，避免把“可读取”当成“已接入”。</small>
                  </article>
                </div>
                <div v-if="compatibilityHosts.length" class="host-compatibility-grid">
                  <article v-for="host in compatibilityHosts" :key="host.host">
                    <header>
                      <strong>{{ hostLabel(host.host) }}</strong>
                      <StatusTag :label="hostFormatLabel(host.format)" :tone="compatibilityTone(host.format)" />
                    </header>
                    <dl>
                      <div><dt>适配器</dt><dd>{{ adapterLabel(host.adapter) }}</dd></div>
                      <div><dt>证据</dt><dd>{{ evidenceLabel(host.evidence) }}</dd></div>
                      <div v-if="host.metadataStatus"><dt>Host 元数据</dt><dd>{{ metadataStatusLabel(host.metadataStatus) }}</dd></div>
                      <div v-if="host.allowImplicitInvocation != null"><dt>隐式调用</dt><dd>{{ host.allowImplicitInvocation ? '允许' : '关闭，仅显式调用' }}</dd></div>
                      <div v-if="host.toolDependencyCount"><dt>工具依赖</dt><dd>{{ host.toolDependencyCount }} 项，尚未自动解析</dd></div>
                    </dl>
                  </article>
                </div>
                <el-empty v-else description="该历史版本没有结构化 Host 兼容证据" :image-size="56" />
                <details class="raw-report">
                  <summary>查看原始兼容报告</summary>
                  <pre>{{ prettyJson(selectedVersion.compatibilityReport) }}</pre>
                </details>
              </div>
            </el-tab-pane>
            <el-tab-pane label="评审记录" name="reviews">
              <div v-loading="reviewsLoading" class="review-list">
                <article v-for="review in reviews" :key="review.id">
                  <StatusTag :label="review.decision === 'APPROVE' ? '通过' : '驳回'" :tone="review.decision === 'APPROVE' ? 'success' : 'danger'" />
                  <div><strong>{{ review.reviewer }}</strong><p>{{ review.comment || '无评审说明' }}</p></div>
                  <time>{{ formatDate(review.createdAt) }}</time>
                </article>
                <el-empty v-if="!reviewsLoading && !reviews.length" description="暂无评审记录" :image-size="64" />
              </div>
            </el-tab-pane>
          </el-tabs>
        </section>
      </div>
    </AppDrawer>

    <AppDialog
      v-model="importVisible"
      title="导入标准 Agent Skill 包"
      width="min(720px, 94vw)"
      top="5vh"
      class="skill-import-dialog"
      destroy-on-close
      @closed="resetImportPackageSelection"
    >
      <el-form label-position="top">
        <el-form-item label="ZIP 包" required>
          <label class="file-picker">
            <input type="file" accept=".zip,application/zip" @change="handleFileChange" />
            <el-icon><Upload /></el-icon>
            <span>
              <strong>{{ importFile?.name || '选择 Skill 或仓库 ZIP' }}</strong>
              <small>支持单 Skill、市场仓库与插件包；最大 {{ formatBytes(skillAccess.maxPackageBytes) }}</small>
            </span>
          </label>
        </el-form-item>
        <section v-if="importFile" v-loading="discoveryLoading" class="bundle-discovery">
          <template v-if="bundleDiscovery">
            <header>
              <div>
                <strong>
                  {{ bundleDiscovery.multiSkill
                    ? `发现 ${bundleDiscovery.candidateCount} 个 Skill，请选择一个`
                    : '已识别 1 个标准 Skill' }}
                </strong>
                <small>{{ bundleDiscovery.archiveFileCount }} 个归档文件 · Bundle SHA {{ shortDigest(bundleDiscovery.bundleSourceSha256) }}</small>
              </div>
              <StatusTag
                :label="bundleDiscovery.multiSkill ? '仓库 / 插件包' : '单 Skill 包'"
                :tone="bundleDiscovery.multiSkill ? 'info' : 'success'"
              />
            </header>
            <el-radio-group v-model="selectedCandidateRoot" class="bundle-candidate-list">
              <el-radio
                v-for="candidate in bundleDiscovery.candidates"
                :key="candidate.sourceRoot || '__archive_root__'"
                :value="candidate.sourceRoot"
                :disabled="!candidate.selectable"
                class="bundle-candidate"
              >
                <div class="bundle-candidate-body">
                  <div class="bundle-candidate-heading">
                    <strong>{{ candidateTitle(candidate) }}</strong>
                    <span>
                      <el-tag v-if="candidate.declaredVersion" size="small" effect="plain">v{{ candidate.declaredVersion }}</el-tag>
                      <el-tag v-if="candidate.selectable" size="small" effect="plain">{{ candidate.fileCount }} 个文件</el-tag>
                      <el-tag v-if="candidate.hasScripts" size="small" type="warning" effect="plain">含脚本</el-tag>
                      <el-tag v-if="!candidate.selectable" size="small" type="danger" effect="plain">不可导入</el-tag>
                    </span>
                  </div>
                  <code>{{ candidate.sourceRoot || '<archive-root>' }}</code>
                  <p :class="{ 'candidate-error': !candidate.selectable }">
                    {{ candidate.selectable ? candidate.description : candidate.errorMessage }}
                  </p>
                </div>
              </el-radio>
            </el-radio-group>
          </template>
          <div v-else-if="discoveryError" class="bundle-discovery-error">
            <span>{{ discoveryError }}</span>
            <el-button link type="primary" @click="retryBundleDiscovery">重新解析</el-button>
          </div>
          <div v-else class="bundle-discovery-placeholder">正在安全扫描 ZIP 中的 SKILL.md…</div>
        </section>
        <div class="dialog-grid">
          <el-form-item label="Publisher">
            <el-input v-model="importForm.publisher" placeholder="community" />
            <small class="field-help">当前为导入者声明，不代表平台已验证发布者身份；reachai 命名空间仅供内置包使用。</small>
          </el-form-item>
          <el-form-item label="版本"><el-input v-model="importForm.version" placeholder="优先读取包内 metadata.version" /></el-form-item>
          <el-form-item label="显示名称"><el-input v-model="importForm.displayName" placeholder="可选" /></el-form-item>
          <el-form-item label="可见范围">
            <el-select v-model="importForm.visibility" @change="handleVisibilityChange">
              <el-option label="私有" value="PRIVATE" />
              <el-option label="项目" value="PROJECT" />
              <el-option label="共享（需全局导入权限）" value="SHARED" :disabled="!canImportSharedOrPublic" />
              <el-option label="公开（需全局导入权限）" value="PUBLIC" :disabled="!canImportSharedOrPublic" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="importForm.visibility === 'PROJECT'" label="所属项目" required>
            <el-select v-model="importForm.projectCode" filterable placeholder="选择可访问项目">
              <el-option
                v-for="project in scanProjects"
                :key="project.id"
                :label="`${project.name} / ${project.projectCode || project.id}`"
                :value="project.projectCode"
                :disabled="!project.projectCode"
              />
            </el-select>
          </el-form-item>
        </div>
        <div class="import-guardrail">
          <el-icon><Lock /></el-icon>
          <span>
            仓库包会先发现候选，再只封装所选 Skill 子目录；不会带入兄弟 Skill 或仓库杂项。
            导入只做静态解析，不执行脚本。含 scripts/ 的包必须单独评审；allowed-tools 仅保留为声明。
            当前运行时脚本执行：{{ skillAccess.scriptExecutionEnabled ? '已启用受控沙箱' : '关闭' }}。
          </span>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" :disabled="!canSubmitImport" @click="submitImport">校验并导入所选 Skill</el-button>
      </template>
    </AppDialog>

    <AppDialog v-model="reviewVisible" :title="reviewDecision === 'APPROVE' ? '评审通过' : '驳回版本'" width="480px">
      <el-form label-position="top">
        <el-form-item label="评审说明">
          <el-input v-model="reviewComment" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="记录风险判断、兼容性结论或驳回原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button :type="reviewDecision === 'APPROVE' ? 'success' : 'danger'" :loading="actionLoading" @click="submitReview">
          确认{{ reviewDecision === 'APPROVE' ? '通过' : '驳回' }}
        </el-button>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { Compass, Download, Lock, MoreFilled, Search, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import CollapsibleHeaderRegion from '@/components/common/CollapsibleHeaderRegion.vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import { useCollapsiblePageHeader } from '@/composables/useCollapsiblePageHeader'
import {
  deprecateAgentSkill,
  downloadAgentSkillPackage,
  getAgentSkillAccess,
  getAgentSkillFilePreview,
  getAgentSkill,
  getAgentSkillReviews,
  importAgentSkill,
  listAgentSkills,
  publishAgentSkill,
  reviewAgentSkill,
  revokeAgentSkill,
  setDefaultAgentSkillVersion,
} from '@/api/skill'
import type {
  AgentSkillBundleCandidate,
  AgentSkillDetail,
  AgentSkillFilePreview,
  AgentSkillReview,
  AgentSkillSummary,
  AgentSkillVersion,
} from '@/types/skill'
import { getScanProjects } from '@/api/scanProject'
import type { ScanProject } from '@/types/scanProject'
import { useAgentSkillBundleImport } from './composables/useAgentSkillBundleImport'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const skills = ref<AgentSkillSummary[]>([])
const catalogSkills = ref<AgentSkillSummary[]>([])
const scanProjects = ref<ScanProject[]>([])
const defaultSkillAccess = {
  canImport: false,
  canImportSharedOrPublic: false,
  maxPackageBytes: 20 * 1024 * 1024,
  maxExpandedBytes: 100 * 1024 * 1024,
  maxSingleFileBytes: 20 * 1024 * 1024,
  maxFiles: 1024,
  maxInstructionBytes: 256 * 1024,
  scriptExecutionEnabled: false,
}
const skillAccess = ref({ ...defaultSkillAccess })
const search = ref('')
const statusFilter = ref('')
const currentPage = ref(1)
const pageSize = ref(9)
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<AgentSkillDetail | null>(null)
const selectedVersion = ref<AgentSkillVersion | null>(null)
const inspectorTab = ref('manifest')
const reviews = ref<AgentSkillReview[]>([])
const reviewsLoading = ref(false)
const filePreview = ref<AgentSkillFilePreview | null>(null)
const filePreviewLoading = ref(false)
const importVisible = ref(false)
const {
  importFile,
  bundleDiscovery,
  selectedCandidateRoot,
  discoveryLoading,
  discoveryError,
  selectedBundleCandidate,
  canSubmitImport,
  reset: resetImportPackageSelection,
  selectFile: selectImportFile,
  retry: retryBundleDiscovery,
} = useAgentSkillBundleImport()
const importing = ref(false)
const actionLoading = ref(false)
const reviewVisible = ref(false)
const reviewDecision = ref<'APPROVE' | 'REJECT'>('APPROVE')
const reviewComment = ref('')
const reviewTarget = ref<AgentSkillVersion | null>(null)
const importForm = reactive({
  publisher: 'community',
  version: '',
  displayName: '',
  visibility: 'PRIVATE',
  projectCode: '',
})

const statusOptions = [
  { label: '待评审', value: 'REVIEW_PENDING' },
  { label: '已通过', value: 'APPROVED' },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '已驳回', value: 'REJECTED' },
  { label: '已废弃', value: 'DEPRECATED' },
  { label: '已撤销', value: 'REVOKED' },
]
const publishedCount = computed(() => catalogSkills.value.filter(item => item.latestVersionId != null).length)
const pendingCount = computed(() => catalogSkills.value.filter(item => ['REVIEW_PENDING', 'REJECTED'].includes(item.latestVersionStatus || '')).length)
const hasActiveFilters = computed(() => Boolean(search.value.trim() || statusFilter.value))
const pagedSkills = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return skills.value.slice(start, start + pageSize.value)
})
const metricItems = computed<MetricStripItem[]>(() => [
  {
    key: 'skill-total',
    label: '目录 Skill',
    value: catalogSkills.value.length,
    hint: '标准身份与独立治理范围',
    iconKey: 'workflow-manual',
    tone: 'brand',
  },
  {
    key: 'skill-bindable',
    label: '可绑定',
    value: publishedCount.value,
    hint: '可用于 Agent 配置',
    iconKey: 'agent-ready',
    tone: 'success',
  },
  {
    key: 'skill-governance',
    label: '待治理',
    value: pendingCount.value,
    hint: pendingCount.value ? '待评审或已驳回版本' : '当前没有待处理版本',
    iconKey: 'workflow-published',
    tone: pendingCount.value ? 'warning' : 'neutral',
  },
  {
    key: 'skill-format',
    label: '标准包格式',
    value: 'ZIP',
    hint: 'SKILL.md 与配套资源',
    iconKey: 'sdk',
    tone: 'info',
  },
])
const canImportSkills = computed(() => skillAccess.value.canImport)
const canImportSharedOrPublic = computed(() => skillAccess.value.canImportSharedOrPublic)
const canReviewSelectedSkill = computed(() => detail.value?.actions?.canReview === true)
const canPublishSelectedSkill = computed(() => detail.value?.actions?.canPublish === true)

const {
  collapsed: isSkillHeaderCollapsed,
  refreshScrollTargets: refreshSkillHeaderScrollTargets,
} = useCollapsiblePageHeader({
  rootSelector: '.skill-center',
})

watch([() => skills.value.length, pageSize], () => {
  const lastPage = Math.max(1, Math.ceil(skills.value.length / pageSize.value))
  if (currentPage.value > lastPage) currentPage.value = lastPage
})
interface SkillManifestFileRow { path: string; kind: string; size: number; sha256: string }
interface HostCompatibilityRow {
  host: string
  format: string
  adapter: string
  evidence: string
  metadataStatus: string
  allowImplicitInvocation: boolean | null
  toolDependencyCount: number
}
const selectedFiles = computed<SkillManifestFileRow[]>(() => {
  const files = selectedVersion.value?.packageManifest?.files
  if (!Array.isArray(files)) return []
  return files
    .filter((value): value is Record<string, unknown> => Boolean(value) && typeof value === 'object')
    .map(value => ({
      path: String(value.path || ''),
      kind: String(value.kind || 'OTHER'),
      size: Number(value.size || 0),
      sha256: String(value.sha256 || ''),
    }))
    .filter(value => Boolean(value.path))
})
const compatibilityReport = computed(() => objectValue(selectedVersion.value?.compatibilityReport))
const compatibilityDependency = computed(() => String(compatibilityReport.value?.dependency || 'NOT_EVALUATED'))
const compatibilityHostE2e = computed(() => String(compatibilityReport.value?.hostE2e || 'NOT_RUN'))
const compatibilityHosts = computed<HostCompatibilityRow[]>(() => {
  const hosts = objectValue(compatibilityReport.value?.hosts)
  if (hosts) {
    return Object.entries(hosts).flatMap(([host, raw]) => {
      const value = objectValue(raw)
      if (!value) return []
      const metadata = objectValue(value.hostMetadata)
      return [{
        host,
        format: String(value.format || 'UNKNOWN'),
        adapter: String(value.adapter || 'UNKNOWN'),
        evidence: String(value.evidence || 'UNKNOWN'),
        metadataStatus: String(metadata?.status || ''),
        allowImplicitInvocation: typeof metadata?.allowImplicitInvocation === 'boolean'
          ? metadata.allowImplicitInvocation
          : null,
        toolDependencyCount: Number(metadata?.toolDependencyCount || 0),
      }]
    })
  }
  const legacyHarness = objectValue(compatibilityReport.value?.harness)
  if (!legacyHarness) return []
  return Object.entries(legacyHarness).map(([host, format]) => ({
    host,
    format: String(format || 'UNKNOWN'),
    adapter: 'UNKNOWN',
    evidence: 'HISTORICAL_REPORT',
    metadataStatus: '',
    allowImplicitInvocation: null,
    toolDependencyCount: 0,
  }))
})

async function loadSkillAccess() {
  try {
    const { data } = await getAgentSkillAccess()
    skillAccess.value = { ...defaultSkillAccess, ...data }
  } catch {
    skillAccess.value = { ...defaultSkillAccess }
  }
}

async function loadSkills() {
  loading.value = true
  try {
    const normalizedSearch = search.value.trim()
    const hasFilters = Boolean(normalizedSearch || statusFilter.value)
    const resultRequest = listAgentSkills({
      search: normalizedSearch || undefined,
      status: statusFilter.value || undefined,
    })
    const catalogRequest = hasFilters ? listAgentSkills() : resultRequest
    const [{ data: resultData }, { data: catalogData }] = await Promise.all([resultRequest, catalogRequest])
    skills.value = Array.isArray(resultData) ? resultData : []
    catalogSkills.value = Array.isArray(catalogData) ? catalogData : []
  } finally {
    loading.value = false
    await nextTick()
    refreshSkillHeaderScrollTargets()
  }
}

async function handleQuery() {
  currentPage.value = 1
  await loadSkills()
}

async function resetFilters() {
  search.value = ''
  statusFilter.value = ''
  currentPage.value = 1
  await loadSkills()
}

async function loadProjects() {
  try {
    const { data } = await getScanProjects()
    scanProjects.value = Array.isArray(data) ? data : []
  } catch {
    scanProjects.value = []
  }
}

async function openDetail(row: AgentSkillSummary) {
  detailVisible.value = true
  detailLoading.value = true
  try {
    const { data } = await getAgentSkill(row.id)
    detail.value = data
    await selectVersion(data.versions[0] || null)
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail() {
  if (!detail.value) return
  const id = detail.value.skill.id
  const currentId = selectedVersion.value?.id
  const { data } = await getAgentSkill(id)
  detail.value = data
  await selectVersion(data.versions.find(item => item.id === currentId) || data.versions[0] || null)
  await loadSkills()
}

async function selectVersion(version: AgentSkillVersion | null) {
  selectedVersion.value = version
  filePreview.value = null
  inspectorTab.value = 'files'
  reviews.value = []
  if (!version || !detail.value) return
  reviewsLoading.value = true
  try {
    const { data } = await getAgentSkillReviews(detail.value.skill.id, version.id)
    reviews.value = Array.isArray(data) ? data : []
  } finally {
    reviewsLoading.value = false
  }
}

async function loadFilePreview(file: SkillManifestFileRow) {
  if (!detail.value || !selectedVersion.value || !file?.path) return
  filePreviewLoading.value = true
  filePreview.value = null
  try {
    const { data } = await getAgentSkillFilePreview(
      detail.value.skill.id,
      selectedVersion.value.id,
      file.path,
    )
    filePreview.value = data
  } catch {
    ElMessage.error('Skill 文件预览加载失败')
  } finally {
    filePreviewLoading.value = false
  }
}

function openImport() {
  if (!canImportSkills.value) {
    ElMessage.warning('当前账号没有 Skill 导入权限')
    return
  }
  resetImportPackageSelection()
  Object.assign(importForm, {
    publisher: 'community',
    version: '',
    displayName: '',
    visibility: 'PRIVATE',
    projectCode: '',
  })
  importVisible.value = true
}

function handleVisibilityChange() {
  if (importForm.visibility !== 'PROJECT') importForm.projectCode = ''
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] || null
  if (file && file.size > skillAccess.value.maxPackageBytes) {
    resetImportPackageSelection()
    input.value = ''
    ElMessage.warning(`Skill ZIP 不能超过 ${formatBytes(skillAccess.value.maxPackageBytes)}`)
    return
  }
  await selectImportFile(file)
}

async function submitImport() {
  if (!importFile.value || !selectedBundleCandidate.value) {
    ElMessage.warning('请先选择一个可导入的 Skill')
    return
  }
  if (importForm.visibility === 'PROJECT' && !importForm.projectCode) {
    ElMessage.warning('请选择项目范围')
    return
  }
  importing.value = true
  try {
    const { data } = await importAgentSkill(importFile.value, {
      ...importForm,
      sourceRoot: selectedBundleCandidate.value.sourceRoot || undefined,
    })
    ElMessage.success(data.created ? 'Skill 包已导入，等待评审' : '相同版本与摘要已存在')
    importVisible.value = false
    await loadSkills()
    await openDetail(data.skill)
  } finally {
    importing.value = false
  }
}

function isReviewable(version: AgentSkillVersion) {
  return ['REVIEW_PENDING', 'REJECTED'].includes(version.status)
}

function hasGovernanceAction(version: AgentSkillVersion) {
  return (canReviewSelectedSkill.value && isReviewable(version))
    || (canPublishSelectedSkill.value && version.status !== 'REVOKED')
}

async function handleVersionCommand(command: string, version: AgentSkillVersion) {
  if (!detail.value) return
  if (command === 'approve' || command === 'reject') {
    reviewDecision.value = command === 'approve' ? 'APPROVE' : 'REJECT'
    reviewTarget.value = version
    reviewComment.value = ''
    reviewVisible.value = true
    return
  }
  const skillId = detail.value.skill.id
  if (command === 'revoke' || command === 'deprecate') {
    const label = command === 'revoke' ? '撤销' : '废弃'
    await ElMessageBox.confirm(
      command === 'revoke'
        ? '撤销后，任何 Agent 在后续运行需要加载该版本时都会被阻止，即使 Runtime 已有缓存；可选 Skill 会安全跳过并记录 Trace。'
        : '废弃后不允许新 Agent 绑定，但已有发布配置仍可复现运行。',
      `确认${label} ${version.version}？`,
      { type: 'warning', confirmButtonText: `确认${label}` },
    )
  }
  actionLoading.value = true
  try {
    if (command === 'publish') await publishAgentSkill(skillId, version.id)
    if (command === 'default') await setDefaultAgentSkillVersion(skillId, version.id)
    if (command === 'deprecate') await deprecateAgentSkill(skillId, version.id)
    if (command === 'revoke') await revokeAgentSkill(skillId, version.id)
    ElMessage.success('版本状态已更新')
    await refreshDetail()
  } finally {
    actionLoading.value = false
  }
}

async function submitReview() {
  if (!detail.value || !reviewTarget.value) return
  actionLoading.value = true
  try {
    await reviewAgentSkill(detail.value.skill.id, reviewTarget.value.id, {
      decision: reviewDecision.value,
      comment: reviewComment.value || undefined,
    })
    ElMessage.success(reviewDecision.value === 'APPROVE' ? '评审已通过' : '版本已驳回')
    reviewVisible.value = false
    await refreshDetail()
  } finally {
    actionLoading.value = false
  }
}

async function downloadVersion(version: AgentSkillVersion) {
  if (!detail.value) return
  const { data } = await downloadAgentSkillPackage(detail.value.skill.id, version.id)
  const url = URL.createObjectURL(data)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${detail.value.skill.name}-${version.version}.zip`
  anchor.click()
  URL.revokeObjectURL(url)
}

function statusLabel(status?: string | null) {
  return ({ REVIEW_PENDING: '待评审', APPROVED: '已通过', REJECTED: '已驳回', PUBLISHED: '已发布', DEPRECATED: '已废弃', REVOKED: '已撤销' } as Record<string, string>)[status || ''] || status || '未知'
}
function statusTone(status?: string | null) {
  return ({ REVIEW_PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', PUBLISHED: 'success', DEPRECATED: 'info', REVOKED: 'danger' } as Record<string, 'neutral' | 'success' | 'warning' | 'danger' | 'info'>)[status || ''] || 'neutral'
}
function visibilityLabel(value: string) { return ({ PRIVATE: '私有', PROJECT: '项目', SHARED: '共享', PUBLIC: '公开' } as Record<string, string>)[value] || value }
function scopeLabel(skill: AgentSkillSummary) {
  return skill.visibility === 'PROJECT' && skill.projectCode
    ? `项目 · ${skill.projectCode}`
    : visibilityLabel(skill.visibility)
}
function skillInitial(skill: AgentSkillSummary) {
  const name = (skill.displayName || skill.name || 'S').trim()
  return Array.from(name)[0]?.toUpperCase() || 'S'
}
function publisherTrustLabel(publisher: string) { return publisher === 'reachai' ? '内置可信' : '发布者未验证' }
function sourceLabel(value: string) { return ({ UPLOAD: '上传', GIT: 'Git', MARKET: '市场', BUILTIN: '内置' } as Record<string, string>)[value] || value }
function objectValue(value: unknown): Record<string, unknown> | null {
  return value != null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}
function hostLabel(value: string) { return ({ AGENTSCOPE: 'AgentScope', CODEX: 'Codex', OPENCODE: 'OpenCode' } as Record<string, string>)[value] || value }
function hostFormatLabel(value: string) { return ({ COMPATIBLE: '格式兼容', INCOMPATIBLE: '不兼容', UNKNOWN: '未知' } as Record<string, string>)[value] || value }
function adapterLabel(value: string) { return ({ IMPLEMENTED: '已实现', NOT_IMPLEMENTED: '未实现', UNKNOWN: '未知' } as Record<string, string>)[value] || value }
function evidenceLabel(value: string) { return ({ UNIT_VERIFIED: '单元验证', FORMAT_VERIFIED: '格式验证', HOST_METADATA_INVALID: 'Host 元数据无效', HISTORICAL_REPORT: '历史报告', UNKNOWN: '未知' } as Record<string, string>)[value] || value }
function metadataStatusLabel(value: string) { return ({ VALID: '有效', INVALID: '无效', ABSENT: '未提供' } as Record<string, string>)[value] || value }
function dependencyStatusLabel(value: string) { return ({ NONE_DECLARED: '未声明依赖', DECLARED_NOT_RESOLVED: '已声明，尚未解析', HOST_METADATA_INVALID: 'Host 元数据无效', NOT_EVALUATED: '尚未评估' } as Record<string, string>)[value] || value }
function hostE2eLabel(value: string) { return ({ NOT_RUN: '未执行', PASSED: '已通过', FAILED: '失败' } as Record<string, string>)[value] || value }
function compatibilityTone(value: string): 'neutral' | 'success' | 'warning' | 'danger' | 'info' {
  return value === 'COMPATIBLE' ? 'success' : value === 'INCOMPATIBLE' ? 'danger' : 'neutral'
}
function fileKindLabel(value: string) {
  return ({ INSTRUCTIONS: '说明', REFERENCE: '参考', ASSET: '资源', SCRIPT: '脚本', HOST_METADATA: 'Host 元数据', OTHER: '其他' } as Record<string, string>)[value] || value
}
function prettyJson(value: unknown) { return JSON.stringify(value || {}, null, 2) }
function candidateTitle(candidate: AgentSkillBundleCandidate) {
  if (candidate.name) return candidate.name
  if (!candidate.sourceRoot) return '归档根目录 Skill'
  return candidate.sourceRoot.substring(candidate.sourceRoot.lastIndexOf('/') + 1)
}
function shortDigest(value?: string | null) { return value ? `${value.slice(0, 12)}…` : '—' }
function formatDate(value?: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function formatBytes(value?: number | null) {
  const size = Number(value || 0)
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

async function openRoutedSkill() {
  const skillId = Number(route.query.skillId)
  if (!Number.isInteger(skillId) || skillId <= 0) return
  detailVisible.value = true
  detailLoading.value = true
  try {
    const { data } = await getAgentSkill(skillId)
    detail.value = data
    const versionId = Number(route.query.versionId)
    const target = data.versions.find(version => version.id === versionId) || data.versions[0] || null
    await selectVersion(target)
  } catch {
    detailVisible.value = false
    ElMessage.error('无法打开市场导入的 Skill 版本')
  } finally {
    detailLoading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadSkills(), loadProjects(), loadSkillAccess()])
  await openRoutedSkill()
})
</script>

<style scoped lang="scss">
.skill-center {
  min-width: 0;
}

.skill-catalog-shell {
  min-height: 0;
}

.skill-catalog-shell :deep(.data-table-shell__toolbar) {
  display: block;
}

.skill-catalog-shell :deep(.data-table-shell__body) {
  overflow: visible;
}

.skill-filter-bar {
  width: 100%;
  box-sizing: border-box;
  padding: 10px 12px;
  border-color: color-mix(in srgb, var(--border-readable) 82%, transparent);
  border-radius: 16px;
  background: color-mix(in srgb, var(--surface-solid-control) 72%, transparent);
  box-shadow: var(--inner-highlight);
}

.skill-filter-bar :deep(.filter-bar__fields) {
  flex-wrap: nowrap;
  gap: 0;
  overflow: hidden;
  border: 1px solid var(--border-readable);
  border-radius: 11px;
  background: color-mix(in srgb, var(--surface-solid-control) 78%, transparent);
  box-shadow: var(--inner-highlight);
}

.skill-filter-control.is-keyword {
  width: min(560px, 100%);
  flex: 1 1 320px;
}

.skill-filter-control.is-status {
  width: 166px;
  flex: 0 0 166px;
  border-inline-start: 1px solid var(--border-divider);
}

.skill-filter-control.is-keyword :deep(.el-input__wrapper),
.skill-filter-control.is-status :deep(.el-input__wrapper),
.skill-filter-control.is-status :deep(.el-select__wrapper) {
  min-height: 36px;
  border-radius: 0;
  background: transparent;
  box-shadow: none !important;
}

.skill-filter-bar :deep(.filter-bar__actions) {
  gap: 6px;
}

.skill-filter-clear {
  padding-inline: 8px;
  color: var(--text-muted);
}

.skill-filter-bar :deep(.filter-bar__actions .el-button--primary) {
  min-width: 88px;
  min-height: 38px;
  border-radius: 10px;
  box-shadow: 0 10px 22px -16px rgb(var(--brand-primary-rgb) / 0.72), var(--inner-highlight);
}

.skill-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(310px, 1fr));
  gap: var(--section-gap);
}

.skill-card {
  position: relative;
  isolation: isolate;
  display: flex;
  min-width: 0;
  min-height: 228px;
  flex-direction: column;
  overflow: hidden;
  padding: var(--panel-padding);
  border: 1px solid color-mix(in srgb, var(--border-readable) 76%, transparent);
  border-radius: var(--radius-lg);
  background:
    linear-gradient(
      145deg,
      color-mix(in srgb, var(--surface-solid-control) 88%, transparent),
      rgb(var(--brand-primary-rgb) / 0.045)
    );
  box-shadow: 0 20px 42px -34px rgb(var(--brand-primary-rgb) / 0.44), var(--inner-highlight);
  -webkit-backdrop-filter: blur(22px) saturate(1.18);
  backdrop-filter: blur(22px) saturate(1.18);
  cursor: pointer;
  transition:
    border-color var(--motion-duration-fast) ease,
    box-shadow var(--motion-duration-fast) ease,
    transform var(--motion-duration-fast) ease;
}

.skill-card::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 0;
  border-radius: inherit;
  background:
    linear-gradient(125deg, color-mix(in srgb, var(--surface-solid-control) 44%, transparent), transparent 40%),
    radial-gradient(circle at 100% 0%, rgb(var(--brand-primary-rgb) / 0.09), transparent 42%);
  pointer-events: none;
}

.skill-card > * {
  position: relative;
  z-index: 1;
}

.skill-card:hover {
  border-color: color-mix(in srgb, var(--brand-primary) 48%, var(--border-readable));
  box-shadow: 0 24px 50px -32px rgb(var(--brand-primary-rgb) / 0.54), var(--inner-highlight);
  transform: translateY(-2px);
}

.skill-card:focus-visible {
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 2px;
}

.skill-card__header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.skill-card__header :deep(.status-tag) {
  flex-shrink: 0;
}

.skill-card__mark {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  flex: 0 0 42px;
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  color: var(--brand-active);
  background: color-mix(in srgb, var(--surface-solid-control) 62%, transparent);
  box-shadow: var(--inner-highlight);
  -webkit-backdrop-filter: blur(10px) saturate(1.12);
  backdrop-filter: blur(10px) saturate(1.12);
  font-size: 16px;
  font-weight: 700;
}

.skill-card__identity {
  min-width: 0;
  flex: 1;
}

.skill-card__identity h3,
.skill-card__identity code {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-card__identity h3 {
  margin: 0;
  color: var(--text-primary);
  font-size: 16px;
  line-height: 1.35;
}

.skill-card__identity code {
  margin-top: 4px;
  color: var(--brand-active);
  font-size: 11px;
}

.skill-card__description {
  display: -webkit-box;
  min-height: 40px;
  margin: 12px 0 0;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.skill-card__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.skill-card__metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 12px 0 0;
  padding: 11px 0 0;
  border-top: 1px solid var(--border-divider);
}

.skill-card__metrics div {
  min-width: 0;
}

.skill-card__metrics dt,
.skill-card__metrics dd {
  overflow: hidden;
  margin: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-card__metrics dt {
  color: var(--text-muted);
  font-size: 11px;
}

.skill-card__metrics dd {
  margin-top: 4px;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 650;
}

.drawer-heading code { color: var(--el-color-primary); }
.section-heading small, .drawer-heading small { color: var(--el-text-color-secondary); }
.empty-state { display: grid; justify-items: center; gap: 8px; padding: 40px 30px; color: var(--el-text-color-secondary); }
.empty-state p { margin: 0 0 6px; }
.drawer-heading { display: flex; justify-content: space-between; align-items: center; width: 100%; gap: 16px; }
.header-tags { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.drawer-heading h2, .section-heading h3 { margin: 2px 0; color: var(--el-text-color-primary); }
.detail-body { display: grid; gap: 20px; }
.detail-description { margin: 0; color: var(--el-text-color-regular); }
.boundary-note, .import-guardrail { display: flex; gap: 10px; align-items: flex-start; padding: 12px 14px; border: 1px solid var(--el-color-primary-light-7); border-radius: 10px; background: var(--el-color-primary-light-9); }
.boundary-note { flex-direction: column; }
.boundary-note span, .import-guardrail span { color: var(--el-text-color-regular); line-height: 1.6; }
.version-section, .version-inspector { display: grid; gap: 12px; }
.section-heading { display: flex; align-items: end; justify-content: space-between; gap: 12px; }
.section-heading > span { color: var(--el-text-color-secondary); }
.risk-label.warning { color: var(--el-color-warning); font-weight: 600; }
.evidence-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.evidence-grid article { display: grid; gap: 6px; min-width: 0; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; }
.evidence-grid span { color: var(--el-text-color-secondary); }
.evidence-grid code { overflow-wrap: anywhere; font-size: 11px; }
.inspector-tabs pre { max-height: 360px; margin: 0; padding: 14px; overflow: auto; border-radius: 10px; background: var(--el-fill-color-light); color: var(--el-text-color-primary); font-size: 12px; line-height: 1.55; }
.compatibility-panel { display: grid; gap: 12px; }
.compatibility-overview, .host-compatibility-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.compatibility-overview article, .host-compatibility-grid article { display: grid; gap: 8px; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-bg-color); }
.compatibility-overview span, .compatibility-overview small, .host-compatibility-grid dt { color: var(--el-text-color-secondary); }
.compatibility-overview small { line-height: 1.5; }
.host-compatibility-grid header { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.host-compatibility-grid dl { display: grid; gap: 6px; margin: 0; }
.host-compatibility-grid dl div { display: grid; grid-template-columns: 76px 1fr; gap: 8px; }
.host-compatibility-grid dt, .host-compatibility-grid dd { margin: 0; }
.raw-report { border: 1px solid var(--el-border-color-lighter); border-radius: 10px; overflow: hidden; }
.raw-report summary { padding: 10px 12px; cursor: pointer; color: var(--el-text-color-regular); background: var(--el-fill-color-lighter); }
.raw-report pre { border-radius: 0; }
.file-browser { display: grid; grid-template-columns: minmax(280px, .9fr) minmax(320px, 1.1fr); gap: 12px; min-height: 360px; }
.file-browser :deep(.el-table__row) { cursor: pointer; }
.file-preview-pane { min-width: 0; min-height: 340px; overflow: hidden; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-fill-color-lighter); }
.file-preview-pane header { display: flex; justify-content: space-between; gap: 12px; padding: 10px 12px; border-bottom: 1px solid var(--el-border-color-lighter); background: var(--el-bg-color); }
.file-preview-pane header > div { display: grid; min-width: 0; gap: 2px; }
.file-preview-pane header strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-preview-pane header small { color: var(--el-text-color-secondary); }
.file-preview-pane pre { max-height: 390px; border-radius: 0; background: transparent; }
.review-list { display: grid; gap: 8px; min-height: 80px; }
.review-list article { display: grid; grid-template-columns: auto 1fr auto; gap: 10px; align-items: start; padding: 10px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; }
.review-list p { margin: 4px 0 0; color: var(--el-text-color-secondary); }
.review-list time { color: var(--el-text-color-secondary); font-size: 12px; }
.file-picker { display: flex; width: 100%; box-sizing: border-box; gap: 12px; align-items: center; padding: 18px; border: 1px dashed var(--el-border-color); border-radius: 12px; cursor: pointer; background: var(--el-fill-color-lighter); }
.file-picker input { display: none; }
.file-picker .el-icon { font-size: 24px; color: var(--el-color-primary); }
.file-picker span { display: grid; gap: 3px; }
.file-picker small { color: var(--el-text-color-secondary); }
.bundle-discovery { min-height: 72px; margin: 0 0 18px; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 12px; background: var(--el-fill-color-lighter); }
.bundle-discovery > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.bundle-discovery > header > div { display: grid; gap: 3px; min-width: 0; }
.bundle-discovery > header small, .bundle-discovery-placeholder { color: var(--el-text-color-secondary); }
.bundle-candidate-list { display: grid; width: 100%; max-height: 290px; gap: 8px; overflow: auto; }
.bundle-candidate { width: 100%; height: auto; box-sizing: border-box; margin: 0; padding: 10px 12px; border: 1px solid var(--el-border-color); border-radius: 10px; background: var(--el-bg-color); white-space: normal; }
.bundle-candidate.is-checked { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.bundle-candidate :deep(.el-radio__label) { width: 100%; min-width: 0; padding-left: 10px; }
.bundle-candidate-body { display: grid; min-width: 0; gap: 5px; }
.bundle-candidate-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.bundle-candidate-heading > span { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 5px; }
.bundle-candidate-body code { overflow: hidden; color: var(--el-text-color-secondary); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.bundle-candidate-body p { margin: 0; color: var(--el-text-color-regular); line-height: 1.45; }
.bundle-candidate-body .candidate-error { color: var(--el-color-danger); }
.bundle-discovery-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: var(--el-color-danger); }
.field-help { display: block; margin-top: 5px; color: var(--el-text-color-secondary); line-height: 1.45; }
.dialog-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 14px; }
:global(.skill-import-dialog .el-dialog__body) { max-height: calc(90vh - 142px); overflow-y: auto; }
@media (max-width: 760px) {
  .file-browser,
  .skill-card-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 640px) {
  .evidence-grid,
  .dialog-grid,
  .compatibility-overview,
  .host-compatibility-grid {
    grid-template-columns: 1fr;
  }

  .skill-filter-bar :deep(.filter-bar__fields) {
    flex-wrap: wrap;
    gap: 8px;
    overflow: visible;
    border: 0;
    background: transparent;
    box-shadow: none;
  }

  .skill-filter-control.is-keyword,
  .skill-filter-control.is-status {
    width: 100%;
    flex-basis: 100%;
    overflow: hidden;
    border: 1px solid var(--border-readable);
    border-radius: 10px;
  }

  .skill-filter-control.is-keyword :deep(.el-input__wrapper),
  .skill-filter-control.is-status :deep(.el-input__wrapper),
  .skill-filter-control.is-status :deep(.el-select__wrapper) {
    border-radius: 9px;
  }
}
</style>
