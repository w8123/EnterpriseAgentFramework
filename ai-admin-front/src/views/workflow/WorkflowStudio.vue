<template>
  <div class="studio-page">
    <header class="studio-header">
      <div class="header-left">
        <el-tooltip content="返回 Workflow 列表" placement="bottom">
          <button class="studio-back-btn" type="button" @click="router.push('/workflows')">
            <el-icon><ArrowLeft /></el-icon>
            <span>返回</span>
          </button>
        </el-tooltip>
        <div class="studio-title-wrap">
          <div class="studio-title-row">
            <span class="studio-eyebrow">Workflow 编排</span>
            <el-tooltip :content="studioDisplayName" placement="bottom" :show-after="400">
              <h1>{{ studioDisplayName }}</h1>
            </el-tooltip>
          </div>
          <div class="studio-meta-row">
            <el-tag size="small" effect="plain">{{ workflowVisibilityLabel }}</el-tag>
            <el-tag
              size="small"
              :type="graphLintErrors.length ? 'danger' : graphLintWarnings.length ? 'warning' : 'success'"
              effect="plain"
            >
              {{ graphLintErrors.length ? '阻断' : graphLintWarnings.length ? '提醒' : '健康' }}
            </el-tag>
            <el-tooltip
              v-if="studioKeySlug"
              :content="studioKeySlug"
              placement="bottom"
              :show-after="400"
            >
              <el-tag size="small" type="info" class="studio-slug-tag">{{ studioKeySlug }}</el-tag>
            </el-tooltip>
            <el-tag size="small" type="success">{{ studio?.projectCode || '全局' }}</el-tag>
          </div>
        </div>
      </div>
      <div class="header-right">
        <span class="save-state" :class="saveStateClass">
          <i></i>
          <span>{{ saveBadgeText }}</span>
        </span>
        <el-button :icon="VideoPlay" :loading="debugLoading" @click="handleDebug">调试</el-button>
        <el-button :icon="MagicStick" :loading="aiDraftLoading" :disabled="studioReadOnly" @click="openAiDraftDialog">AI 生成流程</el-button>
        <el-button :icon="Link" :disabled="studioReadOnly" @click="openApiQueryTemplateDialog">API 查询流程</el-button>
        <el-button :icon="Finished" :loading="validating || evalRunning" @click="openEvalDrawer">评测</el-button>
        <el-button :icon="CircleCheck" :loading="validating" @click="validateRuntime">校验</el-button>
        <el-dropdown trigger="click" @command="handleHeaderCommand">
          <el-button :icon="MoreFilled">更多</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="json" :icon="DocumentChecked">JSON 视图</el-dropdown-item>
              <el-dropdown-item command="sync-json" :icon="Refresh">同步 GraphSpec / Canvas</el-dropdown-item>
              <el-dropdown-item command="search" :icon="Search">搜索节点</el-dropdown-item>
              <el-dropdown-item command="fit" :icon="Aim">聚焦画布</el-dropdown-item>
              <el-dropdown-item command="layout" :icon="Rank">自动整理</el-dropdown-item>
              <el-dropdown-item command="node-debug" :icon="VideoPlay">节点测试</el-dropdown-item>
              <el-dropdown-item command="versions" :icon="Tickets">版本记录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button :loading="saving" :disabled="studioReadOnly" @click="saveStudio">保存</el-button>
        <el-button type="primary" :disabled="studioReadOnly" @click="publishWorkflow">发布</el-button>
      </div>
    </header>

    <div
      class="studio-body"
      :class="{ 'palette-open': paletteExpanded, 'property-collapsed': propertyPanelCollapsed }"
    >
      <aside class="palette">
        <div class="palette-rail">
          <el-tooltip content="添加节点" placement="right">
            <button class="rail-primary" type="button" :disabled="studioReadOnly" @click="nodeSearchOpen = true">
              <el-icon><Plus /></el-icon>
            </button>
          </el-tooltip>
          <button
            v-for="group in paletteGroups"
            :key="group.title"
            class="rail-item"
            :class="{ active: paletteExpanded && activePaletteGroup === group.title }"
            type="button"
            @click="openPaletteGroup(group.title)"
          >
            <span class="rail-icon">
              <el-icon><component :is="group.icon" /></el-icon>
            </span>
            <em>{{ group.title.slice(0, 2) }}</em>
          </button>
          <div class="rail-spacer"></div>
          <el-tooltip content="检查与变量" placement="right">
            <button class="rail-item" type="button" @click="inspectorExpanded = !inspectorExpanded">
              <el-icon><Operation /></el-icon>
            </button>
          </el-tooltip>
        </div>
        <div class="palette-content">
          <div class="panel-title-row">
            <div>
              <div class="palette-title">节点库</div>
              <div class="palette-subtitle">搜索或拖入节点，保存后写入图规范。</div>
            </div>
            <el-button text :icon="ArrowLeft" @click="paletteExpanded = false" />
          </div>
          <el-input
            v-model="nodeSearchKeyword"
            class="node-search"
            :prefix-icon="Search"
            clearable
            placeholder="搜索节点、用途或类型"
            size="small"
          />
          <div v-for="group in filteredPaletteGroups" :key="group.title" class="palette-group">
            <div class="palette-group-title">{{ group.title }}</div>
            <div
              v-for="item in group.items"
              :key="item.kind"
              class="palette-item"
              :class="{ disabled: studioReadOnly }"
              :style="{ borderLeftColor: kindColor(item.kind).border }"
              :draggable="!studioReadOnly"
              @dragstart="onDragStart($event, item.kind)"
              @dblclick="handleAddNode(item.kind)"
            >
              <div class="palette-item-head">
                <div class="palette-item-icon" :style="{ background: kindColor(item.kind).bg, color: kindColor(item.kind).border }">
                  <el-icon><component :is="item.icon" /></el-icon>
                </div>
                <div>
                  <div class="palette-item-title">{{ item.label }}</div>
                  <div class="palette-item-meta">{{ item.meta }}</div>
                </div>
              </div>
              <div class="palette-item-desc">{{ item.hint }}</div>
            </div>
          </div>
          <el-divider>画布规则</el-divider>
          <div class="palette-tips">
            - 连接线条件会进入图规范<br />
            - 双击节点可快速选中属性<br />
            - 保存后写入 Workflow GraphSpec
          </div>
        </div>
      </aside>

      <section class="canvas-wrap" @dragover.prevent @drop="onDrop">
        <VueFlow
          v-model:nodes="nodes"
          v-model:edges="edges"
          :fit-view-on-init="true"
          :default-viewport="{ zoom: 0.9 }"
          :min-zoom="0.2"
          :max-zoom="1.2"
          :default-edge-options="defaultEdgeOptions"
          :connection-line-options="connectionLineOptions"
          :elevate-edges-on-select="true"
          :delete-key-code="['Delete', 'Backspace']"
          :nodes-draggable="!studioReadOnly"
          :nodes-connectable="!studioReadOnly"
          class="studio-canvas"
          @connect="onConnect"
          @node-click="onNodeClick"
          @node-double-click="onNodeDoubleClick"
          @edge-click="onEdgeClick"
          @pane-click="clearSelection"
          @nodes-change="markCanvasDirty"
          @edges-change="markCanvasDirty"
        >
          <Background />
          <MiniMap />

          <template #node-start="nodeProps">
            <div class="studio-node start-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><VideoPlay /></el-icon></div>
              <div class="node-kicker">入口</div>
              <div class="node-label">{{ nodeProps.data.label }}</div>
              <div class="node-desc">用户输入 / 上下文入口</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.outputs, '输出') }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>

          <template #node-end="nodeProps">
            <div class="studio-node end-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <div class="node-icon"><el-icon><Finished /></el-icon></div>
              <div class="node-kicker">结束</div>
              <div class="node-label">{{ nodeProps.data.label }}</div>
              <div class="node-desc">响应输出 / 流程结束</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '输入') }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>

          <template #node-classifier="nodeProps">
            <div
              class="studio-node classifier-node"
              :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]"
            >
              <Handle type="target" :position="Position.Left" />
              <div class="node-icon">
                <el-icon><Switch /></el-icon>
              </div>
              <div class="node-head">
                <span class="node-kind">意图</span>
                <span class="node-state">分类</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="classifier-routes">
                <div
                  v-for="route in classifierRouteRows(nodeProps.data)"
                  :key="route.id"
                  class="classifier-route-row"
                  :class="{ 'is-default': route.isDefault }"
                >
                  <div class="classifier-route-copy">
                    <strong>{{ route.label }}</strong>
                    <span>{{ route.meta }}</span>
                  </div>
                  <Handle
                    type="source"
                    :id="route.handleId"
                    :position="Position.Right"
                    class="classifier-route-handle"
                  />
                </div>
              </div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.outputs, '分支') }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>

          <template #node-userInput="nodeProps">
            <div class="studio-node user-input-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><SetUp /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">用户输入</span>
                <span class="node-state">{{ nodeProps.data.userInputConfig?.outputAlias || nodeProps.data.outputAlias || 'params' }}</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ userInputFieldCount(nodeProps.data) }} 个输入字段，写入 params</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.outputs, '变量') }}</div>
              <div v-if="nodeProps.data.outputAlias" class="node-alias">输出别名：{{ nodeProps.data.outputAlias }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-interaction="nodeProps">
            <div class="studio-node interaction-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><SetUp /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">交互</span>
                <span class="node-state">{{ interactionTypeLabel(nodeProps.data.interactionConfig?.interactionType) }}</span>
              </div>
              <div class="node-label">{{ nodeProps.data.interactionConfig?.title || nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ interactionFieldCount(nodeProps.data) }} 个字段，写入 {{ nodeProps.data.interactionConfig?.outputAlias || nodeProps.data.outputAlias || 'interaction_output' }}</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.outputs, '变量') }}</div>
              <div v-if="nodeProps.data.outputAlias" class="node-alias">输出别名：{{ nodeProps.data.outputAlias }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-pageAction="nodeProps">
            <div class="studio-node page-action-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><Link /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">页面动作</span>
                <span class="node-state">{{ nodeProps.data.pageActionConfig?.confirm ? '需确认' : '直接触发' }}</span>
              </div>
              <div class="node-label">{{ nodeProps.data.pageActionConfig?.title || nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ nodeProps.data.pageActionConfig?.actionKey || '未配置 actionKey' }}</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '输入') }} · {{ portSummary(nodeProps.data.outputs, '输出') }}</div>
              <div v-if="nodeProps.data.outputAlias" class="node-alias">输出别名：{{ nodeProps.data.outputAlias }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-llm="nodeProps">
            <div class="studio-node llm-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><LlmModelIcon /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">大模型</span>
                <span class="node-state">推理</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">模型实例 · {{ nodeProps.data.llmConfig?.modelInstanceId || workflowMeta.defaultModelInstanceId || studio?.defaultModelInstanceId || '未选择' }}</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · {{ portSummary(nodeProps.data.outputs, '出') }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-skill="nodeProps">
            <div class="studio-node skill-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><Briefcase /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">能力</span>
                <span class="node-state">{{ visibilityLabel(nodeProps.data.toolConfig?.visibility || 'DRAFT') }}</span>
              </div>
              <div class="node-label">{{ nodeProps.data.toolConfig?.ref || '未选择能力' }}</div>
              <div class="node-desc">{{ nodeProps.data.description || '粗粒度业务能力' }}</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · {{ portSummary(nodeProps.data.outputs, '出') }}</div>
              <div v-if="nodeProps.data.outputAlias" class="node-alias">输出别名：{{ nodeProps.data.outputAlias }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-tool="nodeProps">
            <div class="studio-node tool-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed, 'needs-config': nodeProps.data.needsConfiguration }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><Tools /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">工具</span>
                <span class="node-state">{{ nodeProps.data.toolConfig?.projectCode || studio?.projectCode || '全局' }}</span>
              </div>
              <div class="node-label">{{ nodeProps.data.toolConfig?.ref || '未选择工具' }}</div>
              <div class="node-desc">{{ nodeProps.data.description || '原子工具调用' }}</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · {{ portSummary(nodeProps.data.outputs, '出') }}</div>
              <div v-if="nodeProps.data.outputAlias" class="node-alias">输出别名：{{ nodeProps.data.outputAlias }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-knowledge="nodeProps">
            <div class="studio-node knowledge-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><Coin /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">知识</span>
                <span class="node-state">检索</span>
              </div>
              <div class="node-label">{{ nodeProps.data.knowledgeConfig?.knowledgeBaseCodes?.join(', ') || '未选择知识库' }}</div>
              <div class="node-desc">查询：{{ nodeProps.data.knowledgeConfig?.query || '输入' }} / 返回数 {{ nodeProps.data.knowledgeConfig?.topK || 5 }}</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · {{ portSummary(nodeProps.data.outputs, '出') }}</div>
              <div v-if="nodeProps.data.outputAlias" class="node-alias">输出别名：{{ nodeProps.data.outputAlias }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-condition="nodeProps">
            <div class="studio-node condition-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <div class="node-icon"><el-icon><Switch /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">条件</span>
                <span class="node-state">路由</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ nodeProps.data.description || '按连线条件分流' }}</div>
              <div class="classifier-routes">
                <div
                  v-for="route in conditionRouteRows(nodeProps.data)"
                  :key="route.id"
                  class="classifier-route-row"
                  :class="{ 'is-default': route.isDefault }"
                >
                  <div class="classifier-route-copy">
                    <strong>{{ route.label }}</strong>
                    <span>{{ route.meta }}</span>
                  </div>
                  <Handle
                    type="source"
                    :id="route.handleId"
                    :position="Position.Right"
                    class="classifier-route-handle"
                  />
                </div>
              </div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.outputs, '分支') }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-variable="nodeProps">
            <div class="studio-node variable-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><SetUp /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">变量</span>
                <span class="node-state">状态</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ assignmentCount(nodeProps.data.assignments) }} 个变量赋值</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · {{ portSummary(nodeProps.data.outputs, '出') }}</div>
              <div v-if="nodeProps.data.outputAlias" class="node-alias">输出别名：{{ nodeProps.data.outputAlias }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-template="nodeProps">
            <div class="studio-node template-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><Document /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">模板</span>
                <span class="node-state">渲染</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ nodeProps.data.description || '模板转换与响应组装' }}</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · {{ portSummary(nodeProps.data.outputs, '出') }}</div>
              <div v-if="nodeProps.data.outputAlias" class="node-alias">输出别名：{{ nodeProps.data.outputAlias }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-parameter="nodeProps">
            <div class="studio-node parameter-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><MagicStick /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">参数</span>
                <span class="node-state">提取</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ nodeProps.data.parameterConfig?.fields?.length || 0 }} 个参数字段</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · {{ portSummary(nodeProps.data.outputs, '出') }}</div>
              <div v-if="nodeProps.data.outputAlias" class="node-alias">输出别名：{{ nodeProps.data.outputAlias }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-http="nodeProps">
            <div class="studio-node http-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><Link /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">接口</span>
                <span class="node-state">{{ nodeProps.data.httpConfig?.method || 'GET' }}</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ nodeProps.data.httpConfig?.url || '未配置 URL' }}</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · {{ portSummary(nodeProps.data.outputs, '出') }}</div>
              <div v-if="nodeProps.data.outputAlias" class="node-alias">输出别名：{{ nodeProps.data.outputAlias }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-answer="nodeProps">
            <div class="studio-node answer-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><Finished /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">回复</span>
                <span class="node-state">输出</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ nodeProps.data.answerConfig?.template || nodeProps.data.template || '未配置回复模板' }}</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · 写入 answer</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-code="nodeProps">
            <div class="studio-node code-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><Document /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">代码</span>
                <span class="node-state">表达式</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ Object.keys(nodeProps.data.codeConfig?.outputs || {}).length }} 个输出字段</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · {{ portSummary(nodeProps.data.outputs, '出') }}</div>
              <div v-if="nodeProps.data.outputAlias" class="node-alias">输出别名：{{ nodeProps.data.outputAlias }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-aggregate="nodeProps">
            <div class="studio-node aggregate-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><SetUp /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">聚合</span>
                <span class="node-state">{{ nodeProps.data.aggregateConfig?.mode || 'object' }}</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ nodeProps.data.aggregateConfig?.items?.length || 0 }} 个变量聚合项</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · {{ portSummary(nodeProps.data.outputs, '出') }}</div>
              <div v-if="nodeProps.data.outputAlias" class="node-alias">输出别名：{{ nodeProps.data.outputAlias }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-approval="nodeProps">
            <div class="studio-node approval-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <div class="node-icon"><el-icon><Finished /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">人工</span>
                <span class="node-state">确认</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ nodeProps.data.approvalConfig?.title || '人工确认' }}</div>
              <div class="classifier-routes">
                <div
                  v-for="route in approvalRouteRows()"
                  :key="route.id"
                  class="classifier-route-row"
                >
                  <div class="classifier-route-copy">
                    <strong>{{ route.label }}</strong>
                    <span>{{ route.meta }}</span>
                  </div>
                  <Handle
                    type="source"
                    :id="route.handleId"
                    :position="Position.Right"
                    class="classifier-route-handle"
                  />
                </div>
              </div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.outputs, '分支') }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-loop="nodeProps">
            <div class="studio-node loop-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <div class="node-icon"><el-icon><RefreshRight /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">循环</span>
                <span class="node-state">{{ nodeProps.data.loopConfig?.maxIterations || 3 }} 次</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">键：{{ nodeProps.data.loopConfig?.loopKey || 'loop' }}</div>
              <div class="classifier-routes">
                <div
                  v-for="route in loopRouteRows()"
                  :key="route.id"
                  class="classifier-route-row"
                >
                  <div class="classifier-route-copy">
                    <strong>{{ route.label }}</strong>
                    <span>{{ route.meta }}</span>
                  </div>
                  <Handle
                    type="source"
                    :id="route.handleId"
                    :position="Position.Right"
                    class="classifier-route-handle"
                  />
                </div>
              </div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.outputs, '分支') }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-knowledgeWrite="nodeProps">
            <div class="studio-node knowledge-write-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><Collection /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">知识</span>
                <span class="node-state">写入</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ nodeProps.data.knowledgeWriteConfig?.knowledgeBaseCode || '未选择知识库' }}</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · {{ portSummary(nodeProps.data.outputs, '出') }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-documentExtract="nodeProps">
            <div class="studio-node document-extract-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><Files /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">文档</span>
                <span class="node-state">{{ nodeProps.data.documentExtractConfig?.format || 'text' }}</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ nodeProps.data.documentExtractConfig?.fields?.length || 0 }} 个抽取字段</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · {{ portSummary(nodeProps.data.outputs, '出') }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-mcp="nodeProps">
            <div class="studio-node mcp-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><Connection /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">MCP</span>
                <span class="node-state">调用</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ nodeProps.data.mcpConfig?.toolName || '未配置工具' }}</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · {{ portSummary(nodeProps.data.outputs, '出') }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" type="button" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
        </VueFlow>

        <div class="canvas-operator" :class="{ collapsed: canvasOperatorCollapsed }">
          <el-tooltip :content="canvasOperatorCollapsed ? '展开画布工具' : '收起画布工具'" placement="top">
            <button
              class="canvas-operator-toggle"
              type="button"
              :aria-expanded="!canvasOperatorCollapsed"
              @click="canvasOperatorCollapsed = !canvasOperatorCollapsed"
            >
              <el-icon><Operation /></el-icon>
              <span>{{ canvasOperatorCollapsed ? '工具' : '收起' }}</span>
              <el-icon class="operator-caret">
                <ArrowUp v-if="canvasOperatorCollapsed" />
                <ArrowDown v-else />
              </el-icon>
            </button>
          </el-tooltip>
          <template v-if="!canvasOperatorCollapsed">
            <el-divider direction="vertical" />
            <el-tooltip content="搜索节点 Ctrl+F" placement="top">
              <el-button :icon="Search" circle @click="openCanvasSearch" />
            </el-tooltip>
            <el-divider direction="vertical" />
            <el-tooltip content="撤销 Ctrl+Z" placement="top">
              <el-button :icon="RefreshLeft" circle :disabled="studioReadOnly || !canUndo" @click="undoCanvas" />
            </el-tooltip>
            <el-tooltip content="重做 Ctrl+Y" placement="top">
              <el-button :icon="RefreshRight" circle :disabled="studioReadOnly || !canRedo" @click="redoCanvas" />
            </el-tooltip>
            <el-divider direction="vertical" />
            <el-tooltip content="复制 Ctrl+C" placement="top">
              <el-button :icon="CopyDocument" circle :disabled="!canCopySelectedNode" @click="copySelectedNode" />
            </el-tooltip>
            <el-tooltip content="粘贴 Ctrl+V" placement="top">
              <el-button :icon="Files" circle :disabled="studioReadOnly || !copiedNode" @click="pasteCopiedNode" />
            </el-tooltip>
            <el-tooltip content="折叠/展开节点" placement="top">
              <el-button :icon="Operation" circle :disabled="!selectedNode" @click="toggleSelectedNodeCollapsed" />
            </el-tooltip>
            <el-divider direction="vertical" />
            <el-tooltip content="自动整理 Ctrl+O" placement="top">
              <el-button :icon="Rank" circle @click="handleAutoLayout" />
            </el-tooltip>
            <el-tooltip content="聚焦画布 Ctrl+1" placement="top">
              <el-button :icon="Aim" circle @click="handleFitView" />
            </el-tooltip>
            <el-tooltip content="缩小" placement="top">
              <el-button :icon="ZoomOut" circle @click="handleZoomOut" />
            </el-tooltip>
            <el-tooltip content="放大" placement="top">
              <el-button :icon="ZoomIn" circle @click="handleZoomIn" />
            </el-tooltip>
          </template>
        </div>

        <div
          v-if="!aiEditMinimized"
          class="ai-edit-bar"
          :class="{ 'debug-drawer-open': debugOpen, 'is-generating': aiEditLoading }"
          @mousedown.stop
          @click.stop
        >
          <div class="ai-edit-input-row">
            <el-input
              v-model="aiEditInstruction"
              :disabled="studioReadOnly || aiEditLoading"
              clearable
              class="ai-edit-main-input"
              type="textarea"
              resize="none"
              :autosize="{ minRows: 2, maxRows: 4 }"
              placeholder="您想更改或创建什么内容？"
              @keydown.enter.exact.prevent="editAiDraft"
            />
            <div class="ai-edit-toolbar">
              <div class="ai-edit-toolbar-left">
                <el-tooltip content="当前会结合已选节点和画布上下文生成修改" placement="top">
                  <el-button text circle :icon="MagicStick" disabled />
                </el-tooltip>
              </div>
              <div class="ai-edit-toolbar-right">
                <el-tooltip content="最小化智能修改" placement="top">
                  <el-button
                    class="ai-edit-minimize"
                    circle
                    :icon="Minus"
                    aria-label="最小化智能修改"
                    @click="aiEditMinimized = true"
                  />
                </el-tooltip>
                <el-popover trigger="click" placement="top-end" width="360" popper-class="ai-edit-model-popover">
                  <template #reference>
                    <el-button
                      class="ai-edit-model-trigger"
                      :class="{ active: !!selectedAiEditModelLabel }"
                      :disabled="aiEditLoading"
                      circle
                      aria-label="选择语义修改模型"
                    >
                      <el-icon><LlmModelIcon /></el-icon>
                    </el-button>
                  </template>
                  <div class="ai-edit-model-panel">
                    <div class="ai-edit-model-title">选择修改模型</div>
                    <el-select
                      v-model="aiModelInstanceId"
                      filterable
                      clearable
                      placeholder="选择用于语义修改的模型"
                      :disabled="aiEditLoading"
                    >
                      <el-option
                        v-for="item in aiDraftModelOptions"
                        :key="item.id"
                        :label="modelOptionLabel(item)"
                        :value="item.id"
                      />
                    </el-select>
                    <div class="ai-edit-model-hint">{{ selectedAiEditModelLabel || '未选择模型时会使用 Workflow 默认模型' }}</div>
                  </div>
                </el-popover>
                <el-tooltip content="生成修改" placement="top">
                  <el-button
                    class="ai-edit-send"
                    type="primary"
                    circle
                    :icon="SendIcon"
                    :loading="aiEditLoading"
                    :disabled="!aiEditInstruction.trim()"
                    aria-label="生成修改"
                    @click="editAiDraft"
                  />
                </el-tooltip>
              </div>
            </div>
          </div>
          <div v-if="aiEditPreview" class="ai-edit-preview">
            <div class="ai-edit-preview-head">
              <strong>{{ aiEditPreview.summary || 'AI 修改预览' }}</strong>
              <span>{{ aiEditPreview.provider }} · {{ aiEditPreview.operations.length }} 项变更</span>
            </div>
            <div v-if="aiEditPreview.validationErrors?.length" class="ai-edit-alert error">
              <span v-for="item in aiEditPreview.validationErrors" :key="item">{{ item }}</span>
            </div>
            <div v-if="aiEditPreview.warnings?.length" class="ai-edit-alert warning">
              <span v-for="item in aiEditPreview.warnings" :key="item">{{ item }}</span>
            </div>
            <div class="ai-edit-operation-grid">
              <div
                v-for="group in aiEditOperationGroups"
                :key="group.type"
                class="ai-edit-operation-group"
              >
                <strong>{{ group.label }}</strong>
                <span v-for="item in group.items" :key="operationKey(item)">
                  {{ operationTarget(item) }}{{ item.reason ? `：${item.reason}` : '' }}
                </span>
              </div>
            </div>
            <div class="ai-edit-actions">
              <el-button text @click="clearAiEditPreview">取消</el-button>
              <el-button
                type="primary"
                :disabled="!!aiEditPreview.validationErrors?.length"
                @click="applyAiEditPreview"
              >
                应用到草稿
              </el-button>
            </div>
          </div>
        </div>

        <div v-if="canvasSearchOpen" class="canvas-search-panel">
          <el-input
            ref="canvasSearchInputRef"
            v-model="canvasSearchKeyword"
            :prefix-icon="Search"
            clearable
            placeholder="搜索节点名称、类型、描述或引用"
            @keyup.enter="focusNextCanvasSearch"
            @keyup.esc="closeCanvasSearch"
          />
          <span class="canvas-search-count">
            {{ canvasSearchMatches.length ? `${canvasSearchIndex + 1}/${canvasSearchMatches.length}` : canvasSearchKeyword ? '无结果' : '搜索画布' }}
          </span>
          <el-button text :icon="ArrowUp" :disabled="!canvasSearchMatches.length" @click="focusPrevCanvasSearch" />
          <el-button text :icon="ArrowDown" :disabled="!canvasSearchMatches.length" @click="focusNextCanvasSearch" />
          <el-button text :icon="Close" @click="closeCanvasSearch" />
        </div>

        <div v-if="inspectorExpanded" class="workflow-inspector">
          <div class="inspector-section">
            <div class="inspector-head">
              <strong>流程检查</strong>
              <el-tag size="small" :type="graphLintErrors.length ? 'danger' : graphLintWarnings.length ? 'warning' : 'success'">
                {{ graphLintErrors.length ? graphLintErrors.length + ' 个阻断' : graphLintWarnings.length ? graphLintWarnings.length + ' 个提醒' : '健康' }}
              </el-tag>
            </div>
            <div v-if="graphLintItems.length" class="inspector-list">
              <button
                v-for="item in graphLintItems.slice(0, 5)"
                :key="item.level + '-' + (item.nodeId || item.edgeId || item.message)"
                class="inspector-item"
                :class="item.level"
                type="button"
                @click="focusLintItem(item)"
              >
                <span>{{ item.level === 'error' ? '阻断' : '提醒' }}</span>
                <em>{{ item.message }}</em>
              </button>
            </div>
            <div v-else class="inspector-empty">当前画布结构可发布，继续补充业务节点即可。</div>
          </div>
          <div class="inspector-section">
            <div class="inspector-head">
              <strong>变量</strong>
              <el-tag size="small">{{ graphVariables.length }}</el-tag>
            </div>
            <div class="variable-chips">
              <button
                v-for="item in graphVariables.slice(0, 12)"
                :key="item.name"
                class="variable-chip"
                type="button"
                @click="selectedNodeId = item.nodeId"
              >
                <span>{{ item.name }}</span>
                <em>{{ item.source }}</em>
              </button>
              <span v-if="!graphVariables.length" class="inspector-empty">暂无节点输出变量</span>
            </div>
          </div>
        </div>
        <div class="canvas-statusbar">
          <div class="status-left">
            <button
              class="status-pill"
              :class="{ danger: graphLintErrors.length, warning: !graphLintErrors.length && graphLintWarnings.length }"
              type="button"
              @click="inspectorExpanded = !inspectorExpanded"
            >
              <span class="status-dot"></span>
              <strong>{{ graphLintErrors.length ? '阻断' : graphLintWarnings.length ? '提醒' : '健康' }}</strong>
              <em>{{ graphLintErrors.length || graphLintWarnings.length || '正常' }}</em>
            </button>
            <button class="status-pill" type="button" @click="inspectorExpanded = !inspectorExpanded">
              <strong>变量</strong>
              <em>{{ graphVariables.length }}</em>
            </button>
            <button v-if="aiEditMinimized" class="status-pill smart-edit-pill" type="button" @click="aiEditMinimized = false">
              <el-icon><MagicStick /></el-icon>
              <strong>智能修改</strong>
            </button>
          </div>
        </div>
      </section>

      <aside class="property-panel">
        <button class="property-toggle" type="button" @click="propertyPanelCollapsed = !propertyPanelCollapsed">
          <el-icon><ArrowRight v-if="!propertyPanelCollapsed" /><ArrowLeft v-else /></el-icon>
        </button>
        <div v-if="propertyPanelCollapsed" class="property-compact">
          <strong>{{ selectedNode?.data.label || edgeDisplayLabel(selectedEdge?.condition || selectedEdge?.label, selectedEdge) || '画布摘要' }}</strong>
          <span>{{ selectedNode ? nodeKindLabel(selectedNode.data.kind) : selectedEdge ? '连线' : `${canvasStats.nodes} 节点` }}</span>
        </div>
        <div v-else class="property-content">
        <el-alert
          v-if="studioReadOnly"
          class="readonly-alert"
          title="代码托管 Workflow 只读展示；请在业务代码中修改后重启同步。"
          type="warning"
          :closable="false"
        />
        <div v-if="selectedEdge">
            <el-divider>连线条件</el-divider>
            <el-form label-width="100px" size="small">
              <el-form-item label="来源">
                <el-input :model-value="selectedEdge.source" disabled />
              </el-form-item>
              <el-form-item label="目标">
                <el-input :model-value="selectedEdge.target" disabled />
              </el-form-item>
              <el-form-item label="条件">
                <el-select
                  v-model="selectedEdge.condition"
                  :disabled="studioReadOnly"
                  filterable
                  allow-create
                  default-first-option
                  placeholder="选择或输入条件"
                  style="width: 100%"
                  @change="syncSelectedEdgeLabel"
                >
                  <el-option
                    v-for="item in selectedEdgeConditionOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
              <div v-if="selectedEdgeRouteOptions.length" class="route-quick-picks">
                <el-button
                  v-for="item in selectedEdgeRouteOptions"
                  :key="item.value"
                  size="small"
                  text
                  type="primary"
                  :disabled="studioReadOnly"
                  @click="applySelectedEdgeCondition(item.value)"
                >
                  {{ item.label }}
                </el-button>
              </div>
              <div class="condition-help">
                支持：默认、成功、失败、为空、非空、包含、等于；条件节点出边可使用“路由: 分组ID”。
              </div>
            </el-form>
          </div>
          <div v-else-if="!selectedNode">
            <div class="property-empty-state">
              <div class="empty-state-icon">
                <el-icon><Aim /></el-icon>
              </div>
              <strong>选择画布中的节点</strong>
              <span>在这里编辑配置、调试节点，或查看连线条件。</span>
            </div>
            <el-divider>Workflow 元数据</el-divider>
            <el-form label-width="100px" size="small" class="property-form">
              <el-form-item label="名称">
                <el-input v-model="workflowMeta.name" @change="markCanvasDirty" />
              </el-form-item>
              <el-form-item label="keySlug">
                <el-input v-model="workflowMeta.keySlug" placeholder="默认使用 workflowId" @change="markCanvasDirty" />
              </el-form-item>
              <el-form-item label="类型">
                <el-input v-model="workflowMeta.workflowType" @change="markCanvasDirty" />
              </el-form-item>
              <el-form-item label="说明">
                <el-input v-model="workflowMeta.description" type="textarea" :rows="4" @change="markCanvasDirty" />
              </el-form-item>
              <el-form-item label="默认模型">
                <el-select v-model="workflowMeta.defaultModelInstanceId" filterable clearable placeholder="选择默认 LLM 模型" style="width: 100%">
                  <el-option
                    v-for="item in aiDraftModelOptions"
                    :key="item.id"
                    :label="modelOptionLabel(item)"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>
            </el-form>
          </div>

          <div v-else class="node-property-overview">
            <div class="node-property-head">
              <div class="node-property-icon" :style="{ background: kindColor(selectedNode!.data.kind).bg, color: kindColor(selectedNode!.data.kind).border }">
                <el-icon><component :is="nodeIcon(selectedNode!.data.kind) || Operation" /></el-icon>
              </div>
              <div class="node-property-title">
                <div>
                  <strong>{{ selectedNode!.data.label || selectedNode!.id }}</strong>
                  <el-tag size="small" effect="plain">{{ nodeKindLabel(selectedNode!.data.kind) }}</el-tag>
                </div>
                <span>{{ selectedNode!.data.description || '配置该节点的运行行为、变量、输出与测试。' }}</span>
              </div>
            </div>

            <div class="node-property-actions">
              <el-button size="small" type="primary" plain :icon="Operation" @click="openPropertyDetail('base')">基础</el-button>
              <el-button size="small" type="primary" plain :icon="SetUp" @click="openPropertyDetail('node')">配置</el-button>
              <el-button
                size="small"
                plain
                :icon="Delete"
                type="danger"
                @click="deleteSelectedNode"
                :disabled="selectedNode!.data.kind === 'start' || selectedNode!.data.kind === 'end'"
              >
                删除
              </el-button>
            </div>

            <button class="property-section-card" type="button" @click="openPropertyDetail('base')">
              <span class="section-card-icon"><el-icon><Operation /></el-icon></span>
              <span class="section-card-main">
                <strong>基础</strong>
                <em>节点名称、描述、状态等</em>
              </span>
              <span class="section-card-meta">
                <el-tag size="small" :type="selectedNode!.data.source === 'SDK' ? 'warning' : 'success'">
                  {{ sourceLabel(selectedNode!.data.source) }}
                </el-tag>
                <el-icon><ArrowRight /></el-icon>
              </span>
            </button>

            <button class="property-section-card" type="button" @click="openPropertyDetail('node')">
              <span class="section-card-icon"><el-icon><SetUp /></el-icon></span>
              <span class="section-card-main">
                <strong>节点配置</strong>
                <em>{{ nodeKindLabel(selectedNode!.data.kind) }} 专属参数、变量与输出</em>
              </span>
              <span class="section-card-meta">
                <el-tag size="small">{{ portSummary(selectedNode!.data.inputs, '输入') }}</el-tag>
                <el-tag size="small">{{ portSummary(selectedNode!.data.outputs, '输出') }}</el-tag>
                <el-icon><ArrowRight /></el-icon>
              </span>
            </button>

            <button
              v-if="selectedNode!.data.kind !== 'start' && selectedNode!.data.kind !== 'end'"
              class="property-section-card"
              type="button"
              @click="openPropertyDetail('debug')"
            >
              <span class="section-card-icon"><el-icon><VideoPlay /></el-icon></span>
              <span class="section-card-main">
                <strong>节点测试</strong>
                <em>测试消息、模拟状态与输出状态</em>
              </span>
              <span class="section-card-meta">
                <el-tag
                  v-if="nodeDebugResult?.nodeId === selectedNode!.id"
                  size="small"
                  :type="nodeDebugResult.success ? 'success' : 'danger'"
                >
                  {{ nodeDebugResult.success ? '通过' : '失败' }}
                </el-tag>
                <el-icon><ArrowRight /></el-icon>
              </span>
            </button>

            <button class="property-section-card" type="button" @click="openPropertyDetail('trace')">
              <span class="section-card-icon"><el-icon><Document /></el-icon></span>
              <span class="section-card-main">
                <strong>运行记录</strong>
                <em>{{ selectedNodeTrace ? '查看上次运行输入、输出与状态' : '暂无节点运行记录' }}</em>
              </span>
              <span class="section-card-meta">
                <el-tag v-if="selectedNodeTrace" size="small" :type="nodeTraceTagType(selectedNodeTrace.status)">
                  {{ nodeTraceStatusText(selectedNodeTrace.status) }}
                </el-tag>
                <el-icon><ArrowRight /></el-icon>
              </span>
            </button>
          </div>
        </div>
      </aside>
    </div>

    <el-dialog
      v-model="propertyDetailOpen"
      class="node-property-dialog"
      width="920px"
      align-center
      destroy-on-close
    >
      <template #header>
        <div class="node-dialog-header">
          <div class="node-dialog-icon" :style="{ '--node-accent': selectedNode ? kindColor(selectedNode.data.kind).border : '#4f46e5' }">
            <el-icon v-if="selectedNode"><component :is="nodeIcon(selectedNode.data.kind) || Operation" /></el-icon>
            <el-icon v-else><SetUp /></el-icon>
          </div>
          <div class="node-dialog-title">
            <strong>{{ selectedNode?.data.label || selectedNode?.id || '节点' }}</strong>
            <span>{{ propertyDetailSectionLabel }} · {{ selectedNode ? nodeKindLabel(selectedNode.data.kind) : '节点配置' }}</span>
          </div>
          <div v-if="selectedNode" class="node-dialog-tags">
            <el-tag size="small" :type="selectedNode.data.source === 'SDK' ? 'warning' : 'success'">
              {{ sourceLabel(selectedNode.data.source) }}
            </el-tag>
            <el-tag size="small" effect="plain">{{ categoryLabel(selectedNode.data.category) }}</el-tag>
          </div>
        </div>
      </template>
      <template v-if="selectedNode">
        <el-form v-if="propertyDetailSection === 'base'" class="node-base-form" label-width="88px" size="small">
          <section class="node-detail-card">
            <div class="node-detail-card-head">
              <div>
                <strong>身份信息</strong>
                <span>用于画布展示、运行日志和上游节点引用</span>
              </div>
              <el-tag size="small" effect="plain">{{ nodeKindLabel(selectedNode.data.kind) }}</el-tag>
            </div>
            <el-form-item label="节点 ID">
              <el-input v-model="selectedNode.id" disabled />
            </el-form-item>
            <el-form-item label="名称">
              <el-input v-model="selectedNode.data.label" placeholder="给节点起一个清晰的业务名称" @change="markCanvasDirty" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="selectedNode.data.description" type="textarea" :rows="4" placeholder="说明这个节点在流程中的职责" @change="markCanvasDirty" />
            </el-form-item>
            <el-form-item v-if="selectedNode.data.kind !== 'start' && selectedNode.data.kind !== 'end'" label="输出别名">
              <el-input v-model="selectedNode.data.outputAlias" placeholder="如 customer / params / context" @change="markCanvasDirty" />
            </el-form-item>
          </section>

          <section class="node-detail-card compact-card">
            <div class="node-detail-card-head">
              <div>
                <strong>节点契约</strong>
                <span>展示节点来源、类别和输入输出端口</span>
              </div>
            </div>
            <div class="node-contract-grid">
              <div class="contract-chip">
                <span>来源</span>
                <el-tag size="small" :type="selectedNode.data.source === 'SDK' ? 'warning' : 'success'">
                  {{ sourceLabel(selectedNode.data.source) }}
                </el-tag>
              </div>
              <div class="contract-chip">
                <span>类别</span>
                <el-tag size="small">{{ categoryLabel(selectedNode.data.category) }}</el-tag>
              </div>
              <div class="contract-chip">
                <span>输入</span>
                <el-tag size="small" effect="plain">{{ portSummary(selectedNode.data.inputs, '输入') }}</el-tag>
              </div>
              <div class="contract-chip">
                <span>输出</span>
                <el-tag size="small" effect="plain">{{ portSummary(selectedNode.data.outputs, '输出') }}</el-tag>
              </div>
            </div>
          </section>
        </el-form>

        <NodeConfigPanel
          v-else-if="propertyDetailSection === 'node'"
          :data="selectedNode.data"
          :model-options="modelOptions"
          :knowledge-options="knowledgeOptions"
          :tool-options="availableTools"
          :composition-options="availableCompositions"
          :variable-options="variableOptions"
          :credential-options="credentialOptions"
          :param-source-hints="paramSourceHints"
          :project-id="studio?.projectId || null"
          :project-code="studio?.projectCode || null"
          @credential-created="handleCredentialCreated"
          @create-call-node="handleCreateInteractionCallNode"
        />

        <el-form v-else-if="propertyDetailSection === 'debug'" label-width="100px" size="small">
          <div class="node-debug-box">
            <div class="node-debug-head">
              <strong>节点测试</strong>
              <el-tag v-if="nodeDebugResult?.nodeId === selectedNode.id" size="small" :type="nodeDebugResult.success ? 'success' : 'danger'">
                {{ nodeDebugResult.success ? '通过' : '失败' }}
              </el-tag>
            </div>
            <el-form-item label="测试消息">
              <el-input v-model="nodeDebugMessage" />
            </el-form-item>
            <el-form-item label="模拟状态">
              <el-input v-model="nodeDebugStateText" type="textarea" :rows="5" />
            </el-form-item>
            <el-button type="primary" size="small" :loading="nodeDebugLoading" @click="handleRunNodeDebug">
              测试此节点
            </el-button>
            <div v-if="nodeDebugResult?.nodeId === selectedNode.id" class="node-trace-block">
              <span>输出状态</span>
              <pre>{{ formatDebugResult(nodeDebugResult.outputState || nodeDebugResult.nodeOutput || {}) }}</pre>
            </div>
          </div>
        </el-form>

        <div v-else>
          <div v-if="selectedNodeTrace" class="node-trace-panel">
            <div class="node-trace-head">
              <strong>上次运行</strong>
              <el-tag size="small" :type="nodeTraceTagType(selectedNodeTrace.status)">
                {{ nodeTraceStatusText(selectedNodeTrace.status) }}
              </el-tag>
            </div>
            <div class="node-trace-meta">
              <span>{{ selectedNodeTrace.nodeId }}</span>
              <span>{{ formatElapsed(selectedNodeTrace.elapsedMs) }}</span>
              <span v-if="selectedNodeTrace.errorCode">{{ selectedNodeTrace.errorCode }}</span>
              <span v-if="selectedNodeTrace.route">route: {{ selectedNodeTrace.route }}</span>
            </div>
            <div v-if="selectedNodeTrace.input" class="node-trace-block">
              <span>输入</span>
              <pre>{{ selectedNodeTrace.input }}</pre>
            </div>
            <div v-if="selectedNodeTrace.output" class="node-trace-block">
              <span>输出</span>
              <pre>{{ selectedNodeTrace.output }}</pre>
            </div>
          </div>
          <el-empty v-else description="暂无节点运行记录" />
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="nodeSearchOpen" title="添加节点" width="620px" class="node-search-dialog">
      <el-input
        v-model="nodeSearchKeyword"
        :prefix-icon="Search"
        clearable
        autofocus
        placeholder="输入大模型、接口、变量、知识库、条件..."
      />
      <div class="node-search-grid">
        <button
          v-for="item in flatFilteredPalette"
          :key="item.kind"
          class="node-search-card"
          type="button"
          :style="{ borderColor: kindColor(item.kind).border }"
          @click="addNodeFromSearch(item.kind)"
        >
          <span :style="{ background: kindColor(item.kind).bg, color: kindColor(item.kind).border }">
            <el-icon><component :is="item.icon" /></el-icon>
          </span>
          <strong>{{ item.label }}</strong>
          <em>{{ item.hint }}</em>
        </button>
      </div>
    </el-dialog>

    <el-drawer
      v-model="debugOpen"
      class="studio-debug-drawer"
      modal-class="studio-debug-drawer-overlay"
      size="min(960px, 58vw)"
      direction="rtl"
      :modal="false"
      :modal-penetrable="true"
      :lock-scroll="false"
    >
      <template #header>
        <div class="debug-drawer-head">
          <strong>工作流调试台（当前草稿）</strong>
          <el-popover
            placement="bottom-end"
            trigger="click"
            width="560"
            popper-class="debug-advanced-popover"
          >
            <template #reference>
              <el-button class="debug-advanced-trigger" plain :icon="Operation">
                高级调试
              </el-button>
            </template>
            <el-collapse class="debug-advanced-collapse debug-advanced-popover-collapse">
              <el-collapse-item name="variables">
                <template #title>
                  <span class="debug-collapse-title">高级调试：变量与状态快照</span>
                </template>
                <div class="result-section">
                  <strong>变量映射合同：</strong>
                  <pre>{{ JSON.stringify(variablePreview, null, 2) }}</pre>
                </div>
                <div v-if="debugRunResult" class="result-section">
                  <strong>最终状态快照：</strong>
                  <pre>{{ stringifyDebugPayload(debugRunResult.finalState) }}</pre>
                </div>
              </el-collapse-item>

              <el-collapse-item name="trace">
                <template #title>
                  <span class="debug-collapse-title">高级调试：Trace 回放与生产运行</span>
                </template>
                <div class="trace-replay-panel">
                  <div class="trace-replay-row debug-production-row">
                    <div>
                      <strong>发布版本验证</strong>
                      <span>使用已发布 ACTIVE 版本图规范运行，用于和当前草稿调试结果对照。</span>
                    </div>
                    <el-button :loading="debugLoading" @click="handleRunPublishedDebug">
                      发布版本验证
                    </el-button>
                  </div>
                  <div class="trace-replay-row">
                    <el-input
                      v-model="replayTraceInput"
                      clearable
                      placeholder="输入 traceId 回放到画布"
                      @keyup.enter="handleLoadTraceReplay()"
                    />
                    <el-button type="primary" plain :loading="traceReplayLoading" @click="handleLoadTraceReplay()">
                      回放
                    </el-button>
                    <el-button :disabled="!currentTraceId" @click="clearTraceReplay">
                      清除
                    </el-button>
                  </div>
                  <div class="trace-replay-row">
                    <el-select
                      v-model="selectedRecentTraceId"
                      filterable
                      clearable
                      :placeholder="studioRecentRunsPlaceholder"
                      style="width: 100%"
                      @change="handleRecentTraceChange"
                    >
                      <el-option
                        v-for="run in studioRecentRuns"
                        :key="run.traceId"
                        :label="recentRunLabel(run)"
                        :value="run.traceId"
                      />
                    </el-select>
                    <el-button :loading="recentRunsLoading" @click="loadRecentStudioRuns">
                      刷新
                    </el-button>
                  </div>
                </div>
                <div v-if="currentTraceId" class="result-section trace-detail-section">
                  <div class="debug-section-head">
                    <div>
                      <strong>链路详情</strong>
                      <span>{{ currentTraceId }}</span>
                    </div>
                    <el-button
                      type="primary"
                      size="small"
                      @click="router.push('/runops/' + currentTraceId)"
                    >查看运行详情</el-button>
                  </div>
                  <div v-if="workflowReplaySummary.length" class="runtime-insights workflow-replay-summary">
                    <div v-for="item in workflowReplaySummary" :key="item.label" class="runtime-insight">
                      <span>{{ item.label }}</span>
                      <strong>{{ item.value }}</strong>
                    </div>
                  </div>
                  <div v-if="nodeTraceList.length" class="node-run-summary">
                    <button
                      v-for="item in nodeTraceList"
                      :key="item.nodeId"
                      class="node-run-item"
                      :class="item.status"
                      type="button"
                      @click="openNodeTrace(item.nodeId)"
                    >
                      <span>{{ item.nodeId }}</span>
                      <em>{{ formatElapsed(item.elapsedMs) }}</em>
                    </button>
                  </div>
                  <div class="trace-toolbar">
                    <el-select
                      v-model="traceToolPick"
                      multiple
                      filterable
                      placeholder="选中若干工具作为能力草稿序列（留空表示全量）"
                      style="width: 100%"
                    >
                      <el-option
                        v-for="name in traceToolNames"
                        :key="name"
                        :label="name"
                        :value="name"
                      />
                    </el-select>
                    <el-button
                      type="warning"
                      size="small"
                      :icon="Collection"
                      :disabled="!traceToolNames.length"
                      @click="handleExtractCompositionDraft"
                      :loading="extracting"
                    >抽取为能力草稿</el-button>
                  </div>
                  <TraceTimeline :nodes="traceNodes" />
                </div>
                <el-empty v-else description="需要排查生产调用时，再输入 traceId 回放" />
              </el-collapse-item>
            </el-collapse>
          </el-popover>
        </div>
      </template>
      <div class="debug-body" :class="debugSessionVisualClass(debugSession?.status)">
        <div class="debug-session-grid">
          <section class="debug-chat-panel" :class="debugSessionVisualClass(debugSession?.status)">
            <div class="debug-chat-messages">
              <template v-if="debugSessionMessages.length">
                <article
                  v-for="message in debugSessionMessages"
                  :key="message.id"
                  class="debug-message"
                  :class="`is-${message.role}`"
                >
                  <div class="debug-message-role">{{ debugMessageRole(message.role) }}</div>
                  <div class="debug-message-content">
                    <p>{{ message.content || '-' }}</p>
                    <InteractionRenderer
                      v-if="message.uiRequest && shouldRenderDebugMessageUi(message)"
                      :ui-request="message.uiRequest"
                      @submit="handleDebugUiSubmit"
                      @cancel="handleCancelDebugSession"
                    />
                  </div>
                </article>
              </template>
              <el-empty v-else description="输入问题后开始一次可恢复调试会话" />
            </div>
            <section class="debug-input-card debug-unified-input debug-chat-composer">
              <InteractionRenderer
                v-if="debugCurrentUiRequest"
                :ui-request="debugCurrentUiRequest"
                @submit="handleDebugUiSubmit"
                @cancel="handleCancelDebugSession"
              />
              <template v-else>
                <div v-if="debugInputFields.length" class="debug-field-grid">
                  <el-form-item
                    v-for="field in debugInputFields"
                    :key="field.name"
                    :label="debugFieldLabel(field)"
                  >
                    <el-switch
                      v-if="field.type === 'boolean'"
                      v-model="debugInputParams[field.name]"
                    />
                    <el-input-number
                      v-else-if="field.type === 'number' || field.type === 'integer'"
                      v-model="debugInputParams[field.name]"
                      style="width: 100%"
                    />
                    <el-input
                      v-else-if="field.type === 'object' || field.type === 'array'"
                      v-model="debugInputParams[field.name]"
                      type="textarea"
                      :rows="3"
                      :placeholder="field.description || '输入 JSON 或文本'"
                    />
                    <el-input
                      v-else
                      v-model="debugInputParams[field.name]"
                      :placeholder="field.description || '输入字段值'"
                    />
                  </el-form-item>
                </div>
                <el-input
                  v-else
                  v-model="debugMessage"
                  type="textarea"
                  :rows="3"
                  resize="none"
                  placeholder="输入测试消息..."
                />
                <div class="debug-actions">
                  <el-tooltip content="运行当前草稿" placement="top">
                    <el-button
                      type="primary"
                      circle
                      :icon="SendIcon"
                      :loading="debugLoading"
                      aria-label="运行当前草稿"
                      @click="handleRunDraftDebug"
                    />
                  </el-tooltip>
                </div>
              </template>
            </section>
          </section>

          <section class="debug-steps-panel">
            <div class="debug-section-head">
              <div>
                <strong>节点轨迹</strong>
                <span>{{ debugSessionSteps.length }} 个节点事件</span>
              </div>
              <el-button size="small" text :disabled="!debugSession" @click="clearDebugSessionView">
                清空视图
              </el-button>
            </div>
            <div class="workflow-debug-steps">
              <article
                v-for="(step, index) in debugSessionSteps"
                :key="step.nodeId + ':' + index"
                class="workflow-debug-step-card"
                :class="[debugStepStatusClass(step.status), { selected: selectedDebugStepIndex === index }]"
              >
                <button
                  class="workflow-debug-step"
                  :class="debugStepStatusClass(step.status)"
                  type="button"
                  @click="selectDebugStep(index)"
                >
                  <span class="step-marker">
                    <span
                      class="step-running-icon"
                      :class="{ active: isDebugStepRunning(step) }"
                      aria-label="运行中"
                    ></span>
                    <span class="step-index">{{ index + 1 }}</span>
                  </span>
                  <span class="step-main">
                    <strong>{{ step.nodeName || step.nodeId }}</strong>
                    <em>{{ step.nodeType || step.eventType || '-' }}</em>
                  </span>
                  <span class="step-route">
                    <template v-if="step.route">路由 {{ step.route }}</template>
                    <template v-else>{{ step.eventType || '节点' }}</template>
                    <small v-if="step.nextNodeId">→ {{ step.nextNodeId }}</small>
                  </span>
                  <span class="step-time">{{ formatElapsed(step.elapsedMs) }}</span>
                </button>
                <div v-if="selectedDebugStepIndex === index" class="debug-step-inline">
                  <el-tabs>
                    <el-tab-pane label="输入">
                      <pre>{{ stringifyDebugPayload(step.input) }}</pre>
                    </el-tab-pane>
                    <el-tab-pane label="输出">
                      <div v-if="step.uiRequest || debugWaitingOutput(step)" class="debug-waiting-card">
                        <strong>{{ step.status === 'WAITING' ? '等待用户补充' : '输出卡片' }}</strong>
                        <span>{{ step.uiRequest?.message || debugWaitingOutput(step)?.message || step.uiRequest?.title || '已生成交互 UI' }}</span>
                      </div>
                      <pre>{{ stringifyDebugPayload(step.output ?? step.statePatch) }}</pre>
                    </el-tab-pane>
                    <el-tab-pane label="状态变化">
                      <pre>{{ stringifyDebugPayload(step.statePatch) }}</pre>
                    </el-tab-pane>
                    <el-tab-pane v-if="step.errorMessage" label="错误">
                      <pre>{{ step.errorCode }} {{ step.errorMessage }}</pre>
                    </el-tab-pane>
                  </el-tabs>
                </div>
              </article>
            </div>
          </section>
        </div>

        <div v-if="debugResult" class="debug-result">
          <div class="result-section">
            <strong>发布版本回答：</strong>
            <div>{{ debugResult.answer }}</div>
          </div>
          <div class="result-section" v-if="debugOpsItems.length">
            <strong>生产运行信息：</strong>
            <div class="runtime-insights">
              <div v-for="item in debugOpsItems" :key="item.label" class="runtime-insight">
                <span>{{ item.label }}</span>
                <strong>{{ item.value }}</strong>
              </div>
            </div>
          </div>
        </div>
      </div>
    </el-drawer>

    <el-drawer
      v-model="evalOpen"
      title="Workflow 评测"
      size="760px"
      class="workflow-eval-drawer"
      destroy-on-close
    >
      <div class="eval-body">
        <section class="eval-panel">
          <div class="eval-section-head">
            <div>
              <strong>发布前用例</strong>
              <span>使用当前 Workflow GraphSpec 草稿在沙箱模式下重复运行。</span>
            </div>
            <div class="eval-import-actions">
              <el-button size="small" @click="addEvalCase">新增用例</el-button>
              <el-button size="small" @click="resetEvalCases">重置示例</el-button>
            </div>
          </div>
          <el-table :data="evalCases" size="small" max-height="300">
            <el-table-column label="启用" width="64">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" />
              </template>
            </el-table-column>
            <el-table-column label="用例" min-width="120">
              <template #default="{ row }">
                <el-input v-model="row.caseNo" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="消息" min-width="190">
              <template #default="{ row }">
                <el-input v-model="row.message" type="textarea" :rows="2" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="输入 JSON" min-width="190">
              <template #default="{ row }">
                <el-input v-model="row.inputParamsJson" type="textarea" :rows="2" size="small" spellcheck="false" />
              </template>
            </el-table-column>
            <el-table-column label="期望包含" min-width="150">
              <template #default="{ row }">
                <el-input v-model="row.expectedText" size="small" placeholder="可留空" />
              </template>
            </el-table-column>
            <el-table-column label="" width="54" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text type="danger" @click="removeEvalCase(row.id)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section class="eval-panel">
          <div class="eval-section-head">
            <div>
              <strong>运行设置</strong>
              <span>默认附加 evalMode 和 sandboxSideEffects，避免真实不可逆副作用。</span>
            </div>
          </div>
          <div class="eval-toolbar">
            <el-input-number v-model="evalRepeatCount" :min="1" :max="20" controls-position="right" />
            <el-button :loading="validating" @click="validateRuntime">校验 GraphSpec</el-button>
            <el-button type="primary" :loading="evalRunning" :disabled="!enabledEvalCases.length" @click="runWorkflowEval">
              开始评测
            </el-button>
          </div>
        </section>

        <section v-if="evalResults.length" class="eval-summary-grid">
          <div class="eval-metric">
            <span>断言通过</span>
            <strong>{{ formatEvalRate(evalSummary.assertionRate) }}</strong>
          </div>
          <div class="eval-metric">
            <span>运行成功</span>
            <strong>{{ formatEvalRate(evalSummary.runtimeSuccessRate) }}</strong>
          </div>
          <div class="eval-metric">
            <span>P95 响应</span>
            <strong>{{ evalSummary.p95LatencyMs }} ms</strong>
          </div>
          <div class="eval-metric">
            <span>偏差数</span>
            <strong>{{ evalSummary.biasCount }}</strong>
          </div>
        </section>

        <section v-if="evalResults.length" class="eval-panel">
          <div class="eval-section-head">
            <div>
              <strong>评测结果</strong>
              <span>{{ evalResults.length }} 次执行 · {{ evalRunName }}</span>
            </div>
          </div>
          <el-table :data="evalResults" size="small" max-height="320">
            <el-table-column prop="roundNo" label="轮次" width="72" />
            <el-table-column prop="caseNo" label="用例" min-width="120" />
            <el-table-column label="状态" width="92">
              <template #default="{ row }">
                <el-tag :type="row.assertionPassed ? 'success' : row.runtimeSuccess ? 'warning' : 'danger'" size="small">
                  {{ row.assertionPassed ? '通过' : row.runtimeSuccess ? '偏差' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="elapsedMs" label="耗时" width="92" />
            <el-table-column prop="answer" label="输出" min-width="220" show-overflow-tooltip />
            <el-table-column prop="errorMessage" label="错误" min-width="160" show-overflow-tooltip />
          </el-table>
        </section>
      </div>
    </el-drawer>

    <el-drawer v-model="jsonDrawerVisible" title="Workflow JSON" size="50%">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="GraphSpec" name="graph">
          <el-input v-model="graphSpecJson" type="textarea" :autosize="{ minRows: 24 }" spellcheck="false" class="json-editor" />
        </el-tab-pane>
        <el-tab-pane label="Canvas" name="canvas">
          <el-input v-model="canvasJson" type="textarea" :autosize="{ minRows: 24 }" spellcheck="false" class="json-editor" />
        </el-tab-pane>
      </el-tabs>
    </el-drawer>

    <el-dialog v-model="apiQueryTemplateOpen" title="从 API 资产生成查询流程" width="900px" class="api-query-template-dialog">
      <div class="api-query-template-body">
        <el-alert
          type="info"
          :closable="false"
          title="选择一个已关联 Tool 的项目接口，系统会生成交互收集、页面查询动作、Tool 调用和结果展示节点。"
        />
        <div class="api-query-template-toolbar">
          <el-input
            v-model="apiQueryTemplateFilters.keyword"
            :prefix-icon="Search"
            clearable
            placeholder="搜索接口名称、路径、描述"
            @keyup.enter="reloadApiQueryTemplateAssets"
          />
          <el-select v-model="apiQueryTemplateFilters.toolLinkStatus" clearable placeholder="Tool 状态">
            <el-option label="已关联 Tool" value="LINKED" />
            <el-option label="未关联 Tool" value="NOT_LINKED" />
            <el-option label="全局 Tool 缺失" value="GLOBAL_MISSING" />
          </el-select>
          <el-input v-model="apiQueryTemplateActionKey" placeholder="page.search.applyFilters" />
          <el-button type="primary" @click="reloadApiQueryTemplateAssets">查询</el-button>
        </div>
        <el-table
          v-loading="apiQueryTemplateLoading"
          :data="apiQueryTemplateAssets"
          row-key="apiId"
          :row-class-name="apiQueryTemplateRowClassName"
          height="420"
          stripe
          empty-text="暂无 API 资产"
        >
          <el-table-column label="接口" min-width="280" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="api-template-cell">
                <strong>{{ row.name }}</strong>
                <span>{{ row.httpMethod || '-' }} {{ row.endpointPath || row.sourceLocation || '-' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="项目 / 模块" min-width="190" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="api-template-cell">
                <strong>{{ row.projectName || row.projectCode || '-' }}</strong>
                <span>{{ row.moduleName || '-' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="参数" width="80" align="center">
            <template #default="{ row }">{{ row.parameterCount || row.parameters?.length || 0 }}</template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="{ row }">
              <el-tag size="small" :type="apiQueryTemplateSelectable(row) ? 'success' : 'info'" effect="plain">
                {{ apiQueryTemplateStatusLabel(row) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button
                size="small"
                type="primary"
                text
                :disabled="!apiQueryTemplateSelectable(row)"
                @click="generateApiQueryTemplate(row)"
              >
                生成流程
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="api-query-template-footer">
          <span>页面动作 actionKey 由业务前端 SDK 启动时用项目 key/secret 自动上报，例如班组档案页可注册为 teamArchive.search。</span>
          <el-pagination
            v-model:current-page="apiQueryTemplateFilters.page"
            v-model:page-size="apiQueryTemplateFilters.pageSize"
            layout="total, prev, pager, next"
            :total="apiQueryTemplateTotal"
            @current-change="loadApiQueryTemplateAssets"
          />
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="aiDraftDialogOpen" title="AI 生成 Workflow 流程草稿" width="760px" class="ai-draft-dialog">
      <div class="ai-draft-body">
        <el-alert
          type="info"
          :closable="false"
          title="生成结果只替换当前前端草稿，不会自动保存或发布。占位节点需要补全后才能通过发布校验。"
        />
        <div class="ai-draft-model-row">
          <span>生成模型</span>
          <el-select
            v-model="aiModelInstanceId"
            filterable
            clearable
            placeholder="选择用于生成和 LLM 节点的模型"
            :disabled="aiDraftLoading"
          >
            <el-option
              v-for="item in aiDraftModelOptions"
              :key="item.id"
              :label="modelOptionLabel(item)"
              :value="item.id"
            />
          </el-select>
        </div>
        <el-input
          v-model="aiRequirement"
          type="textarea"
          :rows="4"
          maxlength="1000"
          show-word-limit
          placeholder="例如：查询订单状态，如果订单已完成就回复物流信息，否则提示当前处理进度。"
        />
        <div class="ai-draft-actions">
          <span>{{ availableTools.length }} 个工具 / {{ availableCompositions.length }} 个能力 / {{ knowledgeOptions.length }} 个知识库可供匹配</span>
          <el-button type="primary" :loading="aiDraftLoading" @click="generateAiDraft">生成预览</el-button>
        </div>
        <div v-if="aiDraftPreview" class="ai-draft-preview">
          <div class="ai-draft-preview-head">
            <div>
              <strong>{{ aiDraftPreview.graphSpec?.name || 'AI 生成流程' }}</strong>
              <span>{{ aiDraftPreview.provider }} / {{ aiDraftPreviewNodes.length }} 个节点</span>
            </div>
            <el-tag :type="aiDraftPreview.placeholderNodes?.length ? 'warning' : 'success'" size="small">
              {{ aiDraftPreview.placeholderNodes?.length ? '含占位节点' : '可编辑草稿' }}
            </el-tag>
          </div>
          <el-alert v-if="aiDraftPreview.warnings?.length" type="warning" :closable="false" class="ai-draft-warning">
            <template #title>
              <span v-for="item in aiDraftPreview.warnings" :key="item">{{ item }}</span>
            </template>
          </el-alert>
          <el-alert v-if="aiDraftPreview.validationErrors?.length" type="error" :closable="false" class="ai-draft-warning">
            <template #title>
              <span v-for="item in aiDraftPreview.validationErrors" :key="item">{{ item }}</span>
            </template>
          </el-alert>
          <div v-if="aiDraftPreview.placeholderNodes?.length" class="ai-draft-placeholders">
            <el-tag v-for="item in aiDraftPreview.placeholderNodes" :key="item.nodeId" type="warning" effect="plain">
              {{ item.label }}：{{ item.reason }}
            </el-tag>
          </div>
          <div v-if="aiDraftPreviewEdges.length" class="ai-draft-edge-list">
            <div v-for="edge in aiDraftPreviewEdges" :key="edge.id" class="ai-draft-edge">
              <span>{{ previewNodeLabel(edge.source) }}</span>
              <el-tag size="small" effect="plain">{{ previewEdgeLabel(edge) }}</el-tag>
              <span>{{ previewNodeLabel(edge.target) }}</span>
            </div>
          </div>
          <div class="ai-draft-node-list">
            <div v-for="node in aiDraftPreviewNodes" :key="node.id" class="ai-draft-node">
              <strong>{{ node.data?.label || node.id }}</strong>
              <span>{{ nodeKindLabel(node.data?.kind) }}</span>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="aiDraftDialogOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!aiDraftPreview || !!aiDraftPreview.validationErrors?.length" @click="handleApplyAiDraft">
          替换当前草稿
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="publishDialogOpen" title="发布 Workflow 版本" width="640px">
      <div v-if="releaseErrors.length || releaseWarnings.length" class="release-check-panel">
        <div class="release-check-head">
          <div>
            <strong>Workflow 发布门禁</strong>
            <span>{{ releaseErrors.length }} 个阻断项 / {{ releaseWarnings.length }} 个提醒项</span>
          </div>
          <el-tag :type="releaseErrors.length ? 'danger' : 'success'" size="small">
            {{ releaseErrors.length ? '未通过' : '已通过' }}
          </el-tag>
        </div>
        <el-collapse model-value="errors" class="release-check-collapse">
          <el-collapse-item v-if="releaseErrors.length" title="阻断项" name="errors">
            <div v-for="item in releaseErrors" :key="releaseValidationKey(item)" class="check-item error">
              <el-tag size="small" type="danger">{{ item.code }}</el-tag>
              <span v-if="item.nodeId" class="check-node">{{ item.nodeId }}</span>
              <span>{{ item.message }}</span>
            </div>
          </el-collapse-item>
          <el-collapse-item v-if="releaseWarnings.length" title="提醒项" name="warnings">
            <div v-for="item in releaseWarnings" :key="releaseValidationKey(item)" class="check-item warn">
              <el-tag size="small" type="warning">{{ item.code }}</el-tag>
              <span v-if="item.nodeId" class="check-node">{{ item.nodeId }}</span>
              <span>{{ item.message }}</span>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>
      <el-alert
        v-if="publishWarnings.length"
        type="warning"
        :closable="false"
        class="publish-warning"
        title="发布前检查"
      >
        <ul>
          <li v-for="item in publishWarnings" :key="item">{{ item }}</li>
        </ul>
      </el-alert>
      <el-form :model="publishForm" label-width="120px">
        <el-form-item label="版本号" required>
          <el-input v-model="publishForm.version" placeholder="v1.0.0" />
        </el-form-item>
        <el-form-item label="灰度比例">
          <el-slider v-model="publishForm.rolloutPercent" :min="0" :max="100" show-input />
        </el-form-item>
        <el-form-item label="发布说明">
          <el-input v-model="publishForm.note" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="发布者">
          <el-input v-model="publishForm.publishedBy" placeholder="运营账号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="publishDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="publishing" @click="handlePublishWorkflow">确认发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import type { Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Aim,
  ArrowLeft,
  ArrowRight,
  ArrowDown,
  ArrowUp,
  Briefcase,
  CircleCheck,
  Close,
  Coin,
  Collection,
  Connection,
  CopyDocument,
  Delete,
  Document,
  DocumentChecked,
  EditPen,
  Files,
  Finished,
  Link,
  MagicStick,
  MoreFilled,
  Minus,
  Operation,
  Plus,
  Promotion,
  Rank,
  Refresh,
  RefreshLeft,
  RefreshRight,
  Search,
  SetUp,
  Switch,
  Tickets,
  Tools,
  VideoPlay,
  ZoomIn,
  ZoomOut,
} from '@element-plus/icons-vue'
import { Handle, Position, VueFlow, useVueFlow, ConnectionLineType } from '@vue-flow/core'
import type { EdgeMouseEvent, NodeMouseEvent } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { MiniMap } from '@vue-flow/minimap'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'
import {
  getWorkflowDebugSession,
  validateWorkflowRuntime as validateWorkflowRuntimeApi,
} from '@/api/workflow'
import { extractDraftFromTrace } from '@/api/capabilityMining'
import type { TraceNode } from '@/types/trace'
import type { RunDetail, RunSpan, RunSummary, WorkflowPathItem } from '@/types/runops'
import type { ChatResponse } from '@/types/chat'
import type {
  WorkflowDebugRunResult,
  WorkflowDraftEditResult,
  WorkflowDraftGenerationResult,
  WorkflowNodeDebugResult,
  WorkflowPublishRequest,
  WorkflowReleaseValidationItem,
  WorkflowRuntimeValidationResult,
  WorkflowStudioState,
  WorkflowDebugMessage,
  WorkflowDebugSessionView,
  WorkflowDebugStepResult,
} from '@/types/workflow'
import type {
  CanvasEdge,
  CanvasNode,
  CanvasNodeKind,
  CanvasSnapshot,
  InteractionCallNodeRequest,
  StudioVariableOption,
} from '@/types/studio'
import type { ModelInstance } from '@/types/model'
import TraceTimeline from '@/components/TraceTimeline.vue'
import InteractionRenderer from '@/components/interaction/InteractionRenderer.vue'
import type { UiFieldPayload, UiRequestPayload } from '@/types/interaction'
import {
  createWorkflowCanvasNode,
  workflowCanvasToSaveRequest,
  workflowStudioToCanvas,
} from '@/utils/workflowStudio'
import {
  runDisplayName,
  runMatchesCurrentWorkflow,
} from '@/utils/workflowRunOps'
import LlmModelIcon from '@/components/icons/LlmModelIcon.vue'
import SendIcon from '@/components/icons/SendIcon.vue'
import type { StudioFieldSchema } from '@/types/studio'
import { normalizeCanvasEdgeHandles } from '@/utils/studio'
import NodeConfigPanel from '@/views/workflow/studio-panels/NodeConfigPanel.vue'
import { useWorkflowStudioNodeMetadata } from '@/views/workflow/composables/useWorkflowStudioNodeMetadata'
import { useWorkflowStudioPalette } from '@/views/workflow/composables/useWorkflowStudioPalette'
import { useWorkflowStudioDebugSession } from '@/views/workflow/composables/useWorkflowStudioDebugSession'
import { useWorkflowStudioSessionLifecycle } from '@/views/workflow/composables/useWorkflowStudioSessionLifecycle'
import { useWorkflowStudioCanvasSearch } from '@/views/workflow/composables/useWorkflowStudioCanvasSearch'
import { useWorkflowStudioPersistence } from '@/views/workflow/composables/useWorkflowStudioPersistence'
import { useWorkflowStudioRelease } from '@/views/workflow/composables/useWorkflowStudioRelease'
import {
  useWorkflowStudioCanvasActions,
  type WorkflowNodeTraceState,
} from '@/views/workflow/composables/useWorkflowStudioCanvasActions'
import {
  useWorkflowStudioDebugRun,
  debugStepStatus,
  stringifyDebugPayload,
  formatElapsed,
} from '@/views/workflow/composables/useWorkflowStudioDebugRun'
import { useWorkflowStudioAiDraft } from '@/views/workflow/composables/useWorkflowStudioAiDraft'
import { useWorkflowStudioAiDraftActions } from '@/views/workflow/composables/useWorkflowStudioAiDraftActions'
import { useWorkflowStudioEval } from '@/views/workflow/composables/useWorkflowStudioEval'
import { useWorkflowStudioHistory } from '@/views/workflow/composables/useWorkflowStudioHistory'
import { useWorkflowStudioApiQueryTemplate } from '@/views/workflow/composables/useWorkflowStudioApiQueryTemplate'
import { useWorkflowStudioResources } from '@/views/workflow/composables/useWorkflowStudioResources'
import {
  useWorkflowStudioGraphAnalysis,
  type GraphLintItem,
} from '@/views/workflow/composables/useWorkflowStudioGraphAnalysis'
import { formatJson, normalizeJson } from '@/views/workflow/composables/workflowStudioJson'

const route = useRoute()
const router = useRouter()
const { fitView, screenToFlowCoordinate, setCenter, zoomIn, zoomOut, getViewport } = useVueFlow()
const workflowId = computed(() => String(route.params.workflowId || route.params.id || ''))
const studio = ref<WorkflowStudioState | null>(null)
const graphSpecJson = ref('')
const canvasJson = ref('')
const emptyWorkspaceGraphSpec = formatJson('{"nodes":[],"edges":[]}')
const emptyWorkspaceCanvas = formatJson('{"nodes":[],"edges":[]}')
const activeTab = ref('visual')
const loading = ref(false)
const saving = ref(false)
const validating = ref(false)
const publishing = ref(false)
const nodeKeyword = ref('')
const canvasSearchOpen = ref(false)
const canvasSearchKeyword = ref('')
const canvasSearchIndex = ref(0)
const canvasSearchInputRef = ref()
const nodes = ref<CanvasNode[]>([])
const edges = ref<CanvasEdge[]>([])
const selectedNodeId = ref<string | null>(null)
const selectedEdgeId = ref<string | null>(null)
const visualDirty = ref(false)
const copiedNode = ref<CanvasNode | null>(null)
const historyPast = ref<string[]>([])
const historyFuture = ref<string[]>([])
const historyApplying = ref(false)
const historyReady = ref(false)
const validation = ref<WorkflowRuntimeValidationResult | null>(null)
const debugNodeId = ref('')
const debugMessage = ref('这是一条测试消息')
const nodeDebugMessage = ref('这是一条节点测试消息')
const nodeDebugStateJson = ref('{}')
const nodeDebugStateText = ref('{\n  "input": "这是一条节点测试消息"\n}')
const runInputParamsJson = ref('{}')
const runDebugOptionsJson = ref('{}')
const nodeDebugLoading = ref(false)
const debugLoading = ref(false)
const nodeDebugResult = ref<WorkflowNodeDebugResult | null>(null)
const debugRunResult = ref<WorkflowDebugRunResult | null>(null)
const debugSession = ref<WorkflowDebugSessionView | null>(null)
const debugResult = ref<ChatResponse | null>(null)
const debugInputParams = reactive<Record<string, unknown>>({})
const debugInteractionParams = reactive<Record<string, unknown>>({})
const currentTraceId = ref('')
const traceNodes = ref<TraceNode[]>([])
const runOpsDetail = ref<RunDetail | null>(null)
const replayTraceInput = ref('')
const selectedRecentTraceId = ref('')
const traceReplayLoading = ref(false)
const recentRunsLoading = ref(false)
const recentRuns = ref<RunSummary[]>([])
const traceToolPick = ref<string[]>([])
const extracting = ref(false)
const selectedDebugStepIndex = ref<number | null>(null)
const currentDebugNodeId = ref('')
const debugPlaybackToken = ref(0)
const aiModelInstanceId = ref('')
const aiRequirement = ref('')
const aiEditInstruction = ref('')
const aiDraftLoading = ref(false)
const aiEditLoading = ref(false)
const aiEditMinimized = ref(false)
const aiDraftPreview = ref<WorkflowDraftGenerationResult | null>(null)
const aiEditPreview = ref<WorkflowDraftEditResult | null>(null)
const paletteExpanded = ref(false)
const activePaletteGroup = ref('推理')
const propertyPanelCollapsed = ref(true)
const canvasOperatorCollapsed = ref(true)
const inspectorExpanded = ref(false)
const nodeSearchOpen = ref(false)
const nodeSearchKeyword = ref('')
const propertyDetailOpen = ref(false)
type PropertyDetailSection = 'base' | 'node' | 'debug' | 'trace'
const propertyDetailSection = ref<PropertyDetailSection>('base')
const jsonDrawerVisible = ref(false)
const debugOpen = ref(false)
const publishDialogOpen = ref(false)
const releaseErrors = ref<WorkflowReleaseValidationItem[]>([])
const releaseWarnings = ref<WorkflowReleaseValidationItem[]>([])
const viewportWidth = ref(typeof window === 'undefined' ? 1440 : window.innerWidth)
const lastSavedAt = ref('--:--')
const publishForm = reactive<WorkflowPublishRequest>({
  version: 'v1.0.0',
  rolloutPercent: 100,
  note: '',
  publishedBy: '',
})
const workflowMeta = reactive({
  name: '',
  keySlug: '',
  workflowType: '',
  description: '',
  defaultModelInstanceId: '',
})
const aiDraftDialogOpen = ref(false)

const selectedToolName = computed(() => {
  const node = nodes.value.find((item) => item.id === selectedNodeId.value) || null
  return node?.data.kind === 'tool' ? node.data.toolConfig?.ref || '' : ''
})

const {
  nodeTypesLoading,
  nodeTypes,
  modelOptions,
  knowledgeOptions,
  toolOptions,
  compositionOptions,
  credentialOptions,
  paramSourceHints,
  graphNodeTypeCapabilitiesLoaded,
  availableTools,
  availableCompositions,
  aiDraftModelOptions,
  selectedAiEditModel,
  selectedToolInfo,
  loadNodeTypes,
  loadToolOptions,
  loadCompositionOptions,
  loadModelOptions,
  loadKnowledgeOptions,
  loadCredentialOptions,
  handleCredentialCreated,
  refreshParamSourceHints,
} = useWorkflowStudioResources({
  studio,
  aiModelInstanceId,
  selectedToolName,
})

const studioReadOnly = computed(() => {
  const managed = studio.value?.managedBy
  return managed === 'SDK' || managed === 'AI_QUICK_ACCESS'
})

const selectedAiEditModelLabel = computed(() =>
  selectedAiEditModel.value ? modelOptionLabel(selectedAiEditModel.value) : '',
)

const statusTagType = computed(() => {
  if (studio.value?.status === 'ACTIVE') return 'success'
  if (studio.value?.status === 'ARCHIVED') return 'info'
  return 'warning'
})

const validationLabel = computed(() => {
  if (!validation.value) return 'Pending'
  return validation.value.valid ? 'Valid' : `${validation.value.errors.length} issue(s)`
})

const validationTagType = computed(() => {
  if (!validation.value) return 'info'
  return validation.value.valid ? 'success' : 'danger'
})

const saveBadgeText = computed(() => {
  if (saving.value) return '保存中'
  if (visualDirty.value || workflowMetaDirty()) return '待保存'
  return lastSavedAt.value === '--:--' ? '已保存' : `已保存 ${lastSavedAt.value}`
})

const saveStateClass = computed(() => ({
  'is-saving': saving.value,
  'is-pending': !saving.value && (visualDirty.value || workflowMetaDirty()),
  'is-saved': !saving.value && !visualDirty.value && !workflowMetaDirty(),
}))

function categoryLabel(value?: string | null) {
  const normalized = String(value || '').toLowerCase()
  const labels: Record<string, string> = {
    system: '系统',
    custom: '自定义',
    sdk: 'SDK',
    action: '执行',
    flow: '流程',
    integration: '集成',
  }
  return labels[normalized] || value || '系统'
}

const defaultEdgeOptions = {
  type: 'smoothstep',
  markerEnd: 'arrowclosed',
  interactionWidth: 18,
}

const connectionLineOptions = {
  type: ConnectionLineType.SmoothStep,
}

function visibilityLabel(value?: string | null) {
  const normalized = String(value || '').toUpperCase()
  const labels: Record<string, string> = {
    PRIVATE: '私有',
    PUBLIC: '公开',
    SHARED: '共享',
    DRAFT: '草稿',
  }
  return labels[normalized] || value || '私有'
}

const workflowVisibilityLabel = computed(() => {
  const status = studio.value?.status
  if (status === 'ACTIVE') return '已发布'
  if (status === 'ARCHIVED') return '已归档'
  return '草稿'
})

const studioDisplayName = computed(() => studio.value?.name || workflowMeta.name || '未命名')

const studioKeySlug = computed(() => studio.value?.keySlug || workflowMeta.keySlug || '')

const propertyDetailSectionLabel = computed(() => {
  const sectionLabel: Record<PropertyDetailSection, string> = {
    base: '基础信息',
    node: '节点配置',
    debug: '节点测试',
    trace: '运行记录',
  }
  return sectionLabel[propertyDetailSection.value]
})

const {
  paletteGroups,
  filteredPaletteGroups,
  flatFilteredPalette,
  openPaletteGroup,
  kindColor,
  nodeIcon,
} = useWorkflowStudioPalette({
  nodeSearchKeyword,
  activePaletteGroup,
  paletteExpanded,
  nodeTypes,
  graphNodeTypeCapabilitiesLoaded,
})

const {
  nodeKindLabel,
  assignmentCount,
  userInputFieldCount,
  interactionFieldCount,
  interactionTypeLabel,
} = useWorkflowStudioNodeMetadata()

const {
  rememberDebugSession,
  forgetDebugSession,
  applyDebugSession,
  clearDebugSessionView,
  loadStoredDebugSession,
} = useWorkflowStudioDebugSession({
  workflowId,
  workflowKeySlug: computed(() => workflowMeta.keySlug),
  debugSession,
  debugRunResult,
  selectedDebugStepIndex,
  currentDebugNodeId,
  currentTraceId,
  replayTraceInput,
  selectedRecentTraceId,
  refreshWorkflowNodeClasses: () => refreshWorkflowNodeClasses(),
  getDebugSessionById: async (sessionId: string) => {
    const { data } = await getWorkflowDebugSession(sessionId)
    return data
  },
})

const {
  canvasSearchMatches,
  openCanvasSearch,
  closeCanvasSearch,
  focusCanvasSearchMatch,
  focusNextCanvasSearch,
  focusPrevCanvasSearch,
} = useWorkflowStudioCanvasSearch({
  canvasSearchOpen,
  canvasSearchKeyword,
  canvasSearchIndex,
  canvasSearchInputRef,
  nodes,
  selectedNodeId,
  selectedEdgeId,
  setCenter,
}, nextTick)

const { resetWorkflowSessionState, clearWorkflowDocumentState } = useWorkflowStudioSessionLifecycle({
  closeCanvasSearch,
  selectedNodeId,
  selectedEdgeId,
  debugNodeId,
  currentDebugNodeId,
  selectedDebugStepIndex,
  nodeDebugResult,
  debugRunResult,
  debugSession,
  debugResult,
  aiDraftPreview,
  aiEditPreview,
  validation,
  visualDirty,
  historyPast,
  historyFuture,
  historyReady,
  studio,
  graphSpecJson,
  canvasJson,
  nodes,
  edges,
  emptyGraphSpecJson: emptyWorkspaceGraphSpec,
  emptyCanvasJson: emptyWorkspaceCanvas,
  resetHistorySnapshot: () => resetHistorySnapshot(),
  forgetDebugSession,
})

const selectedNodeTrace = computed(() =>
  selectedNodeId.value ? nodeTraceStates.value[selectedNodeId.value] || null : null,
)

const filteredNodeTypes = computed(() => {
  const keyword = nodeKeyword.value.trim().toLowerCase()
  if (!keyword) return nodeTypes.value
  return nodeTypes.value.filter((node) =>
    [node.type, node.canvasKind, node.canvasCategory, node.family, ...(node.aliases || [])]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(keyword)),
  )
})

const selectedNode = computed(() =>
  nodes.value.find((node) => node.id === selectedNodeId.value) || null,
)

const selectedEdge = computed(() =>
  edges.value.find((edge) => edge.id === selectedEdgeId.value) || null,
)

const selectedEdgeSourceNode = computed(() =>
  selectedEdge.value ? nodes.value.find((node) => node.id === selectedEdge.value?.source) || null : null,
)

const selectedEdgeRouteOptions = computed(() => sourceRouteOptions(selectedEdgeSourceNode.value))

function sourceRouteOptions(source?: CanvasNode | null) {
  if (source?.data.kind === 'condition') {
    const groups = source.data.conditionConfig?.groups || []
    return groups
      .filter((group) => group.id?.trim())
      .map((group) => ({
        value: `route:${group.id.trim()}`,
        label: group.label || `路由：${group.id}`,
      }))
  }
  if (source?.data.kind === 'classifier') {
    return (source.data.classifierConfig?.classes || [])
      .filter((item) => item.id?.trim())
      .map((item) => ({
        value: `route:${item.id.trim()}`,
        label: item.label || item.id,
      }))
  }
  if (source?.data.kind === 'approval') {
    return [
      { value: 'route:approved', label: '批准' },
      { value: 'route:rejected', label: '拒绝' },
      { value: 'route:timeout', label: '超时' },
    ]
  }
  if (source?.data.kind === 'loop') {
    return [
      { value: 'route:continue', label: '继续' },
      { value: 'route:done', label: '结束' },
    ]
  }
  return []
}

const selectedEdgeConditionOptions = computed(() => [
  { value: 'always', label: '默认 / always' },
  { value: 'success', label: '成功' },
  { value: 'error', label: '失败' },
  { value: 'else', label: '否则 / else' },
  { value: 'empty', label: '为空' },
  { value: 'not_empty', label: '非空' },
  ...selectedEdgeRouteOptions.value,
])

const canCopySelectedNode = computed(() =>
  !!selectedNode.value && !['start', 'end'].includes(selectedNode.value.data.kind),
)
const canDeleteSelection = computed(() =>
  !!selectedEdge.value || (!!selectedNode.value && !['start', 'end'].includes(selectedNode.value.data.kind)),
)

const debugInputFields = computed<StudioFieldSchema[]>(() => {
  const userInputNode = nodes.value.find((node) => node.data.kind === 'userInput')
  return (userInputNode?.data.userInputConfig?.fields || [])
    .filter((field) => !!field.name?.trim())
})

const debugWaitingRequest = computed<UiRequestPayload | null>(() => {
  if (debugSession.value?.status === 'WAITING' && debugSession.value.uiRequest) {
    return debugSession.value.uiRequest
  }
  for (const step of debugRunResult.value?.steps || []) {
    if (debugStepStatus(step.status) !== 'waiting') continue
    const output = debugWaitingOutput(step)
    const request = uiRequestFromOutput(output)
    if (request) return request
  }
  return null
})

const debugWaitingFields = computed<UiFieldPayload[]>(() =>
  (debugWaitingRequest.value?.fields || []).filter((field) => !!debugUiFieldKey(field)),
)

const debugSessionMessages = computed(() => debugSession.value?.messages || [])

const debugCurrentUiRequest = computed<UiRequestPayload | null>(() =>
  debugSession.value?.status === 'WAITING'
    ? debugSession.value.uiRequest || debugWaitingRequest.value
    : null,
)

const debugSessionSteps = computed<WorkflowDebugStepResult[]>(() =>
  debugSession.value?.steps || debugRunResult.value?.steps || [],
)

const traceToolNames = computed(() => {
  const names = traceNodes.value
    .map((n) => (n.toolName || '').trim())
    .filter((n) => !!n)
  return Array.from(new Set(names))
})

const workflowPath = computed<WorkflowPathItem[]>(() => runOpsDetail.value?.workflowPath ?? [])

const workflowPathSourceNodeIds = computed(() => {
  const ids = new Set<string>()
  for (const step of debugRunResult.value?.steps || []) {
    ids.add(step.nodeId)
  }
  for (const item of workflowPath.value) {
    if (item.fromNodeId) ids.add(item.fromNodeId)
  }
  return ids
})

const workflowHitEdgeKeys = computed(() => {
  const keys = new Set<string>()
  for (const step of debugRunResult.value?.steps || []) {
    if (step.nextNodeId) {
      keys.add(edgeKey(step.nodeId, step.nextNodeId))
    }
  }
  for (const item of workflowPath.value) {
    if (item.fromNodeId && item.toNodeId) {
      keys.add(edgeKey(item.fromNodeId, item.toNodeId))
    }
  }
  return keys
})

const workflowReplaySummary = computed(() => {
  if (!workflowPath.value.length) return []
  const waiting = workflowPath.value.filter((item) => workflowItemStatus(item) === 'waiting').length
  const errors = workflowPath.value.filter((item) => workflowItemStatus(item) === 'error').length
  return [
    { label: 'RunOps path', value: String(workflowPath.value.length) },
    { label: 'Waiting', value: String(waiting) },
    { label: 'Errors', value: String(errors) },
  ]
})

const studioRecentRuns = computed(() => {
  const currentWorkflowId = workflowId.value
  const currentWorkflowName = (studio.value?.name || workflowMeta.name || '').trim()
  const currentWorkflowKeySlug = (studio.value?.keySlug || workflowMeta.keySlug || '').trim()
  const matched = recentRuns.value.filter((run) =>
    runMatchesCurrentWorkflow(run, currentWorkflowId, currentWorkflowName, currentWorkflowKeySlug),
  )
  return matched.length ? matched : recentRuns.value
})

const studioRecentRunsPlaceholder = computed(() => {
  const currentWorkflowId = workflowId.value
  const currentWorkflowName = (studio.value?.name || workflowMeta.name || '').trim()
  const currentWorkflowKeySlug = (studio.value?.keySlug || workflowMeta.keySlug || '').trim()
  const matchedCount = recentRuns.value.filter((run) =>
    runMatchesCurrentWorkflow(run, currentWorkflowId, currentWorkflowName, currentWorkflowKeySlug),
  ).length
  if (!recentRuns.value.length) {
    return '选择最近运行'
  }
  if (matchedCount > 0) {
    return '当前 Workflow 最近运行'
  }
  return '最近运行（含其他 Workflow）'
})

const nodeTraceList = computed(() =>
  Object.values(nodeTraceStates.value).sort((a, b) => {
    const ai = nodes.value.findIndex((node) => node.id === a.nodeId)
    const bi = nodes.value.findIndex((node) => node.id === b.nodeId)
    return (ai < 0 ? 9999 : ai) - (bi < 0 ? 9999 : bi)
  }),
)

const debugOpsItems = computed(() => {
  const metadata = debugResult.value?.metadata || {}
  const fallback = metadata.embeddedFallbackReason ? '已回落' : metadata.runtimePlacement === 'HYBRID' ? '未回落' : '-'
  return [
    { label: '版本', value: textValue(metadata.version) },
    { label: '运行位置', value: textValue(metadata.runtimePlacement) },
    { label: '运行时', value: textValue(metadata.runtimeType) },
    { label: '业务项目', value: textValue(metadata.projectCode) },
    { label: 'Workflow', value: textValue(metadata.workflowKeySlug || metadata.workflowId) },
    { label: '入口绑定', value: textValue(metadata.entryAgentKeySlug || metadata.entryAgentId) },
    { label: '实例', value: textValue(metadata.instanceId) },
    { label: 'HYBRID 回落', value: fallback },
    { label: '追踪 ID', value: textValue(metadata.traceId) },
  ].filter((item) => item.value !== '-')
})

const variablePreview = computed(() => {
  const flowNodes = nodes.value
    .filter((n) => n.data.kind !== 'start' && n.data.kind !== 'end')
    .map((n) => ({
      id: n.id,
      kind: n.data.kind,
      ref: n.data.toolConfig?.ref || '',
      outputAlias: n.data.outputAlias || '',
      inputSchema: n.data.inputSchema || {},
      outputSchema: n.data.outputSchema || {},
      inputFields: n.data.userInputConfig?.fields || [],
      interactionFields: n.data.interactionConfig?.fields || [],
      inputMapping: n.data.toolConfig?.inputMapping || n.data.mcpConfig?.inputMapping || n.data.inputMapping || {},
      outputs: n.data.outputs || [],
      assignments: n.data.assignments || {},
      parameters: n.data.parameterConfig?.fields || [],
    }))
  return {
    context: {
      userId: '$context.userId',
      tenantId: '$context.tenantId',
      roles: '$context.roles',
    },
    nodes: flowNodes,
  }
})

const selectedDebugStep = computed(() => {
  if (selectedDebugStepIndex.value === null) return null
  return debugSessionSteps.value[selectedDebugStepIndex.value] || null
})

const nodeTraceStates = computed<Record<string, WorkflowNodeTraceState>>(() => {
  const states: Record<string, WorkflowNodeTraceState> = {}
  const debugSteps = debugRunResult.value?.steps || []
  if (debugSteps.length) {
    for (const step of debugSteps) {
      states[step.nodeId] = {
        nodeId: step.nodeId,
        status: debugStepStatus(step.status),
        elapsedMs: step.elapsedMs,
        input: stringifyDebugPayload(step.input),
        output: stringifyDebugPayload(step.output ?? step.statePatch),
        errorCode: step.errorCode,
        route: step.route,
        createdAt: step.startedAt,
      }
    }
    return states
  }
  const runSpans = runOpsDetail.value?.spans ?? []
  if (runSpans.length) {
    const orderedSpans = [...runSpans].sort((a, b) => dateMs(a.startedAt) - dateMs(b.startedAt))
    for (const item of orderedSpans) {
      const next = spanToNodeTraceState(item)
      if (!next) continue
      const previous = states[next.nodeId]
      states[next.nodeId] = preferNodeTraceState(previous, next)
    }
    for (const item of workflowPath.value) {
      const nodeId = (item.fromNodeId || '').trim()
      if (!nodeId) continue
      const previous = states[nodeId]
      const next: WorkflowNodeTraceState = {
        nodeId,
        status: workflowItemStatus(item),
        route: item.route || item.condition,
        createdAt: item.startedAt,
      }
      states[nodeId] = preferNodeTraceState(previous, next)
    }
    return states
  }
  const ordered = [...traceNodes.value].sort((a, b) => dateMs(a.createdAt) - dateMs(b.createdAt))
  for (const item of ordered) {
    const nodeId = (item.nodeId || '').trim()
    if (!nodeId) continue
    const next: WorkflowNodeTraceState = {
      nodeId,
      status: item.success ? 'success' : 'error',
      elapsedMs: item.elapsedMs,
      input: item.argsJson,
      output: item.resultSummary,
      errorCode: item.errorCode,
      createdAt: item.createdAt,
    }
    const previous = states[nodeId]
    states[nodeId] = preferNodeTraceState(previous, next)
  }
  return states
})

function markCanvasDirty() {
  visualDirty.value = true
}

function lastRouteForNode(nodeId: string) {
  if (nodeDebugResult.value?.nodeId === nodeId) {
    const route = nodeDebugResult.value.lastRoute || String(nodeDebugResult.value.outputState?.lastRoute || '')
    if (route) return route
  }
  const fromWorkflow = workflowPath.value.find((item) => item.fromNodeId === nodeId && item.route)?.route
  if (fromWorkflow) return fromWorkflow
  const trace = nodeTraceStates.value[nodeId]
  if (trace?.route) return trace.route
  if (!trace?.output) return ''
  try {
    const parsed = JSON.parse(trace.output)
    return String(parsed.lastRoute || parsed.outputState?.lastRoute || '')
  } catch {
    const match = trace.output.match(/lastRoute[=:]\s*([A-Za-z0-9_-]+)/)
    return match?.[1] || ''
  }
}

const {
  decorateWorkflowEdge,
  decorateWorkflowNode,
  decorateWorkflowEdges,
  refreshWorkflowNodeClasses,
  canvasSnapshot,
  onConnect,
  copySelectedNode,
  pasteCopiedNode,
  deleteSelection,
  deleteSelectedNode,
  syncSelectedEdgeLabel,
  applySelectedEdgeCondition,
  cloneCanvasNode,
  previewEdgeLabel,
  connectionCondition,
  edgeDisplayLabel,
  isDynamicCondition,
  stripTransientNodeClasses,
} = useWorkflowStudioCanvasActions({
  studioReadOnly,
  nodes,
  edges,
  selectedNodeId,
  selectedEdgeId,
  debugNodeId,
  copiedNode,
  currentDebugNodeId,
  nodeTraceStates,
  canCopySelectedNode,
  selectedNode,
  selectedEdge,
  workflowPath,
  workflowHitEdgeKeys,
  workflowPathSourceNodeIds,
  getNodeDebugState: (nodeId) => nodeDebugStateForCanvas(nodeId),
  getLastRouteForNode: lastRouteForNode,
  markCanvasDirty,
  syncJsonFromCanvas: () => syncJsonFromCanvas(),
  activeTab,
  propertyDetailOpen,
  fitView,
  nextTick,
})

const {
  canvasStats,
  graphVariables,
  graphLintItems,
  graphLintErrors,
  graphLintWarnings,
  autoLayoutWorkflowCanvas,
} = useWorkflowStudioGraphAnalysis({
  nodes,
  edges,
  decorateWorkflowNode,
  markCanvasDirty,
  syncJsonFromCanvas,
})

const {
  canUndo,
  canRedo,
  currentSnapshotText,
  pushHistorySnapshot,
  resetHistorySnapshot,
  undoCanvas,
  redoCanvas,
} = useWorkflowStudioHistory({
  historyPast,
  historyFuture,
  historyApplying,
  historyReady,
  nodes,
  edges,
  selectedNodeId,
  selectedEdgeId,
  visualDirty,
  stripTransientNodeClasses,
  decorateWorkflowNode,
  decorateWorkflowEdge,
  syncJsonFromCanvas: () => syncJsonFromCanvas(),
  nextTick,
})

function applyCanvasFromStudio(state: WorkflowStudioState) {
  const snapshot = workflowStudioToCanvas(state)
  nodes.value = (snapshot.nodes || []).map(decorateWorkflowNode)
  edges.value = (snapshot.edges || []).map(decorateWorkflowEdge)
  selectedNodeId.value = null
  selectedEdgeId.value = null
  visualDirty.value = false
  nextTick(() => fitView({ padding: 0.2, duration: 240 }))
}

function syncJsonFromCanvas() {
  if (!studio.value) return
  const saveRequest = workflowCanvasToSaveRequest(studio.value, canvasSnapshot())
  graphSpecJson.value = formatJson(saveRequest.graphSpecJson)
  canvasJson.value = formatJson(saveRequest.canvasJson || '{"nodes":[],"edges":[]}')
  visualDirty.value = false
  validation.value = null
}

const {
  syncWorkflowMetaFromStudio,
  workflowMetaDirty,
  loadStudio,
  saveStudio,
} = useWorkflowStudioPersistence({
  workflowId,
  studioReadOnly,
  saving,
  loading,
  studio,
  graphSpecJson,
  canvasJson,
  nodes,
  workflowMeta,
  visualDirty,
  lastSavedAt,
  validation,
  aiModelInstanceId,
  applyCanvasFromStudio,
  syncJsonFromCanvas,
  resetHistorySnapshot: () => resetHistorySnapshot(),
  loadCredentialOptions,
  clearWorkflowDocumentState,
})

const {
  resolveAiModelInstanceId,
  toolToDraftResource,
  compositionToDraftResource,
  knowledgeToDraftResource,
} = useWorkflowStudioAiDraft({
  aiModelInstanceId,
  studio,
  aiDraftModelOptions,
})

const {
  buildDebugBaseRequest,
  buildWorkflowDebugDraftDefinition,
  currentStudioStateForDebug,
  nodeDebugState: resolveNodeDebugState,
  nodeRunClass: resolveNodeRunClass,
  nodeRunLabel: resolveNodeRunLabel,
  focusDebugNode,
  selectDebugStep,
  openNodeTrace,
  handleDebug,
  handleRunDraftDebug,
  handleDebugUiSubmit,
  handleCancelDebugSession,
  loadRecentStudioRuns,
  handleLoadTraceReplay,
  handleRecentTraceChange,
  clearTraceReplay,
  recentRunLabel,
  handleRunPublishedDebug,
  runNodeDebug,
  handleRunNodeDebug,
  clearWorkflowDebugView,
  isDebugStepRunning,
} = useWorkflowStudioDebugRun({
  workflowId,
  studio,
  workflowMeta,
  graphSpecJson,
  canvasJson,
  nodes,
  debugOpen,
  propertyPanelCollapsed,
  debugLoading,
  nodeDebugLoading,
  traceReplayLoading,
  recentRunsLoading,
  debugNodeId,
  debugMessage,
  nodeDebugMessage,
  nodeDebugStateJson,
  debugInputParams,
  debugInteractionParams,
  currentTraceId,
  traceNodes,
  runOpsDetail,
  replayTraceInput,
  selectedRecentTraceId,
  recentRuns,
  selectedDebugStepIndex,
  currentDebugNodeId,
  debugPlaybackToken,
  nodeDebugResult,
  debugRunResult,
  debugSession,
  debugResult,
  selectedNodeId,
  selectedEdgeId,
  selectedNode,
  nodeDebugStateText,
  debugInputFields,
  debugWaitingFields,
  resolveAiModelInstanceId,
  syncJsonFromCanvas,
  canvasSnapshot,
  refreshWorkflowNodeClasses,
  applyDebugSession,
  forgetDebugSession,
  clearDebugSessionView,
  loadStoredDebugSession,
  getViewport,
  setCenter,
  nextTick,
  findNodePosition: (nodeId) => {
    const node = nodes.value.find((item) => item.id === nodeId)
    return node ? node.position : null
  },
  parseOptionalObject,
})

const {
  evalOpen,
  evalRunning,
  evalRepeatCount,
  evalRunName,
  evalCases,
  evalResults,
  enabledEvalCases,
  evalSummary,
  openEvalDrawer,
  addEvalCase,
  removeEvalCase,
  resetEvalCases,
  formatEvalRate,
  runWorkflowEval,
} = useWorkflowStudioEval({
  studio,
  buildDebugBaseRequest,
  parseOptionalObject,
})

const {
  selectedNodeIdsForAi,
  selectedEdgeIdsForAi,
  activeAiPreview,
  aiDraftPreviewNodes,
  aiDraftPreviewEdges,
  aiDraftPreviewNodeLabels,
  aiEditOperationGroups,
  aiPreviewPlaceholderNodes,
  draftPreviewTitle,
  draftPreviewSummary,
  draftPreviewIssues,
  aiEditScopeLabel,
  openAiDraftDialog,
  generateAiDraft,
  editAiDraft,
  applyAiPreview,
  clearAiPreview,
  clearAiEditPreview,
  applyAiEditPreview,
  handleApplyAiDraft,
  operationKey,
  previewNodeLabel,
  workflowEditOperationLabel,
  operationTarget,
} = useWorkflowStudioAiDraftActions({
  workflowId,
  studioReadOnly,
  studio,
  graphSpecJson,
  canvasJson,
  nodes,
  edges,
  selectedNodeId,
  selectedEdgeId,
  activeTab,
  validation,
  aiModelInstanceId,
  aiRequirement,
  aiEditInstruction,
  aiDraftLoading,
  aiEditLoading,
  aiDraftPreview,
  aiEditPreview,
  aiDraftDialogOpen,
  availableTools,
  availableCompositions,
  knowledgeOptions,
  resolveAiModelInstanceId,
  toolToDraftResource,
  compositionToDraftResource,
  knowledgeToDraftResource,
  syncJsonFromCanvas,
  canvasSnapshot,
  applyCanvasFromStudio,
})

const {
  apiQueryTemplateOpen,
  apiQueryTemplateLoading,
  apiQueryTemplateAssets,
  apiQueryTemplateTotal,
  apiQueryTemplateActionKey,
  apiQueryTemplateFilters,
  openApiQueryTemplateDialog,
  reloadApiQueryTemplateAssets,
  loadApiQueryTemplateAssets,
  applyApiAssetRouteContext,
  apiQueryTemplateRowClassName,
  apiQueryTemplateSelectable,
  apiQueryTemplateStatusLabel,
  generateApiQueryTemplate,
} = useWorkflowStudioApiQueryTemplate({
  studio,
  nodes,
  edges,
  selectedNodeId,
  selectedEdgeId,
  propertyPanelCollapsed,
  decorateWorkflowNode,
  decorateWorkflowEdge,
  markCanvasDirty,
  syncJsonFromCanvas,
})

const {
  publishWarnings,
  publishWorkflow,
  preloadPublishValidation,
  releaseValidationKey,
  formatReleaseValidationItem,
  handlePublishWorkflow,
} = useWorkflowStudioRelease({
  workflowId,
  studioReadOnly,
  studio,
  nodes,
  publishing,
  publishDialogOpen,
  releaseErrors,
  releaseWarnings,
  publishForm,
  saveStudio,
  loadStudio,
})

function nodeDebugStateForCanvas(nodeId: string) {
  return resolveNodeDebugState(nodeId, nodeTraceStates.value)
}

function nodeDebugState(nodeId: string) {
  return nodeDebugStateForCanvas(nodeId)
}

function nodeRunClass(nodeId: string) {
  return resolveNodeRunClass(nodeId, nodeTraceStates.value)
}

function nodeRunLabel(nodeId: string) {
  return resolveNodeRunLabel(nodeId, nodeTraceStates.value)
}

const variableOptions = computed<StudioVariableOption[]>(() => {
  const options: StudioVariableOption[] = [
    { value: 'params', label: '用户输入 · 全部参数', group: '用户输入', description: '用户输入节点写入的 params 对象' },
    { value: 'sys.userId', label: '系统变量 · 当前用户 ID', group: '系统变量', description: '运行上下文用户标识' },
    { value: 'sys.tenantId', label: '系统变量 · 租户 ID', group: '系统变量', description: '运行上下文租户标识' },
    { value: 'sys.roles', label: '系统变量 · 用户角色', group: '系统变量', description: '当前用户角色列表' },
    { value: 'input', label: '运行态 · 原始输入消息', group: '运行态变量', description: '本次运行的原始消息' },
    { value: 'answer', label: '运行态 · 最终回答', group: '运行态变量', description: '当前已生成的 answer' },
    { value: 'lastOutput', label: '运行态 · 上一步输出', group: '运行态变量', description: '便捷变量，适合快速串联原型' },
    { value: 'lastRoute', label: '运行态 · 命中分支', group: '运行态变量', description: '条件或意图分类节点最近一次路由' },
  ]
  for (const item of graphVariables.value) {
    if (!item.name || item.name === selectedNodeId.value) continue
    options.push({
      value: item.name,
      label: item.label || `${item.source} · ${item.name}`,
      group: item.group || '节点输出',
      description: item.description || item.name,
      nodeId: item.nodeId,
      source: item.source,
    })
    if (item.group !== '用户输入' && !item.name.startsWith('nodeOutput.')) {
      options.push({
        value: `var.${item.name}`,
        label: `${item.source} · 业务别名 ${item.name}`,
        group: '节点输出',
        description: `var.${item.name}`,
        nodeId: item.nodeId,
        source: item.source,
      })
    }
    options.push({
      value: `nodeOutput.${item.nodeId}`,
      label: `${item.source} · 节点原始输出`,
      group: '节点输出',
      description: `nodeOutput.${item.nodeId}`,
      nodeId: item.nodeId,
      source: item.source,
    })
  }
  const seen = new Set<string>()
  return options.filter((item) => {
    if (!item.value || seen.has(item.value)) return false
    seen.add(item.value)
    return true
  })
})

onMounted(async () => {
  updateViewportWidth()
  await Promise.all([
    loadStudio(),
    loadNodeTypes(),
    loadToolOptions(),
    loadCompositionOptions(),
    loadModelOptions(),
    loadKnowledgeOptions(),
  ])
  applyApiAssetRouteContext()
  resetHistorySnapshot()
  window.addEventListener('resize', updateViewportWidth)
  window.addEventListener('keydown', handleStudioShortcut)
})

onUnmounted(() => {
  window.removeEventListener('resize', updateViewportWidth)
  window.removeEventListener('keydown', handleStudioShortcut)
})

watch(
  () => [
    selectedToolInfo.value?.projectId || '',
    selectedToolInfo.value?.name || '',
  ].join(':'),
  () => {
    void refreshParamSourceHints()
  },
)

watch(
  () => currentSnapshotText(),
  () => {
    if (!historyReady.value || historyApplying.value) return
    pushHistorySnapshot()
  },
  { flush: 'post' },
)

watch(
  canvasSearchKeyword,
  () => {
    canvasSearchIndex.value = 0
    if (canvasSearchOpen.value && canvasSearchMatches.value.length) {
      focusCanvasSearchMatch(0)
    }
  },
)

watch(
  () => [
    currentDebugNodeId.value,
    debugSessionSteps.value.length,
    debugSessionSteps.value.map((step) => `${step.nodeId}:${step.status}:${step.nextNodeId || ''}`).join('|'),
  ].join('::'),
  () => refreshWorkflowNodeClasses(),
)

function updateViewportWidth() {
  viewportWidth.value = window.innerWidth
}

watch(
  workflowId,
  async (nextId, prevId) => {
    if (!nextId || nextId === prevId) return
    resetWorkflowSessionState()
    await loadStudio()
  },
)

watch(
  () => debugWaitingRequest.value?.interactionId || '',
  () => {
    for (const key of Object.keys(debugInteractionParams)) {
      delete debugInteractionParams[key]
    }
    const request = debugWaitingRequest.value
    if (!request) return
    const prefilled = request.prefilled || {}
    for (const field of debugWaitingFields.value) {
      const key = debugUiFieldKey(field)
      if (!key) continue
      debugInteractionParams[key] = prefilled[key] ?? ''
    }
  },
)

async function validateRuntime() {
  validating.value = true
  try {
    if (nodes.value.length) {
      syncJsonFromCanvas()
    }
    const { data } = await validateWorkflowRuntimeApi({
      workflowId: workflowId.value,
      graphSpecJson: graphSpecJson.value,
      runtimeType: studio.value?.runtimeType || 'LANGGRAPH4J',
    })
    validation.value = data
    if (data.valid) {
      ElMessage.success('Workflow GraphSpec 校验通过')
    }
  } finally {
    validating.value = false
  }
}

function onDragStart(event: DragEvent, kind: CanvasNodeKind) {
  if (studioReadOnly.value) {
    event.preventDefault()
    return
  }
  event.dataTransfer?.setData('application/vueflow', kind)
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
  }
}

function onDrop(event: DragEvent) {
  if (studioReadOnly.value) return
  const kind = event.dataTransfer?.getData('application/vueflow') as CanvasNodeKind | undefined
  if (!kind) return
  const position = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  insertNodeTemplate(kind, position)
}

function insertNodeTemplate(type: string, position?: { x: number; y: number }) {
  if (!studio.value) {
    ElMessage.warning('请先加载 Workflow')
    return
  }
  const kind = normalizeCanvasKind(type)
  const node = createWorkflowCanvasNode(kind, position || {
    x: 180 + nodes.value.length * 32,
    y: 160 + nodes.value.length * 18,
  }, studio.value)
  nodes.value.push(decorateWorkflowNode(node))
  selectedNodeId.value = node.id
  selectedEdgeId.value = null
  debugNodeId.value = node.id
  propertyPanelCollapsed.value = false
  markCanvasDirty()
  syncJsonFromCanvas()
  activeTab.value = 'visual'
  nextTick(() => fitView({ padding: 0.2, duration: 240 }))
}

function handleAddNode(kind: CanvasNodeKind) {
  if (studioReadOnly.value) return
  insertNodeTemplate(kind)
}

function addNodeFromSearch(kind: CanvasNodeKind) {
  handleAddNode(kind)
  nodeSearchOpen.value = false
}

function openPropertyDetail(section: PropertyDetailSection) {
  if (!selectedNode.value) return
  propertyDetailSection.value = section
  propertyDetailOpen.value = true
}

function refreshCanvasFromJson() {
  if (!studio.value) return
  const state: WorkflowStudioState = {
    ...studio.value,
    graphSpecJson: graphSpecJson.value,
    canvasJson: canvasJson.value,
  }
  applyCanvasFromStudio(state)
}

function handleCreateInteractionCallNode(request: InteractionCallNodeRequest) {
  if (studioReadOnly.value) return
  if (!studio.value || !selectedNode.value || selectedNode.value.data.kind !== 'interaction') return
  const kind: CanvasNodeKind = request.sourceKind === 'COMPOSITION' ? 'skill' : 'tool'
  const node = createWorkflowCanvasNode(kind, {
    x: selectedNode.value.position.x + 320,
    y: selectedNode.value.position.y,
  }, studio.value)
  const outputAlias = request.outputAlias || node.data.outputAlias || `${kind}_output`
  node.data.label = request.label || (kind === 'skill' ? `调用能力 ${request.ref}` : `调用工具 ${request.ref}`)
  node.data.description = request.description || node.data.description || ''
  node.data.outputAlias = outputAlias
  node.data.inputs = Object.keys(request.inputMapping || {}).map((key) => ({
    id: key,
    name: key,
    type: 'any',
    required: false,
    source: request.inputMapping[key],
  }))
  node.data.outputs = [{ id: outputAlias, name: outputAlias, type: 'any' }]
  node.data.toolConfig = {
    ...(node.data.toolConfig || { inputMapping: {} }),
    ref: request.ref,
    qualifiedName: request.qualifiedName || null,
    projectCode: request.projectCode || null,
    visibility: request.visibility || null,
    inputMapping: request.inputMapping,
    mappingNote: `由交互节点 ${selectedNode.value.id} 自动生成，可继续手动调整。`,
  }
  nodes.value.push(node)
  edges.value.push(decorateWorkflowEdge({
    id: `e-${selectedNode.value.id}-${node.id}-${Date.now()}`,
    source: selectedNode.value.id,
    target: node.id,
    condition: 'always',
    label: 'always',
  }))
  const binding = selectedNode.value.data.interactionConfig?.binding
  if (binding) {
    binding.callNodeId = node.id
  }
  selectedNodeId.value = node.id
  selectedEdgeId.value = null
  markCanvasDirty()
  nextTick(() => fitView({ padding: 0.2, duration: 240 }))
}

function onNodeClick(event: NodeMouseEvent) {
  selectedNodeId.value = event.node?.id || null
  selectedEdgeId.value = null
  if (selectedNodeId.value) {
    debugNodeId.value = selectedNodeId.value
  }
}

function onNodeDoubleClick(event: NodeMouseEvent) {
  selectedNodeId.value = event.node?.id || null
  selectedEdgeId.value = null
  propertyPanelCollapsed.value = false
  if (selectedNodeId.value) {
    debugNodeId.value = selectedNodeId.value
    openPropertyDetail('node')
  }
}

function clearSelection() {
  selectedNodeId.value = null
  selectedEdgeId.value = null
  propertyDetailOpen.value = false
}

async function handleFitView() {
  await nextTick()
  fitView({ padding: 0.18, duration: 260 })
}

function handleZoomIn() {
  zoomIn({ duration: 180 })
}

function handleZoomOut() {
  zoomOut({ duration: 180 })
}

async function handleAutoLayout() {
  autoLayoutWorkflowCanvas()
  await handleFitView()
}

function toggleSelectedNodeCollapsed() {
  if (!selectedNode.value) return
  selectedNode.value.data.collapsed = !selectedNode.value.data.collapsed
  nodes.value = nodes.value.map((node) =>
    node.id === selectedNode.value?.id ? decorateWorkflowNode({ ...node }) : node,
  )
  markCanvasDirty()
  syncJsonFromCanvas()
}

function isInputTarget(target: EventTarget | null) {
  const el = target as HTMLElement | null
  if (!el) return false
  const tag = el.tagName?.toLowerCase()
  return tag === 'input' || tag === 'textarea' || tag === 'select' || el.isContentEditable
}

function handleStudioShortcut(event: KeyboardEvent) {
  if (isInputTarget(event.target)) return
  const key = event.key.toLowerCase()
  const mod = event.ctrlKey || event.metaKey
  if (mod && key === 'f') {
    event.preventDefault()
    openCanvasSearch()
    return
  }
  if (mod && key === 'z' && event.shiftKey) {
    event.preventDefault()
    redoCanvas()
    return
  }
  if (mod && key === 'z') {
    event.preventDefault()
    undoCanvas()
    return
  }
  if (mod && key === 'y') {
    event.preventDefault()
    redoCanvas()
    return
  }
  if (mod && key === 'c') {
    event.preventDefault()
    copySelectedNode()
    return
  }
  if (mod && key === 'v') {
    event.preventDefault()
    pasteCopiedNode()
    return
  }
  if (mod && key === 'o') {
    event.preventDefault()
    void handleAutoLayout()
    return
  }
  if (mod && key === '1') {
    event.preventDefault()
    void handleFitView()
    return
  }
  if ((key === 'delete' || key === 'backspace') && canDeleteSelection.value) {
    event.preventDefault()
    deleteSelection()
  }
}

function onEdgeClick(event: EdgeMouseEvent) {
  selectedEdgeId.value = event.edge?.id || null
  selectedNodeId.value = null
  propertyPanelCollapsed.value = false
}

function focusLintItem(item: GraphLintItem) {
  if (item.nodeId) {
    selectedNodeId.value = item.nodeId
    selectedEdgeId.value = null
    propertyPanelCollapsed.value = false
    void focusDebugNode(item.nodeId)
    return
  }
  if (item.edgeId) {
    selectedEdgeId.value = item.edgeId
    selectedNodeId.value = null
    propertyPanelCollapsed.value = false
  }
}

function normalizeCanvasKind(type: string): CanvasNodeKind {
  const normalized = type.trim()
  const upper = normalized.toUpperCase()
  if (upper === 'USER_INPUT') return 'userInput'
  if (upper === 'INTERACTION') return 'interaction'
  if (upper === 'PAGE_ACTION') return 'pageAction'
  if (upper === 'LLM') return 'llm'
  if (upper === 'CAPABILITY') return 'skill'
  if (upper === 'TOOL') return 'tool'
  if (upper === 'KNOWLEDGE_RETRIEVAL') return 'knowledge'
  if (upper === 'IF_ELSE') return 'condition'
  if (upper === 'VARIABLE_ASSIGN') return 'variable'
  if (upper === 'TEMPLATE') return 'template'
  if (upper === 'PARAMETER_EXTRACT') return 'parameter'
  if (upper === 'HTTP_REQUEST') return 'http'
  if (upper === 'ANSWER') return 'answer'
  if (upper === 'CODE') return 'code'
  if (upper === 'INTENT_CLASSIFIER') return 'classifier'
  if (upper === 'VARIABLE_AGGREGATOR') return 'aggregate'
  if (upper === 'HUMAN_APPROVAL') return 'approval'
  if (upper === 'LOOP') return 'loop'
  if (upper === 'KNOWLEDGE_WRITE') return 'knowledgeWrite'
  if (upper === 'DOCUMENT_EXTRACT') return 'documentExtract'
  if (upper === 'MCP_CALL') return 'mcp'
  const allowed: CanvasNodeKind[] = [
    'start',
    'end',
    'userInput',
    'interaction',
    'pageAction',
    'llm',
    'skill',
    'tool',
    'knowledge',
    'condition',
    'variable',
    'template',
    'parameter',
    'http',
    'answer',
    'code',
    'classifier',
    'aggregate',
    'approval',
    'loop',
    'knowledgeWrite',
    'documentExtract',
    'mcp',
  ]
  return allowed.includes(normalized as CanvasNodeKind)
    ? normalized as CanvasNodeKind
    : 'tool'
}

function findNodeDescriptor(kind: CanvasNodeKind) {
  return nodeTypes.value.find((item) => normalizeCanvasKind(item.canvasKind || item.type || '') === kind)
}

function sourceLabel(value?: string | null) {
  const normalized = String(value || 'CANVAS').toUpperCase()
  if (normalized === 'SDK') return 'SDK 托管'
  if (normalized === 'CANVAS') return '画布创建'
  return value || '画布创建'
}

function nodeTraceStatusText(status: WorkflowNodeTraceState['status']) {
  if (status === 'running') return '运行中'
  if (status === 'waiting') return '等待'
  return status === 'success' ? '成功' : '失败'
}

function nodeTraceTagType(status: WorkflowNodeTraceState['status']) {
  if (status === 'running') return 'primary'
  if (status === 'waiting') return 'warning'
  return status === 'success' ? 'success' : 'danger'
}

function conditionRouteRows(data: CanvasNode['data']) {
  const config = data.conditionConfig
  const rows = (config?.groups || [])
    .filter((group) => group.id?.trim())
    .map((group) => ({
      id: group.id.trim(),
      handleId: group.id.trim(),
      label: group.label || group.id,
      meta: `${group.logic || 'AND'} · ${group.conditions?.length || 0} 条条件`,
      isDefault: false,
    }))
  const defaultRoute = (config?.defaultRoute || 'else').trim()
  if (defaultRoute && !rows.some((row) => row.handleId === defaultRoute)) {
    rows.push({
      id: `default-${defaultRoute}`,
      handleId: defaultRoute,
      label: defaultRoute === 'else' ? '默认分支' : defaultRoute,
      meta: '未命中条件时进入',
      isDefault: true,
    })
  }
  if (!rows.length) {
    for (const port of data.outputs || []) {
      const extended = port as { id?: string; name?: string; key?: string; label?: string }
      const id = String(extended.id || extended.name || extended.key || '').trim()
      if (!id) continue
      rows.push({
        id,
        handleId: id,
        label: String(extended.label || extended.name || id),
        meta: '条件分支',
        isDefault: id === 'else',
      })
    }
  }
  return rows.length ? rows : [{
    id: 'default-else',
    handleId: 'else',
    label: '默认分支',
    meta: '未命中条件时进入',
    isDefault: true,
  }]
}

function approvalRouteRows() {
  return [
    { id: 'approved', handleId: 'approved', label: '批准', meta: '审批通过' },
    { id: 'rejected', handleId: 'rejected', label: '拒绝', meta: '审批驳回' },
    { id: 'timeout', handleId: 'timeout', label: '超时', meta: '等待超时' },
  ]
}

function loopRouteRows() {
  return [
    { id: 'continue', handleId: 'continue', label: '继续', meta: '进入下一轮' },
    { id: 'done', handleId: 'done', label: '结束', meta: '循环完成' },
  ]
}

function classifierRouteRows(data: CanvasNode['data']) {
  const config = data.classifierConfig
  const rows = (config?.classes || [])
    .filter((item) => item.id?.trim())
    .map((item) => {
      const keywords = (item.keywords || []).filter(Boolean)
      return {
        id: item.id.trim(),
        handleId: item.id.trim(),
        label: item.label || item.id,
        meta: keywords.length ? keywords.slice(0, 3).join(' / ') : item.id,
        isDefault: false,
      }
    })
  const defaultRoute = (config?.defaultRoute || 'else').trim()
  if (defaultRoute && !rows.some((row) => row.handleId === defaultRoute)) {
    rows.push({
      id: `default-${defaultRoute}`,
      handleId: defaultRoute,
      label: defaultRoute === 'else' ? '默认分支' : defaultRoute,
      meta: '未命中分类时进入',
      isDefault: true,
    })
  }
  return rows.length ? rows : [{
    id: 'default-else',
    handleId: 'else',
    label: '默认分支',
    meta: '未命中分类时进入',
    isDefault: true,
  }]
}

function portSummary(ports: CanvasNode['data']['inputs'] | CanvasNode['data']['outputs'], fallback: string) {
  if (!ports?.length) return fallback
  const labels = ports
    .map((port) => port.name || port.id)
    .filter(Boolean)
    .slice(0, 2)
  return labels.length ? labels.join(', ') : fallback
}

function handleHeaderCommand(command: string | number | object) {
  if (command === 'json') {
    jsonDrawerVisible.value = true
    return
  }
  if (command === 'sync-json') {
    syncJsonFromCanvas()
    jsonDrawerVisible.value = true
    return
  }
  if (command === 'fit') {
    void handleFitView()
    return
  }
  if (command === 'layout') {
    void handleAutoLayout()
    return
  }
  if (command === 'search') {
    openCanvasSearch()
    return
  }
  if (command === 'node-debug') {
    propertyPanelCollapsed.value = false
    if (selectedNodeId.value) {
      debugNodeId.value = selectedNodeId.value
    }
    return
  }
  if (command === 'versions') {
    router.push(`/workflows/${workflowId.value}/versions`)
  }
}

function debugStepStatusClass(status?: string) {
  return `is-${debugStepStatus(status)}`
}

function debugStepClass(status?: string) {
  return debugStepStatusClass(status)
}

function debugStepTagType(status?: string) {
  const normalized = debugStepStatus(status)
  if (normalized === 'success') return 'success'
  if (normalized === 'error') return 'danger'
  if (normalized === 'waiting') return 'warning'
  return 'primary'
}

function debugStepOutput(step: WorkflowDebugStepResult) {
  return step.output ?? step.rawOutput ?? step.statePatch ?? step.uiRequest ?? step.artifact ?? null
}

function debugSessionVisualClass(status?: string) {
  const normalized = (status || '').trim().toUpperCase()
  if (normalized === 'RUNNING') return 'is-running'
  if (!normalized) return 'is-idle'
  return debugStepStatusClass(status)
}

function debugMessageRole(role?: string) {
  const normalized = (role || '').toLowerCase()
  if (normalized === 'user') return '用户'
  if (normalized === 'assistant') return '调试台'
  if (normalized === 'runtime') return '运行时'
  return '系统'
}

function isInteractiveDebugUiRequest(request?: UiRequestPayload | null) {
  const component = String(request?.component || request?.type || '').trim().toLowerCase()
  return ['form', 'text_question', 'confirm', 'select', 'choice', 'multi_select'].includes(component)
}

function shouldRenderDebugMessageUi(message: WorkflowDebugMessage) {
  return !!message.uiRequest && !isInteractiveDebugUiRequest(message.uiRequest)
}

function objectPayload(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
}

function debugWaitingOutput(step: WorkflowDebugStepResult) {
  const raw = objectPayload(step.rawOutput)
  if (raw.status === 'WAITING') return raw
  const output = objectPayload(step.output)
  const lastOutput = objectPayload(output.lastOutput)
  return lastOutput.status === 'WAITING' ? lastOutput : null
}

function uiRequestFromOutput(output: Record<string, unknown> | null) {
  const request = objectPayload(output?.uiRequest)
  return request.component || request.fields ? request as unknown as UiRequestPayload : null
}

function debugUiFieldKey(field: UiFieldPayload) {
  return String(field.key || field.name || '').trim()
}

function debugFieldLabel(field: StudioFieldSchema) {
  return `${field.name}${field.required ? ' *' : ''}`
}

function edgeKey(source?: string, target?: string) {
  return `${source || ''}->${target || ''}`
}

function textValue(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  return String(value)
}

function dateMs(value?: string) {
  if (!value) return 0
  const ms = Date.parse(value)
  return Number.isFinite(ms) ? ms : 0
}

function stringMeta(metadata: Record<string, unknown>, key: string) {
  const value = metadata[key]
  return value === null || value === undefined ? '' : String(value)
}

function workflowItemStatus(item: WorkflowPathItem): WorkflowNodeTraceState['status'] {
  const status = (item.status || item.workflowStatus || '').trim().toUpperCase()
  if (status === 'RUNNING' || status === 'EXECUTING') return 'running'
  if (status === 'WAITING') return 'waiting'
  if (status === 'ERROR' || status === 'FAILED' || status === 'FAILURE') return 'error'
  return 'success'
}

function preferNodeTraceState(previous: WorkflowNodeTraceState | undefined, next: WorkflowNodeTraceState) {
  if (!previous) return next
  if (previous.status === 'error' && next.status !== 'error') return previous
  if (next.status === 'running') return { ...previous, ...next }
  if (next.status === 'waiting') return { ...previous, ...next }
  if (previous.status === 'waiting' && next.status === 'success') return previous
  return next.createdAt && previous.createdAt && dateMs(previous.createdAt) > dateMs(next.createdAt)
    ? previous
    : { ...previous, ...next }
}

function spanToNodeTraceState(span: RunSpan): WorkflowNodeTraceState | null {
  const nodeId = (span.nodeId || '').trim()
  if (!nodeId) return null
  const metadata = span.metadata || {}
  const normalized = (span.status || '').trim().toUpperCase()
  let status: WorkflowNodeTraceState['status'] = 'success'
  if (normalized === 'RUNNING' || normalized === 'EXECUTING') status = 'running'
  else if (normalized === 'WAITING') status = 'waiting'
  else if (normalized === 'ERROR' || normalized === 'FAILED' || normalized === 'FAILURE') status = 'error'
  return {
    nodeId,
    status,
    elapsedMs: span.latencyMs,
    spanType: span.spanType,
    toolName: span.toolName,
    input: span.inputSummary,
    output: span.outputSummary,
    errorCode: span.errorCode,
    route: stringMeta(metadata, 'lastRoute') || stringMeta(metadata, 'route'),
    interactionId: stringMeta(metadata, 'interactionId'),
    createdAt: span.startedAt,
  }
}

async function handleExtractCompositionDraft() {
  if (!currentTraceId.value) {
    ElMessage.warning('请先执行调试获取 trace')
    return
  }
  const picks = traceToolPick.value.length ? traceToolPick.value : traceToolNames.value
  if (picks.length < 2) {
    ElMessage.warning('选中工具数量不足 2，无法抽取能力草稿')
    return
  }
  extracting.value = true
  try {
    const { data } = await extractDraftFromTrace({
      traceId: currentTraceId.value,
      toolNames: picks,
    })
    ElMessage.success('已生成能力草稿：' + data.name + '（ID ' + data.id + '）')
  } catch (err) {
    ElMessage.error('抽取失败：' + (err as Error).message)
  } finally {
    extracting.value = false
  }
}

function parseOptionalObject(value: string, label: string) {
  if (!value.trim()) return undefined
  const parsed = JSON.parse(value)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error(`${label} must be a JSON object`)
  }
  return parsed as Record<string, unknown>
}

function modelOptionLabel(item: ModelInstance) {
  return `${item.name || item.id} / ${item.provider || '-'} / ${item.modelName || '-'}`
}

function formatDebugResult(value: unknown) {
  return JSON.stringify(value, null, 2)
}
</script>

<style scoped lang="scss">
.workflow-studio {
  min-height: calc(100vh - 56px);
  background: var(--el-bg-color-page);
  color: var(--el-text-color-primary);
}

.studio-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}

.topbar-main,
.topbar-actions,
.meta-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.topbar-main h1 {
  margin: 0 0 6px;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0;
}

.studio-shell {
  display: grid;
  grid-template-columns: minmax(220px, 260px) minmax(0, 1fr) minmax(260px, 320px);
  min-height: calc(100vh - 122px);
}

.node-panel,
.inspect-panel {
  padding: 16px;
  background: var(--el-bg-color);
}

.node-panel {
  border-right: 1px solid var(--el-border-color-lighter);
}

.inspect-panel {
  border-left: 1px solid var(--el-border-color-lighter);
}

.editor-panel {
  min-width: 0;
  padding: 12px 16px 18px;
}

.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}

.panel-heading h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0;
}

.node-list {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.node-type {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  min-height: 38px;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-primary);
  cursor: pointer;
  text-align: left;
}

.node-type:hover {
  border-color: var(--el-color-primary);
}

.node-type span {
  overflow: hidden;
  font-size: 12px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-type em {
  flex: none;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-style: normal;
}

.json-editor :deep(textarea) {
  min-height: 620px !important;
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.55;
}

.canvas-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.canvas-tool-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}

.canvas-search-panel {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) auto auto auto auto;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-light);
}

.canvas-search-count {
  min-width: 76px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-align: right;
}

.canvas-wrap {
  height: 650px;
  min-height: 520px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
}

.workflow-canvas {
  width: 100%;
  height: 100%;
}

.workflow-canvas :deep(.vue-flow__node) {
  min-width: 148px;
  max-width: 220px;
  padding: 9px 11px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  box-shadow: var(--el-box-shadow-light);
  font-size: 12px;
}

.workflow-canvas :deep(.vue-flow__node.selected) {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
}

.workflow-canvas :deep(.vue-flow__node.run-success) {
  border-color: var(--el-color-success);
  box-shadow: 0 0 0 2px var(--el-color-success-light-8);
}

.workflow-canvas :deep(.vue-flow__node.run-error) {
  border-color: var(--el-color-danger);
  box-shadow: 0 0 0 2px var(--el-color-danger-light-8);
}

.workflow-canvas :deep(.vue-flow__node.run-waiting) {
  border-color: var(--el-color-warning);
  box-shadow: 0 0 0 2px var(--el-color-warning-light-8);
}

.workflow-canvas :deep(.vue-flow__node.run-running) {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
}

.workflow-canvas :deep(.vue-flow__node.run-current) {
  box-shadow: 0 0 0 3px var(--el-color-primary-light-7), var(--el-box-shadow-light);
}

.workflow-canvas :deep(.vue-flow__node.workflow-node-collapsed) {
  min-width: 122px;
  max-width: 150px;
  min-height: 36px;
  padding: 7px 9px;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-canvas :deep(.vue-flow__edge-path) {
  stroke: var(--el-color-primary);
}

.node-config-form {
  display: grid;
  gap: 10px;
}

.node-config-form :deep(textarea) {
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
}

.issue-list {
  display: grid;
  gap: 10px;
}

.issue {
  padding: 10px;
  border: 1px solid var(--el-color-danger-light-5);
  border-radius: 6px;
  background: var(--el-color-danger-light-9);
}

.issue strong {
  display: block;
  margin-bottom: 4px;
  font-size: 12px;
}

.issue span,
.issue p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.45;
}

.publish-warning {
  margin-bottom: 14px;
}

.publish-warning ul {
  margin: 0;
  padding-left: 18px;
}

.release-check-panel {
  display: grid;
  gap: 10px;
  margin-bottom: 14px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}

.release-check-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.release-check-head strong,
.release-check-head span {
  display: block;
}

.release-check-head span {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.release-check-collapse {
  --el-collapse-header-bg-color: transparent;
  --el-collapse-content-bg-color: transparent;
}

.check-item {
  display: grid;
  grid-template-columns: auto auto 1fr;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 12px;
}

.check-node {
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  font-family: Consolas, Monaco, 'Courier New', monospace;
}

.ai-draft-form,
.debug-form {
  display: grid;
  gap: 10px;
}

.debug-actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.debug-drawer-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.ai-draft-form :deep(textarea),
.debug-form :deep(textarea) {
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
}

.draft-preview,
.debug-result {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.draft-preview-head,
.debug-result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.debug-result-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.draft-preview-head strong,
.debug-result strong {
  font-size: 12px;
}

.draft-preview pre,
.debug-result pre {
  overflow: auto;
  max-height: 260px;
  margin: 0;
  padding: 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
}

.ai-edit-preview,
.ai-draft-preview,
.ai-draft-placeholders {
  display: grid;
  gap: 10px;
  min-width: 0;
  margin-bottom: 12px;
}

.ai-edit-preview-head,
.ai-draft-preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.ai-edit-preview-head strong,
.ai-draft-preview-head strong,
.ai-draft-placeholders strong {
  min-width: 0;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 12px;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-edit-preview-head span,
.ai-draft-preview-head span {
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.ai-edit-operation-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 8px;
}

.ai-edit-operation-group {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.ai-edit-operation-group strong {
  color: var(--el-text-color-primary);
  font-size: 12px;
}

.ai-edit-operation-group span {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-preview-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

.ai-draft-node-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  gap: 8px;
}

.ai-draft-node {
  display: grid;
  gap: 2px;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.ai-draft-node strong,
.ai-draft-node span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-draft-node strong {
  color: var(--el-text-color-primary);
  font-size: 12px;
}

.ai-draft-node span,
.ai-draft-edge-list span,
.ai-draft-placeholder {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.45;
}

.ai-draft-edge-list,
.ai-draft-placeholders {
  display: grid;
  gap: 6px;
}

.ai-draft-edge-list span,
.ai-draft-placeholder {
  min-width: 0;
  overflow: hidden;
  padding: 6px 8px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.eval-body {
  display: grid;
  gap: 14px;
}

.eval-panel {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.eval-section-head,
.eval-toolbar,
.eval-import-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.eval-section-head {
  justify-content: space-between;
}

.eval-section-head strong,
.eval-section-head span {
  display: block;
}

.eval-section-head span {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.eval-toolbar {
  flex-wrap: wrap;
}

.eval-summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.eval-metric {
  display: grid;
  gap: 6px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.eval-metric span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.eval-metric strong {
  color: var(--el-text-color-primary);
  font-size: 18px;
}

@media (max-width: 1080px) {
  .studio-topbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .studio-shell {
    grid-template-columns: 1fr;
  }

  .node-panel,
  .inspect-panel {
    border: 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }
}

.studio-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: #f5f7fa;
  color: #172033;
}

.studio-page .studio-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 12px 20px;
  border-bottom: 1px solid #e6ebf3;
  background: rgba(255, 255, 255, 0.96);
}

.header-left {
  min-width: 0;
  flex: 1 1 auto;
  gap: 12px;
}

.header-right {
  flex: 0 0 auto;
  gap: 10px;
}

.studio-title-wrap {
  min-width: 0;
  flex: 1 1 auto;
}

.studio-title-row {
  gap: 10px;
  min-width: 0;
}

.studio-eyebrow {
  flex: 0 0 auto;
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.2;
  white-space: nowrap;
}

.studio-title-row h1 {
  overflow: hidden;
  min-width: 0;
  flex: 1 1 auto;
  max-width: min(520px, calc(100vw - 760px));
  margin: 0;
  font-size: 17px;
  font-weight: 800;
  letter-spacing: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-title-row :deep(.el-tooltip__trigger) {
  display: block;
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
}

.studio-meta-row {
  gap: 8px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.studio-slug-tag {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.header-left,
.header-right,
.studio-title-row,
.studio-meta-row {
  display: flex;
  align-items: center;
}

.studio-back-btn {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-width: 82px;
  min-height: 40px;
  padding: 0 14px 0 10px;
  border: 1px solid #cdd9ec;
  border-radius: 10px;
  background: linear-gradient(180deg, #ffffff 0%, #f6f9ff 100%);
  color: #1e3a8a;
  cursor: pointer;
  font-size: 14px;
  font-weight: 700;
  line-height: 1;
  white-space: nowrap;
  box-shadow: 0 10px 24px rgba(30, 58, 138, 0.12);
  transition: transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s ease;
}

.studio-back-btn span {
  white-space: nowrap;
}

.studio-back-btn .el-icon {
  flex: 0 0 24px;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 999px;
  background: #e9f0ff;
  color: #3157ff;
  font-size: 16px;
}

.studio-back-btn:hover {
  transform: translateY(-1px);
  border-color: #9db5ff;
  box-shadow: 0 14px 30px rgba(49, 87, 255, 0.18);
}

.studio-back-btn:active {
  transform: translateY(0);
}

.save-state {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 30px;
  padding: 0 10px;
  border: 1px solid #e2e8f0;
  border-radius: 999px;
  background: rgba(248, 250, 252, 0.9);
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  white-space: nowrap;
}

.save-state i {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #94a3b8;
  box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.12);
}

.save-state.is-pending i {
  background: #f97316;
  box-shadow: 0 0 0 3px rgba(249, 115, 22, 0.16);
}

.save-state.is-pending {
  border-color: #fed7aa;
  background: #fff7ed;
  color: #c2410c;
}

.save-state.is-saving i {
  background: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15);
}

.save-state.is-saving {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
}

.save-state.is-saved i {
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.14);
}

.save-state.is-saved {
  border-color: #bbf7d0;
  background: #f0fdf4;
  color: #15803d;
}

.studio-body {
  flex: 1;
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) 430px;
  overflow: hidden;
  transition: grid-template-columns 0.2s ease;
}

.studio-body.palette-open {
  grid-template-columns: 320px minmax(0, 1fr) 430px;
}

.studio-body.property-collapsed {
  grid-template-columns: 72px minmax(0, 1fr) 58px;
}

.studio-body.palette-open.property-collapsed {
  grid-template-columns: 320px minmax(0, 1fr) 58px;
}

.palette {
  display: flex;
  min-width: 0;
  overflow: hidden;
  border-right: 1px solid #e5edf7;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.palette-rail {
  display: flex;
  flex: 0 0 72px;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 14px 10px;
  border-right: 1px solid #edf2f7;
  background: rgba(255, 255, 255, 0.82);
}

.rail-primary,
.rail-item {
  border: 0;
  cursor: pointer;
  transition: transform 0.16s ease, box-shadow 0.16s ease, background 0.16s ease;
}

.rail-primary {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  border-radius: 8px;
  background: linear-gradient(135deg, #4776ff, #6846f6);
  color: #fff;
  box-shadow: 0 12px 24px rgba(75, 97, 255, 0.28);
}

.rail-item {
  display: grid;
  place-items: center;
  width: 48px;
  min-height: 54px;
  border-radius: 8px;
  background: transparent;
  color: #64748b;
}

.rail-item .rail-icon {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: #eef4ff;
  color: #3157ff;
  font-size: 16px;
}

.rail-item em {
  margin-top: 3px;
  font-size: 11px;
  font-style: normal;
  line-height: 1;
}

.rail-item:hover,
.rail-item.active {
  background: #f0f5ff;
  color: #3157ff;
  box-shadow: inset 3px 0 0 #3157ff;
}

.rail-spacer {
  flex: 1;
}

.palette-content {
  display: none;
  flex: 1;
  min-width: 0;
  padding: 16px 14px;
  overflow-y: auto;
}

.studio-body.palette-open .palette-content {
  display: block;
}

.panel-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 14px;
}

.palette-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 14px;
}

.palette-head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 800;
}

.palette-title {
  font-size: 16px;
  font-weight: 700;
  margin-bottom: 4px;
  color: #111827;
}

.palette-subtitle {
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
  margin-bottom: 12px;
}

.palette-search {
  margin-bottom: 12px;
}

.node-search {
  margin-bottom: 12px;
}

.palette-group {
  margin-bottom: 14px;
}

.palette-group-title {
  color: #475569;
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 8px;
}

.palette-item {
  border: 1px solid #e6edf7;
  border-left-width: 3px;
  border-radius: 8px;
  padding: 10px 11px;
  margin-bottom: 8px;
  cursor: grab;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 6px 16px rgba(15, 23, 42, 0.045);
  transition: transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s ease;
}

.palette-item:hover {
  transform: translateY(-1px);
  border-color: #cbd5e1;
  box-shadow: 0 14px 28px rgba(15, 23, 42, 0.08);
}

.palette-item:active {
  cursor: grabbing;
}

.palette-item-head {
  display: grid;
  grid-template-columns: 32px 1fr;
  gap: 9px;
  align-items: center;
  min-width: 0;
}

.palette-item-icon,
.palette-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--node-border);
  border-radius: 8px;
  background: var(--node-bg);
  color: var(--node-border);
}

.palette-item-desc {
  display: -webkit-box;
  overflow: hidden;
  color: #64748b;
  font-size: 11px;
  line-height: 1.45;
  margin-top: 6px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.palette-item-main {
  min-width: 0;
}

.palette-item-title,
.palette-item-meta,
.palette-item-hint {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.palette-item-title {
  color: #1f2937;
  font-weight: 700;
  font-size: 13px;
}

.palette-item-meta {
  color: #94a3b8;
  font-size: 11px;
  margin-top: 2px;
}

.palette-tips {
  color: #64748b;
  font-size: 12px;
  line-height: 1.8;
}

.palette-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 160px;
  color: #94a3b8;
  font-size: 12px;
}

.studio-page .canvas-wrap {
  position: relative;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  border: 0;
  border-radius: 0;
  background: #f8fbff;
}

.studio-canvas {
  width: 100%;
  height: 100%;
}

.studio-canvas :deep(.vue-flow__pane) {
  cursor: grab;
}

.studio-canvas :deep(.vue-flow__pane.dragging) {
  cursor: grabbing;
}

.studio-canvas :deep(.vue-flow__node) {
  border: 0;
  background: transparent;
  box-shadow: none;
}

.studio-canvas :deep(.vue-flow__node.selected .studio-node) {
  box-shadow: 0 0 0 3px rgba(111, 99, 255, 0.18), 0 12px 28px rgba(46, 54, 80, 0.12);
}

.studio-canvas :deep(.vue-flow__edge-path) {
  stroke: #7b879a;
  stroke-width: 2;
}

.studio-canvas :deep(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke: #6157ff;
  stroke-width: 2.5;
}

.studio-canvas :deep(.vue-flow__controls) {
  left: 22px;
  bottom: 28px;
  border: 1px solid #e5eaf2;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 12px 32px rgba(31, 41, 55, 0.12);
}

.studio-canvas :deep(.vue-flow__minimap) {
  right: 34px;
  bottom: 34px;
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 12px 28px rgba(31, 41, 55, 0.1);
}

.studio-canvas :deep(.vue-flow__handle) {
  width: 10px;
  height: 10px;
  border: 2px solid #ffffff;
  background: #1f2937;
}

.canvas-operator {
  position: absolute;
  left: 22px;
  right: auto;
  bottom: 86px;
  z-index: 8;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid rgba(148, 163, 184, 0.35);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 18px 44px rgba(15, 23, 42, 0.16);
  backdrop-filter: blur(12px);
  transition: width 0.18s ease, padding 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;
}

.canvas-operator .el-button {
  margin-left: 0;
}

.canvas-operator.collapsed {
  padding: 6px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.86);
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.13);
}

.canvas-search-panel {
  position: absolute;
  top: 76px;
  right: 24px;
  z-index: 5;
  display: grid;
  grid-template-columns: minmax(220px, 1fr) auto auto auto auto;
  width: min(620px, calc(100% - 48px));
  margin: 0;
  box-shadow: 0 14px 36px rgba(31, 41, 55, 0.12);
}

.workflow-inspector {
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: 72px;
  z-index: 5;
  display: grid;
  grid-template-columns: minmax(260px, 1.15fr) minmax(220px, 0.85fr);
  gap: 10px;
  pointer-events: none;
}

.inspector-section {
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid rgba(148, 163, 184, 0.28);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 14px 36px rgba(15, 23, 42, 0.08);
  pointer-events: auto;
}

.inspector-list {
  display: grid;
  gap: 6px;
}

.inspector-item {
  display: grid;
  grid-template-columns: 54px 1fr;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
  padding: 6px 8px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
  text-align: left;
}

.inspector-item.error {
  border-color: #fecaca;
  background: #fff5f5;
}

.inspector-item.warning {
  border-color: #fed7aa;
  background: #fffbeb;
}

.canvas-statusbar {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 7;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 10px;
  min-height: 56px;
  padding: 8px 18px;
  border-top: 1px solid rgba(148, 163, 184, 0.28);
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(12px);
}

.status-left {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  min-width: 0;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid #dbe5f2;
  border-radius: 8px;
  background: #fff;
  color: #334155;
  cursor: pointer;
  font-size: 12px;
}

.status-pill strong,
.status-pill em {
  font-style: normal;
  line-height: 1;
}

.status-pill strong {
  font-weight: 700;
}

.status-pill em {
  color: #64748b;
}

.status-pill.danger {
  border-color: #fecaca;
  color: #b91c1c;
}

.status-pill.warning {
  border-color: #fed7aa;
  color: #c2410c;
}

.smart-edit-pill {
  border-color: rgba(99, 102, 241, 0.3);
  color: #4338ca;
  background: linear-gradient(135deg, rgba(238, 242, 255, 0.96), rgba(240, 253, 250, 0.86));
  box-shadow: 0 10px 24px rgba(79, 70, 229, 0.1);
}

.property-empty-state {
  display: grid;
  gap: 8px;
  padding: 20px 18px;
  border: 1px dashed #c8d5e8;
  border-radius: 12px;
  background:
    radial-gradient(circle at top left, rgba(49, 87, 255, 0.08), transparent 36%),
    linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
  color: #64748b;
  text-align: center;
}

.property-empty-state .empty-state-icon {
  display: grid;
  width: 40px;
  height: 40px;
  margin: 0 auto 2px;
  place-items: center;
  border-radius: 12px;
  background: #eef4ff;
  color: #3157ff;
  font-size: 18px;
}

.property-empty-state strong {
  color: #0f172a;
  font-size: 14px;
}

.property-empty-state span {
  font-size: 12px;
  line-height: 1.55;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #22c55e;
}

.status-pill.danger .status-dot {
  background: #ef4444;
}

.status-pill.warning .status-dot {
  background: #f59e0b;
}

.condition-help {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.route-quick-picks {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}

.api-query-template-toolbar {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 160px 180px auto;
  gap: 10px;
  margin: 12px 0;
}

.api-template-cell {
  display: grid;
  gap: 2px;
}

.api-template-cell span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.api-query-template-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.ai-draft-body {
  display: grid;
  gap: 12px;
}

.ai-draft-model-row {
  display: grid;
  grid-template-columns: 88px 1fr;
  align-items: center;
  gap: 10px;
}

.ai-draft-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.ai-draft-edge {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.inspector-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.9);
  color: #334155;
  box-shadow: 0 10px 26px rgba(31, 41, 55, 0.08);
  font-size: 12px;
  font-weight: 700;
}

.ai-edit-bar {
  position: absolute;
  right: 22px;
  bottom: 18px;
  left: 22px;
  z-index: 10;
  display: grid;
  gap: 10px;
  max-width: 860px;
  margin: 0 auto;
  padding: 18px 20px 16px;
  border: 1px solid transparent;
  border-radius: 28px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.74), rgba(241, 245, 249, 0.44)) padding-box,
    linear-gradient(135deg, rgba(255, 255, 255, 0.92), rgba(129, 140, 248, 0.28)) border-box;
  box-shadow:
    0 24px 70px rgba(15, 23, 42, 0.16),
    inset 0 1px 0 rgba(255, 255, 255, 0.68);
  backdrop-filter: blur(22px) saturate(1.18);
  isolation: isolate;
  overflow: hidden;
}

.ai-edit-bar::before,
.ai-edit-bar::after {
  content: '';
  position: absolute;
  pointer-events: none;
}

.ai-edit-bar::before {
  inset: -2px;
  z-index: 0;
  border-radius: inherit;
  background: conic-gradient(from 140deg, #7c3aed, #06b6d4, #22c55e, #f472b6, #7c3aed);
  opacity: 0;
  filter: blur(1px);
}

.ai-edit-bar::after {
  inset: 1px;
  z-index: 1;
  border-radius: 27px;
  background:
    radial-gradient(circle at 12% 0%, rgba(129, 140, 248, 0.12), transparent 30%),
    radial-gradient(circle at 88% 12%, rgba(34, 211, 238, 0.12), transparent 30%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.68), rgba(248, 250, 252, 0.5));
  backdrop-filter: blur(22px) saturate(1.18);
}

.ai-edit-bar > * {
  position: relative;
  z-index: 2;
}

.ai-edit-bar.is-generating::before {
  opacity: 0.78;
  animation: debugAuraSpin 5.8s linear infinite, debugPulseGlow 2.4s ease-in-out infinite;
}

.ai-edit-bar.debug-drawer-open {
  right: calc(min(960px, 58vw) + 22px);
  max-width: none;
  margin-left: 0;
}

.ai-edit-input-row {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.ai-edit-main-input :deep(.el-textarea__inner) {
  min-height: 56px !important;
  padding: 4px 4px 0;
  border: 0;
  background: transparent;
  box-shadow: none;
  color: #0f172a;
  font-size: 17px;
  line-height: 1.55;
}

.ai-edit-main-input :deep(.el-textarea__inner::placeholder) {
  color: rgba(51, 65, 85, 0.58);
}

.ai-edit-toolbar,
.ai-edit-toolbar-left,
.ai-edit-toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ai-edit-toolbar {
  justify-content: space-between;
}

.ai-edit-minimize,
.ai-edit-model-trigger,
.ai-edit-send {
  width: 38px;
  height: 38px;
  border: 0;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.08);
}

.ai-edit-minimize,
.ai-edit-model-trigger {
  background: rgba(248, 250, 252, 0.86);
  color: #1f2937;
}

.ai-edit-model-trigger.active {
  color: #2563eb;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.15), 0 12px 24px rgba(37, 99, 235, 0.12);
}

.ai-edit-send:not(.is-disabled) {
  background: #2563eb;
  color: #ffffff;
  box-shadow: 0 16px 32px rgba(37, 99, 235, 0.24);
}

.ai-edit-model-panel {
  display: grid;
  gap: 10px;
}

.ai-edit-model-panel .el-select {
  width: 100%;
}

.ai-edit-model-title {
  color: #0f172a;
  font-size: 13px;
  font-weight: 800;
}

.ai-edit-model-hint {
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.property-panel {
  position: relative;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
  border-left: 1px solid #e5edf7;
  overflow: hidden;
}

.property-toggle {
  position: absolute;
  top: 14px;
  right: 14px;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 1px solid #dbe5f2;
  border-radius: 999px;
  background: #fff;
  color: #64748b;
  cursor: pointer;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.08);
  transition: border-color 0.16s ease, color 0.16s ease, transform 0.16s ease;
}

.property-toggle:hover {
  transform: translateX(-1px);
  border-color: #bfd0ff;
  color: #3157ff;
}

.property-content {
  height: 100%;
  padding: 18px 16px;
  overflow-y: auto;
}

.property-compact {
  display: flex;
  height: 100%;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 54px 8px 14px;
  color: #64748b;
  writing-mode: vertical-rl;
  text-orientation: mixed;
}

.property-compact strong,
.property-compact span {
  overflow: hidden;
  max-height: 220px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.property-compact strong {
  color: #0f172a;
  font-size: 13px;
}

.property-compact span {
  font-size: 12px;
}

.node-property-overview {
  display: grid;
  gap: 16px;
}

.node-property-head {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  padding: 8px 2px 14px;
  border-bottom: 1px solid #eef2f7;
}

.node-property-icon {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: 10px;
  font-size: 18px;
}

.node-property-title {
  min-width: 0;
}

.node-property-title > div {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.node-property-title strong {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #0f172a;
  font-size: 16px;
  font-weight: 800;
  white-space: nowrap;
}

.node-property-title span {
  display: block;
  margin-top: 6px;
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  white-space: normal;
}

.node-property-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.property-section-card {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  width: 100%;
  min-height: 74px;
  padding: 14px 12px;
  border: 1px solid #e4eaf3;
  border-radius: 8px;
  background: #fff;
  color: inherit;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;
}

.property-section-card:hover {
  transform: translateY(-1px);
  border-color: #bfd0ff;
  box-shadow: 0 14px 32px rgba(15, 23, 42, 0.08);
}

.section-card-icon {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: 1px solid #e1e8f5;
  border-radius: 8px;
  background: #f8fbff;
  color: #3157ff;
  font-size: 16px;
}

.section-card-main {
  min-width: 0;
}

.section-card-main strong {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #0f172a;
  font-size: 14px;
  font-weight: 800;
  white-space: nowrap;
}

.section-card-main em {
  display: block;
  margin-top: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #64748b;
  font-size: 12px;
  font-style: normal;
  white-space: nowrap;
}

.section-card-meta {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  min-width: 0;
  color: #64748b;
}

.node-search-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 16px;
}

.node-search-card {
  display: grid;
  gap: 8px;
  padding: 14px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  text-align: left;
}

.node-search-card span:first-child {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 10px;
  font-size: 16px;
}

.node-search-card strong {
  color: #0f172a;
  font-size: 14px;
}

.node-search-card em {
  color: #64748b;
  font-size: 12px;
  font-style: normal;
  line-height: 1.45;
}

.node-dialog-header {
  display: flex;
  align-items: center;
  gap: 14px;
}

.node-dialog-icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 12px;
  background: color-mix(in srgb, var(--node-accent, #4f46e5) 12%, white);
  color: var(--node-accent, #4f46e5);
  font-size: 20px;
}

.node-dialog-title strong {
  display: block;
  color: #0f172a;
  font-size: 18px;
  font-weight: 800;
}

.node-dialog-title span {
  color: #64748b;
  font-size: 12px;
}

.node-dialog-tags {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.node-detail-card {
  padding: 16px;
  border: 1px solid #e5eaf2;
  border-radius: 12px;
  background: #fff;
}

.node-detail-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.node-detail-card-head strong {
  display: block;
  color: #0f172a;
  font-size: 14px;
  font-weight: 800;
}

.node-detail-card-head span {
  display: block;
  margin-top: 4px;
  color: #64748b;
  font-size: 12px;
}

.node-contract-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.contract-chip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid #e8edf5;
  border-radius: 10px;
  background: #f8fafc;
  font-size: 12px;
  color: #64748b;
}

.node-debug-box,
.node-trace-panel {
  display: grid;
  gap: 12px;
}

.node-debug-head,
.node-trace-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.node-trace-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: #64748b;
  font-size: 12px;
}

.node-trace-block {
  display: grid;
  gap: 6px;
}

.node-trace-block span {
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
}

.node-trace-block pre {
  margin: 0;
  padding: 12px;
  border-radius: 10px;
  background: #0f172a;
  color: #e2e8f0;
  font-size: 12px;
  line-height: 1.5;
  overflow: auto;
}

.empty-property-card,
.section-card {
  padding: 16px;
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  background: #ffffff;
}

.empty-property-card {
  display: grid;
  place-items: center;
  min-height: 138px;
  color: #64748b;
  text-align: center;
}

.empty-property-card .el-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  margin-bottom: 12px;
  border-radius: 12px;
  background: #eef2ff;
  color: #5b6cff;
}

.empty-property-card strong,
.section-title strong {
  color: #111827;
  font-size: 14px;
  font-weight: 900;
}

.empty-property-card p,
.section-title span {
  margin: 6px 0 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
}

.property-section {
  margin-top: 18px;
}

.property-section h3 {
  margin: 0 0 10px;
  color: #111827;
  font-size: 14px;
  font-weight: 900;
}

.node-meta-grid,
.workflow-meta-grid {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  gap: 8px 10px;
  align-items: center;
  color: #475569;
  font-size: 12px;
}

.node-meta-grid span,
.workflow-meta-grid span {
  color: #64748b;
}

.node-meta-grid strong,
.workflow-meta-grid strong {
  overflow: hidden;
  color: #172033;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.debug-compact {
  display: grid;
  gap: 10px;
}

.debug-mini-actions {
  display: flex;
  gap: 8px;
}

.debug-mini-actions .el-button {
  flex: 1;
  margin: 0;
}

.ai-preview-section {
  margin-top: 18px;
}

.studio-page .draft-preview pre,
.studio-page .debug-result pre,
.studio-page .debug-step-payloads pre {
  max-height: 180px;
  font-size: 11px;
}

.studio-page .studio-node {
  --node-color: #2563eb;
  --node-soft: rgba(37, 99, 235, 0.1);
  --node-line: rgba(37, 99, 235, 0.2);
  position: relative;
  width: auto;
  min-width: 204px;
  max-width: 250px;
  min-height: 118px;
  padding: 20px 14px 0;
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, 0.28);
  border-radius: 12px;
  border-top: 1px solid rgba(148, 163, 184, 0.28);
  background:
    radial-gradient(circle at 82% 0%, var(--node-soft), transparent 34%),
    linear-gradient(180deg, #ffffff 0%, #fbfdff 100%);
  color: #0f172a;
  font-size: 13px;
  box-shadow: 0 14px 32px rgba(15, 23, 42, 0.11);
  transition:
    border-color 0.16s ease,
    box-shadow 0.16s ease,
    transform 0.16s ease;
}

.studio-page .studio-node::before {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 4px;
  background: linear-gradient(90deg, var(--node-color), color-mix(in srgb, var(--node-color) 56%, #ffffff));
  content: '';
}

.studio-page .studio-node:hover {
  transform: translateY(-1px);
  border-color: color-mix(in srgb, var(--node-color) 52%, #dbe5f0);
  box-shadow: 0 18px 42px rgba(15, 23, 42, 0.14);
}

.studio-page .studio-node .node-icon {
  position: absolute;
  top: 20px;
  left: 14px;
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: 1px solid var(--node-line);
  border-radius: 9px;
  background: var(--node-soft);
  color: var(--node-color);
  font-size: 17px;
  line-height: 1;
}

.studio-page .studio-node .node-icon .el-icon {
  font-size: 17px;
}

.studio-page .studio-node .node-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  min-height: 30px;
  margin: 0 0 8px 42px;
}

.studio-page .studio-node .node-kind {
  display: inline-flex;
  align-items: center;
  max-width: 104px;
  min-height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.045);
  color: #475569;
  font-size: 11px;
  font-weight: 800;
  line-height: 22px;
  white-space: nowrap;
}

.studio-page .studio-node .node-state,
.studio-page .studio-node .node-kicker {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 0 8px;
  border: 1px solid var(--node-line);
  border-radius: 999px;
  background: var(--node-soft);
  color: var(--node-color);
  font-size: 11px;
  font-weight: 800;
  line-height: 22px;
  white-space: nowrap;
}

.studio-page .studio-node .node-kicker {
  margin-left: 42px;
  margin-bottom: 8px;
}

.studio-page .studio-node .node-label {
  overflow: visible;
  color: #0f172a;
  font-size: 15px;
  font-weight: 800;
  line-height: 1.35;
  text-overflow: clip;
  white-space: normal;
  word-break: break-all;
}

.studio-page .studio-node .node-kicker + .node-label,
.studio-page .studio-node .node-head + .node-label {
  margin-left: 42px;
}

.studio-page .studio-node .node-desc {
  min-height: 34px;
  margin-top: 9px;
  padding: 8px 10px;
  overflow: hidden;
  border-radius: 8px;
  background: rgba(248, 250, 252, 0.86);
  color: #475569;
  font-size: 11px;
  line-height: 1.45;
  text-overflow: clip;
  white-space: normal;
}

.studio-page .studio-node .node-alias {
  display: inline-flex;
  max-width: 100%;
  margin-top: 8px;
  padding: 3px 7px;
  border: 1px solid rgba(14, 165, 233, 0.18);
  border-radius: 6px;
  background: rgba(14, 165, 233, 0.08);
  color: #0369a1;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  font-size: 11px;
}

.studio-page .node-port-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 10px -14px 0;
  padding: 8px 14px;
  border-top: 1px solid rgba(226, 232, 240, 0.9);
  background: rgba(248, 250, 252, 0.76);
  color: #64748b;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.35;
}

.studio-page .studio-node.collapsed {
  width: auto;
  min-width: 170px;
  min-height: 64px;
  padding: 18px 14px 14px 58px;
}

.studio-page .studio-node.collapsed .node-desc,
.studio-page .studio-node.collapsed .node-port-row,
.studio-page .studio-node.collapsed .node-alias {
  display: none;
}

.studio-page .studio-node.collapsed .node-head,
.studio-page .studio-node.collapsed .node-kicker,
.studio-page .studio-node.collapsed .node-label {
  margin-left: 0;
}

.studio-page .studio-node.collapsed .node-head {
  min-height: 0;
  margin-bottom: 4px;
}

.studio-page .studio-node.collapsed .node-label {
  font-size: 14px;
}

.studio-page .studio-node.collapsed .node-icon {
  top: 18px;
  left: 14px;
}

.studio-page .node-runtime {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: auto;
  margin-top: 9px;
  padding: 3px 7px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.7);
  color: #334155;
  cursor: pointer;
  font-size: 11px;
  font-weight: 700;
}

.studio-page .runtime-dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #22c55e;
}

.studio-page .run-error .node-runtime .runtime-dot {
  background: #ef4444;
}

.studio-page .run-waiting .node-runtime .runtime-dot {
  background: #f59e0b;
}

.studio-page .run-running .node-runtime .runtime-dot {
  background: #6366f1;
}

.studio-page .studio-node.run-success {
  border-color: rgba(34, 197, 94, 0.58);
  box-shadow: 0 0 0 2px rgba(34, 197, 94, 0.18), 0 16px 38px rgba(15, 23, 42, 0.12);
}

.studio-page .studio-node.run-running {
  --node-color: #6366f1;
  --node-soft: rgba(99, 102, 241, 0.08);
  --node-line: rgba(99, 102, 241, 0.22);
  border-color: rgba(99, 102, 241, 0.62);
  box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.2), 0 16px 38px rgba(15, 23, 42, 0.13);
}

.studio-page .studio-node.run-error {
  --node-color: #ef4444;
  --node-soft: rgba(239, 68, 68, 0.08);
  --node-line: rgba(239, 68, 68, 0.24);
  border-color: rgba(239, 68, 68, 0.58);
  box-shadow: 0 0 0 2px rgba(239, 68, 68, 0.2), 0 16px 38px rgba(15, 23, 42, 0.13);
}

.studio-page .studio-node.run-waiting {
  --node-color: #f59e0b;
  --node-soft: rgba(245, 158, 11, 0.1);
  --node-line: rgba(245, 158, 11, 0.26);
  border-color: rgba(245, 158, 11, 0.62);
  box-shadow: 0 0 0 2px rgba(245, 158, 11, 0.2), 0 16px 38px rgba(15, 23, 42, 0.13);
}

.studio-page .studio-node.run-current {
  border-color: rgba(99, 102, 241, 0.76);
  box-shadow:
    0 0 0 3px rgba(99, 102, 241, 0.22),
    0 18px 42px rgba(15, 23, 42, 0.16);
  transform: translateY(-2px);
}

.studio-page .start-node {
  --node-color: #22c55e;
  --node-soft: rgba(34, 197, 94, 0.1);
  --node-line: rgba(34, 197, 94, 0.24);
}

.studio-page .condition-routes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 9px;
}

.studio-page .condition-route {
  display: inline-flex;
  min-height: 22px;
  align-items: center;
  padding: 0 8px;
  border: 1px solid rgba(249, 115, 22, 0.2);
  border-radius: 999px;
  background: rgba(255, 247, 237, 0.86);
  color: #c2410c;
  font-size: 11px;
  font-weight: 800;
  line-height: 22px;
}

.studio-page .classifier-routes {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.studio-page .classifier-route-row {
  position: relative;
  display: flex;
  min-height: 42px;
  align-items: center;
  padding: 8px 28px 8px 10px;
  border: 1px solid rgba(249, 115, 22, 0.22);
  border-radius: 10px;
  background: rgba(255, 247, 237, 0.88);
}

.studio-page .classifier-route-row.is-default {
  border-style: dashed;
  background: rgba(248, 250, 252, 0.94);
}

.studio-page .classifier-route-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.studio-page .classifier-route-copy strong {
  overflow: hidden;
  color: #9a3412;
  font-size: 12px;
  font-weight: 850;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-page .classifier-route-copy span {
  overflow: hidden;
  color: #64748b;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-canvas :deep(.vue-flow__handle.classifier-route-handle) {
  top: 50%;
  right: -9px;
  width: 13px;
  height: 13px;
  border: 3px solid #dbeafe;
  background: #2563eb;
  transform: translateY(-50%);
}

.studio-page .end-node,
.studio-page .answer-node {
  --node-color: #16a34a;
  --node-soft: rgba(22, 163, 74, 0.1);
  --node-line: rgba(22, 163, 74, 0.24);
}

.studio-page .llm-node {
  --node-color: #635bff;
  --node-soft: rgba(99, 91, 255, 0.1);
  --node-line: rgba(99, 91, 255, 0.24);
}

.studio-page .skill-node {
  --node-color: #d97706;
  --node-soft: rgba(217, 119, 6, 0.1);
  --node-line: rgba(217, 119, 6, 0.24);
}

.studio-page .tool-node {
  --node-color: #2563eb;
  --node-soft: rgba(37, 99, 235, 0.1);
  --node-line: rgba(37, 99, 235, 0.24);
}

.studio-page .knowledge-node {
  --node-color: #0d9488;
  --node-soft: rgba(13, 148, 136, 0.1);
  --node-line: rgba(13, 148, 136, 0.24);
}

.studio-page .user-input-node {
  --node-color: #10b981;
  --node-soft: rgba(16, 185, 129, 0.1);
  --node-line: rgba(16, 185, 129, 0.24);
}

.studio-page .interaction-node {
  --node-color: #14b8a6;
  --node-soft: rgba(20, 184, 166, 0.1);
  --node-line: rgba(20, 184, 166, 0.24);
}

.studio-page .page-action-node {
  --node-color: #8b5cf6;
  --node-soft: rgba(139, 92, 246, 0.1);
  --node-line: rgba(139, 92, 246, 0.24);
}

.studio-page .condition-node {
  --node-color: #f97316;
  --node-soft: rgba(249, 115, 22, 0.11);
  --node-line: rgba(249, 115, 22, 0.26);
}

.studio-page .variable-node {
  --node-color: #64748b;
  --node-soft: rgba(100, 116, 139, 0.1);
  --node-line: rgba(100, 116, 139, 0.24);
}

.studio-page .template-node {
  --node-color: #a21caf;
  --node-soft: rgba(162, 28, 175, 0.1);
  --node-line: rgba(162, 28, 175, 0.24);
}

.studio-page .parameter-node {
  --node-color: #db2777;
  --node-soft: rgba(219, 39, 119, 0.09);
  --node-line: rgba(219, 39, 119, 0.22);
}

.studio-page .http-node,
.studio-page .mcp-node {
  --node-color: #4f46e5;
  --node-soft: rgba(79, 70, 229, 0.1);
  --node-line: rgba(79, 70, 229, 0.24);
}

.studio-page .code-node,
.studio-page .document-extract-node {
  --node-color: #475569;
  --node-soft: rgba(71, 85, 105, 0.1);
  --node-line: rgba(71, 85, 105, 0.24);
}

.studio-page .classifier-node {
  --node-color: #ea580c;
  --node-soft: rgba(234, 88, 12, 0.1);
  --node-line: rgba(234, 88, 12, 0.24);
  min-width: 278px;
  max-width: 320px;
}

.studio-page .aggregate-node {
  --node-color: #0891b2;
  --node-soft: rgba(8, 145, 178, 0.1);
  --node-line: rgba(8, 145, 178, 0.24);
}

.studio-page .approval-node {
  --node-color: #ca8a04;
  --node-soft: rgba(202, 138, 4, 0.1);
  --node-line: rgba(202, 138, 4, 0.24);
}

.studio-page .loop-node {
  --node-color: #0284c7;
  --node-soft: rgba(2, 132, 199, 0.1);
  --node-line: rgba(2, 132, 199, 0.24);
}

.studio-page .knowledge-write-node {
  --node-color: #e11d48;
  --node-soft: rgba(225, 29, 72, 0.09);
  --node-line: rgba(225, 29, 72, 0.22);
}

.studio-page .studio-node.needs-config {
  border-style: dashed;
  border-color: #f59e0b;
  background: #fffbeb;
}

.studio-canvas :deep(.vue-flow__node.selected .studio-node) {
  border-color: var(--node-color);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--node-color) 24%, transparent), 0 22px 52px rgba(15, 23, 42, 0.18);
}

.studio-canvas :deep(.vue-flow__node.selected .studio-node::before) {
  height: 5px;
}

.studio-canvas :deep(.vue-flow__handle) {
  width: 10px;
  height: 10px;
  border: 2px solid #ffffff;
  background: #94a3b8;
  box-shadow: 0 0 0 1px #64748b, 0 2px 6px rgba(15, 23, 42, 0.16);
}

.studio-canvas :deep(.vue-flow__node.selected .vue-flow__handle) {
  background: #ffffff;
  box-shadow: 0 0 0 2px #2563eb, 0 0 0 6px rgba(37, 99, 235, 0.12);
}

:global(.node-property-dialog.el-dialog) {
  max-width: calc(100vw - 48px);
  overflow: hidden;
  border: 1px solid rgba(226, 232, 240, 0.92);
  border-radius: 18px;
  background: #f8fafc;
  box-shadow: 0 30px 80px rgba(15, 23, 42, 0.28);
}

:global(.node-property-dialog.el-dialog .el-dialog__header) {
  margin: 0;
  padding: 18px 22px 16px;
  border-bottom: 1px solid #e6edf7;
  background:
    linear-gradient(135deg, rgba(79, 70, 229, 0.08), rgba(14, 165, 233, 0.05) 44%, rgba(255, 255, 255, 0.88)),
    #ffffff;
}

:global(.node-property-dialog.el-dialog .el-dialog__body) {
  max-height: min(74vh, 760px);
  overflow: auto;
  padding: 18px 22px 22px;
}

.canvas-operator-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 36px;
  padding: 0 11px;
  border: 1px solid rgba(129, 140, 248, 0.22);
  border-radius: 999px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.94), rgba(238, 242, 255, 0.82)),
    radial-gradient(circle at 18% 12%, rgba(99, 102, 241, 0.12), transparent 48%);
  color: #4f46e5;
  cursor: pointer;
  font-size: 13px;
  font-weight: 800;
  line-height: 1;
  white-space: nowrap;
}

.canvas-operator-toggle:hover {
  transform: translateY(-1px);
  border-color: rgba(99, 102, 241, 0.42);
  box-shadow:
    0 12px 24px rgba(79, 70, 229, 0.18),
    inset 0 1px 0 rgba(255, 255, 255, 0.86);
}

:global(.studio-debug-drawer-overlay) {
  pointer-events: none;
}

:global(.studio-debug-drawer-overlay .studio-debug-drawer) {
  pointer-events: auto;
  min-width: min(760px, 100vw);
  border-left: 1px solid rgba(191, 219, 254, 0.72);
  background:
    radial-gradient(circle at 18% 8%, rgba(129, 140, 248, 0.14), transparent 32%),
    radial-gradient(circle at 76% 18%, rgba(34, 211, 238, 0.13), transparent 28%),
    linear-gradient(135deg, rgba(248, 250, 252, 0.78), rgba(239, 246, 255, 0.62));
  box-shadow: -26px 0 72px rgba(15, 23, 42, 0.16);
  backdrop-filter: blur(20px) saturate(1.18);
}

:global(.studio-debug-drawer-overlay .studio-debug-drawer.rtl.open),
:global(.studio-debug-drawer.rtl.open) {
  transform: translateX(0) !important;
}

:global(.studio-debug-drawer-overlay .studio-debug-drawer .el-drawer__header) {
  display: flex;
  align-items: center;
  margin-bottom: 0;
  padding: 22px 28px 18px;
  border-bottom: 1px solid rgba(226, 232, 240, 0.72);
  background: rgba(255, 255, 255, 0.42);
  backdrop-filter: blur(16px) saturate(1.18);
}

:global(.studio-debug-drawer-overlay .studio-debug-drawer .el-drawer__close-btn) {
  color: #334155;
}

:global(.studio-debug-drawer-overlay .studio-debug-drawer .el-drawer__title) {
  color: #0f172a;
  font-weight: 800;
  letter-spacing: 0;
}

:global(.studio-debug-drawer-overlay .studio-debug-drawer .el-drawer__body) {
  padding: 18px 0 0;
  overflow: hidden;
  background: transparent;
}

.debug-body {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 82px);
  min-height: 0;
  padding: 0 20px 22px;
  position: relative;
  overflow: hidden;
  color: #0f172a;
}

.debug-session-grid {
  display: grid;
  grid-template-columns: minmax(360px, 1fr) minmax(300px, 0.92fr);
  align-items: stretch;
  gap: 14px;
  flex: 1;
  min-height: 0;
}

.debug-chat-panel,
.debug-steps-panel {
  min-width: 0;
  min-height: 0;
  height: 100%;
  padding: 14px;
  border: 1px solid rgba(203, 213, 225, 0.64);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.74);
  box-shadow: 0 22px 56px rgba(15, 23, 42, 0.1);
  backdrop-filter: blur(18px) saturate(1.16);
}

.debug-steps-panel {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 18px 46px rgba(15, 23, 42, 0.07);
}

.debug-chat-panel {
  display: flex;
  min-height: 0;
  flex-direction: column;
  position: relative;
  isolation: isolate;
  overflow: hidden;
  border-color: transparent;
  background:
    linear-gradient(rgba(255, 255, 255, 0.72), rgba(248, 250, 252, 0.58)) padding-box,
    linear-gradient(135deg, rgba(255, 255, 255, 0.9), rgba(148, 163, 184, 0.34)) border-box;

  &::before,
  &::after {
    content: '';
    position: absolute;
    pointer-events: none;
  }

  &::before {
    inset: -2px;
    z-index: 0;
    border-radius: inherit;
    background: conic-gradient(from 140deg, #7c3aed, #06b6d4, #22c55e, #f472b6, #7c3aed);
    opacity: 0;
    filter: blur(1px);
  }

  &::after {
    inset: 1px;
    z-index: 1;
    border-radius: 15px;
    background:
      radial-gradient(circle at 14% 0%, rgba(129, 140, 248, 0.12), transparent 34%),
      radial-gradient(circle at 92% 12%, rgba(34, 211, 238, 0.11), transparent 28%),
      rgba(255, 255, 255, 0.84);
    backdrop-filter: blur(18px) saturate(1.16);
  }

  > * {
    position: relative;
    z-index: 2;
  }

  &.is-running::before {
    opacity: 0.78;
    animation: debugAuraSpin 5.8s linear infinite, debugPulseGlow 2.6s ease-in-out infinite;
  }

  &.is-waiting::before {
    background: conic-gradient(from 120deg, #f59e0b, #a855f7, #38bdf8, #fb7185, #f59e0b);
    opacity: 0.72;
    animation: debugAuraSpin 7.2s linear infinite;
  }

  &.is-success {
    background:
      linear-gradient(rgba(255, 255, 255, 0.76), rgba(248, 250, 252, 0.62)) padding-box,
      linear-gradient(135deg, rgba(34, 197, 94, 0.56), rgba(14, 165, 233, 0.24)) border-box;
  }

  &.is-error {
    background:
      linear-gradient(rgba(255, 255, 255, 0.76), rgba(254, 242, 242, 0.6)) padding-box,
      linear-gradient(135deg, rgba(239, 68, 68, 0.62), rgba(244, 114, 182, 0.28)) border-box;
  }
}

.debug-chat-messages {
  display: grid;
  align-content: start;
  gap: 12px;
  flex: 1;
  min-height: 0;
  max-height: none;
  padding: 2px 2px 6px;
  overflow: auto;

  :deep(.el-empty__description p) {
    color: #475569;
    font-weight: 600;
  }
}

.debug-message {
  display: grid;
  gap: 6px;
  justify-items: start;

  &.is-user {
    justify-items: end;

    .debug-message-content {
      background: linear-gradient(135deg, rgba(238, 242, 255, 0.92), rgba(236, 253, 245, 0.62));
      border-color: rgba(129, 140, 248, 0.34);
      box-shadow: 0 12px 26px rgba(79, 70, 229, 0.1);
    }
  }

  &.is-system {
    .debug-message-content {
      background: rgba(248, 250, 252, 0.78);
    }
  }

  &.is-runtime,
  &.is-assistant {
    .debug-message-content {
      background: rgba(255, 255, 255, 0.72);
    }
  }
}

.debug-message-role {
  padding: 0 4px;
  color: #475569;
  font-size: 12px;
  font-weight: 700;
}

.debug-message-content {
  width: min(100%, 440px);
  padding: 12px 14px;
  border: 1px solid rgba(203, 213, 225, 0.66);
  border-radius: 14px;
  color: #1e293b;
  background: rgba(255, 255, 255, 0.82);
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.08);
  backdrop-filter: blur(12px) saturate(1.12);

  p {
    margin: 0 0 8px;
    line-height: 1.6;
    word-break: break-word;
  }

  p:last-child {
    margin-bottom: 0;
  }
}

.debug-chat-composer {
  margin-top: 12px;
  padding: 12px;
  border-color: rgba(129, 140, 248, 0.22);
  border-radius: 16px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.94), rgba(239, 246, 255, 0.78)),
    rgba(255, 255, 255, 0.86);
  box-shadow: 0 18px 42px rgba(79, 70, 229, 0.11);

  :deep(.el-textarea__inner),
  :deep(.el-input__wrapper) {
    border-radius: 12px;
    color: #0f172a;
    background: rgba(255, 255, 255, 0.9);
    box-shadow: inset 0 0 0 1px rgba(203, 213, 225, 0.72);
  }

  .debug-actions .el-button--primary {
    width: 42px;
    height: 42px;
    border: 0;
    background: linear-gradient(135deg, #635bff, #7c3aed 55%, #06b6d4);
    box-shadow: 0 12px 26px rgba(99, 91, 255, 0.28);
  }
}

.debug-unified-input {
  margin-top: 12px;
}

.debug-drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding-right: 34px;

  strong {
    color: #0f172a;
    font-size: 16px;
    font-weight: 850;
  }
}

.debug-section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;

  strong,
  span {
    display: block;
  }

  strong {
    color: #0f172a;
    font-weight: 800;
  }

  span {
    margin-top: 3px;
    color: #64748b;
    font-size: 12px;
  }
}

.debug-field-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
}

.debug-input-card,
.debug-answer-card {
  padding: 14px;
  border: 1px solid rgba(203, 213, 225, 0.72);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.68);
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.06);
  backdrop-filter: blur(14px) saturate(1.12);
}

.debug-result {
  min-height: 180px;
}

.debug-advanced-collapse {
  margin-top: 14px;
  border: 1px solid rgba(203, 213, 225, 0.58);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.46);
  overflow: hidden;
  backdrop-filter: blur(12px);
}

.debug-advanced-popover-collapse {
  max-height: calc(100vh - 150px);
  margin-top: 0;
  border: 0;
  background: transparent;
  overflow: auto;
  backdrop-filter: none;
}

.debug-collapse-title {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  font-weight: 600;
}

.debug-answer-card {
  display: grid;
  align-items: flex-start;
  gap: 10px;
  border-left: 4px solid #22c55e;

  &.is-error {
    border-left-color: #ef4444;
  }

  &.is-waiting {
    border-left-color: #f59e0b;
  }

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  strong {
    display: block;
    margin-top: 6px;
    color: var(--el-text-color-primary);
    line-height: 1.6;
    word-break: break-word;
  }
}

.workflow-debug-steps {
  display: grid;
  align-content: start;
  grid-auto-rows: max-content;
  flex: 1;
  gap: 8px;
  min-height: 0;
  padding-right: 2px;
  overflow: auto;
}

.workflow-debug-step-card {
  overflow: hidden;
  border: 1px solid rgba(203, 213, 225, 0.62);
  border-left: 3px solid #22c55e;
  border-radius: 14px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.88), rgba(248, 250, 252, 0.66)),
    rgba(255, 255, 255, 0.76);
  box-shadow: 0 10px 26px rgba(15, 23, 42, 0.055);
  backdrop-filter: blur(12px) saturate(1.12);
  transition: border-color 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;

  &:hover {
    transform: translateY(-1px);
    border-color: rgba(129, 140, 248, 0.34);
    box-shadow: 0 14px 32px rgba(15, 23, 42, 0.08);
  }

  &.selected {
    border-color: rgba(99, 102, 241, 0.36);
    box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.14), 0 16px 36px rgba(99, 102, 241, 0.1);
  }

  &.is-error {
    border-left-color: #ef4444;
  }

  &.is-waiting {
    border-left-color: #f59e0b;
  }
}

.workflow-debug-step {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  gap: 7px 8px;
  align-items: start;
  width: 100%;
  padding: 10px 12px;
  border: 0;
  background: transparent;
  color: var(--el-text-color-primary);
  cursor: pointer;
  text-align: left;
}

.workflow-debug-step .step-route {
  grid-column: 2 / -1;
}

.workflow-debug-step .step-time {
  grid-column: 3;
  grid-row: 1;
}

.step-marker {
  display: inline-flex;
  position: relative;
  align-items: center;
  justify-content: center;
  width: 32px;
}

.step-running-icon {
  position: absolute;
  left: -4px;
  width: 14px;
  height: 14px;
  border: 2px solid rgba(99, 102, 241, 0.22);
  border-top-color: #6366f1;
  border-radius: 999px;
  opacity: 0;

  &.active {
    opacity: 1;
    animation: debugStepSpin 0.86s linear infinite;
  }
}

.step-index {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border-radius: 50%;
  background: rgba(241, 245, 249, 0.92);
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
}

.workflow-debug-step-card.is-success .step-index {
  background: rgba(220, 252, 231, 0.92);
  color: #15803d;
}

.workflow-debug-step-card.is-running {
  border-left-color: #6366f1;
}

.workflow-debug-step-card.is-running .step-index {
  background: rgba(224, 231, 255, 0.95);
  color: #4338ca;
}

.workflow-debug-step-card.is-waiting .step-index {
  background: rgba(254, 243, 199, 0.95);
  color: #b45309;
}

.workflow-debug-step-card.is-error .step-index {
  background: rgba(254, 226, 226, 0.95);
  color: #b91c1c;
}

.step-main,
.step-route {
  min-width: 0;

  strong,
  em,
  small {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  em,
  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
    font-style: normal;
  }

  strong {
    color: #0f172a;
    font-size: 13px;
    line-height: 1.22;
  }
}

.step-time {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.2;
  text-align: right;
}

.debug-step-inline {
  padding: 0 12px 12px 54px;
  border-top: 1px solid rgba(226, 232, 240, 0.74);
}

.debug-step-inline pre,
.debug-console-layout :deep(.el-collapse-item__content) pre {
  margin: 0;
  padding: 10px;
  border-radius: 6px;
  background: #f4f4f5;
  color: var(--el-text-color-primary);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.debug-waiting-card {
  display: grid;
  gap: 4px;
  margin-bottom: 8px;
  padding: 10px;
  border: 1px solid rgba(245, 158, 11, 0.35);
  border-radius: 6px;
  background: rgba(245, 158, 11, 0.08);

  strong {
    color: #92400e;
    font-size: 13px;
  }

  span {
    color: var(--el-text-color-primary);
    font-size: 13px;
  }
}

.trace-replay-panel {
  display: grid;
  gap: 10px;
  margin-top: 12px;
  padding: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}

.debug-advanced-collapse .trace-replay-panel {
  margin-top: 0;
  margin-bottom: 12px;
}

.trace-replay-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 8px;
  align-items: center;
}

.debug-production-row {
  padding: 10px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: #fff;

  strong,
  span {
    display: block;
  }

  span {
    margin-top: 4px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    line-height: 1.5;
  }
}

@media (prefers-reduced-motion: reduce) {
  .debug-chat-panel.is-running::before,
  .debug-chat-panel.is-waiting::before {
    animation: none;
  }
}

.trace-toolbar {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 12px;

  .el-select {
    flex: 1;
  }
}

.node-run-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.node-run-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 220px;
  padding: 6px 9px;
  border: 1px solid #bbf7d0;
  border-radius: 999px;
  background: #f0fdf4;
  color: #166534;
  cursor: pointer;
  font-size: 12px;

  span,
  em {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    font-weight: 700;
  }

  em {
    color: #64748b;
    font-style: normal;
  }

  &.error {
    border-color: #fecaca;
    background: #fef2f2;
    color: #991b1b;
  }

  &.waiting {
    border-color: #fde68a;
    background: #fffbeb;
    color: #92400e;
  }
}

.workflow-replay-summary {
  margin-bottom: 12px;
}

.trace-detail-section {
  margin-top: 12px;
}

.result-section {
  margin-bottom: 12px;

  pre {
    background: #f4f4f5;
    padding: 8px;
    border-radius: 4px;
    font-size: 12px;
    white-space: pre-wrap;
    word-break: break-all;
  }
}

.runtime-insights {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 8px;
}

.runtime-insight {
  padding: 10px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);

  span {
    display: block;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    margin-bottom: 4px;
  }

  strong {
    color: var(--el-text-color-primary);
    word-break: break-word;
  }
}

.debug-advanced-trigger {
  border-color: rgba(129, 140, 248, 0.22);
  color: #4338ca;
  background: rgba(255, 255, 255, 0.58);
  box-shadow: 0 10px 24px rgba(79, 70, 229, 0.08);
  backdrop-filter: blur(12px) saturate(1.12);
}

:global(.debug-advanced-popover) {
  max-width: min(560px, calc(100vw - 40px));
  border: 1px solid rgba(203, 213, 225, 0.64) !important;
  border-radius: 16px !important;
  background:
    radial-gradient(circle at 10% 0%, rgba(129, 140, 248, 0.12), transparent 34%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.9), rgba(248, 250, 252, 0.8)) !important;
  box-shadow: 0 24px 62px rgba(15, 23, 42, 0.16) !important;
  backdrop-filter: blur(18px) saturate(1.16);
}

:global(.vue-flow__edge.edge-route-hit .vue-flow__edge-path) {
  stroke: #16a34a;
  stroke-width: 3;
}

:global(.vue-flow__edge.edge-route-miss .vue-flow__edge-path) {
  stroke: #cbd5e1;
  stroke-dasharray: 5 5;
}

:global(.vue-flow__edge.edge-route-hit .vue-flow__edge-text) {
  fill: #166534;
  font-weight: 700;
}

@keyframes debugStepSpin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes debugAuraSpin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes debugPulseGlow {
  0%,
  100% {
    opacity: 0.58;
  }

  50% {
    opacity: 0.9;
  }
}

@media (max-width: 1120px) {
  .studio-page .studio-header {
    height: auto;
    min-height: 66px;
    align-items: flex-start;
    flex-direction: column;
    padding: 12px 16px;
  }

  .header-right {
    width: 100%;
    flex-wrap: wrap;
  }

  .studio-body,
  .studio-body.palette-open,
  .studio-body.property-collapsed,
  .studio-body.palette-open.property-collapsed {
    grid-template-columns: 74px minmax(0, 1fr);
    height: calc(100vh - 118px);
  }

  .property-panel {
    position: absolute;
    top: 118px;
    right: 0;
    z-index: 8;
    width: min(430px, 90vw);
    height: calc(100vh - 118px);
    box-shadow: -16px 0 42px rgba(31, 41, 55, 0.12);
  }

  .studio-body.property-collapsed .property-panel {
    width: 64px;
  }

  .ai-edit-bar.debug-drawer-open {
    right: 22px;
  }
}

@media (max-width: 760px) {
  .studio-title-row h1 {
    max-width: calc(100vw - 120px);
  }

  .studio-body,
  .studio-body.palette-open,
  .studio-body.property-collapsed,
  .studio-body.palette-open.property-collapsed {
    grid-template-columns: 1fr;
  }

  .palette {
    position: absolute;
    z-index: 10;
    width: 74px;
    height: calc(100vh - 118px);
  }

  .palette-open .palette {
    width: min(340px, 86vw);
  }

  .canvas-operator {
    left: 12px;
    bottom: 72px;
  }

  .workflow-inspector {
    display: none;
  }
}
</style>
