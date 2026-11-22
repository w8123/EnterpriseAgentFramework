# 用一句话建一个 Workflow

> 验证情况：按平台 MCP 工具契约提供操作步骤；实际项目的配置、权限和执行结果仍需逐项核对。

下文“本次用户指定”或“本次页面交接”的值来自当前任务上下文；这些说明文字不是可直接提交的参数。缺少必要信息时先查询授权目录或向用户确认。

帮我在 ReachAI 里做一个 Workflow（项目：{{projectCode}}；已有 Workflow：本次用户指定的已有 Workflow）。我要它做的事：本次用户的真实需求

1. 有已有 Workflow 就先 reachai_workflow_working_copy 读工作副本；没有就 reachai_workflow_create_draft 新建（先用 reachai_workflows_search 确认没有同名）。
2. 用 reachai_workflow_node_types 和 reachai_business_methods_list 选节点和能力。
3. 每次改动先 reachai_workflow_preview_patch 预览，再 reachai_workflow_save_patch 保存（用刚读到的 revision）。
4. reachai_workflow_validate_release 校验通过后，用 reachai_workflow_trial_read_only 只读试运行一次。
5. 把流程图用文字讲给我听，我同意后再 reachai_workflow_request_publish 申请发布。
