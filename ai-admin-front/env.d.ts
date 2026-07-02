/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 绠＄悊绔敓鎴愩€屼笟鍔＄郴缁熼厤缃ず渚嬨€嶆椂 eaf.registry.url 鎸囧悜鐨?ReachAI Control public API 鏍瑰湴鍧€锛堝惈绔彛锛夛紝濡?http://localhost:18603 */
  readonly VITE_REACHAI_CONTROL_SERVICE_URL?: string
  /** 椤圭洰鏈厤缃?Base URL 鏃讹紝绀轰緥閲?base-url 鐨勫崰浣嶏紙鍚鍙ｏ級锛屽 http://127.0.0.1:8611 */
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
