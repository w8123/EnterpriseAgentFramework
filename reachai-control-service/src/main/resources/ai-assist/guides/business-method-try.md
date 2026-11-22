# 检查并试用已接入的业务方法

> 验证情况：按平台 MCP 工具契约提供操作步骤；实际项目的配置、权限和执行结果仍需逐项核对。

下文“本次用户指定”或“本次页面交接”的值来自当前任务上下文；这些说明文字不是可直接提交的参数。缺少必要信息时先查询授权目录或向用户确认。

帮我检查 ReachAI 项目「{{projectCode}}」已接入的业务方法。

1. 用 reachai_business_methods_list 列出全部，标出缺少中文说明、参数说明不清或读写类型可疑的。
2. 对只读方法：先 reachai_business_method_invocation_context 固定契约，再 reachai_business_method_invoke_read_only 试调用，记录结果。
3. 说明需要补的地方用 reachai_business_capability_semantic_generate 生成草稿给我看，不要直接保存。
4. 最后给我一张表：方法、读写、试调用结果、建议。
