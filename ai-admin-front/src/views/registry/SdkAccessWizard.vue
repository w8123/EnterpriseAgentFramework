<template>
  <div class="sdk-access-page project-workbench-page workbench-page--list" :class="theme === 'dark' ? 'is-dark-skin' : 'is-light-skin'">
    <span class="page-ambient-center" aria-hidden="true" />
    <PageHeader variant="workbench" domain="project" presentation="plain" title="项目接入工作台" description="接入业务系统，核对 SDK 同步、业务身份和页面对话。">
      <template #meta>
        <span>{{ project?.name || projectCode }} · {{ project?.projectCode || projectCode }} · SDK 接入</span>
        <LiquidGlassSelect v-if="accessMode === 'manual'" v-model="sdkLanguage" class="sdk-language-picker" aria-label="业务系统语言" dropdown-title="业务系统语言">
          <el-option v-for="option in sdkAccessLanguageOptions" :key="option.value" v-bind="option" />
        </LiquidGlassSelect>
        <span v-else class="sdk-detected-language">{{ detectedSdkLanguage ? `技术栈：${detectedSdkLanguageLabel}` : '等待 AI 识别技术栈' }}</span>
      </template>
      <template #mode>
        <div v-if="!projectMissing && !loadError" class="sdk-header-controls">
          <HeaderModeSwitch
            v-model="accessMode"
            :options="accessModeOptions"
            aria-label="SDK 接入方式"
          />
        </div>
      </template>
    </PageHeader>

    <LiquidGlassNotice
      v-if="project && !isSdkBackedProject"
      class="page-alert"
      type="warning"
      title="当前项目不是 SDK 接入项目"
      description="项目接入工作台仅适用于 SDK 接入或混合接入项目；OpenAPI 导入项目请继续使用 API 目录和项目来源工作台。"
    />

    <ProjectRouteMissingState
      v-if="projectMissing"
      :project-code="projectCode"
    />

    <ProjectWorkbenchLoadErrorState
      v-else-if="loadError"
      title="项目接入工作台加载失败"
      :message="loadError"
      :loading="loading"
      @retry="loadAll"
    />

    <main v-else class="access-workbench">
      <section v-show="accessMode === 'manual'" class="manual-access-pane wizard-shell">
        <section class="step-progress access-progress--refined" aria-label="SDK 接入步骤">
          <div class="access-progress">
            <span>
              接入清单
              <strong>{{ completedStepCount }}/{{ steps.length }}</strong>
              项已核对
            </span>
            <div class="access-progress-track" aria-hidden="true">
              <i :style="{ width: `${completedPercent}%` }" />
            </div>
          </div>
          <button
            v-for="step in steps"
            :key="step.key"
            class="progress-step"
            :class="{ active: activeStep === step.key, done: step.done }"
            :aria-current="activeStep === step.key ? 'step' : undefined"
            type="button"
            @click="activeStep = step.key"
          >
            <span class="step-index">
              <el-icon v-if="step.done"><Check /></el-icon>
              <span v-else class="step-dot" />
            </span>
            <span class="step-copy">
              <span class="step-title-line">
                <span class="step-number">{{ step.index }}</span>
                <strong>{{ step.title }}</strong>
              </span>
              <small>{{ step.status }}</small>
            </span>
            <el-icon v-if="activeStep === step.key" class="step-caret"><ArrowRight /></el-icon>
          </button>
        </section>

        <section class="stage-shell">
          <section class="focus-panel">
            <div class="step-screen">
              <div class="panel-head">
                <div>
                  <span class="step-kicker">步骤 {{ activeStepIndex + 1 }} / {{ steps.length }}</span>
                  <h2>{{ activeStepDetails.title }}</h2>
                  <p>{{ activeStepDetails.desc }}</p>
                </div>
              </div>

            <template v-if="activeStep === 'starter'">
            <section class="config-section">
              <h3>确认项目与注册凭据</h3>
              <dl class="credential-options">
                <div>
                  <dt>首次接入</dt>
                  <dd>从「项目管理 → 接入项目 → SDK 接入」生成一次性接入令牌，按弹窗说明安装并启动。首次注册后，项目出现在列表中，后端从受保护的凭据存储继续启动。</dd>
                </div>
                <div>
                  <dt>已有项目</dt>
                  <dd>沿用原有凭据。需要配置静态 App Key 和 App Secret 时，从项目详情的「编辑」设置，并将 Secret 通过环境变量或密钥管理器交给业务后端。</dd>
                </div>
              </dl>
              <el-link :href="integrationGuideUrl" type="primary" target="_blank" rel="noopener noreferrer">查看系统接入文档</el-link>
            </section>
            <section class="config-section">
              <h3>安装当前技术栈的 SDK</h3>
              <p v-if="loading || selectedSdkLoading" role="status">正在读取 SDK 制品信息…</p>
              <LiquidGlassNotice v-else-if="!selectedSdkManifest" type="warning" source="backdrop"
                title="无法读取当前项目的 SDK 安装信息">
                <template #actions><LiquidGlassButton size="small" @click="loadAll">重新加载</LiquidGlassButton></template>
              </LiquidGlassNotice>
              <SdkArtifactInstall v-else :language="sdkLanguage" :artifacts="selectedSdkManifest.sdkArtifacts || []"
                :package-url="selectedSdkManifest.endpoints.skillPackageUrl" />
            </section>
            <template v-if="sdkLanguage === 'csharp'">
            <section class="config-section">
              <h3>配置业务服务</h3>
              <p>把以下配置加入业务项目的 appsettings.json。Enrollment Token 或已有项目凭据通过受保护的环境变量或密钥管理器提供，文件中不要保存密钥。</p>
              <CodeSnippetBlock variant="guide" title="appsettings.json" :code="csharpApplicationSnippet" @copy="copyText" />
              <p>使用一次性令牌时，须将 <code>CredentialFilePath</code> 替换为应用包外受保护的持久文件路径，也可通过 <code>ReachAI__CredentialFilePath</code> 提供。持久保存 ASP.NET Core Data Protection 密钥，并为运行账号限制文件和目录权限。</p>
              <p>令牌使用 <code>ReachAI__EnrollmentToken</code>，已有项目凭据使用 <code>ReachAI__AppKey</code> 和 <code>ReachAI__AppSecret</code>；注册地址必须从 ReachAI 服务端可达。</p>
            </section>
            <section class="config-section">
              <h3>注册能力与身份桥</h3>
              <p>替换示例类型并实现从已登录业务会话解析员工，以及将受验证身份带入业务授权的适配器。业务系统继续执行数据权限与事务校验。</p>
              <CodeSnippetBlock variant="guide" title="Program.cs" :code="csharpStartupSnippet" @copy="copyText" />
            </section>
            </template>
            <template v-else-if="sdkLanguage === 'go'">
            <section class="config-section">
              <h3>配置业务服务和身份桥</h3>
              <p>项目凭据由后端安全保存，业务身份从可信登录态还原；业务系统继续校验角色、租户和数据权限。具体 API 和示例见源码包内 README。</p>
              <p>以下是初始化片段，请接入已有业务服务：监听后调用 sdk.Start(ctx)，退出时调用 sdk.Stop(ctx)，并实现身份桥与业务处理函数。</p>
              <CodeSnippetBlock variant="guide" title="Go net/http 接入示例" :code="goStartupSnippet" @copy="copyText" />
            </section>
            </template>
            <template v-else-if="sdkLanguage === 'python'">
            <section class="config-section">
              <h3>配置业务服务和身份桥</h3>
              <p>将业务登录态与项目凭据交由服务端处理；注册能力时保留业务系统自己的权限、租户与事务校验。不要将项目密钥放入浏览器或代码仓库。</p>
              <CodeSnippetBlock variant="guide" title="FastAPI 接入示例" :code="pythonStartupSnippet" @copy="copyText" />
            </section>
            </template>
            <template v-else-if="sdkLanguage === 'node'">
            <section class="config-section">
              <h3>配置业务服务和身份桥</h3>
              <p>从业务服务的可信登录态取得用户，并在服务端校验权限、租户和数据范围。不要将项目密钥放入浏览器或代码仓库。</p>
              <CodeSnippetBlock variant="guide" title="Express 接入示例" :code="nodeStartupSnippet" @copy="copyText" />
            </section>
            </template>
            <template v-else>
            <section class="config-section">
              <h3>配置文件（application.yml）</h3>
              <p>已经通过接入令牌注册的项目继续使用受保护的凭据存储。使用静态凭据的项目可用以下配置，并按需用隐藏输入脚本设置当前 Windows 用户环境变量。</p>
              <CodeSnippetBlock
                variant="guide"
                title="application.yml"
                :code="starterApplicationSnippet"
                :highlighted-code="highlightedStarterApplicationSnippet"
                @copy="copyText"
              />
              <CodeSnippetBlock
                v-if="registrySecretSetupSnippet"
                variant="guide"
                title="PowerShell · 隐藏输入 Secret"
                :code="registrySecretSetupSnippet"
                @copy="copyText"
              />
            </section>

            </template>
            <el-checkbox v-model="manualChecks.starter">我已完成 SDK 引入与配置</el-checkbox>
            </template>

            <template v-else-if="activeStep === 'capabilities'">
            <section class="config-section">
              <h3>从一个只读方法开始</h3>
              <template v-if="sdkLanguage === 'java'">
                <p>在业务 Spring Bean 的公开方法上加 <code>@ReachCapability</code>，使用唯一且稳定的名称，用 <code>@ReachParam</code> 描述参数。<code>@ReachOutput</code> 可用于说明返回 DTO 字段。</p>
                <CodeSnippetBlock variant="guide" title="Java · 在已有业务方法上声明能力" :code="javaCapabilitySnippet" @copy="copyText" />
                <p>将示例注解加到已有的、执行授权检查的真实业务方法上。使用 <code>ANNOTATED_ONLY</code> 并按需设置 <code>scan-packages</code> 限定业务包。</p>
              </template>
              <p v-else>沿用上一步的 SDK 初始化代码。C# 用 Attribute，Python 用装饰器，Node.js 和 Go 用 SDK 注册方法。用实际业务处理器替换示例，声明准确的入参、出参和副作用。</p>
              <p>依赖当前用户的方法应声明业务身份必需。写方法要声明真实副作用，后续按项目的审批策略验收。仅接入页面对话时，按原有能力范围核对。</p>
              <el-link :href="sdkLanguage === 'java' ? integrationGuideUrl + '#java-系统' : sdkGuideUrl" type="primary" target="_blank" rel="noopener noreferrer">查看当前技术栈的业务方法示例</el-link>
            </section>
            <el-checkbox v-model="manualChecks.capabilities">我已核对本次开放范围并完成所需的能力声明</el-checkbox>
            </template>

            <template v-else-if="activeStep === 'identity'">
            <section class="config-section">
              <h3>让业务方法以正确身份执行</h3>
              <p v-if="sdkLanguage === 'java'">在业务后端注册 <code>ReachAiSecurityContextBridge</code> Bean。SDK 验证调用后，由身份桥将用户、租户和角色绑定到原有业务上下文，执行现有权限与数据范围校验，调用结束后恢复上下文。</p>
              <p v-else>接入所选 SDK 的业务身份桥，用 SDK 已验证的调用身份还原用户、租户与权限上下文。让现有业务方法继续执行授权与数据范围校验。</p>
              <el-link :href="sdkGuideUrl" type="primary" target="_blank" rel="noopener noreferrer">查看业务调用身份桥</el-link>
            </section>
            <section class="config-section">
              <h3>为已登录用户签发对话令牌</h3>
              <p v-if="sdkLanguage === 'java'">需要页面对话时，注册 <code>ReachAiEmbedPrincipalResolver</code> Bean，从已验证的业务登录态解析用户、租户与角色。Starter 会提供 <code>POST /reachai/embed/token</code>，用于服务端换取短期 Embed Token。</p>
              <p v-else>需要页面对话时，实现所选 SDK 的登录用户解析器，从已验证的登录态解析用户、租户与角色，并挂载 SDK 的 <code>POST /reachai/embed/token</code>。</p>
              <p>在业务后端建立可信身份，浏览器仅传页面上下文。需要退出即失效时，绑定业务会话并在退出时由后端通知 ReachAI 撤销。</p>
              <el-link :href="embedGuideUrl" type="primary" target="_blank" rel="noopener noreferrer">查看登录态、令牌与退出处理</el-link>
            </section>
            <el-checkbox v-model="manualChecks.identity">我已接通本次接入所需的业务身份</el-checkbox>
            </template>

            <template v-else-if="activeStep === 'gateway'">
            <section class="config-section">
              <h3>ReachAI 服务端调用业务 SDK</h3>
              <p>让 ReachAI 服务端可访问业务后端地址与 Context Path。将 <code>/reachai/registry/**</code> 和 <code>/reachai/capabilities/**</code> 转发到业务服务，保留签名与调用令牌，使用 SDK 的验签链。将签名回调与业务用户的登录、JWT 和 CSRF 校验分开。</p>
            </section>
            <section class="config-section">
              <h3>浏览器获取令牌并发起对话</h3>
              <p><code>POST /reachai/embed/token</code> 走业务登录与 CSRF 防护。若经过业务网关代理对话，将 <code>/api/reachai/embed/**</code> 转发到平台 <code>/api/embed/**</code>，跳过业务 JWT 解析并原样透传 Embed Token 的 Authorization 头。</p>
              <el-form label-position="top" class="inline-form" novalidate>
                <el-form-item label="对话代理地址">
                  <el-input v-model="gatewayBaseUrl" placeholder="例如 https://erp.example.com" />
                </el-form-item>
              </el-form>
              <p>此地址用于生成页面示例。留空时浏览器直接请求 ReachAI，对话令牌仍从业务系统获取。</p>
              <CodeSnippetBlock
                v-if="sdkLanguage === 'java'"
                variant="guide"
                title="Spring Cloud Gateway · 路由示例"
                :code="gatewaySnippet"
                :highlighted-code="highlightedGatewaySnippet"
                @copy="copyText"
              />
              <p v-if="sdkLanguage === 'java'">将示例合入现有路由，替换实际业务上游。使用网关前缀或 Context Path 时，核对实际转发路径。安全链按本项目使用的认证框架配置。</p>
            </section>
            <el-checkbox v-model="manualChecks.gateway">我已配置本次接入所需的路径和鉴权规则</el-checkbox>
            </template>

            <template v-else-if="activeStep === 'backend-check'">
            <section class="config-section">
              <h3>1. 构建并启动业务服务</h3>
              <p>使用注入了注册凭据的业务环境启动服务。点击下方「刷新平台自检」，在当前语言下看到在线实例后，继续同步。</p>
              <div class="check-list">
                <div v-for="item in backendChecks" :key="item.label" class="check-row" :class="item.status">
                  <el-icon><component :is="item.icon" /></el-icon>
                  <span>
                    <strong>{{ item.label }}</strong>
                    <small>{{ item.desc }}</small>
                  </span>
                </div>
              </div>
            </section>
            <section class="config-section">
              <h3>2. 同步并核对能力</h3>
              <p>打开本项目的「接入与变更」，选择「同步与发现 → 扫描并同步接口」。确认同步目标、响应和实例，在能力目录核对本次开放的方法及输入输出，显式接纳需要生效的契约变化。</p>
              <el-link v-if="capabilitySyncHref" :href="capabilitySyncHref" type="primary" target="_blank" rel="noopener noreferrer">打开接入与变更</el-link>
            </section>
            <section class="config-section">
              <h3>3. 查看平台证据</h3>
              <p>自检读取项目的注册凭据、心跳与签名回调证据。项目存在多种 SDK 时，请在同步结果里核对实际实例和能力。</p>
              <LiquidGlassButton type="primary" :loading="checking" @click="runCheck">刷新平台自检</LiquidGlassButton>
            </section>
            <div v-if="manualSdkCheck" class="check-result" aria-live="polite">
              <div class="result-head" :class="manualSdkCheck.status.toLowerCase()">
                <strong>项目 SDK 自检 · {{ statusLabel(manualSdkCheck.status) }}</strong>
                <span>{{ projectCode }}</span>
              </div>
              <div class="result-list">
                <div v-for="item in manualSdkCheck.checks" :key="item.key" class="result-row" :class="item.status.toLowerCase()">
                  <span class="result-status">{{ statusLabel(item.status) }}</span>
                  <span>
                    <strong>{{ item.label }}</strong>
                    <small>{{ item.message }}</small>
                    <em v-if="item.evidence">{{ item.evidence }}</em>
                  </span>
                </div>
              </div>
            </div>
            <p v-if="!canConfirmSync">项目自检通过且所选语言实例在线后，可确认本次同步。</p>
            <el-checkbox v-model="manualChecks.sync" :disabled="!canConfirmSync">我已核对本次同步的能力和契约变化</el-checkbox>
            </template>

            <template v-else-if="activeStep === 'frontend'">
            <section class="config-section">
              <h3>1. 准备 Agent 与来源授权</h3>
              <p>在 Agent 管理发布用于嵌入的 Agent，关联本项目并配置业务页面的来源域名、协议与端口。将实际使用的 Agent 标识填入示例。仅接入后端能力时，可继续到「真实业务验收」。</p>
            </section>
            <section class="config-section">
              <h3>2. 安装 Embed Chat SDK</h3>
              <template v-if="onboardingPackageUrl && embedSdkInstallSnippet">
                <el-link :href="onboardingPackageUrl" :icon="Download" type="primary" download>下载接入工具包</el-link>
                <p>解压后，在业务前端的 package.json 所在目录运行安装命令，将 <code>{skillExtractDir}</code> 替换为解压目录。</p>
                <CodeSnippetBlock variant="guide" title="Node.js · 安装 Embed Chat SDK" :code="embedSdkInstallSnippet" @copy="copyText" />
              </template>
              <p v-else>平台暂未提供 Embed Chat SDK 安装信息，请联系管理员核对分发制品后重新打开工作台。</p>
            </section>
            <section class="config-section">
              <h3>3. 在登录后的页面挂载对话</h3>
              <p>在宿主页面添加 <code>&lt;div id="reachai-chat"&gt;&lt;/div&gt;</code>，填写实际页面标识和名称。组件卸载或退出登录时销毁对话。业务系统使用自定义 Broker 时，按实际路径、认证和请求格式适配。</p>
              <el-form label-position="top" class="inline-form" novalidate>
                <el-form-item label="Token Broker">
                  <el-input v-model="embedTokenPath" placeholder="/reachai/embed/token" />
                </el-form-item>
              </el-form>
              <CodeSnippetBlock
                variant="guide"
                title="业务页面 · Embed Chat"
                :code="frontendSnippet"
                :highlighted-code="highlightedFrontendSnippet"
                @copy="copyText"
              />
              <el-link :href="embedGuideUrl" type="primary" target="_blank" rel="noopener noreferrer">查看挂载示例、页面动作与配置说明</el-link>
            </section>
            <el-checkbox v-model="manualChecks.frontend">我已在登录后的业务页面挂载对话</el-checkbox>
            </template>

            <template v-else-if="activeStep === 'acceptance'">
            <section class="config-section">
              <h3>以真实身份验证权限和业务结果</h3>
              <p>开放业务方法时，将已同步并接纳的方法编排成 Workflow，发布后挂载到目标 Agent 或开放给员工 AI 工具。用有权限的业务测试账号调用，在 Trace 核对执行的方法与结果，并到原业务系统回读。再用无权限账号验证拒绝。接入写方法时，用测试数据验证确认、取消和实际写入结果。</p>
              <el-link :href="workflowGuideUrl" type="primary" target="_blank" rel="noopener noreferrer">查看业务方法的 Workflow 接入</el-link>
            </section>
            <section class="config-section">
              <h3>接入页面对话时，验证完整登录生命周期</h3>
              <p>登录后确认入口可见、可以收发消息，再用两个账号核对数据和会话隔离。退出时确认对话已销毁，启用了会话绑定的令牌在后端撤销后无法继续使用。接入页面动作时，在真实页面执行并核对界面结果。</p>
              <el-link v-if="businessPageUrl" :href="businessPageUrl" type="primary" target="_blank" rel="noopener noreferrer">打开业务系统</el-link>
              <el-link v-else :href="embedGuideUrl" type="primary" target="_blank" rel="noopener noreferrer">查看页面验收场景</el-link>
            </section>
            <el-checkbox v-model="manualChecks.acceptance">我已在真实业务系统中验证本次接入范围</el-checkbox>
            </template>
            </div>
        </section>

        <footer class="wizard-footer">
          <span class="footer-actions">
            <el-button :disabled="activeStepIndex === 0" @click="goPrev">上一步</el-button>
            <el-button type="primary" :disabled="activeStepIndex === steps.length - 1" @click="goNext">下一步</el-button>
          </span>
          </footer>
        </section>
      </section>

      <section v-show="accessMode === 'ai-coding'" class="ai-coding-access-pane">
        <section class="ai-task-overview" :class="`is-${taskGuidance.tone}`">
          <div class="ai-task-overview__indicator" aria-hidden="true"><span /></div>
          <div class="ai-task-overview__content">
            <div class="ai-task-overview__eyebrow">当前接入状态</div>
            <h2>{{ taskGuidance.title }}</h2>
            <p>{{ taskGuidance.description }}</p>
            <div class="ai-task-overview__meta">
              <span v-if="openTaskQuestions.length">{{ openTaskQuestions.length }} 个问题待回答</span>
              <span v-if="onboardingTask">{{ aiTaskStateLabel }}</span>
              <span v-if="taskLastUpdatedAt">任务读取于 {{ formatTaskTime(taskLastUpdatedAt) }}</span>
              <span v-else>任务状态尚未读取</span>
            </div>
          </div>
          <div class="ai-task-overview__actions">
            <AiHandoffButton
              v-if="taskGuidance.action === 'start'"
              scenario="project-workbench-onboarding"
              type="primary"
              :context="{ projectCode: project?.projectCode || projectCode }"
            />
            <LiquidGlassButton
              v-else-if="taskGuidance.action !== 'none'"
              type="primary"
              @click="openTaskGuidance"
            >{{ taskActionLabel }}</LiquidGlassButton>
            <AiHandoffButton
              v-if="onboardingTask?.executionStatus === 'FAILED'"
              scenario="project-workbench-onboarding"
              :context="{ projectCode: project?.projectCode || projectCode }"
            />
            <el-button v-if="onboardingTask && taskGuidance.action !== 'detail'" text @click="openOnboardingTaskDetail">任务详情</el-button>
          </div>
        </section>
        <aside class="ai-coding-side step-progress ai-progress-panel access-progress--refined" aria-label="AI Coding 接入进度">
          <header class="ai-report-heading">
            <span class="ai-report-heading__icon" aria-hidden="true"><el-icon><Document /></el-icon></span>
            <span class="ai-report-heading__copy"><small>AI 任务</small><strong>接入报告</strong></span>
            <span class="ai-report-heading__count"><strong>{{ aiReportAvailable ? aiAccessCompletedSteps : '—' }}</strong><small> / {{ aiAccessTotalSteps }} 已通过</small></span>
          </header>
          <p class="ai-report-note">{{ aiReportAvailable ? '以下状态来自 AI 已提交的接入报告。' : 'AI 提交接入报告后，这里会展示各项结果。' }}</p>
          <div class="ai-progress-steps" role="list" aria-label="AI 接入步骤状态">
            <div
              v-for="(item, index) in aiDisplaySteps"
              :key="item.stepKey"
              class="progress-step ai-progress-step"
              :class="{
                active: item.status === 'RUNNING',
                done: item.status === 'PASS',
                warn: item.status === 'WARN',
                fail: item.status === 'FAIL',
                skipped: item.status === 'SKIPPED',
              }"
              role="listitem"
            >
              <span class="step-index">
                <el-icon v-if="item.status === 'PASS'"><Check /></el-icon>
                <span v-else class="step-dot" />
              </span>
              <span class="step-copy">
                <span class="step-title-line">
                  <span class="step-number">{{ index + 1 }}</span>
                  <strong>{{ item.title }}</strong>
                </span>
                <small>{{ aiReportAvailable ? accessStatusLabel(item.status) : '待报告' }}</small>
              </span>
            </div>
          </div>
          <div class="ai-recent-activity">
            <div class="ai-recent-activity__head"><span class="ai-recent-activity__dot" aria-hidden="true" /><strong>最近任务动态</strong></div>
            <template v-if="latestTaskEvent">
              <p>{{ latestTaskEvent.eventType === 'HANDOFF_ISSUED' ? '任务连接已准备' : latestTaskEvent.message }}</p>
              <time>{{ formatTaskTime(latestTaskEvent.createdAt) }}</time>
            </template>
            <p v-else>任务开始后，最新进展会显示在这里。</p>
          </div>
        </aside>

        <section class="ai-coding-main">
          <span class="panel-glass-highlight" aria-hidden="true" />
          <LiquidGlassNotice
            v-if="aiTaskLoadError"
            class="page-alert"
            type="error"
            source="backdrop"
            :title="aiTaskLoadError"
          >
            <template #actions>
              <LiquidGlassButton size="small" :loading="taskLoading" @click="retryOnboardingTaskLoad">
                重新加载任务状态
              </LiquidGlassButton>
            </template>
          </LiquidGlassNotice>
          <div v-if="taskRefreshError" class="ai-refresh-warning" role="status">
            <span>{{ taskRefreshError }}</span>
            <el-button text size="small" :loading="taskLoading" @click="retryTaskRefresh">重试</el-button>
          </div>

          <section class="ai-coding-grid ai-coding-grid--single">
            <div class="ai-coding-card">
              <span class="card-glass-highlight" aria-hidden="true" />
              <div class="ai-coding-card-inner">
                <div class="ai-coding-card-head">
                  <div>
                    <span class="ai-section-eyebrow">REACHAI 观测</span>
                    <h3>平台接入检查</h3>
                    <p>项目配置、业务实例与授权对话的验证结果。</p>
                  </div>
                  <LiquidGlassButton size="small" :icon="Refresh" :loading="checking" @click="runCheck">重新检查</LiquidGlassButton>
                </div>
                <div
                  v-if="selfCheckSource !== 'NONE'"
                  class="ai-self-check-summary"
                  :class="{ pass: selfCheckVerified }"
                >
                  <strong>
                    {{
                      selfCheckVerified ? '平台检查已通过' : '平台检查有待处理项'
                    }}
                  </strong>
                  <span>
                    {{ selfCheckSource === 'TASK' ? '项目事实与当前任务的授权会话分别判断' : '结果来自最近一次手动检查' }}
                  </span>
                </div>
                <div v-if="readinessConcern" class="ai-check-attention" role="status">
                  <span class="ai-check-attention__mark" aria-hidden="true">!</span>
                  <span><strong>需要关注 · {{ readinessConcern.label }}</strong><small>{{ readinessConcern.message }}</small></span>
                </div>
                <div v-if="selfCheckReadiness.length" class="readiness-list ai-readiness-list">
                  <div
                    v-for="item in selfCheckReadiness"
                    :key="item.key"
                    class="readiness-row"
                    :class="item.status.toLowerCase()"
                  >
                    <div class="readiness-row__head"><strong>{{ item.label }}</strong><span>{{ statusLabel(item.status) }}</span></div>
                    <small>{{ item.message }}</small>
                    <div class="readiness-row__source">{{ readinessSource(item) }}</div>
                  </div>
                </div>
                <div v-else class="ai-self-check-empty">
                  <span class="ai-self-check-glow" aria-hidden="true" />
                  <img class="ai-self-check-shield" src="/sdk-access-self-check-shield.png" alt="" />
                  <strong>尚无平台验证结果</strong>
                  <p>完成 AI 接入后，任务验证结果会自动显示在这里；也可以发起一次手动自检。</p>
                </div>
                <div v-if="checkResult?.checks?.length" class="result-list ai-result-list">
                  <div v-for="item in checkResult.checks" :key="item.key" class="result-row" :class="item.status.toLowerCase()">
                    <span class="result-status">{{ statusLabel(item.status) }}</span>
                    <span>
                      <strong>{{ item.label }}</strong>
                      <small>{{ item.message }}</small>
                      <em v-if="item.evidence">{{ item.evidence }}</em>
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </section>
        </section>
      </section>
    </main>

    <AppDrawer
      v-model="taskDrawerVisible"
      title="AI Coding 任务详情"
      size="720px"
      class="ai-onboarding-task-drawer"
      @opened="focusTaskAction"
    >
      <template #header="{ titleId, titleClass }">
        <div class="ai-task-drawer-header">
          <h4 :id="titleId" :class="titleClass">AI Coding 任务详情</h4>
          <el-button
            :icon="Refresh"
            :loading="taskActionBusy"
            :disabled="!onboardingTaskDetail?.task?.taskId"
            @click="handleOnboardingTaskRefresh"
          >
            刷新
          </el-button>
        </div>
      </template>
      <AiCodingTaskDetailPanel
        :detail="onboardingTaskDetail"
        :loading="taskActionBusy"
        :busy="taskActionBusy"
        :handoff-enabled="false"
        @refresh="handleOnboardingTaskRefresh"
        @answer="handleOnboardingTaskAnswer"
        @verify="handleOnboardingTaskVerification"
        @acceptance="handleOnboardingTaskAcceptance"
        @cancel="handleOnboardingTaskCancel"
      />
    </AppDrawer>
  </div>
</template>

<script setup lang="ts">
import AiHandoffButton from '@/components/ai-access/AiHandoffButton.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import AiCodingTaskDetailPanel from '@/components/ai-coding/AiCodingTaskDetailPanel.vue'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
  Check,
  Document,
  Download,
  MagicStick,
  Pointer,
  Refresh,
} from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'
import { normalizeBusinessPageUrl } from '@/utils/businessPageUrl'
import CodeSnippetBlock from '@/components/common/CodeSnippetBlock.vue'
import { LiquidGlassButton, LiquidGlassNotice, LiquidGlassSelect } from '@/components/common/liquid-glass'
import HeaderModeSwitch, { type HeaderModeOption } from '@/components/common/HeaderModeSwitch.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import ProjectRouteMissingState from '@/views/registry/components/ProjectRouteMissingState.vue'
import ProjectWorkbenchLoadErrorState from '@/views/registry/components/ProjectWorkbenchLoadErrorState.vue'
import SdkArtifactInstall from '@/views/registry/components/SdkArtifactInstall.vue'
import { projectCapabilityWorkspaceLocation } from '@/views/capability/businessCapabilityRoutes'
import { useSdkAccessWizardActions } from '@/views/registry/composables/useSdkAccessWizardActions'
import { useSdkAccessWizardData } from '@/views/registry/composables/useSdkAccessWizardData'
import { useSdkAccessWizardTaskRefresh } from '@/views/registry/composables/useSdkAccessWizardTaskRefresh'
import { useSdkAccessWizardNavigation } from '@/views/registry/composables/useSdkAccessWizardNavigation'
import { instanceMatchesSdkLanguage, useSdkAccessWizardProgress } from '@/views/registry/composables/useSdkAccessWizardProgress'
import { useSdkAccessWizardSnippets } from '@/views/registry/composables/useSdkAccessWizardSnippets'
import { useSdkAccessWizardCsharpSnippets } from '@/views/registry/composables/useSdkAccessWizardCsharpSnippets'
import { useSdkAccessWizardPythonSnippets } from '@/views/registry/composables/useSdkAccessWizardPythonSnippets'
import { useSdkAccessWizardNodeSnippets } from '@/views/registry/composables/useSdkAccessWizardNodeSnippets'
import { useSdkAccessWizardGoSnippets } from '@/views/registry/composables/useSdkAccessWizardGoSnippets'
import { useSdkAccessWizardLanguage } from '@/views/registry/composables/useSdkAccessWizardLanguage'
import { sdkAccessLanguageOptions, useSdkAccessWizardUiState } from '@/views/registry/composables/useSdkAccessWizardUiState'
import {
  aiAccessStepStatusLabel,
  embedGuideUrl,
  integrationGuideUrl,
  sdkAccessCheckStatusLabel,
  sdkGuideUrls,
  workflowGuideUrl,
} from '@/views/registry/sdkAccessWizardViewModel'
import {
  aiCodingConnectionStatusLabel,
  aiCodingExecutionStatusLabel,
} from '@/utils/aiCodingPresentation'

const { theme } = useTheme()
const router = useRouter()
const javaCapabilitySnippet = `@ReachCapability(
    name = "order.query", title = "查询订单",
    description = "查询当前用户有权查看的订单",
    sideEffect = ReachSideEffectLevel.READ,
    businessIdentityRequired = true)
public OrderView query(@ReachParam(name = "orderId", required = true) String orderId) {
    return orderService.findVisibleOrder(orderId);
}`

const accessModeOptions: HeaderModeOption[] = [
  { value: 'manual', label: '手动接入', icon: Pointer },
  { value: 'ai-coding', label: 'AI Coding 接入', icon: MagicStick },
]

const {
  accessMode,
  sdkLanguage,
  activeStep,
  gatewayBaseUrl,
  embedTokenPath,
  manualChecks,
  resetManualChecks,
} = useSdkAccessWizardUiState()

const {
  projectCode,
  project,
  instances,
  loading,
  projectMissing,
  loadError,
  aiTaskLoadError,
  checking,
  aiOnboardingManifest,
  onboardingTask,
  onboardingTaskDetail,
  detectedSdkLanguage,
  effectiveSdkLanguage,
  selfCheckReadiness,
  selfCheckSource,
  selfCheckVerified,
  aiDisplaySteps,
  aiReportAvailable,
  aiAccessCompletedSteps,
  aiAccessTotalSteps,
  latestTaskEvent,
  openTaskQuestions,
  taskGuidance,
  taskLastUpdatedAt,
  taskRefreshError,
  taskLoading,
  checkResult,
  isSdkBackedProject,
  loadAll,
  runCheck,
  refreshOnboardingTask,
  refreshOnboardingTaskInBackground,
  retryOnboardingTaskLoad,
  retryTaskRefresh,
  answerOnboardingQuestion,
  verifyOnboardingAcceptanceReadiness,
  finishOnboardingAcceptance,
  cancelOnboardingTask,
} = useSdkAccessWizardData({
  sdkLanguage,
  accessMode,
})

const detectedSdkLanguageLabel = computed(() => sdkAccessLanguageOptions.find(
  (option) => option.value === detectedSdkLanguage.value,
)?.label || '')

const { csharpManifest, csharpLoading, pythonManifest, pythonLoading,
  nodeManifest, nodeLoading, goManifest, goLoading } =
  useSdkAccessWizardLanguage(sdkLanguage, project)
const selectedSdkManifest = computed(() => ({
  java: aiOnboardingManifest.value,
  csharp: csharpManifest.value,
  python: pythonManifest.value,
  node: nodeManifest.value,
  go: goManifest.value,
}[sdkLanguage.value] ?? aiOnboardingManifest.value))
const selectedSdkLoading = computed(() => ({
  java: false,
  csharp: csharpLoading.value,
  python: pythonLoading.value,
  node: nodeLoading.value,
  go: goLoading.value,
}[sdkLanguage.value]))

watch([sdkLanguage, projectCode], resetManualChecks)
watch([gatewayBaseUrl, embedTokenPath], () => {
  manualChecks.gateway = false
  manualChecks.frontend = false
  manualChecks.acceptance = false
})

const sdkGuideUrl = computed(() => sdkGuideUrls[sdkLanguage.value])
const businessPageUrl = computed(() => normalizeBusinessPageUrl(project.value?.businessPageUrl))
const onboardingPackageUrl = computed(() => aiOnboardingManifest.value?.endpoints.skillPackageUrl)
const embedSdkInstallSnippet = computed(() => aiOnboardingManifest.value?.sdkArtifacts?.find(
  (artifact) => artifact.packageName === '@reachai/embed-chat',
)?.installCommandTemplate)
const capabilitySyncHref = computed(() => project.value?.id && isSdkBackedProject.value
  ? router.resolve(projectCapabilityWorkspaceLocation(project.value)).href
  : '')

const registrySecretSetupSnippet = computed(
  () => aiOnboardingManifest.value?.security
    ?.secretSetupCommandTemplate || '',
)

const languageInstances = computed(() => instances.value.filter((item) => instanceMatchesSdkLanguage(item, effectiveSdkLanguage.value)))
const onlineInstanceCount = computed(() => languageInstances.value.filter((item) => item.status === 'ONLINE').length)
const {
  steps,
  activeStepIndex,
  completedStepCount,
  completedPercent,
  backendChecks,
  manualSdkCheck,
  canConfirmSync,
} = useSdkAccessWizardProgress({
  activeStep,
  project,
  instances: languageInstances,
  onboardingTask,
  checkResult,
  isSdkBackedProject,
  onlineInstanceCount,
  embedTokenPath,
  manualChecks,
})
const activeStepDetails = computed(() => steps.value[activeStepIndex.value] || steps.value[0])

const {
  starterApplicationSnippet,
  highlightedStarterApplicationSnippet,
  gatewaySnippet,
  highlightedGatewaySnippet,
  frontendSnippet,
  highlightedFrontendSnippet,
} = useSdkAccessWizardSnippets({
  projectCode,
  project,
  aiOnboardingManifest,
  gatewayBaseUrl,
  embedTokenPath,
})

const {
  application: csharpApplicationSnippet,
  startup: csharpStartupSnippet,
} = useSdkAccessWizardCsharpSnippets(project, csharpManifest)
const { startup: pythonStartupSnippet } = useSdkAccessWizardPythonSnippets(project, pythonManifest)
const { startup: nodeStartupSnippet } = useSdkAccessWizardNodeSnippets(project, nodeManifest)
const { startup: goStartupSnippet } = useSdkAccessWizardGoSnippets(project, goManifest)

const {
  goPrev,
  goNext,
} = useSdkAccessWizardNavigation({
  activeStep,
  activeStepIndex,
  steps,
  projectCode,
})

const { copyText } = useSdkAccessWizardActions()

const taskDrawerVisible = ref(false)
const taskActionBusy = ref(false)

useSdkAccessWizardTaskRefresh({
  accessMode,
  projectCode,
  activeProjectCode: computed(() => project.value?.projectCode),
  paused: computed(() => loading.value || checking.value || taskLoading.value
    || taskDrawerVisible.value || taskActionBusy.value),
  refresh: refreshOnboardingTaskInBackground,
})

async function runTaskAction<T>(
  action: () => Promise<T>,
  errorMessage: string,
): Promise<T | null> {
  taskActionBusy.value = true
  try {
    return await action()
  } catch (error) {
    ElMessage.error((error as Error).message || errorMessage)
    return null
  } finally {
    taskActionBusy.value = false
  }
}

async function openOnboardingTaskDetail() {
  taskDrawerVisible.value = true
  await runTaskAction(
    () => refreshOnboardingTask(),
    '加载任务详情失败',
  )
  await nextTick()
  focusTaskAction()
}

let pendingTaskAction: 'question' | 'acceptance' | 'verify' | null = null

function openTaskGuidance() {
  const action = taskGuidance.value.action
  pendingTaskAction = action === 'question' || action === 'acceptance' || action === 'verify' ? action : null
  void openOnboardingTaskDetail()
}

function focusTaskAction() {
  if (!pendingTaskAction || !taskDrawerVisible.value) return
  const drawer = document.querySelector<HTMLElement>('.ai-onboarding-task-drawer')
  const target = pendingTaskAction === 'question'
    ? drawer?.querySelector<HTMLElement>('.ai-task-questions textarea')
    : [...(drawer?.querySelectorAll<HTMLButtonElement>('.ai-task-detail > footer button') || [])]
      .find((button) => button.textContent?.includes(pendingTaskAction === 'acceptance' ? '确认验收通过' : '重新验证'))
  const section = pendingTaskAction === 'question'
    ? drawer?.querySelector<HTMLElement>('.ai-task-questions')
    : drawer?.querySelector<HTMLElement>('.ai-task-detail > footer')
  if (!target && !section) return
  ;(target || section)?.scrollIntoView({ block: 'center' })
  target?.focus({ preventScroll: true })
  pendingTaskAction = null
}

watch(taskDrawerVisible, (visible) => {
  if (!visible) pendingTaskAction = null
})

async function handleOnboardingTaskRefresh() {
  await runTaskAction(
    () => refreshOnboardingTask(),
    '刷新任务详情失败',
  )
}

async function handleOnboardingTaskAnswer(
  taskId: string,
  questionId: string,
  answer: string,
) {
  const result = await runTaskAction(
    () => answerOnboardingQuestion(taskId, questionId, answer),
    '回答写回失败',
  )
  if (result) ElMessage.success('回答已写回当前任务')
}

async function handleOnboardingTaskAcceptance(
  taskId: string,
  passed: boolean,
  message: string,
) {
  const result = await runTaskAction(
    () => finishOnboardingAcceptance(taskId, passed, message),
    '写入验收结果失败',
  )
  if (result) {
    ElMessage[passed ? 'success' : 'warning'](
      passed ? '任务验收已通过' : '任务已标记为验收不通过',
    )
  }
}

async function handleOnboardingTaskVerification(taskId: string) {
  const result = await runTaskAction(
    () => verifyOnboardingAcceptanceReadiness(taskId),
    '平台验证失败',
  )
  if (result) {
    ElMessage[result.acceptanceReady ? 'success' : 'warning'](
      result.acceptanceReady
        ? '平台验证已通过，现在可以验收'
        : `仍有 ${result.blockers.length} 项未通过平台验证`,
    )
  }
}

async function handleOnboardingTaskCancel(taskId: string) {
  const result = await runTaskAction(
    () => cancelOnboardingTask(taskId),
    '取消任务失败',
  )
  if (result) ElMessage.success('任务已取消')
}

const statusLabel = sdkAccessCheckStatusLabel
const accessStatusLabel = aiAccessStepStatusLabel

const aiTaskStateLabel = computed(() => {
  if (aiTaskLoadError.value) return '状态不可用'
  const task = onboardingTask.value
  if (!task) return '待创建'
  const connection = aiCodingConnectionStatusLabel(task.connection.status)
  const execution = aiCodingExecutionStatusLabel(task.executionStatus)
  return `${connection} · ${execution}`
})

const taskActionLabel = computed(() => ({
  detail: '查看任务',
  question: '查看并回答',
  acceptance: '查看并验收',
  verify: '查看验证',
  none: '',
  start: '',
}[taskGuidance.value.action]))

const readinessConcern = computed(() => {
  const items = selfCheckReadiness.value.filter((item) => item.status !== 'PASS')
  return items.find((item) => item.status === 'FAIL')
    || items.find((item) => item.key === 'RUNTIME_READY')
    || items[0]
})

function formatTaskTime(value: Date | string) {
  if (value instanceof Date) {
    return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(value)
  }
  const match = value.match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})/)
  return match ? `${match[1]} ${match[2]}` : value || '暂无时间记录'
}

function readinessSource(item: { evidence?: unknown }) {
  const evidence = item.evidence && typeof item.evidence === 'object'
    ? item.evidence as Record<string, unknown>
    : undefined
  const scope = evidence?.scope === 'TASK_WINDOW' ? '当前任务观测窗口'
    : evidence?.scope === 'PROJECT' ? '项目范围' : '来源未标明'
  if (typeof evidence?.observedAt !== 'string' || !evidence.observedAt) return `${scope} · 暂无时间记录`
  const timeLabel = evidence.timeKind === 'LAST_HEARTBEAT' ? '最近心跳'
    : evidence.timeKind === 'LAST_SUCCESSFUL_CALLBACK' ? '最近成功回调' : '记录时间'
  return `${scope} · ${timeLabel} ${formatTaskTime(evidence.observedAt)}`
}

watch(projectCode, () => { void loadAll() })
onMounted(loadAll)
</script>

<style scoped lang="scss">
@use './styles/SdkAccessWizard.scss';
@use './styles/SdkAccessWizard.ai-coding.figma.scss' as aiCodingFigma;
</style>
