-- 2026-10-16 目录分析模型改为可在「模型中心 → 目录设置」中选择
--
-- 问题：官方模型目录同步需要一个“目录分析模型”，此前只能通过部署环境变量
-- MODEL_CATALOG_ANALYZER_MODEL_INSTANCE_ID 指定，管理员在界面上无法启用目录同步，模板因此长期停留在种子版本。
--
-- 影响：给单例表 model_catalog_setting 新增可空列 analyzer_model_instance_id。
-- 界面保存的值优先；为 NULL 时继续回退到环境变量。不修改现有行的其他字段。
-- 可重复执行，兼容 MySQL 5.7/8。

SET @add_catalog_analyzer_column = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'model_catalog_setting'
          AND column_name = 'analyzer_model_instance_id'
    ),
    'SELECT 1',
    'ALTER TABLE `model_catalog_setting` ADD COLUMN `analyzer_model_instance_id` VARCHAR(64) DEFAULT NULL COMMENT ''Console-selected ACTIVE LLM instance for catalog analysis; NULL falls back to MODEL_CATALOG_ANALYZER_MODEL_INSTANCE_ID'' AFTER `auto_sync_enabled`'
);
PREPARE add_catalog_analyzer_column_stmt FROM @add_catalog_analyzer_column;
EXECUTE add_catalog_analyzer_column_stmt;
DEALLOCATE PREPARE add_catalog_analyzer_column_stmt;

-- 回读：应返回 1 行
SELECT column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'model_catalog_setting'
  AND column_name = 'analyzer_model_instance_id';
