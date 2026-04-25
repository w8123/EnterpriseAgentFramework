/** 将 tool_call_log.tool_name 转为 Trace 回放展示标签（含后端写入的 _trace:* 内部节点） */
export function traceNodeTitle(toolName: string): string {
  if (!toolName) return '未知节点'
  if (toolName.startsWith('_trace:llm.stream#')) {
    const n = toolName.split('#')[1] ?? '?'
    return `大模型调用（ReAct 第 ${n} 轮）`
  }
  if (toolName === '_trace:agentscope.run') return 'AgentScope 执行摘要'
  if (toolName === '_trace:embedding.encode') return '向量化（Tool 召回用）'
  if (toolName === '_trace:milvus.tool_search') return 'Milvus 向量检索（Tool 候选）'
  if (toolName === '_trace:tool_retrieval.failed') return 'Tool 语义召回失败'
  if (toolName.startsWith('_trace:')) return `内部链路：${toolName.replace('_trace:', '')}`
  return toolName
}

export function isInternalTraceSpan(toolName: string): boolean {
  return !!toolName && toolName.startsWith('_trace:')
}
