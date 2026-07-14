<script setup lang="ts">
import { computed } from 'vue'

import AppDialog from './AppDialog.vue'
import {
  densityClass,
  type StatusTone,
  type WizardStep,
  type WizardStepState,
} from './glassWorkbench'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    description?: string
    steps: WizardStep[]
    activeStep: number
    navigable?: boolean
    canAdvance?: boolean
    loading?: boolean
    finishLabel?: string
  }>(),
  {
    navigable: false,
    canAdvance: true,
    loading: false,
    finishLabel: '完成',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  stepChange: [index: number]
  back: []
  next: []
  cancel: []
  finish: []
}>()

const stepToneByState = {
  pending: 'neutral',
  current: 'info',
  complete: 'success',
  error: 'danger',
  skipped: 'neutral',
} satisfies Record<WizardStepState, StatusTone>

const activeErrorStep = computed(() => {
  const step = props.steps[props.activeStep]
  return step && step.state === 'error' ? step : undefined
})

function stepTone(state: WizardStepState): StatusTone {
  return stepToneByState[state]
}
</script>

<template>
  <AppDialog
    :model-value="props.modelValue"
    :title="props.title"
    :description="props.description"
    body-class="app-dialog__body--delegated-scroll"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div :class="['wizard-dialog', densityClass('spacious')]">
      <aside class="wizard-dialog__rail glass-surface-control">
        <div v-if="$slots.summary" class="wizard-dialog__summary">
          <slot name="summary" />
        </div>
        <ol class="wizard-dialog__steps" aria-label="步骤">
          <li
            v-for="(step, index) in props.steps"
            :key="step.key"
            class="wizard-dialog__step"
            :class="[
              `wizard-dialog__step--state-${step.state}`,
              `wizard-dialog__step--tone-${stepTone(step.state)}`,
            ]"
            :aria-current="index === props.activeStep ? 'step' : undefined"
          >
            <button
              class="wizard-dialog__step-button"
              type="button"
              :disabled="!props.navigable || step.disabled"
              @click="emit('stepChange', index)"
            >
              <span class="wizard-dialog__step-marker" aria-hidden="true">{{ index + 1 }}</span>
              <span class="wizard-dialog__step-copy">
                <strong class="wizard-dialog__step-title">{{ step.title }}</strong>
                <span v-if="step.description" class="wizard-dialog__step-description">
                  {{ step.description }}
                </span>
              </span>
            </button>
          </li>
        </ol>
      </aside>

      <main class="wizard-dialog__body">
        <div class="wizard-dialog__live" aria-live="polite" aria-atomic="true">
          <div v-if="activeErrorStep" class="wizard-dialog__validation">
            <strong class="wizard-dialog__validation-title">{{ activeErrorStep.title }}</strong>
            <span
              v-if="activeErrorStep.description"
              class="wizard-dialog__validation-description"
            >
              {{ activeErrorStep.description }}
            </span>
          </div>
        </div>
        <slot />
      </main>
    </div>

    <template #footer>
      <slot name="footer">
        <div class="wizard-dialog__footer">
          <el-button native-type="button" @click="emit('cancel')">取消</el-button>
          <el-button
            native-type="button"
            :disabled="props.activeStep === 0 || props.loading"
            @click="emit('back')"
          >
            上一步
          </el-button>
          <el-button
            v-if="props.activeStep < props.steps.length - 1"
            type="primary"
            native-type="button"
            :disabled="!props.canAdvance || props.loading"
            @click="emit('next')"
          >
            下一步
          </el-button>
          <el-button
            v-else
            type="primary"
            native-type="button"
            :disabled="!props.canAdvance"
            :loading="props.loading"
            @click="emit('finish')"
          >
            {{ props.finishLabel }}
          </el-button>
        </div>
      </slot>
    </template>
  </AppDialog>
</template>

<style scoped lang="scss">
.wizard-dialog {
  display: grid;
  grid-template-columns: minmax(13rem, 18rem) minmax(0, 1fr);
  gap: var(--section-gap);
  min-width: 0;
  color: var(--text-primary);
}

.wizard-dialog__rail {
  display: flex;
  flex-direction: column;
  gap: var(--section-gap);
  min-width: 0;
  padding: var(--panel-padding);
  border-radius: var(--radius-lg);
}

.wizard-dialog__summary {
  color: var(--text-secondary);
}

.wizard-dialog__steps {
  display: flex;
  flex-direction: column;
  gap: calc(var(--section-gap) / 2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.wizard-dialog__step-button {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: calc(var(--section-gap) / 2);
  align-items: start;
  width: 100%;
  min-height: var(--control-height);
  padding: calc(var(--panel-padding) / 2);
  border: 0;
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  background: transparent;
  font: inherit;
  text-align: start;
  cursor: pointer;
}

.wizard-dialog__step-button:disabled {
  color: var(--text-disabled);
  cursor: not-allowed;
}

.wizard-dialog__step-button:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}

.wizard-dialog__step-marker {
  display: inline-grid;
  place-items: center;
  min-width: calc(var(--control-height) / 2);
  min-height: calc(var(--control-height) / 2);
  border: 1px solid currentColor;
  border-radius: var(--radius-xl);
}

.wizard-dialog__step-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: calc(var(--section-gap) / 4);
}

.wizard-dialog__step-title,
.wizard-dialog__step-description {
  display: block;
}

.wizard-dialog__step-description {
  color: var(--text-muted);
  font-size: 0.8125rem;
  line-height: 1.45;
}

.wizard-dialog__step--tone-success .wizard-dialog__step-button {
  color: var(--status-success);
  background: var(--status-success-soft);
}

.wizard-dialog__step--tone-danger .wizard-dialog__step-button {
  color: var(--status-danger);
  background: var(--status-danger-soft);
}

.wizard-dialog__step--tone-info .wizard-dialog__step-button {
  color: var(--status-info);
  background: var(--status-info-soft);
}

.wizard-dialog__step--tone-neutral .wizard-dialog__step-button {
  color: var(--status-neutral);
  background: var(--status-neutral-soft);
}

.wizard-dialog__step--state-current .wizard-dialog__step-button {
  border: 1px solid var(--border-focus);
  color: var(--brand-active);
  background: var(--surface-glass-selected);
}

.wizard-dialog__body {
  min-width: 0;
  max-height: min(70vh, 44rem);
  overflow-y: auto;
  padding-inline-end: calc(var(--panel-padding) / 4);
  color: var(--text-primary);
}

.wizard-dialog__live {
  min-height: 0;
}

.wizard-dialog__validation {
  display: flex;
  flex-direction: column;
  gap: calc(var(--section-gap) / 4);
  margin-bottom: var(--section-gap);
  padding: calc(var(--panel-padding) / 2);
  border: 1px solid var(--status-danger);
  border-radius: var(--radius-md);
  color: var(--status-danger);
  background: var(--status-danger-soft);
}

.wizard-dialog__validation-description {
  color: var(--text-secondary);
}

.wizard-dialog__footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: calc(var(--section-gap) / 2);
}

@media (max-width: 760px) {
  .wizard-dialog {
    grid-template-columns: 1fr;
  }
}
</style>
