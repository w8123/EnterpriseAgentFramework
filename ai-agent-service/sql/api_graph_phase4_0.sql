-- =============================================================================
-- Phase 4.0 接口图谱（ApiCallGraph）一期：节点 + 边 + 布局
-- =============================================================================
-- 目标：把扫描出来的接口（API）、参数（FIELD_IN / FIELD_OUT）、复合数据模型（DTO）、
--       模块（MODULE）抽象为图谱节点；扫描完成后自动生成「数据模型共享」紫色虚线边
--       （MODEL_REF）；运营在画布上手动连接「请求引用 / 响应引用」蓝/绿边；前端按
--       项目维度拉取整张图后由 G6 渲染。
--
-- 设计取舍（详见 docs/接口图谱-设计与落地.md）：
--   1. 一期不引入图数据库（Neo4j / Nebula）。规模 < 5K 节点 / 10K 边时 MySQL 足矣，
--      所有图访问统一走 ApiGraphRepository 抽象，方便二期按需替换为图 DB 副本。
--   2. 节点持久化（不是纯虚拟投影）：DTO/字段都需要稳定主键，运营才能在节点上挂
--      备注 / 布局 / 手动边而不在重扫时丢失。
--   3. 边来源 source ∈ {auto, manual}：manual 永不被自动重算覆盖；auto 边在重新
--      推断时按 (project_id, kind, source, target) 维度幂等 upsert。
--   4. 不污染既有路径：扫描完成在 ScanProjectService.performScan 末尾旁路 hook，
--      失败不影响扫描主链路。
--
-- 与 sql/init.sql 八（接口图谱）节保持一致。幂等可重入。
-- =============================================================================

USE `ai_text_service`;

CREATE TABLE IF NOT EXISTS `api_graph_node` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`    BIGINT       NOT NULL                       COMMENT '所属扫描项目 scan_project.id',
    `kind`          VARCHAR(16)  NOT NULL                       COMMENT 'API / FIELD_IN / FIELD_OUT / DTO / MODULE',
    `ref_id`        BIGINT       DEFAULT NULL                   COMMENT '业务表外键：API→scan_project_tool.id，MODULE→scan_module.id，FIELD/DTO 为空',
    `parent_id`     BIGINT       DEFAULT NULL                   COMMENT '字段树嵌套时指向父字段节点；DTO 内部 children 也建节点',
    `label`         VARCHAR(255) NOT NULL                       COMMENT '展示名（字段名 / 接口名 / 模块名 / DTO 简名）',
    `type_name`     VARCHAR(255) DEFAULT NULL                   COMMENT '字段类型 / DTO 全名（含泛型，如 List<RoleDTO>）',
    `props_json`    TEXT         DEFAULT NULL                   COMMENT '附加属性 JSON：required / location / paramPath / httpMethod / endpointPath / aiDescription 等',
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_node_identity` (`project_id`, `kind`, `ref_id`, `parent_id`, `label`),
    KEY `idx_project_kind` (`project_id`, `kind`),
    KEY `idx_parent`       (`parent_id`),
    KEY `idx_type`         (`project_id`, `type_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口图谱节点（Phase 4.0）';

CREATE TABLE IF NOT EXISTS `api_graph_edge` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`      BIGINT       NOT NULL                     COMMENT '所属扫描项目 scan_project.id（与两端节点同项目）',
    `source_node_id`  BIGINT       NOT NULL                     COMMENT '边起点 api_graph_node.id',
    `target_node_id`  BIGINT       NOT NULL                     COMMENT '边终点 api_graph_node.id',
    `kind`            VARCHAR(16)  NOT NULL                     COMMENT 'REQUEST_REF（蓝） / RESPONSE_REF（绿） / MODEL_REF（紫虚线） / BELONGS_TO',
    `source`          VARCHAR(8)   NOT NULL DEFAULT 'manual'    COMMENT 'auto（系统自动推断） / manual（运营手动连线，永不被覆盖）',
    `confidence`      DOUBLE       DEFAULT NULL                 COMMENT '0~1，自动推断置信度；手动连线为 1.0',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'CONFIRMED' COMMENT 'CANDIDATE / CONFIRMED / REJECTED',
    `infer_strategy`  VARCHAR(32)  DEFAULT NULL                 COMMENT 'schema_match / dto_match / trace_value_match / llm_assisted',
    `confirmed_by`    VARCHAR(64)  DEFAULT NULL                 COMMENT '确认人',
    `confirmed_at`    DATETIME     DEFAULT NULL                 COMMENT '确认时间',
    `reject_reason`   VARCHAR(512) DEFAULT NULL                 COMMENT '拒绝原因',
    `evidence_json`   TEXT         DEFAULT NULL                 COMMENT '推断依据 JSON：例如 {"by":"shared_type","type":"UserDTO"}',
    `note`            VARCHAR(512) DEFAULT NULL                 COMMENT '运营备注',
    `enabled`         TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_edge_identity` (`project_id`, `kind`, `source_node_id`, `target_node_id`, `source`),
    KEY `idx_project_kind` (`project_id`, `kind`),
    KEY `idx_project_status` (`project_id`, `status`),
    KEY `idx_source_node`  (`source_node_id`),
    KEY `idx_target_node`  (`target_node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口图谱边（Phase 4.0）';

DROP PROCEDURE IF EXISTS add_col_if_absent_api_graph;
DROP PROCEDURE IF EXISTS add_idx_if_absent_api_graph;

DELIMITER $$

CREATE PROCEDURE add_col_if_absent_api_graph(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name   = p_table
          AND column_name  = p_column
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE add_idx_if_absent_api_graph(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_columns VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name   = p_table
          AND index_name   = p_index
    ) THEN
        SET @sql = CONCAT('CREATE INDEX `', p_index, '` ON `', p_table, '` (', p_columns, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL add_col_if_absent_api_graph('api_graph_edge', 'status', 'VARCHAR(16) NOT NULL DEFAULT ''CONFIRMED'' COMMENT ''CANDIDATE / CONFIRMED / REJECTED'' AFTER `confidence`');
CALL add_col_if_absent_api_graph('api_graph_edge', 'infer_strategy', 'VARCHAR(32) DEFAULT NULL COMMENT ''schema_match / dto_match / trace_value_match / llm_assisted'' AFTER `status`');
CALL add_col_if_absent_api_graph('api_graph_edge', 'confirmed_by', 'VARCHAR(64) DEFAULT NULL COMMENT ''确认人'' AFTER `infer_strategy`');
CALL add_col_if_absent_api_graph('api_graph_edge', 'confirmed_at', 'DATETIME DEFAULT NULL COMMENT ''确认时间'' AFTER `confirmed_by`');
CALL add_col_if_absent_api_graph('api_graph_edge', 'reject_reason', 'VARCHAR(512) DEFAULT NULL COMMENT ''拒绝原因'' AFTER `confirmed_at`');
CALL add_idx_if_absent_api_graph('api_graph_edge', 'idx_project_status', '`project_id`, `status`');

DROP PROCEDURE IF EXISTS add_col_if_absent_api_graph;
DROP PROCEDURE IF EXISTS add_idx_if_absent_api_graph;

CREATE TABLE IF NOT EXISTS `api_graph_layout` (
    `id`         BIGINT     NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT     NOT NULL                  COMMENT '所属扫描项目 scan_project.id',
    `node_id`    BIGINT     NOT NULL                  COMMENT '目标节点 api_graph_node.id',
    `x`          DOUBLE     NOT NULL DEFAULT 0,
    `y`          DOUBLE     NOT NULL DEFAULT 0,
    `ext_json`   TEXT       DEFAULT NULL              COMMENT '扩展：折叠状态 / 锁定 / 自定义颜色等',
    `updated_at` DATETIME   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_node` (`project_id`, `node_id`),
    KEY `idx_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口图谱画布布局（Phase 4.0）';

-- END OF api_graph_phase4_0.sql
