-- Model 服务签名鉴权：适用于已有 model_instance 表且尚无 model_internal_auth_nonce 的环境。
-- 仅新增 Model 自有防重放表；不修改模型、账号或凭据。兼容 MySQL 5.7 / 8.0，可重复执行。
-- 升级前先 reachai backup，确认目标库；部署 Model 与 Control / Runtime / Knowledge 的配套签名代码。
CREATE TABLE IF NOT EXISTS `model_internal_auth_nonce` (
    `nonce` VARCHAR(128) NOT NULL,
    `caller` VARCHAR(64) NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`nonce`),
    KEY `idx_model_internal_auth_nonce_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Model service signed request replay protection';

-- 执行后回读：SHOW CREATE TABLE model_internal_auth_nonce;
