import { onBeforeUnmount, onMounted, watch, type Ref } from 'vue'
import type { SdkAccessMode } from './useSdkAccessWizardUiState'

const REFRESH_INTERVAL_MS = 10_000

export function useSdkAccessWizardTaskRefresh(options: {
  accessMode: Ref<SdkAccessMode>
  projectCode: Ref<string>
  activeProjectCode: Ref<string | null | undefined>
  paused: Ref<boolean>
  refresh: (canApply: () => boolean) => Promise<void>
}) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let inFlight = false
  let wakePending = false
  let disposed = false

  function eligible() {
    return !disposed && !document.hidden && !options.paused.value
      && options.accessMode.value === 'ai-coding'
      && Boolean(options.projectCode.value)
      && options.activeProjectCode.value === options.projectCode.value
  }

  function clearTimer() {
    if (timer !== undefined) clearTimeout(timer)
    timer = undefined
  }

  function schedule(delay = REFRESH_INTERVAL_MS) {
    clearTimer()
    if (!eligible()) return
    if (inFlight) {
      if (delay === 0) wakePending = true
      return
    }
    timer = setTimeout(() => { void refresh() }, delay)
  }

  async function refresh() {
    timer = undefined
    if (!eligible() || inFlight) return
    const projectCode = options.projectCode.value
    inFlight = true
    try {
      await options.refresh(() => eligible() && options.projectCode.value === projectCode)
    } catch {
      // Retain the last confirmed task state and retry on the next interval.
    } finally {
      inFlight = false
      const delay = wakePending ? 0 : REFRESH_INTERVAL_MS
      wakePending = false
      schedule(delay)
    }
  }

  function wake() {
    schedule(0)
  }

  watch([options.accessMode, options.projectCode, options.activeProjectCode, options.paused], () => schedule(), { flush: 'sync' })
  onMounted(() => {
    document.addEventListener('visibilitychange', wake)
    window.addEventListener('focus', wake)
    schedule()
  })
  onBeforeUnmount(() => {
    disposed = true
    clearTimer()
    document.removeEventListener('visibilitychange', wake)
    window.removeEventListener('focus', wake)
  })
}
