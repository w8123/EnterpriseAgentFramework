# ReachAI Service Table Ownership

> Date: 2026-07-14
> Scope: first physical split with one shared MySQL database

## Rules

Knowledge 独占文件及授权的 `record_generation`、导入任务的 `replace_file_generation` 和索引执行的 `target_file_generation`。记录身份在创建时生成，长期任务与检索快照通过 owning service 读取并校验；其他服务不直接写入或推断这些字段。开发库执行记录见 [文件与授权记录身份升级](../../output/tasks/architecture-audit-20260905/knowledge-record-generation-database-notes.md)。

- One shared MySQL database is allowed in this phase, but each table still has one owner service.
- New code must not directly write another service's owned table.
- Application code access is the source of truth for boundary violations: `@TableName`, MyBatis annotation SQL, MyBatis XML SQL, and JdbcTemplate SQL are checked.
- `sql/initV2.sql` is the long-term SQL baseline and every `CREATE TABLE` row must appear in this matrix, even if the table has no current direct code access.
- Temporary same-database reads or compatibility writes must be documented in `Additional direct access` with the accessing service and reason.
- The current target state is zero cross-service direct table access in application code; service collaboration must go through internal HTTP APIs, explicit clients, or service-owned read models.
- The current baseline targets a new database: legacy names such as `ai_workflow`, `ai_agent`, `skill_draft`, `skill_interaction`, `eaf_page_registry`, and `tool_call_log` are replaced by service/domain-prefixed names. No old-table compatibility views or data migration scripts are required. ReachAI no longer creates `capability_draft`, `runtime_skill_interaction`, or `kind=SKILL` catalog rows.

## Shared Table Exceptions Retired

The current scan finds no direct table access shared across physical services. Former first-phase exceptions have been moved behind internal service APIs:

- `control_page_action`: owned by `reachai-control-service`; Runtime calls the Control internal page-action catalog API.
- `runtime_tool_call_log`: owned by `reachai-runtime-service`; remaining consumers call Runtime internal APIs when needed.

These rows remain in the ownership matrix because the current baseline still records their owner service, not because direct cross-service table access is still allowed.

## Ownership Matrix

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
| `control_a2a_publication` | `reachai-control-service` | - | A2A Hub local Agent publication aggregate and current immutable revision |
| `control_a2a_publication_revision` | `reachai-control-service` | - | Immutable A2A 1.0 Agent Card publication snapshot and conformance evidence |
| `control_a2a_remote_agent` | `reachai-control-service` | - | Stable remote Agent catalog identity, trust and health state |
| `control_a2a_remote_agent_revision` | `reachai-control-service` | - | Immutable remote Agent Card revision and verification evidence |
| `control_a2a_trust_profile` | `reachai-control-service` | - | A2A authentication, authorization, data and quota policy |
| `control_a2a_principal` | `reachai-control-service` | - | Authenticated A2A principal and owner scope |
| `control_a2a_credential` | `reachai-control-service` | - | Hashed, encrypted or externally referenced A2A credential material |
| `control_a2a_context` | `reachai-control-service` | - | Principal-scoped A2A conversational context |
| `control_a2a_task` | `reachai-control-service` | - | A2A Task current projection, owner scope and Runtime/Trace linkage |
| `control_a2a_outbound_execution` | `reachai-control-service` | - | Immutable outbound Task acceptance snapshot, polling schedule and distributed lease |
| `control_a2a_message` | `reachai-control-service` | - | Encrypted A2A Message resource and safe summary |
| `control_a2a_artifact` | `reachai-control-service` | - | Encrypted or externalized A2A Task Artifact |
| `control_a2a_task_event` | `reachai-control-service` | - | Immutable ordered A2A Task event ledger |
| `control_a2a_push_notification_config` | `reachai-control-service` | - | Task webhook configuration and credential reference |
| `control_a2a_transport_event` | `reachai-control-service` | - | A2A protocol audit without raw request or response bodies |
| `control_a2a_rate_limit_window` | `reachai-control-service` | - | Multi-instance Principal/publication request-rate windows |
| `control_a2a_conformance_run` | `reachai-control-service` | - | A2A TCK and publication preflight evidence |
| `control_a2a_outbox` | `reachai-control-service` | - | Durable A2A dispatch and notification outbox without content bodies |
| `runtime_agent_eval_case` | `reachai-runtime-service` | - | Runtime evaluation case |
| `runtime_agent_eval_case_result` | `reachai-runtime-service` | - | Runtime evaluation result |
| `runtime_agent_eval_dataset` | `reachai-runtime-service` | - | Runtime evaluation dataset |
| `runtime_agent_eval_run` | `reachai-runtime-service` | - | Runtime evaluation run |
| `runtime_automation` | `reachai-runtime-service` | - | Automation aggregate and current immutable-version projection |
| `runtime_automation_version` | `reachai-runtime-service` | - | Exact Agent/Workflow target, schedule, policy, principal and input snapshot |
| `runtime_automation_occurrence` | `reachai-runtime-service` | - | Idempotent nominal trigger occurrence and recoverable execution lease |
| `runtime_automation_attempt` | `reachai-runtime-service` | - | Per-lease Automation execution attempt and bounded result evidence |
| `runtime_automation_event` | `reachai-runtime-service` | - | Metadata-only Automation lifecycle and operator audit ledger |
| `runtime_automation_engine_command` | `reachai-runtime-service` | - | Durable Automation-definition to cluster-clock synchronization command |
| `runtime_automation_execution_slot` | `reachai-runtime-service` | - | Cross-replica Automation concurrency-policy lease slot |
| `runtime_scheduler_task` | `reachai-runtime-service` | - | Embedded db-scheduler cluster clock; no Agent/Workflow business result |
| `runtime_eval_target_snapshot` | `reachai-runtime-service` | - | Server-derived immutable Eval target snapshot and canonical fingerprint |
| `runtime_eval_dataset` | `reachai-runtime-service` | - | EvalOps versioned dataset catalog |
| `runtime_eval_dataset_version` | `reachai-runtime-service` | - | Immutable published Eval dataset version |
| `runtime_eval_dataset_item` | `reachai-runtime-service` | - | Version-pinned Eval input and expected-result item |
| `runtime_eval_evaluator_suite_version` | `reachai-runtime-service` | - | Immutable deterministic evaluator-suite version |
| `runtime_eval_experiment` | `reachai-runtime-service` | - | Baseline/candidate Eval experiment aggregate |
| `runtime_eval_experiment_variant` | `reachai-runtime-service` | - | Experiment target variant and server snapshot fingerprint |
| `runtime_eval_experiment_item` | `reachai-runtime-service` | - | Per-variant, per-item, per-repeat execution result |
| `runtime_eval_score` | `reachai-runtime-service` | - | First-class evaluator score and evidence |
| `runtime_eval_task` | `reachai-runtime-service` | - | Recoverable MySQL 5.7-compatible Eval worker lease |
| `runtime_managed_execution` | `reachai-runtime-service` | - | Managed Executor aggregate, policy snapshot, execution-scoped worker token digest and lease |
| `runtime_managed_execution_event` | `reachai-runtime-service` | - | Strictly ordered sanitized Worker event ledger without reasoning or raw output deltas |
| `runtime_managed_artifact` | `reachai-runtime-service` | - | Object-store Artifact digest, validation, scan and retention metadata |
| `runtime_managed_execution_outbox` | `reachai-runtime-service` | - | Durable metadata-only Managed Executor status and Artifact notification outbox |
| `control_managed_execution_inbox` | `reachai-control-service` | - | Idempotent Runtime event receipt and Control-side Managed Execution projection state |
| `runtime_run` | `reachai-runtime-service` | - | RunOps root execution fact used by lists, KPIs, diagnostics, and replay |
| `runtime_internal_auth_nonce` | `reachai-runtime-service` | - | Control→Runtime HMAC nonce anti-replay store (multi-instance safe) |
| `runtime_conversation_session` | `reachai-runtime-service` | - | Trusted tenant/user/Agent session ownership, turn/lifecycle leases, Legal Hold, and retention state machine |
| `runtime_conversation_event` | `reachai-runtime-service` | - | Durable full conversation event ledger; not canonical long-term personal memory |
| `runtime_session_retention_policy` | `reachai-runtime-service` | - | Tenant-scoped active/cleared session retention policy; Control administers it only through Runtime internal APIs |
| `runtime_session_retention_audit` | `reachai-runtime-service` | - | Metadata-only policy, Legal Hold, clear, purge, recovery, and failure audit; no conversation body or raw actor ID |
| `runtime_tool_result_artifact` | `reachai-runtime-service` | - | Session-scoped encrypted large Tool output with bounded read and ciphertext TTL scrubbing; not personal memory |
| `runtime_trace_span` | `reachai-runtime-service` | - | Runtime generic trace span child event |
| `runtime_agent_workflow_credential` | `reachai-runtime-service` | - | Workflow credential vault metadata |
| `runtime_agent` | `reachai-runtime-service` | - | Runtime agent catalog |
| `runtime_agent_config_version` | `reachai-runtime-service` | - | Versioned Agent Supervisor configuration |
| `runtime_agent_workflow_tool` | `reachai-runtime-service` | - | Workflow-as-Tool allow-list per Agent configuration version |
| `runtime_agent_skill_binding` | `reachai-runtime-service` | - | Exact published Agent Skill package plus visibility/project snapshot bound to an Agent configuration version |
| `runtime_agent_remote_agent_binding` | `reachai-runtime-service` | - | Exact remote Agent revision delegated by a published Agent configuration; Runtime validates through Control internal API |
| `model_template` | `reachai-model-service` | - | Model Center V2 catalog templates (no secrets) |
| `model_instance` | `reachai-model-service` | - | Model Center V2 executable instances (stable modelInstanceId) |
| `model_catalog_source` | `reachai-model-service` | - | Official provider source metadata and safe fetch policy; no credentials |
| `model_catalog_setting` | `reachai-model-service` | - | Singleton automatic-sync setting; defaults to manual-only mode |
| `model_catalog_sync_run` | `reachai-model-service` | - | One recoverable leased synchronization slot per source and business day |
| `model_catalog_snapshot` | `reachai-model-service` | - | Immutable normalized public source snapshot and bounded analyzer output |
| `model_catalog_change` | `reachai-model-service` | - | Validated candidate diff, evidence and guarded publication result |
| `capability_project_instance` | `reachai-capability-service` | - | SDK access-instance heartbeat |
| `capability_external_api_source` | `reachai-capability-service` | - | API 市场来源、信任级别与同步策略；不保存调用凭据 |
| `capability_external_api_provider` | `reachai-capability-service` | - | 外部 API 提供方身份目录 |
| `capability_external_api_entry` | `reachai-capability-service` | - | 可发现的外部 API 聚合目录条目 |
| `capability_external_api_version` | `reachai-capability-service` | - | 外部 API 不可变版本与契约来源快照 |
| `capability_external_api_operation` | `reachai-capability-service` | - | 版本内可选择的 HTTP Operation 及 schema |
| `capability_external_api_verification` | `reachai-capability-service` | - | 无响应正文、无秘密的质量验证证据摘要 |
| `capability_external_api_sync_run` | `reachai-capability-service` | - | 来源同步批次与人工发布统计 |
| `capability_project_external_api` | `reachai-capability-service` | - | 项目接入意图和目录版本钉住；凭据仍归 Runtime |
| `capability_project_external_api_operation` | `reachai-capability-service` | - | 项目接入选中的 Operation 集合 |
| `runtime_workflow` | `reachai-runtime-service` | - | Workflow runtime definition |
| `runtime_workflow_version` | `reachai-runtime-service` | - | Workflow release snapshot |
| `runtime_workflow_release_event` | `reachai-runtime-service` | - | Append-only Workflow publication and rollback history |
| `runtime_workflow_draft_submission` | `reachai-runtime-service` | - | Workflow task draft submission fingerprints and last applied revisions; no raw request or credentials |
| `runtime_workflow_capability_reference` | `reachai-runtime-service` | - | Transactional Workflow reverse references and index coverage |
| `runtime_workflow_resource_binding` | `reachai-runtime-service` | - | Workflow to PAGE and other stable resource bindings |
| `capability_api_graph_edge` | `reachai-capability-service` | - | Capability API graph edge |
| `capability_api_graph_layout` | `reachai-capability-service` | - | Capability API graph layout |
| `capability_api_graph_node` | `reachai-capability-service` | - | Capability API graph node |
| `knowledge_business_index` | `reachai-knowledge-service` | - | Knowledge business index |
| `knowledge_business_index_attachment` | `reachai-knowledge-service` | - | Knowledge business index attachment |
| `knowledge_business_index_record` | `reachai-knowledge-service` | - | Knowledge business index record |
| `capability_apply_record` | `reachai-capability-service` | - | Capability review apply history |
| `capability_diff_item` | `reachai-capability-service` | - | Capability diff item |
| `capability_module` | `reachai-capability-service` | - | Capability module catalog |
| `capability_snapshot` | `reachai-capability-service` | - | Capability snapshot |
| `capability_source_state` | `reachai-capability-service` | - | Latest verified source contract and catalog consistency |
| `capability_sync_log` | `reachai-capability-service` | - | Capability sync log |
| `capability_sync_receipt` | `reachai-capability-service` | - | Project-scoped sync identity and snapshot binding |
| `knowledge_chunk` | `reachai-knowledge-service` | - | Knowledge knowledge_chunk storage |
| `capability_composition_definition` | `reachai-capability-service` | - | Capability composition definition |
| `control_context_audit_event` | `reachai-control-service` | - | Control context governance audit |
| `control_context_binding` | `reachai-control-service` | - | Control context binding |
| `control_context_evidence` | `reachai-control-service` | - | Control context evidence |
| `control_context_item` | `reachai-control-service` | - | Control context item |
| `control_context_memory_candidate` | `reachai-control-service` | - | Control memory candidate review buffer |
| `control_context_memory_outbox` | `reachai-control-service` | - | Transactional personal-memory projection outbox; Control canonical write boundary |
| `control_memory_erasure_request` | `reachai-control-service` | - | Durable cross-domain Agent-memory erasure request, lease, retry, and aggregate completion state |
| `control_memory_erasure_domain` | `reachai-control-service` | - | Per-domain automated/manual erasure evidence without object identifiers or content |
| `control_context_namespace` | `reachai-control-service` | - | Control context namespace |
| `control_context_runtime_user_mapping` | `reachai-control-service` | - | Control to Runtime user mapping |
| `knowledge_personal_memory_index` | `reachai-knowledge-service` | - | Rebuildable owner-hashed lexical/vector search projection; never canonical |
| `knowledge_personal_memory_index_event` | `reachai-knowledge-service` | - | Idempotent personal-memory projection consumption ledger |
| `capability_domain_assignment` | `reachai-capability-service` | - | Capability domain assignment |
| `capability_domain_def` | `reachai-capability-service` | - | Capability domain definition |
| `control_ai_coding_task` | `reachai-control-service` | - | ReachAI-wide AI Coding task root |
| `control_ai_coding_task_artifact` | `reachai-control-service` | - | Generic contracted artifacts and domain apply results |
| `control_ai_coding_task_event` | `reachai-control-service` | - | Immutable AI Coding task events |
| `control_ai_coding_task_handoff` | `reachai-control-service` | - | One-time activation and short-lived task connection facts |
| `control_ai_coding_project_policy` | `reachai-control-service` | - | Per-project handoff activation and task-token TTL policy |
| `control_ai_coding_task_question` | `reachai-control-service` | - | AI Coding questions and user answers |
| `control_ai_coding_task_target` | `reachai-control-service` | - | Typed task targets and access scope |
| `control_business_user` | `reachai-control-service` | - | Control business user directory |
| `control_embed_chat_event` | `reachai-control-service` | - | Embed chat transcript |
| `control_embed_renderer` | `reachai-control-service` | - | Embed renderer catalog |
| `control_embed_session` | `reachai-control-service` | - | Embed session state |
| `control_embed_token_revocation` | `reachai-control-service` | - | Embed token revocation list |
| `control_external_user_binding` | `reachai-control-service` | - | External user binding |
| `control_external_user_role_binding` | `reachai-control-service` | - | External user role binding |
| `control_page_action_event` | `reachai-control-service` | - | Browser page action event |
| `control_page_action` | `reachai-control-service` | - | Stable page action contract exposed through Control internal API |
| `control_page_analysis_finding` | `reachai-control-service` | - | Per-page read-only AI Coding analysis results |
| `control_project_page` | `reachai-control-service` | - | Canonical project page definition |
| `control_project_page_resource` | `reachai-control-service` | - | Page routes, components, APIs, permissions, config and test locations |
| `runtime_executable_debug_session` | `reachai-runtime-service` | - | Runtime executable debug session |
| `knowledge_file_info` | `reachai-knowledge-service` | - | Knowledge file metadata |
| `knowledge_document_import_job` | `reachai-knowledge-service` | - | Durable source/artifact and job state, server-captured replacement file identity, global active-file reservation and transactional cancellation; Knowledge owns all mutations |
| `knowledge_document_index_execution` | `reachai-knowledge-service` | - | Immutable vector manifests, operation/target snapshots, publication deadlines and exact replaced/deleted-file vector retirement progress |
| `knowledge_collection_lifecycle` | `reachai-knowledge-service` | - | Single creation intent, metadata publication and durable retirement of immutable physical collections; unknown creation outcomes remain reclaimable |
| `knowledge_document_artifact_lifecycle` | `reachai-knowledge-service` | - | Immutable object writes, reference publication and durable reclamation scoped to the owning storage backend |
| `runtime_guard_decision_log` | `reachai-runtime-service` | - | Runtime guard decision log |
| `capability_interaction_definition` | `reachai-capability-service` | - | Capability interaction definition |
| `runtime_interaction_event` | `reachai-runtime-service` | - | Runtime interaction event |
| `runtime_interaction_session` | `reachai-runtime-service` | - | Runtime interaction session |
| `knowledge_base` | `reachai-knowledge-service` | - | Knowledge base |
| `knowledge_hit_log` | `reachai-knowledge-service` | - | Knowledge retrieval hit log |
| `knowledge_question` | `reachai-knowledge-service` | - | Knowledge question curation; file retirement unlinks chunk references while retaining question text |
| `knowledge_tag` | `reachai-knowledge-service` | - | Knowledge tag |
| `control_market_item` | `reachai-control-service` | - | Control marketplace item |
| `control_agent_skill` | `reachai-control-service` | - | Scope-keyed standard Agent Skill installation identity plus enforced PRIVATE owner / PROJECT scope / SHARED / PUBLIC visibility |
| `control_agent_skill_version` | `reachai-control-service` | - | Immutable package version, artifact digest, validation and compatibility snapshot |
| `control_agent_skill_review` | `reachai-control-service` | - | Human review decision for a standard Agent Skill package version |
| `control_agent_skill_market_source` | `reachai-control-service` | - | External Agent Skill search/transport/curated source catalog and explicit trust classification; contains no credentials |
| `control_agent_skill_market_import` | `reachai-control-service` | - | Immutable marketplace provider, public GitHub commit/path and digest provenance for a governed Skill import |
| `control_internal_auth_nonce` | `reachai-control-service` | - | Runtime→Control HMAC nonce anti-replay store shared by Control replicas |
| `control_mcp_call_log` | `reachai-control-service` | - | MCP Hub bidirectional call audit (OUTBOUND external caller / INBOUND remote server) |
| `control_mcp_client` | `reachai-control-service` | - | MCP Hub client credential scoped to a single publication; terminal credential states are non-reversible |
| `control_mcp_publication` | `reachai-control-service` | - | MCP Hub outbound publication aggregate with state machine and current revision pointer |
| `control_mcp_publication_item` | `reachai-control-service` | - | MCP Hub draft publication item referencing a Capability or a published Workflow |
| `control_mcp_publication_revision` | `reachai-control-service` | - | MCP Hub immutable tool-projection snapshot; the only source of truth for tools/list and tools/call |
| `control_platform_auth_provider` | `reachai-control-service` | - | Platform auth provider |
| `control_platform_auth_audit_event` | `reachai-control-service` | - | Non-secret audit for platform auth and high-risk runtime-user identity mapping changes |
| `control_platform_login_session` | `reachai-control-service` | - | Platform login session |
| `control_platform_permission` | `reachai-control-service` | - | Platform permission catalog |
| `control_platform_role` | `reachai-control-service` | - | Platform role |
| `control_platform_role_permission` | `reachai-control-service` | - | Platform role permission binding |
| `control_platform_user` | `reachai-control-service` | - | Platform user |
| `control_platform_user_role` | `reachai-control-service` | - | Platform user role |
| `capability_registry_project_credential` | `reachai-capability-service` | - | SDK registry credential |
| `capability_registry_enrollment_token` | `reachai-capability-service` | - | One-time SDK enrollment proof digest |
| `capability_internal_auth_nonce` | `reachai-capability-service` | - | Control-to-Capability internal HMAC replay ledger |
| `capability_registry_request_nonce` | `reachai-capability-service` | - | Body-bound project credential request replay ledger |
| `capability_scan_module` | `reachai-capability-service` | - | Capability scan module |
| `capability_scan_project` | `reachai-capability-service` | - | Capability project identity; non-null project_code is unique across registration and catalog writes |
| `capability_scan_project_tool` | `reachai-capability-service` | - | Capability scan project tool |
| `capability_semantic_doc` | `reachai-capability-service` | - | Capability semantic document |
| `control_tool_acl` | `reachai-control-service` | - | Control tool ACL |
| `capability_tool_asset` | `reachai-capability-service` | - | Capability execution action; legacy technical table name |
| `runtime_tool_call_log` | `reachai-runtime-service` | - | Runtime Tool call audit |
| `capability_tool_definition` | `reachai-capability-service` | - | Runtime Tool projection for governed Capability/API execution; not a product asset |
| `capability_tool_retrieval_setting` | `reachai-capability-service` | - | Capability tool retrieval setting |
| `knowledge_user_file_permission` | `reachai-knowledge-service` | - | Knowledge file permission; file retirement revokes grants in the same transaction, subsequent reads use current database rows |
