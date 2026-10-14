# Open-Meteo 官方来源与本轮明确契约

当前市场提供发现、目录读取、项目选择及停用，没有通用的官方规范导入/刷新接口。本轮不新增一个未经设计的通用导入器。已有 v1 的规范摘要、响应媒体与状态为空，真实选择 project36 时收到 HTTP400 / API_MARKET_SOURCE_FACTS_REQUIRED；正确保留该拒绝。

官方来源是 [Open-Meteo Forecast OpenAPI](https://github.com/open-meteo/open-meteo/blob/930063f12047363861f775c685c16a98b52dbfe4/openapi/forecast.yml)，已读取固定 commit 的完整规范。原始文档字节 SHA256 为 `512e29bca2afb06a088cc27fae5c6793a05e272c6404a67e3632cecb87f8516f`。

新增不可变版本 `official-930063f12047-scalar-query-20261009` 仅声明官方 GET `/v1/forecast` 的标量查询子集：必填 query `latitude` 与 `longitude`，保持官方 string 类型；无需认证，成功状态200、application/json对象响应来自规范。当前首批调用契约不支持数组请求参数，未选择 optional current/hourly/daily/minutely_15 数组参数。响应声明保留官方根位置与时区元数据，允许其他响应字段；本轮不宣称查询到具体天气变量。固定实验参数52.52/13.41与任何用户位置无关。

完整规范与可执行子集明确区分。[子集来源快照](./open-meteo-forecast-scalar-20261009.json) 记录官方URL、commit、原始字节摘要及参数范围，规范化子集摘要为 `570d8f8258ee4b9d187cb9d2f3416ef098f75c2320c1406f0eee1600781f51fc`，作为新版本spec_hash。不是为旧v1补造一个hash。

新库 `sql/initV2.sql` 与 [窄增量](../../../sql/upgrade-20261009-open-meteo-official-version.sql) 仅INSERT本条公开目录的新版本和新Operation，二遍执行幂等；不更新provider、entry、旧v1、旧Operation、既有project27绑定或凭据。落现库前已只读回查该旧绑定，旧v1仍保持原事实。该增量不代表项目接纳、连接配置、ACL、调用或验证成功；这些必须继续走当前公开管理接口。

本轮的实际接入/receipt/VERIFY结果与异常保存在 `output/tasks/qmssmp-e2e-20261009/api-market-*`，以完成后的现场报告为准。
