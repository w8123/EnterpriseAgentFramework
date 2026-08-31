<template>
  <WorkbenchPage class="skill-market" density="compact" layout="list">
    <CollapsibleHeaderRegion>
      <PageHeader
        title="Skill 市场"
        description="发现公开 Agent Skill，把上游仓库中的标准工作手册安全引入 ReachAI 治理链路。"
        domain="agent"
        variant="overview"
      >
        <template #tags>
          <el-tag effect="light">外部 Skill</el-tag>
          <el-tag type="success" effect="light">当前 {{ searchResult?.count || 0 }} 个结果</el-tag>
          <el-tag type="info" effect="light">{{ modeLabel }}</el-tag>
        </template>
        <template #actions>
          <el-button :icon="Collection" @click="router.push('/skills')">Skill 管理</el-button>
          <el-button
            type="primary"
            :icon="Link"
            :disabled="!canImportSkills"
            @click="directImportVisible = true"
          >
            从 GitHub 检查
          </el-button>
        </template>
      </PageHeader>

      <template #summary>
        <section class="market-metric-strip" aria-label="Skill 市场概览">
          <template v-for="(metric, index) in marketMetrics" :key="metric.label">
            <div v-if="index > 0" class="metric-divider" aria-hidden="true" />
            <article class="metric-segment">
              <MetricIconBg class="metric-segment-icon" :icon-key="metric.iconKey" :tone="metric.tone" />
              <div class="metric-content">
                <div class="metric-line">
                  <span class="metric-label">{{ metric.label }}</span>
                  <em class="metric-delta" :class="metric.deltaTone">{{ metric.delta }}</em>
                </div>
                <strong>{{ metric.value }}</strong>
                <small>{{ metric.hint }}</small>
              </div>
            </article>
          </template>
        </section>
      </template>
    </CollapsibleHeaderRegion>

    <el-card class="market-workbench-card" shadow="never">
      <div class="market-boundary-note">
        <strong>安全边界</strong>
        <span>安装量仅作发现信号；导入不会自动发布、执行脚本或获得 Tool / MCP / 凭据权限。</span>
      </div>

      <el-tabs v-model="activeTab" class="market-tabs" @tab-change="handleTabChange">
        <el-tab-pane label="发现 Skill" name="discover">
          <div class="discover-toolbar">
            <el-input
              v-model="query"
              class="search-input"
              clearable
              :prefix-icon="Search"
              placeholder="搜索 Skill 名称、用途或关键词"
              @keyup.enter="runSearch"
            />
            <el-input
              v-model="owner"
              clearable
              placeholder="限定 GitHub owner"
              @keyup.enter="runSearch"
            />
            <el-select v-model="trustFilter" clearable placeholder="来源可信度" @change="resetMarketPage">
              <el-option label="官方来源" value="OFFICIAL" />
              <el-option label="精选验证" value="VERIFIED" />
              <el-option label="社区与聚合" value="COMMUNITY" />
            </el-select>
            <el-button class="toolbar-reset" @click="resetDiscoverFilters">重置</el-button>
            <el-button class="toolbar-search" :icon="Search" :loading="searchLoading" @click="runSearch">搜索</el-button>
          </div>

          <el-alert v-if="searchError" class="inline-error" type="error" :closable="false" :title="searchError" show-icon />

          <div v-loading="searchLoading" class="market-card-shell">
            <div v-if="searchResult" class="market-source-bar">
              <div>
                <i aria-hidden="true" />
                <strong>{{ searchResult.provider }}</strong>
                <span>{{ modeLabel }}</span>
              </div>
              <small>{{ searchResult.message || '上游只提供发现元数据，制品会从 GitHub 重新解析。' }}</small>
            </div>

            <div class="market-card-grid">
              <SkillMarketCard
                v-for="item in pagedMarketItems"
                :key="`${item.source}:${item.id}`"
                :item="item"
                :can-import="canImportSkills"
                @inspect="inspectMarketItem"
              />
              <div v-if="!searchLoading && !pagedMarketItems.length" class="market-card-empty">
                <el-empty description="暂未发现匹配的 Skill；也可以直接检查公共 GitHub 仓库。" :image-size="72">
                  <el-button :disabled="!canImportSkills" @click="directImportVisible = true">从 GitHub 检查</el-button>
                </el-empty>
              </div>
            </div>

            <div v-if="filteredMarketItems.length" class="table-footer">
              <span>共 {{ filteredMarketItems.length }} 条</span>
              <el-pagination
                v-model:current-page="marketPage"
                v-model:page-size="marketPageSize"
                background
                layout="prev, pager, next, sizes"
                :page-sizes="[9, 18, 36]"
                :total="filteredMarketItems.length"
              />
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="精选来源" name="sources">
          <div class="market-table-shell">
            <el-table v-loading="sourcesLoading" :data="sources" row-key="sourceKey" class="market-table">
              <el-table-column label="来源名称" min-width="230">
                <template #default="{ row }">
                  <div class="source-name-cell">
                    <span class="source-mark">{{ sourceMark(row.displayName) }}</span>
                    <div><strong>{{ row.displayName }}</strong><code>{{ row.sourceKey }}</code></div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="来源说明" min-width="280" show-overflow-tooltip>
                <template #default="{ row }"><span class="muted">{{ row.description || 'ReachAI 管理的外部 Skill 发现来源。' }}</span></template>
              </el-table-column>
              <el-table-column label="可信度" width="116">
                <template #default="{ row }"><StatusTag :label="trustLabel(row.trustLevel)" :tone="trustTone(row.trustLevel)" /></template>
              </el-table-column>
              <el-table-column label="发现方式" width="110">
                <template #default="{ row }">{{ row.supportsSearch ? '在线搜索' : '仓库发现' }}</template>
              </el-table-column>
              <el-table-column label="引入范围" width="120">
                <template #default="{ row }">{{ row.supportsImport ? '公共 GitHub' : '仅发现' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="96">
                <template #default="{ row }"><span class="source-status"><i />{{ row.status }}</span></template>
              </el-table-column>
              <el-table-column label="操作" width="180" fixed="right" align="right">
                <template #default="{ row }">
                  <div class="row-actions">
                    <a
                      v-if="row.repositoryUrl || row.baseUrl"
                      :href="row.repositoryUrl || row.baseUrl || '#'"
                      target="_blank"
                      rel="noopener noreferrer"
                    >查看来源</a>
                    <el-button
                      v-if="row.repositoryUrl"
                      type="primary"
                      plain
                      size="small"
                      :disabled="!canImportSkills || !row.supportsImport"
                      @click="openProbe(row.repositoryUrl, row.sourceKey)"
                    >检查仓库</el-button>
                  </div>
                </template>
              </el-table-column>
              <template #empty><el-empty description="尚未配置 Skill 市场来源" :image-size="72" /></template>
            </el-table>
            <div v-if="sources.length" class="table-footer"><span>共 {{ sources.length }} 条</span></div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="导入记录" name="imports">
          <div class="market-table-shell">
            <el-table v-loading="importsLoading" :data="imports" row-key="id" class="market-table">
              <el-table-column label="Skill" min-width="220">
                <template #default="{ row }">
                  <div class="identity-cell">
                    <strong>{{ row.publisher }}/{{ row.name }}</strong>
                    <span>{{ row.version }} · {{ row.visibility }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="上游证据" min-width="310">
                <template #default="{ row }">
                  <div class="evidence-cell">
                    <strong>{{ row.repository }}</strong>
                    <code>{{ shortSha(row.sourceCommitSha) }} · {{ row.sourceRoot || '仓库根目录' }}</code>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="来源" width="140">
                <template #default="{ row }"><StatusTag :label="row.providerKey" tone="neutral" /></template>
              </el-table-column>
              <el-table-column label="导入于" width="170">
                <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="110" fixed="right" align="right">
                <template #default="{ row }">
                  <el-button text type="primary" @click="openImportedSkill(row)">进入评审</el-button>
                </template>
              </el-table-column>
              <template #empty><el-empty description="还没有从外部市场引入 Skill" :image-size="72" /></template>
            </el-table>
            <div v-if="imports.length" class="table-footer"><span>共 {{ imports.length }} 条</span></div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="directImportVisible" title="检查公共 GitHub Skill" width="min(620px, 92vw)">
      <p class="dialog-intro">支持仓库、tree 目录或具体 SKILL.md 链接。ReachAI 只接受 HTTPS 公共 GitHub，并在服务端校验 DNS、重定向与大小边界。</p>
      <el-input
        v-model="directSourceUrl"
        :prefix-icon="Link"
        placeholder="https://github.com/owner/repository/tree/main/path/to/skill"
        @keyup.enter="submitDirectProbe"
      />
      <template #footer>
        <el-button @click="directImportVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!directSourceUrl.trim()" @click="submitDirectProbe">解析并检查</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="probeVisible" size="min(920px, 96vw)" destroy-on-close>
      <template #header>
        <div class="drawer-heading">
          <div>
            <small>IMMUTABLE UPSTREAM INTAKE</small>
            <h2>{{ probeResult?.repository.repository || probeContext.expectedSkillName || '检查外部 Skill' }}</h2>
            <span>先读取真实仓库与 SKILL.md，再决定是否引入目录。</span>
          </div>
          <StatusTag v-if="probeResult" :label="`commit ${shortSha(probeResult.repository.commitSha)}`" tone="success" />
        </div>
      </template>

      <div v-loading="probeLoading" class="probe-body">
        <el-alert v-if="probeError" type="error" :closable="false" :title="probeError" show-icon />

        <template v-if="probeResult">
          <section class="repository-summary">
            <div class="repository-heading">
              <div>
                <small>{{ probeResult.repository.owner }}</small>
                <h3>{{ probeResult.repository.repository }}</h3>
                <p>{{ probeResult.repository.description || '上游仓库未提供说明。' }}</p>
              </div>
              <a :href="probeResult.repository.repositoryUrl" target="_blank" rel="noopener noreferrer">打开 GitHub</a>
            </div>
            <div class="repository-metrics">
              <article><span>精确 Commit</span><code>{{ probeResult.repository.commitSha }}</code></article>
              <article><span>Stars</span><strong>{{ formatInstalls(probeResult.repository.stars) }}</strong></article>
              <article><span>License</span><strong>{{ probeResult.repository.license || '未声明' }}</strong></article>
              <article><span>仓库包</span><strong>{{ formatBytes(probeResult.bundleSize) }}</strong></article>
            </div>
            <el-alert
              v-if="probeResult.repository.archived"
              type="warning"
              :closable="false"
              title="该上游仓库已归档；除非有明确维护计划，否则不建议引入。"
              show-icon
            />
          </section>

          <section class="candidate-section">
            <div class="section-heading">
              <div><small>DISCOVERED PACKAGES</small><h3>选择一个标准 Skill 包</h3></div>
              <span>{{ selectableCandidateCount }} / {{ probeResult.candidateCount }} 可引入</span>
            </div>
            <el-table
              :data="probeResult.candidates"
              row-key="sourceRoot"
              size="small"
              highlight-current-row
              :row-class-name="candidateRowClass"
              @row-click="selectCandidate"
            >
              <el-table-column width="48" align="center">
                <template #default="{ row }">
                  <el-radio
                    :model-value="selectedCandidateRoot"
                    :value="row.sourceRoot"
                    :disabled="!row.selectable"
                    aria-label="选择此 Skill"
                    @change="selectCandidate(row)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="Skill" min-width="230">
                <template #default="{ row }">
                  <div class="candidate-name">
                    <strong>{{ row.name || row.sourceRoot }}</strong>
                    <span>{{ row.description || row.errorMessage || '暂无说明' }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="路径" min-width="220" show-overflow-tooltip>
                <template #default="{ row }"><code>{{ row.sourceRoot }}</code></template>
              </el-table-column>
              <el-table-column label="证据" width="150">
                <template #default="{ row }">
                  <div class="candidate-signals">
                    <StatusTag :label="row.selectable ? `${row.fileCount} 文件` : '不可引入'" :tone="row.selectable ? 'neutral' : 'danger'" />
                    <StatusTag v-if="row.hasScripts" label="含脚本" tone="warning" />
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </section>

          <section v-if="selectedCandidate" class="import-section">
            <div class="section-heading">
              <div><small>GOVERNED IMPORT</small><h3>导入身份与范围</h3></div>
              <StatusTag label="导入后待评审" tone="warning" />
            </div>

            <div class="selected-evidence">
              <div><span>选中包 SHA-256</span><code>{{ selectedCandidate.selectedSourceSha256 }}</code></div>
              <div><span>文件树 SHA-256</span><code>{{ selectedCandidate.contentTreeSha256 }}</code></div>
            </div>
            <el-alert
              v-if="selectedCandidate.hasScripts"
              type="warning"
              :closable="false"
              title="此 Skill 含 scripts/。ReachAI 仅记录并提示风险，不会因导入或发布自动允许脚本执行。"
              show-icon
            />
            <ul v-if="candidateWarnings.length" class="warning-list">
              <li v-for="warning in candidateWarnings" :key="warning">{{ warning }}</li>
            </ul>

            <el-form label-position="top" class="import-form">
              <el-form-item label="发布者">
                <el-input v-model="importForm.publisher" maxlength="64" />
              </el-form-item>
              <el-form-item label="版本">
                <el-input v-model="importForm.version" placeholder="优先使用 SKILL.md 声明，否则锁定 commit" />
              </el-form-item>
              <el-form-item label="显示名称">
                <el-input v-model="importForm.displayName" placeholder="可选" />
              </el-form-item>
              <el-form-item label="可见范围">
                <el-select v-model="importForm.visibility">
                  <el-option label="仅自己（PRIVATE）" value="PRIVATE" />
                  <el-option label="项目内（PROJECT）" value="PROJECT" />
                  <el-option label="团队共享（SHARED）" value="SHARED" :disabled="!canImportSharedOrPublic" />
                  <el-option label="全平台（PUBLIC）" value="PUBLIC" :disabled="!canImportSharedOrPublic" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="importForm.visibility === 'PROJECT'" label="归属项目">
                <el-select v-model="importForm.projectCode" filterable placeholder="选择项目">
                  <el-option
                    v-for="project in projectsWithCode"
                    :key="project.id"
                    :label="`${project.name} · ${project.projectCode}`"
                    :value="project.projectCode"
                  />
                </el-select>
              </el-form-item>
            </el-form>
          </section>
        </template>

        <el-empty v-else-if="!probeLoading && !probeError" description="等待解析 GitHub 仓库" />
      </div>

      <template #footer>
        <div class="drawer-footer">
          <span>提交时会按同一 commit 重新下载，并验证选中包摘要没有变化。</span>
          <div>
            <el-button @click="probeVisible = false">关闭</el-button>
            <el-button
              type="primary"
              :loading="importing"
              :disabled="!canSubmitMarketImport"
              @click="submitMarketImport"
            >导入并进入评审</el-button>
          </div>
        </div>
      </template>
    </el-drawer>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Collection, Link, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import CollapsibleHeaderRegion from '@/components/common/CollapsibleHeaderRegion.vue'
import MetricIconBg from '@/components/common/MetricIconBg.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import SkillMarketCard from './components/SkillMarketCard.vue'
import { getAgentSkillAccess } from '@/api/skill'
import { getScanProjects } from '@/api/scanProject'
import {
  importSkillMarketCandidate,
  listSkillMarketImports,
  listSkillMarketSources,
  probeSkillMarketSource,
  searchSkillMarket,
} from '@/api/skillMarket'
import type { AgentSkillAccessCapabilities, AgentSkillBundleCandidate } from '@/types/skill'
import type { ScanProject } from '@/types/scanProject'
import type {
  SkillMarketImportOrigin,
  SkillMarketItem,
  SkillMarketProbeResult,
  SkillMarketSearchResult,
  SkillMarketSource,
} from '@/types/skillMarket'
import {
  defaultMarketVersion,
  formatInstalls,
  normalizeGitHubPublisher,
  selectSuggestedCandidate,
  trustLabel,
  trustTone,
} from './skillMarketPresentation'

const router = useRouter()
const activeTab = ref('discover')
const query = ref('agent')
const owner = ref('')
const trustFilter = ref('')
const marketPage = ref(1)
const marketPageSize = ref(9)
const searchLoading = ref(false)
const searchError = ref('')
const searchResult = ref<SkillMarketSearchResult | null>(null)
const sources = ref<SkillMarketSource[]>([])
const sourcesLoading = ref(false)
const imports = ref<SkillMarketImportOrigin[]>([])
const importsLoading = ref(false)
const scanProjects = ref<ScanProject[]>([])

const defaultSkillAccess: AgentSkillAccessCapabilities = {
  canImport: false,
  canImportSharedOrPublic: false,
  maxPackageBytes: 20 * 1024 * 1024,
  maxExpandedBytes: 100 * 1024 * 1024,
  maxSingleFileBytes: 20 * 1024 * 1024,
  maxFiles: 1024,
  maxInstructionBytes: 256 * 1024,
  scriptExecutionEnabled: false,
}
const skillAccess = ref<AgentSkillAccessCapabilities>({ ...defaultSkillAccess })
const canImportSkills = computed(() => skillAccess.value.canImport)
const canImportSharedOrPublic = computed(() => skillAccess.value.canImportSharedOrPublic)
const curatedSourceCount = computed(() => sources.value.filter(source => ['OFFICIAL', 'VERIFIED'].includes(source.trustLevel)).length)
const projectsWithCode = computed(() => scanProjects.value.filter(project => Boolean(project.projectCode)))
const modeLabel = computed(() => ({
  OFFICIAL_V1: 'skills.sh 官方 API',
  LEGACY_PUBLIC_SEARCH: 'skills.sh 公共发现',
  SOURCE_DISCOVERY_ONLY: '精选来源模式',
}[searchResult.value?.mode || ''] || searchResult.value?.mode || '发现源待连接'))
const filteredMarketItems = computed(() => {
  const items = searchResult.value?.items || []
  if (!trustFilter.value) return items
  if (trustFilter.value === 'COMMUNITY') {
    return items.filter(item => ['COMMUNITY', 'AGGREGATED'].includes(item.trustLevel))
  }
  return items.filter(item => item.trustLevel === trustFilter.value)
})
const pagedMarketItems = computed(() => {
  const start = (marketPage.value - 1) * marketPageSize.value
  return filteredMarketItems.value.slice(start, start + marketPageSize.value)
})
const marketMetrics = computed(() => [
  {
    label: '外部发现',
    value: searchResult.value?.count || 0,
    delta: searchResult.value ? '已连接' : '待连接',
    deltaTone: searchResult.value ? 'brand' : 'warning',
    hint: modeLabel.value,
    iconKey: 'api-discovery',
    tone: 'brand',
  },
  {
    label: '精选来源',
    value: curatedSourceCount.value,
    delta: '可信来源',
    deltaTone: 'success',
    hint: '官方与平台验证仓库',
    iconKey: 'workflow-sources',
    tone: 'success',
  },
  {
    label: '已引入版本',
    value: imports.value.length,
    delta: '留存证据',
    deltaTone: 'success',
    hint: '保留 commit 与双摘要',
    iconKey: 'tool-publish',
    tone: 'brand',
  },
  {
    label: '治理出口',
    value: 'REVIEW',
    delta: '待评审',
    deltaTone: 'warning',
    hint: '导入后进入 Skill 管理',
    iconKey: 'scan',
    tone: 'warning',
  },
])

const directImportVisible = ref(false)
const directSourceUrl = ref('')
const probeVisible = ref(false)
const probeLoading = ref(false)
const probeError = ref('')
const probeResult = ref<SkillMarketProbeResult | null>(null)
const selectedCandidateRoot = ref('')
const importing = ref(false)
const probeContext = reactive({
  sourceUrl: '',
  provider: 'GITHUB',
  marketplaceSkillId: '',
  expectedSkillName: '',
})
const importForm = reactive({
  publisher: 'community',
  version: '',
  displayName: '',
  visibility: 'PRIVATE',
  projectCode: '',
})

const selectedCandidate = computed(() => probeResult.value?.candidates.find(
  candidate => candidate.sourceRoot === selectedCandidateRoot.value,
) || null)
const selectableCandidateCount = computed(() => probeResult.value?.candidates.filter(candidate => candidate.selectable).length || 0)
const candidateWarnings = computed(() => [
  ...(probeResult.value?.warnings || []),
  ...(selectedCandidate.value?.warnings || []),
])
const canSubmitMarketImport = computed(() => {
  if (!canImportSkills.value || importing.value || !probeResult.value || !selectedCandidate.value?.selectable) return false
  if (!selectedCandidate.value.selectedSourceSha256 || !importForm.publisher.trim() || !importForm.version.trim()) return false
  if (importForm.visibility === 'PROJECT' && !importForm.projectCode) return false
  return true
})

async function loadSkillAccess() {
  try {
    const { data } = await getAgentSkillAccess()
    skillAccess.value = { ...defaultSkillAccess, ...data }
  } catch {
    skillAccess.value = { ...defaultSkillAccess }
  }
}

async function runSearch() {
  searchLoading.value = true
  searchError.value = ''
  try {
    const { data } = await searchSkillMarket({
      query: query.value.trim() || undefined,
      owner: owner.value.trim() || undefined,
      limit: 36,
    })
    searchResult.value = data
    marketPage.value = 1
  } catch (error) {
    searchError.value = errorMessage(error, 'Skill 市场发现源暂时不可用')
    searchResult.value = null
  } finally {
    searchLoading.value = false
  }
}

async function loadSources() {
  sourcesLoading.value = true
  try {
    const { data } = await listSkillMarketSources()
    sources.value = Array.isArray(data) ? data : []
  } catch (error) {
    sources.value = []
    ElMessage.error(errorMessage(error, '精选来源加载失败'))
  } finally {
    sourcesLoading.value = false
  }
}

async function loadImports() {
  importsLoading.value = true
  try {
    const { data } = await listSkillMarketImports()
    imports.value = Array.isArray(data) ? data : []
  } catch (error) {
    imports.value = []
    ElMessage.error(errorMessage(error, 'Skill 导入记录加载失败'))
  } finally {
    importsLoading.value = false
  }
}

async function loadProjects() {
  try {
    const { data } = await getScanProjects()
    scanProjects.value = Array.isArray(data) ? data : []
  } catch {
    scanProjects.value = []
  }
}

function resetMarketPage() {
  marketPage.value = 1
}

async function resetDiscoverFilters() {
  query.value = 'agent'
  owner.value = ''
  trustFilter.value = ''
  marketPage.value = 1
  await runSearch()
}

function handleTabChange(name: string | number) {
  if (name === 'sources' && !sources.value.length) loadSources()
  if (name === 'imports') loadImports()
}

function inspectMarketItem(item: SkillMarketItem) {
  if (!item.installUrl) return
  openProbe(item.installUrl, 'SKILLS_SH', item.id, item.name)
}

function submitDirectProbe() {
  const sourceUrl = directSourceUrl.value.trim()
  if (!sourceUrl) return
  directImportVisible.value = false
  openProbe(sourceUrl, 'GITHUB')
}

async function openProbe(
  sourceUrl: string,
  provider = 'GITHUB',
  marketplaceSkillId = '',
  expectedSkillName = '',
) {
  if (!canImportSkills.value) {
    ElMessage.warning('当前账号拥有只读访问，没有 skill:import 权限')
    return
  }
  Object.assign(probeContext, { sourceUrl, provider, marketplaceSkillId, expectedSkillName })
  probeVisible.value = true
  probeLoading.value = true
  probeError.value = ''
  probeResult.value = null
  selectedCandidateRoot.value = ''
  try {
    const { data } = await probeSkillMarketSource({
      sourceUrl,
      marketplaceProvider: provider,
      marketplaceSkillId: marketplaceSkillId || undefined,
      expectedSkillName: expectedSkillName || undefined,
    })
    probeResult.value = data
    const suggested = selectSuggestedCandidate(data.candidates, data.suggestedSourceRoot)
    if (suggested) selectCandidate(suggested)
    importForm.publisher = normalizeGitHubPublisher(data.repository.owner)
    importForm.version = defaultMarketVersion(suggested?.declaredVersion, data.repository.commitSha)
    importForm.displayName = suggested?.name || expectedSkillName || ''
    importForm.visibility = 'PRIVATE'
    importForm.projectCode = ''
  } catch (error) {
    probeError.value = errorMessage(error, '无法检查该 GitHub Skill')
  } finally {
    probeLoading.value = false
  }
}

function selectCandidate(candidate: AgentSkillBundleCandidate) {
  if (!candidate.selectable) return
  selectedCandidateRoot.value = candidate.sourceRoot
  if (probeResult.value) {
    importForm.version = defaultMarketVersion(candidate.declaredVersion, probeResult.value.repository.commitSha)
  }
  importForm.displayName = candidate.name || importForm.displayName
}

async function submitMarketImport() {
  if (!probeResult.value || !selectedCandidate.value?.selectedSourceSha256 || !canSubmitMarketImport.value) return
  importing.value = true
  try {
    const { data } = await importSkillMarketCandidate({
      sourceUrl: probeContext.sourceUrl,
      commitSha: probeResult.value.repository.commitSha,
      sourceRoot: selectedCandidate.value.sourceRoot,
      expectedSelectedSourceSha256: selectedCandidate.value.selectedSourceSha256,
      marketplaceProvider: probeContext.provider,
      marketplaceSkillId: probeContext.marketplaceSkillId || undefined,
      publisher: importForm.publisher.trim(),
      version: importForm.version.trim(),
      displayName: importForm.displayName.trim() || undefined,
      visibility: importForm.visibility,
      projectCode: importForm.visibility === 'PROJECT' ? importForm.projectCode : undefined,
    })
    probeVisible.value = false
    await loadImports()
    ElMessage.success(data.imported.created ? 'Skill 已安全引入，等待评审' : '相同制品已经存在，已返回原版本')
    await router.push({
      path: '/skills',
      query: { skillId: String(data.imported.skill.id), versionId: String(data.imported.version.id) },
    })
  } catch (error) {
    ElMessage.error(errorMessage(error, 'Skill 市场导入失败'))
  } finally {
    importing.value = false
  }
}

function openImportedSkill(origin: SkillMarketImportOrigin) {
  router.push({
    path: '/skills',
    query: { skillId: String(origin.skillId), versionId: String(origin.skillVersionId) },
  })
}

function candidateRowClass({ row }: { row: AgentSkillBundleCandidate }) {
  return row.sourceRoot === selectedCandidateRoot.value ? 'is-selected-candidate' : ''
}

function shortSha(value?: string | null) {
  return value ? value.slice(0, 12) : '—'
}

function sourceMark(value?: string | null) {
  return (value || '?').slice(0, 2).toUpperCase()
}

function formatDate(value?: string | null) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function formatBytes(value?: number | null) {
  const bytes = Math.max(0, Number(value || 0))
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

function errorMessage(error: unknown, fallback: string) {
  const candidate = error as { message?: string; response?: { data?: { message?: string; detail?: string; error?: string } } }
  return candidate.response?.data?.message
    || candidate.response?.data?.detail
    || candidate.response?.data?.error
    || candidate.message
    || fallback
}

onMounted(async () => {
  await loadSkillAccess()
  await Promise.allSettled([runSearch(), loadSources(), loadImports(), loadProjects()])
})
</script>

<style scoped lang="scss">
.skill-market {
  min-width: 0;
}

.market-metric-strip {
  display: flex;
  align-items: stretch;
  width: 100%;
  min-width: 0;
  min-height: 104px;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.16);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.5);
  box-shadow: 0 18px 42px rgb(var(--brand-primary-rgb) / 0.052);
  backdrop-filter: blur(9px);
}

.metric-divider {
  flex: 0 0 1px;
  align-self: center;
  width: 1px;
  height: 68px;
  border-radius: 1px;
  background: rgb(var(--brand-primary-rgb) / 0.18);
}

.metric-segment {
  display: flex;
  flex: 1 1 0;
  align-items: center;
  min-width: 0;
  min-height: 104px;
  gap: 18px;
  padding: 18px 22px;
}

.metric-segment-icon {
  flex-shrink: 0;
  --metric-icon-glyph-size: 24px;
}

.metric-content {
  min-width: 0;
  flex: 1;
}

.metric-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-width: 0;
  gap: 12px;
}

.metric-label {
  overflow: hidden;
  color: #64748b;
  font-size: 13px;
  font-weight: 500;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-delta {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  min-width: 68px;
  height: 24px;
  padding: 0 10px;
  border-radius: 12px;
  font-size: 12px;
  font-style: normal;
  font-weight: 500;
}

.metric-delta.brand {
  color: var(--brand-active);
  background: var(--brand-selected-bg);
}

.metric-delta.success {
  color: #16a34a;
  background: #dcfce7;
}

.metric-delta.warning {
  color: #f97316;
  background: #fff7ed;
}

.metric-segment strong {
  display: block;
  margin: 4px 0 2px;
  overflow: hidden;
  color: #0f172a;
  font-size: 25px;
  font-weight: 700;
  line-height: 30px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-segment small {
  display: block;
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.market-workbench-card {
  min-width: 0;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.14);
  border-radius: 16px;
  background: var(--brand-glass-card-bg, rgba(255, 255, 255, 0.76));
  box-shadow: 0 18px 42px -16px rgb(var(--brand-primary-rgb) / 0.08);
  backdrop-filter: blur(18px);
}

.market-workbench-card :deep(.el-card__body) {
  padding: 0;
}

.market-boundary-note {
  display: flex;
  align-items: center;
  min-height: 44px;
  gap: 10px;
  padding: 0 28px;
  border-bottom: 1px solid rgb(var(--brand-primary-rgb) / 0.1);
  color: #64748b;
  background: rgba(255, 255, 255, 0.44);
  font-size: 12px;
}

.market-boundary-note strong {
  flex: 0 0 auto;
  color: var(--brand-active);
  font-size: 12px;
}

.market-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 28px;
  border-bottom: 1px solid rgb(var(--brand-primary-rgb) / 0.1);
  background: rgba(255, 255, 255, 0.34);
}

.market-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 0;
}

.market-tabs :deep(.el-tabs__item) {
  height: 52px;
  color: #52627a;
  font-weight: 600;
}

.market-tabs :deep(.el-tabs__item.is-active) {
  color: var(--brand-active);
}

.market-tabs :deep(.el-tabs__active-bar) {
  height: 3px;
  border-radius: 999px 999px 0 0;
  background: var(--brand-primary);
}

.discover-toolbar {
  display: grid;
  grid-template-columns: minmax(340px, 2.4fr) minmax(180px, 1fr) minmax(170px, 1fr) 74px 88px;
  align-items: center;
  gap: 12px;
  margin: 24px 28px 0;
  padding: 9px 14px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.11);
  border-radius: 12px;
  background: var(--brand-soft-bg, rgba(238, 242, 255, 0.76));
}

.discover-toolbar :deep(.el-input__wrapper),
.discover-toolbar :deep(.el-select__wrapper) {
  min-height: 38px;
  padding: 0 14px;
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.86);
  box-shadow: 0 0 0 1px rgb(var(--brand-primary-rgb) / 0.18) inset;
}

.discover-toolbar :deep(.el-input__inner),
.discover-toolbar :deep(.el-select__placeholder),
.discover-toolbar :deep(.el-select__selected-item) {
  color: #8290a9;
  font-size: 13px;
}

.toolbar-reset,
.toolbar-search {
  height: 40px;
  min-height: 40px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.18);
  border-radius: 10px;
  font-weight: 700;
}

.toolbar-reset {
  color: #334155;
  background: rgba(255, 255, 255, 0.86);
}

.toolbar-search {
  color: #fff;
  border-color: transparent;
  background: var(--brand-primary-gradient);
  box-shadow: 0 10px 18px -10px rgb(var(--brand-primary-rgb) / 0.24);
}

.toolbar-search:hover {
  color: #fff;
  background: linear-gradient(135deg, var(--brand-hover), var(--brand-primary));
}

.inline-error {
  margin: 16px 28px 0;
}

.market-card-shell,
.market-table-shell {
  display: flex;
  flex-direction: column;
  min-width: 0;
  margin: 20px 28px 28px;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.12);
  border-radius: 13px;
  background: rgba(255, 255, 255, 0.68);
}

.market-card-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  align-items: stretch;
  min-height: 220px;
  gap: 14px;
  padding: 16px;
  background:
    radial-gradient(circle at 8% 8%, rgb(var(--brand-primary-rgb) / 0.055), transparent 30%),
    color-mix(in srgb, var(--brand-selected-bg) 12%, transparent);
}

.market-card-empty {
  display: grid;
  grid-column: 1 / -1;
  min-height: 260px;
  place-items: center;
}

.market-source-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 42px;
  gap: 18px;
  padding: 0 14px;
  border-bottom: 1px solid rgb(var(--brand-primary-rgb) / 0.1);
  background: rgba(255, 255, 255, 0.58);
}

.market-source-bar > div {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 8px;
}

.market-source-bar i,
.source-status i {
  flex: 0 0 7px;
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #22c55e;
}

.market-source-bar strong {
  color: #0f172a;
  font-size: 12px;
}

.market-source-bar span,
.market-source-bar small {
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.market-source-bar small {
  text-align: right;
}

.market-table {
  --el-table-header-bg-color: color-mix(in srgb, var(--brand-selected-bg) 42%, #f1f5fb);
  --el-table-border-color: rgb(var(--brand-primary-rgb) / 0.1);
  width: 100%;
  background: transparent;
}

.market-table :deep(.el-table__inner-wrapper::before) {
  height: 0;
}

.market-table :deep(.cell) {
  padding: 0 8px;
}

.market-table :deep(th.el-table__cell) {
  height: 44px;
  color: var(--brand-active);
  background: color-mix(in srgb, var(--brand-selected-bg) 42%, #f1f5fb) !important;
  font-size: 12px;
  font-weight: 700;
}

.market-table :deep(td.el-table__cell) {
  height: 64px;
  color: #334155;
  background: rgba(255, 255, 255, 0.72) !important;
  border-bottom-color: rgb(var(--brand-primary-rgb) / 0.1);
}

.market-table :deep(.el-table__row:hover > td.el-table__cell),
.market-table :deep(.el-table__body tr.hover-row > td.el-table__cell) {
  background: color-mix(in srgb, var(--brand-selected-bg) 22%, #fff) !important;
}

.market-table :deep(.el-table-fixed-column--right),
.market-table :deep(.el-table-fixed-column--left) {
  background-color: rgba(255, 255, 255, 0.94) !important;
}

.market-table :deep(th.el-table-fixed-column--right),
.market-table :deep(th.el-table-fixed-column--left) {
  background-color: color-mix(in srgb, var(--brand-selected-bg) 42%, #f1f5fb) !important;
}

.market-table :deep(td.el-table-fixed-column--right),
.market-table :deep(td.el-table-fixed-column--left) {
  background-color: rgba(255, 255, 255, 0.94) !important;
}

.source-name-cell {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 12px;
}

.source-name-cell > div {
  display: block;
  min-width: 0;
}

.source-name-cell strong,
.source-name-cell code {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-name-cell strong {
  color: #0f172a;
  font-weight: 700;
}

.source-name-cell code {
  margin-top: 4px;
  color: #64748b;
  font-size: 11px;
}

.source-mark {
  display: grid;
  place-items: center;
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.2);
  border-radius: 9px;
  color: var(--brand-active);
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.96), rgb(var(--brand-selected-rgb) / 0.66));
  font-size: 11px;
  font-weight: 800;
}

.muted {
  display: block;
  overflow: hidden;
  color: #64748b;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #16a34a;
  font-size: 12px;
  font-weight: 600;
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.row-actions a,
.repository-heading a {
  color: var(--brand-primary);
  font-size: 12px;
  text-decoration: none;
}

.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 62px;
  gap: 16px;
  padding: 12px 18px;
  border-top: 1px solid rgb(var(--brand-primary-rgb) / 0.1);
  color: #64748b;
  background: rgba(255, 255, 255, 0.58);
  font-size: 13px;
}

.identity-cell,
.evidence-cell,
.candidate-name,
.candidate-signals {
  display: grid;
  gap: 5px;
}

.identity-cell span,
.candidate-name span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.evidence-cell code,
.candidate-name code {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.repository-heading,
.section-heading,
.drawer-heading,
.drawer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.section-heading small,
.drawer-heading small,
.repository-heading small {
  color: var(--el-text-color-secondary);
  font-size: 10px;
  letter-spacing: .08em;
}

.section-heading h3,
.repository-heading h3,
.drawer-heading h2 {
  margin: 3px 0 0;
  color: var(--el-text-color-primary);
}

.repository-heading p,
.drawer-heading span,
.dialog-intro {
  margin: 0;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.65;
}

.dialog-intro {
  margin: -4px 0 14px;
}

.drawer-heading {
  width: 100%;
  align-items: flex-start;
  padding-right: 20px;
}

.drawer-heading h2 {
  font-size: 20px;
}

.drawer-heading span {
  display: block;
  margin-top: 5px;
}

.probe-body {
  display: grid;
  min-height: 360px;
  gap: 22px;
  padding: 0 4px 20px;
}

.repository-summary,
.candidate-section,
.import-section {
  display: grid;
  gap: 15px;
  padding: 18px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 15px;
  background: var(--el-bg-color);
}

.repository-heading {
  align-items: flex-start;
}

.repository-heading h3 {
  font-size: 18px;
}

.repository-heading p {
  margin-top: 6px;
}

.repository-metrics {
  display: grid;
  grid-template-columns: 2fr repeat(3, 1fr);
  gap: 10px;
}

.repository-metrics article {
  display: grid;
  min-width: 0;
  gap: 6px;
  padding: 12px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
}

.repository-metrics span,
.selected-evidence span {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.repository-metrics code,
.selected-evidence code {
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.section-heading {
  align-items: flex-end;
}

.section-heading h3 {
  font-size: 16px;
}

.section-heading > span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.candidate-name strong {
  color: var(--el-text-color-primary);
}

.candidate-signals {
  grid-auto-flow: column;
  justify-content: start;
}

:deep(.is-selected-candidate td.el-table__cell) {
  background: color-mix(in srgb, var(--el-color-primary) 8%, var(--el-bg-color));
}

.selected-evidence {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.selected-evidence > div {
  display: grid;
  min-width: 0;
  gap: 6px;
  padding: 12px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
}

.warning-list {
  margin: 0;
  padding-left: 20px;
  color: var(--el-color-warning-dark-2);
  font-size: 12px;
  line-height: 1.7;
}

.import-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 14px;
}

.import-form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.import-form :deep(.el-select) {
  width: 100%;
}

.drawer-footer {
  width: 100%;
}

.drawer-footer > span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.drawer-footer > div {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

@media (max-width: 1200px) {
  .market-metric-strip {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .metric-divider {
    display: none;
  }

  .metric-segment:nth-of-type(n + 3) {
    border-top: 1px solid rgb(var(--brand-primary-rgb) / 0.12);
  }

  .discover-toolbar {
    grid-template-columns: minmax(260px, 1.5fr) repeat(2, minmax(150px, 1fr));
  }

  .market-card-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .repository-metrics {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 760px) {
  .market-metric-strip,
  .discover-toolbar,
  .market-card-grid,
  .repository-metrics,
  .import-form,
  .selected-evidence {
    grid-template-columns: 1fr;
  }

  .market-boundary-note,
  .market-source-bar,
  .drawer-footer,
  .table-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .market-boundary-note {
    padding: 12px 18px;
  }

  .market-tabs :deep(.el-tabs__header) {
    padding: 0 18px;
  }

  .discover-toolbar,
  .market-card-shell,
  .market-table-shell,
  .inline-error {
    margin-right: 18px;
    margin-left: 18px;
  }

  .market-source-bar {
    padding: 10px 14px;
  }
}
</style>
