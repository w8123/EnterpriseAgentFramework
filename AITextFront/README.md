# AITextFront — AI 知识库管理系统前端

基于 **Vue 3 + TypeScript + Element Plus** 构建的企业级 AI 知识库管理前端，为 [AI Text Service](../AI%20Text%20Service/README.md) 提供可视化操作界面。

---

## 一、功能概览

| 功能模块 | 说明 |
|----------|------|
| 知识库管理 | 知识库列表 CRUD、查看文件数量、进入详情 |
| 知识库详情 | 文件列表（大小/Chunk数/状态）、删除、重新解析、Chunk 策略配置 |
| 文件入库 | 拖拽上传（doc/docx/pdf）、切分策略选择、实时 Chunk 预览、触发入库 Pipeline |
| 文件详情 | 查看该文件下全部 Chunk 内容（可展开/折叠） |
| 检索测试 | 输入 query 对知识库进行向量检索，查看相似度分数与命中内容 |

---

## 二、技术栈

| 依赖 | 版本 | 用途 |
|------|------|------|
| Vue 3 | ^3.5 | 核心框架（Composition API） |
| TypeScript | ~5.6 | 类型安全 |
| Vite | ^6.0 | 构建工具（开发服务器 + HMR） |
| Element Plus | ^2.9 | UI 组件库 |
| @element-plus/icons-vue | ^2.3 | 图标库 |
| Vue Router | ^4.5 | 客户端路由 |
| Pinia | ^2.3 | 状态管理 |
| Axios | ^1.7 | HTTP 请求 |
| SASS | ^1.83 | CSS 预处理器 |

---

## 三、项目结构

```
AITextFront/
├── index.html                    # HTML 入口
├── vite.config.ts                # Vite 配置（别名 @、代理 /ai → localhost:8080）
├── tsconfig.json                 # TypeScript 配置
├── .env.development              # 开发环境变量（VITE_API_BASE_URL=/ai）
├── package.json
└── src/
    ├── main.ts                   # 应用入口（注册 Element Plus、Pinia、Router）
    ├── App.vue                   # 根组件（仅 <router-view />）
    ├── api/                      # 接口层
    │   ├── request.ts            #   Axios 实例封装（拦截器、统一错误提示）
    │   ├── knowledge.ts          #   知识库、文件、检索相关接口
    │   └── import.ts             #   文件预览 & 入库接口
    ├── components/               # 通用组件
    │   ├── KnowledgeSelector.vue #   知识库下拉选择器
    │   ├── FileUploader.vue      #   拖拽上传（doc/docx/pdf）
    │   ├── ChunkStrategyForm.vue #   切分策略 & 参数配置表单
    │   ├── ChunkPreview.vue      #   Chunk 预览列表
    │   ├── AdvancedSettings.vue  #   高级参数折叠面板（OCR/标签/部门）
    │   └── ImportActions.vue     #   重置/入库操作按钮
    ├── views/                    # 页面
    │   ├── layout/
    │   │   └── MainLayout.vue    #   布局：侧栏菜单 + 顶部面包屑 + <router-view />
    │   ├── KnowledgeList.vue     #   知识库列表页
    │   ├── KnowledgeDetail.vue   #   知识库详情页（文件列表 + 策略配置）
    │   ├── KnowledgeImport.vue   #   文件入库页
    │   ├── FileDetail.vue        #   文件详情页（Chunk 列表）
    │   └── RetrievalTest.vue     #   检索测试页
    ├── router/
    │   └── index.ts              # 路由定义
    ├── store/
    │   ├── knowledge.ts          #   知识库列表状态
    │   └── import.ts             #   文件入库流程状态
    ├── types/
    │   ├── knowledge.ts          #   知识库、文件、Chunk、检索相关类型
    │   └── import.ts             #   入库流程相关类型
    ├── styles/
    │   └── index.scss            # 全局样式（滚动条、页面容器、Chunk 卡片）
    └── utils/
        └── index.ts              # 工具函数
```

---

## 四、页面与路由

| 路由 | 页面文件 | 说明 |
|------|----------|------|
| `/knowledge` | `KnowledgeList.vue` | 知识库列表，支持新建/编辑/删除，点击名称进入详情 |
| `/knowledge/:code` | `KnowledgeDetail.vue` | 知识库详情，含文件列表与 Chunk 策略配置面板 |
| `/knowledge/:code/file/:fileId` | `FileDetail.vue` | 文件下的全部 Chunk 列表，内容可展开 |
| `/knowledge/import` | `KnowledgeImport.vue` | 文件入库，上传 → 预览 → 入库 |
| `/retrieval` | `RetrievalTest.vue` | 检索测试，输入 query 查看向量检索结果 |

侧栏高亮规则：所有 `/knowledge/*` 路由均高亮"知识库管理"菜单项。

---

## 五、核心组件说明

### `KnowledgeList.vue`
- 表格展示知识库列表（名称/编码/Embedding 模型/文件数/状态/创建时间）
- 新建/编辑通过 `el-dialog` 表单完成，编辑时编码字段禁用
- 删除使用 `el-popconfirm` 确认，成功后刷新列表
- 点击知识库名称（或"详情"按钮）跳转到 `/knowledge/:code`

### `KnowledgeDetail.vue`
- 顶部显示返回按钮与知识库名称/编码
- **Chunk 策略配置区**：切分策略（FIXED/PARAGRAPH/SEMANTIC）、chunkSize、chunkOverlap，支持保存
- **文件列表区**：展示文件名、类型、大小、Chunk 数、解析状态（解析中/完成/失败）
- 操作：查看详情（跳转文件详情）、重新解析（调用后端基于 rawText 重新切分向量化）、删除（同时清除向量库数据）

### `KnowledgeImport.vue`
- 左栏：知识库选择 → 文件上传 → 切分策略 → 高级参数（OCR/标签/部门）
- 右栏：实时 Chunk 预览（上传文件或修改策略时自动触发）
- 入库前需确认 Chunk 数量，成功后展示写入的向量数

### `FileDetail.vue`
- 展示文件下的全部 Chunk（按 chunkIndex 升序排列）
- 表格列：序号、内容（超过 150 字符可展开/折叠）、长度、向量 ID、创建时间

### `RetrievalTest.vue`
- 左侧：查询内容（textarea，支持 Ctrl+Enter 提交）、知识库多选过滤、topK（1-50）、相似度阈值（滑块 0~1）
- 搜索后展示每条结果的命中排名、知识库编码、来源文件名、相似度（颜色区分）、chunk 内容
- 支持展开全文查看完整 chunk 内容

---

## 六、状态管理（Pinia）

### `useKnowledgeStore`
```typescript
// 知识库列表状态
const knowledgeList   // KnowledgeBase[] 全部知识库
const currentKnowledge // 当前选中
const loading         // 列表加载中

// 方法
fetchList()           // 调接口刷新列表
setCurrent(kb)        // 设置当前知识库
findByCode(code)      // 按 code 查找
```

### `useImportStore`
```typescript
// 文件入库流程状态
const knowledgeBaseCode  // 选中的知识库编码
const file               // 上传的文件对象
const chunkConfig        // { chunkStrategy, chunkSize, chunkOverlap }
const extraParams        // { enableOcr, tags, deptId, overwrite }
const chunkPreview       // 预览的 ChunkItem[]
const previewLoading     // 预览加载中
const importLoading      // 入库中

// 方法
setFile(file)
setPreviewResult(chunks, total)
reset()
```

---

## 七、API 接口层

所有请求统一通过 `src/api/request.ts` 中的 Axios 实例发送。

**基础配置：**
- `baseURL`：`VITE_API_BASE_URL`（开发环境为 `/ai`，由 Vite 代理到 `http://localhost:8080`）
- 超时：60 秒（预览接口 120s，入库接口 300s）
- 响应拦截：`code !== 200` 时自动 `ElMessage.error` 并 `reject`

**接口清单（`src/api/knowledge.ts`）：**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/knowledge/base/list` | 获取知识库列表 |
| POST | `/knowledge/base` | 创建知识库 |
| PUT | `/knowledge/base` | 更新知识库 |
| DELETE | `/knowledge/base/:code` | 删除知识库 |
| GET | `/knowledge/kb/:code/files` | 获取知识库文件列表 |
| PUT | `/knowledge/kb/:code/config` | 更新 Chunk 策略配置 |
| GET | `/file/:fileId/chunks` | 获取文件 Chunk 列表 |
| DELETE | `/file/:fileId` | 删除文件（含向量） |
| POST | `/file/:fileId/reparse` | 重新解析文件 |
| POST | `/retrieval/test` | 检索测试 |

**接口清单（`src/api/import.ts`）：**

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/knowledge/preview` | Chunk 预览（multipart，120s） |
| POST | `/knowledge/import/file` | 文件入库 Pipeline（multipart，300s） |

---

## 八、快速启动

### 前置条件

- Node.js >= 18
- 后端服务 [AI Text Service](../AI%20Text%20Service/README.md) 已启动（默认 `http://localhost:8080`）

### 安装依赖

```bash
cd AITextFront
npm install
```

### 开发模式

```bash
npm run dev
```

浏览器访问 [http://localhost:3000](http://localhost:3000)

Vite 开发服务器会将所有 `/ai/*` 请求代理到 `http://localhost:8080`，无需配置跨域。

### 生产构建

```bash
npm run build
```

产物输出到 `dist/` 目录，部署时需将 Nginx（或其他 Web 服务器）配置为：
- 静态文件根目录指向 `dist/`
- `history` 模式需配置 `try_files $uri $uri/ /index.html`
- `/ai/*` 路径反向代理到后端服务

### 生产预览

```bash
npm run preview
```

---

## 九、开发环境配置

`.env.development`（当前配置）：

```env
VITE_API_BASE_URL=/ai
```

如果后端地址有变化，可通过环境变量或 `vite.config.ts` 中的 `server.proxy` 调整：

```typescript
// vite.config.ts
server: {
  port: 3000,
  proxy: {
    '/ai': {
      target: 'http://your-backend-host:8080',  // 修改此处
      changeOrigin: true,
    },
  },
}
```

---

## 十、Nginx 生产部署参考

```nginx
server {
    listen 80;
    server_name your-domain.com;

    root /var/www/ai-text-front/dist;
    index index.html;

    # Vue Router history 模式
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 反向代理后端 API
    location /ai/ {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 300s;
    }
}
```

---

## 十一、类型定义说明

核心类型定义位于 `src/types/knowledge.ts`：

```typescript
// 知识库
interface KnowledgeBase {
  id: number; code: string; name: string
  chunkSize: number; chunkOverlap: number; splitType: string
  fileCount: number; status: number
  ...
}

// 文件信息
interface FileInfo {
  fileId: string; fileName: string; fileSize: number
  chunkCount: number; status: number  // 0-解析中 1-完成 2-失败
  ...
}

// Chunk 详情
interface ChunkDetail {
  chunkIndex: number; content: string; length: number; vectorId: string
  ...
}

// 检索结果
interface RetrievalItem {
  content: string; score: number
  fileName: string; knowledgeBaseCode: string; chunkIndex: number
  ...
}
```

---

## 十二、注意事项

1. **文件格式**：仅支持 `.doc`、`.docx`、`.pdf` 上传，后端限制单文件最大 50MB
2. **入库流程**：必须完成 Chunk 预览后才能点击"开始入库"
3. **重新解析**：需要文件入库时保存了原始文本（`rawText`），若文件入库时版本较旧（V1）则不支持
4. **检索阈值**：默认相似度阈值为 0.3，可根据实际 Embedding 效果调整，COSINE 相似度范围为 0~1
5. **侧栏路由**：详情页和文件页的路由均以 `/knowledge` 开头，因此侧栏始终高亮"知识库管理"
