# ReachAI Service Table Ownership

> Date: 2026-07-01
> Scope: first physical split with one shared MySQL database

## Rules

- One shared MySQL database is allowed in this phase, but each table still has one owner service.
- New code must not directly write another service's owned table.
- Temporary same-database reads or compatibility writes must be documented in `Additional direct access`.
- The current target state is zero cross-service direct table access in application code; service collaboration must go through internal HTTP APIs.

## Shared Table Exceptions Retired

The current scan finds no direct table access shared across physical services. Former first-phase exceptions have been moved behind internal service APIs:

- `eaf_page_action_registry`: owned by `reachai-control-service`; Runtime now calls Control internal page-action catalog API.
- `skill_interaction`: owned by `reachai-runtime-service`; Capability now calls Runtime internal interaction APIs.
- `tool_call_log`: owned by `reachai-runtime-service`; Capability now calls Runtime internal trace/tool-call-log APIs.

These rows remain in the ownership matrix because the storage names are compatibility-sensitive, not because direct cross-service table access is still allowed.

## Ownership Matrix

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
| `a2a_call_log` | `reachai-control-service` | - | External A2A call audit |
| `a2a_endpoint` | `reachai-control-service` | - | External A2A endpoint catalog |
| `a2a_task` | `reachai-control-service` | - | A2A task state |
| `agent_eval_case` | `reachai-runtime-service` | - | Runtime evaluation case |
| `agent_eval_case_result` | `reachai-runtime-service` | - | Runtime evaluation result |
| `agent_eval_dataset` | `reachai-runtime-service` | - | Runtime evaluation dataset |
| `agent_eval_run` | `reachai-runtime-service` | - | Runtime evaluation run |
| `agent_trace_span` | `reachai-runtime-service` | - | Runtime trace span |
| `agent_workflow_credential` | `reachai-runtime-service` | - | Workflow credential vault metadata |
| `ai_agent` | `reachai-runtime-service` | - | Runtime agent catalog |
| `ai_agent_workflow_binding` | `reachai-runtime-service` | - | Agent to Workflow binding |
| `ai_model_instance` | `reachai-model-service` | - | Model Gateway instance registry |
| `ai_project_instance` | `reachai-capability-service` | - | SDK runtime instance heartbeat |
| `ai_workflow` | `reachai-runtime-service` | - | Workflow runtime definition |
| `ai_workflow_version` | `reachai-runtime-service` | - | Workflow release snapshot |
| `api_graph_edge` | `reachai-capability-service` | - | Capability API graph edge |
| `api_graph_layout` | `reachai-capability-service` | - | Capability API graph layout |
| `api_graph_node` | `reachai-capability-service` | - | Capability API graph node |
| `business_index` | `reachai-knowledge-service` | - | Knowledge business index |
| `business_index_attachment` | `reachai-knowledge-service` | - | Knowledge business index attachment |
| `business_index_record` | `reachai-knowledge-service` | - | Knowledge business index record |
| `capability_apply_record` | `reachai-capability-service` | - | Capability review apply history |
| `capability_diff_item` | `reachai-capability-service` | - | Capability diff item |
| `capability_module` | `reachai-capability-service` | - | Capability module catalog |
| `capability_snapshot` | `reachai-capability-service` | - | Capability snapshot |
| `capability_sync_log` | `reachai-capability-service` | - | Capability sync log |
| `chunk` | `reachai-knowledge-service` | - | Knowledge chunk storage |
| `composition_definition` | `reachai-capability-service` | - | Capability composition definition |
| `context_audit_event` | `reachai-control-service` | - | Control context governance audit |
| `context_binding` | `reachai-control-service` | - | Control context binding |
| `context_evidence` | `reachai-control-service` | - | Control context evidence |
| `context_item` | `reachai-control-service` | - | Control context item |
| `context_memory_candidate` | `reachai-control-service` | - | Control memory candidate review buffer |
| `context_namespace` | `reachai-control-service` | - | Control context namespace |
| `context_runtime_user_mapping` | `reachai-control-service` | - | Control to Runtime user mapping |
| `domain_assignment` | `reachai-capability-service` | - | Capability domain assignment |
| `domain_def` | `reachai-capability-service` | - | Capability domain definition |
| `eaf_business_user` | `reachai-control-service` | - | Control business user directory |
| `eaf_embed_chat_event` | `reachai-control-service` | - | Embed chat transcript |
| `eaf_embed_renderer` | `reachai-control-service` | - | Embed renderer catalog |
| `eaf_embed_session` | `reachai-control-service` | - | Embed session state |
| `eaf_external_user_binding` | `reachai-control-service` | - | External user binding |
| `eaf_external_user_role_binding` | `reachai-control-service` | - | External user role binding |
| `eaf_page_action_event` | `reachai-control-service` | - | Browser page action event |
| `eaf_page_action_registry` | `reachai-control-service` | - | Runtime release validation reads page action definitions through Control internal API |
| `eaf_page_registry` | `reachai-control-service` | - | SDK page registry |
| `executable_debug_session` | `reachai-runtime-service` | - | Runtime executable debug session |
| `field_extractor_binding` | `reachai-control-service` | - | Control slot extraction binding |
| `file_info` | `reachai-knowledge-service` | - | Knowledge file metadata |
| `guard_decision_log` | `reachai-runtime-service` | - | Runtime guard decision log |
| `interaction_definition` | `reachai-capability-service` | - | Capability interaction definition |
| `interaction_event` | `reachai-runtime-service` | - | Runtime interaction event |
| `interaction_session` | `reachai-runtime-service` | - | Runtime interaction session |
| `knowledge_base` | `reachai-knowledge-service` | - | Knowledge base |
| `knowledge_hit_log` | `reachai-knowledge-service` | - | Knowledge retrieval hit log |
| `knowledge_question` | `reachai-knowledge-service` | - | Knowledge question curation |
| `knowledge_tag` | `reachai-knowledge-service` | - | Knowledge tag |
| `market_item` | `reachai-control-service` | - | Control marketplace item |
| `mcp_call_log` | `reachai-control-service` | - | MCP external call log |
| `mcp_client` | `reachai-control-service` | - | MCP client credential |
| `mcp_visibility` | `reachai-control-service` | - | MCP tool visibility |
| `platform_auth_provider` | `reachai-control-service` | - | Platform auth provider |
| `platform_login_session` | `reachai-control-service` | - | Platform login session |
| `platform_role` | `reachai-control-service` | - | Platform role |
| `platform_user` | `reachai-control-service` | - | Platform user |
| `platform_user_role` | `reachai-control-service` | - | Platform user role |
| `registry_project_credential` | `reachai-capability-service` | - | SDK registry credential |
| `scan_module` | `reachai-capability-service` | - | Capability scan module |
| `scan_project` | `reachai-capability-service` | - | Capability scan project |
| `scan_project_tool` | `reachai-capability-service` | - | Capability scan project tool |
| `semantic_doc` | `reachai-capability-service` | - | Capability semantic document |
| `skill_draft` | `reachai-capability-service` | - | Compatibility-sensitive capability draft storage |
| `skill_interaction` | `reachai-runtime-service` | - | Capability admin-test Composition flows use Runtime internal interaction APIs |
| `slot_dict_dept` | `reachai-control-service` | - | Control slot department dictionary |
| `slot_dict_user` | `reachai-control-service` | - | Control slot user dictionary |
| `slot_extract_log` | `reachai-control-service` | - | Control slot extraction log |
| `tool_acl` | `reachai-control-service` | - | Control tool ACL |
| `tool_asset` | `reachai-capability-service` | - | Capability tool asset |
| `tool_call_log` | `reachai-runtime-service` | - | Capability mining and metrics read/write demo trace data through Runtime internal trace APIs |
| `tool_definition` | `reachai-capability-service` | - | Capability tool definition |
| `tool_retrieval_setting` | `reachai-capability-service` | - | Capability tool retrieval setting |
| `user_file_permission` | `reachai-knowledge-service` | - | Knowledge file permission |
