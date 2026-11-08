<template>
  <WorkbenchPage class="ai-approvals">
    <PageHeader
      variant="standard"
      presentation="plain"
      action-presentation="inline"
      domain="agent"
      title="AI 操作审核"
      description="审核 AI 申请的业务写入、配置修改和发布，核对具体内容后批准一次执行。"
    />

    <LiquidGlassTabs v-model="tab" source="wallpaper">
      <el-tab-pane v-if="canReviewEmployees" name="employees">
        <template #label>
          员工提交的<span v-if="employeePending" class="ai-approvals__count">{{ employeePending }}</span>
        </template>
        <McpWriteApprovalList v-if="tab === 'employees'" @pending-count="employeePending = $event ?? 0" />
      </el-tab-pane>

      <el-tab-pane name="mine">
        <template #label>
          我的 AI 申请<span v-if="minePending" class="ai-approvals__count">{{ minePending }}</span>
        </template>
        <LiquidGlassCard class="approvals-card" source="wallpaper">
          <div class="section-heading">
            <div>
              <h2>我的 AI 申请</h2>
              <p>你的 AI 工具申请业务写入、配置修改或发布时，请核对范围、操作和完整内容。批准后，AI 才能执行一次。</p>
            </div>
            <div class="section-actions">
              <div class="ai-approvals__filters" role="group" aria-label="按状态筛选">
                <button type="button" :class="{ active: mineFilter === 'pending' }" @click="mineFilter = 'pending'">待我审核</button>
                <button type="button" :class="{ active: mineFilter === 'all' }" @click="mineFilter = 'all'">全部</button>
              </div>
              <LiquidGlassButton :loading="approvalsLoading" @click="loadApprovals">刷新审核</LiquidGlassButton>
            </div>
          </div>
          <p v-if="approvalsError" class="error-message" role="alert">{{ approvalsError }}</p>
          <p v-if="approvalsLoading && !approvals.length" class="muted" role="status">正在加载审核…</p>
          <LiquidGlassEmptyState v-else-if="!approvalsLoading && !approvalsError && !shownApprovals.length"
            :title="mineFilter === 'pending' ? '暂无待审核的操作' : '暂无 AI 操作审核'" description="需要你审核的请求会显示在这里。" />
          <ul v-else-if="shownApprovals.length" class="connection-list">
            <li v-for="approval in shownApprovals" :key="approval.id" class="connection-row">
              <div class="connection-main">
                <div class="connection-title">
                  <strong>{{ approvalSubject(approval) }}</strong>
                  <el-tag size="small" :type="approvalStatusType(approval.status)" effect="plain">{{ approvalStatusLabel(approval.status) }}</el-tag>
                </div>
                <p>{{ connectionName(approval.connectionId) }} · 调用编号 {{ approval.invocationId }}</p>
                <small>申请于 {{ formatTime(approval.createdAt) }} · 审核有效期至 {{ formatTime(approval.expiresAt) }}</small>
              </div>
              <LiquidGlassButton v-if="approval.status === 'PENDING'" type="primary" @click="openReview(approval)">审核</LiquidGlassButton>
            </li>
          </ul>
        </LiquidGlassCard>
      </el-tab-pane>
    </LiquidGlassTabs>

    <AppDialog v-model="reviewOpen" :title="isRegistryEnrollment ? '审核首次注册授权' : isKnowledgeSettings ? '审核知识库设置' : isWorkflowPublication ? '审核 Workflow 发布' : isAgentPublication ? '审核 Agent 发布' : isPageAssistantSync ? '审核同步到页面副驾驶' : isWorkflowRollback ? '审核 Workflow 回滚' : isKnowledgeImportCommit ? '审核知识库文件入库' : isKnowledgeImportCancel ? '审核取消知识库导入' : isSkillGovernance ? `审核 Skill 版本${skillOperationLabel}` : isToolAcl ? `审核调用权限规则${aclOperationLabel}` : isBusinessSystem ? `审核业务系统${systemOperationLabel}` : isModelInstance ? `审核模型实例${modelOperationLabel}` : isProviderAccount ? `审核提供商账号${accountOperationLabel}` : isMcpPublication ? `审核 MCP 发布变更（${mcpOperationLabel}）` : isAutomation ? `审核自动化${automationOperationLabel}` : isRunReplay ? '审核运行重放' : isHttpConnection ? `审核 API 连接${httpConnectionLabel}` : isIdentityMapping ? `审核${identityMappingLabel}` : isKnowledgeDelete ? (reviewed?.arguments?.operation === 'DELETE_BASE' ? '审核删除知识库' : '审核删除知识库文件') : isCapabilityReview ? `审核能力变更${capabilityActionLabel}` : isEmbedChange ? (reviewed?.arguments?.operation === 'DELETE_PAGE' ? '审核删除嵌入页面' : '审核修改嵌入接入策略') : isApiGraphChange ? (reviewed?.arguments?.operation === 'REGENERATE' ? '审核重新生成 API 关系图' : '审核删除 API 关系') : isCredentialChange ? (reviewed?.arguments?.operation === 'DISABLE' ? '审核停用 Workflow 凭据' : '审核修改 Workflow 凭据') : isMemoryErasure ? '审核跨域记忆删除' : isAgentWorkflowPublish ? (reviewed?.arguments?.operation === 'KNOWLEDGE_QA' ? '审核给 Agent 挂知识问答并发布' : '审核 Agent 更新到最新 Workflow 版本') : isPageDelivery ? '审核页面 Workflow 交付' : isAgentLifecycle ? `审核${agentLifecycleLabel} Agent` : isBusinessIndex ? (reviewed?.arguments?.operation === 'DELETE' ? '审核删除业务索引' : '审核业务索引修改') : isAccountChange ? `审核${accountChangeLabel}` : isKnowledgeChunk ? (reviewed?.arguments?.operation === 'TOGGLE' ? '审核知识切片启停' : '审核知识切片改写') : '审核 AI 业务写入'" width="700px"
      :show-close="!deciding" :close-on-click-modal="!deciding" :close-on-press-escape="!deciding">
      <p v-if="reviewLoading" role="status">正在读取完整操作内容…</p>
      <p v-if="reviewError" class="error-message" role="alert">{{ reviewError }}</p>
      <template v-if="reviewed && reviewed.arguments">
        <p class="review-note">请核对以下内容。输入可能包含敏感信息，批准后 AI 可按此内容执行一次。
          {{ isRegistryEnrollment ? '批准后，当前 AI 连接可以签发并接收一次性 SDK 接入令牌，用于此项目的业务服务首次注册。令牌有效 72 小时，仅可成功使用一次。'
            : isKnowledgeSettings ? '保存使用你的平台权限，会影响之后的检索。当前设置或资源归属变化后须重新申请；保存不会自动重新解析文件。'
            : isWorkflowPublication ? '发布使用你的平台权限，会替换 Workflow 的线上版本。完整定义、固定后的业务契约或当前线上版本变化后，须重新申请审核。'
            : isAgentPublication ? '发布使用你的平台权限，会替换 Agent 的线上配置，并固定下方列出的 Workflow 版本；配置或版本变化后须重新申请审核。'
            : isWorkflowRollback ? '回滚使用你的平台权限，会把下方历史版本恢复为线上版本，当前线上版本被替换；工作副本保持不变。线上版本或工作副本变化后须重新申请审核。'
            : isKnowledgeImportCommit ? '入库使用你的平台权限，会把下方已解析的文件按预览切片写入知识库，之后的检索可以命中这些内容。任务或切片预览变化后须重新申请审核。'
            : isKnowledgeImportCancel ? '取消使用你的平台权限，会停止这个导入任务并丢弃已上传的原件，需要重新上传才能再次导入。任务已进入索引或已结束时不会执行。'
            : isToolAcl ? '修改使用你的平台权限，会改变哪些角色的 Agent 可以调用哪些工具。规则在审核前被他人改动时不会执行，须重新申请。'
            : isKnowledgeChunk ? '修改使用你的平台权限，之后检索到这个切片的 Agent 会看到新内容（停用后不再检索到）。切片在审核前被改动时不会执行；改写后需重新向量化才会更新语义检索。'
            : isAgentWorkflowPublish ? (reviewed.arguments.operation === 'KNOWLEDGE_QA' ? '批准后会以你的权限创建知识问答 Workflow 并发布新的 Agent 线上配置，Agent 立即用新配置回答。Agent 线上配置或 Workflow 版本在审核前变化时不会执行。' : '批准后会以你的权限发布新的 Agent 线上配置，把下列 Workflow 换成最新发布版本，Agent 立即生效。Agent 线上配置或 Workflow 版本在审核前变化时不会执行。')
            : isPageDelivery ? '批准后会以你的权限发布这个页面 Workflow，填了 Agent 时还会发布新的 Agent 线上配置。任务有了更新的工程结果时不会执行。'
            : isMemoryErasure ? '批准后会以你的合规管理权限创建删除请求，永久删除这个运行时用户在所有数据域的记忆，不可恢复。各数据域的人工证据仍需你在控制台提交。'
            : isCredentialChange ? (reviewed.arguments.operation === 'DISABLE' ? '停用使用你的平台权限，所有引用这个凭据的连接和 Workflow 调用会立即失败。凭据在审核前变化时不会执行。' : '修改使用你的平台权限，之后引用这个凭据的调用按新设置认证；审核单只保存新密钥的指纹，执行时 AI 必须提供同一密钥。凭据在审核前变化时不会执行。')
            : isApiGraphChange ? (reviewed.arguments.operation === 'REGENERATE' ? '重新生成使用你的平台权限，先清空这个项目的全部关系、节点和布局再按当前能力重新推断，下面列出的人工确认关系会丢失，无法撤销。关系图在审核前变化时不会执行。' : '删除使用你的平台权限，这条关系会被永久删除，无法撤销。关系在审核前变化时不会执行。')
            : isEmbedChange ? (reviewed.arguments.operation === 'DELETE_PAGE' ? '删除使用你的平台权限，页面连同它的页面动作、会话和对话记录一起删除，无法撤销。页面或动作在审核前变化时不会执行。' : '修改使用你的平台权限，之后只有下面列出的来源域名和 Agent 能打开嵌入式对话，请确认域名可信。策略在审核前被改动时不会执行。')
            : isCapabilityReview ? '评审使用你的平台权限。采纳或回滚会改变 Agent 和 Workflow 调用这个能力时的契约，请先看下面的引用影响面。变更状态或影响面在审核前变化时不会执行。'
            : isKnowledgeDelete ? (reviewed.arguments.operation === 'DELETE_BASE' ? '删除使用你的平台权限，知识库连同下面所有文件、切片和向量一起删除，无法撤销；用到它的 Workflow 将检索不到内容。文件清单在审核前变化时不会执行。' : '删除使用你的平台权限，文件连同它的切片和向量一起删除，无法撤销。文件在审核前变化时不会执行。')
            : isIdentityMapping ? '修改使用你的平台权限。映射决定这个人以哪个业务身份办事、在业务系统里被认作谁，请确认对象正确。执行时按审核时的状态提交，映射或身份在审核前被改动时不会执行。'
            : isHttpConnection ? '修改使用你的平台权限，之后这个 API（或使用项目默认连接的所有 API）的调用会发往下面的地址并携带所列凭据，请确认地址可信。执行时按审核时的修订提交，连接在审核前被改动时不会执行。'
            : isAgentLifecycle ? (reviewed.arguments.operation === 'DELETE' ? '删除使用你的平台权限，Agent 及其配置版本将被移除，不能恢复；嵌入它的页面和自动化将无法再调用。Agent 在审核前被改动时不会执行。'
              : reviewed.arguments.operation === 'DISABLE' ? '停用使用你的平台权限，用户将不能再使用这个 Agent，可以再次启用。Agent 在审核前被改动时不会执行。'
              : '启用使用你的平台权限，可见范围内的用户将可以使用这个 Agent 的线上配置。Agent 在审核前被改动时不会执行。')
            : isBusinessIndex ? (reviewed.arguments.operation === 'DELETE' ? '删除使用你的平台权限，会移除业务索引及业务系统推送的全部记录，不能恢复。索引定义在审核前被改动时不会执行。'
              : '修改使用你的平台权限，会改变检索这个业务索引的 Agent 看到的内容；修改切分或向量设置后需要重建索引才会生效。索引定义在审核前被改动时不会执行。')
            : isAccountChange ? '变更使用你的平台管理员权限，会改变别人能登录什么、能做什么。AI 不能借此修改你自己的账号、授权或你持有的角色；账号、授权或角色在审核前被改动时不会执行。'
            : isRunReplay ? '重放会用下面的输入再次执行这次运行的已发布 Agent 版本，并以你的身份运行；Agent 的工具可能产生真实的业务副作用。'
            : isAutomation ? (reviewed.arguments.operation === 'ARCHIVE' ? '归档使用你的平台权限，自动化将不再运行，且不能恢复。'
              : '自动化会以创建者身份无人值守地按计划运行目标。执行时按审核时的修订提交，自动化在审核前被改动时不会执行。')
            : isMcpPublication ? (String(reviewed.arguments.operation).startsWith('CLIENT_') ? '修改使用你的平台权限，会改变这个外部客户端能否调用、能调用哪些工具。客户端在审核前被改动时不会执行。'
              : reviewed.arguments.operation === 'EMPLOYEE_ACCESS' ? '修改使用你的平台权限，会改变员工能否用自己的 AI 工具接入这个发布，以及写操作是否需要审批。'
              : '变更使用你的平台权限，会立即改变外部 AI 客户端能看到和调用的工具。发布条目、修订或检查结果在审核前变化时不会执行。')
            : isProviderAccount ? (reviewed.arguments.operation === 'DELETE' ? '删除使用你的平台权限，账号的地址和密钥将被移除，不能恢复；仍有模型引用时不会执行。'
              : reviewed.arguments.operation === 'CREATE_INSTANCES' ? '接入使用你的平台权限，会在这个账号下新建平台级模型实例，已接入的模型会被跳过。账号在审核前被改动时不会执行。'
              : '提供商账号是平台级的，地址和密钥对引用它的所有项目的模型生效。审核单不包含新密钥，只显示指纹；执行时 AI 须再次提供同样的密钥。账号在审核前被改动时不会执行。')
            : isModelInstance ? (reviewed.arguments.operation === 'ARCHIVE' ? '归档使用你的平台权限，绑定这个模型的 Agent、Workflow 和知识库将不能再调用它。实例在审核前被改动时不会执行。'
              : '修改使用你的平台权限，会改变绑定此模型的 Agent 调用哪个提供商、地址和密钥。审核单不包含新密钥，只显示指纹；执行时 AI 须再次提供同样的密钥。实例在审核前被改动时不会执行。')
            : isBusinessSystem ? (reviewed.arguments.operation === 'DELETE' ? '删除使用你的平台权限，会移除业务系统及其扫描出的接口、分组和注册凭据，且不能恢复；仍被引用时不会执行。'
              : reviewed.arguments.operation === 'REGISTRY_CREDENTIAL' ? '替换使用你的平台权限，业务系统之后用新的 AppKey/AppSecret 注册。审核单不包含密钥，只显示指纹；执行时 AI 须再次提供同一个密钥。'
              : '修改使用你的平台权限，会改变 Agent 通过此业务系统可以访问的内容。业务系统在审核前被他人改动时不会执行，须重新申请。')
            : isSkillGovernance ? (reviewed.arguments.operation === 'REVOKE' ? '撤销使用你的平台权限，绑定此版本的 Agent 将不能再加载它，且不能恢复。'
              : reviewed.arguments.operation === 'REVIEW' ? '评审使用你的平台权限和你的账号名记录评审人；包内容、风险报告或版本状态变化后须重新申请审核。'
              : '操作使用你的平台权限，会改变绑定此 Skill 的 Agent 可加载的版本；包内容、版本状态或默认版本变化后须重新申请审核。')
            : isPageAssistantSync ? '同步使用你的平台权限，会为项目页面副驾驶生成新的线上配置并接入此页面助手；草稿保持不变。页面副驾驶或页面助手线上版本变化后须重新申请审核。'
            : reviewed.action === 'HTTP_API_WRITE' ? '调用使用 API 配置的连接身份，不会携带你的业务用户身份。' : '调用使用项目测试身份，不会携带你的业务用户身份。' }}</p>
        <dl class="review-facts">
          <dt>{{ isRegistryEnrollment ? '项目编码' : isKnowledgeChunk ? '知识切片' : isBusinessSystem ? '业务系统' : isModelInstance ? '模型实例' : isProviderAccount ? '提供商账号' : isMcpPublication ? 'MCP 发布' : isAutomation ? '自动化' : isRunReplay ? '运行' : isHttpConnection ? 'HTTP API' : isIdentityMapping ? (reviewed.arguments.operation === 'BUSINESS_IDENTITY_SAVE' ? '业务用户' : '平台账号') : isKnowledgeDelete ? '知识库' : isCapabilityReview ? '能力' : isEmbedChange ? (reviewed.arguments.operation === 'DELETE_PAGE' ? '嵌入页面' : '接入策略') : isApiGraphChange ? (reviewed.arguments.operation === 'REGENERATE' ? '项目' : '关系') : isCredentialChange ? '凭据' : isMemoryErasure ? '运行时用户' : isAgentWorkflowPublish ? 'Agent' : isPageDelivery ? '任务' : isAgentLifecycle ? 'Agent' : isBusinessIndex ? '业务索引' : isAccountChange ? (reviewed.arguments.operation === 'CREATE_ROLE' || reviewed.arguments.operation === 'UPDATE_ROLE' ? '角色' : '账号') : isToolAcl ? '调用权限规则' : isSkillGovernance ? 'Skill' : isKnowledgeImport ? '知识库文件' : isKnowledgeSettings ? '知识库' : isWorkflowPublication || isPageAssistantSync || isWorkflowRollback ? 'Workflow' : isAgentPublication ? 'Agent' : reviewed.action === 'HTTP_API_WRITE' ? 'HTTP API' : '业务方法' }}</dt>
          <dd>{{ isRegistryEnrollment ? reviewed.arguments.projectCode : isKnowledgeChunk ? `第 ${Number(chunkBefore?.chunkIndex ?? 0) + 1} 段（切片 #${reviewed.arguments.chunkId}，文件 ${reviewed.arguments.fileId}）` : isBusinessSystem ? (systemBefore ? `${systemBefore.name ?? ''}（${systemBefore.projectCode ?? ''}，#${reviewed.arguments.projectId}）` : '新业务系统') : isIdentityMapping ? identityTargetText : isAgentWorkflowPublish ? `${reviewed.arguments.agentName ?? reviewed.arguments.agentId}（线上配置 #${reviewed.arguments.activeConfigVersionId}${reviewed.arguments.projectCode ? `，${reviewed.arguments.projectCode}` : ''}）` : isPageDelivery ? `${reviewed.arguments.taskId}（${reviewed.arguments.projectCode}）` : isMemoryErasure ? `${reviewed.arguments.runtimeUserId}（租户 ${reviewed.arguments.tenantId}）` : isCredentialChange ? `${credentialBefore?.name ?? ''}（${credentialBefore?.credentialRef ?? ''}，#${reviewed.arguments.credentialId}）` : isApiGraphChange ? (reviewed.arguments.operation === 'REGENERATE' ? `#${reviewed.arguments.projectId}` : `#${reviewed.arguments.edgeId}（项目 #${reviewed.arguments.projectId}）`) : isEmbedChange ? `${embedBefore?.name ?? embedBefore?.appKey ?? ''}（#${reviewed.arguments.targetId}，${embedBefore?.projectCode ?? ''}）` : isCapabilityReview ? `${capabilityChange?.qualifiedName ?? ''}（变更 #${reviewed.arguments.diffItemId}，${reviewed.arguments.projectCode}）` : isKnowledgeDelete ? `${deleteBase?.name ?? ''}（${reviewed.arguments.kbCode}，${deleteBase?.projectCode ?? deleteBase?.scope ?? ''}）` : isHttpConnection ? `${httpConnectionBefore?.qualifiedName ?? ''}（#${reviewed.arguments.apiId}，${httpConnectionBefore?.projectCode ?? ''}）` : isAgentLifecycle ? `${lifecycleAgent?.name ?? ''}（${reviewed.arguments.agentId}，当前${lifecycleAgent?.enabled === false ? '已停用' : '已启用'}）` : isBusinessIndex ? `${indexBefore?.indexName ?? ''}（${reviewed.arguments.indexCode}）` : isAccountChange ? accountTargetText : isRunReplay ? `${replaySource?.agentName ?? replaySource?.agentId ?? ''}（运行 ${reviewed.arguments.traceId}，版本 ${replaySource?.agentConfigVersionId ?? ''}）` : isAutomation ? (automationBefore ? `${automationBefore.name ?? ''}（${reviewed.arguments.automationKey}，修订 ${automationBefore.revision}，当前${automationBefore.status}）` : `新自动化 ${automationAfter?.name ?? ''}`) : isMcpPublication ? `${mcpPublication?.name ?? ''}（#${reviewed.arguments.publicationId}，当前${mcpPublication?.state ?? ''}）` : isProviderAccount ? (accountBefore ? `${accountBefore.name ?? ''}（${accountBefore.provider ?? ''}，引用模型 ${accountBefore.instanceCount ?? 0} 个）` : `新账号 ${accountAfter?.name ?? ''}（${accountAfter?.provider ?? ''}）`) : isModelInstance ? (modelBefore ? `${modelBefore.name ?? ''}（${reviewed.arguments.modelId}）` : `新模型 ${modelAfter?.name ?? ''}`) : isToolAcl ? (reviewed.arguments.aclId ? `规则 #${reviewed.arguments.aclId}` : '新规则') : isSkillGovernance ? `${reviewed.arguments.displayName ?? ''}（${reviewed.arguments.publisher}/${reviewed.arguments.name}）` : isKnowledgeImport ? `${reviewed.arguments.fileName ?? ''}（知识库 ${reviewed.arguments.knowledgeBaseCode}）` : isKnowledgeSettings ? `${knowledgeReview?.name ?? ''}（${reviewed.arguments.kbCode}）` : isWorkflowPublication ? `${workflowDefinition?.name ?? ''}（${reviewed.arguments.workflowId}）` : isAgentPublication ? `${publicationIdentity?.name ?? ''}（${reviewed.arguments.agentId}）`
            : isPageAssistantSync || isWorkflowRollback ? `${reviewed.arguments.workflowName ?? ''}（${reviewed.arguments.workflowId}）`
            : reviewed.action === 'HTTP_API_WRITE' ? reviewed.arguments.qualifiedName : reviewed.subject }}</dd>
          <template v-if="isRegistryEnrollment"><dt>接收令牌的 AI 连接</dt><dd>{{ connectionName(reviewed.connectionId) }}</dd></template>
          <template v-if="isKnowledgeSettings">
            <dt>管理范围</dt><dd>{{ knowledgeReview?.resource.scope === 'PROJECT' ? '项目' : knowledgeReview?.resource.scope === 'WORKSPACE' ? '工作空间' : knowledgeReview ? '共享' : '无法读取' }}</dd>
            <dt>工作空间</dt><dd>{{ knowledgeReview?.resource.workspaceId ?? '无法读取' }}</dd>
            <dt>项目</dt><dd>{{ knowledgeReview?.resource.projectCode ?? '不限定项目' }}</dd>
          </template>
          <template v-else-if="!isRegistryEnrollment && !isKnowledgeImport && !isToolAcl && !isBusinessSystem && !isKnowledgeChunk && !isModelInstance && !isProviderAccount && !isMcpPublication && !isAutomation && !isRunReplay && !isAccountChange && !isBusinessIndex && !isAgentLifecycle && !isHttpConnection && !isIdentityMapping && !isKnowledgeDelete && !isCapabilityReview && !isEmbedChange && !isApiGraphChange && !isCredentialChange && !isMemoryErasure && !isAgentWorkflowPublish && !isPageDelivery"><dt>项目</dt><dd>{{ reviewed.arguments.projectCode ?? '平台级' }}<template v-if="reviewed.arguments.projectId">（{{ reviewed.arguments.projectId }}）</template></dd></template>
          <template v-if="isAgentPublication">
            <dt>可见范围</dt><dd>{{ publicationVisibility }}</dd>
            <dt>角色范围</dt><dd>{{ publicationRoles === null ? '无法读取' : publicationRoles.length ? publicationRoles.join('、') : '未配置' }}</dd>
            <dt>Agent 状态</dt><dd>{{ publicationIdentity?.enabled ? '已启用' : '已停用' }}</dd>
            <dt>待发布配置版本</dt><dd>{{ reviewed.arguments.configVersionId }}</dd>
            <dt>当前线上配置版本</dt><dd>{{ reviewed.arguments.activeConfigVersionId ?? '尚未发布' }}</dd>
            <dt>本次固定的 Workflow 版本</dt>
            <dd><ul v-if="publicationWorkflowPins.length" class="publication-pins">
              <li v-for="pin in publicationWorkflowPins" :key="pin.workflowId">{{ pin.name }} · 发布版本 <template v-if="pin.previousVersionId && pin.previousVersionId !== pin.versionId">#{{ pin.previousVersionId }} → </template>#{{ pin.versionId }}</li>
            </ul><template v-else>没有启用的 Workflow</template></dd>
          </template>
          <template v-if="isToolAcl">
            <dt>操作</dt><dd>{{ aclOperationLabel }}</dd>
            <template v-if="reviewed.arguments.operation === 'BATCH_GRANT' && aclAfter">
              <dt>授权</dt><dd>{{ aclAfter.roleCode }} {{ aclAfter.permission === 'DENY' ? '禁止' : '允许' }}调用以下 {{ aclBatchTargets.length }} 项（{{ aclAfter.projectCode ?? (aclAfter.projectId ? `项目 ${aclAfter.projectId}` : '平台级') }}）</dd>
              <dt>工具</dt><dd class="chunk-text">{{ aclBatchTargets.map(target => target.kind === 'ALL' ? '全部工具' : target.name).join('、') }}</dd>
              <dt>会被覆盖</dt><dd>{{ aclOverwritten.length ? aclOverwritten.map(rule => aclRuleText(rule)).join('；') : '没有已有规则' }}</dd>
            </template>
            <template v-else>
              <dt v-if="aclBefore">当前规则</dt><dd v-if="aclBefore">{{ aclRuleText(aclBefore) }}</dd>
              <dt v-if="aclAfter">修改后</dt><dd v-if="aclAfter">{{ aclAfter.roleCode ? aclRuleText(aclAfter) : (aclAfter.enabled ? '启用' : '停用') }}</dd>
            </template>
          </template>
          <template v-if="isKnowledgeChunk">
            <dt>知识库范围</dt><dd>{{ chunkResource?.projectCode ?? (chunkResource?.scope === 'WORKSPACE' ? '工作空间' : '共享') }}</dd>
            <template v-if="reviewed.arguments.operation === 'TOGGLE'"><dt>修改后</dt><dd>{{ chunkAfter?.enabled ? '启用' : '停用' }}</dd></template>
            <template v-else>
              <dt v-if="chunkAfter?.title !== chunkBefore?.title">标题</dt><dd v-if="chunkAfter?.title !== chunkBefore?.title">{{ chunkBefore?.title ?? '空' }} → {{ chunkAfter?.title ?? '空' }}</dd>
              <dt>当前内容</dt><dd class="chunk-text">{{ chunkBefore?.content }}</dd>
              <dt>改写为</dt><dd class="chunk-text">{{ chunkAfter?.content }}</dd>
            </template>
          </template>
          <template v-if="isBusinessSystem">
            <dt>操作</dt><dd>{{ systemOperationLabel }}</dd>
            <template v-if="systemAfter && reviewed.arguments.operation !== 'SCAN_SETTINGS' && reviewed.arguments.operation !== 'REGISTRY_CREDENTIAL'">
              <template v-for="field in systemChangedFields" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ field.before }} → {{ field.after }}</dd></template>
            </template>
            <template v-if="reviewed.arguments.operation === 'REGISTRY_CREDENTIAL' && systemAfter">
              <dt>AppKey</dt><dd>{{ systemAfter.appKey }}</dd>
              <dt>AppSecret</dt><dd>不在审核单中，指纹 {{ String(systemAfter.appSecretSha256).slice(0, 12) }}…</dd>
              <dt>原有凭据</dt><dd>{{ systemBefore?.registryCredentialConfigured ? '已配置，将被替换' : '未配置' }}</dd>
            </template>
          </template>
          <template v-if="isModelInstance">
            <dt>操作</dt><dd>{{ modelOperationLabel }}</dd>
            <dt>项目</dt><dd>{{ (modelBefore ?? modelAfter)?.projectCode ?? '全局' }}</dd>
            <template v-if="modelAfter">
              <template v-for="field in modelChangedFields" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ field.before }} → {{ field.after }}</dd></template>
              <template v-for="entry in modelConnectionEntries" :key="entry.key"><dt>连接 {{ entry.key }}</dt><dd>{{ entry.text }}</dd></template>
            </template>
            <template v-else><dt>归档实例</dt><dd>{{ modelBefore?.name }}（{{ modelBefore?.modelName }}），绑定它的 Agent 将无法再调用</dd></template>
          </template>
          <template v-if="isAgentWorkflowPublish">
            <template v-if="reviewed.arguments.operation === 'KNOWLEDGE_QA'">
              <dt>知识库</dt><dd>{{ reviewed.arguments.knowledgeBaseName ?? reviewed.arguments.knowledgeBaseCode }}（{{ reviewed.arguments.knowledgeBaseCode }}）</dd>
            </template>
            <template v-else>
              <dt>Workflow</dt>
              <dd><div v-for="tool in agentWorkflowStale" :key="String(tool.workflowId)">{{ tool.workflowName ?? tool.workflowId }}：{{ tool.pinnedWorkflowVersion ?? `#${tool.pinnedWorkflowVersionId}` }} → {{ tool.latestWorkflowVersion ?? `#${tool.latestWorkflowVersionId}` }}</div></dd>
            </template>
          </template>
          <template v-if="isPageDelivery">
            <dt>Workflow</dt><dd>{{ pageDeliveryPlan?.workflowId }} · {{ pageDeliveryRequest?.version }} · 页面 {{ pageDeliveryRequest?.pageKey }}</dd>
            <dt>挂到 Agent</dt><dd>{{ pageDeliveryRequest?.agentId ?? '不挂' }}{{ pageDeliveryRequest?.replaceWorkflowId ? `，替换 ${pageDeliveryRequest.replaceWorkflowId}` : '' }}</dd>
          </template>
          <template v-if="isMemoryErasure">
            <dt>原因</dt><dd>{{ reviewed.arguments.reasonCode }} · {{ reviewed.arguments.referenceId }}</dd>
            <dt>请求键</dt><dd>{{ reviewed.arguments.clientRequestId }}</dd>
          </template>
          <template v-if="isCredentialChange">
            <dt>当前</dt><dd>{{ credentialBefore?.type }} · {{ credentialBefore?.scope }}{{ credentialBefore?.projectCode ? ` · ${credentialBefore.projectCode}` : '' }} · {{ credentialBefore?.status }}</dd>
            <template v-if="reviewed.arguments.operation === 'UPDATE'">
              <dt>修改后</dt><dd>{{ credentialAfter?.name }} · {{ credentialAfter?.type }} · {{ credentialAfter?.scope }}{{ credentialAfter?.projectCode ? ` · ${credentialAfter.projectCode}` : '' }}</dd>
              <dt>密钥</dt><dd>{{ reviewed.arguments.secretChanged ? '更换为新密钥（只保存指纹）' : '不变' }}</dd>
            </template>
          </template>
          <template v-if="isApiGraphChange">
            <template v-if="reviewed.arguments.operation === 'REGENERATE'">
              <dt>当前规模</dt><dd>{{ reviewed.arguments.nodeCount }} 个节点、{{ reviewed.arguments.edgeCount }} 条关系、{{ reviewed.arguments.layoutCount }} 个布局位置</dd>
              <dt>关系状态</dt><dd>{{ graphStatusText }}</dd>
              <dt>会丢失的人工关系</dt><dd>{{ graphCurated.length ? graphCurated.map(edgeText).join('；') : '没有' }}</dd>
            </template>
            <template v-else>
              <dt>关系</dt><dd>{{ edgeText(reviewed.arguments.edge as Record<string, unknown>) }}</dd>
              <dt>说明</dt><dd>{{ (reviewed.arguments.edge as Record<string, unknown>)?.note || '无' }}</dd>
            </template>
          </template>
          <template v-if="isEmbedChange">
            <template v-if="reviewed.arguments.operation === 'DELETE_PAGE'">
              <dt>页面</dt><dd>{{ embedBefore?.pageKey }} · {{ embedBefore?.origin }}{{ embedBefore?.routePattern }}</dd>
              <dt>一并删除</dt><dd>{{ embedActions.length ? embedActions.map((action) => action.title || action.actionKey).join('、') : '没有页面动作' }}，以及这个页面的会话和对话记录</dd>
            </template>
            <template v-else>
              <dt>来源域名</dt><dd>{{ listText(embedBefore?.allowedOrigins) }} → {{ listText(embedAfter?.allowedOrigins) }}</dd>
              <dt>允许的 Agent</dt><dd>{{ listText(embedBefore?.allowedAgentIds) }} → {{ listText(embedAfter?.allowedAgentIds) }}</dd>
              <dt>令牌时长</dt><dd>{{ embedBefore?.tokenTtlSeconds }} 秒 → {{ embedAfter?.tokenTtlSeconds }} 秒</dd>
              <dt>状态</dt><dd>{{ embedBefore?.status }} → {{ embedAfter?.status }}</dd>
            </template>
          </template>
          <template v-if="isCapabilityReview">
            <dt>操作</dt><dd>{{ capabilityActionLabel }}<template v-if="reviewed.arguments.note">（{{ reviewed.arguments.note }}）</template></dd>
            <dt>变更</dt><dd>{{ capabilityChangeLabel }}，当前{{ capabilityChange?.reviewStatus }}</dd>
            <dt>字段差异</dt><dd><pre class="setup-code review-input">{{ prettyJson(capabilityChange?.fieldDiffJson) }}</pre></dd>
            <dt>引用影响面</dt><dd><pre class="setup-code review-input">{{ prettyJson(capabilityChange?.impactJson) }}</pre></dd>
          </template>
          <template v-if="isKnowledgeDelete">
            <template v-if="reviewed.arguments.operation === 'DELETE_BASE'">
              <dt>一并删除</dt><dd>{{ deleteFiles.length ? `${deleteFiles.length} 个文件：${deleteFiles.map((file) => file.fileName).join('、')}` : '没有文件' }}</dd>
            </template>
            <template v-else>
              <dt>删除文件</dt><dd>{{ deleteFile?.fileName }}（{{ reviewed.arguments.fileId }}）</dd>
            </template>
          </template>
          <template v-if="isIdentityMapping">
            <dt>操作</dt><dd>{{ identityMappingLabel }}</dd>
            <template v-if="reviewed.arguments.operation === 'MAPPING_DELETE'">
              <dt>删除映射</dt><dd>{{ mappingText(identityBefore) }}</dd>
            </template>
            <template v-else>
              <dt>当前</dt><dd>{{ identityCurrentText }}</dd>
              <dt>修改后</dt><dd>{{ reviewed.arguments.operation === 'MAPPING_CREATE' ? mappingText(identityAfter) : identityText(identityAfter) }}</dd>
            </template>
          </template>
          <template v-if="isHttpConnection">
            <dt>操作</dt><dd>{{ httpConnectionLabel }}</dd>
            <dt>当前</dt><dd>{{ connectionText(httpConnectionCurrent) }}</dd>
            <dt>{{ reviewed.arguments.operation === 'REMOVE_OVERRIDE' ? '改为项目默认' : '修改后' }}</dt>
            <dd>{{ connectionText(reviewed.arguments.operation === 'REMOVE_OVERRIDE' ? httpConnectionBefore?.projectDefault : httpConnectionAfter) }}</dd>
          </template>
          <template v-if="isAgentLifecycle">
            <dt>操作</dt><dd>{{ agentLifecycleLabel }}</dd>
            <dt>项目</dt><dd>{{ lifecycleAgent?.projectCode ?? '平台级' }}</dd>
            <dt>线上配置版本</dt><dd>{{ lifecycleAgent?.activeConfigVersionId ?? '尚未发布' }}</dd>
          </template>
          <template v-if="isBusinessIndex">
            <template v-if="indexAfter">
              <template v-for="field in indexChangedFields" :key="field.key"><dt>{{ field.label }}</dt><dd class="chunk-text">{{ field.before }} → {{ field.after }}</dd></template>
            </template>
            <template v-else><dt>删除</dt><dd>业务索引 {{ indexBefore?.indexName }} 及其全部记录</dd></template>
          </template>
          <template v-if="isAccountChange">
            <dt>操作</dt><dd>{{ accountChangeLabel }}</dd>
            <template v-for="field in platformAccountFields" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ field.before }} → {{ field.after }}</dd></template>
            <template v-if="reviewed.arguments.operation === 'REPLACE_GRANTS'">
              <dt>当前授权</dt><dd>{{ grantText(accountChangeBefore?.grants) }}</dd>
              <dt>替换为</dt><dd>{{ grantText(accountChangeAfter?.grants) }}</dd>
            </template>
            <template v-if="reviewed.arguments.operation === 'REVOKE_SESSIONS'"><dt>效果</dt><dd>该账号的登录会话和 AI 连接全部失效，需要重新登录</dd></template>
          </template>
          <template v-if="isRunReplay">
            <dt>项目</dt><dd>{{ replaySource?.projectCode ?? '平台级' }}</dd>
            <dt>重放输入</dt><dd class="chunk-text">{{ replayCommand?.messageOverride }}</dd>
          </template>
          <template v-if="isAutomation">
            <dt>操作</dt><dd>{{ automationOperationLabel }}</dd>
            <dt>项目</dt><dd>{{ (automationAfter?.projectCode ?? automationBefore?.projectCode) ?? '平台级' }}</dd>
            <template v-if="automationAfter">
              <dt>运行目标</dt><dd>{{ modelShow(automationAfter.target) }}</dd>
              <dt>计划</dt><dd>{{ modelShow(automationAfter.schedule) }}</dd>
              <dt v-if="automationAfter.activate !== undefined">保存后启用</dt><dd v-if="automationAfter.activate !== undefined">{{ automationAfter.activate ? '是' : '否' }}</dd>
            </template>
          </template>
          <template v-if="isMcpPublication">
            <dt>操作</dt><dd>{{ mcpOperationLabel }}<template v-if="reviewed.arguments.operation === 'ROLLBACK'">到修订 #{{ reviewed.arguments.targetId }}</template></dd>
            <dt>条目</dt><dd>{{ mcpItems.length }} 个（启用 {{ mcpItems.filter(item => item.enabled).length }} 个）</dd>
            <template v-if="mcpPrecheck"><dt>发布检查</dt><dd>{{ mcpPrecheck.toolCount }} 个工具，其中不可逆 {{ mcpPrecheck.irreversibleToolCount ?? 0 }} 个<template v-if="mcpAfter?.irreversibleAcknowledged">（已确认不可逆工具）</template></dd></template>
            <template v-if="mcpClient"><dt>客户端</dt><dd>{{ mcpClient.name }}（{{ mcpClient.apiKeyPrefix ?? '' }}…，{{ mcpClient.state }}）</dd></template>
            <template v-if="mcpAfter && reviewed.arguments.operation !== 'PUBLISH'">
              <template v-for="(value, key) in mcpAfter" :key="key"><dt>{{ key }}</dt><dd>{{ modelShow(mcpCurrent?.[key]) }} → {{ modelShow(value) }}</dd></template>
            </template>
          </template>
          <template v-if="isProviderAccount">
            <dt>操作</dt><dd>{{ accountOperationLabel }}</dd>
            <template v-if="reviewed.arguments.operation === 'CREATE_INSTANCES'">
              <dt>接入模型</dt><dd><ul class="publication-pins"><li v-for="item in accountModels" :key="item.modelName + item.modelType">{{ item.name || item.modelName }}（{{ item.modelType }}）</li></ul></dd>
            </template>
            <template v-else-if="accountAfter">
              <template v-for="field in accountChangedFields" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ field.before }} → {{ field.after }}</dd></template>
              <template v-for="entry in accountConnectionEntries" :key="entry.key"><dt>连接 {{ entry.key }}</dt><dd>{{ entry.text }}</dd></template>
            </template>
          </template>
          <template v-if="isSkillGovernance">
            <dt>操作</dt><dd>{{ skillOperationLabel }}<template v-if="reviewed.arguments.operation === 'REVIEW'">：{{ reviewed.arguments.decision === 'APPROVE' ? '通过' : '驳回' }}</template></dd>
            <dt>版本</dt><dd>{{ reviewed.arguments.version }}（当前状态 {{ reviewed.arguments.status }}）</dd>
            <dt>当前默认版本</dt><dd>{{ reviewed.arguments.defaultVersion ?? '无' }}<template v-if="reviewed.arguments.isDefault">（就是此版本）</template></dd>
            <dt>包含脚本</dt><dd>{{ reviewed.arguments.hasScripts ? '是' : '否' }}</dd>
            <dt v-if="reviewed.arguments.comment">评审意见</dt><dd v-if="reviewed.arguments.comment">{{ reviewed.arguments.comment }}</dd>
          </template>
          <template v-if="isKnowledgeImport">
            <dt>导入任务</dt><dd>{{ reviewed.arguments.jobId }}</dd>
            <dt v-if="reviewed.arguments.replaceFileId">替换的原文件</dt><dd v-if="reviewed.arguments.replaceFileId">{{ reviewed.arguments.replaceFileId }}</dd>
            <template v-if="isKnowledgeImportCommit">
              <dt>切片数量</dt><dd>{{ importPreview?.totalChunks }}</dd>
              <dt>切分方式</dt><dd>{{ importPreview?.chunkStrategy }}，长度 {{ importPreview?.chunkSize }}，重叠 {{ importPreview?.chunkOverlap }}</dd>
            </template>
          </template>
          <template v-if="isWorkflowRollback">
            <dt>恢复为线上的版本</dt><dd>{{ reviewed.arguments.targetVersion }}（编号 {{ reviewed.arguments.versionId }}）</dd>
            <dt>该版本发布者</dt><dd>{{ reviewed.arguments.targetPublishedBy || '未知' }}</dd>
            <dt>当前线上版本编号</dt><dd>{{ reviewed.arguments.activeVersionId ?? '无' }}</dd>
          </template>
          <template v-if="isPageAssistantSync">
            <dt>页面助手线上版本编号</dt><dd>{{ reviewed.arguments.workflowActiveVersionId }}</dd>
            <dt>页面副驾驶</dt><dd>{{ reviewed.arguments.targetExists ? `${reviewed.arguments.agentKeySlug ?? ''}（${reviewed.arguments.agentId}）` : '尚未创建，本次将新建' }}</dd>
            <dt>当前线上配置</dt><dd>{{ reviewed.arguments.activeConfigVersionNo ? `v${reviewed.arguments.activeConfigVersionNo}` : '尚未发布' }}</dd>
            <dt>是否已接入</dt><dd>{{ reviewed.arguments.attached ? `已接入（版本编号 ${reviewed.arguments.attachedWorkflowVersionId ?? '未知'}），本次更新到线上版本` : '尚未接入，本次新增' }}</dd>
            <dt v-if="reviewed.arguments.replaceWorkflowId">替换的旧页面助手</dt><dd v-if="reviewed.arguments.replaceWorkflowId">{{ reviewed.arguments.replaceWorkflowId }}</dd>
            <dt v-if="reviewed.arguments.modelInstanceId">指定模型</dt><dd v-if="reviewed.arguments.modelInstanceId">{{ reviewed.arguments.modelInstanceId }}</dd>
          </template>
          <template v-if="isWorkflowPublication">
            <dt>待发布版本</dt><dd>{{ reviewed.arguments.version }}</dd>
            <dt>当前线上版本编号</dt><dd>{{ reviewed.arguments.activeVersionId ?? '尚未发布' }}</dd>
            <dt>发布说明</dt><dd>{{ reviewed.arguments.note || '未填写' }}</dd>
          </template>
          <dt v-if="reviewed.arguments.environment">环境</dt><dd v-if="reviewed.arguments.environment">{{ reviewed.arguments.environment }}</dd>
          <template v-if="!isRegistryEnrollment && !isKnowledgeSettings && !isAgentPublication && !isWorkflowPublication && !isPageAssistantSync && !isWorkflowRollback && !isKnowledgeImport && !isSkillGovernance && !isToolAcl && !isBusinessSystem && !isKnowledgeChunk && !isModelInstance && !isProviderAccount && !isMcpPublication && !isAutomation && !isRunReplay && !isAccountChange && !isBusinessIndex && !isAgentLifecycle && !isHttpConnection && !isIdentityMapping && !isKnowledgeDelete && !isCapabilityReview && !isEmbedChange && !isApiGraphChange && !isCredentialChange && !isMemoryErasure && !isAgentWorkflowPublish && !isPageDelivery"><dt>声明的副作用</dt><dd>{{ reviewed.arguments.declaredSideEffect ?? reviewed.arguments.sideEffect }}</dd></template>
          <template v-if="reviewed.arguments.targetDescription">
            <dt>调用目标</dt><dd>{{ reviewed.arguments.targetDescription }}</dd>
          </template>
          <dt>调用编号</dt><dd>{{ reviewed.invocationId }}</dd>
          <dt>有效期至</dt><dd>{{ formatTime(reviewed.expiresAt) }}</dd>
        </dl>
        <template v-if="isKnowledgeSettings && knowledgeReview">
          <p v-if="knowledgeReview.expandsAccess" class="error-message" role="alert">检索范围将从“仅授权文件”扩大到“所有调用方”，调用使用此知识库的 Agent 的用户将无需逐文件授权。</p>
          <h3 class="review-input-title">本次修改</h3>
          <dl class="review-facts" data-testid="knowledge-settings-changes">
            <template v-for="change in knowledgeReview.changes" :key="change.field"><dt>{{ change.label }}</dt><dd>{{ change.before }} → {{ change.after }}</dd></template>
          </dl>
        </template>
        <h3 class="review-input-title">{{ isKnowledgeSettings ? '完整修改前后设置' : isWorkflowPublication ? '完整定义与本次发布的 GraphSpec' : isAgentPublication ? '配置快照与本次固定的 Workflow 版本' : isPageAssistantSync ? '冻结的同步目标' : isWorkflowRollback ? '冻结的回滚目标' : isKnowledgeImport ? '冻结的导入任务' : isSkillGovernance ? '冻结的 Skill 版本' : isToolAcl ? '冻结的调用权限规则' : isBusinessSystem ? '冻结的业务系统配置与修改' : isModelInstance ? '冻结的模型实例与修改' : isProviderAccount ? '冻结的提供商账号与修改' : isMcpPublication ? '冻结的 MCP 发布与变更' : isAutomation ? '冻结的自动化与新配置' : isRunReplay ? '冻结的原运行与重放输入' : isKnowledgeChunk ? '冻结的知识切片' : '完整输入' }}</h3>
        <pre class="setup-code review-input" data-testid="approval-input">{{ JSON.stringify(reviewInput, null, 2) }}</pre>
        <p v-if="!reviewReady" class="error-message" role="alert">审核内容不完整，请刷新后重新核对。</p>
      </template>
      <template #footer>
        <LiquidGlassButton :disabled="deciding" @click="reviewOpen = false">关闭</LiquidGlassButton>
        <template v-if="reviewed?.status === 'PENDING'">
          <LiquidGlassButton :disabled="deciding" @click="decideApproval(false)">拒绝</LiquidGlassButton>
          <LiquidGlassButton v-if="reviewReady" type="primary" :loading="deciding" @click="decideApproval(true)">批准一次执行</LiquidGlassButton>
        </template>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { knowledgeSettingsReview } from './knowledgeSettingsReview'
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listPlatformAiConnections, type PlatformAiConnection } from '@/api/platformAiConnections'
import {
  decidePlatformAiApproval, listPlatformAiApprovals, reviewPlatformAiApproval,
  type PlatformAiApproval,
} from '@/api/platformAiApprovals'
import { platformSessionUser } from '@/auth/platformSession'
import { PLATFORM_PERMISSION_MCP_WRITE_APPROVAL_MANAGE, hasPlatformPermission } from '@/auth/platformAccess'
import AppDialog from '@/components/common/AppDialog.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import {
  LiquidGlassButton, LiquidGlassCard, LiquidGlassEmptyState, LiquidGlassTabs,
} from '@/components/common/liquid-glass'
import McpWriteApprovalList from '@/views/mcp-hub/McpWriteApprovalList.vue'

// 管理人员审批员工 AI 写操作；每个人审核自己平台 AI 连接的业务写入和发布。
const canReviewEmployees = computed(() => hasPlatformPermission(platformSessionUser.value?.permissions,
  PLATFORM_PERMISSION_MCP_WRITE_APPROVAL_MANAGE))
const tab = ref<'employees' | 'mine'>(canReviewEmployees.value ? 'employees' : 'mine')
const employeePending = ref(0)
const mineFilter = ref<'pending' | 'all'>('pending')
const connections = ref<PlatformAiConnection[]>([])
const approvals = ref<PlatformAiApproval[]>([])
const approvalsLoading = ref(false)
const approvalsError = ref('')
const reviewOpen = ref(false)
const reviewLoading = ref(false)
const reviewError = ref('')
const reviewed = ref<PlatformAiApproval | null>(null)
const deciding = ref(false)
const ownerId = computed(() => platformSessionUser.value?.userId ?? null)
const minePending = computed(() => approvals.value.filter(approval => approval.status === 'PENDING').length)
const shownApprovals = computed(() => mineFilter.value === 'pending'
  ? approvals.value.filter(approval => approval.status === 'PENDING')
  : approvals.value)
const isAgentPublication = computed(() => reviewed.value?.action === 'AGENT_PUBLISH')
const isKnowledgeSettings = computed(() => reviewed.value?.action === 'KNOWLEDGE_SETTINGS_WRITE')
const knowledgeReview = computed(() => isKnowledgeSettings.value && reviewed.value?.arguments
  ? knowledgeSettingsReview(reviewed.value.arguments, reviewed.value.subject, reviewed.value.invocationId) : null)
const isWorkflowPublication = computed(() => reviewed.value?.action === 'WORKFLOW_PUBLISH')
const isPageAssistantSync = computed(() => reviewed.value?.action === 'PAGE_ASSISTANT_SYNC')
const isWorkflowRollback = computed(() => reviewed.value?.action === 'WORKFLOW_ROLLBACK')
const isKnowledgeImportCommit = computed(() => reviewed.value?.action === 'KNOWLEDGE_IMPORT_COMMIT')
const isKnowledgeImportCancel = computed(() => reviewed.value?.action === 'KNOWLEDGE_IMPORT_CANCEL')
const isToolAcl = computed(() => reviewed.value?.action === 'TOOL_ACL_CHANGE')
const aclOperationLabel = computed(() => ({ CREATE: '新建', UPDATE: '修改', TOGGLE: '启停', DELETE: '删除', BATCH_GRANT: '批量授权' } as Record<string, string>)[String(reviewed.value?.arguments?.operation)] ?? '')
const aclBefore = computed(() => isToolAcl.value ? reviewed.value?.arguments?.before as Record<string, unknown> | null : null)
const aclAfter = computed(() => isToolAcl.value ? reviewed.value?.arguments?.after as Record<string, unknown> | null : null)
const aclBatchTargets = computed(() => (Array.isArray(aclAfter.value?.targets) ? aclAfter.value?.targets : []) as Array<Record<string, string>>)
const aclOverwritten = computed(() => (isToolAcl.value && Array.isArray(reviewed.value?.arguments?.overwritten) ? reviewed.value?.arguments?.overwritten : []) as Array<Record<string, unknown>>)
function aclRuleText(rule: Record<string, unknown>) {
  return `${rule.roleCode} ${rule.permission === 'DENY' ? '禁止' : '允许'}调用 ${rule.targetKind === 'ALL' ? '全部工具' : rule.targetName}`
    + `（${rule.projectCode ?? (rule.projectId ? `项目 ${rule.projectId}` : '平台级')}${rule.enabled === false ? '，已停用' : ''}）`
}
const isKnowledgeChunk = computed(() => reviewed.value?.action === 'KNOWLEDGE_CHUNK_CHANGE')
const chunkBefore = computed(() => isKnowledgeChunk.value ? reviewed.value?.arguments?.before as Record<string, unknown> | undefined : undefined)
const chunkAfter = computed(() => isKnowledgeChunk.value ? reviewed.value?.arguments?.after as Record<string, unknown> | undefined : undefined)
const chunkResource = computed(() => isKnowledgeChunk.value ? reviewed.value?.arguments?.resource as Record<string, unknown> | undefined : undefined)
const isRegistryEnrollment = computed(() => reviewed.value?.action === 'REGISTRY_ENROLLMENT')
const isBusinessSystem = computed(() => reviewed.value?.action === 'BUSINESS_SYSTEM_CHANGE')
const systemOperationLabel = computed(() => ({ CREATE: '新建', UPDATE: '修改', SCAN_SETTINGS: '扫描设置修改', REGISTRY_CREDENTIAL: '注册凭据替换', DELETE: '删除' } as Record<string, string>)[String(reviewed.value?.arguments?.operation)] ?? '')
const systemBefore = computed(() => isBusinessSystem.value ? reviewed.value?.arguments?.before as Record<string, unknown> | null : null)
const systemAfter = computed(() => isBusinessSystem.value ? reviewed.value?.arguments?.after as Record<string, unknown> | null : null)
const systemFieldLabels: Record<string, string> = { name: '名称', projectCode: '项目编码', projectKind: '类型', environment: '环境', owner: '负责人', visibility: '可见范围', baseUrl: '业务系统后端地址', contextPath: '上下文路径' }
const systemChangedFields = computed(() => Object.entries(systemFieldLabels)
  .filter(([key]) => (systemBefore.value?.[key] ?? null) !== (systemAfter.value?.[key] ?? null))
  .map(([key, label]) => ({ key, label, before: String(systemBefore.value?.[key] ?? '空'), after: String(systemAfter.value?.[key] ?? '空') })))
const isModelInstance = computed(() => reviewed.value?.action === 'MODEL_INSTANCE_CHANGE')
const modelOperationLabel = computed(() => ({ CREATE: '新建', UPDATE: '修改', ARCHIVE: '归档' } as Record<string, string>)[String(reviewed.value?.arguments?.operation)] ?? '')
const modelBefore = computed(() => isModelInstance.value ? reviewed.value?.arguments?.before as Record<string, unknown> | null : null)
const modelAfter = computed(() => isModelInstance.value ? reviewed.value?.arguments?.after as Record<string, unknown> | null : null)
const modelFieldLabels: Record<string, string> = { name: '名称', provider: '提供商', modelType: '类型', modelName: '模型名', protocol: '协议', defaultOptions: '默认参数', paramsSchema: '参数定义', remark: '备注' }
const modelShow = (value: unknown) => value == null ? '空' : typeof value === 'object' ? JSON.stringify(value) : String(value)
const modelChangedFields = computed(() => Object.entries(modelFieldLabels)
  .filter(([key]) => modelShow(modelBefore.value?.[key]) !== modelShow(modelAfter.value?.[key]))
  .map(([key, label]) => ({ key, label, before: modelShow(modelBefore.value?.[key]), after: modelShow(modelAfter.value?.[key]) })))
const modelConnectionEntries = computed(() => connectionEntries(modelBefore.value?.connection, modelAfter.value?.connection))
const isRunReplay = computed(() => reviewed.value?.action === 'RUN_REPLAY')
const replaySource = computed(() => isRunReplay.value ? reviewed.value?.arguments?.source as Record<string, unknown> | undefined : undefined)
const replayCommand = computed(() => isRunReplay.value ? reviewed.value?.arguments?.command as Record<string, unknown> | undefined : undefined)
const isHttpConnection = computed(() => reviewed.value?.action === 'HTTP_API_CONNECTION_CHANGE')
const isIdentityMapping = computed(() => reviewed.value?.action === 'IDENTITY_MAPPING_CHANGE')
const isKnowledgeDelete = computed(() => reviewed.value?.action === 'KNOWLEDGE_DELETE')
const isCapabilityReview = computed(() => reviewed.value?.action === 'CAPABILITY_CHANGE_REVIEW')
const isEmbedChange = computed(() => reviewed.value?.action === 'EMBED_CHANGE')
const isApiGraphChange = computed(() => reviewed.value?.action === 'API_GRAPH_CHANGE')
const isCredentialChange = computed(() => reviewed.value?.action === 'WORKFLOW_CREDENTIAL_CHANGE')
const isMemoryErasure = computed(() => reviewed.value?.action === 'MEMORY_ERASURE_REQUEST')
const isAgentWorkflowPublish = computed(() => reviewed.value?.action === 'AGENT_WORKFLOW_PUBLISH')
const agentWorkflowStale = computed(() => isAgentWorkflowPublish.value && Array.isArray(reviewed.value?.arguments?.workflows)
  ? (reviewed.value!.arguments.workflows as Array<Record<string, unknown>>).filter((tool) => tool.stale === true) : [])
const isPageDelivery = computed(() => reviewed.value?.action === 'PAGE_WORKFLOW_DELIVERY')
const pageDeliveryPlan = computed(() => isPageDelivery.value ? reviewed.value?.arguments?.plan as Record<string, unknown> | undefined : undefined)
const pageDeliveryRequest = computed(() => pageDeliveryPlan.value?.request as Record<string, unknown> | undefined)
const credentialBefore = computed(() => isCredentialChange.value ? reviewed.value?.arguments?.before as Record<string, unknown> | undefined : undefined)
const credentialAfter = computed(() => isCredentialChange.value ? reviewed.value?.arguments?.after as Record<string, unknown> | null : null)
const graphCurated = computed(() => (isApiGraphChange.value && Array.isArray(reviewed.value?.arguments?.curatedEdges)
  ? reviewed.value?.arguments?.curatedEdges : []) as Record<string, unknown>[])
const graphStatusText = computed(() => Object.entries((reviewed.value?.arguments?.edgesByStatus ?? {}) as Record<string, unknown>)
  .map(([status, count]) => `${status} ${count}`).join('、') || '没有关系')
const edgeText = (edge?: Record<string, unknown>) => edge
  ? `#${edge.id} 节点 ${edge.sourceNodeId} → ${edge.targetNodeId}（${edge.kind}，${edge.status}${edge.confirmedBy ? `，${edge.confirmedBy} 确认` : ''}）` : ''
const embedBefore = computed(() => isEmbedChange.value ? reviewed.value?.arguments?.before as Record<string, unknown> | undefined : undefined)
const embedAfter = computed(() => isEmbedChange.value ? reviewed.value?.arguments?.after as Record<string, unknown> | null : null)
const embedActions = computed(() => (Array.isArray(embedBefore.value?.actions) ? embedBefore.value?.actions : []) as Record<string, unknown>[])
const listText = (value: unknown) => Array.isArray(value) && value.length ? value.join('、') : '（空）'
const capabilityChange = computed(() => isCapabilityReview.value ? reviewed.value?.arguments?.change as Record<string, unknown> | undefined : undefined)
const capabilityActionLabel = computed(() => ({ APPLY: '采纳', IGNORE: '忽略', ROLLBACK: '回滚' } as Record<string, string>)[String(reviewed.value?.arguments?.action)] ?? '')
const capabilityChangeLabel = computed(() => ({ ADDED: '新增', CHANGED: '修改', DELETED: '删除', UNCHANGED: '未变' } as Record<string, string>)[String(capabilityChange.value?.changeType)] ?? String(capabilityChange.value?.changeType ?? ''))
const prettyJson = (value: unknown) => {
  if (typeof value !== 'string' || !value) return '无'
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}
const deleteBase = computed(() => isKnowledgeDelete.value ? reviewed.value?.arguments?.base as Record<string, unknown> | undefined : undefined)
const deleteFiles = computed(() => (isKnowledgeDelete.value && Array.isArray(reviewed.value?.arguments?.files) ? reviewed.value?.arguments?.files : []) as Record<string, unknown>[])
const deleteFile = computed(() => isKnowledgeDelete.value ? reviewed.value?.arguments?.file as Record<string, unknown> | undefined : undefined)
const identityMappingLabel = computed(() => ({ MAPPING_CREATE: '新建运行时用户映射', MAPPING_DELETE: '删除运行时用户映射', BUSINESS_IDENTITY_SAVE: '保存业务系统账号身份' } as Record<string, string>)[String(reviewed.value?.arguments?.operation)] ?? '')
const identityBefore = computed(() => isIdentityMapping.value ? reviewed.value?.arguments?.before as Record<string, unknown> | Record<string, unknown>[] | undefined : undefined)
const identityAfter = computed(() => isIdentityMapping.value ? reviewed.value?.arguments?.after as Record<string, unknown> | null : null)
const mappingText = (mapping: unknown) => {
  const value = mapping as Record<string, unknown> | null | undefined
  return value ? `平台账号 #${value.platformUserId} → 运行身份 ${value.runtimeUserId ?? value.externalUserId ?? value.globalUserId ?? '（按账号自动生成）'}（租户 ${value.tenantId ?? ''}${value.projectCode ? `，项目 ${value.projectCode}` : ''}）` : ''
}
const identityText = (identity: unknown) => {
  const value = identity as Record<string, unknown> | null | undefined
  return value ? `${value.appId} 账号 ${value.externalUserId}${value.externalUserName ? `（${value.externalUserName}）` : ''}${value.status ? ` · ${value.status}` : ''}` : ''
}
const identityCurrentText = computed(() => {
  const rows = Array.isArray(identityBefore.value) ? identityBefore.value : []
  if (!rows.length) return reviewed.value?.arguments?.operation === 'MAPPING_CREATE' ? '没有生效的映射' : '还没有业务系统账号'
  return rows.map((row) => reviewed.value?.arguments?.operation === 'MAPPING_CREATE' ? mappingText(row) : identityText(row)).join('；')
})
const identityTargetText = computed(() => {
  const args = reviewed.value?.arguments ?? {}
  if (args.operation === 'BUSINESS_IDENTITY_SAVE') return `业务用户 #${args.targetId}`
  if (args.operation === 'MAPPING_DELETE') return `映射 #${args.targetId}`
  return `平台账号 #${identityAfter.value?.platformUserId ?? ''}`
})
const httpConnectionLabel = computed(() => ({ SAVE_OVERRIDE: '修改', SAVE_PROJECT_DEFAULT: '项目默认修改', REMOVE_OVERRIDE: '改回项目默认' } as Record<string, string>)[String(reviewed.value?.arguments?.operation)] ?? '')
const httpConnectionBefore = computed(() => isHttpConnection.value ? reviewed.value?.arguments?.before as Record<string, unknown> | undefined : undefined)
const httpConnectionAfter = computed(() => isHttpConnection.value ? reviewed.value?.arguments?.after as Record<string, unknown> | null : null)
const httpConnectionCurrent = computed(() => reviewed.value?.arguments?.operation === 'SAVE_PROJECT_DEFAULT'
  ? httpConnectionBefore.value?.projectDefault as Record<string, unknown> | null | undefined : httpConnectionBefore.value)
const connectionText = (connection: unknown) => {
  const value = connection as Record<string, unknown> | null | undefined
  return value?.origin ? `${value.origin} · ${value.authMode ?? '无认证'}${value.credentialRef ? ` · 凭据 ${value.credentialName ?? value.credentialRef}` : ''}` : '未配置'
}
const isAgentLifecycle = computed(() => reviewed.value?.action === 'AGENT_LIFECYCLE_CHANGE')
const agentLifecycleLabel = computed(() => ({ ENABLE: '启用', DISABLE: '停用', DELETE: '删除' } as Record<string, string>)[String(reviewed.value?.arguments?.operation)] ?? '')
const lifecycleAgent = computed(() => isAgentLifecycle.value ? reviewed.value?.arguments?.agent as Record<string, unknown> | undefined : undefined)
const isBusinessIndex = computed(() => reviewed.value?.action === 'BUSINESS_INDEX_CHANGE')
const indexBefore = computed(() => isBusinessIndex.value ? reviewed.value?.arguments?.before as Record<string, unknown> | null : null)
const indexAfter = computed(() => isBusinessIndex.value ? reviewed.value?.arguments?.after as Record<string, unknown> | null : null)
const indexFieldLabels: Record<string, string> = { indexName: '名称', sourceSystem: '来源系统', textTemplate: '文本模板', fieldSchema: '字段定义', embeddingModelInstanceId: '向量模型', dimension: '向量维度', chunkSize: '切分长度', chunkOverlap: '切分重叠', splitType: '切分方式', remark: '备注' }
const indexChangedFields = computed(() => Object.entries(indexFieldLabels)
  .filter(([key]) => modelShow(indexBefore.value?.[key]) !== modelShow(indexAfter.value?.[key]))
  .map(([key, label]) => ({ key, label, before: modelShow(indexBefore.value?.[key]), after: modelShow(indexAfter.value?.[key]) })))
const isAccountChange = computed(() => reviewed.value?.action === 'ACCOUNT_CHANGE')
const accountChangeLabel = computed(() => ({ UPDATE_USER: '账号修改', REVOKE_SESSIONS: '强制下线', REPLACE_GRANTS: '角色授权替换', CREATE_ROLE: '新建角色', UPDATE_ROLE: '角色修改' } as Record<string, string>)[String(reviewed.value?.arguments?.operation)] ?? '')
const accountChangeBefore = computed(() => isAccountChange.value ? reviewed.value?.arguments?.before as Record<string, unknown> | null : null)
const accountChangeAfter = computed(() => isAccountChange.value ? reviewed.value?.arguments?.after as Record<string, unknown> | null : null)
const accountTargetText = computed(() => {
  const before = accountChangeBefore.value
  const after = accountChangeAfter.value
  if (reviewed.value?.arguments?.operation === 'CREATE_ROLE') return `新角色 ${after?.roleName ?? ''}（${after?.roleCode ?? ''}）`
  if (reviewed.value?.arguments?.operation === 'UPDATE_ROLE') return `${before?.roleName ?? ''}（${before?.roleCode ?? ''}，#${reviewed.value?.arguments?.targetId}）`
  return `${before?.displayName ?? ''}（${before?.username ?? ''}，#${reviewed.value?.arguments?.targetId}，当前${before?.status ?? ''}）`
})
const accountFieldLabels: Record<string, string> = { displayName: '姓名', email: '邮箱', mobile: '手机号', status: '状态', roleCode: '角色编码', roleName: '角色名称', description: '说明', permissionCodes: '权限' }
const platformAccountFields = computed(() => Object.entries(accountFieldLabels)
  .filter(([key]) => accountChangeAfter.value && key in accountChangeAfter.value && modelShow(accountChangeBefore.value?.[key]) !== modelShow(accountChangeAfter.value?.[key]))
  .map(([key, label]) => ({ key, label, before: modelShow(accountChangeBefore.value?.[key]), after: modelShow(accountChangeAfter.value?.[key]) })))
const grantText = (grants: unknown) => Array.isArray(grants) && grants.length
  ? grants.map(grant => `${grant?.roleName ?? grant?.roleCode ?? `#${grant?.roleId}`} · ${grant?.scopeType === 'PROJECT' ? `项目 ${grant?.scopeValue}` : '全局'}`).join('；')
  : '无'
const isAutomation = computed(() => reviewed.value?.action === 'AUTOMATION_CHANGE')
const automationOperationLabel = computed(() => ({ CREATE: '新建', UPDATE: '修改', RESUME: '恢复', ARCHIVE: '归档' } as Record<string, string>)[String(reviewed.value?.arguments?.operation)] ?? '')
const automationBefore = computed(() => isAutomation.value ? reviewed.value?.arguments?.before as Record<string, unknown> | null : null)
const automationAfter = computed(() => isAutomation.value ? reviewed.value?.arguments?.after as Record<string, unknown> | null : null)
const isMcpPublication = computed(() => reviewed.value?.action === 'MCP_PUBLICATION_CHANGE')
const mcpOperationLabel = computed(() => ({ PUBLISH: '发布', SUSPEND: '暂停', RESUME: '恢复', ARCHIVE: '归档', ROLLBACK: '回滚', EMPLOYEE_ACCESS: '员工接入修改', CLIENT_UPDATE: '客户端修改', CLIENT_REVOKE: '客户端吊销' } as Record<string, string>)[String(reviewed.value?.arguments?.operation)] ?? '')
const mcpPublication = computed(() => isMcpPublication.value ? reviewed.value?.arguments?.publication as Record<string, unknown> | undefined : undefined)
const mcpItems = computed(() => (isMcpPublication.value && Array.isArray(reviewed.value?.arguments?.items) ? reviewed.value?.arguments?.items : []) as Array<Record<string, unknown>>)
const mcpPrecheck = computed(() => isMcpPublication.value ? reviewed.value?.arguments?.precheck as Record<string, unknown> | undefined : undefined)
const mcpClient = computed(() => isMcpPublication.value ? reviewed.value?.arguments?.client as Record<string, unknown> | undefined : undefined)
const mcpAfter = computed(() => isMcpPublication.value ? reviewed.value?.arguments?.after as Record<string, unknown> | null : null)
const mcpCurrent = computed(() => (mcpClient.value ?? reviewed.value?.arguments?.employeeAccess) as Record<string, unknown> | undefined)
const isProviderAccount = computed(() => reviewed.value?.action === 'MODEL_PROVIDER_ACCOUNT_CHANGE')
const accountOperationLabel = computed(() => ({ CREATE: '新建', UPDATE: '修改', DELETE: '删除', CREATE_INSTANCES: '批量接入模型' } as Record<string, string>)[String(reviewed.value?.arguments?.operation)] ?? '')
const accountBefore = computed(() => isProviderAccount.value ? reviewed.value?.arguments?.before as Record<string, unknown> | null : null)
const accountAfter = computed(() => isProviderAccount.value ? reviewed.value?.arguments?.after as Record<string, unknown> | null : null)
const accountModels = computed(() => (Array.isArray(accountAfter.value?.models) ? accountAfter.value?.models : []) as Array<Record<string, string>>)
const accountChangedFields = computed(() => Object.entries({ name: '名称', provider: '提供商', remark: '备注' } as Record<string, string>)
  .filter(([key]) => accountAfter.value && key in accountAfter.value && modelShow(accountBefore.value?.[key]) !== modelShow(accountAfter.value?.[key]))
  .map(([key, label]) => ({ key, label, before: modelShow(accountBefore.value?.[key]), after: modelShow(accountAfter.value?.[key]) })))
const accountConnectionEntries = computed(() => connectionEntries(accountBefore.value?.connection, accountAfter.value?.connection))
function connectionEntries(beforeConnection: unknown, afterConnection: unknown) {
  const before = (beforeConnection ?? {}) as Record<string, unknown>
  const after = (afterConnection ?? {}) as Record<string, unknown>
  return Object.entries(after).flatMap(([key, value]) => {
    if (value && typeof value === 'object' && 'secretSha256' in value) {
      return [{ key, text: `新密钥，不在审核单中，指纹 ${String((value as Record<string, unknown>).secretSha256).slice(0, 12)}…` }]
    }
    if (value === '******') return [{ key, text: '沿用原密钥' }]
    if (/key|secret|token|password/i.test(key) && typeof value === 'string') return [{ key, text: '审核单含明文密钥，不能批准' }]
    return modelShow(before[key]) === modelShow(value) ? [] : [{ key, text: `${modelShow(before[key])} → ${modelShow(value)}` }]
  })
}
function modelSecretsSafe(connection: unknown) {
  if (connection == null) return true
  if (typeof connection !== 'object' || Array.isArray(connection)) return false
  return Object.entries(connection as Record<string, unknown>).every(([key, value]) => !/key|secret|token|password/i.test(key)
    || typeof value !== 'string' || value.includes('******'))
}
const isSkillGovernance = computed(() => reviewed.value?.action === 'SKILL_VERSION_GOVERNANCE')
const skillOperationLabel = computed(() => ({ REVIEW: '评审', PUBLISH: '发布', SET_DEFAULT: '设为默认', DEPRECATE: '弃用', REVOKE: '撤销' } as Record<string, string>)[String(reviewed.value?.arguments?.operation)] ?? '')
const isKnowledgeImport = computed(() => isKnowledgeImportCommit.value || isKnowledgeImportCancel.value)
const importPreview = computed(() => isKnowledgeImportCommit.value
  ? reviewed.value?.arguments?.preview as Record<string, unknown> | undefined : undefined)
const workflowDefinition = computed(() => isWorkflowPublication.value ? reviewed.value?.arguments?.definition as Record<string, unknown> | undefined : undefined)
const workflowPublishedGraph = computed(() => {
  if (!isWorkflowPublication.value) return null
  try {
    const graph = JSON.parse(String(reviewed.value?.arguments?.publishedGraph ?? ''))
    return graph && typeof graph === 'object' && !Array.isArray(graph) ? graph : null
  } catch { return null }
})
const publicationIdentity = computed(() => isAgentPublication.value
  ? reviewed.value?.arguments?.identity as Record<string, unknown> | undefined : undefined)
const publicationVisibility = computed(() => ({ PROJECT: '项目内', PRIVATE: '私有', PUBLIC: '公开' } as Record<string, string>)[String(publicationIdentity.value?.visibility)] ?? '未知')
const publicationRoles = computed<string[] | null>(() => {
  try {
    const value = JSON.parse(String(publicationIdentity.value?.allowedRolesJson ?? '[]'))
    return Array.isArray(value) && value.every(item => typeof item === 'string') ? value : null
  } catch { return null }
})
const reviewReady = computed(() => {
  const approval = reviewed.value
  const args = approval?.arguments
  if (!approval || !args) return false
  if (approval.action === 'REGISTRY_ENROLLMENT') return args.contractVersion === 1 && args.ttlHours === 72
    && typeof args.projectCode === 'string' && !!args.projectCode.trim()
    && approval.subject === `REGISTRY_ENROLLMENT:${args.projectCode}` && args.invocationId === approval.invocationId
  if (approval.action === 'KNOWLEDGE_SETTINGS_WRITE') return knowledgeReview.value !== null
  if (approval.action === 'TOOL_ACL_CHANGE') {
    const operation = String(args.operation)
    const before = args.before as Record<string, unknown> | null
    const after = args.after as Record<string, unknown> | null
    if (operation === 'BATCH_GRANT') return args.contractVersion === 1 && approval.subject === 'TOOL_ACL:BATCH' && Array.isArray(args.overwritten)
      && typeof after?.roleCode === 'string' && Array.isArray(after.targets) && after.targets.length > 0
    return args.contractVersion === 1 && ['CREATE', 'UPDATE', 'TOGGLE', 'DELETE'].includes(operation)
      && approval.subject === `TOOL_ACL:${operation === 'CREATE' ? 'NEW' : args.aclId}`
      && (operation === 'CREATE' ? before == null && !!after && typeof after.roleCode === 'string'
        : !!before && Object.prototype.hasOwnProperty.call(before, 'updatedAt')
          && (operation === 'DELETE' ? after == null : operation === 'TOGGLE' ? typeof after?.enabled === 'boolean' : typeof after?.roleCode === 'string'))
  }
  if (approval.action === 'KNOWLEDGE_CHUNK_CHANGE') {
    const before = args.before as Record<string, unknown> | null
    const after = args.after as Record<string, unknown> | null
    const resource = args.resource as Record<string, unknown> | null
    return args.contractVersion === 1 && Number.isSafeInteger(args.chunkId) && approval.subject === `KNOWLEDGE_CHUNK:${args.chunkId}`
      && typeof args.fileId === 'string' && !!resource && typeof resource.scope === 'string'
      && !!before && typeof before.content === 'string' && /^[0-9a-f]{64}$/.test(String(before.contentSha256))
      && (args.operation === 'TOGGLE' ? typeof after?.enabled === 'boolean' : args.operation === 'UPDATE' && typeof after?.content === 'string')
  }
  if (approval.action === 'BUSINESS_SYSTEM_CHANGE') {
    const operation = String(args.operation)
    const before = args.before as Record<string, unknown> | null
    const after = args.after as Record<string, unknown> | null
    if (args.contractVersion !== 1 || approval.subject !== `BUSINESS_SYSTEM:${operation === 'CREATE' ? 'NEW' : args.projectId}`) return false
    if (operation === 'CREATE') return before == null && typeof after?.name === 'string'
    if (!before || typeof before.name !== 'string') return false
    if (operation === 'UPDATE') return typeof after?.name === 'string'
    if (operation === 'SCAN_SETTINGS') return !!after && typeof after.scanSettings === 'object'
    if (operation === 'REGISTRY_CREDENTIAL') return typeof after?.appKey === 'string' && /^[0-9a-f]{64}$/.test(String(after.appSecretSha256)) && !('appSecret' in after)
    return operation === 'DELETE' && after == null
  }
  if (approval.action === 'MODEL_INSTANCE_CHANGE') {
    const operation = String(args.operation)
    const before = args.before as Record<string, unknown> | null
    const after = args.after as Record<string, unknown> | null
    if (args.contractVersion !== 1 || approval.subject !== `MODEL_INSTANCE:${operation === 'CREATE' ? 'NEW' : args.modelId}`) return false
    if (operation === 'ARCHIVE') return !!before && typeof before.name === 'string' && after == null
    if (operation !== 'CREATE' && operation !== 'UPDATE') return false
    return (operation === 'CREATE' ? before == null : !!before && typeof before.name === 'string')
      && typeof after?.name === 'string' && modelSecretsSafe(after.connection)
  }
  if (approval.action === 'RUN_REPLAY') {
    const source = args.source as Record<string, unknown> | undefined
    const command = args.command as Record<string, unknown> | undefined
    return args.contractVersion === 1 && typeof args.traceId === 'string' && approval.subject === `RUN:${args.traceId}`
      && !!source && typeof source.agentId === 'string' && typeof command?.messageOverride === 'string' && !!command.messageOverride
  }
  if (approval.action === 'AGENT_WORKFLOW_PUBLISH') {
    return args.contractVersion === 1 && typeof args.agentId === 'string' && approval.subject === `AGENT:${args.agentId}`
      && Number.isSafeInteger(args.activeConfigVersionId) && Array.isArray(args.workflows)
      && (args.operation === 'PUBLISH_LATEST' || (args.operation === 'KNOWLEDGE_QA' && typeof args.knowledgeBaseCode === 'string'))
  }
  if (approval.action === 'PAGE_WORKFLOW_DELIVERY') {
    const plan = args.plan as Record<string, unknown> | undefined
    return args.contractVersion === 1 && typeof args.taskId === 'string' && approval.subject === `PAGE_WORKFLOW_TASK:${args.taskId}`
      && !!plan && typeof plan.workflowId === 'string' && !!plan.request
  }
  if (approval.action === 'MEMORY_ERASURE_REQUEST') {
    return args.contractVersion === 1 && ['tenantId', 'runtimeUserId', 'clientRequestId', 'reasonCode', 'referenceId']
      .every((key) => typeof args[key] === 'string' && (args[key] as string).length > 0)
      && approval.subject === `MEMORY_ERASURE:${args.clientRequestId}`
  }
  if (approval.action === 'WORKFLOW_CREDENTIAL_CHANGE') {
    const before = args.before as Record<string, unknown> | undefined
    if (args.contractVersion !== 1 || !Number.isSafeInteger(args.credentialId) || !before || before.id !== args.credentialId
      || approval.subject !== `WORKFLOW_CREDENTIAL:${args.credentialId}`) return false
    return args.operation === 'DISABLE' ? args.after == null : args.operation === 'UPDATE' && !!args.after && typeof args.secretChanged === 'boolean'
  }
  if (approval.action === 'API_GRAPH_CHANGE') {
    if (args.contractVersion !== 1 || !Number.isSafeInteger(args.projectId)) return false
    if (args.operation === 'REGENERATE') return args.edgeId == null && Array.isArray(args.curatedEdges) && typeof args.edgeDigest === 'string'
      && approval.subject === `API_GRAPH:${args.projectId}`
    const edge = args.edge as Record<string, unknown> | undefined
    return args.operation === 'DELETE_EDGE' && Number.isSafeInteger(args.edgeId) && !!edge && edge.id === args.edgeId
      && approval.subject === `API_GRAPH_EDGE:${args.projectId}:${args.edgeId}`
  }
  if (approval.action === 'EMBED_CHANGE') {
    const before = args.before as Record<string, unknown> | undefined
    if (args.contractVersion !== 1 || !Number.isSafeInteger(args.targetId) || !before || before.id !== args.targetId) return false
    if (args.operation === 'DELETE_PAGE') return args.after == null && Array.isArray(before.actions) && approval.subject === `EMBED_PAGE:${args.targetId}`
    const after = args.after as Record<string, unknown> | null
    return args.operation === 'UPDATE_POLICY' && !!after && Array.isArray(after.allowedOrigins) && Array.isArray(after.allowedAgentIds)
      && approval.subject === `EMBED_POLICY:${args.targetId}`
  }
  if (approval.action === 'CAPABILITY_CHANGE_REVIEW') {
    const change = args.change as Record<string, unknown> | undefined
    return args.contractVersion === 1 && typeof args.projectCode === 'string' && Number.isSafeInteger(args.diffItemId)
      && ['APPLY', 'IGNORE', 'ROLLBACK'].includes(String(args.action)) && !!change && change.id === args.diffItemId
      && approval.subject === `CAPABILITY_CHANGE:${args.projectCode}:${args.diffItemId}`
  }
  if (approval.action === 'KNOWLEDGE_DELETE') {
    const base = args.base as Record<string, unknown> | undefined
    if (args.contractVersion !== 1 || typeof args.kbCode !== 'string' || !base || base.code !== args.kbCode) return false
    if (args.operation === 'DELETE_BASE') return args.fileId == null && Array.isArray(args.files) && approval.subject === `KNOWLEDGE_BASE:${args.kbCode}`
    const file = args.file as Record<string, unknown> | undefined
    return args.operation === 'DELETE_FILE' && typeof args.fileId === 'string' && !!file && file.fileId === args.fileId
      && approval.subject === `KNOWLEDGE_FILE:${args.kbCode}/${args.fileId}`
  }
  if (approval.action === 'IDENTITY_MAPPING_CHANGE') {
    const operation = String(args.operation)
    const after = args.after as Record<string, unknown> | null
    if (args.contractVersion !== 1) return false
    if (operation === 'MAPPING_CREATE') return args.targetId == null && Array.isArray(args.before) && !!after && Number.isSafeInteger(after.platformUserId)
      && approval.subject === `RUNTIME_USER_MAPPING:NEW:${after.platformUserId}`
    if (operation === 'MAPPING_DELETE') return Number.isSafeInteger(args.targetId) && approval.subject === `RUNTIME_USER_MAPPING:${args.targetId}`
      && after == null && !!args.before && !Array.isArray(args.before)
    return operation === 'BUSINESS_IDENTITY_SAVE' && Number.isSafeInteger(args.targetId) && approval.subject === `BUSINESS_USER:${args.targetId}`
      && Array.isArray(args.before) && !!after && typeof after.appId === 'string' && typeof after.externalUserId === 'string'
  }
  if (approval.action === 'HTTP_API_CONNECTION_CHANGE') {
    const operation = String(args.operation)
    const before = args.before as Record<string, unknown> | undefined
    const after = args.after as Record<string, unknown> | null
    if (args.contractVersion !== 1 || !Number.isSafeInteger(args.apiId) || approval.subject !== `HTTP_API:${args.apiId}` || !before) return false
    if (operation === 'REMOVE_OVERRIDE') return after == null && Number.isSafeInteger(args.expectedRevision)
    return (operation === 'SAVE_OVERRIDE' || operation === 'SAVE_PROJECT_DEFAULT') && !!after && 'origin' in after
  }
  if (approval.action === 'AGENT_LIFECYCLE_CHANGE') {
    const agent = args.agent as Record<string, unknown> | undefined
    return args.contractVersion === 1 && typeof args.agentId === 'string' && approval.subject === `AGENT:${args.agentId}`
      && ['ENABLE', 'DISABLE', 'DELETE'].includes(String(args.operation)) && agent?.id === args.agentId
  }
  if (approval.action === 'BUSINESS_INDEX_CHANGE') {
    const before = args.before as Record<string, unknown> | null
    const after = args.after as Record<string, unknown> | null
    if (args.contractVersion !== 1 || typeof args.indexCode !== 'string' || approval.subject !== `BUSINESS_INDEX:${args.indexCode}`
      || !before || before.indexCode !== args.indexCode) return false
    return args.operation === 'DELETE' ? after == null : args.operation === 'UPDATE' && after?.indexCode === args.indexCode
  }
  if (approval.action === 'ACCOUNT_CHANGE') {
    const operation = String(args.operation)
    const before = args.before as Record<string, unknown> | null
    const after = args.after as Record<string, unknown> | null
    const role = operation === 'CREATE_ROLE' || operation === 'UPDATE_ROLE'
    if (args.contractVersion !== 1 || approval.subject !== (operation === 'CREATE_ROLE' ? 'PLATFORM_ROLE:NEW'
      : `${role ? 'PLATFORM_ROLE' : 'PLATFORM_USER'}:${args.targetId}`)) return false
    if (operation === 'CREATE_ROLE') return before == null && typeof after?.roleCode === 'string' && Array.isArray(after.permissionCodes)
    if (!before || !Number.isSafeInteger(args.targetId)) return false
    if (operation === 'UPDATE_ROLE') return typeof after?.roleName === 'string' && Array.isArray(after.permissionCodes)
    if (operation === 'UPDATE_USER') return typeof after?.status === 'string'
    if (operation === 'REPLACE_GRANTS') return Array.isArray(after?.grants)
    return operation === 'REVOKE_SESSIONS' && after == null
  }
  if (approval.action === 'AUTOMATION_CHANGE') {
    const operation = String(args.operation)
    const before = args.before as Record<string, unknown> | null
    const after = args.after as Record<string, unknown> | null
    if (args.contractVersion !== 1 || approval.subject !== `AUTOMATION:${operation === 'CREATE' ? 'NEW' : args.automationKey}`) return false
    if (operation === 'CREATE') return before == null && typeof after?.name === 'string'
    if (!before || !Number.isSafeInteger(before.revision)) return false
    if (operation === 'UPDATE') return !!after && typeof after === 'object'
    return (operation === 'RESUME' || operation === 'ARCHIVE') && after == null
  }
  if (approval.action === 'MCP_PUBLICATION_CHANGE') {
    const operation = String(args.operation)
    const publication = args.publication as Record<string, unknown> | undefined
    if (args.contractVersion !== 1 || !Number.isSafeInteger(args.publicationId) || approval.subject !== `MCP_PUBLICATION:${args.publicationId}`
      || !publication || typeof publication.state !== 'string' || !Array.isArray(args.items)) return false
    if (operation === 'PUBLISH') return !!args.precheck && typeof args.precheck === 'object'
    if (['SUSPEND', 'RESUME', 'ARCHIVE'].includes(operation)) return args.after == null
    if (operation === 'ROLLBACK') return Number.isSafeInteger(args.targetId)
    if (operation === 'EMPLOYEE_ACCESS') return !!args.after && !!args.employeeAccess
    if (operation === 'CLIENT_REVOKE') return Number.isSafeInteger(args.targetId) && !!args.client
    return operation === 'CLIENT_UPDATE' && Number.isSafeInteger(args.targetId) && !!args.client && !!args.after
  }
  if (approval.action === 'MODEL_PROVIDER_ACCOUNT_CHANGE') {
    const operation = String(args.operation)
    const before = args.before as Record<string, unknown> | null
    const after = args.after as Record<string, unknown> | null
    if (args.contractVersion !== 1 || approval.subject !== `MODEL_PROVIDER_ACCOUNT:${operation === 'CREATE' ? 'NEW' : args.accountId}`) return false
    if (operation === 'CREATE') return before == null && typeof after?.name === 'string' && typeof after.provider === 'string' && modelSecretsSafe(after.connection)
    if (!before || typeof before.name !== 'string') return false
    if (operation === 'DELETE') return after == null
    if (operation === 'CREATE_INSTANCES') return Array.isArray(after?.models) && after.models.length > 0
    return operation === 'UPDATE' && !!after && modelSecretsSafe(after.connection)
  }
  if (approval.action === 'SKILL_VERSION_GOVERNANCE') {
    const operation = String(args.operation)
    return args.contractVersion === 1 && ['REVIEW', 'PUBLISH', 'SET_DEFAULT', 'DEPRECATE', 'REVOKE'].includes(operation)
      && Number.isSafeInteger(args.skillId) && Number.isSafeInteger(args.versionId)
      && approval.subject === `SKILL:${args.skillId}:VERSION:${args.versionId}`
      && typeof args.sourceSha256 === 'string' && /^[0-9a-f]{64}$/.test(args.sourceSha256) && typeof args.status === 'string'
      && (operation === 'REVIEW' ? args.decision === 'APPROVE' || args.decision === 'REJECT' : args.decision == null)
  }
  if (approval.action === 'KNOWLEDGE_IMPORT_COMMIT' || approval.action === 'KNOWLEDGE_IMPORT_CANCEL') {
    const commit = approval.action === 'KNOWLEDGE_IMPORT_COMMIT'
    const preview = args.preview as Record<string, unknown> | undefined
    return args.contractVersion === 1 && args.operation === (commit ? 'COMMIT' : 'CANCEL')
      && typeof args.jobId === 'string' && approval.subject === `KNOWLEDGE_IMPORT_JOB:${args.jobId}`
      && typeof args.knowledgeBaseCode === 'string' && !!args.knowledgeBaseCode
      && (!commit || (args.status === 'PARSED' && !!preview && Number.isSafeInteger(preview.totalChunks)
        && typeof preview.chunksSha256 === 'string' && /^[0-9a-f]{64}$/.test(preview.chunksSha256)))
  }
  if (approval.action === 'WORKFLOW_ROLLBACK') {
    return args.contractVersion === 1 && approval.subject === `WORKFLOW:${args.workflowId}`
      && Number.isSafeInteger(args.versionId) && Number(args.versionId) > 0 && typeof args.targetVersion === 'string'
      && typeof args.baseRevision === 'string' && !!args.baseRevision
      && typeof args.targetGraphSha256 === 'string' && /^[0-9a-f]{64}$/.test(args.targetGraphSha256)
      && args.activeVersionId !== args.versionId
  }
  if (approval.action === 'PAGE_ASSISTANT_SYNC') {
    return args.contractVersion === 1 && approval.subject === `WORKFLOW:${args.workflowId}`
      && typeof args.projectCode === 'string' && !!args.projectCode && Number.isSafeInteger(args.workflowActiveVersionId)
      && (args.targetExists === true
        ? typeof args.agentId === 'string' && typeof args.baseRevision === 'string' && /^[a-f0-9]{64}$/.test(args.baseRevision) && args.createTarget === false
        : args.targetExists === false && args.createTarget === true && args.baseRevision == null)
  }
  if (approval.action === 'WORKFLOW_PUBLISH') {
    return args.contractVersion === 1 && !!workflowDefinition.value && workflowDefinition.value.id === args.workflowId
      && approval.subject === `WORKFLOW:${args.workflowId}` && typeof args.version === 'string' && !!args.version.trim()
      && typeof args.baseRevision === 'string' && !!args.baseRevision
      && typeof args.publishedGraph === 'string' && !!args.publishedGraph.trim()
      && workflowPublishedGraph.value !== null
      && typeof args.publicationDigest === 'string' && /^[0-9a-f]{64}$/.test(args.publicationDigest)
      && Object.prototype.hasOwnProperty.call(args, 'activeVersionId')
      && Object.prototype.hasOwnProperty.call(args, 'projectId') && Object.prototype.hasOwnProperty.call(args, 'projectCode')
  }
  if (approval.action === 'AGENT_PUBLISH') {
    const snapshot = args.snapshot as Record<string, unknown> | undefined
    return !!snapshot && snapshot.status === 'DRAFT' && snapshot.id === args.configVersionId && snapshot.agentId === args.agentId
      && approval.subject === `AGENT:${args.agentId}` && Number.isSafeInteger(args.configVersionId) && Number(args.configVersionId) > 0
      && typeof args.baseRevision === 'string' && /^[a-f0-9]{64}$/.test(args.baseRevision)
      && args.workflowVersions != null && typeof args.workflowVersions === 'object' && !Array.isArray(args.workflowVersions)
      && Object.values(args.workflowVersions).every(id => Number.isSafeInteger(id) && Number(id) > 0)
      && Object.prototype.hasOwnProperty.call(args, 'projectId') && Object.prototype.hasOwnProperty.call(args, 'projectCode')
      && Object.prototype.hasOwnProperty.call(args, 'activeConfigVersionId')
      && !!publicationIdentity.value && typeof publicationIdentity.value.name === 'string'
      && publicationVisibility.value !== '未知' && typeof publicationIdentity.value.enabled === 'boolean'
      && publicationRoles.value !== null
  }
  return approval.action === 'HTTP_API_WRITE' ? typeof args.qualifiedName === 'string'
    : approval.action === 'BUSINESS_METHOD_WRITE' && Object.prototype.hasOwnProperty.call(args, 'input')
})
const publicationWorkflowPins = computed(() => {
  const args = isAgentPublication.value ? reviewed.value?.arguments : null
  if (!args?.workflowVersions || typeof args.workflowVersions !== 'object' || Array.isArray(args.workflowVersions)) return []
  const tools = (args.snapshot as Record<string, unknown> | undefined)?.tools
  return Object.entries(args.workflowVersions).map(([workflowId, versionId]) => {
    const tool = Array.isArray(tools) ? tools.find(item => item?.workflowId === workflowId) : null
    return { workflowId, versionId, previousVersionId: tool?.workflowVersionId,
      name: tool?.workflowName ? `${tool.workflowName}（${workflowId}）` : workflowId }
  })
})
const reviewInput = computed(() => {
  const approval = reviewed.value
  const args = approval?.arguments
  if (!approval || !args) return null
  return approval.action === 'REGISTRY_ENROLLMENT'
    ? { projectCode: args.projectCode, ttlHours: args.ttlHours, invocationId: args.invocationId }
    : approval.action === 'KNOWLEDGE_SETTINGS_WRITE'
    ? { resource: args.resource, before: args.before, after: args.after, changes: args.changes }
    : approval.action === 'WORKFLOW_PUBLISH'
    ? { definition: args.definition, publishedGraph: workflowPublishedGraph.value }
    : approval.action === 'AGENT_PUBLISH'
    ? { identity: args.identity, configuration: args.snapshot, workflowVersions: args.workflowVersions }
    : approval.action === 'TOOL_ACL_CHANGE'
    ? { operation: args.operation, aclId: args.aclId, before: args.before, overwritten: args.overwritten, after: args.after }
    : approval.action === 'AGENT_WORKFLOW_PUBLISH'
    ? { operation: args.operation, agentId: args.agentId, activeConfigVersionId: args.activeConfigVersionId, workflows: args.workflows, knowledgeBaseCode: args.knowledgeBaseCode, modelInstanceId: args.modelInstanceId }
    : approval.action === 'PAGE_WORKFLOW_DELIVERY'
    ? { projectCode: args.projectCode, taskId: args.taskId, plan: args.plan }
    : approval.action === 'MEMORY_ERASURE_REQUEST'
    ? { tenantId: args.tenantId, runtimeUserId: args.runtimeUserId, clientRequestId: args.clientRequestId, reasonCode: args.reasonCode, referenceId: args.referenceId }
    : approval.action === 'WORKFLOW_CREDENTIAL_CHANGE'
    ? { operation: args.operation, credentialId: args.credentialId, before: args.before, after: args.after, secretChanged: args.secretChanged }
    : approval.action === 'API_GRAPH_CHANGE'
    ? { operation: args.operation, projectId: args.projectId, edgeId: args.edgeId, edge: args.edge, nodeCount: args.nodeCount,
        edgeCount: args.edgeCount, edgesByStatus: args.edgesByStatus, curatedEdges: args.curatedEdges, edgeDigest: args.edgeDigest }
    : approval.action === 'EMBED_CHANGE'
    ? { operation: args.operation, targetId: args.targetId, before: args.before, after: args.after }
    : approval.action === 'CAPABILITY_CHANGE_REVIEW'
    ? { projectCode: args.projectCode, diffItemId: args.diffItemId, action: args.action, note: args.note, change: args.change }
    : approval.action === 'KNOWLEDGE_DELETE'
    ? { operation: args.operation, kbCode: args.kbCode, fileId: args.fileId, base: args.base, files: args.files, file: args.file }
    : approval.action === 'IDENTITY_MAPPING_CHANGE'
    ? { operation: args.operation, targetId: args.targetId, before: args.before, after: args.after }
    : approval.action === 'HTTP_API_CONNECTION_CHANGE'
    ? { operation: args.operation, apiId: args.apiId, before: args.before, after: args.after, expectedRevision: args.expectedRevision }
    : approval.action === 'AGENT_LIFECYCLE_CHANGE'
    ? { operation: args.operation, agentId: args.agentId, agent: args.agent }
    : approval.action === 'BUSINESS_INDEX_CHANGE'
    ? { operation: args.operation, indexCode: args.indexCode, before: args.before, after: args.after }
    : approval.action === 'BUSINESS_SYSTEM_CHANGE'
    ? { operation: args.operation, projectId: args.projectId, before: args.before, after: args.after }
    : approval.action === 'RUN_REPLAY'
    ? { traceId: args.traceId, source: args.source, command: args.command }
    : approval.action === 'ACCOUNT_CHANGE'
    ? { operation: args.operation, targetId: args.targetId, before: args.before, after: args.after }
    : approval.action === 'AUTOMATION_CHANGE'
    ? { operation: args.operation, automationKey: args.automationKey, before: args.before, after: args.after }
    : approval.action === 'MCP_PUBLICATION_CHANGE'
    ? { operation: args.operation, publicationId: args.publicationId, targetId: args.targetId, publication: args.publication, items: args.items,
        revisions: args.revisions, precheck: args.precheck, client: args.client, employeeAccess: args.employeeAccess, after: args.after }
    : approval.action === 'MODEL_PROVIDER_ACCOUNT_CHANGE'
    ? { operation: args.operation, accountId: args.accountId, before: args.before, after: args.after }
    : approval.action === 'MODEL_INSTANCE_CHANGE'
    ? { operation: args.operation, modelId: args.modelId, before: args.before, after: args.after }
    : approval.action === 'KNOWLEDGE_CHUNK_CHANGE'
    ? { operation: args.operation, fileId: args.fileId, chunkId: args.chunkId, resource: args.resource, before: args.before, after: args.after }
    : approval.action === 'SKILL_VERSION_GOVERNANCE'
    ? { operation: args.operation, skill: `${args.publisher}/${args.name}`, versionId: args.versionId, version: args.version, status: args.status,
        sourceSha256: args.sourceSha256, contentTreeSha256: args.contentTreeSha256, riskReport: args.riskReport,
        defaultVersionId: args.defaultVersionId, decision: args.decision, comment: args.comment }
    : approval.action === 'KNOWLEDGE_IMPORT_COMMIT' || approval.action === 'KNOWLEDGE_IMPORT_CANCEL'
    ? { jobId: args.jobId, knowledgeBase: args.knowledgeBaseCode, file: args.fileName, fileId: args.fileId,
        replaceFileId: args.replaceFileId, ...(approval.action === 'KNOWLEDGE_IMPORT_COMMIT' ? { preview: args.preview } : {}) }
    : approval.action === 'WORKFLOW_ROLLBACK'
    ? { workflowId: args.workflowId, project: args.projectCode, restoreVersionId: args.versionId, restoreVersion: args.targetVersion,
        targetGraphSha256: args.targetGraphSha256, replacesActiveVersionId: args.activeVersionId, baseRevision: args.baseRevision }
    : approval.action === 'PAGE_ASSISTANT_SYNC'
    ? { workflowId: args.workflowId, workflowActiveVersionId: args.workflowActiveVersionId, project: args.projectCode,
        target: args.targetExists ? { agentId: args.agentId, agentKeySlug: args.agentKeySlug, baseRevision: args.baseRevision } : 'CREATE',
        modelInstanceId: args.modelInstanceId, replaceWorkflowId: args.replaceWorkflowId }
    : approval.action === 'HTTP_API_WRITE'
    ? { pathParams: args.pathParams ?? {}, queryParams: args.queryParams ?? {}, body: args.body ?? null }
    : args.input
})
let approvalGeneration = 0
let reviewGeneration = 0

watch(canReviewEmployees, (can) => {
  if (!can && tab.value === 'employees') tab.value = 'mine'
})

watch(ownerId, (owner) => {
  approvalGeneration += 1
  reviewGeneration += 1
  connections.value = []
  approvals.value = []
  approvalsLoading.value = false
  approvalsError.value = ''
  reviewOpen.value = false
  reviewed.value = null
  reviewLoading.value = false
  reviewError.value = ''
  deciding.value = false
  if (owner !== null) {
    void loadApprovals()
    void loadConnections(owner)
  }
}, { immediate: true })

watch(reviewOpen, (open) => {
  if (!open) {
    reviewGeneration += 1
    reviewed.value = null
    reviewLoading.value = false
    reviewError.value = ''
  }
})

async function loadConnections(owner: number) {
  try {
    const { data } = await listPlatformAiConnections()
    if (owner === ownerId.value) connections.value = data
  } catch {
    // Names fall back to 「连接 #id」.
  }
}

async function loadApprovals() {
  const owner = ownerId.value
  if (owner === null) return
  const generation = ++approvalGeneration
  approvalsLoading.value = true
  approvalsError.value = ''
  try {
    const { data } = await listPlatformAiApprovals()
    if (generation === approvalGeneration && owner === ownerId.value) approvals.value = data
  } catch {
    if (generation === approvalGeneration && owner === ownerId.value) approvalsError.value = '审核列表加载失败，请刷新重试。'
  } finally {
    if (generation === approvalGeneration && owner === ownerId.value) approvalsLoading.value = false
  }
}

async function openReview(approval: PlatformAiApproval) {
  const owner = ownerId.value
  if (owner === null) return
  const generation = ++reviewGeneration
  reviewed.value = null
  reviewError.value = ''
  reviewLoading.value = true
  reviewOpen.value = true
  try {
    const { data } = await reviewPlatformAiApproval(approval.id)
    if (generation !== reviewGeneration || owner !== ownerId.value || !reviewOpen.value) return
    if (data.id !== approval.id || data.status !== 'PENDING' || !data.arguments || !data.requestDigest) {
      reviewError.value = '该请求已变化或不再待审核，请刷新列表。'
    } else {
      reviewed.value = data
    }
  } catch {
    if (generation === reviewGeneration && owner === ownerId.value) reviewError.value = '审核内容加载失败，请关闭后重试。'
  } finally {
    if (generation === reviewGeneration && owner === ownerId.value) reviewLoading.value = false
  }
}

async function decideApproval(approve: boolean) {
  const owner = ownerId.value
  const snapshot = reviewed.value
  const generation = reviewGeneration
  if (owner === null || !snapshot || (approve && !reviewReady.value) || snapshot.status !== 'PENDING' || deciding.value) return
  deciding.value = true
  reviewError.value = ''
  try {
    await decidePlatformAiApproval(snapshot.id, snapshot.requestDigest, approve)
    if (generation !== reviewGeneration || owner !== ownerId.value) return
    reviewOpen.value = false
    ElMessage.success(approve ? '已批准，等待 AI 执行。' : '已拒绝该操作。')
    await loadApprovals()
  } catch {
    if (generation === reviewGeneration && owner === ownerId.value) reviewError.value = '审核提交失败，请刷新后重新核对。'
  } finally {
    if (owner === ownerId.value) deciding.value = false
  }
}

function connectionName(id: number) {
  return connections.value.find(connection => connection.id === id)?.label ?? `连接 #${id}`
}

function approvalSubject(approval: PlatformAiApproval) {
  if (approval.action === 'REGISTRY_ENROLLMENT') return `首次注册授权 ${approval.subject.replace(/^REGISTRY_ENROLLMENT:/, '')}`
  if (approval.action === 'KNOWLEDGE_SETTINGS_WRITE') return `修改知识库设置 ${approval.subject.replace(/^KNOWLEDGE:/, '')}`
  if (approval.action === 'WORKFLOW_PUBLISH') return `发布 Workflow ${approval.subject.replace(/^WORKFLOW:/, '')}`
  if (approval.action === 'HTTP_API_WRITE') return `HTTP API #${approval.subject.replace('HTTP_API:', '')}`
  if (approval.action === 'AGENT_PUBLISH') return `发布 Agent ${approval.subject.replace(/^AGENT:/, '')}`
  if (approval.action === 'TOOL_ACL_CHANGE') return `调用权限规则 ${approval.subject === 'TOOL_ACL:NEW' ? '新建' : approval.subject === 'TOOL_ACL:BATCH' ? '批量授权' : approval.subject.replace(/^TOOL_ACL:/, '#')}`
  if (approval.action === 'AGENT_WORKFLOW_PUBLISH') return `Agent ${approval.subject.replace(/^AGENT:/, '')} 线上配置`
  if (approval.action === 'PAGE_WORKFLOW_DELIVERY') return `页面 Workflow 交付 ${approval.subject.replace(/^PAGE_WORKFLOW_TASK:/, '')}`
  if (approval.action === 'MEMORY_ERASURE_REQUEST') return `跨域记忆删除 ${approval.subject.replace(/^MEMORY_ERASURE:/, '')}`
  if (approval.action === 'WORKFLOW_CREDENTIAL_CHANGE') return `Workflow 凭据 #${approval.subject.replace(/^WORKFLOW_CREDENTIAL:/, '')}`
  if (approval.action === 'API_GRAPH_CHANGE') return approval.subject.startsWith('API_GRAPH_EDGE:')
    ? `删除 API 关系 #${approval.subject.split(':')[2]}` : `重新生成项目 #${approval.subject.replace(/^API_GRAPH:/, '')} 的 API 关系图`
  if (approval.action === 'EMBED_CHANGE') return approval.subject.startsWith('EMBED_PAGE:')
    ? `删除嵌入页面 #${approval.subject.replace(/^EMBED_PAGE:/, '')}` : `嵌入接入策略 #${approval.subject.replace(/^EMBED_POLICY:/, '')}`
  if (approval.action === 'CAPABILITY_CHANGE_REVIEW') return `能力变更 ${approval.subject.replace(/^CAPABILITY_CHANGE:/, '').replace(/:(\d+)$/, ' #$1')}`
  if (approval.action === 'KNOWLEDGE_DELETE') return approval.subject.startsWith('KNOWLEDGE_BASE:')
    ? `删除知识库 ${approval.subject.replace(/^KNOWLEDGE_BASE:/, '')}` : `删除知识库文件 ${approval.subject.replace(/^KNOWLEDGE_FILE:/, '')}`
  if (approval.action === 'IDENTITY_MAPPING_CHANGE') return approval.subject.startsWith('BUSINESS_USER:')
    ? `业务用户 #${approval.subject.replace(/^BUSINESS_USER:/, '')} 的账号身份`
    : `运行时用户映射 ${approval.subject.replace(/^RUNTIME_USER_MAPPING:/, '').replace(/^NEW:/, '新建 · 平台账号 #')}`
  if (approval.action === 'HTTP_API_CONNECTION_CHANGE') return `API 连接 #${approval.subject.replace(/^HTTP_API:/, '')}`
  if (approval.action === 'AGENT_LIFECYCLE_CHANGE') return `Agent ${approval.subject.replace(/^AGENT:/, '')} 启停或删除`
  if (approval.action === 'BUSINESS_INDEX_CHANGE') return `业务索引 ${approval.subject.replace(/^BUSINESS_INDEX:/, '')}`
  if (approval.action === 'KNOWLEDGE_CHUNK_CHANGE') return `知识切片 ${approval.subject.replace(/^KNOWLEDGE_CHUNK:/, '#')}`
  if (approval.action === 'RUN_REPLAY') return `重放运行 ${approval.subject.replace(/^RUN:/, '')}`
  if (approval.action === 'ACCOUNT_CHANGE') return approval.subject === 'PLATFORM_ROLE:NEW' ? '新建角色'
    : approval.subject.startsWith('PLATFORM_ROLE:') ? `角色 #${approval.subject.replace(/^PLATFORM_ROLE:/, '')}` : `账号 #${approval.subject.replace(/^PLATFORM_USER:/, '')}`
  if (approval.action === 'AUTOMATION_CHANGE') return `自动化 ${approval.subject === 'AUTOMATION:NEW' ? '新建' : approval.subject.replace(/^AUTOMATION:/, '')}`
  if (approval.action === 'MCP_PUBLICATION_CHANGE') return `MCP 发布 #${approval.subject.replace(/^MCP_PUBLICATION:/, '')}`
  if (approval.action === 'MODEL_PROVIDER_ACCOUNT_CHANGE') return `提供商账号 ${approval.subject === 'MODEL_PROVIDER_ACCOUNT:NEW' ? '新建' : approval.subject.replace(/^MODEL_PROVIDER_ACCOUNT:/, '')}`
  if (approval.action === 'MODEL_INSTANCE_CHANGE') return `模型实例 ${approval.subject === 'MODEL_INSTANCE:NEW' ? '新建' : approval.subject.replace(/^MODEL_INSTANCE:/, '')}`
  if (approval.action === 'BUSINESS_SYSTEM_CHANGE') return `业务系统 ${approval.subject === 'BUSINESS_SYSTEM:NEW' ? '新建' : approval.subject.replace(/^BUSINESS_SYSTEM:/, '#')}`
  if (approval.action === 'SKILL_VERSION_GOVERNANCE') return `Skill 版本操作 ${approval.subject.replace(/^SKILL:(\d+):VERSION:(\d+)$/, 'Skill #$1 版本 #$2')}`
  if (approval.action === 'KNOWLEDGE_IMPORT_COMMIT') return `知识库文件入库 ${approval.subject.replace(/^KNOWLEDGE_IMPORT_JOB:/, '')}`
  if (approval.action === 'KNOWLEDGE_IMPORT_CANCEL') return `取消知识库导入 ${approval.subject.replace(/^KNOWLEDGE_IMPORT_JOB:/, '')}`
  if (approval.action === 'WORKFLOW_ROLLBACK') return `回滚 Workflow ${approval.subject.replace(/^WORKFLOW:/, '')}`
  if (approval.action === 'PAGE_ASSISTANT_SYNC') return `同步页面助手 ${approval.subject.replace(/^WORKFLOW:/, '')} 到页面副驾驶`
  return approval.subject
}

function approvalStatusLabel(status: PlatformAiApproval['status']) {
  const labels: Record<PlatformAiApproval['status'], string> = {
    PENDING: '待审核', APPROVED: '已批准，等待执行', REJECTED: '已拒绝', EXPIRED: '已过期',
    EXECUTING: '执行中', SUCCEEDED: '已完成', FAILED: '未完成', UNKNOWN: '结果未知，请核对原申请和目标状态',
  }
  return labels[status] ?? status
}

function approvalStatusType(status: PlatformAiApproval['status']) {
  return status === 'SUCCEEDED' ? 'success' : status === 'PENDING' || status === 'UNKNOWN' ? 'warning'
    : status === 'FAILED' || status === 'REJECTED' ? 'danger' : 'info'
}

function formatTime(value?: string | null) {
  if (!value) return '未知'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped lang="scss">
.section-heading, .section-actions, .connection-row, .connection-title { display: flex; align-items: center; gap: 12px; }
.section-heading, .connection-row { justify-content: space-between; }
.section-heading { align-items: flex-start; }
.section-heading h2 { margin: 0; font-size: 1rem; }
.section-heading p, .connection-main p, .muted { color: var(--text-secondary); line-height: 1.6; }
.section-heading p, .connection-main p { margin: 6px 0; }
.connection-list { padding: 0; margin: 16px 0 0; list-style: none; }
.connection-row { padding: 16px 0; border-top: 1px solid var(--border-divider); }
.connection-main { min-width: 0; overflow-wrap: anywhere; }
.connection-main small { color: var(--text-secondary); }
.setup-code { padding: 12px; overflow-x: auto; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--text-primary); background: var(--surface-solid-opaque-raised); border: 1px solid var(--border-divider); border-radius: 8px; }
.review-note { line-height: 1.6; color: var(--text-secondary); }
.review-facts { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 10px 20px; overflow-wrap: anywhere; }
.review-facts dt { color: var(--text-secondary); }
.review-facts dd { margin: 0; }
.publication-pins { margin: 0; padding-left: 18px; }
.review-input-title { margin: 20px 0 8px; font-size: 1rem; }
.review-input { max-height: 320px; overflow: auto; }
.error-message { color: var(--el-color-danger); }

.ai-approvals__count {
  min-width: 18px;
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 999px;
  color: var(--text-on-brand);
  font-size: 11px;
  line-height: 18px;
  text-align: center;
  background: var(--status-warning);
}

.ai-approvals__filters {
  display: inline-flex;
  overflow: hidden;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-sm);

  button { padding: 6px 12px; border: 0; color: var(--text-secondary); font-size: 13px; background: transparent; cursor: pointer; }
  button + button { border-left: 1px solid var(--border-divider); }
  button.active { color: var(--brand-primary); font-weight: 600; background: var(--surface-glass-inset); }
}

@media (max-width: 640px) {
  .section-heading, .section-actions, .connection-row { align-items: flex-start; flex-wrap: wrap; }
}
.chunk-text { white-space: pre-wrap; max-height: 240px; overflow: auto; }
</style>
