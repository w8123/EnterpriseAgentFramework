<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import { CircleCheckFilled } from '@element-plus/icons-vue'

defineOptions({ inheritAttrs: false })

const props = withDefaults(defineProps<{
  modelValue: boolean
  title: string
  eyebrow?: string
  description?: string
  status?: string
  statusTone?: 'neutral' | 'success'
  width?: string | number
}>(), {
  eyebrow: '',
  description: '',
  status: '',
  statusTone: 'neutral',
  width: '820px',
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()
</script>

<template>
  <AppDialog
    v-bind="$attrs"
    class="glass-composed-dialog"
    :model-value="props.modelValue"
    :title="props.title"
    :width="props.width"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header="{ titleId, titleClass }">
      <div :id="titleId" :class="[titleClass, 'glass-dialog-header']">
        <span v-if="$slots.icon" class="glass-dialog-header__mark" aria-hidden="true">
          <slot name="icon" />
        </span>
        <div class="glass-dialog-header__copy">
          <span v-if="props.eyebrow" class="glass-dialog-header__eyebrow">{{ props.eyebrow }}</span>
          <div class="glass-dialog-header__title-row">
            <strong>{{ props.title }}</strong>
            <span
              v-if="props.status"
              class="glass-dialog-header__status"
              :class="`is-${props.statusTone}`"
            >
              <el-icon v-if="props.statusTone === 'success'"><CircleCheckFilled /></el-icon>
              {{ props.status }}
            </span>
          </div>
          <small v-if="props.description">{{ props.description }}</small>
        </div>
        <div v-if="$slots.aside" class="glass-dialog-header__aside">
          <slot name="aside" />
        </div>
      </div>
    </template>

    <slot />

    <template v-if="$slots.footer" #footer>
      <div class="glass-dialog-footer" :class="{ 'has-hint': $slots.hint }">
        <div v-if="$slots.hint" class="glass-dialog-footer__hint">
          <slot name="hint" />
        </div>
        <div class="glass-dialog-footer__actions">
          <slot name="footer" />
        </div>
      </div>
    </template>
  </AppDialog>
</template>

<style lang="scss">
.glass-composed-dialog.app-dialog.el-dialog {
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--border-readable);
  border-radius: var(--radius-xl);
  background: var(--surface-glass-overlay);
  box-shadow:
    0 32px 96px rgb(17 35 58 / 0.28),
    0 10px 30px rgb(var(--brand-primary-rgb) / 0.1),
    var(--inner-highlight);
  -webkit-backdrop-filter: blur(34px) saturate(1.24);
  backdrop-filter: blur(34px) saturate(1.24);
}

.glass-composed-dialog.app-dialog.el-dialog .el-dialog__header {
  min-height: 98px;
  padding: 24px 72px 22px 30px;
  border-bottom-color: transparent;
  background:
    radial-gradient(ellipse 66% 130% at 10% -18%, rgb(var(--brand-primary-rgb) / 0.16), transparent 70%),
    linear-gradient(180deg, rgb(255 255 255 / 0.2) 0%, rgb(255 255 255 / 0.08) 54%, transparent 100%);
}

.glass-composed-dialog.app-dialog.el-dialog .el-dialog__headerbtn {
  top: 24px;
  right: 28px;
  width: 36px;
  height: 36px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  transition:
    color var(--motion-duration-fast) ease,
    border-color var(--motion-duration-fast) ease,
    background var(--motion-duration-fast) ease;
}

.glass-composed-dialog.app-dialog.el-dialog .el-dialog__headerbtn:hover {
  border-color: var(--border-subtle);
  background: var(--surface-glass-control);
}

.glass-composed-dialog.app-dialog.el-dialog .el-dialog__body {
  max-height: min(68vh, 42rem);
  padding: 16px 30px 22px;
  scrollbar-color: var(--border-readable) transparent;
  scrollbar-width: thin;
}

.glass-composed-dialog.app-dialog.el-dialog .el-dialog__footer {
  min-height: 66px;
  padding: 10px 30px 18px;
  border-top-color: transparent;
  background: linear-gradient(180deg, transparent 0%, rgb(var(--brand-selected-rgb) / 0.1) 100%);
  box-shadow: 0 -20px 36px -34px rgb(var(--brand-primary-rgb) / 0.28);
}

.glass-dialog-header {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 14px;
}

.glass-dialog-header__mark {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  flex: 0 0 46px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.28);
  border-radius: 14px;
  color: var(--brand-active);
  background: var(--surface-glass-selected);
  box-shadow:
    0 12px 24px -18px rgb(var(--brand-primary-rgb) / 0.46),
    var(--inner-highlight);
}

.glass-dialog-header__mark .el-icon {
  font-size: 23px;
}

.glass-dialog-header__copy {
  min-width: 0;
  flex: 1;
}

.glass-dialog-header__eyebrow {
  display: block;
  margin-bottom: 2px;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 720;
  letter-spacing: 0.06em;
  line-height: 1.4;
  text-transform: uppercase;
}

.glass-dialog-header__title-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.glass-dialog-header__title-row > strong {
  color: var(--text-primary);
  font-size: 21px;
  font-weight: 760;
  letter-spacing: -0.015em;
  line-height: 1.3;
}

.glass-dialog-header__copy > small {
  display: block;
  margin-top: 5px;
  color: var(--text-muted);
  font-size: 13px;
  font-weight: 500;
  line-height: 1.5;
}

.glass-dialog-header__status {
  display: inline-flex;
  min-height: 25px;
  align-items: center;
  gap: 5px;
  padding: 0 10px;
  border: 1px solid var(--border-subtle);
  border-radius: 999px;
  color: var(--status-neutral);
  background: var(--status-neutral-soft);
  font-size: 12px;
  font-weight: 700;
}

.glass-dialog-header__status.is-success {
  color: var(--status-success);
  border-color: color-mix(in srgb, var(--status-success) 28%, transparent);
  background: var(--status-success-soft);
}

.glass-dialog-header__status .el-icon {
  font-size: 12px;
}

.glass-dialog-header__aside {
  flex: 0 0 auto;
  margin-left: auto;
}

.glass-dialog-footer {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.glass-dialog-footer:not(.has-hint) {
  justify-content: flex-end;
}

.glass-dialog-footer__hint {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
  color: var(--text-muted);
  font-size: 12px;
}

.glass-dialog-footer__hint > .el-icon {
  flex: 0 0 auto;
  color: var(--status-warning);
}

.glass-dialog-footer__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
}

.glass-dialog-footer__actions .el-button {
  min-height: 40px;
  margin: 0;
  border-radius: 10px;
  font-weight: 680;
}

/* Shared form composition for configuration dialogs. */
.glass-dialog-form {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 14px;
}

.glass-dialog-section {
  min-width: 0;
  padding: 18px 18px 4px;
  border: 1px solid var(--border-subtle);
  border-radius: 14px;
  background: color-mix(in srgb, var(--surface-solid-panel) 78%, transparent);
  box-shadow: var(--inner-highlight), var(--shadow-sm);
}

.glass-dialog-section > .glass-section-header {
  margin-bottom: 16px;
}

.glass-dialog-grid {
  display: grid;
  min-width: 0;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 14px;
}

.glass-dialog-grid > .is-wide {
  grid-column: 1 / -1;
}

.glass-dialog-form .el-form-item {
  min-width: 0;
  margin-bottom: 16px;
}

.glass-dialog-form .el-form-item__label {
  height: auto;
  margin-bottom: 7px;
  padding: 0;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 680;
  line-height: 18px;
}

.glass-dialog-form .el-input,
.glass-dialog-form .el-select,
.glass-dialog-form .el-textarea {
  width: 100%;
}

.glass-dialog-form .el-input__wrapper,
.glass-dialog-form .el-select__wrapper {
  min-height: 42px;
  border-radius: 10px;
  background: color-mix(in srgb, var(--surface-solid-control) 88%, transparent);
  box-shadow: 0 0 0 1px var(--border-subtle) inset;
  transition:
    background var(--motion-duration-fast) ease,
    box-shadow var(--motion-duration-fast) ease;
}

.glass-dialog-form .el-input__wrapper:hover,
.glass-dialog-form .el-select__wrapper:hover {
  background: color-mix(in srgb, var(--surface-solid-control) 96%, transparent);
  box-shadow: 0 0 0 1px var(--border-readable) inset;
}

.glass-dialog-form .el-input__wrapper.is-focus,
.glass-dialog-form .el-select__wrapper.is-focused {
  background: var(--surface-solid-control);
  box-shadow:
    0 0 0 1px var(--border-focus) inset,
    0 0 0 3px color-mix(in srgb, var(--brand-primary) 9%, transparent);
}

.glass-dialog-form .el-textarea__inner {
  min-height: 94px !important;
  padding: 11px 13px;
  border: 0;
  border-radius: 10px;
  color: var(--text-primary);
  background: color-mix(in srgb, var(--surface-solid-control) 88%, transparent);
  box-shadow: 0 0 0 1px var(--border-subtle) inset;
  line-height: 1.55;
  transition:
    background var(--motion-duration-fast) ease,
    box-shadow var(--motion-duration-fast) ease;
}

.glass-dialog-form .el-textarea__inner:hover {
  background: color-mix(in srgb, var(--surface-solid-control) 96%, transparent);
  box-shadow: 0 0 0 1px var(--border-readable) inset;
}

.glass-dialog-form .el-textarea__inner:focus {
  background: var(--surface-solid-control);
  box-shadow:
    0 0 0 1px var(--border-focus) inset,
    0 0 0 3px color-mix(in srgb, var(--brand-primary) 9%, transparent);
}

.glass-dialog-form .el-input__inner,
.glass-dialog-form .el-select__placeholder,
.glass-dialog-form .el-select__selected-item,
.glass-dialog-form .el-textarea__inner {
  color: var(--text-primary);
  font-size: 13px;
}

.glass-dialog-form .el-input__inner::placeholder,
.glass-dialog-form .el-textarea__inner::placeholder,
.glass-dialog-form .el-select__placeholder.is-transparent {
  color: var(--text-muted);
}

.glass-dialog-form .el-input.is-disabled .el-input__wrapper {
  background: color-mix(in srgb, var(--surface-solid-panel) 72%, transparent);
  box-shadow: 0 0 0 1px var(--border-divider) inset;
}

.glass-dialog-form .el-input.is-disabled .el-input__inner {
  color: var(--text-muted);
  -webkit-text-fill-color: var(--text-muted);
}

[data-theme="dark"] .glass-composed-dialog.app-dialog.el-dialog {
  border-color: var(--border-readable);
  box-shadow:
    0 34px 100px rgb(0 0 0 / 0.62),
    0 10px 34px rgb(var(--brand-primary-rgb) / 0.16),
    var(--inner-highlight);
}

[data-theme="dark"] .glass-composed-dialog.app-dialog.el-dialog .el-dialog__header {
  background:
    radial-gradient(ellipse 66% 130% at 10% -18%, rgb(var(--brand-primary-rgb) / 0.22), transparent 72%),
    linear-gradient(180deg, rgb(255 255 255 / 0.04) 0%, rgb(255 255 255 / 0.015) 58%, transparent 100%);
}

[data-reduce-transparency="true"] .glass-composed-dialog.app-dialog.el-dialog {
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
}

@media (prefers-reduced-transparency: reduce) {
  .glass-composed-dialog.app-dialog.el-dialog {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
}

@media (max-width: 760px) {
  .glass-composed-dialog.app-dialog.el-dialog .el-dialog__header,
  .glass-composed-dialog.app-dialog.el-dialog .el-dialog__body,
  .glass-composed-dialog.app-dialog.el-dialog .el-dialog__footer {
    padding-right: 18px;
    padding-left: 18px;
  }

  .glass-composed-dialog.app-dialog.el-dialog .el-dialog__header {
    padding-right: 64px;
  }

  .glass-dialog-header__aside {
    display: none;
  }

  .glass-dialog-footer {
    align-items: stretch;
    flex-direction: column;
  }

  .glass-dialog-footer__hint {
    display: none;
  }

  .glass-dialog-footer__actions,
  .glass-dialog-footer__actions .el-button {
    width: 100%;
  }

  .glass-dialog-footer__actions .el-button {
    flex: 1;
  }

  .glass-dialog-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .glass-dialog-grid > .is-wide {
    grid-column: auto;
  }

  .glass-dialog-section {
    padding-right: 14px;
    padding-left: 14px;
  }
}
</style>
