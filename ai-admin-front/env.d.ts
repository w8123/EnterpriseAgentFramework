/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 管理端生成「业务系统配置示例」时 eaf.registry.url 指向的 ai-agent-service 根地址（含端口），如 http://localhost:18603 */
  readonly VITE_AI_AGENT_SERVICE_URL?: string
  /** 项目未配置 Base URL 时，示例里 base-url 的占位（含端口），如 http://127.0.0.1:8611 */
  readonly VITE_EXAMPLE_BUSINESS_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
