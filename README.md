# Enterprise Agent Framework

企业级 AI Agent 基础设施平台 — 统一 Agent 编排、RAG 引擎、模型网关。

## 模块结构

```
EnterpriseAgentFramework/
├── ai-common/           公共库（DTO、异常、通用配置）
├── ai-model-service/    模型网关（LLM Chat / Embedding，多 Provider）
├── ai-text-service/     RAG 引擎（知识库、文档 Pipeline、向量检索）
├── ai-agent-service/    智能体编排（AgentScope、意图识别、Tool 调用）
├── ai-admin-front/      统一管理前端（Vue 3 + Element Plus）
├── deploy/              部署配置（Docker / K8s）
├── sql/                 数据库脚本
└── docs/                架构文档
```

## 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| ai-text-service | 8080 | RAG 引擎，context-path: /ai |
| ai-agent-service | 8081 | 智能体编排 |
| ai-model-service | 8090 | 模型网关 |
| ai-admin-front | 3000 | 前端开发服务器 |

## 快速开始

### 1. 启动基础设施（MySQL / Redis / Milvus / Nacos）

```bash
docker compose -f deploy/docker-compose.infra.yml up -d
```

### 2. 初始化数据库

```bash
mysql -h localhost -u root -proot ai_text_service < sql/init.sql
```

### 3. 构建全部 Java 模块

```bash
mvn clean install -DskipTests
```

### 4. 按顺序启动服务

```bash
# 1) 模型网关（其他服务依赖它）
cd ai-model-service && mvn spring-boot:run

# 2) RAG 引擎
cd ai-text-service && mvn spring-boot:run

# 3) 智能体编排
cd ai-agent-service && mvn spring-boot:run
```

### 5. 启动前端

```bash
cd ai-admin-front && npm install && npm run dev
```

访问 http://localhost:3000

## 技术栈

- **Java 17** / Spring Boot 3.4.x / Spring Cloud 2024.0
- **Spring AI** + Spring AI Alibaba（DashScope）
- **AgentScope** 1.0.9（Agent 编排框架）
- **Nacos 3.0**（服务注册 + 配置中心）
- **MySQL 8** / **Redis 7** / **Milvus 2.4**
- **Vue 3** + Vite 6 + Element Plus + TypeScript
