-- 芋道 ERP 演示数据（租户 1「芋道源码」）。status 0 = 开启；单据 status 20 = 已审核。
SET NAMES utf8mb4;

INSERT INTO erp_product_unit (id, name, status, tenant_id) VALUES
  (1, '个', 0, 1), (2, '台', 0, 1), (3, '箱', 0, 1);

INSERT INTO erp_product_category (id, parent_id, name, code, sort, status, tenant_id) VALUES
  (1, 0, '电脑外设', 'PERIPHERAL', 1, 0, 1),
  (2, 0, '办公用品', 'OFFICE', 2, 0, 1),
  (3, 0, '网络设备', 'NETWORK', 3, 0, 1);

INSERT INTO erp_product (id, name, bar_code, category_id, unit_id, status, standard, remark, purchase_price, sale_price, min_price, tenant_id) VALUES
  (1, '罗技无线鼠标 M330', '6943448010010', 1, 1, 0, '静音 / 黑色', '畅销款', 65, 99, 89, 1),
  (2, '罗技机械键盘 K845', '6943448010027', 1, 1, 0, '青轴 / 104 键', NULL, 199, 299, 269, 1),
  (3, '戴尔 27 英寸显示器 P2723', '6943448010034', 1, 2, 0, '2K / IPS', NULL, 1399, 1899, 1699, 1),
  (4, '得力 A4 复印纸', '6943448010041', 2, 3, 0, '70g / 5 包每箱', NULL, 95, 135, 120, 1),
  (5, '晨光中性笔', '6943448010058', 2, 3, 0, '0.5mm / 12 支每盒，100 盒每箱', NULL, 160, 230, 210, 1),
  (6, 'TP-Link 千兆交换机 SG1008', '6943448010065', 3, 2, 0, '8 口', NULL, 89, 139, 125, 1),
  (7, '华为路由器 AX3', '6943448010072', 3, 2, 0, 'Wi-Fi 6', '库存偏低', 199, 279, 259, 1);

INSERT INTO erp_warehouse (id, name, address, sort, principal, status, default_status, tenant_id) VALUES
  (1, '上海一号仓', '上海市浦东新区张江路 100 号', 1, '王仓管', 0, b'1', 1),
  (2, '北京二号仓', '北京市朝阳区望京街 8 号', 2, '李仓管', 0, b'0', 1);

INSERT INTO erp_stock (product_id, warehouse_id, count, tenant_id) VALUES
  (1, 1, 320, 1), (1, 2, 85, 1),
  (2, 1, 120, 1), (2, 2, 40, 1),
  (3, 1, 36, 1),  (3, 2, 12, 1),
  (4, 1, 500, 1), (4, 2, 260, 1),
  (5, 1, 80, 1),  (5, 2, 45, 1),
  (6, 1, 60, 1),  (6, 2, 18, 1),
  (7, 1, 6, 1),   (7, 2, 3, 1);

INSERT INTO erp_customer (id, name, contact, mobile, email, status, sort, tax_percent, remark, tenant_id) VALUES
  (1, '上海星辰科技有限公司', '张经理', '13800000001', 'zhang@xingchen.example', 0, 1, 13, '老客户，月结', 1),
  (2, '北京远航贸易有限公司', '刘女士', '13800000002', 'liu@yuanhang.example', 0, 2, 13, NULL, 1),
  (3, '杭州青禾教育咨询有限公司', '陈老师', '13800000003', 'chen@qinghe.example', 0, 3, 6, NULL, 1);

INSERT INTO erp_supplier (id, name, contact, mobile, email, status, sort, tax_percent, tenant_id) VALUES
  (1, '深圳数码供应链有限公司', '周先生', '13900000001', 'zhou@szdigital.example', 0, 1, 13, 1),
  (2, '广州文具批发中心', '吴女士', '13900000002', 'wu@gzoffice.example', 0, 2, 13, 1);

INSERT INTO erp_account (id, name, no, status, sort, default_status, tenant_id) VALUES
  (1, '招商银行基本户', 'CMB-0001', 0, 1, b'1', 1);

INSERT INTO erp_sale_order (id, no, status, customer_id, account_id, sale_user_id, order_time, total_count, total_price, total_product_price, total_tax_price, discount_percent, discount_price, deposit_price, out_count, return_count, remark, tenant_id) VALUES
  (1, 'XSDD202610200001', 20, 1, 1, 1, '2026-10-20 10:00:00', 50, 4950, 4950, 0, 0, 0, 0, 0, 0, '办公室换新鼠标', 1),
  (2, 'XSDD202610220001', 20, 2, 1, 1, '2026-10-22 15:30:00', 10, 18990, 18990, 0, 0, 0, 0, 0, 0, NULL, 1),
  (3, 'XSDD202610230001', 10, 3, 1, 1, '2026-10-23 09:20:00', 20, 2700, 2700, 0, 0, 0, 0, 0, 0, '待审核', 1);

INSERT INTO erp_sale_order_items (order_id, product_id, product_unit_id, product_price, count, total_price, tax_percent, tax_price, out_count, return_count, tenant_id) VALUES
  (1, 1, 1, 99, 50, 4950, 0, 0, 0, 0, 1),
  (2, 3, 2, 1899, 10, 18990, 0, 0, 0, 0, 1),
  (3, 4, 3, 135, 20, 2700, 0, 0, 0, 0, 1);

INSERT INTO erp_purchase_order (id, no, status, supplier_id, account_id, order_time, total_count, total_price, total_product_price, total_tax_price, discount_percent, discount_price, deposit_price, in_count, return_count, remark, tenant_id) VALUES
  (1, 'CGDD202610210001', 20, 1, 1, '2026-10-21 11:00:00', 30, 5970, 5970, 0, 0, 0, 0, 0, 0, '补货华为路由器', 1);

INSERT INTO erp_purchase_order_items (order_id, product_id, product_unit_id, product_price, count, total_price, tax_percent, tax_price, in_count, return_count, tenant_id) VALUES
  (1, 7, 2, 199, 30, 5970, 0, 0, 0, 0, 1);

-- 演示环境只保留系统管理 / 基础设施 / ERP 三个顶级菜单（其余模块未启用）。
UPDATE system_menu SET status = 1
 WHERE parent_id = 0 AND type IN (1, 2)
   AND name NOT IN ('系统管理', '基础设施', 'ERP 系统');
