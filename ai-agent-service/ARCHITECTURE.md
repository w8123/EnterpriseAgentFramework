# AI Agent Service 开发规范

---

# 1 不要让 LLM 直接访问数据库

错误方式：

LLM → SQL → 数据库

正确方式：

LLM → Tool → 服务层 → 数据库

原因：

安全
权限控制
SQL审计

---

# 2 Tool 必须具备权限控制

AI调用工具时必须验证：

用户身份

用户权限

否则可能产生数据泄露。

---

# 3 Agent 必须可观测

必须记录：

用户输入

Prompt

Tool调用

LLM输出

方便调试和审计。

---

# 4 Prompt 必须版本化

不要把 Prompt 写死在代码中。

建议：

数据库管理 Prompt

支持版本控制。

---

# 5 Tool 不要太细粒度

错误：

getUser
getUserName
getUserAddress

正确：

queryUserProfile

减少 LLM 决策复杂度。

---

# 6 RAG数据必须治理

不要直接把文档丢进向量库。

需要：

分段

清洗

标签化

否则回答质量会很差。

---

# 7 Agent流程必须限制步骤

避免无限循环推理。

建议：

maxStep = 5

---

# 8 AI服务必须限流

LLM调用成本高。

必须增加：

限流

缓存

降级策略

---
