# Node.js Express 接入

从项目接入清单 `?language=node` 选择 `@reachai/node-sdk` 制品，下载后核验 `integritySha256`；摘要缺失或不一致时停止安装。将验证后的 tgz 在业务项目执行 `npm install <tgz路径> express`，依赖来自可信 npm 来源。平台制品地址不是 npm 仓库。首版支持 Node.js 20+、Express 4.18–5.x。

在业务后端配置 `ReachAI`、`EncryptedFileCredentialStore`、`principalResolver` 和 `securityBridge`。业务系统自己从已验证登录会话解析用户、租户、角色与业务会话，并在受信身份进入业务服务后继续校验数据范围和事务。只声明允许调用的 `reach.capability`，明确输入和输出 JSON Schema，再将 `createReachRouter(reach)` 挂载到 Express；业务监听后 `await reach.start()`，退出时 `await reach.stop()`。不把项目密钥或业务认证信息交给浏览器。

首次注册由单个进程使用一次性 Enrollment Token，凭据必须保存在独立密钥保护的持久存储中；注册结果不确定时不得自动重用 token。密钥为独立提供的 32 字节随机值的标准 Base64。多 worker 使用共享受保护凭据和原子回调 nonce 存储；默认 nonce 存储仅覆盖单进程。退出业务登录时，调用 SDK 会话撤销并由宿主可靠处理失败。

转发 `/reachai/registry/**` 与 `/reachai/capabilities/**` 时保留签名请求头；`/reachai/embed/token` 保留宿主登录与 CSRF 防护。HTTP API 使用现有 OpenAPI 导入，不能伪装为 Spring MVC 来源。前端复用 Embed Chat 与 Page Bridge，分别验证会话、页面动作、权限和业务结果；项目级旧会话不能证明来自 Node 实例。
