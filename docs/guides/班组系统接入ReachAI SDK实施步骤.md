# 班组系统接入 ReachAI SDK 实施步骤

本文只记录班组业务系统接入 ReachAI 的标准实施步骤，用于下一次接入、复盘当前接入完整性，以及识别还需要产品化补齐的部分。本文不记录本次接入过程中的临时问题、踩坑和回退过程。

## 1. 接入目标

班组系统接入 ReachAI 后，需要达到以下效果：

1. 班组后端服务启动时主动注册到 ReachAI 中台。
2. 班组后端服务持续上报实例心跳。
3. 班组后端把可被 Agent 调用的业务接口注册为能力。
4. 班组前端页面可以嵌入 ReachAI 对话框。
5. 用户在班组档案页面输入自然语言后，Agent 可以触发页面动作，自动填充查询条件并执行查询。
6. embed token 由 `qmssmp-gateway` 统一签发、缓存和代理，不放在单个业务微服务里。

## 2. 涉及系统和职责

| 系统 | 路径 | 职责 |
| --- | --- | --- |
| ReachAI 中台 | `D:\work\EnterpriseAgentFramework` | 提供注册中心、能力资产、Agent Runtime、嵌入式对话、embed token exchange、Page Action 协议 |
| 班组后端服务 | `D:\work\qmssmp\qmssmp-teams-construction-service` | 引入 ReachAI SDK，注册项目、实例、能力，提供班组档案查询接口 |
| 业务网关 | `D:\work\qmssmp\qmssmp-gateway` | 统一处理业务登录态、微服务路由、ReachAI embed token broker/cache |
| 班组前端 | `D:\work\qmssmp\qmssmp-ui` | 嵌入对话框，申请 embed token，调用 ReachAI Chat API，执行页面动作 |

## 3. 前置条件

### 3.1 ReachAI 中台

1. 启动 ReachAI 后端服务，确保注册中心和嵌入式对话接口可用。
2. 本地默认地址为：

```text
http://localhost:18603
```

3. 确认 ReachAI 数据库已经执行最新 `sql/initV2.sql` 或对应升级脚本。
4. 在 ReachAI 中台中准备班组项目凭证：

```text
projectCode: qmssmp-teams-construction-service
appKey: qmssmp-teams-construction-service
appSecret: 按环境配置，不应在生产写死为 change-me
```

5. 在 Workflow Studio 中准备班组档案助手：

```text
agentId: team-archive-assistant
```

### 3.2 SDK 产物

班组系统是 Java 8 / Spring Boot 2 业务系统，只接入新的 JDK8 兼容 SDK：

```xml
<groupId>com.enterprise.ai</groupId>
<artifactId>reachai-capability-sdk</artifactId>
<version>1.0.0-SNAPSHOT</version>

<groupId>com.enterprise.ai</groupId>
<artifactId>reachai-spring-boot2-starter</artifactId>
<version>1.0.0-SNAPSHOT</version>
```

接入前需要确保这两个产物已经发布到业务系统可访问的 Maven 仓库，或已经安装到本地 Maven 仓库。

ReachAI 平台服务地址不是 Maven 仓库，也不是 npm registry。不要把平台 baseUrl 配成 Maven repository，不要请求 `/repository/**`、`/maven/**`、`/repository/maven/**`、`/api/embed/sdk` 或 `/npm/**` 这类猜测路径。若业务系统无法解析 SDK 依赖，应先发布到公司 Maven 仓库，或在 ReachAI 仓库本地执行 install。

本地安装可在 ReachAI 仓库执行：

```powershell
mvn -pl reachai-spring-boot2-starter -am install -DskipTests
```

## 4. 班组后端接入步骤

### 4.1 添加能力声明 SDK

在声明 Controller、DTO 或业务能力的模块中加入 `reachai-capability-sdk`。

班组系统当前放在：

```text
D:\work\qmssmp\qmssmp-teams-construction-service\depart-construction-api\pom.xml
```

配置示例：

```xml
<dependency>
    <groupId>com.enterprise.ai</groupId>
    <artifactId>reachai-capability-sdk</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 4.2 添加 Spring Boot 接入 Starter

在启动模块加入 `reachai-spring-boot2-starter`。

班组系统当前放在：

```text
D:\work\qmssmp\qmssmp-teams-construction-service\depart-construction-web\pom.xml
```

配置示例：

```xml
<dependency>
    <groupId>com.enterprise.ai</groupId>
    <artifactId>reachai-spring-boot2-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 4.3 配置 ReachAI 注册信息

在班组服务启动配置中增加 `reachai` 配置。

班组系统当前配置文件：

```text
D:\work\qmssmp\qmssmp-teams-construction-service\depart-construction-web\src\main\resources\application.yml
```

配置示例：

```yaml
reachai:
  registry:
    enabled: ${REACHAI_REGISTRY_ENABLED:true}
    url: ${REACHAI_REGISTRY_URL:http://localhost:18603}
    app-key: ${REACHAI_REGISTRY_APP_KEY:qmssmp-teams-construction-service}
    app-secret: ${REACHAI_REGISTRY_APP_SECRET:change-me}
    heartbeat-interval-ms: ${REACHAI_REGISTRY_HEARTBEAT_INTERVAL_MS:180000}
  project:
    code: qmssmp-teams-construction-service
    name: 班组建设服务
    base-url: ${REACHAI_PROJECT_BASE_URL:http://localhost:${server.port}}
    environment: ${spring.profiles.active:dev}
    owner: qmssmp
    visibility: PROJECT
  capability:
    scan-beans: true
    sync-on-startup: true
  embed:
    allowed-origins:
      - ${REACHAI_EMBED_ORIGIN:http://localhost:9200}
    allowed-agent-ids:
      - team-archive-assistant
    token-ttl-seconds: ${REACHAI_EMBED_TOKEN_TTL_SECONDS:1800}
  agent-graph:
    sync-on-startup: true
```

关键含义：

| 配置 | 含义 |
| --- | --- |
| `reachai.registry.url` | ReachAI 中台地址 |
| `reachai.registry.app-key` | 业务系统访问 ReachAI 的应用 key |
| `reachai.registry.app-secret` | 业务系统访问 ReachAI 的应用密钥 |
| `reachai.project.code` | 中台识别业务系统的稳定项目编码 |
| `reachai.project.base-url` | ReachAI 调用业务能力时使用的基础地址 |
| `reachai.capability.sync-on-startup` | 启动时同步能力快照 |
| `reachai.embed.allowed-origins` | 允许嵌入对话框的业务前端 Origin |
| `reachai.embed.allowed-agent-ids` | 允许该业务系统嵌入的 Agent |

### 4.4 声明业务能力

在希望暴露给 Agent 的 Controller 或 Service 方法上增加 `@ReachCapability`。

班组档案查询当前能力声明位置：

```text
D:\work\qmssmp\qmssmp-teams-construction-service\depart-construction-api\src\main\java\com\qtsk\qmssmp\controller\TeamInfoController.java
```

示例：

```java
@ApiOperation("查询部室列表")
@ReachCapability(
        name = "teamArchivePage",
        title = "查询班组档案",
        description = "按关联组织、班组名称、负责人姓名或班组成员姓名分页查询班组档案列表，适合页面智能助手执行自然语言筛选。",
        domain = "qmssmp",
        module = "teams-construction",
        tags = {"班组档案", "列表查询", "页面筛选"},
        sideEffect = ReachSideEffectLevel.READ
)
@PostMapping("/page")
public WebApiResult<Page<TeamInfoPageVO>> page(@RequestBody TeamInfoPageDTO dto) {
    return teamInfoService.page(dto);
}
```

能力声明建议：

1. `name` 使用稳定英文标识，后续不要随意变更。
2. `title` 使用业务人员能理解的中文名称。
3. `description` 写清楚查询维度、适用场景和限制。
4. 查询类能力标记为 `ReachSideEffectLevel.READ`。
5. 如果参数语义不够清晰，补充 `@ReachParam` 或在 DTO 字段上补充可扫描的语义信息。

### 4.5 启动并确认注册

启动班组后端服务后，在 ReachAI 注册中心确认：

1. 项目列表出现 `qmssmp-teams-construction-service`。
2. 项目名称为 `班组建设服务`。
3. 实例心跳正常。
4. 能力列表中出现 `teamArchivePage`。
5. 能力详情中能看到请求路径、HTTP 方法、入参和出参结构。

## 5. 网关接入步骤

### 5.1 网关职责

`qmssmp-gateway` 是业务系统统一入口。embed token 不放在 `teams-construction-service` 中，而是由网关统一处理：

```text
qmssmp-ui -> qmssmp-gateway -> ReachAI /api/embed/token/exchange
```

这样下一次其他页面、其他微服务接入嵌入式对话时，不需要每个业务服务都重复实现 token proxy。

### 5.2 增加网关配置

网关配置文件：

```text
D:\work\qmssmp\qmssmp-gateway\qmssmp-gateway\src\main\resources\application.yml
```

配置示例：

```yaml
reachai:
  registry:
    url: ${REACHAI_REGISTRY_URL:http://localhost:18603}
  embed:
    enabled: ${REACHAI_EMBED_TOKEN_BROKER_ENABLED:true}
    default-project-code: ${REACHAI_EMBED_DEFAULT_PROJECT_CODE:qmssmp-teams-construction-service}
    cache-skew-seconds: ${REACHAI_EMBED_TOKEN_CACHE_SKEW_SECONDS:60}
  projects:
    - code: qmssmp-teams-construction-service
      name: 班组建设服务
      app-key: ${REACHAI_TEAMS_APP_KEY:qmssmp-teams-construction-service}
      app-secret: ${REACHAI_TEAMS_APP_SECRET:change-me}
```

生产环境必须通过环境变量或配置中心注入 `app-secret`。

### 5.3 提供 embed token 接口

网关提供统一 token 接口：

```http
GET /api/v1/reachai/embed-token
```

请求参数：

| 参数 | 必填 | 含义 |
| --- | --- | --- |
| `projectCode` | 建议传 | 业务系统项目编码 |
| `agentId` | 是 | 页面要嵌入的 Agent ID |
| `pageInstanceId` | 是 | 当前页面实例 ID |
| `route` | 否 | 当前前端路由 |
| `origin` | 否 | 当前页面 Origin |

网关需要做的事：

1. 从业务登录态解析当前用户。
2. 将业务用户映射为 ReachAI embed principal。
3. 使用项目 `appKey/appSecret` 签名请求 ReachAI。
4. 调用 ReachAI：

```http
POST /api/embed/token/exchange
```

5. 按 `projectCode + agentId + pageInstanceId + route + origin + externalUserId` 缓存短期 token。
6. 返回中台签发的 embed token 给前端。

ReachAI token exchange 成功响应是统一 `ApiResult`，不是顶层 token：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "jwt",
    "expiresIn": 600,
    "sessionHint": {}
  }
}
```

网关 broker 必须从 `data.token` / `data.expiresIn` 取值；可以兼容历史顶层 `token` / `expiresIn`，但不能只读取顶层字段。实现时需要用上面的包装响应做 mock 单测或本地断言，避免把单路径 helper 误用成 `token.data.token`。

## 6. 班组前端接入步骤

### 6.1 增加 ReachAI 前端配置

前端配置文件：

```text
D:\work\qmssmp\qmssmp-ui\src\environments\environment-config.ts
```

配置示例：

```ts
reachAi = {
  enabled: true,
  apiBase: 'http://localhost:18603',
  tokenPath: 'reachai/embed-token',
  projectCode: 'qmssmp-teams-construction-service',
  agentId: 'team-archive-assistant'
};
```

说明：

| 配置 | 含义 |
| --- | --- |
| `enabled` | 是否显示嵌入式助手 |
| `apiBase` | ReachAI Chat API 地址（ReachAI 平台 origin；SDK 请求 `${apiBase}/api/embed/**`。不要写成 `/api/reachai/embed`） |
| `tokenPath` | 通过业务网关申请 embed token 的路径 |
| `projectCode` | 当前业务系统项目编码 |
| `agentId` | 页面 copilot Agent 的 keySlug；应先 provisioning 获取 `agent.keySlug`，不要用展示名 |

SDK 接入项目的前端配置不再包含 `pageRegistry.appSecret`。项目级 `appSecret` 只保存在业务后端或网关侧，用于服务端到服务端申请 embed token、注册能力和平台调用业务接口时的短期 invocation token 签发链路。

当前约定是：Chat API 允许前端直连 ReachAI，embed token 必须通过 `qmssmp-gateway` 获取；SDK 接入项目的页面 / 页面动作目录由平台页面助手、业务后端注册或治理页面维护，不再要求浏览器携带项目级密钥。扫描方式项目如仍使用旧的开发态 `pageRegistry` 自动上报，应与 SDK 接入链路分开治理。

`qmssmp-gateway` 在申请 embed token 时必须把当前登录用户映射为 ReachAI `principal`，其中 `principal.externalUserId` 为必填项。班组网关当前会从业务 JWT claims 中取 `sub/userId` 作为 `externalUserId`；如果后续换成其它业务系统自己的 token broker，也必须保留这个字段，否则 ReachAI 会按治理规则拒绝签发 embed token。

### 6.2 嵌入对话框组件

班组档案页面当前组件位置：

```text
D:\work\qmssmp\qmssmp-ui\src\app\pages\team-build\depart-management\partial\reach-ai-chat
```

当前班组前端已经在该 Angular 组件中实现了与 ReachAI 前端 SDK 等价的最小协议：创建嵌入式会话，轮询 pending page action，执行本地 handler，并回传 result。后续如果改成正式 npm SDK，只需要把这些协议调用替换为 `createEafPageBridge/createEafChat`，页面 handler 和验收流程不变。

组件职责：

1. 生成 `pageInstanceId`。
2. 调用网关 `reachai/embed-token` 获取 embed token。
3. 调用 ReachAI 创建嵌入式会话：

```http
POST /api/embed/chat/sessions
```

5. 调用 ReachAI 发送用户消息：

```http
POST /api/embed/chat/sessions/{sessionId}/messages
```

6. 接收 ReachAI 返回的 Page Action 请求。
7. 执行当前页面注册的前端动作。
8. 回传 Page Action 执行结果：

```http
POST /api/embed/chat/sessions/{sessionId}/page-actions/{requestId}/result
```

最小初始化代码示例：

```ts
const pageBridge = createEafPageBridge({
  pageInstanceId: 'team-archive-list',
  route: window.location.pathname
});

const chat = await createEafChat({
  mount: '#reachai-chat',
  apiBase: reachAi.apiBase,
  agentId: reachAi.agentId,
  tokenProvider: async () => {
    const query = new URLSearchParams({
      projectCode: reachAi.projectCode,
      agentId: reachAi.agentId,
      pageInstanceId: pageBridge.pageInstanceId,
      route: window.location.pathname,
      origin: window.location.origin
    });
    const payload = await fetch(`${reachAi.tokenPath}?${query}`).then((res) => res.json());
    const token = payload.data?.token || payload.token;
    if (!token) {
      throw new Error('ReachAI embed token missing; check token broker principal.externalUserId');
    }
    return token;
  },
  bridge: pageBridge,
  page: {
    pageKey: 'teamArchive.list',
    name: '班组档案列表',
    routePattern: '/team-build/depart-management'
  }
});
```

页面动作如果在 `createEafChat` 后才注册，SDK 会自动重报目录；需要立即同步时可以调用 `chat.registerPageCatalog()`。

### 6.3 在页面中挂载组件

班组档案列表页面：

```text
D:\work\qmssmp\qmssmp-ui\src\app\pages\team-build\depart-management\partial\list\list.component.html
```

挂载方式：

```html
<div id="reachai-chat"></div>
<zr-reach-ai-chat [searchHandler]="aiSearchHandler"></zr-reach-ai-chat>
```

### 6.4 注册页面动作

班组档案页面当前 Page Action：

```text
actionKey: qmssmp.teamArchive.search
```

前端页面需要提供对应处理函数：

```ts
const pageBridge = createEafPageBridge({
  pageInstanceId,
  route: window.location.pathname
});

pageBridge.registerAction(
  'qmssmp.teamArchive.search',
  (args: TeamArchiveAiSearch) => this.applyAiSearch(args),
  {
    title: '班组档案查询',
    description: '回填班组名称、负责人或成员等筛选条件并执行查询',
    sampleArgs: { teamName: '一班' }
  }
);
```

SDK 接入项目的正式链路不要求浏览器自动签名上报页面目录。`pageKey`、`routePattern`、`actionKey`、展示标题、描述、示例参数等目录信息可以通过 ReachAI 管理端“创建页面助手”、业务后端注册流程或治理页面维护。

```http
POST /api/registry/projects/{projectCode}/pages/register
```

如仍使用该目录注册接口，应由可信业务后端或受控治理工具发起，使用项目级 `appKey/appSecret` 做服务端签名。SDK 接入项目不应把 `appSecret` 下发到浏览器。中台接受新增、变更，并在 `replaceActions=true` 时将本次未上报的旧动作标记为 `REMOVED`。随后 Workflow Studio 的 Page Action 节点可以直接从该目录选择页面动作。

如果页面动作在前端代码中新增，建议先通过“创建页面助手”或治理页面补齐目录，再让前端 handler 与目录中的 `actionKey` 保持一致。

参数结构建议：

```ts
interface TeamArchiveAiSearch {
  managerName?: string;
  teamName?: string;
  memberName?: string;
  organId?: string;
  deptId?: string;
}
```

页面动作处理函数需要完成：

1. 解析 Agent 返回的筛选参数。
2. 回填页面查询模型。
3. 重置分页到第一页。
4. 调用原有业务查询接口。
5. 更新表格数据。
6. 返回执行结果给 ReachAI。

返回结果建议至少包含：

```ts
{
  total: number,
  filters: object,
  records: object[]
}
```

## 7. Workflow Studio 配置步骤

### 7.1 创建或确认 Agent

在 ReachAI 中台创建班组档案助手：

```text
agentId: team-archive-assistant
name: 班组档案助手
```

### 7.2 配置运行图

Agent 的运行图需要能识别用户意图并输出页面动作请求。

配置 Page Action 节点时，不再手抄 `actionKey`。推荐流程是：

1. 在 ReachAI 管理端使用“创建页面助手”或页面动作治理入口维护 `teamArchive.list` 页面和 `qmssmp.teamArchive.search` 动作。
2. 启动班组前端并打开目标页面，确认能看到当前页面实例和嵌入式会话。
3. 在 Workflow Studio 的 Page Action 节点中选择或输入项目编码；如果顶部项目选择器已经选中当前项目，节点会默认带出该项目。
4. 节点会自动加载目录；也可以点击“加载目录”，依次选择已注册页面和该页面下的动作。
5. Studio 自动带出 `pageKey/actionKey/title/confirm/inputSchema/sampleArgs`。
6. 在“动作参数映射”表格中逐行配置参数取值表达式，例如把 `teamName` 映射到 `lastOutput.teamName`，不再默认手写整段 JSON。

班组档案查询场景最终落入 GraphSpec 的 Page Action 配置类似：

```json
{
  "type": "PAGE_ACTION",
  "projectCode": "qmssmp-teams-construction-service",
  "pageKey": "teamArchive.list",
  "actionKey": "qmssmp.teamArchive.search",
  "args": {
    "managerName": "靳圣辉"
  }
}
```

发布校验会检查同时配置了 `projectCode/pageKey/actionKey` 的 Page Action 是否仍在页面动作目录中，且状态必须为 `ACTIVE`。如果前端删除或改名了某个动作，Studio 发布会提示引用失效，避免上线后才发现页面接不住。

发布校验还会读取动作目录中的 `inputSchema` 和授权配置：

1. `inputSchema.required` 中的字段必须在 Page Action 节点的 `args` 中配置映射。
2. 如果 `inputSchema.additionalProperties=false`，节点不能额外传入 schema 之外的参数。
3. 如果页面动作目录配置了 `allowedAgentIds`，当前 Agent 必须在白名单内。
4. 项目必须存在 `ACTIVE` 的前端 SDK / 嵌入凭证；如果凭证配置了 `allowedAgentIds`，当前 Agent 也必须被允许嵌入。

项目详情的“页面动作目录”还可以查看某个动作被哪些 Agent GraphSpec 节点引用。前端开发者准备删除或改名 `actionKey` 前，应先查看引用列表；如果仍有启用中的 Agent 使用该动作，优先在 Studio 中改用新动作并重新发布，再让前端停止上报旧动作。

如果运行图中需要大模型理解自然语言，则必须配置 LLM 节点和模型实例。如果运行图不包含 LLM 节点，则不应强制要求 `modelInstanceId`。

### 7.3 调试页面动作

在项目详情的“页面动作目录”中，可以对 `ACTIVE` 动作发起调试：

1. 打开目标业务页面，并确保前端 SDK 已创建嵌入式会话。
2. 在 ReachAI 项目详情中选择对应动作，点击“调试”。
3. 输入参数 JSON 并发送调试请求。
4. ReachAI 会为当前在线页面创建一条 `REQUESTED` 页面动作事件。
5. 前端 SDK 轮询当前 session 的 pending page action，执行本地 handler，并通过既有 result 接口回传结果。
6. 调试弹窗会按 `requestId` 轮询页面动作事件，直接展示 `SUCCESS/FAILED/TIMEOUT`、错误信息和返回数据。
7. 也可以在嵌入式会话、页面动作事件和聊天事件审计中查看执行结果或错误原因。

如果没有找到在线页面，调试接口会返回 `NO_ACTIVE_SESSION`，此时先刷新业务页面并确认 SDK 初始化成功。

### 7.4 绑定嵌入授权

Agent 需要允许被班组项目嵌入：

```text
projectCode: qmssmp-teams-construction-service
agentId: team-archive-assistant
origin: http://localhost:9200
```

生产环境应配置正式域名 Origin。

## 8. 本地联调启动顺序

建议按以下顺序启动：

1. Consul
2. ReachAI 中台，默认 `http://localhost:18603`
3. 班组后端服务，默认 `http://localhost:8089`
4. `qmssmp-gateway`，默认 `http://localhost:8080`
5. `qmssmp-ui`，默认 `http://localhost:9200`

班组前端页面地址：

```text
http://localhost:9200/team-build/depart-management
```

登录账号按业务系统本地环境配置。

## 9. 验证清单

### 9.1 编译验证

ReachAI SDK：

```powershell
cd D:\work\EnterpriseAgentFramework
mvn -pl reachai-spring-boot2-starter -am test
```

班组后端：

```powershell
cd D:\work\qmssmp\qmssmp-teams-construction-service
mvn -pl depart-construction-web -am -DskipTests compile
```

网关：

```powershell
cd D:\work\qmssmp\qmssmp-gateway
mvn -pl qmssmp-gateway -am -DskipTests compile
```

班组前端：

```powershell
cd D:\work\qmssmp\qmssmp-ui
npm run build
```

### 9.2 注册中心验证

在 ReachAI 注册中心确认：

1. 项目已注册。
2. 实例心跳正常。
3. 能力快照已同步。
4. `teamArchivePage` 能力存在。
5. 嵌入授权包含 `team-archive-assistant`。

### 9.3 token 验证

登录班组前端后，通过浏览器网络面板确认：

```http
GET http://localhost:8080/api/v1/reachai/embed-token
```

返回中包含：

```json
{
  "token": "...",
  "expiresIn": 1800
}
```

### 9.4 对话验证

在班组档案页面打开助手，输入：

```text
帮我查询一下负责人为靳圣辉的班组
```

期望结果：

1. 对话框能正常发送消息。
2. Agent 返回页面动作请求。
3. 前端自动回填负责人查询条件。
4. 前端自动执行查询。
5. 表格只展示符合条件的数据。
6. 对话框提示查询完成和记录数。

### 9.5 页面动作目录和调试闭环验证

打开班组档案页面后，在 ReachAI 项目详情中验证：

1. “页面动作目录”出现 `teamArchive.list` 页面。
2. 页面下出现 `qmssmp.teamArchive.search` 动作，状态为 `ACTIVE`。
3. 动作的标题、描述、示例参数和输入 schema 与前端 `registerAction` 配置一致。
4. 在 Workflow Studio 的 Page Action 节点中选择当前项目后，可以直接从目录选择 `teamArchive.list / qmssmp.teamArchive.search`。
5. 发布校验通过；如果删除或改名动作，发布校验应提示目录引用失效。
6. 在项目详情点击该动作“调试”，输入：

```json
{
  "managerName": "靳圣辉"
}
```

期望结果：

1. 调试接口返回 `REQUESTED`，并显示当前页面实例 ID。
2. 班组前端 SDK 轮询到 pending page action。
3. 前端执行 `qmssmp.teamArchive.search` handler，回填查询条件并刷新列表。
4. 前端通过 result 接口回传执行结果。
5. 调试弹窗最终展示 `SUCCESS`，并能看到返回数据；失败时展示 `FAILED` 或 `TIMEOUT` 以及错误信息。

如果需要先做 HTTP 级闭环验证，可以在 ReachAI 后端启动后执行：

```powershell
.\scripts\verify-page-action-loop.ps1 -BaseUrl http://localhost:18603
```

该脚本会模拟业务前端完成目录上报、embed token exchange、session 创建、平台 debug 请求、pending 拉取、result 回传和 debug result 查询。脚本通过只能证明协议和后端闭环可用；最终验收仍要打开真实班组页面，确认页面 handler 会回填筛选条件并刷新列表。

真实页面联调前置条件：

1. 班组前端必须能使用有效业务账号登录，进入 `/team-build/depart-management`。
2. `environment-config.ts` 中 `reachAi.apiBase` 必须指向当前版本 ReachAI 后端；如果本地旧服务占用 `18603`，应改用临时端口或重启当前代码到 `18603` 后再验收。
3. `qmssmp-gateway` 的 `reachai.registry.url` 必须指向同一个 ReachAI 后端，避免页面动作目录上报到 A 服务、embed token 换到 B 服务。
4. 页面动作调试时，业务页面、ReachAI 管理端和网关应处在同一套项目凭证 `projectCode/appKey/appSecret` 下。

## 10. 当前接入还需要补齐的内容

### 10.1 SDK 接入层

1. 为常用 DTO 字段补充更完整的参数语义，减少 Agent 对字段含义的猜测。
2. 明确哪些接口适合作为能力暴露，避免把所有 Controller 方法都默认暴露给 Agent。
3. 建立能力命名规范，例如 `domain.module.action` 或固定业务前缀。

### 10.2 网关 token broker

1. 当前网关 token 缓存是进程内缓存，生产多实例需要 Redis 或统一缓存。
2. 多业务系统接入后，需要把 `reachai.projects` 做成配置中心或数据库化管理。
3. 需要补充 token broker 的访问日志、失败日志和指标。
4. 需要明确 token broker 是否走独立白名单路径，以及和业务 JWT 鉴权的边界。

### 10.3 前端嵌入组件

1. 当前对话框组件在班组页面内，后续应抽成共享组件或 npm 包。
2. 不同页面应通过配置注册自己的 `actionKey` 和 handler，而不是复制组件。
3. 需要统一处理会话过期、token 过期、网络断开和重试。

### 10.4 Workflow Studio 配置

1. Page Action 节点已支持从项目页面动作目录选择已注册动作。
2. 发布校验已检查 `projectCode/pageKey/actionKey` 是否仍指向 `ACTIVE` 的页面动作目录项。
3. 项目详情已支持页面动作调试和引用查看，发布校验已覆盖参数 schema 必填项、动作白名单和项目凭证 Agent 白名单。

### 10.5 权限与审计

1. Agent 调用业务能力时，需要明确复用当前业务用户权限，还是使用系统服务权限。
2. 嵌入式会话、页面动作、工具调用需要能按业务用户、页面、Agent 查询审计日志。
3. 需要定义生产环境 Origin 白名单、Agent 白名单和项目密钥轮换流程。

## 11. 下一次接入其他业务系统的最小步骤

1. 在 ReachAI 中台创建项目，拿到 `projectCode/appKey/appSecret`。
2. 在业务后端启动模块引入 `reachai-spring-boot2-starter`。
3. 在能力声明模块引入 `reachai-capability-sdk`。
4. 在业务后端配置 `reachai.registry`、`reachai.project`、`reachai.capability`、`reachai.embed`。
5. 给目标业务接口增加 `@ReachCapability` 和必要的参数语义。
6. 在业务网关增加该项目的 `reachai.projects` 配置。
7. 在业务前端配置 `reachAi.projectCode`、`reachAi.agentId`、`reachAi.tokenPath`、`reachAi.apiBase`，不配置项目级 `appSecret`。
8. 在目标页面挂载嵌入式对话组件。
9. 在目标页面注册 Page Action handler。
10. 在 Workflow Studio 创建或编辑 Workflow，从页面动作目录选择 Page Action，并通过 Agent binding 暴露入口。
11. 启动全链路并验证注册、心跳、能力、token、对话、页面动作和审计。
