# 架构契约

这里放会影响代码边界、路由归属、服务间调用和部署方式的事实源。根目录文档只讲产品主线；具体约束以本目录为准。

| 文档 | 用途 |
| --- | --- |
| [public-route-contracts.md](./public-route-contracts.md) | 前端和外部调用应使用的公共路由、冻结兼容 alias 和 retired route |
| [physical-split-route-ownership.md](./physical-split-route-ownership.md) | public route owning service 归属和迁移状态 |
| [internal-api-contracts.md](./internal-api-contracts.md) | 服务间 internal API 契约、owner/consumer 和前端禁用边界 |
| [service-table-ownership.md](./service-table-ownership.md) | 同库阶段的服务表所有权 |
| [backend-boundaries-and-naming.md](./backend-boundaries-and-naming.md) | 五服务边界、同库策略、命名规则和公共入口 |
| [physical-services-and-startup.md](./physical-services-and-startup.md) | 五服务启动、IDEA 配置、环境变量和验证入口 |
| [legacy-retirement.md](./legacy-retirement.md) | 旧 agent 主入口退场、兼容面生命周期和启动清单 |
