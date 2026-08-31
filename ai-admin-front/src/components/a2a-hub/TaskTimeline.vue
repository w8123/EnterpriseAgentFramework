<script setup lang="ts">
import type { A2aTaskEvent } from '@/types/a2aHub'
import { a2aFormatDate, a2aLabel, a2aTone } from '@/utils/a2aHub'

defineProps<{ events: A2aTaskEvent[] }>()
</script>

<template>
  <el-timeline class="task-timeline">
    <el-timeline-item
      v-for="event in events"
      :key="event.eventId"
      :timestamp="a2aFormatDate(event.createdAt)"
      placement="top"
      :type="a2aTone(event.toState)"
    >
      <div class="timeline-card">
        <div class="timeline-card__heading">
          <strong>#{{ event.sequence }} · {{ event.eventType }}</strong>
          <span v-if="event.fromState || event.toState">
            {{ a2aLabel(event.fromState) }} → {{ a2aLabel(event.toState) }}
          </span>
        </div>
        <p v-if="event.safeSummary">{{ event.safeSummary }}</p>
        <small>
          {{ event.actorType }} / {{ event.actorId }}
          <template v-if="event.resourceType"> · {{ event.resourceType }} {{ event.resourceId }}</template>
          <template v-if="event.runtimeSequence != null"> · runtime #{{ event.runtimeSequence }}</template>
        </small>
      </div>
    </el-timeline-item>
  </el-timeline>
</template>

<style scoped lang="scss">
.task-timeline {
  padding-left: 4px;
}

.timeline-card {
  padding: 10px 12px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  background: var(--surface-glass-control);
}

.timeline-card__heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.timeline-card__heading strong {
  color: var(--text-primary);
  font-size: 13px;
}

.timeline-card__heading span,
.timeline-card small {
  color: var(--text-muted);
  font-size: 11px;
}

.timeline-card p {
  margin: 7px 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.55;
}
</style>
