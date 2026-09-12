-- Workflow 能力引用索引，由 reachai-runtime-service 独占维护。
-- 先执行本文件再启动新版 Runtime；新库请直接使用 sql/initV2.sql。
-- 只创建可重建的派生数据表，不修改 Workflow、发布快照或 Agent 配置。
-- Runtime 启动时分批补齐缺失索引；完成前影响查询显示 PARTIAL。
-- 后续保存草稿、发布版本和删除 Workflow 与索引写入同事务。
CREATE TABLE IF NOT EXISTS `runtime_workflow_capability_reference` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_id` VARCHAR(32) NOT NULL,
    `workflow_version_id` BIGINT NOT NULL COMMENT '0 为当前草稿；正数为不可变发布版本',
    `node_ordinal` INT NOT NULL COMMENT '-1 为覆盖状态，其余为 GraphSpec 节点序号',
    `node_id` TEXT DEFAULT NULL,
    `reference_key` VARCHAR(256) COLLATE utf8mb4_bin DEFAULT NULL,
    `state` VARCHAR(16) NOT NULL COMMENT 'READY / PARTIAL',
    `warnings_json` TEXT DEFAULT NULL,
    `indexed_revision` DATETIME DEFAULT NULL COMMENT '草稿索引对应的 updated_at；发布版本不变',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workflow_reference_node` (`workflow_id`, `workflow_version_id`, `node_ordinal`),
    KEY `idx_workflow_reference_key` (`reference_key`(191)),
    KEY `idx_workflow_reference_coverage` (`node_ordinal`, `state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow 自有能力引用索引；缺失或过期索引不得视为零引用';

SELECT state, COUNT(*) AS graph_count
FROM runtime_workflow_capability_reference WHERE node_ordinal = -1 GROUP BY state;
