# 调整一个智能体的配置

> 验证情况：按平台 MCP 工具契约提供操作步骤；实际项目的配置、权限和执行结果仍需逐项核对。

下文“本次用户指定”或“本次页面交接”的值来自当前任务上下文；这些说明文字不是可直接提交的参数。缺少必要信息时先查询授权目录或向用户确认。

帮我调整 ReachAI 里的智能体（本次用户指定的智能体编号；未指定时先用 reachai_agents_list 让我选）。我想改的是：本次用户的真实需求

1. 用 reachai_agent_draft_context 读可编辑配置和 revision。
2. 需要新工具或知识库时，用 reachai_tools_list、reachai_knowledge_bases_list、reachai_skills_list 找候选给我确认。
3. 用 reachai_agent_save_draft 按 revision 保存草稿；冲突时重新读取再合并。
4. 把改动前后对比讲给我听，我同意后用 reachai_agent_request_publish 申请发布。
