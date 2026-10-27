"""按芋道 yudao-module-erp 的 *DO.java 生成 MySQL 建表 SQL（上游未公开 ERP 建表 SQL）。

用法：python gen_erp_schema.py <ruoyi-vue-pro>/yudao-module-erp > 02-erp-schema.sql
"""
import re, sys, pathlib
root = pathlib.Path(sys.argv[1])
TYPES = {'Long': 'bigint', 'Integer': 'int', 'BigDecimal': 'decimal(24,6)', 'Boolean': 'bit(1)',
         'LocalDateTime': 'datetime', 'String': 'varchar(500)'}
def snake(n): return re.sub(r'(?<!^)(?=[A-Z])', '_', n).lower()
out = []
for f in sorted(root.rglob('*DO.java')):
    src = f.read_text(encoding='utf-8')
    table = re.search(r'@TableName\((?:value = )?"([^"]+)"', src).group(1)
    title = re.search(r'/\*\*\s*\n\s*\*\s*(.+?)\s*DO\b', src)
    body = src[src.index('{', src.index('class ')):]
    cols = []
    # strip javadoc but keep first line as comment
    for m in re.finditer(r'(/\*\*(?P<doc>.*?)\*/\s*)?(?P<ann>(@[^\n]+\n\s*)*)private (?P<type>\w+) (?P<name>\w+);', body, re.S):
        if 'static' in m.group(0) or 'exist = false' in (m.group('ann') or ''):
            continue
        doc = (m.group('doc') or '').strip().lstrip('*').strip().split('\n')[0].strip().replace("'", '')
        name, typ = snake(m.group('name')), TYPES[m.group('type')]
        if name == 'id':
            cols.insert(0, "  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号'")
            continue
        cols.append(f"  `{name}` {typ} NULL COMMENT '{doc}'")
    cols += ["  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者'",
             "  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'",
             "  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者'",
             "  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'",
             "  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除'",
             "  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号'",
             "  PRIMARY KEY (`id`)"]
    comment = title.group(1).strip() if title else table
    out.append(f"DROP TABLE IF EXISTS `{table}`;\nCREATE TABLE `{table}` (\n" + ",\n".join(cols) +
               f"\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='{comment}';\n")
print("-- 芋道 ERP 表结构：由 ruoyi-vue-pro ac022b1 的 yudao-module-erp *DO.java 生成（上游未公开 ERP 建表 SQL）。\nSET NAMES utf8mb4;\n")
print("\n".join(out))
