/** Shared vocabulary of the 「AI 工具接入」 page, used by the employee portal and the console. */
import claudeIcon from '@/assets/ai-coding-providers/claude-code.svg'
import codexIcon from '@/assets/ai-coding-providers/codex.svg'
import cursorIcon from '@/assets/ai-coding-providers/cursor.svg'
import traeIcon from '@/assets/ai-coding-providers/trae.svg'
import workbuddyIcon from '@/assets/ai-coding-providers/workbuddy-color.svg'
import doubaoIcon from '@/assets/ai-coding-providers/doubao-color.svg'
import geminiIcon from '@/assets/ai-coding-providers/gemini-color.svg'
import openclawIcon from '@/assets/ai-coding-providers/openclaw-color.svg'
import hermesIcon from '@/assets/ai-coding-providers/hermesagent.svg'

export type AiToolKind = 'CLAUDE_CODE' | 'CODEX' | 'OTHER'
export type AiClientId = AiToolKind | 'WORKBUDDY' | 'TRAE' | 'DOUBAO' | 'CURSOR' | 'GEMINI_CLI' | 'OPENCLAW' | 'HERMES'

export interface AiToolOption {
  value: AiClientId
  kind: AiToolKind
  label: string
  hint: string
  icon?: string
  monochrome?: boolean
  manual?: boolean
  guide: string
  docsUrl?: string
}

export const AI_TOOL_OPTIONS: AiToolOption[] = [
  { value: 'CLAUDE_CODE', kind: 'CLAUDE_CODE', label: 'Claude Code', hint: '编程助手', icon: claudeIcon,
    guide: '把安装提示词粘贴到 Claude Code，让它添加连接并检查工具列表。' },
  { value: 'CODEX', kind: 'CODEX', label: 'Codex', hint: '编程助手', icon: codexIcon,
    guide: '把安装提示词粘贴到 Codex，让它添加连接并检查工具列表。' },
  { value: 'OTHER', kind: 'OTHER', label: '其他 AI 工具', hint: '兼容 MCP',
    guide: '把安装提示词交给支持 MCP 的 AI 工具，按说明添加连接。' },
]

/** Client-specific guidance shares the existing OTHER connection protocol. */
export const PLATFORM_AI_TOOL_OPTIONS: AiToolOption[] = [
  AI_TOOL_OPTIONS[0]!, AI_TOOL_OPTIONS[1]!,
  { value: 'WORKBUDDY', kind: 'OTHER', label: 'WorkBuddy', hint: '办公助手', icon: workbuddyIcon, manual: true,
    guide: '打开「连接器 → 自定义连接器」，按生成的接入配置添加服务，再信任并启用连接器。',
    docsUrl: 'https://www.workbuddy.cn/docs/workbuddy/From-Beginner-to-Expert-Guide/Function-Description/Connector' },
  { value: 'TRAE', kind: 'OTHER', label: 'TRAE', hint: '编程助手', icon: traeIcon, manual: true,
    guide: '打开「设置 → MCP」，添加自定义远程服务，填写生成的服务地址与认证信息。',
    docsUrl: 'https://docs.trae.cn/ide_add-mcp-servers' },
  { value: 'DOUBAO', kind: 'OTHER', label: '豆包', hint: '桌面助手', icon: doubaoIcon, manual: true,
    guide: '请先确认当前客户端提供自定义 MCP 入口，并支持远程服务和认证请求头，再按生成的配置填写。' },
  { value: 'CURSOR', kind: 'OTHER', label: 'Cursor', hint: '编程助手', icon: cursorIcon, monochrome: true, manual: true,
    guide: '在 Cursor 的 MCP 设置中添加远程服务，填写生成的服务地址与认证信息。' },
  { value: 'GEMINI_CLI', kind: 'OTHER', label: 'Gemini CLI', hint: '命令行助手', icon: geminiIcon, manual: true,
    guide: '在 Gemini CLI 的 MCP 配置中添加远程 HTTP 服务，并从运行环境读取认证密钥。' },
  { value: 'OPENCLAW', kind: 'OTHER', label: 'OpenClaw', hint: '个人智能体', icon: openclawIcon, manual: true,
    guide: '在支持远程 MCP 的运行环境中添加服务，填写生成的地址与认证信息，再加载工具列表。' },
  { value: 'HERMES', kind: 'OTHER', label: 'Hermes Agent', hint: '个人智能体', icon: hermesIcon, monochrome: true, manual: true,
    guide: '在智能体的 MCP 配置中添加远程服务，填写生成的地址与认证信息，再加载工具列表。' },
  { ...AI_TOOL_OPTIONS[2]!, label: '企业自建智能体', hint: '兼容 MCP', manual: true,
    guide: '在企业智能体的 MCP 设置中添加远程服务，填写服务地址与认证信息。' },
]

export function aiToolLabel(kind?: string | null) {
  return AI_TOOL_OPTIONS.find(option => option.value === kind)?.label ?? 'AI 工具'
}

/** One row of 「访问凭据」. */
export interface AiCredentialRow {
  id: number
  name: string
  toolKind: string
  keyHint: string
  scope: string
  status: string
  createdAt?: string | null
  expiresAt?: string | null
  lastUsedAt?: string | null
}

/** One row of 「任务记录」. */
export interface AiCallRecord {
  id: number
  createdAt?: string | null
  credential: string
  action: string
  system?: string | null
  success: boolean
  latencyMs?: number | null
  error?: string | null
}

export type AiCapabilityMode = 'read' | 'trial' | 'approval' | 'write'

export interface AiCapabilityItem {
  name: string
  description?: string | null
  mode: AiCapabilityMode
}

export interface AiCapabilityGroup {
  key: string
  title: string
  subtitle?: string
  items: AiCapabilityItem[]
}

export function credentialStatusLabel(status: string) {
  return ({ ACTIVE: '使用中', REVOKED: '已停用', EXPIRED: '已过期', ROTATED: '已更换' } as Record<string, string>)[status]
    ?? status
}

export function formatAccessTime(value?: string | null) {
  if (!value) return '-'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN', { hour12: false })
}

export function isToday(value?: string | null, now = new Date()) {
  if (!value) return false
  const parsed = new Date(value)
  return !Number.isNaN(parsed.getTime()) && parsed.toDateString() === now.toDateString()
}

export interface InstallPromptInput {
  toolKind: AiToolKind
  toolLabel?: string
  manual?: boolean
  /** What the AI will be able to operate, e.g. 「芋道 ERP」 or 「ReachAI 平台」. */
  target: string
  /** Where the snippet goes and what to do with it. */
  configHint: string
  configContent: string
  /** Platform connections keep the secret in an environment variable instead of the snippet. */
  secretVariable?: string
  secret?: string
}

/**
 * The text the user pastes into their own AI tool, which then installs the connection
 * itself (the same idea as the Baota MCP panel's 「获取安装提示词」). It may contain the
 * secret, so it is only ever shown once and never stored by the page.
 */
export function buildInstallPrompt(input: InstallPromptInput) {
  const tool = input.toolLabel || aiToolLabel(input.toolKind)
  const lines = [
    input.manual
      ? `在 ${tool} 中接入「${input.target}」，按以下说明手动配置：`
      : `请帮我把 ReachAI 接入到你（${tool}）里，让你以我的身份操作「${input.target}」。按下面几步做：`,
    '',
    `1. ${input.configHint}`,
    '',
    '```',
    input.configContent.trimEnd(),
    '```',
  ]
  let step = 2
  if (input.secretVariable && input.secret) {
    lines.push('', `${step}. 把我的密钥保存为环境变量 ${input.secretVariable}，写进我的 shell 配置（如 ~/.zshrc 或 ~/.bashrc），不要写进项目文件：`,
      '', '```', `export ${input.secretVariable}="${input.secret}"`, '```')
    step += 1
  }
  lines.push('', input.manual
    ? `${step}. 在客户端中重新连接 MCP 服务，确认能加载工具列表，再发起一次有权限的查询以验证连接。`
    : `${step}. 重新加载 MCP 配置，列出 reachai 提供的工具，确认能看到它们后告诉我「已接好」，并用一两句话说说你现在能帮我做什么。`)
  lines.push('', '注意：上面的内容包含我的个人密钥，不要上传到代码仓库，也不要发给任何人。按平台列出的能力和影响范围操作；需要审核的操作先提交审核，批准后才执行。')
  return lines.join('\n')
}
