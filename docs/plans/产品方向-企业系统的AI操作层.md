# ReachAI 产品方向：企业系统的 AI 操作层

> 2026-10-24 用户确认（定位、冻结清单）；员工身份改为 ReachAI 管理账号、员工只勾选系统，不走 OAuth 授权页。结论已写入 [平台定位与架构总览](../01-平台定位与架构总览.md)。

## 1. 一句话定位

**让任何 AI（ERP 里的嵌入式对话、员工自己的 Codex / Claude Code）都能以"员工本人"的身份，安全地操作企业业务系统；每一次操作都可审批、可审计，好的做法能沉淀下来复用。**

ReachAI 不做 agent 大脑、不做通用聊天、不做 IDE。Agent 循环这一层已经被 Codex、Claude Code、DeepSeek Harness 免费提供，我们做它们做不了的企业那一层。

## 2. 三类人分别得到什么

| 人 | 在哪里用 | 得到什么 |
| --- | --- | --- |
| 企业开发者 | 业务系统代码 + 自己的 Codex | 用 SDK 把业务系统改造成"AI 可操作"；Codex 通过 ReachAI 的 AI Coding 任务完成接入和升级改造，结果进入能力变化评审 |
| 业务用户 | ERP 嵌入式对话，或自己的 Codex / Claude Code | 同一套业务能力、同一套权限，两种入口都能办业务 |
| 管理者 | 管理端 | 看到谁、用什么 AI 工具、对哪个系统做了什么；审批写操作；把好用的做法沉淀成 Skill / Workflow |

## 3. 结构：三个入口，一套治理

```text
入口    嵌入式对话（业务用户）   MCP（员工自己的 AI 工具）   AI Coding 任务（开发者的 Codex）
          \                         |                          /
治理    能力目录 · 员工身份 · 调用权限 · 写操作审批 · 审计/Trace · 经验沉淀（Skill / Workflow / 记忆）
          |
接入    业务系统 SDK（@ReachCapability） · HTTP API · API 市场
```

## 4. 现状与缺口（基于 dev 代码）

| 方面 | 已有 | 缺口 |
| --- | --- | --- |
| MCP 出口 | MCP Hub 出向 M1 代码已落地（发布、修订、Tool ACL、Trace） | 未部署验收；调用方是"MCP Client"应用身份，**不是员工本人**，业务系统里也拿不到真实操作人 |
| 写操作审批 | Runtime Interaction、确认、恢复 | MCP 身份下阻塞交互直接拒绝，**MCP 无法做写操作审批** |
| 开发者改造 | AI Coding Task Protocol（项目接入、页面扫描） | "给业务系统加能力 / 升级改造"场景（CODE_IMPLEMENTATION）未打通 |
| 经验沉淀 | Skill 包、Workflow、个人记忆 | 缺"从一次成功的对话或调用 → 一键沉淀成 Skill / Workflow"；Skill 不能导出给 Codex 用 |
| 管理者视图 | 运行中心（对话、运行、Trace、评测） | 缺按人、按 AI 工具、按业务系统的使用记录和审计 |
| Agent 循环 | AgentScope Java harness；Managed Executor 里有 Codex app-server Worker | Worker 未部署（服务器只有 2 核 3.6G） |

## 5. 开源 harness 怎么用

1. **员工自己的 agent 就是 harness。** Codex、Claude Code、DeepSeek Harness 作为客户端，通过 MCP 接 ReachAI。我们不去包装它们，这是最省力、也最不会过时的复用。
2. **Skill 包走标准格式。** ReachAI 的 Skill 导出为标准 Agent Skill（SKILL.md），员工可以直接装进 Codex / Claude Code；Skill 里写"怎样用 ReachAI 的 MCP 办某类业务"。
3. **嵌入式对话继续用 AgentScope。** Java 栈一致、已稳定，没有必要换。
4. **Codex app-server 留到后面。** 开放式执行（跑脚本、改代码）放在 Managed Executor，等资源和沙箱验收具备再上。

## 6. 冻结与保留

- 冻结：A2A 互联中心、MCP 入向（M2/M3）、K8s Managed Executor、Workflow 画布新功能、全站 UI 打磨。
- 保留并强化：API 市场、Skill 包（变成对外分发的经验载体）。

## 7. 路线（按顺序）

1. **员工在自己的 AI 工具里办业务。** 员工在芋道 AI 面板勾选要接入的系统，复制一行到 Claude Code / Codex，查库存、新建客户；调用权限按芋道角色生效，芋道里记录的操作人是本人；运行中心能看到。顺带完成 MCP Hub M1 部署验收。
2. **写操作审批。** MCP 写操作先返回"待审批"，审批人在管理端（或芋道里）批准后执行，全程审计。
3. **经验沉淀闭环。** 从运行记录一键生成 Skill 或 Workflow 草稿；Skill 可下载装进 Codex / Claude Code。
4. **管理者使用记录。** 按人 / AI 工具 / 业务系统 / 能力统计和审计，三个入口统一口径。
5. **开发者改造。** Codex 通过 AI Coding 任务给业务系统加 @ReachCapability，结果进入变化评审。

贯穿全程（开源项目还没有用户，这一条决定能不能有用户）：10 分钟上手，docker-compose 一键起 ReachAI + 芋道 demo，README 里有"用 Claude Code 操作 ERP"的演示；补测试和 CI。

## 8. 待确认

1. 同意这个定位吗？
2. 员工身份：ReachAI 管理和同步账号，员工只在页面上勾选系统（已确认，替代原 OAuth 设想）。
3. 冻结清单可以吗？
