import { computed, inject, provide, readonly, ref, shallowRef, watch, type InjectionKey, type Ref } from 'vue'

interface ClassificationModeOwner {
  canAdjust: Readonly<Ref<boolean>>
  beforeExit: () => Promise<boolean>
}

export interface CatalogClassificationMode {
  active: Readonly<Ref<boolean>>
  busy: Readonly<Ref<boolean>>
  canAdjust: Readonly<Ref<boolean>>
  enter: () => void
  requestExit: () => Promise<boolean>
  reset: () => void
  register: (canAdjust: Readonly<Ref<boolean>>, beforeExit: () => Promise<boolean>) => () => void
}

export const catalogClassificationModeKey: InjectionKey<CatalogClassificationMode> = Symbol('catalog-classification-mode')

export function provideCatalogClassificationMode(): CatalogClassificationMode {
  const active = ref(false)
  const busy = ref(false)
  const owner = shallowRef<ClassificationModeOwner | null>(null)
  const canAdjust = computed(() => !!owner.value?.canAdjust.value)
  let revision = 0

  function reset() {
    revision += 1
    active.value = false
    busy.value = false
  }

  function register(allowed: Readonly<Ref<boolean>>, beforeExit: () => Promise<boolean>) {
    const registration = { canAdjust: allowed, beforeExit }
    reset()
    owner.value = registration
    return () => {
      if (owner.value !== registration) return
      reset()
      owner.value = null
    }
  }

  async function requestExit() {
    if (!active.value) return true
    if (busy.value || !owner.value) return false
    const registration = owner.value
    const current = revision
    busy.value = true
    try {
      if (!await registration.beforeExit()) return false
      if (owner.value !== registration || current !== revision) return false
      active.value = false
      return true
    } finally {
      if (owner.value === registration && current === revision) busy.value = false
    }
  }

  watch(canAdjust, allowed => { if (!allowed) reset() }, { flush: 'sync' })
  const mode: CatalogClassificationMode = {
    active: readonly(active), busy: readonly(busy), canAdjust,
    enter() { if (canAdjust.value && !busy.value) active.value = true },
    requestExit, reset, register,
  }
  provide(catalogClassificationModeKey, mode)
  return mode
}

export function useCatalogClassificationMode() {
  return inject(catalogClassificationModeKey, undefined)
}
