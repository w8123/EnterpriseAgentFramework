# Security Backlog (frozen after fifth-round close)

本文件记录第五轮安全收口后**刻意不再继续扩张**的事项。它们不阻断 Workflow 主线（五类节点可用性与 LOOP 建设）。

## Scope closed（已交付，不再重开架构）

1. nonce 容量边界与并发防重放（仅删过期；满容 fail-closed；H2 JDBC 并发证明）
2. HMAC 与实际 HTTP body 字节绑定（`BODY_SHA256` + cached-body）
3. Trace/RunOps 持久化出口 sanitize（Debug / finish* / snapshot / ToolLog）
4. 可信身份为唯一审计主体（`WorkflowExecutionIdentity`）
5. Control/Runtime 部署 secret 接线与 `INTERNAL_AUTH_NOT_CONFIGURED`

## Explicitly deferred（SECURITY_BACKLOG）

| 项 | 原因 |
| --- | --- |
| 真实 MySQL/Testcontainers 多 Runtime 实例 nonce Live 证明 | 属部署/Live 验收，不属再开一轮安全架构；H2 已覆盖 store 语义 |
| mTLS 服务网格 | 与当前 HMAC 内部认证正交，过大 |
| 完整 secret rotation 平台 | K8s 同 Secret 滚动已够用；轮换运维文档已有 |
| WAF / 通用 IAM | 超出 Workflow 中台边界 |
| 重写全部 RunOps / Trace 数据模型 | 现有 allowlist sanitize 已满足不变量 |
| 对全部历史接口的无限扩散式审计 | 仅绑定 Control→Runtime 可信执行与 RunOps 出口 |
| 内部 auth ping 专用端点之外的探测体系 | health indicator 已 fail-closed |
| INTERACTION 生产开放 | 独立 E2E 门槛，非本轮安全范围 |

## Rule

发现新的理论攻击面时：写入本 backlog，**不得**因此再开一轮安全架构或阻断 LOOP / 五类节点 E2E。
