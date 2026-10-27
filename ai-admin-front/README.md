# ReachAI 管理端

Vue 3、TypeScript、Element Plus 和 Vite 实现的 ReachAI 工作台。页面不拥有后端业务事实，所有管理请求经 Control 的平台会话与路由授权。

```powershell
npm ci
npm run dev
```

Vite 默认端口 5200。生产构建运行 `npm run build`，输出管理端与嵌入 SDK 制品；代理、脚本与版本以 `vite.config.ts` 和 `package.json` 为准。

- 页面布局、主题和公共组件：[DESIGN](DESIGN.md)。
- 产品与后端边界：[文档入口](../docs/README.md)。
- 登录与 CSRF：[身份与安全](../docs/security.md)。
- Embed / Page Bridge：[系统接入](../docs/integration.md)。

修改共享组件或 SDK 时同时验证管理端、宿主隔离、主题回退和构建产物，不以页面存在证明部署或业务可用。
