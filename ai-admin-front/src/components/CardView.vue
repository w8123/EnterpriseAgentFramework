<template>
  <div class="card-grid">
    <div
      v-for="(item, index) in items"
      :key="item[keyField] || index"
      class="card-item glass-card fade-in-up"
      :style="{ animationDelay: `${index * 0.04}s` }"
      @click="$emit('card-click', item)"
    >
      <div class="card-item-header">
        <div class="card-title-row">
          <slot name="card-icon" :item="item">
            <div class="card-icon" :style="{ background: iconGradient }">
              <el-icon :size="18"><component :is="icon" /></el-icon>
            </div>
          </slot>
          <div class="card-title-area">
            <h4 class="card-title">{{ item[titleField] }}</h4>
            <p v-if="item[subtitleField]" class="card-subtitle">{{ item[subtitleField] }}</p>
          </div>
        </div>
        <slot name="card-badge" :item="item">
          <el-tag
            v-if="statusField && item[statusField] !== undefined"
            :type="getStatusType(item[statusField])"
            size="small"
            effect="dark"
          >
            {{ getStatusLabel(item[statusField]) }}
          </el-tag>
        </slot>
      </div>

      <div v-if="descriptionField && item[descriptionField]" class="card-desc">
        {{ item[descriptionField] }}
      </div>

      <div class="card-meta">
        <slot name="card-meta" :item="item" />
      </div>

      <div class="card-tags" v-if="$slots['card-tags']">
        <slot name="card-tags" :item="item" />
      </div>

      <div class="card-footer">
        <slot name="card-actions" :item="item">
          <el-button link type="primary" size="small" @click.stop="$emit('edit', item)">
            编辑
          </el-button>
        </slot>
      </div>
    </div>

    <div v-if="items.length === 0" class="card-empty">
      <el-empty :description="emptyText" :image-size="80" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { Cpu } from '@element-plus/icons-vue'
import type { Component } from 'vue'

const props = withDefaults(defineProps<{
  items: any[]
  keyField?: string
  titleField?: string
  subtitleField?: string
  descriptionField?: string
  statusField?: string
  icon?: Component
  iconGradient?: string
  emptyText?: string
  statusMap?: Record<string, { type: string; label: string }>
}>(), {
  keyField: 'id',
  titleField: 'name',
  subtitleField: '',
  descriptionField: '',
  statusField: '',
  icon: Cpu,
  iconGradient: 'linear-gradient(135deg, #6366f1, #8b5cf6)',
  emptyText: '暂无数据',
  statusMap: () => ({}),
})

defineEmits<{
  'card-click': [item: any]
  edit: [item: any]
}>()

function getStatusType(val: any): '' | 'success' | 'warning' | 'info' | 'danger' {
  if (props.statusMap[val]) return props.statusMap[val].type as any
  if (val === true || val === 1 || val === 'enabled' || val === 'success') return 'success'
  if (val === false || val === 0 || val === 'disabled' || val === 'error') return 'danger'
  return 'info'
}

function getStatusLabel(val: any): string {
  if (props.statusMap[val]) return props.statusMap[val].label
  if (val === true || val === 1) return '启用'
  if (val === false || val === 0) return '停用'
  return String(val)
}
</script>

<style scoped lang="scss">
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.card-item {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 16px;
  padding: 20px;
  cursor: pointer;
  transition: all 0.25s ease;
  backdrop-filter: blur(12px);
  animation-fill-mode: both;

  &:hover {
    background: rgba(255, 255, 255, 0.05);
    border-color: rgba(99, 102, 241, 0.2);
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3), 0 0 20px rgba(99, 102, 241, 0.06);
    transform: translateY(-2px);
  }
}

.card-item-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
}

.card-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1;
}

.card-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
  box-shadow: 0 4px 8px rgba(99, 102, 241, 0.2);
}

.card-title-area {
  min-width: 0;
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: #e2e8f0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin: 0;
}

.card-subtitle {
  font-size: 12px;
  color: #64748b;
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-desc {
  font-size: 13px;
  color: #94a3b8;
  line-height: 1.5;
  margin-bottom: 12px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-meta {
  margin-bottom: 12px;
}

.card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
}

.card-footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.04);
}

.card-empty {
  grid-column: 1 / -1;
  padding: 40px 0;
}

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .card-item {
    background: #ffffff;
    border: 1px solid #ebeef5;
    backdrop-filter: none;

    &:hover {
      background: #ffffff;
      border-color: rgba(99, 102, 241, 0.2);
      box-shadow: 0 8px 32px rgba(0, 0, 0, 0.08), 0 0 20px rgba(99, 102, 241, 0.04);
    }
  }

  .card-title {
    color: #1e293b;
  }

  .card-subtitle {
    color: #94a3b8;
  }

  .card-desc {
    color: #64748b;
  }

  .card-footer {
    border-top: 1px solid #ebeef5;
  }
}
</style>
