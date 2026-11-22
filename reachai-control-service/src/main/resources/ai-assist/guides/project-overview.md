# 看看项目接入到哪一步了

> 验证情况：按平台 MCP 工具契约提供操作步骤；实际项目的配置、权限和执行结果仍需逐项核对。

下文“本次用户指定”或“本次页面交接”的值来自当前任务上下文；这些说明文字不是可直接提交的参数。缺少必要信息时先查询授权目录或向用户确认。

帮我看看 ReachAI 项目「{{projectCode}}」现在接入到哪一步了（未指定时先用 reachai_projects_list 列出我能看的项目让我选）。

1. 用 reachai_business_system_get 读项目配置和接入状态，用 reachai_registry_instances 看 SDK 实例是否在线。
2. 用 reachai_business_methods_list 统计已接入的业务方法，按只读 / 写操作分开。
3. 用 reachai_page_workbench_read 看业务页面接入情况。
4. 用 reachai_doctor 做一次体检。
5. 用一张表告诉我：已完成、有问题（附修法）、下一步建议。只读，不做任何修改。
