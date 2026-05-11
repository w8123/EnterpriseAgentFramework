<template>
  <div class="page-container">
    <div class="page-header">
      <h2>能力管理</h2>
      <div class="header-actions">
        <el-button type="primary" @click="openCreateDialog">
          <el-icon><Plus /></el-icon>新建能力
        </el-button>
        <el-button @click="onRefresh" :loading="loading">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
        <el-button type="warning" plain @click="openPendingInteractionsDialog">
          <el-icon><List /></el-icon>测试挂起交互
        </el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="能力（Capability）是把「一段稳定的多步业务流程」打包成一个粗粒度可调用的单元"
      description="支持多种形态：子智能体能力（系统提示词 + 工具白名单）；交互式表单能力，结构化收集参数后调用目标 Tool。上层智能体仍像调用普通 Tool 一样选用它们。"
      style="margin-bottom: 16px"
    />

    <el-card shadow="never">
      <el-form :inline="true" class="tool-filter" @submit.prevent="handleSearch">
        <el-form-item label="关键词">
          <el-input
            v-model="filters.keyword"
            clearable
            placeholder="名称或描述"
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-select v-model="filters.enabled" clearable placeholder="全部" style="width: 120px">
            <el-option label="是" :value="true" />
            <el-option label="否" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item label="草稿">
          <el-select v-model="filters.draft" clearable placeholder="全部" style="width: 120px">
            <el-option label="是" :value="true" />
            <el-option label="否" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目">
          <el-select
            v-model="filters.projectId"
            clearable
            filterable
            placeholder="全部"
            style="width: 220px"
          >
            <el-option
              v-for="project in scanProjects"
              :key="project.id"
              :label="projectOptionLabel(project)"
              :value="project.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="capabilities" v-loading="loading" stripe>
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-content">
              <h4>参数 Schema</h4>
              <el-table :data="row.parameters || []" size="small" border>
                <el-table-column prop="name" label="参数名" min-width="160" />
                <el-table-column prop="type" label="类型" width="120" />
                <el-table-column prop="description" label="描述" />
                <el-table-column prop="required" label="必填" width="80" align="center">
                  <template #default="{ row: p }">
                    <el-tag :type="p.required ? 'danger' : 'info'" size="small">
                      {{ p.required ? '是' : '否' }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>

              <template v-if="row.skillKind === 'INTERACTIVE_FORM'">
                <h4 style="margin-top: 16px">交互式表单 Spec</h4>
                <InteractiveFormSpecEditor
                  :model-value="normalizeInteractiveFormSpec(row.spec)"
                  :tool-options="toolOptions"
                  readonly
                />
              </template>
              <template v-else>
                <h4 style="margin-top: 16px">子 Agent Spec</h4>
                <div class="meta-grid">
                  <div><b>模型：</b>{{ (row.spec as any)?.llmProvider || '-' }} / {{ (row.spec as any)?.llmModel || '-' }}</div>
                  <div><b>最大步数：</b>{{ (row.spec as any)?.maxSteps ?? 8 }}</div>
                  <div class="span-2"><b>工具白名单：</b>{{ ((row.spec as any)?.toolWhitelist || []).join(', ') || '-' }}</div>
                  <div class="span-2"><b>系统提示词：</b><pre class="prompt-preview">{{ (row.spec as any)?.systemPrompt || '-' }}</pre></div>
                </div>
              </template>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="能力名" min-width="220">
          <template #default="{ row }">
            <el-text type="primary" tag="b">{{ row.name }}</el-text>
            <el-tag v-if="row.draft" type="warning" size="small" style="margin-left: 8px">草稿</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="形态" width="130" align="center">
          <template #default="{ row }">
            <el-tag size="small">{{ formatSkillKindLabel(row.skillKind) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="副作用" width="150" align="center">
          <template #default="{ row }">
            <el-tag :type="sideEffectTagType(row.sideEffect)" size="small">
              {{ formatSideEffectLabel(row.sideEffect) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="项目编码" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.projectCode || projectCodeById(row.projectId) || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="可见性" width="110">
          <template #default="{ row }">
            <el-tag size="small">{{ formatVisibilityLabel(row.visibility || 'PRIVATE') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="qualifiedName" label="全限定名" min-width="180" show-overflow-tooltip />
        <el-table-column prop="description" label="描述" min-width="280" show-overflow-tooltip />
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :disabled="Boolean(row.draft)"
              @change="handleEnabledChange(row, $event as boolean)"
            />
          </template>
        </el-table-column>
        <el-table-column label="Agent 可见" width="110" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.agentVisible"
              @change="handleVisibleChange(row, $event as boolean)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <div class="operation-actions">
              <button type="button" class="table-action-link" @click.stop="openEditDialog(row)">编辑</button>
              <button
                type="button"
                class="table-action-link"
                :disabled="Boolean(row.draft)"
                @click.stop="openTest(row)"
              >
                测试
              </button>
              <button type="button" class="table-action-link success" @click.stop="openMetrics(row)">指标</button>
              <button type="button" class="table-action-link danger" @click.stop="handleDelete(row)">删除</button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && capabilities.length === 0" description="尚未创建能力，点右上角新建" />
      <div v-if="total > 0" class="pagination-wrap">
        <el-pagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="fetchCapabilities"
          @size-change="handlePageSizeChange"
        />
      </div>
    </el-card>

    <el-dialog
      v-model="formDialogVisible"
      :title="formDialogTitle"
      width="1180px"
      top="3vh"
      append-to-body
      class="capability-editor-dialog"
    >
      <div class="capability-editor">
        <aside class="editor-rail">
          <button
            v-for="(step, index) in capabilityEditorSteps"
            :key="step.key"
            type="button"
            class="editor-step"
            :class="{ active: activeCapabilityStep === index }"
            @click="activeCapabilityStep = index"
          >
            <span class="step-index">{{ index + 1 }}</span>
            <span>
              <b>{{ step.title }}</b>
              <small>{{ step.desc }}</small>
            </span>
          </button>
        </aside>

        <section class="editor-main">
          <div class="editor-summary">
            <div>
              <p class="summary-eyebrow">{{ formatSkillKindLabel(form.skillKind) }}</p>
              <h3>{{ form.name || '未命名能力' }}</h3>
              <p>{{ form.description || '填写一句能让 Agent 判断何时调用的描述。' }}</p>
            </div>
            <div class="summary-tags">
              <el-tag size="small" effect="plain">{{ formatVisibilityLabel(form.visibility || 'PRIVATE') }}</el-tag>
              <el-tag size="small" :type="sideEffectTagType(form.sideEffect)" effect="plain">
                {{ formatSideEffectLabel(form.sideEffect) }}
              </el-tag>
              <el-tag v-if="editingIsDraft" size="small" type="warning" effect="plain">草稿</el-tag>
            </div>
          </div>

          <el-form label-width="112px" class="editor-form">
            <div v-show="activeCapabilityStep === 0" class="editor-panel">
              <div class="panel-heading">
                <h4>基本信息</h4>
                <p>定义这个能力叫什么、归属哪里，以及 Agent 是否可以看见它。</p>
              </div>
              <el-form-item label="能力名">
                <el-input v-model="form.name" :disabled="isEditMode" placeholder="snake_case，如 risk_customer_triage" />
              </el-form-item>
              <el-form-item label="描述">
                <el-input
                  v-model="form.description"
                  type="textarea"
                  :rows="4"
                  placeholder="一句话讲清楚这个能力解决什么问题、什么场景下调用。LLM 根据它决定是否选中。"
                />
              </el-form-item>
              <el-row :gutter="16">
                <el-col :span="12">
                  <el-form-item label="项目">
                    <el-select
                      v-model="form.projectId"
                      clearable
                      filterable
                      placeholder="全局能力"
                      style="width: 100%"
                      @change="handleFormProjectChange"
                    >
                      <el-option
                        v-for="project in scanProjects"
                        :key="project.id"
                        :label="projectOptionLabel(project)"
                        :value="project.id"
                      />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="可见性">
                    <el-select v-model="form.visibility" style="width: 100%">
                      <el-option
                        v-for="opt in VISIBILITY_SELECT_OPTIONS"
                        :key="opt.value"
                        :label="opt.label"
                        :value="opt.value"
                      />
                    </el-select>
                  </el-form-item>
                </el-col>
              </el-row>
              <el-row :gutter="16">
                <el-col :span="12">
                  <el-form-item label="形态">
                    <el-select v-model="form.skillKind" style="width: 100%" @change="onSkillKindChange">
                      <el-option
                        v-for="opt in CAPABILITY_KIND_OPTIONS"
                        :key="opt.value"
                        :label="opt.label"
                        :value="opt.value"
                      />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="副作用">
                    <el-select v-model="form.sideEffect" style="width: 100%">
                      <el-option
                        v-for="opt in SIDE_EFFECT_OPTIONS"
                        :key="opt.value"
                        :label="opt.label"
                        :value="opt.value"
                      />
                    </el-select>
                  </el-form-item>
                </el-col>
              </el-row>
              <div class="meta-readonly">
                <span>全限定名</span>
                <code>{{ resolvedFormQualifiedName || '保存后生成' }}</code>
              </div>
            </div>

            <div v-show="activeCapabilityStep === 1" class="editor-panel">
              <div class="panel-heading">
                <h4>{{ form.skillKind === 'INTERACTIVE_FORM' ? '执行动作与表单' : '子 Agent 执行策略' }}</h4>
                <p>{{ form.skillKind === 'INTERACTIVE_FORM' ? '先选择最终调用的 Tool，再根据 Tool 入参生成用户要填写的表单。' : '为子 Agent 配置提示词、工具白名单和推理边界。' }}</p>
              </div>
              <template v-if="form.skillKind === 'INTERACTIVE_FORM'">
                <InteractiveFormSpecEditor v-model="interactiveSpec" :tool-options="toolOptions" />
              </template>
              <template v-else>
                <el-form-item label="系统提示词">
                  <el-input
                    v-model="form.spec.systemPrompt"
                    type="textarea"
                    :rows="8"
                    placeholder="子 Agent 的角色设定 + 工作流程，用 Markdown/自然语言写。建议约束：1) 只在明确收到请求时执行；2) 步骤与顺序；3) 最终输出结构。"
                  />
                </el-form-item>
                <el-form-item label="工具白名单">
                  <el-select
                    v-model="form.spec.toolWhitelist"
                    multiple
                    filterable
                    placeholder="选择子 Agent 可调用的 Tool（不可嵌套其他粗粒度能力）"
                    style="width: 100%"
                  >
                    <el-option
                      v-for="t in toolOptions"
                      :key="t.name"
                      :label="toolOptionLabel(t)"
                      :value="t.name"
                    />
                  </el-select>
                  <div class="param-hint">仅能选择原子 Tool；嵌套粗粒度能力会被运行时拦截。</div>
                </el-form-item>
                <el-row :gutter="16">
                  <el-col :span="8">
                    <el-form-item label="最大步数">
                      <el-input-number v-model="form.spec.maxSteps" :min="1" :max="50" style="width: 100%" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="Provider">
                      <el-input v-model="form.spec.llmProvider" placeholder="留空继承父 Agent" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="Model">
                      <el-input v-model="form.spec.llmModel" placeholder="留空继承父 Agent" />
                    </el-form-item>
                  </el-col>
                </el-row>
              </template>
            </div>

            <div v-show="activeCapabilityStep === 2" class="editor-panel">
              <div class="panel-heading">
                <h4>Agent 调用入口</h4>
                <p>这是上层 Agent 看到的参数 Schema。普通场景可以保持简洁，只暴露必要入口。</p>
              </div>
              <ParameterTable
                v-model="form.parameters as any"
                :show-location="false"
                :type-options="['string', 'integer', 'number', 'boolean', 'object', 'array']"
              />
            </div>

            <div v-show="activeCapabilityStep === 3" class="editor-panel">
              <div class="panel-heading">
                <h4>运行控制</h4>
                <p>控制能力是否启用、是否对 Agent 可见，并在发布前确认关键配置。</p>
              </div>
              <div class="control-grid">
                <div class="control-card">
                  <span>启用</span>
                  <el-tooltip
                    :disabled="!editingIsDraft"
                    content="草稿需先「发布」后才能在列表中启用"
                    placement="top"
                  >
                    <el-switch v-model="form.enabled" :disabled="editingIsDraft" />
                  </el-tooltip>
                </div>
                <div class="control-card">
                  <span>Agent 可见</span>
                  <el-switch v-model="form.agentVisible" />
                </div>
              </div>
              <el-alert
                v-if="editingIsDraft"
                class="editor-alert"
                type="warning"
                :closable="false"
                show-icon
                title="当前为草稿；发布通过校验后即可在列表中启用。"
              />
              <div class="publish-checklist">
                <div>
                  <b>能力名</b>
                  <span>{{ form.name || '未填写' }}</span>
                </div>
                <div>
                  <b>描述</b>
                  <span>{{ form.description ? '已填写' : '未填写' }}</span>
                </div>
                <div>
                  <b>{{ form.skillKind === 'INTERACTIVE_FORM' ? '目标 Tool' : '系统提示词' }}</b>
                  <span>{{ form.skillKind === 'INTERACTIVE_FORM' ? (interactiveSpec.targetTool || '未选择') : ((form.spec as SubAgentSpec).systemPrompt ? '已填写' : '未填写') }}</span>
                </div>
              </div>
            </div>
          </el-form>
        </section>
      </div>

      <template #footer>
        <div class="editor-footer">
          <el-button :disabled="activeCapabilityStep === 0" @click="activeCapabilityStep -= 1">上一步</el-button>
          <el-button :disabled="activeCapabilityStep >= capabilityEditorSteps.length - 1" @click="activeCapabilityStep += 1">
            下一步
          </el-button>
        </div>
        <el-button @click="formDialogVisible = false">取消</el-button>
        <el-button v-if="!isEditMode || editingIsDraft" :loading="saving" @click="handleSaveDraft">
          暂存
        </el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">
          {{ isEditMode && editingIsDraft ? '发布' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="testDialogVisible" :title="`测试能力 — ${testingSkill?.name}`" width="720px" append-to-body>
      <el-form v-if="testingSkill" label-width="140px">
        <el-form-item
          v-for="param in testingSkill.parameters"
          :key="param.name"
          :label="param.name"
          :required="param.required"
        >
          <el-input
            v-model="testArgs[param.name]"
            :placeholder="param.description || param.type"
          />
          <div class="param-hint">{{ param.description }} ({{ param.type }})</div>
        </el-form-item>
      </el-form>

      <div v-if="testResult" class="test-result-area">
        <el-divider content-position="left">执行结果</el-divider>
        <el-alert
          v-if="testResult.interactionPending && testResult.success"
          type="warning"
          title="等待交互（非执行失败）"
          :description="testResult.result || '请按下方提示继续。'"
          :closable="false"
          show-icon
        />
        <el-alert v-else :type="testResult.success ? 'success' : 'error'" :closable="false" show-icon>
          <template #title>
            <span>{{ testResult.success ? '执行成功' : '执行失败' }}</span>
          </template>
          <template #default>
            <p style="margin: 0">
              {{
                testResult.errorMessage ||
                (!testResult.success ? testResult.result : '') ||
                ''
              }}
            </p>
            <p v-if="isPendingQuotaBlockError(testResult.errorMessage)" style="margin: 8px 0 0">
              <el-button link type="primary" @click="openPendingInteractionsDialog">
                查看并取消挂起中的交互
              </el-button>
              <span class="param-hint">（管理端测试会话 skill-admin-test，最多 5 条未完成）</span>
            </p>
          </template>
        </el-alert>
        <pre
          v-if="testResult.result && !(testResult.interactionPending && testResult.success)"
          class="result-content"
        >{{ testResult.result }}</pre>
        <!-- 与 Agent 调试台一致：挂起时用 DynamicInteraction 填写 / 确认 -->
        <div
          v-if="testResult.interactionPending && testResult.interactionId && testResult.uiRequest"
          class="skill-test-interaction-wrap"
          v-loading="testResumeRunning"
        >
          <p class="param-hint skill-test-interaction-hint">
            下方为与对话里相同的交互界面（表单、确认卡等），填写后提交即可继续；也可展开底部查看原始 JSON。
          </p>
          <DynamicInteraction
            :payload="testResult.uiRequest as unknown as UiRequestPayload"
            @action="handleTestUiAction"
          />
          <el-collapse class="skill-test-raw-json">
            <el-collapse-item title="原始 uiRequest JSON（调试）" name="raw">
              <pre class="result-content ui-payload">{{ formatUiRequestPreview(testResult.uiRequest) }}</pre>
            </el-collapse-item>
            <el-collapse-item
              v-if="uiRequestComponent(testResult.uiRequest) === 'form'"
              title="手动输入 JSON（备选，与上方表单二选一）"
              name="json"
            >
              <p class="param-hint">若组件未覆盖某字段类型，可在此提交键值 JSON：</p>
              <el-input
                v-model="testResumeValuesJson"
                type="textarea"
                :rows="4"
                placeholder='例如 { "deptId": "001" }'
              />
              <el-button
                type="primary"
                class="test-resume-btn"
                :loading="testResumeRunning"
                @click="handleTestResumeJsonSubmit"
              >
                按 JSON 提交本批
              </el-button>
            </el-collapse-item>
          </el-collapse>
        </div>
        <p class="result-duration">耗时：{{ testResult.durationMs }}ms</p>
      </div>

      <template #footer>
        <el-button @click="testDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="handleTest" :loading="testRunning && !testResumeRunning">执行</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="pendingDialogVisible"
      title="能力测试 — 挂起中的未完成交互"
      width="760px"
      @opened="loadPendingInteractions"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
        title="说明"
        description="页面「测试」按钮使用固定测试身份（skill-admin-test，legacy 会话 id）。交互式表单能力挂起后会计入未完成条数，达到 5 条后将无法新开测试。可在此取消单条或全部取消后重试。"
      />
      <div style="margin-bottom: 10px; display: flex; gap: 8px; flex-wrap: wrap">
        <el-button size="small" :loading="pendingLoading" @click="loadPendingInteractions">刷新列表</el-button>
        <el-button
          size="small"
          type="danger"
          plain
          :loading="pendingCancelAllRunning"
          :disabled="pendingList.length === 0"
          @click="handleCancelAllPending"
        >
          全部取消
        </el-button>
      </div>
      <el-table :data="pendingList" v-loading="pendingLoading" stripe size="small" max-height="360">
        <el-table-column prop="skillName" label="能力" min-width="140" show-overflow-tooltip />
        <el-table-column prop="uiTitle" label="界面标题" min-width="120" show-overflow-tooltip />
        <el-table-column prop="interactionId" label="interactionId" min-width="220" show-overflow-tooltip />
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatPendingTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="过期时间" width="170">
          <template #default="{ row }">{{ formatPendingTime(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="danger"
              size="small"
              :loading="pendingRowDeleting === row.interactionId"
              @click="handleCancelOnePending(row.interactionId)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!pendingLoading && pendingList.length === 0" description="当前无挂起中的测试交互" />
      <template #footer>
        <el-button type="primary" @click="pendingDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="metricsDialogVisible" :title="`能力指标 — ${metricsSkillName}`" width="760px" append-to-body>
      <div v-if="!metricsData">暂无指标数据</div>
      <template v-else>
        <el-row :gutter="12" class="metric-cards">
          <el-col :span="6"><el-statistic title="调用次数" :value="metricsData.callCount" /></el-col>
          <el-col :span="6"><el-statistic title="成功率" :value="Number((metricsData.successRate * 100).toFixed(2))" suffix="%" /></el-col>
          <el-col :span="6"><el-statistic title="P95 延迟" :value="metricsData.p95LatencyMs" suffix="ms" /></el-col>
          <el-col :span="6"><el-statistic title="P95 Token" :value="metricsData.p95TokenCost" /></el-col>
        </el-row>
        <el-table :data="metricsData.trends" stripe size="small" style="margin-top: 12px">
          <el-table-column prop="day" label="日期" width="130" />
          <el-table-column prop="callCount" label="调用量" width="100" />
          <el-table-column label="成功率" width="120">
            <template #default="{ row }">{{ (row.successRate * 100).toFixed(2) }}%</template>
          </el-table-column>
          <el-table-column prop="p95LatencyMs" label="P95 延迟(ms)" width="140" />
          <el-table-column prop="p95TokenCost" label="P95 Token" width="120" />
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { List, Plus, Refresh } from '@element-plus/icons-vue'
import ParameterTable from '@/components/ParameterTable.vue'
import InteractiveFormSpecEditor from '@/components/capability/InteractiveFormSpecEditor.vue'
import DynamicInteraction from '@/components/interaction/DynamicInteraction.vue'
import {
  cancelAdminTestPendingInteraction,
  cancelAllAdminTestPendingInteractions,
  createCapability,
  deleteCapability,
  getAdminTestPendingInteractions,
  getCapabilityMetrics,
  listCapabilities,
  testCapability,
  testCapabilityResume,
  toggleCapability,
  updateCapability,
} from '@/api/capability'
import { getTools } from '@/api/tool'
import { getScanProjects } from '@/api/scanProject'
import type {
  InteractiveFormSpec,
  CapabilityAdminTestPendingItem,
  CapabilityInfo,
  CapabilityMetrics,
  CapabilityUpsertRequest,
  SubAgentSpec,
} from '@/types/capability'
import type { UiRequestPayload } from '@/types/interaction'
import {
  SIDE_EFFECT_OPTIONS,
  CAPABILITY_KIND_OPTIONS,
  defaultInteractiveFormSpec,
  formatSideEffectLabel,
  formatSkillKindLabel,
  normalizeInteractiveFormSpec,
  validateInteractiveFormSpec,
} from '@/types/capability'
import { VISIBILITY_SELECT_OPTIONS, formatVisibilityLabel } from '@/utils/projectLabels'
import type { ToolInfo } from '@/types/tool'
import type { ScanProject } from '@/types/scanProject'
import { useProjectStore } from '@/store/project'

const route = useRoute()
const projectStore = useProjectStore()
const capabilities = ref<CapabilityInfo[]>([])
const scanProjects = ref<ScanProject[]>([])
const total = ref(0)
const loading = ref(false)
const filters = reactive({
  keyword: '',
  enabled: undefined as boolean | undefined,
  draft: undefined as boolean | undefined,
  projectId: undefined as number | undefined,
})
const pagination = reactive({ current: 1, size: 20 })
const saving = ref(false)

const toolOptions = ref<ToolInfo[]>([])

const formDialogVisible = ref(false)
const editingName = ref<string | null>(null)
const activeCapabilityStep = ref(0)
/** 打开编辑对话框时该行是否为草稿（控制「暂存」与表单内「启用」） */
const editingIsDraft = ref(false)
const form = reactive<CapabilityUpsertRequest>(createEmptyForm())
/** INTERACTIVE_FORM 形态下编辑的结构化 spec */
const interactiveSpec = ref<InteractiveFormSpec>(defaultInteractiveFormSpec())
const isEditMode = computed(() => editingName.value !== null)
const formDialogTitle = computed(() =>
  isEditMode.value ? `编辑能力 — ${form.name}` : '新建能力',
)
const capabilityEditorSteps = computed(() => [
  { key: 'basic', title: '基本信息', desc: '命名、归属与可见性' },
  {
    key: 'spec',
    title: form.skillKind === 'INTERACTIVE_FORM' ? '执行动作' : '执行策略',
    desc: form.skillKind === 'INTERACTIVE_FORM' ? '选择 Tool 并设计表单' : '提示词与工具白名单',
  },
  { key: 'schema', title: '调用入口', desc: 'Agent 参数 Schema' },
  { key: 'release', title: '运行发布', desc: '启用、可见与校验' },
])
const resolvedFormQualifiedName = computed(() => {
  if (form.qualifiedName) return form.qualifiedName
  if (form.projectCode && form.name) return `${form.projectCode}:${form.name}`
  return ''
})

const testDialogVisible = ref(false)
const testingSkill = ref<CapabilityInfo | null>(null)
const testArgs = reactive<Record<string, string>>({})
const testResult = ref<{
  success: boolean
  result: string
  errorMessage?: string
  durationMs: number
  interactionPending?: boolean
  interactionId?: string | null
  uiRequest?: Record<string, unknown> | null
} | null>(null)
const testRunning = ref(false)
const testResumeRunning = ref(false)
const testResumeValuesJson = ref('{}')
const metricsDialogVisible = ref(false)
const metricsSkillName = ref('')
const metricsData = ref<CapabilityMetrics | null>(null)

const pendingDialogVisible = ref(false)
const pendingList = ref<CapabilityAdminTestPendingItem[]>([])
const pendingLoading = ref(false)
const pendingRowDeleting = ref<string | null>(null)
const pendingCancelAllRunning = ref(false)

function onSkillKindChange(kind: string) {
  if (kind === 'INTERACTIVE_FORM') {
    interactiveSpec.value = defaultInteractiveFormSpec()
  }
}

function createEmptyForm(): CapabilityUpsertRequest {
  return {
    name: '',
    description: '',
    parameters: [],
    skillKind: 'SUB_AGENT',
    sideEffect: 'WRITE',
    projectId: null,
    projectCode: null,
    visibility: 'PRIVATE',
    qualifiedName: null,
    enabled: true,
    agentVisible: true,
    spec: {
      systemPrompt: '',
      toolWhitelist: [],
      llmProvider: '',
      llmModel: '',
      maxSteps: 8,
      useMultiAgentModel: false,
    },
  }
}

function sideEffectTagType(level?: string | null) {
  switch ((level || '').toUpperCase()) {
    case 'NONE':
    case 'READ_ONLY':
      return 'success'
    case 'IDEMPOTENT_WRITE':
      return 'info'
    case 'WRITE':
      return 'warning'
    case 'IRREVERSIBLE':
      return 'danger'
    default:
      return 'info'
  }
}

function buildListParams() {
  return {
    current: pagination.current,
    size: pagination.size,
    ...(filters.keyword.trim() ? { keyword: filters.keyword.trim() } : {}),
    ...(filters.enabled !== undefined ? { enabled: filters.enabled } : {}),
    ...(filters.draft !== undefined ? { draft: filters.draft } : {}),
    ...(filters.projectId !== undefined ? { projectId: filters.projectId } : {}),
  }
}

async function fetchCapabilities() {
  loading.value = true
  try {
    const { data } = await listCapabilities(buildListParams())
    if (data && 'records' in data) {
      capabilities.value = Array.isArray(data.records) ? data.records : []
      total.value = typeof data.total === 'number' ? data.total : 0
    } else {
      capabilities.value = []
      total.value = 0
    }
  } catch {
    capabilities.value = []
    total.value = 0
    ElMessage.error('加载能力列表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.current = 1
  return fetchCapabilities()
}

function handlePageSizeChange() {
  pagination.current = 1
  return fetchCapabilities()
}

function resetFilters() {
  filters.keyword = ''
  filters.enabled = undefined
  filters.draft = undefined
  filters.projectId = projectStore.currentProjectId ?? undefined
  pagination.current = 1
  return fetchCapabilities()
}

async function loadToolOptions(scopeProjectId?: number | null) {
  const effectiveProjectId =
    scopeProjectId !== undefined
      ? scopeProjectId
      : formDialogVisible.value
        ? form.projectId
        : filters.projectId ?? null
  try {
    const { data } = await getTools({
      current: 1,
      size: 200,
      enabled: true,
      ...(effectiveProjectId != null ? { projectId: effectiveProjectId } : {}),
    })
    if (data && 'records' in data) {
      toolOptions.value = data.records || []
    }
  } catch {
    toolOptions.value = []
  }
}

async function loadScanProjects() {
  try {
    const { data } = await getScanProjects()
    scanProjects.value = Array.isArray(data) ? data : []
    projectStore.projects = scanProjects.value
  } catch {
    scanProjects.value = []
  }
}

function projectOptionLabel(project?: ScanProject | null) {
  if (!project) return ''
  const code = project.projectCode ? ` / ${project.projectCode}` : ''
  const env = project.environment ? ` · ${project.environment}` : ''
  return `${project.name}${code}${env}`
}

function projectCodeById(projectId?: number | null) {
  if (projectId == null) return null
  return scanProjects.value.find((project) => project.id === projectId)?.projectCode || null
}

function toolOptionLabel(tool: ToolInfo) {
  const project = tool.projectCode ? ` · ${tool.projectCode}` : ''
  const visibility = tool.visibility ? ` · ${formatVisibilityLabel(tool.visibility)}` : ''
  const desc = tool.description ? ` — ${tool.description.slice(0, 40)}` : ''
  return `${tool.name}${project}${visibility}${desc}`
}

function syncFiltersWithProjectContext() {
  const queryProjectId = Number(route.query.projectId)
  if (Number.isFinite(queryProjectId) && queryProjectId > 0) {
    filters.projectId = queryProjectId
    projectStore.setCurrentProject(queryProjectId)
    return
  }
  filters.projectId = projectStore.currentProjectId ?? undefined
}

function handleFormProjectChange(projectId: number | null | undefined, clearSelection = true) {
  const project = scanProjects.value.find((item) => item.id === projectId)
  form.projectCode = project?.projectCode || null
  form.qualifiedName = null
  if (clearSelection) {
    if (form.skillKind === 'INTERACTIVE_FORM') {
      interactiveSpec.value.targetTool = ''
    } else {
      const spec = form.spec as SubAgentSpec
      spec.toolWhitelist = []
    }
  }
  loadToolOptions(projectId ?? null)
}

async function onRefresh() {
  await loadScanProjects()
  await loadToolOptions(filters.projectId ?? null)
  return fetchCapabilities()
}

function applyForm(data: CapabilityUpsertRequest) {
  form.name = data.name
  form.description = data.description
  form.parameters = [...(data.parameters || [])]
  form.skillKind = data.skillKind
  form.sideEffect = data.sideEffect ?? 'WRITE'
  form.projectId = data.projectId ?? null
  form.projectCode = data.projectCode ?? projectCodeById(data.projectId) ?? null
  form.visibility = data.visibility || 'PRIVATE'
  form.qualifiedName = data.qualifiedName || null
  form.enabled = data.enabled
  form.agentVisible = data.agentVisible
  if (data.skillKind === 'INTERACTIVE_FORM') {
    const s = data.spec as Record<string, unknown> | undefined
    interactiveSpec.value =
      s && typeof s === 'object' && 'targetTool' in s
        ? normalizeInteractiveFormSpec(s)
        : defaultInteractiveFormSpec()
    form.spec = {
      systemPrompt: '',
      toolWhitelist: [],
      llmProvider: '',
      llmModel: '',
      maxSteps: 8,
      useMultiAgentModel: false,
    }
  } else {
    const s = data.spec as SubAgentSpec
    form.spec = {
      systemPrompt: s?.systemPrompt || '',
      toolWhitelist: [...(s?.toolWhitelist || [])],
      llmProvider: s?.llmProvider || '',
      llmModel: s?.llmModel || '',
      maxSteps: s?.maxSteps ?? 8,
      useMultiAgentModel: s?.useMultiAgentModel ?? false,
    }
    interactiveSpec.value = defaultInteractiveFormSpec()
  }
}

function openCreateDialog() {
  editingName.value = null
  editingIsDraft.value = false
  activeCapabilityStep.value = 0
  applyForm(createEmptyForm())
  form.projectId = projectStore.currentProjectId
  handleFormProjectChange(form.projectId, false)
  formDialogVisible.value = true
}

function openEditDialog(skill: CapabilityInfo) {
  editingName.value = skill.name
  editingIsDraft.value = Boolean(skill.draft)
  activeCapabilityStep.value = 0
  applyForm({
    name: skill.name,
    description: skill.description,
    parameters: skill.parameters || [],
    skillKind: skill.skillKind || 'SUB_AGENT',
    sideEffect: skill.sideEffect || 'WRITE',
    projectId: skill.projectId ?? null,
    projectCode: skill.projectCode ?? projectCodeById(skill.projectId) ?? null,
    visibility: skill.visibility || 'PRIVATE',
    qualifiedName: skill.qualifiedName || null,
    enabled: skill.enabled,
    agentVisible: skill.agentVisible,
    spec: skill.spec || {
      systemPrompt: '',
      toolWhitelist: [],
      llmProvider: '',
      llmModel: '',
      maxSteps: 8,
      useMultiAgentModel: false,
    },
  })
  formDialogVisible.value = true
  loadToolOptions(skill.projectId ?? null)
}

function buildSpecPayload(): Record<string, unknown> | SubAgentSpec {
  return form.skillKind === 'INTERACTIVE_FORM'
    ? (JSON.parse(JSON.stringify(interactiveSpec.value)) as unknown as Record<string, unknown>)
    : { ...form.spec, toolWhitelist: [...(form.spec as SubAgentSpec).toolWhitelist] }
}

function buildUpsertPayload(draft: boolean): CapabilityUpsertRequest {
  return {
    ...form,
    parameters: [...form.parameters],
    spec: buildSpecPayload(),
    draft,
  }
}

async function handleSaveDraft() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写能力名')
    return
  }
  saving.value = true
  try {
    const payload = buildUpsertPayload(true)
    if (isEditMode.value && editingName.value) {
      await updateCapability(editingName.value, payload)
      ElMessage.success('已暂存')
    } else {
      await createCapability(payload)
      ElMessage.success('已暂存')
    }
    formDialogVisible.value = false
    await fetchCapabilities()
  } catch (err) {
    const msg = err as { response?: { data?: { message?: string } }; message?: string }
    ElMessage.error(msg.response?.data?.message || msg.message || '暂存失败')
  } finally {
    saving.value = false
  }
}

async function handleSave() {
  if (!form.name.trim() || !form.description.trim()) {
    ElMessage.warning('请填写能力名与描述')
    return
  }
  if (form.skillKind !== 'INTERACTIVE_FORM' && !(form.spec as SubAgentSpec).systemPrompt?.trim()) {
    ElMessage.warning('请填写子 Agent 系统提示词')
    return
  }
  if (form.skillKind === 'INTERACTIVE_FORM') {
    const err = validateInteractiveFormSpec(interactiveSpec.value)
    if (err) {
      ElMessage.warning(err)
      return
    }
  }
  saving.value = true
  try {
    const payload = buildUpsertPayload(false)
    if (isEditMode.value && editingName.value) {
      await updateCapability(editingName.value, payload)
      ElMessage.success(editingIsDraft.value ? '已发布' : '能力更新成功')
    } else {
      await createCapability(payload)
      ElMessage.success('能力创建成功')
    }
    formDialogVisible.value = false
    await fetchCapabilities()
  } catch (err) {
    const msg = err as { response?: { data?: { message?: string } }; message?: string }
    ElMessage.error(msg.response?.data?.message || msg.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleDelete(skill: CapabilityInfo) {
  try {
    await ElMessageBox.confirm(`确认删除能力 ${skill.name} 吗？`, '删除确认', { type: 'warning' })
    await deleteCapability(skill.name)
    ElMessage.success('能力已删除')
    await fetchCapabilities()
  } catch (err) {
    if ((err as Error).message !== 'cancel') {
      ElMessage.error((err as Error).message || '删除失败')
    }
  }
}

function axiosMessage(err: unknown): string {
  const e = err as { response?: { data?: { message?: string } }; message?: string }
  return e.response?.data?.message || e.message || '请求失败'
}

async function handleEnabledChange(skill: CapabilityInfo, enabled: boolean) {
  if (skill.draft && enabled) {
    ElMessage.warning('草稿请先「发布」后再启用')
    await fetchCapabilities()
    return
  }
  try {
    await toggleCapability(skill.name, enabled)
    ElMessage.success(`已${enabled ? '启用' : '禁用'} ${skill.name}`)
    await fetchCapabilities()
  } catch (err) {
    ElMessage.error(axiosMessage(err))
    await fetchCapabilities()
  }
}

async function handleVisibleChange(skill: CapabilityInfo, agentVisible: boolean) {
  try {
    const specBody: SubAgentSpec | Record<string, unknown> =
      skill.skillKind === 'INTERACTIVE_FORM'
        ? (normalizeInteractiveFormSpec(skill.spec ?? {}) as unknown as Record<string, unknown>)
        : ((skill.spec as SubAgentSpec) || {
            systemPrompt: '',
            toolWhitelist: [],
            llmProvider: '',
            llmModel: '',
            maxSteps: 8,
            useMultiAgentModel: false,
          })
    await updateCapability(skill.name, {
      name: skill.name,
      description: skill.description,
      parameters: skill.parameters || [],
      skillKind: skill.skillKind || 'SUB_AGENT',
      sideEffect: skill.sideEffect || 'WRITE',
      enabled: skill.enabled,
      agentVisible,
      spec: specBody,
      draft: Boolean(skill.draft),
    })
    ElMessage.success('配置已更新')
    await fetchCapabilities()
  } catch (err) {
    ElMessage.error((err as Error).message || '更新失败')
  }
}

function formatUiRequestPreview(ui: Record<string, unknown>) {
  try {
    return JSON.stringify(ui, null, 2)
  } catch {
    return String(ui)
  }
}

function uiRequestComponent(
  ui: Record<string, unknown> | null | undefined,
): string | undefined {
  const c = ui?.component
  return typeof c === 'string' ? c : undefined
}

/** 与后端 InteractiveForm 执行器抛出文案一致 */
function isPendingQuotaBlockError(msg: string | undefined): boolean {
  if (!msg) return false
  return msg.includes('未完成') && msg.includes('交互') && msg.includes('上限')
}

function formatPendingTime(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString()
}

function openPendingInteractionsDialog() {
  pendingDialogVisible.value = true
}

async function loadPendingInteractions() {
  pendingLoading.value = true
  try {
    const { data } = await getAdminTestPendingInteractions()
    pendingList.value = Array.isArray(data) ? data : []
  } catch {
    pendingList.value = []
    ElMessage.error('加载挂起交互列表失败')
  } finally {
    pendingLoading.value = false
  }
}

async function handleCancelOnePending(interactionId: string) {
  pendingRowDeleting.value = interactionId
  try {
    await cancelAdminTestPendingInteraction(interactionId)
    ElMessage.success('已取消该交互')
    await loadPendingInteractions()
  } catch (err) {
    ElMessage.error(axiosMessage(err))
  } finally {
    pendingRowDeleting.value = null
  }
}

async function handleCancelAllPending() {
  try {
    await ElMessageBox.confirm(
      '确认取消当前列表中的全部挂起交互？（不影响真实用户会话）',
      '全部取消',
      { type: 'warning' },
    )
  } catch {
    return
  }
  pendingCancelAllRunning.value = true
  try {
    const { data } = await cancelAllAdminTestPendingInteractions()
    const n = data?.cancelled ?? 0
    ElMessage.success(n > 0 ? `已取消 ${n} 条` : '没有待取消项')
    await loadPendingInteractions()
  } catch (err) {
    ElMessage.error(axiosMessage(err))
  } finally {
    pendingCancelAllRunning.value = false
  }
}

function openTest(skill: CapabilityInfo) {
  if (skill.draft) {
    ElMessage.warning('草稿能力不可测试，请先发布')
    return
  }
  testingSkill.value = skill
  testResult.value = null
  testResumeValuesJson.value = '{}'
  Object.keys(testArgs).forEach((k) => delete testArgs[k])
  for (const p of skill.parameters || []) {
    testArgs[p.name] = ''
  }
  testDialogVisible.value = true
}

async function handleTest() {
  if (!testingSkill.value) return
  testRunning.value = true
  testResult.value = null
  testResumeValuesJson.value = '{}'
  try {
    const args: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(testArgs)) {
      if (v !== '') args[k] = v
    }
    const { data } = await testCapability(testingSkill.value.name, args)
    testResult.value = data as never
  } catch (err) {
    testResult.value = {
      success: false,
      result: '',
      errorMessage: (err as Error).message || '执行失败',
      durationMs: 0,
    }
  } finally {
    testRunning.value = false
  }
}

/** 与 AgentDebug 中 DynamicInteraction 一致：action + values 调用 test/resume */
async function handleTestUiAction(action: string, values: Record<string, unknown>) {
  if (!testingSkill.value || !testResult.value?.interactionId) return
  testResumeRunning.value = true
  try {
    const { data } = await testCapabilityResume(testingSkill.value.name, {
      interactionId: testResult.value.interactionId,
      action,
      values: values ?? {},
    })
    testResult.value = data as never
    if (!data.interactionPending) {
      testResumeValuesJson.value = '{}'
    }
  } catch (err) {
    testResult.value = {
      success: false,
      result: '',
      errorMessage: (err as Error).message || '继续失败',
      durationMs: 0,
    }
  } finally {
    testResumeRunning.value = false
  }
}

async function handleTestResumeJsonSubmit() {
  if (!testingSkill.value || !testResult.value?.interactionId) return
  let values: Record<string, unknown> = {}
  try {
    const raw = testResumeValuesJson.value?.trim() || '{}'
    values = JSON.parse(raw) as Record<string, unknown>
  } catch {
    ElMessage.error('本批字段 JSON 格式无效')
    return
  }
  await handleTestUiAction('submit', values)
}

async function openMetrics(skill: CapabilityInfo) {
  metricsSkillName.value = skill.name
  metricsData.value = null
  metricsDialogVisible.value = true
  try {
    const { data } = await getCapabilityMetrics(skill.name, 7)
    metricsData.value = data
  } catch {
    ElMessage.error('加载指标失败')
  }
}

onMounted(async () => {
  await loadScanProjects()
  syncFiltersWithProjectContext()
  await loadToolOptions(filters.projectId ?? null)
  fetchCapabilities()
})

watch(
  () => projectStore.currentProjectId,
  () => {
    syncFiltersWithProjectContext()
    pagination.current = 1
    loadToolOptions(filters.projectId ?? null)
    fetchCapabilities()
  },
)
</script>

<style scoped lang="scss">
.header-actions {
  display: flex;
  gap: 8px;
}

.tool-filter {
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.tool-filter :deep(.el-form-item) {
  margin-bottom: 12px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
  padding-top: 8px;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
}

.expand-content {
  padding: 12px 20px;

  h4 {
    font-size: 13px;
    color: #64748b;
    margin-bottom: 8px;
  }
}

.meta-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
  font-size: 13px;
  color: var(--text-secondary);

  .span-2 {
    grid-column: span 2;
  }
}

.prompt-preview {
  margin: 4px 0 0;
  padding: 8px 12px;
  background: var(--bg-tertiary);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.6;
}

.param-hint {
  font-size: 12px;
  color: #64748b;
  margin-top: 2px;
}

.switch-group {
  display: flex;
  align-items: center;
  gap: 10px;
}

.switch-inline {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.operation-actions {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  white-space: nowrap;
}

.table-action-link {
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--el-color-primary);
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  line-height: 1;

  &:hover {
    color: var(--el-color-primary-light-3);
  }

  &:disabled {
    color: var(--el-text-color-disabled);
    cursor: not-allowed;
  }

  &.success {
    color: var(--el-color-success);

    &:hover:not(:disabled) {
      color: var(--el-color-success-light-3);
    }
  }

  &.danger {
    color: var(--el-color-danger);

    &:hover:not(:disabled) {
      color: var(--el-color-danger-light-3);
    }
  }
}

.test-result-area {
  margin-top: 12px;
}

.result-content {
  background: var(--bg-tertiary);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 4px;
  padding: 12px;
  font-size: 13px;
  margin-top: 12px;
  max-height: 260px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.result-duration {
  font-size: 12px;
  color: #64748b;
  margin-top: 8px;
}

.test-resume-actions {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.test-resume-btn {
  align-self: flex-start;
  margin-top: 8px;
}

.skill-test-interaction-hint {
  margin-bottom: 8px;
}

.skill-test-raw-json {
  margin-top: 12px;
}

.ui-payload {
  max-height: 200px;
}

.metric-cards {
  margin-bottom: 8px;
}

.capability-editor {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 18px;
  min-height: 620px;
}

.editor-rail {
  padding: 12px;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 8px;
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.035), rgba(15, 23, 42, 0.01));
}

.editor-step {
  width: 100%;
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 12px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  text-align: left;
  transition: all 0.18s ease;

  & + & {
    margin-top: 6px;
  }

  b {
    display: block;
    color: var(--text-primary);
    font-size: 14px;
    line-height: 1.3;
  }

  small {
    display: block;
    margin-top: 3px;
    color: #64748b;
    line-height: 1.35;
  }

  &:hover,
  &.active {
    border-color: rgba(99, 102, 241, 0.35);
    background: rgba(99, 102, 241, 0.08);
  }
}

.step-index {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border-radius: 50%;
  background: rgba(99, 102, 241, 0.12);
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 700;
}

.editor-main {
  min-width: 0;
}

.editor-summary {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px;
  margin-bottom: 14px;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 8px;
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.08), rgba(20, 184, 166, 0.06));

  h3 {
    margin: 2px 0 6px;
    font-size: 20px;
    line-height: 1.25;
  }

  p {
    margin: 0;
    color: #64748b;
    line-height: 1.6;
  }
}

.summary-eyebrow {
  margin: 0 !important;
  color: var(--el-color-primary) !important;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
}

.summary-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.editor-form {
  min-width: 0;
}

.editor-panel {
  min-height: 450px;
  padding: 18px;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 8px;
  background: var(--bg-secondary);
}

.panel-heading {
  margin-bottom: 18px;

  h4 {
    margin: 0;
    color: var(--text-primary);
    font-size: 17px;
    line-height: 1.3;
  }

  p {
    margin: 6px 0 0;
    color: #64748b;
    font-size: 13px;
    line-height: 1.6;
  }
}

.meta-readonly {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 1px dashed rgba(148, 163, 184, 0.35);
  border-radius: 8px;
  color: #64748b;

  code {
    color: var(--text-primary);
    word-break: break-all;
  }
}

.control-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.control-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 8px;
  background: var(--bg-tertiary);
  font-weight: 600;
}

.editor-alert {
  margin-top: 14px;
}

.publish-checklist {
  margin-top: 16px;
  display: grid;
  gap: 10px;

  div {
    display: flex;
    justify-content: space-between;
    gap: 16px;
    padding: 12px 14px;
    border-radius: 8px;
    background: rgba(148, 163, 184, 0.08);
  }

  span {
    color: #64748b;
  }
}

.editor-footer {
  display: inline-flex;
  gap: 8px;
  margin-right: auto;
}

:deep(.capability-editor-dialog .el-dialog__body) {
  padding-top: 10px;
}

:deep(.capability-editor-dialog .el-dialog__footer) {
  display: flex;
  align-items: center;
  gap: 8px;
}

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .pagination-wrap {
    border-top: 1px solid #ebeef5;
  }

  .expand-content h4 {
    color: #94a3b8;
  }

  .prompt-preview {
    border: 1px solid #ebeef5;
  }

  .param-hint,
  .result-duration {
    color: #94a3b8;
  }

  .result-content {
    border: 1px solid #ebeef5;
  }

  .editor-rail,
  .editor-summary,
  .editor-panel,
  .control-card {
    border-color: #e5e7eb;
  }

  .editor-panel {
    background: #fff;
  }

  .control-card {
    background: #f8fafc;
  }
}
</style>
