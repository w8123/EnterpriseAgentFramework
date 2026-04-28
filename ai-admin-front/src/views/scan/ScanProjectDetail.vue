<template>
  <div class="page-container">
    <div class="page-header">
      <h2>扫描详情</h2>
      <div class="header-actions">
        <el-button @click="goBack">返回列表</el-button>
        <el-button type="danger" plain :loading="deleteProjectLoading" @click="handleDeleteProject">
          删除项目
        </el-button>
        <el-button :loading="loading" @click="refreshAll">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
      </div>
    </div>

    <el-collapse v-model="detailPanelActive" class="scan-detail-sections">
      <el-collapse-item class="scan-detail-top-item" name="overview" title="项目概览">
        <div v-if="project" class="project-summary">
          <div><b>项目名称：</b>{{ project.name }}</div>
          <div><b>项目域名：</b>{{ project.baseUrl }}</div>
          <div><b>Context Path：</b>{{ project.contextPath || '-' }}</div>
          <div><b>扫描路径：</b>{{ project.scanPath }}</div>
          <div><b>扫描方式：</b>{{ project.scanType }}</div>
          <div><b>状态：</b><el-tag :type="statusTagType(project.status)">{{ project.status }}</el-tag></div>
          <div><b>接口数：</b>{{ project.toolCount }}</div>
          <div><b>错误信息：</b>{{ project.errorMessage || '-' }}</div>
        </div>
      </el-collapse-item>

      <el-collapse-item v-if="project" class="scan-detail-top-item scan-settings-card" name="scanSettings">
        <template #title>
        <div class="scan-settings-header">
          <span>扫描设置</span>
          <div class="scan-settings-header-actions" @click.stop>
            <el-tooltip
              effect="dark"
              content="将使用最近一次点「保存设置」保存的扫描项配置（含增量与描述来源顺序等）"
              placement="bottom"
            >
              <el-button type="warning" :loading="rescanLoading" @click="handleRescan">重新扫描</el-button>
            </el-tooltip>
            <el-button type="info" :loading="rebuildEmbeddingLoading" @click="handleRebuildEmbeddings">
              重建向量索引
            </el-button>
          </div>
        </div>
        </template>
      <el-alert
        v-if="isOpenApiMode"
        class="scan-settings-mode-alert"
        type="info"
        :closable="false"
        show-icon
        title="当前为 OpenAPI/Auto-OpenAPI 方式：下方「描述来源」「仅 @RestController」「类名正则」等仅对 Controller 代码扫描有效；对 OpenAPI 可生效的项有 HTTP 方法、跳过 deprecated、新接口默认开关、增量等。"
      />
      <p class="scan-settings-hint">先配置并保存，再点「重新扫描」使配置生效。增量重扫不删除已有接口，仅合入新变更文件解析出的端点并更新同名校验。</p>
      <el-form label-width="160px" class="scan-settings-form" @submit.prevent>
        <el-form-item label="接口说明来源（优先级上→下）" :class="{ 'is-disabled-form-item': isOpenApiMode }">
          <div v-if="!isOpenApiMode" class="order-list">
            <div v-for="(k, i) in scanSettingsForm.descriptionSourceOrder" :key="k" class="order-item">
              <span class="order-label">{{ descriptionSourceLabels[k] || k }}</span>
              <el-switch
                :model-value="scanSettingsForm.descriptionSourceEnabled[k] !== false"
                class="order-source-switch"
                size="small"
                :disabled="isOpenApiMode"
                @update:model-value="(v: boolean) => setDescriptionSourceEnabled(k, v)"
                @click.stop
              />
              <el-button-group>
                <el-button size="small" :disabled="i === 0" @click="moveDescriptionOrder(i, -1)">上移</el-button>
                <el-button
                  size="small"
                  :disabled="i === scanSettingsForm.descriptionSourceOrder.length - 1"
                  @click="moveDescriptionOrder(i, 1)"
                >下移</el-button>
              </el-button-group>
            </div>
          </div>
          <span v-else class="el-text is-secondary">OpenAPI 扫描从规范读取 summary/description，无需本项</span>
        </el-form-item>
        <el-form-item label="参数说明来源（优先级上→下）" :class="{ 'is-disabled-form-item': isOpenApiMode }">
          <div v-if="!isOpenApiMode" class="order-list">
            <div
              v-for="(k, i) in scanSettingsForm.paramDescriptionSourceOrder"
              :key="k"
              class="order-item"
            >
              <span class="order-label">{{ paramSourceLabels[k] || k }}</span>
              <el-switch
                :model-value="scanSettingsForm.paramDescriptionSourceEnabled[k] !== false"
                class="order-source-switch"
                size="small"
                :disabled="isOpenApiMode"
                @update:model-value="(v: boolean) => setParamDescriptionSourceEnabled(k, v)"
                @click.stop
              />
              <el-button-group>
                <el-button size="small" :disabled="i === 0" @click="moveParamOrder(i, -1)">上移</el-button>
                <el-button
                  size="small"
                  :disabled="i === scanSettingsForm.paramDescriptionSourceOrder.length - 1"
                  @click="moveParamOrder(i, 1)"
                >下移</el-button>
              </el-button-group>
            </div>
          </div>
          <span v-else class="el-text is-secondary">此扫描方式不解析 Controller 形参与 DTO，无需配置</span>
        </el-form-item>
        <el-form-item label="仅 @RestController" :class="{ 'is-disabled-form-item': isOpenApiMode }">
          <el-switch
            v-model="scanSettingsForm.onlyRestController"
            :disabled="isOpenApiMode"
          />
        </el-form-item>
        <el-form-item label="HTTP 方法白名单">
          <el-select
            v-model="scanSettingsForm.httpMethodWhitelist"
            multiple
            clearable
            filterable
            class="http-method-select"
            placeholder="留空=全部；OpenAPI/Controller 均会过滤"
          >
            <el-option v-for="m in allHttpMethods" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="类名包含正则" :class="{ 'is-disabled-form-item': isOpenApiMode }">
          <el-input
            v-model="scanSettingsForm.classIncludeRegex"
            clearable
            :disabled="isOpenApiMode"
            placeholder="例如 .*\.controller\..*（留空=不限制）"
          />
        </el-form-item>
        <el-form-item label="类名排除正则" :class="{ 'is-disabled-form-item': isOpenApiMode }">
          <el-input
            v-model="scanSettingsForm.classExcludeRegex"
            clearable
            :disabled="isOpenApiMode"
            placeholder="留空=不排除"
          />
        </el-form-item>
        <el-form-item label="跳过 deprecated 接口">
          <el-switch v-model="scanSettingsForm.skipDeprecated" />
          <span class="el-text is-secondary" style="margin-left: 8px">Controller：@Deprecated/注释；OpenAPI：operation.deprecated</span>
        </el-form-item>
        <el-form-item label="新发现接口默认">
          <div class="switch-group">
            <el-switch v-model="scanSettingsForm.defaultFlags.enabled" />
            <span>启用</span>
            <el-switch v-model="scanSettingsForm.defaultFlags.agentVisible" />
            <span>Agent 可见</span>
            <el-switch v-model="scanSettingsForm.defaultFlags.lightweightEnabled" />
            <span>轻量调用</span>
          </div>
        </el-form-item>
        <el-form-item label="增量扫描">
          <el-radio-group v-model="scanSettingsForm.incrementalMode" class="incr-radio">
            <el-radio-button label="OFF">关闭</el-radio-button>
            <el-radio-button label="MTIME">仅变更文件 (mtime)</el-radio-button>
            <el-radio-button label="GIT_DIFF">Git 差异</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="上次成功扫描" v-if="lastScannedDisplay">
          <span class="el-text is-secondary">{{ lastScannedDisplay }}</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="scanSettingsSaving" @click="handleSaveScanSettings">保存设置</el-button>
        </el-form-item>
      </el-form>
    </el-collapse-item>

    <el-collapse-item v-if="project" class="scan-detail-top-item" name="auth" title="鉴权设置">
      <p class="auth-hint">用于「测试」扫描到的 HTTP 接口，以及已注册为全局 Tool 且仍关联本项目的动态调用。</p>
      <el-form label-width="160px" class="auth-form" @submit.prevent>
        <el-form-item label="鉴权方式">
          <el-select v-model="authForm.authType" style="width: 260px" placeholder="请选择">
            <el-option label="无需鉴权" value="none" />
            <el-option label="API Key" value="api_key" />
          </el-select>
        </el-form-item>
        <template v-if="authForm.authType === 'api_key'">
          <el-form-item label="Key 位置">
            <el-select v-model="authForm.authApiKeyIn" style="width: 260px" placeholder="请选择">
              <el-option label="HTTP Header" value="header" />
              <el-option label="URL 查询参数 (params)" value="query" />
            </el-select>
          </el-form-item>
          <el-form-item label="参数名 (Key)">
            <el-input v-model="authForm.authApiKeyName" clearable placeholder="例如 X-API-Key、api_key" />
          </el-form-item>
          <el-form-item label="参数值 (Value)">
            <el-input
              v-model="authForm.authApiKeyValue"
              type="password"
              show-password
              clearable
              placeholder="密钥或 Token"
            />
          </el-form-item>
        </template>
        <el-form-item>
          <el-button type="primary" :loading="authSaving" @click="saveAuthSettings">保存鉴权设置</el-button>
        </el-form-item>
      </el-form>
    </el-collapse-item>

    <el-collapse-item
      v-if="project"
      class="scan-detail-top-item ai-settings-card"
      name="aiGen"
      title="AI 理解生成设置"
    >
      <p class="ai-settings-hint">以下模型选择与「一键生成 / 强制重生成」对项目级摘要、模块列表及下方接口列表中的单条「重新生成」均生效。</p>
      <div class="ai-toolbar">
        <el-button type="primary" :loading="batchStarting" @click="startBatchGenerate(false)">
          一键生成 AI 理解
        </el-button>
        <el-button :loading="batchStarting" @click="startBatchGenerate(true)">强制重生成（覆盖已编辑）</el-button>
        <el-button @click="reloadSemanticUi">刷新</el-button>
        <el-select
          v-model="semanticProvider"
          placeholder="Provider"
          clearable
          filterable
          class="semantic-llm-select"
          @change="onSemanticProviderChange"
        >
          <el-option v-for="p in semanticProviders" :key="p.name" :label="p.name" :value="p.name" />
        </el-select>
        <el-select
          v-model="semanticModel"
          placeholder="模型"
          clearable
          filterable
          class="semantic-llm-select semantic-model-select"
          :disabled="!semanticProvider"
        >
          <el-option v-for="m in semanticModelsForSelect" :key="m" :label="m" :value="m" />
        </el-select>
        <el-tag v-if="task" :type="taskTagType(task.stage)" style="margin-left: 12px">
          {{ task.stage }} · {{ task.completedSteps }}/{{ task.totalSteps }}
        </el-tag>
        <span v-if="task" class="token-sum">累计 token：{{ task.totalTokens }}</span>
      </div>
      <el-progress
        v-if="task && (task.stage === 'QUEUED' || task.stage === 'RUNNING')"
        :percentage="taskPercent"
        :text-inside="true"
        :stroke-width="18"
        class="task-progress"
      />
      <el-alert
        v-if="task && task.stage === 'FAILED'"
        type="error"
        :title="`批量生成失败：${task.errorMessage || '未知错误'}`"
        :closable="false"
        show-icon
      />
    </el-collapse-item>

    <el-collapse-item v-if="project" class="scan-detail-top-item semantic-inline-collapse" name="projectDoc">
        <template #title>
          <div class="ai-card-header">
            <span>项目级摘要</span>
            <div @click.stop>
              <el-button size="small" :loading="projectGenLoading" @click="regenerateProject">重新生成</el-button>
              <el-button size="small" :disabled="!projectDoc" @click="openEditDoc(projectDoc)">编辑</el-button>
            </div>
          </div>
        </template>
        <div v-if="projectDoc" class="markdown-body" v-html="renderMd(projectDoc.contentMd)" />
        <el-empty v-else description="项目级文档尚未生成" />
    </el-collapse-item>

    <el-collapse-item v-if="project" class="scan-detail-top-item semantic-inline-collapse" name="modules">
        <template #title>
          <div class="ai-card-header">
            <span>模块列表（{{ modules.length }}）</span>
            <div @click.stop>
              <el-button size="small" :disabled="selectedModuleIds.length < 2" @click="openMergeDialog">
                合并选中（{{ selectedModuleIds.length }}）
              </el-button>
            </div>
          </div>
        </template>
        <el-table :data="modules" stripe @selection-change="onModuleSelectionChange">
          <el-table-column type="selection" width="48" />
          <el-table-column prop="displayName" label="展示名" min-width="200" />
          <el-table-column prop="name" label="原始类名" min-width="220" />
          <el-table-column label="聚合类" min-width="260">
            <template #default="{ row }">
              <el-tag v-for="c in row.sourceClasses" :key="c" size="small" class="source-class-tag">{{ c }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="AI 文档" width="160">
            <template #default="{ row }">
              <el-tag v-if="moduleDocMap[row.id]" :type="moduleDocMap[row.id].status === 'edited' ? 'success' : 'info'" size="small">
                {{ moduleDocMap[row.id].status }}
              </el-tag>
              <el-tag v-else size="small" type="warning">未生成</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="260">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="regenerateModule(row)">重新生成</el-button>
              <el-button link size="small" :disabled="!moduleDocMap[row.id]" @click="openEditDoc(moduleDocMap[row.id])">编辑</el-button>
              <el-button link size="small" @click="openRenameDialog(row)">重命名</el-button>
            </template>
          </el-table-column>
        </el-table>
    </el-collapse-item>

    <el-collapse-item v-if="project" class="scan-detail-top-item merged-tools-card" name="tools">
      <template #title>
        <div class="tools-header">
          <span>扫描接口与 AI 语义</span>
          <div class="tools-actions" @click.stop>
            <el-button size="small" @click="batchToggle(false)">全部禁用</el-button>
            <el-button size="small" type="primary" @click="batchToggle(true)">全部启用</el-button>
          </div>
        </div>
      </template>

      <div v-loading="loading" class="tools-table-wrap">
        <el-empty v-if="!loading && tools.length === 0" description="这个项目还没有扫描出任何接口" />
        <el-collapse
          v-else-if="tools.length > 0"
          v-model="interfaceCollapseActive"
          class="tool-groups-collapse"
        >
          <el-collapse-item v-for="g in toolModuleGroups" :key="g.key" :name="g.key">
            <template #title>
              <div class="module-collapse-title">
                <div class="module-collapse-title__text">
                  <span class="collapse-module-title">{{ g.label }}</span>
                  <el-tag size="small" type="info" class="module-tool-count">{{ g.tools.length }} 个接口</el-tag>
                </div>
                <el-button
                  v-if="g.tools.length > 0 && g.tools.some((t) => !t.globalToolDefinitionId)"
                  type="primary"
                  size="small"
                  :loading="batchModulePromoteLoading[g.key] ?? false"
                  @click.stop="handlePromoteModuleToGlobal(g)"
                >
                  添加为 Tool
                </el-button>
              </div>
            </template>
            <el-table :data="g.tools" stripe class="nested-tools-table merged-interface-table">
              <el-table-column type="expand" width="44">
                <template #default="{ row }">
                  <div class="expand-content expand-merged">
                    <h4>参数定义</h4>
                    <el-table
                      :data="parameterRows(row.parameters)"
                      size="small"
                      border
                      row-key="_key"
                      :tree-props="{ children: 'children' }"
                      default-expand-all
                    >
                      <el-table-column prop="name" label="参数名" min-width="200" />
                      <el-table-column prop="type" label="类型" width="160" />
                      <el-table-column prop="location" label="位置" width="100">
                        <template #default="{ row: param }">
                          {{ param.location || '-' }}
                        </template>
                      </el-table-column>
                      <el-table-column prop="description" label="描述" />
                      <el-table-column prop="required" label="必填" width="80" align="center">
                        <template #default="{ row: param }">
                          <el-tag :type="param.required ? 'danger' : 'info'" size="small">
                            {{ param.required ? '是' : '否' }}
                          </el-tag>
                        </template>
                      </el-table-column>
                    </el-table>
                    <div class="tool-meta">
                      <div><b>HTTP：</b>{{ row.httpMethod || '-' }} {{ row.contextPath || '' }}{{ row.endpointPath || '' }}</div>
                      <div><b>Base URL：</b>{{ row.baseUrl || '-' }}</div>
                      <div><b>来源定位：</b>{{ row.sourceLocation || '-' }}</div>
                      <div><b>请求体类型：</b>{{ row.requestBodyType || '-' }}</div>
                      <div><b>响应类型：</b>{{ row.responseType || '-' }}</div>
                    </div>
                    <h4 class="expand-ai-heading">AI 语义文档</h4>
                    <div v-if="toolDocMap[row.scanToolId]" class="markdown-body expand-md" v-html="renderMd(toolDocMap[row.scanToolId].contentMd)" />
                    <el-empty v-else description="该接口还没有 AI 描述" :image-size="60" />
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="name" label="工具名" min-width="180" />
              <el-table-column label="端点" min-width="200">
                <template #default="{ row }">
                  <span>{{ row.httpMethod || '-' }} {{ row.contextPath || '' }}{{ row.endpointPath || '' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
              <el-table-column label="参数数" width="78" align="center">
                <template #default="{ row }">
                  {{ (row.parameters || []).length }}
                </template>
              </el-table-column>
              <el-table-column label="AI 描述" min-width="200">
                <template #default="{ row }">
                  <div class="ai-desc-ellipsis">
                    {{ toolDocSummary(row) }}
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="AI 状态" width="96" align="center">
                <template #default="{ row }">
                  <el-tag v-if="toolDocMap[row.scanToolId]" :type="toolDocMap[row.scanToolId].status === 'edited' ? 'success' : 'info'" size="small">
                    {{ toolDocMap[row.scanToolId].status }}
                  </el-tag>
                  <el-tag v-else size="small" type="warning">未生成</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="启用" width="78" align="center">
                <template #default="{ row }">
                  <el-switch :model-value="row.enabled" @change="handleEnabledChange(row, $event as boolean)" />
                </template>
              </el-table-column>
              <el-table-column label="Agent 可见" width="96" align="center">
                <template #default="{ row }">
                  <el-switch :model-value="row.agentVisible" @change="handleFlagChange(row, 'agentVisible', $event as boolean)" />
                </template>
              </el-table-column>
              <el-table-column label="轻量调用" width="96" align="center">
                <template #default="{ row }">
                  <el-switch :model-value="row.lightweightEnabled" @change="handleFlagChange(row, 'lightweightEnabled', $event as boolean)" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="520" fixed="right">
                <template #default="{ row }">
                  <div class="merged-ops-wrap">
                    <el-button link type="primary" size="small" @click="openEditDialog(row)">编辑</el-button>
                    <el-button link type="primary" size="small" @click="openTest(row)">测试</el-button>
                    <el-button
                      v-if="!row.globalToolDefinitionId"
                      link
                      type="success"
                      size="small"
                      :loading="promoteLoading[row.scanToolId]"
                      @click="handlePromoteToGlobal(row)"
                    >
                      添加为 Tool
                    </el-button>
                    <el-button
                      v-if="row.globalToolDefinitionId && row.globalToolOutOfSync"
                      link
                      type="warning"
                      size="small"
                      :loading="pushToGlobalLoading[row.scanToolId]"
                      @click="handlePushToGlobalTool(row)"
                    >
                      更新到Tool
                    </el-button>
                    <el-button
                      v-if="row.globalToolDefinitionId"
                      link
                      type="danger"
                      size="small"
                      :loading="unpromoteLoading[row.scanToolId]"
                      @click="handleUnpromoteFromGlobal(row)"
                    >
                      从Tool中下架
                    </el-button>
                    <span class="merged-ops-sep" aria-hidden="true" />
                    <el-button link size="small" type="primary" @click="regenerateTool(row)">重新生成 AI</el-button>
                    <el-button link size="small" :disabled="!toolDocMap[row.scanToolId]" @click="openEditDoc(toolDocMap[row.scanToolId])">编辑 AI 文档</el-button>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-collapse-item>
    </el-collapse>

    <el-dialog v-model="docEditVisible" title="编辑 AI 文档（保存后标记为 edited，不会被重新生成覆盖，除非强制）" width="720px">
      <el-input v-model="docEditContent" type="textarea" :rows="18" placeholder="Markdown 内容" />
      <template #footer>
        <el-button @click="docEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="docEditSaving" @click="submitDocEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="mergeDialogVisible" title="合并模块" width="520px">
      <el-form label-width="100px">
        <el-form-item label="合并目标">
          <el-select v-model="mergeTargetId" style="width: 100%">
            <el-option
              v-for="m in mergeSelectedModules"
              :key="m.id"
              :value="m.id"
              :label="`${m.displayName}（${m.name}）`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="合并后名称">
          <el-input v-model="mergeDisplayName" placeholder="可选，留空则沿用目标模块名" />
        </el-form-item>
        <el-form-item label="被合并">
          <div>
            <el-tag
              v-for="m in mergeSourceModules"
              :key="m.id"
              class="source-class-tag"
            >{{ m.displayName }}</el-tag>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mergeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="mergeSaving" @click="submitMerge">确认合并</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="renameDialogVisible" title="重命名模块" width="420px">
      <el-form label-width="100px">
        <el-form-item label="展示名">
          <el-input v-model="renameValue" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renameDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="renameSaving" @click="submitRename">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="formDialogVisible" :title="form.name ? `编辑 Tool - ${form.name}` : '编辑 Tool'" width="760px">
      <el-form label-width="120px">
        <el-form-item label="工具名">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="来源">
              <el-input :model-value="form.source" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="HTTP 方法">
              <el-select v-model="form.httpMethod" style="width: 100%">
                <el-option v-for="method in httpMethods" :key="method" :label="method" :value="method" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="请求体类型">
              <el-input v-model="form.requestBodyType" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Base URL">
              <el-input v-model="form.baseUrl" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Context Path">
              <el-input v-model="form.contextPath" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Endpoint Path">
              <el-input v-model="form.endpointPath" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="响应类型">
              <el-input v-model="form.responseType" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="来源定位">
          <el-input v-model="form.sourceLocation" />
        </el-form-item>
        <el-form-item label="参数定义">
          <div class="parameter-editor">
            <el-table :data="form.parameters" size="small" border>
              <el-table-column label="参数名" min-width="120">
                <template #default="{ row }">
                  <el-input v-model="row.name" />
                </template>
              </el-table-column>
              <el-table-column label="类型" width="120">
                <template #default="{ row }">
                  <el-input v-model="row.type" />
                </template>
              </el-table-column>
              <el-table-column label="位置" width="120">
                <template #default="{ row }">
                  <el-select v-model="row.location" style="width: 100%">
                    <el-option v-for="location in parameterLocations" :key="location" :label="location" :value="location" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="描述" min-width="180">
                <template #default="{ row }">
                  <el-input v-model="row.description" />
                </template>
              </el-table-column>
              <el-table-column label="必填" width="80" align="center">
                <template #default="{ row }">
                  <el-switch v-model="row.required" />
                </template>
              </el-table-column>
              <el-table-column width="80" align="center">
                <template #default="{ $index }">
                  <el-button link type="danger" @click="removeParameter($index)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button class="add-parameter-button" @click="addParameter">+ 添加参数</el-button>
          </div>
        </el-form-item>
        <el-form-item label="运行控制">
          <div class="switch-group">
            <el-switch v-model="form.enabled" />
            <span>启用</span>
            <el-switch v-model="form.agentVisible" />
            <span>Agent 可见</span>
            <el-switch v-model="form.lightweightEnabled" />
            <span>轻量调用可见</span>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="formDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="testDialogVisible" :title="`测试工具 - ${testingTool?.name}`" width="600px">
      <el-form v-if="testingTool" label-width="120px">
        <el-form-item v-for="param in testingTool.parameters" :key="param.name" :label="param.name" :required="param.required">
          <el-input v-model="testArgs[param.name]" :placeholder="param.description || param.type" />
          <div class="param-hint">{{ param.description }} ({{ param.type }})</div>
        </el-form-item>
      </el-form>

      <div v-if="testResult" class="test-result-area">
        <el-divider content-position="left">执行结果</el-divider>
        <el-alert
          :type="testResult.success ? 'success' : 'error'"
          :title="testResult.success ? '执行成功' : '执行失败'"
          :description="testResult.errorMessage || ''"
          :closable="false"
          show-icon
        />
        <pre v-if="testResult.result" class="result-content">{{ testResult.result }}</pre>
        <p class="result-duration">耗时：{{ testResult.durationMs }}ms</p>
      </div>

      <template #footer>
        <el-button @click="testDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="testRunning" @click="handleTest">执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { marked } from 'marked'
import type { ProviderInfo } from '@/types/model'
import type { DescriptionSource, ParamDescriptionSource, ProjectToolInfo, ScanProject, ScanSettings } from '@/types/scanProject'
import { getDefaultScanSettings } from '@/types/scanProject'
import type { ToolParameter, ToolTestResult, ToolUpsertRequest } from '@/types/tool'
import type { ScanModule, SemanticDoc, SemanticTask } from '@/types/semanticDoc'
import {
  deleteScanProject,
  getScanProjectDetail,
  getScanProjectOperationBlockers,
  getScanProjectTools,
  promoteScanModuleToolsToGlobal,
  promoteScanProjectToolToGlobal,
  pushScanProjectToolToGlobalTool,
  unpromoteScanProjectToolFromGlobal,
  testScanProjectTool,
  toggleScanProjectTool,
  triggerRescan,
  updateScanProjectAuthSettings,
  updateScanProjectScanSettings,
  updateScanProjectTool,
} from '@/api/scanProject'
import {
  formatScanProjectBlockersMessage,
  parseScanProjectBlockersFromError,
} from '@/utils/scanProjectBlockers'
import { startToolRetrievalRebuild } from '@/api/toolRetrieval'
import {
  editSemanticDoc,
  generateModuleDoc,
  generateProjectDoc,
  generateScanToolDoc,
  getProjectBatchStatus,
  listProjectSemanticDocs,
  listScanModules,
  mergeScanModules,
  renameScanModule,
  startProjectBatchGenerate,
  type SemanticLlmParams,
} from '@/api/semanticDoc'
import { getProviders } from '@/api/model'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => Number(route.params.id))

const project = ref<ScanProject | null>(null)
const tools = ref<ProjectToolInfo[]>([])
const loading = ref(false)
const saving = ref(false)
const rescanLoading = ref(false)
const deleteProjectLoading = ref(false)
const rebuildEmbeddingLoading = ref(false)
/** 扫描详情各区块折叠；空数组=全部折叠 */
const detailPanelActive = ref<string[]>([])

const formDialogVisible = ref(false)
const editingScanToolId = ref<number | null>(null)
const promoteLoading = reactive<Record<number, boolean>>({})
const pushToGlobalLoading = reactive<Record<number, boolean>>({})
const unpromoteLoading = reactive<Record<number, boolean>>({})
/** 扫描结果按模块「批量添加为 Tool」 loading，key 同 toolModuleGroups */
const batchModulePromoteLoading = reactive<Record<string, boolean>>({})
const form = reactive<ToolUpsertRequest>(createEmptyForm())
const httpMethods = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']
const parameterLocations = ['QUERY', 'PATH', 'BODY']

const testDialogVisible = ref(false)
const testingTool = ref<ProjectToolInfo | null>(null)
const testArgs = reactive<Record<string, string>>({})
const testResult = ref<ToolTestResult | null>(null)
const testRunning = ref(false)

const authSaving = ref(false)
const authForm = reactive({
  authType: 'none' as 'none' | 'api_key',
  authApiKeyIn: 'header' as 'header' | 'query',
  authApiKeyName: '',
  authApiKeyValue: '',
})

const scanSettingsForm = reactive<ScanSettings>(getDefaultScanSettings())
const scanSettingsSaving = ref(false)

const descriptionSourceLabels: Record<DescriptionSource, string> = {
  JAVADOC: 'Javadoc',
  SWAGGER_API_OPERATION: 'Swagger @ApiOperation',
  OPENAPI_OPERATION: 'OpenAPI @Operation',
  METHOD_NAME: '方法名兜底',
}

const paramSourceLabels: Record<ParamDescriptionSource, string> = {
  JAVADOC_PARAM: 'Javadoc @param',
  SCHEMA_ANNO: '@Schema / 模型',
  PARAMETER_ANNO: '@Parameter 等',
  FIELD_NAME: '形参/字段名',
}

const allHttpMethods = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'] as const

const isOpenApiMode = computed(() => project.value?.scanType === 'openapi')

const lastScannedDisplay = computed(() => {
  const t = project.value?.lastScannedAt
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return t
  return d.toLocaleString()
})

function syncScanSettingsFormFromProject() {
  const p = project.value
  if (!p) return
  const s = p.scanSettings
  const b = getDefaultScanSettings()
  if (!s) {
    Object.assign(scanSettingsForm, b)
    return
  }
  const df = s.defaultFlags ?? b.defaultFlags
  const dOrder = s.descriptionSourceOrder?.length
    ? ([...s.descriptionSourceOrder] as DescriptionSource[])
    : ([...b.descriptionSourceOrder] as DescriptionSource[])
  const pOrder = s.paramDescriptionSourceOrder?.length
    ? ([...s.paramDescriptionSourceOrder] as ParamDescriptionSource[])
    : ([...b.paramDescriptionSourceOrder] as ParamDescriptionSource[])
  Object.assign(scanSettingsForm, {
    descriptionSourceOrder: dOrder,
    paramDescriptionSourceOrder: pOrder,
    descriptionSourceEnabled: buildSourceEnabledMap(dOrder, s.descriptionSourceEnabled),
    paramDescriptionSourceEnabled: buildParamSourceEnabledMap(
      pOrder,
      s.paramDescriptionSourceEnabled
    ),
    onlyRestController: s.onlyRestController ?? b.onlyRestController,
    httpMethodWhitelist: s.httpMethodWhitelist != null ? [...s.httpMethodWhitelist] : [],
    classIncludeRegex: s.classIncludeRegex ?? '',
    classExcludeRegex: s.classExcludeRegex ?? '',
    skipDeprecated: s.skipDeprecated ?? false,
    defaultFlags: { ...b.defaultFlags, ...df },
    incrementalMode: s.incrementalMode ?? b.incrementalMode,
  } as ScanSettings)
}

function buildSourceEnabledMap(
  order: DescriptionSource[],
  fromApi: ScanSettings['descriptionSourceEnabled'] | undefined
): ScanSettings['descriptionSourceEnabled'] {
  const o: ScanSettings['descriptionSourceEnabled'] = {}
  for (const k of order) {
    o[k] = fromApi?.[k] !== false
  }
  return o
}

function buildParamSourceEnabledMap(
  order: ParamDescriptionSource[],
  fromApi: ScanSettings['paramDescriptionSourceEnabled'] | undefined
): ScanSettings['paramDescriptionSourceEnabled'] {
  const o: ScanSettings['paramDescriptionSourceEnabled'] = {}
  for (const k of order) {
    o[k] = fromApi?.[k] !== false
  }
  return o
}

function setDescriptionSourceEnabled(k: DescriptionSource, v: boolean) {
  scanSettingsForm.descriptionSourceEnabled[k] = v
}

function setParamDescriptionSourceEnabled(k: ParamDescriptionSource, v: boolean) {
  scanSettingsForm.paramDescriptionSourceEnabled[k] = v
}

function syncAuthFormFromProject() {
  const p = project.value
  if (!p) return
  authForm.authType = p.authType === 'api_key' ? 'api_key' : 'none'
  authForm.authApiKeyIn = p.authApiKeyIn === 'query' ? 'query' : 'header'
  authForm.authApiKeyName = p.authApiKeyName ?? ''
  authForm.authApiKeyValue = p.authApiKeyValue ?? ''
}

function moveDescriptionOrder(i: number, d: number) {
  const arr = scanSettingsForm.descriptionSourceOrder
  const j = i + d
  if (j < 0 || j >= arr.length) return
  const tmp = arr[i]
  arr[i] = arr[j]
  arr[j] = tmp
}

function moveParamOrder(i: number, d: number) {
  const arr = scanSettingsForm.paramDescriptionSourceOrder
  const j = i + d
  if (j < 0 || j >= arr.length) return
  const tmp = arr[i]
  arr[i] = arr[j]
  arr[j] = tmp
}

async function handleSaveScanSettings() {
  const payload: ScanSettings = {
    descriptionSourceOrder: [...scanSettingsForm.descriptionSourceOrder],
    paramDescriptionSourceOrder: [...scanSettingsForm.paramDescriptionSourceOrder],
    descriptionSourceEnabled: { ...scanSettingsForm.descriptionSourceEnabled },
    paramDescriptionSourceEnabled: { ...scanSettingsForm.paramDescriptionSourceEnabled },
    onlyRestController: scanSettingsForm.onlyRestController,
    httpMethodWhitelist: [...scanSettingsForm.httpMethodWhitelist],
    classIncludeRegex: scanSettingsForm.classIncludeRegex?.trim() ?? '',
    classExcludeRegex: scanSettingsForm.classExcludeRegex?.trim() ?? '',
    skipDeprecated: scanSettingsForm.skipDeprecated,
    defaultFlags: { ...scanSettingsForm.defaultFlags },
    incrementalMode: scanSettingsForm.incrementalMode,
  }
  scanSettingsSaving.value = true
  try {
    const { data } = await updateScanProjectScanSettings(projectId.value, payload)
    if (data) {
      project.value = data
      syncScanSettingsFormFromProject()
    }
    ElMessage.success('扫描设置已保存')
  } catch (e) {
    ElMessage.error((e as Error).message || '保存失败')
  } finally {
    scanSettingsSaving.value = false
  }
}

async function saveAuthSettings() {
  if (authForm.authType === 'api_key') {
    if (!authForm.authApiKeyName.trim()) {
      ElMessage.warning('请填写参数名 (Key)')
      return
    }
    if (!authForm.authApiKeyValue.trim()) {
      ElMessage.warning('请填写参数值 (Value)')
      return
    }
  }
  authSaving.value = true
  try {
    await updateScanProjectAuthSettings(projectId.value, {
      authType: authForm.authType,
      authApiKeyIn: authForm.authType === 'api_key' ? authForm.authApiKeyIn : null,
      authApiKeyName: authForm.authType === 'api_key' ? authForm.authApiKeyName.trim() : null,
      authApiKeyValue: authForm.authType === 'api_key' ? authForm.authApiKeyValue : null,
    })
    ElMessage.success('鉴权设置已保存')
    await refreshAll()
  } catch (e) {
    ElMessage.error((e as Error).message || '保存失败')
  } finally {
    authSaving.value = false
  }
}

/** 扫描结果 / 接口语义 按模块分组（与下方 toolModuleGroups 一致） */
interface ToolModuleGroup {
  key: string
  moduleId: number | null
  label: string
  tools: ProjectToolInfo[]
}

function createEmptyForm(): ToolUpsertRequest {
  return {
    name: '',
    description: '',
    parameters: [],
    source: 'scanner',
    sourceLocation: '',
    httpMethod: 'GET',
    baseUrl: '',
    contextPath: '',
    endpointPath: '',
    requestBodyType: '',
    responseType: '',
    projectId: null,
    enabled: false,
    agentVisible: false,
    lightweightEnabled: false,
  }
}

function cloneParameters(parameters: ToolParameter[] = []): ToolParameter[] {
  return parameters.map((parameter) => ({ ...parameter }))
}

/** 参数表用行：给每个节点分配唯一 _key（按 location:path 累积），children 递归保留，供 el-table 树形展示。 */
interface ParameterRow extends ToolParameter {
  _key: string
  children?: ParameterRow[]
}

function parameterRows(parameters: ToolParameter[] | null | undefined, prefix = ''): ParameterRow[] {
  if (!parameters || parameters.length === 0) return []
  return parameters.map((parameter, index) => {
    const keyBase = `${parameter.location || 'ROOT'}:${parameter.name || `#${index}`}`
    const key = prefix ? `${prefix}>${keyBase}` : keyBase
    const { children, ...rest } = parameter
    const nested = children && children.length > 0 ? parameterRows(children, key) : undefined
    const row: ParameterRow = { ...rest, _key: key }
    if (nested) row.children = nested
    return row
  })
}

function toUpsertRequest(tool: ProjectToolInfo): ToolUpsertRequest {
  return {
    name: tool.name,
    description: tool.description,
    parameters: cloneParameters(tool.parameters),
    source: tool.source,
    sourceLocation: tool.sourceLocation || '',
    httpMethod: tool.httpMethod || 'GET',
    baseUrl: tool.baseUrl || '',
    contextPath: tool.contextPath ?? '',
    endpointPath: tool.endpointPath || '',
    requestBodyType: tool.requestBodyType || '',
    responseType: tool.responseType || '',
    projectId: tool.projectId ?? null,
    enabled: tool.enabled,
    agentVisible: tool.agentVisible,
    lightweightEnabled: tool.lightweightEnabled,
  }
}

function applyForm(data: ToolUpsertRequest) {
  form.name = data.name
  form.description = data.description
  form.parameters = cloneParameters(data.parameters)
  form.source = data.source
  form.sourceLocation = data.sourceLocation || ''
  form.httpMethod = data.httpMethod || 'GET'
  form.baseUrl = data.baseUrl || ''
  form.contextPath = data.contextPath ?? ''
  form.endpointPath = data.endpointPath || ''
  form.requestBodyType = data.requestBodyType || ''
  form.responseType = data.responseType || ''
  form.projectId = data.projectId ?? null
  form.enabled = data.enabled
  form.agentVisible = data.agentVisible
  form.lightweightEnabled = data.lightweightEnabled
}

function statusTagType(status: ScanProject['status']) {
  if (status === 'scanned') return 'success'
  if (status === 'failed') return 'danger'
  if (status === 'scanning') return 'warning'
  return 'info'
}

function applySemanticDocsFromList(docs: SemanticDoc[]) {
  projectDoc.value = docs.find((d) => d.level === 'project') || null
  const mMap: Record<number, SemanticDoc> = {}
  docs.filter((d) => d.level === 'module' && d.moduleId != null).forEach((d) => {
    mMap[d.moduleId as number] = d
  })
  moduleDocMap.value = mMap
  const tMap: Record<number, SemanticDoc> = {}
  for (const d of docs.filter((x) => x.level === 'scan_tool')) {
    if (d.toolId != null) tMap[d.toolId] = d
  }
  toolDocMap.value = tMap
}

async function refreshAll() {
  loading.value = true
  try {
    const [projectResponse, toolResponse, moduleResponse] = await Promise.all([
      getScanProjectDetail(projectId.value),
      getScanProjectTools(projectId.value),
      listScanModules(projectId.value),
    ])
    project.value = projectResponse.data
    syncAuthFormFromProject()
    syncScanSettingsFormFromProject()
    tools.value = Array.isArray(toolResponse.data) ? toolResponse.data : []
    modules.value = Array.isArray(moduleResponse.data) ? moduleResponse.data : []
    try {
      const { data } = await listProjectSemanticDocs(projectId.value)
      applySemanticDocsFromList(Array.isArray(data) ? data : [])
    } catch {
      applySemanticDocsFromList([])
    }
  } catch {
    project.value = null
    tools.value = []
    modules.value = []
    applySemanticDocsFromList([])
    ElMessage.error('加载扫描详情失败')
  } finally {
    loading.value = false
  }
}

async function handleRebuildEmbeddings() {
  rebuildEmbeddingLoading.value = true
  try {
    const { data } = await startToolRetrievalRebuild()
    ElMessage.success(`已提交向量索引重建任务 (${data.taskId.slice(0, 8)})，可在「Tool 检索测试」页查看进度`)
  } catch (error) {
    ElMessage.error((error as Error).message || '重建向量索引失败')
  } finally {
    rebuildEmbeddingLoading.value = false
  }
}

async function ensureScanOperationAllowed(): Promise<boolean> {
  try {
    const { data } = await getScanProjectOperationBlockers(projectId.value)
    if (!data.blocked) {
      return true
    }
    await ElMessageBox.alert(formatScanProjectBlockersMessage(data), '操作被阻止', {
      type: 'warning',
      confirmButtonText: '知道了',
    })
    return false
  } catch {
    ElMessage.error('检查引用关系失败')
    return false
  }
}

async function handleRescan() {
  rescanLoading.value = true
  try {
    if (!(await ensureScanOperationAllowed())) {
      return
    }
    const { data } = await triggerRescan(projectId.value)
    ElMessage.success(`重新扫描完成，发现 ${data.toolCount} 个接口`)
    await refreshAll()
  } catch (error) {
    const blockers = parseScanProjectBlockersFromError(error)
    if (blockers?.blocked) {
      await ElMessageBox.alert(formatScanProjectBlockersMessage(blockers), '操作被阻止', {
        type: 'warning',
        confirmButtonText: '知道了',
      })
      return
    }
    ElMessage.error((error as Error).message || '重新扫描失败')
    await refreshAll()
  } finally {
    rescanLoading.value = false
  }
}

async function handleDeleteProject() {
  if (!project.value) {
    return
  }
  if (!(await ensureScanOperationAllowed())) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认删除扫描项目「${project.value.name}」吗？将删除关联扫描行、挂到本项目的全局 Tool/Skill 及模块与语义数据。`,
      '删除确认',
      { type: 'warning' },
    )
  } catch {
    return
  }
  deleteProjectLoading.value = true
  try {
    await deleteScanProject(projectId.value)
    ElMessage.success('已删除')
    await router.push('/scan-project')
  } catch (error) {
    const blockers = parseScanProjectBlockersFromError(error)
    if (blockers?.blocked) {
      await ElMessageBox.alert(formatScanProjectBlockersMessage(blockers), '无法删除', {
        type: 'warning',
        confirmButtonText: '知道了',
      })
      return
    }
    ElMessage.error((error as Error).message || '删除失败')
  } finally {
    deleteProjectLoading.value = false
  }
}

function openEditDialog(tool: ProjectToolInfo) {
  editingScanToolId.value = tool.scanToolId
  applyForm(toUpsertRequest(tool))
  formDialogVisible.value = true
}

function addParameter() {
  form.parameters.push({
    name: '',
    type: 'string',
    description: '',
    required: false,
    location: 'QUERY',
  })
}

function removeParameter(index: number) {
  form.parameters.splice(index, 1)
}

async function handleSave() {
  if (editingScanToolId.value == null || !form.name.trim() || !form.description.trim()) {
    ElMessage.warning('请填写工具名和描述')
    return
  }
  saving.value = true
  try {
    await updateScanProjectTool(projectId.value, editingScanToolId.value, {
      ...form,
      parameters: cloneParameters(form.parameters),
    })
    ElMessage.success('扫描接口已更新')
    formDialogVisible.value = false
    await refreshAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleEnabledChange(tool: ProjectToolInfo, enabled: boolean) {
  try {
    await toggleScanProjectTool(projectId.value, tool.scanToolId, enabled)
    ElMessage.success(`已${enabled ? '启用' : '禁用'} ${tool.name}`)
    await refreshAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '状态更新失败')
  }
}

async function handleFlagChange(tool: ProjectToolInfo, field: 'agentVisible' | 'lightweightEnabled', value: boolean) {
  try {
    const payload = toUpsertRequest({
      ...tool,
      [field]: value,
    })
    await updateScanProjectTool(projectId.value, tool.scanToolId, payload)
    ElMessage.success('配置已更新')
    await refreshAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '配置更新失败')
  }
}

async function batchToggle(enabled: boolean) {
  try {
    await Promise.all(
      tools.value.map((tool) => toggleScanProjectTool(projectId.value, tool.scanToolId, enabled)),
    )
    ElMessage.success(enabled ? '已批量启用' : '已批量禁用')
    await refreshAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '批量操作失败')
  }
}

async function handlePromoteToGlobal(tool: ProjectToolInfo) {
  promoteLoading[tool.scanToolId] = true
  try {
    const { data } = await promoteScanProjectToolToGlobal(projectId.value, tool.scanToolId)
    ElMessage.success(`已添加到 Tool 管理，全局名称：${data.globalToolName}`)
    await refreshAll()
    await reloadAiTab()
  } catch (error) {
    ElMessage.error((error as Error).message || '添加失败')
  } finally {
    promoteLoading[tool.scanToolId] = false
  }
}

async function handlePushToGlobalTool(tool: ProjectToolInfo) {
  pushToGlobalLoading[tool.scanToolId] = true
  try {
    await pushScanProjectToolToGlobalTool(projectId.value, tool.scanToolId)
    ElMessage.success('已更新到 Tool 管理中的对应工具')
    await refreshAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '更新失败')
  } finally {
    pushToGlobalLoading[tool.scanToolId] = false
  }
}

async function handleUnpromoteFromGlobal(tool: ProjectToolInfo) {
  try {
    await ElMessageBox.confirm(
      '将删除 Tool 管理中的该工具，并解除与本扫描接口的关联。若需对外暴露，可再次点「添加为 Tool」。',
      '从Tool中下架',
      { type: 'warning', confirmButtonText: '确定下架', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  unpromoteLoading[tool.scanToolId] = true
  try {
    await unpromoteScanProjectToolFromGlobal(projectId.value, tool.scanToolId)
    ElMessage.success('已从 Tool 中下架')
    await refreshAll()
    await reloadAiTab()
  } catch (error) {
    ElMessage.error((error as Error).message || '下架失败')
  } finally {
    unpromoteLoading[tool.scanToolId] = false
  }
}

async function handlePromoteModuleToGlobal(g: ToolModuleGroup) {
  if (g.tools.length === 0) return
  batchModulePromoteLoading[g.key] = true
  try {
    const { data } = await promoteScanModuleToolsToGlobal(projectId.value, g.moduleId)
    if (data.promotedCount === 0) {
      ElMessage.info('本模块下没有可添加的接口')
      return
    }
    ElMessage.success(`已添加 ${data.promotedCount} 个接口到 Tool 管理`)
    await refreshAll()
    await reloadAiTab()
  } catch (error) {
    ElMessage.error((error as Error).message || '批量添加失败')
  } finally {
    batchModulePromoteLoading[g.key] = false
  }
}

function openTest(tool: ProjectToolInfo) {
  testingTool.value = tool
  testResult.value = null
  Object.keys(testArgs).forEach((key) => delete testArgs[key])
  for (const parameter of tool.parameters || []) {
    testArgs[parameter.name] = ''
  }
  testDialogVisible.value = true
}

async function handleTest() {
  if (!testingTool.value) return
  testRunning.value = true
  testResult.value = null
  try {
    const args: Record<string, unknown> = {}
    for (const [key, value] of Object.entries(testArgs)) {
      if (value !== '') {
        args[key] = value
      }
    }
    const { data } = await testScanProjectTool(projectId.value, testingTool.value.scanToolId, args)
    testResult.value = data as unknown as ToolTestResult
  } catch (error) {
    testResult.value = {
      success: false,
      result: '',
      errorMessage: (error as Error).message || '执行失败',
      durationMs: 0,
    }
  } finally {
    testRunning.value = false
  }
}

function goBack() {
  router.push('/scan-project')
}

// ==================== 语义文档与「扫描接口 + AI」合并列表 ====================

const modules = ref<ScanModule[]>([])
const projectDoc = ref<SemanticDoc | null>(null)
const moduleDocMap = ref<Record<number, SemanticDoc>>({})
const toolDocMap = ref<Record<number, SemanticDoc>>({})
const selectedModuleIds = ref<number[]>([])

/** 扫描接口与 AI 语义合并列表：按模块折叠，与原先两个 TAB 共用同一组 key */
const interfaceCollapseActive = ref<string[]>([])

const toolModuleGroups = computed<ToolModuleGroup[]>(() => {
  const moduleById = new Map<number, ScanModule>()
  for (const m of modules.value) {
    moduleById.set(m.id, m)
  }
  const buckets = new Map<string, ProjectToolInfo[]>()
  const order: string[] = []

  for (const t of tools.value) {
    const mid = t.moduleId ?? null
    const key = mid != null ? `m-${mid}` : 'm-none'
    if (!buckets.has(key)) {
      buckets.set(key, [])
      order.push(key)
    }
    buckets.get(key)!.push(t)
  }

  const groups: ToolModuleGroup[] = order.map((key) => {
    const list = buckets.get(key)!
    const mid = key === 'm-none' ? null : Number(key.slice(2))
    const mod = mid != null ? moduleById.get(mid) : undefined
    const fromTool = list[0]?.moduleDisplayName?.trim()
    const label =
      mid == null
        ? '未关联模块'
        : (mod?.displayName?.trim() || mod?.name || fromTool || `模块 #${mid}`)
    return { key, moduleId: mid, label, tools: list }
  })

  return groups.sort((a, b) => {
    if (a.moduleId == null && b.moduleId != null) return 1
    if (a.moduleId != null && b.moduleId == null) return -1
    return a.label.localeCompare(b.label, 'zh-CN')
  })
})

const batchStarting = ref(false)
const task = ref<SemanticTask | null>(null)
const projectGenLoading = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

const semanticProviders = ref<ProviderInfo[]>([])
const semanticProvider = ref('')
const semanticModel = ref('')
const semanticModelsForSelect = computed(() => {
  const p = semanticProviders.value.find((x) => x.name === semanticProvider.value)
  return p?.models ?? []
})

const docEditVisible = ref(false)
const docEditContent = ref('')
const docEditingId = ref<number | null>(null)
const docEditSaving = ref(false)

const mergeDialogVisible = ref(false)
const mergeSelectedModules = ref<ScanModule[]>([])
const mergeSourceModules = ref<ScanModule[]>([])
const mergeTargetId = ref<number | null>(null)
const mergeDisplayName = ref('')
const mergeSaving = ref(false)

const renameDialogVisible = ref(false)
const renameTarget = ref<ScanModule | null>(null)
const renameValue = ref('')
const renameSaving = ref(false)

const taskPercent = computed(() => {
  if (!task.value || task.value.totalSteps <= 0) return 0
  return Math.min(100, Math.round((task.value.completedSteps / task.value.totalSteps) * 100))
})

function renderMd(content: string | null | undefined): string {
  if (!content) return ''
  return marked.parse(content, { async: false }) as string
}

function toolDocSummary(tool: ProjectToolInfo): string {
  const doc = toolDocMap.value[tool.scanToolId]
  if (!doc || !doc.contentMd) return tool.description || '（无 AI 描述）'
  const marker = '## 一句话语义'
  const idx = doc.contentMd.indexOf(marker)
  if (idx < 0) return doc.contentMd.slice(0, 120)
  const rest = doc.contentMd.slice(idx + marker.length).trim()
  const next = rest.indexOf('\n##')
  const section = next > 0 ? rest.slice(0, next) : rest
  return section.trim().slice(0, 140)
}

function taskTagType(stage: SemanticTask['stage']) {
  switch (stage) {
    case 'DONE': return 'success'
    case 'FAILED': return 'danger'
    case 'RUNNING': return 'warning'
    default: return 'info'
  }
}

function syncSemanticModelToProvider() {
  const models = semanticModelsForSelect.value
  if (models.length === 0) {
    semanticModel.value = ''
    return
  }
  if (!semanticModel.value || !models.includes(semanticModel.value)) {
    semanticModel.value = models[0]
  }
}

function onSemanticProviderChange() {
  if (!semanticProvider.value) {
    semanticModel.value = ''
    return
  }
  syncSemanticModelToProvider()
}

/** 传给语义生成接口；未选 Provider/模型时由后端走默认网关配置 */
function semanticLlmParams(): SemanticLlmParams | undefined {
  const p = semanticProvider.value?.trim()
  const m = semanticModel.value?.trim()
  if (!p && !m) return undefined
  return {
    ...(p ? { provider: p } : {}),
    ...(m ? { model: m } : {}),
  }
}

async function loadSemanticProviders() {
  try {
    const { data } = await getProviders()
    const list = (data?.data ?? (Array.isArray(data) ? data : [])) as ProviderInfo[]
    semanticProviders.value = list
    if (list.length === 0) {
      semanticProvider.value = ''
      semanticModel.value = ''
      return
    }
    if (!semanticProvider.value || !list.some((x) => x.name === semanticProvider.value)) {
      semanticProvider.value = list[0].name
    }
    syncSemanticModelToProvider()
  } catch {
    semanticProviders.value = []
  }
}

async function reloadAiTab() {
  try {
    const [moduleResp, docResp] = await Promise.all([
      listScanModules(projectId.value),
      listProjectSemanticDocs(projectId.value),
    ])
    modules.value = Array.isArray(moduleResp.data) ? moduleResp.data : []
    applySemanticDocsFromList(Array.isArray(docResp.data) ? docResp.data : [])
  } catch (error) {
    ElMessage.error((error as Error).message || '加载 AI 理解数据失败')
  }
}

/** 工具栏「刷新」：同步模块与语义文档（项目级摘要、模块列表、AI TAB 共用） */
async function reloadSemanticUi() {
  await reloadAiTab()
}

async function resumeBatchTaskIfAny() {
  try {
    const { data } = await getProjectBatchStatus(projectId.value)
    task.value = data ?? null
    if (data && (data.stage === 'RUNNING' || data.stage === 'QUEUED')) {
      startPollingTask(data.taskId)
    }
  } catch {
    task.value = null
  }
}

async function startBatchGenerate(force: boolean) {
  batchStarting.value = true
  try {
    const { data } = await startProjectBatchGenerate(projectId.value, force, semanticLlmParams())
    ElMessage.success('已提交批量生成任务')
    startPollingTask(data.taskId)
  } catch (error) {
    ElMessage.error((error as Error).message || '启动批量生成失败')
  } finally {
    batchStarting.value = false
  }
}

function startPollingTask(taskId: string) {
  stopPollingTask()
  const poll = async () => {
    try {
      const { data } = await getProjectBatchStatus(projectId.value, taskId)
      if (data == null) {
        task.value = null
        stopPollingTask()
        return
      }
      task.value = data
      if (data.stage === 'DONE' || data.stage === 'FAILED') {
        stopPollingTask()
        await reloadAiTab()
        await refreshAll()
      }
    } catch {
      stopPollingTask()
    }
  }
  poll()
  pollTimer = setInterval(poll, 2500)
}

function stopPollingTask() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function regenerateProject() {
  projectGenLoading.value = true
  try {
    const { data } = await generateProjectDoc(projectId.value, true, semanticLlmParams())
    projectDoc.value = data
    ElMessage.success('项目级 AI 摘要已更新')
  } catch (error) {
    ElMessage.error((error as Error).message || '生成失败')
  } finally {
    projectGenLoading.value = false
  }
}

async function regenerateModule(row: ScanModule) {
  try {
    const { data } = await generateModuleDoc(row.id, true, semanticLlmParams())
    moduleDocMap.value = { ...moduleDocMap.value, [row.id]: data }
    ElMessage.success(`已更新模块 ${row.displayName}`)
  } catch (error) {
    ElMessage.error((error as Error).message || '生成失败')
  }
}

async function regenerateTool(row: ProjectToolInfo) {
  try {
    const { data } = await generateScanToolDoc(projectId.value, row.scanToolId, true, semanticLlmParams())
    toolDocMap.value = { ...toolDocMap.value, [row.scanToolId]: data }
    ElMessage.success(`已更新接口 ${row.name}`)
    await refreshAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '生成失败')
  }
}

function openEditDoc(doc: SemanticDoc | null | undefined) {
  if (!doc) return
  docEditingId.value = doc.id
  docEditContent.value = doc.contentMd || ''
  docEditVisible.value = true
}

async function submitDocEdit() {
  if (!docEditingId.value) return
  docEditSaving.value = true
  try {
    const { data } = await editSemanticDoc(docEditingId.value, { contentMd: docEditContent.value })
    if (data.level === 'project') projectDoc.value = data
    else if (data.level === 'module' && data.moduleId != null)
      moduleDocMap.value = { ...moduleDocMap.value, [data.moduleId]: data }
    else if (data.level === 'scan_tool' && data.toolId != null) {
      toolDocMap.value = { ...toolDocMap.value, [data.toolId]: data }
    } else if (data.level === 'tool' && data.toolId != null) {
      const ent = Object.entries(toolDocMap.value).find(([, v]) => v.id === data.id)
      if (ent) {
        const k = Number(ent[0])
        if (!Number.isNaN(k)) toolDocMap.value = { ...toolDocMap.value, [k]: data }
      }
    }
    docEditVisible.value = false
    ElMessage.success('已保存')
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    docEditSaving.value = false
  }
}

function onModuleSelectionChange(rows: ScanModule[]) {
  selectedModuleIds.value = rows.map((r) => r.id)
}

function openMergeDialog() {
  const selected = modules.value.filter((m) => selectedModuleIds.value.includes(m.id))
  if (selected.length < 2) {
    ElMessage.warning('至少选中 2 个模块才能合并')
    return
  }
  mergeSelectedModules.value = selected
  mergeTargetId.value = selected[0].id
  mergeDisplayName.value = ''
  mergeSourceModules.value = selected
  mergeDialogVisible.value = true
}

watch(mergeTargetId, (target) => {
  mergeSourceModules.value = mergeSelectedModules.value.filter((m) => m.id !== target)
})

async function submitMerge() {
  if (!mergeTargetId.value) return
  const sourceIds = mergeSourceModules.value.map((m) => m.id)
  mergeSaving.value = true
  try {
    await mergeScanModules({
      targetId: mergeTargetId.value,
      sourceIds,
      displayName: mergeDisplayName.value || null,
    })
    mergeDialogVisible.value = false
    ElMessage.success('合并成功')
    await reloadAiTab()
  } catch (error) {
    ElMessage.error((error as Error).message || '合并失败')
  } finally {
    mergeSaving.value = false
  }
}

function openRenameDialog(row: ScanModule) {
  renameTarget.value = row
  renameValue.value = row.displayName
  renameDialogVisible.value = true
}

async function submitRename() {
  if (!renameTarget.value) return
  renameSaving.value = true
  try {
    await renameScanModule(renameTarget.value.id, renameValue.value.trim())
    renameDialogVisible.value = false
    ElMessage.success('已重命名')
    await reloadAiTab()
  } catch (error) {
    ElMessage.error((error as Error).message || '重命名失败')
  } finally {
    renameSaving.value = false
  }
}

onMounted(() => {
  void refreshAll()
  void loadSemanticProviders()
  void resumeBatchTaskIfAny()
})
onUnmounted(stopPollingTask)
</script>

<style scoped lang="scss">
.header-actions {
  display: flex;
  gap: 8px;
}

.project-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 20px;
  font-size: 14px;
}

.tools-table-wrap {
  min-height: 120px;
}

.tool-groups-collapse {
  border: none;

  :deep(.el-collapse-item__header) {
    font-weight: 600;
    padding: 0 16px 0 14px;
    min-height: 44px;
    align-items: center;
  }

  :deep(.el-collapse-item__wrap) {
    border-bottom: none;
  }

  :deep(.el-collapse-item__content) {
    padding: 0 4px 12px 0;
  }
}

.module-collapse-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
  box-sizing: border-box;
  padding-right: 4px;
}

.module-collapse-title__text {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}

.collapse-module-title {
  margin-right: 0;
}

.module-tool-count {
  vertical-align: middle;
  flex-shrink: 0;
}

.nested-tools-table {
  margin-top: 4px;
}

.tools-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.tools-actions {
  display: flex;
  gap: 8px;
}

.expand-content {
  padding: 12px 20px;

  h4 {
    font-size: 13px;
    color: #909399;
    margin-bottom: 8px;
  }
}

.tool-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
  margin-top: 12px;
  font-size: 13px;
  color: #606266;
}

.parameter-editor {
  width: 100%;
}

.add-parameter-button {
  margin-top: 8px;
}

.switch-group {
  display: flex;
  align-items: center;
  gap: 10px;
}

.param-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.test-result-area {
  margin-top: 12px;
}

.result-content {
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 12px;
  font-size: 13px;
  margin-top: 12px;
  max-height: 200px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.result-duration {
  font-size: 12px;
  color: #909399;
  margin-top: 8px;
}

.auth-hint {
  font-size: 13px;
  color: #909399;
  margin: 0 0 12px;
  line-height: 1.5;
}

.auth-form {
  max-width: 640px;
}

.scan-settings-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.scan-settings-header-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}

.scan-settings-mode-alert {
  margin-bottom: 8px;
}

.scan-settings-hint {
  font-size: 13px;
  color: #909399;
  margin: 0 0 12px;
  line-height: 1.5;
}

.scan-settings-form {
  max-width: 800px;
}

.is-disabled-form-item {
  opacity: 0.7;
}

.order-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  max-width: 640px;
}

.order-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  min-width: 0;
}

.order-label {
  flex: 1;
  min-width: 0;
  font-size: 14px;
}

.order-source-switch {
  flex-shrink: 0;
}

.http-method-select {
  min-width: 280px;
}

.incr-radio {
  flex-wrap: wrap;
}

.scan-detail-sections {
  border: none;
  --el-collapse-header-height: 50px;
}
.scan-detail-sections :deep(.scan-detail-top-item) {
  margin-bottom: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  overflow: hidden;
  background: var(--el-bg-color);
}
.scan-detail-sections :deep(.scan-detail-top-item .el-collapse-item__header) {
  padding: 0 20px 0 18px;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
  min-height: var(--el-collapse-header-height);
  align-items: center;
  box-sizing: border-box;
}
.scan-detail-sections :deep(.scan-detail-top-item .el-collapse-item__content) {
  padding: 4px 20px 18px;
}
.scan-detail-sections :deep(.scan-detail-top-item:last-of-type) {
  margin-bottom: 0;
}
.ai-settings-card {
  margin-top: 0;
}

.ai-settings-hint {
  font-size: 13px;
  color: #909399;
  margin: 0 0 12px;
  line-height: 1.5;
}

.merged-tools-card {
  margin-top: 0;
}

.merged-interface-table {
  min-width: 1080px;
}

.expand-merged .expand-ai-heading {
  margin: 16px 0 8px;
  font-size: 13px;
  color: #909399;
}

.merged-ops-wrap {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 2px;
  line-height: 1.5;
}

.merged-ops-sep {
  display: inline-block;
  width: 1px;
  height: 14px;
  margin: 0 8px 0 4px;
  background: #dcdfe6;
  vertical-align: middle;
  flex-shrink: 0;
}

.ai-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.semantic-llm-select {
  width: 140px;
}

.semantic-model-select {
  width: 200px;
}

.token-sum {
  color: #909399;
  font-size: 12px;
  margin-left: auto;
}

.task-progress {
  margin-top: 4px;
}

.ai-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.markdown-body {
  font-size: 13px;
  line-height: 1.7;
  padding: 4px 4px 8px;

  :deep(h1), :deep(h2), :deep(h3) {
    margin-top: 12px;
    margin-bottom: 6px;
  }

  :deep(pre) {
    background: #f6f7f9;
    border-radius: 4px;
    padding: 10px;
    overflow: auto;
  }

  :deep(table) {
    border-collapse: collapse;

    th, td {
      border: 1px solid #dcdfe6;
      padding: 4px 8px;
    }
  }
}

.expand-md {
  padding: 8px 16px 16px;
}

.ai-desc-ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.source-class-tag {
  margin-right: 6px;
  margin-bottom: 4px;
}
</style>
