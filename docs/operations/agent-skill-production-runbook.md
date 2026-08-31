# Agent Skill Center 部署与真实验收手册

本文用于把 Agent Skill Center 从代码、测试与构建通过推进到目标环境真实可用。它补充
`docs/architecture/agent-skill-center.md`，不替代变更审批、数据库备份、服务发布和回滚制度。

## 1. 验收结论分层

必须使用以下状态，不能把较弱证据改写成较强结论：

| 状态 | 含义 |
| --- | --- |
| `CODE_VERIFIED` | 单元、契约和架构守卫覆盖实现语义 |
| `EXTERNAL_PACKAGE_VERIFIED` | 真实外部 Skill 目录通过生产检查器，但未进入目标服务 |
| `BUILD_VERIFIED` | 前后端制品可以构建；不证明运行进程已加载新制品 |
| `LIVE_PENDING` | 目标库、进程、权限角色或真实对话证据尚缺失 |
| `DEPLOYMENT_READY` | 一个开发、测试、预发或生产环境完成全部真实验收门槛 |
| `PRODUCTION_PROMOTION_ELIGIBLE` | `staging` 或 `production` 环境完成全部门槛且具有变更单证据 |

开发环境可以达到 `DEPLOYMENT_READY`，但不能因此得到生产放行结论。

## 2. 默认只读仓库预检

在仓库根目录运行：

```powershell
node scripts/check-agent-skill-production-readiness.mjs
```

该命令只读取仓库文件，不连接 MySQL、不访问 HTTP、不执行 Skill 脚本。它检查：

- `sql/initV2.sql` 与升级 SQL 中五张表的 DDL 是否一致；
- 五张表是否登记唯一 owning service；
- 六项 `skill:*` 权限是否同时存在于新库基线与升级 SQL；
- Control 制品路径、Runtime 缓存路径、共同内部密钥等配置契约是否存在；
- Inspector、目录、Runtime adapter、管理端和真实外部包探针等关键文件是否存在。

只有输出 `repositoryReady=true` 才能进入部署验收。输出中的升级 SQL SHA-256 必须写入目标环境证据。

## 3. 变更授权边界

以下操作会改变外部状态，必须在明确授权和可回滚窗口内执行：

- 从 GitHub 基线 `ae9e1ce6` 升级时，完整执行 `sql/upgrade-20260830-platform-consolidated.sql`（Agent Skill 目录/市场位于 Section 02/04）；
- 构建并替换正在运行的 Control、Runtime 或前端制品；
- 重启进程、容器或服务；
- 创建验收用户、角色、项目、Agent、Skill 版本或对话；
- 篡改 Runtime 验收缓存、撤销 Skill 或模拟 Control 故障。

默认只读预检、源码测试和读取当前健康状态不能代替上述真实部署步骤。

## 4. 数据库升级与只读回读

升级前记录目标库、备份、变更单和升级 SQL SHA-256。升级完成后使用只读连接回读，不在聊天、日志或证据文件中保存数据库密码。

表清单：

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
    'control_internal_auth_nonce',
    'control_agent_skill',
    'control_agent_skill_version',
    'control_agent_skill_review',
    'runtime_agent_skill_binding'
  )
ORDER BY table_name;
```

列与索引契约：

```sql
SELECT table_name, column_name, column_type, is_nullable, column_default, extra
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN (
    'control_internal_auth_nonce',
    'control_agent_skill',
    'control_agent_skill_version',
    'control_agent_skill_review',
    'runtime_agent_skill_binding'
  )
ORDER BY table_name, ordinal_position;

SELECT table_name, index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name IN (
    'control_internal_auth_nonce',
    'control_agent_skill',
    'control_agent_skill_version',
    'control_agent_skill_review',
    'runtime_agent_skill_binding'
  )
ORDER BY table_name, index_name, seq_in_index;
```

权限与角色种子：

```sql
SELECT permission_code, resource_type, action
FROM control_platform_permission
WHERE permission_code IN (
  'skill:read', 'skill:import', 'skill:review',
  'skill:publish', 'skill:bind', 'skill:script:approve'
)
ORDER BY permission_code;

SELECT r.role_code, p.permission_code
FROM control_platform_role_permission rp
JOIN control_platform_role r ON r.id = rp.role_id
JOIN control_platform_permission p ON p.id = rp.permission_id
WHERE p.permission_code LIKE 'skill:%'
ORDER BY r.role_code, p.permission_code;
```

五张表、列、索引、六项权限或角色授权任一缺失，都必须保持 `LIVE_PENDING`。

## 5. 进程与配置证据

每个运行单元分别记录制品引用、SHA-256、PID/容器、启动时间、监听端口和直接 health。必须证明进程启动时间晚于目标制品生成时间；旧进程的 HTTP 200 不是新版本证据。

配置门槛：

- Control `REACHAI_SKILL_ARTIFACT_ROOT` 指向持久化目录；多副本时使用共享卷或等价持久存储；
- Runtime `RUNTIME_SKILL_CACHE_DIR` 可写、不是临时目录，quarantine 可写；
- Control 与 Runtime 均注入非空 `REACHAI_INTERNAL_SERVICE_SECRET`；
- 不记录、回显或比较明文密钥，也不把密钥摘要放入验收 JSON；
- 由一次 Runtime→Control 签名 `resolve-execution` 成功证明签名密钥兼容；
- 使用同一 nonce 重放同一签名请求必须被拒绝，并回读 `control_internal_auth_nonce` 证明多副本共享防重放；
- `skillCodeExecutionEnabled` 仍为 false，管理端脚本策略只能选择 `DENY`。

## 6. 真实外部包导入

准备两类非 ReachAI 自建输入：

1. 一个单 Skill 包，至少包含：

- `SKILL.md`；
- `references/`、`assets/`、`scripts/`、`agents/` 中的多种目录；
- 至少一个未知扩展文件；
- 有效 `agents/openai.yaml` 和 Tool/MCP 依赖声明。

2. 一个真实仓库或插件 ZIP，至少包含两个不同目录下的 `SKILL.md`，以及仓库级 README/License。优先覆盖官方常见的 `skills/<name>`，并补充 `.opencode/skills/<name>`、`.claude/skills/<name>`、`.agents/skills/<name>` 或插件嵌套目录之一。

通过真实管理端和目标 Control 导入，而不是 API Mock 或直接写表。验收内容：

- 单包上传后自动识别唯一候选；仓库/插件包列出全部候选，无效候选显示原因且不可选，多候选不会静默默认导入；
- 发现页展示原始 Bundle SHA-256；版本详情展示最终单 Skill ZIP 与规范化文件树 SHA-256；审计事件记录 `bundleSourceSha256` 和 `bundleSkillRoot`；
- 最终制品只包含所选 Skill 子树，兄弟 Skill、仓库 README/License 和构建杂项均未进入 manifest；重新选择同一 Bundle 与根目录生成相同的单 Skill ZIP 摘要；
- 未知文件仍在 manifest 中；
- 脚本被标为需要评审，但没有执行；
- Codex Host 元数据和依赖可见，但没有自动安装 Tool、MCP、Capability 或凭据；
- 发布者显示“未验证”，不能伪装成 `reachai` 内置发布者。

## 7. 四类角色浏览器流程

使用四个独立身份，浏览器直接连接目标服务，`apiMocksUsed` 必须为 false：

| 身份 | 必须验证 |
| --- | --- |
| 作者 | 导入 PRIVATE、PROJECT 及有权限范围内的包；不能越权导入其他项目 |
| 评审者 | 查看说明、License、manifest、风险和 Host 兼容证据；完成通过或驳回 |
| Agent 设计者 | 只能绑定 `PUBLISHED` 精确版本；升级版本必须显式保存并重新发布 Agent 配置 |
| 无权限用户 | 导入、评审、发布、下载和绑定操作由服务端拒绝，不能只依赖按钮隐藏 |

另外验证：PRIVATE 对其他用户不可见；PROJECT 不能绑定到其他项目 Agent；改变 Agent 项目不能绕过历史绑定范围。

## 8. 真实 AgentScope 与 RunOps

至少创建两个可追踪对话：

1. `MODEL_SELECTED`：用户意图确实触发 Skill repository 渐进加载；
2. `ALWAYS`：主说明固定进入本次系统上下文。

还要验证普通对话不会加载 `EXPLICIT`。每次运行记录 trace ID，并在 `SKILL_CONTEXT` 与 RunOps 中确认 publisher/name、精确版本、摘要、active/skipped 数量和跳过原因。证据必须来自真实 AgentScope 与真实模型调用；格式检查或 Mock 响应不能填写 `hostE2e=PASSED`。

Trace metadata 不应复制 Skill 正文、附件内容、凭据或用户原文。

## 9. 失败、篡改与撤销演练

在专用验收 Agent 和缓存目录中执行，不修改其他用户缓存：

- 修改缓存文件后，Runtime 将目录移入 quarantine，并从 Control 重新下载；
- 重新下载后同时复核 ZIP、文件树 manifest 和逐文件摘要；
- 构造摘要不一致时拒绝加载，不回退到旧缓存；
- 撤销已缓存版本后，下一次执行重新向 Control 复核并拒绝使用；
- Control 不可用时，`required=true` 阻断执行，optional Skill 安全跳过并写入 Trace；
- 演练结束后保留非秘密日志、trace ID、quarantine manifest 与 RunOps 引用。

## 10. 组装与校验证据

复制 `reachai-agent-skill-production-evidence-v2` 模板到变更证据目录后填写，不要修改仓库中的示例文件。v2 将仓库/插件 Bundle 的发现、隔离与审计追溯列为必需证据，不兼容缺少这些字段的早期草稿：

```powershell
$skillEvidencePath = 'C:\approved-change\agent-skill-production-evidence.json'
Copy-Item -LiteralPath 'scripts\fixtures\agent-skill-production-evidence.example.json' `
  -Destination $skillEvidencePath
```

填写后的证据必须引用不可变日志、截图、Trace、RunOps、SQL 回读或制品清单。运行：

```powershell
node scripts/check-agent-skill-production-readiness.mjs `
  --evidence=$skillEvidencePath
```

输出解释：

- `ready=true`：仓库契约和目标环境证据都满足，目标环境达到 `DEPLOYMENT_READY`；
- `productionPromotionEligible=true`：仅适用于 classification 为 `staging` 或 `production` 且具有变更单的完整证据；
- exit code `1`：证据明确不满足；
- exit code `2`：命令、JSON 或文件本身无效，不能视为业务门槛失败或通过。

验收器只验证证据结构、时效、仓库 checksum 和跨门槛一致性，不会自动信任一张成功截图。真实外部包证据还必须填写原始 `bundleSourceSha256`、`bundleSkillRoot`，并证明候选发现、子树隔离和审计追溯均通过。任何布尔结论都必须有对应 `evidenceRef`，并接受独立复核。

## 11. 回滚与清理

- 代码或制品回滚不得删除五张表、评审历史或内容寻址制品；
- 紧急停用优先撤销目标 Skill 版本或停止新绑定，不直接改 Runtime 快照；
- 撤销演练只使用验收版本；若需要恢复，导入新版本并重新评审发布，不能覆盖原版本字节；
- 临时验收用户、项目、Agent 和证据缓存的清理由环境负责人按清单审批；不要在验收脚本中隐式删除。
