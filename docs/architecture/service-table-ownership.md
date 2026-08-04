# ReachAI Service Table Ownership

> Date: 2026-07-14
> Scope: first physical split with one shared MySQL database

## Rules

- One shared MySQL database is allowed in this phase, but each table still has one owner service.
- New code must not directly write another service's owned table.
- Application code access is the source of truth for boundary violations: `@TableName`, MyBatis annotation SQL, MyBatis XML SQL, and JdbcTemplate SQL are checked.
- `sql/initV2.sql` is the long-term SQL baseline and every `CREATE TABLE` row must appear in this matrix, even if the table has no current direct code access.
- Temporary same-database reads or compatibility writes must be documented in `Additional direct access` with the accessing service and reason.
- The current target state is zero cross-service direct table access in application code; service collaboration must go through internal HTTP APIs, explicit clients, or service-owned read models.
- The current baseline targets a new database: legacy names such as `ai_workflow`, `ai_agent`, `skill_draft`, `skill_interaction`, `eaf_page_registry`, and `tool_call_log` are replaced by service/domain-prefixed names. No old-table compatibility views or data migration scripts are required.

## Shared Table Exceptions Retired

The current scan finds no direct table access shared across physical services. Former first-phase exceptions have been moved behind internal service APIs:

- `control_page_action`: owned by `reachai-control-service`; Runtime calls the Control internal page-action catalog API.
- `runtime_skill_interaction`: owned by `reachai-runtime-service`; Capability now calls Runtime internal interaction APIs.
- `runtime_tool_call_log`: owned by `reachai-runtime-service`; Capability now calls Runtime internal trace/tool-call-log APIs.

These rows remain in the ownership matrix because the current baseline still records their owner service, not because direct cross-service table access is still allowed.

## Ownership Matrix

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
| `control_a2a_call_log` | `reachai-control-service` | - | External A2A call audit |
| `control_a2a_endpoint` | `reachai-control-service` | - | External A2A endpoint catalog |
| `control_a2a_task` | `reachai-control-service` | - | A2A task state |
| `runtime_agent_eval_case` | `reachai-runtime-service` | - | Runtime evaluation case |
| `runtime_agent_eval_case_result` | `reachai-runtime-service` | - | Runtime evaluation result |
| `runtime_agent_eval_dataset` | `reachai-runtime-service` | - | Runtime evaluation dataset |
| `runtime_agent_eval_run` | `reachai-runtime-service` | - | Runtime evaluation run |
| `runtime_run` | `reachai-runtime-service` | - | RunOps root execution fact used by lists, KPIs, diagnostics, and replay |
| `runtime_internal_auth_nonce` | `reachai-runtime-service` | - | Control→Runtime HMAC nonce anti-replay store (multi-instance safe) |
| `runtime_trace_span` | `reachai-runtime-service` | - | Runtime generic trace span child event |
| `runtime_agent_workflow_credential` | `reachai-runtime-service` | - | Workflow credential vault metadata |
| `runtime_agent` | `reachai-runtime-service` | - | Runtime agent catalog |
| `runtime_agent_config_version` | `reachai-runtime-service` | - | Versioned Agent Supervisor configuration |
| `runtime_agent_workflow_tool` | `reachai-runtime-service` | - | Workflow-as-Tool allow-list per Agent configuration version |
| `model_template` | `reachai-model-service` | - | Model Center V2 catalog templates (no secrets) |
| `model_instance` | `reachai-model-service` | - | Model Center V2 executable instances (stable modelInstanceId) |
| `capability_project_instance` | `reachai-capability-service` | - | SDK access-instance heartbeat |
| `runtime_workflow` | `reachai-runtime-service` | - | Workflow runtime definition |
| `runtime_workflow_version` | `reachai-runtime-service` | - | Workflow release snapshot |
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
| `capability_sync_log` | `reachai-capability-service` | - | Capability sync log |
| `knowledge_chunk` | `reachai-knowledge-service` | - | Knowledge knowledge_chunk storage |
| `capability_composition_definition` | `reachai-capability-service` | - | Capability composition definition |
| `control_context_audit_event` | `reachai-control-service` | - | Control context governance audit |
| `control_context_binding` | `reachai-control-service` | - | Control context binding |
| `control_context_evidence` | `reachai-control-service` | - | Control context evidence |
| `control_context_item` | `reachai-control-service` | - | Control context item |
| `control_context_memory_candidate` | `reachai-control-service` | - | Control memory candidate review buffer |
| `control_context_namespace` | `reachai-control-service` | - | Control context namespace |
| `control_context_runtime_user_mapping` | `reachai-control-service` | - | Control to Runtime user mapping |
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
| `control_field_extractor_binding` | `reachai-control-service` | - | Control slot extraction binding |
| `knowledge_file_info` | `reachai-knowledge-service` | - | Knowledge file metadata |
| `runtime_guard_decision_log` | `reachai-runtime-service` | - | Runtime guard decision log |
| `capability_interaction_definition` | `reachai-capability-service` | - | Capability interaction definition |
| `runtime_interaction_event` | `reachai-runtime-service` | - | Runtime interaction event |
| `runtime_interaction_session` | `reachai-runtime-service` | - | Runtime interaction session |
| `knowledge_base` | `reachai-knowledge-service` | - | Knowledge base |
| `knowledge_hit_log` | `reachai-knowledge-service` | - | Knowledge retrieval hit log |
| `knowledge_question` | `reachai-knowledge-service` | - | Knowledge question curation |
| `knowledge_tag` | `reachai-knowledge-service` | - | Knowledge tag |
| `control_market_item` | `reachai-control-service` | - | Control marketplace item |
| `control_mcp_call_log` | `reachai-control-service` | - | MCP external call log |
| `control_mcp_client` | `reachai-control-service` | - | MCP client credential |
| `control_mcp_visibility` | `reachai-control-service` | - | MCP tool visibility |
| `control_platform_auth_provider` | `reachai-control-service` | - | Platform auth provider |
| `control_platform_login_session` | `reachai-control-service` | - | Platform login session |
| `control_platform_permission` | `reachai-control-service` | - | Platform permission catalog |
| `control_platform_role` | `reachai-control-service` | - | Platform role |
| `control_platform_role_permission` | `reachai-control-service` | - | Platform role permission binding |
| `control_platform_user` | `reachai-control-service` | - | Platform user |
| `control_platform_user_role` | `reachai-control-service` | - | Platform user role |
| `capability_registry_project_credential` | `reachai-capability-service` | - | SDK registry credential |
| `capability_scan_module` | `reachai-capability-service` | - | Capability scan module |
| `capability_scan_project` | `reachai-capability-service` | - | Capability scan project |
| `capability_scan_project_tool` | `reachai-capability-service` | - | Capability scan project tool |
| `capability_semantic_doc` | `reachai-capability-service` | - | Capability semantic document |
| `capability_draft` | `reachai-capability-service` | - | Compatibility-sensitive capability draft storage |
| `capability_eval_snapshot` | `reachai-capability-service` | - | Compatibility-sensitive capability evaluation snapshot |
| `runtime_skill_interaction` | `reachai-runtime-service` | - | Capability admin-test Composition flows use Runtime internal interaction APIs |
| `control_slot_dict_dept` | `reachai-control-service` | - | Control slot department dictionary |
| `control_slot_dict_user` | `reachai-control-service` | - | Control slot user dictionary |
| `control_slot_extract_log` | `reachai-control-service` | - | Control slot extraction log |
| `control_tool_acl` | `reachai-control-service` | - | Control tool ACL |
| `capability_tool_asset` | `reachai-capability-service` | - | Capability tool asset |
| `runtime_tool_call_log` | `reachai-runtime-service` | - | Capability mining and metrics read/write demo trace data through Runtime internal trace APIs |
| `capability_tool_definition` | `reachai-capability-service` | - | Capability tool definition |
| `capability_tool_retrieval_setting` | `reachai-capability-service` | - | Capability tool retrieval setting |
| `knowledge_user_file_permission` | `reachai-knowledge-service` | - | Knowledge file permission |
