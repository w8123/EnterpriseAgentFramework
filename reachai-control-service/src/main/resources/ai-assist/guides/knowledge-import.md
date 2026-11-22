# 把本地文档导入知识库

> 验证情况：按平台 MCP 工具契约提供操作步骤；实际项目的配置、权限和执行结果仍需逐项核对。

下文“本次用户指定”或“本次页面交接”的值来自当前任务上下文；这些说明文字不是可直接提交的参数。缺少必要信息时先查询授权目录或向用户确认。

帮我把这些文档导入 ReachAI 知识库（知识库编码：本次用户指定的知识库编码；未指定时先用 reachai_knowledge_bases_list 让我选，或问我要不要用 reachai_knowledge_base_create 新建）。文档：本次用户的真实需求

1. 按 reachai_upload_get 的说明把每个文件 POST 到 /mcp/platform/uploads 拿到 uploadId（单个最大 20MB）。
2. 用 reachai_knowledge_import_job_submit 提交导入，reachai_knowledge_import_job_get 等解析完成。
3. 用 reachai_knowledge_import_job_request_commit 申请入库，告诉我去审核。
4. 入库后用 reachai_knowledge_retrieval_test 拿两三个问题测一下命中情况。
