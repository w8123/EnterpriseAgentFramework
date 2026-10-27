-- 嵌入渲染器登记表退场（MySQL 5.7 / 8）。
-- 前置条件：已部署移除渲染器管理入口及 /api/platform/embed/renderers 接口的前端和 Control，
-- 并停止仍提供旧登记接口的 Control 实例；执行前确认当前连接为目标开发/测试库 reach_ai。
-- 数据影响：永久删除 control_embed_renderer 表及全部组件名称、版本、Agent 列表等登记元数据。
-- 该登记表不参与聊天渲染与 Workflow 执行；页面、动作、会话、审计和前端组件代码均保留。
-- 需要保留登记信息时先导出；DDL 隐式提交，不能通过事务回滚。脚本可重复执行。

DROP TABLE IF EXISTS `control_embed_renderer`;

-- 回读：remaining_embed_renderer_tables 应为 0。
SELECT COUNT(*) AS remaining_embed_renderer_tables
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'control_embed_renderer';
