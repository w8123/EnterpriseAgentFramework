-- 调试创建请求身份恢复；新库使用 initV2.sql，已有开发库备份后执行。
-- Runtime 停写期间执行。既有无创建标识会话不推断回填，不修改运行记录。
SET NAMES utf8mb4;
SET @debug_creation_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='runtime_executable_debug_session' AND column_name='creation_request_hash'),
  'SELECT 1', 'ALTER TABLE `runtime_executable_debug_session` ADD COLUMN `creation_request_hash` CHAR(64) DEFAULT NULL COMMENT ''创建请求不可变指纹；创建身份按平台用户和尝试标识隔离''');
PREPARE debug_creation_stmt FROM @debug_creation_ddl;
EXECUTE debug_creation_stmt;
DEALLOCATE PREPARE debug_creation_stmt;
