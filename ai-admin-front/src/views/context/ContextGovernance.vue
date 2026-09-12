<template>
  <WorkbenchPage class="context-governance">
    <PageHeader
      variant="workbench"
      domain="governance"
      eyebrow="Context Governance"
      title="上下文治理"
      description="维护 PROJECT_DEV 项目上下文；RUNTIME_USER 仅开放映射内候选代审，不展示已采纳私有记忆条目"
    >
      <template #actions>
        <el-tooltip content="刷新上下文治理数据" placement="top">
          <el-button circle :icon="Refresh" :loading="loading" aria-label="刷新上下文治理数据" @click="reloadAll" />
        </el-tooltip>
      </template>
    </PageHeader>

    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="PROJECT_DEV 治理与 RUNTIME_USER 代审授权分离"
      description="上下文条目和组包预览仍固定 PROJECT_DEV；Runtime 映射 tab 只允许在 ACTIVE 授权映射内审核 RUNTIME_USER 候选缓冲区，不开放已采纳私有记忆条目管理。"
    />

    <el-card shadow="never" class="ops-card" v-loading="opsLoading">
      <template #header>
        <div class="ops-header">
          <span>运维摘要</span>
          <div class="ops-actions">
            <el-button size="small" :loading="opsLoading" @click="loadOpsSummary">刷新摘要</el-button>
            <el-button size="small" type="warning" plain :loading="lifecycleLoading" @click="runLifecycleDryRun">
              Lifecycle dryRun
            </el-button>
          </div>
        </div>
      </template>
      <div v-if="opsSummary" class="ops-stats">
        <el-tag>Namespace {{ opsSummary.namespaceCount }}</el-tag>
        <el-tag type="success">ACTIVE {{ opsSummary.activeItemCount }}</el-tag>
        <el-tag type="warning">STALE {{ opsSummary.staleItemCount }}</el-tag>
        <el-tag type="danger">REVOKED {{ opsSummary.revokedItemCount }}</el-tag>
        <el-tag type="info">待 stale {{ opsSummary.staleDueItemCount }}</el-tag>
        <el-tag>候选 PENDING {{ opsSummary.pendingCandidateCount }}</el-tag>
        <el-tag>候选 EXPIRED {{ opsSummary.expiredCandidateCount }}</el-tag>
        <el-tag>24h 审计 {{ opsSummary.auditEventCountRecent }}</el-tag>
      </div>
      <div v-if="lifecycleResult" class="lifecycle-result">
        <span class="lifecycle-label">dryRun 结果：</span>
        <el-tag size="small">过期候选 {{ lifecycleResult.expiredCandidateCount }}</el-tag>
        <el-tag size="small" type="warning">可 stale {{ lifecycleResult.staleItemCount }}</el-tag>
        <el-tag size="small" type="info">跳过 RUNTIME_USER {{ lifecycleResult.skippedRuntimeUserItemCount }}</el-tag>
        <el-tag size="small">扫描 {{ lifecycleResult.scannedItemCount }}</el-tag>
        <span v-if="lifecycleResult.warnings?.length" class="lifecycle-warnings">
          {{ lifecycleResult.warnings.join('；') }}
        </span>
      </div>
      <el-empty v-if="!opsSummary && !opsLoading" description="点击刷新摘要" :image-size="48" />
    </el-card>

    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" class="filter-form">
        <el-form-item label="租户">
          <el-input v-model="filters.tenantId" style="width: 120px" disabled />
        </el-form-item>
        <el-form-item label="项目">
          <span class="project-hint">{{ projectLabel }}</span>
        </el-form-item>
        <el-form-item label="Memory Lane">
          <el-tag type="success">PROJECT_DEV</el-tag>
        </el-form-item>
        <el-form-item label="Namespace">
          <el-select v-model="filters.namespaceId" clearable placeholder="全部" style="width: 180px">
            <el-option
              v-for="ns in namespaces"
              :key="ns.id"
              :label="nsLabel(ns)"
              :value="ns.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="filters.itemType" clearable placeholder="全部" style="width: 140px">
            <el-option v-for="t in itemTypeOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" clearable placeholder="全部" style="width: 120px">
            <el-option v-for="s in statusOptions" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="filters.keyword" clearable placeholder="标题/摘要/内容" style="width: 180px" @keyup.enter="loadItems" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadItems">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-tabs v-model="activeTab" class="main-tabs">
      <el-tab-pane label="上下文条目" name="items">
        <div class="toolbar">
          <el-dropdown @command="openQuickCreate">
            <el-button type="primary">
              快捷创建 <el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="project-bg">项目背景</el-dropdown-item>
                <el-dropdown-item command="page-bg">页面背景</el-dropdown-item>
                <el-dropdown-item command="api-contract">API 契约</el-dropdown-item>
                <el-dropdown-item command="workflow-bg">Workflow 背景</el-dropdown-item>
                <el-dropdown-item command="rule">规则/约束</el-dropdown-item>
                <el-dropdown-item divided command="custom">自定义条目</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>

        <el-table :data="items" v-loading="loading" stripe>
          <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
          <el-table-column prop="itemType" label="类型" width="130" />
          <el-table-column prop="status" label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="visibility" label="可见性" width="90" />
          <el-table-column prop="trustLevel" label="信任" width="90" />
          <el-table-column prop="confidence" label="置信度" width="80" />
          <el-table-column prop="sourceType" label="来源" width="110" />
          <el-table-column prop="updatedAt" label="更新时间" width="170" />
          <el-table-column prop="expiresAt" label="过期" width="170" />
          <el-table-column label="操作" width="260" fixed="right">
            <template #default="{ row }">
              <el-button link size="small" @click="openItemDetail(row)">详情</el-button>
              <el-button link size="small" @click="openItemEdit(row)">编辑</el-button>
              <el-button link size="small" @click="handleVerify(row)">验证</el-button>
              <el-dropdown trigger="click" @command="(cmd: string) => handleItemAction(cmd, row)">
                <el-button link size="small">更多</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="stale">标记 stale</el-dropdown-item>
                    <el-dropdown-item command="revoke">撤销</el-dropdown-item>
                    <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && items.length === 0" description="暂无上下文条目" />
      </el-tab-pane>

      <el-tab-pane label="候选审核" name="candidates">
        <div class="toolbar candidate-toolbar">
          <div class="candidate-filters">
            <el-select v-model="candidateFilters.status" style="width: 140px" @change="loadCandidates">
              <el-option v-for="s in candidateStatusOptions" :key="s" :label="s" :value="s" />
            </el-select>
            <el-input
              v-model="candidateFilters.traceId"
              clearable
              placeholder="submissionId / traceId"
              style="width: 220px"
              @keyup.enter="loadCandidates"
              @clear="loadCandidates"
            />
            <el-checkbox v-model="candidateFilters.includeExpired" @change="loadCandidates">包含过期</el-checkbox>
          </div>
          <div class="candidate-batch-actions">
            <el-button
              type="primary"
              plain
              :disabled="selectedApproveCandidateCount === 0"
              :loading="saving"
              @click="handleApproveCandidateBatch"
            >
              批量采纳 {{ selectedApproveCandidateCount }}
            </el-button>
            <el-button
              type="danger"
              plain
              :disabled="selectedRejectCandidateCount === 0"
              :loading="saving"
              @click="handleRejectCandidateBatch"
            >
              批量忽略
            </el-button>
          </div>
          <el-button :icon="Refresh" :loading="candidateLoading" @click="loadCandidates">刷新候选</el-button>
        </div>
        <el-alert
          type="info"
          show-icon
          :closable="false"
          title="候选来自 AI Coding 工具或平台抽取，采纳前不会写入长期上下文"
          description="采纳后会生成 context_item，并带入来源证据与 PAGE / API / WORKFLOW / PROJECT 绑定。"
          style="margin-bottom: 12px"
        />
        <el-table
          :data="candidates"
          v-loading="candidateLoading"
          stripe
          @selection-change="handleCandidateSelectionChange"
        >
          <el-table-column type="selection" width="44" :selectable="selectablePendingCandidate" />
          <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.title || row.candidateType }}
            </template>
          </el-table-column>
          <el-table-column prop="candidateType" label="候选类型" width="150" />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="candidateStatusTagType(row.status)" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="sourceType" label="来源" width="100" />
          <el-table-column prop="sourceRef" label="来源引用" min-width="180" show-overflow-tooltip />
          <el-table-column label="提交批次" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              {{ candidateSubmissionId(row) }}
            </template>
          </el-table-column>
          <el-table-column prop="confidence" label="置信度" width="90" />
          <el-table-column prop="proposedBy" label="提交者" width="130" show-overflow-tooltip />
          <el-table-column prop="createdAt" label="提交时间" width="170" />
          <el-table-column label="内容" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">
              {{ truncate(row.content, 160) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                size="small"
                :disabled="row.status !== 'PENDING'"
                @click="openCandidateEdit(row)"
              >
                编辑
              </el-button>
              <el-button
                link
                size="small"
                type="primary"
                :disabled="!candidateReviewReady(row)"
                :title="candidateReviewBlocker(row) || ''"
                @click="handleApproveCandidate(row)"
              >
                采纳
              </el-button>
              <el-button
                link
                size="small"
                type="danger"
                :disabled="row.status !== 'PENDING'"
                @click="handleRejectCandidate(row)"
              >
                忽略
              </el-button>
              <el-button
                link
                size="small"
                @click="filterAuditByCandidateSubmission(row)"
              >
                查审计
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!candidateLoading && candidates.length === 0" description="暂无上下文候选" />
      </el-tab-pane>

      <el-tab-pane label="Runtime 映射" name="runtime-mappings">
        <div class="toolbar runtime-mapping-toolbar">
          <div class="runtime-mapping-filters">
            <el-input-number
              v-model="runtimeMappingFilters.platformUserId"
              :min="1"
              controls-position="right"
              placeholder="平台用户 ID"
              style="width: 160px"
              @keyup.enter="loadRuntimeMappings"
            />
            <el-input
              v-model="runtimeMappingFilters.runtimeUserId"
              clearable
              placeholder="runtimeUserId / globalUserId"
              style="width: 240px"
              @keyup.enter="loadRuntimeMappings"
              @clear="loadRuntimeMappings"
            />
            <el-select v-model="runtimeMappingFilters.status" style="width: 120px" @change="loadRuntimeMappings">
              <el-option label="ACTIVE" value="ACTIVE" />
              <el-option label="DELETED" value="DELETED" />
            </el-select>
          </div>
          <div class="runtime-mapping-actions">
            <el-button :icon="Refresh" :loading="runtimeMappingLoading" @click="loadRuntimeMappings">刷新映射</el-button>
            <el-button type="primary" :icon="Plus" @click="openRuntimeMappingCreate">新增映射</el-button>
          </div>
        </div>
        <el-alert
          type="warning"
          show-icon
          :closable="false"
          title="权限边界：context:runtime-user:mapping:manage 维护映射，context:runtime-user:review 执行代审"
          description="平台账号必须同时具备代审权限和 ACTIVE 映射，才可审核指定 Runtime 用户候选；本 tab 不开放 RUNTIME_USER 记忆条目列表。"
          style="margin-bottom: 12px"
        />
        <el-table :data="runtimeMappings" v-loading="runtimeMappingLoading" stripe>
          <el-table-column prop="platformUserId" label="平台用户" width="110" />
          <el-table-column prop="runtimeUserId" label="Runtime 用户" min-width="180" show-overflow-tooltip />
          <el-table-column prop="globalUserId" label="globalUserId" min-width="150" show-overflow-tooltip />
          <el-table-column prop="externalUserId" label="externalUserId" min-width="150" show-overflow-tooltip />
          <el-table-column label="项目范围" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">
              {{ runtimeMappingProjectLabel(row) }}
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdBy" label="创建者" width="100" />
          <el-table-column prop="updatedAt" label="更新时间" width="170" />
          <el-table-column label="操作" width="190" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                size="small"
                type="primary"
                :disabled="row.status !== 'ACTIVE'"
                @click="selectRuntimeMappingForReview(row)"
              >
                代审候选
              </el-button>
              <el-button
                link
                size="small"
                type="danger"
                :disabled="row.status !== 'ACTIVE'"
                @click="handleDeleteRuntimeMapping(row)"
              >
                撤销
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!runtimeMappingLoading && runtimeMappings.length === 0" description="暂无代审映射" />

        <div class="runtime-candidate-panel">
          <div class="runtime-candidate-header">
            <div>
              <h3>RUNTIME_USER 候选代审</h3>
              <p v-if="activeRuntimeMapping" class="runtime-candidate-meta">
                Runtime 用户 {{ activeRuntimeMapping.runtimeUserId }} · {{ runtimeMappingProjectLabel(activeRuntimeMapping) }}
              </p>
              <p v-else class="runtime-candidate-meta">选择一条 ACTIVE 映射后查看候选缓冲区</p>
            </div>
            <div class="runtime-candidate-filters">
              <el-select
                v-model="runtimeCandidateReviewFilters.status"
                style="width: 140px"
                :disabled="!activeRuntimeMapping"
                @change="loadRuntimeCandidateReview"
              >
                <el-option v-for="s in candidateStatusOptions" :key="s" :label="s" :value="s" />
              </el-select>
              <el-input
                v-model="runtimeCandidateReviewFilters.traceId"
                clearable
                placeholder="submissionId / traceId"
                style="width: 220px"
                :disabled="!activeRuntimeMapping"
                @keyup.enter="loadRuntimeCandidateReview"
                @clear="loadRuntimeCandidateReview"
              />
              <el-checkbox
                v-model="runtimeCandidateReviewFilters.includeExpired"
                :disabled="!activeRuntimeMapping"
                @change="loadRuntimeCandidateReview"
              >
                包含过期
              </el-checkbox>
              <el-button
                :icon="Refresh"
                :loading="runtimeCandidateReviewLoading"
                :disabled="!activeRuntimeMapping"
                @click="loadRuntimeCandidateReview"
              >
                刷新候选
              </el-button>
            </div>
          </div>
          <el-alert
            type="info"
            show-icon
            :closable="false"
            title="只审核候选缓冲区，采纳后写入 PRIVATE 用户记忆"
            description="这里不列出已采纳的 RUNTIME_USER context_item；采纳/忽略会沿用后端 context:runtime-user:review 与 ACTIVE 映射校验。"
            style="margin-bottom: 12px"
          />
          <el-table
            v-if="activeRuntimeMapping"
            :data="runtimeReviewCandidates"
            v-loading="runtimeCandidateReviewLoading"
            stripe
          >
            <el-table-column prop="title" label="标题" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.title || row.candidateType }}
              </template>
            </el-table-column>
            <el-table-column prop="candidateType" label="候选类型" width="150" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="candidateStatusTagType(row.status)" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sourceType" label="来源" width="100" />
            <el-table-column prop="traceId" label="提交批次" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                {{ candidateSubmissionId(row) }}
              </template>
            </el-table-column>
            <el-table-column prop="confidence" label="置信度" width="90" />
            <el-table-column prop="createdAt" label="提交时间" width="170" />
            <el-table-column label="内容" min-width="240" show-overflow-tooltip>
              <template #default="{ row }">
                {{ truncate(row.content, 160) }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  size="small"
                  type="primary"
                  :disabled="row.status !== 'PENDING'"
                  @click="handleApproveRuntimeCandidate(row)"
                >
                  采纳
                </el-button>
                <el-button
                  link
                  size="small"
                  type="danger"
                  :disabled="row.status !== 'PENDING'"
                  @click="handleRejectRuntimeCandidate(row)"
                >
                  忽略
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty
            v-if="!activeRuntimeMapping"
            description="请选择一条 ACTIVE 映射后代审候选"
            :image-size="48"
          />
          <el-empty
            v-else-if="!runtimeCandidateReviewLoading && runtimeReviewCandidates.length === 0"
            description="暂无 Runtime 用户候选"
            :image-size="48"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="Namespace" name="namespaces">
        <div class="toolbar">
          <el-button type="primary" :icon="Plus" @click="openNamespaceCreate">创建 Namespace</el-button>
        </div>
        <el-table :data="namespaces" v-loading="namespaceLoading" stripe>
          <el-table-column prop="displayName" label="名称" min-width="140" />
          <el-table-column prop="namespaceType" label="类型" width="110" />
          <el-table-column prop="namespaceKey" label="Key" min-width="200" show-overflow-tooltip />
          <el-table-column prop="projectCode" label="项目" width="120" />
          <el-table-column prop="ownerType" label="Owner" width="100" />
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column prop="updatedAt" label="更新" width="170" />
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button link size="small" @click="viewNamespace(row)">详情</el-button>
              <el-popconfirm title="确认逻辑删除该 Namespace？" @confirm="handleDeleteNamespace(row.id)">
                <template #reference>
                  <el-button link size="small" type="danger">删除</el-button>
                </template>
              </el-popconfirm>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="组包预览" name="package">
        <el-form :inline="true" class="package-form">
          <el-form-item label="检索模式">
            <el-segmented v-model="packageForm.retrievalMode" :options="retrievalModeOptions" />
          </el-form-item>
          <el-form-item label="关键词">
            <el-input v-model="packageForm.keyword" clearable placeholder="可选" style="width: 200px" />
          </el-form-item>
          <el-form-item label="maxItems">
            <el-input-number v-model="packageForm.maxItems" :min="1" :max="50" />
          </el-form-item>
          <el-form-item label="tokenBudget">
            <el-input-number v-model="packageForm.tokenBudget" :min="0" :step="500" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="packageLoading" @click="loadPackagePreview">预览组包</el-button>
          </el-form-item>
        </el-form>
        <el-alert
          v-if="packageResult"
          type="warning"
          :closable="false"
          show-icon
          title="PROJECT_DEV 开发者上下文预览"
          description="不代表业务 Runtime 用户私有记忆注入；userMemory 字段已忽略。"
          style="margin-bottom: 12px"
        />
        <div v-if="packageResult" class="package-summary">
          <el-tag>totalItems: {{ packageResult.totalItems }}</el-tag>
          <el-tag type="info">truncated: {{ packageResult.truncatedCount }}</el-tag>
        </div>
        <el-row v-if="packageResult" :gutter="12">
          <el-col v-for="section in packageSections" :key="section.key" :span="12">
            <el-card shadow="never" class="package-section">
              <template #header>{{ section.label }} ({{ section.items.length }})</template>
              <div v-if="section.items.length === 0" class="empty-hint">无</div>
              <div v-for="hit in section.items" :key="hit.item.id" class="package-hit">
                <div class="hit-title">{{ hit.item.title || hit.item.itemType }}</div>
                <div class="hit-meta">
                  {{ hit.item.itemType }}
                  · score {{ formatScore(hit.rankScore) }}
                  · {{ hit.hitReason || '-' }}
                </div>
                <div v-if="hit.scoreBreakdown" class="hit-breakdown">{{ hit.scoreBreakdown }}</div>
                <div class="hit-content">{{ truncate(hit.item.content, 200) }}</div>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </el-tab-pane>

      <el-tab-pane label="审计" name="audit">
        <el-form :inline="true" class="audit-filter-form">
          <el-form-item label="事件">
            <el-input v-model="auditFilters.eventType" clearable placeholder="SEARCH / MARK_STALE" style="width: 140px" />
          </el-form-item>
          <el-form-item label="Actor">
            <el-input v-model="auditFilters.actorId" clearable placeholder="actorId" style="width: 120px" />
          </el-form-item>
          <el-form-item label="Trace">
            <el-input v-model="auditFilters.traceId" clearable placeholder="traceId" style="width: 140px" />
          </el-form-item>
          <el-form-item label="Time">
            <el-date-picker
              v-model="auditFilters.dateRange"
              type="datetimerange"
              range-separator="-"
              start-placeholder="From"
              end-placeholder="To"
              format="YYYY-MM-DD HH:mm"
              value-format="YYYY-MM-DDTHH:mm:ss"
              clearable
              style="width: 360px"
              @change="loadAudit"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="loadAudit">查询</el-button>
          </el-form-item>
        </el-form>
        <el-table :data="auditEvents" v-loading="auditLoading" stripe>
          <el-table-column prop="eventType" label="事件" width="130" />
          <el-table-column prop="decision" label="决策" width="90" />
          <el-table-column prop="actorType" label="Actor 类型" width="100" />
          <el-table-column prop="actorId" label="Actor" width="120" show-overflow-tooltip />
          <el-table-column prop="itemId" label="Item" width="80" />
          <el-table-column prop="reason" label="原因" min-width="200" show-overflow-tooltip />
          <el-table-column prop="traceId" label="Trace" width="140" show-overflow-tooltip />
          <el-table-column prop="createdAt" label="时间" width="170" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- Candidate edit dialog -->
    <AppDialog v-model="candidateEditDialogOpen" title="编辑候选" width="720px" destroy-on-close>
      <el-form v-if="candidateEditForm" :model="candidateEditForm" label-width="120px">
        <el-form-item label="候选类型" required>
          <el-select v-model="candidateEditForm.candidateType" style="width: 100%">
            <el-option v-for="t in candidateTypeOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="candidateEditForm.title" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="candidateEditForm.content" type="textarea" :rows="7" />
        </el-form-item>
        <el-form-item label="摘要">
          <el-input v-model="candidateEditForm.summary" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="理由">
          <el-input v-model="candidateEditForm.reason" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="来源类型">
          <el-select v-model="candidateEditForm.sourceType" style="width: 100%">
            <el-option v-for="s in candidateSourceTypeOptions" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源引用">
          <el-input v-model="candidateEditForm.sourceRef" />
        </el-form-item>
        <el-form-item label="Workflow ID">
          <el-input v-model="candidateEditForm.workflowId" />
        </el-form-item>
        <el-form-item label="Page ID">
          <el-input v-model="candidateEditForm.pageInstanceId" />
        </el-form-item>
        <el-form-item label="信任等级">
          <el-select v-model="candidateEditForm.trustLevel" style="width: 100%">
            <el-option v-for="t in trustLevelOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="置信度">
          <el-input-number v-model="candidateEditForm.confidence" :min="0" :max="1" :step="0.05" :precision="2" />
        </el-form-item>
        <el-form-item label="metadataJson">
          <el-input v-model="candidateEditForm.metadataJson" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="candidateEditDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCandidateEdit">保存</el-button>
      </template>
    </AppDialog>

    <!-- Runtime user mapping dialog -->
    <AppDialog v-model="runtimeMappingDialogOpen" title="新增 Runtime 代审映射" width="560px" destroy-on-close>
      <el-form v-if="runtimeMappingForm" :model="runtimeMappingForm" label-width="130px">
        <el-form-item label="平台用户 ID" required>
          <el-input-number v-model="runtimeMappingForm.platformUserId" :min="1" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="runtimeUserId">
          <el-input v-model="runtimeMappingForm.runtimeUserId" placeholder="优先使用 globalUserId / externalUserId 归一后的 ownerId" />
        </el-form-item>
        <el-form-item label="globalUserId">
          <el-input v-model="runtimeMappingForm.globalUserId" />
        </el-form-item>
        <el-form-item label="externalUserId">
          <el-input v-model="runtimeMappingForm.externalUserId" />
        </el-form-item>
        <el-form-item label="项目范围">
          <el-switch
            v-model="runtimeMappingUseProjectScope"
            active-text="当前项目"
            inactive-text="租户级"
          />
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="租户级映射可审核该租户下该 runtime 用户候选；项目级映射仅匹配当前项目 scope"
        />
      </el-form>
      <template #footer>
        <el-button @click="runtimeMappingDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRuntimeMapping">创建映射</el-button>
      </template>
    </AppDialog>

    <!-- Item create/edit dialog -->
    <AppDialog v-model="itemDialogOpen" :title="itemDialogTitle" width="680px" destroy-on-close>
      <el-form v-if="itemForm" :model="itemForm" label-width="120px">
        <el-form-item label="Namespace" required>
          <el-select v-model="itemForm.namespaceId" style="width: 100%">
            <el-option v-for="ns in namespaces" :key="ns.id" :label="nsLabel(ns)" :value="ns.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="itemForm.itemType" style="width: 100%">
            <el-option v-for="t in itemTypeOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="itemForm.title" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="itemForm.content" type="textarea" :rows="6" />
        </el-form-item>
        <el-form-item label="摘要">
          <el-input v-model="itemForm.summary" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="可见性">
          <el-select v-model="itemForm.visibility" style="width: 100%">
            <el-option v-for="v in visibilityOptions" :key="v" :label="v" :value="v" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源类型">
          <el-select v-model="itemForm.sourceType" style="width: 100%">
            <el-option v-for="s in sourceTypeOptions" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源引用">
          <el-input v-model="itemForm.sourceRef" />
        </el-form-item>
        <el-form-item label="信任等级">
          <el-select v-model="itemForm.trustLevel" style="width: 100%">
            <el-option v-for="t in trustLevelOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="置信度">
          <el-input-number v-model="itemForm.confidence" :min="0" :max="1" :step="0.05" :precision="2" />
        </el-form-item>
        <el-form-item label="metadataJson">
          <el-input v-model="itemForm.metadataJson" type="textarea" :rows="2" placeholder='{"pageKey":"..."}' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveItem">保存</el-button>
      </template>
    </AppDialog>

    <!-- Namespace create dialog -->
    <AppDialog v-model="namespaceDialogOpen" title="创建 Namespace" width="560px" destroy-on-close>
      <el-form v-if="namespaceForm" :model="namespaceForm" label-width="120px">
        <el-form-item label="类型" required>
          <el-select v-model="namespaceForm.namespaceType" style="width: 100%">
            <el-option v-for="t in namespaceTypeOptions" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="namespaceForm.displayName" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="namespaceForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="Owner 类型">
          <el-input v-model="namespaceForm.ownerType" placeholder="如 PROJECT" />
        </el-form-item>
        <el-form-item label="Owner ID">
          <el-input v-model="namespaceForm.ownerId" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="namespaceDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveNamespace">创建</el-button>
      </template>
    </AppDialog>

    <!-- Item detail drawer -->
    <AppDrawer v-model="detailOpen" :title="detailItem?.title || '上下文详情'" size="640px" destroy-on-close>
      <template v-if="detailItem">
        <el-descriptions :column="1" border size="small" class="detail-desc">
          <el-descriptions-item label="ID">{{ detailItem.id }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ detailItem.itemType }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detailItem.status }}</el-descriptions-item>
          <el-descriptions-item label="可见性">{{ detailItem.visibility }}</el-descriptions-item>
          <el-descriptions-item label="内容">
            <pre class="content-pre">{{ detailItem.content }}</pre>
          </el-descriptions-item>
        </el-descriptions>

        <el-tabs v-model="detailTab" class="detail-tabs">
          <el-tab-pane label="Evidence" name="evidence">
            <div class="detail-toolbar">
              <el-button size="small" type="primary" @click="openEvidenceAdd">添加 Evidence</el-button>
            </div>
            <el-table :data="evidenceList" size="small" stripe>
              <el-table-column prop="evidenceType" label="类型" width="120" />
              <el-table-column prop="evidenceRef" label="引用" min-width="120" show-overflow-tooltip />
              <el-table-column prop="evidenceExcerpt" label="摘录" min-width="160" show-overflow-tooltip />
              <el-table-column prop="traceId" label="Trace" width="120" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="Binding" name="binding">
            <el-table :data="bindingList" size="small" stripe>
              <el-table-column prop="bindType" label="类型" width="100" />
              <el-table-column prop="bindId" label="ID" min-width="140" />
              <el-table-column prop="status" label="状态" width="90" />
            </el-table>
            <el-empty v-if="bindingList.length === 0" description="暂无绑定（本批只读）" />
          </el-tab-pane>
          <el-tab-pane label="Audit" name="audit-detail">
            <el-table :data="itemAuditEvents" size="small" stripe>
              <el-table-column prop="eventType" label="事件" width="110" />
              <el-table-column prop="decision" label="决策" width="80" />
              <el-table-column prop="reason" label="原因" min-width="160" show-overflow-tooltip />
              <el-table-column prop="createdAt" label="时间" width="150" />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </AppDrawer>

    <!-- Evidence add dialog -->
    <AppDialog v-model="evidenceDialogOpen" title="添加 Evidence" width="520px" destroy-on-close>
      <el-form v-if="evidenceForm" :model="evidenceForm" label-width="120px">
        <el-form-item label="类型" required>
          <el-input v-model="evidenceForm.evidenceType" placeholder="MANUAL_NOTE / TRACE / DOC" />
        </el-form-item>
        <el-form-item label="引用">
          <el-input v-model="evidenceForm.evidenceRef" />
        </el-form-item>
        <el-form-item label="摘录">
          <el-input v-model="evidenceForm.evidenceExcerpt" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="Trace ID">
          <el-input v-model="evidenceForm.traceId" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="evidenceDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEvidence">添加</el-button>
      </template>
    </AppDialog>

    <!-- Namespace detail dialog -->
    <AppDialog v-model="namespaceDetailOpen" title="Namespace 详情" width="560px">
      <el-descriptions v-if="namespaceDetail" :column="1" border>
        <el-descriptions-item label="Key">{{ namespaceDetail.namespaceKey }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ namespaceDetail.namespaceType }}</el-descriptions-item>
        <el-descriptions-item label="项目">{{ namespaceDetail.projectCode }}</el-descriptions-item>
        <el-descriptions-item label="Owner">{{ namespaceDetail.ownerType }} / {{ namespaceDetail.ownerId }}</el-descriptions-item>
        <el-descriptions-item label="描述">{{ namespaceDetail.description }}</el-descriptions-item>
      </el-descriptions>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Plus, Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'

import {
  addContextEvidence,
  composeContextPackage,
  createContextItem,
  createContextNamespace,
  createContextRuntimeUserMapping,
  deleteContextItem,
  deleteContextNamespace,
  deleteContextRuntimeUserMapping,
  getContextOpsSummary,
  approveContextMemoryCandidate,
  approveContextMemoryCandidateBatch,
  listContextAudit,
  listContextBindings,
  listContextEvidence,
  listContextItems,
  listContextMemoryCandidates,
  listContextNamespaces,
  listContextRuntimeUserMappings,
  markContextItemStale,
  rejectContextMemoryCandidate,
  rejectContextMemoryCandidateBatch,
  revokeContextItem,
  runContextLifecycleDryRun,
  updateContextMemoryCandidate,
  updateContextItem,
  verifyContextItem,
} from '@/api/context'
import { useProjectStore } from '@/store/project'
import type {
  ContextAuditEvent,
  ContextBinding,
  ContextEvidence,
  ContextItem,
  ContextItemCreateRequest,
  ContextItemType,
  ContextMemoryCandidate,
  ContextMemoryCandidateType,
  ContextMemoryCandidateStatus,
  ContextMemoryCandidateUpdateRequest,
  ContextNamespace,
  ContextNamespaceRequest,
  ContextOpsSummary,
  ContextRetrievalMode,
  ContextLifecycleRunResult,
  ContextPackageResponse,
  ContextScope,
  ContextSourceType,
  ContextRuntimeUserMapping,
  ContextRuntimeUserMappingCreateRequest,
  ContextVisibility,
} from '@/types/context'

const MEMORY_LANE = 'PROJECT_DEV' as const
const DEFAULT_TENANT = 'default'

const route = useRoute()
const projectStore = useProjectStore()

const loading = ref(false)
const namespaceLoading = ref(false)
const auditLoading = ref(false)
const packageLoading = ref(false)
const opsLoading = ref(false)
const lifecycleLoading = ref(false)
const candidateLoading = ref(false)
const runtimeMappingLoading = ref(false)
const runtimeCandidateReviewLoading = ref(false)
const saving = ref(false)

const opsSummary = ref<ContextOpsSummary | null>(null)
const lifecycleResult = ref<ContextLifecycleRunResult | null>(null)

const activeTab = ref(initialTabFromQuery())
const items = ref<ContextItem[]>([])
const candidates = ref<ContextMemoryCandidate[]>([])
const selectedCandidates = ref<ContextMemoryCandidate[]>([])
const runtimeMappings = ref<ContextRuntimeUserMapping[]>([])
const activeRuntimeMapping = ref<ContextRuntimeUserMapping | null>(null)
const runtimeReviewCandidates = ref<ContextMemoryCandidate[]>([])
const namespaces = ref<ContextNamespace[]>([])
const auditEvents = ref<ContextAuditEvent[]>([])
const packageResult = ref<ContextPackageResponse | null>(null)

const filters = reactive({
  tenantId: DEFAULT_TENANT,
  namespaceId: undefined as number | undefined,
  itemType: '',
  status: '',
  keyword: '',
})

const packageForm = reactive({
  retrievalMode: 'HYBRID' as ContextRetrievalMode,
  keyword: '',
  maxItems: 10,
  tokenBudget: 4000,
})

const auditFilters = reactive({
  eventType: '',
  actorId: '',
  traceId: '',
  dateRange: [] as string[] | null,
})

const candidateFilters = reactive({
  status: 'PENDING' as ContextMemoryCandidateStatus,
  traceId: '',
  includeExpired: false,
})

const runtimeMappingFilters = reactive({
  platformUserId: undefined as number | undefined,
  runtimeUserId: '',
  status: 'ACTIVE',
})

const runtimeCandidateReviewFilters = reactive({
  status: 'PENDING' as ContextMemoryCandidateStatus,
  traceId: '',
  includeExpired: false,
})

const itemTypeOptions = [
  'FACT', 'NOTE', 'RULE', 'DECISION', 'PITFALL',
  'PAGE_CONTEXT', 'API_CONTRACT', 'WORKFLOW_CONTEXT', 'TRACE_LEARNING',
]
const statusOptions = ['ACTIVE', 'STALE', 'REVOKED']
const visibilityOptions: ContextVisibility[] = ['PROJECT', 'TENANT', 'GLOBAL', 'PRIVATE']
const trustLevelOptions = ['LOW', 'MEDIUM', 'HIGH', 'VERIFIED']
const sourceTypeOptions = ['MANUAL', 'DOC', 'CODE', 'API', 'WORKFLOW', 'SYSTEM', 'TRACE']
const retrievalModeOptions: ContextRetrievalMode[] = ['HYBRID', 'KEYWORD']
const candidateStatusOptions: ContextMemoryCandidateStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'EXPIRED']
const candidateTypeOptions: ContextMemoryCandidateType[] = [
  'NOTE', 'FACT', 'RULE', 'PAGE_CONTEXT', 'API_CONTEXT', 'WORKFLOW_CONTEXT', 'PREFERENCE',
]
const candidateSourceTypeOptions: ContextSourceType[] = [
  'CODE', 'SQL', 'DOC', 'API', 'TRACE', 'PAGE', 'WORKFLOW', 'SYSTEM', 'MANUAL', 'AGENT_OUTPUT', 'USER_CONFIRMED',
]

const namespaceTypeOptions = [
  { value: 'PROJECT', label: '项目' },
  { value: 'MODULE', label: '模块' },
  { value: 'FEATURE', label: '功能' },
  { value: 'PAGE', label: '页面' },
  { value: 'API', label: '接口' },
  { value: 'WORKFLOW', label: '工作流' },
]

function initialTabFromQuery() {
  if (route.query.tab === 'runtime-mappings') return 'runtime-mappings'
  if (route.query.tab === 'audit') return 'audit'
  return route.query.tab === 'candidates' ? 'candidates' : 'items'
}

function firstQueryValue(value: unknown) {
  return Array.isArray(value) ? value[0] : value
}

function queryProjectId() {
  const raw = firstQueryValue(route.query.projectId)
  const id = Number(raw)
  return Number.isFinite(id) && id > 0 ? id : null
}

function queryProjectCode() {
  const raw = firstQueryValue(route.query.projectCode)
  return typeof raw === 'string' && raw.trim() ? raw.trim() : null
}

function scopeProjectId() {
  return projectStore.currentProjectId ?? queryProjectId()
}

function scopeProjectCode() {
  return projectStore.currentProjectCode || queryProjectCode()
}

const projectLabel = computed(() => {
  if (!projectStore.currentProject) {
    const code = scopeProjectCode()
    const id = scopeProjectId()
    if (code || id) return `项目 ${code || `#${id}`}`
    return '全部项目（建议选择顶部项目）'
  }
  return projectStore.projectLabel(projectStore.currentProject)
})

function buildScope(): ContextScope {
  return {
    tenantId: filters.tenantId,
    projectCode: scopeProjectCode(),
    projectId: scopeProjectId(),
    memoryLane: MEMORY_LANE,
  }
}

function nsLabel(ns: ContextNamespace) {
  return `${ns.displayName || ns.namespaceType} (${ns.namespaceKey})`
}

function statusTagType(status: string) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'STALE') return 'warning'
  if (status === 'REVOKED') return 'danger'
  return 'info'
}

function candidateStatusTagType(status: string) {
  if (status === 'PENDING') return 'warning'
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  return 'info'
}

function truncate(text: string, max: number) {
  if (!text) return ''
  return text.length > max ? `${text.slice(0, max)}…` : text
}

function formatScore(score?: number) {
  if (score == null) return '-'
  return score.toFixed(2)
}

function candidateSubmissionId(row: ContextMemoryCandidate) {
  const metadata = parseCandidateMetadata(row.metadataJson)
  const submission = metadata?.aiCodingSubmission
  if (submission && typeof submission === 'object' && 'submissionId' in submission) {
    const value = (submission as { submissionId?: unknown }).submissionId
    if (typeof value === 'string' && value.trim()) return value.trim()
  }
  return row.traceId || '-'
}

function auditTraceIdForCandidate(row: ContextMemoryCandidate) {
  const submissionId = candidateSubmissionId(row)
  return submissionId === '-' ? '' : submissionId
}

function parseCandidateMetadata(metadataJson?: string | null) {
  if (!metadataJson) return null
  try {
    return JSON.parse(metadataJson) as Record<string, unknown>
  } catch {
    return null
  }
}

const selectedPendingCandidates = computed(() =>
  selectedCandidates.value.filter(candidate => candidate.status === 'PENDING'),
)

const selectedReviewableCandidates = computed(() =>
  selectedPendingCandidates.value.filter(candidateReviewReady),
)

const selectedApproveCandidateCount = computed(() => selectedReviewableCandidates.value.length)
const selectedRejectCandidateCount = computed(() => selectedPendingCandidates.value.length)

function selectablePendingCandidate(row: ContextMemoryCandidate) {
  return row.status === 'PENDING'
}

function candidateText(value?: string | null) {
  return typeof value === 'string' ? value.trim() : ''
}

function candidateReviewBlocker(row: ContextMemoryCandidate) {
  if (row.status !== 'PENDING') return 'Only PENDING candidates can be approved'
  if (row.candidateType === 'WORKFLOW_CONTEXT' && !candidateText(row.workflowId || row.workflowKey)) {
    return 'WORKFLOW_CONTEXT requires workflowId or workflowKey'
  }
  if (row.candidateType === 'PAGE_CONTEXT' && !candidateText(row.pageInstanceId)) {
    return 'PAGE_CONTEXT requires pageInstanceId'
  }
  if (row.candidateType === 'API_CONTEXT' && !candidateText(row.sourceRef)) {
    return 'API_CONTEXT requires sourceRef'
  }
  return ''
}

function candidateReviewReady(row: ContextMemoryCandidate) {
  return !candidateReviewBlocker(row)
}

function handleCandidateSelectionChange(selection: ContextMemoryCandidate[]) {
  selectedCandidates.value = selection.filter(selectablePendingCandidate)
}

function auditDateParam(index: number) {
  const values = auditFilters.dateRange
  return Array.isArray(values) && values[index] ? values[index] : undefined
}

const packageSections = computed(() => {
  if (!packageResult.value) return []
  const r = packageResult.value
  return [
    { key: 'projectMemory', label: 'projectMemory', items: r.projectMemory || [] },
    { key: 'pageContext', label: 'pageContext', items: r.pageContext || [] },
    { key: 'workflowContext', label: 'workflowContext', items: r.workflowContext || [] },
    { key: 'apiContext', label: 'apiContext', items: r.apiContext || [] },
    { key: 'rules', label: 'rules', items: r.rules || [] },
    { key: 'evidenceSummary', label: 'evidenceSummary', items: r.evidenceSummary || [] },
  ]
})

async function loadNamespaces() {
  namespaceLoading.value = true
  try {
    const scope = buildScope()
    const { data } = await listContextNamespaces({
      tenantId: filters.tenantId,
      projectCode: scope.projectCode || undefined,
      projectId: scope.projectId ?? undefined,
    })
    namespaces.value = data ?? []
  } catch {
    namespaces.value = []
  } finally {
    namespaceLoading.value = false
  }
}

async function loadItems() {
  loading.value = true
  try {
    const { data } = await listContextItems({
      ...buildScope(),
      namespaceId: filters.namespaceId,
      itemType: filters.itemType || undefined,
      status: filters.status || undefined,
      keyword: filters.keyword || undefined,
      limit: 200,
    })
    items.value = data ?? []
  } catch {
    items.value = []
  } finally {
    loading.value = false
  }
}

async function loadAudit() {
  auditLoading.value = true
  try {
    const scope = buildScope()
    const { data } = await listContextAudit({
      tenantId: filters.tenantId,
      projectCode: scope.projectCode || undefined,
      projectId: scope.projectId ?? undefined,
      eventType: auditFilters.eventType || undefined,
      actorId: auditFilters.actorId || undefined,
      traceId: auditFilters.traceId || undefined,
      dateFrom: auditDateParam(0),
      dateTo: auditDateParam(1),
      limit: 50,
    })
    auditEvents.value = data ?? []
  } catch {
    auditEvents.value = []
  } finally {
    auditLoading.value = false
  }
}

async function filterAuditByCandidateSubmission(row: ContextMemoryCandidate) {
  const traceId = auditTraceIdForCandidate(row)
  if (!traceId) {
    ElMessage.warning('该候选没有 submissionId / traceId')
    return
  }
  auditFilters.traceId = auditTraceIdForCandidate(row)
  activeTab.value = 'audit'
  await loadAudit()
}

async function loadCandidates() {
  const scope = buildScope()
  selectedCandidates.value = []
  if (!scope.projectCode && !scope.projectId) {
    candidates.value = []
    return
  }
  candidateLoading.value = true
  try {
    const { data } = await listContextMemoryCandidates({
      tenantId: scope.tenantId,
      projectCode: scope.projectCode || undefined,
      projectId: scope.projectId ?? undefined,
      memoryLane: MEMORY_LANE,
      status: candidateFilters.status,
      traceId: candidateFilters.traceId.trim() || undefined,
      includeExpired: candidateFilters.includeExpired,
      limit: 200,
    })
    candidates.value = data ?? []
  } catch {
    candidates.value = []
  } finally {
    candidateLoading.value = false
  }
}

function runtimeMappingProjectLabel(row: ContextRuntimeUserMapping) {
  if (row.projectCode || row.projectId) {
    return row.projectCode || `#${row.projectId}`
  }
  return '租户级'
}

function clearRuntimeCandidateReview() {
  activeRuntimeMapping.value = null
  runtimeReviewCandidates.value = []
}

function buildRuntimeCandidateReviewScope() {
  if (!activeRuntimeMapping.value) return null
  return {
    tenantId: activeRuntimeMapping.value.tenantId || filters.tenantId,
    projectCode: activeRuntimeMapping.value.projectCode || undefined,
    projectId: activeRuntimeMapping.value.projectId ?? undefined,
    memoryLane: 'RUNTIME_USER' as const,
    userId: activeRuntimeMapping.value.runtimeUserId,
  }
}

async function selectRuntimeMappingForReview(row: ContextRuntimeUserMapping) {
  if (row.status !== 'ACTIVE') return
  activeRuntimeMapping.value = row
  await loadRuntimeCandidateReview()
}

async function loadRuntimeCandidateReview() {
  const scope = buildRuntimeCandidateReviewScope()
  runtimeReviewCandidates.value = []
  if (!scope) return
  runtimeCandidateReviewLoading.value = true
  try {
    const { data } = await listContextMemoryCandidates({
      ...scope,
      status: runtimeCandidateReviewFilters.status,
      traceId: runtimeCandidateReviewFilters.traceId.trim() || undefined,
      includeExpired: runtimeCandidateReviewFilters.includeExpired,
      limit: 200,
    })
    runtimeReviewCandidates.value = data ?? []
  } catch {
    runtimeReviewCandidates.value = []
  } finally {
    runtimeCandidateReviewLoading.value = false
  }
}

async function loadRuntimeMappings() {
  runtimeMappingLoading.value = true
  try {
    const scope = buildScope()
    const { data } = await listContextRuntimeUserMappings({
      tenantId: scope.tenantId,
      platformUserId: runtimeMappingFilters.platformUserId || undefined,
      runtimeUserId: runtimeMappingFilters.runtimeUserId.trim() || undefined,
      projectCode: scope.projectCode || undefined,
      projectId: scope.projectId ?? undefined,
      status: runtimeMappingFilters.status || undefined,
      limit: 200,
    })
    runtimeMappings.value = data ?? []
    if (activeRuntimeMapping.value) {
      const refreshedActive = runtimeMappings.value.find(row =>
        row.id === activeRuntimeMapping.value?.id && row.status === 'ACTIVE',
      )
      if (refreshedActive) {
        activeRuntimeMapping.value = refreshedActive
      } else {
        clearRuntimeCandidateReview()
      }
    }
  } catch {
    runtimeMappings.value = []
    clearRuntimeCandidateReview()
  } finally {
    runtimeMappingLoading.value = false
  }
}

async function loadOpsSummary() {
  opsLoading.value = true
  try {
    const scope = buildScope()
    const { data } = await getContextOpsSummary({
      tenantId: scope.tenantId,
      projectCode: scope.projectCode || undefined,
      projectId: scope.projectId ?? undefined,
      memoryLane: MEMORY_LANE,
      includeRuntimeUser: false,
    })
    opsSummary.value = data ?? null
  } catch {
    opsSummary.value = null
  } finally {
    opsLoading.value = false
  }
}

async function runLifecycleDryRun() {
  lifecycleLoading.value = true
  try {
    const scope = buildScope()
    const { data } = await runContextLifecycleDryRun({
      tenantId: scope.tenantId,
      projectCode: scope.projectCode || undefined,
      projectId: scope.projectId ?? undefined,
      dryRun: true,
      includeRuntimeUserItems: false,
    })
    lifecycleResult.value = data ?? null
    ElMessage.success('Lifecycle dryRun 完成（未修改数据）')
  } catch {
    lifecycleResult.value = null
    ElMessage.error('Lifecycle dryRun 失败')
  } finally {
    lifecycleLoading.value = false
  }
}

async function loadPackagePreview() {
  packageLoading.value = true
  try {
    const scope = buildScope()
    const { data } = await composeContextPackage({
      query: {
        tenantId: scope.tenantId,
        projectCode: scope.projectCode || undefined,
        projectId: scope.projectId || undefined,
        memoryLane: MEMORY_LANE,
        retrievalMode: packageForm.retrievalMode,
        query: packageForm.keyword || undefined,
      },
      maxItems: packageForm.maxItems,
      tokenBudget: packageForm.tokenBudget || undefined,
    })
    packageResult.value = data ?? null
  } catch {
    packageResult.value = null
  } finally {
    packageLoading.value = false
  }
}

async function reloadAll() {
  await Promise.all([
    loadNamespaces(),
    loadItems(),
    loadCandidates(),
    loadRuntimeMappings(),
    loadRuntimeCandidateReview(),
    loadAudit(),
    loadOpsSummary(),
  ])
}

async function handleApproveCandidate(row: ContextMemoryCandidate) {
  const blocker = candidateReviewBlocker(row)
  if (blocker) {
    ElMessage.warning(blocker)
    return
  }
  try {
    await ElMessageBox.confirm(`确认采纳候选「${row.title || row.candidateType}」？`, '采纳候选', { type: 'warning' })
  } catch {
    return
  }
  const scope = buildScope()
  await approveContextMemoryCandidate(row.id, {
    ...scope,
    reviewReason: 'approved from context governance',
    confidence: row.confidence ?? undefined,
    trustLevel: row.trustLevel || undefined,
  })
  ElMessage.success('候选已采纳')
  await Promise.all([loadCandidates(), loadItems(), loadNamespaces(), loadOpsSummary()])
}

async function handleRejectCandidate(row: ContextMemoryCandidate) {
  try {
    await ElMessageBox.confirm(`确认忽略候选「${row.title || row.candidateType}」？`, '忽略候选', { type: 'warning' })
  } catch {
    return
  }
  await rejectContextMemoryCandidate(row.id, {
    ...buildScope(),
    reviewReason: 'rejected from context governance',
  })
  ElMessage.success('候选已忽略')
  await Promise.all([loadCandidates(), loadOpsSummary()])
}

async function handleApproveRuntimeCandidate(row: ContextMemoryCandidate) {
  const scope = buildRuntimeCandidateReviewScope()
  if (!scope) return
  try {
    await ElMessageBox.confirm(
      `确认代审采纳 Runtime 候选「${row.title || row.candidateType}」？`,
      'RUNTIME_USER 候选代审',
      { type: 'warning' },
    )
  } catch {
    return
  }
  await approveContextMemoryCandidate(row.id, {
    ...scope,
    reviewReason: 'approved from runtime delegated review',
    confidence: row.confidence ?? undefined,
    trustLevel: row.trustLevel || undefined,
  })
  ElMessage.success('Runtime 候选已采纳')
  await loadRuntimeCandidateReview()
}

async function handleRejectRuntimeCandidate(row: ContextMemoryCandidate) {
  const scope = buildRuntimeCandidateReviewScope()
  if (!scope) return
  try {
    await ElMessageBox.confirm(
      `确认代审忽略 Runtime 候选「${row.title || row.candidateType}」？`,
      'RUNTIME_USER 候选代审',
      { type: 'warning' },
    )
  } catch {
    return
  }
  await rejectContextMemoryCandidate(row.id, {
    ...scope,
    reviewReason: 'rejected from runtime delegated review',
  })
  ElMessage.success('Runtime 候选已忽略')
  await loadRuntimeCandidateReview()
}

async function handleApproveCandidateBatch() {
  const selected = [...selectedReviewableCandidates.value]
  if (selected.length === 0) {
    if (selectedPendingCandidates.value.length > 0) {
      ElMessage.warning('Selected candidates are missing required object targets')
    }
    return
  }

  try {
    await ElMessageBox.confirm(`确认采纳选中的 ${selected.length} 条候选？`, '批量采纳候选', { type: 'warning' })
  } catch {
    return
  }

  const scope = buildScope()
  saving.value = true
  try {
    await approveContextMemoryCandidateBatch({
      ...scope,
      candidateIds: selected.map(row => row.id),
      reviewReason: 'approved batch from context governance',
    })
    ElMessage.success(`已采纳 ${selected.length} 条候选`)
    await Promise.all([loadCandidates(), loadItems(), loadNamespaces(), loadOpsSummary()])
  } finally {
    saving.value = false
  }
}

async function handleRejectCandidateBatch() {
  const selected = [...selectedPendingCandidates.value]
  if (selected.length === 0) return

  try {
    await ElMessageBox.confirm(`确认忽略选中的 ${selected.length} 条候选？`, '批量忽略候选', { type: 'warning' })
  } catch {
    return
  }

  saving.value = true
  try {
    await rejectContextMemoryCandidateBatch({
      ...buildScope(),
      candidateIds: selected.map(row => row.id),
      reviewReason: 'rejected batch from context governance',
    })
    ElMessage.success(`已忽略 ${selected.length} 条候选`)
    await Promise.all([loadCandidates(), loadOpsSummary()])
  } finally {
    saving.value = false
  }
}

const candidateEditDialogOpen = ref(false)
const editingCandidateId = ref<number | null>(null)
const candidateEditForm = ref<Partial<ContextMemoryCandidateUpdateRequest> | null>(null)

function openCandidateEdit(row: ContextMemoryCandidate) {
  if (row.status !== 'PENDING') return
  editingCandidateId.value = row.id
  candidateEditForm.value = {
    ...buildScope(),
    candidateType: row.candidateType,
    title: row.title || '',
    content: row.content,
    summary: row.summary || '',
    reason: row.reason || '',
    sourceType: row.sourceType,
    sourceRef: row.sourceRef || '',
    workflowId: row.workflowId || '',
    workflowKey: row.workflowKey || '',
    pageInstanceId: row.pageInstanceId || '',
    origin: row.origin || '',
    confidence: row.confidence ?? undefined,
    trustLevel: row.trustLevel || undefined,
    visibility: row.visibility || undefined,
    metadataJson: row.metadataJson || '',
    updateReason: 'edited from context governance',
  }
  candidateEditDialogOpen.value = true
}

async function saveCandidateEdit() {
  if (!editingCandidateId.value || !candidateEditForm.value) return
  if (!candidateEditForm.value.content?.trim()) {
    ElMessage.warning('候选内容不能为空')
    return
  }
  saving.value = true
  try {
    await updateContextMemoryCandidate(editingCandidateId.value, {
      ...buildScope(),
      ...candidateEditForm.value,
      content: candidateEditForm.value.content.trim(),
      updateReason: candidateEditForm.value.updateReason || 'edited from context governance',
    } as ContextMemoryCandidateUpdateRequest)
    ElMessage.success('候选已保存')
    candidateEditDialogOpen.value = false
    editingCandidateId.value = null
    candidateEditForm.value = null
    await Promise.all([loadCandidates(), loadOpsSummary()])
  } finally {
    saving.value = false
  }
}

const runtimeMappingDialogOpen = ref(false)
const runtimeMappingUseProjectScope = ref(true)
const runtimeMappingForm = ref<Partial<ContextRuntimeUserMappingCreateRequest> | null>(null)

function openRuntimeMappingCreate() {
  runtimeMappingForm.value = {
    tenantId: filters.tenantId,
    platformUserId: undefined,
    runtimeUserId: '',
    globalUserId: '',
    externalUserId: '',
  }
  runtimeMappingUseProjectScope.value = Boolean(scopeProjectCode() || scopeProjectId())
  runtimeMappingDialogOpen.value = true
}

async function saveRuntimeMapping() {
  if (!runtimeMappingForm.value) return
  const platformUserId = runtimeMappingForm.value.platformUserId
  if (!platformUserId || platformUserId <= 0) {
    ElMessage.warning('请填写平台用户 ID')
    return
  }
  if (
    !runtimeMappingForm.value.runtimeUserId?.trim()
    && !runtimeMappingForm.value.globalUserId?.trim()
    && !runtimeMappingForm.value.externalUserId?.trim()
  ) {
    ElMessage.warning('请填写 runtimeUserId、globalUserId 或 externalUserId')
    return
  }
  const scope = buildScope()
  saving.value = true
  try {
    await createContextRuntimeUserMapping({
      tenantId: scope.tenantId,
      platformUserId,
      runtimeUserId: runtimeMappingForm.value.runtimeUserId?.trim() || undefined,
      globalUserId: runtimeMappingForm.value.globalUserId?.trim() || undefined,
      externalUserId: runtimeMappingForm.value.externalUserId?.trim() || undefined,
      projectCode: runtimeMappingUseProjectScope.value ? scope.projectCode : undefined,
      projectId: runtimeMappingUseProjectScope.value ? scope.projectId : undefined,
    })
    ElMessage.success('代审映射已创建')
    runtimeMappingDialogOpen.value = false
    await loadRuntimeMappings()
  } finally {
    saving.value = false
  }
}

async function handleDeleteRuntimeMapping(row: ContextRuntimeUserMapping) {
  try {
    await ElMessageBox.confirm(
      `确认撤销平台用户 ${row.platformUserId} 对 ${row.runtimeUserId} 的代审映射？`,
      '撤销 Runtime 代审映射',
      { type: 'warning' },
    )
  } catch {
    return
  }
  await deleteContextRuntimeUserMapping(row.id)
  ElMessage.success('代审映射已撤销')
  if (activeRuntimeMapping.value?.id === row.id) {
    clearRuntimeCandidateReview()
  }
  await loadRuntimeMappings()
}

// --- Item CRUD ---
const itemDialogOpen = ref(false)
const itemDialogTitle = ref('创建条目')
const editingItemId = ref<number | null>(null)
const itemForm = ref<Partial<ContextItemCreateRequest> | null>(null)

function defaultItemForm(partial?: Partial<ContextItemCreateRequest>) {
  const scope = buildScope()
  return {
    namespaceId: filters.namespaceId || namespaces.value[0]?.id,
    itemType: 'FACT' as ContextItemType,
    memoryLane: MEMORY_LANE,
    title: '',
    content: '',
    summary: '',
    visibility: 'PROJECT' as ContextVisibility,
    sourceType: 'MANUAL' as ContextSourceType,
    sourceRef: '',
    trustLevel: 'MEDIUM' as const,
    confidence: 0.7,
    metadataJson: '',
    tenantId: scope.tenantId,
    projectCode: scope.projectCode,
    projectId: scope.projectId,
    ...partial,
  }
}

function openQuickCreate(command: string) {
  const presets: Record<string, Partial<ContextItemCreateRequest>> = {
    'project-bg': { itemType: 'FACT', title: '项目背景' },
    'page-bg': { itemType: 'PAGE_CONTEXT', title: '页面背景', metadataJson: '{"pageKey":""}' },
    'api-contract': { itemType: 'API_CONTRACT', title: 'API 契约', sourceType: 'API' as ContextSourceType },
    'workflow-bg': { itemType: 'WORKFLOW_CONTEXT', title: 'Workflow 背景', sourceType: 'WORKFLOW' as ContextSourceType },
    rule: { itemType: 'RULE', title: '规则/约束' },
    custom: {},
  }
  editingItemId.value = null
  itemDialogTitle.value = '创建上下文条目'
  itemForm.value = defaultItemForm(presets[command] || {})
  itemDialogOpen.value = true
}

function openItemEdit(row: ContextItem) {
  const scope = buildScope()
  editingItemId.value = row.id
  itemDialogTitle.value = `编辑条目 #${row.id}`
  itemForm.value = {
    namespaceId: row.namespaceId,
    itemType: row.itemType,
    memoryLane: MEMORY_LANE,
    title: row.title || '',
    content: row.content,
    summary: row.summary || '',
    visibility: row.visibility || 'PROJECT',
    sourceType: row.sourceType,
    sourceRef: row.sourceRef || '',
    trustLevel: row.trustLevel || 'MEDIUM',
    confidence: row.confidence ?? 0.7,
    metadataJson: row.metadataJson || '',
    tenantId: scope.tenantId,
    projectCode: scope.projectCode,
    projectId: scope.projectId,
  }
  itemDialogOpen.value = true
}

async function saveItem() {
  if (!itemForm.value?.content || !itemForm.value.namespaceId) {
    ElMessage.warning('请填写 Namespace 和内容')
    return
  }
  saving.value = true
  try {
    const scope = buildScope()
    if (editingItemId.value) {
      await updateContextItem(editingItemId.value, {
        title: itemForm.value.title,
        content: itemForm.value.content,
        summary: itemForm.value.summary,
        metadataJson: itemForm.value.metadataJson,
        sourceType: itemForm.value.sourceType,
        sourceRef: itemForm.value.sourceRef,
        confidence: itemForm.value.confidence,
        trustLevel: itemForm.value.trustLevel,
        visibility: itemForm.value.visibility,
      }, scope)
      ElMessage.success('已更新')
    } else {
      await createContextItem(itemForm.value as ContextItemCreateRequest)
      ElMessage.success('已创建')
    }
    itemDialogOpen.value = false
    await loadItems()
  } finally {
    saving.value = false
  }
}

async function handleVerify(row: ContextItem) {
  await verifyContextItem(row.id, { ...buildScope(), trustLevel: 'VERIFIED', confidence: 0.95 })
  ElMessage.success('已验证')
  await loadItems()
}

async function handleItemAction(cmd: string, row: ContextItem) {
  const scope = buildScope()
  const labels: Record<string, string> = {
    stale: '标记 stale',
    revoke: '撤销',
    delete: '删除',
  }
  try {
    await ElMessageBox.confirm(`确认${labels[cmd]}条目「${row.title || row.id}」？`, '确认操作', { type: 'warning' })
  } catch {
    return
  }
  if (cmd === 'stale') await markContextItemStale(row.id, scope)
  else if (cmd === 'revoke') await revokeContextItem(row.id, scope)
  else if (cmd === 'delete') await deleteContextItem(row.id, scope)
  ElMessage.success('操作成功')
  await loadItems()
}

// --- Namespace ---
const namespaceDialogOpen = ref(false)
const namespaceForm = ref<ContextNamespaceRequest | null>(null)
const namespaceDetailOpen = ref(false)
const namespaceDetail = ref<ContextNamespace | null>(null)

function openNamespaceCreate() {
  const scope = buildScope()
  namespaceForm.value = {
    namespaceType: 'PROJECT',
    tenantId: scope.tenantId,
    projectCode: scope.projectCode,
    projectId: scope.projectId,
    displayName: '',
    description: '',
    ownerType: 'PROJECT',
    ownerId: scope.projectCode || (scope.projectId ? `pid-${scope.projectId}` : ''),
  }
  namespaceDialogOpen.value = true
}

async function saveNamespace() {
  if (!namespaceForm.value) return
  saving.value = true
  try {
    await createContextNamespace(namespaceForm.value)
    ElMessage.success('Namespace 已创建')
    namespaceDialogOpen.value = false
    await loadNamespaces()
  } finally {
    saving.value = false
  }
}

function viewNamespace(row: ContextNamespace) {
  namespaceDetail.value = row
  namespaceDetailOpen.value = true
}

async function handleDeleteNamespace(id: number) {
  await deleteContextNamespace(id)
  ElMessage.success('已删除')
  await loadNamespaces()
}

// --- Detail drawer ---
const detailOpen = ref(false)
const detailItem = ref<ContextItem | null>(null)
const detailTab = ref('evidence')
const evidenceList = ref<ContextEvidence[]>([])
const bindingList = ref<ContextBinding[]>([])
const itemAuditEvents = ref<ContextAuditEvent[]>([])

async function openItemDetail(row: ContextItem) {
  detailItem.value = row
  detailTab.value = 'evidence'
  detailOpen.value = true
  const scope = buildScope()
  const [ev, bind, audit] = await Promise.all([
    listContextEvidence(row.id, scope).then((r) => r.data ?? []).catch(() => []),
    listContextBindings(row.id, scope).then((r) => r.data ?? []).catch(() => []),
    listContextAudit({ tenantId: filters.tenantId, itemId: row.id, limit: 20 }).then((r) => r.data ?? []).catch(() => []),
  ])
  evidenceList.value = ev
  bindingList.value = bind
  itemAuditEvents.value = audit
}

const evidenceDialogOpen = ref(false)
const evidenceForm = ref({ evidenceType: 'MANUAL_NOTE', evidenceRef: '', evidenceExcerpt: '', traceId: '' })

function openEvidenceAdd() {
  evidenceForm.value = { evidenceType: 'MANUAL_NOTE', evidenceRef: '', evidenceExcerpt: '', traceId: '' }
  evidenceDialogOpen.value = true
}

async function saveEvidence() {
  if (!detailItem.value || !evidenceForm.value.evidenceType) return
  saving.value = true
  try {
    await addContextEvidence(detailItem.value.id, evidenceForm.value, buildScope())
    ElMessage.success('Evidence 已添加')
    evidenceDialogOpen.value = false
    evidenceList.value = (await listContextEvidence(detailItem.value.id, buildScope())).data ?? []
  } finally {
    saving.value = false
  }
}

watch(
  () => projectStore.currentProjectId,
  () => {
    reloadAll()
  },
)

watch(
  () => [route.query.projectId, route.query.projectCode],
  () => {
    reloadAll()
  },
)

watch(activeTab, (tab) => {
  if (tab === 'audit' && auditEvents.value.length === 0) loadAudit()
  if (tab === 'runtime-mappings' && runtimeMappings.value.length === 0) loadRuntimeMappings()
  if (tab === 'runtime-mappings' && activeRuntimeMapping.value && runtimeReviewCandidates.value.length === 0) {
    loadRuntimeCandidateReview()
  }
})

watch(
  () => route.query.tab,
  () => {
    const nextTab = initialTabFromQuery()
    if (activeTab.value !== nextTab) {
      activeTab.value = nextTab
    }
  },
)

onMounted(async () => {
  if (!projectStore.projects.length) {
    await projectStore.fetchProjects()
  }
  await reloadAll()
})
</script>

<style scoped>
.context-governance .page-subtitle {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.ops-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.ops-actions {
  display: flex;
  gap: 8px;
}
.ops-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}
.lifecycle-result {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.lifecycle-label {
  font-weight: 600;
  color: var(--el-text-color-regular);
}
.lifecycle-warnings {
  color: var(--el-color-warning);
}
.hit-breakdown {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-bottom: 4px;
}
.audit-filter-form {
  margin-bottom: 8px;
}
.filter-form {
  margin-bottom: -8px;
}
.project-hint {
  color: var(--el-text-color-regular);
  font-size: 13px;
}
.toolbar {
  margin-bottom: 12px;
}
.runtime-candidate-panel {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.runtime-candidate-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.runtime-candidate-header h3 {
  margin: 0 0 4px;
  font-size: 15px;
  color: var(--el-text-color-primary);
}
.runtime-candidate-meta {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.runtime-candidate-filters {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}
.package-form {
  margin-bottom: 12px;
}
.package-summary {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.package-section {
  margin-bottom: 12px;
  min-height: 120px;
}
.package-hit {
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.package-hit:last-child {
  border-bottom: none;
}
.hit-title {
  font-weight: 600;
  font-size: 13px;
}
.hit-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 2px 0 4px;
}
.hit-content {
  font-size: 12px;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
}
.empty-hint {
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}
.detail-desc {
  margin-bottom: 12px;
}
.content-pre {
  margin: 0;
  white-space: pre-wrap;
  font-family: inherit;
  font-size: 13px;
}
.detail-toolbar {
  margin-bottom: 8px;
}
.detail-tabs {
  margin-top: 12px;
}
</style>
