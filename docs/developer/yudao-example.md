# 芋道 ERP 接入实战

> 适合：想看一个真实系统完整接入过程的开发者。读完你会知道芋道 ERP 为了接入 ReachAI 改了哪些代码、每处改动对应前面哪一篇。

一键体验里的芋道就是按本篇接好的。芋道是 Spring Boot 2.7 + Vue 3 的开源后台，接入改动都放在两个补丁里，可以直接拿来对照。

- [后端补丁](../guides/yudao/yudao-reachai-integration.patch)：SDK 依赖、业务方法声明、身份桥、令牌解析、退出撤销。
- [前端补丁](../guides/yudao/yudao-ui-reachai-chat.patch)：在芋道布局中挂载助手和页面动作。

## 改动一览

| 改了什么 | 在哪 | 对应文章 |
| --- | --- | --- |
| 根 POM、ERP 模块、`yudao-server` 引入依赖并启用 ERP 模块 | `pom.xml`、`yudao-module-erp/pom.xml`、`yudao-server/pom.xml` | [Java 接入](java-starter.md) |
| 11 个 ERP 方法加 `@ReachCapability` | `yudao-module-erp/.../controller/admin/**` | [Java 接入](java-starter.md#第-3-步声明业务方法) |
| 放行 `/reachai/**` | `YudaoReachAiConfiguration` | [业务身份桥](business-identity.md#放行-reachai-路径) |
| 调用时还原芋道登录用户和租户 | `YudaoReachAiSecurityBridge` | [业务身份桥](business-identity.md#第-2-段身份桥调用业务方法时需要) |
| 助手换令牌时解析当前用户和角色 | `YudaoReachAiEmbedPrincipalResolver` | [业务身份桥](business-identity.md#第-1-段身份解析嵌入助手时需要) |
| 退出登录时撤销助手令牌（默认关闭） | `YudaoReachAiLogoutOutbox`、`sql/mysql/reachai-logout-outbox.sql` | [嵌入助手](embed-chat.md#第-3-步退出登录时) |
| 前端挂载助手、产品列表注册筛选动作 | 前端补丁 | [嵌入助手](embed-chat.md) |

## 后端：配置

```yaml
reachai:
  registry:
    url: http://reachai-control:18603
    enrollment-token: ${REACHAI_REGISTRY_ENROLLMENT_TOKEN:}
    credential-store-path: /app/data/reachai-credentials.properties
  project:
    code: yudao-erp
    name: 芋道 ERP
    base-url: http://yudao-server:48080
    environment: test
  capability:
    scan-mode: ANNOTATED_ONLY
    scan-packages:
      - cn.iocoder.yudao.module.erp.controller.admin
```

芋道是多租户的，`/reachai/**` 不在它的 `/admin-api` 下，所以还要把这条路径加进 `yudao.tenant.ignore-urls`，由身份桥自己设置租户。

## 后端：声明业务方法

芋道直接在 Controller 方法上加注解，原有的 `@PreAuthorize` 权限检查照常生效：

```java
@ReachCapability(name = "erpStockPage", title = "查询产品库存",
        description = "按产品编号、仓库编号分页查询 ERP 产品库存，返回产品名称、仓库名称、库存数量和单位。"
                + "只能按编号过滤，按名称查询时先用 erpProductPage 找到产品编号。",
        domain = "erp", module = "stock", tags = {"库存", "列表查询"},
        sideEffect = ReachSideEffectLevel.READ, businessIdentityRequired = true)
@GetMapping("/page")
@PreAuthorize("@ss.hasPermission('erp:stock:query')")
public CommonResult<PageResult<ErpStockRespVO>> getStockPage(@Valid ErpStockPageReqVO pageReqVO) { ... }
```

注意 `description` 的写法：告诉 AI 这个方法只能按编号查，要按名称查得先调哪个方法。AI 就是靠这句话学会「先查产品、再查库存」的。

开放的 11 个方法包括：查询产品（分页、精简列表）、查询仓库、查询库存、查询客户、新建客户、查询供应商、查询采购订单、查询销售订单、采购和销售统计。

## 后端：身份桥

`YudaoReachAiSecurityBridge` 做了这几件事：

1. 从调用身份里取芋道的租户编号和用户编号，缺任何一个就拒绝（返回 403「调用需要有效的芋道用户和租户身份，请通过业务页面发起」）。
2. 校验租户有效，在目标租户内查用户，用户不存在或被停用就拒绝。
3. 构造芋道的 `LoginUser`，设置到 Spring Security 上下文和芋道的租户上下文。
4. 执行业务方法，最后恢复原来的上下文。

`YudaoReachAiEmbedPrincipalResolver` 从芋道当前登录态取用户，返回用户编号、昵称、启用的角色编码，以及租户编号（放在 `attributes.tenantId`）。角色编码会用在 ReachAI 的「调用权限」里。

## 前端

前端补丁在芋道布局里加载 ReachAI 嵌入 SDK，通过环境变量配置：

| 变量 | 含义 |
| --- | --- |
| `VITE_REACHAI_BASE_URL` | ReachAI 地址 |
| `VITE_REACHAI_AGENT_ID` | Agent 编码，例如 `erp-assistant` |
| `VITE_REACHAI_TOKEN_URL` | 可选，换令牌接口的完整地址。默认是芋道后端的 `/reachai/embed/token` |

两个变量都不配时不显示助手。改了 Vite 环境变量要重新构建前端。

补丁还给 ERP 产品列表注册了一个「按名称筛选」的页面动作：清空分类、回到第一页、调用芋道已有的查询。员工说「只看罗技的产品」，AI 直接在列表上筛选。

## 应用补丁

1. 在芋道根 POM 和 `yudao-server/pom.xml` 中启用 ERP 模块。
2. 在芋道代码目录执行 `git apply --check <补丁路径>`，确认没有冲突再 `git apply`。补丁基于特定的上游版本，你的芋道版本不同时可能要手工合并。
3. Starter 和 Capability SDK 用 ReachAI 项目接入工作台给出的版本。
4. 打包：`mvn -pl yudao-server -am '-Dmaven.jar.forceCreation=true' package`，确认新包里的 Starter 是新版本。

## 芋道特有的坑

- **业务码 403 但 HTTP 200。** 芋道的全局异常处理把权限错误包成 `CommonResult`，HTTP 状态仍是 200。ReachAI 会识别 `CommonResult` 的业务码并判为失败，你的系统如果也这样包装，要确认能被识别。
- **Studio 试运行、Agent 调试调不通。** 芋道的方法都声明了需要员工身份，用平台账号或项目测试身份调用会被身份桥拒绝。这是预期行为，在芋道页面的助手里验证。
- **升级 ReachAI 后芋道助手 403。** 新版 ReachAI 要求换令牌请求正文签名，芋道后端里的 Starter 要同步升级、重新打包。

## 验收清单

1. 项目接入工作台 6 项全部通过。
2. 员工在芋道助手里查库存，结果和芋道库存页面一致。
3. 员工让 AI 新建客户：助手里先确认；在自己的 AI 工具里走审批，通过后芋道里新客户的创建人是这个员工。
4. 非白名单地址、无签名、无令牌、角色无权限的调用都被拒绝。
5. 每次调用都能在 ReachAI 运行中心找到。

## 下一步

- [在自己电脑上体验 ReachAI](../start/try-in-10-minutes.md)：一条命令跑起已接好的芋道
- [开发者常见问题](troubleshooting.md)
