<template>
  <div class="sdk-access-page project-workbench-page workbench-page--list" :class="theme === 'dark' ? 'is-dark-skin' : 'is-light-skin'">
    <span class="page-ambient-center" aria-hidden="true" />
    <PageHeader variant="workbench" domain="project" presentation="plain" title="项目接入工作台" description="完成 SDK 接入配置，检查注册与同步状态。">
      <template #meta>
        <span>{{ project?.name || projectCode }} · {{ project?.projectCode || projectCode }} · SDK 接入</span>
        <LiquidGlassSelect v-model="sdkLanguage" class="sdk-language-picker" aria-label="业务系统语言" dropdown-title="业务系统语言">
          <el-option label="Java / Spring" value="java" />
          <el-option label="C# / ASP.NET Core" value="csharp" />
          <el-option label="Python / FastAPI" value="python" />
          <el-option label="Node.js / Express" value="node" />
          <el-option label="Go / net/http" value="go" />
        </LiquidGlassSelect>
      </template>
      <template #mode>
        <HeaderModeSwitch
          v-if="!projectMissing && !loadError"
          v-model="accessMode"
          :options="accessModeOptions"
          aria-label="SDK 接入方式"
        />
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
              接入进度
              <strong>{{ completedStepCount }}/{{ steps.length }}</strong>
              已完成
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
            <div v-if="activeStep === 'starter'" class="step-screen">
            <div class="panel-head">
              <div>
                <span class="step-kicker">步骤 1 / 5</span>
                <h2>后端 SDK</h2>
                <p>将 SDK 引入到业务服务中，并完成基础配置。</p>
              </div>
              <el-tag type="danger" effect="light">必填</el-tag>
            </div>

            <template v-if="sdkLanguage === 'csharp'">
            <section class="config-section">
              <h3>1. 获取 C# SDK</h3>
              <p>下载平台提供的 NuGet 制品，核对 SHA-256 后放入持久的本地 feed，并将该 feed 配置到业务项目。当前支持 ASP.NET Core 10。</p>
              <p v-if="csharpLoading" role="status">正在读取 C# SDK 制品信息…</p>
              <LiquidGlassNotice v-else-if="csharpError" type="error" source="backdrop" :title="csharpError">
                <template #actions><LiquidGlassButton size="small" @click="loadCsharpManifest">重试</LiquidGlassButton></template>
              </LiquidGlassNotice>
              <template v-else-if="csharpArtifactReady && csharpArtifact">
                <p><a :href="csharpArtifact.downloadUrl || undefined" download>下载 {{ csharpArtifact.packageName }} {{ csharpArtifact.version }}</a></p>
                <CodeSnippetBlock title="SHA-256 · 安装前核对" :code="csharpArtifact.integritySha256 || ''" @copy="copyText" />
                <CodeSnippetBlock title="dotnet · 从已验证的本地 feed 安装" :code="csharpArtifact.installCommandTemplate || ''" @copy="copyText" />
                <p>命令中的项目路径和本地 feed 路径需要替换为你的实际路径；平台下载地址不是 NuGet 源。</p>
              </template>
              <LiquidGlassNotice v-else type="warning" source="backdrop" title="C# SDK 制品尚未就绪" description="平台尚未提供可验证的制品及摘要，请检查平台构建产物后刷新。" />
            </section>
            <section class="config-section">
              <h3>2. 配置业务服务</h3>
              <p>把以下配置加入业务项目的 appsettings.json。Enrollment Token 或已有项目凭据通过受保护的环境变量或密钥管理器提供，文件中不要保存密钥。</p>
              <CodeSnippetBlock title="appsettings.json" :code="csharpApplicationSnippet" @copy="copyText" />
              <p>使用一次性令牌时，须将 <code>CredentialFilePath</code> 替换为应用包外受保护的持久文件路径，也可通过 <code>ReachAI__CredentialFilePath</code> 提供。持久保存 ASP.NET Core Data Protection 密钥，并为运行账号限制文件和目录权限。</p>
              <p>令牌使用 <code>ReachAI__EnrollmentToken</code>，已有项目凭据使用 <code>ReachAI__AppKey</code> 和 <code>ReachAI__AppSecret</code>；注册地址必须从 ReachAI 服务端可达。</p>
            </section>
            <section class="config-section">
              <h3>3. 注册能力与身份桥</h3>
              <p>替换示例类型并实现从已登录业务会话解析员工，以及将受验证身份带入业务授权的适配器。业务系统继续执行数据权限与事务校验。</p>
              <CodeSnippetBlock title="Program.cs" :code="csharpStartupSnippet" @copy="copyText" />
            </section>
            </template>
            <template v-else-if="sdkLanguage === 'go'">
            <section class="config-section">
              <h3>1. 获取 Go SDK</h3>
              <p>下载平台提供的 Go 源码包并核对 SHA-256，再解压到业务项目的受控目录。首版提供标准库 net/http 适配。</p>
              <p v-if="goLoading" role="status">正在读取 Go SDK 制品信息…</p>
              <LiquidGlassNotice v-else-if="goError" type="error" source="backdrop" :title="goError">
                <template #actions><LiquidGlassButton size="small" @click="loadGoManifest">重试</LiquidGlassButton></template>
              </LiquidGlassNotice>
              <template v-else-if="goArtifactReady && goArtifact">
                <p><a :href="goArtifact.downloadUrl || undefined" download>下载 {{ goArtifact.packageName }} {{ goArtifact.version }}</a></p>
                <CodeSnippetBlock title="SHA-256 · 安装前核对" :code="goArtifact.integritySha256 || ''" @copy="copyText" />
                <CodeSnippetBlock title="Go · 使用已验证源码包" :code="goArtifact.installCommandTemplate || ''" @copy="copyText" />
                <p>先将 ZIP 中的 reachai-go 目录解压到业务项目的 third_party/ 下，再执行命令；如使用其他位置，请相应修改相对路径。平台制品下载地址不是 Go 模块代理。</p>
              </template>
              <LiquidGlassNotice v-else type="warning" source="backdrop" title="Go SDK 制品暂不可用，请联系平台管理员检查分发包。" />
            </section>
            <section class="config-section">
              <h3>2. 配置业务服务和身份桥</h3>
              <p>项目凭据由后端安全保存，业务身份从可信登录态还原；业务系统继续校验角色、租户和数据权限。具体 API 和示例见源码包内 README。</p>
              <p>以下是初始化片段，请接入已有业务服务：监听后调用 sdk.Start(ctx)，退出时调用 sdk.Stop(ctx)，并实现身份桥与业务处理函数。</p>
              <CodeSnippetBlock title="Go net/http 接入示例" :code="goStartupSnippet" @copy="copyText" />
            </section>
            </template>
            <template v-else-if="sdkLanguage === 'python'">
            <section class="config-section">
              <h3>1. 获取 Python SDK</h3>
              <p>下载平台提供的 wheel 并核对 SHA-256，再将它安装到业务项目的 Python 环境；首次支持 FastAPI。</p>
              <p v-if="pythonLoading" role="status">正在读取 Python SDK 制品信息…</p>
              <LiquidGlassNotice v-else-if="pythonError" type="error" source="backdrop" :title="pythonError">
                <template #actions><LiquidGlassButton size="small" @click="loadPythonManifest">重试</LiquidGlassButton></template>
              </LiquidGlassNotice>
              <template v-else-if="pythonArtifactReady && pythonArtifact">
                <p><a :href="pythonArtifact.downloadUrl || undefined" download>下载 {{ pythonArtifact.packageName }} {{ pythonArtifact.version }}</a></p>
                <CodeSnippetBlock title="SHA-256 · 安装前核对" :code="pythonArtifact.integritySha256 || ''" @copy="copyText" />
                <CodeSnippetBlock title="pip · 安装已验证的 wheel" :code="pythonArtifact.installCommandTemplate || ''" @copy="copyText" />
                <p>命令中的 wheel 路径需要替换为下载并验证后的实际路径；依赖须从可信的软件包来源安装。</p>
              </template>
              <LiquidGlassNotice v-else type="warning" source="backdrop" title="Python SDK 制品尚未就绪" description="平台尚未提供可验证的制品及摘要，请检查平台构建产物后刷新。" />
            </section>
            <section class="config-section">
              <h3>2. 配置业务服务和身份桥</h3>
              <p>将业务登录态与项目凭据交由服务端处理；注册能力时保留业务系统自己的权限、租户与事务校验。不要将项目密钥放入浏览器或代码仓库。</p>
              <CodeSnippetBlock title="FastAPI 接入示例" :code="pythonStartupSnippet" @copy="copyText" />
            </section>
            </template>
            <template v-else-if="sdkLanguage === 'node'">
            <section class="config-section">
              <h3>1. 获取 Node.js SDK</h3>
              <p>下载平台提供的 npm 安装包并核对 SHA-256，再安装到业务项目。首版支持 Node.js 20 及以上和 Express 4 / 5。</p>
              <p v-if="nodeLoading" role="status">正在读取 Node.js SDK 制品信息…</p>
              <LiquidGlassNotice v-else-if="nodeError" type="error" source="backdrop" :title="nodeError">
                <template #actions><LiquidGlassButton size="small" @click="loadNodeManifest">重试</LiquidGlassButton></template>
              </LiquidGlassNotice>
              <template v-else-if="nodeArtifactReady && nodeArtifact">
                <p><a :href="nodeArtifact.downloadUrl || undefined" download>下载 {{ nodeArtifact.packageName }} {{ nodeArtifact.version }}</a></p>
                <CodeSnippetBlock title="SHA-256 · 安装前核对" :code="nodeArtifact.integritySha256 || ''" @copy="copyText" />
                <CodeSnippetBlock title="npm · 安装已验证的包" :code="nodeArtifact.installCommandTemplate || ''" @copy="copyText" />
                <p>替换命令中的安装包路径；依赖须从可信的软件包来源安装。平台制品下载地址不是 npm 仓库。</p>
              </template>
              <LiquidGlassNotice v-else type="warning" source="backdrop" title="Node.js SDK 制品尚未就绪" description="平台尚未提供可验证的制品及摘要，请检查平台构建产物后刷新。" />
            </section>
            <section class="config-section">
              <h3>2. 配置业务服务和身份桥</h3>
              <p>从业务服务的可信登录态取得用户，并在服务端校验权限、租户和数据范围。不要将项目密钥放入浏览器或代码仓库。</p>
              <CodeSnippetBlock title="Express 接入示例" :code="nodeStartupSnippet" @copy="copyText" />
            </section>
            </template>
            <template v-else>
            <section class="config-section">
              <h3>1. 下载并安装 Java SDK 制品</h3>
              <p>无需 ReachAI 源码仓库。平台给出固定版本的 JAR、独立 POM 和双 SHA-256；先安装 capability-sdk，再安装 Starter。</p>
              <CodeSnippetBlock
                v-if="javaSdkInstallSnippet"
                title="PowerShell · 平台制品安装"
                :code="javaSdkInstallSnippet"
                @copy="copyText"
              />
              <LiquidGlassNotice
                v-else
                type="warning"
                source="backdrop"
                title="平台尚未返回 Java SDK 制品链接"
                description="请刷新项目接入信息；不要猜测 /maven、/repository 等下载地址。"
              />
            </section>

            <section class="config-section">
              <h3>2. 引入依赖（Maven）</h3>
              <p>制品安装完成后，将以下依赖添加到业务服务的 pom.xml。平台下载 API 不是 Maven 仓库，无需添加 repository。</p>
              <CodeSnippetBlock
                title="pom.xml"
                :code="starterDependencySnippet"
                :highlighted-code="highlightedStarterDependencySnippet"
                @copy="copyText"
              />
            </section>

            <section class="config-section">
              <h3>3. 配置文件（application.yml）</h3>
              <p>在 application.yml 中添加以下配置；项目密钥只通过环境变量注入。可用隐藏输入脚本写入当前 Windows 用户环境，随后用新终端启动业务服务。</p>
              <CodeSnippetBlock
                title="application.yml"
                :code="starterApplicationSnippet"
                :highlighted-code="highlightedStarterApplicationSnippet"
                @copy="copyText"
              />
              <CodeSnippetBlock
                v-if="registrySecretSetupSnippet"
                title="PowerShell · 隐藏输入 Secret"
                :code="registrySecretSetupSnippet"
                @copy="copyText"
              />
            </section>

            </template>
            <section class="config-section config-check">
              <h3>4. 完成后请勾选</h3>
              <el-checkbox v-model="manualChecks.starter">我已完成 SDK 引入与配置</el-checkbox>
            </section>
          </div>

          <div v-else-if="activeStep === 'gateway'" class="step-screen">
            <div class="panel-head">
              <div>
                <span class="step-kicker">步骤 2</span>
                <h2>网关路由</h2>
              </div>
            </div>
            <el-form label-width="120px" class="inline-form">
              <el-form-item label="网关入口">
                <el-input v-model="gatewayBaseUrl" placeholder="例如 http://localhost:8080" />
              </el-form-item>
            </el-form>
            <div v-if="sdkLanguage !== 'java'" class="config-section">
              <p>将 <code>/reachai/capabilities/**</code> 和 <code>/reachai/registry/**</code> 转发到业务服务，保留 SDK 验签所需的请求头。<code>/reachai/embed/token</code> 保留业务登录认证与 CSRF 防护。</p>
              <p>将 <code>/api/reachai/embed/**</code> 转发到平台的 <code>/api/embed/**</code>，完整透传 Embed Token 的 Authorization 头。按实际使用的网关实现这些规则。</p>
            </div>
            <CodeSnippetBlock
              v-else
              title="application.yml"
              :code="gatewaySnippet"
              :highlighted-code="highlightedGatewaySnippet"
              @copy="copyText"
            />
            <el-checkbox v-model="manualChecks.gateway">我已配置网关路由并确认调用头会透传</el-checkbox>
          </div>

          <div v-else-if="activeStep === 'backend-check'" class="step-screen">
            <div class="panel-head">
              <div>
                <span class="step-kicker">步骤 3</span>
                <h2>业务服务校验</h2>
              </div>
              <el-tag effect="plain">{{ onlineInstanceCount }} 在线实例</el-tag>
            </div>
            <div class="check-list">
              <div v-for="item in backendChecks" :key="item.label" class="check-row" :class="item.status">
                <el-icon><component :is="item.icon" /></el-icon>
                <span>
                  <strong>{{ item.label }}</strong>
                  <small>{{ item.desc }}</small>
                </span>
              </div>
            </div>
          </div>

          <div v-else-if="activeStep === 'frontend'" class="step-screen">
            <div class="panel-head">
              <div>
                <span class="step-kicker">步骤 4</span>
                <h2>前端 Embed Token</h2>
              </div>
            </div>
            <el-form label-width="120px" class="inline-form">
              <el-form-item label="Token Broker">
                <el-input v-model="embedTokenPath" :placeholder="sdkLanguage !== 'java' ? '/reachai/embed/token' : '/api/reachai/embed-token'" />
              </el-form-item>
            </el-form>
            <CodeSnippetBlock
              title="frontend embed token"
              :code="sdkLanguage !== 'java' ? csharpFrontendSnippet : frontendSnippet"
              :highlighted-code="sdkLanguage !== 'java' ? undefined : highlightedFrontendSnippet"
              @copy="copyText"
            />
            <el-checkbox v-model="manualChecks.frontend">我已在业务前端接入短期 embed token，不在浏览器保存项目 secret</el-checkbox>
          </div>

          <div v-else class="step-screen">
            <div class="panel-head">
              <div>
                <span class="step-kicker">步骤 5</span>
                <h2>平台接入自检</h2>
              </div>
              <el-button type="primary" :loading="checking" @click="runCheck">发起自检</el-button>
            </div>
            <LiquidGlassNotice
              title="自检范围：项目配置、实例心跳与 SDK 签名回调"
              :description="sdkLanguage === 'python' || sdkLanguage === 'node' || sdkLanguage === 'go' ? '结果按项目汇总，无法确认回调和会话来自所选语言的实例；请单独验证业务流程，平台自检步骤暂不标记为已验证。' : '结果按项目汇总，并非所选语言的独立验收；真实浏览器 Embed 会话由 AI Coding 任务的“浏览器 Embed 闭环”单独验收。'"
              type="info"
              source="backdrop"
            />

            <div v-if="checkResult" class="check-result">
              <div class="result-head" :class="checkResult.overallStatus.toLowerCase()">
                <strong>整体结果：{{ statusLabel(checkResult.overallStatus) }}</strong>
                <span>{{ checkResult.projectCode }}</span>
              </div>
              <div v-if="checkResult.readiness?.length" class="readiness-list">
                <div
                  v-for="item in checkResult.readiness"
                  :key="item.key"
                  class="readiness-row"
                  :class="item.status.toLowerCase()"
                >
                  <strong>{{ item.label }}</strong>
                  <span>{{ statusLabel(item.status) }}</span>
                  <small>{{ item.message }}</small>
                </div>
              </div>
              <div class="result-list">
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

        <footer class="wizard-footer">
          <span class="footer-actions">
            <el-button :disabled="activeStepIndex === 0" @click="goPrev">上一步</el-button>
            <el-button type="primary" :disabled="activeStepIndex === steps.length - 1" @click="goNext">下一步</el-button>
          </span>
          </footer>
        </section>
      </section>

      <section v-show="accessMode === 'ai-coding'" class="ai-coding-access-pane">
        <aside class="ai-coding-side step-progress ai-progress-panel access-progress--refined" aria-label="AI Coding 接入进度">
          <div class="access-progress">
            <span>
              AI 回传进度
              <strong class="ai-coding-progress-value">{{ aiAccessCompletedSteps }}/{{ aiAccessTotalSteps }}</strong>
              项已报告
            </span>
            <div class="access-progress-track" aria-hidden="true">
              <i :style="{ width: `${aiAccessProgressPercent}%` }" />
            </div>
          </div>
          <div class="ai-progress-meta">
            <span>
              <strong>AI 接入会话</strong>
              <small>{{ onboardingTask?.taskId || '等待创建任务' }}</small>
            </span>
            <el-tag size="small" effect="plain" :type="accessSessionTagType">
              {{ aiTaskStateLabel }}
            </el-tag>
            <el-button
              v-if="onboardingTask"
              text
              size="small"
              @click="openOnboardingTaskDetail"
            >
              任务详情
            </el-button>
          </div>
          <div class="ai-progress-steps" role="list" aria-label="AI 接入步骤状态">
            <div
              v-for="(item, index) in aiDisplaySteps"
              :key="item.stepKey"
              class="progress-step ai-progress-step"
              :class="{
                active: item.status === 'RUNNING' || (index === 0 && item.status === 'TODO'),
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
                <small>{{ accessStatusLabel(item.status) }}</small>
              </span>
            </div>
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
              <LiquidGlassButton size="small" :loading="aiOnboardingPromptLoading" @click="retryOnboardingTaskLoad">
                重新加载任务状态
              </LiquidGlassButton>
            </template>
          </LiquidGlassNotice>
          <div class="panel-head">
            <div>
              <h2>将接入任务交给AI 编程工具</h2>
              <p>生成一次性交接包并复制到所选 AI 编程工具；进度、问题和结果通过当前任务回到 ReachAI。</p>
            </div>
          </div>

          <section class="ai-coding-grid">
            <div class="ai-coding-card">
              <span class="card-glass-highlight" aria-hidden="true" />
              <div class="ai-coding-card-inner">
                <div class="ai-coding-card-head">
                  <div>
                    <h3>1. 复制给 AI 工具</h3>
                  </div>
                  <button
                    type="button"
                    class="btn-copy-prompt"
                    :disabled="aiOnboardingPromptLoading || Boolean(aiTaskLoadError)"
                    :aria-busy="aiOnboardingPromptLoading"
                    :title="aiOnboardingPromptUnavailableReason"
                    @click="
                      aiOnboardingPromptReady
                        ? copyAiOnboardingPrompt()
                        : prepareAiOnboardingTask()
                    "
                  >
                    {{
                      aiOnboardingPromptLoading
                        ? '生成中…'
                        : aiOnboardingPromptReady
                          ? '复制交接包'
                          : '生成交接包'
                    }}
                  </button>
                </div>
                <AiCodingProviderSelector
                  v-model="aiPromptTool"
                  class="sdk-ai-provider-selector"
                  aria-label="选择 AI 编程工具"
                />
                <div
                  v-if="aiOnboardingPromptReady"
                  class="compact-handoff-meta"
                  aria-live="polite"
                >
                  <span>紧凑交接包</span>
                  <strong>{{ aiOnboardingPromptCharacterSummary }}</strong>
                </div>
                <el-input
                  class="ai-prompt-input ai-prompt-preview"
                  :model-value="aiOnboardingPromptPreview"
                  type="textarea"
                  :rows="14"
                  readonly
                />
              </div>
            </div>

            <div class="ai-coding-card">
              <span class="card-glass-highlight" aria-hidden="true" />
              <div class="ai-coding-card-inner">
                <div class="ai-coding-card-head">
                  <div>
                    <h3>2. SDK 回调与平台自检</h3>
                    <p>核对项目配置、实例心跳和签名回调；AI 回传报告仍沉淀到当前任务。</p>
                  </div>
                  <el-tooltip
                    :content="checking ? '自检中…' : (checkResult ? '重新发起手动自检' : '发起手动自检')"
                    placement="top"
                  >
                    <span class="btn-self-check-wrap">
                      <button
                        type="button"
                        class="btn-self-check"
                        :disabled="checking"
                        :aria-label="checking ? '自检中…' : (checkResult ? '重新发起手动自检' : '发起手动自检')"
                        @click="runCheck"
                      >
                        <el-icon :class="{ 'is-loading': checking }"><Refresh /></el-icon>
                      </button>
                    </span>
                  </el-tooltip>
                </div>
                <div
                  v-if="selfCheckSource !== 'NONE'"
                  class="ai-self-check-summary"
                  :class="{ pass: selfCheckVerified }"
                >
                  <strong>
                    {{
                      selfCheckSource === 'TASK'
                        ? (selfCheckVerified ? '当前任务平台验证已通过' : '当前任务平台验证仍有待处理项')
                        : (selfCheckVerified ? '最近一次手动自检已通过' : '最近一次手动自检仍有待处理项')
                    }}
                  </strong>
                  <span>
                    {{ selfCheckSource === 'TASK' ? '结果来自当前 AI Coding 任务' : '结果来自最近一次手动自检' }}
                  </span>
                </div>
                <div v-if="selfCheckReadiness.length" class="readiness-list ai-readiness-list">
                  <div
                    v-for="item in selfCheckReadiness"
                    :key="item.key"
                    class="readiness-row"
                    :class="item.status.toLowerCase()"
                  >
                    <strong>{{ item.label }}</strong>
                    <span>{{ statusLabel(item.status) }}</span>
                    <small>{{ item.message }}</small>
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

    <AppDialog
      v-model="aiPromptDialogVisible"
      title="使用 AI 编程工具快速接入"
      width="880px"
      class="ai-onboarding-dialog"
      destroy-on-close
    >
      <LiquidGlassNotice
        class="ai-onboarding-alert"
        type="info"
        source="backdrop"
        title="复制交接包到所选 AI 编程工具，让 AI 在业务系统代码仓库里完成 SDK 接入。"
        description="提示词不会包含 App Secret；AI 只会被要求使用本机环境变量或密钥管理器。"
      />
      <AiCodingProviderSelector
        v-model="aiPromptTool"
        class="ai-onboarding-provider-selector"
        aria-label="选择 AI 编程工具"
        compact
      />
      <div
        v-if="aiOnboardingPromptReady"
        class="compact-handoff-meta"
        aria-live="polite"
      >
        <span>紧凑交接包</span>
        <strong>{{ aiOnboardingPromptCharacterSummary }}</strong>
      </div>
      <el-input
        class="ai-prompt-input"
        :model-value="aiOnboardingPromptPreview"
        type="textarea"
        :rows="20"
        readonly
      />
      <template #footer>
        <LiquidGlassButton @click="aiPromptDialogVisible = false">关闭</LiquidGlassButton>
        <LiquidGlassButton
          type="primary"
          :icon="DocumentCopy"
          :loading="aiOnboardingPromptLoading"
          :disabled="Boolean(aiTaskLoadError)"
          @click="
            aiOnboardingPromptReady
              ? copyAiOnboardingPrompt()
              : prepareAiOnboardingTask()
          "
        >
          {{ aiOnboardingPromptReady ? '复制交接包' : '生成交接包' }}
        </LiquidGlassButton>
      </template>
    </AppDialog>

    <AppDrawer
      v-model="taskDrawerVisible"
      title="AI Coding 任务详情"
      size="720px"
      class="ai-onboarding-task-drawer"
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
        @refresh="handleOnboardingTaskRefresh"
        @answer="handleOnboardingTaskAnswer"
        @reissue="handleOnboardingTaskReissue"
        @verify="handleOnboardingTaskVerification"
        @acceptance="handleOnboardingTaskAcceptance"
        @cancel="handleOnboardingTaskCancel"
      />
    </AppDrawer>
  </div>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import AiCodingProviderSelector from '@/components/ai-coding/AiCodingProviderSelector.vue'
import AiCodingTaskDetailPanel from '@/components/ai-coding/AiCodingTaskDetailPanel.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
  Check,
  DocumentCopy,
  MagicStick,
  Pointer,
  Refresh,
} from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'
import CodeSnippetBlock from '@/components/common/CodeSnippetBlock.vue'
import { LiquidGlassButton, LiquidGlassNotice, LiquidGlassSelect } from '@/components/common/liquid-glass'
import HeaderModeSwitch, { type HeaderModeOption } from '@/components/common/HeaderModeSwitch.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import ProjectRouteMissingState from '@/views/registry/components/ProjectRouteMissingState.vue'
import ProjectWorkbenchLoadErrorState from '@/views/registry/components/ProjectWorkbenchLoadErrorState.vue'
import { useSdkAccessWizardActions } from '@/views/registry/composables/useSdkAccessWizardActions'
import { useSdkAccessWizardData } from '@/views/registry/composables/useSdkAccessWizardData'
import { useSdkAccessWizardNavigation } from '@/views/registry/composables/useSdkAccessWizardNavigation'
import { instanceMatchesSdkLanguage, useSdkAccessWizardProgress } from '@/views/registry/composables/useSdkAccessWizardProgress'
import { useSdkAccessWizardSnippets } from '@/views/registry/composables/useSdkAccessWizardSnippets'
import { useSdkAccessWizardCsharpSnippets } from '@/views/registry/composables/useSdkAccessWizardCsharpSnippets'
import { useSdkAccessWizardPythonSnippets } from '@/views/registry/composables/useSdkAccessWizardPythonSnippets'
import { useSdkAccessWizardNodeSnippets } from '@/views/registry/composables/useSdkAccessWizardNodeSnippets'
import { useSdkAccessWizardGoSnippets } from '@/views/registry/composables/useSdkAccessWizardGoSnippets'
import { useSdkAccessWizardLanguage } from '@/views/registry/composables/useSdkAccessWizardLanguage'
import { useSdkAccessWizardUiState } from '@/views/registry/composables/useSdkAccessWizardUiState'
import {
  aiAccessStepStatusLabel,
  sdkAccessCheckStatusLabel,
} from '@/views/registry/sdkAccessWizardViewModel'
import {
  aiCodingConnectionStatusLabel,
  aiCodingExecutionStatusLabel,
} from '@/utils/aiCodingPresentation'

const { theme } = useTheme()

const accessModeOptions: HeaderModeOption[] = [
  { value: 'manual', label: '手动接入', icon: Pointer },
  { value: 'ai-coding', label: 'AI Coding 接入', icon: MagicStick },
]

const {
  aiPromptTool,
  accessMode,
  sdkLanguage,
  activeStep,
  gatewayBaseUrl,
  embedTokenPath,
  manualChecks,
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
  aiPromptDialogVisible,
  aiOnboardingManifest,
  onboardingTask,
  onboardingTaskDetail,
  handoffPackage,
  selfCheckReadiness,
  selfCheckSource,
  selfCheckVerified,
  aiDisplaySteps,
  aiAccessCompletedSteps,
  aiAccessTotalSteps,
  aiOnboardingPrompt,
  aiOnboardingPromptLoading,
  aiOnboardingPromptReady,
  aiOnboardingPromptUnavailableReason,
  checkResult,
  isSdkBackedProject,
  loadAll,
  prepareAiOnboardingTask,
  runCheck,
  refreshOnboardingTask,
  retryOnboardingTaskLoad,
  reissueOnboardingHandoff,
  answerOnboardingQuestion,
  verifyOnboardingAcceptanceReadiness,
  finishOnboardingAcceptance,
  cancelOnboardingTask,
} = useSdkAccessWizardData({
  aiPromptTool,
  sdkLanguage,
})

const { csharpManifest, csharpLoading, csharpError, csharpArtifact, csharpArtifactReady, loadCsharpManifest,
  pythonManifest, pythonLoading, pythonError, pythonArtifact, pythonArtifactReady, loadPythonManifest,
  nodeManifest, nodeLoading, nodeError, nodeArtifact, nodeArtifactReady, loadNodeManifest,
  goManifest, goLoading, goError, goArtifact, goArtifactReady, loadGoManifest } =
  useSdkAccessWizardLanguage(sdkLanguage, project)

watch(sdkLanguage, (language) => {
  manualChecks.starter = false
  manualChecks.gateway = false
  manualChecks.frontend = false
  if (language !== 'java' && embedTokenPath.value === '/api/reachai/embed-token') {
    embedTokenPath.value = '/reachai/embed/token'
  } else if (language === 'java' && embedTokenPath.value === '/reachai/embed/token') {
    embedTokenPath.value = '/api/reachai/embed-token'
  }
})

const javaSdkInstallSnippet = computed(() =>
  (aiOnboardingManifest.value?.sdkArtifacts || [])
    .filter((artifact) => artifact.language === 'java')
    .map((artifact) => artifact.installCommandTemplate || '')
    .filter(Boolean)
    .join('\n\n'),
)

const registrySecretSetupSnippet = computed(
  () => aiOnboardingManifest.value?.security
    ?.secretSetupCommandTemplate || '',
)

const languageInstances = computed(() => instances.value.filter((item) => instanceMatchesSdkLanguage(item, sdkLanguage.value)))
const onlineInstanceCount = computed(() => languageInstances.value.filter((item) => item.status === 'ONLINE').length)
const hasOtherLanguageInstances = computed(() => instances.value.length > languageInstances.value.length)

const {
  steps,
  activeStepIndex,
  completedStepCount,
  completedPercent,
  accessSessionTagType,
  backendChecks,
} = useSdkAccessWizardProgress({
  activeStep,
  project,
  instances: languageInstances,
  onboardingTask,
  checkResult,
  isSdkBackedProject,
  onlineInstanceCount,
  gatewayBaseUrl,
  embedTokenPath,
  manualChecks,
  sdkLanguage,
  hasOtherLanguageInstances,
})

const {
  starterDependencySnippet,
  starterApplicationSnippet,
  highlightedStarterDependencySnippet,
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
  frontend: csharpFrontendSnippet,
} = useSdkAccessWizardCsharpSnippets(project, csharpManifest, frontendSnippet)
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
}

async function handleOnboardingTaskRefresh() {
  await runTaskAction(
    () => refreshOnboardingTask(),
    '刷新任务详情失败',
  )
}

async function handleOnboardingTaskReissue(taskId: string) {
  const handoff = await runTaskAction(
    () => reissueOnboardingHandoff(taskId),
    '重新生成交接包失败',
  )
  if (handoff) {
    taskDrawerVisible.value = false
    aiPromptDialogVisible.value = true
  }
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

const aiOnboardingPromptPreview = computed(() =>
  aiOnboardingPromptReady.value
    ? aiOnboardingPrompt.value
    : aiOnboardingPromptUnavailableReason.value,
)

const aiOnboardingPromptCharacterSummary = computed(() => {
  const promptCharacters = handoffPackage.value?.promptCharacters
    ?? aiOnboardingPrompt.value.length
  const promptCharacterLimit = handoffPackage.value?.promptCharacterLimit
  return promptCharacterLimit
    ? `${promptCharacters} / ${promptCharacterLimit} 字符`
    : `${promptCharacters} 字符`
})

async function copyAiOnboardingPrompt() {
  if (!aiOnboardingPromptReady.value) return
  await copyText(aiOnboardingPrompt.value)
}

const statusLabel = sdkAccessCheckStatusLabel
const accessStatusLabel = aiAccessStepStatusLabel

const aiAccessProgressPercent = computed(() =>
  aiAccessTotalSteps.value
    ? Math.round((aiAccessCompletedSteps.value / aiAccessTotalSteps.value) * 100)
    : 0,
)

const aiTaskStateLabel = computed(() => {
  if (aiTaskLoadError.value) return '状态不可用'
  const task = onboardingTask.value
  if (!task) return '待创建'
  const connection = aiCodingConnectionStatusLabel(task.connection.status)
  const execution = aiCodingExecutionStatusLabel(task.executionStatus)
  return `${connection} · ${execution}`
})

onMounted(loadAll)
</script>

<style scoped lang="scss">
@use './styles/SdkAccessWizard.scss';
@use './styles/SdkAccessWizard.ai-coding.figma.scss' as aiCodingFigma;
</style>
