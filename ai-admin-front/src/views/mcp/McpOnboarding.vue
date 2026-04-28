<template>
  <div class="page-container">
    <div class="page-header">
      <h2>MCP 接入向导</h2>
    </div>

    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="一句话理解：把本仓的 Tool/Skill 通过 MCP 协议暴露，Cursor / Claude Desktop / Dify 等可一行配置接入"
      description="先在『MCP 暴露白名单』勾选要暴露的 Tool；再在『MCP Client』生成 API Key；最后把 Key 填到客户端配置即可。"
      style="margin-bottom: 12px"
    />

    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>① 服务端基础信息</template>
          <el-descriptions :column="1" border size="default">
            <el-descriptions-item label="协议">MCP (JSON-RPC 2.0)</el-descriptions-item>
            <el-descriptions-item label="协议版本">2024-11-05</el-descriptions-item>
            <el-descriptions-item label="HTTP 端点">
              <code>{{ jsonrpcUrl }}</code>
              <el-button size="small" :icon="DocumentCopy" link @click="copy(jsonrpcUrl)">复制</el-button>
            </el-descriptions-item>
            <el-descriptions-item label="Manifest">
              <code>{{ manifestUrl }}</code>
              <el-button size="small" :icon="DocumentCopy" link @click="copy(manifestUrl)">复制</el-button>
            </el-descriptions-item>
            <el-descriptions-item label="鉴权">
              <code>Authorization: Bearer &lt;API Key&gt;</code>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card shadow="never" style="margin-top: 12px">
          <template #header>② Cursor 接入</template>
          <p>编辑 <code>~/.cursor/mcp.json</code>：</p>
          <pre>{{ cursorExample }}</pre>
          <el-button :icon="DocumentCopy" @click="copy(cursorExample)">复制配置</el-button>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header>③ Claude Desktop 接入</template>
          <p>编辑 <code>claude_desktop_config.json</code>：</p>
          <pre>{{ claudeExample }}</pre>
          <el-alert type="warning" :closable="false" show-icon style="margin-top: 8px">
            Claude Desktop 目前仅支持 stdio MCP；本仓暂不暴露 stdio，可借助
            <a href="https://github.com/modelcontextprotocol/servers" target="_blank">mcp-proxy</a>
            把 HTTP 端点桥接到 stdio。
          </el-alert>
        </el-card>

        <el-card shadow="never" style="margin-top: 12px">
          <template #header>④ Dify / OpenClaw / 通用 HTTP MCP</template>
          <p>直接配置远端 MCP 服务地址：</p>
          <pre>{{ genericExample }}</pre>
        </el-card>

        <el-card shadow="never" style="margin-top: 12px">
          <template #header>⑤ curl 自检</template>
          <pre>{{ curlExample }}</pre>
          <el-button :icon="DocumentCopy" @click="copy(curlExample)">复制</el-button>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentCopy } from '@element-plus/icons-vue'

const origin = window.location.origin
const jsonrpcUrl = `${origin}/mcp/jsonrpc`
const manifestUrl = `${origin}/mcp/manifest`

const cursorExample = computed(() => `{
  "mcpServers": {
    "enterprise-agent-framework": {
      "url": "${jsonrpcUrl}",
      "headers": { "Authorization": "Bearer YOUR_API_KEY" }
    }
  }
}`)

const claudeExample = computed(() => `{
  "mcpServers": {
    "enterprise-agent-framework": {
      "command": "mcp-proxy",
      "args": ["--http", "${jsonrpcUrl}", "--header", "Authorization=Bearer YOUR_API_KEY"]
    }
  }
}`)

const genericExample = computed(() => `endpoint: ${jsonrpcUrl}
auth_type: bearer
api_key: YOUR_API_KEY`)

const curlExample = computed(() => `curl -X POST "${jsonrpcUrl}" \\
  -H "Authorization: Bearer YOUR_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'`)

function copy(text: string) {
  navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}
</script>

<style scoped lang="scss">
.page-container { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;
  h2 { margin: 0; font-size: 18px; } }
pre { background: #2b2d3a; color: #fff; padding: 12px; border-radius: 6px; overflow: auto; font-size: 12px; }
code { background: #f5f7fa; padding: 2px 6px; border-radius: 3px; }
</style>
