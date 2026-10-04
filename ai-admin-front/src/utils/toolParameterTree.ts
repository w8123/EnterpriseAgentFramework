import type { ToolParameter } from '@/types/tool'

function cloneValue<T>(value: T): T {
  if (value === undefined) return value
  try {
    return JSON.parse(JSON.stringify(value)) as T
  } catch {
    return value
  }
}

function syntheticParameter(name: string): ToolParameter {
  return { name, type: 'object', description: '', required: false, children: [] }
}

interface MutableParameterNode {
  name: string
  declaration: ToolParameter
  children: Map<string, MutableParameterNode>
}

function mutableNode(name: string): MutableParameterNode {
  return { name, declaration: syntheticParameter(name), children: new Map() }
}

function mergeDeclaration(node: MutableParameterNode, source: ToolParameter) {
  const children = Array.isArray(source.children) ? source.children : []
  node.declaration = { ...node.declaration, ...cloneValue(source), name: node.name, children: [] }
  for (const child of children) appendDeclaration(node.children, child)
}

function appendDeclaration(roots: Map<string, MutableParameterNode>, source: ToolParameter) {
  const segments = String(source?.name || '').trim().split('.').filter(Boolean)
  if (!segments.length) return
  const rootName = segments[0]
  let current: MutableParameterNode = roots.get(rootName) ?? mutableNode(rootName)
  if (!roots.has(rootName)) roots.set(rootName, current)
  for (let index = 1; index < segments.length; index += 1) {
    const name = segments[index]
    let child = current.children.get(name)
    if (!child) {
      child = mutableNode(name)
      current.children.set(name, child)
    }
    current = child
  }
  mergeDeclaration(current, source)
}

function materializeParameter(node: MutableParameterNode): ToolParameter {
  return {
    ...node.declaration,
    name: node.name,
    children: [...node.children.values()].map(materializeParameter),
  }
}

/**
 * Builds an ephemeral tree for dotted source declarations. The result is only
 * an authoring/view helper: callers must not write it back into the accepted
 * source contract or use it to invent fields.
 */
export function logicalToolParameters(parameters: readonly ToolParameter[] | null | undefined): ToolParameter[] {
  const roots = new Map<string, MutableParameterNode>()
  for (const source of parameters || []) appendDeclaration(roots, source)
  return [...roots.values()].map(materializeParameter)
}
