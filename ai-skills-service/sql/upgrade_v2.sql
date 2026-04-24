-- ============================================================
-- AI Text Service V2 升级脚本
-- 新增：知识库 chunk 策略配置、文件大小、原始文本存储
-- ============================================================

USE `ai_text_service`;

-- 1. knowledge_base 表新增 chunk 策略配置字段
ALTER TABLE `knowledge_base`
    ADD COLUMN `chunk_size`    INT         DEFAULT 500     COMMENT 'chunk 切分大小（字符数）' AFTER `dimension`,
    ADD COLUMN `chunk_overlap` INT         DEFAULT 50      COMMENT 'chunk 重叠大小（字符数）' AFTER `chunk_size`,
    ADD COLUMN `split_type`    VARCHAR(32) DEFAULT 'FIXED' COMMENT '切分策略: FIXED / PARAGRAPH / SEMANTIC' AFTER `chunk_overlap`;

-- 2. file_info 表新增文件大小和原始文本字段
ALTER TABLE `file_info`
    ADD COLUMN `file_size` BIGINT   DEFAULT 0    COMMENT '文件大小（字节）' AFTER `file_type`,
    ADD COLUMN `raw_text`  LONGTEXT DEFAULT NULL  COMMENT '解析后的原始文本（用于重新解析）' AFTER `status`;
