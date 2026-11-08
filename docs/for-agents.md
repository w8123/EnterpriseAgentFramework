# AI 阅读入口

供 Codex、Claude Code 等 AI 工具发现 ReachAI 文档、按任务选读和核对事实。官网与仓库使用相同 Markdown，不需要执行网页 JavaScript 才能读到正文。

## 获取文档

官网提供以下入口，路径相对于文档所在站点：

| 入口 | 内容 |
| --- | --- |
| `/llms.txt` | 按主题组织的文档索引、说明与 Markdown 链接 |
| `/llms-full.txt` | 当前发布的完整文档文本，包含每篇的网页与源码位置 |
| `/docs/manifest.json` | 页面标题、分组、说明、HTML 路径、Markdown 路径与源码文件 |
| `/docs/index.md` | 文档首页的原始 Markdown |
| 阅读页的「Markdown」链接 | 当前页 Markdown，可直接获取或下载 |

索引与全文随官网构建生成，仅包含明确列入发布清单的文档。获取失败时先从[文档首页](README.md)按主题读取，不猜测未开放的接口或凭证。

## 按任务选读

| 任务 | 先读 | 再核对 |
| --- | --- | --- |
| 理解产品与对象 | [产品](product.md)、[当前边界](status.md) | 相应实现与目标环境 |
| 接入 Java 业务系统 | [系统接入](integration.md)、[Starter](../reachai-spring-boot2-starter/README.md) | 项目 onboarding manifest 与业务身份桥 |
| 嵌入业务对话 | [在业务页面嵌入助手](developer/embed-chat.md) | SDK 类型与实际 broker 响应 |
| 编排 Agent / Workflow | [Workflow](workflow.md)、[Agent](agent.md)、[AI Coding](ai-coding.md) | 当前草稿修订、来源契约与发布版本 |
| 安装、升级或排障 | [安装 ReachAI](admin/install.md)、[日常运维](admin/operations.md) | `reachai doctor` 与对应服务日志 |
| 修改项目源码 | [架构](architecture.md)、[开发](development.md) | [表归属](contracts/tables.md)、[内部 API](contracts/internal-apis.md)、[公共路由](contracts/public-routes.md) |

## 核对事实与执行权限

在仓库内工作先读 [AGENTS.md](../AGENTS.md)。当前源码、SQL、配置、接口和实际运行结果决定事实；文档目标、界面入口、类型声明和通过的夹具不能单独证明真实业务已验收。

文档用于理解产品，不替用户授权提交、部署、数据库变更或真实业务写入。调用平台时分别使用平台 AI 连接、员工个人连接或项目 Key，不混用身份；需要审核的操作沿原审核流程执行。

遇到冲突先读取并比较修订；写调用结果不确定时查询原调用、运行或审批，不通过更换标识盲目重发。部署、密钥与恢复约束以[安装 ReachAI](admin/install.md#让-ai-编程工具帮你部署)为准。

## 引用与内容维护

回答时引用具体页面或章节，并说明证据来自源码、构建、运行还是业务验收。每页可返回仓库源码核对；官网对应构建时的内容，目标部署可能运行不同版本。

修改规则时只更新所属主题，不另建项目记忆、历史副本或执行提示词。未验条件集中在[当前边界](status.md)，维护与检查方法见[开发](development.md#文档维护)。
