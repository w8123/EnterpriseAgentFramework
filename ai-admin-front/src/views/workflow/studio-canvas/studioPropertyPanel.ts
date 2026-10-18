export type StudioPropertyTab = 'config' | 'test' | 'trace'

export interface StudioWorkflowMeta {
  name: string
  keySlug: string
  workflowKind: string
  description: string
  defaultModelInstanceId: string
}

export const STUDIO_PROPERTY_WIDTH_DEFAULT = 480
export const STUDIO_PROPERTY_WIDTH_MIN = 380
export const STUDIO_PROPERTY_WIDTH_MAX = 960
const WIDTH_STORAGE_KEY = 'reachai.workflowStudio.propertyWidth'

/** Keeps the canvas at least ~480px wide whatever width the user drags the panel to. */
export function clampPropertyWidth(value: number, viewportWidth = typeof window === 'undefined' ? 1600 : window.innerWidth) {
  const max = Math.min(STUDIO_PROPERTY_WIDTH_MAX, Math.max(STUDIO_PROPERTY_WIDTH_MIN, viewportWidth - 480))
  const width = Number.isFinite(value) ? value : STUDIO_PROPERTY_WIDTH_DEFAULT
  return Math.round(Math.min(max, Math.max(STUDIO_PROPERTY_WIDTH_MIN, width)))
}

export function loadPropertyWidth() {
  try {
    const stored = Number(window.localStorage.getItem(WIDTH_STORAGE_KEY))
    return clampPropertyWidth(stored > 0 ? stored : STUDIO_PROPERTY_WIDTH_DEFAULT)
  } catch {
    return clampPropertyWidth(STUDIO_PROPERTY_WIDTH_DEFAULT)
  }
}

export function savePropertyWidth(width: number) {
  try {
    window.localStorage.setItem(WIDTH_STORAGE_KEY, String(width))
  } catch {
    // Remembering the width is a convenience only.
  }
}
