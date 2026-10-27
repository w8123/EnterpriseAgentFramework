<p align="center"><img src="ai-admin-front/public/reachai-logo-horizontal.svg" alt="ReachAI" width="300" /></p>

# ReachAI

面向 Java 企业系统的 AI 能力中台。把 AI 助手接入现有业务页面，也让员工在自己的 AI 工具中，以本人身份查询数据、填写表单和办理业务。

## 工作方式

```text
业务系统声明方法/API → 来源同步与接纳 → Workflow 编排与发布
                    → Agent 按意图选择 → 身份、权限、确认与运行审计
```

- 开发者接入系统和明确开放的操作，业务系统保留最终鉴权与事务。
- 运营人员配置 Agent、Workflow、知识与开放服务，按版本发布和评测。
- 员工从原页面或个人 AI 工具使用，写操作按当前策略确认或审批。
- 管理人员从运行、调用与审批记录核对真实使用结果。

## 快速体验

需要 Docker Compose v2。在仓库根目录执行：

```bash
cd deploy/quickstart
cp .env.example .env
docker compose up -d --build
```

PowerShell 用 `Copy-Item .env.example .env`。按模板配置模型与环境参数；打开芋道 `http://localhost:48080` 和 ReachAI `http://localhost:18080`，演示账号为 `admin / admin123`。

详见[本机体验](deploy/quickstart/README.md)。演示配置不直接用于生产，真实业务操作按系统身份和权限执行。

## 文档

| 目的 | 入口 |
| --- | --- |
| 理解产品与核心对象 | [产品](docs/product.md) |
| 源码开发、构建与验证 | [开发](docs/development.md) |
| 接入业务系统 | [系统接入](docs/integration.md) |
| 设计和运行 Agent / Workflow | [Workflow](docs/workflow.md)、[Agent](docs/agent.md) |
| 使用员工工具与开放服务 | [开放协议](docs/protocols.md) |
| 部署与业务验收 | [运维](docs/operations.md) |
| 查看全部核心主题 | [文档入口](docs/README.md) |

AI 编程工具先读 [AGENTS.md](AGENTS.md)。参与开发见 [CONTRIBUTING](CONTRIBUTING.md)。源码、配置与实际环境决定行为，当前限制见[支持边界](docs/status.md)。

平台由 Control / Embed Gateway、Runtime Host、Capability Catalog、Knowledge / Retrieval、Model Gateway 五服务组成；技术边界见[架构](docs/architecture.md)。

## 社区与许可

[学习交流群](docs/系统截图/ReachAI学习交流群.png) · [MIT License](LICENSE)
