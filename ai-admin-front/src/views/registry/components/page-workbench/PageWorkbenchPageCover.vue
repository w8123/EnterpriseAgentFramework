<script setup lang="ts">
defineProps<{
  title: string
  route?: string
  imageUrl?: string
}>()
</script>

<template>
  <span class="page-map-card-cover">
    <img
      v-if="imageUrl"
      :src="imageUrl"
      :alt="`${title}页面截图`"
      loading="lazy"
      decoding="async"
    />
    <span v-else class="page-map-card-cover__placeholder" aria-hidden="true">
      <span class="page-map-card-cover__browser">
        <span class="page-map-card-cover__browser-bar">
          <i />
          <i />
          <i />
          <small>{{ route || '页面预览' }}</small>
        </span>
        <span class="page-map-card-cover__browser-body">
          <i class="is-sidebar" />
          <span>
            <i class="is-title" />
            <i class="is-line" />
            <i class="is-line is-short" />
            <i class="is-panel" />
          </span>
        </span>
      </span>
    </span>
  </span>
</template>
