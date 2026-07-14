<template>
  <WorkbenchPage density="spacious">
    <PageHeader
      variant="workbench"
      domain="platform"
      eyebrow="MCP Onboarding"
      title="MCP 接入向导"
      description="从能力暴露、Client 凭证到外部工具配置，按步骤完成企业 MCP 接入与连通性自检。"
      density="spacious"
    />

    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="一句话理解：把本仓的 Tool / 粗粒度能力通过 MCP 协议暴露，Cursor / Claude Desktop / Dify 等可一行配置接入"
      description="先在『MCP 暴露白名单』勾选要暴露的 Tool；再在『MCP Client』生成 API Key；最后把 Key 填到客户端配置即可。"
    />

    <div class="mcp-onboarding__columns">
      <div class="mcp-onboarding__column">
        <WorkbenchPanel title="① 服务端基础信息" density="spacious">
          <el-descriptions :column="1" border size="default">
            <el-descriptions-item label="协议">MCP (JSON-RPC 2.0)</el-descriptions-item>
            <el-descriptions-item label="协议版本">2024-11-05</el-descriptions-item>
            <el-descriptions-item label="HTTP 端点">
              <code class="mcp-onboarding__inline-code">{{ jsonrpcUrl }}</code>
              <el-button size="small" :icon="DocumentCopy" link @click="copy(jsonrpcUrl)">复制</el-button>
            </el-descriptions-item>
            <el-descriptions-item label="Manifest">
              <code class="mcp-onboarding__inline-code">{{ manifestUrl }}</code>
              <el-button size="small" :icon="DocumentCopy" link @click="copy(manifestUrl)">复制</el-button>
            </el-descriptions-item>
            <el-descriptions-item label="鉴权">
              <code class="mcp-onboarding__inline-code">Authorization: Bearer &lt;API Key&gt;</code>
            </el-descriptions-item>
          </el-descriptions>
        </WorkbenchPanel>

        <WorkbenchPanel title="② Cursor 接入" density="spacious">
          <p>编辑 <code class="mcp-onboarding__inline-code">~/.cursor/mcp.json</code>：</p>
          <CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />
        </WorkbenchPanel>
      </div>

      <div class="mcp-onboarding__column">
        <WorkbenchPanel title="③ Claude Desktop 接入" density="spacious">
          <p>编辑 <code class="mcp-onboarding__inline-code">claude_desktop_config.json</code>：</p>
          <CodeSnippetBlock
            title="claude_desktop_config.json"
            :code="claudeExample"
            @copy="copy"
          />
          <el-alert type="warning" :closable="false" show-icon>
            Claude Desktop 目前仅支持 stdio MCP；本仓暂不暴露 stdio，可借助
            <a href="https://github.com/modelcontextprotocol/servers" target="_blank">mcp-proxy</a>
            把 HTTP 端点桥接到 stdio。
          </el-alert>
        </WorkbenchPanel>

        <WorkbenchPanel title="④ Dify / OpenClaw / 通用 HTTP MCP" density="spacious">
          <p>直接配置远端 MCP 服务地址：</p>
          <CodeSnippetBlock title="HTTP MCP" :code="genericExample" @copy="copy" />
        </WorkbenchPanel>

        <WorkbenchPanel title="⑤ curl 自检" density="spacious">
          <CodeSnippetBlock title="curl" :code="curlExample" @copy="copy" />
        </WorkbenchPanel>
      </div>
    </div>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentCopy } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import CodeSnippetBlock from '@/components/common/CodeSnippetBlock.vue'

const origin = window.location.origin
const jsonrpcUrl = `${origin}/mcp/jsonrpc`
const manifestUrl = `${origin}/mcp/manifest`

const cursorExample = computed(() => `{
  "mcpServers": {
    "reachai": {
      "url": "${jsonrpcUrl}",
      "headers": { "Authorization": "Bearer YOUR_API_KEY" }
    }
  }
}`)

const claudeExample = computed(() => `{
  "mcpServers": {
    "reachai": {
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
.mcp-onboarding__columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--section-gap);
  min-width: 0;
}

.mcp-onboarding__column {
  display: flex;
  flex-direction: column;
  gap: var(--section-gap);
  min-width: 0;
}

.mcp-onboarding__inline-code {
  overflow-wrap: anywhere;
  word-break: break-word;
}

@media (max-width: 1080px) {
  .mcp-onboarding__columns {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
