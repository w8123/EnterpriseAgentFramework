<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, useId, watch } from 'vue'
import { liquidDropOutline, type LiquidDropCorner, type LiquidDropSide } from '@/composables/liquidGlass'
import LiquidGlassLayer from './LiquidGlassLayer.vue'

const props = withDefaults(defineProps<{
  source?: 'backdrop' | 'wallpaper'
  reference?: HTMLElement
  anchorSelector?: string
}>(), { source: 'backdrop' })
const anchor = ref<HTMLElement | null>(null)
const host = shallowRef<HTMLElement | null>(null)
const outline = ref('')
const viewBox = ref('0 0 190 200')
const dropCorner = ref<LiquidDropCorner>('top-left')
const dropSide = ref<LiquidDropSide>('top')
const dropOffset = ref<number>()
const rimId = `liquid-dropdown-rim-${useId()}`
let observer: ResizeObserver | undefined
let placementObserver: MutationObserver | undefined
let lastGeometry = ''

function measure() {
  if (!host.value || !host.value.offsetWidth || !host.value.offsetHeight) return
  const width = host.value.offsetWidth
  const height = host.value.offsetHeight
  const [side, alignment] = (host.value.getAttribute('data-popper-placement') || 'bottom-start').split('-')
  const bounds = host.value.getBoundingClientRect()
  const target = (props.anchorSelector && props.reference?.querySelector<HTMLElement>(props.anchorSelector)) || props.reference
  const reference = target?.getBoundingClientRect()
  const horizontal = side === 'left' || side === 'right'
  let bottom = horizontal ? alignment === 'end' : side === 'top'
  let right = horizontal ? side === 'left' : alignment === 'end'
  // Follow the actual expansion icon, including when Popper shifts the menu
  // away from the viewport edge or opens it beside the trigger.
  if (reference?.width && reference.height) {
    if (horizontal) {
      const delta = reference.top + reference.height / 2 - (bounds.top + height / 2)
      if (Math.abs(delta) > 1) bottom = delta > 0
    } else {
      const delta = reference.left + reference.width / 2 - (bounds.left + width / 2)
      if (Math.abs(delta) > 1) right = delta > 0
    }
  }
  dropCorner.value = `${bottom ? 'bottom' : 'top'}-${right ? 'right' : 'left'}`
  dropSide.value = side === 'left' ? 'right' : side === 'right' ? 'left' : side === 'top' ? 'bottom' : 'top'
  const length = horizontal ? height : width
  const offset = reference?.width && reference.height
    ? horizontal ? reference.top + reference.height / 2 - bounds.top : reference.left + reference.width / 2 - bounds.left
    : (horizontal ? bottom : right) ? length - 24 : 24
  dropOffset.value = Math.round(Math.max(24, Math.min(length - 24, offset)))
  const geometry = `${width}:${height}:${dropCorner.value}:${dropSide.value}:${dropOffset.value}`
  if (geometry === lastGeometry) return
  lastGeometry = geometry
  host.value.dataset.liquidDropCorner = dropCorner.value
  host.value.dataset.liquidDropSide = dropSide.value
  outline.value = liquidDropOutline(width, height, dropCorner.value, dropSide.value, dropOffset.value)
  viewBox.value = `0 0 ${width} ${height}`
}

onMounted(() => {
  host.value = anchor.value?.closest<HTMLElement>('.el-popper:is(.liquid-select-popper, .liquid-menu-popper)') ?? null
  if (host.value) {
    measure()
    observer = new ResizeObserver(measure)
    observer.observe(host.value)
    placementObserver = new MutationObserver(measure)
    placementObserver.observe(host.value, { attributes: true, attributeFilter: ['data-popper-placement', 'style'] })
  }
})
watch(() => props.reference, measure)
onBeforeUnmount(() => {
  observer?.disconnect()
  placementObserver?.disconnect()
  if (host.value) {
    delete host.value.dataset.liquidDropCorner
    delete host.value.dataset.liquidDropSide
  }
})
</script>

<template>
  <span ref="anchor" class="liquid-dropdown-anchor" aria-hidden="true" />
  <Teleport v-if="host" :to="host">
    <div class="liquid-dropdown-surface" :class="{ 'samples-wallpaper': source === 'wallpaper' }" aria-hidden="true">
      <div
        class="liquid-dropdown-surface__material glass-surface-frost liquid-glass-host"
        :style="outline ? { clipPath: `var(--glass-dropdown-clip, path('${outline}'))` } : undefined"
      >
        <LiquidGlassLayer :source="source" shape="droplet" :drop-corner="dropCorner" :drop-side="dropSide" :drop-offset="dropOffset" :bezel="10" :depth="14" />
      </div>
      <svg v-if="outline" class="liquid-dropdown-surface__rim" :viewBox="viewBox" preserveAspectRatio="none" focusable="false">
        <defs>
          <linearGradient :id="rimId" x1="0" y1="0" x2="0.8" y2="1">
            <stop offset="0" stop-color="var(--border-glass-lens)" />
            <stop offset="0.3" stop-color="var(--border-glass-edge)" />
            <stop offset="0.55" stop-color="var(--border-glass-lens)" />
            <stop offset="0.8" stop-color="var(--border-subtle)" />
            <stop offset="1" stop-color="var(--border-glass-lens)" />
          </linearGradient>
        </defs>
        <path :d="outline" fill="none" :stroke="`url(#${rimId})`" stroke-width="1.25" vector-effect="non-scaling-stroke" />
      </svg>
    </div>
  </Teleport>
</template>

<style scoped lang="scss">
.liquid-dropdown-anchor {
  display: contents;
}

.liquid-dropdown-surface {
  position: absolute;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  border-radius: inherit;
  filter: var(--glass-dropdown-filter);
}

.liquid-dropdown-surface__material,
.liquid-dropdown-surface__rim {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.liquid-dropdown-surface__material {
  --glass-tint: var(--surface-glass-overlay);
  border-radius: inherit;
  border: 0;
  box-shadow: none;

  &:not(.is-liquid-refracting) {
    background: var(--surface-fallback-panel);
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
}

.samples-wallpaper .liquid-dropdown-surface__material {
  --glass-tint: var(--surface-glass-frost);
}

.liquid-dropdown-surface__rim {
  display: var(--glass-decoration-display);
}

@media (forced-colors: active) {
  .liquid-dropdown-surface__material:not(.is-liquid-refracting) {
    background: Canvas;
  }

  .liquid-dropdown-surface {
    filter: none;
  }

  .liquid-dropdown-surface__rim path {
    stroke: CanvasText;
  }
}
</style>
