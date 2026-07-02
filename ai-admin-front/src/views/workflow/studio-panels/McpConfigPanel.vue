<template>
  <div class="node-specific-panel">
    <el-divider>MCP 调用</el-divider>
    <el-form-item label="服务引用">
      <el-input v-model="config.serverRef" placeholder="可选：MCP server 标识" />
    </el-form-item>
    <el-form-item label="工具名称">
      <el-input v-model="config.toolName" placeholder="例如：filesystem.read_file" />
    </el-form-item>
    <el-form-item label="入参映射">
      <el-input :model-value="formatMap(config.inputMapping)" type="textarea" :rows="6" placeholder="path = params.path&#10;query = input" @update:model-value="config.inputMapping = parseMap($event)" />
    </el-form-item>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CanvasNodeData, McpNodeConfig } from '@/types/studio'
import { formatMap, parseMap } from './panelUtils'

const props = defineProps<{ data: CanvasNodeData }>()

const config = computed<McpNodeConfig>(() => {
  props.data.mcpConfig ||= {
    serverRef: '',
    toolName: '',
    inputMapping: {},
  }
  return props.data.mcpConfig
})
</script>
