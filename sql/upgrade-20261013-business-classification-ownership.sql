-- 业务分类持久归属。先备份，停止 Capability 写入后执行；MySQL 5.7/8。
-- 非破坏性且可重跑：保留已有正式成员及来源分类，人工状态包括以后保存的空集合。
-- 回填只使用同项目稳定业务标识，或同环境、method/route 且 source_location 精确相同的唯一 HTTP 绑定。
-- 历史简写位置无法精确配对的 HTTP 资产不猜测关联，下一次扫描/Starter 同步按来源标签初始化；也可手工批量归类。
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS `capability_catalog_classification_state` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT NOT NULL,
    `subject_type` VARCHAR(32) NOT NULL COMMENT 'BUSINESS_METHOD / HTTP_API',
    `subject_id` BIGINT NOT NULL,
    `manual` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '人工选择含显式清空，后续同步不覆盖',
    `revision` BIGINT NOT NULL DEFAULT 1 COMMENT '成员编辑乐观锁，项目行锁内递增',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_classification_owner` (`project_id`, `subject_type`, `subject_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正式资产的分类选择状态，不拥有资产契约';

-- 仅处理从未建立分类状态的主体；重跑不会改变已经人工清空的记录。
DROP TEMPORARY TABLE IF EXISTS classification_pending_owner;
DROP TEMPORARY TABLE IF EXISTS classification_owner_observation;
CREATE TEMPORARY TABLE classification_pending_owner (
 project_id BIGINT NOT NULL, subject_type VARCHAR(32) NOT NULL, subject_id BIGINT NOT NULL, manual TINYINT(1) NOT NULL,
 PRIMARY KEY(project_id,subject_type,subject_id)
);
CREATE TEMPORARY TABLE classification_owner_observation (
 project_id BIGINT NOT NULL, subject_type VARCHAR(32) NOT NULL, subject_id BIGINT NOT NULL, observation_id BIGINT NOT NULL,
 PRIMARY KEY(project_id,subject_type,subject_id,observation_id)
);
START TRANSACTION;
INSERT INTO classification_pending_owner
SELECT a.project_id,'BUSINESS_METHOD',a.id,EXISTS(SELECT 1 FROM capability_catalog_group_member m WHERE m.project_id=a.project_id AND m.subject_type='BUSINESS_METHOD' AND m.subject_id=a.id)
FROM capability_business_method_asset a LEFT JOIN capability_catalog_classification_state s
 ON s.project_id=a.project_id AND s.subject_type='BUSINESS_METHOD' AND s.subject_id=a.id WHERE s.id IS NULL;
INSERT INTO classification_pending_owner
SELECT a.project_id,'HTTP_API',a.id,EXISTS(SELECT 1 FROM capability_catalog_group_member m WHERE m.project_id=a.project_id AND m.subject_type='HTTP_API' AND m.subject_id=a.id)
FROM capability_http_api_asset a LEFT JOIN capability_catalog_classification_state s
 ON s.project_id=a.project_id AND s.subject_type='HTTP_API' AND s.subject_id=a.id WHERE s.id IS NULL;

INSERT IGNORE INTO classification_owner_observation
SELECT a.project_id,'BUSINESS_METHOD',a.id,o.id FROM capability_business_method_asset a
JOIN classification_pending_owner pending ON pending.project_id=a.project_id AND pending.subject_type='BUSINESS_METHOD' AND pending.subject_id=a.id
JOIN capability_scan_project_tool o ON o.project_id=a.project_id AND o.source_qualified_name=a.qualified_name AND o.asset_type='BUSINESS_METHOD';

-- REMOVED 来源仍可证明历史归属；无法精确且唯一配对的记录留待下一次同步或人工归类。
INSERT IGNORE INTO classification_owner_observation
SELECT a.project_id,'HTTP_API',a.id,o.id FROM capability_http_api_asset a
JOIN classification_pending_owner pending ON pending.project_id=a.project_id AND pending.subject_type='HTTP_API' AND pending.subject_id=a.id
JOIN capability_http_api_source_binding b ON b.asset_id=a.id AND b.project_id=a.project_id AND b.project_code=a.project_code AND b.environment=a.environment
JOIN capability_scan_project_tool o ON o.project_id=a.project_id AND o.asset_type='HTTP_API'
 AND o.source_location=b.source_location AND UPPER(o.http_method)=a.http_method
 AND CONCAT(COALESCE(NULLIF(TRIM(TRAILING '/' FROM o.context_path),''),''),'/',TRIM(LEADING '/' FROM o.endpoint_path))=a.route_template
WHERE b.source_kind IN ('STARTER_MVC','CONTROLLER_SCAN','OPENAPI_SCAN')
 AND (SELECT COUNT(*) FROM capability_http_api_source_binding other
      WHERE other.project_id=b.project_id AND other.project_code=b.project_code AND other.environment=b.environment
      AND other.source_location=b.source_location AND other.source_kind=b.source_kind)=1;

-- 保存升级前用户看到的自动成员与人工追加成员的并集。
INSERT IGNORE INTO capability_catalog_group_member(project_id,group_id,subject_type,subject_id)
SELECT link.project_id,o.module_id,link.subject_type,link.subject_id FROM classification_owner_observation link
JOIN capability_scan_project_tool o ON o.id=link.observation_id AND o.project_id=link.project_id
JOIN capability_scan_module g ON g.id=o.module_id AND g.project_id=link.project_id;
INSERT IGNORE INTO capability_catalog_group_member(project_id,group_id,subject_type,subject_id)
SELECT link.project_id,m.group_id,link.subject_type,link.subject_id FROM classification_owner_observation link
JOIN capability_catalog_group_member m ON m.project_id=link.project_id AND m.subject_type='SOURCE_OBSERVATION' AND m.subject_id=link.observation_id
JOIN capability_scan_module g ON g.id=m.group_id AND g.project_id=link.project_id;
UPDATE classification_pending_owner pending JOIN classification_owner_observation link
 ON link.project_id=pending.project_id AND link.subject_type=pending.subject_type AND link.subject_id=pending.subject_id
JOIN capability_catalog_group_member m ON m.project_id=link.project_id AND m.subject_type='SOURCE_OBSERVATION' AND m.subject_id=link.observation_id
SET pending.manual=1;
INSERT IGNORE INTO capability_catalog_classification_state(project_id,subject_type,subject_id,manual,revision)
SELECT pending.project_id,pending.subject_type,pending.subject_id,pending.manual,1 FROM classification_pending_owner pending
WHERE EXISTS(SELECT 1 FROM capability_catalog_group_member m WHERE m.project_id=pending.project_id AND m.subject_type=pending.subject_type AND m.subject_id=pending.subject_id);
COMMIT;
DROP TEMPORARY TABLE classification_owner_observation;
DROP TEMPORARY TABLE classification_pending_owner;
