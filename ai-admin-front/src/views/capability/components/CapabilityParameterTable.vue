<template>
  <el-table :data="parameters" row-key="name" default-expand-all :tree-props="{ children: 'children' }" class="capability-parameters">
    <el-table-column prop="name" label="字段 / 类型" min-width="150">
      <template #default="{ row }"><div class="parameter-name"><code>{{ row.name }}</code><small>{{ row.type || '未声明类型' }}</small></div></template>
    </el-table-column>
    <el-table-column v-if="!output" label="位置" width="72"><template #default="{ row }">{{ parameterLocation(row.location) }}</template></el-table-column>
    <el-table-column v-if="!output" label="必填" width="56"><template #default="{ row }"><span :class="{ 'is-required': row.required }">{{ row.required ? '必填' : '可选' }}</span></template></el-table-column>
    <el-table-column label="说明" min-width="140"><template #default="{ row }">{{ row.description || '未提供说明' }}</template></el-table-column>
    <el-table-column label="来源补充" min-width="170">
      <template #default="{ row }">
        <div v-if="parameterMetadataItems(row.metadata).length" class="parameter-metadata">
          <span v-for="item in parameterMetadataItems(row.metadata)" :key="`${item.label}-${item.value}`"><strong>{{ item.label }}：</strong>{{ item.value }}</span>
        </div>
        <span v-else class="parameter-metadata--empty">未声明补充信息</span>
      </template>
    </el-table-column>
  </el-table>
</template>
<script setup lang="ts">
import type { ToolParameter } from '@/types/tool'
import { parameterLocation } from '../capabilityDetail'
import { parameterMetadataItems } from './capabilityParameterMetadata'
defineProps<{ parameters: ToolParameter[]; output?: boolean }>()
</script>
<style scoped lang="scss">
.capability-parameters { font-size: 12px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); }
.parameter-name { display: inline-grid; max-width: 100%; gap: 4px; vertical-align: middle; }
.parameter-name code { padding: 0; background: transparent; overflow-wrap: anywhere; color: var(--text-primary); font: 600 12px/1.5 ui-monospace, Consolas, monospace; }
.parameter-name small { overflow-wrap: anywhere; color: var(--text-secondary); font-size: 11px; }
.is-required { color: var(--status-warning); font-weight: 600; }
.parameter-metadata { display: grid; gap: 3px; color: var(--text-secondary); font-size: 12px; line-height: 1.55; overflow-wrap: anywhere; }
.parameter-metadata strong { color: var(--text-primary); font-weight: 600; }
.parameter-metadata--empty { color: var(--text-muted); font-size: 12px; }
</style>
