# 业务分类融入能力目录实施计划

> 后续决定：2026-10-13 用户要求移除业务分类合并的前后端逻辑，并要求 Java 业务方法目录改为平铺列表中的分类列、点击分类查看详情，见[实施基线 D17/D20](./业务方法与API重构实施基线.md)与[实施进度](./业务方法与API重构实施进度.md)。下文保留本批次实施与验证的历史记录；合并功能及 Java 目录分类分段折叠不再属于当前产品范围。

> 使用 superpowers:subagent-driven-development 按独立文件范围执行；当前工作区保留全部既有 WIP，不提交、推送、升级数据库或重启运行服务。

**目标：** 在同一能力目录浏览业务分类及成员、阅读和维护分类说明，取消独立分组清单，让分类成为查找能力的组织方式。

**架构：** Java/HTTP 资产和服务 owner 保持独立。分类仍复用 capability_scan_module 及既有成员、重命名、合并、说明与归属接口；新增 Capability 批量只读分类索引，避免按当前分页猜归属或逐项请求 enrichment。目录保留原 owner 的查询和分页，分类筛选仍在服务器分页前完成。

**技术栈：** Vue 3、Element Plus、现有 Glass Workbench 组件、TypeScript、Control/Capability、Vitest、Maven。

## 授权与产品边界

- 用户在本轮讨论后明确要求“按你的理解，修改”；D14 更新 D13 的目录筛选和独立分组管理呈现，关联 U1–U4。
- 分组面向用户称为“业务分类”；代码 module/类名等只作初始来源，不宣称自动识别真实业务模块。
- 分类不决定调用权限、调用顺序、Workflow 关系，不新增资产模型；保留人工显示名、合并、说明与成员关系。
- 分类少时在目录内按分类分段；分类较多时提供单层导航，不引入多层树。无分类成员明确显示“未分类”，读取失败/索引不完整不能当作未分类。
- 默认搜索覆盖全部能力；选择分类时，目录通过原 owner 的全量分类条件查询，返回恢复分类、搜索和页码。
- 总条数取正式查询；分类索引的成员数指当前项目该资产类型的完整分类成员，分页段的已展示数明确标记本页，不将当前页冒充完整分组。
- 多分类能力在全部目录中只显示一次并保留全部归属提示，选择任何真实所属分类均可找到；加入分类是附加归属，不冒充移动或删除原来源归属。

## 数据契约

```ts
type ClassificationIndex = {
  projectId: number
  subjectType: 'BUSINESS_METHOD' | 'HTTP_API'
  groups: Array<ScanModule & { memberCount: number }>
  memberships: Array<{ subjectId: number; groupIds: number[] }>
  ungroupedCount: number
}
```

`GET /api/business-capabilities/projects/{projectId}/classification/{subjectType}` 由 Control 做项目读取授权，Capability 解析正式 owner 和来源成员，批量查询分类及显式成员；不逐项读写其他服务表。未分类列表使用 `ungrouped=true`，与 `groupId` 互斥，必须指定已授权项目。

## 任务与验证

- [x] 后端：新增批量分类索引与未分类选择；覆盖同数字不同类型 ID、来源成员、显式成员、多分类去重、空组/未分类、跨项目与非法参数。复用已有分页前 ID 选择，H2 验证计数分页，Control 验证路径白名单及项目权限。
- [x] 目录：共享分类分段/导航及读取状态，移除旧分组下拉；两类目录接入同一组件，保留各自筛选、表格、详情和分页。测试失败重试、迟到响应、项目/账号切换、冷链接、返回和未知索引成员。
- [x] 就地整理：分类“⋯”提供说明与重命名；目录整理提供合并分类；成员可加入已有分类。复用 typed semantic 修订、模型选择及实际项目写权限；失败留输入、409 重读与未保存关闭/导航保护。
- [x] 入口：删除独立分组清单组件及导航；旧 groups 深链转到同类别目录并保留查询/目标分类。分类说明和来源/关系工具的返回入口统一。
- [x] 整合：独立代码审查，Capability 前端全套、受影响后端目标测试、范围/导航回归、生产构建、UI 守卫、Premium strict 与 diff 检查；桌面/390px 使用真实前端受控数据验证分类浏览/搜索/详情往返/就地整理。
- [x] 文档：同步 DESIGN、UX-CONTRACT、基线 D14、实施进度及验证记录；区分代码测试、受控浏览器、运行包与真实调用/真人验收。

## 实施命令

```powershell
# 前端（各写入行为先运行对应失败用例，再实现）
npx vitest run --config vitest.capability.config.ts --maxWorkers=2
npm run build
npm run check:business-capability-workbench-ui

# 后端使用 Corretto 17，运行分类、目录及 Control 资源目标测试
mvn -pl reachai-capability-service,reachai-control-service -am test -Dtest=CatalogClassificationServiceTest,CatalogGroupSelectionTest,CatalogResourcesConsoleControllerTest,BusinessMethodCatalogPersistenceTest,HttpApiCatalogAcceptancePersistenceTest -Dsurefire.failIfNoSpecifiedTests=false

git diff --check
```

输出集中在 `output/tasks/business-method-api/BMAPI-11/`。本次不发起真实说明生成、同步、业务调用或发布；现有运行包更新仍单独报告。

最终结果见[验证记录](../../output/tasks/business-method-api/BMAPI-11/验证记录.md)：前端 375 项、范围/布局 24 项、后端 97 项及构建/守卫通过。运行包更新和真实环境验证保留为后续事项，不将受控浏览器写入等同于真实服务写入。
