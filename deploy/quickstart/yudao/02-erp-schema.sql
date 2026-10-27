-- 芋道 ERP 表结构：由 ruoyi-vue-pro ac022b1 的 yudao-module-erp *DO.java 生成（上游未公开 ERP 建表 SQL）。
SET NAMES utf8mb4;

DROP TABLE IF EXISTS `erp_account`;
CREATE TABLE `erp_account` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(500) NULL COMMENT '账户名称',
  `no` varchar(500) NULL COMMENT '账户编码',
  `remark` varchar(500) NULL COMMENT '备注',
  `status` int NULL COMMENT '开启状态',
  `sort` int NULL COMMENT '排序',
  `default_status` bit(1) NULL COMMENT '是否默认',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 结算账户';

DROP TABLE IF EXISTS `erp_finance_payment`;
CREATE TABLE `erp_finance_payment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `no` varchar(500) NULL COMMENT '付款单号',
  `status` int NULL COMMENT '付款状态',
  `payment_time` datetime NULL COMMENT '付款时间',
  `finance_user_id` bigint NULL COMMENT '财务人员编号',
  `supplier_id` bigint NULL COMMENT '供应商编号',
  `account_id` bigint NULL COMMENT '付款账户编号',
  `total_price` decimal(24,6) NULL COMMENT '合计价格，单位：元',
  `discount_price` decimal(24,6) NULL COMMENT '优惠金额，单位：元',
  `payment_price` decimal(24,6) NULL COMMENT '实付金额，单位：分',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 付款单';

DROP TABLE IF EXISTS `erp_finance_payment_item`;
CREATE TABLE `erp_finance_payment_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `payment_id` bigint NULL COMMENT '付款单编号',
  `biz_type` int NULL COMMENT '业务类型',
  `biz_id` bigint NULL COMMENT '业务编号',
  `biz_no` varchar(500) NULL COMMENT '业务单号',
  `total_price` decimal(24,6) NULL COMMENT '应付金额，单位：分',
  `paid_price` decimal(24,6) NULL COMMENT '已付金额，单位：分',
  `payment_price` decimal(24,6) NULL COMMENT '本次付款，单位：分',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 付款项';

DROP TABLE IF EXISTS `erp_finance_receipt`;
CREATE TABLE `erp_finance_receipt` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `no` varchar(500) NULL COMMENT '收款单号',
  `status` int NULL COMMENT '收款状态',
  `receipt_time` datetime NULL COMMENT '收款时间',
  `finance_user_id` bigint NULL COMMENT '财务人员编号',
  `customer_id` bigint NULL COMMENT '客户编号',
  `account_id` bigint NULL COMMENT '收款账户编号',
  `total_price` decimal(24,6) NULL COMMENT '合计价格，单位：元',
  `discount_price` decimal(24,6) NULL COMMENT '优惠金额，单位：元',
  `receipt_price` decimal(24,6) NULL COMMENT '实付金额，单位：分',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 收款单';

DROP TABLE IF EXISTS `erp_finance_receipt_item`;
CREATE TABLE `erp_finance_receipt_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `receipt_id` bigint NULL COMMENT '收款单编号',
  `biz_type` int NULL COMMENT '业务类型',
  `biz_id` bigint NULL COMMENT '业务编号',
  `biz_no` varchar(500) NULL COMMENT '业务单号',
  `total_price` decimal(24,6) NULL COMMENT '应收金额，单位：分',
  `receipted_price` decimal(24,6) NULL COMMENT '已收金额，单位：分',
  `receipt_price` decimal(24,6) NULL COMMENT '本次收款，单位：分',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 收款项';

DROP TABLE IF EXISTS `erp_product_category`;
CREATE TABLE `erp_product_category` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `parent_id` bigint NULL COMMENT '父分类编号',
  `name` varchar(500) NULL COMMENT '分类名称',
  `code` varchar(500) NULL COMMENT '分类编码',
  `sort` int NULL COMMENT '分类排序',
  `status` int NULL COMMENT '开启状态',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 产品分类';

DROP TABLE IF EXISTS `erp_product`;
CREATE TABLE `erp_product` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(500) NULL COMMENT '产品名称',
  `bar_code` varchar(500) NULL COMMENT '产品条码',
  `category_id` bigint NULL COMMENT '产品分类编号',
  `unit_id` bigint NULL COMMENT '单位编号',
  `status` int NULL COMMENT '产品状态',
  `standard` varchar(500) NULL COMMENT '产品规格',
  `remark` varchar(500) NULL COMMENT '产品备注',
  `expiry_day` int NULL COMMENT '保质期天数',
  `weight` decimal(24,6) NULL COMMENT '基础重量（kg）',
  `purchase_price` decimal(24,6) NULL COMMENT '采购价格，单位：元',
  `sale_price` decimal(24,6) NULL COMMENT '销售价格，单位：元',
  `min_price` decimal(24,6) NULL COMMENT '最低价格，单位：元',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 产品';

DROP TABLE IF EXISTS `erp_product_unit`;
CREATE TABLE `erp_product_unit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(500) NULL COMMENT '单位名字',
  `status` int NULL COMMENT '单位状态',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 产品单位';

DROP TABLE IF EXISTS `erp_purchase_in`;
CREATE TABLE `erp_purchase_in` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `no` varchar(500) NULL COMMENT '采购入库单号',
  `status` int NULL COMMENT '入库状态',
  `supplier_id` bigint NULL COMMENT '供应商编号',
  `account_id` bigint NULL COMMENT '结算账户编号',
  `in_time` datetime NULL COMMENT '入库时间',
  `order_id` bigint NULL COMMENT '采购订单编号',
  `order_no` varchar(500) NULL COMMENT '采购订单号',
  `total_count` decimal(24,6) NULL COMMENT '合计数量',
  `total_price` decimal(24,6) NULL COMMENT '最终合计价格，单位：元',
  `payment_price` decimal(24,6) NULL COMMENT '已支付金额，单位：元',
  `total_product_price` decimal(24,6) NULL COMMENT '合计产品价格，单位：元',
  `total_tax_price` decimal(24,6) NULL COMMENT '合计税额，单位：元',
  `discount_percent` decimal(24,6) NULL COMMENT '优惠率，百分比',
  `discount_price` decimal(24,6) NULL COMMENT '优惠金额，单位：元',
  `other_price` decimal(24,6) NULL COMMENT '其它金额，单位：元',
  `file_url` varchar(500) NULL COMMENT '附件地址',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 采购入库';

DROP TABLE IF EXISTS `erp_purchase_in_items`;
CREATE TABLE `erp_purchase_in_items` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `in_id` bigint NULL COMMENT '采购入库编号',
  `order_item_id` bigint NULL COMMENT '采购订单项编号',
  `warehouse_id` bigint NULL COMMENT '仓库编号',
  `product_id` bigint NULL COMMENT '产品编号',
  `product_unit_id` bigint NULL COMMENT '产品单位单位',
  `product_price` decimal(24,6) NULL COMMENT '产品单位单价，单位：元',
  `count` decimal(24,6) NULL COMMENT '数量',
  `total_price` decimal(24,6) NULL COMMENT '总价，单位：元',
  `tax_percent` decimal(24,6) NULL COMMENT '税率，百分比',
  `tax_price` decimal(24,6) NULL COMMENT '税额，单位：元',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 采购入库项';

DROP TABLE IF EXISTS `erp_purchase_order`;
CREATE TABLE `erp_purchase_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `no` varchar(500) NULL COMMENT '采购订单号',
  `status` int NULL COMMENT '采购状态',
  `supplier_id` bigint NULL COMMENT '供应商编号',
  `account_id` bigint NULL COMMENT '结算账户编号',
  `order_time` datetime NULL COMMENT '下单时间',
  `total_count` decimal(24,6) NULL COMMENT '合计数量',
  `total_price` decimal(24,6) NULL COMMENT '最终合计价格，单位：元',
  `total_product_price` decimal(24,6) NULL COMMENT '合计产品价格，单位：元',
  `total_tax_price` decimal(24,6) NULL COMMENT '合计税额，单位：元',
  `discount_percent` decimal(24,6) NULL COMMENT '优惠率，百分比',
  `discount_price` decimal(24,6) NULL COMMENT '优惠金额，单位：元',
  `deposit_price` decimal(24,6) NULL COMMENT '定金金额，单位：元',
  `file_url` varchar(500) NULL COMMENT '附件地址',
  `remark` varchar(500) NULL COMMENT '备注',
  `in_count` decimal(24,6) NULL COMMENT '采购入库数量',
  `return_count` decimal(24,6) NULL COMMENT '采购退货数量',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 采购订单';

DROP TABLE IF EXISTS `erp_purchase_order_items`;
CREATE TABLE `erp_purchase_order_items` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `order_id` bigint NULL COMMENT '采购订单编号',
  `product_id` bigint NULL COMMENT '产品编号',
  `product_unit_id` bigint NULL COMMENT '产品单位单位',
  `product_price` decimal(24,6) NULL COMMENT '产品单位单价，单位：元',
  `count` decimal(24,6) NULL COMMENT '数量',
  `total_price` decimal(24,6) NULL COMMENT '总价，单位：元',
  `tax_percent` decimal(24,6) NULL COMMENT '税率，百分比',
  `tax_price` decimal(24,6) NULL COMMENT '税额，单位：元',
  `remark` varchar(500) NULL COMMENT '备注',
  `in_count` decimal(24,6) NULL COMMENT '采购入库数量',
  `return_count` decimal(24,6) NULL COMMENT '采购退货数量',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 采购订单项';

DROP TABLE IF EXISTS `erp_purchase_return`;
CREATE TABLE `erp_purchase_return` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `no` varchar(500) NULL COMMENT '采购退货单号',
  `status` int NULL COMMENT '退货状态',
  `supplier_id` bigint NULL COMMENT '供应商编号',
  `account_id` bigint NULL COMMENT '结算账户编号',
  `return_time` datetime NULL COMMENT '退货时间',
  `order_id` bigint NULL COMMENT '采购订单编号',
  `order_no` varchar(500) NULL COMMENT '采购订单号',
  `total_count` decimal(24,6) NULL COMMENT '合计数量',
  `total_price` decimal(24,6) NULL COMMENT '最终合计价格，单位：元',
  `refund_price` decimal(24,6) NULL COMMENT '已退款金额，单位：元',
  `total_product_price` decimal(24,6) NULL COMMENT '合计产品价格，单位：元',
  `total_tax_price` decimal(24,6) NULL COMMENT '合计税额，单位：元',
  `discount_percent` decimal(24,6) NULL COMMENT '优惠率，百分比',
  `discount_price` decimal(24,6) NULL COMMENT '优惠金额，单位：元',
  `other_price` decimal(24,6) NULL COMMENT '其它金额，单位：元',
  `file_url` varchar(500) NULL COMMENT '附件地址',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 采购退货';

DROP TABLE IF EXISTS `erp_purchase_return_items`;
CREATE TABLE `erp_purchase_return_items` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `return_id` bigint NULL COMMENT '采购退货编号',
  `order_item_id` bigint NULL COMMENT '采购订单项编号',
  `warehouse_id` bigint NULL COMMENT '仓库编号',
  `product_id` bigint NULL COMMENT '产品编号',
  `product_unit_id` bigint NULL COMMENT '产品单位单位',
  `product_price` decimal(24,6) NULL COMMENT '产品单位单价，单位：元',
  `count` decimal(24,6) NULL COMMENT '数量',
  `total_price` decimal(24,6) NULL COMMENT '总价，单位：元',
  `tax_percent` decimal(24,6) NULL COMMENT '税率，百分比',
  `tax_price` decimal(24,6) NULL COMMENT '税额，单位：元',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 采购退货项';

DROP TABLE IF EXISTS `erp_supplier`;
CREATE TABLE `erp_supplier` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(500) NULL COMMENT '供应商名称',
  `contact` varchar(500) NULL COMMENT '联系人',
  `mobile` varchar(500) NULL COMMENT '手机号码',
  `telephone` varchar(500) NULL COMMENT '联系电话',
  `email` varchar(500) NULL COMMENT '电子邮箱',
  `fax` varchar(500) NULL COMMENT '传真',
  `remark` varchar(500) NULL COMMENT '备注',
  `status` int NULL COMMENT '开启状态',
  `sort` int NULL COMMENT '排序',
  `tax_no` varchar(500) NULL COMMENT '纳税人识别号',
  `tax_percent` decimal(24,6) NULL COMMENT '税率',
  `bank_name` varchar(500) NULL COMMENT '开户行',
  `bank_account` varchar(500) NULL COMMENT '开户账号',
  `bank_address` varchar(500) NULL COMMENT '开户地址',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 供应商';

DROP TABLE IF EXISTS `erp_customer`;
CREATE TABLE `erp_customer` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(500) NULL COMMENT '客户名称',
  `contact` varchar(500) NULL COMMENT '联系人',
  `mobile` varchar(500) NULL COMMENT '手机号码',
  `telephone` varchar(500) NULL COMMENT '联系电话',
  `email` varchar(500) NULL COMMENT '电子邮箱',
  `fax` varchar(500) NULL COMMENT '传真',
  `remark` varchar(500) NULL COMMENT '备注',
  `status` int NULL COMMENT '开启状态',
  `sort` int NULL COMMENT '排序',
  `tax_no` varchar(500) NULL COMMENT '纳税人识别号',
  `tax_percent` decimal(24,6) NULL COMMENT '税率',
  `bank_name` varchar(500) NULL COMMENT '开户行',
  `bank_account` varchar(500) NULL COMMENT '开户账号',
  `bank_address` varchar(500) NULL COMMENT '开户地址',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 客户';

DROP TABLE IF EXISTS `erp_sale_order`;
CREATE TABLE `erp_sale_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `no` varchar(500) NULL COMMENT '销售订单号',
  `status` int NULL COMMENT '销售状态',
  `customer_id` bigint NULL COMMENT '客户编号',
  `account_id` bigint NULL COMMENT '结算账户编号',
  `sale_user_id` bigint NULL COMMENT '销售员编号',
  `order_time` datetime NULL COMMENT '下单时间',
  `total_count` decimal(24,6) NULL COMMENT '合计数量',
  `total_price` decimal(24,6) NULL COMMENT '最终合计价格，单位：元',
  `total_product_price` decimal(24,6) NULL COMMENT '合计产品价格，单位：元',
  `total_tax_price` decimal(24,6) NULL COMMENT '合计税额，单位：元',
  `discount_percent` decimal(24,6) NULL COMMENT '优惠率，百分比',
  `discount_price` decimal(24,6) NULL COMMENT '优惠金额，单位：元',
  `deposit_price` decimal(24,6) NULL COMMENT '定金金额，单位：元',
  `file_url` varchar(500) NULL COMMENT '附件地址',
  `remark` varchar(500) NULL COMMENT '备注',
  `out_count` decimal(24,6) NULL COMMENT '销售出库数量',
  `return_count` decimal(24,6) NULL COMMENT '销售退货数量',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 销售订单';

DROP TABLE IF EXISTS `erp_sale_order_items`;
CREATE TABLE `erp_sale_order_items` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `order_id` bigint NULL COMMENT '销售订单编号',
  `product_id` bigint NULL COMMENT '产品编号',
  `product_unit_id` bigint NULL COMMENT '产品单位单位',
  `product_price` decimal(24,6) NULL COMMENT '产品单位单价，单位：元',
  `count` decimal(24,6) NULL COMMENT '数量',
  `total_price` decimal(24,6) NULL COMMENT '总价，单位：元',
  `tax_percent` decimal(24,6) NULL COMMENT '税率，百分比',
  `tax_price` decimal(24,6) NULL COMMENT '税额，单位：元',
  `remark` varchar(500) NULL COMMENT '备注',
  `out_count` decimal(24,6) NULL COMMENT '销售出库数量',
  `return_count` decimal(24,6) NULL COMMENT '销售退货数量',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 销售订单项';

DROP TABLE IF EXISTS `erp_sale_out`;
CREATE TABLE `erp_sale_out` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `no` varchar(500) NULL COMMENT '销售出库单号',
  `status` int NULL COMMENT '出库状态',
  `customer_id` bigint NULL COMMENT '客户编号',
  `account_id` bigint NULL COMMENT '结算账户编号',
  `sale_user_id` bigint NULL COMMENT '销售员编号',
  `out_time` datetime NULL COMMENT '出库时间',
  `order_id` bigint NULL COMMENT '销售订单编号',
  `order_no` varchar(500) NULL COMMENT '销售订单号',
  `total_count` decimal(24,6) NULL COMMENT '合计数量',
  `total_price` decimal(24,6) NULL COMMENT '最终合计价格，单位：元',
  `receipt_price` decimal(24,6) NULL COMMENT '已收款金额，单位：元',
  `total_product_price` decimal(24,6) NULL COMMENT '合计产品价格，单位：元',
  `total_tax_price` decimal(24,6) NULL COMMENT '合计税额，单位：元',
  `discount_percent` decimal(24,6) NULL COMMENT '优惠率，百分比',
  `discount_price` decimal(24,6) NULL COMMENT '优惠金额，单位：元',
  `other_price` decimal(24,6) NULL COMMENT '其它金额，单位：元',
  `file_url` varchar(500) NULL COMMENT '附件地址',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 销售出库';

DROP TABLE IF EXISTS `erp_sale_out_items`;
CREATE TABLE `erp_sale_out_items` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `out_id` bigint NULL COMMENT '销售出库编号',
  `order_item_id` bigint NULL COMMENT '销售订单项编号',
  `warehouse_id` bigint NULL COMMENT '仓库编号',
  `product_id` bigint NULL COMMENT '产品编号',
  `product_unit_id` bigint NULL COMMENT '产品单位单位',
  `product_price` decimal(24,6) NULL COMMENT '产品单位单价，单位：元',
  `count` decimal(24,6) NULL COMMENT '数量',
  `total_price` decimal(24,6) NULL COMMENT '总价，单位：元',
  `tax_percent` decimal(24,6) NULL COMMENT '税率，百分比',
  `tax_price` decimal(24,6) NULL COMMENT '税额，单位：元',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 销售出库项';

DROP TABLE IF EXISTS `erp_sale_return`;
CREATE TABLE `erp_sale_return` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `no` varchar(500) NULL COMMENT '销售退货单号',
  `status` int NULL COMMENT '退货状态',
  `customer_id` bigint NULL COMMENT '客户编号',
  `account_id` bigint NULL COMMENT '结算账户编号',
  `sale_user_id` bigint NULL COMMENT '销售员编号',
  `return_time` datetime NULL COMMENT '退货时间',
  `order_id` bigint NULL COMMENT '销售订单编号',
  `order_no` varchar(500) NULL COMMENT '销售订单号',
  `total_count` decimal(24,6) NULL COMMENT '合计数量',
  `total_price` decimal(24,6) NULL COMMENT '最终合计价格，单位：元',
  `refund_price` decimal(24,6) NULL COMMENT '已退款金额，单位：元',
  `total_product_price` decimal(24,6) NULL COMMENT '合计产品价格，单位：元',
  `total_tax_price` decimal(24,6) NULL COMMENT '合计税额，单位：元',
  `discount_percent` decimal(24,6) NULL COMMENT '优惠率，百分比',
  `discount_price` decimal(24,6) NULL COMMENT '优惠金额，单位：元',
  `other_price` decimal(24,6) NULL COMMENT '其它金额，单位：元',
  `file_url` varchar(500) NULL COMMENT '附件地址',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 销售退货';

DROP TABLE IF EXISTS `erp_sale_return_items`;
CREATE TABLE `erp_sale_return_items` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `return_id` bigint NULL COMMENT '销售退货编号',
  `order_item_id` bigint NULL COMMENT '销售订单项编号',
  `warehouse_id` bigint NULL COMMENT '仓库编号',
  `product_id` bigint NULL COMMENT '产品编号',
  `product_unit_id` bigint NULL COMMENT '产品单位单位',
  `product_price` decimal(24,6) NULL COMMENT '产品单位单价，单位：元',
  `count` decimal(24,6) NULL COMMENT '数量',
  `total_price` decimal(24,6) NULL COMMENT '总价，单位：元',
  `tax_percent` decimal(24,6) NULL COMMENT '税率，百分比',
  `tax_price` decimal(24,6) NULL COMMENT '税额，单位：元',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 销售退货项';

DROP TABLE IF EXISTS `erp_stock_check`;
CREATE TABLE `erp_stock_check` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `no` varchar(500) NULL COMMENT '盘点单号',
  `check_time` datetime NULL COMMENT '盘点时间',
  `total_count` decimal(24,6) NULL COMMENT '合计数量',
  `total_price` decimal(24,6) NULL COMMENT '合计金额，单位：元',
  `status` int NULL COMMENT '状态',
  `remark` varchar(500) NULL COMMENT '备注',
  `file_url` varchar(500) NULL COMMENT '附件 URL',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 库存盘点单';

DROP TABLE IF EXISTS `erp_stock_check_item`;
CREATE TABLE `erp_stock_check_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `check_id` bigint NULL COMMENT '盘点编号',
  `warehouse_id` bigint NULL COMMENT '仓库编号',
  `product_id` bigint NULL COMMENT '产品编号',
  `product_unit_id` bigint NULL COMMENT '产品单位编号',
  `product_price` decimal(24,6) NULL COMMENT '产品单价',
  `stock_count` decimal(24,6) NULL COMMENT '账面数量（当前库存）',
  `actual_count` decimal(24,6) NULL COMMENT '实际数量（实际库存）',
  `count` decimal(24,6) NULL COMMENT '盈亏数量',
  `total_price` decimal(24,6) NULL COMMENT '合计金额，单位：元',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 库存盘点单项';

DROP TABLE IF EXISTS `erp_stock`;
CREATE TABLE `erp_stock` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `product_id` bigint NULL COMMENT '产品编号',
  `warehouse_id` bigint NULL COMMENT '仓库编号',
  `count` decimal(24,6) NULL COMMENT '库存数量',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 产品库存';

DROP TABLE IF EXISTS `erp_stock_in`;
CREATE TABLE `erp_stock_in` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `no` varchar(500) NULL COMMENT '入库单号',
  `supplier_id` bigint NULL COMMENT '供应商编号',
  `in_time` datetime NULL COMMENT '入库时间',
  `total_count` decimal(24,6) NULL COMMENT '合计数量',
  `total_price` decimal(24,6) NULL COMMENT '合计金额，单位：元',
  `status` int NULL COMMENT '状态',
  `remark` varchar(500) NULL COMMENT '备注',
  `file_url` varchar(500) NULL COMMENT '附件 URL',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 其它入库单';

DROP TABLE IF EXISTS `erp_stock_in_item`;
CREATE TABLE `erp_stock_in_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `in_id` bigint NULL COMMENT '入库编号',
  `warehouse_id` bigint NULL COMMENT '仓库编号',
  `product_id` bigint NULL COMMENT '产品编号',
  `product_unit_id` bigint NULL COMMENT '产品单位编号',
  `product_price` decimal(24,6) NULL COMMENT '产品单价',
  `count` decimal(24,6) NULL COMMENT '产品数量',
  `total_price` decimal(24,6) NULL COMMENT '合计金额，单位：元',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 其它入库单项';

DROP TABLE IF EXISTS `erp_stock_move`;
CREATE TABLE `erp_stock_move` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `no` varchar(500) NULL COMMENT '调拨单号',
  `move_time` datetime NULL COMMENT '调拨时间',
  `total_count` decimal(24,6) NULL COMMENT '合计数量',
  `total_price` decimal(24,6) NULL COMMENT '合计金额，单位：元',
  `status` int NULL COMMENT '状态',
  `remark` varchar(500) NULL COMMENT '备注',
  `file_url` varchar(500) NULL COMMENT '附件 URL',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 库存调拨单';

DROP TABLE IF EXISTS `erp_stock_move_item`;
CREATE TABLE `erp_stock_move_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `move_id` bigint NULL COMMENT '调拨编号',
  `from_warehouse_id` bigint NULL COMMENT '调出仓库编号',
  `to_warehouse_id` bigint NULL COMMENT '调入仓库编号',
  `product_id` bigint NULL COMMENT '产品编号',
  `product_unit_id` bigint NULL COMMENT '产品单位编号',
  `product_price` decimal(24,6) NULL COMMENT '产品单价',
  `count` decimal(24,6) NULL COMMENT '产品数量',
  `total_price` decimal(24,6) NULL COMMENT '合计金额，单位：元',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 库存调拨单项';

DROP TABLE IF EXISTS `erp_stock_out`;
CREATE TABLE `erp_stock_out` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `no` varchar(500) NULL COMMENT '出库单号',
  `customer_id` bigint NULL COMMENT '客户编号',
  `out_time` datetime NULL COMMENT '出库时间',
  `total_count` decimal(24,6) NULL COMMENT '合计数量',
  `total_price` decimal(24,6) NULL COMMENT '合计金额，单位：元',
  `status` int NULL COMMENT '状态',
  `remark` varchar(500) NULL COMMENT '备注',
  `file_url` varchar(500) NULL COMMENT '附件 URL',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 其它出库单';

DROP TABLE IF EXISTS `erp_stock_out_item`;
CREATE TABLE `erp_stock_out_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `out_id` bigint NULL COMMENT '出库编号',
  `warehouse_id` bigint NULL COMMENT '仓库编号',
  `product_id` bigint NULL COMMENT '产品编号',
  `product_unit_id` bigint NULL COMMENT '产品单位编号',
  `product_price` decimal(24,6) NULL COMMENT '产品单价',
  `count` decimal(24,6) NULL COMMENT '产品数量',
  `total_price` decimal(24,6) NULL COMMENT '合计金额，单位：元',
  `remark` varchar(500) NULL COMMENT '备注',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 其它出库单项';

DROP TABLE IF EXISTS `erp_stock_record`;
CREATE TABLE `erp_stock_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `product_id` bigint NULL COMMENT '产品编号',
  `warehouse_id` bigint NULL COMMENT '仓库编号',
  `count` decimal(24,6) NULL COMMENT '出入库数量',
  `total_count` decimal(24,6) NULL COMMENT '总库存量',
  `biz_type` int NULL COMMENT '业务类型',
  `biz_id` bigint NULL COMMENT '业务编号',
  `biz_item_id` bigint NULL COMMENT '业务项编号',
  `biz_no` varchar(500) NULL COMMENT '业务单号',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 产品库存明细';

DROP TABLE IF EXISTS `erp_warehouse`;
CREATE TABLE `erp_warehouse` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(500) NULL COMMENT '仓库名称',
  `address` varchar(500) NULL COMMENT '仓库地址',
  `sort` bigint NULL COMMENT '排序',
  `remark` varchar(500) NULL COMMENT '备注',
  `principal` varchar(500) NULL COMMENT '负责人',
  `warehouse_price` decimal(24,6) NULL COMMENT '仓储费，单位：元',
  `truckage_price` decimal(24,6) NULL COMMENT '搬运费，单位：元',
  `status` int NULL COMMENT '开启状态',
  `default_status` bit(1) NULL COMMENT '是否默认',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP 仓库';

