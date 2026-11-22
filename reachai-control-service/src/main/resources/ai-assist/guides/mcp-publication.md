# 把能力开放给员工的 AI 工具

> 验证情况：按平台 MCP 工具契约提供操作步骤；实际项目的配置、权限和执行结果仍需逐项核对。

下文“本次用户指定”或“本次页面交接”的值来自当前任务上下文；这些说明文字不是可直接提交的参数。缺少必要信息时先查询授权目录或向用户确认。

帮我把 ReachAI 项目「{{projectCode}}」的常用能力开放给员工的 AI 工具（Codex、Claude Code 等）。项目未指定时先用 reachai_projects_list 列出让我选。

1. 用 reachai_mcp_publications_list 看已有的发布，避免重复。
2. 列出候选能力（reachai_business_methods_list、reachai_http_apis_list、reachai_workflows_list），按只读 / 写操作分组给我选。
3. 我确认后：reachai_mcp_publication_create 建草稿，reachai_mcp_publication_add_item 逐个添加。
4. 用 reachai_mcp_publication_precheck 做发布前检查，再用 reachai_mcp_publication_request_change 申请发布，告诉我去审核。
