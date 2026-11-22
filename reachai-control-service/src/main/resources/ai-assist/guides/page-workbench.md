# 让业务页面支持 AI 操作（业务页面工作台）

> 验证情况：按平台 MCP 工具契约提供操作步骤；实际项目的配置、权限和执行结果仍需逐项核对。

下文“本次用户指定”或“本次页面交接”的值来自当前任务上下文；这些说明文字不是可直接提交的参数。缺少必要信息时先查询授权目录或向用户确认。

在当前业务系统前端仓库里完成 ReachAI 项目「{{projectCode}}」的业务页面接入任务。

1. 先领取任务：调用 reachai_ai_coding_task_claim，taskId="本次页面交接的任务编号"，claimCode="本次页面签发的一次性领取码"。
2. 调用 reachai_ai_coding_task_context 读取任务上下文，再用 reachai_page_workbench_read（projectCode="{{projectCode}}"）看页面列表、就绪度和分析发现。
3. 按上下文的范围改页面代码，只动任务涉及的页面；不扫描无关模块。
4. 执行中用 reachai_ai_coding_task_event 写真实进度，有歧义用 reachai_ai_coding_task_ask 提问，长时间构建前后调用 reachai_ai_coding_task_heartbeat。
5. 在真实浏览器里自检页面动作，再用 reachai_ai_coding_task_artifact 提交结果。
6. 返回 ACCEPTANCE_READY 或 COMPLETED 时告诉我「现在可以回到 ReachAI 验收」；其他状态如实说明。

领取任务后，再读取 reachai_integration_guide（topic="ai-coding-task"）中的状态恢复、心跳和验收规则。
