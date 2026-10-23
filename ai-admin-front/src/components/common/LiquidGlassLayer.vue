<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, useId, watch } from 'vue'

import { useTheme } from '@/composables/useTheme'
import {
  createLiquidGlassMap,
  prefersReducedTransparency,
  supportsLiquidGlassRefraction,
  type LiquidGlassMap,
  type LiquidDropCorner,
  type LiquidDropSide,
} from '@/composables/liquidGlass'

/**
 * 导航/操作层的折射增强。放在带 `liquid-glass-host` 类的宿主内（宿主需定位、带圆角）：
 * 启用时为宿主加上 `is-liquid-refracting`，由本层承担模糊 + 边缘折射；不支持或关闭时不渲染，
 * 宿主保持 _glass.scss 的纯 CSS 材质。业务页面不直接调整折射参数。
 */
const props = withDefaults(
  defineProps<{
    /** 边缘折射带宽度（px） */
    bezel?: number
    /** 边缘最大采样偏移（px） */
    depth?: number
    /** 公共弹窗的默认 slot 位于 body 内，光学层应挂到真正的外壳。 */
    hostSelector?: string
    /** 嵌套在磨砂导航内的透镜可直接采样同坐标壁纸，避免重复采样父层的着色。 */
    source?: 'backdrop' | 'wallpaper'
    shape?: 'rounded-rect' | 'droplet'
    dropCorner?: LiquidDropCorner
    dropSide?: LiquidDropSide
    dropOffset?: number
  }>(),
  {
    bezel: 24,
    depth: 32,
    source: 'backdrop',
    shape: 'rounded-rect',
    dropCorner: 'top-left',
  },
)

const { glassRefraction, reduceTransparency } = useTheme()
const filterId = `reachai-liquid-glass-${useId()}`
const layer = ref<HTMLElement | null>(null)
const map = shallowRef<LiquidGlassMap | null>(null)
const systemReduced = ref(false)
const wallpaperStyle = shallowRef<Record<string, string>>({})

const active = computed(
  () => glassRefraction.value
    && !reduceTransparency.value
    && !systemReduced.value
    && supportsLiquidGlassRefraction(),
)

const host = shallowRef<HTMLElement | null>(null)
let resizeObserver: ResizeObserver | null = null
let mediaQueries: MediaQueryList[] = []
let pendingFrame = 0
let lastSize = ''

function measureAndBuild() {
  pendingFrame = 0
  if (!host.value || !active.value) return
  if (props.source === 'wallpaper') {
    const bounds = host.value.getBoundingClientRect()
    wallpaperStyle.value = {
      width: `${window.innerWidth}px`,
      height: `${window.innerHeight}px`,
      left: `${-bounds.left - host.value.clientLeft}px`,
      top: `${-bounds.top - host.value.clientTop}px`,
    }
  }
  // Layout dimensions remain stable during Dialog's scale entrance animation.
  const width = host.value.offsetWidth
  const height = host.value.offsetHeight
  const radius = Number.parseFloat(getComputedStyle(host.value).borderTopLeftRadius) || 0
  const size = `${width}:${height}:${radius}:${props.bezel}:${props.depth}:${props.shape}:${props.dropCorner}:${props.dropSide}:${props.dropOffset}`
  if (size === lastSize) return
  lastSize = size
  map.value = width > 0 && height > 0
    ? createLiquidGlassMap({ width, height, radius, bezel: props.bezel, depth: props.depth, shape: props.shape, dropCorner: props.dropCorner, dropSide: props.dropSide, dropOffset: props.dropOffset })
    : null
}

function scheduleBuild() {
  if (pendingFrame) return
  pendingFrame = requestAnimationFrame(measureAndBuild)
}

function syncHostState() {
  host.value?.classList.toggle('is-liquid-refracting', active.value && map.value !== null)
}

function syncSystemPreference() {
  systemReduced.value = prefersReducedTransparency()
}

function scheduleWallpaperSync() {
  if (props.source === 'wallpaper' && active.value) scheduleBuild()
}

function syncWallpaperListeners() {
  window.removeEventListener('scroll', scheduleWallpaperSync, true)
  window.removeEventListener('resize', scheduleWallpaperSync)
  if (props.source === 'wallpaper') {
    window.addEventListener('scroll', scheduleWallpaperSync, { capture: true, passive: true })
    window.addEventListener('resize', scheduleWallpaperSync, { passive: true })
  }
}

onMounted(() => {
  const parent = layer.value?.parentElement
  host.value = (props.hostSelector ? parent?.closest<HTMLElement>(props.hostSelector) : parent) ?? null
  syncSystemPreference()
  mediaQueries = ['(prefers-reduced-transparency: reduce)', '(forced-colors: active)'].map((query) => window.matchMedia(query))
  mediaQueries.forEach((query) => query.addEventListener('change', syncSystemPreference))
  if (host.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(scheduleBuild)
    resizeObserver.observe(host.value)
    // The scroll viewport can move when the project-scope panel changes height.
    if (props.source === 'wallpaper') {
      const scrollViewport = host.value.closest('.el-scrollbar')
      if (scrollViewport) resizeObserver.observe(scrollViewport)
    }
  }
  syncWallpaperListeners()
  scheduleBuild()
})

watch(active, (value) => {
  if (value) {
    lastSize = ''
    scheduleBuild()
  } else {
    map.value = null
  }
  syncHostState()
})
watch(map, syncHostState)
watch(() => [props.bezel, props.depth, props.shape, props.dropCorner, props.dropSide, props.dropOffset], () => {
  lastSize = ''
  scheduleBuild()
})
watch(() => props.source, () => {
  syncWallpaperListeners()
  scheduleBuild()
})

onBeforeUnmount(() => {
  if (pendingFrame) cancelAnimationFrame(pendingFrame)
  resizeObserver?.disconnect()
  resizeObserver = null
  mediaQueries.forEach((query) => query.removeEventListener('change', syncSystemPreference))
  window.removeEventListener('scroll', scheduleWallpaperSync, true)
  window.removeEventListener('resize', scheduleWallpaperSync)
  host.value?.classList.remove('is-liquid-refracting')
  host.value = null
  map.value = null
})
</script>

<template>
  <span
    ref="layer"
    class="liquid-glass-layer-anchor"
    aria-hidden="true"
  />
  <Teleport v-if="active && map && host" :to="host">
    <span
      class="liquid-glass-layer"
      :class="{ 'liquid-glass-layer--wallpaper': source === 'wallpaper' }"
      aria-hidden="true"
      :style="source === 'wallpaper' ? {
        filter: `url(#${filterId})`,
        backdropFilter: 'none',
        WebkitBackdropFilter: 'none',
      } : {
        backdropFilter: `blur(var(--glass-blur-refract)) saturate(var(--glass-saturation)) url(#${filterId})`,
        WebkitBackdropFilter: `blur(var(--glass-blur-refract)) saturate(var(--glass-saturation)) url(#${filterId})`,
      }"
    >
      <span v-if="source === 'wallpaper'" class="liquid-glass-wallpaper" :style="wallpaperStyle" />
    </span>
    <span class="liquid-glass-tint" aria-hidden="true" />
    <span
      class="liquid-glass-specular"
      aria-hidden="true"
      :style="{ '--liquid-specular-light': `url(${map.specular})`, '--liquid-specular-dark': `url(${map.specularDark})` }"
    />
    <svg
      class="liquid-glass-layer__defs"
      width="0"
      height="0"
      focusable="false"
      aria-hidden="true"
    >
      <filter
        :id="filterId"
        x="0"
        y="0"
        :width="map.width"
        :height="map.height"
        filterUnits="userSpaceOnUse"
        color-interpolation-filters="sRGB"
      >
        <feImage
          :href="map.url"
          x="0"
          y="0"
          :width="map.width"
          :height="map.height"
          preserveAspectRatio="none"
          result="liquid-map"
        />
        <feDisplacementMap
          in="SourceGraphic"
          in2="liquid-map"
          :scale="map.scale"
          xChannelSelector="R"
          yChannelSelector="G"
        />
      </filter>
    </svg>
  </Teleport>
</template>

<style scoped>
.liquid-glass-layer-anchor {
  display: contents;
}

.liquid-glass-layer--wallpaper {
  overflow: hidden;
}

.liquid-glass-wallpaper {
  position: absolute;
  background: var(--surface-page-background);
  background-size: cover;
  background-position: var(--theme-wallpaper-position, center);
  background-repeat: no-repeat;
}

.liquid-glass-layer__defs {
  position: absolute;
  width: 0;
  height: 0;
  overflow: hidden;
  pointer-events: none;
}
</style>
