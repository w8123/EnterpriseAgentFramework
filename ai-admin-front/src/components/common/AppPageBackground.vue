<template>
  <div class="app-page-background" :class="{ 'is-local': local }" aria-hidden="true" />
</template>

<script setup lang="ts">
defineProps<{
  local?: boolean
}>()
</script>

<style scoped lang="scss">
.app-page-background {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
  background: var(
    --brand-page-bg,
    linear-gradient(180deg, #edf7ff 0%, #f8fbff 44%, #effaf6 100%)
  );
  background-size: var(--brand-page-bg-size, auto);
  box-shadow:
    inset 0 0 0 1px var(--brand-page-frame-border, rgba(205, 229, 247, 0.86)),
    inset 0 1px 0 rgba(255, 255, 255, 0.86);
  transition: background 0.24s ease, box-shadow 0.24s ease;
}

.app-page-background.is-local {
  border-radius: inherit;
}

/* Figma 布局 + 主题变量：环境光随 data-brand 变化 */
:global(.main-layout.registry-shell:not(.is-dark)) .app-page-background {
  background:
    radial-gradient(700px 380px at 58% 58%, rgba(255, 255, 255, 0.34), transparent 72%),
    radial-gradient(620px 560px at 78% 23%, rgb(var(--brand-hover-rgb) / 0.14), transparent 72%),
    radial-gradient(620px 520px at 52% -16%, rgb(var(--brand-primary-rgb) / 0.16), transparent 70%),
    var(--brand-page-bg, linear-gradient(180deg, #f2f8ff 0%, #f7fbff 55%, #eef8f5 100%));
  box-shadow:
    inset 0 0 0 1px var(--brand-page-frame-border, rgba(205, 229, 247, 0.86)),
    inset 0 1px 0 rgba(255, 255, 255, 0.86);
}

:global([data-theme='dark']) .app-page-background {
  background:
    radial-gradient(620px 520px at 52% -16%, rgb(var(--brand-primary-rgb) / 0.18), transparent 70%),
    radial-gradient(620px 560px at 78% 23%, rgb(var(--brand-hover-rgb) / 0.12), transparent 72%),
    radial-gradient(700px 380px at 58% 58%, rgba(255, 255, 255, 0.04), transparent 72%),
    linear-gradient(180deg, #0c1424 0%, #0b1020 52%, #08151a 100%);
  box-shadow:
    inset 0 0 0 1px rgba(148, 163, 184, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.05);
}
</style>
