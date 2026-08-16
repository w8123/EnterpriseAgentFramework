export type AiCodingClipboardCopyMethod = 'clipboard' | 'legacy' | 'manual'

export interface AiCodingClipboardCopyResult {
  copied: boolean
  method: AiCodingClipboardCopyMethod
}

/**
 * Copies a one-time AI Coding handoff in both secure and embedded browser contexts.
 * Clipboard API is preferred, while the selection fallback keeps the handoff usable when a
 * browser blocks clipboard permission (for example an iframe or an HTTP development host).
 */
export async function copyAiCodingText(value: string): Promise<AiCodingClipboardCopyResult> {
  if (!value?.trim()) return { copied: false, method: 'manual' }
  const text = value

  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return { copied: true, method: 'clipboard' }
    } catch {
      // The fallback below deliberately handles denied/insecure Clipboard API access.
    }
  }

  if (typeof document === 'undefined' || !document.body) {
    return { copied: false, method: 'manual' }
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  textarea.style.pointerEvents = 'none'
  document.body.appendChild(textarea)
  try {
    textarea.focus()
    textarea.select()
    const copied = typeof document.execCommand === 'function'
      && document.execCommand('copy')
    return { copied, method: copied ? 'legacy' : 'manual' }
  } catch {
    return { copied: false, method: 'manual' }
  } finally {
    textarea.remove()
  }
}
