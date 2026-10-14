-- 移除无效的调用候选索引重建配置（MySQL 5.7 / 8，幂等）。
-- 影响：删除 capability_tool_retrieval_setting 及其中仅用于重建的模型实例选择。
-- 保留：业务方法/API 资产、capability_tool_definition 调用投影、知识库和所有向量数据。
-- 先部署已删除重建任务与设置读取的 Capability 版本，再执行本清理脚本。
-- 执行前备份本表；本脚本不用于仍在运行旧重建实现的环境。
-- 执行后回读 information_schema.tables，确认本表不存在。

DROP TABLE IF EXISTS `capability_tool_retrieval_setting`;
