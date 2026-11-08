# 业务身份桥：让 AI 以员工本人身份办事

> 适合：开发者。读完你会明白 ReachAI 怎么把「谁在问」传到业务系统，并能写出业务系统这一侧的两段代码。

## 为什么需要它

员工张三在助手里问「我负责的客户有哪些」，业务系统要按张三的权限查，而不是按某个管理员账号查。ReachAI 自己不知道张三在业务系统里能看什么，所以要由业务系统告诉它「当前是谁」，再在执行时把这个人还原回来。

这件事分成两段，各由一个接口负责：

```text
员工在业务页面打开助手
   │  ① 身份解析：业务后端从登录态里取出「张三，租户 1，角色 sales」
   ▼
ReachAI 签发短期令牌，AI 开始干活
   │  ② 身份桥：调用业务方法时，令牌里的张三被还原成业务系统的登录用户
   ▼
业务方法按张三的数据权限执行
```

员工在自己的 Codex 里用 MCP 时，第 ① 步换成「员工在业务系统里生成个人连接」，第 ② 步完全一样。

## 三条铁律

1. **身份只能来自业务系统服务端已验证的登录态。** 不能用浏览器请求里传来的用户 ID、角色、租户。
2. **身份缺失就拒绝。** 不能把「没有身份」默认当成管理员或某个公共账号。
3. **业务系统保留最终的数据权限和事务校验。** ReachAI 传身份，不替你判断张三能不能看某条订单。

## 第 1 段：身份解析（嵌入助手时需要）

Java 实现 `ReachAiEmbedPrincipalResolver`。Starter 提供 `POST /reachai/embed/token`，业务页面上的助手调这个接口换令牌，Starter 会调用你的解析器：

```java
@Component
public class MyEmbedPrincipalResolver implements ReachAiEmbedPrincipalResolver {

    @Override
    public ReachAiEmbedPrincipal resolve(String agentId) {
        LoginUser user = SecurityUtils.getLoginUser();   // 业务系统自己的登录态
        if (user == null) {
            return null;                                 // 未登录，Starter 返回 401
        }
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("tenantId", String.valueOf(user.getTenantId()));
        return new ReachAiEmbedPrincipal(
            String.valueOf(user.getId()),    // 业务系统里的用户 ID
            String.valueOf(user.getId()),    // 全局用户 ID，没有统一身份时同上
            user.getNickname(),              // 显示名
            user.getRoleCodes(),             // 角色编码，用于 ReachAI 的「调用权限」
            attributes);
    }
}
```

返回的角色编码会用在管理人员配置的 [调用权限](../manager/permissions.md) 里，比如「只有 sales 角色能让 AI 新建客户」。

想让员工退出登录后助手令牌立刻失效，可以再传第六、七个参数：业务登录会话的不可逆标识和过期时间，并在退出时调用撤销接口。见 [在业务页面嵌入助手](embed-chat.md#第-3-步退出登录时)。

## 第 2 段：身份桥（调用业务方法时需要）

Java 实现 `ReachAiSecurityContextBridge`。平台每次调用业务方法，Starter 验签后把身份放在 `context` 里交给你，你建立登录上下文、执行、再恢复原状：

```java
@Component
public class MySecurityBridge implements ReachAiSecurityContextBridge {

    @Override
    public Object runWith(ReachAiInvocationContext context, Invocation invocation) throws Exception {
        String userId = context.getExternalUserId();
        String tenantId = context.getTenantId();
        if (userId == null || tenantId == null) {
            throw new AccessDeniedException("需要有效的业务用户身份");
        }
        User user = userService.getEnabledUser(tenantId, userId);
        if (user == null) {
            throw new AccessDeniedException("当前身份无法映射到可用用户");
        }
        SecurityContext old = SecurityContextHolder.getContext();
        try {
            SecurityContextHolder.setContext(buildContextFor(user));  // 建立业务系统的登录上下文
            return invocation.proceed();                              // 执行业务方法
        } finally {
            SecurityContextHolder.setContext(old);                    // 一定要恢复
        }
    }
}
```

`context` 里还有 `getCapabilityName()`、`getAgentId()`、`getTraceId()`，建议写进日志，出问题时能和 ReachAI 运行中心对上。

完整的真实例子见芋道补丁里的 `YudaoReachAiSecurityBridge` 和 `YudaoReachAiEmbedPrincipalResolver`：[芋道 ERP 接入实战](yudao-example.md)。

其他语言的 SDK 里，这两段分别叫 `PrincipalResolver` 和 `SecurityBridge`，意思一样，见 [其他语言 SDK](other-sdks.md)。

## 放行 /reachai 路径

`/reachai/**` 由 Starter 验签和验令牌保护。业务系统的登录拦截（Spring Security、Shiro、自定义 Filter）要放行这条路径，否则平台的调用会被业务系统当成「未登录」挡掉。

放行的意思是「交给 Starter 鉴权」，不是取消鉴权。

## 记得声明 businessIdentityRequired

业务方法里只要用到了「当前用户」，就在声明里加 `businessIdentityRequired = true`。平台看到这个标记，会直接拒绝没有员工身份的调用，比如：

- Workflow Studio 里的「只读试运行」（用的是项目测试身份）；
- Agent 调试页（用的是你的平台账号）；
- 自动化任务（用的是自动化服务账号）。

这些地方调不通是**预期行为**，不是 bug。这类方法要在业务页面的助手里、或员工自己的 AI 工具里，用真实员工账号验证。

## 怎么验证

1. 用两个权限不同的业务账号，在业务页面的助手里问同一个问题，结果应该不同，并且和各自在业务系统页面上看到的一致。
2. 退出登录后，助手应该提示重新登录，不能继续查。
3. 去 ReachAI「运行中心」打开这次运行，能看到调用的业务方法和执行身份。

## 遇到问题

**调用返回「需要有效的业务用户身份」。** 说明调用没有带员工身份，看是不是在 Studio 试运行或 Agent 调试里测的。换到业务页面助手里测。

**业务方法里拿到的当前用户是空的。** 身份桥里建立的上下文和业务代码读取的上下文不是同一个（比如多租户框架有自己的 ThreadLocal）。参考芋道补丁，同时设置租户上下文。

**业务系统返回 HTTP 200，但里面是 403 业务码。** 有些系统（比如芋道）用统一包装返回错误。ReachAI 会识别常见包装里的业务码并判为失败；你自己的包装格式不同时，在方法里直接抛异常更稳妥。

## 下一步

- [核对业务能力](capabilities.md)
- [身份与安全（参考）](../security.md)
