<template>
  <div class="node-specific-panel">
    <ConfigSection title="MCP 调用" description="选择服务与工具，绑定调用参数">
      <el-form-item label="服务引用">
        <el-input v-model="config.serverRef" placeholder="可选：MCP server 标识" />
      </el-form-item>
      <el-form-item label="工具名称">
        <el-input v-model="config.toolName" placeholder="例如：filesystem.read_file" />
      </el-form-item>
      <el-form-item label="入参映射">
        <el-input
          :model-value="formatMap(config.inputMapping)"
          type="textarea"
          :rows="6"
          placeholder="path = params.path&#10;query = input"
          @update:model-value="config.inputMapping = parseMap($event)"
        />
      </el-form-item>
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import ConfigSection from './ConfigSection.vue'
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
