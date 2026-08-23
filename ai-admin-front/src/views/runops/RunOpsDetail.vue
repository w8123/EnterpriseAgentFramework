<template>
  <WorkbenchPage class="runops-detail">
    <PageHeader
      variant="entity"
      domain="governance"
      height-preset="compact"
      eyebrow="运行诊断"
      :title="detailTitle"
      show-back
      @back="router.push('/runops')"
    >
      <template #tags>
        <el-tag v-if="summary" size="small" effect="plain" :type="summary.runType === 'AGENT' ? 'success' : 'primary'">
          {{ runTypeLabel(summary.runType) }}
        </el-tag>
        <el-tag v-if="summary" size="small" effect="plain" :type="statusTagType(summary.status)">
          {{ lifecycleStatusLabel(summary) }}
        </el-tag>
      </template>
      <template #actions>
        <el-tooltip content="刷新运行详情" placement="top">
          <el-button
            circle
            :icon="Refresh"
            :loading="loading"
            aria-label="刷新运行详情"
            @click="loadDetail"
          />
        </el-tooltip>
        <el-button :disabled="!summary" @click="copyIssueSummary">复制运行摘要</el-button>
        <el-button
          v-if="summary?.runType === 'WORKFLOW' && summary.workflowId"
          @click="router.push(`/workflows/${summary.workflowId}/studio`)"
        >
          打开工作流编排
        </el-button>
        <el-dropdown trigger="click" @command="handleMoreCommand">
          <el-button :icon="MoreFilled" aria-label="更多运行操作" />
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="context">运行上下文</el-dropdown-item>
              <el-dropdown-item command="candidate">
                Workflow 候选资格
                <span class="menu-state">
                  {{ candidateEligibility?.eligible ? '可创建' : `${candidateEligibility?.blockers.length || 0} 项未满足` }}
                </span>
              </el-dropdown-item>
              <el-dropdown-item v-if="comparison" command="compare">查看重放对比</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button
          type="primary"
          :icon="VideoPlay"
          :loading="replaying"
          :disabled="!detail"
          @click="openReplayDialog"
        >
          重放运行
        </el-button>
      </template>
    </PageHeader>

    <el-empty v-if="!loading && !detail" description="未找到运行记录" />

    <template v-if="detail && summary">
      <section :class="['run-overview-panel', `is-${diagnosticState.tone}`]" aria-label="运行结论与上下文">
        <div class="run-conclusion-row">
          <div class="run-conclusion-icon" aria-hidden="true">{{ diagnosticIcon }}</div>
          <div class="run-conclusion-copy">
            <div class="run-conclusion-heading">
              <el-tag size="small" :type="diagnosticState.tagType" effect="plain" round>
                {{ diagnosticState.label }}
              </el-tag>
              <strong>{{ diagnosticState.title }}</strong>
              <el-button v-if="failureFocus" link type="danger" @click="focusFailure">定位失败点 →</el-button>
            </div>
            <p>{{ diagnosticState.description }}</p>
          </div>
          <dl class="run-vitals" aria-label="运行关键指标">
            <div>
              <dt>耗时</dt>
              <dd>{{ formatDuration(summary.latencyMs) }}</dd>
            </div>
            <div>
              <dt>Token</dt>
              <dd>{{ formatTokenCount(summary.tokenCost) }}</dd>
            </div>
            <div>
              <dt>规划</dt>
              <dd>{{ summary.planCount ?? 0 }} / 重规划 {{ summary.replanCount ?? 0 }}</dd>
            </div>
          </dl>
        </div>

        <div class="run-context-strip">
          <span>项目 <strong>{{ summary.projectCode || '-' }}</strong></span>
          <span>发布版本 <strong>{{ runVersionLabel(summary) }}</strong></span>
          <span>入口 <strong>{{ entryTypeLabel(summary.entryType) }}</strong></span>
          <span class="trace-context">
            Trace
            <el-tooltip :content="traceId" placement="top" :show-after="400">
              <code>{{ shortIdentifier(traceId) }}</code>
            </el-tooltip>
            <el-button
              link
              :icon="CopyDocument"
              aria-label="复制追踪 ID"
              @click="copyIdentifier('追踪 ID', traceId)"
            />
          </span>
          <el-button link class="context-entry" @click="contextDrawerVisible = true">运行上下文 ›</el-button>
        </div>
      </section>

      <RunOpsInvestigationWorkbench
        ref="investigationWorkbenchRef"
        :detail="detail"
        :summary="summary"
        :comparison="comparison"
      />

      <el-card v-if="comparison && legacyDetailVisible" class="compare-card" shadow="never">
        <template #header>
          <div class="card-header">
            <span>原运行与重放对比</span>
            <el-tag size="small" effect="plain">
              {{ comparison.baseline.traceId }} → {{ comparison.candidate.traceId }}
            </el-tag>
          </div>
        </template>
        <section class="compare-summary">
          <div v-for="item in changedSummaryDiffs" :key="item.field" class="diff-chip">
            <span>{{ diffFieldLabel(item.field) }}</span>
            <strong>{{ displayDiffValue(item.field, item.baseline) }} → {{ displayDiffValue(item.field, item.candidate) }}</strong>
          </div>
          <el-empty v-if="!changedSummaryDiffs.length" description="摘要指标一致" />
        </section>
        <el-tabs>
          <el-tab-pane label="链路差异">
            <el-table :data="changedSpanDiffs" stripe>
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="diff-detail-grid">
                    <div>
                      <div class="panel-title">原运行</div>
                      <pre>{{ pretty(row.baseline) }}</pre>
                    </div>
                    <div>
                      <div class="panel-title">重放</div>
                      <pre>{{ pretty(row.candidate) }}</pre>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="key" label="链路片段" min-width="180" show-overflow-tooltip />
              <el-table-column label="原运行" min-width="240" show-overflow-tooltip>
                <template #default="{ row }">{{ spanDigest(row.baseline) }}</template>
              </el-table-column>
              <el-table-column label="重放" min-width="240" show-overflow-tooltip>
                <template #default="{ row }">{{ spanDigest(row.candidate) }}</template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!changedSpanDiffs.length" description="执行链路一致" />
          </el-tab-pane>
          <el-tab-pane label="工具差异">
            <el-table :data="changedToolDiffs" stripe>
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="diff-detail-grid">
                    <div>
                      <div class="panel-title">原运行</div>
                      <pre>{{ pretty(row.baseline) }}</pre>
                    </div>
                    <div>
                      <div class="panel-title">重放</div>
                      <pre>{{ pretty(row.candidate) }}</pre>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="key" label="工具" min-width="180" show-overflow-tooltip />
              <el-table-column label="原运行" min-width="240" show-overflow-tooltip>
                <template #default="{ row }">{{ toolDigest(row.baseline) }}</template>
              </el-table-column>
              <el-table-column label="重放" min-width="240" show-overflow-tooltip>
                <template #default="{ row }">{{ toolDigest(row.candidate) }}</template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!changedToolDiffs.length" description="工具调用一致" />
          </el-tab-pane>
          <el-tab-pane label="治理差异">
            <el-table :data="changedGuardDiffs" stripe>
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="diff-detail-grid">
                    <div>
                      <div class="panel-title">原运行</div>
                      <pre>{{ pretty(row.baseline) }}</pre>
                    </div>
                    <div>
                      <div class="panel-title">重放</div>
                      <pre>{{ pretty(row.candidate) }}</pre>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="key" label="策略对象" min-width="220" show-overflow-tooltip />
              <el-table-column label="原运行" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">{{ guardDigest(row.baseline) }}</template>
              </el-table-column>
              <el-table-column label="重放" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">{{ guardDigest(row.candidate) }}</template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!changedGuardDiffs.length" description="治理决策一致" />
          </el-tab-pane>
        </el-tabs>
      </el-card>

      <section v-if="detail && summary && legacyDetailVisible" ref="detailTabsSection" class="run-detail-section">
        <el-tabs v-model="activeTab" class="run-detail-tabs">
        <el-tab-pane v-if="summary.runType === 'AGENT'" label="调度决策" name="supervisor">
          <section class="supervisor-metrics">
            <div v-for="item in supervisorMetrics" :key="item.label" class="supervisor-metric">
              <span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
              <small>{{ item.hint }}</small>
            </div>
          </section>
          <el-table :data="supervisorEvents" row-key="id" class="supervisor-event-table">
            <el-table-column label="阶段" width="136">
              <template #default="{ row }">
                <el-tag :type="phaseTagType(row.spanType)">{{ phaseLabel(row.spanType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="计划 / 选择" min-width="230">
              <template #default="{ row }">{{ supervisorEventLabel(row) }}</template>
            </el-table-column>
            <el-table-column label="结果" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">{{ supervisorEventResultLabel(row) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="112">
              <template #default="{ row }"><el-tag :type="statusTagType(row.status)">{{ executionStatusLabel(row.status) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="耗时" width="100">
              <template #default="{ row }">{{ formatDuration(row.latencyMs) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!supervisorEvents.length" description="该运行尚无结构化调度决策事件" />
        </el-tab-pane>

        <el-tab-pane label="执行路径" name="execution">
          <div class="execution-model" :class="{ 'workflow-only': summary.runType === 'WORKFLOW' }">
            <template v-if="summary.runType === 'AGENT'">
              <div><strong>调度器</strong><small>理解与调度</small></div>
              <span>→</span>
              <div><strong>规划 / 重规划</strong><small>规划与有限重规划</small></div>
              <span>→</span>
              <div><strong>工作流工具</strong><small>选择并调用工作流</small></div>
              <span>→</span>
            </template>
            <div><strong>工作流节点</strong><small>执行 GraphSpec 节点</small></div>
          </div>

          <el-table :data="executionPath" row-key="spanId" stripe>
            <el-table-column label="层级 / 阶段" min-width="260">
              <template #default="{ row }">
                <div class="execution-stage" :style="{ paddingLeft: `${Math.min(row.depth || 0, 4) * 24}px` }">
                  <span v-if="row.depth" class="path-prefix">↳</span>
                  <el-tag size="small" effect="plain" :type="phaseTagType(row.spanType)">
                    {{ phaseLabel(row.spanType) }}
                  </el-tag>
                  <strong>{{ executionPathLabel(row) }}</strong>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="运行时" width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ formatRuntimeTypeLabel(row.runtimeType) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="112">
              <template #default="{ row }"><el-tag size="small" :type="statusTagType(row.status)">{{ executionStatusLabel(row.status) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="开始时间" width="180">
              <template #default="{ row }">{{ formatDateTime(row.startedAt) }}</template>
            </el-table-column>
            <el-table-column label="结束时间" width="180">
              <template #default="{ row }">{{ formatDateTime(row.endedAt) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!executionPath.length" description="暂无结构化执行路径" />
        </el-tab-pane>

        <el-tab-pane label="链路明细" name="spans">
          <el-timeline class="span-timeline">
            <el-timeline-item
              v-for="span in detail.spans"
              :key="span.id"
              :timestamp="span.startedAt"
              :type="statusTagType(span.status)"
              placement="top"
            >
              <div class="span-item">
                <div class="span-head">
                  <div>
                    <strong>{{ spanDisplayName(span) }}</strong>
                    <span>{{ phaseLabel(span.spanType) }} · {{ formatRuntimeTypeLabel(span.runtimeType) }}</span>
                  </div>
                  <div class="span-tags">
                    <el-tag size="small" :type="statusTagType(span.status)">{{ executionStatusLabel(span.status) }}</el-tag>
                    <el-tag size="small" effect="plain">{{ formatDuration(span.latencyMs) }}</el-tag>
                  </div>
                </div>
                <div v-if="span.errorMessage" class="error-text">
                  {{ span.errorCode || 'SPAN_FAILED' }}：{{ executionSummaryLabel(span.errorMessage) }}
                </div>
                <div class="io-grid">
                  <pre>{{ executionSummaryLabel(span.inputSummary) }}</pre>
                  <pre>{{ executionSummaryLabel(span.outputSummary) }}</pre>
                </div>
              </div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-if="!detail.spans.length" description="暂无结构化链路片段" />
        </el-tab-pane>

        <el-tab-pane label="工具调用" name="tools">
          <el-table :data="detail.toolCalls" stripe>
            <el-table-column prop="success" label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.success ? 'success' : 'danger'">{{ row.success ? '成功' : '失败' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="toolName" label="工具" min-width="220" show-overflow-tooltip />
            <el-table-column prop="elapsedMs" label="耗时" width="100">
              <template #default="{ row }">{{ formatDuration(row.elapsedMs) }}</template>
            </el-table-column>
            <el-table-column prop="tokenCost" label="Token 数" width="100" />
            <el-table-column prop="errorCode" label="错误" width="180" show-overflow-tooltip />
            <el-table-column prop="createdAt" label="时间" width="180" />
          </el-table>
          <el-empty v-if="!detail.toolCalls.length" description="该运行未调用工具" />
        </el-tab-pane>

        <el-tab-pane label="治理决策" name="guards">
          <el-table :data="detail.guardDecisions" stripe>
            <el-table-column prop="decision" label="决策" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.decision === 'DENY' ? 'danger' : 'success'">{{ guardDecisionLabel(row.decision) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="140">
              <template #default="{ row }">{{ guardDecisionTypeLabel(row.decisionType) }}</template>
            </el-table-column>
            <el-table-column label="对象类型" width="120">
              <template #default="{ row }">{{ guardTargetKindLabel(row.targetKind) }}</template>
            </el-table-column>
            <el-table-column prop="targetName" label="对象" min-width="180" show-overflow-tooltip />
            <el-table-column label="原因" min-width="240" show-overflow-tooltip>
              <template #default="{ row }">{{ executionSummaryLabel(row.reason) }}</template>
            </el-table-column>
            <el-table-column prop="createdAt" label="时间" width="180" />
          </el-table>
          <el-empty v-if="!detail.guardDecisions.length" description="暂无治理决策记录" />
        </el-tab-pane>

        <el-tab-pane label="发布配置与元数据" name="metadata">
          <section class="snapshot-grid">
            <div class="snapshot-panel">
              <div class="panel-title">根运行元数据</div>
              <pre>{{ pretty(summary.metadata) }}</pre>
            </div>
            <div class="snapshot-panel">
              <div class="panel-title">已发布运行时配置</div>
              <pre>{{ pretty(detail.snapshot?.runtimeConfig) }}</pre>
            </div>
            <div class="snapshot-panel">
              <div class="panel-title">已发布 GraphSpec</div>
              <pre>{{ pretty(detail.snapshot?.graphSpec) }}</pre>
            </div>
            <div class="snapshot-panel">
              <div class="panel-title">配置版本身份</div>
              <pre>{{ pretty(versionIdentity) }}</pre>
            </div>
          </section>
        </el-tab-pane>
        </el-tabs>
      </section>
    </template>

    <AppDrawer
      v-if="summary"
      v-model="contextDrawerVisible"
      title="运行上下文"
      description="用于复现、审计与跨团队协作的完整运行证据。"
      size="560px"
    >
      <section class="context-drawer-section">
        <h3>标识与入口</h3>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="追踪 ID">
            <div class="copyable-value">
              <code>{{ traceId }}</code>
              <el-button link :icon="CopyDocument" @click="copyIdentifier('追踪 ID', traceId)" />
            </div>
          </el-descriptions-item>
          <el-descriptions-item label="会话 ID">
            <div class="copyable-value">
              <code>{{ summary.sessionId || '-' }}</code>
              <el-button
                link
                :icon="CopyDocument"
                :disabled="!summary.sessionId"
                @click="copyIdentifier('会话 ID', summary.sessionId)"
              />
            </div>
          </el-descriptions-item>
          <el-descriptions-item label="用户 ID"><code>{{ summary.userId || '-' }}</code></el-descriptions-item>
          <el-descriptions-item label="租户"><code>{{ summary.tenantId || '-' }}</code></el-descriptions-item>
          <el-descriptions-item label="所属项目">{{ summary.projectCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="运行入口">{{ entryTypeLabel(summary.entryType) }}</el-descriptions-item>
        </el-descriptions>
      </section>

      <section class="context-drawer-section">
        <h3>发布快照</h3>
        <el-descriptions :column="1" border>
          <el-descriptions-item :label="runTypeLabel(summary.runType)">{{ runObjectId(summary) }}</el-descriptions-item>
          <el-descriptions-item label="发布版本">{{ runVersionLabel(summary) }}</el-descriptions-item>
          <el-descriptions-item label="运行时">{{ formatRuntimeTypeLabel(summary.runtimeType) }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ formatDateTime(summary.startedAt) }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ summary.endedAt ? formatDateTime(summary.endedAt) : '—（运行未结束）' }}</el-descriptions-item>
          <el-descriptions-item label="调用统计">
            {{ summary.planCount ?? 0 }} 规划 · {{ summary.replanCount ?? 0 }} 重规划 ·
            {{ summary.workflowCallCount ?? 0 }} Workflow · {{ summary.toolCallCount ?? 0 }} Tool
          </el-descriptions-item>
        </el-descriptions>
      </section>

      <section v-if="summary.replayOfTraceId || compareSource" class="context-drawer-section">
        <h3>重放关系</h3>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="原始运行"><code>{{ summary.replayOfTraceId || compareSource }}</code></el-descriptions-item>
          <el-descriptions-item label="当前运行"><code>{{ traceId }}</code></el-descriptions-item>
        </el-descriptions>
      </section>

      <section class="context-drawer-section">
        <h3>重放边界</h3>
        <el-alert
          type="info"
          :closable="false"
          description="重放固定使用本次运行的历史发布版本与配置快照，不会静默切换到当前最新配置；新运行会保留对原始运行的审计引用。"
        />
      </section>
    </AppDrawer>

    <AppDialog v-model="replayDialogVisible" title="重放运行" width="560px">
      <el-alert
        class="replay-alert"
        type="info"
        :closable="false"
        show-icon
        title="重放使用原运行的已发布配置版本"
        description="重放不会切换到当前活动配置；新的追踪记录将自动与原运行建立对比关系。"
      />
      <div v-if="summary" class="replay-version-box">
        固定版本：<strong>{{ runTypeLabel(summary.runType) }} · {{ runVersionLabel(summary) }} · {{ formatRuntimeTypeLabel(summary.runtimeType) }}</strong>
      </div>
      <details class="replay-advanced">
        <summary>高级覆盖选项 <small>默认复用原运行输入与身份</small></summary>
        <el-form label-width="110px">
        <el-form-item label="覆盖输入">
          <el-input
            v-model="replayForm.messageOverride"
            type="textarea"
            :rows="4"
            placeholder="留空则复用原追踪输入"
          />
        </el-form-item>
        <el-form-item label="用户 ID">
          <el-input v-model="replayForm.userId" clearable placeholder="留空则复用原运行用户" />
        </el-form-item>
        <el-form-item label="会话 ID">
          <el-input v-model="replayForm.sessionId" clearable placeholder="留空则自动生成重放会话" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select
            v-model="replayRoles"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="可选，输入后回车创建"
          />
        </el-form-item>
        </el-form>
      </details>
      <template #footer>
        <el-button @click="replayDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="replaying" @click="replayTrace">开始重放</el-button>
      </template>
    </AppDialog>

    <AppDialog
      v-model="candidateDialogVisible"
      :title="candidateEligibility?.eligible ? '生成 Workflow 候选' : 'Workflow 候选资格'"
      width="640px"
    >
      <template v-if="candidateLoading">
        <el-skeleton :rows="6" animated />
      </template>
      <template v-else-if="candidateEligibility?.eligible">
        <el-alert
          type="success"
          :closable="false"
          show-icon
          title="资格检查通过，可以创建候选任务"
          description="只创建并校验 DRAFT Workflow；不会自动发布、绑定 Agent 或改动线上路由。"
        />
        <el-descriptions :column="1" border class="candidate-summary">
          <el-descriptions-item label="源追踪">{{ candidateEligibility.traceId }}</el-descriptions-item>
          <el-descriptions-item label="源 Workflow">
            {{ candidateEligibility.sourceWorkflowId }} · {{ candidateEligibility.sourceWorkflowVersion }}
          </el-descriptions-item>
          <el-descriptions-item label="资格证据">
            <div class="hint-list">
              <span v-for="item in candidateEligibility.evidence" :key="item">{{ item }}</span>
            </div>
          </el-descriptions-item>
        </el-descriptions>
        <el-form label-width="112px">
          <el-form-item label="AI 编程工具">
            <el-select v-model="candidateProvider" style="width: 100%">
              <el-option label="Codex" value="CODEX" />
              <el-option label="Cursor" value="CURSOR" />
              <el-option label="Trae" value="TRAE" />
              <el-option label="Claude Code" value="CLAUDE_CODE" />
            </el-select>
          </el-form-item>
        </el-form>
      </template>
      <template v-else-if="candidateEligibility">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="当前运行不满足 Workflow 候选规则"
          description="这些限制会阻止把失败或缺少治理证据的偶然轨迹固化成 Workflow。"
        />
        <div class="candidate-blocker-panel">
          <strong>{{ candidateEligibility.blockers.length }} 项规则未满足</strong>
          <ol>
            <li v-for="blocker in candidateEligibility.blockers" :key="blocker">{{ blocker }}</li>
          </ol>
        </div>
      </template>
      <el-empty v-else description="暂时无法读取候选资格，请刷新运行详情后重试" />
      <template #footer>
        <el-button @click="candidateDialogVisible = false">关闭</el-button>
        <el-button
          v-if="candidateEligibility?.eligible"
          type="primary"
          :loading="candidateTaskBusy"
          @click="createCandidateTask"
        >
          创建 AI Coding 任务
        </el-button>
      </template>
    </AppDialog>

    <AppDialog
      v-model="candidateHandoffVisible"
      title="Workflow 候选 AI Coding 交接包"
      width="860px"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="将下方交接包复制到所选 AI 编程工具"
        description="重复点击同一轨迹会复用未终态任务；同一任务的 Runtime 草稿也会幂等复用。"
      />
      <el-input
        class="candidate-prompt"
        :model-value="candidateHandoffPrompt"
        type="textarea"
        :rows="20"
        readonly
      />
      <template #footer>
        <el-button @click="candidateHandoffVisible = false">关闭</el-button>
        <el-button
          :disabled="!candidateTaskId"
          @click="openCandidateTaskDetail"
        >
          查看任务闭环
        </el-button>
        <el-button type="primary" @click="copyCandidateHandoff">复制交接包</el-button>
      </template>
    </AppDialog>

    <AppDrawer
      v-model="candidateTaskDrawerVisible"
      title="Workflow 候选任务"
      size="760px"
    >
      <AiCodingTaskDetailPanel
        :detail="candidateTaskDetail"
        :loading="candidateTaskActionBusy"
        :busy="candidateTaskActionBusy"
        @refresh="refreshCandidateTask"
        @answer="answerCandidateTaskQuestion"
        @reissue="reissueCandidateTaskHandoff"
        @verify="verifyCandidateTask"
        @acceptance="finishCandidateTaskAcceptance"
        @cancel="cancelCandidateTask"
      />
    </AppDrawer>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import AiCodingTaskDetailPanel from '@/components/ai-coding/AiCodingTaskDetailPanel.vue'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CopyDocument, MoreFilled, Refresh, VideoPlay } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import RunOpsInvestigationWorkbench from './components/RunOpsInvestigationWorkbench.vue'
import {
  compareRunOpsTrace,
  createTraceWorkflowCandidateTask,
  getRunOpsDetail,
  getTraceWorkflowCandidateEligibility,
  replayRunOpsTrace,
} from '@/api/runops'
import { issueAiCodingHandoff } from '@/api/aiCodingTasks'
import { useAiCodingTask } from '@/composables/useAiCodingTask'
import { formatRuntimeTypeLabel } from '@/utils/registryLabels'
import type { AiCodingExecutorProvider } from '@/types/aiCodingTask'
import type {
  ReplayRequest,
  RunComparison,
  RunDetail,
  RunExecutionPathItem,
  RunGuardDecision,
  RunSpan,
  RunStatus,
  RunSummary,
  RunToolCall,
  TraceWorkflowCandidateEligibility,
} from '@/types/runops'

type DetailTabName = 'supervisor' | 'execution' | 'spans' | 'tools' | 'guards' | 'metadata'
type DiagnosticTone = 'success' | 'danger' | 'warning' | 'primary' | 'info'
type RunOpsMoreCommand = 'context' | 'candidate' | 'compare'

interface FailureFocus {
  kindLabel: string
  title: string
  status?: string
  code?: string
  message: string
  tab: DetailTabName
}

interface DiagnosticState {
  tone: DiagnosticTone
  tagType: DiagnosticTone
  label: string
  title: string
  description: string
}

const route = useRoute()
const router = useRouter()
const traceId = computed(() => route.params.traceId as string)
const legacyDetailVisible = false
const loading = ref(false)
const replaying = ref(false)
const replayDialogVisible = ref(false)
const contextDrawerVisible = ref(false)
const candidateDialogVisible = ref(false)
const candidateHandoffVisible = ref(false)
const candidateLoading = ref(false)
const candidateTaskBusy = ref(false)
const candidateTaskActionBusy = ref(false)
const candidateTaskDrawerVisible = ref(false)
const candidateTaskId = ref('')
const candidateEligibility = ref<TraceWorkflowCandidateEligibility | null>(null)
const candidateProvider = ref<AiCodingExecutorProvider>('CODEX')
const candidateHandoffPrompt = ref('')
const candidateTaskKernel = useAiCodingTask()
const candidateTaskDetail = candidateTaskKernel.selectedTaskDetail
const detail = ref<RunDetail | null>(null)
const comparison = ref<RunComparison | null>(null)
const replayForm = ref<ReplayRequest>({})
const replayRoles = ref<string[]>([])
const activeTab = ref<DetailTabName>('execution')
const detailTabsSection = ref<HTMLElement | null>(null)
const investigationWorkbenchRef = ref<InstanceType<typeof RunOpsInvestigationWorkbench> | null>(null)
const summary = computed(() => detail.value?.summary)
const compareSource = computed(() =>
  (route.query.compareWith as string | undefined) || summary.value?.replayOfTraceId,
)
const supervisorEvents = computed(() => (detail.value?.spans || []).filter((span) =>
  ['PLAN', 'REPLAN', 'WORKFLOW_TOOL'].includes(span.spanType || ''),
))

const supervisorMetrics = computed(() => {
  const run = summary.value
  if (!run) return []
  return [
    { label: '配置版本', value: run.agentConfigVersion || '-', hint: run.agentConfigVersionId == null ? '未记录版本 ID' : `版本 ID ${run.agentConfigVersionId}` },
    { label: '计划次数', value: run.planCount ?? 0, hint: '调度器首次规划' },
    { label: '重规划', value: run.replanCount ?? 0, hint: '有限重规划次数' },
    { label: '工作流调用', value: run.workflowCallCount ?? 0, hint: '工作流工具' },
  ]
})

const executionPath = computed<RunExecutionPathItem[]>(() => {
  if (detail.value?.executionPath?.length) return detail.value.executionPath
  return (detail.value?.spans || []).map((span) => ({
    spanId: span.spanId || String(span.id),
    parentSpanId: span.parentSpanId,
    depth: semanticDepth(span),
    spanType: span.spanType,
    label: spanDisplayName(span),
    status: span.status,
    nodeId: span.nodeId,
    toolName: span.toolName,
    runtimeType: span.runtimeType,
    startedAt: span.startedAt,
    endedAt: span.endedAt,
  }))
})

function executionPathLabel(item: RunExecutionPathItem) {
  const labels: Record<string, string> = {
    Supervisor: '智能体调度',
    Plan: '首次规划',
    Replan: '有限重规划',
    'Workflow Tool': '工作流工具',
    'Workflow Node': '工作流节点',
  }
  const label = item.label || item.nodeId || item.toolName || item.spanId
  return label ? labels[label] || label : '-'
}

const failureFocus = computed<FailureFocus | null>(() => {
  const run = summary.value
  if (!run) return null

  const failedSpan = detail.value?.spans
    .filter((span) => isFailureStatus(span.status) || Boolean(span.errorCode || span.errorMessage))
    .sort((left, right) => {
      const depthDelta = semanticDepth(right) - semanticDepth(left)
      if (depthDelta !== 0) return depthDelta
      return timestampValue(left.startedAt) - timestampValue(right.startedAt)
    })[0]
  if (failedSpan) {
    const title = failureSpanTitle(failedSpan)
    const codeSuffix = failedSpan.errorCode ? `，错误码 ${failedSpan.errorCode}` : ''
    return {
      kindLabel: phaseLabel(failedSpan.spanType),
      title,
      status: failedSpan.status || 'FAILED',
      code: failedSpan.errorCode,
      message: compactMessage(
        failedSpan.errorMessage || failedSpan.outputSummary,
        `${phaseLabel(failedSpan.spanType)}“${title}”未成功完成${codeSuffix}。`,
      ),
      tab: 'spans',
    }
  }

  const failedTool = detail.value?.toolCalls.find((tool) => !tool.success)
  if (failedTool) {
    return {
      kindLabel: '工具调用',
      title: failedTool.toolName || '未命名工具',
      status: 'FAILED',
      code: failedTool.errorCode,
      message: compactMessage(
        failedTool.resultSummary,
        '工具调用失败，但没有记录更具体的错误信息。',
      ),
      tab: 'tools',
    }
  }

  const failedPath = executionPath.value.find((item) => isFailureStatus(item.status))
  if (failedPath) {
    return {
      kindLabel: phaseLabel(failedPath.spanType),
      title: executionPathLabel(failedPath),
      status: failedPath.status,
      message: '执行路径中记录了非成功阶段，请展开对应链路查看输入、输出和错误码。',
      tab: 'execution',
    }
  }

  if (isFailureStatus(run.status) || run.errorCode || run.errorMessage) {
    return {
      kindLabel: '根运行',
      title: runObjectLabel(run),
      status: run.status,
      code: run.errorCode,
      message: compactMessage(run.errorMessage, '根运行失败，但没有记录更具体的错误信息。'),
      tab: 'execution',
    }
  }

  return null
})

const diagnosticState = computed<DiagnosticState>(() => {
  const run = summary.value
  if (!run) {
    return {
      tone: 'info',
      tagType: 'info',
      label: '正在加载',
      title: '正在读取运行结果',
      description: '运行数据加载完成后将在此给出明确结论。',
    }
  }
  if (run.status === 'RUNNING') {
    return {
      tone: 'primary',
      tagType: 'primary',
      label: '运行中',
      title: '本次运行仍在执行',
      description: '页面会保留当前已产生的执行路径，可刷新查看最新进展。',
    }
  }
  if (run.status === 'SUSPENDED') {
    return {
      tone: 'warning',
      tagType: 'warning',
      label: runStatusLabel(run),
      title: run.suspensionReason === 'APPROVAL' ? '运行正在等待审批' : '运行正在等待用户输入',
      description: '完成当前交互后，运行会从暂停位置继续执行。',
    }
  }
  if (failureFocus.value) {
    return {
      tone: 'danger',
      tagType: 'danger',
      label: run.status === 'COMPLETED' ? '异常完成' : runStatusLabel(run),
      title: run.status === 'COMPLETED'
        ? '流程已结束，但执行链中存在失败'
        : `本次运行${runStatusLabel(run)}`,
      description: failureFocus.value.message,
    }
  }
  if (run.status === 'CANCELLED') {
    return {
      tone: 'info',
      tagType: 'info',
      label: '已取消',
      title: '本次运行已取消',
      description: '运行没有继续执行，已产生的链路仍可用于审计。',
    }
  }
  if (detail.value?.repairHints?.length) {
    return {
      tone: 'warning',
      tagType: 'warning',
      label: '需要检查',
      title: '流程已结束，但仍有诊断提示',
      description: detail.value.repairHints[0],
    }
  }
  return {
    tone: 'success',
    tagType: 'success',
    label: '运行成功',
    title: '本次运行已成功完成',
    description: '未发现失败阶段、失败工具调用或治理拒绝。',
  }
})

const diagnosticIcon = computed(() => {
  if (diagnosticState.value.tone === 'success') return '✓'
  if (diagnosticState.value.tone === 'primary') return '↻'
  if (diagnosticState.value.tone === 'warning') return '…'
  if (diagnosticState.value.tone === 'danger') return '!'
  return 'i'
})

const changedSummaryDiffs = computed(() => comparison.value?.summaryDiffs.filter((item) => item.changed) ?? [])
const changedSpanDiffs = computed(() => comparison.value?.spanDiffs.filter((item) => item.changed) ?? [])
const changedToolDiffs = computed(() => comparison.value?.toolDiffs.filter((item) => item.changed) ?? [])
const changedGuardDiffs = computed(() => comparison.value?.guardDiffs.filter((item) => item.changed) ?? [])

const summaryItems = computed(() => {
  const run = summary.value
  if (!run) return []
  return [
    { label: '所属项目', value: run.projectCode || '-', hint: runObjectId(run) },
    { label: '发布版本', value: runVersionLabel(run), hint: run.runtimeType ? formatRuntimeTypeLabel(run.runtimeType) : '未记录运行时' },
    { label: '运行入口', value: entryTypeLabel(run.entryType), hint: run.userId ? `用户 ${shortIdentifier(run.userId)}` : '匿名用户' },
    { label: '调度', value: `${run.planCount ?? 0} 次规划`, hint: `${run.replanCount ?? 0} 次重规划` },
    { label: '执行调用', value: `${run.workflowCallCount ?? 0} 个工作流`, hint: `${run.toolCallCount ?? 0} 个工具调用` },
    { label: '治理事件', value: `${run.guardDenyCount ?? 0} 次拒绝`, hint: `${run.approvalCount ?? 0} 次审批` },
  ]
})

const detailTitle = computed(() => {
  const run = summary.value
  if (!run) return '运行详情'
  return runObjectLabel(run)
})

const versionIdentity = computed(() => {
  const run = summary.value
  if (!run) return {}
  return {
    runType: run.runType,
    agentId: run.agentId,
    agentKeySlug: run.agentKeySlug,
    agentConfigVersionId: run.agentConfigVersionId,
    agentConfigVersion: run.agentConfigVersion,
    workflowId: run.workflowId,
    workflowKeySlug: run.workflowKeySlug,
    workflowVersionId: run.workflowVersionId,
    workflowVersion: run.workflowVersion,
  }
})

async function loadDetail() {
  loading.value = true
  candidateTaskKernel.reset()
  candidateTaskId.value = ''
  candidateHandoffPrompt.value = ''
  candidateTaskDrawerVisible.value = false
  candidateHandoffVisible.value = false
  try {
    const { data } = await getRunOpsDetail(traceId.value)
    detail.value = data
    await Promise.all([loadComparison(), loadCandidateEligibility()])
  } catch {
    detail.value = null
    comparison.value = null
    ElMessage.error('加载运行详情失败')
  } finally {
    loading.value = false
  }
}

async function loadCandidateEligibility() {
  candidateLoading.value = true
  candidateEligibility.value = null
  try {
    const { data } = await getTraceWorkflowCandidateEligibility(traceId.value)
    candidateEligibility.value = data
  } catch {
    candidateEligibility.value = null
  } finally {
    candidateLoading.value = false
  }
}

function openCandidateDialog() {
  candidateDialogVisible.value = true
}

function handleMoreCommand(command: RunOpsMoreCommand) {
  if (command === 'context') {
    contextDrawerVisible.value = true
    return
  }
  if (command === 'candidate') {
    openCandidateDialog()
    return
  }
  investigationWorkbenchRef.value?.showCompare()
}

async function createCandidateTask() {
  if (!candidateEligibility.value?.eligible) return
  candidateTaskBusy.value = true
  try {
    const { data } = await createTraceWorkflowCandidateTask(traceId.value, {
      executorProvider: candidateProvider.value,
    })
    const handoff = (await issueAiCodingHandoff(data.task.taskId)).data
    candidateTaskId.value = data.task.taskId
    candidateHandoffPrompt.value = handoff.prompt
    candidateDialogVisible.value = false
    candidateHandoffVisible.value = true
    ElMessage.success(data.created ? '候选任务已创建' : '已复用该轨迹的未完成候选任务')
  } catch {
    ElMessage.error('创建 Workflow 候选任务失败')
  } finally {
    candidateTaskBusy.value = false
  }
}

async function runCandidateTaskAction<T>(
  action: () => Promise<T>,
  errorMessage: string,
): Promise<T | null> {
  candidateTaskActionBusy.value = true
  try {
    return await action()
  } catch (error) {
    ElMessage.error((error as Error).message || errorMessage)
    return null
  } finally {
    candidateTaskActionBusy.value = false
  }
}

async function openCandidateTaskDetail() {
  if (!candidateTaskId.value) return
  candidateHandoffVisible.value = false
  candidateTaskDrawerVisible.value = true
  await refreshCandidateTask(candidateTaskId.value)
}

async function refreshCandidateTask(taskId: string) {
  await runCandidateTaskAction(
    () => candidateTaskKernel.refreshTask(taskId),
    '刷新 Workflow 候选任务失败',
  )
}

async function answerCandidateTaskQuestion(
  taskId: string,
  questionId: string,
  answer: string,
) {
  const result = await runCandidateTaskAction(
    () => candidateTaskKernel.answerQuestion(taskId, questionId, answer),
    '回答写回失败',
  )
  if (result) ElMessage.success('回答已写回候选任务')
}

async function reissueCandidateTaskHandoff(taskId: string) {
  const handoff = await runCandidateTaskAction(
    () => candidateTaskKernel.reissueHandoff(taskId),
    '重新生成交接包失败',
  )
  if (!handoff) return
  candidateHandoffPrompt.value = handoff.prompt
  candidateTaskDrawerVisible.value = false
  candidateHandoffVisible.value = true
}

async function verifyCandidateTask(taskId: string) {
  const result = await runCandidateTaskAction(
    () => candidateTaskKernel.verifyAcceptanceReadiness(taskId),
    '平台验证失败',
  )
  if (!result) return
  ElMessage[result.acceptanceReady ? 'success' : 'warning'](
    result.acceptanceReady
      ? '平台已确认候选草稿、发布校验和成功重放证据'
      : `仍有 ${result.blockers.length} 项未通过平台验证`,
  )
}

async function finishCandidateTaskAcceptance(
  taskId: string,
  passed: boolean,
  message: string,
) {
  const result = await runCandidateTaskAction(
    () => candidateTaskKernel.finishAcceptance(taskId, passed, message),
    '写入验收结果失败',
  )
  if (result) {
    ElMessage[passed ? 'success' : 'warning'](
      passed ? '候选任务验收已通过' : '候选任务已标记为验收不通过',
    )
  }
}

async function cancelCandidateTask(taskId: string) {
  const result = await runCandidateTaskAction(
    () => candidateTaskKernel.cancelTask(taskId),
    '取消候选任务失败',
  )
  if (result) ElMessage.success('候选任务已取消')
}

async function copyCandidateHandoff() {
  if (!candidateHandoffPrompt.value) return
  try {
    await navigator.clipboard.writeText(candidateHandoffPrompt.value)
    ElMessage.success('交接包已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

async function loadComparison() {
  const sourceTraceId = compareSource.value
  comparison.value = null
  if (!sourceTraceId || sourceTraceId === traceId.value) return
  try {
    const { data } = await compareRunOpsTrace(sourceTraceId, traceId.value)
    comparison.value = data
  } catch {
    ElMessage.error('加载重放差异对比失败')
  }
}

function openReplayDialog() {
  replayForm.value = { userId: summary.value?.userId }
  replayRoles.value = []
  replayDialogVisible.value = true
}

async function replayTrace() {
  if (!detail.value) return
  replaying.value = true
  try {
    const request: ReplayRequest = { ...replayForm.value, roles: replayRoles.value }
    const { data } = await replayRunOpsTrace(traceId.value, request)
    if (!data?.replayTraceId) {
      ElMessage.warning('重放完成，但未返回新的追踪 ID')
      return
    }
    replayDialogVisible.value = false
    ElMessage.success('已按原发布配置完成重放，正在打开新的运行详情')
    router.push({ path: `/runops/${data.replayTraceId}`, query: { compareWith: traceId.value } })
  } catch {
    ElMessage.error('重放运行失败')
  } finally {
    replaying.value = false
  }
}

function runObjectLabel(run: RunSummary) {
  return run.runType === 'AGENT'
    ? run.agentName || run.agentKeySlug || run.agentId || '-'
    : run.workflowName || run.workflowKeySlug || run.workflowId || '-'
}

function runObjectId(run: RunSummary) {
  return run.runType === 'AGENT'
    ? run.agentKeySlug || run.agentId || '-'
    : run.workflowKeySlug || run.workflowId || '-'
}

function runVersionLabel(run: RunSummary) {
  const version = run.runType === 'AGENT' ? run.agentConfigVersion : run.workflowVersion
  const versionId = run.runType === 'AGENT' ? run.agentConfigVersionId : run.workflowVersionId
  if (!version && versionId == null) return '-'
  return `${version || '-'}${versionId == null ? '' : ` · #${versionId}`}`
}

function runTypeLabel(runType?: string) {
  const labels: Record<string, string> = {
    AGENT: '智能体',
    WORKFLOW: '工作流',
  }
  return runType ? labels[runType] || runType : '-'
}

function entryTypeLabel(entryType?: string) {
  const labels: Record<string, string> = {
    DEBUG: '调试',
    EMBED: '嵌入',
    GATEWAY: '网关',
    EVAL: '评测',
    REPLAY: '重放',
    API: 'API',
  }
  return entryType ? labels[entryType] || entryType : '-'
}

function formatDuration(ms?: number | null) {
  const value = Number(ms ?? 0)
  if (!Number.isFinite(value) || value <= 0) return '0 毫秒'
  if (value < 1000) return `${Math.round(value)} 毫秒`
  if (value < 60_000) {
    const seconds = value / 1000
    return `${seconds >= 10 ? seconds.toFixed(1) : seconds.toFixed(2)} 秒`
  }
  const minutes = Math.floor(value / 60_000)
  const seconds = (value % 60_000) / 1000
  return seconds > 0 ? `${minutes} 分 ${seconds.toFixed(1)} 秒` : `${minutes} 分钟`
}

function formatTokenCount(value?: number | null) {
  const count = Number(value ?? 0)
  if (!Number.isFinite(count) || count <= 0) return '0 个'
  return `${Math.round(count).toLocaleString('zh-CN')} 个`
}

function formatDateTime(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}

function formatCompactDateTime(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const parts = new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).formatToParts(date)
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${values.month}-${values.day} ${values.hour}:${values.minute}:${values.second}`
}

function shortIdentifier(value?: string | null) {
  if (!value) return '-'
  if (value.length <= 24) return value
  return `${value.slice(0, 10)}…${value.slice(-8)}`
}

function compactMessage(value?: string | null, fallback = '未记录详细信息。') {
  const text = value?.replace(/\s+/g, ' ').trim()
  if (!text || text === '[omitted]' || text === '[redacted]') return fallback
  return text.length > 240 ? `${text.slice(0, 237)}…` : text
}

function timestampValue(value?: string | null) {
  if (!value) return Number.MAX_SAFE_INTEGER
  const timestamp = new Date(value).getTime()
  return Number.isNaN(timestamp) ? Number.MAX_SAFE_INTEGER : timestamp
}

function failureSpanTitle(span: RunSpan) {
  const nodeName = typeof span.metadata?.nodeName === 'string' ? span.metadata.nodeName : undefined
  if (span.spanType === 'WORKFLOW_TOOL') {
    const workflowName = typeof span.metadata?.workflowName === 'string' ? span.metadata.workflowName : undefined
    return span.toolName || workflowName || nodeName || span.nodeId || '未命名工作流工具'
  }
  return nodeName || span.nodeId || span.toolName || spanDisplayName(span)
}

function statusLabel(status: RunStatus) {
  const labels: Record<RunStatus, string> = {
    RUNNING: '运行中',
    SUSPENDED: '已暂停',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
    TIMED_OUT: '已超时',
  }
  return labels[status]
}

function runStatusLabel(run: RunSummary) {
  if (run.status !== 'SUSPENDED') return statusLabel(run.status)
  if (run.suspensionReason === 'APPROVAL') return '等待审批'
  if (run.suspensionReason === 'USER_INPUT') return '等待用户交互'
  return statusLabel(run.status)
}

function lifecycleStatusLabel(run: RunSummary) {
  return run.status === 'COMPLETED' ? '流程已结束' : runStatusLabel(run)
}

function isFailureStatus(status?: string) {
  return status === 'FAILED' || status === 'TIMED_OUT' || status === 'TIMEOUT'
}

function statusTagType(status?: string) {
  if (status === 'COMPLETED' || status === 'SUCCESS') return 'success'
  if (status === 'RUNNING') return 'primary'
  if (status === 'SUSPENDED' || status === 'WAITING' || status === 'WAITING_APPROVAL' || status === 'WAITING_USER') return 'warning'
  if (status === 'CANCELLED') return 'info'
  return 'danger'
}

function executionStatusLabel(status?: string) {
  const labels: Record<string, string> = {
    RUNNING: '运行中',
    SUSPENDED: '已暂停',
    COMPLETED: '已完成',
    SUCCESS: '成功',
    FAILED: '失败',
    CANCELLED: '已取消',
    TIMED_OUT: '已超时',
    TIMEOUT: '已超时',
    WAITING: '等待中',
    WAITING_APPROVAL: '等待审批',
    WAITING_USER: '等待用户交互',
    RECORDED: '已记录',
    EXPIRED: '已过期',
  }
  return status ? labels[status] || status : '-'
}

function phaseLabel(spanType?: string) {
  const labels: Record<string, string> = {
    SUPERVISOR: '调度器',
    PLAN: '规划',
    REPLAN: '重规划',
    WORKFLOW_TOOL: '工作流工具',
  }
  return spanType ? labels[spanType] || '工作流节点' : '工作流节点'
}

function phaseTagType(spanType?: string) {
  if (spanType === 'SUPERVISOR') return 'primary'
  if (spanType === 'PLAN') return 'primary'
  if (spanType === 'REPLAN') return 'warning'
  if (spanType === 'WORKFLOW_TOOL') return 'success'
  return 'info'
}

function semanticDepth(span: RunSpan) {
  if (span.spanType === 'SUPERVISOR') return 0
  if (span.spanType === 'PLAN' || span.spanType === 'REPLAN') return 1
  if (span.spanType === 'WORKFLOW_TOOL') return 2
  return summary.value?.runType === 'AGENT' ? 3 : 1
}

function spanDisplayName(span: RunSpan) {
  const nodeName = span.metadata?.nodeName
  const workflowName = span.metadata?.workflowName
  const spanTypeLabels: Record<string, string> = {
    SUPERVISOR: '智能体调度',
    PLAN: '规划',
    REPLAN: '重规划',
    WORKFLOW_TOOL: '工作流工具',
  }
  const spanTypeLabel = span.spanType ? spanTypeLabels[span.spanType] || span.spanType : undefined
  const base = span.spanType === 'WORKFLOW_TOOL'
    ? span.toolName || (typeof workflowName === 'string' ? workflowName : undefined) || span.nodeId || spanTypeLabel || span.spanId || '-'
    : span.nodeId || span.toolName || spanTypeLabel || span.spanId || '-'
  return nodeName ? `${base} · ${nodeName}` : base
}

function supervisorEventLabel(span: RunSpan) {
  if (span.spanType === 'WORKFLOW_TOOL') {
    const version = span.metadata?.workflowVersion
    return `${span.toolName || span.nodeId || '工作流'}${version ? ` · ${version}` : ''}`
  }
  const planNo = span.metadata?.planNo
  return planNo ? `第 ${planNo} 次规划` : span.nodeId || phaseLabel(span.spanType)
}

function supervisorEventResultLabel(span: RunSpan) {
  const summaryText = executionSummaryLabel(span.outputSummary)
  if (!['PLAN', 'REPLAN'].includes(span.spanType || '') || !span.outputSummary?.startsWith('{')) {
    return summaryText
  }
  try {
    const result = JSON.parse(span.outputSummary) as Record<string, unknown>
    const planNo = Number(result.planNo || span.metadata?.planNo || 0)
    const stepCount = Number(result.stepCount || 0)
    const status = executionStatusLabel(typeof result.status === 'string' ? result.status : undefined)
    const planText = planNo > 0 ? `第 ${planNo} 次规划` : '规划'
    const stepText = Number.isFinite(stepCount) ? `，共 ${stepCount} 个步骤` : ''
    return `${status === '-' ? '' : status}${planText}${stepText}`
  } catch {
    return summaryText
  }
}

function diffFieldLabel(field: string) {
  const labels: Record<string, string> = {
    runType: '运行类型',
    entryType: '运行入口',
    status: '运行状态',
    suspensionReason: '暂停原因',
    agentId: '智能体 ID',
    agentConfigVersionId: '智能体配置版本 ID',
    workflowId: '工作流 ID',
    workflowVersionId: '工作流版本 ID',
    runtimeType: '运行时类型',
    latencyMs: '耗时',
    tokenCost: 'Token 数',
    planCount: '规划次数',
    replanCount: '重规划次数',
    workflowCallCount: '工作流调用次数',
    toolCallCount: '工具调用次数',
    guardDenyCount: '治理拒绝次数',
    approvalCount: '审批次数',
    errorCode: '错误码',
  }
  return labels[field] || field
}

function displayDiffValue(field: string, value: unknown) {
  if (value == null || value === '') return '-'
  if (field === 'runType') return runTypeLabel(String(value))
  if (field === 'entryType') return entryTypeLabel(String(value))
  if (field === 'status') return executionStatusLabel(String(value))
  if (field === 'suspensionReason') {
    const labels: Record<string, string> = {
      APPROVAL: '等待审批',
      USER_INPUT: '等待用户输入',
    }
    return labels[String(value)] || String(value)
  }
  if (field === 'runtimeType') return formatRuntimeTypeLabel(String(value))
  if (field === 'latencyMs') return formatDuration(Number(value))
  if (field === 'tokenCost') return `${formatTokenCount(Number(value))} Token`
  if (typeof value === 'boolean') return value ? '是' : '否'
  return String(value)
}

function executionSummaryLabel(summary?: string) {
  if (!summary) return '-'
  if (summary === '[omitted]') return '[已省略]'
  if (summary === '[redacted]') return '[已脱敏]'
  return summary
}

function guardDecisionLabel(decision?: string) {
  const labels: Record<string, string> = {
    ALLOW: '允许',
    DENY: '拒绝',
    WAITING_APPROVAL: '等待审批',
  }
  return decision ? labels[decision] || decision : '-'
}

function guardDecisionTypeLabel(decisionType?: string) {
  const labels: Record<string, string> = {
    SUPERVISOR_TOOL_POLICY: '调度工具策略',
  }
  return decisionType ? labels[decisionType] || decisionType : '-'
}

function guardTargetKindLabel(targetKind?: string) {
  const labels: Record<string, string> = {
    WORKFLOW_TOOL: '工作流工具',
  }
  return targetKind ? labels[targetKind] || targetKind : '-'
}

function spanDigest(span?: RunSpan) {
  if (!span) return '缺失'
  const summaryText = ['PLAN', 'REPLAN'].includes(span.spanType || '')
    ? supervisorEventResultLabel(span)
    : executionSummaryLabel(span.outputSummary)
  return `${executionStatusLabel(span.status)} · ${formatDuration(span.latencyMs)} · ${span.errorCode || summaryText}`
}

function toolDigest(tool?: RunToolCall) {
  if (!tool) return '缺失'
  return `${tool.success ? '成功' : '失败'} · ${formatDuration(tool.elapsedMs)} · ${tool.errorCode || tool.resultSummary || '-'}`
}

function guardDigest(guard?: RunGuardDecision) {
  if (!guard) return '缺失'
  return `${guardDecisionLabel(guard.decision)} · ${guard.reason || '-'}`
}

async function copyIssueSummary() {
  const run = summary.value
  if (!run) return
  const text = [
    `追踪 ID: ${run.traceId}`,
    `运行类型: ${runTypeLabel(run.runType)} (${run.runType})`,
    `运行对象: ${runObjectLabel(run)} (${runObjectId(run)})`,
    `发布版本: ${runVersionLabel(run)}`,
    `运行入口: ${entryTypeLabel(run.entryType)} (${run.entryType})`,
    `运行状态: ${runStatusLabel(run)} (${run.status})`,
    `诊断结论: ${diagnosticState.value.label}`,
    `错误: ${run.errorCode || '-'} ${run.errorMessage || ''}`.trim(),
    `耗时: ${formatDuration(run.latencyMs)}`,
    `规划/重规划: ${run.planCount ?? 0}/${run.replanCount ?? 0}`,
    `工作流/工具调用: ${run.workflowCallCount ?? 0}/${run.toolCallCount ?? 0}`,
    `修复建议: ${(detail.value?.repairHints || []).join(' | ') || '-'}`,
  ].join('\n')
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('运行摘要已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}

async function copyIdentifier(label: string, value?: string | null) {
  if (!value) return
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.error(`${label}复制失败`)
  }
}

function focusFailure() {
  if (!failureFocus.value) return
  nextTick(() => {
    investigationWorkbenchRef.value?.focusFailure()
  })
}

function pretty(value: unknown) {
  if (value == null) return '-'
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

watch(traceId, () => {
  activeTab.value = 'execution'
  loadDetail()
})
onMounted(loadDetail)
</script>

<style scoped lang="scss">
.runops-detail {
  --layout-page-header-art-opacity: 0.18;
  min-width: 0;
  overflow-x: hidden;
}

.runops-detail :deep(.app-page-header) {
  border: 1px solid var(--border-color);
  box-shadow: none;
}

.replay-alert {
  margin-bottom: 16px;
}

.candidate-summary,
.candidate-prompt {
  margin-top: 16px;
}

.diagnostic-overview {
  --diagnostic-tone: var(--status-info);
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(380px, 0.9fr) minmax(0, 1.1fr);
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--diagnostic-tone) 30%, var(--border-color));
  border-left: 4px solid var(--diagnostic-tone);
  border-radius: 12px;
  background: var(--card-bg);
  box-shadow: 0 14px 30px -28px color-mix(in srgb, var(--diagnostic-tone) 55%, transparent);

  &.is-success { --diagnostic-tone: var(--status-success); }
  &.is-danger { --diagnostic-tone: var(--status-danger); }
  &.is-warning { --diagnostic-tone: var(--status-warning); }
  &.is-primary { --diagnostic-tone: var(--brand-primary); }
}

.diagnostic-result {
  min-width: 0;
  padding: 20px 22px;
  background: color-mix(in srgb, var(--diagnostic-tone) 6%, var(--card-bg));

  h2,
  p {
    margin: 0;
  }

  h2 {
    margin-top: 10px;
    color: var(--text-primary);
    font-size: clamp(20px, 1.6vw, 26px);
    line-height: 1.35;
  }

  > p {
    display: -webkit-box;
    margin-top: 8px;
    overflow: hidden;
    color: var(--text-secondary);
    line-height: 1.65;
    overflow-wrap: anywhere;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }
}

.diagnostic-result__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  > span {
    color: var(--text-secondary);
    font-size: 13px;
    font-weight: 700;
    letter-spacing: 0.06em;
  }
}

.run-vitals {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 18px 0 0;

  div {
    min-width: 0;
  }

  dt,
  dd {
    margin: 0;
  }

  dt {
    color: var(--text-secondary);
    font-size: 12px;
  }

  dd {
    margin-top: 4px;
    overflow: hidden;
    color: var(--text-primary);
    font-weight: 700;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.diagnostic-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 18px;

  :deep(.el-button + .el-button) {
    margin-left: 0;
  }
}

.summary-grid {
  display: grid;
  min-width: 0;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  align-content: stretch;
  margin: 0;
}

.snapshot-panel,
.span-item {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--card-bg);
}

.summary-card {
  min-width: 0;
  padding: 16px 18px;
  border-left: 1px solid var(--border-color);
  border-bottom: 1px solid var(--border-color);

  dt,
  dd,
  small {
    display: block;
    min-width: 0;
    margin: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  dt,
  small {
    color: var(--text-secondary);
  }

  dt {
    font-size: 12px;
  }

  dd {
    margin: 7px 0 3px;
    color: var(--text-primary);
    font-size: 16px;
    font-weight: 750;
  }

  small {
    font-size: 12px;
  }
}

.identifier-strip {
  display: grid;
  min-width: 0;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.identifier-item {
  display: grid;
  min-width: 0;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid var(--border-color);
  border-radius: 9px;
  background: var(--card-bg);

  > span {
    color: var(--text-secondary);
    font-size: 12px;
    font-weight: 700;
  }

  code {
    min-width: 0;
    overflow: hidden;
    color: var(--text-primary);
    font-family: var(--el-font-family-monospace, ui-monospace, SFMono-Regular, Consolas, monospace);
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.diagnosis-panel {
  min-width: 0;
  padding: 18px 20px;
  border: 1px solid color-mix(in srgb, var(--status-danger) 28%, var(--border-color));
  border-radius: 12px;
  background: var(--card-bg);
}

.diagnosis-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;

  span,
  h2 {
    margin: 0;
  }

  > div > span {
    color: var(--status-danger);
    font-size: 12px;
    font-weight: 800;
    letter-spacing: 0.06em;
  }

  h2 {
    margin-top: 3px;
    color: var(--text-primary);
    font-size: 18px;
  }
}

.diagnosis-panel__content {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, 1fr) minmax(320px, 0.9fr);
  gap: 18px;
  margin-top: 16px;
}

.failure-focus {
  padding: 14px 16px;
  border-left: 3px solid var(--status-danger);
  border-radius: 0 8px 8px 0;
  background: color-mix(in srgb, var(--status-danger) 7%, var(--fill-color-light));

  p {
    margin: 8px 0 0;
    color: var(--text-secondary);
    line-height: 1.65;
    overflow-wrap: anywhere;
  }
}

.failure-focus__location {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;

  > span {
    color: var(--status-danger);
    font-size: 12px;
    font-weight: 750;
  }

  strong {
    min-width: 0;
    color: var(--text-primary);
    overflow-wrap: anywhere;
  }
}

.repair-list {
  min-width: 0;
  padding: 14px 16px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--fill-color-light);

  h3 {
    margin: 0 0 8px;
    color: var(--text-primary);
    font-size: 14px;
  }

  ol {
    display: grid;
    gap: 6px;
    margin: 0;
    padding-left: 20px;
    color: var(--text-secondary);
    line-height: 1.55;
  }

  li {
    padding-left: 4px;
    overflow-wrap: anywhere;
  }
}

.candidate-blockers {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--border-color);
  color: var(--text-secondary);

  summary {
    width: fit-content;
    color: var(--text-primary);
    font-weight: 700;
    cursor: pointer;
  }

  ul {
    display: grid;
    gap: 5px;
    margin: 12px 0 0;
    padding-left: 20px;
    line-height: 1.5;
  }
}

.run-detail-section {
  min-width: 0;
  scroll-margin-top: 18px;
}

.run-detail-tabs {
  min-width: 0;

  :deep(.el-tabs__content),
  :deep(.el-tab-pane) {
    min-width: 0;
  }
}

.supervisor-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.supervisor-metric {
  padding: 14px;
  border: 1px solid var(--border-color);
  border-radius: 10px;
  background: var(--card-bg);

  span,
  small {
    display: block;
    color: var(--text-secondary);
  }

  strong {
    display: block;
    margin: 7px 0 3px;
    color: var(--text-primary);
    font-size: 20px;
  }
}

.supervisor-event-table {
  margin-bottom: 16px;
}

.card-header,
.span-head,
.span-tags,
.execution-stage {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-header,
.span-head {
  justify-content: space-between;
}

.card-header {
  min-width: 0;
  flex-wrap: wrap;

  :deep(.el-tag) {
    max-width: 100%;
  }

  :deep(.el-tag__content) {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.compare-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}

.diff-chip {
  padding: 12px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--fill-color-light);

  span,
  strong {
    display: block;
  }

  span {
    color: var(--text-secondary);
    font-size: 12px;
  }

  strong {
    margin-top: 6px;
    color: var(--text-primary);
    word-break: break-word;
  }
}

.diff-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding: 10px 0;

  > div {
    border: 1px solid var(--border-color);
    border-radius: 8px;
    overflow: hidden;
    background: var(--card-bg);
  }

  .panel-title {
    padding: 10px 12px;
    border-bottom: 1px solid var(--border-color);
    font-weight: 600;
  }

  pre {
    max-height: 300px;
    overflow: auto;
    margin: 0;
    padding: 12px;
    white-space: pre-wrap;
    word-break: break-word;
  }
}

.hint-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.execution-model {
  display: flex;
  align-items: stretch;
  gap: 10px;
  margin: 6px 0 18px;

  > div {
    display: grid;
    flex: 1;
    gap: 4px;
    padding: 14px;
    border: 1px solid var(--border-color);
    border-radius: 8px;
    background: var(--fill-color-light);
  }

  > span {
    align-self: center;
    color: var(--text-secondary);
    font-size: 20px;
  }

  small {
    color: var(--text-secondary);
  }

  &.workflow-only > div {
    max-width: 320px;
  }
}

.path-prefix {
  color: var(--text-secondary);
  font-size: 18px;
}

.span-timeline {
  padding: 12px 8px;
}

.span-item {
  padding: 14px;
}

.span-head strong,
.span-head span {
  display: block;
}

.span-head span {
  margin-top: 4px;
  color: var(--text-secondary);
}

.error-text {
  margin-top: 10px;
  color: var(--el-color-danger);
}

.io-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 12px;

  pre {
    max-height: 220px;
    overflow: auto;
    padding: 10px;
    border-radius: 6px;
    background: var(--fill-color-light);
    white-space: pre-wrap;
    word-break: break-word;
  }
}

.snapshot-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.snapshot-panel {
  min-height: 280px;
  overflow: hidden;

  .panel-title {
    padding: 12px 14px;
    border-bottom: 1px solid var(--border-color);
    font-weight: 600;
  }

  pre {
    max-height: 480px;
    overflow: auto;
    margin: 0;
    padding: 14px;
    white-space: pre-wrap;
    word-break: break-word;
  }
}

@media (max-width: 1240px) {
  .diagnostic-overview {
    grid-template-columns: 1fr;
  }

  .summary-grid {
    border-top: 1px solid var(--border-color);
  }
}

@media (max-width: 1100px) {
  .summary-grid,
  .supervisor-metrics,
  .compare-summary,
  .diff-detail-grid,
  .snapshot-grid,
  .io-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .execution-model {
    flex-direction: column;

    > span {
      transform: rotate(90deg);
    }
  }

  .diagnosis-panel__content {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .identifier-strip {
    grid-template-columns: 1fr;
  }

  .diagnostic-result,
  .diagnosis-panel {
    padding: 16px;
  }

  .run-vitals {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .summary-grid,
  .supervisor-metrics,
  .compare-summary,
  .diff-detail-grid,
  .snapshot-grid,
  .io-grid {
    grid-template-columns: 1fr;
  }

  .run-vitals {
    grid-template-columns: 1fr;
  }
}

/* RunOps investigation workbench V2 */
.runops-detail {
  align-items: center;
  gap: 8px;
}

.runops-detail :deep(.app-page-header),
.runops-detail > .run-overview-panel,
.runops-detail :deep(.investigation-workbench) {
  width: min(100%, 1840px);
  margin-inline: auto;
}

.runops-detail :deep(.app-page-header__actions) {
  flex-wrap: nowrap;
}

.runops-detail :deep(.app-page-header) {
  border-color: color-mix(in srgb, var(--border-readable) 48%, transparent);
  border-radius: 12px;
  box-shadow:
    0 16px 36px -34px rgb(15 23 42 / 0.46),
    inset 0 1px 0 color-mix(in srgb, var(--text-primary) 7%, transparent);
}

.runops-detail :deep(.app-page-header__title) {
  font-size: clamp(19px, 1.55vw, 23px);
  letter-spacing: -0.015em;
}

.runops-detail :deep(.app-page-header__action-dock) {
  gap: 4px;
  padding: 3px;
  border-radius: 11px;
  box-shadow:
    0 12px 24px -22px rgb(15 23 42 / 0.44),
    inset 0 1px 0 rgb(255 255 255 / 0.9);
}

.runops-detail :deep(.app-page-header__actions .el-button) {
  min-height: 34px;
  border-radius: 8px;
  font-size: 12px;
}

.runops-detail :deep(.app-page-header__actions .el-button.is-circle) {
  width: 34px;
}

.run-overview-panel {
  --run-tone: var(--status-info);
  min-width: 0;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--border-readable) 48%, transparent);
  border-left: 2px solid var(--run-tone);
  border-radius: 9px;
  background: color-mix(in srgb, var(--surface-solid-overlay) 78%, transparent);
  box-shadow:
    0 14px 28px -30px color-mix(in srgb, var(--run-tone) 42%, transparent),
    inset 0 1px 0 color-mix(in srgb, var(--text-primary) 6%, transparent);
  -webkit-backdrop-filter: blur(16px) saturate(1.02);
  backdrop-filter: blur(16px) saturate(1.02);

  &.is-success { --run-tone: var(--status-success); }
  &.is-danger { --run-tone: var(--status-danger); }
  &.is-warning { --run-tone: var(--status-warning); }
  &.is-primary { --run-tone: var(--brand-primary); }
}

.run-conclusion-row {
  display: grid;
  min-width: 0;
  min-height: 54px;
  grid-template-columns: 28px minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
  padding: 6px 12px;
  background: color-mix(in srgb, var(--run-tone) 3%, transparent);
}

.run-conclusion-icon {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 50%;
  background: var(--run-tone);
  color: #fff;
  font-size: 12px;
  font-weight: 800;
}

.run-conclusion-copy {
  min-width: 0;

  p {
    margin: 3px 0 0;
    overflow: hidden;
    color: var(--text-secondary);
    font-size: 11px;
    line-height: 1.4;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.run-conclusion-heading {
  min-width: 0;
  gap: 7px;

  strong {
    overflow: hidden;
    color: var(--text-primary);
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  :deep(.el-tag) {
    flex: 0 0 auto;
  }

  :deep(.el-button) {
    flex: 0 0 auto;
    padding: 0;
  }
}

.run-overview-panel .run-vitals {
  display: grid;
  min-width: 306px;
  grid-template-columns: repeat(3, minmax(90px, 1fr));
  gap: 0;
  margin: 0;
  padding: 4px;
  border: 1px solid color-mix(in srgb, var(--border-readable) 42%, transparent);
  border-radius: 8px;
  background: color-mix(in srgb, var(--surface-solid-control) 66%, transparent);

  > div {
    min-width: 0;
    padding: 0 10px;
    border-left: 1px solid var(--border-divider);
  }

  dt,
  dd {
    margin: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  dt {
    color: var(--text-muted);
    font-size: 9px;
  }

  dd {
    margin-top: 2px;
    color: var(--text-primary);
    font-size: 12px;
    font-weight: 750;
  }
}

.run-context-strip {
  display: flex;
  min-width: 0;
  min-height: 29px;
  align-items: center;
  gap: 12px;
  padding: 3px 10px;
  border-top: 1px solid var(--border-divider);
  color: var(--text-muted);
  font-size: 10px;

  > span {
    min-width: 0;
    white-space: nowrap;
  }

  strong,
  code {
    margin-left: 3px;
    color: var(--text-primary);
    font-size: 10px;
    font-weight: 700;
  }

  .context-entry {
    margin-left: auto;
    padding: 2px 7px;
    border-radius: 6px;
    background: color-mix(in srgb, var(--surface-solid-control) 58%, transparent);
    color: var(--text-secondary);
  }
}

.trace-context {
  display: flex;
  min-width: 0;
  align-items: center;

  code {
    display: block;
    max-width: 240px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  :deep(.el-button) {
    min-height: auto;
    padding: 0 4px;
  }
}

:global(.menu-state) {
  margin-left: 16px;
  color: var(--text-muted);
  font-size: 11px;
}

.context-drawer-section {
  & + & {
    margin-top: 20px;
  }

  h3 {
    margin: 0 0 10px;
    color: var(--text-primary);
    font-size: 14px;
  }

  code {
    color: var(--text-secondary);
    font-size: 11px;
    overflow-wrap: anywhere;
  }
}

.copyable-value {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 8px;

  code {
    min-width: 0;
  }
}

.replay-version-box {
  margin: 12px 0;
  padding: 10px 12px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--fill-color-light);
  color: var(--text-secondary);
  font-size: 12px;

  strong {
    color: var(--text-primary);
  }
}

.replay-advanced {
  overflow: hidden;
  border: 1px solid var(--border-color);
  border-radius: 8px;

  summary {
    padding: 11px 12px;
    color: var(--text-primary);
    cursor: pointer;
    font-size: 13px;
    font-weight: 700;

    small {
      margin-left: 8px;
      color: var(--text-muted);
      font-size: 11px;
      font-weight: 500;
    }
  }

  :deep(.el-form) {
    padding: 14px 14px 2px;
    border-top: 1px solid var(--border-divider);
  }
}

.candidate-blocker-panel {
  margin-top: 14px;
  padding: 14px;
  border: 1px solid var(--border-color);
  border-radius: 9px;
  background: var(--fill-color-light);

  > strong {
    color: var(--text-primary);
    font-size: 13px;
  }

  ol {
    display: grid;
    gap: 7px;
    margin: 10px 0 0;
    padding-left: 20px;
    color: var(--text-secondary);
    font-size: 12px;
    line-height: 1.5;
  }
}

@media (max-width: 1180px) {
  .run-overview-panel .run-vitals {
    min-width: 280px;
  }

  .run-context-strip > span:nth-child(2) {
    display: none;
  }
}

@media (max-width: 900px) {
  .runops-detail :deep(.app-page-header__actions) {
    flex-wrap: wrap;
  }

  .run-conclusion-row {
    grid-template-columns: 30px minmax(0, 1fr);
  }

  .run-overview-panel .run-vitals {
    min-width: 0;
    grid-column: 1 / -1;
    padding-left: 40px;
  }

  .run-context-strip {
    flex-wrap: wrap;
  }

  .run-context-strip .context-entry {
    margin-left: 0;
  }
}

@media (max-width: 640px) {
  .run-conclusion-copy p {
    white-space: normal;
  }

  .run-conclusion-heading {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .run-overview-panel .run-vitals {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    padding-left: 0;

    > div {
      padding: 0 7px;
    }
  }

  .run-context-strip > span:nth-child(1),
  .run-context-strip > span:nth-child(3) {
    display: none;
  }

  .trace-context {
    flex: 1 1 auto;
  }
}
</style>
