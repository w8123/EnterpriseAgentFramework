<template>
  <WorkbenchPage class="http-api-detail">
    <PageHeader variant="entity" domain="tool" eyebrow="HTTP API CONTRACT"
      :title="detail ? `${detail.summary.httpMethod} ${detail.summary.routeTemplate}` : 'API 详情'"
      :description="detail ? `${detail.summary.projectCode} · ${detail.summary.environment}` : '正在读取当前来源与连接状态'"
      show-back back-label="返回 API 目录" @back="backToList">
      <template #tags>
        <el-tag v-if="detail" :type="sourceTone(detail.summary.sourceStatus)" effect="light">
          {{ sourceLabel(detail.summary.sourceStatus) }}
        </el-tag>
        <el-tag v-if="connection" :type="connection.status === 'CONFIGURED' ? 'success' : 'warning'" effect="light">
          {{ connection.status === 'CONFIGURED' ? '连接已配置' : connection.status === 'BLOCKED' ? '连接受阻' : '连接未配置' }}
        </el-tag>
        <el-tag v-if="marketSource" :type="currentMarketVerified ? 'success' : 'warning'" effect="light">
          {{ currentMarketVerified ? '当前市场验证已确认' : '当前市场验证未确认' }}
        </el-tag>
      </template>
      <template #actions><el-button :icon="Refresh" :loading="loading" @click="load">刷新当前状态</el-button></template>
    </PageHeader>

    <ProjectScopeState v-if="!projectId" title="请先确认 API 所属项目"
      :message="scope?.feedbackMessage.value || '从左侧当前范围选择项目后继续。'"
      :status="scope?.status.value === 'blocked' ? 'blocked' : 'pending'"
      :can-retry="scope?.recoveryAction.value === 'retry-catalog' || scope?.recoveryAction.value === 'retry-normalization'"
      @retry="scope?.retryScope()" />
    <el-alert v-else-if="pageError" type="error" :closable="false" show-icon :title="pageError">
      <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
    </el-alert>
    <div v-else-if="loading && !detail" class="api-loading" role="status">正在读取 API 契约与连接…</div>

    <template v-else-if="detail">
      <section class="api-card api-overview" aria-labelledby="api-contract-heading">
        <div class="api-section-head">
          <div><p class="api-eyebrow">请求契约</p><h2 id="api-contract-heading">用途与输入</h2></div>
          <span class="api-identity">{{ detail.summary.httpMethod }} <code>{{ detail.summary.routeTemplate }}</code></span>
        </div>
        <p class="api-muted">来源未提供可信业务说明；请依据路径和参数核对用途，试调用仅验证本次 HTTP 返回。</p>
        <p v-if="!detail.contract && detail.acceptedContract" class="api-warning" role="status">以下展示已接纳契约；冲突来源不会覆盖它。</p>
        <p v-else-if="!displayContract" class="api-warning" role="status">当前没有可比较契约；请先查看下方来源与冲突事实，不据此判断参数或响应未声明。</p>
        <div class="api-facts">
          <div><span>所属项目</span><strong>{{ detail.summary.projectCode }}</strong></div>
          <div><span>环境</span><strong>{{ detail.summary.environment }}</strong></div>
          <div><span>认证声明</span><strong>{{ authenticationLabel }}</strong></div>
          <div><span>操作性质</span><strong>{{ displayContract?.sideEffect || '未知' }}</strong></div>
        </div>
        <h3>请求参数</h3>
        <el-table v-if="displayContract?.parameters?.length" :data="displayContract.parameters" class="api-contract-table">
          <el-table-column prop="name" label="参数" min-width="160" />
          <el-table-column prop="location" label="位置" width="100" />
          <el-table-column label="类型" width="110">
            <template #default="{ row }">{{ row.schema?.type || '未知' }}</template>
          </el-table-column>
          <el-table-column label="必填" width="80">
            <template #default="{ row }">{{ row.required ? '是' : '否' }}</template>
          </el-table-column>
        </el-table>
        <p v-else class="api-muted">{{ displayContract ? '所展示契约未声明请求参数。' : '当前请求参数无法比较。' }}</p>
        <div v-if="displayContract?.requestBody" class="api-response-contract">
          <h3>请求体 · {{ displayContract.requestBody.contentTypes.join('、') }}</h3>
          <p class="api-muted">{{ displayContract.requestBody.required ? '必须提供' : '可选' }} JSON 对象；字段与约束来自来源声明。</p>
          <pre>{{ pretty(displayContract.requestBody.schema) }}</pre>
        </div>
        <h3>响应契约</h3>
        <div v-if="displayContract?.responses?.length" class="api-response-contract">
          <div v-for="(response, index) in displayContract.responses" :key="index">
            <strong>{{ response.status }}</strong>
            <span>{{ response.contentTypes.join('、') || '未声明媒体类型' }} · {{ response.schema?.type || '未知结构' }}</span>
          </div>
        </div>
        <p v-else class="api-muted">{{ displayContract ? '所展示契约未声明响应结构。' : '当前响应结构无法比较。' }}</p>
      </section>

      <section class="api-card" aria-labelledby="api-source-heading">
        <div class="api-section-head"><div><p class="api-eyebrow">来源与接纳</p><h2 id="api-source-heading">当前契约</h2></div>
          <el-button v-if="canAccept" type="primary" :loading="accepting" @click="acceptCurrent">接纳当前契约</el-button>
        </div>
        <p v-if="detail.summary.sourceReason" class="api-warning" role="status">{{ detail.summary.sourceReason }}</p>
        <p v-else class="api-muted">{{ detail.summary.sourceStatus === 'ACCEPTED' ? '当前活动来源与已接纳契约一致。' : '来源已确认；接纳后才能配置本批受控试调用。' }}</p>
        <p v-if="!canAccept && detail.summary.sourceStatus !== 'ACCEPTED'" class="api-muted">
          {{ detail.summary.sourceStatus === 'CONFLICT' ? '先在来源修正差异并重新扫描；新试调用和新发布暂不可用。' : !detail.summary.sourceConfirmed ? marketSource ? '固定市场来源未确认，不能接纳；请返回 API 市场核对版本/Operation 并显式重新接入。' : '来源未确认，不能接纳；请重新扫描或同步。' : !canWrite ? '当前账号缺少此项目的写入权限。' : '当前契约不可接纳。' }}
        </p>
        <p v-if="equivalentSources" class="api-muted" role="status">{{ sourceComparison.active.length }} 个活动来源等价：项目与环境、HTTP 方法、完整路由和 mapping conditions 相同，规范化请求、响应、认证及副作用契约 hash 一致。只需接纳和配置此 API 一次；不会自动新增调用授权。</p>
        <div class="api-contract-changes" aria-labelledby="api-change-heading">
          <h3 id="api-change-heading">已接纳与候选变化</h3>
          <p v-if="sameExecutionHash" role="status">执行契约没有变化；候选与已接纳 hash 一致，但不等于来源已确认。</p>
          <p v-else-if="detail.summary.acceptedContractHash && detail.summary.candidateContractHash" class="api-warning" role="status">候选执行契约已变化；以下仅比较来源事实，旧版本仍固定原契约。</p>
          <p v-else class="api-muted" role="status">{{ detail.summary.acceptedContractHash ? '当前没有唯一候选契约，不能判断执行契约是否变化。' : '尚无已接纳契约；本次是首次接纳，不是旧契约变更。' }}</p>
          <ul v-if="contractComparison.available && contractComparison.differences.length" class="api-conflict-list">
            <li v-for="difference in contractComparison.differences" :key="difference.path">
              <strong>{{ difference.label }}</strong><small>{{ difference.path }}</small>
              <dl><dt>已接纳</dt><dd>{{ difference.before }}</dd><dt>候选</dt><dd>{{ difference.after }}</dd></dl>
            </li>
          </ul>
          <p v-else-if="detail.summary.acceptedContractHash && !contractComparison.available" class="api-warning">字段比较不可用；展开下方契约和来源事实核对，不将缺失信息视为没有差异。</p>
          <p v-if="contractComparison.truncated" class="api-warning">字段比较已截断，仅展示前 64 处差异；展开完整契约核对。</p>
          <p class="api-muted">来源集合修订与执行契约 hash 分开记录。展示文案、重扫清单修订或来源成员变化也可能使旧发布的来源固定失效；须重新校验并显式发布，不自动刷新旧固定。</p>
        </div>
        <div v-if="detail.summary.sourceStatus === 'CONFLICT'" class="api-conflicts" aria-labelledby="api-conflict-heading">
          <h3 id="api-conflict-heading">来源契约差异</h3>
          <p class="api-muted">已接纳契约及已有发布引用保持原值；这里仅比较活动来源，不选择新的执行目标。</p>
          <ul v-if="sourceComparison.differences.length" class="api-conflict-list">
            <li v-for="difference in sourceComparison.differences" :key="difference.path">
              <strong>{{ difference.label }}</strong>
              <dl><template v-for="value in difference.values" :key="value.sourceId">
                <dt>{{ sourceName(value.sourceId) }}</dt><dd>{{ value.text }}</dd>
              </template></dl>
            </li>
          </ul>
          <p v-else class="api-warning" role="status">来源契约无法完整比较，请展开各来源契约核对后重新扫描；不会解除冲突保护。</p>
          <p v-if="sourceComparison.truncated" class="api-warning">仅展示前 64 处差异；展开各来源契约可查看完整事实。</p>
        </div>
        <p v-if="acceptError" class="api-warning" role="alert">{{ acceptError }}
          <el-button link type="primary" @click="load">刷新并比较</el-button></p>
        <div class="api-accept-impact" role="status">
          <p>{{ referencesLoading ? '正在查询使用位置；影响尚未确认。' : referencesError || !references ? '使用位置当前不可用，影响未知。' : `已查到 ${references.references.length} 处使用位置${referencesComplete ? '，本次证据完整。' : '，尚未完整确认，可能还有其他使用方。'}` }}
            <a href="#api-usage-heading" class="api-usage-link">查看具体影响</a></p>
          <p class="api-muted">接纳只更新目录事实，不会更新旧版本、调用授权或 MCP/A2A 开放发布。请从受影响草稿修正映射、校验并发布新版本，再显式更新开放发布；旧版本不会自动转向新契约。</p>
        </div>
        <div class="api-source-list">
          <article v-for="source in detail.sources" :key="source.id" class="api-source-item">
            <div><strong>{{ sourceKind(source.sourceKind) }}</strong>
              <el-tag :type="source.status === 'REMOVED' ? 'info' : source.confirmedInLatestInventory ? 'success' : 'warning'" size="small" effect="light">
                {{ source.status === 'REMOVED' ? '来源已移除' : source.confirmedInLatestInventory ? '本次已确认' : '本次未确认' }}
              </el-tag></div>
            <p>{{ source.sourceLocation || source.sourceKey }}</p>
            <template v-if="source.sourceKind === 'API_MARKET_OPERATION'">
              <small>固定目录选择 · 最近观察：{{ displayTime(source.observedAt) }}；目录变化不会自动改选或验证。</small>
            </template>
            <template v-else>
              <small>最近观察：{{ displayTime(source.observedAt) }} · 清单：{{ source.inventoryComplete ? '完整' : '部分或未知' }}</small>
              <small>最近清单：{{ displayTime(source.latestInventoryAt) }} · 最近确认：{{ displayTime(source.confirmedAt) }}</small>
            </template>
            <p v-if="source.reason" class="api-warning">{{ source.reason }}</p>
            <details class="api-evidence"><summary>查看此来源的规范化契约</summary>
              <p>来源 key：{{ source.sourceKey }}</p><p>修订：{{ source.sourceRevision || '尚无记录' }}</p>
              <p>契约 hash：{{ source.sourceContractHash }}</p><pre>{{ pretty(source.contract) }}</pre>
            </details>
          </article>
        </div>
        <details class="api-evidence"><summary>比较契约与审计标识</summary>
          <div class="api-contract-compare">
            <div><h3>当前来源</h3><pre>{{ pretty(detail.contract) }}</pre></div>
            <div><h3>已接纳</h3><pre>{{ pretty(detail.acceptedContract) }}</pre></div>
          </div>
          <p>候选 hash：{{ detail.summary.candidateContractHash || '无' }}</p>
          <p>已接纳 hash：{{ detail.summary.acceptedContractHash || '无' }}</p>
          <p>来源集合修订：{{ detail.summary.sourceSetRevision }}</p>
          <p>最近接纳：{{ detail.summary.acceptedBy || '尚未接纳' }} · {{ displayTime(detail.summary.acceptedAt) }}</p>
        </details>
      </section>

      <section class="api-card" aria-labelledby="api-usage-heading">
        <div class="api-section-head"><div><p class="api-eyebrow">依赖检查</p><h2 id="api-usage-heading">使用位置</h2></div>
          <el-button :icon="Refresh" :loading="referencesLoading" @click="loadReferences()">刷新使用位置</el-button>
        </div>
        <p class="api-muted">查看引用这条 API 的 Workflow 草稿、发布版本及开放入口。</p>
        <div v-if="referencesLoading && !references" class="api-usage-state" role="status">正在查询使用位置…</div>
        <div v-else-if="referencesError" class="api-usage-state" role="alert">
          <span>{{ referencesError }}</span><el-button text @click="loadReferences()">重试查询</el-button>
        </div>
        <template v-else-if="references">
          <el-alert v-if="!referencesComplete" title="部分使用位置尚未确认"
            description="下方仅展示已查到的引用，不能据此判断没有其他使用方。" type="warning" :closable="false" show-icon />
          <ul v-if="references.references.length" class="api-usage-list">
            <li v-for="(usage, index) in references.references" :key="`${usage.kind}:${usage.id}:${usage.versionId || index}`">
              <div><strong>{{ usage.name || usage.id }}</strong>
                <span>{{ referenceKindLabel(usage.kind) }} · {{ referenceStage(usage.stage) }}<template v-if="usage.version"> · {{ usage.version }}</template></span>
                <small v-if="usage.nodeId">节点 {{ usage.nodeId }}</small>
                <small v-if="usage.kind === 'MCP' && usage.versionId">固定 Workflow 版本 ID {{ usage.versionId }} · {{ usage.workflowId }}</small>
              </div>
              <router-link v-if="usageRoute(usage)" :to="usageRoute(usage)!" class="api-usage-link"
                :aria-label="`查看 ${usage.name || usage.id}`">查看</router-link>
            </li>
          </ul>
          <p v-else class="api-usage-state" role="status">{{ referencesComplete ? '还没有被引用。可从 Workflow Studio 添加这条 API。' : '暂未查到已确认的引用；稍后刷新。' }}</p>
        </template>
      </section>

      <section class="api-card" aria-labelledby="api-connection-heading">
        <div class="api-section-head"><div><p class="api-eyebrow">测试环境</p><h2 id="api-connection-heading">配置接入</h2></div></div>
        <p class="api-muted">只保存服务 origin（协议、主机、端口）；完整路径来自已接纳契约，不重复填写 contextPath。</p>
        <div v-if="marketSource" class="api-response-contract" aria-live="polite">
          <h3>当前市场绑定验证 · {{ currentMarketVerified ? '已确认' : '未确认' }}</h3>
          <p class="api-muted">{{ connection?.verification?.reason || '连接配置不是成功验证，请接纳并配置后完成一次真实 Console GET。' }}</p>
          <p class="api-muted">验证绑定 API、已接纳契约、固定目录选择、连接与凭据修订；失败、未知或旧修订不支持新发布。</p>
          <div v-if="connection?.verification?.runId" class="api-result-links">
            <router-link v-if="connection.verification.traceId" :to="`/runops/${connection.verification.traceId}`">验证 Run/Trace</router-link>
            <span>Run #{{ connection.verification.runId }} · Trace {{ connection.verification.traceId }}</span>
          </div>
          <el-button link type="primary" @click="router.push('/api-market')">返回市场接入，创建稳定 API 引用草稿</el-button>
        </div>
        <p v-if="connection?.blockingReason" class="api-warning" role="status">{{ connection.blockingReason }}</p>
        <p v-if="connectionError" class="api-warning" role="alert">{{ connectionError }}
          <el-button link type="primary" @click="load">刷新连接</el-button></p>
        <div class="api-facts" v-if="connection?.revision">
          <div><span>当前服务地址</span><strong>{{ connection.origin }}</strong></div>
          <div><span>当前凭据</span><strong>{{ connection.credentialName || '不使用凭据' }}</strong></div>
          <div><span>预览地址</span><strong>{{ connection.previewUrl }}</strong></div>
          <div><span>连接修订</span><strong>{{ connection.revision }}</strong></div>
        </div>
        <el-form novalidate label-position="top" class="api-form" @submit.prevent="saveConnection">
          <el-form-item label="服务地址" :error="connectionFieldError">
            <el-input v-model="originDraft" placeholder="https://orders.example.com:8443" :disabled="!canWrite" />
          </el-form-item>
          <el-form-item label="认证方式">
            <el-select v-model="authModeDraft" placeholder="明确选择认证方式" :disabled="!canWrite" aria-label="API 连接认证方式">
              <el-option v-for="mode in allowedModes" :key="mode" :value="mode" :label="authModeLabel(mode)" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="authModeDraft && authModeDraft !== 'NONE'" label="项目凭据">
            <div class="api-credential-row">
              <el-select v-model="credentialDraft" filterable clearable placeholder="选择当前项目的活动凭据"
                :disabled="!canWrite" aria-label="项目凭据">
                <el-option v-for="item in eligibleCredentials" :key="item.credentialRef"
                  :label="`${item.name} / ${item.type}`" :value="item.credentialRef" />
              </el-select>
              <el-button v-if="canManageCredential" native-type="button" @click="credentialDialog = true">新建项目凭据</el-button>
            </div>
            <p v-if="!eligibleCredentials.length" class="api-muted">没有匹配的活动项目凭据；{{ canManageCredential ? '可在这里新建。' : '请联系有凭据管理权限的同事。' }}</p>
          </el-form-item>
          <div class="api-form-actions"><el-button type="primary" native-type="submit" :loading="saving"
            :disabled="!canWrite || !sourceAccepted || !connection || saving">保存连接</el-button>
            <span v-if="!canWrite">当前账号缺少项目写入权限。</span>
            <span v-else-if="!sourceAccepted">请先接纳当前来源契约。</span>
          </div>
        </el-form>
        <CredentialCreateDialog v-model="credentialDialog" project-only
          :project-id="detail.summary.projectId" :project-code="detail.summary.projectCode"
          :allowed-types="['API_KEY_HEADER', 'BEARER']" :initial-type="authModeDraft === 'BEARER' ? 'BEARER' : 'API_KEY_HEADER'"
          :suggested-header="declaredHeader" @created="credentialCreated" />
      </section>

      <section class="api-card" aria-labelledby="api-call-heading">
        <div class="api-section-head"><div><p class="api-eyebrow">一次受控请求</p><h2 id="api-call-heading">试调用</h2></div></div>
        <p class="api-muted">仅提交当前契约声明的参数与受支持的 POST JSON 请求体；HTTP 2xx 不代表业务成功。内部 POST + WRITE 平铺 JSON 可通过已发布 Workflow 执行；Studio 两种调试入口仍不执行写 API。Agent 使用既有写操作确认，MCP 由已授权客户端显式发起新运行。</p>
        <p v-if="callBlocker" class="api-warning" role="status">{{ callBlocker }}</p>
        <el-form novalidate label-position="top" class="api-form" @submit.prevent="invoke">
          <div class="api-parameter-grid">
            <el-form-item v-for="parameter in trialParameters" :key="`${parameter.location}:${parameter.name}`"
              :label="`${parameter.name} · ${parameter.location.toLowerCase()}${parameter.required ? ' · 必填' : ''}`"
              :error="parameterErrors[`${parameter.location}:${parameter.name}`]">
              <el-select v-if="parameter.schema.type === 'boolean'" v-model="parameterDraft[`${parameter.location}:${parameter.name}`]"
                clearable placeholder="选择 true / false" :disabled="invoking || !!invocationId" :aria-label="parameter.name">
                <el-option label="true" value="true" /><el-option label="false" value="false" />
              </el-select>
              <el-input v-else v-model="parameterDraft[`${parameter.location}:${parameter.name}`]"
                :placeholder="parameter.schema.type === 'integer' || parameter.schema.type === 'number' ? '输入数字' : '输入参数值'"
                :disabled="invoking || !!invocationId" :aria-label="parameter.name" />
            </el-form-item>
          </div>
          <BusinessMethodInvocationInputEditor v-if="isWrite" v-model="bodyDraft" title="JSON 请求体"
            :parameters="bodyParameters" :disabled="invoking || !!invocationId"
            @draft-change="bodyDraftChanged" />
          <p v-if="bodyError" class="api-warning" role="alert">{{ bodyError }}</p>
          <div class="api-form-actions">
            <el-button v-if="!invocationId" type="primary" native-type="submit" :loading="invoking"
              :disabled="!!callBlocker || invoking">{{ isWrite ? '确认写入试调用' : '试调用' }}</el-button>
            <el-button v-if="invocationId" native-type="button" :loading="querying" :disabled="invoking" @click="queryAttempt()">查询原调用</el-button>
            <el-button v-if="invocationId && canStartNewHttpApiAttempt(outcome) && !invoking"
              native-type="button" :disabled="querying" @click="newAttempt">发起新调用</el-button>
          </div>
        </el-form>
        <p v-if="callError" class="api-warning" role="alert">{{ callError }}</p>
        <p v-if="invocationId && (!outcome || outcome.status === 'UNKNOWN')" class="api-warning" role="status">本次可能已经执行，无法确认业务系统是否已生效。请查询同一调用 ID，勿重复提交。</p>
        <p v-if="outcome?.status === 'HTTP_FAILED'" class="api-warning" role="status">已收到 HTTP 错误或重定向响应；这不表示业务未执行。平台未自动重发，请核对业务状态。</p>
        <div v-if="invocationId" class="api-result" aria-live="polite">
          <div class="api-section-head"><h3>调用记录</h3>
            <el-tag :type="outcome?.status === 'SUCCEEDED' ? 'success' : outcome?.status === 'HTTP_FAILED' ? 'danger' : 'warning'">
              {{ outcome ? outcomeStatus(outcome.status) : '结果待查询' }}
            </el-tag></div>
          <div class="api-facts">
            <div><span>调用 ID</span><strong>{{ invocationId }}</strong></div>
            <div><span>HTTP 状态</span><strong>{{ outcome?.httpStatus ?? '未确认' }}</strong></div>
            <div><span>实际耗时</span><strong>{{ outcome?.latencyMs == null ? '未确认' : `${outcome.latencyMs} ms` }}</strong></div>
            <div><span>当前有效性</span><strong>{{ currentEvidence ? '与当前契约及配置一致' : '历史结果，当前状态需重新核对' }}</strong></div>
          </div>
          <pre v-if="outcome?.result != null" class="api-result-body">{{ pretty(outcome.result) }}</pre>
          <p v-if="outcome?.resultTruncated" class="api-muted">响应超过安全展示上限，已截断。</p>
          <p v-if="outcome?.resultExpiresAt && Date.now() > outcome.resultExpiresAt" class="api-muted">安全结果已超过 24 小时读取期限。</p>
          <div v-if="outcome" class="api-result-links">
            <router-link :to="`/runops/${outcome.traceId}`">查看运行记录</router-link>
            <span>Run #{{ outcome.runId }} · Trace {{ outcome.traceId }}</span>
          </div>
        </div>
      </section>
    </template>
    <AppDialog v-model="confirmationOpen" title="确认写入试调用" width="560px" @open-auto-focus="focusCancel" @opened="focusCancel">
      <div class="api-confirmation">
        <p>这会实际写入所选环境，不是模拟或只读验证。</p>
        <p>{{ detail?.summary.projectCode }} · {{ detail?.summary.environment }} · {{ detail?.summary.httpMethod }} {{ detail?.summary.routeTemplate }}</p>
        <p>目标：{{ connection?.previewUrl }}；项目凭据：{{ connection?.credentialName || '不使用凭据' }}。平台账号仅用于授权与审计，不提供业务用户身份。</p>
        <p>确认绑定当前契约、来源、连接修订及完整输入；平台不会自动重试或跟随重定向。</p>
      </div>
      <template #footer>
        <el-button ref="cancelButton" @click="confirmationOpen = false">取消</el-button>
        <el-button type="warning" :loading="invoking" :disabled="invoking || !confirmationSnapshot" @click="confirmInvocation">确认并写入一次</el-button>
      </template>
    </AppDialog>
    <AppDialog v-model="newAttemptOpen" title="发起新的试调用" width="520px">
      <p>上次请求可能已在业务系统生效。新调用是另一次真实操作，不是恢复查询；请先核对业务状态。</p>
      <template #footer><el-button @click="newAttemptOpen = false">继续查询原调用</el-button>
        <el-button type="warning" @click="beginNewAttempt">使用新 ID 准备新调用</el-button></template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { HTTP_API_CATALOG_PATH } from '@/views/capability/businessCapabilityRoutes'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import ProjectScopeState from '@/components/common/ProjectScopeState.vue'
import CredentialCreateDialog from '@/components/credential/CredentialCreateDialog.vue'
import AppDialog from '@/components/common/AppDialog.vue'
import BusinessMethodInvocationInputEditor from '@/views/capability/components/BusinessMethodInvocationInputEditor.vue'
import { readInvocationReferences, rememberInvocationReference, removeInvocationReferences } from '@/composables/consoleInvocationReferences'
import { platformSessionId, platformSessionUser } from '@/auth/platformSession'
import { hasPlatformResourcePermission, PLATFORM_PERMISSION_WRITE, PLATFORM_PERMISSION_CAPABILITY_INVOKE,
  PLATFORM_PERMISSION_WORKFLOW_CREDENTIAL_MANAGE } from '@/auth/platformAccess'
import { usePageProjectScope } from '@/composables/usePageProjectScope'
import { listWorkflowCredentials } from '@/api/workflowCredential'
import { acceptHttpApi, getHttpApi, getHttpApiConnection, getHttpApiInvocation,
  getHttpApiReferences, invokeHttpApi, saveHttpApiConnection } from '@/api/httpApi'
import type { AssetReferences } from '@/types/assetReferences'
import { referenceKindLabel, referenceStage, usageRoute } from '@/views/capability/referenceUsage'
import { buildHttpApiTrialParameters, canStartNewHttpApiAttempt, httpApiBodyParameters, httpApiTrialFailure, validateHttpApiBody, isCurrentHttpApiEvidence } from './httpApiTrial'
import { compareHttpApiContracts, compareHttpApiSources } from './httpApiSourceComparison'
import type { WorkflowCredential } from '@/types/workflowCredential'
import type { HttpApiConnection, HttpApiDetail, HttpApiInvocationOutcome,
  HttpApiSourceStatus } from '@/types/httpApi'

const route = useRoute()
const router = useRouter()
const scope = usePageProjectScope()
const apiId = computed(() => Number(route.params.id))
const projectId = computed(() => scope?.canLoadData.value ? scope.requestParams.value?.projectId ?? null : null)
const detail = ref<HttpApiDetail | null>(null)
const connection = ref<HttpApiConnection | null>(null)
const marketSource = computed(() => detail.value?.summary.sourceKinds?.includes('API_MARKET_OPERATION') === true)
const currentMarketVerified = computed(() => {
  const owner = detail.value?.summary, proof = connection.value?.verification
  return proof?.status === 'VERIFIED' && owner?.sourceConfirmed && owner.sourceStatus === 'ACCEPTED'
    && proof.apiId === owner.id && proof.acceptedContractHash === owner.acceptedContractHash
    && proof.sourceSetRevision === owner.sourceSetRevision && proof.connectionRevision === connection.value?.revision
    && proof.credentialRevision === connection.value?.credentialRevision
})
const references = ref<AssetReferences | null>(null)
const referencesLoading = ref(false)
const referencesError = ref('')
const credentials = ref<WorkflowCredential[]>([])
const outcome = ref<HttpApiInvocationOutcome | null>(null)
const invocationId = ref('')
const loading = ref(false)
const accepting = ref(false)
const saving = ref(false)
const invoking = ref(false)
const querying = ref(false)
const credentialDialog = ref(false)
const pageError = ref('')
const acceptError = ref('')
const connectionError = ref('')
const connectionFieldError = ref('')
const callError = ref('')
const originDraft = ref('')
const authModeDraft = ref('')
const credentialDraft = ref('')
const parameterDraft = reactive<Record<string, string>>({})
const parameterErrors = reactive<Record<string, string>>({})
const bodyDraft = ref<Record<string, unknown>>({})
const bodyDraftValid = ref(false)
const bodyRevision = ref(0)
const bodyError = ref('')
const confirmationOpen = ref(false)
const newAttemptOpen = ref(false)
const cancelButton = ref<{ $el?: HTMLElement } | null>(null)
const confirmationSnapshot = ref<{ signature: string; parameters: ReturnType<typeof buildHttpApiTrialParameters>; body: Record<string, unknown> } | null>(null)
let epoch = 0
let referenceSequence = 0

const referencesComplete = computed(() => references.value?.runtimeEvidence === 'COMPLETE'
  && references.value.publicationEvidence === 'COMPLETE')

const ownerCode = computed(() => detail.value?.summary.projectCode || '')
const grants = computed(() => platformSessionUser.value?.permissionGrants || [])
const canWrite = computed(() => !!ownerCode.value && hasPlatformResourcePermission(
  grants.value, PLATFORM_PERMISSION_WRITE, 'PROJECT', null, ownerCode.value))
const canManageCredential = computed(() => !!ownerCode.value && hasPlatformResourcePermission(
  grants.value, PLATFORM_PERMISSION_WORKFLOW_CREDENTIAL_MANAGE, 'PROJECT', null, ownerCode.value))
const hasInvokeGrant = computed(() => !!ownerCode.value && hasPlatformResourcePermission(
  grants.value, PLATFORM_PERMISSION_CAPABILITY_INVOKE, 'PROJECT', null, ownerCode.value))
const sourceAccepted = computed(() => detail.value?.summary.sourceConfirmed === true
  && detail.value.summary.sourceStatus === 'ACCEPTED'
  && !!detail.value.summary.acceptedContractHash
  && detail.value.summary.acceptedContractHash === detail.value.summary.candidateContractHash)
const canAccept = computed(() => !!detail.value && detail.value.summary.sourceConfirmed
  && detail.value.summary.sourceStatus !== 'ACCEPTED' && canWrite.value)
const displayContract = computed(() => detail.value?.contract || detail.value?.acceptedContract || null)
const sourceComparison = computed(() => compareHttpApiSources(detail.value?.sources || []))
const contractComparison = computed(() => compareHttpApiContracts(detail.value?.acceptedContract || null, detail.value?.contract || null))
const sameExecutionHash = computed(() => !!detail.value?.summary.acceptedContractHash
  && detail.value.summary.acceptedContractHash === detail.value.summary.candidateContractHash)
const equivalentSources = computed(() => sourceComparison.value.active.length > 1
  && sourceComparison.value.active.every(source => source.status === 'EQUIVALENT')
  && new Set(sourceComparison.value.active.map(source => source.sourceContractHash)).size === 1)
function sourceName(id: number) {
  const source = detail.value?.sources.find(item => item.id === id)
  return source ? `${sourceKind(source.sourceKind)} · ${source.sourceLocation || source.sourceKey}` : `来源 ${id}`
}
const authenticationLabel = computed(() => {
  const auth = displayContract.value?.authentication
  if (!auth) return '未声明'
  if (auth.state === 'NONE') return '无需认证'
  if (auth.state === 'UNKNOWN') return '来源未声明，需显式选择'
  const schemes = auth.schemes.map((scheme) => {
    if (scheme.toLowerCase().startsWith('api_key:')) return '请求头 API Key'
    if (scheme.toLowerCase().startsWith('http:bearer:')) return 'Bearer 令牌'
    return scheme
  })
  const headers = auth.requiredHeaderNames.join('、')
  return `${schemes.join('、') || '未声明方式'}${headers ? ` · ${headers}` : ''}`
})
const declaredHeader = computed(() => detail.value?.acceptedContract?.authentication?.requiredHeaderNames?.[0] || 'X-API-Key')
const allowedModes = computed(() => {
  const auth = detail.value?.acceptedContract?.authentication
  if (!auth) return []
  if (auth.state === 'NONE') return ['NONE']
  if (auth.state === 'UNKNOWN') return ['NONE', 'API_KEY_HEADER', 'BEARER']
  const scheme = auth.schemes[0]?.toLowerCase() || ''
  if (scheme.startsWith('api_key:')) return ['API_KEY_HEADER']
  if (scheme.startsWith('http:bearer:')) return ['BEARER']
  return []
})
const eligibleCredentials = computed(() => credentials.value.filter((item) => item.scope === 'PROJECT'
  && item.status === 'ACTIVE' && item.projectId === detail.value?.summary.projectId
  && item.projectCode === detail.value?.summary.projectCode && item.type === authModeDraft.value))
const trialParameters = computed(() => detail.value?.acceptedContract?.parameters?.filter(
  (parameter) => parameter.location === 'PATH' || parameter.location === 'QUERY') || [])
const isWrite = computed(() => detail.value?.acceptedContract?.sideEffect === 'WRITE')
const bodyParameters = computed(() => httpApiBodyParameters(detail.value))
const callBlocker = computed(() => {
  if (detail.value?.summary.sourceStatus === 'CONFLICT') return '来源契约冲突；请先修正来源并重新扫描，当前不能发起新试调用。'
  if (!sourceAccepted.value) return '请先确认来源并接纳当前契约。'
  if (!connection.value) return '正在读取连接状态。'
  if (connection.value.status !== 'CONFIGURED') return connection.value.blockingReason || '请先保存可用连接。'
  if (!hasInvokeGrant.value) return '当前账号缺少此项目的试调用权限。'
  return ''
})
const currentEvidence = computed(() => isCurrentHttpApiEvidence(detail.value, connection.value,
  outcome.value, sourceAccepted.value))

function storageKey() { return `reachai.httpApiInvocation.refs.v1.${encodeURIComponent(JSON.stringify([
  platformSessionUser.value?.userId, ownerCode.value, detail.value?.summary.environment, apiId.value,
]))}` }
function storedId() { return platformSessionUser.value?.userId ? readInvocationReferences(storageKey())[0]?.invocationId || '' : '' }
function saveId(value: string) { if (platformSessionUser.value?.userId) rememberInvocationReference(storageKey(), value) }
function clearId() { removeInvocationReferences(storageKey()) }
function matchesAttempt(data: HttpApiInvocationOutcome, id: string) {
  const owner = detail.value?.summary
  return !!owner && data.targetType === 'HTTP_API' && data.invocationId === id
    && data.qualifiedName === owner.qualifiedName && data.projectId === owner.projectId
    && data.projectCode === owner.projectCode && data.environment === owner.environment
}
function confirmationSignature() { return JSON.stringify([epoch, platformSessionId.value, apiId.value,
  detail.value?.summary, connection.value, parameterDraft, bodyDraft.value, bodyRevision.value, hasInvokeGrant.value]) }
function bodyDraftChanged(state: { valid: boolean; revision: number }) { bodyDraftValid.value = state.valid; bodyRevision.value = state.revision }
async function focusCancel(event?: Event) { event?.preventDefault(); await nextTick(); cancelButton.value?.$el?.focus() }
function backToList() { void router.push({ path: HTTP_API_CATALOG_PATH, query: route.query }) }
function pretty(value: unknown) { return value == null ? '尚无可比较契约' : JSON.stringify(value, null, 2) }
function displayTime(value: string | null) {
  if (!value) return '尚无记录'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '时间不可用' : date.toLocaleString('zh-CN')
}
function sourceLabel(value: HttpApiSourceStatus) {
  return ({ DISCOVERED: '契约待接纳', ACCEPTED: '契约已接纳', CONTRACT_DRIFT: '契约已变化',
    CONFLICT: '来源冲突', SOURCE_MISSING: '来源缺失', SOURCE_UNCONFIRMED: '来源待确认' } as Record<string, string>)[value] || value
}
function sourceTone(value: HttpApiSourceStatus) { return value === 'ACCEPTED' ? 'success' : value === 'DISCOVERED' ? 'warning' : 'danger' }
function sourceKind(value: string) { return ({ OPENAPI_SCAN: 'OpenAPI 扫描', CONTROLLER_SCAN: 'Controller 扫描',
  STARTER_MVC: 'Starter MVC 同步', API_MARKET_OPERATION: 'API 市场固定 Operation' } as Record<string, string>)[value] || value }
function authModeLabel(value: string) { return ({ NONE: '不使用凭据', API_KEY_HEADER: '请求头 API Key',
  BEARER: 'Bearer 令牌' } as Record<string, string>)[value] || value }
function outcomeStatus(value: string) { return ({ SUCCEEDED: 'HTTP 调用成功', HTTP_FAILED: 'HTTP 状态失败',
  UNKNOWN: '执行结果未知', NOT_DISPATCHED: '未派发', ACCEPTED: '调用已建立', DISPATCHING: '正在调用' } as Record<string, string>)[value] || value }

function resetView() {
  confirmationOpen.value = false; confirmationSnapshot.value = null; newAttemptOpen.value = false
  bodyDraft.value = {}; bodyDraftValid.value = false; bodyError.value = ''
  invoking.value = false; accepting.value = false; saving.value = false
  ++referenceSequence
  references.value = null; referencesLoading.value = false; referencesError.value = ''
  detail.value = null; connection.value = null; credentials.value = []; outcome.value = null; invocationId.value = ''
  querying.value = false
  pageError.value = ''; acceptError.value = ''; connectionError.value = ''; callError.value = ''
  originDraft.value = ''; authModeDraft.value = ''; credentialDraft.value = ''; credentialDialog.value = false
  Object.keys(parameterDraft).forEach((key) => delete parameterDraft[key])
  Object.keys(parameterErrors).forEach((key) => delete parameterErrors[key])
}
async function load() {
  const current = ++epoch
  resetView()
  if (!projectId.value || !Number.isInteger(apiId.value) || apiId.value <= 0) return
  loading.value = true
  try {
    const { data } = await getHttpApi(apiId.value)
    if (current !== epoch) return
    if (data.summary.projectId !== projectId.value) {
      pageError.value = '此 API 不属于当前项目，请切换项目范围后重试'; return
    }
    detail.value = data
    void loadReferences(current)
    const [connectionResult, credentialResult] = await Promise.allSettled([
      getHttpApiConnection(apiId.value),
      listWorkflowCredentials({ projectId: data.summary.projectId, projectCode: data.summary.projectCode }),
    ])
    if (current !== epoch) return
    if (connectionResult.status === 'fulfilled') {
      connection.value = connectionResult.value.data
      originDraft.value = connection.value.origin || ''
      authModeDraft.value = connection.value.authMode || (allowedModes.value.length === 1 ? allowedModes.value[0] : '')
      credentialDraft.value = connection.value.credentialRef || ''
    } else connectionError.value = '连接状态读取失败；请刷新后再保存或调用'
    if (credentialResult.status === 'fulfilled') credentials.value = credentialResult.value.data
    const prior = storedId()
    if (prior) { invocationId.value = prior; void queryAttempt(current) }
  } catch {
    if (current === epoch) pageError.value = 'API 详情加载失败；请确认项目权限或稍后重试'
  } finally { if (current === epoch) loading.value = false }
}
async function loadReferences(expectedEpoch = epoch) {
  if (!detail.value || expectedEpoch !== epoch) return
  const current = ++referenceSequence
  referencesLoading.value = true; referencesError.value = ''
  try {
    const { data } = await getHttpApiReferences(apiId.value)
    if (expectedEpoch !== epoch || current !== referenceSequence) return
    if (!data || !Array.isArray(data.references)) throw new Error('Invalid references')
    references.value = data
  } catch {
    if (expectedEpoch === epoch && current === referenceSequence) {
      referencesError.value = '使用位置查询失败，当前无法判断引用情况。请重试。'
    }
  } finally {
    if (expectedEpoch === epoch && current === referenceSequence) referencesLoading.value = false
  }
}
async function acceptCurrent() {
  if (!detail.value || !canAccept.value || accepting.value) return
  const current = epoch
  accepting.value = true; acceptError.value = ''
  try {
    await acceptHttpApi(apiId.value, detail.value.summary.sourceSetRevision)
    if (current === epoch) { ElMessage.success('当前契约已接纳'); void load() }
  } catch { if (current === epoch) acceptError.value = '接纳失败：来源可能已变化；刷新并比较后重试' }
  finally { if (current === epoch) accepting.value = false }
}
async function credentialCreated(item: WorkflowCredential) {
  credentials.value = [...credentials.value.filter((entry) => entry.credentialRef !== item.credentialRef), item]
  credentialDraft.value = item.credentialRef
}
async function saveConnection() {
  if (!detail.value || !canWrite.value || !sourceAccepted.value || !connection.value || saving.value) return
  connectionError.value = ''; connectionFieldError.value = ''
  if (!/^https?:\/\/[^/?#]+\/?$/i.test(originDraft.value.trim())) {
    connectionFieldError.value = '只填写 HTTP(S) 协议、主机和端口，不包含路径或查询'; return
  }
  if (!allowedModes.value.includes(authModeDraft.value)) { connectionError.value = '请选择来源支持的认证方式'; return }
  if (authModeDraft.value !== 'NONE' && !eligibleCredentials.value.some((item) => item.credentialRef === credentialDraft.value)) {
    connectionError.value = '请选择当前项目且类型匹配的活动凭据'; return
  }
  const current = epoch
  saving.value = true
  try {
    const { data } = await saveHttpApiConnection(apiId.value, { origin: originDraft.value.trim(),
      authMode: authModeDraft.value, credentialRef: authModeDraft.value === 'NONE' ? null : credentialDraft.value,
      expectedRevision: connection.value.revision })
    if (current !== epoch) return
    connection.value = data
    ElMessage.success('连接已保存')
  } catch { if (current === epoch) connectionError.value = '连接保存失败或修订已变化；草稿已保留，请刷新比较后重试' }
  finally { if (current === epoch) saving.value = false }
}

function buildParameters(): { pathParams: Record<string, unknown>; queryParams: Record<string, unknown> } | null {
  Object.keys(parameterErrors).forEach((key) => delete parameterErrors[key])
  const { pathParams, queryParams, errors } = buildHttpApiTrialParameters(trialParameters.value, parameterDraft)
  Object.assign(parameterErrors, errors)
  if (Object.keys(parameterErrors).length) {
    document.querySelector<HTMLElement>('.api-parameter-grid .is-error input')?.focus()
    return null
  }
  return { pathParams, queryParams }
}
async function invoke() {
  if (!detail.value || !connection.value?.revision || callBlocker.value || invocationId.value || invoking.value) return
  const parameters = buildParameters()
  if (!parameters) return
  if (isWrite.value) {
    bodyError.value = bodyDraftValid.value ? validateHttpApiBody(detail.value, bodyDraft.value) : '请先完成有效的 JSON 请求体。'
    if (bodyError.value) return
    confirmationSnapshot.value = { signature: confirmationSignature(),
      parameters: { ...parameters, errors: {} }, body: JSON.parse(JSON.stringify(bodyDraft.value)) }
    confirmationOpen.value = true
    return
  }
  await dispatch(parameters, undefined, false)
}
async function confirmInvocation() {
  const snapshot = confirmationSnapshot.value
  if (!confirmationOpen.value || !snapshot || snapshot.signature !== confirmationSignature() || callBlocker.value || invocationId.value || invoking.value) {
    confirmationOpen.value = false; confirmationSnapshot.value = null; return
  }
  confirmationOpen.value = false; confirmationSnapshot.value = null
  await dispatch(snapshot.parameters, snapshot.body, true)
}
async function dispatch(parameters: { pathParams: Record<string, unknown>; queryParams: Record<string, unknown> },
  body: Record<string, unknown> | undefined, confirmedSideEffect: boolean) {
  if (!detail.value || !connection.value?.revision || invoking.value || invocationId.value || callBlocker.value) return
  const id = crypto.randomUUID()
  const current = epoch
  invocationId.value = id; outcome.value = null; callError.value = ''
  saveId(id)
  invoking.value = true
  try {
    const { data } = await invokeHttpApi(apiId.value, { invocationId: id,
      expectedContractHash: detail.value.summary.acceptedContractHash || '',
      connectionRevision: connection.value.revision, pathParams: parameters.pathParams, queryParams: parameters.queryParams,
      body, confirmedSideEffect, expectedSourceSetRevision: detail.value.summary.sourceSetRevision,
      expectedCredentialRevision: connection.value.credentialRevision })
    if (current !== epoch || invocationId.value !== id) return
    if ('queryable' in data) callError.value = data.message
    else if (matchesAttempt(data, id)) outcome.value = data
    else callError.value = '返回未确认；请只读查询此调用 ID'
  } catch (error) {
    if (current === epoch && invocationId.value === id) callError.value = httpApiTrialFailure(error, 'invoke')
  } finally {
    if (current === epoch) { invoking.value = false; await refreshMarketVerification(current) }
  }
}
async function queryAttempt(expectedEpoch = epoch) {
  if (!detail.value || !invocationId.value || querying.value || invoking.value || !hasInvokeGrant.value) return
  const id = invocationId.value
  querying.value = true; callError.value = ''
  try {
    const { data } = await getHttpApiInvocation(id, detail.value.summary.projectCode)
    if (expectedEpoch === epoch && invocationId.value === id) {
      if (matchesAttempt(data, id)) outcome.value = data
      else callError.value = '调用记录身份无法确认，请核对运行记录'
    }
  } catch (error) {
    if (expectedEpoch === epoch && invocationId.value === id) {
      callError.value = httpApiTrialFailure(error, 'query')
    }
  } finally {
    if (expectedEpoch === epoch) { querying.value = false; await refreshMarketVerification(expectedEpoch) }
  }
}
async function refreshMarketVerification(expectedEpoch: number) {
  if (!marketSource.value || !connection.value) return
  try {
    const { data } = await getHttpApiConnection(apiId.value)
    if (expectedEpoch !== epoch || !connection.value || data.qualifiedName !== connection.value.qualifiedName) return
    // Keep unsaved connection edits and their optimistic revision; refresh only derived evidence.
    connection.value = { ...connection.value, verification: data.verification }
  } catch {
    if (expectedEpoch === epoch && connection.value) connection.value = { ...connection.value,
      verification: { status: 'UNKNOWN', reason: '当前验证事实读取失败，请刷新；不把未知当作成功', apiId: null,
        acceptedContractHash: null, sourceSetRevision: null, connectionRevision: null, credentialRevision: null,
        invocationId: null, runId: null, traceId: null, verifiedAt: null } }
  }
}
function newAttempt() {
  if (invoking.value || querying.value || !canStartNewHttpApiAttempt(outcome.value)) return
  newAttemptOpen.value = true
}
function beginNewAttempt() {
  if (invoking.value || querying.value || !newAttemptOpen.value) return
  invocationId.value = ''; outcome.value = null; callError.value = ''; clearId()
  confirmationSnapshot.value = null; confirmationOpen.value = false; newAttemptOpen.value = false
}
watch(confirmationSignature, () => {
  if (confirmationSnapshot.value && confirmationSnapshot.value.signature !== confirmationSignature()) {
    confirmationSnapshot.value = null; confirmationOpen.value = false
  }
}, { flush: 'sync' })
watch(confirmationOpen, open => { if (!open) confirmationSnapshot.value = null }, { flush: 'sync' })
watch(grants, () => {
  if (detail.value && !hasInvokeGrant.value) {
    ++epoch; bodyDraft.value = {}; bodyDraftValid.value = false; outcome.value = null
    Object.keys(parameterDraft).forEach(key => delete parameterDraft[key])
    confirmationSnapshot.value = null; confirmationOpen.value = false; newAttemptOpen.value = false
    invoking.value = false; querying.value = false
  }
}, { flush: 'sync', deep: true })
watch([apiId, () => scope?.requestKey.value, platformSessionId, () => platformSessionUser.value?.userId], () => {
  epoch += 1; resetView(); void load()
}, { flush: 'sync' })
onMounted(() => { void load() })
onBeforeUnmount(() => { ++epoch; resetView() })
</script>

<style scoped lang="scss">
.http-api-detail { max-width: 1450px; margin-inline: auto; }
.api-card { min-width: 0; padding: var(--panel-padding); border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg); background: var(--surface-solid-panel); color: var(--text-primary); }
.api-section-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.api-section-head h2, .api-section-head h3 { margin: 0; }
.api-eyebrow { margin: 0 0 4px; color: var(--text-muted); font-size: 11px; letter-spacing: .08em; text-transform: uppercase; }
.api-identity { display: flex; gap: 8px; align-items: center; color: var(--brand-active); font-weight: 700; }
.api-identity code { color: var(--text-primary); overflow-wrap: anywhere; }
.api-muted { color: var(--text-muted); line-height: 1.6; }
.api-warning { color: var(--el-color-warning-dark-2); line-height: 1.6; }
.api-usage-state { min-height: 48px; display: flex; align-items: center; gap: 12px; color: var(--text-muted); }
.api-contract-changes, .api-accept-impact { margin: 12px 0; overflow-wrap: anywhere; }
.api-contract-changes small { color: var(--text-muted); }
.api-usage-list { list-style: none; padding: 0; margin: 12px 0 0; display: grid; gap: 8px; }
.api-usage-list li { display: flex; justify-content: space-between; align-items: center; gap: 16px;
  padding: 10px 12px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); }
.api-usage-list li div { min-width: 0; display: grid; gap: 2px; }
.api-usage-list li strong { overflow-wrap: anywhere; }
.api-usage-list li span, .api-usage-list li small { color: var(--text-muted); }
.api-usage-link { flex: none; color: var(--brand-active); text-decoration: none; }
.api-usage-link:hover, .api-usage-link:focus-visible { text-decoration: underline; }
.api-facts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px;
  margin: 16px 0; padding: 16px; border-radius: var(--radius-md); background: var(--surface-glass-selected); }
.api-facts div { min-width: 0; }
.api-facts span { display: block; color: var(--text-muted); font-size: 12px; }
.api-facts strong { display: block; margin-top: 5px; overflow-wrap: anywhere; font-size: 13px; }
.api-contract-table { width: 100%; }
.api-response-contract { display: grid; gap: 8px; }
.api-response-contract div { display: flex; gap: 12px; flex-wrap: wrap; padding: 9px 12px; border: 1px solid var(--border-divider); border-radius: var(--radius-sm); }
.api-source-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 12px; margin-top: 16px; }
.api-source-item { min-width: 0; padding: 12px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); }
.api-source-item div { display: flex; gap: 8px; align-items: center; }
.api-source-item p, .api-source-item small { overflow-wrap: anywhere; }
.api-source-item small { color: var(--text-muted); }
.api-source-item > small { display: block; margin-top: 4px; }
.api-source-item pre { max-height: 260px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
.api-conflict-list { list-style: none; padding: 0; display: grid; gap: 12px; }
.api-conflict-list li { padding: 12px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); }
.api-conflict-list strong, .api-conflict-list dt, .api-conflict-list dd { overflow-wrap: anywhere; }
.api-conflict-list dl { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 8px 12px; margin-bottom: 0; }
.api-conflict-list dt { color: var(--text-muted); font-size: 12px; }
.api-conflict-list dd { margin: 0; font-family: var(--font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace); }
.api-evidence { margin-top: 16px; color: var(--text-muted); overflow-wrap: anywhere; }
.api-evidence summary { cursor: pointer; color: var(--brand-active); font-weight: 600; }
.api-contract-compare { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.api-contract-compare pre, .api-result-body { max-height: 300px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere;
  padding: 12px; border-radius: var(--radius-md); background: var(--surface-glass-selected); font-size: 12px; }
.api-form { margin-top: 14px; }
.api-form :deep(.el-select) { width: 100%; }
.api-credential-row { display: flex; gap: 8px; width: 100%; }
.api-credential-row .el-select { flex: 1; min-width: 0; }
.api-form-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; color: var(--text-muted); font-size: 12px; }
.api-parameter-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.api-result { margin-top: 18px; padding-top: 15px; border-top: 1px solid var(--border-divider); }
.api-result-links { display: flex; flex-wrap: wrap; gap: 12px; color: var(--text-muted); font-size: 12px; }
.api-result-links a { color: var(--brand-active); }
.api-confirmation { min-width: 0; overflow-wrap: anywhere; line-height: 1.6; }
.api-loading { padding: 40px; text-align: center; color: var(--text-muted); }
@media (max-width: 850px) { .api-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .api-contract-compare, .api-parameter-grid { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .api-facts { grid-template-columns: 1fr; }
  .api-source-list { grid-template-columns: 1fr; }
  .api-conflict-list dl { grid-template-columns: 1fr; }
  .api-credential-row, .api-usage-list li { flex-direction: column; align-items: stretch; } }
</style>
