# ReachAI Control Service

`reachai-control-service` 负责公共 API/BFF、平台身份、页面工作台、嵌入入口、标准 Skill、MCP/A2A 和治理编排。只读写自有表；跨服务通过显式内部 API 协作，同库不改变所有权。

- 源码：`src/main/java/`，配置：`src/main/resources/application.yml`。
- 公共请求、身份和调用方向见[架构](../docs/architecture.md)与[公共路由](../docs/contracts/public-routes.md)。
- 精确表归属见[所有权清单](../docs/contracts/tables.md)，服务间调用见[内部 API](../docs/contracts/internal-apis.md)。

Control 随包分发 `ReachAI.AspNetCore` NuGet 制品，正常 Maven 构建直接使用仓库内的 nupkg 与摘要。修改 `reachai-dotnet/ReachAI.AspNetCore` 后，安装 `reachai-dotnet/global.json` 指定的 .NET SDK 10.0.403，在仓库根目录运行 `node scripts/pack-dotnet-sdk-artifacts.mjs`：该命令从当前源码重新构建包，并更新 Control 资源目录中的包、SHA-256 和源码摘要；将这三项与源码一起维护。源码摘要也覆盖固定 SDK 版本文件、打包与 NuGet 规范化脚本。`node scripts/pack-dotnet-sdk-artifacts.mjs --check` 检查包内容摘要及当前源码是否匹配，CI 在重新打包前执行此检查。Docker 发布从源码重新构建 NuGet 包，再打进 Control JAR；开发机仅构建 Java 时无需安装 .NET。发布包构建关闭调试符号，并规范化 NuGet 的 XML 行尾及 ZIP 元数据，以使不同系统产出一致；本地开发构建可使用默认调试设置。安全更新编译器时，同步更新 `global.json`、CI、Dockerfile 和脚本中的 SDK 固定版本；变更已发布的包时，同步升级 C# 项目、Control 制品支持类、打包脚本与 Dockerfile 路径中的包版本并重新生成包与摘要，不能用不同字节替换已发布的同一版本。

在仓库根目录运行相关模块测试：

```powershell
mvn -pl reachai-control-service -am test
```

本地构建/启动见[开发](../docs/development.md)，正式环境配置与验收见[运维](../docs/operations.md)。模块源码、配置和实际运行结果决定当前行为，本页不复制另一份端口、字段或部署状态表。
