import { computed, inject, provide, type ComputedRef, type InjectionKey } from 'vue'

export type LiquidGlassSource = 'backdrop' | 'wallpaper'
const sourceKey: InjectionKey<ComputedRef<LiquidGlassSource>> = Symbol('liquid-glass-source')

export function useLiquidGlassSource(source: () => LiquidGlassSource | undefined) {
  const inherited = inject(sourceKey, undefined)
  return computed(() => source() ?? inherited?.value ?? 'backdrop')
}

export function provideLiquidGlassSource(source: ComputedRef<LiquidGlassSource>) {
  provide(sourceKey, source)
}

/** Width, layout classes and style belong to the decorator; native behavior belongs to the control. */
export function nativeControlAttrs(attrs: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(attrs).filter(([key]) => key !== 'class' && key !== 'style'))
}
