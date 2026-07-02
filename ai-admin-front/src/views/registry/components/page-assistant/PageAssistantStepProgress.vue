<script setup lang="ts">
import type { WizardStepKey } from '@/views/registry/pageAssistantWizardViewModel'

export interface WizardStepItem {
  index: number
  key: WizardStepKey
  title: string
  desc: string
  done: boolean
}

defineProps<{
  steps: WizardStepItem[]
  displayedStep: WizardStepKey
}>()

const emit = defineEmits<{
  selectStep: [key: WizardStepKey]
  wheel: [event: WheelEvent]
}>()
</script>

<template>
  <section class="step-progress" aria-label="页面助手创建步骤" @wheel="emit('wheel', $event)">
    <button
      v-for="step in steps"
      :key="step.key"
      class="progress-step"
      :class="{ active: displayedStep === step.key, done: step.done }"
      type="button"
      :aria-current="displayedStep === step.key ? 'step' : undefined"
      @click="emit('selectStep', step.key)"
    >
      <span class="step-index">{{ step.index }}</span>
      <span>
        <strong>{{ step.title }}</strong>
        <small>{{ step.desc }}</small>
      </span>
    </button>
  </section>
</template>
