/**
 * ReachAI Prism 共享视觉资源。
 * - 管理端：走 /conversation/*.webp（public）
 * - Embed SDK：走包内相对路径 ./*.webp（write-embed-chat-package 会复制）
 *
 * 不使用 import 图片 URL，避免 Vite library build 把 WebP base64 进 JS/CSS。
 */
declare const __REACHAI_EMBED_SDK__: boolean | undefined

function resolveAsset(fileName: string): string {
  if (typeof __REACHAI_EMBED_SDK__ !== 'undefined' && __REACHAI_EMBED_SDK__) {
    return `./${fileName}`
  }
  return `/conversation/${fileName}`
}

export const prismAvatarUrl = resolveAsset('agent-prism.webp')
export const atmosphereUrl = resolveAsset('conversation-atmosphere.webp')
