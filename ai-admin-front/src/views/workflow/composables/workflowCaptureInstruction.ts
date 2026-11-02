/** The canvas renders start/end placeholders for a persisted empty GraphSpec. */
export function captureInstructionForEmptyDraft(
  instruction: unknown,
  canWrite: boolean,
  graphSpecJson: string | null | undefined,
  canvasKinds: readonly string[],
): string {
  if (typeof instruction !== 'string' || !instruction.trim() || !canWrite || !graphSpecJson) return ''
  if (canvasKinds.some((kind) => kind !== 'start' && kind !== 'end')) return ''
  try {
    const graph = JSON.parse(graphSpecJson)
    if (!Array.isArray(graph.nodes) || graph.nodes.length !== 0) return ''
  } catch {
    return ''
  }
  return instruction.trim().slice(0, 2000)
}
