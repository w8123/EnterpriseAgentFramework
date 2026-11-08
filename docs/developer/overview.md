# 开发者从这里开始

> 适合：要把业务系统接入 ReachAI 的开发者。读完你会知道接入要做哪几件事、按什么顺序做、每一步读哪篇。

## 做完你会得到什么

一个业务系统接入 ReachAI 后，员工可以：

- 在业务系统右下角的助手里，用一句话查数据、办业务；
- 在自己的 Codex、Claude Code 里，以本人身份操作这个系统；
- 让 AI 改数据时，先走确认或审批，每一步都有记录。

你写的代码只有两类：**声明哪些业务方法开放给 AI**，以及**告诉 ReachAI 当前登录的是谁**。其余的（注册、签名、审批、审计、MCP 协议）都由 SDK 和平台处理。

## 接入路线

```text
① 接入项目          ② 声明业务方法          ③ 接身份桥
平台建项目、拿令牌 → SDK 扫描并同步方法 → AI 以员工身份执行
        │
        ▼
④ 核对业务能力 → ⑤ 编排 Workflow / 配 Agent → ⑥ 嵌入页面 / 发布 MCP 服务
```

| 步骤 | 做什么 | 读哪篇 |
| --- | --- | --- |
| ① 接入项目 | 在「项目管理」建项目，拿到 72 小时有效的接入令牌 | [接入第一个业务系统](connect-project.md) |
| ② 声明业务方法 | Java 用 Starter + `@ReachCapability`；其他语言用对应 SDK；没法改代码就导入 OpenAPI | [Java](java-starter.md) / [C#、Python、Node、Go](other-sdks.md) / [OpenAPI 导入](openapi-import.md) |
| ③ 接身份桥 | 把平台传来的员工身份还原成业务系统的登录用户 | [业务身份桥](business-identity.md) |
| ④ 核对业务能力 | 在「业务能力」看平台收到了什么，试调用 | [核对业务能力](capabilities.md) |
| ⑤ 编排和配置 | 准备模型，把业务方法编成 Workflow，交给 Agent | [接入模型](model-setup.md) / [Workflow](first-workflow.md) / [Agent](agent.md) |
| ⑥ 交给员工用 | 业务页面嵌入助手；发布 MCP 服务给员工的 AI 工具 | [嵌入助手](embed-chat.md) / [发布 MCP 服务](publish-mcp.md) |

第 ⑥ 步两条路可以只走一条：只想让员工在自己的 Codex 里用，不嵌入页面也行。

## 让你的 AI 帮你接

上面每一步都可以交给你自己的 Codex 或 Claude Code 做。ReachAI 在项目接入工作台、Workflow、MCP 服务等页面都有「交给我的 AI」按钮，点一下复制提示词，粘贴给 AI，它会读平台给的接入教程、改你的代码、回平台核对结果。见 [交给我的 AI](ai-tool-handoff.md)。

已经接入的系统，想再开放更多业务方法，可以 [用 AI 改造业务能力](retrofit.md)。

## 先试一试

不想从零开始的话，先跑 [一键体验](../start/try-in-10-minutes.md)：芋道 ERP 已经按本篇的步骤接好，你可以对照着看每一步在真实系统里长什么样。完整过程见 [芋道 ERP 接入实战](yudao-example.md)。

## 开始前你需要

- 一个 ReachAI 账号，角色是「开发人员」或「平台管理员」。左下角「切换角色」切到 **开发人员**，左侧会出现「接入与能力」「智能体开发」「开放与互联」三组菜单。
- 业务系统能访问 ReachAI 地址，ReachAI 也能访问业务系统后端（平台要回调业务方法）。
- 一个可用的大模型（平台管理员可能已经接好）。

## 下一步

- [接入第一个业务系统](connect-project.md)
- [一句话怎么变成一次 ERP 操作](../start/how-it-works.md)：先理解原理
