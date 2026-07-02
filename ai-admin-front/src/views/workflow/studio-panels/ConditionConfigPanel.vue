<template>
  <div class="node-specific-panel">
    <el-divider>条件分支</el-divider>
    <div class="field-table-head">
      <strong>路由分组</strong>
      <el-button size="small" type="primary" plain @click="addGroup">添加条件组</el-button>
    </div>
    <div v-for="(group, groupIndex) in config.groups" :key="group.id" class="condition-group">
      <div class="condition-group-head">
        <el-input v-model="group.id" placeholder="路由 ID，如 approved" />
        <el-select v-model="group.logic" style="width: 90px">
          <el-option label="AND" value="AND" />
          <el-option label="OR" value="OR" />
        </el-select>
        <el-button text type="danger" @click="config.groups.splice(groupIndex, 1)">删除组</el-button>
      </div>
      <div v-for="(item, index) in group.conditions" :key="index" class="condition-row">
        <el-input v-model="item.left" placeholder="左值：lastOutput.status" />
        <el-select v-model="item.operator" style="width: 150px">
          <el-option label="存在" value="exists" />
          <el-option label="为空" value="empty" />
          <el-option label="非空" value="not_empty" />
          <el-option label="等于" value="equals" />
          <el-option label="不等于" value="not_equals" />
          <el-option label="包含" value="contains" />
          <el-option label="不包含" value="not_contains" />
          <el-option label="大于" value="gt" />
          <el-option label="大于等于" value="gte" />
          <el-option label="小于" value="lt" />
          <el-option label="小于等于" value="lte" />
        </el-select>
        <el-input v-model="item.right" placeholder="右值" />
        <el-button text type="danger" @click="group.conditions.splice(index, 1)">删除</el-button>
      </div>
      <el-button size="small" text type="primary" @click="group.conditions.push({ left: 'lastOutput', operator: 'not_empty' })">添加条件</el-button>
    </div>
    <el-form-item label="默认分支">
      <el-input v-model="config.defaultRoute" placeholder="默认分支，如 else" />
    </el-form-item>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CanvasNodeData, ConditionNodeConfig } from '@/types/studio'

const props = defineProps<{ data: CanvasNodeData }>()

const config = computed<ConditionNodeConfig>(() => {
  props.data.conditionConfig ||= {
    groups: [{ id: 'matched', logic: 'AND', conditions: [{ left: 'lastOutput', operator: 'not_empty' }] }],
    defaultRoute: 'else',
  }
  return props.data.conditionConfig
})

function addGroup() {
  config.value.groups.push({
    id: `route_${config.value.groups.length + 1}`,
    logic: 'AND',
    conditions: [{ left: 'lastOutput', operator: 'not_empty' }],
  })
}
</script>
