import claude from '@/assets/ai-coding-providers/claude-code.svg'
import codex from '@/assets/ai-coding-providers/codex.svg'
import cursor from '@/assets/ai-coding-providers/cursor.svg'
import gemini from '@/assets/ai-coding-providers/gemini-color.svg'
import openclaw from '@/assets/ai-coding-providers/openclaw-color.svg'
import trae from '@/assets/ai-coding-providers/trae.svg'

// Reuse the licensed, locally bundled artwork. No third-party image requests in business pages.
export const aiToolIcons = [
  { name: 'Claude Code', src: claude },
  { name: 'Codex', src: codex },
  { name: 'Cursor', src: cursor },
  { name: 'Gemini', src: gemini },
  { name: 'OpenClaw', src: openclaw },
  { name: 'Trae', src: trae },
] as const

export const connectionToolIcon: Record<string, string> = {
  CLAUDE_CODE: claude,
  CODEX: codex,
}
