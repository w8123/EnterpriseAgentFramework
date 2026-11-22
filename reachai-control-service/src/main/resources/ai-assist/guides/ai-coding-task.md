# 继续 AI 编程任务

> 验证情况：按平台 MCP 工具契约提供操作步骤；实际项目的配置、权限和执行结果仍需逐项核对。

下文“本次用户指定”或“本次页面交接”的值来自当前任务上下文；这些说明文字不是可直接提交的参数。缺少必要信息时先查询授权目录或向用户确认。

1. 调用 reachai_ai_coding_task_claim，taskId="本次页面交接的任务编号"，claimCode="本次页面签发的一次性领取码"。领取码只能使用一次，不要输出或保存。
2. 调用 reachai_ai_coding_task_context 读取任务目标、范围、所需资源和验收契约，按任务上下文完成工作。
3. 用 reachai_ai_coding_task_event 写进度，遇到问题用 reachai_ai_coding_task_ask 提问，并在长时间操作前后调用 reachai_ai_coding_task_heartbeat。
4. 用 reachai_ai_coding_task_artifact 和任务要求的验证工具提交证据；如实说明任务状态，等待用户在 ReachAI 验收。

任务规则：
- 读取或修改仓库前先读取任务 context；下载并打开 requiredBeforeEditing=true 的 requiredResources，遵守 scope、protocolGuide 和 Artifact 契约。不要扫描无关模块。
- READY / RUNNING 按 context 的事件规则写 STARTED；WAITING_USER 先读取问题，存在未回答问题时不要写 STARTED 或 RESUMED；全部回答且已读取后才写 RESUMED。RESULT_APPLIED 不要重新写 STARTED，先核对平台验证缺口。
- 写真实进度，长时间操作前后及执行期间至少每两分钟发送心跳；同一 Artifact 内容重试保持 artifactKey，内容修正按契约使用新的 revision key。
- 换对话后使用原平台连接继续已领取的任务，不要再次使用已消费的领取码。连接失效时请用户重新生成领取码；不要打印、保存或提交领取码和凭据。
- 不安装常驻程序或后台进程；保留已有改动。执行任务要求的测试和浏览器自检，只报告真实证据。停止本地会话前写回真实进度，ReachAI 不会自动唤醒它。
- 仅当返回 ACCEPTANCE_READY 或 COMPLETED 时提示用户回到 ReachAI 验收；RESULT_APPLIED 表示结果已回传但仍待平台验证，其余状态如实说明。
