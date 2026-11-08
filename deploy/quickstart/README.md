# Docker 体验

在本机启动 ReachAI 和已接入的芋道 ERP，体验嵌入对话、员工 AI 工具连接与写审批。需要 Docker Compose v2，并能访问构建所需依赖仓库。

## 启动

在仓库根目录执行：

```bash
cd deploy/quickstart
cp .env.example .env
docker compose up -d --build
```

Windows PowerShell 用 `Copy-Item .env.example .env` 复制配置。按 `.env.example` 填写环境参数；对话需要模型凭据，员工工具直接调用已开放业务能力不要求平台替其模型付费。

| 地址 | 用途 | 演示账号 |
| --- | --- | --- |
| `http://localhost:48080` | 芋道 ERP 与 AI 面板 | admin / admin123 |
| `http://localhost:18080` | ReachAI 管理端 | admin / admin123 |

这些账号和配置仅用于本机演示。服务、端口、持久卷与资源限制以本目录 Compose 文件为准，正式部署用一条命令安装，见[安装 ReachAI](../../docs/admin/install.md)。

## 使用

登录芋道，从右下角 AI 面板查询库存；在“在我的 AI 工具中使用”中生成个人连接并复制到自己的工具。写操作到 ReachAI 的 **MCP 开放 → 写操作审批** 处理，结果从调用记录和运行中心核对。

模型参数在启动后修改时，按初始化服务要求重跑配置；检查 `docker compose logs -f reachai-init` 与各服务日志，不把容器启动当作业务初始化完成。

Agent 初始化使用[共同修订协议](../../docs/protocols.md)：先读取当前修订，保存后使用返回的新修订发布；冲突或响应无法确认时停止，不自动重新读取并覆盖。核对实际配置后再决定是否重新执行初始化。Control 与 Runtime 必须同时支持该协议，旧服务不会回退到无修订写入口。

## 停止与排错

```bash
docker compose ps
docker compose logs --tail=100
docker compose down
```

停止时默认保留数据卷；需要重新初始化时先确认数据归属和影响，不把删除卷作为常规重试。更详细的接入和验收见[芋道 ERP 接入实战](../../docs/developer/yudao-example.md)。
