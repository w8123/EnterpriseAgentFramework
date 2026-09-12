-- Workflow 发布/回滚事件。新库只执行 initV2.sql，已有开发/测试库执行本文件。
-- DDL 自动提交；确认目标库并备份后执行，再部署 Runtime/Control/前端。
-- 保留历史版本；历史未记录的回滚事件不进行推测回填。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `runtime_workflow_release_event` (
    `id`                  BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_id`         VARCHAR(32) NOT NULL,
    `action`              VARCHAR(24) NOT NULL COMMENT 'PUBLISH / ROLLBACK',
    `previous_version_id` BIGINT DEFAULT NULL,
    `target_version_id`   BIGINT NOT NULL,
    `actor`               VARCHAR(64) NOT NULL COMMENT '可信平台身份或服务端工作流身份',
    `base_revision`       VARCHAR(64) NOT NULL COMMENT '操作已校验的草稿修订',
    `occurred_at`         DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_workflow_release_event` (`workflow_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow 发布与回滚事件；不改写原始发布人和时间';

-- 如有历史重复活动版本，先调查并明确保留哪个版本；新发布逻辑拒绝静默选择。
SELECT workflow_id, COUNT(*) AS active_version_count
FROM runtime_workflow_version WHERE status = 'ACTIVE'
GROUP BY workflow_id HAVING COUNT(*) > 1;
