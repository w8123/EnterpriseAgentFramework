<script setup lang="ts">
export interface WorkbenchSummaryCard {
  label: string
  value: string
  desc: string
  tone: string
}

defineProps<{
  cards: WorkbenchSummaryCard[]
}>()
</script>

<template>
  <section class="kpi-strip">
    <article v-for="item in cards" :key="item.label" class="kpi-item" :class="item.tone">
      <span>{{ item.label }}</span>
      <strong>{{ item.value }}</strong>
      <small>{{ item.desc }}</small>
    </article>
  </section>
</template>
