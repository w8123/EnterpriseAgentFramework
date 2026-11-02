# 开发

先读 [AGENTS.md](../AGENTS.md)，再根据[文档入口](README.md)定位相关主题。按实际代码、配置和接口处理问题，不从任务记录推断服务、数据库或浏览器的当前状态。

## 构建与启动

使用 JDK 17、Maven、Node.js 和 npm；具体依赖与版本取自 `pom.xml`、`ai-admin-front/package.json`。安装和启用基础设施见[运维](operations.md)。

```powershell
mvn -pl reachai-control-service,reachai-runtime-service,reachai-capability-service,reachai-knowledge-service,reachai-model-service -am -DskipTests compile
```

本地一条命令启动用 `./dev.sh up`（Windows：`.\dev.ps1 up`），它生成 `reachai.env`、用 Docker 起 MySQL、编译并按 Model → Knowledge → Capability → Runtime → Control 启动服务和管理端。也可以使用仓库 `.run/` 配置或各模块的 `mvn spring-boot:run`：五个服务都会读取当前目录或上一级的 `reachai.env`（同名环境变量优先），空库自动按 `sql/initV2.sql` 建表，本机密钥首次启动生成到 `reachai.env` 旁边的 `.reachai/secrets.properties`。只把需要用户决定的项写进 `reachai.env.example`；很少调整的参数直接写成 `application.yml` 的默认值。

```powershell
Set-Location ai-admin-front
npm ci
npm run dev
```

Vite 默认端口 5200。浏览器 API 经 Control，代理规则以 [vite.config.ts](../ai-admin-front/vite.config.ts)为准。

官网源码位于开发仓库的 `reachai-website/`，本地开发、演示地址配置与静态构建见开发库中该模块的 README。开源仓库不分发官网源码；核心服务与管理端无需构建官网。

## 开源同步

开发库保留官网源码与版本历史；开源库排除 `reachai-website/`。排除目录统一维护在 [open-source-policy.json](../scripts/open-source-policy.json)，开源库的 `.gitignore` 作为防误添加措施。开发库不忽略官网。

先在开发库提交待同步代码及同步规则，再在干净的开源库检出中运行：

```powershell
node scripts/sync-open-source.mjs --source ../EnterpriseAgentFramework-dev --ref dev
node scripts/sync-open-source.mjs --source ../EnterpriseAgentFramework-dev --ref dev --apply
node scripts/check-open-source.mjs
```

首条命令仅预览；`--apply` 将过滤后的文件差异写入开源库工作区和暂存区，不提交、不推送。只读取指定修订的已提交文件，开发库未提交改动保持原样。同步会保留开源专用忽略规则；目标库有未提交文件、源修订缺少同步规则或两库策略不一致时拒绝执行。

审阅暂存差异、运行相关检查后，在开源库单独提交。同步不合并开发库提交历史，不使用直接 merge、pull 或整段 cherry-pick 导入含官网的历史。已进入待发布历史的官网必须在推送前从该历史移除，追加删除提交仍会公开旧版本。

每个开源检出启用推送检查；开发库不要启用此开源钩子：

```powershell
git config --local core.hooksPath .githooks
node --test scripts/open-source-sync.test.mjs
```

[推送钩子](../.githooks/pre-push)检查本次推送的每个分支和标签的完整历史，拦截曾包含排除目录的提交。开源仓库 CI 同样检查文件树与历史；浅克隆须先补齐历史。同步规则不替代提交或推送授权。

## 验证

| 改动 | 最小验证 |
| --- | --- |
| 后端 | 对应 Maven 模块的目标测试；涉及边界时运行架构检查 |
| 前端 | 相关行为测试、`npm run build`（包含未使用本地符号与参数检查），并核对真实页面的交互与布局 |
| 体验初始化 Agent | `python scripts/test-quickstart-agent-revisions.py`，使用隔离 API 夹具，不执行真实初始化 |
| 数据库 | 同步 `sql/initV2.sql`；已有环境需要时提供 upgrade SQL，执行后回读结构与中文 |
| 文档与规则 | 文档链接检查和 `git diff --check` |

```powershell
node scripts/verify-architecture.mjs
node --test scripts/check-documentation.test.mjs
git diff --check
```

服务启动后再运行 `node scripts/check-physical-service-smoke.mjs`；更新运行包后用 `node scripts/check-local-service-artifact-freshness.mjs` 核对实际制品。命令执行成功只证明其检查范围，不能代替业务验收。

## 排错顺序

1. 用同一接口、身份、数据或错误签名复现问题。
2. 确认路由、owning service、运行制品和配置，再检查代码与数据库。
3. SQL 缺表/缺列先比对 `initV2.sql` 和适用升级；不要直接改 Entity 掩盖结构漂移。
4. 前端核对 API 状态、响应体、作用域与迟到响应；空结果、无权限、加载失败和服务不可用分别处理。
5. 发布或调用失败核对来源契约、执行修订、凭据、ACL 和已发布 pin；不把显示名称或曾经成功当作当前有效证据。

浏览器验证使用可用的真实浏览器工具；隔离夹具注明范围。数据库检查优先使用只读连接，不将数据检查扩大为写入或导出。Windows 文件锁可能影响正在运行的 JAR；只操作已确认归属的进程与制品。

## 文档维护

每个主题只保留当前规则、必要示例、源码入口和明确限制。删除重复说明、历史状态、测试流水、讨论过程与旧执行提示词；不创建归档。新增或移动文档同步引用和检查器，仍需验证的条件写入[当前边界](status.md)，不保留第二套计划。
