# 芋道 ERP 接入 ReachAI 实施步骤

> 2026-10-16 在宝塔测试环境按真实用户流程完成：芋道 `https://yudao.w8123.com`（ruoyi-vue-pro master，只开系统管理、基础设施、ERP），ReachAI `https://reachai.w8123.com`。
> 芋道侧完整改动见 [yudao-reachai-integration.patch](./yudao/yudao-reachai-integration.patch)（基于 YunaiV/ruoyi-vue-pro `ac022b1`）；前端对话入口见 [yudao-ui-reachai-chat.patch](./yudao/yudao-ui-reachai-chat.patch)（基于 yudaocode/yudao-ui-admin-vue3 `050bac1`）。

芋道是多租户、Spring Security + `@PreAuthorize` 的典型国产 Java 后台，适合验证 ReachAI 对“已有业务系统”的接入体验。

## 1. ReachAI 侧：签发接入令牌

项目中心 → 项目管理 → 接入项目 → SDK 接入，填写：

| 字段 | 值 |
| --- | --- |
| 项目名称 / 编码 | 芋道 ERP / `yudao-erp` |
| Base URL | ReachAI 服务端能访问到的芋道地址。同机部署用 `http://127.0.0.1:48080` |

生成 72 小时一次性令牌，复制 Starter 配置。令牌只用于首次注册，注册成功后从配置里删掉。

## 2. 芋道侧：引入 SDK 和 Starter

1. ReachAI 仓库执行 `mvn -pl reachai-spring-boot2-starter -am install -DskipTests`，把 SDK 装到本地仓库。
2. 根 `pom.xml` 打开 `yudao-module-erp`；`yudao-server/pom.xml` 加 `yudao-module-erp` 和 `reachai-spring-boot2-starter`。
3. `yudao-module-erp/pom.xml` 加 `reachai-capability-sdk`，并设置 `<maven.compiler.parameters>true</maven.compiler.parameters>`。
   不开 `-parameters` 时，业务方法参数在 ReachAI 里显示成 `arg0`。
4. 配置（放在部署环境的 `application-local.yaml`，令牌和凭据文件不要进仓库）：

```yaml
reachai:
  registry:
    url: https://reachai.w8123.com
    enrollment-token: ${REACHAI_REGISTRY_ENROLLMENT_TOKEN:}   # 首次注册后删除
    credential-store-path: /www/wwwroot/yudao/config/reachai-credentials.properties
  project:
    code: yudao-erp
    name: 芋道 ERP
    base-url: http://127.0.0.1:48080
    environment: test
  capability:
    scan-mode: ALL_CONTROLLERS          # 同时把 Controller 同步成 HTTP API；只要业务方法时用 ANNOTATED_ONLY
    scan-packages:
      - cn.iocoder.yudao.module.erp.controller.admin
yudao:
  reachai:
    default-tenant-id: 1
    default-user-id: 1                 # 控制台试调用 / Studio 试运行不带业务用户时使用的芋道账号
  tenant:
    ignore-urls:
      - /jmreport/*
      - /reachai/**
```

## 3. 芋道侧：放行 Starter 入口并建立身份

`/reachai/**` 由 Starter 自己校验项目签名和调用令牌，芋道的登录拦截和租户校验要放行它（不是取消鉴权）。
业务方法真正执行时，需要芋道的租户和登录用户，否则 `@PreAuthorize`、数据权限和多租户插件都会失败。

`yudao-server` 新增两个类（见 patch）：

- `YudaoReachAiConfiguration`：`/reachai/**` permitAll，注册安全桥。
- `YudaoReachAiSecurityBridge implements ReachAiSecurityContextBridge`：把 ReachAI 验签后的调用身份映射为芋道 `LoginUser` 和租户。
  映射顺序：调用令牌里的外部用户编号（芋道用户 id）→ 同名芋道账号 → `default-user-id`；都找不到就拒绝。

## 4. 芋道侧：声明业务方法

直接在已有 Controller 方法上加 `@ReachCapability`，在查询 VO 字段上加 `@ReachParam`。本次声明 11 个：

| 名称 | 说明 | 读写 |
| --- | --- | --- |
| `erpProductPage` / `erpProductSimpleList` | 查询产品 / 全部产品 | 读 |
| `erpStockPage` | 按产品、仓库查库存 | 读 |
| `erpWarehouseSimpleList` | 全部仓库 | 读 |
| `erpCustomerPage` / `erpCustomerCreate` | 查询客户 / 新建客户 | 读 / 写 |
| `erpSaleOrderPage` | 查询销售订单 | 读 |
| `erpSupplierPage` / `erpPurchaseOrderPage` | 查询供应商 / 采购订单 | 读 |
| `erpSaleSummary` / `erpPurchaseSummary` | 销售 / 采购统计概览 | 读 |

description 里写清“按名称查库存要先用 erpProductPage 换产品编号”这类调用顺序，Agent 规划会用到。

## 5. ReachAI 侧：同步、评审、试调用

1. 芋道启动后项目自动注册，凭据写入 `credential-store-path`。删掉配置里的 enrollment-token。
2. 项目详情 → 同步业务方法和 HTTP API。本次得到 11 个业务方法、146 个 HTTP API。
3. 只读的新方法自动接纳；写方法（新建客户）和参数变化进入待处理，人工 APPLY。
4. 业务能力 → Java 业务方法 → 试调用。`erpStockPage` 传 `{"pageReqVO":{"productId":1}}` 返回两个仓库的库存。

## 6. ReachAI 侧：HTTP API

1. 业务能力 → HTTP API，接纳需要的接口（例如 `GET /admin-api/erp/stock/page`）。
2. 芋道 token 默认 30 分钟过期，不适合当长期凭据。做法：芋道「系统管理 → OAuth 2.0 → 应用管理」建一个应用（本次 `reachai-erp`，授权类型 password + refresh_token，访问令牌 90 天），用密码模式换 token。
3. ReachAI 新建项目凭据，类型 `BEARER`。芋道在没有 `tenant-id` 头时使用登录用户所在租户，所以只需 Bearer。
4. API 连接的服务地址填公网地址 `https://yudao.w8123.com`。`127.0.0.1` 会被 Runtime 出站策略拒绝（防 SSRF）。
5. 试调用 `pageNo=1&pageSize=3` 成功。

## 7. 嵌入式对话：在芋道里直接用 ERP 助手

2026-10-17 起，芋道用户登录后右下角出现 ReachAI 对话入口，以自己的芋道账号和角色使用 ERP 助手。

**芋道后端**：`YudaoReachAiEmbedPrincipalResolver`（见 patch）实现 Starter 的 `ReachAiEmbedPrincipalResolver`，从芋道登录态取用户、租户和角色编码。提供这个 Bean 后，Starter 自动暴露 `POST /reachai/embed/token`，用当前登录用户换短期嵌入令牌；未登录返回 401。`/reachai/**` 在芋道是 permitAll，但芋道的令牌过滤器仍会解析 `Authorization`，所以解析器能拿到登录用户。

**芋道前端**：`src/layout/components/ReachAiChat/index.vue` 挂在 `Layout.vue`。脚本直接引用 ReachAI 站点的 `/sdk/embed-chat/reachai-chat-embed.umd.js`，芋道不打包 SDK。`.env.prod` 配置：

```env
VITE_REACHAI_BASE_URL=https://reachai.w8123.com
VITE_REACHAI_AGENT_ID=8e747bd7c1f1        # ERP 助手的 Agent ID
```

**Nginx**（芋道站点）：把 `/reachai/embed/` 转给芋道后端，其他 `/reachai/**` 不对公网开放：

```nginx
location ^~ /reachai/embed/ {
    proxy_pass http://127.0.0.1:48080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
```

**ReachAI**：打开 Agent「ERP 助手」→「嵌入」，在业务系统 `yudao-erp` 上设置来源 `https://yudao.w8123.com`，打开「允许本 Agent」，再用「检查能否打开」确认每一项都通过。来源白名单同时决定 ReachAI 对 `/api/embed/**` 的 CORS 放行，不需要改服务器配置。

## 8. 本次发现并修复的 ReachAI 问题

| 问题 | 影响 | 修复 |
| --- | --- | --- |
| 业务响应只认 `code=200` | 芋道等用 `code=0` 表示成功的系统，所有调用都被判为“查询失败” | `BusinessResponseEnvelope` 同时接受 `0` 和 `200` |
| Starter 扫 HTTP API 时忽略 Spring MVC 的路径前缀 | 芋道 `addPathPrefix("/admin-api")` 下的 146 个接口路径全部缺前缀，调用 404 | 从 `RequestMappingHandlerMapping.getPathPrefixes()` 取前缀 |
| 调用权限种子给的是角色 `admin`，平台管理员角色是 `PLATFORM_ADMIN` | 全新环境管理员试调用任何业务方法都是 403 | `initV2.sql` 补种子；已有库按 [SQL 说明](../../sql/README.md) 执行适用的近期合并升级（管理员 ACL 见段落 04） |
| SDK 接入令牌的 Starter 配置把 registry url 写成 `localhost:18603` | 照抄配置的业务系统注册不上 | 默认填当前 ReachAI 访问地址 |
| HTTP API 连接保存失败时只提示“当前条件已变化，请刷新后重试” | 认证方式不匹配、接口不支持试调用等真实原因被吞掉 | Control 透传 Runtime 的 `HTTP_API_*` 固定原因 |

## 9. 仍待改进

- HTTP API 连接按单个接口配置。芋道 146 个接口要一个个配服务地址和凭据，缺少按项目/环境复用。
- 带数组查询参数的接口（芋道几乎所有分页接口都有 `createTime[]`）不能在控制台和 Workflow 里调用，提示“首批仅支持有明确类型的标量参数”。
- 业务方法的单个 DTO 参数在契约里是 `pageReqVO.xxx` 嵌套形式，Starter 调用时其实也接受扁平参数。
