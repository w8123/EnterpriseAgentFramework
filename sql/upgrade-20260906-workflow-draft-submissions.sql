-- Runtime Workflow 草稿投递凭据。只新增表，不重建旧任务请求或自动接管旧草稿。
-- 新请求重复投递时读取当前草稿；修正请求只能覆盖上一投递记录对应的未编辑修订。
-- 执行影响：先执行本脚本，再部署使用投递凭据的 Runtime；无历史数据更新或删除。
-- 旧任务若与已有草稿命名冲突，需要创建新任务或由用户在 Studio 明确编辑，不猜测旧请求指纹。

CREATE TABLE IF NOT EXISTS `runtime_workflow_draft_submission` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `workflow_id`       VARCHAR(64)  NOT NULL COMMENT '由任务作用域确定的 Workflow ID',
    `request_hash`      CHAR(64)     NOT NULL COMMENT '规范化请求 SHA-256，不保存原始请求或凭据',
    `source_type`       VARCHAR(48)  NOT NULL COMMENT 'PAGE_WORKBENCH / RUNOPS_TRACE_CANDIDATE',
    `project_id`        BIGINT       DEFAULT NULL,
    `project_code`      VARCHAR(96)  NOT NULL,
    `task_id`           VARCHAR(64)  NOT NULL,
    `target_key`        VARCHAR(255) NOT NULL COMMENT '来源页面或 Trace 标识',
    `workflow_revision` DATETIME(6)  DEFAULT NULL COMMENT '成功应用后的 runtime_workflow.updated_at；事务内预约期间为空',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workflow_draft_request` (`workflow_id`, `request_hash`),
    KEY `idx_workflow_draft_revision` (`workflow_id`, `workflow_revision`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow 任务草稿的幂等投递与自动修正凭据';
