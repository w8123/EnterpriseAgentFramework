-- 2026-11-12 内网地址白名单：平台管理员在页面上允许 ReachAI 调用的内网主机（业务系统在内网或本机时用）。
-- 一张新表，不改已有表；可重复执行。未执行时白名单页面会报错，其他功能不受影响（照旧只按环境变量放行）。
-- owner: runtime_http_egress_host → reachai-runtime-service。
USE reach_ai;

CREATE TABLE IF NOT EXISTS `runtime_http_egress_host` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `host`        VARCHAR(253) NOT NULL COMMENT '主机名或 IP，小写，不带协议和端口',
    `note`        VARCHAR(200) DEFAULT NULL COMMENT '备注：这是哪个系统',
    `created_by`  VARCHAR(128) DEFAULT NULL,
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_http_egress_host` (`host`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台管理员允许调用的内网主机';
