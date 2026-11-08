# 五种语言的能力声明写法

只给被开发者勾选的方法加声明。能力名在项目内唯一；`title`、`description`、参数说明用中文。

## Java（Spring Boot，`reachai-capability-sdk`）

```java
import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import com.enterprise.ai.reach.sdk.annotation.ReachSideEffectLevel;

@ReachCapability(
        name = "erpReceivablePage",
        title = "查询应收款",
        description = "按客户和日期分页查询应收款",
        sideEffect = ReachSideEffectLevel.READ,
        businessIdentityRequired = true)
public PageResult<ReceivableRespVO> getReceivablePage(
        @ReachParam(name = "customerName", description = "客户名称", required = false) String customerName) {
    ...
}
```

- `@ReachCapability` 只放在方法上；`@ReachParam` 放在参数或 DTO 字段上；`@ReachOutput` 只放在出参 DTO 字段上。
- `sideEffect` 默认是 WRITE，查询方法一定要显式写 `READ`。
- 声明所在模块需要依赖 `reachai-capability-sdk`（接入时一般已加）。

## C#（ASP.NET Core，`ReachAI.AspNetCore`）

```csharp
[ReachCapability("erp.receivable.page", Title = "查询应收款", Description = "按客户和日期分页查询应收款",
    SideEffect = ReachSideEffect.Read, BusinessIdentityRequired = true)]
public Task<PageResult<ReceivableVo>> PageAsync(
    [ReachParam("customerName", Description = "客户名称", Required = false)] string? customerName)
```

- 所在类需要已通过 `AddCapabilitiesFrom<T>()` 注册；新类要补上这一行。
- `ReachSideEffect` 取值 `Read`、`Write`、`IdempotentWrite`、`Irreversible`。

## Python（FastAPI，`reachai_fastapi`）

```python
@reach.capability(name="erp.receivable.page", title="查询应收款", description="按客户和日期分页查询应收款",
                  side_effect="READ", business_identity_required=True)
async def page_receivables(customer_name: str | None = None):
    ...
```

- 必须在 `reach.mount(app)` 之前注册。

## Node.js（Express，`@reachai/node-sdk`）

```js
reach.capability({
  name: 'erp.receivable.page', title: '查询应收款', sideEffect: 'READ', businessIdentityRequired: true,
  inputSchema: { type: 'object', properties: { customerName: { type: 'string', description: '客户名称' } } },
  outputSchema: { type: 'object', properties: { total: { type: 'integer' } }, required: ['total'] },
  handler: async ({ customerName }) => receivableService.page({ customerName }),
})
```

- 输入、输出都要明确的 JSON Schema，不支持 `$ref`、递归和任意字典。
- handler 里用 `currentInvocation()` 拿到已验证身份，业务服务仍要校验数据权限。

## Go（net/http，`reachai`）

```go
err := client.RegisterCapability(reachai.Capability{
    Name: "erp.receivable.page", Title: "查询应收款", SideEffect: "READ", BusinessIdentityRequired: true,
    InputSchema: input, OutputSchema: output,
    Handler: func(ctx context.Context, args map[string]any) (any, error) { return svc.PageReceivables(ctx, args) },
})
```

## 共同规则

- 读操作 `READ`；会改数据 `WRITE`；不可撤回 `IRREVERSIBLE`。
- 需要知道当前员工的操作设 `businessIdentityRequired`，不要在能力里写死用户或绕过业务权限。
- 不要在启动时自动同步；声明好后通过任务里的 `SDK_SYNC` 同步一次。
