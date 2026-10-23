<template>
  <LiquidGlassPage class="skill-market" density="compact">
    <CollapsibleHeaderRegion>
      <PageHeader
        title="Skill 市场"
        description="发现标准 Agent Skill，查看来源后导入工作手册。"
        domain="agent"
        variant="overview"
        presentation="plain"
        action-presentation="inline"
      >
        <template #tags>
          <el-tag effect="light">外部 Skill</el-tag>
          <el-tag type="success" effect="light">当前 {{ searchResult?.count || 0 }} 个结果</el-tag>
          <el-tag type="info" effect="light">{{ modeLabel }}</el-tag>
        </template>
        <template #meta>
          <MetricStrip presentation="inline" source="wallpaper" :items="marketMetrics" density="compact" aria-label="Skill 市场概览" />
        </template>
        <template #actions>
          <LiquidGlassButton source="wallpaper" :icon="Collection" @click="router.push('/skills')">Skill 管理</LiquidGlassButton>
          <LiquidGlassButton
            source="wallpaper"
            type="primary"
            :icon="Link"
            :disabled="!canImportSkills"
            @click="directImportVisible = true"
          >
            从 GitHub 检查
          </LiquidGlassButton>
        </template>
      </PageHeader>
    </CollapsibleHeaderRegion>

    <LiquidGlassTabs v-model="activeTab" source="wallpaper" class="market-tabs workbench-list-surface" @tab-change="handleTabChange">
      <el-tab-pane label="发现 Skill" name="discover">
        <div class="market-pane">
          <LiquidGlassNotice source="wallpaper" class="market-boundary-note" title="安全边界">
            安装量仅作发现信号；导入不会自动发布、执行脚本或获得 Tool / MCP / 凭据权限。
          </LiquidGlassNotice>
          <LiquidGlassNotice v-if="searchError" source="wallpaper" type="error" :title="searchError" />

          <LiquidGlassTable
            v-model:current-page="marketPage"
            v-model:page-size="marketPageSize"
            body-layout="cards"
            presentation="collection"
            class="market-card-shell"
            density="compact"
            pagination-label="Skill 市场分页"
            :loading="searchLoading"
            :empty="!searchLoading && !pagedMarketItems.length"
            :total="filteredMarketItems.length"
            :page-sizes="[9, 18, 36]"
          >
            <template #toolbar>
              <LiquidGlassFilterBar density="compact" :loading="searchLoading" query-label="搜索" @query="runSearch" @reset="resetDiscoverFilters">
                <LiquidGlassInput
                  v-model="query"
                  clearable
                  :prefix-icon="Search"
                  placeholder="搜索 Skill 名称、用途或关键词"
                  aria-label="搜索 Skill"
                />
                <LiquidGlassInput
                  v-model="owner"
                  class="market-filter"
                  clearable
                  placeholder="限定 GitHub owner"
                  aria-label="GitHub owner"
                />
                <LiquidGlassSelect v-model="trustFilter" class="market-filter" clearable placeholder="来源可信度" dropdown-title="来源可信度" aria-label="来源可信度" @change="resetMarketPage">
                  <el-option label="官方来源" value="OFFICIAL" />
                  <el-option label="精选验证" value="VERIFIED" />
                  <el-option label="社区与聚合" value="COMMUNITY" />
                </LiquidGlassSelect>
              </LiquidGlassFilterBar>
              <div v-if="searchResult" class="market-source-bar">
                <div>
                  <i aria-hidden="true" />
                  <strong>{{ searchResult.provider }}</strong>
                  <span>{{ modeLabel }}</span>
                </div>
                <small>{{ searchResult.message || '上游只提供发现元数据，制品会从 GitHub 重新解析。' }}</small>
              </div>
            </template>

            <div class="market-card-grid">
              <SkillMarketCard
                v-for="item in pagedMarketItems"
                :key="`${item.source}:${item.id}`"
                :item="item"
                :can-import="canImportSkills"
                @inspect="inspectMarketItem"
              />
            </div>

            <template #empty>
              <LiquidGlassEmptyState title="暂未发现匹配的 Skill" description="换个关键词，或直接检查公共 GitHub 仓库。">
                <LiquidGlassButton :disabled="!canImportSkills" @click="directImportVisible = true">从 GitHub 检查</LiquidGlassButton>
              </LiquidGlassEmptyState>
            </template>
          </LiquidGlassTable>
        </div>
      </el-tab-pane>

      <el-tab-pane label="精选来源" name="sources">
        <LiquidGlassTable
          class="market-table-shell"
          :loading="sourcesLoading"
          :empty="!sourcesLoading && !sources.length"
          empty-description="尚未配置 Skill 市场来源"
          :show-pagination="false"
        >
          <el-table :data="sources" row-key="sourceKey" class="market-table" empty-text=" ">
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
                    link
                    size="small"
                    :disabled="!canImportSkills || !row.supportsImport"
                    @click="openProbe(row.repositoryUrl, row.sourceKey)"
                  >检查仓库</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
          <p v-if="sources.length" class="table-total">共 {{ sources.length }} 条</p>
        </LiquidGlassTable>
      </el-tab-pane>

      <el-tab-pane label="导入记录" name="imports">
        <LiquidGlassTable
          class="market-table-shell"
          :loading="importsLoading"
          :empty="!importsLoading && !imports.length"
          empty-description="还没有从外部市场引入 Skill"
          :show-pagination="false"
        >
          <el-table :data="imports" row-key="id" class="market-table" empty-text=" ">
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
          </el-table>
          <p v-if="imports.length" class="table-total">共 {{ imports.length }} 条</p>
        </LiquidGlassTable>
      </el-tab-pane>
    </LiquidGlassTabs>

    <AppDialog v-model="directImportVisible" title="检查公共 GitHub Skill" width="min(620px, 92vw)">
      <p class="dialog-intro">支持仓库、tree 目录或具体 SKILL.md 链接。ReachAI 只接受 HTTPS 公共 GitHub，并在服务端校验 DNS、重定向与大小边界。</p>
      <el-input
        v-model="directSourceUrl"
        :prefix-icon="Link"
        placeholder="https://github.com/owner/repository/tree/main/path/to/skill"
        @keyup.enter="submitDirectProbe"
      />
      <template #footer>
        <LiquidGlassButton @click="directImportVisible = false">取消</LiquidGlassButton>
        <LiquidGlassButton type="primary" :disabled="!directSourceUrl.trim()" @click="submitDirectProbe">解析并检查</LiquidGlassButton>
      </template>
    </AppDialog>

    <AppDrawer title="检查外部 Skill" v-model="probeVisible" size="min(920px, 96vw)" destroy-on-close>
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
        <LiquidGlassNotice
          v-if="probeError"
          type="error"
          :title="probeError"
          source="backdrop"
        />

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
            <LiquidGlassNotice
              v-if="probeResult.repository.archived"
              type="warning"
              title="该上游仓库已归档；除非有明确维护计划，否则不建议引入。"
              source="backdrop"
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
            <LiquidGlassNotice
              v-if="selectedCandidate.hasScripts"
              type="warning"
              title="此 Skill 含 scripts/。ReachAI 仅记录并提示风险，不会因导入或发布自动允许脚本执行。"
              source="backdrop"
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
            <LiquidGlassButton @click="probeVisible = false">关闭</LiquidGlassButton>
            <LiquidGlassButton
              type="primary"
              :loading="importing"
              :disabled="!canSubmitMarketImport"
              @click="submitMarketImport"
            >导入并进入评审</LiquidGlassButton>
          </div>
        </div>
      </template>
    </AppDrawer>
  </LiquidGlassPage>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { Collection, Link, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import CollapsibleHeaderRegion from '@/components/common/CollapsibleHeaderRegion.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import {
  LiquidGlassButton,
  LiquidGlassEmptyState,
  LiquidGlassFilterBar,
  LiquidGlassInput,
  LiquidGlassNotice,
  LiquidGlassPage,
  LiquidGlassSelect,
  LiquidGlassTable,
  LiquidGlassTabs,
} from '@/components/common/liquid-glass'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
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
const marketMetrics = computed<MetricStripItem[]>(() => [
  { key: 'discover', label: '外部发现', value: searchResult.value?.count || 0, hint: searchResult.value ? '已连接' : '待连接', tone: 'brand' },
  { key: 'sources', label: '精选来源', value: curatedSourceCount.value, hint: '可信来源', tone: 'success' },
  { key: 'imports', label: '已引入版本', value: imports.value.length, hint: '留存证据' },
  { key: 'governance', label: '治理出口', value: '待评审', hint: '导入后进入 Skill 管理', tone: 'warning' },
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

.market-tabs {
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  flex-direction: column;
}

.market-tabs :deep(.el-tabs__content) {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
}

.market-pane {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: var(--layout-section-gap);
}

/* 来源和可信度是短值，筛选条里收窄，把宽度留给搜索框。 */
.market-card-shell :deep(.filter-bar__fields > .liquid-glass-control.market-filter) {
  flex: 1 1 150px;
  max-width: 200px;
}

.market-card-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  align-items: stretch;
  gap: 14px;
}

.market-source-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-top: 10px;
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
  background: var(--status-success);
}

.market-source-bar strong {
  color: var(--text-primary);
  font-size: 12px;
}

.market-source-bar span,
.market-source-bar small {
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.market-source-bar small {
  text-align: right;
}

.market-table {
  width: 100%;
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
  color: var(--text-primary);
  font-weight: 700;
}

.source-name-cell code {
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 11px;
}

.source-mark {
  display: grid;
  place-items: center;
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  border: 1px solid var(--border-glass-edge);
  border-radius: 9px;
  color: var(--brand-on-surface);
  background: var(--surface-glass-inset);
  font-size: 11px;
  font-weight: 800;
}

.muted {
  display: block;
  overflow: hidden;
  color: var(--text-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--status-success);
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
  color: var(--brand-on-surface);
  font-size: 12px;
  text-decoration: none;
}

.table-total {
  margin: 0;
  color: var(--text-muted);
  font-size: 12.5px;
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
  border: 1px solid var(--border-glass-edge);
  border-radius: 15px;
  background: var(--surface-glass-inset);
  box-shadow: var(--glass-rim-double);
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
  background: var(--surface-glass-selected);
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
  background: var(--surface-glass-selected);
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
  background: var(--surface-glass-selected);
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
  .market-card-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .repository-metrics {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 760px) {
  .market-card-grid,
  .repository-metrics,
  .import-form,
  .selected-evidence {
    grid-template-columns: 1fr;
  }

  .market-source-bar,
  .drawer-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .market-card-shell :deep(.filter-bar__fields > .liquid-glass-control.market-filter) {
    max-width: none;
  }
}
</style>
