<template>
  <div class="studio-page">
    <!-- 顶部工具栏 -->
    <div class="studio-header">
      <div class="header-left">
        <el-tooltip content="返回 Agent 列表" placement="bottom">
          <button class="studio-back-btn" @click="router.push('/agent')">
            <el-icon><ArrowLeft /></el-icon>
            <span>返回</span>
          </button>
        </el-tooltip>
        <h2>智能体编排 - {{ form.name || '未命名' }}</h2>
        <el-tag size="small" effect="plain">草稿</el-tag>
        <el-tag size="small" type="success" effect="plain">健康</el-tag>
        <el-tag v-if="form.keySlug" size="small" type="info">{{ form.keySlug }}</el-tag>
        <el-tag size="small" type="success">{{ form.projectCode || '全局' }}</el-tag>
        <el-tag size="small">{{ visibilityLabel(form.visibility) }}</el-tag>
        <el-tooltip v-if="isSdkManaged" placement="bottom" :content="sdkPublishState.hint">
          <el-tag size="small" type="warning">SDK 只读</el-tag>
        </el-tooltip>
      </div>
      <div class="header-right">
        <span class="save-state" :class="saveStateClass">
          <i></i>
          <span>{{ saveBadgeText }}</span>
        </span>
        <el-button @click="handleDebug" :icon="VideoPlay">调试</el-button>
        <el-button :icon="MagicStick" :disabled="studioReadOnly" @click="openAiDraftDialog">AI 生成流程</el-button>
        <el-button @click="openEvalDrawer" :icon="Finished">评测</el-button>
        <el-dropdown trigger="click" @command="handleHeaderCommand">
          <el-button :icon="MoreFilled">更多</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="form" :icon="DocumentCopy">表单视图</el-dropdown-item>
              <el-dropdown-item command="extract" :icon="Collection" :disabled="canvasExtracting">
                画布转能力草稿
              </el-dropdown-item>
              <el-dropdown-item command="fit" :icon="Aim">聚焦画布</el-dropdown-item>
              <el-dropdown-item command="layout" :icon="Rank">自动整理</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button @click="handleSave" :loading="saving" :disabled="studioReadOnly">保存</el-button>
        <el-button type="primary" @click="publishDialogOpen = true">发布</el-button>
      </div>
    </div>

    <el-alert
      v-if="isSdkManaged"
      class="sdk-source-alert"
      type="warning"
      :closable="false"
      show-icon
    >
      <template #title>
        SDK 代码托管图：{{ sdkGraphMeta.projectCode || form.projectCode }} / {{ sdkGraphMeta.graphCode || form.keySlug }}
      </template>
      <div class="sdk-source-detail">
        Studio 当前以只读方式展示代码同步草稿；代码变更并重启业务系统后会自动更新草稿，发布版本不会自动改变。
        <span v-if="sdkGraphMeta.lastSyncedAt">最近同步：{{ sdkGraphMeta.lastSyncedAt }}</span>
        <div class="sdk-publish-state">
          <el-tag size="small" :type="sdkPublishState.type" effect="dark">{{ sdkPublishState.label }}</el-tag>
          <span>{{ sdkPublishState.hint }}</span>
        </div>
      </div>
    </el-alert>

    <div class="studio-body" :class="{ 'palette-open': paletteExpanded, 'property-collapsed': propertyPanelCollapsed }">
      <!-- 左侧节点调色板 -->
      <aside class="palette">
        <div class="palette-rail">
          <el-tooltip content="添加节点" placement="right">
            <button class="rail-primary" :disabled="studioReadOnly" @click="nodeSearchOpen = true">
              <el-icon><Plus /></el-icon>
            </button>
          </el-tooltip>
          <button
            v-for="group in paletteGroups"
            :key="group.title"
            class="rail-item"
            :class="{ active: paletteExpanded && activePaletteGroup === group.title }"
            @click="openPaletteGroup(group.title)"
          >
            <span class="rail-icon">
              <el-icon><component :is="group.icon" /></el-icon>
            </span>
            <em>{{ group.title.slice(0, 2) }}</em>
          </button>
          <div class="rail-spacer"></div>
          <el-tooltip content="检查与变量" placement="right">
            <button class="rail-item" @click="inspectorExpanded = !inspectorExpanded">
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
          - SDK 托管图只读展示<br />
          - 双击节点可快速选中属性
        </div>
        </div>
      </aside>

      <!-- 中间画布 -->
      <section class="canvas-wrap" @dragover.prevent @drop="onDrop">
        <VueFlow
          v-model:nodes="nodes"
          v-model:edges="edges"
          :default-viewport="{ zoom: 0.9 }"
          :default-edge-options="defaultEdgeOptions"
          :connection-line-options="connectionLineOptions"
          :fit-view-on-init="true"
          :elevate-edges-on-select="true"
          :delete-key-code="['Delete', 'Backspace']"
          :nodes-draggable="!studioReadOnly"
          :nodes-connectable="!studioReadOnly"
          @connect="onConnect"
          @node-double-click="onNodeDoubleClick"
          @pane-click="clearSelection"
          @node-click="onNodeClick"
          @edge-click="onEdgeClick"
          class="studio-canvas"
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-llm="nodeProps">
            <div class="studio-node llm-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><Cpu /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">大模型</span>
                <span class="node-state">推理</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">模型实例 · {{ nodeProps.data.llmConfig?.modelInstanceId || form.modelInstanceId || '未选择' }}</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · {{ portSummary(nodeProps.data.outputs, '出') }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
                <span class="node-state">{{ nodeProps.data.toolConfig?.projectCode || form.projectCode || '全局' }}</span>
              </div>
              <div class="node-label">{{ nodeProps.data.toolConfig?.ref || '未选择工具' }}</div>
              <div class="node-desc">{{ nodeProps.data.description || '原子工具调用' }}</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.inputs, '入') }} · {{ portSummary(nodeProps.data.outputs, '出') }}</div>
              <div v-if="nodeProps.data.outputAlias" class="node-alias">输出别名：{{ nodeProps.data.outputAlias }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
              <div v-if="conditionRoutePills(nodeProps.data).length" class="condition-routes">
                <span
                  v-for="route in conditionRoutePills(nodeProps.data)"
                  :key="route"
                  class="condition-route"
                >
                  {{ route }}
                </span>
              </div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.outputs, '分支') }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-classifier="nodeProps">
            <div class="studio-node classifier-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <div class="node-icon"><el-icon><Switch /></el-icon></div>
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-approval="nodeProps">
            <div class="studio-node approval-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><Finished /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">人工</span>
                <span class="node-state">确认</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">{{ nodeProps.data.approvalConfig?.title || '人工确认' }}</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.outputs, '分支') }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
          <template #node-loop="nodeProps">
            <div class="studio-node loop-node" :class="[nodeRunClass(nodeProps.id), { collapsed: nodeProps.data.collapsed }]">
              <Handle type="target" :position="Position.Left" />
              <Handle type="source" :position="Position.Right" />
              <div class="node-icon"><el-icon><RefreshRight /></el-icon></div>
              <div class="node-head">
                <span class="node-kind">循环</span>
                <span class="node-state">{{ nodeProps.data.loopConfig?.maxIterations || 3 }} 次</span>
              </div>
              <div class="node-label">{{ nodeProps.data.label || nodeProps.id }}</div>
              <div class="node-desc">键：{{ nodeProps.data.loopConfig?.loopKey || 'loop' }}</div>
              <div class="node-port-row">{{ portSummary(nodeProps.data.outputs, '分支') }}</div>
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
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
              <button v-if="nodeDebugState(nodeProps.id)" class="node-runtime" @click.stop="openNodeTrace(nodeProps.id)">
                <span class="runtime-dot"></span>
                <span>{{ nodeRunLabel(nodeProps.id) }}</span>
              </button>
            </div>
          </template>
        </VueFlow>
        <div class="canvas-operator">
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
            <el-button :icon="CopyDocument" circle :disabled="!selectedNode || selectedNode.data.kind === 'start' || selectedNode.data.kind === 'end'" @click="copySelectedNode" />
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
        </div>
        <div class="ai-edit-bar" @mousedown.stop @click.stop>
          <div class="ai-edit-context">
            <div class="ai-edit-context-left">
              <el-popover trigger="click" placement="top-start" width="360" popper-class="ai-edit-model-popover">
                <template #reference>
                  <el-button
                    class="ai-edit-model-trigger"
                    :class="{ active: !!selectedAiEditModelLabel }"
                    :disabled="aiEditLoading"
                    circle
                    aria-label="选择语义修改模型"
                  >
                    <el-icon><Cpu /></el-icon>
                  </el-button>
                </template>
                <div class="ai-edit-model-panel">
                  <div class="ai-edit-model-title">选择修改模型</div>
                  <el-select
                    v-model="aiDraftModelInstanceId"
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
                  <div class="ai-edit-model-hint">{{ selectedAiEditModelLabel || '未选择模型时会使用智能体默认模型' }}</div>
                </div>
              </el-popover>
              <el-tag size="small" effect="plain">{{ aiEditContextLabel }}</el-tag>
              <span>{{ aiEditPreview?.summary || '用自然语言修改当前画布草稿' }}</span>
            </div>
            <span class="ai-edit-model-current">{{ selectedAiEditModelName }}</span>
          </div>
          <div class="ai-edit-input-row">
            <el-input
              v-model="aiEditInstruction"
              :disabled="studioReadOnly || aiEditLoading"
              clearable
              placeholder="例如：在当前节点后面加一个物流查询，并在订单完成时才执行"
              @keyup.enter="handleGenerateWorkflowEdit"
            />
            <el-button
              type="primary"
              :loading="aiEditLoading"
              :disabled="studioReadOnly || !aiEditInstruction.trim()"
              @click="handleGenerateWorkflowEdit"
            >
              生成修改
            </el-button>
          </div>
          <div v-if="aiEditPreview" class="ai-edit-preview">
            <div class="ai-edit-preview-head">
              <strong>{{ aiEditPreview.summary || 'AI 修改预览' }}</strong>
              <span>{{ aiEditPreview.provider }} · {{ aiEditPreview.operations.length }} 项变更</span>
            </div>
            <div v-if="aiEditPreview.validationErrors.length" class="ai-edit-alert error">
              <span v-for="item in aiEditPreview.validationErrors" :key="item">{{ item }}</span>
            </div>
            <div v-if="aiEditPreview.warnings.length" class="ai-edit-alert warning">
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
              <el-button text @click="clearWorkflowEditPreview">取消</el-button>
              <el-button
                type="primary"
                :disabled="!!aiEditPreview.validationErrors.length"
                @click="handleApplyWorkflowEdit"
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
                @click="selectedNodeId = item.nodeId"
              >
                <span>{{ item.name }}</span>
                <em>{{ item.source }}</em>
              </button>
              <span v-if="!graphVariables.length" class="inspector-empty">暂无节点输出变量</span>
            </div>
            <div v-if="runtimeContextEntries.length" class="runtime-context">
              <div class="inspector-head">
                <strong>运行上下文</strong>
                <el-tag size="small">{{ runtimeContextEntries.length }}</el-tag>
              </div>
              <button
                v-for="item in runtimeContextEntries.slice(0, 8)"
                :key="item.key"
                class="context-row"
                @click="nodeDebugStateText = JSON.stringify({ ...parseNodeDebugState(), [item.key]: item.value }, null, 2)"
              >
                <span>{{ item.key }}</span>
                <em>{{ item.value }}</em>
              </button>
            </div>
          </div>
        </div>
        <div class="canvas-statusbar">
          <div class="status-left">
            <button class="status-pill" :class="{ danger: graphLintErrors.length, warning: !graphLintErrors.length && graphLintWarnings.length }" @click="inspectorExpanded = !inspectorExpanded">
              <span class="status-dot"></span>
              <strong>{{ graphLintErrors.length ? '阻断' : graphLintWarnings.length ? '提醒' : '健康' }}</strong>
              <em>{{ graphLintErrors.length || graphLintWarnings.length || '正常' }}</em>
            </button>
            <button class="status-pill" @click="inspectorExpanded = true">
              <strong>变量</strong>
              <em>{{ graphVariables.length }}</em>
            </button>
            <button class="status-pill" @click="inspectorExpanded = true">
              <strong>运行上下文</strong>
              <em>{{ runtimeContextEntries.length }}</em>
            </button>
          </div>
          <span class="status-autosave" :class="saveStateClass">
            <i></i>
            <span>{{ saveBadgeText }}</span>
          </span>
          <button class="status-collapse" @click="inspectorExpanded = !inspectorExpanded">
            {{ inspectorExpanded ? '收起面板' : '展开检查' }}
          </button>
        </div>
      </section>

      <!-- 右侧属性面板 -->
      <aside class="property-panel">
        <button class="property-toggle" @click="propertyPanelCollapsed = !propertyPanelCollapsed">
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
          title="代码托管 Agent 只读展示；请在业务代码中修改 EafGraph 后重启同步。"
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
          <el-divider>智能体元数据</el-divider>
          <el-form label-width="100px" size="small">
            <el-form-item label="名称">
              <el-input v-model="form.name" />
            </el-form-item>
            <el-form-item label="keySlug">
              <el-input v-model="form.keySlug" placeholder="默认自动生成" />
            </el-form-item>
            <el-form-item label="意图">
              <el-input v-model="form.intentType" />
            </el-form-item>
            <el-form-item label="最大步数">
              <el-input-number v-model="form.maxSteps" :min="1" :max="20" />
            </el-form-item>
            <el-form-item label="系统提示词">
              <el-input
                v-model="form.systemPrompt"
                type="textarea"
                :rows="8"
                placeholder="Agent 的角色和工作流程..."
              />
            </el-form-item>
            <el-form-item label="允许不可逆操作" :title="'允许该智能体调用 DELETE 等不可逆工具'">
              <el-switch v-model="form.allowIrreversible" />
            </el-form-item>
          </el-form>
        </div>

        <div v-else class="node-property-overview">
          <div class="node-property-head">
            <div class="node-property-icon" :style="{ background: kindColor(selectedNode.data.kind).bg, color: kindColor(selectedNode.data.kind).border }">
              <el-icon><component :is="nodeIconMap[selectedNode.data.kind] || Operation" /></el-icon>
            </div>
            <div class="node-property-title">
              <div>
                <strong>{{ selectedNode.data.label || selectedNode.id }}</strong>
                <el-tag size="small" effect="plain">{{ nodeKindLabel(selectedNode.data.kind) }}</el-tag>
              </div>
              <span>{{ selectedNode.data.description || '配置该节点的运行行为、变量、输出与测试。' }}</span>
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
              :disabled="studioReadOnly || selectedNode.data.kind === 'start' || selectedNode.data.kind === 'end'"
            >
              删除
            </el-button>
          </div>

          <button class="property-section-card" @click="openPropertyDetail('base')">
            <span class="section-card-icon"><el-icon><Operation /></el-icon></span>
            <span class="section-card-main">
              <strong>基础</strong>
              <em>节点名称、描述、状态等</em>
            </span>
            <span class="section-card-meta">
              <el-tag size="small" :type="selectedNode.data.source === 'SDK' ? 'warning' : 'success'">
                {{ sourceLabel(selectedNode.data.source) }}
              </el-tag>
              <el-icon><ArrowRight /></el-icon>
            </span>
          </button>

          <button class="property-section-card" @click="openPropertyDetail('node')">
            <span class="section-card-icon"><el-icon><SetUp /></el-icon></span>
            <span class="section-card-main">
              <strong>节点配置</strong>
              <em>{{ nodeKindLabel(selectedNode.data.kind) }} 专属参数、变量与输出</em>
            </span>
            <span class="section-card-meta">
              <el-tag size="small">{{ portSummary(selectedNode.data.inputs, '输入') }}</el-tag>
              <el-tag size="small">{{ portSummary(selectedNode.data.outputs, '输出') }}</el-tag>
              <el-icon><ArrowRight /></el-icon>
            </span>
          </button>

          <button
            v-if="selectedNode.data.kind !== 'start' && selectedNode.data.kind !== 'end'"
            class="property-section-card"
            @click="openPropertyDetail('debug')"
          >
            <span class="section-card-icon"><el-icon><VideoPlay /></el-icon></span>
            <span class="section-card-main">
              <strong>节点测试</strong>
              <em>测试消息、模拟状态与输出状态</em>
            </span>
            <span class="section-card-meta">
              <el-tag
                v-if="nodeDebugResult?.nodeId === selectedNode.id"
                size="small"
                :type="nodeDebugResult.success ? 'success' : 'danger'"
              >
                {{ nodeDebugResult.success ? '通过' : '失败' }}
              </el-tag>
              <el-icon><ArrowRight /></el-icon>
            </span>
          </button>

          <button class="property-section-card" @click="openPropertyDetail('trace')">
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
            <el-icon v-if="selectedNode"><component :is="nodeIconMap[selectedNode.data.kind] || Operation" /></el-icon>
            <el-icon v-else><SetUp /></el-icon>
          </div>
          <div class="node-dialog-title">
            <strong>{{ selectedNode?.data.label || selectedNode?.id || '节点' }}</strong>
            <span>{{ propertyDetailSectionLabel }} · {{ selectedNode ? studioNodeLabel(selectedNode.data.kind) : '节点配置' }}</span>
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
              <el-tag size="small" effect="plain">{{ studioNodeLabel(selectedNode.data.kind) }}</el-tag>
            </div>
            <el-form-item label="节点 ID">
              <el-input v-model="selectedNode.id" disabled />
            </el-form-item>
            <el-form-item label="名称">
              <el-input v-model="selectedNode.data.label" placeholder="给节点起一个清晰的业务名称" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="selectedNode.data.description" type="textarea" :rows="4" placeholder="说明这个节点在流程中的职责，便于协作和调试" />
            </el-form-item>
            <el-form-item v-if="selectedNode.data.kind !== 'start' && selectedNode.data.kind !== 'end'" label="输出别名">
              <el-input v-model="selectedNode.data.outputAlias" placeholder="如 customer / params / context" />
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

          <section
            v-if="selectedNode.data.kind !== 'start' && selectedNode.data.kind !== 'end' && selectedNode.data.errorPolicy && selectedNode.data.retry"
            class="node-detail-card compact-card"
          >
            <div class="node-detail-card-head">
              <div>
                <strong>异常治理</strong>
                <span>配置失败后的处理方式和重试节奏</span>
              </div>
              <el-switch
                v-model="selectedNode.data.retry.enabled"
                class="retry-switch"
                inline-prompt
                active-text="重试"
                inactive-text="不重试"
              />
            </div>
            <div class="governance-row">
              <el-form-item label="失败策略">
                <el-select v-model="selectedNode.data.errorPolicy.strategy" style="width: 160px">
                  <el-option label="终止" value="TERMINATE" />
                  <el-option label="继续" value="CONTINUE" />
                  <el-option label="兜底" value="FALLBACK" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="selectedNode.data.retry?.enabled && selectedNode.data.retry" label="次数">
                <el-input-number v-model="selectedNode.data.retry.maxAttempts" :min="1" :max="5" size="small" />
              </el-form-item>
              <el-form-item v-if="selectedNode.data.retry?.enabled && selectedNode.data.retry" label="间隔">
                <el-input-number v-model="selectedNode.data.retry.backoffMs" :min="0" :step="200" size="small" class="retry-delay" />
              </el-form-item>
            </div>
          </section>
        </el-form>

        <NodeConfigPanel
          v-else-if="propertyDetailSection === 'node'"
          :data="selectedNode.data"
          :model-options="modelOptions"
          :knowledge-options="knowledgeOptions"
          :tool-options="availableTools"
          :capability-options="availableCapabilities"
          :variable-options="variablePickerOptions"
          :credential-options="credentialOptions"
          :param-source-hints="paramHints"
          :project-id="form.projectId"
          :project-code="form.projectCode"
          @credential-created="handleCredentialCreated"
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
              <pre>{{ JSON.stringify(nodeDebugResult.outputState || {}, null, 2) }}</pre>
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
              <span>{{ selectedNodeTrace.spanType || selectedNodeTrace.toolName || 'span' }}</span>
              <span>{{ formatElapsed(selectedNodeTrace.elapsedMs) }}</span>
              <span v-if="selectedNodeTrace.errorCode">{{ selectedNodeTrace.errorCode }}</span>
              <span v-if="selectedNodeTrace.route">route: {{ selectedNodeTrace.route }}</span>
              <span v-if="selectedNodeTrace.interactionId">interaction: {{ selectedNodeTrace.interactionId }}</span>
            </div>
            <div v-if="selectedNodeTrace.input" class="node-trace-block">
              <span>输入</span>
              <pre>{{ prettyTracePayload(selectedNodeTrace.input) }}</pre>
            </div>
            <div v-if="selectedNodeTrace.output" class="node-trace-block">
              <span>输出</span>
              <pre>{{ prettyTracePayload(selectedNodeTrace.output) }}</pre>
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

    <el-dialog v-model="aiDraftDialogOpen" title="AI 生成流程草稿" width="760px" class="ai-draft-dialog">
      <div class="ai-draft-body">
        <el-alert
          type="info"
          :closable="false"
          title="生成结果只替换当前前端草稿，不会自动保存或发布。占位节点需要补全后才能通过发布校验。"
        />
        <div class="ai-draft-model-row">
          <span>生成模型</span>
          <el-select
            v-model="aiDraftModelInstanceId"
            filterable
            clearable
            placeholder="选择用于生成和 LLM 节点的模型"
            :disabled="aiDraftGenerating"
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
          v-model="aiDraftRequirement"
          type="textarea"
          :rows="4"
          maxlength="1000"
          show-word-limit
          placeholder="例如：查询订单状态，如果订单已完成就回复物流信息，否则提示当前处理进度。"
        />
        <div class="ai-draft-actions">
          <span>{{ availableTools.length }} 个工具 / {{ availableCapabilities.length }} 个能力 / {{ knowledgeOptions.length }} 个知识库可供匹配</span>
          <el-button type="primary" :loading="aiDraftGenerating" @click="handleGenerateWorkflowDraft">
            生成预览
          </el-button>
        </div>
        <div v-if="aiDraftPreview" class="ai-draft-preview">
          <div class="ai-draft-preview-head">
            <div>
              <strong>{{ aiDraftPreview.graphSpec?.name || 'AI 生成流程' }}</strong>
              <span>{{ aiDraftPreview.provider }} / {{ aiDraftPreviewNodes.length }} 个节点</span>
            </div>
            <el-tag :type="aiDraftPreview.placeholderNodes.length ? 'warning' : 'success'" size="small">
              {{ aiDraftPreview.placeholderNodes.length ? '含占位节点' : '可编辑草稿' }}
            </el-tag>
          </div>
          <el-alert
            v-if="aiDraftPreview.warnings.length"
            type="warning"
            :closable="false"
            class="ai-draft-warning"
          >
            <template #title>
              <span v-for="item in aiDraftPreview.warnings" :key="item">{{ item }}</span>
            </template>
          </el-alert>
          <el-alert
            v-if="aiDraftPreview.validationErrors?.length"
            type="error"
            :closable="false"
            class="ai-draft-warning"
          >
            <template #title>
              <span v-for="item in aiDraftPreview.validationErrors" :key="item">{{ item }}</span>
            </template>
          </el-alert>
          <div v-if="aiDraftPreview.placeholderNodes.length" class="ai-draft-placeholders">
            <el-tag
              v-for="item in aiDraftPreview.placeholderNodes"
              :key="item.nodeId"
              type="warning"
              effect="plain"
            >
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
              <span>{{ studioNodeLabel(node.data?.kind) }}</span>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="aiDraftDialogOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!aiDraftPreview || !!aiDraftPreview.validationErrors?.length" @click="handleApplyWorkflowDraft">
          替换当前草稿
        </el-button>
      </template>
    </el-dialog>

    <!-- 发布弹窗 -->
    <el-dialog v-model="publishDialogOpen" title="发布智能体版本" width="640px">
      <div v-if="releaseErrors.length || releaseWarnings.length" class="release-check-panel">
        <div class="release-check-head">
          <div>
            <strong>后端发布门禁</strong>
            <span>{{ releaseErrors.length }} 个阻断项 / {{ releaseWarnings.length }} 个提醒项</span>
          </div>
          <el-tag :type="releaseErrors.length ? 'danger' : 'success'" size="small">
            {{ releaseErrors.length ? '未通过' : '已通过' }}
          </el-tag>
        </div>
        <el-collapse model-value="errors" class="release-check-collapse">
          <el-collapse-item v-if="releaseErrors.length" title="阻断项" name="errors">
            <div v-for="item in releaseErrors" :key="item.code + '-' + item.nodeId + '-' + item.message" class="check-item error">
              <el-tag size="small" type="danger">{{ item.code }}</el-tag>
              <span v-if="item.nodeId" class="check-node">{{ item.nodeId }}</span>
              <span>{{ item.message }}</span>
            </div>
          </el-collapse-item>
          <el-collapse-item v-if="releaseWarnings.length" title="提醒项" name="warnings">
            <div v-for="item in releaseWarnings" :key="item.code + '-' + item.nodeId + '-' + item.message" class="check-item warn">
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
        <el-button type="primary" @click="handlePublish" :loading="publishing">确认发布</el-button>
      </template>
    </el-dialog>

    <!-- 调试抽屉：当前草稿逐节点执行 + 发布端点验证 -->
    <el-drawer
      v-model="debugOpen"
      class="studio-debug-drawer"
      modal-class="studio-debug-drawer-overlay"
      title="工作流调试台（当前草稿）"
      size="34%"
      direction="rtl"
      :modal="false"
      :modal-penetrable="true"
      :lock-scroll="false"
    >
      <div class="debug-body">
        <section class="debug-input-card">
          <div class="debug-section-head">
            <div>
              <strong>调试输入</strong>
              <span>优先读取用户输入节点字段，运行时写入 params</span>
            </div>
            <el-tag v-if="debugInputFields.length" size="small" type="success">
              {{ debugInputFields.length }} 个输入字段
            </el-tag>
            <el-tag v-else size="small" type="info">自由消息</el-tag>
          </div>
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
            placeholder="输入测试消息..."
          />
        </section>
        <div class="debug-actions">
          <el-button type="primary" :loading="debugLoading" @click="handleRunDraftDebug">
            运行当前草稿
          </el-button>
          <el-button :loading="debugLoading" @click="handleRunPublishedDebug">
            发布端点验证
          </el-button>
        </div>
        <el-divider>执行结果</el-divider>
        <div class="debug-result">
          <div v-if="debugRunResult" class="debug-console-layout">
            <section class="debug-answer-card" :class="debugStepStatusClass(debugRunResult.status)">
              <div>
                <span>最终回答</span>
                <strong>{{ debugRunResult.answer || debugRunResult.errorMessage || '-' }}</strong>
              </div>
              <div class="debug-run-meta">
                <el-tag :type="debugRunResult.success ? 'success' : 'danger'" size="small">
                  {{ debugRunStatusText(debugRunResult.status) }}
                </el-tag>
                <span>{{ debugRunResult.steps?.length || 0 }} 个节点</span>
                <span v-if="debugRunResult.traceId">Trace {{ debugRunResult.traceId }}</span>
              </div>
            </section>

            <section class="workflow-debug-steps">
              <article
                v-for="(step, index) in debugRunResult.steps"
                :key="step.nodeId + ':' + index"
                class="workflow-debug-step-card"
                :class="[debugStepStatusClass(step.status), { selected: selectedDebugStepIndex === index }]"
              >
                <button
                  class="workflow-debug-step"
                  :class="debugStepStatusClass(step.status)"
                  @click="selectDebugStep(index)"
                >
                  <span class="step-index">{{ index + 1 }}</span>
                  <span class="step-main">
                    <strong>{{ step.nodeName || step.nodeId }}</strong>
                    <em>{{ step.nodeType || '-' }}</em>
                  </span>
                  <span class="step-route">
                    <template v-if="step.route">路由 {{ step.route }}</template>
                    <template v-else>直连</template>
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
            </section>

          </div>
          <div v-else-if="debugResult">
            <div class="result-section">
              <strong>发布端点回答：</strong>
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
            <div class="result-section" v-if="debugResult.toolCalls?.length">
              <strong>工具调用：</strong>
              <el-tag
                v-for="t in debugResult.toolCalls"
                :key="t"
                size="small"
                class="tool-tag"
              >{{ t }}</el-tag>
            </div>
            <div class="result-section" v-if="debugResult.metadata">
              <strong>元数据：</strong>
              <pre>{{ JSON.stringify(debugResult.metadata, null, 2) }}</pre>
            </div>
          </div>
          <el-empty v-else description="运行当前草稿后，会按节点顺序展示执行路径" />
        </div>

        <el-collapse class="debug-advanced-collapse">
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
                  placeholder="选择最近运行"
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
                  @click="handleExtractCapabilityDraft"
                  :loading="extracting"
                >抽取为能力草稿</el-button>
              </div>
              <TraceTimeline :nodes="traceNodes" />
            </div>
            <el-empty v-else description="需要排查生产调用时，再输入 traceId 回放" />
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-drawer>

    <el-drawer
      v-model="evalOpen"
      title="智能体评测"
      size="720px"
      class="studio-eval-drawer"
      destroy-on-close
    >
      <div class="eval-body">
        <section class="eval-panel">
          <div class="eval-section-head">
            <div>
              <strong>数据集</strong>
              <span>导入 Excel/CSV 后，使用当前画布草稿重复执行评测。</span>
            </div>
            <el-button size="small" :loading="evalDatasetLoading" @click="loadEvalDatasets">刷新</el-button>
          </div>
          <div class="eval-toolbar">
            <el-select
              v-model="evalSelectedDatasetId"
              placeholder="选择评测数据集"
              filterable
              class="eval-dataset-select"
            >
              <el-option
                v-for="dataset in evalDatasets"
                :key="dataset.id"
                :label="`${dataset.name}（${dataset.caseCount || 0} 条）`"
                :value="dataset.id"
              />
            </el-select>
            <div class="eval-import-actions">
              <el-button :icon="Download" @click="downloadEvalTemplate">模板下载</el-button>
              <el-upload
                :show-file-list="false"
                accept=".xlsx,.xls,.csv"
                :before-upload="handleEvalUpload"
              >
                <el-button :loading="evalUploading" :icon="Document">导入</el-button>
              </el-upload>
            </div>
          </div>
          <div v-if="evalImportErrors.length" class="eval-import-errors">
            <div v-for="error in evalImportErrors" :key="error">{{ error }}</div>
          </div>
        </section>

        <section class="eval-panel">
          <div class="eval-section-head">
            <div>
              <strong>运行设置</strong>
              <span>默认沙箱执行，不允许真实不可逆副作用。</span>
            </div>
          </div>
          <div class="eval-toolbar">
            <el-input-number v-model="evalRepeatCount" :min="1" :max="20" controls-position="right" />
            <el-button type="primary" :loading="evalRunning" :disabled="!evalSelectedDatasetId" @click="handleStartEval">
              开始评测
            </el-button>
          </div>
        </section>

        <section v-if="evalSummary" class="eval-summary-grid">
          <div class="eval-metric">
            <span>准确率</span>
            <strong>{{ formatEvalRate(evalSummary.accuracyRate) }}</strong>
          </div>
          <div class="eval-metric">
            <span>成功率</span>
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

        <section v-if="evalRunView" class="eval-panel">
          <div class="eval-section-head">
            <div>
              <strong>评测结果</strong>
              <span>{{ evalRunView.run.status }} · {{ evalRunView.summary.totalExecutions }} 次执行</span>
            </div>
          </div>
          <el-table :data="evalRunView.results" size="small" max-height="280">
            <el-table-column prop="roundNo" label="轮次" width="72" />
            <el-table-column prop="caseNo" label="用例" min-width="120" />
            <el-table-column label="状态" width="92">
              <template #default="{ row }">
                <el-tag :type="row.assertionPassed ? 'success' : row.runtimeSuccess ? 'warning' : 'danger'" size="small">
                  {{ row.assertionPassed ? '通过' : row.runtimeSuccess ? '偏差' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="score" label="得分" width="80" />
            <el-table-column prop="elapsedMs" label="耗时" width="92" />
            <el-table-column prop="answer" label="输出" min-width="220" show-overflow-tooltip />
          </el-table>
        </section>

        <section v-if="evalRunView?.suggestion" class="eval-panel">
          <div class="eval-section-head">
            <div>
              <strong>AI 修复建议</strong>
              <span>{{ evalRunView.suggestion.summary }}</span>
            </div>
          </div>
          <div v-if="evalRunView.suggestion.items.length" class="eval-suggestion-list">
            <div v-for="item in evalRunView.suggestion.items" :key="item.nodeId" class="eval-suggestion-item">
              <el-tag size="small" :type="item.severity === 'HIGH' ? 'danger' : 'warning'">{{ item.nodeId }}</el-tag>
              <div>
                <strong>{{ item.reason }}</strong>
                <span>{{ item.recommendation }}</span>
              </div>
            </div>
          </div>
          <el-empty v-else description="暂无需要修复的问题" />
        </section>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import type { Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRawFile } from 'element-plus'
import {
  Aim,
  ArrowLeft,
  ArrowDown,
  ArrowRight,
  ArrowUp,
  Briefcase,
  Close,
  Collection,
  Connection,
  Coin,
  CopyDocument,
  Cpu,
  Delete,
  Document,
  DocumentCopy,
  Download,
  Files,
  Finished,
  Link,
  MagicStick,
  MoreFilled,
  Operation,
  Plus,
  Rank,
  RefreshLeft,
  RefreshRight,
  Search,
  SetUp,
  Switch,
  Tools,
  VideoPlay,
  ZoomIn,
  ZoomOut,
} from '@element-plus/icons-vue'

import { ConnectionLineType, Handle, Position, VueFlow, useVueFlow } from '@vue-flow/core'
import * as XLSX from 'xlsx'
import { Background } from '@vue-flow/background'
import { MiniMap } from '@vue-flow/minimap'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/minimap/dist/style.css'

import type { AgentForm, AgentReleaseValidationItem, AgentVersion, AgentDefinition, AgentNodeDebugResult, AgentGraphNodeTypeDescriptor, WorkflowDraftGenerationResult, WorkflowDraftResource, AgentWorkflowDebugRunResult, AgentWorkflowDebugStepResult, WorkflowDraftEditResult, WorkflowDraftEditOperation, WorkflowDraftEditOperationType } from '@/types/agent'
import type { CanvasNode, CanvasEdge, CanvasNodeKind, StudioFieldSchema, StudioVariableOption } from '@/types/studio'
import { getAgent, updateAgent, publishAgentVersion, validateAgentRelease, gatewayChat, listAgentVersions, debugAgentNode, debugAgentWorkflowRun, getAgentGraphNodeTypes, generateWorkflowDraft, editWorkflowDraft } from '@/api/agent'
import { createEvalDataset, listEvalDatasets, startEvalRun } from '@/api/agentEval'
import type { AgentEvalCaseImportRow, AgentEvalDataset, AgentEvalRunSummary, AgentEvalRunView } from '@/types/agentEval'
import { getTools } from '@/api/tool'
import { listCapabilities } from '@/api/capability'
import type { ToolInfo } from '@/types/tool'
import type { CapabilityInfo } from '@/types/capability'
import type { ChatResponse } from '@/types/chat'
import { canvasToDefinition, createDefaultNodeData, definitionToCanvas, kindColor } from '@/utils/studio'
import {
  STUDIO_NODE_GROUPS,
  STUDIO_NODE_REGISTRY,
  enabledStudioNodeKinds,
  studioNodeCapabilityMap,
  studioNodeDefaultLabel,
  studioNodeLabel,
} from '@/utils/studioNodeRegistry'
import TraceTimeline from '@/components/TraceTimeline.vue'
import { getTraceDetail } from '@/api/trace'
import type { TraceNode } from '@/types/trace'
import { getRecentRunOps, getRunOpsDetail } from '@/api/runops'
import type { RunDetail, RunSpan, RunSummary, WorkflowPathItem } from '@/types/runops'
import { extractDraftFromTrace, extractDraftFromCanvas } from '@/api/capabilityMining'
import { getApiGraphParamHints } from '@/api/apiGraph'
import type { ApiGraphParamSourceHint } from '@/api/apiGraph'
import { getModelInstances } from '@/api/model'
import type { ModelInstance } from '@/types/model'
import { getKnowledgeList } from '@/api/knowledge'
import type { KnowledgeBase } from '@/types/knowledge'
import { listWorkflowCredentials } from '@/api/workflowCredential'
import type { WorkflowCredential } from '@/types/workflowCredential'
import NodeConfigPanel from './studio-panels/NodeConfigPanel.vue'

const route = useRoute()
const router = useRouter()
const agentId = route.params.id as string
const isNew = agentId === 'new'

const { screenToFlowCoordinate, fitView, setCenter, zoomIn, zoomOut, getViewport } = useVueFlow()
const DEBUG_DRAWER_WIDTH_RATIO = 0.34

const saving = ref(false)
const publishing = ref(false)
const publishDialogOpen = ref(false)
const releaseErrors = ref<AgentReleaseValidationItem[]>([])
const releaseWarnings = ref<AgentReleaseValidationItem[]>([])
const versions = ref<AgentVersion[]>([])
const debugOpen = ref(false)
const debugLoading = ref(false)
const debugMessage = ref('这是一条测试消息')
const debugResult = ref<ChatResponse | null>(null)
const debugRunResult = ref<AgentWorkflowDebugRunResult | null>(null)
const selectedDebugStepIndex = ref<number | null>(null)
const currentDebugNodeId = ref('')
const debugPlaybackToken = ref(0)
const debugInputParams = reactive<Record<string, unknown>>({})
const evalOpen = ref(false)
const evalDatasetLoading = ref(false)
const evalUploading = ref(false)
const evalRunning = ref(false)
const evalDatasets = ref<AgentEvalDataset[]>([])
const evalSelectedDatasetId = ref<number | null>(null)
const evalRepeatCount = ref(1)
const evalRunView = ref<AgentEvalRunView | null>(null)
const evalImportErrors = ref<string[]>([])
const nodeDebugLoading = ref(false)
const nodeDebugMessage = ref('这是一条节点测试消息')
const nodeDebugStateText = ref('{\n  "input": "这是一条节点测试消息"\n}')
const nodeDebugResult = ref<AgentNodeDebugResult | null>(null)
const currentTraceId = ref<string>('')
const traceNodes = ref<TraceNode[]>([])
const runOpsDetail = ref<RunDetail | null>(null)
const replayTraceInput = ref('')
const selectedRecentTraceId = ref('')
const traceReplayLoading = ref(false)
const recentRunsLoading = ref(false)
const recentRuns = ref<RunSummary[]>([])
const traceToolPick = ref<string[]>([])
const extracting = ref(false)
const canvasExtracting = ref(false)
const aiDraftDialogOpen = ref(false)
const aiDraftGenerating = ref(false)
const aiDraftRequirement = ref('')
const aiDraftModelInstanceId = ref('')
const aiDraftPreview = ref<WorkflowDraftGenerationResult | null>(null)
const aiEditInstruction = ref('')
const aiEditLoading = ref(false)
const aiEditPreview = ref<WorkflowDraftEditResult | null>(null)
const traceToolNames = computed(() => {
  const names = traceNodes.value
    .map((n) => (n.toolName || '').trim())
    .filter((n) => !!n)
  return Array.from(new Set(names))
})
const evalSummary = computed<AgentEvalRunSummary | null>(() => evalRunView.value?.summary || null)

type NodeTraceState = {
  nodeId: string
  status: 'success' | 'error' | 'waiting'
  elapsedMs?: number
  spanType?: string
  toolName?: string
  input?: string
  output?: string
  errorCode?: string
  route?: string
  interactionId?: string
  createdAt?: string
}

const nodeTraceStates = computed<Record<string, NodeTraceState>>(() => {
  const states: Record<string, NodeTraceState> = {}
  const debugSteps = debugRunResult.value?.steps || []
  if (debugSteps.length) {
    for (const step of debugSteps) {
      states[step.nodeId] = {
        nodeId: step.nodeId,
        status: debugStepStatus(step.status),
        elapsedMs: step.elapsedMs,
        spanType: 'STUDIO_DEBUG_STEP',
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
      if (!next) {
        continue
      }
      const previous = states[next.nodeId]
      states[next.nodeId] = preferNodeTraceState(previous, next)
    }
    for (const item of workflowPath.value) {
      const nodeId = (item.fromNodeId || '').trim()
      if (!nodeId) {
        continue
      }
      const previous = states[nodeId]
      const next: NodeTraceState = {
        nodeId,
        status: workflowItemStatus(item),
        spanType: 'FLOW_NODE',
        route: item.route || item.condition,
        interactionId: item.interactionId,
        createdAt: item.startedAt,
      }
      states[nodeId] = preferNodeTraceState(previous, next)
    }
    return states
  }
  const ordered = [...traceNodes.value].sort((a, b) => dateMs(a.createdAt) - dateMs(b.createdAt))
  for (const item of ordered) {
    const nodeId = (item.nodeId || '').trim()
    if (!nodeId) {
      continue
    }
    const next: NodeTraceState = {
      nodeId,
      status: item.success ? 'success' : 'error',
      elapsedMs: item.elapsedMs,
      spanType: item.spanType,
      toolName: item.toolName,
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
  const currentAgentId = isNew ? '' : String(agentId)
  const currentAgentName = (form.name || '').trim()
  const matched = recentRuns.value.filter((run) => {
    if (currentAgentId && String(run.agentId || '') === currentAgentId) return true
    if (currentAgentName && run.agentName === currentAgentName) return true
    return !currentAgentId && !currentAgentName
  })
  return matched.length ? matched : recentRuns.value
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
    { label: '实例', value: textValue(metadata.instanceId) },
    { label: 'HYBRID 回落', value: fallback },
    { label: '追踪 ID', value: textValue(metadata.traceId) },
  ].filter((item) => item.value !== '-')
})

const toolOptions = ref<ToolInfo[]>([])
const capabilityOptions = ref<CapabilityInfo[]>([])
const modelOptions = ref<ModelInstance[]>([])
const knowledgeOptions = ref<KnowledgeBase[]>([])
const credentialOptions = ref<WorkflowCredential[]>([])
const paramHints = ref<ApiGraphParamSourceHint[]>([])
const availableTools = computed(() =>
  toolOptions.value.filter((t) => t.enabled && t.agentVisible),
)
const availableCapabilities = computed(() =>
  capabilityOptions.value.filter((s) => s.enabled && s.agentVisible && !s.draft),
)
const aiDraftModelOptions = computed(() => {
  const llmOptions = modelOptions.value.filter((item) => item.modelType === 'LLM')
  return llmOptions.length ? llmOptions : modelOptions.value
})
const selectedAiEditModel = computed(() => {
  const id = aiDraftModelInstanceId.value || form.modelInstanceId || aiDraftModelOptions.value[0]?.id || ''
  if (!id) return null
  return aiDraftModelOptions.value.find((item) => item.id === id)
    || modelOptions.value.find((item) => item.id === id)
    || null
})
const selectedAiEditModelLabel = computed(() => selectedAiEditModel.value ? modelOptionLabel(selectedAiEditModel.value) : '')
const selectedAiEditModelName = computed(() => selectedAiEditModel.value?.name || selectedAiEditModel.value?.modelName || aiDraftModelInstanceId.value || form.modelInstanceId || '默认模型')
const aiDraftPreviewNodes = computed(() => {
  const snapshot = aiDraftPreview.value?.canvasSnapshot as { nodes?: unknown } | undefined
  return Array.isArray(snapshot?.nodes) ? snapshot.nodes as CanvasNode[] : []
})
const aiDraftPreviewEdges = computed(() => {
  const snapshot = aiDraftPreview.value?.canvasSnapshot as { edges?: unknown } | undefined
  return Array.isArray(snapshot?.edges) ? snapshot.edges as CanvasEdge[] : []
})
const aiDraftPreviewNodeLabels = computed(() => {
  const labels = new Map<string, string>()
  for (const node of aiDraftPreviewNodes.value) {
    labels.set(node.id, node.data?.label || node.id)
  }
  return labels
})
const aiEditOperationGroups = computed(() => {
  const operations = aiEditPreview.value?.operations || []
  const order: WorkflowDraftEditOperationType[] = ['ADD_NODE', 'UPDATE_NODE', 'DELETE_NODE', 'ADD_EDGE', 'UPDATE_EDGE', 'DELETE_EDGE']
  return order
    .map((type) => ({
      type,
      label: workflowEditOperationLabel(type),
      items: operations.filter((item) => item.type === type),
    }))
    .filter((group) => group.items.length)
})

const form = reactive<AgentForm>({
  keySlug: '',
  name: '',
  description: '',
  projectId: null,
  projectCode: null,
  visibility: 'PRIVATE',
  intentType: 'GENERAL_CHAT',
  systemPrompt: '',
  tools: [],
  skills: [],
  modelInstanceId: '',
  runtimeType: 'AGENTSCOPE',
  runtimePlacement: 'CENTRAL',
  runtimeConfig: {},
  defaultResourceConfig: {},
  graphSpec: null,
  maxSteps: 5,
  enabled: true,
  type: 'single',
  pipelineAgentIds: [],
  knowledgeBaseGroupId: '',
  promptTemplateId: '',
  outputSchemaType: '',
  triggerMode: 'all',
  useMultiAgentModel: false,
  extra: {},
  canvasJson: '',
  allowIrreversible: false,
})

const sdkGraphMeta = computed<Record<string, unknown>>(() => {
  const extra = form.extra || {}
  const raw = extra.sdkGraph
  return raw && typeof raw === 'object' ? (raw as Record<string, unknown>) : {}
})
const isSdkManaged = computed(() => sdkGraphMeta.value.managedBy === 'SDK' || sdkGraphMeta.value.source === 'SDK')
const studioReadOnly = computed(() => isSdkManaged.value)
const latestPublishedVersion = computed(() =>
  [...versions.value]
    .filter((version) => version.status === 'ACTIVE' || version.status === 'RETIRED')
    .sort((a, b) => dateMs(b.publishedAt || b.createTime) - dateMs(a.publishedAt || a.createTime))[0] || null,
)
const sdkPublishState = computed(() => {
  const latest = latestPublishedVersion.value
  const syncAt = String(sdkGraphMeta.value.lastSyncedAt || '')
  if (!latest) {
    return {
      type: 'danger' as const,
      label: '未发布',
      hint: `SDK 草稿已同步${syncAt ? `于 ${syncAt}` : ''}，还没有发布版本。`,
    }
  }
  const currentHash = String(sdkGraphMeta.value.sourceHash || '')
  const publishedHash = sdkSourceHashFromVersion(latest)
  const hashDiffers = currentHash && publishedHash && currentHash !== publishedHash
  const timeDiffers = !currentHash || !publishedHash
    ? dateMs(syncAt) > dateMs(latest.publishedAt || latest.createTime) + 1000
    : false
  if (hashDiffers || timeDiffers) {
    return {
      type: 'warning' as const,
      label: '草稿待发布',
      hint: `SDK 草稿晚于最新发布版本 ${latest.version}，需要重新发布后生产入口才会生效。`,
    }
  }
  return {
    type: 'success' as const,
    label: '已发布同步',
    hint: `最新发布版本 ${latest.version} 已包含当前 SDK 图。`,
  }
})

const nodes = ref<CanvasNode[]>([])
const edges = ref<CanvasEdge[]>([])
const debugInputFields = computed<StudioFieldSchema[]>(() => {
  const userInputNode = nodes.value.find((node) => node.data.kind === 'userInput')
  return (userInputNode?.data.userInputConfig?.fields || [])
    .filter((field) => !!field.name?.trim())
})
const selectedNodeId = ref<string | null>(null)
const selectedEdgeId = ref<string | null>(null)
const nodeSearchKeyword = ref('')
const nodeSearchOpen = ref(false)
const canvasSearchOpen = ref(false)
const canvasSearchKeyword = ref('')
const canvasSearchIndex = ref(0)
const canvasSearchInputRef = ref()
const paletteExpanded = ref(false)
const activePaletteGroup = ref('')
const inspectorExpanded = ref(false)
const propertyPanelCollapsed = ref(true)
type PropertyDetailSection = 'base' | 'node' | 'debug' | 'trace'
const propertyDetailOpen = ref(false)
const propertyDetailSection = ref<PropertyDetailSection>('base')
const copiedNode = ref<CanvasNode | null>(null)
const historyPast = ref<string[]>([])
const historyFuture = ref<string[]>([])
const historyApplying = ref(false)
const historyReady = ref(false)
const selectedNode = computed(() =>
  nodes.value.find((n) => n.id === selectedNodeId.value) ?? null,
)
const propertyDetailTitle = computed(() => {
  const nodeLabel = selectedNode.value?.data.label || selectedNode.value?.id || '节点'
  const sectionLabel: Record<PropertyDetailSection, string> = {
    base: '基础',
    node: '节点配置',
    debug: '节点测试',
    trace: '运行记录',
  }
  return `${nodeLabel} · ${sectionLabel[propertyDetailSection.value]}`
})
const propertyDetailSectionLabel = computed(() => {
  const sectionLabel: Record<PropertyDetailSection, string> = {
    base: '基础设置',
    node: '节点配置',
    debug: '节点测试',
    trace: '运行记录',
  }
  return sectionLabel[propertyDetailSection.value]
})
const selectedEdge = computed(() =>
  edges.value.find((edge) => edge.id === selectedEdgeId.value) ?? null,
)
const selectedNodeIdsForAi = computed(() => {
  const ids = new Set<string>()
  for (const node of nodes.value) {
    if ((node as CanvasNode & { selected?: boolean }).selected) ids.add(node.id)
  }
  if (selectedNodeId.value) ids.add(selectedNodeId.value)
  return Array.from(ids)
})
const selectedEdgeIdsForAi = computed(() => {
  const ids = new Set<string>()
  for (const edge of edges.value) {
    if ((edge as CanvasEdge & { selected?: boolean }).selected) ids.add(edge.id)
  }
  if (selectedEdgeId.value) ids.add(selectedEdgeId.value)
  return Array.from(ids)
})
const aiEditContextLabel = computed(() => {
  const nodeCount = selectedNodeIdsForAi.value.length
  const edgeCount = selectedEdgeIdsForAi.value.length
  if (!nodeCount && !edgeCount) return '全局画布'
  const parts = []
  if (nodeCount) parts.push(`${nodeCount} 个节点`)
  if (edgeCount) parts.push(`${edgeCount} 条连线`)
  return `已选 ${parts.join(' / ')}`
})
const selectedEdgeSourceNode = computed(() =>
  selectedEdge.value ? nodes.value.find((node) => node.id === selectedEdge.value?.source) || null : null,
)
const selectedEdgeRouteOptions = computed(() => {
  const source = selectedEdgeSourceNode.value
  return sourceRouteOptions(source)
})
const graphNodeTypeCapabilities = ref<AgentGraphNodeTypeDescriptor[]>([])
const graphNodeTypeCapabilitiesLoaded = ref(false)
const selectedEdgeConditionOptions = computed(() => [
  { label: '默认', value: 'always' },
  { label: '成功', value: 'success' },
  { label: '失败', value: 'error' },
  { label: '为空', value: 'empty' },
  { label: '非空', value: 'not_empty' },
  { label: '否则', value: 'else' },
  ...selectedEdgeRouteOptions.value,
])

function sourceRouteOptions(source?: CanvasNode | null) {
  if (source?.data.kind === 'condition') {
    const groups = source.data.conditionConfig?.groups || []
    const routes = groups
      .filter((group) => group.id?.trim())
      .map((group) => ({
        label: group.label || `路由：${group.id}`,
        value: `route:${group.id}`,
      }))
    const defaultRoute = source.data.conditionConfig?.defaultRoute || 'else'
    return [
      ...routes,
      { label: `默认：${defaultRoute}`, value: defaultRoute === 'else' ? 'else' : `route:${defaultRoute}` },
    ]
  }
  if (source?.data.kind === 'classifier') {
    const classes = source.data.classifierConfig?.classes || []
    const routes = classes
      .filter((item) => item.id?.trim())
      .map((item) => ({
        label: item.label || `意图：${item.id}`,
        value: `route:${item.id}`,
      }))
    const defaultRoute = source.data.classifierConfig?.defaultRoute || 'else'
    return [
      ...routes,
      { label: `默认：${defaultRoute}`, value: defaultRoute === 'else' ? 'else' : `route:${defaultRoute}` },
    ]
  }
  if (source?.data.kind === 'approval') {
    return [
      { label: '批准', value: 'route:approved' },
      { label: '拒绝', value: 'route:rejected' },
      { label: '超时', value: 'route:timeout' },
    ]
  }
  if (source?.data.kind === 'loop') {
    return [
      { label: '继续', value: 'route:continue' },
      { label: '结束', value: 'route:done' },
    ]
  }
  return []
}

const selectedNodeTrace = computed(() =>
  selectedNodeId.value ? nodeTraceStates.value[selectedNodeId.value] || null : null,
)
const runtimeContextEntries = computed(() => {
  const state = nodeDebugResult.value?.outputState || debugResult.value?.metadata || {}
  return Object.entries(state)
    .filter(([key]) => ['input', 'answer', 'lastOutput', 'lastError', 'lastSuccess', 'lastRoute'].includes(key)
      || key === 'params'
      || key === 'sys'
      || key.startsWith('nodeOutput.')
      || key.startsWith('var.'))
    .map(([key, value]) => ({
      key,
      value: typeof value === 'string' ? value : JSON.stringify(value),
    }))
})
const selectedToolInfo = computed(() => {
  const refName = selectedNode.value?.data.kind === 'tool' ? selectedNode.value.data.toolConfig?.ref : ''
  return toolOptions.value.find((t) => t.name === refName) ?? null
})
const canvasStats = computed(() => {
  const executable = nodes.value.filter((n) => n.data.kind !== 'start' && n.data.kind !== 'end')
  return {
    nodes: nodes.value.length,
    edges: edges.value.length,
    toolLike: executable.length,
  }
})
const canUndo = computed(() => historyPast.value.length > 1)
const canRedo = computed(() => historyFuture.value.length > 0)
const lastSavedAt = computed(() => {
  const value = form.extra?.lastSavedAt || form.extra?.updatedAt || ''
  if (typeof value === 'string' && value) return value.slice(11, 16) || value
  return '--:--'
})
const saveStateText = computed(() => lastSavedAt.value === '--:--' ? '尚未保存' : `已保存 ${lastSavedAt.value}`)

const saveBadgeText = computed(() => {
  if (saving.value) return '保存中'
  return lastSavedAt.value === '--:--' ? '待保存' : `已保存 ${lastSavedAt.value}`
})
const saveStateClass = computed(() => ({
  'is-saving': saving.value,
  'is-pending': !saving.value && lastSavedAt.value === '--:--',
  'is-saved': !saving.value && lastSavedAt.value !== '--:--',
}))

type GraphLintItem = {
  level: 'error' | 'warning'
  message: string
  nodeId?: string
  edgeId?: string
}

const graphLintItems = computed<GraphLintItem[]>(() => {
  const items: GraphLintItem[] = []
  const nodeIds = new Set(nodes.value.map((node) => node.id))
  const incoming = new Map<string, number>()
  const outgoing = new Map<string, number>()
  for (const edge of edges.value) {
    if (!nodeIds.has(edge.source)) {
      items.push({ level: 'error', edgeId: edge.id, message: `连线来源不存在：${edge.source}` })
    }
    if (!nodeIds.has(edge.target)) {
      items.push({ level: 'error', edgeId: edge.id, message: `连线目标不存在：${edge.target}` })
    }
    outgoing.set(edge.source, (outgoing.get(edge.source) || 0) + 1)
    incoming.set(edge.target, (incoming.get(edge.target) || 0) + 1)
    if (!isSupportedCanvasCondition(edge.condition || edge.label)) {
      items.push({ level: 'warning', edgeId: edge.id, message: `连线条件可能无法命中：${edge.condition || edge.label}` })
    }
  }
  const startCount = nodes.value.filter((node) => node.data.kind === 'start').length
  const endCount = nodes.value.filter((node) => node.data.kind === 'end').length
  if (startCount !== 1) items.push({ level: 'error', message: `画布需要且仅需要 1 个开始节点，当前 ${startCount} 个` })
  if (endCount !== 1) items.push({ level: 'error', message: `画布需要且仅需要 1 个结束节点，当前 ${endCount} 个` })
  if (!(outgoing.get('start') || 0)) items.push({ level: 'error', nodeId: 'start', message: '开始节点没有出边' })
  if (!(incoming.get('end') || 0)) items.push({ level: 'warning', nodeId: 'end', message: '结束节点没有入边，保存时会尝试自动补边' })

  for (const node of nodes.value) {
    if (node.data.kind === 'start' || node.data.kind === 'end') continue
    if (!(incoming.get(node.id) || 0)) {
      items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 没有入边` })
    }
    if (!(outgoing.get(node.id) || 0)) {
      items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 没有出边` })
    }
    if ((node.data.kind === 'tool' || node.data.kind === 'skill') && !node.data.toolConfig?.ref) {
      items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 未选择引用能力` })
    }
    const mapping = nodeInputMapping(node.data)
    for (const input of node.data.inputs || []) {
      const target = input.id || input.name || ''
      if (input.required && target && !mapping[target] && !input.source) {
        items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 必填输入未绑定：${target}` })
      }
      const source = mapping[target] || input.source || ''
      if (source && isProbablyVariableReference(source) && !isKnownVariableReference(source, node.id)) {
        items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 引用了不存在的变量：${source}` })
      }
    }
    if (node.data.kind === 'userInput') {
      const fields = node.data.userInputConfig?.fields || []
      if (!fields.length) {
        items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 没有输入字段` })
      }
      const seenFields = new Set<string>()
      for (const field of fields) {
        const name = field.name?.trim()
        if (!name) {
          items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 存在未命名输入字段` })
          continue
        }
        if (seenFields.has(name)) {
          items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 输入字段重复：${name}` })
        }
        seenFields.add(name)
      }
    }
    if (node.data.kind === 'knowledge' && !(node.data.knowledgeConfig?.knowledgeBaseCodes || []).length) {
      items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 未配置知识库` })
    }
    if (node.data.kind === 'http' && !node.data.httpConfig?.url) {
      items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 未配置 URL` })
    }
    if (node.data.kind === 'variable' && Object.keys(node.data.assignments || {}).length === 0) {
      items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 没有变量赋值` })
    }
    if (node.data.kind === 'parameter' && !(node.data.parameterConfig?.fields || []).length) {
      items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 没有参数字段` })
    }
    if (node.data.kind === 'template' && !node.data.template) {
      items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 没有模板内容` })
    }
    if (node.data.kind === 'answer' && !node.data.answerConfig?.template) {
      items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 没有回复模板` })
    }
    if (node.data.kind === 'code' && !Object.keys(node.data.codeConfig?.outputs || {}).length) {
      items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 没有输出字段` })
    }
    if (node.data.kind === 'classifier' && !(node.data.classifierConfig?.classes || []).length) {
      items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 没有分类分支` })
    }
    if (node.data.kind === 'classifier' && (outgoing.get(node.id) || 0) > 1) {
      const routeEdges = edges.value.filter((edge) => edge.source === node.id && isRouteCondition(edge.condition || edge.label))
      if (!routeEdges.length) {
        items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 有多条出边，但还没有配置 route 分支条件` })
      }
    }
    if (node.data.kind === 'aggregate' && !(node.data.aggregateConfig?.items || []).length) {
      items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 没有聚合项` })
    }
    if (node.data.kind === 'approval' && !node.data.approvalConfig?.prompt) {
      items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 没有确认内容` })
    }
    if (node.data.kind === 'loop' && (node.data.loopConfig?.maxIterations || 0) < 1) {
      items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 循环次数必须大于 0` })
    }
    if (node.data.kind === 'knowledgeWrite' && !node.data.knowledgeWriteConfig?.knowledgeBaseCode) {
      items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 未选择写入知识库` })
    }
    if (node.data.kind === 'documentExtract' && !node.data.documentExtractConfig?.sourceExpression) {
      items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 没有文档来源表达式` })
    }
    if (node.data.kind === 'mcp' && !node.data.mcpConfig?.toolName) {
      items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 未配置 MCP 工具名称` })
    }
  }
  return items
})

const graphLintErrors = computed(() => graphLintItems.value.filter((item) => item.level === 'error'))
const graphLintWarnings = computed(() => graphLintItems.value.filter((item) => item.level === 'warning'))

const graphVariables = computed(() => {
  const vars: { name: string; source: string; nodeId: string; label?: string; group?: string; description?: string }[] = []
  for (const node of nodes.value) {
    if (node.data.kind === 'start' || node.data.kind === 'end') continue
    if (node.data.kind === 'userInput') {
      const alias = node.data.userInputConfig?.outputAlias || node.data.outputAlias || 'params'
      const nodeLabel = node.data.label || node.id
      vars.push({ name: alias, source: nodeLabel, nodeId: node.id, label: `${nodeLabel} · 全部输入`, group: '用户输入', description: alias })
      for (const field of node.data.userInputConfig?.fields || []) {
        const name = field.name?.trim()
        if (name) {
          vars.push({
            name: `${alias}.${name}`,
            source: nodeLabel,
            nodeId: node.id,
            label: `${nodeLabel} · ${field.description || field.name}`,
            group: '用户输入',
            description: `${alias}.${name}`,
          })
        }
      }
      continue
    }
    if (node.data.outputAlias) {
      vars.push({
        name: node.data.outputAlias,
        source: node.data.label || node.id,
        nodeId: node.id,
        label: `${node.data.label || node.id} · 输出`,
        group: '节点输出',
        description: `业务别名：${node.data.outputAlias}`,
      })
    }
    for (const key of Object.keys(node.data.assignments || {})) {
      vars.push({ name: key, source: node.data.label || node.id, nodeId: node.id, label: `${node.data.label || node.id} · ${key}`, group: '节点输出', description: key })
    }
    for (const key of (node.data.parameterConfig?.fields || []).map((field) => field.name).filter(Boolean)) {
      vars.push({ name: key, source: node.data.label || node.id, nodeId: node.id, label: `${node.data.label || node.id} · ${key}`, group: '节点输出', description: key })
    }
    for (const key of Object.keys(node.data.codeConfig?.outputs || {})) {
      vars.push({ name: key, source: node.data.label || node.id, nodeId: node.id, label: `${node.data.label || node.id} · ${key}`, group: '节点输出', description: key })
    }
    for (const key of (node.data.aggregateConfig?.items || []).map((item) => item.name).filter(Boolean)) {
      vars.push({ name: key, source: node.data.label || node.id, nodeId: node.id, label: `${node.data.label || node.id} · ${key}`, group: '节点输出', description: key })
    }
    for (const port of node.data.outputs || []) {
      const id = port.id || port.name
      if (!id) continue
      vars.push({
        name: id,
        source: node.data.label || node.id,
        nodeId: node.id,
        label: `${node.data.label || node.id} · ${port.name || port.id}`,
        group: '节点输出',
        description: id,
      })
    }
  }
  const seen = new Set<string>()
  return vars.filter((item) => {
    const key = `${item.name}@${item.nodeId}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
})

function nodeInputMapping(data: CanvasNode['data']) {
  if (data.kind === 'tool' || data.kind === 'skill') return data.toolConfig?.inputMapping || data.inputMapping || {}
  if (data.kind === 'mcp') return data.mcpConfig?.inputMapping || data.inputMapping || {}
  return data.inputMapping || {}
}

function isProbablyVariableReference(source: string) {
  const value = source.trim()
  if (!value || value.startsWith('const:') || value.startsWith('"') || value.startsWith("'")) return false
  if (['true', 'false', 'null'].includes(value)) return false
  return Number.isNaN(Number(value))
}

function isKnownVariableReference(source: string, currentNodeId?: string) {
  const value = source.trim().replace(/^\$/, '')
  if (!value) return true
  if (['input', 'answer', 'lastOutput', 'previousOutput', 'lastRoute', 'lastSuccess', 'lastError', 'params', 'sys'].includes(value)) return true
  if (value.startsWith('params.') || value.startsWith('sys.')) return true
  if (value.startsWith('nodeOutput.')) {
    const nodeId = value.slice('nodeOutput.'.length).split('.', 1)[0]
    return nodes.value.some((node) => node.id === nodeId)
  }
  if (value.startsWith('var.')) {
    const alias = value.slice('var.'.length).split('.', 1)[0]
    return graphVariables.value.some((item) => item.name === alias)
  }
  const alias = value.split('.', 1)[0]
  return graphVariables.value.some((item) => item.name === alias || item.nodeId === alias) || alias === currentNodeId
}

const variablePickerOptions = computed<StudioVariableOption[]>(() => {
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

const canvasSearchMatches = computed(() => {
  const keyword = canvasSearchKeyword.value.trim().toLowerCase()
  if (!keyword) return []
  return nodes.value.filter((node) => {
    const data = node.data
    const haystack = [
      node.id,
      data.label,
      data.kind,
      data.description,
      data.outputAlias,
      data.toolConfig?.ref,
      data.toolConfig?.qualifiedName,
      data.httpConfig?.url,
      data.knowledgeConfig?.knowledgeBaseCodes?.join(' '),
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    return haystack.includes(keyword)
  })
})

const defaultEdgeOptions = {
  type: 'smoothstep',
  markerEnd: 'arrowclosed',
  interactionWidth: 18,
}

const connectionLineOptions = {
  type: ConnectionLineType.SmoothStep,
}

const publishForm = reactive({
  version: 'v1.0.0',
  rolloutPercent: 100,
  note: '',
  publishedBy: '',
})

const publishWarnings = computed(() => {
  const warnings: string[] = []
  const toolCount = nodes.value.filter((n) => n.data.kind === 'tool' && n.data.toolConfig?.ref).length
  const skillCount = nodes.value.filter((n) => n.data.kind === 'skill' && n.data.toolConfig?.ref).length
  if (!form.keySlug) {
    warnings.push('未配置 keySlug，业务系统可能无法通过发布端点稳定访问。')
  }
  if (!form.systemPrompt || form.systemPrompt.length < 20) {
    warnings.push('系统提示词较短，建议补充角色、边界和失败处理策略。')
  }
  if (toolCount + skillCount === 0) {
    warnings.push('画布中没有可调用工具或能力，本版本只能进行纯对话。')
  }
  if (form.allowIrreversible) {
    warnings.push('已允许不可逆工具调用，请确认工具权限与限流已配置。')
  }
  warnings.push(...projectBoundaryWarnings())
  if (publishForm.rolloutPercent === 100) {
    warnings.push('本次为全量发布，会替换该 Agent 的历史 ACTIVE 全量版本。')
  }
  return warnings
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

type PaletteItem = { kind: CanvasNodeKind; label: string; meta: string; icon: Component; hint: string }
type PaletteGroup = { title: string; icon: Component; items: PaletteItem[] }

const nodeIconMap: Record<CanvasNodeKind, Component> = {
  start: ArrowLeft,
  end: Finished,
  userInput: SetUp,
  llm: Cpu,
  skill: Briefcase,
  tool: Tools,
  knowledge: Coin,
  condition: Switch,
  variable: SetUp,
  template: Document,
  parameter: MagicStick,
  http: Link,
  answer: Finished,
  code: Document,
  classifier: Switch,
  aggregate: SetUp,
  approval: Finished,
  loop: RefreshRight,
  knowledgeWrite: Collection,
  documentExtract: Files,
  mcp: Connection,
}

const groupIconMap: Record<string, Component> = {
  Cpu,
  Operation,
  Connection,
  Collection,
}

const graphNodeCapabilityByKind = computed(() => studioNodeCapabilityMap(graphNodeTypeCapabilities.value))
const enabledPaletteKinds = computed(() =>
  enabledStudioNodeKinds(graphNodeTypeCapabilities.value, graphNodeTypeCapabilitiesLoaded.value),
)

const paletteGroups = computed<PaletteGroup[]>(() => STUDIO_NODE_GROUPS.map((group) => ({
  title: group.title,
  icon: groupIconMap[group.icon] || Operation,
  items: Object.values(STUDIO_NODE_REGISTRY)
    .filter((item) =>
      item.group === group.title
      && item.category !== 'system'
      && enabledPaletteKinds.value.has(item.kind),
    )
    .map((item) => {
      const capability = graphNodeCapabilityByKind.value[item.kind]
      return {
        kind: item.kind,
        label: item.label,
        meta: capability?.type || item.meta,
        icon: nodeIconMap[item.kind],
        hint: item.hint,
      }
    }),
})).filter((group) => group.items.length))

const filteredPaletteGroups = computed(() => {
  const keyword = nodeSearchKeyword.value.trim().toLowerCase()
  if (!keyword) return paletteGroups.value
  return paletteGroups.value
    .map((group) => ({
      ...group,
      items: group.items.filter((item) =>
        [item.kind, item.label, item.meta, item.hint, group.title]
          .join(' ')
          .toLowerCase()
          .includes(keyword),
      ),
    }))
    .filter((group) => group.items.length)
})

const flatFilteredPalette = computed(() => filteredPaletteGroups.value.flatMap((group) => group.items))

function onDragStart(event: DragEvent, kind: CanvasNodeKind) {
  if (studioReadOnly.value) {
    event.preventDefault()
    return
  }
  event.dataTransfer?.setData('application/vueflow', kind)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}

function onDrop(event: DragEvent) {
  if (studioReadOnly.value) {
    return
  }
  const kind = event.dataTransfer?.getData('application/vueflow') as CanvasNodeKind | undefined
  if (!kind) return
  const position = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  addCanvasNode(kind, position)
}

function handleAddNode(kind: CanvasNodeKind) {
  if (studioReadOnly.value) {
    return
  }
  const position = {
    x: 320 + Math.max(0, nodes.value.length - 2) * 32,
    y: 160 + Math.max(0, nodes.value.length - 2) * 24,
  }
  addCanvasNode(kind, position)
}

function addCanvasNode(kind: CanvasNodeKind, position: { x: number; y: number }) {
  const id = `${kind}-${Date.now()}`
  nodes.value.push({
    id,
    type: kind,
    position,
    data: createDefaultNodeData(kind, studioNodeDefaultLabel(kind), form),
  })
  selectedNodeId.value = id
  selectedEdgeId.value = null
}

function addNodeFromSearch(kind: CanvasNodeKind) {
  handleAddNode(kind)
  nodeSearchOpen.value = false
}

function portSummary(ports: CanvasNode['data']['inputs'] | CanvasNode['data']['outputs'], fallback: string) {
  if (!ports?.length) return `${fallback} 0`
  return `${fallback} ${ports.map((port) => port.name || port.id).slice(0, 3).join(', ')}`
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

function sourceLabel(value?: string | null) {
  const normalized = String(value || 'CANVAS').toUpperCase()
  if (normalized === 'SDK') return 'SDK 托管'
  if (normalized === 'CANVAS') return '画布创建'
  return value || '画布创建'
}

function categoryLabel(value?: string | null) {
  const normalized = String(value || '').toLowerCase()
  const labels: Record<string, string> = {
    system: '系统',
    custom: '自定义',
    sdk: 'SDK',
  }
  return labels[normalized] || value || '系统'
}

function nodeKindLabel(kind?: string | null) {
  return studioNodeLabel(kind)
}

function currentSnapshotText() {
  return JSON.stringify({ nodes: nodes.value, edges: edges.value })
}

function restoreSnapshotText(text: string) {
  historyApplying.value = true
  try {
    const parsed = JSON.parse(text) as { nodes: CanvasNode[]; edges: CanvasEdge[] }
    nodes.value = parsed.nodes || []
    edges.value = parsed.edges || []
    decorateEdges()
    selectedNodeId.value = null
    selectedEdgeId.value = null
  } finally {
    nextTick(() => {
      historyApplying.value = false
    })
  }
}

function pushHistorySnapshot() {
  const text = currentSnapshotText()
  if (historyPast.value[historyPast.value.length - 1] === text) return
  historyPast.value.push(text)
  if (historyPast.value.length > 80) historyPast.value.shift()
  historyFuture.value = []
}

function undoCanvas() {
  if (!canUndo.value) return
  const current = historyPast.value.pop()
  if (current) historyFuture.value.unshift(current)
  const previous = historyPast.value[historyPast.value.length - 1]
  if (previous) restoreSnapshotText(previous)
}

function redoCanvas() {
  const next = historyFuture.value.shift()
  if (!next) return
  historyPast.value.push(next)
  restoreSnapshotText(next)
}

function copySelectedNode() {
  if (!selectedNode.value || ['start', 'end'].includes(selectedNode.value.data.kind)) return
  copiedNode.value = JSON.parse(JSON.stringify(selectedNode.value))
  ElMessage.success('已复制节点')
}

function pasteCopiedNode() {
  if (studioReadOnly.value || !copiedNode.value) return
  const copy = JSON.parse(JSON.stringify(copiedNode.value)) as CanvasNode
  const id = `${copy.data.kind}-${Date.now()}`
  copy.id = id
  copy.position = { x: copy.position.x + 48, y: copy.position.y + 48 }
  copy.data = {
    ...copy.data,
    label: `${copy.data.label || copy.data.kind} Copy`,
    source: 'CANVAS',
  }
  nodes.value.push(copy)
  selectedNodeId.value = id
  selectedEdgeId.value = null
}

function toggleSelectedNodeCollapsed() {
  if (!selectedNode.value) return
  selectedNode.value.data.collapsed = !selectedNode.value.data.collapsed
}

function defaultOutputAlias(kind: CanvasNodeKind, index: number) {
  if (kind === 'userInput') {
    return 'params'
  }
  if (kind !== 'start' && kind !== 'end' && kind !== 'llm' && kind !== 'condition') {
    return `${kind}_${index}`
  }
  return ''
}

function onConnect(connection: { source?: string | null; target?: string | null; sourceHandle?: string | null; targetHandle?: string | null }) {
  if (studioReadOnly.value || !connection.source || !connection.target) {
    return
  }
  const source = nodes.value.find((node) => node.id === connection.source)
  const sourceHandle = connection.sourceHandle || undefined
  const targetHandle = connection.targetHandle || undefined
  if (source?.data.kind === 'classifier' && !sourceHandle) {
    ElMessage.warning('意图分类节点只能从具体分类分支拖出连线')
    return
  }
  const condition = connectionCondition(source, sourceHandle)
  edges.value.push(decorateEdge({
    id: `e-${connection.source}-${connection.target}-${Date.now()}`,
    source: connection.source,
    target: connection.target,
    sourceHandle,
    targetHandle,
    condition,
    label: condition,
  }))
}

function connectionCondition(source?: CanvasNode | null, sourceHandle?: string) {
  if (!sourceHandle) return 'always'
  if (['condition', 'classifier', 'approval', 'loop'].includes(source?.data.kind || '')) {
    const normalized = sourceHandle.trim()
    if (!normalized) return 'always'
    return normalized === 'else' || normalized === 'default' ? 'else' : `route:${normalized}`
  }
  return 'always'
}

function decorateEdge(edge: CanvasEdge): CanvasEdge {
  const condition = edge.condition || edge.label || 'always'
  return {
    ...edge,
    type: edge.type || 'smoothstep',
    markerEnd: edge.markerEnd || 'arrowclosed',
    interactionWidth: edge.interactionWidth || 18,
    animated: edge.animated ?? isDynamicCondition(condition),
    label: edgeDisplayLabel(condition, edge) || undefined,
    class: edgeRuntimeClass(edge, condition),
  }
}

function edgeDisplayLabel(condition?: string, edge?: CanvasEdge | null) {
  const raw = (condition || '').trim()
  const normalized = raw.toLowerCase()
  const source = edge ? nodes.value.find((node) => node.id === edge.source) : null
  if (!raw || normalized === 'always' || normalized === 'default') {
    return source?.data.kind === 'condition' || source?.data.kind === 'classifier' || source?.data.kind === 'approval' || source?.data.kind === 'loop' ? '默认' : ''
  }
  const labels: Record<string, string> = {
    success: '成功',
    error: '失败',
    failure: '失败',
    else: '否则',
    empty: '为空',
    not_empty: '非空',
  }
  if (labels[normalized]) return labels[normalized]
  if (normalized.startsWith('route:')) return raw.slice('route:'.length).trim() || '分支'
  if (normalized.startsWith('contains:')) return `包含 ${raw.slice('contains:'.length).trim()}`
  if (normalized.startsWith('not_contains:')) return `不含 ${raw.slice('not_contains:'.length).trim()}`
  if (normalized.startsWith('equals:')) return `等于 ${raw.slice('equals:'.length).trim()}`
  if (normalized.startsWith('not_equals:')) return `不等于 ${raw.slice('not_equals:'.length).trim()}`
  return raw
}

function decorateEdges() {
  edges.value = edges.value.map(decorateEdge)
}

function isDynamicCondition(condition?: string) {
  const normalized = (condition || '').trim().toLowerCase()
  return !!normalized && normalized !== 'always' && normalized !== 'default'
}

function isSupportedCanvasCondition(condition?: string) {
  const normalized = (condition || '').trim().toLowerCase()
  if (!normalized) return true
  if (['always', 'default', 'else', 'success', 'error', 'failure', 'empty', 'not_empty'].includes(normalized)) {
    return true
  }
  return normalized.startsWith('contains:')
    || normalized.startsWith('not_contains:')
    || normalized.startsWith('equals:')
    || normalized.startsWith('not_equals:')
    || normalized.startsWith('route:')
}

function isRouteCondition(condition?: string) {
  const normalized = (condition || '').trim().toLowerCase()
  return normalized === 'else' || normalized === 'default' || normalized.startsWith('route:')
}

function edgeKey(source?: string, target?: string) {
  return `${source || ''}->${target || ''}`
}

function edgeRuntimeClass(edge: CanvasEdge, rawCondition?: string) {
  const condition = (rawCondition || edge.condition || edge.label || '').trim()
  const source = nodes.value.find((node) => node.id === edge.source)
  const route = lastRouteForNode(edge.source)
  const classes: string[] = []
  const key = edgeKey(edge.source, edge.target)
  if (workflowHitEdgeKeys.value.has(key)) {
    classes.push('edge-route-hit')
  } else if (workflowPath.value.length && workflowPathSourceNodeIds.value.has(edge.source)) {
    classes.push('edge-route-miss')
  }
  if ((source?.data.kind === 'condition' || source?.data.kind === 'classifier' || source?.data.kind === 'approval' || source?.data.kind === 'loop') && route) {
    const expected = condition.toLowerCase().startsWith('route:')
      ? condition.slice('route:'.length).trim()
      : condition === 'else' || condition === 'default'
        ? 'else'
        : ''
    if (expected) {
      classes.push(expected === route ? 'edge-route-hit' : 'edge-route-miss')
    }
  }
  const state = nodeDebugState(edge.source)
  if (state?.status === 'error' && ['error', 'failure'].includes(condition.toLowerCase())) {
    classes.push('edge-route-hit')
  }
  if (state?.status === 'success' && condition.toLowerCase() === 'success') {
    classes.push('edge-route-hit')
  }
  return classes.join(' ')
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

function focusLintItem(item: GraphLintItem) {
  if (item.nodeId) {
    selectedNodeId.value = item.nodeId
    selectedEdgeId.value = null
    return
  }
  if (item.edgeId) {
    selectedEdgeId.value = item.edgeId
    selectedNodeId.value = null
  }
}

function nodeDebugState(nodeId: string) {
  return nodeTraceStates.value[nodeId] || null
}

function nodeRunClass(nodeId: string) {
  const state = nodeDebugState(nodeId)
  const classes: string[] = []
  if (currentDebugNodeId.value === nodeId) {
    classes.push('run-current')
  }
  if (!state) return classes.join(' ')
  if (state.status === 'waiting') {
    classes.push('run-waiting')
  } else {
    classes.push(state.status === 'success' ? 'run-success' : 'run-error')
  }
  return classes.join(' ')
}

function nodeRunLabel(nodeId: string) {
  const state = nodeDebugState(nodeId)
  if (!state) return ''
  const prefix = nodeTraceStatusText(state.status)
  return `${prefix} ${formatElapsed(state.elapsedMs)}`
}

function nodeTraceStatusText(status: NodeTraceState['status']) {
  if (status === 'waiting') return '等待'
  return status === 'success' ? '成功' : '失败'
}

function nodeTraceTagType(status: NodeTraceState['status']) {
  if (status === 'waiting') return 'warning'
  return status === 'success' ? 'success' : 'danger'
}

function openNodeTrace(nodeId: string) {
  selectedNodeId.value = nodeId
  selectedEdgeId.value = null
  currentDebugNodeId.value = nodeId
  focusDebugNode(nodeId)
  const debugStepIndex = debugRunResult.value?.steps?.findIndex((step) => step.nodeId === nodeId) ?? -1
  if (debugStepIndex >= 0) {
    selectedDebugStepIndex.value = debugStepIndex
  }
}

function formatElapsed(value?: number) {
  if (value === null || value === undefined) return '-'
  return `${value}ms`
}

function prettyTracePayload(value?: string) {
  if (!value) return ''
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
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

function openCanvasSearch() {
  canvasSearchOpen.value = true
  nextTick(() => {
    canvasSearchInputRef.value?.focus?.()
  })
}

function closeCanvasSearch() {
  canvasSearchOpen.value = false
  canvasSearchKeyword.value = ''
  canvasSearchIndex.value = 0
}

function focusCanvasSearchMatch(index = canvasSearchIndex.value) {
  const matches = canvasSearchMatches.value
  if (!matches.length) return
  const normalized = ((index % matches.length) + matches.length) % matches.length
  canvasSearchIndex.value = normalized
  const node = matches[normalized]
  selectedNodeId.value = node.id
  selectedEdgeId.value = null
  propertyPanelCollapsed.value = false
  setCenter(node.position.x + 110, node.position.y + 70, { zoom: 1, duration: 260 })
}

function focusNextCanvasSearch() {
  focusCanvasSearchMatch(canvasSearchIndex.value + 1)
}

function focusPrevCanvasSearch() {
  focusCanvasSearchMatch(canvasSearchIndex.value - 1)
}

async function handleAutoLayout() {
  const rank = graphRanks()
  const lanes = new Map<number, number>()
  nodes.value = nodes.value.map((node) => {
    const level = rank.get(node.id) ?? (node.data.kind === 'end' ? maxRank(rank) + 1 : 1)
    const lane = lanes.get(level) ?? 0
    lanes.set(level, lane + 1)
    return {
      ...node,
      position: {
        x: 80 + level * 260,
        y: 120 + lane * 150,
      },
    }
  })
  await handleFitView()
}

function graphRanks() {
  const ranks = new Map<string, number>()
  const outgoing = new Map<string, string[]>()
  for (const edge of edges.value) {
    outgoing.set(edge.source, [...(outgoing.get(edge.source) || []), edge.target])
  }
  const queue: string[] = ['start']
  ranks.set('start', 0)
  for (let i = 0; i < queue.length; i++) {
    const current = queue[i]
    const currentRank = ranks.get(current) ?? 0
    for (const target of outgoing.get(current) || []) {
      const nextRank = currentRank + 1
      if ((ranks.get(target) ?? -1) < nextRank) {
        ranks.set(target, nextRank)
        queue.push(target)
      }
    }
  }
  return ranks
}

function maxRank(ranks: Map<string, number>) {
  return Math.max(0, ...Array.from(ranks.values()))
}

function formatInputMapping(mapping?: Record<string, string>) {
  if (!mapping || Object.keys(mapping).length === 0) {
    return ''
  }
  return Object.entries(mapping)
    .map(([key, value]) => `${key} = ${value}`)
    .join('\n')
}

function parseInputMapping(text: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const rawLine of (text || '').split('\n')) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) {
      continue
    }
    const idx = line.indexOf('=')
    if (idx <= 0) {
      continue
    }
    const key = line.slice(0, idx).trim()
    const value = line.slice(idx + 1).trim()
    if (key && value) {
      out[key] = value
    }
  }
  return out
}

function updateSelectedInputMapping(text: string) {
  if (!selectedNode.value) {
    return
  }
  selectedNode.value.data.toolConfig ||= { inputMapping: {} }
  selectedNode.value.data.toolConfig.inputMapping = parseInputMapping(text)
}

function formatAssignments(assignments?: Record<string, string>) {
  return formatInputMapping(assignments)
}

function updateSelectedAssignments(text: string) {
  if (!selectedNode.value) {
    return
  }
  selectedNode.value.data.assignments = parseInputMapping(text)
}

function updateSelectedParameters(text: string) {
  if (!selectedNode.value) {
    return
  }
  selectedNode.value.data.parameterConfig ||= { mode: 'expression', fields: [] }
  selectedNode.value.data.parameterConfig.fields = Object.entries(parseInputMapping(text)).map(([name, source]) => ({
    name,
    source,
    type: 'string',
    required: false,
  }))
}

function updateSelectedHeaders(text: string) {
  if (!selectedNode.value) {
    return
  }
  selectedNode.value.data.httpConfig ||= {
    method: 'GET',
    url: '',
    queryParams: {},
    headers: {},
    bodyType: 'none',
    body: '',
    timeoutMs: 30000,
  }
  selectedNode.value.data.httpConfig.headers = parseInputMapping(text)
}

function assignmentCount(assignments?: Record<string, string>) {
  return assignments ? Object.keys(assignments).length : 0
}

function userInputFieldCount(data: CanvasNode['data']) {
  return data.userInputConfig?.fields?.filter((field) => !!field.name?.trim()).length || 0
}

function conditionRoutePills(data: CanvasNode['data']) {
  const groups = data.conditionConfig?.groups || []
  const labels = groups
    .map((group) => group.label || group.id)
    .filter((item): item is string => !!item)
    .slice(0, 3)
  if (labels.length) {
    return labels
  }
  const outputNames = (data.outputs || [])
    .map((port) => port.name || port.id)
    .filter(Boolean)
    .slice(0, 3)
  return outputNames.length ? outputNames : ['成功', '失败', '否则']
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

async function refreshParamHints() {
  paramHints.value = []
  const tool = selectedToolInfo.value
  if (!tool?.projectId || !tool.name) {
    return
  }
  try {
    const { data } = await getApiGraphParamHints(tool.projectId, tool.name)
    paramHints.value = Array.isArray(data) ? data : []
  } catch {
    paramHints.value = []
  }
}

function applyParamHint(hint: ApiGraphParamSourceHint) {
  if (!selectedNode.value) {
    return
  }
  selectedNode.value.data.toolConfig ||= { inputMapping: {} }
  const mapping = { ...(selectedNode.value.data.toolConfig.inputMapping || {}) }
  mapping[hint.targetPath] = `${hint.sourceApi}.${hint.sourcePath}`
  selectedNode.value.data.toolConfig.inputMapping = mapping
  if (!selectedNode.value.data.toolConfig.mappingNote) {
    selectedNode.value.data.toolConfig.mappingNote = '参数来源由接口图谱确认关系生成。'
  }
}

function capabilityLabel(item: ToolInfo | CapabilityInfo) {
  const project = item.projectCode ? ` / ${item.projectCode}` : ''
  const visibility = item.visibility ? ` / ${item.visibility}` : ''
  const desc = item.description ? ` - ${item.description.slice(0, 32)}` : ''
  return `${item.name}${project}${visibility}${desc}`
}

function findCapability(kind: 'tool' | 'skill', name?: string) {
  if (!name) return null
  const source = kind === 'tool' ? toolOptions.value : capabilityOptions.value
  return source.find((item) => item.name === name) || null
}

function handleNodeRefChange(kind: 'tool' | 'skill') {
  if (!selectedNode.value) return
  selectedNode.value.data.toolConfig ||= { inputMapping: {} }
  const capability = findCapability(kind, selectedNode.value.data.toolConfig.ref)
  selectedNode.value.data.toolConfig.qualifiedName = capability?.qualifiedName || null
  selectedNode.value.data.toolConfig.projectCode = capability?.projectCode || null
  selectedNode.value.data.toolConfig.visibility = capability?.visibility || null
  selectedNode.value.data.description = capability?.description || selectedNode.value.data.description || ''
}

function nodeKindWarnLabel(kind: string) {
  return nodeKindLabel(kind)
}

function projectBoundaryWarnings() {
  const warnings: string[] = []
  for (const node of nodes.value) {
    if (node.data.kind !== 'tool' && node.data.kind !== 'skill') continue
    const ref = node.data.toolConfig?.ref
    if (!ref) continue
    const capability = findCapability(node.data.kind, ref)
    if (!capability) {
      warnings.push(
        `${nodeKindWarnLabel(node.data.kind)} ${ref} 不在当前项目能力调色板中，请确认是否已下线或跨项目引用。`,
      )
      continue
    }
    const sameProject = !capability.projectId || capability.projectId === form.projectId
    const shared = capability.visibility === 'SHARED' || capability.visibility === 'PUBLIC'
    if (!sameProject && !shared) {
      warnings.push(
        `${nodeKindWarnLabel(node.data.kind)} ${ref} 属于 ${capability.projectCode || '其他项目'}，且不是 SHARED / PUBLIC。`,
      )
    }
  }
  return warnings
}

function onNodeClick(evt: { node: { id: string } }) {
  selectedNodeId.value = evt.node.id
  selectedEdgeId.value = null
}

function onNodeDoubleClick(evt: { node: { id: string } }) {
  selectedNodeId.value = evt.node.id
  selectedEdgeId.value = null
}

function openPropertyDetail(section: PropertyDetailSection) {
  if (!selectedNode.value) return
  propertyDetailSection.value = section
  propertyDetailOpen.value = true
}

function onEdgeClick(evt: { edge: { id: string } }) {
  selectedEdgeId.value = evt.edge.id
  selectedNodeId.value = null
}

function clearSelection() {
  selectedNodeId.value = null
  selectedEdgeId.value = null
  propertyDetailOpen.value = false
}

function syncSelectedEdgeLabel() {
  if (!selectedEdge.value) return
  const condition = selectedEdge.value.condition?.trim() || ''
  selectedEdge.value.label = edgeDisplayLabel(condition, selectedEdge.value) || undefined
  selectedEdge.value.animated = isDynamicCondition(condition)
  selectedEdge.value.class = edgeRuntimeClass(selectedEdge.value, condition)
}

function applySelectedEdgeCondition(condition: string) {
  if (!selectedEdge.value) return
  selectedEdge.value.condition = condition
  syncSelectedEdgeLabel()
}

function refreshEdgeRuntimeClasses() {
  edges.value = edges.value.map((edge) => ({
    ...edge,
    class: edgeRuntimeClass(edge),
  }))
}

function deleteSelectedNode() {
  if (studioReadOnly.value) return
  if (!selectedNode.value) return
  if (selectedNode.value.data.kind === 'start' || selectedNode.value.data.kind === 'end') {
    ElMessage.warning('开始 / 结束节点不可删除')
    return
  }
  const id = selectedNode.value.id
  nodes.value = nodes.value.filter((n) => n.id !== id)
  edges.value = edges.value.filter((e) => e.source !== id && e.target !== id)
  selectedNodeId.value = null
  propertyDetailOpen.value = false
}

async function loadAgent() {
  if (isNew) {
    nodes.value = [
      {
        id: 'start',
        type: 'start',
        position: { x: 60, y: 220 },
        data: createDefaultNodeData('start', '开始', form),
      },
      {
        id: 'end',
        type: 'end',
        position: { x: 500, y: 220 },
        data: createDefaultNodeData('end', '结束', form),
      },
    ]
    edges.value = [decorateEdge({ id: 'e-start-end', source: 'start', target: 'end', condition: 'always', label: 'always' })]
    return
  }
  try {
    const { data } = await getAgent(agentId)
    Object.assign(form, {
      keySlug: data.keySlug ?? '',
      name: data.name,
      description: data.description || '',
      projectId: data.projectId ?? null,
      projectCode: data.projectCode ?? null,
      visibility: data.visibility || 'PRIVATE',
      intentType: data.intentType || '',
      systemPrompt: data.systemPrompt || '',
      tools: data.tools || [],
      skills: data.skills || [],
      modelInstanceId: data.modelInstanceId || '',
      runtimeType: data.runtimeType || 'AGENTSCOPE',
      runtimePlacement: data.runtimePlacement || 'CENTRAL',
      runtimeConfig: data.runtimeConfig || {},
      defaultResourceConfig: data.defaultResourceConfig || {},
      graphSpec: data.graphSpec || null,
      maxSteps: data.maxSteps || 5,
      enabled: data.enabled ?? true,
      type: data.type || 'single',
      pipelineAgentIds: data.pipelineAgentIds || [],
      knowledgeBaseGroupId: data.knowledgeBaseGroupId || '',
      promptTemplateId: data.promptTemplateId || '',
      outputSchemaType: data.outputSchemaType || '',
      triggerMode: data.triggerMode || 'all',
      useMultiAgentModel: data.useMultiAgentModel ?? false,
      extra: data.extra || {},
      canvasJson: data.canvasJson || '',
      allowIrreversible: data.allowIrreversible ?? false,
    })
    const snap = definitionToCanvas(data)
    nodes.value = snap.nodes
    edges.value = snap.edges
    decorateEdges()
    await loadVersions()
  } catch {
    ElMessage.error('加载 Agent 失败')
  }
}

async function loadGraphNodeTypes() {
  try {
    const { data } = await getAgentGraphNodeTypes()
    graphNodeTypeCapabilities.value = Array.isArray(data) ? data : []
    graphNodeTypeCapabilitiesLoaded.value = true
  } catch {
    graphNodeTypeCapabilities.value = []
    graphNodeTypeCapabilitiesLoaded.value = false
  }
}

async function loadVersions() {
  if (isNew) {
    versions.value = []
    return
  }
  try {
    const { data } = await listAgentVersions(agentId)
    versions.value = Array.isArray(data) ? data : []
  } catch {
    versions.value = []
  }
}

async function loadToolOptions() {
  try {
    const { data } = await getTools({
      current: 1,
      size: 2000,
      ...(form.projectId != null ? { projectId: form.projectId } : {}),
    })
    toolOptions.value = data?.records && Array.isArray(data.records) ? data.records : []
  } catch {
    toolOptions.value = []
  }
}

async function loadCapabilityOptions() {
  try {
    const { data } = await listCapabilities({
      current: 1,
      size: 2000,
      ...(form.projectId != null ? { projectId: form.projectId } : {}),
    })
    capabilityOptions.value = data?.records && Array.isArray(data.records) ? data.records : []
  } catch {
    capabilityOptions.value = []
  }
}

async function loadModelOptions() {
  try {
    const { data } = await getModelInstances({ modelType: 'LLM' })
    const list = normalizeModelInstanceList(data)
    modelOptions.value = list.filter((item) => isActiveModelInstance(item))
  } catch {
    modelOptions.value = []
  }
}

function normalizeModelInstanceList(payload: unknown): ModelInstance[] {
  if (Array.isArray(payload)) {
    return payload as ModelInstance[]
  }
  if (payload !== null && typeof payload === 'object' && 'data' in payload) {
    const wrapped = (payload as { data?: unknown }).data
    return Array.isArray(wrapped) ? (wrapped as ModelInstance[]) : []
  }
  return []
}

function isActiveModelInstance(item: ModelInstance) {
  return String(item.status ?? '').toUpperCase() === 'ACTIVE'
}

async function loadKnowledgeOptions() {
  try {
    const { data } = await getKnowledgeList()
    knowledgeOptions.value = Array.isArray(data?.data) ? data.data : []
  } catch {
    knowledgeOptions.value = []
  }
}

async function loadCredentialOptions() {
  try {
    const { data } = await listWorkflowCredentials({
      projectId: form.projectId,
      projectCode: form.projectCode,
    })
    credentialOptions.value = Array.isArray(data) ? data : []
  } catch {
    credentialOptions.value = []
  }
}

function handleCredentialCreated(credential: WorkflowCredential) {
  const existing = credentialOptions.value.filter((item) => item.credentialRef !== credential.credentialRef)
  credentialOptions.value = [credential, ...existing]
}

async function handleSave() {
  if (studioReadOnly.value) {
    ElMessage.info('SDK 代码托管 Agent 当前为只读草稿，请修改 EafGraph 后重启业务系统同步。')
    return false
  }
  if (!form.name) {
    ElMessage.warning('请先填写 Agent 名称')
    return false
  }
  saving.value = true
  try {
    const payload = canvasToDefinition(form, { version: 2, nodes: nodes.value, edges: edges.value })
    if (isNew) {
      ElMessage.warning('请先在表单视图创建 Agent，再进入 Studio 编辑')
      return false
    }
    await updateAgent(agentId, payload)
    ElMessage.success('已保存草稿')
    return true
  } catch {
    ElMessage.error('保存失败')
    return false
  } finally {
    saving.value = false
  }
}

async function handlePublish() {
  if (!publishForm.version) {
    ElMessage.warning('请先填写版本号')
    return
  }
  publishing.value = true
  releaseErrors.value = []
  releaseWarnings.value = []
  try {
    const saved = studioReadOnly.value ? true : await handleSave()
    if (!saved) {
      return
    }
    const validation = await validateAgentRelease(agentId, {
      version: publishForm.version,
      rolloutPercent: publishForm.rolloutPercent,
      operator: publishForm.publishedBy,
    })
    releaseErrors.value = validation.data.errors || []
    releaseWarnings.value = validation.data.warnings || []
    if (!validation.data.valid) {
      ElMessage.error('发布门禁未通过，请先修复阻断项')
      return
    }
    const warnings = [
      ...publishWarnings.value,
      ...releaseWarnings.value.map(formatReleaseValidationItem),
    ]
    if (warnings.length) {
      try {
        await ElMessageBox.confirm(
          warnings.join('\n'),
          '确认继续发布？',
          { type: 'warning', confirmButtonText: '继续发布', cancelButtonText: '返回检查' },
        )
      } catch {
        return
      }
    }
    await publishAgentVersion(agentId, {
      version: publishForm.version,
      rolloutPercent: publishForm.rolloutPercent,
      note: publishForm.note,
      publishedBy: publishForm.publishedBy,
    })
    ElMessage.success(`已发布 ${publishForm.version}（灰度 ${publishForm.rolloutPercent}%）`)
    await loadVersions()
    publishDialogOpen.value = false
  } catch (err) {
    ElMessage.error('发布失败：' + (err as Error).message)
  } finally {
    publishing.value = false
  }
}

function textValue(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  return String(value)
}

function dateMs(value?: string | null) {
  if (!value) return 0
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function spanToNodeTraceState(span: RunSpan): NodeTraceState | null {
  const nodeId = (span.nodeId || '').trim()
  if (!nodeId) return null
  const metadata = span.metadata || {}
  return {
    nodeId,
    status: spanStatus(span.status),
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

function spanStatus(status?: string): NodeTraceState['status'] {
  const normalized = (status || '').trim().toUpperCase()
  if (normalized === 'WAITING') return 'waiting'
  if (normalized === 'ERROR' || normalized === 'FAILED' || normalized === 'FAILURE') return 'error'
  return 'success'
}

function debugStepStatus(status?: string): NodeTraceState['status'] {
  const normalized = (status || '').trim().toUpperCase()
  if (normalized === 'WAITING') return 'waiting'
  if (normalized === 'ERROR' || normalized === 'FAILED' || normalized === 'FAILURE') return 'error'
  return 'success'
}

function debugStepStatusClass(status?: string) {
  return `is-${debugStepStatus(status)}`
}

function debugRunStatusText(status?: string) {
  const normalized = debugStepStatus(status)
  if (normalized === 'waiting') return '等待中'
  return normalized === 'error' ? '失败' : '成功'
}

function workflowItemStatus(item: WorkflowPathItem): NodeTraceState['status'] {
  const status = (item.status || item.workflowStatus || '').trim().toUpperCase()
  if (status === 'WAITING') return 'waiting'
  if (status === 'ERROR' || status === 'FAILED' || status === 'FAILURE') return 'error'
  return 'success'
}

function preferNodeTraceState(previous: NodeTraceState | undefined, next: NodeTraceState) {
  if (!previous) return next
  if (previous.status === 'error' && next.status !== 'error') return previous
  if (next.status === 'waiting') return { ...previous, ...next }
  if (previous.status === 'waiting' && next.status === 'success') return previous
  return next.createdAt && previous.createdAt && dateMs(previous.createdAt) > dateMs(next.createdAt) ? previous : { ...previous, ...next }
}

function stringMeta(metadata: Record<string, unknown>, key: string) {
  const value = metadata[key]
  return value === null || value === undefined ? '' : String(value)
}

function sdkSourceHashFromVersion(version: AgentVersion) {
  const snapshot = parseVersionSnapshot(version)
  const value = snapshot?.extra?.sdkGraph
  if (!value || typeof value !== 'object') return ''
  return String((value as Record<string, unknown>).sourceHash || '')
}

function parseVersionSnapshot(version: AgentVersion): AgentDefinition | null {
  if (!version.snapshotJson) return null
  try {
    return JSON.parse(version.snapshotJson) as AgentDefinition
  } catch {
    return null
  }
}

function formatReleaseValidationItem(item: AgentReleaseValidationItem) {
  return item.nodeId ? '[' + item.code + '] ' + item.nodeId + ': ' + item.message : '[' + item.code + '] ' + item.message
}

function parseNodeDebugState() {
  const text = nodeDebugStateText.value.trim()
  if (!text) return {}
  const parsed = JSON.parse(text)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('状态必须是 JSON 对象')
  }
  return parsed as Record<string, unknown>
}

async function handleRunNodeDebug() {
  if (!selectedNode.value || selectedNode.value.data.kind === 'start' || selectedNode.value.data.kind === 'end') {
    ElMessage.warning('请选择可执行节点')
    return
  }
  nodeDebugLoading.value = true
  try {
    const payload = canvasToDefinition(form, { version: 2, nodes: nodes.value, edges: edges.value })
    const state = parseNodeDebugState()
    const { data } = await debugAgentNode({
      agentDefinition: payload,
      nodeId: selectedNode.value.id,
      message: nodeDebugMessage.value,
      state,
    })
    nodeDebugResult.value = data
    refreshEdgeRuntimeClasses()
    if (data.success) {
      ElMessage.success('节点测试通过')
    } else {
      ElMessage.error('节点测试失败：' + (data.errorMessage || data.errorCode || 'unknown error'))
    }
  } catch (err) {
    ElMessage.error('节点测试失败：' + (err as Error).message)
  } finally {
    nodeDebugLoading.value = false
  }
}

async function loadTraceArtifacts(traceId: string) {
  const [trace, runOps] = await Promise.allSettled([
    getTraceDetail(traceId),
    getRunOpsDetail(traceId),
  ])
  if (trace.status === 'fulfilled') {
    traceNodes.value = trace.value.data?.nodes ?? []
  } else {
    traceNodes.value = []
  }
  if (runOps.status === 'fulfilled') {
    runOpsDetail.value = runOps.value.data ?? null
  } else {
    runOpsDetail.value = null
  }
  refreshEdgeRuntimeClasses()
}

async function loadRecentStudioRuns() {
  recentRunsLoading.value = true
  try {
    const { data } = await getRecentRunOps({ days: 7, limit: 100 })
    recentRuns.value = data ?? []
  } catch {
    ElMessage.error('加载最近运行失败')
  } finally {
    recentRunsLoading.value = false
  }
}

async function handleLoadTraceReplay(traceId = replayTraceInput.value) {
  const value = String(traceId || '').trim()
  if (!value) {
    ElMessage.warning('请输入 traceId')
    return
  }
  traceReplayLoading.value = true
  currentTraceId.value = value
  replayTraceInput.value = value
  selectedRecentTraceId.value = value
  debugResult.value = null
  debugRunResult.value = null
  selectedDebugStepIndex.value = null
  currentDebugNodeId.value = ''
  debugPlaybackToken.value += 1
  try {
    await loadTraceArtifacts(value)
    if (!runOpsDetail.value && !traceNodes.value.length) {
      ElMessage.warning('未读取到这次运行的链路数据')
    } else {
      ElMessage.success('已回放到画布')
    }
  } catch (err) {
    ElMessage.error('回放失败：' + (err as Error).message)
  } finally {
    traceReplayLoading.value = false
  }
}

function handleRecentTraceChange(value: string | number | boolean | undefined) {
  const traceId = String(value || '').trim()
  if (traceId) {
    handleLoadTraceReplay(traceId)
  }
}

function clearTraceReplay() {
  currentTraceId.value = ''
  replayTraceInput.value = ''
  selectedRecentTraceId.value = ''
  traceNodes.value = []
  runOpsDetail.value = null
  debugResult.value = null
  debugRunResult.value = null
  selectedDebugStepIndex.value = null
  currentDebugNodeId.value = ''
  debugPlaybackToken.value += 1
  refreshEdgeRuntimeClasses()
}

function recentRunLabel(run: RunSummary) {
  const status = run.status || '-'
  const name = run.agentName || run.agentId || 'Agent'
  const time = run.startedAt ? run.startedAt.replace('T', ' ').slice(0, 19) : '-'
  return `${status} · ${name} · ${time} · ${run.traceId}`
}

function buildDebugInputParams() {
  const params: Record<string, unknown> = {}
  for (const field of debugInputFields.value) {
    const raw = debugInputParams[field.name]
    if (field.type === 'number' || field.type === 'integer') {
      params[field.name] = raw === '' || raw === undefined || raw === null ? undefined : Number(raw)
    } else if (field.type === 'boolean') {
      params[field.name] = Boolean(raw)
    } else if (field.type === 'object' || field.type === 'array') {
      params[field.name] = parseDebugJsonLike(raw)
    } else {
      params[field.name] = raw === undefined || raw === null ? '' : String(raw)
    }
  }
  if (!debugInputFields.value.length) {
    params.input = debugMessage.value
    params.question = debugMessage.value
  }
  return params
}

function parseDebugJsonLike(value: unknown) {
  if (typeof value !== 'string') return value
  const text = value.trim()
  if (!text) return ''
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

function debugMessageFromParams(params: Record<string, unknown>) {
  const preferred = params.question ?? params.input ?? params.message
  if (preferred !== undefined && preferred !== null && String(preferred).trim()) {
    return String(preferred)
  }
  const firstValue = Object.values(params).find((value) => value !== undefined && value !== null && String(value).trim())
  return firstValue === undefined ? debugMessage.value : String(firstValue)
}

function debugFieldLabel(field: StudioFieldSchema) {
  return `${field.name}${field.required ? ' *' : ''}`
}

function selectDebugStep(index: number) {
  selectedDebugStepIndex.value = index
  const step = debugRunResult.value?.steps?.[index]
  if (step?.nodeId) {
    selectedNodeId.value = step.nodeId
    selectedEdgeId.value = null
    currentDebugNodeId.value = step.nodeId
    focusDebugNode(step.nodeId)
  }
}

function focusDebugNode(nodeId: string, duration = 320) {
  const node = nodes.value.find((item) => item.id === nodeId)
  if (!node) return
  const viewport = getViewport()
  const zoom = viewport.zoom || 1
  const drawerOffset = debugOpen.value && typeof window !== 'undefined'
    ? (window.innerWidth * DEBUG_DRAWER_WIDTH_RATIO / 2) / zoom
    : 0
  const nodeCenterX = node.position.x + 125
  const nodeCenterY = node.position.y + 70
  setCenter(nodeCenterX + drawerOffset, nodeCenterY, {
    zoom: Math.max(zoom, 0.85),
    duration,
  })
}

function sleep(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

async function replayDebugSteps(steps: AgentWorkflowDebugStepResult[] = []) {
  const token = debugPlaybackToken.value + 1
  debugPlaybackToken.value = token
  await nextTick()
  for (let index = 0; index < steps.length; index += 1) {
    if (debugPlaybackToken.value !== token) return
    const step = steps[index]
    selectedDebugStepIndex.value = index
    currentDebugNodeId.value = step.nodeId
    selectedNodeId.value = step.nodeId
    selectedEdgeId.value = null
    focusDebugNode(step.nodeId, 360)
    await sleep(420)
  }
  if (debugPlaybackToken.value === token) {
    currentDebugNodeId.value = ''
  }
}

function stringifyDebugPayload(value: unknown) {
  if (value === undefined || value === null || value === '') return '{}'
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
    } catch {
      return value
    }
  }
  return JSON.stringify(value, null, 2)
}

function buildCurrentAgentDefinitionForDebug(): AgentDefinition {
  return {
    ...canvasToDefinition(form, { version: 2, nodes: nodes.value, edges: edges.value }),
    id: isNew ? form.keySlug || 'studio-draft' : agentId,
  } as AgentDefinition
}

function currentCanvasSnapshot() {
  return { version: 2, nodes: nodes.value, edges: edges.value }
}

function formatEvalRate(value?: number) {
  return `${Math.round((value || 0) * 1000) / 10}%`
}

async function openEvalDrawer() {
  evalOpen.value = true
  await loadEvalDatasets()
}

async function loadEvalDatasets() {
  evalDatasetLoading.value = true
  try {
    const { data } = await listEvalDatasets(isNew ? undefined : { agentId })
    evalDatasets.value = data || []
    if (!evalSelectedDatasetId.value && evalDatasets.value.length) {
      evalSelectedDatasetId.value = evalDatasets.value[0].id
    }
  } catch (err) {
    ElMessage.error('加载评测数据集失败：' + (err as Error).message)
  } finally {
    evalDatasetLoading.value = false
  }
}

function readJsonObjectCell(raw: unknown, label: string, rowNo: number, errors: string[]) {
  if (raw === undefined || raw === null || String(raw).trim() === '') return {}
  if (typeof raw === 'object') return raw as Record<string, unknown>
  try {
    const parsed = JSON.parse(String(raw))
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>
    }
    errors.push(`第 ${rowNo} 行 ${label} 必须是 JSON 对象`)
  } catch {
    errors.push(`第 ${rowNo} 行 ${label} 不是合法 JSON`)
  }
  return {}
}

function downloadEvalTemplate() {
  const headers = ['caseNo', 'message', 'inputParams', 'expected', 'judgeConfig', 'tags']
  const rows = [
    {
      caseNo: 'case-001',
      message: '查询订单1001是否可以退款',
      inputParams: JSON.stringify({ question: '查询订单1001是否可以退款' }),
      expected: JSON.stringify({
        contains: ['订单1001'],
        jsonPath: {
          'finalState.orderId': '1001',
        },
      }),
      judgeConfig: JSON.stringify({ semanticEnabled: false }),
      tags: 'smoke,refund',
    },
  ]
  const worksheet = XLSX.utils.json_to_sheet(rows, { header: headers })
  worksheet['!cols'] = [
    { wch: 16 },
    { wch: 32 },
    { wch: 42 },
    { wch: 58 },
    { wch: 30 },
    { wch: 18 },
  ]
  const workbook = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(workbook, worksheet, 'eval-cases')
  XLSX.writeFile(workbook, 'AgentStudio评测数据集模板.xlsx')
}

async function parseEvalWorkbook(file: UploadRawFile) {
  const buffer = await file.arrayBuffer()
  const workbook = XLSX.read(buffer, { type: 'array' })
  const sheet = workbook.Sheets[workbook.SheetNames[0]]
  const rows = XLSX.utils.sheet_to_json<Record<string, unknown>>(sheet, { defval: '' })
  const errors: string[] = []
  const cases: AgentEvalCaseImportRow[] = rows.map((row, index) => {
    const rowNo = index + 2
    const message = String(row.message || '').trim()
    if (!message) {
      errors.push(`第 ${rowNo} 行 message 不能为空`)
    }
    return {
      caseNo: String(row.caseNo || '').trim() || `case-${index + 1}`,
      message,
      inputParams: readJsonObjectCell(row.inputParams, 'inputParams', rowNo, errors),
      expected: readJsonObjectCell(row.expected, 'expected', rowNo, errors),
      judgeConfig: readJsonObjectCell(row.judgeConfig, 'judgeConfig', rowNo, errors),
      tags: String(row.tags || '').trim(),
    }
  })
  return { cases, errors }
}

function handleEvalUpload(file: UploadRawFile) {
  evalUploading.value = true
  evalImportErrors.value = []
  parseEvalWorkbook(file)
    .then(async ({ cases, errors }) => {
      if (!cases.length) {
        errors.push('文件中没有可导入的用例')
      }
      if (errors.length) {
        evalImportErrors.value = errors
        ElMessage.error('评测数据存在错误，请修正后重新导入')
        return
      }
      const { data } = await createEvalDataset({
        agentId: isNew ? undefined : agentId,
        agentName: form.name,
        name: file.name.replace(/\.(xlsx|xls|csv)$/i, ''),
        description: 'Agent Studio 导入评测集',
        cases,
      })
      ElMessage.success(`已导入 ${data.caseCount} 条评测用例`)
      await loadEvalDatasets()
      evalSelectedDatasetId.value = data.id
    })
    .catch((err) => {
      ElMessage.error('导入评测数据失败：' + (err as Error).message)
    })
    .finally(() => {
      evalUploading.value = false
    })
  return false
}

async function handleStartEval() {
  if (!evalSelectedDatasetId.value) {
    ElMessage.warning('请先选择评测数据集')
    return
  }
  evalRunning.value = true
  try {
    const { data } = await startEvalRun({
      datasetId: evalSelectedDatasetId.value,
      agentId: isNew ? undefined : agentId,
      agentName: form.name,
      runName: `${form.name || '未命名智能体'} 发布前评测`,
      repeatCount: evalRepeatCount.value,
      agentDefinition: buildCurrentAgentDefinitionForDebug(),
      canvasSnapshot: currentCanvasSnapshot(),
    })
    evalRunView.value = data
    ElMessage[data.summary.biasCount ? 'warning' : 'success'](
      data.summary.biasCount ? '评测完成，存在结果偏差' : '评测完成，全部通过',
    )
  } catch (err) {
    ElMessage.error('评测运行失败：' + (err as Error).message)
  } finally {
    evalRunning.value = false
  }
}

async function handleRunDraftDebug() {
  const inputParams = buildDebugInputParams()
  const message = debugMessageFromParams(inputParams)
  if (!message.trim()) {
    ElMessage.warning('请输入测试消息或用户输入字段')
    return
  }
  debugLoading.value = true
  currentTraceId.value = ''
  traceNodes.value = []
  runOpsDetail.value = null
  debugResult.value = null
  debugRunResult.value = null
  selectedDebugStepIndex.value = null
  currentDebugNodeId.value = ''
  debugPlaybackToken.value += 1
  try {
    const payload = buildCurrentAgentDefinitionForDebug()
    const { data } = await debugAgentWorkflowRun({
      agentDefinition: payload,
      message,
      inputParams,
      debugOptions: {},
    })
    debugRunResult.value = data
    currentTraceId.value = data.traceId || ''
    replayTraceInput.value = data.traceId || ''
    selectedRecentTraceId.value = data.traceId || ''
    refreshEdgeRuntimeClasses()
    await replayDebugSteps(data.steps || [])
    selectedDebugStepIndex.value = data.steps?.length ? data.steps.length - 1 : null
    ElMessage[data.success ? 'success' : 'error'](data.success ? '当前草稿调试完成' : '当前草稿调试失败')
  } catch (err) {
    ElMessage.error('草稿调试失败：' + (err as Error).message)
  } finally {
    debugLoading.value = false
  }
}

async function handleRunPublishedDebug() {
  const key = form.keySlug || agentId
  if (!debugMessage.value) {
    ElMessage.warning('请输入测试消息')
    return
  }
  debugLoading.value = true
  currentTraceId.value = ''
  traceNodes.value = []
  runOpsDetail.value = null
  debugRunResult.value = null
  selectedDebugStepIndex.value = null
  currentDebugNodeId.value = ''
  debugPlaybackToken.value += 1
  try {
    const { data } = await gatewayChat(key, {
      message: debugMessage.value,
      userId: 'studio-debug',
    })
    debugResult.value = data
    const traceId = (data.metadata?.traceId as string) || ''
    if (traceId) {
      currentTraceId.value = traceId
      replayTraceInput.value = traceId
      selectedRecentTraceId.value = traceId
      try {
        await loadTraceArtifacts(traceId)
      } catch {
        // 如果 trace 还没写入，给用户一个 retry 提示即可。
      }
    }
  } catch (err) {
    ElMessage.error('调试失败：' + (err as Error).message)
  } finally {
    debugLoading.value = false
  }
}

function handleDebug() {
  debugOpen.value = true
  for (const field of debugInputFields.value) {
    if (!(field.name in debugInputParams)) {
      debugInputParams[field.name] = field.defaultValue ?? (field.name === 'question' ? debugMessage.value : '')
    }
  }
  if (!recentRuns.value.length) {
    loadRecentStudioRuns()
  }
}

function openAiDraftDialog() {
  if (studioReadOnly.value) {
    ElMessage.info('SDK 托管图当前只读，请从代码侧更新后同步')
    return
  }
  aiDraftModelInstanceId.value = aiDraftModelInstanceId.value || form.modelInstanceId || aiDraftModelOptions.value[0]?.id || ''
  aiDraftRequirement.value = aiDraftRequirement.value || form.description || form.systemPrompt || ''
  aiDraftPreview.value = null
  aiDraftDialogOpen.value = true
}

async function handleGenerateWorkflowEdit() {
  const instruction = aiEditInstruction.value.trim()
  if (!instruction) {
    ElMessage.warning('请先输入要修改的流程指令')
    return
  }
  const modelInstanceId = aiDraftModelInstanceId.value || form.modelInstanceId || aiDraftModelOptions.value[0]?.id || ''
  if (!modelInstanceId) {
    ElMessage.warning('请先选择或配置可用的 LLM 模型实例')
    return
  }
  aiEditLoading.value = true
  try {
    const { data } = await editWorkflowDraft({
      agentId,
      agentName: form.name,
      instruction,
      projectCode: form.projectCode,
      modelInstanceId,
      currentCanvas: currentCanvasSnapshot(),
      selectedNodeIds: selectedNodeIdsForAi.value,
      selectedEdgeIds: selectedEdgeIdsForAi.value,
      tools: availableTools.value.map(toolToDraftResource),
      capabilities: availableCapabilities.value.map(capabilityToDraftResource),
      knowledgeBases: knowledgeOptions.value.map(knowledgeToDraftResource),
    })
    aiEditPreview.value = data
    if (data.validationErrors?.length) {
      ElMessage.warning('AI 修改预览存在校验问题，请检查后重试')
    } else {
      ElMessage.success('AI 修改预览已生成')
    }
  } catch (err) {
    ElMessage.error('生成 AI 修改失败：' + (err as Error).message)
  } finally {
    aiEditLoading.value = false
  }
}

function handleApplyWorkflowEdit() {
  const preview = aiEditPreview.value
  const snapshot = normalizeGeneratedCanvas(preview?.canvasSnapshot)
  if (!preview || !snapshot) {
    ElMessage.warning('请先生成可用的 AI 修改预览')
    return
  }
  if (preview.validationErrors?.length) {
    ElMessage.warning('当前预览仍有校验问题，不能应用到草稿')
    return
  }
  const normalized = definitionToCanvas({
    ...(form as unknown as AgentDefinition),
    graphSpec: preview.graphSpec,
    canvasJson: JSON.stringify(snapshot),
  })
  nodes.value = normalized.nodes
  edges.value = normalized.edges
  decorateEdges()
  form.graphSpec = preview.graphSpec
  form.canvasJson = JSON.stringify({ version: 2, nodes: nodes.value, edges: edges.value })
  selectedNodeId.value = null
  selectedEdgeId.value = null
  aiEditPreview.value = null
  aiEditInstruction.value = ''
  pushHistorySnapshot()
  nextTick(() => fitView({ padding: 0.2, duration: 300 }))
  ElMessage.success('已应用 AI 修改到当前前端草稿，请检查后再保存')
}

function clearWorkflowEditPreview() {
  aiEditPreview.value = null
}

async function handleGenerateWorkflowDraft() {
  const requirement = aiDraftRequirement.value.trim()
  if (!requirement) {
    ElMessage.warning('请先输入流程需求')
    return
  }
  aiDraftGenerating.value = true
  try {
    const { data } = await generateWorkflowDraft({
      agentId,
      agentName: form.name,
      requirement,
      projectCode: form.projectCode,
      modelInstanceId: aiDraftModelInstanceId.value || form.modelInstanceId,
      currentCanvas: { version: 2, nodes: nodes.value, edges: edges.value },
      tools: availableTools.value.map(toolToDraftResource),
      capabilities: availableCapabilities.value.map(capabilityToDraftResource),
      knowledgeBases: knowledgeOptions.value.map(knowledgeToDraftResource),
    })
    aiDraftPreview.value = data
    ElMessage.success('流程草稿预览已生成')
  } catch (err) {
    ElMessage.error('生成流程草稿失败：' + (err as Error).message)
  } finally {
    aiDraftGenerating.value = false
  }
}

function handleApplyWorkflowDraft() {
  const preview = aiDraftPreview.value
  const snapshot = normalizeGeneratedCanvas(preview?.canvasSnapshot)
  if (!preview || !snapshot) {
    ElMessage.warning('请先生成可用的流程草稿预览')
    return
  }
  if (preview.validationErrors?.length) {
    ElMessage.warning('当前流程草稿仍有校验问题，不能替换画布')
    return
  }
  const normalized = definitionToCanvas({
    ...(form as unknown as AgentDefinition),
    graphSpec: preview.graphSpec,
    canvasJson: JSON.stringify(snapshot),
  })
  nodes.value = normalized.nodes
  edges.value = normalized.edges
  decorateEdges()
  form.graphSpec = preview.graphSpec
  form.canvasJson = JSON.stringify({ version: 2, nodes: nodes.value, edges: edges.value })
  selectedNodeId.value = null
  selectedEdgeId.value = null
  aiDraftDialogOpen.value = false
  pushHistorySnapshot()
  nextTick(() => fitView({ padding: 0.2, duration: 300 }))
  ElMessage.success('已替换为 AI 生成的前端草稿，请检查后再保存')
}

function normalizeGeneratedCanvas(raw: unknown) {
  if (!raw || typeof raw !== 'object') return null
  const snapshot = raw as { nodes?: unknown; edges?: unknown }
  if (!Array.isArray(snapshot.nodes) || !Array.isArray(snapshot.edges)) return null
  return {
    version: 2 as const,
    nodes: snapshot.nodes as CanvasNode[],
    edges: snapshot.edges as CanvasEdge[],
  }
}

function workflowEditOperationLabel(type: WorkflowDraftEditOperationType) {
  const labels: Record<WorkflowDraftEditOperationType, string> = {
    ADD_NODE: '新增节点',
    UPDATE_NODE: '修改节点',
    DELETE_NODE: '删除节点',
    ADD_EDGE: '新增连线',
    UPDATE_EDGE: '修改连线',
    DELETE_EDGE: '删除连线',
  }
  return labels[type] || type
}

function operationKey(item: WorkflowDraftEditOperation) {
  return `${item.type}:${item.nodeId || item.edgeId || operationTarget(item)}:${item.reason || ''}`
}

function operationTarget(item: WorkflowDraftEditOperation) {
  const node = item.node as { id?: string; data?: { label?: string } } | undefined
  const edge = item.edge as { id?: string; source?: string; target?: string } | undefined
  if (item.nodeId) return item.nodeId
  if (item.edgeId) return item.edgeId
  if (node?.data?.label) return node.data.label
  if (node?.id) return node.id
  if (edge?.source || edge?.target) return `${edge?.source || '?'} -> ${edge?.target || '?'}`
  return workflowEditOperationLabel(item.type)
}

function modelOptionLabel(item: ModelInstance) {
  return `${item.name || item.id} / ${item.provider || '-'} / ${item.modelName || '-'}`
}

function previewNodeLabel(nodeId: string) {
  return aiDraftPreviewNodeLabels.value.get(nodeId) || nodeId
}

function previewEdgeLabel(edge: CanvasEdge) {
  const condition = edge.condition || edge.label || 'always'
  if (condition === 'always') return '连线'
  if (condition === 'else') return '默认分支'
  if (condition.startsWith('route:')) return `分支：${condition.slice('route:'.length) || edge.sourceHandle || '未命名'}`
  return condition
}

function toolToDraftResource(tool: ToolInfo): WorkflowDraftResource {
  return {
    kind: 'TOOL',
    name: tool.name,
    qualifiedName: tool.qualifiedName,
    projectCode: tool.projectCode,
    description: tool.aiDescription || tool.description,
  }
}

function capabilityToDraftResource(capability: CapabilityInfo): WorkflowDraftResource {
  return {
    kind: 'SKILL',
    name: capability.name,
    qualifiedName: capability.qualifiedName,
    projectCode: capability.projectCode,
    description: capability.aiDescription || capability.description,
  }
}

function knowledgeToDraftResource(knowledge: KnowledgeBase): WorkflowDraftResource {
  return {
    kind: 'KNOWLEDGE',
    name: knowledge.code,
    projectCode: knowledge.projectCode,
    description: knowledge.description || knowledge.name,
  }
}

async function handleExtractCapabilityDraft() {
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

async function handleExtractCanvasSkill() {
  const toolNames = nodes.value
    .filter((n) => n.data.kind === 'tool' && n.data.toolConfig?.ref)
    .map((n) => n.data.toolConfig?.ref as string)
  if (toolNames.length < 2) {
    ElMessage.warning('画布中至少需要 2 个工具节点才能抽取能力草稿')
    return
  }
  canvasExtracting.value = true
  try {
    const snapshot = { version: 2, nodes: nodes.value, edges: edges.value }
    const { data } = await extractDraftFromCanvas({
      agentName: form.name,
      toolNames,
      canvasJson: JSON.stringify(snapshot),
    })
    ElMessage.success('已从画布生成能力草稿：' + data.name + '（ID ' + data.id + '）')
  } catch (err) {
    ElMessage.error('画布抽取失败：' + (err as Error).message)
  } finally {
    canvasExtracting.value = false
  }
}

function handleSwitchToForm() {
  ElMessageBox.confirm('切换到表单视图会丢弃未保存的画布变更，是否继续？', '提示')
    .then(() => router.push('/agent/' + agentId + '/edit'))
    .catch(() => {})
}

function openPaletteGroup(title: string) {
  activePaletteGroup.value = title
  paletteExpanded.value = true
  nodeSearchKeyword.value = ''
}

async function handleHeaderCommand(command: string | number | object) {
  if (command === 'form') {
    handleSwitchToForm()
    return
  }
  if (command === 'extract') {
    await handleExtractCanvasSkill()
    return
  }
  if (command === 'fit') {
    await handleFitView()
    return
  }
  if (command === 'layout') {
    await handleAutoLayout()
  }
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
    handleAutoLayout()
    return
  }
  if (mod && key === '1') {
    event.preventDefault()
    handleFitView()
    return
  }
  if ((key === 'delete' || key === 'backspace') && selectedNode.value && !studioReadOnly.value) {
    event.preventDefault()
    deleteSelectedNode()
  }
}

onMounted(async () => {
  await Promise.all([loadGraphNodeTypes(), loadAgent()])
  await Promise.all([loadToolOptions(), loadCapabilityOptions(), loadModelOptions(), loadKnowledgeOptions(), loadCredentialOptions()])
  await nextTick()
  pushHistorySnapshot()
  historyReady.value = true
  window.addEventListener('keydown', handleStudioShortcut)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleStudioShortcut)
})

watch(
  debugInputFields,
  (fields) => {
    const names = new Set(fields.map((field) => field.name))
    for (const key of Object.keys(debugInputParams)) {
      if (!names.has(key)) {
        delete debugInputParams[key]
      }
    }
    for (const field of fields) {
      if (!(field.name in debugInputParams)) {
        debugInputParams[field.name] = field.defaultValue ?? (field.name === 'question' ? debugMessage.value : '')
      }
    }
  },
  { immediate: true, deep: true },
)

watch(
  () => (selectedNode.value?.data.kind || '') + ':' + (selectedNode.value?.data.toolConfig?.ref || ''),
  () => {
    refreshParamHints()
  },
)

watch(
  [selectedNodeId, selectedEdgeId],
  ([nodeId, edgeId]) => {
    if (nodeId || edgeId) {
      propertyPanelCollapsed.value = false
    }
    if (!nodeId || edgeId) {
      propertyDetailOpen.value = false
    }
  },
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

// 保留 watch 便于未来联动：若 tools 变化需要映射到画布，可在此同步。
watch(
  () => form.tools,
  () => {},
)

watch(
  () => currentSnapshotText(),
  () => {
    if (!historyReady.value || historyApplying.value) return
    pushHistorySnapshot()
  },
  { flush: 'post' },
)
</script>

<style scoped lang="scss">
.studio-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #f5f7fa;
}

.publish-warning {
  margin-bottom: 12px;

  ul {
    margin: 4px 0 0;
    padding-left: 18px;
  }
}

.param-hints {
  margin: 8px 0 16px;
  padding: 10px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background: #fafafa;
}

.param-hints-title {
  margin-bottom: 8px;
  color: #606266;
  font-size: 13px;
  font-weight: 600;
}

.param-hint-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 0;
  font-size: 12px;
  border-top: 1px solid #ebeef5;

  code {
    color: #409eff;
  }
}

.release-check-panel {
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}

.release-check-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;

  div {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  strong {
    color: var(--el-text-color-primary);
  }

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

.release-check-collapse {
  border-top: none;
  border-bottom: none;
}

.check-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 0;
  line-height: 1.5;

  .el-tag {
    flex: 0 0 auto;
  }
}

.check-node {
  flex: 0 0 auto;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  color: var(--el-text-color-secondary);
}

.condition-help {
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}

.route-quick-picks {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: -4px 0 8px 100px;
}

.studio-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  background: #fff;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);

  .header-left {
    display: flex;
    align-items: center;
    gap: 12px;

    h2 {
      margin: 0;
      font-size: 18px;
    }
  }

  .header-right {
    display: flex;
    align-items: center;
    gap: 8px;
  }
}

.save-state,
.status-autosave {
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

  i {
    width: 7px;
    height: 7px;
    border-radius: 999px;
    background: #94a3b8;
    box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.12);
  }

  &.is-pending {
    border-color: #fed7aa;
    background: #fff7ed;
    color: #c2410c;

    i {
      background: #f97316;
      box-shadow: 0 0 0 3px rgba(249, 115, 22, 0.16);
    }
  }

  &.is-saving {
    border-color: #bfdbfe;
    background: #eff6ff;
    color: #1d4ed8;

    i {
      background: #3b82f6;
      box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15);
    }
  }

  &.is-saved {
    border-color: #bbf7d0;
    background: #f0fdf4;
    color: #15803d;

    i {
      background: #22c55e;
      box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.14);
    }
  }
}

.studio-back-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 0 14px 0 10px;
  border: 1px solid #cdd9ec;
  border-radius: 10px;
  background: linear-gradient(180deg, #ffffff 0%, #f6f9ff 100%);
  color: #1e3a8a;
  cursor: pointer;
  font-size: 14px;
  font-weight: 700;
  box-shadow: 0 10px 24px rgba(30, 58, 138, 0.12);
  transition: transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s ease;

  .el-icon {
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    border-radius: 999px;
    background: #e9f0ff;
    color: #3157ff;
    font-size: 16px;
  }

  &:hover {
    transform: translateY(-1px);
    border-color: #9db5ff;
    box-shadow: 0 14px 30px rgba(49, 87, 255, 0.18);
  }

  &:active {
    transform: translateY(0);
  }
}

.sdk-source-alert {
  margin: 0 16px 12px;
}

.sdk-source-detail {
  font-size: 12px;
  line-height: 1.7;

  span {
    margin-left: 12px;
    color: #92400e;
  }
}

.sdk-publish-state {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;

  span {
    margin-left: 0;
  }
}

.studio-body {
  flex: 1;
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) 430px;
  overflow: hidden;
  transition: grid-template-columns 0.2s ease;

  &.palette-open {
    grid-template-columns: 320px minmax(0, 1fr) 430px;
  }

  &.property-collapsed {
    grid-template-columns: 72px minmax(0, 1fr) 58px;
  }

  &.palette-open.property-collapsed {
    grid-template-columns: 320px minmax(0, 1fr) 58px;
  }
}

.palette {
  display: flex;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
  border-right: 1px solid #e5edf7;
  overflow: hidden;

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

    &:disabled {
      cursor: not-allowed;
      opacity: 0.48;
      box-shadow: none;
    }
  }

  .rail-item {
    display: grid;
    place-items: center;
    width: 48px;
    min-height: 54px;
    border-radius: 8px;
    background: transparent;
    color: #64748b;

    .rail-icon {
      display: grid;
      place-items: center;
      width: 28px;
      height: 28px;
      border-radius: 8px;
      background: #eef4ff;
      color: #3157ff;
      font-size: 16px;
    }

    em {
      margin-top: 3px;
      font-size: 11px;
      font-style: normal;
      line-height: 1;
    }

    &:hover,
    &.active {
      background: #f0f5ff;
      color: #3157ff;
      box-shadow: inset 3px 0 0 #3157ff;
    }
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

  .studio-body.palette-open & .palette-content {
    display: block;
  }

  .panel-title-row {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 8px;
    margin-bottom: 14px;
  }

  .palette-title {
    font-size: 16px;
    font-weight: 700;
    margin-bottom: 4px;
  }

  .palette-subtitle {
    color: #64748b;
    font-size: 12px;
    line-height: 1.5;
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
    border-left: 3px solid #409eff;
    border-radius: 8px;
    padding: 10px 11px;
    margin-bottom: 8px;
    cursor: grab;
    background: rgba(255, 255, 255, 0.92);
    box-shadow: 0 6px 16px rgba(15, 23, 42, 0.045);
    transition: transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s ease;

    &:hover {
      transform: translateY(-1px);
      border-color: #cbd5e1;
      box-shadow: 0 14px 28px rgba(15, 23, 42, 0.08);
    }

    &:active {
      cursor: grabbing;
    }

    &.disabled {
      cursor: not-allowed;
      opacity: 0.55;
    }

    &-head {
      display: grid;
      grid-template-columns: 32px 1fr;
      gap: 9px;
      align-items: center;
      min-width: 0;
    }

    &-icon {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 32px;
      height: 32px;
      border-radius: 8px;

      .el-icon {
        font-size: 16px;
      }
    }

    &-title {
      color: #1f2937;
      font-weight: 700;
      font-size: 13px;
    }

    &-meta {
      color: #94a3b8;
      font-size: 11px;
      margin-top: 2px;
    }

    &-desc {
      display: -webkit-box;
      overflow: hidden;
      color: #64748b;
      font-size: 11px;
      line-height: 1.45;
      margin-top: 6px;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
    }
  }

  .palette-tips {
    color: #64748b;
    font-size: 12px;
    line-height: 1.8;
  }
}

.canvas-wrap {
  position: relative;
  background:
    radial-gradient(circle at 20px 20px, rgba(59, 130, 246, 0.08) 1px, transparent 0) 0 0 / 22px 22px,
    linear-gradient(180deg, #f8fbff 0%, #eef4fb 100%);
}

.studio-canvas {
  width: 100%;
  height: 100%;
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

.inspector-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;

  strong {
    color: #0f172a;
    font-size: 12px;
  }
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

  span {
    color: #64748b;
    font-size: 10px;
    font-weight: 800;
  }

  em {
    overflow: hidden;
    color: #334155;
    font-size: 12px;
    font-style: normal;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &.error {
    border-color: #fecaca;
    background: #fff5f5;

    span {
      color: #dc2626;
    }
  }

  &.warning {
    border-color: #fed7aa;
    background: #fff7ed;

    span {
      color: #ea580c;
    }
  }
}

.variable-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.variable-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 180px;
  padding: 5px 8px;
  border: 1px solid #dbeafe;
  border-radius: 999px;
  background: #eff6ff;
  cursor: pointer;

  span {
    overflow: hidden;
    color: #1d4ed8;
    font-size: 12px;
    font-weight: 700;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  em {
    overflow: hidden;
    color: #64748b;
    font-size: 11px;
    font-style: normal;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.inspector-empty {
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
}

.runtime-context {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #e2e8f0;
}

.context-row {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 8px;
  width: 100%;
  padding: 5px 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  text-align: left;

  span,
  em {
    overflow: hidden;
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: #1d4ed8;
    font-weight: 700;
  }

  em {
    color: #64748b;
    font-style: normal;
  }
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

  &:hover {
    transform: translateX(-1px);
    border-color: #bfd0ff;
    color: #3157ff;
  }
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

  strong,
  span {
    overflow: hidden;
    max-height: 220px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    color: #0f172a;
    font-size: 13px;
  }

  span {
    font-size: 12px;
  }
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

  .empty-state-icon {
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

  strong {
    color: #0f172a;
    font-size: 14px;
  }

  span {
    font-size: 12px;
    line-height: 1.55;
  }
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

  > div {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 8px;
  }

  strong,
  span {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  strong {
    color: #0f172a;
    font-size: 16px;
    font-weight: 800;
    white-space: nowrap;
  }

  span {
    margin-top: 6px;
    color: #64748b;
    font-size: 12px;
    line-height: 1.5;
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    white-space: normal;
  }
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
  transition:
    border-color 0.16s ease,
    box-shadow 0.16s ease,
    transform 0.16s ease;

  &:hover {
    transform: translateY(-1px);
    border-color: #bfd0ff;
    box-shadow: 0 14px 32px rgba(15, 23, 42, 0.08);
  }
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

  strong,
  em {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    color: #0f172a;
    font-size: 14px;
    font-weight: 800;
  }

  em {
    margin-top: 6px;
    color: #64748b;
    font-size: 12px;
    font-style: normal;
  }
}

.section-card-meta {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  min-width: 0;
  color: #64748b;
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

:global(.node-property-dialog.el-dialog .el-dialog__headerbtn) {
  top: 18px;
  right: 18px;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  transition: background 0.16s ease;

  &:hover {
    background: #eef2ff;
  }
}

:global(.node-property-dialog.el-dialog .el-dialog__body) {
  max-height: min(74vh, 760px);
  overflow: auto;
  padding: 18px 22px 22px;
}

.node-dialog-header {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  padding-right: 34px;
}

.node-dialog-icon {
  --node-accent: #4f46e5;
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--node-accent) 28%, #ffffff);
  border-radius: 12px;
  background:
    radial-gradient(circle at 75% 18%, color-mix(in srgb, var(--node-accent) 22%, transparent), transparent 46%),
    #ffffff;
  color: var(--node-accent);
  font-size: 19px;
  box-shadow: 0 10px 24px rgba(79, 70, 229, 0.12);
}

.node-dialog-title {
  min-width: 0;

  strong,
  span {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    color: #0f172a;
    font-size: 18px;
    font-weight: 850;
    letter-spacing: 0;
  }

  span {
    margin-top: 5px;
    color: #64748b;
    font-size: 12px;
  }
}

.node-dialog-tags {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.node-base-form {
  display: grid;
  gap: 14px;
}

.node-detail-card {
  padding: 16px;
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  background:
    linear-gradient(180deg, rgba(248, 250, 252, 0.82), rgba(255, 255, 255, 0.98)),
    #ffffff;
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.045);

  &.compact-card {
    padding: 14px 16px;
  }

  :deep(.el-form-item) {
    margin-bottom: 14px;

    &:last-child {
      margin-bottom: 0;
    }
  }

  :deep(.el-form-item__label) {
    color: #475569;
    font-size: 13px;
    font-weight: 800;
    line-height: 42px;
  }

  :deep(.el-input__wrapper),
  :deep(.el-select__wrapper) {
    min-height: 42px;
    border-radius: 11px;
    box-shadow: 0 0 0 1px #dbe3ef inset;
  }

  :deep(.el-input__inner),
  :deep(.el-select__placeholder),
  :deep(.el-select__selected-item) {
    color: #334155;
    font-size: 14px;
    font-weight: 600;
  }

  :deep(.el-textarea__inner) {
    min-height: 128px;
    padding: 13px 15px;
    border-radius: 13px;
    color: #334155;
    font-size: 14px;
    line-height: 1.7;
    box-shadow: 0 0 0 1px #dbe3ef inset;
  }

  :deep(.el-input.is-disabled .el-input__wrapper) {
    background: #f8fafc;
    box-shadow: 0 0 0 1px #e5eaf2 inset;
  }
}

.node-detail-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;

  strong,
  span {
    display: block;
  }

  strong {
    color: #0f172a;
    font-size: 15px;
    font-weight: 850;
  }

  span {
    margin-top: 4px;
    color: #64748b;
    font-size: 12px;
    line-height: 1.45;
  }
}

.node-contract-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.contract-chip {
  min-width: 0;
  padding: 11px 12px;
  border: 1px solid #e8eef7;
  border-radius: 12px;
  background: #f8fbff;

  > span {
    display: block;
    margin-bottom: 7px;
    color: #64748b;
    font-size: 12px;
    font-weight: 750;
  }
}

.governance-row {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) auto auto;
  gap: 12px;
  align-items: center;

  :deep(.el-form-item) {
    margin-bottom: 0;
  }

  :deep(.el-input-number) {
    width: 148px;
  }

  :deep(.el-input-number .el-input__wrapper) {
    min-height: 42px;
  }
}

.readonly-alert {
  margin-bottom: 12px;
}

.studio-node {
  --node-color: #2563eb;
  --node-soft: rgba(37, 99, 235, 0.1);
  --node-line: rgba(37, 99, 235, 0.2);
  position: relative;
  min-width: 204px;
  max-width: 250px;
  min-height: 118px;
  padding: 20px 14px 0;
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, 0.28);
  border-radius: 12px;
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

  &::before {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    height: 4px;
    background: linear-gradient(90deg, var(--node-color), color-mix(in srgb, var(--node-color) 56%, #ffffff));
    content: '';
  }

  .node-icon {
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

    .el-icon {
      font-size: 17px;
    }
  }

  &:hover {
    transform: translateY(-1px);
    border-color: color-mix(in srgb, var(--node-color) 52%, #dbe5f0);
    box-shadow: 0 18px 42px rgba(15, 23, 42, 0.14);
  }

  .node-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 8px;
    min-height: 30px;
    margin: 0 0 8px 42px;
  }

  .node-kind {
    display: inline-flex;
    max-width: 104px;
    min-height: 22px;
    align-items: center;
    padding: 0 8px;
    border-radius: 999px;
    background: rgba(15, 23, 42, 0.045);
    color: #475569;
    font-size: 11px;
    font-weight: 800;
    line-height: 22px;
    white-space: nowrap;
  }

  .node-state,
  .node-kicker {
    display: inline-flex;
    min-height: 22px;
    align-items: center;
    padding: 0 8px;
    border-radius: 999px;
    border: 1px solid var(--node-line);
    background: var(--node-soft);
    color: var(--node-color);
    font-size: 11px;
    font-weight: 800;
    line-height: 22px;
    white-space: nowrap;
  }

  .node-kicker {
    margin-left: 42px;
    margin-bottom: 8px;
  }

  .node-label {
    color: #0f172a;
    font-size: 15px;
    font-weight: 800;
    line-height: 1.35;
    word-break: break-all;
  }

  .node-kicker + .node-label,
  .node-head + .node-label {
    margin-left: 42px;
  }

  .node-desc {
    min-height: 34px;
    margin-top: 9px;
    padding: 8px 10px;
    overflow: hidden;
    border-radius: 8px;
    background: rgba(248, 250, 252, 0.86);
    color: #475569;
    font-size: 11px;
    line-height: 1.45;
  }

  .node-alias {
    display: inline-flex;
    max-width: 100%;
    margin-top: 8px;
    padding: 3px 7px;
    border-radius: 6px;
    border: 1px solid rgba(14, 165, 233, 0.18);
    background: rgba(14, 165, 233, 0.08);
    color: #0369a1;
    font-family: 'Cascadia Code', 'Fira Code', monospace;
    font-size: 11px;
  }

  &.run-success {
    border-color: rgba(34, 197, 94, 0.58);
    box-shadow: 0 0 0 2px rgba(34, 197, 94, 0.18), 0 16px 38px rgba(15, 23, 42, 0.12);
  }

  &.run-error {
    --node-color: #ef4444;
    --node-soft: rgba(239, 68, 68, 0.08);
    --node-line: rgba(239, 68, 68, 0.24);
    border-color: rgba(239, 68, 68, 0.58);
    box-shadow: 0 0 0 2px rgba(239, 68, 68, 0.2), 0 16px 38px rgba(15, 23, 42, 0.13);
  }

  &.run-waiting {
    --node-color: #f59e0b;
    --node-soft: rgba(245, 158, 11, 0.1);
    --node-line: rgba(245, 158, 11, 0.26);
    border-color: rgba(245, 158, 11, 0.62);
    box-shadow: 0 0 0 2px rgba(245, 158, 11, 0.2), 0 16px 38px rgba(15, 23, 42, 0.13);
  }

  &.run-current {
    border-color: rgba(99, 102, 241, 0.76);
    box-shadow:
      0 0 0 3px rgba(99, 102, 241, 0.22),
      0 18px 42px rgba(15, 23, 42, 0.16);
    transform: translateY(-2px);
  }
}

.node-runtime {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 9px;
  padding: 3px 7px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.7);
  color: #334155;
  cursor: pointer;
  font-size: 11px;
  font-weight: 700;

  .runtime-dot {
    width: 7px;
    height: 7px;
    border-radius: 999px;
    background: #22c55e;
  }
}

.run-error .node-runtime .runtime-dot {
  background: #ef4444;
}

.run-waiting .node-runtime .runtime-dot {
  background: #f59e0b;
}

.condition-routes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 9px;
}

.condition-route {
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

.classifier-routes {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.classifier-route-row {
  position: relative;
  display: flex;
  min-height: 42px;
  align-items: center;
  padding: 8px 28px 8px 10px;
  border: 1px solid rgba(249, 115, 22, 0.22);
  border-radius: 10px;
  background: rgba(255, 247, 237, 0.88);
}

.classifier-route-row.is-default {
  border-style: dashed;
  background: rgba(248, 250, 252, 0.94);
}

.classifier-route-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.classifier-route-copy strong {
  overflow: hidden;
  color: #9a3412;
  font-size: 12px;
  font-weight: 850;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.classifier-route-copy span {
  overflow: hidden;
  color: #64748b;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:global(.vue-flow__handle.classifier-route-handle) {
  top: 50%;
  right: -9px;
  width: 13px;
  height: 13px;
  border: 3px solid #dbeafe;
  background: #2563eb;
  transform: translateY(-50%);
}

.start-node {
  --node-color: #22c55e;
  --node-soft: rgba(34, 197, 94, 0.1);
  --node-line: rgba(34, 197, 94, 0.24);
}

.end-node {
  --node-color: #16a34a;
  --node-soft: rgba(22, 163, 74, 0.1);
  --node-line: rgba(22, 163, 74, 0.24);
}

.llm-node {
  --node-color: #635bff;
  --node-soft: rgba(99, 91, 255, 0.1);
  --node-line: rgba(99, 91, 255, 0.24);
}

.skill-node {
  --node-color: #d97706;
  --node-soft: rgba(217, 119, 6, 0.1);
  --node-line: rgba(217, 119, 6, 0.24);
}

.tool-node {
  --node-color: #2563eb;
  --node-soft: rgba(37, 99, 235, 0.1);
  --node-line: rgba(37, 99, 235, 0.24);
}

.knowledge-node {
  --node-color: #0d9488;
  --node-soft: rgba(13, 148, 136, 0.1);
  --node-line: rgba(13, 148, 136, 0.24);
}

.user-input-node {
  --node-color: #10b981;
  --node-soft: rgba(16, 185, 129, 0.1);
  --node-line: rgba(16, 185, 129, 0.24);
}

.condition-node {
  --node-color: #f97316;
  --node-soft: rgba(249, 115, 22, 0.11);
  --node-line: rgba(249, 115, 22, 0.26);
}

.variable-node {
  --node-color: #64748b;
  --node-soft: rgba(100, 116, 139, 0.1);
  --node-line: rgba(100, 116, 139, 0.24);
}

.template-node {
  --node-color: #a21caf;
  --node-soft: rgba(162, 28, 175, 0.1);
  --node-line: rgba(162, 28, 175, 0.24);
}

.parameter-node {
  --node-color: #db2777;
  --node-soft: rgba(219, 39, 119, 0.09);
  --node-line: rgba(219, 39, 119, 0.22);
}

.http-node {
  --node-color: #4f46e5;
  --node-soft: rgba(79, 70, 229, 0.1);
  --node-line: rgba(79, 70, 229, 0.24);
}

.answer-node {
  --node-color: #16a34a;
  --node-soft: rgba(22, 163, 74, 0.1);
  --node-line: rgba(22, 163, 74, 0.24);
}

.code-node {
  --node-color: #475569;
  --node-soft: rgba(71, 85, 105, 0.1);
  --node-line: rgba(71, 85, 105, 0.24);
}

.classifier-node {
  --node-color: #ea580c;
  --node-soft: rgba(234, 88, 12, 0.1);
  --node-line: rgba(234, 88, 12, 0.24);
  min-width: 278px;
  max-width: 320px;
}

.aggregate-node {
  --node-color: #0891b2;
  --node-soft: rgba(8, 145, 178, 0.1);
  --node-line: rgba(8, 145, 178, 0.24);
}

.approval-node {
  --node-color: #ca8a04;
  --node-soft: rgba(202, 138, 4, 0.1);
  --node-line: rgba(202, 138, 4, 0.24);
}

.loop-node {
  --node-color: #0284c7;
  --node-soft: rgba(2, 132, 199, 0.1);
  --node-line: rgba(2, 132, 199, 0.24);
}

.knowledge-write-node {
  --node-color: #e11d48;
  --node-soft: rgba(225, 29, 72, 0.09);
  --node-line: rgba(225, 29, 72, 0.22);
}

.document-extract-node {
  --node-color: #64748b;
  --node-soft: rgba(100, 116, 139, 0.1);
  --node-line: rgba(100, 116, 139, 0.24);
}

.mcp-node {
  --node-color: #4f46e5;
  --node-soft: rgba(79, 70, 229, 0.1);
  --node-line: rgba(79, 70, 229, 0.24);
}

.node-config-alert {
  margin-bottom: 12px;
}

.node-debug-box {
  margin: 12px 0 14px;
  padding: 12px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
}

.node-debug-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;

  strong {
    color: #0f172a;
    font-size: 13px;
  }
}

.node-trace-panel {
  margin: 14px 0;
  padding: 12px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
}

.node-trace-head,
.node-trace-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.node-trace-head {
  margin-bottom: 8px;
}

.node-trace-meta {
  justify-content: flex-start;
  color: #64748b;
  font-size: 12px;
}

.node-trace-block {
  margin-top: 10px;

  > span {
    color: #475569;
    font-size: 12px;
    font-weight: 700;
  }

  pre {
    max-height: 180px;
    margin: 6px 0 0;
    padding: 10px;
    overflow: auto;
    border-radius: 6px;
    background: #0f172a;
    color: #e2e8f0;
    font-size: 11px;
    line-height: 1.5;
    white-space: pre-wrap;
    word-break: break-word;
  }
}

:global(.vue-flow__edge-path) {
  stroke: #64748b;
  stroke-width: 2;
}

:global(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke: #2563eb;
  stroke-width: 3;
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

:global(.vue-flow__edge-text) {
  fill: #475569;
  font-size: 11px;
  font-weight: 700;
}

:global(.vue-flow__edge-textbg) {
  fill: rgba(255, 255, 255, 0.96);
  stroke: rgba(203, 213, 225, 0.65);
  stroke-width: 1px;
}

:global(.vue-flow__node.selected .studio-node) {
  border-color: var(--node-color);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--node-color) 24%, transparent), 0 22px 52px rgba(15, 23, 42, 0.18);
}

:global(.vue-flow__node.selected .studio-node::before) {
  height: 5px;
}

:global(.vue-flow__handle) {
  width: 10px;
  height: 10px;
  border: 2px solid #ffffff;
  background: #94a3b8;
  box-shadow: 0 0 0 1px #64748b, 0 2px 6px rgba(15, 23, 42, 0.16);
}

:global(.vue-flow__node.selected .vue-flow__handle) {
  background: #ffffff;
  box-shadow: 0 0 0 2px #2563eb, 0 0 0 6px rgba(37, 99, 235, 0.12);
}

:global(.vue-flow__minimap) {
  right: 22px !important;
  bottom: 78px !important;
  width: 170px;
  height: 116px;
  border: 1px solid #dbe5f0;
  border-radius: 8px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.12);
}

.node-search {
  margin: 12px 0 6px;
}

.node-port-row {
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

.studio-node.collapsed {
  min-width: 170px;
  min-height: 64px;
  padding: 18px 14px 14px 58px;

  .node-desc,
  .node-port-row,
  .node-alias {
    display: none;
  }

  .node-head,
  .node-kicker,
  .node-label {
    margin-left: 0;
  }

  .node-head {
    min-height: 0;
    margin-bottom: 4px;
  }

  .node-label {
    font-size: 14px;
  }

  .node-icon {
    top: 18px;
    left: 14px;
  }
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

  .el-button {
    margin-left: 0;
  }
}

.ai-edit-bar {
  position: absolute;
  right: 22px;
  bottom: 18px;
  left: 22px;
  z-index: 10;
  display: grid;
  gap: 8px;
  max-width: 960px;
  margin: 0 auto;
  padding: 10px 12px;
  border: 1px solid rgba(79, 70, 229, 0.24);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.95);
  box-shadow: 0 18px 48px rgba(15, 23, 42, 0.18);
  backdrop-filter: blur(14px);
}

.ai-edit-context,
.ai-edit-input-row,
.ai-edit-preview-head,
.ai-edit-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ai-edit-context {
  justify-content: space-between;
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
}

.ai-edit-context-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.ai-edit-context-left > span:last-child,
.ai-edit-model-current {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-edit-model-trigger {
  width: 30px;
  height: 30px;
  border-color: rgba(99, 102, 241, 0.24);
  color: #4f46e5;
  background: linear-gradient(180deg, rgba(238, 242, 255, 0.98), rgba(255, 255, 255, 0.98));

  &.active {
    border-color: rgba(79, 70, 229, 0.42);
    box-shadow: 0 8px 18px rgba(79, 70, 229, 0.16);
  }
}

.ai-edit-model-current {
  max-width: 260px;
  color: #475569;
}

.ai-edit-model-panel {
  display: grid;
  gap: 10px;

  .el-select {
    width: 100%;
  }
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

.ai-edit-input-row {
  .el-input {
    flex: 1;
  }
}

.ai-edit-preview {
  display: grid;
  gap: 8px;
  max-height: 260px;
  overflow: auto;
  padding-top: 8px;
  border-top: 1px solid rgba(148, 163, 184, 0.22);
}

.ai-edit-preview-head {
  justify-content: space-between;

  strong {
    color: #0f172a;
  }

  span {
    color: #64748b;
    font-size: 12px;
    font-weight: 700;
  }
}

.ai-edit-alert {
  display: grid;
  gap: 4px;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.5;

  &.error {
    border: 1px solid rgba(239, 68, 68, 0.28);
    color: #991b1b;
    background: rgba(254, 242, 242, 0.9);
  }

  &.warning {
    border: 1px solid rgba(245, 158, 11, 0.28);
    color: #92400e;
    background: rgba(255, 251, 235, 0.9);
  }
}

.ai-edit-operation-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 8px;
}

.ai-edit-operation-group {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 8px;
  background: rgba(248, 250, 252, 0.92);

  strong {
    color: #334155;
    font-size: 12px;
  }

  span {
    overflow: hidden;
    color: #64748b;
    font-size: 12px;
    line-height: 1.45;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.ai-edit-actions {
  justify-content: flex-end;
}

.canvas-search-panel {
  position: absolute;
  top: 18px;
  left: 50%;
  z-index: 9;
  display: grid;
  grid-template-columns: minmax(260px, 420px) auto auto auto auto;
  align-items: center;
  gap: 8px;
  width: min(680px, calc(100% - 40px));
  padding: 10px 12px;
  border: 1px solid rgba(148, 163, 184, 0.35);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 18px 44px rgba(15, 23, 42, 0.16);
  transform: translateX(-50%);
  backdrop-filter: blur(12px);
}

.canvas-search-count {
  min-width: 64px;
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
  text-align: center;
  white-space: nowrap;
}

.canvas-statusbar {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 7;
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 10px;
  min-height: 56px;
  padding: 8px 18px;
  border-top: 1px solid rgba(148, 163, 184, 0.28);
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(12px);
}

.status-pill,
.status-collapse {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
  border: 1px solid #dbe5f2;
  border-radius: 8px;
  background: #fff;
  color: #334155;
  cursor: pointer;
  font-size: 12px;
}

.status-left {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.status-pill {
  padding: 0 12px;

  strong,
  em {
    font-style: normal;
    line-height: 1;
  }

  strong {
    font-weight: 700;
  }

  em {
    color: #64748b;
  }

  &.danger {
    border-color: #fecaca;
    color: #b91c1c;
  }

  &.warning {
    border-color: #fed7aa;
    color: #c2410c;
  }
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #22c55e;

  .status-pill.danger & {
    background: #ef4444;
  }

  .status-pill.warning & {
    background: #f59e0b;
  }
}

.status-autosave {
  justify-self: center;
}

.status-collapse {
  justify-self: end;
  padding: 0 12px;
}

.node-search-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 14px;
  max-height: 460px;
  overflow: auto;
}

.node-search-card {
  display: grid;
  grid-template-columns: 38px 1fr;
  grid-template-rows: auto auto;
  column-gap: 10px;
  row-gap: 2px;
  min-height: 86px;
  padding: 12px;
  border: 1px solid;
  border-radius: 8px;
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;

  &:hover {
    transform: translateY(-1px);
    box-shadow: 0 12px 30px rgba(15, 23, 42, 0.12);
  }

  span {
    grid-row: 1 / span 2;
    display: grid;
    place-items: center;
    width: 34px;
    height: 34px;
    border-radius: 8px;

    .el-icon {
      font-size: 17px;
    }
  }

  strong {
    color: #0f172a;
    font-size: 14px;
  }

  em {
    color: #64748b;
    font-size: 12px;
    font-style: normal;
    line-height: 1.45;
  }
}

.node-contract-preview {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.retry-switch {
  margin-left: 10px;
}

.retry-delay {
  margin-left: 8px;
}

.debug-body {
  padding: 0 16px;
}

:global(.studio-debug-drawer-overlay) {
  pointer-events: none;
}

:global(.studio-debug-drawer-overlay .studio-debug-drawer) {
  pointer-events: auto;
}

.debug-actions {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.debug-input-card,
.debug-answer-card {
  padding: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: #fff;
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

  span {
    margin-top: 3px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

.debug-field-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
}

.debug-console-layout {
  display: grid;
  gap: 12px;
}

.debug-result {
  min-height: 180px;
}

.debug-advanced-collapse {
  margin-top: 14px;
  border-top: 1px solid var(--el-border-color-lighter);
  border-bottom: 1px solid var(--el-border-color-lighter);
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

.debug-run-meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-start;
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.workflow-debug-steps {
  display: grid;
  gap: 8px;
}

.workflow-debug-step-card {
  overflow: hidden;
  border: 1px solid var(--el-border-color-light);
  border-left: 4px solid #22c55e;
  border-radius: 8px;
  background: #fff;

  &.selected {
    box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.16);
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
  grid-template-columns: 28px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: start;
  width: 100%;
  padding: 10px;
  border: 0;
  background: #fff;
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

.step-index {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 50%;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 700;
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
}

.step-time {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-align: right;
}

.debug-step-inline {
  padding: 0 10px 10px 38px;
  border-top: 1px solid var(--el-border-color-lighter);
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

.studio-node.needs-config {
  border-style: dashed;
  border-color: #f59e0b;
  background: #fffbeb;
}

.ai-draft-body {
  display: grid;
  gap: 12px;
}

.ai-draft-model-row {
  display: grid;
  grid-template-columns: 80px minmax(0, 1fr);
  gap: 10px;
  align-items: center;

  > span {
    color: var(--el-text-color-regular);
    font-size: 13px;
    font-weight: 600;
  }
}

.ai-draft-actions,
.ai-draft-preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.ai-draft-actions span,
.ai-draft-preview-head span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.ai-draft-preview {
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}

.ai-draft-warning span {
  display: block;
}

.ai-draft-placeholders {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ai-draft-edge-list {
  display: grid;
  gap: 6px;
}

.ai-draft-edge {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  background: #fff;

  span {
    overflow: hidden;
    color: var(--el-text-color-regular);
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span:last-child {
    text-align: right;
  }
}

.ai-draft-node-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.ai-draft-node {
  min-width: 0;
  padding: 10px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: #fff;

  strong,
  span {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    margin-top: 4px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

// 日间模式覆盖
:global([data-theme="light"]) {
  .studio-header {
    border-bottom: 1px solid #ebeef5;
  }

  .palette {
    border-right: 1px solid #ebeef5;
  }

  .property-panel {
    border-left: 1px solid #ebeef5;
  }

  .palette-item-desc,
  .palette-tips,
  .node-kind,
  .node-desc {
    color: #94a3b8;
  }
}
.eval-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.eval-panel {
  padding: 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.eval-section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;

  strong,
  span {
    display: block;
  }

  span {
    margin-top: 4px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

.eval-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.eval-dataset-select {
  flex: 1;
  min-width: 260px;
}

.eval-import-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
}

.eval-import-actions .el-button {
  width: 100%;
}

.eval-import-errors {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.7;
}

.eval-summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.eval-metric {
  padding: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);

  span,
  strong {
    display: block;
  }

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  strong {
    margin-top: 6px;
    color: var(--el-text-color-primary);
    font-size: 20px;
  }
}

.eval-suggestion-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.eval-suggestion-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px;
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);

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

</style>
