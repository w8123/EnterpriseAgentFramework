# 芋道 ERP 接入

通用机制先读[系统接入](../integration.md)。这里只保留芋道自身的适配点；快速体验直接使用 [Docker 演示](../../deploy/quickstart/README.md)。

## 应用补丁

- [后端与业务方法补丁](yudao/yudao-reachai-integration.patch)：SDK、身份桥、嵌入令牌解析和 ERP 能力声明。
- [页面补丁](yudao/yudao-ui-reachai-chat.patch)：在芋道布局中接入对话与页面通道。

先在目标芋道 checkout 执行 `git apply --check <补丁路径>`，核对当前上游代码与冲突后再应用。依赖版本使用本平台 onboarding manifest 给出的制品，不按示例推断兼容所有芋道版本。

## 后端配置

项目使用独立 code 和凭据，扫描范围限定 ERP 包。仅业务方法使用 `ANNOTATED_ONLY`；需要同时发现 Controller API 时选择 Starter 支持的对应模式。

`/reachai/**` 交给 Starter 校验签名和调用令牌，芋道侧放行该受保护入口，不能取消 Starter 鉴权。业务执行通过 `YudaoReachAiSecurityBridge` 还原 `LoginUser`、租户和角色，再沿用芋道的数据权限与事务。

员工调用必须对应本人账号；仅在受控测试入口使用明确的测试账号，不把缺失用户身份映射成业务管理员。配置中的平台地址、凭据文件和租户值使用目标环境真实配置，敏感内容不提交。

## 嵌入与员工工具

业务后端通过 `YudaoReachAiEmbedPrincipalResolver` 从当前登录态解析身份。平台配置允许来源、项目与 Agent，前端加载构建的 Embed SDK，由后端换取短期令牌。浏览器不保存项目 appSecret。

需要员工在自己的 AI 工具中使用时，按[员工指南](用Claude%20Code操作芋道.md)开通 MCP 服务、调用权限与写审批。

## 验收

1. 注册实例、心跳、同步与变化评审对应同一项目和真实来源。
2. 核对产品、仓库、客户等返回 DTO 的业务包装和字段路径，再试调用。
3. 员工查询结果与芋道一致，写操作先审批，实际创建人与当前员工一致。
4. 非白名单来源、无签名、无令牌和越权角色被拒绝；切换用户不能读取前一用户的会话或记忆。
5. 从员工连接、审批、调用记录回到同一运行与 Trace；失败能定位权限、来源、参数或契约问题。

参数与具体能力清单以补丁、平台接纳结果及芋道当前代码为准，本页不重复维护另一份接口目录。
