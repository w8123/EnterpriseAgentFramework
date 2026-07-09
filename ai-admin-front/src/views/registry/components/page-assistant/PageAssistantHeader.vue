<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  projectName?: string
  projectCode?: string
}>()

const subtitle = computed(() => {
  const name = props.projectName || props.projectCode || '当前项目'
  const code = props.projectCode && props.projectCode !== name ? ` · ${props.projectCode}` : ''
  return `${name}${code} · 页面助手`
})
</script>

<template>
  <header class="page-header title-card page-assistant-title-card">
    <div class="title-card-layers" aria-hidden="true">
      <span class="title-card-hero-image" />
      <span class="title-card-hero-wash" />
      <span class="title-card-hero-overlay" />
      <span class="title-card-glass-highlight" />
    </div>
    <div class="title-card-body">
      <div class="hero-copy">
        <span class="hero-accent" aria-hidden="true" />
        <div class="hero-text">
          <h1>创建页面助手</h1>
          <p class="project-meta">{{ subtitle }}</p>
        </div>
      </div>
    </div>
  </header>
</template>

<style scoped lang="scss">
.page-header.page-assistant-title-card {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: stretch;
  min-height: var(--reachai-workbench-title-height, 120px);
  height: var(--reachai-workbench-title-height, 120px);
  margin-bottom: 16px;
  padding: 0 !important;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.28) !important;
  border-radius: 16px !important;
  background: transparent !important;
  box-shadow:
    0 16px 28px -12px rgb(var(--brand-primary-rgb) / 0.1),
    inset 0 1px 2px rgba(255, 255, 255, 0.6) !important;
  backdrop-filter: blur(7px);
}

.page-header.page-assistant-title-card::after {
  content: none !important;
}

.title-card-layers {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  overflow: hidden;
  pointer-events: none;
}

.title-card-hero-image {
  position: absolute;
  inset: 0;
  background: url('/reachai-project-hero-bg.png') right center / cover no-repeat;
  opacity: 0.86;
}

.title-card-hero-wash {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    90deg,
    rgb(var(--brand-selected-rgb) / 0.82) 0%,
    rgb(var(--brand-selected-rgb) / 0.52) 34%,
    rgb(var(--brand-primary-rgb) / 0.14) 58%,
    rgba(255, 255, 255, 0.06) 78%,
    rgba(255, 255, 255, 0) 100%
  );
}

.title-card-hero-overlay {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    90deg,
    rgba(255, 255, 255, 0.42) 0%,
    rgba(255, 255, 255, 0.18) 100%
  );
}

.title-card-glass-highlight {
  position: absolute;
  inset: 0 0 auto;
  height: 40px;
  border-radius: 16px 16px 0 0;
  background: linear-gradient(90deg, rgba(255, 255, 255, 0.24) 0%, rgba(255, 255, 255, 0.05) 54%, rgba(255, 255, 255, 0) 100%);
  opacity: 0.55;
}

.title-card-body {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 24px;
  width: 100%;
  min-height: var(--reachai-workbench-title-height, 120px);
  padding: 26px 28px;
}

.hero-copy {
  display: flex;
  align-items: flex-start;
  gap: 20px;
  min-width: 0;
}

.hero-accent {
  flex: 0 0 auto;
  width: 4px;
  height: 40px;
  margin-top: 3px;
  border-radius: 2px;
  background: linear-gradient(180deg, var(--brand-primary) 0%, var(--brand-hover) 100%);
}

.hero-text {
  min-width: 0;
}

.page-header h1 {
  margin: 0 0 8px !important;
  color: #11183a !important;
  font-size: 32px !important;
  font-weight: 800 !important;
  line-height: 40px !important;
  letter-spacing: 0;
  text-shadow: none !important;
}

.project-meta {
  margin: 0 !important;
  color: #4f5f81 !important;
  font-size: 16px !important;
  line-height: 22px !important;
}

.project-meta::before {
  content: none !important;
}
</style>
