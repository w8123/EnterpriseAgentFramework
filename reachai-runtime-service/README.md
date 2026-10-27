# ReachAI Runtime Service

`reachai-runtime-service` 负责Agent、Workflow、GraphSpec、执行、Trace、评测、自动化与托管执行控制面。只读写自有表；跨服务通过显式内部 API 协作，同库不改变所有权。

- 源码：`src/main/java/`，配置：`src/main/resources/application.yml`。
- 公共请求、身份和调用方向见[架构](../docs/architecture.md)与[公共路由](../docs/contracts/public-routes.md)。
- 精确表归属见[所有权清单](../docs/contracts/tables.md)，服务间调用见[内部 API](../docs/contracts/internal-apis.md)。

在仓库根目录运行相关模块测试：

```powershell
mvn -pl reachai-runtime-service -am test
```

本地构建/启动见[开发](../docs/development.md)，正式环境配置与验收见[运维](../docs/operations.md)。模块源码、配置和实际运行结果决定当前行为，本页不复制另一份端口、字段或部署状态表。
