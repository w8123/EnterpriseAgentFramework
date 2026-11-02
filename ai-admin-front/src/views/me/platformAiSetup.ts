import type { PlatformAiToolKind } from '@/api/platformAiConnections'

/** Setup instructions contain a variable reference, never the one-time secret. */
export function platformAiSetup(kind: PlatformAiToolKind, origin: string, connectionId: number, toolLabel = 'AI 工具') {
  const url = new URL('/mcp/platform', origin).toString()
  const serverName = `reachai_platform_${connectionId}`
  const variable = `REACHAI_PLATFORM_AI_TOKEN_${connectionId}`
  if (kind === 'CODEX') {
    return {
      variable,
      title: '将下面的配置加入 ~/.codex/config.toml',
      content: `[mcp_servers.${serverName}]\nurl = ${JSON.stringify(url)}\nbearer_token_env_var = "${variable}"`,
    }
  }
  if (kind === 'CLAUDE_CODE') {
    return {
      variable,
      title: '将下面的配置加入项目的 .mcp.json',
      content: JSON.stringify({
        mcpServers: {
          [serverName]: {
            type: 'http', url, headers: { Authorization: `Bearer \${${variable}}` },
          },
        },
      }, null, 2),
    }
  }
  return {
    variable,
    title: `在 ${toolLabel} 的自定义 MCP 设置中填写；密钥从环境变量读取，若客户端不能引用变量，请将其实际值填入客户端的私密凭据字段`,
    content: `传输方式：Streamable HTTP\n服务地址：${url}\n请求头：Authorization\n请求头值：Bearer <环境变量 ${variable} 的实际值>`,
  }
}
