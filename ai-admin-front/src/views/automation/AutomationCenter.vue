<template>
  <WorkbenchPage class="automation-page" layout="list" full-height>
    <CollapsibleHeaderRegion :collapsed="isAutomationHeaderCollapsed">
      <PageHeader
        variant="overview"
        domain="governance"
        title="自动化中心"
        description="设置好时间后，系统会自动运行指定的 Agent 或 Workflow。"
        :collapsed="isAutomationHeaderCollapsed"
      >
        <template #tags>
          <el-tag type="success" effect="light">运行治理</el-tag>
          <el-tag type="success" effect="light">当前 {{ automations.length }} 个自动化</el-tag>
          <el-tag :type="readiness?.enabled ? 'success' : readiness ? 'warning' : 'info'" effect="light">
            {{ readiness ? `${readiness.engine} ${readiness.engineVersion}` : '执行引擎检查中' }}
          </el-tag>
        </template>
        <template #actions>
          <el-button :icon="Refresh" :loading="loading" @click="refreshAll">刷新</el-button>
          <el-button v-if="canCreateAutomation" type="primary" :icon="Plus" @click="openCreate">
            新建自动化
          </el-button>
        </template>
      </PageHeader>

      <template #summary>
        <section class="automation-metric-strip" aria-label="自动化概览">
          <template v-for="(metric, index) in automationMetrics" :key="metric.label">
            <div v-if="index > 0" class="automation-metric-divider" aria-hidden="true" />
            <div class="automation-metric-segment">
              <MetricIconBg
                class="automation-metric-icon"
                :icon-key="metric.iconKey"
                :tone="metric.tone"
              />
              <div class="automation-metric-content">
                <div class="automation-metric-line">
                  <span>{{ metric.label }}</span>
                  <em :class="metric.deltaTone">{{ metric.delta }}</em>
                </div>
                <strong>{{ metric.value }}</strong>
              </div>
            </div>
          </template>
        </section>
      </template>
    </CollapsibleHeaderRegion>

    <el-card shadow="never" class="automation-card workbench-list-surface">
      <div class="filter-row">
        <el-input
          v-model="filters.keyword"
          class="keyword-filter"
          clearable
          :prefix-icon="Search"
          placeholder="搜索自动化名称、标识"
          @keyup.enter="loadAutomations"
        />
        <el-select
          v-model="filters.projectCode"
          class="project-filter"
          clearable
          filterable
          placeholder="所属项目"
          @change="onProjectFilterChanged"
        >
          <el-option
            v-for="project in projectOptions"
            :key="project.id"
            :label="projectLabel(project)"
            :value="project.projectCode"
          />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="全部状态" class="status-filter">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="运行中" value="ACTIVE" />
          <el-option label="已暂停" value="PAUSED" />
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="已归档" value="ARCHIVED" />
        </el-select>
        <el-button class="filter-reset" @click="resetFilters">重置</el-button>
        <el-button class="filter-search" type="primary" :icon="Search" :loading="loading" @click="loadAutomations">
          搜索
        </el-button>
      </div>

      <div
        v-if="readiness && !readiness.enabled"
        class="readiness-inline"
        role="status"
      >
        <el-icon><WarningFilled /></el-icon>
        <div>
          <strong>自动运行暂未开启</strong>
          <span>现在只能保存草稿，不会按计划执行。完成数据库升级并开启自动化后即可使用。</span>
        </div>
      </div>

      <div class="automation-table-shell">
        <el-table
          v-loading="loading"
          :data="automations"
          :class="['automation-table', { 'is-empty': !loading && automations.length === 0 }]"
          empty-text=" "
        >
          <el-table-column label="自动化" min-width="238">
            <template #default="{ row }">
              <button class="automation-name" type="button" @click="openHistory(row)">
                {{ row.name }}
              </button>
              <div class="cell-meta">
                <span>{{ row.projectCode || '未绑定项目' }}</span>
                <code>{{ shortKey(row.automationKey) }}</code>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="执行目标" min-width="210">
            <template #default="{ row }">
              <div class="target-cell">
                <el-tag size="small" effect="plain" :type="row.targetType === 'AGENT' ? 'primary' : 'success'">
                  {{ row.targetType === 'AGENT' ? 'Agent' : 'Workflow' }}
                </el-tag>
                <span class="target-name">{{ targetLabel(row) }}</span>
              </div>
              <div class="cell-meta">固定版本 #{{ row.targetVersionId || '-' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="计划" min-width="220">
            <template #default="{ row }">
              <div class="schedule-cell">
                <el-tag size="small" effect="plain">{{ row.triggerType === 'ONCE' ? '单次' : '周期' }}</el-tag>
                <code>{{ scheduleLabel(row) }}</code>
              </div>
              <div class="cell-meta">{{ row.timeZone || 'Asia/Shanghai' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="104">
            <template #default="{ row }">
              <el-tag :type="statusTone(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="下次运行" min-width="156">
            <template #default="{ row }">
              <span :class="{ muted: !row.nextFireAt }">{{ formatTime(row.nextFireAt) }}</span>
              <div v-if="row.lastFireAt" class="cell-meta">上次 {{ formatRelative(row.lastFireAt) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="244" fixed="right">
            <template #default="{ row }">
              <div class="row-actions">
                <el-button link type="primary" @click="openHistory(row)">运行历史</el-button>
                <el-button
                  v-if="canOperateAutomation(row.projectCode)"
                  link
                  type="primary"
                  :disabled="row.status === 'ARCHIVED'"
                  @click="runNow(row)"
                >
                  立即运行
                </el-button>
                <el-button
                  v-if="row.status === 'ACTIVE' && canWriteAutomation(row.projectCode)"
                  link
                  type="warning"
                  @click="pause(row)"
                >
                  暂停
                </el-button>
                <el-button
                  v-else-if="['PAUSED', 'DRAFT'].includes(row.status) && canWriteAutomation(row.projectCode)"
                  link
                  type="success"
                  @click="resume(row)"
                >
                  启用
                </el-button>
                <el-dropdown
                  v-if="canWriteAutomation(row.projectCode)"
                  trigger="click"
                  @command="(command: string) => handleMore(command, row)"
                >
                  <el-button link :icon="MoreFilled" aria-label="更多操作" />
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item
                        command="edit"
                        :disabled="!['DRAFT', 'ACTIVE', 'PAUSED'].includes(row.status)"
                      >
                        编辑并创建新版本
                      </el-dropdown-item>
                      <el-dropdown-item command="archive" divided :disabled="row.status === 'ARCHIVED'">
                        归档
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <div
          v-if="!loading && automations.length === 0"
          class="automation-empty-state"
        >
          <div class="automation-empty-icon" aria-hidden="true">
            <el-icon><Clock /></el-icon>
          </div>
          <div class="automation-empty-copy">
            <h3>还没有自动化任务</h3>
            <p>新建后，系统会按你设置的时间运行，并在这里保留运行记录。</p>
          </div>
          <el-button
            v-if="canCreateAutomation"
            class="automation-empty-action"
            type="primary"
            :icon="Plus"
            @click="openCreate"
          >
            创建第一个自动化
          </el-button>
        </div>
      </div>

      <div class="automation-table-footer">
        <span>共 {{ automations.length }} 条</span>
        <span>{{ filters.projectCode ? '当前项目范围' : '全部可访问项目' }}</span>
      </div>

    </el-card>

    <AppDrawer
      v-model="editorVisible"
      :title="editorMode === 'create' ? '新建自动化' : '编辑自动化并创建新版本'"
      size="min(760px, 94vw)"
      destroy-on-close
      class="automation-editor"
    >
      <div class="editor-intro">
        <el-icon><Clock /></el-icon>
        <div>
          <strong>计划始终固定到精确发布版本</strong>
          <p>后续发布不会静默改变运行行为；需要变更时会创建新的 Automation 版本。</p>
        </div>
      </div>

      <el-form label-position="top" class="editor-form" @submit.prevent>
        <section class="form-section">
          <div class="section-title"><span>1</span><div><strong>基本信息</strong><small>名称和所属项目决定治理范围</small></div></div>
          <div class="form-grid form-grid--2">
            <el-form-item label="名称" required>
              <el-input v-model="form.name" maxlength="128" show-word-limit placeholder="例如：每日销售日报 Agent" />
            </el-form-item>
            <el-form-item label="项目" required>
              <el-select
                v-model="form.projectCode"
                filterable
                placeholder="选择项目"
                :disabled="editorMode === 'edit'"
                @change="onEditorProjectChanged"
              >
                <el-option
                  v-for="project in writableProjectOptions"
                  :key="project.id"
                  :label="projectLabel(project)"
                  :value="project.projectCode"
                />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="说明">
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="2"
              maxlength="1000"
              show-word-limit
              placeholder="说明业务目的、输出去向和责任人；不要填写凭据"
            />
          </el-form-item>
        </section>

        <section class="form-section">
          <div class="section-title"><span>2</span><div><strong>执行目标</strong><small>只显示当前项目内可执行资产</small></div></div>
          <el-form-item label="目标类型" required>
            <el-radio-group v-model="form.targetType" @change="onTargetTypeChanged">
              <el-radio-button value="AGENT">Agent</el-radio-button>
              <el-radio-button value="WORKFLOW">Workflow</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <div class="form-grid form-grid--2">
            <el-form-item label="目标" required>
              <el-select
                v-model="form.targetId"
                filterable
                :loading="targetsLoading"
                :disabled="!form.projectCode"
                placeholder="选择已启用目标"
                @change="onTargetChanged"
              >
                <el-option
                  v-for="target in targetOptions"
                  :key="target.id"
                  :label="`${target.name} / ${target.keySlug}`"
                  :value="target.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="发布版本" required>
              <el-select
                v-model="form.targetVersionId"
                :loading="versionsLoading"
                :disabled="!form.targetId"
                placeholder="选择精确版本"
              >
                <el-option
                  v-for="version in versionOptions"
                  :key="version.id"
                  :label="version.label"
                  :value="version.id"
                />
              </el-select>
            </el-form-item>
          </div>
          <el-alert
            v-if="form.targetId && !versionsLoading && versionOptions.length === 0"
            type="warning"
            :closable="false"
            title="该目标没有可用于自动化的已发布版本"
          />
        </section>

        <section class="form-section">
          <div class="section-title"><span>3</span><div><strong>触发计划</strong><small>所有持久时间以 UTC 保存，CRON 按所选时区解释</small></div></div>
          <el-form-item label="触发方式" required>
            <el-radio-group v-model="form.triggerType">
              <el-radio-button value="CRON">周期执行</el-radio-button>
              <el-radio-button value="ONCE">单次执行</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <template v-if="form.triggerType === 'CRON'">
            <div class="cron-presets">
              <span>常用：</span>
              <el-button link type="primary" @click="form.cronExpression = '0 */5 * * * *'">每 5 分钟</el-button>
              <el-button link type="primary" @click="form.cronExpression = '0 0 * * * *'">每小时</el-button>
              <el-button link type="primary" @click="form.cronExpression = '0 0 9 * * *'">每天 09:00</el-button>
              <el-button link type="primary" @click="form.cronExpression = '0 0 9 * * MON-FRI'">工作日 09:00</el-button>
            </div>
            <div class="form-grid form-grid--2">
              <el-form-item label="Spring 六段 CRON" required>
                <el-input v-model="form.cronExpression" placeholder="秒 分 时 日 月 周" />
              </el-form-item>
              <el-form-item label="IANA 时区" required>
                <el-select v-model="form.timeZone" filterable allow-create>
                  <el-option v-for="zone in timeZones" :key="zone" :label="zone" :value="zone" />
                </el-select>
              </el-form-item>
            </div>
          </template>
          <template v-else>
            <el-form-item label="执行时刻（按浏览器本地时间选择，提交为 UTC）" required>
              <el-date-picker
                v-model="form.fireAt"
                type="datetime"
                placeholder="选择未来时刻"
                :disabled-date="disablePastDate"
                style="width: 100%"
              />
            </el-form-item>
          </template>
        </section>

        <section class="form-section">
          <button class="advanced-toggle" type="button" @click="advancedOpen = !advancedOpen">
            <span><strong>高级执行策略</strong><small>误触发、并发、超时与失败恢复</small></span>
            <el-icon :class="{ rotated: advancedOpen }"><ArrowDown /></el-icon>
          </button>
          <div v-show="advancedOpen" class="advanced-body">
            <div class="form-grid form-grid--3">
              <el-form-item label="错过计划">
                <el-select v-model="form.misfirePolicy">
                  <el-option label="恢复后执行一次" value="FIRE_ONCE" />
                  <el-option label="跳过并留证" value="SKIP" />
                  <el-option label="限量补跑" value="CATCH_UP" />
                </el-select>
              </el-form-item>
              <el-form-item label="宽限秒数">
                <el-input-number v-model="form.misfireGraceSeconds" :min="0" :max="86400" controls-position="right" />
              </el-form-item>
              <el-form-item label="最多补跑">
                <el-input-number
                  v-model="form.maxCatchUp"
                  :min="1"
                  :max="100"
                  controls-position="right"
                  :disabled="form.misfirePolicy !== 'CATCH_UP'"
                />
              </el-form-item>
            </div>
            <div class="form-grid form-grid--3">
              <el-form-item label="并发策略">
                <el-select v-model="form.concurrencyPolicy">
                  <el-option label="排队（推荐）" value="QUEUE" />
                  <el-option label="跳过重叠" value="SKIP" />
                  <el-option label="允许并行" value="ALLOW" />
                </el-select>
              </el-form-item>
              <el-form-item label="最大并行数">
                <el-input-number
                  v-model="form.maxConcurrentRuns"
                  :min="1"
                  :max="32"
                  controls-position="right"
                  :disabled="form.concurrencyPolicy !== 'ALLOW'"
                />
              </el-form-item>
              <el-form-item label="超时（秒）">
                <el-input-number v-model="form.timeoutSeconds" :min="10" :max="86400" controls-position="right" />
              </el-form-item>
            </div>
            <div class="form-grid form-grid--3">
              <el-form-item label="最多尝试">
                <el-input-number v-model="form.maxAttempts" :min="1" :max="20" controls-position="right" />
              </el-form-item>
              <el-form-item label="初始退避（秒）">
                <el-input-number v-model="form.initialBackoffSeconds" :min="1" :max="3600" controls-position="right" />
              </el-form-item>
              <el-form-item label="最大退避（秒）">
                <el-input-number v-model="form.maxBackoffSeconds" :min="1" :max="86400" controls-position="right" />
              </el-form-item>
            </div>
          </div>
        </section>

        <section class="form-section">
          <div class="section-title"><span>4</span><div><strong>版本化输入</strong><small>JSON 对象，最大 64 KiB；禁止保存任何凭据</small></div></div>
          <el-form-item :error="inputError">
            <el-input
              v-model="form.inputText"
              type="textarea"
              :rows="7"
              resize="vertical"
              class="json-input"
              spellcheck="false"
              placeholder='{"reportDate":"today"}'
              @blur="validateInputJson"
            />
          </el-form-item>
          <el-alert
            type="info"
            :closable="false"
            title="凭据应由 Runtime 凭据库或 Capability 边界注入。出现 token、password、secret、credential、apiKey 等字段会被后端拒绝。"
          />
        </section>

        <section class="activation-panel">
          <div>
            <strong>保存后立即启用</strong>
            <p>{{ readiness?.enabled ? '启用后会同步持久时钟。' : '引擎关闭时定义会保留，但不会触发执行。' }}</p>
          </div>
          <el-switch v-model="form.activate" />
        </section>
      </el-form>

      <template #footer>
        <div class="drawer-footer">
          <el-button @click="editorVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="saveAutomation">
            {{ form.activate ? '保存并启用' : '保存草稿' }}
          </el-button>
        </div>
      </template>
    </AppDrawer>

    <AppDrawer
      v-model="historyVisible"
      title="运行历史"
      size="min(920px, 96vw)"
      destroy-on-close
      class="history-drawer"
    >
      <template v-if="selectedDetail">
        <div class="history-hero">
          <div>
            <div class="history-title-row">
              <h3>{{ selectedDetail.automation.name }}</h3>
              <el-tag :type="statusTone(selectedDetail.automation.status)">
                {{ statusLabel(selectedDetail.automation.status) }}
              </el-tag>
            </div>
            <p>
              {{ selectedDetail.automation.projectCode }} ·
              {{ selectedDetail.automation.targetType }} #{{ selectedDetail.automation.targetVersionId }} ·
              v{{ selectedDetail.currentVersion?.versionNo || '-' }}
            </p>
          </div>
          <div class="history-actions">
            <el-select v-model="historyStatus" clearable placeholder="全部状态" style="width: 130px">
              <el-option v-for="status in occurrenceStatuses" :key="status" :label="occurrenceStatusLabel(status)" :value="status" />
            </el-select>
            <el-button :icon="Refresh" :loading="historyLoading" @click="loadHistory">刷新</el-button>
          </div>
        </div>

        <div class="policy-strip">
          <span><b>时区</b>{{ selectedDetail.currentVersion?.timeZone }}</span>
          <span><b>错过计划</b>{{ misfireLabel(selectedDetail.currentVersion?.misfirePolicy) }}</span>
          <span><b>并发</b>{{ concurrencyLabel(selectedDetail.currentVersion?.concurrencyPolicy) }}</span>
          <span><b>重试</b>{{ selectedDetail.currentVersion?.maxAttempts || 0 }} 次</span>
          <span><b>主体</b>Automation Service Account</span>
        </div>

        <el-table v-loading="historyLoading" :data="history" class="history-table" stripe>
          <el-table-column type="expand" width="40">
            <template #default="{ row }">
              <div class="attempt-panel">
                <div v-if="row.attempts?.length" class="attempt-list">
                  <div v-for="attempt in row.attempts" :key="attempt.id" class="attempt-item">
                    <span class="attempt-index">#{{ attempt.attemptNo }}</span>
                    <el-tag size="small" :type="occurrenceTone(attempt.status)">{{ occurrenceStatusLabel(attempt.status) }}</el-tag>
                    <span>{{ formatTime(attempt.startedAt) }} → {{ formatTime(attempt.endedAt) }}</span>
                    <span class="attempt-worker">{{ attempt.workerId || '-' }}</span>
                    <button v-if="attempt.traceId" type="button" class="trace-link" @click="openTrace(attempt.traceId)">
                      查看 Trace
                    </button>
                    <p v-if="attempt.errorCode || attempt.errorMessage" class="attempt-error">
                      {{ attempt.errorCode || 'EXECUTION_FAILED' }} · {{ attempt.errorMessage }}
                    </p>
                  </div>
                </div>
                <el-empty v-else :image-size="40" description="尚未产生执行尝试" />
              </div>
            </template>
          </el-table-column>
          <el-table-column label="计划时刻" min-width="160">
            <template #default="{ row }">
              <span>{{ formatTime(row.scheduledAt) }}</span>
              <div class="cell-meta">{{ sourceLabel(row.sourceType) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="112">
            <template #default="{ row }">
              <el-tag :type="occurrenceTone(row.status)">{{ occurrenceStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="尝试" width="82" align="center">
            <template #default="{ row }">{{ row.attemptCount }}/{{ row.maxAttempts }}</template>
          </el-table-column>
          <el-table-column label="结果" min-width="220">
            <template #default="{ row }">
              <button v-if="row.traceId" type="button" class="trace-link" @click="openTrace(row.traceId)">
                {{ row.traceId }}
              </button>
              <span v-else class="muted">尚无 Trace</span>
              <div v-if="row.errorCode" class="cell-error">{{ row.errorCode }} · {{ row.errorMessage }}</div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="118" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="terminalStatuses.includes(row.status) && canOperateSelectedAutomation"
                link
                type="primary"
                @click="retryOccurrence(row)"
              >
                重试
              </el-button>
              <el-button
                v-if="['PENDING', 'RETRY'].includes(row.status) && canOperateSelectedAutomation"
                link
                type="danger"
                @click="cancelOccurrence(row)"
              >
                取消
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-empty v-if="!historyLoading && history.length === 0" :image-size="64" description="暂无运行记录" />
      </template>
    </AppDrawer>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import AppDrawer from '@/components/common/AppDrawer.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Clock, MoreFilled, Plus, Refresh, Search, WarningFilled } from '@element-plus/icons-vue'
import CollapsibleHeaderRegion from '@/components/common/CollapsibleHeaderRegion.vue'
import MetricIconBg from '@/components/common/MetricIconBg.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import { useCollapsiblePageHeader } from '@/composables/useCollapsiblePageHeader'
import {
  archiveAutomation,
  cancelAutomationOccurrence,
  createAutomation,
  getAutomation,
  getAutomationReadiness,
  listAutomationOccurrences,
  listAutomations,
  pauseAutomation,
  resumeAutomation,
  retryAutomationOccurrence,
  runAutomationNow,
  updateAutomation,
} from '@/api/automation'
import { listAgentConfigVersions, listAgents, listWorkflowVersions, listWorkflows } from '@/api/workflow'
import { useProjectStore } from '@/store/project'
import type { AgentConfigVersion } from '@/types/agent'
import type {
  AutomationConcurrencyPolicy,
  AutomationDetail,
  AutomationMisfirePolicy,
  AutomationOccurrence,
  AutomationOccurrenceStatus,
  AutomationStatus,
  AutomationSummary,
  AutomationTargetType,
  AutomationTriggerType,
  AutomationUpsertCommand,
} from '@/types/automation'
import type { Agent, WorkflowVersion, WorkflowWorkingCopy } from '@/types/workflow'
import type { ScanProject } from '@/types/scanProject'
import {
  hasPlatformResourcePermission,
  PLATFORM_PERMISSION_AUTOMATION_OPERATE,
  PLATFORM_PERMISSION_AUTOMATION_READ,
  PLATFORM_PERMISSION_AUTOMATION_WRITE,
} from '@/auth/platformAccess'
import { platformSessionUser } from '@/auth/platformSession'

interface TargetOption {
  id: string
  keySlug: string
  name: string
}

interface VersionOption {
  id: number
  label: string
}

interface AutomationMetric {
  label: string
  value: string | number
  delta: string
  deltaTone: 'brand' | 'success' | 'warning' | 'neutral'
  iconKey: string
  tone: 'brand' | 'success' | 'warning' | 'neutral'
}

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const loading = ref(false)
const saving = ref(false)
const editorVisible = ref(false)
const historyVisible = ref(false)
const historyLoading = ref(false)
const targetsLoading = ref(false)
const versionsLoading = ref(false)
const advancedOpen = ref(false)
const editorMode = ref<'create' | 'edit'>('create')
const editingKey = ref('')
const automations = ref<AutomationSummary[]>([])
const readiness = ref<Awaited<ReturnType<typeof getAutomationReadiness>>['data']>()
const targetOptions = ref<TargetOption[]>([])
const versionOptions = ref<VersionOption[]>([])
const selectedDetail = ref<AutomationDetail>()
const history = ref<AutomationOccurrence[]>([])
const historyStatus = ref('')
const inputError = ref('')

const {
  collapsed: isAutomationHeaderCollapsed,
  refreshScrollTargets: refreshAutomationHeaderScrollTargets,
} = useCollapsiblePageHeader({
  rootSelector: '.automation-page',
  scrollSelectors: [
    '.automation-table .el-scrollbar__wrap',
    '.automation-table .el-table__body-wrapper',
  ],
})

const filters = reactive({
  projectCode: typeof route.query.projectCode === 'string' ? route.query.projectCode : '',
  keyword: '',
  status: '' as AutomationStatus | '',
})

const form = reactive({
  name: '',
  description: '',
  projectCode: '',
  targetType: 'AGENT' as AutomationTargetType,
  targetId: '',
  targetVersionId: undefined as number | undefined,
  triggerType: 'CRON' as AutomationTriggerType,
  cronExpression: '0 0 9 * * *',
  fireAt: null as Date | null,
  timeZone: 'Asia/Shanghai',
  misfirePolicy: 'FIRE_ONCE' as AutomationMisfirePolicy,
  misfireGraceSeconds: 60,
  maxCatchUp: 10,
  concurrencyPolicy: 'QUEUE' as AutomationConcurrencyPolicy,
  maxConcurrentRuns: 1,
  timeoutSeconds: 900,
  maxAttempts: 3,
  initialBackoffSeconds: 10,
  maxBackoffSeconds: 300,
  inputText: '{}',
  activate: true,
  expectedRevision: undefined as number | undefined,
})

const timeZones = ['Asia/Shanghai', 'UTC', 'Asia/Tokyo', 'Europe/London', 'America/New_York']
const occurrenceStatuses: AutomationOccurrenceStatus[] = [
  'PENDING', 'RETRY', 'LEASED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD', 'CANCELLED', 'SKIPPED',
]
const terminalStatuses: string[] = ['SUCCEEDED', 'FAILED', 'DEAD', 'CANCELLED', 'SKIPPED']

function hasAutomationProjectPermission(permission: string, projectCode?: string | null) {
  return hasPlatformResourcePermission(
    platformSessionUser.value?.permissionGrants,
    permission,
    'PROJECT',
    null,
    projectCode,
  )
}

function canReadAutomation(projectCode?: string | null) {
  return hasAutomationProjectPermission(PLATFORM_PERMISSION_AUTOMATION_READ, projectCode)
}

function canWriteAutomation(projectCode?: string | null) {
  return hasAutomationProjectPermission(PLATFORM_PERMISSION_AUTOMATION_WRITE, projectCode)
}

function canOperateAutomation(projectCode?: string | null) {
  return hasAutomationProjectPermission(PLATFORM_PERMISSION_AUTOMATION_OPERATE, projectCode)
}

const projectOptions = computed(() => projectStore.projects.filter((item) => (
  Boolean(item.projectCode) && canReadAutomation(item.projectCode)
)))
const writableProjectOptions = computed(() => projectOptions.value.filter((item) => (
  canWriteAutomation(item.projectCode)
)))
const canCreateAutomation = computed(() => writableProjectOptions.value.length > 0)
const canOperateSelectedAutomation = computed(() => canOperateAutomation(
  selectedDetail.value?.automation.projectCode,
))
const activeCount = computed(() => automations.value.filter((item) => item.status === 'ACTIVE').length)
const attentionCount = computed(() => automations.value.filter((item) => ['PAUSED', 'COMPLETED'].includes(item.status)).length)
const automationMetrics = computed<AutomationMetric[]>(() => [
  {
    label: '自动化总数',
    value: automations.value.length,
    delta: filters.projectCode ? '当前项目' : '全部项目',
    deltaTone: 'brand',
    iconKey: 'workflow-total',
    tone: 'brand',
  },
  {
    label: '运行中',
    value: activeCount.value,
    delta: activeCount.value ? '已调度' : '待启用',
    deltaTone: activeCount.value ? 'success' : 'neutral',
    iconKey: 'agent-enabled',
    tone: 'success',
  },
  {
    label: '需关注',
    value: attentionCount.value,
    delta: attentionCount.value ? `待处理 ${attentionCount.value}` : '稳定',
    deltaTone: attentionCount.value ? 'warning' : 'success',
    iconKey: 'agent-ready',
    tone: attentionCount.value ? 'warning' : 'success',
  },
  {
    label: '执行引擎',
    value: readiness.value ? (readiness.value.enabled ? '就绪' : '关闭') : '检查中',
    delta: readiness.value ? (readiness.value.enabled ? '可调度' : '未启用') : '状态未知',
    deltaTone: readiness.value?.enabled ? 'success' : readiness.value ? 'warning' : 'neutral',
    iconKey: 'model-ready',
    tone: readiness.value?.enabled ? 'success' : readiness.value ? 'warning' : 'neutral',
  },
])

onMounted(async () => {
  if (!projectStore.projects.length) await projectStore.fetchProjects()
  if (!projectOptions.value.some((item) => item.projectCode === filters.projectCode)) {
    const currentProject = projectOptions.value.find(
      (item) => item.projectCode === projectStore.currentProjectCode,
    )
    filters.projectCode = currentProject?.projectCode || projectOptions.value[0]?.projectCode || ''
  }
  await refreshAll()
})

async function refreshAll() {
  await Promise.all([loadReadiness(), loadAutomations()])
  refreshAutomationHeaderScrollTargets()
}

async function loadReadiness() {
  try {
    const { data } = await getAutomationReadiness()
    readiness.value = data
  } catch {
    readiness.value = undefined
  }
}

async function loadAutomations() {
  loading.value = true
  try {
    const { data } = await listAutomations({
      projectCode: filters.projectCode || undefined,
      status: filters.status,
      keyword: filters.keyword.trim() || undefined,
      limit: 500,
    })
    automations.value = Array.isArray(data) ? data : []
    if (filters.projectCode) await loadTargetAssets(filters.projectCode, false)
  } catch {
    automations.value = []
  } finally {
    loading.value = false
  }
}

function onProjectFilterChanged() {
  void loadAutomations()
}

async function resetFilters() {
  filters.keyword = ''
  filters.status = ''
  const currentProject = projectOptions.value.find(
    (item) => item.projectCode === projectStore.currentProjectCode,
  )
  filters.projectCode = currentProject?.projectCode || projectOptions.value[0]?.projectCode || ''
  await loadAutomations()
}

function resetForm() {
  Object.assign(form, {
    name: '',
    description: '',
    projectCode: filters.projectCode || projectStore.currentProjectCode || projectOptions.value[0]?.projectCode || '',
    targetType: 'AGENT',
    targetId: '',
    targetVersionId: undefined,
    triggerType: 'CRON',
    cronExpression: '0 0 9 * * *',
    fireAt: null,
    timeZone: 'Asia/Shanghai',
    misfirePolicy: 'FIRE_ONCE',
    misfireGraceSeconds: 60,
    maxCatchUp: 10,
    concurrencyPolicy: 'QUEUE',
    maxConcurrentRuns: 1,
    timeoutSeconds: 900,
    maxAttempts: 3,
    initialBackoffSeconds: 10,
    maxBackoffSeconds: 300,
    inputText: '{}',
    activate: true,
    expectedRevision: undefined,
  })
  inputError.value = ''
  targetOptions.value = []
  versionOptions.value = []
  advancedOpen.value = false
}

async function openCreate() {
  if (!canCreateAutomation.value) {
    ElMessage.warning('当前账号没有可写项目的自动化权限')
    return
  }
  editorMode.value = 'create'
  editingKey.value = ''
  resetForm()
  if (!canWriteAutomation(form.projectCode)) {
    form.projectCode = writableProjectOptions.value[0]?.projectCode || ''
  }
  editorVisible.value = true
  if (form.projectCode) await loadTargetAssets(form.projectCode, true)
}

async function openEdit(row: AutomationSummary) {
  if (!canWriteAutomation(row.projectCode)) {
    ElMessage.warning('当前账号没有该项目的自动化写权限')
    return
  }
  editorMode.value = 'edit'
  editingKey.value = row.automationKey
  resetForm()
  saving.value = true
  try {
    const { data } = await getAutomation(row.automationKey)
    const version = data.currentVersion
    if (!version) throw new Error('当前定义没有有效版本')
    Object.assign(form, {
      name: data.automation.name,
      description: data.automation.description || '',
      projectCode: data.automation.projectCode || '',
      targetType: version.targetType,
      targetId: version.targetId,
      targetVersionId: version.targetVersionId,
      triggerType: version.triggerType,
      cronExpression: version.cronExpression || '',
      fireAt: version.fireAt ? new Date(version.fireAt) : null,
      timeZone: version.timeZone,
      misfirePolicy: version.misfirePolicy,
      misfireGraceSeconds: version.misfireGraceSeconds,
      maxCatchUp: version.maxCatchUp,
      concurrencyPolicy: version.concurrencyPolicy,
      maxConcurrentRuns: version.maxConcurrentRuns,
      timeoutSeconds: version.timeoutSeconds,
      maxAttempts: version.maxAttempts,
      initialBackoffSeconds: version.initialBackoffSeconds,
      maxBackoffSeconds: version.maxBackoffSeconds,
      inputText: JSON.stringify(version.input || {}, null, 2),
      activate: data.automation.status === 'ACTIVE',
      expectedRevision: data.automation.revision,
    })
    editorVisible.value = true
    await loadTargetAssets(form.projectCode, false)
    await loadTargetVersions(false)
  } catch (error) {
    ElMessage.error(messageOf(error, '读取自动化详情失败'))
  } finally {
    saving.value = false
  }
}

async function onEditorProjectChanged() {
  form.targetId = ''
  form.targetVersionId = undefined
  await loadTargetAssets(form.projectCode, true)
}

async function onTargetTypeChanged() {
  form.targetId = ''
  form.targetVersionId = undefined
  versionOptions.value = []
  await loadTargetAssets(form.projectCode, true)
}

async function onTargetChanged() {
  form.targetVersionId = undefined
  await loadTargetVersions(true)
}

async function loadTargetAssets(projectCode: string, clearSelection: boolean) {
  if (!projectCode) {
    targetOptions.value = []
    return
  }
  targetsLoading.value = true
  try {
    if (form.targetType === 'AGENT') {
      const { data } = await listAgents({ projectCode })
      targetOptions.value = (Array.isArray(data) ? data : [])
        .filter((item: Agent) => item.enabled !== false)
        .map((item: Agent) => ({ id: item.id, keySlug: item.keySlug, name: item.name }))
    } else {
      const { data } = await listWorkflows({ projectCode, status: 'ACTIVE' })
      targetOptions.value = (Array.isArray(data) ? data : [])
        .filter((item: WorkflowWorkingCopy) => item.status === 'ACTIVE')
        .map((item: WorkflowWorkingCopy) => ({ id: item.id, keySlug: item.keySlug, name: item.name }))
    }
    if (clearSelection && !targetOptions.value.some((item) => item.id === form.targetId)) {
      form.targetId = ''
      form.targetVersionId = undefined
      versionOptions.value = []
    }
  } catch {
    targetOptions.value = []
  } finally {
    targetsLoading.value = false
  }
}

async function loadTargetVersions(clearSelection: boolean) {
  if (!form.targetId) {
    versionOptions.value = []
    return
  }
  versionsLoading.value = true
  try {
    if (form.targetType === 'AGENT') {
      const { data } = await listAgentConfigVersions(form.targetId)
      versionOptions.value = (Array.isArray(data) ? data : [])
        .filter((item: AgentConfigVersion) => ['ACTIVE', 'ARCHIVED'].includes(item.status))
        .map((item: AgentConfigVersion) => ({
          id: item.id,
          label: `v${item.versionNo} · ${item.status === 'ACTIVE' ? '当前发布' : '历史发布'}${item.publishedAt ? ` · ${formatTime(item.publishedAt)}` : ''}`,
        }))
    } else {
      const { data } = await listWorkflowVersions(form.targetId)
      versionOptions.value = (Array.isArray(data) ? data : [])
        .filter((item: WorkflowVersion) => ['ACTIVE', 'RETIRED'].includes(String(item.status || '').toUpperCase()))
        .map((item: WorkflowVersion) => ({
          id: item.id,
          label: `${item.version} · ${item.status === 'ACTIVE' ? '当前发布' : '历史发布'}${item.publishedAt ? ` · ${formatTime(item.publishedAt)}` : ''}`,
        }))
    }
    if (clearSelection && !versionOptions.value.some((item) => item.id === form.targetVersionId)) {
      form.targetVersionId = undefined
    }
  } catch {
    versionOptions.value = []
  } finally {
    versionsLoading.value = false
  }
}

function parseInputJson(): Record<string, unknown> | null {
  inputError.value = ''
  try {
    const value = JSON.parse(form.inputText || '{}')
    if (!value || Array.isArray(value) || typeof value !== 'object') {
      inputError.value = '输入必须是 JSON 对象'
      return null
    }
    return value as Record<string, unknown>
  } catch {
    inputError.value = 'JSON 格式不正确'
    return null
  }
}

function validateInputJson() {
  parseInputJson()
}

async function saveAutomation() {
  if (!form.name.trim()) return ElMessage.warning('请填写名称')
  if (!form.projectCode) return ElMessage.warning('请选择项目')
  if (!canWriteAutomation(form.projectCode)) {
    return ElMessage.warning('当前账号没有该项目的自动化写权限')
  }
  if (!form.targetId || !form.targetVersionId) return ElMessage.warning('请选择目标和精确发布版本')
  if (form.triggerType === 'CRON' && !form.cronExpression.trim()) return ElMessage.warning('请填写 CRON')
  if (form.triggerType === 'ONCE' && (!form.fireAt || form.fireAt.getTime() <= Date.now())) {
    return ElMessage.warning('请选择未来的单次执行时刻')
  }
  if (form.maxBackoffSeconds < form.initialBackoffSeconds) {
    return ElMessage.warning('最大退避不能小于初始退避')
  }
  const input = parseInputJson()
  if (!input) return
  const project = projectOptions.value.find((item) => item.projectCode === form.projectCode)
  const payload: AutomationUpsertCommand = {
    name: form.name.trim(),
    description: form.description.trim() || undefined,
    projectId: project?.id,
    projectCode: form.projectCode,
    expectedRevision: form.expectedRevision,
    activate: form.activate,
    target: { type: form.targetType, id: form.targetId, versionId: form.targetVersionId },
    schedule: {
      type: form.triggerType,
      cronExpression: form.triggerType === 'CRON' ? form.cronExpression.trim() : undefined,
      fireAt: form.triggerType === 'ONCE' && form.fireAt ? form.fireAt.toISOString() : undefined,
      timeZone: form.timeZone,
      misfirePolicy: form.misfirePolicy,
      misfireGraceSeconds: form.misfireGraceSeconds,
      maxCatchUp: form.maxCatchUp,
    },
    executionPolicy: {
      concurrencyPolicy: form.concurrencyPolicy,
      maxConcurrentRuns: form.concurrencyPolicy === 'ALLOW' ? form.maxConcurrentRuns : 1,
      timeoutSeconds: form.timeoutSeconds,
      maxAttempts: form.maxAttempts,
      initialBackoffSeconds: form.initialBackoffSeconds,
      maxBackoffSeconds: form.maxBackoffSeconds,
    },
    input,
  }
  saving.value = true
  try {
    if (editorMode.value === 'create') {
      await createAutomation(payload)
      ElMessage.success(form.activate ? '自动化已创建并启用' : '自动化草稿已创建')
    } else {
      await updateAutomation(editingKey.value, payload)
      ElMessage.success('新版本已保存')
    }
    editorVisible.value = false
    await loadAutomations()
  } finally {
    saving.value = false
  }
}

async function pause(row: AutomationSummary) {
  if (!canWriteAutomation(row.projectCode)) return
  await pauseAutomation(row.automationKey, row.revision)
  ElMessage.success('自动化已暂停')
  await loadAutomations()
}

async function resume(row: AutomationSummary) {
  if (!canWriteAutomation(row.projectCode)) return
  await resumeAutomation(row.automationKey, row.revision)
  ElMessage.success('自动化已启用')
  await loadAutomations()
}

async function runNow(row: AutomationSummary) {
  if (!canOperateAutomation(row.projectCode)) return
  await ElMessageBox.confirm(
    `将立即以 Automation Service Account 执行“${row.name}”的固定版本。`,
    '确认立即运行',
    { type: 'warning', confirmButtonText: '立即运行', cancelButtonText: '取消' },
  )
  await runAutomationNow(row.automationKey)
  ElMessage.success('已创建手工运行实例')
  await openHistory(row)
}

async function archive(row: AutomationSummary) {
  if (!canWriteAutomation(row.projectCode)) return
  await ElMessageBox.confirm(
    `归档后“${row.name}”不可恢复，也不能再次运行。历史和 RunOps 证据会保留。`,
    '归档自动化',
    { type: 'warning', confirmButtonText: '归档', cancelButtonText: '取消' },
  )
  await archiveAutomation(row.automationKey, row.revision)
  ElMessage.success('自动化已归档')
  await loadAutomations()
}

function handleMore(command: string, row: AutomationSummary) {
  if (command === 'edit') void openEdit(row)
  if (command === 'archive') void archive(row)
}

async function openHistory(row: AutomationSummary) {
  historyVisible.value = true
  historyStatus.value = ''
  historyLoading.value = true
  try {
    const { data } = await getAutomation(row.automationKey)
    selectedDetail.value = data
    await loadHistory()
  } finally {
    historyLoading.value = false
  }
}

async function loadHistory() {
  if (!selectedDetail.value) return
  historyLoading.value = true
  try {
    const { data } = await listAutomationOccurrences(selectedDetail.value.automation.automationKey, {
      status: historyStatus.value || undefined,
      limit: 500,
    })
    history.value = Array.isArray(data) ? data : []
  } finally {
    historyLoading.value = false
  }
}

async function retryOccurrence(row: AutomationOccurrence) {
  if (!selectedDetail.value) return
  if (!canOperateSelectedAutomation.value) return
  await ElMessageBox.confirm('将使用该 occurrence 固定的 Automation 版本创建一次新重试。', '确认重试', {
    type: 'warning', confirmButtonText: '创建重试', cancelButtonText: '取消',
  })
  await retryAutomationOccurrence(selectedDetail.value.automation.automationKey, row.id)
  ElMessage.success('已创建重试实例')
  await loadHistory()
}

async function cancelOccurrence(row: AutomationOccurrence) {
  if (!selectedDetail.value) return
  if (!canOperateSelectedAutomation.value) return
  await ElMessageBox.confirm('仅待执行或退避中的实例可以取消。', '取消运行实例', {
    type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '返回',
  })
  await cancelAutomationOccurrence(
    selectedDetail.value.automation.automationKey,
    row.id,
    'Cancelled from Automation Center',
  )
  ElMessage.success('运行实例已取消')
  await loadHistory()
}

function openTrace(traceId: string) {
  historyVisible.value = false
  router.push({ name: 'RunOpsDetail', params: { traceId } })
}

function projectLabel(project: ScanProject) {
  return `${project.name}${project.projectCode ? ` / ${project.projectCode}` : ''}`
}

function targetLabel(row: AutomationSummary) {
  const match = targetOptions.value.find((item) => item.id === row.targetId)
  return match?.name || row.targetId || '-'
}

function shortKey(value: string) {
  return value.length > 18 ? `${value.slice(0, 10)}…${value.slice(-6)}` : value
}

function scheduleLabel(row: AutomationSummary) {
  if (!row.scheduleLabel) return '-'
  return row.triggerType === 'ONCE' ? formatTime(row.scheduleLabel) : row.scheduleLabel
}

function formatTime(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(date)
}

function formatRelative(value?: string) {
  if (!value) return '—'
  const difference = Date.now() - new Date(value).getTime()
  if (difference < 60_000) return '刚刚'
  if (difference < 3_600_000) return `${Math.floor(difference / 60_000)} 分钟前`
  if (difference < 86_400_000) return `${Math.floor(difference / 3_600_000)} 小时前`
  return `${Math.floor(difference / 86_400_000)} 天前`
}

function statusLabel(value: string) {
  return ({ DRAFT: '草稿', ACTIVE: '运行中', PAUSED: '已暂停', COMPLETED: '已完成', ARCHIVED: '已归档' } as Record<string, string>)[value] || value
}

function statusTone(value: string): 'success' | 'warning' | 'info' | 'danger' | 'primary' {
  if (value === 'ACTIVE') return 'success'
  if (value === 'PAUSED') return 'warning'
  if (value === 'ARCHIVED' || value === 'COMPLETED') return 'info'
  return 'primary'
}

function occurrenceStatusLabel(value?: string) {
  return ({
    PENDING: '待执行', RETRY: '等待重试', LEASED: '已领取', RUNNING: '执行中', SUCCEEDED: '成功',
    FAILED: '失败', DEAD: '重试耗尽', CANCELLED: '已取消', SKIPPED: '已跳过',
  } as Record<string, string>)[value || ''] || value || '-'
}

function occurrenceTone(value?: string): 'success' | 'warning' | 'info' | 'danger' | 'primary' {
  if (value === 'SUCCEEDED') return 'success'
  if (['FAILED', 'DEAD'].includes(value || '')) return 'danger'
  if (['RETRY', 'SKIPPED'].includes(value || '')) return 'warning'
  if (['PENDING', 'LEASED', 'RUNNING'].includes(value || '')) return 'primary'
  return 'info'
}

function sourceLabel(value: string) {
  return ({ SCHEDULE: '计划触发', MANUAL: '手工触发', RETRY: '操作员重试' } as Record<string, string>)[value] || value
}

function misfireLabel(value?: string) {
  return ({ FIRE_ONCE: '恢复后一次', SKIP: '跳过', CATCH_UP: '限量补跑' } as Record<string, string>)[value || ''] || '-'
}

function concurrencyLabel(value?: string) {
  return ({ QUEUE: '排队', SKIP: '跳过重叠', ALLOW: '允许并行' } as Record<string, string>)[value || ''] || '-'
}

function disablePastDate(date: Date) {
  return date.getTime() < Date.now() - 86_400_000
}

function messageOf(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}
</script>

<style scoped>
.automation-page {
  min-height: 100%;
  background: transparent;
}

.automation-page :deep(.app-page-header--governance) {
  --page-header-domain-tone: var(--brand-active);
  --page-header-domain-rgb: var(--brand-primary-rgb);
}

.automation-metric-strip {
  display: flex;
  min-width: 0;
  min-height: 104px;
  max-height: 104px;
  align-items: stretch;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.16);
  border-radius: 8px;
  background: rgb(255 255 255 / 0.5);
  box-shadow: 0 18px 42px rgb(var(--brand-primary-rgb) / 0.052);
  backdrop-filter: blur(9px);
}

.automation-metric-divider {
  width: 1px;
  height: 68px;
  flex: 0 0 1px;
  align-self: center;
  border-radius: 1px;
  background: rgb(var(--brand-primary-rgb) / 0.18);
}

.automation-metric-segment {
  display: flex;
  min-width: 0;
  min-height: 104px;
  flex: 1 1 0;
  align-items: center;
  gap: 18px;
  padding: 18px 20px;
}

.automation-metric-icon {
  --metric-icon-glyph-size: 24px;
  flex-shrink: 0;
}

.automation-metric-content {
  min-width: 0;
  flex: 1;
}

.automation-metric-line {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.automation-metric-line > span {
  overflow: hidden;
  color: #64748b;
  font-size: 13px;
  font-weight: 500;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.automation-metric-line em {
  display: inline-flex;
  min-width: 72px;
  height: 24px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  padding: 0 12px;
  border-radius: 12px;
  font-size: 12px;
  font-style: normal;
  font-weight: 500;
  line-height: 18px;
}

.automation-metric-line em.brand {
  color: var(--brand-active);
  background: var(--brand-selected-bg);
}

.automation-metric-line em.success {
  color: #16a34a;
  background: #dcfce7;
}

.automation-metric-line em.warning {
  color: #f97316;
  background: #fff7ed;
}

.automation-metric-line em.neutral {
  color: #64748b;
  background: #f1f5f9;
}

.automation-metric-content strong {
  display: block;
  margin: 4px 0 2px;
  color: #0f172a;
  font-size: 25px;
  font-weight: 700;
  line-height: 30px;
}

.automation-card {
  display: flex;
  width: 100%;
  min-width: 0;
  min-height: 420px;
  flex: 1;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.14);
  border-radius: 16px;
  background: var(--brand-glass-card-bg, rgb(255 255 255 / 0.76));
  box-shadow: 0 18px 42px -16px rgb(var(--brand-primary-rgb) / 0.08);
  backdrop-filter: blur(18px);
}

.automation-card :deep(.el-card__body) {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  padding: 0;
}

.filter-row {
  display: grid;
  grid-template-columns: minmax(340px, 2.4fr) minmax(220px, 1.2fr) minmax(150px, 1fr) 74px 88px;
  align-items: center;
  gap: 12px;
  margin: 28px 28px 0;
  padding: 9px 14px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.11);
  border-radius: 12px;
  background: var(--brand-soft-bg, rgb(238 242 255 / 0.76));
}

.project-filter,
.keyword-filter,
.status-filter {
  width: 100%;
  min-width: 0;
}

.filter-row :deep(.el-input__wrapper),
.filter-row :deep(.el-select__wrapper) {
  min-height: 38px;
  padding: 0 14px;
  border-radius: 9px;
  background: rgb(255 255 255 / 0.86);
  box-shadow: 0 0 0 1px rgb(var(--brand-primary-rgb) / 0.18) inset;
}

.filter-row :deep(.el-input__inner),
.filter-row :deep(.el-select__placeholder),
.filter-row :deep(.el-select__selected-item) {
  color: #8290a9;
  font-size: 13px;
  line-height: 17px;
}

.filter-reset,
.filter-search {
  min-height: 40px;
  border-radius: 10px;
  font-weight: 700;
}

.filter-reset {
  border-color: rgb(var(--brand-primary-rgb) / 0.18);
  background: rgb(255 255 255 / 0.86);
  color: #334155;
}

.filter-search {
  border-color: transparent;
  background: var(--brand-primary-gradient);
  box-shadow: 0 10px 18px -10px rgb(var(--brand-primary-rgb) / 0.24);
}

.readiness-inline {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  margin: 14px 28px 0;
  padding: 10px 14px;
  border: 1px solid color-mix(in srgb, var(--status-warning) 24%, transparent);
  border-radius: 10px;
  background: color-mix(in srgb, var(--status-warning-soft) 74%, transparent);
}

.readiness-inline > .el-icon {
  color: var(--status-warning);
  font-size: 20px;
}

.readiness-inline > div {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 10px;
}

.readiness-inline strong {
  flex: 0 0 auto;
  color: var(--text-primary);
  font-size: 13px;
}

.readiness-inline span {
  min-width: 0;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.automation-table-shell {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  margin: 22px 28px 0;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.12);
  border-radius: 13px;
  background: rgb(255 255 255 / 0.68);
}

.readiness-inline + .automation-table-shell {
  margin-top: 14px;
}

.automation-table {
  --el-table-border-color: rgb(var(--brand-primary-rgb) / 0.1);
  --el-table-header-bg-color: color-mix(in srgb, var(--brand-selected-bg) 42%, #f1f5fb);
  --el-table-row-hover-bg-color: rgb(var(--brand-selected-rgb) / 0.16);
  width: 100%;
  background: transparent;
}

.automation-table :deep(.el-table__inner-wrapper::before) {
  height: 0;
}

.automation-table :deep(.cell) {
  padding: 0 8px;
}

.automation-table :deep(th.el-table__cell) {
  height: 44px;
  background: color-mix(in srgb, var(--brand-selected-bg) 42%, #f1f5fb) !important;
  color: var(--brand-active);
  font-size: 12px;
  font-weight: 700;
  line-height: 16px;
}

.automation-table :deep(td.el-table__cell) {
  height: 62px;
  border-bottom-color: rgb(var(--brand-primary-rgb) / 0.1);
  background: rgb(255 255 255 / 0.72) !important;
  color: #334155;
}

.automation-table :deep(.el-table__row:hover > td.el-table__cell),
.automation-table :deep(.el-table__body tr.hover-row > td.el-table__cell) {
  background: rgb(var(--brand-selected-rgb) / 0.16) !important;
}

.automation-table :deep(.el-table-fixed-column--right),
.automation-table :deep(.el-table-fixed-column--left) {
  background-color: rgb(255 255 255 / 0.94) !important;
}

.automation-table :deep(th.el-table-fixed-column--right),
.automation-table :deep(th.el-table-fixed-column--left) {
  background-color: color-mix(in srgb, var(--brand-selected-bg) 42%, #f1f5fb) !important;
}

.automation-table :deep(td.el-table-fixed-column--right),
.automation-table :deep(td.el-table-fixed-column--left) {
  background-color: rgb(255 255 255 / 0.94) !important;
}

.automation-table :deep(.el-table__row:hover > td.el-table-fixed-column--right),
.automation-table :deep(.el-table__row:hover > td.el-table-fixed-column--left),
.automation-table :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--right),
.automation-table :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--left) {
  background-color: color-mix(in srgb, var(--brand-selected-bg) 34%, #fff) !important;
}

.automation-table.is-empty :deep(.el-table__empty-block) {
  display: none;
}

.automation-empty-state {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr) auto;
  min-height: 220px;
  flex: 1;
  align-items: center;
  gap: 24px;
  margin: 20px;
  padding: 28px 32px;
  border: 1px dashed rgb(var(--brand-primary-rgb) / 0.28);
  border-radius: 18px;
  background: linear-gradient(135deg, rgb(var(--brand-selected-rgb) / 0.55), rgb(255 255 255 / 0.62));
}

.automation-empty-icon {
  display: grid;
  width: 88px;
  height: 88px;
  place-items: center;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.18);
  border-radius: 22px;
  background: rgb(255 255 255 / 0.76);
  color: var(--brand-active);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.92);
}

.automation-empty-icon .el-icon {
  font-size: 36px;
}

.automation-empty-copy {
  min-width: 0;
}

.automation-empty-copy h3 {
  margin: 0 0 8px;
  color: #101828;
  font-size: 18px;
}

.automation-empty-copy p {
  max-width: 620px;
  margin: 0;
  color: #667085;
  line-height: 1.65;
}

.automation-empty-action {
  min-height: 40px;
  border-radius: 10px;
}

.automation-table-footer {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: auto;
  padding: 14px 28px 16px;
  border-top: 1px solid rgb(var(--brand-primary-rgb) / 0.1);
  color: #667085;
  font-size: 13px;
}

.automation-name,
.trace-link {
  appearance: none;
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--text-link);
  cursor: pointer;
  font: inherit;
  text-align: left;
}

.automation-name { color: #101828; font-weight: 700; }
.automation-name:hover,
.trace-link:hover { color: var(--brand-primary); text-decoration: underline; }

.cell-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 5px;
  color: var(--text-muted);
  font-size: 12px;
}

.cell-meta code,
.schedule-cell code {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
}

.target-cell,
.schedule-cell,
.row-actions,
.history-title-row,
.history-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.target-name { color: var(--text-primary); font-weight: 550; }
.muted { color: var(--text-disabled); }

.editor-intro {
  display: flex;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 18px;
  border: 1px solid color-mix(in srgb, var(--brand-primary) 24%, var(--border-divider));
  border-radius: 10px;
  background: color-mix(in srgb, var(--brand-primary) 6%, var(--surface-solid-panel));
  color: var(--text-primary);
}

.editor-intro .el-icon { margin-top: 2px; color: var(--brand-primary); font-size: 20px; }
.editor-intro p,
.activation-panel p { margin: 4px 0 0; color: var(--text-muted); font-size: 13px; }

.editor-form { display: flex; flex-direction: column; gap: 16px; }

.form-section {
  padding: 18px;
  border: 1px solid var(--border-divider);
  border-radius: 12px;
  background: var(--surface-solid-panel);
}

.section-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.section-title > span {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: color-mix(in srgb, var(--brand-primary) 14%, transparent);
  color: var(--brand-primary);
  font-weight: 700;
}

.section-title div,
.advanced-toggle span { display: flex; flex-direction: column; gap: 2px; }
.section-title small,
.advanced-toggle small { color: var(--text-muted); font-weight: 400; }

.form-grid { display: grid; gap: 14px; }
.form-grid--2 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.form-grid--3 { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.form-grid :deep(.el-input-number),
.form-grid :deep(.el-select) { width: 100%; }

.cron-presets {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin: -4px 0 12px;
  color: var(--text-muted);
  font-size: 13px;
}

.advanced-toggle {
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--text-primary);
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  text-align: left;
}

.advanced-toggle .el-icon { transition: transform 160ms ease; }
.advanced-toggle .el-icon.rotated { transform: rotate(180deg); }
.advanced-body { margin-top: 18px; }
.json-input :deep(textarea) { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 13px; }

.activation-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px;
  border: 1px solid color-mix(in srgb, var(--brand-primary) 22%, var(--border-divider));
  border-radius: 12px;
  background: color-mix(in srgb, var(--brand-primary) 5%, var(--surface-solid-panel));
}

.drawer-footer { display: flex; justify-content: flex-end; gap: 10px; }

.history-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 14px;
}

.history-hero h3 { margin: 0; color: var(--text-primary); font-size: 20px; }
.history-hero p { margin: 7px 0 0; color: var(--text-muted); }

.policy-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.policy-strip span {
  padding: 7px 10px;
  border: 1px solid var(--border-divider);
  border-radius: 8px;
  color: var(--text-secondary);
  background: var(--surface-solid-control);
  font-size: 12px;
}

.policy-strip b { margin-right: 6px; color: var(--text-muted); font-weight: 500; }
.history-table { --el-table-header-bg-color: var(--surface-solid-control); }
.attempt-panel { padding: 10px 20px 16px 48px; background: var(--surface-solid-control); }
.attempt-list { display: flex; flex-direction: column; gap: 8px; }

.attempt-item {
  display: grid;
  grid-template-columns: 38px 88px 176px minmax(120px, 1fr) auto;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 12px;
}

.attempt-index { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; color: var(--text-muted); }
.attempt-worker { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.attempt-error,
.cell-error { color: var(--status-danger); font-size: 12px; }
.attempt-error { grid-column: 2 / -1; margin: 0; }
.cell-error { margin-top: 5px; }

:global([data-theme='dark']) .automation-metric-strip,
:global([data-theme='dark']) .automation-card {
  border-color: rgb(255 255 255 / 0.08);
  background: rgb(255 255 255 / 0.04);
  box-shadow: 0 18px 42px rgb(0 0 0 / 0.18);
}

:global([data-theme='dark']) .automation-metric-line > span,
:global([data-theme='dark']) .automation-table-footer {
  color: var(--text-muted);
}

:global([data-theme='dark']) .automation-metric-content strong,
:global([data-theme='dark']) .automation-name,
:global([data-theme='dark']) .automation-empty-copy h3 {
  color: var(--text-primary);
}

:global([data-theme='dark']) .filter-row,
:global([data-theme='dark']) .automation-table-shell,
:global([data-theme='dark']) .automation-empty-state {
  border-color: rgb(255 255 255 / 0.08);
  background: rgb(255 255 255 / 0.035);
}

:global([data-theme='dark']) .filter-row :deep(.el-input__wrapper),
:global([data-theme='dark']) .filter-row :deep(.el-select__wrapper),
:global([data-theme='dark']) .filter-reset,
:global([data-theme='dark']) .automation-empty-icon {
  border-color: rgb(255 255 255 / 0.1);
  background: var(--surface-solid-control);
  color: var(--text-secondary);
}

:global([data-theme='dark']) .automation-table :deep(th.el-table__cell),
:global([data-theme='dark']) .automation-table :deep(th.el-table-fixed-column--right),
:global([data-theme='dark']) .automation-table :deep(th.el-table-fixed-column--left) {
  background: var(--surface-solid-control) !important;
  color: var(--brand-primary);
}

:global([data-theme='dark']) .automation-table :deep(td.el-table__cell),
:global([data-theme='dark']) .automation-table :deep(td.el-table-fixed-column--right),
:global([data-theme='dark']) .automation-table :deep(td.el-table-fixed-column--left) {
  background: var(--surface-solid-panel) !important;
  color: var(--text-secondary);
}

:global([data-theme='dark']) .automation-empty-copy p,
:global([data-theme='dark']) .readiness-inline span {
  color: var(--text-muted);
}

@media (max-width: 1100px) {
  .automation-metric-strip {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    max-height: none;
    gap: 1px;
    background: rgb(var(--brand-primary-rgb) / 0.14);
  }

  .automation-metric-divider {
    display: none;
  }

  .automation-metric-segment {
    min-height: 92px;
    background: var(--surface-glass-panel);
  }

  .filter-row {
    grid-template-columns: minmax(260px, 2fr) repeat(2, minmax(140px, 1fr)) 74px 88px;
  }
}

@media (max-width: 1040px) {
  .filter-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .keyword-filter {
    grid-column: 1 / -1;
  }

  .readiness-inline > div {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
  }

  .readiness-inline span {
    overflow: visible;
    text-overflow: initial;
    white-space: normal;
  }

  .automation-empty-state {
    grid-template-columns: 72px minmax(0, 1fr);
  }

  .automation-empty-icon {
    width: 72px;
    height: 72px;
  }

  .automation-empty-action {
    grid-column: 2;
    justify-self: start;
  }
}

@media (max-width: 720px) {
  .automation-metric-strip,
  .filter-row {
    grid-template-columns: 1fr;
  }

  .automation-metric-segment {
    min-height: 84px;
    padding: 14px 16px;
  }

  .filter-row,
  .readiness-inline,
  .automation-table-shell {
    margin-right: 16px;
    margin-left: 16px;
  }

  .keyword-filter {
    grid-column: auto;
  }

  .automation-empty-state {
    grid-template-columns: 1fr;
    justify-items: start;
    margin: 14px;
    padding: 24px;
  }

  .automation-empty-action {
    grid-column: auto;
  }

  .automation-table-footer {
    padding-right: 16px;
    padding-left: 16px;
  }

  .form-grid--2,
  .form-grid--3 { grid-template-columns: 1fr; }
  .history-hero { flex-direction: column; }
  .attempt-item { grid-template-columns: 36px 84px 1fr; }
  .attempt-worker { grid-column: 2 / -1; }
}
</style>
